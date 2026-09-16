# CrawlService.kt 拆分与 crawl 分包（4.13.x）

> 目标：把 1887 行 / 95 KB 的"上帝文件"拆成职责单一的若干文件，并让 crawl 实现拥有自己的包，
> **不改变任何行为**。
> 关联：#592（丢失页面可见性）、`crawl-multitab-parallel-4.13.x.md`（采集并行化）。

## 1. 拆分前的问题

`browser4-rest/.../api/service/CrawlService.kt` 一个文件里同时住着：

* 6 个 DTO（`CrawlRequest` / `CrawlResponse` / `CrawlPageResult` / `CrawlRound` / `CrawlFailedPage` / `CrawlSeedStatus`）；
* 任务注册表（Caffeine 存储 + JSONL 持久化 + `Job` 取消 + TTL 清理 + 启动恢复）；
* 种子调度与进度发布：`submit()` 单方法 **375 行**，内部还嵌套 `fetchSeed` / `recordSeed` 两个局部函数；
* 三套抓取策略：`crawlDepth0` / `crawlDepth1` / `crawlDepthN`（后两者合计 **430 行**，各自内嵌 parse handler）；
* X-SQL 执行（`executeSqlQuery`，115 行）；
* 一批纯函数报告助手（loss note / readonly note / title fallback / 空出链诊断 / URL 归一化）。

同时 `api/service/` 目录是一个 14 文件的平铺堆，crawl 一家占了 6 个文件、约 2400 行，
却和 `ActService`（48 行）、`ExtractService`（41 行）这样的薄门面混在一起。

后果：任何一次 crawl 改动都要在 1900 行里定位；并发状态与纯字符串助手混在一起，纯函数无法单独测试；
`grep Crawl` 命中的目录层级看不出"这是一块独立子系统"。

## 2. 目录结构

### 2.1 crawl 收敛到自己的包

```
browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/
├── api/
│   ├── controller/                     REST 入口（CrawlController 等，不动）
│   └── service/
│       ├── crawl/                      ← 本次收纳：crawl 的全部实现
│       │   ├── CrawlService.kt          任务生命周期、种子调度、持久化
│       │   ├── CrawlModels.kt           请求/响应/报告模型（6 个 DTO）
│       │   ├── CrawlRoundRunner.kt      一轮抓取（depth 0/1/N）+ CrawlProgressSink
│       │   ├── CrawlSupport.kt          纯函数助手（URL 归一化、并发映射、报告文案）
│       │   ├── CrawlXSql.kt             页面 X-SQL 抽取
│       │   └── CrawlLedger.kt           每轮结算账本（原有文件，一并收纳）
│       ├── ActService.kt / ExtractService.kt / LoadService.kt / ConversationService.kt
│       ├── ScrapeService.kt / ScrapeHyperlinkFactory.kt
│       ├── SwarmService.kt
│       └── AsyncTaskCache.kt            跨服务共享的异步任务缓存
├── agent/tool/                         MCP 工具执行器（CrawlToolExecutor 等，不动）
├── config/                             （不动）
├── mcp/                                （不动）
└── session/                            （不动）
```

测试目录镜像同样的结构：

```
browser4-rest/src/test/kotlin/ai/platon/pulsar/rest/api/service/crawl/
├── CrawlServiceTest.kt          ├── CrawlServicePersistenceTest.kt   ├── CrawlLedgerTest.kt
├── CrawlParallelBudgetTest.kt   ├── CrawlResponseTest.kt             └── CrawlSupportTest.kt（本次新增）
└── CrawlSeedSchedulerTest.kt
```

包名 `ai.platon.pulsar.rest.api.service.crawl`；类名/成员名**一个没改**，因此：

* Spring 组件扫描不受影响（`@SpringBootApplication` 在 `ai.platon.pulsar.rest.ApiApplication`，向下覆盖子包）；
* 跨模块调用方只改了 `import`：`CrawlController`、`CrawlToolExecutor`、`CrawlToolMountConfiguration`、
  `browser4-tests/browser4-rest-tests` 的 `CrawlParallelTabsTest` / `CrawlFixtureMetadataTest`、
  `browser4-agent-tools` 的 `JsonlPersistence` KDoc 链接、以及 `CrawlXSql` 的 logger 名。

### 2.2 文件职责

