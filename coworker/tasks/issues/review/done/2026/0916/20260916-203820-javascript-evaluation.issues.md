# Issues: javascript-evaluation

> **Source:** `20260916-203820-javascript-evaluation.full.md` | **Date:** 20260916-203820 | **Mode:** dev

## Scenario Background

### Task

All 8 steps completed successfully against `http://localhost:18080/generated/interactive-1.html`, with every result cross-verified against the raw fixture HTML fetched via `curl`.

| Step | Method | Result | Ground truth | Verdict |
|---|---|---|---|---|
| 3 | `eval "document.title"` | `Interactive Single Page` | same | ✅ |
| 4 | `eval "<obj>" --json` | `{"url":…,"title":…,"links":3}` | links=3 | ✅ |
| 5 | `eval --file page_info.js` | `{"images":2,"links":3,"forms":1}` | 2/3/1 | ✅ |
| 6 | `eval --stdin` (piped) | 5 headings, emoji intact | h1×1 + h2×4 | ✅ |
| 7 | `eval --ref e3078` / `e3025` | `Back to user information` / `name` | matches fixture | ✅ |
| 8 | `htmlsnapshot get all` (h2, img src) | 4 h2, 2 img | consistent | ✅ |

Determinism check: the same consolidated eval run 3× returned byte-identical output. The `--json` envelope carried correct JSON typing (`"links":3` as a number), confirming the scalar-stringification issue reported in a prior run of this scenario is now fixed.

**One workaround was required.** Step 5 asked for a script that "computes and *logs*" the counts. A literal `console.log`-only script returns `""` — documented behavior (`console.log` output is not captured; only the return value is printed), and the CLI does print a correct reminder. I wrote a second script that logs *and* returns to produce real output.

### Execution Context

**Key Commands:**

```
help · help snapshot · help page-info
goto "http://localhost:18080/generated/interactive-1.html"
snapshot -i --stdout · snapshot --stdout · snapshot -i --no-compact --stdout
eval "document.title"
eval "({url: document.URL, title: document.title, links: document.querySelectorAll('a').length})" --json
eval --file …/page_info_logs_only.js · eval --file …/page_info.js --json
printf '…' | eval --stdin --json
eval "element => element.textContent" --ref e3078      # correct form
eval "element.textContent" --ref e3078                 # documented mistake, verified
eval "element => element.id" e3025                     # positional ref
eval "element => element.textContent" --ref e99999     # invalid ref
page-info · --json page-info · tab-list · --json tab-list
htmlsnapshot · htmlsnapshot get all text "h2" --json · htmlsnapshot get all attr "img" src --json
```

**Major steps / decisions.** Read `SKILL.md` and `references/eval.md` first. Established ground truth from the fixture *before* touching the browser, so every CLI result had an independent reference. `goto` auto-started the daemon and backend with no manual setup. I used `--json` on all structured reads so results were machine-comparable, and `tee`'d every command to the run scratch directory.

**Notable observations during the run.** The first `goto` reconnected to a pre-existing DEFAULT session that already held an unrelated tab (`localhost:18080/ec/b?node=…`); the CLI disclosed this (`current page: …; 2 tabs`), and it is documented in SKILL.md §Sessions. It did, however, set up the multi-tab ambiguity in Issue 2. The `--ref` invalid-ref test and the `--no-compact` isolation test ran in parallel shells against the same session without interference.

**Workarounds.** (1) Added `return` to the step-5 script. (2) Used the default `snapshot` (not `-i`) to read page structure, because `-i` silently omits headings/paragraphs. (3) Used `page-info --json` + `tab-list` together to establish which tab was current — neither alone answers it.

---

---

## Issues Found (5 issues)

### Issue 1: snapshot -i is a strict interactive-only filter, contradicting four documentation surfaces

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 snapshot --stdout
Compare element composition of the two outputs.

#### Expected Behavior

Per documentation, -i is 'not a strict interactive-only filter' and 'does not shrink the tree' — addressable headings, paragraphs and generic containers should remain. Expected the headings (h1 'Welcome to the Interactive Page', h2 '📋 User Information', etc.) to be present in the -i output.

#### Actual Behavior

snapshot -i returned 9 ref lines containing ONLY interactive controls (1 textbox, 1 combobox, 2 spinbuttons, 2 buttons, 3 links): 0 headings, 0 paragraphs, 0 generics. The default snapshot on the same page, same session, no navigation between, returned 40 ref lines including 5 headings, 8 paragraphs and 2 generics. -i therefore silently drops all non-interactive content. Reproduced twice, including with --no-compact (still 0 headings, 9 refs), which rules out compact mode as the cause.

