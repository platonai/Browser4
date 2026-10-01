# URL Normalization 修复计划

> 配套审查报告：[`url-normalization-review.md`](./url-normalization-review.md)
> 分支：`fix/url-normalization-consistency`（Browser4）、`fix/urlutils-normalize-fragment-safe`（browser4base）
> 原则：先修"行为与自身文档/契约矛盾"的地方（不改对外语义），再改"语义本身有歧义"的地方（需确认）。

---

## 决策摘要

| # | 决策 | 结论 | 依据 |
|---|---|---|---|
| D1 | `URLUtils.normalize` 的职责 | **保持窄职责**：剥 args + fragment，不做 canonicalization | 加 host 大小写 / 默认端口 / dot-segment / 尾斜杠 / query 顺序归一化会改掉 page store、page cache、ledger 的 key 语义，需要迁移评估。列入 Phase C |
| D2 | `normalizeForVisit` 的职责 | **crawl 内部身份键**；`lowercase` 只作用于 scheme+host；query 敏感性**待确认**（见 Q1） | `.lowercase()` 作用整条 URL 会吞掉大小写敏感路径，这一点无歧义必改 |
| D3 | `-ignoreUrlQuery` 的作用域 | **只作用于"发现链接"的排队与去重，不改写被加载的主 URL** | 与 `help crawl`、`references/crawl.md` 两处文档一致，也与 draft issue Issue 1 的修复建议一致 |
| D4 | 基础库改动方式 | **待确认**（见 Q2） | 推荐"基础库修正确 + 本轮 browser4 侧自带防护"，避免把 base 仓库未提交的 WIP 编译进依赖 jar |

---

## Phase A —— 本轮执行（低风险，不改对外语义契约）

### A1 修 S2：fragment 里的非法转义不应让整条 URL 判死

**问题**：`URLUtils.normalize` 先用 `URIBuilder` 构造完整 URI 才 `fragment = null`，
所以即将被丢弃的 fragment 里一个裸 `%` 就能让 `https://x.com/a#100%` 变成 `null` → NIL。

**改法**（双处，互为纵深）：
1. **基础库**（`browser4base`）：`URLUtils.normalize` 在 `splitUrlArgs` 之后、
   `URIBuilder` 之前先 `substringBefore('#')`。fragment 反正要丢，不该参与校验。
2. **browser4**：新增归一化门面（`browser4-common`），在把字符串交给 `URLUtils.normalizeOrNull`
   之前统一先剥 fragment。覆盖 4 个真实调用点：
   - `CombinedUrlNormalizer.kt:52`（主链接归一化）
   - `UserCommandExecutor.kt:223`（`isConfiguredUrl`：当前会把可加载页面判为非 URL）
   - `AbstractPulsarSession.kt:865`（`parseNormalizedLink`：当前会把合法 link **静默丢弃**）
   - `Browser4WebDriver` 的 `pageUrlNormalizer` 装配处（`AbstractPulsarSession.kt:397-399`）

**验收**：`#100%`、`#x#y`、`!@#$%^&*()` 三类输入归一化为
`https://x.com/a` / `https://x.com/a` / `http://example.com/!@`，不再返回 null。

### A2 修 M1：`CombinedUrlNormalizer` 的 `noNorm` / `ignoreUrlQuery` 改读 `finalOptions`

`CombinedUrlNormalizer.kt:36,52` 读的是入参 `options`（未被 `finalArgs` 影响），
而同函数第 30-32 行的 `priority` 读的是 `finalOptions`。改为一律读 `finalOptions`，
使"url args 覆盖 LoadOptions"这一自述契约成立。

### A3 修 H1：`-ignoreUrlQuery` 不再改写被加载的主 URL

- `CombinedUrlNormalizer.kt:52` 不再把 `ignoreUrlQuery` 传入 `URLUtils.normalizeOrNull`
  （该处归一化的是**待加载的主 URL**，不是发现链接）。
- 保留 `CrawlSupport.kt:622`（discovered href 层）与 `buildLinkArgs`（`:764`）的现有行为。
- 更正 `CrawlSupport.kt:590-600` 中与代码相反的注释
  （"the load path never sees it for these links"）。
- 行为变化：`crawl --ignore-url-query "<seed with query>"` 现在按文档抓取 **带 query 的 seed**。

### A4 修 M2：`url + args` 不再被当成 URL 校验

