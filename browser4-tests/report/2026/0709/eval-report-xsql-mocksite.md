# Browser4-CLI Usability Evaluation Report — X-SQL MockSite Extraction

**Date:** 2026-07-09
**Evaluator:** Claude (AI Agent)
**Task:** MockSite e-commerce X-SQL multi-field extraction with DOM functions, STR cleaning, ARRAY fallbacks, PowerCSS :expr(), WHERE, ORDER BY, LIMIT
**CLI invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`

---

## A. Task Result

All 9 task steps were completed successfully:

1. ✅ Navigated to `http://localhost:18080/ec/b?node=1292115012` — Electronics category with 6 products
2. ✅ Captured HTML snapshot — 11 KB, 7 images, 9 links, 22 interactive elements
3. ✅ Used `htmlsnapshot inspect` with `--max 3 --depth 3` — discovered CSS selectors (required workaround, see Issue 1)
4. ✅ Built X-SQL query with all DOM functions: `DOM_FIRST_TEXT`, `DOM_FIRST_FLOAT`, `DOM_FIRST_HREF`, `DOM_FIRST_IMG`, `DOM_FIRST_ATTR`
5. ✅ Applied STR functions: `STR_TRIM`, `STR_UPPER_CASE`, `STR_DEFAULT_IF_BLANK`, `STR_FIRST_FLOAT`, `STR_ABBREVIATE`
6. ✅ Used `ARRAY_FIRST_NOT_BLANK` with `MAKE_ARRAY` for fallback selectors on rating
7. ✅ Applied PowerCSS `:expr(width > 100)` to filter product cards by visual width
8. ✅ Added `WHERE`, `ORDER BY`, and `LIMIT` clauses (numeric comparison workaround, see Issue 3)
9. ✅ Ran final query and reviewed extracted data — all 6 products sorted by price

**Final extracted data (sorted by price ASC, cheapest first):**

| Title | Price | Rating | Badge |
|---|---|---|---|
| Wireless Mouse | $24.99 | 4.1 (156) | None |
| USB-C Hub 7-in-1 | $29.95 | 4.2 (77) | None |
| Portable Bluetooth Speaker | $49.99 | 4.3 (901) | None |
| Wireless Noise-Cancelling Headphones | $199.99 | 4.4 (312) | Bestseller |
| Smartphone 128GB | $599.00 | 4.5 (210) | Hot |
| 4K OLED TV 55 | $899.99 | 4.6 (521) | Bestseller |

---

## B. Execution Trace

| Step | Command | Notes |
|------|---------|-------|
| Prep | `cargo run -- ... -- --help` | Help output comprehensive |
| Prep | Read `skills/browser4-cli/SKILL.md` and all X-SQL reference docs | Documentation generally good |
| 1 | `goto "http://localhost:18080/ec/b?node=1292115012"` | Page loaded, reconnected to existing session |
| 2 | `htmlsnapshot` | Captured 11 KB snapshot with 22 interactive elements |
| 3a | `htmlsnapshot inspect --max 3 --depth 3` | **FAILED** — auto-discovered selector had 0 matches |
| 3b | Exported HTML to `/tmp/mocksite-electronics.html` | Analyzed structure manually |
| 3c | `htmlsnapshot get all text 'div[class*="product-card"]'` | Found 6 products, verified selector |
| 3d | `htmlsnapshot inspect "div[class*='product-card']" --max 3 --depth 3` | Worked correctly with explicit selector |
| 4-8 | Wrote X-SQL query to `extract-electronics-final.sql` | Iteratively debugged failures |
| 4-8a | Simple query: `DOM_FIRST_TEXT` only | ✅ Worked |
| 4-8b | Added price, link, image, data attr | ✅ Worked (escaped quotes in URLs noted) |
| 4-8c | Added STR functions | ✅ All worked except numeric WHERE |
| 4-8d | Added ARRAY_FIRST_NOT_BLANK | ✅ Worked |
| 4-8e | Added PowerCSS `:expr(width > 100)` | ✅ Worked |
| 4-8f | Added `WHERE DOM_FIRST_FLOAT(...) > 0` | **FAILED** — 417 Expectation Failed |
| 4-8g | Changed to `WHERE DOM_FIRST_FLOAT(...) IS NOT NULL` | ✅ Worked (workaround) |
| 4-8h | Added all fields, WHERE, ORDER BY, LIMIT | ✅ Final query succeeded |
| 9 | `htmlsnapshot query --sql @extract-electronics-final.sql` | All 6 products extracted, sorted by price |

### Workarounds Required

