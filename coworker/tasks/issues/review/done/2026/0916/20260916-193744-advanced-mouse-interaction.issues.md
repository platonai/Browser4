# Issues: advanced-mouse-interaction

> **Source:** `20260916-193744-advanced-mouse-interaction.full.md` | **Date:** 20260916-193744 | **Mode:** dev

## Scenario Background

### Task

I completed every step of the scenario against `http://localhost:18080/generated/interactive-5.html` (title: *Advanced Interaction Playground*) using only `./b4w.ps1`:

1. **Navigation** — MockSite came up on 18080 (took ~60 s via Maven); `goto` succeeded.
2. **Interactive snapshot** — `snapshot -i` discovered the cards, double-click zones and dialog buttons, **but omitted refs for the draggable list items and both tooltip targets**. I fell back to the default `snapshot` (47 refs vs 19 in `-i`) to obtain refs for the drag and hover steps.
3. **Tooltips** — Hovered `e21` ("Accessibility Tree") and `e24` ("DOM Snapshot"); verified with computed style (`visibility: visible`, `opacity: 1`) and confirmed the tooltip sentences appeared in the AX tree.
4. **Hover card** — `hover e26` expanded `#productCard`'s `.card-detail` from 0 px to 53 px, revealing the feature text.
5. **Drag reorder** — `drag e38 e41 --at bottom` moved High Priority to position 4/4; `drag e41 e39 --at top` moved Backlog to position 1. DOM order verified after each.
6. **Double-click activate** — `dblclick e44`: status `idle → ACTIVATED ✅`, dblClickCount 0 → 1, single 0 → 2.
7. **Double-click reset** — `dblclick e50`: counters back to 0, status back to `idle`.
8. **generate-locator** — `generate-locator e56` → `#alertBtn`.
9. **get text** — `get text "#alertBtn"` → `🔔 Show Alert`.
10. **Alert** — click returned the well-worded "dialog pending" error; `dialog-accept` released it; result area became `[alert] User dismissed the alert dialog.` (green).
11. **Confirm** — both paths verified: `dialog-dismiss` → `User clicked Cancel / declined.` (red), `dialog-accept` → `User clicked OK / accepted.` (green).
12. **Prompt** — `dialog-accept "Hello from Browser4"` → `User entered: "Hello from Browser4"`; `dialog-dismiss` → `User cancelled the prompt.`
13. **Final screenshot** — full-page capture including the interaction log.

Generated artifacts are in `.test-sessions/20260916T1722282030412Z/` (`final-state.png`, `final-fullpage.png`, snapshot dumps, check scripts). Nothing was written to the repo root.

### Execution Context

**Key Commands:**

**Key decisions / workarounds:**
- Used the **default** snapshot rather than `-i` for interaction refs, because `-i` omitted the drag sources and hover targets (Issue 1).
- Used `eval --file` (writing JS into the scratch dir) instead of inline `eval "…"` to avoid Git Bash/PowerShell quoting hazards, per the skill's guidance.
- Verified every interaction against the live DOM rather than trusting the success line, which is what surfaced the triple drag dispatch (Issue 2) and the misleading diff (Issue 3).
- Deliberately exercised both `dialog-accept` and `dialog-dismiss`, and both `drag --at top` and `--at bottom`, to cover the alternative code paths.

**Notable pass:** the dialog UX is the strongest part of the tool — the "dialog is pending" error names the dialog text, says which command to run, and explicitly warns not to re-click; exit code 1 makes it scriptable.

---

## Issues Found (7 issues)

### Issue 1: snapshot -i silently drops element refs for draggable items and hover targets

**Severity:** High
**Category:** Discoverability

#### Reproduction

./b4w.ps1 goto http://localhost:18080/generated/interactive-5.html
./b4w.ps1 snapshot -i --stdout --all > i.txt
./b4w.ps1 snapshot --stdout --all > d.txt
grep -c 'listitem' i.txt d.txt
grep -oE '\[ref=e[0-9]+\]' i.txt | sort -u | wc -l   # 19
grep -oE '\[ref=e[0-9]+\]' d.txt | sort -u | wc -l   # 47

