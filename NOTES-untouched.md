# NOTES-untouched：本轮实施中顺带发现、未处理的问题

> 按实施手册第 2 章 (25) 建立：随时记录顺带发现的问题。本文件不并入功能 commit
> （单独 docs commit 或留存本地）。

## 数据库与检索

- **agent_messages 全文搜索未上 FTS**：`searchInWorkspace` 的 `content LIKE` 仍是全表扫。
  本轮仅加 (sessionId, timestamp) 复合索引（迁移 59）。现有 debounce + LIMIT + IO
  调度缓解可接受，10 万条级实测留待真机（见测试清单 17·P1-4）。

## 检查点 / 回退（任务 18 的依赖项）

- **17·P0-2.4「派发前快照」钩子未实现**：`CheckpointManager.beforeFileModified`
  已按可复用设计（sessionId 维度 + `SnapshotOutcome`），但「工作区级 / git write-tree
  派发前快照」API 未加——按手册该能力在任务 18 实现，本条仅保证接口届时可复用。
- **shell 命令的文件改动仍无快照**（现状，任务 18 处理）。
- **快照空间预检用 `snapshotDir.usableSpace`**：本地存储准确；远程模式下该目录是
  应用本地目录，预检的是「本机磁盘」而非远程剩余空间——远程大文件快照前不检查远程
  磁盘。影响面小（快照下载到本机），记录待评估。

## 任务 10（rg 保障）未验证点

- proot 下 `apk add ripgrep` 真机耗时与网络依赖（DNS/镜像源/代理/IPv6）。
- 远程 SSH 的 `sudo -n` 三种形态（无 sudo / 需密码 / 免密）成功率。
- 真机 ROOT 的 toybox grep 对 `-E` / `--exclude-dir` / `-I` 的支持度。
- Hilt `Provider<RipgrepProvisioner>` 破环依赖图（javax.inject Provider 破环无先例，
  编译已过，运行期实例化待真机验证）。

## 其他

- `MarkdownPreviewViewModel` 的 WebView `PathHandler` 管道方案（PipedInputStream +
  IO 协程异步写）：远程大文件、WebView 中途断连（broken pipe）行为需真机验证。
- 任务 16 同类弹层检查（模型选择器 / 文件夹浮窗 / @ 提及列表）：结论见任务 16 报告，
  需要真机复核滚动与外接键盘行为。
