package com.inmc.achievements.progress

import kr.inmc.core.store.PlayerStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 플레이어별 업적 진행도.
 *
 * 몬스터의 `TriggerCounters` 에서 **모양만** 가져왔다. 올리지 않고 베낀 이유는 가장 위험한
 * 동작이 서로 반대이기 때문이다 — 아래 [syncTo] 의 경고를 보라. 불린 하나로 가르면
 * 한쪽 설정이 조용히 기록을 먹는 클래스가 된다.
 *
 * ## 성능 계약 (양보 불가)
 *
 * [increment] 는 `computeIfAbsent` 두 번 + 필드 쓰기로 끝나야 한다. **`PlayerStore.set` 을
 * 부르면 안 된다** — 문자열 셋에 `requireFlat` 이 돌고 플레이어가 dirty 로 찍혀, 신호가 잦은
 * 사람마다 30초마다 YAML 전체가 다시 쓰인다. [touched] 배치가 설계 그 자체다.
 *
 * ## 메모리에는 접속자만 있다
 *
 * 접속할 때 [load] 하고 퇴장할 때 [forget] 한다. 그래서 **메모리에서 무엇을 내리는 만료
 * 정리는 두지 않는다** — 내릴 대상이 곧 접속 중인 사람이고, 접속 중인 사람의 항목을 내리면
 * 다음 신호가 빈 항목을 새로 만들어 저장된 진행도를 **덮어쓴다.** (실제로 그렇게 짰다가 뺐다.)
 *
 * 메모리에 올라와 있지 않은 사람은 **반드시 `…Offline` 경로**를 탄다. 판단 기준은
 * "접속했는가" 가 아니라 [isLoaded] 다.
 *
 * ## 무엇이 여기 있고 무엇이 없는가
 *
 * 여기 있는 것은 **잃어도 다시 쌓으면 그만인 것**뿐이다. 받은 보상의 기록(claim)은
 * [com.inmc.achievements.claim.ClaimStore] 가 따로, 훨씬 엄격하게 들고 있다.
 */
class AchievementCounters {

    private class Progress {
        @Volatile var count: Long = 0L
        @Volatile var signature: String = ""
        @Volatile var readAt: Long = 0L
        @Volatile var discoveredAt: Long = 0L
    }

    private val players = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Progress>>()

    /**
     * 마지막 동기화 이후 값이 바뀐 플레이어.
     *
     * 공유 저장소는 플레이어 단위로 파일을 쓴다. 누가 바뀌었는지 모르면 플러시마다 전원의
     * 파일을 다시 쓰게 된다.
     */
    private val touched = ConcurrentHashMap.newKeySet<UUID>()

    val size: Int get() = players.size

    fun hasPending(): Boolean = touched.isNotEmpty()

    /** 이 사람이 메모리에 올라와 있는가. **아니면 오프라인 경로를 탄다.** */
    fun isLoaded(playerId: UUID): Boolean = players.containsKey(playerId)

    // --- 읽기 (메모리) ----------------------------------------------------------------

    fun count(playerId: UUID, uid: String): Long = players[playerId]?.get(uid)?.count ?: 0L

    fun signature(playerId: UUID, uid: String): String? = players[playerId]?.get(uid)?.signature

    /** 통계 캐시를 언제 읽었는지. 0이면 아직. 오프라인 표시에 쓴다. */
    fun readAt(playerId: UUID, uid: String): Long = players[playerId]?.get(uid)?.readAt ?: 0L

    /** 발견한 시각. **키의 유무가 진실이다** — 0이면 미발견. */
    fun discoveredAt(playerId: UUID, uid: String): Long =
        players[playerId]?.get(uid)?.discoveredAt ?: 0L

    fun isDiscovered(playerId: UUID, uid: String): Boolean = discoveredAt(playerId, uid) > 0L

    /** 이 사람의 모든 진행 항목. 통계 화면이 쓴다. */
    fun entries(playerId: UUID): Map<String, Long> =
        players[playerId]?.mapValues { it.value.count }.orEmpty()

    // --- 쓰기 (메모리) ----------------------------------------------------------------

    /**
     * 진행도를 [amount] 만큼 올리고 새 값을 돌려준다. **핫 패스다.**
     *
     * [signature] 가 저장된 것과 다르면 **`Signal`·`Custom` 에서만** 0부터 다시 센다 —
     * 통계는 애초에 여기로 안 온다([cache] 를 쓴다).
     */
    fun increment(playerId: UUID, uid: String, signature: String, amount: Long): Long {
        val entry = progress(playerId, uid)
        if (entry.signature != signature) {
            entry.count = 0L
            entry.signature = signature
        }
        entry.count += amount
        touched.add(playerId)
        return entry.count
    }

