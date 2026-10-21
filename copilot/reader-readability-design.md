# Reader 能力统一设计：`read` + `htmlsnapshot readability`

> 日期：2026-08-25
> 来源：合并 `docs-dev/copilot/agent-browser-cli-gap-analysis.md`（C 档 `read`，Sprint 2 计划）与 `htmlsnapshot readability` 影响评估
> 状态：设计建议（待评审）

## 1. 目标与定位

一个**共享的启发式正文提取核心**，支撑两条产品入口：

- **`read <url>`** — 零 token 快速读（对齐 agent-browser），面向"给我这篇文章"的即时场景，不依赖浏览器会话。
- **`htmlsnapshot readability`** — 快照家族扩展，面向"对已捕获/当前会话页面做正文提取"，输出结构化 JSON。

两者共享同一提取算法与输出结构，避免双实现漂移。

### 定位对比（写入文档时需讲清）

| 能力 | 方式 | 成本 | 适用 |
|---|---|---|---|
| `extract` | LLM 指令式 | token | 自然语言指令、多步理解 |
| **readability（新）** | 启发式打分，离线确定性 | 0 token | 长文/新闻正文快速提取 |
| `get text` / X-SQL | CSS 选择器 | 0 | 结构化字段提取、列表页 |
| `markdown.convert/fetch` | 全页转 md（排除式） | 0 | 整页归档、抓站 |

readability 是三者中唯一的"**自动定位正文区域**"能力，与 `extract` 互补而非替代（gap 分析原文："Browser4 的 extract 是 LLM 指令式，不同定位"）。

## 2. 总体架构

```
L3 CLI        read <url> [flags]              htmlsnapshot readability [--url]
                  │                                  │
L2 工具       markdown.read (browser4-markdown)   html_snapshot.readability (browser4-rest)
                  │                                  │
L1 核心       ReadabilityExtractor (browser4-skeleton, 纯 jsoup, 零新依赖)
                  │
         FeaturedDocument (jsoup)  ← 存储快照 / HTTP 抓取 / 会话页面
```

- **依赖方向**：`browser4-rest → browser4-skeleton`（已有先例：`PageSummaryIndexService`）；`browser4-markdown → browser4-skeleton`（插件本就依赖 pulsar core 构件，需在 pom 确认）。
- **注册**：两个工具都走 `ToolMount` 自动注册 + `CustomToolRegistry` 域解析，**无需改 `MCPToolController.kt`**（`html_snapshot_*` / `markdown_*` 均已验证按最长前缀路由）。
- **CLI**：`read` 是新顶层命令（首个由插件工具支撑的 CLI 命令）；`htmlsnapshot-readability` 是既有家族成员。

## 3. 共享核心：ReadabilityExtractor

**位置**：`browser4-skeleton/.../skeleton/workflow/parse/html/ReadabilityExtractor.kt`（与 `PageSummaryIndexService` 同包，供 rest / 插件 / 未来能力复用）。

**签名**：

```kotlin
data class ReadabilityResult(
    val title: String,      // <title> 或 H1 回退
    val byline: String,     // meta[name=author]
    val siteName: String,   // meta[property=og:site_name]
    val excerpt: String,    // meta[name=description] 或首段截断
    val content: String,    // 净化后正文 HTML（语义标签 + 保留类）
    val textContent: String,// 纯文本
    val length: Int,        // 正文字符数
    val url: String,
    val confidence: Double, // 得分归一化，供下游判断
)

class ReadabilityExtractor(options: ReadabilityOptions = ReadabilityOptions()) {
    fun extract(doc: org.jsoup.nodes.Document): ReadabilityResult?
}
```

**算法**（参照 Mozilla Readability 的 jsoup 移植，手写 ~250–350 行）：

