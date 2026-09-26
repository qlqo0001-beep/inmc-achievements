package com.inmc.achievements.claim

import com.inmc.achievements.achievement.Celebration
import java.util.ArrayDeque
import java.util.UUID

/**
 * 완료 하나를 **그 자리에서 통째로 얼린 것.**
 *
 * 큐에 `uid + tierId` 만 넣으면 달성과 지급 사이에 관리자가 보상을 고쳤을 때 **옛 조건으로
 * 달성한 사람이 새 보상을 받는다.** 업적을 지웠으면 더 나쁘다 — 처리를 취소하면 "완료는
 * 기록된 사실" 이라는 원칙과 충돌하고, 취소하지 않으면 사라진 정의를 읽어야 한다.
 *
 * 그래서 조건을 만족한 자리에서 **정의를 다시 볼 일이 없게** 전부 옮겨 담는다.
 * [units] 는 `RewardService.resolve` 를 **이미 마친** 결과다 — `ROLL_ONE`/`ROLL_N` 번들은
 * 무작위라 나중에 다시 돌리면 빚진 것과 다른 것을 준다.
 */
class CompletionSnapshot(
    val playerId: UUID,
    val uid: String,
    val tierId: String,
    /** 화면과 메시지에 쓸 이름. 정의가 사라져도 이걸로 말한다. */
    val achievementName: String,
    val tierName: String,
    val points: Int,
    val units: List<PayoutUnit>,
    val celebration: Celebration,
    /**
     * 이 단계에 서버 최초 보상이 걸려 있다면 그 몫. **최초인지는 여기서 정하지 않는다.**
     *
     * 감지 시점에 "아직 아무도 없음" 을 보고 얼리면, 같은 배치에 든 두 사람이 **둘 다** 최초
     * 보상을 받는다. 누가 최초인지는 큐를 순차 처리하는 [com.inmc.achievements.AchievementService]
     * 가 파일에 먼저 적은 쪽으로 정한다.
     */
    val firstClearUnits: List<PayoutUnit> = emptyList(),
    val firstClearCelebration: Celebration? = null,
    /** 이 단계에 서버 최초 정의가 있는가. 보상이 비어 있어도 기록은 단계마다 남긴다. */
    val firstClear: Boolean = false,
    /** 소급으로 생긴 완료인가. 참이면 연출을 건너뛰고 서버 최초 경쟁에도 들지 않는다. */
    val silent: Boolean = false,
    /**
     * 한 번에 넘은 단계들 중 **최고 단계**인가.
     *
     * 0 → 10000 처럼 여러 단계를 한 번에 넘으면 보상은 전부 주되 토스트·타이틀·공지·
     * 최초 달성 공지는 이 스냅샷 하나에서만 터진다. 아니면 공지가 단계 수만큼 나간다.
     */
    val highest: Boolean = true,
    /** 완료 시점에 접속해 있었는가. 아니면 연출을 미루거나 버린다. */
    val online: Boolean = true,
    val at: Long = System.currentTimeMillis(),
) {
    /** 이 스냅샷이 실제로 연출할 것이 있는가. */
    fun celebrates(): Boolean = !silent && highest && !celebration.isSilent()

    /** 서버 최초 보상이 걸린 단계인가. 소급 완료는 경쟁하지 않는다 — 먼저 훑인 사람이 이기게 된다. */
    fun competesForFirst(): Boolean = !silent && firstClear
}

/**
 * 완료를 모았다가 **틱 끝에** 흘린다.
 *
 * ## 왜 즉시 처리하지 않는가
 *
 * 신호 리스너는 `CatchService.grant` 의 스택 **안에서** 돈다. 거기서 폭죽·공지·보상을
 * 그 자리에 터뜨리면 `grant` 가 돌아오기도 전에 일어나고, 보상이 또 신호를 쏘면 재귀한다.
 *
 * ## 이중 버퍼인 이유
 *
 * 보상 명령어가 다른 업적 조건을 만족시키면 완료가 **드레인 도중에** 들어온다.
 * `while (queue.isNotEmpty())` 면 같은 틱에 연쇄로 돌아 **틱당 상한이 무의미해진다.**
 * 드레인을 시작할 때 현재 큐를 통째로 가져가고 새 큐를 비워 둔다 — 그 사이에 생긴 것은
 * 반드시 **다음 틱**이다.
 */
class CompletionQueue {

    private var pending = ArrayDeque<CompletionSnapshot>()
    private val lock = Any()

    /** 이미 큐에 있는 (player, uid, tier). 같은 완료가 두 번 들어오는 것을 막는다. */
    private val inFlight = HashSet<String>()

    val size: Int get() = synchronized(lock) { pending.size }

    fun isEmpty(): Boolean = synchronized(lock) { pending.isEmpty() }

    /** 이미 대기 중이면 false. 스윕과 신호가 같은 완료를 동시에 발견할 수 있다. */
    fun offer(snapshot: CompletionSnapshot): Boolean = synchronized(lock) {
        val key = key(snapshot.playerId, snapshot.uid, snapshot.tierId)
        if (!inFlight.add(key)) return false
        pending.addLast(snapshot)
        true
    }

    fun isQueued(playerId: UUID, uid: String, tierId: String): Boolean = synchronized(lock) {
        inFlight.contains(key(playerId, uid, tierId))
    }

    /**
     * 이번 틱에 처리할 것을 떼어 온다. [limit] 개까지.
     *
     * 떼어 온 것은 `inFlight` 에서도 빠진다 — 처리 중에 같은 완료가 다시 감지되면 그때는
     * `ClaimStore` 가 이미 기록을 갖고 있어 걸러진다.
     */
    fun take(limit: Int): List<CompletionSnapshot> = synchronized(lock) {
        if (pending.isEmpty()) return emptyList()
        val taken = ArrayList<CompletionSnapshot>(minOf(limit, pending.size))
        var remaining = limit
        while (remaining > 0 && pending.isNotEmpty()) {
            val snapshot = pending.pollFirst() ?: break
            inFlight.remove(key(snapshot.playerId, snapshot.uid, snapshot.tierId))
            taken += snapshot
            remaining--
        }
        taken
    }

    /**
     * 남은 것을 **전부** 떼어 온다. 리로드·종료 직전에 쓴다.
     *
     * 일회성 신호(대회 우승 같은)는 다시 오지 않으므로, 큐를 메모리에 둔 채 서비스를
     * 갈아끼우면 그 완료가 **영원히 사라진다.**
     */
    fun drainAll(): List<CompletionSnapshot> = synchronized(lock) {
        val taken = pending.toList()
        pending = ArrayDeque()
        inFlight.clear()
        taken
    }

    private fun key(playerId: UUID, uid: String, tierId: String): String =
        "$playerId/$uid/$tierId"
}
