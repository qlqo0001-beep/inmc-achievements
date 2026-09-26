package com.inmc.achievements.achievement

import org.bukkit.configuration.ConfigurationSection

/**
 * 달성했을 때 무슨 일이 벌어지는가. **장식이다** — 여기서 터지는 것은 하나도 지급이 아니다.
 *
 * 지급과 장식을 가르는 선이 중요하다. 지급은 [com.inmc.achievements.claim.ClaimStore] 가
 * 내구성 있게 추적하고 재시도 대상이 되지만, 장식은 놓쳐도 다시 하지 않는다 —
 * 폭죽을 한 번 더 터뜨리려고 서버를 뒤질 이유가 없다.
 *
 * 그래서 `RewardEntry.announce` 는 **쓰지 않는다.** 그쪽도 켜면 공지가 두 번 나간다.
 * 업적의 보상 경로는 지급 효과만 resolve 하고 알리는 일은 전부 여기로 모은다.
 */
data class Celebration(
    val scope: Scope = Scope.SELF,
    /** [Scope.NEARBY] 일 때의 반경(블록). */
    val radius: Int = 32,
    val toast: Boolean = true,
    val title: String = "",
    val subtitle: String = "",
    val sound: String = "",
    val firework: Boolean = false,
    val particle: String = "",
    /** 채팅 공지. 비면 안 보낸다. */
    val broadcast: String = "",
) {

    /** 아무것도 안 하는가. 비어 있으면 아예 부르지 않는다. */
    fun isSilent(): Boolean =
        !toast && !firework && title.isBlank() && subtitle.isBlank() &&
            sound.isBlank() && particle.isBlank() && broadcast.isBlank()

    /**
     * 본인이 접속해 있지 않아도 지금 할 수 있는가.
     *
     * [Scope.SERVER] 만 그렇다 — 서버 공지는 본인이 없어도 뜻이 통한다.
     * [Scope.SELF] 는 다음 접속으로 미루고, [Scope.NEARBY] 와 [Scope.WORLD] 는
     * **기준 위치가 없어 재현할 수 없으므로 아예 하지 않는다.**
     */
    fun runnableWhileOffline(): Boolean = scope == Scope.SERVER

    /** 다음 접속에 미룰 수 있는가. 주변·월드는 그때 위치가 달라 뜻이 없다. */
    fun deferrableToJoin(): Boolean = scope == Scope.SELF

    fun save(section: ConfigurationSection) {
        section.set("scope", scope.name)
        if (scope == Scope.NEARBY) section.set("radius", radius)
        section.set("toast", toast)
        if (title.isNotBlank()) section.set("title", title)
        if (subtitle.isNotBlank()) section.set("subtitle", subtitle)
        if (sound.isNotBlank()) section.set("sound", sound)
        if (firework) section.set("firework", true)
        if (particle.isNotBlank()) section.set("particle", particle)
        if (broadcast.isNotBlank()) section.set("broadcast", broadcast)
    }

    companion object {

        /** 일반 업적의 기본값 — 토스트만. 요구사항의 "일반적인 업적은 토스트만" 이 이것이다. */
        val TOAST_ONLY = Celebration()

        fun load(section: ConfigurationSection?): Celebration? {
            if (section == null) return null
            return Celebration(
                scope = Scope.of(section.getString("scope")),
                radius = section.getInt("radius", 32).coerceIn(1, 256),
                toast = section.getBoolean("toast", true),
                title = section.getString("title").orEmpty(),
                subtitle = section.getString("subtitle").orEmpty(),
                sound = section.getString("sound").orEmpty(),
                firework = section.getBoolean("firework", false),
                particle = section.getString("particle").orEmpty(),
                broadcast = section.getString("broadcast").orEmpty(),
            )
        }
    }

    /** 누가 보는가. */
    enum class Scope(val display: String) {
        SELF("본인만"),
        NEARBY("주변 사람"),
        WORLD("같은 월드"),
        SERVER("서버 전체"),
        ;

        companion object {
            fun of(raw: String?): Scope =
                entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: SELF
        }
    }
}