`StatefulPageVisitor.kt:173,193` 改为先 `URLUtils.splitUrlArgs(url).first` 再 `isStandard`。
修掉 `https://example.com -expires 1s` → `Invalid URL` 的偶发误报。

### A5 修 L1：统一 fragment 剥离规则

`AbstractPulsarSession.kt:869` 的 `substringBeforeLast("#")` → `substringBefore('#')`
（前者在 `http://h/p#a#b` 上会留下 `#a`）。与 `CrawlSupport.kt:622` 对齐。

### A6 修 H4：重定向的 seed 不再被同时报成功与丢失

`CrawlLedger` 在 `recordSuccess` 时同时登记"提交键"，
或让 `outstanding()`（`CrawlLedger.kt:343-353`）像 `lossReasonForLoaded`
（`CrawlSupport.kt:420`）那样接受 `page.url` 兜底。
恢复 `pages + failed + outstanding == pagesExpected` 不变式。

### A7 日志：NIL 归一化从 info 提到 warn 并带原因

`AbstractPulsarContext.kt:283` 目前 `logger.info("URL is normalized to NIL | {}")`。
改为 `warn` 并说明是归一化链拒绝（区分"非法 URL"与"被 normalizer 拒绝"）。

---

## Phase B —— 需确认后执行（改行为语义）

| # | 项 | 内容 | 阻塞于 |
|---|---|---|---|
| B1 | H2 / H3 | `normalizeForVisit` 增加 query 敏感性；`.lowercase()` 收敛到 scheme+host | **Q1** |
| B2 | M4 | crawl/scrape seed 入队前校验，拒绝非法 seed（替代"静默换搜索引擎"） | 可直接做，但会改 REST 错误语义 |
| B3 | M5 | 统一失败语义：区分"没有 URL"与"URL 非法"，并把非法值回给调用方 | 需要定 API 形状 |
| B4 | M6 / M7 | 经验层：`rawPath` 替代 `path`、`extractDomain` 加 `IDN.toASCII`+lowercase 且禁止回退原始 URL、Level-1 加 URL gate | 独立，可单独排期 |

---

## Phase C —— 本轮不做（列入后续评估）

| # | 项 | 为什么不在本轮 |
|---|---|---|
| C1 | S1：`goto` 路径接入 `normalize` | 需要产品决策："goto 是否应剥 fragment / 规范化"取决于用户预期，且影响 `attach`、fragment 跳转、SPA 路由 |
| C2 | `URLUtils.normalize` canonicalization（D1 的 5 项） | 改 page store / cache / ledger 的 key，需要存量数据迁移与兼容评估 |
| C3 | M8：Scrape/Swarm SQL 转义统一 | 与本主题正交，宜独立 PR |
| C4 | L3：SQL context 保留 `detail` | 同上 |

---

## 测试计划

| # | 位置 | 断言 |
|---|---|---|
| T1 | `browser4-skeleton` 新增 `CombinedUrlNormalizerTest` | 内联 args 的 `-noNorm` / `-ignoreUrlQuery` 生效；fragment 非法转义不再产生 NIL |
| T2 | `browser4-skeleton` 新增 `URLUtilsContractTest` | fragment 安全三类输入；记录 `isStandard` 与 `normalizeOrNull` 的已知分歧（防回归基线） |
| T3 | `CrawlSupportTest` | 按 Q1 结论补 query 敏感性用例 |
| T4 | `CrawlLedgerTest` | 重定向 seed 不出现在 `outstanding()` |
| T5 | `CrawlSupportTest` / `CrawlCheckpointTest` | `-ignoreUrlQuery` 下 seed 保留 query、discovered href 剥 query（A3） |
| T6 | 更新 `CombinedScopedUrlNormalizerTest.kt:150-163` | 空格/特殊字符不再断言"静默截断"，改为断言完整保留或显式失败 |
| T7 | `browser4base` 新增 `URLUtilsTest` | `normalize` 对 fragment 非法转义的处理 |

**最小验证范围**（按 AGENTS.md "Don't run full suites"）：
`mvn -q -DskipTests` 编译 → `mvn test -pl browser4-core/browser4-skeleton -Dtest=CombinedUrlNormalizerTest+URLUtilsContractTest`
→ `mvn test -pl browser4-rest -Dtest=CrawlSupportTest+CrawlLedgerTest+CrawlCheckpointTest`
→ 受影响路径跑一次 `cargo test --test e2e -- --scenario=*crawl*`（需要真实后端时再决定）。

---

## 待确认问题

见对话中的 Q1 / Q2 / Q3。
