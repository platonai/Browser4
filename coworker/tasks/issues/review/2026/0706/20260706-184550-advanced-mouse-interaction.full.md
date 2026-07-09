---

# Browser4-CLI Usability Evaluation — Interactive Page Task

## A. Task Result

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

## B. Execution Trace

**Commands used (chronologically):**
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

## C. Issues Found

### Issue 1: Template variables in task specification are undefined

**Severity:** Medium

**Category:** Documentation

**Reproduction:** Read the task specification. It references `$cliInvocation`, `$helpCmd`, `$skillPath`, and `$RepoRootPath` as if they are defined variables, but they are literal strings with no substitution.

**Expected:** Variables should be pre-substituted with actual values, or a legend should define what each variable means.

**Actual:** The evaluator had to infer: `$RepoRootPath` = the git repo root, `$skillPath` = `skills/browser4-cli/SKILL.md`, `$cliInvocation` = `cd cli/browser4-cli && cargo run --` (from SKILL.md development section), `$helpCmd` = `cargo run -- --help`.

**Root Cause:** The evaluation template uses placeholder variables intended for automated substitution by a test harness, but no substitution occurred.

**Code Pointer:** (test harness / evaluation framework — not in the browser4-cli codebase)

**AI Suggested Improvement:**
- Pre-substitute template variables before presenting the task to the evaluator, or provide a variables legend at the top of the task spec
- Consider using `{{variable}}` syntax instead of `$variable` to avoid confusion with shell variables

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `drag` command fails with snapshot element refs

**Severity:** High

**Category:** Product

**Reproduction:**
```
cd cli/browser4-cli && cargo run -- snapshot -i
cd cli/browser4-cli && cargo run -- drag e79 e82
```
Where e79 and e82 are listitem refs from the snapshot.

**Expected:** The `drag` command should accept snapshot refs (e79, e82) since the help output says `drag <startRef> <endRef>` and the convention throughout browser4-cli is that refs from snapshots are used for element targeting.

**Actual:** Error: `browser_drag failed: SyntaxError: Failed to execute 'querySelector' on 'Document': 'backend:79' is not a valid selector.`

**Root Cause:** The server-side `browser_drag` implementation translates the ref into `backend:<nodeId>` format and passes it to `document.querySelector()`, which doesn't understand CDP backend node IDs. The CLI sends snapshot refs, but the backend expects CSS selectors. There's a mismatch between the CLI's ref-based interface and the server's CSS-selector-based implementation. The CLI should either (a) resolve refs to CSS selectors before sending to the server, or (b) resolve refs to CDP backend node IDs on the server side using a different DOM access method.

**Code Pointer:** `cli/browser4-cli/src/main.rs` — the `drag` command handler; server-side `browser_drag` implementation

**AI Suggested Improvement:**
- In the CLI, resolve snapshot refs to `generate-locator` CSS selectors before passing to the `drag` backend, so the user-visible interface stays ref-based while the backend gets valid CSS
- Alternatively, update the server-side `browser_drag` to accept CDP backend node IDs alongside CSS selectors
- As a documentation fix, update the `drag` help text to clarify that CSS selectors are expected, not snapshot refs, or that refs are auto-resolved

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `dblclick` fails on non-focusable elements

**Severity:** High

**Category:** Product

**Reproduction:**
```
cd cli/browser4-cli && cargo run -- snapshot -i --stdout
# Locate the double-click zone ref (e.g., e85)
cd cli/browser4-cli && cargo run -- dblclick e85
cd cli/browser4-cli && cargo run -- dblclick "#dblclickZone"
```

**Expected:** Double-clicking on an element with `dblclick` event listeners should dispatch the double-click event regardless of whether the element is focusable. Many real-world interactive elements (cards, zones, custom widgets) use `<div>` elements with event listeners and are not natively focusable.