#### Expected Behavior

`snapshot -i` is documented as an 'interactive-oriented layout … not a strict interactive-only filter', so every interactive element (the four draggable <li> items and the two tooltip hover <span>s) should still carry a ref usable by `drag <ref>`, `hover <ref>`, etc.

#### Actual Behavior

In `-i` mode the four draggable listitems and both tooltip spans have NO ref at all — they render as orphan bare lines such as `- text: 🔴 High Priority — Fix login bug` and `- text: — used by screen readers to understand page structure.`, and their parent <ul>/<p> containers are absent too. `-i` produced 19 refs vs 47 for the default snapshot; 0 listitem lines vs 4; 0 tooltip-span refs vs 1. Following the task instruction to 'take an interactive snapshot to discover all the interactive elements' therefore made the very elements needed for the hover and drag steps untargetable.

#### Root Cause Analysis

The interactive rendering pass removes nodes rather than only re-aggregating inner text into names, as its own help text claims. Nodes without an actionable AX role (listitem, plain text spans) are emitted as ref-less text fragments and their containers are pruned, so the ref table has no entry to target. Reproduced identically on the freshly-loaded page (19 vs 48 refs) and again later in the session (19 vs 47), so it is deterministic for this DOM shape, not a race.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs (interactive/-i rendering path)`

#### AI Suggested Improvement

- Decide the contract explicitly: either `-i` is a name-aggregation pass that preserves every ref (then stop pruning ref-carrying nodes), or it is a filter — and if it is a filter, say so in `help snapshot` and drop the 'not a strict interactive-only filter' wording.
- Ensure any node that the default snapshot assigns a ref to keeps that ref in `-i` output; aggregate text into the name without deleting the node line.
- Add a regression test asserting that for a fixture containing draggable <li>s and text-only hover spans, the `-i` ref set is a superset of the actionable refs the default snapshot emits.
- While `-i` loses refs, add a one-line stderr hint when `-i` prunes ref-carrying nodes: 'N elements with refs were omitted; re-run without -i for the full ref set.'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: drag dispatches the full HTML5 drag lifecycle three times per invocation

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 eval --file clear-log.js          # clears #interactionLog on the fixture
./b4w.ps1 drag e38 e41 --at top
./b4w.ps1 eval --file check-log.js            # prints the log
# Log shows three complete pairs: DRAG START … DRAG END, three times, for ONE drag command.

#### Expected Behavior

One `drag` invocation triggers exactly one dragstart → dragenter → dragover → drop → dragend cycle, so page-registered drag handlers run once.

#### Actual Behavior

The page's dragstart/dragend handlers fire three times per command, ~1.3 s apart. The final DOM order is correct, so the bug is silent. Captured verbatim: '[03:33:22] DRAG START: 🔴 High Priority … / [03:33:23] DRAG END … / [03:33:23] DRAG START … / [03:33:25] DRAG END … / [03:33:25] DRAG START … / [03:33:26] DRAG END'.

#### Root Cause Analysis

Browser4WebDriver.dragAt() wraps the drag dispatch in `repeat(3) { attempt -> … if (scriptError == null) { dragSucceeded = true; return@repeat } … }`. In Kotlin `return@repeat` on a `repeat()` lambda behaves as `continue`, not `break`, so the loop always runs all three iterations even after a successful drag. Each iteration re-executes the full drag script built by buildDragSequenceScript(), which fires dragstart/dragenter/dragover/drop/dragend — hence three lifecycles per command. The success path also never exits early, tripling latency.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:2214 (dragAt, the `repeat(3) { attempt -> … return@repeat }` loop)`

#### AI Suggested Improvement

