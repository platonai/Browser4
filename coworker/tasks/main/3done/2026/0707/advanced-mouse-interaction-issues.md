# Issues: advanced-mouse-interaction

> **Source:** `20260706-184550-advanced-mouse-interaction.full.md` | **Date:** 20260706-184550 | **Mode:** dev

## Scenario Background

### Task

✅ **All 13 task steps completed successfully:**

| Step | Command | Result |
|------|---------|--------|
| 1. Navigate | `goto "http://localhost:18080/generated/interactive-5.html"` | Page loaded, title "Advanced Interaction Playground" |
| 2. Interactive snapshot | `snapshot -i` | 7 KB, 67 nodes, all interactive elements discovered |
| 3. Hover tooltips | `hover e62` (Accessibility Tree), `hover e65` (DOM Snapshot) | Both tooltips triggered; tooltip text visible in accessible name |
| 4. Hover product card | `hover e67` | Card expansion triggered; detail text visible at e70 |
| 5. Drag reorder | `drag "#priorityHigh" "#priorityBacklog"` | High Priority moved to bottom; order: Medium→Low→Backlog→High |
| 6. Double-click activate | `dblclick "#dblclickZone"` (after `eval` to add tabindex) | Status: ACTIVATED ✅, counter: 1 |
| 7. Double-click reset | `dblclick "#dblclickResetZone"` (after `eval` to add tabindex) | Counters reset to 0, Status: idle |
| 8. Generate locator | `generate-locator e97` | Produced `#alertBtn` CSS selector |
| 9. Get text with locator | `get text "#alertBtn"` | "🔔 Show Alert" |
| 10. Alert dialog | `click "#alertBtn"` → `dialog-accept` | Result: "[alert] User dismissed the alert dialog." |
| 11. Confirm dialog | `click "#confirmBtn"` → `dialog-accept` | Result: "[confirm] User clicked OK / accepted." |
| 12. Prompt dialog | `click "#promptBtn"` → `dialog-accept "Hello from browser4-cli"` | Result: "[prompt] User entered: \"Hello from browser4-cli\"" |
| 13. Final screenshot | `screenshot --full-page` | Full-page PNG captured |

### Execution Context

**Key Commands:**

```
cd cli/browser4-cli && cargo run -- goto "http://localhost:18080/generated/interactive-5.html"
cd cli/browser4-cli && cargo run -- snapshot -i
cd cli/browser4-cli && cargo run -- snapshot -i --stdout
cd cli/browser4-cli && cargo run -- hover e62
cd cli/browser4-cli && cargo run -- snapshot -i --stdout
cd cli/browser4-cli && cargo run -- hover e65
cd cli/browser4-cli && cargo run -- snapshot -i --stdout
cd cli/browser4-cli && cargo run -- hover e67
cd cli/browser4-cli && cargo run -- snapshot -v 0 --stdout | grep "Wireless Headphones"
cd cli/browser4-cli && cargo run -- drag e79 e82                     # FAILED - refs not valid for drag
cd cli/browser4-cli && cargo run -- drag ".priorityHigh" ".priorityBacklog"  # FAILED - wrong selectors
cd cli/browser4-cli && cargo run -- htmlsnapshot
cd cli/browser4-cli && cargo run -- htmlsnapshot grep "priority"
cd cli/browser4-cli && cargo run -- drag "#priorityHigh" "#priorityBacklog"  # SUCCESS
cd cli/browser4-cli && cargo run -- dblclick e85                     # FAILED - not focusable
cd cli/browser4-cli && cargo run -- dblclick "#dblclickZone"         # FAILED - not focusable
cd cli/browser4-cli && cargo run -- eval "document.getElementById('dblclickZone').setAttribute('tabindex', '0')"
cd cli/browser4-cli && cargo run -- dblclick "#dblclickZone"         # SUCCESS
cd cli/browser4-cli && cargo run -- eval "document.getElementById('dblclickResetZone').setAttribute('tabindex', '0')"
cd cli/browser4-cli && cargo run -- dblclick "#dblclickResetZone"    # SUCCESS
cd cli/browser4-cli && cargo run -- generate-locator e97
cd cli/browser4-cli && cargo run -- get text "#alertBtn"
cd cli/browser4-cli && cargo run -- click "#alertBtn"                # hung (dialog open)
cd cli/browser4-cli && cargo run -- dialog-accept
cd cli/browser4-cli && cargo run -- click "#confirmBtn"              # hung (dialog open)
cd cli/browser4-cli && cargo run -- dialog-accept
cd cli/browser4-cli && cargo run -- click "#promptBtn"               # hung (dialog open)
cd cli/browser4-cli && cargo run -- dialog-accept "Hello from browser4-cli"
cd cli/browser4-cli && cargo run -- screenshot --full-page
cd cli/browser4-cli && cargo run -- get text "#interactionLog"
```

