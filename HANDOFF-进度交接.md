# Aicode-Glass 实施进度交接

更新：2026-10-24。本文状态取代旧版交接内容；详细实施项与真机步骤见 Codex 输出目录《实施报告与真机验收清单.md》。

## 当前状态

- 需求源：`C:\Users\gx520\Downloads\Telegram Desktop\Aicode-Glass_完整实施规格与回家执行手册.txt`。
- 仓库：`C:\Users\gx520\WorkBuddy\2026-10-03-00-44-43\AiCode-main`；分支 `main`，HEAD `c9a14f8b`，比 `origin/main` 超前 13 笔（**全部未推送，等用户确认**）。
- 20 项需求和任务 17 子项的实现已落地；P3-c 的状态归属/Flow 数量已改善，但 `AIAgentViewModel.kt` 行数仍比 HEAD 基线多 200，故该静态验收项不算完全达标。本轮补齐 Macrobenchmark 冷启动/聊天滚动场景、SFTP 操作队列超时和取消测试、SAF canonical-path 白名单/根目录保护，以及终端空输出 delta 边界测试。
- 本轮真机体验反馈已补齐：GitHub Device Flow 明确交给外部浏览器、Super Agent 开关静默且改用分支图标、回退确认/结果按实际非零项显示并区分工作区与 Git、聊天草稿按会话持久化、文件树走轻量目录列表、ChatGPT 账号改为入口卡片 + 独立管理页、默认模型页重做 Super Agent/派发限制层级。
- 代码已冻结并完成最终验证：Release lint、Universal Release assemble、Macrobenchmark instrumentation assemble 均 `BUILD SUCCESSFUL`；单测报告 1,281 项通过、0 失败、0 错误、2 项因 Windows ARM/Conscrypt 环境跳过。迁移核对通过。
- 不推送、不创建 Release。所有现有工作区内容保留；本机仍有大量多任务修改，最终交付前不清理或重置。
- 数据库 schema 66；迁移 60–66，其中迁移 59 为消息复合索引；迁移核对已通过。Windows ARM 上 Robolectric/Conscrypt 两项仍需在兼容 CI/设备环境补跑。
- 当前未连接 ADB 实体设备；OAuth、真实 SSH/SFTP、布局/动画帧率、回滚和 Macrobenchmark 指标需用户装包后验收。

## 20 项覆盖

1. 压缩保留最新用户消息。
2. 工具组终态自动收起、错误与取消状态清理。
3. PLAN 先计划后按权限执行，ASK 保持只读。
4. 隐藏 `aicode` 供应商展示，保留历史模型配置。
5. 统一上下文窗口和模型覆盖策略。
6. 子代理派发/范围/取消/审批/返工，两轮返工上限；内置类型。
7. 意图和命令审查，超时/异常询问回退。
8. 删除命令风险识别、硬拦截和授权流程。
9. 文件浏览分页/缓存/精准失效，拆为 `FileBrowserViewModel`。
10. ripgrep 幂等补装、自愈重试与 grep/git grep 兜底；根因说明见最终报告。
11. GitHub Device Flow、仓库分页/克隆及错误处理。
12. OpenAI 官方授权、账号/额度管理、加密凭据和安全路由。
13. StepFun/MiMo 图标。
14. `origin` 与原始上游分别审计；基线不兼容时不整体合并，低冲突修复独立采纳。
15. `sendFile` 总结交付与路径/大小/重复校验。
16. `/` 技能菜单限高、可滚动、键盘导航与分组。
17. P0-1/2、P1-1..6、P2-1..7、P3-a..d；Macrobenchmark 代码已加但未接设备测量。
18. 文件与 Git tree 检查点、子代理回退、外部改动保护和级联清理。
19. 会话级 Super Agent 开关和快速/主力两档子代理模型。
20. 移除自定义子代理设置入口/代码，内置四类型及旧会话兼容。

## 静态数据与已知遗留