1. **预清理**：移除 `script/style/noscript/nav/aside/form/iframe/svg`、隐藏元素（`display:none`、`visibility:hidden`）、低文本密度的注释节点。
2. **候选识别**：`<article>/<main>/<div>/<p>/<td>` 等块级元素，文本量 > `charThreshold` 者入候选。
3. **打分**：文本密度公式——正文文本量 /（1 + 标签数 + 链接文本量加权）。链接越密（导航/聚合特征）得分越低。
4. **选优净化**：最高分候选为正文容器，递归剔除空段落、多余链接、广告容器；清理 class/id（保留 `keepClasses` 或 `classesToPreserve` 名单，如代码高亮类）。
5. **元数据**：title/byline/siteName/excerpt。

**选项**：`charThreshold`（默认 500）、`keepClasses`（默认 false）、`classesToPreserve`（默认空）、`maxElemsToParse`（默认 0 不限）、`allowedVideoRegex`。

**关键决策**：
- **选 jsoup 后端而非页面内注入 Readability.js**：与 htmlsnapshot 家族"读存储 HTML"的语义一致、离线可跑、单测直接喂 HTML；注入方案算法保真但依赖活动浏览器、双维护，留作后续优化项（提取器接口预留 `extractFromLiveDom` 扩展点）。
- **手写而非引入 readability4j**：零新依赖、无 BOM 评审成本、代码可控；`ReadabilityExtractor` 内部按阶段拆分，未来若要替换为移植版只需换实现。

## 4. 入口 A：`htmlsnapshot readability`

### 后端（browser4-rest）

`HTMLSnapshotToolExecutor` 新增方法（toolSpec 注册 + `callFunctionOn` 分支，其余自动路由）：

```text
readability(sessionId, url?) → JSON
```

- 无 `url`：读当前会话页面（`getOrNull ?: capture`，与 scrape/export 同构）。
- 有 `url`：独立抓取（同 `query` 的 `@url` 模式）。
- 输出：`ReadabilityResult` 序列化 JSON。

### CLI（cli/browser4-cli）

- `commands.rs`：`CommandDef htmlsnapshot-readability`（Category::Snapshot，tool `html_snapshot_readability`，参数 `url?`，选项 `--text-only`、`--json`；`batch_supported: false` 起步）。
- `main.rs`：`no_snapshot_commands()` 加项；dispatch match 加分支；新 handler（仿 `handle_html_snapshot_summary`，展示 title/byline/长度/来源，正文分页输出防 256KB 类问题）。
- `help.rs`：display-name 映射 + `generate_command_help` 分支。
- `tips.rs`：映射 + 新 tip（"一步提取正文，无需手写选择器"）。

**无需改动**：`MCPToolController.kt`、`HTMLSnapshotToolMountConfiguration.kt`。

## 5. 入口 B：`read`（零 token 快速读）——插件内实现，走 plugins 命令体系

> **落地决策（2026-08-28 评审）**：`read` **不进 CLI 主命令**，仅在 `browser4-markdown` 插件内实现为 MCP 工具 `markdown.read`，通过现有 **`plugin-<name>` 动态命令体系**暴露（`browser4-cli plugin-markdown read --url <url> ...`，由 `handle_dynamic_plugin_command` 自动发现 `/mcp/tools` 并路由），**无需任何 CLI 改动**。

### 后端（browser4-markdown 插件扩展，不新建插件）

`MarkdownToolExecutor` 新增 `read` 方法（toolSpec + 分支 + `ReaderService` bean 装配）：

```text
markdown.read(url, requireMd?, llms?, outline?, filter?, allowedDomains?) → ReadResult
```

**抓取管线（按优先级）**：

1. **llms.txt 发现**（`llms: true` 时）：站点根目录 `llms.txt` / `llms-full.txt` → 取对应条目直接返回，零解析。
2. **内容协商**：HTTP 请求带 `Accept: text/markdown`；服务器直出 md → 直接返回，零解析。
3. **启发式提取**：HTTP 抓 HTML → `ReadabilityExtractor.extract(Jsoup.parse(html, url))` → 对**提取区域**复用 `SiteCrawler.convertHtmlToMarkdown`（已有 jsoup HTML→md 路径，`fetchAndConvert` 已验证）→ markdown。
4. **失败路径**：`requireMd=true` 且非 md 来源 → 报错退出；SPA/JS 渲染页 → 提示 `goto` + `markdown.convert`（或后续 `--browser` 标志）。

