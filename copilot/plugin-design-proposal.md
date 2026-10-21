# Browser4 新插件设计提案

## 现有插件盘点

`browser4-plugins` 下共 7 个模块，遵循统一架构（`config/` + `service/` + `integration/BrowseEventHandler` + `tools/ToolExecutor`）：

| 插件 | 价值链位置 | 核心能力 |
|------|-----------|---------|
| captcha | 访问 | reCAPTCHA/hCaptcha/Turnstile 检测 + 第三方打码 + token 注入 |
| protocol | 导航 | 浏览器模拟、隐私上下文池、driver 池管理（基础设施） |
| parse | 提取 | Tika 二进制文档解析（PDF/Word/Excel） |
| images | 提取 | 图片检测（img/picture/CSS bg/OG meta）+ 批量下载 |
| media | 提取 | 视频检测（video/source/blob）+ FFmpeg 下载 |
| markdown | 输出 | DOM→Markdown + 站点爬取 |
| pptx | 输出 | 页面内容→PPTX + 图片嵌入 |

**缺口判断**：交互层（forms）完全无插件抽象；提取层缺最高信号密度的结构化数据（JSON-LD/表格）；导航层无分页抽象；输出层缺监控态（diff）。

---

## 提议新插件（6 个，按 ROI 排序）

### 1. browser4-forms — 表单检测与填充（P0，最大缺口）

**问题**：AI agent 做"搜索→筛选→登录→注册→结账"时，每次都要手动猜输入框类型、拼选择器、处理动态校验。这是 agent 最高频的交互场景，却完全无插件支持。

**核心服务**：
- `FormDetector` — 扫描页面所有 `<form>` 及游离字段，输出字段图（label/placeholder/type/required/pattern/aria）
- `FieldTypeInferrer` — 推断字段语义类型（email/phone/zip/credit-card/name/address/date/captcha），基于 label 文本 + input type + autocomplete attr + placeholder + 上下文 `<select>` 选项
- `FormFiller` — 按字段图 + 用户提供的 value map 填充，处理：select 下拉、checkbox/radio 组、date picker、富文本(contenteditable)、动态联动（选了省份才出现城市）
- `FormSubmitter` — 提交 + 等待结果 + 检测校验错误（字段级 error 提取，返回给 agent 决策）

**ToolExecutor 暴露**：
```
form.detect()                          → List<FormField>
form.inferTypes(selector?)             → List<FormFieldWithSemantics>
form.fill(values: Map<String, Any>)    → FillResult
form.fillAndSubmit(values, waitFor?)   → SubmitResult
form.getValidationErrors()             → List<FieldError>
```

**BrowseEventHandler**：`onDocumentSteady` 时自动检测页面是否有表单，注入 `__b4_forms__` 全局对象供 JS 侧快速查询。

**复用现有能力**：vi 包围盒（排序字段优先级）、`computeInteractiveWeights`（已有交互元素检测）、captcha 插件（表单含验证码时联动）。

---

### 2. browser4-tables — 表格结构化提取（P1）

**问题**：金融数据、产品对比、规格表、价目表——表格是网页中信号密度最高的结构，但 jsoup `select("table")` 只能拿到 HTML，跨行跨列（rowspan/colspan）、嵌套表、div 模拟表全要手工处理。现有 markdown 插件把表格转成 pipe table 会丢失合并单元格语义。

**核心服务**：
- `TableDetector` — 检测真实 `<table>` + div 模拟表（grid/flex 布局且有对齐子元素），输出表格位置和边界
- `TableNormalizer` — 解析 rowspan/colspan 为二维矩阵，处理嵌套表（提取为子表引用），去除外层包裹表（布局用 table）
- `TableExtractor` — 输出结构化数据：header 行/列、数据单元格、合并单元格标记
- `TableExporter` — 导出 CSV / JSON (行列数组) / Excel (.xlsx via POI)

