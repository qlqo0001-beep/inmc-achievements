package com.inmc.achievements.listener

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.DiscoveryKind
import kr.inmc.core.event.InmcSignalEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 신호를 받는다. **이 플러그인이 다른 InMC 플러그인과 이어지는 유일한 자리다.**
 *
 * `MONITOR` 로 듣는 것은 우리가 아무것도 바꾸지 않기 때문이다 — 진행도를 세는 것이 전부고,
 * 그 사건을 취소할 권리는 없다.
 */
class SignalListener(private val ach: Achievements) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignal(event: InmcSignalEvent) {
        ach.service.onSignal(event)
    }
}

/**
 * 남의 플러그인이 이미 쏘고 있는 이벤트를 우리 낱말([InmcSignalEvent])로 옮긴다.
 *
 * **몬스터와 타이틀포지는 한 줄도 건드리지 않는다** — 이미 자기 이벤트를 쏘고 있기 때문이다.
 * 이 경계가 옳다는 증거고, 그래서 여기서만 흡수한다.
 *
 * 컴파일 의존을 만들지 않으려고 **클래스 이름으로 찾아 등록한다.** `@EventHandler` 는
 * 구체 이벤트 타입을 요구하므로 쓸 수 없고, `PluginManager.registerEvent` 에 `Class` 를
 * 직접 넘긴다. 그 플러그인이 없으면 클래스가 없고, 그러면 아무것도 등록하지 않는다.
 */
class ForeignEventBridge(private val ach: Achievements) : Listener {

    fun register() {
        bind("com.inmc.monster.api.CustomMobDeathEvent") { event -> monsterKill(event) }
        bind("kr.inmc.titleforge.api.event.BadgeGrantEvent") { event -> badgeGrant(event) }
    }

    private fun bind(className: String, handler: (Any) -> Unit) {
        val type = runCatching { Class.forName(className) }.getOrNull() ?: return
        if (!org.bukkit.event.Event::class.java.isAssignableFrom(type)) return

        @Suppress("UNCHECKED_CAST")
        val eventClass = type as Class<out org.bukkit.event.Event>
        runCatching {
            ach.plugin.server.pluginManager.registerEvent(
                eventClass,
                this,
                EventPriority.MONITOR,
                { _, event -> if (eventClass.isInstance(event)) runCatching { handler(event) } },
                ach.plugin,
                true,
            )
            ach.logger.info("연동 이벤트를 구독했습니다: " + type.simpleName)
        }.onFailure {
            ach.logger.warning("연동 이벤트 구독에 실패했습니다 ($className): " + it.message)
        }
    }

    private fun monsterKill(event: Any) {
        val entity = event.javaClass.getMethod("getEntity").invoke(event)
        val killer = entity?.javaClass?.getMethod("getKiller")?.invoke(entity) as? Player ?: return
        val mobId = (event.javaClass.getMethod("getMobId").invoke(event) as? String).orEmpty()
        if (mobId.isBlank()) return

        InmcSignalEvent.fire(
            source = "monster", type = "kill",
            playerId = killer.uniqueId, subject = mobId, player = killer,
        ) { mapOf("mob" to mobId) }
    }

    private fun badgeGrant(event: Any) {
        val target = event.javaClass.getMethod("getTarget").invoke(event)
            as? org.bukkit.OfflinePlayer ?: return
        val badge = event.javaClass.getMethod("getBadge").invoke(event) ?: return
        val id = (badge.javaClass.getMethod("getId").invoke(badge) as? String).orEmpty()
        if (id.isBlank()) return

        InmcSignalEvent.fire(
            source = "titleforge", type = "badge",
            playerId = target.uniqueId, subject = id,
        ) { mapOf("badge" to id) }
    }
}

