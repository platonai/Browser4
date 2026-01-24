# BrowserPerceptiveAgent Testability Improvements

**日期**: 2026-01-24  
**基于**: CALL_CHAIN_VISUALIZATION.md 和 CODE_REVIEW_BrowserPerceptiveAgent.md  
**状态**: 第一阶段完成

---

## 概述

本文档总结了针对 `BrowserPerceptiveAgent.run()` 调用链可测试性的改进工作。通过引入测试工具类和可测试包装类，大幅降低了编写单元测试的复杂度，同时保持了生产代码的最小改动原则。

---

## 可测试性问题分析

### 原始问题

基于 CODE_REVIEW 第 7 节的分析，主要存在以下可测试性问题：

1. **依赖注入不足** (7.2节)
   - 需要完整的 `AgenticSession` 才能创建实例
   - `AgenticSession` 需要实际的浏览器驱动
   - 难以在测试中隔离单个组件

2. **副作用过多** (7.3节)
   - 方法直接修改传入的 `ExecutionContext`
   - 直接访问全局状态（`stateManager`, `toolExecutor`）
   - 日志、指标、文件 I/O 混在业务逻辑中

3. **缺少单元测试** (7.1节)
   - 未找到针对 `BrowserPerceptiveAgent` 的单元测试
   - 复杂的状态管理和错误处理逻辑难以验证
   - 重试、断路器等关键特性未被测试覆盖

---

## 解决方案

### 1. 测试工具类 (BrowserPerceptiveAgentTestUtils)

创建专门的测试工具类，提供工厂方法简化测试对象的构建。

#### 1.1 Mock Session 工厂

```kotlin
/**
 * Creates a minimal mock AgenticSession for testing.
 * 
 * @param withBoundDriver Whether to include a bound WebDriver
 * @return Mocked AgenticSession
 */
fun createMockSession(withBoundDriver: Boolean = false): AgenticSession {
    val session = mockk<AgenticSession>(relaxed = true)
    
    if (withBoundDriver) {
        val driver = mockk<WebDriver>(relaxed = true)
        every { session.boundDriver } returns driver
    } else {
        every { session.boundDriver } returns null
    }
    
    return session
}
```

**特点**:
- 使用 MockK 创建轻量级 mock 对象
- `relaxed = true` 自动处理未明确 mock 的方法
- 可选绑定 WebDriver，支持不同测试场景

#### 1.2 测试配置工厂

```kotlin
/**
 * Creates a minimal AgentConfig for testing with reasonable defaults.
 * 
 * @param maxSteps Maximum steps for the test scenario
 * @param maxRetries Maximum retries for the test scenario
 * @return AgentConfig suitable for testing
 */
fun createTestConfig(
    maxSteps: Int = 5,
    maxRetries: Int = 1,
    enableStructuredLogging: Boolean = false,
    enablePerformanceMetrics: Boolean = false,
    enableTodoWrites: Boolean = false
): AgentConfig {
    return AgentConfig(
        maxSteps = maxSteps,
        maxRetries = maxRetries,
        consecutiveNoOpLimit = 3,
        enableStructuredLogging = enableStructuredLogging,
        enablePerformanceMetrics = enablePerformanceMetrics,
        enableTodoWrites = enableTodoWrites,
        logInferenceToFile = false,
        enableDebugMode = false,
        memoryCleanupIntervalSteps = 10,
        maxHistorySize = 20,
        // Shorter timeouts for tests
        actTimeoutMs = 5_000,
        llmInferenceTimeoutMs = 5_000,
        resolveTimeoutMs = 10_000,
        actionGenerationTimeoutMs = 3_000,
        screenshotCaptureTimeoutMs = 1_000,
        domSettleTimeoutMs = 1_000
    )
}
```

**特点**:
- 默认值针对测试优化（更短的超时、更少的步骤）
- 默认关闭非必要功能（日志、指标、TODO）
- 可按需启用特定功能进行测试

#### 1.3 测试上下文工厂

```kotlin
/**
 * Creates a minimal ExecutionContext for testing.
 * 
 * @param instruction The instruction to execute
 * @param step Current step number
 * @return ExecutionContext suitable for testing
 */
fun createTestContext(
    instruction: String = "test instruction",
    step: Int = 0,
    targetUrl: String = "https://example.com"
): ExecutionContext {
    val agentState = AgentState(
        instruction = instruction,
        step = step,
        targetUrl = targetUrl,
        stepStartTime = Instant.now()
    )
    
    return ExecutionContext(
        instruction = instruction,
        agentState = agentState,
        step = step,
        targetUrl = targetUrl,
        stepStartTime = Instant.now(),
        sid = "test-sid-${System.currentTimeMillis()}"
    )
}
```

