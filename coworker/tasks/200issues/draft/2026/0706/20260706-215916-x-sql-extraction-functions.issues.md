# Issues: x-sql-extraction-functions

> **Source:** `20260706-215916-x-sql-extraction-functions.full.md` | **Date:** 20260706-215916 | **Mode:** dev

## Scenario Background

### Task

**Task Status:** ✅ **Completed** (with caveats)

The task successfully demonstrated browser4-cli's X-SQL query capabilities for e-commerce data extraction. The final enhanced query extracted 6 products with clean titles, numeric prices, product IDs, width metadata, and cleaned URLs — all sorted by price ascending.

**Final extracted data (sorted by price):**

| # | Title | Price | Link |
|---|-------|-------|------|
| 1 | Wireless Mouse | $24.99 | `/ec/dp/B0E000006` |
| 2 | USB-C Hub 7-in-1 | $29.95 | `/ec/dp/B0E000005` |
| 3 | Portable Bluetooth Speaker | $49.99 | `/ec/dp/B0E000003` |
| 4 | Wireless Noise-Cancelling Headphones | $199.99 | `/ec/dp/B0E000002` |
| 5 | Smartphone 128GB | $599.00 | `/ec/dp/B0E000004` |
| 6 | 4K OLED TV 55" | $899.99 | `/ec/dp/B0E000001` |

**Features demonstrated:** DOM functions (`DOM_FIRST_TEXT`, `DOM_FIRST_FLOAT`, `DOM_FIRST_HREF`, `DOM_FIRST_IMG`, `DOM_FIRST_ATTR`), STR functions (`STR_TRIM`, `STR_UPPER_CASE`, `STR_DEFAULT_IF_BLANK`, `STR_FIRST_FLOAT`, `STR_ABBREVIATE`), `ARRAY_FIRST_NOT_BLANK` with fallback chains, PowerCSS `:expr()`, `WHERE`, `ORDER BY`, `LIMIT`, `STR_REPLACE_CHARS` for data cleaning.

**Not demonstrated** (due to no LLM API key): `LLM_EXTRACT`, `LLM_CHAT`. The `LLM_EXTRACT` call produced a 417 "Expectation Failed" error with no actionable guidance about the missing API key.

---

### Execution Context

**Key Commands:**

1. `cargo run -- goto "http://localhost:18080/ec/b?node=1292115012"` — Navigate to Electronics category page
2. `cargo run -- htmlsnapshot` — Capture static HTML snapshot (11KB, 7 images, 9 links)
3. `cargo run -- htmlsnapshot inspect --max 3 --depth 3` — Auto-discover selectors (selector quoting issue)
4. `cargo run -- htmlsnapshot get html ".product-card"` — Test selector (returned "No elements matched")
5. `cargo run -- htmlsnapshot get all text "[href*='B0E0']"` — Find products via attribute selector (success: 6 products)
6. `cargo run -- htmlsnapshot export --file snapshot.html` — Export HTML for structural analysis
7. `cargo run -- htmlsnapshot inspect "[id^='product-']" --max 3 --depth 3` — Inspect with attribute selector (success: 7 matches found)
8. Multiple X-SQL queries via `htmlsnapshot query --sql @file.sql` with iterative refinement:
   - Basic query (step 4) — link/images extracted but titles/prices empty
   - Debug query — discovered escaped quotes in class names (`\"product-card\"`)
   - Fixed query with `[class*="..."]` selectors — all fields extracted
   - Enhanced query with STR/ARRAY/PowerCSS/WHERE/ORDER BY/LIMIT — fully working
   - LLM_EXTRACT query — 417 error (no API key)
9. `cargo run -- htmlsnapshot summary` — WPSI generated successfully (page type: Search Results)
10. `cargo run -- --version` — browser4-cli 0.1.28

**Key decisions:**
- Used `cargo run --` from `cli/browser4-cli` directory per SKILL.md development instructions
- Used `@query.sql` file-based SQL to avoid shell escaping on Windows
- Switched from class-based selectors (`.product-card`) to attribute-based (`[class*="product-card"]`) after discovering escaped quotes in stored HTML
- Used `cat > file << 'SQLEOF'` heredoc for writing SQL files

