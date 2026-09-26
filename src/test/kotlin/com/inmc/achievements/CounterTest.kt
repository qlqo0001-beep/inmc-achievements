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

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
