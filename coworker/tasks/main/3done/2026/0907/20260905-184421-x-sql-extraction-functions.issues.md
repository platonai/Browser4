# Issues: x-sql-extraction-functions

> **Source:** `20260905-184421-x-sql-extraction-functions.full.md` | **Date:** 20260905-184421 | **Mode:** dev

## Scenario Background

### Task

The task was **completed successfully**. All 9 steps were executed against MockSite (`http://localhost:18080/ec/b?node=1292115012`, "Category: Electronics", 6 products):

1. **Navigation** — `./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"` auto-started the backend and opened the session (first-run latency absorbed by the daemon startup; no manual server setup needed).
2. **HTML snapshot** — `htmlsnapshot` capture succeeded (9 KB, 7 images, 22 interactive elements); exported to `.test-sessions/electronics-page.html`.
3. **Selector discovery** — `htmlsnapshot inspect --max 3 --depth 3` auto-discovered `.product-card` (6 matches) and suggested `div.product-title`, `a.product-link`, `img.product-img`, plus sample-structure selectors `div.product-price` / `.product-rating` and `[data-category-id="1292115012"]`.
4–6. **X-SQL with DOM + STR + ARRAY functions** — the final query (`.test-sessions/xsql-final.sql`) extracted per-product: trimmed title (`DOM_FIRST_TEXT` + `STR_TRIM`), price as number (`DOM_FIRST_FLOAT(DOM,'div.product-price',0.0)` → 899.99, 199.99…), absolute detail link (`DOM_FIRST_HREF`), image URL (`DOM_FIRST_IMG`), data attributes on the card (`DOM_FIRST_ATTR(DOM, ':root', 'data-category-id')`) and rating (`data-rating`), uppercase-normalized title (`STR_UPPER_CASE`), numbers-from-text (`STR_FIRST_FLOAT` over `"$899.99"` and `"4.6 (521)"` → 4.6), truncated display title (`STR_ABBREVIATE`), and a badge fallback chain (`ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(span.badge → .product-badges → .product-rating → 'no-badge'))` wrapped in `STR_DEFAULT_IF_BLANK`). A second run without the price filter proved the fallback fires on the 3 badge-less cards (yields rating text like `4.3 (901)`).
7. **PowerCSS `:expr()`** — used in the FROM clause: `DOM_LOAD_AND_SELECT(@url, '.product-card:expr(width >= 150 && height >= 200)')` — accepted and returned all 6 rows.
8. **WHERE / ORDER BY / LIMIT** — `WHERE CAST(DOM_FIRST_FLOAT(...) AS DOUBLE) >= 50.0` (the documented CAST workaround), `ORDER BY … ASC`, `LIMIT 3` → returned Wireless Headphones ($199.99), Smartphone ($599.00), 4K OLED TV ($899.99), correctly sorted.
9. **Data review** — all 11 columns populated correctly across runs; exit codes, table/JSON output formats and the live-DOM (URL-less) query path verified.

### Execution Context

