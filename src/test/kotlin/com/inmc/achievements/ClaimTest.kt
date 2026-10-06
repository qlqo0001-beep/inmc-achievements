package com.inmc.achievements

import com.inmc.achievements.achievement.Celebration
import com.inmc.achievements.claim.Claim
import com.inmc.achievements.claim.CompletionQueue
import com.inmc.achievements.claim.CompletionSnapshot
import com.inmc.achievements.claim.PayoutUnit
import com.inmc.achievements.claim.UnitState
import com.inmc.achievements.claim.UnitType
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 완료 큐.
 *
 * **이중 버퍼가 계약이다.** 보상 명령어가 다른 업적 조건을 만족시키면 완료가 드레인 도중에
 * 들어온다. `while (큐가 안 빔)` 이면 같은 틱에 연쇄로 돌아 틱당 상한이 무의미해진다.
 */
class CompletionQueueTest {

    private val player = UUID.randomUUID()

    private fun snapshot(uid: String, tier: String, id: UUID = player) = CompletionSnapshot(
        playerId = id,
        uid = uid,
        tierId = tier,
        achievementName = uid,
        tierName = tier,
        points = 0,
        units = emptyList(),
        celebration = Celebration.TOAST_ONLY,
    )

    @Test
    fun `같은 완료를 두 번 넣지 않는다`() {
        // 스윕과 신호가 같은 완료를 동시에 발견할 수 있다.
        val queue = CompletionQueue()

        assertTrue(queue.offer(snapshot("a", "t1")))
        assertFalse(queue.offer(snapshot("a", "t1")))
        assertEquals(1, queue.size)
    }

    @Test
    fun `틱당 상한만큼만 떼어 간다`() {
        val queue = CompletionQueue()
        repeat(5) { queue.offer(snapshot("a", "t$it")) }

        assertEquals(2, queue.take(2).size)
        assertEquals(3, queue.size)
    }

    @Test
    fun `드레인 도중에 들어온 것은 다음 차례다`() {
        // 이것이 재진입을 막는 전부다. take 가 떼어 간 뒤 들어온 것은 남아 있어야 한다.
        val queue = CompletionQueue()
        queue.offer(snapshot("a", "t1"))

        val batch = queue.take(10)
        queue.offer(snapshot("b", "t1"))

        assertEquals(1, batch.size)
        assertEquals(1, queue.size, "이번 배치에 섞이지 않는다")
    }

    @Test
    fun `떼어 간 것은 다시 넣을 수 있다`() {
        // 처리에 실패해 다시 감지되는 경우. 그때는 ClaimStore 가 기록으로 거른다.
        val queue = CompletionQueue()
        queue.offer(snapshot("a", "t1"))
        queue.take(1)

        assertTrue(queue.offer(snapshot("a", "t1")))
    }

    @Test
    fun `전부 비우기는 남김없이 가져간다`() {
        // 리로드·종료 직전. 일회성 신호는 다시 오지 않으므로 버리면 영원히 사라진다.
        val queue = CompletionQueue()
        repeat(7) { queue.offer(snapshot("a", "t$it")) }

        assertEquals(7, queue.drainAll().size)
        assertTrue(queue.isEmpty())
        assertTrue(queue.offer(snapshot("a", "t0")), "비운 뒤에는 같은 것도 다시 들어간다")
    }

    @Test
    fun `대기 중인지 물어볼 수 있다`() {
        // 이게 없으면 스윕이 같은 완료를 매 5초마다 큐에 밀어 넣는다.
        val queue = CompletionQueue()
        queue.offer(snapshot("a", "t1"))

        assertTrue(queue.isQueued(player, "a", "t1"))
        assertFalse(queue.isQueued(player, "a", "t2"))
    }

    @Test
    fun `초기화는 그 사람의 것만 버린다`() {
        // 남은 사람의 것은 순서 그대로 남고, 버린 자리는 다시 들어갈 수 있다.
        val queue = CompletionQueue()
        val other = UUID.randomUUID()
        queue.offer(snapshot("a", "t1"))
        queue.offer(snapshot("b", "t1"))
        val foreign = snapshot("a", "t1", other)
        queue.offer(foreign)

        assertEquals(2, queue.drop(player, null))
        assertEquals(1, queue.size)
        assertTrue(queue.isQueued(other, "a", "t1"))
        assertTrue(queue.offer(snapshot("a", "t1")), "버린 뒤에는 같은 것도 다시 들어간다")
    }

