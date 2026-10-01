# URL Normalization 修复计划与执行记录

> 配套审查报告：[`url-normalization-review.md`](./url-normalization-review.md)
> 分支：Browser4 `fix/url-normalization-consistency`、browser4base `fix/urlutils-normalize-fragment-safe`
> 原则：先修"行为与自身文档/契约矛盾"的地方（不改对外语义），再改"语义本身有歧义"的地方（已与用户确认）。

---

## 一、已确认的决策

| # | 决策 | 结论 | 依据 |
|---|---|---|---|
| D1 | `URLUtils.normalize` 的职责 | **保持窄职责**：剥 args + fragment，本轮不做 canonicalization | 加 host 大小写 / 默认端口 / dot-segment / 尾斜杠 / query 顺序归一化会改掉 page store、page cache、ledger 的 key 语义，需要存量迁移评估。列入 Phase C |
| D2 | `normalizeForVisit` 的职责 | **crawl 内部身份键，默认保留 query**；`lowercase` 只作用于 scheme+host | 用户确认方案 A。分页站点被静默限制在第一页属于缺陷而非设计 |
| D3 | `-ignoreUrlQuery` 的作用域 | **只作用于"发现链接"的排队与去重**，不改写被加载的主 URL | 与 `help crawl`、`references/crawl.md` 一致，也与 draft issue Issue 1 的修复建议一致 |
| D4 | 基础库落地方式 | 基础库分支修正确 + browser4 本轮自带防护；下个基础库发版后删防护 | 用户确认方案 A。browser4 仍 pin 已发布的 `4.11.21`，CI 不受影响 |

---

## 二、已完成的改动

### 基础库（browser4base，分支 `fix/urlutils-normalize-fragment-safe`，commit `244d6f47f`）

| 文件 | 改动 |
|---|---|
| `pulsar-core/pulsar-common/.../urls/URLUtils.kt` | `normalize` 在构造 uri **之前**先剥 fragment（`substringBefore('#')`），fragment 不再能让整条 URL 判死 |
| `pulsar-core/pulsar-core-tests/.../urls/URLUtilsTest.kt` | 新增 2 条测试：丢弃的 fragment 不得拒绝 URL；**保留部分**的非法转义仍被拒绝 |

实测（用真实 `pulsar-common-4.11.21.jar` 里的 `splitUrlArgs` + `URIBuilder` 复现新旧算法）：

| 输入 | 旧 | 新 |
|---|---|---|
| `https://example.com/a#100%` | `URISyntaxException` | `https://example.com/a` |
| `https://example.com/a#x#y` | `URISyntaxException` | `https://example.com/a` |
| `http://example.com/!@#$%^&*()` | `URISyntaxException` | `http://example.com/!@` |
| `https://example.com/a%` / `a%zz` | 拒绝 | 拒绝（不变） |
| `http://example.com/path&%!({{` | `URISyntaxException` | `URISyntaxException`（不变，既有测试仍通过） |
| `https://example.com/p -requireNotBlank '#productTitle'` | `https://example.com/p` | `https://example.com/p`（`#` 在被剥离的 args 里，未被误判） |

### browser4

| 项 | 位置 | 改动 |
|---|---|---|
| **A1** 新增防护门面 | `browser4-skeleton/.../common/urls/SafeUrlNormalize.kt` | 先 `splitUrlArgs` 再 `substringBefore('#')`，之后才交给 `URLUtils`。带 TODO：基础库发版后删除 |
| **S2** 修复 | `CombinedUrlNormalizer.kt` | 改走 `SafeUrlNormalize`；`NormURL` 构造包 `runCatching`，`-noNorm` 下不可解析的 URL 变 NIL 而不是抛异常 |
| **A2** 修复 M1 | `CombinedUrlNormalizer.kt` | `noNorm` / `ignoreUrlQuery` 一律读 `finalOptions`（与同函数内 `priority` 一致，兑现"url args 覆盖 LoadOptions"的自述契约） |
| **A3** 修复 H1 | `CombinedUrlNormalizer.kt` | 归一化**不再**传入 `ignoreUrlQuery`：该 flag 属于发现链接层。seed 不再被改写（对齐 draft issue Issue 1），本地文件 URL 不再塌缩成一个 cache key |
| **A4** 修复 M2 | `StatefulPageVisitor.kt` ×2 | 先 `splitUrlArgs(...).first` 再 `isStandard` / 进 X-SQL |
| **A5** 修复 L1 | `AbstractPulsarSession.kt` | `substringBeforeLast("#")` → `substringBefore("#")`（与 `CrawlSupport` 对齐） |
| **A6** 修复 H4 | `CrawlSupport.resolveQueueDepthKey` + `CrawlLedger.recordSuccess(url, submittedUrl)` + `CrawlRoundRunner` | 重定向/`<base href>` 的页面同时结清"报告身份"和"提交身份"，不再同时出现在成功行与 `outstanding()` |
| **A7** 日志 | `CombinedUrlNormalizer.nil()` / `AbstractPulsarContext` | NIL 原因在 `CombinedUrlNormalizer` 里以 warn 说出（浏览器内部 URL 如 `about:blank` 降为 debug）；`AbstractPulsarContext` 的重复 info 日志移除 |
| **Q1-A** crawl 身份键 | `CrawlSupport.normalizeForVisit` | 默认**保留 query**；尾斜杠只从 path 上剥（`/p/?utm=1` ≡ `/p?utm=1`）；只 lowercase scheme+host（path/query/userinfo 大小写保留） |
| **入口** | `UserCommandExecutor.isConfiguredUrl` | 改用 `SafeUrlNormalize`：fragment 里带非法转义的 URL 不再被降级成 agent 任务 |
| 文档 | `CrawlSupport` KDoc ×3 | 更正与代码相反的注释（"the load path never sees it"、query 折叠规则） |

