package com.aicode.feature.terminal.domain

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aicode.MainActivity
import com.aicode.core.util.FileLogger
import com.aicode.R
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一个 AI 任务的常驻通知进度：任务标题 + 最近执行步骤（尾部截断）+ 流式实时预览。
 *
 *  **livePreview** 是当前正在流式生成的正文预览（首行截断），不进 steps 列表——
 *  每收到一个 delta 就覆盖更新，给用户实时看到 AI 正在写什么；
 *  当一节正文收尾（AssistantText）时，把小结写进 steps 并清空 livePreview。
 *  这样 steps 列表里全是「每一节的小段总结」，用户在通知栏一眼看出 AI 在做什么。 */
data class AgentTaskProgress(
    val title: String,
    val steps: List<String>,
    val livePreview: String? = null
)

@AndroidEntryPoint
class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private var sessionCount = 0

    /** 用户在设置页开启的常驻保活：为 true 时即便没有后台会话也保持前台通知。 */
    private var persistent = false

    /**
     * 服务级 WakeLock：有活跃会话时持有 PARTIAL_WAKE_LOCK，确保切后台后 CPU 不休眠、
     * SSE/网络 I/O 不会被系统挂起。所有会话结束或常驻关闭时释放。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 服务级 WifiLock：有活跃会话时持有 WIFI_MODE_FULL_HIGH_PERF，确保切后台后 Wi-Fi 无线电
     * 不进入低功耗模式。PARTIAL_WAKE_LOCK 只保 CPU 不休眠，但系统仍可能让 Wi-Fi 降频导致
     * TCP 长连接 I/O 被挂起——这是切后台 SSE 断网的核心原因。
     * 浏览器下载不受影响是因为 DownloadManager 跑在系统进程，不受应用后台限制。
     */
    private var wifiLock: WifiManager.WifiLock? = null

    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "Service created")
        // AI 任务进度驱动通知刷新：进度状态在 companion（进程级单例）里由
        // AIAgentViewModel 直接写入，服务侧订阅并重发通知。不走 startService 传值——
        // App 退后台后从 ViewModel startService 会触发 Android 8+ 的后台启动限制。
        serviceScope.launch {
            // conflate：流式 delta 期间上游每秒发射 ~16 次（每个 SSE chunk 一次），
            // ensureForeground 内部要重建 NotificationCompat + PendingIntent 并调
            // startForeground 系统调用。StateFlow 自带 conflate 语义（只保留最新值），
            // collect 在重建通知期间自动跳过中间值——避免 16 次/秒的系统调用刷爆主线程。
            // 配合下游 pushTaskLivePreview 的 200ms 时间窗口节流，通知刷新频率稳定在 ≤5 次/秒。
            taskProgressState.collect {
                if (sessionCount > 0 || persistent) ensureForeground()
            }
        }
        // 进程被系统回收后 START_STICKY 重建：persistent 是内存态会丢失。
        // 从持久化开关恢复常驻前台，否则服务空转后 stopSelf，保活形同虚设。
        serviceScope.launch {
            if (keepaliveSettings.isEnabled()) {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Restored persistent keepalive after restart")
            }
        }
    }

    override fun onDestroy() {
        releaseServiceWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉 app = 明确不要后台运行：停掉前台服务并取消 WorkManager 周期兜底，
        // 否则进程被杀后服务会被 START_STICKY 或 KeepaliveWorker 重新拉起并弹通知。
        // 开关本身保持不变，下次打开 app 时由 MainActivity / AIEditorApp 自动恢复。
        KeepaliveWorker.cancel(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Task removed, keepalive stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                sessionCount++
                ensureForeground()
                acquireServiceWakeLock()
                FileLogger.i(TAG, "Session started, count=$sessionCount")
            }
            ACTION_STOP_SESSION -> {
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                if (sessionCount == 0 && !persistent) {
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "All sessions ended, stopping service")
                } else {
                    ensureForeground()
                    if (sessionCount == 0) releaseServiceWakeLock()
                    FileLogger.i(TAG, "Session ended, count=$sessionCount, persistent=$persistent")
                }
            }
            ACTION_ENABLE_PERSISTENT -> {
                persistent = true
                ensureForeground()
                acquireServiceWakeLock()
                FileLogger.i(TAG, "Persistent keepalive enabled")
            }
            ACTION_DISABLE_PERSISTENT -> {
                persistent = false
                if (sessionCount == 0) {
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "Persistent keepalive disabled, no sessions, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Persistent keepalive disabled, sessions still running count=$sessionCount")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 进入前台并刷新通知；文案随「AI 任务进度 / 常驻保活 / 后台会话」组合变化。
     *
     * 有 AI 任务在跑时：通知标题 = 任务标题（多任务时为「N 个 AI 任务运行中」），
     * 折叠正文 = 当前执行到哪一步；展开（BigTextStyle）显示最近步骤列表——
     * 用户在通知栏就能看到 AI 的执行进度，不必回到 App。
     *
     * startForeground 在 Android 12+ 从后台启动前台服务时可能抛
     * ForegroundServiceStartNotAllowedException，此处捕获仅记录——常驻通知本次未能展示，
     * 不应让进程崩溃（设置页的开关仍是开启态，下次前台时由 [com.aicode.MainActivity] 恢复）。
     */
    private fun ensureForeground() {
        val tasks = taskProgressState.value
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        when {
            // 单个 AI 任务：标题=任务标题，正文=实时预览（流式生成中正在写的那一小段）或最近一节小结，
            // 展开（BigTextStyle）= 各节小结列表 + 当前正在写的那一小段（最后一行）。
            // 关键：流式生成第一段时 steps 列表还是空的，若 bigText 只取 steps 会变成空串——
            // 展开通知什么都不显示（用户反馈"太长不会显示"实为"没内容可显示"）。把 livePreview
            // 作为最后一行拼进 bigText，保证展开通知始终有内容。
            tasks.size == 1 -> {
                val task = tasks.values.first()
                val previewText = task.livePreview
                    ?: task.steps.lastOrNull()
                    ?: getString(R.string.notification_agent_task_preparing)
                val bigText = buildString {
                    task.steps.takeLast(MAX_TASK_STEPS_SHOWN).forEach { append(it).append('\n') }
                    if (!task.livePreview.isNullOrEmpty()) {
                        // 正在写的那一小段放最后一行（去掉 steps 已有的末尾换行，避免多空行）
                        if (isNotEmpty()) append('\n')
                        append(task.livePreview)
                    }
                }.ifEmpty { previewText }
                builder
                    .setContentTitle(task.title)
                    .setContentText(previewText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            }
            // 多个 AI 任务（根任务 + 子代理并行）：标题=N 个任务，正文=最近更新的任务的实时预览/小结，
            // 展开逐行列出每个任务的标题与当前小结
            tasks.size > 1 -> {
                val lines = tasks.values.joinToString("\n\n") { task ->
                    val step = task.livePreview
                        ?: task.steps.lastOrNull()
                        ?: getString(R.string.notification_agent_task_preparing)
                    "${task.title}\n$step"
                }
                builder
                    .setContentTitle(getString(R.string.notification_agent_tasks_running, tasks.size))
                    .setContentText(tasks.values.last().let { it.livePreview ?: it.steps.lastOrNull() } ?: "")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            }
            else -> {
                val text = when {
                    sessionCount > 0 && persistent ->
                        getString(R.string.notification_keepalive_persistent_sessions, sessionCount)
                    sessionCount > 0 ->
                        getString(R.string.notification_keepalive_running_sessions, sessionCount)
                    else -> getString(R.string.notification_keepalive_enabled)
                }
                builder
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(text)
            }
        }
        runCatching { startForeground(NOTIFICATION_ID, builder.build()) }
            .onFailure { FileLogger.e(TAG, "startForeground failed", it) }
    }

    /**
     * 获取服务级 PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF WifiLock：
     * - WakeLock 保 CPU 唤醒，防止系统挂起网络 I/O
     * - WifiLock 保 Wi-Fi 无线电高性能模式，防止切后台后 Wi-Fi 降频导致 TCP 断连
     * 两者缺一不可：只有 CPU 醒着但 Wi-Fi 休眠，SSE 仍然断。
     * 仅在有活跃会话时持有，会话全部结束时释放。
     */
    private fun acquireServiceWakeLock() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:KeepaliveService").also {
                    runCatching { it.acquire(WAKELOCK_TIMEOUT_MS) }
                        .onFailure { FileLogger.e(TAG, "acquire service wakeLock failed", it) }
                }
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = getSystemService(WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AiCode:StreamNetwork").also {
                    runCatching { it.acquire() }
                        .onFailure { FileLogger.e(TAG, "acquire wifiLock failed", it) }
                }
            }
        }
    }

    /** 释放服务级 WakeLock 和 WifiLock。 */
    private fun releaseServiceWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { FileLogger.e(TAG, "release service wakeLock failed", it) }
        }
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { FileLogger.e(TAG, "release wifiLock failed", it) }
        }
        wifiLock = null
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "terminal_service"
        /** 服务级 WakeLock 超时保险：AI 流式生成可能持续很久，给足 60 分钟。 */
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
        /** 通知展开时最多显示的步骤数（尾部保留，老的丢掉）。 */
        private const val MAX_TASK_STEPS_SHOWN = 8
        /** 每个任务在内存里最多保留的步骤数。 */
        private const val MAX_TASK_STEPS_KEPT = 12
        const val ACTION_START_SESSION = "com.aicode.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.aicode.action.STOP_SESSION"
        const val ACTION_ENABLE_PERSISTENT = "com.aicode.action.ENABLE_PERSISTENT"
        const val ACTION_DISABLE_PERSISTENT = "com.aicode.action.DISABLE_PERSISTENT"

        /**
         * AI 任务进度（进程级单例）：key = 会话 id。ViewModel 直接写入（同进程，无 IPC），
         * 服务侧订阅后刷新常驻通知。任务开始时 [updateTaskProgress] 带 title；
         * 每执行一步带 step（与上一步相同则忽略，流式 delta 不会刷爆通知）。
         */
        private val taskProgressState = MutableStateFlow<Map<String, AgentTaskProgress>>(emptyMap())

        /**
         * 注册/更新一个 AI 任务的进度。
         *
         * @param title 任务标题（非 null 时更新标题；任务开始时传）
         * @param step 一节正文收尾时的小段总结（非 null 且与最近一步不同时追加进 steps 列表）
         * @param livePreview 当前正在流式生成的正文预览（首行截断）；
         *   传 null 时不动已有预览；传 "" 时清空预览（一节收尾、切到工具调用等）。
         *   livePreview 不进 steps 列表——只覆盖通知正文，给用户实时看到 AI 正在写什么。
         */
        fun updateTaskProgress(sessionId: String, title: String?, step: String?, livePreview: String? = null) {
            val current = taskProgressState.value[sessionId]
            val nextTitle = title ?: current?.title ?: ""
            val nextSteps = when {
                step == null -> current?.steps ?: emptyList()
                current != null && current.steps.lastOrNull() == step -> current.steps
                else -> (current?.steps ?: emptyList()).let {
                    (it + step).takeLast(MAX_TASK_STEPS_KEPT)
                }
            }
            val nextPreview = when {
                livePreview == null -> current?.livePreview
                livePreview.isEmpty() -> null
                else -> livePreview
            }
            // 增量去重：title/steps/preview 三项全部未变时跳过 emission，
            // 避免 delta 期间每秒 ~16 次无意义的 Map 复制 + collect 触发。
            if (current != null &&
                current.title == nextTitle &&
                current.steps == nextSteps &&
                current.livePreview == nextPreview
            ) return
            taskProgressState.value =
                taskProgressState.value + (sessionId to AgentTaskProgress(nextTitle, nextSteps, nextPreview))
        }

        /** 任务收工：移除进度条目（服务侧通知随之回退为普通保活文案）。 */
        fun clearTaskProgress(sessionId: String) {
            if (sessionId !in taskProgressState.value) return
            taskProgressState.value = taskProgressState.value - sessionId
        }

        /**
         * 注册一个活跃会话并拉起前台服务（用于 AI 流式生成 / 后台终端）。
         * 使用 startForegroundService 而非 startService，确保 Android 8+ 从后台恢复时也能正确启动。
         */
        fun startSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_START_SESSION
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 结束一个活跃会话。会话计数归零且无常驻保活时自动停止服务。 */
        fun stopSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "stopSession startService failed", it) }
        }

        /** 开启常驻保活（幂等）。使用 startForegroundService 确保后台也能拉起。 */
        fun enablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_ENABLE_PERSISTENT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 关闭常驻保活（幂等）。仅在确曾开启过时调用，避免为关闭而凭空拉起 Service。 */
        fun disablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_DISABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "disablePersistent startService failed", it) }
        }

        /** 判断保活服务当前是否在运行。WorkManager 兜底拉起前先探测，避免反复 startService。 */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val serviceName = TerminalKeepaliveService::class.java.name
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.packageName == context.packageName && it.service.className == serviceName }
        }
    }
}
