package com.aicode.feature.workspace.data.repository

import com.aicode.core.util.LINE_TRUNCATED_NOTE
import com.aicode.feature.agent.domain.container.BoundedOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * 远程 exec 流读取原语（[drainBoundedLines]）测试，对应性能专项 P2-4：
 * 修复 execRemote 全量 readText() 读 stdout 且丢失 stderr 的问题。
 *
 * 仓库类依赖 Android Context + DataStore + SSH 连接，单测无法构造，故直接测抽出的
 * 顶层读取函数，覆盖四个要点：
 * 1. stdout/stderr 并发读：大量 stderr 输出不死锁，且 stderr 内容可见；
 * 2. stdout 超限截断并标注（BoundedOutput 既有风格）；
 * 3. 单行超长按项目既有 LINE_TRUNCATED_NOTE 标注；
 * 4. 正常输出按行解析结果不变（调用方 refreshRemoteWorkspaces 的消费方式）。
 */
class WorkspaceRepositoryExecRemoteTest {

    @Test
    fun normalOutput_lineParsingUnchanged() {
        // 调用方按行解析 stdout（output.lines()）：重组文本与原文按行切分结果一致
        for (raw in listOf("", "\n", "a\nb", "a\nb\nc\n", "  spaced  \n")) {
            val output = BoundedOutput()
            drainBoundedLines(ByteArrayInputStream(raw.toByteArray()), output)
            assertEquals(raw.lines(), output.build().lines())
            assertFalse(output.truncated)
        }
    }

    @Test
    fun stdoutBeyondLimit_isTruncatedWithNote() {
        val big = (1..20_000).joinToString("\n") { "line $it ${"y".repeat(20)}" }
        val output = BoundedOutput()
        drainBoundedLines(ByteArrayInputStream(big.toByteArray()), output)
        assertTrue(output.truncated)
        val built = output.build()
        // 沿用 BoundedOutput 的截断标注风格
        assertTrue(built.contains("…[输出过长，已省略"))
        // 保留开头与结尾，且整体仍有界（不会把 60 万字符全部读进内存）
        assertTrue(built.startsWith("line 1 "))
        assertTrue(built.contains("line 20000 "))
        assertTrue(built.length < big.length)
    }

    @Test
    fun longSingleLine_isTruncatedWithLineNote() {
        val line = "z".repeat(70_000) // 超过 MAX_STREAM_LINE_CHARS(64KB)
        val output = BoundedOutput()
        drainBoundedLines(ByteArrayInputStream(line.toByteArray()), output)
        assertTrue(output.build().contains(LINE_TRUNCATED_NOTE))
    }

    @Test
    fun concurrentRead_largeStderr_noDeadlock_stderrVisible() = runBlocking {
        val stdoutPipe = PipedInputStream()
        val stderrPipe = PipedInputStream()
        // 写端必须先连接再启动读协程：JDK 的 PipedInputStream 在「未连接任何写端」时
        // 直接 read() 会抛 Pipe not connected（不是阻塞等待），连接必须发生在读之前。
        val stderrWriter = PipedOutputStream(stderrPipe)
        val stdoutWriter = PipedOutputStream(stdoutPipe)
        // 约 66 万字符，远超典型管道缓冲（约 64KB）：顺序读必死锁
        val stderrText = (1..20_000).joinToString("\n") { "err line $it ${"x".repeat(20)}" }
        val writer = Thread {
            stderrWriter.use { it.write(stderrText.toByteArray()) }
            stdoutWriter.use { it.write("a\nb\nc\n".toByteArray()) }
        }
        writer.isDaemon = true
        writer.start()
        val stdout = BoundedOutput()
        val stderr = BoundedOutput()
        withTimeout(30_000) {
            val outJob = launch(Dispatchers.IO) { drainBoundedLines(stdoutPipe, stdout) }
            val errJob = launch(Dispatchers.IO) { drainBoundedLines(stderrPipe, stderr) }
            outJob.join()
            errJob.join()
        }
        writer.join(5_000)
        // stdout 正常命令内容不受影响
        assertEquals("a\nb\nc\n", stdout.build())
        // stderr 不再丢失：被限幅保留，且能看见开头内容
        assertTrue(stderr.truncated)
        assertTrue(stderr.build().contains("err line 1 "))
    }

    @Test
    fun sequentialStdoutOnlyDrain_hangsWhenStderrBackedUp() {
        // 复现修复前的读法：只排空 stdout、不读 stderr。远端 stderr 写满管道缓冲后命令阻塞，
        // stdout 永不 EOF（命令未结束）→ 本地无限挂起，即线上死锁。
        val stdoutPipe = PipedInputStream()
        val stderrPipe = PipedInputStream()
        val keepStdoutOpen = PipedOutputStream(stdoutPipe) // 连接但不关闭：stdout 无 EOF
        val writer = Thread {
            runCatching { PipedOutputStream(stderrPipe).use { it.write(ByteArray(200_000)) } }
            runCatching { Thread.sleep(30_000) }
        }
        writer.isDaemon = true
        writer.start()
        val drainThread = Thread {
            runCatching { drainBoundedLines(stdoutPipe, BoundedOutput()) }
        }
        drainThread.isDaemon = true
        drainThread.start()
        drainThread.join(3_000)
        // 3 秒后仍在读 => 挂起（若 stderr 也并发排空则早已 EOF 结束）
        val hung = drainThread.isAlive
        writer.interrupt()
        runCatching { keepStdoutOpen.close() }
        runCatching { stdoutPipe.close() }
        runCatching { stderrPipe.close() }
        assertTrue("只排空 stdout 时应一直挂起（修复前即死锁）", hung)
    }
}