**Workarounds required:**
1. Had to export HTML manually to understand the DOM structure because `inspect` auto-discovery failed with quoting issues
2. Could not use CSS class selectors (`.product-card`) due to escaped quotes in stored HTML — had to use attribute substring selectors (`[class*="product-card"]`)
3. Could not test LLM functions because no API key was configured
4. Had to apply `STR_REPLACE_CHARS` to clean escaped quote artifacts from extracted URLs

---

---

## Issues Found (8 issues)

### Issue 1: HTML snapshot stores escaped-quote-encoded class names that break CSS selectors

**Severity:** High
**Category:** Product (Reliability / Data Quality)

#### Reproduction

```bash
cargo run -- htmlsnapshot
cargo run -- htmlsnapshot get text ".product-card"
```

#### Expected Behavior

`.product-card` selector should match all 6 product card divs, returning their text content.

#### Actual Behavior

`No elements matched ".product-card"`. The selector returns zero results. The stored HTML contains literal backslash-escaped quotes in class attribute values (`class="\"product-card\""`), making the class name `\"product-card\"` instead of `product-card`.

#### Root Cause Analysis

The HTML snapshot serialization in the backend stores HTML with escaped quotes in attribute values. The jsoup/DOM parser then treats these escaped quotes as part of the actual class name, so `.product-card` doesn't match `\"product-card\"`. This is likely in the snapshot capture pipeline where the raw HTML source has quoted attributes that get double-escaped during storage.

#### Code Pointer

`Likely in the backend Kotlin code that handles HTML snapshot capture and storage (`browser4-core` or `browser4-rest` module). The HTML serialization/deserialization layer is introducing or preserving escaped quotes.`

#### AI Suggested Improvement

- Normalize HTML attribute values during snapshot capture — strip or unescape `\"` sequences in attribute values before storing
- Add a pre-processing step in the DOM parser to handle common encoding artifacts (escaped quotes, HTML entities within attributes)
- Consider using jsoup's `Parser.unescapeEntities()` or equivalent during DOM construction for the snapshot

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: `htmlsnapshot inspect` auto-discovered selectors have quoting issues, returning 0 matches

**Severity:** Medium
**Category:** Reliability (Discoverability)

#### Reproduction

```bash
cargo run -- htmlsnapshot inspect --max 3 --depth 3
```

#### Expected Behavior

Auto-discovered selectors should match elements and show product card structure.

#### Actual Behavior

Output shows `Auto-discovered selector ".\"product-card\"" from ":root" also had no matches. - No elements matched.` The auto-discovered selector has mangled quoting.

#### Root Cause Analysis

The `inspect` command's automatic pattern discovery finds a class name that works internally but the rendered/discovered selector is displayed with escaped quotes that don't work when passed back to the query engine. The same root cause as Issue 1 — the escaped quotes in stored HTML corrupt the selector generation pipeline.

#### Code Pointer

`Likely in the backend code responsible for `htmlsnapshot inspect` selector discovery and pattern matching logic.`

#### AI Suggested Improvement

- Fix the underlying HTML storage quoting issue (Issue 1) — this would cascade-fix this issue
- Add a validation step in `inspect` that verifies auto-discovered selectors actually match before presenting them to the user
- Normalize selectors by stripping `\"` sequences from discovered class names before displaying them

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `LLM_EXTRACT` returns opaque 417 "Expectation Failed" when no LLM API key is configured

**Severity:** Medium
**Category:** UX (Error Messaging)

#### Reproduction

```sql
SELECT LLM_EXTRACT(DOM, '[class*="product-title"]', 'Extract category') AS category
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div', 1, 2)
```

#### Expected Behavior

A clear error message indicating: "LLM functions require an API key. Set DEEPSEEK_API_KEY, OPENAI_API_KEY, or OPENROUTER_API_KEY environment variable."

#### Actual Behavior