- Replace the `repeat(3) { … return@repeat … }` construct with a real loop that breaks on success (`for (attempt in 0 until 3) { … break }`) or a labeled `return@dragAttempt`; `return@repeat` is a continue, which is the direct cause of the triple dispatch.
- Add a guard so only one lifecycle can be dispatched per dragAt() call (e.g. an AtomicBoolean set before the second attempt), making the retry idempotent even if the control flow regresses.
- Extend the existing drag e2e coverage to assert the page observed exactly ONE dragstart/dragend pair — a test that only checks the resulting order cannot catch this.
- Document the retry semantics: retries exist for transient CDP failures, so they must fire only on failure.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: snapshot -v 0 --auto-diff reports almost the whole page as changed and lists the same refs as both added and removed

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 snapshot -v 0                 # baseline at top of page
./b4w.ps1 dblclick <dblclickZone-ref>    # click scrolls the element into view
./b4w.ps1 snapshot -v 0 --auto-diff --stdout
# Simpler repro of the scroll effect: any click/hover on a below-the-fold element, then -v 0 --auto-diff.

#### Expected Behavior

The documented verification recipe (`snapshot -v 0 --auto-diff --stdout`) should report the real change — here the status text flipping to 'Status: ACTIVATED ✅' and the two counters going 0 → 2 / 0 → 1, i.e. roughly 2-3 modified nodes.

#### Actual Behavior

The diff header reported '4 added, 29 removed, 0 modified'. The Removed list contained the page banner, both hover headings, both tooltip paragraphs, the product/service cards, the drag list, and the footer — none of which changed. The header said '0 modified' despite the status/counter text actually changing. Worse, four refs (e42, e45, e51, e52) appear in BOTH the '## Removed' and '## Added' sections, so the diff contradicts itself. Cause: the preceding interaction auto-scrolled the page (documented behaviour), and `-v 0` captures only the current screen, so everything that scrolled out of view is diffed as removed.

#### Root Cause Analysis

`--auto-diff` diffs the previous stored snapshot against the new one at the same viewport spec. When the two captures have different scroll offsets (hiddenTopHeight changed from 0 to 637 px here), the viewport-scoped node sets are disjoint and the diff degenerates into a whole-page rewrite. The same-ref-in-both-lists symptom additionally suggests the diff keys nodes on (role, name) rather than ref, so a node whose AX role is re-derived between captures is emitted as a removal plus an addition under one ref. A hint line ('Page is scrolled 637px down …') is printed after the footer, but it does not prevent the misleading result.

#### Code Pointer

`cli/browser4-cli/src/snapshot_diff.rs (diff keying and viewport handling)`

#### AI Suggested Improvement

- Record the scroll offset / hiddenTopHeight in each snapshot and refuse to auto-diff two captures taken at different offsets — print 'snapshots were captured at different scroll positions; re-run without -v or scroll to the same position' instead of a bogus diff.
- Default `--auto-diff` to a full-page (`-v all`) comparison regardless of the display viewport, so the diff reflects the DOM rather than the window.
- Key diff entries on the stable ref so the same node can never appear in both Removed and Added; when a node's AX role changes, report it as modified.
- Move the 'Page is scrolled Npx down' hint above the diff so it cannot be missed, and add a count of nodes excluded by viewport scoping.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: get text fails on selectors containing a pseudo-class and wrongly reports 'No elements matched'

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 generate-locator e21        # -> #hoverSection > p:nth-of-type(2) > span
./b4w.ps1 get text "#hoverSection > p:nth-of-type(2) > span"
# Also fails: get text "#hoverSection p:nth-of-type(2)", "#alertBtn:first-of-type", "#alertBtn:hover"
./b4w.ps1 eval "document.querySelectorAll('#hoverSection p:nth-of-type(2)').length"   # -> 1

#### Expected Behavior

The generated selector should resolve — the element exists and matches — and `get text` should return its text. This is the exact verification loop documented in the skill: 'generate-locator <ref>' followed by 'get text <generated-selector>'.

#### Actual Behavior

