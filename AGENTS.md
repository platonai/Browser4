# Repository Guidelines

Repo: https://github.com/platonai/Browser4

## Architecture

```
browser4-cli (Rust)  ──MCP over HTTP──▶  browser4-rest (Kotlin/Spring)  ──▶  PulsarWebDriver (Kotlin/CDP)
     ▲                                           │
     │                                           ▼
     └──── e2e tests ────▶  Fixture HTTP server (Rust test harness)
```

- **CLI:** `cli/browser4-cli/` — Rust binary, MCP tool calls over HTTP
- **Backend:** `browser4-rest/` — Spring Boot, `MCPToolController` dispatches tools
- **Browser driver:** `browser4-core/browser4-browser/` — `PulsarWebDriver` wraps CDP
- **Agent tools:** `browser4-agentic/` — `AgentToolManager` maps MCP tool names → driver methods

### Dispatch chain (CLI → browser)

1. CLI builds MCP tool call: `{tool: "browser_type", arguments: {ref: "#el", text: "hi"}}`
2. `MCPToolController.callTool()` → `dispatchToToolExecutor()`
3. `normalizeFrontendToolCall()` applies `FRONTEND_TOOL_NAME_ALIASES` (e.g., `browser_type` → `fill`)
4. `DefaultArgumentNormalizer` maps `ref` → `selector`, strips `sessionId`, converts snake_case
5. `resolveMcpToolCall()` → `ToolCall("tab", "fill", args)`
6. `AgentToolManager.execute()` → `executor.callFunctionOn(toolCall, driver)` → `PulsarWebDriver.fill()`

### Batch commands

`handleCommandBatch()` → `handleBatchTool()` in `MCPToolController`. CLI's `compile_batch_request()` builds step arrays with `op: "tool"`. `preFocusSelector` only added for `keydown`/`keyup` (not fill/type/press).

### Known CDP pitfalls

- **crbug.com/444929150:** `Input.dispatchMouseEvent` type `mouseWheel` race condition in headless Chrome. Fix: dispatch to `{passive: false}` wheel listener.
- **Cursor positioning:** `DOM.focus()` + `Input.dispatchMouseEvent` (click) may leave cursor at 0. Fix: `setSelectionRange(99999, 99999)` after focus+click.
- **`Input.insertText` racing:** 0ms delay between chars drops `input` events. Fix: use same inter-char delay as `type()` via `randomDelayMillis("type")` (90-240ms).

## Project Structure

| Module | Description |
|---|---|
| `browser4-core` | Core engine: sessions, scheduling, DOM, browser control |
| `browser4-dependencies` | BOM and dependency alignment |
| `browser4-tools` | Operational tools and launch helpers |
| `browser4-agentic` | AI agents, MCP, skill registration |
| `browser4-agent-tools` | High-level agent tools: scraping, crawling, stateful page interaction |
| `browser4-rest` | Spring Boot REST layer & command endpoints |
| `cli/browser4-cli` | Rust CLI binary |
| `skills/browser4-cli` | AI agent skill definitions |
| `browser4-apps/browser4-standalone` | Product packaging, unified launcher (`target/Browser4.jar`) |
| `examples/browser4-examples` | Runnable examples |
| `browser4-tests` | E2E, integration, scenario tests |
| `browser4-tests/browser4-tests-common` | Shared test base classes and utilities |
| `cdp-protocol` | Chrome DevTools Protocol JSON definitions |
| `coworker/` | File-queue automation for task-driven AI workflows |

## Build & Test

### Quick commands

```bash
# Build (skip tests)
./mvnw -DskipTests                           # Linux/macOS
.\mvnw.cmd -q -D"skipTests"                  # Windows PowerShell

# Rust unit tests (fast, no backend needed)
cd cli/browser4-cli && cargo test --bin browser4-cli

# Kotlin tests
mvn test -pl browser4-rest -am
mvn test -pl browser4-rest -am -Dtest=MCPToolControllerTest

# E2E tests (needs running backend or mock server)
cargo test --test e2e -- --nocapture
cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_*

# Scoped test runs (Windows)
bin/test.ps1 fast|it|e2e|rest|skills|mcp|cli|browser4|mock-site
```

