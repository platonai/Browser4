# Issues: x-sql-extraction-functions

> **Source:** `20260902-212331-x-sql-extraction-functions.full.md` | **Date:** 20260902-212331 | **Mode:** dev

## Scenario Background

### Task

**Successful.** The complete X-SQL extraction pipeline was executed against the MockSite Electronics category (`http://localhost:18080/ec/b?node=1292115012`, 6 products). All 9 scenario steps were completed:

- **Navigation & capture:** `goto` opened the page ("Category: Electronics"), `htmlsnapshot` captured the live DOM. Notable: the browser-served DOM (`div.product-card`, `div.product-title`, `div.product-price`) differs from the raw HTML curl receives (`article.product-card`, `h2.product-title`, `span.product-price`) — the MockSite serves client-type-specific markup.
- **Selector discovery:** `htmlsnapshot inspect --max 3 --depth 3` cleanly discovered recurring patterns with coverage percentages (`div.product-title`, `a.product-link`, `img.product-img`, `.product-price`, `.product-rating`, `[data-category-id=…]`, `.product-badges`).
- **Final query:** extracts per-product `title` (STR_TRIM'd via a 3-selector `ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(…))` fallback chain), `price` (`DOM_FIRST_FLOAT`, parsed to numbers), absolute `detail_link` (`DOM_FIRST_HREF`), `image_url` (`DOM_FIRST_IMG`), `image_visible` (`DOM_FIRST_ATTR` + PowerCSS `img:expr(width > 100 && height > 100)`), `rating` (`DOM_FIRST_ATTR` → `data-rating`), `badge` (uppercased via `STR_UPPER_CASE`, fallback chain + `STR_DEFAULT_IF_BLANK` → "NO BADGE" for badge-less products), `rating_from_text` (`STR_FIRST_FLOAT` on "4.4 (312)"), `title_short` (`STR_ABBREVIATE`), with PowerCSS in the FROM selector, `WHERE CAST(DOM_FIRST_FLOAT(...) AS DOUBLE) >= 25.0 AND DOM_WIDTH(DOM) > 100`, `ORDER BY … DESC`, `LIMIT 3`.
- **Verified result (3 rows):** `4K OLED TV 55 | 899.99 | http://localhost:18080/ec/dp/B0E000001 | …/seed/1250857624/200/140 | 4.6 | BESTSELLER`; `Smartphone 128GB | 599.0 | …/dp/B0E000004 | … | 4.5 | HOT`; `Wireless Noise-Cancelling Headphones | 199.99 | …/dp/B0E000002 | … | 4.4 | BESTSELLER`. A second run (no price filter, `ORDER BY … ASC`) returned all 6 products and demonstrated the fallback chain correctly producing `NO BADGE` for the three cards without badges.

**Workarounds required:** (1) `DOM_FIRST_FLOAT` cannot be compared to a numeric literal in `WHERE` — it crashes with an opaque H2 hex-conversion error; wrapping in `CAST(... AS DOUBLE)` (or `STR_FIRST_FLOAT(DOM_FIRST_TEXT(...), 0.0)`) fixes it. (2) `DOM_FIRST_IMG` silently ignores `:expr()` PowerCSS selectors (returns no match); using `DOM_FIRST_ATTR(DOM, 'img:expr(...)', 'src')` or `DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img:expr(...)'))` works. Both are reported as issues below.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — full command map read; confirmed dev wrapper works.
2. Read `skills/browser4-cli/SKILL.md` + `references/{x-sql,x-sql-array-functions,x-sql-string-functions,htmlsnapshot}.md` completely before first browser action.
3. `./bin/test.ps1 mock-site` — **failed under Windows PowerShell 5.1** (ParserError on UTF-8 script); succeeded via `pwsh -File ./bin/test.ps1 mock-site`. MockSite reachable at :18080 after ~1 min Maven/Spring boot.
4. `./b4w.ps1 status` — daemon/backend already healthy (4.13.13-SNAPSHOT at :18182).
5. `./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"` — navigated, page title "Category: Electronics".
6. `./b4w.ps1 htmlsnapshot` — captured live DOM (metadata: 6 product cards, 9 links, 8 inputs, sizes/bounding boxes).
7. `./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3` — discovered selectors + coverage (`div.product-title` 3/3, `a.product-link` 3/3, `img.product-img` 3/3, `[data-category-id="1292115012"]` 3/3, `.product-badges` 2/3, `span.badge` 2/3 → the badge variation that motivated fallback chains).
8. Cross-checked against `curl` raw HTML and `htmlsnapshot export` (saved to `.test-sessions/`) — identified the browser-vs-plain-HTML DOM divergence and per-card structure (cards 5–6 lack `.product-badges`).
9. Query files written under `.test-sessions/` (sanity.sql → probes a–j → final.sql/final-2.sql/final-3.sql), executed with `./b4w.ps1 htmlsnapshot query "<url>" --sql "@.test-sessions/<file>.sql"` (file form avoids the documented shell-quoting pitfalls — this worked first try).
10. Debugging iterations: probe a (`WHERE DOM_FIRST_FLOAT(...) >= 25.0` → 417 hex error) → probe b/c (3-arg overload still fails; `CAST AS DOUBLE` works) → probe d/e (raw ORDER BY works; int literal also fails) → probe f–i (`:expr` fails inside `DOM_FIRST_IMG` for `> 0` and `>= 0`, works in `DOM_FIRST_ATTR`, `DOM_SELECT_FIRST`, FROM selector, and `htmlsnapshot get attr` on the stored snapshot) → probe j (`STR_FIRST_FLOAT(...)` in WHERE/ORDER BY works).
11. Final runs: `final-2.sql` (`--format table` → 3 clean rows, "3 rows returned."), `final-3.sql` (6 rows, ASC, badge fallback verified).

**Key decisions:** Followed the docs' own guidance to write SQL to files and use `--sql @file`; used `inspect` results rather than assumptions; adopted `CAST`/`STR_FIRST_FLOAT` wrappers after the WHERE failure; chose `DOM_FIRST_ATTR`+`:expr` composition for PowerCSS image filtering once `DOM_FIRST_IMG`+`:expr` proved broken; kept all scratch files under `.test-sessions/`.



**D. Overall assessment summary (JSON above, mirrored here):** task completed successfully with an estimated 95% success rate; 4 issues found (2 High product defects with silent/opaque failure modes, 1 Medium discoverability gap, 1 Low environment/documentation issue). No major blockers — both High issues have undocumented workarounds I verified. The documentation is unusually thorough (decision trees, quick-start SQL template, shell-quoting warnings that proved accurate), but the two X-SQL engine asymmetries undermine the documented semantics exactly where a first-time user would write a filter (`WHERE price >= X`) or a PowerCSS image selector — and each fails in a way that gives no clue about the cause (cryptic H2 hex error; silent empty match). Overall usability rating: **6/10**.

---

## Issues Found (4 issues)

### Issue 1: DOM_FIRST_FLOAT cannot be compared to a numeric literal in WHERE — opaque 417 'Hexadecimal string' error

**Severity:** High
**Category:** Product

#### Reproduction

Write a query file: SELECT DOM_FIRST_TEXT(DOM, '.product-title') AS title FROM DOM_LOAD_AND_SELECT(@url, '.product-card') WHERE DOM_FIRST_FLOAT(DOM, '.product-price') >= 25.0  then run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "@.test-sessions/probe-a.sql". Also fails with integer literal (>= 25) and with the 3-arg overload DOM_FIRST_FLOAT(DOM, '.product-price', 0.0).

#### Expected Behavior

The natural SQL pattern WHERE DOM_FIRST_FLOAT(DOM, '.price') >= 25.0 should filter rows numerically (the same expression works in SELECT and in ORDER BY).

#### Actual Behavior

Request returns statusCode 417 'Expectation Failed' with the H2 error 'Hexadecimal string contains non-hex character: "899.99"' (SQL 90004-197), echoing the full SQL. Zero rows returned. Workarounds that DO work: WHERE CAST(DOM_FIRST_FLOAT(DOM, '.product-price') AS DOUBLE) >= 25.0, or WHERE STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '.product-price'), 0.0) >= 25.0. ORDER BY DOM_FIRST_FLOAT(...) without CAST sorts correctly.

