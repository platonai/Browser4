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
5. 当前 CI 状态（本轮结束时）：**`v4.13.18-ci.6` CI/CD Pipeline success**（2049 用例 / 0 失败，
   `TestLoadResources` 4.5 s 通过、新增驱动池用例 2/0）+ 同轮 Cross-Platform Smoke Test success；
   此前 `ci.3`、`ci.4` 亦为 success，`ci.5` 的唯一红点已定位并修复（§3.1、§9）。

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
| **驱动池分片轮询修复**（§9，提交 `2671e1574d`）+ 报告 | 提交 `f3c1a6202c` | — | **ci.6 全绿 ✅**（`Total 2049 / Failed 0 / Passed 1993`，26m+；`TestLoadResources` 4.522 s 通过、`LoadingWebDriverPoolTest` 2/0；Cross-Platform Smoke Test 同轮 success） |

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
* 处置：`monitor-ci.ps1` 自动落了 coworker 任务 `fix-ci-yml-tag-failure.md`（提取到的失败类
  `FAILED_LIST="ai.platon.pulsar.browser.TestLoadResources"` 明确），已由提交 `2671e1574d` 修复
  ——机制反推与对照实验见 §9；本轮对修复做了**独立复核**（见 §7 末行），ci.6 完成端到端验证。
* **ci.6 端到端验证（决定性证据）**：`TestLoadResources` 变成 `Tests run: 3, Failures: 0, Errors: 0,
  Skipped: 1`，耗时从 63.16 s 降到 **4.522 s**；更关键的是 ci.6 日志里**同一条件再次出现**——
  14:44:21（正是该用例执行窗口内）打出
  `LoadingWebDriverPool - The system is over the critical load, will not create a new driver`，
  也就是说守卫那次确实又拒绝了创建，而修复后 `poll` 在负载恢复后立刻拿到 driver，不再空等 60 s。
  新增的 `LoadingWebDriverPoolTest` 在 CI 上 `Tests run: 2, Failures: 0`（3.015 s）。
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

## 7. 本轮改动

| 文件 | 改动 | 验证 |
|---|---|---|
| `.github/actions/run-tests/action.yml` | 记录 Maven 退出码；`status=timeout`；`Reconcile Test Status` 对 124/timeout 一律判 failed；命令追加 `--fail-at-end`；新增输出 `run_exit_code` | YAML 解析通过（PyYAML）；4 个 `run:` 步骤 `bash -n` 全通过；reconcile 逻辑 6 个场景模拟全通过（124+0失败→failed、timeout+0失败→failed、干净→success、3失败→failed、非零+0失败→success、空输入→success） |
| `AGENTS.md` | CI 段落改写为"PR 门禁 / 主 CI 门禁"对照表 | 纯文档 |
| `docs/TESTING.md` | 新增「本地复现 CI 测试范围（一次跑全）」小节：Windows/Linux 命令、`-Dsurefire.excludedGroups=` 替换语义、复跑单模块/单类（含 group 过滤）的完整命令 | 单类命令实测跑出 4 个用例并通过（见 §5） |
| `docs-dev/copilot/ci-stabilization-4.13.x.md` | 本报告 | — |
| platonai/Browser4#592 | 抓取页静默丢失 + 任务终态后仍在后台工作 | issue |
| coworker `2671e1574d`（见 §9） | `LoadingWebDriverPool.pollDriverInSlices`：等待按 500 ms 分片、每片重新评估资源守卫；池 retire/close 时立即返回；过载拒绝补节流日志；新增 `LoadingWebDriverPoolTest` | **本轮独立复核**：`-pl browser4-core/browser4-protocol,browser4-core/browser4-browser,browser4-rest -am`（13 模块 / 2132 用例 / 0 失败）——protocol 80→**82**（新增 2 用例，4.0 s 通过）、agentic 957、skeleton 393、browser 266、rest 331 全部 0 失败 |

> 产品代码改动只有 coworker 任务带来的 `2671e1574d`（驱动池分片轮询，见 §9），本轮已独立复核；
> 其余改动都集中在 CI 判定逻辑、测试文档与本报告。

## 8. 遗留风险与后续动作

1. **#592**（crawl 静默丢页 / 终态后仍在工作）—— 建议按 issue 里的方案修：守卫拒绝改为"换新
   driver/tab 重试"而不是丢页；`CrawlResponse` 暴露失败页清单；任务终态与 crawl scope 的完成绑定；
   补一个高并发下的回归测试（例如断言 `pages.size == linksDiscovered + seeds`）。
2. **模块覆盖对比**（§2.3）—— 建议在 `Reconcile Test Status` 前加一步"reactor 模块 vs 有 XML 的模块"校验。
3. `CrawlFixtureMetadataTest` 在 CI 上仍需 171–215 s，是主 CI 里最慢的单类之一；若后续把它移出主 CI，
   请同步 `AGENTS.md` 与本报告的排除清单。
4. 本地全量自检约 23 分钟，建议在改动跨模块/序列化/Spring 装配时作为 tag 前的预检（见 `docs/TESTING.md`）。
5. `bin/ci/monitor-ci.ps1` 的失败提取（§3.1）：Pass 1 只识别 Rust/Go 形态的失败行，Maven/surefire 的
   `<<< FAILURE! -- in <class>`、`[ERROR] <class>.<method> -- Time elapsed: ... <<< FAILURE!`、
   `FAILED_LIST="..."` 都不识别，于是自动任务里没有 `## Failing Tests` 段、正文被汇报步骤淹没。
   建议给 Pass 1 补这三条模式（`bin/ci/tests/monitor-ci.tests.ps1` 可直接加用例；`bin/release/monitor-release.ps1`
   有同名函数的副本，需同步）。本轮**只记录不改**，原因有二：一是避免在最终验证轮引入脚本改动；
   二是 `.github/workflows/ps1-tests.yml` **只在 `main` 的每日 cron 上跑**（不响应 push/tag），
   4.13.x 上的 `.ps1` 改动实际上拿不到 CI 覆盖，只能在本地跑 Pester（`pwsh bin/ci/tests/monitor-ci.tests.ps1`）。
6. `TestLoadResources.testLoadResource`（§3.1/§9）修复后仍需观察：它依赖 `/json` 这类非 HTML 资源的
   抓取，若 ci.6 再红，优先看 `Driver pool is exhausted` 与 `over the critical load` 两条日志。

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

### 9.4 独立复核（本轮排查方，非修复方）

同一提交在**更宽的模块面**上重跑（`-pl browser4-core/browser4-protocol,browser4-core/browser4-browser,browser4-rest -am`，
CI 同款开关：`-Dsurefire.excludes=**integration**` + 同款 `excludedGroups` + `-DrunITs=true`）：

| 模块 | 用例 | 失败 |
|---|---|---|
| Browser4 Protocol | 82（新增 2：`LoadingWebDriverPoolTest`，显示名 "LoadingWebDriverPool polling"，4.008 s） | 0 |
| Browser4 Agentic | 957 | 0 |
| Browser4 Skeleton | 393 | 0 |
| Browser4 Rest | 331 | 0 |
| Browser4 Browser | 266 | 0 |
| 其余 8 个模块（Common/Parse/Agent Tools/Boot 等） | 103 | 0 |
| **合计** | **2132** | **0** |

`Browser4 Protocol` 由 80 增至 82，其余模块计数与修复前完全一致（无副作用）；新增的诊断日志
`The system is over the critical load, will not create a new driver` 在日志中可见。
端到端由 ci.6（tag `v4.13.18-ci.6`，提交 `f3c1a6202c`）验证。

---

## 10. v4.13.18 发布流水线的唯一红点：npm 发布后"可见性验证"窗口太短（已修）

`release.yml`（run 34706653079，tag `v4.13.18`）整轮只有一个失败 job：
`Publish browser4-cli to npm`，失败步骤是 **`Verify npm package was published`**：

```
17:17:19 npm notice Your package is being processed and may take a few minutes to become available.
17:17:19 + browser4-cli@4.13.18
17:17:19 npm registry has not reported browser4-cli@4.13.18 yet (attempt 1/5); retrying in 10s...
17:17:50 npm registry has not reported browser4-cli@4.13.18 yet (attempt 4/5); retrying in 10s...
17:18:00 Unable to verify browser4-cli@4.13.18 on npm after publish
```

**定性：registry 侧的异步发布处理 + CI 验证窗口过短（基础设施/时序问题），不是回归，
也不是测试断言变化。** 决定性证据是**发布本身成功了**：`npm publish --provenance`
返回 0 并打印 `+ browser4-cli@4.13.18`；事后查询 registry 也确认版本真实存在
（`npm view browser4-cli@4.13.18 version` → `4.13.18`，`dist-tags.latest = 4.13.18`）。
失败的只是发布之后的可见性确认。

### 10.1 根因

* 该步骤只等 `5 × 10 s ≈ 50 s`（4 次 sleep + 5 次查询），而这次 registry 在 41 s 之后
  仍未把新版本暴露给 `npm view`；
* 同一次发布里 npm 打印了官方解释：
  `Your package is being processed and may take a few minutes to become available.`
  —— registry 已改为**异步发布处理**。该 notice 在 v4.13.14 / v4.13.15 / v4.13.16
  三次绿色 run 的发布步骤日志里**一次都没出现过**（逐 run grep 计数为 0），
  说明这是 registry 侧行为变化，不是本分支改动引入的；
* 历史窗口本来就贴着临界值：v4.13.15 的验证步骤耗时 21 s（重试过 1 次），
  v4.13.14 / v4.13.16 是 11 s / 10 s（首次即成功）；
* 代价不对称：这一步失败会让整个 job 失败，而 `publish-github-release` 的 `needs` 要求
  `publish-cli-npm.result == 'success'` —— 于是一次**已经成功**的 npm 发布把
  v4.13.18 的 GitHub Release 也一起挡掉了。

### 10.2 修复（单一实现，两个 workflow 共用）

| 文件 | 改动 |
|---|---|
| `cli/scripts/wait-for-npm-version.sh`（新） | 轮询 registry 直到**精确版本**可见：默认 600 s 预算 / 15 s 间隔；404（"还没发布"）安静重试，其它错误（网络/registry 抖动）记 WARN 后照样重试；单次 sleep 不超过剩余预算；超时后打印最后一次 registry 答复与手工复查命令，退出码 0/1 |
| `.github/workflows/release.yml` | `Verify npm package was published` 由内联 `5 × 10 s` 循环改为调用该脚本；`test-install-scripts` job 增加一步（Linux）跑它的单测 |
| `.github/workflows/release-cli.yml` | 同一段内联循环（逐字重复，只差 `steps.check` 前缀与 emoji）同样改为调用脚本 |
| `cli/scripts/README.md` | 记录脚本、单测，新增 "Verifying a publish landed" 一节 |

* 判定语义**没有放宽**：预算用尽仍然 `exit 1`；
* 只是把"registry 需要多久"从 50 s 提到 10 min（npm 自己说 "a few minutes"），
  并把以前被 `2>/dev/null` 丢掉的 npm 报错带进日志；
* 两个 workflow 之前是逐字重复的实现，这次收敛到同一个脚本，避免以后只修一边。

### 10.3 验证

* 新增 `cli/scripts/tests/wait-for-npm-version.tests.sh`（11 个用例，stub `npm`，不联网）
  → `All 11 tests passed`（约 20 s）；
* **对照实验**：把脚本改回"最多 5 次尝试"后重跑同一套用例 →
  只有 `keeps polling past the old 5-attempt window` 失败（`1 of 11 tests failed`），
  即该用例确实能区分新旧行为（改动已还原）；
* 用例不依赖可执行位：仓库里这些脚本一律以 `100644` 记录（Windows 上 checkout，
  git 不跟踪执行位，与 `install-browser4-cli.tests.sh` 相同），workflow 一律用
  `bash <路径>` 调用 —— 早期草稿里的 "is executable" 断言在 Linux runner 上必然失败，
  已删除（本地试跑验证）。
* **真 registry 端到端**：`bash cli/scripts/wait-for-npm-version.sh browser4-cli 4.13.18`
  → `Verified browser4-cli@4.13.18 on npm after 2s (attempt 1)`，退出码 0；
  用一个不存在的版本号 + 6 s 预算 → 退出码 1，输出含最后一次答复
  （`npm error code E404`）与 `https://www.npmjs.com/package/browser4-cli/v/9.9.9` 复查链接；
* YAML：两个 workflow 经 PyYAML 解析通过（9 / 5 个 job）；改动过的 3 个 `run:` 块
  `bash -n` 通过。

### 10.4 顺带发现（本轮只记录不改）

`cli/scripts/tests/install-browser4-cli.tests.sh` 的 `test()` 只把第 2 个参数当函数名调用
（`local fn="$2"; if "$fn"`），于是所有 `test "<名字>" bash -c "..."` 形式的断言**丢掉了
`-c "..."`，实际执行的是裸 `bash`** —— 无参数、stdin 为 /dev/null 时立即返回 0，
这些用例**恒为 PASS**（`file exists`、`starts with shebang`、`bash syntax check`、
`no non-ASCII bytes` 等）。把 `test()` 改成 `local name="$1"; shift; if "$@"` 后重跑：
`Results: 56 / 57 passed`，暴露 1 个真实失败
`double dash in --version handled (no operator parsing)` —— 它的断言其实是
`bash install-browser4-cli.sh --locate | grep -q version`，而 `--locate` 的输出里没有
"version" 一词，测试名与断言内容也对不上。本轮不动它：一是会让 release 的
`test-install-scripts` job 立刻变红，二是"正确的断言该是什么"需要单独判断；
建议另开一轮修 `test()` 并重写这条断言。新写的 `wait-for-npm-version.tests.sh`
已使用 `shift` + `"$@"` 形式，不受影响。

### 10.5 待观察与已知边界

* 下次发版若该步骤再次超时，先看日志里 `Waiting for ... appear on npm` 的行数与最后一次
  registry 答复：可能是 registry 处理超过 10 min，也可能是发布真的没落地；
* 该脚本从 **tag 的 checkout** 里执行，与本仓库其它 release 期脚本
  （`smoke-test-runtime-bundle.sh`、`install-browser4-cli.tests.sh`）一致。
  若用 workflow_dispatch 从分支重发一个**早于本提交**的 tag，且该版本还没发布到 npm，
  checkout 出来的树里没有这个脚本，该步骤会以 "No such file or directory" 失败；
  安全做法是用该 tag 自己的 workflow 版本（`gh workflow run release.yml --ref <tag> -f tag=<tag>`）。
  当前 tag `v4.13.18` 不受影响：版本已在 npm 上 → `should_publish=false` → 该步骤被跳过。

## 11. 安装脚本 + 两个测试套件的重审（按脚本实际行为对齐）

起因：§10.4 发现 `install-browser4-cli.tests.sh` 的断言助手恒为 PASS。本轮按用户要求，
以**安装脚本的实际行为**为准重审 `install-browser4-cli.sh` / `.ps1` 与两个测试套件，
并把审出来的健壮性问题一并修掉。

### 11.1 安装脚本 `install-browser4-cli.sh`

| 问题 | 后果 | 修复 |
|---|---|---|
| `--version --dry-run` 把 `--dry-run` 当成 tag | 生成 `releases/download/--dry-run/...`，很久之后以 404 失败；`--install-dir --silent` 会建一个名为 `--silent` 的目录 | 新增 `validate_value`：缺值或值像选项即报错退出 |
| `die` 放在 `$( ... )` 里（本轮改动中自查发现） | 只杀掉子 shell，安装继续，退出码 0 —— 校验形同虚设 | 校验函数在**当前 shell** 执行 |
| `GITHUB_TOKEN` 对**所有** URL 附加 Authorization | 令牌被发给阿里云 OSS 镜像（第三方） | 仅当 URL 是 `https://github.com/*` 时才加头 |
| 只按 >100 KB 判断下载内容 | 大于 100 KB 的 HTML 错误页/被截断的内容会被当二进制安装 | 新增 `verify_binary_magic`（ELF / MZ / Mach-O），不通过即拒绝 |
| `--locate` 也要求 curl/od 等下载工具 | 诊断命令在精简系统上直接失败 | `check_commands` 移到安装路径（locate 早退之后） |
| 无 `flock` 时提示"Could not acquire lock after 10s"并空等（macOS 默认无 flock） | 每次安装在 macOS 上白等数秒 + 假警告 | 无 flock 时直接跳过加锁 |
| PATH 幂等用 `grep -F "$dir"` 全文匹配 | 目录名出现在注释/其他变量里就再也不写 PATH | 只在 `PATH=` 赋值行里匹配 |
| `--install-dir` 指向已存在的文件 | `mkdir -p` 报错信息晦涩 | 明确 `die "Install path exists but is not a directory"` |
| `--locate` 忽略 `--install-dir` | 诊断结果误导 | 按 `--install-dir` 显示 |

### 11.2 安装脚本 `install-browser4-cli.ps1`

| 问题（独立审计编号） | 后果 | 修复 |
|---|---|---|
| R2 `& $binaryPath --version` 不检查 `$LASTEXITCODE` | 损坏/错架构的二进制照样打印绿色"installed successfully" | 检查退出码与输出，失败打印红色 `[x]` 并置退出码 1 |
| R3 后端安装失败只 warn，脚本仍 exit 0 | CI/自动化无法发现后端失败 | `Install-Backend` 返回布尔值，失败时最终 `exit 1`；成功横幅移到后端步骤之前 |
| R4 `Get-BackendAction` 空状态默认 `upgrade` | 全新机器（或无 CLI 响应时）会跑 `upgrade` | 只有状态里确实出现已安装 bundle 才 `upgrade`，否则 `install` |
| R5 `-DryRun` 仍会真的调用已安装 CLI 的 `status`，并打印 `[v] Installed:` | 干跑有副作用、输出不实；每次泄漏一个临时文件 | dry-run 不再探测 CLI；临时文件放进 `try/finally` 清理 |
| R6 PATH 去重用解析路径、追加却用原始 `$Dir` | `-InstallDir .\b4` 会把**相对路径**永久写进用户 PATH，尾斜杠会重复追加 | 追加解析后的绝对路径，比较大小写不敏感 |
| R7 中国区检测只匹配 IANA 时区 | Windows 返回 `China Standard Time`，检测恒为 false（在华语 Windows 上实测 False） | 一并匹配 `China Standard Time` 与 `zh-CN/zh-Hans` 文化（实测已变 True） |
| R8 `-Version` 未校验 | `-Version ../../evil` 生成穿越 URL 并传给 `--tag` | 校验 tag 形态；裸 semver 自动补 `v` 前缀（与 .sh 一致） |
| R1 下载无完整性校验（仅 >100 KB） | 截断/错误内容可能被安装 | 新增 `Test-BinaryMagic`（ELF/MZ/Mach-O）并在下载后校验 |
| R9 未 pin TLS / `-Force` 文档不实 / `-Locate` 忽略 `-InstallDir` | 老 .NET 上 TLS 1.0 握手失败；文档误导 | 5.1 下 pin TLS 1.2；修正 `-Force` 文档；`-Locate` 尊重 `-InstallDir` |

