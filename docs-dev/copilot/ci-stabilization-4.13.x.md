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
