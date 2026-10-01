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

## 三、Phase B：经验记忆层（M6 / M7，已在本分支完成）

用户确认的下一步。这一层的三个函数原本互不一致，而它们必须给同一个页面同一个拼写：
`normalize` 产出 `host/path[?显著query]`、`urlPatternOf` 产出存进 fact 的 pattern 形状、
`matches` 回答"某个 pattern 是否覆盖这个 url"。

| # | 问题 | 改动 |
|---|---|---|
| B3-1 | M6 `extractDomain` 是目录名却不设防 | host 小写 + `IDN.toASCII`（`中文.cn` → `xn--fiq228c.cn`）+ 只保留 host 允许字符 + **永不回退成整条 URL**；`KnowledgeStore` 新增 `safeSegment`（domain 与 intent 都过 allow-list，`trim('.')` 挡掉 `..` 逃逸），`factsFilePath` / `writeFactsLocked` / `listFactsForDomain` 三处统一 |
| B3-2 | M6 `matches` 与生产调用形状不匹配 | 重写为单一 `pathOf`/`queryOf`：`/{asterisk}` 能匹配多段路径与根；尾随 `*` 表示"余下路径"（但仍要求至少一段，`/dp/*` 不匹配 `/dp/`）；带 query 的 pattern 约束它点名的参数，不带 query 的旧 pattern 依旧匹配任何 query |
| B3-3 | M6 `urlPatternOf` 有两份重复实现且丢掉 query | 收敛进 `UrlNormalizer`，并保留 query 形状（值通配）——KDoc 承诺的 `/s?k=*` 现在真能产出，也真能匹配 |
| B3-4 | M6 `normalize` 用了解码后的 path/query | 改用 `rawPath`/`rawQuery`：`%2F` 不再被解码成路径分隔符、值里的 `%26` 不再截断；无 scheme 输入先补 `https://`，于是幂等 |
| B3-5 | M7 一页两个 pattern | 丢弃路径里的 `key=value` **追踪段**（只丢已知 key 与 `ref_`/`pf_rd_`/`pd_rd_` 前缀，base64 padding 如 `abc==` 保住）→ `/dp/X/ref=sr_1_1` 与 `/dp/X` 归一 |
| B3-6 | M6 Level-1 绕过 URL、Level-2 按目录顺序 | Level-1 增加 URL gate（pattern 为空时不 gate，保持向后兼容）；Level-2 改用 `findBestMatch`；`specificity` 把 query 形状计入，`/s?k=*` 胜过 `/s` |
| B3-7 | M6 catch 兜底与正常路径不同形状 | 兜底也剥 scheme/fragment/query 并小写 host，不再保留 query、不再可能返回整条 URL |
| B3-8 | M6 参数键大小写 | 显著参数键比较大小写不敏感（`?K=` 不再被整个丢掉） |
| B3-9 | Low 可变全局集合 | `SEMANTICALLY_SIGNIFICANT_PARAMS` 改为不可变 `Set`（每次 `normalize` 都读它，而"按站点可配置"那一期还没设计） |

### 仍未做

| # | 项 | 为什么 |
|---|---|---|
| B1 | M4 | seed 非法时静默换成搜索引擎。改成显式失败会动 REST 语义（202 → 400），而 `AbstractPulsarContext` 的搜索回退看起来是有意设计，需要产品决策 |
| B2 | M5 | 三种失败语义混用、非法 URL 被静默降级成 agent 任务；需要先定"没有 URL / URL 非法"的 API 形状 |
| B4 | H2 补 | `isStandard` 与 `normalizeOrNull` 的分歧（实测 23 组里 9 组结论相反）收敛到一个解析器 |

### Phase C —— 本轮明确不做

| # | 项 | 为什么 |
|---|---|---|
| C1 | S1：`goto` 路径接入归一化 | 需要产品决策（goto 是否应剥 fragment / 规范化），且影响 `attach`、fragment 跳转、SPA 路由 |
| C2 | `URLUtils.normalize` canonicalization | 改 page store / cache / ledger 的 key，需要存量迁移方案（D1） |
| C3 | M8：Scrape/Swarm SQL 转义统一 | ~~与本主题正交~~ → **已完成，见下节** |
| C4 | L3：SQL context 保留 `detail` | ~~同上~~ → **1+2 已完成，3+4 见下节** |

---

## 三之三、Phase C 先行项：C3 与 C4(1+2)（本轮完成）

这两项没有任何产品决策，先做掉。

### C3 — Scrape 与 Swarm 的转义/校验统一

