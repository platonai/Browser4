# Issues: extraction-method-routing

> **Source:** `20261016-181635-extraction-method-routing.issues.json` | **Date:** 20261016-181635 | **Mode:** dev

## Scenario Background

### Task

All seven branches of SKILL.md §4a ("Choosing an Extraction Method") were demonstrated against MockSite on `localhost:18080` and each result was verified against the live page/DOM:

| AC | Branch | Command(s) | Result |
|----|--------|-----------|--------|
| AC1 | Interact → extract | `fill`/`select --verify`/`check`/`click` on the form, then `htmlsnapshot get text "#result-panel"` | ✅ "Submission Result Your form data was captured successfully." + `#result-data` payload showing exactly the entered values (Ada / Lovelace / ada@example.com / sg / topics [automation, testing]) |
| AC2 | Static, one field | `htmlsnapshot get text "#productTitle"` on `/ec/dp/B0E000001` | ✅ "4K OLED TV 55" (cross-checked with live DOM) |
| AC3 | Static, all matches | `htmlsnapshot get all text '[class*="product-title"]'` on `/ec/b?node=1292115012` | ✅ 6 titles + "6 elements matched." (DOM count = 6) |
| AC4 | Correlated rows | `htmlsnapshot query --sql @ac4-cards.sql --format table` with `DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]')` | ✅ 6 aligned rows: source_url, title, price, price_num, detail_url — titles/prices/URLs correlate per card (B0E000001→"4K OLED TV 55") |
| AC5 | Live-DOM logic | `eval --file @ac5-domfacts.js --json` on `/generated/interactive-1.html` | ✅ Structured object: title, url, readyState, counts {buttons:2, links:3, forms:1}, 5 visible headings — headings verified in the AX tree |
| AC6 | Natural language | `extract` on `/ec/dp/B0E000002` | ⚠️ Plain prompt returned `{}` (exit 1) on 2 of 3 runs; retry succeeded. With `--schema @file`: `{"title":"…Headphones","price":"$199.99","rating":4.4,"reviewCount":312,"features":["Bluetooth 5.2","30h battery"]}` — exact match to page |
| AC7 | High-volume | `crawl --seed-file ac7-seed-urls.txt --depth 0 --sql @ac7-detail.sql --format table --refresh` (8 sitemap URLs) | ✅ "8 pages crawled, 8 rows extracted." — one row per seed URL (url/title/price) |

Environment: MockSite was already running (HTTP 200 on all five scenario pages); backend dev-mode server was up on 127.0.0.1:8282 (bundle matches checkout); LLM configured (`DEEPSEEK_API_KEY`, deepseek-v4-flash), so AC6 was not environment-blocked.

### Execution Context

**Setup:** confirmed CWD = repo root; ran `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` + references (`htmlsnapshot.md`, `x-sql-dom-load-select.md`, `crawl.md`, `eval.md`, `x-sql-dom-functions.md`, `x-sql-dom-select-functions.md`); `status`, `doctor` (LLM OK), `list`.

