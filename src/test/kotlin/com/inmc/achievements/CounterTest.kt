package com.inmc.achievements

import com.inmc.achievements.progress.AchievementCounters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 진행도 카운터.
 *
 * 몬스터의 `TriggerCounters` 에서 모양만 가져왔다. **가장 위험한 동작이 정반대**이고
 * 그게 올리지 않고 베낀 이유다.
 */
class CounterTest {

    private val player = UUID.randomUUID()

    @Test
    fun `같은 지문이면 누적한다`() {
        val counters = AchievementCounters()
        counters.increment(player, "a", "sig", 3)
        counters.increment(player, "a", "sig", 4)

        assertEquals(7L, counters.count(player, "a"))
    }

    @Test
    fun `지문이 바뀌면 0부터 다시 센다`() {
        // Signal·Custom 에서만 일어난다. 통계는 cache 를 쓴다.
        val counters = AchievementCounters()
        counters.increment(player, "a", "옛조건", 100)
        counters.increment(player, "a", "새조건", 1)

        assertEquals(1L, counters.count(player, "a"))
        assertEquals("새조건", counters.signature(player, "a"))
    }

    @Test
    fun `통계 캐시는 누적이 아니라 덮어쓰기다`() {
        // 바닐라가 세는 값이라 우리가 더하면 두 배가 된다.
        val counters = AchievementCounters()
        counters.cache(player, "a", "sig", 500, NOW)
        counters.cache(player, "a", "sig", 520, NOW + 1000)

        assertEquals(520L, counters.count(player, "a"))
        assertEquals(NOW + 1000, counters.readAt(player, "a"))
    }

    @Test
    fun `캐시는 덮기 전 값을 돌려준다 — 소급 판정의 근거다`() {
        val counters = AchievementCounters()

        assertNull(counters.cache(player, "a", "sig", 500, NOW), "처음 보는 것은 null — 이미 해 둔 것이다")
        assertEquals(500L, counters.cache(player, "a", "sig", 520, NOW + 1))
        assertEquals(520L, counters.cache(player, "a", "sig", 520, NOW + 2), "값이 안 바뀌어도 옛 값을 준다")
    }

    @Test
    fun `조건이 바뀐 캐시는 처음 보는 것으로 친다`() {
        // 참나무 900 을 들고 있다가 자작나무로 바뀌면, 자작나무 5000 은 **이미 해 둔 것**이다.
        // 옛 값(900)을 기준으로 삼으면 900~5000 사이 단계가 전부 방금 넘은 것처럼 연출된다.
        val counters = AchievementCounters()
        counters.cache(player, "a", "stat|MINE_BLOCK|OAK_LOG", 900, NOW)

        assertNull(counters.cache(player, "a", "stat|MINE_BLOCK|BIRCH_LOG", 5000, NOW + 1))
        assertEquals(5000L, counters.count(player, "a"))
    }

    @Test
    fun `발견은 키의 유무가 진실이다`() {
        // enum 순서값을 디스크에 적으면 나중에 상태를 끼울 때 전부 다른 뜻이 된다.
        val counters = AchievementCounters()

        assertFalse(counters.isDiscovered(player, "a"))
        assertEquals(0L, counters.discoveredAt(player, "a"))

        assertTrue(counters.discover(player, "a", NOW))
        assertTrue(counters.isDiscovered(player, "a"))
        assertEquals(NOW, counters.discoveredAt(player, "a"))

        assertFalse(counters.discover(player, "a", NOW + 1), "두 번째는 false")
        assertEquals(NOW, counters.discoveredAt(player, "a"), "시각이 덮이지 않는다")
    }

    @Test
    fun `바뀐 사람만 동기화 대상이 된다`() {
        val counters = AchievementCounters()
        assertFalse(counters.hasPending())

        counters.increment(player, "a", "sig", 1)
        assertTrue(counters.hasPending())
    }

    @Test
    fun `퇴장하면 메모리에서 내려가고 적재 여부가 달라진다`() {
        val counters = AchievementCounters()
        counters.increment(player, "a", "sig", 1)
        assertTrue(counters.isLoaded(player))

        counters.forget(player)
        assertFalse(counters.isLoaded(player), "이제 오프라인 경로를 타야 한다")
        assertEquals(0L, counters.count(player, "a"))
    }

    // --- 관리자 초기화 뒤 (읽어 오는 값은 지워지지 않는다 — 새로 한 것만 센다) -------------------

