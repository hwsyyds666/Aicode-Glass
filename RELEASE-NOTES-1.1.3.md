# AiCode 1.1.3 发布说明

> 版本：1.1.3
> 构建：universal release（双 ABI 通用包）

## 一、聊天记录上滑加载历史（深层根因修复）

### 问题描述
1.1.2 移除了 `armed` 闩锁，但用户反馈仍未彻底修复：划到最顶上时第一页加载后仍需"往下滑再往上滑"才能继续加载。

### 根因
`loadMoreMessages()` 缺少**去重保护**。fling 经过触发区（index 3→2→1→0）时，每越过阈值一次就 bump 一次 limit（30→60→90→120→150），`flatMapLatest` 不断取消正在跑的 Room 查询并重启——每次重启都还没来得及 emit 就被下一次 bump 取消，用户划到顶部却看不到加载结果。

### 修复
对照原项目（jieapi/aicode）补回 `loadedMessageLimits` 去重保护：
- 新增 `loadedMessageLimits: MutableMap<String, Int>`，记录"已实际加载到 UI 的 limit 值"
- `messagesState` Flow 链加 `.onEach { loadedMessageLimits[id] = limit }`：Room 查询 emit 结果时才标记该 limit 已落地
- `loadMoreMessages()` 增加守卫：`if (loadedMessageLimits[sid] != currentLimit) return`——上次 bump 的 limit 尚未落地（查询还在跑）时直接 return，不再重复 bump
- 会话删除时 `loadedMessageLimits.remove(sessionId)` 清理

## 二、任务过程展开状态不一致（深层根因修复）

### 问题描述
1.1.2 移除了 `turnRunningSettled` 静置窗口，但用户反馈仍有问题：滑动时是展开状态，只有划到"发出消息的位置"才收起，操作割裂。

### 根因
分页加载后的 **leading 消息**（窗口截断、找不到轮首用户消息的那半截过程项）缺少 turn header 包裹。`buildChatItems` 直接 `leading.forEach { items += messageRenderItems(it) }` 把它们当散消息渲染，tool calls / reasoning 等过程项全部散开显示。同时分页补齐时新消息插在已有轮首之前，导致同一轮的 key 在补齐前后变化，展开选择与 LazyColumn 锚点全丢。

### 修复
对照原项目补回 leading turn header + 稳定轮 key 机制：
- 新增 `leadingHeadKey()`：为被窗口截断的半轮创建稳定折叠头 key（取块尾消息 id，窗口继续向前补加载时块尾不变、key 保持稳定）
- `buildChatItems` 新增 `sessionId` / `turnKeys` 参数：leading 块也创建 turn header，默认收起（不创建的话过程项会全部散开）
- 新增 `reconcileTurnKeys()`：将消息 id 映射到稳定轮 key，分页补齐轮首时沿用上一轮已显示的 key，保证半轮→整轮过渡时 key 不变、展开选择与列表锚点不丢
- `splitChatTurns` 排除 compaction marker（压缩标记可能是 USER 角色的系统注入，不应作为轮起点切断正在进行的轮）
- ViewModel 新增 `turnMessageKeys` 缓存（按会话 id），composable 用 `SideEffect` 持久化

## 三、通知栏流式预览改进

### 问题描述
通知栏展开后只显示部分文字，太长不显示；流式生成时预览卡在第一段不动。

### 根因
1. 流式预览 `summarizeForNotification(event.accumulated)` 取**首行**：AI 一节正文常分多段写（"我来搜索…"换行"找到了…"换行"现在编辑…"），取首行让预览卡在第一段，用户看不到 AI 已经写到哪了
2. 展开通知 `BigTextStyle.bigText` 只取 `steps` 列表：流式生成第一段时 `steps` 为空，bigText 变成空串——展开通知什么都不显示

### 修复
- 新增 `latestParagraphForPreview()`：取累积正文里**最后一段非空行**（AI 此刻正在写的那一小段），超长从尾部截断保留段落开头。每写完一小段通知就跳到那一段——正是用户要的"每一小段落总结的时候就把那一小段总结放上去"
- `AssistantDelta` 的 livePreview 改用 `latestParagraphForPreview`（收尾后 `AssistantText` 仍用 `summarizeForNotification` 首行写入 steps，作为各节小结）
- `ensureForeground` 的 BigTextStyle 把 `livePreview` 作为最后一行拼进 `bigText`：保证展开通知始终有内容（steps 各节小结 + 当前正在写的那一小段）

## 四、验证

- `compileUniversalDebugKotlin` ✓
- `testUniversalDebugUnitTest` ✓
- `assembleUniversalRelease` ✓
