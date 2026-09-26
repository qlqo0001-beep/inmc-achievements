package com.inmc.achievements.achievement

/**
 * 적재할 때 거부할 것들. **전부 순수 함수다** — 서버 없이 검증된다.
 *
 * 레지스트리 안에 두면 테스트할 방법이 없고, 그러면 "조용히 깨지는 것을 막는 장치" 자체가
 * 검증되지 않은 채로 남는다. 이 워크스페이스가 같은 이유로 여러 번 당했다.
 */
object Validation {

    /** 왜 거부하는지. 로그에 그대로 나간다. */
    enum class Problem(val describe: String) {
        TIER_ID_DUPLICATE("단계 이름이 중복됩니다"),
        TIER_NOT_ASCENDING("단계 임계값이 순증가가 아닙니다"),
        TIER_TOO_MANY("단계가 ${Tier.MAX}개를 넘습니다"),
        TIER_ON_STATE("이 조건은 단계를 가질 수 없습니다"),
        COMPLETE_ON_DISCOVER_WITH_TIERS("단계가 있어 '발견 즉시 달성'을 쓸 수 없습니다"),
        CYCLE("선행 조건이 순환합니다 — 관련 업적이 전부 영원히 미완료가 됩니다"),
    }

    /** 문제가 없으면 null. */
    fun check(achievement: Achievement): Problem? {
        val tiers = achievement.tiers
        return when {
            tiers.map { it.id }.toSet().size != tiers.size -> Problem.TIER_ID_DUPLICATE
            !Tier.isAscending(tiers) -> Problem.TIER_NOT_ASCENDING
            tiers.size > Tier.MAX -> Problem.TIER_TOO_MANY
            // 참/거짓 조건에 누적 임계값은 뜻이 없다.
            tiers.isNotEmpty() && achievement.condition?.supportsTiers == false -> Problem.TIER_ON_STATE
            achievement.discovery.completeOnDiscover && tiers.isNotEmpty() ->
                Problem.COMPLETE_ON_DISCOVER_WITH_TIERS
            else -> null
        }
    }

    /**
     * 선행 조건 그래프의 순환에 낀 uid 들. `A→B→C→A` 면 셋 다 **영원히 미완료**가 된다.
     *
     * 선행 조건은 업적당 **하나뿐**이라 그래프의 출차수가 1 이하다. 그래서 각 노드에서
     * 사슬을 따라가며 이미 지난 곳을 다시 밟는지만 보면 된다.
     *
     * `next` 는 보지 않는다 — **표시 전용**이라 화면 연결이 돌아도 아무도 막히지 않는다.
     */
    fun findCycles(achievements: Collection<Achievement>): Set<String> {
        val prerequisite = achievements.associate { achievement ->
            achievement.uid to (achievement.condition as? Condition.State)
                ?.takeIf { it.kind == StateKind.ACHIEVEMENT }?.value
        }
        val bad = HashSet<String>()
        for (start in prerequisite.keys) {
            if (start in bad) continue
            val walked = LinkedHashSet<String>()
            var current: String? = start
            while (current != null && walked.add(current)) {
                current = prerequisite[current]
            }
            // 사슬이 끊기지 않고 이미 지난 곳으로 돌아왔다면 그 지점부터가 순환이다.
            if (current != null) bad += walked.dropWhile { it != current }
        }
        return bad
    }
}