**Main flow (all via `./b4w.ps1`, session `-s ext-eval`):**
1. **AC1** — `goto` form page → `snapshot -i --stdout` for refs → `fill e3/e4/e5`, `select e6 "sg" --verify`, `check e15`, `check e18`, `click e89` → `eval` of `#state-log` before/after (submitCount=1, all values correct) → `htmlsnapshot` capture → `snapshot grep` to locate the confirmation → `htmlsnapshot get text` on it. Refinements: initial class-selector guesses (`.result-panel`) matched nothing; found the real IDs via `eval` (`#result-panel`, `#result-d...

(truncated — see 20261016-181635-extraction-method-routing.issues.json for the complete execution trace)

---

## Issues Found (5 issues)

### Issue 1: extract nondeterministically returns an empty payload for a reasonable prompt (2 of 3 runs), while the backend logs the call as OK

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 -s ext-eval goto "http://localhost:18080/ec/dp/B0E000002"
2) ./b4w.ps1 -s ext-eval extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout
Run step 2 three times. Backend evidence: ./b4w.ps1 doctor log pulsar grep "agent_extract" (tool.call done lines for this instruction show resultChars=86 twice and resultChars=1801 once, all outcome=OK).

#### Expected Behavior

Every run returns the same populated result (title/price/rating/features), or a deterministic error explaining that the model produced nothing.

#### Actual Behavior

2 of 3 runs printed `{}` to stdout with stderr 'Extraction produced no data — the model returned nothing for this page.' and exit 1; 1 run returned the full payload (1801 chars). The backend reported outcome=OK for all three, including the empty ones (86-char payload, 6.6 s and 22.2 s durations).

#### Root Cause Analysis

The extraction pipeline accepts an empty model completion as a successful tool call: no server-side retry and no non-empty validation; the model (deepseek-v4-flash in this dev setup) nondeterministically returns nothing for an instruction that explicitly asks for JSON, so behavior varies by run. Contributing contract mismatch: schema-less calls omit the `schema` argument the backend tool spec declares required (see related issue), so the schema-less default path is used for exactly the runs that fail.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/BasicBrowserAgent.kt:330 (extract implementation); cli/browser4-cli/src/main.rs:9925-9948 (empty-result reporting)`

#### AI Suggested Improvement

- Add a bounded server-side retry when the model returns no usable content, then fail with the raw model response attached.
- Validate the extraction payload before returning success; distinguish 'model returned nothing' from 'page had no matching content'.
- Harden the extraction prompt/response contract (e.g. simpler output instruction, lower temperature) for JSON-style requests.
- Have the CLI mention `--schema` in the empty-result hint, since the structured path succeeded reliably.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: SKILL.md says schema-less extract 'always' returns the fixed {title, content, links} envelope, but observed output included additional top-level fields

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s ext-eval extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout (the successful run) — output was {"title":"Wireless Noise-Cancelling Headphones","price":"$199.99","rating":"4.4","features":["Bluetooth 5.2","30h battery"],"content":"...","links":[...]}

#### Expected Behavior

Per skills/browser4-cli/SKILL.md §Output Modes: 'Without --schema, extract always returns the fixed {title, content, links} envelope: the instruction only guides which text ends up inside content (asking for "price and rating as JSON" still returns one prose blob)'.

#### Actual Behavior

Named top-level fields (price, rating, features) appeared WITHOUT --schema when the instruction asked for JSON — contradicting the 'fixed envelope' claim; another run with a short prompt returned exactly {title, content, links}.

#### Root Cause Analysis

The documentation states an intended contract that the implementation does not enforce: the backend appears to propagate/merge model-produced JSON keys into the payload (model/provider dependent), so the actual output shape varies with the model's response. SKILL.md was not updated to match.

#### Code Pointer

`skills/browser4-cli/SKILL.md (§ Output Modes, line ~107)`

#### AI Suggested Improvement

- Reword to: 'the payload always contains title/content/links, and may include additional keys produced by the model when the instruction asks for named fields; use --schema for a guaranteed shape.'
- Note that the shape is model/provider dependent.
- Consider having the backend normalize the payload to the envelope unless --schema is given (then the doc claim would hold).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Accessibility-tree snapshot merges id/class/name tokens into element names with no syntax, so users cannot tell an id from a class when building CSS selectors

**Severity:** Low
**Category:** UX

#### Reproduction

On the form page after submitting:
1) ./b4w.ps1 -s ext-eval snapshot grep "result-panel" → shows a node name containing tokens 'statusPanel … error-panel alert result-panel …'
2) ./b4w.ps1 -s ext-eval htmlsnapshot get text ".result-panel" → 'No elements matched ".result-panel".' (exit 0)
3) ./b4w.ps1 -s ext-eval htmlsnapshot get text "#result-panel" → works ('Submission Result Your form data was captured successfully.')

#### Expected Behavior

An element token shown in the snapshot should be usable to construct a matching selector, or the snapshot should tell the user whether each token is an id or a class.

#### Actual Behavior

The AX name text concatenates ids, classes, name attributes, and types (e.g. 'identitySection First Name text first-name firstName …'), so '.result-panel' (class syntax) silently matched nothing (advisory printed, exit 0) while '#result-panel' was the real identity. The htmlsnapshot capture's interactive-elements list does prefix ids with '#' — the two discovery surfaces are inconsistent.

