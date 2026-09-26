package com.inmc.achievements.hook

import com.inmc.achievements.Achievements
import com.inmc.achievements.achievement.Category
import com.inmc.achievements.achievement.Condition
import org.bstats.bukkit.Metrics
import org.bstats.charts.SimplePie

/**
 * bStats 집계.
 *
 * **개인 식별 값은 보내지 않는다.** 이름·UUID·업적 내용은 전부 제외하고 "이 기능을 켜 둔
 * 서버가 얼마나 되는가"만 센다. 정확한 개수도 의미가 없어 구간으로 뭉친다.
 *
 * 차트 콜백은 **비메인 스레드에서 돈다.** Bukkit API 를 건드리면 안 되고, 불변 스냅샷과
 * 동시성 컬렉션만 읽는다.
 */
class MetricsHook(private val ach: Achievements) {

    private var metrics: Metrics? = null

    fun start() {
        val instance = Metrics(ach.plugin, PLUGIN_ID)

        instance.addCustomChart(SimplePie("advancement_toasts") { onOff(ach.toasts.isEnabled()) })
        instance.addCustomChart(SimplePie("luckperms") { onOff(ach.luckPerms.isEnabled) })
        instance.addCustomChart(SimplePie("titleforge") { onOff(ach.titles.isEnabled) })
        instance.addCustomChart(SimplePie("economy") { onOff(ach.economy.isEnabled) })

        instance.addCustomChart(SimplePie("achievement_count") { bucket(ach.registry.size) })
        instance.addCustomChart(SimplePie("region_count") { bucket(ach.regions.size) })
        instance.addCustomChart(SimplePie("hidden_count") { bucket(ach.registry.all().count { it.hidden }) })
        instance.addCustomChart(SimplePie("guide_count") { bucket(ach.registry.inCategory(Category.GUIDE).size) })

        // 어느 조건 갈래가 실제로 쓰이는지. 통계 갈래가 안 쓰이면 그 설계가 틀린 것이다.
        instance.addCustomChart(SimplePie("main_condition") { dominantCondition() })

        // 외부 플러그인이 API 를 실제로 쓰는지.
        instance.addCustomChart(SimplePie("custom_evaluators") { bucket(ach.custom.conditionKinds().size) })

        metrics = instance
    }

    fun stop() {
        metrics?.shutdown()
        metrics = null
    }

    private fun dominantCondition(): String {
        val all = ach.registry.all()
        if (all.isEmpty()) return "none"
        val counts = all.groupingBy { achievement ->
            when (achievement.condition) {
                is Condition.Stat -> "statistic"
                is Condition.Signal -> "signal"
                is Condition.Custom -> "custom"
                is Condition.State -> "state"
                null -> "none"
            }
        }.eachCount()
        return counts.maxByOrNull { it.value }?.key ?: "none"
    }

    private fun onOff(value: Boolean): String = if (value) "on" else "off"

    private fun bucket(value: Int): String = when {
        value <= 0 -> "0"
        value <= 5 -> "1-5"
        value <= 20 -> "6-20"
        value <= 50 -> "21-50"
        value <= 200 -> "51-200"
        else -> "200+"
    }

    private companion object {
        /**
         * 경고: **배포 전에 bStats 에 등록하고 이 값을 바꿔야 한다.**
         * 0 이면 집계가 남의 것과 섞이거나 버려진다.
         */
        const val PLUGIN_ID = 0
    }
}
