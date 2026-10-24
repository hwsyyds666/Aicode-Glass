package com.aicode.feature.agent.domain.tool.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * search/list 工具的 shell 参数解析与 `| head` 白名单校验。
 *
 * 安全焦点：只放行 `head [-n N]` 行数截断，其余管道命令（grep/sort/wc/rm/…）与
 * shell 元字符（`;` 等）一律拒绝，防止借管道语法在容器里执行任意命令。
 */
class ShellArgsTest {

    // ---------- parseShellWords ----------

    @Test
    fun parseShellWords_handlesQuotesAndEscapedPipe() {
        val tokens = parseShellWords(
            "-n \"挂载\\|mount\" ~/workspace/app/src/main/res/values/strings.xml | head -30"
        )
        assertEquals(
            listOf("-n", "挂载|mount", "~/workspace/app/src/main/res/values/strings.xml", "|", "head", "-30"),
            tokens
        )
    }

    @Test
    fun parseShellWords_pipeInsideQuotes_isLiteral() {
        val tokens = parseShellWords("-n \"a|b\" src/")
        assertEquals(listOf("-n", "a|b", "src/"), tokens)
    }

    @Test
    fun parseShellWords_singleQuotedPipe_isLiteral() {
        val tokens = parseShellWords("'a|b' src/")
        assertEquals(listOf("a|b", "src/"), tokens)
    }

    @Test
    fun parseShellWords_unclosedQuote_returnsNull() {
        assertNull(parseShellWords("-n \"unclosed"))
        assertNull(parseShellWords("'unclosed"))
    }

    @Test
    fun parseShellWords_emptyInput_returnsEmptyList() {
        assertEquals(emptyList<String>(), parseShellWords(""))
        assertEquals(emptyList<String>(), parseShellWords("   "))
    }

    // ---------- splitPipes ----------

    @Test
    fun splitPipes_noPipe_returnsTokensWithEmptySegments() {
        val (head, segments) = splitPipes(listOf("-n", "foo", "src/"))!!
        assertEquals(listOf("-n", "foo", "src/"), head)
        assertTrue(segments.isEmpty())
    }

    @Test
    fun splitPipes_singlePipe_splitsCorrectly() {
        val (head, segments) = splitPipes(listOf("-n", "foo", "src/", "|", "head", "-30"))!!
        assertEquals(listOf("-n", "foo", "src/"), head)
        assertEquals(listOf(listOf("head", "-30")), segments)
    }

    @Test
    fun splitPipes_multiplePipes_splitsAllSegments() {
        val (head, segments) = splitPipes(listOf("a", "|", "head", "-5", "|", "head"))!!
        assertEquals(listOf("a"), head)
        assertEquals(listOf(listOf("head", "-5"), listOf("head")), segments)
    }

    @Test
    fun splitPipes_emptyHead_returnsNull() {
        assertNull(splitPipes(listOf("|", "head", "-30")))
    }

    // ---------- headLinesOf ----------

    @Test
    fun headLinesOf_allowsHeadOnly() {
        assertEquals(10, headLinesOf(listOf("head")))
        assertEquals(30, headLinesOf(listOf("head", "-30")))
        assertEquals(30, headLinesOf(listOf("head", "-n", "30")))
        assertEquals(0, headLinesOf(listOf("head", "-0")))
    }

    @Test
    fun headLinesOf_rejectsNonHeadCommands() {
        assertNull(headLinesOf(listOf("grep", "foo")))
        assertNull(headLinesOf(listOf("wc", "-l")))
        assertNull(headLinesOf(listOf("sort")))
        assertNull(headLinesOf(listOf("rm", "-rf", "/")))
        assertNull(headLinesOf(listOf("cat")))
    }

