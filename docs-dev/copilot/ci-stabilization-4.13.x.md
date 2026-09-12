# 4.13.x CI 稳定化排查报告

日期：2026-09-12 ｜ 分支：`4.13.x` ｜ 目标：让 tag CI **可预期地**全绿，而不是每轮暴露一个潜伏失败；
把"CI 为什么红/绿"从猜测变成可复现的本地证据。

**结论摘要**

1. 本地按 CI 的**完全相同**的开关跑一遍全量：**32 个模块 / 2504 个用例 / 3 个失败 / 0 个错误**，
   失败**全部集中在 1 个类** —— `browser4-rest-tests` 的 `CrawlFixtureMetadataTest`（`IntegrationTest`）。
2. 该类**不是稳定失败**：同一提交在 CI（4 核 Linux）连续两轮 4/0/0 通过（171 s、215 s），
   本地**单类隔离**复跑也通过（4/0/0，187.5 s），只有**在整模块/全量同一个 JVM 里**跑才失败
   （4 个用例 3 个断言失败，107 s，10 页只爬到 8 页）—— 典型的**时序/相互干扰**型 flaky，
   属既有集成测试的暴露面，不是本次改动引入的回归。
3. 根因已定位并开 issue：抓取页被快照 origin 守卫拒绝后**静默丢失**，且任务报"completed"后
   仍在后台提交链接 → platonai/Browser4#592。
4. 本轮**修掉 2 个 CI 判定可信度缺口**（超时可被"零失败"洗白、一轮只暴露一个失败模块），
   并**更正了排除清单的误判**（见 §6）。
5. 当前 CI 状态：`v4.13.18-ci.3` CI/CD Pipeline **success**；加固后的 **`v4.13.18-ci.4` success**
   （2047 个用例、0 失败，`CrawlFixtureMetadataTest` 第三轮连续通过：207.2 s），同轮
   Cross-Platform Smoke Test 也是 success。

---

## 1. CI 到底跑什么（两个门禁，范围不同）

| 门禁 | Workflow | Maven `excluded_groups` | 定位 |
|---|---|---|---|
| PR Quality Gate | `.github/workflows/pr.yml` | `ManualOnly,RequiresAI,E2E,E2ETest,Slow,Heavy,HeavyTest,Integration,IntegrationTest,RequiresServer,RequiresBrowser,RequiresDocker,TestInfraCheck` | 只跑快速单测（`run_pulsar_tests: 'false'`） |
| CI/CD Pipeline（main + release tag） | `.github/workflows/ci.yml` | `ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck` | 额外跑需要 Chrome / Docker / 已启动应用的集成与基础设施测试 |

主 CI 的实际命令（从 run 日志的 `MAVEN_CMD_LINE_ARGS` 抄录）：

```bash
./mvnw test \
  -Pall-main-modules,all-test-modules \
  -Dsurefire.excludes=**integration** \
  -Dsurefire.excludedGroups=ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck \
  -DrunITs=true -B
```

外层 `timeout 35*60`（`.github/actions/run-tests/action.yml`），随后两步决定成败：

* `Test Summary`：累加所有 `**/TEST-*.xml` 的 `tests/failures/errors/skipped`；
* `Reconcile Test Status`：**只有 surefire XML 中失败数为 0 时才判 success**。

## 2. 判定可信度的三个缺口

### 2.1 超时被杀 ⇒ 没跑的模块不算失败 ⇒ 报 success ✅本轮已修

`timeout` 命中后 Maven 被杀，**后面的模块根本没跑**，自然没有 `TEST-*.xml`；`failed_count=0`
⇒ `Reconcile Test Status` 判 `success`。CI 可以在"只跑了一半模块"的情况下变绿。

现在：`Run Tests` 记录 Maven 退出码并区分 `status=timeout`；`Reconcile Test Status` 在
`exit code == 124` 或 `status == timeout` 时**一律判 failed**，不再看失败计数。
（保留了原有宽容语义：非 124 的非零退出 + 零失败仍判 success，避免插件噪声误杀。）

### 2.2 一轮 CI 只暴露一个失败模块 ✅本轮已修

