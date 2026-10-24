package com.aicode.feature.credentials.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import com.aicode.feature.workspace.data.repository.GitHubOAuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义 git credential helper（容器内 /root/.aicode/git-credential-aicode）与 app 之间的文件 IPC 桥。
 *
 * 容器内 git 缺凭据时 helper 创建 `cred-resp-<id>` FIFO，再写 `cred-req-<id>` 到 /root/.aicode（经 PRoot -b
 * 绑定即宿主 filesDir/aicode 同一 inode）。本桥订阅 [FileChangeHub] 的目录事件捕获请求 → 暴露 [request]
 * StateFlow 供全局 Compose 弹窗 → 用户回填后 [respond] 通过 FIFO 交付 KV，helper 直接喂回 git，git 自动续跑。
 * 凭据响应不写入普通文件。三端（UI / AI Bash /
 * 交互终端）git 缺凭据都走这条统一链，无需逐命令路径适配。
 *
 * 临时对象清理：请求文件与响应 FIFO 都由 helper 在读完响应后删除；App 只向 FIFO 写入、不抢删。
 *
 * 在途计数：收到请求 [LinuxContainerEngine.incPromptInFlight]、写回响应 [decPromptInFlight]，配合
 * [LinuxContainerEngine.launchKillWatchdog] 在 helper 在途时暂停超时杀进程——用户离开几分钟填凭据，git 命令
 * 不会被 120s watchdog 强杀。
 *
 * 目录监听交给中心服务 [FileChangeHub]（inotify 跨 PRoot -b 对 app filesDir 基本可靠，但个别机型 inotify 在
 * 绑定目录可能失效），本桥另起一个低频兜底轮询协程（[fallbackPollLoop]）扫 `cred-req-*` 未处理项；两者按
 * requestId 去重防双处理。
 *
 * 生命周期：@Singleton = App 级，借 [com.aicode.AIEditorApp] 调 [start]。弹窗在任意页面（终端页/聊天页/Git 页）
 * 都能弹，不限于 Git 页。
 */
