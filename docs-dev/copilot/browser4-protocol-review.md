# browser4-protocol 代码审查报告

审查范围：`browser4-plugins/browser4-protocol` 全部 ~45 个 Kotlin 源文件。
审查重点：并发安全性、资源泄漏、架构设计、代码质量。

---

## 一、模块概览

该模块是 Browser4 的浏览器协议层，负责浏览器生命周期管理、driver 池调度、隐私上下文管理和页面加载模拟。核心分层：

| 层 | 关键类 | 职责 |
|----|--------|------|
| 协议入口 | `BrowserEmulatorProtocol` | 转发协议，先查缓存再走浏览器抓取 |
| 组件装配 | `DefaultBrowserComponents` | 依赖注入入口，通过 ObjectCache 单例化 |
| Driver 池 | `WebDriverPoolManager` → `LoadingWebDriverPool` → `ConcurrentStatefulDriverPool` | 三级池：池管理器 → 每浏览器池 → 状态驱动池 |
| 池协调 | `ConcurrentStatefulDriverPoolPool` | working/retired/closed 三态池管理 |
| 关闭器 | `BrowserAccompaniedDriverPoolCloser` | 优雅/强制关闭浏览器+池 |
| 浏览器模拟 | `InteractiveBrowserEmulator` | 页面加载全流程（导航→等待→滚动→特征计算） |
| 隐私上下文 | `MultiPrivacyContextManager` | 隐私上下文轮转、维护、回收 |
| 抓取器 | `PrivacyManagedBrowserFetcher` | 指定 driver/浏览器/隐私上下文三种路径 |
| 完整性 | `HtmlIntegrityChecker` | HTML 质量校验（空页/缺锚点/无 JS 标志） |

---

## 二、P0 — 必须修复

### 1. `PulsarBrowserFactory.kt` 是空文件（0 字节）

`ai/platon/browser4/protocol/browser/PulsarBrowserFactory.kt` 存在于文件系统但内容为空。这要么是遗漏了实现，要么是重构后忘记删除的残留。应删除或补实现。

### 2. `ConcurrentStatefulDriverPool.retire()` 在持锁状态下 `runBlocking`

```kotlin
@Synchronized  // 持有 monitor 锁
fun retire() {
    ...
    drivers.forEach { driver ->
        driver.cancel()
        driver.retire()
        kotlin.runCatching { runBlocking { driver.stop() } }  // 阻塞当前线程
    }
}
```

`retire()` 持有 `@Synchronized` 锁的同时用 `runBlocking` 调用 `driver.stop()`。`driver.stop()` 可能涉及 CDP 网络请求（等待 Chrome 响应），耗时不可控。在此期间，整个 driver 池的所有操作（`poll`/`offer`/`close`）全部被阻塞。

对 10 万~20 万页/天的目标场景，一次 retire 可能阻塞数秒，期间所有等待 driver 的协程全部卡住。**建议**：将 `driver.stop()` 移到锁外异步执行，或改用 `runBlocking` 的超时变体。

### 3. `PrivacyManagedBrowserFetcher` 的 cancel/reset/cancelAll 是空实现

```kotlin
override fun reset() { }
override fun cancel(page: WebPage) { }
override fun cancelAll() { }
```

该类实现了 `IncognitoBrowserFetcher` 接口，但三个核心控制方法全是空方法体。调用方调用 `cancelAll()` 期望停止所有任务时，什么都不会发生。`BrowserEmulatorProtocol.cancelAll()` 调用 `browserEmulator.cancelAll()` 传入的正是这个对象。

**影响**：协议层的取消机制完全失效——当需要紧急停止所有浏览器任务时（如进程关闭、任务超时），页面仍在后台加载。**建议**：委托给 `privacyManager` 或 `driverPoolManager` 的对应方法。

### 4. `LoadingWebDriverPool.close()` 调用 `clear()` 导致 driver 泄漏

```kotlin
override fun close() {
    if (closed.compareAndSet(false, true)) {
        statefulDriverPool.clear()  // 只清引用，不 quit driver
    }
}
```

`clear()` 清空四个队列但不调用 `driver.quit()` 或 `driver.retire()`。Chrome 进程和 CDP 连接不会被释放。在隐私上下文频繁创建/销毁的场景（`MultiPrivacyContextManager.closeDyingContexts` 每次维护周期都可能关闭上下文），这会导致 Chrome 僵尸进程累积。

**建议**：`close()` 应先 `retire()` 所有活跃 driver，再 `close` 每一个，最后 `clear()`。

---

## 三、P1 — 并发与正确性

### 5. `WebDriverPoolManager.lastActiveTime` 非线程安全

```kotlin
var lastActiveTime = startTime   // 非 volatile，非 atomic
val idleTime get() = Duration.between(lastActiveTime, Instant.now())
val isIdle get() = idleTime > idleTimeout
```

`lastActiveTime` 在 `run()` 中被多个协程并发写入（`lastActiveTime = Instant.now()` 在 try 和 finally 中各一次），同时被 `isIdle` 在维护线程中读取。由于非 `@Volatile`，维护线程可能读到过期值，导致：①空闲池未被及时关闭（资源浪费）；②活跃池被误判空闲后关闭（任务中断）。

### 6. `ConcurrentStatefulDriverPoolPool._closeHistory` 无限增长

```kotlin
private val _closeHistory = mutableListOf<BrowserId>()  // 在 close() 中 add，永不清理
```

长期运行（10 万页/天）会累积大量 `BrowserId` 对象，造成内存缓慢增长。虽然每个 `BrowserId` 不大，但这是无界的。**建议**：改为有界队列或定期清理。

