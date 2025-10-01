# 🚦 AI Coder Agent Guideline

This guideline defines how AI Coder Agents (e.g., GitHub Copilot, Claude Coder, ChatGPT) should behave when contributing 
to this project. The document standardizes **environment setup**, **coding rules**, **testing strategy**, 
and **CI/CD integration**.

> 💡 Note: This document should be updated as the functionality evolves.

---

## 1. 🛠 Environment Setup

- **Project Type**: Multi-module **Maven** project
- **Primary Language**: **Kotlin**
- **Build Tool**: Always use `./mvnw` (Maven wrapper) from the project root
- **System Adaptation**: Detect **OS environment first** to select best-suited tools  
- **Java Compatibility**: Read `pom.xml` determine Java versions

---

## 2. 📖 General Guidelines

- Prefer **data classes** for DTOs and state holders
- Keep functions **small and single-responsibility**
- Document all **public functions and classes** with KDoc
- Original (Chinese): 如果在任何 README-AI.md 中遇到中文条款，将其翻译为英文。例外：1. 中文引用，2. 明确要求保留中文。
- Translation: If any Chinese clauses appear in a README-AI.md, translate them into English unless (1) they are direct quotations, or (2) the text explicitly requires keeping Chinese.

## 3. 🤖 AI Agent Behavior

Provide consistent, minimal, verifiable contributions:

- Environment Detection:
  - Always detect OS (Windows/macOS/Linux) before suggesting shell commands; prefer cross-platform forms.
  - Use the Maven wrapper: `./mvnw` (Unix) or `mvnw.cmd` (Windows) from project root.
- Editing Rules:
  - Do not mass‑reformat unrelated code; restrict diffs to purposeful changes.
  - Preserve license headers and existing comment style.
  - Avoid introducing new dependencies unless absolutely necessary; justify in commit/PR description.
- Code Generation:
  - Favor pure functions and immutability for new utilities.
  - Provide minimal, focused unit tests for each new public function (happy path + 1 edge case).
  - When adding an abstraction (interface/strategy), include a concise rationale in KDoc.
- Testing Integration:
  - New features must include at least one `@Tag("UnitTest")` test.
  - For behavior relying on external services (LLM, network), inject a strategy or generator to allow deterministic offline tests.
  - Use the system property `-Dpulsar.tta.disableLLM=true` to disable model calls in unit tests.
- Prompt / LLM Aware Code:
  - Keep model/system prompts short, structured, and versionable as constants.
  - Centralize parsing of model outputs; avoid scattering ad hoc JSON parsing.
- Documentation:
  - Update the closest README-* file when adding or changing a developer-facing concept.
  - Translate any lingering Chinese lines per the translation rule above.
- Security & Safety:
  - Never log secrets or raw authorization headers.
  - Validate untrusted input (selectors, URLs) before execution.
- Performance:
  - Defer heavy initialization (LLM clients, large caches) until first use (lazy or on-demand pattern).
- PR Hygiene:
  - Keep commits logically grouped (docs, refactor, feature, test).
  - Reference related issue/ticket IDs in commit messages when available.

---

## 4. 📂 Project Structure Rules

- **Root layout**:
```
project-root/
├── pulsar-core/            # Core modules
│    ├── pulsar-skeleton/   # Core WebDriver & AI translation logic
│    ├── pulsar-common/     # Common utilities and shared code
│    ├── pulsar-dom/        # DOM manipulation and parsing
│    ├── pulsar-persist/    # Data persistence layer
│    ├── pulsar-plugins/    # Plugin system
│    ├── pulsar-ql/         # Query language implementation
│    ├── pulsar-ql-common/  # Query language common components
│    ├── pulsar-resources/  # Resource management
│    ├── pulsar-spring-support/ # Spring framework integration
│    ├── pulsar-third/      # Third-party integrations
│    └── pulsar-tools/      # Development and utility tools
├── pulsar-rest/            # REST API and web services
├── pulsar-client/          # Client libraries and SDKs
├── pulsar-tests/           # Centralized test packages
├── pulsar-examples/        # Example code and tutorials
├── browser4/              # Browser automation modules
│    ├── browser4-crawler/ # Web crawling functionality
│    └── browser4-spa/     # Single Page Application support
├── pulsar-all/            # Aggregation module
├── pulsar-bom/            # Bill of Materials
└── pom.xml                # Root Maven configuration
```

