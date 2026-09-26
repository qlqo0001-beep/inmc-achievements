package com.inmc.achievements.gui

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Achievement
import com.inmc.achievements.achievement.Celebration
import com.inmc.achievements.achievement.Discovery
import com.inmc.achievements.achievement.DiscoveryKind
import com.inmc.achievements.region.Region
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.store.DefinitionKey
import kr.inmc.core.util.Text
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 달성 연출. [tierId] 가 null 이면 업적 기본값, 아니면 그 단계 전용.
 *
 * **여기서 터지는 것은 전부 장식이다** — 지급이 아니므로 실패해도 다시 하지 않는다.
 */
class CelebrationMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
    private val tierId: String?,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>달성 연출</dark_gray>")) {

    private fun current(): Celebration? {
        val item = ach.registry.byUid(uid) ?: return null
        if (tierId == null) return item.celebration
        return item.tier(tierId)?.celebration
    }

    private fun mutate(edit: (Celebration) -> Celebration) {
        val item = ach.registry.byUid(uid) ?: return
        val base = current() ?: Celebration.TOAST_ONLY
        val updated = edit(base)
        val next = if (tierId == null) {
            item.copy(celebration = updated)
        } else {
            item.copy(tiers = item.tiers.map { if (it.id == tierId) it.copy(celebration = updated) else it })
        }
        store(viewer, next)
        refresh()
    }

    override fun draw() {
        clear()
        if (ach.registry.byUid(uid) == null) {
            ach.tell(viewer, "not-found"); AdminListMenu(ach, viewer).open(viewer); return
        }
        val celebration = current() ?: Celebration.TOAST_ONLY

        set(SLOT_SCOPE, Icon.of(Material.SPYGLASS, "<yellow>범위: " + celebration.scope.display + "</yellow>", buildList {
            addAll(Editors.optionList(Celebration.Scope.entries.toList(), celebration.scope) { it.display })
            add("")
            add("<dark_gray>오프라인 완료일 때:</dark_gray>")
            add("<dark_gray>· 본인만 → 다음 접속에 한 번</dark_gray>")
            add("<dark_gray>· 서버 전체 → 즉시</dark_gray>")
            add("<dark_gray>· 주변/월드 → 기준 위치가 없어 생략</dark_gray>")
        })) { event ->
            val next = Editors.cycle(event, Celebration.Scope.entries.toList(), celebration.scope)
            mutate { it.copy(scope = next) }
        }

        if (celebration.scope == Celebration.Scope.NEARBY) {
            set(SLOT_RADIUS, Editors.intIcon(
                Material.TARGET, "<yellow>반경</yellow>", celebration.radius, "블록", emptyList(),
            )) { event ->
                val delta = Editors.step(event, 1)
                mutate { it.copy(radius = (it.radius + delta).coerceIn(1, 256)) }
            }
        }

        set(SLOT_TOAST, Icon.of(
            Icon.toggleMaterial(celebration.toast),
            "<yellow>토스트: " + Icon.toggle(celebration.toast) + "</yellow>",
            buildList {
                add("<gray>마인크래프트 고유의 달성 알림창.</gray>")
                if (!ach.toasts.isEnabled()) {
                    add("<red>지금은 쓸 수 없습니다 — 타이틀·소리로 알립니다.</red>")
                }
                add("<dark_gray>아이콘·제목 편집은 서버를 다시 켜야 반영됩니다.</dark_gray>")
            },
        )) { mutate { it.copy(toast = !it.toast) } }

        set(SLOT_TITLE, textIcon(Material.OAK_SIGN, "화면 제목", celebration.title)) {
            askText("화면 제목") { value -> mutate { it.copy(title = value) } }
        }
        set(SLOT_SUBTITLE, textIcon(Material.OAK_SIGN, "화면 부제목", celebration.subtitle)) {
            askText("화면 부제목") { value -> mutate { it.copy(subtitle = value) } }
        }
        set(SLOT_SOUND, textIcon(Material.NOTE_BLOCK, "소리", celebration.sound)) {
            askText("소리 이름 (예: ENTITY_PLAYER_LEVELUP)") { value -> mutate { it.copy(sound = value) } }
        }
        set(SLOT_PARTICLE, textIcon(Material.BLAZE_POWDER, "입자", celebration.particle)) {
            askText("입자 이름 (예: TOTEM_OF_UNDYING)") { value -> mutate { it.copy(particle = value) } }
        }
        set(SLOT_BROADCAST, textIcon(Material.PAPER, "공지 문구", celebration.broadcast)) {
            askText("공지 문구 ({플레이어} {업적} 사용 가능)") { value -> mutate { it.copy(broadcast = value) } }
        }

        set(SLOT_FIREWORK, Icon.of(
            Icon.toggleMaterial(celebration.firework),
            "<yellow>폭죽: " + Icon.toggle(celebration.firework) + "</yellow>",
            listOf("<gray>피해를 주지 않습니다.</gray>"),
        )) { mutate { it.copy(firework = !it.firework) } }

        set(SLOT_BACK, Icon.back()) {
            if (tierId == null) AchievementEditMenu(ach, viewer, uid).open(viewer)
            else TierEditMenu(ach, viewer, uid, tierId).open(viewer)
        }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun textIcon(material: Material, label: String, value: String) = Icon.of(
        material,
        "<yellow>$label</yellow>",
        listOf(
            "<white>" + value.ifBlank { "(없음)" } + "</white>",
            "", "<yellow>▶ 클릭: 입력</yellow>",
        ),
    )

    private fun askText(what: String, apply: (String) -> Unit) {
        prompt(viewer, "<yellow>$what 을(를) 입력하세요. (비우려면 '-')</yellow>") { raw ->
            apply(raw.trim().takeIf { it != "-" }.orEmpty())
            CelebrationMenu(ach, viewer, uid, tierId).open(viewer)
        }
    }

    private companion object {
        const val SLOT_SCOPE = 19
        const val SLOT_RADIUS = 20
        const val SLOT_TOAST = 22
        const val SLOT_TITLE = 28
        const val SLOT_SUBTITLE = 29
        const val SLOT_SOUND = 30
        const val SLOT_PARTICLE = 31
        const val SLOT_FIREWORK = 32
        const val SLOT_BROADCAST = 33
        const val SLOT_BACK = 45
        const val SLOT_CLOSE = 53
    }
}