#### Root Cause Analysis

The backend AX serializer enriches element names with id/class/name/placeholder tokens for AI consumption but strips the '#id' vs '.class' selector syntax; the CLI renders the name verbatim. An untraced part of the pipeline (AX serialization likely lives in the pulsar-browser runtime, not identified in this checkout) is responsible for the token merging.

#### AI Suggested Improvement

- Annotate tokens in AX names (e.g. '#result-panel', '.callout') or emit id/class as explicit YAML attributes like the existing '/required' and '/url' fields.
- Reuse the same id-marking convention already used by `htmlsnapshot` capture's interactive-elements list.
- When `htmlsnapshot get` matches nothing, the advisory could suggest `htmlsnapshot inspect`/`snapshot grep` explicitly for selector discovery.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Every schema-less `extract` call trips a backend validation shadow warning (missing required argument 'schema'), signaling a CLI/backend contract mismatch

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s ext-eval extract "product title, price, rating" --stdout   (no --schema)
Then: ./b4w.ps1 doctor log pulsar grep "validation.shadow"
→ WARN MCPToolController — mcp.validation.shadow tool=agent_extract spec=agent.extract(instruction: String, schema: ExtractionSchema) codes=MISSING_REQUIRED_ARG details=missing required argument 'schema' for agent.extract(instruction: String, schema: ExtractionSchema)

#### Expected Behavior

The CLI should satisfy the backend tool contract (e.g. send ExtractionSchema.DEFAULT), or the spec should mark schema optional, so no validation warning is emitted.

#### Actual Behavior

Every extract call without --schema logs a MISSING_REQUIRED_ARG validation shadow warning on the backend while the call proceeds (shadow mode tolerates it).

#### Root Cause Analysis

The CLI's extract command only sends `schema` when --schema is given (cli/browser4-cli/src/commands.rs:3894 maps the command to agent_extract), while the backend tool spec declares `schema: ExtractionSchema` required. Shadow validation reports but does not enforce; if shadow mode is ever flipped to enforcing, all schema-less extractions would fail.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:1677 (mcp.validation.shadow); cli/browser4-cli/src/commands.rs:3894 (agent_extract tool mapping)`

#### AI Suggested Improvement

- Make the CLI always populate `schema` with the documented default (ExtractionSchema.DEFAULT) when --schema is absent, or declare `schema` optional in the backend spec.
- Add a CI/e2e assertion that `mcp.validation.shadow` with MISSING_REQUIRED_ARG stays empty for every CLI-mediated tool call.
- Investigate whether this mismatch contributes to the empty-result behavior in issue 1.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: `doctor log <name> grep` mis-binds flags placed before the pattern, silently searching the wrong log and exiting 0

**Severity:** Low
**Category:** Reliability

#### Reproduction

A) ./b4w.ps1 doctor log pulsar grep -i "extraction"
B) ./b4w.ps1 doctor log pulsar grep -i -C 2 "extraction"
C) ./b4w.ps1 doctor log pulsar grep --ignore-case "extraction"
Working order for comparison: ./b4w.ps1 doctor log pulsar grep "extraction" -i -C 2

#### Expected Behavior

Flags in GNU-grep style are accepted regardless of position; the pattern is 'extraction' and pulsar.log is searched.

#### Actual Behavior

A/B print the header '--- doctor log pulsar extraction.log grep "-i" ---' and return no matches with exit 0; C prints '--- doctor log pulsar extraction.log (full) ---' and '(log file empty or not found)' (exit 0) although pulsar.log is 335 KB. Only pattern-first ordering works.

#### Root Cause Analysis

