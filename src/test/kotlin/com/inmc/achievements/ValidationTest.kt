package com.inmc.achievements

import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Condition
import com.inmc.achievements.achievement.Discovery
import com.inmc.achievements.achievement.DiscoveryKind
import com.inmc.achievements.achievement.StateKind
import com.inmc.achievements.achievement.Tier
import com.inmc.achievements.achievement.Validation
import org.bukkit.Statistic
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 적재할 때 거부하는 것들. 전부 **조용히 깨지는 것**을 막는 장치다. */
class ValidationTest {

    private fun of(
        uid: String = "a1b2c3d4e5f6",
        id: String = "test",
        tiers: List<Tier> = emptyList(),
        condition: Condition? = Condition.Stat(Statistic.MOB_KILLS),
        discovery: Discovery = Discovery.AUTO,
    ) = Achievement(uid = uid, id = id, display = id, condition = condition, tiers = tiers, discovery = discovery)

    @Test
    fun `멀쩡한 업적은 통과한다`() {
        assertNull(Validation.check(of(tiers = listOf(Tier("t1", 10), Tier("t2", 100)))))
    }

    @Test
    fun `단계 이름이 겹치면 거부한다`() {
        // 기록이 어느 단계 것인지 모호해진다.
        assertEquals(
            Validation.Problem.TIER_ID_DUPLICATE,
            Validation.check(of(tiers = listOf(Tier("t1", 10), Tier("t1", 100)))),
        )
    }

    @Test
    fun `임계값이 순증가가 아니면 거부한다`() {
        // 조용히 정렬하면 관리자가 적은 것과 도는 것이 달라진다.
        assertEquals(
            Validation.Problem.TIER_NOT_ASCENDING,
            Validation.check(of(tiers = listOf(Tier("t1", 100), Tier("t2", 10)))),
        )
        assertEquals(
            Validation.Problem.TIER_NOT_ASCENDING,
            Validation.check(of(tiers = listOf(Tier("t1", 10), Tier("t2", 10)))),
            "같은 값도 안 된다 - 둘 중 어느 쪽이 먼저인지 정해지지 않는다",
        )
    }

    @Test
    fun `상태 조건에 단계를 붙이면 거부한다`() {
        assertEquals(
            Validation.Problem.TIER_ON_STATE,
            Validation.check(
                of(
                    condition = Condition.State(StateKind.GROUP, "vip"),
                    tiers = listOf(Tier("t1", 100)),
                ),
            ),
        )
    }

    @Test
    fun `발견 즉시 달성에 단계를 붙이면 거부한다`() {
        // 어느 단계를 줄지 정할 수 없다.
        assertEquals(
            Validation.Problem.COMPLETE_ON_DISCOVER_WITH_TIERS,
            Validation.check(
                of(
                    tiers = listOf(Tier("t1", 100)),
                    discovery = Discovery(DiscoveryKind.AUTO, "", completeOnDiscover = true),
                ),
            ),
        )
    }

    @Test
    fun `단계가 상한을 넘으면 거부한다`() {
        val tiers = (1..Tier.MAX + 1).map { Tier("t$it", it.toLong()) }

        assertEquals(Validation.Problem.TIER_TOO_MANY, Validation.check(of(tiers = tiers)))
    }

    // --- 순환 -----------------------------------------------------------------------

    private fun requiring(uid: String, prerequisite: String?) = of(
        uid = uid,
        id = uid,
        condition = prerequisite?.let { Condition.State(StateKind.ACHIEVEMENT, it) }
            ?: Condition.Stat(Statistic.MOB_KILLS),
    )

    @Test
    fun `곧은 사슬은 순환이 아니다`() {
        val chain = listOf(
            requiring("aaaaaaaaaaaa", null),
            requiring("bbbbbbbbbbbb", "aaaaaaaaaaaa"),
            requiring("cccccccccccc", "bbbbbbbbbbbb"),
        )

        assertTrue(Validation.findCycles(chain).isEmpty())
    }

    @Test
    fun `세 개가 물고 물리면 셋 다 잡는다`() {
        // A→B→C→A 면 셋 다 **영원히 미완료**가 된다. 그래서 거부 대상이다.
        val cycle = listOf(
            requiring("aaaaaaaaaaaa", "cccccccccccc"),
            requiring("bbbbbbbbbbbb", "aaaaaaaaaaaa"),
            requiring("cccccccccccc", "bbbbbbbbbbbb"),
        )

        assertEquals(3, Validation.findCycles(cycle).size)
    }

    @Test
    fun `자기 자신을 요구해도 순환이다`() {
        assertEquals(
            setOf("aaaaaaaaaaaa"),
            Validation.findCycles(listOf(requiring("aaaaaaaaaaaa", "aaaaaaaaaaaa"))),
        )
    }

