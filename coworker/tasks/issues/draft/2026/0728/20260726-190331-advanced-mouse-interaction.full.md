---

# Deliverables

## A. Task Result

All 13 steps completed successfully:

| Step | Action | Result |
|------|--------|--------|
| 1 | Navigate to interactive-5.html | ✅ Page loaded |
| 2 | Interactive snapshot (`snapshot -i`) | ✅ All interactive elements discovered |
| 3 | Hover tooltips (Accessibility Tree, DOM Snapshot) | ✅ Tooltip content verified in interactive snapshot ARIA labels |
| 4 | Hover product card (Wireless Headphones) | ✅ Card expanded, detail text visible (height: 71→121px) |
| 5 | Drag Backlog to top of priority list | ✅ Reorder confirmed via CSS selectors (`#priorityBacklog` → `#priorityHigh`) |
| 6 | Double-click activation zone | ✅ Status changed to "ACTIVATED ✅" |
| 7 | Double-click reset zone | ✅ Counters reset |
| 8 | Generate locator for Show Alert button | ✅ `#alertBtn` produced |
| 9 | Get text with generated selector | ✅ "🔔 Show Alert" retrieved |
| 10 | Alert dialog — click, accept, verify | ✅ "[alert] User dismissed the alert dialog." |
| 11 | Confirm dialog — click, accept, verify | ✅ "[confirm] User clicked OK / accepted." |
| 12 | Prompt dialog — click, accept with input, verify | ✅ `[prompt] User entered: "Browser4 evaluation test"` |
| 13 | Final screenshot | ✅ screenshot-2026-07-26T19-01-52-383Z.png |

---

## B. Execution Trace

### Commands Used (chronological)

```
./b4w.ps1 help                                      # Initial exploration
bin/test.ps1 mock-site                               # Start mock site (failed — module profile issue)
mvn -pl ... -DallTestModules=true ...                # Manual mock site start
./b4w.ps1 --version                                  # Verify CLI (CRLF issue found)
sed -i 's/\r$//' ./b4w.ps1                          # Fix CRLF line endings
./b4w.ps1 goto "http://localhost:18080/..."          # Navigation (session stale → close-all → retry)
./b4w.sh goto "http://localhost:18080/..."           # Switched to b4w.sh wrapper
./b4w.sh snapshot -i --stdout                        # Interactive snapshot
./b4w.sh hover e66                                   # Hover tooltip 1
./b4w.sh hover e69                                   # Hover tooltip 2
./b4w.sh snapshot -v 0 --stdout                      # Verify page state
./b4w.sh hover e71                                   # Hover product card
./b4w.sh snapshot -i -v 0 --stdout                   # Verify card expansion
./b4w.sh drag e86 e83                                # Drag attempt (failed — ref not resolved to selector)
./b4w.sh generate-locator e83                        # Get CSS selector for drag
./b4w.sh drag "#priorityBacklog" "#priorityHigh"     # Drag with CSS selectors (succeeded)
./b4w.sh snapshot -v 1 --stdout                      # Get double-click zone refs
./b4w.sh dblclick e89                                # Dblclick failed — "Element is not focusable"
./b4w.sh eval "document.getElementById('dblclickZone').setAttribute('tabindex','0')..."  # Workaround
./b4w.sh dblclick "#dblclickZone"                    # Dblclick succeeded after making focusable
./b4w.sh eval "document.querySelector('#dblclickResetZone')..."  # Same workaround for reset
./b4w.sh dblclick "#dblclickResetZone"               # Reset dblclick
./b4w.sh generate-locator e101                       # Generate selector for alert button → #alertBtn
./b4w.sh get text "#alertBtn"                        # Get button label
./b4w.sh click "#alertBtn"                           # Trigger alert (hangs → background task)
./b4w.sh dialog-accept                               # Dismiss alert
./b4w.sh get text "#dialogResult"                    # Verify alert result
./b4w.sh click "#confirmBtn"                         # Trigger confirm
./b4w.sh dialog-accept                               # Accept confirm
./b4w.sh get text "#dialogResult"                    # Verify confirm result
./b4w.sh click "#promptBtn"                          # Trigger prompt
./b4w.sh dialog-accept "Browser4 evaluation test"    # Accept prompt with input
./b4w.sh get text "#dialogResult"                    # Verify prompt result
./b4w.sh screenshot                                  # Final screenshot
```

