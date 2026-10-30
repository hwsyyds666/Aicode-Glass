# 内置浏览器

App 内置了一个 WebView 浏览器，支持 AI 自动化操作网页。AI 可在后台操作浏览器，截图也能在后台完成（无需先打开面板）。

## 打开浏览器

在侧边栏底部的卡片中点击「内置浏览器」：

- **大屏（平板）**：浏览器在右栏与聊天并排打开，可拖动分割条调整宽度。
- **窄屏（手机）**：浏览器全屏打开，按返回键回到聊天。

## 手动浏览

在地址栏输入网址按回车即可导航；输入的内容不像网址时（如含空格或中文），会自动交给内置搜索引擎 Bing 搜索。地址栏右侧提供开发者工具开关与刷新按钮，下方底栏包含后退、前进、新建标签页、夜间模式切换与多标签管理。页面加载时地址栏下方显示线性进度条。

## 本地文件预览

地址栏支持本地文件地址，可直接打开设备上的 HTML 文件：

- 设备真实路径：`file:///storage/emulated/0/Download/index.html`
- 容器路径（AI 在容器里看到的路径，自动映射为真实文件）：`~/workspace/index.html`、`/etc/xxx.html`

AI 也可通过 `browser` 工具的 `navigate` 打开本地页面并截图分析。

**注意**：远程工作区模式下工作区文件在远端，只能加载页面本身，HTML 引用的相对资源（CSS/JS/图片）会失效；本地工作区不受影响。

## 开发者工具

点击地址栏右侧的 `</>`（代码图标）可随时开启或收起移动端开发者工具（基于 Eruda）：
- **全功能控制台**：包含 Console（查看日志与执行 JavaScript 代码）、Elements（查看与实时编辑 DOM 树和 CSS 样式）、Network（查看网络请求与响应）、Resources（查看 LocalStorage、Cookie 等数据）、Sources 等。
- **即时可用**：开启后页面右下角显示浮动齿轮图标，点击即可展开完整控制台面板；再次点击地址栏的开发工具按钮可彻底关闭并移除悬浮球。
- **电脑端调试联动**：开启时同步启用 Chromium 的 `WebContentsDebugging`，支持通过 USB 连接电脑并在 Chrome 浏览器访问 `chrome://inspect` 进行桌面级远程审查。

## 夜间模式

浏览器内置夜间模式，默认跟随 App 外观：App 为深色时页面自动变暗，App 为浅色时恢复原样。底栏的月亮/太阳图标可手动切换；手动选择后不再跟随 App 外观，切回与 App 外观一致时会自动恢复跟随。

夜间模式采用双轨渲染架构：
- **原生深色优先**：向页面声明 `prefers-color-scheme: dark` 与 `color-scheme: dark`，现代网站（如 GitHub、Google 等）会自动启用官方精细调优的原生暗色主题，文字清晰且深色区域不会反白。
- **亮底站点压暗**：对无暗色样式的亮底站点，在页面顶层盖一层半透明暗色蒙版整体压暗（保留原色、只降低亮度，不做反色），观感柔和。该蒙版作用于页面渲染，AI 截图同样会呈现压暗后的配色。
- **防闪白**：在页面启动时立即设置暗色视口背景并提前注入深色偏好，避免页面刷新瞬间的白屏闪烁。

## AI 自动化操作

AI 可通过 `browser` 工具控制浏览器执行以下操作：

| 操作 | 说明 |
| --- | --- |
| `navigate` | 导航到指定 URL（支持 `http(s)`，本地文件支持 `file://` 或容器路径；不像网址的输入会按关键词用内置搜索引擎 Bing 搜索），等待页面加载完成 |
| `evaluate` | 执行任意 JavaScript（支持 Promise/async，返回原生 JSON） |
| `click` | 点击元素（完整事件链，兼容 React/Vue） |
| `fill` | 填充表单字段（native setter + React valueTracker hack） |
| `select` | 选择原生下拉框 `<select>`（按 value 或可见文本） |
| `hover` | 悬停元素（派发 mouseenter/over/move，可展开下拉菜单） |
| `press` | 按键（Enter/Escape/Tab/方向键/Backspace/Delete/空格/普通字符） |
| `getText` | 提取页面文本（可指定选择器），已过滤 script/style |
| `getHtml` | 提取页面 HTML（可指定选择器） |
| `getBackbone` | 提取无障碍树（role/name/ref，可指定 `maxDepth`），ref 可直接用于后续操作 |
| `screenshot` | 截取当前页面，返回图片供视觉模型分析（支持后台离屏截图） |
| `console` | 取页面控制台日志（可过滤级别、可清空） |
| `wait` | 等待条件满足（`text=` / `text*=` / `selector=` / `domStable`，可设 `timeout`） |
| `scroll` | 滚动页面（滚动到指定元素或滚到底部） |
| `dialog` | 处理挂起的 `confirm`/`prompt` 对话框（接受或取消） |
| `back` / `forward` / `reload` | 浏览器导航控制，back/forward 会等待导航完成 |

页面弹出 `confirm`/`prompt` 时会被挂起，工具响应里会出现 `pendingDialog` 字段，用 `dialog` action 接受或取消（30 秒未处理会自动取消）。

## 选择器格式

`click`、`fill`、`hover`、`getText`、`getHtml`、`scroll`、`wait` 的选择器支持：

- `ref=e22`：`getBackbone` 返回的元素引用，直接定位快照里的元素
- CSS：`#id`、`.class`、`a[href=...]`
- `text=登录`：精确匹配元素文本
- `text*=登录`：包含匹配
- `role=button[name="登录"]`：按角色与名称匹配
- `xpath=//a[@href]`：XPath 表达式

`getBackbone` 返回无障碍树：每个节点形如 `{role,name,ref,url,value,children}`，`role` 是按标签/`role` 属性推导的可访问角色（`link`/`button`/`textbox`/`heading`/`navigation`…），`name` 是可访问名称（`aria-label`/`aria-labelledby`/`alt`/`placeholder`/关联 `label`/内容文本），`ref` 只分配给可交互元素。已过滤 `script`/`style` 与不可见元素，超出 `maxDepth` 的节点以 `truncated:true` 标记。

## 与 websearch / webfetch 的区别

- **websearch**：搜索引擎查询，获取搜索结果摘要。
- **webfetch**：抓取网页 HTTP 内容（纯文本或 HTML），不支持 JS 渲染。
- **browser**：完整 WebView 渲染，支持 JS 动态页面、交互操作、截图分析。

当网页内容依赖 JavaScript 渲染、需要登录后才能访问、或需要点击/填表等交互操作时，使用 `browser` 工具。
