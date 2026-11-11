# Test Taxonomy (AI-First)

## 核心原则（TL;DR）

* `mvn test` **必须永远快、永远安全**
* 所有高成本测试 **必须显式启用**
* **Tag = 语义事实**
* **Property = 是否执行**
* 测试是给 **调度系统** 看的，不只是给人看的

---

## 四个正交维度（必须声明）

### 1. Test Level（测试层级）

**必选其一**

* `Unit` — 单模块，默认
* `Integration` — 多模块 / 服务协作
* `E2E` — 用户端到端路径
* `SDK` — 对外 SDK 契约

---

### 2. Cost（执行成本）

**必选其一**

* `Fast` — < 5s
* `Slow` — 5–30s
* `Heavy` — > 30s / 高资源

遗留代码默认 `Fast`, 但必须逐步补齐成本标签

---

### 3. Environment（环境依赖）

**按需声明**

* `RequiresServer`
* `RequiresBrowser`
* `RequiresAI`
* `RequiresDocker`

---

### 4. Policy（执行策略）

**按需声明**

* `ManualOnly` — 必须人工触发
* `SkippableLowerLevel` — 上层成功可跳过
* `TestInfraCheck` — 基础设施自检（最高优先级）

---

## 合法 Tag 组合示例

### 默认可跑的单测

```java
@Tag("Unit")
@Tag("Fast")
class ParserTest {}
```

---

### 稳定但慢的单测

```java
@Tag("Unit")
@Tag("Slow")
@Tag("ManualOnly")
class LegacyEngineTest {}
```

---

### 集成测试

```java
@Tag("Integration")
@Tag("Heavy")
@Tag("RequiresServer")
@Tag("ManualOnly")
class RestContractIT {}
```

---

### E2E 测试

```java
@Tag("E2E")
@Tag("Heavy")
@Tag("RequiresBrowser")
@Tag("RequiresAI")
@Tag("ManualOnly")
class ChatFlowE2ETest {}
```

---

## 默认执行语义

### `mvn test` 等价于

```
Level = Unit
AND Cost = Fast
AND NOT ManualOnly
```

---

## 执行控制（Property）

| 行为     | Property             |
| ------ | -------------------- |
| 集成测试   | `-DrunITs=true`      |
| E2E 测试 | `-DrunE2ETests=true`（当前无 workflow 使用，见下节） |
| SDK 测试 | 无此 property：`SDK` tag 没有任何测试标注。对外契约由 MCP 契约测试（`ToolContractMatrixTest` / `ToolSpecLintTest` / `docs/mcp-tools.{md,json}` 漂移检查）与 CLI e2e 的 `test_e2e_command_coverage` 覆盖 |
| 全量     | `bin/test.sh all`    |

---

## 测试放置规范

| 类型          | 目录                         |
| ----------- | -------------------------- |
| Unit        | `<module>/src/test`        |
| Integration | `browser4-tests/*-it-tests`  |
| E2E         | `browser4-tests/*-e2e-tests` |

> **注：** 原 `pulsar-it-tests` / `pulsar-e2e-tests` 的集成与 E2E 套件已迁移至底层库
> （browser4-core 各模块与 `pulsar-tests-common`），占位模块已从本仓库移除。

---

## CI / 调度语义（给 AI）

* `Fast` → 可并行、即时反馈
* `Heavy` → 夜间 / 手动 / 资源隔离
* `ManualOnly` → 必须人工触发
* `SkippableLowerLevel` → 可剪枝执行
* `TestInfraCheck` → 失败立即中断

---

## 本地复现 CI 测试范围（一次跑全）

不要靠 tag CI 逐轮试错（一轮约 26 分钟，而且 Maven 在第一个失败模块就停，所以一轮只暴露一个失败）。
本地用与 `ci.yml` **相同**的开关跑一次，把失败全部列出来：

```powershell
# Windows
./mvnw.cmd -o -B "-Pall-main-modules,all-test-modules" `
  "-Dsurefire.excludes=**integration**" `
  "-Dsurefire.excludedGroups=ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck" `
  -DrunITs=true "-Dmaven.test.failure.ignore=true" test
```

```bash
# Linux / macOS
./mvnw -o -B -Pall-main-modules,all-test-modules \
  -Dsurefire.excludes='**integration**' \
  -Dsurefire.excludedGroups=ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck \
  -DrunITs=true -Dmaven.test.failure.ignore=true test
