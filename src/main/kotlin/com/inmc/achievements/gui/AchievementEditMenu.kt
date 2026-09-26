package com.inmc.achievements.gui

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Category
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 업적 설정 허브.
 *
 * **정의가 아니라 uid 를 들고 있다.** 정의가 불변이라 한 칸 고칠 때마다 새 객체가 되는데,
 * 옛 객체를 붙들고 있으면 두 번째 편집이 첫 번째를 지운다.
 *
 * 리로드로 그 uid 가 사라지면 **메시지와 함께 닫는다** — `draw()` 안에서 NPE 를 내지 않는다.
 */
class AchievementEditMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>업적 설정</dark_gray>")) {

    private fun definition(): Achievement? = ach.registry.byUid(uid)

    /** 한 칸을 고친다. 정의를 새로 만들어 레지스트리에 갈아끼운다. */
    private fun mutate(reopen: Boolean = true, edit: (Achievement) -> Achievement) {
        val current = definition() ?: return
        store(viewer, edit(current))
        if (reopen) refresh()
    }

    override fun draw() {
        clear()
        val item = definition() ?: run {
            ach.tell(viewer, "not-found")
            AdminListMenu(ach, viewer).open(viewer)
            return
        }

        set(SLOT_PREVIEW, preview(item))

        set(SLOT_NAME, Icon.of(Material.NAME_TAG, "<yellow>표시 이름</yellow>", "<white>" + item.display + "</white>")) {
            prompt(viewer, "<yellow>표시 이름을 입력하세요.</yellow>") { value ->
                mutate(reopen = false) { it.copy(display = value.trim()) }
                open()
            }
        }

        set(SLOT_DESC, Icon.of(
            Material.BOOK,
            "<yellow>설명</yellow>",
            item.description.map { "<gray>$it</gray>" }.ifEmpty { listOf("<dark_gray>(없음)</dark_gray>") } +
                listOf("", "<yellow>▶ 클릭: 한 줄 추가</yellow>", "<red>▶ 우클릭: 마지막 줄 삭제</red>"),
        )) { event ->
            if (event.isRightClick) {
                mutate { it.copy(description = it.description.dropLast(1)) }
            } else {
                prompt(viewer, "<yellow>설명 한 줄을 입력하세요.</yellow>") { value ->
                    mutate(reopen = false) { it.copy(description = it.description + value.trim()) }
                    open()
                }
            }
        }

        set(SLOT_ICON, Icon.of(item.icon, "<yellow>아이콘</yellow>", listOf(
            "<gray>" + item.icon.name + "</gray>",
            "",
            "<yellow>▶ 클릭: 손에 든 것으로</yellow>",
        ))) {
            val held = viewer.inventory.itemInMainHand
            if (held.type.isAir) return@set
            mutate { it.copy(icon = held.type) }
        }

        set(SLOT_CATEGORY, Icon.of(
            item.category.icon,
            "<yellow>분류: " + item.category.display + "</yellow>",
            Editors.optionList(Category.entries.toList(), item.category) { it.display },
        )) { event ->
            val next = Editors.cycle(event, Category.entries.toList(), item.category)
            mutate { it.copy(category = next) }
        }

        set(SLOT_CONDITION, Icon.of(Material.COMPARATOR, "<yellow>조건</yellow>", listOf(
            "<gray>무엇을 해야 달성되는가</gray>",
            "",
            "<yellow>▶ 클릭: 설정</yellow>",
        ))) { ConditionMenu(ach, viewer, uid).open(viewer) }

        set(SLOT_TIERS, Icon.of(Material.EXPERIENCE_BOTTLE, "<yellow>단계와 보상</yellow>", tierLore(item))) {
            TierListMenu(ach, viewer, uid).open(viewer)
        }

        set(SLOT_CELEBRATION, Icon.of(Material.FIREWORK_ROCKET, "<yellow>달성 연출</yellow>", listOf(
            "<gray>범위: " + item.celebration.scope.display + "</gray>",
            "<gray>토스트: " + Icon.toggle(item.celebration.toast) + "</gray>",
            "",
            "<yellow>▶ 클릭: 설정</yellow>",
        ))) { CelebrationMenu(ach, viewer, uid, null).open(viewer) }

        set(SLOT_HIDDEN, Icon.of(
            if (item.hidden) Material.ENDER_EYE else Material.ENDER_PEARL,
            "<yellow>히든: " + Icon.toggle(item.hidden) + "</yellow>",
            listOf(
                "<gray>발견하기 전에는 이름·설명·조건을</gray>",
                "<gray>감춥니다.</gray>",
                "",
                "<yellow>▶ 클릭: 전환</yellow>",
            ),
        )) { mutate { it.copy(hidden = !it.hidden) } }

        if (item.hidden) {
            set(SLOT_DISCOVERY, Icon.of(Material.SPYGLASS, "<yellow>발견 조건</yellow>", listOf(
                "<gray>" + item.discovery.kind.display + "</gray>",
                "",
                "<yellow>▶ 클릭: 설정</yellow>",
            ))) { DiscoveryMenu(ach, viewer, uid).open(viewer) }

            set(SLOT_COUNTS, Icon.of(
                Icon.toggleMaterial(item.countsTowardTotal),
                "<yellow>전체 개수에 포함: " + Icon.toggle(item.countsTowardTotal) + "</yellow>",
                listOf(
                    "<gray>끄면 '전체 달성률' 분모에서 빠집니다.</gray>",
                    "<dark_gray>점수는 그대로 지급됩니다 — 그래서</dark_gray>",
                    "<dark_gray>달성률 100% 와 최대 점수가 다를 수 있습니다.</dark_gray>",
                ),
            )) { mutate { it.copy(countsTowardTotal = !it.countsTowardTotal) } }
        }

        set(SLOT_LISTED, Icon.of(
            Icon.toggleMaterial(item.listed),
            "<yellow>목록 공개: " + Icon.toggle(item.listed) + "</yellow>",
            listOf("<gray>끄면 관리자만 봅니다.</gray>"),
        )) { mutate { it.copy(listed = !it.listed) } }

        set(SLOT_NEXT, Icon.of(Material.ARROW, "<yellow>다음 업적</yellow>", listOf(
            "<gray>" + (ach.registry.byUid(item.next)?.display ?: "(없음)") + "</gray>",
            "",
            "<dark_gray>표시 전용입니다 — 해금하지 않습니다.</dark_gray>",
            "<dark_gray>해금은 발견 조건이, 선행은 조건이 맡습니다.</dark_gray>",
            "",
            "<yellow>▶ 클릭: 고르기</yellow>",
            "<red>▶ 우클릭: 지우기</red>",
        ))) { event ->
            if (event.isRightClick) {
                mutate { it.copy(next = "") }
            } else {
                PickAchievementMenu(ach, viewer, uid, "다음 업적") { picked ->
                    mutate(reopen = false) { it.copy(next = picked) }
                    open()
                }.open(viewer)
            }
        }

        set(SLOT_RETROACTIVE, Icon.of(
            Material.CLOCK,
            "<yellow>이미 해 둔 것으로 달성: " + item.retroactive.display + "</yellow>",
            listOf(
                "<gray>업적을 새로 만들거나 단계를 더하면, 조건을</gray>",
                "<gray>이미 채운 사람들이 한꺼번에 달성합니다.</gray>",
                "",
                "<gray>조용히 — 보상만 주고 연출·공지 생략</gray>",
                "<gray>연출함 — 평소처럼 공지·폭죽</gray>",
                "<dark_gray>조용히 달성한 것은 서버 최초 경쟁에 들지 않습니다.</dark_gray>",
                "",
                "<yellow>▶ 클릭: 전환</yellow>",
            ),
        )) { event ->
            val next = Editors.cycle(
                event,
                com.inmc.achievements.achievement.Retroactive.entries.toList(),
                item.retroactive,
            )
            mutate { it.copy(retroactive = next) }
        }

        set(SLOT_GIVE, Icon.of(Material.CHEST, "<yellow>나에게 지급</yellow>", listOf(
            "<gray>조건을 무시하고 첫 단계를 지급합니다.</gray>",
            "<dark_gray>설정을 고친 자리에서 바로 확인하려고.</dark_gray>",
        ))) {
            ach.service.grant(viewer.uniqueId, item, null)
        }

        set(SLOT_BACK, Icon.back()) { AdminListMenu(ach, viewer).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun preview(item: Achievement) = Icon.of(
        item.icon,
        "<white>" + item.display + "</white>",
        buildList {
            add("<dark_gray>" + item.id + " · " + item.uid + "</dark_gray>")
            if (item.description.isNotEmpty()) {
                add("")
                addAll(item.description.map { "<gray>$it</gray>" })
            }
            add("")
            add("<dark_gray>토스트 아이콘·제목 편집은 서버를</dark_gray>")
            add("<dark_gray>다시 켜야 반영됩니다. 다른 설정은 즉시.</dark_gray>")
        },
    )

    private fun tierLore(item: Achievement): List<String> = buildList {
        if (item.tiers.isEmpty()) {
            add("<gray>단계 없음 — 목표 <white>${item.goal}</white></gray>")
        } else {
            for (tier in item.tiers) {
                add("<gray>· " + tier.label.ifBlank { tier.id } + " <white>${tier.threshold}</white>" +
                    (if (tier.points > 0) " <dark_gray>(${tier.points}점)</dark_gray>" else "") + "</gray>")
            }
        }
        if (item.condition?.supportsTiers == false) {
            add("")
            add("<red>이 조건은 단계를 가질 수 없습니다.</red>")
        }
        add("")
        add("<yellow>▶ 클릭: 설정</yellow>")
    }

    private fun open() {
        AchievementEditMenu(ach, viewer, uid).open(viewer)
    }

    private companion object {
        const val SLOT_PREVIEW = 4
        const val SLOT_NAME = 19
        const val SLOT_DESC = 20
        const val SLOT_ICON = 21
        const val SLOT_CATEGORY = 22
        const val SLOT_CONDITION = 23
        const val SLOT_TIERS = 24
        const val SLOT_CELEBRATION = 25
        const val SLOT_HIDDEN = 28
        const val SLOT_DISCOVERY = 29
        const val SLOT_COUNTS = 30
        const val SLOT_LISTED = 31
        const val SLOT_NEXT = 32
        const val SLOT_GIVE = 33
        const val SLOT_RETROACTIVE = 34
        const val SLOT_BACK = 45
        const val SLOT_CLOSE = 53
    }
}
