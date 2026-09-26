package com.inmc.achievements.achievement

import com.inmc.achievements.util.Sections
import kr.inmc.core.config.ConfigService
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.logging.Logger

/**
 * `achievements.yml` 한 벌.
 *
 * 색인을 **uid 와 id 양쪽으로** 들고 있다. uid 는 플레이어 데이터가 가리키는 열쇠라 조회가
 * 잦고, id 는 관리자와 명령어가 쓰는 이름이다.
 *
 * ## 적재 때 거부하는 것
 *
 * 전부 **조용히 망가지는 것을 막는 장치**다. 거부한 것은 이름과 이유를 대고 로그에 남긴다.
 *
 * | 무엇 | 왜 |
 * |---|---|
 * | `uid` 중복 | 두 업적이 같은 기록을 공유한다 |
 * | `id` 중복 | 명령어·GUI 가 어느 쪽을 가리키는지 정해지지 않는다 |
 * | 한 업적 안의 `Tier.id` 중복 | 기록이 어느 단계 것인지 모호해진다 |
 * | 같은 임계값 둘 | 어느 단계가 먼저인지 정해지지 않는다 (순서는 정렬로 맞춘다 — 정체는 id 다) |
 * | 선행 그래프 순환 | 관련된 업적이 **전부 영원히 미완료**가 된다 |
 *
 * **거부한 것은 파일에서 지우지 않는다.** 저장은 빈 YAML 에 들고 있는 것만 적으므로, 그냥
 * 두면 다음 저장에서 영구히 사라진다. 원문을 들고 있다가 그대로 되써 넣는다.
 *
 * 끊긴 참조(지워진 업적을 가리킴)는 **거부하지 않는다.** 거부하면 그 업적이 목록에서
 * 사라져 관리자가 고칠 수단을 잃고 연쇄된다. 살려두고 그 조건만 무효로 다룬다.
 */