**输出**：`{markdown, title, byline, siteName, url, charCount, source: llms|negotiation|extractor, outline?: [...], filtered?: bool}`。

**安全**：`allowedDomains` 白名单（默认同站点内链），防 SSRF（gap 分析要求）。

### CLI（无主命令改动）

`read` 经 `plugin-markdown` 动态命令暴露，用法：

```text
browser4-cli plugin-markdown read --url <url> [--requireMd true] [--llms true] [--outline true] [--filter <section>] [--allowedDomains example.com]
```

- 方法解析：`resolve_plugin_method` 将首个位置参数 `read` 映射到工具 `markdown_read`，`--key value` 透传为工具参数，自动附加 `sessionId`。
- `plugin`（裸命令）列表会自动包含 `markdown` 域。

## 6. 关键设计决策汇总

| # | 决策 | 理由 |
|---|---|---|
| 1 | 算法放后端 jsoup（方案 A），不做页面注入 | 与家族语义一致、离线、可单测、零 CDP 陷阱门禁 |
| 2 | 手写提取器，不引入 readability4j | 零依赖零 BOM 评审；接口预留替换位 |
| 3 | 两入口共用 `ReadabilityResult` 与同一提取器 | 防双实现漂移；`read` 的 markdown 化是提取后的视图 |
| 4 | `read` 复用 `SiteCrawler.htmlToMarkdown` | 已有 jsoup HTML→md 路径（已改为 open），零新转换代码 |
| 5 | llms.txt / 内容协商优先，提取兜底 | "零 token 快路径"的完整实现：站点给 md 就拿 md |
| 6 | `htmlsnapshot readability` 默认读存储快照，`--url` 独立抓取 | 与家族 7 工具语义一致 |
| 7 | **`read` 仅存在于插件，走 `plugin-markdown` 动态命令体系** | 用户评审决策：不进 CLI 主命令；零 CLI 触点、插件独立版本化 |
| 8 | 输出默认防大（正文分页 / `--text-only` / `charCount` 提示） | 吸取 snapshot 256KB 教训 |

## 7. 测试策略

| 层 | 内容 |
|---|---|
| 单测 | `ReadabilityExtractorTest`（正文页/高杂讯页/短页/空页/多候选竞争/元数据）；`HTMLSnapshotToolExecutorTest` 补 readability；`MarkdownToolExecutorTest` 补 read（mock 管线） |
| Fixture | `HtmlSnapshotMockController` 新增"高杂讯文章页"（导航+广告+侧栏包裹正文）；mock server 新增 `/llms.txt`、`/llms-full.txt` 端点；复用现有 `/htmlsnapshot-test/news` |
| E2E（Rust） | `mock_server.rs` 新增 `test_e2e_htmlsnapshot_readability`、`test_e2e_read_*`（group 挂 `htmlsnapshot` / 新 group `read`），`mod.rs` 命令清单同步 |
| E2E（Kotlin） | `HtmlSnapshotScenariosE2ETest` 加 readability 场景；read 走真实 HTTP mock 站点 |
| 真实站点适配 | 首批 ~10 站点矩阵（博客/新闻/文档站/电商详情页），人工标注通过率，沉淀到 `docs/`（gap 分析"大量站点适配测试"的落地形态） |

## 8. 文档更新清单（AGENTS.md 规则）

| 文件 | 内容 |
|---|---|
| `skills/browser4-cli/SKILL.md` | 命令表 + 提取方法决策树（§4）加入 read/readability 定位 |
| `skills/browser4-cli/references/htmlsnapshot.md` | 新增 `## Readability` 章节 |
| `skills/browser4-cli/references/read.md`（新增） | read 完整参考：管线、flags、与 extract/convert 的区分 |
| `skills/browser4-cli/references/quickstart.md` | 可选示例 |
| `docs/htmlsnapshot-inspect-summary.md` | 可选提及 |
| `README.md` / `README.zh.md` / `cli/browser4-cli/README.md` | 命令列表同步 |
| `help.rs` / `tips.rs` | 命令帮助 + tips |