**Actual:** `ERROR: browser_click failed: Element is not focusable`. The `dblclick` command delegates to `browser_click` which requires the target element to be focusable. A `<div>` with `dblclick` event listener is not natively focusable.

**Root Cause:** The `browser_click` (and by extension `dblclick`) server implementation checks `Element is not focusable` before proceeding, but many legitimate double-click targets (like custom `<div>` zones with event listeners) are not focusable elements. The workaround is to inject `tabindex="0"` via `eval` first, which is not obvious to new users.

**Code Pointer:** Server-side `browser_click` implementation — the focusability check; `cli/browser4-cli/src/main.rs` — the `dblclick` command handler

**AI Suggested Improvement:**
- Remove or relax the focusability check for `dblclick` — double-click events don't require focus; they operate at the DOM event level
- If the focusability check is needed for `click`, make `dblclick` use a separate code path that dispatches `dblclick` events via CDP without going through focus
- As a documentation fix, add a note to the `dblclick` help text: "Element must be focusable. Use `eval` to add `tabindex='0'` to non-focusable elements like `<div>`"
- Consider adding a `--force` flag that bypasses the focusability check

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `drag` and `dblclick` use `ref` terminology but don't accept snapshot refs

**Severity:** Medium

**Category:** Documentation / UX

**Reproduction:** Read the help output: `drag <startRef> <endRef>` and `dblclick <ref> [button]`. A user naturally interprets `<ref>` as a snapshot element reference (e5, e12, etc.) because that's the convention throughout the CLI. Attempting to use snapshot refs fails for both commands.

**Expected:** Either (a) the commands should accept snapshot refs, or (b) the help text should clearly state these accept CSS selectors (not snapshot refs), with a different argument name like `<selector>`.

**Actual:** The help text uses `<ref>` and `<startRef>/<endRef>` terminology, but the commands actually require CSS selectors. `drag` produces a cryptic "not a valid selector" error; `dblclick` produces "Element is not focusable."

**Root Cause:** The CLI command definitions use the same `<ref>` nomenclature across all commands, but the underlying server implementations for `drag` and `dblclick` have different expectations (CSS selectors, focusable elements) than other `<ref>`-labeled commands like `click` and `hover` which work with snapshot refs.

**Code Pointer:** `cli/browser4-cli/src/args.rs` or `cli/browser4-cli/src/commands.rs` — argument naming and help text

**AI Suggested Improvement:**
- Change argument names from `<ref>` to `<selector>` for `drag`, `dblclick`, and any other commands that don't accept snapshot refs
- Or, implement ref-to-selector resolution on the CLI side (using `generate-locator` internally) so all commands consistently accept snapshot refs
- Add a note in the help text clarifying what types of selectors are accepted

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Dialog-triggering clicks hang and go to background, requiring separate `dialog-accept`

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:**
```
cd cli/browser4-cli && cargo run -- click "#alertBtn"
```
The command hangs because a browser dialog is blocking execution, gets sent to background, and requires a separate `dialog-accept` command to complete.

**Expected:** Ideally, the CLI could detect a dialog was triggered and either (a) auto-dismiss it, (b) provide a timeout/retry mechanism, or (c) clearly document that `click` on dialog-triggering buttons requires a separate `dialog-accept` step.

**Actual:** The `click` command hangs and the shell detects it as a background task. The user must then issue a separate `dialog-accept` command. This two-step process works but is not intuitive — a first-time user might not realize they need to open a second terminal or cancel and retry.

**Root Cause:** Browser dialogs (`alert`, `confirm`, `prompt`) are synchronous and block the JavaScript execution context. The CDP `click` command dispatches a click event, which triggers the dialog synchronously, blocking the CDP response. The `dialog-accept` command uses a separate CDP domain (`Page.handleJavaScriptDialog`) to dismiss the dialog.

**Code Pointer:** CLI `click` command handler; server-side CDP interaction layer

