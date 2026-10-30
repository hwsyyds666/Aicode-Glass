# 遗留与真机验收清单

更新：2026-10-24（第二轮）。旧版中“任务 18 派发前快照未实现”等条目已过时，按当前代码删除；本轮已修复 Bash stdout 被 warning 吞、search grep 兜底漏结果、terminal send 必填 tab_id、独立 reviewer 设置、GitHub 手填 Client ID、步骤失败横幅、ChatGPT 账号页改版。这里仅保留不能由本机静态检查/单测替代的验证，以及明确延期项。

## 用户拿到 Release 包后重点验收

- Bash 修复验证：非 Git 仓库工作区连续执行多条命令，stdout/stderr 必须完整可见（曾经的 bug：只返回 warning）。
- search 修复验证：**真机（DEVICE_ROOT）模式**下搜索工作区内文件（如 `pstore|ramoops` 这类 alternation），结果应与容器模式一致；此模式下现在会翻译路径后交给容器执行。再单独验证搜索工作区外的 `/system`、`/data` 子树仍走真机执行且不报错。
- search 内置 rg 验证：真机模式下 `-uu`、`--glob`、`-t` 等 rg 专有参数应返回真实命中（走内置静态 rg，结果 JSON 的 `backend` 为 `rg`）；若 `backend` 为 `grep`，确认 `warning`/`dropped_flags` 有说明、且不是"静默 0 结果"。
- ChatGPT 多账号验证：连续添加 2 个以上账号（按钮应始终可点）、两账号额度分别刷新、启用/停用互不干扰、"设为默认"后新建会话确实用该账号、移除其中一个后另一个不受影响；确认聊天里选模型仍能指定任一账号。
- terminal 验证：不传 tab_id 的 send 在只有一个运行中标签时自动命中；多标签时报错带候选清单。
- 超级 Agent 设置区：快速/主力的模型与思考强度（底部弹层）、派发限制三项（底部弹层）；确认无「独立 reviewer / 大改动阈值」残留。
- ChatGPT 账号页：计划徽章配色、5h/每周额度迷你条与重置时间、模型选择弹层、图标动作行；深浅色主题下均清晰。
- GitHub 连接：点「连接 GitHub」直接走 Device Flow（此版本未内置 OAuth App 时会显示注册指引，而非要求手填 ID；内置后零操作）。
- 压缩长对话、超长最新消息、执行中触发压缩后继续提问；确认最新用户消息仍在上下文。
- 工具卡片六种结束路径（成功、失败、取消、断网、切会话、进程回收）折叠/展开状态正确。
- PLAN 多步骤任务及三种权限档位；确认 ASK 仍拒绝写操作。
- 超过 2MB 的文件编辑直接拒绝；创建文件、编辑文件及子代理修改均可回退；人工外部改动不被覆盖。
- 终态子代理返工两轮后第三次被拒；超级 Agent 关闭时不主动派发；开启时执行 6 文件/3 独立任务，检查并行状态、范围拒绝、返工。
- 命令审查危险/模糊用例，断网/超时回退询问；删除家目录、工作区根、系统路径等均不能被“完全权限”或提权绕过。
- 新目录缓存快速连续展开、SFTP 慢目录并发浏览、Agent 修改后精准刷新；观察滚动流畅度及切换目录时旧请求不覆盖新结果。
- ripgrep 初次安装/缺失自愈（含容器目标补装）；远程 SSH 无 rg 时 grep 兜底；测试 toybox grep 的实际选项兼容性。
- **GitHub Device Flow（需 owner 在 OAuth App 上勾选 Enable device flow）**：Client ID 已内置（`Ov23li…`），点击「GitHub」入口一键跳浏览器、**不再弹二次窗口**；授权码自动复制到剪贴板并 Toast 提示。若 GitHub 返回"设备流未启用"，需到 Settings → Developer settings → OAuth Apps → 该 App 勾选 **Enable device flow** 后重试。
- GitHub OAuth Device Flow 真实登录、分页、克隆取消、限流/拒绝/过期；确认 token 不进入日志。
- ChatGPT 添加账号：弹层里「打开浏览器登录」后，若浏览器**没有自动跳回**应用，需复制地址栏完整回调链接粘回「手动粘贴回调链接」并点「完成登录」——这是 cockpit-tools 同款兜底路径，必须真机验一遍。
- ChatGPT 导入账号：粘贴 Codex CLI `auth.json` 全文（或含 access_token/refresh_token/id_token 的 JSON）应直接建立账号；注意导入账号的 refresh 依赖 `client_id`，auth.json 未带时回退到 Codex CLI 公开 client id，如续期失败请重新走浏览器登录。
- OpenAI 登录、凭据加密存储、退出/恢复授权及额度展示。不要将多账号轮换作为避开套餐/速率限制的测试目标。
- sendFile 从容器/远程路径映射到本地，图片/大文件/重复文件/不存在路径，以及纯问答不应误发文件。
- 斜杠菜单：100+ 技能，竖/横屏、软键盘、分屏、外接键盘与窄屏。
- 设置各 Section、两档子代理模型/reviewer（命令审查器）设置、窄屏和横屏；确认设置页改动没有 UI 语义回归。
- 背景图最大尺寸/面积约束；工具大量 stderr 时远程命令不死锁且错误可见。
- 无限动画后台暂停/恢复，切换系统“移除动画”；真实帧率/CPU/GPU 仍需设备测量。

## 依赖设备/网络的尚未验证项

- 当前 ADB 无设备连接。本机最终单测与构建完成不代表真机功能已验收。
- Android 容器安装 `ripgrep` 的 DNS/镜像源/IPv6/代理、SSH `sudo -n` 三种情形、toybox grep 兼容性。
- 远程 SFTP 大文件检查点仍把快照下载到应用本地目录；空间预检因此是本机剩余空间，不代表远端磁盘余量。
- WebView Markdown 预览采用管道传输；超大远程文件和用户中途关闭 WebView 时的 broken-pipe 行为需真机确认。
- 迁移 60–66 的脚本编号/版本对账可本机验证；Room/Robolectric 真实开库升级用例因当前 Windows ARM JDK 缺少 Conscrypt native 而被环境条件跳过。实际用户数据库升级仍需要在兼容 Linux/CI 或 Android 环境补测。
- FTS 全文索引未增加：目前搜索仍以 `LIKE` 为主；10 万条量级性能数据需真实设备基准后决定是否引入。

## 有意延期

- 17·P3-d 已新增 Macrobenchmark 模块，含冷启动与对话流式输出/滚动两个基准场景。当前无连接设备，基准代码尚未在实体设备运行；不要把模拟器结果当成性能结论。
- 多账号**管理**已实现（2026-10-24 第四轮：可并存多个账号、分别查看额度、单独启停、设为默认、移除）。仍未实现且有意不做的是**自动轮换/故障转移**：请求只走用户显式选中的账号，某个账号额度触顶不会自动换号——这是遵守 Sign in with ChatGPT 官方授权边界的硬线，不为规避订阅套餐或速率限制而在账号间自动切换。
- 内置 rg 为 musl 静态二进制（ripgrep 15.2.0，MIT/Unlicense），以 `librg.so` 形式随包分发；仅在真机通道启用。若将来升级，需同步替换 `app/src/main/jniLibs/<abi>/librg.so`。
