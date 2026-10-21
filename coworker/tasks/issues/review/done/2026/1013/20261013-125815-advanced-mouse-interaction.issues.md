# Issues: advanced-mouse-interaction

> **Source:** `20261013-125815-advanced-mouse-interaction.issues.json` | **Date:** 20261013-125815 | **Mode:** dev

## Scenario Background

### Task

All 13 task steps completed successfully on the MockSite fixture `http://localhost:18080/generated/interactive-5.html`:

1. **Navigated** with `./b4w.ps1 -s eval5 goto ...` — the daemon auto-started the local dev backend (workspace `Browser4-4.14`, port 8283) in 14.7 s.
2. **Interactive snapshot** taken — but `snapshot -i` turned out to be unusable for the task (see Issue 3); fell back to the default `-v 0` snapshot, which yielded the refs (e83/e85 tooltips, e87/e89 cards, e40/e42/e44/e46 list items, e91/e96 zones, e64-e70 buttons).
3. **Tooltips**: hovered `e83` ("Accessibility Tree") and `e85` ("DOM Snapshot"); verified `visibility: visible / opacity=1` via `eval` (as the docs prescribe for hover-revealed content) and confirmed the tooltip text in a snapshot (`snapshot grep`).
4. **Hover card**: hovered `e87`; `.card-detail` grew `0 → 53 px` (max-height 100 px), text read back as "Features: Noise cancelling, 30h battery, Bluetooth 5.3. …"; collapsed back to 0 after `mousemove 5 5`.
5. **Drag reorder**: `drag e40 e46 --at bottom` → "Dropped li#priorityHigh as child 4 of 4"; also dragged Backlog to the top (`drag e46 e42 --at top` → child 1 of 4). Order verified via eval: `backlog > medium > low > high`.
6. **Double-click activate**: `dblclick e91` → `Status: ACTIVATED ✅`, `single=2 double=1`, class `activated`.
7. **Double-click reset**: `dblclick e96` → counters `0/0`, status back to `idle`.
8. **generate-locator** on the alert button ref `e64` → `#alertBtn`.
9. **get text "#alertBtn"** → `🔔 Show Alert`.
10. **Alert**: click returned the documented "dialog pending" error; `dialog-status` returned `{"pending":true,"type":"alert",...}`; `dialog-accept` → result area updated to `[alert] User dismissed the alert dialog.` (also verified the one-step `click --auto-dismiss-dialogs` shortcut).
11. **Confirm**: click + `dialog-dismiss` → `[confirm] User clicked Cancel / declined.`
12. **Prompt**: click + `dialog-accept "Eval Session Alpha"` → `[prompt] User entered: "Eval Session Alpha"`.
13. **Final screenshot** captured full-page including the interaction log (saved to the run scratch dir; also re-taken after a second drag to include the newest log entries).

### Execution Context

**Setup:** `./bin/test.ps1 mock-site` (background; cold Maven preflight took ~13 min, then Spring Boot booted MockSite on :18080 — HTTP 200 verified with curl). `./b4w.ps1 help`, `skills/browser4-cli/SKILL.md` (full), plus `quick-patterns.md` and `--help` for hover/drag/dblclick/dialog-accept/generate-locator/screenshot.