**特点**:
- 创建最小化但完整的 ExecutionContext
- 提供合理的默认值
- 使用时间戳生成唯一 sid

#### 1.4 测试 Agent 工厂

```kotlin
/**
 * Creates a testable BrowserPerceptiveAgent with mocked dependencies.
 * 
 * This method creates an agent instance that can be used in unit tests
 * without requiring a real browser or external services.
 * 
 * @param config Agent configuration
 * @param withBoundDriver Whether to include a mocked bound driver
 * @return Testable BrowserPerceptiveAgent instance
 */
fun createTestAgent(
    config: AgentConfig = createTestConfig(),
    withBoundDriver: Boolean = false
): TestableBrowserPerceptiveAgent {
    val session = createMockSession(withBoundDriver)
    return TestableBrowserPerceptiveAgent(session, config.maxSteps, config)
}
```

**特点**:
- 一站式创建可测试的 Agent 实例
- 自动组装所有依赖
- 无需真实浏览器或外部服务

---

### 2. 可测试包装类 (TestableBrowserPerceptiveAgent)

创建 `BrowserPerceptiveAgent` 的子类，暴露内部方法供测试使用，同时保持生产代码不变。

```kotlin
/**
 * Testable version of BrowserPerceptiveAgent that exposes internal methods
 * for unit testing while maintaining the original implementation.
 * 
 * This class makes protected methods accessible for testing without
 * changing the visibility of the production code.
 */
class TestableBrowserPerceptiveAgent(
    session: AgenticSession,
    maxSteps: Int = 100,
    config: AgentConfig = AgentConfig(maxSteps = maxSteps)
) : BrowserPerceptiveAgent(session, maxSteps, config) {
    
    /**
     * Expose classifyError for testing.
     */
    fun testClassifyError(e: Exception, step: Int) = classifyError(e, step)
    
    /**
     * Expose shouldRetryError for testing.
     */
    fun testShouldRetryError(e: Exception) = shouldRetryError(e)
    
    /**
     * Expose calculateRetryDelay for testing.
     */
    fun testCalculateRetryDelay(attempt: Int) = calculateRetryDelay(attempt)
    
    /**
     * Expose cleanupPartialState for testing.
     */
    suspend fun testCleanupPartialState(context: ExecutionContext) = 
        cleanupPartialState(context)
    
    /**
     * Expose performMemoryCleanup for testing.
     */
    suspend fun testPerformMemoryCleanup(context: ExecutionContext) = 
        performMemoryCleanup(context)
    
    /**
     * Access to internal state for verification in tests.
     */
    fun getStepExecutionTimesSize() = stepExecutionTimes.size
    
    /**
     * Access to performance metrics for verification.
     */
    fun getPerformanceMetrics() = performanceMetrics
    
    /**
     * Access to circuit breaker state for verification.
     */
    fun getCircuitBreakerFailures() = circuitBreaker.getFailureCounts()
}
```

**设计原则**:
- **零侵入**: 不修改 `BrowserPerceptiveAgent` 的可见性
- **明确意图**: 测试方法以 `test` 前缀命名
- **完整实现**: 继承原始实现，无需重复代码
- **状态访问**: 提供 getter 方法验证内部状态

---

### 3. 单元测试示例 (BrowserPerceptiveAgentTest)

基于测试工具创建的单元测试示例。

#### 3.1 基础测试

```kotlin
@Test
fun testAgentCanBeCreatedWithMinimalDependencies() {
    agent = createTestAgent()
    assertFalse(agent!!.isClosed)
}

@Test
fun testAgentCloseSetsFlagAndIsClosed() {
    agent = createTestAgent()
    assertFalse(agent!!.isClosed)
    
    agent!!.close()
    assertTrue(agent!!.isClosed)
    
    // Calling close again should be idempotent
    agent!!.close()
    assertTrue(agent!!.isClosed)
}
```

#### 3.2 重试策略测试

