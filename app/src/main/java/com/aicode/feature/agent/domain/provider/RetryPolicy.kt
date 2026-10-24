package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import retrofit2.HttpException
import retrofit2.await
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * 参考 opencode 的网络请求重试策略：指数退避，仅针对瞬时故障。
 */
private const val TAG = "RetryPolicy"

// 对应 opencode 的 attempts = 3（最多 2 次重试）→ 提升至 6 次重试以应对不稳定网络
const val MAX_NETWORK_RETRIES = 6

/**
 * 每次流式尝试从发起请求到首个有效内容的等待上限，包含等待响应头。
 *
 * OkHttp 的 readTimeout 已设为无限制，首个内容之前由应用层取消请求兜底，
 * 故放宽到 5 分钟以容纳慢启动与长思考模型。
 */
const val FIRST_BYTE_TIMEOUT_MS = 300_000L

class FirstContentGuard {
    @Volatile private var close: (() -> Unit)? = null
    @Volatile private var cancelCall: (() -> Unit)? = null
    private var received = false
    private var expired = false
    @Volatile private var closed = false
    internal var timer: Job? = null

    fun attach(close: () -> Unit) {
        this.close = close
        if (closed) closeBody()
    }

    suspend fun awaitBody(call: retrofit2.Call<okhttp3.ResponseBody>): okhttp3.ResponseBody {
        cancelCall = { call.cancel() }
        if (closed) call.cancel()
        return call.await()
    }

    fun receivedContent() {
        synchronized(this) {
            received = true
            timer?.cancel()
        }
    }

    internal fun expire(): Boolean = synchronized(this) {
        if (received || expired) false else {
            expired = true
            true
        }
    }

    fun closeBody() {
        closed = true
        runCatching { cancelCall?.invoke() }
        runCatching { close?.invoke() }
    }
}

@OptIn(InternalCoroutinesApi::class)
suspend fun withFirstContentTimeout(
    timeoutMs: Long,
    block: suspend (FirstContentGuard) -> Unit
) {
    val guard = FirstContentGuard()
    val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
    try {
        coroutineScope {
            val attemptJob = coroutineContext[Job]!!
            // 阻塞 readLine 无法靠协程取消唤醒，必须在进入 cancelling 时取消底层 Call。
            val handle = attemptJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
                if (it != null) guard.closeBody()
            }
            val timer = launch(start = CoroutineStart.LAZY) {
                if (timeoutMs <= 0) return@launch
                delay(timeoutMs)
                if (guard.expire()) {
                    timedOut.set(true)
                    attemptJob.cancel(CancellationException("First content timeout"))
                }
            }
            guard.timer = timer
            timer.start()
            try {
                block(guard)
            } finally {
                timer.cancel()
                handle.dispose()
            }
        }
    } catch (e: Throwable) {
        coroutineContext.ensureActive()
        if (timedOut.get()) throw SocketTimeoutException("First content timeout after ${timeoutMs}ms")
        throw e
    }
}

/**
 * 流式响应「数据块间隔」watchdog：相邻两个数据块之间超过 [timeoutMs] 未到达即调用 [close]，
 * 使阻塞中的读取抛出 IOException，交给重试机制处理。
 *
 * 每收到一个数据块调用一次 [touch] 重新计时；流结束（正常或异常）时调用 [cancel]。
 * [timeoutMs] <= 0 表示不限制，此时两个方法均为空操作。
 */
class StreamIdleWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val close: () -> Unit
) {
    private var timer: Job? = null

    fun touch() {
        if (timeoutMs <= 0) return
        timer?.cancel()
        timer = scope.launch {
            delay(timeoutMs)
            runCatching { close() }
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
    }
}

/** 在当前协程下创建 [StreamIdleWatchdog]；[timeoutMs] <= 0 时创建的实例不做任何事。 */
suspend fun launchStreamIdleWatchdog(timeoutMs: Long, close: () -> Unit): StreamIdleWatchdog =
    StreamIdleWatchdog(CoroutineScope(coroutineContext[Job]!!), timeoutMs, close)

private val TRANSIENT_MESSAGES = listOf(
    "load failed",
    "network connection was lost",
    "network request failed",
    "failed to fetch",
    "econnreset",
    "econnrefused",
    "etimedout",
    "socket hang up"
)

/**
 * 对应 opencode 的 delay=500, factor=2, maxDelay=10000。
 *
 * 已不再作为重试等待的默认值（改为固定 [NETWORK_RETRY_DELAY_MILLIS]），保留供需要
 * 长退避的场景（如多 Key 全部限流时的兜底）使用。
 */
fun exponentialDelayMillis(retryIndex: Int): Long {
    val delay = 500L
    val factor = 2.0
    val maxDelay = 10000L
    val wait = (delay * factor.pow(retryIndex)).toLong()
    return min(wait, maxDelay)
}

/**
 * 网络类失败的重试间隔（毫秒）：**固定 3 秒**。
 *
 * 移动端网络抖动、中转站瞬时 5xx/重置通常 1~2 秒内即恢复，指数退避（0.5→1→2→4→8→10 秒）
 * 与服务端常见的 `Retry-After: 23` 都只是让用户干等。统一按 3 秒重试，配合默认 6 次重试上限，
 * 一次卡顿最多多花约 18 秒，而不是动辄半分钟以上。
 */
const val NETWORK_RETRY_DELAY_MILLIS = 3_000L

/**
 * SSE 流中收到的 error 事件，携带错误码用于重试判定。
 *
 * 对齐 Codex CLI 的错误分类：cyber_policy / invalid_request / context_window_exceeded /
 * quota_exceeded / usage_not_included 等为不可重试；server_error / server_is_overloaded 等默认可重试。
 */
class StreamApiException(
    val code: String?,
    message: String,
    val retryAfterMillis: Long? = null
) : Exception(message.ifBlank { code ?: "stream error" })

/** OpenAI's documented ChatGPT-plan limit response is not a transient API 429. */
class SubscriptionSharingUsageLimitException(
    val statusCode: Int? = null,
    val requestId: String? = null,
    val errorParam: String? = null,
    message: String
) : Exception(message)

// 对齐 Codex CLI is_retryable()：这些错误码明确不可重试。
// 限流类（rate_limit_*）在此列，是让它们优先走多 Key 自动切换；单 Key 无候选时由调用方按限流退避重试。
// 额度类（quota_exceeded / insufficient_quota / usage_* 等）不是瞬时限流，重试无益，保持不可重试。
private val NON_RETRYABLE_STREAM_CODES = setOf(
    "cyber_policy",
    "invalid_request_error",
    "invalid_request",
    "context_window_exceeded",
    "quota_exceeded",
    "usage_not_included",
    "usage_limit_reached",
    "insufficient_quota",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_image_request",
    "response_too_large"
)

/**
 * 对应 opencode 的 isTransientError，并兼容 Android 的网络异常类型。
 *
 * 除传统的 IOException 判定外，还支持 HTTP 状态码感知：
 * - 408（请求超时）、5xx（500/502/503/504 等）→ 可重试（服务端瞬时故障）
 * - 429（限流）→ 不走这里，交给多 Key 自动切换；无 Key 可切时由调用方按限流退避重试（见 [isRateLimitError]）
 * - 其他 4xx → 不重试（客户端错误，重试无意义）
 */
fun isRetriableNetworkError(t: Throwable): Boolean {
    if (t is CancellationException) return false
    if (t is SubscriptionSharingUsageLimitException) return false

    if (t is StreamApiException) {
        return !NON_RETRYABLE_STREAM_CODES.contains(t.code)
    }

    // 兼容原生网络异常
    if (t is SocketTimeoutException || t is InterruptedIOException ||
        t is java.net.UnknownHostException || t is java.net.ConnectException ||
        t is javax.net.ssl.SSLException || t is IOException) {
        return true
    }

    // HTTP 状态码感知：408/5xx 视为瞬时故障可重试；429 归多 Key 切换
    if (t is HttpException) {
        val code = t.code()
        return code == 408 || code >= 500
    }

    val message = t.message?.lowercase() ?: t.toString().lowercase()
    return TRANSIENT_MESSAGES.any { message.contains(it) }
}

/** 流内限流错误码（对齐各 provider 的 SSE error.code）。 */
private val RATE_LIMIT_STREAM_CODES = setOf(
    "rate_limit_exceeded",
    "rate_limit_error",
    "too_many_requests"
)

/** 限流错误文案兜底：HttpException 被 enrich 包成 IllegalStateException 后只能靠消息文本识别。 */
private val RATE_LIMIT_MESSAGES = listOf(
    "rate limit",
    "too many requests",
    "http 429"
)

/** 沿 cause 链查找的最大深度，与 [isKeySwitchFailure] 保持一致。 */
private const val MAX_CAUSE_DEPTH = 5

/**
 * 判断失败是否为限流类错误（HTTP 429 / 流内 rate_limit_* / 服务端限流文案）。
 *
 * 429 归多 Key 自动切换处理，故 [isRetriableNetworkError] 对它返回 false；但当没有可切换的
 * Key（单 Key）且尚未吐字时，退避重试才是正确处理——429 是瞬时限流，常带 Retry-After。
 */
fun isRateLimitError(t: Throwable): Boolean {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is HttpException -> if (current.code() == 429) return true
            is StreamApiException -> if (current.code?.lowercase() in RATE_LIMIT_STREAM_CODES) return true
        }
        current = current.cause
        depth++
    }
    val message = t.message?.lowercase() ?: return false
    return RATE_LIMIT_MESSAGES.any { message.contains(it) }
}

