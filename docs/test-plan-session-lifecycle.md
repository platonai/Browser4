# Browser4 CLI Session-Lifecycle Command Test Plan

## 1. Scope

This plan covers comprehensive testing for the 8 session-lifecycle CLI commands:

| Command | Current e2e Coverage | Risk Profile |
|---------|---------------------|--------------|
| `open`   | ✅ Covered (9+ mock-server + 1 browser scenarios) | Low — already well-tested |
| `close`  | ✅ Covered (via `test_session_lifecycle` browser scenario) | Medium — tested only in full browser context |
| `list`   | ✅ Covered (via `test_session_lifecycle` browser scenario) | Medium — tested only in full browser context |
| `close-all` | ❌ Excluded ("destructive") | High — no e2e tests at all |
| `kill-all`  | ❌ Excluded ("destructive") | High — no e2e tests at all |
| `upgrade`   | ❌ Excluded ("one-time setup") | Medium — no e2e tests; relies on external downloads |
| `stop`      | ❌ Excluded ("server lifecycle") | High — no e2e tests at all |
| `status`    | ❌ Excluded ("server lifecycle") | Medium — no e2e tests at all |

**Goal**: Move `close-all`, `kill-all`, `stop`, `status`, `upgrade`, and improved `close`/`list` tests into the mock-server e2e suite. Ensure session management is stable across all commands.

---

## 2. Test Architecture

### 2.1 Two complementary layers

| Layer | Tool | When to use |
|-------|------|-------------|
| **Mock-server e2e scenarios** | `MockBrowser4Server` + `run_command()` | Commands that issue HTTP calls + mutate local state. The mock provides controlled backend responses; assertions check how the CLI processes them and what state ends up on disk. |
| **Pure unit tests** (in `main.rs` or module files) | Standard `#[test]` | Pure functions like `get_session_id_for_close`, `log_close_all_summary`, `finalize_global_cleanup`, `CloseAllSummary` helpers — no I/O needed. Already partly covered. |

### 2.2 Mock-server coverage

For **every** target command, the mock-server approach:
1. Starts a `MockBrowser4Server` (in-process HTTP mock)
2. Points the CLI at the mock via `--server=<mock-url>`
3. Runs the command via `run_command()` / `run_command_expecting_failure()`
4. Asserts on: exit code, stdout/stderr text, recorded tool calls, persisted state files

**Why mock-server**: This is the only e2e approach that works for `close-all`, `kill-all`, `stop` without disrupting the real Browser4 backend that other scenarios share. It also handles `upgrade` and `status` which the brand-new `requires_browser4: false` flag already supports.

---

## 3. Command-by-Command Test Plan

### 3.1 `close`

**Behavior under test** (`handle_close`, line 744):
- Reads persisted state → gets `session_id`
- If no session_id: prints guidance, clears state, exits 0
- If session_id: calls `close_session` MCP tool (ignores errors), clears state, prints "Session closed."

#### Scenarios

**A. `test_close_active_session`** — mock-server  
*Precondition*: CLI state has persisted `session_id = "swarm-session-1"`.  
1. Run `close`  
2. Assert exit 0  
3. Assert stdout contains "Session closed."  
4. Assert `close_session` tool was called with `{"sessionId": "swarm-session-1"}`  
5. Assert state file no longer exists (session_id cleared)  

**B. `test_close_no_active_session`** — mock-server  
*Precondition*: No persisted state.  
1. Run `close`  
2. Assert exit 0 (never errors)  
3. Assert stdout/stderr contains "Session required" guidance  
4. Assert NO `close_session` tool call was made  
5. Assert state file does not exist  

**C. `test_close_ignores_backend_close_failure`** — mock-server  
*Precondition*: Persisted session. Mock queues a tool failure for `close_session`.  
1. Run `close`  
2. Assert exit 0 (error is swallowed)  
3. Assert stdout contains "Session closed."  
4. Assert `close_session` tool call was attempted  
5. Assert state file does not exist (state cleared regardless)  

**D. `test_close_named_session`** — mock-server  
*Precondition*: Named session "auth" with persisted state.  
1. Run `-s=auth close`  
2. Assert `close_session` called with "swarm-session-1"  
3. Assert named state file (`sessions/auth.json`) is removed  
4. Assert default state file remains untouched  

---

### 3.2 `close-all`

**Behavior under test** (`handle_close_all`, line 770):
- Calls `close_all_sessions` MCP tool on each known server URL
- Clears ALL state (default + all named sessions)
- Logs summary; swallows errors from unreachable servers