**ToolExecutor 暴露**：
```
table.detect()                          → List<TableRegion>
table.extract(selector?, index?)        → TableData (headers + rows + merged cells)
table.export(selector?, format: "csv"|"json"|"xlsx") → FilePath
table.extractAll(format?)               → List<FilePath>
```

**BrowseEventHandler**：检测到表格时在 page metadata 中标注 `tableCount`，让 agent 知道是否值得调用 table 工具。

---

### 3. browser4-pagination — 分页模式检测与遍历（P1）

**问题**：规模化爬取（10 万页/天目标）时，agent 每到一个列表页都要猜"下一页"选择器——可能是 `a.next`、`a[rel=next]`、`button.load-more`、无限滚动、JS 翻页。没有统一抽象意味着每个站点都要 LLM 花 token 推理。

**核心服务**：
- `PaginationDetector` — 检测分页模式：经典分页（数字页码 + 上一页/下一页）、加载更多按钮、无限滚动（IntersectionObserver / scroll 事件）、URL 规律翻页（`?page=N` / `/page/N/` / path 参数）
- `PaginationTraverser` — 按模式遍历：点击下一页→等待→收集新增项；或滚动到底→等待→收集；或 URL 模式生成
- `PaginationState` — 跟踪已访问页、当前页、是否有下一页、总页数（如可推断）

**ToolExecutor 暴露**：
```
pagination.detect()                    → PaginationPattern
pagination.next()                      → PageLoadResult (新页面的 ref)
pagination.collectAll(itemSelector, maxPages?) → List<ElementRef>
pagination.hasNext()                   → Boolean
```

**BrowseEventHandler**：`onDocumentSteady` 时预检测分页模式，注入到 page metadata 的 `pagination` 字段。

**与现有协作**：markdown 插件的 `SiteCrawler` 可调用 pagination 遍历列表页，每页再转 markdown。

---

### 4. browser4-structured — 结构化数据提取（P1）

**问题**：现代网站大量嵌入 JSON-LD、微数据（itemprop）、Open Graph、Twitter Card——这是信号密度最高、解析成本最低的结构化数据。但当前无插件提取，agent 只能拿原始 HTML 让 LLM 自己找。

**核心服务**：
- `StructuredDataExtractor` — 提取四类结构化数据：
  - JSON-LD（`<script type="application/ld+json">`）→ 解析为 Schema.org 对象
  - 微数据（`itemscope`/`itemtype`/`itemprop`）→ 转为结构化对象
  - Open Graph（`<meta property="og:*">`）→ 统一格式
  - Twitter Card（`<meta name="twitter:*">`）→ 统一格式
- `SchemaNormalizer` — 将不同来源归一化为统一 `PageStructuredData`（title/description/image/type/author/date/price/rating 等），标注数据来源和置信度
- `ProductExtractor` / `ArticleExtractor` / `EventExtractor` — 按 Schema.org @type 分发到专用提取器，输出强类型对象

**ToolExecutor 暴露**：
```
structured.extract()                   → PageStructuredData (all sources merged)
structured.jsonld()                    → List<JsonObject>
structured.opengraph()                 → Map<String, String>
structured.product()                   → ProductInfo? (name/price/currency/availability/rating)
structured.article()                   → ArticleInfo? (headline/author/datePublished/wordCount)
```

**BrowseEventHandler**：提取后注入 page metadata 的 `structured` 字段，供 agent 无 token 决策"这页是什么类型"。

---

### 5. browser4-auth — 会话与登录态持久化（P2）

**问题**：agent 需要保持登录态跨页面/跨任务（已登录用户看到的页面不同）。当前每次新建 session 都是干净的，cookie/session 无法持久化，也无法复用 OAuth 流程。

**核心服务**：
- `SessionStore` — 持久化 cookie + localStorage + sessionStorage + IndexedDB（按域名隔离），存到磁盘/数据库
- `LoginFlowCapture` — 记录一次成功登录的关键步骤（URL 跳转链、表单提交、token 交换），生成可重放的 `LoginRecipe`
- `SessionRestorer` — 从 SessionStore 恢复登录态到新 session，验证有效性（请求受保护页面检查）
- `AuthStateMonitor` — 检测当前 session 是否仍登录（检查特定 cookie/元素/重定向），失效时通知 agent