### 11.3 两个测试套件

* **`.sh`**：助手改为 `shift` + `"$@"`（53 处断言第一次真正执行）；4 条恒真断言
  （`detect_china_locale && true || true`、`... || true` 的 box-drawing 检查、
  `grep -vq 'Added to PATH'`、`grep -qi '...|Install'`）改成真断言；过期的
  `--locate | grep -q version` 重写为 `--version` 语义检查；新增 9 条用例：参数值拒绝、
  裸 semver 归一化、`--locate` 在缺少下载工具时仍可用（自建最小 PATH）、下载拒绝 HTML、
  下载接受 ELF、令牌只发给 GitHub（stub curl 记录参数）。
* **`.ps1`**：新增 **harness 自检**（故意失败一次，验证失败会被计数）；`RunScript` 不再
  返回 AutomationNull（这正是两条断言"永远不会失败"的根因）；`-Force rejected` 这条
  过期断言改为验证 `-Force` 覆盖 `-SkipIfInstalled`；`-Source invalid` 改为断言退出码；
  剥离末尾 `Main` 改为按 AST 范围（原来用正则，一旦有人在 `Main` 后加一行，套件就会执行
  真实安装并改用户 PATH）；新增 `Test-BinaryMagic`、`-Version` 三条、`-DryRun` 不探测 CLI、
  以及此前完全没覆盖的 `Find-LocalBinary` 捆绑二进制路径。

### 11.4 验证

| 套件 | 结果 | 耗时 |
|---|---|---|
| `bash cli/scripts/tests/install-browser4-cli.tests.sh` | **66 / 66 passed** | 16.5 s |
| `pwsh -NoProfile -File cli/scripts/tests/install-browser4-cli.tests.ps1` | **47 / 47 passed** | 8.6 s |

两者都远低于 release 的 `test-install-scripts` job 超时（5 分钟）。注意：**下一次打 tag 时，
Linux 那半边会第一次真正执行这些断言**——如果某条依赖 runner 环境，会在 release 里暴露。

### 11.5 未修（已记录，供后续判断）

`Content-Length` 比对（`Invoke-WebRequest -OutFile` 不暴露响应头，需要额外 HEAD 请求）；
`.old` 仅在目标存在时清理；非 Arm64 一律映射 x64；`-SkipIfInstalled` 与 `-Version` 的交互
（带 `-Version` 时不会跳过）；下载失败后残留空安装目录；Windows 侧 `Add-DirectoryToUserPath`
会真实写入用户 PATH，因此测试套件刻意不覆盖该分支（避免污染开发机）。

## 12. v4.13.19-ci.1 的唯一红点：`install-browser4-cli.sh` 里的一个非 ASCII 字节（已修）

`ci.yml` 的 `Validate install script tests` 步骤（`65 / 66 passed`）唯一失败的是
`no non-ASCII bytes`；该步骤是本轮（§11）刚刚加进每一轮 CI 的（commit `871933cdd7`）。

### 12.1 根因：文件里真有一个非 ASCII 字节，不是断言的问题

`install-browser4-cli.sh` 的注释（`check_symlinks` 内）用了 em dash：

```
  # Check in install dir first — anything here is ours
```

该字符自 2026-07-21 起就在（blame `87e85c6e41c`，与 `4.14.x`/`main` 一致），不属本轮改动。

### 12.2 为什么现在才红：两次"真正的断言"叠加

先是 §10.4 发现 `test()` 助手把断言变成恒真（`grep -P` 那条从未真正执行），
`1a62bd71b4` 修好助手；接着 `871933cdd7` 把套件接进每一轮 CI——Linux（UTF-8）第一次执行
这条断言，立刻命中那个字节。**"测试刚变绿又变红"不代表有新改动，很可能是断言第一次真的跑。**

### 12.3 一个只在本机出现的"假绿"

本机（Windows Git Bash + zh-CN 码页）：`grep -P` 把文件按 GBK 解码，em dash 的 3 个字节
不在 `\x00-\x7F` 范围内。实测**对照实验**：对一个确认含 3 个非 ASCII 字节的副本执行该
断言，本机同样返回 0 —— 即本机根本无法区分该文件是否含非 ASCII 字节。所以本机看到的
`66 / 66` 与 CI 看到的 `65 / 66` 并不矛盾，是两个不同的东西。CI 的 `ci-build` 跑在
`ubuntu-latest`（UTF-8 locale），断言在那里是有效的。

### 12.4 修复与验证

* 只改 1 行：em dash → ASCII `-`（正是"不删除/不跳过测试、修产品或脚本"的路线）。
* 字节级验证：Python 读原始字节，>0x7F 计数为 **0**；按 `grep -P '[^\x00-\x7F]'`
  的语义（POSIX/UTF-8 下匹配任何 >0x7F 的字节）该断言必然通过。
* `bash cli/scripts/tests/install-browser4-cli.tests.sh` → **66 / 66 passed**；
  `bash cli/scripts/tests/wait-for-npm-version.tests.sh` → **All 11 tests passed**；
  `bash -n cli/scripts/install-browser4-cli.sh` 通过。
* 顺带核对 release 的 `Run install-browser4-cli.tests.sh`（Linux）——同一个套件，
  即本修复同时解掉 release 侧的同款红点。

### 12.5 边界说明（不在本门禁内，故不动）

`b4w.sh`（15）、`b4w.ps1`（3051）、`cli/scripts/smoke-test-runtime-bundle.sh`（78）都不是
纯 ASCII，但**没有任何测试对这些文件做 ASCII 断言**（两个安装器套件的 `no non-ASCII bytes`
只检查各自的安装脚本），因此不在本 CI 门禁范围内、也没有功能性风险——本次刻意不扩大改动。

## 13. v4.13.19-ci.2 的唯一红点：`SwarmCrawlFixtureTest`（守卫拒绝沿用了 30–45 s 远程退避）（已修）

红 run 34712923110 的 `Run Tests` 终局是 `browser4-rest-tests` 的 `SwarmCrawlFixtureTest`：
`Tests run: 3, Failures: 2 … Time elapsed: 156.4 s`，上一轮绿 run（ci.6，`f3c1a6202c`）同一
用例是 `3 / 0`、`33.56 s`。红 run 的代码侧没有任何相关改动：`e018b73107` 只动安装脚本、
文档与版本号；守卫代码与测试和 `4.14.x` 逐字节相同，`main` 上甚至没有该守卫。**判定为
负载敏感的 flake，不是回归**，故按"不删测试、不跳过、补重试/修竞态"的路线处理。

### 13.1 机制（由失败 run 自身日志反推）

1. 测试 1（`testSubmitGeneratedCrawlProductUrl`）在 `19:11:37.372` 提交任务，然后
   `waitForScrapeCompletion` 轮询 2 分钟（`19:13:37` 到期）。
2. 同一个 run 里有大量并发 fetch 在争抢同一批 fixture 页面。快照来源守卫
   （`captureNavigationSnapshot`）反复拒绝本次 fetch 的抓取：
   `Tab origin mismatch: refusing to capture '…/product/1.html' for fetch '…'`，
   时间点 `19:11:20.9 / 19:11:33.1 / 19:12:09.4 / 19:12:48.5 / 19:13:34.5`，每次随后
   `🤺 Trying Nth 32–43s later`。全 run 共 78 条（绿 run 74 条——**该冲突在绿 run 里
   同样存在**，差别只在量级）。
3. 拒绝走的是"退役 driver + CRAWL 重试"（`ForwardingResponse.crawlRetry`），延迟由
   `page.retryDelay ?: retryDelayPolicy(...)` 决定，而默认策略是
   `AbstractTaskRunner.retryDelayPolicy` 的 **30 + rand(15) s**（远程失败退避）。
   3 次重试合计 90–135 s ≈ 整个 2 分钟等待窗口 ⇒ **调用方必然先放弃**。
4. 结果：测试 1 在 `19:13:37` 时看到 `isDone=false`（最后一次状态还是 202/1601 retrying）
   而失败（`Time elapsed: 121.1 s`）；页面在 `19:14:09.779` 才打印
   `Gone … got 408 … retry budget exhausted (4)`，即**比调用方的 deadline 晚 32 s**。
5. 测试 2（`19:13:38.385` 提交，`35.26 s`）因为同一 URL 已经失败（417）而提前退出，
   于是 `assertEquals(200, statusCode)` 也失败。同一个 run 里 78 次拒绝、0 次
   `withTimeout` 取消——**问题不是"抓取失败"，是"重试节拍与调用方 deadline 不匹配"**。

### 13.2 修复（`browser4-protocol`，最小改动）

* `emulator/Exceptions.kt`：新增 `TabOriginMismatchException(message, driver)`，父类仍是
  `WebDriverException`（`open class`，见 §9 同类用法）。**故意保持子类型**，这样
  `browseWithDriver` 里既有的 `catch (e: WebDriverException)` 语义不变：退役 driver +
  `crawlRetry`——拒绝的 driver 会在 `put` 时被关闭（`isWorking == false`），下一次重试
  落在**新 driver/新 tab** 上，正是 §8 里 #592 记录的既定方向"换新 driver/tab 重试"。
* 新增 `TAB_ORIGIN_MISMATCH_RETRY_DELAY = 10 s` 与 `crawlRetryDelayFor(e): Duration?`
  （只对守卫拒绝返回非 null），在同一个 catch 里 `crawlRetryDelayFor(e)?.let {
  task.page.retryDelay = it }`。
* 10 s 不是新造的魔数，是本模块已有的两个先例：`MultiPrivacyContextManager`
  （"No driver available" → `FetchResult.crawlRetry(task, delay = 10s, …)`）与
  `StreamingTaskRunner` 的 cancel 路径（`page?.retryDelay ?: Duration.ofSeconds(10)`）。
  改完后最坏节拍从 `30 + 10 …` 变成 `~30 s + 3×10 s`，落在 2 分钟窗口内。
* 两个守卫抛点改为抛新异常；catch 里对守卫拒绝只记 `[Handled]`（不带栈），守卫自身的
  warn 仍带完整文档信息——避免每 run 78 条 `[Unexpected] WebDriverException` 栈噪声。
* 测试（`ExceptionsTest`，纯单元、无浏览器）：子类型契约 + 携带 driver（可被 retire）、
  `crawlRetryDelayFor` 只对守卫拒绝返回 10 s（其它 `WebDriverException` 仍走默认策略）、
  以及"4 次尝试 × 10 s 必须落在调用方 2 分钟窗口内、且小于退避下限 30 s"。

### 13.3 验证

`./mvnw -ntp -o -pl browser4-core/browser4-protocol -am test -Dtest=ExceptionsTest` 全绿
（新增用例含"必须小于 30 s / 4 次重试必须小于 2 分钟"两条边界断言，在改动前必然失败）。

下一轮的**可观测信号**：守卫拒绝后的重试日志行 `🤺 Trying Nth <delay> later` 应由
`32–43s` 变为 `10s`（`delay.readable()`），而 `Tab origin mismatch` 行数与绿 run 同量级
（70+）仍属正常——**看节拍，不要只看拒绝次数**。

### 13.4 边界（未修，仍指向 #592）

本轮只修"**把瞬时、本地的拒绝当成远程失败来退避**"这一放大环节，没有修**两个 fetch 共用
一个 tab** 这个根因（§5/#592，涉及驱动复用，不在本仓可改范围）。因此：如果争用持续超过
约 30 s，fetch 现在会**在调用方窗口内快速失败**（测试 1 会改在 `statusCode` 断言上失败，
而不是死在 deadline 上）。同一份日志显示争用是瞬时的（同一批页面在 `19:14:18` 以 200
成功、绿 run 也有 74 次同类拒绝），所以这一轮把"调用方必然先放弃"变成"重试能在窗口内跑完"。

## 14. #592 的另一半：丢页必须报账，"完成"必须等于静止（已修）

§13 修的是**节拍**，并明确把"两个 fetch 共用一个 tab"记为不在本仓可改范围。本节修的是
#592 剩下那一半——**丢页不可见**与**终态不等于没活干**，同样不碰驱动复用。

### 14.1 五个缺口（对照改动前的代码）

1. **完成判据只数数**：`crawlDepthN` 用 `submittedCount == completedCount` 判定结束。
2. **计数相等不等于静止**：一个 handler 的顺序是"记账 → 发现并 submit 子链接 → 自己 +1"，
   而多个 handler 并发跑。B..E 的 +1 完全可以在 A 做完发现之前把计数追平 ⇒ 轮次被判完成，
   **A 的子链接永远没被 fetch**。这就是 10 页变 8 页。
3. **失败没有任何渠道进入判定**：`ParsableHyperlink` 只注册了 `onHTMLDocumentParsed`
   （`browser4-core/.../ParsableHyperlink.kt:35`）——**只有解析成功才回调**。fetch 失败
   （守卫耗尽重试预算、任务被丢弃、408/417）时 crawl 一无所知，计数器只会永远差一个。
4. **终态后仍在提交**：`crawlDepthN` 返回后 `finally { session.close() }` 关不掉已经进入解析
   管线的 fetch，而发现逻辑不受 `firstEvent` 保护，于是出现 `+2m31s: submitted 2 links at
   depth 2`（issue 症状 3）。
5. **顺带发现**：轮次内部超时后写的 TIMEOUT 记录，会被 seed 循环之后的终态写入覆盖成 OK——
   因为 seed 循环的 `catch (e: Exception)` 把 `TimeoutCancellationException` 一起吞了。
   即"**超时的爬取报 OK**"，与 #592 同一类静默。

### 14.2 修复

* 新增 `CrawlLedger`（`browser4-rest/.../service/CrawlLedger.kt`，纯 Kotlin 状态机，
  不依赖 session/browser 因而可单测）：
  * `enter()/leave()`：**有 handler 在飞就不可能完成**（缺口 2）；
  * `recordFailure()`：给失败 URL 一个终态（缺口 3），按 URL 幂等（`onLoaded` 每次重试都会触发）；
  * `submit()`：按 URL 去重（重复外链不会让轮次空等）；
  * `close()`：**终态即闭闸**，晚到的 parse 事件不再提交任何东西（缺口 4）；
  * `outstanding()`：轮次被放弃（超时/取消）时列出没交代的 URL，而不是让 partial 结果冒充完整。
* 计数**故意不按 URL 匹配**：页面最终 URL 可能与提交时的 URL 不同（重定向、`document.baseURI`），
  所以"到了"是**按页计数**，URL 只用于去重与报账。
* `CrawlService`：两个 crawl 循环（`crawlDepthN`、`crawlDepth1`）都接 ledger；每个提交的 URL
  挂 `crawlEventHandlers.onLoaded` 结算，判定照抄 `XSQLHyperlink.CrawlEventHandlers`
  （本仓既有的权威读法：retry/canceled 不结算；`!isFetched` **不等于**失败，页库命中时它也
  是 false）；`currentDepth == null` 的"记账式丢页"改为记失败。
* 数据模型：`CrawlResponse.failedPages` / `pagesExpected` + 新 `CrawlFailedPage`、`CrawlRound`。
  **不变式**：`pagesFound + failedPages.size == pagesExpected` 恒成立。
  `status` 取值集合**故意不动**（CLI 轮询只认 OK/SC_OK/TIMEOUT/ERROR，新增取值会把轮询挂死），
  损失用 `failedPages` + `diagnostic` 表达。
* 终态：`timedOut` 的轮次不再被 OK 覆盖；loss note 是**追加**而不是替换既有 diagnostic；
  seed 循环显式重抛 `CancellationException`。
* CLI（`main.rs`）：`failedPages` 非空时打印 `⚠ N of M submitted page(s) were never
  delivered` 并列出前 5 个 URL（含 depth/status/reason），`json_field` 暴露
  `failed_pages` / `pages_expected`。

### 14.3 验证

| 层 | 命令 | 结果 |
|---|---|---|
| 单元 | `-pl browser4-rest -am test -Dtest=CrawlLedgerTest,CrawlResponseTest` | 30 / 0 / 0（ledger 13、response 17） |
| 单元（全模块） | `-pl browser4-rest -am test` | 348 / 0 / 0，BUILD SUCCESS |
| Rust | `cargo test --bin browser4-cli` | 1195 passed / 0 failed |
| Rust e2e（新增场景） | `cargo test --test e2e -- --scenario=test_e2e_crawl_foreground_reports_lost_pages` | 1 passed / 0 failed |
| 集成 | `-Pall-test-modules -pl browser4-tests/browser4-rest-tests -am -DrunITs=true -Dtest=CrawlFixtureMetadataTest` | **5 / 0 / 0**，294.9 s |

新增的 CLI 场景 `test_e2e_crawl_foreground_reports_lost_pages` 用 mock 响应喂一份
`pagesFound=8 / pagesExpected=10 / failedPages=[2 条]` 的 OK 结果，断言 CLI 打印
`⚠ 2 of 10 submitted page(s) were never delivered` 并逐条列出 URL（含 depth/status/reason，
status=0 时省略 status 段）——**"报 OK 但少页"从此有 UI 级回归**。

> 已知无关红点：`cargo test --test e2e -- --group=crawl` 里
> `test_e2e_crawl_foreground_with_sql` 失败。**该失败在本轮改动前的基线上逐字复现**
> （`git stash push -- cli/browser4-cli/src/main.rs` 后重跑同样失败），原因是 `--sql` 爬取时
> `crawl_structured_stdout_active()` 为真，`crawl_status_println!` 把
> `Crawl task submitted: ...` 写到了 **stderr**，而该场景断言在 stdout 上找它。
> 属既有的"结构化 stdout 模式"遗留问题，不在 #592 范围内。

`CrawlLedgerTest` 里两条用例就是缺口的回归：**"有 handler 在飞时轮次不得放行"**（10 页变
8 页那种交错）、**"失败必须核销，否则轮次空等"**。

集成 run 的实测日志（20 核 Windows，正是 issue 里失败的那台机器的形态）：

```
2026-09-13 13:43:54.771 INFO [@crawl#627] CrawlService - Crawl task 298f4eb7-... completed: 10 pages, 0 lost, status OK
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 294.9 s
```

同一 run 里仍有大量 `Retry(1601) rs: TabOriginMismatchException`（守卫拒绝本身照旧发生），
但轮次等到了每一个提交的 URL，**10/10 页、0 丢失**。这正是本次修复要达到的状态：
**拒绝还在，但它不再变成"少两页且报 OK"。**

集成测试同时加了：
* `assertNoLostPages()`——断言守恒式，失败时**点名**丢了哪些 URL（不再是 `expected 10, got 8`）；
* `testBackToBackCrawlsLoseNoPages()`——同时提交两个 crawl（复现 issue 里"上一个还没停就发
  下一个"的干扰条件），两者都必须终态且零丢失。

### 14.4 仍未修（与 §13.4 同一处边界）

根因"两个 fetch 共用一个 tab"仍在：守卫拒绝的次数不会因此减少，一次**持续**的争用仍可能让
某个 URL 耗尽重试预算。区别是现在它会出现在 `failedPages` 里、伴随 `⚠` 警告和可读的 reason，
而不再是一句 `status=OK` 加一个更小的页数。真要消除争用，得动 `browser4-core/browser4-browser`
的驱动池语义（例如"拒绝即换新 tab"），属于另一个 PR。