```

* `-Dmaven.test.failure.ignore=true`：Maven 不在第一个失败模块停下，一轮即可看到**全部**失败模块（CI 没开这个开关，所以 CI 一次只暴露一个失败 —— 这正是"每轮修一个"的来源）。
* PowerShell 中 `-P...` 必须整体加引号（`,` 会被解析成参数数组）。
* 汇总方式：按 `[INFO] Building <模块>` 分组取 `Tests run:` 行；已知结果与处置见
  [4.13.x CI 稳定化报告](../docs-dev/copilot/ci-stabilization-4.13.x.md)。
* 复跑单个模块：模块必须先进入 reactor（只给 `-pl` 会报
  "Could not find the selected project in the reactor"），例如
  `-Pall-test-modules -pl browser4-tests/browser4-rest-tests test`。
* **`-Dsurefire.excludedGroups=` 是"整体替换"而不是"追加"**：root `pom.xml` 的默认值
  （`Slow,Heavy,RequiresServer,RequiresBrowser,RequiresAI,RequiresDocker,Integration,E2E,ManualOnly,TestInfraCheck,IntegrationTest,E2ETest,HeavyTest`
  —— 即"排除所有非 Fast"）会被命令行**覆盖**；传了 `-Dsurefire.excludedGroups=` 就必须把想排除的
  tag 全部再列一遍，否则像 `IntegrationTest` 这类测试会意外跑起来。
* 想再窄到某个类：必须同时放开 group 过滤，否则选中的类被默认 `excludedGroups` 排除后
  surefire 会给出 `Tests run: 0` **且退出码为 0**（静默通过，极易误判）：

  ```powershell
  ./mvnw.cmd -o -B -Pall-test-modules -pl browser4-tests/browser4-rest-tests `
    -DrunITs=true `
    "-Dsurefire.excludedGroups=ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck" `
    "-Dtest=CrawlFixtureMetadataTest" test
  ```

  过滤后先看 `Running <类名>` 行确认真的跑起来了。

### PowerShell / skills 文档门禁（两个独立 workflow）

这两件事在 2026-11 之前被焊在同一个 job 里，结果是**文档超行数会报成 PowerShell 失败**：
`test.ps1 ps` 先打印 `[PASS] PowerShell tests: 25 passed, 0 failed`，紧接着
`[FAIL] Skill document conformance`，而单独命名的 `Skill document conformance` 步骤因为前一步
已失败被 **skipped**。`ps1-tests` 最近 100 次运行里 34 次失败，其中 **24 次**是 skills/ 文档的
M6 行数规则，Pester 的 25 个文件在每一次里都全绿。

现在拆成两个 workflow、两个独立的 check：

| Workflow | 检查 | 触发路径 | 预算 |
| --- | --- | --- | --- |
| `ps1-tests.yml` | `./bin/test.ps1 ps -SkipDocs`（Pester） | `**/*.ps1` 等 | ~3 min |
| `skill-docs.yml` | `./bin/skill-doc-lint.ps1`（M1-M8） | `skills/**/*.md`、linter 及其边界测试 | ~15 s |

`-SkipDocs` 是关键：CI 的 PowerShell job 在原理上就不可能因为一份文档而红。

本地对应命令：

```powershell
pwsh bin/test.ps1 ps                  # Pester + skills/ 合规检查（本地默认，一条命令覆盖两个门禁）
pwsh bin/test.ps1 ps -SkipDocs        # 只跑 Pester（与 CI 的 ps1-tests job 完全一致）
pwsh bin/skill-doc-lint.ps1           # 只跑合规检查（与 skill-docs job 等价）
pwsh bin/skill-doc-lint.ps1 -Strict   # advisory 也当失败（发版 / 还债时用）
```

* M6 现在是**分层**规则，而不是一个 500 行的悬崖：
  * `SKILL.md`（每次激活整篇载入）—— 硬上限 500，超过即失败，并直接告出"移多少行到 references/"；
  * `decision` / `procedure` 参考文档（按需读取，方法论对它给的是 target 而非机器测试）——
    501–700 行只报 **advisory**（计数、可被 `-Strict` 升级），> 700 行才算失败（该拆分了）；
  * `catalog` 仍然不限长度。
  依据见 [ps1 门禁失败分析](../docs-dev/copilot/ps1-ci-gate-failure-analysis.md)。