**Command sequence (all via `./b4w.ps1`, session `-s eval5`, headless):** `goto` → `snapshot -i --stdout` (insufficient refs) → `snapshot -v 0 --stdout` → `hover e83` → `eval getComputedStyle…` → `hover e85` → `eval` → `snapshot grep "hierarchical representation|static capture"` → `hover e87` → `eval clientHeight` → `get text "#productCard .card-detail"` → `mousemove 5 5` → `eval` → `drag e40 e46 --at bottom` → `eval` (order) → `get text "#interactionLog"` → `dblclick e91...

(truncated — see 20261013-125815-advanced-mouse-interaction.issues.json for the complete execution trace)

---

## Issues Found (5 issues)

### Issue 1: click/hover/dblclick silently report success when the target element does not exist

**Severity:** High
**Category:** Reliability

#### Reproduction

1. ./b4w.ps1 -s eval5 click e987654321   (nonexistent ref)
2. ./b4w.ps1 -s eval5 click "#noSuchButton"   (nonexistent CSS selector)
3. ./b4w.ps1 -s eval5 hover "#noSuchThing"
4. ./b4w.ps1 -s eval5 dblclick e987654321
Compare with the read/DOM commands: ./b4w.ps1 -s eval5 get text e987654321  and  ./b4w.ps1 -s eval5 fill e987654321 "x"

#### Expected Behavior

A loud failure with a non-zero exit code and a message naming the unresolved target (as the `get` path does: "Element not found for ref backend:987654321. Refs expire after page changes — re-run `snapshot` to get fresh refs.")

#### Actual Behavior

All four commands print a success tick ("✓ Clicked e987654321", "✓ Clicked #noSuchButton", "✓ Hovered #noSuchThing", "✓ Double-clicked e987654321") and exit 0 while nothing is clicked/hovered. Verified by inspecting the page: the fixture's interaction log records no new events and no page state changes. In contrast, `get`, `fill` and `select` with the same unresolvable ref exit 1 with explicit errors.

#### Root Cause Analysis

In Browser4WebDriver.click(selector, count), movePointerToClickTarget() is explicitly best-effort and returns null when the selector cannot be resolved ("the pointer is left alone and the upstream click proceeds as before"). That makes clickTrusted() return false, so the code falls back to super.click() — the upstream pulsar-browser synthetic DOM click path — which no-ops silently for an unresolvable ref/CSS selector. No branch of the left-click path validates that the target resolved. dblclick uses the same movePointerToClickTarget/clickTrusted/super fallback chain and hover inherits the upstream silent path, while fill/select/get resolve through validating upstream helpers that throw.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:1606 (override suspend fun click), :1484 (movePointerToClickTarget), :1688 (clickTrusted)`

#### AI Suggested Improvement

- Validate resolution before the fallback: if movePointerToClickTarget() returns null and clickTrusted() also fails, re-resolve the selector with the same locator path `get`/`fill` use and throw WebDriverException("Element not found for ref …") instead of calling super.click().
- Alternatively, make the upstream fallback itself verify: probe document.querySelector/backend node existence after the DOM click and fail the command when nothing was pressed.
- Apply the same guard to hover and dblclick so the whole mouse-interaction family has one consistent fail-loud contract.
- Add e2e coverage: click/hover/dblclick with (a) an expired eN ref and (b) a typo'd CSS selector must exit non-zero.
- Treat a success tick for a missing target as a contract violation — automation loops (and AI agents) that rely on the tick will silently diverge from the page.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: drag dispatches the full drag lifecycle 3 times per invocation (repeat(3) retry loop never stops on success)

**Severity:** Medium
**Category:** Product

#### Reproduction

1. ./b4w.ps1 -s eval5 eval "document.getElementById('interactionLog').textContent.split('DRAG START').length-1"  → 3
2. ./b4w.ps1 -s eval5 drag e46 e42 --at top   (fixture logs DRAG START/DRAG END per dragstart/dragend)
3. Repeat the eval → 6 (exactly +3 per drag command); the fixture log also shows three full DRAG START→DRAG END pairs with ~1 s spacing for the single command. Reproduced for both drags performed in this session.

#### Expected Behavior

One drag command dispatches one dragstart → dragover… → drop → dragend lifecycle (the log should gain one DRAG START/DRAG END pair).

#### Actual Behavior

Every drag command runs the whole page-side drag sequence three times: the fixture's interaction log gains exactly 3 DRAG START/DRAG END pairs per command (20:49:46/47, :47/48, :48/50 for the first drag). The command still reports a single correct placement summary.

#### Root Cause Analysis

