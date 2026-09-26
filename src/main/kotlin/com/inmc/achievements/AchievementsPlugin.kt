package com.inmc.achievements

import com.inmc.achievements.api.AchievementsApi
import com.inmc.achievements.api.AchievementsReadyEvent
import com.inmc.achievements.config.AchievementsConfig
import com.inmc.achievements.config.Messages
import com.inmc.achievements.listener.ChatInputListener
import com.inmc.achievements.listener.DiscoveryListener
import com.inmc.achievements.listener.ForeignEventBridge
import com.inmc.achievements.listener.PlayerListener
import com.inmc.achievements.listener.RegionListener
import com.inmc.achievements.listener.SignalListener
import com.inmc.achievements.scheduler.Ticker
import kr.inmc.core.event.SignalCatalog
import org.bukkit.plugin.java.JavaPlugin

/**
 * 진입점. **배선만** 한다.
 *
 * ## 순서가 계약이다
 *
 * `PlayerStore.ready` 는 **비동기다** — core 가 워커에서 전 플레이어 파일을 읽고 메인 콜백에서
 * 준비를 알린다. 그래서 진행도를 건드리는 초기화는 전부 `whenReady { }` 안이다. 리로드로
 * 이미 접속자가 있을 수 있으므로 거기서 온라인 전원을 적재한다.
 *
 * 구독과 공급처는 **정의를 다 읽기 전에** 꽂는다. Paper 는 core 를 먼저 올리지만 우리와
 * 낚시·랜덤박스 사이에는 순서가 없다.
 */
class AchievementsPlugin : JavaPlugin() {

    private lateinit var ach: Achievements
    private lateinit var ticker: Ticker
    private lateinit var bridge: ForeignEventBridge
    private lateinit var metrics: com.inmc.achievements.hook.MetricsHook

