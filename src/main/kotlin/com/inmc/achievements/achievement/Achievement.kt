package com.inmc.achievements.achievement

import kr.inmc.core.store.DefinitionKey
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

/**
 * 업적 하나. **불변이다** — 한 칸을 고치면 새 객체가 되어 레지스트리에 갈아끼워진다.
 *
 * ## `uid` 와 `id` 를 나눈 이유
 *
 * `id` 는 관리자가 읽고 바꾸는 이름이고, [uid] 는 **플레이어 데이터가 가리키는 열쇠**다.
 * 이걸 합치면 두 가지가 동시에 깨진다.
 *
 * - `나무꾼` → `벌목왕` 으로 **이름만 바꿔도** 기록 전부가 고아가 된다. 옮기려면 500명의
 *   claim 파일과 `PlayerStore` 를 걸친 분산 마이그레이션이 되고, 중간에 죽으면 절반만 옮겨진다.
 * - `나무꾼` 을 지우고 같은 이름으로 다시 만들면 **옛 기록이 새 업적 것으로 되살아난다.**
 *
 * `uid` 하나로 둘 다 사라진다. 덤으로 uid 는 **ASCII 라서 발전과제 `NamespacedKey` 에 그대로
 * 들어간다** — `DefinitionKey` 는 한글을 허용하지만 `NamespacedKey` 는 `[a-z0-9._-]` 뿐이라,
 * 한글 업적 이름은 토스트를 **지급하는 순간** 터졌을 것이다.
 */
