# Firecrawl 兼容 `formats` 层设计草案

| 项 | 值 |
|---|---|
| 状态 | **Phase 0 + 1a/1b/1d 已实现；Phase 1c 主体已实现**（格式模型 / 校验 / Document / SPI / 核心 HTML→Markdown / 计划构建器 / 格式引擎 + 8 个 provider / SPI 接线与 contributor 消费 / runner + `page` 域 + CLI `page scrape` + REST `api/scrape`，**166 个单测全绿**，MCP 与 REST 两条路径均经真实后端 + 真实 Chrome 验证）；剩余：`strict` 三态、`expires`/`maxAge`、异步面、夹具页、Phase 2+ |
| 目标仓库 | Browser4 `4.14.0-rc.8` @ `e0a7d20858`（4.13.x 合并后的 4.14.x；设计起草时基线为 `d86b69fc8b`） |
| 对照基线 | Firecrawl `ce8ed1233`（见 [对照表](firecrawl-vs-browser4-output-formats.md)） |
| 一句话 | 给 Browser4 加一层「`formats[]` 请求 → 一次抓取扇出多种输出 → 返回 Firecrawl 形状 Document」的兼容面，并把它作为后续 branding/product/menu 等格式的**插件扩展点** |

---

## 1. 目标与非目标

### 目标

1. **G1 一个请求多种输出**：`POST /api/scrape` + MCP `scrape.run` + CLI `scrape`，请求体含 `formats[]`，一次返回一个 `data` 文档（Firecrawl v2 形状）。
2. **G2 一次抓取（capture-once）**：多格式共享**同一份快照**，绝不因 8 个格式抓 8 次页面。这是 Browser4 相对"多命令拼接"的核心收益。
3. **G3 行为可预测**：不可用/失败的格式有明确的降级契约（字段省略 + `warning`，或按严格模式 400/503），不静默吞掉。
4. **G4 可扩展**：`branding` / `product` / `menu` / `highlights` 等非核心格式通过 **SPI** 由插件贡献，核心不硬编码第三方服务。
5. **G5 与既有设施零重复**：复用 `htmlsnapshot` / `tab` / 插件工具执行器、页面存储与 `--expires`、异步任务框架与错误码体系。

### 非目标

- **N1** 不实现 `branding` / `product` / `menu` 的抽取算法本身（核心只提供 SPI + 挂载点，首批实现留给插件）。
- **N2** 不复制 Firecrawl 的**云托管 URL** 媒体语义：Browser4 媒体产物是本地文件；如需 URL 由调用方自建网关。
- **N3** 不引入按格式计费。
- **N4** 不新增任何直接调用 CDP 的方法（截图/导出全部复用既有实现），因此**不触发** `AGENTS.md` 的"直接 CDP 方法强制评审门"。若后续新增，按该章节四条门逐条过。
- **N5** 不做 Firecrawl 的 v1/v0 兼容（`screenshot@fullPage`、`extract`、`pageOptions`）；只在**归一化**阶段接受这两个别名以降低迁移成本（见 §6.1）。

---

## 2. 现状盘点

### 2.1 可复用设施

| 能力 | 现有资产 | 复用方式 |
|---|---|---|
| 页面抓取与快照 | `HTMLSnapshotToolExecutor`（domain `html_snapshot`）：`capture` / `export` / `scrape` / `scrape_all` / `query` / `summary` / `inspect` / `readability` | 作为各格式 provider 的**执行器**，一次 `capture` 后共享快照 |
| 快照缓存与版本 | `--expires <dur>`（读存储中的旧版本，不触碰 tab） | 映射 Firecrawl 的 `maxAge`；`capture-once` 的物理载体 |
| 活体产物 | `tab.screenshot`（元素/全页/视口索引/区域）、`tab.ariaSnapshot`、`tab.eval` | 视觉类 provider；**复用不新增 CDP 代码** |
| 正文提取 | `ReadabilityExtractor`（`browser4-skeleton/.../workflow/parse/html/`）→ `ReadabilityResult(content=清洗后HTML, textContent, title, byline, ...)` | `onlyMainContent=true` 时 markdown 的输入 |
| 结构化抽取 | X-SQL 引擎（`html_snapshot.query`）+ CLI 侧 `--format json\|csv\|table`、`--result-only` | `json` / `deterministicJson` / `attributes` provider |
| Markdown 转换 | `browser4-markdown` 插件 `MarkdownConverter`（Jsoup 实现，返回 `MarkdownResult`） | ⚠️ 在插件里——见决策 **D3** |
| 链接/图片 | `markdown.discoverLinks`；`image.detectImages`（domain `image`） | `links` / `images` provider |
| 媒体 | `media.detectVideos` / `download` / `extractAudio` / `getInfo` | `audio` / `video` provider |
| 元数据 | `seo.extractMeta`（domain `seo`）、`htmlsnapshot capture` 元数据 | `metadata` 区块 |
| AI 抽取 | `agent_extract` / `agent_summarize` | `question` / `summary`(LLM 模式) provider |
| 工具编排 | `AgentToolManager.execute(ToolCall)`：MCP 与内部调用**同一路径** | 引擎内部按计划逐步执行，天然复用参数归一化与错误语义 |
| 插件 SPI 先例 | `PageSummaryAlgorithm` + `PageSummaryAlgorithmRegistry` + `PageSummaryAlgorithmMount` + `PluginManager.wirePageSummaryAlgorithmMount()` | **逐条照搬**为 `PageFormatContributor`（§7） |
| 工具执行器注册 | `ToolMount` / `CustomToolRegistry` / `PluginManager` | 新增 `scrape` 执行器的注册路径 |
| 异步任务 | `AsyncTaskCache`、`ScrapeResponse(id/statusCode/isDone/resultSet)`、`/{id}/status`、`/{id}/result`、`/{id}/stream`(SSE) | 长任务（媒体下载/LLM）的异步面 |
| 错误码 | `ToolErrorCode`（`INVALID_ARGUMENT` 400 / `TARGET_UNAVAILABLE` 503 / `TIMEOUT` 504 / `UPSTREAM_ERROR` 502…） | 格式不可用与失败的**统一**上报（§6.3） |
| 输出契约 | `ToolSpec.outputSchema` + `ToolResultSchemas` + `ToolResultValidator` | Document 的 schema 声明与校验 |
| 测试夹具 | `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/` | 新增格式夹具页（§11） |

### 2.2 命名与路由冲突

| 已占用 | 说明 | 本设计的选择 |
|---|---|---|
| `POST /api/x/**` | 既有 `ScrapeController`（X-SQL/URL 抓取 + 任务） | **不动**。新端点用 `/api/scrape`（当前未被占用：已占用前缀为 `api/x`、`api/crawl`、`api/swarm`、`api/extractions`、`api/act`、`api/pages`、`api/config`、`api/system`、`api/doctor`、`api/plugins`、`api/skills`、`api/mcp`） |
| `<domain>.scrape` | 已有 `html_snapshot.scrape`（单元素取值） | 新工具 domain 用 `page`，方法 `scrape` → MCP 名 `page_scrape`；**避免**与 `html_snapshot_scrape` 混淆 |
| CLI `scrape` | 当前无该命令（`crawl` 已存在） | 采用单词命令 `scrape`（按 `AGENTS.md`：无子命令的单词命令保持裸 kebab） |

> **决策点**：MCP domain 名取 `page`（`page_scrape` / `page_formats`）而不是 `scrape`（`scrape_scrape` 别扭）也不是 `scrape_run`（与 CLI 名不对齐）。

---

## 3. 对外契约

### 3.1 REST

```
POST /api/scrape                    # 同步（默认），返回 { success, data }
POST /api/scrape/submit             # 异步，返回 { success, id }
GET  /api/scrape/{id}/status
GET  /api/scrape/{id}/result
GET  /api/scrape/{id}/stream        # SSE，可选
GET  /api/scrape/formats            # 能力发现：本部署支持哪些格式、为何不可用
```

- 前缀与既有控制器风格一致（`@RequestMapping("api/scrape", consumes = ALL, produces = APPLICATION_JSON)`）。
- 异步面直接复用 `AsyncTaskCache` 与 `/{id}/status|result|stream` 的既有形状。

### 3.2 MCP

| 工具名 | 参数 | 返回 |
|---|---|---|
| `page_scrape` | `url?`、`sessionId?`、`formats`、`options?`、`strict?` | Document JSON |
| `page_formats` | — | `[{id, available, reason, requires, plugin}]`（对齐 `htmlsnapshot algorithms` 的发现式设计） |

- `page_scrape` **不需要** `sessionId`：无 url 时用会话当前页，有 url 时在共享 scrape 会话上只读加载（与 `htmlsnapshot readability <url>` 的语义一致）。
- 需在 `MCPToolController.FRONTEND_TOOL_NAME_ALIASES` 注册前端别名。

