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
