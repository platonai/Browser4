# Kotlin SDK 集成测试设计方案 - 摘要

> 完整设计文档请查看：[INTEGRATION-TEST-DESIGN.md](INTEGRATION-TEST-DESIGN.md)

## 📋 快速概览

本方案设计了一个完整的测试框架，用于启动真实的 Browser4 REST 服务器并使用 Kotlin SDK 进行端到端集成测试。

## 🎯 设计目标

- ✅ 使用真实的 pulsar-rest 服务器（非 mock）
- ✅ 覆盖 Kotlin SDK 的所有主要功能
- ✅ 提供稳定、快速、可维护的测试套件
- ✅ 支持 CI/CD 集成
- ✅ 清晰的文档和示例

## 🏗️ 核心架构

### 测试结构（模块分离）
```
sdks/
├── kotlin-sdk/                              # SDK 模块（保持干净）
│   ├── pom.xml                              # 最小依赖
│   └── src/
│       ├── main/kotlin/                     # SDK 源码
│       └── test/kotlin/                     # 仅单元测试
│
└── kotlin-sdk-tests/                       # 独立测试模块
    ├── pom.xml                              # 所有测试依赖
    └── src/test/kotlin/ai/platon/pulsar/sdk/integration/
        ├── KotlinSdkIntegrationTestBase.kt  # 测试基类
        ├── PulsarClientIntegrationTest.kt   # 客户端测试
        ├── WebDriverIntegrationTest.kt      # WebDriver 测试
        ├── PulsarSessionIntegrationTest.kt  # 会话测试
        ├── AgenticSessionIntegrationTest.kt # AI 功能测试
        ├── server/                           # 测试服务器
        │   ├── PulsarRestServerApplication.kt
        │   └── TestServerConfiguration.kt
        └── util/                             # 工具类
            ├── TestUrls.kt
            └── TestHelpers.kt
```

### 测试基类模板

```kotlin
@SpringBootTest(
    classes = [PulsarRestServerApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(PulsarContextConfiguration::class)
@Tag("IntegrationTest")
abstract class KotlinSdkIntegrationTestBase {
    @LocalServerPort
    protected var serverPort: Int = 0

    protected lateinit var client: PulsarClient
    protected val baseUrl: String get() = "http://localhost:$serverPort"

    @BeforeEach
    fun setupClient() {
        client = PulsarClient(baseUrl = baseUrl)
    }

    @AfterEach
    fun cleanupClient() {
        // 清理会话和资源
    }
}
```

## 📦 测试套件

### 1. PulsarClient 测试
- 会话创建和删除
- HTTP 请求方法（GET, POST, DELETE）
- 错误处理和异常场景

### 2. WebDriver 测试
- 导航功能（navigateTo, currentUrl, reload, goBack, goForward）
- 元素交互（click, fill, type, press, hover）
- 等待机制（waitForSelector, exists, isVisible）
- 滚动操作（scrollDown, scrollUp, scrollTo, scrollToBottom）
- 内容提取（selectFirstTextOrNull, selectTextAll, extract）
- 截图功能（captureScreenshot）
- 脚本执行（executeScript, evaluate）

### 3. PulsarSession 测试
- 页面加载（load, open, submit）
- 数据提取和解析
- URL 规范化
- 批量操作（loadAll）
- 端到端抓取（scrape）

### 4. AgenticSession 测试（可选）
- AI 动作执行（act）
- 自主任务（run）
- 页面观察（observe）
- AI 数据提取（agentExtract）
- 页面摘要（summarize）

## 🔧 技术方案

### 服务器配置
- **REST 服务器**: 使用 Spring Boot Test 启动完整的 pulsar-rest
- **端口策略**: RANDOM_PORT 避免端口冲突
- **Mock 服务器**: 端口 18080 提供测试页面
- **配置隔离**: 专用测试配置文件

### 依赖管理（模块分离）