Maven profile switches in root `pom.xml`: `-DrunITs=true`, `-DrunE2ETests=true`, `-DrunCoreTests=true`, `-DrunRestTests=true`.

### E2E test filtering

```
cargo test --test e2e -- --help           # All options
--scenario <pattern>                      # Glob filter
--group <name>                            # Group filter (repeatable)
--level BASIC|EXTENDED|all                # Test depth
--fail-fast / --failed                    # Stop early / rerun failures
--list / --list-groups                    # Discover without running
--enable-install-scenario                 # Opt into install tests
--enable-batch-scenario                   # Opt into batch tests
--force-rebuild-bundle                    # Force local Maven + runtime rebuild
--force-remote-bundle                     # Download pre-built bundle instead
```

### Test locations

| Scope | Path |
|---|---|
| Unit tests | `src/test/kotlin/...` |
| Integration | `browser4-tests/pulsar-it-tests/` |
| E2E | `browser4-tests/pulsar-e2e-tests/` |
| REST integration/E2E | `browser4-tests/browser4-rest-tests/` |
| Shared utilities | `browser4-tests/browser4-tests-common/` |
| Rust E2E | `cli/browser4-cli/tests/e2e/` |

## Code Style

### Kotlin
- Immutable `data class`; explicit return types; null-safety (`require`/`check`/`?:`)
- Public APIs require KDoc
- Store AI-generated task docs in `docs-dev/copilot/`

### Logging
```kotlin
logger.info("Task {} finished in {} ms", taskId, cost)  // placeholders, never concatenation
```

### Naming
- Test methods: camelCase + `@DisplayName("...")` — **NOT** backtick naming
- Test classes: `<Name>Test.kt` (unit), `<Name>IT.kt` (integration), `<Name>E2ETest.kt` (e2e)

## Testing Guidelines

**Default policy:** Don't run full suites. Compile with tests skipped, run smallest relevant scope. Upgrade scope when risk increases (cross-module, public API/DTO/serialization, Spring wiring, dependency bumps, concurrency/I/O, browser lifecycle).

**Tag-driven scheduling** — use tags from `docs/TESTING.md`:
- Scope: `Unit`, `Integration`, `E2E`, `SDK`
- Speed: `Fast` (<5s), `Slow` (5–30s), `Heavy` (>30s)
- Gates: `Requires*`, `ManualOnly`

**Coverage targets:** Global ≥70%, Core ≥80%, Utilities ≥90%, Controllers ≥85%

**CI:** `.github/workflows/ci.yml` builds all-main-modules, starts Dockerized app on port 8182, runs `cargo test` in `cli/browser4-cli`, limits Maven tests to fast/unit tags by excluding `Slow`, `Heavy`, `Integration`, `E2E`, `SDK`, `Requires*`, `ManualOnly`.

## Configuration

- Default port: **8182**
- Config files: `application.properties` → `application-*.properties` → `application-private.properties` (git-ignored, secrets here or env vars)
- Key properties: `openrouter.api.key`, `browser.profile.mode` (DEFAULT|SYSTEM_DEFAULT|SEQUENTIAL|TEMPORARY), `browser.display.mode` (GUI|HEADLESS|SUPERVISED)
- LLM providers configured via env vars: `DEEPSEEK_API_KEY`, `OPENROUTER_API_KEY`, `VOLCENGINE_API_KEY`, `OPENAI_API_KEY`

## Development Patterns

### Adding a `browser4-cli` command

1. **`commands.rs`** — Add `CommandDef`: CLI name kebab-case, MCP tool `browser_`-prefixed snake_case, map args in `tool_params_fn`
2. **`MCPToolController.kt`** — Add frontend alias so `browser_my_tool` resolves to internal name `my_tool`
3. **Backend tool** — Reuse existing when possible; if new capability needed add `@MCP` method in `WebDriver.kt`, implement in concrete driver. Add explicit `BrowserTabToolExecutor` case only for non-trivial parameter mapping
4. **`main.rs`** — Update only for: custom dispatch, dynamic tool-name selection, stale-session recovery, `no_snapshot_commands()`, or custom batch handling in `compile_batch_request()`
5. **Docs** — Update `skills/browser4-cli/SKILL.md`; extensive docs → `skills/browser4-cli/references/<topic>.md`
6. **Tests** — `commands.rs` unit tests → controller mapping tests → `e2e.rs` → `MCPToolControllerE2ETest.kt`
7. **Common failures:** missing backend alias, omitted `sessionId`, forgetting `no_snapshot_commands()`/`batch_supported`, element-ref parameter name mismatches, snake_case/camelCase normalization

