# Issues: comprehensive-ecommerce-workflow

> **Source:** `20261013-133603-comprehensive-ecommerce-workflow.issues.json` | **Date:** 20261013-133603 | **Mode:** dev

## Scenario Background

### Task

All 18 scenario steps completed successfully against MockSite (http://localhost:18080) using session `ec-eval`.

**Product detail page — 4K OLED TV 55** (`http://localhost:18080/ec/dp/B0E000001`)

| Field | Value | Selector |
|---|---|---|
| Title | 4K OLED TV 55 | `#productTitle` |
| Price | $899.99 | `#product-price` (also `.price-row strong`) |
| Description | Premium 4K OLED TV | `p.description` |
| Image URL | `https://picsum.photos/seed/1250857624/200/140` | `#product-image[src]` |
| Product ID | B0E000001 | `article#product-page[data-product-id]` |
| Rating / Stock | 4.6 (521) · In stock (35 available) | `#product-rating` / `.stock-state` |
| Features / Specs | 55 inch, HDR10+ · panel OLED, 3840x2160 | `#product-features li` / `#product-specs` |

Verified five independent ways: `htmlsnapshot get all`, X-SQL (`--sql @file`), `htmlsnapshot grep`, LLM `extract --schema`, `snapshot grep` on the AX tree — all agreed.

**Electronics listing** (`http://localhost:18080/ec/b?node=1292115012`, 6 products): 4K OLED TV 55 $899.99 · Wireless Noise-Cancelling Headphones $199.99 · Portable Bluetooth Speaker $49.99 · Smartphone 128GB $599.00 · USB-C Hub 7-in-1 $29.95 · Wireless Mouse $24.99. `eval --json` counted **6** product links. Full-page screenshot saved as `electronics-listing-fullpage.png`.

**Session preserved:** state saved to `browser-state-ec-eval.json` (no cookies set by the site; localStorage empty for `http://localhost:18080`); detail tab reloaded and content re-verified; 2 tabs left open. Full report: `.test-sessions/20261013T0154334271610Z/product-report.md`.

### Execution Context

**Key Commands:**

`list` → `goto http://localhost:18080/ec/` → `snapshot -v 0` → `snapshot -i` → `click e145` → `htmlsnapshot` → `htmlsnapshot inspect --max 3 --depth 2` → `htmlsnapshot inspect "#product-page" --max 3 --depth 2` → `htmlsnapshot export --file …/product-detail.html` → `htmlsnapshot get all text "p.description"` / `"#details"` → `htmlsnapshot query --sql @product-query.sql --result-only` (×3 iterations) → `htmlsnapshot grep -i '\$[0-9]+\.[0-9]{2}' --selector '#product-page'` → `grep -c` → `config` + `doctor status --section llm` → `extract --schema @file --stdout` (×4 probes) → `tab-new "…/ec/b?node=1292115012"` → `tab-list` → `htmlsnapshot` → `htmlsnapshot inspect --max 6` → `htmlsnapshot get all text "div.product-title"` / `"div.product-price"` → `eval --json` → `screenshot --full-page -o` → `tab-select 1` → `reload` → `snapshot -v 0` → `snapshot grep -i -C 1 "hdr10|3840x2160|oled"` → `state-save` → `list --json` / `list --verbose`.

**Key decisions / workarounds:**
- Two sessions (`price-watch`, default) from an earlier run were present, so per SKILL.md's concurrent-runs rule I used a dedicated named session `-s ec-eval` for every command.
- MockSite was already listening and serving the correct pages (verified by curl before starting), so I did not launch a duplicate `./bin/test.ps1 mock-site` (avoids a concurrent Maven build).
- `snapshot` prints only a 30-line preview; I read the saved YAML files for the full tree.
- `inspect` never surfaced the unique detail-page selectors, so I discovered them via `htmlsnapshot export` + local inspection of the exported HTML, then verified each with `get all`/X-SQL.
- `DOM_FIRST_IMG(DOM, '#product-image')` silently returned `""`; switched to `DOM_FIRST_ATTR(DOM, '#product-image', 'src')` after probing 9 selector variants.
- First `extract --schema` printed `{}` (exit 0, no warning); an identical retry returned the full JSON.

---



One note for the record: no `### Issue N` markdown duplicates are provided — the JSON block above replaces Sections C and D per the preferred format, and all temp files were written under `.test-sessions/20261013T0154334271610Z/`.

---

## Issues Found (6 issues)

### Issue 1: DOM_FIRST_IMG silently returns empty string when the selector matches the <img> element without an explicit img tag

**Severity:** Medium
**Category:** Reliability

#### Reproduction

On http://localhost:18080/ec/dp/B0E000001 run an X-SQL query with rows FROM DOM_LOAD_AND_SELECT(@url, 'article#product-page') selecting:
  DOM_FIRST_IMG(DOM, '#product-image') -> ""
  DOM_FIRST_IMG(DOM, '.product-image') -> ""
while these all return https://picsum.photos/seed/1250857624/200/140:
  DOM_FIRST_IMG(DOM, 'img'), DOM_FIRST_IMG(DOM, 'img.product-image'), DOM_FIRST_IMG(DOM, '.product-images'), DOM_FIRST_IMG(DOM, '.product-images img'), DOM_FIRST_ATTR(DOM, '#product-image', 'src')

#### Expected Behavior

The documented "First image absolute src" should return the src when the selector resolves to an <img> element (the id/class of the image is the most natural selector a user has), or fail loudly.

#### Actual Behavior

Empty string, exit code 0, no warning to the CLI user. The consumer cannot distinguish 'no image on the page' from 'selector shape not supported'.

#### Root Cause Analysis

The DOM_*_IMG family uses an img-scanning selector path: a selector without an explicit `img` tag token appears to be treated as a container whose descendant image is searched, so a selector that matches the <img> itself (e.g. #product-image) finds no descendant image and silently yields "". This is a sibling of the known :expr silent-no-match behavior the backend already logs a warning for (HTMLSnapshotToolExecutor.kt), but the id/class case is not covered by that detection and the warning is server-log only. Engine parity fix is tracked upstream with the pulsar-ql dependency (per the code comment).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt (DOM_*_IMG warning path ~758-770, hasDomFirstImgExpr ~1039-1045); engine behavior lives in the X-SQL engine (pulsar-ql dependency)`

#### AI Suggested Improvement

- Make the selector path return the matched element's (absolute) src when the selector itself resolves to an <img>, regardless of whether the selector text contains the img tag
- When a DOM_*_IMG call yields an empty result, surface a result-level warning (not just a server log line) naming the likely cause
- Document the selector constraint in references/x-sql-dom-select-functions.md next to the existing :expr caveat, with the id/class example
- Add a test mirroring the id-selector case alongside the existing :expr detection tests

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: extract --stdout prints {} and exits 0 when the LLM returns no data — the empty-extraction warning is suppressed in raw mode

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Step 10 of the scenario: run
  ./b4w.ps1 -s ec-eval extract "Extract the product title, price, description, image URL, and feature bullets from this product detail page" --schema '@.test-sessions/.../product-schema.json' --stdout
Observed: {} with exit=0. An identical retry returned the full 5-field JSON.

#### Expected Behavior

Either the empty result should carry a visible diagnostic (stderr warning and/or non-zero exit), or the command should be documented as 'exit 0 even when no data was extracted'.

#### Actual Behavior

Only `{}` is printed; no warning, exit code 0. Scripts piping extract --stdout into jq/CI see a valid-but-empty document and proceed. (In non-raw/file mode the CLI does print '⚠️ Extraction produced no data'.)

#### Root Cause Analysis

handle_extract computes detect_empty_extraction() (which deliberately treats "{}" as empty — there is a unit test for it) but the raw/--stdout branch prints the payload only and discards that flag; the warning text exists solely in the non-raw branch. The transient trigger was an empty LLM (DeepSeek) response, but the CLI-side behavior is deterministic whenever the backend returns an empty payload.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_extract (~8650-8685, the `if raw { println }` branch); detect_empty_extraction at ~8727`

#### AI Suggested Improvement

- Print the empty-extraction warning to stderr in the raw branch (stderr does not corrupt stdout JSON), and keep stdout as pure JSON
- Consider a non-zero exit code (or an opt-out flag such as --allow-empty) so pipelines can detect the failure
- Include the retry hint from the non-raw warning in the stderr message

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: htmlsnapshot inspect only discovers repeating patterns — it cannot surface the unique landmark selectors (product title/price/description/image) on a detail page

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

On http://localhost:18080/ec/dp/B0E000001 run:
  ./b4w.ps1 -s ec-eval htmlsnapshot inspect --max 3 --depth 2
  => reports a repeating pattern of .recommendation-card ('Customers also viewed' cards)
  ./b4w.ps1 -s ec-eval htmlsnapshot inspect "#product-page" --max 3 --depth 2
  => reports span.info-pill; the header even claims "The tool found that '#product-page' repeats as a sibling group" although it occurs exactly once

#### Expected Behavior

Per the scenario (and the inspect help: 'discover CSS selectors for recurring patterns' is the only described mode), a user on a detail page wants selectors for the main product title, price, description and image — the most important extraction targets on the page.

#### Actual Behavior

Only repeating sibling groups are mined; the unique landmarks (#productTitle, #product-price, p.description, #product-image) never appear. The user must fall back to htmlsnapshot export + manual HTML reading to find them. The scoped-narrative text is also misleading (claims the scope selector repeats, naming the wrong element).

#### Root Cause Analysis

inspect is implemented as repeated-pattern mining; elements with a single occurrence are not candidates by design, and the narrative/rendering labels the auto-discovered pattern with the scope selector's text, producing the misleading sentence. A new user has no documented pointer that unique elements require a different tool (the tool's own tip mentions 'htmlsnapshot summary' only for page landmarks).

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_inspect (~10848; narrative strings ~11058 and 'Suggested selectors' ~11229); pattern analysis on the backend htmlsnapshot inspect tool`

#### AI Suggested Improvement

- Add a 'unique landmarks' section to inspect output (h1/h2 headings, price-like text nodes, images, elements with ids) with ready-to-use selectors, even when no repeat pattern exists
- Fix the narrative when a scope selector is supplied: name the actual repeated descendant (e.g. '#product-page span.info-pill (4 matches)'), never claim the scope itself repeats
- Document in `--help htmlsnapshot inspect` and the SKILL that inspect answers 'what repeats?' and that unique-element selectors come from summary/export/get probing

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: list URL column shows '-' for active sessions that have open pages, contradicting the documented meaning of '-'

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s ec-eval goto http://localhost:18080/ec/ ; ./b4w.ps1 -s ec-eval tab-new http://localhost:18080/ec/b?node=1292115012 ; ./b4w.ps1 list  (also list --verbose and list --json)

#### Expected Behavior

Per SKILL.md: 'list shows every session and its current page URL … "-" means the backend is unreachable or the saved session is stale'. An active session currently showing a page should print that URL.

#### Actual Behavior

Every active session (including ec-eval with two live tabs and freshly executed commands, backend_reachable:true) shows URL '-'. The column is effectively non-informative, so users cannot tell which page each session is on — exactly the problem the column is meant to solve when running multiple sessions.

#### Root Cause Analysis

Unknown — needs investigation. Either the backend session-status payload does not carry a per-session URL in development mode, or the CLI list handler does not read/populate it from the session's state (the session's last_access timestamp does update, so the state file is being written).

#### Code Pointer

`cli/browser4-cli/src/main.rs (list command handler / session row rendering); backend session status endpoint used by list`

#### AI Suggested Improvement

- Persist the active tab's URL into session state on every navigation/tab switch and render it in the URL column
- If the URL is genuinely unavailable for some session kinds, mark it 'unknown' in the docs instead of reusing '-' (which is documented to mean unreachable/stale)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: htmlsnapshot grep has no -o (only-matching) option and its error message does not list the supported flags

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s ec-eval htmlsnapshot grep -o 'id="product-price"'

#### Expected Behavior

Either -o works (grep-standard, ideal for pulling matched substrings such as prices), or the error states that -o is unsupported and lists the valid flags.

#### Actual Behavior

Error: error: unexpected positional arguments (this command accepts 1): ["-o"] — a doubled 'Error: error:' prefix, no mention of which flags are valid and no --help pointer.

#### Root Cause Analysis

The grep option parser defines a fixed flag set (-e, -i, -n, -A/-B/-C, -v, -c, -l, -F, -w, --no-line-number, --selector, --selector-all) with no -o, and its unknown-token error path only echoes the rejected positional.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_grep (~11652)`

#### AI Suggested Improvement

- Add -o/--only-matching to htmlsnapshot grep (and top-level snapshot grep for parity), which is the natural way to harvest matched text like $899.99
- Improve the error to: 'unknown option -o; supported: -e, -i, -A, -B, -C, -v, -c, -l, -F, -w (see --help htmlsnapshot)' and drop the doubled 'Error: error:' prefix

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: extract without --schema ignores the requested field list, despite the help example implying field selection

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s ec-eval extract "product title, price, ratings" --stdout  (the exact example from --help extract)

#### Expected Behavior

Output shaped around the requested fields (title/price/rating), as the example 'browser4-cli extract "product name, price, ratings"' suggests.

#### Actual Behavior

A fixed generic envelope {"title": …, "content": <entire page text blob>, "links": […]}: the price and rating appear only embedded inside the content blob, not as fields.

#### Root Cause Analysis

Without --schema the extraction returns a fixed {title, content, links} document; the natural-language instruction only guides which text ends up inside content. The help's argument description ('What data to extract, e.g. product name, price, ratings') reads as if it selects output fields.

#### Code Pointer

`cli/browser4-cli/src/help.rs / cli/browser4-cli/src/main.rs (extract help text); backend extract tool`

#### AI Suggested Improvement

- Reword the help: '<instruction> guides what content to collect; use --schema to get named fields as top-level JSON'
- Make the first example schema-constrained (e.g. extract "product name, price, ratings" --schema '{"fields":[…]}") so the example's output matches its expectation
- Consider a warning when the instruction mentions commas/field-like lists but no --schema is given

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

**Completion Status:** Successful — all 18 scenario steps completed, including the conditional LLM extract step (DeepSeek key configured). Five independent extraction methods produced consistent product data; session state was saved and the reloaded detail page re-verified.

**Success Rate:** 95% — one transient silent-empty extract (fixed by retry) and one selector-discovery detour; every other step succeeded first try.

**Issues Found:** 6

**Most Confusing Aspects:** 1) htmlsnapshot inspect was the biggest surprise: tasked to discover selectors for the product title/price/description/image via `--max 3 --depth 2`, it surfaced only 'what repeats' patterns (recommendation cards, info pills) and never the unique page landmarks, and its scoped narrative emitted a misleading sentence about the scope selector repeating — I had to export the HTML and read it to find #productTitle / #product-price / p.description / #product-image. 2) DOM_FIRST_IMG returning "" for a straightforward #id selector with no warning — the silent no-match class of behavior is hard to diagnose. 3) extract --stdout printing {} with exit 0 on an empty LLM response. 4) The list URL column showing '-' for my active, reachable session with two live tabs.

**Most Valuable Improvements:** 1) Let inspect list unique landmarks (headings, price nodes, images, id'd elements) or clearly point to summary/export for them; fix its scoped narrative. 2) Make DOM_*_IMG handle selectors that resolve to the img element itself (id/class) and warn on any silent empty image result. 3) Surface the empty-extraction warning on stderr in extract --stdout mode (or exit non-zero) so pipelines cannot silently consume {}. 4) Populate the list URL column so multi-session users can see which session is where.

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

#### Issue 1: DOM_FIRST_IMG silently returns empty string when the selector matches the <img> element without an explicit img tag

On http://localhost:18080/ec/dp/B0E000001 run an X-SQL query with rows FROM DOM_LOAD_AND_SELECT(@url, 'article#product-page') selecting:
  DOM_FIRST_IMG(DOM, '#product-image') -> ""
  DOM_FIRST_IMG(DOM, '.product-image') -> ""
while these all return https://picsum.photos/seed/1250857624/200/140:
  DOM_FIRST_IMG(DOM, 'img'), DOM_FIRST_IMG(DOM, 'img.product-image'), DOM_FIRST_IMG(DOM, '.product-images'), DOM_FIRST_IMG(DOM, '.product-images img'), DOM_FIRST_ATTR(DOM, '#product-image', 'src')

#### Issue 2: extract --stdout prints {} and exits 0 when the LLM returns no data — the empty-extraction warning is suppressed in raw mode

Step 10 of the scenario: run
  ./b4w.ps1 -s ec-eval extract "Extract the product title, price, description, image URL, and feature bullets from this product detail page" --schema '@.test-sessions/.../product-schema.json' --stdout
Observed: {} with exit=0. An identical retry returned the full 5-field JSON.

#### Issue 3: htmlsnapshot inspect only discovers repeating patterns — it cannot surface the unique landmark selectors (product title/price/description/image) on a detail page

On http://localhost:18080/ec/dp/B0E000001 run:
  ./b4w.ps1 -s ec-eval htmlsnapshot inspect --max 3 --depth 2
  => reports a repeating pattern of .recommendation-card ('Customers also viewed' cards)
  ./b4w.ps1 -s ec-eval htmlsnapshot inspect "#product-page" --max 3 --depth 2
  => reports span.info-pill; the header even claims "The tool found that '#product-page' repeats as a sibling group" although it occurs exactly once

#### Issue 4: list URL column shows '-' for active sessions that have open pages, contradicting the documented meaning of '-'

./b4w.ps1 -s ec-eval goto http://localhost:18080/ec/ ; ./b4w.ps1 -s ec-eval tab-new http://localhost:18080/ec/b?node=1292115012 ; ./b4w.ps1 list  (also list --verbose and list --json)

#### Issue 5: htmlsnapshot grep has no -o (only-matching) option and its error message does not list the supported flags

./b4w.ps1 -s ec-eval htmlsnapshot grep -o 'id="product-price"'

#### Issue 6: extract without --schema ignores the requested field list, despite the help example implying field selection

./b4w.ps1 -s ec-eval extract "product title, price, ratings" --stdout  (the exact example from --help extract)