Maven 默认在第一个失败模块停止（CI 没有 `-fae` / `-Dmaven.test.failure.ignore`），
这就是 `ci.1 → ci.2 → ci.3` 逐轮暴露一个失败的机制来源。
现在命令固定追加 `--fail-at-end`：一轮 CI 就列出所有失败模块（最终状态仍由 surefire XML 汇总决定）。

### 2.3 "没跑"与"跑了且通过"仍无法区分（未修，建议）

`Reconcile Test Status` 只统计**存在**的 XML。建议后续加一步：对比 reactor 模块清单
（`mvn -q help:evaluate`/`-Dmaven.reactor...` 或直接对比 `Building <module>` 日志）与产出
`TEST-*.xml` 的模块清单，缺失即失败。若再叠加 2.1 的修复，可以保证"绿 = 全跑完且零失败"。

## 3. 本分支已修掉的 CI 阻塞

| 失败点 | 性质 | 修复提交 | 对应 CI |
|---|---|---|---|
| `ExtensionWebSocketHandlerTest`（browser4-rest，9 用例） | 测试过期：handler 早已读 `handshakeHeaders`、`onExtensionConnected` 早有第三个参数，测试没跟上（自 `8eabd1acf2` 起一直红） | `fc4b6f9ddf` | ci.1 前 |
| `CrawlFixtureMetadataTest`（browser4-rest-tests） | 测试侧缺陷：客户端 Jackson 缺 Kotlin module，轮询永远读到默认 `CREATED`（4.14 修过，未回灌 4.13.x） | `cae4042735` | ci.1 红 |
| `PulsarSessionTests.testLoadLocalFile`（pulsar-it-tests） | **真缺陷**：快照 origin 守卫不认 `file://` 翻译（无重定向、无 main request），本地文件抓取被拒 | `4d0af69110` | ci.2 红 |
| （以上全部） | — | — | **ci.3 全绿 ✅** |
| **CI 判定加固 + 文档**（见 §2、§7） | 提交 `efc650490a` | — | **ci.4 全绿 ✅**（`Total 2047 / Failed 0 / Passed 1991 / Skipped 56`，26m13s；Cross-Platform Smoke Test 同轮 success） |
| 排除列表语义注释 | 提交 `39779e079c` | — | ci.5 红 ❌（见 §3.1） |

### 3.1 ci.5 的 flaky 失败：`TestLoadResources.testLoadResource`

`v4.13.18-ci.5`（提交 `07b6019ace`，相对 ci.4 **只多了 YAML 注释与文档**）红了，
失败点是 `browser4-tests/pulsar-it-tests` 的 `ai.platon.pulsar.browser.TestLoadResources`：

```
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 1, Time elapsed: 63.16 s <<< FAILURE! -- in ai.platon.pulsar.browser.TestLoadResources
[ERROR] ai.platon.pulsar.browser.TestLoadResources.testLoadResource(Continuation) -- Time elapsed: 61.72 s <<< FAILURE!
org.opentest4j.AssertionFailedError: http://127.0.0.1:32769/json
	at ai.platon.pulsar.browser.TestLoadResources.testLoadResource$suspendImpl(TestLoadResources.kt:44)
```

* 断言内容是 `assertTrue(resourceUrl) { page.protocolStatus.isSuccess }`，失败的是第 2 个资源
  `$baseURL/json`（`baseURL = http://127.0.0.1:$port`，`port` 是本测试 Spring 上下文
  `RANDOM_PORT` 的随机端口，见 `MockSiteAccess.kt:48,83`）；
* 该用例自身耗时 61.72 s（本地同一用例 14.5 s）——典型的"重试到超时"形态；
* **分类：环境敏感 / flaky（非回归）**。依据：ci.3、ci.4 同一用例通过，本地全量也通过
  （`Tests run: 3, Failures: 0, Errors: 0, Skipped: 1`，14.50 s），而 ci.5 与 ci.4 的
  代码差异只有工作流注释和文档，不可能影响该测试。
* 处置：`monitor-ci.ps1` 已自动落 coworker 任务
  `coworker/tasks/main/2working/fix-ci-yml-tag-failure.md`（提取到的失败类
  `FAILED_LIST="ai.platon.pulsar.browser.TestLoadResources"` 明确），修复由该任务跟进；
  本轮报告记录证据与分类，修复落地后再补一轮 CI 验证。
