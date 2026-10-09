# Firecrawl v2 ↔ Browser4 网页输出格式对照表

| 项 | 值 |
|---|---|
| Firecrawl 源码 | `D:\codebase\crawler-scraper\firecrawl` @ `ce8ed1233` (2026-10-05) |
| Browser4 源码 | 本仓库 @ `d86b69fc8b` (2026-10-06)，版本 `4.14.0-rc.8` |
| 关联文档 | [Firecrawl 兼容 formats 层设计草案](firecrawl-compatible-formats-design.md) |
| 结论用途 | 判断 Browser4 在"网页输出格式"这一维度上对 Firecrawl 的覆盖度、缺口与可复用点 |

---

## 0. 一句话定性

**Firecrawl** = *一个端点 + 一个 `formats[]` 参数 → 一个字段化 JSON 文档*：`POST /v2/scrape` 一次返回 `data.markdown` / `data.json` / `data.screenshot`…，且**未请求的字段会被服务端删除**。

**Browser4** = *一个格式 = 一个命令/工具 → 一个独立产物*：`.md` 文件、YAML、JSON、CSV、PNG、本地媒体文件；组合靠 `batch` 或多条命令。

因此对照不是字段 1:1 映射，而是**能力映射**。适配度记号：✅ 等价 / ✅➕ 等价且更强 / ⚠️ 部分或同名不同义 / ➖ 缺失。

---

## 1. Firecrawl v2 的 20 种输出格式（权威来源）

定义在 `apps/api/src/controllers/v2/types.ts:758-815`（`scrapeOptionFields.formats` 的 Zod union），响应字段落在 `Document`（`types.ts:1439-1506`）。默认 `[{type:"markdown"}]`。

| 分类 | 格式 |
|---|---|
| 正文/标记 | `markdown`(默认)、`html`、`rawHtml`、`rawBase64` |
| 列表 | `links`、`images` |
| 视觉 | `screenshot`、`branding` |
| AI/LLM | `json`、`deterministicJson`、`summary`、`question`、`highlights`、`query`(废弃) |
| 规则提取 | `attributes` |
| 业务结构 | `product`、`menu` |
| 媒体 | `audio`、`video` |
| 变更追踪 | `changeTracking` |

组合约束（会被 400 拒绝）：`screenshot` ≤ 1；`changeTracking` 必须同时请求 `markdown`；`json` ⊥ `deterministicJson`；`rawBase64` 必须独占。

---

## 2. 主对照表

### 2.1 正文 / 标记类

| Firecrawl v2 | Firecrawl 响应字段 | Browser4 对应 | Browser4 输出形态 | 适配度 |
|---|---|---|---|---|
| `markdown` | `data.markdown` | `plugin markdown convert`<br>`plugin markdown read --url <url>` | `.md` 文件（YAML front matter + 标题层级/表格/列表/代码块/引用）；`read` 返回 markdown + title/byline/outline + `source(llms\|llms-index\|llms-full\|negotiation\|path-markdown\|llms-link\|text\|extractor)` | ✅➕ |
| `html` | `data.html` | `htmlsnapshot export --clean`<br>`htmlsnapshot get html <selector>` | 全页 / 单元素 HTML 字符串 | ✅ |
| `rawHtml` | `data.rawHtml` | `htmlsnapshot export`（默认未清洗）<br>`eval 'document.documentElement.outerHTML'` | 原始 HTML | ✅ |
| `rawBase64` | `data.rawBase64` | ➖ 无内置（`eval` + `btoa` 可自造） | — | ➖ |

### 2.2 链接 / 图片

