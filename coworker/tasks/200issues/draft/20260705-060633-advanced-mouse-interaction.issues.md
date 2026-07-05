# Issues: advanced-mouse-interaction

> **Source:** `20260705-060633-advanced-mouse-interaction.full.md` | **Date:** 20260705-060633 | **Mode:** dev
> **Verified:** 2026-07-05 against cli/browser4-cli source at 4.11.x

## Scenario Background

### Task

All 13 steps of the task were completed, though several required significant workarounds. The key page interactions (hover, drag, double-click, dialog handling) all succeeded, and the interaction log at the bottom of the page reflects all actions taken.

**Final interaction log:**
- `[system] Page loaded. Ready for interaction testing.`
- `[14:02:38] All sections initialized.`
- `[14:03:42] DIALOG: Showing alert()`
- `[14:03:52] DIALOG: Alert dismissed`
- `[14:04:06] DIALOG: Showing confirm()`
- `[14:04:13] DIALOG: Confirm result = true`
- `[14:04:27] DIALOG: Showing prompt()`
- `[14:04:35] DIALOG: Prompt result = "Hello from browser4-cli"`

Dialog results area shows: `[prompt] User entered: "Hello from browser4-cli"`

---

### Execution Context

| Step | Command(s) | Result |
|------|-----------|--------|
| 1 | `goto "http://localhost:18080/generated/interactive-5.html"` | ✅ |
| 2 | `snapshot -i` → read snapshot YAML | ✅ 7 interactive sections discovered |
| 3 | `hover e21`, `hover e24` → `screenshot` | ✅ Hover succeeded; visual tooltip state transient (not captured in snapshot) |
| 4 | `hover e26` | ✅ Card details already in AX tree |
| 5 | `generate-locator e41`/`e38` → `drag "#priorityBacklog" "#priorityHigh"` | ✅ Reordering confirmed (Backlog moved to position 2) |
| 6 | `eval "document.querySelector('#dblclickZone').dispatchEvent(...)"` | ✅ Status → ACTIVATED, counter → 1 |
| 7 | `eval "document.querySelector('#dblclickResetZone').dispatchEvent(...)"` | ✅ Status → idle, counters → 0 |
| 8 | `generate-locator e56` → `#alertBtn...

(truncated — see full.md for complete trace)

---

## Issues Found (10 issues)

### Issue 1: Dialog-triggering clicks deadlock the server

**Severity:** Critical
**Category:** Product / Reliability

#### Reproduction

```
cd cli/browser4-cli && cargo run -- goto "http://localhost:18080/generated/interactive-5.html"
cd cli/browser4-cli && cargo run -- click e56    # Click "Show Alert" button
```
Then try any subsequent command — it times out with `HTTP request timed out`.

#### Expected Behavior

Click triggers the alert dialog, and the server remains responsive so `dialog-accept` can dismiss it.

#### Actual Behavior

The server becomes completely unresponsive. `dialog-accept` times out. `kill-all` is needed to recover (the server is unreachable and must be force-killed along with 7 orphaned browser processes).

#### Root Cause Analysis

The `click` command dispatches a click event, then attempts to capture a post-click accessibility tree snapshot. When the click triggers `alert()`/`confirm()`/`prompt()`, JavaScript execution is blocked synchronously. The snapshot requires CDP communication with the page, which can't proceed while JS is blocked. The server-side handler for `click` never returns, and no other commands (including `dialog-accept`) can be processed because the server thread is stuck waiting for the snapshot.

#### Code Pointer

The post-click snapshot logic in the click handler needs to either be skippable or the server should handle dialogs via CDP's `Page.javascriptDialogOpening` event before attempting the snapshot.

#### AI Suggested Improvement

- Add CDP `Page.javascriptDialogOpening` event listener that auto-accepts/dismisses dialogs during post-interaction snapshots, or defers the snapshot until after dialog resolution
- Add a `--no-snapshot` flag to `click` (and other interaction commands) to skip the post-command snapshot, preventing the deadlock when the user knows a dialog will appear
- Document this behavior clearly in SKILL.md with the workaround (use `eval` with `setTimeout` to trigger dialog buttons asynchronously)
- Consider adding a dedicated `dialog-trigger` or `click-dialog` command that is dialog-aware

#### Human Review

**VERIFIED — Server-side issue.** The CLI correctly sends the `browser_click` MCP call with the resolved element ref (`backend:N`). The deadlock occurs entirely within the server's handler: the server dispatches the click, JS blocks on `alert()`, and the server's own post-click snapshot (not the CLI-side `post_command_snapshot`) hangs waiting for CDP communication with the blocked page.

**CLI mitigation applied:** The `--no-snapshot` flag has been added to `click`, `dblclick`, `drag`, `hover`, `fill`, `type`, `press`, `select`, `check`, and `uncheck` commands (see Issue 4). Note: this only skips the CLI-side post-command snapshot — the server-side snapshot within `browser_click` is unaffected. The root fix must be in the Browser4 server (Java backend).

**Workaround:** Use `eval` with `setTimeout` to trigger dialog buttons asynchronously, then `dialog-accept` separately:
```
eval "setTimeout(() => document.querySelector('#alertBtn').click(), 500); 'scheduled'"
dialog-accept
```

---

### Issue 2: `drag` command rejects snapshot refs, requires CSS selectors

**Severity:** Critical
**Category:** Product / Documentation

#### Reproduction

```
cd cli/browser4-cli && cargo run -- drag e41 e38
```

#### Expected Behavior

Drag operates on the elements identified by snapshot refs e41 and e38 (as the help text `<startRef> <endRef>` implies).

#### Actual Behavior

`ERROR: browser_drag failed: SyntaxError: Failed to execute 'querySelector' on 'Document': 'backend:41' is not a valid selector.`

#### Root Cause Analysis

The `drag` command translates snapshot refs to internal `backend:N` selectors, but the implementation uses `querySelector` with this string directly instead of resolving the backend node ID through CDP. The parameter naming `startRef`/`endRef` in the help text misleads users into thinking snapshot refs (e5, e41) are accepted.

#### Code Pointer

The drag command implementation should resolve refs via CDP's `DOM.resolveNode` before executing, or the ref-to-selector bridge should be applied.

#### AI Suggested Improvement

- Fix the drag implementation to accept snapshot refs by resolving them via CDP backend node ID, matching the behavior of `click` and `hover`
- Alternatively, update the help text to say `<startSelector> <endSelector>` and clarify that CSS selectors are expected (not snapshot refs)
- Add an example to `drag --help` showing usage with CSS selectors

#### Human Review

**VERIFIED — Server-side issue.** The CLI correctly resolves snapshot refs before sending them to the server:
- `http.rs:normalize_refs()` (line 136-146) resolves `startRef` and `endRef` keys
- `state.rs:resolve_ref()` (line 616-624) converts `e41` → `backend:41`
- The normalized `{"startRef": "backend:41", "endRef": "backend:38"}` is sent to the server

The error occurs because the server's `browser_drag` implementation passes `backend:41` to `querySelector()` instead of resolving it via CDP's `DOM.resolveNode`. The `click` and `hover` server handlers do resolve backend node IDs correctly — the fix is to apply the same resolution logic in `browser_drag`.

**Workaround:** Use `generate-locator` to get CSS selectors first:
```
generate-locator e41   # → "#priorityBacklog"
generate-locator e38   # → "#priorityHigh"
drag "#priorityBacklog" "#priorityHigh"
```

---

### Issue 3: `dblclick` fails on non-focusable elements

**Severity:** Critical
**Category:** Product / Reliability

#### Reproduction

```
cd cli/browser4-cli && cargo run -- dblclick "#dblclickZone"
```

#### Expected Behavior

Double-click event is dispatched to the element, triggering any dblclick event listeners.

#### Actual Behavior

`Error: ERROR: browser_click failed: Element is not focusable`

#### Root Cause Analysis

The `dblclick` implementation requires the target element to be focusable (have `tabindex` or be an inherently focusable element like `<button>`/`<input>`). Many double-click targets in real applications are `<div>` elements without `tabindex` — this is a standard pattern for double-click interactions.

#### Code Pointer

The dblclick handler should focus the element programmatically first, or use CDP's `Input.dispatchMouseEvent` directly without requiring prior focus.

#### AI Suggested Improvement

- Add a `focus()` call on the element before dispatching the double-click, or use CDP to dispatch mouse events directly without the focusability requirement
- Document the focusability requirement in `dblclick --help` and SKILL.md if it's an intentional constraint
- Consider adding an `eval`-based fallback example in the docs: `eval "document.querySelector('#zone').dispatchEvent(new MouseEvent('dblclick', {bubbles: true}))"`

#### Human Review

**VERIFIED — Server-side issue.** The CLI correctly sends `{"ref": "backend:N", "doubleClick": true}` via the `browser_click` MCP tool (see `commands.rs` lines 823-833). The "Element is not focusable" error originates from the server's click handler, which requires the target to be focusable before dispatching mouse events. The fix must be server-side: either programmatically focus the element first, or use CDP's `Input.dispatchMouseEvent` directly without the focusability prerequisite.

**Workaround:** Use `eval` to dispatch the event directly:
```
eval "document.querySelector('#dblclickZone').dispatchEvent(new MouseEvent('dblclick', {bubbles: true}))"
```

---

### Issue 4: No way to suppress post-command auto-snapshot

**Severity:** High
**Category:** Product / UX

#### Reproduction

Any `click`, `hover`, `fill`, etc. command.

#### Expected Behavior

A flag like `--no-snapshot` or `--quick` to skip the automatic post-interaction snapshot for performance or to avoid dialog deadlocks.

#### Actual Behavior

Every interaction command always captures and saves a snapshot, with no opt-out mechanism.

#### Root Cause Analysis

The auto-snapshot behavior is hardcoded into interaction commands. While useful for most workflows, it's harmful when the interaction triggers a blocking dialog or when the user wants faster execution for known-good interactions.

#### AI Suggested Improvement

- Add a `--no-snapshot` flag to `click`, `hover`, `fill`, `type`, `press`, `select`, `check`, `dblclick`, and `drag` commands
- Document the flag in command-specific `--help` output

#### Human Review

**FIXED (CLI-side).** The `--no-snapshot` flag has been added to all main interaction commands:

| Command | File:Line |
|---------|-----------|
| `click` | `commands.rs:801` |
| `dblclick` | `commands.rs:824` |
| `drag` | `commands.rs:849` |
| `hover` | `commands.rs:893` |
| `fill` | `commands.rs:870` |
| `type` | `commands.rs:650` |
| `press` | `commands.rs:624` |
| `select` | `commands.rs:908` |
| `check` | `commands.rs:941` |
| `uncheck` | `commands.rs:953` |

The dispatch logic in `main.rs:10739` checks `parsed.get("no-snapshot")` and skips the CLI-side `post_command_snapshot` when the flag is set.

**Caveat:** This only skips the CLI-side post-command snapshot. The server-side post-click snapshot (within `browser_click`) is unaffected. For dialog-related deadlocks (Issue 1), this flag alone is insufficient — the server-side handler must also be fixed.

**Usage:**
```
click e56 --no-snapshot          # Click without auto-snapshot
hover e21 --no-snapshot          # Hover without auto-snapshot
drag e41 e38 --no-snapshot       # Drag without auto-snapshot
```

---

### Issue 5: Runtime bundle build fails due to Maven version incompatibility

**Severity:** High
**Category:** Product / Installation

#### Reproduction

Run any `browser4-cli` command from source (`cargo run`) on a system with Maven < 3.6.3.

#### Expected Behavior

The runtime bundle builds successfully or cleanly falls back to an installed runtime.

#### Actual Behavior

`BUILD FAILURE: The plugin org.apache.maven.plugins:maven-dependency-plugin:3.9.0 requires Maven version 3.6.3`. Falls back to installed runtime with a loud error message that may alarm users.

#### Root Cause Analysis

The build script requires Maven >= 3.6.3. The fallback works but the error output is noisy and confusing.

#### AI Suggested Improvement

- Check Maven version at the start of the build script and provide a clear, calm message ("Maven 3.6.3+ required for local build; using installed runtime instead")
- Suppress the full Maven stack trace in the fallback path
- Document the Maven version requirement in README.md and SKILL.md

#### Human Review

**CONFIRMED — Build-system issue.** This is outside the CLI Rust source. The build/install scripts (in the `cli/browser4-cli/` directory or build system) need updating. The fallback to an installed runtime is the correct behavior; the primary fix should be a cleaner Maven version check with a calmer error message. Not actionable from the Rust CLI source alone — needs build-script maintainer attention.

---

### Issue 6: Auto-snapshot captures transient hover state unreliably

**Severity:** High
**Category:** Documentation / UX

#### Reproduction

```
cargo run -- hover e21   # Hover tooltip term
cargo run -- snapshot -i --stdout   # Check snapshot - tooltip not visible
```

#### Expected Behavior

The post-hover snapshot captures the visual tooltip content that appeared during hover.

#### Actual Behavior

Tooltips and hover-expanded content that appear via CSS `:hover` or JS `mouseenter` disappear when the mouse moves (which happens during the snapshot capture). The accessibility tree shows the same content regardless of hover state. There's no built-in way to verify hover-triggered visual changes.

#### Root Cause Analysis

CSS `:hover` states and JS-triggered tooltips are transient. The snapshot captures the accessibility tree after the hover command completes, but by that time the hover effect may have already ended. Additionally, pure CSS visual changes (like `:hover` styles) don't modify the accessibility tree.

#### AI Suggested Improvement

- Document in SKILL.md that hover state verification requires screenshots (not snapshots) and should be captured during or immediately after hover
- Consider adding a `hover-and-screenshot` compound command or a `--screenshot` flag to `hover` that captures a screenshot while the hover state is active
- Add a `hover --hold` flag that keeps the mouse in position for a specified duration to allow inspection

#### Human Review

**CONFIRMED — Inherent behavior, documentation/feature request.** The CLI's `hover` command sends `browser_hover` to the server. The server moves the mouse and returns. The CSS `:hover` state is transient — once the mouse moves away (which may happen during snapshot capture), the tooltip disappears. Additionally, CSS visual changes don't modify the accessibility tree, so even a perfectly timed snapshot wouldn't capture them.

The current workaround (`screenshot` after hover) is the correct approach for verifying visual hover effects. The suggested `hover --screenshot` compound flag would be a nice UX improvement but is not yet implemented. No CLI code change is strictly required — this is primarily a documentation and feature-request item.

---

### Issue 7: Inconsistent ref vs CSS selector support across commands

**Severity:** Medium
**Category:** UX / Documentation

#### Reproduction

Compare behavior:
- `click e56` works ✅
- `hover e21` works ✅
- `drag e41 e38` fails ❌
- `dblclick e45` fails ❌

#### Expected Behavior

All element-referencing commands consistently accept snapshot refs (e.g., `e5`, `e21`).

#### Actual Behavior

`click` and `hover` work with refs. `drag` and `dblclick` silently fail with confusing errors when given refs.

#### Root Cause Analysis

Different commands use different element resolution paths. Some resolve backend node IDs through CDP, while others pass the identifier to `querySelector` directly.

#### AI Suggested Improvement

- Normalize element resolution so all commands accepting a `<ref>` parameter support both snapshot refs and CSS selectors
- Add a unified validation step that detects backend node IDs and resolves them before passing to the command implementation

#### Human Review

**CONFIRMED — Server-side inconsistency.** The CLI-side ref resolution is already unified: `http.rs:normalize_refs()` handles `ref`, `selector`, `startRef`, and `endRef` keys, converting `eN` to `backend:N` via `state.rs:resolve_ref()`. The inconsistency is in the server's MCP tool implementations:

- `browser_click` and `browser_hover`: resolve `backend:N` via CDP's `DOM.resolveNode` ✅
- `browser_drag`: passes `backend:N` to `querySelector()` ❌
- `browser_click` with `doubleClick: true`: same as click, but focusability check adds a second failure mode ❌

The fix needed is server-side: apply `DOM.resolveNode` resolution in `browser_drag` and relax the focusability requirement in the double-click path. The CLI is already doing the right thing.

---

### Issue 8: `get` command output has excessive indentation/whitespace

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- get text "#alertBtn"
```

