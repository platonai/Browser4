# Issues: extraction-method-routing

> **Source:** `20260905-174944-extraction-method-routing.full.md` | **Date:** 20260905-174944 | **Mode:** dev

## Scenario Background

### Task

All 7 acceptance criteria of the SKILL.md §4a extraction-method branches were executed end-to-end and verified against the locally built CLI/backend (4.13.13-SNAPSHOT dev) and MockSite (localhost:18080):

- **AC1 (interact-first):** snapshot `-i` → refs → 3× `fill` + `select e6 "us"` + `check e18` + `click e173` on /generated/form-filling.html → fresh `htmlsnapshot` → `get text "#result-data"` returned exactly the entered values (Ada/Lovelace/ada.lovelace@analytical.engine/us). ✅
- **AC2 (single field):** `htmlsnapshot get text "#productTitle"` → `4K OLED TV 55`. ✅
- **AC3 (all matches):** `get all text "[class*='product-title']"` → JSON array of all 6 listing titles. ✅
- **AC4 (correlated rows):** `htmlsnapshot query --sql "@.test-sessions/ac4-cards.sql" --format table` (DOM_LOAD_AND_SELECT over `div.product-card` + DOM_BASE_URI/DOM_FIRST_TEXT/DOM_FIRST_HREF) → 6 rows, title/price/link aligned per card. ✅
- **AC5 (dynamic/complex):** `eval --file --json` → `{title, counts{buttons:2, links:3, forms:1, inputs:3}, headings:[5]}`, all verified against fixture source. ✅
- **AC6 (natural-language):** key was configured → `extract "…top three feature bullets…" --stdout` → title/price/rating + the page's two bullets (page truthfully has only two — verified via `#product-features`). ✅ Content correct; payload shape defective (Issue 1).
- **AC7 (bulk):** `crawl --seed-file .test-sessions/ac7-seeds.txt --depth 0 --sql "@.test-sessions/ac7-extract.sql" --format table --refresh` → 4 rows for 4 seed URLs in ~26 s. ✅

No task-blocking defects. All findings below are post-hoc quality issues from the deeper reliability pass.

### Execution Context

Preparation: read SKILL.md + htmlsnapshot/crawl/agent references and X-SQL docs; read fixture sources to design selectors/refs. Invoked everything as `./b4w.ps1 <command>` from the repo root; all scratch files under `.test-sessions/`.

1. **AC1:** `goto` form page → `snapshot -i --stdout` (element refs e3–e18, e173) → fills → `select`/`check`/`click` submit → `htmlsnapshot` fresh capture → `get text "#result-data"`/`"#result-panel"` → verified payload against entered values.
2. **AC2:** goto product → capture → single-field get.
3. **AC3:** goto listing → capture → `get all text` with product-title selector → 6 titles.
4. **AC4:** `htmlsnapshot inspect` for card-selector discovery → wrote `ac4-cards.sql` → `query --sql @file --format table`.
5. **AC5:** wrote `ac5-eval.js` → `eval --file -...

(truncated — see full.md for complete trace)

---

## Issues Found (4 issues)

### Issue 1: extract returns pipeline bookkeeping fields inside the requested payload and reports completed=false with exit 0

**Severity:** High
**Category:** Product

#### Reproduction

browser4-cli goto http://localhost:18080/ec/dp/B0E000002 then: browser4-cli extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout (LLM key configured). Also reproduces with an explicit --schema (@file with fields title/price/rating/featureBullets).

#### Expected Behavior

stdout (and the saved file) should contain only the requested schema fields (e.g. {"title":..., "displayedPrice":..., ...}); completion must be reported truthfully at the envelope level (true when usable content is present) with exit 0; docs for extract --schema say fields arrive as plain top-level JSON with no envelope and no bookkeeping.

#### Actual Behavior

Output was {"title":"Wireless Noise-Cancelling Headphones","displayedPrice":"$199.99","rating":"4.4","featureBullets":["Bluetooth 5.2","30h battery"],"metadata":{"progress":"","completed":false},"inputToken":1930,"outputToken":12140,"totalToken":14070,"inferenceTimeMillis":90140} with exit 0. The same contamination occurred with an explicit --schema. The backend's own evaluator chat log confirms the metadata call answered completed=false ('only two feature bullets... more viewport data remains'), i.e. a successful, exit-0 extraction is flagged incomplete inside its own payload.

#### Root Cause Analysis

The two-stage extract pipeline (InferenceEngine.extract + BasicBrowserAgent.extract) is documented and unit-tested (ExtractResultEnvelopeTest) to keep the payload clean: data must be the schema fields only, with metadata{progress,completed} and token/timing counts never merged in, and completed must be true whenever usable content is present. In the live dev backend the final data JSON contains exactly those forbidden keys, meaning the response assembly path merges the inference summary (key names inputToken/outputToken/totalToken/inferenceTimeMillis match InferenceEngine's logSummary payload) and the evaluator metadata into the data node before ExtractResult.toString() is wrapped into the {type, description, completed} envelope. The exact merge site was not pinned down from behavior alone (stage-1 raw model output JSONL was not located in the runtime dirs) — candidates: the result assembly in InferenceEngine.kt (result = extractedNode.deepCopy()), the caller in BasicBrowserAgent.kt extract(), or the chat-history-aware model output echoing summary fields; needs a log trace of the raw stage-1 response to confirm.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/InferenceEngine.kt:extract() (result assembly ~lines 97-205) and browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/BasicBrowserAgent.kt:extract() (~line 259); envelope wrapping in browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/AbstractToolExecutor.kt:callFunctionOn() (~line 82)`

