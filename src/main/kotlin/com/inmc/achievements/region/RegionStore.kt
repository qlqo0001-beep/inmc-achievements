package com.inmc.achievements.region

import kr.inmc.core.config.ConfigService
import kr.inmc.core.store.DefinitionKey
import kr.inmc.core.store.YamlFileStore
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.logging.Logger
import kotlin.math.max
import kotlin.math.min

/**
 * 업적이 쓰는 지역. **WorldGuard 에 의존하지 않는다.**
 *
 * 필요한 것이 "이 좌표가 이 상자 안인가" 하나뿐이라 남의 플러그인을 필수 의존으로 만들 이유가
 * 없다. 관리자는 두 지점을 찍어 만든다.
 *
 * ## 표본형이 기본이다
 *
 * 평가는 통계와 **같은 5초 스윕**에서 돈다 — 리스너가 늘지 않는 대신 **5초 안에 지나가
 * 버리면 발견되지 않는다.** 그게 버그가 아니라 사양이고 화면에 그렇게 적는다.
 *
 * 다만 "비밀 장소를 밟으면 히든 업적 공개" 가 표본형이 실패하는 바로 그 경우다. 밟았는데
 * 아무 일도 안 일어나면 플레이어는 영영 모른다. 그래서 지역마다 [Region.precise] 를 둬서
 * 그 지역만 이동 기반 검사에 넣는다.
 */
class RegionStore(
    files: ConfigService,
    private val logger: Logger,
) : YamlFileStore(files, listOf(FILE), HEADER, "지역") {

    private val byName = LinkedHashMap<String, Region>()

    /** 이동 기반으로 볼 지역만. 월드별로 미리 갈라 둔다 — 이동 핸들러가 훑는 것이 이것뿐이다. */
    private var preciseByWorld: Map<String, List<Region>> = emptyMap()

    val size: Int get() = byName.size

    fun all(): List<Region> = byName.values.toList()

    fun get(name: String?): Region? = name?.let { byName[it.lowercase()] }

    fun names(): List<String> = byName.keys.toList()

    /** 이동 기반으로 볼 지역이 하나라도 있는가. 없으면 리스너가 아무 일도 안 한다. */
    fun hasPrecise(): Boolean = preciseByWorld.isNotEmpty()

    fun preciseIn(world: String): List<Region> = preciseByWorld[world].orEmpty()

    /**
     * 이 좌표가 그 지역 안인가. 지역을 모르면 **null** 이다 — false 가 아니다.
     * 모르는 것을 "밖에 있다" 로 다루면 그 조건이 영영 조용히 미완료가 된다.
     */
    fun contains(name: String?, location: Location): Boolean? =
        get(name)?.contains(location)

    fun put(region: Region) {
        byName[region.name] = region
        reindex()
        markDirty()
    }

    fun remove(name: String): Boolean {
        val removed = byName.remove(name.lowercase()) != null
        if (removed) {
            reindex()
            markDirty()
        }
        return removed
    }

    private fun reindex() {
        preciseByWorld = byName.values.filter { it.precise }.groupBy { it.world }
    }

    /** 설계 전제가 "1~3개" 다. 전제가 깨지면 조용히 느려지므로 적재 때 알린다. */
    fun warnIfCrowded(limit: Int) {
        for ((world, regions) in preciseByWorld) {
            if (regions.size > limit) {
                logger.warning(
                    "월드 '$world' 에 정밀 검사 지역이 ${regions.size}개 있습니다 " +
                        "- 이동마다 그만큼 검사합니다. 꼭 필요한 곳만 정밀로 두세요.",
                )
            }
        }
    }

    override fun read(config: YamlConfiguration) {
        byName.clear()
        rejected.clear()
        val root = config.getConfigurationSection(ROOT)
        if (root != null) {
            for (key in root.getKeys(false)) {
                val section = root.getConfigurationSection(key) ?: continue
                val region = Region.load(key.lowercase(), section)
                if (region == null) {
                    // 지우지 않는다. 저장은 들고 있는 것만 적으므로 그냥 두면 다음 저장에서 사라진다.
                    rejected[key] = section
                    logger.warning("지역 '$key' 을(를) 읽지 못했습니다 - 파일에는 그대로 남깁니다")
                    continue
                }
                byName[region.name] = region
            }
        }
        reindex()
    }

    override fun write(config: YamlConfiguration) {
        val root = config.createSection(ROOT)
        for (region in byName.values) region.save(root.createSection(region.name))
        for ((key, section) in rejected) {
            if (root.contains(key)) continue
            com.inmc.achievements.util.Sections.copy(section, root.createSection(key))
        }
    }

    /** 읽지 못한 지역의 원문. */
    private val rejected = LinkedHashMap<String, org.bukkit.configuration.ConfigurationSection>()

    private companion object {
        const val FILE = "regions.yml"
        const val ROOT = "regions"
        const val HEADER = "업적이 쓰는 지역. /업적 관리 에서 두 지점을 찍어 만드는 것을 권장합니다."
    }
}

/** 축에 나란한 직육면체 하나. 경계를 포함한다. */
data class Region(
    val name: String,
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    /** 이동할 때마다 볼지. 기본은 5초 표본. */
    val precise: Boolean = false,
    val display: String = "",
) {

    fun contains(location: Location): Boolean {
        if (location.world?.name != world) return false
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    fun save(section: ConfigurationSection) {
        section.set("world", world)
        section.set("min", listOf(minX, minY, minZ))
        section.set("max", listOf(maxX, maxY, maxZ))
        if (precise) section.set("precise", true)
        if (display.isNotBlank()) section.set("display", display)
    }

    companion object {

        /** 두 지점으로 만든다. 어느 쪽이 min 인지 부르는 쪽이 신경 쓸 필요가 없다. */
        fun between(name: String, a: Location, b: Location, precise: Boolean = false): Region? {
            val world = a.world?.name ?: return null
            if (b.world?.name != world) return null
            return Region(
                name = name.lowercase(),
                world = world,
                minX = min(a.blockX, b.blockX),
                minY = min(a.blockY, b.blockY),
                minZ = min(a.blockZ, b.blockZ),
                maxX = max(a.blockX, b.blockX),
                maxY = max(a.blockY, b.blockY),
                maxZ = max(a.blockZ, b.blockZ),
                precise = precise,
            )
        }

        fun load(name: String, section: ConfigurationSection): Region? {
            if (!DefinitionKey.isValid(name)) return null
            val world = section.getString("world")?.takeIf { it.isNotBlank() } ?: return null
            val min = section.getIntegerList("min").takeIf { it.size == 3 } ?: return null
            val max = section.getIntegerList("max").takeIf { it.size == 3 } ?: return null
            return Region(
                name = name,
                world = world,
                minX = min(min[0], max[0]),
                minY = min(min[1], max[1]),
                minZ = min(min[2], max[2]),
                maxX = max(min[0], max[0]),
                maxY = max(min[1], max[1]),
                maxZ = max(min[2], max[2]),
                precise = section.getBoolean("precise", false),
                display = section.getString("display").orEmpty(),
            )
        }
    }
}
