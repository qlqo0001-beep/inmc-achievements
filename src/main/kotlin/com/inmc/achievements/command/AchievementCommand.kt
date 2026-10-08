package com.inmc.achievements.command

import com.inmc.achievements.Achievements
import com.inmc.achievements.AchievementsPlugin
import com.inmc.achievements.claim.UnitState
import com.inmc.achievements.gui.AdminListMenu
import com.inmc.achievements.gui.BrowseMenu
import com.inmc.achievements.gui.GuideMenu
import com.inmc.achievements.gui.StatsMenu
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * `/업적` 한 트리.
 *
 * 한글 인자는 전부 [StringArgumentType.greedyString] 이다 — Brigadier 의 `word()`/`string()`
 * 은 한글 첫 글자에서 멈춘다. greedy 는 마지막 인자여야만 해서 이름을 맨 뒤로 민다.
 */
class AchievementCommand(private val ach: Achievements) {

    fun register(plugin: JavaPlugin) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(tree().build(), "INMC 업적", listOf("achievements", "도전과제"))
        }
    }

    private val achievementIds = SuggestionProvider<CommandSourceStack> { _, builder ->
        ach.registry.all().map { it.id }
            .filter { it.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private val players = SuggestionProvider<CommandSourceStack> { _, builder ->
        Bukkit.getOnlinePlayers()
            .filter { it.name.startsWith(builder.remainingLowerCase, ignoreCase = true) }
            .forEach { builder.suggest(it.name) }
        builder.buildFuture()
    }

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("업적")
            .executes { context ->
                val player = asPlayer(context.source) ?: return@executes 0
                if (!guardReady(player)) return@executes 0
                BrowseMenu(ach, player).open(player)
                1
            }
            .then(
                Commands.literal("길라잡이").executes { context ->
                    val player = asPlayer(context.source) ?: return@executes 0
                    if (!guardReady(player)) return@executes 0
                    GuideMenu(ach, player).open(player)
                    1
                },
            )
            .then(
                Commands.literal("통계")
                    .executes { context ->
                        val player = asPlayer(context.source) ?: return@executes 0
                        if (!guardReady(player)) return@executes 0
                        StatsMenu(ach, player, player.uniqueId, player.name).open(player)
                        1
                    }
                    .then(
                        Commands.argument("대상", StringArgumentType.word())
                            .suggests(players)
                            .executes { context ->
                                val viewer = asPlayer(context.source) ?: return@executes 0
                                if (!guardReady(viewer)) return@executes 0
                                val name = StringArgumentType.getString(context, "대상")
                                val target = Bukkit.getOfflinePlayer(name)
                                if (target.name == null) {
                                    ach.tell(viewer, "player-not-found")
                                    return@executes 0
                                }
                                StatsMenu(ach, viewer, target.uniqueId, name).open(viewer)
                                1
                            },
                    ),
            )
            .then(
                Commands.literal("우편함").executes { context ->
                    val player = asPlayer(context.source) ?: return@executes 0
                    ach.mailbox.claimAll(player)
                    1
                },
            )
            .then(admin())

    private fun admin(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("관리")
            .requires { it.sender.hasPermission(PERMISSION) }
            .executes { context ->
                val player = asPlayer(context.source) ?: return@executes 0
                if (!guardReady(player)) return@executes 0
                AdminListMenu(ach, player).open(player)
                1
            }
            .then(
                Commands.literal("리로드").executes { context ->
                    val sender = context.source.sender
                    (ach.plugin as? AchievementsPlugin)?.reload {
                        ach.tell(sender, "admin-reloaded", ach.ph().count(ach.registry.size.toLong()))
                    }
                    1
                },
            )
            .then(
                // 그 사람 기준의 업적 목록(진행도·단계·히든까지) — "왜 안 오르지?"를 그 사람 화면 없이 본다(2026-10-08).
                Commands.literal("보기").then(
                    Commands.argument("대상", StringArgumentType.word())
                        .suggests(players)
                        .executes { context ->
                            val viewer = asPlayer(context.source) ?: return@executes 0
                            if (!guardReady(viewer)) return@executes 0
                            val name = StringArgumentType.getString(context, "대상")
                            val target = Bukkit.getPlayerExact(name) ?: Bukkit.getOfflinePlayerIfCached(name)
                            if (target == null) {
                                ach.tell(viewer, "player-not-found")
                                return@executes 0
                            }
                            BrowseMenu(ach, viewer, subject = target.uniqueId, subjectName = target.name ?: name).open(viewer)
                            1
                        },
                ),
            )
            .then(
                Commands.literal("미지급").executes { context ->
                    unpaid(context.source.sender)
                    1
                },
            )
            .then(
                Commands.literal("재계산").executes { context ->
                    val sender = context.source.sender
                    ach.tell(sender, "admin-rebuilding")
                    val touched = ach.points.rebuildAll()
                    ach.tell(sender, "admin-rebuilt", ach.ph().count(touched.toLong()))
                    1
                },
            )
            .then(
                Commands.literal("지급")
                    .then(
                        Commands.argument("대상", StringArgumentType.word())
                            .suggests(players)
                            .then(
                                Commands.argument("업적", StringArgumentType.greedyString())
                                    .suggests(achievementIds)
                                    .executes { context -> grant(context.source.sender, context) },
                            ),
                    ),
            )
            .then(
                Commands.literal("회수")
                    .then(
                        Commands.argument("대상", StringArgumentType.word())
                            .suggests(players)
                            .then(
                                Commands.argument("업적", StringArgumentType.greedyString())
                                    .suggests(achievementIds)
                                    .executes { context -> revoke(context.source.sender, context) },
                            ),
                    ),
            )
            .then(
                // 서버 안 자동 검증(2026-10-08) — 신호 → 진행 → 완료 → 기록 → 초기화 · 지급/회수 · 토스트 · 타이틀포지 · 화면.
                Commands.literal("검증").executes { context ->
                    val player = asPlayer(context.source) ?: return@executes 0
                    com.inmc.achievements.verify.Verifier(ach).run(player)
                    1
                },
            )
            .then(
                // 관리자 시험 도구(2026-10-08) — 연출(토스트·타이틀·소리·폭죽·입자)을 달성 없이 나에게. 공지 문구는 나에게만.
                Commands.literal("연출").then(
                    Commands.argument("업적", StringArgumentType.greedyString())
                        .suggests(achievementIds)
                        .executes { context ->
                            val player = asPlayer(context.source) ?: return@executes 0
                            val achievement = ach.registry.find(StringArgumentType.getString(context, "업적"))
                            if (achievement == null) {
                                ach.tell(player, "not-found")
                                return@executes 0
                            }
                            ach.celebrations.preview(player, achievement)
                            player.sendMessage(kr.inmc.core.util.Text.render("<gray>'${achievement.display}' 의 연출을 미리 보여 줬습니다 — 기록·보상은 없습니다.</gray>"))
                            1
                        },
                ),
            )
            .then(
                Commands.literal("초기화")
                    .then(
                        Commands.argument("대상", StringArgumentType.word())
                            .suggests(players)
                            .executes { context -> reset(context.source.sender, context, null) }
                            .then(
                                Commands.argument("업적", StringArgumentType.greedyString())
                                    .suggests(achievementIds)
                                    .executes { context ->
                                        reset(
                                            context.source.sender,
                                            context,
                                            StringArgumentType.getString(context, "업적"),
                                        )
                                    },
                            ),
                    ),
            )
            .then(signalTree())

    // --- 동작 -----------------------------------------------------------------------

    private fun grant(
        sender: CommandSender,
        context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
    ): Int {
        val target = Bukkit.getOfflinePlayer(StringArgumentType.getString(context, "대상"))
        if (target.name == null) {
            ach.tell(sender, "player-not-found")
            return 0
        }
        val achievement = ach.registry.find(StringArgumentType.getString(context, "업적"))
        if (achievement == null) {
            ach.tell(sender, "not-found")
            return 0
        }
        ach.service.grant(target.uniqueId, achievement, null)
        ach.tell(
            sender,
            "admin-granted",
            ach.ph().player(target.name.orEmpty()).achievement(achievement.display),
        )
        return 1
    }

    private fun revoke(
        sender: CommandSender,
        context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
    ): Int {
        val target = Bukkit.getOfflinePlayer(StringArgumentType.getString(context, "대상"))
        if (target.name == null) {
            ach.tell(sender, "player-not-found")
            return 0
        }
        val achievement = ach.registry.find(StringArgumentType.getString(context, "업적"))
        if (achievement == null) {
            ach.tell(sender, "not-found")
            return 0
        }
        ach.service.revoke(target.uniqueId, achievement)
        ach.tell(
            sender,
            "admin-revoked",
            ach.ph().player(target.name.orEmpty()).achievement(achievement.display),
        )
        return 1
    }

    /**
     * `/업적 관리 초기화 <대상> [업적]`. 업적 인자가 없으면 그 사람 전체.
     *
     * 되돌릴 수 없으므로 결과(지운 개수)를 반드시 보고한다. 아무것도 없으면
     * 그 취지로 말한다 — 오타로 다른 사람을 친 줄 모르고 지나가면 안 된다.
     */
    private fun reset(
        sender: CommandSender,
        context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>,
        rawAchievement: String?,
    ): Int {
        val target = Bukkit.getOfflinePlayer(StringArgumentType.getString(context, "대상"))
        if (target.name == null) {
            ach.tell(sender, "player-not-found")
            return 0
        }
        val achievement = rawAchievement?.let { ach.registry.find(it) }
        if (rawAchievement != null && achievement == null) {
            ach.tell(sender, "not-found")
            return 0
        }
        val summary = ach.service.reset(target.uniqueId, achievement?.uid)
        val name = target.name.orEmpty()
        if (summary.titles > 0) ach.tell(sender, "admin-reset-titles", ach.ph().player(name).count(summary.titles.toLong()))
        if (summary.tiers == 0 && summary.progress == 0 && summary.firsts == 0 &&
            summary.mailbox == 0 && summary.queued == 0
        ) {
            ach.tell(sender, "admin-reset-empty", ach.ph().player(name))
            return 1
        }
        if (achievement != null) {
            ach.tell(
                sender,
                "admin-reset-one",
                ach.ph().player(name).achievement(achievement.display)
                    .count(summary.tiers.toLong()),
            )
        } else {
            ach.tell(
                sender,
                "admin-reset",
                ach.ph().player(name).count(summary.tiers.toLong()),
            )
        }
        return 1
    }

    /**
     * 지급 여부를 **모르는** 것만 보여준다.
     *
     * `DEFERRED`(일부러 미룬 오프라인 칭호)는 여기 안 나온다 — 섞으면 평소에도 목록이 가득
     * 차서 진짜 이상한 것이 묻힌다.
     */
    private fun unpaid(sender: CommandSender) {
        var total = 0
        for (playerId in ach.claims.knownPlayers()) {
            for (claim in ach.claims.uncertain(playerId)) {
                val name = Bukkit.getOfflinePlayer(playerId).name ?: playerId.toString()
                val achievement = ach.registry.byUid(claim.uid)?.display ?: claim.uid
                for (unit in claim.units.filter { it.state == UnitState.PENDING }) {
                    sender.sendMessage(
                        kr.inmc.core.util.Text.renderFlat(
                            "<gray>$name</gray> <dark_gray>|</dark_gray> <white>$achievement</white> " +
                                "<dark_gray>|</dark_gray> <yellow>${unit.describe()}</yellow>",
                        ),
                    )
                    total++
                }
            }
        }
        ach.tell(
            sender,
            if (total == 0) "admin-unpaid-none" else "admin-unpaid",
            ach.ph().count(total.toLong()),
        )
    }

    // --- 신호 직접 쏘기 (관리자 시험 도구, 2026-10-08) -------------------------------------------------------

    private val signalSources = SuggestionProvider<CommandSourceStack> { _, builder ->
        kr.inmc.core.event.SignalCatalog.all().map { it.source }.distinct().forEach(builder::suggest)
        builder.buildFuture()
    }

    private val signalTypes = SuggestionProvider<CommandSourceStack> { context, builder ->
        val source = StringArgumentType.getString(context, "출처")
        kr.inmc.core.event.SignalCatalog.all().filter { it.source == source }.forEach { builder.suggest(it.type) }
        builder.buildFuture()
    }

    private val signalRest = SuggestionProvider<CommandSourceStack> { context, builder ->
        builder.suggest("-")
        val entry = kr.inmc.core.event.SignalCatalog.get(StringArgumentType.getString(context, "출처"), StringArgumentType.getString(context, "종류"))
        entry?.subjects?.invoke()?.forEach { (id, _) -> builder.suggest(id) }
        builder.buildFuture()
    }

    /**
     * `/업적 관리 신호 <출처> <종류> [대상|-] [수] [키=값 …]` — 그 신호를 **쏜 사람 자신에게** 쏜다. 낚시·디스코드·상점처럼 다른 플러그인 사건으로
     * 세는 업적을 그 플러그인을 돌리지 않고 확인한다. 진짜 신호와 같은 길(core `InmcSignalEvent`)이라 업적 쪽 처리가 그대로 돈다.
     * 대상 뒤는 통째로 받아 나눈다 — Brigadier word 인자는 `minecraft:dirt` 의 `:`·한글을 못 받는다.
     */
    private fun signalTree(): LiteralArgumentBuilder<CommandSourceStack> {
        fun run(context: com.mojang.brigadier.context.CommandContext<CommandSourceStack>, rest: String): Int {
            val player = asPlayer(context.source) ?: return 0
            if (!guardReady(player)) return 0
            val source = StringArgumentType.getString(context, "출처")
            val type = StringArgumentType.getString(context, "종류")
            val tokens = rest.trim().split(' ').filter { it.isNotEmpty() }
            val subject = tokens.getOrNull(0)?.takeIf { it != "-" }.orEmpty()
            val amount = tokens.getOrNull(1)?.toLongOrNull()?.coerceIn(1, 1_000_000) ?: 1L
            val values = tokens.drop(2).mapNotNull { pair ->
                pair.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] }
            }.toMap()
            kr.inmc.core.event.InmcSignalEvent.fire(source, type, player.uniqueId, subject, amount, player) { values }
            val shown = "$source/$type" + (if (subject.isEmpty()) "" else " · $subject") +
                (if (values.isEmpty()) "" else " · " + values.entries.joinToString(" ") { "${it.key}=${it.value}" })
            ach.tell(player, "admin-signal-sent", ach.ph().reason(shown).count(amount))
            return 1
        }
        return Commands.literal("신호")
            .executes { context -> asPlayer(context.source)?.let { ach.tell(it, "admin-signal-usage") }; 0 }
            .then(
                Commands.argument("출처", StringArgumentType.word()).suggests(signalSources).then(
                    Commands.argument("종류", StringArgumentType.word()).suggests(signalTypes)
                        .executes { run(it, "") }
                        .then(
                            Commands.argument("나머지", StringArgumentType.greedyString()).suggests(signalRest)
                                .executes { run(it, StringArgumentType.getString(it, "나머지")) },
                        ),
                ),
            )
    }

    private fun asPlayer(source: CommandSourceStack): Player? {
        val sender = source.sender
        if (sender is Player) return sender
        ach.tell(sender, "player-only")
        return null
    }

    private fun guardReady(player: Player): Boolean {
        if (ach.ready) return true
        ach.tell(player, "not-ready")
        return false
    }

    private companion object {
        const val PERMISSION = "inachievements.admin"
    }
}
