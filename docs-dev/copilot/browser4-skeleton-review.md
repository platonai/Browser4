# browser4-skeleton 代码审查报告

> 审查方式：**b4 编程能力**（coding 域工具链）取证 + 人工精读。
> 日期：2026-08-15 · 分支 4.14.x · 模块 `browser4-core/browser4-skeleton`

## 1. 工具链取证（真实输出）

```
moduleGraph(module=browser4-core/browser4-skeleton)
  affects (transitively): browser4-skeleton, browser4-agent-tools, browser4-agentic,
    browser4-apps/browser4-bundle, browser4-apps/browser4-standalone, browser4-boot,
    browser4-core/browser4-parse, browser4-core/browser4-protocol,
    browser4-pdk/browser4-pdk-test-plugin, browser4-plugins/* (captcha/images/markdown/media/pptx/seo),
    browser4-rest, examples/browser4-examples, browser4-tests/* (5) — 共 21 个模块

impact(PulsarSession.kt) → module: browser4-core/browser4-skeleton；同上 21 模块受影响

ktSymbols(PulsarSession.kt) → interface PulsarSession @ line 156
ktReferences(scope=module, PulsarSession) → 5 处跨文件引用：
  core/api/API.kt (typealias), loop/impl/AbstractTaskRunner.kt, loop/impl/StreamingTaskRunner.kt,
  context/PulsarContext.kt, context/support/AbstractPulsarContext.kt
ktInheritance(AbstractPulsarSession.kt) → AbstractPulsarSession → PulsarSession → AutoCloseable
validate(type=repo-consistency) → ✓ 全部通过（含新注册的 browser4-weather 模块）
```

**结论**：browser4-skeleton 是核心基座模块——21 个模块直接/间接受其影响（改动影响面≈全仓）。审查优先级应为全仓最高。

## 2. 架构与包组织

```
browser/privacy/   隐私上下文与浏览器档案（AbstractPrivacyManager/PrivacyContext/…）
browser/rpa/       RPA 入口（BrowseRPA）
dom/util/          UriExtractor
loop/              任务循环（TaskLoop/TaskRunner + StreamingTaskRunner 1010 行）
skeleton/common/   通用设施：options（LoadOptions 1496 行）、metrics（Codahale 包装）、proxy、urls、collect、message
skeleton/context/  PulsarContext 与实现（AbstractPulsarContext 638 行）
skeleton/event/    页面事件总线（PageEvents 742 行）
skeleton/plugin/   Browser4Plugin / PluginManifest / MountPoints
skeleton/session/  PulsarSession 接口（2444 行）+ AbstractPulsarSession（765 行）
skeleton/workflow/ 抓取流水线：fetch/parse/protocol/schedule/filter + PageSummaryIndexService（1471 行）
```

分层清晰（context → session → workflow → loop），是典型的"配置→会话→抓取流水线"分层；skeleton 承载了 pulsar 的历史核心职责。

## 3. 优点

1. **资源管理规范**：`AbstractPulsarSession.close()`（L595）用 `closed.compareAndSet(false, true)` 幂等 + `runCatching { it.close() }` 逐个关闭并记警告——同类代码里少见的严谨
2. **并发原语选型正确**：AtomicLong（SEQUENCER/id）、AtomicBoolean（closed）、ConcurrentHashMap（dataCache）、ReentrantLock + withLock、ConcurrentSkipListSet（StreamingTaskRunner）——无裸 synchronized 滥用
3. **配置分层成熟**：VolatileConfig（会话级可变）/ImmutableConfig 分离，多配置源优先级（env > sysprop > application.properties）文档化
4. **隐私管理语义清晰**：permanent vs temporary context + 隐私泄漏检测丢弃临时上下文
5. **测试覆盖较全**：40 个测试文件覆盖 options/normalizer/session/metrics/privacy/event 等主要面

## 4. 问题与风险（按优先级）

### 高
| 位置 | 问题 |
|---|---|
| `AbstractPulsarSession.kt` L97 | `closableObjects = mutableSetOf<AutoCloseable>()` —— **非并发安全容器**；`registerClosable` 可被任意线程调用（session 是多线程共享对象），并发注册会丢元素/数据竞争 → 用 `ConcurrentHashMap.newKeySet()` |
| `AbstractPulsarSession.kt` L83 | `enablePDCache` 是**普通布尔**（非 volatile），`disablePDCache()` 与读取跨线程 → 可见性问题 → 加 `@Volatile` |

### 中
| 位置 | 问题 |
|---|---|
| `AbstractPulsarSession.kt` L62-63 | companion 全局静态计数器 `pageCacheHits/documentCacheHits` —— 进程级共享可变状态，单元测试间互相污染，且无重置入口 |
| `PulsarSession.kt`（2444 行） | **接口过大**：`normalize` 12+ 重载、`get`/`open` 多组、职责横跨 URL 规范化/加载/驱动绑定/事件——缺接口隔离（可拆 role 接口组合） |
| `LoadOptions.kt`（1496 行） | 文件过大；虽 17 个方法多为 JCommander `@Parameter` 声明字段（声明式配置可接受），但单文件可读性差，建议按选项域拆分 |
| `PageSummaryIndexService.kt`（1471 行）/ `StreamingTaskRunner.kt`（1010 行） | God 类风险：职责可继续拆分（索引服务可拆 parser/index/writer） |
| `StreamingTaskRunner.kt` L~60 | 使用 `kotlin.reflect.full.memberProperties` + `isAccessible` 反射读取成员——脆弱（混淆/重构/性能），建议显式访问器 |

### 低
| 位置 | 问题 |
|---|---|
| `AbstractPulsarSession.kt` L46 | 版权头 `Created by Vincent on 18-1-17. Copyright @ 2013-2023` —— 年份过时、作者注释与"团队代码"规范相悖，应只留 license |
| 40 个测试文件 | 命名多为历史 `TestXxx`（TestLoadOptions/TestPulsarOptions/…），与 AGENTS.md 的 `camelCase + @DisplayName` 新规范不一致（存量可分批迁移） |
| `PulsarSession.kt` L135 | 注释笔误 "archive such purpose" → "achieve such purpose" |

## 5. 建议（按优先级）

1. **P0（一行级修复）**：`closableObjects` 换并发集合、`enablePDCache` 加 `@Volatile`——两个数据竞争点，改动各 1-2 行
2. **P1**：PulsarSession 接口按角色拆分（URL/加载/驱动/生命周期），大文件（PageSummaryIndexService/StreamingTaskRunner）做类拆分
3. **P1**：移除 StreamingTaskRunner 的反射成员访问
4. **P2**：测试命名迁移到 camelCase + @DisplayName（配合旧式测试的逐步重写）
5. **P2**：清理版权头年份与注释笔误

## 6. 附带发现（审查流程本身）

审查过程通过 coding 工具链暴露了**2 个脚手架真实 bug**（已修复）：
- `coding.scaffold(type=plugin)` 返回插件**内部相对路径**（`pom.xml`/`src/...`），调用方需自行拼 `browser4-plugins/<name>/` 前缀（文档已说明）
- 生成的 Service 骨架 `fun $toolMethod(driver)` 调用 suspend `driver.evaluateValue` → 编译失败；已给模板加 `suspend`（`ArtifactScaffolds.kt` L259）
