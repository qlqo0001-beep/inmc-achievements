package com.inmc.achievements.hook

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.logging.Logger

/**
 * 칭호 지급. **타이틀포지가 없어도 돌아간다.**
 *
 * `TitleForgeApi.grant(player, type, id, durationMillis)` 하나만 쓴다. 전부 리플렉션인 것은
 * 컴파일 의존을 만들면 타이틀포지 없는 서버에서 클래스 적재가 터지기 때문이다.
 *
 * ## 오프라인이면 미룬다
 *
 * 그쪽 API 는 `Player` 를 받는다. 그 API 를 우리 편의로 넓히지 않는 것이 이 경계의 요점이라,
 * 오프라인 완료의 칭호는 [com.inmc.achievements.claim.UnitState.DEFERRED] 로 두고 **접속할
 * 때 실행한다.** 그래서 이 훅은 "지금 줄 수 있으면 주고 아니면 못 줬다고 말하는" 것만 한다.
 */
class TitleForgeHook(private val logger: Logger) {

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var apiClass: Class<*>? = null
    private var apiInstance: Any? = null
    private var badgeTypeClass: Class<*>? = null

    fun setup() {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN) == null) {
            logger.info("$PLUGIN 을(를) 찾지 못했습니다 - 칭호 보상은 건너뜁니다")
            return
        }
        runCatching {
            val klass = Class.forName("kr.inmc.titleforge.api.TitleForgeApi")
            // Kotlin object 라 INSTANCE 필드로 잡는다.
            apiInstance = klass.getField("INSTANCE").get(null)
            apiClass = klass
            badgeTypeClass = Class.forName("kr.inmc.titleforge.badge.BadgeType")
            isEnabled = true
            logger.info("$PLUGIN 연동 활성화")
        }.onFailure {
            logger.warning("$PLUGIN 연동에 실패했습니다: " + it.message)
        }
    }

    /**
     * `타입:아이디` 를 지급한다. 타입을 안 적었으면 `TITLE` 로 본다.
     *
     * **이미 갖고 있으면 그쪽이 false 를 준다.** 우리는 그것도 성공으로 다룬다 — 빚을 갚는
     * 것이 목적이고, 이미 갖고 있으면 빚이 없다. false 를 실패로 다루면 그 unit 이 영원히
     * 미지급 목록에 남는다.
     */
    fun grant(player: Player, raw: String): Boolean {
        if (!isEnabled) return false
        val (type, id) = split(raw) ?: return false
        return runCatching {
            val badgeType = java.lang.Enum.valueOf(
                @Suppress("UNCHECKED_CAST")
                (badgeTypeClass as Class<out Enum<*>>),
                type,
            )
            apiClass!!
                .getMethod("grant", Player::class.java, badgeTypeClass, String::class.java, Long::class.java)
                .invoke(apiInstance, player, badgeType, id, 0L)
            true
        }.onFailure {
            logger.warning("칭호 '$raw' 지급에 실패했습니다: " + it.message)
        }.isSuccess
    }

    /** `타입:아이디` 를 거둔다(초기화 때, 2026-10-08). 안 갖고 있었으면 그쪽이 false — 거둘 것이 없으니 참으로 본다. */
    fun revoke(player: Player, raw: String): Boolean {
        if (!isEnabled) return false
        val (type, id) = split(raw) ?: return false
        return runCatching {
            val badgeType = java.lang.Enum.valueOf(
                @Suppress("UNCHECKED_CAST")
                (badgeTypeClass as Class<out Enum<*>>),
                type,
            )
            apiClass!!
                .getMethod("revoke", Player::class.java, badgeTypeClass, String::class.java)
                .invoke(apiInstance, player, badgeType, id)
            true
        }.onFailure {
            logger.warning("칭호 '$raw' 회수에 실패했습니다: " + it.message)
        }.isSuccess
    }

    /** `TITLE:벌목왕` · `벌목왕`(타입 생략). 못 읽으면 null. */
    fun split(raw: String): Pair<String, String>? {
        val text = raw.trim().takeIf { it.isNotBlank() } ?: return null
        val parts = text.split(':', limit = 2)
        return if (parts.size == 2) parts[0].trim().uppercase() to parts[1].trim()
        else DEFAULT_TYPE to text
    }

    /**
     * 타이틀포지의 칭호·인장 목록. `TitleForgeApi.all(type)` 하나만 쓴다 — grant 와 같은
     * 리플렉션 경계라 타이틀포지 없는 서버에서도 적재가 터지지 않는다.
     *
     * 편집 화면의 선택 목록용이다. 지급은 기존 `grant` 그대로.
     */
    fun listBadges(): List<TitleRef> {
        if (!isEnabled) return emptyList()
        return runCatching {
            val all = apiClass!!.getMethod("all", badgeTypeClass)
            val out = ArrayList<TitleRef>()
            for (constant in badgeTypeClass!!.enumConstants) {
                val type = (constant as Enum<*>).name
                val result = all.invoke(apiInstance, constant) as? List<*> ?: continue
                for (badge in result) {
                    if (badge == null) continue
                    val id = badge.javaClass.getMethod("getId").invoke(badge) as? String ?: continue
                    val display = badge.javaClass.getMethod("getDisplayName").invoke(badge) as? String ?: id
                    out += TitleRef(type, id, display)
                }
            }
            out.sortedWith(compareBy({ it.type }, { it.id }))
        }.getOrDefault(emptyList())
    }

    /** 편집 화면 선택 목록 한 줄. `title` 필드에는 `"${type}:${id}"` 로 적힌다. */
    data class TitleRef(val type: String, val id: String, val display: String)

    private companion object {
        const val PLUGIN = "InMc-TitleForge"
        const val DEFAULT_TYPE = "TITLE"
    }
}