### 测试

| 文件 | 内容 |
|---|---|
| `CombinedUrlNormalizerTest.kt`（新增 9 条） | 内联 `-noNorm` / `UrlAware.args` 的 `-noNorm` 生效；`-ignoreUrlQuery` **不**改写被加载的 URL、**不**塌缩本地文件 URL；fragment 非法转义不再 NIL；path 非法转义仍 NIL；`-noNorm` + 不可解析 → NIL 不抛异常；normalizer 链拒绝 → NIL |
| `CombinedScopedUrlNormalizerTest.kt` | 特殊字符用例从"断言 isNil"改为断言正确结果 `http://example.com/!@`；空格用例保留并注明是已知限制（S1） |
| `CrawlSupportTest.kt` | 身份键断言按 Q1-A 重写（query 敏感、只折叠 host 大小写、尾斜杠只在 path 上剥）；`selectDiscoveredLinks` 的 `repeated` / `overBudget` / `links` 期望值更新 |
| `CrawlLedgerTest.kt` | 新增"served elsewhere 的页面同时结清提交身份" |

---

## 三、未做（已排期，不在本轮）

### Phase B —— 需要先定 API 形状

| # | 项 | 内容 |
|---|---|---|
| B1 | M4 | crawl/scrape seed 入队前校验，替代"静默换搜索引擎"（会改 REST 错误语义：从 202+搜索引擎结果 变成 400） |
| B2 | M5 | 统一失败语义：区分"没有 URL"与"URL 非法"，并把非法值回给调用方（`ConversationService` 的 `"URL must not be blank"` 文案也是错的） |
| B3 | M6/M7 | 经验层：`rawPath` 替代 `path`、`extractDomain` 加 `IDN.toASCII` + lowercase 且禁止回退原始 URL、Level-1 加 URL gate |
| B4 | H2 补 | `isStandard` 与 `normalizeOrNull` 的分歧（实测 23 组里 9 组结论相反）收敛到一个解析器 |

### Phase C —— 本轮明确不做

| # | 项 | 为什么 |
|---|---|---|
| C1 | S1：`goto` 路径接入归一化 | 需要产品决策（goto 是否应剥 fragment / 规范化），且影响 `attach`、fragment 跳转、SPA 路由 |
| C2 | `URLUtils.normalize` canonicalization | 改 page store / cache / ledger 的 key，需要存量迁移方案（D1） |
| C3 | M8：Scrape/Swarm SQL 转义统一 | 与本主题正交，宜独立 PR |
| C4 | L3：SQL context 保留 `detail` | 同上 |

---

## 四、验证范围与结果

按 AGENTS.md "Don't run full suites"，从最小相关范围起步，按风险升级到 PR 门禁同口径（`surefire.excludedGroups` 默认
已排除 Slow/Heavy/Integration/E2E/Requires*，与 `.github/workflows/pr.yml` 的 excluded_groups 同量级）。

| 步骤 | 命令 | 结果 |
|---|---|---|
| 1 | `mvnw -o -q -DskipTests -pl browser4-core/browser4-skeleton,browser4-agent-tools,browser4-rest -am compile` | ✅ 通过 |
| 2 | `browser4-skeleton`：`CombinedUrlNormalizerTest` + `CombinedScopedUrlNormalizerTest` | ✅ **19 / 19** |
| 3 | `browser4-skeleton` 扩大：`NormUrlTests` + `HyperlinkTests` + `TestLoadOptions` + `AbstractPulsarSessionLoadTest` + 上述两条 | ✅ **85 / 85** |
| 4 | `browser4-rest`：`CrawlSupportTest` + `CrawlLedgerTest` + `CrawlCheckpointTest` + `CrawlResumeTest` | ✅ **119 / 119** |
| 5 | `browser4-rest` **全量快速套件** | ✅ **608 / 608**（先出现 3 个 `LlmConfigTemplateTest` 失败，定位为本地 m2 的 `browser4-resources` jar 陈旧、缺少 `config/conf-available/application-private.properties.template`；刷新该模块后 5/5 通过，与本次改动无关） |
| 6 | 基础库 `pulsar-common-tests` 的 `URLUtilsTest` | 见下 |

### 基础库测试的执行方式

`browser4base` 工作区带有使用者未提交的 WIP（`BrowserId.kt`、`PulsarBrowser.kt`、`ProfilePaths.kt`、
`WebSocketChromeImpl.kt` 及对应测试）。为避免把这份 WIP 编译进依赖，验证只编译并安装
**本次改动所在的 `pulsar-common` 模块**（该模块在本分支外没有任何未提交改动），再单独跑
`pulsar-common-tests` 的 `URLUtilsTest`。

本分支在 `browser4base` 上只 `git add` 了两个文件，使用者的 WIP 保持未暂存、未被提交。

---

## 五、后续动作

1. **基础库发版后删除防护**：`SafeUrlNormalize.kt` 的 TODO 指向本节。删除时把
   `CombinedUrlNormalizer` 与 `UserCommandExecutor` 改回 `URLUtils`，并移除该文件与其引用。
2. `browser4-base.version` 保持 `4.11.21`（已发布的 release），本轮不动 —— CI 才能解析到依赖。
3. Phase B / C 见上表，建议各自独立 PR。