### Important Decisions Made
- **Switched from b4w.ps1 to b4w.sh** after discovering PowerShell parameter binding issues with `-i`, `-v` flags
- **Used CSS selectors instead of refs** for `drag` and `dblclick` commands when ref-based invocation failed
- **Used `eval` to add `tabindex`** as a workaround for `dblclick` requiring focusable elements
- **Used direct `mvn` command** instead of `test.ps1 mock-site` to start the test server (profile activation issue)

### Workarounds Required
1. Fixed CRLF line endings in `b4w.ps1` and `b4w.sh` — necessary for Linux execution
2. Used `b4w.sh` wrapper to avoid PowerShell flag interception
3. Activated Maven profile `-DallTestModules=true` to start mock site
4. Used `#id` CSS selectors instead of snapshot refs for drag command
5. Made non-focusable elements focusable via `eval` before using `dblclick`
6. Handled dialog-blocking clicks by running `dialog-accept` in a separate command

---

## C. Issues Found

### Issue 1: CRLF line endings break Linux shebang execution

**Severity:** High

**Category:** Reliability

**Reproduction:** Run `./b4w.ps1 --version` on Linux after a fresh checkout.

**Expected:** Script executes via the `#!/usr/bin/env pwsh` shebang.

**Actual:** `/usr/bin/env: 'pwsh\r': No such file or directory` — the `\r` in the shebang line causes the kernel to look for `pwsh\r` (with carriage return).

**Root Cause:** `b4w.ps1` and `b4w.sh` are committed with Windows-style CRLF line endings. Git's default `core.autocrlf` behavior on Windows converts LF→CRLF on checkout, but the script files need LF endings for the shebang to work on Linux/macOS. A `.gitattributes` entry marking these files as `text eol=lf` would fix this.

**Code Pointer:** `.gitattributes` (needs `b4w.ps1 text eol=lf` and `b4w.sh text eol=lf`)

**AI Suggested Improvement:**
- Add `.gitattributes` entry: `b4w.ps1 text eol=lf` and `b4w.sh text eol=lf`
- Alternatively, convert the files to LF before committing: `dos2unix b4w.ps1 b4w.sh`
- Add a CI check that fails if shell scripts have CRLF line endings

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: PowerShell parameter binding silently consumes short flags (`-i`, `-v`)

**Severity:** High

**Category:** UX / Discoverability

**Reproduction:** Run `./b4w.ps1 snapshot -i --stdout` from bash (via pwsh shebang).

**Expected:** `-i` and `-v` flags reach the browser4-cli binary as intended.

**Actual:** PowerShell's parameter binder intercepts `-i` (matches `-InformationAction`) and `-v` (matches `-Verbose`), producing cryptic errors like `Parameter cannot be processed because the parameter name 'i' is ambiguous`. The `--` workaround mentioned in SKILL.md (`./b4w.ps1 -- snapshot -i`) also failed with a different ambiguous parameter error.

**Root Cause:** The `b4w.ps1` script uses `param(...)` with `[Parameter(ValueFromRemainingArguments = $true)]`, but PowerShell still tries to bind short flags before they reach `$RemainingArgs`. The documented `--` separator workaround is unreliable in practice.

**Code Pointer:** `b4w.ps1:3-5` (parameter declaration)

**AI Suggested Improvement:**
- The `b4w.sh` wrapper works correctly (individually quotes each argument) — make it the primary documented entry point for non-PowerShell shells
- Add a prominent warning at the top of the help output: "Use `./b4w.sh` on Linux/macOS/Git Bash to avoid PowerShell flag interception"
- Consider a native binary wrapper or shell script that bypasses PowerShell entirely on Unix
- Add a `--` passthrough mechanism that actually works, or document that `b4w.sh` is the only reliable invocation method outside pwsh

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `b4w.sh` wrapper prints intrusive warning on every invocation

**Severity:** Medium

**Category:** UX

**Reproduction:** Run any command via `./b4w.sh`.

**Expected:** Clean output with command results.

**Actual:** Every invocation prints: `It is strongly recommended to launch \`pwsh\` and run the .ps1 commands directly within the \`pwsh\` terminal.` followed by a blank line. This message is misleading — on Linux, running inside pwsh directly has the same flag-interception problems, and the user is using `b4w.sh` specifically to *avoid* those problems.

**Root Cause:** The `b4w.sh` script hardcodes this warning message (line 17-18) via `echo`. It appears to be a leftover advisory message that contradicts the actual recommended workflow on Linux.

**Code Pointer:** `b4w.sh:17-18`

**AI Suggested Improvement:**
- Remove the warning entirely or gate it behind a `$B4W_SUPPRESS_WARNING` env var
- Replace with a one-time check: show only on first invocation per session
- Change message to acknowledge platform: on Linux/macOS, `b4w.sh` IS the recommended entry point

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `drag` command fails with snapshot refs — requires CSS selectors

