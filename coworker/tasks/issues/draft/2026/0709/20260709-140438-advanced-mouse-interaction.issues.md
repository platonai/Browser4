# Issues: advanced-mouse-interaction

> **Source:** `20260709-140438-advanced-mouse-interaction.full.md` | **Date:** 20260709-140438 | **Mode:** dev

## Scenario Background

### Task

The task is **successfully completed** with one workaround. All 13 steps were executed:

| Step | Action | Result |
|------|--------|--------|
| 1 | Navigate to interactive-5.html | Page loaded, session established on port 8182 |
| 2 | Interactive snapshot (`snapshot -i`) | 67 nodes discovered across 2 viewports |
| 3 | Hover tooltip triggers (e22, e25) | Tooltips triggered; accessible names include tooltip text |
| 4 | Hover product card (e27) | Card expanded — bounding box height went from 71→121, detail text visible |
| 5 | Drag Backlog to top (drag e42→e39) | List reordered: Backlog→High→Medium→Low ✅ |
| 6 | Double-click activation zone | **Workaround required** — `dblclick` command doesn't trigger JS handlers; used `eval` to dispatch `MouseEvent('dblclick')`. Zone activated, counter=1 ✅ |
| 7 | Double-click reset zone | Same workaround. Counters reset to 0, status idle ✅ |
| 8 | `generate-locator e57` | Generated `#alertBtn` |
| 9 | `get text "#alertBtn"` | Returned "🔔 Show Alert" ✅ |
| 10 | Alert dialog (click + dialog-accept) | "[alert] User dismissed the alert dialog." ✅ |
| 11 | Confirm dialog (click + dialog-accept) | "[confirm] User clicked OK / accepted." ✅ |
| 12 | Prompt dialog (click + dialog-accept "test-input-from-cli") | "[prompt] User entered: \"test-input-from-cli\"" ✅ |
| 13 | Final screenshot (`--full-page`) | Saved 184KB PNG |

---

### Execution Context

**Key Commands:**

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/generated/interactive-5.html"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -i
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- hover e22
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- hover e25
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- hover e27
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- drag e42 e39
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- dblclick e45 (failed silently)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- dblclick e46 (failed silently)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "document.getElementById('dblclickZone').dispatchEvent(...)"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- dblclick e51 (failed silently)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "document.getElementById('dblclickResetZone').dispatchEvent(...)"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- generate-locator e57
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- get text "#alertBtn"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click "#alertBtn"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- dialog-accept
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click "#confirmBtn"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- dialog-accept
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click "#promptBtn"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- dialog-accept "test-input-from-cli"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --full-page --filename .../final-screenshot.png
```

**Major decisions:**
- Used `eval` to work around non-functional `dblclick` command
- Used CSS selectors (`#alertBtn`, `#confirmBtn`, `#promptBtn`) instead of refs for dialog buttons
- Used `& sleep 3` for alert/confirm/prompt to let `click` command complete before `dialog-accept`

**Workarounds required:**
1. `dblclick`: Does not trigger JavaScript event listeners; used `eval` with `dispatchEvent(new MouseEvent('dblclick', {bubbles: true}))` as workaround
2. `htmlsnapshot capture` and `get text` (with refs): Intermittent 60s timeout failures against backend; `get text` with CSS selectors eventually worked
3. MockSite startup: Required multiple monitors and a total ~5 minutes to compile and start

---

---

## Issues Found (8 issues)

### Issue 1: `dblclick` command does not trigger JavaScript event listeners

**Severity:** Critical
**Category:** Product

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/generated/interactive-5.html"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -i
# Find the dblclick zone ref (e45/e46 or similar)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- dblclick e45
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot --stdout | grep -E "ACTIVATED|dblClickCount"
```

#### Expected Behavior

Double-clicking the zone should trigger the `dblclick` JavaScript event listener, updating the counter and status to "ACTIVATED ✅".

#### Actual Behavior

The command reports "✓ Double-clicked e45" but the counters remain at 0 and status remains "idle". No JavaScript `dblclick` event is dispatched to the page.

#### Root Cause Analysis

The CDP `Input.dispatchMouseEvent` with clickCount=2 likely does not synthesize a proper JavaScript `dblclick` event that bubbles through the DOM. The command only sends the low-level CDP mouse events but does not ensure the page's JS event listeners receive the `dblclick` event. Investigation needed to determine whether CDP's `dispatchMouseEvent` with clickCount=2 should trigger JS `dblclick` or whether an explicit `dispatchEvent` is required.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the mouse interaction handler that translates `dblclick` into CDP `Input.dispatchMouseEvent` calls.`

#### AI Suggested Improvement

