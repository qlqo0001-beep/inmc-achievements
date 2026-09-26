package com.inmc.achievements

import kr.inmc.core.event.SignalCatalog
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 화면 슬롯.
 *
 * **슬롯 충돌은 컴파일러가 못 잡는다.** 겹치면 나중에 그린 버튼만 보이면서 **안 보이는
 * 버튼의 클릭 핸들러가 남아** 엉뚱한 동작을 한다. 그래서 소스에서 상수를 직접 읽는다.
 */
class MenuLayoutTest {

    private val sources: List<File> =
        File("src/main/kotlin/com/inmc/achievements/gui").listFiles()?.toList().orEmpty()

    private val slotPattern = Regex("""const val (SLOT_[A-Z_]+) = (\d+)""")
    private val sizePattern = Regex("""Menu\(ach, (\d+),""")

    @Test
    fun `한 화면 안에서 슬롯이 겹치지 않는다`() {
        for (file in sources) {
            val text = file.readText()
            // 파일 하나에 화면이 여럿이므로 companion 블록 단위로 본다.
            for (block in text.split("private companion object").drop(1)) {
                val slots = slotPattern.findAll(block).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
                val byValue = slots.groupBy { it.second }.filterValues { it.size > 1 }
                assertTrue(
                    byValue.isEmpty(),
                    "${file.name}: 슬롯이 겹칩니다 — " +
                        byValue.values.joinToString { group -> group.joinToString("/") { it.first } },
                )
            }
        }
    }

    @Test
    fun `슬롯을 숫자로 직접 적지 않는다`() {
        // 숫자로 적은 슬롯은 위 겹침 검사를 빠져나간다. 단계 화면이 실제로 `set(22, …)` 와
        // `SLOT_SINGLE = 22` 를 같이 써서, "단계를 가질 수 없습니다" 안내가 덮여 안 보였다.
        val literal = Regex("""\bset\(\s*\d+\s*,""")
        for (file in sources) {
            val hits = file.readLines().withIndex()
                .filter { (_, line) -> literal.containsMatchIn(line) }
                .map { (index, _) -> index + 1 }
            assertTrue(hits.isEmpty(), "${file.name}: 슬롯 숫자를 직접 적었습니다 (줄 $hits)")
        }
    }

    @Test
    fun `27칸 화면이 54칸용 슬롯 상수를 쓰지 않는다`() {
        // `Menu.set` 은 범위 밖 슬롯을 **조용히 무시한다.** 27칸 화면에 Paging.SLOT_CLOSE(53)
        // 을 쓰면 닫기 버튼이 그냥 안 그려진다. 낚시와 커스텀아이템에서 실제로 그랬다.
        for (file in sources) {
            val text = file.readText()
            val sizes = sizePattern.findAll(text).map { it.groupValues[1].toInt() }.toList()
            if (sizes.isEmpty() || sizes.any { it > 27 }) continue
            assertFalse(
                text.contains("Paging.SLOT_"),
                "${file.name}: 27칸 화면인데 Paging 의 슬롯 상수(45~53)를 씁니다",
            )
        }
    }

    @Test
    fun `모든 슬롯이 자기 화면 크기 안에 있다`() {
        for (file in sources) {
            val text = file.readText()
            val maxSize = sizePattern.findAll(text).map { it.groupValues[1].toInt() }.maxOrNull() ?: continue
            for (match in slotPattern.findAll(text)) {
                val value = match.groupValues[2].toInt()
                assertTrue(
                    value < maxSize,
                    "${file.name}: ${match.groupValues[1]} = $value 가 화면(${maxSize}칸) 밖입니다",
                )
            }
        }
    }
}

/**
 * 디스크립터 계약.
 *
 * 다른 5세대 플러그인과 같은 규칙을 지키는지. 어긋나면 적재 순서나 명령어 등록이
 * 조용히 달라진다.
 */
class DescriptorTest {

    private val descriptor: YamlConfiguration =
        YamlConfiguration.loadConfiguration(File("src/main/resources/paper-plugin.yml"))

    @Test
    fun `main 클래스가 실재한다`() {
        val main = descriptor.getString("main")
        assertEquals("com.inmc.achievements.AchievementsPlugin", main)
        Class.forName(main!!)
    }