HTTP 417 "Expectation Failed" with no actionable guidance. The user has no idea what went wrong or how to fix it.

#### Root Cause Analysis

The backend returns a generic HTTP 417 status code for LLM functions when no API key is configured, without a descriptive error message body. The CLI passes this through without adding context.

#### Code Pointer

`Backend endpoint handling LLM function calls in `browser4-rest` or `browser4-core` module. The error response body should include a descriptive message.`

#### AI Suggested Improvement

- Return a descriptive error message with the 417 response (e.g., `{"error": "LLM_EXTRACT requires an LLM API key. Set DEEPSEEK_API_KEY, OPENAI_API_KEY, or OPENROUTER_API_KEY."}`)
- Add CLI-level detection: before sending LLM function queries, check if any LLM env vars are set and provide a helpful error early
- Document in the `htmlsnapshot query --help` output that LLM functions require API key configuration

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: Extracted URLs contain malformed paths due to escaped quote artifacts

**Severity:** Medium
**Category:** Product (Data Quality)

#### Reproduction

```sql
SELECT DOM_FIRST_HREF(DOM, 'a') AS link,
       DOM_FIRST_IMG(DOM, 'img') AS image
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div')
```

#### Expected Behavior

Clean URLs like `/ec/dp/B0E000001` and `https://picsum.photos/seed/1250857624/200/140`.

#### Actual Behavior

URLs contain literal `\"` sequences and are resolved incorrectly: `http://localhost:18080/ec/\"/ec/dp/B0E000001\"` for links, `http://localhost:18080/ec/\"https://picsum.photos/.../200/140\"` for images.

#### Root Cause Analysis

Same root cause as Issue 1. The stored HTML has escaped quotes in attribute values (`href="\"/ec/dp/B0E000001\""`). `DOM_FIRST_HREF` and `DOM_FIRST_IMG` extract the raw attribute value including the escape sequences, then URL resolution treats these as part of the path.

#### Code Pointer

`Same as Issue 1 — the HTML snapshot capture/deserialization pipeline that preserves escaped quotes.`

#### AI Suggested Improvement