- `AIAgentViewModel.kt`：HEAD 基线 2,378 行/27 处 `MutableStateFlow`，当前 2,578 行/23 处；文件浏览状态已移出，新增回退/生命周期协调导致总行数仍净增 200。最终报告解释范围与后续 coordinator 拆分建议。
- `SettingsScreen.kt`：1,750 → 1,411 行；`collectAsStateWithLifecycle` 字符串命中 80 → 16。
- Macrobenchmark：冷启动 5 次测量；聊天流式输出场景发送 1 条长回复并滚动；命令和前置条件在 `macrobenchmark/README.md`。需要真实 Android 设备及有效模型配置，可能消耗 API 用量。
- Cockpit Tools 仓库授权标注为 CC BY-NC-SA 4.0，本仓库为 GPL-3.0；本轮仅参考功能，不复制代码，OpenAI 授权按官方 SIWC 文档独立实现。
- GitHub 连接已零配置化（2026-10-24 本轮）：点「连接 GitHub」直接走 Device Flow，不再要求用户手填 Client ID；client_id 取用户存储值，缺省用 `GitHubOAuthRepository.BUILT_IN_CLIENT_ID` 常量（仓库维护者注册 OAuth App 后填此即全量生效；未内置时界面显示注册指引）。令牌不硬编码进源码或日志。
- 仓库及当前用户目录 `C:\Users\gx520\.aicode\agents` 未发现旧子代理定义目录；其他设备上的旧定义应用不删除、也不再读取。

## 最终验证与产物

最终验证：`:app:lintUniversalRelease :app:assembleUniversalRelease :macrobenchmark:assemble` 成功（153 个任务，21 执行、132 up-to-date）；单测报告 1,281/0/0/2（通过/失败/错误/跳过）；`git diff --check` 通过。Macrobenchmark 的 `AgentMacrobenchmark` 类已实际编译进测试 APK。

Release APK（已被下方第三轮新包取代）：`C:\Users\gx520\Documents\Codex\2026-10-23\aicode-glass-svg-workbuddy-app-assembleuniversaldebug\outputs\Aicode-Glass-1.0.9-dev.11-ux-fixes-universal-release.apk`。`applicationId=com.aicode`，`versionCode=49`，`versionName=1.0.9-dev.11+205b7899-dirty`，ABI 为 arm64-v8a/x86_64，minSdk 26，19,037,964 bytes。apksigner v2 校验通过；SHA-256 `B1721CD36310672ACFA54E0E6A63F35E4EF119706306B20C0BB466D5C45A8F57`。

未构建或交付 Debug APK；未推送/发布。ADB 未连接实体设备，真实 OAuth、SSH/SFTP、布局/动画、迁移启动和性能指标仍由用户安装后验收。P3-c 的 `AIAgentViewModel.kt` 总行数相对基线净增 200，详见报告中的遗留说明。

---

## 2026-10-24 第二轮（用户真机反馈修复轮）

HEAD `03a4ea3` 之后用户装包实测，反馈一批工具故障与界面问题。本轮全部修复，与在途改动一起以一次全量快照提交（共享文件双方交织，无法按主题干净切分；整树已验证）。

### 修复（用户点名的故障）

1. **Bash 全废**：`withSnapshotSkipWarning` 假设 data 是 JsonObject，Bash/terminal 的字符串 stdout 被替换成空对象，输出整个消失只剩 warning。现保留原始 data，仅对象型 data 才合并 warning。
2. **warning 噪音**：非 Git 仓库工作区每条 shell 命令重复注入"没有可用的 Git 检查点"，改为仅记日志。
3. **search 兜底漏结果**：`isRgMissing` 漏 toybox 报错格式 `rg: inaccessible or not found`；grep 兜底改 `-E`（ERE，与 rg 语义一致，alternation 不再变字面量）；退出码 2 时保留已有命中并标注不完整，另给最小命令重试。
4. **terminal send 必填 tab_id**：省略时自动选用唯一运行中标签；无法判定时报错附带候选清单，AI 可一次重试修正。
5. **步骤执行失败横幅**：用户明确未要求且难看，整块移除（TurnCollapseHeader 失败态、TurnHeaderState failure* 字段、双字符串、测试断言）。

### 重做（用户点名的界面）

6. **超级 Agent 设置区**：快速/主力的模型与思考强度、派发限制三项全部改用统一底部弹层（替代复古 DropdownMenu）；移除"独立 reviewer 审查 / 大改动阈值"（设置项、SystemPromptProvider 注入、SubAgentSettingsRepository、双语文案一并清理）。reviewer 模型行保留——那是任务 7 命令审查器的专用模型，非独立 review。
7. **ChatGPT 账号页对齐 cockpit-tools**：计划徽章配色（plus 翠绿 / pro 蓝 / free 灰等）、状态 pill、5h/每周额度迷你条（剩余比例绿→黄→橙→红 + 重置时间）、本地用量、图标动作行；模型选择改底部弹层。仅参考视觉，未复制其代码（对方 CC BY-NC-SA，本仓库 GPL-3.0）。
8. **GitHub 一键连接**：见上文「静态数据与已知遗留」更新条。

