package com.inmc.achievements.hook

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Logger

/**
 * 권한 그룹(LuckPerms 등급) 조회. **필수 의존이 아니다.**
 *
 * ## 왜 권한 노드만으로 하지 않는가
 *
 * LuckPerms 는 그룹 소속에게 `group.<이름>` 노드를 자동으로 준다. 그래서 `hasPermission`
 * 하나로 덮을 수 있을 것 같지만 **그건 LuckPerms 고유 동작이다** — PEX·GroupManager 에서
 * `group.vip` 는 아무도 안 가진 그냥 문자열이다.
 *
 * 더 중요한 이유는 **화면**이다. LuckPerms 가 있으면 여기서 **실제 그룹 목록**을 꺼내
 * 편집 화면에 보여줄 수 있다. 관리자가 이름을 손으로 치게 두면 오타 하나로 그 업적이
 * 오류 없이 영영 완료되지 않는다 — 이 플러그인에서 가장 날 법한 버그가 그것이다.
 *
 * 전부 리플렉션이다. 컴파일 의존을 만들면 LuckPerms 없는 서버에서 클래스 적재가 터진다.
 */
class LuckPermsHook(private val logger: Logger) {

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var api: Any? = null
    private var groupManager: Any? = null

    fun setup() {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN) == null) {
            logger.info("LuckPerms 를 찾지 못했습니다 - 권한 그룹 조건은 권한 노드로 판정합니다")
            return
        }
        runCatching {
            val provider = Bukkit.getServicesManager()
                .getRegistration(Class.forName("net.luckperms.api.LuckPerms"))
                ?: error("LuckPerms 서비스가 등록되지 않았습니다")
            api = provider.provider
            groupManager = api?.javaClass?.getMethod("getGroupManager")?.invoke(api)
            isEnabled = true
            logger.info("LuckPerms 연동 활성화")
        }.onFailure {
            logger.warning("LuckPerms 연동에 실패했습니다: " + it.message)
        }
    }

    /**
     * 편집 화면에 보여줄 그룹 목록. 연동이 없으면 **빈 목록이 아니라 null** 이다 —
     * 빈 목록이면 화면이 "그룹이 하나도 없습니다" 라고 거짓말을 한다.
     */
    fun groups(): List<String>? {
        if (!isEnabled) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val loaded = groupManager?.javaClass?.getMethod("getLoadedGroups")?.invoke(groupManager)
                as? Collection<Any> ?: return null
            loaded.mapNotNull { group ->
                group.javaClass.getMethod("getName").invoke(group) as? String
            }.sorted()
        }.getOrNull()
    }

    /**
     * 이 사람이 그 그룹인가. **판단할 수 없으면 null.**
     *
     * 접속 중이면 권한 노드로 본다 — LuckPerms 의 상속(MVP 가 VIP 를 물려받음)까지 자연히
     * 반영되고, 다른 권한 플러그인에서도 관리자가 `group.vip` 를 직접 주면 돈다.
     * 오프라인은 판단하지 않는다.
     */
    fun inGroup(playerId: UUID, player: Player?, group: String): Boolean? {
        val name = group.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        if (player != null) return player.hasPermission(NODE_PREFIX + name)
        return null
    }

    /** 이 이름을 아는 그룹이 있는가. 연동이 없으면 null(판단 안 함). */
    fun knows(group: String): Boolean? =
        groups()?.any { it.equals(group.trim(), ignoreCase = true) }

    private companion object {
        const val PLUGIN = "LuckPerms"
        const val NODE_PREFIX = "group."
    }
}
