package com.inmc.achievements.scheduler

import com.inmc.achievements.Achievements
import kr.inmc.core.scheduler.TickerBase
import org.bukkit.Bukkit

/**
 * 이 플러그인의 **유일한** 반복 작업.
 *
 * 주기가 셋이라 하나의 티커 안에서 카운터로 나눈다 — 티커를 셋 두면 같은 파일에 대한 쓰기가
 * 겹치고, 그게 core 가 플러시를 자기 티커 하나로 도는 이유다.
 *
 * | 무엇 | 주기 |
 * |---|---|
 * | 완료 큐 드레인 | 매 틱 (상한 있음) |
 * | 통계·상태 스윕 | 5초, 접속자를 [AchievementsConfig.sweepShards] 틱에 나눠 |
 * | 진행도 동기화 | 30초 |
 *
 * ## 스윕이 폴링인 이유
 *
 * `PlayerStatisticIncrementEvent` 는 `WALK_ONE_CM` 이 **이동 틱마다** 올라서, 100명이면
 * 핸들러가 아무 일도 안 해도 초당 2천 건이 디스패치된다. 싼 이벤트로 "다시 봐라" 넛지를
 * 다는 것도 `BlockBreakEvent`·`EntityDeathEvent` 에 리스너를 붙이는 일이라, 통계 갈래를
 * 만든 이유가 없어진다.
 *
 * 곱할 수는 업적 200개가 아니라 **그 사람이 아직 최고 단계를 못 넘은 조건 수**다.
 */
class Ticker(private val ach: Achievements) : TickerBase(ach.plugin) {

    override val periodTicks: Long get() = 1L

    private var ticks = 0L

    override fun ready(): Boolean = ach.ready

    override fun tick(now: Long) {
        ticks++

        // 매 틱. 큐가 비어 있으면 곧바로 돌아온다.
        if (!ach.queue.isEmpty()) {
            step("완료 처리") { ach.service.drain(ach.config.completionsPerTick) }
        }

        val period = ach.config.sweepPeriodTicks
        val shards = ach.config.sweepShards
        // 5초 주기를 다시 shards 등분해, 한 틱에 접속자 전부를 훑지 않는다.
        val shardPeriod = (period / shards).coerceAtLeast(1L)
        if (ticks % shardPeriod == 0L) {
            val shard = ((ticks / shardPeriod) % shards).toInt()
            step("통계 스윕") { sweepShard(shard, shards, now) }
        }

        // 채팅 입력 만료는 초 단위로 본다. 30초 주기에 얹으면 만료가 최대 30초 늦는다.
        if (ticks % PROMPT_PERIOD_TICKS == 0L) {
            step("프롬프트") { ach.prompts.tick(now) }
        }

        if (ticks % ach.config.syncPeriodTicks == 0L) {
            // core 가 자기 티커로 flush 한다. 우리는 넘기기만 한다.
            step("진행도 동기화") { ach.counters.syncTo(ach.players) }
            step("저장") {
                ach.registry.flush()
                ach.regions.flush()
            }
        }
    }

    /**
     * 접속자를 [shards] 등분한 중 하나만 훑는다.
     *
     * UUID 해시로 나누므로 같은 사람은 늘 같은 칸에 들어간다 — 순서가 흔들리면 어떤 사람이
     * 연속으로 두 번 훑히고 다른 사람이 건너뛰어진다.
     */
    private fun sweepShard(shard: Int, shards: Int, now: Long) {
        val players = Bukkit.getOnlinePlayers().filter {
            Math.floorMod(it.uniqueId.hashCode(), shards) == shard
        }
        if (players.isEmpty()) return
        ach.service.sweep(players, now)
    }

    private companion object {
        const val PROMPT_PERIOD_TICKS = 20L
    }
}