**Key decisions:**
- Used `cd cli/browser4-cli && cargo run --` from repo root as CLI invocation (per SKILL.md development instructions)
- Used CSS ID selectors (`#priorityHigh`, `#dblclickZone`) instead of snapshot refs when needed, because ref-based targeting fails for `drag` and `dblclick` on non-focusable elements
- Used `htmlsnapshot grep` to discover the actual HTML structure when snapshot refs didn't map to CSS correctly
- Used `eval` to add `tabindex="0"` to make non-focusable elements work with `dblclick`

**Workarounds required:**
- Added `tabindex` via `eval` before `dblclick` on `<div>` elements (non-focusable by default)
- Switched from ref-based to CSS ID selectors for `drag` (refs produce "not a valid selector" error)
- Used `htmlsnapshot grep` for DOM structure discovery when snapshot refs were insufficient
- Accepted that dialog-triggering `click` commands hang and go to background (handled with separate `dialog-accept`)

---

---

---

## Issues Found (9 issues)
> **Review complete:** 5 approved, 4 deferred/rejected

### Issue 2: `drag` command fails with snapshot element refs

**Severity:** High
**Category:** Product

#### Reproduction

```
cd cli/browser4-cli && cargo run -- snapshot -i
cd cli/browser4-cli && cargo run -- drag e79 e82
```
Where e79 and e82 are listitem refs from the snapshot.

#### Expected Behavior

The `drag` command should accept snapshot refs (e79, e82) since the help output says `drag <startRef> <endRef>` and the convention throughout browser4-cli is that refs from snapshots are used for element targeting.

#### Actual Behavior

Error: `browser_drag failed: SyntaxError: Failed to execute 'querySelector' on 'Document': 'backend:79' is not a valid selector.`

#### Root Cause Analysis