    @Test
    fun `초기화한 통계는 그때 값이 기준점이라 곧바로 다시 달성되지 않는다`() {
        // 나무 1000개 업적을 받은 사람을 초기화해도 바닐라 통계는 1200 그대로다.
        val counters = AchievementCounters()
        counters.cache(player, "a", "stat", 1200, NOW)
        counters.markReset(player, "a", rebase = true, discoverWait = false)

        assertEquals(0L, counters.sinceReset(player, "a", "stat", 1200), "초기화 직후 — 0")
        assertEquals(30L, counters.sinceReset(player, "a", "stat", 1230), "그 뒤에 캔 만큼만")
        assertEquals(1000L, counters.sinceReset(player, "a", "stat", 2200), "1000개를 새로 캐야 다시 달성")
    }

    @Test
    fun `초기화 직후에는 어느 단계도 열리지 않고 새로 한 만큼만 열린다`() {
        val achievement = com.inmc.achievements.achievement.Achievement(
            uid = "a1b2c3d4e5f6", id = "t", display = "t",
            tiers = listOf(
                com.inmc.achievements.achievement.Tier(id = "1", threshold = 100),
                com.inmc.achievements.achievement.Tier(id = "2", threshold = 1000),
            ),
        )
        val counters = AchievementCounters()
        counters.cache(player, achievement.uid, "stat", 1200, NOW)
        assertEquals(listOf("1", "2"), achievement.reached(1200).map { it.id }, "초기화 전 — 둘 다 받았다")

        counters.markReset(player, achievement.uid, rebase = true, discoverWait = false)
        assertTrue(achievement.reached(counters.sinceReset(player, achievement.uid, "stat", 1200)).isEmpty())
        assertEquals(listOf("1"), achievement.reached(counters.sinceReset(player, achievement.uid, "stat", 1300)).map { it.id })
    }

    @Test
    fun `상태 조건은 한 번 풀렸다가 다시 충족돼야 달성된다`() {
        // VIP 그룹인 채로 초기화하면 VIP 업적이 그 자리에서 다시 나가면 안 된다.
        val counters = AchievementCounters()
        counters.markReset(player, "vip", rebase = true, discoverWait = false)

        assertEquals(0L, counters.sinceReset(player, "vip", "state", 1), "충족한 채 — 0")
        assertEquals(0L, counters.sinceReset(player, "vip", "state", 1), "계속 충족해도 0")
        assertEquals(0L, counters.sinceReset(player, "vip", "state", 0), "풀림 — 기준점이 0으로")
        assertEquals(1L, counters.sinceReset(player, "vip", "state", 1), "다시 충족 — 달성")
    }

    @Test
    fun `초기화하지 않은 것은 읽은 값 그대로이고 빈 항목을 만들지 않는다`() {
        val counters = AchievementCounters()
        assertEquals(500L, counters.sinceReset(player, "a", "stat", 500))
        assertFalse(counters.isLoaded(player), "없는 사람을 만들면 빈 껍데기가 저장된 진행도를 덮는다")
    }

    @Test
    fun `기준점 계산`() {
        assertEquals(AchievementCounters.Rebased(700, 0), AchievementCounters.rebase(0, true, null, "s", 700))
        assertEquals(AchievementCounters.Rebased(700, 50), AchievementCounters.rebase(700, false, "s", "s", 750))
        assertEquals(AchievementCounters.Rebased(0, 750), AchievementCounters.rebase(700, false, "옛조건", "s", 750), "조건이 바뀌면 기준점을 버린다")
        assertEquals(AchievementCounters.Rebased(300, 0), AchievementCounters.rebase(700, false, "s", "s", 300), "값이 내려가면 기준점도")
        assertEquals(AchievementCounters.Rebased(5, 0), AchievementCounters.rebase(0, true, "옛조건", "s", 5), "초기화 직후가 조건 변경보다 먼저")
    }

    @Test
    fun `초기화 뒤 지역 발견은 한 번 벗어날 때까지 기다린다`() {
        val counters = AchievementCounters()
        counters.markReset(player, "secret", rebase = false, discoverWait = true)
        assertTrue(counters.discoverWaiting(player, "secret"))

        counters.releaseDiscoverWait(player, "secret")
        assertFalse(counters.discoverWaiting(player, "secret"))
    }

    @Test
    fun `발견하면 기다림도 끝난다`() {
        // 신호·아이템처럼 사건으로 발견된 것은 기다림과 무관하다.
        val counters = AchievementCounters()
        counters.markReset(player, "secret", rebase = false, discoverWait = true)
        assertTrue(counters.discover(player, "secret", NOW))
        assertFalse(counters.discoverWaiting(player, "secret"))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
