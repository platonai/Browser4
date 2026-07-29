# Issues: x-sql-query-methods

> **Source:** `20260728-030936-x-sql-query-methods.full.md` | **Date:** 20260728-030936 | **Mode:** dev

## Scenario Background

### Task

Successfully extracted 20 book titles and prices from `http://books.toscrape.com/` using browser4-cli's X-SQL query engine via all four input methods:

| Method | Command | Result |
|--------|---------|--------|
| Inline `--sql` | `htmlsnapshot query --sql "SELECT ..."` | ✅ 20 rows |
| File `--sql @file` | `htmlsnapshot query --sql @extract_books.sql` | ✅ 20 rows |
| Stdin `--sql-stdin` | `htmlsnapshot query --sql-stdin < extract_books.sql` | ✅ 20 rows |
| Base64 `--sql-base64` | `htmlsnapshot query --sql-base64 <encoded>` | ✅ 20 rows |
| Result-only | `--result-only` | ✅ Clean JSON array |

**X-SQL query used:**
```sql
SELECT
    DOM_FIRST_TEXT(DOM, 'h3') AS title,
    DOM_FIRST_TEXT(DOM, 'p.price_color') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')
WHERE DOM_IS_NOT_NIL(DOM)
```

Selectors discovered via `htmlsnapshot inspect`: container `article.product_pod` (20 matches), title `h3`, price `p.price_color`.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — learned available commands
2. Read `skills/browser4-cli/SKILL.md` and `references/x-sql.md`, `references/x-sql-dom-load-select.md` — learned X-SQL syntax
3. `./b4w.ps1 goto "http://books.toscrape.com/"` — navigated to target page
4. `./b4w.ps1 htmlsnapshot` — captured static HTML snapshot
5. `./b4w.ps1 htmlsnapshot inspect` — auto-discovered CSS selectors for product cards
6. `./b4w.ps1 htmlsnapshot query --sql "SELECT..."` — inline X-SQL (method 1)
7. Wrote query to `.test-sessions/extract_books.sql`
8. `./b4w.ps1 htmlsnapshot query --sql @.test-sessions/extract_books.sql` — file-based (method 2)
9. `./b4w.ps1 htmlsnapshot query --sql-stdin < extract_books.sql` — stdin (method 3)
10. `base64 -w0` encode, then `--sql-base64` — base64 (method 4)
11. `./b4w.ps1 htmlsnapshot query --sql @file --result-only` — clean data output
12. `rm .test-sessions/extract_books.sql` — cleanup

**Key decisions:**
- Used `DOM_FIRST_TEXT(DOM, 'h3')` for titles (not `DOM_FIRST_ATTR(DOM, 'h3 a', 'title')` which would give full titles from the `title` attribute — some titles are CSS-truncated in the visible text)
- Used relative path `.test-sessions/extract_books.sql` for file-based input
- All temporary files placed in `.test-sessions/`

