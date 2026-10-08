package com.inmc.achievements.gui

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Category
import com.inmc.achievements.progress.ProgressEngine
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 유저용 업적 목록.
 *
 * ## 무엇을 보여주고 무엇을 감추는가
 *
 * - `listed = false` 는 아예 안 보인다. 관리자 전용이다.
 * - 히든은 **발견하기 전까지** 이름·설명·조건을 전부 감춘다. 칸은 `???` 로 남겨 "뭔가 있다"는
 *   것만 알린다 — 칸 자체를 지우면 목록이 들쭉날쭉해져 오히려 눈에 띈다.
 * - `countsTowardTotal = false` 인 히든은 **분모에서 빠진다.** 그래야 전부 모은 사람이
 *   100% 를 볼 수 있다. 다만 **점수는 준다** — 그 둘이 어긋나 보이므로 화면에 적어 둔다.
 *
 * 관리자 `/업적 관리 보기 <대상>`(2026-10-08)은 같은 화면을 **그 사람 기준**으로 연다([subject]) — 히든도 펼쳐 보인다.
 */
class BrowseMenu(
    ach: Achievements,
    private val viewer: Player,
    private var page: Int = 0,
    private var category: Category? = null,
    private val subject: UUID = viewer.uniqueId,
    private val subjectName: String = viewer.name,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>업적" + (if (subject == viewer.uniqueId) "" else " · $subjectName") + "</dark_gray>")) {

    /** 남의 것을 보는 관리자 화면 — 히든을 펼친다. */
    private val inspecting = subject != viewer.uniqueId

    override fun draw() {
        clear()
        val id = subject
        val items = visible()

        val shown = Paging.slice(items, page)
        shown.forEachIndexed { index, achievement -> set(index, iconFor(id, achievement)) }

        set(SLOT_CATEGORY, categoryIcon()) { event ->
            category = kr.inmc.core.gui.Editors.cycle(event, CATEGORIES, category)
            page = 0
            refresh()
        }
        set(SLOT_STATS, summaryIcon(id)) {
            StatsMenu(ach, viewer, id, subjectName).open(viewer)
        }
        if (!inspecting) {
            set(SLOT_GUIDE, Icon.of(Material.COMPASS, "<yellow>길라잡이</yellow>", "<gray>순서대로 따라가 보세요</gray>")) {
                GuideMenu(ach, viewer).open(viewer)
            }
        }

        val pages = Paging.pageCount(items.size)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun visible(): List<Achievement> = ach.registry.listed()
        .filter { category == null || it.category == category }
        .sortedWith(compareBy({ it.category.ordinal }, { it.display }))

    /**
     * 칸 하나.
     *
     * 진행도 막대를 직접 그린다 — 바닐라 발전과제 화면은 "달성한 조건 수/전체" 만 보여줄 수
     * 있어서 `나무 10,000개` 같은 것을 표현하지 못한다. 그게 그 화면을 목록으로 안 쓰는
     * 이유고, 그러니 여기서는 제대로 보여준다.
     */
    private fun iconFor(id: UUID, achievement: Achievement): org.bukkit.inventory.ItemStack {
        val discovered = !achievement.hidden || ach.counters.isDiscovered(id, achievement.uid)
        if (!discovered && !inspecting) {
            return Icon.of(
                Material.GRAY_DYE,
                "<dark_gray>???</dark_gray>",
                listOf("<dark_gray>아직 발견하지 못한 업적입니다.</dark_gray>"),
            )
        }

        val claimed = ach.claims.claims(id, achievement.uid).keys
        val steps = achievement.steps()
        val done = steps.count { it.id in claimed }
        val complete = done >= steps.size

        val progress = ach.engine.progressOf(id, if (inspecting) Bukkit.getPlayer(id) else viewer, achievement)
        val count = (progress as? ProgressEngine.Progress.Known)?.count ?: 0L
        val next = steps.firstOrNull { it.id !in claimed }
        val goal = next?.threshold ?: achievement.maxThreshold()

        val lore = buildList {
            add("<dark_gray>" + achievement.category.display + "</dark_gray>")
            // 발견 기록은 메모리에만 있다 — 오프라인인 사람은 알 수 없어 적지 않는다.
            if (!discovered && ach.counters.isLoaded(id)) add("<dark_purple>히든 — 그 사람은 아직 발견하지 못함</dark_purple>")
            if (achievement.description.isNotEmpty()) {
                add("")
                addAll(achievement.description.map { "<gray>$it</gray>" })
            }
            add("")
            if (progress is ProgressEngine.Progress.Unavailable) {
                add("<red>이 조건을 아는 플러그인이 없습니다.</red>")
            } else {
                add(bar(count, goal))
                add("<gray>" + format(count) + " / " + format(goal) + "</gray>")
            }
            if (steps.size > 1) add("<gray>단계 <white>$done</white>/<white>${steps.size}</white></gray>")
            val points = steps.filter { it.id in claimed }.sumOf { it.points }
            if (points > 0) add("<gray>얻은 점수: <yellow>$points</yellow></gray>")
            if (!achievement.countsTowardTotal) {
                add("<dark_gray>전체 달성률에는 포함되지 않습니다(점수는 지급).</dark_gray>")
            }
        }

        val material = if (complete) achievement.icon else Material.GRAY_DYE
        val name = (if (complete) "<green>✔ " else "<white>") + achievement.display +
            (if (complete) "</green>" else "</white>")
        return Icon.of(material, name, lore)
    }

    /** 스무 칸짜리 막대. 숫자만으로는 "얼마나 남았나" 가 한눈에 안 들어온다. */
    private fun bar(count: Long, goal: Long): String {
        if (goal <= 0L) return ""
        val filled = ((count.toDouble() / goal) * BAR_WIDTH).toInt().coerceIn(0, BAR_WIDTH)
        return "<green>" + "■".repeat(filled) + "</green><dark_gray>" +
            "■".repeat(BAR_WIDTH - filled) + "</dark_gray>"
    }

    private fun format(value: Long): String = String.format("%,d", value)

    private fun categoryIcon() = Icon.of(
        category?.icon ?: Material.BOOKSHELF,
        "<yellow>분류: " + (category?.display ?: "전체") + "</yellow>",
        kr.inmc.core.gui.Editors.optionList(CATEGORIES, category) { it?.display ?: "전체" },
    )

    private fun summaryIcon(id: UUID): org.bukkit.inventory.ItemStack {
        val total = ach.registry.totalCount()
        val done = ach.points.completedCount(id)
        val percent = if (total <= 0) 0.0 else done * 100.0 / total
        return Icon.of(
            Material.NETHER_STAR,
            if (inspecting) "<gold>$subjectName 의 업적</gold>" else "<gold>내 업적</gold>",
            listOf(
                "<gray>달성: <white>$done</white>/<white>$total</white> " +
                    "<dark_gray>(" + String.format("%.1f", percent) + "%)</dark_gray></gray>",
                "<gray>점수: <yellow>" + ach.points.pointsOf(id) + "</yellow></gray>",
                "<gray>랭크: " + ach.points.rankOf(id) + "</gray>",
                "",
                "<yellow>▶ 클릭: 자세히</yellow>",
            ),
        )
    }

    private companion object {
        const val SLOT_CATEGORY = 48
        const val SLOT_STATS = 49
        const val SLOT_GUIDE = 50
        const val BAR_WIDTH = 20

        /** null 은 "전체". 순환 목록의 첫 칸이다. */
        val CATEGORIES: List<Category?> = listOf(null) + Category.entries
    }
}