- After dispatching CDP mouse events, additionally dispatch a JavaScript `dblclick` event via `Runtime.evaluate` or `Page.dispatchEvent` as a fallback
- Add a `--verify` flag to `dblclick` (similar to `press --verify`) that checks the outcome
- Document the known limitation in help text: "Note: dblclick may not trigger JavaScript dblclick event listeners; use eval to dispatch manually for JS-driven interactions"
- Add an e2e test case specifically for double-clicking elements with JavaScript event listeners

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: `get text` and `htmlsnapshot capture` intermittently time out (60s)

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- get text e22
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot capture
```

#### Expected Behavior

Commands complete within a few seconds.

#### Actual Behavior

HTTP request times out at 60 seconds: `Error: HTTP request timed out [tool=select_first_text_or_null, endpoint=http://localhost:8182/mcp/call-tool, timeout=60s]`

#### Root Cause Analysis

The Browser4 backend at `localhost:8182` sometimes fails to respond to `get text` (with ref-based selectors) and `htmlsnapshot capture` within the 30-60s timeout window. The `snapshot`, `hover`, `click`, `drag`, and `dialog-accept` commands work reliably, suggesting the issue is specific to certain MCP tool calls. The backend process was alive and `status` reported "UP" at the time.

#### Code Pointer

`Unknown — investigation needed on the Browser4 backend's handling of `select_first_text_or_null` and `html_snapshot_capture` tool calls.`

#### AI Suggested Improvement

- Add retry logic in the CLI (`cli/browser4-cli/src/http.rs`) for transient backend timeouts
- Investigate backend-side root cause — could be a deadlock, resource exhaustion, or long-running DOM operation
- Add a timeout flag (`--timeout <ms>`) for users to control the timeout per-command
- Surface a clearer error message that distinguishes "server unreachable" from "request timed out"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `click` on button that triggers `alert()` blocks indefinitely

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click "#alertBtn"
```
The command runs indefinitely (goes to background) because the `alert()` dialog blocks page execution before the click response completes.

#### Expected Behavior

The click command should complete promptly, or the CLI should auto-detect and handle the dialog, or provide a clear error/instruction about how to proceed.

#### Actual Behavior

The command hangs indefinitely, forcing the user to run `dialog-accept` in a separate terminal/shell invocation.

#### Root Cause Analysis

When `window.alert()` fires, it blocks the JavaScript execution context and the page's event loop, potentially preventing the CDP from sending the click acknowledgment back. The CLI has no timeout or dialog-detection mechanism for the click command.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the click command handler; could add a post-click dialog check or timeout.`

#### AI Suggested Improvement

- Add a short timeout to the click response (e.g., 5s), then auto-check for pending dialogs
- Add a `--handle-dialog` flag to click that accepts any dialog that appears after the click
- Document this behavior clearly in the click command help and SKILL.md
- Consider adding a `batch`-compatible pattern: `click e57 ; dialog-accept`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: `drag` command lacks confirmation output

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- drag e42 e39
```

#### Expected Behavior

A confirmation message similar to "✓ Dragged e42 to e39" (matching the pattern of "✓ Hovered e22", "✓ Double-clicked e45", "✓ Clicked #confirmBtn").

#### Actual Behavior

No confirmation output — only the auto-snapshot header is shown. The user must re-snapshot to verify the drag succeeded.

#### Root Cause Analysis

The `drag` command response handler does not emit a success confirmation line, unlike `hover`, `click`, and `dblclick`.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the drag command response handler.`

#### AI Suggested Improvement

- Add "✓ Dragged e42 to e39" confirmation output to match other interaction commands

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: Tooltip text verification is ambiguous from accessibility snapshots

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Hover over a tooltip trigger element, then take a snapshot. The tooltip text content is merged into the parent element's accessible name regardless of CSS visibility state.

#### Expected Behavior

The snapshot should clearly distinguish between hidden tooltip text and visible tooltip text, or there should be a documented way to verify tooltip visibility.

#### Actual Behavior

The accessible name of the tooltip container always includes the tooltip text content (e.g., "Accessibility Tree A hierarchical representation of the page that assistive technologies use to navigate content."), even before hovering. This makes it impossible to distinguish "tooltip is visible" from "tooltip content exists but is hidden" using snapshots alone.

#### Root Cause Analysis

The accessibility tree includes hidden child text in parent accessible names. CSS `visibility: hidden` and `opacity: 0` do not remove elements from the accessibility tree in all browsers.

#### Code Pointer

`Unknown — depends on whether the ARIA tree generation happens browser-side (CDP) or server-side.`

#### AI Suggested Improvement

- Document a recommended pattern for tooltip verification: use `eval` to check `getComputedStyle(el).visibility` or `opacity` after hovering
- Add a `--boxes` example to the tooltip section of documentation
- Consider adding an `--include-hidden` flag to snapshot to control whether hidden elements appear

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: Documentation does not cover `dialog-accept [prompt]` input parameter

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `browser4-cli --help` or read `cli/README.md` — the dialog-accept command shows `dialog-accept [prompt]` but nowhere explains that `[prompt]` is the text input for `window.prompt()` dialogs.

#### Expected Behavior

Help output should explain that `dialog-accept [prompt]` accepts an optional text argument used as input for prompt dialogs.

#### Actual Behavior

The help text says "Accept a dialog" with no explanation of the optional `[prompt]` parameter. Users must guess its purpose.

#### Root Cause Analysis

The help text generator (`cli/browser4-cli/src/help.rs`) emits a single-line description without parameter details.

#### Code Pointer

``cli/browser4-cli/src/help.rs` — the dialog-accept command description.`

#### AI Suggested Improvement

- Change help text to: `dialog-accept [prompt] — Accept a dialog. For prompt() dialogs, optionally provide input text.`
- Add an example to the help output: `browser4-cli dialog-accept "my input text"`
- Add dialog handling examples to the SKILL.md quick patterns section

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: No command to scroll to viewport for screenshot coverage

**Severity:** Low
**Category:** UX / Discoverability

#### Reproduction

The page has 2 viewports. To see the interaction log at the bottom, the user must either scroll or use `snapshot -v 1`. There's no obvious way to scroll the viewport for a screenshot.

#### Expected Behavior

`scroll` command should be prominent and clearly documented for moving between viewports. The `screenshot --full-page` flag works but isn't documented in the snapshot viewport guidance.

#### Actual Behavior

The `--full-page` screenshot flag solved the problem (it captured the entire page), but the connection between viewport scrolling and screenshot capture isn't documented. New users might take multiple manual screenshots.

#### Root Cause Analysis

The viewport/scrolling documentation is scattered across `snapshot --help` and `scroll` command help without cross-referencing `screenshot` capabilities.

#### Code Pointer

``cli/browser4-cli/src/help.rs` — cross-reference between viewport/snapshot and screenshot docs.`

#### AI Suggested Improvement

- Add a tip in viewport guidance: "Tip: Use `screenshot --full-page` to capture all viewports in a single image."
- Add a `screenshot` example to the SKILL.md quick patterns section

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: Ref lifecycle documentation could better warn about drag

**Severity:** Low
**Category:** Documentation

#### Reproduction

The SKILL.md says refs are unsafe after: "click on navigation links or buttons that trigger page updates, goto, reload, tab switches." It doesn't mention `drag`, which reorders DOM nodes.

#### Expected Behavior

The ref lifecycle table should list `drag` as a DOM-mutating command that requires re-snapshot.

#### Actual Behavior

`drag` is absent from both the "Safe" and "Unsafe" categories. In practice, refs survived the drag in my test (the refs were the same before and after), but this may not be guaranteed.

#### Root Cause Analysis

`drag` may or may not mutate the DOM depending on the implementation (HTML5 drag uses `insertBefore` which does mutate the DOM).

#### Code Pointer

``skills/browser4-cli/SKILL.md` — section 2, Ref Lifecycle table.`

#### AI Suggested Improvement

- Add `drag` to the "Unsafe" ref lifecycle category (since it performs `insertBefore`/`appendChild`)
- Alternatively, document that refs MAY survive drag but should not be relied upon

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
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `dblclick` command does not trigger JavaScript event listeners

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/generated/interactive-5.html"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -i
# Find the dblclick zone ref (e45/e46 or similar)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- dblclick e45
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot --stdout | grep -E "ACTIVATED|dblClickCount"
```

#### Issue 2: `get text` and `htmlsnapshot capture` intermittently time out (60s)

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- get text e22
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot capture
```

#### Issue 3: `click` on button that triggers `alert()` blocks indefinitely

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click "#alertBtn"
```
The command runs indefinitely (goes to background) because the `alert()` dialog blocks page execution before the click response completes.

#### Issue 4: `drag` command lacks confirmation output

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- drag e42 e39
```

#### Issue 5: Tooltip text verification is ambiguous from accessibility snapshots

Hover over a tooltip trigger element, then take a snapshot. The tooltip text content is merged into the parent element's accessible name regardless of CSS visibility state.

#### Issue 6: Documentation does not cover `dialog-accept [prompt]` input parameter

Run `browser4-cli --help` or read `cli/README.md` — the dialog-accept command shows `dialog-accept [prompt]` but nowhere explains that `[prompt]` is the text input for `window.prompt()` dialogs.

#### Issue 7: No command to scroll to viewport for screenshot coverage

The page has 2 viewports. To see the interaction log at the bottom, the user must either scroll or use `snapshot -v 1`. There's no obvious way to scroll the viewport for a screenshot.

#### Issue 8: Ref lifecycle documentation could better warn about drag

The SKILL.md says refs are unsafe after: "click on navigation links or buttons that trigger page updates, goto, reload, tab switches." It doesn't mention `drag`, which reorders DOM nodes.