Browser4WebDriver.dragAt() wraps the page-side sequence in `repeat(3) { attempt -> ... }`. On a successful dispatch it sets dragSucceeded = true and executes `return@repeat`, which in Kotlin returns from the current lambda invocation — i.e. it behaves like `continue`, not `break` — and there is no `if (dragSucceeded) return@repeat` guard at the top of the loop. The retry loop therefore always runs all three attempts, each dispatching the full drag lifecycle, unless an exception aborts it. Observable page effects: tripled dragstart/dragend/dragover events for one logical drag; pages with non-idempotent drag handlers (upload-on-drop, counters, analytics beacons, undo stacks) execute their side effect three times.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:3145 (dragAt) — the repeat(3) retry block at lines ~3210-3267`

#### AI Suggested Improvement

- Replace the `repeat(3) { attempt -> … }` loop with a plain `for (attempt in 0 until 3)` loop and use `break` on success (or keep repeat and add `if (dragSucceeded) return@repeat` at the loop head).
- Keep the existing 'source already moved → treat as success' fallback; only attempt 2/3 should run when dispatch actually failed transiently.
- Add a regression test asserting exactly one drag lifecycle dispatch when the first attempt succeeds (the fixture's DRAG START counter is a ready-made assertion target).
- Consider surfacing a debug/warn log when a retry actually runs so pages that log duplicated drags can be explained quickly.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: snapshot -i omits refs for draggable and hover-target elements, and -i -v <n> keeps only cursor:pointer elements — contradicting the documented 'not a strict interactive-only filter'

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. ./b4w.ps1 -s eval5 snapshot -i --stdout  → the four priority list items appear as bare lines `- text: 🔴 High Priority — Fix login bug` with NO refs, and the tooltip terms appear only as loose text fragments; the hover cards and buttons keep refs.
2. ./b4w.ps1 -s eval5 snapshot -i -v 0 --stdout → only the two hover cards (both [cursor=pointer]) are returned, though viewport 0 also contains the header, tooltip terms, the drag list and the double-click zone.
3. ./b4w.ps1 -s eval5 snapshot -i -v 1 --stdout → only the double-click zones and the four dialog buttons; the dialog-result text and interaction log are absent.
Compare: ./b4w.ps1 -s eval5 snapshot -v 0 --stdout shows refs for everything (e40/e42/e44/e46 list items, e83/e85 tooltip terms).

#### Expected Behavior

Per `browser4-cli --help snapshot` and SKILL.md §5: '-i, --interactive — Interactive-oriented rendering: merge inner text into element names so ref lines read as self-contained targets (not a strict interactive-only filter)' and 'Pair with -v 0 (snapshot -i -v 0) for one focused screenful' — i.e. a full tree whose interactive lines are directly addressable.

#### Actual Behavior

`-i` behaves as a strict interactive-only filter whose heuristic covers cursor:pointer elements (buttons, cards) but not `li[draggable=true]` (cursor:grab) or tooltip spans (cursor:help): the very elements this page's documented interactions target lose their refs, and with `-v` they disappear entirely. A first-time user following the skill's recommendation ('snapshot -i — quick orientation before acting via refs') cannot act on the tooltip terms or reorder the list and must re-snapshot in another mode (extra round-trip, and the wrong first impression of the tool).

#### Root Cause Analysis

The CLI forwards `interactive=true` to the backend ariaSnapshot tool, whose own argument spec says 'Keep only interactive nodes.' (a strict filter). The filter's interactivity heuristic is pointer-cursor/role-based and does not treat draggable list items or hover tooltip spans as interactive, so their refs are dropped (their text survives only as merged text lines). The user-facing help text in the CLI (and SKILL.md) claims the opposite ('not a strict interactive-only filter'; 'it does not reduce the tree to buttons/links'), so documentation and behavior disagree. The rendering itself lives in the pulsar-browser library; the CLI help text is local.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:1668 and cli/browser4-cli/src/main.rs:7015 (help text) — behavior: browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:458/1649 ('Keep only interactive nodes' → AriaSnapshotOptions.interactive, renderer in the pulsar-browser library)`

#### AI Suggested Improvement

- Fix the help/SKILL wording first: describe -i as 'interactive-only rendering (buttons/links/inputs/pointer elements), inner text merged into names', and drop the 'not a strict filter' clause.
- Additionally consider extending the interactive heuristic to include draggable elements and elements with hover-revealed content, or at least keep a ref line for any element the default tree would address, so `-i` remains usable as a pre-action orientation view.
- Document the -i + -v combination explicitly, since the two flags together change the result from 'filtered full tree' to 'pointer elements in one screenful'.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: list shows URL '-' for live, reachable sessions; documented meaning of '-' says backend unreachable or session stale

**Severity:** Low
**Category:** Product

#### Reproduction

1. ./b4w.ps1 -s eval5 goto http://localhost:18080/generated/interactive-5.html
2. ./b4w.ps1 list        → row: eval5 | ... | - | Active | Headless | ... | Browser4 | Reuse
3. ./b4w.ps1 list --json → {"name":"eval5", ..., "url":"-", "status":"active", ...}, "backend_reachable":true
4. ./b4w.ps1 -s eval5 page-info → URL: http://localhost:18080/generated/interactive-5.html
5. Reproduced with an `open --headless`-created session too (also '-' while Active/Reuse).

#### Expected Behavior

`list` shows the session's current page URL (the docs say the URL column comes from the backend and `-` only means 'the backend is unreachable or the saved session is stale').

#### Actual Behavior

