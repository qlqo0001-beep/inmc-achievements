package com.inmc.achievements.claim

import org.bukkit.configuration.ConfigurationSection

/**
 * "이 사람이 이 단계를 달성했다" 는 **기록된 사실.**
 *
 * 계산식이 아니다. `진행도 >= 임계값` 으로 매번 판정하면 관리자가 임계값을 100→200 으로
 * 올리는 순간 150 인 사람이 **소리 없이 미완료로 되돌아간다** — 포인트가 줄고 랭크가
 * 내려가고 로그에는 아무것도 안 남는다. 넘는 순간 여기 적고 다시는 계산하지 않는다.
 *
 * [units] 는 완료 시점에 `RewardService.resolve` 가 뽑아낸 것을 **얼린 것**이다. 정의가
 * 아니라 결과를 적는 이유는 `ROLL_ONE`/`ROLL_N` 번들이 **무작위**이기 때문이다 — 재시도 때
 * 정의를 다시 돌리면 빚진 것과 다른 것을 준다.
 */
data class Claim(
    val uid: String,
    val tierId: String,
    val at: Long,
    val units: MutableList<PayoutUnit> = mutableListOf(),
    /** 오프라인 완료였다. 접속할 때 연출하고 지운다. */
    var celebrateOnJoin: Boolean = false,
) {

    /** 아직 결과를 모르는 것이 있는가. `DEFERRED` 는 아는 것이므로 안 센다. */
    fun hasUncertain(): Boolean = units.any { it.state == UnitState.PENDING }

    fun hasDeferred(): Boolean = units.any { it.state == UnitState.DEFERRED }

    fun isSettled(): Boolean = units.all { it.state == UnitState.GRANTED }

    fun save(section: ConfigurationSection) {
        section.set("at", at)
        if (celebrateOnJoin) section.set("celebrate-on-join", true)
        if (units.isEmpty()) return
        val list = section.createSection("payout")
        // 위치가 곧 정체다. 숫자 키로 적어 순서가 보존되게 한다.
        units.forEachIndexed { index, unit -> unit.save(list.createSection(index.toString())) }
    }

    companion object {
        fun load(uid: String, tierId: String, section: ConfigurationSection): Claim? {
            val at = section.getLong("at", 0L)
            if (at <= 0L) return null
            val units = mutableListOf<PayoutUnit>()
            section.getConfigurationSection("payout")?.let { list ->
                // 키를 숫자로 정렬한다. 문자열 순서면 10 이 2 보다 앞에 온다.
                for (key in list.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }) {
                    list.getConfigurationSection(key)?.let { PayoutUnit.load(it)?.let(units::add) }
                }
            }
            return Claim(
                uid = uid,
                tierId = tierId,
                at = at,
                units = units,
                celebrateOnJoin = section.getBoolean("celebrate-on-join", false),
            )
        }
    }
}
