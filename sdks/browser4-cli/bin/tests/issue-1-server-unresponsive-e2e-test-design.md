# E2E Test Design: Issue 1 — Server Becomes Unresponsive After Initial Session

## Issue Summary

After `open` succeeds and the browser session is active, subsequent commands (e.g., `click`, `fill`, `press Enter`) fail with:
```
Error: HTTP request failed: error sending request for url (http://localhost:8182/mcp/call-tool)
```

The server was reachable moments before (during `open`) but becomes unreachable afterward. The only recovery path is `kill-all` + restart, which destroys all sessions irreversibly.

---

## Root Cause Analysis (from code inspection)

### Possible root causes

| # | Cause | Location | Likelihood |
|---|-------|----------|------------|
| R1 | Server process crashes silently between `open` and the next tool call | daemon.rs — server process is `drop()`ed after startup, no monitoring | Medium |
| R2 | `post_command_snapshot` fires three parallel `call_tool` requests immediately after a navigation-triggering command (e.g., `press Enter`), and the server hasn't finished handling the navigation | main.rs:327-365 — `tokio::join!` with no delay/retry | **High** |
| R3 | Server returns empty snapshot content (page not loaded yet), but `post_command_snapshot` only checks `Ok`/`Err`, not content validity — subsequent reads of the 0-byte snapshot file fail silently | main.rs:349-352 — match only checks `(Ok, Ok, Ok)` | **High** |
| R4 | `reqwest` client connection pool keeps a dead connection (HTTP keep-alive to a server that restarted its event loop) | http.rs:12-17 — `make_client()` uses default connection pool | Low |
| R5 | Server-side session corruption from rapid sequential calls | Server internals | Low |
| R6 | Port conflict — another process steals port 8182 between calls | daemon.rs — `is_local_port_open` only checks at startup | Low |

### Primary focus: R2 + R3 (the `post_command_snapshot` race condition)

The critical code path:

```
press Enter (search form submission)
  └→ browser_press_key returns Ok immediately (key pressed, but navigation not awaited)
       └→ post_command_snapshot fires immediately
            ├─ page_url       → Ok(new URL)      ← URL bar updates fast
            ├─ page_title     → Ok("")           ← DOM not parsed yet, but Ok!
            └─ browser_snapshot→ Ok("")          ← DOM not rendered yet, but Ok!
            match (Ok, Ok, Ok) → writes empty file to disk
```

The snapshot file is empty (0 bytes), but the CLI prints a valid-looking snapshot path. When the user tries to read it, they get no element refs, and the next command fails because they're using stale refs.

But the user's original error was `HTTP request failed: error sending request for url` — this is a **transport-level** error, not an empty response. This means one of the `call_tool` requests in `post_command_snapshot` actually returned `Err`, causing the function to silently `return` at the match guard. However, the command itself (e.g., `press Enter`) was already executed. The error message might be misleading — it could be that the server is slow to respond and the 30-second client timeout is hit.

---

## Test Strategy

Two categories:

1. **Mock server tests** — Fast, deterministic, no real browser. Test the CLI's resilience to server errors. These catch the CLI-side bugs.
2. **Real server tests** — Test actual Browser4.jar stability with a real browser. These catch server-side bugs.

---

## Mock Server Tests

### Mock server extension needed

The existing `MockBrowser4Server` needs a **failure injection** mechanism. Add an optional `failure_config` to control which calls fail:

```rust
struct FailureConfig {
    /// Which tool name to fail on (e.g., "browser_snapshot", "page_url")
    tool_filter: Option<String>,
    /// Which call number (1-indexed) for that tool triggers the failure. None = all calls.
    call_number: Option<usize>,
    /// HTTP status code for the failure
    status: u16,
    /// Body for the failure response
    body: String,
    /// Whether to drop the connection instead of sending a response
    drop_connection: bool,
    /// Per-tool call counters (mutable state)
    call_counts: Mutex<HashMap<String, usize>>,
}
```

The mock server's `/mcp/call-tool` handler checks the failure config before responding. If the current call matches the filter, it either returns the error status or drops the TCP connection.

---

### Test 1.1: `test_server_unreachable_on_post_command_snapshot`

**What it tests:** The CLI's behavior when the server becomes unreachable on the *second* tool call (simulating the exact bug scenario — `open` succeeds, but the next command's post-command snapshot calls fail).

