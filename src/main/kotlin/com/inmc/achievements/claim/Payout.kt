package com.inmc.achievements.claim

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack

/**
 * 지급 효과 하나. **번들도 엔트리도 아니고 효과 하나다.**
 *
 * `RewardEntry` **하나**가 이미 `item`·`money`·`commands` 를 동시에 갖는다. 그래서 "엔트리
 * 단위 상태" 로는 부족하다 — 엔트리 하나 안에서 돈은 나가고 명령어는 안 나간 채로 죽으면
 * 그 엔트리는 미완이고, 재시도하면 **돈이 또 나간다.**
 *
 * 평탄화해 두면 `/업적 미지급` 이 정말로 안 나간 것만 다시 돌릴 수 있다.
 *
 * `id` 필드를 두지 않는 것은 의도다 — 이 목록은 완료 시점에 **얼어붙은 스냅샷**이라
 * 위치가 곧 정체다. `command:0` 같은 id 를 붙이면 번들에 엔트리가 여럿일 때 **엔트리 간에
 * 충돌한다.** 대신 위치는 **그 스냅샷 안에서만** 식별자다 — 정의가 바뀐 뒤 다시 뽑은
 * payout 과 위치로 대조하지 않는다.
 */
data class PayoutUnit(
    val type: UnitType,
    val money: Double = 0.0,
    /**
     * **완성된 스택 그대로** 담는다. `StoredItem` 참조가 아니다.
     *
     * 참조로 두면 재시도할 때 그 정의를 다시 풀게 되는데, 그 사이에 관리자가 아이템을 고쳤으면
     * **빚진 것과 다른 것**이 나간다. `RewardService.resolve` 가 이미 실제 스택까지 풀어주므로
     * 그걸 그대로 얼린다. Bukkit 이 `ItemStack` 을 YAML 로 직렬화할 수 있다.
     */
    val stack: ItemStack? = null,
    val command: String = "",
    /** `타입:아이디`. 타이틀포지에 넘길 값. */
    val title: String = "",
    var state: UnitState = UnitState.PENDING,
) {

    fun save(section: ConfigurationSection) {
        section.set("type", type.name)
        section.set("state", state.name)
        when (type) {
            UnitType.MONEY -> section.set("money", money)
            UnitType.ITEM -> section.set("stack", stack)
            UnitType.COMMAND -> section.set("command", command)
            UnitType.TITLE -> section.set("title", title)
        }
    }

    /** 사람이 읽을 한 줄. `/업적 미지급` 과 편집 화면이 쓴다. */
    fun describe(): String = when (type) {
        UnitType.MONEY -> "돈 " + String.format("%,.0f", money)
        UnitType.ITEM -> (stack?.type?.name ?: "아이템") + " x" + (stack?.amount ?: 0)
        UnitType.COMMAND -> "명령어 " + command.take(40)
        UnitType.TITLE -> "칭호 " + title
    }

    companion object {
        fun load(section: ConfigurationSection): PayoutUnit? {
            val type = UnitType.of(section.getString("type")) ?: return null
            return PayoutUnit(
                type = type,
                money = section.getDouble("money", 0.0),
                stack = section.getItemStack("stack"),
                command = section.getString("command").orEmpty(),
                title = section.getString("title").orEmpty(),
                state = UnitState.of(section.getString("state")),
            )
        }
    }
}

/**
 * 효과 종류.
 *
 * `TITLE` 이 여기 있는 것이 중요하다. 타이틀포지를 claim 밖에서 부르면 **내구성 경계가 없어서**
 * 칭호는 나갔는데 기록 전에 죽으면 다시 준다.
 */
enum class UnitType(val display: String) {
    MONEY("돈"),
    ITEM("아이템"),
    COMMAND("명령어"),
    TITLE("칭호"),
    ;

    companion object {
        fun of(raw: String?): UnitType? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * 지급 상태 **셋**.
 *
 * 둘(`미지급`/`지급`)로는 **"크래시로 결과를 모르는 것"과 "일부러 미룬 것"이 섞인다.**
 * 전자는 관리자 판단이 필요하고 후자는 그냥 기다리면 된다. 섞어두면 `/업적 미지급` 목록이
 * 평소에도 오프라인 칭호로 가득 차서 진짜 이상한 것이 묻힌다.
 */
enum class UnitState(val display: String) {
    /** 지급을 시도했는지 **모른다.** `/업적 미지급` 에 오르고 **자동 재지급하지 않는다.** */
    PENDING("확인 필요"),

    /** 일부러 미뤘다(오프라인 칭호 등). **접속할 때 자동으로 실행한다.** */
    DEFERRED("접속 대기"),

    GRANTED("지급 완료"),
    ;

    companion object {
        fun of(raw: String?): UnitState =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: PENDING
    }
}
