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

> **Note for Linux/macOS:** Many scripts in this repo are PowerShell (`.ps1`) files (e.g., `bin/test.ps1`, `bin/build.ps1`). To run them on Linux/macOS, install PowerShell if not already installed, then use `pwsh`:
> ```bash
> # Install PowerShell (Ubuntu/Debian)
> sudo apt-get install -y powershell
> # Or via snap
> sudo snap install powershell --classic
>
> # Run a PowerShell script
> pwsh bin/test.ps1 fast
> pwsh bin/build.ps1 -test
> ```

## Project Structure

| Module                                 | Description |
|----------------------------------------|-------------|
| `browser4-core`                        | Core engine: sessions, scheduling, DOM, browser control |
| `browser4-dependencies`                | BOM and dependency alignment |
| `browser4-tools`                       | Operational tools and launch helpers |
| `browser4-agentic`                     | AI agents implementation, MCP, skills registration |
| `browser4-agent-tools`                 | High-level agent tools: scraping, crawling, stateful page interaction |
| `browser4-rest`                        | Spring Boot REST layer & command endpoints |
| `cli/*`                                | Browser4 CLI + skill assets (`cli/browser4-cli`, `cli/skill`) |
| `browser4-apps/*`                      | Product packaging and the unified launcher (`browser4-apps/browser4-standalone`, `target/Browser4.jar`) |
| `examples/*`                           | Runnable examples (`examples/browser4-examples`) |
| `browser4-tests`                       | E2E & heavy integration & scenario tests |
| `browser4-tests/browser4-tests-common` | Shared test base classes and utilities |
| `cdp-protocol`                         | Chrome DevTools Protocol JSON definitions (`browser_protocol.json`, `js_protocol.json`) |
| `coworker/`                            | File-queue automation system for task-driven AI workflows |

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
  concurrency/I/O, browser/BrowserProtocol lifecycle)
- Test scheduling is tag-driven; reuse the dimensions in `docs/TESTING.md` (`Unit`/`Integration`/`E2E`/`SDK`, `Fast`/`Slow`/`Heavy`, `Requires*`, `ManualOnly`) instead of inventing new tags

See [TESTING.md](docs/TESTING.md) for details and trade-offs.

### Test Commands in This Repository
- Use `bin/test.ps1` on Windows for scoped runs: `fast`, `it`, `e2e`, `rest`, `skills`, `mcp`, `cli`, `browser4`, `mock-site` (`mocksite` and `mocksiteboot` remain accepted aliases)
- Maven profile switches in root `pom.xml` are property-driven: `-DrunITs=true`, `-DrunE2ETests=true`, `-DrunCoreTests=true`, `-DrunRestTests=true`
- Use `bin/test.ps1 mock-site -Dmock.site.port=18080` to launch `MockSiteBoot` from `browser4-tests/browser4-rest-tests`; use `MockSiteLauncher` from `browser4-tests/browser4-tests-common` for in-process startup
- `cli/browser4-cli/tests/e2e.rs`: all e2e scenarios must start and depend on Browser4.jar; this includes single-scenario runs via `--scenario`.
- **E2E Test Efficiency:** Run `cargo test --test e2e -- --help` to see all filtering options for running e2e tests efficiently. Key flags:
    - `--scenario <pattern>` — run only matching scenarios (supports `*` and `?` globs)
    - `--group <name>` — run scenarios in a specific group (repeatable)
    - `--level BASIC` — default; use `EXTENDED` or `all` for longer edge-case tests
    - `--fail-fast` — stop after the first failure
    - `--failed` — rerun only scenarios that failed in the previous run
    - `--list` / `--list-groups` — discover available scenarios and groups without running anything
    - `--enable-install-scenario` / `--enable-batch-scenario` — opt into heavier install/batch tests
    - `--force-rebuild-bundle` — force local Maven + runtime bundle rebuild (skip cached artifacts)
    - `--force-remote-bundle` — download a pre-built bundle from remote releases instead of building locally
- `.github/workflows/ci.yml` builds with `all-main-modules`, starts the Dockerized app on port `8182`, runs `cargo test` in `cli/browser4-cli`, and keeps the main Maven test pass limited to fast/unit-style tags by excluding `Slow`, `Heavy`, `Integration`, `E2E`, `SDK`, `Requires*`, and `ManualOnly`.

