# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260902-191811-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260902-191811 | **Mode:** dev

## Scenario Background

### Task

All 18 scenario steps were completed and the requested data was extracted:

- **Product detail (4K OLED TV 55, `/ec/dp/B0E000001`):** title, price `$899.99`, description "Premium 4K OLED TV", image URL, features (55 inch, HDR10+), attributes (panel OLED, resolution 3840×2160, rating 4.6/521) — via `snapshot`, `htmlsnapshot capture/inspect/get/grep`, an X-SQL `--sql @file` query, and AI `extract` (a DEEPSEEK_API_KEY was configured, so step 10 ran).
- **Electronics listing (`/ec/b?node=1292115012`):** all 6 product titles+prices extracted with `htmlsnapshot get all`; `eval --json` counted 6 product links; full-page screenshot saved to `../../../../../../.test-sessions/shots/electronics-listing-1292115012.png`.
- **State preservation:** detail tab content verified after reload (snapshot + grep of attributes), `state-save` wrote `../../../../../../.test-sessions/session-state.json`.
- Report: `../../../../../../.test-sessions/product-data-report.md`.

**Two caveats:** (1) Steps 11–14 could not run as scripted because `htmlsnapshot capture` breaks session-wide after `tab-new` (Issue 1) — the session had to be restarted and the listing captured before the second tab was created; (2) no cookies/localStorage exist on the fixture, so the state file is structurally valid but empty.

### Execution Context

Commands (all `./b4w.ps1`, per harness rules):

| Step | Command(s) | Result |
|---|---|---|
| Prep | `./b4w.ps1 help`, read `../../../../../../skills/browser4-cli/SKILL.md` + refs; MockSite already up (HTTP 200) | OK |
| 1 | `goto http://localhost:18080/ec/` | OK — session auto-created |
| 2 | `snapshot -v 0 --stdout` | OK — banner, category nav, trending panel |
| 3 | `snapshot -i --stdout` | OK — interactive-only tree |
| 4 | `click e95` (first `/ec/dp/` link, found via saved-snapshot grep) | OK → detail page |
| 5 | `htmlsnapshot` (capture) | OK |
| 6 | `htmlsnapshot inspect --max 3 --depth 2` | Ran, but auto-discovered the **recommendation rail**, not the main product (Issue 3); resolved by `htmlsnapshot export` + manual id scan (`#productTitle`, `#product-price`, `p.description`, `img.product-image`) ...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: htmlsnapshot capture breaks session-wide after tab-new: 'ReferenceError: __pulsar_utils__ is not defined'; only closing the session recovers

**Severity:** High
**Category:** Reliability

#### Reproduction

Fresh session: 1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot  (works) 3) ./b4w.ps1 tab-new http://localhost:18080/ec/b?node=1292115012 4) ./b4w.ps1 htmlsnapshot  -> ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1. The failure then affects EVERY tab: tab-select back to the original tab, reload, goto to another URL, tab-close of the new tab, and open (reconnect) all still fail. Only './b4w.ps1 close' followed by a new session restores capture. Reproduced twice in independent sessions. Other page ops (snapshot/AX tree, eval, click) keep working.

#### Expected Behavior

htmlsnapshot capture should work on any tab of an existing session, including tabs opened via tab-new.

#### Actual Behavior

Once a second tab is created with tab-new, htmlsnapshot capture fails for the rest of the session with a page-side ReferenceError; the stored-snapshot family (htmlsnapshot get/get all/grep/inspect) becomes unusable because capture is a prerequisite. Multi-tab workflows (detail page + listing page, as in this scenario) are effectively blocked and require a full session restart, losing tab state.

#### Root Cause Analysis

html_snapshot_capture evaluates capture JS in the page that calls a driver-injected helper __pulsar_utils__. In browser4-protocol, InteractiveBrowserEmulator.kt checks `typeof(__pulsar_utils__)` via isScriptInjected() (line ~666, 'For some type of pages, the script can not be injected') and the capture path calls __pulsar_utils__ unconditionally (line ~791). Creating a CDP target via tab-new appears to bypass the document-settle/injection path that normally installs the util, so the helper never exists in the new page context, and re-injecting on the ORIGINAL tab also stops working afterwards - suggesting the injection registration is bound per-target/context state that tab-new corrupts. Investigation needed in the PulsarWebDriver/emulator multi-target bookkeeping: why a new tab invalidates injection for the whole session and why reload/goto do not re-inject.

#### Code Pointer

`browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/impl/InteractiveBrowserEmulator.kt:666 (isScriptInjected / ensureInjected area; capture usage of __pulsar_utils__ at ~line 791)`

#### AI Suggested Improvement

- Re-inject __pulsar_utils__ (or re-run the injection script) automatically before each html_snapshot_capture when typeof(__pulsar_utils__) is not 'function', instead of failing
- Fix the underlying multi-tab injection registration in PulsarWebDriver so new tabs created via tab-new get the same init-script/document-settle treatment as navigated pages
- Add a regression e2e test: capture -> tab-new <url> -> capture, asserting success (cli/browser4-cli/tests/e2e/scenarios/batch.rs or browser.rs)
- While unfixed, make the CLI error actionable: detect the __pulsar_utils__ symptom and print 'capture broken after tab-new; run close then retry' instead of a raw JS stack

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified blocker with highest user impact — one tab-new permanently breaks capture for the whole session, and the fix (re-inject guard before capture + driver-side init for tab-new targets + regression e2e) is well-scoped. Schedule first; the "closing the session recovers" symptom confirms a page-side injection state gap, not a transient race.

---

### Issue 2: Git Bash: ./b4w.ps1 silently mangles arguments that start with '/' - snapshot grep '/ec/dp/' reports '0 matches found' though the text exists

**Severity:** Medium
**Category:** Reliability

#### Reproduction

From Git Bash in the repo root (task-mandated invocation): 1) ./b4w.ps1 goto http://localhost:18080/ec/ 2) ./b4w.ps1 snapshot grep '/ec/dp/'  -> '0 matches found', even though snapshot grep 'dp/' and snapshot grep 'B0E000001' match. Also fails via -e and -F variants, and for '/' alone. Works correctly with: ./b4w.sh snapshot grep '/ec/dp/', 'pwsh -NoProfile -Command "./b4w.ps1 snapshot grep '/ec/dp/'"', and the regex-equivalent escaped pattern '\/ec\/dp\/'.

#### Expected Behavior

All wrapper invocations should produce identical results; a pattern present in the snapshot should match.

#### Actual Behavior

The pattern is silently dropped/mangled, and the CLI prints the misleading '0 matches found' verdict (designed for honest no-match cases) instead of an error, making a real search appear to genuinely find nothing. This burned significant debugging time during the scenario.

#### Root Cause Analysis

b4w.ps1 is a PowerShell script with a #!/usr/bin/env pwsh shebang. When bash executes it, PowerShell re-serializes the argv when launching the native browser4-cli.exe and treats tokens beginning with '/' (PowerShell's alternate parameter-prefix character) as switch-like parameters, consuming them. The 'correct' paths documented in CLAUDE.md/SKILL.md (./b4w.sh on Git Bash, which quotes args for pwsh) are not affected, but the scripts are ambiguous about invocation from bash (b4w.ps1 'works' for ordinary args, so users do not switch).

#### Code Pointer

`b4w.ps1 (repo root): $RemainingArgs collection and native-exe invocation (& $Exe @RemainingArgs); mitigation docs live in b4w.sh and CLAUDE.md/SKILL.md invocation table`

#### AI Suggested Improvement

- In b4w.ps1, detect Git-Bash style '/'-leading tokens and re-quote or reject them with a clear message pointing to ./b4w.sh
- Or make b4w.sh the only documented bash path and have b4w.ps1 print a warning when $PSNativeCommandArgumentPassing drops an argument
- CLI-side safety net: when snapshot grep receives a pattern that began with '/' but arrives empty/odd, error out ('pattern lost in shell quoting - use ./b4w.sh or -F') rather than print '0 matches found'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Silent wrong results ('0 matches found' for a present pattern) are worse than an error, so this is worth fixing despite the documented b4w.sh path. Before fixing, validate the root cause — when pwsh is launched from Git Bash, MSYS path conversion of '/'-leading tokens (not PowerShell's native-arg re-serialization) is a strong candidate culprit, and the fix location depends on which layer drops the argument; also add the CLI-side empty/odd-pattern guard as the safety net.

---

### Issue 3: htmlsnapshot inspect auto-discovery targets side rails instead of main product content; primary selectors (title/price/desc/image) never surfaced

**Severity:** Medium
**Category:** Product

#### Reproduction

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot 3) ./b4w.ps1 htmlsnapshot inspect --max 3 --depth 2  -> Output header: 'Inspect: ".recommendation-card" (4 matches, 3 analyzed)' and all suggested selectors (p.recommendation-copy, span.recommendation-price, h3...) are from the 'Customers also viewed' rail. The page's actual product selectors #productTitle, #product-price, p.description, img.product-image appear nowhere in the output. (Same on the listing page it worked well: auto-discovered .product-card.)