#### Root Cause Analysis

The `interactive` rendering path in the backend snapshot/AX renderer filters the tree down to interactive roles, rather than the documented behavior of keeping non-interactive containers and only aggregating inner text into element names. The CLI merely forwards the flag (browser4-agentic AgenticCliRunner.kt:740: args["interactive"]?.let { params["interactive"] = it }), so the filter is server-side. The docs and the implementation disagree in four independent places, which indicates doc/behaviour drift rather than a single typo: (1) `help` Quick Start ('an interactive-oriented layout, not an interactive-only filter'), (2) `help snapshot` ('not a strict interactive-only filter'), (3) SKILL.md §5 mode table, (4) SKILL.md §5 bold note ('-i does not shrink the tree ... does not strip non-interactive containers'). Which side is authoritative needs a product decision; the impact of the current mismatch is a silent content-loss trap, since a user following the docs will use -i to read page structure and never learn that headings were dropped.

#### Code Pointer

`skills/browser4-cli/SKILL.md (§5 snapshot modes, 2 places) and cli/browser4-cli/src/help.rs:2360 (page-info-adjacent help table; the snapshot -i description is in the same file). Renderer side: browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/AgenticCliRunner.kt:740 forwards the flag; the actual role filter lives in the backend snapshot/AX renderer and needs a follow-up trace.`

#### AI Suggested Improvement

- Decide authoritatively: either restore the documented behaviour (keep addressable headings/paragraphs/generics, only aggregate text into names) or accept the strict-filter behaviour and rewrite all four doc surfaces.
- If the strict filter is intentional, rename or re-document it as an interactive-only mode — `-i` reading as 'interactive only' is a reasonable user expectation, and the current docs fight that expectation.
- Add a one-line stderr note when -i drops non-interactive nodes (e.g. 'interactive-only view: N structural nodes hidden; use `snapshot` for the full tree') so the omission is never silent.
- Add a regression test asserting the heading count in `snapshot -i` output for a fixture with known headings, so the doc and the behaviour cannot drift apart again.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: page-info cannot identify the current page in a multi-tab session, and its active flag is always false

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"   # in a session that already has another tab
./b4w.ps1 page-info
./b4w.ps1 --json page-info
./b4w.ps1 --json tab-list

#### Expected Behavior

page-info's documented purpose is 'Show current page identity — title, URL, and key metadata' and 'quick "what page am I on?" checks'. In a multi-tab session it should mark which tab is current, e.g. '▶ Active page: Interactive Single Page'.

#### Actual Behavior

Human-readable output listed both tabs unmarked:
    ─ Page 0 ─
    Title:  Category: Electronics
    URL:    http://localhost:18080/ec/b?node=1292115012
    ─ Page 1 ─
    Title:  Interactive Single Page
    URL:    http://localhost:18080/generated/interactive-1.html
Nothing indicates which of the two is active. `--json page-info` returns "pages":[...,{"index":0,...,"active":false},{"index":1,...,"active":false}] — BOTH tabs report active:false, so the machine-readable path cannot disambiguate either. `--json tab-list` shows the same always-false field. The command therefore does not answer the one question it exists to answer.

#### Root Cause Analysis

parse_tab_entry (cli/browser4-cli/src/main.rs:846-849) reads an `active` field from the backend's browser_tabs list response and defaults to false when absent: `obj.get("active").and_then(|v| v.as_bool()).unwrap_or(false)`. The backend's tab-list payload does not supply that field, so every tab is parsed as inactive. handle_page_info then takes its `tabs.iter().find(|t| t.active)` branch only when a tab is active; since none ever is, it always falls through to the multi-tab else-branch (main.rs:3048-3057) that prints every tab unmarked. The source comment at main.rs:2987-2988 acknowledges this explicitly: 'When the active-tab field is available (see Issue 2), the active tab is highlighted.' The fix is to have the backend emit the active flag (or the active tab id/guid) in the browser_tabs list response — or have the CLI determine the active tab by another means — and to surface it in the human-readable branch.

#### Code Pointer

`cli/browser4-cli/src/main.rs:parse_tab_entry (line 846) and cli/browser4-cli/src/main.rs:handle_page_info (line 3034, the `find(|t| t.active)` branch); backend emitter of the browser_tabs list payload.`

#### AI Suggested Improvement