**kotlin-sdk/pom.xml**（保持最小）:
```xml
<dependencies>
    <!-- 仅 SDK 核心依赖 -->
    <dependency>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-stdlib</artifactId>
    </dependency>
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
    </dependency>
    <!-- 轻量级单元测试 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

**kotlin-sdk-tests/pom.xml**（包含所有测试依赖）:
```xml
<dependencies>
    <!-- SDK 依赖 -->
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>pulsar-sdk-kotlin</artifactId>
    </dependency>
    <!-- 测试服务器依赖 -->
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>pulsar-rest</artifactId>
    </dependency>
    <dependency>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>pulsar-tests-common</artifactId>
    </dependency>
    <!-- Spring Boot Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
    </dependency>
</dependencies>
```

### Maven Profile（在 kotlin-sdk-tests 模块）
```xml
<profile>
    <id>run-integration-tests</id>
    <!-- Profile can be activated directly with -Prun-integration-tests -->
    <properties>
        <skipTests>false</skipTests>
    </properties>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <groups>IntegrationTest</groups>
                    <excludedGroups>RequiresAI</excludedGroups>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

## 🏃 执行方式

### 命令行
```bash
# 运行 SDK 单元测试
cd sdks/kotlin-sdk
mvn test

# 运行集成测试（在独立模块）
cd sdks/kotlin-sdk-tests
mvn test

# 运行所有测试（包括 AI 功能）
cd sdks/kotlin-sdk-tests
mvn test -DrunSDKTests=true

# 从项目根目录运行
mvn test -pl sdks/kotlin-sdk-tests
```

### 本地脚本
```bash
# SDK 单元测试
cd sdks/kotlin-sdk && mvn test

# 集成测试
cd sdks/kotlin-sdk-tests && ./test.sh --integration

# 所有测试
cd sdks/kotlin-sdk-tests && ./test.sh --all
```

# 集成测试
./test.sh --integration

# 所有测试
./test.sh --all
```

## 🏷️ 测试标记

- `@Tag("IntegrationTest")`: 所有集成测试
- `@Tag("RequiresServer")`: 需要 REST 服务器
- `@Tag("RequiresBrowser")`: 需要浏览器环境
- `@Tag("RequiresAI")`: 需要 AI/LLM 配置
- `@Tag("Slow")`: 执行时间较长（> 10 秒）
- `@Tag("Fast")`: 快速测试（< 5 秒）

## ⏱️ 性能目标

| 测试类型 | 目标时间 | 最大时间 |
|---------|---------|---------|
| 单个单元测试 | < 1 秒 | 5 秒 |
| 单个集成测试 | < 10 秒 | 30 秒 |
| 完整单元测试套件 | < 10 秒 | 30 秒 |
| 完整集成测试套件 | < 5 分钟 | 10 分钟 |

## 🔄 实施步骤

### 第一阶段：基础设施（高优先级）
1. 创建测试目录结构
2. 实现测试基类
3. 配置测试服务器
4. 更新 pom.xml

### 第二阶段：基础测试（高优先级）
5. 实现 PulsarClient 集成测试
6. 实现基础 WebDriver 测试

### 第三阶段：完整功能测试（中优先级）
7. 实现完整 WebDriver 测试套件
8. 实现 PulsarSession 测试

### 第四阶段：高级功能（低优先级）
9. 实现 AgenticSession 测试（可选）

### 第五阶段：优化和文档（中优先级）
10. 创建工具类
11. 编写文档和示例
12. 配置 CI/CD 工作流

### 第六阶段：持续改进（低优先级）
13. 性能优化
14. 可靠性增强

## 💡 测试示例

### 简单的导航和提取测试
```kotlin
@Test
fun `should navigate and extract content`() {
    // 1. 创建会话
    val sessionId = client.createSession()
    client.sessionId = sessionId

    // 2. 创建 WebDriver
    val driver = WebDriver(client)

    // 3. 导航到页面
    driver.navigateTo(TestUrls.PRODUCT_DETAIL)

    // 4. 提取内容
    val title = driver.selectFirstTextOrNull("#productTitle")
    assertNotNull(title)
    assertTrue(title.isNotBlank())

    // 5. 清理
    driver.close()
    client.deleteSession()
}
```

### 使用 PulsarSession 抓取数据
```kotlin
@Test
fun `should scrape page with selectors`() {
    // 1. 创建会话
    createSession()
    val session = PulsarSession(client)

    // 2. 定义提取规则
    val selectors = mapOf(
        "title" to "#productTitle",
        "price" to ".a-price-whole",
        "rating" to ".a-icon-star"
    )

    // 3. 抓取数据
    val result = session.scrape(
        url = TestUrls.PRODUCT_DETAIL,
        selectors = selectors
    )

    // 4. 验证结果
    assertNotNull(result)
    assertTrue(result.containsKey("title"))
    assertTrue(result.containsKey("price"))
}
```

## 🔍 CI/CD 集成

### GitHub Actions 工作流
```yaml
name: Kotlin SDK Tests

