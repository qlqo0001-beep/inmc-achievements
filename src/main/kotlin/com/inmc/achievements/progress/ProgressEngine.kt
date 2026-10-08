package com.inmc.achievements.progress

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Condition
import com.inmc.achievements.achievement.StateKind
import com.inmc.achievements.achievement.Tier
import com.inmc.achievements.claim.Claim
import com.inmc.achievements.claim.CompletionSnapshot
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 진행도를 읽고, 무엇이 새로 열렸는지 정한다.
 *
 * **여기서는 지급하지 않는다.** 완료를 발견하면 [CompletionSnapshot] 으로 얼려 큐에 넣는 것이
 * 전부다. 지급은 틱 끝에 [com.inmc.achievements.claim.PayoutService] 가 한다 — 신호 리스너가
 * 남의 플러그인 스택 안에서 도는데 거기서 보상을 터뜨리면 재귀하고, 그 보상이 또 신호를 쏜다.
 */
class ProgressEngine(private val ach: Achievements) {

    /**
     * 한 사람의 한 업적 진행도.
     *
     * [Progress.Unavailable] 은 **0이 아니다.** 평가기가 아직 안 붙었거나 참조가 끊긴
     * 상태이고, 0으로 다루면 그 업적이 오류 없이 영영 완료되지 않는다. 부르는 쪽은
     * 완료도 초기화도 일으키지 않고 넘어간다.
     */
    sealed interface Progress {
        data object Unavailable : Progress
        data class Known(val count: Long) : Progress
    }

    // --- 읽기 -----------------------------------------------------------------------

    /**
     * 지금 진행도. 통계는 바닐라에서 **직접 읽고**, 나머지는 저장된 값을 본다.
     *
     * [player] 가 null 이면 오프라인이다 — 통계는 `OfflinePlayer` 로 읽을 수 없으므로
     * 마지막으로 읽어둔 캐시를 돌려준다.
     */
    fun progressOf(playerId: UUID, player: Player?, achievement: Achievement): Progress {
        val condition = achievement.condition ?: return Progress.Unavailable
        return when (condition) {
            is Condition.Stat -> statProgress(playerId, player, achievement, condition)
            is Condition.Signal -> stored(playerId, achievement, condition)
            is Condition.Custom -> customProgress(playerId, achievement, condition)
            is Condition.State -> stateProgress(playerId, player, achievement, condition)
        }
    }

    /**
     * 관리자 초기화의 기준점을 뺀다. 통계·상태·평가기 커스텀은 우리가 세지 않고 **읽어 오는** 값이라
     * `/업적 관리 초기화` 로 지워지지 않는다 — 그대로 두면 다음 스윕이 곧바로 다시 달성시켜 보상이 또 나간다.
     * 초기화 뒤에 새로 한 만큼만 센다([com.inmc.achievements.progress.AchievementCounters.rebase]).
     * 초기화한 적 없는 대부분은 그대로 돌려준다.
     */
    private fun sinceReset(playerId: UUID, achievement: Achievement, condition: Condition, raw: Long): Long =
        if (ach.counters.isLoaded(playerId)) {
            ach.counters.sinceReset(playerId, achievement.uid, condition.signature, raw)
        } else {
            ach.counters.sinceResetOffline(ach.players, playerId, achievement.uid, condition.signature, raw)
        }

    /**
     * 저장된 진행도와 그 지문. **메모리에 없는 사람은 공유 저장소에서 읽는다.**
     *
     * 메모리만 보면 오프라인 플레이어는 늘 0으로 읽힌다 — 오프라인 대회 우승으로 히든 업적이
     * 열렸을 때 이미 쌓인 진행도로 완료돼야 하는데 0이라 안 된다.
     */
    private fun saved(playerId: UUID, uid: String): Pair<String?, Long> =
        if (ach.counters.isLoaded(playerId)) {
            ach.counters.signature(playerId, uid) to ach.counters.count(playerId, uid)
        } else {
            ach.counters.storedSignature(ach.players, playerId, uid) to
                ach.counters.storedCount(ach.players, playerId, uid)
        }

    private fun stored(playerId: UUID, achievement: Achievement, condition: Condition): Progress {
        val (signature, count) = saved(playerId, achievement.uid)
        // 지문이 다르면 진행도는 옛 조건의 것이다. 0부터 본다.
        if (signature != null && signature != condition.signature) return Progress.Known(0L)
        return Progress.Known(count)
    }

