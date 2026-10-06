package com.inmc.achievements.progress

import kr.inmc.core.config.ConfigService
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * 서버 최초 달성 기록. **서버 전역이라 플레이어 파일에 둘 수 없다.**
 *
 * 전원의 기록을 훑어 "누가 처음인가" 를 묻는 것은 O(n) 이고, 그걸 완료마다 할 수는 없다.
 * 여기 한 파일에 `업적uid/단계id → 누가·언제` 로 적어 두면 O(1) 이다.
 *
 * ## 경합
 *
 * 완료 큐는 **메인 스레드에서 순차 처리**된다(보상이 Bukkit API 를 만지므로 그럴 수밖에
 * 없다). 그래서 CAS 같은 동시성 장치가 필요 없다 — 확인하고, 없으면 저장하고, **그 저장
 * 결과를 근거로** 최초로 다룬다.
 *
 * **파일이 공지보다 먼저다.** 반대로 하면 공지가 나간 뒤 저장이 실패했을 때 두 사람이
 * 각자 "내가 최초" 라고 듣는다. 이건 잃으면 사람들이 다투는 유일한 데이터라 쓰는 즉시
 * 원자적으로 확정한다.
 */
class FirstClears(private val io: ConfigService, private val logger: Logger) {

    private class Record(val playerId: UUID, val name: String, val at: Long)

    private val records = ConcurrentHashMap<String, Record>()

    @Volatile
    var ready: Boolean = false
        private set

    private val file: File get() = io.file(FILE)

    fun isUnclaimed(uid: String, tierId: String): Boolean = !records.containsKey(key(uid, tierId))

    fun holderOf(uid: String, tierId: String): UUID? = records[key(uid, tierId)]?.playerId

    fun nameOf(uid: String, tierId: String): String? = records[key(uid, tierId)]?.name

    fun size(): Int = records.size

    /**
     * 최초로 등록해 보고 **실제로 최초였는지** 돌려준다.
     *
     * 저장이 실패하면 false 다 — 확정하지 못한 것을 최초라고 알리지 않는다.
     */
    fun claim(uid: String, tierId: String, playerId: UUID, name: String, now: Long): Boolean {
        val key = key(uid, tierId)
        if (records.containsKey(key)) return false
        records[key] = Record(playerId, name, now)
        if (write()) return true
        // 저장 실패. 기록을 되돌려 다음 사람에게 기회를 남긴다.
        records.remove(key)
        return false
    }

    /** 업적이 지워졌을 때. */
    fun forget(uid: String) {
        val prefix = "$uid/"
        if (records.keys.removeIf { it.startsWith(prefix) }) write()
    }

    /**
     * 이 사람의 서버 최초 기록을 푼다. 관리자 초기화용 — 푼 개수를 돌려준다.
     *
     * uid 가 null 이면 그 사람 전체. 최초 타이틀을 잃은 자리는 다음 달성자가 차지한다.
     */
    fun release(playerId: UUID, uid: String?): Int {
        val prefix = if (uid != null) "$uid/" else null
        var released = 0
        val iterator = records.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if ((prefix == null || entry.key.startsWith(prefix)) && entry.value.playerId == playerId) {
                iterator.remove()
                released++
            }
        }
        if (released > 0) write()
        return released
    }

    // --- 영속화 -------------------------------------------------------------------

    fun load(then: () -> Unit) {
        io.async({ YamlConfiguration.loadConfiguration(file) }) { config ->
            records.clear()
            for (key in config.getKeys(false)) {
                val section = config.getConfigurationSection(key) ?: continue
                val id = runCatching { UUID.fromString(section.getString("player").orEmpty()) }
                    .getOrNull() ?: continue
                records[key] = Record(
                    playerId = id,
                    name = section.getString("name").orEmpty(),
                    at = section.getLong("at", 0L),
                )
            }
            ready = true
            then()
        }
    }

    /**
     * 통째로 다시 쓴다. **임시 파일 → 원자적 교체.**
     *
     * `ConfigService.save` 는 temp+rename 이 없어 쓰는 도중에 죽으면 잘린 YAML 을 남긴다.
     * 여기서만은 그걸 허용할 수 없다.
     */
    private fun write(): Boolean {
        val config = YamlConfiguration()
        config.options().setHeader(listOf(HEADER))
        for ((key, record) in records) {
            val section = config.createSection(key)
            section.set("player", record.playerId.toString())
            section.set("name", record.name)
            section.set("at", record.at)
        }
        val temp = File(file.parentFile, "$FILE.tmp")
        return runCatching {
            file.parentFile?.mkdirs()
            temp.writeText(config.saveToString(), Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrElse {
            logger.severe("최초 달성 기록을 저장하지 못했습니다: " + it.message)
            temp.delete()
            false
        }
    }

    /**
     * 키에 `/` 를 쓴다. `DefinitionKey` 가 점은 막지만 슬래시는 허용하고,
     * `YamlConfiguration` 은 점만 경로로 다루므로 안전하다.
     */
    private fun key(uid: String, tierId: String): String = "$uid/$tierId"

    private companion object {
        const val FILE = "first-clears.yml"
        const val HEADER = "서버 최초 달성 기록. 손으로 고치지 마세요."
    }
}