#### Root Cause Analysis

The DOM_FIRST_FLOAT scalar UDF returns a custom ValueFloat type that is not registered as a numeric H2 type, so a WHERE comparison against a DOUBLE literal triggers a fallback type-conversion path that tries to hex-decode the value's string form ('899.99'). SELECT and ORDER BY use raw-value paths that avoid the conversion, which is why only WHERE comparisons crash. Likely in the external ai.platon.pulsar X-SQL UDF engine (function alias registration for DOM_FIRST_FLOAT / DomSelectFunctions); needs confirmation by inspecting the engine's H2 function type mapping. The error message gives no hint which expression failed or how to fix it.

#### Code Pointer

`X-SQL UDF engine lives in the external ai.platon.pulsar dependency (function source per docs: DomSelectFunctions.kt, DOM_FIRST_FLOAT alias; H2 function type registration). In-repo touchpoints: skills/browser4-cli/references/x-sql.md (docs to update), cli/browser4-cli/src/commands.rs (htmlsnapshot-query arg def ~line 3236, error surfacing).`

#### AI Suggested Improvement

- Register DOM_FIRST_FLOAT/DOM_FIRST_INTEGER result type as a numeric H2 type (or return a plain DOUBLE) so WHERE comparisons work without CAST
- Add a 'Common mistakes' entry in SKILL.md and x-sql.md documenting the CAST/STR_FIRST_FLOAT workaround and a WHERE numeric-filter example
- Detect this failure mode server-side and return a hint (e.g. 'DOM_FIRST_FLOAT comparisons require CAST(... AS DOUBLE)') instead of the raw H2 hex error

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid High: a natural WHERE pattern hard-fails with a misleading H2 hex error and zero rows. The type-registration root cause sits in the external pulsar X-SQL dependency, so the in-repo part should be the server-side hint + SKILL.md/x-sql.md CAST documentation, while the actual UDF fix is bundled into an upstream ticket (share one ticket with Issue 2 — same engine, same escape hatch) and lands with a dependency bump.

