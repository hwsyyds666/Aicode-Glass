package com.aicode.feature.agent.domain.container

import com.aicode.feature.agent.domain.device.DeviceRootEngine
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CommandEngine] 的委托层：同时持有本地容器、远程 SSH 与真机 ROOT 三套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [CommandEngine] 在何时被首次注入，
 * 真正执行命令时才读取当前模式。
 *
 * 除全局模式外，还支持调用方显式指定执行环境（[engineFor]）：全局模式是「首选项」，
 * AI 可在合适的场合（如需要容器内完整 Linux 工具链时）显式选另一个通道执行。
 */
@Singleton
class DelegatingCommandEngine @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localEngine: LinuxContainerEngine,
    private val remoteEngine: RemoteSshEngine,
    private val deviceRootEngine: DeviceRootEngine
) : CommandEngine {

    /** 显式执行环境。[AUTO] 跟随全局模式首选项。 */
    enum class TargetEnv { AUTO, CONTAINER, DEVICE, REMOTE }

    private fun delegate(): CommandEngine = when (modeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteEngine
        ExecutionMode.DEVICE_ROOT -> deviceRootEngine
        ExecutionMode.LOCAL_PROOT -> localEngine
    }

    /**
     * 按指定执行环境取引擎。[TargetEnv.AUTO] 等价于当前全局模式（首选项）；
     * 其余值绕过全局模式直连对应引擎，供 AI 按场合显式切换通道用。
     */
    fun engineFor(env: TargetEnv): CommandEngine = when (env) {
        TargetEnv.AUTO -> delegate()
        TargetEnv.CONTAINER -> localEngine
        TargetEnv.DEVICE -> deviceRootEngine
        TargetEnv.REMOTE -> remoteEngine
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

    override suspend fun ensureInstalled() = delegate().ensureInstalled()
}
