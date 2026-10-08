package com.inmc.achievements.achievement

import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.EntityType

/**
 * 업적이 무엇을 요구하는지. **세 갈래이고 하나로 합치지 않는다.**
 *
 * 합치고 싶어지겠지만 셋은 **진행도의 정체가 서로 다르다** — 그래서 지문이 바뀌었을 때의
 * 처리도, 재평가 계기도, 저장 비용도 다르다. 한 갈래로 뭉치면 그 차이가 분기문으로 흩어지고,
 * 통계 조건에서 "진행도를 0으로" 같은 **아무 일도 안 하면서 한 것처럼 보이는** 코드가 생긴다.
 *
 * | 갈래 | 진행도의 정체 | 재평가 계기 | 저장 |
 * |---|---|---|---|
 * | [Stat] | 바닐라 카운터를 읽은 **캐시** | 5초 스윕 · 접속 1틱 뒤 | 캐시만 |
 * | [Signal] | **진행도 자체** | 신호 도착 즉시 | 진행도 |
 * | [Custom] | **진행도 자체** | 외부 플러그인이 밀어줄 때 | 진행도 |
 * | [State] | 없음. **술어**다 | 5초 스윕 · 해당 이벤트 | 없음 |
 *
 * **진행의 원천은 갈래마다 정확히 하나다.** 통계 업적을 API 로 올려놓아도 5초 뒤 스윕이
 * 덮어써서 조용히 아무 일도 안 일어난다 — 그래서 [Custom] 이 아닌 조건에 대한
 * `AchievementsApi.progress` 는 거부한다.
 */
sealed interface Condition {

    /** 이 조건이 단계(누적 임계값)를 가질 수 있는지. [State] 만 못 가진다. */
    val supportsTiers: Boolean get() = true

    /**
     * 이 조건을 나타내는 정규 문자열. 진행도 옆에 같이 저장해 **조건이 바뀐 것을 알아챈다.**
     *
     * **정규화가 계약이다.** 뜻이 같은 두 조건은 반드시 같은 지문을 내야 한다 — 그러지 않으면
     * 관리자가 GUI 에서 값을 다시 고른 것만으로 전원의 진행도가 0이 된다.
     * 그래서 [Signal.match] 는 **키로 정렬**하고, 값 안의 [SEP] 는 이스케이프한다.
     */
    val signature: String

    /** `kind` 를 포함해 통째로 쓴다. 부르는 쪽이 섹션을 만들어 넘긴다. */
    fun save(section: ConfigurationSection)

    /**
     * 바닐라가 이미 세고 있는 것.
     *
     * **리스너가 0개이고 저장이 0바이트이며 소급 적용된다** — 업적을 오늘 만들어도 어제까지
     * 캔 것이 이미 세어져 있다. `PlayerMoveEvent` 로 걸음을 세면 접속자마다 초당 20번 도는데
     * `getStatistic(WALK_ONE_CM)` 은 공짜다.
     *
     * 경고: `OfflinePlayer` 에는 `getStatistic` 이 없다. 이 갈래는 접속 중에만 진행한다.
     *
     * 대상은 **여러 개**일 수 있고 진행도는 그 합이다(2026-10-07 사용자 요청) — 다이아몬드 광석과 심층암 다이아몬드 광석,
     * 좀비와 허스크처럼 바닐라가 따로 세는 것을 한 업적으로. 대상은 [Stat.of] 로 만들어 정렬·중복 제거한다.
     */
    data class Stat(
        val statistic: Statistic,
        val materials: List<Material> = emptyList(),
        val entities: List<EntityType> = emptyList(),
    ) : Condition {

        /**
         * 대상이 하나면 예전과 **같은 지문**(`stat|MINE_BLOCK|OAK_LOG`)이다 — 대상을 목록으로 바꾼 것만으로 진행도·초기화 기준점이
         * 버려지면 안 된다. 여럿이면 이름순으로 `,` 로 잇는다(순서만 다른 두 조건이 같은 지문을 내게 — 규칙 7).
         */
        override val signature: String
            get() {
                val targets = (materials.map { it.name } + entities.map { it.name }).distinct().sorted()
                return (listOf("stat", statistic.name) + listOfNotNull(targets.takeIf { it.isNotEmpty() }?.joinToString(",")))
                    .joinToString(SEP.toString())
            }

        /**
         * 인자 수가 맞는지. **정의를 저장할 때 검사하고 스윕 안에서는 절대 검사하지 않는다.**
         *
         * `Statistic.MINE_BLOCK` 을 1인자 `getStatistic` 에 넘기면 `IllegalArgumentException`
         * 이 난다. 그걸 5초마다 도는 스윕 안에서 맞으면 한 사람이 터져 나머지가 건너뛰어진다.
         */
        fun isWellFormed(): Boolean = when (statistic.type) {
            Statistic.Type.UNTYPED -> materials.isEmpty() && entities.isEmpty()
            Statistic.Type.ITEM, Statistic.Type.BLOCK -> materials.isNotEmpty() && entities.isEmpty()
            Statistic.Type.ENTITY -> entities.isNotEmpty() && materials.isEmpty()
        }

        /** 하나면 예전처럼 `material:`/`entity:` 한 줄, 여럿이면 `materials:`/`entities:` 목록. */
        override fun save(section: ConfigurationSection) {
            section.set("kind", "STATISTIC")
            section.set("statistic", statistic.name)
            when (materials.size) {
                0 -> {}
                1 -> section.set("material", materials.single().name)
                else -> section.set("materials", materials.map { it.name })
            }
            when (entities.size) {
                0 -> {}
                1 -> section.set("entity", entities.single().name)
                else -> section.set("entities", entities.map { it.name })
            }
        }

        companion object {
            fun of(statistic: Statistic, materials: Collection<Material> = emptyList(), entities: Collection<EntityType> = emptyList()) =
                Stat(statistic, materials.distinct().sortedBy { it.name }, entities.distinct().sortedBy { it.name })
        }
    }

