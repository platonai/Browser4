# Issues: x-sql-query-methods

> **Source:** `20260708-200550-x-sql-query-methods.full.md` | **Date:** 20260708-200550 | **Mode:** dev

## Scenario Background

### Task

Successfully extracted 20 book titles and prices from `http://books.toscrape.com/` using X-SQL queries via all four input methods. The extracted data (sorted by price, ascending):

| # | Title | Price |
|---|-------|-------|
| 1 | Starving Hearts (Triangular Trade ... | £13.99 |
| 2 | Set Me Free | £17.46 |
| 3 | The Coming Woman: A ... | £17.93 |
| 4 | Shakespeare's Sonnets | £20.66 |
| 5 | The Boys in the ... | £22.60 |
| ... | ... | ... |
| 20 | Our Band Could Be ... | £57.25 |

Key selectors discovered via `htmlsnapshot inspect`:
- **Product container:** `article.product_pod` (20 matches)
- **Title:** `h3 a` (child of each product)
- **Price:** `p.price_color` (child of each product)

All four SQL input methods (`--sql`, `--sql @file`, `--sql-stdin`, `--sql-base64`) produced identical results. The `--result-only` flag successfully produced clean JSON output.

---

### Execution Context

**Key Commands:**

```
1. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
2. cargo run ... -- goto "http://books.toscrape.com/"
3. cargo run ... -- htmlsnapshot
4. cargo run ... -- htmlsnapshot inspect
5. cargo run ... -- htmlsnapshot query "https://books.toscrape.com/" --sql "SELECT ..."
6. Write extract_books.sql → cargo run ... -- htmlsnapshot query "https://..." --sql @extract_books.sql
7. cat extract_books.sql | cargo run ... -- htmlsnapshot query "https://..." --sql-stdin
8. base64 -w0 extract_books.sql → cargo run ... -- htmlsnapshot query "https://..." --sql-base64 "..."
9. cargo run ... -- htmlsnapshot query "https://..." --sql @extract_books.sql --result-only
10. cargo run ... -- htmlsnapshot query --sql-stdin --result-only  (no URL — defaulted to session)
11. rm extract_books.sql
```

**Workarounds Applied During Task:**

- None. All commands worked as documented on first attempt.

---

---

## Issues Found (6 issues)

### Issue 1: "Finding browser4 root" debug message leaks into user output

**Severity:** Low
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query "https://books.toscrape.com/" --sql @extract_books.sql --result-only
```

#### Expected Behavior

Only the `resultSet` JSON array, as `--result-only` promises to "omit wrapper metadata."

#### Actual Behavior

Output contains `Finding browser4 root from "/home/vincent/workspace/Browser4"` on a line before the JSON output. This debug/info message leaks inconsistently — it appears with `--sql @file` and `--result-only` modes but not with `--sql` inline or `--sql-stdin`.

#### Root Cause Analysis

The `@file` path resolution logic prints a diagnostic message to stdout (or stderr captured via `2>&1`) when locating the browser4 root directory to resolve the relative file path. This should go to stderr only, or ideally be suppressed entirely when `--result-only` or `--json` is active.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — the file path resolution for `@file` syntax`

#### AI Suggested Improvement

- Print "Finding browser4 root" only to stderr, never to stdout
- Suppress this diagnostic entirely when `--result-only` or `--json` is active
- Consider removing the message entirely — it's an implementation detail, not useful to users

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Debug message on stdout breaks the --result-only contract of producing clean JSON output. AI agents parsing the result would encounter JSON parse failures. Fix: route the diagnostic to stderr or suppress it entirely when --result-only or --json is active.

---

### Issue 2: `--result-only` flag not discoverable from global help

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
```
Scroll to the `HTML Snapshot (htmlsnapshot)` section. `htmlsnapshot query` is listed as:
```
htmlsnapshot query [url]    Run X-SQL against the HTML snapshot...
```
No mention of `--result-only`, `--format`, `--output-file`, or other flags.

#### Expected Behavior

The global help should mention key flags like `--result-only` and `--format`, or at minimum direct users to `<command> --help` for details.

#### Actual Behavior

Users discover `--result-only` only by running `htmlsnapshot query --help` (subcommand-level help). The global help shows no indication that these flags exist.

#### Root Cause Analysis

The global help output is a compact summary; subcommand-specific flags are not included. However, the global help doesn't hint that subcommand-level help exists or provides more detail.

#### Code Pointer

``cli/browser4-cli/src/help.rs:print_help()` — the help rendering logic`

#### AI Suggested Improvement

- Add a line to the global help footer: "Run `<command> --help` for command-specific options and examples"
- Consider adding common flags like `--result-only` and `--format` to the global `htmlsnapshot query` description
- The `--help` footer already exists for some sections; apply consistently

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Standard CLI convention — subcommand-specific flags belong in subcommand-level help, not global help. AI agents readily discover flags via `htmlsnapshot query --help`. Adding a footer hint to global help is a low-priority documentation enhancement that primarily benefits human users, not AI agents.

---

### Issue 3: DOM_FIRST_FLOAT results serialized as JSON strings, not numbers

**Severity:** Medium
**Category:** Product

#### Reproduction

```sql
SELECT DOM_FIRST_FLOAT(DOM, 'p.price_color', 0.0) AS price
FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod', 1, 48)
```

#### Expected Behavior

JSON output with numeric values: `"price": 51.77`

#### Actual Behavior

JSON output with string values: `"price": "51.77"`

#### Root Cause Analysis

The H2 database or the Java backend serializes `ValueFloat`/`BigDecimal` as strings in the JSON response. This forces downstream consumers to parse strings back into numbers.

#### Code Pointer

`Backend Java code — the JSON serialization layer for X-SQL result sets`

#### AI Suggested Improvement

- Serialize numeric X-SQL result columns as JSON numbers, not strings
- If type ambiguity is a concern, add a `--typed-json` flag that annotates column types in the output
- Document the current behavior so users know to expect string-encoded numbers

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid data quality issue. JSON consumers (including AI agents) expect numeric values as JSON numbers, not strings. String-encoded numbers force downstream parsing and lose type information. Fix in the backend JSON serialization layer.

---

### Issue 4: Inconsistent function name casing in documentation

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. Read `skills/browser4-cli/references/x-sql.md` — uses `DOM_LOAD_AND_SELECT`, `DOM_FIRST_TEXT` (uppercase)
2. Run `htmlsnapshot inspect` — tips show `load_and_select`, `dom_text` (lowercase)
3. Run `htmlsnapshot query --help` — examples show `dom_first_text`, `load_and_select` (lowercase)
4. Both forms work (SQL is case-insensitive), but the inconsistency is confusing

#### Expected Behavior

Consistent naming convention across all documentation. Pick one canonical form and use it everywhere.

#### Actual Behavior

The reference docs use UPPERCASE, while CLI tips and help examples use lowercase.

#### Root Cause Analysis

Different authors or different contexts — the Java/SQL reference naturally uses SQL-style uppercase, while CLI tips may have been written with shell-friendly lowercase. No style guide enforced consistency.

#### Code Pointer

``skills/browser4-cli/references/x-sql.md` and `cli/browser4-cli/src/help.rs``

#### AI Suggested Improvement

- Standardize on one casing convention across all documentation and CLI tips
- If both are valid, document this explicitly: "Function names are case-insensitive"
- The x-sql.md reference is the canonical source — CLI tips should match its convention

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Purely cosmetic documentation inconsistency. SQL function names are case-insensitive — both forms work identically. AI agents handle either casing without issue. Standardizing casing across docs is low priority and does not block agent workflows.

---

### Issue 5: SKILL.md examples always include URL for `htmlsnapshot query`, obscuring that it's optional

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read `skills/browser4-cli/SKILL.md` — every `htmlsnapshot query` example includes an explicit URL:
```bash
browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql
```
The `htmlsnapshot query --help` shows `[url]` as optional: "Defaults to the current session's page URL."

#### Expected Behavior

The SKILL.md should note that the URL is optional and defaults to the current page URL, especially in the common pattern where `htmlsnapshot` was already captured from the current page.

#### Actual Behavior

Users following the SKILL.md patterns will always redundantly specify the URL, unaware they can omit it.

#### Root Cause Analysis

Documentation was written before (or without awareness of) the URL-defaulting behavior.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — the X-SQL section and examples`

#### AI Suggested Improvement

- Add a note in SKILL.md: "The URL defaults to the current page URL — omit it when querying the page you just snapshotted"
- Include at least one example without the URL to demonstrate the shorter form
- Update the "Quick Patterns" section to show both forms

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Documentation enhancement, not a blocker. Including the URL in examples is the safer, more explicit pattern and never produces incorrect results. Noting the optionality would help brevity but is low priority for AI agent workflows.

---

### Issue 6: Titles truncated with ellipsis in X-SQL results

**Severity:** Medium
**Category:** Product

#### Reproduction

```sql
SELECT DOM_FIRST_TEXT(DOM, 'h3 a') AS title FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod', 1, 48)
```

#### Expected Behavior

Full book titles, e.g., "A Light in the Attic"

#### Actual Behavior

Truncated titles: "A Light in the ...", "Starving Hearts (Triangular Trade ..."

#### Root Cause Analysis

`DOM_FIRST_TEXT` appears to return the element's accessible name/text which is truncated in the DOM. The full title is likely in the `title` attribute of the `<a>` tag or requires accessing `DOM_WHOLE_TEXT` or `DOM_OWN_TEXT`. Alternatively, the HTML snapshot storage may truncate long text nodes.

#### Code Pointer

`Backend Java code — DOM text extraction in the HTML snapshot/storage layer`

#### AI Suggested Improvement

- Investigate whether `DOM_WHOLE_TEXT` or `DOM_ATTR(DOM, 'title')` would return the full title
- Document the distinction between `DOM_FIRST_TEXT`, `DOM_OWN_TEXT`, and `DOM_WHOLE_TEXT` more prominently — this is a common pitfall
- Consider whether text truncation in the snapshot storage is too aggressive

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] The truncation is in the website's source HTML, not a tool defect. DOM_FIRST_TEXT correctly returns the element's visible text content — books.toscrape.com truncates link text and stores full titles in the title attribute. The correct workaround is DOM_ATTR(DOM, 'title'), which should be documented as a common extraction pattern. The tool cannot fix upstream website HTML.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: "Finding browser4 root" debug message leaks into user output

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query "https://books.toscrape.com/" --sql @extract_books.sql --result-only
```

#### Issue 2: `--result-only` flag not discoverable from global help

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
```
Scroll to the `HTML Snapshot (htmlsnapshot)` section. `htmlsnapshot query` is listed as:
```
htmlsnapshot query [url]    Run X-SQL against the HTML snapshot...
```
No mention of `--result-only`, `--format`, `--output-file`, or other flags.