- Fix the underlying HTML storage quoting (Issue 1) — this is the root cause
- Alternatively, add URL-aware cleaning in `DOM_FIRST_HREF` and `DOM_FIRST_IMG` to strip common encoding artifacts from resolved URLs
- Consider adding a `DOM_ABS_HREF` variant that handles malformed href values gracefully

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: `htmlsnapshot query` output is JSON-only — no human-readable table format available

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
cargo run -- htmlsnapshot query --sql @query.sql
```

#### Expected Behavior

A formatted table output (like a SQL client would show) for quick visual inspection of results.

#### Actual Behavior

Raw JSON output only. The user must parse JSON manually or use external tools. Compare with `htmlsnapshot get all text` which outputs a clean JSON array.

#### Root Cause Analysis

`htmlsnapshot query` goes through the scrape API which returns JSON. There's no client-side formatting for human-readable display.

#### AI Suggested Improvement

- Add a `--format table` option to render results as an ASCII table (use the same formatter as `crawl --format table`)
- Add a `--format csv` option for spreadsheet import
- Show a brief summary line (e.g., "6 rows returned") at the bottom of the output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: Working directory drifts into `cli/browser4-cli` when using `cargo run`

**Severity:** Low
**Category:** UX / Discoverability

#### Reproduction

Run `cargo run -- <command>` from the repository root using bash. After execution, the working directory is `cli/browser4-cli`.

#### Expected Behavior

The working directory should remain unchanged after command execution.

#### Actual Behavior

The working directory changes to `cli/browser4-cli`, which means subsequent file operations (like `cat > file`) create files in the CLI directory instead of the repo root.

#### Root Cause Analysis

The initial `cd cli/browser4-cli && cargo run -- ...` command changes the shell's working directory. Since shell state persists between `Bash` tool calls, subsequent commands run from `cli/browser4-cli`.

#### AI Suggested Improvement

- Document the recommended development workflow: use `cd cli/browser4-cli && cargo run -- <command>` consistently for all commands, or use a subshell `(cd cli/browser4-cli && cargo run -- <command>)`
- Alternatively, support a `--manifest-path` approach from the repo root: `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>`
- The SKILL.md already documents `cd cli/browser4-cli && cargo run --`, but it's easy to miss the directory change side effect

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: `DOM_FIRST_FLOAT` drops trailing zeros from price values

**Severity:** Low
**Category:** Product (Minor Data Quality)

#### Reproduction

```sql
SELECT STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '[class*="product-price"]'), 0.0) AS price_value
```

#### Expected Behavior

`$599.00` should be extracted as `599.00`.

#### Actual Behavior

Extracted as `599.0` — the trailing zero is dropped.

#### Root Cause Analysis

`STR_FIRST_FLOAT` parses the string and returns a `Float`/`Double` type which doesn't preserve trailing zeros. The JSON serialization then drops the insignificant trailing zero.

#### AI Suggested Improvement

- Consider adding a `STR_FIRST_DECIMAL` function that returns a string preserving exact formatting from the source
- Document this behavior in the X-SQL string functions reference
- For monetary values, recommend using `DOM_FIRST_TEXT` + `STR_TRIM` to preserve the exact display format, and use `STR_FIRST_FLOAT` only for numeric comparisons/sorting

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: `cargo run -- --help` outputs a wall of text with mixed organization

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```bash
cargo run -- --help
```

#### Expected Behavior

Clearly organized command groups with quick visual scanning.

#### Actual Behavior

A large wall of text where commands are grouped but the groups lack clear visual hierarchy. The "Snapshot" section confusingly contains both `htmlsnapshot` commands AND `snapshot grep` and `generate-locator`. The `snapshot` command is listed under "Core" while `snapshot grep` is under "Snapshot".

#### Root Cause Analysis

The help text organization in `help.rs` mixes the accessibility-tree `snapshot` command with the HTML `htmlsnapshot` command family. `snapshot grep` belongs to the `snapshot` command but appears in the "Snapshot" section alongside `htmlsnapshot` subcommands.

#### AI Suggested Improvement

- Split the help into two clearly labeled sections: "Accessibility Snapshot (`snapshot`)" and "HTML Snapshot (`htmlsnapshot`)"
- Move `snapshot grep` out of the "Snapshot" section and group it with `snapshot` under "Core" or its own section
- Consider adding a visual separator (like `---`) between command groups

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: HTML snapshot stores escaped-quote-encoded class names that break CSS selectors

```bash
cargo run -- htmlsnapshot
cargo run -- htmlsnapshot get text ".product-card"
```

#### Issue 2: `htmlsnapshot inspect` auto-discovered selectors have quoting issues, returning 0 matches

```bash
cargo run -- htmlsnapshot inspect --max 3 --depth 3
```

#### Issue 3: `LLM_EXTRACT` returns opaque 417 "Expectation Failed" when no LLM API key is configured

```sql
SELECT LLM_EXTRACT(DOM, '[class*="product-title"]', 'Extract category') AS category
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div', 1, 2)
```

#### Issue 4: Extracted URLs contain malformed paths due to escaped quote artifacts

```sql
SELECT DOM_FIRST_HREF(DOM, 'a') AS link,
       DOM_FIRST_IMG(DOM, 'img') AS image
FROM DOM_LOAD_AND_SELECT(@url, '#product-list > div')
```

#### Issue 5: `htmlsnapshot query` output is JSON-only — no human-readable table format available

```bash
cargo run -- htmlsnapshot query --sql @query.sql
```

#### Issue 6: Working directory drifts into `cli/browser4-cli` when using `cargo run`

Run `cargo run -- <command>` from the repository root using bash. After execution, the working directory is `cli/browser4-cli`.

#### Issue 7: `DOM_FIRST_FLOAT` drops trailing zeros from price values

```sql
SELECT STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '[class*="product-price"]'), 0.0) AS price_value
```

#### Issue 8: `cargo run -- --help` outputs a wall of text with mixed organization

```bash
cargo run -- --help
```

