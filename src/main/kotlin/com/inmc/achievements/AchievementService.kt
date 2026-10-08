package com.inmc.achievements

import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Condition
import com.inmc.achievements.achievement.DiscoveryKind
import com.inmc.achievements.achievement.Retroactive
import com.inmc.achievements.claim.Claim
import com.inmc.achievements.claim.CompletionSnapshot
import com.inmc.achievements.progress.ProgressEngine
import kr.inmc.core.event.InmcSignalEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 진행·발견·완료가 모이는 곳.
 *
 * **여기서도 지급하지 않는다.** 완료를 발견하면 얼려서 큐에 넣고, [drain] 만이 지급한다.
 * 신호 리스너가 남의 플러그인 스택 안에서 돌기 때문이다 — 거기서 보상을 터뜨리면 그 플러그인의
 * 함수가 돌아오기도 전에 일어나고, 보상 명령어가 또 신호를 쏘면 재귀한다.
 *
 * ## 메모리 경로와 저장소 경로
 *
 * 진행도를 만질 때 기준은 "접속했는가" 가 아니라 **[com.inmc.achievements.progress.AchievementCounters.isLoaded]** 다.
 * 올라와 있지 않은 사람을 메모리로 쓰면 빈 항목이 새로 생겨 저장된 진행도를 덮어쓴다.
 */
class AchievementService(private val ach: Achievements) {

    /**
     * 신호 색인. `(source/type)` → 그 신호를 보는 업적들.
     *
     * 정의가 바뀔 때마다 **통째로 다시 만든다.** 줄기만 하게 두면 관리자가 단계를 추가했을 때
     * 최고 단계를 넘겼던 사람이 감시 대상으로 돌아오지 못한다 — 오류 없이 영영.
     */
    @Volatile
    private var signalIndex: Map<String, List<Achievement>> = emptyMap()

    /** 5초 스윕이 훑는 조건: 통계 · 상태 · 평가기가 붙은 커스텀. */
    @Volatile
    private var swept: List<Achievement> = emptyList()

    /** 발견 계기별 색인. */
    @Volatile
    private var discoveryIndex: Map<DiscoveryKind, List<Achievement>> = emptyMap()

    @Volatile
    private var hasPreciseRegions: Boolean = false

    fun reindex() {
        val all = ach.registry.all()
        signalIndex = all.mapNotNull { achievement ->
            (achievement.condition as? Condition.Signal)?.let { key(it.source, it.type) to achievement }
        }.groupBy({ it.first }, { it.second })

        swept = all.filter { achievement ->
            when (achievement.condition) {
                is Condition.Stat, is Condition.State, is Condition.Custom -> true
                else -> false
            }
        }

        discoveryIndex = all.filter { it.hidden }.groupBy { it.discovery.kind }
        hasPreciseRegions = ach.regions.hasPrecise()
    }

    fun watchesPreciseRegions(): Boolean = hasPreciseRegions

    fun sweptCount(): Int = swept.size

    // --- 신호 ----------------------------------------------------------------------

    /** 신호 하나를 받는다. 진행도만 올리고 완료는 큐로 간다. */
    fun onSignal(event: InmcSignalEvent) {
        if (!ach.ready || !ach.accepting) return
        val candidates = signalIndex[key(event.source, event.type)]
        if (candidates != null) {
            for (achievement in candidates) {
                val condition = achievement.condition as? Condition.Signal ?: continue
                if (!matches(condition, event)) continue
                val count = push(event.playerId, achievement, condition, event.amount)
                evaluate(event.playerId, event.player, achievement, count, previous = count - event.amount)
            }
        }
        // 신호가 히든 업적의 발견 계기일 수도 있다.
        discoverBySignal(event)
    }

    /**
     * 진행도를 올린다. 올라와 있으면 메모리, 아니면 공유 저장소에 직접.
     *
     * `AchievementsApi.progress` 도 여기를 지난다.
     */
    fun push(playerId: UUID, achievement: Achievement, condition: Condition, amount: Long): Long =
        if (ach.counters.isLoaded(playerId)) {
            ach.counters.increment(playerId, achievement.uid, condition.signature, amount)
        } else {
            ach.counters.incrementOffline(ach.players, playerId, achievement.uid, condition.signature, amount)
        }

