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
| E2E 测试 | `-DrunE2ETests=true` |
| SDK 测试 | `-DrunSDKTests=true` |
| 全量     | `bin/test.sh all`    |

---

## 测试放置规范

| 类型          | 目录                         |
| ----------- | -------------------------- |
| Unit        | `<module>/src/test`        |
| Integration | `browser4-tests/*-it-tests`  |
| E2E         | `browser4-tests/*-e2e-tests` |

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
* 物理模块隔离（`pulsar-it-tests/`）已实现统计分离
* `@SpringBootTest` 已解决生命周期管理
* GitHub Actions 已编排外部服务（MongoDB、Docker Compose）
* Failsafe 的 `<groups>` 无法表达多维度组合（如 "Fast 且不需要 AI 的集成测试"）

**替代方案（现有最佳实践）：**

```bash
mvn test                              # 快速单测
mvn test -DrunITs=true                # 集成测试
mvn test -pl browser4-tests/pulsar-it-tests  # 按模块执行
mvn test -Dgroups="Integration,Fast"  # 按 Tag 组合过滤
```

---

## 一句话共识

> **Tests are contracts for the scheduler.**
