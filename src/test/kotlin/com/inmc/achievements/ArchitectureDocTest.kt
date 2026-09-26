package com.inmc.achievements

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ARCHITECTURE.md` 가 거짓이 되는 것을 막는다.
 *
 * 그 문서의 목적은 **코드를 다시 읽지 않아도 되게 하는 것**이고, 그래서 거짓이 되면
 * 없는 것보다 나쁘다 — 읽은 사람이 다 봤다고 믿고 코드를 안 본다.
 *
 * **시그니처까지는 검사하지 않는다.** 유지비가 이득을 넘는다. 여기서 지키는 것은
 * "빠뜨리기 쉽고 빠뜨리면 바로 틀리는 것" 뿐이다 — 모듈 목록과 파일 경로.
 */
class ArchitectureDocTest {

    private val root = File("..")

    private val doc: String by lazy {
        val file = File(root, "ARCHITECTURE.md")
        assertTrue(file.exists(), "ARCHITECTURE.md 가 워크스페이스 루트에 없습니다")
        file.readText()
    }

    private val settings: String by lazy {
        File(root, "settings.gradle.kts").readText()
    }

    @Test
    fun `모듈 목록이 settings 와 정확히 일치한다`() {
        // include("a", "b", …) 에서 이름을 뽑는다.
        val declared = Regex("""include\(([^)]*)\)""").find(settings)
            ?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotBlank() }
            ?.toSortedSet()
            ?: sortedSetOf()

        assertTrue(declared.isNotEmpty(), "settings.gradle.kts 를 읽지 못했습니다")

        val documented = Regex("""\|\s*`:([a-z]+)`\s*\|""").findAll(doc)
            .map { it.groupValues[1] }
            .toSortedSet()

        assertEquals(
            declared,
            documented,
            "문서에만: ${documented - declared} / settings 에만: ${declared - documented}",
        )
    }

    @Test
    fun `문서가 가리키는 폴더가 실재한다`() {
        // 표의 두 번째 칸이 폴더다. 이름을 바꾸고 문서를 안 고치는 것이 가장 흔한 드리프트다.
        val folders = Regex("""\|\s*`([^`]+/)`\s*\|""").findAll(doc)
            .map { it.groupValues[1].trimEnd('/') }
            .filter { !it.contains("…") }
            .toSet()

        assertTrue(folders.isNotEmpty(), "문서에서 폴더 표를 찾지 못했습니다")
        for (folder in folders) {
            assertTrue(File(root, folder).isDirectory, "'$folder' 폴더가 없습니다")
        }
    }

    @Test
    fun `유지 규칙이 루트 CLAUDE_md 에 실려 있다`() {
        // 규칙이 문서 안에만 있으면 아무도 안 읽는다. 작업 지침에 있어야 한다.
        val claude = File(root, "CLAUDE.md")
        assertTrue(claude.exists())
        assertTrue(
            claude.readText().contains("ARCHITECTURE.md"),
            "루트 CLAUDE.md 에 ARCHITECTURE.md 유지 규칙이 없습니다",
        )
    }

    @Test
    fun `문서가 주장하는 core 파일이 실재한다`() {
        // 백틱 안의 경로 중 실제 경로 모양인 것만 고른다.
        val paths = Regex("""`(inmc-core/src/[^`]+)`""").findAll(doc)
            .map { it.groupValues[1] }
            .toSet()

        for (path in paths) {
            assertTrue(File(root, path).exists(), "'$path' 가 없습니다")
        }
    }
}
