# AiCode v1.1.5

## 输入框草稿持久化（根因修复）

**症状**：输入框输入的文字，切换到别的页面、切换会话或做了其他操作后都会消失。

**根因**（Compose 副作用释放顺序竞态）：切会话 A→B 时，`remember(currentSessionId)` 先把 `inputText` 重置为空串；旧 `DisposableEffect` 的 `onDispose` 在**新组合之后**才执行，读到的 `latestInputText` 已是空串 → `updateInputDraft(A, "")` 直接把会话 A 的草稿清空。切页再回来同样触发该路径。

**修复**：
- 移除 `DisposableEffect` onDispose 兜底——「每次击键即存」本就是唯一事实来源，这个兜底只会清零草稿
- 切会话/切页回来直接从草稿表同步查初值（不走 flatMapLatest 异步流，杜绝旧会话草稿闪进新会话输入框）
- 回退恢复 / 编辑重发回填输入框时同步保存草稿（原来漏存）

**草稿落盘防抖（300ms）**：原来每击键一次 `SharedPreferences.apply()`（主线程内存快照克隆 + IO 线程 XML 全文件序列化磁盘写）是输入期间可感知的持续负载。现在内存 Map 即时更新，磁盘写合并到停顿 300ms 后一次。

## 全局卡顿继续治理（AI 执行工具期间的掉帧）

**流式正文/思考的 UI 发射节流（80ms）**：快模型的 delta 可达 20-50 次/秒，每次 emission 都让聊天尾部 `__active__` item 全量重组（前缀检查 + 两个 LaunchedEffect 重启 + 打字机推进），是 AI 高频输出/工具执行期间全局掉帧的持续负载源。累积文本单调增长，窗口内丢中间值零损失（下一个 delta 是超集）；打字机渲染有自适应速率，视觉无差异。重组频率从 20-50Hz 压到 ≤12.5Hz。

**停止路径不丢尾部**：被节流的最新值存 pending 暂存；「停止任务」把未完成流式内容落库时读含暂存值的最新文本，保证部分内容不缺最后几字。

**会话清理一致性**：所有流式状态的批量清理点统一走 setter，一并清掉节流簿记，杜绝残留状态。

---

- 全量改动：`AIAgentViewModel.kt`、`AIChatPanel.kt`（2 文件，+118/-25）
- 编译验证：`compileUniversalDebugKotlin` ✓ / `testUniversalDebugUnitTest` ✓ / `assembleUniversalRelease` ✓
