# AiCode v1.1.7 — 聊天页卡顿修复（GPU 玻璃减压）

## 修了什么

两个卡顿场景，同一类根因：**液态玻璃的 AGSL 全屏采样是每帧在跑的 GPU 大户**。

### 1. 发送消息后明显掉帧

流式输出时打字机每 ~48ms 让消息列表（玻璃的 backdrop）失效一次，玻璃 shader 每帧重跑全屏模糊 + 折射 + 色散。GPU 被玻璃占满，消息列表的绘制和滚动就只能排队 → 卡。

**修复**：AI 忙碌期间（流式输出 / 工具执行），三处玻璃（标题栏 / 输入区 / 待办浮层）与 backdrop 录制全部停掉，降级为静态容器。闲时自动恢复，观感不变。忙碌时你的注意力在消息内容上，玻璃本来就是被牺牲的那个。

### 2. 打开模型选择器 / 调整思考强度卡一下

底部 sheet 是独立窗口，打开期间**底层窗口还要整帧渲染**。玻璃 AGSL 继续在底层每帧全屏采样，双窗口叠加渲染，中低端 GPU 直接跪。

**修复**：sheet 打开期间（模型选择 / 思考强度 / 附件 / 全屏输入）玻璃同样降级——sheet 的 scrim 本来就盖着底层，降级零视觉损失，关闭即恢复。

## 技术说明

- 根因定位：PrismalGlassLayerNode（DrawModifierNode）把 backdrop 内容录制进 GraphicsLayer，PrismalGlassSurface 每帧用 AGSL shader 采样该层做模糊/折射。backdrop 失效频率 × 全屏采样成本 = 每帧 GPU 大账单。
- 降级复用 GlassSurface 已有的 `backdrop = null` 分支（半透明 surface + 描边的普通容器），调用方零布局改动。
- 模态存续上报：ModelIconButton / ReasoningEffortSelector 新增 `onSheetPresenceChange`，ChatInputBar 聚合四个模态源（模型 sheet / 档位 sheet / 附件 sheet / 全屏输入）上报给 AIChatPanel。
- 减压期间连 backdrop 录制一并摘除（消费端已全部拿 null，录了也没人看）。
- 玻璃亮度仍走主题推导静态值（零采样），不受影响。

## 升级建议

聊天页在低端机上发送消息后掉帧、打开选择器卡顿的用户建议升级。