### 3.3 CLI

```bash
browser4-cli scrape <url?> \
  --formats markdown,links,screenshot \
  --json-schema @schema.json \
  --only-main-content \
  --expires 1d \
  --out ./out \
  --strict \
  --format json|csv|table      # only affects json/x-sql result rendering
browser4-cli scrape formats    # 能力发现
```

- `--formats` 支持逗号分隔字符串，也支持 `@formats.json`（对象形式，带格式选项）。
- 产出规则：文本类默认打印到 stdout（`--out` 落盘）；二进制类（screenshot/audio/video/pdf）**必须**落到 `--out` 目录，文件名由 `metadata.scrapeId` + 扩展名派生。

> **命名约定**（本文档通篇遵循）
> - **MCP 工具名** = `{domain}_{method}`，例如 `html_snapshot_capture`、`html_snapshot_scrape`、`markdown_read`。
> - **CLI 调用**：核心域有专用命令（`htmlsnapshot get html <selector>`、`screenshot -o a.png --full-page`、`eval ...`）；**插件域**统一用 `plugin <domain> <method> --arg value`，例如 `plugin markdown read --url <url>`、`plugin image detectImages`、`plugin media extractAudio`、`plugin seo extractMeta`（参数由该工具的 `ToolSpec.arguments` 定义）。
> - 本文档 §2.1 / §5 的 `domain.method` 记法即上表 MCP 名。

### 3.4 请求体

```jsonc
{
  "url": "https://example.com/post",     // 可省略 → 用会话当前页
  "sessionId": "s1",                     // 可省略 → 共享 scrape 会话
  "formats": [
    "markdown",
    { "type": "screenshot", "fullPage": true, "quality": 80 },
    { "type": "json", "prompt": "...", "schema": { /* JSON Schema */ } }
  ],
  "onlyMainContent": true,               // 默认 true（对齐 Firecrawl）
  "expires": "1d",                       // 0s=活体（默认）
  "waitFor": 0,
  "timeout": 30000,
  "headers": { "Accept-Language": "zh-CN" },
  "strict": false,                       // true → 不可用格式返回 400/503 而非降级
  "async": false
}
```

归一化（对齐 Firecrawl `types.ts:760-769` 的 `preprocess`）：字符串 → `{type: s}`；**同时接受 v1 别名** `screenshot@fullPage → {type:"screenshot",fullPage:true}`、`extract → json`（与 `lib/key-restriction.ts:236-244` 的既有别名表复用同一张表，避免两处定义漂移）。

`formats` 缺省 = `["markdown"]`。

### 3.5 响应体

```jsonc
{
  "success": true,
  "data": {
    "markdown": "…",
    "links": ["…"],
    "screenshot": "file:///…/media/screenshot-<id>.png",   // 本地路径/文件 URL
    "json": { },
    "warning": "branding: format unavailable (no contributor installed)",
    "metadata": {
      "url": "…", "sourceURL": "…", "title": "…", "description": "…",
      "language": "…", "statusCode": 200, "scrapeId": "…",
      "cacheState": "hit|miss", "cachedAt": "…",
      "ogTitle": "…", "favicon": "…", "wordCount": 1234,
      "formatsRequested": ["markdown", "links"], "formatsDelivered": ["markdown", "links"],
      "captureId": "<snapshot key>", "captureTime": "…"
    }
  }
}
```

Kotlin 形状（放 `browser4-skeleton`，见 §9 D1）：

```kotlin
data class ScrapedDocument(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val markdown: String? = null,
    val html: String? = null,
    val rawHtml: String? = null,
    val links: List<String>? = null,
    val images: List<String>? = null,
    val screenshot: String? = null,
    val audio: String? = null,
    val video: String? = null,
    val json: Any? = null,
    val summary: String? = null,
    val answer: String? = null,
    val highlights: String? = null,
    val attributes: List<AttributeValue>? = null,
    val readability: ReadabilityResult? = null,
    val changeTracking: ChangeTracking? = null,
    val warning: String? = null,
    val metadata: ScrapeMetadata,
) {
    /** Firecrawl parity: 未请求的字段不出现在响应里。 */
    fun retainRequested(requested: Set<String>): ScrapedDocument = …
}
```

> `@JsonInclude(NON_NULL)` + `retainRequested` 共同保证"未请求即不出现"。
>
> `deliveredFormats` / `withDeliveredFormats` 都**接收请求的 id 列表**（Phase 1d 修正）：字段名不能唯一确定格式（`json`/`deterministicJson` 共用字段、`video` 还填 `videos`、已废弃的 `query` 与 `highlights` 共用字段），不传请求集就会把调用方没要过的 id 报成"已交付"。详见 Phase 1d。

---

## 4. 执行模型：capture-once / fan-out

### 4.1 阶段图

```
                 ┌─────────────── Stage 0: ENSURE ───────────────┐
  url? ─────────▶│ 命中当前 tab？ 否则在共享 scrape 会话加载       │
                 │ LoadOptions: -expires / -requireNotBlank …    │
                 └───────────────────────┬───────────────────────┘
                                         ▼
                 ┌─────── Stage 1: CAPTURE（恰好一次）───────────┐
                 │ html_snapshot.capture → snapshot S（页面存储） │
                 │ key = 规范化 URL；返回 captureId/captureTime   │
                 └───────────────────────┬───────────────────────┘
        ┌────────────────────────────────┼────────────────────────────────┐
        ▼                                ▼                                ▼
 Stage 2: FROM_SNAPSHOT        Stage 3: LIVE_TAB              Stage 4/5: SERVICES
 markdown / html / rawHtml      screenshot / pdf               audio / video（媒体）
 links / images / attributes    aria（扩展）                   summary/question（LLM）
 json / deterministicJson                                      branding/product/menu（插件）
 summary（WPSI）/ inspect / readability
        └────────────────────────────────┼────────────────────────────────┘
                                         ▼
                        ┌──── Stage 6: ASSEMBLE ────┐
                        │ 裁剪未请求字段 + warning   │
                        │ + metadata 汇总            │
                        └───────────────────────────┘
```

### 4.2 关键不变量

| # | 不变量 | 理由 |
|---|---|---|
| I1 | **Stage 1 每个请求恰好一次** | capture-once 的全部价值；多格式共享 S |
| I2 | Stage 2 全部只读 S，不触碰 tab | 并发安全；`--expires` 语义自然落地；重试幂等 |
| I3 | Stage 3 才需要活体 tab；失败**不阻断** Stage 2 产物 | 截图依赖引擎能力（自托管 playwright 引擎 `screenshot:false`），不能因此让 markdown 也失败 |
| I4 | Stage 4/5 在所有本地产物**之后**执行 | 外部服务慢/贵；前面的产物先保证拿到 |
| I5 | 每步通过 `AgentToolManager.execute(ToolCall)` 执行 | MCP 与内部调用同一路径，参数归一化/错误码/超时行为一致 |
| I6 | 计划编译期即完成组合校验 | 非法组合在 400 一次说清，而不是跑到第 5 步才炸 |
| I7 | 每个 provider 的失败被分类（REQUIRED/DEGRADABLE/OPTIONAL） | 见 §6.2；不静默、也不过度失败 |

---

## 5. 格式 → 计划步骤矩阵

`S` = 捕获到的快照；`T` = 活体 tab；`M` = markdown 文本。