The server-side `browser_drag` implementation translates the ref into `backend:<nodeId>` format and passes it to `document.querySelector()`, which doesn't understand CDP backend node IDs. The CLI sends snapshot refs, but the backend expects CSS selectors. There's a mismatch between the CLI's ref-based interface and the server's CSS-selector-based implementation. The CLI should either (a) resolve refs to CSS selectors before sending to the server, or (b) resolve refs to CDP backend node IDs on the server side using a different DOM access method.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — the `drag` command handler; server-side `browser_drag` implementation`

#### AI Suggested Improvement

- In the CLI, resolve snapshot refs to `generate-locator` CSS selectors before passing to the `drag` backend, so the user-visible interface stays ref-based while the backend gets valid CSS
- Alternatively, update the server-side `browser_drag` to accept CDP backend node IDs alongside CSS selectors
- As a documentation fix, update the `drag` help text to clarify that CSS selectors are expected, not snapshot refs, or that refs are auto-resolved

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 3: `dblclick` fails on non-focusable elements

**Severity:** High
**Category:** Product

#### Reproduction

```
cd cli/browser4-cli && cargo run -- snapshot -i --stdout
# Locate the double-click zone ref (e.g., e85)
cd cli/browser4-cli && cargo run -- dblclick e85
cd cli/browser4-cli && cargo run -- dblclick "#dblclickZone"
```

#### Expected Behavior

Double-clicking on an element with `dblclick` event listeners should dispatch the double-click event regardless of whether the element is focusable. Many real-world interactive elements (cards, zones, custom widgets) use `<div>` elements with event listeners and are not natively focusable.

#### Actual Behavior

`ERROR: browser_click failed: Element is not focusable`. The `dblclick` command delegates to `browser_click` which requires the target element to be focusable. A `<div>` with `dblclick` event listener is not natively focusable.

#### Root Cause Analysis

The `browser_click` (and by extension `dblclick`) server implementation checks `Element is not focusable` before proceeding, but many legitimate double-click targets (like custom `<div>` zones with event listeners) are not focusable elements. The workaround is to inject `tabindex="0"` via `eval` first, which is not obvious to new users.

#### Code Pointer

`Server-side `browser_click` implementation — the focusability check; `cli/browser4-cli/src/main.rs` — the `dblclick` command handler`

#### AI Suggested Improvement

- Remove or relax the focusability check for `dblclick` — double-click events don't require focus; they operate at the DOM event level
- If the focusability check is needed for `click`, make `dblclick` use a separate code path that dispatches `dblclick` events via CDP without going through focus
- As a documentation fix, add a note to the `dblclick` help text: "Element must be focusable. Use `eval` to add `tabindex='0'` to non-focusable elements like `<div>`"
- Consider adding a `--force` flag that bypasses the focusability check

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
Remove the focusability check for dblclick

---

---

### Issue 4: `drag` and `dblclick` use `ref` terminology but don't accept snapshot refs

**Severity:** Medium
**Category:** Documentation / UX

#### Reproduction

Read the help output: `drag <startRef> <endRef>` and `dblclick <ref> [button]`. A user naturally interprets `<ref>` as a snapshot element reference (e5, e12, etc.) because that's the convention throughout the CLI. Attempting to use snapshot refs fails for both commands.

#### Expected Behavior

Either (a) the commands should accept snapshot refs, or (b) the help text should clearly state these accept CSS selectors (not snapshot refs), with a different argument name like `<selector>`.

#### Actual Behavior

The help text uses `<ref>` and `<startRef>/<endRef>` terminology, but the commands actually require CSS selectors. `drag` produces a cryptic "not a valid selector" error; `dblclick` produces "Element is not focusable."

#### Root Cause Analysis

The CLI command definitions use the same `<ref>` nomenclature across all commands, but the underlying server implementations for `drag` and `dblclick` have different expectations (CSS selectors, focusable elements) than other `<ref>`-labeled commands like `click` and `hover` which work with snapshot refs.

#### Code Pointer

``cli/browser4-cli/src/args.rs` or `cli/browser4-cli/src/commands.rs` — argument naming and help text`

#### AI Suggested Improvement

- Change argument names from `<ref>` to `<selector>` for `drag`, `dblclick`, and any other commands that don't accept snapshot refs
- Or, implement ref-to-selector resolution on the CLI side (using `generate-locator` internally) so all commands consistently accept snapshot refs
- Add a note in the help text clarifying what types of selectors are accepted

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**
`drag` and `dblclick` should accept snapshot refs

---

---

### Issue 8: `generate-locator` produces simple ID selectors, not "resilient" selectors

**Severity:** Low
**Category:** Product

#### Reproduction

```
cd cli/browser4-cli && cargo run -- generate-locator e97
```
Result: `#alertBtn`

#### Expected Behavior

The command name "generate-locator" and its description ("Generate a unique CSS selector path for an element") suggest something more sophisticated than the element's ID. A new user might expect a multi-segment selector path (like `body > div.dialogSection > button#alertBtn`) that would be more resilient to page restructuring.

#### Actual Behavior

It returns `#alertBtn` — just the element's ID. While this is perfectly valid and unique, it's not necessarily "resilient" if IDs change. The name "generate-locator" implies more than just reading the ID attribute.

#### Root Cause Analysis

The `generate-locator` implementation appears to use the simplest unique selector available. If an element has an ID, it returns `#id`. This is correct behavior but the command name sets higher expectations.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` or the server-side selector generation logic`

#### AI Suggested Improvement