* 不靠"提高上限"或"失败重试"：这些失败是**确定性**的（同一断言、同一份文档，100 次里 10 次是同一个
  map 漂移断言），自动重试只会掩盖；历史 24 次 M6 失败中没有一次对应真实的 PowerShell 缺陷。
* `skills` / `mcp-contract` 组里合规检查仍跑在**最前面**：文档不合规就 1 秒失败，不浪费一次构建。
* `pr.yml` / `ci.yml` 仍各自显式跑 `./bin/skill-doc-lint.ps1`；`skill-docs.yml` 是它在 push 分支上的
  独立看门人（含 nightly cron），不改变既有判定。
* 挂载与 `-SkipDocs` 的回归保护在 `bin/tests/test.ps1.tests.ps1`；M6 边界在
  `bin/tests/skill-doc-lint.tests.ps1`（500 / 501 / 700 / 701、`-Strict`、catalog 无上限）。
* `ps` 只认 `-Quiet`（转发给每个测试脚本，Pester 分支转成 `-Show None`）与 `-SkipDocs`；
  **其余参数一律报错**（仅当 `ps` 是唯一目标时；`ps fast -pl x` 这类组合仍按原样透传）。
  此前未识别的参数会被静静转发、再被测试脚本丢弃，所以 `ps --list` 不是列文件而是把整套测试跑了一遍
  ——长到看起来像卡死。要列文件用 `pwsh bin/test.ps1 -Show ps`。

---

## CI 门禁实际覆盖（实测盘点）

> 数字来自对 `src/test` 的静态清点与 e2e harness 的 `--list`（2026-09 盘点；2026-10 复测：新增
> `test_e2e_experience_web_roundtrip`（EXTENDED，默认跑）、`test_e2e_experience_real_web_smoke`
> （EXTENDED，`--enable-all` 才跑）与 `test_e2e_mock_click_is_stack_safe`（BASIC，mock server），因此
> BASIC 计数 +1、EXTENDED 计数 +2）。

### 门禁矩阵

| Workflow | JVM `excluded_groups` | JVM 增量 | CLI e2e | 预算 |
| --- | --- | --- | --- | --- |
| `pr.yml`（PR） | 除 `Unit/Fast` 外全排除 + `-Pquality-gate`（**强制** INSTRUCTION ≥ 0.20） | 快档基线 | 无 | 25 min |
| `ci.yml`（release tag） | `ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck` | +Integration/Heavy | `--level=BASIC`（150） | 50 min |
| `nightly.yml`（00:00 UTC） | `ManualOnly,RequiresAI` | +Slow/HeavyTest/TestInfraCheck、**+E2E/E2ETest（5 个类 / 64 个方法，实测 ~9 min）**、JaCoCo **仅观测**（`-Djacoco.check.skip=true`） | `--level=EXTENDED --enable-all --max-failures=0`（208） | 75 / 30 min |
| `nightly-cli.yml`（03:00 UTC） | — | — | `--level=ALL --enable-all`（208） | 30 min |
| `nightly-cli.yml` 的 `windows-smoke` job（03:00 UTC） | — | — | `--level=SMOKE --max-failures=0`（7，含 coverage 伪条目）**+** `--scenario=test_e2e_mock_click_is_stack_safe` | 25 min |
| `release.yml` / `release-cli.yml` | 仅构建 | — | `--level=EXTENDED --enable-all` | — |

Rust 单元测试（`cli/browser4-cli/src`，约 1400 个 `#[test]`/`#[tokio::test]`）**只有 `nightly.yml` 会跑**
（`cargo test --bin browser4-cli --lib`）；其余 workflow 只构建 `e2e` 测试目标。

`windows-smoke` 是**唯一在 Windows 上跑 CLI 的门禁**，只选 `requires_browser4: false` 的场景（mock server /
CLI 状态类，不需要 Java、Chrome、Docker），因此不会重蹈旧的三平台矩阵 job 因 CLI 托管后端在 Windows 上不稳
而整条变红的覆辙；它覆盖的是 Linux job 结构上看不到的一半：Windows 的进程/状态路径，以及**主线程栈保留**
（`cli/browser4-cli/build.rs` 用 `/STACK:8388608` 对齐 Linux/macOS 的 ~8 MB 默认；Windows 默认只有 1 MB，
debug 构建的异步链曾在 `click` 上撞穿它并让进程 `STATUS_STACK_OVERFLOW` 直接 abort）。该保留值由
`main.rs::test_windows_binary_reserves_a_linux_sized_main_thread_stack` 读 PE 头钉住。若日后 mock 场景在
runner 上证明稳定，把该 job 提到 `--level=BASIC` 只是一个词的改动。