### REST-based commands (swarm, crawl)

- `tool_name_fn` returns `""`; dispatch entirely in `main.rs` via custom `handle_*`
- HTTP in `http.rs`: `submit_*` → POST `/api/<resource>`, `get_*_result` → GET `/api/<resource>/{id}/result`
- Backend: `@RestController` + `@Service` + `ConcurrentHashMap` task store + `CoroutineScope`
- Return task UUID from POST; CLI polls for completion. No MCP alias needed.

### Snapshot-related commands

Category `Category::Snapshot`:
- `htmlsnapshot get` / `query` — HTML retrieval with pagination (`-limit`, `-offset`)
- `htmlsnapshot grep` — regex search (`-i`, `-v`, `-F`, `-w`, `-A/B/C`, `--selector`)
- `htmlsnapshot inspect` — CSS selector discovery for recurring patterns
- `htmlsnapshot summary` — compressed Web Page Summary Index
- `snapshot` — live a11y snapshot with `--boxes`, `--stdout`, `--limit`

### Modifying install/uninstall/upgrade code

After changing `cli/browser4-cli/src/daemon.rs`, run install-scenario e2e tests:
```bash
cargo test --test e2e -- --nocapture --level ALL --enable-install-scenario --scenario '*install*'
```

## PowerShell Cross-Platform Compatibility

- Use `$IsWindows`, `$IsLinux`, `$IsMacOS` for platform branching
- Avoid Windows-only cmdlets; use `Join-Path` / `[System.IO.Path]::Combine()`
- Prefer `$env:HOME` over `$env:USERPROFILE`
- Shebang: `#!/usr/bin/env pwsh`
- **When fixing one `.ps1`, check siblings for the same issue**

## Test Script Portability

Scripts under `browser4-tests/tests-production/` and `bin/test-production.ps1` test globally-installed `browser4-cli`. They must **never** depend on: git, repo root, source code, Maven/Cargo build outputs. Use `$PSScriptRoot` for sibling references. Repo-awareness must be opt-in with clear error messages when absent.

## Coworker Automation

File-queue system for task-driven AI workflows (`coworker/`). Task files (Markdown with optional `Title:`/`Prompt:` headers) route through state directories: `0draft/` → `1ready/` → `2working/` → `3complete/` (or `3aborted/`, `4review/`, `5approved/`). See [Coworker SKILL.md](coworker/SKILL.md).

## Definition of Done

- [ ] Build and related tests pass
- [ ] No new high-noise logs or warnings
- [ ] New/changed logic has tests (main path + edge case)
- [ ] No secrets or private endpoints committed
- [ ] No arbitrary version changes (follow parent BOM)
- [ ] Documentation updated for public behavior changes
- [ ] Performance impact assessed if >5%

## Common Issues

| Issue | Solution |
|---|---|
| `.ps1` scripts don't run on Linux | `sudo apt-get install -y powershell`, then `pwsh script.ps1` |
| `mvnw` no execute permission | `chmod +x mvnw` |
| JDK version mismatch | JDK 17+ in `JAVA_HOME` |
| Windows parameter escaping | `-D"key.with.dots=value"` |
| Port 8182 in use | Override `server.port` in root `application.properties` |
| BrowserProtocol retry log storms | Use existing retry utilities, lower log level |

## Documentation References

- [Testing Taxonomy](docs/TESTING.md)
- [Build from Source](docs/build-from-source.md)
- [Configuration Guide](docs/config.md)
- [CLI Skill Guide](skills/browser4-cli/SKILL.md)
- [ARIA Snapshots](docs/aria-snapshots.md)
- [HTML Snapshot](docs/htmlsnapshot-inspect-summary.md)
- [Eval Command Output](docs/eval-command-output.md)
- [Load Options Guide](docs/load-options-guide.md)
- [Mock Site](docs/mocksite.md)
- [QL Functions Guide](docs/ql-functions-guide.md)
- [Coworker Automation](coworker/SKILL.md)

---

*Last updated: 2026-07-14*
