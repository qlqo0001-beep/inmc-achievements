package com.inmc.achievements.util

import kr.inmc.core.util.TokenBag
import org.bukkit.entity.Player

/**
 * 메시지 한 번 렌더링에 쓰이는 토큰 주머니.
 *
 * 한글 표기와 영문 표기를 둘 다 받는다 — 다른 5세대 플러그인과 같은 규칙이라 관리자가
 * 어느 쪽으로 적어도 돌아간다.
 */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES

    fun player(name: String): Ph = put(PLAYER, name)

    fun player(player: Player): Ph = put(PLAYER, player.name)

    fun achievement(name: String): Ph = put(ACHIEVEMENT, name)

    fun tier(name: String): Ph = put(TIER, name)

    fun category(name: String): Ph = put(CATEGORY, name)

    fun count(value: Long): Ph = put(COUNT, String.format("%,d", value))

    fun goal(value: Long): Ph = put(GOAL, String.format("%,d", value))

    fun points(value: Int): Ph = put(POINTS, String.format("%,d", value))

    fun rank(value: String): Ph = put(RANK, value)

    fun percent(value: Double): Ph = put(PERCENT, String.format("%.1f", value))

    fun amount(value: Int): Ph = put(AMOUNT, value.toString())

    fun reason(text: String): Ph = put(REASON, text)

    fun copy(): Ph = copyValuesInto(Ph())

    companion object {

        fun of(): Ph = Ph()

        const val PLAYER = "player"
        const val ACHIEVEMENT = "achievement"
        const val TIER = "tier"
        const val CATEGORY = "category"
        const val COUNT = "count"
        const val GOAL = "goal"
        const val POINTS = "points"
        const val RANK = "rank"
        const val PERCENT = "percent"
        const val AMOUNT = "amount"
        const val REASON = "reason"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어}", "{player}"),
            ACHIEVEMENT to listOf("{업적}", "{achievement}"),
            TIER to listOf("{단계}", "{tier}"),
            CATEGORY to listOf("{분류}", "{category}"),
            COUNT to listOf("{진행도}", "{count}"),
            GOAL to listOf("{목표}", "{goal}"),
            POINTS to listOf("{점수}", "{points}"),
            RANK to listOf("{랭크}", "{rank}"),
            PERCENT to listOf("{달성률}", "{percent}"),
            AMOUNT to listOf("{수량}", "{amount}"),
            REASON to listOf("{사유}", "{reason}"),
        )
    }
}