    /**
     * 다른 InMC 플러그인이 쏜 신호. **우리가 직접 센다.**
     *
     * [subject] 는 주축이고 [match] 는 덤이다. 둘 다 비면 그 `source`/`type` 의 **모든** 신호를
     * 센다 — "물고기를 100마리 잡는다" 처럼 종류를 안 가리는 업적이 그 모양이다.
     */
    data class Signal(
        val source: String,
        val type: String,
        val subject: String? = null,
        val match: Map<String, String> = emptyMap(),
    ) : Condition {

        override val signature: String
            get() = buildString {
                append("signal").append(SEP).append(source).append(SEP).append(type)
                append(SEP).append(escape(subject.orEmpty()))
                // 키로 정렬한다. 안 하면 뜻이 같은 조건이 다른 지문을 내고, 관리자가 GUI 에서
                // 값을 다시 고른 것만으로 전원의 진행도가 0이 된다.
                for (key in match.keys.sorted()) {
                    append(SEP).append(escape(key)).append('=').append(escape(match.getValue(key)))
                }
            }

        override fun save(section: ConfigurationSection) {
            section.set("kind", "SIGNAL")
            section.set("source", source)
            section.set("type", type)
            subject?.let { section.set("subject", it) }
            if (match.isNotEmpty()) {
                val where = section.createSection("match")
                for ((key, value) in match) where.set(key, value)
            }
        }
    }

    /**
     * 외부 플러그인이 직접 밀어주는 진행도.
     *
     * `AchievementsApi.registerCondition(owner, kind, …)` 로 등록된 [kind] 를 가리킨다.
     * **등록된 평가기가 없으면 0이 아니라 "모름" 이다** — 0으로 다루면 그 업적이 오류 없이
     * 영영 완료되지 않고, 정의를 버리면 저장할 때 통째로 사라진다. 부르는 쪽이
     * `UNAVAILABLE` 로 다뤄 완료도 초기화도 일으키지 않는다.
     */
    data class Custom(val kind: String, val value: String = "") : Condition {

        override val signature: String
            get() = "custom" + SEP + escape(kind) + SEP + escape(value)

        override fun save(section: ConfigurationSection) {
            section.set("kind", "CUSTOM")
            section.set("custom", kind)
            if (value.isNotBlank()) section.set("value", value)
        }
    }

    /**
     * 세는 것이 아니라 **지금 참인지 묻는** 조건.
     *
     * 진행도가 없으므로 저장할 것도 없다. 0 아니면 1 이다.
     * 그래서 **단계를 붙일 수 없다** — `GROUP(vip)` 에 100/500/1000 은 뜻이 없다.
     */
    data class State(val kind: StateKind, val value: String) : Condition {

        override val supportsTiers: Boolean get() = false

        override val signature: String get() = "state" + SEP + kind.name + SEP + escape(value)