/** 重试错误的用户可见类别，用于 UI 展示具体原因（而非笼统的「网络波动」）。 */
enum class RetryErrorKind {
    /** HTTP 429 / 服务端 rate limit 类错误。 */
    RATE_LIMIT,
    /** HTTP 503 服务过载（或流式错误码 server_is_overloaded / overloaded）。 */
    SERVER_OVERLOADED,
    /** HTTP 5xx（除 503）等服务端错误。 */
    SERVER_ERROR,
    /** 连接/读取超时（含首字节 watchdog 触发的断流）。 */
    TIMEOUT,
    /** 目标端口无服务，连接被拒绝。 */
    CONNECTION_REFUSED,
    /** DNS 解析失败（域名不存在或网络不可达）。 */
    DNS_FAILED,
    /** 连接建立后被对端/中间设备重置（流中断、unexpected end of stream 等）。 */
    CONNECTION_RESET,
    /** TLS/SSL 握手失败。 */
    SSL_ERROR,
    /** 其它网络层故障。 */
    NETWORK,
    /** 无法归类的其它错误。 */
    UNKNOWN
}

/**
 * 一次重试对应的错误摘要，随 Retrying 事件一路透传到 UI。
 * [statusCode] 为 HTTP 状态码（如 429/500），非 HTTP 错误为 null。
 */
data class RetryErrorInfo(
    val kind: RetryErrorKind,
    val statusCode: Int? = null
)

/** 把触发重试的异常归类为 UI 可展示的错误摘要。 */
fun Throwable.toRetryErrorInfo(): RetryErrorInfo = when {
    this is HttpException -> when {
        code() == 429 -> RetryErrorInfo(RetryErrorKind.RATE_LIMIT, code())
        code() == 503 -> RetryErrorInfo(RetryErrorKind.SERVER_OVERLOADED, code())
        code() >= 500 -> RetryErrorInfo(RetryErrorKind.SERVER_ERROR, code())
        else -> RetryErrorInfo(RetryErrorKind.UNKNOWN, code())
    }
    this is StreamApiException -> when (code) {
        "rate_limit_exceeded", "rate_limit_error", "insufficient_quota" -> RetryErrorInfo(RetryErrorKind.RATE_LIMIT)
        "server_is_overloaded", "overloaded" -> RetryErrorInfo(RetryErrorKind.SERVER_OVERLOADED)
        "server_error", "internal_error" -> RetryErrorInfo(RetryErrorKind.SERVER_ERROR)
        else -> RetryErrorInfo(RetryErrorKind.UNKNOWN)
    }
    this is SocketTimeoutException || this is InterruptedIOException -> RetryErrorInfo(RetryErrorKind.TIMEOUT)
    this is UnknownHostException -> RetryErrorInfo(RetryErrorKind.DNS_FAILED)
    this is ConnectException -> RetryErrorInfo(RetryErrorKind.CONNECTION_REFUSED)
    // SSLException / ConnectException / UnknownHostException 都是 IOException 子类，必须先于 IOException 匹配
    this is SSLException -> RetryErrorInfo(RetryErrorKind.SSL_ERROR)
    this is IOException -> if (isConnectionReset(this)) {
        RetryErrorInfo(RetryErrorKind.CONNECTION_RESET)
    } else {
        RetryErrorInfo(RetryErrorKind.NETWORK)
    }
    else -> RetryErrorInfo(RetryErrorKind.UNKNOWN)
}

