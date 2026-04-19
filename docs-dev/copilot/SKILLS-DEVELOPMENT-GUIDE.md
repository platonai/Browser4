# Browser4 CLI SKILLS Development Guide

> **Audience**: AI agent tools (Claude Code, GitHub Copilot, OpenCode, etc.)
> that need to add new CLI commands (skills) to `browser4-cli`.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [End-to-End Flow: `goto` Case Study](#2-end-to-end-flow-goto-case-study)
3. [Step-by-Step: Adding a New CLI Command](#3-step-by-step-adding-a-new-cli-command)
4. [Testing Checklist](#4-testing-checklist)
5. [Command Categories & Patterns](#5-command-categories--patterns)
6. [File Reference Map](#6-file-reference-map)
7. [Common Pitfalls](#7-common-pitfalls)

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│  CLI (Rust)                                                         │
│  sdks/browser4-cli/src/                                             │
│                                                                     │
│  commands.rs  ──►  main.rs  ──►  http.rs  ──►  POST /mcp/call-tool │
│  (define)        (dispatch)     (HTTP call)                         │
└────────────────────────────────────┬────────────────────────────────┘
                                     │ HTTP
┌────────────────────────────────────▼────────────────────────────────┐
│  Backend (Kotlin)                                                   │
│                                                                     │
│  MCPToolController.kt  ──►  BrowserTabToolExecutor.kt  ──►         │
│  (route & normalize)        (execute tool function)                 │
│                                                                     │
│  WebDriver.kt (interface, @MCP methods)                             │
│  PulsarWebDriver.kt (CDP implementation)                            │
└─────────────────────────────────────────────────────────────────────┘
```

### Key Concepts

| Concept | Description |
|---------|-------------|
| **CommandDef** | Rust struct that defines a CLI command — name, args, options, and mapping to an MCP tool |
| **MCP Tool Name** | The wire protocol tool name sent via HTTP (e.g., `browser_navigate`) |
| **Internal Tool Name** | Backend tool name after alias resolution (e.g., `navigate`) |
| **ToolCall** | Kotlin data class with `(domain, method, arguments)` — e.g., `ToolCall("tab", "navigate", args)` |
| **Tool Domain** | Grouping: `tab` (WebDriver methods), `browser` (tab management), `agent`, `system` |
| **@MCP** | Kotlin annotation on WebDriver methods that are auto-registered as tool specs |

### Name Resolution Chain

```
CLI command    →  MCP tool name        →  Internal name  →  ToolCall(domain, method)
───────────────────────────────────────────────────────────────────────────────────
goto           →  browser_navigate     →  navigate       →  ToolCall("tab", "navigate")
snapshot       →  browser_snapshot     →  aria_snapshot   →  ToolCall("tab", "ariaSnapshot")
click e15      →  browser_click        →  click           →  ToolCall("tab", "click")
go-back        →  browser_navigate_back→  go_back         →  ToolCall("tab", "goBack")
tab-list       →  browser_tabs(list)   →  tab_list        →  ToolCall("browser", "listTabs")
```

---

## 2. End-to-End Flow: `goto` Case Study

### 2.1 User Input

```bash
browser4-cli goto https://browser4.io/
```

### 2.2 CLI Argument Parsing

**File**: `sdks/browser4-cli/src/args.rs`

1. `parse_global_flags()` extracts `-s=<session>`, `--server=<url>`, remaining args
2. `parse_raw_args()` splits remaining args into positional + named options
3. `build_command_args()` maps positional args to named fields using `ArgDef` list

Result: `parsed = { "url": "https://browser4.io/" }`

### 2.3 Command Definition Lookup

**File**: `sdks/browser4-cli/src/commands.rs` (lines 151–163)

```rust
CommandDef {
    name: "goto",
    description: "Navigate to a URL",
    category: Category::Core,
    hidden: false,
    args: &[ArgDef {
        name: "url",
        description: "The URL to navigate to",
        optional: false,
    }],
    options: &[],
    tool_name_fn: |_| "browser_navigate".to_string(),
    tool_params_fn: |args| {
        let url = get_str(args, "url").unwrap_or_default();
        json!({ "url": url })
    },
},
```

- `tool_name_fn` → `"browser_navigate"` (MCP tool name)
- `tool_params_fn` → `{ "url": "https://browser4.io/" }`

### 2.4 Command Dispatch

**File**: `sdks/browser4-cli/src/main.rs` (lines 949–962)

The `goto` command falls into the `_ =>` default branch in `run()`:

```rust
_ => {
    handle_tool_command(
        &client,
        &base_url,
        &tool_name,         // "browser_navigate"
        &tool_params,       // { "url": "https://browser4.io/" }
        command == "goto",  // recover_stale = true
        global.session_name.as_deref(),
    )
    .await?;
}
```

### 2.5 Session Management & HTTP Call

**File**: `sdks/browser4-cli/src/main.rs` (lines 489–513)

`handle_tool_command()` calls `with_session()` which:
1. Reads the persisted session ID from `~/.browser4/cli-state.json`
2. Injects `sessionId` into the tool params
3. Calls `call_tool()` from `http.rs`
4. If the session is stale and `recover_stale=true`, auto-creates a new session and retries

### 2.6 HTTP Request

**File**: `sdks/browser4-cli/src/http.rs` (lines 35–80)

```
POST http://localhost:8182/mcp/call-tool
Content-Type: application/json

{
  "tool": "browser_navigate",
  "arguments": {
    "url": "https://browser4.io/",
    "sessionId": "<uuid>"
  }
}
```

### 2.7 Backend: MCPToolController

**File**: `browser4-rest/.../MCPToolController.kt`

1. **callTool()**: Routes to `dispatchToAgentToolExecutor()` for non-session tools
2. **normalizeFrontendToolCall()**: `"browser_navigate"` → `"navigate"` via `FRONTEND_TOOL_NAME_ALIASES`
3. **normalizeToolArguments()**: Removes `sessionId`, converts snake_case → camelCase
4. **resolveMcpToolCall()**: Matches `"navigate"` against tool specs → `ToolCall("tab", "navigate", args)`
5. **Execute**: `agent.toolExtractor.execute(toolCall)`

### 2.8 Backend: BrowserTabToolExecutor

**File**: `browser4-agentic/.../BrowserTabToolExecutor.kt` (line ~45)

```kotlin
"navigate" -> {
    when {
        args.containsKey("url") -> {
            validateArgs(args, allowed("url"), setOf("url"), functionName)
            driver.navigate(paramString(args, "url", functionName)!!)
        }
        // ... NavigateEntry overload
    }
}
```

### 2.9 Backend: WebDriver → CDP

**File**: `browser4-core/.../WebDriver.kt` (line ~268)

```kotlin
@MCP
suspend fun navigate(url: String)
```

**File**: `browser4-core/.../PulsarWebDriver.kt` (line ~112)

Actual Chrome navigation via Chrome DevTools Protocol (CDP).

### 2.10 Post-Command Snapshot

After `goto` completes, the CLI automatically:
1. Fetches `page_url`, `page_title`, and `browser_snapshot`
2. Saves the snapshot to `.browser4-cli/snapshot/page-<timestamp>.yml`
3. Prints a summary:
   ```
   ### Page
   - Page URL: https://browser4.io/
   - Page Title: Browser4
   ### Snapshot
   [Snapshot](.browser4-cli/snapshot/page-2026-04-04T14-07-00-000Z.yml)
   ```

---

## 3. Step-by-Step: Adding a New CLI Command

### Step 1: Define the Command (Rust)

**File**: `sdks/browser4-cli/src/commands.rs`

Add a new `CommandDef` to the `all_commands()` vec. Insert it in the appropriate category group.

```rust
CommandDef {
    name: "my-command",                         // CLI name (kebab-case)
    description: "Short description of what it does",
    category: Category::Core,                   // Pick appropriate category
    hidden: false,                              // true = hidden from help
    args: &[
        ArgDef {
            name: "arg1",                       // Positional arg name
            description: "Description",
            optional: false,                    // Required or optional
        },
    ],
    options: &[
        OptionDef {
            name: "my-option",                  // --my-option=value
            description: "Description",
            is_bool: false,                     // true for flags, false for key=value
        },
    ],
    tool_name_fn: |_| "browser_my_tool".to_string(),  // MCP tool name
    tool_params_fn: |args| {
        json!({
            "arg1": get_str(args, "arg1").unwrap_or_default(),
            // options accessed the same way
        })
    },
},
```

#### Naming Conventions

| What | Convention | Example |
|------|-----------|---------|
| CLI command name | kebab-case | `my-command` |
| MCP tool name | snake_case, prefixed with `browser_` | `browser_my_tool` |
| Internal tool name | snake_case, no prefix | `my_tool` |
| Backend method name | camelCase | `myTool` |
| Tool domain | lowercase | `tab`, `browser`, `system` |

#### Dynamic Tool Name

Use `tool_name_fn` with args when the MCP tool depends on parameters:

```rust
tool_name_fn: |args| {
    if get_bool(args, "clear").unwrap_or(false) {
        "browser_console_clear".to_string()
    } else {
        "browser_console_messages".to_string()
    }
},
```

### Step 2: Add Backend Tool Name Alias (Kotlin)

**File**: `browser4-rest/.../MCPToolController.kt`

Add the mapping in `FRONTEND_TOOL_NAME_ALIASES`:

```kotlin
private val FRONTEND_TOOL_NAME_ALIASES: Map<String, String> = mapOf(
    // ... existing entries ...
    "browser_my_tool" to "my_tool",
)
```

> **Note**: This step is required for all commands that use a `browser_` prefixed
> MCP tool name. The generic `resolveMcpToolCall()` mapper only matches the
> internal name (e.g., `"my_tool"`) — it does not strip the `browser_` prefix
> automatically. Always add the alias to ensure proper routing.

### Step 3: Implement the Backend Tool Function (Kotlin)

There are three scenarios:

#### Scenario A: The WebDriver method already exists

If WebDriver already has a `@MCP`-annotated method (e.g., `navigate(url: String)`),
you only need to add the CLI command definition and alias mapping.
The `BrowserTabToolExecutor` auto-discovers methods via `ToolSpecGenerator`.

#### Scenario B: Need a new WebDriver method

1. **Add the method to WebDriver interface**:

   **File**: `browser4-core/pulsar-skeleton/.../WebDriver.kt`

   ```kotlin
   /**
    * Description of what this method does.
    *
    * @param arg1 Description.
    * @return Description.
    */
   @Throws(WebDriverException::class)
   @MCP
   suspend fun myTool(arg1: String): String
   ```

2. **Implement in concrete driver classes**:

   **File**: `browser4-core/pulsar-plugins/.../PulsarWebDriver.kt`

   ```kotlin
   override suspend fun myTool(arg1: String): String {
       // Implementation using CDP or other mechanisms
   }
   ```

3. **Add handling in BrowserTabToolExecutor** (if non-trivial parameter mapping):

   **File**: `browser4-agentic/.../BrowserTabToolExecutor.kt`

   Add a case in `callFunctionOn()`:

   ```kotlin
   "myTool" -> {
       validateArgs(args, allowed("arg1"), setOf("arg1"), functionName)
       driver.myTool(paramString(args, "arg1", functionName)!!)
   }
   ```

   > **Note**: Simple methods with direct parameter mapping are handled
   > automatically by the generic dispatch. Only add explicit cases for
   > complex parameter transformations.

#### Scenario C: Need a non-WebDriver tool (different domain)

For tools in the `browser`, `system`, or `agent` domain, add handling in
the appropriate executor class (e.g., `BrowserToolExecutor`, `SystemToolExecutor`)
and register the tool spec.

### Step 4: Handle Special Dispatch (if needed, Rust)

If your command needs custom handling beyond `handle_tool_command()`, add a
dedicated handler function and a case in the `match command` block of `run()`:

**File**: `sdks/browser4-cli/src/main.rs`

```rust
// In run():
match command {
    // ... existing handlers ...
    "my-command" => {
        handle_my_command(&client, &base_url, &tool_params, global.session_name.as_deref()).await?;
    }
    _ => {
        handle_tool_command(/* ... */).await?;
    }
}
```

Most commands do **NOT** need this — they use the default `handle_tool_command()` path.

Custom handlers are only needed for:
- Commands that call multiple tools sequentially
- Commands with special output formatting
- Commands that manage sessions (open, close)
- Commands with async polling (agent-run, co-create)

### Step 5: Update SKILL.md

**File**: `sdks/skill/SKILL.md`

Add the new command to the appropriate section with usage examples.

### Step 6: Control Post-Command Snapshot

By default, every command triggers a post-command snapshot. If your command
should NOT trigger a snapshot (e.g., read-only or session-management commands),
add it to `no_snapshot_commands()`:

**File**: `sdks/browser4-cli/src/main.rs` (line ~43)

```rust
fn no_snapshot_commands() -> HashSet<&'static str> {
    [
        "open", "close", "close-all", "kill-all", "list", "help", "snapshot", "screenshot", "pdf",
        "agent-run", "agent-status", "agent-result",
        "my-command",  // ← add here if no snapshot needed
    ]
    .into()
}
```

---

## 4. Testing Checklist

### 4.1 CLI Unit Tests (Rust)

**File**: `sdks/browser4-cli/src/commands.rs` (test module at end of file)

Add unit tests for tool name and parameter mapping:

```rust
#[test]
fn test_my_command_tool_name_and_params() {
    let map = commands_map();
    let cmd = map.get("my-command").unwrap();
    let mut args = HashMap::new();
    args.insert("arg1".to_string(), json!("value1"));
    assert_eq!((cmd.tool_name_fn)(&args), "browser_my_tool");
    let params = (cmd.tool_params_fn)(&args);
    assert_eq!(params["arg1"], "value1");
}
```

**Run**: `cd sdks/browser4-cli && cargo test --quiet`

### 4.2 Backend Unit Tests (Kotlin)

**File**: `browser4-rest/src/test/kotlin/.../MCPToolControllerTest.kt`

Add a test verifying the full name mapping chain:

```kotlin
@Test
fun `test frontend my-tool maps to my_tool`() = runBlocking {
    mockTool("tab", "myTool")

    val request = MCPToolCallRequest(
        tool = "browser_my_tool",
        arguments = mapOf("sessionId" to sessionId, "arg1" to "value1")
    )

    val result = controller.callTool(request, response)

    assertEquals(HttpStatus.OK, result.statusCode)

    val captor = ArgumentCaptor.forClass(ToolCall::class.java)
    Mockito.verify(agentToolExecutor).execute(capture(captor))
    val toolCall = captor.value

    assertEquals("tab", toolCall.domain)
    assertEquals("myTool", toolCall.method)
    assertTrue(!toolCall.arguments.containsKey("sessionId"))
    assertEquals("value1", toolCall.arguments["arg1"])
}
```

**Run**: `./mvnw -pl browser4-rest -am test -Dtest=MCPToolControllerTest -Dsurefire.failIfNoSpecifiedTests=false`

### 4.3 CLI E2E Tests (Rust)

**File**: `sdks/browser4-cli/tests/e2e.rs`

Add an E2E test scenario that exercises the full round-trip:

```rust
fn test_my_command(ctx: &mut E2ECtx) {
    run_command(ctx, &["my-command", "value1"]);
    // Verify the result using eval or snapshot
    assert_eq!(
        eval_text(ctx, "some.javascript.expression"),
        "expected-value",
        "my-command should have expected effect"
    );
}
```

Then call `test_my_command(&mut ctx);` in the main test runner function.

**Run**: `BROWSER4_CLI_E2E=true cargo test --test e2e -- --nocapture`

### 4.4 Backend E2E Tests (Kotlin)

**File**: `browser4-tests/browser4-rest-tests/src/test/.../MCPToolControllerE2ETest.kt`

Add the command-to-tool mapping and an E2E test:

```kotlin
// In cliCommandToMcpTool map:
"my-command" to "browser_my_tool",

// Test method:
@Test
@DisplayName("myTool does something (cli: my-command)")
fun testMyTool() {
    val sid = openSession()
    val response = callTool("my_tool", mapOf("sessionId" to sid, "arg1" to "value1"))
    assertNotError(response)
}
```

**Run**: `./mvnw -P browser4-tests -pl browser4-tests/browser4-rest-tests -am test -Dtest=MCPToolControllerE2ETest -Dsurefire.failIfNoSpecifiedTests=false`

### 4.5 Testing Summary

| Layer | File | What to Test | Run Command |
|-------|------|-------------|-------------|
| CLI unit | `commands.rs` | tool name, params mapping | `cargo test --quiet` |
| CLI E2E | `tests/e2e.rs` | Full round-trip | `BROWSER4_CLI_E2E=true cargo test --test e2e` |
| Backend unit | `MCPToolControllerTest.kt` | Name alias resolution, dispatch | `./mvnw -pl browser4-rest test` |
| Backend E2E | `MCPToolControllerE2ETest.kt` | Real browser execution | `./mvnw -P browser4-tests -pl browser4-tests/browser4-rest-tests test` |

---

## 5. Command Categories & Patterns

### Simple Tool Command (most common)

Commands that map 1:1 to a single MCP tool. Uses the default `handle_tool_command()` path.

**Examples**: `goto`, `click`, `press`, `hover`, `fill`, `eval`, `go-back`, `reload`

```rust
CommandDef {
    name: "click",
    tool_name_fn: |_| "browser_click".to_string(),
    tool_params_fn: |args| json!({ "ref": get_str(args, "ref").unwrap_or_default() }),
    // ...
}
```

### Composite Command

Commands that need multiple tool calls or special logic. Requires a custom handler in `main.rs`.

**Examples**: `open` (creates session then navigates), `close-all`, `agent-run` (submits + polls), `co-create` (session + submit)

### Dynamic Tool Name Command

Commands where the MCP tool depends on the arguments.

**Examples**: `open` (with/without URL), `console` (messages vs clear), `tab-*` (browser_tabs with action)

### Read-Only / No-Snapshot Command

Commands that don't modify browser state (add to `no_snapshot_commands()`).

**Examples**: `list`, `snapshot`, `screenshot`, `agent-status`, `help`

---

## 6. File Reference Map

### Frontend (Rust) — `sdks/browser4-cli/src/`

| File | Purpose | What to Modify |
|------|---------|----------------|
| `commands.rs` | All CLI command definitions | **Always**: Add `CommandDef` |
| `main.rs` | Entry point, command dispatch, handlers | **Sometimes**: Add custom handler |
| `http.rs` | HTTP client for `/mcp/call-tool` | **Rarely**: Only if new endpoints needed |
| `args.rs` | Argument parsing | **Never**: Shared infrastructure |
| `daemon.rs` | Server auto-start | **Never**: Shared infrastructure |
| `help.rs` | Help text generation | **Never**: Auto-generated from `CommandDef` |
| `state.rs` | Session state persistence | **Never**: Shared infrastructure |
| `snapshot.rs` | Snapshot file saving | **Never**: Shared infrastructure |

### Backend (Kotlin)

| File | Purpose | What to Modify |
|------|---------|----------------|
| `MCPToolController.kt` | REST endpoint, tool routing | **Usually**: Add alias in `FRONTEND_TOOL_NAME_ALIASES` |
| `BrowserTabToolExecutor.kt` | Tab-domain tool execution | **Sometimes**: Add method case in `callFunctionOn()` |
| `WebDriver.kt` | Browser control interface | **Sometimes**: Add `@MCP` method |
| `PulsarWebDriver.kt` | CDP implementation | **Sometimes**: Implement new methods |
| `ToolSpecGenerator.kt` | Auto tool spec generation | **Never**: Shared infrastructure |

### Tests

| File | Purpose |
|------|---------|
| `sdks/browser4-cli/src/commands.rs` (test mod) | CLI unit tests for command mapping |
| `sdks/browser4-cli/tests/e2e.rs` | CLI end-to-end tests |
| `browser4-rest/src/test/.../MCPToolControllerTest.kt` | Backend unit tests |
| `browser4-tests/browser4-rest-tests/src/test/.../MCPToolControllerE2ETest.kt` | Backend E2E tests |

### Documentation

| File | Purpose |
|------|---------|
| `sdks/skill/SKILL.md` | User-facing skill documentation (for AI agents) |
| `docs-dev/copilot/SKILLS-DEVELOPMENT-GUIDE.md` | This guide |

---

## 7. Common Pitfalls

### 1. Forgetting the Backend Alias

**Symptom**: CLI sends `browser_my_tool`, backend returns "Unknown tool".

**Fix**: Add `"browser_my_tool" to "my_tool"` in `FRONTEND_TOOL_NAME_ALIASES`.

### 2. MCP Name Mismatch

**Symptom**: Tool not found in `resolveMcpToolCall()`.

**Fix**: Verify that `toMcpToolName(domain, method)` produces the same name as your alias.
The conversion is: `camelCase` → `snake_case` for `tab`/`system` domains,
`domain_snake_case` for other domains.

- `"tab"` + `"navigate"` → `"navigate"` ✓
- `"tab"` + `"ariaSnapshot"` → `"aria_snapshot"` ✓
- `"browser"` + `"listTabs"` → `"browser_list_tabs"` ✓

### 3. Missing `sessionId` in Backend

**Symptom**: "Session not found" error.

**Fix**: The CLI auto-injects `sessionId` in `handle_tool_command()`. If using a custom handler, ensure you include it: `params["sessionId"] = json!(session_id)`.

### 4. Snapshot After Non-Modifying Command

**Symptom**: Unnecessary snapshot after a read-only command.

**Fix**: Add command name to `no_snapshot_commands()` in `main.rs`.

### 5. Element Ref Resolution

**Symptom**: Backend doesn't understand `e15` notation.

**Fix**: The CLI auto-resolves `e<N>` → `backend:<N>` via `normalize_refs()` in `http.rs`.
This happens for keys: `selector`, `ref`, `startRef`, `endRef`.
If your command uses a different key name for element refs, add it to the `ref_keys` array.

### 6. Stale Session Recovery

By default, `handle_tool_command()` only recovers stale sessions for the `goto` command
(`command == "goto"` → `recover_stale = true`). If your command should also recover
stale sessions, modify the condition in the `_ =>` dispatch branch of `run()`.

### 7. Argument Normalization

The backend's `normalizeToolArguments()` converts snake_case args to camelCase.
Ensure your CLI tool params use the expected naming convention:
- CLI sends: `{ "my_param": "value" }`
- Backend receives: `{ "myParam": "value" }`

If this doesn't apply, use camelCase in the CLI `tool_params_fn` directly.

---

## Appendix: Complete `goto` Code Trace

For reference, here is every file and function involved in `browser4-cli goto https://browser4.io/`:

> **Note**: Line numbers are approximate and may shift as the code evolves.
> Use method/function names for reliable lookup.

```
1. sdks/browser4-cli/src/main.rs           → main() entry, parse args
2. sdks/browser4-cli/src/args.rs            → parse_global_flags, parse_raw_args, build_command_args
3. sdks/browser4-cli/src/main.rs            → run() — lookup command, dispatch
4. sdks/browser4-cli/src/commands.rs        → goto CommandDef (in all_commands())
5. sdks/browser4-cli/src/daemon.rs          → ensure_server_running
6. sdks/browser4-cli/src/main.rs            → default dispatch → handle_tool_command
7. sdks/browser4-cli/src/main.rs            → handle_tool_command → with_session
8. sdks/browser4-cli/src/main.rs            → with_session: session recovery logic
9. sdks/browser4-cli/src/http.rs            → call_tool: POST /mcp/call-tool
10. MCPToolController.kt                     → callTool: route request
11. MCPToolController.kt                     → dispatchToAgentToolExecutor
12. MCPToolController.kt                     → normalizeFrontendToolCall: "browser_navigate" → "navigate"
13. MCPToolController.kt                     → resolveMcpToolCall: → ToolCall("tab","navigate")
14. BrowserTabToolExecutor.kt                → callFunctionOn: "navigate" → driver.navigate(url)
15. WebDriver.kt                             → navigate(url: String) interface
16. PulsarWebDriver.kt                       → navigate() CDP implementation
17. sdks/browser4-cli/src/main.rs            → post_command_snapshot (page_url, page_title, snapshot)
```