`get text` prints `null`, then 'No elements matched "#hoverSection p:nth-of-type(2)".' and advises capturing the DOM with `htmlsnapshot`. The claim is false: scripted `document.querySelectorAll(...)` on the same live page returns 1 match. Plain selectors work fine on the same element (`get text ".tooltip-container"` and `get text "#hoverSection span"` both return the text), and `htmlsnapshot get text` with the identical pseudo-class selector also works — so the failure is specific to the live-DOM `get` path, and the advice points at a different subsystem than the one that is broken.

#### Root Cause Analysis

The selector-resolution path behind `get` does not evaluate pseudo-classes; it returns no value, and the CLI's `get_no_value_diagnostic_lines()` treats a null result as 'no elements matched' and emits generic htmlsnapshot guidance. Because `generate-locator` emits `:nth-of-type()` paths precisely for elements lacking an id or class, the two commands disagree on what a valid selector is: generate-locator's own output cannot be fed back into `get`.

#### Code Pointer

`cli/browser4-cli/src/main.rs:6670 (get_no_value_diagnostic_lines) — message; the pseudo-class resolution gap is in the backend `get` selector path reached from the `get` command handler.`

#### AI Suggested Improvement

- Make the live-DOM `get` path evaluate the selector with the same engine `htmlsnapshot get` uses (or via `querySelector`) so pseudo-classes resolve consistently across both extraction paths.
- Until then, make generate-locator emit a selector that the live path supports (id → class → plain descendant path prefer `>` chains and `:nth-child` avoided), or note in its help that its structural fallback requires `htmlsnapshot get`.
- Fix the diagnostic so it does not assert 'No elements matched' when the element provably exists: distinguish 'selector engine could not evaluate this selector' from 'no match', and name the unsupported construct (e.g. pseudo-class) explicitly.
- Add a round-trip test: for every ref on a fixture, feed generate-locator's output into `get text` and assert the same string the default path returns.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Accessible name of the same node differs between snapshot -v 0 and snapshot --all

**Severity:** Low
**Category:** Product

#### Reproduction

for i in 1 2 3; do ./b4w.ps1 snapshot -v 0 --stdout | grep -E '^\s+- paragraph' | head -3; done
for i in 1 2 3; do ./b4w.ps1 snapshot --stdout --all | grep -E 'paragraph "(— used by|Accessibility Tree)' | head -1; done

#### Expected Behavior

The same unmodified DOM node (ref e20) should render the same accessible name regardless of which viewport chunk is being written.

#### Actual Behavior

Reproducible 3/3 in each mode: with `-v 0` the node renders as `paragraph "— used by screen readers to understand page structure." [ref=e20]` with the span text on a separate child line (`generic "Accessibility Tree" [ref=e21]`); with `--all`/full-page it renders as `paragraph "Accessibility Tree — used by screen readers to understand page structure." [ref=e20]`. Name-based greps and any tooling that matches on AX names therefore see different strings depending on the display flag.

#### Root Cause Analysis

