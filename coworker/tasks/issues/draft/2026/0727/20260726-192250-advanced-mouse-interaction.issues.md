# Issues: advanced-mouse-interaction

> **Source:** `20260726-192250-advanced-mouse-interaction.full.md` | **Date:** 20260726-192250 | **Mode:** dev

## Scenario Background

### Task

The task was completed successfully. All 13 steps were executed:
- Navigation and interactive snapshot ✅
- Hover over tooltips verified ✅
- Hover over product card verified ✅
- Drag reorder accomplished ✅
- Double-click activate and reset accomplished ✅ (via eval workaround)
- Generate-locator and get text ✅
- Alert, Confirm, Prompt dialogs handled ✅ (via CDP dialog override)
- Final screenshot captured ✅

**Workarounds required:** 4 (short flags, drag selectors, dblclick focus, native dialogs)

---

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| 0 | `./bin/test.ps1 mock-site` | Mock site started on :18080 |
| 1 | `goto "http://localhost:18080/generated/interactive-5.html"` | Page loaded |
| 2 | `snapshot --interactive --stdout` | All interactive elements discovered |
| 3 | `hover e62`, `hover e65` | Tooltip terms hovered; content visible in tree |
| 4 | `hover e67` | Wireless Headphones product card hovered |
| 5 | `drag '#priorityBacklog' '#priorityHigh'` | Backlog moved to top (had to use CSS selectors) |
| 6 | `eval --file /tmp/dblclick.js` | Double-click dispatched via JS; Status→ACTIVATED, count→1 |
| 7 | `eval --file /tmp/reset.js` | Counters reset via JS; Status→idle, count→0 |
| 8 | `generate-locator e97` | Produced `#alertBtn` |
| 9 | `get text '#alertBtn'` | Retur...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: Native browser dialogs (alert/confirm/prompt) block the browser and break the driver

**Severity:** Critical
**Category:** Reliability

#### Reproduction

Click a button that calls `window.alert()` on a page, e.g., `./b4w.ps1 click e56` on the Show Alert button.

#### Expected Behavior

The dialog should be detectable and dismissable via `dialog-accept`/`dialog-dismiss` commands, or the click should complete with the dialog state reported.

#### Actual Behavior

The click times out (120s), the driver becomes unhealthy ("Driver 3 is unhealthy | state: INIT | the target page is not alive"), and the session must be closed and recreated. The `dialog-accept` command also times out because the HTTP request to the backend never completes.

#### Root Cause Analysis

When `window.alert()` fires, Chrome's main thread is blocked until the dialog is dismissed. CDP commands that go through the page's main thread (like `Runtime.evaluate`, `Input.dispatchMouseEvent` completion callbacks) also block. The backend's synchronous tool execution model cannot detect or handle this state — it waits for the click to complete, which never happens because the dialog blocks it. CDP has `Page.javascriptDialogOpening` and `Page.handleJavaScriptDialog` events/commands specifically for this, but browser4 doesn't appear to use them for automatic dialog handling.

#### Code Pointer

``browser4-rest/src/main/kotlin/.../MCPToolController.kt` — the tool dispatch logic; `browser4-core/browser4-browser/.../PulsarWebDriver.kt` — the click implementation should enable `Page.javascriptDialogOpening` event handling before clicks`

#### AI Suggested Improvement

- Before any click command, enable `Page.javascriptDialogOpening` event and register a handler that auto-dismisses dialogs (or at minimum prevents blocking)
- Implement a pre-click dialog guard: call `Page.setInterceptFileChooserDialog` equivalent for JavaScript dialogs
- Add a `--auto-dismiss-dialogs` flag for click that auto-accepts any dialog that appears
- Document that native dialogs require CDP-level handling and provide a CDP-based workaround recipe in the SKILL.md
- The `dialog-accept`/`dialog-dismiss` commands should work via CDP `Page.handleJavaScriptDialog` even when the page is blocked

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: PowerShell parameter binder intercepts short flags (-i, -v, -s)

**Severity:** High
**Category:** UX

#### Reproduction

`./b4w.ps1 snapshot -i` or `./b4w.ps1 snapshot -v 0` from bash

#### Expected Behavior

Flags passed through to browser4-cli binary.

#### Actual Behavior

PowerShell intercepts `-i` as `-InformationAction` and `-v` as `-Verbose`. The CLI receives `snapshot-i` or `snapshot-0` as a single unknown command.

#### Root Cause Analysis