#### Expected Behavior

On a product detail page, inspect should surface the main content area's selectors (h1#productTitle, #product-price, .description, .product-image) - or at least present several candidate patterns and suggest scoping, since step 6 of this scenario explicitly relies on inspect to discover them.

#### Actual Behavior

Auto-discovery selects only the first repeating sibling group in document order (the recommendation rail) and analyzes it; the unique-id product elements are invisible to a repeat-pattern detector. A first-time user following the documented flow would extract recommendation data instead of product data. Workaround used: htmlsnapshot export + manual id/class scan of the HTML.

#### Root Cause Analysis

inspect's :root auto-discovery is designed for repeating container grids and picks one sibling group; on detail pages the rail repeats while the main article does not. --max/--depth only tune per-pattern analysis, not pattern selection, and unique identifiers (#ids, h1) are not part of the suggestion vocabulary even though the capture metadata (interactiveElements with ids) already knows them.

#### Code Pointer

`cli/browser4-cli/src/main.rs: htmlsnapshot inspect auto-discovery handler (pattern candidate ranking, ~line 7205 area '### Inspect: ... (0 matches)'), backend selector suggestion in htmlsnapshot inspect tool`

#### AI Suggested Improvement

- Rank candidate groups by layout prominence (largest area, main-column position, document position) or return the top N distinct candidate groups (rail + card + specs table) rather than one
- Add a second suggestion section for unique landmarks: h1 text, elements with id=, from the already-collected interactiveElements metadata
- In the 'Try these next' output for pages where the chosen group is narrow/side-positioned, prompt: 'rail detected - run htmlsnapshot inspect \"<scoped selector>\" for the main content'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Valid product gap, but the real fix (rank candidate groups by prominence, add id/h1 landmark vocabulary) is heuristic redesign with regression risk to the grid-container use case that inspect exists for — it needs deliberate design and fixture tests, not a hotfix. No data loss and a manual workaround exists; a cheap interim step is the 'rail detected — scope your selector' hint once Issues 1–2 are done.

---

### Issue 4: extract output is a double-encoded envelope (schema fields nested as a JSON string under 'description') and defaults to a file instead of stdout

**Severity:** Low
**Category:** UX

#### Reproduction

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 extract 'Extract product title, price, description, and key features' --schema '{"fields":[...]}'  -> stdout shows only '### Extracted content' plus a link to a timestamped file in .browser4-cli/snapshot/extract-*.txt; the file contains {"type":"ai.platon.pulsar.agentic.ExtractResult","description":"{\"title\":...}",...} i.e. the structured result is an escaped JSON string. With --stdout the same envelope prints, still escaped.

#### Expected Behavior

A synchronous structured-extraction command invoked with --schema should emit usable JSON (schema fields at top level) on stdout, or clearly document file-by-default behavior in SKILL.md.

#### Actual Behavior

Consumers must locate the artifact file, then JSON.parse twice (outer envelope, then description) to obtain the requested fields; the inner object also mixes user fields with metadata and lacks a top-level completion flag. --help documents the file default ('Output is saved to a timestamped file by default. Use --stdout'), but SKILL.md's agent section does not, so a first-time user reading the SKILL sees the output vanish into a file.

#### Root Cause Analysis

extract prints the raw backend ExtractResult envelope (ai.platon.pulsar.agentic.ExtractResult) whose schema output is transported in the description field as a JSON string; the CLI has no unwrap/normalization step for the schema-fields case. File-by-default is an undocumented (in SKILL) design for non-TTY invocation.

#### Code Pointer

`cli/browser4-cli/src/main.rs extract handler (result printing / output destination selection); reference docs: skills/browser4-cli/references/agent.md §extract`

#### AI Suggested Improvement

- When --schema is provided, print the parsed schema fields as the top-level JSON result (unwrap description) instead of the raw envelope
- Document 'output goes to a timestamped file; use --stdout/--raw' prominently in SKILL.md and agent.md (not only --help)
- Add a top-level completed/status flag and keep token counts out of the payload the user requested

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The double-encoded envelope is a genuine output-contract bug for --schema consumers and unwrapping `description` is low-risk; file-by-default must be documented in SKILL.md/agent.md regardless. Consider batching with Issue 5 as one output-hygiene round since both break machine consumption of structured results.

---

### Issue 5: eval --json quotes numeric results into strings ('result': "6"), breaking typed consumption