    @Test
    fun headLinesOf_rejectsInvalidHeadArgs() {
        // head 只允许行数参数：其它 flag、非数字、负数、多余参数、空段一律拒绝。
        assertNull(headLinesOf(listOf("head", "-c", "100")))
        assertNull(headLinesOf(listOf("head", "-n", "abc")))
        assertNull(headLinesOf(listOf("head", "-n", "-5")))
        assertNull(headLinesOf(listOf("head", "-30", "extra")))
        assertNull(headLinesOf(listOf("head", "-")))
        assertNull(headLinesOf(emptyList()))
    }

    @Test
    fun headLinesOf_rejectsShellMetacharactersAfterHead() {
        // `;`/`&&`/重定向等不是 head 的合法参数 → 整段拒绝，不拼进命令。
        assertNull(headLinesOf(listOf("head", "-30", ";", "rm", "-rf", "/")))
        assertNull(headLinesOf(listOf("head", "-30", ">", "/tmp/x")))
    }

    // ---------- buildSearchCommand ----------

    @Test
    fun buildSearchCommand_noPipe_unchangedBehavior() {
        val command = buildSearchCommand(
            parseShellWords("-n \"fun main\" ~/workspace/app")!!
        )!!
        assertEquals(
            "rg --line-number --no-heading --with-filename --color never '-n' 'fun main' '/root/workspace/app'",
            command
        )
    }

    @Test
    fun buildSearchCommand_customHome_expandsTilde() {
        // 传入执行环境（本地容器 / 远程服务器）的真实 home，~/ 按该 home 展开而非硬编码 /root
        val command = buildSearchCommand(
            parseShellWords("-n \"fun main\" ~/workspace/app")!!,
            home = "/home/alice"
        )!!
        assertEquals(
            "rg --line-number --no-heading --with-filename --color never '-n' 'fun main' '/home/alice/workspace/app'",
            command
        )
        // 裸 ~ 也展开为 home
        assertEquals(
            "rg --line-number --no-heading --with-filename --color never '-n' 'x' '/home/alice'",
            buildSearchCommand(parseShellWords("-n x ~")!!, home = "/home/alice")
        )
    }

    @Test
    fun buildSearchCommand_escapedPipeWithHead_appendsWhitelistedPipe() {
        val command = buildSearchCommand(
            parseShellWords("-n \"挂载\\|mount\" ~/workspace/app/src/main/res/values/strings.xml | head -30")!!
        )!!
        assertTrue(command.endsWith(" | head -30"))
        assertTrue(command.contains("'挂载|mount'"))
    }

