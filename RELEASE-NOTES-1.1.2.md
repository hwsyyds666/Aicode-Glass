# AiCode 1.1.2 发布说明

> 版本：1.1.2
> 构建：universal release（双 ABI 通用包）

## 一、聊天列表上滑加载历史 Bug 修复

### 问题描述
向上滑动到接近顶部时，第一页历史加载后无法继续加载下一页——需要往下滑一点再快速往上滑才能触发第二次加载。

### 根因
本分支在 `loadMore` 的 `snapshotFlow { firstVisibleItemIndex }` 收集器中加了 `armed` 闩锁：进近顶部一次只翻一页，翻页后 `armed = false`，只有当 `firstVisibleItemIndex > LOAD_MORE_TRIGGER_INDEX`（滑回安全区）才重新 `armed = true`。但翻页成功后新消息插入在顶部，LazyColumn 的 key 锚点把视口稳定在新页内，`firstVisibleItemIndex` 不会自然超过阈值——闩锁永远不复位。

### 修复
移除 `armed` 闩锁，回到原项目（jieapi/aicode）的简单守卫：`if (index <= 3 && ms.hasMore && !ms.isLoadingMore)`。`isLoadingMore` 已经是足够的防重入保护。

## 二、任务过程展开状态不一致 Bug 修复

### 问题描述
打开任意聊天记录时，任务过程面板是展开的；但往上翻历史消息时，展开状态突然变为收起。

### 根因
本分支引入了 `turnRunningSettled` 静置窗口（250ms）和 `onTurnFinished` 回收逻辑：
- 打开会话时 `turnRunningSettled` 从 `false` 开始，250ms 后变 `true`——如果此时 `isBusy` 为 `true`（或瞬态为 true），`activeTurnKey` 从 `null` 变为末轮 key，末轮展开
- 往上翻触发 `loadMoreMessages()`，`messages` 列表变化，`remember(messages, isBusy, turnRunningSettled)` 重新计算——中间可能有一帧 `activeTurnKey` 为 `null`，末轮收起
- `onTurnFinished` 回收逻辑在 `activeTurnKey` 变化时清除手动展开记录，进一步加剧抖动

### 修复
对照原项目回退：
- 移除 `turnRunningSettled` 静置窗口和 250ms 延迟
- 移除 `onTurnFinished` / `turnOverrideSetWhileRunning` 回收机制
- `activeTurnKey` 改为直接判断：`if (isBusy) messages.lastOrNull { ... }?.let { turnKeyOf(it.id) } else null`
- `setTurnExpanded` 移除 `whileRunning` 参数，回到原项目的简单 `turnExpansionOverrides[key] = expanded`

## 三、超级 Agent 图标样式修复

### 问题描述
未激活时图标有方形背景色和边框；激活时也是方形背景。用户期望：未激活时纯图标无背景无边框，激活时圆形背景。

### 修复
- 未激活态：无 `background`、无 `border`，只显示图标
- 激活态：`CircleShape` clip + `primary.copy(alpha = 0.16f)` 圆形背景

## 四、验证

- `compileUniversalDebugKotlin` ✓
- `testUniversalDebugUnitTest` ✓
- `assembleUniversalRelease` ✓
