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
| SDK         | `sdks/*-tests`             |

---

## CI / 调度语义（给 AI）

* `Fast` → 可并行、即时反馈
* `Heavy` → 夜间 / 手动 / 资源隔离
* `ManualOnly` → 必须人工触发
* `SkippableLowerLevel` → 可剪枝执行
* `TestInfraCheck` → 失败立即中断

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
