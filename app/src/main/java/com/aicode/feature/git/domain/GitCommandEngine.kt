package com.aicode.feature.git.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.device.DeviceRootEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Git 模块专用的命令引擎：在「真机 ROOT」通道下把命令改投到本地 PRoot 容器，其余通道原样转发。
 *
 * 背景：Android 真机的 root 环境里**没有 git 二进制**（系统不带、Magisk 也不带），而 Debian/Ubuntu
 * 容器镜像内是预装好的。原先 Git 页与其它工具一样走 [CommandEngine] 委托层，切到真机通道后
 * 每条 `git` 都变成 `/system/bin/sh: git: inaccessible or not found`，表现为「初始化仓库失败」。
 *
 * 之所以只回退「真机」这一个通道：
 * - REMOTE_SSH：工作区本身在远程 Linux 上，git 必须在远程执行才看得到仓库，回退到本地反而错；
 * - LOCAL_PROOT：本来就是容器，行为不变。
 *
 * 若容器也尚未安装，[LinuxContainerEngine.notReadyHint] 会返回引导文案，由 Git 页展示给用户。
 */
@Singleton
class GitCommandEngine @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localEngine: LinuxContainerEngine,
    private val remoteEngine: com.aicode.feature.agent.domain.container.RemoteSshEngine
) : CommandEngine {

    /** 真机通道 → 容器；其余按各通道转发（用户放弃真机执行 git）。 */
    private fun delegate(): CommandEngine = when (modeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteEngine
        ExecutionMode.DEVICE_ROOT -> localEngine
        ExecutionMode.LOCAL_PROOT -> localEngine
    }

    override val initProgress: StateFlow<ContainerInitState>
        get() = delegate().initProgress

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = delegate().runCommandStream(command, projectPath, timeoutMs)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = delegate().runCommandSync(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate().runCommandSyncWithExit(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = delegate().runCommandSyncUnbounded(command, projectPath, timeoutMs)

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? = delegate().runCommandSyncIfReady(command, projectPath, timeoutMs)

    override fun isContainerInstalled(): Boolean = delegate().isContainerInstalled()

    override fun isProvisioned(): Boolean = delegate().isProvisioned()

    override fun defaultShell(): String = delegate().defaultShell()

    override fun notReadyHint(): String? = delegate().notReadyHint()

    override suspend fun ensureInstalled() {
        // 真机通道下初始化的是容器（root 无需安装），保证 Git 页也能把容器拉起来。
        localEngine.ensureInstalled()
    }
}
