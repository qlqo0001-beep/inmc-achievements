package com.inmc.achievements

import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Condition
import com.inmc.achievements.achievement.Tier
import com.inmc.achievements.config.AchievementsConfig
import com.inmc.achievements.config.Messages
import com.inmc.achievements.region.Region
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 배포 리소스를 **코드가 읽는 방식 그대로** 읽어 검사한다.
 *
 * 한 줄만 어긋나도 그 항목이 통째로 사라지는데 오류는 안 난다. 그게 이 테스트의 존재
 * 이유고, 낚시에서 실제로 `modifiers.yml` 의 권한 노드가 그렇게 통째로 무시되고 있었다.
 */
class ResourceTest {

    private fun resource(name: String): YamlConfiguration {
        val file = File("src/main/resources/$name")
        assertTrue(file.exists(), "$name 이 배포 리소스에 없습니다")
        return YamlConfiguration.loadConfiguration(file)
    }

    @Test
    fun `배포 messages 와 코드 기본값의 키가 정확히 같다`() {
        // 한쪽에만 있는 키는 관리자가 고쳐도 안 나오거나, 코드가 찾는데 파일에 없는 키다.
        val file = resource("messages.yml").getKeys(false).toSortedSet()
        val code = Messages.DEFAULTS.keys.toSortedSet()

        assertEquals(code, file, "파일에만: ${file - code} / 코드에만: ${code - file}")
    }

    @Test
    fun `core ChatPrompt 가 요구하는 네 키가 있다`() {
        // 없으면 관리 화면이 값을 물어본 뒤 **영영 조용히 기다린다.** 오류가 안 난다.
        val keys = resource("messages.yml").getKeys(false)
        for (key in listOf("prompt-enter", "prompt-cancelled", "prompt-timeout", "prompt-invalid-number")) {
            assertTrue(key in keys, "$key 가 없습니다")
        }
    }

    @Test
    fun `배포 config 가 코드가 읽는 값과 맞는다`() {
        val config = AchievementsConfig.from(resource("config.yml"))

        assertEquals(100L, config.sweepPeriodTicks)
        assertEquals(5, config.sweepShards)
        assertEquals(600L, config.syncPeriodTicks)
        // 완료 하나가 `1 + 효과 수` 번 디스크를 쓴다. 소급 폭주에서 이 값이 유일한 방벽이다.
        assertTrue(config.completionsPerTick in 1..4, "틱당 완료 상한이 너무 큽니다")
        assertTrue(config.rankThresholds.isNotEmpty())
    }

    @Test
    fun `랭크 임계값이 점수 순으로 정렬된다`() {
        // 파일에 뒤섞여 적혀 있어도 rankOf 가 '마지막으로 넘은 것'을 고를 수 있어야 한다.
        val config = AchievementsConfig.from(resource("config.yml"))
        val points = config.rankThresholds.map { it.first }

        assertEquals(points.sorted(), points)
        assertEquals(0, points.first(), "0점에도 줄 이름이 있어야 빈 칸이 안 생긴다")
    }

    @Test
    fun `랭크가 점수에 따라 오른다`() {
        val config = AchievementsConfig.from(resource("config.yml"))
        val low = config.rankOf(0)
        val high = config.rankOf(100_000)

        assertTrue(low.isNotBlank())
        assertTrue(high.isNotBlank())
        assertTrue(low != high)
    }

    @Test
    fun `배포 업적이 전부 읽힌다`() {
        val root = resource("achievements.yml").getConfigurationSection("achievements")
        assertNotNull(root)

        val ids = root.getKeys(false)
        assertTrue(ids.isNotEmpty())

        val loaded = ids.mapNotNull { id ->
            root.getConfigurationSection(id)?.let { Achievement.load(id, it) }
        }
        assertEquals(ids.size, loaded.size, "읽히지 않은 업적이 있습니다")
    }

    @Test
    fun `배포 업적의 식별자가 규칙에 맞고 중복이 없다`() {
        // uid 는 NamespacedKey 에 그대로 들어가므로 ASCII 여야 한다. 한글이면 토스트를
        // **지급하는 순간** IllegalArgumentException 이 난다.
        val loaded = loadAll()
        val uids = loaded.map { it.uid }

        assertEquals(uids.size, uids.toSet().size, "uid 가 중복입니다")
        for (uid in uids) assertTrue(Achievement.isValidUid(uid), "'$uid' 는 uid 규칙에 안 맞습니다")
    }

