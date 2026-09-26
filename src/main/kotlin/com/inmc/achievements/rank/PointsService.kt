package com.inmc.achievements.rank

import com.inmc.achievements.Achievements
import kr.inmc.core.rank.Better
import kr.inmc.core.rank.RankConfig
import kr.inmc.core.rank.RankMode
import kr.inmc.core.rank.Rankable
import kr.inmc.core.reward.RewardTable
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 업적 점수와 랭크.
 *
 * ## 점수는 저장하지 않는다
 *
 * `claims ∩ 현재 단계` 의 합으로 **계산한다.** 누적 저장하면 관리자가 단계를 지웠을 때 유령
 * 점수가 영원히 남고, 그 차이를 감사할 방법이 없다. 단계를 지워서 점수가 줄어드는 것은
 * **옳고 설명 가능한** 동작이다.
 *
 * 메모리 캐시만 둔다 — 리로드가 언제든 고칠 수 있어야 한다.
 */
class PointsService(private val ach: Achievements) {

    private val cache = ConcurrentHashMap<UUID, Int>()

    /** 지금 점수. 캐시가 없으면 계산한다. */
    fun pointsOf(playerId: UUID): Int = cache.getOrPut(playerId) { compute(playerId) }

    /** 기록에서 다시 계산한다. **정의가 바뀌면 값이 달라지는 것이 정상이다.** */
    fun compute(playerId: UUID): Int {
        var total = 0
        for ((uid, tiers) in ach.claims.all(playerId)) {
            val achievement = ach.registry.byUid(uid) ?: continue
            for (tierId in tiers.keys) {
                total += achievement.tier(tierId)?.points ?: 0
            }
        }
        return total
    }

    fun rankOf(playerId: UUID): String = ach.config.rankOf(pointsOf(playerId))

    /**
     * 이 업적을 **끝까지** 달성했는가 — 단계가 있으면 전 단계.
     *
     * 목록 화면은 전 단계를 채워야 완료(✔)로 보여주는데 달성률이 "하나라도 받았으면 완료" 로
     * 세면, 같은 화면 안에서 "3/10 완료" 와 회색 칸 여덟 개가 어긋난다. 기준을 하나로 둔다.
     */
    fun isComplete(playerId: UUID, achievement: com.inmc.achievements.achievement.Achievement): Boolean {
        val claimed = ach.claims.claims(playerId, achievement.uid).keys
        return achievement.steps().all { it.id in claimed }
    }

    /** 끝까지 달성한 업적 수. 전체 개수에서 뺀 히든은 세지 않는다. */
    fun completedCount(playerId: UUID): Int =
        ach.registry.all().count { it.countsTowardTotal && isComplete(playerId, it) }

    /**
     * 한 사람의 점수를 다시 계산하고 순위표에 반영한다. 랭크가 올랐으면 그 이름을 돌려준다.
     *
     * 경고: **`RankBoard.put(0)` 은 `hasRecord = true` 를 무조건 세운다** — 0점짜리 행이
     * 순위표에 보인다. 그래서 0이면 `put` 이 아니라 뺀다.
     */
    fun refresh(playerId: UUID): String? {
        val before = cache[playerId]
        val points = compute(playerId)
        cache[playerId] = points

        val board = board()
        if (points > 0) {
            val name = Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString()
            ach.ranks.put(board, playerId, name, points.toLong())
        } else {
            ach.ranks.drop(board, playerId)
        }

        if (before == null) return null
        val wasRank = ach.config.rankOf(before)
        val nowRank = ach.config.rankOf(points)
        return nowRank.takeIf { it != wasRank && points > before }
    }

    /** 정의가 바뀌었을 때. 캐시를 통째로 버린다. */
    fun invalidate() {
        cache.clear()
    }

    fun forget(playerId: UUID) {
        cache.remove(playerId)
    }

    /**
     * 전원 재계산. **단계 추가·삭제·임계값 변경·업적 삭제 뒤에 부른다.**
     *
     * 평소에는 완료하는 사람만 갱신하지만, 그러면 오프라인 플레이어의 점수가 옛 정의 기준으로
     * 순위표에 남는다. 관리 작업이라 핫 패스가 아니고, 기록이 전부 메모리에 있어 파일 훑기도
     * 아니다.
     */
    fun rebuildAll(): Int {
        cache.clear()
        var touched = 0
        for (playerId in ach.claims.knownPlayers()) {
            refresh(playerId)
            touched++
        }
        return touched
    }

    fun boards(): List<Rankable> = listOf(board())

    private fun board(): Rankable = POINTS

    private companion object {

        /**
         * 업적 점수 순위표.
         *
         * `record()` 가 아니라 `put()` 으로 올린다 — 점수는 **사건의 누적이 아니라 시점의
         * 값**이라, 누적으로 올리면 갱신할 때마다 배로 뛴다.
         */
        val POINTS = object : Rankable {
            override val id: String = "achievement-points"
            override val displayName: String = "업적 점수"

            /**
             * 시즌 초기화를 끄고(`ResetSchedule` 기본이 없음) 최고 기록만 본다.
             * 점수는 시점의 값이라 시즌으로 끊을 것이 없다.
             */
            override val ranking: RankConfig = RankConfig(mode = RankMode.BEST_RECORD, minPlays = 1)
            override val rewards: RewardTable = RewardTable()
            override val recordUnit: String = "점"
            override val better: Better = Better.HIGHER
        }
    }
}
