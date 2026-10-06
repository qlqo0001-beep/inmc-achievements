package com.inmc.achievements.gui

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.FirstClear
import com.inmc.achievements.achievement.Tier
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.reward.GiveMode
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 단계 목록.
 *
 * ## 단계를 고치면 기록을 다시 센다
 *
 * 임계값을 내리면 이미 그만큼 진행한 사람이 새로 자격을 얻고, 단계를 지우면 그 점수가
 * 사라진다. **오프라인 플레이어는 `put`/`drop` 이 안 불리므로** 순위표에 옛 점수가 남는다.
 * 그래서 구조를 바꾸는 편집 뒤에는 전원 재계산을 돌린다 — 관리 작업이라 핫 패스가 아니고,
 * 기록이 전부 메모리에 있어 파일 훑기도 아니다.
 */
class TierListMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>단계와 보상</dark_gray>")) {

    private fun definition(): Achievement? = ach.registry.byUid(uid)

    override fun draw() {
        clear()
        val item = definition() ?: run {
            ach.tell(viewer, "not-found"); AdminListMenu(ach, viewer).open(viewer); return
        }

        if (item.condition?.supportsTiers == false) {
            set(SLOT_NOTICE, Icon.of(Material.BARRIER, "<red>이 조건은 단계를 가질 수 없습니다.</red>", listOf(
                "<gray>상태 조건은 참/거짓이라 누적 임계값이</gray>",
                "<gray>뜻을 갖지 않습니다.</gray>",
            )))
            set(SLOT_SINGLE, singleIcon(item)) { TierEditMenu(ach, viewer, uid, null).open(viewer) }
            set(SLOT_BACK, Icon.back()) { AchievementEditMenu(ach, viewer, uid).open(viewer) }
            set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
            return
        }

        item.tiers.forEachIndexed { index, tier ->
            set(index, tierIcon(item, tier, index)) { event ->
                if (event.isRightClick) askDelete(item, tier)
                else TierEditMenu(ach, viewer, uid, tier.id).open(viewer)
            }
        }

        if (item.tiers.isEmpty()) {
            set(SLOT_SINGLE, singleIcon(item)) { TierEditMenu(ach, viewer, uid, null).open(viewer) }
        }

        if (item.tiers.size < Tier.MAX) {
            set(SLOT_ADD, Icon.of(Material.EMERALD, "<green>＋ 단계 추가</green>", listOf(
                "<gray>임계값을 입력하면 만들어집니다.</gray>",
                "<dark_gray>이미 그만큼 진행한 사람은 즉시 달성합니다.</dark_gray>",
            ))) { askAdd(item) }
        }

        set(SLOT_BACK, Icon.back()) { AchievementEditMenu(ach, viewer, uid).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun tierIcon(item: Achievement, tier: Tier, index: Int) = Icon.of(
        Material.EXPERIENCE_BOTTLE,
        "<yellow>" + tier.label.ifBlank { ROMAN.getOrElse(index) { (index + 1).toString() } } + "</yellow>",
        buildList {
            add("<gray>임계값: <white>" + String.format("%,d", tier.threshold) + "</white></gray>")
            if (tier.points > 0) add("<gray>점수: <yellow>" + tier.points + "</yellow></gray>")
            if (tier.title.isNotBlank()) add("<gray>칭호: <white>" + tier.title + "</white></gray>")
            add("<gray>보상: " + tier.rewards.entries.size + "개</gray>")
            if (tier.firstOnServer != null) add("<gold>서버 최초 보상 있음</gold>")
            add("<dark_gray>" + tier.id + "</dark_gray>")
            add(""); add("<yellow>▶ 좌클릭: 설정</yellow>"); add("<red>▶ 우클릭: 삭제</red>")
        },
    )

    private fun singleIcon(item: Achievement) = Icon.of(
        Material.EXPERIENCE_BOTTLE,
        "<yellow>단계 없음 — 기본 보상</yellow>",
        listOf(
            "<gray>목표: <white>" + item.goal + "</white></gray>",
            "<gray>점수: <yellow>" + (item.single?.points ?: 0) + "</yellow></gray>",
            "", "<yellow>▶ 클릭: 설정</yellow>",
        ),
    )

    private fun askAdd(item: Achievement) {
        promptInt(viewer, "<yellow>임계값을 입력하세요.</yellow>", 1, Int.MAX_VALUE) { value ->
            val id = freshTierId(item)
            val tiers = (item.tiers + Tier(id = id, threshold = value.toLong())).sortedBy { it.threshold }
            if (!Tier.isAscending(tiers)) {
                ach.tell(viewer, "admin-invalid-id", ach.ph().reason("같은 임계값이 이미 있습니다"))
            } else {
                save(item.copy(tiers = tiers))
            }
            TierListMenu(ach, viewer, uid).open(viewer)
        }
    }

    /** 지운 id 는 다시 쓰지 않는다 — 옛 기록이 새 단계에 붙는다. */
    private fun freshTierId(item: Achievement): String {
        var index = item.tiers.size + 1
        while (item.tiers.any { it.id == "t$index" }) index++
        return "t$index"
    }

    private fun askDelete(item: Achievement, tier: Tier) {
        ConfirmMenu(
            owner = ach,
            question = "<red>이 단계를 지울까요?</red>",
            detail = listOf(
                "<gray>이미 받은 보상은 회수하지 않습니다.</gray>",
                "<gray>다만 이 단계가 주던 점수는 사라지고</gray>",
                "<gray>순위가 내려갈 수 있습니다.</gray>",
            ),
            onConfirm = {
                save(item.copy(tiers = item.tiers.filter { it.id != tier.id }))
                TierListMenu(ach, viewer, uid).open(viewer)
            },
            onCancel = { TierListMenu(ach, viewer, uid).open(viewer) },
        ).open(viewer)
    }

    private fun save(updated: Achievement) {
        if (!store(viewer, updated)) return
        // 구조가 바뀌었다. 오프라인까지 다시 센다.
        ach.points.rebuildAll()
    }

    private companion object {
        const val SLOT_NOTICE = 13
        const val SLOT_SINGLE = 22
        const val SLOT_ADD = 49
        const val SLOT_BACK = 45
        const val SLOT_CLOSE = 53
        val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
    }
}

/** 단계 하나. [tierId] 가 null 이면 단계 없는 업적의 기본 보상을 고친다. */
class TierEditMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
    private val tierId: String?,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>단계 설정</dark_gray>")) {

    private fun definition(): Achievement? = ach.registry.byUid(uid)

    private fun tier(): Tier? {
        val item = definition() ?: return null
        return if (tierId == null) item.single ?: Tier("1", item.goal) else item.tier(tierId)
    }

    private fun mutate(edit: (Tier) -> Tier) {
        val item = definition() ?: return
        val current = tier() ?: return
        val updated = edit(current)
        val next = if (tierId == null) {
            item.copy(single = updated, goal = updated.threshold)
        } else {
            item.copy(tiers = item.tiers.map { if (it.id == tierId) updated else it }.sortedBy { it.threshold })
        }
        if (store(viewer, next)) ach.points.rebuildAll()
        refresh()
    }

    override fun draw() {
        clear()
        val item = definition() ?: run {
            ach.tell(viewer, "not-found"); AdminListMenu(ach, viewer).open(viewer); return
        }
        val tier = tier() ?: run {
            TierListMenu(ach, viewer, uid).open(viewer); return
        }

        set(SLOT_THRESHOLD, Editors.numberIcon(
            Material.TARGET,
            "<yellow>임계값</yellow>",
            tier.threshold.toDouble(),
            "",
            listOf("<gray>이 수치에 도달하면 달성됩니다.</gray>"),
        )) { event ->
            if (Editors.isPrompt(event)) {
                promptInt(viewer, "<yellow>임계값을 입력하세요.</yellow>", 1, Int.MAX_VALUE) { value ->
                    mutate { it.copy(threshold = value.toLong()) }
                    open()
                }
            } else {
                val delta = Editors.step(event, 1)
                mutate { it.copy(threshold = (it.threshold + delta).coerceAtLeast(1L)) }
            }
        }

        set(SLOT_LABEL, Icon.of(Material.NAME_TAG, "<yellow>단계 이름</yellow>", listOf(
            "<white>" + tier.label.ifBlank { "(자동: 로마 숫자)" } + "</white>",
        ))) {
            prompt(viewer, "<yellow>단계 이름을 입력하세요. (비우려면 '-')</yellow>") { raw ->
                val value = raw.trim().takeIf { it != "-" }.orEmpty()
                mutate { it.copy(label = value) }
                open()
            }
        }

        set(SLOT_POINTS, Editors.intIcon(
            Material.EXPERIENCE_BOTTLE,
            "<yellow>업적 점수</yellow>",
            tier.points,
            "",
            listOf("<gray>랭크가 이 합계로 오릅니다.</gray>"),
        )) { event ->
            val delta = Editors.step(event, 1)
            mutate { it.copy(points = (it.points + delta).coerceAtLeast(0)) }
        }

        set(SLOT_TITLE, Icon.of(Material.GOLDEN_HELMET, "<yellow>칭호 보상</yellow>", buildList {
            add("<white>" + tier.title.ifBlank { "(없음)" } + "</white>")
            add("<dark_gray>타입:아이디 (예: TITLE:벌목왕)</dark_gray>")
            if (ach.titles.isEnabled) add("<gray>클릭: 목록에서 고르기</gray>")
            else add("<red>타이틀포지가 없어 지급되지 않습니다.</red>")
        })) {
            if (ach.titles.isEnabled) {
                TitlePickMenu(ach, viewer, tier.title,
                    apply = { value -> mutate { it.copy(title = value) } },
                    back = { open() }).open(viewer)
            } else {
                prompt(viewer, "<yellow>칭호를 입력하세요. (비우려면 '-')</yellow>") { raw ->
                    val value = raw.trim().takeIf { it != "-" }.orEmpty()
                    mutate { it.copy(title = value) }
                    open()
                }
            }
        }

        set(SLOT_REWARDS, Icon.of(Material.CHEST, "<yellow>보상</yellow>", rewardLore(tier.rewards))) {
            RewardMenu(ach, viewer, uid, tierId, first = false).open(viewer)
        }

        set(SLOT_FIRST, Icon.of(
            if (tier.firstOnServer != null) Material.GOLD_BLOCK else Material.GOLD_NUGGET,
            "<gold>서버 최초 달성 보상</gold>",
            buildList {
                if (tier.firstOnServer == null) {
                    add("<gray>없음 — 클릭해서 켜세요.</gray>")
                } else {
                    add("<gray>보상 " + tier.firstOnServer.rewards.entries.size + "개</gray>")
                    add("<yellow>▶ 좌클릭: 보상 설정</yellow>")
                    add("<red>▶ 우클릭: 끄기</red>")
                }
                ach.firstClears.nameOf(uid, tier.id)?.let {
                    add(""); add("<dark_gray>이미 " + it + " 님이 달성했습니다.</dark_gray>")
                }
            },
        )) { event ->
            when {
                tier.firstOnServer == null -> mutate { it.copy(firstOnServer = FirstClear()) }
                event.isRightClick -> mutate { it.copy(firstOnServer = null) }
                else -> RewardMenu(ach, viewer, uid, tierId, first = true).open(viewer)
            }
        }

        set(SLOT_CELEBRATION, Icon.of(Material.FIREWORK_ROCKET, "<yellow>이 단계 전용 연출</yellow>", listOf(
            "<gray>" + (tier.celebration?.scope?.display ?: "(업적 기본값을 씁니다)") + "</gray>",
            "", "<yellow>▶ 클릭: 설정</yellow>",
        ))) { CelebrationMenu(ach, viewer, uid, tierId).open(viewer) }

        set(SLOT_BACK, Icon.back()) { TierListMenu(ach, viewer, uid).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun rewardLore(bundle: RewardBundle): List<String> = buildList {
        add("<gray>방식: " + bundle.mode.display + "</gray>")
        add("<gray>항목: " + bundle.entries.size + "개</gray>")
        if (bundle.entries.isEmpty()) add("<dark_gray>(비어 있음)</dark_gray>")
        add(""); add("<yellow>▶ 클릭: 설정</yellow>")
    }

    private fun open() {
        TierEditMenu(ach, viewer, uid, tierId).open(viewer)
    }

    private companion object {
        const val SLOT_THRESHOLD = 20
        const val SLOT_LABEL = 21
        const val SLOT_POINTS = 22
        const val SLOT_TITLE = 23
        const val SLOT_REWARDS = 24
        const val SLOT_FIRST = 30
        const val SLOT_CELEBRATION = 32
        const val SLOT_BACK = 45
        const val SLOT_CLOSE = 53
    }
}

/**
 * 보상 편집. core 의 [RewardBundle] 을 그대로 쓴다 — 일곱 번째 보상 모양을 만들지 않는다.
 *
 * `announce` 는 건드리지 않는다. 그쪽을 켜면 연출의 공지와 겹쳐 **두 번 나간다.**
 */
class RewardMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
    private val tierId: String?,
    private val first: Boolean,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>보상</dark_gray>")) {

    private fun bundle(): RewardBundle? {
        val item = ach.registry.byUid(uid) ?: return null
        val tier = if (tierId == null) item.single else item.tier(tierId)
        return if (first) tier?.firstOnServer?.rewards else tier?.rewards
    }

    private fun save() {
        val item = ach.registry.byUid(uid) ?: return
        // 번들은 가변이라 그 자리에서 고쳐진다. 정의를 다시 넣어 dirty 로 찍기만 한다.
        store(viewer, item)
    }

    override fun draw() {
        clear()
        val bundle = bundle() ?: run {
            TierEditMenu(ach, viewer, uid, tierId).open(viewer); return
        }

        bundle.entries.forEachIndexed { index, entry ->
            set(index, entryIcon(entry)) { event ->
                if (event.isRightClick) {
                    bundle.entries.removeAt(index)
                    save()
                    refresh()
                }
            }
        }

        set(SLOT_ADD_ITEM, Icon.of(Material.HOPPER, "<green>＋ 손에 든 아이템</green>", listOf(
            "<gray>손에 든 것을 보상으로 넣습니다.</gray>",
        ))) {
            val held = viewer.inventory.itemInMainHand
            if (held.type.isAir) return@set
            bundle.entries.add(RewardEntry(item = ach.itemResolver.capture(held)))
            save()
            refresh()
        }

        set(SLOT_ADD_MONEY, Icon.of(Material.GOLD_INGOT, "<green>＋ 돈</green>")) {
            promptInt(viewer, "<yellow>금액을 입력하세요.</yellow>", 1, Int.MAX_VALUE) { value ->
                bundle.entries.add(RewardEntry(money = value.toDouble()))
                save()
                RewardMenu(ach, viewer, uid, tierId, first).open(viewer)
            }
        }

        set(SLOT_ADD_COMMAND, Icon.of(Material.COMMAND_BLOCK, "<green>＋ 명령어</green>", listOf(
            "<dark_gray>{플레이어} 가 이름으로 바뀝니다.</dark_gray>",
        ))) {
            prompt(viewer, "<yellow>명령어를 입력하세요. (/ 없이)</yellow>") { line ->
                bundle.entries.add(RewardEntry(commands = mutableListOf(line.trim())))
                save()
                RewardMenu(ach, viewer, uid, tierId, first).open(viewer)
            }
        }

        set(SLOT_MODE, Icon.of(Material.COMPARATOR, "<yellow>방식: " + bundle.mode.display + "</yellow>", listOf(
            "<gray>" + bundle.mode.help + "</gray>",
        ))) { event ->
            bundle.mode = Editors.cycle(event, GiveMode.entries.toList(), bundle.mode)
            save()
            refresh()
        }

        set(SLOT_BACK, Icon.back()) { TierEditMenu(ach, viewer, uid, tierId).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun entryIcon(entry: RewardEntry) = Icon.of(
        entry.item?.material ?: if (entry.money > 0) Material.GOLD_INGOT else Material.COMMAND_BLOCK,
        "<white>" + describe(entry) + "</white>",
        listOf(
            "<gray>확률: " + entry.chance + "%</gray>",
            "", "<red>▶ 우클릭: 삭제</red>",
        ),
    )

    private fun describe(entry: RewardEntry): String = when {
        entry.item != null -> entry.item?.displayName ?: entry.item!!.material.name
        entry.money > 0 -> "돈 " + String.format("%,.0f", entry.money)
        entry.commands.isNotEmpty() -> entry.commands.first().take(30)
        else -> "(비어 있음)"
    }

    private companion object {
        const val SLOT_ADD_ITEM = 45
        const val SLOT_ADD_MONEY = 46
        const val SLOT_ADD_COMMAND = 47
        const val SLOT_MODE = 49
        const val SLOT_BACK = 52
        const val SLOT_CLOSE = 53
    }
}