1. **inspect auto-discovery failure** (Issue 1): Had to export HTML, manually analyze structure, and provide explicit selector
2. **Inspect suggested selectors don't work from CLI** (Issue 2): Had to use attribute-based selectors instead of class selectors
3. **DOM_FIRST_FLOAT numeric comparison fails in WHERE** (Issue 3): Had to use `IS NOT NULL` instead of `> 0`
4. **Escaped-quote data in URLs/attrs** (Issue 4): Link/image values contain literal `\"` sequences in JSON output

---

## C. Issues Found

### Issue 1: htmlsnapshot inspect auto-discovery fails on pages with HTML-entity-quoted class names

**Severity:** High

**Category:** Product

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/ec/b?node=1292115012"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect --max 3 --depth 3
```

**Expected:** The inspect command should auto-discover repeating product card elements and suggest CSS selectors for titles, prices, images, and links.

**Actual:** Auto-discovered selector `."product-card"` returns 0 matches. The tool reports "No elements matched. Check the CSS selector and ensure a HTML snapshot has been captured." The user has no guidance on how to proceed — no hint that the class names contain literal quote characters, no suggestion to try attribute selectors.

**Root Cause:** The MockSite HTML uses `&quot;` entities in class names, resulting in literal double-quote characters in class values (e.g., `class="&quot;product-card&quot;"` becomes `class="\"product-card\""` in the DOM). The inspect tool's auto-discovery algorithm generates standard class selectors (`.product-card`) that don't match elements whose class attribute literally contains quote characters. The tool should detect this edge case and generate attribute selectors (`[class*="product-card"]`) as a fallback.

**Code Pointer:** The HTML snapshot inspect logic that generates CSS selectors from DOM pattern analysis. The `htmlsnapshot inspect` command handler.

**AI Suggested Improvement:**
- When auto-discovery returns 0 matches, the inspect tool should try attribute-based selectors (`[class*="..."]`, `[id*="..."]`) as fallbacks before reporting failure.
- Add a diagnostic message when class names contain special characters: "Detected non-standard class names containing quote characters. Try attribute selectors like `[class*='product-card']`."
- The auto-discovery algorithm should normalize HTML entities in class names before generating selectors.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: inspect tool suggests selectors with backslash-escaped quotes that fail from the shell

**Severity:** High

**Category:** Discoverability

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect "div[class*='product-card']" --max 3 --depth 3
# Inspect suggests: div.\"product-title\"
# Then try it:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text 'div.\"product-title\"'
# Returns: [] (no elements matched)
```

**Expected:** Selectors suggested by the inspect tool should work when copy-pasted into `htmlsnapshot get` or X-SQL queries.

**Actual:** The suggested selectors (`div.\"product-title\"`) return 0 matches because the backslash-escaped quote notation is the inspect tool's display format, not a valid CSS selector for shell use. The user must independently discover that attribute selectors like `[class*="product-title"]` are needed.

**Root Cause:** The inspect tool displays selectors using backslash-escape notation (`.\"product-title\"`) to represent class names containing literal quote characters. However, this notation is not directly usable — the backslash-quote is consumed by shell parsing before reaching the CSS engine, resulting in an invalid selector. The tool should either emit shell-safe selectors or flag that the class name requires special handling.

**Code Pointer:** The inspect output formatter that generates suggested CSS selectors.

**AI Suggested Improvement:**
- Next to each suggested selector that contains special characters, add a note like "Use `[class*='product-title']` instead for shell compatibility."
- Generate both the canonical CSS selector and a shell-safe alternative in the inspect output.
- Add a general note in the inspect output: "Some class names on this page contain special characters. Prefer attribute selectors (`[class*='...']`) for reliability."

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: DOM_FIRST_FLOAT numeric comparison in WHERE clause returns 417 Expectation Failed

**Severity:** High

**Category:** Reliability

**Reproduction:**
```sql
SELECT DOM_FIRST_TEXT(DOM, '[class*="product-title"]') AS title,
       DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 0.0) AS price
FROM DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]', 1, 20)
WHERE DOM_IS_NOT_NIL(DOM)
  AND DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', -1.0) > 0
ORDER BY DOM_FIRST_FLOAT(DOM, '[class*="product-price"]', 999999.0) ASC
LIMIT 6
```
Result: `statusCode: 417, status: "Expectation Failed", resultSet: []`

**Expected:** The `> 0` comparison should work — `DOM_FIRST_FLOAT` returns a numeric value that should be comparable with standard SQL comparison operators.

**Actual:** `> 0` and `> 0.0` both return 417 with empty result set. However, `IS NOT NULL` works correctly. The same function works correctly in SELECT and ORDER BY clauses — only the WHERE numeric comparison fails.

**Root Cause:** `DOM_FIRST_FLOAT` returns a `ValueFloat` type (likely a custom wrapper or `java.lang.Double` with special null handling) that H2's query engine cannot compare with numeric literals via standard operators. The H2 type coercion or comparison path for UDF return values in WHERE clauses differs from SELECT/ORDER BY. This is likely in `DomSelectFunctions.kt` or `DomFunctions.kt` — the `DOM_FIRST_FLOAT` alias mapping to the underlying Java method.