    /**
     * 통계·상태에서 읽은 값을 **캐시로** 덮어쓰고, 덮기 **전** 값을 돌려준다.
     *
     * 누적이 아니다 — 통계 진행도는 우리 것이 아니라 바닐라 것이라, 여기 적는 숫자는
     * "마지막으로 본 값" 일 뿐이다.
     *
     * 돌려주는 옛 값이 **소급 판정의 근거**다. 처음 보는 것(읽은 적 없음 또는 조건이 바뀜)이면
     * null — 그 사람이 이미 해 둔 것으로 달성되는 것이므로 소급이다.
     */
    fun cache(playerId: UUID, uid: String, signature: String, value: Long, now: Long): Long? {
        val entry = progress(playerId, uid)
        val previous = if (entry.readAt > 0L && entry.signature == signature) entry.count else null
        if (entry.count == value && entry.signature == signature) {
            entry.readAt = now
            return previous
        }
        entry.count = value
        entry.signature = signature
        entry.readAt = now
        touched.add(playerId)
        return previous
    }

    /** 발견 처리. 이미 발견했으면 false. */
    fun discover(playerId: UUID, uid: String, now: Long): Boolean {
        val entry = progress(playerId, uid)
        if (entry.discoveredAt > 0L) return false
        entry.discoveredAt = now
        touched.add(playerId)
        return true
    }

    private fun progress(playerId: UUID, uid: String): Progress =
        players.computeIfAbsent(playerId) { ConcurrentHashMap() }
            .computeIfAbsent(uid) { Progress() }

    // --- 오프라인 경로 -----------------------------------------------------------------

    /**
     * 메모리에 없는 사람의 진행도를 올리고 새 값을 돌려준다.
     *
     * **메모리를 거치지 않는다.** 거치면 그 유령 항목이 그 사람이 접속할 때 디스크 적재로
     * **덮어써지고**, `forget` 은 퇴장 때만 불리니 접속한 적 없는 사람 것은 영영 안 지워진다.
     *
     * `PlayerStore` 가 부팅 때 **전원을 메모리에 올려두므로** 이건 파일 I/O 가 아니라 맵
     * 조회다. 대회 우승처럼 드물게 일어나므로 위의 성능 계약과 충돌하지 않는다.
     */
    fun incrementOffline(
        store: PlayerStore,
        playerId: UUID,
        uid: String,
        signature: String,
        amount: Long,
    ): Long {
        val base = if (storedSignature(store, playerId, uid) == signature) storedCount(store, playerId, uid) else 0L
        val next = base + amount
        store.set(playerId, NAMESPACE, uid, FIELD_COUNT, next)
        store.set(playerId, NAMESPACE, uid, FIELD_SIG, signature)
        return next
    }

    /** 오프라인 발견. 이미 발견했으면 false. */
    fun discoverOffline(store: PlayerStore, playerId: UUID, uid: String, now: Long): Boolean {
        if (store.getLong(playerId, NAMESPACE, uid, FIELD_DISCOVERED) > 0L) return false
        store.set(playerId, NAMESPACE, uid, FIELD_DISCOVERED, now)
        return true
    }

    fun storedCount(store: PlayerStore, playerId: UUID, uid: String): Long =
        store.getLong(playerId, NAMESPACE, uid, FIELD_COUNT)

    fun storedSignature(store: PlayerStore, playerId: UUID, uid: String): String? =
        store.getString(playerId, NAMESPACE, uid, FIELD_SIG)

    fun storedDiscovered(store: PlayerStore, playerId: UUID, uid: String): Boolean =
        store.getLong(playerId, NAMESPACE, uid, FIELD_DISCOVERED) > 0L

    // --- 적재·동기화 --------------------------------------------------------------------

