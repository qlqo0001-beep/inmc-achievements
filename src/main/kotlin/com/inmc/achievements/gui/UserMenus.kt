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
 * 길라잡이. 첫 벌목 → 첫 제작 → 첫 전투 → … 를 **순서대로** 보여준다.
 *
 * 데이터 모양은 다른 업적과 **똑같다.** 다른 것은 `next` 로 이어지는 사슬을 따라 늘어놓는다는
 * 것 하나뿐이라, 별도 타입을 만들면 목록·편집·저장이 두 벌이 된다.
 *
 * `next` 는 **표시 전용**이다 — 해금하지 않는다. 진짜 선행 조건은 `Condition.State` 이고
 * 자동 해금은 `Discovery` 가 한다. 셋을 구분하지 않으면 구현자마다 다르게 읽는다.
 */
class GuideMenu(
    ach: Achievements,
    private val viewer: Player,
) : Menu(ach, 27, Text.renderFlat("<dark_gray>길라잡이</dark_gray>")) {

    override fun draw() {
        clear()
        val id = viewer.uniqueId
        val chain = orderedChain()

        if (chain.isEmpty()) {
            set(SLOT_EMPTY, Icon.of(Material.BARRIER, "<gray>길라잡이 업적이 없습니다.</gray>"))
        }

        // 한 줄에 최대 9칸. 사슬이 길면 앞에서 자르지 않고 진행 중인 곳 주변을 보여준다.
        val focus = chain.indexOfFirst { ach.claims.claims(id, it.uid).isEmpty() }
            .takeIf { it >= 0 } ?: (chain.size - 1)
        val start = (focus - ROW / 2).coerceIn(0, maxOf(0, chain.size - ROW))
        val window = chain.drop(start).take(ROW)

        window.forEachIndexed { index, achievement ->
            set(ROW_START + index, icon(id, achievement, start + index + 1, chain.size))
        }

        set(SLOT_BACK, Icon.back()) { BrowseMenu(ach, viewer).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    /**
     * `next` 를 따라 늘어놓는다. 아무도 안 가리키는 것이 시작점이다.
     *
     * 사슬 밖의 길라잡이 업적도 **뒤에 붙인다** — 관리자가 `next` 를 안 이은 것이 목록에서
     * 통째로 사라지면 왜 안 보이는지 알 길이 없다.
     */
    private fun orderedChain(): List<Achievement> {
        val guide = ach.registry.inCategory(Category.GUIDE)
        if (guide.isEmpty()) return emptyList()

        val byUid = guide.associateBy { it.uid }
        val pointed = guide.mapNotNull { it.next.takeIf { n -> n.isNotBlank() } }.toSet()
        val heads = guide.filter { it.uid !in pointed }

        val ordered = LinkedHashSet<Achievement>()
        for (head in heads) {
            var current: Achievement? = head
            while (current != null && ordered.add(current)) {
                current = byUid[current.next]
            }
        }
        // 순환에 갇힌 것도 빠뜨리지 않는다.
        for (achievement in guide) ordered.add(achievement)
        return ordered.toList()
    }

    private fun icon(
        id: UUID,
        achievement: Achievement,
        step: Int,
        total: Int,
    ): org.bukkit.inventory.ItemStack {
        val done = ach.claims.claims(id, achievement.uid).isNotEmpty()
        val discovered = !achievement.hidden || ach.counters.isDiscovered(id, achievement.uid)
        if (!discovered) {
            return Icon.of(Material.GRAY_DYE, "<dark_gray>???</dark_gray>", "<dark_gray>$step / $total</dark_gray>")
        }
        val progress = ach.engine.progressOf(id, viewer, achievement)
        val count = (progress as? ProgressEngine.Progress.Known)?.count ?: 0L
        return Icon.of(
            if (done) achievement.icon else Material.GRAY_DYE,
            (if (done) "<green>✔ " else "<white>") + achievement.display + (if (done) "</green>" else "</white>"),
            buildList {
                add("<dark_gray>$step / $total</dark_gray>")
                if (achievement.description.isNotEmpty()) {
                    add("")
                    addAll(achievement.description.map { "<gray>$it</gray>" })
                }
                if (!done) {
                    add("")
                    add("<gray>진행: <white>$count</white> / ${achievement.maxThreshold()}</gray>")
                }
            },
        )
    }

    private companion object {
        const val ROW = 9
        const val ROW_START = 9

        // 27칸 화면이다. Paging 의 45/53 을 쓰면 Menu.set 이 조용히 무시해 버튼이 안 그려진다.
        const val SLOT_EMPTY = 13
        const val SLOT_BACK = 18
        const val SLOT_CLOSE = 26
    }
}

/**
 * 통계. 분류별 달성률·점수·랭크.
 *
 * 오프라인도 볼 수 있다 — 기록은 전부 메모리에 있고 이름은 core 의 `Profile` 이 안다.
 * 다만 **통계 기반 업적의 진행도는 접속 중에만 갱신된다**(`OfflinePlayer` 에 `getStatistic`
 * 이 없다). 그래서 마지막으로 읽은 시각을 같이 보여준다.
 */
class StatsMenu(
    ach: Achievements,
    private val viewer: Player,
    private val target: UUID,
    private val targetName: String,
) : Menu(ach, 27, Text.renderFlat("<dark_gray>업적 통계 — $targetName</dark_gray>")) {

    override fun draw() {
        clear()

        set(SLOT_SUMMARY, summary())

        var slot = ROW_START
        for (category in Category.entries) {
            val all = ach.registry.inCategory(category).filter { it.countsTowardTotal }
            if (all.isEmpty()) continue
            val done = all.count { ach.points.isComplete(target, it) }
            val percent = done * 100.0 / all.size
            set(
                slot++,
                Icon.of(
                    category.icon,
                    "<yellow>" + category.display + "</yellow>",
                    listOf(
                        "<gray><white>$done</white>/<white>${all.size}</white> " +
                            "<dark_gray>(" + String.format("%.1f", percent) + "%)</dark_gray></gray>",
                    ),
                ),
            )
            if (slot >= ROW_START + 9) break
        }

        set(SLOT_BACK, Icon.back()) { BrowseMenu(ach, viewer).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun summary(): org.bukkit.inventory.ItemStack {
        val total = ach.registry.totalCount()
        val done = ach.points.completedCount(target)
        val percent = if (total <= 0) 0.0 else done * 100.0 / total
        val online = Bukkit.getPlayer(target) != null
        return Icon.of(
            Material.NETHER_STAR,
            "<gold>$targetName</gold>",
            buildList {
                add("<gray>달성: <white>$done</white>/<white>$total</white> " +
                    "<dark_gray>(" + String.format("%.1f", percent) + "%)</dark_gray></gray>")
                add("<gray>점수: <yellow>" + ach.points.pointsOf(target) + "</yellow></gray>")
                add("<gray>랭크: " + ach.points.rankOf(target) + "</gray>")
                if (!online) {
                    add("")
                    add("<dark_gray>접속 중이 아니라 통계 기반 업적의</dark_gray>")
                    add("<dark_gray>진행도는 마지막 접속 때의 값입니다.</dark_gray>")
                }
            },
        )
    }

    private companion object {
        const val SLOT_SUMMARY = 4
        const val ROW_START = 9
        const val SLOT_BACK = 18
        const val SLOT_CLOSE = 26
    }
}