Both live sessions show `-` even though the backend is reachable (backend_reachable: true, status Active, Next open Reuse) and `page-info` on the same session prints the correct URL. `list --verbose` and `list --json` show `-` as well. A user reading the documented semantics would conclude the session/backing backend is dead and possibly run `close-all`, destroying a perfectly good session.

#### Root Cause Analysis

collect_namespace_sessions() fills SessionRow.url from the backend session record (`backend_record.url`), falling back to "-" when that field is absent/empty. In this dev-mode run the backend record carries no URL for sessions created by either `goto` or `open`, even though the CLI itself has a working `current_session_url()` helper (used by page-info/status paths) that returns the live URL.

#### Code Pointer

`cli/browser4-cli/src/main.rs:5105-5120 (collect_namespace_sessions — url: backend_record…unwrap_or_else("-")), fallback candidate cli/browser4-cli/src/main.rs:4569 (current_session_url)`

#### AI Suggested Improvement

- When the backend record has no URL, fall back to current_session_url() for locally-known active sessions before printing '-'.
- If a fallback is not possible, distinguish the cases: a '-' documented as 'unreachable/stale' must not be used for 'backend can't tell me the URL' — use an explicit 'unknown' marker and update the doc line.
- Add a regression test that opens a session, navigates, and asserts the URL column is populated.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: MockSite cold start runs ~13 minutes with no interim progress output

**Severity:** Low
**Category:** UX

#### Reproduction

./bin/test.ps1 mock-site (from a checkout whose Maven artifacts are not yet warm). Observed: two Maven install phases (browser4-dependencies, then browser4-rest + ~25 modules) run with -q; the first banner ('[PASS] InstallDependencyBOM') appears only after the first phase completes, and the second after the ~10-minute browser4-rest install; Spring Boot starts afterwards. During the wait the command prints nothing that grows or updates (and when output is redirected/backgrounded, buffering can hide even the initial banner).

#### Expected Behavior

Long-running setup steps should emit periodic progress (module counter, elapsed time) so the user can tell the process is alive rather than hung, and so timeouts can be chosen sensibly.

#### Actual Behavior

~13 minutes of silence (about 10 minutes of it in the rest-module install) before the mock site became reachable on :18080; a first-time user is likely to kill the process and/or retry, compounding the problem (the script itself warns that concurrent Maven builds must not run).

#### Root Cause Analysis

The preflight installs run with `-q` and the wrapper only prints a banner per completed phase (Invoke-CommandAndReport), so there is no sub-phase progress signal. The 10-15 minute cold cost is acknowledged in a script comment but is not visible at the terminal while it happens. (Part of the observed silence may also be pipe buffering in the invoking shell.)

#### Code Pointer

`bin/test.ps1 (mock-site preflight: 'InstallDependencyBOM' / 'InstallBrowser4Rest' phases, ~lines 840-870)`

#### AI Suggested Improvement

- Print a one-line 'this can take 10-15 min on a cold cache; artifacts are cached afterwards' notice before the phases and a periodic heartbeat (elapsed time) while each Maven phase runs.
- Consider streaming the Maven output (drop -q or tee it to a log file whose path is printed) so the user can tail progress.
- The already-cached path is fast, so gate the verbose/progress mode on whether the local artifacts are missing.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 13 task steps completed and verified: navigation, interactive snapshot discovery, both hover tooltips (computed-style verified), hover-card expansion (0→53px, verified both directions), two drag reorders (High→bottom, Backlog→top, DOM order verified), double-click activate (ACTIVATED, counter=1), double-click reset (counters 0/0, idle), generate-locator → #alertBtn, get text with the generated selector, and all three dialogs (alert accepted, confirm dismissed, prompt accepted with input) with result-area verification, plus a final full-page screenshot including the interaction log.

**Success Rate:** 95% — every task step succeeded; the only rework was the snapshot mode fallback in step 2 (snapshot -i lacked refs for the elements required by steps 3-6). Every other step worked on the first attempt with the documented workflow.

**Issues Found:** 5

**Major Blockers:** None. Two notable friction points: (1) the MockSite cold start took ~13 minutes of apparently dead time before the scenario could begin; (2) the recommended `snapshot -i` mode could not produce refs for the tooltip terms or draggable list items, forcing a fallback to the default snapshot mode for the interactive part of the task.