**Steps:**
1. Start mock server with failure config: `browser_snapshot` returns HTTP 503 on first call
2. Run `open https://example.com/` → should succeed (open_session + browser_navigate work)
3. Run `click e5` → the tool call (`browser_click`) succeeds, but `post_command_snapshot`'s `browser_snapshot` call gets 503
4. Verify the CLI reports an appropriate error (not a crash/panic)
5. Verify exit code is non-zero

**Expected behavior (current):** `post_command_snapshot` silently returns (the `Err` from `call_tool` hits the `_ => return` branch). The user sees no output at all — no error, no snapshot path, nothing.

**Expected behavior (desired):** CLI should print a warning to stderr: `"Warning: failed to capture post-command snapshot: server returned 503"` and exit with a non-zero code or at least inform the user.

**Assertions:**
```rust
assert_ne!(result.exit_code, 0);  // or at minimum stderr should contain a warning
assert!(result.stderr.contains("snapshot") || result.stderr.contains("503"));
```

---

### Test 1.2: `test_server_drops_connection_during_snapshot`

**What it tests:** The CLI's behavior when the server drops the TCP connection (simulating server crash).

**Steps:**
1. Start mock server with failure config: `drop_connection = true` on the 2nd `browser_snapshot` call
2. Run `open https://example.com/` → succeeds
3. Run `fill e3 "test"` → tool call succeeds, but `browser_snapshot` connection is dropped
4. Verify the CLI handles the connection drop gracefully

**Expected behavior:** The `call_tool` function in http.rs catches transport errors at line 97-99 and returns `Err(format!("HTTP request failed: {e}"))`. In `post_command_snapshot`, this hits the `_ => return` branch. The CLI prints nothing and exits 0 (the command succeeded, just the snapshot failed).

**Assertions:**
```rust
// Current behavior: CLI exits 0 silently (suboptimal but not a crash)
// Desired: at minimum a stderr warning
assert!(result.stderr.contains("HTTP request failed") || result.stderr.contains("snapshot"));
```

---

### Test 1.3: `test_all_three_snapshot_calls_fail`

**What it tests:** Worst case — all three parallel calls in `post_command_snapshot` fail (server completely unresponsive after command execution).

**Steps:**
1. Start mock server with failure config: ALL calls to `page_url`, `page_title`, `browser_snapshot` return 503
2. Run `open` → succeeds
3. Run `click e5` → tool call succeeds, all three snapshot calls fail
4. Verify behavior

**Assertions:**
```rust
// post_command_snapshot returns early at match guard
// No output, no error, exit 0 (the click itself succeeded)
// This is a problem: user has no idea the snapshot failed
```

---

### Test 1.4: `test_intermittent_server_failure_on_tool_call`

**What it tests:** Server fails on the actual tool call (not just snapshot). The most direct simulation of the user's bug.

**Steps:**
1. Start mock server with failure config: `browser_click` returns 503 on 1st attempt, 200 on 2nd
2. Run `open` → succeeds
3. Run `click e5` → first attempt fails, but does the CLI retry?

**Expected behavior (current):** The CLI will fail immediately. `handle_tool_command` calls `with_session` → `call_tool` → gets 503 → returns `Err`. The `run()` function propagates this error and exits with code 1. There is no retry logic for tool commands (only for the `list` command via `is_backend_unreachable_error`, and for stale sessions via `with_session`).

**Assertions:**
```rust
assert_ne!(result.exit_code, 0);
assert!(result.stderr.contains("503") || result.stderr.contains("HTTP request failed"));
```

**Note:** This test validates that the error message is clear enough for the user to understand what went wrong. Currently it would say `"HTTP request failed with status 503: <body>"` or similar.

---

### Test 1.5: `test_empty_snapshot_content_accepted_silently`

**What it tests:** R3 — the race condition where `browser_snapshot` returns `Ok("")` (empty string), which passes the match guard but produces a 0-byte snapshot file.

**Steps:**
1. Start mock server with a special mode where `browser_snapshot` returns `Ok("")` (empty string, not an error)
2. Run `open https://example.com/` → succeeds with normal snapshot
3. Run `click e5` → tool call succeeds, post_command_snapshot gets empty snapshot
4. Verify the snapshot file written to disk is empty
5. Verify the CLI reports something useful