| Firecrawl v2 | Firecrawl 响应字段 | Browser4 对应 | Browser4 输出形态 | 适配度 |
|---|---|---|---|---|
| `links` | `data.links: string[]` | `plugin markdown discoverLinks`<br>`htmlsnapshot capture` 的 link groups<br>`htmlsnapshot query`（X-SQL `dom` 函数族） | `[{href, text, resolvedUrl, isInternal}]` | ✅➕（带文本/内外部标记，可 SQL 过滤） |
| `images` | `data.images: string[]` | `plugin image detectImages`<br>`htmlsnapshot query` + `dom_attr(dom,'src')` | 图片 URL + 尺寸/alt 等元数据 | ✅➕ |

### 2.3 视觉类

| Firecrawl v2 | Firecrawl 响应字段 | Browser4 对应 | Browser4 输出形态 | 适配度 |
|---|---|---|---|---|
| `screenshot`<br>选项 `fullPage/quality/viewport` | `data.screenshot`（云托管 URL：`https://service.firecrawl.dev/storage/v1/object/public/media/screenshot-*`） | `screenshot [ref] -o a.png\|a.jpg --full-page --viewport N`<br>`GET /api/pages/{sid}/{guid}/screenshot.png`<br>`tab.screenshot(rect)` 区域截图 | **本地 PNG/JPEG**（扩展名决定格式）；支持元素、区域矩形、视口索引、全页 | ✅➕（可 jpeg；自托管无需外部引擎） |
| `branding` | `data.branding`（logo/配色/字体档案） | ➖ 无（`plugin seo extractMeta` 只到 OG/Twitter/favicon） | — | ➖ |

> Firecrawl 自托管注意：`playwright` 引擎 `screenshot:false`（`scraper/scrapeURL/engines/index.ts:405-409`），只有 `fire-engine;chrome-cdp` / `index` 等引擎支持截图。

### 2.4 AI / 结构化提取

| Firecrawl v2 | Firecrawl 响应字段 | Browser4 对应 | Browser4 输出形态 | 适配度 |
|---|---|---|---|---|
| `json`<br>`schema/prompt/checkPromptInjection` | `data.json` | `extract "<instruction>" --schema @file.json`（MCP `agent_extract`） | JSON（`--stdout` 直出 / `--filename` 落盘） | ✅ |
| `deterministicJson` | `data.json`（可复用 JS 提取器，有缓存） | **`htmlsnapshot query --sql @q.sql`**（X-SQL：确定性、可复用、join/filter/sort/聚合）<br>CLI `--format json\|csv\|table`、`--result-only` | JSON / CSV / 表格 | ✅➕（SQL 引擎 vs 缓存 JS 脚本） |
| `summary` | `data.summary`（LLM 文本） | `htmlsnapshot summary [--algorithm id]` → **WPSI（确定性、非 LLM）**<br>`summarize "<instruction>"` → LLM | YAML：page type / landmarks / 打分关键节点+选择器提示 / 重复列表 / link groups / 表格 / 统计 | ⚠️ **同名不同义** |
| `attributes`<br>`selectors[{selector,attribute}]` | `data.attributes[]` | `htmlsnapshot get all attr <selector> <attrName> --absolute`<br>X-SQL `dom_attr` / `dom_attrs` | 值数组 / JSON 行集 | ✅➕ |
| `question` | `data.answer` | `extract` / `summarize` 指令式提问；`agent run` | LLM 文本 | ⚠️ 无独立格式 |
| `highlights` | `data.highlights`（原文直引） | ➖ 无语义高亮；`htmlsnapshot grep` 是**正则**（`--selector`、`-A/-B/-C`、`-i/-v/-F/-w`） | 匹配行 | ⚠️ 弱对应 |
| `query`（已废弃） | `data.answer`（freeform）/ `data.highlights`（directQuote） | 同上 | — | — |

### 2.5 业务结构 / 媒体