---

### Issue 2: DOM_FIRST_IMG silently ignores PowerCSS :expr() selectors while DOM_FIRST_ATTR/DOM_SELECT_FIRST honor them

**Severity:** High
**Category:** Product

#### Reproduction

SELECT STR_DEFAULT_IF_BLANK(DOM_FIRST_IMG(DOM, 'img.product-img:expr(width > 100 && height > 100)'), 'NO_IMAGE') AS img, DOM_FIRST_TEXT(DOM, '.product-title') AS title FROM DOM_LOAD_AND_SELECT(@url, '.product-card') LIMIT 2 — run via ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @file.sql. Try variants :expr(width > 0) and :expr(width >= 0); compare with DOM_FIRST_ATTR(DOM, 'img.product-img:expr(width > 100)', 'src') and DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img.product-img:expr(width > 100 && height > 100)')).

#### Expected Behavior

Per SKILL.md §PowerCSS ('usable in any CSS selector via :expr(...), in X-SQL DOM_* functions'), DOM_FIRST_IMG(DOM, 'img.x:expr(width > 100)') should return the src of a matching visible image — the images here measure 169x140 (vi attribute present), and the identical selector returns URLs through DOM_FIRST_ATTR, DOM_SELECT_FIRST, the FROM selector, and htmlsnapshot get attr.

#### Actual Behavior

DOM_FIRST_IMG with any :expr filter returns no match (empty string → the STR_DEFAULT_IF_BLANK fallback 'NO_IMAGE' appears), even for a tautological :expr(width >= 0). Plain DOM_FIRST_IMG(DOM, 'img.product-img') works. The failure is silent — no error, no warning — so an unwrapped query yields NULL/empty image columns without explanation. Only 2-3 of the 6 products' rows showed this in testing, but a single product with a filterable image would lose data unnoticed.

#### Root Cause Analysis

DOM_FIRST_IMG appears to evaluate its selector through a different code path than DOM_FIRST_ATTR/DOM_SELECT_FIRST (e.g., an img-scanning implementation whose selector matcher does not parse/evaluate the :expr() pseudo-selector, or evaluates features on an unmeasured DOM copy where all visual features are NaN, making every expression false). Needs confirmation by locating the DOM_FIRST_IMG alias implementation in the pulsar X-SQL engine and comparing its selector pipeline with DOM_FIRST_ATTR's.

#### Code Pointer

`X-SQL UDF engine (external ai.platon.pulsar dependency): DOM_FIRST_IMG alias implementation vs DOM_FIRST_ATTR/DOM_SELECT_FIRST selector handling. Docs claiming uniform :expr support: skills/browser4-cli/references/power-dom.md and SKILL.md §PowerCSS. In-repo backend surface: browser4-rest X-SQL execution path.`

#### AI Suggested Improvement

- Make DOM_FIRST_IMG route its selector through the same PowerCSS-capable selector engine as DOM_FIRST_ATTR/DOM_SELECT_FIRST (or document and enforce that :expr is only honored in DOM_SELECT_*/DOM_FIRST_ATTR/FROM selectors)
- Add a unit test asserting :expr behavior parity across DOM_FIRST_IMG, DOM_FIRST_ATTR, DOM_SELECT_FIRST, and DOM_LOAD_AND_SELECT table selectors
- Since a selector that matches nothing is indistinguishable from an unsupported selector, log a warning when :expr appears in a selector evaluated by a non-PowerCSS path

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid High — and the most dangerous of the four because failure is silent (empty/NULL columns with no error), which is worse than Issue 1's hard 417. Root cause is again the external X-SQL engine, so group the upstream fix with Issue 1; in-repo, add the :expr warning, DOM_FIRST_IMG/ATTR/SELECT_FIRST parity tests, and correct the docs that overpromise uniform :expr support.

