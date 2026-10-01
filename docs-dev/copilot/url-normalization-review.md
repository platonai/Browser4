# URL Normalization 机制审查报告

> **范围**：`browser4-core`（会话 / 加载 / 浏览器）、`browser4-rest`（crawl / REST 入口）、
> `browser4-agentic`（经验记忆层），以及被依赖的基础库 `pulsar-common`。
> **审查基线**：`4.14.x` @ `7f503ab35f`；基础库 `browser4-base.version = 4.11.21`。
> **方法**：`URLUtils` 的行为不靠读码推断 —— 用**实际依赖的 `pulsar-common-4.11.21.jar`**
> （okhttp 4.12.0 + httpcore5 + commons-lang3 + okio）在 JVM 上直接跑了 60+ 组输入：
> 23 组 `isStandard` vs `normalizeOrNull` 对照、10 组"应等价"去重配对、幂等性、
> `splitUrlArgs`/`mergeUrlArgs` round-trip。下文凡行为断言均标注 `[实测]` 或 `[读码]`。

---

## 一、核心结论

**系统里没有"URL 的规范形式"这一个概念，而是同时存在 4 套互不兼容的身份函数。**

| # | 规范化器 | 规则 | 实际用作 |
|---|---|---|---|
| 1 | `URLUtils.normalize`（基础库） | 只剥 args + fragment；query 仅当 `ignoreQuery`；**其余原样** | 页面存储 / page cache key（`NormURL.urlString`） |
| 2 | `CrawlSupport.normalizeForVisit` | `trim().lowercase()` + **无条件**剥 `#`、`?` + 去尾 `/` | crawl ledger / visited / depths / checkpoint / resume |
| 3 | `agentic.tools.experience.UrlNormalizer.normalize` | 剥 `www.`、去尾 `/`、**丢 scheme**、query 白名单（`q/id/page/k`） | 经验库 `url_pattern`（`normalize()` 输出实际未被用作 key，见 M6） |
| 4 | `AbstractBrowser4SQLContext.normalize` | 在 #1 之上再套 `SQLUtils.unsanitizeUrl` 并**重建** `NormURL`（丢 `detail`） | X-SQL 路径 |

只要"同一份输入在四处的'是不是同一页'结论不同"，几乎所有下游问题都能追溯到"用错了哪一套"。

---

## 二、实测结果（真实 jar，非推断）

### 2.1 同一个商品页的 5 种写法 → 4 个不同 key

```
https://www.amazon.com/dp/B0CXJ1NT4B    → https://www.amazon.com/dp/B0CXJ1NT4B
https://amazon.com/dp/B0CXJ1NT4B/       → https://amazon.com/dp/B0CXJ1NT4B/
https://amazon.com/dp/B0CXJ1NT4B        → https://amazon.com/dp/B0CXJ1NT4B
https://AMAZON.com/dp/B0CXJ1NT4B        → https://AMAZON.com/dp/B0CXJ1NT4B
https://amazon.com/dp/B0CXJ1NT4B#reviews→ https://amazon.com/dp/B0CXJ1NT4B   （唯一被合并的）
```

### 2.2 10 组"应等价"配对，9 组判为不同

| 配对 | 结果 |
|---|---|
| 仅 fragment 不同 | ✅ SAME |
| host 大小写 / 默认端口 `:443` / `/a/../b` / 尾斜杠 / `/` vs 无 / query 顺序 / `%7E` vs `~` / `a//b` / `www.` 前缀 | ❌ 全部 DIFFER |

结论：`URLUtils.normalize` **不是** canonicalizer，它只是"剥 args 与 fragment"。

### 2.3 `isStandard` 与 `normalizeOrNull` 在 23 组输入上有 9 组结论相反

两者分别把守不同入口，因此"合法"与"可规范化"是两个不同答案：