| format | stage | 执行器（`domain.method`） | 关键参数 | 输出字段 | 依赖 | 失败策略 |
|---|---|---|---|---|---|---|
| `markdown` | 2 | 核心 HTML→MD（D3） | `onlyMainContent` → 先 `html_snapshot.readability` 再转换 | `markdown` | S | **REQUIRED**（默认格式） |
| `html` | 2 | `html_snapshot.export` | `clean=true` | `html` | S | REQUIRED |
| `rawHtml` | 2 | `html_snapshot.export` | `clean=false` | `rawHtml` | S | REQUIRED |
| `links` | 2 | `markdown.discoverLinks`（缺插件则退化 `html_snapshot.query` + `<a>` 抽取） | — | `links` | S | DEGRADABLE |
| `images` | 2 | `image.detectImages`（缺插件则 `html_snapshot.query` + `img@src`） | — | `images` | S | DEGRADABLE |
| `attributes` | 2 | `html_snapshot.scrape_all` 逐 selector | `field=attr`、`absoluteUrls` | `attributes[]` | S | REQUIRED（指定了 selector 就是明确意图） |
| `json` | 2/4 | `html_snapshot.query`（X-SQL）或 `agent_extract --schema`（LLM 模式） | `schema`/`prompt` | `json` | S(+M) | REQUIRED |
| `deterministicJson` | 2 | `html_snapshot.query`（X-SQL，确定性） | `schema`（转 X-SQL 或校验结果） | `json` | S | REQUIRED |
| `summary`(WPSI) | 2 | `html_snapshot.summary` | `algorithm`（默认 wpsi） | `summary` | S | DEGRADABLE |
| `summary`(LLM) | 4 | `agent_summarize` | `instruction` | `summary` | M | DEGRADABLE |
| `question` | 4 | `agent_extract` / `agent_summarize` | `question` | `answer` | M | REQUIRED |
| `screenshot` | 3 | `tab.screenshot`（复用 `Browser4WebDriver.screenshotFullPage`） | `fullPage`/`quality`/`viewport`/`selector` | `screenshot`（本地路径） | T | DEGRADABLE（引擎不支持 → warning） |
| `pdf` *(扩展格式)* | 3 | `tab.pdf` / `pdf` 命令 | `filename` | `pdf` | T | DEGRADABLE |
| `audio` | 5 | `media.extractAudio`（或 `download`） | `—` | `audio`（本地路径） | 外部 `ffmpeg` | DEGRADABLE（对齐 Firecrawl：服务缺失只写 warning） |
| `video` | 5 | `media.detectVideos` → `media.download` | — | `video` / `videos[]` | 外部 | DEGRADABLE |
| `readability` *(扩展格式)* | 2 | `html_snapshot.readability` | — | `readability` | S | DEGRADABLE |
| `changeTracking` | 2+6 | 读上次快照（`--expires`）+ `snapshot --auto-diff` 语义 + 状态机 | `tag` | `changeTracking` | S + 存储 | DEGRADABLE（Phase 4） |
| `branding` | 2 | **SPI** `PageFormatContributor` | `mode` | `branding` | S(rawHtml) | DEGRADABLE |
| `product` | 2 | **SPI** | — | `product` | S(rawHtml) | DEGRADABLE |
| `menu` | 2 | **SPI** | — | `menu` | S(rawHtml) | DEGRADABLE |
| `highlights` | 4 | **SPI** | `query` | `highlights` | M | DEGRADABLE |
| `rawBase64` | 2 | 核心 `Base64.encode(exportedBytes)`（**独占格式**） | — | `rawBase64` | S | REQUIRED |

---

## 6. 校验、降级与错误契约

### 6.1 组合规则（与 Firecrawl 对齐，编译期拒绝）

| 规则 | 违反时 |
|---|---|
| `screenshot` 最多 1 个 | 400 `INVALID_ARGUMENT`："You may only specify one screenshot format" |
| `changeTracking` 必须同时含 `markdown` | 400，同上文案风格 |
| `json` 与 `deterministicJson` 互斥 | 400 |
| `rawBase64` 必须独占 | 400 |
| `attributes.selectors` 非空且 `selector`/`attribute` 均非空 | 400 |
| `json` 需 `prompt` 或 `schema` 至少其一（对齐 JS SDK 校验） | 400 |
| `formats` 为空数组 | 视为 `["markdown"]`（默认） |

> 规则集中在 `PageFormatPlanBuilder`，单一实现点，单测覆盖每条。

### 6.2 不可用格式的三态策略

| 情形 | `strict=false`（默认） | `strict=true` |
|---|---|---|
| **格式名未知**（拼写错误，如 `markdwon`） | **400 `INVALID_ARGUMENT`**（永不静默忽略——拼错和"暂不支持"必须可区分） | 同左 |
| 格式已知，但本部署不可用（插件未装 / 外部服务未配 / LLM key 缺失） | 字段省略 + `warning: "branding: unavailable (no contributor installed)"`；HTTP 200 | 503 `TARGET_UNAVAILABLE`，`hint` 指出缺哪个插件或配置 |
| 格式可用，但本次执行失败（超时 / 上游 5xx / 页面非商品页） | 字段省略 + `warning: "product: not a product page"`；HTTP 200 | 502 `UPSTREAM_ERROR` / 504 `TIMEOUT` |

这与 Firecrawl 的行为一致（其 `product`/`menu`/`audio`/`video` 在服务未配时也只用 `warning` 降级，见 `transformers/product.ts`、`video.ts`）。

### 6.3 错误码映射（复用 `ToolErrorCode`）

| 场景 | code | HTTP | retryable |
|---|---|---|---|
| 非法/未知格式名、非法组合 | `INVALID_ARGUMENT` | 400 | false |
| 格式已知但本部署不可用（strict） | `TARGET_UNAVAILABLE` | 503 | false |
| 会话不存在 / 浏览器已退出 | `SESSION_NOT_FOUND` / `SESSION_UNHEALTHY` | 404 / 409 | false / true |
| 抓取超时 | `TIMEOUT` | 504 | true |
| 外部服务（LLM/媒体/抽取服务）失败 | `UPSTREAM_ERROR` | 502 | true |
| 页面加载后无内容 | 不算错误：`metadata.statusCode` + `warning` | 200 | — |

**不做**：把多格式混合失败折叠成单一 `INTERNAL`——`warning` 里逐格式写清原因，且 `metadata.formatsDelivered` 让调用方一眼看出缺了什么。

---

## 7. 扩展点：`PageFormatContributor` SPI

### 7.1 分层

```
核心 FormatProvider（internal，任意步骤，写入任意字段）
   ↑ 只被 core 实现：markdown / html / rawHtml / links / images / attributes / json / screenshot / pdf / media
PageFormatContributor（public SPI，单字段，输入受限）
   ↑ 插件实现：branding / product / menu / highlights / changeTracking / 第三方
```

两层而不是一层：核心格式需要"capture / 多步 / 跨字段"的表达力，插件只需要"给我输入，还我一个值"。

### 7.2 SPI 定义（逐条对齐 `PageSummaryAlgorithm` 的既有范式）

```kotlin
package ai.platon.pulsar.skeleton.workflow.format

/**
 * 一个由插件贡献的页面输出格式。
 *
 * 内置格式不走这个接口（它们由核心的 FormatProvider 实现，需要多步与跨字段表达力）；
 * 插件贡献的格式是单字段的：给输入，还一个可直接放进 Document 的值。
 *
 * id 是稳定 API：出现在请求的 formats 里、`scrape formats` 的输出里和错误消息里，
 * 改名即破坏性变更。必须匹配 `[a-z][a-zA-Z0-9]*`（与 Firecrawl 格式名同形，如 branding/product/menu）。
 */
interface PageFormatContributor {
    val id: String
    val displayName: String
    val description: String

    /** 该格式默认写入哪个 Document 字段（同名字段）。 */
    val outputField: String get() = id

    /** 需要哪些输入；引擎保证在调用前已就绪，缺失则本格式判为不可用。 */
    val requires: Set<FormatInput>

    /** 本部署此刻是否可用（服务是否配置、团队是否开通）。默认 true。 */
    fun isAvailable(): Boolean = true

    /** 不可用原因，进 `warning` / `scrape formats` 的 reason。 */
    fun unavailableReason(): String? = null

    /** 实现必须线程安全：宿主会并发调用（不同 session）。 */
    suspend fun contribute(ctx: FormatContext): Any?

    companion object {
        /** id 合法性校验，注册时强制执行。 */
        fun isValidId(id: String): Boolean = Regex("[a-z][a-zA-Z0-9]*").matches(id)
    }
}

enum class FormatInput { SNAPSHOT, RAW_HTML, HTML, MARKDOWN, LIVE_TAB, URL, METADATA }

data class FormatContext(
    val snapshotKey: String,
    val url: String,
    val rawHtml: String?,
    val html: String?,
    val markdown: String?,
    val metadata: Map<String, Any?>,
    val options: Map<String, Any?>,
)
```

> **实现偏差（Phase 1d）**
>
> - 代码里 `metadata` 的类型是 `ScrapeMetadata` 而非 `Map<String, Any?>`——它是现成的强类型对象，摊平成 map 只会丢信息。
> - `FormatInput.LIVE_TAB` 目前**恒不满足**：`FormatContext` 只携带值，不带 tab 控制权，所以声明了它的 contributor 一律被判不可用（warning 点名 `needs a live tab (contributors are handed values, not a driver)`）。这使本节下方的 `BrandingFormatContributor` 示例恒不生效，需要一次 SPI 形状决策，见 Phase 1d 的"设计缺口"。
> - `outputField` 实际只能在 `branding` / `product` / `menu` / `highlights` 之间重定向：`ScrapedDocument` 的字段集是封闭的，全新的第三方 id 没有落脚字段（引擎会明确报错，不会静默丢弃）。同样见 Phase 1d 的"设计缺口"。

注册表（照搬 `PageSummaryAlgorithmRegistry`）：

