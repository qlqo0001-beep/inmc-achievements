package com.inmc.achievements

import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Celebration
import com.inmc.achievements.achievement.Discovery
import com.inmc.achievements.achievement.DiscoveryKind
import com.inmc.achievements.achievement.Retroactive
import com.inmc.achievements.claim.CompletionSnapshot
import com.inmc.achievements.claim.PayoutUnit
import com.inmc.achievements.claim.UnitType
import com.inmc.achievements.util.Sections
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 재검수에서 찾은 결함들의 회귀 방지.
 *
 * 전부 **오류 없이 조용히** 틀리던 것들이다.
 */
class LifecycleTest {

    private fun achievement(
        hidden: Boolean = false,
        discovery: Discovery = Discovery.AUTO,
        retroactive: Retroactive = Retroactive.SILENT,
    ) = Achievement(
        uid = "a1b2c3d4e5f6",
        id = "t",
        display = "t",
        hidden = hidden,
        discovery = discovery,
        retroactive = retroactive,
    )

    // --- 히든 AUTO -------------------------------------------------------------------

    @Test
    fun `AUTO 히든은 완료하는 순간 발견된다`() {
        // 이게 거짓이면 AUTO 히든은 영원히 완료되지 않는다 — 발견하려면 완료해야 하는데
        // 완료하려면 발견해야 한다. GUI 에서 히든을 켜면 기본이 AUTO 라 전부 그 상태였다.
        assertTrue(achievement(hidden = true).discoversOnCompletion())
    }

    @Test
    fun `다른 발견 조건의 히든은 완료로 발견되지 않는다`() {
        for (kind in DiscoveryKind.entries.filter { it != DiscoveryKind.AUTO }) {
            assertFalse(
                achievement(hidden = true, discovery = Discovery(kind, "x")).discoversOnCompletion(),
                "$kind",
            )
        }
    }

    @Test
    fun `히든이 아니면 발견과 무관하다`() {
        assertFalse(achievement(hidden = false).discoversOnCompletion())
    }

    // --- 소급 -----------------------------------------------------------------------

    @Test
    fun `처음 보는 사람이 이미 넘은 단계는 소급이다`() {
        // 통계 업적을 새로 만들면 조건을 이미 채운 사람들이 한꺼번에 달성한다.
        assertTrue(achievement().silentFor(threshold = 100, previous = null, live = false))
    }

    @Test
    fun `지난번 본 값 이하의 단계는 소급이다 — 새로 끼운 단계`() {
        // 1000 까지 한 사람에게 500 단계가 새로 생겼다. 이번에 넘은 것이 아니다.
        assertTrue(achievement().silentFor(threshold = 500, previous = 1000, live = false))
    }

    @Test
    fun `이번 변화로 넘은 단계는 소급이 아니다`() {
        assertFalse(achievement().silentFor(threshold = 100, previous = 99, live = false))
        assertFalse(achievement().silentFor(threshold = 100, previous = 0, live = false))
    }

    @Test
    fun `발견과 관리자 지급은 그 자체가 사건이라 소급이 아니다`() {
        assertFalse(achievement().silentFor(threshold = 100, previous = null, live = true))
    }

    @Test
    fun `연출함으로 두면 소급도 연출한다`() {
        assertFalse(
            achievement(retroactive = Retroactive.CELEBRATE)
                .silentFor(threshold = 100, previous = null, live = false),
        )
    }

    @Test
    fun `소급 정책이 왕복하고 기본값은 적지 않는다`() {
        for (policy in Retroactive.entries) {
            val config = YamlConfiguration()
            achievement(retroactive = policy).save(config.createSection("t"))
            val restored = Achievement.load("t", config.getConfigurationSection("t")!!)!!
            assertEquals(policy, restored.retroactive)
        }
        val config = YamlConfiguration()
        achievement(retroactive = Retroactive.SILENT).save(config.createSection("t"))
        assertFalse(config.contains("t.retroactive"), "기본값은 파일을 어지럽히지 않는다")
    }

    // --- 스냅샷 -----------------------------------------------------------------------

    private fun snapshot(silent: Boolean, highest: Boolean, firstClear: Boolean) = CompletionSnapshot(
        playerId = UUID.randomUUID(),
        uid = "a1b2c3d4e5f6",
        tierId = "t1",
        achievementName = "t",
        tierName = "",
        points = 0,
        units = listOf(PayoutUnit(UnitType.MONEY, money = 1.0)),
        celebration = Celebration(broadcast = "공지"),
        firstClearUnits = listOf(PayoutUnit(UnitType.MONEY, money = 100.0)),
        firstClear = firstClear,
        silent = silent,
        highest = highest,
    )

    @Test
    fun `여러 단계를 한 번에 넘으면 최고 단계만 연출한다`() {
        // 아니면 0 → 10000 한 번에 서버 공지가 세 번 나간다.
        assertTrue(snapshot(silent = false, highest = true, firstClear = false).celebrates())
        assertFalse(snapshot(silent = false, highest = false, firstClear = false).celebrates())
    }

    @Test
    fun `소급 완료는 연출하지 않는다`() {
        assertFalse(snapshot(silent = true, highest = true, firstClear = false).celebrates())
    }

    @Test
    fun `소급 완료는 서버 최초 경쟁에 들지 않는다`() {
        // 들면 새 업적을 만든 순간 먼저 훑인 사람이 '최초' 가 된다. 한 일이 아니라 순서다.
        assertFalse(snapshot(silent = true, highest = true, firstClear = true).competesForFirst())
        assertTrue(snapshot(silent = false, highest = true, firstClear = true).competesForFirst())
        assertFalse(snapshot(silent = false, highest = true, firstClear = false).competesForFirst())
    }

    @Test
    fun `최고 단계가 아니어도 최초 기록은 경쟁한다`() {
        // 기록은 단계마다 남는다. 공지만 최고 단계 하나다.
        assertTrue(snapshot(silent = false, highest = false, firstClear = true).competesForFirst())
    }

    // --- 거부된 정의 보존 ---------------------------------------------------------------

    @Test
    fun `읽지 못한 정의를 글자 그대로 되써 넣는다`() {
        // 저장은 빈 YAML 에 들고 있는 것만 적는다. 거부한 것을 되써 넣지 않으면 관리자가
        // 손으로 고치다 틀린 정의가 GUI 에서 아무거나 하나 고치는 순간 영구히 사라진다.
        val original = YamlConfiguration()
        original.loadFromString(
            """
            broken:
              uid: abcdef123456
              display: '깨진 업적'
              tiers:
                t1: { threshold: 10 }
                t2: { threshold: 10 }
              condition: { kind: STATISTIC, statistic: MOB_KILLS }
            """.trimIndent(),
        )

        val rewritten = YamlConfiguration()
        Sections.copy(original.getConfigurationSection("broken")!!, rewritten.createSection("broken"))

        val reread = YamlConfiguration()
        reread.loadFromString(rewritten.saveToString())
        assertEquals("깨진 업적", reread.getString("broken.display"))
        assertEquals(10, reread.getInt("broken.tiers.t2.threshold"))
        assertEquals("MOB_KILLS", reread.getString("broken.condition.statistic"))
        assertEquals("abcdef123456", reread.getString("broken.uid"))
    }
}