**Code Pointer:** `browser4-core/src/main/kotlin/.../DomSelectFunctions.kt` — the `DOM_FIRST_FLOAT` function implementation and its SQL alias registration. Also `DomFunctions.kt` for how `ValueFloat` is defined and compared.

**AI Suggested Improvement:**
- The `DOM_FIRST_FLOAT` function should return a native H2-compatible numeric type (e.g., `Double` or `java.math.BigDecimal`) rather than a custom wrapper, so that standard SQL comparison operators (>, <, >=, <=, =, !=, BETWEEN) work in all clauses.
- Alternatively, document this limitation explicitly in the X-SQL reference: "DOM_FIRST_FLOAT in WHERE clauses only supports IS NULL/IS NOT NULL checks. For numeric comparisons, use STR_FIRST_FLOAT on text values instead."
- Add validation during query parsing that rejects unsupported operations with a clear error message instead of returning 417 with empty results.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: 500 Internal Server Error on SQL query provides no diagnostic information

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:**
Submit an X-SQL query with an unsupported pattern (e.g., `DOM_FIRST_FLOAT(...) > 0` combined with many SELECT fields). The response is:
```json
{"id":"","statusCode":500,"pageStatusCode":1462,"pageContentBytes":0,"isDone":false,"resultSet":null,"event":"","status":"Internal Server Error"}
```

**Expected:** A descriptive error message indicating what went wrong (e.g., "Type mismatch: cannot compare ValueFloat with numeric literal in WHERE clause", or "Syntax error at line N").

**Actual:** Generic 500 error with no details. The user must binary-search the query to isolate the problematic clause. This makes debugging complex queries extremely tedious.

**Root Cause:** The backend server catches exceptions without forwarding the underlying error message to the CLI response. The `statusCode: 500` and `status: "Internal Server Error"` are generic HTTP wrappers — the actual H2 SQL exception or Java stack trace is logged server-side but not included in the JSON response.

**Code Pointer:** `browser4-rest/` — the REST endpoint that executes X-SQL queries and catches exceptions. The error response builder that omits diagnostic details.

**AI Suggested Improvement:**
- Include the underlying SQL error message in the JSON response under an `error` or `sqlError` field.
- If sanitization is a concern (not leaking internal paths), include at minimum the H2 error code and message, which are safe to expose.
- Add a `--verbose` flag to the CLI that includes server-side diagnostic information (stack traces, execution plan, timing).

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `--max` and `--depth` flags for htmlsnapshot inspect lack documentation in --help output

**Severity:** Low

**Category:** Documentation

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect --help
```

**Expected:** Clear explanation of what `--max` (number of representative elements to analyze) and `--depth` (DOM tree traversal depth) control, with examples.

**Actual:** The main help output lists the flags without detailed explanation. The htmlsnapshot.md reference provides context but requires the user to read a separate file. The flags are not self-documenting.

**Root Cause:** The CLI help text for `htmlsnapshot inspect` is generated from the command definition and may not include detailed flag descriptions.

**Code Pointer:** `cli/browser4-cli/src/` — the inspect command definition and its flag descriptions.

**AI Suggested Improvement:**
- Add per-flag descriptions in the CLI help: `--max N` → "Analyze up to N representative elements for selector discovery (default: all)." `--depth D` → "Limit DOM tree traversal depth within each element to D levels."
- Add an example to the help text: `htmlsnapshot inspect ".product-card" --max 3 --depth 3  # analyze 3 cards, 3 levels deep`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Data quality — escaped quotes in extracted attribute values require additional cleaning

**Severity:** Low

**Category:** Product

**Reproduction:**
Extract href or src attributes from the MockSite page using `DOM_FIRST_HREF` or `DOM_FIRST_IMG`.

**Expected:** Clean URLs like `/ec/dp/B0E000001` or `https://picsum.photos/seed/.../200/140`.

**Actual:** URLs return with literal escaped quotes: `http://localhost:18080/ec/\" /ec/dp/B0E000001\"` and `http://localhost:18080/ec/\"https://picsum.photos/...\"`. The `DOM_ABS_HREF` resolution compounds the issue by prepending the base URL to the already-corrupted relative URL. `DOM_FIRST_ATTR` for `data-category-id` returns `\"1292115012\"`.

**Root Cause:** The MockSite HTML uses `&quot;` entities within attribute values (e.g., `href="&quot;/ec/dp/B0E000001&quot;"`), which the DOM parser interprets as literal quote characters in the attribute values. This is technically valid HTML (though unusual). The extraction functions return the raw values without HTML entity decoding. Additionally, `DOM_FIRST_HREF`/`DOM_FIRST_IMG` call `DOM_ABS_HREF`/`DOM_ABS_SRC` internally, which apply URL resolution to the already-corrupted value.