The doctor-log argument binder assigns positional tokens without recognizing that a flag may precede the pattern, so the pattern token is folded into the log name and the flag becomes the pattern; the resulting nonexistent log is not validated against /api/doctor/log-files and the command still exits 0. Fix belongs in the doctor-log option definitions and the generic positional binder.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:3763-3773 (doctor log option defs); cli/browser4-cli/src/main.rs:27898 (handle_doctor_log)`

#### AI Suggested Improvement

- Parse grep flags order-independently (collect flags first, bind the remaining token as pattern).
- Validate `name` against the log-file list; if unknown, exit non-zero and name the closest match.
- Make a missing/empty log an error rather than a successful empty result.

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

**Completion Status:** Successful — all 7 acceptance criteria (AC1–AC7) were demonstrated and verified against the live MockSite pages and DOM. The natural-language branch (AC6) failed nondeterministically on the plain prompt (2 of 3 runs) but succeeded on retry, and the schema-constrained run returned exact, verified structured data.

**Success Rate:** ~95% — every scenario step completed; only the AC6 plain-prompt extract needed retries (2 empty results before success), with a deterministic --schema fallback available.

**Issues Found:** 5

**Most Confusing Aspects:** Deriving matching CSS selectors from the AX snapshot, whose merged element names do not say whether a token is an id or a class (I first tried '.result-panel' and got a silent no-match; '#result-panel' was correct). The nondeterministic empty extract result versus the documented 'fixed envelope' claim. And doctor log grep flags silently changing which log/pattern is searched.

**Most Valuable Improvements:** Server-side retry + non-empty validation for extract (and keeping docs in sync with its actual output shape); marking '#id' vs '.class' syntax in snapshot element data (or exposing id/class attributes); order-independent flag parsing in doctor log with non-zero exits for wrong targets.

**Usability Rating:** 8/10

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

#### Issue 1: extract nondeterministically returns an empty payload for a reasonable prompt (2 of 3 runs), while the backend logs the call as OK

1) ./b4w.ps1 -s ext-eval goto "http://localhost:18080/ec/dp/B0E000002"
2) ./b4w.ps1 -s ext-eval extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout
Run step 2 three times. Backend evidence: ./b4w.ps1 doctor log pulsar grep "agent_extract" (tool.call done lines for this instruction show resultChars=86 twice and resultChars=1801 once, all outcome=OK).

#### Issue 2: SKILL.md says schema-less extract 'always' returns the fixed {title, content, links} envelope, but observed output included additional top-level fields

./b4w.ps1 -s ext-eval extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." --stdout (the successful run) — output was {"title":"Wireless Noise-Cancelling Headphones","price":"$199.99","rating":"4.4","features":["Bluetooth 5.2","30h battery"],"content":"...","links":[...]}

#### Issue 3: Accessibility-tree snapshot merges id/class/name tokens into element names with no syntax, so users cannot tell an id from a class when building CSS selectors

On the form page after submitting:
1) ./b4w.ps1 -s ext-eval snapshot grep "result-panel" → shows a node name containing tokens 'statusPanel … error-panel alert result-panel …'
2) ./b4w.ps1 -s ext-eval htmlsnapshot get text ".result-panel" → 'No elements matched ".result-panel".' (exit 0)
3) ./b4w.ps1 -s ext-eval htmlsnapshot get text "#result-panel" → works ('Submission Result Your form data was captured successfully.')

#### Issue 4: Every schema-less `extract` call trips a backend validation shadow warning (missing required argument 'schema'), signaling a CLI/backend contract mismatch

./b4w.ps1 -s ext-eval extract "product title, price, rating" --stdout   (no --schema)
Then: ./b4w.ps1 doctor log pulsar grep "validation.shadow"
→ WARN MCPToolController — mcp.validation.shadow tool=agent_extract spec=agent.extract(instruction: String, schema: ExtractionSchema) codes=MISSING_REQUIRED_ARG details=missing required argument 'schema' for agent.extract(instruction: String, schema: ExtractionSchema)

#### Issue 5: `doctor log <name> grep` mis-binds flags placed before the pattern, silently searching the wrong log and exiting 0

A) ./b4w.ps1 doctor log pulsar grep -i "extraction"
B) ./b4w.ps1 doctor log pulsar grep -i -C 2 "extraction"
C) ./b4w.ps1 doctor log pulsar grep --ignore-case "extraction"
Working order for comparison: ./b4w.ps1 doctor log pulsar grep "extraction" -i -C 2

