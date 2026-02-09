# Kotlin SDK 集成测试设计方案

## 目录
1. [概述](#概述)
2. [测试架构](#测试架构)
3. [测试服务器配置](#测试服务器配置)
4. [测试套件设计](#测试套件设计)
5. [测试数据和页面](#测试数据和页面)
6. [测试执行策略](#测试执行策略)
7. [依赖和构建配置](#依赖和构建配置)
8. [CI/CD 集成](#cicd-集成)
9. [性能和可靠性](#性能和可靠性)
10. [实施步骤](#实施步骤)

---

## 概述

### 目标
设计并实现一个完整的测试框架，用于启动真实的 Browser4 REST 服务器，并使用 Kotlin SDK 进行端到端集成测试。

### 关键原则
- **真实环境**: 使用完整的 pulsar-rest 服务器，而非 mock
- **隔离性**: 每个测试独立运行，避免相互影响
- **可维护性**: 清晰的结构，易于扩展和维护
- **快速反馈**: 合理的执行时间，支持增量测试
- **可靠性**: 稳定的测试，减少 flaky tests

### 测试范围
- ✅ PulsarClient 基础 API
- ✅ WebDriver 浏览器自动化
- ✅ PulsarSession 会话管理
- ✅ AgenticSession AI 功能（可选）
- ✅ 错误处理和异常场景
- ✅ 并发和性能测试（未来）

---

## 测试架构

### 模块结构
```
sdks/
├── kotlin-sdk/                                   # SDK 模块（保持干净）
│   ├── pom.xml                                   # 最小依赖，仅 SDK 核心
│   └── src/
│       ├── main/kotlin/ai/platon/pulsar/sdk/
│       │   ├── PulsarClient.kt
│       │   ├── WebDriver.kt
│       │   ├── AgenticSession.kt
│       │   └── Models.kt
│       └── test/kotlin/ai/platon/pulsar/sdk/    # 仅单元测试
│           ├── PulsarClientTest.kt
│           ├── WebDriverTest.kt
│           └── SessionTest.kt
│
└── kotlin-sdk-tests/                            # 独立测试模块
    ├── pom.xml                                   # 包含所有测试依赖
    └── src/test/
        ├── kotlin/ai/platon/pulsar/sdk/integration/
        │   ├── KotlinSdkIntegrationTestBase.kt
        │   ├── PulsarClientIntegrationTest.kt
        │   ├── WebDriverIntegrationTest.kt
        │   ├── PulsarSessionIntegrationTest.kt
        │   ├── AgenticSessionIntegrationTest.kt
        │   ├── server/
        │   │   ├── PulsarRestServerApplication.kt
        │   │   └── TestServerConfiguration.kt
        │   └── util/
        │       ├── TestUrls.kt
        │       └── TestHelpers.kt
        └── resources/
            └── application-sdk-integration-test.properties
```

### 测试基础类

#### KotlinSdkIntegrationTestBase
所有集成测试的基类，提供：
- 服务器生命周期管理
- SDK 客户端创建和清理
- 通用工具方法
- 测试前置和后置处理

```kotlin
package ai.platon.pulsar.sdk.integration

import ai.platon.pulsar.boot.autoconfigure.PulsarContextConfiguration
import ai.platon.pulsar.sdk.PulsarClient
import ai.platon.pulsar.sdk.integration.server.PulsarRestServerApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import kotlin.test.assertTrue

/**
 * 集成测试基类
 *
 * 特性：
 * - 自动启动完整的 Browser4 REST 服务器
 * - 使用随机端口避免冲突
 * - 自动配置和清理 SDK 客户端
 * - 提供测试工具方法
 */
@SpringBootTest(
    classes = [PulsarRestServerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(PulsarContextConfiguration::class)
@Tag("IntegrationTest")
@Tag("RequiresServer")
abstract class KotlinSdkIntegrationTestBase {

    /**
     * Spring Boot 注入的服务器端口
     */
    @LocalServerPort
    protected var serverPort: Int = 0

    /**
     * SDK 客户端实例
     */
    protected lateinit var client: PulsarClient

    /**
     * 服务器基础 URL
     */
    protected val baseUrl: String
        get() = "http://localhost:$serverPort"

    /**
     * 每个测试前设置
     */
    @BeforeEach
    fun setupClient() {
        assertTrue(serverPort > 0, "Server port should be assigned")
        client = PulsarClient(baseUrl = baseUrl, timeout = java.time.Duration.ofSeconds(60))
    }

    /**
     * 每个测试后清理
     */
    @AfterEach
    fun cleanupClient() {
        try {
            // 尝试删除会话（如果存在）
            if (client.sessionId != null) {
                client.deleteSession()
            }
        } catch (e: Exception) {
            // 忽略清理错误
        } finally {
            client.close()
        }
    }

    /**
     * 创建新会话并设置到客户端
     */
    protected fun createSession(): String {
        val sessionId = client.createSession()
        client.sessionId = sessionId
        return sessionId
    }

    /**
     * 等待条件满足或超时
     */
    protected fun waitUntil(
        timeoutSeconds: Int = 10,
        intervalMillis: Long = 500,
        condition: () -> Boolean
    ): Boolean {
        val endTime = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < endTime) {
            if (condition()) {
                return true
            }
            Thread.sleep(intervalMillis)
        }
        return false
    }
}
```

---

## 测试服务器配置

### PulsarRestServerApplication

```kotlin
package ai.platon.pulsar.sdk.integration.server

import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.ImportResource
import org.springframework.test.context.ContextConfiguration

/**
 * 测试服务器应用
 *
 * 启动完整的 Browser4 REST API 服务器用于 SDK 集成测试
 */
@SpringBootApplication
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@ComponentScan(
    basePackages = [
        "ai.platon.pulsar.boot.autoconfigure",
        "ai.platon.pulsar.rest.api",
        "ai.platon.pulsar.test.server"  // Mock 站点服务器
    ]
)
@ImportResource("classpath:rest-beans/app-context.xml")
class PulsarRestServerApplication
```

### TestServerConfiguration

```kotlin
package ai.platon.pulsar.sdk.integration.server

import ai.platon.pulsar.test.server.MockSiteApplication
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import java.net.ServerSocket

/**
 * Mock 测试站点服务器配置
 *
 * 在端口 18080 启动 Mock EC 服务器，提供测试页面
 */
@TestConfiguration
class TestServerConfiguration : InitializingBean, DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)
    private var mockServerContext: ConfigurableApplicationContext? = null

    companion object {
        const val MOCK_SERVER_PORT = 18080
    }

    override fun afterPropertiesSet() {
        startMockServer()
    }

    override fun destroy() {
        stopMockServer()
    }

    private fun startMockServer() {
        if (isPortInUse(MOCK_SERVER_PORT)) {
            log.info("Mock server already running on port $MOCK_SERVER_PORT")
            return
        }

        log.info("Starting mock server on port $MOCK_SERVER_PORT...")
        val app = SpringApplication(MockSiteApplication::class.java)
        app.setDefaultProperties(mapOf(
            "server.port" to MOCK_SERVER_PORT.toString(),
            "spring.main.banner-mode" to "off"
        ))

        Thread {
            mockServerContext = app.run()
        }.apply {
            isDaemon = true
            start()
        }

        // 等待服务器启动
        waitForServerStart()
    }

    private fun stopMockServer() {
        mockServerContext?.close()
        mockServerContext = null
    }

    private fun waitForServerStart() {
        repeat(30) {
            if (isPortInUse(MOCK_SERVER_PORT)) {
                log.info("Mock server started successfully")
                return
            }
            Thread.sleep(1000)
        }
        log.warn("Mock server may not have started successfully")
    }

    private fun isPortInUse(port: Int): Boolean {
        return try {
            ServerSocket(port).use { false }
        } catch (e: Exception) {
            true
        }
    }
}
```

### 配置文件

`src/test/resources/application-sdk-integration-test.properties`:

```properties
# Browser4 测试配置

# 服务器配置
spring.main.allow-bean-definition-overriding=true
spring.main.banner-mode=off

# 浏览器配置
browser.context.mode=TEMPORARY
browser.context.cleanup=true

# 性能配置
pulsar.context.task.scheduler.pool.size=2
pulsar.context.task.scheduler.enabled=false

# 日志配置
logging.level.root=WARN
logging.level.ai.platon.pulsar=INFO
logging.level.ai.platon.pulsar.sdk=DEBUG

# 超时配置
pulsar.common.net.connect.timeout=10000
pulsar.common.net.read.timeout=30000

# 测试模式
pulsar.stub.mode=true
```

---

## 测试套件设计

### 1. PulsarClientIntegrationTest

测试 PulsarClient 的基础功能：

```kotlin
package ai.platon.pulsar.sdk.integration

import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * PulsarClient 集成测试
 */
class PulsarClientIntegrationTest : KotlinSdkIntegrationTestBase() {

    @Test
    fun `should create and delete session`() {
        // 创建会话
        val sessionId = client.createSession()
        assertNotNull(sessionId)
        assertTrue(sessionId.isNotBlank())

        // 验证会话已设置
        client.sessionId = sessionId
        assertEquals(sessionId, client.sessionId)

        // 删除会话
        client.deleteSession()

        // 清空会话 ID
        client.sessionId = null
        assertNull(client.sessionId)
    }

    @Test
    fun `should handle session with capabilities`() {
        val capabilities = mapOf(
            "browserName" to "chrome",
            "pageLoadStrategy" to "normal"
        )

        val sessionId = client.createSession(capabilities)
        assertNotNull(sessionId)

        client.sessionId = sessionId
        client.deleteSession()
    }

    @Test
    fun `should make GET request`() {
        val sessionId = createSession()

        // GET 当前 URL（应该返回空或默认值）
        val result = client.get("/session/$sessionId/url")
        assertNotNull(result)
    }

    @Test
    fun `should make POST request`() {
        val sessionId = createSession()

        // POST 导航到 URL
        val url = "${TestUrls.MOCK_SERVER_BASE}/ec/"
        val result = client.post(
            "/session/$sessionId/url",
            mapOf("url" to url)
        )

        // 应该成功返回
        assertNotNull(result)
    }

    @Test
    fun `should handle errors gracefully`() {
        // 不创建会话，直接尝试访问
        assertFailsWith<IllegalStateException> {
            client.post("/session/{sessionId}/url", mapOf("url" to "https://example.com"))
        }
    }
}
```

### 2. WebDriverIntegrationTest

测试 WebDriver 的浏览器自动化功能：

```kotlin
package ai.platon.pulsar.sdk.integration

import ai.platon.pulsar.sdk.WebDriver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * WebDriver 集成测试
 */
@Tag("RequiresBrowser")
class WebDriverIntegrationTest : KotlinSdkIntegrationTestBase() {

    private lateinit var driver: WebDriver

    @BeforeEach
    fun setupDriver() {
        createSession()
        driver = WebDriver(client)
    }

    @Test
    fun `should navigate to URL`() {
        val url = TestUrls.SIMPLE_PAGE
        driver.navigateTo(url)

        val currentUrl = driver.currentUrl()
        assertNotNull(currentUrl)
        assertTrue(currentUrl.contains(url) || currentUrl == url)
    }

    @Test
    fun `should get page title`() {
        driver.navigateTo(TestUrls.SIMPLE_PAGE)

        val title = driver.title()
        assertNotNull(title)
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `should check element exists`() {
        driver.navigateTo(TestUrls.SIMPLE_PAGE)

        // 检查常见元素
        assertTrue(driver.exists("body"))
        assertTrue(driver.exists("html"))
    }

    @Test
    fun `should extract text content`() {
        driver.navigateTo(TestUrls.PRODUCT_DETAIL)

        val title = driver.selectFirstTextOrNull("#productTitle")
        assertNotNull(title)
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `should scroll page`() {
        driver.navigateTo(TestUrls.PRODUCT_LIST)

        // 滚动到底部
        driver.scrollToBottom()

        // 滚动到顶部
        driver.scrollToTop()
    }

    @Test
    fun `should capture screenshot`() {
        driver.navigateTo(TestUrls.SIMPLE_PAGE)

        val screenshot = driver.captureScreenshot()
        assertNotNull(screenshot)
        assertTrue(screenshot.isNotEmpty())
    }

    @Test
    fun `should execute script`() {
        driver.navigateTo(TestUrls.SIMPLE_PAGE)

        val result = driver.executeScript("return document.title")
        assertNotNull(result)
    }

    @Test
    fun `should wait for selector`() {
        driver.navigateTo(TestUrls.SIMPLE_PAGE)

        // 等待 body 元素
        driver.waitForSelector("body", timeout = 5000)
        assertTrue(driver.exists("body"))
    }

    @Test
    fun `should extract multiple fields`() {
        driver.navigateTo(TestUrls.PRODUCT_DETAIL)

        val fields = driver.extract(mapOf(
            "title" to "#productTitle",
            "price" to ".a-price-whole"
        ))

        assertNotNull(fields)
        assertTrue(fields.containsKey("title"))
    }
}
```

### 3. PulsarSessionIntegrationTest

测试 PulsarSession 的页面加载和数据提取：

```kotlin
package ai.platon.pulsar.sdk.integration

import ai.platon.pulsar.sdk.PulsarSession
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * PulsarSession 集成测试
 */
class PulsarSessionIntegrationTest : KotlinSdkIntegrationTestBase() {

    private lateinit var session: PulsarSession

    @BeforeEach
    fun setupSession() {
        createSession()
        session = PulsarSession(client)
    }

    @Test
    fun `should normalize URL`() {
        val url = "https://example.com"
        val normalized = session.normalize(url)

        assertNotNull(normalized)
        assertTrue(normalized.isNotBlank())
    }

    @Test
    fun `should load page`() {
        val url = TestUrls.PRODUCT_DETAIL
        val page = session.load(url)

        assertNotNull(page)
        assertFalse(page.isNil)
        assertEquals(url, page.url)
    }

    @Test
    fun `should open page immediately`() {
        val url = TestUrls.SIMPLE_PAGE
        val page = session.open(url)

        assertNotNull(page)
        assertFalse(page.isNil)
    }

    @Test
    fun `should scrape page with selectors`() {
        val url = TestUrls.PRODUCT_DETAIL
        val selectors = mapOf(
            "title" to "#productTitle",
            "price" to ".a-price-whole"
        )

        val result = session.scrape(url, selectors = selectors)

        assertNotNull(result)
        assertTrue(result.containsKey("title"))
    }

    @Test
    fun `should load multiple pages`() {
        val urls = listOf(
            TestUrls.SIMPLE_PAGE,
            TestUrls.PRODUCT_DETAIL
        )

        val pages = session.loadAll(urls)

        assertEquals(urls.size, pages.size)
        pages.forEach { page ->
            assertNotNull(page)
            assertFalse(page.isNil)
        }
    }
}
```

### 4. AgenticSessionIntegrationTest（可选）

测试 AI 驱动的浏览器自动化：

```kotlin
package ai.platon.pulsar.sdk.integration

import ai.platon.pulsar.sdk.AgenticSession
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * AgenticSession 集成测试
 *
 * 注意：这些测试需要 AI/LLM 配置才能运行
 */
@Tag("RequiresAI")
@Disabled("Requires AI/LLM configuration")
class AgenticSessionIntegrationTest : KotlinSdkIntegrationTestBase() {

    private lateinit var session: AgenticSession

    @BeforeEach
    fun setupSession() {
        createSession()
        session = AgenticSession(client)
    }

    @Test
    fun `should execute single action`() {
        session.driver.navigateTo(TestUrls.SIMPLE_PAGE)

        val result = session.act("scroll to the bottom")

        assertNotNull(result)
        // AI 功能可能返回不同结果，只验证不抛异常
    }

    @Test
    fun `should run autonomous task`() {
        val result = session.run("visit ${TestUrls.PRODUCT_DETAIL} and summarize the product")

        assertNotNull(result)
    }

    @Test
    fun `should observe page`() {
        session.driver.navigateTo(TestUrls.PRODUCT_DETAIL)

        val observation = session.observe("find interactive elements")

        assertNotNull(observation)
        assertNotNull(observation.observations)
    }

    @Test
    fun `should extract data with AI`() {
        session.driver.navigateTo(TestUrls.PRODUCT_DETAIL)

        val extraction = session.agentExtract("extract product name, price, and description")

        assertNotNull(extraction)
        assertNotNull(extraction.data)
    }

    @Test
    fun `should summarize page`() {
        session.driver.navigateTo(TestUrls.PRODUCT_DETAIL)

        val summary = session.summarize()

        assertNotNull(summary)
        assertTrue(summary.isNotBlank())
    }
}
```

---

## 测试数据和页面

### TestUrls 工具类

```kotlin
package ai.platon.pulsar.sdk.integration.util

/**
 * 测试 URL 常量
 */
object TestUrls {
    /**
     * Mock 服务器基础 URL
     */
    const val MOCK_SERVER_BASE = "http://localhost:18080"

    /**
     * 简单静态页面（用于基础导航测试）
     */
    const val SIMPLE_PAGE = "$MOCK_SERVER_BASE/ec/"

    /**
     * 产品列表页面
     */
    const val PRODUCT_LIST = "$MOCK_SERVER_BASE/ec/b?node=1292115012"

    /**
     * 产品详情页面
     */
    const val PRODUCT_DETAIL = "$MOCK_SERVER_BASE/ec/dp/B0E000001"

    /**
     * 验证 Mock 服务器是否运行
     */
    fun isMockServerRunning(): Boolean {
        return try {
            java.net.URL(MOCK_SERVER_BASE).openConnection().apply {
                connectTimeout = 5000
                readTimeout = 5000
            }.getInputStream().close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
```

### TestHelpers 工具类

```kotlin
package ai.platon.pulsar.sdk.integration.util

import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * 测试辅助工具
 */
object TestHelpers {

    /**
     * 重试执行直到成功或超时
     */
    fun <T> retry(
        maxAttempts: Int = 3,
        delay: Duration = Duration.ofSeconds(1),
        block: () -> T
    ): T {
        var lastException: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(delay.toMillis())
                }
            }
        }

        throw lastException ?: IllegalStateException("Retry failed")
    }

    /**
     * 等待条件满足
     */
    fun waitFor(
        timeout: Duration = Duration.ofSeconds(10),
        pollInterval: Duration = Duration.ofMillis(500),
        condition: () -> Boolean
    ) {
        val endTime = System.currentTimeMillis() + timeout.toMillis()

        while (System.currentTimeMillis() < endTime) {
            if (condition()) {
                return
            }
            Thread.sleep(pollInterval.toMillis())
        }

        throw TimeoutException("Condition not met within ${timeout.seconds} seconds")
    }

    /**
     * 生成唯一的测试会话 ID
     */
    fun generateTestSessionId(): String {
        return "test-session-${System.currentTimeMillis()}-${(0..9999).random()}"
    }
}
```

---

## 测试执行策略

### 标记和分类

使用 JUnit 5 的 `@Tag` 注解对测试进行分类：

- `@Tag("IntegrationTest")`: 所有集成测试
- `@Tag("RequiresServer")`: 需要 REST 服务器
- `@Tag("RequiresBrowser")`: 需要浏览器环境
- `@Tag("RequiresAI")`: 需要 AI/LLM 配置
- `@Tag("Slow")`: 执行时间较长的测试（> 10 秒）
- `@Tag("Fast")`: 快速测试（< 5 秒）

### 执行命令

```bash
# 运行 SDK 单元测试
cd sdks/kotlin-sdk
mvn test

# 运行集成测试（在独立测试模块）
cd sdks/kotlin-sdk-tests
mvn test

# 运行所有测试（包括 AI）
cd sdks/kotlin-sdk-tests
mvn test -DrunSDKTests=true

# 从项目根目录运行
mvn test -pl sdks/kotlin-sdk-tests

# 运行特定标签的测试
mvn test -pl sdks/kotlin-sdk-tests -Dgroups="IntegrationTest,!Slow"

# 跳过慢速测试
mvn test -pl sdks/kotlin-sdk-tests -Dgroups="IntegrationTest,!Slow"
```

### 测试顺序

建议的测试执行顺序（从快到慢）：

1. **快速单元测试**（无需服务器）
   - PulsarClientTest
   - WebDriverTest
   - ModelsTest

2. **基础集成测试**（需要服务器）
   - PulsarClientIntegrationTest
   - 基础 WebDriver 导航测试

3. **中等复杂度测试**
   - WebDriverIntegrationTest（完整套件）
   - PulsarSessionIntegrationTest

4. **慢速测试**
   - 复杂的数据提取场景
   - 多页面加载测试
   - AgenticSessionIntegrationTest（AI 功能）

---

## 依赖和构建配置

### 模块分离策略

为了保持 SDK 的 pom.xml 干净和最小化，集成测试被提取到独立模块 `kotlin-sdk-tests`：

#### kotlin-sdk/pom.xml（保持最小）

```xml
<dependencies>
    <!-- 仅 SDK 核心依赖 -->
    <dependency>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-stdlib</artifactId>
        <version>${kotlin.version}</version>
    </dependency>
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.10.1</version>
    </dependency>

    <!-- 单元测试依赖（轻量级） -->
    <dependency>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-test-junit5</artifactId>
        <version>${kotlin.version}</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.1</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

#### kotlin-sdk-tests/pom.xml（包含所有测试依赖）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>ai.platon.pulsar</groupId>
    <artifactId>pulsar-sdk-kotlin-tests</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>Browser4 Kotlin SDK Integration Tests</name>

    <properties>
        <!-- 默认跳过测试，需要显式运行 -->
        <skipTests>true</skipTests>
        <kotlin.version>2.2.21</kotlin.version>
        <pulsar.version>4.5.0-rc.1</pulsar.version>
        <spring.boot.version>3.2.1</spring.boot.version>
    </properties>

    <dependencies>
        <!-- SDK 依赖 -->
        <dependency>
            <groupId>ai.platon.pulsar</groupId>
            <artifactId>pulsar-sdk-kotlin</artifactId>
            <version>${project.version}</version>
        </dependency>

        <!-- Pulsar 测试依赖 -->
        <dependency>
            <groupId>ai.platon.pulsar</groupId>
            <artifactId>pulsar-rest</artifactId>
            <version>${pulsar.version}</version>
        </dependency>
        <dependency>
            <groupId>ai.platon.pulsar</groupId>
            <artifactId>pulsar-tests-common</artifactId>
            <version>${pulsar.version}</version>
        </dependency>

        <!-- Spring Boot Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <version>${spring.boot.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <version>${spring.boot.version}</version>
        </dependency>

        <!-- 测试框架 -->
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-test-junit5</artifactId>
            <version>${kotlin.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.1</version>
        </dependency>
    </dependencies>
</project>
```

### Maven Surefire 配置

```xml
<build>
    <plugins>
        <!-- Kotlin 编译 -->
        <plugin>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-maven-plugin</artifactId>
            <version>${kotlin.version}</version>
            <executions>
                <execution>
                    <id>compile</id>
                    <phase>compile</phase>
                    <goals>
                        <goal>compile</goal>
                    </goals>
                </execution>
                <execution>
                    <id>test-compile</id>
                    <phase>test-compile</phase>
                    <goals>
                        <goal>test-compile</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>

        <!-- Surefire 测试插件 -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.2.5</version>
            <configuration>
                <!-- 默认排除集成测试 -->
                <excludedGroups>IntegrationTest</excludedGroups>
                <!-- 并行执行配置 -->
                <parallel>methods</parallel>
                <threadCount>2</threadCount>
                <!-- 失败处理 -->
                <rerunFailingTestsCount>1</rerunFailingTestsCount>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Maven Profile（在 kotlin-sdk-tests/pom.xml）

```xml
<profiles>
    <!-- 运行集成测试 Profile -->
    <profile>
        <id>run-integration-tests</id>
        <!-- Profile can be activated directly with -Prun-integration-tests -->
        <properties>
            <skipTests>false</skipTests>
        </properties>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                        <!-- 运行集成测试，排除 AI 测试 -->
                        <groups>IntegrationTest</groups>
                        <excludedGroups>RequiresAI</excludedGroups>
                        <forkedProcessTimeoutInSeconds>600</forkedProcessTimeoutInSeconds>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>

    <!-- 完整测试（包括 AI 功能） -->
    <profile>
        <id>run-full-tests</id>
        <activation>
            <property>
                <name>runSDKTests</name>
                <value>true</value>
            </property>
        </activation>
        <properties>
            <skipTests>false</skipTests>
        </properties>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                        <groups>IntegrationTest</groups>
                        <!-- 不排除任何测试 -->
                        <excludedGroups></excludedGroups>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

### 执行命令

```bash
# 运行 SDK 单元测试
cd sdks/kotlin-sdk
mvn test

# 运行集成测试（在独立模块）
cd sdks/kotlin-sdk-tests
mvn test

# 或使用 Profile
mvn test -Prun-integration-tests

# 运行所有测试（包括 AI）
mvn test -DrunSDKTests=true
# 或
mvn test -Prun-full-tests

# 在项目根目录运行所有 SDK 相关测试
mvn test -pl sdks/kotlin-sdk,sdks/kotlin-sdk-tests
```

---

## CI/CD 集成

### GitHub Actions 工作流

创建 `.github/workflows/kotlin-sdk-test.yml`:

```yaml
name: Kotlin SDK Tests

on:
  push:
    branches: [ main, master, develop ]
    paths:
      - 'sdks/kotlin-sdk/**'
      - 'pulsar-rest/**'
      - '.github/workflows/kotlin-sdk-test.yml'
  pull_request:
    branches: [ main, master, develop ]
    paths:
      - 'sdks/kotlin-sdk/**'
      - 'pulsar-rest/**'
  schedule:
    # 每日夜间构建
    - cron: '0 2 * * *'
  workflow_dispatch:

jobs:
  unit-tests:
    name: Unit Tests
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Run unit tests
        run: |
          cd sdks/kotlin-sdk
          mvn test

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-results
          path: sdks/kotlin-sdk/target/surefire-reports/

  integration-tests:
    name: Integration Tests
    runs-on: ubuntu-latest
    needs: unit-tests

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Install Chrome
        run: |
          wget -q -O - https://dl-ssl.google.com/linux/linux_signing_key.pub | sudo apt-key add -
          sudo sh -c 'echo "deb [arch=amd64] http://dl.google.com/linux/chrome/deb/ stable main" >> /etc/apt/sources.list.d/google.list'
          sudo apt-get update
          sudo apt-get install -y google-chrome-stable

      - name: Build project
        run: mvn install -DskipTests -pl pulsar-core,pulsar-rest,pulsar-tests-common -am

      - name: Run integration tests
        run: |
          cd sdks/kotlin-sdk
          mvn test -Pintegration-test
        env:
          DISPLAY: :99

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: integration-test-results
          path: sdks/kotlin-sdk/target/surefire-reports/

      - name: Upload logs
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: integration-test-logs
          path: |
            sdks/kotlin-sdk/target/*.log
            ~/.browser4/logs/

  publish-results:
    name: Publish Test Results
    runs-on: ubuntu-latest
    needs: [unit-tests, integration-tests]
    if: always()

    steps:
      - name: Download test results
        uses: actions/download-artifact@v4

      - name: Publish test results
        uses: EnricoMi/publish-unit-test-result-action@v2
        with:
          files: |
            **/*.xml
```

### 本地测试脚本

创建 `sdks/kotlin-sdk/test.sh`:

```bash
#!/bin/bash

set -e

echo "=== Kotlin SDK Test Runner ==="

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 解析参数
RUN_UNIT=true
RUN_INTEGRATION=false
RUN_ALL=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --integration)
            RUN_INTEGRATION=true
            RUN_UNIT=false
            shift
            ;;
        --all)
            RUN_ALL=true
            RUN_UNIT=true
            RUN_INTEGRATION=true
            shift
            ;;
        --unit)
            RUN_UNIT=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--unit|--integration|--all]"
            exit 1
            ;;
    esac
done

# 运行单元测试
if [ "$RUN_UNIT" = true ]; then
    echo -e "${YELLOW}Running unit tests...${NC}"
    mvn test
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Unit tests passed${NC}"
    else
        echo -e "${RED}✗ Unit tests failed${NC}"
        exit 1
    fi
fi

# 运行集成测试
if [ "$RUN_INTEGRATION" = true ]; then
    echo -e "${YELLOW}Running integration tests...${NC}"

    # 检查 Chrome 是否安装
    if ! command -v google-chrome &> /dev/null; then
        echo -e "${YELLOW}Warning: Chrome not found. Some tests may fail.${NC}"
    fi

    # 运行集成测试
    mvn test -Pintegration-test
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Integration tests passed${NC}"
    else
        echo -e "${RED}✗ Integration tests failed${NC}"
        exit 1
    fi
fi

echo -e "${GREEN}=== All tests completed successfully ===${NC}"
```

使用方式：

```bash
# 仅运行单元测试（默认）
./test.sh

# 运行集成测试
./test.sh --integration

# 运行所有测试
./test.sh --all
```

---

## 性能和可靠性

### 测试稳定性策略

1. **适当的等待时间**
   - 使用显式等待而非固定 sleep
   - 配置合理的超时时间
   - 实现重试机制

2. **资源清理**
   - 每个测试后关闭会话
   - 清理浏览器上下文
   - 避免资源泄漏

3. **隔离性**
   - 每个测试使用独立的会话
   - 避免测试间的状态共享
   - 使用随机端口避免冲突

4. **错误处理**
   - 捕获并记录详细的错误信息
   - 区分测试失败和环境问题
   - 提供清晰的失败原因

### 性能目标

| 测试类型 | 目标时间 | 最大时间 |
|---------|---------|---------|
| 单个单元测试 | < 1 秒 | 5 秒 |
| 单个集成测试 | < 10 秒 | 30 秒 |
| 完整单元测试套件 | < 10 秒 | 30 秒 |
| 完整集成测试套件 | < 5 分钟 | 10 分钟 |
| Mock 服务器启动 | < 5 秒 | 10 秒 |

### 优化建议

1. **并行执行**
   - 使用 Maven Surefire 的并行执行功能
   - 每个测试类使用独立的会话
   - 避免共享状态

2. **智能跳过**
   - 检测环境（如 Chrome 是否安装）
   - 跳过不可用的测试
   - 提供清晰的跳过原因

3. **缓存优化**
   - Maven 依赖缓存
   - 浏览器安装缓存
   - 测试数据缓存

---

## 实施步骤

### 第一阶段：基础设施（优先级：高）

1. ✅ **创建独立测试模块**
   - [ ] 创建 `sdks/kotlin-sdk-tests/` 目录
   - [ ] 创建 `kotlin-sdk-tests/pom.xml`
   - [ ] 配置模块依赖（SDK + pulsar-rest + Spring Boot Test）

2. ✅ **创建测试目录结构**
   - [ ] `src/test/kotlin/ai/platon/pulsar/sdk/integration/`
   - [ ] `server/` 子目录
   - [ ] `util/` 子目录
   - [ ] `src/test/resources/`

3. ✅ **实现测试基类**
   - [ ] `KotlinSdkIntegrationTestBase`
   - [ ] 服务器生命周期管理
   - [ ] 客户端创建和清理

4. ✅ **配置测试服务器**
   - [ ] `PulsarRestServerApplication`
   - [ ] `TestServerConfiguration`
   - [ ] `application-sdk-integration-test.properties`

5. ✅ **配置 Maven Profile**
   - [ ] `run-integration-tests` Profile
   - [ ] `run-full-tests` Profile
   - [ ] Surefire 插件配置

### 第二阶段：基础测试（优先级：高）

6. ✅ **实现 PulsarClient 测试**
   - [ ] 会话创建/删除
   - [ ] HTTP 请求方法
   - [ ] 错误处理

7. ✅ **实现基础 WebDriver 测试**
   - [ ] 导航功能
   - [ ] 元素查找
   - [ ] 基础交互

### 第三阶段：完整功能测试（优先级：中）

8. ✅ **实现完整 WebDriver 测试**
   - [ ] 所有 API 方法
   - [ ] 错误场景
   - [ ] 边界情况

9. ✅ **实现 PulsarSession 测试**
   - [ ] 页面加载
   - [ ] 数据提取
   - [ ] 批量操作

### 第四阶段：高级功能（优先级：低）

10. ⚠️ **实现 AgenticSession 测试**（可选）
    - [ ] AI 动作执行
    - [ ] 自主任务
    - [ ] 页面观察和提取

### 第五阶段：优化和文档（优先级：中）

11. ✅ **创建工具类**
    - [ ] `TestUrls`
    - [ ] `TestHelpers`
    - [ ] 测试数据工厂

12. ✅ **编写文档**
    - [ ] 测试运行指南
    - [ ] 故障排除
    - [ ] 最佳实践

13. ✅ **配置 CI/CD**
    - [ ] GitHub Actions 工作流
    - [ ] 本地测试脚本
    - [ ] 测试报告生成

### 第六阶段：持续改进（优先级：低）

14. ⚠️ **性能优化**
    - [ ] 并行执行调优
    - [ ] 缓存策略
    - [ ] 资源使用优化

15. ⚠️ **可靠性增强**
    - [ ] Flaky 测试分析
    - [ ] 重试机制优化
    - [ ] 错误诊断改进

---

## 常见问题

### Q1: 如何在本地运行集成测试？

```bash
cd sdks/kotlin-sdk
mvn test -Pintegration-test
```

### Q2: 为什么集成测试被跳过？

默认情况下，集成测试被排除在常规测试之外。使用 `-Pintegration-test` 显式运行。

### Q3: 如何调试失败的集成测试？

1. 增加日志级别：设置 `logging.level.ai.platon.pulsar.sdk=DEBUG`
2. 检查服务器日志：`~/.browser4/logs/`
3. 使用 IDE 断点调试

### Q4: 集成测试需要多长时间？

- 完整套件：约 5-10 分钟
- 单个测试类：约 30-60 秒
- 单个测试方法：约 5-15 秒

### Q5: 如何跳过需要 AI 的测试？

默认情况下，`@Tag("RequiresAI")` 的测试会被排除。要运行它们：

```bash
mvn test -Pfull-test
```

### Q6: 服务器启动失败怎么办？

1. 检查端口是否被占用
2. 确保有足够的内存（建议 4GB+）
3. 查看启动日志确定具体错误

### Q7: 如何添加新的测试？

1. 继承 `KotlinSdkIntegrationTestBase`
2. 添加适当的 `@Tag` 注解
3. 在 `@BeforeEach` 中创建会话
4. 编写测试方法
5. 在 `@AfterEach` 中清理资源

---

## 参考资料

### 内部文档
- [Pulsar REST API 文档](../../docs/rest-api-examples.md)
- [配置指南](../../docs/config.md)
- [构建指南](../../docs/build.md)

### 外部资源
- [JUnit 5 用户指南](https://junit.org/junit5/docs/current/user-guide/)
- [Spring Boot 测试文档](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [Kotlin 测试最佳实践](https://kotlinlang.org/docs/jvm-test-using-junit.html)

### 示例项目
- `pulsar-rest/src/test/kotlin/` - REST API 测试示例
- `pulsar-tests/src/test/kotlin/integration/` - 集成测试示例

---

## 版本历史

- **v1.0** (2025-01-13): 初始设计文档
- 未来版本将根据实施反馈更新

---

## 贡献者

- 设计：AI Copilot
- 审核：待定
- 实施：待定

---

**注意**: 这是一个设计文档，具体实现可能会根据实际情况进行调整。
