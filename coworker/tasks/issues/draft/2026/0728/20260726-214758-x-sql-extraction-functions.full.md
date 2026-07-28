Now I have a complete picture. Let me compile the final deliverables.

---

## A. Task Result

Successfully extracted all 6 products from the Electronics category page, sorted by price ascending:

| # | Title | Price | Rating | Badge | Width |
|---|-------|-------|--------|-------|-------|
| 1 | Wireless Mouse | $24.99 | 4.1 (156) | No Badge | 193px |
| 2 | USB-C Hub 7-in-1 | $29.95 | 4.2 (77) | No Badge | 193px |
| 3 | Portable Bluetooth Speaker | $49.99 | 4.3 (901) | No Badge | 193px |
| 4 | Wireless Noise-Cancelling Headphones | $199.99 | 4.4 (312) | Bestseller | 193px |
| 5 | Smartphone 128GB | $599.00 | 4.5 (210) | Hot | 193px |
| 6 | 4K OLED TV 55 | $899.99 | 4.6 (521) | Bestseller | 193px |

**Final X-SQL query** (`/tmp/final-v2.sql`):
```sql
SELECT
    STR_TRIM(DOM_FIRST_TEXT(DOM, 'a[href*="/ec/dp/"]')) AS title,
    DOM_FIRST_FLOAT(DOM, '[id*="product-price"]', 0.0) AS price,
    DOM_FIRST_HREF(DOM, 'a[href*="/ec/dp/"]') AS link,
    DOM_FIRST_IMG(DOM, 'img[src*="picsum"]') AS image,
    DOM_FIRST_ATTR(DOM, '[data-category-id]', 'data-category-id') AS category_id,
    STR_UPPER_CASE(STR_TRIM(DOM_FIRST_TEXT(DOM, 'a[href*="/ec/dp/"]'))) AS title_upper,
    STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(DOM, 'span.badge'), 'No Badge') AS badge,
    STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '[id*="product-price"]'), 0.0) AS price_numeric,
    STR_ABBREVIATE(STR_TRIM(DOM_FIRST_TEXT(DOM, 'a[href*="/ec/dp/"]')), 25) AS title_short,
    ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, '[data-rating]'),
        DOM_FIRST_ATTR(DOM, '[data-rating]', 'data-rating'),
        'No Rating'
    )) AS rating,
    DOM_WIDTH(DOM) AS card_width_px
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div:expr(width > 100 && height > 50)', 1, 20)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, 'a[href*="/ec/dp/"]'))
ORDER BY price ASC
LIMIT 6
```

---

## B. Execution Trace

**Commands used (in order):**
1. `./b4w.ps1 help` — Read all available commands
2. `./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"` — Navigate to e-commerce page
3. `./b4w.ps1 htmlsnapshot` — Capture HTML snapshot
4. `./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3` — Auto-discover CSS selectors (found 0 matches due to class quoting)
5. `./b4w.ps1 htmlsnapshot inspect '[class*="product-card"]' --max 3 --depth 3` — Discover selectors with attribute-based query (worked, 6 matches)
6. `./b4w.ps1 htmlsnapshot export --file /tmp/ec-electronics.html` — Export HTML to inspect raw structure
7. Multiple debug X-SQL queries to isolate issues (9+ iterations)
8. `./b4w.ps1 htmlsnapshot query --sql @/tmp/final-v2.sql` — Final successful query

**Major decisions:**
- Used attribute selectors (`[id*="product-price"]`, `[class*="product-card"]`) instead of class selectors because the page uses literal double-quote characters in CSS class names
- Used `#product-list > div` as the FROM selector after discovering that `[class*="product-card"]` causes 417 in `DOM_LOAD_AND_SELECT` but works in `htmlsnapshot inspect`/`get all`
- Switched from `DOM_ABS_HREF`/`DOM_ABS_SRC` to `DOM_FIRST_HREF`/`DOM_FIRST_IMG` after discovering the ABS variants crash with CSS selector arguments

**Workarounds required:**
1. **1-based offset:** Had to discover through experimentation that `DOM_LOAD_AND_SELECT` offset is 1-based (offset 0 → 417 error)
2. **Attribute selector workaround:** `[class*="product-card"]` doesn't work in X-SQL's `DOM_LOAD_AND_SELECT` but works in `htmlsnapshot inspect`/`get all` — had to use `#product-list > div` instead
3. **Function argument mismatch:** `DOM_ABS_HREF(DOM, selector)` and `DOM_ABS_SRC(DOM, selector)` cause 417 — had to use `DOM_FIRST_HREF`/`DOM_FIRST_IMG` instead

---

## C. Issues Found