| Firecrawl v2 | Firecrawl 响应字段 | Browser4 对应 | Browser4 输出形态 | 适配度 |
|---|---|---|---|---|
| `product` | `data.product`（title/brand/variants[price/sale/availability/images]） | ➖ 无内置抽取服务<br>→ X-SQL 或 `extract --schema` 自建（见 `docs/multi-product-extraction.md`） | — | ➖（可用数行 X-SQL 补） |
| `menu` | `data.menu`（merchant/sections[items[price/dietary/calories]]） | ➖ 无（Firecrawl 侧亦有 `menuBeta` 团队门控） | — | ➖ |
| `audio` | `data.audio`（**云托管 URL**） | `plugin media extractAudio`（FFmpeg 抽音轨）<br>`plugin media download` / `process` / `trim` / `compress` / `getInfo` | **本地音频文件** + 元数据 | ✅（形态不同：文件 vs URL） |
| `video` | `data.video`(URL) + `data.videos: VideoItem[]` | `plugin media detectVideos` / `download` / `getInfo` | 本地视频文件 + 元数据（时长/分辨率） | ✅（形态不同） |

### 2.6 变更追踪

| Firecrawl v2 | Firecrawl 响应字段 | Browser4 对应 | 适配度 |
|---|---|---|---|
| `changeTracking`<br>`prompt/schema/modes[git-diff\|json]/tag` | `data.changeTracking`：`previousScrapeAt` + `changeStatus: new\|same\|changed\|removed` + `visibility` + `diff.text` + `diff.json`（chunks/changes 行级） | `snapshot --auto-diff`（AX 树 `+`/`-`/`~` 差异）<br>`diff snapshot a b`（两个已存快照的 unified diff）<br>`htmlsnapshot --expires 1d` 读**旧快照版本**<br>`webdb` 页面版本存储 | ⚠️ **部分**：有 diff 与版本，无 4 态状态机、无 git-diff JSON、无"上次抓取时间"自动留存对比 |

### 2.7 通用字段

| Firecrawl | Browser4 对应 | 说明 |
|---|---|---|
| `metadata`（**永远返回**）：title/description/language/og*/dc*/favicon/modifiedTime/publishedTime/statusCode/numPages/proxyUsed/cacheState/cachedAt/scrapeId | `plugin seo extractMeta`（url/title/description/canonical/og/twitter/headings{h1..h4}/images/imagesWithoutAlt/wordCount/jsonLd）<br>`page-info`<br>`htmlsnapshot capture` 元数据（url/title/size/时间戳/交互元素/link groups） | ⚠️ Browser4 元数据分散在多个命令；`seo.extractMeta` 覆盖面最接近（还多 JSON-LD 与 SEO 审计 `checkIssues`） |
| `onlyMainContent: true`（默认，剔除导航/页脚） | `htmlsnapshot readability`（Readability 风格：title/byline/siteName/excerpt/cleaned HTML/plain text） | ✅ Browser4 是**显式命令**而非默认行为 |
| `warning`（格式降级/服务未配置/非商品页等） | 无统一字段（各命令用显式错误或 ⚠ 文本） | ⚠️ |

---

## 3. Browser4 独有（Firecrawl 完全没有）

| Browser4 | 输出 | 为什么 Firecrawl 没有 |
|---|---|---|
| `snapshot` / `tab.ariaSnapshot()` | **ARIA/AX 树 YAML**（Playwright 风格：`- role "name" [attr=v]`、`[ref=eN]`、`[cursor=pointer]`、`/url:`、`--boxes`） | Firecrawl 抓内容，不产出可交互引用 |
| `snapshot grep` / `diff snapshot` | 活体 AX 树正则搜索 / 两个已存快照的 unified diff | — |
| `htmlsnapshot inspect` | 重复模式的 **CSS 选择器建议 + 覆盖率** | 无 |
| `htmlsnapshot summary`（WPSI） | 确定性结构索引 YAML（无 LLM 成本） | Firecrawl `summary` 走 LLM |
| `htmlsnapshot query`（X-SQL） | SQL 式抽取 + `json/csv/table` 渲染 + `--result-only` | Firecrawl 只有 json/deterministicJson |
| `eval` / `eval --ref` | 任意 JS 值（JSON 序列化） | 仅 `actions.executeJavascript` |
| `pdf` / `plugin pptx generate` | PDF / PPTX 文件 | 只有 pdf **action**，无 pptx |
| `network har stop` | `.har`（DevTools 可导入） | 无 |
| `profiler stop` | `.cpuprofile`（speedscope 可看） | 无 |
| `vitals` | LCP/CLS/INP/FCP/TTFB JSON | 无 |
| `console` / `errors` | 控制台日志（`--min-level`） | 无 |
| `webdb export` / `webdb normalize` | 页面落盘目录 / 规范化 URL 键 | 无 |
| `crawl` / `swarm` | 批量结果集 → `--sql --format csv -o x.csv` | Firecrawl crawl 返回 `Document[]` |
| `captcha detect/solve` | 验证码 token | 无 |
| `plugin markdown read` 的 llms.txt 链路 | `llms` / `llms-index` / `llms-full` / `negotiation` / `path-markdown` 多来源 markdown | 无 llms.txt 协议感知 |

