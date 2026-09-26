package com.inmc.achievements.achievement

import org.bukkit.configuration.ConfigurationSection

/**
 * 히든 업적이 언제 드러나는가.
 *
 * **발견은 "보이는 것"과 "완료"만 막고 세는 것은 막지 않는다.** 조건부로 세면 "했는데 안
 * 쳐준다"가 되고 분기도 늘어난다. 그래서 히든 업적을 나중에 발견하면 이미 한 만큼이 그대로
 * 반영되고, 그게 요구사항의 "발견과 동시에 달성되는 업적" 을 자연히 만들어 준다.
 *
 * [completeOnDiscover] 는 그것과 다른 스위치다 — **진행도와 무관하게** 발견 즉시 완료시킨다.
 * 단계가 있는 업적에는 쓸 수 없다(어느 단계를 줄지가 없다).
 */
data class Discovery(
    val kind: DiscoveryKind = DiscoveryKind.AUTO,
    /**
     * 무엇을. [kind] 마다 뜻이 다르다.
     * - `ACHIEVEMENT` — 다른 업적의 **uid**
     * - `SIGNAL` — `출처/종류` 또는 `출처/종류/대상`
     * - `REGION` — 지역 이름
     * - `ITEM_USE` — 아이템 참조(`inmc:아이디`)
     * - `NPC` — 이름표 또는 NPC 아이디
     * - `CUSTOM` — 외부가 등록한 발견 종류
     */
    val value: String = "",
    /** 발견되는 순간 완료시킨다. **비단계 업적 전용.** */
    val completeOnDiscover: Boolean = false,
) {

    fun save(section: ConfigurationSection) {
        section.set("kind", kind.name)
        if (value.isNotBlank()) section.set("value", value)
        if (completeOnDiscover) section.set("complete-on-discover", true)
    }

    companion object {
        val AUTO = Discovery()

        fun load(section: ConfigurationSection?): Discovery {
            if (section == null) return AUTO
            return Discovery(
                kind = DiscoveryKind.of(section.getString("kind")),
                value = section.getString("value").orEmpty(),
                completeOnDiscover = section.getBoolean("complete-on-discover", false),
            )
        }
    }
}

/**
 * 발견 조건 일곱 갈래.
 *
 * `AUTO` 가 기본이고 나머지는 전부 **명시적인 계기**다. `AUTO` 는 "조건을 달성하면 그때
 * 드러난다" 라서 사실상 바닐라 발전과제의 `hidden` 과 같은 뜻이다.
 */
enum class DiscoveryKind(val display: String, val needsValue: Boolean) {
    /** 조건을 달성하면 그때 드러난다. */
    AUTO("자동(조건 달성 시)", false),

    /** 다른 업적을 달성하면. 값은 그 업적의 **uid**. */
    ACHIEVEMENT("다른 업적 달성", true),

    /** 특정 신호가 오면. */
    SIGNAL("특정 사건", true),

    /** 지역에 들어가면. */
    REGION("지역 방문", true),

    /** 아이템을 쓰면. */
    ITEM_USE("아이템 사용", true),

    /** NPC 와 상호작용하면. */
    NPC("NPC 대화", true),

    /** 외부 플러그인이 등록한 발견 조건. */
    CUSTOM("커스텀", true),
    ;

    companion object {
        fun of(raw: String?): DiscoveryKind =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
    }
}