data class Achievement(
    /** 절대 안 바뀐다. 생성 때 한 번. ASCII 12자. */
    val uid: String,
    /** 관리자가 보는 이름. 자유롭게 바뀐다. */
    val id: String,
    val display: String,
    val description: List<String> = emptyList(),
    val icon: Material = Material.BOOK,
    val category: Category = Category.LIFE,
    val condition: Condition? = null,
    val tiers: List<Tier> = emptyList(),
    /** 단계가 없는 업적의 임계값. 1이면 "한 번 하면 끝". */
    val goal: Long = 1L,
    /** 단계 없는 업적의 보상·점수. */
    val single: Tier? = null,
    val hidden: Boolean = false,
    /** 히든 업적을 목록의 전체 개수에서 뺄지. 점수는 그래도 준다. */
    val countsTowardTotal: Boolean = true,
    val discovery: Discovery = Discovery.AUTO,
    val celebration: Celebration = Celebration.TOAST_ONLY,
    /** 유저 목록에 내보낼지. 끄면 관리자만 본다. */
    val listed: Boolean = true,
    /**
     * 다음 업적의 **uid**. **표시 전용이다 — 해금하지 않는다.**
     *
     * 진짜 선행 조건은 [Condition.State] 의 `ACHIEVEMENT` 이고, 자동 해금은
     * [Discovery] 의 `ACHIEVEMENT` 다. 셋을 구분하지 않으면 구현자마다 다르게 읽는다.
     */
    val next: String = "",
    /**
     * 이미 해 둔 것으로 달성될 때 연출할지.
     *
     * 통계 업적을 새로 만들면 조건을 이미 채운 사람들이 **한꺼번에** 달성한다. 전부 서버
     * 공지·폭죽을 터뜨리면 그 순간 채팅이 공지로 덮인다. 기본은 **보상만 주고 조용히.**
     */
    val retroactive: Retroactive = Retroactive.SILENT,
) {

    /** 단계 목록. 단계가 없으면 [single] 을 한 칸짜리 목록으로 본다. */
    fun steps(): List<Tier> =
        if (tiers.isNotEmpty()) tiers
        else listOf(single ?: Tier(id = "1", threshold = goal.coerceAtLeast(1L)))

    fun tier(id: String?): Tier? = id?.let { key -> steps().firstOrNull { it.id == key } }

    /** 최고 단계의 임계값. 감시 집합에서 뺄지 판단하는 데 쓴다. */
    fun maxThreshold(): Long = steps().maxOfOrNull { it.threshold } ?: 1L

    /** 이 진행도로 열리는 모든 단계. 임계값 순으로 나온다. */
    fun reached(count: Long): List<Tier> = steps().filter { count >= it.threshold }

    /**
     * 이 단계 달성을 **소급**(조용히)으로 다룰지.
     *
     * [previous] 는 이번 변화 **전**에 본 값이다. 임계값이 그 이하면 이번에 넘은 것이 아니라
     * 이미 해 둔 것이다 — 업적이 새로 생겼거나, 단계가 끼어들었거나, 이 사람을 처음 봤거나
     * (null). [live] 는 발견·관리자 지급처럼 그 자체가 사건인 경우다.
     */
    fun silentFor(threshold: Long, previous: Long?, live: Boolean): Boolean =
        !live && retroactive == Retroactive.SILENT && (previous == null || threshold <= previous)

    /**
     * 완료하는 순간이 곧 발견인가.
     *
     * 발견 방식이 `AUTO` 인 히든이 그렇다. "발견 전에는 완료되지 않는다" 를 이것까지 막으면
     * AUTO 히든은 **영원히 완료되지 않는다** — 발견하려면 완료해야 하는데 완료하려면
     * 발견해야 한다. GUI 에서 히든을 켜면 기본이 AUTO 라 전부 이 상태가 됐었다.
     */
    fun discoversOnCompletion(): Boolean = hidden && discovery.kind == DiscoveryKind.AUTO

    fun save(section: ConfigurationSection) {
        section.set("uid", uid)
        section.set("display", display)
        if (description.isNotEmpty()) section.set("description", description)
        section.set("icon", icon.name)
        section.set("category", category.name)
        condition?.save(section.createSection("condition"))
        if (tiers.isNotEmpty()) {
            val list = section.createSection("tiers")
            for (tier in tiers) tier.save(list.createSection(tier.id))
        } else {
            section.set("goal", goal)
            single?.save(section.createSection("reward"))
        }
        if (hidden) section.set("hidden", true)
        if (!countsTowardTotal) section.set("counts-toward-total", false)
        if (discovery != Discovery.AUTO) discovery.save(section.createSection("discovery"))
        celebration.save(section.createSection("celebration"))
        if (!listed) section.set("listed", false)
        if (next.isNotBlank()) section.set("next", next)
        if (retroactive != Retroactive.SILENT) section.set("retroactive", retroactive.name)
    }

    companion object {

        /**
         * 읽는다. **`uid` 가 없으면 null 을 준다** — 부르는 쪽이 만들어 **원자적으로 확정한 뒤**
         * 다시 읽는다. 여기서 즉석으로 만들어 돌려주면 그 uid 가 디스크에 닿기 전에 진행이
         * 쌓이고, 서버가 죽으면 다음 시작에 uid 가 새로 생겨 기록이 고아가 된다.
         */
        fun load(id: String, section: ConfigurationSection): Achievement? {
            if (!DefinitionKey.isValid(id)) return null
            val uid = section.getString("uid")?.trim()?.takeIf { isValidUid(it) } ?: return null

            val tiers = section.getConfigurationSection("tiers")?.let { list ->
                list.getKeys(false).mapNotNull { key ->
                    list.getConfigurationSection(key)?.let { Tier.load(key, it) }
                }
            }.orEmpty().sortedBy { it.threshold }

            return Achievement(
                uid = uid,
                id = id.lowercase(),
                display = section.getString("display").orEmpty().ifBlank { id },
                description = section.getStringList("description"),
                icon = section.getString("icon")?.let { Material.matchMaterial(it) } ?: Material.BOOK,
                category = Category.of(section.getString("category")),
                condition = Condition.load(section.getConfigurationSection("condition")),
                tiers = tiers,
                goal = section.getLong("goal", 1L).coerceAtLeast(1L),
                single = section.getConfigurationSection("reward")
                    ?.let { Tier.load("1", it) }
                    ?: Tier(id = "1", threshold = section.getLong("goal", 1L).coerceAtLeast(1L)),
                hidden = section.getBoolean("hidden", false),
                countsTowardTotal = section.getBoolean("counts-toward-total", true),
                discovery = Discovery.load(section.getConfigurationSection("discovery")),
                celebration = Celebration.load(section.getConfigurationSection("celebration"))
                    ?: Celebration.TOAST_ONLY,
                listed = section.getBoolean("listed", true),
                next = section.getString("next").orEmpty(),
                retroactive = Retroactive.of(section.getString("retroactive")),
            )
        }

        /** ASCII 소문자·숫자만. `NamespacedKey` 가 그대로 받는 모양이다. */
        private val UID = Regex("^[a-z0-9]{6,32}$")

        fun isValidUid(value: String?): Boolean = value != null && UID.matches(value)

        /** 새 uid. 길이 12의 16진수 — 충돌 확률이 무시할 만하고 사람이 눈으로 비교할 수 있다. */
        fun newUid(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

/**
 * 소급 달성의 연출 정책.
 *
 * "지금부터만 센다"(NONE)는 두지 않는다 — 통계 갈래에서 그걸 하려면 플레이어×업적마다
 * 기준값을 저장해야 하고, 그 순간 "통계 갈래는 아무것도 저장하지 않는다"가 무너진다.
 * 지금부터만 세고 싶으면 그건 신호(SIGNAL) 조건이다. (관리자 초기화의 기준점은 예외 — 초기화한
 * 사람·업적에만 생긴다. `AchievementCounters.rebase`)
 */
enum class Retroactive(val display: String) {
    /** 보상은 주고 연출·공지는 생략. 서버 최초 경쟁에도 들지 않는다. */
    SILENT("조용히"),

    /** 평소처럼 연출한다. */
    CELEBRATE("연출함"),
    ;

    companion object {
        fun of(raw: String?): Retroactive =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SILENT
    }
}

/**
 * 업적 분류. 요구사항의 일곱 갈래를 그대로 옮겼다.
 *
 * [GUIDE] 만 성격이 다르다 — 길라잡이는 **데이터 모양이 같고 화면만 다르다.**
 * 별도 타입을 만들면 목록·편집·저장이 두 벌이 되고, 실제로 다른 것은 "순서대로 보여준다"
 * 하나뿐이다.
 */
enum class Category(val display: String, val icon: Material) {
    GUIDE("길라잡이", Material.COMPASS),
    COMBAT("전투", Material.IRON_SWORD),
    EXPLORE("탐험", Material.MAP),
    LIFE("생활", Material.BREAD),
    GATHER("채집", Material.IRON_PICKAXE),
    FISHING("낚시", Material.FISHING_ROD),
    INMC("InMC", Material.NETHER_STAR),
    EVENT("이벤트", Material.FIREWORK_ROCKET),
    ;

    companion object {
        fun of(raw: String?): Category =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: LIFE
    }
}
