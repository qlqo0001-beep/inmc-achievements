package com.inmc.achievements.claim

import kr.inmc.core.config.ConfigService
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 받은 보상의 기록. **이 플러그인에서 가장 엄격하게 다루는 데이터다.**
 *
 * ## 왜 `PlayerStore` 가 아닌가
 *
 * `PlayerStore.set` 은 **메모리 쓰기 + dirty 표시가 전부다.** 디스크는 core 가 30초마다 도는
 * `flush()` 에서, 그것도 워커로 닿는다. 즉 **30초가 넘는 창**이 있다.
 *
 * 진행도는 그 창에서 죽어도 다시 쌓으면 그만이다. claim 은 아니다 — 돈이나 콘솔 명령어는
 * **즉시 외부에 확정**되므로, 그쪽이 끝난 뒤 기록만 사라지면 **다시 받는다.** 돈 복제다.
 *
 * ## 순서가 계약이다
 *
 * ```
 * 확정된 payout 을 전부 PENDING 으로 기록  →  디스크 확정
 *   →  unit 하나 지급  →  그 unit 만 GRANTED  →  디스크 확정  →  다음 unit …
 * ```
 *
 * 끝에 한꺼번에 적으면 깃발 하나와 완전히 같아지므로 줄일 수 없다.
 *
 * ## 메인 스레드에서 쓴다 — 이 워크스페이스에서 유일한 예외
 *
 * 비동기로 넘기면 호출이 돌아와도 디스크에 닿지 않았으므로 **순서 보장이 사라지고 이 장치의
 * 존재 이유가 없어진다.** 큐에 넣고 기다리는 것(`future.get()`)도 안 된다 — `ConfigService`
 * 의 executor 는 단일 스레드라 앞에 남의 작업이 쌓여 있으면 그 뒤에서 막히고, 소급 폭주 때가
 * 정확히 그 상황이다.
 *
 * 대신 **파일을 플레이어별로 쪼개** 한 번 쓰기를 1~2KB 로 묶는다. `PlayerStore` 가
 * `players/<uuid>.yml` 로 쪼개는 것과 같은 이유다.
 *
 * `fsync` 는 하지 않는다. JVM 크래시·`/stop`·플러그인 예외에서는 OS 페이지 캐시가 살아
 * 데이터가 디스크에 닿는다. fsync 가 막는 것은 정전·커널 패닉이고 그 층위는 다루지 않는다.
 */
class ClaimStore(private val io: ConfigService, private val folderName: String = "claims") {

    /** uuid → 업적 uid → 단계 id → claim. 전부 메모리에 있다. */
    private val byPlayer = ConcurrentHashMap<UUID, ConcurrentHashMap<String, MutableMap<String, Claim>>>()

    /** 업적 uid → 사람이 읽을 이름. 파일에 같이 적어 관리자가 알아볼 수 있게 한다. */
    private val labels = ConcurrentHashMap<String, String>()

    @Volatile
    var ready: Boolean = false
        private set

    private val folder: File get() = io.file(folderName)

    // --- 조회 -----------------------------------------------------------------------

    fun claims(playerId: UUID, uid: String): Map<String, Claim> =
        byPlayer[playerId]?.get(uid).orEmpty()

    fun claim(playerId: UUID, uid: String, tierId: String): Claim? =
        byPlayer[playerId]?.get(uid)?.get(tierId)

    fun hasClaimed(playerId: UUID, uid: String, tierId: String): Boolean =
        claim(playerId, uid, tierId) != null

    fun all(playerId: UUID): Map<String, Map<String, Claim>> =
        byPlayer[playerId]?.mapValues { it.value.toMap() }.orEmpty()

    fun knownPlayers(): Set<UUID> = byPlayer.keys.toSet()

    /** 결과를 모르는 unit 을 가진 claim. `/업적 미지급` 이 쓴다. `DEFERRED` 는 안 센다. */
    fun uncertain(playerId: UUID): List<Claim> =
        byPlayer[playerId]?.values?.flatMap { it.values }
            ?.filter { claim -> claim.units.any { it.state == UnitState.PENDING } }
            .orEmpty()

    /** 접속할 때 실행해야 할 것이 있는 claim — 미뤄둔 지급과 미뤄둔 연출. */
    fun pendingOnJoin(playerId: UUID): List<Claim> =
        byPlayer[playerId]?.values?.flatMap { it.values }
            ?.filter { claim ->
                claim.celebrateOnJoin || claim.units.any { it.state == UnitState.DEFERRED }
            }
            .orEmpty()

    // --- 쓰기 (전부 메인 스레드, 원자적) --------------------------------------------------

    /** 업적 이름을 기억해 둔다. 파일에 적는 참고값이고 열쇠가 아니라 낡아도 무해하다. */
    fun label(uid: String, display: String) {
        labels[uid] = display
    }