        override fun save(section: ConfigurationSection) {
            section.set("kind", "STATE")
            section.set("state", kind.name)
            section.set("value", value)
        }
    }

    companion object {

        /**
         * 지문 구분자.
         *
         * 값 안에 이게 들어가면 **서로 다른 조건이 같은 지문을 낼 수 있다.** 그래서
         * [escape] 를 거친다 — 낮은 확률이지만 결과가 "진행도가 안 바뀐다"라 눈에 안 띈다.
         */
        const val SEP = '|'

        fun escape(value: String): String =
            value.replace("\\", "\\\\").replace(SEP.toString(), "\\" + SEP)

        /**
         * 읽는다. 모르는 값이면 null 이고 **부르는 쪽이 그 업적을 버리지 않는다** —
         * 정의를 통째로 잃는 것보다 "조건을 모르는 업적" 으로 남는 편이 낫다.
         */
        fun load(section: ConfigurationSection?): Condition? {
            if (section == null) return null
            return when (section.getString("kind")?.uppercase()) {
                "STATISTIC" -> loadStat(section)
                "SIGNAL" -> loadSignal(section)
                "CUSTOM" -> loadCustom(section)
                "STATE" -> loadState(section)
                else -> null
            }
        }

        private fun loadCustom(section: ConfigurationSection): Custom? {
            val kind = section.getString("custom")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: return null
            return Custom(kind, section.getString("value")?.trim().orEmpty())
        }

        /** `material:`·`materials:` 둘 다, 한 줄이든 목록이든 받는다. 모르는 이름은 빠진다(예전 한 줄일 때와 같다). */
        private fun loadStat(section: ConfigurationSection): Stat? {
            val statistic = enumOrNull<Statistic>(section.getString("statistic")) ?: return null
            fun names(vararg keys: String) = keys.flatMap { key ->
                if (section.isList(key)) section.getStringList(key) else listOfNotNull(section.getString(key))
            }
            return Stat.of(
                statistic = statistic,
                materials = names("material", "materials").mapNotNull { enumOrNull<Material>(it) },
                entities = names("entity", "entities").mapNotNull { enumOrNull<EntityType>(it) },
            )
        }

        private fun loadSignal(section: ConfigurationSection): Signal? {
            val source = section.getString("source")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: return null
            val type = section.getString("type")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: return null
            val where = section.getConfigurationSection("match")
            return Signal(
                source = source,
                type = type,
                subject = section.getString("subject")?.trim()?.takeIf { it.isNotBlank() },
                match = where?.getKeys(false)?.associateWith { where.getString(it).orEmpty() }.orEmpty(),
            )
        }

        private fun loadState(section: ConfigurationSection): State? {
            val kind = enumOrNull<StateKind>(section.getString("state")) ?: return null
            val value = section.getString("value")?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return State(kind, value)
        }

        private inline fun <reified E : Enum<E>> enumOrNull(raw: String?): E? {
            val name = raw?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { enumValueOf<E>(name) }.getOrNull()
        }
    }
}

/**
 * [Condition.State] 가 묻는 것들.
 *
 * `PERMISSION` 과 `GROUP` 을 나눈 것은 의도다. LuckPerms 가 `group.vip` 노드를 자동으로 주긴
 * 하지만 **그건 LuckPerms 고유 동작**이고 PEX·GroupManager 에서는 아무도 안 가진 문자열이다.
 * 더 중요하게는 GUI 가 다르다 — `GROUP` 은 **실제 그룹 목록**을 보여줄 수 있고, 그래야
 * 관리자가 이름을 손으로 치다 오타를 내고 그 업적이 영영 조용히 미완료되는 일이 없다.
 */
enum class StateKind(val display: String) {
    /** 선행 업적. 값은 업적의 **uid** 다 — 이름을 바꿔도 안 끊긴다. */
    ACHIEVEMENT("선행 업적"),

    /** 권한 그룹(LuckPerms 등급). */
    GROUP("권한 그룹"),

    /** 권한 노드를 그대로. */
    PERMISSION("권한"),

    /** 우리 GUI 로 정의한 지역 안에 있는가. */
    REGION("지역 방문"),

    /** core 랭킹의 상위 N 위 안에 드는가. 값은 `보드id:등수`. */
    RANK_TOP("랭킹 등수"),

    /** 이 아이템을 들고 있는가. 값은 `inmc:아이디` 같은 참조. */
    ITEM_HELD("아이템 소지"),
    ;

    companion object {
        fun of(raw: String?): StateKind? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}
