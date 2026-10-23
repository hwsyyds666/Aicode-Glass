package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContextCompactorTest {
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val metadata = mockk<ModelMetadataService>()
    private val prompts = mockk<SystemPromptProvider>()
    private val settings = mockk<GeneralSettingsRepository>()
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)
    private val compactor = ContextCompactor(dao, metadata, prompts, records, settings, persistence)

    private fun prepare(context: Int = 16_000) {
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        every { provider.maxOutputTokens } returns 8_000
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata(id = "summary", contextTokens = context)
        coEvery { settings.compactionThresholdPercent() } returns 90
        every { prompts.resolvePrompt(any()) } returns "{{INSTRUCTION}}"
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
    }

    private fun history(size: Int = 20_000): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "old", content = "汉".repeat(size)),
        AgentMessage.AssistantMessage(id = "answer", content = "done"),
        AgentMessage.UserMessage(id = "goal", content = "continue the task")
    )

    /** 一条超长工具输出撑爆上下文的会话：绝大多数「上下文超阈值」其实是这一种。 */
    private fun toolHeavyHistory(toolChars: Int = 20_000): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "goal", content = "run the command"),
        AgentMessage.AssistantMessage(id = "call", content = "", toolCalls = listOf(ToolCall("c1", "shell", emptyMap()))),
        AgentMessage.ToolResultMessage(id = "c1", toolName = "shell", result = "R".repeat(toolChars)),
        AgentMessage.AssistantMessage(id = "reply", content = "done"),
        AgentMessage.UserMessage(id = "next", content = "continue")
    )

    @Test
    fun projectionUsesModelResultAndPreservesBothEnds() {
        val message = AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "not sent to model",
            modelResult = "BEGIN" + "x".repeat(3_000) + "END")
        val text = CompactionText.project(message)
        assertTrue(text.contains("BEGIN"))
        assertTrue(text.endsWith("END"))
        assertFalse(text.contains("not sent to model"))
        assertEquals(3_008, message.modelResult!!.length)
    }

    @Test
    fun projectionRemovesMediaAndProtocolThinkingForEveryRole() {
        val image = AgentImage("image/png", "secret-media")
        val messages = listOf(
            AgentMessage.UserMessage(content = "data:image/png;base64,c2VjcmV0", images = listOf(image)),
            AgentMessage.AssistantMessage(content = "answer", reasoning = "private-thinking", signature = "signature",
                thinkingBlocksJson = "snapshot", images = listOf(image)),
            AgentMessage.ToolResultMessage(toolName = "image", result = "{\"images\":[{\"base64Data\":\"secret-media\"}],\"value\":\"kept\"}", images = listOf(image))
        )
        val text = messages.joinToString { CompactionText.project(it) }
        assertFalse(text.contains("secret-media"))
        assertFalse(text.contains("c2VjcmV0"))
        assertFalse(text.contains("private-thinking"))
        assertFalse(text.contains("signature"))
        assertFalse(text.contains("snapshot"))
        assertTrue(text.contains("kept"))
    }

    @Test
    fun splitKeepsAllToolResultsWithAssistant() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "goal"),
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("one", "read", emptyMap()), ToolCall("two", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "one", toolName = "read", result = "one"),
            AgentMessage.ToolResultMessage(id = "two", toolName = "read", result = "two")
        )
        assertEquals(1, CompactionText.adjustSplitIndex(messages, 3))
        assertEquals(2, CompactionText.units(messages).size)
    }

    @Test
    fun splitDoesNotSeparatePreviousSummaryPair() {
        val messages = listOf(
            AgentMessage.UserMessage(content = com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER),
            AgentMessage.AssistantMessage(content = "summary"),
            AgentMessage.UserMessage(content = "continue")
        )
        assertEquals(0, CompactionText.adjustSplitIndex(messages, 1))
    }

    @Test
    fun cursorNeverDiscardsOversizedOrLeadingAssistantMaterial() {
        val source = "汉".repeat(1_000)
        val cursor = CompactionText.Cursor(listOf(source, "LAST"))
        val chunks = mutableListOf<String>()
        repeat(32) { if (!cursor.finished) chunks.add(cursor.next(100)) }
        assertTrue(cursor.finished)
        assertEquals(1_000, chunks.sumOf { chunk -> chunk.count { it == '汉' } })
        assertTrue(chunks.last().contains("LAST"))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 100 })
        assertTrue(CompactionText.units(listOf(AgentMessage.AssistantMessage(content = "leading"))).single().contains("leading"))
    }

    @Test
    fun multipleBlocksFusePreviousSummaryAndRetainLatestGoal() = runTest {
        prepare()
        val requests = mutableListOf<List<AgentMessage>>()
        coEvery { provider.complete(any(), capture(requests), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        assertTrue(requests.size > 1)
        assertTrue((requests[1].single() as AgentMessage.UserMessage).content.contains("<previous-summary>\nConcise handoff"))
        assertTrue(requests.flatten().all { it is AgentMessage.UserMessage })
        assertSame(original.last(), result.messages.last())
        coVerify { provider.maxOutputTokens = 8_000 }
        coVerify(exactly = requests.size) { records.insert(any()) }
    }

    @Test
    fun currentUsageTriggersBeforeSending() = runTest {
        prepare()
        val original = history(100)
        assertFalse(compactor.compactIfNeeded(original, provider).compacted)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        val events = mutableListOf<AgentEvent>()
        compactor.compactIfNeeded(original, provider, currentInputTokens = 99_999, onEvent = { events.add(it) })
        assertTrue(events.any { it is AgentEvent.CompactionStarted })
    }

    @Test
    fun truncatedSummaryIsKeptRatherThanDiscarded() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("partial handoff", stopReason = "length")
        val result = compactor.compactIfNeeded(history(), provider, force = true)
        assertTrue(result.compacted)
        val summary = result.messages[1] as AgentMessage.AssistantMessage
        assertTrue(summary.content.contains("partial handoff"))
        coVerify(exactly = 0) { records.insert(match { it.status == "error" }) }
    }

    @Test
    fun summaryCarriesAntiContinuationBoundary() = runTest {
        prepare()
        val result = compactor.compactIfNeeded(history(), provider, force = true)
        val summary = result.messages[1] as AgentMessage.AssistantMessage
        // 摘要是 assistant 消息且紧跟 marker，没有边界时模型会把它当成自己没写完的话续写。
        assertTrue(summary.content.startsWith(SUMMARY_HEADER))
        assertTrue(summary.content.endsWith(SUMMARY_FOOTER))
        assertTrue(summary.content.contains("Concise handoff"))
    }

    @Test
    fun failedSummaryNeverDropsHistoryOrCommits() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, sessionId = "session", force = true)
        // 没有工具输出可折时诚实失败：上下文没变小，但一条消息都不能少。
        // 旧实现在这里整段丢弃 head 并插一条占位，导致模型失去前因后产出乱码。
        assertFalse(result.compacted)
        assertEquals(original.map { it.id }, result.messages.map { it.id })
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { persistence.invalidateHistory(any()) }
    }

    @Test
    fun summaryTimeoutCountsAsFailureWithoutDroppingHistory() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } coAnswers { kotlinx.coroutines.delay(120_000) ; AIResponse("late") }
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertEquals(original.map { it.id }, result.messages.map { it.id })
    }

    @Test
    fun repeatedFailuresEnterCooldownAndStopCallingModel() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("")
        repeat(3) { compactor.compactIfNeeded(history(), provider, force = true) }
        coVerify(exactly = 3) { provider.complete(any(), any(), any(), any()) }
        val cooled = compactor.compactIfNeeded(history(), provider, force = true)
        assertFalse(cooled.compacted)
        coVerify(exactly = 3) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun microCompactAloneSolvesOverflowWithoutCallingModel() = runTest {
        prepare(context = 4_000)
        val original = toolHeavyHistory()
        val before = CompactionText.estimateRequest("", emptyList(), original)
        assertTrue("前置条件：上下文必须真的超阈值", before > 2_678)
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        assertTrue(CompactionText.estimateRequest("", emptyList(), result.messages) < before)
        // 微压缩不调模型，因此没有超时、没有失败，也就不可能走到「摘要失败丢弃历史」。
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun microCompactKeepsEveryUserAndAssistantMessageIntact() = runTest {
        prepare(context = 4_000)
        val original = toolHeavyHistory()
        val kept = compactor.compactIfNeeded(original, provider, force = true).messages
        assertEquals(original.size, kept.size)
        original.indices.forEach { index ->
            when (val message = original[index]) {
                is AgentMessage.UserMessage, is AgentMessage.AssistantMessage -> assertEquals(message, kept[index])
                is AgentMessage.ToolResultMessage -> {
                    val folded = kept[index] as AgentMessage.ToolResultMessage
                    assertEquals(message.id, folded.id)
                    // UI 与落库读的 result 保持完整，用户看到的输出一点没少。
                    assertEquals(message.result, folded.result)
                    // 只有喂模型的投影被折叠。
                    assertTrue(folded.modelResult!!.length < message.result.length)
                }
            }
        }
    }

    @Test
    fun microCompactPreservesRecentToolResultsAndToolCallPairing() = runTest {
        prepare(context = 4_000)
        // 早期一条超大输出 + 最新一条小输出：只有早期那条该被折。
        val original = listOf(
            AgentMessage.UserMessage(id = "goal", content = "run"),
            AgentMessage.AssistantMessage(id = "call0", content = "", toolCalls = listOf(ToolCall("c0", "shell", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "c0", toolName = "shell", result = "R".repeat(20_000)),
            AgentMessage.UserMessage(id = "next", content = "and again"),
            AgentMessage.AssistantMessage(id = "call1", content = "", toolCalls = listOf(ToolCall("c1", "shell", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "c1", toolName = "shell", result = "small output")
        )
        val kept = compactor.compactIfNeeded(original, provider, force = true).messages
        val early = kept[2] as AgentMessage.ToolResultMessage
        val recent = kept[5] as AgentMessage.ToolResultMessage
        assertNull("预算内的近期工具输出不应被折叠", recent.modelResult)
        assertEquals("small output", recent.result)
        assertEquals("R".repeat(20_000), early.result)
        assertNotNull(early.modelResult)
        assertTrue(early.modelResult!!.length < 20_000)
        // tool-call / tool-result 配对完整保留。
        assertEquals(listOf("c0", "c1"), kept.filterIsInstance<AgentMessage.AssistantMessage>().flatMap { it.toolCalls }.map { it.id })
    }

    @Test
    fun microCompactStubStillNamesTheToolSoItCanBeRerun() = runTest {
        // 小窗口 + 多条超长输出，逼折叠逐级加深。最近的结果受保护保持高保真，
        // 越早的被压得越狠——这与 Claude Code 的 microcompact 行为一致。
        prepare(context = 4_000)
        val original = buildList {
            add(AgentMessage.UserMessage(id = "goal", content = "run"))
            repeat(16) { index ->
                add(AgentMessage.AssistantMessage(id = "call$index", content = "",
                    toolCalls = listOf(ToolCall("c$index", "shell", emptyMap()))))
                add(AgentMessage.ToolResultMessage(id = "c$index", toolName = "shell", result = "R".repeat(200_000)))
            }
        }
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) })
        val kept = result.messages
        assertTrue(result.compacted)
        // 微压缩不调模型：没有超时、没有失败，也就不可能走到「摘要失败丢弃历史」。
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }

        val folded = kept.filterIsInstance<AgentMessage.ToolResultMessage>().mapNotNull { it.modelResult }
        assertTrue("应当有工具输出被折叠", folded.isNotEmpty())
        // 每一档都带工具名与原始长度：模型认得出是哪次调用，也拿得到是否重跑的参考。
        assertTrue(folded.joinToString("\n"), folded.all { it.contains("shell") && it.contains("200000") })
        // 越早的压得越狠：最近的结果受保护，保真度高于最早的。
        val lengths = kept.filterIsInstance<AgentMessage.ToolResultMessage>().map { (it.modelResult ?: it.result).length }
        assertTrue("最早应比最近压得更狠：$lengths", lengths.first() < lengths.last())
        // 原始输出在落库侧一条没少。
        assertTrue(kept.filterIsInstance<AgentMessage.ToolResultMessage>().all { it.result == "R".repeat(200_000) })
    }

    @Test
    fun missingPersistedAnchorFailsBeforeSummaryCall() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns emptyList()
        val original = history()
        assertSame(original, compactor.compactIfNeeded(original, provider, sessionId = "session", force = true).messages)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun commitUsesHeadIdsAndEarliestTailTimestamp() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "answer", sessionId = "session", role = "ASSISTANT", content = "done", timestamp = 200),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
        val result = compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true)
        assertTrue(result.compacted)
        coVerify(exactly = 1) {
            dao.commitCompaction("session", listOf("old"), match { it.map { row -> row.timestamp } == listOf(98L, 99L) }, any())
        }
        coVerify(exactly = 1) { persistence.invalidateHistory("session") }
    }

    @Test
    fun adjustedEmptyHeadFailsWithoutCallingModel() = runTest {
        prepare()
        val original = listOf(
            AgentMessage.AssistantMessage(id = "assistant", content = "", toolCalls = listOf(ToolCall("call", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "result")
        )
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun mainSystemBudgetIsIncludedInFinalValidation() = runTest {
        prepare()
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true, systemPrompt = "汉".repeat(20_000))
        assertFalse(result.compacted)
        assertSame(original, result.messages)
    }

    @Test
    fun latestUserMessageSurvivesCompactionEvenWhenHuge() = runTest {
        prepare()
        // 复现「压缩后 AI 忘了我刚发的内容」：最新用户消息特别长，后面还跟着一轮工具调用。
        // 旧逻辑按 recentBudget 从尾部累加，长消息撑爆预算后 split 落在它之后，
        // 最新消息被并入摘要，模型从很久以前的某句话开始思考。
        val original = listOf(
            AgentMessage.UserMessage(id = "old", content = "早期需求：重构登录模块"),
            AgentMessage.AssistantMessage(id = "a1", content = "好的，我先看看代码"),
            AgentMessage.UserMessage(id = "huge", content = "请按以下 spec 实现：" + "需求点".repeat(3_000)),
            AgentMessage.AssistantMessage(id = "call", content = "", toolCalls = listOf(ToolCall("c1", "shell", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "c1", toolName = "shell", result = "ok"),
            AgentMessage.AssistantMessage(id = "reply", content = "已完成")
        )
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        val tail = result.messages.drop(2) // marker + summary
        val latest = tail.filterIsInstance<AgentMessage.UserMessage>().lastOrNull()
        assertNotNull("最新用户消息必须原文进入保留区", latest)
        assertEquals("huge", latest!!.id)
        // 头尾保留、中间标注省略，而不是整条丢掉或整条塞进摘要。
        assertTrue(latest.content.startsWith("请按以下 spec 实现："))
        assertTrue(latest.content.contains("此处省略"))
        assertTrue(latest.content.endsWith("需求点"))
        // 工具调用链完整跟随其后，配对没有被打断（工具结果的 id 带 tool_ 前缀）。
        assertEquals(listOf("call", "tool_c1", "reply"), tail.drop(1).map { it.id })
    }

    @Test
    fun latestUserMessageKeptVerbatimWhenItFits() = runTest {
        prepare()
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        // 装得下预算时一字不改，且位于重建后上下文的末尾。
        assertSame(original.last(), result.messages.last())
    }

    @Test
    fun oversizedLatestUserMessageIsTruncatedNotDroppedOrFailed() = runTest {
        prepare()
        val original = history() + AgentMessage.UserMessage(id = "huge-tail", content = "汉".repeat(30_000))
        val result = compactor.compactIfNeeded(original, provider, force = true)
        // 旧实现这里直接判定「保留区超预算」整轮失败，用户刚发的内容永远进不了上下文。
        assertTrue(result.compacted)
        val latest = result.messages.drop(2).filterIsInstance<AgentMessage.UserMessage>().last()
        assertEquals("huge-tail", latest.id)
        assertTrue(latest.content.length < 30_000)
        assertTrue(latest.content.startsWith("汉"))
        assertTrue(latest.content.endsWith("汉"))
        assertTrue(latest.content.contains("此处省略"))
    }

    @Test
    fun clampRetainedTailOnlyTouchesTheLatestUserMessage() {
        val original = listOf(
            AgentMessage.UserMessage(id = "goal", content = "旧目标"),
            AgentMessage.AssistantMessage(id = "call", content = "", toolCalls = listOf(ToolCall("c1", "shell", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "c1", toolName = "shell", result = "R".repeat(50_000)),
            AgentMessage.UserMessage(id = "huge", content = "汉".repeat(20_000))
        )
        val clamped = CompactionText.clampRetainedTail(original, 2_000)
        assertEquals(original.map { it.id }, clamped.map { it.id })
        val huge = clamped.last() as AgentMessage.UserMessage
        assertTrue(huge.content.length < 20_000)
        assertTrue(huge.content.contains("此处省略"))
        // 其余消息一条不动。
        assertEquals("旧目标", (clamped[0] as AgentMessage.UserMessage).content)
        assertEquals("R".repeat(50_000), (clamped[2] as AgentMessage.ToolResultMessage).result)
    }

    @Test
    fun clampRetainedTailIsNoopWhenTailFits() {
        val original = listOf(
            AgentMessage.UserMessage(id = "goal", content = "短目标"),
            AgentMessage.AssistantMessage(id = "reply", content = "done")
        )
        assertSame(original, CompactionText.clampRetainedTail(original, 100_000))
    }

    @Test
    fun transactionFailureReturnsOriginal() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
        coEvery { dao.commitCompaction(any(), any(), any(), any()) } throws IllegalStateException("transaction failed")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, sessionId = "session", force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(exactly = 0) { persistence.invalidateHistory(any()) }
    }

    @Test
    fun cancellationPropagatesAndRecordsFailure() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } throws CancellationException("cancelled")
        try {
            compactor.compactIfNeeded(history(), provider, force = true)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            coVerify(exactly = 1) { records.insert(match { it.status == "error" }) }
            coVerify { provider.maxOutputTokens = 8_000 }
        }
    }

    @Test
    fun oversizedTailFailsWithoutReplacingHistory() = runTest {
        prepare()
        // 保留区里没有任何用户消息可截断（唯一一条在 index 0）、且末尾是一条超大 assistant
        // 消息时，摘要+保留区装不进输入预算：诚实失败并原样返回，不丢历史、不造假压缩。
        val original = listOf(
            AgentMessage.UserMessage(id = "goal", content = "总结一下"),
            AgentMessage.AssistantMessage(id = "a1", content = "好的"),
            AgentMessage.AssistantMessage(id = "huge", content = "汉".repeat(30_000))
        )
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
    }
}
