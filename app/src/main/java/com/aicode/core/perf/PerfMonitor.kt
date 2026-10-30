package com.aicode.core.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import android.os.SystemClock
import android.view.Choreographer
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** 单个采样点（约每 1 秒一个）。 */
data class PerfSample(
    /** epoch 毫秒。 */
    val t: Long,
    /** 采样时用户所在模块（由导航路由映射，见 [setModule]）。 */
    val module: String,
    /** 本进程 CPU 占整机算力百分比（全核合计 100%）。 */
    val cpuPct: Float,
    val pssMb: Float,
    val rssMb: Float,
    val javaHeapMb: Float,
    val javaHeapMaxMb: Float,
    val nativePssMb: Float,
    val threads: Int,
    /** 过去 1 秒渲染帧数。 */
    val fps: Int,
    /** 过去 1 秒卡顿帧数（帧间隔 > 33ms）。 */
    val jank: Int
)

/** 进程累计占用（自采样开启起）。 */
data class PerfTotals(
    val startedAt: Long = 0L,
    val samples: Int = 0,
    val frames: Long = 0L,
    val jankFrames: Long = 0L,
    val peakRssMb: Float = 0f,
    /** 进程累计 CPU 时间（毫秒，utime+stime 折算）。 */
    val totalCpuMs: Long = 0L
)

/** 整机（设备）快照。 */
data class PerfSystem(
    val memTotalMb: Float = 0f,
    val memAvailMb: Float = 0f,
    val storageTotalMb: Float = 0f,
    val storageAvailMb: Float = 0f,
    val cpuCores: Int = Runtime.getRuntime().availableProcessors()
)

/** 持久化记录文件概况。 */
data class PerfFilesInfo(
    val count: Int = 0,
    val totalBytes: Long = 0L,
    val oldest: Long = 0L,
    val newest: Long = 0L
)

/**
 * 性能采样器：为「性能面板」提供实时占用、模块聚合与长期持久化数据。
 *
 * 设计取舍（务实版）：
 * - 采样由用户手动开启（设置 → 数据与诊断 → 性能面板），每 [SAMPLE_INTERVAL_MS] 采一个点；
 * - 实时数据走内存环形缓冲（最近 [RING_MAX] 个点，够 1 小时），聚合在 UI 层现算；
 * - 长期分析靠 JSONL 落盘（`filesDir/perf/perf-yyyyMMdd.jsonl`，每日一个文件，超 8MB 自动加序号），
 *   保留最近 [KEEP_DAYS] 天，旧文件自动清理；
 * - 模块归属不打点侵入各界面，直接复用 MainActivity 的导航路由（chat/settings/terminal/…），
 *   由 [setModule] 被动接收；
 * - CPU 占用按「占整机算力百分比」计算（全核合计 100%），与系统监控工具口径一致。
 *
 * 采样本身只做 /proc 读取与一次 appendText，开销可忽略；FPS 通过 Choreographer 帧回调统计。
 */