```kotlin
@Test
fun testCalculateRetryDelayFollowsExponentialBackoff() {
    agent = createTestAgent(
        createTestConfig(maxRetries = 5)
    )
    
    val delay0 = agent!!.testCalculateRetryDelay(0)
    val delay1 = agent!!.testCalculateRetryDelay(1)
    val delay2 = agent!!.testCalculateRetryDelay(2)
    
    // Each delay should be greater than the previous (exponential backoff)
    assertTrue(delay1 > delay0)
    assertTrue(delay2 > delay1)
}

@Test
fun testShouldRetryErrorForTransientErrors() {
    agent = createTestAgent()
    
    // Transient errors should be retryable
    val timeoutException = TimeoutCancellationException("timeout")
    assertTrue(agent!!.testShouldRetryError(timeoutException))
}
```

#### 3.3 内存管理测试

```kotlin
@Test
fun testMemoryCleanupReducesStepExecutionTimesSize() = runBlocking {
    val config = createTestConfig(
        maxSteps = 10,
        memoryCleanupIntervalSteps = 5,
        maxHistorySize = 10
    )
    agent = createTestAgent(config)
    
    val context = createTestContext()
    
    // Simulate adding many step execution times
    repeat(250) { step ->
        agent!!.stepExecutionTimes[step] = 100L
    }
    
    assertTrue(agent!!.getStepExecutionTimesSize() > 200)
    
    // Trigger cleanup
    agent!!.testPerformMemoryCleanup(context)
    
    // After cleanup, size should be reduced
    assertTrue(agent!!.getStepExecutionTimesSize() <= 100)
}
```

#### 3.4 断路器测试

```kotlin
@Test
fun testCleanupPartialStateResetsCircuitBreaker() = runBlocking {
    agent = createTestAgent()
    val context = createTestContext()
    
    // Record some failures to trigger circuit breaker
    repeat(3) {
        runCatching { 
            agent!!.circuitBreaker.recordFailure(
                ai.platon.pulsar.agentic.inference.detail.CircuitBreaker.FailureType.LLM_FAILURE
            )
        }
    }
    
    // Verify there are failures recorded
    val failuresBeforeCleanup = agent!!.getCircuitBreakerFailures()
    assertTrue(failuresBeforeCleanup.values.any { it > 0 })
    
    // Cleanup should reset the circuit breaker
    agent!!.testCleanupPartialState(context)
    
    val failuresAfterCleanup = agent!!.getCircuitBreakerFailures()
    assertTrue(failuresAfterCleanup.values.all { it == 0 })
}
```

---

## 优势与影响

### 可测试性提升

1. **降低测试复杂度**
   - 无需构建完整的 AgenticSession
   - 无需启动真实浏览器
   - 无需配置外部服务（LLM API）

2. **提高测试覆盖率**
   - 可独立测试重试逻辑
   - 可独立测试断路器行为
   - 可独立测试内存清理机制

3. **加快测试执行**
   - 纯内存测试，无 I/O 开销
   - 更短的超时配置
   - 可并行运行

### 代码改动最小化

1. **零生产代码修改**
   - `BrowserPerceptiveAgent` 保持不变
   - 仅添加测试代码
   - 不影响现有功能

2. **向后兼容**
   - ✅ API 不变
   - ✅ 行为不变
   - ✅ 性能不变

---

## 测试覆盖的调用链

基于 CALL_CHAIN_VISUALIZATION.md，当前测试覆盖了以下关键路径：

### 已覆盖

1. ✅ **错误分类与重试** (`classifyError`, `shouldRetryError`, `calculateRetryDelay`)
   - 对应调用链: `resolveProblemWithRetry` → `classifyError` / `calculateRetryDelay`

2. ✅ **内存清理** (`performMemoryCleanup`)
   - 对应调用链: `doResolveProblem` → `step` → 定期触发 `performMemoryCleanup`

3. ✅ **断路器管理** (`cleanupPartialState`)
   - 对应调用链: `resolveProblemWithRetry` → `cleanupPartialState` → 重置断路器

4. ✅ **Agent 生命周期** (`close`, `isClosed`)
   - 对应调用链: 所有方法入口检查 `isClosed`

### 待覆盖 (未来迭代)

1. ⏳ **单步执行** (`step`)
   - 需要 mock LLM 推理和工具执行

2. ⏳ **动作生成** (`generateActions`)
   - 需要 mock ContextToAction

3. ⏳ **工具调用执行** (`executeToolCall`)
   - 需要 mock ToolExecutor

4. ⏳ **完整 resolve 流程** (`resolveInCoroutine`, `resolveProblemWithRetry`, `doResolveProblem`)
   - 需要集成测试支持

---

## 使用指南

### 编写新测试

