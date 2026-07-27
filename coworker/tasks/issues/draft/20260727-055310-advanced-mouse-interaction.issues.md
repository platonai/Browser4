# Issues: advanced-mouse-interaction

> **Source:** `20260727-055310-advanced-mouse-interaction.full.md` | **Date:** 20260727-055310 | **Mode:** dev

## Scenario Background

### Task

The task was completed with workarounds for several issues. Here's a summary of all 13 steps:

| Step | Action | Result |
|------|--------|--------|
| 1 | `goto` interactive-5.html | Page loaded successfully |
| 2 | `snapshot -i --stdout` | Interactive elements discovered (had to use CLI binary directly due to PS flag interception) |
| 3 | `hover e22`, `hover e25` | Tooltip content visible in snapshot (embedded in element names) |
| 4 | `hover e27` | Product card expanded, "Features: Noise cancelling…" text appeared |
| 5 | `drag "#priorityHigh" "#priorityBacklog"` | High Priority moved to bottom (required CSS selectors, not refs) |
| 6 | `dblclick "#dblclickZone"` | Status → ACTIVATED ✅ (required `eval` to add `tabindex=0` first) |
| 7 | `dblclick "#dblclickResetZone"` | Counters reset to 0 (required `eval` to add `tabindex=0` first) |
| 8 | `generate-locator e57` | Produced `#alertBtn` |
| 9 | `get text "#alertBtn"` | Retrieved "🔔 Show Alert" |
| 10 | `click "#alertBtn"` → `dialog-accept` | `[alert] User dismissed the alert dialog.` |
| 11 | `click "#confirmBtn"` → `dialog-accept` | `[confirm] User clicked OK / accepted.` |
| 12 | `click "#promptBtn"` → `dialog-accept "Hello from Browser4"` | `[prompt] User entered: "Hello from Browser4"` |
| 13 | CDP screenshot | final-screenshot.png saved (135KB, 2525×1479 PNG) |

Complete interaction log from the page:
```
[13:33:08] All sections initialized.
[13:36:50] DRAG START: 🔴 High Priority — Fix login bug
[13:36:50] DRAG END: 🔴 High Priority — Fix login bug → new position
[13:38:14] DBLCLICK: Zone activated (1 total double-clicks)
[13:38:29] DBLCLICK: Zone activated (2 total double-clicks)
[13:38:56] DBLCLICK: Counters reset
[13:39:25] DIALOG: Showing alert()
[13:39:59] DIALOG: Alert dismissed
[13:42:09] DIALOG: Showing confirm()
[13:42:43] DIALOG: Confirm result = true
[13:44:05] DIALOG: Showing prompt()
[13:44:41] DIALOG: Prompt result = "Hello from Browser4"
```

### Execution Context

**Key Commands:**

