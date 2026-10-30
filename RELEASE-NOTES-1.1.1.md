# AiCode 1.1.1 发布说明

> 版本：1.1.1
> 构建：universal release（双 ABI 通用包）

## 一、付费模型路由 Bug 修复

### 问题描述
用户在模型选择器中选择付费专属模型（如 GPT-6 Sol、GPT-6 Astra）时，系统没有自动路由到付费层级账号，而是可能落到免费账号——上游会直接拒绝，用户看到"模型不可用"错误。

### 根因（两层）

**运行时层**：`resolveAccountTarget` / `isTierBlocked` / `routeViaAffinityOrFallback` 在检查模型的 `paidOnly` 标记时，直接从账号的 `modelCatalogJson` 解析目录。但当账号目录为空（尚未拉取或拉取失败）时，`firstOrNull` 返回 null，`paidOnly` 检查被跳过——免费账号被当作付费模型的有效路由目标。

**修复**：三处检查统一改为：先从账号目录找，找不到时回退到内置兜底目录 `ACCOUNT_MODELS_CATALOG` 查 `paidOnly` 标记。内置目录已覆盖所有已知模型的档位归属，确保即使在线目录缺失也不会误路由。

**UI 层**：模型选择器聚合组（ChatGPT 账号组）选中付费模型时，`accountModelInfo` 的 `planType` 为 null（聚合组按 providerIds 展开，但 providerId → planType 的映射依赖账号目录已加载）。`planType` 为 null 时付费模型匹配逻辑失败，落到 `entry.providerIds.first()`（可能是免费号）。

**修复**：`SettingsViewModel` 和 `AIAgentViewModel` 的 `accountModelInfo` 在 `account.models` 为空时回退到内置兜底目录填充 `paidOnlyBySlug`；`DefaultModelsSection` 选中聚合组模型时，`planType` 为 null 的账号按保守策略处理（付费模型排除 null planType 账号，免费模型优先匹配 free planType）。

## 二、绝版模型过滤

### 问题描述
部分已下架/绝版的模型（如 `gpt-reserve`）仍出现在模型选择器中，用户选中后无法使用。

### 修复
- 内置兜底目录移除 `gpt-reserve` 等绝版条目。
- 新增 `DEPRECATED_MODEL_SLUGS` / `DEPRECATED_MODEL_STATUSES` 常量集，`fetchModelCatalog` 解析在线目录时按 `status` 字段过滤掉 `discontinued` / `deprecated` / `sunset` / `end_of_life` 标记的条目。
- `decodeModelCatalog`（从数据库加载已存储目录时）同样过滤绝版 slug，确保旧版存储数据里的绝版模型不再展示。

## 三、通知栏任务进度改进

### 问题描述
通知栏显示的是 AI 调用了什么工具（如"正在执行 read_file"），用户看不出 AI 到底在做什么。用户期望的是每一节的**小段总结**——AI 当前在思考什么、在生成什么回复，一目了然。

### 改进
- **实时预览**：`AssistantDelta`（流式正文）和 `ReasoningDelta`（思考链）事件把当前生成中的文本截取前 80 字作为实时预览，直接写入通知正文。用户展开通知就能看到 AI 正在写什么内容。
- **段落小结**：`AssistantText`（一节回复完成）事件把完整回复截取前 120 字作为小结，追加到通知步骤列表。通知展开后按时间顺序显示 AI 各阶段的小结，像一份执行日志。
- **工具调用不再刷屏**：`ToolCallStarted` 不再往通知 steps 里推工具名，改为清空实时预览让上一节小结继续展示。工具调用本身不影响通知进度条。
- `AgentTaskProgress` 新增 `livePreview` 字段，`ensureForeground` 通知正文优先展示 `livePreview`，无预览时回退到最近一步小结。

## 四、搜索超时统一为 2 分钟

### 改动
- `WebSearchTool`：连接超时 15s → 30s，读取超时 30s → 120s（2 分钟）。Parallel AI 搜索接口是 AI 驱动的，响应时间较长，30s 经常超时。
- `WebFetchTool`：页面抓取超时 15s → 120s（2 分钟）。部分网页响应慢或页面较大，15s 不够。
- `SearchCodeTool`（ripgrep 代码搜索）：30s → 120s。大型代码库或复杂正则搜索可能超过 30s。

## 五、超级 Agent 主动并行派发

### 问题描述
用户开启"超级 Agent"后，AI 仍然不主动派发子代理并行调查——只有用户明确说"用子代理"时才派。与设计预期不符：开启超级 Agent 就应该意味着用户期望 AI 主动拆分并行。

### 根因
`super-agent.md` 系统提示词过于保守，反复强调"15 倍 token 成本""不要为了并行而制造子任务""只有预期收益明显更高时才拆分"，导致 AI 默认不拆。`TaskTool` 描述也以"仅在并行收益显著高于约 15 倍 token 成本时派发"开头，门槛极高。

### 修复
- **重写 `super-agent.md`**：从"保守不派"转为"主动并行派发"。核心原则改为：
  - 研究/调查类任务**默认并行**——可按主题或目录切分的就拆成多个 `research` / `explore` 并行。
  - 多文件/多模块改动按目录边界拆多个 `worker`，各持不相交可写范围。
  - 阈值要低：只要能识别出两个以上独立方向，就应该并行。
  - 移除"15 倍 token 成本"门槛——用户开启超级 Agent 即隐含接受并行成本。
  - 保留安全约束：可写范围不重叠、派发后审查汇总、PLAN 模式不绕过。
