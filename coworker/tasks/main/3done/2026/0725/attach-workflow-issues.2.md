# Issues: attach-workflow

> **Source:** `20260725-154131-attach-workflow.full.md` | **Date:** 20260725-154131 | **Mode:** dev

## Scenario Background

### Task

The attach/open/goto command sequence was completed. All 17 steps executed, with some workarounds required for steps 6b, 12, and 15 where the default session slot was occupied. The critical regression check (Step 6) passed — no `user-data-dir` appeared in the snapshot, confirming the extension session connects to the user's own Chrome.

### Execution Context

| Step | Command | Exit | Notes |
|------|---------|------|-------|
| 0 | `kill-all` | 0 | Clean slate, server stopped |
| 1 | `attach --extension` | 0 | Session `0d91be28`, connected in 1s |
| 2 | `list` | 0 | Active, Extension |
| 3 | `goto https://example.com/1` | 0 | Title "Example Domain" |
| 4 | `list` | 0 | LAST ACCESS updated |
| 5 | `goto chrome://version/` | 0 | Title empty, took ~90s (unexpected success) |
| 6 | `snapshot grep "user-data-dir"` | 0 | No matches — ✅ critical regression check passed |
| 6b | `attach --extension` | 1 (error) | **Workaround:** had to `close` first, then re-attach |
| 6b | `close` then `attach --extension` | 0 | Session `b45497cd` |
| 7 | `goto https://example.com/2` | 0 | Correct via ext session |
| 8 | `list` | 0 | Active, Extension, LAST ACCESS upd...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: chrome://version navigation succeeds silently instead of failing with clear error

**Severity:** Medium
**Category:** Reliability

#### Reproduction

`./b4w.ps1 goto "chrome://version/"` on an extension-attached session

#### Expected Behavior

Per the task documentation's "Expected Behavior After the Fix" section, the command should fail in ~10-15s with a clear error: "Attached session ... is no longer healthy. Re-run `attach --extension` to reconnect."

#### Actual Behavior

The command succeeded (exit code 0), returned a snapshot, and reported the page URL as `chrome://version/`. However, the command took ~90 seconds to complete (exceeded the 60s foreground timeout and had to complete in background). The session was then marked `Stale` in the subsequent `list`.

#### Root Cause Analysis

The 2026-07-25 `ExtensionChromeService.kt` fix was supposed to cancel pending CDP requests immediately on `chrome.debugger.onDetach`, making the command fail fast. Instead, some CDP requests appear to wait for their individual timeouts before the overall command completes, and the snapshot was generated from cached/stale data. The fix may not be fully effective, or there's a race condition where the snapshot is captured before the detach is fully processed.

#### Code Pointer

``browser4-apps/browser4-bundle/.../ExtensionChromeService.kt` (the detach handler) and the snapshot capture path after navigation.`

#### AI Suggested Improvement

- After a `chrome.debugger.onDetach` event fires, immediately reject all pending CDP futures with a clear error rather than letting them wait for timeouts
- Add a check after navigation that verifies the debugger is still attached before attempting to capture a snapshot
- Surface a distinct error message for chrome:// URL navigation attempts: "Cannot debug chrome:// internal pages — the debugger was detached. Session is now stale."
- The 90s delay before completion indicates the fix in `ExtensionChromeService.kt` may need to be more aggressive about cancelling in-flight CDP calls

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
has fixed in another commit

---

### Issue 2: Re-attach documentation incomplete — close step missing

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. `attach --extension` (creates default session)
2. Navigate to `chrome://version/` (session becomes stale)
3. `attach --extension` (attempt re-attach)

#### Expected Behavior

Re-attach succeeds and creates a new session, as documented in `attach.md`: "If the session goes stale, re-attach with `attach --extension`."

#### Actual Behavior

Error: "An unnamed session already exists: ... Use `-s <name>` to create a named session instead, or run `browser4-cli close` to end the current unnamed session first."

#### Root Cause Analysis

The default unnamed session slot is still held by the stale session. The CLI prevents overwriting it. The documentation in `attach.md` (section "Troubleshooting" and "Extension session goes stale") says to just run `attach --extension` again but omits the prerequisite `close` step.

#### Code Pointer

``skills/browser4-cli/references/attach.md:144` (the troubleshooting row for "Extension session goes stale") and `attach.md:109` (the troubleshooting bullet for chrome:// pages).`

#### AI Suggested Improvement