@Singleton
class PerfMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "PerfMonitor"
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val RING_MAX = 3_600

        /**
         * 下发给 UI 的采样窗口（条数）。
         *
         * 环形缓冲保留 [RING_MAX] 用于导出/长期分析，但**不要**整份拷给 UI：
         * 每秒 toList() 一份 3600 项列表，再让 Compose 侧 groupBy 全量重算，
         * 等于每秒一次大对象分配 + O(n) 遍历——实测正是堆在 24↔136MB 间
         * 反复锯齿（GC 抖动）并掉到 15fps 的推手之一。
         * 实时面板只看最近几分钟即可，窗口定 180（3 分钟）。
         */
        const val UI_SAMPLE_WINDOW = 180
        const val KEEP_DAYS = 7
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val DIR = "perf"
        val DAY_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US)
        val TS_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        /** 卡顿阈值：帧间隔 > 33ms（约 2 个 60Hz vsync）。 */
        const val JANK_THRESHOLD_NS = 33_000_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val choreographer = Choreographer.getInstance()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _module = MutableStateFlow("chat")
    val module: StateFlow<String> = _module.asStateFlow()

    private val _samples = MutableStateFlow<List<PerfSample>>(emptyList())
    /** 最近 [RING_MAX] 个采样点，UI 每秒消费一次做实时展示与聚合。 */
    val samples: StateFlow<List<PerfSample>> = _samples.asStateFlow()

    private val _totals = MutableStateFlow(PerfTotals())
    val totals: StateFlow<PerfTotals> = _totals.asStateFlow()

    private val _system = MutableStateFlow(PerfSystem())
    val system: StateFlow<PerfSystem> = _system.asStateFlow()

    private val _filesInfo = MutableStateFlow(PerfFilesInfo())
    val filesInfo: StateFlow<PerfFilesInfo> = _filesInfo.asStateFlow()

    private val ring = ArrayDeque<PerfSample>(RING_MAX)
    private var samplingJob: Job? = null
    private var fileInfoTick = 0

    // ── FPS 帧回调 ─────────────────────────────────────────────
    //
    // 无锁环形缓冲：主线程(doFrame)只写，采样线程(drainFrameStats)只读。
    // 用 LongArray + 原子写入计数替代 ArrayDeque<Long> + synchronized：
    // 1) 消除每帧 synchronized 锁竞争(120Hz 下采样线程 drainFrameStats 持锁时主线程阻塞)
    // 2) 消除每帧 Long 装箱(ArrayDeque<Long> 每次 addLast 装箱 → ~120 个/秒的短命对象)
    @Volatile private var frameBuffer: LongArray? = null
    @Volatile private var frameWriteCount = 0  // 总写入次数(单调递增)，用于采样线程判断是否有新帧
    private var frameBufSize = 240
    private var frameCallback: Choreographer.FrameCallback? = null

    private val frameCallbackImpl = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // 已停止采样（stopFrameCallbacks 置空 frameCallback）：立即终止重挂载链条。
            // 否则停止瞬间正在执行的一次回调会把自己再 post 回去，永久每帧空转。
            if (frameCallback == null) return
            val buf = frameBuffer
            if (buf != null) {
                val idx = frameWriteCount % frameBufSize
                buf[idx] = System.nanoTime()
                frameWriteCount++
            }
            // 持续挂下一帧；停止采样时 [stopFrameCallbacks] 移除。
            choreographer.postFrameCallback(this)
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            pruneOldFiles()
            refreshFilesInfo()
            _system.value = readSystemSnapshot()
        }
    }

    /** 导航路由变化时由 MainActivity 调用。 */
    fun setModule(key: String) {
        if (_module.value != key) _module.value = key
    }

    fun setEnabled(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        samplingJob?.cancel()
        samplingJob = null
        if (on) {
            startFrameCallbacks()
            samplingJob = scope.launch { sampleLoop() }
            FileLogger.i(TAG, "性能采样已开启")
        } else {
            stopFrameCallbacks()
            FileLogger.i(TAG, "性能采样已关闭")
        }
    }

    // ── 导出 / 清理 ────────────────────────────────────────────

    /** 合并全部记录文件为单个 JSONL，返回缓存中的导出文件（由调用方决定保存位置）。 */
    suspend fun exportRawFile(): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "aicode-perf-raw-${TS_FORMAT.format(Date())}.jsonl")
        out.bufferedWriter().use { writer ->
            perfFilesSorted().forEach { f ->
                f.forEachLine { writer.appendLine(it) }
            }
        }
        out
    }

    /** 全部历史采样按模块聚合，导出 CSV（module,samples,seconds,avg_cpu_pct,avg_pss_mb,max_pss_mb）。 */
    suspend fun exportSummaryCsv(): File = withContext(Dispatchers.IO) {
        data class Agg(var samples: Int = 0, var cpuSum: Double = 0.0, var pssSum: Double = 0.0, var pssMax: Float = 0f)
        val agg = linkedMapOf<String, Agg>()
        perfFilesSorted().forEach { f ->
            f.forEachLine { line ->
                runCatching {
                    val o = JSONObject(line)
                    val m = o.optString("module", "other")
                    val a = agg.getOrPut(m) { Agg() }
                    a.samples++
                    a.cpuSum += o.optDouble("cpu_pct", 0.0)
                    val pss = o.optDouble("pss_mb", 0.0).toFloat()
                    a.pssSum += pss
                    if (pss > a.pssMax) a.pssMax = pss
                }
            }
        }
        val out = File(context.cacheDir, "aicode-perf-summary-${TS_FORMAT.format(Date())}.csv")
        out.bufferedWriter().use { w ->
            w.appendLine("module,samples,seconds,avg_cpu_pct,avg_pss_mb,max_pss_mb")
            agg.toSortedMap().forEach { (m, a) ->
                val avgCpu = if (a.samples > 0) a.cpuSum / a.samples else 0.0
                val avgPss = if (a.samples > 0) a.pssSum / a.samples else 0.0
                w.appendLine("$m,${a.samples},${a.samples},${"%.2f".format(avgCpu)},${"%.1f".format(avgPss)},${"%.1f".format(a.pssMax)}")
            }
        }
        out
    }

    /** 清空全部持久化记录（内存环形缓冲保留）。 */
    fun clearHistory() {
        scope.launch(Dispatchers.IO) {
            perfDir().listFiles()?.forEach { it.delete() }
            refreshFilesInfo()
        }
    }

    // ── 采样主循环 ─────────────────────────────────────────────

    private suspend fun sampleLoop() {
        var lastSelfTicks = readSelfTicks()
        var lastDeviceTotal = readDeviceTotalTicks()
        var lastWallMs = SystemClock.elapsedRealtime()
        var lastCpuMs = selfCpuMs()
        while (scope.isActive && _enabled.value) {
            delay(SAMPLE_INTERVAL_MS)
            val nowWall = SystemClock.elapsedRealtime()
            val wallDeltaMs = (nowWall - lastWallMs).coerceAtLeast(1)
            lastWallMs = nowWall

            val selfTicks = readSelfTicks()
            val deviceTotal = readDeviceTotalTicks()
            val selfDelta = (selfTicks - lastSelfTicks).coerceAtLeast(0)
            val rawDeviceDelta = (deviceTotal - lastDeviceTotal).coerceAtLeast(0)
            lastSelfTicks = selfTicks
            lastDeviceTotal = deviceTotal
            // /proc/stat 在部分设备读不到或恒返回 0（实测：OnePlus Android 16），
            // 此时拿它当分母会把 CPU% 放大上百倍——用户导出的数据里出现 6600%，
            // 实际只是 selfDelta=66 ticks。分母不可信时退回「核数 × ticksPerSecond」
            // 的整机容量，保证数值落在 0..(核数×100)% 的合理区间。
            val capacityTicks = (
                Runtime.getRuntime().availableProcessors().coerceAtLeast(1) *
                    ticksPerSecond().toLong()
                ).coerceAtLeast(1L)
            val deviceDelta =
                if (rawDeviceDelta in 1 until capacityTicks * 8) rawDeviceDelta else capacityTicks
            val cpuPct = selfDelta * 100f / deviceDelta

            val mem = readMemoryInfo()
            val (fps, jank) = drainFrameStats()

            val sample = PerfSample(
                t = System.currentTimeMillis(),
                module = _module.value,
                cpuPct = cpuPct,
                pssMb = mem.totalPssMb,
                rssMb = mem.rssMb,
                javaHeapMb = mem.javaHeapMb,
                javaHeapMaxMb = mem.javaHeapMaxMb,
                nativePssMb = mem.nativePssMb,
                threads = mem.threads,
                fps = fps,
                jank = jank
            )

            ring.addLast(sample)
            while (ring.size > RING_MAX) ring.removeFirst()
            // 只把最近 UI_SAMPLE_WINDOW 条交给 UI，避免每秒拷贝整条 3600 项缓冲
            // （见 UI_SAMPLE_WINDOW 注释：这是 GC 锯齿/掉帧的推手）。
            _samples.value = if (ring.size <= UI_SAMPLE_WINDOW) {
                ring.toList()
            } else {
                ring.drop(ring.size - UI_SAMPLE_WINDOW)
            }

            val nowCpuMs = selfCpuMs()
            val cpuMsDelta = (nowCpuMs - lastCpuMs).coerceAtLeast(0)
            lastCpuMs = nowCpuMs
            _totals.value = _totals.value.let {
                PerfTotals(
                    startedAt = if (it.startedAt == 0L) sample.t else it.startedAt,
                    samples = it.samples + 1,
                    frames = it.frames + fps,
                    jankFrames = it.jankFrames + jank,
                    peakRssMb = maxOf(it.peakRssMb, sample.rssMb),
                    totalCpuMs = it.totalCpuMs + cpuMsDelta
                )
            }

            withContext(Dispatchers.IO) {
                runCatching { appendToFile(sample) }
                    .onFailure { FileLogger.w(TAG, "性能记录写入失败: ${it.message}") }
                if (++fileInfoTick % 60 == 0) {
                    _system.value = readSystemSnapshot()
                    refreshFilesInfo()
                }
            }
        }
    }

    // ── 底层读取 ───────────────────────────────────────────────

    private data class MemInfo(
        val totalPssMb: Float,
        val nativePssMb: Float,
        val rssMb: Float,
        val javaHeapMb: Float,
        val javaHeapMaxMb: Float,
        val threads: Int
    )

    private fun readMemoryInfo(): MemInfo {
        // 优先 /proc/self/smaps_rollup：纯文件读取、无 binder IPC，PSS 实时准确。
        // 原先每秒调一次 ActivityManager.getProcessMemoryInfo——那是同步 IPC，
        // 系统繁忙时会阻塞采样线程；且部分设备返回缓存值，实测 PSS 恒定 350.7MB
        // 一动不动，完全失去观测意义。仅在 rollup 不可用时回退到 IPC。
        var totalPssKb = readRollupKb("Pss:")
        var nativePssKb = 0L
        if (totalPssKb <= 0) {
            runCatching {
                val info = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()
                if (info != null) {
                    totalPssKb = info.totalPss.toLong()
                    nativePssKb = info.nativePss.toLong()
                }
            }
        } else {
            // smaps_rollup 成功：native PSS 从中读不到（该文件无 "Native" 字段）。
            // 用 Debug.getNativeHeapAllocatedSize() 获取 native 堆已分配大小作为近似值——
            // 与 dumpsys meminfo 的 "Native Heap" private dirty 接近，远比恒 0 有用。
            // 之前此处不赋值导致 nativePssKb 恒为 0，日志把内存增长低估了三分之一。
            nativePssKb = Debug.getNativeHeapAllocatedSize() / 1024L
        }
        val rt = Runtime.getRuntime()
        val usedHeap = rt.totalMemory() - rt.freeMemory()
        return MemInfo(
            totalPssMb = totalPssKb / 1024f,
            nativePssMb = nativePssKb / 1024f,
            rssMb = readProcStatusKb("VmRSS:") / 1024f,
            javaHeapMb = usedHeap / (1024f * 1024f),
            javaHeapMaxMb = rt.maxMemory() / (1024f * 1024f),
            threads = readProcStatusKb("Threads:").toInt()
        )
    }

    /** 读 /proc/self/smaps_rollup 中 `Pss:  1234 kB` 形式的字段（KB，无 IPC）。 */
    private fun readRollupKb(key: String): Long {
        return runCatching {
            File("/proc/self/smaps_rollup").useLines { lines ->
                lines.firstOrNull { it.startsWith(key) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ')
                    ?.firstOrNull()
                    ?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun readProcStatusKb(key: String): Long {
        return runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith(key) }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ')
                    ?.firstOrNull()
                    ?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)
    }

    /** /proc/self/stat 的 utime+stime（clock ticks）。 */
    private fun readSelfTicks(): Long {
        return runCatching {
            val line = File("/proc/self/stat").readText()
            val after = line.substringAfterLast(')').trim()
            val t = after.split(Regex("\\s+"))
            // after 以 field3(state) 开头，utime=field14 → 下标 11，stime=field15 → 下标 12。
            (t[11].toLongOrNull() ?: 0L) + (t[12].toLongOrNull() ?: 0L)
        }.getOrDefault(0L)
    }

    /** 进程累计 CPU 毫秒（自进程启动）。 */
    private fun selfCpuMs(): Long = readSelfTicks() * 1000L / ticksPerSecond()

    /** /proc/stat 第一行全部核的累计 ticks（user+nice+system+idle+iowait+irq+softirq+steal）。 */
    private fun readDeviceTotalTicks(): Long {
        return runCatching {
            File("/proc/stat").bufferedReader().use { reader ->
                val line = reader.readLine() ?: return 0L
                line.removePrefix("cpu").trim().split(Regex("\\s+"))
                    .sumOf { it.toLongOrNull() ?: 0L }
            }
        }.getOrDefault(1L)
    }

    private fun readSystemSnapshot(): PerfSystem {
        var memTotal = 0L
        var memAvail = 0L
        runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.forEach { l ->
                    when {
                        l.startsWith("MemTotal:") -> memTotal = l.filterKb()
                        l.startsWith("MemAvailable:") -> memAvail = l.filterKb()
                    }
                }
            }
        }
        var storageTotal = 0L
        var storageAvail = 0L
        runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            storageTotal = stat.totalBytes
            storageAvail = stat.availableBytes
        }
        return PerfSystem(
            memTotalMb = memTotal / 1024f,
            memAvailMb = memAvail / 1024f,
            storageTotalMb = storageTotal / (1024f * 1024f),
            storageAvailMb = storageAvail / (1024f * 1024f)
        )
    }

    private fun String.filterKb(): Long =
        substringAfter(':').trim().split(' ').firstOrNull()?.toLongOrNull() ?: 0L

    private fun drainFrameStats(): Pair<Int, Int> {
        val now = System.nanoTime()
        val window = 1_000_000_000L
        val buf = frameBuffer ?: return 0 to 0
        val totalWrites = frameWriteCount  // 读取原子快照：写入线程不会修改已写的元素
        if (totalWrites == 0) return 0 to 0
        // 环形缓冲已写入的元素数 = min(totalWrites, bufSize)
        val written = minOf(totalWrites, frameBufSize)
        val baseIdx = totalWrites - written  // 最老元素的逻辑序号
        var frames = 0
        var jank = 0
        var prev = 0L
        for (i in 0 until written) {
            val t = buf[(baseIdx + i) % frameBufSize]
            if (t == 0L) continue  // 未写入的槽位
            if (now - t > window) continue  // 超过 1 秒的帧不计
            if (prev != 0L && t - prev > JANK_THRESHOLD_NS) jank++
            prev = t
            frames++
        }
        return frames to jank
    }

    // ── 持久化 ─────────────────────────────────────────────────

    private fun perfDir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun perfFilesSorted(): List<File> =
        perfDir().listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name } ?: emptyList()

    private fun appendToFile(sample: PerfSample) {
        val day = DAY_FORMAT.format(Date(sample.t))
        var f = File(perfDir(), "perf-$day.jsonl")
        var idx = 2
        while (f.exists() && f.length() > MAX_FILE_BYTES) {
            f = File(perfDir(), "perf-$day-$idx.jsonl")
            idx++
        }
        f.appendText(sample.toJson().toString() + "\n")
    }

    private fun pruneOldFiles() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        perfFilesSorted().forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    private fun refreshFilesInfo() {
        val files = perfFilesSorted()
        _filesInfo.value = PerfFilesInfo(
            count = files.size,
            totalBytes = files.sumOf { it.length() },
            oldest = files.minOfOrNull { it.lastModified() } ?: 0L,
            newest = files.maxOfOrNull { it.lastModified() } ?: 0L
        )
    }

    private fun startFrameCallbacks() {
        if (frameCallback != null) return
        frameBuffer = LongArray(frameBufSize)
        frameWriteCount = 0
        frameCallback = frameCallbackImpl
        runCatching { choreographer.postFrameCallback(frameCallbackImpl) }
    }

    private fun stopFrameCallbacks() {
        val cb = frameCallback ?: return
        runCatching { choreographer.removeFrameCallback(cb) }
        frameCallback = null
        frameBuffer = null
        frameWriteCount = 0
    }

    private fun PerfSample.toJson(): JSONObject = JSONObject().apply {
        put("t", t)
        put("module", module)
        put("cpu_pct", cpuPct.toDouble())
        put("pss_mb", pssMb.toDouble())
        put("rss_mb", rssMb.toDouble())
        put("java_heap_mb", javaHeapMb.toDouble())
        put("java_heap_max_mb", javaHeapMaxMb.toDouble())
        put("native_pss_mb", nativePssMb.toDouble())
        put("threads", threads)
        put("fps", fps)
        put("jank", jank)
    }
}

/** clock ticks/秒：优先 sysconf(_SC_CLK_TCK)，取不到按 Linux 常见值 100 兜底。 */
private fun ticksPerSecond(): Long = runCatching {
    android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
}.getOrDefault(100L)
