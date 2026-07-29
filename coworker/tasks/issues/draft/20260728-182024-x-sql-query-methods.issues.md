# Issues: x-sql-query-methods

> **Source:** `20260728-182024-x-sql-query-methods.full.md` | **Date:** 20260728-182024 | **Mode:** dev

## Scenario Background

### Task

Successfully completed all 8 steps:

1. Navigated to `http://books.toscrape.com/` (auto-redirected to HTTPS)
2. Used `htmlsnapshot` + `htmlsnapshot inspect` to discover CSS selectors: `article.product_pod` (product cards), `h3 a` (titles), `p.price_color` (prices)
3. Ran inline X-SQL via `--sql` — extracted 20 book titles and prices
4. Wrote query to `.test-sessions/extract_books.sql`, ran via `--sql @file` — same results
5. Piped query via `cat file | ./b4w.ps1 ... --sql-stdin` — same results
6. Base64-encoded query, ran via `--sql-base64` — same results
7. Ran with `--result-only` — clean JSON array with title/price pairs, no metadata
8. Deleted `extract_books.sql`

**Extracted data sample** (20 books total):

| Title | Price |
|-------|-------|
| A Light in the … | £51.77 |
| Tipping the Velvet | £53.74 |
| Soumission | £50.10 |
| Sharp Objects | £47.82 |
| … | … |
| It's Only the Himalayas | £45.17 |

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — read CLI help
2. `./b4w.ps1 goto "http://books.toscrape.com/"` — navigate
3. `./b4w.ps1 htmlsnapshot` — capture static HTML snapshot
4. `./b4w.ps1 htmlsnapshot inspect` — discover CSS selectors
5. `./b4w.ps1 htmlsnapshot query URL --sql "…"` — inline X-SQL
6. `./b4w.ps1 htmlsnapshot query URL --sql @.test-sessions/extract_books.sql` — file-based
7. `cat .test-sessions/extract_books.sql | ./b4w.ps1 htmlsnapshot query URL --sql-stdin` — stdin
8. `BASE64=$(… | base64 -w0) && ./b4w.ps1 htmlsnapshot query URL --sql-base64 "$BASE64"` — base64
9. `./b4w.ps1 htmlsnapshot query URL --sql @file --result-only` — data-only output
10. `rm .test-sessions/extract_books.sql` — cleanup

**Important decisions:**
- Used `DOM_FIRST_TEXT(DOM, 'h3 a')` for titles (link inside heading) rather than just `h3`
- Used `article.product_pod` as the scoping selector from `htmlsnapshot inspect` suggestions
- Read the `x-sql.md` reference to understand `DOM_LOAD_AND_SELECT` syntax and `@url` placeholder
- Used `base64 -w0` to avoid line wrapping in the base64 encoding

**Workarounds:** None needed. All commands worked on the first attempt.