---

## 4. 关键语义差异（集成时最容易踩）

| 维度 | Firecrawl v2 | Browser4 |
|---|---|---|
| **调用模型** | 1 次请求出多格式字段（`formats:[...]` 数组） | 1 次调用 1 个产物；组合用 `batch` 或多命令 |
| **字段裁剪** | 未请求的字段被服务端**删除**（`transformers/index.ts:342-436` `coerceFieldsToFormats`） | 不存在——没调用就没产物 |
| **组合约束** | `screenshot`≤1 / `changeTracking` 需 `markdown` / `json`⊥`deterministicJson` / `rawBase64` 独占 | **无**（各命令独立；X-SQL 一次取多字段，天然对齐） |
| **多次调用对齐** | 一次响应内天然对齐 | ⚠️ 多次 `get all` 产生**未对齐数组**（长度/顺序不同）——需用 `DOM_LOAD_AND_SELECT` 或 X-SQL 一次取 |
| **媒体交付** | 云托管 URL | 本地文件（`--filename`，`.jpg` 即 JPEG） |
| **正文默认** | `onlyMainContent: true` | 默认**全 DOM**；正文需 `readability` 或自行定位 |
| **AI 依赖** | json/summary/question/branding/product/menu 靠云端服务或 LLM，按格式加 credits | 只有 `extract`/`summarize`/`agent run` 需 LLM key；其余本地/确定性 |
| **计费** | 按格式加 credits（json +4、question +4、highlights +4、audio/video +4、deterministicJson 3/10、`checkPromptInjection` 命中再 +4；见 `lib/scrape-billing.ts:160-249`） | 无按格式计费 |
| **格式可用性** | 部分格式有门控：`menu` 需 `menuBeta`、`branding` 的 `fast` 仅内部团队、`product`/`audio`/`video` 需外部服务 URL，缺失时**不报错**只写 `warning` | 命令缺失即不可用（插件未装） |

---

## 5. 同一任务两种写法

### 5.1 取文章 markdown + 链接 + 截图

```bash
# Firecrawl v2
POST /v2/scrape {
  "url": "https://example.com/post",
  "formats": ["markdown", "links", "screenshot"]
}
# → data.markdown / data.links[] / data.screenshot(URL)
```

```bash
# Browser4
browser4-cli goto https://example.com/post
browser4-cli plugin markdown read --url https://example.com/post --outline  # 或 plugin markdown convert → .md
browser4-cli plugin markdown discoverLinks                                  # 或 htmlsnapshot query 取 <a>
browser4-cli screenshot -o post.png --full-page
```

### 5.2 结构化抽 listing 页 10 个商品的 title+price+url