| 输入 | `isStandard` | `normalizeOrNull` |
|---|---|---|
| `https://x.com/a#100%` | ✅ true | ❌ **null** |
| `https://x.com/a#x#y` | ✅ true | ❌ **null** |
| `http://example.com/!@#$%^&*()` | ✅ true | ❌ **null** |
| `https://x.com/a%` / `a%zz` | ✅ true | ❌ **null** |
| `https://x.com:99999/a`（端口越界） | ❌ false | ✅ 通过 |
| `file:///C:/tmp/a.html` | ❌ false | ✅ `file:/C:/tmp/a.html`（`///` 被吃掉） |
| `ftp://x.com/a`、`mailto:a@b.com` | ❌ false | ✅ 通过 |

### 2.4 其它实测行为

| 输入 | `normalize` 结果 | 备注 |
|---|---|---|
| `https://example.com/a b`（裸空格） | `https://example.com/a` | **静默截断**，且 `CombinedScopedUrlNormalizerTest.kt:150-163` 把此行为钉为期望值 |
| `https://example.com/a\u00a0b`（NBSP） | `https://example.com/a` | 同上 |
| `https://x.com/a?`（空 query） | `https://x.com/a?` | 与 `https://x.com/a` 不等价 |
| `https://x.com/a%23b` | `https://x.com/a%23b` | 编码的 `#` 正确未被当 fragment ✅ |
| `about:blank` / `chrome://version` / `data:` | `null` | 内部 URL 归一化为 NIL（由上层 `isInternal` 另行处理） |
| `http://localfile.internal?path=<base64>` + `ignoreQuery` | `http://localfile.internal` | **所有本地文件塌缩为一个 key** |
| `http://browser.internal?url=chrome%3A%2F%2F...` + `ignoreQuery` | `http://browser.internal` | 同上 |

### 2.5 关键常量真值 `[实测]`

```
INTERNAL_URL_PREFIX       = http://internal.platon.ai
NIL_PAGE_URL              = http://internal.platon.ai/nil
LOCAL_FILE_BASE_URL       = http://localfile.internal
BROWSER_INTERNAL_BASE_URL = http://browser.internal
SEARCH_ENGINE_URL         = https://cn.bing.com/
SEARCH_ENGINE_EN_URL      = https://cn.bing.com/?ensearch=1
```

---

## 三、问题清单

### 🔴 严重

#### S1. `goto` 路径对 URL 零校验、零规范化，而其它所有路径都规范化 `[读码，逐层确认]`

```
browser4-cli goto
  → MCPToolController.kt:1638      "navigate" -> ToolCall("tab", "navigate", args1)
  → BrowserTabToolExecutor.kt:1044 driver.navigate(paramString(args, "url", functionName)!!)
  → Browser4WebDriver.kt:2451      ensureFocusEmulation(); super.navigate(entry)
  → AbstractWebDriver.kt:297       navigate(NavigateEntry(userTypedUrl))
  → CDP Page.navigate
```

链路中**没有任何一处**调用 `normalize` / `isStandard`。后果：

- `goto "https://x.com/a#frag"` 浏览器带 fragment 导航，而 crawl/load 路径会剥掉它 ——
  **同一输入两种身份**。
- `goto "https://x.com/a b"`：Chrome 会编码为 `/a%20b` 并正常加载，而 `normalize` 给出 `/a`。
  此时 `pageUrlNormalizer`（`AbstractPulsarSession.kt:397-399`）写进 HTML 的
  `link[rel=normalizedURI]` 记录的是 `/a`，**与实际抓到的文档不是同一个 URL** ——
  离线消费方按该 link 取回的是另一页。
- 非标准串会落到 `PulsarWebDriver.kt:2099-2102`：
  `logger.warn("Invalid url to sent to the browser | {}")` 后**直接 return**，
  静默跳过导航记账（cookie / 状态），用户拿不到错误。

#### S2. 一个"即将被丢弃"的 fragment 能把整条 URL 判死 `[实测]`

`URLUtils.normalize` 先用 `URIBuilder` 构造完整 URI，之后才 `fragment = null`
（`URLUtils.kt:225-237`）。因此 **fragment 内的非法转义会先让构造失败**：

