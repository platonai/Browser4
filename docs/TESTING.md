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

---

## CI 门禁实际覆盖（实测盘点）

> 数字来自对 `src/test` 的静态清点与 e2e harness 的 `--list`（2026-09 盘点）。

### 门禁矩阵

| Workflow | JVM `excluded_groups` | JVM 增量 | CLI e2e | 预算 |
| --- | --- | --- | --- | --- |
| `pr.yml`（PR） | 除 `Unit/Fast` 外全排除 + `-Pquality-gate`（**强制** INSTRUCTION ≥ 0.20） | 快档基线 | 无 | 25 min |
| `ci.yml`（release tag） | `ManualOnly,RequiresAI,E2E,E2ETest,Slow,HeavyTest,TestInfraCheck` | +Integration/Heavy | `--level=BASIC`（148） | 50 min |
| `nightly.yml`（00:00 UTC） | `ManualOnly,RequiresAI,E2E,E2ETest` | +Slow/HeavyTest/TestInfraCheck（约 32 个方法）、JaCoCo **仅观测**（`-Djacoco.check.skip=true`） | `--level=EXTENDED --enable-all --max-failures=0`（204） | 75 / 30 min |
| `nightly-cli.yml`（03:00 UTC） | — | — | `--level=ALL --enable-all`（204） | 30 min |
| `release.yml` / `release-cli.yml` | 仅构建 | — | `--level=EXTENDED --enable-all` | — |

Rust 单元测试（`cli/browser4-cli/src`，约 1400 个 `#[test]`/`#[tokio::test]`）**只有 `nightly.yml` 会跑**
（`cargo test --bin browser4-cli --lib`）；其余 workflow 只构建 `e2e` 测试目标。

`nightly.yml` 与 `ci.yml` 的 job 成败都由**最后一个 gate 步骤**判定（`Enforce Nightly Gate` /
`Enforce CI Gate`）：JVM 阶段失败不再让 Docker 构建、应用启动和 CLI e2e 阶段被跳过，
一轮就能同时拿到两侧结论（`.github/workflows/nightly.yml`、`ci.yml` 的 `Check Test Status` 只记录状态）。

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

* **`E2E`/`E2ETest` 标记的 11 个类 / 86 个方法在任何 workflow 都不执行**
  （含 `HtmlSnapshotScenariosE2ETest` 32、`MCPToolControllerE2ETest` 18、`Browser4MCPServerE2ETest` 14 …），
  整个 `browser4-tests/browser4-e2e-tests` 模块（5 个方法）同样为死代码。
  原因是 nightly/ci/pr 都排除这两个 tag，而没有 workflow 传 `-DrunE2ETests=true`。
  **已做过稳定性评估并给出按类处置方案**（两轮本地采样 56 例 0 失败）：
  见 [E2E tag 稳定性评估](../docs-dev/copilot/e2e-tag-stability-assessment.md)。
  结论摘要：不要在 nightly 里直接放开这两个 tag（放开只多跑 4 个类，其中 3 个是 Spring + 真实 Chrome 的
  重测试，单类 6.5–8 分钟）；`Browser4MCPServerE2ETest` 是 mockk 驱动、2.8 s 跑完 14 例，
  建议改标 `Unit`+`Fast` 让它回到 PR 门禁。
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
