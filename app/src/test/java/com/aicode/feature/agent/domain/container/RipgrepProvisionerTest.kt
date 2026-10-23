package com.aicode.feature.agent.domain.container

import com.aicode.feature.agent.domain.container.DelegatingCommandEngine.TargetEnv
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rg 保障的缓存 / 安装 / 自愈语义。
 *
 * 焦点：按容器缓存（命中不再执行命令）、缺失时装一次并校验、失败按 TTL 缓存不反复重试、
 * force 绕过缓存（search 执行 rg 返回 127 时的自愈）、环境未就绪不跑命令、并发只装一次。
 */
class RipgrepProvisionerTest {

    /**
     * 脚本化假环境：按命令内容返回检测/安装结果，记录执行次数与命令。
     * [detectAvailable] 控制 `command -v rg && rg --version` 是否通过；
     * [installSucceeds]/[installOutput] 控制安装命令的结果。
     */
    private class FakeRgEnvironment(
        var ready: Boolean = true,
        var detectAvailable: Boolean = false,
        var installSucceeds: Boolean = true,
        var installOutput: String = "ripgrep 14.1.1 (rev abcdef)"
    ) : RgEnvironment {
        var runCount = 0
            private set
        val commands = mutableListOf<String>()

        override fun currentTarget(): TargetEnv = TargetEnv.CONTAINER

        override fun envKey(target: TargetEnv): String = "test-container"

        override fun isReady(target: TargetEnv): Boolean = ready

        override suspend fun run(
            target: TargetEnv,
            command: String,
            timeoutMs: Long
        ): CommandResult? {
            runCount++
            commands += command
            return if (command == RG_INSTALL_COMMAND) {
                if (installSucceeds) CommandResult(installOutput, 0)
                else CommandResult("E: Unable to locate package ripgrep", 100)
            } else {
                if (detectAvailable) CommandResult("/usr/bin/rg\nripgrep 14.1.1", 0)
                else CommandResult("", 1)
            }
        }
    }

    @Test
    fun notReady_returnsNotReady_withoutRunningAnyCommand() = runTest {
        val env = FakeRgEnvironment(ready = false)
        val provisioner = RipgrepProvisioner(env)

        val status = provisioner.ensureAvailable()

        assertEquals(RipgrepStatus.NotReady, status)
        assertEquals(0, env.runCount)
    }

    @Test
    fun detectedAvailable_firstCallRunsDetect_secondCallHitsCache() = runTest {
        val env = FakeRgEnvironment(detectAvailable = true)
        val provisioner = RipgrepProvisioner(env)

        val first = provisioner.ensureAvailable()
        val second = provisioner.ensureAvailable()

        assertEquals(RipgrepStatus.Available(justInstalled = false), first)
        assertEquals(RipgrepStatus.Available(justInstalled = false), second)
        // 第二次命中缓存，不再执行命令（每次 search 不重复 fork 检测进程）
        assertEquals(1, env.runCount)
        assertEquals(RG_DETECT_COMMAND, env.commands.single())
    }

    @Test
    fun missingThenInstallSucceeds_reportsJustInstalled_andCaches() = runTest {
        val env = FakeRgEnvironment(detectAvailable = false, installSucceeds = true)
        val provisioner = RipgrepProvisioner(env)

        val status = provisioner.ensureAvailable()

        assertEquals(RipgrepStatus.Available(justInstalled = true), status)
        // 检测一次 + 安装一次
        assertEquals(2, env.runCount)
        // 安装结果入缓存：后续调用（含初始化入口的重复触发）不再跑命令
        assertEquals(RipgrepStatus.Available(justInstalled = false), provisioner.ensureAvailable())
        assertEquals(2, env.runCount)
    }

    @Test
    fun installFails_cachedWithinTtl_forceBypassesCacheAndRetries() = runTest {
        val env = FakeRgEnvironment(detectAvailable = false, installSucceeds = false)
        val provisioner = RipgrepProvisioner(env)

        val first = provisioner.ensureAvailable()
        assertTrue(first is RipgrepStatus.Unavailable)
        assertEquals(2, env.runCount) // 检测 + 安装（仅一次自动安装尝试）

        // 非 force：失败记录在 TTL 内，直接返回缓存，不再重跑安装（避免每次 search 都超时等待）
        val second = provisioner.ensureAvailable()
        assertTrue(second is RipgrepStatus.Unavailable)
        assertEquals(2, env.runCount)

        // force：search 实际执行 rg 返回 127 时绕过缓存，重新检测 + 安装
        val third = provisioner.ensureAvailable(force = true)
        assertTrue(third is RipgrepStatus.Unavailable)
        assertEquals(4, env.runCount)
    }

    @Test
    fun installExitsZeroButNoVersionLine_treatedAsFailure() = runTest {
        // 退出码 0 但输出没有 ripgrep 版本行（安装命令被伪装/异常）→ 判失败，走兜底
        val env = FakeRgEnvironment(detectAvailable = false, installSucceeds = true, installOutput = "OK: 3 packages")
        val provisioner = RipgrepProvisioner(env)

        assertTrue(provisioner.ensureAvailable() is RipgrepStatus.Unavailable)
    }

    @Test
    fun concurrentCalls_installRunsOnlyOnce() = runTest {
        val env = FakeRgEnvironment(detectAvailable = false, installSucceeds = true)
        val provisioner = RipgrepProvisioner(env)

        coroutineScope {
            repeat(5) { launch { provisioner.ensureAvailable() } }
        }

        // Mutex 串行 + 双检：5 个并发调用只做一次检测 + 一次安装
        assertEquals(2, env.runCount)
        assertTrue(provisioner.ensureAvailable() is RipgrepStatus.Available)
    }
}