- Update `attach.md` troubleshooting table row "Extension session goes stale" to: "Close the stale session with `close`, then re-attach with `attach --extension`"
- Update the chrome:// troubleshooting bullet: "If the session goes stale, run `close` first, then re-attach with `attach --extension`"
- Consider making `attach --extension` auto-close a stale unnamed session before creating a new one, reducing the friction of the two-step workflow

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
need to check whether it is caused by stale code

---

### Issue 3: `goto chrome://version/` took ~90 seconds instead of expected ~10-15s

**Severity:** Medium
**Category:** Reliability

#### Reproduction

`./b4w.ps1 goto "chrome://version/"` on an extension-attached session.

#### Expected Behavior

The command should fail in ~10-15 seconds (per the task's "Expected Behavior After the Fix" section describing the `ExtensionChromeService.kt` fix).

#### Actual Behavior

The command exceeded the 60s foreground timeout and completed in background. Total elapsed time was approximately 90 seconds from the start of navigation to snapshot generation.

#### Root Cause Analysis

The `onDetach` handler may cancel pending CDP requests, but the post-navigation checks (snapshot capture, title extraction, etc.) may create new CDP requests after the detach that individually time out. The fix may only address the first wave of pending requests but not prevent new requests from being queued.

#### Code Pointer

``ExtensionChromeService.kt` — the `onDetach` handler needs to set a session-level "disconnected" flag that prevents all subsequent CDP calls from being attempted, rather than just cancelling currently pending ones.`

#### AI Suggested Improvement

- After a detach event, set a flag on the session that causes all future CDP call attempts to fail immediately with a clear error
- The `goto` command's post-navigation phase should check the session health flag before attempting snapshot/title extraction
- Consider a shorter overall timeout for extension session commands (e.g., 30s) with a clear "session unhealthy" error

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
need to check if it runs on stale code

---

### Issue 4: Can't create second unnamed attach when default session is occupied

**Severity:** Low
**Category:** UX

#### Reproduction

1. `open` (creates a Browser4 session in the default slot)
2. `attach --extension` (attempt to create another extension session)

#### Expected Behavior

A new extension session is created, possibly auto-assigned a name.

#### Actual Behavior

Error: "An unnamed session already exists: ... Use `-s <name>` to create a named session instead..."

#### Root Cause Analysis

The unnamed session constraint only allows one session in the `(default)` slot. When that slot is taken (by any session type), `attach --extension` without `-s` fails. This is a reasonable design choice but the workflow friction is notable because the documentation examples all show `attach --extension` working standalone.

#### Code Pointer

`CLI session management layer — the validation that rejects a second unnamed session.`

#### AI Suggested Improvement

- Auto-generate a name for the new extension session when the default slot is already occupied (e.g., `ext-1`, `ext-2`) rather than erroring
- Or, when the default slot holds a Browser4 session and user runs `attach --extension`, auto-name the extension session (e.g., after the browser channel "chrome")
- At minimum, add a note in `attach.md` that `--extension` requires `-s <name>` when another unnamed session exists

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
add a note in `attach.md` that `--extension` requires `-s <name>` when another unnamed session exists

---

### Issue 5: `open` reconnects instead of creating new session when default is active

**Severity:** Low
**Category:** UX

#### Reproduction

1. `open` (creates Browser4 session in default slot)
2. Wait for navigation/some activity
3. `open` again

#### Expected Behavior

A new Browser4 session is created (per the task description and intuitive expectation of "open").

#### Actual Behavior

"Using existing session DEFAULT (current page: ...). Session already open: ..." — reconnects to the existing session without creating a new one.

#### Root Cause Analysis

`open` follows the "reuse or refresh" semantics documented in SKILL.md: "goto auto-opens/reconnects — you rarely need to manage sessions manually." When a named session exists and is still active, `open` reconnects. This is by design but can be confusing — the command name "open" implies creation, not reconnection.

#### Code Pointer

`CLI `open` command handler — session resolution logic.`

#### AI Suggested Improvement

- Add a `--new` flag to `open` to force creation of a new session even when an existing one is available
- Consider renaming `open` to something more descriptive or adding clearer output: "Reconnected to existing session (use `open --new` to create a fresh session)"
- Document the reuse semantics more prominently in the `open` command help: "open — Open a browser session or reconnect to an existing one"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Document the reuse semantics more prominently in the `open` command help: "open — Open a browser session or reconnect to an existing one"

---

### Issue 6: Named session output minimal — no session UUID shown for `-s <name> open`

**Severity:** Low
**Category:** UX

#### Reproduction

`./b4w.ps1 -s regular2 open`

#### Expected Behavior

Output showing the session UUID, connection type, and confirmation similar to how default session creation works.

#### Actual Behavior

Only "Session opened: regular2" — no UUID, no connection type, no confirmation that a Browser4 session was created.

#### Root Cause Analysis

When a named session is provided, the output path differs from the default session creation path and omits the session metadata.

#### Code Pointer

`CLI `open` command handler — output formatting for named vs unnamed sessions.`

#### AI Suggested Improvement

- Include the session UUID in the output for named sessions, matching the default session output format
- Add connection type to the output: "Browser4 session opened: regular2 (a1b2c3d4-...)"
- Ensure consistent output format between named and unnamed session creation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Shell working directory resets to home after each command

**Severity:** Low
**Category:** Reliability

#### Reproduction

Run `cd /d/workspace/Browser4/Browser4-4.12 && ./b4w.ps1 <cmd>` from Git Bash. Observe "Shell cwd was reset to C:\Users\pereg" after each command.

#### Expected Behavior

Working directory persists between commands.

#### Actual Behavior

The shell working directory resets to `C:\Users\pereg` after each command. Requires prefixing every command with `cd /d/workspace/Browser4/Browser4-4.12 &&`.

#### Root Cause Analysis

The `b4w.ps1` script or the Java process it launches appears to change the working directory, and the Bash shell wrapper resets it. The `pwsh` invocation to run the PowerShell script likely triggers this.

#### Code Pointer

``b4w.ps1` or the caller's shell integration.`

#### AI Suggested Improvement

- Investigate whether the PowerShell invocation in `b4w.ps1` is causing the CWD reset
- Provide a `b4w.sh` wrapper for Git Bash users that preserves the working directory
- Document this as a known Git Bash limitation in the development guide

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: `list` display truncates UUID-based session names awkwardly

**Severity:** Low
**Category:** UX

#### Reproduction

Have an extension session displaced from the default slot — it gets auto-named with its UUID. Then run `list`.

#### Expected Behavior

Session names displayed readably.

#### Actual Behavior

Session `b45497cd-7587-4e65-a382-cbbab5fce04d` displayed as `b45497cd-7587-4e65-a382-cbbab…` — the truncation point cuts through the UUID.

#### Root Cause Analysis

The `list` output column for names has a fixed width of ~30 characters, and UUIDs are longer than that (~36 chars). The truncation is applied indiscriminately.

#### Code Pointer

`CLI `list` command output formatting — column width and truncation logic.`

#### AI Suggested Improvement

- Use a shorter derived name for auto-named sessions (e.g., first 8 chars of UUID: `b45497cd`)
- Or widen the Name column to accommodate full UUIDs
- Or truncate at a natural boundary (e.g., after the first segment of the UUID)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
add --verbose flag to show full UUIDs

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: chrome://version navigation succeeds silently instead of failing with clear error

`./b4w.ps1 goto "chrome://version/"` on an extension-attached session

#### Issue 2: Re-attach documentation incomplete — close step missing

1. `attach --extension` (creates default session)
2. Navigate to `chrome://version/` (session becomes stale)
3. `attach --extension` (attempt re-attach)

#### Issue 3: `goto chrome://version/` took ~90 seconds instead of expected ~10-15s

`./b4w.ps1 goto "chrome://version/"` on an extension-attached session.

#### Issue 4: Can't create second unnamed attach when default session is occupied

1. `open` (creates a Browser4 session in the default slot)
2. `attach --extension` (attempt to create another extension session)

#### Issue 5: `open` reconnects instead of creating new session when default is active

1. `open` (creates Browser4 session in default slot)
2. Wait for navigation/some activity
3. `open` again

#### Issue 6: Named session output minimal — no session UUID shown for `-s <name> open`

`./b4w.ps1 -s regular2 open`

#### Issue 7: Shell working directory resets to home after each command

Run `cd /d/workspace/Browser4/Browser4-4.12 && ./b4w.ps1 <cmd>` from Git Bash. Observe "Shell cwd was reset to C:\Users\pereg" after each command.

#### Issue 8: `list` display truncates UUID-based session names awkwardly

Have an extension session displaced from the default slot — it gets auto-named with its UUID. Then run `list`.

