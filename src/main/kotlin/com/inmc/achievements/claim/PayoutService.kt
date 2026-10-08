package com.inmc.achievements.claim

import com.inmc.achievements.Achievements
import kr.inmc.core.reward.RewardBundle
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.random.Random

/**
 * 보상을 **효과 하나씩** 지급하고 그때마다 기록을 확정한다.
 *
 * ## 순서가 전부다
 *
 * ```
 * 확정된 payout 을 전부 PENDING 으로 기록  →  디스크 확정
 *   →  unit 하나 지급  →  그 unit 만 GRANTED  →  디스크 확정  →  다음 unit …
 * ```
 *
 * 끝에 한꺼번에 적으면 깃발 하나와 완전히 같아진다. 돈+아이템+명령어 번들에서 명령어 직전에
 * 죽었을 때, 깃발 하나면 관리자는 *전부 재지급(돈 복제)* 이냐 *전부 포기(명령어 누락)* 냐를
 * 골라야 하고, 효과 단위면 **명령어만** 다시 돌린다.
 *
 * ## 되살릴 때 자동 재지급하지 않는다
 *
 * 지급과 `GRANTED` 사이에서 죽었는지 알 방법이 **없다.** 재지급은 복제, 안 하면 누락이고
 * **누락이 복구 가능한 쪽**이라 그쪽을 택한다. `PENDING` 으로 남은 것은 `/업적 미지급` 에
 * 올려 관리자가 정한다. 창이 밀리초라 평소엔 비어 있다.
 */
class PayoutService(private val ach: Achievements) {

    /**
     * 번들을 **효과 단위로 평탄화한다.** `resolve` 를 여기서 끝내는 것이 핵심이다 —
     * `ROLL_ONE`/`ROLL_N` 은 무작위라 나중에 다시 돌리면 다른 것이 나온다.
     *
     * `announce` 는 가져오지 않는다. 그쪽까지 켜면 [com.inmc.achievements.celebrate.Celebrations]
     * 의 공지와 겹쳐 **두 번 나간다.** 알리는 일은 전부 연출 층이 맡는다.
     */
    fun freeze(bundle: RewardBundle, title: String): List<PayoutUnit> {
        val units = ArrayList<PayoutUnit>(4)
        if (!bundle.isEmpty()) {
            val payout = ach.rewards.resolve(bundle, Random.Default)
            if (payout.money > 0.0) units += PayoutUnit(UnitType.MONEY, money = payout.money)
            for ((currency, amount) in payout.moneyBy) units += PayoutUnit(UnitType.MONEY, money = amount, currency = currency)
            for (stack in payout.stacks) units += PayoutUnit(UnitType.ITEM, stack = stack.clone())
            for (command in payout.commands) units += PayoutUnit(UnitType.COMMAND, command = command)
            if (payout.unresolved > 0) {
                ach.logger.warning("보상 ${payout.unresolved}개를 만들지 못했습니다 - 아이템을 주는 플러그인이 꺼져 있을 수 있습니다")
            }
        }
        if (title.isNotBlank()) units += PayoutUnit(UnitType.TITLE, title = title)
        return units
    }

    /**
     * 방금 기록한 claim 을 지급한다. **부르는 쪽이 이미 `PENDING` 으로 확정해 둔 상태다.**
     *
     * 이 `PENDING` 은 "방금 적었고 지금 줄 것" 이라는 뜻이다. 되살린 뒤의 `PENDING`
     * ("줬는지 모름")과 글자는 같지만 **이 함수는 기록 직후에만** 불린다.
     */
    fun settleFresh(playerId: UUID, claim: Claim) = deliverAll(playerId, claim, UnitState.PENDING)

    /**
     * 접속했을 때 **미뤄둔 것만** 준다.
     *
     * `PENDING` 은 건드리지 않는다 — 되살린 뒤의 `PENDING` 은 크래시로 **줬는지 모르는** 것이라
     * 다시 주면 돈이 복제될 수 있다. 그건 `/업적 관리 미지급` 에서 관리자가 정한다.
     */
    fun settleDeferred(playerId: UUID, claim: Claim) = deliverAll(playerId, claim, UnitState.DEFERRED)

