package com.inmc.achievements.api

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Condition
import kr.inmc.core.event.InmcSignalEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * 외부 플러그인이 업적에 닿는 창구.
 *
 * ## 준비는 **양방향**이다
 *
 * [AchievementsReadyEvent] 만으로는 **업적보다 늦게 켜지는** 플러그인이 이벤트를 놓친다.
 * 반대로 [isReady] 만 있으면 **업적 플러그인이 다시 켜질 때**(서버 재시작·플러그인 재적재)
 * 등록이 통째로 날아가고 다시 꽂을 계기가 없다. (`/업적 관리 리로드` 는 등록을 지우지 않는다.)
 * 둘 다 있어야 순서가 어느 쪽이어도 된다.
 *
 * ```kotlin
 * // 늦게 켜진 경우
 * if (AchievementsApi.isReady()) register()
 * // 업적이 (다시) 켜진 경우
 * @EventHandler fun on(e: AchievementsReadyEvent) = register()
 * ```
 *
 * ## `progress` 는 `Custom` 조건에만 통한다
 *
 * **조건마다 진행의 원천은 정확히 하나여야 한다.** 통계 업적을 여기로 밀어 올려도 5초 뒤
 * 스윕이 바닐라 값으로 덮어써서 **조용히 아무 일도 안 일어난다.** 그래서 그런 호출은
 * 거부하고 로그를 남긴다.
 *
 * ## 열쇠는 `id` 가 아니라 `uid` 다
 *
 * 업적 이름은 바뀐다. id 를 받으면 이름을 바꾼 순간 외부 호출이 끊기고, 지웠다 같은 이름으로
 * 만들면 **엉뚱한 업적에 붙는다.** uid 는 절대 안 바뀐다.
 */
object AchievementsApi {

    @Volatile
    private var host: Achievements? = null

    /** 플러그인이 붙일 때. 외부에서 부르지 않는다. */
    fun bind(achievements: Achievements?) {
        host = achievements
    }

    fun isReady(): Boolean = host?.ready == true

    // --- 등록 -----------------------------------------------------------------------

    /**
     * `Condition.Custom(kind, value)` 를 평가할 함수를 등록한다.
     *
     * 같은 `kind` 를 **다른 플러그인**이 등록하면 거부한다 — 허용하면 남의 플러그인이 조용히
     * 덮는다. 같은 플러그인이면 교체다.
     */
    fun registerCondition(owner: Plugin, kind: String, evaluator: (UUID, String) -> Long): Boolean =
        host?.custom?.registerCondition(owner, kind, evaluator) ?: false

    fun registerDiscovery(owner: Plugin, kind: String, evaluator: (UUID, String) -> Boolean): Boolean =
        host?.custom?.registerDiscovery(owner, kind, evaluator) ?: false

    /** **내려갈 때 반드시 부른다.** 람다가 그 플러그인의 클래스로더를 붙들고 있다. */
    fun unregisterAll(owner: Plugin) {
        host?.custom?.unregisterAll(owner)
    }

    // --- 진행 -----------------------------------------------------------------------

    /**
     * `Custom` 조건의 진행도를 올린다. 새 값을 돌려준다. 거부되면 null.
     *
     * [achievementUid] 는 `/업적 정보` 나 편집 화면에서 볼 수 있다.
     */
    fun progress(playerId: UUID, achievementUid: String, amount: Long): Long? {
        val ach = host ?: return null
        if (!ach.ready) return null
        val achievement = ach.registry.byUid(achievementUid) ?: run {
            ach.logger.warning("API progress: '$achievementUid' 라는 업적이 없습니다")
            return null
        }
        val condition = achievement.condition
        if (condition !is Condition.Custom) {
            ach.logger.warning(
                "API progress: '${achievement.id}' 의 조건은 커스텀이 아닙니다 " +
                    "- 통계·신호·상태 조건은 각자의 원천이 있어 여기서 올릴 수 없습니다",
            )
            return null
        }

        // 평가기가 등록된 종류는 그쪽이 원천이다. 여기서 또 밀면 두 원천이 섞여, 스윕이 평가기
        // 값으로 덮어쓰는 순간 밀어 넣은 것이 조용히 사라진다.
        if (ach.custom.knows(condition.kind)) {
            ach.logger.warning(
                "API progress: '${condition.kind}' 는 평가기가 등록돼 있어 평가기가 원천입니다 " +
                    "- progress 로 밀어 넣지 마세요",
            )
            return null
        }

        val count = ach.service.push(playerId, achievement, condition, amount)
        ach.service.evaluate(playerId, Bukkit.getPlayer(playerId), achievement, count, previous = count - amount)
        return count
    }

    /**
     * 신호를 쏜다. **업적을 몰라도 된다** — core 의 이벤트로 곧장 간다.
     *
     * [player] 기본값이 `Bukkit.getPlayer(playerId)` 라 온라인 여부를 신경 쓸 필요가 없다.
     */
    @JvmStatic
    @JvmOverloads
    fun signal(
        source: String,
        type: String,
        playerId: UUID,
        subject: String,
        amount: Long = 1L,
        data: Map<String, String> = emptyMap(),
        player: Player? = Bukkit.getPlayer(playerId),
    ) {
        InmcSignalEvent.fire(source, type, playerId, subject, amount, player) { data }
    }

    // --- 조회 -----------------------------------------------------------------------

    /** 이 사람이 그 업적(어느 단계든)을 달성했는가. */
    fun hasCompleted(playerId: UUID, achievementUid: String): Boolean =
        host?.claims?.claims(playerId, achievementUid)?.isNotEmpty() == true

    fun pointsOf(playerId: UUID): Int = host?.points?.pointsOf(playerId) ?: 0

    fun rankOf(playerId: UUID): String = host?.points?.rankOf(playerId).orEmpty()

    /** 등록된 업적의 `uid to id`. 외부 플러그인이 uid 를 찾을 때. */
    fun list(): List<Pair<String, String>> =
        host?.registry?.all()?.map { it.uid to it.id }.orEmpty()
}

/**
 * 업적 플러그인이 준비됐다. **외부 플러그인은 여기서 평가기를 등록한다.**
 *
 * enable 과 `/업적 관리 리로드` 뒤에 발행된다. 리로드는 등록을 지우지 않으므로 그때 다시
 * 등록해도 같은 소유자의 교체라 무해하다 — 받는 쪽이 enable 과 리로드를 구별할 필요가 없다.
 */
class AchievementsReadyEvent : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