### Test Location
- Module unit tests: `src/test/kotlin/...`
- Integration-heavy suites: `browser4-tests/pulsar-it-tests/`
- E2E suites: `browser4-tests/pulsar-e2e-tests/`
- REST integration/E2E tests and standalone mock site: `browser4-tests/browser4-rest-tests/`
- Shared utilities and the programmatic mock site launcher: `browser4-tests/browser4-tests-common/`

### Naming Conventions
- Unit tests: `<ClassName>Test.kt`
- Integration tests: `<ClassName>IT.kt`
- E2E tests: `<ClassName>E2ETest.kt`
- **Method names: Use camelCase (NOT backtick naming)**
    - ✅ `testUserLoginWithValidCredentials()` + `@DisplayName("test user login with valid credentials")`
    - ❌ `` `test user login with valid credentials` ``

### Test Performance Targets
- `Fast`: <5s
- `Slow`: 5–30s
- `Heavy`: >30s or high-resource/browser-dependent

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
browser.profile.mode=DEFAULT  # DEFAULT | SYSTEM_DEFAULT | SEQUENTIAL | TEMPORARY

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
| `.ps1` scripts don't run on Linux | Install PowerShell: `sudo apt-get install -y powershell`, then `pwsh script.ps1` |
| `mvnw` no execute permission | `chmod +x mvnw`                                             |
| JDK version mismatch | Ensure JDK 17+ in `JAVA_HOME`                               |
| Windows parameter escaping | Use `-D"key.with.dots=value"`                               |
| Port 8182 in use | Override `server.port` or use root `application.properties` |
| BrowserProtocol retry log storms | Use existing retry utilities, lower log level               |

## Documentation References

- [Configuration Guide](docs/config.md)
- [Testing Taxonomy](docs/TESTING.md)
- [Test Strategy](docs/test-strategy.md)
- [Browser4 CLI Skill Guide](skill/SKILL.md)
- [ARIA Snapshots](docs/aria-snapshots.md)
- [Build from Source](docs/build-from-source.md)
- [CLI Install & Upgrade](docs/cli-install-upgrade.md)
- [CLI Standalone Install](docs/cli-standalone-install.md)
- [DOM Snapshot Inspect & Summary](docs/domsnapshot-inspect-summary.md)
- [Eval Command Output](docs/eval-command-output.md)
- [Load Options Guide](docs/load-options-guide.md)
- [Mock Site](docs/mocksite.md)
- [Metadata Files](docs/metadata-files.md)
- [QL Functions Guide](docs/ql-functions-guide.md)
- [QL H2 UDFs Reference](docs/ql-h2-udfs-reference.md)
- [Coworker Automation](coworker/SKILL.md)

## Claude-Specific Guidance

### Harness Configuration

The `.claude/settings.json` file contains pre-approved permissions for common operations:
- Build commands: `./mvnw`, `cargo`, `npm`, `rust`
- Shell commands: `pwsh`, `powershell`, `bash`
- Git operations: `pull`, `push`, `stash`, `checkout`, `restore`, `add`, `commit`
- Helper tools: `browser4-cli`, `gh`, `java`, `jar`
- Skills: `code-review`

Add new permissions here when you encounter repeated prompts for the same operation. See [Skill: fewer-permission-prompts] for automated scanning.

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

### Coworker Integration

For task-driven batch work, use the `coworker/` file-queue system instead of inline execution:
1. Create a task `.md` file under `coworker/tasks/main/0draft/` with a `Title:` and `Prompt:` header
2. Run `./coworker/scripts/coworker.ps1` or let the scheduler pick it up
3. Results appear in `coworker/tasks/main/3complete/` or `5approved/`
4. For recurring work, configure entries in `coworker/scripts/coworker-scheduler.config.psd1`

### PowerShell (.ps1) Cross-Platform Compatibility

When creating or modifying a `.ps1` file, ensure the script is compatible with multiple platforms (Windows, Linux, macOS). Follow these guidelines:

