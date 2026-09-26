package com.inmc.achievements

import com.inmc.achievements.achievement.Condition
import com.inmc.achievements.achievement.StateKind
import org.bukkit.Material
import org.bukkit.Statistic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 조건 지문.
 *
 * **뜻이 같은 두 조건은 반드시 같은 지문을 내야 한다.** 그러지 않으면 관리자가 GUI 에서
 * 값을 다시 고른 것만으로 전원의 진행도가 0이 된다 — 오류도 없고 로그도 없다.
 */
class SignatureTest {

    @Test
    fun `일치 조건의 키 순서가 달라도 같은 지문이다`() {
        // Map 을 그대로 문자열화하면 삽입 순서가 지문에 새어 들어간다.
        val a = Condition.Signal("fishing", "catch", "tuna", linkedMapOf("grade" to "S", "trophy" to "t"))
        val b = Condition.Signal("fishing", "catch", "tuna", linkedMapOf("trophy" to "t", "grade" to "S"))

        assertEquals(a.signature, b.signature)
    }

    @Test
    fun `값에 구분자가 들어가도 다른 조건은 다른 지문이다`() {
        // 이스케이프가 없으면 `a|b` 와 `a`,`b` 가 같은 지문을 낼 수 있다.
        val one = Condition.Signal("s", "t", "a|b").signature
        val two = Condition.Signal("s", "t", "a\\|b").signature
        val three = Condition.Signal("s", "t", "a\\\\b").signature

        assertNotEquals(one, two)
        assertNotEquals(two, three)
        assertNotEquals(one, three)
    }

    @Test
    fun `대상이 달라지면 지문이 달라진다`() {
        val oak = Condition.Stat(Statistic.MINE_BLOCK, material = Material.OAK_LOG)
        val birch = Condition.Stat(Statistic.MINE_BLOCK, material = Material.BIRCH_LOG)

        assertNotEquals(oak.signature, birch.signature)
    }

    @Test
    fun `같은 조건은 몇 번을 물어도 같은 지문이다`() {
        val condition = Condition.State(StateKind.GROUP, "vip")

        assertEquals(condition.signature, condition.signature)
        assertEquals(condition.signature, Condition.State(StateKind.GROUP, "vip").signature)
    }

    @Test
    fun `갈래가 다르면 지문이 겹치지 않는다`() {
        val signatures = setOf(
            Condition.Stat(Statistic.MOB_KILLS).signature,
            Condition.Signal("a", "b").signature,
            Condition.Custom("a", "b").signature,
            Condition.State(StateKind.PERMISSION, "a").signature,
        )

        assertEquals(4, signatures.size)
    }
}

/**
 * 통계 조건의 인자 수.
 *
 * `Statistic.MINE_BLOCK` 을 1인자 `getStatistic` 에 넘기면 `IllegalArgumentException` 이다.
 * **그 검사는 정의를 저장할 때 하고 5초 스윕 안에서는 절대 하지 않는다** — 스윕 안에서
 * 터지면 한 사람 때문에 나머지가 건너뛰어진다.
 */
class StatArityTest {

    @Test
    fun `대상이 필요 없는 통계에 대상을 주면 거부한다`() {
        assertEquals(true, Condition.Stat(Statistic.MOB_KILLS).isWellFormed())
        assertEquals(
            false,
            Condition.Stat(Statistic.MOB_KILLS, material = Material.OAK_LOG).isWellFormed(),
        )
    }

    @Test
    fun `블록 통계는 재질을 요구한다`() {
        assertEquals(false, Condition.Stat(Statistic.MINE_BLOCK).isWellFormed())
        assertEquals(
            true,
            Condition.Stat(Statistic.MINE_BLOCK, material = Material.OAK_LOG).isWellFormed(),
        )
    }

    @Test
    fun `몹 통계는 몹 종류를 요구한다`() {
        assertEquals(false, Condition.Stat(Statistic.KILL_ENTITY).isWellFormed())
        assertEquals(
            true,
            Condition.Stat(Statistic.KILL_ENTITY, entity = org.bukkit.entity.EntityType.ZOMBIE)
                .isWellFormed(),
        )
        // 재질과 몹을 동시에 주는 것은 어느 쪽 오버로드를 부를지 모호하다.
        assertEquals(
            false,
            Condition.Stat(
                Statistic.KILL_ENTITY,
                material = Material.OAK_LOG,
                entity = org.bukkit.entity.EntityType.ZOMBIE,
            ).isWellFormed(),
        )
    }

    @Test
    fun `상태 조건만 단계를 못 가진다`() {
        // GROUP(vip) 에 100/500/1000 단계는 뜻이 없다. 나머지 셋은 누적이라 뜻이 있다.
        assertEquals(true, Condition.Stat(Statistic.MOB_KILLS).supportsTiers)
        assertEquals(true, Condition.Signal("a", "b").supportsTiers)
        assertEquals(true, Condition.Custom("a").supportsTiers)
        assertEquals(false, Condition.State(StateKind.GROUP, "vip").supportsTiers)
    }
}
