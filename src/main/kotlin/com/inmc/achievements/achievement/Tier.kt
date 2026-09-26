package com.inmc.achievements.achievement

import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.store.DefinitionKey
import org.bukkit.configuration.ConfigurationSection

/**
 * 같은 업적의 한 단계. `나무꾼 I` · `나무꾼 II` · `나무꾼 III`.
 *
 * **단계가 업적 셋이 아니라 업적 하나인 이유**는 카운터다. 셋으로 나누면 같은 신호에 카운터
 * 셋이 각자 늘어나고, 한 번만 플러시를 놓쳐도 서로 어긋난다. 진행도는 하나여야 한다.
 *
 * [id] 는 **번호가 아니다.** I(100)와 III(1000) 사이에 II 를 끼워 넣으면 번호 기준 기록은
 * 옛 III 를 새 II 로 가리키고, 그 사람은 1000 짜리 보상을 쥔 채 "아직 III 가 아님"으로
 * 표시된다. 조용히 어긋나고 되돌릴 수 없다. 그래서 생성 때 한 번 부여하고 안 바꾼다.
 */
data class Tier(
    val id: String,
    /** 이 단계를 여는 누적 수치. 목록 안에서 **순증가**여야 한다. */
    val threshold: Long,
    /** 단계 이름. 비면 업적 이름에 로마 숫자를 붙여 보여준다. */
    val label: String = "",
    /** 업적 점수. 랭크는 이 합계로 오른다. */
    val points: Int = 0,
    /** 지급물. core 의 것을 그대로 쓴다 — 일곱 번째 보상 모양을 만들지 않는다. */
    val rewards: RewardBundle = RewardBundle(),
    /** 같이 주는 칭호. `타입:아이디` (예: `TITLE:벌목왕`). 비면 없음. */
    val title: String = "",
    /** 이 단계 전용 연출. 없으면 업적의 것을 쓴다. */
    val celebration: Celebration? = null,
    /** 서버 최초 달성자에게만 따로. */
    val firstOnServer: FirstClear? = null,
) {

    fun save(section: ConfigurationSection) {
        section.set("threshold", threshold)
        if (label.isNotBlank()) section.set("label", label)
        if (points != 0) section.set("points", points)
        if (title.isNotBlank()) section.set("title", title)
        rewards.save(section.createSection("rewards"))
        celebration?.save(section.createSection("celebration"))
        firstOnServer?.save(section.createSection("first-clear"))
    }

    companion object {

        /** 한 업적이 가질 수 있는 단계 수. 화면이 감당하는 선이다. */
        const val MAX = 10

        fun load(id: String, section: ConfigurationSection): Tier? {
            if (!DefinitionKey.isValid(id)) return null
            return Tier(
                id = id.lowercase(),
                threshold = section.getLong("threshold", 1L).coerceAtLeast(1L),
                label = section.getString("label").orEmpty(),
                points = section.getInt("points", 0),
                rewards = RewardBundle.load(section.getConfigurationSection("rewards")),
                title = section.getString("title").orEmpty(),
                celebration = Celebration.load(section.getConfigurationSection("celebration")),
                firstOnServer = FirstClear.load(section.getConfigurationSection("first-clear")),
            )
        }

        /**
         * 임계값이 순증가인지.
         *
         * 적재할 때는 **임계값 순으로 정렬한 뒤** 이걸 본다. 단계의 정체는 번호가 아니라
         * [Tier.id] 라서 파일에 적힌 순서는 뜻이 없다 — 정렬해도 기록이 어긋나지 않는다.
         * 정렬로 풀리지 않는 것은 **같은 임계값 둘**뿐이고, 그건 어느 쪽이 먼저인지 정해지지
         * 않으므로 거부한다.
         */
        fun isAscending(tiers: List<Tier>): Boolean {
            for (index in 1 until tiers.size) {
                if (tiers[index].threshold <= tiers[index - 1].threshold) return false
            }
            return true
        }
    }
}

/**
 * 서버 최초 달성자에게만 주는 것.
 *
 * 기록은 단계마다 남기지만 **연출은 한 번뿐이다** — 한 사람이 I·II·III 를 동시에 넘으면서
 * 셋 다 최초라면 공지가 세 번 나가면 안 된다.
 */
data class FirstClear(
    val rewards: RewardBundle = RewardBundle(),
    val celebration: Celebration? = null,
) {
    fun save(section: ConfigurationSection) {
        rewards.save(section.createSection("rewards"))
        celebration?.save(section.createSection("celebration"))
    }

    companion object {
        fun load(section: ConfigurationSection?): FirstClear? {
            if (section == null) return null
            return FirstClear(
                rewards = RewardBundle.load(section.getConfigurationSection("rewards")),
                celebration = Celebration.load(section.getConfigurationSection("celebration")),
            )
        }
    }
}