    override fun onEnable() {
        ach = Achievements(this)
        ticker = Ticker(ach)
        bridge = ForeignEventBridge(ach)
        metrics = com.inmc.achievements.hook.MetricsHook(ach)

        ach.mmoItems.setup()
        ach.customItems.setup()
        ach.economy.setup()
        ach.luckPerms.setup()
        ach.titles.setup()

        registerListeners()
        com.inmc.achievements.command.AchievementCommand(ach).register(this)
        AchievementsApi.bind(ach)

        reload {
            ach.claims.load {
                ach.firstClears.load {
                    ach.ranks.load {
                        ach.mailbox.load {
                            // 진행도는 core 의 저장소가 준비된 뒤라야 읽을 수 있다.
                            ach.players.whenReady {
                                for (player in server.onlinePlayers) {
                                    ach.counters.load(ach.players, player.uniqueId)
                                }
                                ach.markReady()
                                ticker.start()
                                metrics.start()
                                announceReady()
                                logger.info("inmc-achievements 활성화 완료 — 업적 ${ach.registry.size}개")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDisable() {
        if (!::ach.isInitialized) return

        // 1. 새 신호를 그만 받는다. 2. 남은 완료를 끝낸다 - 일회성 신호는 다시 오지 않는다.
        ach.accepting = false
        ticker.stop()
        metrics.stop()
        runCatching { ach.service.drainAll() }

        // 3. 진행도를 공유 저장소로. flush 는 core 가 자기 onDisable 에서 한다.
        runCatching { ach.counters.syncTo(ach.players) }

        // 4. 공급처와 평가기를 뺀다. 람다가 우리 클래스로더를 붙들고 있다.
        SignalCatalog.unregisterAll(SOURCE)
        ach.custom.clear()
        AchievementsApi.bind(null)

        ach.ranks.flushBlocking()
        ach.mailbox.flushBlocking()
        ach.registry.flushBlocking()
        ach.regions.flushBlocking()
        ach.io.shutdown()
    }

    private fun registerListeners() {
        val manager = server.pluginManager
        manager.registerEvents(SignalListener(ach), this)
        manager.registerEvents(PlayerListener(ach), this)
        manager.registerEvents(RegionListener(ach), this)
        manager.registerEvents(DiscoveryListener(ach), this)
        manager.registerEvents(ChatInputListener(ach), this)
        // 축하 폭죽이 사람을 죽이지 않게. 폭죽은 터질 때 실제로 피해를 준다.
        manager.registerEvents(com.inmc.achievements.celebrate.FireworkGuard(), this)
        manager.registerEvents(kr.inmc.core.listener.MenuListener(ach), this)
        bridge.register()
    }

    /**
     * 설정과 정의를 다시 읽는다.
     *
     * **순서가 중요하다.** 큐를 먼저 비우지 않으면 대기 중인 완료가 연출할 때 옛 정의를
     * 보게 되고, 업적이 지워졌으면 그 정의가 아예 없다.
     */
    fun reload(then: () -> Unit = {}) {
        for (name in RESOURCES + COPY_ONLY) ach.io.copyDefault(name, ach.io.file(name))

        // 1. 새 신호를 그만 받고 2. 남은 완료를 끝낸다.
        ach.accepting = false
        if (ach.ready) {
            runCatching { ach.service.drainAll() }
            runCatching { ach.counters.syncTo(ach.players) }
        }

        ach.io.async({
            RESOURCES.map { ach.io.load(ach.io.file(it)) }
        }) { (configYaml, messagesYaml) ->
            ach.config = AchievementsConfig.from(configYaml)
            ach.messages = Messages.from(messagesYaml)

            closeOpenMenus()

            ach.registry.load {
                // uid 를 새로 붙였으면 **서비스에 올리기 전에** 파일을 확정한다.
                // 비동기 저장에 맡기면 그 사이 진행이 쌓이고, 죽으면 다음 시작에 uid 가
                // 새로 생겨 기록이 고아가 된다.
                if (ach.registry.confirmBootstrap()) {
                    logger.info("업적 식별자를 파일에 확정했습니다")
                }
                ach.regions.load {
                    ach.regions.warnIfCrowded(ach.config.preciseRegionWarnAt)
                    ach.service.reindex()
                    // 정의가 파일에서 바뀌었을 수 있다(손으로 고친 경우). 오프라인 플레이어의
                    // 점수는 따로 갱신될 계기가 없으므로 전원을 다시 센다. 처음 켤 때는 기록이
                    // 아직 안 올라왔으니 캐시만 비운다.
                    if (ach.ready) ach.points.rebuildAll() else ach.points.invalidate()
                    ach.toasts.registerAll(ach.registry.all())
                    ach.accepting = true
                    if (ach.ready) announceReady()
                    then()
                }
            }
        }
    }

    /**
     * 외부 플러그인에게 등록하라고 알린다.
     *
     * 리로드 뒤에도 쏜다. 리로드는 등록을 지우지 않지만 다시 등록해도 같은 소유자의 교체라
     * 무해하고, 받는 쪽이 enable 과 리로드를 구별할 필요가 없어진다.
     * 우리보다 늦게 켜지는 플러그인은 `AchievementsApi.isReady()` 로 스스로 확인한다.
     */
    private fun announceReady() {
        runCatching { AchievementsReadyEvent().callEvent() }
    }

    /**
     * 이 플러그인이 띄운 화면을 전부 닫는다.
     *
     * core 가 소유한 화면(공용 확인창)도 잡아야 하므로 클래스가 아니라
     * [kr.inmc.core.gui.Menu.owner] 로 가려낸다.
     */
    private fun closeOpenMenus() {
        for (player in server.onlinePlayers) {
            val holder = player.openInventory.topInventory.holder
            if (holder !is kr.inmc.core.gui.Menu || holder.owner !== ach) continue
            player.closeInventory()
        }
    }

    private companion object {
        const val SOURCE = "achievements"

        /** 처음 한 번 깔아주고, 리로드 때 여기서 직접 읽는 것들. 순서가 곧 구조분해 순서다. */
        val RESOURCES = listOf("config.yml", "messages.yml")

        /** 깔기만 하고 읽기는 각자 [kr.inmc.core.store.YamlFileStore] 가 하는 것들. */
        val COPY_ONLY = listOf("achievements.yml", "regions.yml")
    }
}