#### Expected Behavior

Clean output: `🔔 Show Alert`

#### Actual Behavior

Output is indented with significant leading whitespace.

#### Root Cause Analysis

The output formatting adds unnecessary indentation when rendering the text value.

#### AI Suggested Improvement

- Trim leading whitespace from `get text` output, especially when `--json` is not used

#### Human Review

**PARTIALLY CONFIRMED — Likely server-side formatting.** The CLI's `handle_get` function (main.rs:3015-3067) prints the server response verbatim via `cli_println!("{}", result)` without adding any indentation. Any extra whitespace in the output originates from the server's `select_first_text_or_null` MCP handler. If the server returns text with leading whitespace (e.g., from the DOM text content), the CLI will display it as-is.

A `.trim()` could be applied in `handle_get` for the non-JSON output path, but this would also strip intentional whitespace. The safer fix is server-side: ensure `select_first_text_or_null` trims the text node content before returning it.

---

### Issue 9: No `--help` flag documented in `goto` command for session discovery

**Severity:** Low
**Category:** Discoverability

#### Reproduction

A new user starting from scratch may not know how to discover the `--help` flag or that `goto` auto-starts the server.

#### Expected Behavior

Clear first-run guidance in the main `help` output.

#### Actual Behavior

The main `help` is comprehensive but doesn't highlight the "getting started" path. The `goto` auto-open behavior is mentioned briefly. The SKILL.md core loop is good but assumes the user reads it.

