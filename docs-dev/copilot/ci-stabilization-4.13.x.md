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
3. **在途视图仍是"单轮"而非"聚合"**：`publishPages` 只带当前轮的 pages，所以下一轮种子开始发布时
   `pagesFound` 会从聚合值回落到单轮值（`failedPages`/`pagesExpected` 现在已被保留）。
   要真正单调，需要让 sink 聚合各轮 pages——注意不能简单按 URL 求并集：同一 URL 被两个种子各抓一次
   在终态记录里是两行，并集会把它们并成一行。属于显示口径问题，不是丢页问题。
4. 多轮并发发布对同一条记录是 read-modify-write，没有 `recordSeedProgress` 那样的 per-task 锁
   （`CrawlTaskContext.publishLock` 只在种子收尾时用）。危害是瞬时视图可能少一轮的字段，
   下一次发布/种子收尾就会修正；要根治需让 sink 拿到 task context 的锁。
5. 发现链接未去重，且不套用 `--ignore-url-query` / `--no-norm`（depth 1 与引擎路径都套用）；
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

* `taskTimeoutMillis` 只有一个默认值 10 min（单测直接改这个 `@Volatile var`）。若要按请求或配置调，
  需要在 REST/DTO 层定契约（`CrawlRequest` 加字段 + 校验 + 文档），本轮没有做。
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

* **强制 `-refresh` 与"readonly 可从存储读"的契约冲突**（§18.1）：`crawl --readonly` 永远会重新抓取，
  `buildReadonlyNote` 里 "served from the page store (age X)" 的措辞、`CrawlResponse.servedFromStore`
  与 `CrawlFixtureMetadataTest` 的 store-serve 分支都是死代码。要让契约成立，得让
  `buildEffectiveArgs`（以及 `crawlDepth0` 里同样的拼接）在用户明确要 `-readonly` 且没要 `-refresh` 时
  不再补 `-refresh`。这会改变用户可见行为（readonly 会开始吐旧内容），需要单独决策 + e2e。
* **失败抓取的重试**：本轮只把"没抓到"如实报成丢失，没有加重试。`crawlDepth0` 有 `MAX_FETCH_RETRIES`，
  两个链接发现路径没有。"交付失败即重投一次"需要在 ledger 上开一个"尝试中、仍未结算"的口子
  （现有的 `enter/leave` + `settle()` 恰好一次语义会被重复结算破坏），属于独立一轮。
* 触发这次退化的**根因**（浏览器在持续 crawl 负载下变得不可用：`BrowserUnavailableException`、
  `Timeout to wait for document ready`）在引擎/驱动池一侧，本轮没有动 —— 本轮只是让它不再伪装成一行。


## 19. `CrawlParallelTabsTest#testSequentialControlRunDoesNotOverlap` 在 CI 上超时（4.13.x，未修）

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
* **驱动池在该类里的分配日志**（谁占着 driver、谁在等、等了多久）没有拉出来对照，这是把"环境停顿"
  坐实成"驱动池饥饿"的最后一步。


## 20. §19 的收尾：根因是"先建新 tab、再取空闲驱动"（4.14.x，已修）