```kotlin
class PageFormatContributorRegistry private constructor() {
    companion object {
        val instance: PageFormatContributorRegistry by lazy { PageFormatContributorRegistry() }
    }
    fun register(c: PageFormatContributor): Boolean   // id 冲突 → 跳过并 warn（与算法注册表一致）
    fun get(id: String): PageFormatContributor?
    fun list(): List<PageFormatContributor>
}
```

挂载点（加到 `browser4-skeleton/.../plugin/MountPoints.kt`，与 `PageSummaryAlgorithmMount` 并列）：

```kotlin
interface PageFormatContributorMount : PluginMount {
    fun getPageFormatContributors(): List<PageFormatContributor>
}
```

`PluginManager` 增加与 `wirePageSummaryAlgorithmMount`（`PluginManager.kt:251`）同形的一段，并接入 `PageSummaryAlgorithmRegistry` 旁边的一致性日志/异常策略。**已实现（Phase 1d）**，实现处额外做了**逐 contributor** 的 try/catch（不是逐 mount）：`register()` 对保留 id 是抛异常拒绝的，逐 mount 捕获会让一个坏 contributor 连累同 mount 的兄弟。

### 7.3 插件最小示例

```kotlin
@AutoConfiguration
class BrandingAutoConfiguration : PageFormatContributorMount {
    override fun getPageFormatContributors() = listOf(BrandingFormatContributor())
}

class BrandingFormatContributor : PageFormatContributor {
    override val id = "branding"
    override val displayName = "Branding profile"
    override val description = "Logo / colours / typography extracted from the page"
    override val requires = setOf(FormatInput.RAW_HTML, FormatInput.LIVE_TAB)
    override fun isAvailable() = brandingService.isConfigured()
    override suspend fun contribute(ctx: FormatContext) = brandingService.extract(ctx)
}
```

`plugin list` / `page_formats` 会自动列出它——插件声明 `ToolSpec.cliName` 的既有机制不适用于此（格式不是工具），但发现面走 `page_formats` 与 `htmlsnapshot algorithms` 对称。

---

## 8. 选项映射（Firecrawl `scrapeOptions` → Browser4）

| Firecrawl | Browser4 | 备注 |
|---|---|---|
| `maxAge` | `expires`（`0s` 默认 = 活体） | 语义一致：命中存储则不重抓 |
| `onlyMainContent`（默认 true） | `onlyMainContent` → readability 路径 | 见 D2 |
| `waitFor` (ms) | `waitFor`（`tab.delay` / `wait --load`） | — |
| `timeout` | `timeout` | 同时约束整条计划（含外部服务） |
| `headers` | 会话级 `set headers` 或请求级透传 | — |
| `mobile` | `set device` / 会话 capabilities | 属会话状态，配置后不随请求变 |
| `location.country/languages` | `set geo` | 同上 |
| `includeTags` / `excludeTags` | `htmlsnapshot export --clean` + X-SQL / DOM 选择器 | Browser4 侧用 X-SQL 表达更自然 |
| `actions` | `batch` 步骤 或 `htmlsnapshot capture` 前执行 | Phase 3 支持 |
| `parsers`（PDF/图片输入） | ⚠️ **无对应**：Browser4 是浏览器侧工具 | 明确记为 gap，不假装支持 |
| `proxy` / `skipTlsVerification` | 会话/浏览器启动配置 | — |
| `storeInCache` | 恒为 true（页面存储） | — |

---

## 9. 关键设计决策

| # | 决策 | 理由 | 备选与否决原因 |
|---|---|---|---|
| **D1** | `ScrapedDocument` + `PageFormatContributor` SPI 放 `browser4-skeleton`；引擎放 `browser4-agent-tools`；MCP/REST 外壳放 `browser4-rest` | 与 `PageSummaryAlgorithm`（skeleton）+ `HTMLSnapshotToolExecutor`（rest）的既有分层一致；插件只需依赖 skeleton | 全放 rest → 插件被迫依赖 rest（重）；全放 core → core 不该知道 MCP |
| **D2** | `onlyMainContent=true` 走 `ReadabilityExtractor`（确定性），**不**默认走 LLM | 默认格式必须零外部依赖、零成本、可重复；Firecrawl 的"正文"也是启发式 | 直接 `export --clean` → 保留导航噪声；LLM → 默认路径变贵变慢 |
| **D3** | 核心**自建** HTML→Markdown（`ai.platon.pulsar.skeleton.workflow.parse.html.HtmlToMarkdown`），**不改动** `browser4-markdown` 插件的既有链路 | 复核源码后发现插件有两条**不同**的转换路径：`MarkdownConverter` 是在页面里跑 **CDP JS 探针**（`driver.evaluate(...)`，需要活体 tab，与 I2「Stage 2 只读快照」直接冲突）；`SiteCrawler.htmlToMarkdown` 是给爬取用的**简化 Jsoup 转换器**（带插件自有的 front matter / exclude 配置，且不处理行内链接语义）。两者都不能服务"从快照 HTML 派生 markdown"这条路，所以核心实现自己的转换器，插件保持原样 | 原草案认为插件转换器是 Jsoup 且可整体提升——按此实施会既丢掉活体 DOM 语义、又破坏插件的配置行为。**后续（Phase 6+）可选**：让 `SiteCrawler.htmlToMarkdown` 委托核心转换器，消除双实现；届时要先补插件侧的等价性测试 |
| **D4** | MCP domain 用 `page`（`page_scrape`），REST 用 `/api/scrape` | 避免与 `html_snapshot.scrape`、`api/x` 的 `ScrapeController` 概念混淆 | `scrape.run` → 与 CLI `scrape` 名不对齐，且 `scrape` 与既有 `ScrapeService` 撞名 |
| **D5** | 未知格式名 **400**，已知但不可用 **降级 + warning**（strict 时 503） | 拼写错误必须立刻可见；能力缺失必须可降级（Firecrawl 同样只用 warning） | 全部降级 → 拼错静默；全部报错 → 一个可选的 audio 让整个请求失败 |
| **D6** | 媒体/截图产物落地为**本地文件路径**，不做托管 URL | Browser4 是本地/自托管工具，没有云存储可依赖 | 伪造 URL → 调用方拿到打不开的链接 |
| **D7** | `changeTracking` 分两期：Phase 1 先做 "snapshotKey + 上次快照 + AX diff"，Phase 4 再补 `changeStatus` 状态机与 `diff.json` | 状态机需要存储 schema 与保留策略（ZDR/清理），不应阻塞其余格式 | 一次做全 → 阻塞主路径 |
| **D8** | 引擎**不新增任何直接 CDP 方法**（截图复用 `screenshotFullPage`，导出复用 `export`） | `AGENTS.md` 的四条强制评审门只在新增 CDP 路径时触发；本设计刻意绕开 | 自造截图 → 需重做防检测/负面路径/跨平台/真页测试四门 |

---

## 10. 分阶段实施计划

### Phase 0 — 契约与骨架（✅ 已实现）

落地包：`ai.platon.pulsar.skeleton.workflow.format`（`browser4-core/browser4-skeleton`）。

| 文件 | 内容 |
|---|---|
| `.../workflow/format/FormatOption.kt` | `PageFormats`（格式表：CORE/CONTRIBUTED/DEPRECATED、别名、字段映射）、`PageFormat`、`FormatViewport`、`AttributeSelector`、`FormatIssue` + `FormatIssueCode`、`FormatOptionSchema`（`parse`/`normalize`/`validate`）、列表扩展 `hasFormat`/`formatOf`/`formatTypes` |
| `.../workflow/format/ScrapedDocument.kt` | `ScrapedDocument`（Firecrawl 形状 + `retainRequested`/`withWarning`/`deliveredFormats`/`withDeliveredFormats`）、`AttributeValue`、`ScrapeMetadata` |
| `.../workflow/format/PageFormatContributor.kt` | SPI：`PageFormatContributor`、`FormatInput`、`FormatContext` |
| `.../workflow/format/PageFormatContributorRegistry.kt` | 单例注册表（id 校验、核心 id 保留、first-wins、`unavailable()`） |
| `.../plugin/MountPoints.kt` | 新增 `PageFormatContributorMount`（纯新增接口，未改动既有行为） |
| `src/test/.../format/FormatOptionTest.kt` | 27 个用例：归一化（字符串/对象/混合/大小写/别名/结构化选项）+ 每条组合规则 + 每条单格式规则 + 字段映射 |
| `src/test/.../format/ScrapedDocumentTest.kt` | 10 个用例：裁剪语义、封套字段常驻、共享字段（json/answer/video）、`deliveredFormats`、`warning` 合并、NON_NULL 序列化 |
| `src/test/.../format/PageFormatContributorRegistryTest.kt` | 10 个用例：注册/解析/排序/非法 id/保留 id/重复 first-wins/`unavailable`/并发注册恰好一次/`contribute` 可调用 |