```
https://x.com/a#100%   → null    （浏览器可正常访问）
https://x.com/a#x#y    → null    （浏览器把 fragment 视为 "x#y"）
http://example.com/!@#$%^&*()  → null
```

`null` → `CombinedUrlNormalizer.kt:55-56` → `NormURL.createNil` → `NormURL.kt:79 isNil`
→ `LoadComponent.kt:293` 返回 `GoraWebPage.NIL`。

反馈只有一句 **info** 级日志（`AbstractPulsarContext.kt:283`
`logger.info("URL is normalized to NIL | {}")`）。用户看到空页面 + 一句 info，
完全不知道原因是 fragment 里的一个 `%`。
这直接违反 AGENTS.md 的 "No silent failures / error messages must surface the real cause"。

### 🟠 高

#### H1. `-ignoreUrlQuery` 作用于"被加载的 URL"而非"去重键"，抓的不是用户要的文档

`CombinedUrlNormalizer.kt:52`：

```kotlin
normURL = URLUtils.normalizeOrNull(normURL, options.ignoreUrlQuery)
```

该 `normURL` 就是**定位资源用的** `NormURL.url`，而 seed / portal 也走同一条 `normalize()`。
所以 `crawl --ignore-url-query "https://h/search?q=widgets&page=3"` **实际抓 `https://h/search`**，
报告行却仍打印用户请求的原始 URL。

> **非本次新发现**：`coworker/tasks/issues/draft/20260916-200854-crawl-advanced-extraction.issues.md`
> **Issue 1（High，draft = 未修）** 已用真实 crawl + A/B 对照复现
> （depth-0 返回 400 页却报 "Category: Electronics"，depth-1 报 "0 pages found"）。
> 已核对当前源码，**缺陷仍然存在**；同一单还指出 depth-1 与 depth-2 行为不一致。

另：`CrawlSupport.kt:595-597` 的注释声称 "the load path never sees it for these links"，
**与代码相反**。

#### H2. crawl 去重键无条件丢弃 query，分页 / 搜索页被合并掉 `[读码 + 已有测试钉死]`

```kotlin
// CrawlSupport.kt:91-96
internal fun normalizeForVisit(url: String) = url.trim().lowercase()
    .substringBefore('#').substringBefore('?').removeSuffix("/")
```

`CrawlLedger.kt:182-183` 用它做 `submittedUrls.putIfAbsent(key, depth)`。
**注意这与 `-ignoreUrlQuery` 无关**（`LoadOptions.kt:605` 默认 `false`）——
即**默认情况下** `?page=2` 与 `?page=3` 就是同一个 key，第二个**永远不会入队**。

该行为已被 `CrawlSupportTest.kt:145-164`（"one page offered twice is queued once"）
明确钉为设计。但**代价没有被钉住**：

- 常见分页形式（`?page=N`、`?k=`）在 crawl 里**只能抓到第一页**；
- `.lowercase()` 作用于**整条 URL**，而路径大小写敏感 ——
  `/Product/1` 与 `/product/1` 在大小写敏感服务器上是两页，此处被合并；
- 与规范化器 #1 **方向相反**：`URLUtils` 不 lowercase host（不够狠），
  `normalizeForVisit` 却 lowercase path（太狠）。

#### H3. 由 H2 推出的具体塌缩：一批本地文件 URL 只能抓到一个 `[实测 + 读码]`

本地文件 URL 形如 `http://localfile.internal?path=<base64>`。
`normalizeForVisit` 剥掉 `?` 后**全部变成 `http://localfile.internal`** —— 一个 key。
而 `MassiveScrapeTaskTest.kt:81` 正是用 `URLUtils.pathToLocalURL()` 批量投喂的。
结果：N 个本地文件在 crawl / ledger / checkpoint / resume 里只剩 1 个。

同一塌缩在 `-ignoreUrlQuery` 下还会命中 **page cache**：
`AbstractPulsarSession.kt:844-853` 的 cache key 是 `normURL.urlString`，
`ignoreUrlQuery=true` 时 `path` 参数被剥掉 → 所有本地文件共享一个缓存键 →
`-readonly` 模式下**第二个文件会命中第一个文件的缓存页**。
`http://browser.internal?url=chrome://...` 同理。

