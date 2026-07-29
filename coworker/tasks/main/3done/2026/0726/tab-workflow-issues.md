# Issues: tab-workflow

> **Source:** `20260726-172048-tab-workflow.full.md` | **Date:** 20260726-172048 | **Mode:** dev

## Scenario Background

### Task

The tab lifecycle was exercised across regular, extension, and mixed sessions. **All core functionality works** — `tab-list`, `tab-new`, `tab-select`, and `tab-close` operate correctly across session types with proper session isolation. However, multiple usability and reliability issues were discovered.

Key workarounds required:
- Used `--json` **before** `tab-list` (not after per task instructions) to get JSON output
- Used `-s <session-id>` instead of a nonexistent `session`/`switch`/`use` command for multi-session targeting
- Used `snapshot` (no flags) instead of `snapshot -i` because `-i` is eaten by PowerShell

---

### Execution Context

**Commands used (major):**

| Step | Command | Notes |
|------|---------|-------|
| Prep | `./b4w.ps1 help` | Read help; confirmed all commands |
| A.1 | `kill-all`, `open`, `goto example.com` | Session created, navigated OK |
| A.2 | `tab-list` | Human-readable table: Index, GUID, Title, URL |
| A.3 | `--json tab-list` | JSON output with `tabs` array |
| A.4 | `tab-new https://httpbin.org/links/10` | GUID returned, auto-switched |
| A.6 | `tab-new` (no URL) | Created `about:blank` tab |
| A.7 | `tab-select 0`, `goto example.com` | Switch by index works |
| A.8 | `tab-select 2`, `goto httpbin.org/get` | Navigation updates tab URL |
| A.9 | `tab-close 1` | Index compacted after close |
| A.10 | `tab-close` (no args) | Closed current tab |
| A.11 | `tab-close` on last tab | Chrome auto-created replacement |
| A.13 | `tab-close --guid <guid>` | GUID-based close works |
| A.14 | `tab-select --guid <guid>` | GUID-based select works |
| A.15 | `tab-select 99` | Clear error: "out of range" |
| A.16 | `tab-close --guid bogus` | Clear error: "not found" |
| A.17 | Rapid `tab-new` ×2 + `tab-list` | Consistent, sequential indices |
| B.1 | `attach --extension` | Extension connected in ~1s |
| B.2-B.8 | Full tab lifecycle via extension | Works with errors noted |
| C.1-C.10 | Mixed sessions | Session isolation confirmed |

**Important decisions:**
- Used `-s <session-id>` for cross-session targeting since no `session`/`switch`/`use` command exists
- Used `--json` as a global flag (before command) per documentation, not as a subcommand flag
- Avoided `-i`/`-v` flags through b4w.ps1 due to PowerShell interference

---

---

## Issues Found (10 issues)

### Issue 1: PowerShell wrapper consumes short flags (-i, -v, etc.)

**Severity:** High | **Category:** Reliability

#### Reproduction

```bash
cd "D:/workspace/Browser4/Browser4-4.12" && ./b4w.ps1 snapshot -i
cd "D:/workspace/Browser4/Browser4-4.12" && ./b4w.ps1 snapshot -v 0
```

#### Expected Behavior

Flags `-i` and `-v` are passed through to the `browser4-cli` binary as documented.

#### Actual Behavior

- `-i` fails with: `Parameter cannot be processed because the parameter name 'i' is ambiguous. Possible matches include: -InformationAction -InformationVariable.`
- `-v 0` fails with: `Unknown command: 'snapshot-0'` (the `-v` is consumed by PowerShell as `-Verbose` and `0` is mangled)

#### Root Cause Analysis

The b4w.ps1 script has `[CmdletBinding()]` implicitly through its `[Parameter()]` attributes, which adds PowerShell common parameters (`-Verbose`, `-InformationAction`, etc.) to the script's parameter set. PowerShell's parameter binder matches `-v` to `-Verbose` and `-i` to `-InformationAction` *before* the `$RemainingArgs` variable is populated. The `$SafeArgs` quoting approach (lines 136-139) runs too late — it only protects arguments after they reach `$RemainingArgs`, but the error occurs during the initial param binding phase.