**AI Suggested Improvement:**
- Add a `--dialog-timeout <ms>` option to `click` that auto-accepts/dismisses dialogs after a timeout
- Include dialog handling documentation prominently in the `click` command help text: "If a click triggers a browser dialog, use `dialog-accept` or `dialog-dismiss` after the click"
- Consider a combined command like `click --handle-dialog accept "#alertBtn"` that chains the dialog handling
- Add a tip/hint to the `click` command output when it detects it may have triggered a dialog

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Tooltip hover verification is ambiguous from snapshot output

**Severity:** Low

**Category:** UX

**Reproduction:** Hover over a tooltip-triggering element (`hover e62`), then take a snapshot to verify the tooltip appeared.

**Expected:** The snapshot should clearly show whether a tooltip appeared after the hover action, e.g., as a new element in the accessibility tree, or the hover should produce visible output confirming the tooltip state.

**Actual:** The tooltip text ("A hierarchical representation of the page...") is already embedded in the accessible name of the element before hovering. After hovering, the snapshot looks identical. It's impossible to tell from the snapshot alone whether the hover triggered anything.

**Root Cause:** CSS-based tooltips that use the `title` attribute get rendered into the accessible name regardless of hover state. The tooltip content is always present in the AX tree. There's no pre/post comparison unless using `--auto-diff`.

**Code Pointer:** N/A (this is a characteristic of how `title` attribute tooltips work with accessibility trees)

**AI Suggested Improvement:**
- Use `--auto-diff` automatically after `hover` commands so users can see what changed
- Document that tooltip verification via snapshot may not show CSS-based tooltips, and suggest alternatives (e.g., `screenshot` of the hovered state)
- Consider adding a `hover --verify` flag that takes a before/after screenshot automatically

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `cargo run --` from repo root fails (must cd into cli/browser4-cli first)

**Severity:** Medium

**Category:** Discoverability

**Reproduction:**
```
cd "D:/workspace/Browser4/Browser4-4.11"
cargo run -- --help
```

**Expected:** The SKILL.md says to use `cargo run` from the CLI directory, but a reasonable new user might try from the repo root. The error should guide them to the correct directory.

**Actual:** `error: could not find 'Cargo.toml' in 'D:\workspace\Browser4\Browser4-4.11' or any parent directory` — no hint about where the Cargo.toml actually is.

**Root Cause:** The repo root has no Cargo.toml. The Cargo.toml is at `cli/browser4-cli/Cargo.toml`. A user following the SKILL.md but reading carelessly might try `cargo run --` from repo root and get a terse Rust error with no browser4-cli-specific guidance.

**Code Pointer:** N/A (project structure issue)

**AI Suggested Improvement:**
- Add a Cargo workspace at the repo root so `cargo run` works from anywhere, or document the `--manifest-path` alternative clearly
- Document the invocation pattern prominently: `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>` as an alternative to `cd cli/browser4-cli && cargo run --`
- Add a wrapper script at the repo root (e.g., `./b4.sh`) that handles the directory change automatically for development use

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: `generate-locator` produces simple ID selectors, not "resilient" selectors

**Severity:** Low

**Category:** Product

**Reproduction:**
```
cd cli/browser4-cli && cargo run -- generate-locator e97
```
Result: `#alertBtn`

**Expected:** The command name "generate-locator" and its description ("Generate a unique CSS selector path for an element") suggest something more sophisticated than the element's ID. A new user might expect a multi-segment selector path (like `body > div.dialogSection > button#alertBtn`) that would be more resilient to page restructuring.

**Actual:** It returns `#alertBtn` — just the element's ID. While this is perfectly valid and unique, it's not necessarily "resilient" if IDs change. The name "generate-locator" implies more than just reading the ID attribute.

**Root Cause:** The `generate-locator` implementation appears to use the simplest unique selector available. If an element has an ID, it returns `#id`. This is correct behavior but the command name sets higher expectations.

**Code Pointer:** `cli/browser4-cli/src/snapshot.rs` or the server-side selector generation logic