| 改动 | 位置 |
|---|---|
| `escapeSqlStringLiteral` 从 `SwarmController` 的私有方法提到 `ScrapeAPIUtils`（`'` → `''`，CR/LF → 空格） | `ScrapeAPIUtils.kt` |
| 新增 `ScrapeAPIUtils.requireStandardUrl(payload)`：剥掉 LoadOptions 后校验 url，非法则抛 `Malformed url: <...>` | 同上 |
| `ScrapeController.submit` 复用两者（此前**直接插值**，无转义、无校验） | `ScrapeController.kt` |
| `SwarmController.submit` 改调共享实现，删除私有副本，并加上同一份校验 | `SwarmController.kt` |

**修掉的两个后果**：
1. 含 `'` 的 URL 在 `/api/x/submit` 上会破坏 SQL 字面量，调用方却收到 `Invalid URL or X-SQL`（原因指错），且 URL 文本可逃出字面量；
2. `checkSql` 只查**语法**，而 URL 在 `load_and_select('...')` 里语法上永远合法 —— 所以"空主机"一族（`http://`、`https://`、`http://:8080/p`）以前会**返回 UUID 再在异步任务里失败**，或者被 fetcher 悄悄换成默认搜索引擎 URL。现在在提交边界上返回 `Malformed url: <...>`。

**实测：这份校验的覆盖边界**（用真实 `isStandard` 跑过候选集）：

| payload | 结果 |
|---|---|
| `http://`、`https://`、`http://:8080/p`、`http://..`、`http://[::1` | ✅ 被拒（新行为） |
| `htps://exmple.com` | 不以 `http` 开头 → **不进 URL 分支**，仍由 `checkSql` 拒绝，状态码与文案相同 |
| `http://exa mple.com/p` | ❌ 仍漏：`splitUrlArgs` 在第一个空白处截断，token 变成 `http://exa`（合法） |
| `https://example.com/o'brien?q=it's` | ✅ 合法（okhttp 会把 `'` 百分号编码）→ 现在的转义把它安全地送进字面量 |

后两行是**遗留缺口**，属于 S1/M4 一族（入口未规范化 + `splitUrlArgs` 的空白截断），不在本轮范围。

### C4(1+2) — SQL context 的 `NormURL` 重建

`AbstractBrowser4SQLContext.normalize` 改为调用新的 companion 函数 `reKeyForSql`：

| 项 | 内容 |
|---|---|
| **1. 保留 `detail`** | `NormURL(spec, options, hrefSpec = ..., detail = normURL.detail)`。此前 `detail` 被丢掉，`NormURL.referrer`（= `options.referrer ?: detail?.referrer`）的兜底随之失效，调用方也拿不回自己传进来的 `UrlAware` |
| **2. 异常安全** | 重建包在 `runCatching` 里，失败时**返回原 `NormURL`** 而不是抛异常（`NormURL(String)` 会 `URI.create`）。原代码的 `URI.create` 在 try 之外 |

**顺带查清 `^27` 占位符的真相**（原 C4 第 3 项，尚未修）：

- `SQLUtils.sanitizeUrl`（生产 `^27` 的一方）在 **browser4 里没有任何调用者**，`unsanitizeUrl` 只在 SQL context 里被调用 —— 机制是**半接线**的；
- `java.net.URI` 拒绝 `^`，而 `NormURL(String)` 走的就是 `URI.create` —— 所以**携带 `^27` 的 URL 根本到不了 `reKeyForSql`**：`super.normalize` 会先把它判成 NIL。也就是说反净化在当前位置上对一切可解析输入都是 no-op；
- 真正的修法是**把反净化挪到解析之前**（`super.normalize(SQLUtils.unsanitizeUrl(url), ...)`），一行即可 —— 但它会改变"手写 `^27` 的 X-SQL 能否工作"这一行为，且今天没有生产者，属于**无观测效应的语义变更**，所以留给下一轮决定。
- 已在 `reKeyForSql` 的 KDoc 里写明这个不变量，避免下一个人误以为它是活跃路径。