The two rendering paths differ in whether an inline span's text is folded into the parent's accessible name. Since the child node (e21) is present in both outputs, this is a name-computation difference in the renderer, not a DOM difference. Which path is 'correct' is not documented; the discrepancy is deterministic rather than intermittent.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs (accessible-name aggregation vs viewport rendering)`

#### AI Suggested Improvement

- Compute the accessible name once from the AX nodes and reuse it for every rendering mode, rather than deriving it during tree serialization.
- If a fold/no-fold difference is intentional for readability, document it in `help snapshot` under the -v/--all descriptions.
- Add a test that renders the same fixture with `-v 0` and `--all` and asserts every shared ref has an identical name.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: snapshot grep rejects attached short options (-B1) as positional arguments

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 snapshot grep -B1 -A2 'generic "DOM Snapshot"'
# Error: error: unexpected positional arguments (this command accepts 1): ["-B1", "-A2"]
./b4w.ps1 snapshot grep -B 1 -A 2 'generic "DOM Snapshot"'   # works

#### Expected Behavior

Attached short-option values (`-B1`, `-A2`) are standard GNU/POSIX grep syntax and the skill's own examples use the same flags; they should be accepted, or the error should say the spaced form is required.

#### Actual Behavior

The attached forms are treated as extra positional arguments and rejected. The error names the offending tokens but gives no hint that the spaced form (`-B 1 -A 2`) is the accepted syntax, so a user copying grep habits (or the `-n` habit the docs explicitly bless) has to guess.

#### Root Cause Analysis

The argument parser for `snapshot grep` only recognizes short options in the separated form (`-B <n>`); attached values fall through to the positional-argument check. The message is generated by the generic 'too many positional arguments' path, which has no knowledge of which flags were nearly matched.

#### Code Pointer

`cli/browser4-cli/src/args.rs (snapshot grep option parsing)`

#### AI Suggested Improvement

- Accept attached short-option values (`-B1`, `-A2`) by splitting a leading short flag from its trailing value, matching GNU getopt behaviour.
- When a rejected positional argument starts with '-' and matches a known short flag plus a suffix, emit a targeted hint: 'did you mean -B 1?'.
- Audit the other commands' short options for the same attached-form gap so the CLI has one consistent parsing rule.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: goto silently inherits an unrelated pre-existing DEFAULT session

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 goto "http://localhost:18080/generated/interactive-5.html"
# -> Using existing session DEFAULT (current page: https://abrahamjuliot.github.io/creepjs/).

#### Expected Behavior

For a fresh test run against a local fixture, either a clean session or a clear prompt about reusing a session whose page is unrelated to the requested one.

#### Actual Behavior

The command reconnected to a DEFAULT session left over from an earlier run whose current page was a third-party site (creepjs). The CLI does print the inherited page, which is good, but the reuse is silent otherwise — no indication that this session's profile (cookies, localStorage, history) carries state from an unrelated run, which can contaminate fixture results (e.g. counters or logs that start non-zero).

#### Root Cause Analysis

The unnamed DEFAULT session is a persistent singleton; `goto` reconnects to it whenever it exists. The reuse is intentional and documented, but the 'current page differs from the target' case is not surfaced as a decision point, and there is no per-invocation opt-out other than remembering `-s <name>`.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (goto session auto-open/reconnect path)`

#### AI Suggested Improvement

- When `goto` reconnects to an existing session and navigates to a different origin than the session's current page, add a one-line notice plus an escape hatch, e.g. 'reusing session DEFAULT (previous page <url>) — pass -s <name> for an isolated session, or goto --fresh to start clean'.
- Surface the session's display mode and tab count in the reconnect line, as `open` already does.
- Document the recommended pattern for one-off fixture runs (`-s <run-id>`) directly in the `goto` help text, not only in the skill reference.

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

**Completion Status:** Successful — all 13 scenario steps completed end to end. Step 2 required a workaround because the instructed `-i` snapshot omitted refs for the draggable list items and tooltip targets; the default snapshot supplied them. Every state change was verified against the live DOM rather than trusting the command's success line.

**Success Rate:** 100% of the 13 steps completed; one step (interactive snapshot discovery) required falling back to a different snapshot mode, and one documented verification recipe (`-v 0 --auto-diff`) proved unusable for post-scroll interactions.

**Issues Found:** 7

**Major Blockers:** No hard blockers. Three workflows were materially undermined: (1) `snapshot -i` withheld refs for exactly the drag and hover targets the task needed; (2) `drag` fired the page's drag lifecycle three times per call, a silent correctness hazard for any page with side-effecting drag handlers; (3) `snapshot -v 0 --auto-diff` — the skill's own 'verify what changed' recipe — reported 29 removals and self-contradictory added/removed entries for a change that was ~2 text nodes.

**Most Confusing Aspects:** The snapshot family has three overlapping modes (-i, default, -v N) whose output geometry and ref coverage differ in undocumented ways, so a first-time user cannot predict which commands will see a given element; the -i help text explicitly claims it is 'not a strict interactive-only filter' while it in fact drops refs. Second, two commands that share a documented workflow disagree about selector support: generate-locator emits `:nth-of-type()` selectors that `get text` cannot resolve, and the resulting 'No elements matched' message is factually wrong (the element provably matches). Third, the diff names the same ref both added and removed, which destroys trust in the verification step entirely.