- **Module guidelines**:
  - Each module must define its own `pom.xml`
  - No cyclic dependencies between modules
  - Shared utilities go to `pulsar-core/pulsar-common`
  - AI client/utilities in `pulsar-third/pulsar-llm`

---

## 7. 🧪 Testing Rules

### 7.1 🏗️ Test Architecture & Structure

- **Test Module Organization**:
  - **`pulsar-tests/`**: Centralized integration tests and E2E tests
  - **`pulsar-tests-common/`**: Shared test utilities and base classes
  - **Individual modules**: Unit tests within each module under `src/test/kotlin/`

- **Test Base Classes**:
  - **`TestBase`**: Fundamental test configuration and Spring context
  - **`TestWebSiteAccess`**: Inherit for tests requiring test website access (start a mock server automatically)
  - **`WebDriverTestBase`**: Inherit for WebDriver-based automation tests
  - **Test website resources**: Located in `pulsar-tests/src/main/resources/static/`

### 7.2 📝 Test Naming & Organization

- **File Naming Conventions**:
  ```
  Unit tests:        <ClassName>Test.kt
  Integration tests: <ClassName>IT.kt or <ClassName>Tests.kt
  E2E tests:         <ClassName>E2ETest.kt
  ```

- **Method Naming**: Use descriptive backtick names following BDD style:
  ```kotlin
  @Test
  fun `Given valid user input When processing request Then return expected result`()
  
  @Test
  fun `When ask to click a button then generate correct WebDriver action code`()
  ```

- **Package Structure**: Mirror main source packages under `src/test/kotlin/`

### 7.3 🏷️ Test Categorization & Annotations

- **Test Categories**:
  ```kotlin
  @Tag("UnitTest")           // Fast, isolated unit tests
  @Tag("IntegrationTest")    // Tests with external dependencies
  @Tag("E2ETest")           // End-to-end browser automation tests
  @Tag("ExternalServiceTest") // Tests requiring external services (LLM, APIs)
  @Tag("TimeConsumingTest")  // Long-running tests (>30 seconds)
  @Tag("HeavyTest")         // Resource-intensive tests
  @Tag("SmokeTest")         // Critical path validation tests
  ```

- **Spring Test Configuration**:
  ```kotlin
  @SpringBootTest(classes = [Application::class], 
                  webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
  ```

### 7.4 🎯 Test Types & Strategies

- **Unit Tests** (70% of test suite):
  - **Scope**: Individual classes, pure functions, utilities
  - **Focus**: Core logic, data transformations, algorithms
  - **Isolation**: No external dependencies, use mocks/stubs
  - **Speed**: < 100ms per test
  
- **Integration Tests** (25% of test suite):
  - **Scope**: Module interactions, database operations, Spring context
  - **Focus**: Component integration, configuration validation
  - **Dependencies**: Real Spring context, test databases
  - **Speed**: < 5 seconds per test

- **E2E Tests** (5% of test suite):
  - **Scope**: Full user workflows, browser automation
  - **Focus**: AI → WebDriver command correctness, UI interactions
  - **Dependencies**: Real browsers, test websites
  - **Speed**: < 30 seconds per test

### 7.5 🔧 Configuration & Environment

- **Property Binding**: Spring-style relaxed binding support
  ```
  first-name ≈ first_name ≈ firstName ≈ first.name
  server.port ≈ SERVER_PORT
  ```

- **Environment Consistency**: 
  - Behavior identical with/without Spring `Environment`
  - Test profiles: `test`, `integration`, `e2e`
  - Separate test configurations for different test types

- **Test Data Management**:
  ```
  src/test/resources/
  ├── application-test.properties    # Test-specific configuration
  ├── test-data/                     # Static test datasets
  ├── fixtures/                      # Test fixtures and samples
  └── static/                        # Test web pages and assets
  ```

### 7.6 🎭 Mocking & Test Doubles

- **Mocking Strategy**:
  - **Unit tests**: Mock external dependencies extensively
  - **Integration tests**: Real Spring beans, mock external services only
  - **E2E tests**: Minimize mocking, use real systems where possible
  - Local Mock Server (Original Chinese guidance retained for context):
    > 如果需要访问网页，优先使用 Mock Server 的网页进行测试
    - English: If a test needs to access a webpage, prefer using the Local Mock Server pages.
    - Inherit `TestWebSiteAccess` to auto‑start the Local Mock Server
    - Static web resources path: `pulsar-tests-common/src/main/resources/static`
    - Generated test pages may be placed under `pulsar-tests-common/src/main/resources/static/generated/`