```json
{
  "issues": [
    {
      "title": "Diagnostic message leaks into stdout with --result-only when using --sql @file",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.ps1 htmlsnapshot query https://books.toscrape.com/ --sql @.test-sessions/extract_books.sql --result-only",
      "expected": "Only the JSON array of results on stdout, no other text.",
      "actual": "The line \"Finding browser4 root from \"/home/vincent/workspace/Browser4-4.12\"\" appears on stdout before the JSON array. This contaminates machine-readable output and would break JSON parsers.",
      "rootCause": "The file-path resolution logic (likely in the CLI's argument parsing for `@file` syntax) prints a diagnostic to stdout before the query executes. The `--result-only` flag appears to filter only the server response envelope, not earlier CLI-level diagnostic output. The file resolution happens before the HTTP request is made, so it's outside the scope of the server-side response filtering.",
      "codePointer": "cli/browser4-cli/src/ — likely in the argument parsing or SQL file loading path where `@file` paths are resolved and the \"Finding browser4 root\" message is printed.",
      "suggestion": "- Move the \"Finding browser4 root\" message to stderr instead of stdout\n- In `--result-only` mode, suppress all non-result stdout, including CLI-level diagnostics\n- Consider printing this diagnostic only in verbose/debug mode, or only when the root cannot be found automatically"
    },
    {
      "title": "htmlsnapshot inspect does not directly suggest CSS selector for title text inside h3 > a",
      "severity": "Low",
      "category": "UX",
      "reproduction": "1. ./b4w.ps1 goto http://books.toscrape.com/ 2. ./b4w.ps1 htmlsnapshot 3. ./b4w.ps1 htmlsnapshot inspect",
      "expected": "The inspect output should suggest a text-extraction selector for the book title, such as \"h3 a\" or \"h3\", in the \"Suggested selectors\" list alongside the price selector `p.price_color`.",
      "actual": "The inspect output shows `h3:expr(a>0)` as a suggested selector (a PowerCSS structural selector), and the sample structure shows the h3 element with text. But unlike `p.price_color` which directly shows sample values (\"£51.77\" | \"£53.74\" | \"£50.10\"), there is no explicit text-extraction selector suggestion like \"h3 a\" showing sample title values. The user must infer from the sample structure that `h3` contains the title text.",
      "rootCause": "The `htmlsnapshot inspect` text-discovery logic detects text-bearing elements with PowerCSS expression selectors but does not generate explicit text-extraction selector suggestions in the same format as it does for other element types. The sample values are shown in the tree structure but not in the suggested selectors table for non-price-like patterns. Investigation needed to understand why `p.price_color` gets sample values shown but `h3 a` does not.",
      "codePointer": "",
      "suggestion": "- Add text-extraction selector suggestions with sample values for heading/name/title patterns, similar to how price selectors are shown\n- Show sample text values in the suggested selectors table for any text-bearing element, not just price-like elements\n- Consider a dedicated \"Text selectors\" section in the inspect output showing the best selector for visible text content"
    },
    {
      "title": "Inline --sql quoting requires escaping single quotes on Linux/bash, risking shell errors",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 htmlsnapshot query URL --sql \"SELECT DOM_FIRST_TEXT(DOM, 'h3 a') AS title FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')\"",
      "expected": "The command should work without complex escaping, or the documentation should clearly show the exact escaping pattern needed for Linux/bash with single-quoted CSS selectors inside double-quoted SQL.",
      "actual": "The command works but the CLI internally re-escapes the single quotes (visible in the debug output showing `'\\''h3 a'\\''`). This fragile escaping could easily break with different shells or more complex queries. The SKILL.md warns about this on Windows but the issue affects Linux/bash too.",
      "rootCause": "The `./b4w.ps1` wrapper passes arguments through PowerShell which has its own quoting rules. When the outer SQL uses double quotes and CSS selectors use single quotes, the shell and PowerShell layers interact in ways that require careful escaping. This is inherent to mixing shell quoting with PowerShell argument passing.",
      "codePointer": "",
      "suggestion": "- Add a Linux/bash-specific warning in the shell-quoting reference alongside the existing Windows warning\n- Show a concrete Linux/bash example with single-quote escaping in the X-SQL quick-start section\n- Recommend `--sql @file` or `--sql-stdin` as the primary methods even on Linux when CSS selectors contain quotes\n- Consider adding `--sql-stdin` as the first recommended method in the SKILL.md decision tree"
    },
    {
      "title": "The @url placeholder relationship to the positional URL argument is undocumented in CLI help",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "./b4w.ps1 htmlsnapshot query --help",
      "expected": "The help output for `htmlsnapshot query` should explain that the URL passed as a positional argument replaces `@url` in the X-SQL query, and that `@url` is the recommended placeholder.",
      "actual": "The help output for `htmlsnapshot query` (and the main help) does not mention the `@url` placeholder or how it relates to the URL argument. This information is only available in the x-sql.md reference file, which a new user may not know to consult.",
      "rootCause": "The `@url` placeholder is a server-side SQL template feature. The CLI help is generated from command definitions that don't include this level of detail about X-SQL placeholder semantics.",
      "codePointer": "",
      "suggestion": "- Add a brief note in `htmlsnapshot query --help` explaining that `@url` in the query is replaced by the URL argument\n- Add a short example in the help showing `SELECT ... FROM DOM_LOAD_AND_SELECT(@url, ...)`\n- Include `@url` documentation in the `--help extract` category output"
    },
    {
      "title": "htmlsnapshot inspect suggested selectors include PowerCSS expressions without explanation of what :expr() selectors are",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 htmlsnapshot inspect",
      "expected": "When `:expr()` selectors appear in the suggested selectors list, there should be a brief explanatory note or a link to the PowerCSS documentation.",
      "actual": "Selectors like `a:expr(img>0)`, `img:expr(img>0)`, `h3:expr(a>0)`, `article:expr(img>0)`, `div:expr(img>0)` appear without any explanation of what the `:expr()` syntax means. A new user encountering these for the first time would not understand that `:expr(img>0)` means \"elements that have at least one descendant <img>\".",
      "rootCause": "The inspect output is auto-generated from CSS selector discovery. PowerCSS `:expr()` selectors are generated when structural features are more discriminative than class/id selectors, but no contextual explanation is included in the output.",
      "codePointer": "",
      "suggestion": "- Add a one-line legend or footnote in the inspect output explaining the `:expr()` syntax\n- Link to the PowerCSS reference in a tip after the inspect output when PowerCSS selectors appear\n- Show the meaning of each `:expr()` selector in a human-readable form (e.g., \"a:expr(img>0) → links containing images\")"
    },
    {
      "title": "Book titles are truncated in X-SQL results (ellipsis in output)",
      "severity": "Low",
      "category": "Product",
      "reproduction": "./b4w.ps1 htmlsnapshot query URL --sql @extract_books.sql",
      "expected": "Full book titles should be returned without truncation.",
      "actual": "Several titles are truncated with \"...\" in the output: e.g., \"A Light in the ...\" (full title: \"A Light in the Attic\"), \"Sapiens: A Brief History ...\" (full: \"Sapiens: A Brief History of Humankind\").",
      "rootCause": "The truncation likely occurs in the DOM text extraction or the H2 database's text column width defaults. The `DOM_FIRST_TEXT` function or the result serialization may apply a character limit. Alternatively, this could be the actual text content rendered on the page (CSS text-overflow: ellipsis).",
      "codePointer": "",
      "suggestion": "- Investigate whether truncation happens at the DOM level (page rendering), text extraction level, or result serialization level\n- If at the extraction level, increase the default text length limit or add a parameter to control it\n- If the page itself truncates with CSS, document this as a known limitation and suggest using `DOM_FIRST_ATTR(DOM, 'a', 'title')` as a workaround for truncated link text"
    },
    {
      "title": "No --help output category for 'query' or 'htmlsnapshot' subcommand group",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "./b4w.ps1 --help query  OR  ./b4w.ps1 htmlsnapshot query --help",
      "expected": "Category-based help should include an 'extract' or 'query' category that covers htmlsnapshot query, and `htmlsnapshot query --help` should show query-specific options like --sql, --sql-stdin, --sql-base64, --result-only.",
      "actual": "The `--help extract` category exists and does mention htmlsnapshot. However, running `htmlsnapshot query --help` only shows generic `htmlsnapshot` help, not query-specific options. The `--sql` and `--result-only` flags are not visible without reading the x-sql.md reference.",
      "rootCause": "`htmlsnapshot query` is a subcommand of `htmlsnapshot`, and the help system may not generate per-subcommand help pages. The `--sql` flag may be defined at the `htmlsnapshot` level but only applicable to the `query` subcommand.",
      "codePointer": "",
      "suggestion": "- Add `htmlsnapshot query --help` support showing query-specific flags (--sql, --sql-stdin, --sql-base64, --result-only)\n- Include a brief X-SQL example in the htmlsnapshot query help output\n- Add a dedicated --help category for 'query' or 'x-sql'"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 8 task steps completed without errors. Navigated to the target site, discovered selectors via htmlsnapshot inspect, extracted 20 books with titles and prices using all four X-SQL input methods (inline, file, stdin, base64), verified --result-only mode, and cleaned up.",
    "successRate": "100% — every step worked on the first attempt. No workarounds required.",
    "issuesFound": 7,
    "majorBlockers": "",
    "mostConfusingAspects": "1. The relationship between the positional URL argument and the @url placeholder in X-SQL queries is documented only in the x-sql.md reference, not in CLI help. A new user running 'htmlsnapshot query' for the first time would need to consult external documentation to understand the syntax. 2. The :expr() PowerCSS selectors in htmlsnapshot inspect output have no inline explanation — a new user sees 'a:expr(img>0)' without knowing what it means. 3. CSS selectors in inline --sql require shell escaping gymnastics that are fragile across platforms.",
    "mostValuableImprovements": "1. Fix the 'Finding browser4 root' diagnostic leaking into stdout — it contaminates --result-only and --json output, making machine parsing unreliable. 2. Add htmlsnapshot query --help with query-specific options (--sql, --sql-stdin, --sql-base64, --result-only) and a quick example. 3. Add inline explanations for PowerCSS :expr() selectors in htmlsnapshot inspect output. 4. Document the @url placeholder in CLI help output. 5. Investigate and fix title truncation in X-SQL text extraction (titles ending with '...').",
    "usabilityRating": 7
  }
}
```