## 15. #592 的根因：会话绑定的驱动没有租约，整个 crawl 共用一个 tab（已修）

§13 修的是重试节拍，§14 修的是丢页可见性；两次都把"两个 fetch 共用一个 tab"记成
**不在本仓可改范围**。本轮把它定位到**本仓的 fetch 层**并修掉了。

### 15.1 定位过程（可复现）

1. 在 `ConcurrentStatefulDriverPool.poll/offer` 放临时探针（WARN/ERROR 级，绕过日志级别过滤），
   跑 `CrawlFixtureMetadataTest`：**整场 0 条探针输出** ⇒ crawl 的 fetch 根本没走驱动池。
2. 统计守卫拒绝：一场 run 里 `Tab origin mismatch` 234+ 条、`will be retired` 117 条，
   **全部指向同一个 `driver #2`**，且多个 worker 线程在 2 ms 内同时报它 ⇒ 一个 tab 被并发复用。
3. 读 fetch 入口 `PrivacyManagedBrowserFetcher.fetchDeferred`：`getWebDriver(page)` 先看页面/会话上
   有没有"指定驱动"（`page.getBeanOrNull(WebDriver)` / `page.conf.getBeanOrNull(WebDriver)`，
   后者继承自 `sessionConfig`，也就是 `session.bindDriver(driver)`）；命中就**直接 fetch，
   完全绕过驱动池的 poll/put 租约**，只有"没有指定驱动"时才走 `privacyManager.run { … }`（那条路才有租约）。
   ⇒ 会话绑定的那一个 tab 被该会话的**所有** fetch 共用，而 crawl 是并发提交的。

这一步同时解释了 issue 的全部现象：为什么只有一个 driver id、为什么高核机器更糟（并发窗口更大）、
为什么 4 核 CI 上偶发绿、为什么 title 会串页 / 会丢页。

### 15.2 修复

* 新增 `DriverLeaseRegistry`（`browser4-protocol`，纯 Kotlin + coroutines，可单测）：
  按 driver id 的 `Mutex`，`tryAcquire(id, timeout)` / `release(id)`。
* `PrivacyManagedBrowserFetcher.fetchDeferred` 在用"指定驱动"前先取租约、用完释放：
  同一个 tab 同一时刻只有一个 fetch 在驱动它。
* 取不到租约（默认 60 s，远大于正常一次抓取）时**不再共用 tab**，改为走 `privacyManager.run`
  租一个独立驱动，并打 WARN —— 既不永久阻塞调用方，也不做已知会损坏抓取的事。

### 15.3 验证（同一台 20 核 Windows，同一个 fixture 测试）

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `Tab origin mismatch` | 282 | **0** |
| `will be retired` | 141 | **0** |
| `TabOriginMismatchException` | 282 | **0** |
| `CrawlFixtureMetadataTest` | 5/0/0，294.9 s / 327.9 s | **5/0/0，234.9 s** |
| crawl 任务日志 | `completed: 10 pages, 0 lost` | `completed: 10 pages, 0 lost` |

新增单测 `DriverLeaseRegistryTest`（5 条，纯单元无浏览器）：同 driver 互斥、不同 driver 互不阻塞、
等待者在释放后接管、**8 个并发 fetch 的 maxConcurrent 必须为 1**、租约可复用。

**注意**：拒绝从 282 掉到 0 说明链路已按 tab 串行化——代价是同一会话的抓取不再并行。
crawl 想并行需要**不绑定会话驱动**、改为按 tab 从驱动池租用（`crawlDepthN` 虽然设了
`maxOpenTabs(8)`，但驱动池的 capacity 在池创建时读取配置，这个设置常常赶不上）。
这属于下一步的吞吐优化，不是正确性问题。

### 15.4 教训

"不在本仓可改范围"这个判断是从**驱动内部**（`pulsar-browser` 4.11.16 artifact）推出来的，
但**缺陷本身在 fetch 层**：谁把驱动交给谁用、有没有租约，都是本仓的代码。

定位这类 heisenbug，最便宜的一步是**先在怀疑路径上放一个不会被日志级别吞掉的探针**——
"探针 0 输出"直接把结论从"池租约有竞态"翻转成"根本没走池"，省掉几小时的源码阅读。

## 16. 后续修正：深度身份与结算（`crawlDepthN`，4.13.x）

§14 把 `currentDepth == null` 定义为 **记账式丢页** 并记失败。这条规则在本轮被推翻：
它把**已经抓到的页面**判成丢失，而且失败记录经 `failedKeys ∩ submittedUrls` 过滤后
**对调用方不可见**，于是 §14 想消灭的"少页却报 OK"从一个新入口回来了。触发条件不是边角场景：

* **重定向**（`http→https`、`/x → /x/`）：`document.baseURI` 是落地 URL，`depths` 的键是提交 URL；
* **`<base href>`**（jsoup 会用它覆盖 document base URI）：`<base href="/">` 让每一页的 baseURI
  都等于站点根，而根通常就是种子页——子页被当成"重复事件"，一行都不记。

### 16.1 四处修正

| # | 问题 | 修正 |
|---|---|---|
| 1 | 用 `document.baseURI` 当页面身份查 `depths`，查不到就 `recordFailure` + 丢弃该页；这条失败不进 `failedPages`，却消耗一个 settle 名额 ⇒ 轮次提前完成、`pagesFound + failedPages.size == pagesExpected` 不再成立 | 身份回归"提交 URL"（`page.url`），与 `visited`/`depths`/`recorded` 同一套键；深度查询先提交 URL、再 serving URL（都必须是本轮的键）；查不到就**照常记录**该页，标 `depth=-1`（`UNKNOWN_DEPTH`）并打 WARN，只记成功不记失败；`-1` 的页不展开 |
| 2 | `extractDepth()` 从 `page.configuredUrl` 正则抠 `-depth N`：`-depth` 不是 LoadOptions 选项，`configuredUrl` 由 `options.toString()`（只序列化已知选项）拼出，**永远匹配不上**——那层"重定向兜底"并不存在 | 删掉 `extractDepth()` 与 `buildArgsForDepth()` 的 `-depth N` 标记（改为 `buildLinkArgs`），`depths` 成为唯一事实来源；`CrawlLedger.REASON_NOT_QUEUED` 一并删除 |
| 3 | `recordSuccess(key)` 之后的发现/提交一旦抛异常，catch 会对**同一个 key** 再 `recordFailure` ⇒ 一次提交结算两次 ⇒ ledger 走溢出分支并**在还有 handler 在飞时**判完成 | catch 先判 `ledger.isRecordedSuccess(key)`，只有"这一页从未落盘"才记失败 |
| 4 | `ledger.submit(...)` 返回值被忽略、`session.submit` 照发 ⇒ 终态后仍在提交（§14.1 缺口 4 的原症状）；同一页内的重复链接也会被重复抓取 | 只有 `submit` 返回 true 才 `session.submit`；种子提交同样加闸门（被拒则直接结束轮次，而不是空等到超时） |

顺带把结果行的 URL 从 serving URL 改为**提交 URL**：行 URL 现在等于 `recorded` 的去重键，
"一行 = 本轮排队过的一个 URL"成立，`pages` 与 `failedPages` 可用同一把尺子对账
（`crawlDepth1` 一直如此，depth ≥ 2 现在与之一致）。

### 16.2 新增可测接缝与验证

* `CrawlSupport.resolveQueueDepth(submittedUrl, servedUrl, depths)`——纯函数，把
  "提交优先 / serving 兜底 / 都没有则 null"三条规则从 handler 提出来，可单测。`CrawlSupportTest`
  新增 6 例：重定向保深度、`<base href>` 不得让子页继承种子深度、兜底命中、未知深度为 null、
  规范化漂移，以及一条**钉住已知缺陷**的用例（见 16.3 第 1 条）。
* `mvn -o -pl browser4-rest -am "-Dtest=Crawl*Test" -DfailIfNoTests=false -D"surefire.failIfNoSpecifiedTests=false" test`
  → **Tests run: 79, Failures: 0, Errors: 0**（原 73 + 新增 6）。
  Windows 上带点的 `-D` 必须按仓库既有写法转义成 `-D"key.with.dots=value"`，否则会被拆成两个参数
  （`-Dsurefire.failIfNoSpecifiedTests` 被拆开的报错是 `Unknown lifecycle phase ".failIfNoSpecifiedTests=false"`）。

### 16.3 第二轮：会话生命周期与在途视图（已修）

* **B7 在途发布不再抹掉丢失计数（顺带发现一个更严重的）：** `publishIncremental` 原先**重建**记录，
  只带 pages/linksDiscovered/diagnostic/startedTime/seedStatuses ⇒ 下一轮种子一开始发布页面，
  上一轮种子已报的 `failedPages`/`pagesExpected`/`parallelTabs`/`maxConcurrentFetches` 就归零
  （轮询看到的丢失计数闪回 0）。抽成纯函数 `CrawlSupport.mergeIncrementalProgress(...)` 后改为**合并**，
  并保留 `createdAt`（TTL 清理依据，发布进度不该让任务"变年轻"）。
* **新发现：终态记录会被复活。** 轮次超时/取消后 `writeCancelled` 已写入 TIMEOUT，
  而仍在飞的 parse handler 还会 `publishPages` ⇒ 记录被改回 `PROCESSING`，CLI 轮询就此**永远等一个
  不会再被终态化的任务**。`mergeIncrementalProgress` 对终态记录直接返回 null（丢弃发布），
  `publishIncremental` 记一条 debug 日志。这不只影响超时路径：成功收尾后同样可能被晚到的发布覆盖。
* **B8 快照持锁：** 超时/日志路径读 `results` 未持锁（`Collections.synchronizedList` 迭代需手动同步），
  而 handler 可能还在追加 ⇒ 抽 `snapshotResults()`（`synchronized(results) { toList() }`）统一读取。
* **C1 会话生命周期（最小修法）：** 爬取会话是直接在 `agenticContext` 上建的，manager 看不到，
  而 `session.close()` **不会**把会话从 `AbstractPulsarContext.sessions` 摘除（只有
  `context.closeSession()` 会）⇒ 每轮留一个已关闭会话在注册表里，`getOrCreateSession()` 取
  `sessions.values.firstOrNull()`，更老的那个被删后可能把**已关闭的爬取会话**交出去。现改为：
  * 新增 `CrawlSupport.releaseCrawlSession(session, context)`（`closeSession` 而非 `close`，返回失败而非吞掉），
    四个释放点（depth0 的重试/收尾、depth1、depthN、CrawlService 的 depth0 共享会话）全部走它；
    关闭失败按 WARN 报出——**这是真正的泄漏**：close 才解绑 browser/driver，失败后没有任何组件再持有它。
  * 建会话时统一打 label（`CrawlSupport.crawlSessionLabel(taskId)` = `crawl-<taskId>`），
    便于在日志/上下文转储里定位是哪次爬取占着浏览器资源。
  * 仍**未**做（见 16.4）：不把会话纳入 `PulsarSessionManager` 的正规生命周期（`createRoundSession` +
    `SessionKind.CRAWL`）。原因是 crawl 每轮一个会话、且刻意不复用（ledger 的每轮结算依赖"这一轮拥有它"），
    纳入 manager 需要一并定义 kind、健康检查与释放语义；当前的 closeSession + label 已经消掉了
    "注册表堆积 + 可能交出已关闭会话"这两个具体危害。

新增可测接缝：`releaseCrawlSession`（用 Mockito 钉住"必须走 `closeSession` 而不是 `close`"、
"失败要返回而不是吞掉"）与 `mergeIncrementalProgress`（钉住"保留丢失计数/身份/年龄"与
"终态记录不得被复活"）。同一命令复跑 → **Tests run: 83, Failures: 0, Errors: 0**。

### 16.4 仍未做（按优先级）

1. **（§17.1 已修）`normalizeForVisit` 的顺序缺陷**：先 `removeSuffix("/")` 再剥 query，于是
   `…/product/1/?utm=1` 与 `…/product/1` 是两个键——同一页面被两种写法链接时会提交两次、计两行，
   与该函数自己写的"query 不得制造第二个身份"矛盾。修法是先剥 fragment/query 再去尾斜杠；
   因为所有 crawl 路径的去重都派生自它，改动必须显式（`CrawlSupportTest` 已用一条用例钉住现状）。
2. **（§17.2 / §17.3 已修）** `crawlDepthN` 的轮次超时 `depth * 300s`（上限 30 min）**恒大于**任务级 `CRAWL_TASK_TIMEOUT_MS = 600s`，
   所以 `catch (TimeoutCancellationException)` 里"部分结果 + `outstanding()`"对 depth ≥ 2 不可达；
   超时实际由 `CrawlService.writeCancelled` 收尾，而它**不带** `failedPages`/`pagesExpected` ⇒
   超时的深爬仍会少页且不报账。`crawl.md` 的"5 min/level，上限 30 min"也与实际不符（depth ≥ 2 实为 10 min）。
   这一条需要先定预算策略（轮次预算应由"任务剩余预算"派生，而不是每轮各算一份），所以留到下一轮。
3. **（§22 已修）在途视图仍是"单轮"而非"聚合"**：`publishPages` 只带当前轮的 pages，所以下一轮种子开始发布时
   `pagesFound` 会从聚合值回落到单轮值（`failedPages`/`pagesExpected` 现在已被保留）。
   要真正单调，需要让 sink 聚合各轮 pages——注意不能简单按 URL 求并集：同一 URL 被两个种子各抓一次
   在终态记录里是两行，并集会把它们并成一行。属于显示口径问题，不是丢页问题。
4. **（§22 已修）** 多轮并发发布对同一条记录是 read-modify-write，没有 `recordSeedProgress` 那样的 per-task 锁
   （`CrawlTaskContext.publishLock` 只在种子收尾时用）。危害是瞬时视图可能少一轮的字段，
   下一次发布/种子收尾就会修正；要根治需让 sink 拿到 task context 的锁。
5. **（§21 已修）发现链接未去重**，且不套用 `--ignore-url-query` / `--no-norm`（depth 1 与引擎路径都套用）；
   `topLinks` 预算可能被重复链接吃光（重复抓取已在第一轮 #4 的闸门下消失，预算问题仍在）。

## 17. 轮次预算与"没跑起来/没结算"的报账（4.13.x，§16.4 的第 1、2 条）

§16.4 把两条留到"下一轮"：`normalizeForVisit` 的顺序缺陷，以及轮次预算 `depth * 300s` 与任务级 600s
的错配。两条都在本轮修掉，各留一条钉住行为的单测。

### 17.1 `normalizeForVisit`：先剥 fragment/query，最后去尾斜杠

原实现是 `removeSuffix("/")` → `substringBefore('#')` → `substringBefore('?')`，于是：

| 输入 | 旧键 | 新键 |
|---|---|---|
| `…/product/1` | `…/product/1` | 同左 |
| `…/product/1/` | `…/product/1` | 同左 |
| `…/product/1/?utm=1` | `…/product/1/` ❌ | `…/product/1` ✅ |
| `…/product/1/#details` | `…/product/1/` ❌ | `…/product/1` ✅ |

`visited` / `depths` / `recorded` 三张表全部派生自这个键，所以同一页面被两种写法链接时会提交两次、
计两行、拿到两套深度——与该函数自己写的"query 不得制造第二个身份"直接矛盾。顺序改过来即收敛。
`CrawlSupportTest` 里那条 KNOWN QUIRK 用例改成 collapse 断言，另加一条根 URL 单一身份的用例。

### 17.2 轮次预算由"任务剩余预算"派生

`CrawlTaskContext` 在 worker 真正开工时 arm 时钟（`armBudget`，与 `withTimeout` 同一时刻——排队时间不算），
`fetchSeedUnit` 每轮读 `remainingBudgetMs()`，再由 `CrawlSupport.resolveRoundTimeoutMs` 定预算：

```
round = clamp( min(depth × 5min, 30min),  剩余任务预算 − 30s 报告余量,  15s 下限 )
```

* **修复前**：depth ≥ 2 的轮次预算 ≥ 任务上限 600s ⇒ `catch (TimeoutCancellationException)` 那条
  "部分结果 + `outstanding()`"分支**不可达**；轮次只会被任务上限**杀掉**，而被杀的轮次什么都不返回
  （既不返回 pages，也不返回 outstanding），`writeCancelled` 又只搬 `existing.pages` ⇒
  超时的深爬就是"少页且不报账"。
* **修复后**：任何 depth 的轮次都会在任务上限之前自己超时，并带着 `failedPages = failures + outstanding()`
  走 `writeCompleted`，终态记录是 TIMEOUT + 丢失清单 + loss note。30s 余量就是留给
  "快照结果 → 关会话 → 发布丢失"这段收尾的。
* **新增预算闸门 `hasBudgetForRound`**：剩余 < 45s（30s 余量 + 15s 下限）时**不提交**该种子，直接产出
  `unstartedSeedRound()`：0 页 / `pagesExpected = 1` / 1 行丢失 / `timedOut = true`
  （reason = `the crawl ran out of its time budget before this URL was submitted`），种子状态 `skipped`。
  守恒式 `pagesFound + failedPages.size == pagesExpected` 对"根本没跑起来的种子"同样成立，
  而不是让它凭空消失。
* depth = 0 不拿轮次预算：单页 load 是阻塞调用，`withTimeout` 中断不了它，任务上限才是它的界（注释里写明）。

### 17.3 任务上限路径的报账（`writeCancelled`）

`writeCancelled` 过去把 `failedPages`/`pagesExpected` 整个丢掉（连已结算种子的丢失也不报）。现在：

* **已结算的轮次**：`pages` = 各轮聚合、`failedPages`/`pagesExpected` 累加——与 `recordSeedProgress` 同一把尺子；
* **未结算的种子**：每个记一行丢失、`pagesExpected` +1、`seedStatuses` 补 `status = "timeout"`；
  reason 用 `e is TimeoutCancellationException` 区分"撞任务上限"与"用户取消"
  （`REASON_TASK_LIMIT` / `REASON_TASK_CANCELLED`）；
* 快照在 `task.publishLock` 下取：`recordSeedProgress` 是"先写 round 再写 status"，
  不加锁读会把同一个种子同时算成"已结算"和"未结算"；
* **刻意不认领在途轮次已发布的页面**：它的提交数未知，认领就会破坏守恒式。这正是
  `pagesFound + failedPages.size == pagesExpected` 存在的意义——宁可少认领，不可不报账；
  loss note 会说明这些种子需要重跑。

### 17.4 验证