验证命令与结果：

```bash
./mvnw.cmd -o -pl :browser4-skeleton -D"test=FormatOptionTest,ScrapedDocumentTest,PageFormatContributorRegistryTest" \
  -D"failIfNoTests=false" -D"jacoco.skip=true" test
# Tests run: 47, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (~2 min)
```

**与草案的偏差（需在 Phase 1 前知悉）**：

1. ~~`MountPoints.kt` 只声明了 `PageFormatContributorMount`，**`PluginManager` 尚未接线**（Phase 0 刻意不碰启动路径）。因此在 Phase 1/6 补上 `wirePageFormatContributorMount` 之前，插件贡献的格式不会自动注册，注册表只在测试与显式调用中生效。~~ **已在 Phase 1d 解决**，且当时文档只说了"注册表不生效"——真实情况更严重：引擎当时**根本不查注册表**，即便手动注册也不会被调用。
2. 注册表额外拒绝**核心/废弃格式 id**（`markdown`、`screenshot`、`query`…）被插件占用 —— 草案 §12 R7 的命名空间冲突在 Phase 0 就从源头堵住，而不是留到运行期。
3. 未知格式名归入 `FormatIssueCode.UNKNOWN_FORMAT` 并保留用户原始拼写，为 §6.2 的「拼错 → 400」提供了判定依据；「已知但不可用」仍留给 Phase 1 的运行时判定。
4. `FormatOptionSchema` 未包含请求级选项（`onlyMainContent`/`expires`/`strict` 等）——那是 REST/MCP 请求 DTO 的职责，属 Phase 1。

### Phase 1a/1b — 确定性格式与引擎（✅ 已实现）

**核心转换器**（`browser4-core/browser4-skeleton/.../workflow/parse/html/HtmlToMarkdown.kt`）

纯函数式 Jsoup 转换器：标题 / 段落 / 行内链接（绝对化）/ 强调 / 行内代码 / 删除线 / 硬换行 /
有序与嵌套列表 / 表格（含无表头与错行补位、管道转义）/ 带语言的围栏代码（反引号更长时自动加长围栏）/
引用 / 图片（跳过 data URI）/ 水平线 / 定义列表；剥离 script/style/noscript/template/svg/canvas/iframe；
可选 front matter 与 source-url 注释、`excludeSelectors`。**不使用 `:scope` 选择器**（classpath 上的 jsoup 不支持），
改用显式 `children()` 过滤；硬换行用私有区哨兵字符承载，避免逐行 trim 吃掉行尾两空格。

**引擎**（`browser4-agent-tools/.../advanced/format/`）

| 文件 | 内容 |
|---|---|
| `FormatStepRunner.kt` | 宿主抽象：`acquireSnapshot` / `readOnSnapshot`（**保证不碰 tab**）/ `runOnTab` / `supports`；`FormatSnapshot` |
| `PageFormatPlan.kt` | `FormatStage`、`StepPolicy`、`FormatStep`+`StepKey`、`PageFormatPlan`、`FormatOptions`、`StepResult`、`AssemblyContext`、`PageScrapeRequest` |
| `FormatProvider.kt` | provider 接口 + 步骤构造器（`exportStep`/`readabilityStep`/`scrapeAllStep`）+ `FormatJson` 容错解析 |
| `FormatProviders.kt` | 本 build 已实现的 8 个格式注册表 |
| `providers/DocumentFormatProviders.kt` | `markdown`（readability→export 回退）、`html`、`rawHtml`、`readability` |
| `providers/ExtractionFormatProviders.kt` | `links`、`images`、`attributes`（按 selector 关联）、`deterministicJson`（X-SQL） |
| `PageFormatPlanBuilder.kt` | 纯函数计划编译：provider 解析 → 步骤合并去重 → **策略升级**（任一格式 REQUIRED 则共享步骤 REQUIRED）→ 阶段排序 |
| `PageFormatEngine.kt` | 执行：capture 一次 → Stage 2 快照读（全部先于 Stage 3 活体步骤）→ 逐格式装配 → `retainRequested` + `formatsDelivered` + 元数据 |

**Phase 0 模型的追加**：`PageFormat.sql` 选项 + `FormatIssueCode.DETERMINISTIC_JSON_REQUIRES_SQL`
（`deterministicJson` 在本项目里就是 X-SQL，只给 schema 无法机械映射 → 400 并指向 `json`，即 §12 R3 的落地）。

验证命令与结果：

```bash
./mvnw.cmd -o -pl ":browser4-skeleton,:browser4-agent-tools" \
  -D"test=FormatOptionTest,ScrapedDocumentTest,PageFormatContributorRegistryTest,HtmlToMarkdownTest,PageFormatPlanBuilderTest,PageFormatEngineTest" \
  -D"failIfNoTests=false" -D"jacoco.skip=true" test
# browser4-skeleton:    74 tests, 0 failures   (FormatOptionSchema 28 / HtmlToMarkdown 26 / ScrapedDocument 10 / 注册表 10)
# browser4-agent-tools: 26 tests, 0 failures   (PageFormatEngine 14 / PageFormatPlanBuilder 12)
# BUILD SUCCESS
```

不变量覆盖：

| 不变量 | 测试 |
|---|---|
| **I1** capture 恰好一次 | 8 格式请求下 `captures == 1`，且 `capture` 是首个调用 |
| **I2** Stage 2 不碰 tab | 所有读都绑定同一 snapshot；`liveCalls` 为空 |
| **I3** 活体失败不阻断 | 手工计划注入失败的 `tab.screenshot` → markdown 仍在，warning 指名 screenshot，且 `live:*` 是最后一次调用 |
| 策略 | REQUIRED 失败原样抛出（保留异常类型）；DEGRADABLE 失败转 warning 且其余格式完好 |
| 去重与升级 | markdown+html 共享一次 export；任一格式 REQUIRED 则共享步骤 REQUIRED |
| 降级可见 | 未实现格式 / 工具不支持 / X-SQL 报错各自产生不同文案；`formatsDelivered` 可区分"没请求"与"请求了没拿到" |
| 字段裁剪 | 未请求格式的字段在响应中不存在（`retainRequested`） |

### Phase 1c — REST/MCP 集成层（✅ A1 + A2 + A3 + A5 + A7 已交付并经真机验证；`strict`/`expires`/异步面/夹具页见"仍待交付"）

已核实的接入点（复用时不必再找）：

| 接入点 | 位置 |
|---|---|
| 挂载/注册执行器的现成范式 | `browser4-rest/.../rest/config/HTMLSnapshotToolMountConfiguration.kt`（`ToolMount` → `CustomToolRegistry`） |
| 快照族执行器 | `HTMLSnapshotToolExecutor.callFunctionOn(domain, functionName, args, receiver)`（`AbstractToolExecutor` 的 4 参抽象，receiver 传 `ManagedSession`） |
| 插件域查找 | `CustomToolRegistry.instance.get(domain)`（`markdown` / `image` / `media` / `seo`） |
| 会话解析 | `PulsarSessionManager.getOrRecoverSession(sessionId)` / `ManagedSession.withLock` |
| SPI 注册已通电 | `PluginManager` 已在启动时把 `PageFormatContributorMount` 的 contributor 注册进 `PageFormatContributorRegistry`；引擎已消费（见下面的 Phase 1d） |

**环境与机制已实测（2026-10-06，不必再推导）**

- **Java**：`JAVA_HOME` = GraalVM JDK 25.0.3；CLI 解析顺序是 `JAVA_HOME` → bundle 自带 JRE → 常见安装路径（`cli/browser4-cli/src/java.rs:107`），所以 PATH 上的 JDK 17 不影响后端启动。
- **运行时 bundle 版本必须与检出一致**，否则 dev 模式拒绝启动（本次是 bundle `4.14.0-rc.6` vs 检出 `4.14.0-rc.8`）。用 `$env:BROWSER4_CLI_FORCE_REBUILD_BUNDLE = "1"` 重建后：`Server ready in 9.7s`，真实浏览器成功加载 `https://example.com`（title `Example Domain`），MCP 链路端到端可用。**真机 e2e 因此是可行的，1c 必须走到这一步。**
- **`--expires` 就是 capture-once 的物理载体，且已确认**：`export` / `query` / `readability` / `scrape_all` 都接受 `-expires/--expires <dur>`，help 原文写着 "a positive value reads the stored snapshot while it is younger than the window, **without touching the tab**"。这正是 A1 需要的行为，不需要靠 KDoc 推断——`htmlsnapshot capture` 落盘后用带正数 `expires` 的读即可复用同一份快照。

#### A1 ✅ `SnapshotFormatStepRunner`

`browser4-rest/.../rest/api/service/scrape/SnapshotFormatStepRunner.kt`