**Most Confusing Aspects:** 1) Mouse commands (click/hover/dblclick) printing a success tick and exit 0 for targets that do not exist, while `get`/`fill`/`select` fail loudly for the same input — an inconsistent contract that undermines trust in every '✓' the CLI prints. 2) `snapshot -i` returning a tree in which the page's key interactive elements (draggable items, hover terms) have no refs, while the help text promises 'self-contained target' ref lines and explicitly says it is 'not a strict interactive-only filter'. 3) `list` showing '-' for a session that is demonstrably alive (page-info works), against a documented meaning ('backend unreachable or session stale') that points users to the wrong action. 4) One drag command writing three DRAG START/END pairs into the page's own interaction log — visible evidence that the page saw the drag three times.

**Most Valuable Improvements:** 1) Fail loudly on unresolvable targets in click/hover/dblclick (consistent with get/fill/select) — highest impact for both humans and AI agents. 2) Fix the drag retry loop so one command dispatches exactly one drag lifecycle (Kotlin `return@repeat` in `repeat(3)` is a `continue`). 3) Align `snapshot -i` documentation with its actual strict-interactive behavior, and ideally include draggable/hover-target elements. 4) Populate the `list` URL column (fall back to current_session_url) or change the documented meaning of '-'. 5) Give long-running dev setup (MockSite preflight) progress output.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: click/hover/dblclick silently report success when the target element does not exist

1. ./b4w.ps1 -s eval5 click e987654321   (nonexistent ref)
2. ./b4w.ps1 -s eval5 click "#noSuchButton"   (nonexistent CSS selector)
3. ./b4w.ps1 -s eval5 hover "#noSuchThing"
4. ./b4w.ps1 -s eval5 dblclick e987654321
Compare with the read/DOM commands: ./b4w.ps1 -s eval5 get text e987654321  and  ./b4w.ps1 -s eval5 fill e987654321 "x"

#### Issue 2: drag dispatches the full drag lifecycle 3 times per invocation (repeat(3) retry loop never stops on success)

1. ./b4w.ps1 -s eval5 eval "document.getElementById('interactionLog').textContent.split('DRAG START').length-1"  → 3
2. ./b4w.ps1 -s eval5 drag e46 e42 --at top   (fixture logs DRAG START/DRAG END per dragstart/dragend)
3. Repeat the eval → 6 (exactly +3 per drag command); the fixture log also shows three full DRAG START→DRAG END pairs with ~1 s spacing for the single command. Reproduced for both drags performed in this session.

#### Issue 3: snapshot -i omits refs for draggable and hover-target elements, and -i -v <n> keeps only cursor:pointer elements — contradicting the documented 'not a strict interactive-only filter'

1. ./b4w.ps1 -s eval5 snapshot -i --stdout  → the four priority list items appear as bare lines `- text: 🔴 High Priority — Fix login bug` with NO refs, and the tooltip terms appear only as loose text fragments; the hover cards and buttons keep refs.
2. ./b4w.ps1 -s eval5 snapshot -i -v 0 --stdout → only the two hover cards (both [cursor=pointer]) are returned, though viewport 0 also contains the header, tooltip terms, the drag list and the double-click zone.
3. ./b4w.ps1 -s eval5 snapshot -i -v 1 --stdout → only the double-click zones and the four dialog buttons; the dialog-result text and interaction log are absent.
Compare: ./b4w.ps1 -s eval5 snapshot -v 0 --stdout shows refs for everything (e40/e42/e44/e46 list items, e83/e85 tooltip terms).

#### Issue 4: list shows URL '-' for live, reachable sessions; documented meaning of '-' says backend unreachable or session stale

1. ./b4w.ps1 -s eval5 goto http://localhost:18080/generated/interactive-5.html
2. ./b4w.ps1 list        → row: eval5 | ... | - | Active | Headless | ... | Browser4 | Reuse
3. ./b4w.ps1 list --json → {"name":"eval5", ..., "url":"-", "status":"active", ...}, "backend_reachable":true
4. ./b4w.ps1 -s eval5 page-info → URL: http://localhost:18080/generated/interactive-5.html
5. Reproduced with an `open --headless`-created session too (also '-' while Active/Reuse).

#### Issue 5: MockSite cold start runs ~13 minutes with no interim progress output

./bin/test.ps1 mock-site (from a checkout whose Maven artifacts are not yet warm). Observed: two Maven install phases (browser4-dependencies, then browser4-rest + ~25 modules) run with -q; the first banner ('[PASS] InstallDependencyBOM') appears only after the first phase completes, and the second after the ~10-minute browser4-rest install; Spring Boot starts afterwards. During the wait the command prints nothing that grows or updates (and when output is redirected/backgrounded, buffering can hide even the initial banner).