---

### Issue 3: htmlsnapshot query defaults to a raw JSON envelope with no hint of --format table / --result-only; skill docs never mention them

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Run ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @file.sql and observe stdout. Then search skills/browser4-cli/SKILL.md, references/htmlsnapshot.md and references/x-sql.md for 'format table' or 'result-only'.

#### Expected Behavior

Human-oriented default output (or a documented, discoverable table flag): SKILL.md §4e and htmlsnapshot.md's Query section show only raw envelope output and never mention --format table, so a first-time user should at least be pointed to it in output or examples.

#### Actual Behavior

Default stdout is the complete machine envelope (id, pageContentBytes, timestamps, status fields) as a single JSON line — no column headers, no row-count summary. --format table exists (defined in --help htmlsnapshot-query and produces a clean aligned table plus '3 rows returned.') but is absent from the SKILL.md copy-paste template, htmlsnapshot.md, and x-sql.md, so it is effectively undiscoverable. Error responses echo the entire SQL statement inside the envelope's message field, pushing real causes far off-screen.

#### Root Cause Analysis

Docs lag behind CLI capabilities: the query help text documents --format json|csv|table (default json, 'the raw scrape response envelope') and --result-only, but the skill reference pages were not updated to mention the human-readable modes; the CLI makes no effort to suggest --format table when stdout looks like a terminal.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (htmlsnapshot-query ArgDef, ~line 3236-3290: --format and --result-only definitions); skills/browser4-cli/SKILL.md §4e and skills/browser4-cli/references/htmlsnapshot.md 'Query — X-SQL against HTML snapshot'.`

#### AI Suggested Improvement

- Update SKILL.md §4e/§6 and htmlsnapshot.md query examples to default to --format table (or show both: table for humans, --json/--result-only for scripts)
- When the CLI detects the query succeeded and stdout is not piped, print 'N rows returned. Use --format csv|table for other views.' like the existing tip pattern
- Add a 'Query output formats' table to htmlsnapshot.md (json default, table, csv, --result-only)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid Medium discoverability gap, entirely in-repo and low-risk: document --format table/--result-only in SKILL.md §4e and htmlsnapshot.md and consider the piped-stdout tip. Note its complaint about the envelope echoing the full SQL overlaps the error-hint work already planned under Issue 1 — coordinate the two so both improvements live in the same error-formatting change.

---

### Issue 4: bin/test.ps1 mock-site fails under Windows PowerShell 5.1 with parser errors; docs assume pwsh without saying so

**Severity:** Low
**Category:** Documentation

#### Reproduction

powershell -File ./bin/test.ps1 mock-site on Windows with the default Windows PowerShell 5.1 (zh-CN console). Observe ParserError 'unexpected token <' / 'string missing terminator' at test.ps1 lines ~2181, 2302-2304. Then run pwsh -File ./bin/test.ps1 mock-site — succeeds.

#### Expected Behavior

The documented invocation ./bin/test.ps1 mock-site should start MockSite regardless of which PowerShell is first on PATH, or the docs/script should state 'requires PowerShell 7 (pwsh)'.

#### Actual Behavior

Windows PowerShell 5.1 fails to parse test.ps1 (ParserError, exit code 0 reported by the launcher but nothing starts). The script is UTF-8 without BOM with CJK text; PS 5.1 decodes it with the ANSI codepage (GBK), corrupting string literals and producing spurious parse errors at unrelated lines. The CLI wrapper b4w.sh routes through pwsh so CLI usage is unaffected; only direct test.ps1 invocations (e.g., the mock-site prerequisite in this scenario) hit the issue.

#### Root Cause Analysis

test.ps1 (and likely other bin/*.ps1 scripts) contains non-ASCII characters and is stored as UTF-8 without BOM; Windows PowerShell 5.1 reads script files using the ANSI code page, corrupting multi-byte sequences inside string literals and breaking parsing. pwsh (PowerShell 7+) defaults to UTF-8 and parses fine.

#### Code Pointer

`bin/test.ps1 (file encoding; lines ~2181/2302 contain CJK help text) — consider adding a #requires -Version 7 header or saving with BOM so PS 5.1 fails fast with a clear message instead of a parser error.`

#### AI Suggested Improvement