**ToolExecutor 暴露**：
```
auth.save(name: String)                → SessionSnapshot (cookie + storage 持久化)
auth.load(name: String)                → Boolean (恢复到当前 session)
auth.captureLogin(name: String)        → LoginRecipe (记录登录流程)
auth.replayLogin(name: String)         → Boolean (重放登录)
auth.isLoggedIn()                      → AuthState
auth.listSaved()                       → List<SessionName>
```

**BrowseEventHandler**：页面加载后自动检测登录态变化（URL 含 /login、出现登录表单 → 触发 `onAuthRequired` 事件）。

**安全边界**：SessionStore 加密存储敏感数据；LoginRecipe 不存密码，只存流程+token；提供 `auth.clear()` 清除。

---

### 6. browser4-diff — 页面变更监控（P2）

**问题**：价格监控、新闻追踪、库存告警、竞品监测——这些都是"定期看同一个页面，发现变了就通知"的场景。当前无插件支持，agent 要自己存快照、自己做 diff。

**核心服务**：
- `PageSnapshotStore` — 按URL+时间戳存储页面快照（DOM 哈希 + 关键区域文本 + 截图路径）
- `PageDiffer` — 三种 diff 模式：
  - 文本 diff（段落级 LCS 算法，输出增/删/改段落）
  - DOM diff（选择器级，输出哪些元素新增/删除/属性变化）
  - 区域 diff（用户指定 CSS 选择器，只 diff 该区域）
- `ChangeClassifier` — 分类变更：价格变化（提取数字+货币符号比较）、库存变化（in-stock/out-of-stock）、内容更新（新文章/新评论）、结构变化（布局改版）
- `ChangeNotifier` — 变更触发通知（webhook / 回调 / 写文件），带 diff 摘要

**ToolExecutor 暴露**：
```
diff.snapshot(url?, selector?)         → SnapshotId
diff.compare(snapshotId1, snapshotId2) → ChangeReport
diff.compareToLast(selector?)          → ChangeReport (与上次快照比)
diff.watch(url, selector?, interval?)  → WatchJob (注册定时监控)
diff.listChanges(url?, since?)         → List<ChangeReport>
```

**BrowseEventHandler**：每次页面加载后自动存快照（可配置开关），为后续 diff 提供基线。

---

## 优先级矩阵

| 插件 | 用户需求 | 实现难度 | ROI | 建议 |
|------|---------|---------|-----|------|
| forms | 极高（每个 agent 都要） | 中 | ★★★★★ | 第一批 |
| structured | 高（零成本高信号） | 低 | ★★★★★ | 第一批 |
| tables | 中高（金融/电商刚需） | 中 | ★★★★ | 第二批 |
| pagination | 高（规模化爬取必需） | 中 | ★★★★ | 第二批 |
| auth | 中（特定场景必需） | 高 | ★★★ | 第三批 |
| diff | 中（监控场景） | 中 | ★★★ | 第三批 |

## 架构一致性

所有新插件遵循现有四层结构：
```
browser4-<name>/
  src/main/kotlin/ai/platon/pulsar/<name>/
    config/<Name>Config.kt + <Name>AutoConfiguration.kt
    service/<Name>Detector.kt + <Name>Extractor.kt ...
    integration/<Name>BrowseEventHandler.kt
    tools/<Name>ToolExecutor.kt   ← extends AbstractToolExecutor, declares domain + toolSpec
```

ToolExecutor 通过 `AbstractToolExecutor` 自动注册到 agent 的工具表；BrowseEventHandler 通过 Spring `@Component` + `BrowseEvent` 机制自动接入页面加载生命周期。新插件只需在 `browser4-plugins/pom.xml` 加 `<module>` 并在 `browser4-apps` 引入依赖即可激活。