### 7. `closeGracefully` 与 `closeForcibly` 实现完全相同

```kotlin
@Synchronized fun closeGracefully(browserId: BrowserId) {
    runCatching { doClose(browserId) }.onFailure { ... }
}
@Synchronized fun closeForcibly(browserId: BrowserId) {
    runCatching { doClose(browserId) }.onFailure { ... }
}
```

"优雅关闭"和"强制关闭"走完全相同的代码路径。`closeForcibly` 应该跳过等待直接 kill 进程，`closeGracefully` 应该给 driver 时间完成当前操作。当前实现无法区分两种语义。

### 8. `computeBrowserAndDriver` 返回值被忽略

```kotlin
private fun resourceSafeCreateDriverIfNecessary(priority: Int, conf: MutableConfig) {
    synchronized(browserManager) {
        ...
        val driver = computeBrowserAndDriver(priority, conf)  // 返回值未使用
    }
}
```

`computeBrowserAndDriver` 创建并返回一个 WebDriver，但调用方忽略了返回值。driver 被放入 `statefulDriverPool` 后靠 `poll()` 取回，这虽然能工作，但创建和获取分两步，中间有时间窗口。如果 `poll` 超时，刚创建的 driver 就被遗弃在 standby 队列里。

---

## 四、P2 — 设计与可维护性

### 9. 事件处理器大量重复（~100 行模板代码）

`InteractiveBrowserEmulator` 的 12 个 `on*` 方法几乎完全相同：

```kotlin
override suspend fun onWillNavigate(page: WebPage, driver: WebDriver) {
    PulsarEventBus.emitBrowseEvent("onWillNavigate", page)
    PulsarEventBus.pageEventHandlers?.browseEventHandlers?.onWillNavigate?.invoke(page, driver)
    page.browseEventHandlers?.onWillNavigate?.invoke(page, driver)
}
```

每个方法只是换事件名和处理器引用。可抽成一个 `emit三级` 辅助方法或用委托减少重复。

### 10. `doCloseWithDiagnosis` 是死代码

`BrowserAccompaniedDriverPoolCloser.doCloseWithDiagnosis` 方法打开 `chrome://version/` 和 `chrome://history/` 页面用于诊断，但该方法从未被调用（`doClose` 才是实际路径）。

### 11. 大量 `require(driver is AbstractWebDriver)` 散布全局

```kotlin
require(driver is AbstractWebDriver)  // 至少出现 15+ 次
```

这是运行时类型检查，如果传入非 `AbstractWebDriver` 实现会抛 `IllegalArgumentException` 而非有意义的领域异常。应该在接口层用泛型约束或在入口处做一次检查。

### 12. `ForwardingProtocol` 缓存阈值是硬编码魔数

```kotlin
if (cache.size > 100) { logger.warn(...) }
if (cache.size > 1000) { cache.clear() }  // 静默清空
```

100 和 1000 是硬编码的，且 `cache.clear()` 会静默丢弃所有待转发响应。应改为可配置 + 逐条淘汰而非全清。

### 13. 注释掉的代码

多处存在被注释的代码：
- `WebDriverPoolManager`: `// require(result == deferred)` (×2)
- `InteractiveBrowserEmulator`: `// it.contentType = response.contentType()`
- `BrowserAccompaniedDriverPoolCloser`: `// _subscribedDrivers.add(driver)` (×2)
- `MultiPrivacyContextManager`: `// driverPoolManager.maintain()`

应删除或补上 `TODO` 说明。

---

## 五、亮点

1. **三级池设计清晰**：`WebDriverPoolManager` → `LoadingWebDriverPool` → `ConcurrentStatefulDriverPool`，每层职责明确，状态机（standby → working → retired → closed）完整。
2. **`PreemptChannelSupport` 抢占式通道**：维护操作（关闭空闲池）通过 preempt 机制独占执行，不与正常任务竞争，避免了维护操作导致的死锁。
3. **资源感知创建**：`shouldCreateWebDriver()` 检查内存/CPU/磁盘临界状态后才创建新 driver，在系统过载时主动降级。
4. **隐私上下文生命周期管理**：`closeDyingContexts` 按四种条件（inactive/idle/highFailure/permanent-idle）回收上下文，并有节流日志避免日志风暴。
5. **HTML 完整性链式检查**：`ChainedHtmlIntegrityChecker` 支持按 URL 相关性组合多个检查器，可扩展性好。

---

## 六、改进优先级

| 优先级 | 编号 | 问题 | 建议 |
|--------|------|------|------|
| P0 | 1 | 空文件 PulsarBrowserFactory.kt | 删除或补实现 |
| P0 | 2 | retire() 持锁 runBlocking | driver.stop() 移到锁外 |
| P0 | 3 | cancel/reset/cancelAll 空实现 | 委托给 poolManager |
| P0 | 4 | close() 不 quit driver | 先 retire 再 close |
| P1 | 5 | lastActiveTime 非线程安全 | 改 @Volatile 或 AtomicReference |
| P1 | 6 | _closeHistory 无限增长 | 有界队列或定期清理 |
| P1 | 7 | gracefully/forcibly 实现相同 | 区分超时策略 |
| P1 | 8 | 返回值被忽略 | 直接返回创建的 driver |
| P2 | 9 | 事件处理器重复 | 抽辅助方法 |
| P2 | 10 | doCloseWithDiagnosis 死代码 | 删除或接入 |
| P2 | 11 | require 类型检查散布 | 入口统一检查 |
| P2 | 12 | 缓存阈值硬编码 | 可配置 |
| P2 | 13 | 注释代码 | 清理 |