- Add '#requires -Version 7' to test.ps1 (and other bin scripts with non-ASCII content) so PS 5.1 users get an actionable message
- Or re-save the scripts as UTF-8 with BOM, which PS 5.1 handles correctly
- Note the pwsh requirement in the scenario/test-runner docs that reference ./bin/test.ps1

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid Low — the UTF-8-without-BOM + ANSI-codepage failure under Windows PowerShell 5.1 is well-established behavior, and the fix is trivial (#requires -Version 7 or BOM). Apply the sweep to all bin/*.ps1 files with non-ASCII content, not just test.ps1, and note the pwsh requirement wherever ./bin/test.ps1 is referenced in docs.

---

## Overall Assessment

**Completion Status:** Successful — all 9 scenario steps completed; a working multi-field X-SQL extraction query using DOM_*, STR_*, ARRAY_*, PowerCSS :expr, WHERE/ORDER BY/LIMIT was built and verified against all 6 products, with every required function exercised and results reviewed in table format.

**Success Rate:** 95%

**Issues Found:** 4

**Major Blockers:** No full blockers. Two functional traps cost most of the debugging time: (1) numeric WHERE filters on DOM_FIRST_FLOAT crash with an opaque 417 hex-conversion error until CAST(... AS DOUBLE) or STR_FIRST_FLOAT wrapping is applied; (2) DOM_FIRST_IMG silently drops :expr() PowerCSS filters (no match, no error), contradicting the documented 'usable in DOM_* functions' claim. Both have workarounds but are undocumented.

**Most Confusing Aspects:** For a first-time user: the raw-JSON default output of htmlsnapshot query (the clean --format table mode is never mentioned in the skill docs); DOM_FIRST_FLOAT behaving differently in WHERE (crash) vs SELECT/ORDER BY (works); the silent no-match behavior of :expr inside DOM_FIRST_IMG while the identical selector works in DOM_FIRST_ATTR; and the MockSite DOM differing between curl (article/h2/span) and the browser (div/div/div), which makes inspect's suggested div.product-title look wrong when cross-checked against raw HTML.

**Most Valuable Improvements:** Fix DOM_FIRST_FLOAT comparisons in WHERE (numeric type registration) and DOM_FIRST_IMG :expr support (selector-engine parity); document --format table / --result-only and numeric-filter workarounds (CAST/STR_FIRST_FLOAT) in SKILL.md + x-sql.md; surface actionable hints instead of raw H2 errors; add a #requires -Version 7 header to bin/test.ps1.

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

#### Issue 1: DOM_FIRST_FLOAT cannot be compared to a numeric literal in WHERE — opaque 417 'Hexadecimal string' error

Write a query file: SELECT DOM_FIRST_TEXT(DOM, '.product-title') AS title FROM DOM_LOAD_AND_SELECT(@url, '.product-card') WHERE DOM_FIRST_FLOAT(DOM, '.product-price') >= 25.0  then run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "@.test-sessions/probe-a.sql". Also fails with integer literal (>= 25) and with the 3-arg overload DOM_FIRST_FLOAT(DOM, '.product-price', 0.0).

#### Issue 2: DOM_FIRST_IMG silently ignores PowerCSS :expr() selectors while DOM_FIRST_ATTR/DOM_SELECT_FIRST honor them

SELECT STR_DEFAULT_IF_BLANK(DOM_FIRST_IMG(DOM, 'img.product-img:expr(width > 100 && height > 100)'), 'NO_IMAGE') AS img, DOM_FIRST_TEXT(DOM, '.product-title') AS title FROM DOM_LOAD_AND_SELECT(@url, '.product-card') LIMIT 2 — run via ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @file.sql. Try variants :expr(width > 0) and :expr(width >= 0); compare with DOM_FIRST_ATTR(DOM, 'img.product-img:expr(width > 100)', 'src') and DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img.product-img:expr(width > 100 && height > 100)')).

#### Issue 3: htmlsnapshot query defaults to a raw JSON envelope with no hint of --format table / --result-only; skill docs never mention them

Run ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @file.sql and observe stdout. Then search skills/browser4-cli/SKILL.md, references/htmlsnapshot.md and references/x-sql.md for 'format table' or 'result-only'.

#### Issue 4: bin/test.ps1 mock-site fails under Windows PowerShell 5.1 with parser errors; docs assume pwsh without saying so

powershell -File ./bin/test.ps1 mock-site on Windows with the default Windows PowerShell 5.1 (zh-CN console). Observe ParserError 'unexpected token <' / 'string missing terminator' at test.ps1 lines ~2181, 2302-2304. Then run pwsh -File ./bin/test.ps1 mock-site — succeeds.