    @Test
    fun `배포 업적의 단계가 순증가이고 이름이 겹치지 않는다`() {
        for (achievement in loadAll()) {
            val tiers = achievement.tiers
            assertTrue(
                Tier.isAscending(tiers),
                "'${achievement.id}' 의 임계값이 순증가가 아닙니다",
            )
            assertEquals(
                tiers.size,
                tiers.map { it.id }.toSet().size,
                "'${achievement.id}' 의 단계 이름이 중복입니다",
            )
            assertTrue(tiers.size <= Tier.MAX, "'${achievement.id}' 의 단계가 너무 많습니다")
        }
    }

    @Test
    fun `상태 조건 업적에는 단계가 없다`() {
        // 참/거짓 조건에 누적 임계값은 뜻이 없다. 적재가 거부하므로 배포본이 그러면 안 된다.
        for (achievement in loadAll()) {
            if (achievement.condition?.supportsTiers == false) {
                assertTrue(achievement.tiers.isEmpty(), "'${achievement.id}' 가 그 조합입니다")
            }
        }
    }

    @Test
    fun `발견 즉시 달성은 단계 없는 업적에만 쓰인다`() {
        for (achievement in loadAll()) {
            if (achievement.discovery.completeOnDiscover) {
                assertTrue(achievement.tiers.isEmpty(), "'${achievement.id}' 가 그 조합입니다")
            }
        }
    }

    @Test
    fun `통계 조건의 인자 수가 맞는다`() {
        // 틀리면 5초 스윕 안에서 IllegalArgumentException 이 난다.
        for (achievement in loadAll()) {
            val condition = achievement.condition as? Condition.Stat ?: continue
            assertTrue(
                condition.isWellFormed(),
                "'${achievement.id}' 의 통계 대상이 맞지 않습니다",
            )
        }
    }

    @Test
    fun `길라잡이 사슬이 이어지고 끊긴 참조가 없다`() {
        val loaded = loadAll()
        val uids = loaded.map { it.uid }.toSet()

        for (achievement in loaded) {
            if (achievement.next.isNotBlank()) {
                assertTrue(achievement.next in uids, "'${achievement.id}' 의 next 가 없는 업적을 가리킵니다")
            }
            val discovery = achievement.discovery
            if (discovery.kind == com.inmc.achievements.achievement.DiscoveryKind.ACHIEVEMENT) {
                assertTrue(discovery.value in uids, "'${achievement.id}' 의 발견 조건이 없는 업적을 가리킵니다")
            }
            val state = achievement.condition as? Condition.State ?: continue
            if (state.kind == com.inmc.achievements.achievement.StateKind.ACHIEVEMENT) {
                assertTrue(state.value in uids, "'${achievement.id}' 의 선행 조건이 없는 업적을 가리킵니다")
            }
        }
    }

    @Test
    fun `배포 지역이 읽히고 업적이 가리키는 지역이 실재한다`() {
        val root = resource("regions.yml").getConfigurationSection("regions")
        assertNotNull(root)
        val regions = root.getKeys(false).mapNotNull { name ->
            root.getConfigurationSection(name)?.let { Region.load(name.lowercase(), it) }
        }
        assertEquals(root.getKeys(false).size, regions.size, "읽히지 않은 지역이 있습니다")

        val names = regions.map { it.name }.toSet()
        for (achievement in loadAll()) {
            val discovery = achievement.discovery
            if (discovery.kind == com.inmc.achievements.achievement.DiscoveryKind.REGION) {
                assertTrue(
                    discovery.value.lowercase() in names,
                    "'${achievement.id}' 가 없는 지역 '${discovery.value}' 를 가리킵니다",
                )
            }
        }
    }

    private fun loadAll(): List<Achievement> {
        val root = resource("achievements.yml").getConfigurationSection("achievements")!!
        return root.getKeys(false).mapNotNull { id ->
            root.getConfigurationSection(id)?.let { Achievement.load(id, it) }
        }
    }
}