新增测试：`AbstractBrowser4SQLContextTest`（4 条，含用 `java.net.URL` 构造 `^27` URL 来证明反净化确实生效 —— `URI` 做不到这一点）。

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
| 6 | `browser4-agentic` **全量快速套件**（Phase B 之后） | ✅ **1476 / 1476**（含 `UrlNormalizerTest` 45 条、`KnowledgeStore*Test`、`PemKnowledgeProviderTest`、`ExperienceToolExecutor*Test`、`AgentProfileTest`） |
| 7 | 跨模块复查：装上新的 agentic 制品后重跑 `browser4-rest` 全量快速套件 | ✅ **608 / 608** |
| 8 | 基础库 `pulsar-common-tests` 的 `URLUtilsTest` | ✅ **43 / 43**（含新增 2 条） |
| 9 | C3 / C4(1+2) 之后：`browser4-agent-tools` 全量快速套件 | ✅ **77 / 77** |
| 10 | 同上：`browser4-agentic` 全量快速套件 | ✅ **1480 / 1480**（+4 = `AbstractBrowser4SQLContextTest`） |
| 11 | 同上：`browser4-rest` 全量快速套件 | ✅ **616 / 616**（+7 `ScrapeControllerTest`、+1 `SwarmControllerTest`） |

合计 **2301** 条测试通过。

### 基础库测试的执行方式

`browser4base` 工作区曾带有使用者未提交的 WIP，为避免把这份 WIP 编译进依赖，验证走
`-pl pulsar-core/pulsar-core-tests/pulsar-common-tests -am`：反应堆只包含
`Browser4 Base → Pulsar Common → Pulsar Common Tests` 三个模块，本次改动所在的
`pulsar-common` 被就地重编译，其余从本地仓库解析。

本分支在 `browser4base` 上只 `git add` 了 `URLUtils.kt` 与 `URLUtilsTest.kt` 两个文件；
使用者自己的 WIP 后来由使用者自行提交在 `c1dca9881`，期间未被本分支改动或暂存。

> 注：为运行该测试，本地 m2 里的 `pulsar-common-4.11.23-SNAPSHOT.jar` 已被本次改动刷新；
> 它不影响 browser4（后者 pin 的是已发布的 `4.11.21`）。

---

## 五、后续动作

1. ~~基础库发版后删除防护~~ → **已完成**，见下节。
2. ~~`browser4-base.version` 保持 `4.11.21`~~ → **已升到 `4.11.23`**（含 fragment 修复的 release）。
3. Phase B / C 见上表。

---

## 六、本轮（决策落地）：C1-A / M4-A / 基础库升级 / C4-3

### 6.1 基础库升到 `4.11.23`，拆除 `SafeUrlNormalize`

- 已验证 `244d6f47f`（fragment 修复）**在 `v4.11.23` tag 内**（`git merge-base --is-ancestor` 退出码 0），并且用 `pulsar-common-4.11.23.jar` 实测三类输入都已修复、其余行为未变。
- `SafeUrlNormalize.kt` 删除，`CombinedUrlNormalizer` 与 `UserCommandExecutor` 改回 `URLUtils`。

> **顺带修掉一个陷阱**：`browser4-base.version` 原本**声明了两处** —— 根 `pom.xml` 与
> `browser4-dependencies/pom.xml`。真正被消费的只有后者（它 import `pulsar-bom`，是全仓库唯一
> 使用 `${browser4-base.version}` 的地方），根 pom 那一份没有任何读者。也就是说：改根 pom 的版本
> **不会改变任何依赖**。现已把定义收敛到 `browser4-dependencies`，根 pom 只留一条指向它的注释。

### 6.2 C1-A：`goto` 送浏览器的地址只校验、不规范化

用户定的原则：**URL 规范化只用于网页存取身份，送给浏览器的地址尽量保持原有形式**；`Hyperlink`/`UrlAware`
（以及 `NavigateEntry` 自己的 KDoc：`userTypedUrl` 是原始地址、`pageUrl` 是规范化后的库键）已经表达了
这个设计，就是"每个 hyperlink 有一个规范化后的 url，也尽量保留原始 href 作为发给浏览器的首要地址"。

所以本轮**没有**把归一化接进导航路径，只补上缺失的校验：

- `BrowserTabToolExecutor.requireNavigable(url)`：`URLUtils.isBrowserURL(url) || URLUtils.isStandard(url)`，
  否则抛 `Not a navigable address: <...>`；
- 覆盖 `navigate` 的两条分支（`url`、`rawUrl`+`pageUrl`），校验的是 `rawUrl`（真正被导航的地址）。

"可导航"与"可归一化"是**两个问题**：`about:blank` 是合法导航目标却没有规范形式；fragment 是地址的一部分，
`goto "https://h/doc#section"` 必须保留它 —— 这正是不能拿 `normalize()` 当导航前置步骤的原因。

### 6.3 M4-A：crawl / scrape 提交入口校验

