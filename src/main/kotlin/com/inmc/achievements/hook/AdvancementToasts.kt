package com.inmc.achievements.hook

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.claim.CompletionSnapshot
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 바닐라 발전과제 토스트. **장식이고, 죽어도 업적은 100% 동작한다.**
 *
 * ## L키 목록으로는 쓰지 않는다
 *
 * paper-api 를 직접 확인한 결과다. 발전과제 **정의는 서버 전역**이고 `AdvancementDisplay` 는
 * 전부 읽기 전용이며, 플레이어별 손잡이는 `awardCriteria`/`revokeCriteria` 뿐이다. 즉
 * "누구에게 무엇을 보일지" 를 정하는 API 가 없다. 게다가 L키 화면은 **진행도 숫자를 못
 * 보여준다** — `나무 10,000개` 를 제대로 보이려면 조건을 1만 개 등록해야 한다.
 *
 * 그래서 목록은 우리 GUI 가 전담하고 여기서는 **토스트만** 쓴다.
 *
 * ## 지급하고 곧바로 회수한다 — 단, 같은 틱이면 안 된다
 *
 * 진행도 델타는 **틱 끝에 모아 보낸다.** 같은 틱에 award+revoke 하면 상쇄돼 **패킷이 아예
 * 안 나가고 토스트도 안 뜬다.** 오류도 로그도 없다. 그래서 [REVOKE_DELAY_TICKS] 뒤에 회수한다.
 *
 * ## 세대를 올리지 않는다
 *
 * `removeAdvancement` 는 javadoc 상 **persistent storage 에서만** 지우고, 실행 중인
 * 인스턴스에서 빼려면 `Server.reloadData()` 가 필요하다. `persist=false` 면 지울 대상이
 * 없어 **아무 일도 안 일어나고**, `reloadData()` 는 데이터팩·레시피·전리품표를 통째로 다시
 * 읽어 남의 플러그인 레시피까지 깨므로 **절대 부르지 않는다.**
 *
 * 그래서 **enable 때 한 번 등록**한다. 새로 만든 업적은 처음 보는 키라 그때그때 등록되지만,
 * **기존 업적의 아이콘·제목 편집은 재시작해야 반영된다.** 편집 화면이 그렇게 말한다.
 *
 * ## 서버가 품은 API 가 오래되면 하나씩
 *
 * 일괄 API `loadAdvancements(Map, boolean)` 는 paper-api 26.2 에 생겼다. 테섭 Leaf 26.2 build 46 은 26.1.2 를 품고 있어
 * `NoSuchMethodError` 가 났고, 그 뒤로 토스트가 한 번도 안 떴다(2026-10-08). 그 서버에서는 옛 단수 API
 * `loadAdvancement(key, json)` 로 하나씩 등록한다 — 그것은 **저장형**(`world/datapacks/bukkit`)이라 끌 때
 * [unregisterPersisted] 로 지워, 다음 켤 때 지금 정의로 다시 등록되게 한다. 크래시로 못 지웠으면 "이미 있음" 을 그대로 쓴다.
 *
 * 키는 `inmc_ach:<uid>` 다. uid 가 ASCII 라 `NamespacedKey` 에 그대로 들어간다 — 업적 이름은
 * 한글일 수 있고 그러면 **지급하는 순간** `IllegalArgumentException` 이 났을 것이다.
 */
class AdvancementToasts(private val ach: Achievements) {

    /** 첫 실패에 걸리고 다시 안 풀린다. 로그는 한 번만. */
    @Volatile
    var available: Boolean = false
        private set

    private val warned = AtomicBoolean(false)
    private val registered = HashSet<String>()

    /** 일괄 API(`loadAdvancements`)가 있나. null = 아직 안 불러 봄. 없는 서버(Leaf 26.2 build 46)는 하나씩 간다. */
    private var batchApi: Boolean? = null

    /** 하나씩 등록한 키 — 그 API 는 **저장형**(`world/datapacks/bukkit`)이라 끌 때 지워 둔다. */
    private val persisted = HashSet<NamespacedKey>()

    fun isEnabled(): Boolean = available && ach.config.advancementToasts

    /**
     * 정의 전부를 한 번에 등록한다. **일괄이 중요하다** — 하나씩 넣으면 그만큼 클라이언트에
     * 갱신이 나간다.
     */
    fun registerAll(achievements: List<Achievement>) {
        if (!ach.config.advancementToasts) return
        val batch = achievements.filter { it.uid !in registered }
            .associate { key(it.uid) to json(it) }
        if (batch.isEmpty()) {
            available = available || registered.isNotEmpty()
            return
        }
        runCatching {
            if (batchApi != false) {
                try {
                    @Suppress("DEPRECATION")
                    Bukkit.getUnsafe().loadAdvancements(
                        batch.mapKeys { it.key as net.kyori.adventure.key.Key },
                        false,
                    )
                    batchApi = true
                } catch (_: NoSuchMethodError) {
                    // 서버가 품은 API 가 컴파일 대상보다 오래되면(Leaf 26.2 build 46 = paper-api 26.1.2) 일괄 API 가 없다.
                    // 옛 단수 API 로 하나씩 — 저장형이라 [unregisterPersisted] 가 끌 때 지운다.
                    batchApi = false
                    loadOneByOne(batch)
                }
            } else {
                loadOneByOne(batch)
            }
            registered += achievements.map { it.uid }
            available = true
        }.onFailure { fail(it) }
    }