#### AI Suggested Improvement

- Add a deterministic post-process in the extract result assembly that strips non-schema keys (metadata*, inputToken/outputToken/totalToken/inferenceTimeMillis) from the data ObjectNode before it is returned, so payloads match ExtractResultEnvelopeTest's contract
- Align completion semantics: envelope-level completed=true whenever usable content exists (already the engine intent) and never surface evaluator completed=false inside the data payload; if the evaluator verdict must reach the user, return it through a separate channel (event/log/stderr) rather than inside the JSON
- Add an integration test that runs the real extract path (not a stub) and asserts the output payload contains exactly the requested schema fields

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] High severity is right — extract is a primary machine-consumption path and payload contamination with `completed:false` + bookkeeping keys breaks the documented envelope contract that `ExtractResultEnvelopeTest` and `AbstractToolExecutor.kt:82-93` already formalize. Because that unit test passes while the live two-stage pipeline still leaks the inference summary, the fix must pair the post-process strip with a regression test on the real assembly path (integration/controller-level), not just the envelope unit.

---

### Issue 2: htmlsnapshot capture prints a non-suppressible tutorial block to stdout after every capture

**Severity:** Medium
**Category:** UX

#### Reproduction

Run: browser4-cli htmlsnapshot (on any page). Inspect stdout vs stderr (stdout: 33 lines, stderr: 0). The last 12 lines are a '💡 Try these next:' hint block with example commands.

#### Expected Behavior

Per SKILL.md Output Modes, tips are suppressed by default and shown on stderr only when -tip/--show-tip is passed; stdout should carry the capture result (metadata + interactive elements) only, keeping stdout clean for machine/AI consumption without --quiet.

#### Actual Behavior

Every capture emits ~33 lines to stdout including a '💡 Try these next:' tutorial block with command examples (htmlsnapshot get text "h1" --limit 5, etc.). The block cannot be suppressed except by -q/--quiet or --json, and several sample invocations are invalid for the command shown (--limit is a get-all option; plain `get text ... --limit 5` is not a supported combination per htmlsnapshot.md), which can mislead users copying the examples.

#### Root Cause Analysis

The capture command's success output appends an inline hint/tip block to stdout unconditionally (not routed through the stderr tip channel used by other commands, which honors the default tip suppression).

#### Code Pointer

`cli/browser4-cli/src/main.rs: handle of htmlsnapshot capture output (the render path that appends 'Try these next:' lines after the interactive-elements summary)`

#### AI Suggested Improvement

- Move the 'Try these next' hint block to stderr (consistent with -tip semantics) or gate it behind --show-tip so default stdout stays clean
- Fix the example flags in the hint block (drop --limit from plain `get` examples or convert them to `get all` forms)
- Consider trimming the default capture output to metadata + element counts, moving the full interactive-elements listing behind a flag such as --elements

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified — the capture success path hardcodes the `💡 Try these next:` block via `cli_println!` (main.rs:6610-6627), bypassing the tip channel whose documented contract is stderr-only and suppressed by default, and line 6616's `get text "h1" --limit 5` example is unsupported (htmlsnapshot.md only gives `--limit` to `get all`). Route the block through the existing `-tip` stderr channel and fix the examples; the proposed `--elements` flag is optional polish, not required.