#### Code Pointer

`b4w.ps1:130-139` — the `$SafeArgs` quoting fix and the param block needing `[CmdletBinding(DefaultParameterSetName='...')]` or explicit `--%` passthrough support.

#### AI Suggested Improvement

- Remove implicit `[CmdletBinding()]` by adding `[CmdletBinding(DisableNameChecking)]` or use a parameter attribute that doesn't auto-add common parameters
- Alternatively, wrap the PowerShell script in a batch/bash entry point that correctly passes `--%` before all arguments
- Add `--%` (PowerShell stop-parsing token) handling in the script's parameter block so users can invoke `./b4w.ps1 --% snapshot -i`
- Document known-affected flags in the help output and SKILL.md with workarounds

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
wrap the PowerShell script in a batch/bash entry point that correctly passes `--%` before all arguments

---

### Issue 2: No command to change the DEFAULT session

**Severity:** Medium | **Category:** Discoverability

#### Reproduction

1. Create two sessions (e.g., `attach --extension` then `open`).
2. Run `./b4w.ps1 list` to see both sessions.
3. Attempt `./b4w.ps1 session <id>`, `./b4w.ps1 switch <id>`, or `./b4w.ps1 use <id>`.

#### Expected Behavior

A documented command to change which session is marked `(default)`, so users can switch tab command targeting without prefixing every command with `-s <id>`.

#### Actual Behavior

All three commands (`session`, `switch`, `use`) return `Unknown command`. The only way to target a non-default session is the global `-s <id>` prefix.

#### Root Cause Analysis

The default session is implicitly set to the most recently created/used session. There's no explicit command to reassign the default. The `-s` global flag works as a workaround but requires repeating on every command.

#### Code Pointer

Unknown — the default-session logic likely lives in the session manager. A new CLI subcommand needs to be registered.

#### AI Suggested Improvement

- Add a `session <id>` command (or `switch <id>` / `use <id>`) that changes the default session without creating a new browser window
- Document this command prominently in the SKILL.md under §Tab Management and §Sessions
- Consider also displaying a hint after `list` output: "Run `browser4-cli session <id>` to change the default session"
- The `list` command could show a tip: "💡 Use `-s <id>` to target a session, or `session <id>` to change the default"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `--json` flag position is inconsistent with task/documentation expectations

**Severity:** Medium | **Category:** Documentation / UX

#### Reproduction

```bash
./b4w.ps1 tab-list --json     # returns human-readable table, NOT JSON
./b4w.ps1 --json tab-list     # returns JSON (correct)
```

#### Expected Behavior

Either both flag positions work, or the documentation clearly states that `--json` MUST precede the command name.

#### Actual Behavior

`tab-list --json` silently ignores `--json` and outputs the human-readable table. The SKILL.md correctly documents `browser4-cli --json tab-list` but the task's expected usage (`tab-list --json`) doesn't match. A user reading the task would try the wrong order first.

#### Root Cause Analysis

`--json` is a global flag that must appear before the subcommand. When it appears after, it's either ignored (tab-list has no `--json` sub-flag) or parsed differently. No error is emitted to warn the user.

#### Code Pointer

CLI argument parser — could warn on unrecognized flags or accept `--json` in both positions.

#### AI Suggested Improvement

