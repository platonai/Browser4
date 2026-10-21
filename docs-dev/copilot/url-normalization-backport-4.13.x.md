# URL Normalization backport 到 4.13.x

> 上游：[`docs-dev/copilot/url-normalization-fix-plan.md`](./url-normalization-fix-plan.md)（4.14.x 分支 `fix/url-normalization-consistency`）
> 上游基座：`browser4base` `main`，随 **v4.11.23**（fragment 修复）与 **v4.11.24**（canonical form）发布
> 本分支：Browser4 `4.13.x` → `fix/url-normalization-consistency`
> 执行日期：2026-10-04

---

## 一、为什么需要 backport

4.13.x 上**一条 URL 归一化提交都没有**：这条线只跟到了 `a353fa1be6`（把 `browser4-base` 升到 4.11.23），
而归一化的全部落地（契约修复、experience 层、REST 边界校验、canonical form 消费、crawl 身份键）
都发生在 4.14.x。两条线在 `0995a893ab`（2026-09-28）之后分叉，所以这是一次**逐提交移植**，不是合并。

原则与上游一致，本文件不重复论证：

> **Normalization is an identity, never an address.**
> 规范化后的 url 只做**存取身份**（page store / page cache / url pool / ledger / 经验库的 key）；
> **送给浏览器的地址保持原始形式**（`NormURL.href` / `NavigateEntry.userTypedUrl` / `fetchTask.href ?: fetchTask.url`）。

---

## 二、提交映射（上游 4.14.x → 本分支）

14 个上游提交全部移植，顺序不变（`docs(url)` 的几篇按原顺序落在 `docs-dev/copilot/`）：

| # | 上游 4.14.x | 本分支 | 说明 |
|---|---|---|---|
| 1 | `a8650d2bb4` | `e9d3d6c487` | 新增审查报告与修复计划 |
| 2 | `3edfb35cc5` | `749215926c` | `CombinedUrlNormalizer` 兑现自述契约、`ignoreUrlQuery` 只作用于发现链接层、NIL 日志 |
| 3 | `78b65c510b` | `460dd36500` | 记录已执行修复与验证结果 |
| 4 | `5234aa93dc` | `673bffc340` | 记录基础库 `URLUtilsTest` 43/43 |
| 5 | `1cfe6cbd3a` | `2a294c18f7` | experience 层三个函数统一拼写（`normalize` / `urlPatternOf` / `matches`） |
| 6 | `483c74621c` | `218b1b4f7d` | 记录 Phase B 修复与完整验证日志 |
| 7 | `191d9746b6` | `0fc7ca451c` | Scrape/Swarm 转义与校验收敛到 `ScrapeAPIUtils`；SQL context 的 `NormURL` 保留 `detail` |
| 8 | `7cc3112bcb` | `ebd6ae434a` | 消费 pulsar-common 4.11.23；`goto` 只校验不归一化；crawl/scrape 种子边界校验 |
| 9 | `be71dd007d` | `046f67e98b` | 记录基础库定义变更与 canonical form 范围 |
| 10 | `4acb66871a` | `d8a3c6c468` | 身份审计：`normalize()` 之外没有第二个 key |
| 11 | `b975603753` | `b838a78962` | 把不变量写在 `URLUtils` / `NormURL` / `NavigateEntry` 等生产点 |
| 12 | `690382218b` | `faca27ece7` | 升到 `pulsar 4.11.24`，修正两份过期断言 |
| 13 | `fe70df2637` | `4230741a61` | snapshot origin guard 不把重排后的 query 读成另一个文档 |
| 14 | `5b5d59f5ac` | `089e5b0b11` | crawl 队列键按引擎身份的 query 顺序 |
| — | — | `f868671d2d` | **仅本分支**：三处 4.13.x 适配（见下节） |

改动面：40 个文件、+2645 / −309（`git diff --stat 4.13.x HEAD`）。

---

## 三、与上游不同的三处（都在 `f868671d2d` 里）

1. **`ConversationService` 补 `logger`** —— 4.14.x 的该类早已有 `private val logger = getLogger(...)`（来自
   agent-memory 那批改动，不在本次移植范围），移植过来的 warn-and-fall-back 块引用它。缺了它 browser4-rest 编译不过。
2. **`SwarmControllerTest` 用 4.13.x 的构造方式** —— 这里是 `SwarmController(sessionManager, swarmService)` +
   mock service；4.14.x 的 `newController(...)` 与 `SwarmFacadeRegistry` facade 在这一线不存在。断言未改。
3. **`skills/browser4-cli/references/crawl.md` 压缩到 498 行** —— 上游那份是 512 行，超过 **M6 门禁**
   （`SKILL.md` / decision / procedure ≤ 500 物理行，`bin/skill-doc-lint.ps1`）。
   语义未删：只重新折行并合并冗余措辞。

