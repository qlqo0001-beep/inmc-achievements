package com.inmc.achievements.gui

import com.inmc.achievements.Achievements
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * core 의 [kr.inmc.core.gui.Menu] 에 이 플러그인의 서비스 로케이터를 다시 붙인 얇은 층.
 *
 * core 는 [Achievements] 를 알지 못하고 알 필요도 없다. 그 둘을 잇는 것이 이 파일의 전부다.
 */
abstract class Menu(
    protected val ach: Achievements,
    size: Int,
    title: Component,
) : kr.inmc.core.gui.Menu(size, title) {

    /** 리로드 때 열린 화면을 닫는 청소가 이 값으로 우리 것을 가려낸다. */
    override val owner: Any get() = ach

    /**
     * 고친 정의를 저장한다. **다음 리로드에서 거부될 상태면 저장하지 않고 이유를 알린다.**
     *
     * 이 검사가 없으면 GUI 로 만든 무효 상태(같은 임계값 둘, 단계가 생긴 '발견 즉시 달성',
     * 선행 조건 순환)가 그대로 저장되고, 다음 리로드에서 그 업적이 목록에서 사라진다.
     */
    protected fun store(viewer: Player, value: com.inmc.achievements.achievement.Achievement): Boolean {
        val problem = ach.registry.put(value)
        if (problem != null) {
            ach.tell(viewer, "admin-invalid-id", ach.ph().reason(problem.describe))
            return false
        }
        ach.registry.flush()
        ach.service.reindex()
        return true
    }

    /**
     * 채팅으로 값을 묻는다. **취소하면 이 화면으로 돌아온다.**
     *
     * core 의 [kr.inmc.core.gui.Editors] 를 그대로 쓰되 `reopen` 을 매번 적지 않게 감쌌다.
     * 프롬프트는 인벤토리를 닫으므로 `refresh()` 로는 안 되고 실제로 다시 열어야 한다.
     * 이 인스턴스가 uid·페이지를 들고 있으므로 같은 자리로 정확히 돌아간다.
     */
    protected fun prompt(viewer: Player, label: String, onValue: (String) -> Unit) {
        kr.inmc.core.gui.Editors.promptText(
            ach.prompts, viewer, label, emptyList(), { open(viewer) }, onValue,
        )
    }

    protected fun promptInt(
        viewer: Player,
        label: String,
        min: Int,
        max: Int,
        onValue: (Int) -> Unit,
    ) {
        kr.inmc.core.gui.Editors.promptInt(
            ach.prompts, viewer, label, min, max, { open(viewer) }, onValue,
        )
    }
}
