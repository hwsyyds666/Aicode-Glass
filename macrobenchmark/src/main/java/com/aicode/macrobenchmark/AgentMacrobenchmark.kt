package com.aicode.macrobenchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke benchmarks for the user-visible paths requested in the implementation manual.
 * Run on a physical device; the chat case deliberately uses the user's configured model.
 */
@RunWith(AndroidJUnit4::class)
class AgentMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        iterations = STARTUP_ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }

    /**
     * Requires completed onboarding and one working provider/model. Sends exactly one long-output
     * request per invocation, then records FrameTimingMetric while the response is streaming and
     * the user scrolls the real lazy chat list. This test is intentionally not part of CI.
     */
    @Test
    fun chatStreamingScroll() = benchmarkRule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = CHAT_ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()

            val input = waitFor(APP_PACKAGE, CHAT_INPUT_TAG)
            input.click()
            input.setText(LONG_OUTPUT_PROMPT)

            val send = waitFor(APP_PACKAGE, CHAT_SEND_TAG)
            send.click()

            assertNotNull(
                "AI 未开始生成。请完成首次引导、配置可用模型并确认服务可访问。",
                device.wait(Until.findObject(By.res(APP_PACKAGE, CHAT_STOP_TAG)), STREAM_START_TIMEOUT_MS)
            )
            Thread.sleep(STREAM_WARMUP_MS)
        }
    ) {
        val messageList = waitFor(APP_PACKAGE, CHAT_LIST_TAG)
        val stopSelector = By.res(APP_PACKAGE, CHAT_STOP_TAG)
        var measuredSwipes = 0

        repeat(SCROLL_GESTURES) {
            if (device.findObject(stopSelector) == null) return@repeat
            messageList.swipe(Direction.UP, SCROLL_DISTANCE_FRACTION)
            measuredSwipes++
            Thread.sleep(SCROLL_GAP_MS)
        }

        assertTrue("响应在滚动采样开始前就已结束；请增大模型输出长度后重跑。", measuredSwipes > 0)
    }

    private fun MacrobenchmarkScope.waitFor(packageName: String, resourceName: String): UiObject2 {
        val selector = By.res(packageName, resourceName)
        return requireNotNull(device.wait(Until.findObject(selector), ELEMENT_TIMEOUT_MS)) {
            "找不到 Compose 节点 $resourceName。请确保应用已完成引导并停留在聊天主页。"
        }
    }

    private companion object {
        const val APP_PACKAGE = "com.aicode"

        const val CHAT_INPUT_TAG = "chat_benchmark_message_input"
        const val CHAT_SEND_TAG = "chat_benchmark_send_button"
        const val CHAT_STOP_TAG = "chat_benchmark_stop_button"
        const val CHAT_LIST_TAG = "chat_benchmark_message_list"

        const val STARTUP_ITERATIONS = 5
        const val CHAT_ITERATIONS = 1 // smoke: avoid repeated network calls or usage charges
        const val ELEMENT_TIMEOUT_MS = 30_000L
        const val STREAM_START_TIMEOUT_MS = 30_000L
        const val STREAM_WARMUP_MS = 700L
        const val SCROLL_GESTURES = 8
        const val SCROLL_DISTANCE_FRACTION = 0.65f
        const val SCROLL_GAP_MS = 350L

        const val LONG_OUTPUT_PROMPT =
            "请用中文连续写一篇不少于 2500 个汉字的文章，主题是移动端代码助手的工程设计。" +
                "分成很多自然段，持续输出正文，不要使用工具，不要提前总结或省略。"
    }
}