* 顺带发现：`monitor-ci.ps1` 的错误提取抓的是 `Check Test Status`（汇报步骤）而不是真正的
  `[ERROR] ... FAILURE` 行，生成的任务正文里前 3 个 block 都是汇报脚本；建议后续改为优先提取
  `FAILED_LIST=` / `[ERROR] Tests run: ... Failures: [1-9]` / `<<< FAILURE!` 行（见 §8.5）。

## 4. 本地"一次跑全"的复现命令

```powershell
./mvnw.cmd -o -B "-Pall-main-modules,all-test-modules" `
  "-Dsurefire.excludes=**integration**" `
  "-Dsurefire.excludedGroups=ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck" `
  -DrunITs=true "-Dmaven.test.failure.ignore=true" test
```

* `-Dmaven.test.failure.ignore=true`：不在第一个失败模块停下，一轮列出全部失败；
* PowerShell 中 `-P...` 必须整体加引号（`,` 会被解析成参数数组）；
* 约 23 分钟（本地 20 核；CI 约 26 分钟），日志已加进 `docs/TESTING.md`。

## 5. 本地全量枚举结果（32 模块 / 2504 用例 / 3 失败 / 0 错误 / 51 跳过）

日志：`.test-sessions/ci-scope-local-175250.log`（2.8 MB）。

| 模块 | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| Browser4 Agentic | 957 | 0 | 0 | 1 |
| Browser4 Skeleton | 393 | 0 | 0 | 3 |
| Browser4 Rest | 331 | 0 | 0 | 0 |
| Browser4 Browser | 266 | 0 | 0 | 0 |
| Browser4 Media | 88 | 0 | 0 | 3 |
| Browser4 Protocol | 80 | 0 | 0 | 2 |
| Pulsar IT Tests | 79 | 0 | 0 | 35 |
| Browser4 Images | 70 | 0 | 0 | 0 |
| Pulsar E2E Tests | 62 | 0 | 0 | 7 |
| Browser4 Agent Tools | 42 | 0 | 0 | 0 |
| Browser4 Boot | 31 | 0 | 0 | 0 |
| Browser4 Common | 30 | 0 | 0 | 0 |
| Browser4 PPTX | 27 | 0 | 0 | 0 |
| **Browser4 Rest Tests** | **24** | **3** | 0 | 0 |
| Browser4 Markdown | 23 | 0 | 0 | 0 |
| Browser4 Parse | 1 | 0 | 0 | 0 |
| 其余 16 个模块（Dependencies、PDK、Core 等聚合/无测试模块） | 0 | 0 | 0 | 0 |
| **合计** | **2504** | **3** | **0** | **51** |

### 唯一失败点：`CrawlFixtureMetadataTest`（`browser4-rest-tests`）

`Tests run: 4, Failures: 3, Errors: 0, Time elapsed: 107.0 s`

| 用例 | 断言 |
|---|---|
| `testDepth2CrawlRecordsTitlesPerUrl` | `expected 10 pages (hub + 9 products), got 8` |
| `testReadonlyRefreshCrawlVerifiesFreshness` | `expected 10 pages, got 8` |
| `testReadonlyCrawlSurfacesServedOrFresh` | `title for .../product/1.html` 期望 `<Widget Alpha — $10.00>`，实得 `<null>` |

处置：**判定为既有集成测试的负载敏感暴露面，非本轮回归** —— 同一提交在 CI 上连续两轮通过：

| 运行 | 结果 | 耗时 |
|---|---|---|
| `v4.13.18-ci.2`（run 34678560474，4 核 Linux） | `Tests run: 4, Failures: 0, Errors: 0` | 214.6 s |
| `v4.13.18-ci.3`（run 34680803354，4 核 Linux） | `Tests run: 4, Failures: 0, Errors: 0` | 171.2 s |
| 本地全量 CI 范围（同 JVM 依次跑 8 个类） | `Tests run: 4, Failures: 3` | 107.0 s |
| 本地单类隔离（`-Dtest=` + 放开 group 过滤） | `Tests run: 4, Failures: 0, Errors: 0` | 187.5 s |

即：**全量跑会失败、隔离跑就通过** —— flaky 的触发条件是同一模块内多个测试类共用浏览器/模拟
服务器的时序，而不是机器核数本身（隔离跑反而更慢：187.5 s vs 107 s）。

根因（详见 [issue #592](https://github.com/platonai/Browser4/issues/592)）：

* 日志里 `Tab origin mismatch: refusing to capture 'product/2.html' for fetch 'product/7.html' ...`
  说明复用的 tab 被并发 fetch 交叉使用，守卫**正确地**拒绝把别人的文档记到本 URL 上；
* 但拒绝的后果是这一页**从结果里消失**：链接只提交了 `3 (depth 1) + 2 + 2 (depth 2)`，正好少 2 页，
  而 `CrawlResponse.status = OK`，没有"失败页"字段可查；
* 任务终态也不等于工作结束：`18:00:58.749 Crawl task 69f2b470 completed: 8 pages`，之后
  `18:03:30.261 ... submitted 2 links at depth 2`（+2m31s），与下一次 crawl 重叠。

### 5.1 历史红点在本分支的逐个复核（同一份本地日志）

| 曾经在 tag/分支 CI 上红的类 | 本次本地全量结果 |
|---|---|
| `ExtensionWebSocketHandlerTest`（browser4-rest） | `Tests run: 9, Failures: 0` ✅ |
| `PulsarSessionTests`（含 ci.2 的 `testLoadLocalFile`） | `Tests run: 4, Failures: 0, Skipped: 0` ✅ |
| `HTMLSnapshotToolExecutorTest`（browser4-rest） | `Tests run: 18, Failures: 0` ✅ |
| `AgenticContextTest` / `AgentFileSystemTest` / `AgentShellTest` / `AgentEventBusTest` / `RobustBrowserAgentTest` | 3 / 44 / 52 / 10 / 1，全部 0 失败 ✅（4.14 分支上曾红的 `browser4-agentic` 系列在本分支全绿） |
| `CrawlFixtureMetadataTest` | ❌ 唯一失败点，见上 |

## 6. tag / 排除清单核对（更正早期判断）

`AGENTS.md` 原文只写"CI 排除 `Slow`/`Heavy`/`Integration`/`E2E`/`SDK`/`Requires*`/`ManualOnly`"，
把 **PR 门禁**的范围当成了全部 CI，因此看起来像"排除列表漂移"。核对两个 workflow 后确认：
**主 CI 跑集成测试是设计意图**（`CrawlFixtureMetadataTest` 的类注释自己写着
*"Tagged [IntegrationTest] so it runs in main CI + nightly (not PR CI)"*）。

真正的偏差只有三处：

| 项 | 现状 | 处理 |
|---|---|---|
| `AGENTS.md` 的 CI 描述 | 未区分两个门禁，且声称排除 `SDK` | ✅已改写为两门禁表格 + 指向本报告 |
| `SDK` tag | 两个门禁都不排除，但**仓库中没有任何 `@Tag("SDK")`**（`docs/TESTING.md` 的 `-DrunSDKTests=true` 也未接线） | 文档标注为"当前无用例"，暂不改行为 |
| `Heavy` vs `HeavyTest` | PR 门禁排除 `Heavy`，主 CI 只排除 `HeavyTest` | 保留现状（主 CI 有意跑重活），已在 `AGENTS.md` 写明差异 |

仓库中活跃 tag 与两门禁的排除情况（全仓 `src/test` 统计，2026-09-12）：
E2ETest 16、Slow 13、ManualOnly 9、Unit 7、Fast 7、observability 6、BatchTestFailed 4、
TestInfraCheck 4、RequiresAI 3、IntegrationTest 3、Integration 2、HeavyTest 2、Heavy 2、
mcp 2、skills 2、RequiresBrowser 1、RequiresServer 1。

### 6.1 `-Dsurefire.excludedGroups=` 是替换而非追加（本轮新发现，值得记住）

root `pom.xml` 的默认值是"排除所有非 Fast"：

```xml
<surefire.excludedGroups>
    Slow,Heavy,RequiresServer,RequiresBrowser,RequiresAI,RequiresDocker,
    Integration,E2E,ManualOnly,TestInfraCheck,IntegrationTest,E2ETest,HeavyTest