- **更新 `TaskTool` 描述**：开头从"仅在……显著高于约 15 倍 token 成本时派发"改为"当任务存在可并行的独立方向时主动拆分派发"。

## 六、UI 瞬时卡顿修复

### 问题描述
操作 APP 时（展开工具详情卡片、滑动工作区文件列表）出现瞬时卡死/卡顿。

### 根因
**工具卡片组合期同步 JSON 解析**：`ToolMessageBody` 在 `remember { }` 块中同步执行 7 处 JSON 解析（`parseEditDiff`、`formatToolResult`、`parseTodoResult`、`parseWebSearchResult`、`parseToolNotifications`、`toolArgHint`、`formatToolArgs`）。工具结果内容可达数万字符，滚动时每个工具卡片首次滚入视口都会在主线程上同步解析——单个卡片 10-50ms，多卡片叠加导致明显卡顿。**折叠态也照样全量解析**，即使不看详情也付了全套解析代价。

### 修复
- **异步解析（`produceState` + `Dispatchers.Default`）**：`parseEditDiff`、`parseTodoResult`、`parseToolNotifications` 从主线程 `remember{}` 改为后台线程 `produceState{}.value`——首帧渲染时值为 null/空，解析完成后（1-2 帧后）自动更新 UI（DiffStat 徽章、待办计数、通知行延迟一帧出现）。滚动时不再因首次组合而卡顿。
- **懒解析（仅展开时解析）**：`formatToolResult`（结果文本）和 `parseWebSearchResult`（网页搜索卡片）只在 `effectiveExpanded = true` 时才解析——折叠态零开销。
- **`expandable` 轻量判定**：不再依赖解析结果判断是否可展开（避免异步值为 null 时误判不可展开），改为"工具已结束且有内容/参数即可展开"的纯文本检查。
- **DiffView 统计缓存**：`lines.count { ... }`（增删行统计）从每次重组遍历全部行改为 `remember(lines)` 缓存，展开大差异卡时不再重复扫描。
- **`TOOL_SECTION_CHAR_LIMIT` 降低**：16,000 → 8,000 字符。`Text` 组件按全文做布局测量（O 字符数），16K 字符的卡片滚入视口时单次布局可达 10ms+，降到 8K 在保证可读性的前提下显著降低布局开销。
- **`DelegatingFileAccess.listFilesForBrowse` 转发**：委托层缺失该覆写，导致文件浏览退化为完整 `listFiles`（每个文件都做 `stat` 系统调用取 size/lastModified/permissions）。补上转发后，大目录浏览跳过这些系统调用，加载更流畅。

## 七、生图模型 404 修复

### 问题描述
用户在「设置 → 默认模型 → 生图模型」中选择了模型，但 AI 调用 `generateImage` 时返回 404 错误提示"未选择模型"。

### 根因
模型选择器展示所有供应商的模型，包括 ChatGPT 账号。ChatGPT 账号的 `apiKey` 是标记型字符串（`aicode-openai-account:<accountId>`），`baseUrl` 指向 Codex 后端——**Codex 后端不存在 `/v1/images/generations` 端点**，请求直接返回 404。

### 修复
- **UI 层过滤**：生图模型选择器（`ModelSelectionSheet`）传入的 `providers` 列表过滤掉 ChatGPT 账号供应商，用户只能选择标准 API 供应商的图像模型。
- **运行时防御**：`GenerateImageTool.resolveImageGenProvider()` 增加 `isAccountProvider` 检查——如果已存储的 providerId 对应 ChatGPT 账号（此修复前选的），返回清晰的错误提示引导用户重新选择，而不是直接打 404。

## 八、全局性能优化

### 通知栏进度刷新节流
1.1.0 引入的通知栏任务进度在流式 delta 期间每秒触发 ~16 次 `ensureForeground` → `startForeground`（通知重建 + 系统调用），是 1.1.0 后全局卡顿的直接元凶。

- **`conflate()` 合并**：`taskProgressState.collect` 加 `conflate()`，跳过中间值，collect 协程只处理最新一次 emission。
- **`updateTaskProgress` 增量去重**：`livePreview` 与当前值相同时不触发 emission，避免无效 Map 复制。
- **`pushTaskLivePreview` 时间窗口节流**：同一会话 200ms 内只推一次预览更新。200ms = 人眼体感阈值，再快也分不清。

### 流式 StateFlow 增量去重
- `setStreamingText` / `setStreamingReasoning` 加 `if (text == current) return`：delta 期间每秒 ~16 次 SSE chunk 触发的 Map copy-on-write + StateFlow emission + Compose 重组，相同值时直接跳过。
- `setStreamingReasoning` 修复 bug：清空 reasoning 文本时误清 `_reasoningTimings`（timings 应在 `finishReasoning` / 会话重置时管理）。
- `ReasoningDelta` 的 `_reasoningTimings` 写入只在 `currentReasoningStart` 首次设置时执行一次，delta 期间不再重复写相同值。

## 九、验证

- `compileUniversalDebugKotlin` ✓
- `testUniversalDebugUnitTest` ✓
- `assembleUniversalRelease` ✓