/** 流被对端/中间设备中断的常见报错文案，命中则归类为 [RetryErrorKind.CONNECTION_RESET]。 */
private val RESET_MESSAGES = listOf(
    "connection reset",
    "reset by peer",
    "broken pipe",
    "unexpected end of stream",
    "connection closed",
    "socket hang up",
    "econnreset",
    "stream closed",
    "eof"
)

private fun isConnectionReset(e: IOException): Boolean {
    val message = e.message?.lowercase() ?: return false
    return RESET_MESSAGES.any { message.contains(it) }
}

/**
 * 从 [HttpException] 的响应头中解析 `Retry-After`，返回等待毫秒数。
 *
 * `Retry-After` 有两种格式：
 * 1. 秒数（如 `"30"`）→ 直接转为毫秒
 * 2. HTTP 日期（如 `"Fri, 29 Jun 2026 10:00:00 GMT"`）→ 计算距当前时间的差值
 *
 * 另：SSE 流内 error 事件（[StreamApiException]）可自带 [StreamApiException.retryAfterMillis]，
 * 优先于 HTTP 头判定。
 *
 * 非上述类型或无 `Retry-After` 头部 → 返回 null。
 * 解析失败也返回 null（降级到固定 [NETWORK_RETRY_DELAY_MILLIS]）。
 */
fun extractRetryAfterMillis(t: Throwable): Long? {
    // 流内 error 事件自带的服务端要求等待时间（SSE 场景下没有 HTTP 头可读）
    if (t is StreamApiException) {
        t.retryAfterMillis?.takeIf { it > 0 }?.let { return it }
    }
    if (t !is HttpException) return null
    val header = t.response()?.headers()?.get("Retry-After") ?: return null

    // 格式 1：纯秒数
    header.toLongOrNull()?.let { seconds ->
        return seconds * 1000L
    }

    // 格式 2：HTTP 日期（RFC 1123）
    return runCatching {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val retryTime = sdf.parse(header)?.time ?: return null
        val delay = retryTime - System.currentTimeMillis()
        if (delay > 0) delay else null
    }.getOrNull()
}

/**
 * 计算重试等待时间：**固定 [NETWORK_RETRY_DELAY_MILLIS]（3 秒）**。
 *
 * 服务端 `Retry-After` 只在它比 3 秒更短时才采纳；中转站常见的 `retry in 23s` 之类长值
 * 一律按 3 秒重试——移动端等待 23 秒体验极差，而多数情况下服务端限制早已解除。
 *
 * @param retryIndex 保留参数，供未来需要区分首次/后续重试间隔时扩展。
 */
fun retryDelayMillis(retryIndex: Int, error: Throwable): Long {
    val serverDelay = extractRetryAfterMillis(error)
    if (serverDelay != null && serverDelay > 0) {
        return min(serverDelay, NETWORK_RETRY_DELAY_MILLIS)
    }
    return NETWORK_RETRY_DELAY_MILLIS
}