    /**
     * 조건이 이 신호와 맞는가.
     *
     * `subject` 가 비어 있으면 그 출처/종류의 **모든** 신호를 센다 — "물고기 100마리" 처럼
     * 종류를 안 가리는 업적이 그 모양이다.
     */
    private fun matches(condition: Condition.Signal, event: InmcSignalEvent): Boolean {
        if (!condition.subject.isNullOrBlank() &&
            !condition.subject.equals(event.subject, ignoreCase = true)
        ) {
            return false
        }
        for ((key, want) in condition.match) {
            if (!want.equals(event.data[key], ignoreCase = true)) return false
        }
        return true
    }

    // --- 스윕 ----------------------------------------------------------------------

    /**
     * 통계·상태·커스텀 조건과 표본형 발견 계기를 다시 본다. **메모리만 만진다** — 완료는
     * 큐로 간다.
     *
     * 부르는 쪽이 접속자를 나눠 넘긴다. 한 틱에 전부 훑으면 순간 부하가 튄다.
     */
    fun sweep(players: List<Player>, now: Long) {
        val discoveries = discoveryIndex[DiscoveryKind.REGION].orEmpty().isNotEmpty() ||
            discoveryIndex[DiscoveryKind.CUSTOM].orEmpty().isNotEmpty()
        if (swept.isEmpty() && !discoveries) return
        for (player in players) {
            // 한 사람이 터져서 나머지가 건너뛰어지면 안 된다.
            runCatching { sweepOne(player, now) }
                .onFailure { ach.logger.warning("업적 스윕 실패 (${player.name}): " + it.message) }
        }
    }

    private fun sweepOne(player: Player, now: Long) {
        val id = player.uniqueId
        for (achievement in swept) {
            val condition = achievement.condition ?: continue
            // 평가기 없는 커스텀은 밀어 넣을 때 평가된다. 여기서 볼 것이 없다.
            if (condition is Condition.Custom && !ach.custom.knows(condition.kind)) continue

            val progress = ach.engine.progressOf(id, player, achievement)
            if (progress !is ProgressEngine.Progress.Known) continue

            // 읽은 값은 캐시로 남기고, 덮기 전 값이 소급 판정의 근거가 된다.
            val previous = ach.counters.cache(id, achievement.uid, condition.signature, progress.count, now)
            evaluate(id, player, achievement, progress.count, previous)
        }
        discoverByRegion(player)
        discoverByCustom(player)
    }

    // --- 평가와 완료 ------------------------------------------------------------------

    /**
     * 이 진행도로 새로 열린 단계를 큐에 넣는다.
     *
     * ## 소급 판정
     *
     * [previous] 는 이번 변화 **전**에 우리가 본 값이다. 임계값이 그 이하인 단계는 이번 변화로
     * 넘은 것이 아니라 **이미 해 둔 것**이다 — 업적이 새로 생겼거나, 단계가 새로 끼어들었거나,
     * 이 사람을 처음 봤거나(null). 그런 단계는 [Retroactive.SILENT] 면 보상만 주고 조용히 넘긴다.
     * [live] 는 발견·관리자 지급처럼 그 자체가 사건인 경우다.
     *
     * ## 히든
     *
     * **발견하지 않은 히든 업적은 완료되지 않는다.** 진행도는 그대로 쌓인다. 다만
     * 발견 방식이 `AUTO` 면 완료하는 순간이 곧 발견이다 — 여기서 막으면 AUTO 히든은 영원히
     * 완료되지 않는다(발견하려면 완료해야 하는데 완료하려면 발견해야 한다).
     */
    fun evaluate(
        playerId: UUID,
        player: Player?,
        achievement: Achievement,
        count: Long,
        previous: Long?,
        live: Boolean = false,
    ) {
        val fresh = ach.engine.newlyReached(playerId, achievement, count)
        if (fresh.isEmpty()) return

        val silentTier: (Long) -> Boolean = { threshold -> achievement.silentFor(threshold, previous, live) }

        if (achievement.hidden && !isDiscovered(playerId, achievement)) {
            if (!achievement.discoversOnCompletion()) return
            markDiscovered(playerId, player, achievement, announce = !fresh.all { silentTier(it.threshold) })
        }

        val top = fresh.last()
        for (tier in fresh) {
            ach.queue.offer(
                ach.engine.snapshot(
                    playerId = playerId,
                    player = player,
                    achievement = achievement,
                    tier = tier,
                    highest = tier.id == top.id,
                    silent = silentTier(tier.threshold),
                ),
            )
        }
    }