`nightly.yml` 与 `ci.yml` 的 job 成败都由**最后一个 gate 步骤**判定（`Enforce Nightly Gate` /
`Enforce CI Gate`）：JVM 阶段失败不再让 Docker 构建、应用启动和 CLI e2e 阶段被跳过，
一轮就能同时拿到两侧结论（`.github/workflows/nightly.yml`、`ci.yml` 的 `Check Test Status` 只记录状态）。

两者的 checkout 之后、构建之前还有一道 **版本一致性** 前置步骤（`node bin/version.mjs check`）：它除根 pom 外
还沿根 pom 的 `<modules>` 遍历 reactor，逐个校验项目 `<version>` 与仓内 `<parent><version>`。某个模块的父版本
一旦滞后（例如版本 bump 发生在维护分支、而该模块只存在于 main，2026-11-08 nightly 即为此），Maven 会按旧版本
构建它，依赖 `${project.version}` 的兄弟模块随即报 `Could not find artifact ...:jar:<version>`。该步骤在数秒内
点名父模块失败，而不是三分钟后再在 reactor 里失败；模板/generated 等不在 reactor 内的 pom 不在校验范围。

### CLI e2e 的 experience 覆盖（本轮补齐）

`experience save|query|list|deep-learn` 原先标 `E2eCoverage::Excluded`：Rust 参数映射单测、REST 派发单测
（Mockito 掉 `AgentToolManager`）、spec 契约矩阵（只校验参数不执行）和执行器单测都在，但**没有任何一层
真跑过 CLI → 后端 → 真页面**。现在有两个场景（组 `experience`）：

| 场景 | 级别 | 依赖 | 覆盖要点 |
| --- | --- | --- | --- |
| `test_e2e_experience_web_roundtrip` | EXTENDED | 真 Chrome + 后端（fixture `/experience`） | 两轮闭环：真页面操作 → `save --facts` 记录**真实选择器** → 新会话 `query` → **只用返回的选择器**回放并断言页面状态；另含 5 次成功后的 promote（candidate/verified）、VERIFIED 不可变规则、冷启动 P5、坏 trace / 非 http URL / 缺参负向路径 |
| `test_e2e_experience_real_web_smoke` | EXTENDED | 真 Chrome + 后端 + **公网** | `example.com` 上的同一生命周期；`exclude_by_default`，仅 `--enable-all` 时跑 |

- fixture：`browser4-tests/pulsar-tests-common/src/main/resources/static/b4/experience-replay-fixture.html`
  （路由 `/experience`，`?nonce=` 让每轮期望值唯一）。页面里有一个 **disabled 诱饵输入且排在 DOM 最前**，
  所以"取第一个输入框"这类位置猜测必然失败 —— 只有记录下来的选择器能跑通。
- 知识库位置：`knowledge.dir` 系统属性（见 [config.md](config.md)）。e2e harness 通过默认的
  `BROWSER4_SERVER_OPTS=-Dknowledge.dir=<run-temp>/knowledge` 把库钉在每轮临时目录里
  （临时路径含空格时跳过钉定，此时靠 per-run runtime 目录隔离）；断言仍按 `(domain, intent)` 条目做
  增量比较，因为引擎的自动沉淀（`MemoryConsolidator`）也写同一个库。
- 命令 `E2eCoverage` 已翻成 `Tested`，并在 `tests/e2e/mod.rs::tested_commands()` 登记（台账守卫强制两者一致）。
- 本地跑：
  `cargo test --test e2e -- --nocapture --level EXTENDED --scenario=test_e2e_experience_web_roundtrip`
  （真实网站变体加 `--enable-all`）。

### 通过/失败语义（重要）

* **Maven 退出码是权威**：`.github/actions/run-tests/action.yml` 的 `reconcile-status` 不再把
  "非零退出但 0 个测试失败" 记为成功，也会在"退出码 0 但没有任何 surefire XML"时判失败。
  因此 **JaCoCo 覆盖下限、编译错误、fork 崩溃现在都会真的让门禁变红**。