```bash
# Firecrawl：formats:[{type:"json", schema:{...}, prompt:"..."}]  ← LLM，+4 credits
# Browser4：确定性、零 LLM、零 API 成本
browser4-cli htmlsnapshot query --sql "
  SELECT dom_first_text(dom,'h2')    AS title,
         dom_first_text(dom,'.price') AS price,
         dom_base_uri(dom)            AS url
  FROM DOM_LOAD_AND_SELECT('.item', 10)" --format csv -o items.csv
```

---

## 6. 覆盖度记分卡

| 分组 | Firecrawl 格式 | Browser4 状态 |
|---|---|---|
| 正文/标记 | markdown, html, rawHtml, rawBase64 | 3/4（缺 `rawBase64`，且无独占语义需求） |
| 列表 | links, images | **2/2，且更强** |
| 视觉 | screenshot, branding | 1/2（缺 `branding`；截图能力更强） |
| AI/结构 | json, deterministicJson, summary, question, highlights, query, attributes | 6/7 有对应（`highlights` 弱；`summary` 语义不同；`attributes` 更强） |
| 业务结构 | product, menu | 0/2（可用 X-SQL / `extract --schema` 自建） |
| 媒体 | audio, video | 2/2（形态：本地文件 vs 托管 URL） |
| 变更追踪 | changeTracking | 部分（AX diff + 快照版本，无 4 态状态机） |
| 通用 | metadata, onlyMainContent, warning | metadata ✅（`plugin seo extractMeta`）、正文提取 ✅（`readability`）、统一 warning ➖ |

**净结论**：12 类能力等价或更强，2 类缺失（`branding`、`product`/`menu`），1 类部分（`changeTracking`）。

---

## 附：源码索引

### Firecrawl
| 内容 | 位置 |
|---|---|
| v2 formats Schema | `apps/api/src/controllers/v2/types.ts:758-815` |
| 参数化格式选项 | `same:357-495` |
| `Document` 响应结构 | `same:1439-1549` |
| v1 字符串枚举 + 别名 | `apps/api/src/controllers/v1/types.ts:442-474`；别名归一 `apps/api/src/lib/key-restriction.ts:236-244` |
| 字段裁剪 | `apps/api/src/scraper/scrapeURL/transformers/index.ts:342-436` |
| 计费 | `apps/api/src/lib/scrape-billing.ts:160-249` |
| search 格式子集 | `apps/api/src/controllers/v2/types.ts:2577-2592` |
| parse 格式（输入文件类型） | `apps/api/src/lib/parse-formats.ts` |

### Browser4
| 内容 | 位置 |
|---|---|
| htmlsnapshot 工具规格 | `browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:114-360` |
| tab 域工具规格（screenshot/ariaSnapshot/eval） | `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:205-700` |
| CLI 命令定义 | `cli/browser4-cli/src/commands.rs`（`htmlsnapshot*` 4033-4530、`screenshot` 2861、`pdf` 2886、`extract` 3398、`summarize` 3422、`crawl` 3761） |
| markdown 插件 | `browser4-plugins/browser4-markdown/.../MarkdownToolExecutor.kt:55-230` |
| images / media / seo / pptx 插件 | `browser4-plugins/browser4-{images,media,seo,pptx}/.../*ToolExecutor.kt` |
| 页面摘要算法 SPI（插件先例） | `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/workflow/parse/html/PageSummaryAlgorithm.kt`、`.../PageSummaryAlgorithmRegistry.kt`、`.../plugin/MountPoints.kt:147-176` |
| 工具执行器 SPI 与注册 | `browser4-agentic/.../tools/builtin/AbstractToolExecutor.kt`、`.../tools/ToolMount.kt`、`.../tools/CustomToolRegistry.kt` |
| 既有抓取服务 | `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/ScrapeController.kt`（路由 `api/x`）、`.../rest/api/service/ScrapeService.kt`、`browser4-agent-tools/.../advanced/crawl/Models.kt:15-80` |
| ARIA 快照格式 | `docs/aria-snapshots.md` |
| 加载选项 | `docs/load-options-guide.md`、`docs/load-options-quick-ref.md` |