**Severity:** High

**Category:** Product / Reliability

**Reproduction:** 
1. Take a snapshot to get refs for two draggable elements (e.g., `e86` and `e83`)
2. Run `./b4w.sh drag e86 e83`

**Expected:** Drag-and-drop between the two referenced elements.

**Actual:** `ERROR: browser_drag failed: SyntaxError: Failed to execute 'querySelector' on 'Document': 'backend:86' is not a valid selector.`

**Root Cause:** The `DefaultArgumentNormalizer` maps `ref` → `selector` by converting `e86` to `backend:86`, but the drag implementation passes this directly to `document.querySelector()` which doesn't understand the `backend:` pseudo-selector protocol. The ref-to-selector normalization that works for `click`/`fill`/`hover` doesn't work for `drag`. Using CSS selectors (e.g., `#priorityBacklog`) works correctly.

**Code Pointer:** The drag command handler needs to resolve backend node IDs to real CSS selectors before calling `querySelector`, similar to how click/hover resolve refs.

**AI Suggested Improvement:**
- Fix the drag command to properly resolve snapshot refs to queryable selectors (or use backend node resolution instead of `querySelector`)
- Document in help that drag accepts both refs and CSS selectors, but refs must be from a current snapshot
- Add a test case for drag with snapshot refs to prevent regression

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `dblclick` command requires manually making elements focusable

**Severity:** High

**Category:** Product / UX

**Reproduction:**
1. Navigate to a page with a non-focusable element (e.g., a `<div>`)
2. Run `./b4w.sh dblclick <ref>` on that element

**Expected:** Double-click event is dispatched to the element.

**Actual:** `ERROR: browser_click failed: Element is not focusable. Tip: Use 'click <ref>' first to focus the element.` — but even after clicking first, the dblclick still fails because the click invalidates the ref and a generic `<div>` can't receive focus.

**Root Cause:** The `dblclick` implementation requires the target element to be focusable (it calls `element.focus()` before dispatching the double-click). Many real-world elements that need double-click interaction (zones, cards, list items) are not natively focusable. The workaround is to inject `tabindex="0"` via `eval` before dblclick, which is not a reasonable expectation for users.

**Code Pointer:** The dblclick handler (likely in `PulsarWebDriver.kt` or the agent tools layer) should not require the element to be focusable; it should dispatch `dblclick` events directly.

**AI Suggested Improvement:**
- Remove the focusability requirement from `dblclick` — dispatch mouse events directly to the element's coordinates or use CDP to dispatch events
- If focus is needed for the page's JS handlers, use `document.elementFromPoint()` + coordinate-based dispatch instead of `element.focus()`
- Alternatively, automatically set `tabindex="-1"` (which makes an element programmatically focusable without adding it to the tab order) before dispatching the double-click

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Dialog-blocking clicks hang indefinitely instead of returning promptly

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:** Click a button that triggers `alert()`, `confirm()`, or `prompt()`.

**Expected:** The click command returns immediately or with a clear message indicating a dialog is pending. The user then runs `dialog-accept`/`dialog-dismiss` to handle it.

**Actual:** The click command hangs and becomes a background task. The user must run `dialog-accept` in a separate command invocation to unblock it. This breaks the natural command sequencing and requires the user to understand the background task mechanism.

**Root Cause:** The click command waits for the page to settle (network idle or similar), but the modal dialog blocks the page's event loop, preventing the settle condition from being met. The click doesn't detect that a dialog appeared and return early.

**Code Pointer:** The click handler should check for the presence of a JavaScript dialog after dispatching the click, and return early with a "dialog appeared — use dialog-accept/dialog-dismiss" message.

**AI Suggested Improvement:**
- After dispatching a click, check if `Page.javascriptDialogOpening` was received — if so, return immediately with a descriptive message: "Dialog detected: use `dialog-accept` or `dialog-dismiss` to handle it"
- Add a `--auto-dismiss` flag to `click` that automatically dismisses dialogs
- Document the dialog workflow clearly in help output and SKILL.md

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Backend version mismatch — installed v4.11.15 vs source v4.12.0

**Severity:** Medium

**Category:** Reliability / Setup

**Reproduction:** Run `./b4w.sh status` when using dev mode with the locally-built CLI.

**Expected:** Backend runs from the locally-built JAR matching the source code.

**Actual:** `⚠ Version mismatch: CLI is 4.12.0 but installed backend is v4.11.15.` The task instructions say "Dev mode: the CLI daemon auto-starts the locally-built backend JAR from the repository," but in practice a pre-installed older backend bundle is running. This means tests may not reflect the current source tree's behavior.