**Assertions:**
```rust
// Current behavior: CLI prints snapshot path pointing to empty file, exit 0
// The user reads the file, gets 0 lines, has no element refs for next command
// Desired: CLI should detect empty snapshot and warn, or retry
let snapshot_path = extract_snapshot_path(&result.stdout);
let content = fs::read_to_string(&snapshot_path).unwrap_or_default();
assert!(!content.is_empty(), "Snapshot file should not be empty");
```

---

### Test 1.6: `test_partial_snapshot_failure_one_of_three`

**What it tests:** Only one of the three parallel calls fails (e.g., `page_title` fails but `page_url` and `browser_snapshot` succeed). The `tokio::join!` + match guard causes ALL to be discarded.

**Steps:**
1. Start mock server with failure config: `page_title` returns 503
2. Run `open` → succeeds
3. Run `click e5` → tool call succeeds
4. Verify that even though `page_url` and `browser_snapshot` succeeded, the snapshot is not saved

**Assertions:**
```rust
// Current behavior: post_command_snapshot's match (Ok, Err, Ok) hits _ => return
// All results are discarded even though 2/3 succeeded
// No snapshot file created, no output
// This is wasteful — should use the 2 successful results
assert!(!result.stdout.contains("[Snapshot]"), "No snapshot output expected");
// Desired: snapshot should have been saved with whatever calls succeeded
```

---

## Real Server Tests (Browser4.jar + actual browser)

### Test 2.1: `test_multi_command_server_stability`

**What it tests:** That the real Browser4 server stays responsive across a sequence of commands (open → goto → fill → press → click), simulating the full Amazon search workflow from the original bug report.

**Prerequisites:** Requires `BROWSER4_E2E_SERVICE_URL` or local Browser4.jar. Uses fixture HTML pages.

**Steps:**
1. `open` the interactive fixture page
2. `fill` a text input
3. `press Enter` (this may trigger navigation on the fixture)
4. `click` a button on the resulting page
5. After each command, verify the CLI exits 0
6. After the final command, verify the snapshot file is non-empty

**Assertions:**
```rust
let open_result = run_command(ctx, &["open", OPEN_PROFILE_MODE_ARG, &ctx.interactive_url()]);
assert_eq!(open_result.exit_code, 0);

let fill_result = run_command(ctx, &["fill", "e3", "test input"]);
assert_eq!(fill_result.exit_code, 0);
// Verify snapshot file exists and is non-empty
let snap_path = extract_snapshot_path(&fill_result.stdout);
let content = fs::read_to_string(&snap_path).unwrap();
assert!(!content.is_empty(), "Snapshot after fill should be non-empty");

let press_result = run_command(ctx, &["press", "Enter"]);
assert_eq!(press_result.exit_code, 0);
let press_snap = extract_snapshot_path(&press_result.stdout);
let press_content = fs::read_to_string(&press_snap).unwrap();
assert!(!press_content.is_empty(), "Snapshot after press Enter should be non-empty");

let click_result = run_command(ctx, &["click", "e7"]);
assert_eq!(click_result.exit_code, 0);
```

**Pass condition:** All commands exit 0 and all snapshots are non-empty.

---

### Test 2.2: `test_rapid_sequential_commands_without_snapshot_reads`

**What it tests:** Server stability under rapid command execution without pauses between commands (simulating a script/batch-like usage pattern).

**Steps:**
1. `open` the interactive fixture page
2. Execute 10 commands in rapid succession without reading snapshots between them:
   - `fill e3 "a"`, `fill e3 "b"`, `fill e3 "c"`, `click e5`, `click e6`, etc.
3. Verify all 10 commands exit 0

**Assertions:**
```rust
for cmd in &commands {
    let result = run_command(ctx, cmd);
    assert_eq!(result.exit_code, 0, "Command {:?} failed", cmd);
}
```

**Pass condition:** Zero failures across 10 sequential commands.

---

### Test 2.3: `test_snapshot_validity_after_form_submission_navigation`

**What it tests:** R2/R3 in the real server — after `press Enter` on a form that triggers navigation, the post-command snapshot must contain valid page content (not empty).

**Steps:**
1. `open` the fixture form page (which has a search/submit form)
2. `fill` the search input
3. `press Enter` to submit (this triggers navigation)
4. Read the snapshot file
5. Verify it contains element refs (is non-empty and has at least some structure)