    private fun statProgress(
        playerId: UUID,
        player: Player?,
        achievement: Achievement,
        condition: Condition.Stat,
    ): Progress {
        if (!condition.isWellFormed()) return Progress.Unavailable
        if (player == null) {
            // OfflinePlayer 에는 getStatistic 이 없다. 마지막으로 본 값이 최선이고,
            // 그 값이 다른 조건의 것이면 모른다고 답한다 — 다른 통계의 숫자를 진행도라고
            // 보여주면 안 된다.
            val (signature, count) = saved(playerId, achievement.uid)
            if (signature != condition.signature) return Progress.Unavailable
            return Progress.Known(count)
        }
        val value = readStatistic(player, condition) ?: return Progress.Unavailable
        // 오프라인 분기의 캐시는 이미 기준점을 뺀 값이다 — 스윕이 뺀 값을 캐시에 적는다.
        return Progress.Known(sinceReset(playerId, achievement, condition, value))
    }

    /**
     * 바닐라 카운터를 읽는다. **인자 수는 정의 저장 시점에 검사했으므로** 여기서 터질 일이
     * 없지만, 손으로 고친 YAML 이 들어올 수 있어 감싼다 — 한 사람이 터져서 스윕의 나머지가
     * 건너뛰어지면 안 된다. 대상이 여럿이면 합이고, 하나라도 못 읽으면 모른다(일부만 더한 값을 진행도라고 보이지 않는다).
     */
    fun readStatistic(player: Player, condition: Condition.Stat): Long? = runCatching {
        when {
            condition.materials.isNotEmpty() ->
                condition.materials.sumOf { player.getStatistic(condition.statistic, it).toLong() }
            condition.entities.isNotEmpty() ->
                condition.entities.sumOf { player.getStatistic(condition.statistic, it).toLong() }
            else -> player.getStatistic(condition.statistic).toLong()
        }
    }.getOrNull()

    private fun customProgress(
        playerId: UUID,
        achievement: Achievement,
        condition: Condition.Custom,
    ): Progress {
        // 원천은 하나다. 평가기가 붙어 있으면 그쪽이 원천이고(스윕이 끌어온다), 없으면
        // `AchievementsApi.progress` 로 밀어 넣은 저장값이 원천이다.
        if (ach.custom.knows(condition.kind)) {
            val pulled = ach.custom.evaluate(condition.kind, playerId, condition.value)
            // 평가기가 있는데 실패했다 — 모른다. 저장값으로 떨어지면 두 원천이 섞인다.
            return if (pulled != null) Progress.Known(sinceReset(playerId, achievement, condition, pulled)) else Progress.Unavailable
        }
        // 밀어 넣은 적이 없으면 0이 아니라 "모름" 이다. 아무도 이 종류를 모르는 상태와 구별이
        // 안 되기 때문이다 — 0으로 보이면 영영 조용히 미완료가 된다.
        val (signature, count) = saved(playerId, achievement.uid)
        return if (signature == condition.signature) Progress.Known(count) else Progress.Unavailable
    }

    private fun stateProgress(
        playerId: UUID,
        player: Player?,
        achievement: Achievement,
        condition: Condition.State,
    ): Progress {
        val satisfied: Boolean? = when (condition.kind) {
            StateKind.ACHIEVEMENT -> {
                val target = ach.registry.byUid(condition.value) ?: return Progress.Unavailable
                ach.claims.claims(playerId, target.uid).isNotEmpty()
            }
            StateKind.GROUP -> ach.luckPerms.inGroup(playerId, player, condition.value)
            StateKind.PERMISSION -> player?.hasPermission(condition.value)
            StateKind.REGION -> player?.let { ach.regions.contains(condition.value, it.location) }
            StateKind.RANK_TOP -> rankTop(playerId, condition.value)
            StateKind.ITEM_HELD -> player?.let { holding(it, condition.value) }
        }
        // 초기화 때 충족돼 있었으면 기준점이 1 — 한 번 풀렸다가(기준점이 0으로) 다시 충족돼야 달성된다.
        return when (satisfied) {
            null -> Progress.Unavailable
            true -> Progress.Known(sinceReset(playerId, achievement, condition, 1L))
            false -> Progress.Known(sinceReset(playerId, achievement, condition, 0L))
        }
    }

    /**
     * `보드id:등수`. 그 보드가 없으면 판단하지 않는다.
     *
     * **이 플러그인의 랭킹만 본다.** `RankService` 는 플러그인마다 따로 있고 저장소도
     * 각자라, 낚시나 숫자야구의 순위표를 여기서 물을 길이 없다. 그쪽 순위를 조건으로
     * 쓰고 싶으면 그 플러그인이 `AchievementsApi` 로 `Custom` 평가기를 등록하면 된다.
     */
    private fun rankTop(playerId: UUID, raw: String): Boolean? {
        val parts = raw.split(':', limit = 2)
        if (parts.size != 2) return null
        val place = parts[1].trim().toIntOrNull()?.coerceAtLeast(1) ?: return null
        val board = ach.rankables.firstOrNull { it.id.equals(parts[0].trim(), ignoreCase = true) }
            ?: return null
        return ach.ranks.top(board, place).any { it.id == playerId }
    }

