# Repository Guidelines

Repo: https://github.com/platonai/Browser4

<!-- TOC -->
**Table of Contents**
- [Project Overview](#project-overview)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Key APIs and Concepts](#key-apis-and-concepts)
- [Code Style Guidelines](#code-style-guidelines)
- [Testing Guidelines](#testing-guidelines)
- [Configuration](#configuration)
- [Development Principles](#development-principles)
- [Definition of Done](#definition-of-done-pr-checklist)
- [Common Issues & Troubleshooting](#common-issues--troubleshooting)
- [Documentation References](#documentation-references)
- [Claude-Specific Guidance](#claude-specific-guidance)
<!-- /TOC -->

---

## Project Overview

**Browser4** is a lightning-fast, coroutine-safe browser engine for AI agents. It provides:

- **Browser Agents** — Fully autonomous browser agents that reason, plan, and execute end-to-end tasks
- **Browser Automation** — High-performance automation for workflows, navigation, and data extraction
- **Machine Learning Agent** — Learns field structures across complex pages without consuming tokens
- **Extreme Performance** — Fully coroutine-safe; supports 100k ~ 200k complex page visits per machine per day
- **Data Extraction** — Hybrid of LLM, ML, and selectors for clean data across chaotic pages

## Quick Start

### Prerequisites
- Java 17+
- Latest Google Chrome

### Build Commands

**Linux/macOS:**
```bash
chmod +x mvnw
./mvnw -q -DskipTests
```

**Windows (PowerShell):**
```powershell
.\mvnw.cmd -q -D"skipTests"
```

**Windows (cmd):**
```cmd
mvnw.cmd -q -DskipTests
```

### Run Tests

**Core module tests (Linux/macOS):**
```bash
./mvnw -pl browser4-core -am test -Dsurefire.failIfNoSpecifiedTests=false
```

**Core module tests (Windows PowerShell):**
```powershell
.\mvnw.cmd -pl browser4-core -am test -D"surefire.failIfNoSpecifiedTests=false"
```

### Recommended Build Scripts
- Windows: `bin/build.ps1 [-test]`
- Linux/macOS: `bin/build.sh [-test]`

## Project Structure

| Module                                 | Description |
|----------------------------------------|-------------|
| `browser4-core`                        | Core engine: sessions, scheduling, DOM, browser control |
| `browser4-dependencies`                | BOM and dependency alignment |
| `browser4-tools`                       | Operational tools and launch helpers |
| `browser4-agentic`                     | AI agents implementation, MCP, skills registration |
| `browser4-rest`                        | Spring Boot REST layer & command endpoints |
| `cli/*`                                | Browser4 CLI + skill assets (`cli/browser4-cli`, `cli/skill`) |
| `browser4-app/*`                       | Product packaging (`browser4-app/browser4-agents`) |
| `examples/*`                           | Runnable examples (`examples/browser4-examples`) |
| `browser4-tests`                       | E2E & heavy integration & scenario tests |
| `browser4-tests/browser4-tests-common` | Shared test base classes and utilities |

## Key APIs and Concepts

### Sessions
```kotlin
// Create a session
val session = AgenticContexts.createSession()

// Create an agent
val agent = AgenticContexts.getOrCreateAgent()
```

### Core API Classes
- `WebDriver` — Browser control interface with human-like behaviors
- `PulsarSession` → `AgenticSession` — Page loading, parsing, and extraction
- `LoadOptions` — CLI-style URL parameters for page loading
- `BrowserPerceptiveAgent` — AI agent implementation

### Load Options
URL parameters control page loading behavior:
```kotlin
val page = session.load(url, "-expires 1d -refresh -parse")
```

Key options:
- `-expires <duration>` — Page expiration time
- `-refresh` — Force page refresh
- `-parse` — Activate parsing subsystem
- `-outLink <selector>` — Extract links matching selector

## Code Style Guidelines

### Kotlin Conventions
- Prefer immutable `data class`
- Use explicit return types
- Apply null-safety patterns (`require`/`check`/`?:`)
- Public APIs require KDoc documentation
- Store AI generated task docs in `docs-dev/copilot/`

**KDoc Template:**
```kotlin
/**
 * Brief description of what the function does.
 *
 * @param paramName Description of the parameter.
 * @return Description of the return value.
 * @throws ExceptionType When this exception is thrown.
 */
fun functionName(paramName: Type): ReturnType {
    require(paramName.isValid) { "paramName must be valid" }
    // implementation
}
```

### Logging
Use placeholder-style logging (avoid string concatenation):
```kotlin
logger.info("Task {} finished in {} ms", taskId, cost)
```

## Testing Guidelines

### Minimal Test Policy (default)

To keep iteration fast, **don’t run full test suites by default**.

- Default: `./mvnw` compile with tests skipped
- Then: run the **smallest relevant** test scope (module/class) when logic changes
- Upgrade scope when risk increases (cross-module, public API/DTO/serialization, Spring wiring, dependency bumps,
  concurrency/I/O, browser/CDP lifecycle)

See [TESTING.md](docs/TESTING.md) for details and trade-offs.

### Test Commands in This Repository
- Use `bin/test.ps1` on Windows for scoped runs: `fast`, `it`, `e2e`, `rest`, `skills`, `mcp`, `cli`, `browser4`
- Maven profile switches in root `pom.xml` are property-driven: `-DrunITs=true`, `-DrunE2ETests=true`, `-DrunSDKTests=true`, `-DrunCoreTests=true`, `-DrunRestTests=true`
- `cli/browser4-cli/tests/e2e.rs`: all e2e scenarios must start and depend on Browser4.jar; this includes single-scenario runs via `--scenario`.
- `.github/workflows/ci.yml` additionally enforces `MCPToolControllerE2ETest` as a required E2E safety check.

### Test Location
- Module unit tests: `src/test/kotlin/...`
- Centralized integration/E2E: `browser4-tests/`
- Shared utilities: `browser4-tests/browser4-tests-common/`

### Naming Conventions
- Unit tests: `<ClassName>Test.kt`
- Integration tests: `<ClassName>IT.kt`
- E2E tests: `<ClassName>E2ETest.kt`
- **Method names: Use camelCase (NOT backtick naming)**
    - ✅ `testUserLoginWithValidCredentials()` + `@DisplayName("test user login with valid credentials")`
    - ❌ `` `test user login with valid credentials` ``

### Test Performance Targets
- Unit tests: <100ms
- Integration tests: <5s
- E2E tests: <30s

### Coverage Targets
- Global: ≥70%
- Core logic: ≥80%
- Utilities: ≥90%
- Controllers: ≥85%

## Configuration

### Application Port
Default: 8182

### Configuration Files
- `application.properties` — Main configuration
- `application-*.properties` — Profile-specific overrides
- `application-private.properties` — Private overrides (ignored by Git), secrets should be set here or via environment variables

### Key Configuration Properties
```properties
# LLM API Key
openrouter.api.key=your-api-key

# Browser context mode
browser.context.mode=DEFAULT  # DEFAULT | SYSTEM_DEFAULT | SEQUENTIAL | TEMPORARY

# Display mode
browser.display.mode=GUI  # GUI | HEADLESS | SUPERVISED
```

## Development Principles

1. **Minimal Changes** — Make the smallest possible modifications
2. **Preserve Style** — Match existing code patterns
3. **Clear Logging** — Use structured, placeholder-based logging
4. **Test Coverage** — Include tests for new/changed logic
5. **Documentation** — Update docs for public API changes

## Definition of Done (PR Checklist)

- [ ] Build and related tests pass
- [ ] No new high-noise logs or warnings
- [ ] New/changed logic has tests (main path + edge case)
- [ ] No secrets or private endpoints committed
- [ ] No arbitrary version changes (follow parent BOM)
- [ ] Documentation updated for public behavior changes
- [ ] Performance impact assessed if significant (>5%)

## Common Issues & Troubleshooting

| Issue | Solution                                                    |
|-------|-------------------------------------------------------------|
| `mvnw` no execute permission | `chmod +x mvnw`                                             |
| JDK version mismatch | Ensure JDK 17+ in `JAVA_HOME`                               |
| Windows parameter escaping | Use `-D"key.with.dots=value"`                               |
| Port 8182 in use | Override `server.port` or use root `application.properties` |
| CDP retry log storms | Use existing retry utilities, lower log level               |

## Documentation References

- [Configuration Guide](docs/config.md)
- [Testing Taxonomy](docs/TESTING.md)
- [Test Strategy](docs/test-strategy.md)
- [Browser4 CLI Skills Development Guide](docs-dev/copilot/SKILLS-DEVELOPMENT-GUIDE.md)
- [ARIA Snapshots](docs/aria-snapshots.md)

## Claude-Specific Guidance

### Understanding Browser4 Architecture

Browser4 is built around three core concepts:

1. **Sessions** - Main interface to manage page loading, fetching, parsing, extracting, AI chatting, page state, persistence, and more
2. **Agents** - Autonomous browser agents with reasoning capabilities
3. **WebDrivers** - Low-level browser control with human-like behaviors

### Task Planning and Execution

When given a task, Claude should:

1. **Analyze Requirements** - Break down the task into minimal changes
2. **Explore First** - Use grep/glob or explore agent to understand relevant code
3. **Make Minimal Changes** - Preserve existing style and patterns
4. **Test Incrementally** - Run targeted tests after each change
5. **Document Changes** - Update relevant documentation

### Common Task Patterns

#### Adding a New Feature

1. Identify the relevant module (browser4-core, browser4-agentic, browser4-rest)
2. Check existing similar features for patterns
3. Add interface/API in appropriate package
4. Implement with proper error handling and logging
5. Add tests (unit + integration if needed)
6. Update documentation

#### Fixing a Bug

1. Reproduce the issue with a test
2. Use grep to find related code
3. Make minimal fix
4. Verify test passes
5. Check for similar patterns elsewhere

#### Refactoring Code

1. Ensure tests exist for current behavior
2. Make incremental changes
3. Run tests after each step
4. Preserve public API contracts
5. Update KDoc if API changes

#### Adding a `browser4-cli` Command

1. Add a `CommandDef` in `cli/browser4-cli/src/commands.rs`; keep the CLI command name kebab-case, use a `browser_`-prefixed snake_case MCP tool name, and map args/options to JSON in `tool_params_fn`
2. Add the frontend alias in `browser4-rest/.../MCPToolController.kt` so names like `browser_my_tool` resolve to the internal tool name such as `my_tool`
3. Reuse existing backend tools when possible; if a new browser capability is required, add an `@MCP` method in `WebDriver.kt`, implement it in the concrete driver, and only add an explicit `BrowserTabToolExecutor` case when parameter mapping is non-trivial
4. Update `cli/browser4-cli/src/main.rs` only when the command needs custom dispatch, dynamic tool-name selection, stale-session recovery, or inclusion in `no_snapshot_commands()` for read-only behavior
5. Update `cli/skill/SKILL.md` for user-facing command documentation; CLI help is generated from `CommandDef`, so avoid hand-editing help infrastructure
6. Cover the change with the smallest relevant tests: `cli/browser4-cli/src/commands.rs` unit tests, `browser4-rest` controller mapping tests, `cli/browser4-cli/tests/e2e.rs`, and `browser4-tests/browser4-rest-tests/.../MCPToolControllerE2ETest.kt` when the command changes the end-to-end flow
7. Watch the common failure points: missing backend alias, omitted `sessionId` in custom handlers, forgetting `no_snapshot_commands()` for read-only commands, mismatched element-ref parameter names, and snake_case/camelCase argument normalization

### Browser Automation Specifics

**Key Classes to Know:**
- `WebDriver` - Main browser control interface
- `PageHandler` - Page lifecycle management
- `ClickableDOM` - DOM interaction utilities
- `LoadOptions` - Page loading parameters

**Common Patterns:**
```kotlin
val session = AgenticContexts.getOrCreateSession()
val agent = session.companionAgent
val driver = session.getOrCreateBoundDriver()
var page = session.open(url)
var document = session.parse(page)
var fields = session.extract(document, mapOf("title" to "#title"))
var result = agent.act("scroll to the bottom")
result = agent.act("scroll to the top")
result = agent.act("enter 'pulsar' into the search box and submit the form (RESULTS will display in the same page)")
result = agent.act("click search button")
var content = driver.selectFirstTextOrNull("body")
content = driver.selectFirstTextOrNull("body")
var history = agent.run("find the search box, type 'web scraping' and submit the form (RESULTS will display in the same page)")
page = session.capture(driver)
document = session.parse(page)
fields = session.extract(document, mapOf("title" to "#title"))
```

### MCP (Model Context Protocol) Integration

Browser4 integrates with MCP for tool calling:

```kotlin
// Define a tool
class CustomTool : MCPTool {
    override val name = "custom_action"
    override val description = "Performs a custom action"

    override fun execute(params: Map<String, Any>): ToolResult {
        // Implementation
    }
}

// Register the tool
skillRegistry.register(CustomTool())
```

### Performance Considerations

- **Coroutine Safety** - All operations must be coroutine-safe
- **Resource Cleanup** - Always close sessions/drivers in finally blocks
- **Batch Operations** - Use parallel processing for multiple pages
- **Caching** - Respect page expiration settings

### Security Best Practices

- **Input Validation** - Always validate URLs and user inputs
- **API Keys** - Never hardcode, use configuration
- **XSS Prevention** - Sanitize extracted content
- **CDP Security** - Handle Chrome DevTools Protocol errors gracefully

### Debugging with Claude

**For Build Issues:**
```bash
# Check Maven output
./mvnw clean compile -X

# Verify dependencies
./mvnw dependency:tree
```

**For Test Failures:**
```bash
# Run specific test
./mvnw -pl browser4-core test -Dtest=SpecificTest

# With debug output
./mvnw -pl browser4-core test -Dtest=SpecificTest -X
```

**For Runtime Issues:**
- Check logs in `logs/` directory
- Enable trace logging for specific packages
- Use `-diagnose` LoadOption for page loading issues

### Working with Agents

Browser4's agentic capabilities allow autonomous task execution:

```kotlin
val agent = AgenticContexts.getOrCreateAgent()

// Simple task
val result = agent.run("Go to example.com and find the latest news")

// Complex multi-step task
val result = agent.run("""
    1. Navigate to shopping site
    2. Search for 'laptops under $1000'
    3. Filter by rating > 4 stars
    4. Extract top 5 products with specs
    5. Return as JSON
""")
```

**Agent Best Practices:**
- Provide clear, step-by-step instructions
- Use structured output formats (JSON, tables)
- Handle errors gracefully
- Set appropriate timeouts

### Code Review Checklist

Before submitting changes, verify:

- [ ] Code follows Kotlin conventions (immutable, explicit types)
- [ ] Public APIs have KDoc documentation
- [ ] Logging uses placeholders, not concatenation
- [ ] Tests cover main path and at least one edge case
- [ ] No hardcoded values (use configuration)
- [ ] Changes are minimal and focused
- [ ] Existing tests still pass
- [ ] No new warnings or deprecations

### Getting Help

- Check `docs/` for detailed guides
- Review `examples/` for usage patterns
- Look in `browser4-tests/` for test examples
- See `docs-dev/copilot/` for development notes

---

*Last updated: 2026-03-14*