把 `FormatStepRunner` 实现为对 `html_snapshot` 工具族的驱动，经一个 **窄缝** `FormatToolDispatcher`（`call(domain, method, args)` + `supports`）而不是直接抓 driver——这样 runner 能用录制式 fake 测，也就才有可能**断言它注入的参数**。

三处刻意设计：

1. **每个读都注入正数 `expires`，且是覆盖而不是默认。** 引擎在调用 `readOnSnapshot` 之前已经 capture 过一次；而快照族读方法的 `0s` 默认语义是"capture 活体页面"。若只是"提供默认值"，某个步骤自带 `expires: 0s` 就能悄悄让 8 个格式各重载一次页面。所以注入写在步骤自身参数**之后**，不可被覆盖。
2. **窗口在构造期校验，且下限是 1 秒而不是"正数"。** 这条是被自己的测试逼出来的：`Duration.ofMillis(500)` 既不是零也不是负数，但在快照族的时长文法里会渲染成 `"0s"` —— 恰好是"读活体页面"。于是"正数"这个下限不够，真正的下限是"不会塌成 0s"。`formatExpires` 里也再挡一次，避免这个危险值从别的路径产生。
3. **工具返回空即失败。** 返回 `""` 会被上游当成"这页没有链接"，并被记成一次成功的空格式 —— 静默失败。抛出去让引擎按步骤策略处理：尽力而为的转 warning，必需的抛原始异常。

**已知偏差（如实记录，未擅自改既有工具）**：`acquireSnapshot` **总是 capture**，`cacheState` 恒为 `"miss"`。引擎的 `expires` 语义是 Firecrawl 的 `maxAge`（"命中存储则不重抓"），但没有任何 `html_snapshot` 工具能**只读**地给出一个已存快照的身份——`capture` 总是序列化活体 tab，且它是唯一返回 store key / href / capture time 的方法。要兑现 `maxAge` 就得给既有工具加一条"只读元数据"路径，那会改动一个契约写得很细的既有工具，应当单独评审。**对正确性要紧的不变量——每个请求恰好一次 capture——两种做法都成立。**

#### A2 ✅ `PageScrapeService` + `PageScrapeToolExecutor`（domain `page`）+ mount

- `FormatStepRunnerFactory`（`fun interface`）——每次请求一个 runner。这是让 service 可测的缝：测试注入一个返回 fake runner 的工厂，于是参数处理与响应塑形不必有浏览器就能验。工厂而非单例是必须的：runner 绑定到请求寻址的会话，共享实例会让一个调用方的格式读到另一个调用方的页面。
- `PageScrapeService` —— 只负责请求级决策（解析 → 计划 → 执行 → 装配），capture-once / 阶段划分 / 逐格式降级仍归引擎，工具落在哪归工厂。`formats()` 产出能力清单，`available` 明确是**配置层面**的答案而非承诺（已注册但服务刚掉线的 contributor 仍会在调用时失败，那由每请求的 `warning` 报告；在这里也报就成了同一件事的两个真相源）。
- `PageScrapeToolExecutor` —— MCP 适配层。**返回 `Map` 而不是 `ScrapedDocument`**：`AbstractToolExecutor` 会把任何非 String/Number/Boolean/Map/Collection/Array 的结果包成 `{type, description}` 信封，直接返回文档对象会让调用方拿到 `"ScrapedDocument(...)"` 而不是载荷。
- `PageScrapeToolMountConfiguration` —— `ToolMount` → `CustomToolRegistry`，照 `HTMLSnapshotToolMountConfiguration` 的形状。`CustomToolTargets` 目前不是 bean（MCP controller 与内嵌 server 各自 new 一个），这里以同样的 `beanResolver` 构造第三个实例，保证格式步骤与客户端发起的工具调用解析出同一个 receiver。

**参数归一化**：`formats` 接受三种拼法——真正的列表、逗号分隔文本（`--formats markdown,links` 到达时就是一个字符串）、客户端 JSON 编码的数组。三种是同一个请求，所以在入口归一，而不是让调用方猜这个工具要哪一种。

**A4（`FRONTEND_TOOL_NAME_ALIASES`）经核实不需要。** 该别名表是给 `browser_*` 前端名映射到内建方法用的；自定义域的 MCP 名 `page_scrape` 由 `dispatchToCustomExecutor` 通过 `toMcpToolName(domain, specMethod)` 反查得到，无需注册。而且别名表的键集与 `McpToolNames.frontendAliases` 由 `McpToolAliasParityTest` 断言一致，凭空加一条反而会让两边都红。设计原文的 A4 据此作废。

#### A7 的实现方式改了：用 `ToolSpec.cliName`，但必须是**空格形式**

两个 spec 声明了 `cliName = "page scrape"` / `"page formats"`。按 `AGENTS.md` 的既有机制，CLI 从 `GET /mcp/tools/specs` 发现它们并渲染成一级命令，参数由 spec 的 `arguments` 定义——**不需要 `CommandDef`、不需要 `rewrite_prefixed_command()`、不需要 `preferred_spaced_command_form()`**。

**为什么不是单词 `scrape`**：CLI 的声明式命令解析（`main.rs:26076-26106`）要求 `global.args.len() >= 2` 且第二 token 不以 `-` 开头——它只探测**两 token** 的空格形式。所以 `cliName = "scrape"` 永远不会被匹配（`scrape --formats x` 在 flag 之前只有一个 token），而 `scrape formats` 会。单词命令属于 `commands.rs` 里静态注册的那一类（`goto`/`close`/`eval`），声明式机制做不到。两条路只能选一条：

- 现在这样：`page scrape` / `page formats`，零 CLI 代码改动，立即可用；
- 或者走 `commands.rs` 加 `CommandDef` + `MCPToolController` 别名 + e2e 场景（`test_e2e_command_coverage` 不变量要求每个 `Tested` 命令都有场景），才能得到设计稿 §3.3 写的裸 `scrape`。

选了前者并把后者记为后续项。`AGENTS.md` 的"新命令用空格形式"偏好与此一致。

#### 真机 e2e 已留证（2026-10-06，真实后端 + 真实 Chrome）

```
$ ./b4w.ps1 page formats
[{"id":"markdown","available":true,"source":"core"}, … ,{"id":"branding","available":false,
  "reason":"no plugin contributor installed","source":"plugin"}, …]

$ ./b4w.ps1 page scrape --formats "markdown,links,images"
{"url":"https://example.com/","markdown":"该域名仅用于文档示例…Learn more",
 "links":["https://iana.org/help/example-domains"],
 "metadata":{"url":"https://example.com/","captureId":"https://example.com/",
   "captureTime":"2026-10-06T02:29:20.094Z","formatsRequested":["markdown","links","images"],
   "formatsDelivered":["markdown","links","images"]}}

$ ./b4w.ps1 page scrape --formats '["markdown","links"]'      # JSON 数组形式同样可用
```

一次 capture 供三个格式共用（`captureTime` 只有一个，`links` 与 `markdown` 描述同一页面状态）。

**真机才暴露的两个坑，都已落地为回归测试：**

1. **`ToolSpecValidator` 把"没有 `defaultValue`"当作必需参数**，与类型里的 `?` 无关（`ToolSpecValidator.kt:67`）。所以 `url: String?` 这种可选参数在真机上被要求提供，第一次带 `formats` 的调用直接 400。修正：所有可选参数按既有约定写 `defaultValue = "null"`。`PageScrapeToolExecutorTest.scrapeTakesNoRequiredArguments` 锁住这条。
2. **PowerShell 里未加引号的 `--formats markdown,links` 会变成三个 argv**，CLI 只收到第一个，其余成为被丢弃的位置参数——**看起来像"静默丢格式并报成功"**。加引号即可；这不是 CLI 缺陷，但值得写进文档，因为它长得和真 bug 一模一样。已在此处记录。

**`images` 空而有记录不是矛盾**：example.com 没有 `<img>`，所以 `images` 是空列表，被共享 mapper 的 NON_EMPTY 语义从线上省略；但 `formatsDelivered` 仍列出它。这正是想要的区分——"请求了、产出为空" ≠ "没请求"，而后者才需要 `warning`。

#### A5 ✅ `PageScrapeController`（`api/scrape` + `api/scrape/formats`）

薄壳：请求体 → 调用 `PageScrapeService` → `{success, data}` 信封。放在这里的任何决策都会被决定两次，所以这里不做决策。

- `POST /api/scrape` — 校验（`FormatOptionSchema.requireValid()`）在调用 service **之前**，所以拼错格式不需要付一次页面加载。
- `GET /api/scrape/formats` — 能力发现，与 MCP 的 `page.formats` 同一份数据。
- `IllegalArgumentException` → 400 的 `@ExceptionHandler`，照 `ScrapeController` 的既有形状。
- 异步面（`/submit` + `/{id}/status|result|stream`）**未实现**：本 build 能交付的每个格式都在一次同步 capture 内答完，异步面是为媒体下载与 LLM 调用准备的（Phase 3）。现在加就是一个没有调用方的未测信封。

