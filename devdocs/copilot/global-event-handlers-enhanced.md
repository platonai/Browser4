# GlobalEventHandlers Enhanced Features

**Version:** 4.5.0-SNAPSHOT  
**Last Updated:** 2026-01-17

本文档介绍了 GlobalEventHandlers 的增强功能，包括事件过滤、条件触发、历史记录和作用域处理器管理。

## 目录

1. [概览](#概览)
2. [作用域处理器 (Scoped Handlers)](#作用域处理器-scoped-handlers)
3. [事件过滤 (Event Filtering)](#事件过滤-event-filtering)
4. [事件历史记录 (Event History)](#事件历史记录-event-history)
5. [最佳实践](#最佳实践)
6. [API 参考](#api-参考)

---

## 概览

GlobalEventHandlers 现在支持以下增强功能：

### 核心特性

- **线程安全**: 使用 `AtomicReference` 保证多线程环境下的安全性
- **作用域处理器**: 自动管理临时处理器的生命周期
- **事件过滤**: 基于条件过滤事件，实现条件触发
- **历史记录**: 记录事件历史用于调试和审计
- **向后兼容**: 完全兼容现有 API

### 架构改进

```
┌─────────────────────────────────────────────────────┐
│            GlobalEventHandlers (object)              │
├─────────────────────────────────────────────────────┤
│  • pageEventHandlers (AtomicReference)              │
│  • serverSideEventHandlers (AtomicReference)        │
│  • eventFilter (AtomicReference)                    │
│  • eventHistory (ConcurrentLinkedDeque)             │
│  • historyConfig (AtomicReference)                  │
├─────────────────────────────────────────────────────┤
│  Methods:                                            │
│  • withHandlers { }                                 │
│  • withServerSideHandlers { }                       │
│  • withBothHandlers { }                             │
│  • configureHistory()                               │
│  • getEventHistory()                                │
│  • emitXxxEvent() [with filtering & recording]      │
└─────────────────────────────────────────────────────┘
```

---

## 作用域处理器 (Scoped Handlers)

作用域处理器提供了一种自动管理临时处理器生命周期的机制，确保处理器在使用后自动恢复。

### 基本用法

#### 1. 使用 withHandlers

```kotlin
val tempHandlers = DefaultPageEventHandlers().apply {
    loadEventHandlers.onLoaded.addLast { page ->
        println("Temporary handler: ${page.url}")
    }
}

// 自动恢复之前的处理器
GlobalEventHandlers.withHandlers(tempHandlers) {
    session.load(url, options)
    // tempHandlers 在这里活跃
}
// 之前的处理器已自动恢复
```

#### 2. 使用 withServerSideHandlers

```kotlin
val customHandlers = DefaultServerSideEventHandlers()

GlobalEventHandlers.withServerSideHandlers(customHandlers) {
    // 在这个作用域内收集事件
    launch {
        customHandlers.eventFlow.collect { event ->
            println("Event: ${event.eventType}")
        }
    }
    
    session.load(url, options)
}
// 之前的 server-side handlers 已自动恢复
```

#### 3. 同时使用两种处理器

```kotlin
GlobalEventHandlers.withBothHandlers(
    pageHandlers = myPageHandlers,
    serverHandlers = myServerHandlers
) {
    // 两种处理器都在这里活跃
    session.load(url, options)
}
// 两种处理器都已恢复
```

### 异常安全

即使在发生异常时，作用域处理器也会自动恢复：

```kotlin
try {
    GlobalEventHandlers.withHandlers(tempHandlers) {
        session.load(url, options)
        throw RuntimeException("Something went wrong")
    }
} catch (e: Exception) {
    // 即使抛出异常，原始处理器也已恢复
}
```

### REST API 集成

CommandService 已更新为使用作用域处理器：

```kotlin
// 旧方式（手动管理）
val previous = GlobalEventHandlers.serverSideEventHandlers
GlobalEventHandlers.serverSideEventHandlers = handlers
try {
    executeCommand()
} finally {
    GlobalEventHandlers.serverSideEventHandlers = previous
}

// 新方式（自动管理）
GlobalEventHandlers.withServerSideHandlers(handlers) {
    executeCommand()
}
```

---

## 事件过滤 (Event Filtering)

事件过滤允许您基于条件选择性地处理事件，实现条件触发。

### EventFilter 类型

```kotlin
typealias EventFilter = (eventType: String, eventPhase: String, url: String?) -> Boolean
```

- **eventType**: 事件类型（例如 "onWillLoad", "onFetched"）
- **eventPhase**: 事件阶段（"crawl", "load", "browse"）
- **url**: 关联的 URL（可能为 null）
- **返回值**: `true` 处理事件，`false` 跳过事件

### 使用示例

#### 1. 按事件类型过滤

```kotlin
// 只处理 "onWillLoad" 和 "onLoaded" 事件
GlobalEventHandlers.eventFilter = { eventType, _, _ ->
    eventType in setOf("onWillLoad", "onLoaded")
}
```

#### 2. 按事件阶段过滤

```kotlin
// 只处理 load 阶段的事件
GlobalEventHandlers.eventFilter = { _, phase, _ ->
    phase == "load"
}
```

#### 3. 按 URL 过滤

```kotlin
// 只处理特定域名的事件
GlobalEventHandlers.eventFilter = { _, _, url ->
    url?.contains("example.com") == true
}
```

#### 4. 组合条件

```kotlin
// 复杂的过滤逻辑
GlobalEventHandlers.eventFilter = { eventType, phase, url ->
    when (phase) {
        "crawl" -> true  // 所有 crawl 事件
        "load" -> eventType.startsWith("on")  // load 阶段特定事件
        "browse" -> url?.contains("important") == true  // 只处理重要页面
        else -> false
    }
}
```

#### 5. 清除过滤器

```kotlin
// 恢复默认行为（处理所有事件）
GlobalEventHandlers.eventFilter = null
```

### 过滤器作用范围

事件过滤器影响：
- ✅ `emitCrawlEvent()` - 会被过滤
- ✅ `emitLoadEvent()` - 会被过滤  
- ✅ `emitBrowseEvent()` - 会被过滤
- ✅ 事件历史记录 - 被过滤的事件不会被记录
- ❌ `pageEventHandlers` - 不受影响（直接调用）

---

## 事件历史记录 (Event History)

事件历史记录功能允许您记录和查询事件历史，用于调试、审计和监控。

### EventHistoryConfig

```kotlin
data class EventHistoryConfig(
    val enabled: Boolean = false,        // 是否启用历史记录
    val maxSize: Int = 1000,            // 最大记录数（0 = 无限制）
    val filter: EventFilter? = null     // 历史记录过滤器
)
```

### EventHistoryRecord

```kotlin
data class EventHistoryRecord(
    val eventType: String,              // 事件类型
    val eventPhase: String,             // 事件阶段
    val url: String? = null,            // URL
    val message: String? = null,        // 消息
    val timestamp: Instant,             // 时间戳
    val metadata: Map<String, Any?>     // 元数据
)
```

### 配置历史记录

#### 1. 启用基本历史记录

```kotlin
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 1000
    )
)
```

#### 2. 带过滤的历史记录

```kotlin
// 只记录特定事件
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 500,
        filter = { eventType, _, _ ->
            eventType.startsWith("onWill")
        }
    )
)
```

#### 3. 无限制历史记录

```kotlin
// 记录所有事件（谨慎使用）
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 0  // 无限制
    )
)
```

### 查询历史记录

#### 1. 获取所有记录

```kotlin
val allEvents = GlobalEventHandlers.getEventHistory()
// 返回按时间倒序排列的记录（最新的在前）
```

#### 2. 限制返回数量

```kotlin
// 获取最近 10 条记录
val recentEvents = GlobalEventHandlers.getEventHistory(limit = 10)
```

#### 3. 按事件类型过滤

```kotlin
// 获取所有 "onWillLoad" 事件
val willLoadEvents = GlobalEventHandlers.getEventHistory(
    eventType = "onWillLoad"
)
```

#### 4. 按事件阶段过滤

```kotlin
// 获取所有 load 阶段的事件
val loadEvents = GlobalEventHandlers.getEventHistory(
    eventPhase = "load"
)
```

#### 5. 组合查询

```kotlin
// 获取最近 5 条 crawl 阶段的 "onWillLoad" 事件
val events = GlobalEventHandlers.getEventHistory(
    limit = 5,
    eventType = "onWillLoad",
    eventPhase = "crawl"
)
```

### 管理历史记录

#### 清除历史

```kotlin
GlobalEventHandlers.clearEventHistory()
```

#### 禁用历史记录

```kotlin
// 禁用历史记录会自动清除现有记录
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(enabled = false)
)
```

#### 获取当前配置

```kotlin
val config = GlobalEventHandlers.getHistoryConfig()
println("History enabled: ${config.enabled}")
println("Max size: ${config.maxSize}")
```

### 实际应用示例

#### 调试爬虫问题

```kotlin
// 启用历史记录
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(enabled = true, maxSize = 100)
)

// 运行爬虫
session.load(url, options)

// 检查发生了什么
val history = GlobalEventHandlers.getEventHistory()
history.forEach { record ->
    println("[${record.timestamp}] ${record.eventPhase}.${record.eventType}: ${record.url}")
}
```

#### 性能监控

```kotlin
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 500,
        filter = { eventType, _, _ ->
            eventType in setOf("onWillFetch", "onFetched")
        }
    )
)

// 分析获取性能
val fetchEvents = GlobalEventHandlers.getEventHistory(eventType = "onFetched")
fetchEvents.groupBy { it.url }.forEach { (url, events) ->
    println("$url: ${events.size} fetches")
}
```

#### 审计特定 URL

```kotlin
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 200,
        filter = { _, _, url ->
            url?.contains("important-site.com") == true
        }
    )
)
```

---

## 最佳实践

### 1. 使用作用域处理器

**推荐** ✅
```kotlin
GlobalEventHandlers.withHandlers(tempHandlers) {
    session.load(url, options)
}
```

**不推荐** ❌
```kotlin
val previous = GlobalEventHandlers.pageEventHandlers
GlobalEventHandlers.pageEventHandlers = tempHandlers
try {
    session.load(url, options)
} finally {
    GlobalEventHandlers.pageEventHandlers = previous
}
```

### 2. 合理使用事件过滤

- 生产环境中使用过滤器减少不必要的事件处理开销
- 开发环境中可以禁用过滤器以获得完整的事件流
- 考虑使用白名单而不是黑名单

```kotlin
// 好的做法：明确指定需要的事件
GlobalEventHandlers.eventFilter = { eventType, _, _ ->
    eventType in setOf("onWillLoad", "onLoaded", "onFetched")
}

// 避免：排除少数事件（容易遗漏）
GlobalEventHandlers.eventFilter = { eventType, _, _ ->
    eventType !in setOf("someRareEvent")
}
```

### 3. 控制历史记录大小

- 设置合理的 `maxSize` 避免内存溢出
- 生产环境中使用历史记录过滤器只记录关键事件
- 定期清理历史记录

```kotlin
// 生产环境：只记录重要事件
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 500,
        filter = { eventType, _, _ ->
            eventType in setOf("onFetched", "onHTMLDocumentParsed")
        }
    )
)

// 开发环境：记录所有事件用于调试
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 1000
    )
)
```

### 4. 事件过滤与历史记录配合使用

```kotlin
// 全局过滤器：只处理 load 阶段
GlobalEventHandlers.eventFilter = { _, phase, _ ->
    phase == "load"
}

// 历史记录过滤器：在 load 阶段中进一步筛选
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 200,
        filter = { eventType, _, _ ->
            eventType in setOf("onFetched", "onHTMLDocumentParsed")
        }
    )
)
```

### 5. 测试时清理状态

```kotlin
@BeforeEach
fun setup() {
    GlobalEventHandlers.eventFilter = null
    GlobalEventHandlers.clearEventHistory()
    GlobalEventHandlers.configureHistory(EventHistoryConfig(enabled = false))
}
```

---

## API 参考

### 作用域处理器 API

```kotlin
// 使用临时 page handlers
inline fun <T> withHandlers(
    handlers: PageEventHandlers?, 
    block: () -> T
): T

// 使用临时 server-side handlers
inline fun <T> withServerSideHandlers(
    handlers: ServerSideEventHandlers?, 
    block: () -> T
): T

// 同时使用两种临时 handlers
inline fun <T> withBothHandlers(
    pageHandlers: PageEventHandlers?,
    serverHandlers: ServerSideEventHandlers?,
    block: () -> T
): T
```

### 事件过滤 API

```kotlin
// EventFilter 类型定义
typealias EventFilter = (
    eventType: String,    // 事件类型
    eventPhase: String,   // 事件阶段 (crawl/load/browse)
    url: String?          // 关联的 URL
) -> Boolean              // true = 处理事件, false = 跳过

// 设置/获取事件过滤器
var eventFilter: EventFilter?
```

### 事件历史 API

```kotlin
// 配置历史记录
fun configureHistory(config: EventHistoryConfig)

// 获取历史配置
fun getHistoryConfig(): EventHistoryConfig

// 查询历史记录
fun getEventHistory(
    limit: Int = 0,              // 限制返回数量 (0 = 全部)
    eventType: String? = null,   // 按事件类型过滤
    eventPhase: String? = null   // 按事件阶段过滤
): List<EventHistoryRecord>

// 清除历史记录
fun clearEventHistory()
```

### 数据类型

```kotlin
data class EventHistoryConfig(
    val enabled: Boolean = false,
    val maxSize: Int = 1000,
    val filter: EventFilter? = null
)

data class EventHistoryRecord(
    val eventType: String,
    val eventPhase: String,
    val url: String? = null,
    val message: String? = null,
    val timestamp: Instant = Instant.now(),
    val metadata: Map<String, Any?> = emptyMap()
)
```

---

## 迁移指南

### 从旧 API 迁移

#### 手动处理器管理 → 作用域处理器

**之前:**
```kotlin
val previous = GlobalEventHandlers.serverSideEventHandlers
GlobalEventHandlers.serverSideEventHandlers = myHandlers
try {
    // 执行操作
    executeCommand()
} finally {
    GlobalEventHandlers.serverSideEventHandlers = previous
}
```

**之后:**
```kotlin
GlobalEventHandlers.withServerSideHandlers(myHandlers) {
    executeCommand()
}
```

### 向后兼容性

所有现有 API 保持不变：

```kotlin
// 仍然可以使用
GlobalEventHandlers.pageEventHandlers = myHandlers
GlobalEventHandlers.serverSideEventHandlers = myServerHandlers

// 仍然有效
GlobalEventHandlers.emitCrawlEvent("onWillLoad", url)
GlobalEventHandlers.emitLoadEvent("onFetched", page)
```

---

## 故障排查

### 常见问题

#### Q: 事件没有被记录到历史中

A: 检查：
1. 历史记录是否已启用：`GlobalEventHandlers.getHistoryConfig().enabled`
2. 是否设置了过滤器：`GlobalEventHandlers.eventFilter`
3. 历史记录过滤器是否过于严格

#### Q: 内存使用过高

A: 减少历史记录大小或添加更严格的过滤器：
```kotlin
GlobalEventHandlers.configureHistory(
    EventHistoryConfig(
        enabled = true,
        maxSize = 100,  // 减小
        filter = { eventType, _, _ ->
            eventType in setOf("onFetched")  // 只记录关键事件
        }
    )
)
```

#### Q: 作用域处理器没有恢复

A: 确保 block 执行完成。如果异常被捕获，检查是否重新抛出：
```kotlin
GlobalEventHandlers.withHandlers(tempHandlers) {
    try {
        doSomething()
    } catch (e: Exception) {
        // 处理但不要吞掉异常
        logger.error("Error", e)
        throw e  // 重新抛出
    }
}
```

---

## 性能考虑

### 事件过滤性能

- 事件过滤器在事件发出时同步执行
- 保持过滤器逻辑简单快速
- 避免在过滤器中进行网络或 I/O 操作

### 历史记录性能

- 历史记录使用 `ConcurrentLinkedDeque`，并发性能良好
- `maxSize` 检查在每次添加时执行
- 大量事件时考虑增加 `maxSize` 或使用更严格的过滤器

---

## 相关文档

- [PageEventHandlers 完整指南](./page-event-handlers.md)
- [ServerSideEventHandlers 文档](../../docs/server-side-event-handlers.md)
- [事件机制测试指南](../../sdks/kotlin-sdk-tests/devdocs/EVENT-MECHANISM-TESTING-GUIDE.md)

---

**变更历史:**
- 2026-01-17: 添加事件过滤、条件触发、历史记录和作用域处理器功能
