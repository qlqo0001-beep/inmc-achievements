package com.inmc.achievements.celebrate

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Celebration
import com.inmc.achievements.claim.CompletionSnapshot
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import java.time.Duration

/**
 * 달성 연출. **전부 장식이라 실패해도 다시 하지 않는다.**
 *
 * 지급과 가르는 선이 중요하다. 지급은 [com.inmc.achievements.claim.ClaimStore] 가 내구성 있게
 * 추적하지만 여기서 터지는 것은 놓쳐도 그만이다 — 폭죽을 한 번 더 터뜨리려고 서버를 뒤질
 * 이유가 없다.
 *
 * 공지도 여기가 전담한다. `RewardEntry.announce` 를 같이 켜면 **두 번 나간다.**
 */
class Celebrations(private val ach: Achievements) {

    /** 완료 하나를 연출한다. 조용한 완료(소급)면 아무것도 안 한다. */
    fun play(snapshot: CompletionSnapshot, player: Player?) {
        if (!snapshot.celebrates()) return
        val celebration = snapshot.celebration

        // 본인 것은 접속해 있을 때만. 오프라인이면 부르는 쪽이 미뤄 뒀다.
        if (player != null) personal(player, snapshot, celebration)

        val ph = ach.ph()
            .achievement(snapshot.achievementName)
            .player(player?.let { kr.inmc.core.integration.TitleForgeNames.displayName(it.uniqueId, it.name) } ?: nameOf(snapshot))
            .tier(snapshot.tierName)

        if (celebration.broadcast.isNotBlank()) {
            broadcast(celebration, player, Text.renderFlat(celebration.broadcast, ph))
        }
    }

    /** 서버 최초 달성. 연출은 **최고 단계 하나만** 오므로 여기서 다시 거르지 않는다. */
    fun playFirstClear(snapshot: CompletionSnapshot, player: Player?) {
        val celebration = snapshot.firstClearCelebration
        val ph = ach.ph()
            .achievement(snapshot.achievementName)
            .player(player?.let { kr.inmc.core.integration.TitleForgeNames.displayName(it.uniqueId, it.name) } ?: nameOf(snapshot))
            .tier(snapshot.tierName)

        if (celebration == null) {
            // 전용 연출이 없어도 최초 달성은 알린다. 그게 이 기능의 요점이다.
            Bukkit.broadcast(ach.messageComponent("first-clear", ph))
            return
        }
        if (player != null) personal(player, snapshot, celebration)
        if (celebration.broadcast.isNotBlank()) {
            broadcast(celebration, player, Text.renderFlat(celebration.broadcast, ph))
        } else {
            Bukkit.broadcast(ach.messageComponent("first-clear", ph))
        }
    }

    /**
     * 관리자 미리보기(`/업적 관리 연출`, 2026-10-08) — 달성·기록·보상 없이 **연출만** 그 사람에게. 공지 문구는 서버에 띄우지 않고
     * 본인에게만 보여 준다. 전에는 연출을 고친 뒤 보려면 달성시키고 초기화해야 했다(그리고 초기화는 준 보상을 거두지 않는다).
     */
    fun preview(player: Player, achievement: com.inmc.achievements.achievement.Achievement) {
        val tier = achievement.tiers.lastOrNull()
        val celebration = tier?.celebration ?: achievement.celebration
        val snapshot = CompletionSnapshot(
            playerId = player.uniqueId,
            uid = achievement.uid,
            tierId = tier?.id.orEmpty(),
            achievementName = achievement.display,
            tierName = tier?.label.orEmpty(),
            points = tier?.points ?: 0,
            units = emptyList(),
            celebration = celebration,
            highest = true,
            online = true,
        )
        personal(player, snapshot, celebration)
        if (celebration.broadcast.isNotBlank()) {
            val ph = ach.ph().achievement(snapshot.achievementName).tier(snapshot.tierName)
                .player(kr.inmc.core.integration.TitleForgeNames.displayName(player.uniqueId, player.name))
            player.sendMessage(Text.renderFlat("<dark_gray>[미리보기 · 공지 ${celebration.scope.display}]</dark_gray> ").append(Text.renderFlat(celebration.broadcast, ph)))
        }
    }

    private fun personal(player: Player, snapshot: CompletionSnapshot, celebration: Celebration) {
        if (celebration.toast) ach.toasts.show(player, snapshot)

        val ph = ach.ph()
            .achievement(snapshot.achievementName)
            .tier(snapshot.tierName)
            .player(kr.inmc.core.integration.TitleForgeNames.displayName(player.uniqueId, player.name))

        if (celebration.title.isNotBlank() || celebration.subtitle.isNotBlank()) {
            player.showTitle(
                Title.title(
                    Text.renderFlat(celebration.title, ph),
                    Text.renderFlat(celebration.subtitle, ph),
                    Title.Times.times(FADE_IN, STAY, FADE_OUT),
                ),
            )
        }
        if (celebration.sound.isNotBlank()) playSound(player, celebration.sound)
        if (celebration.firework) firework(player.location)
        if (celebration.particle.isNotBlank()) particles(player.location, celebration.particle)
    }