**Severity:** Low
**Category:** UX

#### Reproduction

1) Write .test-sessions/count-links.js containing document.querySelectorAll('a[href*="/ec/dp/"]').length (returns the JS number 6) 2) ./b4w.ps1 eval --file .test-sessions/count-links.js --json  -> {"output":{"result":"6","expression":"..."}} - the number 6 arrives as the string "6".

#### Expected Behavior

In --json mode (advertised as the clean machine-readable mode), a numeric JS result should be a JSON number so pipelines can use it without coercion.

#### Actual Behavior

The scalar is always wrapped as a JSON string, so scripts must detect and parse the value. The eval --json mode doc says scalars are 'JSON-wrapped' and the code comment at main.rs:5210 claims 'objects/arrays/numbers are printed as-is', but the observed nested result field is stringified - behavior differs from the intent and from tab-list --json where counts are native numbers.

#### Root Cause Analysis

Scalar eval results are routed through a string-wrap path (JSON-wrapping scalar results) that quotes the value regardless of its runtime type; the documented 'numbers as-is' normalization is not applied to the nested output.result field for the --file path.

#### Code Pointer

`cli/browser4-cli/src/main.rs eval result serialization (~line 5210, handle_tool_command_with_options eval_json path)`

#### AI Suggested Improvement

- Preserve the JS type: emit native JSON numbers/booleans/null for scalar results in --json mode (only quote actual strings)
- Or add an explicit result_type field ("number"/"string") so consumers can coerce safely
- Align the code comment and SKILL.md wording with the actual behavior until fixed

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The code comment at main.rs:5211 explicitly claims numbers print as-is while the observed nested `result` is stringified — a clear intent-vs-behavior mismatch, and type-preserving serialization for scalar results is a small, testable fix. Include an eval --json regression test asserting native JSON number output.

---

### Issue 6: htmlsnapshot get all prints a misleading staleness warning when a selector legitimately matches exactly one element

**Severity:** Low
**Category:** UX

#### Reproduction

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot 3) ./b4w.ps1 htmlsnapshot get all text 'p.description' --all  -> correct result ["Premium 4K OLED TV"] followed by 'Only 1 result(s) found for "p.description". The page structure may have changed since the snapshot was captured. Try `htmlsnapshot inspect "p.description"` to discover current selectors.'

#### Expected Behavior

One match is a perfectly valid outcome for a unique-element selector (a single description paragraph); extraction should not imply page staleness.

#### Actual Behavior

A first-time user is told their extraction is suspect ('page structure may have changed') precisely when the extraction succeeded, prompting needless re-captures/inspection and undermining trust in a correct result.

#### Root Cause Analysis

The message is heuristic noise tuned for selectors users expect to repeat (product-card lists); it fires on any result count of 1 with no signal about whether the selector is a repeating pattern.

#### Code Pointer

`cli/browser4-cli/src/main.rs:6189 (get-all result reporting: 'Only {} result(s) found ... page structure may have changed')`

#### AI Suggested Improvement

- Only emit the staleness hint for counts of 0 (genuinely no match), or tie it to whether the selector class/id suggests repetition
- Reword for the 1-match case: '1 result found. If you expected more, the selector may be too narrow - run htmlsnapshot inspect to discover alternatives.'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] A false 'page structure may have changed' warning on a successful unique-selector match actively undermines trust in correct output, and the fix is trivial: warn only on 0 matches and reword the 1-match case as 'if you expected more, the selector may be too narrow.' Align its wording with the Issue 3 scoping hints so both messages use the same discovery guidance.

---

## Overall Assessment

**Completion Status:** Successful - all 18 scenario steps completed and all requested product data extracted (detail page fields via htmlsnapshot/X-SQL/AI extract; listing page titles+prices+link count; screenshot; reload verification; state-save; report). Two steps required workarounds because of the tab-new capture failure (steps 11-12 ran in a restarted single-tab session), and extract data was validated after a second parse of the returned envelope.

**Success Rate:** 95%

**Issues Found:** 6

**Major Blockers:** htmlsnapshot capture fails session-wide after tab-new (__pulsar_utils__ is not defined); the documented multi-tab workflow (detail tab + tab-new listing tab, then capture+get all on the listing) cannot be executed without restarting the session, losing tab state. Worked around by capturing the listing page in a fresh single-tab session before recreating the second tab.