**Key risk**: Destroys state for all sessions across all servers. Must not touch real state in tests.

#### Scenarios

**A. `test_close_all_single_server`** — mock-server  
*Precondition*: Default state + 2 named sessions.  
1. Run `close-all`  
2. Assert exit 0  
3. Assert `close_all_sessions` tool was called (once, against the single mock server)  
4. Assert default state file is removed  
5. Assert named session files are removed  
6. Assert mock response text appears in stdout  

**B. `test_close_all_no_active_sessions`** — mock-server  
*Precondition*: No persisted state at all.  
1. Run `close-all`  
2. Assert exit 0  
3. Assert `close_all_sessions` still called (server may have sessions CLI doesn't know about)  
4. Assert stdout: "No reachable Browser4 servers responded..." or server response  

**C. `test_close_all_server_unreachable`** — mock-server  
*Precondition*: Mock server shut down before command.  
1. Run `close-all`  
2. Assert exit 0 (errors are warnings, not fatal)  
3. Assert stderr contains the warning  
4. Assert all state files are still removed (local cleanup always happens)  

**D. `test_close_all_multiple_servers`** — unit test  
*Verify*: `close_all_sessions_across_servers()` iterates over all known server URLs.  
- Test with: 1 registered server, 2 registered servers, 0 registered servers  
- Assert response/error aggregation in `CloseAllSummary`  

**E. `test_close_all_preserves_managed_process_registry`** — mock-server  
*Precondition*: Managed process registry file exists.  
1. Run `close-all`  
2. Assert session state files removed  
3. Assert managed process registry file still exists (only `kill-all`/`stop` remove it)  

---

### 3.3 `kill-all`

**Behavior under test** (`handle_kill_all`, line 782):
- Calls `stop_browser4_server_forcibly()` → kills Java processes + browser processes  
- Calls `finalize_global_cleanup("Killed", ...)` → clears all state + logs results  
- Returns error if browser processes remain after kill attempt  

**Key risk**: This is the most destructive command. Mock-server tests must verify the CLI's *decision-making* and *output* without actually killing real processes.

#### Approach

The `kill-all` handler calls `stop_browser4_server_forcibly()` from `managed_processes.rs`, which:
1. Reads the managed process registry
2. Kills processes by PID
3. Scans for browser PID marker files and kills those too

For e2e tests, we can:
- Create controlled dummy registry files with known PIDs  
- Launch a dummy child process we control (e.g., `sleep` on Unix / `timeout` on Windows)  
- Run `kill-all` and verify it kills the dummy process  
- Assert the registry file is removed  

For unit tests, we already have coverage in `managed_processes.rs` for `ShutdownResult` / `BrowserKillResult` logic.

#### Scenarios

**A. `test_kill_all_no_running_processes`** — mock-server  
*Precondition*: Empty or missing managed process registry.  
1. Run `kill-all`  
2. Assert exit 0  
3. Assert stdout contains "No tracked Browser4 processes found"  
4. Assert all state cleaned up  
5. Assert managed process registry file removed  

**B. `test_kill_all_stops_tracked_process`** — integration (requires OS process control)  
*Precondition*: Dummy child process registered in managed process registry.  
1. Create dummy process (e.g., `sleep 60`)  
2. Register it in the managed process registry with a fake base URL  
3. Run `kill-all`  
4. Assert exit 0  
5. Assert dummy process is killed (no longer running)  
6. Assert managed process registry file removed  
7. Assert output reports the killed PID  

**C. `test_kill_all_clears_all_state`** — mock-server  
*Precondition*: Default + named session state files + dummy managed process registry.  
1. Run `kill-all`  
2. Assert all session state files removed  
3. Assert managed process registry file removed  

**D. `test_kill_all_browser_cleanup_incomplete`** — mock-server + unit  
*Precondition*: A browser PID marker file exists but the process is non-killable.  
1. Run `kill-all`  
2. Assert exit != 0 (error returned)  
3. Assert stderr contains "Browser cleanup incomplete"  

**E. `test_kill_all_reports_fallback_killed_pids`** — unit test  
*Verify*: When `ShutdownResult.fallback_killed_server_pids` is non-empty, the output includes those PIDs.  

---

### 3.4 `stop`

**Behavior under test** (`handle_stop`, line 2378):
- Calls `stop_browser4_server_forcibly()` (same as `kill-all`)  
- Calls `finalize_global_cleanup("Stopped", ...)`  
- Prints different messaging: "Browser4 server stopped." / "No Browser4 server was running."  

**Key difference from `kill-all`**: `stop` does NOT check for remaining browser processes (no error return for incomplete browser cleanup). It also uses a different action label ("Stopped" vs "Killed").

#### Scenarios

**A. `test_stop_no_running_server`** — mock-server  
*Precondition*: No managed processes.  
1. Run `stop`  
2. Assert exit 0  
3. Assert stdout contains "No Browser4 server was running."  
4. Assert all state files cleared  

**B. `test_stop_stops_server`** — integration  
*Precondition*: Dummy process in managed registry.  
1. Run `stop`  
2. Assert exit 0  
3. Assert dummy process killed  
4. Assert stdout contains "Browser4 server stopped." or "Stopped Browser4 process(es)"  
5. Assert managed process registry removed  

**C. `test_stop_never_errors_on_remaining_browsers`** — unit test  
*Verify*: Unlike `kill-all`, `stop` always returns `Ok(())`. Even if browser processes remain, it logs them but does not error.  

**D. `test_stop_forced_processes_output`** — unit test  
*Verify*: When processes are force-killed after timeout, "Stopped" action includes "Forced Browser4 process(es) after graceful timeout" message.  

---

### 3.5 `status`

**Behavior under test** (`handle_status`, line 2407):
- Prints CLI version (`VERSION` constant)  
- Prints server URL  
- Reads installed runtime metadata (from `daemon.rs`)  
- Calls `GET /actuator/health` on the server  
- Reports: UP / NOT READY / DOWN / UNREACHABLE  

#### Scenarios

**A. `test_status_server_up`** — mock-server  
*Precondition*: Mock server returns `{"status":"UP"}` for `/actuator/health`.  
1. Run `status`  
2. Assert exit 0  
3. Assert stdout contains "CLI version:"  
4. Assert stdout contains "Server health: UP"  
5. Assert exactly one `GET /actuator/health` request was made  

**B. `test_status_server_unreachable`** — mock-server  
*Precondition*: No mock server running (point to a free port with nothing listening).  
1. Run `status --server=http://127.0.0.1:<free-port>`  
2. Assert exit 0  
3. Assert stdout contains "Server health: UNREACHABLE"  

**C. `test_status_server_not_ready`** — mock-server  
*Precondition*: Mock returns HTTP 503 for `/actuator/health`.  
*Note*: The current `MockBrowser4Server` always returns 200 for `/actuator/health`. We need to add the ability to configure mock health responses.  
1. Run `status`  
2. Assert stdout contains "Server health: DOWN (HTTP 503)"  

**D. `test_status_installed_runtime`** — mock-server  
*Precondition*: Runtime metadata file exists in the test state dir.  
1. Write a dummy runtime metadata JSON  
2. Run `status`  
3. Assert stdout contains "Installed version:" with the tag  

**E. `test_status_no_installed_runtime`** — mock-server  
*Precondition*: No runtime metadata file.  
1. Run `status`  
2. Assert stdout contains "Installed version: not installed"  

---

### 3.6 `upgrade`

**Behavior under test** (`handle_upgrade`, line 2355):
- Calls `install_browser4_runtime(tag, force)`  
- If already at latest and not forced: prints "already at the latest version"  
- Otherwise: prints upgrade success with paths  

**Key risk**: The real `install_browser4_runtime` downloads from GitHub releases. Testing this requires either:
1. A mock that intercepts the download
2. Unit tests on the pure logic branches
3. Integration test with `--force` against a local build

#### Approach

The `handle_upgrade` function has two code paths based on `runtime.reused_existing && !force`:
- **Path A** (no-op): Print "already at the latest" — testable by mocking `install_browser4_runtime`
- **Path B** (install): Print upgrade details — testable same way

Since `install_browser4_runtime` is an async function in `daemon.rs`, we can either:
- **Option 1 (preferred)**: Add unit tests for the `handle_upgrade` branching logic by extracting the install step behind a test seam, OR
- **Option 2**: Write a mock that serves a tarball from a local HTTP server (complex for a smoke test)

#### Scenarios

**A. `test_upgrade_already_latest`** — unit test (extract testable function)  
*Given*: `runtime.reused_existing = true`, `force = false`  
1. Assert "Browser4 is already at the latest version" printed  
2. Assert no paths printed  

**B. `test_upgrade_installs_new_version`** — unit test  
*Given*: `runtime.reused_existing = false`  
1. Assert "Browser4 upgraded successfully" printed  
2. Assert install dir, lib dir, java path printed  
3. Assert restart hint printed  

**C. `test_upgrade_force_reinstall`** — unit test  
*Given*: `runtime.reused_existing = true`, `force = true`  
1. Assert "Browser4 upgraded successfully" printed (force overrides reuse)  

**D. `test_upgrade_specific_tag`** — unit test  
*Given*: `tag = Some("v4.9.0")`  
1. Assert `install_browser4_runtime(Some("v4.9.0"), false)` was called  

**E. `test_upgrade_install_failure`** — unit test  
*Given*: `install_browser4_runtime` returns an error  
1. Assert error propagated to caller  

---

### 3.7 `list`

**Behavior under test** (`handle_list`, line 1098):
- Calls `list_sessions` MCP tool on the backend  
- Reads local persisted state files from `sessions/` directory  
- Correlates local state with backend status to produce a table: Name | Session ID | Status | Next open  
- Status: Active (backend confirms), Stale (backend doesn't have it), Unknown (backend unreachable)  
- Falls back gracefully when backend unreachable  

#### Existing Coverage
Currently tested only in `test_session_lifecycle` (browser scenario). The mock-server approach enables more edge cases.

#### Scenarios

**A. `test_list_active_session`** — mock-server  
*Precondition*: Persisted session "swarm-session-1". Mock server lists it as active.  
1. Run `list`  
2. Assert exit 0  
3. Assert table row shows "swarm-session-1" with Status = "Active"  
4. Assert `list_sessions` tool was called  

**B. `test_list_stale_session`** — mock-server  
*Precondition*: Persisted session "swarm-session-1". Mock server lists it as stopped.  
1. Run `list`  
2. Assert Status = "Stale"  
3. Assert Next open = "Reopen" (or equivalent)  

**C. `test_list_backend_unreachable`** — mock-server  
*Precondition*: No mock server (or mock server shut down). Persisted session exists.  
1. Run `list`  
2. Assert exit 0 (does not error)  
3. Assert Status = "Unknown"  
4. Assert "Note: Browser4 backend is not started or unreachable" appears  

**D. `test_list_no_sessions`** — mock-server  
*Precondition*: No persisted state, no named sessions.  
1. Run `list`  
2. Assert exit 0  
3. Assert table header printed but no session rows  

**E. `test_list_multiple_named_sessions`** — mock-server  
*Precondition*: "auth" session (active), "scraper" session (stale), default session (active). Mock server reflects these statuses.  
1. Run `list`  
2. Assert all three rows appear  
3. Assert "auth" → Active, "scraper" → Stale, "(default)" → Active  
4. Assert correct ordering  

---

### 3.8 `open` (additional edge cases)

Already well-tested but missing a few session-stability scenarios:

**A. `test_open_clears_stale_reference_before_reopening`** — mock-server  
*Precondition*: Mock returns `browser_navigate` failure with "Target closed" for the first navigate, then succeeds.  
- Already covered by `test_open_reopens_saved_session_after_human_closed_tab`  

**B. `test_open_concurrent_session_conflict`** — mock-server  
*Precondition*: The mock returns a session ID that was already closed by another CLI instance.  
1. Open session, get "swarm-session-1"  
2. Configure mock so `list_sessions` no longer shows it  
3. Run `open` again  
4. Assert a new session is created (not reused)  

---

## 4. Session Management Stability

### 4.1 State transitions to verify

```
┌─────────────┐  open    ┌─────────────┐
│  No State   │─────────→│  Active     │
└─────────────┘          │  Session    │
      ↑                  └──────┬──────┘
      │ close                   │ backend stops session
      │                         ↓
      │                  ┌─────────────┐
      │     open         │  Stale      │
      └──────────────────│  (persisted)│
                         └─────────────┘
```

**Tests to add:**
1. **State idempotency**: Running `close` twice in a row should exit 0 both times
2. **State isolation**: Named session state must not affect default session state
3. **Crash recovery**: If the CLI process is killed mid-`open`, the next `open` should recover (stale session detection)
4. **State file corruption**: A malformed state JSON file should be treated as missing (graceful fallback to default)

### 4.2 Cross-command state integrity

After each command, verify the state directory is in a consistent state:

| Command | Expected State After |
|---------|---------------------|
| `open`  | `session_id` persisted; `base_url` saved |
| `close` | State file removed |
| `close-all` | ALL state files removed; managed process registry preserved |
| `kill-all` | ALL state files removed; managed process registry removed |
| `stop`   | ALL state files removed; managed process registry removed |
| `list`   | State unchanged (read-only) |
| `status` | State unchanged (read-only) |
| `upgrade`| State unchanged (read-only) |

---

## 5. MockBrowser4Server Extensions Needed

To support the new scenarios, the mock server needs these extensions:

### 5.1 Per-route response configuration
```rust
// Currently: always returns {"status":"UP"} for /actuator/health
// Needed: ability to configure health response per test

mock_server.set_health_response(status_code: u16, body: &str);
// or more generally:
mock_server.set_route_response(method: &str, path: &str, status: u16, content_type: &str, body: &str);
```

### 5.2 `close_session` and `close_all_sessions` tool handling
The mock currently does not handle `close_session` or `close_all_sessions` in its `match` arms — they fall through to `mock_browser_tool_text` which returns "mock response for close_session". These should be proper tools that record the call and return a meaningful result.

### 5.3 Server shutdown simulation
For "backend unreachable" tests: add a `shutdown()` method to `MockBrowser4Server` that stops the listener thread without dropping the `state` (so we can still read recorded state after).

Currently `Drop` sets the shutdown flag and the listener stops accepting new connections. We need either:
- A way to stop accepting connections while keeping the state accessible
- Or just use a free port with nothing listening

---

## 6. Implementation Plan (ordered)

### Phase 1: Mock server extensions (1-2 files)
1. Add `close_session` and `close_all_sessions` tool handlers to `serve_mock_browser4_request()`
2. Add configurable health endpoint responses to `MockBrowser4Server`
3. Add a `shutdown()` method or use free-port trick for unreachable-server tests

### Phase 2: `close` mock-server scenarios
- File: `cli/browser4-cli/tests/e2e/scenarios/mock_server.rs`
- 4 scenarios as described in §3.1
- Move `close` from `excluded_commands` → `tested_commands` (it was already tested via browser scenario, but now also via mock)

### Phase 3: `close-all` mock-server scenarios
- 4 scenarios as described in §3.2  
- Move `close-all` from `excluded_commands` → `tested_commands`

### Phase 4: `list` mock-server scenarios
- 5 scenarios as described in §3.7

### Phase 5: `status` mock-server scenarios
- 5 scenarios as described in §3.5
- Requires mock health endpoint configurability
- Move `status` from `excluded_commands` → `tested_commands`

### Phase 6: `stop` and `kill-all` tests
- Unit tests for output formatting and `ShutdownResult` display logic
- Integration tests for process management (requires OS process spawning)
- Move `stop` and `kill-all` from `excluded_commands` → `tested_commands`

### Phase 7: `upgrade` tests
- Unit tests for `handle_upgrade` branching logic
- Optionally: refactor to allow test injection of `install_browser4_runtime`
- Move `upgrade` from `excluded_commands` → `tested_commands`

### Phase 8: Session management stability
- State idempotency tests
- State isolation tests (named vs default)
- Corrupted state file recovery

### Phase 9: Coverage update
- Update `tested_commands()` in `e2e.rs`  
- Remove entries from `excluded_commands()`
- Run `verify_e2e_command_coverage()` to confirm no gaps

---

## 7. Test Execution

```bash
# Run all new scenarios
cargo test --test e2e -- --nocapture --scenario=test_e2e_close_*
cargo test --test e2e -- --nocapture --scenario=test_e2e_close_all_*
cargo test --test e2e -- --nocapture --scenario=test_e2e_list_*
cargo test --test e2e -- --nocapture --scenario=test_e2e_status_*
cargo test --test e2e -- --nocapture --scenario=test_e2e_stop_*
cargo test --test e2e -- --nocapture --scenario=test_e2e_kill_all_*

# Run all mock-server scenarios (no real Browser4 needed)
cargo test --test e2e -- --nocapture --scenario=*mock*

# Run the full suite with new coverage
cargo test --test e2e -- --nocapture
```

---

## 8. Success Criteria

1. **Coverage gate**: `verify_e2e_command_coverage()` passes with zero commands in `excluded_commands()` (or all remaining exclusions have documented justifications)
2. **No regression**: All existing ~35 scenarios continue to pass
3. **Session stability**: State transitions are verified end-to-end — create → read → update → clear
4. **Error resilience**: All commands handle backend errors gracefully (never panic on bad JSON, unreachable server, killed session)
5. **Isolation**: Mock-server scenarios never touch the real Browser4 backend, real state files, or real managed processes
