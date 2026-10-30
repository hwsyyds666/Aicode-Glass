package com.aicode.feature.agent.domain.provider

import com.aicode.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException

private const val TAG = "StreamTruncation"

/**
 * 流式响应「还没收到结束标记、连接就没了」时的降级交付标记。
 *
 * 取值必须落在 [AIResponse.TRUNCATION_STOP_REASONS] 内（这里用 `length`）：这样
 * [AIResponse.isTruncated] 为真，Agent 循环会自动补一条续写请求把回答接上，
 * 而不是把整轮判成失败、让用户重发一次。
 *
 * 覆盖的断流场景：对端直接 EOF（代理/CDN 提前关连接）、移动网络切换、中转站单方面断开。
 * 各家协议的结束标记：Anthropic `message_stop`、OpenAI Chat `[DONE]`、
 * Responses API `response.completed`、Gemini Interactions 的终态 status。
 */
internal const val STREAM_TRUNCATED_STOP_REASON = "length"

/**
 * 断流时是否值得降级交付：只有正文非空才算。
 *
 * 一点正文都没有（连接刚建好就断，或只收到思考/工具名）时降级没有意义，仍抛 [IOException]
 * 走原有的重试 / 多 Key 切换 / 报错流程——此时重发既安全又可能拿到完整回答。
 */
internal fun CharSequence.isSalvageableAfterTruncation(): Boolean = isNotBlank()

internal fun logStreamTruncated(provider: String, chars: Int, lines: Int) {
    FileLogger.w(
        TAG,
        "$provider 流式响应在结束标记前断流：已收到 $lines 行 / $chars 字符，" +
            "降级为截断输出（stopReason=$STREAM_TRUNCATED_STOP_REASON），由 Agent 循环自动续写"
    )
}

/**
 * 工具调用的入参是否看起来完整。
 *
 * 入参是逐片拼接的，断流时几乎必然停在半截 JSON——带着残缺参数去执行工具（写文件、跑命令）
 * 比丢掉这次调用危险得多。故断流时只保留能解析成一个完整 JSON 对象的调用：
 * 半截 JSON 一定解析失败，而 `step.stop` / `arguments.done` 收过尾的必然解析成功。
 */
internal fun looksLikeCompleteJsonObject(raw: String): Boolean {
    val text = raw.trim()
    if (!text.startsWith("{") || !text.endsWith("}")) return false
    return runCatching { Json.parseToJsonElement(text).jsonObject }.isSuccess
}