- Emit the active tab (e.g. an `active` boolean per tab, or a top-level `activeTabId`/`activeIndex`) from the backend's browser_tabs list response.
- When the active tab is identifiable, always print the '▶ Active page:' form even for a single tab, so the output shape is consistent regardless of tab count.
- When the active tab cannot be determined, say so explicitly (e.g. 'Active tab not reported by the server; showing all N tabs') instead of emitting a plausible-looking but unmarked list.
- Drop or null out the `active` field in JSON when the backend did not report it, so consumers are not served a confidently-wrong `false`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: "Expression returned empty" diagnostic misdirects to a stale page context when the real cause is a missing return

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 eval --file page_info_logs_only.js   # script that calls console.log but has no return statement

#### Expected Behavior

The remedy offered should match the actual failure. When the script was submitted as a file, the primary cause of an empty result is a missing return/expression value.

#### Actual Behavior

Output is:
    ℹ️  console.log() output is not captured — only the expression's return value is shown. Use `return` / end with the value to see it.
    ""
    💡 Expression returned empty/undefined.
    This could mean:
    - The page context is stale (try: goto <url>)
    - The JS expression returned undefined or ""
    Check current page: eval "window.location.href"
    `// page_info_logs_only.js — ...` returned empty — the page may have changed since your last navigation. Run `goto` first to restore the page context.
The first line correctly diagnoses the console.log issue, but the following block walks it back and leads with 'The page context is stale (try: goto <url>)' and closes with 'Run `goto` first to restore the page context'. A first-time user following that advice would re-navigate the browser — which does not fix the problem and destroys page state. Additionally, the full script source is echoed inline as a single backticked blob, which becomes very noisy for a long file.

#### Root Cause Analysis

The empty-result diagnostic is a generic handler that suggests the most common cause (stale page context) without weighting the stronger signal already detected one line earlier — that console.log was present in the expression and no return value was produced. The two messages are emitted by separate code paths and are not cross-referenced, so the specific hint and the generic hint contradict each other on the same screen.

#### Code Pointer

`cli/browser4-cli/src/main.rs — the eval result handler that prints the console.log reminder and the 'Expression returned empty/undefined' hint (search for 'Expression returned empty').`

#### AI Suggested Improvement

- When the console.log reminder has already fired, suppress the stale-page-context block entirely and replace it with the actionable form: 'Your script logged but did not return a value — add `return <value>` or end with the value.'
- Only suggest `goto <url>` when there is independent evidence of staleness (e.g. the eval actually failed against the document, not merely returned undefined).
- Truncate or omit the inline echo of file- and stdin-sourced expressions (show a short label like the file path instead), since the user already has the file on disk.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: page-info/tab-list active-page branch prints "(N/N tabs total)" — numerator should be the active index

**Severity:** Low
**Category:** Product

#### Reproduction

Inspect cli/browser4-cli/src/main.rs:3042, or once the active flag is populated, run `./b4w.ps1 page-info` in a session with more than one tab.

#### Expected Behavior

The tab counter should identify the position of the active tab, e.g. '(1/3 tabs total)' for the first of three tabs.

#### Actual Behavior

The format string passes tabs.len() for both arguments: `cli_println!("  ({}/{} tabs total)", tabs.len(), tabs.len());` — producing '(3/3 tabs total)' regardless of which tab is active. Currently unreachable because the active-flag branch never runs (see the page-info issue above), so it is a latent defect that will surface as soon as the active tab is reported.

#### Root Cause Analysis

A copy-paste error in the format arguments; the numerator was meant to be the active tab's 1-based position (active_tab.index + 1) rather than the total count.

#### Code Pointer

`cli/browser4-cli/src/main.rs:3042 (inside handle_page_info, the `if let Some(active_tab)` branch)`

#### AI Suggested Improvement

- Pass the active tab's 1-based position as the numerator: `active_tab.index + 1`.
- Add a unit test for the multi-tab active rendering so the latent branch is actually executed and asserted before the active flag is wired up.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: eval --ref invalid-ref error leaks the internal tool name browser_evaluate and appends an irrelevant help footer

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 eval "element => element.textContent" --ref e99999

#### Expected Behavior

An error in the CLI's own vocabulary (eval), describing the failure and the remedy, consistent with the rest of the tool's messages.

#### Actual Behavior

Error: ERROR: browser_evaluate failed: Element not found for ref backend:99999. Refs expire after page changes — re-run `snapshot` to get fresh refs. help: Executes JavaScript for the element located by [selector] and returns the result as a JSON-serializable value.

#### Root Cause Analysis