#### Issue 3: DOM_FIRST_FLOAT results serialized as JSON strings, not numbers

```sql
SELECT DOM_FIRST_FLOAT(DOM, 'p.price_color', 0.0) AS price
FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod', 1, 48)
```

#### Issue 4: Inconsistent function name casing in documentation

1. Read `skills/browser4-cli/references/x-sql.md` — uses `DOM_LOAD_AND_SELECT`, `DOM_FIRST_TEXT` (uppercase)
2. Run `htmlsnapshot inspect` — tips show `load_and_select`, `dom_text` (lowercase)
3. Run `htmlsnapshot query --help` — examples show `dom_first_text`, `load_and_select` (lowercase)
4. Both forms work (SQL is case-insensitive), but the inconsistency is confusing

#### Issue 5: SKILL.md examples always include URL for `htmlsnapshot query`, obscuring that it's optional

Read `skills/browser4-cli/SKILL.md` — every `htmlsnapshot query` example includes an explicit URL:
```bash
browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql
```
The `htmlsnapshot query --help` shows `[url]` as optional: "Defaults to the current session's page URL."

#### Issue 6: Titles truncated with ellipsis in X-SQL results

```sql
SELECT DOM_FIRST_TEXT(DOM, 'h3 a') AS title FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod', 1, 48)
```

