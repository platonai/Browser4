# Memory – 2026-04-14

## Task: Split Long E2E Tests in e2e.rs

**File:** `sdks/browser4-cli/tests/e2e.rs`

### What was done
Split 6 slow scenario test functions (each taking >30 s) into 12 smaller ones so that each stays under ~30 s per test. The scenarios share the `E2ECtx` across tests, so the "first half" leaves the browser session open and the "second half" continues on it.

**Splits made:**
| Original (time) | Split into |
|---|---|
| `test_session_lifecycle` (33s) | `test_session_open_and_list` + `test_session_close` |
| `test_navigation_and_storage` (56s) | `test_navigation` + `test_storage` |
| `test_interaction_commands` (53s) | `test_typing_commands` + `test_keyboard_and_pointer_commands` |
| `test_form_controls_and_exports` (57s) | `test_form_controls` + `test_export_commands` |
| `test_mouse_and_dialog` (90s) | `test_mouse_commands` + `test_dialog_commands` |
| `test_tab_commands` (47s) | `test_tab_list_and_new` + `test_tab_select_and_close` |

**Also updated:**
- `SCENARIOS` const: 9 → 15 entries with correct `restart_browser4` flags
- `tested_commands()`: comments updated to match new function names

### Key patterns used
- First half of a split calls `reset_cli_artifacts` and opens a fresh session; does **not** call `close` at the end.
- Second half continues on the same open session; calls `close` when done.
- `restart_browser4: false` for all second halves (and for scenarios that continue from a previous test).
- `test_tab_select_and_close` re-runs `tab-list` at the start to retrieve the `other_tab_id` (since local vars don't persist across scenario functions).

### Lessons learned
- When splitting sequential e2e scenarios that share state via `E2ECtx`, leave sessions open between halves and avoid calling `reset_cli_artifacts` in the second half.
- The `restart_browser4` flag controls the Browser4 *service* restart, not session state. Sessions are managed by explicit `open`/`close` CLI commands.
- The file compiles cleanly after the split (`cargo check --test e2e` passes with no errors).

## Task: Fix E2E Kill Warnings and test_storage Failure

**Files:** `sdks/browser4-cli/tests/e2e.rs`, `sdks/browser4-cli/src/managed_processes.rs`

### What was done

Fixed two bugs exposed when running e2e tests:

**Bug 1: `kill: (PID): No such process` warnings**
- Root cause: `force_stop` in `managed_processes.rs` invokes `kill -KILL <pid>` without suppressing stderr. If the process had already exited, the kernel prints "No such process" to stderr, leaking into test output.
- Fix: Added `.stderr(std::process::Stdio::null())` to the kill command in `force_stop` (Unix branch; Windows already uses `-ErrorAction SilentlyContinue`).

**Bug 2: `test_e2e_storage` fails with "Channel was cancelled"**
- Root cause: The main test loop called `kill_all_browsers()` after every scenario including `test_navigation`, terminating Chrome before `test_storage` could use the same open session.
- Fix: Removed the per-scenario `kill_all_browsers()` call from the main loop. Instead it is now called inside `run_named_scenario` only when `restart_browser4 == true`, so linked tests sharing an open session are not disrupted. The final cleanup call at the end of the main function is preserved.

### Lessons learned
- Calling `kill_all_browsers()` between linked split-tests breaks shared session state. It should only happen when `restart_browser4 == true`, not unconditionally after every scenario.
- Always suppress stderr (`Stdio::null()`) for best-effort process management commands that may fail if the target process already exited.
- Both changes compile cleanly (`cargo check --test e2e` passes).