**Most Confusing Aspects:** 1) snapshot grep silently reporting '0 matches found' for a pattern that exists (pattern starting with '/' through ./b4w.ps1 from Git Bash) - wasted significant debugging time; 2) htmlsnapshot inspect auto-discovery pointing at the 'Customers also viewed' rail instead of the product itself, with no hint that the main-content selectors need manual discovery; 3) extract results landing in a timestamped file (with no stdout payload) when invoked non-interactively - not mentioned in SKILL.md; 4) the 'page structure may have changed' warning printed exactly when a single-match extraction succeeded.

**Most Valuable Improvements:** 1) Fix or auto-recover htmlsnapshot capture after tab-new (re-inject __pulsar_utils__ before capture; add a regression test for capture -> tab-new -> capture); 2) make b4w.ps1 (Git Bash) stop silently swallowing '/'-prefixed arguments, or error loudly; 3) teach htmlsnapshot inspect to surface unique-id/primary-content selectors (or multiple candidate groups) so detail-page discovery does not require exporting HTML and hand-scanning ids; 4) print extract's structured JSON on stdout when --schema is used.

**Usability Rating:** 6/10

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

#### Issue 1: htmlsnapshot capture breaks session-wide after tab-new: 'ReferenceError: __pulsar_utils__ is not defined'; only closing the session recovers

Fresh session: 1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot  (works) 3) ./b4w.ps1 tab-new http://localhost:18080/ec/b?node=1292115012 4) ./b4w.ps1 htmlsnapshot  -> ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1. The failure then affects EVERY tab: tab-select back to the original tab, reload, goto to another URL, tab-close of the new tab, and open (reconnect) all still fail. Only './b4w.ps1 close' followed by a new session restores capture. Reproduced twice in independent sessions. Other page ops (snapshot/AX tree, eval, click) keep working.

#### Issue 2: Git Bash: ./b4w.ps1 silently mangles arguments that start with '/' - snapshot grep '/ec/dp/' reports '0 matches found' though the text exists

From Git Bash in the repo root (task-mandated invocation): 1) ./b4w.ps1 goto http://localhost:18080/ec/ 2) ./b4w.ps1 snapshot grep '/ec/dp/'  -> '0 matches found', even though snapshot grep 'dp/' and snapshot grep 'B0E000001' match. Also fails via -e and -F variants, and for '/' alone. Works correctly with: ./b4w.sh snapshot grep '/ec/dp/', 'pwsh -NoProfile -Command "./b4w.ps1 snapshot grep '/ec/dp/'"', and the regex-equivalent escaped pattern '\/ec\/dp\/'.

#### Issue 3: htmlsnapshot inspect auto-discovery targets side rails instead of main product content; primary selectors (title/price/desc/image) never surfaced

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot 3) ./b4w.ps1 htmlsnapshot inspect --max 3 --depth 2  -> Output header: 'Inspect: ".recommendation-card" (4 matches, 3 analyzed)' and all suggested selectors (p.recommendation-copy, span.recommendation-price, h3...) are from the 'Customers also viewed' rail. The page's actual product selectors #productTitle, #product-price, p.description, img.product-image appear nowhere in the output. (Same on the listing page it worked well: auto-discovered .product-card.)

#### Issue 4: extract output is a double-encoded envelope (schema fields nested as a JSON string under 'description') and defaults to a file instead of stdout

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 extract 'Extract product title, price, description, and key features' --schema '{"fields":[...]}'  -> stdout shows only '### Extracted content' plus a link to a timestamped file in .browser4-cli/snapshot/extract-*.txt; the file contains {"type":"ai.platon.pulsar.agentic.ExtractResult","description":"{\"title\":...}",...} i.e. the structured result is an escaped JSON string. With --stdout the same envelope prints, still escaped.

#### Issue 5: eval --json quotes numeric results into strings ('result': "6"), breaking typed consumption

1) Write .test-sessions/count-links.js containing document.querySelectorAll('a[href*="/ec/dp/"]').length (returns the JS number 6) 2) ./b4w.ps1 eval --file .test-sessions/count-links.js --json  -> {"output":{"result":"6","expression":"..."}} - the number 6 arrives as the string "6".

#### Issue 6: htmlsnapshot get all prints a misleading staleness warning when a selector legitimately matches exactly one element

1) ./b4w.ps1 goto http://localhost:18080/ec/dp/B0E000001 2) ./b4w.ps1 htmlsnapshot 3) ./b4w.ps1 htmlsnapshot get all text 'p.description' --all  -> correct result ["Premium 4K OLED TV"] followed by 'Only 1 result(s) found for "p.description". The page structure may have changed since the snapshot was captured. Try `htmlsnapshot inspect "p.description"` to discover current selectors.'