class AchievementRegistry(
    files: ConfigService,
    private val logger: Logger,
) : YamlFileStore(files, listOf(FILE), HEADER, "업적") {

    private val byUid = LinkedHashMap<String, Achievement>()
    private val byId = LinkedHashMap<String, Achievement>()

    /** 읽지 못한 항목의 원문. 저장할 때 그대로 되써 넣는다. */
    private val rejected = LinkedHashMap<String, ConfigurationSection>()

    /** 적재 중에 uid 를 새로 붙인 것이 있으면 true. 부르는 쪽이 즉시 확정 저장한다. */
    @Volatile
    private var bootstrapped = false

    // --- 조회 -------------------------------------------------------------------

    val size: Int get() = byUid.size

    fun all(): List<Achievement> = byUid.values.toList()

    fun byUid(uid: String?): Achievement? = uid?.let { byUid[it] }

    fun byId(id: String?): Achievement? = id?.let { byId[it.lowercase()] }

    /** id 든 uid 든 받는다. 명령어가 쓴다. */
    fun find(key: String?): Achievement? = byUid(key) ?: byId(key)

    fun exists(id: String?): Boolean = byId(id) != null

    /**
     * 이 이름이 **읽지 못한 채 파일에 남아 있는가.** 새로 만들 때 이 이름을 쓰면 그 원문을
     * 덮게 되므로 막는다.
     */
    fun isRejected(id: String?): Boolean = id != null && rejected.containsKey(id.lowercase())

    fun rejectedIds(): List<String> = rejected.keys.toList()

    fun inCategory(category: Category): List<Achievement> =
        byUid.values.filter { it.category == category }

    /** 유저 목록에 보일 것. 숨김은 발견한 사람에게만 보이므로 여기서 거르지 않는다. */
    fun listed(): List<Achievement> = byUid.values.filter { it.listed }

    /** 전체 개수. `countsTowardTotal` 이 꺼진 것은 빠진다. */
    fun totalCount(): Int = byUid.values.count { it.countsTowardTotal }

    /** 이 업적을 가리키는 것들. 삭제를 막을 때 쓴다. */
    fun referencesTo(uid: String): List<Achievement> = byUid.values.filter { other ->
        other.uid != uid && (
            other.next == uid ||
                (other.condition as? Condition.State)
                    ?.takeIf { it.kind == StateKind.ACHIEVEMENT }?.value == uid ||
                (other.discovery.kind == DiscoveryKind.ACHIEVEMENT && other.discovery.value == uid)
            )
    }

    // --- 변경 -------------------------------------------------------------------

    /**
     * 새로 넣거나 갈아끼운다. **적재 때 거부할 상태면 넣지 않고 그 이유를 돌려준다.**
     *
     * GUI 가 여기를 거치지 않고 무효 상태를 만들 수 있으면, 저장은 되지만 다음 리로드에서
     * 거부돼 **그 업적이 목록에서 사라진다.** (같은 임계값 둘, 단계가 생긴 '발견 즉시 달성',
     * 선행 조건 순환이 전부 GUI 로 만들 수 있었다.)
     */
    fun put(value: Achievement): Validation.Problem? {
        Validation.check(value)?.let { return it }
        val candidates = byUid.values.filter { it.uid != value.uid } + value
        if (value.uid in Validation.findCycles(candidates)) return Validation.Problem.CYCLE

        byUid[value.uid]?.let { byId.remove(it.id) }
        byUid[value.uid] = value
        byId[value.id] = value
        markDirty()
        return null
    }

    fun remove(uid: String): Achievement? {
        val removed = byUid.remove(uid) ?: return null
        byId.remove(removed.id)
        markDirty()
        return removed
    }

    // --- 영속화 (둘 다 메인 스레드) -------------------------------------------------

    override fun read(config: YamlConfiguration) {
        byUid.clear()
        byId.clear()
        rejected.clear()
        bootstrapped = false

        val root = config.getConfigurationSection(ROOT) ?: return
        val pending = LinkedHashMap<String, Achievement>()
        val sections = HashMap<String, ConfigurationSection>()

        for (key in root.getKeys(false)) {
            val section = root.getConfigurationSection(key) ?: continue
            val id = key.lowercase()

            if (pending.values.any { it.id == id }) {
                reject(key, section, "이름이 중복됩니다")
                continue
            }

            // uid 가 없으면 여기서 만들어 섹션에 박아둔다. 서비스에 올리는 것은
            // 파일이 확정된 뒤다 - 부르는 쪽이 bootstrapped 를 보고 즉시 저장한다.
            if (!Achievement.isValidUid(section.getString("uid")?.trim())) {
                val uid = freshUid(pending)
                section.set("uid", uid)
                bootstrapped = true
                logger.info("업적 '$id' 에 식별자를 새로 부여했습니다: $uid")
            }

            val achievement = Achievement.load(id, section)
            if (achievement == null) {
                reject(key, section, "읽지 못했습니다")
                continue
            }
            if (pending.containsKey(achievement.uid)) {
                reject(key, section, "식별자 '${achievement.uid}' 가 중복됩니다")
                continue
            }
            val problem = Validation.check(achievement)
            if (problem != null) {
                reject(key, section, problem.describe)
                continue
            }

            pending[achievement.uid] = achievement
            sections[achievement.uid] = section
        }

        val cycles = Validation.findCycles(pending.values)
        for ((uid, achievement) in pending) {
            if (uid in cycles) {
                reject(achievement.id, sections.getValue(uid), Validation.Problem.CYCLE.describe)
                continue
            }
            byUid[uid] = achievement
            byId[achievement.id] = achievement
        }
    }

    private fun reject(key: String, section: ConfigurationSection, reason: String) {
        rejected[key.lowercase()] = section
        logger.warning("업적 '$key' - $reason - 불러오지 않습니다 (파일에는 그대로 남깁니다)")
    }

    override fun write(config: YamlConfiguration) {
        val root = config.createSection(ROOT)
        for (achievement in byUid.values) achievement.save(root.createSection(achievement.id))
        // 읽지 못한 것도 되써 넣는다. 안 그러면 다음 저장에서 영구히 사라진다.
        for ((key, section) in rejected) {
            if (root.contains(key)) continue
            Sections.copy(section, root.createSection(key))
        }
    }

    /**
     * 적재 중에 uid 를 붙였으면 **파일을 즉시 확정한다.**
     *
     * 일반 비동기 저장에 맡기면 그 사이에 진행이 쌓이고, 서버가 죽었을 때 다음 시작에 uid 가
     * 새로 생겨 **기록이 고아가 된다.** 드물게 일어나는 일이라 메인 스레드 쓰기를 감당한다.
     */
    fun confirmBootstrap(): Boolean {
        if (!bootstrapped) return false
        val config = YamlConfiguration()
        config.options().setHeader(listOf(HEADER))
        write(config)
        val target = io.file(FILE)
        val temp = File(target.parentFile, "$FILE.tmp")
        return runCatching {
            target.parentFile?.mkdirs()
            temp.writeText(config.saveToString(), Charsets.UTF_8)
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            bootstrapped = false
            true
        }.getOrElse {
            logger.severe("업적 식별자를 확정 저장하지 못했습니다: " + it.message)
            temp.delete()
            false
        }
    }

    private fun freshUid(pending: Map<String, Achievement>): String {
        var candidate = Achievement.newUid()
        while (pending.containsKey(candidate)) candidate = Achievement.newUid()
        return candidate
    }

    private companion object {
        const val FILE = "achievements.yml"
        const val ROOT = "achievements"
        const val HEADER = "업적 정의. GUI(/업적 관리)에서 만드는 것을 권장합니다."
    }
}