- **Recommended Libraries**:
  ```kotlin
  // Kotlin-friendly mocking
  @MockK lateinit var mockService: ExternalService
  
  // Spring Boot test slices
  @WebMvcTest(Controller::class)
  @DataJpaTest
  @JsonTest
  ```

### 7.7 📊 Performance & Load Testing

- **Performance Test Guidelines**:
  - **Benchmark tests**: Use `@Tag("BenchmarkTest")` for performance regression detection
  - **Load tests**: Simulate realistic user loads for critical paths
  - **Memory tests**: Validate memory usage patterns for large datasets

- **Test Timeouts**:
  ```kotlin
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  fun `Heavy operation completes within time limit`()
  ```

### 7.8 📈 Coverage & Quality Expectations

- **Coverage Targets**:
  - **Overall**: Minimum 70% instruction coverage (JaCoCo)
  - **Core modules**: 80%+ coverage for business logic
  - **Utilities**: 90%+ coverage for reusable components
  - **Controllers**: 85%+ coverage including error paths

- **Quality Metrics**:
  - **Code coverage**: Enforced in CI pipeline
  - **Test execution time**: Total test suite < 10 minutes
  - **Flaky test tolerance**: Zero tolerance for flaky tests
  - **Test maintenance**: Regular cleanup of obsolete tests

### 7.9 🚀 CI/CD Integration

- **Test Execution Phases**:
  1. **Unit tests**: Run on every commit
  2. **Integration tests**: Run on PR and main branch
  3. **E2E tests**: Run nightly and before releases
  4. **Performance tests**: Run weekly and before major releases

- **Parallel Execution**:
  ```xml
  <!-- Maven Surefire configuration -->
  <parallel>methods</parallel>
  <threadCount>4</threadCount>
  <perCoreThreadCount>true</perCoreThreadCount>
  ```

- **Test Reporting**:
  - JUnit XML reports for CI integration
  - JaCoCo coverage reports published to CI
  - Failed test artifacts preserved for debugging

### 7.10 🛠️ Testing Tools & Utilities

- **Core Testing Stack**:
  - **JUnit 5**: Primary testing framework
  - **Mockk**: Kotlin-native mocking library
  - **Spring Boot Test**: Integration testing support
  - **Selenium WebDriver**: Browser automation
  - **TestContainers**: Integration testing with real services

- **Custom Test Utilities**:
  - **WebDriver factories**: Standardized browser setup
  - **Test data builders**: Fluent test data creation
  - **Assertion helpers**: Domain-specific validations
  - **Test fixtures**: Reusable test scenarios

## 8. 🔬 Benchmarks (JMH)
- Module: `pulsar-benchmarks` (not part of deploy/release profile)
- Execution:
  ```bash
  ./mvnw -pl pulsar-benchmarks -am package -DskipTests
  java -jar pulsar-benchmarks/target/pulsar-benchmarks-*-shaded.jar -f 1 -wi 3 -i 5
  ```
- Conventions:
  - Put micro benchmarks close to core algorithms (string processing, DOM, parsing, scoring, ql)
  - Keep benchmark state isolated (`@State(Scope.Thread)` unless shared)
  - Always include a short KDoc: purpose + metric + potential regression trigger
  - Avoid external I/O in JMH loops; pre-load data in `@Setup`

## 9. 🧩 AI Templates & Resources
Located under `docs/copilot/templates/`:
- `response-template.md` – Standard AI agent reply format
- `pr-description-template.md` – PR description scaffold
- `test-tag-usage.md` – Tag examples (`UnitTest`, `IntegrationTest`, `E2ETest`, `BenchmarkTest` ...)

Usage Guidance:
- When generating a PR: fill all mandatory sections (Summary, Motivation, Risk, Rollback)
- For performance-sensitive change: add provisional benchmark in `pulsar-benchmarks` referencing ticket ID
- For flaky test triage: cite tag + recent runtime + failure signature

## 10. ♻️ Future Improvements (Tracking)
- Aggregate JaCoCo across modules (already partially via CI profile) -> Automate delta report
- Add more domain-specific benchmarks (DOM diff, selector matching, scoring heuristics)
- Integrate benchmark threshold check into CI optional workflow
- Auto-generate test gap report (class vs test coverage skeleton)

---
If adding new categories (e.g., Load / Stress), extend both this file and `docs/copilot/README-AI.md` to keep them in sync.