    private fun isDiscovered(playerId: UUID, achievement: Achievement): Boolean =
        if (ach.counters.isLoaded(playerId)) ach.counters.isDiscovered(playerId, achievement.uid)
        else ach.counters.storedDiscovered(ach.players, playerId, achievement.uid)

    /**
     * 큐를 비운다. **이번 틱에 있던 것만.**
     *
     * 드레인 도중에 들어온 완료(보상 명령어가 다른 조건을 만족시킨 경우)는 다음 틱이다.
     * `while (큐가 안 빔)` 이면 같은 틱에 연쇄로 돌아 틱당 상한이 무의미해진다.
     */
    fun drain(limit: Int) {
        process(ach.queue.take(limit))
    }

    /** 리로드·종료 직전. 일회성 신호는 다시 오지 않으므로 **버리면 영원히 사라진다.** */
    fun drainAll() {
        process(ach.queue.drainAll())
    }

    /**
     * 한 배치를 끝까지 처리한다.
     *
     * 1. **서버 최초를 정한다.** 순차 처리라 먼저 온 쪽이 파일에 먼저 적고 이긴다. 같은 배치의
     *    다른 사람은 그걸 보고 진다. 감지 시점에 정하면 둘 다 최초 보상을 받는다.
     * 2. **신규 기록을 플레이어 단위로 한 번에 확정한다.** 같은 틱에 업적 둘을 완료해도 쓰기는 한 번.
     * 3. **효과 하나씩 지급하고 그때마다 확정한다.** 끝에 한꺼번에 적으면 깃발 하나와 같아진다.
     */
    private fun process(batch: List<CompletionSnapshot>) {
        if (batch.isEmpty()) return
        for ((playerId, group) in batch.groupBy { it.playerId }) {
            val fresh = group.filter { !ach.claims.hasClaimed(playerId, it.uid, it.tierId) }
            if (fresh.isEmpty()) continue
            for (snapshot in fresh) ach.claims.label(snapshot.uid, snapshot.achievementName)

            val firsts = fresh.associate { snapshot -> key(snapshot) to decideFirst(playerId, snapshot) }

            val recorded = ach.claims
                .record(playerId, fresh.map { ach.engine.toClaim(it, firsts.getValue(key(it))) })
                .associateBy { it.uid + "/" + it.tierId }

            for (snapshot in fresh) {
                val claim = recorded[key(snapshot)] ?: continue
                runCatching { settle(snapshot, claim, firsts.getValue(key(snapshot))) }.onFailure {
                    ach.logger.severe("업적 완료 처리 실패 (${snapshot.achievementName}): " + it.message)
                }
            }
        }
    }

    /**
     * 이 사람이 서버 최초인가. **파일에 적는 데 성공해야** 참이다.
     *
     * 이미 이 사람 이름으로 적혀 있다면 그것도 참이다 — 최초 기록은 적었는데 claim 을 적기 전에
     * 서버가 죽은 경우라, 되살린 뒤에 그 몫을 잃으면 안 된다.
     */
    private fun decideFirst(playerId: UUID, snapshot: CompletionSnapshot): Boolean {
        if (!snapshot.competesForFirst()) return false
        if (ach.firstClears.holderOf(snapshot.uid, snapshot.tierId) == playerId) return true
        val name = Bukkit.getPlayer(playerId)?.name
            ?: Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString()
        return ach.firstClears.claim(snapshot.uid, snapshot.tierId, playerId, name, snapshot.at)
    }

