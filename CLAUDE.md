# Browser4 — Project Context for Claude

## Architecture

```
browser4-cli (Rust)  ──MCP over HTTP──▶  browser4-rest (Kotlin/Spring)  ──▶  PulsarWebDriver (Kotlin/CDP)
     ▲                                           │
     │                                           ▼
     └──── e2e tests ────▶  Fixture HTTP server (Rust test harness)
```

- **CLI:** `cli/browser4-cli/` — Rust binary, talks to backend via MCP tool calls
- **Backend:** `browser4-rest/` — Spring Boot, `MCPToolController` dispatches tools
- **Browser driver:** `browser4-core/browser4-browser/` — `PulsarWebDriver` wraps CDP (Chrome DevTools Protocol)
- **Agent tools:** `browser4-agentic/` — `AgentToolManager` maps MCP tool names → PulsarWebDriver methods

## Key dispatch chain (CLI → browser)

1. CLI builds MCP tool call: `{tool: "browser_type", arguments: {ref: "#el", text: "hi"}}`
2. Backend `MCPToolController.callTool()` → `dispatchToToolExecutor()`
3. `normalizeFrontendToolCall()` applies `FRONTEND_TOOL_NAME_ALIASES` (e.g., `browser_type` → `fill`)
4. `DefaultArgumentNormalizer` maps `ref` → `selector`, strips `sessionId`, converts snake_case keys
5. `resolveMcpToolCall()` → `ToolCall("tab", "fill", args)`
6. `AgentToolManager.execute()` → `executor.callFunctionOn(toolCall, driver)` → `PulsarWebDriver.fill()`

## Batch commands

Batch commands go through `handleCommandBatch()` → `handleBatchTool()` in `MCPToolController`.
The CLI's `compile_batch_request()` builds step arrays with `op: "tool"` entries.
`preFocusSelector` is only added for `keydown`/`keyup` steps (not fill/type/press).

## E2E test structure

- Tests live in `cli/browser4-cli/tests/e2e/`
- `scenarios/mod.rs` — registry of all scenarios with `requires_browser4`, `level`, `group`
- `scenarios/browser.rs` — tests against the real Browser4 backend
- `scenarios/batch.rs` — batch-command tests against the real backend
- `scenarios/mock_server.rs` — tests against `MockBrowser4Server` (no real backend needed)
- Real-backend tests use HTML fixtures from `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/`
- State verification: `wait_for_state_or_abort()` polls `read_interactive_state()` which evals `state-log.textContent`

## Known CDP pitfalls

- **crbug.com/444929150:** `Input.dispatchMouseEvent` type `mouseWheel` has a race condition in headless Chrome. Fixed by dispatching to a `{passive: false}` wheel listener.
- **Cursor positioning:** `DOM.focus()` + `Input.dispatchMouseEvent` (click) may leave the cursor at position 0. Fix: `setSelectionRange(99999, 99999)` after focus+click.
- **`Input.insertText` racing:** Calling `insertText` with 0ms delay between characters can drop `input` events. Fix: use same inter-character delay as `type()` via `randomDelayMillis("type")` (90-240ms). Hardcoded 10ms was insufficient in Docker headless Chrome.

## Running tests

```bash
# Rust unit tests (fast, no backend)
cd cli/browser4-cli && cargo test --bin browser4-cli

# E2E tests (needs running Browser4 backend or mock server)
cargo test --test e2e -- --nocapture
cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_*

# Kotlin tests
mvn test -pl browser4-rest -am
mvn test -pl browser4-rest -am -Dtest=MCPToolControllerTest
```

## Key files changed in the 2026-07-14 fix round

| File | Change |
|---|---|
| `PulsarWebDriver.kt` | mouseWheel CDP-primary, press/type/fill cursor-to-end, fill uses same inter-char delay as type |
| `MCPToolController.kt` | `buildBatchFocusExpression()` extracted, selector interpolation fixed |
| `commands.rs` | 12 new unit tests (fill, mousewheel, type) |
| `main.rs` | 6 new batch-compilation tests |
| `mock_server.rs` | status test: `ctx.set_env()` canonical path |
| `ArgumentNormalizersTest.kt` | 7 new tests |
| `MCPToolControllerTest.kt` | 7 new focus-expression tests |

## Development conventions

- **Default:** Always use a git worktree (EnterWorktree) on a new branch — unless the task only touches non-compiled assets.
- **Exception:** For docs (`.md`), PowerShell scripts (`.ps1`), Python scripts (`.py`), Node scripts, config files, and other non-compiled assets that don't involve Rust (`cli/browser4-cli/` etc.) or the Kotlin/Spring backend (`browser4-rest/`, `browser4-core/`, `pom.xml`), work directly in the current checkout without a branch or worktree. Commit directly to the current branch.
