package com.aicode.feature.workspace.domain

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 看门狗线程体的中断安全性回归测试。
 *
 * 背景：v1.0.4 真机崩溃（DEVICE_ROOT、聊天页、Thread-845、InterruptedException@Thread.sleep）
 * 的根因是第二次 sleep（destroy 后的宽限期）没有保护——主流程 finally 的 interrupt 落在
 * 判超时之后、宽限 sleep 之中，异常逃出 run() 后被默认未捕获处理器崩掉整个 App。
 *
 * 注意断言方式：线程死了 isAlive 也是 false，所以必须挂 UncaughtExceptionHandler
 * 记录是否有异常逃逸，否则测试会被旧代码"骗过"。
 */
class DeviceRootWatchdogTest {

    /** 跨平台的长驻子进程：Windows 用 ping（timeout 命令拒绝管道 stdin），其余用 sleep。 */
    private fun sleeper(): ProcessBuilder =
        if ((System.getProperty("os.name") ?: "").startsWith("Windows")) {
            ProcessBuilder("cmd", "/c", "ping -n 30 127.0.0.1")
        } else {
            ProcessBuilder("sleep", "30")
        }

    /** 看门狗线程 + 逃逸异常捕获；返回 Pair(线程, 捕获槽)。 */
    private fun watchdog(process: Process, timeoutMs: Long, timedOut: AtomicBoolean): Pair<Thread, AtomicReference<Throwable>> {
        val escaped = AtomicReference<Throwable>()
        val t = Thread({ runShWatchdog(process, timeoutMs, timedOut) }, "watchdog-test")
        t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> escaped.set(e) }
        t.isDaemon = true
        return t to escaped
    }

    @Test
    fun `中断落在宽限期_看门狗自行退出且不逃逸异常`() {
        val p = sleeper().start()
        try {
            val timedOut = AtomicBoolean(false)
            val (t, escaped) = watchdog(p, 150, timedOut)
            t.start()
            // 150ms 首睡已过、destroy 已执行，此刻正处在 200ms 宽限睡中
            Thread.sleep(280)
            t.interrupt() // 模拟主流程 finally 的中断此刻到达
            t.join(3000)

            assertNull("不得有未捕获异常逃逸 run()（v1.0.4 崩溃根因）", escaped.get())
            assertFalse("看门狗线程应自行退出", t.isAlive)
            assertTrue("应已判定超时", timedOut.get())
            assertFalse("子进程应已被销毁", p.isAlive)
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun `中断落在首次睡眠_干净退出且不逃逸异常`() {
        val p = sleeper().start()
        try {
            val timedOut = AtomicBoolean(false)
            val (t, escaped) = watchdog(p, 60_000, timedOut)
            t.start()
            Thread.sleep(120)
            t.interrupt()
            t.join(3000)

            assertNull("不得有未捕获异常逃逸 run()", escaped.get())
            assertFalse(t.isAlive)
            assertFalse("未超时不应置位", timedOut.get())
        } finally {
            p.destroyForcibly()
        }
    }

    @Test
    fun `进程已退出_看门狗空转不误判`() {
        val p = sleeper().start()
        p.destroyForcibly()
        p.waitFor()
        val timedOut = AtomicBoolean(false)
        val (t, escaped) = watchdog(p, 50, timedOut)
        t.start()
        t.join(3000)
        assertNull("不得有未捕获异常逃逸 run()", escaped.get())
        assertFalse(t.isAlive)
        assertFalse("进程已死，不应判超时", timedOut.get())
    }
}
