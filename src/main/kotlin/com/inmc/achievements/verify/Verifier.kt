package com.inmc.achievements.verify

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Condition
import com.inmc.achievements.achievement.Tier
import com.inmc.achievements.gui.AdminListMenu
import kr.inmc.core.event.InmcSignalEvent
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `/업적 관리 검증` — 서버 안에서 신호 → 진행 → 완료 → 기록 → 초기화 를 실제 길로 돌려 확인한다(드랍·상점 검증기와 같은 틀, 2026-10-08).
 *
 * - 검사용 업적(`zz_verify`)을 **같은 틱에 넣고 뺀다** — 저장 틱커가 다른 틱에 돌아 디스크에 닿지 않는다(커스텀아이템 검증기와 같은 성질).
 * - 보상은 없는 업적이다(돈·아이템이 나가지 않는다). 기록(claims)은 검사 끝에 초기화로 지운다.
 * - 신호는 진짜 core `InmcSignalEvent` 로 쏜다 — 다른 플러그인의 신호와 같은 길.
 */
class Verifier(private val ach: Achievements) {

    data class Result(val name: String, val failure: String?) {
        val skipped: Boolean get() = failure?.startsWith(SKIP) == true
    }

    private class Check(val name: String, val run: (Stage) -> String?)

    fun run(player: Player) {
        val stage = Stage(ach, player)
        val results = try {
            stage.setUp()
            CHECKS.map { check ->
                val failure = try {
                    check.run(stage)
                } catch (t: Throwable) {
                    "검증기 오류: " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
                }
                Result(check.name, failure)
            }
        } finally {
            stage.tearDown()
        }
        player.closeInventory()

        val failures = results.filter { it.failure != null && !it.skipped }
        val skips = results.filter { it.skipped }
        ach.tell(
            player, "verify-done",
            ach.ph().count((results.size - failures.size - skips.size).toLong()).amount(failures.size)
                .reason(if (skips.isEmpty()) "" else " · 건너뜀 ${skips.size}"),
        )
        for (f in failures) ach.tell(player, "verify-failure", ach.ph().reason("${f.name} — ${f.failure}"))
        for (s in skips) ach.tell(player, "verify-skipped", ach.ph().reason("${s.name} — ${s.failure!!.removePrefix(SKIP).trim()}"))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = ach.io.file("verify", "achievements-$stamp.txt")
        val text = buildString {
            appendLine("# inmc-achievements 검증 - ${LocalDateTime.now()} - ${player.name}")
            for (r in results) {
                appendLine((if (r.failure == null) "PASS " else if (r.skipped) "SKIP " else "FAIL ") + r.name + (r.failure?.let { " — $it" } ?: ""))
            }
        }
        ach.io.asyncRun {
            file.parentFile.mkdirs()
            kr.inmc.core.util.AtomicFiles.write(file, text)
        }
        ach.tell(player, "verify-report", ach.ph().reason("plugins/${ach.plugin.name}/verify/${file.name}"))
    }

    /** 검사용 업적 하나 — 신호 `zz_verify/check` 를 3번 받으면 완료, 보상 없음. */
    class Stage(val ach: Achievements, val player: Player) {
        val definition = Achievement(
            uid = UID, id = UID, display = "검증용",
            condition = Condition.Signal(source = SOURCE, type = TYPE),
            tiers = listOf(Tier(id = TIER, threshold = 3, label = "검증")),
            listed = false, countsTowardTotal = false,
        )

        fun setUp() {
            ach.registry.put(definition)?.let { error("검사용 업적을 넣지 못했습니다: $it") }
            ach.service.reindex()
        }

        fun fire(times: Int) {
            repeat(times) { InmcSignalEvent.fire(SOURCE, TYPE, player.uniqueId, "-", 1L, player) { emptyMap() } }
            ach.service.drainAll()
        }

        fun claimed(): Boolean = ach.claims.hasClaimed(player.uniqueId, UID, TIER)

        fun tearDown() {
            runCatching { ach.service.reset(player.uniqueId, UID) }
            ach.registry.remove(UID)
            ach.service.reindex()
        }
    }

    companion object {
        const val SKIP = "건너뜀:"
        const val UID = "zz_verify"
        const val TIER = "one"
        const val SOURCE = "zz_verify"
        const val TYPE = "check"

        private fun ok(condition: Boolean, failure: String): String? = if (condition) null else failure

        private val CHECKS: List<Check> = listOf(
            Check("검사용 업적이 색인됐다 — uid·id 로 찾힌다") { s ->
                ok(s.ach.registry.byUid(UID) != null, "uid 로 못 찾습니다") ?: ok(s.ach.registry.byId(UID) != null, "id 로 못 찾습니다")
            },
            Check("신호 2번 → 진행 2, 아직 완료 아님") { s ->
                s.fire(2)
                ok(s.ach.counters.count(s.player.uniqueId, UID) == 2L, "진행이 ${s.ach.counters.count(s.player.uniqueId, UID)} (2 여야)")
                    ?: ok(!s.claimed(), "3번 전인데 완료됐습니다")
            },
            Check("신호 1번 더 → 완료 → 기록(claim)") { s ->
                s.fire(1)
                ok(s.claimed(), "3번 받았는데 기록이 없습니다")
            },
            Check("완료 뒤 신호가 더 와도 기록은 하나") { s ->
                s.fire(1)
                ok(s.ach.claims.claims(s.player.uniqueId, UID).size == 1, "기록이 ${s.ach.claims.claims(s.player.uniqueId, UID).size}개")
            },
            Check("초기화 → 기록 없음 · 진행 0") { s ->
                s.ach.service.reset(s.player.uniqueId, UID)
                ok(!s.claimed(), "초기화했는데 기록이 남았습니다")
                    ?: ok(s.ach.counters.count(s.player.uniqueId, UID) == 0L, "진행이 ${s.ach.counters.count(s.player.uniqueId, UID)} (0 이어야)")
            },
            Check("관리 지급 → 기록 · 두 번째 지급은 거절 · 회수 → 없음") { s ->
                val def = s.definition
                ok(s.ach.service.grant(s.player.uniqueId, def, TIER), "지급이 실패했습니다")
                    ?: run { s.ach.service.drainAll(); ok(s.claimed(), "지급했는데 기록이 없습니다") }
                    ?: ok(!s.ach.service.grant(s.player.uniqueId, def, TIER), "이미 있는데 또 지급됐습니다")
                    ?: ok(s.ach.service.revoke(s.player.uniqueId, def), "회수가 실패했습니다")
                    ?: ok(!s.claimed(), "회수했는데 기록이 남았습니다")
            },
            Check("발전과제 토스트 — 등록이 켜져 있다") { s ->
                if (!s.ach.config.advancementToasts) return@Check "$SKIP 설정에서 토스트를 껐습니다"
                ok(s.ach.toasts.isEnabled(), "토스트를 쓸 수 없습니다(서버 API)")
            },
            Check("타이틀포지 다리 — 칭호 목록을 읽는다") { s ->
                if (!s.ach.titles.isEnabled) return@Check "$SKIP 타이틀포지가 없습니다"
                ok(s.ach.titles.listBadges().isNotEmpty(), "칭호 목록이 비었습니다")
            },
            Check("관리 화면이 열린다") { s ->
                AdminListMenu(s.ach, s.player).open(s.player)
                val opened = s.player.openInventory.topInventory.holder is AdminListMenu
                s.player.closeInventory()
                ok(opened, "관리 화면이 안 열렸습니다")
            },
        )
    }
}