#### AI Suggested Improvement

- Add a "Getting Started" section to the main `help` output showing the first three commands a new user should run
- Add a `quickstart` command that prints a guided walkthrough

#### Human Review

**CONFIRMED — Minor UX enhancement.** All commands including `goto` support `--help` (handled by `print_help()` at main.rs:10767). The main `help` output is comprehensive but could benefit from a "Getting Started" section. The SKILL.md file already documents the core loop (navigate → snapshot → interact → re-snapshot). This is a documentation enhancement, not a code bug. Low priority.

---

### Issue 10: Snapshot output defaults to file path instead of inline display

**Severity:** Low
**Category:** UX / Discoverability

#### Reproduction

Run `snapshot -i` without `--stdout`.

#### Expected Behavior

Snapshot content is displayed inline in the terminal, like most CLI tools.

#### Actual Behavior

Only the file path is shown. The user must open the file manually or know to add `--stdout`. The tip mentions `--stdout` but after the fact: "💡 Tip: Add --stdout to print element refs inline instead of opening the snapshot file."

#### Root Cause Analysis

Snapshot files can be large (the SKILL.md warns they can exceed 256KB). But for smaller interactive snapshots, inline display would be more user-friendly.

#### AI Suggested Improvement

- Auto-detect snapshot size: if < 5KB, display inline by default; for larger snapshots, show the file path
- Add a config setting for default snapshot display mode