/**
 * 在指数退避下重试 [block]（保持原方法名），用于非流式请求。
 *
 * @param onKeyFailure 多 Key 切换回调：在判定为「不可重试」的失败时先调用，入参为
 *        (触发失败, 是否允许重发)。返回 true 表示调用方已切 Key、可重置重试计数并重发；
 *        返回 false 时，限流类失败（429 / rate_limit_*）会退避重试，其余抛出原异常。
 *        网络类失败（408/5xx/超时等）不会触发该回调。
 * @param onRetry 重试前回调，参数为 (当前重试次数, 最大重试次数)；用于通知上层"正在重试"。
 *                置于 [block] 之前以保证 `retryStaircase { ... }` 的 trailing lambda 仍绑定到 [block]。
 * @param maxRetries 最大重试次数（不含首次请求），由调用方按「偏好设置 → 网络」传入；0 表示不重试。
 */
suspend fun <T> retryStaircase(
    maxRetries: Int = MAX_NETWORK_RETRIES,
    onKeyFailure: (suspend (Throwable, Boolean) -> Boolean)? = null,
    onRetry: (suspend (attempt: Int, maxRetries: Int, error: RetryErrorInfo) -> Unit)? = null,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            if (!isRetriableNetworkError(e)) {
                if (onKeyFailure?.invoke(e, true) == true) {
                    attempt = 0
                    continue
                }
                // 无 Key 可切（单 Key）时的限流退避重试：429 是瞬时限流，等待后重试优于直接失败
                if (!isRateLimitError(e)) throw e
            }
            if (attempt >= maxRetries) throw e
            val wait = retryDelayMillis(attempt, e)
            FileLogger.w(TAG, "网络请求失败，第 ${attempt + 1}/$maxRetries 次重试（等待 ${wait}ms）: ${e.javaClass.simpleName} ${e.message}")
            onRetry?.invoke(attempt + 1, maxRetries, e.toRetryErrorInfo())
            attempt++
            if (wait > 0) delay(wait)
        }
    }
}

/**
 * 流式请求的重试封装（保持原方法名）。
 *
 * @param onKeyFailure 多 Key 切换回调：在判定为「不可重试」的失败时先调用，入参为
 *        (触发失败, 是否允许重发)。仅当本次尝试尚未收到任何内容时才会传入允许重发；
 *        已吐字时仍可能切换（供后续请求用新 Key）但不会重发，避免用户看到重复内容。
 *        返回 false 时，尚未吐字的限流类失败会退避重试，其余抛出原异常。
 * @param onRetry 重试前回调，参数为 (当前重试次数, 最大重试次数)；用于通知上层"正在重试"。
 *                回调在 delay 之前调用，确保 UI 能立即展示重试状态。声明为 suspend 以便
 *                调用方在其中通过 Flow 的 emit() 推送重试事件。
 * @param attemptOnce 单次流式请求；收到内容后调用 onContent，以判定 Key 切换时是否允许重发。
 * @param maxRetries 最大重试次数（不含首次请求），由调用方按「偏好设置 → 网络」传入；0 表示不重试。
 */
suspend fun streamWithStaircaseRetry(
    maxRetries: Int = MAX_NETWORK_RETRIES,
    onKeyFailure: (suspend (Throwable, Boolean) -> Boolean)? = null,
    attemptOnce: suspend (onContent: () -> Unit) -> Unit,
    onRetry: (suspend (attempt: Int, maxRetries: Int, error: RetryErrorInfo) -> Unit)? = null
) {
    var attempt = 0
    while (true) {
        var receivedContent = false
        try {
            attemptOnce { receivedContent = true }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            if (!isRetriableNetworkError(e)) {
                if (onKeyFailure?.invoke(e, !receivedContent) == true) {
                    attempt = 0
                    continue
                }
                // 限流且尚未吐字、又无 Key 可切（单 Key）→ 退避重试；已吐字不重发，保持原行为
                if (!(isRateLimitError(e) && !receivedContent)) throw e
            }
            if (attempt >= maxRetries) throw e
            val wait = retryDelayMillis(attempt, e)
            FileLogger.w(TAG, "流式请求失败，第 ${attempt + 1}/$maxRetries 次重试（等待 ${wait}ms）: ${e.javaClass.simpleName} ${e.message}")
            onRetry?.invoke(attempt + 1, maxRetries, e.toRetryErrorInfo())
            attempt++
            if (wait > 0) delay(wait)
        }
    }
}