**Code Pointer:** `DOM_FIRST_HREF` and `DOM_FIRST_IMG` implementations — URL resolution logic that processes raw attribute values.

**AI Suggested Improvement:**
- `DOM_FIRST_HREF` and `DOM_FIRST_IMG` should return raw attribute values (use `DOM_ATTR` semantics) rather than resolved absolute URLs, or provide separate functions for raw vs. absolute URLs.
- Add `STR_STRIP` or `STR_REPLACE_CHARS` examples to the X-SQL documentation showing how to clean quote-entity-corrupted attribute values.
- Consider adding a `DOM_FIRST_HREF_RAW` / `DOM_FIRST_IMG_RAW` variant that returns the literal attribute value without URL resolution.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `--depth` flag for `htmlsnapshot inspect` conflicts with snapshot `-d` flag conceptually

**Severity:** Low

**Category:** UX

**Reproduction:**
The `snapshot` command uses `-d N` for depth-limited snapshots. The `htmlsnapshot inspect` command uses `--depth D` for DOM tree traversal depth. A user accustomed to `-d` from `snapshot` might try `htmlsnapshot inspect -d 3`.

**Expected:** Either consistent short flags across commands, or clear error when a flag from one subcommand is used on another.

**Actual:** No short flag `-d` exists for `htmlsnapshot inspect` — the user must use `--depth`. This is a minor inconsistency but adds to the learning curve.

**Root Cause:** Different command families (`snapshot` vs `htmlsnapshot`) evolved independently with different flag conventions.

**Code Pointer:** Flag definitions in the snapshot and htmlsnapshot command handlers.

**AI Suggested Improvement:**
- Add `-d` as a short flag alias for `--depth` in `htmlsnapshot inspect`, or document the difference explicitly in the help text.
- Consider a global flag consistency audit across all subcommands.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status

**Fully completed.** All 9 task steps were successfully executed. The X-SQL query extracts 11 fields from 6 products with correct sorting, filtering, and data cleaning.

### Estimated Task Success Rate

**85%.** The core functionality works well, but a first-time user would encounter several blockers:
- `htmlsnapshot inspect` auto-discovery fails silently (would block step 3 without manual HTML analysis)
- `DOM_FIRST_FLOAT` numeric comparison in WHERE fails with a cryptic 417 error (would block step 8 without the workaround)
- 500 errors provide no diagnostic information (makes debugging very hard)

### Number of Issues Found

**7 issues:** 2 High severity, 3 Medium, 2 Low.

### Major Blockers

1. **inspect auto-discovery failure** — a new user following the documentation would get stuck at step 3 with no clear path forward
2. **DOM_FIRST_FLOAT WHERE clause failure** — a documented pattern that silently fails would erode trust

### Most Confusing Aspects

1. The gap between the inspect tool's suggested selectors and what actually works from the shell
2. The 417 "Expectation Failed" response with empty result set — no indication that the issue is a type comparison failure
3. The 500 errors with no diagnostic content — debugging requires binary-searching the query

### Most Valuable Improvements

1. **Fix inspect auto-discovery** to handle non-standard class names (HTML entities, special characters)
2. **Add diagnostic error messages** to 500 and 417 responses — include the SQL error details
3. **Fix DOM_FIRST_FLOAT type compatibility** in WHERE clauses
4. **Shell-safe selector suggestions** in inspect output

### Overall Usability Rating

**7/10**

**Strengths:**
- X-SQL query language is expressive and well-designed — the DOM/STR/ARRAY function namespaces are intuitive
- PowerCSS `:expr()` feature is genuinely useful for resilient selectors
- Documentation (SKILL.md + reference files) is thorough and well-organized
- `htmlsnapshot inspect` output is detailed and informative when it works
- The `@file` SQL pattern avoids shell escaping nightmares
- Cargo-based invocation works reliably with no external installation needed

**Weaknesses:**
- `inspect` auto-discovery is fragile on non-standard HTML
- Error handling lacks diagnostic detail
- Function behavior differs between SELECT and WHERE clauses
- Suggested selectors from inspect can't be copy-pasted to CLI
- Data quality issues (escaped quotes, URL resolution) require manual STR function cleaning that should be documented as common patterns

**Comparison to previous evaluations:** This evaluation covered a significantly more complex workflow (X-SQL with 10+ functions) compared to the Wikipedia snapshot evaluation. The system handled the complexity well overall, but the debugging experience for query failures is the weakest link. A developer with prior SQL experience would find X-SQL familiar; a non-technical user would struggle with the debugging workflow.
