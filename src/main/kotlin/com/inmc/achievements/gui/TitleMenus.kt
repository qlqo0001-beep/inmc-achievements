package com.inmc.achievements.gui

import com.inmc.achievements.Achievements
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 칭호 보상 고르기 — 타이틀포지의 칭호·인장 목록.
 *
 * 채팅 입력 대신 고르게 한다. 새 타이틀포지 기능이 아니라 같은 리플렉션 연동이다:
 * 목록은 `TitleForgeHook.listBadges`, 지급은 기존 `grant` 그대로, 저장 형식
 * `"타입:아이디"` 도 그대로라 이미 적어둔 값과 서로 읽는다. 타이틀포지가 없으면
 * 호출자가 채팅 입력으로 보내고 여기에는 오지 않는다.
 */
class TitlePickMenu(
    ach: Achievements,
    private val viewer: Player,
    private val current: String,
    private val apply: (String) -> Unit,
    private val back: () -> Unit,
    private var page: Int = 0,
) : Menu(ach, 54, Text.renderFlat("<dark_gray>칭호 고르기</dark_gray>")) {

    override fun draw() {
        clear()
        val badges = ach.titles.listBadges()
        if (badges.isEmpty()) {
            set(SLOT_NOTICE, Icon.of(Material.BARRIER, "<red>목록을 불러오지 못했습니다.</red>", listOf(
                "<gray>타이틀포지에 등록된 칭호가 없거나</gray>",
                "<gray>연동이 끊겼습니다. 뒤로 가서 채팅으로 입력하세요.</gray>",
            )))
            set(Paging.SLOT_BACK, Icon.back()) { back() }
            set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
            return
        }
        Paging.slice(badges, page).forEachIndexed { index, ref ->
            val key = ref.type + ":" + ref.id
            set(index, Icon.of(Material.PAPER, "<white>" + ref.display + "</white>", buildList {
                add("<dark_gray>" + key + "</dark_gray>")
                if (key == current) add("<green>▶ 지금 이 보상</green>")
            })) {
                apply(key)
                back()
            }
        }
        set(SLOT_CLEAR, Icon.of(Material.BARRIER, "<gray>없음 (지우기)</gray>", listOf(
            "<gray>칭호 보상을 없앱니다.</gray>",
        ))) {
            apply("")
            back()
        }
        val pages = Paging.pageCount(badges.size)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < pages - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        set(Paging.SLOT_BACK, Icon.back()) { back() }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    private companion object {
        /** 하단 줄의 빈칸. 목록(0~44)·이전·다음·뒤로·닫기와 겹치지 않는다. */
        const val SLOT_CLEAR = 49
        const val SLOT_NOTICE = 22
    }
}