#### H4. crawl 的"行 key"与"ledger key"不一致，重定向的 seed 被同时报成功与丢失 `[读码]`

`CrawlRoundRunner.kt:646` 用 `normalizeForVisit(page.url)`（**服务后** URL）`recordSuccess`，
而提交时用的是**提交拼写**（`CrawlLedger.kt:182-184`）。
`outstanding()`（`CrawlLedger.kt:343-353`）只查 `succeededKeys`，没有 `page.url` 兜底
（而 `CrawlSupport.kt:420` 的 `lossReasonForLoaded` 有）。

于是 `http://h/a → http://h/b` 的重定向 seed 会**同时**出现在成功行和 `outstanding` 里，
破坏 `pages + failed + outstanding == pagesExpected` 不变式，并让 resume 重复抓取。

### 🟡 中

#### M1. 内联 `-noNorm` / `-ignoreUrlQuery` 被解析进 `finalOptions`，却在归一化时被忽略 `[读码，已确认无副作用]`

```kotlin
// CombinedUrlNormalizer.kt
val finalOptions = createLoadOptions(url, LoadOptions.parse(finalArgs, options), toItemOption)
if (!finalOptions.isDefault("priority")) { url.priority = finalOptions.priority }  // ✅ finalOptions
...
if (!options.noNorm) {                                                             // ❌ options
    normURL = URLUtils.normalizeOrNull(normURL, options.ignoreUrlQuery)            // ❌ options
}
```

已确认 `LoadOptions(args, other)` 是**新建实例**（`LoadOptions.kt:730-731`，不修改 `other`），
`createLoadOptions0` 也是 `clone()`（`:71-86`），所以 `options` 确实不受 `finalArgs` 影响。

后果：

- 违反本函数自己的 KDoc（第 14 行 "url arguments overrides the LoadOptions"），
  也与同函数内 `priority` 的处理自相矛盾；
- `session.load("https://h/p?q=1 -ignoreUrlQuery")` **不会**剥 query；
  `session.load("https://h/p -noNorm")` **仍会**归一化；
- `CrawlSupport.buildLinkArgs`（`CrawlSupport.kt:752-767`）的 KDoc 明确承诺
  "把 `-ignoreUrlQuery`/`-noNorm` 放进 args 就能到达 load" —— 它确实放进了
  `ParsableHyperlink("$linkUrl $linkArgs", ...)` 的 **url 字符串**
  （`CrawlRoundRunner.kt:450,867`；`ParsableHyperlink.kt:23` 只把 `args` 设为 `"-parse"`，
  因此 linkArgs 落在 `args1` 槽）。crawl 之所以看起来正常，只是因为
  **同一批 flag 也存在于 round options 里**。任何只把 flag 写在链接上的调用方都会静默失效。

#### M2. `url + args` 被当成一个 URL 校验，成败取决于 URL 有没有 path `[实测]`

`UserCommandExecutor.kt:206` 传 `PageVisitRequest(url = url.urlSpec, ...)`，
而 `NormURL.kt:67` 的 `urlSpec = "$urlString $args"`；
`StatefulPageVisitor.kt:173` 直接 `require(URLUtils.isStandard(url))`。实测：

```
https://example.com    -expires 1s        → std=false  ❌ 误报 Invalid URL
https://example.com/p  -priority -2000    → std=true   ✅ 侥幸通过
https://example.com/p  -outLink "a[href]" → std=true   ✅
```

根因是 okhttp 对空格宽松：空格落在 **path/query** 里会被百分号编码（通过），
落在 **authority** 里就非法（失败）。因此"根 URL + 参数"以
`Invalid URL: https://example.com -expires 1s`（`StatefulPageVisitor.kt:156-159`）失败，
而带路径的同样调用却正常 —— **只看输入形状的偶发 bug**。
其余地方都先 `splitUrlArgs`（`CombinedUrlNormalizer.kt:33`、`ScrapeAPIUtils.kt:52-56`），此处漏了。