| 命令 | 结果 |
|---|---|
| `mvn -o -pl browser4-rest -am "-Dtest=Crawl*Test" -DfailIfNoTests=false -D"surefire.failIfNoSpecifiedTests=false" test` | **Tests run: 90, Failures: 0, Errors: 0**（原 83 + 7：`CrawlSupportTest` 22 → 28，`CrawlServiceTest` 10 → 11） |
| `mvn -o -pl browser4-rest -am "-DexcludedGroups=<PR gate 列表>" "-Dsurefire.excludes=**integration" test` | **Tests run: 393, Failures: 0, Errors: 0**，BUILD SUCCESS（拆分提交时的 376 + §16 的 10 + 本轮的 7，账对得上） |
| `mvn -o -pl browser4-tests/browser4-rest-tests -am -DrunRestTests=true -Dtest=CrawlFixtureMetadataTest test`（真浏览器，本机 20 核 Windows） | 改动后 **5 / 1 / 0**；基线（把 7 个 crawl 文件 `git checkout HEAD~1 -- <files>`）**5 / 2 / 0**。两次都含同一个既有 flake，见下 |

真浏览器这一轮的对照，用来说明"没有引入新的红"：

* **改动后**：唯一失败是 `testReadonlyCrawlSurfacesServedOrFresh` —— `product/1.html` 的
  `title` 为 `null`（`expected: <Widget Alpha — $10.00>`）。这正是本文件 §5 第 159 行记录的
  同一条失败（同用例、同 URL、同断言），当时判定为"既有集成测试的负载敏感暴露面，非本轮回归"。
* **基线（§16/§17 之前的 crawl 源码）**：**5 / 2 / 0** —— 除了同一个 title-null，还多挂
  `testReadonlyRefreshCrawlVerifiesFreshness`（`expected 10 pages, got 4`，即 §16 修掉的"少页"症状）。
  也就是说本轮改动**没有**引入 title-null，反而消掉了基线里的少页失败；剩下的 title-null 属于
  store-serve（`-readonly` 不带 `-refresh`）路径的既有问题（存下来的文档没有可解析的 `<title>`），
  与轮次预算/报账无关，留给下一轮。
* 两次运行里 `assertNoLostPages(...)` 全部通过：真浏览器下 `pagesFound + failedPages.size ==
  pagesExpected` 成立，没有丢页——本轮改的报账逻辑在真实 crawl 上没有回归。

新增/改写的用例：

* `CrawlSupportTest`：尾斜杠 + query/fragment collapse、根 URL 单一身份、轮次预算派生与 30min 上限、
  预算闸门边界（45s 整点通过 / 少 1ms 拒绝 / 0 预算取下限）、`unstartedSeedRound` 的守恒式、
  `unfinishedSeedLosses` 只报未结算的种子；
* `CrawlServiceTest`：`taskTimeoutMillis = 10s` 提交 3 个种子 → 终态 TIMEOUT、0 页、3 行丢失、
  `pagesExpected = 3`、`seedStatuses` 三条 `skipped`、守恒式成立，并 `verifyNoInteractions(sessionManager)`
  证明闸门在**碰浏览器之前**就短路（这条测试不需要浏览器，属于 PR-gate 作用域）。

### 17.5 仍未做

§16.4 的第 3、4、5 条不变（在途视图非聚合、发布缺 per-task 锁、发现链接未去重）。本轮新增两条：

* **（§27 已做）`taskTimeoutMillis` 的按请求契约**：原来只有一个默认值 10 min（单测直接改这个
  `@Volatile var`），现在 `CrawlRequest` 带字段 + 校验 + 文档。见 §27。
* **store-serve 行的 `title` 为 null**（真浏览器 `testReadonlyCrawlSurfacesServedOrFresh`，改动前后都红）：
  §17.5 当时把它归到"`-readonly` 不带 `-refresh` 的 store-serve 路径"——**这个判断是错的**，
  下一轮（§18）用探针推翻并修掉了：真正发生的是"一次失败的抓取被 `-ignoreFailure` 兜住，
  crawl 把没抓到的页面当成一行记了下来"。

## 18. "标题为 null 的行"：一次没被真正抓到的页面被当成了页（4.13.x）

§17.5 把那个红归到"`-readonly` 不带 `-refresh` 的 store-serve 路径"。**这个判断是错的**：
本轮先用探针量，再改。

### 18.1 探针（先量，再改）

在 `crawlDepthN` 记录行的那一行加一条 WARN 级探针（绕过日志级别过滤），打印
`isFetched / prevFetchTime / isCached / document.title / html.length / contentLength / status / configuredUrl`：

* 两次带探针的健康运行（各 50 行）里，**每一行**都是 `fetched=true`、`prevFetchTime == fetchTime`、
  `htmlLen ≥ 3765`、标题齐全 —— 健康路径上页面确实被抓到了，行与 URL 对得上；
* 探针同时暴露了一个此前没人写下来的事实：**`crawl` 会给每个页面加载强制补 `-refresh`**
  （`CrawlRoundRunner.buildEffectiveArgs`），而 `-refresh` 在 `LoadOptions` 里的定义是
  `-ignoreFailure -i 0s` + `fetchRetries = 0`。于是：
  * `-readonly`（不带 `-refresh`）**永远走不到 store-serve 分支**：那个分支只有测试注释、
    `buildReadonlyNote` 的措辞和 `servedFromStore` 字段在描述它；
  * 反过来，一旦实时抓取失败，`-ignoreFailure` 会让引擎**不把失败报出来**，而是把存储里的那份交回来：
    页面带着存储的 `contentLength`、`status=200`、`isFetched=false`，以及一个**空文档**。
* 失败 run 的日志正好是这个形状：`got 200 0 <- 5.8857422 KiB … last fetched 3m42s ago, fc:5`
  （0 字节下载、内容来自存储、上次抓取在 3 分 42 秒前），同一 run 里还有
  `Retry(1601) BrowserUnavailableException` 与 `Timeout to wait for document ready`。

结论：那个红不是"store serve 没有 title"，而是**"一次失败的抓取被 `-ignoreFailure` 兜住之后，
crawl 把一张没抓到的页面当成一行记录了下来"** —— 行里有 URL、有（存储的）contentLength、没有 title。
健康时不出现（两次探针运行都绿），浏览器退化时出现（改动前后都能复现），与 §5 记的"负载敏感"一致。

### 18.2 修正

`crawl` 只允许"这一次加载确实交付了文档"的页面成为一行：

```kotlin
internal fun isDocumentDelivered(fetched: Boolean, html: String?): Boolean = fetched && !html.isNullOrBlank()
```

* `crawlDepthN` 与 `crawlDepth1` 的 parse handler 在**记录之前**判定；不满足就不记行，改走
  `ledger.recordFailure(..., CrawlLedger.REASON_NOT_DELIVERED)`，并打一条 WARN 说明
  `fetched / status / contentLength`。新原因与 `REASON_NOT_PARSED`（根本没触发 parse 事件）区分开：
  这条路径**触发了** parse，只是文档是空的 —— 这正是它以前能悄悄变成一行空标题的原因。
* 守恒式因此仍然成立：没交付的 URL 进 `failedPages`，不进 `pages`。用户看到的是
  "N of M submitted page(s) were never delivered + 原因"，而不是一行空标题。
* 判定放在 `recorded.add(key)` **之前**（不烧掉"首次事件"名额），位置在 `ledger.enter()` 之后的
  try 块里，`leave()` 依旧走 finally。

### 18.3 顺带修掉的测试假绿

`CrawlFixtureMetadataTest#testReadonlyCrawlSurfacesServedOrFresh` 原先**不断言页数**：一次返回 0 行的
crawl 会让它逐行的 title 断言循环根本不执行，于是"空跑通过"。现在它断言 10 行，并在注释里写明：
crawl 强制 `-refresh`，所以这条用例今天只会走 "verified fresh" 分支；store-serve 分支在强制 refresh
被重新审视之前没有覆盖。

### 18.4 验证

| 项 | 结果 |
|---|---|
| `mvn -o -pl browser4-rest -am "-Dtest=Crawl*Test" … test` | **Tests run: 92, Failures: 0, Errors: 0**（90 + 2：`CrawlLedgerTest` 13→14、`CrawlSupportTest` 28→29） |
| 真浏览器 `CrawlFixtureMetadataTest` | **5 / 0 / 0**（653.6 s）；新增的 "returned no document" WARN 一次都没触发 —— 健康 run 不误伤，也不再有空标题的行 |
| `browser4-rest` PR-gate 作用域 | **Tests run: 395, Failures: 0, Errors: 0**，BUILD SUCCESS（393 + 本轮新增的 2 条单测） |

### 18.5 仍未做

* **（§25 已做）强制 `-refresh` 与"readonly 可从存储读"的契约冲突**（§18.1）：`crawl --readonly` 永远会重新抓取，
  `buildReadonlyNote` 里 "served from the page store (age X)" 的措辞、`CrawlResponse.servedFromStore`
  与 `CrawlFixtureMetadataTest` 的 store-serve 分支都是死代码。**决策（用户，§25）：`--readonly` 优先于
  `--refresh`** —— 不是"没要 refresh 才不补"，而是"要了 readonly 就把 refresh 擦掉"，因为 readonly 只服务于
  X-SQL 引擎的第二次读，那一次读的语义就是"读本地缓存"。实现见 §25.2，日志证据见 §25.5。
* **（§26 已做）失败抓取的重试**：本轮只把"没抓到"如实报成丢失，没有加重试。`crawlDepth0` 有 `MAX_FETCH_RETRIES`，
  两个链接发现路径没有。"交付失败即重投一次"需要在 ledger 上开一个"尝试中、仍未结算"的口子
  （现有的 `enter/leave` + `settle()` 恰好一次语义会被重复结算破坏），属于独立一轮。
  §26 就是这么做的：attempt token + `startRetry` 撤单式重投，唯一的重投机会只花在**引擎判为终局、
  而 crawl 没拿到**的失败上（引擎自己的重投由 `isRetry` 让路，见 §26.4）。
* 触发这次退化的**根因**（浏览器在持续 crawl 负载下变得不可用：`BrowserUnavailableException`、
  `Timeout to wait for document ready`）在引擎/驱动池一侧，本轮没有动 —— 本轮只是让它不再伪装成一行。


## 19. `CrawlParallelTabsTest#testSequentialControlRunDoesNotOverlap` 在 CI 上超时（4.13.x，已于 v4.13.20 修，见 19.6）

### 19.1 现象