    @Test
    fun buildSearchCommand_headVariants_areAllowed() {
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head")!!)!!.endsWith(" | head"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -n 30")!!)!!.endsWith(" | head -n 30"))
        assertTrue(buildSearchCommand(parseShellWords("-n foo src/ | head -30")!!)!!.endsWith(" | head -30"))
    }

    @Test
    fun buildSearchCommand_rejectsArbitraryPipeCommands() {
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | grep bar")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | rm -rf /")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -30 | wc -l")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -30; rm -rf /")!!))
        assertNull(buildSearchCommand(parseShellWords("-n foo src/ | head -n abc")!!))
        assertNull(buildSearchCommand(parseShellWords("| head -30")!!))
    }

    @Test
    fun buildSearchCommand_pipeInsideQuotes_isNotAPipe() {
        val command = buildSearchCommand(parseShellWords("-n \"a|b\" src/")!!)!!
        assertTrue(command.contains("'a|b'"))
        assertTrue("命令不应包含管道段", " | " !in command)
    }

    @Test
    fun buildSearchCommand_singleQuoteEscaping_isPreserved() {
        val command = buildSearchCommand(parseShellWords("-n \"it's\" src/")!!)!!
        assertTrue(command.contains("'it'\"'\"'s'"))
    }

    // ---------- buildFallbackSearchCommand（rg 缺失时的 grep 兜底） ----------

    @Test
    fun buildFallbackSearchCommand_mapsCompatibleArgs() {
        // -i/-F 透传，-n（rg 默认参数）跳过，~ 展开；输出格式与 rg 一致（文件:行号:内容）
        val command = buildFallbackSearchCommand(
            parseShellWords("-i -F -n \"fun main\" ~/workspace/app")!!,
            home = "/root"
        )!!
        assertEquals(
            "grep -rnI -E --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=.gradle -i -F -e 'fun main' '/root/workspace/app'",
            command
        )
    }

    @Test
    fun buildFallbackSearchCommand_noPath_defaultsToDot() {
        // grep -r 不带路径会读 stdin，必须显式补 "."（命令 cwd 已是工作区）
        val command = buildFallbackSearchCommand(parseShellWords("TODO")!!)!!
        assertTrue(command.startsWith("grep -rnI -E"))
        assertTrue(command.endsWith("-e 'TODO' ."))
    }

    @Test
    fun buildFallbackSearchCommand_globMapsToInclude_negativeGlobRejected() {
        val command = buildFallbackSearchCommand(parseShellWords("-g '*.kt' foo src/")!!)!!
        assertTrue(command.contains("--include='*.kt'"))
        assertTrue(command.endsWith("-e 'foo' 'src/'"))
        // 否定 glob（!）语义与 grep --include 不同，拒绝兜底
        assertNull(buildFallbackSearchCommand(parseShellWords("-g '!*.min.js' foo src/")!!))
    }

    @Test
    fun buildFallbackSearchCommand_multipleRegexpFlags_allPositionalArePaths() {
        val command = buildFallbackSearchCommand(parseShellWords("-e foo -e bar src/")!!)!!
        assertTrue(command.contains("-e 'foo' -e 'bar'"))
        assertTrue(command.endsWith("'src/'"))
    }

    @Test
    fun buildFallbackSearchCommand_contextLines_mappedWithNumericValue() {
        val command = buildFallbackSearchCommand(parseShellWords("-A 3 foo src/")!!)!!
        assertTrue(command.contains("-A 3"))
        assertNull(buildFallbackSearchCommand(parseShellWords("-A x foo src/")!!))
    }

    @Test
    fun buildFallbackSearchCommand_dashDash_allowsLeadingDashPattern() {
        val command = buildFallbackSearchCommand(parseShellWords("-- -foo src/")!!)!!
        assertTrue(command.contains("-e '-foo' 'src/'"))
    }

    @Test
    fun buildFallbackSearchCommand_rejectsRgOnlyArgs() {
        assertNull(buildFallbackSearchCommand(parseShellWords("--json foo src/")!!))
        assertNull(buildFallbackSearchCommand(parseShellWords("-l foo src/")!!))
        assertNull(buildFallbackSearchCommand(parseShellWords("--files")!!))
        assertNull(buildFallbackSearchCommand(parseShellWords("--type ts foo src/")!!))
        assertNull(buildFallbackSearchCommand(parseShellWords("-N foo src/")!!))
        // 没有模式（仅开关）同样拒绝
        assertNull(buildFallbackSearchCommand(parseShellWords("--hidden")!!))
    }

    @Test
    fun buildFallbackSearchCommand_keepsHeadPipe_rejectsOtherPipes() {
        assertTrue(buildFallbackSearchCommand(parseShellWords("foo src/ | head -30")!!)!!.endsWith(" | head -30"))
        assertNull(buildFallbackSearchCommand(parseShellWords("foo src/ | grep bar")!!))
        assertNull(buildFallbackSearchCommand(parseShellWords("foo src/ | head -30; rm -rf /")!!))
    }

    // ---------- positionalPathArgs ----------

    @Test
    fun positionalPathArgs_firstPositionalIsPattern_pathsFollow() {
        assertEquals(
            listOf("src/", "app/"),
            positionalPathArgs(parseShellWords("-n foo src/ app/")!!)
        )
    }

    @Test
    fun positionalPathArgs_withRegexpFlag_allPositionalsArePaths() {
        assertEquals(
            listOf("src/", "app/"),
            positionalPathArgs(parseShellWords("-e foo src/ app/")!!)
        )
    }

    @Test
    fun positionalPathArgs_valueFlagsSkipped() {
        assertEquals(
            listOf("app/"),
            positionalPathArgs(parseShellWords("-g *.kt -A 2 foo app/")!!)
        )
    }

    @Test
    fun positionalPathArgs_dashDash_firstPositionalAfterDashDashIsPattern() {
        // `-- -foo src/`：-- 后第一个位置参数 -foo 是模式串，路径只有 src/
        assertEquals(
            listOf("src/"),
            positionalPathArgs(parseShellWords("-- -foo src/")!!)
        )
    }

    // ---------- isSearchTargetInsideWorkspace ----------

    @Test
    fun isSearchTargetInsideWorkspace_noPathArgs_isInside() {
        val root = tempWorkspace()
        assertTrue(isSearchTargetInsideWorkspace(parseShellWords("-n foo")!!, root, "/data/user/0/com.aicode/files"))
    }

    @Test
    fun isSearchTargetInsideWorkspace_absolutePathInside_isInside() {
        assumePosixFilesystem()
        val root = tempWorkspace()
        assertTrue(isSearchTargetInsideWorkspace(parseShellWords("-n foo $root/app/src")!!, root, "/home"))
    }

    @Test
    fun isSearchTargetInsideWorkspace_absolutePathOutside_isOutside() {
        assumePosixFilesystem()
        val root = tempWorkspace()
        assertTrue(!isSearchTargetInsideWorkspace(parseShellWords("-n foo /system/etc")!!, root, "/home"))
    }

    @Test
    fun isSearchTargetInsideWorkspace_mixedPaths_oneOutside_isOutside() {
        assumePosixFilesystem()
        val root = tempWorkspace()
        val tokens = parseShellWords("-n foo $root/app /data/local/tmp")!!
        assertTrue(!isSearchTargetInsideWorkspace(tokens, root, "/home"))
    }

    @Test
    fun isSearchTargetInsideWorkspace_tildePath_resolvedViaHome() {
        assumeCanonicalResolvesDirSymlinks()
        val root = tempWorkspace()
        val home = tempDirWithSymlink("workspace", root)
        assertTrue(isSearchTargetInsideWorkspace(parseShellWords("-n foo ~/workspace/app")!!, root, home))
    }

    // ---------- translateTokensForContainer ----------

    @Test
    fun translateTokensForContainer_absolutePathsRewrittenToMountPoint() {
        assumePosixFilesystem()
        val root = tempWorkspace()
        val translated = translateTokensForContainer(
            parseShellWords("-n foo $root/app/src $root")!!,
            workspaceRoot = root,
            home = "/data/user/0/com.aicode/files"
        )
        assertEquals(listOf("-n", "foo", "/root/workspace/app/src", "/root/workspace"), translated)
    }

    @Test
    fun translateTokensForContainer_patternAndRelativePathsUntouched() {
        val root = tempWorkspace()
        val translated = translateTokensForContainer(
            parseShellWords("-i 'fun main' src/")!!,
            workspaceRoot = root,
            home = "/data/user/0/com.aicode/files"
        )
        assertEquals(listOf("-i", "fun main", "src/"), translated)
    }

    @Test
    fun translateTokensForContainer_outsidePathUntouched() {
        assumePosixFilesystem()
        val root = tempWorkspace()
        val translated = translateTokensForContainer(
            parseShellWords("-n foo /system/etc/hosts")!!,
            workspaceRoot = root,
            home = "/data/user/0/com.aicode/files"
        )
        assertEquals(listOf("-n", "foo", "/system/etc/hosts"), translated)
    }

    @Test
    fun translateTokensForContainer_valueFlagsNotTranslated() {
        assumePosixFilesystem()
        val root = tempWorkspace()
        val translated = translateTokensForContainer(
            parseShellWords("-g *.kt -A 2 foo $root/app")!!,
            workspaceRoot = root,
            home = "/data/user/0/com.aicode/files"
        )
        assertEquals(listOf("-g", "*.kt", "-A", "2", "foo", "/root/workspace/app"), translated)
    }

    @Test
    fun translateTokensForContainer_withRegexpFlag_firstPositionalIsPath() {
        assumePosixFilesystem()
        val root = tempWorkspace()
        // -e 显式给出模式时，第一个位置参数就是路径（不是模式串）
        val translated = translateTokensForContainer(
            parseShellWords("-e foo $root/app")!!,
            workspaceRoot = root,
            home = "/data/user/0/com.aicode/files"
        )
        assertEquals(listOf("-e", "foo", "/root/workspace/app"), translated)
    }

    @Test
    fun translateTokensForContainer_tildePathViaHomeSymlink_translated() {
        assumeCanonicalResolvesDirSymlinks()
        val root = tempWorkspace()
        val home = tempDirWithSymlink("workspace", root)
        val translated = translateTokensForContainer(
            parseShellWords("-n foo ~/workspace/app")!!,
            workspaceRoot = root,
            home = home
        )
        assertEquals(listOf("-n", "foo", "/root/workspace/app"), translated)
    }

    @Test
    fun translateTokensForContainer_dashDash_pathsAfterStillTranslated() {
        assumePosixFilesystem()
        val root = tempWorkspace()
        val translated = translateTokensForContainer(
            parseShellWords("-- -foo $root/app")!!,
            workspaceRoot = root,
            home = "/data/user/0/com.aicode/files"
        )
        assertEquals(listOf("--", "-foo", "/root/workspace/app"), translated)
    }

    // ---------- 测试辅助 ----------

    /**
     * 假设 POSIX 风格绝对路径（`/...` 开头）。
     *
     * 被测逻辑面向 Android：工作区路径形如 `/data/user/0/...`。Windows 的绝对路径
     * 以盘符开头（`C:/...`），`startsWith("/")` 判定不成立，用例在该平台无意义，
     * 探测后跳过（不静默失败）。Linux/Android（含 CI）上照常执行。
     */
    private fun assumePosixFilesystem() {
        org.junit.Assume.assumeTrue(java.io.File("/etc").exists())
    }

    /** 建一个真实临时目录作为工作区（canonical 解析需要路径存在）；返回正斜杠形式，避免 parseShellWords 吞掉 Windows 反斜杠。 */
    private fun tempWorkspace(): String {
        val dir = kotlin.io.path.createTempDirectory("aicode-ws").toFile()
        dir.deleteOnExit()
        return dir.canonicalPath.replace('\\', '/')
    }

    /** 建临时 home 目录，其中建 `name` symlink 指向 [target]，返回 home 绝对路径。 */
    private fun tempDirWithSymlink(name: String, target: String): String {
        val home = kotlin.io.path.createTempDirectory("aicode-home").toFile()
        home.deleteOnExit()
        java.nio.file.Files.createSymbolicLink(home.resolve(name).toPath(), java.io.File(target).toPath())
        return home.canonicalPath
    }

    /**
     * 当前 JVM 的 File.canonicalPath 是否解析目录 symlink。
     *
     * Linux/Android（realpath）会解析不存在的尾部路径中的父级 symlink——真机
     * `~/workspace` symlink 正是该形态；Windows 的 canonicalPath 实现不解，
     * 相关用例在该平台无意义，探测后跳过（不静默失败）。
     */
    private fun assumeCanonicalResolvesDirSymlinks() {
        val probeRoot = tempWorkspace()
        val probeHome = tempDirWithSymlink("workspace", probeRoot)
        val resolved = runCatching {
            java.io.File("$probeHome/workspace/x").canonicalPath.replace('\\', '/')
        }.getOrNull()
        org.junit.Assume.assumeTrue(
            resolved != null && resolved.startsWith("$probeRoot/"),
        )
    }
}