    /**
     * 접속할 때 그 사람 것을 공유 저장소에서 통째로 올린다.
     *
     * 메모리에 없던 동안의 변경은 전부 오프라인 경로로 저장소에 직접 쓰였으므로, 여기서
     * 저장소를 그대로 읽는 것이 곧 최신이다.
     */
    fun load(store: PlayerStore, playerId: UUID) {
        val entries = ConcurrentHashMap<String, Progress>()
        for (uid in store.subjects(playerId, NAMESPACE)) {
            val entry = Progress()
            entry.count = store.getLong(playerId, NAMESPACE, uid, FIELD_COUNT)
            entry.signature = store.getString(playerId, NAMESPACE, uid, FIELD_SIG).orEmpty()
            entry.readAt = store.getLong(playerId, NAMESPACE, uid, FIELD_READ_AT)
            entry.discoveredAt = store.getLong(playerId, NAMESPACE, uid, FIELD_DISCOVERED)
            entries[uid] = entry
        }
        players[playerId] = entries
    }

    /**
     * 메모리 상태를 공유 저장소에 넘긴다. **`flush()` 는 부르지 않는다** — core 가 자기 티커
     * 하나로 돈다(`CorePlugin`). 의존 플러그인이 각자 돌리면 같은 파일 쓰기가 겹친다.
     *
     * ## 몬스터판과 **정반대인 한 가지**
     *
     * `TriggerCounters.syncTo` 는 메모리에 없는 대상을 **지운다.** 몬스터는 부팅 때 전원의
     * 전 트리거를 올리므로 안전하다. 업적은 **접속 시 적재**라 그대로 베끼면 첫 동기화가
     * 손대지 않은 업적의 진행도를 통째로 지운다. 오류는 안 난다.
     *
     * **그래서 여기서는 부재삭제를 하지 않는다.** 삭제된 업적의 진행도는 남겨 둔다 — uid 는 재사용되지 않으므로 무해하다.
     */
    fun syncTo(store: PlayerStore, playerId: UUID? = null) {
        val batch = if (playerId != null) listOf(playerId) else touched.toList()
        if (batch.isEmpty()) return
        touched.removeAll(batch.toSet())

        for (id in batch) {
            val entries = players[id] ?: continue
            for ((uid, entry) in entries) {
                store.set(id, NAMESPACE, uid, FIELD_COUNT, entry.count)
                store.set(id, NAMESPACE, uid, FIELD_SIG, entry.signature)
                if (entry.readAt > 0L) store.set(id, NAMESPACE, uid, FIELD_READ_AT, entry.readAt)
                // 키의 유무가 진실이라 0이면 아예 안 쓴다.
                if (entry.discoveredAt > 0L) {
                    store.set(id, NAMESPACE, uid, FIELD_DISCOVERED, entry.discoveredAt)
                }
            }
        }
    }

    /** 퇴장. 부르는 쪽이 [syncTo] 를 먼저 한다. */
    fun forget(playerId: UUID) {
        players.remove(playerId)
        touched.remove(playerId)
    }

    /**
     * 진행도·발견 표시를 지운다. 관리자 초기화용 — 지운 업적 수를 돌려준다.
     *
     * 메모리와 공유 저장소를 **둘 다** 지운다. 메모리만 지우면 다음 `syncTo` 가 아니라
     * 그 반대다 — `syncTo` 는 메모리에 있는 것만 쓰므로 지운 것은 다시 안 올라간다.
     * 저장소만 지우면 메모리가 다음 동기화에 되살린다. uid 가 null 이면 그 사람 전체.
     *
     * 빈 항목을 만들지 않는다 — 없는 사람을 `computeIfAbsent` 로 만들면 빈 껍데기가
     * 저장된 진행도를 덮어쓴다(클래스 머리말의 경고).
     */
    fun clear(playerId: UUID, uid: String?, store: PlayerStore): Int {
        val memory = players[playerId]
        var removed: Int
        if (uid != null) {
            removed = if (memory?.remove(uid) != null) 1 else 0
            if (store.subjects(playerId, NAMESPACE).contains(uid)) {
                store.clearSubject(playerId, NAMESPACE, uid)
                removed = 1
            }
        } else {
            val storeSubjects = store.subjects(playerId, NAMESPACE)
            removed = (memory?.keys?.toSet().orEmpty() + storeSubjects).size
            players.remove(playerId)
            store.clear(playerId, NAMESPACE)
            touched.remove(playerId)
        }
        return removed
    }

    fun clear() {
        players.clear()
        touched.clear()
    }

    companion object {
        const val NAMESPACE = "achievements"
        const val FIELD_COUNT = "count"
        const val FIELD_SIG = "sig"
        const val FIELD_READ_AT = "read-at"
        const val FIELD_DISCOVERED = "discovered-at"
    }
}