| Step | Command | Outcome |
|---|---|---|
| Prep | `pwd`, `mkdir -p .test-sessions` | Repo root confirmed; scratch dir ready |
| Docs | `./b4w.ps1 help` + full `SKILL.md` + references (`htmlsnapshot.md`, `x-sql*.md`, `power-dom.md`) | Learned capture→inspect→query workflow; noted quoting, CAST, `:expr`, and `DOM_FIRST_HREF` caveats |
| 1 | `./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"` | Session auto-created, page loaded (title "Category: Electronics") |
| 2 | `./b4w.ps1 htmlsnapshot` → `htmlsnapshot export --file .test-sessions/electronics-page.html` | Stored snapshot + local copy for structure analysis |
| 3 | `./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3` | Auto-discovered `.product-card`; selectors identified |
| Probe | `.test-sessions/xsql-probe.sql` | Verified `...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: DOM_FIRST_IMG with a PowerCSS :expr() selector silently drops the image column while exiting 0

**Severity:** High
**Category:** Reliability

#### Reproduction

Write a query using DOM_FIRST_IMG(DOM, 'img:expr(width > 200)') against any page with matching wide images (see .test-sessions/xsql-img-expr.sql) and run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @.test-sessions/xsql-img-expr.sql --format table; echo $?

#### Expected Behavior

Return the image src for every match, or fail with an explicit error and non-zero exit code.

#### Actual Behavior

img_filtered is empty for every row, output says '2 rows returned.', and the exit code is 0. The only mitigation is a warning banner printed after the result table (text-scan heuristic). Scripted/--quiet/--json consumers cannot detect the data loss; the empty column is indistinguishable from a legitimate no-match.

#### Root Cause Analysis

The image-scanning path behind the DOM_*_IMG helpers does not evaluate :expr(...) and matches nothing (the doc comment in SKILL.md and x-sql.md:349 names this). The behavior is documented in several reference files, but the engine neither errors nor returns a non-200 envelope, so 'success' semantics are preserved. The CLI-level warning at main.rs:7258 is the only guard and it is a string heuristic (see next issue).

#### Code Pointer

`cli/browser4-cli/src/main.rs:6975 (sql_uses_dom_first_img_expr) and :7258 (warning emission); the failing selector evaluation lives in the external pulsar-ql DomSelectFunctions image helpers (ai.platon.pulsar.ql) which ignore :expr.`

#### AI Suggested Improvement

- Make the backend DOM_*_IMG selector path evaluate :expr, or throw a descriptive X-SQL error (417) when :expr appears inside a DOM_*_IMG selector argument instead of silently returning nothing.
- If the engine limitation must stay, at minimum return a per-cell placeholder/error marker and a non-zero exit code so pipelines notice.
- Keep the CLI warning but make it precise (see next issue) so healthy queries are not flagged.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
create a issue on https://github.com/platonai/Browser4base, do not fix

---

### Issue 2: htmlsnapshot inspect never suggests recurring class selectors for elements that carry ids — .product-price and .product-rating are missing from 'Suggested selectors' on an e-commerce page

**Severity:** Medium
**Category:** Product

#### Reproduction

1) ./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"  2) ./b4w.ps1 htmlsnapshot  3) ./b4w.ps1 htmlsnapshot inspect ".product-card"  → sample structures show div#product-price-B0E00000X.product-price / div#product-rating-*.product-rating, but 'Suggested selectors' contains only div.product-title, a.product-link, img.product-img and (3/6) div.product-badges/span.badge — no .product-price, no .product-rating, although they occur in 6/6 cards. 4) ./b4w.ps1 htmlsnapshot inspect ".product-price" (6 matches) → suggestion list is empty except bare-tag 'div'.

#### Expected Behavior

Class selectors recurring in 100% of analyzed matches (div.product-price, div.product-rating) should be suggested for extraction, like div.product-title is. The price selector is the primary field an e-commerce extraction query needs (SKILL.md's own quickstart extracts '.price').

#### Actual Behavior

Id-bearing leaf data elements are absent from the ranked suggestions; they surface only as per-card unique-id singletons (#product-B0E000001 div#product-price-B0E000001.product-price …) in the raw JSON's singletonSuggestions — useless for row-wise extraction — and the CLI does not render singletonSuggestions at all. The omission is silent: no error, output looks complete. Verified in both human and raw JSON output (--json).

#### Root Cause Analysis

In inspectDocument (MCPToolController.kt:1723-1832) the candidate walk records plain 'tag.class' candidates plus compound 'tag.class#id' candidates for elements with ids; the inline comment (lines 1744-1749) says unique template ids should fall out at the threshold while the recurring plain class survives. Empirically the plain class candidate for id-bearing elements never reaches the ranked list (count-6 div.product-price loses to count-3 div.product-badges, which is impossible by the stated score formula), so a filtering/dedup step in the walk or ranking must be dropping it. Pinpointing the exact step needs a debug trace (candidate dedupe via the SelectorCandidate data class or the 'seen' per-match set is the prime suspect).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:1723-1832 (inspectDocument candidate walk) — drop suspected around lines 1740-1755 or 1812-1826`

#### AI Suggested Improvement