### 验证

- `:app:assembleUniversalDebug` BUILD SUCCESSFUL；`:app:testUniversalDebugUnitTest` 1279 通过 0 失败；迁移对账 8..66 通过。
- 回归验证清单（Bash 输出完整 / alternation 搜索 / terminal 自动选标签 / 设置区无 reviewer 残留 / 账号页深浅色 / GitHub 一键流）已列入 `NOTES-untouched.md` 最前。

---

## 2026-10-24 第三轮（search 执行环境统一轮）

HEAD `c9a14f8b`。用户反馈：容器模式 search 正常、真机（DEVICE_ROOT）模式不行；并追问 GitHub 连接到底缺什么。

### 修复

1. **真机模式下工作区内搜索统一走容器**（`SearchCodeTool.pickSearchEnv`）：真机模式的工作区 `~/workspace` 本就是 bind mount 到容器的同一份内容，因此在真机上对 rg 支持不完整（toybox 无 rg、grep 选项受限）却仍强行走真机执行，是 search 失败的根源。现在只要搜索目标路径整体落在工作区且容器已就绪，就路由到容器执行，并用 `ShellArgs.translateTokensForContainer()` 把真机绝对路径（`/data/.../workspace/foo`）翻译成容器内路径（`/root/workspace/foo`）；目标在工作区之外（`/system`、`/data` 其他子树）才回退真机执行。
2. **路径翻译按 rg 语法处理位置参数**（`ShellArgs`）：带值旗标（`-A/-B/-C/-m/--max-count/-g/--glob` 等）后的 token 不当作路径；`--` 之后第一个位置参数是模式、其余是路径；`-e` 形式下全部位置参数都是路径。新增 `isSearchTargetInsideWorkspace`、`positionalPathArgs`、`SEARCH_VALUE_FLAGS`、`normalizeSeparators` 共享函数。
3. **grep 兜底 usage error 误判**：退出码 2 且输出里没有 `文件:行号:` 形式的命中时，才判定为 usage error 并触发最小命令重试；此前会把"部分成功"当成失败丢掉结果。
4. **rg 自愈支持指定目标环境**：`RipgrepProvisioner.ensureAvailable(force, target)` 新增 `target` 参数，可对容器目标执行补装，避免"容器里其实没装 rg 却判定已就绪"。
5. 新增 `ShellArgsTest` 13 例路径翻译/工作区判定用例；Windows 上 POSIX 相关假设（目录 symlink 解析、盘符路径）用 `assume*` 跳过，不产生假失败。

### GitHub 连接缺什么（答复用户）

当前不是代码问题，是**缺一个 OAuth App 的 Client ID**。应用已改成零配置 Device Flow：点「连接 GitHub」直接给设备码 + 打开浏览器，不再要求手填 ID。Client ID 取自用户存储，缺省读 `GitHubOAuthRepository.BUILT_IN_CLIENT_ID` 常量——该常量当前是**空占位**。仓库 owner 需：

1. GitHub → Settings → Developer settings → OAuth Apps → New OAuth App；
2. Homepage URL 随意，**Authorization callback URL 不需要真实回调（Device Flow 不用）**，但字段必填，可填 `https://github.com/hwsyyds666/Aicode-Glass`；
3. 勾选 **Enable device flow**；
4. 复制 Client ID（不需要 client secret）填入 `BUILT_IN_CLIENT_ID`，重新编译即可全员零操作连接。

未填时界面显示注册指引，不会出现"请输入 Client ID"的输入框。

### 验证

- `:app:assembleUniversalDebug` BUILD SUCCESSFUL；`:app:testUniversalDebugUnitTest` **1295 通过 0 失败**（Windows 上 9 个 POSIX 用例跳过）；迁移对账 8..66 通过。
- Release 包：`C:\Users\gx520\Documents\Codex\2026-10-24\aicode-glass-search-container-fix\outputs\Aicode-Glass-1.0.9-dev.13-search-container-universal-release.apk`，`versionCode=51`、`versionName=1.0.9-dev.13+c9a14f8b`，19,039,860 bytes，SHA-256 `F05EE0E81C9DA07A3F6E6AA9DF9605AA40D1AD088916ED42DB8CF33943BF9D37`。构建源同 `app\build\outputs\apk\universal\release\app-universal-release.apk`。