@Singleton
class CredentialRequestBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val credentialRepository: CredentialRepository,
    private val containerEngine: LinuxContainerEngine,
    private val fileChangeHub: FileChangeHub,
    private val githubOAuthRepository: GitHubOAuthRepository
) {
    private companion object {
        const val TAG = "CredentialRequestBridge"
        const val REQ_PREFIX = "cred-req-"
        const val RESP_PREFIX = "cred-resp-"
        /** 兜底轮询间隔：中心服务的 inotify 不灵时也能在 ~1s 内捕获请求。 */
        const val FALLBACK_POLL_MS = 1000L
        const val GITHUB_HOST = "github.com"
        const val RESPONSE_PIPE_TIMEOUT_MS = 5_000L
        const val MAX_RESPONSE_BYTES = 16 * 1024
        const val MAX_REQUEST_BYTES = 16 * 1024
        val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
        val HOST_PATTERN = Regex("[a-z0-9.-]{1,253}")
        /** seen 去重集容量上限，超过淘汰最旧。 */
        const val SEEN_CAP = 64
    }

    /** 当前待弹凭据请求（host 来自 git credential 协议的 host 字段）。null 表示无待处理。 */
    data class CredentialRequest(
        val requestId: String,
        val host: String
    )

    private val _request = MutableStateFlow<CredentialRequest?>(null)
    val request: StateFlow<CredentialRequest?> = _request.asStateFlow()

    /** 已处理/在途的 requestId 去重集，防事件订阅与兜底轮询双触发同一条。 */
    private val seen = LinkedHashSetWithCap(SEEN_CAP)

    private val seenMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var started = false
    private var aicodeDir: File? = null

    /**
     * 启动监听：订阅中心服务的 aicode 目录事件捕获请求，并起兜底轮询协程。幂等可重复调。
     */
    fun start() {
        val dir = File(context.filesDir, "aicode").apply { mkdirs() }
        aicodeDir = dir
        if (!started) {
            started = true
            // 请求文件落在 ~/.aicode 根下，由中心服务统一监听；本桥只做业务侧过滤、去重与弹窗。
            scope.launch {
                fileChangeHub.watchAicode().collect { batch ->
                    for (change in batch.changes) {
                        val name = File(change.hostPath).name
                        if (!name.startsWith(REQ_PREFIX) || name.endsWith(".tmp")) continue
                        handleRequest(File(dir, name), name)
                    }
                }
            }
            FileLogger.i(TAG, "已订阅 aicode 目录事件，监听 ${dir.absolutePath}")
        }
        // 兜底轮询：个别机型 -b 目录 inotify 失效时仍能取到请求；并清理上次会话残留。
        scope.launch { cleanupStale(dir); fallbackPollLoop(dir) }
    }

    /** 兜底轮询：扫未在 seen 里的 cred-req-* 文件，补中心服务漏报。App 常驻，永不退出。 */
    private suspend fun fallbackPollLoop(dir: File) {
        while (true) {
            delay(FALLBACK_POLL_MS)
            runCatching {
                dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) && !f.name.endsWith(".tmp") }
                    ?.forEach { f -> handleRequest(f, f.name) }
            }
        }
    }

    /** 启动时清理上次会话残留的 cred-req/cred-resp，避免误触发弹窗。 */
    private fun cleanupStale(dir: File) {
        runCatching {
            dir.listFiles { f -> f.name.startsWith(REQ_PREFIX) || f.name.startsWith(RESP_PREFIX) }
                ?.forEach { it.delete() }
        }
    }

    /** 解析请求文件 → 置 [request]，并 inc 在途。事件订阅与轮询都走这里，靠 seen 去重。 */
    private suspend fun handleRequest(file: File, name: String) {
        val dir = file.parentFile ?: return
        val requestId = name.removePrefix(REQ_PREFIX)
        if (!REQUEST_ID_PATTERN.matches(requestId)) return
        val fresh = seenMutex.withLock { seen.addCapped(requestId) }
        if (!fresh) return   // 已处理过，跳过
        // 文件已被 helper 删了或为空（启动残留已 cleanup，正常不该到这里），跳过。
        val text = readRequestFile(file) ?: return
        val host = text.lineSequence()
            .firstOrNull { it.startsWith("host=") }
            ?.removePrefix("host=")
            ?.trim()
            ?.lowercase()
            ?.takeIf(HOST_PATTERN::matches)
            ?: return
        // The token is encrypted at rest. Deliver it through the helper's one-shot FIFO so plaintext
        // exists only in process memory/kernel pipe buffers, never in an ordinary IPC file.
        if (host == GITHUB_HOST) {
            val token = runCatching { githubOAuthRepository.accessToken() }.getOrNull()
            if (!token.isNullOrBlank()) {
                if (writeResponsePipe(dir, requestId, "username=x-access-token\npassword=$token\n")) {
                    FileLogger.i(TAG, "已使用加密存储的 GitHub 凭据回答 helper 请求 id=$requestId")
                    return
                }
            }
        }
        containerEngine.incPromptInFlight()
        FileLogger.i(TAG, "收到凭据请求 host=$host id=$requestId")
        _request.value = CredentialRequest(requestId, host)
    }

    /**
     * 用户填完凭据确认：通过一次性 FIFO 回填，并保存用户凭据供之后的 Git 调用复用。
     * [host] 取自 [CredentialRequest]（Git 进程在收到 resp 前不会删 req，但 host 已在请求里，直接传更稳）。
     */
    fun respond(requestId: String, host: String, username: String, token: String) {
        val dir = aicodeDir ?: File(context.filesDir, "aicode")
        scope.launch {
            val safeUsername = username.trim()
            if (safeUsername.any(::isProtocolLineBreak) || token.any(::isProtocolLineBreak)) {
                FileLogger.w(TAG, "拒绝包含换行符的凭据响应 id=$requestId")
                writeResponsePipe(dir, requestId, "cancel=1\n")
                containerEngine.decPromptInFlight()
                clearIfCurrent(requestId)
                return@launch
            }
            val delivered = writeResponsePipe(dir, requestId, "username=$safeUsername\npassword=$token\n")
            runCatching {
                if (delivered) {
                    val h = host.trim().lowercase()
                    credentialRepository.save(
                        GitCredential(id = h, host = h, username = safeUsername, token = token)
                    )
                }
            }.onFailure { FileLogger.e(TAG, "保存回填凭据失败", it) }
            containerEngine.decPromptInFlight()
            clearIfCurrent(requestId)
            FileLogger.i(TAG, if (delivered) "已回填凭据 id=$requestId host=$host" else "凭据 helper 管道不可用 id=$requestId host=$host")
        }
    }

    /** 用户取消弹窗：写 cancel 响应（helper 退出非零让 git 报认证失败）+ dec 在途 + 清状态。 */
    fun cancel(requestId: String) {
        val dir = aicodeDir ?: File(context.filesDir, "aicode")
        scope.launch {
            writeResponsePipe(dir, requestId, "cancel=1\n")
            containerEngine.decPromptInFlight()
            clearIfCurrent(requestId)
            FileLogger.i(TAG, "用户取消凭据弹窗 id=$requestId")
        }
    }

    private fun clearIfCurrent(requestId: String) {
        if (_request.value?.requestId == requestId) _request.value = null
    }

    /** Read only a small regular file through a no-follow descriptor; the container owns this directory. */
    private fun readRequestFile(file: File): String? {
        val descriptor = try {
            Os.open(
                file.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                0
            )
        } catch (_: Exception) {
            return null
        }
        return try {
            val stat = Os.fstat(descriptor)
            if ((stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG ||
                stat.st_size !in 1..MAX_REQUEST_BYTES.toLong()
            ) return null
            val output = ByteArrayOutputStream(stat.st_size.toInt())
            val buffer = ByteArray(4096)
            while (true) {
                val read = Os.read(descriptor, buffer, 0, buffer.size)
                if (read <= 0) break
                if (output.size() + read > MAX_REQUEST_BYTES) return null
                output.write(buffer, 0, read)
            }
            output.toString(StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    /**
     * Write a one-shot credential response to the FIFO created by the in-container helper.
     * Verify the opened descriptor is still a FIFO before writing so a replaced path can never
     * turn this into a plaintext regular file. A missing reader or unsupported FIFO fails closed.
     */
    private suspend fun writeResponsePipe(dir: File, requestId: String, content: String): Boolean {
        val target = File(dir, RESP_PREFIX + requestId)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_RESPONSE_BYTES) return false
        val deadlineNanos = System.nanoTime() + RESPONSE_PIPE_TIMEOUT_MS * 1_000_000L
        var descriptor: java.io.FileDescriptor? = null
        while (System.nanoTime() < deadlineNanos) {
            try {
                val opened = Os.open(
                    target.absolutePath,
                    OsConstants.O_WRONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                    0
                )
                if ((Os.fstat(opened).st_mode and OsConstants.S_IFMT) != OsConstants.S_IFIFO) {
                    Os.close(opened)
                    throw IOException("credential response endpoint is not a FIFO")
                }
                descriptor = opened
                break
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ENOENT && error.errno != OsConstants.ENXIO) {
                    FileLogger.w(TAG, "打开凭据响应管道失败 id=$requestId")
                    return false
                }
                delay(25)
            } catch (_: IOException) {
                FileLogger.w(TAG, "凭据响应目标不是管道 id=$requestId")
                return false
            }
        }
        val fd = descriptor ?: run {
            FileLogger.w(TAG, "凭据响应管道等待超时 id=$requestId")
            return false
        }
        return try {
            var offset = 0
            while (offset < bytes.size) {
                try {
                    val written = Os.write(fd, bytes, offset, bytes.size - offset)
                    if (written <= 0) throw IOException("credential response pipe made no progress")
                    offset += written
                } catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN || System.nanoTime() >= deadlineNanos) throw error
                    delay(10)
                }
            }
            true
        } catch (_: Exception) {
            FileLogger.w(TAG, "写凭据响应管道失败 id=$requestId")
            false
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    private fun isProtocolLineBreak(char: Char): Boolean = char == '\n' || char == '\r' || char == '\u0000'

}

/** 带容量上限的 [LinkedHashSet]，超过 [cap] 自动淘汰最旧条目。供 [CredentialRequestBridge.seen] 去重。 */
private class LinkedHashSetWithCap(private val cap: Int) : LinkedHashSet<String>() {
    fun addCapped(id: String): Boolean {
        if (contains(id)) return false
        add(id)
        if (size > cap) {
            iterator().next().let { remove(it) }
        }
        return true
    }
}