### Issue 1: htmlsnapshot grep crashes on Unicode byte boundary

**Severity:** High

**Category:** Reliability

**Reproduction:**
```bash
./b4w.ps1 htmlsnapshot grep --selector "body" "product" --all
```

**Expected:** Matching lines displayed with grep-style output.

**Actual:** Rust panic: `byte index 2412 is not a char boundary; it is inside '⭐' (bytes 2410..2413)` — the CLI crashes.

**Root Cause:** The grep implementation slices the HTML string at byte offsets without verifying UTF-8 character boundaries. The star emoji (⭐, 3 bytes in UTF-8) causes a slice to land mid-character.

**Code Pointer:** `cli/browser4-cli/src/main.rs:6545` — `htmlsnapshot grep` string slicing

**AI Suggested Improvement:**
- Use `char_indices()` or `.chars()` for safe Unicode-aware slicing instead of raw byte indexing
- Add a unit test with multi-byte UTF-8 characters (emojis, CJK) in HTML content
- Fall back to replacement character (�) instead of panicking when slicing mid-char

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: DOM_LOAD_AND_SELECT uses 1-based offset with undocumented behavior

**Severity:** Medium

**Category:** Documentation

**Reproduction:**
```sql
SELECT DOM_TAG_NAME(DOM) AS tag FROM DOM_LOAD_AND_SELECT(@url, 'body', 0, 1)
```
Returns status 417 ("Expectation Failed"). Changing offset to `1` works.

**Expected:** Either 0 should be a valid offset (0-based indexing), or the documentation should explicitly state that offset is 1-based.

**Actual:** Offset 0 causes a 417 error with no explanation. Only offset ≥ 1 works.

**Root Cause:** The `DOM_LOAD_AND_SELECT` function uses 1-based indexing for the offset parameter, but the X-SQL documentation and examples don't explicitly state this. The reference example uses `1, 20` but doesn't clarify why `1` or what valid values are.

**Code Pointer:** `browser4-rest/src/main/kotlin/` — X-SQL/DomLoadAndSelect implementation

**AI Suggested Improvement:**
- Document in `x-sql-dom-load-select.md` that offset is 1-based: "offset: 1-based index of the first element to return (1 = first match)"
- Alternatively, accept 0 as a valid offset and treat it as 1 (more user-friendly)
- Add input validation that returns a clear error message like "offset must be >= 1, got 0" instead of opaque 417

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: 417 "Expectation Failed" error is opaque and unactionable

**Severity:** High

**Category:** UX

**Reproduction:** Multiple triggers: offset=0 in `DOM_LOAD_AND_SELECT`, passing CSS selector to `DOM_ABS_HREF`, using `[class*="product-card"]` as FROM selector in X-SQL (but works in `htmlsnapshot get all`).

**Expected:** A specific error message explaining what went wrong (e.g., "CSS selector syntax error at position X", "offset must be >= 1", "DOM_ABS_HREF does not accept a second argument").

**Actual:** Every failure returns the same `statusCode: 417, status: "Expectation Failed"` with an empty `resultSet`, making debugging a trial-and-error guessing game.

**Root Cause:** The backend uses HTTP 417 as a catch-all for X-SQL query failures without propagating the root cause (SQL exception message, argument validation error, CSS parse error, etc.) to the CLI/user.

**Code Pointer:** `browser4-rest/src/main/kotlin/` — X-SQL query handler / error response builder

**AI Suggested Improvement:**
- Include a `detail` or `error` field in the JSON response with the actual error message (SQL exception, parse error, etc.)
- Use distinct HTTP status codes for different failure modes (400 for invalid args, 422 for CSS parse error, 500 for internal errors)
- Add validation at the argument level: reject unexpected arguments with a clear message before the query runs

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: DOM_ABS_HREF and DOM_ABS_SRC crash when given CSS selector argument

**Severity:** High

**Category:** Reliability

**Reproduction:**
```sql
SELECT DOM_ABS_HREF(DOM, 'a[href*="/ec/dp/"]') AS abs_link
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div', 1, 6)
```

**Expected:** Either works correctly (resolving relative to absolute with a CSS-selected child), or returns a clear error: "DOM_ABS_HREF does not accept a CSS selector argument".

**Actual:** Returns 417 "Expectation Failed" with empty result set.

**Root Cause:** `DOM_ABS_HREF` and `DOM_ABS_SRC` accept only the DOM node argument (no CSS selector), unlike `DOM_FIRST_HREF` which accepts `(DOM, selector)`. When an unexpected second argument is passed, the function throws an exception that gets caught and turned into a generic 417.

**Code Pointer:** `browser4-rest/` — X-SQL function binding for DOM_ABS_HREF/DOM_ABS_SRC