| 文件 | 行数 | 职责 |
|---|---:|---|
| `crawl/CrawlModels.kt` | 158 | 请求 / 响应 / 报告模型，与执行机制完全解耦 |
| `crawl/CrawlService.kt` | 806 | `@Service`：任务注册表、持久化、生命周期、种子调度 |
| `CrawlTaskContext`（同文件私有类） | – | 单任务共享状态：预算、in-flight 峰值、按种子索引的 round/status、发布锁 |
| `crawl/CrawlRoundRunner.kt` | 790 | 一轮抓取：`crawlDepth0/1/N` + `extractOutLinks` + ledger 结算 + 抓取参数构造 |
| `CrawlProgressSink`（同文件接口） | – | 轮次向任务存储发布进度的唯一出口；`CrawlService` 用私有匿名对象实现 |
| `crawl/CrawlXSql.kt` | 137 | 页面 X-SQL 抽取（预加载 → 查询 → 列序归一化 → 空字段告警） |
| `crawl/CrawlSupport.kt` | 230 | 纯函数助手：URL 归一化、并发映射、title fallback、pattern 过滤、loss/readonly note、空出链诊断 |
| `crawl/CrawlLedger.kt` | 266 | 每轮"提交 / 记录 / 丢失"结算（原有） |

`CrawlService.submit()` 现在只负责"建记录 → 校验种子 → 建上下文 → 起协程"；任务体被拆成
`runCrawlTask` / `markProcessing` / `collectSeeds` / `fetchSeedUnit` / `recordSeedProgress` /
`writeCompleted` / `writeCancelled` / `writeFailed`，每个都是原方法里一段**逐字搬运**的逻辑。

## 3. 成员迁移对照（便于 grep 旧引用）

| 原成员（`CrawlService`） | 现位置 |
|---|---|
| `crawlDepth0` / `crawlDepth1` / `crawlDepthN` | `crawl/CrawlRoundRunner.kt` |
| `extractOutLinks` / `settleFromLoaded` / `parseOptions` / `buildEffectiveArgs` / `buildArgsForDepth` / `extractDepth` | `crawl/CrawlRoundRunner.kt`（private） |
| `executeSqlQuery` → `executeCrawlSqlQuery` | `crawl/CrawlXSql.kt`（internal 顶层函数） |
| `normalizeForVisit` / `mapCrawlSeedsConcurrently` | `crawl/CrawlSupport.kt`（仍为 internal 顶层函数，签名不变） |
| `extractTitleFromHtml` / `matchesPattern` / `storeServeMarkers` / `buildReadonlyNote` / `buildLossNote` / `emptyOutLinksDiagnostic` | `crawl/CrawlSupport.kt`（`private` → `internal`） |
| `publishIncremental` | 仍是 `CrawlService` 私有方法，由 `CrawlProgressSink.publishPages` 调用 |
| 深度 1 找不到出链时写的那条 `taskStore.put(...)` | `CrawlProgressSink.publishDiagnostic`（同样只写内存、不落 JSONL） |
| `clearTerminal` / `purgeExpiredTasks` 里重复的 JSONL 重写 | `rewritePersistence()` |
| 常量 `MAX_FETCH_RETRIES` / `FETCH_RETRY_DELAY_MS` | `CrawlRoundRunner` 伴生对象 |
| 常量 `MIN_FETCH_TIME` / `MAX_REPORTED_FAILED_PAGES` | `crawl/CrawlSupport.kt`（文件私有） |
| 常量 `SEED_INTERVAL_MS` / `CRAWL_TASK_TIMEOUT_MS` / `DEFAULT_PARALLEL_TABS` / `MAX_PARALLEL_TABS` / `crawlPersistencePath()` | 仍留在 `CrawlService` 伴生对象（后三者是对外契约）。`CRAWL_TASK_TIMEOUT_MS` 后来改为公开常量 `DEFAULT_TASK_TIMEOUT_MS` + 可覆盖的 `taskTimeoutMillis`（轮次预算由它派生），见 `ci-stabilization-4.13.x.md` §17.2 |

## 4. "行为不变"的验证

