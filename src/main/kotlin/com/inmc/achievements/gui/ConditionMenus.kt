package com.inmc.achievements.gui

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Condition
import com.inmc.achievements.achievement.StateKind
import kr.inmc.core.event.SignalCatalog
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.Statistic
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/** 조건 편집. 갈래를 고르고 갈래별 칸만 보여준다. */
class ConditionMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>조건</dark_gray>")) {

    private fun definition(): Achievement? = ach.registry.byUid(uid)

    private fun apply(condition: Condition?) {
        val current = definition() ?: return
        // State 는 참/거짓이라 누적 임계값이 뜻이 없다. 갈래를 바꾸면 단계를 접는다.
        val tiers = if (condition?.supportsTiers == false) emptyList() else current.tiers
        store(viewer, current.copy(condition = condition, tiers = tiers))
        refresh()
    }

    override fun draw() {
        clear()
        val item = definition() ?: run {
            ach.tell(viewer, "not-found"); AdminListMenu(ach, viewer).open(viewer); return
        }

        set(SLOT_STAT, Icon.of(Material.DIAMOND_PICKAXE, "<yellow>바닐라 통계</yellow>", listOf(
            "<gray>블록 파괴·걸음 수·몹 사냥 등</gray>",
            "<dark_gray>마인크래프트가 이미 세고 있어 소급 적용됩니다.</dark_gray>",
            "<dark_gray>접속 중에만 갱신됩니다.</dark_gray>",
            "", picked(item.condition is Condition.Stat),
        ))) { StatPickMenu(ach, viewer, uid).open(viewer) }

        set(SLOT_SIGNAL, Icon.of(Material.ENDER_EYE, "<yellow>InMC 사건</yellow>", listOf(
            "<gray>낚시·상자·몬스터 등 다른 플러그인의 사건</gray>",
            "", picked(item.condition is Condition.Signal),
        ))) { SignalPickMenu(ach, viewer, uid).open(viewer) }

        set(SLOT_STATE, Icon.of(Material.REDSTONE_TORCH, "<yellow>상태</yellow>", listOf(
            "<gray>권한 그룹·지역·선행 업적 등</gray>",
            "<dark_gray>참/거짓이라 단계를 가질 수 없습니다.</dark_gray>",
            "", picked(item.condition is Condition.State),
        ))) { StatePickMenu(ach, viewer, uid).open(viewer) }

        set(SLOT_CUSTOM, Icon.of(Material.COMMAND_BLOCK, "<yellow>커스텀</yellow>", buildList {
            add("<gray>외부 플러그인이 등록한 조건</gray>")
            val kinds = ach.custom.conditionKinds()
            if (kinds.isEmpty()) add("<red>등록된 것이 없습니다.</red>")
            else addAll(kinds.map { "<dark_gray>· $it</dark_gray>" })
            add(""); add(picked(item.condition is Condition.Custom))
        })) { CustomPickMenu(ach, viewer, uid).open(viewer) }

        set(SLOT_CURRENT, Icon.of(Material.PAPER, "<yellow>지금 조건</yellow>", describe(item)))

        set(SLOT_CLEAR, Icon.of(Material.BARRIER, "<red>조건 지우기</red>")) { apply(null) }
        set(SLOT_BACK, Icon.back()) { AchievementEditMenu(ach, viewer, uid).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun picked(value: Boolean): String =
        if (value) "<green>▶ 지금 이것</green>" else "<yellow>▶ 클릭: 고르기</yellow>"

    /** 값을 아는 플러그인이 없으면 **빨갛게.** 조용히 받아들이면 영영 미완료인 것을 아무도 모른다. */
    private fun describe(item: Achievement): List<String> = when (val condition = item.condition) {
        null -> listOf("<red>없습니다 — 이 업적은 완료될 수 없습니다.</red>")
        is Condition.Stat -> buildList {
            add("<gray>통계: <white>" + condition.statistic.name + "</white></gray>")
            val targets = condition.materials.map { it.name } + condition.entities.map { it.name }
            if (targets.isNotEmpty()) add("<gray>대상: <white>" + targets.joinToString(", ") + "</white></gray>")
            if (targets.size > 1) add("<dark_gray>대상들의 합으로 셉니다.</dark_gray>")
            if (!condition.isWellFormed()) add("<red>대상이 맞지 않습니다.</red>")
        }
        is Condition.Signal -> buildList {
            add("<gray>출처: <white>" + condition.source + "/" + condition.type + "</white></gray>")
            add("<gray>대상: <white>" + (condition.subject ?: "전체") + "</white></gray>")
            for ((key, value) in condition.match) add("<dark_gray>· $key = $value</dark_gray>")
            val known = condition.subject?.let {
                SignalCatalog.knowsSubject(condition.source, condition.type, it)
            }
            if (known == false) add("<red>이 값을 아는 플러그인이 없습니다.</red>")
        }
        is Condition.Custom -> buildList {
            add("<gray>종류: <white>" + condition.kind + "</white></gray>")
            if (condition.value.isNotBlank()) add("<gray>값: <white>" + condition.value + "</white></gray>")
            if (!ach.custom.knows(condition.kind)) add("<red>이 종류를 아는 플러그인이 없습니다.</red>")
        }
        is Condition.State -> buildList {
            add("<gray>" + condition.kind.display + ": <white>" + condition.value + "</white></gray>")
            if (condition.kind == StateKind.GROUP && ach.luckPerms.knows(condition.value) == false) {
                add("<red>그런 권한 그룹이 없습니다.</red>")
            }
            if (condition.kind == StateKind.ACHIEVEMENT && ach.registry.byUid(condition.value) == null) {
                add("<red>그런 업적이 없습니다.</red>")
            }
        }
    }

    private companion object {
        const val SLOT_STAT = 19
        const val SLOT_SIGNAL = 21
        const val SLOT_STATE = 23
        const val SLOT_CUSTOM = 25
        const val SLOT_CURRENT = 40
        const val SLOT_CLEAR = 44
        const val SLOT_BACK = 45
        const val SLOT_CLOSE = 53
    }
}

/** 바닐라 통계 고르기. **인자 수를 여기서 검사한다** — 스윕 안에서 터뜨리지 않기 위해. */
class StatPickMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
    private var page: Int = 0,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>통계 고르기</dark_gray>")) {

    override fun draw() {
        clear()
        val stats = Statistic.entries.sortedBy { it.name }
        Paging.slice(stats, page).forEachIndexed { index, statistic ->
            set(index, Icon.of(Material.PAPER, "<white>" + statistic.name + "</white>", listOf(
                "<gray>종류: " + typeName(statistic.type) + "</gray>",
            ))) { pick(statistic) }
        }
        val pages = Paging.pageCount(stats.size)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(Paging.SLOT_BACK, Icon.back()) { ConditionMenu(ach, viewer, uid).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun typeName(type: Statistic.Type): String = when (type) {
        Statistic.Type.UNTYPED -> "대상 없음"
        Statistic.Type.ITEM -> "아이템 지정"
        Statistic.Type.BLOCK -> "블록 지정"
        Statistic.Type.ENTITY -> "몹 지정"
    }

    private fun pick(statistic: Statistic) {
        when (statistic.type) {
            Statistic.Type.UNTYPED -> {
                apply(Condition.Stat(statistic))
                ConditionMenu(ach, viewer, uid).open(viewer)
            }
            Statistic.Type.ITEM, Statistic.Type.BLOCK -> {
                prompt(viewer, "<yellow>대상 아이템/블록 이름을 입력하세요. 여러 개는 쉼표로 — 합으로 셉니다. " +
                    "(예: DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE)</yellow>" + current(statistic)) { raw ->
                    val names = targets(raw)
                    val unknown = names.filter { Material.matchMaterial(it) == null }
                    when {
                        names.isEmpty() -> ach.tell(viewer, "admin-invalid-id", ach.ph().reason("대상을 입력하세요"))
                        unknown.isNotEmpty() -> ach.tell(viewer, "admin-invalid-id", ach.ph().reason("그런 재질이 없습니다: " + unknown.joinToString(", ")))
                        else -> apply(Condition.Stat.of(statistic, materials = names.mapNotNull(Material::matchMaterial)))
                    }
                    ConditionMenu(ach, viewer, uid).open(viewer)
                }
            }
            Statistic.Type.ENTITY -> {
                prompt(viewer, "<yellow>대상 몹 이름을 입력하세요. 여러 개는 쉼표로 — 합으로 셉니다. " +
                    "(예: ZOMBIE, HUSK, DROWNED)</yellow>" + current(statistic)) { raw ->
                    val names = targets(raw)
                    fun entity(name: String) = runCatching { EntityType.valueOf(name.uppercase()) }.getOrNull()
                    val unknown = names.filter { entity(it) == null }
                    when {
                        names.isEmpty() -> ach.tell(viewer, "admin-invalid-id", ach.ph().reason("대상을 입력하세요"))
                        unknown.isNotEmpty() -> ach.tell(viewer, "admin-invalid-id", ach.ph().reason("그런 몹이 없습니다: " + unknown.joinToString(", ")))
                        else -> apply(Condition.Stat.of(statistic, entities = names.mapNotNull(::entity)))
                    }
                    ConditionMenu(ach, viewer, uid).open(viewer)
                }
            }
        }
    }

    /** 쉼표·공백으로 나눈 이름들. */
    private fun targets(raw: String): List<String> = raw.split(',', ' ').map(String::trim).filter(String::isNotEmpty)

    /** 같은 통계를 다시 고를 때 지금 대상을 보여준다 — 하나를 더하려면 전부 다시 쳐야 하므로. */
    private fun current(statistic: Statistic): String {
        val now = ach.registry.byUid(uid)?.condition as? Condition.Stat ?: return ""
        if (now.statistic != statistic) return ""
        val names = now.materials.map { it.name } + now.entities.map { it.name }
        return if (names.isEmpty()) "" else " <gray>(지금: <white>" + names.joinToString(", ") + "</white>)</gray>"
    }

    private fun apply(condition: Condition.Stat) {
        if (!condition.isWellFormed()) {
            ach.tell(viewer, "admin-invalid-id", ach.ph().reason("이 통계에 맞지 않는 대상입니다"))
            return
        }
        val current = ach.registry.byUid(uid) ?: return
        store(viewer, current.copy(condition = condition))
    }
}

/**
 * InMC 사건 고르기. **`SignalCatalog` 에서 진짜 목록을 그린다.**
 *
 * 이게 이 화면의 존재 이유다. 손으로 타이핑하게 두면 오타 하나로 그 업적이 오류 없이
 * 영영 완료되지 않고, 만든 사람은 이유를 모른다.
 */
class SignalPickMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>InMC 사건</dark_gray>")) {

    override fun draw() {
        clear()
        // 열 때마다 읽는다. 등록 시점에 목록을 떠서 넘기면 새로 만든 물고기가 안 나온다.
        val entries = SignalCatalog.all()
        if (entries.isEmpty()) {
            set(SLOT_EMPTY, Icon.of(Material.BARRIER, "<red>등록된 사건이 없습니다.</red>", listOf(
                "<gray>낚시·랜덤박스 같은 플러그인이 켜져 있어야</gray>",
                "<gray>여기에 목록이 나옵니다.</gray>",
            )))
        }
        entries.take(Paging.PER_PAGE).forEachIndexed { index, entry ->
            set(index, Icon.of(Material.ENDER_EYE, "<white>" + entry.key + "</white>", buildList {
                if (entry.description.isNotBlank()) add("<gray>" + entry.description + "</gray>")
                add("<gray>" + entry.subjectLabel + " " + entry.subjects().size + "종</gray>")
                add(""); add("<yellow>▶ 클릭: 대상 고르기</yellow>")
                add("<dark_gray>▶ 우클릭: 전체(종류 무관)</dark_gray>")
            })) { event ->
                if (event.isRightClick) {
                    apply(Condition.Signal(entry.source, entry.type))
                    ConditionMenu(ach, viewer, uid).open(viewer)
                } else {
                    SubjectPickMenu(ach, viewer, uid, entry.source, entry.type).open(viewer)
                }
            }
        }
        set(Paging.SLOT_BACK, Icon.back()) { ConditionMenu(ach, viewer, uid).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun apply(condition: Condition) {
        val current = ach.registry.byUid(uid) ?: return
        store(viewer, current.copy(condition = condition))
    }

    private companion object {
        const val SLOT_EMPTY = 22
    }
}

/** 그 사건의 실제 대상 목록. 물고기 이름·상자 이름이 그대로 나온다. */
class SubjectPickMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
    private val source: String,
    private val type: String,
    private var page: Int = 0,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>대상 고르기</dark_gray>")) {

    override fun draw() {
        clear()
        val entry = SignalCatalog.get(source, type)
        val subjects = entry?.subjects().orEmpty()
        Paging.slice(subjects, page).forEachIndexed { index, (id, label) ->
            set(index, Icon.of(Material.PAPER, "<white>$label</white>", "<dark_gray>$id</dark_gray>")) {
                apply(Condition.Signal(source, type, id))
                ConditionMenu(ach, viewer, uid).open(viewer)
            }
        }
        val pages = Paging.pageCount(subjects.size)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(Paging.SLOT_BACK, Icon.back()) { SignalPickMenu(ach, viewer, uid).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun apply(condition: Condition) {
        val current = ach.registry.byUid(uid) ?: return
        store(viewer, current.copy(condition = condition))
    }
}

/** 상태 조건. `GROUP` 은 LuckPerms 가 있으면 실제 그룹 목록을 보여준다. */
class StatePickMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
) : Menu(ach, 27, Text.renderFlat("<dark_gray>상태 조건</dark_gray>")) {

    override fun draw() {
        clear()
        StateKind.entries.forEachIndexed { index, kind ->
            set(index, Icon.of(iconFor(kind), "<yellow>" + kind.display + "</yellow>", hint(kind))) {
                when (kind) {
                    StateKind.ACHIEVEMENT -> PickAchievementMenu(ach, viewer, uid, "선행 업적") { picked ->
                        apply(Condition.State(kind, picked))
                        ConditionMenu(ach, viewer, uid).open(viewer)
                    }.open(viewer)
                    StateKind.GROUP -> {
                        val groups = ach.luckPerms.groups()
                        if (groups.isNullOrEmpty()) askText(kind, "권한 그룹 이름을 입력하세요.")
                        else GroupPickMenu(ach, viewer, uid, groups).open(viewer)
                    }
                    StateKind.PERMISSION -> askText(kind, "권한 노드를 입력하세요.")
                    StateKind.REGION -> askText(kind, "지역 이름을 입력하세요.")
                    StateKind.RANK_TOP -> askText(kind, "보드아이디:등수 를 입력하세요. (예: achievement-points:10)")
                    StateKind.ITEM_HELD -> askText(kind, "아이템 참조를 입력하세요. (예: inmc:훈장)")
                }
            }
        }
        set(SLOT_BACK, Icon.back()) { ConditionMenu(ach, viewer, uid).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun iconFor(kind: StateKind): Material = when (kind) {
        StateKind.ACHIEVEMENT -> Material.BOOK
        StateKind.GROUP -> Material.GOLDEN_HELMET
        StateKind.PERMISSION -> Material.NAME_TAG
        StateKind.REGION -> Material.MAP
        StateKind.RANK_TOP -> Material.GOLD_INGOT
        StateKind.ITEM_HELD -> Material.CHEST
    }

    private fun hint(kind: StateKind): List<String> = when (kind) {
        StateKind.GROUP -> listOf(
            "<gray>LuckPerms 등급 등</gray>",
            if (ach.luckPerms.isEnabled) "<dark_gray>LuckPerms 목록에서 고릅니다.</dark_gray>"
            else "<dark_gray>LuckPerms 가 없어 group.<이름> 권한으로 판정합니다.</dark_gray>",
        )
        StateKind.RANK_TOP -> listOf(
            "<gray>이 플러그인의 순위표만 봅니다.</gray>",
            "<dark_gray>다른 플러그인 순위는 커스텀 조건으로.</dark_gray>",
        )
        else -> emptyList()
    }

    private fun askText(kind: StateKind, question: String) {
        prompt(viewer, "<yellow>$question</yellow>") { value ->
            apply(Condition.State(kind, value.trim()))
            ConditionMenu(ach, viewer, uid).open(viewer)
        }
    }

    private fun apply(condition: Condition) {
        val current = ach.registry.byUid(uid) ?: return
        store(viewer, current.copy(condition = condition, tiers = emptyList()))
    }

    private companion object {
        const val SLOT_BACK = 18
        const val SLOT_CLOSE = 26
    }
}

/** LuckPerms 그룹 목록. 손으로 치다 오타 내는 것을 막는 유일한 장치다. */
class GroupPickMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
    private val groups: List<String>,
    private var page: Int = 0,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>권한 그룹</dark_gray>")) {

    override fun draw() {
        clear()
        Paging.slice(groups, page).forEachIndexed { index, group ->
            set(index, Icon.of(Material.GOLDEN_HELMET, "<white>$group</white>")) {
                val current = ach.registry.byUid(uid) ?: return@set
                store(
                    viewer,
                    current.copy(
                        condition = Condition.State(StateKind.GROUP, group),
                        tiers = emptyList(),
                    ),
                )
                ConditionMenu(ach, viewer, uid).open(viewer)
            }
        }
        val pages = Paging.pageCount(groups.size)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(Paging.SLOT_BACK, Icon.back()) { StatePickMenu(ach, viewer, uid).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }
}

/** 외부가 등록한 커스텀 조건 종류. */
class CustomPickMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
) : Menu(ach, 27, Text.renderFlat("<dark_gray>커스텀 조건</dark_gray>")) {

    override fun draw() {
        clear()
        val kinds = ach.custom.conditionKinds()
        if (kinds.isEmpty()) {
            set(SLOT_EMPTY, Icon.of(Material.BARRIER, "<red>등록된 커스텀 조건이 없습니다.</red>", listOf(
                "<gray>외부 플러그인이 AchievementsApi 로</gray>",
                "<gray>평가기를 등록해야 나옵니다.</gray>",
            )))
        }
        kinds.take(18).forEachIndexed { index, kind ->
            set(index, Icon.of(Material.COMMAND_BLOCK, "<white>$kind</white>")) {
                prompt(viewer, "<yellow>값을 입력하세요. (없으면 '-')</yellow>") { raw ->
                    val value = raw.trim().takeIf { it != "-" }.orEmpty()
                    val current = ach.registry.byUid(uid) ?: return@prompt
                    store(viewer, current.copy(condition = Condition.Custom(kind, value)))
                    ConditionMenu(ach, viewer, uid).open(viewer)
                }
            }
        }
        set(SLOT_BACK, Icon.back()) { ConditionMenu(ach, viewer, uid).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private companion object {
        const val SLOT_EMPTY = 13
        const val SLOT_BACK = 18
        const val SLOT_CLOSE = 26
    }
}

/** 업적 고르기. 선행 조건·발견 조건·다음 업적이 같이 쓴다. 값은 **uid** 다. */
class PickAchievementMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
    private val what: String,
    private var page: Int = 0,
    private val onPick: (String) -> Unit,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>$what</dark_gray>")) {

    override fun draw() {
        clear()
        // 자기 자신은 뺀다. 고르면 그 자리에서 순환이 된다.
        val items = ach.registry.all().filter { it.uid != uid }
        Paging.slice(items, page).forEachIndexed { index, achievement ->
            set(index, Icon.of(achievement.icon, "<white>" + achievement.display + "</white>", listOf(
                "<dark_gray>" + achievement.id + "</dark_gray>",
            ))) { onPick(achievement.uid) }
        }
        val pages = Paging.pageCount(items.size)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(Paging.SLOT_BACK, Icon.back()) { AchievementEditMenu(ach, viewer, uid).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }
}