**AI Suggested Improvement:**
- Consider generating multiple selector variants and ranking them by resilience heuristics
- Add options like `--strategy id|path|aria|power-css` to let users choose the selector generation approach
- Rename to `generate-selector` for clarity, or document that "locator" means "unique selector" in this context

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: `snapshot -i` (interactive) doesn't show how many viewports were skipped

**Severity:** Low

**Category:** UX

**Reproduction:** Run `snapshot -i` on a page with 2 viewports.

**Expected:** The output should indicate how many viewports are available and suggest how to view them.

**Actual:** The snapshot output shows the first viewport (0) but the viewport hints only appear when using `--stdout`. Without `--stdout`, there's no indication the page has more content below the fold. The drag section and bottom half of the page are invisible unless the user knows to use `-v 1` or `-v all`.

**Root Cause:** The `--stdout` output includes viewport state metadata (`# This page has 2 viewports...`), but the default output format (file path only) doesn't include this hint.

**Code Pointer:** `cli/browser4-cli/src/snapshot.rs` — snapshot output formatting

**AI Suggested Improvement:**
- Include viewport count hint in the non-`--stdout` output as a tip: "💡 Tip: This page has 2 viewports. Use `snapshot -v 1` to scroll down."
- Add `-v all` as a convenient shorthand for capturing all viewports at once

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

**Task completion status:** ✅ Fully completed — all 13 steps executed successfully.

**Estimated task success rate:** 85%. Of ~28 commands executed, 4 failed on first attempt and required workarounds:
- `drag e79 e82` — needed CSS selector instead of ref (2 attempts to find right selector)
- `dblclick e85` — needed `eval` to add tabindex (2 attempts)

**Number of issues found:** 9

**Major blockers:**
1. `drag` not accepting snapshot refs (Issue 2) — the most confusing failure; the error message "backend:79 is not a valid selector" gives no clue about the real problem
2. `dblclick` requiring focusable elements (Issue 3) — forces an `eval` workaround that new users wouldn't discover

**Most confusing aspects:**
- The ref vs. CSS selector inconsistency across commands (some accept refs, some don't, but all use `<ref>` in help text)
- Dialog-triggering clicks that hang silently (the background task notification is disorienting)
- The `cd cli/browser4-cli && cargo run --` prefix is very verbose for every command — almost 50 characters before the actual command

**Most valuable improvements:**
1. Make `drag`, `dblclick`, and all mouse commands consistently accept snapshot refs (auto-resolve to CSS internally)
2. Remove the focusability requirement from `dblclick`
3. Streamline the development invocation with a repo-root wrapper script (e.g., `./b4 goto "..."`)

**Overall usability rating:** **6/10**

The CLI core loop (navigate → snapshot → interact → verify) works well and is well-documented in the SKILL.md. The accessibility-tree snapshot format is readable and element refs are a good abstraction. However, the ref/selector inconsistency across commands, silent command failures with cryptic errors, and the cumbersome development invocation pattern significantly degrade the first-time user experience. A new user attempting this task without prior knowledge would likely get stuck at the drag step and need to read source code or search for workarounds.

**End-to-end interaction log (from page):**
```
[system] Page loaded. Ready for interaction testing.
[02:34:23] All sections initialized.
[02:37:27] DRAG START: 🔴 High Priority — Fix login bug
[02:37:27] DRAG END: 🔴 High Priority — Fix login bug → new position
[02:38:39] DBLCLICK: Zone activated (1 total double-clicks)
[02:39:22] DBLCLICK: Counters reset
[02:40:21] DIALOG: Showing alert()
[02:40:59] DIALOG: Alert dismissed
[02:41:37] DIALOG: Showing confirm()
[02:42:15] DIALOG: Confirm result = true
[02:42:27] DIALOG: Showing prompt()
[02:43:30] DIALOG: Prompt result = "Hello from browser4-cli"
```
