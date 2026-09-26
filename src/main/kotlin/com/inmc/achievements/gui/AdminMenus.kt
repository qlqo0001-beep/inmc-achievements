package com.inmc.achievements.gui

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Category
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.store.DefinitionKey
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 업적 목록. 만들고 고치고 지운다.
 *
 * **삭제는 참조를 먼저 본다.** A가 B를 선행 조건으로 걸고 있는데 B를 지우면, A는 "존재하지
 * 않는 업적을 요구하는" 영원한 미완료가 된다. 적재 때 그걸 `UNAVAILABLE` 로 살려두긴 하지만
 * 애초에 못 만드는 편이 낫다.
 */
class AdminListMenu(
    ach: Achievements,
    private val viewer: Player,
    private var page: Int = 0,
    private var category: Category? = null,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>업적 관리</dark_gray>")) {

    override fun draw() {
        clear()
        val items = ach.registry.all()
            .filter { category == null || it.category == category }
            .sortedWith(compareBy({ it.category.ordinal }, { it.id }))

        Paging.slice(items, page).forEachIndexed { index, achievement ->
            set(index, icon(achievement)) { event ->
                if (event.isRightClick) askDelete(achievement) else open(achievement)
            }
        }

        set(SLOT_CREATE, Icon.of(
            Material.WRITABLE_BOOK,
            "<green>＋ 새 업적</green>",
            listOf("<gray>이름을 입력하면 만들어집니다.</gray>", "<dark_gray>" + DefinitionKey.HINT + "</dark_gray>"),
        )) { askCreate() }

        set(SLOT_CATEGORY, Icon.of(
            category?.icon ?: Material.BOOKSHELF,
            "<yellow>분류: " + (category?.display ?: "전체") + "</yellow>",
            Editors.optionList(CATEGORIES, category) { it?.display ?: "전체" },
        )) { event ->
            category = Editors.cycle(event, CATEGORIES, category)
            page = 0
            refresh()
        }

        set(SLOT_REGIONS, Icon.of(Material.MAP, "<yellow>지역 관리</yellow>", "<gray>발견·조건에 쓰는 지역</gray>")) {
            RegionListMenu(ach, viewer).open(viewer)
        }

        val pages = Paging.pageCount(items.size)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun icon(achievement: Achievement) = Icon.of(
        achievement.icon,
        "<white>" + achievement.display + "</white>",
        buildList {
            add("<dark_gray>" + achievement.id + " · " + achievement.uid + "</dark_gray>")
            add("<gray>분류: " + achievement.category.display + "</gray>")
            add("<gray>조건: " + describe(achievement) + "</gray>")
            if (achievement.tiers.isNotEmpty()) add("<gray>단계: ${achievement.tiers.size}개</gray>")
            if (achievement.hidden) add("<light_purple>히든</light_purple>")
            if (!achievement.listed) add("<dark_gray>목록 비공개</dark_gray>")
            add("")
            add("<yellow>▶ 좌클릭: 설정</yellow>")
            add("<red>▶ 우클릭: 삭제</red>")
        },
    )

    private fun describe(achievement: Achievement): String = when (val condition = achievement.condition) {
        null -> "<red>없음</red>"
        is com.inmc.achievements.achievement.Condition.Stat -> "통계 " + condition.statistic.name
        is com.inmc.achievements.achievement.Condition.Signal -> "신호 " + condition.source + "/" + condition.type
        is com.inmc.achievements.achievement.Condition.Custom -> "커스텀 " + condition.kind
        is com.inmc.achievements.achievement.Condition.State -> "상태 " + condition.kind.display
    }

    private fun open(achievement: Achievement) {
        AchievementEditMenu(ach, viewer, achievement.uid).open(viewer)
    }

    private fun askCreate() {
        prompt(viewer, "<yellow>새 업적의 이름을 입력하세요.</yellow>") { name ->
            val id = name.trim().lowercase()
            when {
                !DefinitionKey.isValid(id) ->
                    ach.tell(viewer, "admin-invalid-id", ach.ph().reason(DefinitionKey.HINT))
                ach.registry.exists(id) || ach.registry.isRejected(id) ->
                    ach.tell(viewer, "admin-exists", ach.ph().achievement(id))
                else -> {
                    val created = Achievement(
                        uid = freshUid(),
                        id = id,
                        display = name.trim(),
                        category = category ?: Category.LIFE,
                    )
                    store(viewer, created)
                    ach.toasts.registerOne(created)
                    ach.tell(viewer, "admin-created", ach.ph().achievement(created.display))
                    AchievementEditMenu(ach, viewer, created.uid).open(viewer)
                    return@prompt
                }
            }
            open()
        }
    }

    private fun freshUid(): String {
        var candidate = Achievement.newUid()
        while (ach.registry.byUid(candidate) != null) candidate = Achievement.newUid()
        return candidate
    }

    private fun askDelete(achievement: Achievement) {
        val referrers = ach.registry.referencesTo(achievement.uid)
        if (referrers.isNotEmpty()) {
            ach.tell(
                viewer,
                "admin-delete-blocked",
                ach.ph()
                    .achievement(achievement.display)
                    .reason(referrers.joinToString(", ") { it.id }),
            )
            return
        }
        ConfirmMenu(
            owner = ach,
            question = "<red>'" + achievement.display + "' 을(를) 지울까요?</red>",
            detail = listOf(
                "<gray>되돌릴 수 없습니다.</gray>",
                "<gray>이미 받은 보상은 회수하지 않지만</gray>",
                "<gray>이 업적의 기록과 점수는 사라집니다.</gray>",
            ),
            onConfirm = {
                ach.registry.remove(achievement.uid)
                ach.registry.flush()
                ach.firstClears.forget(achievement.uid)
                ach.service.reindex()
                // 정의가 바뀌었다. 오프라인 포인트까지 다시 센다 - 안 하면 순위표에 옛 점수가 남는다.
                ach.points.rebuildAll()
                ach.tell(viewer, "admin-deleted", ach.ph().achievement(achievement.display))
                open()
            },
            onCancel = { open() },
        ).open(viewer)
    }

    private fun open() {
        AdminListMenu(ach, viewer, page, category).open(viewer)
    }

    private companion object {
        const val SLOT_CREATE = 45
        const val SLOT_CATEGORY = 48
        const val SLOT_REGIONS = 49
        val CATEGORIES: List<Category?> = listOf(null) + Category.entries
    }
}
