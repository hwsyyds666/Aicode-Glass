# AiCode v1.1.4 — 性能优化：消除 GC 锯齿与主线程阻塞

## 核心修复：主线程帧耗时过高导致 90Hz 锁帧 + 工具执行期间全局卡顿

### 根因分析

性能日志显示 App 中位帧耗时 12ms（120Hz 预算 8.33ms），系统自适应刷新率降档锁在 90Hz。
GPU 侧健康（p50 仅 5ms），问题全在 UI/CPU 侧。Java 堆呈标准锯齿波：~90MB/12秒一次 GC，
峰值 ~128MB = 256MB × 0.5 目标利用率。卡顿集中在堆爬升段而非峰值点。

### 修复 1：消除流式 delta 期间 3MB/秒的 String 分配风暴

**问题**：`latestParagraphForPreview(event.accumulated)` 在每个 SSE delta（~16/秒）都对
不断增长的累积正文调 `content.lines()`，创建 List + 全部行的 String 对象。
当正文增长到 100KB+（2000+ 行）时每次分配 ~200KB，16/秒 = **3-4MB/秒**——GC 锯齿的直接元凶。

**修复**：
- 将段落提取从调用端移入 `pushTaskLivePreview` 内部，**仅在通过 200ms 节流窗口后**才执行
- 新增 `extractLastParagraph()`：用 `lastIndexOf('\n') + substring` 替代 `lines()`，
  每次只创建 1 个 String 对象（原来创建 2000+ 个）
- 同步优化 `summarizeForNotification()`：同样用零分配方式取首行

**效果**：流式期间的 String 分配从 ~3MB/秒降至 ~75KB/秒（降幅 97.5%）。

### 修复 2：通知重建从主线程移至后台线程

**问题**：`TerminalKeepaliveService` 的 `serviceScope` 使用 `Dispatchers.Main.immediate`，
`taskProgressState.collect` 在主线程执行 `ensureForeground()`：构建 `NotificationCompat.Builder`
+ `PendingIntent` + `startForeground()` 系统调用。200ms 节流后仍有 ~5次/秒的主线程阻塞，
流式期间每秒吃掉 ~25ms 主线程时间——直接拉高帧耗时导致锁帧。

**修复**：
- `taskProgressState.collect` 切到 `Dispatchers.Default` 执行
- `startForeground()` 可在任意线程调用（Service 方法内部 post 到主线程）
- `sessionCount`/`persistent` 加 `@Volatile` 保证跨线程可见性

**效果**：通知重建不再占用主线程时间，帧耗时降低 ~2-5ms。

### 修复 3：PerfMonitor 帧回调无锁化

**问题**：`frameCallbackImpl` 在每帧（120Hz）执行 `synchronized(frameLock)` + `ArrayDeque<Long>.addLast()`
（Long 装箱），120 次/秒。采样线程 `drainFrameStats()` 持锁遍历时主线程阻塞。

**修复**：
- 用 `LongArray` + `@Volatile` 写入计数替代 `ArrayDeque<Long>` + `synchronized`
- 主线程只写、采样线程只读，无锁竞争
- 消除每帧 Long 装箱（~120 个/秒的短命对象）

### 修复 4：PerfMonitor native_pss_mb 恒为 0

**问题**：`readMemoryInfo()` 中 `nativePssKb` 仅在 `/proc/self/smaps_rollup` 失败时（IPC fallback 路径）
才赋值。rollup 成功时 `nativePssKb` 恒为 0，导致日志把内存增长低估了三分之一
（真实 native 堆 51MB 完全丢失）。

**修复**：rollup 成功时用 `Debug.getNativeHeapAllocatedSize()` 获取 native 堆已分配大小，
与 `dumpsys meminfo` 的 "Native Heap" private dirty 接近。

---

## 改动文件

| 文件 | 改动 |
|:--|:--|
| `AIAgentViewModel.kt` | `extractLastParagraph` 零分配替代 `lines()`；`pushTaskLivePreview` 节流后才提取；`summarizeForNotification` 零分配 |
| `TerminalKeepaliveService.kt` | `collect` 切到 `Dispatchers.Default`；`@Volatile` 保证可见性 |
| `PerfMonitor.kt` | `LongArray` + 原子索引替代 `ArrayDeque` + `synchronized`；`Debug.getNativeHeapAllocatedSize()` 修复 native_pss |

---

**下载**：[AiCode-1.1.4.apk](https://github.com/hwsyyds666/Aicode-Glass/releases/download/v1.1.4/AiCode-1.1.4.apk)