The inner error string originates from the MCP tool named browser_evaluate and is passed through unwrapped, so an internal tool identifier reaches the user even though the CLI exposes the operation as `eval`. The trailing 'help: …' fragment is the tool's description string appended unconditionally to error text; it describes a selector-based lookup and is not relevant to a ref-based failure. Message composition happens where the eval tool error is rendered in the CLI.

#### Code Pointer

`cli/browser4-cli/src/main.rs — eval error rendering path (the code that formats the 'ERROR: <tool> failed: …' message and appends the tool description).`

#### AI Suggested Improvement

- Map the internal tool name to the user-facing command name before printing, so the error reads 'eval failed: element not found for ref e99999'.
- Drop the trailing 'help: <tool description>' fragment from error output, or move it behind a verbose/debug flag.
- Keep the good part as-is: 'Refs expire after page changes — re-run `snapshot` to get fresh refs' is exactly the right remediation and should lead.

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

**Completion Status:** Successful — all 8 task steps completed and every evaluation method (inline, --json, --file, --stdin, --ref, positional ref) returned correct results, cross-verified against the raw fixture HTML fetched with curl. One workaround was required at step 5 (adding a return statement to the 'compute and log' script) and one at step 2 (using the default snapshot rather than -i to read page structure).

**Success Rate:** 100% of the 8 task steps produced correct, consistent, deterministic output; ~85% if scored on first-attempt success without documentation-informed workarounds, since steps 2 and 5 each needed a documented caveat to be applied.

**Issues Found:** 5

**Major Blockers:** None. The daemon and backend auto-started on the first goto with no manual setup, no port conflicts and no Java issues, and every command exited 0. The two issues that materially affected how the task had to be performed were both documentation-driven rather than failures: snapshot -i silently omitted all headings and paragraphs despite four doc surfaces promising it would not, and eval --file returned an empty string for a script that logs without returning.

**Most Confusing Aspects:** Two things would most confuse a first-time user. First, snapshot -i: the help text and SKILL.md both insist it is 'not an interactive-only filter' and 'does not shrink the tree', yet it returned 9 refs versus the default snapshot's 40 and contained zero headings — a user trusting the docs would conclude the page has no headings at all. Second, page-info: the command exists specifically to answer 'what page am I on?', but in a two-tab session it printed both tabs with no active marker, and --json reported active:false for both, so neither the human nor the machine-readable path answers the question the command was created for. A related third point of friction is that the eval empty-result diagnostic recommends re-running goto when the actual cause is a missing return — advice that would destroy page state without fixing anything.

**Most Valuable Improvements:** 1. Resolve the snapshot -i documentation/behaviour conflict in one direction and make the omission non-silent (a stderr note when structural nodes are hidden, plus a regression test pinned to a fixture with known headings). 2. Have the backend report the active tab so page-info and tab-list can actually mark the current page, and never emit a confidently-wrong active:false. 3. Make the eval empty-result hint context-aware: when a console.log was detected, lead with 'add a return value' and suppress the stale-page-context suggestion. 4. Minor polish: stop leaking the internal browser_evaluate tool name into user-facing errors and drop the trailing irrelevant help footer. Positives worth preserving: the --ref arrow-function corrective hint ('Did you mean: eval "element => element.textContent" --ref …?') and the ref-expiry remediation are excellent, the console.log reminder is clear and correct, --json output is properly typed, and piped stdin plus emoji round-tripping both worked flawlessly on Windows/Git Bash despite the shell-quoting hazards the docs warn about.

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

#### Issue 1: snapshot -i is a strict interactive-only filter, contradicting four documentation surfaces

./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 snapshot --stdout
Compare element composition of the two outputs.

#### Issue 2: page-info cannot identify the current page in a multi-tab session, and its active flag is always false

./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"   # in a session that already has another tab
./b4w.ps1 page-info
./b4w.ps1 --json page-info
./b4w.ps1 --json tab-list

#### Issue 3: "Expression returned empty" diagnostic misdirects to a stale page context when the real cause is a missing return

./b4w.ps1 eval --file page_info_logs_only.js   # script that calls console.log but has no return statement

#### Issue 4: page-info/tab-list active-page branch prints "(N/N tabs total)" — numerator should be the active index

Inspect cli/browser4-cli/src/main.rs:3042, or once the active flag is populated, run `./b4w.ps1 page-info` in a session with more than one tab.

#### Issue 5: eval --ref invalid-ref error leaks the internal tool name browser_evaluate and appends an irrelevant help footer

./b4w.ps1 eval "element => element.textContent" --ref e99999