#### M4. seed 非法时静默换成搜索引擎 `[读码 + 实测常量]`

```kotlin
// AbstractPulsarContext.kt:242-255
} catch (_: IllegalArgumentException) {
    logger.warn("Invalid URL, will goto the default search engine {}", ...)
    if (AppContext.isCN) SEARCH_ENGINE_URL else SEARCH_ENGINE_EN_URL   // https://cn.bing.com/
}
```

不含 `://` 的串会被当作 base64 解码，解码抛异常即落到搜索引擎。
`goto "amazon.com"`（漏 scheme）→ **静默跳 Bing**。
而 `CrawlService` 的 seed 校验只查空白 / 深度 / 并发（`CrawlController.kt:33-56`），
`POST /api/crawl {"url":"htps://exmple.com"}` 会返回 taskId、消耗预算，
最后把搜索引擎结果当成 crawl 结果返回。

#### M5. 三种失败语义混用：`normalizeOrNull` 吞掉一切 / `normalizeOrEmpty` 返回 `""` / `isStandard` 给 bool

`AbstractPulsarContext.kt:271-278,288-293` 全是 `runCatching{}.getOrNull()`；
`ConversationService.kt:73-89` 把 `require(isStandard(url))` 包在 try/catch 里，
warn 后返回 null，于是 URL 命令**静默降级成 LLM agent 任务**，
且准备的错误文案本身是错的（`"URL must not be blank"` 用在非空白但非法的 URL 上）。
批量加载会丢非法 URL 却不告诉调用方丢了哪些。

#### M6. 经验记忆层的 `normalize()` 在生产路径上实际是死代码，而真正的 key 会分叉 `[读码复核]`

`agentic/tools/experience/UrlNormalizer.kt:49-73` 保留 `q/id/page/k` 等 query 参数，
但两个调用方紧接着调 `extractPath`（`ExperienceToolExecutor.kt:534`、`KnowledgeStore.kt:342`），
而 `extractPath` 用 `URI.rawPath`（`:103`）—— **query 被丢掉**。按代码逐步推演确认：

```
matches("/s",     "amazon.com/s?k=phone")   → true
matches("/s?k=*", "amazon.com/s?k=laptop")  → false  ← KDoc 承诺的模式永远匹配不上
matches("/*",     "amazon.com/dp/X")        → false  ← '*' 只吃单段
```

（`/*` 的成因：`"/*".trim('/').split('/')` = `["*"]`，而 `"/dp/X"` = `["dp","X"]`，
段数不等 → 直接 false。）

真正当 key 的是 `extractDomain`（`KnowledgeStore.kt:327` → 目录名），而它：
保留 host 大小写、丢端口、IDN 时 `host == null` → **回退返回整条原始 URL**（会成为目录名）；
`"../x"` 这类输入还能让 `resolve` 走出根目录。
后果是**同一站点写进两个目录 → 静默 cache miss**，或 pattern 过粗（`/s`、`/*`、`/dp/*`）
造成**命中错误知识**。`KnowledgeStore.kt:331-335` 的 Level-1 更是完全绕过 URL，
只按 (domain, intent) 返回事实。

#### M7. 同一页写出两个 pattern，最后写入者胜出

`/dp/B0CXJ1NT4B` → `/dp/*`，但 `/dp/B0CXJ1NT4B/ref=sr_1_1?k=laptop&qid=1`
→ `/dp/B0CXJ1NT4B/*`（`ref=` 段被"含数字且长度 > 4"的启发式当成了 ID）。
pattern 会被覆盖写（`KnowledgeStore.kt:246`），因此不稳定。

#### M8. SQL 入口的转义 / 校验不对称 `[读码]`

`SwarmController.kt:100,114-115` 对 payload 做 SQL 字面量转义
（`escapeSqlStringLiteral`），而 `ScrapeController.kt:56-57` 直接把原始 payload
拼进 `load_and_select('$payload', ':root')`。两者都只跑 `checkSql` 语法检查，
`isStandard` 要到异步任务里才发生 —— 调用方拿到 UUID 之后才失败。