    private fun loadOneByOne(batch: Map<NamespacedKey, String>) {
        for ((key, json) in batch) {
            try {
                @Suppress("DEPRECATION")
                Bukkit.getUnsafe().loadAdvancement(key, json)
            } catch (e: IllegalArgumentException) {
                // 지난 세션이 저장해 둔 것(크래시로 못 지웠을 때) — 그대로 쓴다. 장식 편집은 다음 재시작에 반영된다.
                if (e.message?.contains("exist", ignoreCase = true) != true) throw e
            }
            persisted += key
        }
    }

    /** 끌 때 — 하나씩 등록한 것은 저장형이라 지워 둔다. 다음 켤 때 지금 정의로 새로 등록된다. */
    fun unregisterPersisted() {
        for (key in persisted) {
            @Suppress("DEPRECATION")
            runCatching { Bukkit.getUnsafe().removeAdvancement(key) }
        }
        persisted.clear()
    }

    /** 세션 중에 새로 만든 업적 하나. */
    fun registerOne(achievement: Achievement) {
        if (!ach.config.advancementToasts) return
        if (achievement.uid in registered) return
        registerAll(listOf(achievement))
    }

    /**
     * 토스트를 띄운다. **조건을 주고 두 틱 뒤에 회수한다.**
     *
     * 회수하는 이유는 L키 화면에 남지 않게 하기 위해서다. 완료된 발전과제는 `hidden` 이어도
     * 보인다.
     */
    fun show(player: Player, snapshot: CompletionSnapshot) {
        if (!isEnabled()) return
        val key = runCatching { key(snapshot.uid) }.getOrNull() ?: return
        val advancement = runCatching { Bukkit.getAdvancement(key) }.getOrNull() ?: return

        runCatching {
            val progress = player.getAdvancementProgress(advancement)
            for (criterion in progress.remainingCriteria.toList()) progress.awardCriteria(criterion)

            val id = player.uniqueId
            player.scheduler.runDelayed(
                ach.plugin,
                {
                    val online = Bukkit.getPlayer(id) ?: return@runDelayed
                    val again = online.getAdvancementProgress(advancement)
                    for (criterion in again.awardedCriteria.toList()) again.revokeCriteria(criterion)
                },
                null,
                REVOKE_DELAY_TICKS,
            )
        }.onFailure { fail(it) }
    }

    /**
     * `Throwable` 을 잡는다. `Exception` 이 아니다 — `UnsafeValues` 가 바뀌면
     * `NoSuchMethodError` 가 오고 그건 `Error` 다.
     */
    private fun fail(t: Throwable) {
        available = false
        if (warned.compareAndSet(false, true)) {
            ach.logger.warning(
                "발전과제 토스트를 쓸 수 없습니다 (" + t.javaClass.simpleName + ": " + t.message + ") " +
                    "- 업적 자체는 그대로 동작하고 타이틀·소리·공지로 알립니다",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun key(uid: String): NamespacedKey = NamespacedKey(NAMESPACE, uid)

    /**
     * 최소한의 발전과제 정의.
     *
     * `hidden: true` 와 `parent` 없음이 핵심이다 — 뿌리이면서 숨겨져 있어 L키 화면에 탭이
     * 생기지 않는다. 조건은 `minecraft:impossible` 하나뿐이라 **우리가 주지 않으면 절대로
     * 저절로 달성되지 않는다.**
     */
    private fun json(achievement: Achievement): String {
        val title = escape(achievement.display)
        val description = escape(achievement.description.firstOrNull().orEmpty().ifBlank { achievement.display })
        val icon = achievement.icon.key().toString()
        val frame = if (achievement.tiers.size > 1) "goal" else "task"
        return """
            {
              "display": {
                "icon": { "id": "$icon" },
                "title": { "text": "$title" },
                "description": { "text": "$description" },
                "frame": "$frame",
                "show_toast": true,
                "announce_to_chat": false,
                "hidden": true
              },
              "criteria": { "unlock": { "trigger": "minecraft:impossible" } }
            }
        """.trimIndent()
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")

    private companion object {
        const val NAMESPACE = "inmc_ach"

        /**
         * 같은 틱에 회수하면 진행도 델타가 상쇄돼 **패킷이 아예 안 나간다.**
         * 두 틱이면 토스트가 먼저 나가고, 클라이언트는 이미 뜬 토스트를 제 시간만큼 보여준다.
         */
        const val REVOKE_DELAY_TICKS = 2L
    }
}