/** 히든 업적의 발견 조건. */
class DiscoveryMenu(
    ach: Achievements,
    private val viewer: Player,
    private val uid: String,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>발견 조건</dark_gray>")) {

    private fun definition(): Achievement? = ach.registry.byUid(uid)

    private fun mutate(edit: (Discovery) -> Discovery) {
        val item = definition() ?: return
        store(viewer, item.copy(discovery = edit(item.discovery)))
        refresh()
    }

    override fun draw() {
        clear()
        val item = definition() ?: run {
            ach.tell(viewer, "not-found"); AdminListMenu(ach, viewer).open(viewer); return
        }

        DiscoveryKind.entries.forEachIndexed { index, kind ->
            val chosen = item.discovery.kind == kind
            set(ROW_START + index, Icon.of(
                if (chosen) Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>" + kind.display + "</yellow>",
                buildList {
                    add("<gray>" + hint(kind) + "</gray>")
                    if (chosen && kind.needsValue) {
                        add("<white>" + item.discovery.value.ifBlank { "(비어 있음)" } + "</white>")
                    }
                    add("")
                    add(if (chosen) "<green>▶ 지금 이것</green>" else "<yellow>▶ 클릭: 고르기</yellow>")
                },
            )) { pick(kind) }
        }

        set(SLOT_COMPLETE, Icon.of(
            Icon.toggleMaterial(item.discovery.completeOnDiscover),
            "<yellow>발견 즉시 달성: " + Icon.toggle(item.discovery.completeOnDiscover) + "</yellow>",
            buildList {
                add("<gray>진행도와 무관하게 발견하는 순간 완료됩니다.</gray>")
                if (item.tiers.isNotEmpty()) {
                    add("<red>단계가 있는 업적에는 쓸 수 없습니다.</red>")
                    add("<dark_gray>어느 단계를 줄지 정할 수 없기 때문입니다.</dark_gray>")
                }
            },
        )) {
            if (item.tiers.isEmpty()) mutate { it.copy(completeOnDiscover = !it.completeOnDiscover) }
        }

        set(SLOT_BACK, Icon.back()) { AchievementEditMenu(ach, viewer, uid).open(viewer) }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun hint(kind: DiscoveryKind): String = when (kind) {
        DiscoveryKind.AUTO -> "조건을 달성하면 그때 드러납니다."
        DiscoveryKind.ACHIEVEMENT -> "다른 업적을 달성하면."
        DiscoveryKind.SIGNAL -> "특정 사건이 일어나면. (출처/종류)"
        DiscoveryKind.REGION -> "지역에 들어가면."
        DiscoveryKind.ITEM_USE -> "아이템을 쓰면."
        DiscoveryKind.NPC -> "그 이름의 개체와 상호작용하면."
        DiscoveryKind.CUSTOM -> "외부 플러그인이 등록한 조건."
    }

    private fun pick(kind: DiscoveryKind) {
        if (!kind.needsValue) {
            mutate { it.copy(kind = kind, value = "") }
            return
        }
        if (kind == DiscoveryKind.ACHIEVEMENT) {
            PickAchievementMenu(ach, viewer, uid, "발견 조건") { picked ->
                mutate { it.copy(kind = kind, value = picked) }
                DiscoveryMenu(ach, viewer, uid).open(viewer)
            }.open(viewer)
            return
        }
        prompt(viewer, "<yellow>" + hint(kind) + " 값을 입력하세요.</yellow>") { raw ->
            mutate { it.copy(kind = kind, value = raw.trim()) }
            DiscoveryMenu(ach, viewer, uid).open(viewer)
        }
    }

    private companion object {
        const val ROW_START = 19
        const val SLOT_COMPLETE = 40
        const val SLOT_BACK = 45
        const val SLOT_CLOSE = 53
    }
}

/**
 * 지역 목록. 두 지점을 찍어 만든다.
 *
 * **정밀 검사는 꼭 필요한 곳만.** 기본은 5초 표본이라 리스너가 늘지 않지만, 정밀은 이동마다
 * 검사한다 — 비밀 장소처럼 5초 안에 지나가 버리면 안 되는 곳에만 켠다.
 */
class RegionListMenu(
    ach: Achievements,
    private val viewer: Player,
    private var page: Int = 0,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>지역 관리</dark_gray>")) {

    override fun draw() {
        clear()
        val regions = ach.regions.all()
        Paging.slice(regions, page).forEachIndexed { index, region ->
            set(index, icon(region)) { event ->
                when {
                    event.isRightClick -> askDelete(region)
                    event.isShiftClick -> toggle(region)
                    else -> viewer.teleport(
                        Location(
                            ach.plugin.server.getWorld(region.world),
                            region.minX.toDouble(), region.minY.toDouble(), region.minZ.toDouble(),
                        ),
                    )
                }
            }
        }

        set(SLOT_ADD, Icon.of(Material.GOLDEN_AXE, "<green>＋ 새 지역</green>", listOf(
            "<gray>지금 선 자리를 첫 지점으로 잡습니다.</gray>",
            "<gray>이름을 입력한 뒤 반대쪽 모서리에서</gray>",
            "<gray>다시 한 번 만들면 상자가 완성됩니다.</gray>",
        ))) { askCreate() }

        val pages = Paging.pageCount(regions.size)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(Paging.SLOT_BACK, Icon.back()) { AdminListMenu(ach, viewer).open(viewer) }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private fun icon(region: Region) = Icon.of(
        if (region.precise) Material.TARGET else Material.MAP,
        "<white>" + region.name + "</white>",
        listOf(
            "<gray>월드: " + region.world + "</gray>",
            "<gray>(${region.minX}, ${region.minY}, ${region.minZ})</gray>",
            "<gray>(${region.maxX}, ${region.maxY}, ${region.maxZ})</gray>",
            "<gray>정밀 검사: " + Icon.toggle(region.precise) + "</gray>",
            "",
            "<yellow>▶ 클릭: 이동</yellow>",
            "<yellow>▶ Shift 클릭: 정밀 전환</yellow>",
            "<red>▶ 우클릭: 삭제</red>",
        ),
    )

    private fun toggle(region: Region) {
        ach.regions.put(region.copy(precise = !region.precise))
        ach.regions.flush()
        ach.regions.warnIfCrowded(ach.config.preciseRegionWarnAt)
        ach.service.reindex()
        refresh()
    }

    private fun askCreate() {
        val corner = viewer.location.clone()
        prompt(viewer, "<yellow>지역 이름을 입력하세요.</yellow>") { raw ->
            val name = raw.trim().lowercase()
            if (!DefinitionKey.isValid(name)) {
                ach.tell(viewer, "admin-invalid-id", ach.ph().reason(DefinitionKey.HINT))
            } else {
                val existing = ach.regions.get(name)
                // 같은 이름으로 다시 만들면 **반대쪽 모서리**로 다룬다. 두 지점 찍기가 이렇게 된다.
                val other = existing?.let {
                    Location(corner.world, it.minX.toDouble(), it.minY.toDouble(), it.minZ.toDouble())
                } ?: corner
                Region.between(name, corner, other, existing?.precise ?: false)?.let {
                    ach.regions.put(it)
                    ach.regions.flush()
                    ach.service.reindex()
                }
            }
            RegionListMenu(ach, viewer, page).open(viewer)
        }
    }

    private fun askDelete(region: Region) {
        ConfirmMenu(
            owner = ach,
            question = "<red>지역 '" + region.name + "' 을(를) 지울까요?</red>",
            detail = listOf("<gray>이 지역을 쓰는 업적은 그 조건만 무효가 됩니다.</gray>"),
            onConfirm = {
                ach.regions.remove(region.name)
                ach.regions.flush()
                ach.service.reindex()
                RegionListMenu(ach, viewer, page).open(viewer)
            },
            onCancel = { RegionListMenu(ach, viewer, page).open(viewer) },
        ).open(viewer)
    }

    private companion object {
        const val SLOT_ADD = 49
    }
}
