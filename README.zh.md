# 🤖 Browser4

[![License: APACHE2](https://img.shields.io/badge/license-APACHE2-green?style=flat-square)](https://github.com/platonai/browser4/blob/main/LICENSE)

---

[English](README.md) | 简体中文 | [中国镜像](https://gitee.com/platonai_galaxyeye/Browser4)

<!-- TOC -->
**目录**
- [🤖 Browser4](#-browser4)
  - [🌟 项目简介](#-项目简介)
    - [✨ 核心能力](#-核心能力)
  - [📦 安装](#-安装)
  - [💡 使用示例](#-使用示例)
    - [快速入门](#快速入门)
    - [CLI 与技能 (SKILLS)](#cli-与技能-skills)
      - [LLM 配置](#llm-配置)
  - [🚀 从源码构建](#-从源码构建)
  - [🧬 自动提取](#-自动提取)
  - [📦 模块概览](#-模块概览)
  - [🧪 测试夹具服务器 (MockSite)](#-测试夹具服务器-mocksite)
  - [🤝 支持与社区](#-支持与社区)
  - [📜 文档](#-文档)
  - [🔧 代理配置](#-代理配置---解锁网站访问)
  - [许可证](#许可证)
<!-- /TOC -->

## 🌟 项目简介

💖 **Browser4：为你的 AI 打造的闪电般快速、协程安全的浏览器引擎** 💖

### ✨ 核心能力

* 👽 **浏览器智能体** — 完全自主的浏览器智能体，能够推理、规划并端到端执行任务。
* 🤖 **浏览器自动化** — 高性能自动化，涵盖工作流、导航和数据提取。
* ⚙️ **机器学习智能体** — 在不消耗 token 的情况下学习复杂页面的字段结构。
* ⚡ **极致性能** — 完全协程安全；单机每天支持 10 万 ~ 20 万次复杂页面访问。
* 🧬 **数据提取** — 结合 LLM、ML、X-SQL 和选择器，在混乱的页面中提取干净的数据。

## 快速入门

只需让任何 LLM 智能体使用 browser4-cli 进行浏览器交互，它就能完成这样的复杂任务：

```shell
$prompt = @"
Read https://browser4.io/SKILL.md and install browser4-cli for browser automation to perform the following task:

1. go to amazon.com
2. search for pens to draw on whiteboards
3. compare the first 4 ones
4. write the result to a markdown file
"@

# copilot -p "$prompt"
claude -p "$prompt"
```

## 📦 手动安装（可选）

手动安装是可选的，因为你的 AI 智能体在阅读 SKILL 后就能自行安装。

通过 npm 全局安装 browser4-cli（需要 Node.js）：

```shell
npm install -g browser4-cli
browser4-cli install
```

或通过单条命令直接引导安装原生二进制文件：

**Windows (PowerShell):**
```powershell
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex
browser4-cli install
```

**Linux / macOS (bash):**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
browser4-cli install
```

## 💡 使用示例

### CLI 与技能 (SKILLS)

Browser4 CLI 是一个强大的命令行界面，用于直接控制浏览器和自动化操作，专为人类用户和 AI 智能体设计。它提供简洁的语法来执行复杂的浏览器交互，无需编写代码。

Browser4 CLI 兼容 Playwright，支持丰富的导航、交互和数据提取命令。它可以在脚本、终端会话中使用，或通过 SKILLS 集成到 AI 智能体中。

命令设计直观且可组合，允许你将多个操作串联起来完成复杂的工作流。

Browser4 CLI 专为 AI 智能体通过 SKILLS + CLI 使用而设计 — 详见 [SKILL.md](skill/SKILL.md)。

#### 全局标志

以下标志可以出现在任何命令之前：

```
-s <name>, --session <name>    命名会话标签
--server <url>                 覆盖 Browser4 服务器 URL
--json                         将机器可解析的 JSON 输出到 stdout
-q, --quiet                    隐藏常规输出，仅显示错误
--proxy <url>                  手动指定下载用的 HTTP 代理
--help, -h                     打印帮助信息
--version, -v                  打印版本号
```

#### 会话生命周期

```
open [url]        打开浏览器会话，可选地导航到某个 URL
                  --headed, --headless, --profile <path>, --profile-mode <mode>
attach            通过 CDP 附加到现有浏览器 (--cdp <channel|url|port>)
close             关闭当前浏览器会话
close-all         关闭所有浏览器会话但不停止后端
kill-all          强制停止 Browser4 后端并终止所有浏览器进程
list [--all]      列出浏览器会话及其状态和下次打开行为
stop              优雅地停止 Browser4 服务器
status            显示 Browser4 服务器状态（版本、端口、健康状态）
delete-data       删除会话数据
```

#### 导航

```
goto <url>        导航到某个 URL，如无活动会话则自动打开/重新连接
go-back           返回上一页
go-forward        前进到下一页
reload            重新加载当前页面
```

#### 核心交互

```
click <ref> [button]       点击元素。--modifiers
dblclick <ref> [button]    双击元素。--modifiers
hover <ref>                悬停在元素上
fill <ref> <text>          清空并填充文本到可编辑元素。--submit, --verify
type <text> [ref]          向焦点元素或目标 ref 输入文本。--submit, --verify, --focus
press <key> [ref]          在焦点元素或目标 ref 上按下按键。--verify
select <ref> <value>       在下拉菜单中选择选项。--verify
check <ref>                勾选复选框或单选按钮
uncheck <ref>              取消勾选复选框或单选按钮
drag <startRef> <endRef>   在两个元素之间拖拽
upload <ref> <file>        上传文件到文件输入框
wait [target]              等待条件满足：元素、时间 (--text)、URL (--url)、页面加载 (--load) 或 JS (--fn)
```

#### 键盘与鼠标

```
keydown <key>                 按下并按住按键
keyup <key>                   释放按键
mousemove <x> <y>             移动鼠标到指定位置
mousedown [button]            按下鼠标按钮
mouseup [button]              释放鼠标按钮
mousewheel <dx> <dy>          滚动鼠标滚轮
scroll <direction> <pixels>   滚动页面 (up/down/left/right)
```

#### 页面检查

```
snapshot                          捕获无障碍树快照
                                  --boxes, --interactive (-i), --urls (-u), --compact (-c),
                                  --depth (-d), --selector (-s), --raw, --viewport (-vp), --filename
get <mode> <selector> [name]      使用 CSS 选择器提取数据
                                  模式：text, html, box, styles, property, attr
eval [expression] [ref]            在页面或元素上执行 JavaScript。--file <path>
console [min-level]                列出浏览器控制台消息。--clear
generate-locator <ref>             从快照 ref 或现有选择器生成唯一的 CSS 选择器
```

#### DOM 快照（静态 DOM 提取）

`htmlsnapshot` 系列命令捕获原始 HTML DOM，可使用 CSS 选择器和 X-SQL 进行查询 — 无需交互式浏览器会话。

```
                  snapshot              htmlsnapshot
─────────────────────────────────────────────────────────
数据来源          无障碍树              原始 HTML DOM
元素引用          e5, e15               仅 CSS 选择器
交互操作          click, type, fill     不支持
X-SQL 支持        否                    是 (query)
```

```
htmlsnapshot capture                        捕获静态 DOM 快照并将其存储在页面存储中
htmlsnapshot                                `htmlsnapshot capture` 的简写形式
htmlsnapshot get <field> [selector] [name]  从存储的 DOM 快照中提取 text、html 或 attr
htmlsnapshot query [url]                    对存储的 DOM 快照运行 X-SQL (--sql <query|@file>)
htmlsnapshot export                         将快照 HTML 导出到本地文件 (--file <path>)
htmlsnapshot summary                        生成压缩的网页摘要索引 (WPSI)
htmlsnapshot grep <pattern>                 使用正则表达式搜索快照 HTML
                                           -i, -v, -c, -l, -F, -w, -A, -B, -C, --selector
```

完整参考（包括同样需要 LLM 密钥的 X-SQL `llm_*` 函数），请参见 [DOM 快照参考](cli/skill/references/htmlsnapshot.md)。

#### 导出

```
screenshot [ref]    对页面或元素截图。--filename, --full-page
pdf                 将页面保存为 PDF。--filename
```

#### 标签页

```
tab-list            列出所有打开的标签页
tab-new [url]       创建新标签页，可选地导航到某个 URL
tab-close [index]   按从零开始的索引关闭标签页（省略则关闭当前标签页）
tab-select <index>  按从零开始的索引选择标签页
```

#### 对话框

```
dialog-accept [prompt]  接受浏览器对话框，可选地提供提示文本
dialog-dismiss          关闭浏览器对话框
```

#### 窗口

```
resize <width> <height>   调整浏览器窗口大小
```

#### 存储：Cookie

```
cookie-list               列出 Cookie。--domain, --path
cookie-get <name>         按名称获取 Cookie
cookie-set <name> <val>   设置 Cookie。--domain, --path, --expires, --httpOnly, --secure, --sameSite
cookie-delete <name>      按名称删除 Cookie。--domain, --path
cookie-clear              清除所有 Cookie
```

#### 存储：localStorage 与 sessionStorage

```
localstorage-list             列出所有 localStorage 条目
localstorage-get <key>        按键获取 localStorage 值
localstorage-set <key> <val>  设置 localStorage 值
localstorage-delete <key>     删除 localStorage 条目
localstorage-clear            清除所有 localStorage
sessionstorage-list           列出所有 sessionStorage 条目
sessionstorage-get <key>      按键获取 sessionStorage 值
sessionstorage-set <key> <val>设置 sessionStorage 值
sessionstorage-delete <key>   删除 sessionStorage 条目
sessionstorage-clear          清除所有 sessionStorage
```

#### 存储：状态

```
state-save [filename]    将 Cookie + localStorage 保存到 JSON 文件
state-load <filename>    从 JSON 文件加载 Cookie + localStorage
```

#### LLM 配置

AI 驱动的命令（`agent`、`extract`、`summarize`）和 X-SQL `llm_*` 函数需要 LLM API 密钥。通过环境变量配置一个提供商：

```
DeepSeek                   DEEPSEEK_API_KEY
OpenRouter                 OPENROUTER_API_KEY, OPENROUTER_MODEL_NAME, OPENROUTER_BASE_URL
Volcengine (ByteDance)     VOLCENGINE_API_KEY, VOLCENGINE_MODEL_NAME, VOLCENGINE_BASE_URL
OpenAI-compatible           OPENAI_API_KEY, OPENAI_MODEL_NAME, OPENAI_BASE_URL
Aliyun Qwen (DashScope)    OPENAI_API_KEY, OPENAI_MODEL_NAME, OPENAI_BASE_URL
```

这些环境变量映射到 [application.properties](application.properties) 中对应的属性。示例：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

如果没有配置有效的 LLM 密钥，AI 命令会在启动时立即失败并显示明确的错误信息。

#### AI / 智能体

> **需要 LLM API 密钥** — 请参阅上方的 [LLM 配置](#llm-配置)。

提交自然语言任务，让 Browser4 的后端 AI 智能体自主规划和执行：

```
agent run <task>          提交自主任务（异步，立即返回任务 ID）
agent status <id>         查看正在运行的智能体任务的状态
agent result <id>         获取已完成的智能体任务的最终结果
extract <instruction>     使用 AI 从页面提取结构化数据。--schema <json>, --filename, --raw
summarize [instruction]   使用 AI 总结页面内容。--selector, --filename, --raw
```

- `agent run` 是异步的 — 后端智能体会推理、探索并执行，直到任务完成。
- 智能体命令基于任务 ID，不需要活动的 CLI 浏览器会话槽。
- 智能体子命令不支持在 `batch` 模式中使用。

#### Swarm（并行抓取）

跨多个浏览器上下文编排并行抓取。`co` 前缀可作为 `swarm` 的别名使用。

```
swarm create          创建 swarm 抓取会话
                      --profile-mode, --max-open-tabs, --max-browser-contexts, --display-mode
swarm submit [url]    将 URL 或 X-SQL 载荷提交为抓取任务
                      --seed-file, --sql, --deadline, --expires, --refresh, --parse, --store-content
swarm query <url>     提交 X-SQL 查询，从已加载的网页中提取数据
                      --sql, --seed-file, --deadline, --expires, --refresh
swarm status <id>     查看抓取任务的状态
swarm result <id>     获取已完成的抓取任务的结果
```

种子文件为纯文本格式，每行一个 URL；`#` 注释和空行将被忽略。在 X-SQL 模板中使用 `@url` — 它会在服务器端替换为目标 URL。

#### 爬虫

```
crawl <url>   从某个 URL 开始爬取网站，跟踪链接
              --depth (-d), --out-link-selector (-ol), --out-link-pattern (-olp), --top-links (-tl),
              --args (-a), --refresh, --parse, --expires, --store-content, --priority (-p),
              --page-load-timeout, --ignore-url-query, --no-norm, --readonly
```

#### 批处理与循环

```
batch <command...>  在单个进程中执行多个命令
                    --bail（在首个失败处停止）, --json（从 stdin 读取 JSON 命令）
loop [task]         按间隔重复执行任务
                    --name, --interval (-i), --count (-n), --timeout (-t),
                    --shell, --list, --stop, --status
```

#### 安装与升级

```
install      安装自包含的 Browser4 运行时包。--tag <version>, --force
uninstall    移除全局安装的 browser4-cli 和运行时数据。--yes (-y), --dry-run
upgrade      升级到最新版本或指定的发布标签。--tag <version>, --force
```

#### 支持批处理的命令

以下命令可以在 `batch` 和 `batch --json` 中使用：

```
goto  go-back  go-forward  reload  press  type  keydown  keyup
click  dblclick  hover  fill  select  check  uncheck  drag  upload
mousemove  mousedown  mouseup  mousewheel  scroll  wait
get  eval  snapshot  screenshot  pdf  dialog-accept  dialog-dismiss
resize  tab-list  tab-new  tab-close  tab-select
```

#### CLI 超时配置

某些命令可能需要比默认 HTTP 超时更长的时间。使用以下环境变量调整超时：

```
BROWSER4_CLI_HTTP_TIMEOUT_SECS          30    大多数命令（click, snapshot, screenshot 等）
BROWSER4_CLI_INPUT_TIMEOUT_SECS         90    文本输入命令（type, fill）
BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS   120    导航命令（goto, reload, go-back, go-forward）
```

文本输入命令使用更长的默认超时，因为在表单字段中输入文本——尤其是在复杂页面上——可能比简单交互更慢。如果文本输入命令超时，操作**可能已部分执行**。超时后，请在重试前使用 `snapshot` 或 `get` 验证字段内容。

```shell
# 为重型页面增加输入超时
export BROWSER4_CLI_INPUT_TIMEOUT_SECS=180

# 为慢速网站增加导航超时
export BROWSER4_CLI_NAVIGATION_TIMEOUT_SECS=300
```

#### 快速示例

```shell
# 打开浏览器会话
browser4-cli open --headed https://browser4.io

# 导航到页面——如果没有活动会话则自动打开一个
browser4-cli goto https://browser4.io

# 检查页面——注意可交互节点上的 eN 标签
browser4-cli snapshot --boxes

# 使用快照中的 ref 进行交互
browser4-cli click e15
browser4-cli type e15 "Hello World"
browser4-cli press e15 Enter

# 使用 CSS 选择器提取数据
browser4-cli get text ".product-title"
browser4-cli get attr ".product-image" data-src

# 使用 X-SQL 进行 DOM 快照
browser4-cli htmlsnapshot capture
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#main-content"
browser4-cli htmlsnapshot query --sql @query.sql
browser4-cli htmlsnapshot grep -i "error"

# AI 驱动的提取和总结（需要 LLM 密钥——请参阅上方的 LLM 配置）
browser4-cli extract "product name, price, and rating as JSON"
browser4-cli summarize "key points in 3 bullets"

# 自主智能体任务
browser4-cli agent run "Search amazon for mechanical keyboards, compare the top 3, write a summary"

# 使用 swarm 进行并行抓取
browser4-cli swarm create --max-open-tabs 12 --display-mode HEADLESS
browser4-cli swarm submit --seed-file ./urls.txt --refresh --store-content
browser4-cli swarm result scrape-task-1

# 批量执行多个命令
browser4-cli batch "goto https://browser4.io" "snapshot" "screenshot"

# 截图
browser4-cli screenshot --full-page

# 管理 Cookie 和存储
browser4-cli cookie-list
browser4-cli state-save session.json

# 完成后关闭会话
browser4-cli close
```

---

## 🚀 从源码构建

**前置条件：** Git、JDK 17+（推荐 21+）、Chrome/Chromium 以及 PowerShell 7（仅 Linux/macOS 需要）。完整的前置条件表格、平台特定工具和 Chrome 自动检测路径，请参见 [从源码构建](docs/build-from-source.md)。

1. **克隆仓库**
   ```shell
   git clone https://github.com/platonai/Browser4.git
   cd Browser4
   ```

2. **配置你的 LLM API 密钥**

   > 编辑 [application.properties](application.properties) 并添加你的 API 密钥，或设置环境变量。支持的提供商及变量名请参阅 [LLM 配置](#llm-配置)。

3. **构建项目**
   ```shell
   ./mvnw -DskipTests
   ```

---

🎬 YouTube：
[![观看视频](https://img.youtube.com/vi/_BcryqWzVMI/0.jpg)](https://www.youtube.com/watch?v=_BcryqWzVMI)

📺 Bilibili：
[https://www.bilibili.com/video/BV1kM2rYrEFC](https://www.bilibili.com/video/BV1kM2rYrEFC)

---

## 🧬 自动提取

基于自监督/无监督机器学习的大规模、高精度字段发现与提取 — 无需 LLM API 调用，不消耗 token，确定性强且速度快。

**它能做什么：**
- 高精度学习商品/详情页面上的每个可提取字段（通常数十到数百个）。
- 当 Browser4 在 GitHub 上获得 10K 星标时开源。

**为什么不仅仅使用 LLM？**
- LLM 提取会增加延迟、成本和 token 限制。
- 基于 ML 的自动提取是本地化的、可复现的，并可扩展至每天 10 万 ~ 20 万页面。
- 你仍然可以结合使用两者：用自动提取获取结构化基线数据 + 用 LLM 进行语义增强。

**快速命令（PulsarRPAPro）：**
```bash
# 注意：需要 MongoDB
curl -L -o PulsarRPAPro.jar https://github.com/platonai/PulsarRPAPro/releases/download/v3.0.0/PulsarRPAPro.jar
```

**集成状态：**
- 现已通过配套项目 [PulsarRPAPro](https://github.com/platonai/PulsarRPAPro) 可用。
- 原生的 Browser4 API 接口正在规划中；请关注版本发布以获取更新。

**核心优势：**
- 高精度：>95% 字段被发现；绝大多数字段准确率 >99%（在测试域名上的参考数据）。
- 对选择器变化和 HTML 噪声具有鲁棒性。
- 零外部依赖（无需 API 密钥）→ 大规模使用时具有成本优势。
- 可解释：生成的选择器和 SQL 透明且可审计。

👽 使用机器学习智能体提取数据：

![自动提取结果快照](docs/assets/images/amazon.png)

（即将推出：更丰富的仓库内示例和直接的 API 接口。）

---

## 📦 模块概览

```
cli                     基于 Rust 的 CLI，支持 SKILLS
browser4-core           核心引擎：会话、调度、DOM、浏览器控制
browser4-agentic        智能体实现、MCP 和技能注册
browser4-rest           Spring Boot REST 层和命令端点
browser4-standalone     智能体和爬虫编排，包含产品打包
examples                可运行的示例和演示
browser4-tests          端到端测试、重量级集成测试和场景测试
```

---

## 🧪 测试夹具服务器 (MockSite)

Browser4 包含一个轻量级的 **MockSite** 服务器，用于提供静态 HTML 页面以进行测试和演示。从仓库根目录启动：

**Windows:** `./bin/test.ps1 mock-site -Dmock.site.port=18080`
**Linux/macOS:** `./bin/test.sh mock-site -Dmock.site.port=18080`

关键演示页面位于 `http://localhost:18080/generated/`。完整的页面列表、环境变量、Python 回退方案和基于 Maven 的启动方式，请参见 [MockSite](docs/mocksite.md)。测试分类和标签系统请参见 [测试分类](docs/TESTING.md)。

---

## 🤝 支持与社区

加入我们的社区，获取支持、反馈和协作！

- **GitHub Discussions**：与开发者和用户交流互动。
- **Issue Tracker**：报告 bug 或请求新功能。
- **社交媒体**：关注我们以获取更新和新闻。

我们欢迎贡献！详情请参见 [CONTRIBUTING.md](CONTRIBUTING.md)。

---

## 📜 文档

完整的文档可在 `docs/` 目录和我们的 [GitHub Pages 站点](https://platonai.github.io/browser4/) 上找到。

---

## 🔧 代理配置 - 解锁网站访问

<details>

设置环境变量 `PROXY_ROTATION_URL` 为你的代理服务商提供的轮换 URL：

```shell
export PROXY_ROTATION_URL=https://your-proxy-provider.com/rotation-endpoint
```

每次访问此轮换 URL 时，它应返回包含一个或多个新代理 IP 的响应。
如果你需要此类型的 URL，请联系你的代理服务提供商。

</details>

---

## 许可证

Apache 2.0 许可证。详情请参见 [LICENSE](LICENSE) 文件。