    @Test
    fun `필수 의존은 inmc-core 하나뿐이다`() {
        val server = descriptor.getConfigurationSection("dependencies.server")!!
        val required = server.getKeys(false).filter {
            server.getBoolean("$it.required", false)
        }

        assertEquals(listOf("inmc-core"), required)
    }

    @Test
    fun `연동은 전부 load OMIT 이다`() {
        // BEFORE 를 걸면 Paper 가 의존성 순환을 보고 우리 간선을 조용히 끊는다.
        val server = descriptor.getConfigurationSection("dependencies.server")!!
        for (name in server.getKeys(false)) {
            if (name == "inmc-core") continue
            assertEquals("OMIT", server.getString("$name.load"), "$name")
        }
    }

    @Test
    fun `api-version 을 손으로 적지 않는다`() {
        // 관례 플러그인이 `inmc { paper }` 에서 유도한다. 손으로 적으면 컴파일 대상과 어긋난다.
        assertEquals("\${apiVersion}", descriptor.getString("api-version"))
    }

    @Test
    fun `commands 절이 없다`() {
        // paper-plugin.yml 에는 commands 절이 없다. Brigadier 로 등록한다.
        assertNull(descriptor.getConfigurationSection("commands"))
    }
}

/**
 * 등록 수명주기.
 *
 * `SignalCatalog` 의 람다는 **그 플러그인의 클래스로더를 붙들고 있다.** 해제가 없으면
 * 리로드마다 옛 람다가 남는다. `CustomItemHook` 의 절반만 베끼면 그렇게 된다.
 */
class RegistrationLifecycleTest {

    @AfterTest
    fun cleanup() {
        SignalCatalog.clear()
    }

    @Test
    fun `같은 출처와 종류를 다시 등록하면 교체된다`() {
        SignalCatalog.clear()
        SignalCatalog.register("fishing", "catch", "물고기", { listOf("a" to "옛것") })
        SignalCatalog.register("fishing", "catch", "물고기", { listOf("b" to "새것") })

        assertEquals(1, SignalCatalog.all().size, "누적되면 안 된다")
        assertEquals("새것", SignalCatalog.get("fishing", "catch")!!.subjects().first().second)
    }

    @Test
    fun `출처 단위로 한 번에 뺀다`() {
        SignalCatalog.clear()
        SignalCatalog.register("fishing", "catch", "물고기", { emptyList() })
        SignalCatalog.register("fishing", "register", "물고기", { emptyList() })
        SignalCatalog.register("urb", "open", "상자", { emptyList() })

        SignalCatalog.unregisterAll("fishing")

        assertEquals(1, SignalCatalog.all().size)
        assertEquals("urb", SignalCatalog.all().first().source)
    }

    @Test
    fun `목록은 열 때마다 읽는다`() {
        // 등록 시점에 목록을 떠서 넘기면 관리자가 새로 만든 물고기가 편집기에 안 나온다.
        SignalCatalog.clear()
        val live = mutableListOf("a" to "하나")
        SignalCatalog.register("fishing", "catch", "물고기", { live.toList() })

        assertEquals(1, SignalCatalog.get("fishing", "catch")!!.subjects().size)
        live += "b" to "둘"
        assertEquals(2, SignalCatalog.get("fishing", "catch")!!.subjects().size)
    }

    @Test
    fun `모르는 값은 판단하되 모르는 출처는 판단하지 않는다`() {
        // 등록이 없으면 null 이다. 빨갛게 칠하면 낚시를 잠시 끈 서버에서 멀쩡한 설정이
        // 전부 오류처럼 보인다.
        SignalCatalog.clear()
        assertNull(SignalCatalog.knowsSubject("fishing", "catch", "tuna"))

        SignalCatalog.register("fishing", "catch", "물고기", { listOf("tuna" to "참치") })
        assertEquals(true, SignalCatalog.knowsSubject("fishing", "catch", "tuna"))
        assertEquals(false, SignalCatalog.knowsSubject("fishing", "catch", "없는물고기"))
    }

    @Test
    fun `대소문자가 달라도 같은 등록이다`() {
        SignalCatalog.clear()
        SignalCatalog.register("Fishing", "CATCH", "물고기", { emptyList() })

        assertEquals(1, SignalCatalog.all().size)
        assertTrue(SignalCatalog.get("fishing", "catch") != null)
    }
}