    /** 범위대로 보낸다. 본인은 이미 [personal] 에서 받았으므로 빼지 않는다 — 공지는 공지다. */
    private fun broadcast(celebration: Celebration, player: Player?, message: Component) {
        when (celebration.scope) {
            Celebration.Scope.SELF -> player?.sendMessage(message)
            Celebration.Scope.SERVER -> Bukkit.broadcast(message)
            Celebration.Scope.WORLD -> player?.world?.players?.forEach { it.sendMessage(message) }
            Celebration.Scope.NEARBY -> {
                val origin = player?.location ?: return
                val radiusSquared = celebration.radius.toDouble() * celebration.radius
                for (nearby in origin.world.players) {
                    if (nearby.location.distanceSquared(origin) <= radiusSquared) {
                        nearby.sendMessage(message)
                    }
                }
            }
        }
    }

    // --- 낱개 효과. 전부 감싼다 — 관리자 오타로 서버가 멈추면 안 된다 -------------------

    private fun playSound(player: Player, raw: String) {
        val sound = matchSound(raw) ?: return
        runCatching { player.playSound(player.location, sound, 1.0f, 1.0f) }
    }

    /**
     * 소리 이름을 찾는다. 못 찾으면 null 이고 그 효과만 건너뛴다.
     *
     * `Sound.valueOf` 를 쓰지 않는다 — deprecated 이고, 관리자는 `ENTITY_PLAYER_LEVELUP`
     * 처럼도 `entity.player.levelup` 처럼도 적는다. `RegistryAccess` 가 정식 경로다.
     */
    private fun matchSound(raw: String): Sound? {
        val text = raw.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        val name = if (':' in text) text else "minecraft:" + text.replace('.', '_')
        val key = org.bukkit.NamespacedKey.fromString(name) ?: return null
        return runCatching {
            io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.SOUND_EVENT)
                .get(key)
        }.getOrNull()
    }

    private fun firework(location: Location) {
        val world = location.world ?: return
        runCatching {
            world.spawn(location, Firework::class.java) { entity ->
                val meta = entity.fireworkMeta
                meta.addEffect(
                    FireworkEffect.builder()
                        .withColor(Color.YELLOW, Color.ORANGE)
                        .withFade(Color.WHITE)
                        .with(FireworkEffect.Type.BURST)
                        .trail(true)
                        .build(),
                )
                meta.power = 1
                entity.fireworkMeta = meta
                // 축하가 사람을 죽이면 안 된다. 폭죽은 터질 때 실제로 피해를 주므로
                // 표시를 달고 FireworkGuard 가 그 피해만 취소한다.
                entity.persistentDataContainer.set(
                    MARKER,
                    org.bukkit.persistence.PersistentDataType.BYTE,
                    1,
                )
            }
        }
    }

    private fun particles(location: Location, raw: String) {
        val world = location.world ?: return
        val name = raw.trim().uppercase().replace('.', '_')
        val particle = runCatching { Particle.valueOf(name) }.getOrNull() ?: return
        // 데이터가 필요한 입자는 3인자 호출에서 던진다. 감싸서 그 종류만 건너뛴다.
        runCatching { world.spawnParticle(particle, location.clone().add(0.0, 1.0, 0.0), 40, 0.5, 0.8, 0.5, 0.02) }
    }

    private fun nameOf(snapshot: CompletionSnapshot): String =
        Bukkit.getOfflinePlayer(snapshot.playerId).name ?: snapshot.playerId.toString()

    companion object {
        /**
         * 축하용 폭죽이라는 표시.
         *
         * 네임스페이스를 플러그인 이름이 아니라 **상수로 고정**한다 — 다른 InMC 플러그인과
         * 같은 규칙이고, 이름이 바뀌어도 이미 날아간 폭죽의 표시가 유효하다.
         */
        @Suppress("DEPRECATION")
        val MARKER: org.bukkit.NamespacedKey = org.bukkit.NamespacedKey("inmc", "celebration")

        private val FADE_IN: Duration = Duration.ofMillis(300)
        private val STAY: Duration = Duration.ofMillis(2000)
        private val FADE_OUT: Duration = Duration.ofMillis(600)
    }
}

/**
 * 축하 폭죽이 사람을 죽이지 않게 한다.
 *
 * 폭죽은 터질 때 **실제로 피해를 준다.** "장식이니까 괜찮겠지" 하고 두면 업적을 달성한
 * 순간 그 사람이 죽는 일이 생기고, 원인을 찾기도 어렵다.
 *
 * 표시가 달린 폭죽의 피해만 취소한다 — 남이 쏜 폭죽은 건드리지 않는다.
 */
class FireworkGuard : org.bukkit.event.Listener {

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    fun onDamage(event: org.bukkit.event.entity.EntityDamageByEntityEvent) {
        val damager = event.damager as? org.bukkit.entity.Firework ?: return
        val marked = damager.persistentDataContainer
            .has(Celebrations.MARKER, org.bukkit.persistence.PersistentDataType.BYTE)
        if (marked) event.isCancelled = true
    }
}