    @Test
    fun `초기화는 업적을 지정하면 그것만 버린다`() {
        val queue = CompletionQueue()
        queue.offer(snapshot("a", "t1"))
        queue.offer(snapshot("b", "t1"))

        assertEquals(1, queue.drop(player, "a"))
        assertFalse(queue.isQueued(player, "a", "t1"))
        assertTrue(queue.isQueued(player, "b", "t1"))
    }
}

/**
 * 기록의 직렬화.
 *
 * 기록은 **받은 보상의 근거**라 이 플러그인에서 가장 엄격하게 다룬다. 왕복이 깨지면
 * 되살린 뒤 같은 보상을 다시 주거나, 준 것을 안 준 것으로 본다.
 */
class ClaimSerializationTest {

    @Test
    fun `효과 단위 상태가 왕복한다`() {
        val claim = Claim(
            uid = "a3f2c1d0",
            tierId = "t1",
            at = 1_700_000_000_000L,
            units = mutableListOf(
                PayoutUnit(UnitType.MONEY, money = 1000.0, state = UnitState.GRANTED),
                PayoutUnit(UnitType.COMMAND, command = "say hi", state = UnitState.PENDING),
                PayoutUnit(UnitType.TITLE, title = "TITLE:벌목왕", state = UnitState.DEFERRED),
            ),
        )

        val restored = roundTrip(claim)

        assertEquals(3, restored.units.size)
        assertEquals(UnitState.GRANTED, restored.units[0].state)
        assertEquals(1000.0, restored.units[0].money)
        assertEquals(UnitState.PENDING, restored.units[1].state)
        assertEquals("say hi", restored.units[1].command)
        assertEquals(UnitState.DEFERRED, restored.units[2].state)
        assertEquals("TITLE:벌목왕", restored.units[2].title)
    }

    @Test
    fun `순서가 보존된다 — 위치가 곧 정체다`() {
        // id 를 두지 않는 대신 위치로 가리킨다. 10 이 2 보다 앞에 오면 엉뚱한 효과를 재시도한다.
        val units = (0 until 12).map {
            PayoutUnit(UnitType.COMMAND, command = "cmd$it")
        }.toMutableList()
        val claim = Claim("uid", "t1", 1L, units)

        val restored = roundTrip(claim)

        assertEquals(12, restored.units.size)
        restored.units.forEachIndexed { index, unit -> assertEquals("cmd$index", unit.command) }
    }

    @Test
    fun `미룬 연출 표시가 왕복한다`() {
        val claim = Claim("uid", "t1", 1L, mutableListOf(), celebrateOnJoin = true)

        assertTrue(roundTrip(claim).celebrateOnJoin)
    }

    @Test
    fun `DEFERRED 는 확인이 필요한 것으로 세지 않는다`() {
        // 섞으면 `/업적 미지급` 이 평소에도 오프라인 칭호로 가득 차서 진짜 이상한 것이 묻힌다.
        val deferred = Claim("uid", "t1", 1L, mutableListOf(
            PayoutUnit(UnitType.TITLE, title = "t", state = UnitState.DEFERRED),
        ))
        val pending = Claim("uid", "t2", 1L, mutableListOf(
            PayoutUnit(UnitType.MONEY, money = 1.0, state = UnitState.PENDING),
        ))

        assertFalse(deferred.hasUncertain())
        assertTrue(deferred.hasDeferred())
        assertTrue(pending.hasUncertain())
        assertFalse(pending.isSettled())
    }

    @Test
    fun `시각이 없으면 기록으로 읽지 않는다`() {
        // 손으로 고친 파일이 반쪽짜리 기록을 남겼을 때 그걸 완료로 다루면 안 된다.
        val config = YamlConfiguration()
        val section = config.createSection("t1")
        section.set("payout", null)

        assertNull(Claim.load("uid", "t1", section))
    }

    private fun roundTrip(claim: Claim): Claim {
        val config = YamlConfiguration()
        claim.save(config.createSection(claim.tierId))
        val reread = YamlConfiguration()
        reread.loadFromString(config.saveToString())
        return Claim.load(claim.uid, claim.tierId, reread.getConfigurationSection(claim.tierId)!!)!!
    }
}
