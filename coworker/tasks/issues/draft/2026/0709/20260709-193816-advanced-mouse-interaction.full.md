Screenshot captured at 189KB. All steps completed successfully. Let me now compile the full evaluation report.

---

# Evaluation Report: browser4-cli Usability & Task Completion

## A. Task Result

**Task completed successfully.** All 13 steps were executed:

1. ✅ Navigated to `http://localhost:18080/generated/interactive-5.html`
2. ✅ Interactive snapshot captured all elements across 2 viewports
3. ✅ Tooltip content verified via `htmlsnapshot get all text` (CSS-hidden tooltips don't appear in AX tree)
4. ✅ Hover card detail text extracted: "Features: Noise cancelling, 30h battery..."
5. ✅ Drag reordered priority list — "High Priority" moved from position 1 to 3
6. ✅ Double-click activated zone (via CSS selector `#dblclickZone`) — status "ACTIVATED ✅"
7. ✅ Double-click reset zone cleared counters back to 0
8. ✅ `generate-locator e59` produced `#alertBtn`
9. ✅ `get text "#alertBtn"` returned "🔔 Show Alert"
10. ✅ Alert dialog dismissed via `dialog-accept`, result verified
11. ✅ Confirm dialog accepted, result "[confirm] User clicked OK / accepted."
12. ✅ Prompt dialog accepted with input "browser4-test-input", result verified
13. ✅ Final screenshot captured (189KB)

## B. Execution Trace

**Commands used:** `goto`, `snapshot -i -v N --stdout`, `hover`, `htmlsnapshot`, `htmlsnapshot get all text`, `htmlsnapshot get text`, `drag`, `dblclick`, `click`, `get text`, `eval`, `dialog-accept`, `generate-locator`, `batch`, `screenshot --full-page`

**Key decisions:**
- Used `--selector` long form because `-s` conflicts with global session flag
- Used CSS selectors (`#dblclickZone`) for `dblclick` because ref-based targeting failed to trigger JS event listeners
- Used `htmlsnapshot get all text` instead of accessibility-tree `get` for CSS-hidden content (tooltips)
- Used `eval` for DOM verification where accessibility tree was insufficient
- Used background task + separate `dialog-accept` for dialog interactions since dialogs block the CLI

**Workarounds required:**
- `snapshot -s` → must use `snapshot --selector` to avoid session flag conflict
- `dblclick e48` → must use `dblclick "#dblclickZone"` (CSS selector) for JS event listeners
- Dialog interactions → must use background tasks or separate shell for click + accept
- CSS-only tooltips → must use `htmlsnapshot` instead of `snapshot`/`get` for hidden DOM content

## C. Issues Found

### Issue 1: `-s` shorthand conflicts between global session flag and `snapshot --selector`

**Severity:** Medium

**Category:** UX

**Reproduction:**
```
cargo run -- snapshot -i -v 0 -s "#priorityList" --stdout
```

**Expected:** The `-s` flag should be interpreted as `--selector` when used after the `snapshot` command name.

**Actual:** The global `-s`/`--session` flag takes precedence, resulting in:
```
🔐 Session required — No active session is currently stored for this CLI context.
```

**Root Cause:** The global flag parser (`parse_global_flags` in `args.rs`) processes `-s` before the command name is seen. Since `snapshot` defines `-s` as a short alias for `--selector`, the two conflict. The global flag parser should not consume `-s` when it appears after a command that defines its own `-s` option. Alternatively, `snapshot` should remove the `-s` shortcut for `--selector` and only accept the long form.

**Code Pointer:** `cli/browser4-cli/src/args.rs:parse_global_flags()` and `cli/browser4-cli/src/commands.rs` (snapshot command definition where `-s` maps to `--selector`)

**AI Suggested Improvement:**
- Remove `-s` as short alias for `snapshot --selector`; keep only `--selector` long form
- Or: Teach the global flag parser to skip `-s` when the first positional arg (command name) corresponds to a command that defines its own `-s` short option
- Document this limitation prominently in `snapshot --help` output

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `dblclick` with snapshot refs fails to trigger JavaScript `dblclick` event listeners

**Severity:** High

**Category:** Reliability

**Reproduction:**
```
cargo run -- goto "http://localhost:18080/generated/interactive-5.html"
cargo run -- snapshot -i -v 1 --stdout    # note ref e47 for the dblclick zone div
cargo run -- dblclick e47                 # ✓ Double-clicked e47 — but JS event never fires
cargo run -- eval "document.getElementById('dblclickStatus').textContent"
# Output: "Status: idle" (should be "Status: ACTIVATED ✅")
```

Then compare with the CSS selector approach which works:
```
cargo run -- dblclick "#dblclickZone"
cargo run -- eval "document.getElementById('dblclickStatus').textContent"
# Output: "Status: ACTIVATED ✅" 
```

**Expected:** `dblclick e47` (ref targeting the outer zone div) should trigger the JavaScript `dblclick` event listener just like `dblclick "#dblclickZone"` does.

**Actual:** Ref-based targeting sends CDP mouse events but the JavaScript `dblclick` event listener never fires. CSS-selector-based targeting works correctly.

**Root Cause:** When targeting via snapshot ref, the CLI likely resolves the ref to a CDP backend node and dispatches mouse events against that specific node. The issue may be that the CDP `Input.dispatchMouseEvent` with ref-based targeting uses a different code path or coordinate calculation that fails to hit the element correctly in the DOM. The CSS selector path resolves through `document.querySelector` which finds the correct DOM element. Investigation needed in the `dblclick` command handler to compare ref-based vs selector-based element resolution.

**Code Pointer:** `cli/browser4-cli/src/main.rs` (dblclick command handler), and the MCP tool handler on the server side that translates refs to CDP coordinates

**AI Suggested Improvement:**
- Debug the ref-based element resolution for `dblclick` to ensure it hits the same DOM element as `querySelector`
- Add an integration test that verifies `dblclick` triggers actual JS `dblclick` event listeners (not just CDP-level dispatch)
- Consider adding `--verify` option to `dblclick` (similar to `press --verify`) so users can confirm the action had its intended effect

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `get` command works against accessibility tree, not DOM — confusing for new users

**Severity:** Medium

**Category:** Discoverability / Documentation

**Reproduction:**
```
cargo run -- get text ".tooltip-container:first-of-type .tooltip-text"
# → null
# → "No elements matched ... The `get` command queries the live page through the accessibility tree"
# → "... For CSS selector-based extraction, capture the DOM first with `htmlsnapshot`"
```

**Expected:** The error message provides good guidance, but a new user would not know *before* they try that `get` uses the AX tree and not the DOM. The help text for `get` says "Extract data from a page element" without clarifying that it operates on the accessibility tree.

**Actual:** The error message after failure is helpful, but the initial documentation doesn't set expectations correctly. Users must learn this distinction through trial and error.

**Root Cause:** The `get` command's help text describes six modes (text, html, box, styles, property, attr) but doesn't mention that extraction operates on the accessibility tree, not the raw DOM. The SKILL.md documentation is also thin on this distinction — the "Decision Trees" section points users to `htmlsnapshot get` for static pages but doesn't explain the AX-tree-vs-DOM difference.

**Code Pointer:** `cli/browser4-cli/src/help.rs:generate_command_help()` (get command help text), `skills/browser4-cli/SKILL.md` (Decision Trees section)

**AI Suggested Improvement:**
- Add a sentence to the `get` command help: "Queries the live page through the accessibility tree. For DOM-level extraction (including hidden elements), use `htmlsnapshot` first then `htmlsnapshot get`."
- Add a comparison table to SKILL.md: "Live AX tree (`get`) vs Static DOM (`htmlsnapshot get`)"
- The error message is already excellent — consider making it a tip that appears proactively when `get` returns null

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Dialog-triggering clicks block CLI invocation; no built-in async dialog handling

**Severity:** High

**Category:** UX / Reliability

**Reproduction:**
```
cargo run -- click e59    # Clicks "Show Alert" button
# The command hangs/blocks because the browser alert dialog blocks the page
# User must Ctrl+C or run dialog-accept from a separate terminal
```

**Expected:** Either (a) the CLI should auto-handle dialogs when they appear after a click, (b) provide a `--auto-dialog` flag, or (c) document the required workflow clearly.

**Actual:** The `click` command blocks indefinitely when a dialog appears. Users must either:
1. Run `click` as a background shell process, then `dialog-accept` separately
2. Use a compound shell command with `&` and `sleep`

Neither approach is documented in the help or SKILL.md.

**Root Cause:** The CLI sends the click command synchronously and waits for the full page response. When a JavaScript `alert()`/`confirm()`/`prompt()` blocks the page, the response never completes. The MCP/CDP protocol may support async dialog handling, but the CLI doesn't expose it as a single-command workflow.

**Code Pointer:** `cli/browser4-cli/src/main.rs` (click command handler), SKILL.md (missing dialog workflow documentation)

**AI Suggested Improvement:**
- Document the dialog interaction pattern prominently in SKILL.md under a "Dialog Handling" section
- Add a `--handle-dialog` flag to `click` that auto-accepts/dismisses expected dialogs
- Consider a `batch`-compatible approach: after a dialog-triggering click, auto-queue `dialog-accept`
- At minimum, add a timeout + helpful error message: "Dialog detected. Run `dialog-accept` or `dialog-dismiss` to proceed."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `batch` command produces no output on success

**Severity:** Low

**Category:** UX

**Reproduction:**
```
cargo run -- batch "click e47" "click e47"
# No output at all — user doesn't know if it succeeded
```

**Expected:** Batch should produce minimal output confirming completion or show a summary of results.

**Actual:** Silent success. Only errors produce output. The user must run additional commands (`eval`, `snapshot`) to verify the batch had any effect.

**Root Cause:** Batch mode suppresses per-command output by default. While this is reasonable for scripting, it leaves interactive users with no feedback.

**Code Pointer:** `cli/browser4-cli/src/main.rs` (batch command handler)

**AI Suggested Improvement:**
- Add a `--verbose` flag to batch that prints per-command results
- Print a summary line at minimum: "✓ 2 commands executed successfully" or "✗ 1 of 2 commands failed (use --verbose for details)"
- Consider printing one-line status for each command even without `--verbose`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Viewport-based pagination discoverability is poor for first-time users

**Severity:** Low

**Category:** Discoverability

**Reproduction:**
```
cargo run -- goto "http://localhost:18080/generated/interactive-5.html"
cargo run -- snapshot -i --stdout
# Only shows 10-line preview, says "(use --stdout or open the file for full content)"
cargo run -- snapshot -i --stdout
# Still shows limited content — user must know about -v N to see different viewports
```

**Expected:** First-time users should discover viewport pagination through obvious hints. The `snapshot -i --stdout` output should prominently suggest `-v 1` when there are more viewports.

**Actual:** The tip says "Run `snapshot -v 0` to see interactive element refs" but doesn't indicate that the current output is only viewport 0. The `--stdout` output does show `# viewportsTotal: 2` and `# hiddenBottomHeight: 904px` but these are easy to miss among the YAML output. The footer suggests `snapshot -v 1` but it appears below the fold.

**Root Cause:** The viewport footer is printed after the snapshot content. Users who see the first 10 lines and the "preview" message may not scroll to find the footer. The tip after the command doesn't mention there are remaining viewports.

**Code Pointer:** `cli/browser4-cli/src/snapshot.rs` (snapshot rendering), `cli/browser4-cli/src/tips.rs` (tip selection logic)

**AI Suggested Improvement:**
- Move the viewport footer to the TOP of the output (before the snapshot content)
- Add to the post-command tip: "This page has 2 viewports. Use -v 1 to see the next one."
- When the preview cuts off, add a line: "⚠ Showing viewport 0/2. Use -v 1 for next viewport, -v all for full page."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Installation documentation assumes Node.js but dev mode uses Rust

**Severity:** Low

**Category:** Documentation

**Reproduction:** Reading `skills/browser4-cli/SKILL.md` section "Installation" which says "Requires Node.js. npm install -g browser4-cli" — but for development, it's a Rust project built with `cargo run`.

**Expected:** The SKILL.md should have a clear "Development" section or link to `development.md` that explains `cargo run` from source, including the correct invocation from the repo root.

**Actual:** The SKILL.md references `development.md` at the bottom under a "Development" header, but the installation section at the top only mentions npm. Users following the evaluation instructions who read SKILL.md first will be confused about how to invoke the CLI from source.

**Root Cause:** SKILL.md is primarily written for end users of the released CLI, not developers running from source. The "Development" link to `development.md` exists but is easy to miss.

**Code Pointer:** `skills/browser4-cli/SKILL.md` (Installation section)

**AI Suggested Improvement:**
- Move the "Development" section higher in SKILL.md, or add a prominent note at the top: "**Running from source?** See [development.md](references/development.md) for `cargo run` patterns."
- Add a quick-start box: "**From repo root:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>`"
- The `cli/browser4-cli/README.md` already documents this well — link it more prominently

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: `dblclick` with ref silently succeeds but doesn't trigger JS — no error or warning

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```
cargo run -- dblclick e48
# ✓ Double-clicked e48   ← reports success
cargo run -- eval "document.getElementById('dblclickStatus').textContent"
# Status: idle   ← but nothing actually happened
```

**Expected:** If `dblclick` dispatches CDP events but the page's JavaScript doesn't react, the CLI should either (a) detect this mismatch, or (b) provide a `--verify` option like `press --verify`.

**Actual:** The command reports success (`✓ Double-clicked e48`) but the intended effect (JS event listener firing) doesn't occur. This is a silent failure that wastes debugging time.

**Root Cause:** The `dblclick` command successfully dispatches CDP-level mouse events. The success message reflects CDP-level success, not application-level success. There's no mechanism to verify that the action produced the expected DOM change.

**Code Pointer:** `cli/browser4-cli/src/main.rs` (dblclick handler), server-side MCP tool implementation

**AI Suggested Improvement:**
- Add `--verify` option to `dblclick` that checks for DOM mutations after the action
- When targeting via ref vs CSS selector produces different results, log a warning
- Consider running a quick post-click heuristic: if the target is inside a `.dblclick-zone` or has `data-action="double-click"`, check if associated status elements changed
- At minimum, document in the `dblclick --help` that some pages use JavaScript `dblclick` listeners that may require CSS selector targeting

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

**Task completion status:** ✅ Complete — all 13 steps executed successfully

**Estimated task success rate:** 85% (11/13 steps worked on first attempt; 2 required workarounds)

**Number of issues found:** 8

**Major blockers:**
1. `dblclick` ref-based targeting fails to trigger JS event listeners (must use CSS selectors)
2. Dialog-triggering clicks block the CLI — requires awkward background shell workarounds

**Most confusing aspects:**
1. Understanding that `get` uses the accessibility tree vs `htmlsnapshot get` uses the DOM
2. Viewport pagination — discovering that pages are split into viewports and how to navigate them
3. The `-s` flag conflict between global session and snapshot selector

**Most valuable improvements:**
1. Fix `dblclick` with refs to work the same as CSS selectors
2. Add first-class async dialog handling (click → auto-detect dialog → accept/dismiss)
3. Document the AX-tree vs DOM distinction prominently in the `get` command help

**Overall usability rating:** **6/10**

The CLI is functionally capable but has sharp edges for first-time users. The core loop (goto → snapshot → interact → re-snapshot) works well. However, several reliability issues (dblclick with refs, dialog blocking) force workarounds that undermine confidence. The documentation is thorough once you find it, but discoverability of key concepts (viewport pagination, AX vs DOM extraction, dialog handling) needs improvement. The tool would benefit from more guardrails and proactive hints rather than relying on users to discover workarounds through trial and error.