- Trace candidateStats for 'div.product-price' inside inspectDocument with a unit test that asserts an id-bearing class element yields a surviving plain class suggestion (fixture: the mock e-commerce card).
- Fix the walk so plain 'tag.class' candidates of id-bearing elements are counted independently of their compound unique-id form.
- Consider rendering singletonSuggestions in the CLI output, since backend already computes price/id elements that the CLI currently hides.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: CLI subcommand help for `htmlsnapshot query` contradicts the reference docs about what data the query runs against

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run: ./b4w.ps1 htmlsnapshot --help  → 'htmlsnapshot query [url]   Run X-SQL against the HTML snapshot stored in Browser4's page storage via the scrape API.' Then compare with skills/browser4-cli/references/htmlsnapshot.md ('query never reads the stored htmlsnapshot cache…') and the main help text, and with observed behavior (a URL-less query returns the current live page state).

#### Expected Behavior

All help surfaces should agree on the data source semantics that SKILL.md stresses as fundamental: no-URL/current-URL → live DOM; other URL → independent fetch.

#### Actual Behavior

The subcommand help describes query as reading the stored snapshot, which the reference docs explicitly deny; main help (b4w.ps1 help) already describes the correct live-DOM semantics, so users get contradictory guidance depending on which help surface they read. A user deciding between 'capture then get' vs 'query' on this basis can draw the wrong conclusion about freshness/login state.

#### Root Cause Analysis

Stale description string in the CLI help catalog that predates the live-DOM seeding behavior; help.rs was not updated when the query path changed.

#### Code Pointer

`cli/browser4-cli/src/help.rs:1649 (htmlsnapshot query description string)`

#### AI Suggested Improvement

- Rewrite the string to: 'Run X-SQL. Without a URL (or for the current page URL) the query is seeded from the session's LIVE page first; an explicit different URL is fetched independently. Does not read the stored htmlsnapshot capture cache.'
- Grep the help catalog for other stale 'page storage' claims about query.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: False-positive 'DOM_FIRST_IMG ignores :expr' warning fires on fully successful queries that use :expr only in the FROM clause

**Severity:** Low
**Category:** Product

#### Reproduction

