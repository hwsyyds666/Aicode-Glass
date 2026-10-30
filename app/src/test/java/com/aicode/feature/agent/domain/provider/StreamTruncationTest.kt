package com.aicode.feature.agent.domain.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式响应「没收到结束标记、连接就没了」时的降级交付（[StreamTruncation]）。
 *
 * 覆盖点：
 * - 判活只看正文是否非空（只有思考/工具名时降级无意义，仍走重试）
 * - 半截 JSON 入参必须被识别出来并丢弃（带残缺参数执行工具比丢掉调用危险）
 * - 降级后 stopReason 落在 [AIResponse.TRUNCATION_STOP_REASONS] 内，Agent 循环才会自动续写
 * - 已正常终止的流不会被误标截断
 */
class StreamTruncationTest {

    private fun event(json: String): JsonObject =
        JsonParser.parseString(json.replace('\'', '"')).asJsonObject

    @Test
    fun salvageableOnlyWhenTextIsNotBlank() {
        assertFalse("".isSalvageableAfterTruncation())
        assertFalse("   \n\t".isSalvageableAfterTruncation())
        assertTrue("半句话".isSalvageableAfterTruncation())
    }

    @Test
    fun completeJsonObjectIsDetected() {
        assertTrue(looksLikeCompleteJsonObject("{\"path\":\"a.txt\"}"))
        assertTrue(looksLikeCompleteJsonObject("{}"))
        // 断流时参数几乎必然停在半截
        assertFalse(looksLikeCompleteJsonObject("{\"path\":\"a.tx"))
        assertFalse(looksLikeCompleteJsonObject("{\"path\":"))
        assertFalse(looksLikeCompleteJsonObject(""))
    }

    @Test
    fun responsesTruncationMarksLengthAndSalvagesText() {
        val acc = ResponsesStreamAccumulator()
        acc.accept(event("{'type':'response.output_text.delta','delta':'已经流出的一段'}"))
        assertFalse(acc.terminated)

        acc.markNetworkTruncated()

        val response = acc.toResponse()
        assertEquals("已经流出的一段", response.content)
        // stopReason 必须命中 TRUNCATION_STOP_REASONS，否则 Agent 循环不会续写
        assertEquals(STREAM_TRUNCATED_STOP_REASON, response.stopReason)
        assertTrue(response.isTruncated)
    }

    @Test
    fun responsesAlreadyTerminatedIsNotMarkedTruncated() {
        val acc = ResponsesStreamAccumulator()
        acc.accept(event("{'type':'response.output_text.delta','delta':'完整回答'}"))
        acc.accept(
            JsonObject().apply {
                addProperty("type", "response.completed")
                add("response", JsonObject().apply { addProperty("status", "completed") })
            }
        )
        assertTrue(acc.terminated)

        // 到过终态后再置位应被忽略：正常结束不能被误标成截断
        acc.markNetworkTruncated()

        val response = acc.toResponse()
        assertFalse(response.isTruncated)
    }

    @Test
    fun responsesTruncationDropsHalfWrittenToolArguments() {
        val acc = ResponsesStreamAccumulator()
        acc.accept(event("{'type':'response.output_text.delta','delta':'我先读个文件'}"))

        val item = JsonObject().apply {
            addProperty("type", "function_call")
            addProperty("id", "fc_1")
            addProperty("call_id", "c1")
            addProperty("name", "writeFile")
        }
        acc.accept(
            JsonObject().apply {
                addProperty("type", "response.output_item.added")
                addProperty("output_index", 0)
                add("item", item)
            }
        )
        // 参数只流到一半连接就断了
        acc.accept(
            JsonObject().apply {
                addProperty("type", "response.function_call_arguments.delta")
                addProperty("output_index", 0)
                addProperty("item_id", "fc_1")
                addProperty("delta", "{\"path\":\"a.tx")
            }
        )

        acc.markNetworkTruncated()

        val response = acc.toResponse()
        assertTrue(response.isTruncated)
        // 残缺参数拿去写文件比丢掉这次调用危险得多
        assertTrue("半截入参的调用必须丢弃", response.toolCalls.isEmpty())
        assertEquals("我先读个文件", response.content)
    }

    @Test
    fun geminiInteractionsTruncationMarksLength() {
        val acc = GeminiInteractionsStreamAccumulator()
        acc.accept(
            event("{'event_type':'interaction.start','interaction':{'status':'in_progress'}}")
        )
        acc.accept(
            event(
                "{'event_type':'step.start','index':0,'step':" +
                    "{'type':'model_output','id':'s0'}}"
            )
        )
        acc.accept(
            event("{'event_type':'step.delta','index':0,'delta':'已经流出的一段'}")
        )
        assertFalse(acc.terminated)

        acc.markTruncatedByNetwork()

        val response = acc.toResponse()
        assertTrue(response.isTruncated)
        assertEquals(STREAM_TRUNCATED_STOP_REASON, response.stopReason)
    }
}