    /**
     * 새 claim 들을 한 번에 `PENDING` 으로 적고 **디스크에 확정한다.**
     *
     * 같은 틱에 한 사람이 업적 둘을 완료해도 쓰기는 **한 번**이다 — 신규 기록은 플레이어
     * 단위 배치고, `GRANTED` 전환만 효과 단위다.
     *
     * 이미 있는 (uid, tierId) 는 **건너뛴다.** 같은 단계를 두 번 claim 하면 보상이 두 번 나간다.
     */
    fun record(playerId: UUID, fresh: List<Claim>): List<Claim> {
        if (fresh.isEmpty()) return emptyList()
        val perPlayer = byPlayer.computeIfAbsent(playerId) { ConcurrentHashMap() }
        val added = ArrayList<Claim>(fresh.size)
        for (claim in fresh) {
            val tiers = perPlayer.computeIfAbsent(claim.uid) { ConcurrentHashMap() }
            if (tiers.containsKey(claim.tierId)) continue
            tiers[claim.tierId] = claim
            added += claim
        }
        if (added.isNotEmpty()) write(playerId)
        return added
    }

    /** 한 unit 의 상태를 올리고 **즉시 확정한다.** 지급 직후에 부른다. */
    fun mark(playerId: UUID, claim: Claim, index: Int, state: UnitState) {
        val unit = claim.units.getOrNull(index) ?: return
        if (unit.state == state) return
        unit.state = state
        write(playerId)
    }

    /** 연출까지 끝났다. 미룬 표시를 지운다. */
    fun clearCelebrateOnJoin(playerId: UUID, claim: Claim) {
        if (!claim.celebrateOnJoin) return
        claim.celebrateOnJoin = false
        write(playerId)
    }

    /** 업적이 삭제됐을 때. **관리자가 명시적으로 한 일에만** 부른다. */
    fun purge(playerId: UUID, uid: String): Boolean {
        val removed = byPlayer[playerId]?.remove(uid) != null
        if (removed) write(playerId)
        return removed
    }

    /**
     * 한 사람의 달성 기록을 전부 지운다. 관리자 초기화용.
     *
     * 빈 파일이 남지 않게 메모리에서 빼고 파일을 지운다. 지울 것이 없으면 false.
     */
    fun purgePlayer(playerId: UUID): Boolean {
        val had = byPlayer.remove(playerId) != null
        File(folder, "$playerId.yml").delete()
        return had
    }

    // --- 영속화 ---------------------------------------------------------------------

    /**
     * 전원 적재. 부팅 때 한 번, 워커에서.
     *
     * 2000명 × 1~2KB 라 몇 MB 다 — `PlayerStore` 가 같은 규모를 이미 통째로 올린다.
     * 전부 올려두면 `/업적 미지급` 과 **정의 변경 시 오프라인 포인트 재계산**이 파일 훑기가
     * 아니라 맵 조회가 된다.
     */
    fun load(then: () -> Unit) {
        io.async({
            val loaded = HashMap<UUID, ConcurrentHashMap<String, MutableMap<String, Claim>>>()
            val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".yml") } ?: emptyArray()
            for (file in files) {
                val id = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull()
                    ?: continue
                loaded[id] = readFile(YamlConfiguration.loadConfiguration(file))
            }
            loaded
        }) { loaded ->
            byPlayer.putAll(loaded)
            ready = true
            then()
        }
    }

    private fun readFile(config: YamlConfiguration): ConcurrentHashMap<String, MutableMap<String, Claim>> {
        val result = ConcurrentHashMap<String, MutableMap<String, Claim>>()
        for (uid in config.getKeys(false)) {
            val section = config.getConfigurationSection(uid) ?: continue
            section.getString("id")?.let { labels.putIfAbsent(uid, it) }
            val tiers = ConcurrentHashMap<String, Claim>()
            for (tierId in section.getKeys(false)) {
                if (tierId == "id") continue
                val tierSection = section.getConfigurationSection(tierId) ?: continue
                Claim.load(uid, tierId, tierSection)?.let { tiers[tierId] = it }
            }
            if (tiers.isNotEmpty()) result[uid] = tiers
        }
        return result
    }

    /**
     * 한 사람의 파일을 통째로 다시 쓴다. **임시 파일에 쓰고 원자적으로 바꿔 끼운다.**
     *
     * 쓰는 도중에 죽으면 `ConfigService.save` 는 잘린 YAML 을 남긴다(temp+rename 이 없다).
     * 여기서만은 그걸 허용할 수 없다.
     */
    private fun write(playerId: UUID) {
        val perPlayer = byPlayer[playerId] ?: return
        val config = YamlConfiguration()
        for ((uid, tiers) in perPlayer) {
            if (tiers.isEmpty()) continue
            val section = config.createSection(uid)
            labels[uid]?.let { section.set("id", it) }
            for ((tierId, claim) in tiers) claim.save(section.createSection(tierId))
        }

        folder.mkdirs()
        val target = File(folder, "$playerId.yml")
        val temp = File(folder, "$playerId.yml.tmp")
        try {
            temp.writeText(config.saveToString(), Charsets.UTF_8)
            moveAtomically(temp, target)
        } catch (t: Throwable) {
            io.logger.severe("업적 기록을 저장하지 못했습니다 ($playerId): " + t.message)
            temp.delete()
        }
    }

    private fun moveAtomically(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // 파일 시스템이 거부하면 비원자로 떨어진다. 한 번만 알린다.
            if (warnedNonAtomic.compareAndSet(false, true)) {
                io.logger.warning("이 파일 시스템은 원자적 교체를 지원하지 않습니다 — 업적 기록 저장이 덜 안전합니다")
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private val warnedNonAtomic = java.util.concurrent.atomic.AtomicBoolean(false)
}