    @Test
    fun `순환에 들어가는 꼬리는 순환으로 치지 않는다`() {
        // D→A→B→A 에서 막히는 것은 A·B 뿐이다. D 는 그 둘을 고치면 풀린다.
        val graph = listOf(
            requiring("aaaaaaaaaaaa", "bbbbbbbbbbbb"),
            requiring("bbbbbbbbbbbb", "aaaaaaaaaaaa"),
            requiring("dddddddddddd", "aaaaaaaaaaaa"),
        )

        assertEquals(setOf("aaaaaaaaaaaa", "bbbbbbbbbbbb"), Validation.findCycles(graph))
    }

    @Test
    fun `없는 업적을 가리키는 것은 순환이 아니다`() {
        // 끊긴 참조는 거부하지 않는다 - 거부하면 그 업적이 목록에서 사라져 고칠 수단을 잃는다.
        assertTrue(Validation.findCycles(listOf(requiring("aaaaaaaaaaaa", "없는거123456"))).isEmpty())
    }
}

/** 정의의 왕복과 uid 규칙. */
class AchievementLoadTest {

    @Test
    fun `uid 가 없으면 읽지 않는다`() {
        // 즉석에서 만들어 돌려주면 그 uid 가 디스크에 닿기 전에 진행이 쌓이고,
        // 서버가 죽으면 다음 시작에 새로 생겨 기록이 고아가 된다.
        val config = YamlConfiguration()
        val section = config.createSection("test")
        section.set("display", "테스트")

        assertNull(Achievement.load("test", section))
    }

    @Test
    fun `uid 는 ASCII 여야 한다`() {
        // NamespacedKey 는 `[a-z0-9._-]` 만 받는다. 한글이면 토스트를 **지급하는 순간** 터진다.
        assertTrue(Achievement.isValidUid("a1b2c3d4e5f6"))
        assertTrue(!Achievement.isValidUid("나무꾼식별자"))
        assertTrue(!Achievement.isValidUid("ABC123"))
        assertTrue(!Achievement.isValidUid("ab"))
        assertTrue(!Achievement.isValidUid(null))
    }

    @Test
    fun `새 uid 는 규칙에 맞고 매번 다르다`() {
        val generated = (1..200).map { Achievement.newUid() }

        assertEquals(200, generated.toSet().size)
        for (uid in generated) assertTrue(Achievement.isValidUid(uid), "'$uid'")
    }

    @Test
    fun `정의가 왕복한다`() {
        val original = Achievement(
            uid = "a1b2c3d4e5f6",
            id = "나무꾼",
            display = "나무꾼",
            description = listOf("한 줄", "두 줄"),
            condition = Condition.Stat(Statistic.MINE_BLOCK, materials = listOf(org.bukkit.Material.OAK_LOG)),
            tiers = listOf(Tier("t1", 100, points = 3), Tier("t2", 1000, points = 8)),
            hidden = true,
            countsTowardTotal = false,
            discovery = Discovery(DiscoveryKind.REGION, "비밀샘"),
            next = "ffffffffffff",
        )

        val config = YamlConfiguration()
        original.save(config.createSection(original.id))
        val reread = YamlConfiguration()
        reread.loadFromString(config.saveToString())
        val restored = Achievement.load(original.id, reread.getConfigurationSection(original.id)!!)

        assertNotNull(restored)
        assertEquals(original.uid, restored.uid)
        assertEquals(original.description, restored.description)
        assertEquals(original.condition, restored.condition)
        assertEquals(original.tiers.map { it.id to it.threshold }, restored.tiers.map { it.id to it.threshold })
        assertEquals(original.hidden, restored.hidden)
        assertEquals(original.countsTowardTotal, restored.countsTowardTotal)
        assertEquals(original.discovery.kind, restored.discovery.kind)
        assertEquals(original.discovery.value, restored.discovery.value)
        assertEquals(original.next, restored.next)
    }

    @Test
    fun `여러 단계를 한 번에 넘으면 전부 돌려준다`() {
        // 보상은 전부 주고 연출은 최고 단계만. 0 에서 10000 으로 뛰는 일이 실제로 있다.
        val achievement = Achievement(
            uid = "a1b2c3d4e5f6", id = "t", display = "t",
            tiers = listOf(Tier("t1", 100), Tier("t2", 1000), Tier("t3", 10000)),
        )

        assertEquals(listOf("t1", "t2", "t3"), achievement.reached(10000).map { it.id })
        assertEquals(listOf("t1"), achievement.reached(999).map { it.id })
        assertEquals(emptyList(), achievement.reached(99).map { it.id })
        assertEquals(10000L, achievement.maxThreshold())
    }
}