- Accept `--json` after the subcommand name for commands that have JSON output modes (or at minimum warn when it's ignored)
- Add an explicit `--json` flag to `tab-list` itself so both `--json tab-list` and `tab-list --json` work
- Update the SKILL.md examples to show both invocation styles
- Emit a warning on stderr when unknown flags are provided after a subcommand

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `tab-close` produces unhelpful error on extension sessions

**Severity:** Medium | **Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 attach --extension
./b4w.ps1 goto https://example.com
./b4w.ps1 tab-close 1        # (or tab-close without args)
```

#### Expected Behavior

The tab closes successfully with a clear status message, or a meaningful error explains why it failed.

#### Actual Behavior

```
ERROR: browser_tabs failed: closeTab
help: Close a tab by zero-based index or GUID, or the current tab when omitted
browser.closeTab(Arg(name=index, type=Int, defaultValue=null), Arg(name=tabId, type=String, defaultValue=null))
```

The tab IS actually closed (confirmed by subsequent `tab-list`), but the error message suggests failure. The error text "closeTab" provides no diagnostic information.

#### Root Cause Analysis

The extension backend likely returns an error response after successfully closing the tab (perhaps a race condition in the CDP event handling, or the `chrome.tabs.remove` callback fires with an error for a reason unrelated to the actual tab removal). The CLI correctly reports the backend error but the error message is unactionable.

#### Code Pointer

Extension backend tab-close handler — needs better error handling and more descriptive error messages.

#### AI Suggested Improvement

- Improve the error message to include the actual Chrome extension error (e.g., "Tab may have been closed, but Chrome reported: ...")
- If the tab was actually closed (verify by listing tabs), suppress the error entirely or change it to a warning
- Add a specific error code/message for extension tab operations to distinguish from regular session errors
- Consider adding retry logic for extension tab operations

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: GUID format inconsistency between session types and over time

**Severity:** Low | **Category:** UX

#### Reproduction

1. `attach --extension` → `tab-list` shows GUIDs like `chrome:217100514`
2. `tab-new` reports GUID as `217100514` (no `chrome:` prefix)
3. After session goes stale and tabs are recreated, GUIDs become 32-char hex (e.g., `6671E44AE12E4D34208CCEE29008D89D`) instead of `chrome:<number>`

#### Expected Behavior

Consistent GUID format across all commands and session states. The `chrome:` prefix should appear consistently (or never).

#### Actual Behavior

- `tab-list` human-readable table shows `chrome:217100514`
- `tab-new` JSON output shows `{"guid":"217100514"}` (no prefix)
- `--json tab-list` shows just `217100514` (sometimes) or hex GUIDs (after reconnection)
- Stale/reconnected sessions switch to hex GUID format entirely

#### Root Cause Analysis

The extension session uses Chrome's tab IDs as GUIDs, prefixed with `chrome:` for display. `tab-new` reports the raw Chrome tab ID. When the session goes stale and browser state is lost, the backend falls back to generating its own hex GUIDs for new tabs. The display formatting differs between human-readable and JSON output.

#### Code Pointer

Tab GUID formatting across `tab-list` human-readable renderer, `tab-list` JSON renderer, and `tab-new` output.

#### AI Suggested Improvement

- Use the same GUID representation in `tab-new` output as in `tab-list` (always include `chrome:` prefix for extension tabs)
- Document the GUID format difference between session types in SKILL.md more prominently
- Consider using consistent GUIDs even after session reconnection if possible

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Last-tab close message is misleading about blank tab

**Severity:** Low | **Category:** UX

#### Reproduction

```bash
./b4w.ps1 open
./b4w.ps1 goto https://example.com
./b4w.ps1 tab-close  # (close the only tab)
```

#### Expected Behavior

Message says "a new blank tab was created" and the tab list shows `about:blank`.

#### Actual Behavior

Message says "Note: Chrome requires at least one open tab — a new blank tab was created." But the tab list shows the same URL (`https://example.com/`) with the same GUID, not `about:blank`.

#### Root Cause Analysis

When Chrome auto-creates a replacement tab after closing the last one, it clones the state of the closed tab (including URL). The "blank tab" message is a reasonable approximation for most cases, but it's inaccurate when the closed tab had a specific URL.

#### Code Pointer

The "new blank tab was created" message — should be more accurate about what Chrome actually created.

#### AI Suggested Improvement

- Change the message to: "Chrome requires at least one open tab — a replacement tab was created."
- Or check the actual URL of the replacement tab and report it accurately
- For regular sessions, actually verify the URL matches "about:blank" before printing the message

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Extension session becomes "Stale" after closing all tabs

**Severity:** Low | **Category:** Reliability

#### Reproduction

1. `attach --extension`
2. Close all tabs via `tab-close`
3. Run `list` — session shows "Stale" with "Next open: Refresh"

#### Expected Behavior

Session remains "Active" as long as the Chrome browser with the extension is still running.

#### Actual Behavior

Session status becomes "Stale" after all tabs are closed. This suggests the backend considers the session dead when there are no tabs, even though the Chrome browser is still running and the extension is still connected.

#### Root Cause Analysis

The session liveness check may depend on having at least one open tab or may interpret the tab-closed state as a disconnected browser.

#### Code Pointer

Session health check / liveness detection logic on the backend.

#### AI Suggested Improvement

- Session liveness should be based on the actual CDP/WebSocket connection, not tab count
- If the extension WebSocket is still connected, the session should remain "Active"
- Document this behavior if intentional

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: `tab-list` titles are empty for pages without `<title>` elements

**Severity:** Low | **Category:** UX

#### Reproduction

```bash
./b4w.ps1 goto https://httpbin.org/get
./b4w.ps1 tab-list
```

#### Expected Behavior

Title column shows something descriptive (e.g., URL, or "No title").

#### Actual Behavior

Title column is empty (blank) for pages like `httpbin.org/get` that don't set a `<title>` element. The blank cell can be confusing.

#### Root Cause Analysis

The title is extracted from the page's `<title>` tag. When it's absent, the field is empty string.

#### Code Pointer

Tab title extraction — could fall back to URL or hostname when `<title>` is empty.

#### AI Suggested Improvement

- Fall back to displaying the URL path or hostname when the page has no title
- Use a placeholder like "(no title)" instead of an empty cell for consistency

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: Error exit codes are inconsistent — errors exit with code 0

**Severity:** Medium | **Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 tab-select 99; echo "Exit code: $?"
./b4w.ps1 tab-close --guid bogus; echo "Exit code: $?"
```

#### Expected Behavior

Invalid operations should exit with non-zero exit codes so scripts can detect failures.

#### Actual Behavior

Both invalid operations exit with code 0 despite printing `ERROR:` messages to stderr. This means shell scripts using `set -e` or checking `$?` won't detect these failures.

#### Root Cause Analysis

The CLI prints error messages but doesn't set a non-zero exit code for user-input errors (as opposed to internal/system errors). The error is reported on stderr but the process exits successfully.

#### Code Pointer

CLI main exit code logic — should distinguish between successful operations and user-facing errors.

#### AI Suggested Improvement

- Exit with code 1 (or other non-zero) when the operation fails due to invalid input
- Reserve exit code 0 only for fully successful operations
- This is critical for scripting — users rely on `$?` to detect failures

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: SKILL.md `--stdout` flag is missing from `snapshot` help examples

**Severity:** Low | **Category:** Documentation

#### Reproduction

The SKILL.md §1 Core Loop shows `snapshot -v 0` and later mentions `--stdout` as an option, but the main copy-paste template uses `snapshot -v 0` without `--stdout`. A first-time user following the default template would open a snapshot file rather than seeing the output inline.

#### Expected Behavior

The most-common-use-case template should use `--stdout` by default since AI agents need inline output.

#### Actual Behavior

The template omits `--stdout`. The user must discover this flag from a separate paragraph.

#### Root Cause Analysis

The template prioritizes the file-writing default behavior. For AI agent use, `--stdout` is almost always needed.

#### Code Pointer

`skills/browser4-cli/SKILL.md:30-37` — the copy-paste template section.

#### AI Suggested Improvement

- Add `--stdout` to the main copy-paste template: `snapshot -v 0 --stdout`
- Or create a separate "AI Agent Quick Start" template with `--stdout`
- The `--stdout` tip on line 39-41 is easy to miss — move it closer to the template

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---