另有一处**不属于本线**：`browser4-agentic/.../agentic/memory/PemKnowledgeProvider.kt` 在 4.13.x 根本不存在
（`ai.platon.pulsar.agentic.memory` 包是 4.14.x 新增），上游提交对它的 1 行改动（`urlPatternOf` 改调
`UrlNormalizer`）随之不适用。

---

## 四、随上游分支但**未**移植的部分（与本主题无关）

`fix/url-normalization-consistency` 分支上混着若干非 URL 提交，它们属于 4.14.x 的其它工作，本次没有带过来：

| 未移植 | 原因 |
|---|---|
| `fbb05de0b9` / `1d132db7bb` / `55f7124aa3` / `671cdb2311`（htmlsnapshot capture/read 分流） | htmlsnapshot 重构，与 URL 归一化正交 |
| `c79386b482` / `ad678f2d20`（`ignoreDOMFeatures` 作用域） | protocol 的 DOM 特征计算，独立缺陷 |
| `a7137f350f`（crawl checkpoint 终态可见性） | crawl 检查点，独立缺陷 |
| `09457f33d4`（0-byte 诊断）等 4.14.x 独有实现 | 4.14.x 专有，本线没有对应代码 |

因此本分支与 4.14.x 的差异只应落在这些点上。逐文件核对（`git rev-parse HEAD:<f>` 与 `5b5d59f5ac:<f>` 比 blob）：
本次移植的 40 个文件里 **22 个与上游逐字节一致**（含 `CombinedUrlNormalizer.kt`、`NormURL.kt`、
`UrlDocumentMatcher.kt`、`UrlNormalizer.kt`、`UrlNormalizerTest.kt`、`CrawlSupport` 相关断言等），
其余 18 个的差异全部来自上表这些 4.14.x 独有代码，加上第三节的三处适配 —— 已逐个确认没有漏掉的 URL 改动。

---

## 五、验证

| 步骤 | 命令 / 范围 | 结果 |
|---|---|---|
| 1 | `mvnw -DskipTests -pl browser4-core/browser4-skeleton,browser4-core/browser4-protocol,browser4-agent-tools,browser4-agentic,browser4-rest -am install` | ✅ BUILD SUCCESS（含 test-compile） |
| 2 | 依赖解析 `dependency:tree` | ✅ `pulsar-common` / `pulsar-browser` / `pulsar-ql-common` = **4.11.24** |
| 3 | 定向：19 个测试类（skeleton / protocol / agent-tools / agentic / rest） | ✅ **344 条，0 失败**（4 条 skip 为既有 `@Disabled`） |
| 4 | `browser4-core/browser4-skeleton` 全量快速套件 | ✅ **400 / 400**（1 skip） |
| 5 | `browser4-core/browser4-protocol` 全量快速套件 | ✅ **97 / 97**（2 skip） |
| 6 | `browser4-agent-tools` 全量快速套件 | ✅ **65 / 65** |
| 7 | `browser4-agentic` 全量快速套件 | ✅ **986 / 986**（1 skip） |
| 8 | `browser4-rest` 全量快速套件 | ✅ **531 / 531** |
| 9 | `bin/skill-doc-lint.ps1` | ✅ checked 37 files, 0 issue(s) |

合计 **2079** 条（第 4–8 步）全部通过。

> 4.13.x 的类数比 4.14.x 少（上游 agentic 1480 / rest 616）：本线没有 agent-memory 的那批测试。

---

## 六、升级到 4.11.24 带来的行为变化（与上游 §8.3 相同，这里再点一次）

- `normalize` 折叠更多等价拼写：scheme/host 大小写、默认端口、空路径 = `/`、`.`/`..` 段、unreserved 转义，
  以及**按策略**折叠的非根路径尾斜杠、重复分隔符、query 参数顺序（同名参数保持相对顺序）。
  **折叠只改 key，不改地址**。
- `isStandard(str) := normalizeOrNull(str) != null`，且 `file:` 从 false 变 true ——
  `DomUtils`、`JsoupParser`、`StatefulPageVisitor`、crawl/scrape 的种子门控都会接受 `file://`。
  上游决定**不加**"必须能联网抓取"这道闸，本线沿用同一决定。
- `/api/crawl`、`/api/x/submit`、`/api/swarm/submit` 对非法种子返回 **400 `Malformed url: <...>`**
  （此前会发 uuid 再在异步任务里失败，或被 fetcher 悄悄换成搜索引擎 URL）。

---

## 七、后续

1. backport 分支 `fix/url-normalization-consistency` 待 review 后合入 `4.13.x`。
2. 若要继续对齐上游，下一批候选是第四节列出的四项（各自独立，建议分开提交）。
3. `browser4base` 的 `4.11.x` 维护分支**不含**本轮 url 提交（它们随 `main` 发版）。
   4.13.x 消费的是已发布制品，因此本线无需再动基座仓库。