**AI Suggested Improvement:**
- Add argument count validation before invoking each UDF — reject with clear error message
- Or make `DOM_ABS_HREF`/`DOM_ABS_SRC` accept an optional CSS selector argument (like `DOM_FIRST_HREF` does), making them consistent with the `*_FIRST_*` variants
- Document all function signatures clearly: list which accept `(DOM)`, `(DOM, selector)`, `(DOM, selector, default)`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: CSS selector handling differs between htmlsnapshot commands and DOM_LOAD_AND_SELECT

**Severity:** High

**Category:** Reliability

**Reproduction:**
```bash
# This works (returns 6 results):
./b4w.ps1 htmlsnapshot get all text '[class*="product-card"]'

# This also works (returns 6 results):
./b4w.ps1 htmlsnapshot inspect '[class*="product-card"]' --max 3 --depth 3

# This returns 417:
./b4w.ps1 htmlsnapshot query --sql @/tmp/debug3.sql
# with: FROM DOM_LOAD_AND_SELECT(@url, '[class*="product-card"]', 1, 10)
```

**Expected:** Consistent CSS selector handling across all `htmlsnapshot` subcommands. If `[class*="product-card"]` matches 6 elements in `get all` and `inspect`, it should also match 6 elements in `DOM_LOAD_AND_SELECT`.

**Actual:** `DOM_LOAD_AND_SELECT` rejects the selector with 417 while `get all` and `inspect` handle it correctly.

**Root Cause:** The `htmlsnapshot get`/`inspect` commands use the stored HTML snapshot (parsed once by the browser), while `DOM_LOAD_AND_SELECT` loads the page independently through a different code path (possibly Jsoup or a different HTML parser). When class attribute values contain HTML entities (`&quot;` → literal `"`), the two parsers may produce different DOM representations, leading to CSS selector mismatches.

**Code Pointer:** `DOM_LOAD_AND_SELECT` implementation — page loading and CSS selection code path vs `htmlsnapshot inspect`/`get` snapshot-based code path

**AI Suggested Improvement:**
- Investigate why the two code paths parse `&quot;` in class attributes differently
- Consider unifying the HTML parsing to use the same parser for both snapshot and X-SQL paths
- Add a diagnostic command to show how the backend sees CSS class names: `htmlsnapshot get all attr "[class]" class` or similar debugging capability
- As a workaround for users, document the behavior difference and recommend attribute selectors for pages with unusual class names

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Function signature documentation is incomplete

**Severity:** Medium

**Category:** Documentation

**Reproduction:** Look at the X-SQL function index table in `x-sql.md`. For `DOM_FIRST_HREF`, the table shows `Returns: String, Category: CSS select`. For `DOM_ABS_HREF`, it shows `Returns: String, Category: Link/Image`. There is no indication of which functions accept a CSS selector argument vs which only accept the DOM node.

**Expected:** The function reference should include argument signatures (count, types, optional/default values) for each function, or at minimum a column indicating which functions are selector-based.

**Actual:** The function index groups by category ("CSS select", "Link/Image", "Element property") but doesn't show exact signatures. Users must guess or experiment to determine argument counts.

**Root Cause:** The documentation index prioritizes brevity over completeness. Function signatures are discussed in separate detailed files (`x-sql-dom-functions.md`, `x-sql-dom-select-functions.md`) but the quick-reference index doesn't include argument information.

**AI Suggested Improvement:**
- Add an "Arguments" column to the function index table showing e.g. `(ValueDom)`, `(ValueDom, String)`, `(ValueDom, String, Double)`
- Add a visual indicator (e.g., ★) for selector-based functions vs node-property functions
- Include a note at the top: "Selector-based functions (marked ★) accept `(DOM, cssSelector [, default])`. Node-property functions accept `(DOM)` only."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: htmlsnapshot inspect auto-discovery fails with unusual CSS class names

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```bash
./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3
```

Output: `Auto-discovered selector ".\"product-card\"" from ":root" also had no matches.`

But running `htmlsnapshot inspect '[class*="product-card"]' --max 3 --depth 3` successfully finds 6 matches.

**Expected:** Auto-discovery should handle CSS class names with embedded special characters (like literal double-quotes from `&quot;` decoding) and find the repeating product-card pattern.

**Actual:** Auto-discovery generates the selector `.\"product-card\"` which has 0 matches, while the attribute-based equivalent `[class*="product-card"]` correctly finds all 6.

**Root Cause:** The auto-discovery algorithm extracts the class attribute value (which contains literal `"` characters due to `&quot;` decoding) and generates a CSS selector by escaping the quotes with backslashes. However, the escaping may produce a selector that the backend CSS engine doesn't match correctly against the DOM — the CSS engine may have parsed the class differently than the inspect code assumes.