The `b4w.ps1` PowerShell wrapper uses `param()` with `[CmdletBinding()]` or equivalent, causing PowerShell's parameter binder to match short flags before passing them to the underlying binary. Even when invoked from bash via `pwsh`, the `.ps1` script itself processes these flags.

#### Code Pointer

``b4w.ps1` — the param block at the top of the script`

#### AI Suggested Improvement

- Add `--%` (stop-parsing symbol) to the param block so PowerShell passes all remaining arguments unmodified to the CLI binary
- Or use `$args` instead of named params in the .ps1 wrapper to avoid the parameter binder entirely
- Document that users on all shells should prefer long-form flags (`--interactive`, `--viewport`) when using `b4w.ps1`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `drag` command requires CSS selectors, not snapshot refs

**Severity:** High
**Category:** Product

#### Reproduction

`./b4w.ps1 drag e82 e79` (using snapshot refs)

#### Expected Behavior

Drag should work with snapshot refs like `click`, `hover`, `fill`, etc.

#### Actual Behavior

Error: "Failed to execute 'querySelector' on 'Document': 'backend:82' is not a valid selector." The drag command interprets refs as CSS selectors instead of resolving them through the backend node ID lookup used by click/hover/fill.

#### Root Cause Analysis

The `drag` backend implementation (`browser_drag`) uses `querySelector` on the page directly rather than resolving backend node IDs through the CDP accessibility tree. All other interaction commands (click, hover, fill, type, dblclick) resolve refs through the backend node ID → DOM node mapping. The `drag` command likely goes through a different code path that expects CSS selectors.

#### Code Pointer

``browser4-core/browser4-browser/` — the `drag` method in `PulsarWebDriver.kt` or wherever `browser_drag` is implemented`

#### AI Suggested Improvement

- Implement ref-to-selector resolution in the drag command backend, matching the behavior of click/hover/fill
- Alternatively, document clearly in the help text that drag requires CSS selectors, not snapshot refs
- Add a clear error message that says "drag requires CSS selectors; use generate-locator <ref> to convert a ref"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `dblclick` fails on non-focusable elements

**Severity:** High
**Category:** Reliability

#### Reproduction

`./b4w.ps1 dblclick e85` or `./b4w.ps1 dblclick '#dblclickZone'`

#### Expected Behavior

Double-click should dispatch a dblclick event on the target element regardless of whether it is focusable.

#### Actual Behavior

Error: "Element is not focusable. Tip: Use 'click <ref>' first to focus the element." Clicking first does not resolve the issue — the element remains unfocusable.

#### Root Cause Analysis

The `dblclick` implementation requires the element to be keyboard-focusable before dispatching the double-click. Many legitimate double-click targets (div-based zones, cards, custom widgets) are not focusable by default. The CDP `Input.dispatchMouseEvent` with `type: "mousePressed"`, `clickCount: 2` should work on any visible element without requiring focusability.

#### Code Pointer

``browser4-core/browser4-browser/` — `PulsarWebDriver.dblclick()` or wherever browser_double_click is implemented, likely in the focus-check logic before dispatching the mouse event`

#### AI Suggested Improvement

- Remove the focusability check before dispatching double-click mouse events — any visible, interactable element should support double-click
- If focus is required for some CDP reason, automatically call `DOM.focus()` on the element first, then dispatch the double-click
- Fall back to dispatching a raw `dblclick` MouseEvent via `Runtime.evaluate` if CDP mouse events fail

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: `b4w.sh` broken on Git Bash — recommends pwsh but path is wrong

**Severity:** Medium
**Category:** Reliability

#### Reproduction

`./b4w.sh snapshot -v 0` from Git Bash

#### Expected Behavior

Script should execute browser4-cli with the provided arguments.

#### Actual Behavior

Error: "The term '/d/workspace/Browser4/Browser4-4.12/b4w.ps1' is not recognized." The script prints "It is strongly recommended to launch `pwsh` and run the .ps1 commands directly" and then tries to invoke pwsh with a Unix-style path that pwsh doesn't recognize.

#### Root Cause Analysis

`b4w.sh` uses a Unix-format path (`/d/workspace/...`) when calling `pwsh`, but pwsh on Windows expects Windows-style paths (`D:\workspace\...`). The `&` operator in pwsh doesn't resolve Unix paths.

#### Code Pointer

``b4w.sh` — the pwsh invocation line`

#### AI Suggested Improvement

