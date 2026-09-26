package com.inmc.achievements.config

import kr.inmc.core.reward.RewardSettings
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `config.yml` 의 **불변 스냅샷.**
 *
 * 리로드는 이 객체를 통째로 갈아끼운다. 필드를 하나씩 덮어쓰면 반쯤 바뀐 설정이 읽히는
 * 구간이 생기고, 그 구간에 완료된 업적이 옛 값과 새 값을 섞어 쓴다.
 */
class AchievementsConfig(
    /** 통계 스윕 주기(틱). 5초가 기본. */
    val sweepPeriodTicks: Long,
    /** 접속자를 몇 틱에 나눠 훑을지. 한 틱에 몰면 순간 부하가 튄다. */
    val sweepShards: Int,
    /** 진행도를 공유 저장소에 넘기는 주기(틱). core 의 플러시 주기와 맞춘다. */
    val syncPeriodTicks: Long,
    /**
     * 한 틱에 처리할 완료 수.
     *
     * **2가 기본인 이유**는 완료 하나가 `1 + N` 번의 원자 쓰기를 하기 때문이다. 통계 업적을
     * 새로 만들면 전 서버가 즉시 달성하므로(소급) 상한이 없으면 그 순간 서버가 멈춘다.
     */
    val completionsPerTick: Int,
    /** 바닐라 발전과제 토스트를 쓸지. 꺼도 타이틀·소리·공지는 그대로 돈다. */
    val advancementToasts: Boolean,
    /** 한 월드에 `precise` 지역이 이보다 많으면 경고. 설계 전제가 1~3개다. */
    val preciseRegionWarnAt: Int,
    /** 업적 랭크 임계값. `점수 to 이름`. */
    val rankThresholds: List<Pair<Int, String>>,

    override val mailboxLimit: Int,
    override val mailboxExpireSeconds: Long,
    override val dropWhenInventoryFull: Boolean,
    override val broadcastRewards: Boolean,
    override val seasonArchiveLimit: Int,
) : RewardSettings {

    /** 이 점수로 도달하는 랭크 이름. 없으면 빈 문자열. */
    fun rankOf(points: Int): String =
        rankThresholds.lastOrNull { points >= it.first }?.second.orEmpty()

    companion object {

        fun from(config: YamlConfiguration): AchievementsConfig = AchievementsConfig(
            sweepPeriodTicks = config.getLong("sweep.period-ticks", 100L).coerceIn(20L, 1200L),
            sweepShards = config.getInt("sweep.shards", 5).coerceIn(1, 20),
            syncPeriodTicks = config.getLong("sweep.sync-period-ticks", 600L).coerceIn(100L, 6000L),
            completionsPerTick = config.getInt("sweep.completions-per-tick", 2).coerceIn(1, 20),
            advancementToasts = config.getBoolean("advancement-toasts", true),
            preciseRegionWarnAt = config.getInt("region.precise-warn-at", 32).coerceAtLeast(1),
            rankThresholds = readRanks(config),

            mailboxLimit = config.getInt("rewards.mailbox-limit", 54).coerceAtLeast(1),
            mailboxExpireSeconds = config.getLong("rewards.mailbox-expire-seconds", 0L),
            dropWhenInventoryFull = config.getBoolean("rewards.drop-when-inventory-full", false),
            broadcastRewards = config.getBoolean("rewards.broadcast", false),
            seasonArchiveLimit = config.getInt("rewards.season-archive-limit", 5).coerceAtLeast(1),
        )

        /**
         * 랭크 표. **점수 순으로 정렬해 둔다** — 파일에 뒤섞여 적혀 있어도 [rankOf] 가
         * 마지막으로 넘은 것을 고를 수 있어야 한다.
         */
        private fun readRanks(config: YamlConfiguration): List<Pair<Int, String>> {
            val section = config.getConfigurationSection("ranks") ?: return DEFAULT_RANKS
            val parsed = section.getKeys(false).mapNotNull { key ->
                val points = key.toIntOrNull() ?: return@mapNotNull null
                val name = section.getString(key)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                points to name
            }
            return parsed.sortedBy { it.first }.ifEmpty { DEFAULT_RANKS }
        }

        val DEFAULT_RANKS: List<Pair<Int, String>> = listOf(
            0 to "<gray>견습</gray>",
            50 to "<white>숙련</white>",
            200 to "<green>전문</green>",
            500 to "<aqua>대가</aqua>",
            1000 to "<light_purple>전설</light_purple>",
            2000 to "<gold>불멸</gold>",
        )
    }
}
