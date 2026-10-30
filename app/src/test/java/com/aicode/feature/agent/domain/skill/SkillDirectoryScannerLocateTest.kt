package com.aicode.feature.agent.domain.skill

import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 模拟真机 ROOT 通道语义的 [TestFileAccessProvider]：头部读按 maxBytes 截断返回
 * （对应 ROOT 的 `head -c`），并分别计数「头部读 / 全文读 / 全文批量读」的调用，
 * 用来断言 loadSkill 精准路径的开销量级。
 */
private class CountingFileAccessProvider(
    private val delegate: TestFileAccessProvider = TestFileAccessProvider()
) : com.aicode.feature.workspace.domain.FileAccessProvider by delegate {

    var headReadCalls = 0
    var fullReadCalls = 0
    var batchReadCalls = 0

    override suspend fun readFile(path: String): String {
        fullReadCalls++
        return delegate.readFile(path)
    }

    override suspend fun readFiles(paths: List<String>): Map<String, String> {
        batchReadCalls++
        return delegate.readFiles(paths)
    }

    override suspend fun readFileHeads(paths: List<String>, maxBytes: Int): Map<String, String> {
        headReadCalls++
        val out = LinkedHashMap<String, String>(paths.size)
        for (p in paths) {
            runCatching { delegate.readFile(p) }.onSuccess { out[p] = it.take(maxBytes) }
        }
        return out
    }
}

/**
 * [SkillDirectoryScanner.locateAndRead]（loadSkill 精准加载路径）的行为与开销测试。
 *
 * 背景：旧实现 loadInstructions = listSkills() 全量扫描——ROOT 通道下每个技能一次全文读，
 * 118 个技能时单次 loadSkill 就是十几次 su fork + 全部正文过管道，主线程上直接 ANR/闪退。
 * 新实现只读文件头匹配 frontmatter name，命中后仅读该文件全文。
 */
class SkillDirectoryScannerLocateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = CountingFileAccessProvider()

    private fun writeSkill(dirName: String, content: String): File {
        val dir = File(tempFolder.root, dirName).apply { mkdirs() }
        return File(dir, "SKILL.md").apply { writeText(content) }
    }

    private val root: String get() = tempFolder.root.absolutePath

    private fun locate(name: String): String? =
        runBlocking { SkillDirectoryScanner.locateAndRead(provider, root, name) }

    @Test
    fun locate_matchesFrontmatterNameAndReadsOnlyOneFullFile() {
        // 5 个技能，正文都足够大（8K 头部装不下），确保头部读≠全文读
        repeat(5) { i ->
            writeSkill("skill-$i", "---\nname: skill-$i\ndescription: d$i\n---\n" + "正".repeat(20_000))
        }

        val instructions = locate("skill-3")

        assertTrue(instructions != null && instructions.contains("正"))
        assertEquals("正文应只读命中文件一次全文", 1, provider.fullReadCalls + provider.batchReadCalls)
        assertEquals("头部应一批读完", 1, provider.headReadCalls)
    }

    @Test
    fun locate_matchesByDirNameWhenFrontmatterNameMissing() {
        writeSkill("dir-only-skill", "---\ndescription: 无 name 字段\n---\n正文")

        val instructions = locate("dir-only-skill")

        assertEquals("正文", instructions)
        assertEquals(1, provider.headReadCalls)
    }

    @Test
    fun locate_nameInFrontmatterWinsOverDirectoryName() {
        // 目录名与 frontmatter 名不一致：必须按 frontmatter 名命中（与 scan 语义一致）
        writeSkill("dir-a", "---\nname: real-name\n---\n正文A")

        assertNull(locate("dir-a"))
        assertEquals("正文A", locate("real-name"))
    }

    @Test
    fun locate_caseInsensitive() {
        writeSkill("mixed-case", "---\nname: Mixed-Case\n---\n正文")

        assertEquals("正文", locate("mixed-CASE"))
    }

    @Test
    fun locate_prefersSkillMdOverClaudeMd() {
        val dir = File(tempFolder.root, "dual").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: dual\n---\nSKILL 正文")
        File(dir, "CLAUDE.md").writeText("---\nname: dual\n---\nCLAUDE 正文")

        assertEquals("SKILL 正文", locate("dual"))
    }

    @Test
    fun locate_nestedDirectories() {
        val nested = File(tempFolder.root, "repo/skills/nested-skill").apply { mkdirs() }
        File(nested, "SKILL.md").writeText("---\nname: nested-skill\n---\n嵌套正文")

        assertEquals("嵌套正文", locate("nested-skill"))
    }

    @Test
    fun locate_missingSkillReturnsNull() {
        writeSkill("exists", "---\nname: exists\n---\n正文")

        assertNull(locate("not-exists"))
    }

    @Test
    fun locate_truncatedFrontmatterFallsBackToDirNameThenFullParse() {
        // frontmatter 的结束标记超出 8K 头部窗口：头部解析拿不到 name（回退目录名），
        // 全文解析才能拿到真正的 frontmatter name——两条加载路径都必须返回正文
        val longDesc = "描".repeat(9_000)
        writeSkill(
            "deep-dir",
            "---\ndescription: \"$longDesc\"\nname: deep-name\n---\n真正的正文"
        )

        // 按 frontmatter 名加载：头部匹配不到 → 回退全量扫描仍能命中（与旧行为等价）
        assertEquals("真正的正文", locate("deep-name"))
        // 按目录名加载：头部命中 → 读全文返回正文
        assertEquals("真正的正文", locate("deep-dir"))
    }

    @Test
    fun locate_emptyRootReturnsNull() {
        assertNull(locate("any"))
    }

    @Test
    fun locate_readFailureOnOneFileDoesNotBreakOthers() {
        writeSkill("broken", "---\nname: broken\n---\n") // 空正文，仍可解析
        writeSkill("good", "---\nname: good\n---\n好正文")

        assertEquals("好正文", locate("good"))
    }
}