    /** `inmc:아이디` · `mmoitems:타입:아이디` · 재질 이름. 못 읽으면 안 든 것으로 본다. */
    private fun holding(player: Player, reference: String): Boolean {
        val ref = kr.inmc.core.item.ItemRef.parse(reference)
        if (ref is kr.inmc.core.item.ItemRef.None) return false
        val held = player.inventory.itemInMainHand
        if (held.type.isAir) return false
        return ach.itemResolver.identifyAll(held).any { it == ref }
    }

    // --- 완료 판정 --------------------------------------------------------------------

    /**
     * 이 진행도로 **새로** 열린 단계들. 이미 기록이 있는 것은 빠진다.
     *
     * 여러 단계를 한 번에 넘을 수 있다(0 → 10000). **보상은 전부 주고 연출은 최고 단계만**
     * 터뜨린다 — 아니면 `/업적 지급` 한 번에 서버 공지가 세 번 나간다.
     */
    fun newlyReached(playerId: UUID, achievement: Achievement, count: Long): List<Tier> =
        achievement.reached(count).filter { tier ->
            !ach.claims.hasClaimed(playerId, achievement.uid, tier.id) &&
                !ach.queue.isQueued(playerId, achievement.uid, tier.id)
        }

    /**
     * 완료를 **그 자리에서 얼린다.**
     *
     * `RewardService.resolve` 를 여기서 끝내는 것이 핵심이다 — `ROLL_ONE`/`ROLL_N` 번들은
     * 무작위라 나중에 다시 돌리면 빚진 것과 다른 것을 준다.
     */
    fun snapshot(
        playerId: UUID,
        player: Player?,
        achievement: Achievement,
        tier: Tier,
        highest: Boolean,
        silent: Boolean,
    ): CompletionSnapshot {
        // 이미 누가 가져간 최초는 얼릴 필요도 없다. 다만 **아직 아무도 없다고 해서 이 사람이
        // 최초라는 뜻은 아니다** — 같은 배치의 다른 사람이 먼저일 수 있고, 그건 처리할 때 정한다.
        val firstClear = tier.firstOnServer
            ?.takeIf { ach.firstClears.isUnclaimed(achievement.uid, tier.id) }

        return CompletionSnapshot(
            playerId = playerId,
            uid = achievement.uid,
            tierId = tier.id,
            achievementName = achievement.display,
            tierName = tier.label.ifBlank { defaultTierName(achievement, tier) },
            points = tier.points,
            units = ach.payouts.freeze(tier.rewards, tier.title),
            // 단계 전용 연출이 없으면 업적의 것을 쓴다. 최고 단계가 아니면 스냅샷이 스스로
            // 연출을 끈다([CompletionSnapshot.celebrates]).
            celebration = tier.celebration ?: achievement.celebration,
            firstClearUnits = firstClear?.let { ach.payouts.freeze(it.rewards, "") }.orEmpty(),
            firstClearCelebration = firstClear?.celebration,
            firstClear = firstClear != null,
            silent = silent,
            highest = highest,
            online = player != null,
        )
    }

    /**
     * 단계 이름을 안 적었으면 로마 숫자로. `나무꾼 II`.
     *
     * **단계 없는 업적에는 붙이지 않는다** — 안 그러면 "업적 달성! 첫 벌목 I" 이 된다.
     */
    private fun defaultTierName(achievement: Achievement, tier: Tier): String {
        if (achievement.tiers.isEmpty()) return ""
        val index = achievement.steps().indexOfFirst { it.id == tier.id }
        if (index < 0) return ""
        return ROMAN.getOrElse(index) { (index + 1).toString() }
    }

    /**
     * 새로 만든 claim. 기록은 [com.inmc.achievements.claim.ClaimStore] 가 쓴다.
     *
     * 서버 최초 몫은 [first] 가 참일 때만 담는다 — 누가 최초인지는 순차 처리가 정한다.
     */
    fun toClaim(snapshot: CompletionSnapshot, first: Boolean): Claim = Claim(
        uid = snapshot.uid,
        tierId = snapshot.tierId,
        at = snapshot.at,
        units = (snapshot.units + if (first) snapshot.firstClearUnits else emptyList())
            .map { it.copy() }.toMutableList(),
        celebrateOnJoin = !snapshot.online &&
            snapshot.celebrates() && snapshot.celebration.deferrableToJoin(),
    )

    private companion object {
        val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
    }
}
