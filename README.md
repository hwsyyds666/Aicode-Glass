<div align="center">

<img src="docs/icon.webp" alt="AiCode" width="120" />

# AiCode

**手机上的 AI 编程工作站 —— 不只是聊天，而是真正能写代码、跑构建、出成果**

内置 Linux 容器与终端 · AI Agent 自主读写文件、执行命令、调试代码 · 本地 / 远程 SSH 双后端

[中文](README.md) · [English](README.en.md)

<br/>

[![License](https://img.shields.io/github/license/hwsyyds666/Aicode-Glass?color=blue)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/hwsyyds666/Aicode-Glass/releases/latest)
[![Release](https://img.shields.io/github/v/release/hwsyyds666/Aicode-Glass?include_prereleases&display_name=tag&color=orange)](https://github.com/hwsyyds666/Aicode-Glass/releases)
[![Stars](https://img.shields.io/github/stars/hwsyyds666/Aicode-Glass?style=flat&color=yellow)](https://github.com/hwsyyds666/Aicode-Glass/stargazers)

[下载](https://github.com/hwsyyds666/Aicode-Glass/releases/latest) · [功能亮点](#-核心亮点) · [截图](#-截图) · [快速开始](#-快速开始) · [使用文档](https://aicode.murk.top)

</div>

---

> **关于本仓库** · 基于 [jieapi/AiCode](https://github.com/jieapi/aicode) 二次开发，新增 Liquid Glass UI、ChatGPT 多账号聚合与额度路由、超级 Agent 并行子代理、通知栏任务进度、上下文缓存最大化等特性。遵循相同 [GPL-3.0](LICENSE) 协议。

## 💡 这是什么

**一句话**：在手机上跑一个能真正干活的 AI 编程 Agent —— 不只是给你回段代码，而是**自己动手读写文件、执行 Shell、运行构建、调试报错**，全程在手机本地完成。

- 内置 Alpine Linux 容器 + 终端，AI 能直接操作文件系统
- 支持远程 SSH 后端，手机变成远程项目的移动工作站
- 不内置模型、不绑定供应商，OpenAI / Anthropic / Gemini 协议全兼容

## 🌟 核心亮点

### 📱 手机即工作站

不是聊天套壳。AI Agent 拥有完整工具链：文件读写编辑、Shell 执行、后台终端、代码搜索、网页抓取、图片识别、生图、待办管理。**你跟 AI 说"帮我写个 Web 服务并跑起来"，它就真的写代码、装依赖、启动服务、报错了自己修**。

### 🔐 ChatGPT 多账号聚合 + 额度路由

批量导入官方 OAuth 账号，模型选择器聚合为单组、按订阅档位排序。智能额度调度：**免费模型走免费号、付费专属模型走付费号**，会话亲和性绑定同一账号、凭据失效自动软解绑。额度暂停 / 离线 / 禁用的账号绝不静默切换。对齐 cockpit-tools 导入格式。

### 🧠 超级 Agent · 主动并行派发

开启后，AI 会**主动**把研究/调查类任务拆分成多个子代理并行执行——Explore、Research、Worker、Reviewer 四种角色协同。Worker 写入范围受校验、禁止嵌套，主会话负责审查汇总。不再需要你手动说"用子代理"。

### 📢 通知栏任务进度

AI 执行任务时通知栏常驻显示，展开可实时看到 AI 当前在做什么——思考中、生成回复中、每一段的小结一目了然。多任务并行时显示「N 个任务运行中」，完成后自动收起。**不用盯着屏幕也知道 AI 干到哪了**。

### ⚡ 上下文缓存最大化

Anthropic 用满 4 个 `cache_control` 断点（系统提示 + 工具定义 + 原始问题 + 滚动断点）。工具循环中不断增长的历史走滚动断点——**每步写新缓存、下一步读上一步缓存**，长任务只重算最后一条，大幅降低 token 成本和延迟。

### ⏪ 检查点与一键回退

Agent 改代码前自动记录文件快照。不满意？**一键回滚代码、对话，或两者同时回滚**。不怕 AI 改坏了。

### 🌿 可视化 Git + GitHub 工作区

状态、分支、提交历史、差异、标签一目了然，支持暂存、回退、署名。通过 Device Flow 连接 GitHub，分页浏览并克隆仓库，可在确认后创建 Pull Request。

### 🪟 Liquid Glass 液态玻璃 UI

基于 [PrismalAGSL](https://github.com/styropyr0/PrismalAGSL) 的折射、色散与渐变玻璃效果，悬浮玻璃标题栏，明暗主题 + 莫奈取色 + 自定义背景图。

## 📸 截图

<p>
  <img src="docs/screenshots/providers.jpg" width="31%" alt="AI 供应商" />
  <img src="docs/screenshots/models-edit.jpg" width="31%" alt="模型管理" />
  <img src="docs/screenshots/prompts.jpg" width="31%" alt="提示词库" />
  <br/><b>多供应商管理 · 模型配置 · 提示词库</b>
</p>

<p>
  <img src="docs/screenshots/chat-image.jpg" width="31%" alt="AI 对话与图片生成" />
  <img src="docs/screenshots/chat-tools.jpg" width="31%" alt="工具调用与确认" />
  <img src="docs/screenshots/settings.jpg" width="31%" alt="设置" />
  <br/><b>AI 对话 · 图片生成 · 工具授权 · 个性化设置</b>
</p>

## 🔀 三种运行模式

| 模式 | 定位 | 行为 |
| :-- | :-- | :-- |
| **BUILD** | 正常开发 | 按你配置的工具授权规则执行 |
| **PLAN** | 计划后执行 | 先展示任务清单，再逐步执行并更新状态 |
| **ASK** | 只读问答 | 探索并回答问题，写操作由系统硬拒绝 |

## 📦 快速开始

**系统要求**：Android 8.0+（API 26），arm64-v8a / x86_64

**1. 下载安装**

前往 [GitHub Releases](https://github.com/hwsyyds666/Aicode-Glass/releases/latest) 下载：

| 安装包 | 适用设备 |
| :-- | :-- |
| `armsolo` | 真机（arm64-v8a） |
| `x86solo` | 模拟器（x86_64） |
| `universal` | 通用包，不确定选哪个时使用 |

**2. 三步上手**

1. 进入「**设置 → AI 供应商**」，配置你的模型、密钥与端点
2. 在「**容器与镜像**」中选择使用本地容器或远程 SSH
3. **新建会话**，开始对话

> 📖 更详细的教程请查看 [在线文档](https://aicode.murk.top)（与 App 内置文档同源）

<details>
<summary><b>🔧 更多功能特性</b></summary>
<br/>

**AI Agent**
- 内置工具：文件读写编辑、Shell 执行、后台终端、代码/网页搜索、图片识别、生图、待办管理
- 流式输出 + 实时 Markdown 渲染 + 长对话自动压缩上下文
- 全局/项目级技能（Skills）与长期记忆，AI 可跨会话复用经验
- MCP 协议支持，连接本地（stdio）或远程（HTTP）MCP 服务器扩展工具能力
- 逐工具授权规则 + 系统提示词用户可覆盖，升级不丢失

**模型与供应商**
- 模型无关：不内置模型、不绑定供应商
- 协议兼容：OpenAI / Anthropic / Gemini 三类协议 + 自定义供应商
- 多 Key 轮换：同一供应商配置多个 Key，顺序或轮询自动切换
- 思考强度可调

**开发环境**
- 内置终端与容器：基于 Termux + PRoot 的本地 Linux 容器，内置 Alpine 镜像，支持自定义 rootfs 与宿主目录挂载，多标签可后台常驻
- 远程 SSH 模式：远程服务器作为执行后端，命令/文件/终端都作用于远端
- 文件树与代码编辑器：缩进式文件树 + 全屏编辑器，主流语言语法高亮 + Markdown 预览，AI 回复中的 `文件:行号` 可直接跳转
- 工作区同步：SFTP / FTP 同步 + 内置 FTP 服务端

**使用体验**
- 平板与大屏适配：宽屏常驻侧边栏，聊天旁并排代码或终端
- Token 统计：按渠道与模型统计用量、估算费用，可下钻查看调用明细
- 外观：明暗主题、预设配色、莫奈取色、自定义背景图，中英双语
- 网络代理：全局代理与供应商级代理可分别配置
- 备份还原：加密导出/导入供应商配置、聊天历史与工作区文件

</details>

## 🏗 从源码构建

```bash
git clone https://github.com/hwsyyds666/Aicode-Glass.git
cd AiCode
./gradlew assembleDebug
```

构建产物位于 `app/build/outputs/apk/`。需要预先安装 JDK 与 Android SDK。

<details>
<summary>项目结构</summary>

```text
AiCode
├── app/                # Android 主应用
├── terminal-emulator/  # 终端模拟器核心（基于 Termux）
├── terminal-view/      # 终端视图组件（基于 Termux）
├── prismalrepo/        # PrismalAGSL 液态玻璃库（本地 Maven 仓库）
├── docs/               # 图标与截图等文档资源
└── docs-site/          # 在线文档站点源码
```
</details>

## ⭐ Star History

如果 AiCode 对你有帮助，欢迎点一个 ⭐ Star，让更多开发者看到这个项目。

[![Star History Chart](https://api.star-history.com/svg?repos=hwsyyds666/Aicode-Glass&type=Date)](https://www.star-history.com/#hwsyyds666/Aicode-Glass&Date)

## 💬 反馈与贡献

- 🐞 **Bug 反馈**：[Issues](https://github.com/hwsyyds666/Aicode-Glass/issues)（附复现步骤、设备型号与系统版本）
- 💡 **功能建议**：欢迎先在 [Issues](https://github.com/hwsyyds666/Aicode-Glass/issues) 讨论
- 🔧 **贡献代码**：欢迎提交 [Pull Request](https://github.com/hwsyyds666/Aicode-Glass/pulls)
- 💬 **交流群**：[AiCode QQ 交流群](https://qm.qq.com/q/ByvqODJdIs)（群号：1107110698）

<a href="https://github.com/hwsyyds666/Aicode-Glass/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=hwsyyds666/Aicode-Glass" alt="Contributors" />
</a>

## 🙏 致谢

- **[jieapi/AiCode](https://github.com/jieapi/aicode)** — 原作者项目，本分支的基础
- [OpenCode](https://github.com/anomalyco/opencode) — 核心灵感来源
- [Termux](https://github.com/termux/termux-app) — 终端模拟器与 PRoot 方案
- [Kelivo](https://github.com/Chevey339/kelivo) — AI 对话界面设计参考
- [PrismalAGSL](https://github.com/styropyr0/PrismalAGSL) — 液态玻璃效果库

## 📄 开源协议

[GPL-3.0](LICENSE) · 第三方组件版权声明详见 [NOTICE](NOTICE)

<div align="center">

<sub>Made with ❤️ on Android</sub>

</div>