## 9. 分阶段实施计划

| 阶段 | 内容 | 估时 | 产出 |
|---|---|---|---|
| **P1** | `ReadabilityExtractor`（skeleton）+ `html_snapshot.readability` + `htmlsnapshot readability` CLI + 单测/e2e + fixture | 2–3 人日 | 快照家族正文提取闭环 |
| **P2** | `markdown.read`（提取→md 复用）+ llms.txt + 内容协商 + `plugin-markdown read` 暴露 + 单测/e2e | 3–5 人日 | 零 token 快速读闭环（插件体系） |
| **P3** | outline/filter 打磨 + 站点适配矩阵 + 文档全量同步 | 2–3 人日 | 质量与可信度 |

合计约 **1.5–2 周**，与 gap 分析 C 档估算（1–2 周）一致；P1 先行可独立交付，P2 依赖 P1 的提取器。

> **实施状态（2026-08-28）**：P1 ✅ 完成（提取器 + 后端工具 + CLI 命令 + 9 个单测 + 2 个 e2e 场景）；P2 ✅ 完成（ReaderService 管线 + `markdown.read` 工具 + `plugin-markdown read` e2e 场景 + 10 个单测）；P3 ✅ 完成（真实站点适配矩阵 10 站点见 `docs/readability-site-matrix.md`，高杂讯 fixture 见 `HtmlSnapshotMockController./htmlsnapshot-test/readability-article`；改进项 backlog 见矩阵文档 §3）。

> **agent-browser 对齐轮（2026-08-28）**：对照 `agent-browser`（Rust `cli/src/read.rs`）逐项补齐 `markdown.read` 管线：
> 1. **llms.txt 逐级向上发现** — 从 URL 路径最深目录到站点根逐级探测 `llms.txt`/`llms-full.txt`（原实现只查根目录）；
> 2. **llms 模式扩展** — `llms` 参数兼容 `true`（原语义，改名为 `discover`）并新增 `index`（llms.txt 格式化为可过滤链接索引）/ `full`（llms-full.txt 按章节过滤）；
> 3. **`{path}.md` 兄弟回退** — 主响应为 HTML 时尝试 `<path>.md`（根路径为 `/index.md`）；
> 4. **llms.txt 链接路由** — 自动解析 llms.txt 链接列表，按 doc-path 精确匹配 → origin+末段/slug 启发式定位目标文档的 markdown 源并抓取（`llms-link` 路径，requireMd 下仅接受 markdown 类型）；
> 5. **逐跳重定向域校验** — 关闭 OkHttp 自动重定向，手动跟随（≤10 跳）并对每一跳执行 `allowedDomains` 校验（原实现只校验初始 URL）；
> 6. **体积上限 2MB + `truncated` 标记**，输出新增 `finalUrl`（重定向后有效 URL）。
>
> 保留差异：HTML 兜底仍走 **Readability 文章级提取**（agent-browser 为整页 markdownish），这是本项目的质量优势；`timeoutMs` 参数新增（agent-browser 同款）。`ReaderServiceTest` 新增 8 个用例覆盖以上路径。

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 提取质量因站而异（最大风险） | P1 就建 fixture 集；P3 站点矩阵量化通过率；提取器 `confidence` 输出供用户判断 |
| 输出体积大 | 默认分页/`--text-only`/`charCount` 摘要 |
| `read` SSRF | `allowedDomains` 白名单（默认同站）+ 仅 GET 只读请求 |
| 内容协商/llms.txt 依赖站点支持 | 明确 fallback 链，`--require-md` 让用户显式要求 md 来源 |
| 命令面增长漏同步 | 4 处命令集精确断言测试自动把关（良性闸门） |
| 插件依赖方向 | P1 实施前确认 browser4-markdown pom 对 skeleton 的依赖 |
| 与既有审计建议的关系 | 本方案同时满足 hacker-news / search-summary / due-diligence 审计中 readability 相关建议（`htmlsnapshot readability` 子命令 + 一步正文提取 + 绕过付费墙后内容痛点） |