**Most Valuable Improvements:** 1) Guarantee that any node the default snapshot gives a ref to keeps that ref under `-i`, and correct the -i help text. 2) Fix the `return@repeat`-as-continue bug in dragAt() so one drag dispatches one lifecycle, and add an e2e assertion on dragstart count. 3) Make --auto-diff scroll-aware (record the capture offset and refuse or normalize mismatched comparisons) and key entries on ref so a node cannot appear in both lists. 4) Unify the selector engines behind `get` and `htmlsnapshot get`, or make generate-locator emit only selectors the live path supports. 5) Keep the dialog ergonomics as-is — the 'dialog pending' error naming the dialog text, the follow-up command, and the 'do not re-run the click' warning is the best-designed interaction in the CLI and a good model for the other error paths.

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

#### Issue 1: snapshot -i silently drops element refs for draggable items and hover targets

./b4w.ps1 goto http://localhost:18080/generated/interactive-5.html
./b4w.ps1 snapshot -i --stdout --all > i.txt
./b4w.ps1 snapshot --stdout --all > d.txt
grep -c 'listitem' i.txt d.txt
grep -oE '\[ref=e[0-9]+\]' i.txt | sort -u | wc -l   # 19
grep -oE '\[ref=e[0-9]+\]' d.txt | sort -u | wc -l   # 47

#### Issue 2: drag dispatches the full HTML5 drag lifecycle three times per invocation

./b4w.ps1 eval --file clear-log.js          # clears #interactionLog on the fixture
./b4w.ps1 drag e38 e41 --at top
./b4w.ps1 eval --file check-log.js            # prints the log
# Log shows three complete pairs: DRAG START … DRAG END, three times, for ONE drag command.

#### Issue 3: snapshot -v 0 --auto-diff reports almost the whole page as changed and lists the same refs as both added and removed

./b4w.ps1 snapshot -v 0                 # baseline at top of page
./b4w.ps1 dblclick <dblclickZone-ref>    # click scrolls the element into view
./b4w.ps1 snapshot -v 0 --auto-diff --stdout
# Simpler repro of the scroll effect: any click/hover on a below-the-fold element, then -v 0 --auto-diff.

#### Issue 4: get text fails on selectors containing a pseudo-class and wrongly reports 'No elements matched'

./b4w.ps1 generate-locator e21        # -> #hoverSection > p:nth-of-type(2) > span
./b4w.ps1 get text "#hoverSection > p:nth-of-type(2) > span"
# Also fails: get text "#hoverSection p:nth-of-type(2)", "#alertBtn:first-of-type", "#alertBtn:hover"
./b4w.ps1 eval "document.querySelectorAll('#hoverSection p:nth-of-type(2)').length"   # -> 1

#### Issue 5: Accessible name of the same node differs between snapshot -v 0 and snapshot --all

for i in 1 2 3; do ./b4w.ps1 snapshot -v 0 --stdout | grep -E '^\s+- paragraph' | head -3; done
for i in 1 2 3; do ./b4w.ps1 snapshot --stdout --all | grep -E 'paragraph "(— used by|Accessibility Tree)' | head -1; done

#### Issue 6: snapshot grep rejects attached short options (-B1) as positional arguments

./b4w.ps1 snapshot grep -B1 -A2 'generic "DOM Snapshot"'
# Error: error: unexpected positional arguments (this command accepts 1): ["-B1", "-A2"]
./b4w.ps1 snapshot grep -B 1 -A 2 'generic "DOM Snapshot"'   # works

#### Issue 7: goto silently inherits an unrelated pre-existing DEFAULT session

./b4w.ps1 goto "http://localhost:18080/generated/interactive-5.html"
# -> Using existing session DEFAULT (current page: https://abrahamjuliot.github.io/creepjs/).