#### Human Review

**CONFIRMED — Deliberate design choice with existing escape hatch.** The CLI's `handle_snapshot` function (main.rs:2500-2759) intentionally saves snapshots to files by default because they can be very large (>256KB). The `--stdout` flag is available and documented via an on-by-default tip. The code at lines 2699-2707 shows the tip is already shown when `-i` is used without `--stdout`. The auto-size-detection suggestion is a reasonable feature request but not a bug. Current behavior is acceptable.

---

## Summary of Verification

| # | Issue | Severity | Status | Action Taken / Needed |
|---|-------|----------|--------|-----------------------|
| 1 | Dialog deadlock | Critical | Verified (server-side) | Root fix needed in Browser4 server. CLI added `--no-snapshot` as partial mitigation. |
| 2 | drag rejects refs | Critical | Verified (server-side) | Server's `browser_drag` needs `DOM.resolveNode` support. CLI ref resolution is correct. |
| 3 | dblclick focusability | Critical | Verified (server-side) | Server's click handler needs to handle non-focusable elements for dblclick. |
| 4 | No --no-snapshot flag | High | **FIXED** | Added `--no-snapshot` to 10 interaction commands + dispatch logic check. |
| 5 | Maven incompatibility | High | Verified (build-system) | Outside CLI scope. Build scripts need Maven version check. |
| 6 | Hover state capture | High | Verified (inherent) | Documentation/feature request. Use `screenshot` after hover as workaround. |
| 7 | Inconsistent ref support | Medium | Verified (server-side) | Duplicate of Issues 2+3. Server-side fix needed. |
| 8 | get text indentation | Low | Partially confirmed | Likely server-side. CLI prints response verbatim. |
| 9 | goto help discoverability | Low | Verified (UX) | Documentation enhancement. All commands have `--help`. |
| 10 | Snapshot inline display | Low | Verified (design) | `--stdout` flag exists. Tip shown to users. Acceptable as-is. |

**CLI changes made:**
- `commands.rs`: Added `--no-snapshot` option to click, dblclick, drag, hover, fill, type, press, select, check, uncheck
- `main.rs`: Modified post-command snapshot check to respect `--no-snapshot` flag from parsed args

**Server-side fixes needed (3 issues):**
1. `browser_click`/`browser_drag`: Handle `Page.javascriptDialogOpening` to prevent dialog deadlocks
2. `browser_drag`: Resolve `backend:N` refs via CDP's `DOM.resolveNode` instead of `querySelector`
3. `browser_click` (dblclick path): Remove or work around the focusability requirement for non-focusable elements