4.13.x 合并进 4.14.x 后，主 CI 在 commit `5435a7d10e` 上仍红，红点从 1 个变成 3 个，全部是
`Crawl … did not reach a terminal state within N minutes, last: PROCESSING`
（run [35139513535](https://github.com/platonai/Browser4/actions/runs/35139513535)：3043 条测试，
3030 通过、10 跳过、3 报错）：

| 用例 | 上限 | 实际 | 结局 |
|---|---|---|---|
| `CrawlFixtureMetadataTest#testReadonlyCrawlSurfacesServedOrFresh` | 6 min | 361 s 触顶 | crawl 在 19:37:23 以 `status OK, 10 pages, 0 lost` **正常完成**（测试 19:37:01 已放弃） |
| `CrawlParallelTabsTest#testSequentialControlRunDoesNotOverlap` | 4 min | 240 s 触顶 | crawl 在 19:43:44 以 `status OK, 4 pages, 0 lost` **正常完成** |
| `CrawlParallelTabsTest#testParallelLinkDiscoveryRoundsLoseNoPages` | 4 min | 240 s 触顶 | 同一 JVM 内，前一条把池耗住之后 |

两次"失败"的 crawl 都是正常终态 —— 所以问题不在 crawl 语义，而在**单次抓取越来越慢**。

### 20.1 现象：单次抓取从 2.6 s 涨到 80–103 s，然后稳定在平台

把 run 里 74 次 `L.Task … got 200 … in Xs` 拉成曲线：

| 时刻 | 单次抓取 |
|---|---|
| 19:24:31 – 19:27:07 | **2.6 – 4.1 s** |
| 19:27:18 – 19:27:37 | 7.2 – 9.4 s |
| 19:28:24 | 21.0 s |
| 19:29:34 – 19:30:59 | 41.8 – 52.9 s |
| 19:32:46 – 19:53:11 | **77 – 103 s（平台，直到 run 结束都没恢复）** |

停顿期间**应用一行日志都没有**：`processing seed URL 1/4` 与 `fetched seed URL` 之间是纯空白，
INFO 级别完全看不到"卡在哪"。

### 20.2 根因：等待方**先建一个新 tab，再去取空闲驱动**

`LoadingWebDriverPool.pollDriverInSlices` 的循环原本是：

```kotlin
while (driver == null) {
    resourceSafeCreateDriverIfNecessary(priority, conf)      // ← 先建
    ...
    driver = statefulDriverPool.poll(sliceMillis, MILLISECONDS)  // ← 再取
}
```

而 `shouldCreateWebDriver()` 只判断容量（`resourceConsumingDriversInPool < capacity`）与资源守卫，
**不看 standby 队列里是否已经有空闲驱动**。于是每次取驱动都新建一个 tab：

```
WARN Waited 9769ms  for a driver | active: 43, standby: 41, waiting: 1, working: 2, slots: 7
WARN Waited 15803ms for a driver | active: 44, standby: 41, waiting: 0, working: 3, slots: 6
WARN Waited 9278ms  for a driver | active: 46, standby: 43, waiting: 2, working: 3, slots: 4
WARN Waited 10830ms for a driver | active: 47, standby: 44, waiting: 3, working: 3, slots: 3
WARN Waited 16377ms for a driver | active: 48, standby: 44, waiting: 2, working: 4, slots: 2
WARN Waited 14352ms for a driver | active: 49, standby: 44, waiting: 1, working: 5, slots: 1
WARN Waited 18882ms for a driver | active: 50, standby: 44, waiting: 0, working: 6, slots: 0
```

`standby` 一直有 **41–44 个空闲驱动**，`working` 只有 2–6，可是每次取驱动仍然新建一个 tab，
把 `active` 从 43 推到容量上限 50（`slots` 7→0），等待时间 9–19 s 就是**新建 tab 的耗时**。
浏览器 tab 越多，建 tab 与页面加载越慢 —— 这正是曲线爬升并最终平台化（80–103 s）的原因，
CI 与本地是同一个签名（本地复现见 §20.4）。

### 20.3 附带机制：CPU 负载守卫会把一次等待放大到 60 s

驱动创建还受 `AppSystemInfo.isSystemOverCriticalLoad`（`systemCpuLoad > CRITICAL_CPU_THRESHOLD`，
默认 **0.85**）约束；被拒绝时等待方按 500 ms 切片轮询，最多烧掉 `POLLING_TIMEOUT = 60 s`，
再转成 crawl retry（`Retry(1601)`）。用 `-DjacocoArgLine=-Dcritical.cpu.threshold=0.0` 强制守卫
永远拒绝，其余完全相同（同一条用例）：

| 变体 | 结果 | 关键日志 |
|---|---|---|
| A：默认阈值 | **1/0/0，52.6 s** | — |
| B：`threshold=0.0` | **1/0/0，111.3 s（2.1×）** | `The system is over the critical load, will not create a new driver` → `Driver pool is exhausted after 66948ms … [Critical CPU] \| active: 1, standby: 1` → `WARN … [Exhausted] Retry task 1 in browser scope` → `L.Task … fc:1 Retry(1601)` |

CI runner 上"3000 条测试 + Chrome"的 CPU 负载长期高于 0.85，这条路径随时会把单次抓取再叠加 ~60 s，
所以它虽然**不是**主因，也必须一起处理。

### 20.4 修复与验证

| 改动 | 位置 | 说明 |
|---|---|---|
| **先取 standby，再建新 tab**（主修复） | `LoadingWebDriverPool.pollDriverInSlices` | 循环开头先 `statefulDriverPool.poll(0, MILLISECONDS)` 非阻塞取空闲驱动，取不到才走创建路径；冷启动行为不变（无空闲时立刻建） |
| 慢等待可观测 | `LoadingWebDriverPool.poll` | 等到 driver 但等待 ≥ `SLOW_POLL_MILLIS`（5 s）时按 1 分钟节流 WARN，带等待毫秒数与 `Snapshot`（`[Critical CPU]`/容量/各计数）；池耗尽那条 INFO 也带上等待时长 |
| 测试 JVM 关闭 CPU 守卫 | 根 `pom.xml` | `<critical.cpu.threshold>1.0</critical.cpu.threshold>` + surefire `argLine`；生产仍是 0.85，内存/磁盘守卫对测试仍生效。要复现守卫行为传 `-Dcritical.cpu.threshold=0.85` |
| 超时改为"卡住"判定 | `CrawlParallelTabsTest` / `CrawlFixtureMetadataTest` 的 `waitForTerminal` | 墙钟上限只作挂死兜底（12/20 min），真正判据是**进度**：`pagesFound/linksDiscovered/seedStatuses` 连续 3/5 分钟不变才失败；失败信息带上 `last status` 与 `progress: pagesFound=…, linksDiscovered=…, seedsSettled=…`（§19.5 第 1 条） |
| 回归测试 | `LoadingWebDriverPoolTest#testPollReusesAStandbyDriver` | 对旧代码失败（`expected: <1> but was: <2>`：standby 存在却仍新建），修复后通过 |

同一条本地命令（`-Pall-test-modules -pl browser4-tests/browser4-rest-tests -am -DrunITs=true
-Dtest=CrawlParallelTabsTest,CrawlFixtureMetadataTest`）修复前后：

| | 修复前 | 修复后 |
|---|---|---|
| `CrawlFixtureMetadataTest` | 657.4 s（5/0/0） | **243.4 s**（5/0/0） |
| `CrawlParallelTabsTest` | 765.4 s（5/0/**1**） | **51.0 s**（5/0/0） |
| 单次抓取 | 1m24 – 1m51（持续退化） | **2.7 – 5.0 s（全程平稳）** |
| 池等待 WARN | 7 次，9–19 s，`active` 43→50 | 1 次，5.6 s（冷启动 `standby: 0`） |

CI 修复后的数字应与 §19.3 的"健康 run"（`CrawlFixtureMetadataTest` 321 s、`CrawlParallelTabsTest` 72.7 s）
同一量级。

### 20.5 附带发现：合并后在本地 `mvn install` 会留下孤儿 class

`browser4-rest/.../service/CrawlService.kt` 被 4.13.x 拆到 `service/crawl/` 之后，增量构建**不会删除**
被删源文件产生的 class：`target/classes/ai/platon/pulsar/rest/api/service/CrawlService.class`（连同
`CrawlRequest/CrawlResponse/CrawlPageResult/CrawlSeedStatus`）会留在产物里，并被 `install` 打进 jar。
于是 classpath 上同时存在 `service.CrawlService` 与 `service.crawl.CrawlService`，Spring 启动即：

```
ConflictingBeanDefinitionException: Annotation-specified bean name 'crawlService' for bean class
[ai.platon.pulsar.rest.api.service.crawl.CrawlService] conflicts with existing, non-compatible bean
definition of same name and class [ai.platon.pulsar.rest.api.service.CrawlService]
```

CI 不受影响（每次从干净检出构建，没有 `target/`）；**本地**遇到就 `./mvnw clean install`。
排查时注意签名：这类失败发生在 Spring 上下文启动阶段（约 1.5 s 内整类 error），与本文的抓取停顿
（`PROCESSING` 直到上限）完全不同。

### 20.6 还没做

* **守卫的产品语义**：池已达容量且有人排队时，"按 500 ms 切片轮询 + 60 s 超时 + 上层重试"在负载下会把
  延迟放大到分钟级；是否在"已经有等待者"时允许再建一个 driver（受 capacity 约束）需要单独评估。
* **空闲 tab 的回收**：主修复让池不再堆积空闲 tab，但 `idleTimeout`（20 分钟）之外仍没有更积极的回收策略；
  长时间运行的服务会保留 `capacity` 上限内的 tab，值得单独测量内存占用。
* **CI 上的复测**：`Waited {}ms for a driver` 这条 WARN 是新的观测点，若 CI 仍出现平台化，
  先看这条（等 driver）与 `L.Task` 的耗时对比，再决定是池侧还是浏览器侧。