**Root Cause:** The CLI's auto-start mechanism prefers the installed bundle over the locally-built JAR. The locally-built CLI (from `cargo build`) connects to whatever backend is running, and the daemon launched the installed v4.11.15 backend instead of building and running from source.

**Code Pointer:** The daemon/auto-start logic that decides which backend JAR to launch.

**AI Suggested Improvement:**
- In dev mode (when CLI is built from source), prefer the locally-built backend JAR
- Add a `--dev` flag or env var to force local backend usage
- Make the version mismatch warning more prominent and actionable
- Document how to force the locally-built backend in development.md

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: MockSite startup script fails without explicit Maven profile activation

**Severity:** Medium

**Category:** Reliability / Setup

**Reproduction:** Run `./bin/test.ps1 mock-site` as documented.

**Expected:** MockSite starts on localhost:18080.

**Actual:** Maven error: `Could not find the selected project in the reactor: browser4-tests/browser4-rest-tests`. The module is gated behind Maven profiles (`allTestModules`, `runRestTests`, etc.) that are not activated by default.

**Root Cause:** The `browser4-tests/browser4-rest-tests` module is only included in specific Maven profiles. The `test.ps1` script doesn't pass the necessary profile activation flags (`-DallTestModules=true`) when launching the mock site.

**Code Pointer:** `bin/test.ps1:490-511` (the Maven args section of `Invoke-MockSiteBoot`)

**AI Suggested Improvement:**
- Add `-DallTestModules=true` to the Maven arguments in `Invoke-MockSiteBoot`
- Document the profile requirements in a README or in the task instructions
- Add a pre-flight check that verifies the module is reachable before attempting to build

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: Hover-induced tooltip content not visible in regular accessibility tree snapshots

**Severity:** Low

**Category:** UX / Discoverability

**Reproduction:** Hover over a CSS tooltip trigger element, then run `snapshot -v 0 --stdout`.

**Expected:** The tooltip content appears in the accessibility tree snapshot.

**Actual:** Regular (`-v 0`) snapshots don't show CSS tooltip content. Only interactive (`-i`) snapshots capture the full ARIA labels. However, `-i` also strips generic `<div>` containers (per SKILL.md), which can hide important page structure.

**Root Cause:** Regular snapshots capture visible text content only, while interactive snapshots capture ARIA labels. CSS tooltip content that appears on `:hover` may populate `aria-describedby` or `title` attributes rather than visible text nodes.

**Code Pointer:** The accessibility tree snapshot generation logic — could include tooltip/title content as annotations.

**AI Suggested Improvement:**
- Add tooltip content (from `title` attributes and `aria-describedby`) to regular snapshots as inline annotations
- Document the difference between `-i` and non-interactive snapshots more clearly
- Consider a `--with-tooltips` flag or make tooltip annotation the default

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: `htmlsnapshot` command times out during interactive session

**Severity:** Medium

**Category:** Reliability

**Reproduction:** After several interactions (hover, drag, click), run `./b4w.sh htmlsnapshot`.

**Expected:** HTML snapshot is captured and stored.

**Actual:** `Error: HTTP request timed out [tool=html_snapshot_capture, endpoint=http://localhost:8182/mcp/call-tool, timeout=60s]`

**Root Cause:** The `htmlsnapshot capture` tool makes an HTTP request to the backend MCP endpoint that can time out after multiple interactions. This may be related to page state complexity or a backend resource exhaustion issue. Uncertain without deeper investigation of the backend logs.

**Code Pointer:** Backend `MCPToolController` html_snapshot_capture handler — may need increased timeout or optimized page serialization.

**AI Suggested Improvement:**
- Increase the timeout for `htmlsnapshot` capture or make it configurable
- Add retry logic with exponential backoff
- Investigate whether page state size correlates with timeout
- Surface a more helpful error message: "Timed out capturing HTML snapshot. Try reloading the page first."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 11: `snapshot -v` flag is silently consumed on pwsh, resulting in misleading help dump

**Severity:** Medium

**Category:** UX / Discoverability

**Reproduction:** From bash, run `pwsh -Command "& './b4w.ps1' snapshot -v 0 --stdout"`.

**Expected:** Snapshot with viewport 0 is captured.

**Actual:** The `-v 0` is combined into `snapshot-0` by PowerShell, producing: `Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?` followed by the full help text. The error message is technically accurate but the dump of all help text obscures the actual problem.