on:
  push:
    paths:
      - 'sdks/kotlin-sdk/**'
  pull_request:
  schedule:
    - cron: '0 2 * * *'  # 每日夜间构建

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
      - name: Run unit tests
        run: cd sdks/kotlin-sdk && mvn test

  integration-tests:
    runs-on: ubuntu-latest
    needs: unit-tests
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
      - name: Install Chrome
        run: # 安装 Chrome
      - name: Build project
        run: mvn install -DskipTests
      - name: Run integration tests
        run: cd sdks/kotlin-sdk && mvn test -Pintegration-test
```

## 📚 参考资料

### 项目内文档
- 完整设计文档：[INTEGRATION-TEST-DESIGN.md](INTEGRATION-TEST-DESIGN.md)
- SDK README：[README.md](../kotlin-sdk/README.md)
- REST API 示例：[../../docs/rest-api-examples.md](../../docs/rest-api-examples.md)

### 现有测试参考
- REST API 测试：`pulsar-rest/src/test/kotlin/ai/platon/pulsar/rest/api/`
- 集成测试示例：`pulsar-tests/src/test/kotlin/ai/platon/pulsar/integration/rest/`
- Mock 服务器：`pulsar-tests-common/src/main/kotlin/ai/platon/pulsar/test/server/`

### 外部资源
- [JUnit 5 用户指南](https://junit.org/junit5/docs/current/user-guide/)
- [Spring Boot 测试文档](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [Kotlin 测试最佳实践](https://kotlinlang.org/docs/jvm-test-using-junit.html)

## ❓ 常见问题

### Q: 为什么需要真实服务器而不是 mock？
A: 真实服务器测试可以验证完整的请求/响应流程、会话管理、浏览器集成等，确保 SDK 在实际使用场景中正常工作。

### Q: 集成测试执行时间会很长吗？
A: 优化后的集成测试套件目标在 5 分钟内完成，单个测试通常在 10 秒内。

### Q: 如何在 CI 中运行？
A: 使用 Maven Profile：`mvn test -Pintegration-test`，或通过 GitHub Actions 自动化。

### Q: AI 功能测试必须运行吗？
A: 不是必须的。AI 功能测试标记为 `@Tag("RequiresAI")`，默认被排除。需要 LLM API 配置才能运行。

### Q: 如何调试失败的测试？
A:
1. 增加日志级别：`logging.level.ai.platon.pulsar.sdk=DEBUG`
2. 查看服务器日志：`~/.browser4/logs/`
3. 使用 IDE 断点调试
4. 检查测试报告：`target/surefire-reports/`

## ⚠️ 注意事项

1. **设计阶段**：当前仅完成设计，代码实现需要后续进行
2. **环境要求**：集成测试需要 JDK 17+ 和 Chrome/Chromium
3. **资源清理**：确保每个测试后清理会话和浏览器上下文
4. **AI 配置**：AgenticSession 测试需要额外的 LLM API 配置
5. **并行执行**：测试设计支持并行执行，但需要合理配置资源

## 🎉 总结

本设计提供了一个完整、可扩展、易维护的 Kotlin SDK 集成测试方案。通过真实服务器测试，可以：

- ✅ 确保 SDK 在实际环境中正常工作
- ✅ 验证所有 API 功能和错误处理
- ✅ 提供清晰的测试示例和文档
- ✅ 支持 CI/CD 自动化测试
- ✅ 为未来的 SDK 开发提供坚实基础

下一步可根据实施步骤逐步实现测试基础设施和测试用例。

---

**文档版本**: v1.0 (2025-01-13)
**状态**: 设计完成，待实施