- Use `$IsWindows`, `$IsLinux`, `$IsMacOS` (PowerShell Core 6+) for platform-specific branching instead of relying on `[Environment]::OSVersion`
- Avoid Windows-only cmdlets (e.g., `Set-Win*`, `Get-WmiObject`, `netstat`-specific flags). Use cross-platform alternatives where available
- Use forward slashes in paths where possible, or construct paths with `Join-Path` / `[System.IO.Path]::Combine()`
- Avoid case-sensitive file-system assumptions (e.g., don't rely on `$env:USERPROFILE` — use `$env:HOME` or `[Environment]::GetFolderPath()`)
- Prefer `#!/usr/bin/env pwsh` shebang for scripts intended to run on Unix
- Test scripts on at least one non-Windows platform before finalizing
- **When fixing a `.ps1` file**, also check other scripts in the same directory for the same issues and fix them proactively

### Test Script Portability (bin/tests-production/ and bin/test-production.ps1)

All `.ps1` and `.sh` scripts under `bin/tests-production/` and `bin/test-production.ps1` are **production test scripts** — they test a globally-installed `browser4-cli` binary, not the source tree.  They are designed to be run from **any location** (CI, a user's machine, a downloaded bundle) and must **never** depend on:

- `git` — no `git rev-parse`, `git show-toplevel`, or any git command
- The repository root — no traversing up the tree looking for `pom.xml` or similar markers
- Source code — no references to `cli/`, `browser4-core/`, `browser4-apps/`, or any source module path
- Maven / Cargo build outputs — no dependency on `target/` directories

**What IS allowed:**
- Sibling references via `$PSScriptRoot` / `$MyInvocation.MyCommand.Path` / `$BASH_SOURCE` — these work when the `bin/` directory structure is preserved
- `$env:BROWSER4_CLI_BIN` to override the CLI binary path
- Globally-installed tools on PATH (`browser4-cli`, `pwsh`, `bash`, optional AI CLIs like `claude` / `copilot`)
- Remote URLs (OSS install scripts, release downloads)
- **Conditional** repo-awareness: scripts may optionally use repo paths *when available*, but must degrade gracefully when they are not (e.g., `-BuildCli` / `-BuildServer` flags in `multi-scenarios.ps1` throw a clear error when run outside a repo checkout)

**When adding new test scripts or modifying existing ones:**
- Resolve sibling scripts and modules relative to the script's own directory, not the repo root
- Use `$ScriptDir` / `$PSScriptRoot` (PowerShell) or `SCRIPT_DIR` (bash) for all internal path resolution
- If a feature genuinely requires the source repo (e.g., building from source), guard it behind an explicit parameter and fail with a helpful message when the repo is absent
- After making changes, verify the script parses correctly with `pwsh -NoProfile -Command "[Parser]::ParseFile('<path>', [ref]$null, [ref]$null)"`

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
4. Update `cli/browser4-cli/src/main.rs` only when the command needs custom dispatch, dynamic tool-name selection, stale-session recovery, inclusion in `no_snapshot_commands()` for read-only behavior, or custom batch handling in `compile_batch_request()`
5. Update `skill/SKILL.md` for user-facing command documentation; CLI help is generated from `CommandDef`, so avoid hand-editing help infrastructure. For commands with extensive documentation, add a dedicated reference file under `skill/references/` (e.g. `crawl.md`, `loop.md`, `domsnapshot.md`)
6. Cover the change with the smallest relevant tests: `cli/browser4-cli/src/commands.rs` unit tests, `browser4-rest` controller mapping tests, `cli/browser4-cli/tests/e2e.rs`, and `browser4-tests/browser4-rest-tests/.../MCPToolControllerE2ETest.kt` when the command changes the end-to-end flow
7. Watch the common failure points: missing backend alias, omitted `sessionId` in custom handlers, forgetting `no_snapshot_commands()` for read-only commands, forgetting `batch_supported`/`compile_batch_request()` for batch-safe DOM commands, mismatched element-ref parameter names, broken `activeSelector` / `lastMousePosition` persistence in `cli/browser4-cli/src/state.rs`, and snake_case/camelCase argument normalization

**REST-based commands** (like `swarm-submit`, `crawl`) follow a different pattern from MCP tools:
- Set `tool_name_fn` to return `""` and handle dispatch entirely in `main.rs` via a custom `handle_*` function.
- Add HTTP functions in `http.rs` following the `submit_*/get_*_result` pattern (e.g. `submit_crawl` → `POST /api/crawl`, `get_crawl_result` → `GET /api/crawl/{id}/result`).
- Create a backend `@RestController` + `@Service` pair (e.g. `CrawlController.kt` + `CrawlService.kt`) with an async task store (`ConcurrentHashMap`) and a `CoroutineScope` for background execution.
- Return a task UUID from the POST endpoint; the CLI polls for completion.
- No MCP alias needed — these commands bypass `MCPToolController` entirely.

**Snapshot-related commands** (`domsnapshot`, `snapshot`) use the `Category::Snapshot` category:
- `domsnapshot get` / `domsnapshot query` — retrieve DOM snapshots with optional pagination (`-limit`, `-offset`)
- `domsnapshot grep` — search HTML with regex support (`-i`, `-v`, `-fixed-strings`, `-word-regexp`, `-files-with-matches`, `-count`, `-no-line-number`)
- `domsnapshot inspect` — inspect a DOM element with enriched metadata
- `domsnapshot summary` — generate a summary view of the DOM
- `snapshot` — capture live page snapshot with `--boxes` for element bounding boxes, `--stdout` for direct output, `--limit`/`--no-compact` for size control
- `snapshot grep` — grep over a live page snapshot

**Scheduled/loop commands:**
- `loop` — periodically execute a subcommand; configure via `--interval` and `--times`
- The loop command is excluded from batch mode and e2e test runs

**System commands:**
- `doctor` — system diagnosis: checks Chrome, Java, network connectivity, and configuration
- `install` / `uninstall` / `upgrade` — CLI lifecycle management (see [CLI Install & Upgrade](docs/cli-install-upgrade.md))

#### Modifying Install / Uninstall / Upgrade Rust Code

**Rule:** Whenever you change Rust code related to `install`, `uninstall`, or `upgrade` (primarily in `cli/browser4-cli/src/daemon.rs`), you **must** run the install-scenario e2e tests before considering the change complete:

```bash
# Run all install-related scenarios:
cargo test --test e2e -- --nocapture --level ALL --enable-install-scenario --scenario '*install*'

# Or run the full install-enabled suite (broader, catches regressions in other scenarios):
cargo test --test e2e -- --nocapture --level ALL --enable-install-scenario
```

These tests exercise the full download / extract / install / uninstall / upgrade lifecycle against a local mock release server and catch regressions that unit tests miss (lock acquisition failures, path handling, disk-space checks, mirror selection, download-cache behavior, and current-tag management).

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
- **BrowserProtocol Security** - Handle Chrome DevTools Protocol errors gracefully

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

### Coworker Automation

The `coworker/` directory contains a file-queue automation system for task-driven AI workflows. Tasks are plain Markdown files routed through a state-machine pipeline.

**Main entrypoints:**
- `./coworker/scripts/coworker.ps1` — Primary worker: picks up a task file, runs Copilot, logs the run, and routes the result
- `./coworker/scripts/process-coworker-queue.ps1` — One-shot queue processor
- `./coworker/scripts/coworker-scheduler.ps1` — Long-running scheduler with periodic checks

**Task lifecycle directories** (under `coworker/tasks/`):
- `main/0draft/` → `main/1ready/` → `main/2working/` → `main/3complete/` (or `3aborted/`, `4review/`, `5approved/`)
- `200issues/` — GitHub issues pipeline with draft + commit + post stages

**Key conventions:**
- Task files use optional `Title:`, `Description:`, `Prompt:` headers; if absent, the full file is the prompt
- The worker moves the file through state directories — never modify the task file from the AI side
- Scheduler config lives in `coworker/scripts/coworker-scheduler.config.psd1`
- Worker helper (Copilot, Claude, etc.) is configured in `coworker/scripts/config.psd1`

See [Coworker SKILL.md](coworker/SKILL.md) for full documentation.

---

*Last updated: 2026-06-30*