- Consider generating multiple selector variants and ranking them by resilience heuristics
- Add options like `--strategy id|path|aria|power-css` to let users choose the selector generation approach
- Rename to `generate-selector` for clarity, or document that "locator" means "unique selector" in this context

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 9: `snapshot -i` (interactive) doesn't show how many viewports were skipped

**Severity:** Low
**Category:** UX

#### Reproduction

Run `snapshot -i` on a page with 2 viewports.

#### Expected Behavior

The output should indicate how many viewports are available and suggest how to view them.

#### Actual Behavior

The snapshot output shows the first viewport (0) but the viewport hints only appear when using `--stdout`. Without `--stdout`, there's no indication the page has more content below the fold. The drag section and bottom half of the page are invisible unless the user knows to use `-v 1` or `-v all`.

#### Root Cause Analysis

The `--stdout` output includes viewport state metadata (`# This page has 2 viewports...`), but the default output format (file path only) doesn't include this hint.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` — snapshot output formatting`

#### AI Suggested Improvement

- Include viewport count hint in the non-`--stdout` output as a tip: "💡 Tip: This page has 2 viewports. Use `snapshot -v 1` to scroll down."
- Add `-v all` as a convenient shorthand for capturing all viewports at once

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 1: Template variables in task specification are undefined

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Pre-substitute template variables before presenting the task to the evaluator, or provide a variables legend at the top of the task spec

---

### Issue 5: Dialog-triggering clicks hang and go to background, requiring separate `dialog-accept`

**Severity:** Medium
**Category:** UX / Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `--dialog-timeout <ms>` option to `click` that auto-accepts/dismisses dialogs after a timeout

---

### Issue 6: Tooltip hover verification is ambiguous from snapshot output

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Use `--auto-diff` automatically after `hover` commands so users can see what changed

---

### Issue 7: `cargo run --` from repo root fails (must cd into cli/browser4-cli first)

**Severity:** Medium
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a Cargo workspace at the repo root so `cargo run` works from anywhere, or document the `--manifest-path` alternative clearly

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Template variables in task specification are undefined

Read the task specification. It references `$cliInvocation`, `$helpCmd`, `$skillPath`, and `$RepoRootPath` as if they are defined variables, but they are literal strings with no substitution.

#### Issue 2: `drag` command fails with snapshot element refs

```
cd cli/browser4-cli && cargo run -- snapshot -i
cd cli/browser4-cli && cargo run -- drag e79 e82
```
Where e79 and e82 are listitem refs from the snapshot.

#### Issue 3: `dblclick` fails on non-focusable elements

```
cd cli/browser4-cli && cargo run -- snapshot -i --stdout
# Locate the double-click zone ref (e.g., e85)
cd cli/browser4-cli && cargo run -- dblclick e85
cd cli/browser4-cli && cargo run -- dblclick "#dblclickZone"
```

#### Issue 4: `drag` and `dblclick` use `ref` terminology but don't accept snapshot refs

Read the help output: `drag <startRef> <endRef>` and `dblclick <ref> [button]`. A user naturally interprets `<ref>` as a snapshot element reference (e5, e12, etc.) because that's the convention throughout the CLI. Attempting to use snapshot refs fails for both commands.

#### Issue 5: Dialog-triggering clicks hang and go to background, requiring separate `dialog-accept`

```
cd cli/browser4-cli && cargo run -- click "#alertBtn"
```
The command hangs because a browser dialog is blocking execution, gets sent to background, and requires a separate `dialog-accept` command to complete.

#### Issue 6: Tooltip hover verification is ambiguous from snapshot output

Hover over a tooltip-triggering element (`hover e62`), then take a snapshot to verify the tooltip appeared.

#### Issue 7: `cargo run --` from repo root fails (must cd into cli/browser4-cli first)

```
cd "D:/workspace/Browser4/Browser4-4.11"
cargo run -- --help
```

#### Issue 8: `generate-locator` produces simple ID selectors, not "resilient" selectors

```
cd cli/browser4-cli && cargo run -- generate-locator e97
```
Result: `#alertBtn`

#### Issue 9: `snapshot -i` (interactive) doesn't show how many viewports were skipped

Run `snapshot -i` on a page with 2 viewports.