/** 접속·퇴장. 적재와 동기화의 경계다. */
class PlayerListener(private val ach: Achievements) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!ach.ready) return
        val player = event.player
        ach.counters.load(ach.players, player.uniqueId)
        ach.points.forget(player.uniqueId)

        // 통계는 플레이어와 같이 적재된다. 1틱 뒤라야 제 값이 나온다.
        // 소급 달성이 실제로 나는 자리이기도 하다.
        player.scheduler.runDelayed(
            ach.plugin,
            {
                ach.service.onJoin(player)
                ach.service.sweep(listOf(player), System.currentTimeMillis())
            },
            null,
            1L,
        )
    }

    /**
     * 퇴장. **30초 주기만 믿으면 최대 1분치 진행도가 사라진다** — 우리 동기화 주기와 core 의
     * 플러시 주기가 독립 타이머라 최악에 더해지기 때문이다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        if (!ach.ready) return
        val id = event.player.uniqueId
        ach.counters.syncTo(ach.players, id)
        ach.counters.forget(id)
        ach.points.forget(id)
    }
}

/**
 * 정밀 지역 발견.
 *
 * **정밀 지역이 하나도 없으면 아무 일도 하지 않는다.** 기본은 5초 표본이고, 그래서는
 * "비밀 장소를 밟으면 열린다" 가 5초 안에 지나가면 안 열린다 — 그 지역만 여기로 온다.
 *
 * 블록이 바뀔 때만 본다. 같은 블록 안에서 도는 것까지 세면 이동 틱마다 검사하는 셈이 된다.
 */
class RegionListener(private val ach: Achievements) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!ach.ready || !ach.service.watchesPreciseRegions()) return
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return

        val world = to.world?.name ?: return
        val regions = ach.regions.preciseIn(world)
        if (regions.isEmpty()) return

        for (region in regions) {
            if (region.contains(to)) ach.service.enteredPreciseRegion(event.player, region.name)
        }
    }
}

/** 아이템 사용·NPC 상호작용으로 여는 히든 업적. */
class DiscoveryListener(private val ach: Achievements) : Listener {

    /** 허공 클릭은 처음부터 '취소됨'으로 태어난다(클릭한 블록이 없어 블록 사용이 DENY) — `ignoreCancelled` 로 받으면 허공 클릭이 통째로 빠진다. 다른 플러그인이 막았는지는 아이템 사용 쪽을 본다. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onUse(event: PlayerInteractEvent) {
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return
        if (!ach.ready) return
        val candidates = ach.service.discoveriesOf(DiscoveryKind.ITEM_USE)
        if (candidates.isEmpty()) return
        val held = event.item ?: return
        if (held.type.isAir) return

        val refs = ach.itemResolver.identifyAll(held).map { it.serialize() }
        for (achievement in candidates) {
            if (refs.none { it.equals(achievement.discovery.value, ignoreCase = true) }) continue
            ach.service.discover(event.player.uniqueId, event.player, achievement)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTalk(event: PlayerInteractEntityEvent) {
        if (!ach.ready) return
        val candidates = ach.service.discoveriesOf(DiscoveryKind.NPC)
        if (candidates.isEmpty()) return

        val entity = event.rightClicked
        val name = entity.customName()?.let { kr.inmc.core.util.Text.plain(it) }.orEmpty()
        for (achievement in candidates) {
            val want = achievement.discovery.value
            if (!name.equals(want, ignoreCase = true) &&
                !entity.scoreboardTags.any { it.equals(want, ignoreCase = true) }
            ) {
                continue
            }
            ach.service.discover(event.player.uniqueId, event.player, achievement)
        }
    }
}

/**
 * 관리 화면의 채팅 입력을 core 의 [kr.inmc.core.input.ChatPrompt] 로 넘긴다.
 *
 * 이게 없으면 화면이 이름이나 숫자를 물어본 뒤 **영영 기다린다.** 오류는 나지 않고 입력만
 * 채팅에 그대로 찍힌다.
 */
class ChatInputListener(private val ach: Achievements) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!ach.prompts.isWaiting(player.uniqueId)) return
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (ach.prompts.submit(player, text)) event.isCancelled = true
    }
}