    /** 완료 하나의 지급과 연출. 기록은 이미 확정돼 있다. */
    private fun settle(snapshot: CompletionSnapshot, claim: Claim, first: Boolean) {
        val playerId = snapshot.playerId
        ach.payouts.settleFresh(playerId, claim)

        val player = Bukkit.getPlayer(playerId)

        if (snapshot.celebrates()) {
            if (snapshot.online || snapshot.celebration.runnableWhileOffline()) {
                ach.celebrations.play(snapshot, player)
            } else if (!snapshot.celebration.deferrableToJoin()) {
                // 주변·월드는 기준 위치가 없어 재현할 수 없다. 하지 않고 남긴다.
                ach.logger.info("오프라인 완료라 '${snapshot.achievementName}' 의 주변 연출을 건너뜁니다")
            }
        }
        // 최초 기록은 단계마다 남지만 공지는 최고 단계 하나만.
        if (first && snapshot.highest) ach.celebrations.playFirstClear(snapshot, player)

        announce(snapshot, player)

        val newRank = ach.points.refresh(playerId)
        if (player != null && newRank != null) {
            ach.tell(player, "rank-up", ach.ph().rank(newRank))
        }

        // 이 업적이 다른 히든 업적의 열쇠일 수 있다. 그 완료도 같은 큐를 탄다.
        discoverByAchievement(playerId, player, snapshot.uid, snapshot.at)
    }

    private fun announce(snapshot: CompletionSnapshot, player: Player?) {
        if (snapshot.silent || player == null) return
        val ph = ach.ph()
            .achievement(snapshot.achievementName)
            .tier(snapshot.tierName)
            .points(snapshot.points)
        ach.tell(player, if (snapshot.tierName.isBlank()) "unlocked" else "unlocked-tier", ph)
        if (snapshot.points > 0) ach.tell(player, "points-gained", ph)
    }

    // --- 발견 ----------------------------------------------------------------------

    /** 발견 표시만 남긴다. 이미 발견했으면 false. */
    private fun markDiscovered(playerId: UUID, player: Player?, achievement: Achievement, announce: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val opened = if (ach.counters.isLoaded(playerId)) {
            ach.counters.discover(playerId, achievement.uid, now)
        } else {
            ach.counters.discoverOffline(ach.players, playerId, achievement.uid, now)
        }
        if (opened && announce && player != null) {
            ach.tell(player, "discovered", ach.ph().achievement(achievement.display))
        }
        return opened
    }

    /**
     * 히든 업적을 연다. 이미 열려 있으면 false.
     *
     * 발견 자체가 사건이라 그 자리의 완료는 **소급이 아니다** — "발견과 동시에 달성" 이
     * 요구사항의 그 순간이다. `completeOnDiscover` 면 진행도와 무관하게 완료시키고, 아니면
     * 발견 전에 쌓인 진행도로 이미 넘은 단계가 지금 열린다. 둘 다 **같은 큐와 같은 틱당
     * 상한**을 탄다 — 한 사건이 여러 사람의 히든 업적을 동시에 열 수 있다.
     */
    fun discover(playerId: UUID, player: Player?, achievement: Achievement): Boolean {
        if (!markDiscovered(playerId, player, achievement, announce = true)) return false

        if (achievement.discovery.completeOnDiscover) {
            evaluate(playerId, player, achievement, achievement.maxThreshold(), previous = null, live = true)
        } else {
            val progress = ach.engine.progressOf(playerId, player, achievement)
            if (progress is ProgressEngine.Progress.Known) {
                evaluate(playerId, player, achievement, progress.count, previous = null, live = true)
            }
        }
        return true
    }

    /** 다른 업적을 달성해서 열리는 것들. */
    private fun discoverByAchievement(playerId: UUID, player: Player?, completedUid: String, now: Long) {
        for (achievement in discoveryIndex[DiscoveryKind.ACHIEVEMENT].orEmpty()) {
            if (achievement.discovery.value != completedUid) continue
            discover(playerId, player, achievement)
        }
    }

