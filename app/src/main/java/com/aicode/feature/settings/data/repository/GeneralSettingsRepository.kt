package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.generalDataStore by preferencesDataStore(name = "general_prefs")

/**
 * 聊天页「任务待办」区域的显示位置。
 *
 * 待办由 AI 通过 TodoTool 维护，UI 上是一个可折叠的轻量条：默认只显示
 * 「x/y 已完成」和当前进行中的任务，点击展开完整列表。本枚举决定它挂在聊天页的哪一处。
 */
enum class TodoDisplayPosition {
    /** 标题栏下方：紧贴顶部悬浮标题栏之下，进入消息流之前占一行高度。 */
    BELOW_TITLE_BAR,

    /** 消息栏上方：紧贴底部输入区之上（改造前既有行为，默认保持）。 */
    ABOVE_INPUT_BAR,

    /** 关闭：不在聊天页渲染待办区域（仍可在会话内被 AI 维护，仅不展示）。 */
    OFF
}

/** App 启动（含切换工作区）时进入哪个会话。 */
enum class StartupSessionMode {
    /** 新开会话：复用当前工作区里还没发过消息的空会话，否则新建。 */
    NEW_SESSION,

    /** 打开最近会话：直接进入最近更新的会话，该工作区还没有会话时才新建。 */
    RECENT_SESSION
}

/**
 * 「偏好设置」里的用户偏好。
 *
 * 目前七项：拉取模型成功后是否自动移除远端已不存在的本地模型（默认开启）、
 * 启动时进入新会话还是最近会话（默认新开会话）、首字 / 数据块间隔超时（秒）、
 * 网络请求的最大重试次数（默认 6）、回车发送、
 * sendFile 单个文件大小上限（默认 100MB），
 * 以及移除外部本地工作区时是否一并删除其聊天记录（默认关闭）。
 * DataStore 用法与 [KeepaliveSettingsRepository] 一致。
 */
