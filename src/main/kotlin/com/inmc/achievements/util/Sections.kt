package com.inmc.achievements.util

import org.bukkit.configuration.ConfigurationSection

/**
 * 읽지 못한 정의를 **파일에서 지우지 않기** 위한 도구.
 *
 * `YamlFileStore` 는 저장할 때 빈 YAML 에 지금 들고 있는 것만 적는다. 그래서 적재 때
 * 거부한 항목(오타·순환·중복)은 **다음 저장에서 파일째 사라진다.** 관리자가 손으로 고치다
 * 틀린 정의가 GUI 에서 아무거나 하나 고치는 순간 영구히 없어지는 것이다.
 *
 * 거부한 섹션을 들고 있다가 저장할 때 **글자 그대로** 되써 넣는다. 고쳐서 리로드하면 그때
 * 정상으로 읽힌다.
 */
object Sections {

    /** [from] 의 내용을 [to] 에 깊게 옮긴다. */
    fun copy(from: ConfigurationSection, to: ConfigurationSection) {
        for (key in from.getKeys(false)) {
            val value = from.get(key)
            if (value is ConfigurationSection) copy(value, to.createSection(key))
            else to.set(key, value)
        }
    }
}