    private fun discoverBySignal(event: InmcSignalEvent) {
        val candidates = discoveryIndex[DiscoveryKind.SIGNAL].orEmpty()
        if (candidates.isEmpty()) return
        val full = (key(event.source, event.type) + "/" + event.subject).lowercase()
        val partial = key(event.source, event.type)
        for (achievement in candidates) {
            val want = achievement.discovery.value.trim().lowercase()
            if (want != partial && want != full) continue
            discover(event.playerId, event.player, achievement)
        }
    }

    /**
     * 표본형 지역 발견. 5초 스윕에서 돈다.
     *
     * **정밀 지역은 건너뛴다** — 그건 이동 리스너가 본다. 예전에는 정밀 지역이 하나라도 있으면
     * 이 함수 자체를 건너뛰어서, 표본형 지역 발견이 **전부** 멈췄다.
     */
    private fun discoverByRegion(player: Player) {
        val candidates = discoveryIndex[DiscoveryKind.REGION].orEmpty()
        if (candidates.isEmpty()) return
        for (achievement in candidates) {
            val region = ach.regions.get(achievement.discovery.value) ?: continue
            val inside = region.contains(player.location)
            // 정밀 지역의 발견은 이동 리스너가 한다. 여기서는 밖에 있는 것을 보는 것(초기화 뒤 기다림 풀기)만 —
            // 이동 리스너는 안에 있을 때만 부르고, 순간이동·접속으로 나간 것은 이동으로 안 온다.
            if (region.precise && inside) continue
            discoverWhen(player, achievement, inside)
        }
    }

    /**
     * 표본형 발견 조건을 본 결과. 관리자 초기화 직후에는 조건이 한 번 거짓이 될 때까지 열지 않는다([markReset]).
     */
    private fun discoverWhen(player: Player, achievement: Achievement, satisfied: Boolean) {
        val id = player.uniqueId
        if (!satisfied) {
            if (ach.counters.isLoaded(id)) ach.counters.releaseDiscoverWait(id, achievement.uid)
            else if (ach.counters.storedDiscoverWaiting(ach.players, id, achievement.uid)) {
                ach.counters.releaseDiscoverWaitOffline(ach.players, id, achievement.uid)
            }
            return
        }
        if (!discoverWaiting(id, achievement)) discover(id, player, achievement)
    }

    private fun discoverWaiting(playerId: UUID, achievement: Achievement): Boolean =
        if (ach.counters.isLoaded(playerId)) ach.counters.discoverWaiting(playerId, achievement.uid)
        else ach.counters.storedDiscoverWaiting(ach.players, playerId, achievement.uid)

    /**
     * 외부가 등록한 발견 조건. 값은 `종류` 또는 `종류:인자`.
     *
     * 이미 발견한 사람에게는 묻지 않는다 — 남의 코드를 5초마다 헛되이 부를 이유가 없다.
     */
    private fun discoverByCustom(player: Player) {
        val candidates = discoveryIndex[DiscoveryKind.CUSTOM].orEmpty()
        if (candidates.isEmpty()) return
        val id = player.uniqueId
        for (achievement in candidates) {
            if (isDiscovered(id, achievement)) continue
            val raw = achievement.discovery.value
            val kind = raw.substringBefore(':').trim()
            val argument = raw.substringAfter(':', "").trim()
            // null 은 "모름"(평가기 없음·실패) — 열지도 기다림을 풀지도 않는다.
            val satisfied = ach.custom.evaluateDiscovery(kind, id, argument) ?: continue
            discoverWhen(player, achievement, satisfied)
        }
    }

    /** 이동 리스너가 정밀 지역에 들어온 사람을 알린다. */
    fun enteredPreciseRegion(player: Player, regionName: String) {
        for (achievement in discoveryIndex[DiscoveryKind.REGION].orEmpty()) {
            if (!achievement.discovery.value.equals(regionName, ignoreCase = true)) continue
            // 초기화 직후 안에 있던 사람은 한 번 나갔다 와야 한다 — 나간 것은 스윕이 본다(discoverByRegion).
            if (discoverWaiting(player.uniqueId, achievement)) continue
            discover(player.uniqueId, player, achievement)
        }
    }