* **JaCoCo 现在是真在跑（别再改回 `${...}`）**：surefire 的 `argLine` 必须使用 Maven 的晚绑定
  语法 `@{jacocoArgLine}`。该属性在 root `pom.xml` 的 `<properties>` 里声明为空，
  `${jacocoArgLine}` 会在构建 effective model 时就被替换成空串（早于 `prepare-agent` 在运行期赋值），
  结果是 agent 不注入、`jacoco.exec` 不生成、`report`/`check` 全部
  `Skipping JaCoCo execution due to missing execution data file`，下限静默空转。
  下限由 `pr.yml` 强制执行（每模块 INSTRUCTION ≥ 0.20）；`nightly.yml` 用
  `-P...,quality-gate -Djacoco.check.skip=true` 只测量不设限。
  例外：`browser4-tests/pulsar-tests-common` 在模块自己的 `pom.xml` 里跳过 `check`（共享测试支撑库，
  它自己的 main 类只会在别的模块的测试 JVM 里被执行，bundle 覆盖率恒为 0.00；而它一红会让 17 个
  依赖它的模块被 SKIPPED）。同类模块要豁免就显式写在模块 pom 里并说明理由，不要下调全局下限。
* CLI e2e 默认容忍 5 个场景失败；`--max-failures=<n>` 可改，CI 门禁应传 `0`。
  被容忍的失败会打印通过率，并在 GitHub Actions 上产生 `::warning::` 注解。
* `nightly.yml` 的门禁判定集中在最后的 `Enforce Nightly Gate`：JVM 阶段失败不再跳过 CLI e2e 阶段，
  一夜之内两边都能拿到结论。

### 已知覆盖缺口（尚未修，需要决策）

* **`E2E`/`E2ETest`：nightly 是这两个 tag 唯一的执行地**（`pr.yml` 与 `ci.yml` 仍排除它们 —— 它们是
  合并/发布门禁，而单个类就是分钟级）。最近一次实测（nightly run 38345418466，6 个类 / 72 个方法 /
  约 7.5 分钟）：`HtmlSnapshotScenariosE2ETest` 39 例 · 138 s、`MCPToolControllerE2ETest` 18 例 · 252 s
  （其中 1 例没有 LLM key，白等 180 s 超时 —— 见下）、`CommandControllerE2ETest` 4 例 · 31 s、
  `StorageStateCookiePathE2ETest` 4 例 · 19 s、`CaptureIgnoreDomFeaturesE2ETest` 2 例 · 9 s、
  `SwarmControllerE2ETest` 5 例 · 2.1 s。
  **tag 落在方法上时类不会整体退出**：`CommandControllerE2ETest` 只排除 1 个 `ManualOnly` 方法、
  `MCPToolControllerE2ETest` 只排除 2 个 `RequiresAI` 方法，其余用例照跑 —— 所以**新写的 AI 用例必须
  自己带 `RequiresAI`**，否则会在没有 LLM key 的 nightly 上等满超时才失败（run 38345418466 的
  `testAgentRunStatusAndResultTools` 就是如此）。
  整类退出的只有 `browser4-tests/browser4-e2e-tests` 模块的 3 个类（`AgentE2ETest` / `SkillInstallE2ETest`
  类级 `ManualOnly`，`SkillRegistrationAndInvocationE2ETest` 类级 `RequiresAI`，共 5 个方法），
  该模块因此**仍是死代码** —— 要让它跑，要动的是 `ManualOnly`/`RequiresAI` 的语义，那是另一个决策，
  不是"放开 E2E"。
  历史评估（含这类重测试的实测耗时与 flake 风险）见
  [E2E tag 稳定性评估](../docs-dev/copilot/e2e-tag-stability-assessment.md) 与
  [ci-stabilization §31.3/§31.4](../docs-dev/copilot/ci-stabilization-4.13.x.md)；nightly 不是合并门禁，
  因此愿意承担这份耗时与偶发失败，换取这两个 tag 不再无人执行。
* **口径更正**：本节曾写"11 个类 / 86 个方法"，本次静态清点是 **10 个类**；方法数也随用例增长而变化
  （`HtmlSnapshotScenariosE2ETest` 由文档记的 32 增至实测 39）。上表 nightly 行的 counts 以本次实测为准。