- Convert the path to Windows format before passing to pwsh (use `cygpath -w` or string substitution)
- Or have `b4w.sh` invoke `browser4-cli` directly via npm/cargo instead of going through the .ps1 wrapper
- Document that `b4w.sh` doesn't work on Git Bash and recommend `b4w.bat` from Command Prompt or long-form flags with `b4w.ps1`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Help tip suggests `snapshot -v 0` but uses short flag that fails

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Follow the tip from `goto` output: "Run `snapshot -v 0` to see interactive element refs"

#### Expected Behavior

The suggested command should work as-is when copy-pasted.

#### Actual Behavior

The `-v` flag is intercepted by PowerShell, and the command fails. New users following the tip will hit an immediate roadblock.

#### Root Cause Analysis

The tip text is generated by the CLI binary and doesn't account for the PowerShell wrapper that will process the arguments first. The binary uses short flags in its help text, but the wrapper doesn't protect them.

#### Code Pointer

`The tip text generation in the CLI or backend; `b4w.ps1` param block`

#### AI Suggested Improvement

- Update tips to use long-form flags: "Run `snapshot --viewport 0` to see interactive element refs"
- Or add a one-time warning on first run about the PowerShell flag interception issue
- Auto-detect when running through the PowerShell wrapper and adjust tips accordingly

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: No automatic session recovery after dialog-induced driver failure

**Severity:** Medium
**Category:** UX

#### Reproduction

Trigger a native dialog; the driver becomes unhealthy. Run any subsequent command.

#### Expected Behavior

The CLI should detect the unhealthy driver state and either auto-recover (close and reopen session) or provide clear recovery instructions.

#### Actual Behavior

The error message says "Driver 3 is unhealthy | state: INIT | the target page is not alive" but doesn't suggest recovery steps. The user must manually close the session, check status, and start fresh.

#### Root Cause Analysis

The driver health check doesn't trigger automatic recovery. The error is reported but no remediation is attempted.

#### Code Pointer

`The driver health monitoring in the backend; the CLI's error handling`

#### AI Suggested Improvement

- When a driver is detected as unhealthy after a command, offer to auto-recover: "Driver is unhealthy. Would you like to restart the session? (run with --auto-recover to do this automatically)"
- Add a `--auto-recover` global flag that automatically closes and reopens sessions on driver failure
- Include recovery instructions in the error message itself

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: No built-in way to scroll to capture bottom of long pages

**Severity:** Low
**Category:** UX

#### Reproduction

Take a `screenshot` on a page that is 3 viewports tall.

#### Expected Behavior

Option to capture full-page screenshot or scroll to capture specific sections.

#### Actual Behavior

Only the current viewport is captured. To see the interaction log at the bottom, you need to either screenshot a specific ref or scroll first. There's no `screenshot --full-page` option.

#### Root Cause Analysis

The screenshot command captures only the visible viewport. There's no CDP `Page.captureScreenshot` with `fullPage: true` or a `clip` parameter in the current implementation.

#### Code Pointer

``browser4-core/browser4-browser/` — screenshot implementation`

#### AI Suggested Improvement

- Add `screenshot --full-page` flag using CDP's `captureScreenshot` with `captureBeyondViewport: true`
- Alternatively, add `screenshot --scroll-to-bottom` to auto-scroll before capturing

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Native browser dialogs (alert/confirm/prompt) block the browser and break the driver

Click a button that calls `window.alert()` on a page, e.g., `./b4w.ps1 click e56` on the Show Alert button.

#### Issue 2: PowerShell parameter binder intercepts short flags (-i, -v, -s)

`./b4w.ps1 snapshot -i` or `./b4w.ps1 snapshot -v 0` from bash

#### Issue 3: `drag` command requires CSS selectors, not snapshot refs

`./b4w.ps1 drag e82 e79` (using snapshot refs)

#### Issue 4: `dblclick` fails on non-focusable elements

`./b4w.ps1 dblclick e85` or `./b4w.ps1 dblclick '#dblclickZone'`

#### Issue 5: `b4w.sh` broken on Git Bash — recommends pwsh but path is wrong

`./b4w.sh snapshot -v 0` from Git Bash

#### Issue 6: Help tip suggests `snapshot -v 0` but uses short flag that fails

Follow the tip from `goto` output: "Run `snapshot -v 0` to see interactive element refs"

#### Issue 7: No automatic session recovery after dialog-induced driver failure

Trigger a native dialog; the driver becomes unhealthy. Run any subsequent command.

#### Issue 8: No built-in way to scroll to capture bottom of long pages

Take a `screenshot` on a page that is 3 viewports tall.