### 🟢 低

| # | 位置 | 问题 |
|---|---|---|
| L1 | `AbstractPulsarSession.kt:869` | `link.substringBeforeLast("#")`：`http://h/p#a#b` 会留下 `#a`；同功能在 `CrawlSupport.kt:622` 用的是 `substringBefore('#')` |
| L2 | `NormURL.kt:131` | `createNil` 用 `LoadOptions.DEFAULT`，调用方的 args / eventHandlers 全丢（当前被 `LoadComponent.kt:293,335` 的 nil 短路兜住） |
| L3 | `AbstractBrowser4SQLContext.kt:74-77` | 重建 `NormURL` 时丢 `detail`；用 `URI.create(spec).toURL()` 二次解析且在 try 之外；产生第 4 种 URL 拼写 |
| L4 | `URLUtils.removeRedundantSlashes`（基础库） | 能修 `/./`、`a//b`，但**没有**接进主干 `normalize`，能力被浪费 |
| L5 | `URLUtils.normalize` | 空 query `?` 被保留为独立 key |
| L6 | `MCPToolController.kt:604-605` | 读了 `open_session` 的 `url` 参数然后丢弃 |

---

## 四、测试缺口

1. **`CombinedUrlNormalizer` 完全没有针对 `-noNorm` / `-ignoreUrlQuery` 经内联 args 传入的测试**
   （M1 正藏在这里）。`CombinedScopedUrlNormalizerTest` 只测了 `priority` 的 args 覆盖 ——
   恰好是唯一用对 `finalOptions` 的字段。
2. **`isStandard` 与 `normalizeOrNull` 的一致性无测试**（实测 9/23 分歧）。
3. **无测试钉住 H2 的代价**：`CrawlSupportTest.kt:145-164` 只钉了"合并"这一侧，
   没有一条断言"`ignoreUrlQuery=false` 时两个不同 query 的 URL 必须保持不同"。
   缺这一条，任何"合并"回归都无人发现。
4. **空格截断被反向钉死**：`CombinedScopedUrlNormalizerTest.kt:150-163` 断言
   `http://example.com/with spaces-not-supported` → `http://example.com/with`。
   该断言把"静默截断"固化为期望值。
5. `CrawlServiceTest` 只覆盖空白 seed，无"非法 seed 被拒"用例（M4）。
6. 无测试覆盖 `browser_navigate` 的 url 参数是否应保持原样（S1）。
7. 经验层：无幂等性、百分号编码（`%2F` / `%20` / `%26`）、host 大小写、非默认端口、
   IDN、参数大小写的测试；`UrlNormalizerTest.kt:120` 钉的是**生产从不使用的调用形状**
   （传 path-only 而非 `host/path`）。

---

## 五、待决策的方向性问题

1. **`URLUtils.normalize` 要不要成为"规范形式"？**
   若 page store / cache / ledger 都指望它做 key，则 host 大小写、默认端口、`/./`、
   尾斜杠、query 顺序这 5 项必须补上（实测 9/10 配对失败）。
   若它只是"剥 args 与 fragment"，则应把 KDoc 从 "Normalize a url spec" 改掉，
   并把去重责任明确下放到各层。当前困境正是因为它两者都像。
2. **`normalizeForVisit` 无条件剥 query 是有意为之吗？**
   代码注释与测试都说"是"。但这样一来 `-ignoreUrlQuery` 在 crawl 去重层面就没有意义，
   且分页站点会被静默限制在第一页。若确认有意，应在 `crawl.md` 与 `help crawl`
   明说"crawl 的去重身份不含 query，`?page=N` 只抓第一页"。

---

## 附：审查方法与可复现性

行为断言基于**实际依赖的 `pulsar-common-4.11.21.jar`**；引注行号对照的
源码 checkout 为 `D:\workspace\Browser4\browser4base`（`4.11.23-SNAPSHOT`，逻辑与 jar 实测一致）。
验证用的探针脚本为临时文件，已在审查结束后清理；需要复跑时可重新生成。