---

### Issue 3: htmlsnapshot get exit code on zero CSS matches contradicts the reference documentation

**Severity:** Low
**Category:** Documentation

#### Reproduction

browser4-cli htmlsnapshot get text "#no-such-element-xyz"; echo $? → 0 (message 'No elements matched ...' printed). Same for `get all`. But htmlsnapshot.md 'Error Handling' states: 'htmlsnapshot get exits non-zero when the CSS selector matches nothing or an element ref (e5) is passed.'

#### Expected Behavior

Either the command exits non-zero on no-match (as documented) so scripts can detect a broken selector, or the doc must state that no-match is exit 0 (consistent with `htmlsnapshot query` where an empty resultSet is documented as exit 0, 'no rows matched is not an error').

#### Actual Behavior

No-match returns exit 0 with a helpful diagnostic on stdout; passing an element ref (e5) correctly returns exit 1 with a clear error. Documentation and behavior disagree on the no-match case only.

#### Root Cause Analysis

The CLI's handle_get prints diagnostic lines for null/empty results and continues to success (exit 0), while the htmlsnapshot.md error-handling section still claims non-zero exit for the no-match case — doc/behavior drift.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_get() (get_no_value_diagnostic_lines ~line 5899) vs skills/browser4-cli/references/htmlsnapshot.md 'Error Handling' section (~line 378)`

#### AI Suggested Improvement