- `/api/crawl`：`CrawlController.startCrawl` 对 `url` 与 `urls` 的每一个种子做
  `URLUtils.isStandard(splitUrlArgs(seed).first)`，非法抛 `Malformed url: <...>`；该控制器已有
  `@ExceptionHandler(IllegalArgumentException → 400)`，所以是 **400**。
- `/api/x/submit`、`/api/swarm/submit`：上一轮已加同样的校验。
- **`ScrapeController` 原本没有异常处理器** → 它抛出的 `IllegalArgumentException` 在 Spring 里是 **500**。
  本轮补上与 crawl/swarm 相同的 `@ExceptionHandler(IllegalArgumentException → 400)`，否则"M4-A → 400"
  在 scrape 这一侧只是纸面成立。

`AbstractPulsarContext` 的**搜索引擎回退保留不动** —— 它服务交互式场景，删除属于产品决策。详见下节。

### 6.4 C4-3：`^27` 反净化挪到解析之前

`AbstractBrowser4SQLContext.normalize` 改为 `super.normalize(realUrlOf(url), ...)`，即先把占位符还原成
`'`、再解析。原来的写法是在 `super.normalize` **之后**重建 `NormURL`，而 `^` 不是合法 URI 字符 ——
带 `^27` 的 URL 在解析阶段就被判成 NIL，反净化永远不可能生效（对一切可解析输入都是 no-op），且那次重建
还会丢掉 `detail`。挪到前面之后，`detail` 由 `super` 自然带出，`reKeyForSql` 整个消失。

代价（已在 KDoc 写明）：真正含字面 `^27` 的 URL 会被读成 `'`。这类 URL 本来也不是合法 URI，而 `^27`
不会由任何生产者写进 URL（`URLEncoder` 写的是 `%27`）。

---

## 七、搜索引擎回退：完整判定树与可达路径

`AbstractPulsarContext.normalize(url: String, ...)`（`browser4-core/browser4-skeleton/.../AbstractPulsarContext.kt:242-259`）
是全仓库唯一做这件事的地方：

```
输入串
 ├─ 1. 以 about: / data: / blob: / javascript: / chrome: / edge: 开头 → 原样放行
 ├─ 2. 含 "://"                                                     → 原样放行（任何 scheme，包括拼错的 htps://）
 └─ 3. 否则：当成 base64url 解码
        ├─ a. 解码成功 → 把解码出的字节当 URL 用（乱码，通常最终 NIL）
        └─ b. 抛 IllegalArgumentException → ★ 回退搜索引擎 ★
                                            CN: https://cn.bing.com/
                                            其它: https://cn.bing.com/?ensearch=1
```

**第 3b 步的触发条件**：不含 `://`、不属于上面 6 个前缀、且**不是合法 base64url**。
base64url 字母表是 `A-Za-z0-9-_`（外加 `=` 填充），且 Java 要求长度 `% 4 ∈ {0,2,3}`。

| 输入 | 结局 |
|---|---|
| `not a url`（含空格） | ★ **Bing** |
| `amazon.com`（含 `.`） | ★ **Bing** |
| `你好`（非 ASCII） | ★ **Bing** |
| `electronics`（11 字符，`%4==3`，全是字母） | 能解码 → 乱码 → NIL |
| `abcd` | 能解码 → 乱码 → NIL |
| `htps://exmple.com` | 含 `://` → 原样放行 → 后续 URI 解析失败 → NIL |
| `about:blank` / `chrome://version` | 原样放行 |

**哪些入口真的会走到这里**（这一栏纠正了本报告早期版本的说法）：

| 入口 | 会回退 Bing？ | 原因 |
|---|---|---|
| MCP `goto` / `browser_navigate` | ❌ **不会** | 它根本不经过 `normalize`，原串直接交给 Chrome（见 6.2） |
| CLI / REST 的 plain command（`UserCommandExecutor`） | ❌ 不会 | 前面有 `isConfiguredUrl()`（要求 `normalizeOrNull != null`）拦着 |
| **crawl 种子**（`/api/crawl`） | ✅ **会** | 种子直接进 `session.load(seed)`，本轮之前没有任何校验 |
| scrape / swarm submit | ❌ 不会 | `ScrapeAPIUtils.normalize` 先查 `isStandard`；本轮又把校验前移到提交边界 |
| 任何直接调 `session.load(用户串)` 的集成方 | ✅ 会 | 同上 |

也就是说：**"静默跳 Bing" 实际只在 crawl 种子（以及绕过校验直接调用 load API 的集成方）上暴露** —— 这正是
本轮把校验放在 `/api/crawl` 提交边界的原因。回退本身被保留，因为"用户输入关键词"是它的设计场景。