1. `goto "http://localhost:18080/generated/interactive-5.html"`
2. `snapshot -i --stdout` (via direct binary — b4w.ps1 couldn't handle `-i`)
3. `hover e22`, then `hover e25`
4. `snapshot grep "tooltip"`
5. `hover e27`
6. `snapshot grep "Wireless\|Features\|Noise"`
7. `drag e39 e42` → FAILED with selector error
8. `generate-locator e39` → `#priorityHigh`, `generate-locator e42` → `#priorityBacklog`
9. `drag "#priorityHigh" "#priorityBacklog"` → SUCCESS
10. `dblclick e45` → FAILED "Element is not focusable"
11. `eval "…setAttribute('tabindex','0')"` → workaround
12. `dblclick "#dblclickZone"` → SUCCESS
13. `dblclick "#dblclickResetZone"` → SUCCESS (after same tabindex workaround)
14. `generate-locator e57` → `#alertBtn`
15. `get text "#alertBtn"` → "🔔 Show Alert"
16. `click "#alertBtn"` → timed out (dialog blocking)
17. `dialog-accept` → dismissed alert
18. `click "#confirmBtn"` + `dialog-accept` → confirm handled
19. `click "#promptBtn"` + `dialog-accept "Hello from Browser4"` → prompt handled
20. `cdp "Page.captureScreenshot" --json '{"format":"png"}'` → screenshot saved

**Important decisions:**
- Used CLI binary directly for `snapshot -i` because `b4w.ps1` can't pass `-i` and `--` passthrough fails from Git Bash
- Used CSS selectors (from `generate-locator`) instead of refs for `drag` and `dblclick` commands
- Added `tabindex=0` via `eval` to make `<div>` elements focusable for `dblclick`
- Used CDP directly for screenshot since `screenshot` command times out

**Workarounds required:** 4

---

## Issues Found (8 issues)

### Issue 1: PowerShell wrapper intercepts `-i`/`-v` flags; `--` passthrough broken from Git Bash

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 -- snapshot -i --stdout
```

#### Expected Behavior

The `-i` flag passes through to the CLI binary as an argument.

#### Actual Behavior

- `./b4w.ps1 snapshot -i --stdout` → "Parameter cannot be processed because the parameter name 'i' is ambiguous"
- `./b4w.ps1 -- snapshot -i --stdout` → "Parameter cannot be processed because the parameter name '' is ambiguous"

#### Root Cause Analysis

PowerShell's `param()` block runs before the `SafeArgs` quoting logic in the script body. When `b4w.ps1` receives `-i`, PowerShell's parameter binder tries to match it against common parameters (`-InformationAction`, `-InformationVariable`) and fails. The `--` stop-parsing token is consumed or mishandled when invoked from Git Bash (the bash `--` handling and PowerShell `--` handling may conflict).

#### Code Pointer

``b4w.ps1:147-150` — SafeArgs logic runs after param binding. The fix needs to happen earlier, possibly by restructuring the param block to use only `ValueFromRemainingArguments` without switch params.`

#### AI Suggested Improvement

- Restructure `b4w.ps1` to accept all arguments via a single `[string[]]$Args` parameter with `ValueFromRemainingArguments`, eliminating the `[switch]$Rebuild` param binding issue
- Or: add explicit `-InformationAction` and `-Verbose` parameters to silence ambiguity warnings, then ignore them
- Add a clear error message and guidance when common flag interception is detected (e.g., "Flag '-i' was intercepted by PowerShell. Use `b4w.bat` or `b4w.sh` instead.")
- Fix the `b4w.sh` wrapper so it works reliably from Git Bash (currently fails with "not recognized as a cmdlet" error)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: `dblclick` fails on non-focusable elements (common in real-world pages)

**Severity:** High
**Category:** Product

#### Reproduction

```bash
b4w dblclick e45   # where e45 is a <div> dblclick target
```

#### Expected Behavior

Double-click dispatched to the element, triggering its `dblclick` event listener.

#### Actual Behavior

`ERROR: browser_click failed: Element is not focusable Tip: Use 'click <ref>' first to focus the element`

#### Root Cause Analysis

The `dblclick` implementation proxies through `browser_click` which requires the target element to be focusable. Many real-world double-click targets are generic `<div>` elements without `tabindex`. The error message suggests clicking first to focus, but clicking a non-focusable `<div>` doesn't make it focusable either — the suggestion is misleading.

#### Code Pointer

``browser4-core/browser4-browser/` — `PulsarWebDriver.kt` — the dblclick implementation. Likely uses CDP's `Input.dispatchMouseEvent` after focusing, but focus check precedes mouse dispatch.`

#### AI Suggested Improvement

- Skip the focusability check for `dblclick` — dispatch the mouse events directly without requiring focus first
- Or: automatically add `tabindex=-1` to the target element before double-clicking (as a transparent workaround)
- Fix the error message: "Use 'click <ref>' first to focus the element" doesn't help since clicking a non-focusable div doesn't make it focusable. Suggest `eval` or CSS selector workarounds
- Document the focusability requirement in the help text for `dblclick` and provide workarounds

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `drag` command fails with snapshot refs; requires CSS selectors

**Severity:** High
**Category:** Product

#### Reproduction

```bash
b4w drag e39 e42   # using refs from snapshot
```

#### Expected Behavior

Drag-and-drop between two elements identified by their snapshot refs.

#### Actual Behavior

`ERROR: browser_drag failed: SyntaxError: Failed to execute 'querySelector' on 'Document': 'backend:39' is not a valid selector`

#### Root Cause Analysis

The `drag` command passes refs directly to `querySelector` without resolving them to CSS selectors first. The ref `e39` maps to CDP backend node ID 39, and the backend is constructing `backend:39` as the selector string, which is not valid CSS syntax. The ref-to-CSS resolution step that other commands perform is missing in the `drag` code path.

#### Code Pointer

``browser4-core/browser4-browser/` — `PulsarWebDriver.kt` drag implementation. The `drag` method receives refs but treats them as CSS selectors instead of resolving CDP backend node IDs.`

#### AI Suggested Improvement

- Resolve snapshot refs to CSS selectors (or DOM node references) before passing to the drag CDP sequence
- Or: internally call `generate-locator` equivalent logic and use the generated CSS selector for the drag operation
- Update the `drag` help text and SKILL.md to clarify that CSS selectors are required, not just refs (or fix to accept refs)
- Add a specific error message when a ref is detected (e.g., "Refs are not supported for drag. Use a CSS selector or generate-locator first.")

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: `screenshot` command times out consistently on moderately complex pages

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
b4w goto "http://localhost:18080/generated/interactive-5.html"
b4w screenshot
b4w screenshot --full-page
b4w screenshot "#dialogResult"
```

#### Expected Behavior

Screenshot captured and saved within the default 30s timeout.

#### Actual Behavior

All screenshot invocations fail with: `HTTP request timed out [tool=browser_take_screenshot, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s]`. The CDP workaround (`cdp "Page.captureScreenshot" --json '{"format":"png"}'`) succeeds.

#### Root Cause Analysis

The `browser_take_screenshot` tool has a 30-second hard timeout that is insufficient for complex pages with many viewports. The CDP route uses a different code path that completes successfully. May also be related to the page being in a post-dialog state or having accumulated interaction state.

#### Code Pointer

`Backend `MCPToolController.kt` or the screenshot tool implementation — the tool-level timeout may need to be increased or made configurable.`

#### AI Suggested Improvement

- Increase the default timeout for `screenshot` to 60s or make it configurable
- Investigate why the CDP path works but the tool path doesn't — there may be unnecessary processing in the tool path
- For `--full-page`, consider compositing viewport screenshots instead of rendering the entire page at once
- Add a `--quick` flag that uses CDP directly for faster screenshots

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: `click` on dialog-triggering buttons blocks indefinitely; dialog-dismiss requires separate invocation

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
b4w click "#alertBtn"
# Times out after 30s; need to run `dialog-accept` separately
b4w dialog-accept
```

#### Expected Behavior

Either the click completes when the dialog is dismissed, or a clear workflow for handling blocking dialogs is documented.

#### Actual Behavior

The `click` command hangs for 30 seconds until timeout because the JavaScript `alert()` blocks the page's main thread. The user must know to send `dialog-accept` in a separate terminal/process, which is not practical in a single-threaded AI agent context.

#### Root Cause Analysis

Native browser dialogs (`alert()`, `confirm()`, `prompt()`) are synchronous and block the JavaScript event loop. CDP can intercept and handle them via `Page.handleJavaScriptDialog`, but the `click` command waits for the page to respond before returning. The dialog blocks that response, creating a deadlock: `click` waits for completion, dialog waits for user action.

#### Code Pointer

`The click command could detect that a dialog appeared and delegate to dialog handling, or use a non-blocking click approach.`

#### AI Suggested Improvement

- Auto-detect when a dialog appears during `click` and surface a clear message: "A dialog appeared. Use `dialog-accept` or `dialog-dismiss` to handle it."
- Or: add a `--auto-dismiss` flag to `click` that auto-accepts any dialog that appears
- Add a `--expect-dialog` flag to `click` that tells the command to expect and handle a dialog after clicking
- Document the dialog workflow clearly in SKILL.md with explicit examples for alert, confirm, and prompt

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: `generate-locator` and `dialog-accept`/`dialog-dismiss` not documented in SKILL.md command map

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Search SKILL.md for "generate-locator", "dialog-accept", "dialog-dismiss", "dblclick", "drag", "hover" — these commands appear in the CLI help but have zero documentation in SKILL.md.

#### Expected Behavior

Every command in the help output should have at least a brief mention in the SKILL.md documentation, with usage examples for common patterns.

#### Actual Behavior

These commands are discoverable from `help` but completely absent from the skill documentation that AI agents are instructed to read. This creates a discoverability gap for both humans and AI agents.

#### Root Cause Analysis

The SKILL.md command map focuses on data extraction and session management, but interaction commands (mouse operations, dialog handling) are underrepresented. The skill predates some of these commands.

#### AI Suggested Improvement

- Add entries for `dblclick`, `drag`, `hover`, `generate-locator`, `dialog-accept`, `dialog-dismiss` to the Command Map table in SKILL.md (§3)
- Add a "Mouse Interactions" quick pattern section showing hover → verify, drag → verify, dblclick → verify
- Add a "Dialog Handling" quick pattern section covering alert, confirm, and prompt workflows
- Add `generate-locator` to the element refs section (§2) as the bridge between refs and resilient CSS selectors

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: `snapshot` commands time out during dialog-blocked page state without clear error

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
b4w click "#alertBtn"        # triggers alert, blocks page
b4w snapshot grep "alert"    # times out without indicating a dialog is blocking
```
After `dialog-accept` dismisses the dialog, some snapshot operations still time out until the backgrounded `click` task fully resolves.

#### Expected Behavior

Either the snapshot command works despite the dialog, or a clear error message indicates "Page is blocked by a dialog. Use dialog-accept/dialog-dismiss first."

#### Actual Behavior

Commands silently time out with generic `HTTP request timed out` errors, requiring the user to diagnose whether it's a server issue, page issue, or dialog blocking.

#### Root Cause Analysis

When a native dialog is showing, CDP operations that interact with the page hang. The error surfacing doesn't distinguish between network timeouts, server crashes, and dialog-blocked pages.

#### AI Suggested Improvement

- Before executing commands that interact with the page, check if a JavaScript dialog is currently showing and surface a clear message
- Add a `--check-dialog` flag or command to query whether a dialog is currently blocking the page
- Return a specific error code/message for dialog-blocked state vs. genuine timeouts

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: No `--auto-diff` integration example or clear "verify-after-interaction" workflow in docs

**Severity:** Low
**Category:** Discoverability

#### Reproduction

A first-time user reading SKILL.md sees `--auto-diff` mentioned but no clear example of using it after a series of interactions to verify state changes.

#### Expected Behavior

Clear, copy-paste-ready examples of verifying interaction results using `--auto-diff`, `snapshot grep`, and `htmlsnapshot get`.

#### Actual Behavior

The SKILL.md mentions `--auto-diff` in the core loop section but doesn't show it in action with real refs or demonstrate common post-interaction verification patterns. Users discover verification commands (like `snapshot grep`) through trial and error.

#### Root Cause Analysis

Documentation gap — the core loop section shows the pattern abstractly but doesn't provide a concrete walkthrough showing verification of hover, click, drag, and dialog results.

#### AI Suggested Improvement

- Add a "Verifying Results" section to SKILL.md with concrete examples:
  - After click: `snapshot -v 0 --auto-diff --stdout`
  - After hover: `snapshot grep "expected-tooltip-text"`
  - After drag: `snapshot grep "new order|reordered"`
  - After dialog: `snapshot grep "\[alert\]|\[confirm\]|\[prompt\]"`
- Show `snapshot grep` as the primary verification tool with real examples from the interactive-5 page

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: PowerShell wrapper intercepts `-i`/`-v` flags; `--` passthrough broken from Git Bash

```bash
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 -- snapshot -i --stdout
```

#### Issue 2: `dblclick` fails on non-focusable elements (common in real-world pages)

```bash
b4w dblclick e45   # where e45 is a <div> dblclick target
```

#### Issue 3: `drag` command fails with snapshot refs; requires CSS selectors

```bash
b4w drag e39 e42   # using refs from snapshot
```

#### Issue 4: `screenshot` command times out consistently on moderately complex pages

```bash
b4w goto "http://localhost:18080/generated/interactive-5.html"
b4w screenshot
b4w screenshot --full-page
b4w screenshot "#dialogResult"
```

#### Issue 5: `click` on dialog-triggering buttons blocks indefinitely; dialog-dismiss requires separate invocation

```bash
b4w click "#alertBtn"
# Times out after 30s; need to run `dialog-accept` separately
b4w dialog-accept
```

#### Issue 6: `generate-locator` and `dialog-accept`/`dialog-dismiss` not documented in SKILL.md command map

Search SKILL.md for "generate-locator", "dialog-accept", "dialog-dismiss", "dblclick", "drag", "hover" — these commands appear in the CLI help but have zero documentation in SKILL.md.

#### Issue 7: `snapshot` commands time out during dialog-blocked page state without clear error

```bash
b4w click "#alertBtn"        # triggers alert, blocks page
b4w snapshot grep "alert"    # times out without indicating a dialog is blocking
```
After `dialog-accept` dismisses the dialog, some snapshot operations still time out until the backgrounded `click` task fully resolves.

#### Issue 8: No `--auto-diff` integration example or clear "verify-after-interaction" workflow in docs

A first-time user reading SKILL.md sees `--auto-diff` mentioned but no clear example of using it after a series of interactions to verify state changes.