**真机验证（同一后端，紧接上面的 e2e）**：

```
GET  /api/scrape/formats  → 200  {"success":true,"data":[ … 22 条 … ]}
POST /api/scrape          → 200  {"success":true,"data":{"url":"https://example.com/",
                                "markdown":"…","links":["https://iana.org/help/example-domains"],
                                "metadata":{…, "formatsRequested":["markdown","links"],
                                            "formatsDelivered":["markdown","links"]}}}
POST 未知格式 "markdwon"   → 400  {"success":false,"error":"Bad Request",
                                "message":"Unknown format 'markdwon'. Known formats: markdown, …"}
```

**真机才暴露的坑（第三个）**：`PageScrapeRequestBody` 一开始没写 `@param:JsonProperty`，结果请求体**没有绑定**——服务收到的是全默认值对象，于是返回了一个指向 `sessionId` 的 400，而真正的问题是 DTO 绑定。三条探针（未知格式 / 只给 sessionId / 不给 sessionId）当时返回**完全一样**的错误，这个自相矛盾本身就是线索。补上注解后三条全部正确。

**如实记录一处未查清的地方**：我无法完整复原当时的中间状态。按 `needsSnapshot = steps.any { FROM_SNAPSHOT }`，若 `formats` 真的是空的就不该发生 capture，也就不该出现那个 `sessionId` 报错——两者对不上。事实是：加注解前三条探针稳定返回同一个错，加注解后三条全部正确。机制上我只确认到"DTO 未绑定"，没有把中间过程编圆。本模块每个请求 DTO 都带这组注解（`rest/mcp/controller/dto/McpDtos.kt`），跟约定走即可。

#### 仍待交付

- `strict` 三态契约（§6.2 的 503/502/504）。因此 `page.scrape` 与 `POST /api/scrape` **刻意不接受** `strict` 参数——接受了却只降级就是撒谎。
- `expires` / `maxAge`：同上，见 A1 的已知偏差。
- 裸 `scrape` 命令（见上，需要 `commands.rs` + 别名 + e2e 场景）。
- **`sessionId` 目前是必需的**（`requiresReceiver = true`）。设计稿 §3.2 写的是"不需要 sessionId"，但 §12 的开放问题 **Q1**（无 url 且无会话时自动开临时会话，还是要求先 `open`）至今未拍板，所以在拍板前要求会话是唯一诚实的选择。CLI 会自动注入 sessionId，所以 `page scrape` 用户体验上无感；REST 调用方需要显式给。
- 异步面（`/api/scrape/submit` + `/{id}/status|result|stream`），理由见 A5。
- **§11 的夹具页**仍未建（`browser4-tests/pulsar-tests-common/src/main/resources/static/b4/formats-fixture.html`）。本次 e2e 用的是 `https://example.com`，它够证明链路，但不含表格、重复卡片、无 alt 图片、`data-*` 属性——渲染质量仍需夹具页覆盖。



### Phase 1d — SPI 接线与 contributor 消费（✅ 已实现，从 Phase 6 提前）

Phase 0 交付了 SPI 的形状、Phase 1b 交付了引擎，但两者之间**没有连线**：`PageFormatContributorRegistry` 在整个仓库只被它自己和测试引用，引擎只查 `FormatProviders`。也就是说插件注册了 contributor 也不会被调用 —— §7.1 的扩展点当时只是接口。本阶段补上这条链路。

**引擎侧**（`PageFormatEngine.runContributors`）

- 在所有核心 provider 装配**之后**执行，contributor 拿到的 `FormatContext` 就是核心格式用过的那次 capture —— 包括它们产出的 `rawHtml` / `html` / `markdown`，所以 `branding` 读到的是 `markdown` 读过的那个页面，不会自己再抓一次。
- 六种"decline"路径各自产生一条指名格式的 warning，都不让请求失败、也不静默消失：未注册、`isAvailable()==false`（带上 `unavailableReason()`）、`requires` 有缺失项、`contribute` 返回 null、`contribute` 抛异常、`outputField` 不是可写字段。
- 新增 `PageFormats.contributedFields()`（由 `CONTRIBUTED` 推导，而非第二份清单）与 `ScrapedDocument.withContributedField(field, value)`。contributor 只能写这几个字段：核心字段归它自己的 provider，写了就是静默覆盖。

**接线侧**（`PluginManager.wirePageFormatContributorMount`）

- 照 `wirePageSummaryAlgorithmMount` 同形，**每个 contributor 单独 try/catch**：`register()` 对非法/保留 id 是抛异常拒绝的，按 mount 捕获会让一个坏 contributor 连累同 mount 的兄弟。`PluginManagerTest` 用两个 contributor（一个占 `markdown`，一个占 `menu`）锁住了这条。

**计划构建器的边界调整**

`PageFormatPlanBuilder` 不再为 contributed id 产生 warning。**这是刻意的**：某个 contributed 格式此刻能不能交付，取决于启动时装了哪些插件、插件的服务配没配 —— 那是运行期事实，计划期无从得知（查注册表会让计划不再纯粹，`planningIsPure` 也会失效）。静态不可交付（`screenshot` 未实现、`query` 已废弃）仍由计划期决定，运行期事实由引擎决定。两条文案都留在 `PageFormatPlanBuilder` 里（`unavailableWarning` / `missingContributorWarning`），避免同一件事的措辞散在两处。

**顺带修掉的既有缺陷：`deliveredFormats` 会误报**

新测试撞出来的：`deliveredFormats()` 原本遍历 `PageFormats.ALL` 按字段反推，而字段名并不能唯一确定格式 —— `json` 与 `deterministicJson` 共用 `json` 字段、`video` 还填 `videos`、已废弃的 `query` 与 `highlights` 共用字段。于是请求 `deterministicJson` 会得到 `formatsDelivered = ["json", "deterministicJson"]`，请求 `highlights` 会额外带上 `query`。

修法两步，签名改为 `deliveredFormats(requested)` / `withDeliveredFormats(requested)`：

1. 只检查请求过的 id（`json` 与 `deterministicJson` 互斥，所以不可能同时被请求）；
2. 已废弃 id 永不算交付 —— 它只是接受解析，任何 provider 都不产出它。

REST/MCP 层马上要把 `formatsDelivered` 交给调用方，这个误报必须在接线之前清掉，否则契约从第一天就是错的。

**验证**

```bash
./mvnw.cmd -o -pl ":browser4-skeleton,:browser4-agent-tools" \
  -D"test=FormatOptionTest,ScrapedDocumentTest,PageFormatContributorRegistryTest,HtmlToMarkdownTest,PageFormatPlanBuilderTest,PageFormatEngineTest" \
  -D"failIfNoTests=false" -D"jacoco.skip=true" test
# browser4-skeleton:    79 tests, 0 failures  (FormatOptionSchema 29 / HtmlToMarkdown 26 / ScrapedDocument 14 / 注册表 10)
# browser4-agent-tools: 38 tests, 0 failures  (PageFormatEngine 25 / PageFormatPlanBuilder 13)

./mvnw.cmd -o -pl :browser4-boot -D"test=PluginManagerTest" -D"failIfNoTests=false" -D"jacoco.skip=true" test
# browser4-boot:        12 tests, 0 failures  (+2：contributor mount 接线 / 坏 contributor 不连累兄弟)
```

新增的 13 个引擎用例覆盖：写入字段并计入 `formatsDelivered`、contributor 与核心格式共享同一次 capture（`captures == 1`）、拿到格式自身的请求选项、未注册 / 自报不可用（带原因）/ 缺输入 / 缺 live tab / 返回 null / 抛异常 / 写错字段各自的文案、`markdown` 这类核心 id 不可被认领。

**本阶段暴露的两个设计缺口（需要拍板，未擅自改 SPI）**

1. **`FormatInput.LIVE_TAB` 对 contributor 永远不满足。** `FormatContext` 只携带值，不带 driver，所以 contributor 拿不到 tab 控制权。这直接使 §7.2 里那个 `BrandingFormatContributor` 示例（`requires = setOf(RAW_HTML, LIVE_TAB)`）恒不可用。当前实现把它明确报成 `needs a live tab (contributors are handed values, not a driver)`，而不是假装满足。要修就得改 SPI 形状（给 `FormatContext` 一个受控的 tab 调用能力），那是对已发布契约的破坏性变更，应当先定。
2. **全新的第三方格式 id 没有落脚字段。** `ScrapedDocument` 的字段集是封闭的，`contributedFields()` 只有 branding/product/menu/highlights。一个 id 为 `myFormat` 的 contributor 即使注册成功，也没有字段可写（引擎会报 `writes 'x', which is not a contributor field`）。`outputField` 因此实际只能在这四个之间重定向。要支持真正的第三方 id，需要给 `ScrapedDocument` 加一个 `@JsonAnyGetter` 的泛化容器 —— 会改动 Phase 0 的线上形状，同样应当先定。

