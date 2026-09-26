package com.inmc.achievements

import com.inmc.achievements.achievement.AchievementRegistry
import com.inmc.achievements.claim.ClaimStore
import com.inmc.achievements.claim.CompletionQueue
import com.inmc.achievements.claim.PayoutService
import com.inmc.achievements.celebrate.Celebrations
import com.inmc.achievements.config.AchievementsConfig
import com.inmc.achievements.config.Messages
import com.inmc.achievements.hook.AdvancementToasts
import com.inmc.achievements.hook.LuckPermsHook
import com.inmc.achievements.hook.TitleForgeHook
import com.inmc.achievements.progress.CustomEvaluators
import com.inmc.achievements.progress.FirstClears
import com.inmc.achievements.progress.ProgressEngine
import com.inmc.achievements.progress.AchievementCounters
import com.inmc.achievements.rank.PointsService
import com.inmc.achievements.region.RegionStore
import com.inmc.achievements.util.Ph
import kr.inmc.core.config.ConfigService
import kr.inmc.core.input.ChatPrompt
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.EconomyHook
import kr.inmc.core.integration.MMOItemsHook
import kr.inmc.core.item.ItemMatcher
import kr.inmc.core.item.ItemResolver
import kr.inmc.core.rank.RankService
import kr.inmc.core.rank.Rankable
import kr.inmc.core.reward.Mailbox
import kr.inmc.core.reward.RewardHost
import kr.inmc.core.reward.RewardService
import kr.inmc.core.reward.RewardSettings
import kr.inmc.core.store.PlayerStore
import kr.inmc.core.util.Placeholders
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * 플러그인을 엮는 서비스 로케이터.
 *
 * 전부 한 번 만들고 `ach.<서비스>` 로 닿는다. 리로드는 volatile 한 설정 스냅샷만 갈아끼우고
 * **서비스는 절대 다시 만들지 않는다** — 리스너·화면·대기 중인 완료가 낡은 참조를 들게 된다.
 *
 * [RewardHost] 를 구현한다. core 가 요구하는 아홉 개를 채우는 대신 보상·우편함·랭킹이
 * 통째로 딸려 온다. 그 아홉은 **core 가 호스트에게 요구하는 것**이지 우리가 내놓는 표면이
 * 아니다 — 훅과 서비스는 전부 여기, 인터페이스 밖에 있다.
 */
class Achievements(override val plugin: JavaPlugin) : RewardHost {

    val logger: java.util.logging.Logger = plugin.logger

    override val io = ConfigService(plugin)

    override fun tell(target: CommandSender, key: String, ph: Placeholders?) =
        messages.send(target, key, ph as? Ph)

    override fun messageComponent(key: String, ph: Placeholders?): Component =
        messages.component(key, ph as? Ph)

    /**
     * core 가 쓰는 의미 이름을 이 플러그인의 토큰으로 옮긴다.
     * core 는 `{업적}` 을 모르고 알 필요도 없다 — `subject` 라고만 말한다.
     */
    override fun placeholders(vararg pairs: Pair<String, String>): Placeholders {
        val ph = Ph.of()
        for ((name, value) in pairs) when (name) {
            "subject" -> ph.achievement(value)
            "player" -> ph.player(value)
            "item" -> ph.achievement(value)
            "rank" -> ph.rank(value)
            "count", "season" -> ph.count(value.toLongOrNull() ?: 0L)
            else -> ph.raw(name, value)
        }
        return ph
    }

    // --- 연동 (전부 선택) --------------------------------------------------------
    val mmoItems = MMOItemsHook(logger)
    val customItems = CustomItemHook(logger)
    override val economy = EconomyHook(logger)
    val luckPerms = LuckPermsHook(logger)
    val titles = TitleForgeHook(logger)

    // --- 아이템 계층 --------------------------------------------------------------
    override val itemResolver = ItemResolver(mmoItems, customItems, logger)
    val itemMatcher = ItemMatcher(mmoItems, customItems)

    // --- 설정 (리로드로 통째 교체) --------------------------------------------------
    @Volatile
    var config: AchievementsConfig = AchievementsConfig.from(YamlConfiguration())

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    override val rewardSettings: RewardSettings get() = config

    // --- core 의 보상·랭킹 ----------------------------------------------------------
    override val mailbox = Mailbox(this)
    override val rewards = RewardService(this)
    override val ranks = RankService(this)

    val points = PointsService(this)

    override val rankables: List<Rankable> get() = points.boards()

    /**
     * 공유 플레이어 저장소. core 가 들고 있고 **플러시도 core 가 한다.**
     * 우리는 [AchievementCounters.syncTo] 까지만 하고 `flush()` 는 부르지 않는다.
     */
    val players: PlayerStore get() = kr.inmc.core.CorePlugin.get().players

    // --- 정의와 진행 ---------------------------------------------------------------
    val registry = AchievementRegistry(io, logger)
    val regions = RegionStore(io, logger)
    val counters = AchievementCounters()
    val claims = ClaimStore(io)
    val firstClears = FirstClears(io, logger)
    val custom = CustomEvaluators(logger)

    val queue = CompletionQueue()
    val engine = ProgressEngine(this)
    val payouts = PayoutService(this)
    val celebrations = Celebrations(this)
    val toasts = AdvancementToasts(this)

    /** 진행·발견·완료가 모이는 곳. */
    val service = AchievementService(this)

    // --- 입력 --------------------------------------------------------------------
    val prompts = ChatPrompt(this)

    /** 저장된 것을 다 읽기 전에는 false. 그 전의 진행은 전부 보류한다. */
    @Volatile
    var ready: Boolean = false
        private set

    fun markReady() {
        ready = true
    }

    /** 리로드 중에는 새 신호를 받지 않는다. 큐를 비우는 동안 들어오면 순서가 어긋난다. */
    @Volatile
    var accepting: Boolean = true

    fun ph(): Ph = Ph.of()
}