@Singleton
class GeneralSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val AUTO_REMOVE_STALE_MODELS_KEY = booleanPreferencesKey("auto_remove_stale_models")
        val STARTUP_SESSION_MODE_KEY = stringPreferencesKey("startup_session_mode")
        val FIRST_BYTE_TIMEOUT_SEC_KEY = intPreferencesKey("first_byte_timeout_sec")
        val STREAM_IDLE_TIMEOUT_SEC_KEY = intPreferencesKey("stream_idle_timeout_sec")
        val MAX_NETWORK_RETRIES_KEY = intPreferencesKey("max_network_retries")
        val ENTER_TO_SEND_KEY = booleanPreferencesKey("enter_to_send")
        val SENDFILE_MAX_SIZE_MB_KEY = intPreferencesKey("sendfile_max_size_mb")
        val DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY = booleanPreferencesKey("delete_external_workspace_sessions")
        val TODO_DISPLAY_POSITION_KEY = stringPreferencesKey("todo_display_position")
        /** 「提示词」页使用说明公告已展示内容的哈希；无值或与当前内容哈希不一致时重新弹出。 */
        val PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY = stringPreferencesKey("prompts_announcement_shown_hash")

        /** 首字超时默认 5 分钟，与原硬编码值一致。 */
        const val DEFAULT_FIRST_BYTE_TIMEOUT_SEC = 300

        /** 网络重试次数默认 6，与原硬编码值一致。 */
        const val DEFAULT_MAX_NETWORK_RETRIES = 6

        /** sendFile 单个文件大小上限默认 100MB，与原硬编码值一致。 */
        const val DEFAULT_SENDFILE_MAX_SIZE_MB = 100
    }

    /** 拉取模型后自动对齐本地列表的开关流；未设置时回退到 true（默认开启）。 */
    val autoRemoveStaleModelsFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[AUTO_REMOVE_STALE_MODELS_KEY] ?: true }

    suspend fun setAutoRemoveStaleModels(enabled: Boolean) {
        context.generalDataStore.edit { it[AUTO_REMOVE_STALE_MODELS_KEY] = enabled }
    }

    /** 启动时会话偏好流；未设置或值无法识别时回退到 [StartupSessionMode.NEW_SESSION]。 */
    val startupSessionModeFlow: Flow<StartupSessionMode> = context.generalDataStore.data.map { prefs ->
        when (prefs[STARTUP_SESSION_MODE_KEY]) {
            StartupSessionMode.RECENT_SESSION.name -> StartupSessionMode.RECENT_SESSION
            else -> StartupSessionMode.NEW_SESSION
        }
    }

    suspend fun setStartupSessionMode(mode: StartupSessionMode) {
        context.generalDataStore.edit { it[STARTUP_SESSION_MODE_KEY] = mode.name }
    }

    /** 切换到工作区前读一次偏好，避免在会话初始化路径上多开一条收集流。 */
    suspend fun startupSessionMode(): StartupSessionMode = startupSessionModeFlow.first()

    /** 备份快照：当前自动对齐开关。 */
    suspend fun autoRemoveStaleModelsSnapshot(): Boolean = autoRemoveStaleModelsFlow.first()

    /** 备份快照：当前启动时会话偏好名。 */
    suspend fun startupSessionModeSnapshot(): String = startupSessionModeFlow.first().name

    /** 从备份还原自动对齐开关。 */
    suspend fun restoreAutoRemoveStaleModels(enabled: Boolean) = setAutoRemoveStaleModels(enabled)

    /** 从备份还原启动时会话偏好；旧备份无此字段（null）或值无法识别时回退新开会话。 */
    suspend fun restoreStartupSessionMode(mode: String?) {
        setStartupSessionMode(
            StartupSessionMode.entries.firstOrNull { it.name == mode } ?: StartupSessionMode.NEW_SESSION
        )
    }

    /** 首字超时（秒）；0 表示不限制，未设置时回退到 300 秒。 */
    val firstByteTimeoutSecFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[FIRST_BYTE_TIMEOUT_SEC_KEY] ?: DEFAULT_FIRST_BYTE_TIMEOUT_SEC).coerceAtLeast(0)
    }

    /** 流式响应相邻数据块间隔超时（秒）；0（默认）表示不限制。 */
    val streamIdleTimeoutSecFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[STREAM_IDLE_TIMEOUT_SEC_KEY] ?: 0).coerceAtLeast(0)
    }

    suspend fun setFirstByteTimeoutSec(sec: Int) {
        context.generalDataStore.edit { it[FIRST_BYTE_TIMEOUT_SEC_KEY] = sec.coerceAtLeast(0) }
    }

    suspend fun setStreamIdleTimeoutSec(sec: Int) {
        context.generalDataStore.edit { it[STREAM_IDLE_TIMEOUT_SEC_KEY] = sec.coerceAtLeast(0) }
    }

    /** 装配 provider 前读取一次首字超时（毫秒）；0 表示不限制。 */
    suspend fun firstByteTimeoutMs(): Long = firstByteTimeoutSecFlow.first() * 1000L

    /** 装配 provider 前读取一次数据块间隔超时（毫秒）；0 表示不限制。 */
    suspend fun streamIdleTimeoutMs(): Long = streamIdleTimeoutSecFlow.first() * 1000L

    /** 备份快照：两个超时设置（秒）。 */
    suspend fun firstByteTimeoutSecSnapshot(): Int = firstByteTimeoutSecFlow.first()

    suspend fun streamIdleTimeoutSecSnapshot(): Int = streamIdleTimeoutSecFlow.first()

    suspend fun restoreFirstByteTimeoutSec(sec: Int) = setFirstByteTimeoutSec(sec)

    suspend fun restoreStreamIdleTimeoutSec(sec: Int) = setStreamIdleTimeoutSec(sec)

    /** 网络请求最大重试次数（不含首次请求）；0 表示不重试，未设置时回退到 6。 */
    val maxNetworkRetriesFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[MAX_NETWORK_RETRIES_KEY] ?: DEFAULT_MAX_NETWORK_RETRIES).coerceAtLeast(0)
    }

    suspend fun setMaxNetworkRetries(count: Int) {
        context.generalDataStore.edit { it[MAX_NETWORK_RETRIES_KEY] = count.coerceAtLeast(0) }
    }

    /** 装配 provider 前读取一次最大重试次数。 */
    suspend fun maxNetworkRetries(): Int = maxNetworkRetriesFlow.first()

    /** 备份快照：最大重试次数。 */
    suspend fun maxNetworkRetriesSnapshot(): Int = maxNetworkRetriesFlow.first()

    suspend fun restoreMaxNetworkRetries(count: Int) = setMaxNetworkRetries(count)

    /** 回车键是否直接发送消息；默认关闭（回车换行，由发送按钮发送）。 */
    val enterToSendFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[ENTER_TO_SEND_KEY] ?: false }

    suspend fun setEnterToSend(enabled: Boolean) {
        context.generalDataStore.edit { it[ENTER_TO_SEND_KEY] = enabled }
    }

    /** 备份快照：回车发送开关。 */
    suspend fun enterToSendSnapshot(): Boolean = enterToSendFlow.first()

    suspend fun restoreEnterToSend(enabled: Boolean) = setEnterToSend(enabled)

    /** sendFile 单个文件大小上限（MB）；默认 100，下限 1，不设上限。 */
    val sendFileMaxSizeMbFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[SENDFILE_MAX_SIZE_MB_KEY] ?: DEFAULT_SENDFILE_MAX_SIZE_MB).coerceAtLeast(1)
    }

    suspend fun setSendFileMaxSizeMb(mb: Int) {
        context.generalDataStore.edit { it[SENDFILE_MAX_SIZE_MB_KEY] = mb.coerceAtLeast(1) }
    }

    /** 执行 sendFile 前读取一次单个文件大小上限（MB）。 */
    suspend fun sendFileMaxSizeMb(): Int = sendFileMaxSizeMbFlow.first()

    /** 备份快照：sendFile 单个文件大小上限。 */
    suspend fun sendFileMaxSizeMbSnapshot(): Int = sendFileMaxSizeMbFlow.first()

    suspend fun restoreSendFileMaxSizeMb(mb: Int) = setSendFileMaxSizeMb(mb)

    /** 移除外部本地工作区时是否一并删除其聊天记录；默认关闭（仅解除关联、保留聊天记录）。 */
    val deleteExternalWorkspaceSessionsFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY] ?: false }

    suspend fun setDeleteExternalWorkspaceSessions(enabled: Boolean) {
        context.generalDataStore.edit { it[DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY] = enabled }
    }

    /** 移除外部工作区前读取一次该偏好。 */
    suspend fun deleteExternalWorkspaceSessions(): Boolean = deleteExternalWorkspaceSessionsFlow.first()

    /**
     * 任务待办显示位置；未设置或值无法识别时回退到 [TodoDisplayPosition.ABOVE_INPUT_BAR]，
     * 即改造前的既有位置（输入栏上方），保证老用户升级后行为不变。
     */
    val todoDisplayPositionFlow: Flow<TodoDisplayPosition> = context.generalDataStore.data.map { prefs ->
        TodoDisplayPosition.entries.firstOrNull { it.name == prefs[TODO_DISPLAY_POSITION_KEY] }
            ?: TodoDisplayPosition.ABOVE_INPUT_BAR
    }

    suspend fun setTodoDisplayPosition(position: TodoDisplayPosition) {
        context.generalDataStore.edit { it[TODO_DISPLAY_POSITION_KEY] = position.name }
    }

    /** 备份快照：任务待办显示位置名。 */
    suspend fun todoDisplayPositionSnapshot(): String = todoDisplayPositionFlow.first().name

    /** 从备份还原任务待办显示位置；旧备份无此字段（null）或值无法识别时回退输入栏上方。 */
    suspend fun restoreTodoDisplayPosition(position: String?) {
        setTodoDisplayPosition(
            TodoDisplayPosition.entries.firstOrNull { it.name == position }
                ?: TodoDisplayPosition.ABOVE_INPUT_BAR
        )
    }

    /** 「提示词」页公告已展示内容的哈希；无值表示从未展示过。 */
    val promptsAnnouncementShownHashFlow: Flow<String?> = context.generalDataStore.data.map { prefs ->
        prefs[PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY]
    }

    suspend fun markPromptsAnnouncementShown(hash: String) {
        context.generalDataStore.edit { it[PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY] = hash }
    }

    /** 备份快照：外部工作区聊天记录处理偏好。 */
    suspend fun deleteExternalWorkspaceSessionsSnapshot(): Boolean = deleteExternalWorkspaceSessionsFlow.first()

    suspend fun restoreDeleteExternalWorkspaceSessions(enabled: Boolean) = setDeleteExternalWorkspaceSessions(enabled)
}