</surefire.excludedGroups>
```

命令行一旦传入 `-Dsurefire.excludedGroups=...` 就**整体覆盖**这个默认值。所以：

* PR 门禁把 13 个 tag 全列了一遍 —— 等价于默认行为，正确；
* 主 CI 只列 `ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck` —— 等于**故意放开**
  `Integration`/`IntegrationTest`/`Heavy`/`Requires*`，这就是 `CrawlFixtureMetadataTest`
  出现在主 CI 里的机制；
* 本地排查时如果只写 `-Dtest=<类名>` 而不放开 group，被选中的 `IntegrationTest` 类会被默认
  `excludedGroups` 排除，surefire 给出 `Tests run: 0` **且退出码 0** —— 静默通过，极易误判为"通过"。
  复跑单类的完整命令见 `docs/TESTING.md`。

### 5.2 本地与 CI 的用例数口径差异（未完全解释，已列为核查项）

| 模块 | 本地全量 | CI ci.4 | 差 |
|---|---|---|---|
| browser4-agentic | 957 | 664 | −293 |
| browser4-rest | 331 | 244 | −87 |
| browser4-browser | 266 | 223 | −43 |
| browser4-skeleton | 393 | 359 | −34 |
| 其余 12 个有测试的模块 | 557 | 557 | 0 |
| **合计** | **2504** | **2047** | **−457** |

两边失败数都是 0，不影响本轮结论；但"本地跑得到、CI 跑不到"本身是一类潜在假绿。
已排除"整类没跑"是主因：`browser4-agentic` 在 CI 的 surefire 报告里只缺 2 个仓库中存在的类
（`AgentStateManagerPersistenceTest`、`Browser4MCPServerE2ETest`），撑不起 −293。
建议按 §8.2 的模块/类覆盖对比把口径钉死（例如 JDK 17 vs GraalVM 25、`@Nested` 计数方式、
平台条件裁剪）。

## 7. 本轮改动

| 文件 | 改动 | 验证 |
|---|---|---|
| `.github/actions/run-tests/action.yml` | 记录 Maven 退出码；`status=timeout`；`Reconcile Test Status` 对 124/timeout 一律判 failed；命令追加 `--fail-at-end`；新增输出 `run_exit_code` | YAML 解析通过（PyYAML）；4 个 `run:` 步骤 `bash -n` 全通过；reconcile 逻辑 6 个场景模拟全通过（124+0失败→failed、timeout+0失败→failed、干净→success、3失败→failed、非零+0失败→success、空输入→success） |
| `AGENTS.md` | CI 段落改写为"PR 门禁 / 主 CI 门禁"对照表 | 纯文档 |
| `docs/TESTING.md` | 新增「本地复现 CI 测试范围（一次跑全）」小节：Windows/Linux 命令、`-Dsurefire.excludedGroups=` 替换语义、复跑单模块/单类（含 group 过滤）的完整命令 | 单类命令实测跑出 4 个用例并通过（见 §5） |
| `docs-dev/copilot/ci-stabilization-4.13.x.md` | 本报告 | — |
| platonai/Browser4#592 | 抓取页静默丢失 + 任务终态后仍在后台工作 | issue |

> 未改动任何产品代码：本轮的 CI 红点来自测试侧缺陷（已由 coworker 修复）与负载敏感的既有测试，
> 产品侧问题按流程开 issue 跟踪。

## 8. 遗留风险与后续动作

1. **#592**（crawl 静默丢页 / 终态后仍在工作）—— 建议按 issue 里的方案修：守卫拒绝改为"换新
   driver/tab 重试"而不是丢页；`CrawlResponse` 暴露失败页清单；任务终态与 crawl scope 的完成绑定；
   补一个高并发下的回归测试（例如断言 `pages.size == linksDiscovered + seeds`）。
2. **模块覆盖对比**（§2.3）—— 建议在 `Reconcile Test Status` 前加一步"reactor 模块 vs 有 XML 的模块"校验。
3. `CrawlFixtureMetadataTest` 在 CI 上仍需 171–215 s，是主 CI 里最慢的单类之一；若后续把它移出主 CI，
   请同步 `AGENTS.md` 与本报告的排除清单。
4. 本地全量自检约 23 分钟，建议在改动跨模块/序列化/Spring 装配时作为 tag 前的预检（见 `docs/TESTING.md`）。

---

## 9. ci.5 的第 2 个 tag 红点：驱动池在资源守卫拒绝后空等满 60 s（已修，本轮唯一的产品代码改动）

`v4.13.18-ci.5`（run 34694569292，1985 用例）只有 1 个失败：
`ai.platon.pulsar.browser.TestLoadResources.testLoadResource`，
断言 `assertTrue(resourceUrl) { page.protocolStatus.isSuccess }`（第 2 个 URL `/json`）。

**定性：不是回归，是负载敏感的既有缺陷。** ci.4（success）与 ci.5 之间只差一个纯文档提交
`07b6019ace`，没有任何产品代码改动 —— 失败的是"同一提交在不同负载下"的确定性机制，
不是随机 flake。

### 9.1 机制（由失败 run 自身日志反推）

1. `/json` 落在**新创建的临时隐私上下文**上，其驱动池没有 standby driver；
   创建 driver 的唯一一次尝试被资源守卫拒绝：`AppSystemInfo.isSystemOverCriticalLoad`
   被瞬时 CPU 负载顶到 true（阈值 0.85，`/proc/stat` 全机口径，共享 runner 上极易触发）。
2. `LoadingWebDriverPool.shouldCreateWebDriver()` 的**"系统过载"分支当时没有任何日志**，
   这一步在日志里完全不可见。
3. 随后 `statefulDriverPool.poll(60 s)` 整段阻塞：日志从 `12:59:31.405` 到 `13:00:31.414`
   没有任何输出（整整 60.009 s），期间**不重新评估守卫**、也不重试创建。
4. 60 s 到点返回 null → `WebDriverPoolExhaustedException`
   （`active: 0, standby: 0, waiting: 0, working: 0, slots: 50`）→ `FetchResult.crawlRetry` →
   status 1601 → `protocolStatus.isSuccess == false` → 断言失败。
5. ci.4 同一测试通过，只是因为那次 fetch 恰好命中一个已有 standby driver 的上下文（7 ms 完成）。

### 9.2 修复（`LoadingWebDriverPool`，最小改动）

| 改动 | 作用 |
|---|---|
| `pollWebDriver` → 新增 `pollDriverInSlices`：按 `POLLING_SLICE = 500 ms` 分片等待，**每片重新调用 `resourceSafeCreateDriverIfNecessary`** | 守卫的拒绝是瞬时的（尖峰过去即可创建）；分片让等待方在负载恢复后立刻拿到 driver，而不是空等超时后抛异常 |
| 每片前检查 `isActive`，池被 retire/close 时立即返回 | 不再对空池空等整个超时（60 s"假死"） |
| `shouldCreateWebDriver()` 过载分支新增节流 INFO 日志（`ThrottlingLogger`，TTL 1 min） | 补上缺失的诊断信号；消息保持**逐字稳定**（限流键是**格式化后**的消息，含变量则永不触发限流）；变量细节降到 debug 级 |
| KDoc 说明分片的两个目的：重评估守卫、周期性释放 `ConcurrentStatefulDriverPool` 的监视器 | `ConcurrentStatefulDriverPool.poll` 仍是 `@Synchronized`（等待期间持锁），分片把持锁时长限制在 ≤500 ms；本轮**不改**其签名 |

### 9.3 验证（本地，纯 mock，无浏览器）

新增 `browser4-core/browser4-protocol/src/test/.../driver/LoadingWebDriverPoolTest.kt`（2 用例）：

- `testPollCreatesDriverWhenTheResourceGuardAllowsItAgain`：把守卫调成必然拒绝
  （`CRITICAL_CPU_THRESHOLD = -1.0`、`CRITICAL_MEMORY_THRESHOLD_MIB = 1.0`），1 s 后放行 →
  修复后 `poll` 在 **1.13 s** 返回 driver（`pool.numCreated == 1`）。
  **对照实验**：把 `pollDriverInSlices` 临时还原成"创建一次 + 整段阻塞"，同一用例
  **37.99 s 后**以 `WebDriverPoolExhaustedException` 失败 —— 与 CI 的失败签名一致。
- `testPollFailsFastWhenThePoolIsRetired`：`retire()` 后 `poll` 立即失败（修复前会等满超时）。
- 用例在系统**真的**处于临界负载（磁盘剩 <10 GiB 等）的机器上以 JUnit assumption 跳过，
  不会误报。
- 全模块：`./mvnw -ntp -o -pl browser4-core/browser4-protocol test`
  → `Tests run: 82, Failures: 0, Errors: 0, Skipped: 2`，BUILD SUCCESS。