* **Tag 标注仍在补齐中（本轮已按实测数据补了一批）**：357 个含 `@Test` 的类里 62 个带 tag（295 个无 tag）。
  补齐规则（用 surefire 的 `<testcase time>` 实测 + 被测进程是否真的拉起 Chrome 作为证据）：
  单方法实测 **≥30 s → `Heavy`**、**5–30 s → `Slow`**、真的启动 Chrome **→ `RequiresBrowser`**；
  只在少数方法慢的类上做**方法级**标注 —— 类级标注会把同类的快方法一起剔出门禁，
  例如 `BrowserTabToolExecutorTest`（52 个方法 / 11 s，全是上下文启动开销）类级标 Slow 等于白丢 52 个方法。
  本轮结果：`Slow` 8 → 18 类 / 81 方法，`Heavy` 0 → 2 类 / 8 方法，`RequiresBrowser` 1 → 6 类 / 23 方法。
  对门禁的影响（实测）：PR 门禁不再执行这些类，快档实测总时长 691 s 中的约 493 s（71%）被移出 PR；
  `Slow` 的方法在 `ci.yml` 也不再执行（其清单本就排除 `Slow`）；`nightly.yml` 不受影响，仍全部执行。
* **仍然零标注的 tag**：`HeavyTest`（`Heavy` 的遗留别名，已无使用者）、`Integration`（新 taxonomy 的
  scope tag，仓库目前用的是遗留 `IntegrationTest`，9 个类）、`RequiresDocker`（没有测试需要 Docker）、
  `SDK`（没有对外 SDK 契约测试）。排除清单里保留它们属于防御性配置，当前不产生任何效果。
* **6 个插件模块不在覆盖率测量范围内**：`browser4-plugins/browser4-{media,images,pptx,markdown,swarm,profile-import}`
  的父 POM 是独立发布的 `browser4-pdk`（它直接继承 Maven Central 上的 `pulsar-parent`，好让第三方插件
  项目不必继承本仓库的聚合 POM）。因此根 `pom.xml` 的 `quality-gate` profile（JaCoCo agent + report +
  0.20 下限）对它们**完全不生效**：它们的测试照跑，但既不产出覆盖率数据、也不受下限约束。
  查询覆盖率时会看到 nightly 的 Coverage Summary 只列出有数据的模块——这是原因之一。
  要覆盖它们需要改 PDK 的公开契约（属于设计决策，不是本地修补）。
* 类名不匹配 surefire 默认 include（`*Test`/`*Tests`/`TestCase`）的类不会被执行；
  目前只剩 `ToolSpecSnapshotRegenerator`、`RestAPITestBase` 两类，属工具/基类。
* `surefire.excludes=**integration**` 是**小写路径匹配**，实践中只命中
  `browser4-media/.../media/integration/`（1 个类）；`*IntegrationTest.kt` 这类
  类名不会被它排除，只能靠 tag —— 别把它当成集成测试的总开关。

---

## Reviewer / AI Checklist

* 是否声明 **Level**？
* 是否声明 **Cost**？
* 是否有隐式外部依赖？
* 是否会污染 `mvn test`？
* 是否需要 `ManualOnly`？

---

## Anti-Patterns（禁止）

* 不标 Cost
* 默认跑 E2E
* Tag 语义模糊（如 `IntegrationTest`）
* 用代码而非 Tag 控制是否执行

---

## 为何不使用 maven-failsafe-plugin

项目经过评估（详见 `docs-dev/maven-failsafe-plugin-evaluation.md`），决定 **不全面引入 failsafe-plugin**。

**核心原因：**

* JUnit 5 Tags 四维度分类 > Failsafe 单维度命名约定
* 测试套件已迁移至底层库（原 `pulsar-it-tests` / `pulsar-e2e-tests` 模块已移除）
* `@SpringBootTest` 已解决生命周期管理
* GitHub Actions 已编排外部服务（MongoDB、Docker Compose）
* Failsafe 的 `<groups>` 无法表达多维度组合（如 "Fast 且不需要 AI 的集成测试"）

**替代方案（现有最佳实践）：**

```bash
mvn test                              # 快速单测
mvn test -DrunITs=true                # 运行底层库中的集成测试（Tag 驱动）
mvn test -Dgroups="Integration,Fast"  # 按 Tag 组合过滤
```

---

## 一句话共识

> **Tests are contracts for the scheduler.**