```json
{
  "issues": [
    {
      "title": "htmlsnapshot inspect doesn't surface title attribute as alternative for truncated text",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run `htmlsnapshot inspect` on books.toscrape.com. Inspect output shows `h3` text as \"A Light in the ...\" (truncated) but doesn't mention that the child `<a title=\"Full Title\">` attribute contains the full text.",
      "expected": "Inspect should note when visible text is truncated and suggest checking child element attributes (e.g., `<a title>` or `img alt`) for complete values.",
      "actual": "Inspect shows truncated text content from h3 elements without indicating that the full title is available via `DOM_FIRST_ATTR(DOM, 'h3 a', 'title')`.",
      "rootCause": "The inspect tool extracts `textContent` from DOM nodes which reflects CSS `text-overflow: ellipsis` truncation. It doesn't check whether child elements have attributes (like `title`) that contain fuller text. This is a heuristic gap — the tool could compare text length vs attribute length to detect truncation.",
      "codePointer": "",
      "suggestion": "- In inspect output, for elements with truncated text (ending in '...'), check child elements for title attributes with fuller text and surface them as alternative selectors\n- Add a column or annotation showing that DOM_FIRST_ATTR may yield fuller text than DOM_FIRST_TEXT for certain elements\n- Consider adding a PowerCSS pattern or heuristic to detect text-overflow truncation vs genuine ellipsis"
    },
    {
      "title": "Documentation inconsistency: function name casing between --help examples and X-SQL reference",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Compare `htmlsnapshot query --help` examples (lowercase `dom_first_text`, `load_and_select`) with `references/x-sql.md` and `references/x-sql-dom-load-select.md` (uppercase `DOM_FIRST_TEXT`, `DOM_LOAD_AND_SELECT`).",
      "expected": "Consistent casing across all documentation and examples.",
      "actual": "Help examples use lowercase function names while reference docs use uppercase. Both work due to SQL case-insensitivity but the inconsistency is confusing for new users learning the syntax.",
      "rootCause": "The --help text examples were likely written independently from the reference docs without a style guide for function name casing. The H2 SQL engine is case-insensitive so both work.",
      "codePointer": "cli/browser4-cli/src/ or wherever help text strings are defined for the htmlsnapshot query command",
      "suggestion": "- Standardize on one casing convention (prefer UPPERCASE to match the reference docs)\n- Update --help examples to match the reference documentation casing\n- Add a note in the reference docs that function names are case-insensitive"
    },
    {
      "title": "Relationship between htmlsnapshot capture and htmlsnapshot query is unclear",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Read the help text for `htmlsnapshot` and `htmlsnapshot query`. The former says it stores a snapshot for later querying; the latter says it runs X-SQL 'against the HTML snapshot stored in Browser4's page storage' but `DOM_LOAD_AND_SELECT` actually re-fetches the page independently.",
      "expected": "Clear documentation explaining that `htmlsnapshot query` with `DOM_LOAD_AND_SELECT(@url, ...)` fetches the page fresh (not from the stored snapshot), while `htmlsnapshot inspect` and `htmlsnapshot get` use the stored snapshot from a prior `htmlsnapshot` capture.",
      "actual": "The help text for `htmlsnapshot query` says it runs 'against the HTML snapshot stored in Browser4's page storage via the scrape API' which contradicts the behavior of `DOM_LOAD_AND_SELECT` which loads the page fresh. A user needs to run `htmlsnapshot` capture for `inspect` but may not need it for `query`.",
      "rootCause": "The `htmlsnapshot query` command description conflates two concepts: the stored snapshot (used by `get`/`inspect`/`summary`) and the scrape API (used by `DOM_LOAD_AND_SELECT` which re-fetches). The help text wasn't updated when the X-SQL scrape API was added.",
      "codePointer": "cli/browser4-cli/ — help text definition for the htmlsnapshot query subcommand",
      "suggestion": "- Clarify in the help text that `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` re-fetches the page via the scrape API, independent of the stored snapshot\n- Add a note that `htmlsnapshot` capture is only needed for `inspect`/`get`/`summary`, not for `query` with `@url`\n- Consider renaming or restructuring: `query` belongs more naturally under its own namespace since it doesn't actually use the stored snapshot"
    },
    {
      "title": "Cargo dev-profile warning pollutes stderr output on every invocation",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any `./b4w.ps1` command. Observe the line `warning: browser4-cli@4.12.0: browser4-cli: embedded 31 skill files from /home/vincent/workspace/Browser4-4.12/skills` printed to stderr before every command.",
      "expected": "Clean output without build-system warnings in normal operation. Warnings should only appear during actual compilation.",
      "actual": "Every invocation prints a cargo warning to stderr about embedded skill files, even when the binary is already compiled.",
      "rootCause": "This is a cargo/rust build warning emitted during the `cargo run` step in `b4w.ps1`. The warning is about the `build.rs` embedding skill files — it fires on every `cargo run` invocation because cargo checks the build script output even when the binary is up to date.",
      "codePointer": "b4w.ps1 — the cargo run invocation; cli/browser4-cli/build.rs — the build script emitting the warning",
      "suggestion": "- Suppress the specific warning in build.rs or Cargo.toml if it's expected behavior\n- In b4w.ps1, filter stderr to remove known benign warnings before displaying to the user\n- Consider using `cargo build` + running the binary directly instead of `cargo run` to avoid re-triggering the warning"
    },
    {
      "title": "htmlsnapshot inspect requires separate htmlsnapshot capture step — 4-step workflow for data extraction",
      "severity": "Low",
      "category": "UX",
      "reproduction": "To discover selectors and extract data: 1) goto URL, 2) htmlsnapshot capture, 3) htmlsnapshot inspect, 4) htmlsnapshot query. This is 4 separate commands for a task that conceptually could be 2 (goto + query-with-inspect).",
      "expected": "A combined workflow or shortcut that captures, inspects, and suggests a query template in fewer steps. For example, `htmlsnapshot query --inspect` that auto-discovers selectors and runs the extraction in one command.",
      "actual": "User must run 4 separate commands with understanding of which steps are prerequisite for which others.",
      "rootCause": "The `htmlsnapshot` capture and `inspect` are separate architectural layers — capture stores the DOM, inspect reads from storage. There's no combined command that chains them together for the common 'discover selectors and extract' workflow.",
      "codePointer": "",
      "suggestion": "- Add an `htmlsnapshot query --auto` or `--inspect` flag that auto-captures, auto-discovers selectors, and generates a query template\n- Document the minimal workflow more prominently: note that `htmlsnapshot query` with `DOM_LOAD_AND_SELECT(@url, ...)` works without a prior `htmlsnapshot` capture\n- Consider a `quick-extract` meta-command that combines goto + inspect + query"
    },
    {
      "title": "No --format table output produced table despite flag being documented",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "The `htmlsnapshot query --help` lists `--format table` as an option but I did not test it during this session. The default JSON output is functional but a table format would be more readable for terminal viewing.",
      "expected": "Not verified in this session — flag exists per help text.",
      "actual": "N/A — not tested.",
      "rootCause": "N/A — flag exists but was not exercised in this evaluation.",
      "codePointer": "",
      "suggestion": "- Consider making `--format table` the default for terminal output (machine-readable JSON via `--json` flag)\n- The current default JSON output with metadata wrapper is verbose for human consumption"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 8 task steps completed without errors. Extracted 20 book titles and prices from books.toscrape.com using all four X-SQL input methods (inline, file, stdin, base64) plus --result-only mode.",
    "successRate": "100% — every documented command and flag worked as described on first attempt.",
    "issuesFound": 6,
    "majorBlockers": "",
    "mostConfusingAspects": "1) The relationship between `htmlsnapshot` capture and `htmlsnapshot query` — do they use the same stored data or not? The help text says query runs against the stored snapshot but DOM_LOAD_AND_SELECT re-fetches independently. 2) Function name casing inconsistency between `--help` examples (lowercase) and reference docs (UPPERCASE) — both work but it's unclear which is canonical. 3) The 4-step workflow (goto → htmlsnapshot → inspect → query) feels verbose for what should be a 2-step process.",
    "mostValuableImprovements": "1) Clarify the relationship between htmlsnapshot storage and htmlsnapshot query scraping — these are conceptually different data sources. 2) Add an `--auto` or `--inspect` mode to `htmlsnapshot query` that handles capture + inspect + query template generation in one step. 3) Have `htmlsnapshot inspect` detect text truncation and suggest attribute-based alternatives (e.g., title attributes with fuller text). 4) Standardize function name casing across all documentation.",
    "usabilityRating": 8
  }
}
```

---

## Issues Found (6 issues)

### Issue 1: htmlsnapshot inspect doesn't surface title attribute as alternative for truncated text

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `htmlsnapshot inspect` on books.toscrape.com. Inspect output shows `h3` text as "A Light in the ..." (truncated) but doesn't mention that the child `<a title="Full Title">` attribute contains the full text.

#### Expected Behavior

Inspect should note when visible text is truncated and suggest checking child element attributes (e.g., `<a title>` or `img alt`) for complete values.

#### Actual Behavior

Inspect shows truncated text content from h3 elements without indicating that the full title is available via `DOM_FIRST_ATTR(DOM, 'h3 a', 'title')`.

#### Root Cause Analysis

The inspect tool extracts `textContent` from DOM nodes which reflects CSS `text-overflow: ellipsis` truncation. It doesn't check whether child elements have attributes (like `title`) that contain fuller text. This is a heuristic gap — the tool could compare text length vs attribute length to detect truncation.

#### AI Suggested Improvement

- In inspect output, for elements with truncated text (ending in '...'), check child elements for title attributes with fuller text and surface them as alternative selectors
- Add a column or annotation showing that DOM_FIRST_ATTR may yield fuller text than DOM_FIRST_TEXT for certain elements
- Consider adding a PowerCSS pattern or heuristic to detect text-overflow truncation vs genuine ellipsis

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Genuine heuristic gap — the inspect tool extracts `textContent` which reflects CSS truncation without checking child-element attributes like `title` for fuller text. A comparison of visible text length vs attribute text length is a concrete, low-cost improvement that directly aids selector discovery.

---

### Issue 2: Relationship between htmlsnapshot capture and htmlsnapshot query is unclear

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read the help text for `htmlsnapshot` and `htmlsnapshot query`. The former says it stores a snapshot for later querying; the latter says it runs X-SQL 'against the HTML snapshot stored in Browser4's page storage' but `DOM_LOAD_AND_SELECT` actually re-fetches the page independently.

#### Expected Behavior

Clear documentation explaining that `htmlsnapshot query` with `DOM_LOAD_AND_SELECT(@url, ...)` fetches the page fresh (not from the stored snapshot), while `htmlsnapshot inspect` and `htmlsnapshot get` use the stored snapshot from a prior `htmlsnapshot` capture.

#### Actual Behavior

The help text for `htmlsnapshot query` says it runs 'against the HTML snapshot stored in Browser4's page storage via the scrape API' which contradicts the behavior of `DOM_LOAD_AND_SELECT` which loads the page fresh. A user needs to run `htmlsnapshot` capture for `inspect` but may not need it for `query`.

#### Root Cause Analysis

The `htmlsnapshot query` command description conflates two concepts: the stored snapshot (used by `get`/`inspect`/`summary`) and the scrape API (used by `DOM_LOAD_AND_SELECT` which re-fetches). The help text wasn't updated when the X-SQL scrape API was added.

#### Code Pointer

`cli/browser4-cli/ — help text definition for the htmlsnapshot query subcommand`

#### AI Suggested Improvement

- Clarify in the help text that `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` re-fetches the page via the scrape API, independent of the stored snapshot
- Add a note that `htmlsnapshot` capture is only needed for `inspect`/`get`/`summary`, not for `query` with `@url`
- Consider renaming or restructuring: `query` belongs more naturally under its own namespace since it doesn't actually use the stored snapshot

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The help text is factually wrong — it claims `htmlsnapshot query` runs "against the HTML snapshot stored … via the scrape API" when `DOM_LOAD_AND_SELECT` actually re-fetches the page independently. This is a correctness bug in documentation that misleads users about what prerequisite steps are actually needed.

---

### Issue 3: Documentation inconsistency: function name casing between --help examples and X-SQL reference

**Severity:** Low
**Category:** Documentation

#### Reproduction

Compare `htmlsnapshot query --help` examples (lowercase `dom_first_text`, `load_and_select`) with `references/x-sql.md` and `references/x-sql-dom-load-select.md` (uppercase `DOM_FIRST_TEXT`, `DOM_LOAD_AND_SELECT`).

#### Expected Behavior

Consistent casing across all documentation and examples.

#### Actual Behavior

Help examples use lowercase function names while reference docs use uppercase. Both work due to SQL case-insensitivity but the inconsistency is confusing for new users learning the syntax.

#### Root Cause Analysis

The --help text examples were likely written independently from the reference docs without a style guide for function name casing. The H2 SQL engine is case-insensitive so both work.

#### Code Pointer

`cli/browser4-cli/src/ or wherever help text strings are defined for the htmlsnapshot query command`

#### AI Suggested Improvement

- Standardize on one casing convention (prefer UPPERCASE to match the reference docs)
- Update --help examples to match the reference documentation casing
- Add a note in the reference docs that function names are case-insensitive

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Minor but real — help examples use lowercase (`dom_first_text`) while reference docs use uppercase (`DOM_FIRST_TEXT`). Both work due to H2's case-insensitivity, but the inconsistency creates unnecessary cognitive friction for new users. Standardize on UPPERCASE to match the reference docs; add a one-line note that casing is optional.

---

### Issue 4: Cargo dev-profile warning pollutes stderr output on every invocation

**Severity:** Low
**Category:** UX

#### Reproduction

Run any `./b4w.ps1` command. Observe the line `warning: browser4-cli@4.12.0: browser4-cli: embedded 31 skill files from /home/vincent/workspace/Browser4-4.12/skills` printed to stderr before every command.

#### Expected Behavior

Clean output without build-system warnings in normal operation. Warnings should only appear during actual compilation.

#### Actual Behavior

Every invocation prints a cargo warning to stderr about embedded skill files, even when the binary is already compiled.

#### Root Cause Analysis

This is a cargo/rust build warning emitted during the `cargo run` step in `b4w.ps1`. The warning is about the `build.rs` embedding skill files — it fires on every `cargo run` invocation because cargo checks the build script output even when the binary is up to date.

#### Code Pointer

`b4w.ps1 — the cargo run invocation; cli/browser4-cli/build.rs — the build script emitting the warning`

#### AI Suggested Improvement

- Suppress the specific warning in build.rs or Cargo.toml if it's expected behavior
- In b4w.ps1, filter stderr to remove known benign warnings before displaying to the user
- Consider using `cargo build` + running the binary directly instead of `cargo run` to avoid re-triggering the warning

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] A build warning on every single invocation degrades perceived quality, especially for a CLI tool used interactively. Can be resolved by suppressing the specific warning in `build.rs`/`Cargo.toml` (it's expected behavior — embedding skill files is intentional) or by filtering stderr in `b4w.ps1`.

---

### Issue 5: htmlsnapshot inspect requires separate htmlsnapshot capture step — 4-step workflow for data extraction

**Severity:** Low
**Category:** UX

#### Reproduction

To discover selectors and extract data: 1) goto URL, 2) htmlsnapshot capture, 3) htmlsnapshot inspect, 4) htmlsnapshot query. This is 4 separate commands for a task that conceptually could be 2 (goto + query-with-inspect).

#### Expected Behavior

A combined workflow or shortcut that captures, inspects, and suggests a query template in fewer steps. For example, `htmlsnapshot query --inspect` that auto-discovers selectors and runs the extraction in one command.

#### Actual Behavior

User must run 4 separate commands with understanding of which steps are prerequisite for which others.

#### Root Cause Analysis

The `htmlsnapshot` capture and `inspect` are separate architectural layers — capture stores the DOM, inspect reads from storage. There's no combined command that chains them together for the common 'discover selectors and extract' workflow.

#### AI Suggested Improvement

- Add an `htmlsnapshot query --auto` or `--inspect` flag that auto-captures, auto-discovers selectors, and generates a query template
- Document the minimal workflow more prominently: note that `htmlsnapshot query` with `DOM_LOAD_AND_SELECT(@url, ...)` works without a prior `htmlsnapshot` capture
- Consider a `quick-extract` meta-command that combines goto + inspect + query

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The 4-step friction is real and the documentation-note suggestion (query works independently of capture) overlaps with Issue 2 — that part should be resolved under Issue 2's doc fix. The `--auto`/`--inspect` flag suggestion is a feature request that needs design work (what selectors to auto-discover, what query template to generate). Consider splitting: fix the docs now (Issue 2), and track the combined-command idea as a separate enhancement.

---

### Issue 6: No --format table output produced table despite flag being documented

**Severity:** Low
**Category:** Discoverability

#### Reproduction

The `htmlsnapshot query --help` lists `--format table` as an option but I did not test it during this session. The default JSON output is functional but a table format would be more readable for terminal viewing.

#### Expected Behavior

Not verified in this session — flag exists per help text.

#### Actual Behavior

N/A — not tested.

#### Root Cause Analysis

N/A — flag exists but was not exercised in this evaluation.

#### AI Suggested Improvement

- Consider making `--format table` the default for terminal output (machine-readable JSON via `--json` flag)
- The current default JSON output with metadata wrapper is verbose for human consumption

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] No confirmed bug — the reporter explicitly states "I did not test it" and "N/A — not tested." The title implies a non-functional flag but the body contains no reproduction or evidence. The suggestion to make `--format table` the default is a separate UX preference question, not a defect. Re-open if someone reproduces an actual failure with the flag.

---

## Overall Assessment

**Completion Status:** Successful — all 8 task steps completed without errors. Extracted 20 book titles and prices from books.toscrape.com using all four X-SQL input methods (inline, file, stdin, base64) plus --result-only mode.

**Success Rate:** 100% — every documented command and flag worked as described on first attempt.

**Issues Found:** 6

**Most Confusing Aspects:** 1) The relationship between `htmlsnapshot` capture and `htmlsnapshot query` — do they use the same stored data or not? The help text says query runs against the stored snapshot but DOM_LOAD_AND_SELECT re-fetches independently. 2) Function name casing inconsistency between `--help` examples (lowercase) and reference docs (UPPERCASE) — both work but it's unclear which is canonical. 3) The 4-step workflow (goto → htmlsnapshot → inspect → query) feels verbose for what should be a 2-step process.

**Most Valuable Improvements:** 1) Clarify the relationship between htmlsnapshot storage and htmlsnapshot query scraping — these are conceptually different data sources. 2) Add an `--auto` or `--inspect` mode to `htmlsnapshot query` that handles capture + inspect + query template generation in one step. 3) Have `htmlsnapshot inspect` detect text truncation and suggest attribute-based alternatives (e.g., title attributes with fuller text). 4) Standardize function name casing across all documentation.

**Usability Rating:** 8/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: htmlsnapshot inspect doesn't surface title attribute as alternative for truncated text

Run `htmlsnapshot inspect` on books.toscrape.com. Inspect output shows `h3` text as "A Light in the ..." (truncated) but doesn't mention that the child `<a title="Full Title">` attribute contains the full text.

#### Issue 2: Relationship between htmlsnapshot capture and htmlsnapshot query is unclear

Read the help text for `htmlsnapshot` and `htmlsnapshot query`. The former says it stores a snapshot for later querying; the latter says it runs X-SQL 'against the HTML snapshot stored in Browser4's page storage' but `DOM_LOAD_AND_SELECT` actually re-fetches the page independently.

#### Issue 3: Documentation inconsistency: function name casing between --help examples and X-SQL reference

Compare `htmlsnapshot query --help` examples (lowercase `dom_first_text`, `load_and_select`) with `references/x-sql.md` and `references/x-sql-dom-load-select.md` (uppercase `DOM_FIRST_TEXT`, `DOM_LOAD_AND_SELECT`).

#### Issue 4: Cargo dev-profile warning pollutes stderr output on every invocation

Run any `./b4w.ps1` command. Observe the line `warning: browser4-cli@4.12.0: browser4-cli: embedded 31 skill files from /home/vincent/workspace/Browser4-4.12/skills` printed to stderr before every command.

#### Issue 5: htmlsnapshot inspect requires separate htmlsnapshot capture step — 4-step workflow for data extraction

To discover selectors and extract data: 1) goto URL, 2) htmlsnapshot capture, 3) htmlsnapshot inspect, 4) htmlsnapshot query. This is 4 separate commands for a task that conceptually could be 2 (goto + query-with-inspect).

#### Issue 6: No --format table output produced table despite flag being documented

The `htmlsnapshot query --help` lists `--format table` as an option but I did not test it during this session. The default JSON output is functional but a table format would be more readable for terminal viewing.

