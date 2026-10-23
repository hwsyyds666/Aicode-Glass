package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.workflow.AgentEvent
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * [ToolInvoker] 单元测试。重点保障「工具执行不占调用方线程」这一线上崩溃修复语义：
 * workflow 的收集方是 `viewModelScope.launch` 的 Main 单线程，若工具执行不切 IO，
 * 并行批次的阻塞 IO（su fork + waitFor / runCommandSync）会串行占死主线程。
 */
class ToolInvokerTest {

    private val toolOutputStore: ToolOutputStore = mockk<ToolOutputStore>(relaxed = true).also { store ->
        // process 原样返回输入（裁剪/落盘是 ToolOutputStore 自身职责，此处聚焦线程与包装语义）
        coEvery { store.process(any(), any(), any()) } answers { thirdArg() }
    }
    private val invoker = ToolInvoker(toolOutputStore)

    private val context = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/tmp/ws",
        language = "zh-CN"
    )

    private fun call(name: String = "Bash") = ToolCall(id = "call-1", name = name, arguments = emptyMap())

    private class FakeTool(
        override val name: String,
        private val body: suspend () -> ToolResult
    ) : AgentTool() {
        override val description = "fake"
        override val parameters: Map<String, ToolParameter> = emptyMap()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = body()
    }

    private class FakeStreamTool(
        override val name: String,
        private val events: List<ToolStreamEvent>
    ) : AgentTool(), StreamingAgentTool {
        override val description = "fake"
        override val parameters: Map<String, ToolParameter> = emptyMap()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Error("unused")
        override fun executeStream(
            args: Map<String, JsonElement>,
            context: com.aicode.feature.agent.domain.model.AgentContext
        ) = events.asFlow()
    }

    /**
     * 模拟线上调用环境：调用方是单线程「主」调度器（对应 Dispatchers.Main.immediate）。
     * 修复前工具执行占满该线程（=ANR/闪退）；修复后全部落到 IO 池线程。
     */
    @Test
    fun runSync_在单线程主调度器上也落到IO线程() = runBlocking {
        val mainSim = Executors.newSingleThreadExecutor { r -> Thread(r, "main-sim") }
        val dispatcher = mainSim.asCoroutineDispatcher()
        val execThreads = CopyOnWriteArrayList<String>()
        try {
            runBlocking(dispatcher) {
                coroutineScope {
                    (1..4).map { i ->
                        async {
                            invoker.runSync(FakeTool("Bash") {
                                execThreads.add(Thread.currentThread().name)
                                ToolResult.Success(JsonPrimitive("ok$i"))
                            }, call(), context)
                        }
                    }.awaitAll()
                }
            }
        } finally {
            mainSim.shutdownNow()
        }
        assertEquals("应执行 4 次", 4, execThreads.size)
        assertTrue(
            "任何一次工具执行都不得占用调用方线程，实际: $execThreads",
            execThreads.none { it.contains("main-sim") }
        )
    }

    /**
     * 并行性回归：4 个各阻塞 300ms 的工具，在 IO 池上应远小于串行的 1200ms。
     * 旧行为（Main 单线程串行）下总耗时必然 >= 1200ms，测试失败。
     */
    @Test
    fun runSync_并行批次在IO池上真正并发() = runBlocking {
        val startedAt = System.currentTimeMillis()
        runBlocking(Executors.newSingleThreadExecutor { r -> Thread(r, "main-sim") }.asCoroutineDispatcher()) {
            coroutineScope {
                (1..4).map {
                    async {
                        invoker.runSync(FakeTool("Bash") {
                            Thread.sleep(300)
                            ToolResult.Success(JsonPrimitive("ok"))
                        }, call(), context)
                    }
                }.awaitAll()
            }
        }
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("并行 4×300ms 应远快于串行 1200ms，实际 ${elapsed}ms", elapsed < 900)
    }

    @Test
    fun runSync_工具抛异常时包装为执行失败() = runBlocking {
        val result = invoker.runSync(FakeTool("Bash") {
            throw IllegalStateException("boom")
        }, call(), context)
        assertTrue(result.isError)
        assertTrue("应含错误码，实际: ${result.raw}", result.raw.contains("TOOL_EXECUTION_FAILED"))
        assertTrue(result.raw.contains("boom"))
    }

    @Test
    fun runSync_Cancellation异常原样穿透不包装() {
        val tool = FakeTool("Bash") { throw CancellationException("cancelled") }
        var caught: Throwable? = null
        runBlocking {
            try {
                invoker.runSync(tool, call(), context)
            } catch (e: CancellationException) {
                caught = e
            }
        }
        assertTrue("CancellationException 必须重新抛出", caught is CancellationException)
    }

    @Test
    fun runSync_工具不存在时返回未找到错误() = runBlocking {
        val result = invoker.runSync(null, call(name = "不存在的工具"), context)
        assertTrue(result.isError)
        assertTrue(result.raw.contains("TOOL_NOT_FOUND"))
    }

    /** sendFile 结果：attachments 带宿主本地路径，喂模型的 transport 串剥掉 local_path。 */
    @Test
    fun runSync_提取附件并剥离本地路径() = runBlocking {
        val files = JsonArray(listOf(JsonObject(mapOf(
            "path" to JsonPrimitive("/root/workspace/a.txt"),
            "local_path" to JsonPrimitive("/data/user/0/com.aicode/files/ws/a.txt"),
            "name" to JsonPrimitive("a.txt"),
            "mime_type" to JsonPrimitive("text/plain"),
            "size_bytes" to JsonPrimitive(12),
            "is_image" to JsonPrimitive(false)
        ))))
        val result = invoker.runSync(
            FakeTool("sendFile") {
                ToolResult.Success(JsonObject(mapOf("files" to files)))
            },
            call(name = "sendFile"),
            context
        )
        assertFalse(result.isError)
        assertEquals(1, result.attachments.size)
        assertEquals("/data/user/0/com.aicode/files/ws/a.txt", result.attachments[0].localPath)
        assertTrue("模型侧不得看到 local_path", !result.raw.contains("local_path"))
        assertTrue("应带容器路径", result.raw.contains("/root/workspace/a.txt"))
        assertTrue("应标记已附文件", result.raw.contains("files_attached"))
    }

    @Test
    fun runStream_进度事件回传且最终结果非错误() = runBlocking {
        val progressThreads = CopyOnWriteArrayList<String>()
        val events = listOf(
            ToolStreamEvent.Progress(JsonPrimitive("line-1").toString()),
            ToolStreamEvent.Progress(JsonPrimitive("line-2").toString()),
            ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive("done")))
        )
        val result = invoker.runStream(
            FakeStreamTool("Bash", events),
            call(),
            context
        ) { event ->
            if (event is AgentEvent.ToolCallProgress) {
                progressThreads.add(Thread.currentThread().name)
            }
        }
        assertFalse(result.isError)
        assertTrue(result.raw.contains("done"))
        assertTrue("进度事件应至少回传一次", progressThreads.isNotEmpty())
    }

    @Test
    fun runStream_同样不占调用方单线程() = runBlocking {
        val mainSim = Executors.newSingleThreadExecutor { r -> Thread(r, "main-sim") }
        val eventThreads = CopyOnWriteArrayList<String>()
        try {
            runBlocking(mainSim.asCoroutineDispatcher()) {
                invoker.runStream(
                    FakeStreamTool("Bash", listOf(
                        ToolStreamEvent.Progress(JsonPrimitive("x").toString()),
                        ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive("done")))
                    )),
                    call(),
                    context
                ) { event ->
                    eventThreads.add(Thread.currentThread().name)
                }
            }
        } finally {
            mainSim.shutdownNow()
        }
        assertTrue(eventThreads.isNotEmpty())
        assertTrue(
            "流式执行与事件回传都不得占用调用方线程，实际: $eventThreads",
            eventThreads.none { it.contains("main-sim") }
        )
    }

    @Test
    fun runStream_流式工具未返回Completed结果时报缺失错误() = runBlocking {
        val result = invoker.runStream(
            FakeStreamTool("Bash", listOf(ToolStreamEvent.Progress(JsonPrimitive("x").toString()))),
            call(),
            context
        ) { }
        assertTrue(result.isError)
        assertTrue(result.raw.contains("MISSING_STREAM_RESULT"))
    }
}
