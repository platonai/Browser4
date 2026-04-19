Here is the compressed content (≈1 450 chars):
---
# Daily Memory – 2026-04-15
## fix-e2e-test-cleanup-bugs
**Bug 1 – "kill: No such process" spam**
- `is_process_running` in `managed_processes.rs` ran `kill -0 <pid>` without suppressing stderr.
- Fix: add `.stderr(Stdio::null())` to the `kill -0` command (Unix path).
- File: `sdks/browser4-cli/src/managed_processes.rs`
**Bug 2 – `test_e2e_batch_error_handling` panic**
- `type` command simulates keystrokes and *appends* to existing field content, not replaces. Test expected exact match `"bail test"` but actual value was `"before errorbail test"`.
- Fix: change `wait_for_state` predicates to use `.ends_with(expected_text)` for `typeValue` checks.
- File: `sdks/browser4-cli/tests/e2e.rs`
**Lessons**
- `type` always appends; use `.ends_with()` (or clear field first) when testing across sub-tests on the same page.
- Always add `stderr(null)` when using `kill -0` purely for existence checks.
---
## fix-batch-form-submission-bug
**Root cause**: `executeBatchStep` for the `"open"` op had a stray `managedSession.driver` expression (line 419) that eagerly initialised the CDP browser context, breaking subsequent batch tool steps with "Cannot find context with specified id".
**Fix**: Remove that 1 line. Batch `"open"` now mirrors `handleOpenSession` — creates session, returns `sessionId`, defers driver init to first actual tool use.
**File**: `browser4-rest/…/MCPToolController.kt`
**Lesson**: Never access `managedSession.driver` in batch open; rely on lazy initialization.
---
## create-browser4-cli-bash-script
**Task**: Create a bash equivalent of `sdks/browser4-cli/browser4-cli.cmd` for Linux/macOS.
**Output**: `sdks/browser4-cli/browser4-cli.sh`
- Resolves `SCRIPT_DIR` via `${BASH_SOURCE[0]}`, constructs `EXE_PATH` pointing to `target/release/browser4-cli`.
- Prints a helpful error and exits 1 if the binary is missing.
- Uses `exec` to replace the shell process, forwarding all args and exit code cleanly.
- Made executable with `chmod +x`.
**Lesson**: Use `exec "$EXE_PATH" "$@"` (not a subshell) so the caller sees the binary's real exit code and signals propagate correctly.
---