v4.13.19 发布之后补派了一次真正的门禁（`gh workflow run ci.yml --ref 4.13.x`，
run [35090001184](https://github.com/platonai/Browser4/actions/runs/35090001184)，
commit `ae250c3a9`）。整套测试只有一处红，且两次尝试（含 `gh run rerun --failed`）**同一用例、同一原因**：

```
[ERROR] CrawlParallelTabsTest.testSequentialControlRunDoesNotOverlap -- Time elapsed: 240.4 s <<< ERROR!
java.lang.IllegalStateException: Crawl eb660148-… did not reach a terminal state within 4 minutes, last: PROCESSING
  at CrawlParallelTabsTest.waitForTerminal(CrawlParallelTabsTest.kt:290)
```

`waitForTerminal` 的 4 分钟上限就是那条线的判定点：crawl 一直停在 `PROCESSING`，没有终态。

### 19.2 为什么不是这一轮改动引起的

1. **不在改动路径上**：该类的 crawl 请求体只有 `url/urls/args/depth/parallelTabs`，**不带 `sql`**，
   所以 `executeCrawlSqlQuery` 根本不会被调用；`CrawlRoundRunner` 的编辑全部位于
   `if (request.sql != null)` 之内，`XSqlExecutor` / 封印 / 冻结同样不可达。
2. **测试类本身没变**：`git diff 2d8e0d627..ae250c3a9 -- …/CrawlParallelTabsTest.kt` 只有 3 行 import
   （package split 的 `rest.api.service` → `rest.api.service.crawl`），5 个用例内容与上一次全绿的
   CI（`v4.13.19-ci.4`）完全一致。
3. **本地同 commit 通过**：`-Dtest=CrawlParallelTabsTest` → **5 / 0 / 0，91.2 s**；只跑失败那条方法 → **63.9 s**。
4. **本轮新增的两个测试不会拖慢它**（共享 Spring 上下文/浏览器的假设被直接证伪）：

   | 本地运行 | `CrawlParallelTabsTest` |
   |---|---|
   | 单独跑（对照） | 5 / 0 / 0，**91.2 s** |
   | 先跑 `CommandXSqlTest` + `CrawlXSqlE2ETest`（CI 顺序） | 5 / 0 / 0，**78.0 s** |

   后者比对照还快；同一轮里 `CommandXSqlTest` 34.4 s、`CrawlXSqlE2ETest` 7.1 s。

### 19.3 与上一次全绿 CI 的数字对比

| 同一 CI 工作流内的类 | ci.4 `2d8e0d627`（绿） | 本次 `ae250c3a9`（红 ×2） |
|---|---|---|
| `CrawlFixtureMetadataTest` | 321.1 s | 632.8 s / 650.7 s（≈2×） |
| `CrawlParallelTabsTest` | **72.7 s** | **538.1 s / 547.0 s（≈7.4×）** |
| ↳ `testSequentialControlRunDoesNotOverlap` | 包含在 72.7 s 内 | 240.4 s / 240.5 s → 触上限 |

其余类别无异常：`browser4-rest` 单测 404/0/0，`CommandXSqlTest` 2/0/0，`CrawlXSqlE2ETest` 2/0/0，
`CrawlXSqlTest` 9/0/0，`SwarmCrawlFixtureTest` 3/0/0 —— 本轮改动的部分在这个门禁里全绿。

### 19.4 判断

* 这是一条**环境敏感的时序脆弱点**，不是逻辑死锁：本地同代码 78–91 s 跑完，CI 上却是 7.4×；
  而"7.4×"远超同批次邻居类的 2×，说明不是整台 runner 变慢，而是这类抓取在 CI 上停顿。
* 停顿的量级与成因方向：`parallelTabs=1` 的 4 seed 顺序抓取 + 240 s 上限 ≈ **4 × 60 s**，
  与仓库已知的 driver-scarcity 停顿（拿不到 driver 时按 60 s 量级干等，见 CLAUDE.md/§16 相关记录）同一量级；
  该类在它之前还跑了 `CrawlFixtureMetadataTest`（本轮 2× 于 ci.4）与 `SwarmCrawlFixtureTest`，同 JVM 共享
  浏览器与驱动池。
* **不能**据此说"这是已证明的既有 flake"：ci.3/ci.4 是绿的，所以它是"与本次改动无关、但在 CI 上连续可复现"
  的脆弱点。范围里唯一与无 SQL crawl 路径相关的运行时代码是 §16–§18 那三个提交
  （`7ffc190e56` 结算/会话生命周期、`298b4f31dc` 未交付页面、`13096ebbbb` 拆分）；若继续挖，lead 在那里。
* 已发布内容不受影响：`release.yml`（构建/6 平台产物/runtime bundle/npm/3 平台 smoke/GitHub Release）全绿，
  ci.yml 是事后补派的质量门禁，两者互不阻塞。

### 19.5 仍未做

* **超时时的诊断**：`waitForTerminal` 只报了 `last: PROCESSING`，没有带上该 crawl 最近的状态/日志，
  所以"卡在哪"只能去翻 CI 日志。下一轮应让它在超时时把任务状态、`pagesExpected/pagesFound`、
  最近的 WARN/ERROR 摘要一起抛出来。
* **上限的合理性**：4 分钟是这条用例写死的；本地 63.9 s、ci.4 整类 72.7 s，健康区间离上限有 3× 余量，
  但 CI 负载下的停顿会把它吃掉。要么按类内累计耗时调大，要么让该类拥有独立上下文/独立超时策略。
* **（§23 已做）驱动池在该类里的分配日志**（谁占着 driver、谁在等、等了多久）没有拉出来对照，这是把"环境停顿"
  坐实成"驱动池饥饿"的最后一步。

### 19.6 修复（v4.13.20，2026-09-20）

v4.13.20 的门禁（标签 `v4.13.20-ci.1`，run 35497172762）**两次尝试分别挂在两个用例上**，
两者是同一个根因的两个面：**`status` 字段混用两套词表**，而测试的等待循环只认其中一套。

`CrawlService` 既写裸 token（`status = "PROCESSING"`，两处），又写 `ResourceStatus` 可读文本
（`getStatusText(...)` → `"OK"` / `"Request Timeout"` / `"Created"`，多处），而 `CrawlResponse.status`
的默认值是 token `"CREATED"`。

| 用例 | 现象 | 根因 | 修法 |
|---|---|---|---|
| `CrawlServiceTest#an exhausted task budget reports the seeds it never started` | attempt 1 红：`expected: <Request Timeout> but was: <Created>`，`Time elapsed: 0.008 s` | `awaitTerminal` 的守卫只比较大写 token，而服务结算写的是可读文本 `"Created"` → **首次轮询即退出循环**，断言立刻失败（本地靠"结算够快"侥幸通过） | 大小写无关比较，并以 `CrawlResponse.finishTime` 作为权威终态标记；轮询上限 10 s → 30 s（任务自身预算是 10 s） |
| `CrawlParallelTabsTest#testSequentialControlRunDoesNotOverlap` | attempt 2 红：`240.4 s` 触上限，`last: PROCESSING`（与 §19.1 同用例、同 240.4 s） | 4 分钟写死上限被 CI 负载吃掉（本地 63.9–91.2 s，CI 同比 7.4×）；另外 `isTerminal` 只认 `"SC_REQUEST_TIMEOUT"` / `"SC_INTERNAL_SERVER_ERROR"` 这类**服务从不产出的拼写**，真超时的任务永远不会被识别为终态 | 上限 4 → 10 分钟（对健康值 ~7× 余量，仍有界）；终态判定同时接受 token 与可读文本并接受 `finishTime`；超时消息带上任务自身账目（status / pages / links / parallelTabs / waiting / error / diagnostic / seeds） |

`19.5` 的前两条（超时诊断、上限合理性）随之落地；第三条（驱动池分配日志）仍未做，
所以"这是驱动池饥饿"仍属假设——若 10 分钟上限再被吃掉，新消息里的
`waiting=` / `seeds=` 会直接指出卡在哪个 seed。

本地验证（2026-09-20，Windows，JDK 25 / GraalVM）：

| 命令 | 结果 |
|---|---|
| `-pl browser4-rest -am -Dtest=CrawlServiceTest` | **11 / 0 / 0，3.869 s**（该用例 0.112 s） |
| `-pl browser4-tests/browser4-rest-tests -am -DrunRestTests=true -Dtest=CrawlParallelTabsTest` | **5 / 0 / 0，126.2 s**，BUILD SUCCESS |

**未做（留给后续）**：服务侧统一状态词表。测试侧已兼容两套，但同一个字段继续混用
token 与可读文本仍会持续制造这类陷阱——根治应在 `CrawlService` 出口统一
（例如一律写 `getStatusText(...)`，或一律写 token），并同步 CLI 的解析。

### 19.7 门禁测试预算：35 → 50 分钟（v4.13.20，2026-09-20）

`v4.13.20-ci.2`（run [35507033919](https://github.com/platonai/Browser4/actions/runs/35507033919)）
是 19.6 的修复上线后的第一次门禁。结果是**零失败**，但仍然是红的：

```
Total Tests: 2018   Failed: 0   Passed: 2005   Skipped: 13
⏱️ Tests timed out after 2100 seconds (limit 2100s) — the reactor was killed, results are incomplete
```

原因不在测试：`Run Tests` 走 `./.github/actions/run-tests`，它用 `timeout 2100` 包住
Maven；`exit 124` 被映射成 `status=timeout`，于是 `Check Test Status` 打印
"Failed Tests: 0" 却仍然 `exit 1`。**这是预算问题，不是结果问题。**

被谁吃掉（同一 run 的时间线）：

| 时间 | 事件 | 耗时 |
|---|---|---|
| 11:13:32 | 测试步骤开始 | — |
| 11:18:14 | 快速单测段结束 | ~5 min |
| 11:29:23 | `CrawlFixtureMetadataTest` 完成 | **668.8 s** |
| 11:40:36 | `CrawlParallelTabsTest` 完成（19.6 修复后 5/0/0） | **671.7 s** |
| 11:40:42 | `ScrapeServiceTests` 开始 | — |
| 11:48:32 | `timeout` 杀进程 | 该类 **8 分钟无输出** |

即两个 crawl 集成类各 ~11 分钟（合 22 分钟），随后 `ScrapeServiceTests` 长时间无输出，
35 分钟预算见底。注意 19.6 的修复本身让诚实耗时 **+2 分钟**（`CrawlParallelTabsTest`
原先在 240 s 报错退出，现在会真正跑完 671.7 s）——预算本来就贴边，这一改把它顶破。

**处置**：`.github/workflows/ci.yml` 的 `timeout_minutes` 由 `'35'` 提到 `'50'`，
并在该行上方写明依据。取 50 而不是更大，是因为当前整套约 40 分钟；
若下一轮仍被 kill，说明还有**卡住**的类（而不是"慢"的类），那就该先去查
`ScrapeServiceTests` 为何 8 分钟不产出，而不是继续加预算。

**仍未做**：`ScrapeServiceTests` 在 CI 上长时间无输出的原因（本轮没有它的失败日志可看，
因为它从未跑完）；以及 19.5 第三条的驱动池分配日志。

## 20. `ScrapeServiceTests` 慢 13 分钟 + crawl 状态词表混用（v4.13.20 后修）

两项都来自 §19 的收尾清单，一起修。

### 20.1 `ScrapeServiceTests`：13.2 分钟不是"卡住"，是**每次加载都重新注入运行时**

绿的那次门禁（run 35514175379）里，该类是整套最慢：`Tests run: 4 ... Time elapsed: 794.7 s`
（13.2 分钟，1 个用例因无 LLM key 跳过）。本地同 commit 也慢：**168.0 s**，而两个 scrape
各自只用了 **1.69 s / 1.60 s**。时间线（本地日志，按时间戳做间隔分析）：

| 现象 | 数据 |
|---|---|
| Spring 上下文启动 | 11.968 s（`Started ScrapeServiceTests in 11.968 seconds`） |
| 页面加载耗时 | **列表页 `/ec/b?node=...`（101 个商品）每次 ~32 s**（32.07 / 31.80 / 33.36），详情页 `/ec/dp/...` 每次 ~3 s（2.71 / 3.16 / 3.57） |
| 间隔成因 | 每轮之间固定 13–33 s 的停顿，对应 `IsolatedWorldManager - Injecting Browser4 runtime (v1.0.0) into isolated world`（本地全程 **32 次**），`CoreMetrics` 同期为 `Fetched 2 pages in 1m (0.03 pages/s)` |

列表页每次重新抓取（日志里 `last fetched 35s ago, fc:2` / `fc:3`，缓存没帮上忙），所以
三个用例各自付一次 ~32 s。`IsolatedWorldManager` 不在本仓库（在 `pulsar-browser` 依赖里），
**注入本身我们改不了**，能改的只有"少加载"。

顺带发现一个**死钩子**：

```kotlin
@BeforeEach
@DisplayName("Ensure resources are prepared")
suspend fun ensureResourcesArePrepared() { ... }   // 编译后签名为 (Continuation) -> JUnit 无法调用
```

`javap` 证实编译结果是 `ensureResourcesArePrepared(kotlin.coroutines.Continuation)`，且没有无参重载
——JUnit 永远不会执行它。也就是说它声明的"每个用例前准备好页面"从未发生（真发生了反而会
每个用例多付两次 ~32 s）。

**修法**（`browser4-tests/browser4-rest-tests/.../ScrapeServiceTests.kt`）：

* 删掉死钩子，并在类 KDoc 写清"为什么这里故意没有 `@BeforeEach`"，防止有人再加挂起钩子；
  `MockEcServerTestBase.setup()` 仍会在每个用例前校验 mock server。
* 两个只断言 `dom_base_uri(dom)` 的用例改抓**详情页**（~3 s）而不是 101 商品的列表页（~32 s）
  —— 断言的契约是"同步/异步抓取返回正确 base URI"，与页面身份无关；列表页的抓取由
  `CrawlFixtureMetadataTest` / `CrawlParallelTabsTest` 覆盖。
* 异步用例的轮询由"1 秒 × 最多 120 次（417 时再来一轮最多 120 次）"改为**deadline 驱动 + 250 ms 间隔**
  （`awaitScrapeJob`），最坏上限仍是 2 分钟，但常见路径不再白等整秒。

本地实测（同一台机器、同一 commit 家族）：

| 运行方式 | 之前 | 之后 |
|---|---|---|
| 单独跑 `-Dtest=ScrapeServiceTests` | **168.0 s**（4/4） | **65.65 s**（4/4）——**2.6× 提速** |
| 与 `CrawlParallelTabsTest` + `CrawlFixtureMetadataTest` 同 JVM 连跑 | — | 247.7 s（三个类同 JVM，前两类各 ~11 分钟） |

### 20.1.1 仍未解决的部分：负载下"抓取本身就慢"

单独跑只有 65.65 s，但**同 JVM 跑完两个重类之后**，同样的详情页抓取要 **90–113 s**
（`Task 164 ... got 200 14.93 KiB in 1m52.74s`，一次 scrape `used PT3M48.5850761S`）。
慢 fetch 邻域日志显示原因不是等待，而是**中途整套新建浏览器/隐私上下文**：

```
BrowserFileSystem - User data dir does not exist, copy from prototype | ...\browser4-pereg\context\tmp\groups
ChromeLauncher   - DevTools listening on ws://127.0.0.1:20455/devtools/browser/...
DualWorldScriptLoader - Generated js: ... page-world.gen.js / isolated-world.gen.js
IsolatedWorldManager  - Injecting Browser4 runtime (v1.0.0) into isolated world context 2 / context 4
MultiPrivacyContextManager / WebDriverPoolManager - Maintaining service is started  (再一次)
ScrapeService - X-SQL: pre-load of '.../ec/dp/B0E000001' before first attempt failed: null
```

即 `browser.context.number=2` 的两个上下文被前序重类占满后，后续 fetch 会**新建上下文乃至新建
Chrome**（拷贝 prototype profile → 启动 → 重新生成并注入双世界 JS），单次数十秒。这一点
`CoreMetrics` 也印证：`Fetched 67 pages in 23m (0.05 pages/s)`。

**已排除的假设**：60 秒驱动租约等待。`PrivacyManagedBrowserFetcher.DRIVER_LEASE_TIMEOUT_MILLIS = 60_000`
确实与 §19.4 的"4 × 60 s"算术吻合，但本地两轮日志里该告警**出现 0 次**
（`is busy with another fetch for more than ...`），租约的 acquire/release 也是成对的
（`tryAcquire` + `finally { release }`），因此不是它。

**已查明（2026-09-21 补充）——剩下的成本不在本仓库，且隔离是有意为之**：

* **注入类在外部依赖里**：`IsolatedWorldManager` 与 `DualWorldScriptLoader` 在本仓库中**不存在**
  （`IsolatedWorldManager.kt` / `DualWorldScriptLoader.kt` 全仓无匹配），所以"每次导航重新注入"的
  单次成本在这里改不了。
* **本仓库这一侧的路径已经做了缓存**：`Browser4WebDriver.ensurePulsarUtilsInjected`
  （`browser4-core/browser4-browser/.../Browser4WebDriver.kt:1634-1685`）会先探测 `__pulsar_utils__`，
  命中就复用已缓存的 isolated-world context id（源码注释：*"id is returned and reused, and the
  runtime is injected only when the …"*）。即重复注入是**导航丢弃执行上下文后的必要动作**，不是缺陷。
* **浏览器启动次数已归因**：3 类那次本地运行共 4 次 `DevTools listening`（12:16:12、12:16:47、
  12:38:40、12:39:01），后两次落在 `ScrapeServiceTests` 窗口内——即该类因
  `@DirtiesContext(BEFORE_CLASS)` 每次都要重建上下文与浏览器，约 **40–60 s**。这与"单独跑 65.65 s"
  互相印证：去掉隔离确实能再省一块，但该注解是为了防前序用例 `kill_all_sessions` 关掉浏览器而存在的
  （见该类内注释），**不应为提速牺牲**。
* **已否证**：60 秒驱动租约等待（本地两轮日志 0 次告警）；服务端残留 token 消费者（全仓扫描仅命中
  整数状态码与其它子系统）。

### 20.3 验证状态（截至 2026-09-21，`v4.13.21-ci.1` 门禁 **success**）

| 层 | 证据 | 结果 |
|---|---|---|
| 本地 · 第 2 项 | `browser4-rest` crawl 单测 6 个类 | **88 / 0 / 0** |
| 本地 · 第 2 项 | `cargo test --bin browser4-cli`（含新增 display-text 用例） | **1209 / 0** |
| 本地 · 第 2 项 | 真实持久化 JSONL（29 条记录） | **单一句表、零 token**：Created 10 · Processing 9 · OK 8 · Internal Server Error 1 · Request Timeout 1 |
| 本地 · 第 2 项 | 全仓消费者扫描（.kt/.rs/.ps1/.js/.ts/.json/.md） | 无遗漏（命中项均为整数状态码或其它子系统） |
| 本地 · 第 1 项 | `ScrapeServiceTests` 单独跑 | **168.0 s → 65.65 s**（4/4 通过） |
| CI · 第 2 项 | browser4-rest 模块（run 35568764699） | **404 / 0 / 0**，含改动的 5 个单测类 |
| CI · 第 2 项 | `CrawlXSqlE2ETest` | **2 / 0 / 0**（7.23 s） |
| CI · 第 2 项 | Cross-Platform Smoke Test（标签 + 分支各一次，同 SHA） | **success / success** |
| CI · 第 1 项 | `ScrapeServiceTests`（run 35568764699） | **8.55 s**（4 个用例，1 个因无 LLM key 跳过） |
| CI · 全部 | 门禁 `CI/CD Pipeline`（tag `v4.13.21-ci.1` = `8ac4d359d`，run [35568764699](https://github.com/platonai/Browser4/actions/runs/35568764699)） | **success**：`Total 2164 / Passed 2108 / Skipped 56 / Failed 0`；`Check Test Status` 通过；CLI E2E 通过 |

#### 20.3.1 第 1 项的收益在 CI 上兑现（−786 s 全落在目标类上）

基线取**上一次绿门禁** `v4.13.20-ci.3`（run [35514175379](https://github.com/platonai/Browser4/actions/runs/35514175379)），
同一个 `ci-build` job、同一个 `Run Tests` 步骤、同一份排除清单，逐项同口径对比：

| 项 | v4.13.20-ci.3 | v4.13.21-ci.1 | Δ |
|---|---|---|---|
| `ScrapeServiceTests` | **794.7 s** | **8.55 s** | **−786.2 s（−98.9%）** |
| `CrawlFixtureMetadataTest` | 656.8 s | 714.6 s | +57.8 s（+8.8%） |
| `CrawlParallelTabsTest` | 638.7 s | 672.1 s | +33.4 s（+5.2%） |
| `Run Tests` 步骤 | **2 585 s（43 分 05 秒）** | **1 807 s（30 分 07 秒）** | **−778 s（−13 分）** |
| `ci-build` job 全程（含 Docker 构建 + CLI E2E） | 3 595 s（59 分 55 秒） | 3 234 s（53 分 54 秒） | −361 s（−6 分） |
| 用例账目（Total / Passed / Skipped / Failed） | 2164 / 2108 / 56 / 0 | 2164 / 2108 / 56 / 0 | 完全相同 |

三点结论：

* **收益只在目标类上，且几乎不多不少**：单类省 786.2 s、测试步骤省 778 s，差 8 s 落在步骤内噪声里；
  两个 crawl 类合计 +91 s 是它们自身的负载波动（最近三次门禁里分别在 656.8–714.6 s 与
  638.7–672.1 s 之间），本次改动没有碰它们的代码路径，两类本轮也都绿。
* **§19.7 的 50 分钟预算现在有明显余量**：`Run Tests` 只用 30 分 07 秒，**35 分钟的旧预算也已够用**
  （`ci.2` 那次正是被 2100 s 杀掉的）。即便如此仍建议保留 50 分钟：两个 crawl 类仍占 23 / 30 分钟，
  单类波动 ±10%，留 20 分钟余量比贴着上限再赌一次便宜。
* **门禁覆盖到 `8ac4d359d`**：其后的 `9126e70c0c`（`CrawlResponseTest` / `CrawlServicePersistenceTest`
  改用 `CrawlStatus` 常量，外加本文档）没有门禁记录——这是机制而非遗漏：`ci.yml` 只在
  `v*.*.*-ci.N` / `v*.*.*-rc.N-ci.N` 标签上触发，分支推送只跑 Cross-Platform Smoke Test。
  该提交涉及的两个类本地 **17 / 0 / 0** 与 **5 / 0 / 0**，会随下一个 `-ci.N` 标签（或发布流水线）一并覆盖。


**因此本仓库内可安全优化的部分已经做完**：死钩子、列表页→详情页、轮询方式，实测 168.0 s → 65.65 s。
再往下需要动上游注入实现，或重新评估测试隔离策略——两者都超出本次范围。



### 20.2 crawl 状态词表：一个状态一个拼写

服务端 `CrawlResponse.status` 同时存在两套词表：裸 token（默认值 `"CREATED"`、
worker 写的 `"PROCESSING"`）与 `ResourceStatus` 可读文本（`"OK"`、`"Request Timeout"`）。
代价在 CLI 上最明显 —— `friendly_crawl_status` 只匹配 token：

| 服务端实际发出 | 旧 CLI 映射结果 | 应为 |
|---|---|---|
| `Created` | `created` | `queued` |
| `Request Timeout` | `request timeout` | `failed (timeout)` |
| `Internal Server Error` | `internal server error` | `failed (error)` |
| `Not Found` | `not found`（`contains("NOT_FOUND")` 不匹配） | `failed (not found)` |
| `OK` | `completed` ✅ | `completed` |

**修法**：

* `browser4-rest/.../crawl/CrawlModels.kt` 新增 `object CrawlStatus`——`CREATED` / `PROCESSING` /
  `OK` / `REQUEST_TIMEOUT` / `INTERNAL_SERVER_ERROR` / `NOT_FOUND`（全部由
  `ResourceStatus.getStatusText(...)` 生成）、`TERMINAL` / `RUNNING` 集合与 `isTerminal` / `isRunning`。
  可读文本是权威形式，因为 REST 载荷对已结算任务一直用它。
* `CrawlResponse.status` 默认值改用 `CrawlStatus.CREATED`；`CrawlService` 的 12 处写点与
  `terminalStatuses`、`CrawlSupport` 的 1 处写点全部改用常量（`ResourceStatus` 导入随之删除）。
* 测试改从常量/谓词断言，不再写死拼写：`CrawlResponseTest`、`CrawlServicePersistenceTest`、
  `CrawlParallelBudgetTest`、`CrawlSupportTest`、`CrawlServiceTest`（`isStillRunning`）、
  `CrawlParallelTabsTest`（`isTerminal`）、`CrawlFixtureMetadataTest`、`CrawlXSqlE2ETest`。
* CLI `friendly_crawl_status` 改为接受**两套拼写**（可读文本优先、token 兼容旧载荷），
  并新增 `friendly_crawl_status_accepts_display_text` 覆盖上述四种曾被误标的状态。
* 文档：`skills/browser4-cli/references/crawl.md` 的 `crawl status` / `crawl result` 段改为列出
  真实线上值与该映射。

**仍未做**：`IsolatedWorldManager` 的注入为何每次加载都要重来（依赖内实现，需要上游看）；
驱动池分配日志（19.5 第三条）。



## 21. 发现链接的去重与 URL 整形：重复 href 吃掉 `--top-links` 预算、`--ignore-url-query` 不生效（4.13.x，§16.4 第 5 条）

§16.4 第 5 条把两件事写在一起：**发现链接未去重**，以及**不套用 `--ignore-url-query` / `--no-norm`**。
本轮按"先量、再改"的顺序做。

### 21.1 探针：一个"重复链接"的 fixture，把三件事一次量出来

新增 fixture `pulsar-tests-common/.../static/generated/crawl/dup/hub.html`
（5 个目标、8 个锚点：图片与标题指向同一个详情页、grid/list 两个 query 拼写指向同一页、
最后一个带 `#specs`），配 `CrawlLinkDiscoveryTest`（`IntegrationTest`，真实浏览器 + REST API）。
**改动前**实测（本机，`--depth 2 -ol "a.pick"`）：

| 场景 | 改前实测 | 应有 |
|---|---|---|
| `-topLinks 3` | **3 行**：hub + `product/1.html` + `product/2.html` —— 8 个锚点里 `[1,1,2]` 先占满 3 个预算槽，实际只排队了 2 个不同页面 | 4 行：hub + product/1,2,3 |
| `-topLinks 20 -ignoreUrlQuery` | 行 URL 仍是 `…/product/4.html?src=grid` —— 标志对发现链接无效 | 行 URL 无 query |
| `-topLinks 20`（两种拼写） | 6 行，`?src=grid`（首个拼写）胜出、`#specs` 已被剥掉 | **同**（这一条改前就是对的） |

第三条是重要的对照组：它说明**身份去重与 fragment 剥离早就是对的**，坏掉的只是"预算前的去重"和
"`--ignore-url-query` 在哪一层生效"。第二条还不是"少抓页"而是**报错的口径**：`normalizeForVisit`
早已把 query 折进同一个身份，排队的 URL 却保留 query，于是行里报的 URL 不是真正抓的那个。

### 21.2 修法：一个共用的选择器，两条发现路径不能再分叉

* `CrawlSupport.selectDiscoveredLinks(hrefs, visited, outLinkPattern, topLinks, ignoreUrlQuery)`
  —— 顺序是**整形 → 模式过滤 → 身份去重 → 已访问过滤 → 预算**，返回 `DiscoverySelection`
  （`links` + `filtered` / `repeated` / `alreadyVisited` / `overBudget` 四个桶）。四个桶是有意的：
  日志的 "N of M anchor(s) … were not queued" 必须能把 M - links 拆开说清，否则"预算被重复链接吃掉"
  看起来和一次正常运行没有区别；`links.size + skipped == M` 也由单测钉住。
* `crawlDepthN`（depth ≥ 2）与 `extractOutLinks`（depth 1）**都**改用它。这正是 §16.4 #5 的另一半：
  两条路径此前各写一份——depth 1 做了 `.distinct()`（按拼写）+ query 剥离，depth-N 什么都没做，
  于是同一页面在不同 depth 下的行为不一致。
* fragment 在整形阶段**无条件**剥掉（与引擎 `parseNormalizedLink` 一致：无论 `--no-norm`，
  fragment 都不参与身份），`#specs` 这类拼写不再进入模式/去重/预算的判断。
* `buildLinkArgs(options, expandable)` 从 `CrawlRoundRunner` 的私有方法移到 `CrawlSupport`，
  并补上 `-ignoreUrlQuery` / `-noNorm` 的转发：**发现页由 session 加载，没进 args 的选项就等于不存在**
  （`-readonly` 当初正是因为同样的原因漏在 depth ≥ 2 之外）。depth-1 的链接参数也改用同一函数
  （`expandable = false`：depth-1 不展开子页，不需要 out-link 选择器）。

### 21.3 验证

| 层 | 证据 | 结果 |
|---|---|---|
| 本地 · 单测 | `browser4-rest` 8 个 crawl 单测类 | **113 / 0 / 0**（`CrawlSupportTest` 29 → 41，新增 11 个选择器 + 3 个参数转发用例） |
| 本地 · 集成 | `CrawlLinkDiscoveryTest`（真实浏览器，3 条 crawl：预算 / 两种拼写 / `-ignoreUrlQuery`） | **4 / 0 / 0，130.6 s**（含继承的 hello 用例）。改前同一份测试 2 红（`-topLinks 3` 得 3 行、`-ignoreUrlQuery` 行里带 query） |
| 本地 · 集成 | `CrawlFixtureMetadataTest`（既有 10 页 depth-2 fixture） | **5 / 0 / 0，667.4 s**（CI 基线 714.6 s，本地同量级） |
| 本地 · 集成 | `SwarmCrawlFixtureTest` | **3 / 0 / 0，4.2 s** |
| 文档 | `skills/browser4-cli/references/crawl.md` | `--top-links` 一行改为"**distinct** pages one page may contribute"；"URL deduplication" 段补上四条可观察契约（预算先去重、fragment 不进 URL、首个拼写胜出、`--ignore-url-query` 作用于排队前的 href） |

**仍未做（记录边界）**：

* `--no-norm` 只有**代码层**证据：它的转发路径有单测钉住参数字符串（`buildLinkArgs`），
  但 MockSite 上找不到一个"归一化会改变、改后仍能抓通"的 URL（大小写路径在 Linux 会 404，
  默认端口/大写主机名在 fixture 里不存在），因此没有浏览器层断言。
* `--ignore-url-query` 对**种子 URL** 的影响（`CombinedUrlNormalizer` 在加载种子时也会剥 query）
  不在本轮范围：那是引擎侧行为，且与 coworker 报告里的另一条 issue 重合，需要单独判断
  "种子是否应当原样抓取"。



## 22. 在途进度视图：只报"最后一轮"、且无锁 read-modify-write（4.13.x，§16.4 第 3、4 条）

两条同族：都不是丢页，而是**派生出来的在途视图**既不聚合（第 3 条）也不互斥（第 4 条），
共同破坏同一个承诺——*运行中的计数只增不减、且不超过终态值*。

### 22.1 探针：多种子 crawl + 150 ms 轮询，把回落量出来

新增 `ConcurrencyProbeController` 的 `/__probe/hub/{id}?links=N&delayMs=M`（hub 自身立即返回、
它的链接指向慢页面，于是每一轮都持续几秒并多次发布）与 `CrawlInFlightProgressTest`
（真实浏览器，150 ms 轮询 `/api/crawl/{id}/result`）。

**改动前**（3 个种子、每 hub 3 条 600 ms 链接、`parallelTabs=1`，共 9 页）的轨迹只列变化点：

```
pages=0/0 links=0 → 1/0 → 2/0 → 3/3 links=3          ← 种子 A 结算（聚合值 3）
→ 1/3 links=6 → 2/3 → 6/6 links=6                    ← 回落 3 → 1；随后 B 结算
→ 1/6 links=9 → 2/6 → 9/9 OK                         ← 又回落 6 → 1
```

断言直接给出证据：`pagesFound fell back from 3 to 1 while the crawl was running`。
`pagesExpected` 同期从 0 跳到该轮的值（它只统计**已结算**种子，属预期口径，见 22.4）。

第 4 条（并发发布的 read-modify-write）在探针里**没有**稳定复现——窗口只有一次 get/put 的宽度，
靠轮询撞上它属于撞运气。所以它是**按结构修**的，并由第 3 条的不变量在真实 crawl 上兜底。

### 22.2 修法：一个聚合视图 + 一条加锁写入路径

* **聚合**：`CrawlTaskContext.publishedPages: MutableMap<Int, List<CrawlPageResult>>`（按 seed index），
  记录里的 pages = `CrawlSupport.aggregateInFlightPages(...)` = 各轮**最新一次**发布按 seed 顺序拼接。
  *为什么不是按 URL 求并集*：终态记录一行 = 一次抓取，同一 URL 被两个种子各抓一次就是两行
  （`SeedCollection.pages` 直接拼接各轮 pages），并集会让在途值**小于**终态值——把"计数回落"换成了
  "最后一步长大"。这条规则由单测钉住（`testAggregateIsNotAUrlUnion`）。
* **单写入路径**：`CrawlProgressSink` 由"每个 crawl 一个、按 taskId 发布"改为**每轮一个**
  （`SeedProgressSink(task, seedIndex)`，作为参数传给 `crawlDepth1` / `crawlDepthN`）；
  服务侧只剩一个 `publishInFlight(task, seedIndex, pages, linksDiscovered, diagnostic)`，
  在 `synchronized(task.publishLock)` 内完成"记下本轮 pages → 聚合 → 合并旧记录 → 写回"。
  `recordSeedProgress`（种子结算）也把该轮的 pages 写进同一张表、并改用同一个聚合视图，
  于是两个发布者报的是**同一个数**（此前一个是"已结算轮之和"、另一个是"当前轮"，这正是回落来源）。
* **顺带修掉一个更小的同类问题**：`publishDiagnostic`（某轮没发现可跟进链接时）原先**整条重建**记录
  并把 `status` 写成 `OK`——多种子 crawl 里只要有一个种子没有 out-link，轮询方就会看到整个任务"已完成"。
  现在它走同一条合并路径（`PROCESSING` + diagnostic），终态仍由 `writeCompleted` 写。
* §16.3 的语义保持不变：终态记录**丢弃**晚到的发布（`mergeIncrementalProgress` 返回 null 的那条路径）。

### 22.3 验证

| 层 | 证据 | 结果 |
|---|---|---|
| 本地 · 单测 | `CrawlSupportTest` 新增 3 条（求和 / 非 URL 并集 / seed 顺序） | **44 / 0 / 0**（原 41） |
| 本地 · 单测 | `browser4-rest` 全部 crawl 单测类（8 个类） | **116 / 0 / 0** |
| 本地 · 集成 | `CrawlInFlightProgressTest`：顺序 3 种子（9 页）与 2 路并行（6 页）各全程 150 ms 轮询 | **3 / 0 / 0，145.9 s**（改前同用例在 `pagesFound fell back from 3 to 1` 处红） |
| 本地 · 集成 | `CrawlParallelTabsTest`（多种子并行 + depth-1 两轮共享 out-link 的既有用例） | **5 / 0 / 0，105.0 s** |
| 本地 · 集成 | `CrawlFixtureMetadataTest`（depth ≥ 2 的 parse handler 发布路径） | **5 / 0 / 0，625.0 s** |

改后同一探针的轨迹（顺序用例）变成单调：

```
pages=0/0 → 1/0 → 2/0 → 3/3 → 4/3 → 5/3 → 6/6 → 7/6 → 8/6 → 9/9 OK
```

`CrawlInFlightProgressTest` 现在的断言就是这两条的验收条件：运行中 `pagesFound` / `pagesExpected` /
`failedPages` 各自单调不减、在途值不超过终态值、且确实观测到视图在增长（至少 3 个不同计数——
只采到最后一个样本的 run 什么也证明不了）。

### 22.4 边界与仍未做

* **`pagesExpected` 是"已结算种子"的口径**（不是一开始就报总数）：深爬在跑的时候它会随种子结算
  阶梯式上升。要让它在起跑时就是总数，得先在 REST 契约里定义"还没提交的页算不算已承诺"，
  所以本轮刻意保留现状，只保证**不回落**。
* **取消/超时任务不满足"在途 ≤ 终态"**：终态是"已返回轮次的 pages + 每个未结算种子 1 条丢失"，
  而在途视图含正在跑的轮次已收集的部分。这是 §17.3 / §19.5 的报账口径，不在本轮范围
  （测试只对正常完成的 crawl 断言该性质）。
* 第 4 条的竞态没有**直接**复现用例（见 22.1）：它由"两条发布路径共用同一把锁与同一个写入函数"
  在结构上消除，并由上述不变量覆盖。



## 23. 驱动池的分配日志：谁在等、等了多久、谁占着（4.13.x，§19.5 第 3 条）

§19.5 第 3 条是"把'环境停顿'坐实成'驱动池饥饿'的最后一步"。此前判断"不是驱动池饥饿"只能靠
**告警缺席**（60 s 租约告警 0 次），而池子自己什么都不说：一个卡在 `poll()` 里的任务，从上面看
和"页面慢"完全一样。

### 23.1 加了什么

都在 `LoadingWebDriverPool`（所有取 driver 的路径都经过它）：

* **计量**：`pollWebDriver` 记录进入/离开时间，等待时长随结果一起上报（在 `finally` 里，成功与
  失败都报）。
* **谁在等**：`poll(priority, conf, event, page)` 把 `page.url` 一路带到上报里——这是爬取路径唯一
  知道"这是哪个任务"的地方；不经过该路径的调用记为 `unknown`。
* **谁占着**：上报当下把 `workingDrivers` 的前 3 个连同它们的当前页面列出来（`#id state url`），
  其余按数量省略（`(+N more)`）——一行日志必须还是一行。
* **为什么**：`driverWaitReason(...)` 把原因分类。`every driver slot is taken`（该加容量）与
  `driver creation is refused: ...`（该等负载回落）是两种不同的处置，混淆就会把真正的饱和读成
  一次瞬时抖动；这个函数与 `shouldCreateWebDriver` 的判定顺序一致。
* **两级，两种用途**：
  * `WAIT_DEBUG_THRESHOLD`（默认 1 s）以上的等待，DEBUG 一行给出精确数字（等待时长 / 拿到的
    driver / 池快照 / 原因 / 等待者 / 占用者）——排查时看的就是这一行；
  * `WAIT_WARN_THRESHOLD`（默认 10 s）以上的等待，额外一条 WARN，**消息只由小集合的值构成**
    （池号、等待倍数桶 `1x/3x/10x/30x`、原因、browserId）。原因是 `ThrottlingLogger` 按
    **渲染后的消息**去重：消息里带 URL 或毫秒数，就等于每次都是新消息，等于没有节流。
* 池耗尽时的异常消息与 INFO 也带上原因与占用者——调用方此前只能看到一句 "exhausted"，无法区分
  "池被占满"和"浏览器根本没起来"。

### 23.2 顺带修掉一个真 bug：亚秒超时被静默吞掉

`poll(priority, conf, timeout: Duration)` 原先用 `timeout.seconds` 转秒，而
`Duration.ofMillis(700).seconds == 0` ⇒ "等 700 ms"变成"不等待、立刻报池耗尽"。爬取路径
（`settings.pollingDriverTimeout`）现在按毫秒传递。这是写诊断测试时被测试逼出来的：探针把超时设成
700 ms，实际只等了 0.15 s。

### 23.3 验证

| 层 | 证据 | 结果 |
|---|---|---|
| 本地 · 单测 | `LoadingWebDriverPoolTest`（新增 4 条：端到端诊断 1 条 + 纯函数 3 条） | **6 / 0 / 0**（原 2 条） |
| 本地 · 单测 | 同包 `ConcurrentStatefulDriverPoolPoolTest` + `ExceptionsTest` | **20 / 0 / 0**、**13 / 0 / 0** |
| 本地 · 集成 | `CrawlParallelTabsTest`（4 种子并行 + depth-1 双轮，真实浏览器） | **5 / 0 / 0，135.1 s** |

端到端那条用例断言的是**可读性本身**（不是"有没有日志"）：强制让资源守卫拒绝创建（与负载尖峰同一
路径），一个等待的调用必须（a）异常消息里带原因与占用者，（b）DEBUG 行里点名等待的页面，
（c）WARN 行里点名池与原因、但**不含**等待者 URL（否则节流失效）。

### 23.4 真实 crawl 的观察：**没有**池饥饿（否证，不是坐实）

同一台机器上跑 `CrawlParallelTabsTest`（4 种子并行、`parallelTabs=4`，另有 depth-1 双轮用例），
按新诊断在应用日志里检索：

| 检索项 | 命中 |
|---|---|
| `A task waited more than …`（≥10 s 等待的 WARN） | **0** |
| `Driver pool is exhausted, rethrow …`（池耗尽） | **0** |
| `Maintaining service is started/closed`（池管理器） | 各 8 次（正常启停） |

即：这些场景里池**从未**让任务等待到 10 s，也从未耗尽——"CI 上 crawl 停顿"在这类负载下**不是**
驱动池饥饿。这与 §20 的结论互相印证：成本在"新建浏览器/隐私上下文 + 重新注入双世界 JS"一侧。

**边界**：1–10 s 之间的等待只在 DEBUG 级别可见，而测试配置里
`ai.platon.pulsar.protocol.browser.driver` 固定为 INFO，所以本轮**只否证了 ≥10 s 的池等待**。
要拿到更细的分布，把该 logger 调到 DEBUG，或临时下调 `WAIT_DEBUG_THRESHOLD`
（两者都是 `var`，不需要改代码）。



## 24. 门禁 `v4.13.21-ci.2`：三笔产品改动转绿，但测试预算只剩 74 秒（4.13.x，2026-09-22）

`v4.13.21-ci.1` 之后有三笔**产品**改动（§21 链接发现、§22 在途视图、§23 驱动池诊断）此前没有被任何
tag 门禁跑过，本轮补跑：

| 项 | 值 |
|---|---|
| tag / SHA | `v4.13.21-ci.2` = `5fce62557f` |
| run | [35646179429](https://github.com/platonai/Browser4/actions/runs/35646179429) |
| 结果 | **success**（19:39:23 → 20:43:21 UTC，63 分 58 秒） |
| 用例账目 | **Total 2190 / Passed 2134 / Skipped 56 / Failed 0**（ci.1：2164 / 2108 / 56 / 0，+26 条） |
| Cross-Platform Smoke Test | success（同 SHA） |

### 24.1 三个慢类的耗时对比（同一 job、同一 `Run Tests` 步骤）

| 类 | ci.1 | ci.2 | Δ |
|---|---|---|---|
| `CrawlLinkDiscoveryTest`（§21 新增） | — | **685.3 s** | +685.3 |
| `CrawlInFlightProgressTest`（§22 新增） | — | **123.3 s** | +123.3 |
| `CrawlFixtureMetadataTest`（**未改动**） | 714.6 s | **956.1 s** | +241.5 |
| `CrawlParallelTabsTest`（**未改动**） | 672.1 s | **708.8 s** | +36.7 |
| `ScrapeServiceTests` | 8.55 s | 8.27 s | −0.3 |
| `CrawlXSqlE2ETest` | 7.23 s | 6.07 s | −1.2 |
| `SwarmCrawlFixtureTest` | 3.12 s | 2.08 s | −1.0 |

### 24.2 预算：`Run Tests` 2926 s（48 分 46 秒），上限 3000 s

| 步骤 | ci.1 | ci.2 |
|---|---|---|
| Maven Build | 163 s | 163 s |
| **Run Tests** | **1807 s（30 分 07 秒）** | **2926 s（48 分 46 秒）** |
| Build Docker Image | 227 s | 239 s |
| Run browser4-cli E2E | 322 s | 323 s |
| `ci-build` job 全程 | 3234 s（53 分 54 秒） | 3834 s（63 分 54 秒） |

+1119 s 里，~809 s 是**新增两个集成测试类**的成本，~278 s 是**未被改动**的两个重类的波动。
余量只剩 **74 秒**——下一次同量级波动就会把门禁打成 `timeout`（正是 §19.7 的失败模式）。

处置：

* **本轮已做（缩小成本）**：`CrawlLinkDiscoveryTest` 由 3 条 crawl 减到 2 条。被删的那条
  （"两种拼写只抓一次"）的契约在单元接缝上已钉住
  （`CrawlSupportTest.testOnePageIsQueuedUnderItsFirstSpelling`），而它的两条断言里
  "fragment 不进 URL"仍由保留的 `-ignoreUrlQuery` 用例逐行断言精确 URL 覆盖。
  该用例在 CI 上约 228 s，预计把门禁压回 ~45 分钟。
* **需要决策**：把 tag 门禁的 `timeout_minutes` 由 50 提到 60（`.github/workflows/ci.yml`）。
  这与 §19.7"不要盲目加预算"不冲突：那次是"没跑起来/卡住"（该先查为什么），这次是**可归因的测试成本**
  加上**可观测的波动**（同一份代码 ±4 分钟）。50 分钟是 v4.13.20 时为 ~30 分钟的负载定的，
  现在这个负载已经涨到 ~45–49 分钟；只有同时收成本 + 留余量，门禁才重新"可预期地全绿"。



## 25. `--readonly` 与 X-SQL 的第二次读：readonly 优先于 refresh（4.13.x，§18.5 第 1 条）

§18.5 第 1 条记的是"强制 `-refresh` 让 `readonlyNote` 的存取分支变成死代码"。本轮的结论是：
`--readonly` **只服务于 X-SQL 执行引擎的第二次读**，所以那些"死代码"不是该删掉的冗余，而是**没接通的接口**。

### 25.1 从日志学到的用法：X-SQL 会读同一页两次

`CrawlXSqlE2ETest` 的真实日志（本轮）：

```
DEBUG CrawlXSql - Crawl X-SQL: froze the round's page for 'http://.../__probe/slow/crawl-sql-seed-<run>'
                 (2248 bytes, document=true)
INFO  CrawlXSql - Crawl X-SQL: executing query on 'http://.../crawl-sql-seed-<run>':
                 select dom_first_text(dom, '#probe-id') as id
                 from load_and_select('http://.../crawl-sql-seed-<run> -readonly', ':root')
INFO  CrawlXSql - Crawl X-SQL: extracted 1 row(s)
```

**第一次读**是 crawl 自己加载页面（随后 freeze 进本地缓存）；**第二次读**是语句里的
`load_and_select()` UDF —— 它只带 `-readonly`，因为封印
（`ScrapeAPIUtils.normalizeForReadOnlyQuery`）把 `refresh/expires/expireAt/itemExpires/itemExpireAt`
按名字擦掉并校验（`checkReadOnlyQuery`）。§20 之前的日志里也见过它的另一半：
`X-SQL: pre-load of '...' before first attempt failed`（`ScrapeService` 的预加载）。

### 25.2 规则：readonly 优先于 refresh（改在哪、为什么）

`-refresh` 不是一个普通选项，它是 `-ignoreFailure -i 0s` 的简写：**任何**本地副本都会被判定为过期，
于是 `AbstractPulsarSession.createPageWithCachedCoreOrNull`（要求 readonly 且未过期）这条捷径被绕过，
UDF 在查询执行期间回到网络并回写存储。两个选项因此不能并存：

* **本轮改动**：新增 `CrawlSupport.resolveRoundArgs`——args 里含 `-readonly` 时**擦掉 `-refresh` 且不再补**；
  `crawlDepth0` 与 depth-1/depth-N 三处统一走它，旧的私有 `buildEffectiveArgs` 删除。
  不带 `--readonly` 时行为不变（仍强制 `-refresh`，因为陈旧/半写的存储副本正是"门户页 0 个外链"
  的成因）。
* 语句内部的封印不变（既有实现 + 既有断言）。

### 25.3 接通接口：readonly 命中存储不再被当成"丢页"

改完规则后 `CrawlFixtureMetadataTest` 的两条 readonly 用例立刻红了，失败信息本身给出了原因：

```
WARN CrawlRoundRunner - the load of '.../index.html' returned no document
     (fetched=false, status=200, contentLength=7706); reporting it as lost
```

内容在（7706 字节，就是存储里那份），只是"这一轮没抓"，而 §18 引入的
`isDocumentDelivered(fetched, html)` 把"没抓"等同于"没收到"。修法是把 readonly 这一种情况显式接上：

* `isDocumentDelivered(fetched, html, storeServed)`：`storeServed` 时文档非空即算送达；
* `isReadOnlyStoreServe(page, readonly) = readonly && page.isCached`：只有 **readonly 轮次**能把存储
  命中当作送达——`-ignoreFailure` 下"抓取失败被存储副本顶替"的老问题（§18）依旧报丢失，
  因为那种轮次必然带 `-refresh`（因而非 readonly）。

于是 §18 留下的两半（`servedFromStore` 标记、`buildReadonlyNote` 的存取分支与 age 文案）第一次真正被走到，
测试也从"两个分支随便哪个"收紧为"必须走存取分支"。

### 25.4 验证

| 层 | 证据 | 结果 |
|---|---|---|
| 本地 · 单测 | `CrawlSupportTest`（新增 3 条 `resolveRoundArgs` + 2 条送达判定） | **49 / 0 / 0** |
| 本地 · 集成 | `CrawlFixtureMetadataTest`（两条 readonly 用例改为断言存取分支） | **5 / 0 / 0，179.4 s** |
| 本地 · 集成 | `CrawlLinkDiscoveryTest`（同一 JVM 连跑，确认 link 参数改动未回归） | **3 / 0 / 0，181.0 s** |
| 本地 · 集成 | `CrawlXSqlE2ETest`（服务器侧计数断言"查询不额外抓一次"） | **2 / 0 / 0，150.4 s**（单独跑 24.4 s） |
| 汇总 | 三类同批 `-Dtest=CrawlFixtureMetadataTest,CrawlLinkDiscoveryTest,CrawlXSqlE2ETest` | **BUILD SUCCESS，10 / 0 / 0** |
| 文档 | `skills/browser4-cli/references/crawl.md` 新增"`--readonly` and the X-SQL second read"；CLI `--readonly` 描述同步 | — |

### 25.5 日志证据（`browser4-tests/browser4-rest-tests/logs/pulsar.log`，末次运行段）

请求侧与生效侧的 args 成对出现，正好把"擦除"钉住（`CrawlController` 打请求原样 args，
`LoadComponent.Task` 打真正下发的 args）：

```
INFO CrawlController - Crawl request: url='.../__probe/slow/crawl-sql-seed-muc40dkb' ... args='-refresh -readonly' sql=true
INFO LoadComponent.Task - 134. 💯 ⚡ U for N got 200 2.1953125 KiB [💿2.1953125 KiB] in 1m22.097s, fc:1 | ... |
                         http://localhost:13196/__probe/slow/crawl-sql-seed-muc40dkb -readonly
```

即 `-refresh -readonly` → `-readonly`：`-refresh` 被擦掉，**页面仍然照抓**（`⚡`、`fc:1`、耗时 1m22s 是
探针的 `delayMs`）。这正是"readonly 不是禁止联网，而是允许命中本地副本"的语义。

其余三条观察：

* **不带 readonly 的轮次照旧带 `-refresh`**：同段内 `/generated/crawl/` 的加载行全部形如
  `... -outLinkPattern product/ -outLinkSelector a.product -parse -refresh`（`dup/hub.html` 的
  `-outLinkSelector a.pick` 同名），默认行为没被改动。
* **readonly 轮次不再产生加载任务行**：该运行段内带 `-readonly` 的 `LoadComponent` 行只有上面这两条
  X-SQL 探针页；两个 readonly fixture crawl 的请求（`11:20:21` 的 `-readonly`、`11:20:25` 的
  `-readonly -refresh`）各自对应 `Crawl task ... completed: 10 pages, 0 lost, status OK`，中间没有
  任何 `-readonly` 加载任务 —— 也就是说两次读里的第二次读直接命中了本地页存储。
  修复前的运行段恰是反例：同一批 URL 每页都留下 `-readonly … -refresh` 的加载任务行
  （本文件 line 2678 / 2680、3245 / 3259 属旧段，可作对照）。
* **"存储命中=送达"真的被走到**：readonly 轮的终态是 `0 lost`，而修复前这里是
  `returned no document (fetched=false, …); reporting it as lost`。

### 25.6 仍未做（本条边界）

* 上面那两条 X-SQL 探针页是 `fc:1`（**第一次读就联网**），所以日志没有覆盖"第一次读也命中旧副本"的
  情形——按语义那是允许的（readonly 只承诺"可以读本地"，不承诺"必须新鲜"），但如果将来要保证
  X-SQL 的第一次读一定新鲜，得由 crawl 侧（而非引擎侧）显式要求，这里留个明确边界。
* §18.5 第 2 条（送达失败后重试）仍未做：需要 ledger 暴露"在途尝试"这一层，属于独立改动。


## 26. 送达失败即重投一次：给 ledger 开一个"在途尝试"的口子（4.13.x，§18.5 第 2 条）

§18.5 第 2 条记的是"本轮只把'没抓到'如实报成丢失，没有加重试"。这一轮补上：一次投递失败之后**再加载
一次同一个 URL**，而且这一轮的 row 由重投那一次产生。

### 26.1 为什么不能简单"再 submit 一遍"

原来丢页是在 **parse 事件**里结算的（`recordFailure(... REASON_NOT_DELIVERED)`），而重投必须发生在
**load 事件**里：只有那里同时握着这次尝试的 token 与协议状态。三层原因，每一层单独都会让"朴素重投"出错：

* `onLoaded` 在 parse **之后**触发（代码里写明的顺序）。parse 已经把 URL 结算成失败，轮次就可能在
  load 事件跑到之前 `complete()`；一旦 terminal，重投只能被拒 —— 结果是"刚决定要重投、又立刻报告丢失"。
  单页 crawl 必然踩中这一条，所以现在的顺序是**先认领重投、再记录失败**，并且 load 事件用
  `enter()/leave()` 持有轮次（它可能提交重投，和 parse 处理器一样必须让轮次保持未完成）。
* ledger 的结算语义是"每个提交 URL 恰好一次"，`recordFailure` 靠 `failedKeys` 幂等。重投意味着同一个
  URL 要有第二次结算机会，于是需要显式**撤单**：撤回失败记录、把结算数还回去。
* 旧尝试的事件在重投期间还会继续到达（重复的 parse 事件、跟在 parse 后面的 load 事件）。它们必须
  **什么都结算不了**，否则重投的 row 会变成第二次结算，撞上 ledger 的 over-count 守卫（"响亮地"提前
  结束轮次）。

### 26.2 ledger：attempt token + 撤单式重投

`CrawlLedger(taskId, maxDeliveryAttempts = 2)`（首次 + 一次重投）新增三个方法：

| 方法 | 作用 |
|---|---|
| `beginAttempt(url): Long` | 每次**提交**（首次或重投）开一个尝试，返回 token |
| `isCurrentAttempt(url, token)` | token 是否仍是该 URL 最新的尝试——被取代的尝试据此自我否决 |
| `startRetry(url): Long?` | 原子地花掉一次重投预算；**并把已结算的失败撤单**（撤回记录 + 归还 settled 计数），返回新 token；预算用完或轮次已终止则返回 null |

`pagesExpected` 不变：一个 URL 提交过一次，就仍然只值一页。

### 26.3 回合内：parse 只负责"看见"，load 负责"决定"

两条丢页路径（`crawlDepth1` 的外链、`crawlDepthN` 的发现子页）现在共用同一套流程：

1. 提交时 `ledger.beginAttempt(url)` 取 token，该次提交的 parse / load 事件都闭包持有它；
2. parse 事件发现"这次没有拿到文档"时**只打标记**（`emptyDeliveries`），不做任何结算；
3. 该次尝试的 load 事件（`resolveDeliveryAttempt`，与 `lossReasonForLoaded` / `claimDeliveryRetry`
   一起放在 `CrawlSupport`，输入是从 `WebPage` 抽出来的 `LoadedPageFacts`，所以可以脱离浏览器单测）
   在做任何结算**之前**先决定要不要重投：
   * **引擎自己还在重试的**（`isRetry` / canceled 状态）什么都不做：第二次加载是引擎排的，轮次只要等它；
   * 其余的失败一律重投一次（预算在 ledger 里），不按协议状态挑 —— 见 §26.4 的探针证据；
   * "内容到了但 parse 没触发"（`REASON_NOT_PARSED` 且 `contentLength > 0`）不算投递失败，不重投；
     而 `contentLength == 0` 的那种（0 字节响应、连 parse 都没跟上）仍然算 —— 重投正是冲它去的；
4. 重投也失败时照旧报丢失，措辞保持原样：`... reporting it as lost`（多带上 `on attempt N`）。

### 26.4 探针：失败答案该长什么样（两次否证）

`ConcurrencyProbeController` 新增两个端点 + 一处计数：

* `GET /__probe/flaky/{id}?failures=N&status=500`：前 N 次用 `status` 应答，之后回真页面；每个 id 的命中
  次数进 `/stats` 的 `flakyHits`（`/reset` 一并清零）。
* `GET /__probe/flaky-hub/{id}?links=N&failures=N`：门户本身永远有内容，只有它链出去的子页会失败
  （门户失败会走"0 外链诊断"那条路，测不到投递）。`failures` 只作用于子页——第一版把它同时用在自己身上，
  深度-2 用例传 `failures=0` 时子页也就从不失败了。

站点侧计数是这里唯一的见证人：对 crawl 而言两次加载是同一个 URL，只有服务器能区分"加载了两次、
第二次成功"与"只加载了一次"。但**失败答案本身**试了两版才站住：

1. **`200` + 空 body 不行**（第一版：两条用例都红，`expected: <2> but was: <1>`）。浏览器导航到一个空响应
   会合成为 `<html><head></head><body></body></html>` —— 日志里量到的那个 **39 B** —— `isDocumentDelivered`
   看到 html 非空，于是"没抓到"变成了"抓到一个空壳页"（row 有了、标题为空），重投压根不存在。
2. **`500` 和 `404` 也不行**：浏览器驱动的加载失败会落在浏览器错误页上，引擎据此判为**可重试**
   （`1601 Retry(1601) rs: BrowserErrorPageException`），并自己排下一次加载：
   `StreamingTaskRunner.Task - 4. 🤺 Trying 1th 37s later | ... fc:1/1 Retry(1601)`，约 37~55 s 后那次
   `💯 🖴 ... last fetched 45s ago, fc:2` 就是它。也就是说：**这类失败根本轮不到 crawl 的重投**，
   用它们做的用例会"不劳而获"地通过。

**结论（决定实现与测试怎么分工）**：引擎自带重投，覆盖"引擎认为可重试"的失败；crawl 的重投只覆盖
**引擎认为已经结束、而 crawl 没拿到**的失败 —— §18 记录的"存储副本顶替了一次失败的抓取"
（`fetched=false, status=200, contentLength=7706`）正是这一类，也是 §18.5 第 2 条真正要修的那一种。
所以：这条规则用 `CrawlSupportTest` 的确定性单测钉住（含"引擎在重试时什么都不做"这一条），端到端那条
用例改为钉**契约**——"第一次加载失败的页面最终仍被交付、且绝不报成丢失"，无论第二次加载是引擎排的还是
crawl 排的。

### 26.5 验证

| 层 | 证据 | 结果 |
|---|---|---|
| 单测 · ledger | `CrawlLedgerTest` 14 → **18**：重投预算、撤单（失败撤回 + settled 归还）、被取代的尝试、轮次已终止时拒绝重投 | **18 / 0 / 0** |
| 单测 · 规则 | `CrawlSupportTest` 49 → **57**：空交付重投、二次失败报丢失、**引擎在重试时什么都不做**、被取代的尝试不结算、终局失败重投并把原因报回来、`NOT_PARSED`（有内容）不重投、0 字节要重投、已记录成功的页面不结算 | **57 / 0 / 0** |
| 集成 · 端到端 | `CrawlDeliveryRetryTest`（新增 2 条：depth-1 外链 / depth-2 发现子页），断言站点侧"每个子页被问了两次"、row 带上交付页的标题、`0 lost` | **3 / 0 / 0，189.4 s** |
| 日志 | `1601 Retry(1601) rs: BrowserErrorPageException` → `Trying 1th 37s later` → 45 s 后 `fc:2` 拿到 200 | 见 §26.4 |

### 26.6 仍未做（本条边界）

* **引擎的重投延迟是轮次预算的隐性开销**：一次失败的加载要先等 37~55 s 才等到引擎排的下一次，而这段时间
  轮次一直在等（`isRetry` 不结算）。页数多的时候这会显著吃掉任务预算，本轮没有动 —— 那是引擎侧的调度，
  不是 crawl 能决定的。
* **crawl 的重投只有一次，且只覆盖引擎判为终局的失败**。要让它也接管"引擎还在重投"的情形，得先决定谁的
  判断更可信（引擎的重试间隔可调吗？值得等吗？），属于独立一轮的决策。
* **端到端用例钉的是契约而不是机制**：它无法区分"第二次加载是谁排的"。要区分就需要一个能产生**终局**投递
  失败的 fixture；本轮试了 `200` 空 body、`500`、`404` 三种，前两种直接不成立、第三种被引擎接管（§26.4）。


## 27. 任务预算的按请求契约：`--timeout`（4.13.x，§17.5 第 1 条）

§17.5 记的是"`taskTimeoutMillis` 只有一个默认值 10 min；要按请求调，需要在 REST/DTO 层定契约"。这一轮补上，
并且顺着 §17.2/§17.3 已有的那条线走：**任务预算是一个时钟**，轮次预算从它派生、种子闸门按它算，所以"按请求
调预算"改的是整个任务的时间面，不是另加一个定时器。

### 27.1 契约

| 层 | 形状 |
|---|---|
| 请求 | `CrawlRequest.taskTimeoutMillis: Long? = null`（JSON 同名）。`null` / `≤ 0` = "没有偏好"，用服务端默认值；`> 0` 被夹到 `[1s, 1h]` |
| 纯规则 | `CrawlSupport.resolveRequestTaskTimeout(requested, serverDefault)`；常量 `MIN_REQUEST_TASK_TIMEOUT_MS = 1_000` / `MAX_REQUEST_TASK_TIMEOUT_MS = 3_600_000` |
| 服务 | `CrawlService.resolveTaskTimeoutMillis(request)`：与 `resolveParallelTabs` 同一形状、同一约定 |
| 任务 | `CrawlTaskContext.taskTimeoutMillis` 在提交时解析一次；worker 用它 arm 时钟、用它做 `withTimeout`、用它写"预算用尽"和"任务上限"文案 |
| 记录 | `CrawlResponse.taskTimeoutMillis` 报**实际生效**的值（含服务端夹取），不是原始请求 |
| CLI | `crawl --timeout <dur>`（`900`/`30s`/`10m`/`1h`），在本地校验后翻译成 `taskTimeoutMillis` |

三条设计取舍，都是照已有的约定抄的：

* **夹取而不是拒绝**（服务端）：与 `--parallel` 一样，"问了超过服务端愿意给的"仍然得到一次能跑的 crawl，
  而记录里报的是实际值，调用方看得见差别。`0` 和负数当"没有偏好"：预算是上限，不是开关，`0` 不能解释成
  "立刻取消"。
* **CLI 提前拒绝**：`--parallel` 的先例是"把一次往返变成一条立刻的消息"。所以 `--timeout 500ms` / `2h` /
  `ten minutes` 在本地就以非零退出，错误信息里给出可用的写法（`10m`）。
* **默认值不动**：`CrawlService.taskTimeoutMillis`（10 min）仍是"没要求就用它"，运维侧的唯一旋钮也还在那里；
  按请求调只是让一次 crawl 能自己说"我需要 30 分钟"。

### 27.2 验证

| 层 | 证据 | 结果 |
|---|---|---|
| 单测 · 纯规则 | `CrawlSupportTest` 57 → **59**：缺省/非正数回落默认、请求值被夹到 `[1s, 1h]` | **59 / 0 / 0** |
| 单测 · 服务 | `CrawlServiceTest` 11 → **13**：请求自带 `taskTimeoutMillis = 10_000` 时闸门在碰浏览器前短路、终态 TIMEOUT、每个种子按名报丢失、记录里报的就是 10_000（不是服务端默认）；夹取与缺省回落 | **13 / 0 / 0** |
| 单测 · 序列化 | `CrawlResponseTest` 17 → **18**：`taskTimeoutMillis` 持久化往返；没有该字段的历史 JSONL 行仍能还原（任务文件是跨版本追加的） | **18 / 0 / 0** |
| 单测 · CLI | `cargo test --bin browser4-cli`：时长解析（`900`→900000、`1500ms`、`10m`、`1h`、`2d`、垃圾值→None）、上下界校验、`--timeout 45s` → `taskTimeoutMillis: 45000` 且 CLI 拼写不外泄、未指定时不发这个字段 | **1214 passed / 0 failed** |
| 汇总（同批） | `CrawlLedgerTest` + `CrawlResponseTest` + `CrawlServiceTest` + `CrawlSupportTest` | **108 / 0 / 0，BUILD SUCCESS** |

文档：`skills/browser4-cli/references/crawl.md` 新增 `--timeout` 选项行 + "Task budget (`--timeout`)" 小节，
把"后端任务上限 10 分钟"改成"默认 10 分钟，可按 crawl 用 `--timeout` 提到最多 1h"，排障表新增
`Invalid --timeout` 行；`skills/browser4-cli/SKILL.md` 的 crawl 指南补一句；CLI 帮助文本由
`commands.rs` 的 `OptionDef` 生成，所以 `--timeout` 的说明只写在一处。

### 27.3 仍未做（本条边界）

* **请求侧预算不做"下限保护"**：允许 `--timeout 1s`（服务端下限）意味着这次 crawl 的每个种子都会在
  闸门处被拒。这是**如实报账**的行为（每个种子一行丢失 + `skipped` 状态），不是静默失败，所以没有再加
  "至少 45s"的限制——那会让"我就是想测一下预算闸门"变成做不到。CLI 只挡 `1s` 以下。
* **`--timeout` 不覆盖排队时间**：预算从 worker 真正开工时 arm（`armBudget`），与 `withTimeout` 同一个
  时刻，排队不算——这条语义没变，文档里也写了。
* **没有任何配置项把默认值调大**：运维侧仍然只能改 `CrawlService.taskTimeoutMillis`（或按请求传）。若将来要
  做成 `application.properties` 配置项，那是配置层的独立改动（本轮只动 REST/DTO 契约，符合 §17.5 的原话）。
* **CLI 端到端（真后端）没有单独跑 `--timeout`**：本地校验与参数翻译有 Rust 单测，服务端契约有 Kotlin 单测，
  而请求字段的绑定形状与 `parallelTabs` 完全相同（后者在 CLI e2e 里已经跑通）。要把它变成"真浏览器 +
  真后端"的证据，得往 `crawl` 的 e2e 场景里加一条，留给下一次 CLI e2e 批次。




## 28. `release.yml` `v4.13.21`：导航探针与 `fill` 的拒绝让 7 个 CLI e2e 场景变红（4.13.x，2026-09-23）

`release.yml` run 35853681478（tag `v4.13.21`）的 `Build core artifacts and Docker image` 挂在
`Run browser4-cli E2E Tests`：`153 passed; 7 failed; ... (7 failure entries; allowed <= 5)`——场景本身没有
"硬失败"机制，只有 5 个的容忍额度，第 6 个失败才让步骤以 101 退出。

7 个失败分成三组，都不是 flake，而是**当天两笔产品改动改了行为、断言还停在旧行为**，外加一条早已修在
`main` 上、4.13.x 没有的旧帐：

### 28.1 五条：`open`/`goto` 多了一次导航探针

`41a5007982`（bot-stealth 报告）给每次成功导航加了一次**建议性**的 `browser_evaluate`
（`BLOCK_PROBE_JS`）：把落地页的 URL 与可见正文一次取回，按 SKILL §2 的封禁/挑战签名打分，命中就写一条
stderr 提示、`--json` 里报 `challenge_detected`。它不改退出码、不重试、探针失败就静默跳过，代价是**每次导航
多一次往返**。

于是所有"数 `browser_evaluate` 次数"的 mock 场景都开始把**导航路径**当成**被测命令**来量：

| 场景 | 旧断言 | 实际 |
|---|---|---|
| `test_e2e_mock_eval_command` | `2` | `3` |
| `test_e2e_mock_eval_css_selector_passthrough` | `1` | `2` |
| `test_e2e_mock_eval_await_command` | `1` | `2` |
| `test_e2e_mock_eval_without_await_omits_flag` | `1` | `2` |
| `test_e2e_mock_press_command_uses_direct_tool_dispatch` | 断言"press 不合成 `browser_evaluate`" | 探针在前，断言必红 |

这五条断言的**意图**没错（`eval` 一次命令一次调用、CSS 选择器不被改写成 `backend:N`、`--await` 才带
`awaitPromise`、`press` 走直连工具分发），错的是**取样范围**：它们取的是整台 mock server 的记录，而记录里
第一次导航自己的调用也在内。

修法是给取样划一条界，而不是按探针的 JS 文本去过滤（那会把主仓 `main.rs` 里的一个字符串常量复制进测试，
下次改探针就失效）：`mod.rs` 新增 `tool_calls_before_command(&mock_server)`，在 `run_open_command` 之后
立刻记下偏移，之后用 `&tool_calls[after_open..]` 切片。导航路径以后再加调用，也不会再动这些断言；
反过来，被测命令自己多出一次 `browser_evaluate` 仍然会被抓到（切片是从导航之后算起的）。

### 28.2 一条：`fill` 对"收不了输入的目标"改成拒绝

`571bd10693` 让 `fillSafe()` 先探目标再写：定位不到、或 `disabled`/`readonly`，直接抛
`fill: target [#x] is disabled|read-only — user input is blocked.`。此前这条路径是**静默成功**——`fill` 的 JS
作用在空 `this` 上是 no-op，`evaluateValue` 返回 `null`，CLI 照样打 `✓ Filled ...` 并退出 0。

`test_e2e_keyboard_edge_inputs` 正是按旧契约写的：它对 `#readonly-target` / `#disabled-target` 调
`run_command`（要求退出 0），随后断言值没变。值没变这条仍然成立，但**退出码变了**。改成
`run_command_expecting_failure(..., "target [#readonly-target] is read-only")`：既钉住"被拒绝"，也钉住
**是哪个目标**被拒绝（只写 `"is read-only"` 的话，CLI 认错元素也会过），随后保留原来的"值未被改写"断言。

### 28.3 一条：`--sql` 的载荷独占 stdout（早就有的旧帐）

`test_e2e_crawl_foreground_with_sql` 把 `Crawl task submitted:` / `X-SQL extraction: enabled` 断言在 **stdout**
上，但带 `--sql` 且没有 `--output` 时，抽取出来的载荷独占 stdout，`crawl_status_println!` 会把这些状态行
按 `CRAWL_STRUCTURED_STDOUT` **有意**改道到 stderr（`987bf9aba9` 起，2026-09-07）。这条在 4.13.x 上一直红着，
靠 5 个失败的额度活着；`main` 上 `befd1f1c74`（2026-09-17）已经修过——本轮把那一半**回移**到 4.13.x：状态
断言读两路合并（`stdout + stderr`），载荷断言仍然只看 stdout。

### 28.4 验证

| 层 | 证据 | 结果 |
|---|---|---|
| mock 组（本地，`--level=EXTENDED`） | `--scenario='test_e2e_mock_*'` | **8 / 0 / 0** |
| crawl 组（本地，`--level=EXTENDED`） | `--scenario='test_e2e_crawl*'` | **16 / 0 / 0** |
| 真后端（本地） | `--scenario='test_e2e_keyboard_edge_inputs'`（自启后端 + 真浏览器）：`cli (expect failure) fill #readonly-target` / `#disabled-target` 各一步，随后两次取值断言 | **1 / 0 / 0** |
| 全量门禁（本地） | `cargo test --test e2e -- --nocapture --level=EXTENDED --enable-batch-scenario`（与 CI 同一命令行，`running 160 tests` / 159 个场景） | **159 / 1 / 0**，见 §28.6 |

### 28.6 全量门禁的那 1 个失败：与本轮无关的 Windows-only 断言

全量跑下来只剩 `test_e2e_session_lifecycle` 一条红，且**只在 Windows 上红**：`browser.rs:41` 断言引导语里
写着 ``run `browser4-cli open <url>` to start a new session.``，而 CLI 在 Windows 上打印的是可执行文件的
**实际文件名** ``run `browser4-cli.exe open <url>` ``（Linux/CI 上没有 `.exe`，所以这条在 CI 上一直是绿的）。
它与本轮的三个改动没有任何交集（本轮只动了 `browser.rs` 的 `test_keyboard_edge_inputs`），属于与
`resolve_storage_state_path_*` 同一类的"本地 Windows 环境假红"，本轮按"不扩大范围"处理，没有改动它。

除此之外，CI 那 7 条逐条复跑均为 `ok`：`test_e2e_mock_eval_command` /
`test_e2e_mock_eval_css_selector_passthrough` / `test_e2e_keyboard_edge_inputs` /
`test_e2e_mock_eval_await_command` / `test_e2e_mock_eval_without_await_omits_flag` /
`test_e2e_mock_press_command_uses_direct_tool_dispatch` / `test_e2e_crawl_foreground_with_sql`。

### 28.5 留下的判断

* **没有动产品代码**：三组失败都是"行为改了、断言没改"。探针与 `fill` 的拒绝都是同一天**有意**加的行为，
  Kotlin 单测（`Browser4WebDriverTest#inputTargetErrorRefusesADisabledTarget` / `...ReadOnlyTarget`）已经把
  两条消息逐字钉住，回退产品行为不在本轮范围。
* **没有按探针 JS 文本过滤**：`tool_calls_before_command` 只依赖"导航之后"这个位置，不复制 `BLOCK_PROBE_JS`。
  代价是它必须**放在 `run_open_command` 之后、被测命令之前**——放错时切片为空或含探针，断言会**响亮地**
  失败（本轮第一次改就踩了：偏移记在被测命令之后，`eval_calls` 变成 0），不会静默放过。
* **5 个失败的容忍额度仍然偏松**：7 个失败里有 4 个是"每次都红"的确定性失败，却因为额度只报了 exit 101 而
  没有更早暴露。额度本身是 §12 定的，本轮没动。

## 29. `release.yml` `v4.13.21`：OSS CDN 同步挂住，发布任务被自己的 15 分钟上限判红（4.13.x，2026-09-23）

`release.yml` run 35860129386（tag `v4.13.21`）的 `Publish GitHub release` 只有一个失败步骤：

```
2026-09-23T13:20:13.9383720Z ##[error]The action 'Sync to Aliyun OSS CDN' has timed out after 15 minutes.
```

其余 13 个步骤全绿——`Create or update GitHub Release`、`Generate Artifact Attestation`、`Verify Release`、
`Release Summary` 都成功，11 个资产（551 MB）已经带在这个 release 上，npm 与容器镜像也都已经发布。**红的是
CDN 镜像那一步，不是发布本身**；而这一步之所以红，是因为它**等的东西自己挂住了**。

### 29.1 根因：被等的 run 卡在 `Upload to OSS`，越过了等待方的 15 分钟上限

该步骤只做三件事：`gh workflow run sync-to-oss.yml` → 找到刚触发的 run → `gh run watch` 等它结束。
它触发的 run 35864655616 的步骤时序：

| 步骤 | v4.13.20（run 35519287410，09-20） | v4.13.21（run 35864655616，09-23） |
|---|---|---|
| `Download Release Assets` | 6 s | 4 s |
| `Install ossutil` | 2 s | 4 s |
| **`Upload to OSS`** | **2 分 04 秒（成功）** | **13:05:21 起 `in_progress`，超过 1 小时仍未结束** |
| 之后 7 个步骤 | 合计约 2 分钟，全部成功 | 全部 `pending` |
| 整个 job | 4 分 16 秒 | 无结论 |

两次上传的是同一批 11 个资产（551 MB，最大单个 121 MB）；09-20 那次逐个资产的耗时是
`7.5 / 5.2 / 20.9 / 43.0 / 3.7 / 3.5 / 26.0 / 3.3 / 3.4 / 3.4 / 3.7` 秒（最慢 42.98 秒，合计约 127 秒），
而 `Create latest symlinks`（1 分 36 秒）已经是整个 job 里最慢的一步。所以 09-23 不是"慢"，是**挂**：
`ossutil cp` 停在第一个资产上不再推进，run 记录自 13:05:14 之后再没有更新过。

**一个资产都没落地**这一点可以直接查证：镜像上 v4.13.20 的 `Browser4.jar` 返回 `HTTP 200`，而 v4.13.21 的
同名对象返回 `HTTP 404`——正常一次上传只需要 7.5 秒。上游没有结论，下游的 `gh run watch` 只能一直等，
直到步骤级 `timeout-minutes: 15` 把步骤杀掉——顺带把「同步失败」和「我们放弃了等待」这两件事混成了同一条消息。

顺带记下一个观测约束：**在途 run 的日志取不回来**。`gh api repos/.../actions/jobs/<id>/logs` 对 `in_progress`
的 job 返回 `HTTP 404`，所以事故当下既看不到 `ossutil` 的进度，也说不清它卡在哪个资产上。这决定了本轮的做法
是"把边界和诊断放在能被看到的地方"，而不是"等它自己好"。

### 29.2 三处修法

**1. `sync-to-oss.yml` 的每条执行路径都必须有结论**（这是等待方唯一能拿到的东西）：

* `sync` job 加 `timeout-minutes: 30`：任何步骤挂住，run 也会以 failure 收尾，而不是永远 `in_progress`；
* `Upload to OSS` 加 `timeout-minutes: 20`：正常 2 分钟、最慢单文件 43 秒，只有真正的停滞才可能触发，
  而步骤级上限能**指名**是哪个步骤挂了；
* `Install ossutil` 的 `curl` 加 `--connect-timeout 15 --max-time 300`：同样是"无界网络调用"的形状。

**2. `cli/scripts/wait-for-oss-sync.sh`（新增）**：把触发、选 run、等待三段合成一处，供 `release.yml` 与
`release-cli.yml` 共用——两者此前是**逐字相同**的副本（只有 tag 的来源和一句注释不同），改一处漏一处是迟早的事。
脚本自身带预算（默认 2700 s > 上游 job 上限 30 分钟），**不睡过截止时间**，并在成功、失败、**超时**三种结局下
都打印 run id、run URL、已用时间与**当前卡住的步骤名**。超时的消息形如：

```
::error::sync-to-oss.yml run 35864655616 was still in_progress (step: Upload to OSS) after 2700s -- the OSS CDN was not confirmed updated for v4.13.21.
::error::Run URL: https://github.com/platonai/Browser4/actions/runs/35864655616
::error::Re-check it with: gh run view 35864655616
::error::Then re-trigger with: gh workflow run sync-to-oss.yml -f tag_name=v4.13.21
```

**3. 两个调用方的步骤体缩成一次脚本调用**，`timeout-minutes: 15 → 50`：必须大于上游 job 的 30 分钟上限
**加上排队时间**，否则等待方还是会先于上游超时（这正是 v4.13.21 发生的事）。另外 `publish-github-release`
此前**没有 checkout**，脚本不会出现在 runner 上；按 4.14.x `41c012aaf5` 的做法把 checkout 加为该 job 的
**第一个**步骤（checkout 会清理工作区，必须排在下载产物之前）。

### 29.3 顺带修掉的一个假绿：等待方可能等在"上一次发布"的 run 上

旧写法是"`gh run list` 的第一个非空答案就是它"：

```bash
gh workflow run sync-to-oss.yml -f tag_name="$TAG"
sleep 3
for i in $(seq 1 20); do
  RUN_ID=$(gh run list --workflow=sync-to-oss.yml --limit 1 --json databaseId -q '.[0].databaseId' ...)
  [ -n "$RUN_ID" ] && break
  sleep 3
done
gh run watch "$RUN_ID" --exit-status
```

`gh run list` 是**新的在前**，而派发刚发出、GitHub 还没把新 run 索引出来时，这个查询返回的是**上一次发布**的
sync run——早已 `completed` / `success`。于是步骤会打出 "Aliyun OSS CDN updated" 直接放行，而当前这次同步
**从未被等待过**：CDN 会不会落后，发布会给出一个无法区分的"绿"。这与 `bd18d2b7d8`（`monitor-release.ps1`
认错 run、把旧 run 的结论当成新 run 的结论）是同一类缺陷，本轮按同样的办法修：**派发之前**先记下已存在的
run id，之后只接受不在该集合里的 run；id 基线列不出来时，退化为派发前取的**本地时间水印**（`createdAt`
在派发时刻之前的 run 一律不接受，并且**继续轮询**而不是把旧 run 当答案返回）。id 集合的比较不涉及时钟，
所以正常路径连时钟偏差都不可能影响判定。

### 29.4 验证

| 层 | 证据 | 结果 |
|---|---|---|
| 新套件（本地，无网络，`gh` 全部打桩） | `bash cli/scripts/tests/wait-for-oss-sync.tests.sh` | **19 / 19** |
| 变异 1：删掉"排除旧 run"的闸（回到旧行为） | 同一套件 | **3 条红**（含 `never adopts the previous run's success`） |
| 变异 2：超时消息里丢掉"卡在哪一步" | 同一套件 | **1 条红**（`reports the stuck run when the budget expires`） |
| 变异 3：等待超时反而报成功 | 同一套件 | **3 条红** |
| 真 `gh`（只读，对着仍在跑的 run） | `gh run view 35864655616 --json status,conclusion,jobs -q '[.status, (.conclusion // "--"), ...] \| @tsv'` | 当场返回 `in_progress\t--\tUpload to OSS`——脚本的两个过滤器逐字验证，卡的正是本次事故的步骤 |
| 既有套件（未改动，应保持全绿） | `install-browser4-cli.tests.sh` / `wait-for-npm-version.tests.sh` | **66 / 66**、**11 / 11** |
| 四个 workflow 文件的语法 | `python -c "import yaml,sys; yaml.safe_load(open(...))"` | 全部可解析；`publish-github-release` 新 checkout 与 `release-assets/`、`release-notes/` 无路径冲突 |
| 调用方清点 | `grep -rn "sync-to-oss.yml" .github/` | 只有 `release.yml` 与 `release-cli.yml` 调用，两者都已换成脚本调用；没有第三处内联副本 |
| 事故的线上证据（只读 HTTP） | `curl -sI .../releases/download/{v4.13.20,v4.13.21}/Browser4.jar` | v4.13.20 **200**、v4.13.21 **404**——本轮同步一个资产都没落地，与"卡在第一个资产"一致 |

### 29.5 留下的判断

* **没有把 OSS 同步降级为"建议性"**：`00aafa902c` 是**有意**让同步不成功就红（否则 CDN 会静默落后，而
  `latest` 符号链接是安装脚本的入口）。本轮保留这条闸门，只把"它失败了"和"我们放弃了"分开——超时会明确
  说明 run 还在跑、卡在哪一步、怎么复查与重跑。
* **脚本不自动重试同步**：重新触发一次同步会把每个资产**再传一遍**（551 MB），代价不小，而且第一次挂住的
  原因未知；自动重试只是把同一个问题再花一遍钱。判决权留给调用方。
* **没有在 `sync-to-oss.yml` 里给单个资产加 `timeout` 包裹**：`ossutil cp` 自带 `--retry-times 3`，用
  `timeout` 掐掉整个 cp 反而会**绕过**它自己的重试。步骤级 20 分钟 + job 级 30 分钟的边界已经足够，而且能
  指名步骤。
* **旧代码里的 `WATCH_OK` 死变量随这段代码一起消失了**：它从来没被用于判定，真正决定成败的一直是后面的
  `gh run watch --exit-status` 退出码。
* **套件覆盖的是脚本的判断逻辑，不是 `gh` 的行为**：打桩意味着"`gh run list` 新的在前"这一前提是**断言**
  而非**被测**（本轮用真实 `gh` 只读核对了过滤器，见 29.4）。`gh` 若改变排序或字段语义，套件不会红——
  这是刻意取舍，让套件能在没有网络、没有 token 的 CI 里跑。