---

## Issues Found (7 issues)

### Issue 1: Diagnostic message leaks into stdout with --result-only when using --sql @file

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot query https://books.toscrape.com/ --sql @.test-sessions/extract_books.sql --result-only

#### Expected Behavior

Only the JSON array of results on stdout, no other text.

#### Actual Behavior

The line "Finding browser4 root from "/home/vincent/workspace/Browser4-4.12"" appears on stdout before the JSON array. This contaminates machine-readable output and would break JSON parsers.

#### Root Cause Analysis

The file-path resolution logic (likely in the CLI's argument parsing for `@file` syntax) prints a diagnostic to stdout before the query executes. The `--result-only` flag appears to filter only the server response envelope, not earlier CLI-level diagnostic output. The file resolution happens before the HTTP request is made, so it's outside the scope of the server-side response filtering.

#### Code Pointer

`cli/browser4-cli/src/ — likely in the argument parsing or SQL file loading path where `@file` paths are resolved and the "Finding browser4 root" message is printed.`

#### AI Suggested Improvement

- Move the "Finding browser4 root" message to stderr instead of stdout
- In `--result-only` mode, suppress all non-result stdout, including CLI-level diagnostics
- Consider printing this diagnostic only in verbose/debug mode, or only when the root cannot be found automatically

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Inline --sql quoting requires escaping single quotes on Linux/bash, risking shell errors

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 htmlsnapshot query URL --sql "SELECT DOM_FIRST_TEXT(DOM, 'h3 a') AS title FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')"

#### Expected Behavior

The command should work without complex escaping, or the documentation should clearly show the exact escaping pattern needed for Linux/bash with single-quoted CSS selectors inside double-quoted SQL.

#### Actual Behavior

The command works but the CLI internally re-escapes the single quotes (visible in the debug output showing `'\''h3 a'\''`). This fragile escaping could easily break with different shells or more complex queries. The SKILL.md warns about this on Windows but the issue affects Linux/bash too.

#### Root Cause Analysis

The `./b4w.ps1` wrapper passes arguments through PowerShell which has its own quoting rules. When the outer SQL uses double quotes and CSS selectors use single quotes, the shell and PowerShell layers interact in ways that require careful escaping. This is inherent to mixing shell quoting with PowerShell argument passing.

#### AI Suggested Improvement

- Add a Linux/bash-specific warning in the shell-quoting reference alongside the existing Windows warning
- Show a concrete Linux/bash example with single-quote escaping in the X-SQL quick-start section
- Recommend `--sql @file` or `--sql-stdin` as the primary methods even on Linux when CSS selectors contain quotes
- Consider adding `--sql-stdin` as the first recommended method in the SKILL.md decision tree

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: htmlsnapshot inspect does not directly suggest CSS selector for title text inside h3 > a

**Severity:** Low
**Category:** UX

#### Reproduction

1. ./b4w.ps1 goto http://books.toscrape.com/ 2. ./b4w.ps1 htmlsnapshot 3. ./b4w.ps1 htmlsnapshot inspect

#### Expected Behavior

The inspect output should suggest a text-extraction selector for the book title, such as "h3 a" or "h3", in the "Suggested selectors" list alongside the price selector `p.price_color`.

#### Actual Behavior

The inspect output shows `h3:expr(a>0)` as a suggested selector (a PowerCSS structural selector), and the sample structure shows the h3 element with text. But unlike `p.price_color` which directly shows sample values ("£51.77" | "£53.74" | "£50.10"), there is no explicit text-extraction selector suggestion like "h3 a" showing sample title values. The user must infer from the sample structure that `h3` contains the title text.

#### Root Cause Analysis

The `htmlsnapshot inspect` text-discovery logic detects text-bearing elements with PowerCSS expression selectors but does not generate explicit text-extraction selector suggestions in the same format as it does for other element types. The sample values are shown in the tree structure but not in the suggested selectors table for non-price-like patterns. Investigation needed to understand why `p.price_color` gets sample values shown but `h3 a` does not.

#### AI Suggested Improvement

- Add text-extraction selector suggestions with sample values for heading/name/title patterns, similar to how price selectors are shown
- Show sample text values in the suggested selectors table for any text-bearing element, not just price-like elements
- Consider a dedicated "Text selectors" section in the inspect output showing the best selector for visible text content

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: The @url placeholder relationship to the positional URL argument is undocumented in CLI help

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 htmlsnapshot query --help

#### Expected Behavior

The help output for `htmlsnapshot query` should explain that the URL passed as a positional argument replaces `@url` in the X-SQL query, and that `@url` is the recommended placeholder.

#### Actual Behavior

The help output for `htmlsnapshot query` (and the main help) does not mention the `@url` placeholder or how it relates to the URL argument. This information is only available in the x-sql.md reference file, which a new user may not know to consult.

#### Root Cause Analysis

The `@url` placeholder is a server-side SQL template feature. The CLI help is generated from command definitions that don't include this level of detail about X-SQL placeholder semantics.

#### AI Suggested Improvement

- Add a brief note in `htmlsnapshot query --help` explaining that `@url` in the query is replaced by the URL argument
- Add a short example in the help showing `SELECT ... FROM DOM_LOAD_AND_SELECT(@url, ...)`
- Include `@url` documentation in the `--help extract` category output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: htmlsnapshot inspect suggested selectors include PowerCSS expressions without explanation of what :expr() selectors are

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 htmlsnapshot inspect

#### Expected Behavior

When `:expr()` selectors appear in the suggested selectors list, there should be a brief explanatory note or a link to the PowerCSS documentation.

#### Actual Behavior

Selectors like `a:expr(img>0)`, `img:expr(img>0)`, `h3:expr(a>0)`, `article:expr(img>0)`, `div:expr(img>0)` appear without any explanation of what the `:expr()` syntax means. A new user encountering these for the first time would not understand that `:expr(img>0)` means "elements that have at least one descendant <img>".

#### Root Cause Analysis

The inspect output is auto-generated from CSS selector discovery. PowerCSS `:expr()` selectors are generated when structural features are more discriminative than class/id selectors, but no contextual explanation is included in the output.

#### AI Suggested Improvement

- Add a one-line legend or footnote in the inspect output explaining the `:expr()` syntax
- Link to the PowerCSS reference in a tip after the inspect output when PowerCSS selectors appear
- Show the meaning of each `:expr()` selector in a human-readable form (e.g., "a:expr(img>0) → links containing images")

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Book titles are truncated in X-SQL results (ellipsis in output)

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot query URL --sql @extract_books.sql

#### Expected Behavior

Full book titles should be returned without truncation.

#### Actual Behavior

Several titles are truncated with "..." in the output: e.g., "A Light in the ..." (full title: "A Light in the Attic"), "Sapiens: A Brief History ..." (full: "Sapiens: A Brief History of Humankind").

#### Root Cause Analysis

The truncation likely occurs in the DOM text extraction or the H2 database's text column width defaults. The `DOM_FIRST_TEXT` function or the result serialization may apply a character limit. Alternatively, this could be the actual text content rendered on the page (CSS text-overflow: ellipsis).

#### AI Suggested Improvement

- Investigate whether truncation happens at the DOM level (page rendering), text extraction level, or result serialization level
- If at the extraction level, increase the default text length limit or add a parameter to control it
- If the page itself truncates with CSS, document this as a known limitation and suggest using `DOM_FIRST_ATTR(DOM, 'a', 'title')` as a workaround for truncated link text

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: No --help output category for 'query' or 'htmlsnapshot' subcommand group

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 --help query  OR  ./b4w.ps1 htmlsnapshot query --help

#### Expected Behavior

Category-based help should include an 'extract' or 'query' category that covers htmlsnapshot query, and `htmlsnapshot query --help` should show query-specific options like --sql, --sql-stdin, --sql-base64, --result-only.

#### Actual Behavior

The `--help extract` category exists and does mention htmlsnapshot. However, running `htmlsnapshot query --help` only shows generic `htmlsnapshot` help, not query-specific options. The `--sql` and `--result-only` flags are not visible without reading the x-sql.md reference.

#### Root Cause Analysis

`htmlsnapshot query` is a subcommand of `htmlsnapshot`, and the help system may not generate per-subcommand help pages. The `--sql` flag may be defined at the `htmlsnapshot` level but only applicable to the `query` subcommand.

#### AI Suggested Improvement

- Add `htmlsnapshot query --help` support showing query-specific flags (--sql, --sql-stdin, --sql-base64, --result-only)
- Include a brief X-SQL example in the htmlsnapshot query help output
- Add a dedicated --help category for 'query' or 'x-sql'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 8 task steps completed without errors. Navigated to the target site, discovered selectors via htmlsnapshot inspect, extracted 20 books with titles and prices using all four X-SQL input methods (inline, file, stdin, base64), verified --result-only mode, and cleaned up.

**Success Rate:** 100% — every step worked on the first attempt. No workarounds required.

**Issues Found:** 7

**Most Confusing Aspects:** 1. The relationship between the positional URL argument and the @url placeholder in X-SQL queries is documented only in the x-sql.md reference, not in CLI help. A new user running 'htmlsnapshot query' for the first time would need to consult external documentation to understand the syntax. 2. The :expr() PowerCSS selectors in htmlsnapshot inspect output have no inline explanation — a new user sees 'a:expr(img>0)' without knowing what it means. 3. CSS selectors in inline --sql require shell escaping gymnastics that are fragile across platforms.

**Most Valuable Improvements:** 1. Fix the 'Finding browser4 root' diagnostic leaking into stdout — it contaminates --result-only and --json output, making machine parsing unreliable. 2. Add htmlsnapshot query --help with query-specific options (--sql, --sql-stdin, --sql-base64, --result-only) and a quick example. 3. Add inline explanations for PowerCSS :expr() selectors in htmlsnapshot inspect output. 4. Document the @url placeholder in CLI help output. 5. Investigate and fix title truncation in X-SQL text extraction (titles ending with '...').

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Diagnostic message leaks into stdout with --result-only when using --sql @file

./b4w.ps1 htmlsnapshot query https://books.toscrape.com/ --sql @.test-sessions/extract_books.sql --result-only

#### Issue 2: Inline --sql quoting requires escaping single quotes on Linux/bash, risking shell errors

./b4w.ps1 htmlsnapshot query URL --sql "SELECT DOM_FIRST_TEXT(DOM, 'h3 a') AS title FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')"

#### Issue 3: htmlsnapshot inspect does not directly suggest CSS selector for title text inside h3 > a

1. ./b4w.ps1 goto http://books.toscrape.com/ 2. ./b4w.ps1 htmlsnapshot 3. ./b4w.ps1 htmlsnapshot inspect

#### Issue 4: The @url placeholder relationship to the positional URL argument is undocumented in CLI help

./b4w.ps1 htmlsnapshot query --help

#### Issue 5: htmlsnapshot inspect suggested selectors include PowerCSS expressions without explanation of what :expr() selectors are

./b4w.ps1 htmlsnapshot inspect

#### Issue 6: Book titles are truncated in X-SQL results (ellipsis in output)

./b4w.ps1 htmlsnapshot query URL --sql @extract_books.sql

#### Issue 7: No --help output category for 'query' or 'htmlsnapshot' subcommand group

./b4w.ps1 --help query  OR  ./b4w.ps1 htmlsnapshot query --help

