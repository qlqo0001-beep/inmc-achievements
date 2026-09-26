package com.inmc.achievements.progress

import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * 외부 플러그인이 등록한 조건·발견 평가기.
 *
 * ## 소유자를 받는 이유
 *
 * `SignalCatalog` 은 **발행자가 자기 등록을 소유**하므로 자기 enable 때 다시 꽂으면 된다.
 * 여기는 반대다 — 목록은 우리 메모리에 있는데 **항목의 주인은 외부 플러그인**이다.
 * 그래서 소유자를 알고 있어야 그 플러그인이 내려갈 때 정확히 그 몫만 뺄 수 있다.
 * 람다는 그 플러그인의 객체와 **클래스로더를 붙들고 있다.**
 *
 * ## 같은 `kind` 를 다른 플러그인이 등록하면 거부한다
 *
 * 교체를 허용하면 남의 플러그인이 조용히 덮는다. 같은 소유자면 교체고, 다른 소유자면
 * 거부하고 로그에 둘 다 적는다.
 */
class CustomEvaluators(private val logger: Logger) {

    private class Registration<T>(val owner: String, val evaluator: T)

    private val conditions = ConcurrentHashMap<String, Registration<(UUID, String) -> Long>>()
    private val discoveries = ConcurrentHashMap<String, Registration<(UUID, String) -> Boolean>>()

    // --- 등록 -----------------------------------------------------------------------

    fun registerCondition(owner: Plugin, kind: String, evaluator: (UUID, String) -> Long): Boolean =
        put(conditions, owner, kind, evaluator, "조건")

    fun registerDiscovery(owner: Plugin, kind: String, evaluator: (UUID, String) -> Boolean): Boolean =
        put(discoveries, owner, kind, evaluator, "발견 조건")

    private fun <T> put(
        into: ConcurrentHashMap<String, Registration<T>>,
        owner: Plugin,
        kind: String,
        evaluator: T,
        what: String,
    ): Boolean {
        val key = kind.trim().lowercase()
        if (key.isBlank()) return false
        val existing = into[key]
        if (existing != null && existing.owner != owner.name) {
            logger.warning(
                "$what '$key' 는 이미 ${existing.owner} 가 등록했습니다 - ${owner.name} 의 등록을 거부합니다",
            )
            return false
        }
        into[key] = Registration(owner.name, evaluator)
        return true
    }

    /** 내려갈 때. 그 플러그인 몫만 전부 뺀다. */
    fun unregisterAll(owner: Plugin) {
        val name = owner.name
        conditions.entries.removeIf { it.value.owner == name }
        discoveries.entries.removeIf { it.value.owner == name }
    }

    fun clear() {
        conditions.clear()
        discoveries.clear()
    }

    // --- 조회 -----------------------------------------------------------------------

    /** 이 종류를 아는 플러그인이 있는가. 없으면 그 조건은 `UNAVAILABLE` 이다. */
    fun knows(kind: String): Boolean = conditions.containsKey(kind.trim().lowercase())

    fun knowsDiscovery(kind: String): Boolean = discoveries.containsKey(kind.trim().lowercase())

    fun conditionKinds(): List<String> = conditions.keys.sorted()

    fun discoveryKinds(): List<String> = discoveries.keys.sorted()

    /**
     * 평가기에게 묻는다. 등록이 없으면 null.
     *
     * **남의 코드라 예외를 감싼다** — 한 플러그인의 버그가 우리 스윕을 멈추면 안 된다.
     */
    fun evaluate(kind: String, playerId: UUID, value: String): Long? {
        val registration = conditions[kind.trim().lowercase()] ?: return null
        return runCatching { registration.evaluator(playerId, value) }
            .onFailure { logger.warning("조건 평가기 '$kind'(${registration.owner}) 가 실패했습니다: " + it.message) }
            .getOrNull()
    }

    fun evaluateDiscovery(kind: String, playerId: UUID, value: String): Boolean? {
        val registration = discoveries[kind.trim().lowercase()] ?: return null
        return runCatching { registration.evaluator(playerId, value) }
            .onFailure { logger.warning("발견 평가기 '$kind'(${registration.owner}) 가 실패했습니다: " + it.message) }
            .getOrNull()
    }
}