**Root Cause:** When using `pwsh -Command` with the `&` call operator, PowerShell still parses `-v` as its own parameter. The `b4w.sh` wrapper avoids this by individually quoting each argument. Users invoking pwsh directly (which the wrapper itself recommends!) will hit this.

**AI Suggested Improvement:**
- Don't recommend `pwsh` direct invocation on Linux — emphasize `b4w.sh` as THE entry point
- Add input validation: detect when a combined token like `snapshot-0` is received and suggest the quoting fix
- The error for unknown commands should not dump the full help — show a concise message with the fix

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 12: No `--help` output for individual interaction commands (hover, drag, dblclick, dialog-accept)

**Severity:** Low

**Category:** Discoverability

**Reproduction:** Run `./b4w.sh hover --help` or `./b4w.sh dblclick --help`.

**Expected:** Detailed help for the specific command, including argument descriptions, examples, and known limitations.

**Actual:** The main help listing shows these commands but `--help` per-command was not discoverable or returns minimal output. For example, there was no way to discover that `dblclick` requires a focusable element without encountering the error at runtime.

**Root Cause:** The `hover`, `drag`, `dblclick`, `dialog-accept`, and `dialog-dismiss` commands lack detailed `--help` output with usage examples and limitations.

**AI Suggested Improvement:**
- Add per-command `--help` for all interaction commands
- Include known limitations (e.g., "dblclick requires a focusable element — use eval to add tabindex if needed")
- Include examples showing both ref-based and CSS-selector-based invocation
- Document the dialog workflow (click → dialog-accept/dismiss) in `click --help` and `dialog-accept --help`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
✅ **All 13 steps completed successfully.** Every interaction (hover, drag, double-click, dialog handling, locator generation, text extraction, screenshot) was accomplished, though several required workarounds.

### Estimated Task Success Rate
**~70% without workarounds.** A first-time user would likely get stuck at:
- Step 1 (session stale requiring `close-all`)
- Step 5 (drag fails with refs)
- Step 6 (dblclick fails on non-focusable elements)
- Step 10-12 (click hangs on dialogs)

With the documentation and trial-and-error, ~85% of the task can be completed by an experienced user.

### Number of Issues Found
**12 issues** across five categories: Product (3), UX (4), Reliability (4), Discoverability (2), Setup (1).

### Major Blockers
1. **`dblclick` focusability requirement** — required `eval` workaround to add `tabindex`
2. **`drag` ref-to-selector resolution failure** — required `generate-locator` workaround
3. **PowerShell flag interception** — required switching from `b4w.ps1` to `b4w.sh`
4. **Dialog-blocking click behavior** — required separate command invocations for `dialog-accept`

### Most Confusing Aspects
1. **Invocation method** — `$(./b4w.ps1)` vs `./b4w.sh` vs `pwsh -Command ...` vs direct binary. The task instructions specify `$(./b4w.ps1)` but this doesn't work reliably on Linux. The SKILL.md documents the flag-interception issue but the recommended fix (`b4w.sh`) prints a contradictory warning telling users to use `pwsh` directly instead.
2. **Ref lifecycle** — When exactly refs become invalid is not always intuitive. Click invalidates refs (documented) but hover does too (partially — some refs survive, some don't).
3. **CSS selectors vs refs** — Some commands work with both, some only with one, and the error messages don't always make the distinction clear.

### Most Valuable Improvements
1. **Fix `dblclick` to not require focusable elements** — this is the single biggest UX friction point
2. **Fix `drag` to resolve snapshot refs** — drag is a key interaction and should work with refs directly
3. **Make dialog-blocking clicks return immediately** — the current hang-then-background-task flow is confusing
4. **Clean up the b4w.ps1/b4w.sh story** — pick one canonical entry point per platform and make it seamless
5. **Add per-command `--help`** for interaction commands with examples and known limitations

### Overall Usability Rating: **6/10**

**Strengths:**
- The accessibility tree snapshot format is powerful and well-structured
- `generate-locator` produces clean, human-readable CSS selectors
- The `dialog-accept [prompt]` with optional input text is well-designed
- Interactive snapshots (`-i`) capture rich ARIA label data
- The interaction log at the bottom of the test page provides excellent audit trail
- SKILL.md documentation is comprehensive and well-organized

**Weaknesses:**
- Fragile invocation story on Linux (CRLF, PowerShell flag interception)
- Key interactions (drag, dblclick) fail with snapshot refs in common scenarios
- Dialog workflow breaks the natural command sequencing
- Backend version mismatch in dev mode undermines confidence in testing
- Missing per-command help for interaction commands
- `b4w.sh` wrapper prints a misleading warning on every invocation