### Phase 2 — 活体产物（≈1 天）
- `screenshot`（沿用 `tab.screenshot` 的全部参数与 `/STACK` 相关处理）、`pdf`
- 二进制产物的落盘命名与 `--out` 语义
- 测试：夹具页 + 真实浏览器 e2e（`requires_browser4: true`）

### Phase 3 — 服务与 AI 格式（≈1.5 天）
- `audio` / `video`（`media.*`）、`summary(LLM)` / `question`（`agent_*`）
- 异步面：超过阈值自动转 `submit` + `/{id}/status|result|stream`
- 测试：缺服务/缺 LLM key 时的降级契约（D5 三态）

### Phase 4 — `changeTracking`（≈1.5 天）
- 快照保留与清理策略、`changeStatus` 状态机、`diff.text` / `diff.json`
- 对齐 Firecrawl 四态：`new` / `same` / `changed` / `removed`
- 测试：同一页三次抓取的状态迁移；移除页的 `removed`

### Phase 5 — CLI 与文档（≈1 天）
- `cli/browser4-cli/src/commands.rs` 增 `scrape` 命令（`CommandDef.name` = kebab `scrape`），并在 `main.rs` 的 `rewrite_prefixed_command()` 注册前缀 `scrape` + `known_subs` 白名单（`formats`），让 `scrape formats` 正确改写为内部 kebab 名（见 `AGENTS.md` §"CLI command naming" 的 4 步）
- `preferred_spaced_command_form()` / `preferred_prefixed_group_form()` 注册 `scrape formats`
- `help.rs`、`tips.rs`、`skills/browser4-cli/SKILL.md`、`references/`新增 `scrape-formats.md`
- 按 `AGENTS.md` 文档更新规则同步 `README.md`、`README.zh.md`、`cli/browser4-cli/README.md`
- e2e：`cli/browser4-cli/tests/e2e/scenarios/` 增 `scrape_formats` 场景

### Phase 6 — SPI 落地与首批插件（独立排期）
- `browser4-branding` 插件（对应 Firecrawl `branding`）
- `browser4-product` / `browser4-menu`（可选；也可先用 `extract --schema` 方案替代）

**总量估算**：Phase 0 + Phase 1a/1b/1d 已交付；Phase 1c ≈ 1 天、Phase 2-5 ≈ 6 人日（不含插件实现）。Phase 1d 提前消化了原计划里 Phase 6 的 `PluginManager` 接线，并把 Phase 1c 的真正起点从"引擎能否被扩展"推进到"接入层"。Phase 6 现在只剩插件实现本身（以及两个待拍板的 SPI 形状决策）。

---

## 11. 测试计划

| 层 | 内容 |
|---|---|
| 单元 | 组合规则矩阵（每条规则一个反例）；别名归一；`page_formats` 的能力聚合；`retainRequested` 裁剪；contributor 注册（id 非法/重复/并发） |
| 引擎 | **I1**：一次 `page_scrape` 只触发一次 `html_snapshot.capture`（用 mock executor 计数）；**I2**：Stage 2 步骤无 `LIVE_TAB` 输入；**I3**：截图失败时 markdown/links 仍返回且带 warning；**I6**：非法组合在编译期抛错 |
| REST | MockMvc：`/api/scrape` 200/400/503；`/api/scrape/formats` 形状；异步面 `submit → status → result` |
| MCP | `page_scrape` 参数归一化（`schemas` 别名、snake_case）、缺 sessionId 的行为、错误码透传 |
| 夹具 | 新增 `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/formats-fixture.html`：文章区 + 导航噪声 + 3 个重复卡片 + 表格 + 图片（含无 alt）+ `data-*` 属性 + 外链/内链 + 分页链接 |
| e2e | `cargo test --test e2e -- --scenario=test_e2e_scrape_formats*`（`requires_browser4: true`）；真实浏览器验证 screenshot 落盘非空、markdown 长度 > 阈值、links 含预期 URL |
| 对拍（非门禁） | 同一夹具页分别跑 Firecrawl 与 Browser4，人工比对 markdown 体积、links 集合、images 集合，作为"语义漂移"预警，不设阈值断言 |

---

## 12. 风险与开放问题

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **Markdown 渲染差异**：Firecrawl 用自有转化服务，Browser4 用 Jsoup 实现，表格/代码块/嵌套列表输出必然不同 | 迁移用户对 markdown 文本 diff | 明确"不保证字节级一致"；夹具页对拍观察；提供 `html`/`rawHtml` 作为无损退路 |
| R2 | **readability 命中率**：`onlyMainContent` 在列表页/文档站可能抽错 | 默认路径质量 | 暴露 `readability.confidence`；低置信度时在 `warning` 提示并附 `html` |
| R3 | **`json` 的 schema 强校验**：Firecrawl 走 OpenAI strict schema（`.` 必填、`additionalProperties:false` 等），Browser4 的 X-SQL 路线不天然支持任意 JSON Schema | 复杂 schema 无法确定性满足 | 双模式：`json` 默认 LLM（`agent_extract`，宽松校验），`deterministicJson` 走 X-SQL（要求 schema 可机械映射，映射不了则 400 并提示改用 `json`） |
| R4 | **媒体格式耗时**：视频下载可能数分钟 | 同步请求体验 | 超过阈值自动转异步 + `/{id}/stream`；`timeout` 到期返回 `TIMEOUT` 且已完成的本地产物仍写入 `warning`/目录 |
| R5 | **capture-once 与重试的交互**：Stage 3 失败重试不得让 Stage 2 重抓 | 幂等性（`AGENTS.md` 明确要求） | 计划按 stage 划分重试边界；S 只读步骤天然幂等；`AgentToolManager` 的既有重试不跨越 Stage 1 |
| R6 | **`expires` 语义与 Firecrawl `maxAge=0` 的差异**：Browser4 `0s` 表示"总是活体"，Firecrawl `maxAge=0` 表"跳过缓存" | 行为看起来类似但触发条件不同 | 文档明确；请求级 `expires` 显式透传 |
| R7 | **格式命名空间冲突**：插件贡献的 id 与未来核心格式撞名 | 注册被跳过、行为静默变化 | 注册冲突 warn + `page_formats` 标出来源（core/plugin）；核心格式名保留清单写入 `FormatOption.kt` |
| R8 | **能力发现滞后**：`page_formats` 报告可用、实际调用失败（服务刚掉线） | 调用方误判 | `page_formats` 标 `available` 为"配置层面可用"，运行时失败仍走 §6.2 契约 |

**开放问题（需评审拍板）**

1. **Q1**：`page_scrape` 无 url 且无会话时——是自动开临时会话，还是要求调用方先 `open`？（倾向：自动用共享 scrape 会话，与 `readability <url>` 一致）
2. **Q2**：`ScrapedDocument` 是否要保留 Firecrawl 的 `extract` 字段名（v1 遗留）？倾向不保留。
3. **Q3**：二进制产物是否需要 `/api/scrape/{id}/media/{name}` 的下载端点，让远程调用方不必共享文件系统？倾向 Phase 3 再加。
4. **Q4**：`changeTracking` 的存储保留策略是否复用 `webdb` 的清理周期，还是独立 TTL？（影响 R7/D7）

---

## 13. 验收清单（DoD，对齐 `AGENTS.md`）

- [ ] 构建与相关测试通过（`mvn -DskipTests` + 最小相关测试域）
- [ ] 新增/变更逻辑有测试：主路径 + 边界（未知格式、不可用格式、非法组合、截图引擎不支持）
- [ ] 无新增高噪声日志/警告；降级路径用 `warning` 字段而非日志刷屏
- [ ] **I1/I2/I3 有不变量测试**（capture 恰好一次、只读、失败不阻断）
- [ ] 夹具页 + 真实浏览器 e2e 覆盖截图/导出（`requires_browser4: true`）
- [ ] 无新增直接 CDP 方法；若新增，四条评审门逐条留证据
- [ ] 文档同步：`SKILL.md` / `references/scrape-formats.md` / `help.rs` / `tips.rs` / `README.md` / `README.zh.md` / `cli/browser4-cli/README.md`
- [ ] 无密钥/私有端点入库；`page_formats` 不泄露服务地址
- [ ] 无版本号随意变更（走父 BOM）
- [ ] 性能影响评估：多格式请求的额外开销（除外部服务外应为零抓取、零额外 CDP 往返）