    fun discoveriesOf(kind: DiscoveryKind): List<Achievement> = discoveryIndex[kind].orEmpty()

    // --- 관리자 경로 -------------------------------------------------------------------

    /** `/업적 지급`. 조건을 무시하고 준다. */
    fun grant(playerId: UUID, achievement: Achievement, tierId: String?): Boolean {
        val tier = tierId?.let { achievement.tier(it) } ?: achievement.steps().first()
        if (ach.claims.hasClaimed(playerId, achievement.uid, tier.id)) return false
        val player = Bukkit.getPlayer(playerId)
        ach.queue.offer(
            ach.engine.snapshot(playerId, player, achievement, tier, highest = true, silent = false),
        )
        return true
    }

    /** `/업적 회수`. 기록을 지우고 점수를 다시 센다. */
    fun revoke(playerId: UUID, achievement: Achievement): Boolean {
        val removed = ach.claims.purge(playerId, achievement.uid)
        if (removed) ach.points.refresh(playerId)
        return removed
    }

    /** 관리자 초기화 결과 — 보고용 개수. */
    data class ResetSummary(
        /** 지운 달성 단계 수. */
        val tiers: Int,
        /** 지운 진행 항목 수. */
        val progress: Int,
        /** 푼 서버 최초 기록 수. */
        val firsts: Int,
        /** 버린 우편함 항목 수. */
        val mailbox: Int,
        /** 버린 대기 완료 수. */
        val queued: Int,
        /** 거둔 칭호 수(접속 중일 때만 거둔다 — 타이틀포지 API 가 Player 를 받는다). */
        val titles: Int = 0,
    )

    /**
     * `/업적 관리 초기화`. 진행·달성 기록·점수·서버 최초·우편함을 지운다.
     *
     * uid 가 null 이면 그 사람 전체, 있으면 그 업적 하나. 우편함 항목에는 업적이
     * 적혀 있지 않아 귀속을 가릴 수 없으므로 전체 초기화 때만 비운다.
     * 점수는 claims 에서 다시 계산되므로 `refresh` 하나로 끝나고, 발견 표시는
     * 진행도에 딸려 있어 따로 손댈 것이 없다.
     *
     * 순서는 대기 완료 버리기가 먼저다 — 지우는 사이에 처리되면 지운 것이 되살아난다.
     * 이미 `take` 로 떼어 가 처리 중인 것은 건드리지 않는다(그쪽은 기록이 있어 걸러진다).
     */
    fun reset(playerId: UUID, uid: String?): ResetSummary {
        val tiers = if (uid != null) ach.claims.claims(playerId, uid).size
        else ach.claims.all(playerId).values.sumOf { it.size }
        val titles = reclaimTitles(playerId, uid)
        val queued = ach.queue.drop(playerId, uid)
        if (uid != null) ach.claims.purge(playerId, uid) else ach.claims.purgePlayer(playerId)
        val progress = ach.counters.clear(playerId, uid, ach.players)
        markReset(playerId, uid)
        val firsts = ach.firstClears.release(playerId, uid)
        val mailbox = if (uid == null) ach.mailbox.discard(playerId) else 0
        ach.points.refresh(playerId)
        return ResetSummary(tiers, progress, firsts, mailbox, queued, titles)
    }

    /**
     * 초기화할 때 **준 칭호를 거둔다**(사용자 결정 2026-10-08 — 돈은 이미 썼을 수 있어 그대로 둔다). 기록(claims)에서 `GRANTED` 인 칭호 단위만.
     * 타이틀포지 API 가 Player 를 받아 접속 중일 때만 — 오프라인이면 0 이고 기록은 그대로 지워진다.
     */
    private fun reclaimTitles(playerId: UUID, uid: String?): Int {
        if (!ach.titles.isEnabled) return 0
        val player = org.bukkit.Bukkit.getPlayer(playerId) ?: return 0
        val claims = if (uid != null) ach.claims.claims(playerId, uid).values else ach.claims.all(playerId).values.flatMap { it.values }
        var count = 0
        for (claim in claims) for (unit in claim.units) {
            if (unit.type != com.inmc.achievements.claim.UnitType.TITLE || unit.state != com.inmc.achievements.claim.UnitState.GRANTED) continue
            if (ach.titles.revoke(player, unit.title)) count++
        }
        return count
    }