1. **文案零丢失**：拆分前后逐条比对字符串字面量 —— 145 条（日志、诊断、错误信息）全部原样存在，0 条缺失。
2. **代码行集合比对**：非空非注释行做集合差；差异全部可解释 —— import 清理（`AgenticContexts` 未使用、`java.util.*` 拆细、`java.time.Instant` 改为 import）、接收者改写（`taskId` → `task.taskId`、`publishIncremental` → `progress.publishPages`、`executeSqlQuery` → `executeCrawlSqlQuery`）、以及新增的 sink/context 脚手架。
3. **旧包引用清零**：`grep -S ai.platon.pulsar.rest.api.service.Crawl`（区分大小写）→ 0 命中；`service.crawl.crawl` 双重改写 → 0 命中。
4. **编译**：`mvn -pl browser4-rest -am -DskipTests test-compile` → BUILD SUCCESS；仅有的 3 条 warning（两处 elvis、一处 `toInt()`）经比对为**原有代码原样搬运**，非新增。
5. **测试**：`mvn -pl browser4-rest -am test -Dtest='Crawl*Test'` → `Tests run: 73, Failures: 0, Errors: 0`，覆盖 `CrawlLedgerTest` / `CrawlParallelBudgetTest`（含 DTO 线格式往返）/ `CrawlResponseTest`（含持久化往返）/ `CrawlSeedSchedulerTest` / `CrawlServicePersistenceTest`（反射取 `persistence`，字段仍在 `CrawlService` 上）/ `CrawlServiceTest`（取消/超时状态机）。
   * 新增 `CrawlSupportTest`（12 例）：把拆分出的纯函数接缝钉住 —— URL 归一化（fragment/query 去重）、`<title>` 兜底、非法正则不得清空 crawl、loss note 截断但计数精确、readonly note 的"新鲜/来自存储 + 时长"两种措辞。
   * 跨模块：`mvn -pl browser4-tests/browser4-rest-tests -am -DrunRestTests=true -DskipTests test-compile` → BUILD SUCCESS（该模块只在 `tests-rest`/`all-test-modules` 等 profile 下才进 reactor，`-pl :browser4-rest-tests` 单独选会报 "Could not find the selected project"）。
   * PR 门禁同口径（`-Dsurefire.excludedGroups=ManualOnly,RequiresAI,E2E,…,RequiresBrowser,…` + `-Dsurefire.excludes=**integration**`）跑 `browser4-rest`：`Tests run: 376, Failures: 0, Errors: 0`，BUILD SUCCESS。
6. **Spring/序列化面**：Spring 组件扫描（`@SpringBootApplication` 位于 `ai.platon.pulsar.rest`，向下覆盖子包）与 bean 名（由简单类名派生，`StartupWarmerTest` 里的 `"crawlService"` 不变）都不受包移动影响；`pulsarObjectMapper` 未开启 default typing，落盘的 `crawl-tasks.jsonl` 不含 `@class` 类型标识，因此历史任务记录在新包下照样能读回。
7. **注意**：改包后 `target/` 里会残留旧包的 `Crawl*.class`，`-Dtest=Crawl*Test` 会把新旧两份测试类各跑一遍（合计 146）。发现后已删除残留并复跑，确认新包下就是 73 例。CI 是 clean build，不受影响；本地改包后建议 `mvn -pl <module> clean` 或手工清掉旧包目录。

## 5. 保留的在途改动

拆分时工作区已有未提交改动，全部**原样保留**：

* `crawlDepthN`：`PulsarSettings(profileMode = BrowserProfileMode.SEQUENTIAL)` + `createSession(settings = ...)`（替代 `PulsarSettings.withSequentialBrowsers().maxOpenTabs(8)`）；
* `selector.isNotBlank()`（替代 `!selector.isNullOrBlank()`）；
* `withTimeout(timeoutMs.milliseconds)`；
* 定时清理 `delay((5 * 60 * 1000L).milliseconds)`。

## 6. 有意未做

* 不改任何公开 API、线格式、日志文案、状态机与时序常量 —— 这是纯结构重构。
* 不把 `crawlDepth1` / `crawlDepthN` 再拆成两个文件：两者共享 `extractOutLinks`、`settleFromLoaded` 与抓取参数构造，再拆只会引入继承或重复。
* 本轮**只给 crawl 建包**：`api/service/` 其余 8 个文件保持平铺。理由是它们的引用面各不相同且价值有限 ——
  `SwarmService`（493 行）被 8 个文件引用，`ScrapeService`（266 行）被 5 个，`ScrapeHyperlinkFactory` 被 2 个，
  其余 `ActService`/`ExtractService`/`LoadService`/`ConversationService` 都是 40–120 行的薄门面，
  单独建目录收益不明显。若要继续收敛，建议 `scrape/`（`ScrapeService` + `ScrapeHyperlinkFactory`）与 `swarm/` 两个包，
  代价是约 13 处 import 改动（均在本模块内，编译期即可验证）。
* 不给 `CrawlRoundRunner` 的三个策略补单元测试：它们依赖真实 `PulsarSession`/浏览器，属于 E2E 范畴（`browser4-tests/browser4-rest-tests` 的 `CrawlParallelTabsTest` / `CrawlFixtureMetadataTest` 已覆盖）。