- Decide the intended contract: if no-match must remain a non-error (parity with query's empty-resultSet semantics), update htmlsnapshot.md to document exit 0 for no-match and keep non-zero for invalid selectors/refs only
- If scripts need to detect no-match programmatically, document the stdout message or provide --json exit diagnostics rather than relying on the exit code

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified drift — code exits 0 with diagnostics on no-match (main.rs:5979-5988) while htmlsnapshot.md:378 claims non-zero; only the ref/invalid-selector clause of that sentence matches behavior. Fix the doc, not the code: exit-0-on-no-match is consistent with `query`'s documented "no rows matched is not an error" (htmlsnapshot.md:197) and the global "-l always exits 0; non-zero means the backend call failed" contract (htmlsnapshot.md:268); optionally add a machine-readable no-match signal in `--json` output for script detection.

---

### Issue 4: eval --file silently rejects the @file convention that --sql and inspect accept, with an unhelpful error

**Severity:** Low
**Category:** UX

#### Reproduction

browser4-cli eval --file "@.test-sessions/x.js" --json → error: "Eval file '@.test-sessions/x.js' not found\n  Tried: D:\workspace\...\@.test-sessions/x.js" (literal '@' kept in path). Plain relative path works.

#### Expected Behavior

SKILL.md teaches the @file convention prominently for --sql (@query.sql) and shell-quoting guidance repeatedly shows @-prefixed forms; eval guidance says only 'Prefer --file or --stdin'. Either eval --file should accept (and strip) a leading @ like --sql does, or the not-found error should hint that the @ prefix is not supported for eval --file.

#### Actual Behavior

The command fails with an error that treats '@.test-sessions/x.js' as a literal filename. A first-time user following the @-file convention from adjacent sections (and the repeated @-quoting warnings) trips on this silently different convention with no hint in the error message.

#### Root Cause Analysis

eval --file resolves its path literally without the @-prefix strip/fallback logic that the --sql file loader implements; the error message does not mention the @ convention mismatch.

#### Code Pointer

`cli/browser4-cli/src/main.rs (eval --file resolution error ~lines 6871-6901)`

#### AI Suggested Improvement

- Accept and strip a leading '@' in eval --file (and --stdin-adjacent file options) for consistency with --sql @file
- Otherwise, extend the not-found error message: 'the @file prefix is not supported here; pass a plain path'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified — `resolve_file_path_with_root_fallback` (main.rs:6863) treats `@` literally and its error gives no hint, while `extract --schema` (main.rs:6045-6051) and `--sql` already strip `@`; SKILL.md teaches the convention prominently. Prefer adding `@`-strip to `eval --file` for consistency — ideally centralizing the strip in one shared resolver so all file-taking options behave alike — with the hint in the error as the fallback option.

---

## Overall Assessment

**Completion Status:** Successful — all seven acceptance criteria (AC1-AC7) of the SKILL.md §4a extraction-branch scenario were completed and verified against the locally built CLI/backend and MockSite.

**Success Rate:** 100% of task steps succeeded on the first or second documented attempt; the only retry was a user-side syntax error on eval --file (@-prefix), recorded as an issue.

**Issues Found:** 4

**Major Blockers:** None. The natural-language branch (AC6) ran because an LLM key (DEEPSEEK_API_KEY) was configured; had no key been set, that branch would have been recorded as environment-blocked per the scenario instructions.

**Most Confusing Aspects:** - extract's JSON output mixing requested fields with metadata{progress,completed} and token/timing keys, and reporting completed=false while exiting 0 — hard to interpret for a first-time user or an automated consumer
- The @file convention applying to --sql but not eval --file, with no hint in the error message (I hit this during AC5)
- htmlsnapshot capture polluting stdout with a tutorial block after every run, contradicting the 'tips are suppressed by default' promise
- Reference documentation claiming non-zero exit for no-match htmlsnapshot get while the CLI exits 0

**Most Valuable Improvements:** - Fix the extract payload contract (strip bookkeeping keys, truthful completion) — the highest-impact fix for AI-agent consumers
- Route htmlsnapshot capture hints to stderr or behind --show-tip, and correct the invalid example flags in the hint block
- Reconcile htmlsnapshot get no-match exit-code docs with behavior
- Make eval --file accept (or clearly reject with guidance) the @-prefix used elsewhere

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

#### Issue 1: extract returns pipeline bookkeeping fields inside the requested payload and reports completed=false with exit 0

browser4-cli goto http://localhost:18080/ec/dp/B0E000002 then: browser4-cli extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout (LLM key configured). Also reproduces with an explicit --schema (@file with fields title/price/rating/featureBullets).

#### Issue 2: htmlsnapshot capture prints a non-suppressible tutorial block to stdout after every capture

Run: browser4-cli htmlsnapshot (on any page). Inspect stdout vs stderr (stdout: 33 lines, stderr: 0). The last 12 lines are a '💡 Try these next:' hint block with example commands.

#### Issue 3: htmlsnapshot get exit code on zero CSS matches contradicts the reference documentation

browser4-cli htmlsnapshot get text "#no-such-element-xyz"; echo $? → 0 (message 'No elements matched ...' printed). Same for `get all`. But htmlsnapshot.md 'Error Handling' states: 'htmlsnapshot get exits non-zero when the CSS selector matches nothing or an element ref (e5) is passed.'

#### Issue 4: eval --file silently rejects the @file convention that --sql and inspect accept, with an unhelpful error

browser4-cli eval --file "@.test-sessions/x.js" --json → error: "Eval file '@.test-sessions/x.js' not found\n  Tried: D:\workspace\...\@.test-sessions/x.js" (literal '@' kept in path). Plain relative path works.



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. Verified: `InferenceEngineBookkeepingStripTest` + `ExtractResultEnvelopeTest` (agentic module), full CLI suite green.

| Issue | Decision | Resolution |
|---|---|---|
| 1 — extract payload contaminated with bookkeeping + `completed:false` (High) | ACCEPT with improvements | Fixed: `InferenceEngine` now deterministically strips echoed engine bookkeeping (`metadata{progress,completed}`, `inputToken`/`outputToken`/`totalToken`/`inferenceTimeMillis`) from the extraction data recursively at assembly time — schema fields only reach the envelope, and the envelope-level `completed` stays truthful. New `InferenceEngineBookkeepingStripTest` covers top-level/nested/legitimate-user-metadata/mixed cases. |
| 2 — htmlsnapshot capture tutorial block on stdout (Medium) | ACCEPT | Fixed: the capture hint block (`💡 Try these next:` + live-page note) is now gated behind `--show-tip`/`-tip` and emitted on stderr, matching the documented tip policy; the invalid `--limit 5` example in the block was corrected. |
| 3 — `htmlsnapshot get` no-match exit-code doc drift (Low) | ACCEPT with improvements | Fixed (docs): htmlsnapshot.md Error Handling now states no-match prints a diagnostic and exits 0 (consistent with `query`), and that a non-zero exit means the backend call failed (invalid selector / element ref). Code unchanged per review. |
| 4 — `eval --file` rejects `@file` convention (Low) | ACCEPT | Fixed: `resolve_file_path_with_root_fallback` now strips a leading `@`, so `eval --file "@script.js"` behaves like `--sql @file` (shared resolver covers both eval entry points). |