**Code Pointer:** `browser4-rest/` — htmlsnapshot inspect auto-discovery CSS selector generation logic

**AI Suggested Improvement:**
- When auto-discovery generates a selector that returns 0 matches, fall back to attribute-selector equivalents (e.g., `[class~="value"]` or `[class*="value"]`)
- Add sanitization for class/ID values containing special characters — strip or normalize quotes before generating CSS selectors
- Include the attribute-selector form in the "Suggested selectors" output as a reliability hint

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: No table/rich output format for X-SQL query results

**Severity:** Medium

**Category:** UX

**Reproduction:** Run any `htmlsnapshot query` command. Output is JSON only.

**Expected:** A formatted table view for quick human review (at least optionally, e.g., `--format table`).

**Actual:** Only JSON output is available. Reading multi-field results requires manual JSON parsing or switching to `--json` for clean machine output. The default output mixes JSON with tips/warnings on stderr.

**Root Cause:** The `htmlsnapshot query` command only produces JSON output. There's no terminal table renderer in the CLI for query results.

**AI Suggested Improvement:**
- Add `--format table` option to render results as an ASCII/Unicode table in the terminal
- Consider making table format the default for results with ≤ 10 columns, falling back to JSON for wider results
- Add `--format csv` for spreadsheet import

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: X-SQL reference documentation is fragmented across 5 files

**Severity:** Low

**Category:** Discoverability

**Reproduction:** Try to find the complete list of available DOM functions as a new user.

**Expected:** A single, searchable function reference with all DOM/STR/ARRAY functions in one place.

**Actual:** Functions are split across:
- `x-sql.md` — index table (function names only)
- `x-sql-dom-load-select.md` — DOM_LOAD_AND_SELECT
- `x-sql-dom-functions.md` — ~65 DOM functions
- `x-sql-dom-select-functions.md` — ~50 selector-based DOM functions
- `x-sql-string-functions.md` — ~90 STR functions
- `x-sql-array-functions.md` — 3 ARRAY functions

Users must read up to 6 files to understand the full function surface. The index in `x-sql.md` is good but doesn't distinguish which functions accept selectors.

**AI Suggested Improvement:**
- Add a one-page "X-SQL Quick Reference" cheat sheet with all functions, signatures, and 1-line descriptions
- In the SKILL.md Decision Tree, link directly to the cheat sheet for fast lookup
- Consider auto-generating the index table from metadata annotations in the Kotlin source

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

**Task completion status:** ✅ Fully completed. All requested X-SQL features were successfully used (DOM functions, STR functions, ARRAY functions, PowerCSS `:expr()`, WHERE/ORDER BY/LIMIT clauses). All 6 products extracted with 11 fields each.

**Estimated task success rate:** 70% — The task was ultimately completed but required significant debugging. A new user without the patience to isolate 5+ failure modes would likely give up.

**Number of issues found:** 9 (3 High, 5 Medium, 1 Low)

**Major blockers:**
1. **Opaque 417 errors** (Issue 3) — the single biggest UX problem; every failure looks identical, requiring blind guessing to debug
2. **CSS selector incompatibility** (Issue 5) — `DOM_LOAD_AND_SELECT` and `htmlsnapshot get`/`inspect` handle the same CSS differently, breaking the documented workflow of "inspect first, then query"
3. **Argument mismatch crashes** (Issue 4) — passing a CSS selector to `DOM_ABS_HREF` silently fails instead of giving a clear error

**Most confusing aspects:**
- The `@url` placeholder vs explicit URL behavior in `htmlsnapshot query`
- Why `[class*="product-card"]` works in `get all` and `inspect` but not in `DOM_LOAD_AND_SELECT`
- The 1-based offset in `DOM_LOAD_AND_SELECT` (discovered through trial and error)
- Which DOM functions accept CSS selectors vs which only work on the node itself

**Most valuable improvements:**
1. Granular, actionable error messages in X-SQL query responses (would have saved ~70% of debugging time)
2. Unifying the HTML/CSS parsing between snapshot commands and X-SQL's `DOM_LOAD_AND_SELECT`
3. Adding `--format table` for human-readable query results

**Overall usability rating: 5/10**

The core extraction engine is powerful — when it works, it extracts exactly what you want. But the discoverability and debugging experience are poor. A new user following the documented workflow (inspect → query) will hit multiple opaque failures and have no way to diagnose them without deep experimentation. The documentation is comprehensive but fragmented, and the lack of function signature clarity forces trial-and-error. With targeted improvements to error messages and CSS selector consistency, this could easily be an 8/10 experience.