    private fun deliverAll(playerId: UUID, claim: Claim, only: UnitState) {
        val player = Bukkit.getPlayer(playerId)
        for ((index, unit) in claim.units.withIndex()) {
            if (unit.state != only) continue
            val outcome = deliver(playerId, player, unit)
            if (outcome != unit.state) ach.claims.mark(playerId, claim, index, outcome)
        }
    }

    /**
     * 효과 하나를 실제로 준다. 돌려주는 값이 **새 상태**다.
     *
     * [UnitState.DEFERRED] 는 "일부러 미뤘다" 는 뜻이라 `/업적 미지급` 에 안 오른다.
     * 칭호가 그 경우다 — 타이틀포지 API 가 `Player` 를 받고, 우리 편의로 그 API 를 넓히지
     * 않는 것이 이 경계의 요점이다.
     */
    private fun deliver(playerId: UUID, player: Player?, unit: PayoutUnit): UnitState =
        when (unit.type) {
            // 입금이 거절된 것은 "줬는지 모름" 이 아니라 **안 준 것이 확실**하다. PENDING 으로
            // 두면 Vault 가 없는 서버에서 돈 보상이 전부 영원히 '확인 필요' 에 쌓인다.
            // 미뤄 두면 경제 플러그인을 설치한 뒤 다음 접속 때 들어간다.
            UnitType.MONEY -> {
                val target = Bukkit.getOfflinePlayer(playerId)
                if (ach.economy.isEnabled && ach.economy.deposit(target, unit.money, unit.currency)) UnitState.GRANTED
                else UnitState.DEFERRED
            }

            UnitType.ITEM -> {
                val stack = unit.stack
                when {
                    stack == null -> UnitState.GRANTED
                    player != null -> {
                        // 넘치면 core 가 바닥에 떨구거나 우편함으로 보낸다. 둘 다 "줬다" 이다.
                        val leftover = player.inventory.addItem(stack.clone())
                        if (leftover.isEmpty()) UnitState.GRANTED else mail(playerId, leftover.values)
                    }
                    else -> mail(playerId, listOf(stack))
                }
            }

            UnitType.COMMAND -> {
                val name = nameOf(playerId, player)
                val line = unit.command
                    .replace("{player}", name)
                    .replace("{플레이어}", name)
                    .replace("{플레이어네임}", name)
                runCatching {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line)
                }.onFailure {
                    ach.logger.warning("보상 명령어 실행에 실패했습니다 ($line): " + it.message)
                }.let { if (it.isSuccess) UnitState.GRANTED else UnitState.PENDING }
            }

            // 오프라인이면 미룬다. 접속할 때 JoinListener 가 다시 부른다.
            UnitType.TITLE -> when {
                player == null -> UnitState.DEFERRED
                !ach.titles.isEnabled -> UnitState.DEFERRED
                ach.titles.grant(player, unit.title) -> UnitState.GRANTED
                else -> UnitState.PENDING
            }
        }

    /** 우편함으로. 실패하면 준 것으로 치지 않는다. */
    private fun mail(playerId: UUID, stacks: Collection<org.bukkit.inventory.ItemStack>): UnitState {
        if (stacks.isEmpty()) return UnitState.GRANTED
        val payout = kr.inmc.core.reward.RewardService.Payout(stacks.toMutableList())
        return runCatching {
            ach.rewards.mail(playerId, payout, SOURCE)
            UnitState.GRANTED
        }.getOrElse {
            ach.logger.warning("보상을 우편함에 넣지 못했습니다 ($playerId): " + it.message)
            UnitState.PENDING
        }
    }

    private fun nameOf(playerId: UUID, player: Player?): String =
        player?.name ?: Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString()

    private companion object {
        const val SOURCE = "업적"
    }
}