**Assertions:**
```rust
let press_result = run_command(ctx, &["press", "Enter"]);
assert_eq!(press_result.exit_code, 0);

let snap_path = extract_snapshot_path(&press_result.stdout);
let content = fs::read_to_string(&snap_path).unwrap();
assert!(!content.is_empty(), "Snapshot must not be empty after form submission");
assert!(content.contains("[ref="), "Snapshot must contain element refs");
```

**Pass condition:** Snapshot is non-empty and contains element references.

---

### Test 2.4: `test_server_health_endpoint_remains_up`

**What it tests:** That the server's health endpoint (`/actuator/health`) remains responsive after multiple tool calls.

**Steps:**
1. `open` the interactive fixture page
2. Execute several tool commands
3. Between each command, make a direct HTTP GET to `{base_url}/actuator/health`
4. Verify each health check returns `{"status":"UP"}`

**Assertions:**
```rust
for (i, cmd) in commands.iter().enumerate() {
    run_command(ctx, cmd);
    let health = reqwest::blocking::get(&format!("{}/actuator/health", ctx.browser4_base_url))
        .expect("Health check should succeed");
    assert!(health.status().is_success(), "Health check failed after command {}", i);
}
```

**Pass condition:** Health check succeeds after every command.

---

## Implementation Plan

### Phase 1: Extend MockBrowser4Server (in `tests/e2e.rs`)

Add failure injection to the mock server:

```rust
// New types to add:

struct FailureRule {
    /// Tool name filter (e.g., "browser_snapshot")
    tool_filter: String,
    /// Which matching call triggers the failure (1-indexed). None = every call.
    nth_call: Option<usize>,
    /// What kind of failure
    failure: FailureMode,
}

enum FailureMode {
    HttpStatus(u16, String),     // Return HTTP error with body
    DropConnection,              // Close TCP stream without response
    EmptyResponse,               // Return Ok("") — empty string success
}

// Add to MockBrowser4Server::start() signature:
fn start() -> Self  // unchanged
fn start_with_failures(rules: Vec<FailureRule>) -> Self  // new

// Add to MockBrowser4State:
struct MockBrowser4State {
    // ... existing fields ...
    failure_rules: Vec<FailureRule>,
    call_counts: HashMap<String, usize>,  // per-tool call counter
}
```

### Phase 2: Write mock server tests (in `tests/e2e/scenarios/mock_server.rs`)

6 new test functions:
- `test_server_unreachable_on_post_command_snapshot`
- `test_server_drops_connection_during_snapshot`
- `test_all_three_snapshot_calls_fail`
- `test_intermittent_server_failure_on_tool_call`
- `test_empty_snapshot_content_accepted_silently`
- `test_partial_snapshot_failure_one_of_three`

### Phase 3: Write real server tests (in `tests/e2e/scenarios/browser.rs`)

4 new test functions:
- `test_multi_command_server_stability`
- `test_rapid_sequential_commands_without_snapshot_reads`
- `test_snapshot_validity_after_form_submission_navigation`
- `test_server_health_endpoint_remains_up`

### Phase 4: Register all tests (in `tests/e2e/scenarios/mod.rs`)

Add 10 `ScenarioDef` entries to `SCENARIOS`.

### Phase 5: Add snapshot extraction helper (in `tests/e2e.rs`)

```rust
fn extract_snapshot_path(stdout: &str) -> PathBuf {
    stdout.lines()
        .find_map(|line| {
            line.trim()
                .strip_prefix("[Snapshot](")
                .and_then(|rest| rest.strip_suffix(')'))
        })
        .map(PathBuf::from)
        .expect("Expected [Snapshot](path) in output")
}
```

---

## Expected Findings

These tests will expose:

1. **Critically:** `post_command_snapshot` silently discards ALL results when ANY of the three parallel calls fails (Test 1.6)
2. **Critically:** Empty snapshot content (`Ok("")`) is treated as success, producing 0-byte files (Test 1.5)
3. **High:** No stderr warning when snapshot capture fails — the user gets no feedback (Tests 1.1–1.3)
4. **Medium:** No retry logic in `call_tool` for transport errors (Test 1.4)
5. **Information:** Whether the real server survives rapid sequential commands (Tests 2.1–2.4)