Run .test-sessions/xsql-final.sql (DOM_FIRST_IMG(DOM, 'img.product-img') — plain selector — plus :expr(width >= 150 && height >= 200) in DOM_LOAD_AND_SELECT's FROM selector): ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @.test-sessions/xsql-final.sql --format table

#### Expected Behavior

No warning: the :expr filter is only in the FROM clause where it IS evaluated (x-sql.md:349), and image_url is correctly populated for all rows.

#### Actual Behavior

Every row has a correct image_url, yet the CLI appends '⚠️ DOM_FIRST_IMG does not evaluate PowerCSS :expr(...) filters — a filtered selector silently matches nothing'. The warning is factually wrong for this query and erodes trust in the tool's diagnostics.

#### Root Cause Analysis

sql_uses_dom_first_img_expr (main.rs:6975-6981) tests string co-occurrence: upper.contains(":EXPR(") && (contains DOM_FIRST_IMG/DOM_NTH_IMG/DOM_ALL_IMGS) across the whole SQL text. It never checks whether :expr is actually an argument of the IMG call. Unit tests cover pure positive and pure negative cases but not the mixed case (DOM_FIRST_IMG with a plain selector + :expr elsewhere), which is exactly the composition SKILL.md's own quickstart template produces.

#### Code Pointer

`cli/browser4-cli/src/main.rs:6975-6981 (fn sql_uses_dom_first_img_expr)`

#### AI Suggested Improvement

- Parse the argument span of each DOM_FIRST_IMG/DOM_NTH_IMG/DOM_ALL_IMGS call (balanced parentheses) and flag only when :expr appears inside that span.
- Add a unit test for the mixed case: DOM_FIRST_IMG(DOM, 'img.product-img') with :expr in DOM_LOAD_AND_SELECT must not warn.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: htmlsnapshot inspect coverage fractions (N/N) are computed over the analyzed subset, which reads as full-set coverage when --max truncates

**Severity:** Low
**Category:** UX

#### Reproduction

After capture run: ./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3  → header '(6 matches, 3 analyzed)'; rows then print '3/3 (100%) div.product-title', '2/3 (67%) div.product-badges' — but the page actually has badges on 3 of 6 cards, so 67% of the analyzed subset is not the true 50% coverage of the set a user will extract from.

#### Expected Behavior

Row percentages/denominators should be unambiguous: either computed over the total match count, or explicitly labeled as '2 of 3 analyzed' so users don't misjudge selector robustness across the full result set.

#### Actual Behavior

Rows show 'N/N (%)' where the denominator is the (possibly capped) analyzed count, while the heading and narrative emphasize total matches (6). The disclosure exists ('It analyzed 3 of the 6 occurrences') but the per-row fractions still read as full coverage; with --max used per the task guidance, every row shows a perfect 100% ceiling that later proves wrong on unseen matches.

#### Root Cause Analysis

Backend computes coverage as count/matches.size where matches is the analyzed (post-cap) list (MCPToolController.kt:1901), and the CLI renders analyzed as the denominator (main.rs:8111).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:1901 (coverage = stats.count * 100.0 / matches.size); cli/browser4-cli/src/main.rs:8111`

#### AI Suggested Improvement

- When --max < total matches, compute counts for candidate selectors across ALL matches (cheap class/id string counting) and report coverage over the full matchCount, capping only the expensive depth-walk samples.
- Otherwise label the rows explicitly, e.g. '2/3 analyzed (67% of 3)' or add a footnote row: 'coverage measured over the 3 analyzed of 6 matches'.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 6: DOM_FIRST_HREF silently returns an empty string for class-only selectors while the tag-qualified form works

**Severity:** Low
**Category:** Reliability

#### Reproduction

Probe query (xsql-probe.sql): SELECT DOM_FIRST_HREF(DOM, 'a.product-link') AS href_tagged, DOM_FIRST_HREF(DOM, '.product-link') AS href_class FROM DOM_LOAD_AND_SELECT(@url, '.product-card') → href_tagged = 'http://localhost:18080/ec/dp/B0E000001', href_class = '' (exit 0).

#### Expected Behavior

Both forms address the same anchor and should return the same href; at minimum a class-only selector should not fail silently.

#### Actual Behavior

Class-only selector yields empty strings with a successful envelope and exit 0 — indistinguishable from 'no link in card'. A new user who discovers '.product-link' via inspect and writes DOM_FIRST_HREF(DOM, '.product-link') (class-only, mirroring DOM_FIRST_TEXT usage) loses the whole column without any error.

#### Root Cause Analysis

The href-scanning scalar function apparently requires an anchor tag-name context to resolve (external pulsar-ql implementation, referenced in the x-sql.md note: 'DOM_FIRST_HREF(DOM, sel) can return an empty string for a class-only selector while the tag-qualified form a.product-link works'). The x-sql.md:332 note documents the symptom, but the failure mode stays silent and the note is buried in a catalog index rather than the quick-reference/common-mistakes tables.

#### Code Pointer

`External ai.platon.pulsar.ql DomFunctions/DomSelectFunctions href helper; doc note at skills/browser4-cli/references/x-sql.md:332`

#### AI Suggested Improvement

- Fix the engine to accept class-only descendant selectors for href extraction, or raise a descriptive error instead of returning ''.
- Promote the caveat into SKILL.md's 'Common mistakes and solutions' table (symptom: DOM_FIRST_HREF returns empty for .class selectors) and standardize the extraction templates on DOM_FIRST_ATTR(DOM, sel, 'href') / DOM_FIRST_HREF(DOM, 'a.<class>').

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: MAKE_ARRAY is load-bearing in every ARRAY-function example but is never documented as a function

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/references/x-sql-array-functions.md: every ARRAY_FIRST_NOT_BLANK / ARRAY_FIRST_NOT_EMPTY example calls MAKE_ARRAY(...), yet the page's Quick Index lists only '3 functions' (ARRAY_JOIN_TO_STRING, ARRAY_FIRST_NOT_BLANK, ARRAY_FIRST_NOT_EMPTY) and x-sql.md's ARRAY namespace table likewise omits it.

#### Expected Behavior

MAKE_ARRAY should be listed with its signature and semantics (including NULL/blank handling) wherever ARRAY functions are documented, so users know it exists and how to build the fallback chains the docs promote.

#### Actual Behavior

MAKE_ARRAY appears only inside examples with no definition, source, or entry in any function index. It works (verified: the fallback chains ran), so the gap is purely discoverability — a new user cannot tell if it is an H2 built-in, a custom alias, or a typo, and has no documented way to construct arrays other than copying examples.

#### Root Cause Analysis

Documentation gap: array-function reference and master index were never updated with the MAKE_ARRAY helper that the examples depend on.

#### Code Pointer

`skills/browser4-cli/references/x-sql-array-functions.md (add a MAKE_ARRAY section); skills/browser4-cli/references/x-sql.md ARRAY namespace table`

#### AI Suggested Improvement

- Add a documented MAKE_ARRAY(values...) section with signature, NULL handling, and a one-line note on the equivalent H2 literal syntax if any.
- Add it to the x-sql.md ARRAY namespace table so the master function index is complete.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 9 task steps completed: navigation, capture, selector discovery (inspect --max 3 --depth 3), a full X-SQL query using every requested DOM/STR/ARRAY function, PowerCSS :expr() in the FROM clause, WHERE/ORDER BY/LIMIT, and review of the extracted data (two output variants plus negative probes for error quality).

**Success Rate:** 95 — one probe column (DOM_FIRST_HREF class-only form) returned empty and one final-query warning was spurious, but neither affected the required deliverables; every documented function executed as specified on the first run.

**Issues Found:** 7

**Major Blockers:** None. The daemon auto-started cleanly, MockSite was reachable, and no command needed a retry. The documented CAST workaround for numeric WHERE and the tag-qualified DOM_FIRST_HREF form were applied pre-emptively after reading the references.

**Most Confusing Aspects:** 1) Silent empty results with exit code 0 for DOM_FIRST_IMG + :expr and DOM_FIRST_HREF with a class-only selector — data loss looks like success. 2) htmlsnapshot inspect's 'Suggested selectors' omitting .product-price/.product-rating (id-bearing elements) even though they recur in every card — the exact selectors the task needed. 3) The ⚠️ warning banner printed after fully successful queries when :expr appears anywhere in the SQL. 4) Contradictory descriptions of htmlsnapshot query's data source between subcommand help and reference docs.

**Most Valuable Improvements:** 1) Fix inspectDocument so recurring class selectors of id-bearing elements (.product-price) reach 'Suggested selectors' — the flagship discovery path for e-commerce extraction. 2) Make the DOM_*_IMG/:expr and class-only DOM_FIRST_HREF silent failures loud (error or non-zero exit) or precisely warned, so no column can vanish silently. 3) Scope the :expr warning heuristic to the DOM_FIRST_IMG argument span to eliminate false positives. 4) Align the htmlsnapshot query help string with the documented live-DOM semantics.

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

#### Issue 1: DOM_FIRST_IMG with a PowerCSS :expr() selector silently drops the image column while exiting 0

Write a query using DOM_FIRST_IMG(DOM, 'img:expr(width > 200)') against any page with matching wide images (see .test-sessions/xsql-img-expr.sql) and run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @.test-sessions/xsql-img-expr.sql --format table; echo $?

#### Issue 2: htmlsnapshot inspect never suggests recurring class selectors for elements that carry ids — .product-price and .product-rating are missing from 'Suggested selectors' on an e-commerce page

1) ./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"  2) ./b4w.ps1 htmlsnapshot  3) ./b4w.ps1 htmlsnapshot inspect ".product-card"  → sample structures show div#product-price-B0E00000X.product-price / div#product-rating-*.product-rating, but 'Suggested selectors' contains only div.product-title, a.product-link, img.product-img and (3/6) div.product-badges/span.badge — no .product-price, no .product-rating, although they occur in 6/6 cards. 4) ./b4w.ps1 htmlsnapshot inspect ".product-price" (6 matches) → suggestion list is empty except bare-tag 'div'.

#### Issue 3: CLI subcommand help for `htmlsnapshot query` contradicts the reference docs about what data the query runs against

Run: ./b4w.ps1 htmlsnapshot --help  → 'htmlsnapshot query [url]   Run X-SQL against the HTML snapshot stored in Browser4's page storage via the scrape API.' Then compare with skills/browser4-cli/references/htmlsnapshot.md ('query never reads the stored htmlsnapshot cache…') and the main help text, and with observed behavior (a URL-less query returns the current live page state).

#### Issue 4: False-positive 'DOM_FIRST_IMG ignores :expr' warning fires on fully successful queries that use :expr only in the FROM clause

Run .test-sessions/xsql-final.sql (DOM_FIRST_IMG(DOM, 'img.product-img') — plain selector — plus :expr(width >= 150 && height >= 200) in DOM_LOAD_AND_SELECT's FROM selector): ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @.test-sessions/xsql-final.sql --format table

#### Issue 5: htmlsnapshot inspect coverage fractions (N/N) are computed over the analyzed subset, which reads as full-set coverage when --max truncates

After capture run: ./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3  → header '(6 matches, 3 analyzed)'; rows then print '3/3 (100%) div.product-title', '2/3 (67%) div.product-badges' — but the page actually has badges on 3 of 6 cards, so 67% of the analyzed subset is not the true 50% coverage of the set a user will extract from.

#### Issue 6: DOM_FIRST_HREF silently returns an empty string for class-only selectors while the tag-qualified form works

Probe query (xsql-probe.sql): SELECT DOM_FIRST_HREF(DOM, 'a.product-link') AS href_tagged, DOM_FIRST_HREF(DOM, '.product-link') AS href_class FROM DOM_LOAD_AND_SELECT(@url, '.product-card') → href_tagged = 'http://localhost:18080/ec/dp/B0E000001', href_class = '' (exit 0).

#### Issue 7: MAKE_ARRAY is load-bearing in every ARRAY-function example but is never documented as a function

Read skills/browser4-cli/references/x-sql-array-functions.md: every ARRAY_FIRST_NOT_BLANK / ARRAY_FIRST_NOT_EMPTY example calls MAKE_ARRAY(...), yet the page's Quick Index lists only '3 functions' (ARRAY_JOIN_TO_STRING, ARRAY_FIRST_NOT_BLANK, ARRAY_FIRST_NOT_EMPTY) and x-sql.md's ARRAY namespace table likewise omits it.



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. CLI changes verified with full `cargo test --bin browser4-cli` (incl. new `:expr`-scope tests); `InspectDocumentTest` green.

| Issue | Decision | Resolution |
|---|---|---|
| 1 — DOM_FIRST_IMG + `:expr` silently drops the column (High) | ACCEPT with improvements — do not fix, file upstream | Filed as upstream issue on the engine repo: https://github.com/platonai/Browser4base/issues/5 (engine-level `:expr` evaluation or descriptive error). No in-repo fix per the review note. |
| 2 — inspect never suggests recurring classes of id-bearing elements (Medium) | ACCEPT | Already fixed in HEAD (commit 0906407f39): the candidate walk records the plain `tag.class` form alongside the compound `tag.class#id` form so recurring classes of id-bearing elements reach the suggestions; regression test `InspectDocumentTest.recurringClassWithUniquePerCardIds` passes. The 2026-09-05 evaluation ran against a stale runtime bundle; verified fixed on current sources. |
| 3 — htmlsnapshot query subcommand help contradicts live-DOM semantics (Medium) | ACCEPT | Fixed: help text now states query is seeded from the session's LIVE page (current/absent URL) and does not read the stored capture cache. |
| 4 — false-positive `DOM_FIRST_IMG ignores :expr` warning (Low) | ACCEPT | Fixed: `sql_uses_dom_first_img_expr` now scans each DOM_*_IMG call's own balanced argument span, so `:expr` in the FROM clause (or another function) no longer triggers the warning while a real IMG-argument `:expr` still does. New mixed/multiple-call unit tests added. |
| 5 — inspect coverage fractions over analyzed subset (Low) | DEFER | Per review (AI review unavailable — defaulted to DEFER). |
| 6 — DOM_FIRST_HREF empty for class-only selectors (Low) | ACCEPT | Docs: the caveat is promoted into SKILL.md's Common-mistakes table (symptom: `DOM_FIRST_HREF(DOM, '.class')` returns `''` with exit 0; use `a.class` or `DOM_FIRST_ATTR(DOM, sel, 'href')`). Engine fix is external (pulsar-ql). |
| 7 — MAKE_ARRAY undocumented (Low) | ACCEPT | Fixed (docs): MAKE_ARRAY added to the x-sql.md ARRAY namespace table and to x-sql-array-functions.md Quick Index with a dedicated section (signature, null handling, fallback-chain role). |