    /**
     * 지워도 그대로인 것에 초기화 표시를 남긴다 — **초기화 뒤에 새로 한 것만 다시 준다**(사용자 결정 2026-10-07).
     *
     *  - 통계·상태·평가기 커스텀: 우리가 세지 않고 읽어 오는 값이라 지워지지 않는다. 다음에 읽은 값이 기준점이 되고
     *    그 위로 늘어난 만큼만 센다. 상태는 한 번 풀렸다가 다시 충족돼야 한다([ProgressEngine] `sinceReset`).
     *  - 지역·커스텀 발견: 그 자리에 선 채로 초기화하면 곧바로 다시 발견되고, "발견 즉시 달성" 이면 보상이 또 나간다.
     *    한 번 거짓이 될 때까지(나갔다가 다시 들어올 때까지) 다시 열지 않는다([discoverWhen]).
     *
     * 신호·밀어 넣는 커스텀은 우리가 센 값이라 지우는 것으로 0부터다. 신호·아이템·NPC·다른 업적 발견은 그 자체가 새 사건이다.
     */
    private fun markReset(playerId: UUID, uid: String?) {
        val targets = if (uid != null) listOfNotNull(ach.registry.byUid(uid)) else ach.registry.all()
        val loaded = ach.counters.isLoaded(playerId)
        for (achievement in targets) {
            val read = when (achievement.condition) {
                is Condition.Stat, is Condition.State, is Condition.Custom -> true
                else -> false
            }
            val wait = achievement.hidden &&
                (achievement.discovery.kind == DiscoveryKind.REGION || achievement.discovery.kind == DiscoveryKind.CUSTOM)
            if (!read && !wait) continue
            if (loaded) ach.counters.markReset(playerId, achievement.uid, read, wait)
            else ach.counters.markResetOffline(ach.players, playerId, achievement.uid, read, wait)
        }
    }

    /**
     * 접속했을 때 미뤄둔 것을 처리한다 — **미룬 지급과 미룬 연출만.**
     *
     * `PENDING` 은 건드리지 않는다. 되살린 뒤의 `PENDING` 은 크래시로 줬는지 모르는 것이라
     * 자동으로 다시 주면 돈이 복제될 수 있다.
     */
    fun onJoin(player: Player) {
        val id = player.uniqueId
        for (claim in ach.claims.pendingOnJoin(id)) {
            if (claim.hasDeferred()) ach.payouts.settleDeferred(id, claim)
            if (!claim.celebrateOnJoin) continue

            // **보상을 다시 뽑지 않는다.** `snapshot()` 은 `resolve` 를 돌리므로
            // ROLL_ONE 번들이면 다른 것이 나온다. 연출에 필요한 것만 들고 만든다.
            val achievement = ach.registry.byUid(claim.uid)
            if (achievement != null) {
                val tier = achievement.tier(claim.tierId)
                ach.celebrations.play(
                    CompletionSnapshot(
                        playerId = id,
                        uid = claim.uid,
                        tierId = claim.tierId,
                        achievementName = achievement.display,
                        tierName = tier?.label.orEmpty(),
                        points = tier?.points ?: 0,
                        units = emptyList(),
                        celebration = tier?.celebration ?: achievement.celebration,
                        online = true,
                        at = claim.at,
                    ),
                    player,
                )
            }
            ach.claims.clearCelebrateOnJoin(id, claim)
        }
    }

    private fun key(snapshot: CompletionSnapshot): String = snapshot.uid + "/" + snapshot.tierId

    private fun key(source: String, type: String): String =
        source.lowercase() + "/" + type.lowercase()
}