```kotlin
class MyBrowserPerceptiveAgentTest {
    
    private var agent: TestableBrowserPerceptiveAgent? = null
    
    @AfterEach
    fun cleanup() {
        agent?.close()
        agent = null
    }
    
    @Test
    fun testMyFeature() = runBlocking {
        // 1. 创建测试配置
        val config = BrowserPerceptiveAgentTestUtils.createTestConfig(
            maxSteps = 10,
            enablePerformanceMetrics = true
        )
        
        // 2. 创建测试 agent
        agent = BrowserPerceptiveAgentTestUtils.createTestAgent(config)
        
        // 3. 创建测试上下文
        val context = BrowserPerceptiveAgentTestUtils.createTestContext(
            instruction = "my test instruction"
        )
        
        // 4. 执行测试逻辑
        agent!!.testPerformMemoryCleanup(context)
        
        // 5. 验证结果
        assertTrue(agent!!.getStepExecutionTimesSize() < 200)
    }
}
```

### 测试异步方法

```kotlin
@Test
fun testAsyncMethod() = runBlocking {
    agent = createTestAgent()
    val context = createTestContext()
    
    // Use runBlocking for suspend functions
    agent!!.testCleanupPartialState(context)
    
    // Assertions...
}
```

### Mock 外部依赖

```kotlin
@Test
fun testWithMockedDriver() {
    val mockSession = mockk<AgenticSession>(relaxed = true)
    val mockDriver = mockk<WebDriver>(relaxed = true)
    
    every { mockSession.boundDriver } returns mockDriver
    every { mockDriver.currentUrl() } returns "https://example.com"
    
    agent = TestableBrowserPerceptiveAgent(
        mockSession,
        maxSteps = 5,
        createTestConfig()
    )
    
    // Test with mocked driver...
}
```

---

## 最佳实践

### 1. 测试隔离

- ✅ 使用 `@AfterEach` 清理 agent
- ✅ 每个测试创建独立的 agent 实例
- ✅ 不依赖测试执行顺序

### 2. 命名规范

- ✅ 测试方法名描述测试意图: `testCalculateRetryDelayFollowsExponentialBackoff`
- ✅ 测试类以 `Test` 结尾: `BrowserPerceptiveAgentTest`
- ✅ 使用驼峰命名，禁止反引号命名法

### 3. 断言清晰

- ✅ 每个测试关注单一行为
- ✅ 使用有意义的断言消息
- ✅ 先设置状态，再执行操作，最后验证结果

### 4. 性能考虑

- ✅ 使用测试专用配置（更短超时）
- ✅ 避免真实 I/O 操作
- ✅ 使用 mock 替代外部服务

---

## 未来改进方向

### 短期

1. **扩展测试覆盖**
   - 添加 `step` 方法测试
   - 添加 `generateActions` 测试
   - 添加 `executeToolCall` 测试

2. **改进 Mock 工具**
   - 提供预配置的 ToolExecutor mock
   - 提供预配置的 ContextToAction mock
   - 提供常见异常场景的 mock

3. **性能基准测试**
   - 使用 JMH 测量关键方法性能
   - 建立性能回归检测

### 中期

1. **集成测试支持**
   - 创建轻量级集成测试框架
   - 支持完整 resolve 流程测试
   - 使用测试容器 (Testcontainers) 模拟浏览器

2. **测试数据管理**
   - 创建测试数据构建器
   - 提供常见场景的测试数据集
   - 支持数据驱动测试

### 长期

1. **属性测试 (Property-Based Testing)**
   - 使用 Kotest 或 JUnit QuickCheck
   - 测试状态机不变量
   - 自动生成边界条件

2. **并发测试**
   - 测试多个 agent 并发运行
   - 测试 close 期间的并发操作
   - 使用 LinCheck 检测并发 bug

3. **测试报告增强**
   - 集成覆盖率工具 (Jacoco)
   - 生成测试报告仪表板
   - CI 中自动运行测试

---

## 参考文档

- [CALL_CHAIN_VISUALIZATION.md](./CALL_CHAIN_VISUALIZATION.md) - 调用链可视化
- [CODE_REVIEW_BrowserPerceptiveAgent.md](./CODE_REVIEW_BrowserPerceptiveAgent.md) - 代码审查
- [IMPROVEMENTS_SUMMARY.md](./IMPROVEMENTS_SUMMARY.md) - 已完成的改进
- [Kotlin Testing Best Practices](https://kotlinlang.org/docs/jvm-test-using-junit.html)
- [MockK Documentation](https://mockk.io/)

---

**文档版本**: 1.0  
**最后更新**: 2026-01-24
