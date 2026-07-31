# Issues: swarm-parallel-scraping

> **Source:** `20260730-162640-swarm-parallel-scraping.full.md` | **Date:** 20260730-162640 | **Mode:** dev

## Scenario Background

### Task

The task was completed **successfully**. All 9 steps executed without errors:

| Step | Action | Result |
|------|--------|--------|
| 1 | `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` | Swarm session `SWARM` created |
| 2 | Created seed file with 10 URLs (6 Electronics + 4 Home) | File written to `.test-sessions/seed-urls.txt` |
| 3 | Wrote X-SQL query extracting `#productTitle`, `#product-price`, `#product-image` src | File written to `.test-sessions/extract-products.sql` |
| 4 | `swarm query --sql @file --seed-file --refresh` | 10 X-SQL jobs submitted |
| 5 | `swarm submit <url> --refresh` (no SQL) | 1 plain scrape job submitted |
| 6 | `swarm list` polled after ~10s sleep | All 11 jobs completed |
| 7 | `swarm result <id>` for 4 representative jobs | Structured data extracted correctly for X-SQL jobs; plain submit returned URL-only result |
| 8 | `swarm list` | Full task history with 11 entries, timestamps, and status |
| 9 | `swarm close` | Session closed, resources released |

Extracted data sample:
- **Electronics**: "4K OLED TV 55" / $899.99, "Wireless Noise-Cancelling Headphones" / $199.99
- **Home**: "Vacuum Cleaner Smart" / $159.99, "LED Desk Lamp" / $35.99

### Execution Context

**Key Commands:**

1. `goto "http://localhost:18080/ec/dp/B0E000001"` — navigated to a product page to discover DOM structure
2. `htmlsnapshot` — captured static HTML snapshot
3. `htmlsnapshot inspect` — auto-discovered repeating patterns; found `tr` specs table but not product-card pattern
4. `htmlsnapshot summary` — WPSI summary revealed full page structure: `#product-page` article with `#productTitle`, `#product-price`, `#product-image`
5. `htmlsnapshot get text "#product-page h1"` — confirmed product title selector
6. `htmlsnapshot get html "#product-page"` — read full HTML to identify precise IDs: `#productTitle`, `#product-price`, `#product-image`
7. `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` — created swarm
8. `swarm list --clear` — cleared 13 stale tasks from prior sessions (necessary workaround)
9. `swarm query --sql @.test-sessions/extract-products.sql --seed-file .test-sessions/seed-urls.txt --refresh` — submitted 10 X-SQL jobs
10. `swarm submit "http://localhost:18080/ec/dp/B0E000001" --refresh` — submitted 1 plain scrape job
11. `swarm list` — polled status; all 11 completed
12. `swarm result <id>` × 4 — retrieved and verified results
13. `swarm status <id>` — checked status metadata format
14. `swarm close` — released resources

**Important decisions:**
- Used `htmlsnapshot summary` and `htmlsnapshot get html` rather than `inspect` alone because `inspect` didn't surface the product-card container pattern (it picked up `tr` specs rows instead)
- Cleared stale tasks before submitting new jobs to avoid potential worker-pool interference (as warned by the CLI)
- Used individual argument quoting (`"swarm" "query" "--sql" ...`) per the documented Git Bash workaround

**Workarounds required:**
1. **Git Bash quoting:** Had to quote each dashed argument individually to prevent PowerShell parameter binding from mangling them. Without quoting, arguments like `--sql` and `--seed-file` can be silently consumed by PowerShell.
2. **Stale task cleanup:** Had to run `swarm list --clear` after `swarm create` to remove 13 stale tasks from prior sessions. Without this, new jobs could get stuck in "queued" state.
3. **Selector discovery iteration:** `htmlsnapshot inspect` didn't directly surface the product selectors — had to use `summary` and `get html` to read the actual DOM structure and find `#productTitle`, `#product-price`, `#product-image`.

---

```json
{
  "issues": [
    {
      "title": "Stale task cleanup requires three-command recovery flow",
      "severity": "Low",
      "category": "UX",
      "reproduction": "1. Run swarm create after a previous swarm session left tasks.\n2. Observe: 'Note: 13 swarm task(s) from prior sessions are still tracked.'\n3. Must run: swarm list --clear, swarm close, swarm create again.",
      "expected": "A single command or flag should handle stale task cleanup. For example, `swarm create --clear-stale` that clears stale tasks and recreates in one step, or an interactive prompt offering to clear.",
      "actual": "The user must read the warning, then separately run `swarm list --clear`, potentially close and recreate the swarm session. The swarm create itself succeeds but the stale tasks may interfere with new jobs.",
      "rootCause": "The swarm task tracker persists task records across sessions. `swarm create` detects staleness and warns, but offers no automation — the user must know the correct sequence (clear then recreate) from documentation.",
      "codePointer": "cli/browser4-cli/src/commands/swarm.rs (create handler — could accept --clear-stale flag)",
      "suggestion": "- Add `--clear-stale` flag to `swarm create` that automatically clears stale tasks before creating the new session\n- Or offer an interactive prompt: 'Clear 13 stale tasks? [y/N]' when stale tasks are detected\n- At minimum, the warning message should include the exact commands to run (it already does this reasonably well)"
    },
    {
      "title": "Git Bash argument quoting required for dashed flags on Windows",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "On Windows Git Bash, run: `./b4w.ps1 swarm query --sql @query.sql --seed-file urls.txt --refresh`\nWithout individual quoting, arguments like `--sql` may be silently consumed by PowerShell's parameter binder, leading to confusing errors or silent failures.",
      "expected": "Arguments should pass through transparently without manual quoting. At minimum, if quoting is required, the error message should clearly explain what went wrong and how to fix it.",
      "actual": "All dashed arguments must be individually quoted: `./b4w.ps1 \"swarm\" \"query\" \"--sql\" \"@query.sql\" \"--seed-file\" \"urls.txt\" \"--refresh\"`. The documentation mentions this (swarm.md line 180, SKILL.md shell selection guide), but if a new user hits it without reading the docs, there's no actionable error message.",
      "rootCause": "The `b4w.ps1` PowerShell wrapper uses manual `$args` parsing, but when called from bash via `pwsh`, PowerShell's parameter binder can still intercept dash-prefixed arguments before they reach the script's argument parser. The `b4w.sh` wrapper mitigates this by individually quoting each argument.",
      "codePointer": "b4w.sh wrapper (should be the default recommendation for Git Bash users)",
      "suggestion": "- Make `b4w.sh` the default recommendation for Git Bash users in the help output and SKILL.md quick-start section\n- Add an auto-detect in `b4w.ps1` that checks if it's being called from bash and warns with the correct invocation\n- Consider a `b4w` alias/symlink that auto-selects the right wrapper per shell"
    },
    {
      "title": "swarm query resultSet does not include the source URL",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "Run `swarm result <id>` for an X-SQL query job. The `resultSet` contains `{title, price, image_url}` but no `url` field identifying which page the data came from.",
      "expected": "Each row in `resultSet` should include the source URL so results are self-contained and don't require cross-referencing with `swarm list` or memorizing task IDs.",
      "actual": "X-SQL query results: `{\"resultSet\": [{\"title\": \"...\", \"price\": \"...\", \"image_url\": \"...\"}]}`. The URL is not present in the result rows. Compare with `swarm submit` results which DO include the URL: `{\"resultSet\": [{\"url\": \"...\"}]}`.",
      "rootCause": "The X-SQL query output reflects only the columns explicitly selected in the SQL. The source URL is available to the backend (it was passed to `DOM_LOAD_AND_SELECT(@url, ...)`) but is not automatically injected into the result rows. The `@url` placeholder is resolved during query execution but not added as an output column.",
      "codePointer": "browser4-rest/ (backend result assembly — could inject source URL into each result row automatically, or document that users should SELECT DOM_BASE_URI(DOM) AS url)",
      "suggestion": "- Automatically include the source URL in each result row as a `_url` or `source_url` field (similar to how `swarm submit` includes `url`)\n- Alternatively, document the `DOM_BASE_URI(DOM)` or `DOM_LOCATION(DOM)` functions as a way to include the URL in results\n- Add a note in the swarm.md documentation about this difference between `swarm query` and `swarm submit` results"
    },
    {
      "title": "swarm submit documentation says resultSet is 'empty' but it contains a URL row",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run `swarm submit <url>` without `--sql`, then check `swarm result <id>`. The documentation says 'the resultSet will be empty'.",
      "expected": "Documentation should accurately describe the output: the resultSet contains one row with the URL field, not an empty array.",
      "actual": "Actual output: `{\"resultSet\": [{\"url\": \"http://...\"}]}`. The docs (swarm.md line 74) say: 'Without --sql, swarm submit only fetches and loads the page — no data is extracted. The resultSet will be empty.' This is technically incorrect — the resultSet is not empty; it has a URL-only row.",
      "rootCause": "The documentation was likely written before the URL-injection behavior was added, or 'empty' was meant colloquially ('empty of extracted data') rather than literally. The discrepancy between documented and actual behavior creates confusion.",
      "codePointer": "skills/browser4-cli/references/swarm.md line 74",
      "suggestion": "- Update swarm.md line 74 to say: 'The resultSet will contain only a URL field with no extracted data columns'\n- Or add a comparison table showing the difference between swarm submit and swarm query result formats"
    },
    {
      "title": "htmlsnapshot inspect did not surface product-card container pattern",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "On a MockSite product detail page, run `htmlsnapshot inspect`. The auto-discovery picks up `tr` (specs table) as the primary repeating pattern and suggests `th`, `td`, `tr` selectors — not the product title/price/image selectors the user likely wants.",
      "expected": "`htmlsnapshot inspect` should identify product-related patterns (title, price, image) as high-priority suggestions, especially on e-commerce pages.",
      "actual": "The auto-discovered pattern was the specs table (`tr` elements). The product title (`#productTitle`), price (`#product-price`), and image (`#product-image`) were not surfaced as a pattern. The `summary` command did identify these as key content nodes.",
      "rootCause": "`inspect` looks for sibling-repeating patterns (like product cards in a grid). On a product detail page, the specs table rows are the most prominent repeating sibling group. Single-instance elements like `#productTitle` aren't 'repeating patterns' so they don't fit the inspect heuristic.",
      "codePointer": "browser4-core/ (inspect heuristic — could prioritize ID-bearing elements or suggest summary as fallback)",
      "suggestion": "- Add a note to `inspect` output: 'For single product pages, try `htmlsnapshot summary` to identify key content nodes'\n- Consider enhancing `inspect` to also report non-repeating but semantically significant elements (elements with IDs, h1-h6, elements with price-like text patterns)\n- The `summary` command already works well for this case — cross-reference it more prominently in inspect output"
    },
    {
      "title": "Swarm workflow discoverability from help output is limited",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "Run `./b4w.ps1 help`. The swarm commands appear in a compact '[Swarm]' section. A new user may not realize the parallel scraping capability exists without reading SKILL.md or running `./b4w.ps1 help swarm`.",
      "expected": "The main help output should highlight the swarm workflow as a first-class feature, similar to how 'Common workflows' shows navigate→snapshot→click→snapshot and bulk crawl.",
      "actual": "The 'Common workflows' section at the top of help shows single-page navigation, data extraction, form interaction, JavaScript eval, and bulk crawl — but not swarm. Swarm commands are buried in the alphabetical command list under '[Swarm]'.",
      "rootCause": "The help output's 'Common workflows' section prioritizes the most common single-page workflows. Swarm is categorized as an advanced/bulk feature alongside crawl and loop, which appear in their own sections but aren't featured in the top workflow summary.",
      "codePointer": "cli/browser4-cli/src/help.rs or equivalent help text generation",
      "suggestion": "- Add a 'Parallel extraction' entry to the 'Common workflows' section: 'swarm create → swarm query --sql @q.sql --seed-file urls.txt --refresh → swarm result <id>'\n- Or add cross-references at the bottom of each section: 'For bulk/parallel work, see also: crawl, swarm, loop'"
    },
    {
      "title": "No swarm close confirmation or resource summary on close",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run `swarm close`. Output is just 'Session closed. Browser terminated.' with no summary of what was released or whether any tasks remain tracked.",
      "expected": "Closing the swarm should summarize what was released (N browser contexts, M open tabs) and note whether tracked tasks persist (for post-close auditing).",
      "actual": "Output: 'Session closed. Browser terminated.' No information about contexts closed, tabs released, or whether tracked task history is preserved.",
      "rootCause": "The close handler sends the session termination command but doesn't report back resource counts or task persistence status.",
      "codePointer": "cli/browser4-cli/src/commands/swarm.rs (close handler)",
      "suggestion": "- Report number of browser contexts and tabs released on close\n- Note: '11 tracked task(s) retained for history. Use swarm list --clear to remove.'\n- This would give users confidence that resources are actually freed and that task history is preserved"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — All 9 task steps completed without errors. Swarm session created with specified options, 10 X-SQL extraction jobs and 1 plain scrape job submitted and completed, results retrieved and verified, session closed cleanly.",
    "successRate": "100% — every step of the task succeeded on the first attempt with only minor workarounds (Git Bash quoting, stale task cleanup).",
    "issuesFound": 7,
    "majorBlockers": "None. The task was completable without any blocking issues. The two workarounds (Git Bash quoting and stale task cleanup) were minor friction points, not blockers.",
    "mostConfusingAspects": "1. Git Bash argument quoting: Required individually quoting dashed arguments when using b4w.ps1 from bash, which is not obvious to first-time users. The documentation covers this but the error when you get it wrong is not actionable.\n2. Selector discovery for product detail pages: htmlsnapshot inspect surfaced specs table rows as the repeating pattern, not the product title/price/image. Required using summary + get html to find the right IDs.\n3. Stale task warning at swarm create: The warning is clear about the problem but requires a 2-3 step manual recovery flow (clear, optionally close, recreate) instead of a one-click fix.",
    "mostValuableImprovements": "1. Auto-include source URL in swarm query resultSet rows — this would make results self-contained and eliminate the need to cross-reference task IDs.\n2. Add --clear-stale flag to swarm create — reduces a 3-step recovery flow to one command.\n3. Improve swarm workflow visibility in main help output — the parallel scraping capability is powerful but hidden in the alphabetical command list.\n4. Add a Git Bash usage hint to the help output or first-run experience — many Windows users will hit the quoting issue.",
    "usabilityRating": 7
  }
}
```

---

## Issues Found (7 issues)

### Issue 1: Git Bash argument quoting required for dashed flags on Windows

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

On Windows Git Bash, run: `./b4w.ps1 swarm query --sql @query.sql --seed-file urls.txt --refresh`
Without individual quoting, arguments like `--sql` may be silently consumed by PowerShell's parameter binder, leading to confusing errors or silent failures.

#### Expected Behavior

Arguments should pass through transparently without manual quoting. At minimum, if quoting is required, the error message should clearly explain what went wrong and how to fix it.

#### Actual Behavior

All dashed arguments must be individually quoted: `./b4w.ps1 "swarm" "query" "--sql" "@query.sql" "--seed-file" "urls.txt" "--refresh"`. The documentation mentions this (swarm.md line 180, SKILL.md shell selection guide), but if a new user hits it without reading the docs, there's no actionable error message.

#### Root Cause Analysis

The `b4w.ps1` PowerShell wrapper uses manual `$args` parsing, but when called from bash via `pwsh`, PowerShell's parameter binder can still intercept dash-prefixed arguments before they reach the script's argument parser. The `b4w.sh` wrapper mitigates this by individually quoting each argument.

#### Code Pointer

`b4w.sh wrapper (should be the default recommendation for Git Bash users)`

#### AI Suggested Improvement

- Make `b4w.sh` the default recommendation for Git Bash users in the help output and SKILL.md quick-start section
- Add an auto-detect in `b4w.ps1` that checks if it's being called from bash and warns with the correct invocation
- Consider a `b4w` alias/symlink that auto-selects the right wrapper per shell

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: swarm query resultSet does not include the source URL

**Severity:** Medium
**Category:** Product

#### Reproduction

Run `swarm result <id>` for an X-SQL query job. The `resultSet` contains `{title, price, image_url}` but no `url` field identifying which page the data came from.

#### Expected Behavior

Each row in `resultSet` should include the source URL so results are self-contained and don't require cross-referencing with `swarm list` or memorizing task IDs.

#### Actual Behavior

X-SQL query results: `{"resultSet": [{"title": "...", "price": "...", "image_url": "..."}]}`. The URL is not present in the result rows. Compare with `swarm submit` results which DO include the URL: `{"resultSet": [{"url": "..."}]}`.

#### Root Cause Analysis

The X-SQL query output reflects only the columns explicitly selected in the SQL. The source URL is available to the backend (it was passed to `DOM_LOAD_AND_SELECT(@url, ...)`) but is not automatically injected into the result rows. The `@url` placeholder is resolved during query execution but not added as an output column.

#### Code Pointer

`browser4-rest/ (backend result assembly — could inject source URL into each result row automatically, or document that users should SELECT DOM_BASE_URI(DOM) AS url)`

#### AI Suggested Improvement

- Automatically include the source URL in each result row as a `_url` or `source_url` field (similar to how `swarm submit` includes `url`)
- Alternatively, document the `DOM_BASE_URI(DOM)` or `DOM_LOCATION(DOM)` functions as a way to include the URL in results
- Add a note in the swarm.md documentation about this difference between `swarm query` and `swarm submit` results

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
document the `DOM_BASE_URI(DOM)` or `DOM_LOCATION(DOM)` functions as a way to include the URL in results

---

### Issue 3: Stale task cleanup requires three-command recovery flow

**Severity:** Low
**Category:** UX

#### Reproduction

1. Run swarm create after a previous swarm session left tasks.
2. Observe: 'Note: 13 swarm task(s) from prior sessions are still tracked.'
3. Must run: swarm list --clear, swarm close, swarm create again.

#### Expected Behavior

A single command or flag should handle stale task cleanup. For example, `swarm create --clear-stale` that clears stale tasks and recreates in one step, or an interactive prompt offering to clear.

#### Actual Behavior

The user must read the warning, then separately run `swarm list --clear`, potentially close and recreate the swarm session. The swarm create itself succeeds but the stale tasks may interfere with new jobs.

#### Root Cause Analysis

The swarm task tracker persists task records across sessions. `swarm create` detects staleness and warns, but offers no automation — the user must know the correct sequence (clear then recreate) from documentation.

#### Code Pointer

`cli/browser4-cli/src/commands/swarm.rs (create handler — could accept --clear-stale flag)`

#### AI Suggested Improvement

- Add `--clear-stale` flag to `swarm create` that automatically clears stale tasks before creating the new session
- Or offer an interactive prompt: 'Clear 13 stale tasks? [y/N]' when stale tasks are detected
- At minimum, the warning message should include the exact commands to run (it already does this reasonably well)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Add `--clear-stale` flag to `swarm create`

---

### Issue 4: swarm submit documentation says resultSet is 'empty' but it contains a URL row

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `swarm submit <url>` without `--sql`, then check `swarm result <id>`. The documentation says 'the resultSet will be empty'.

#### Expected Behavior

Documentation should accurately describe the output: the resultSet contains one row with the URL field, not an empty array.

#### Actual Behavior

Actual output: `{"resultSet": [{"url": "http://..."}]}`. The docs (swarm.md line 74) say: 'Without --sql, swarm submit only fetches and loads the page — no data is extracted. The resultSet will be empty.' This is technically incorrect — the resultSet is not empty; it has a URL-only row.

#### Root Cause Analysis

The documentation was likely written before the URL-injection behavior was added, or 'empty' was meant colloquially ('empty of extracted data') rather than literally. The discrepancy between documented and actual behavior creates confusion.

#### Code Pointer

`skills/browser4-cli/references/swarm.md line 74`

#### AI Suggested Improvement

- Update swarm.md line 74 to say: 'The resultSet will contain only a URL field with no extracted data columns'
- Or add a comparison table showing the difference between swarm submit and swarm query result formats

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: htmlsnapshot inspect did not surface product-card container pattern

**Severity:** Low
**Category:** Discoverability

#### Reproduction

On a MockSite product detail page, run `htmlsnapshot inspect`. The auto-discovery picks up `tr` (specs table) as the primary repeating pattern and suggests `th`, `td`, `tr` selectors — not the product title/price/image selectors the user likely wants.

#### Expected Behavior

`htmlsnapshot inspect` should identify product-related patterns (title, price, image) as high-priority suggestions, especially on e-commerce pages.

#### Actual Behavior

The auto-discovered pattern was the specs table (`tr` elements). The product title (`#productTitle`), price (`#product-price`), and image (`#product-image`) were not surfaced as a pattern. The `summary` command did identify these as key content nodes.

#### Root Cause Analysis

`inspect` looks for sibling-repeating patterns (like product cards in a grid). On a product detail page, the specs table rows are the most prominent repeating sibling group. Single-instance elements like `#productTitle` aren't 'repeating patterns' so they don't fit the inspect heuristic.

#### Code Pointer

`browser4-core/ (inspect heuristic — could prioritize ID-bearing elements or suggest summary as fallback)`

#### AI Suggested Improvement

- Add a note to `inspect` output: 'For single product pages, try `htmlsnapshot summary` to identify key content nodes'
- Consider enhancing `inspect` to also report non-repeating but semantically significant elements (elements with IDs, h1-h6, elements with price-like text patterns)
- The `summary` command already works well for this case — cross-reference it more prominently in inspect output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Swarm workflow discoverability from help output is limited

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `./b4w.ps1 help`. The swarm commands appear in a compact '[Swarm]' section. A new user may not realize the parallel scraping capability exists without reading SKILL.md or running `./b4w.ps1 help swarm`.

#### Expected Behavior

The main help output should highlight the swarm workflow as a first-class feature, similar to how 'Common workflows' shows navigate→snapshot→click→snapshot and bulk crawl.

#### Actual Behavior

The 'Common workflows' section at the top of help shows single-page navigation, data extraction, form interaction, JavaScript eval, and bulk crawl — but not swarm. Swarm commands are buried in the alphabetical command list under '[Swarm]'.

#### Root Cause Analysis

The help output's 'Common workflows' section prioritizes the most common single-page workflows. Swarm is categorized as an advanced/bulk feature alongside crawl and loop, which appear in their own sections but aren't featured in the top workflow summary.

#### Code Pointer

`cli/browser4-cli/src/help.rs or equivalent help text generation`

#### AI Suggested Improvement

- Add a 'Parallel extraction' entry to the 'Common workflows' section: 'swarm create → swarm query --sql @q.sql --seed-file urls.txt --refresh → swarm result <id>'
- Or add cross-references at the bottom of each section: 'For bulk/parallel work, see also: crawl, swarm, loop'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: No swarm close confirmation or resource summary on close

**Severity:** Low
**Category:** UX

#### Reproduction

Run `swarm close`. Output is just 'Session closed. Browser terminated.' with no summary of what was released or whether any tasks remain tracked.

#### Expected Behavior

Closing the swarm should summarize what was released (N browser contexts, M open tabs) and note whether tracked tasks persist (for post-close auditing).

#### Actual Behavior

Output: 'Session closed. Browser terminated.' No information about contexts closed, tabs released, or whether tracked task history is preserved.

#### Root Cause Analysis

The close handler sends the session termination command but doesn't report back resource counts or task persistence status.

#### Code Pointer

`cli/browser4-cli/src/commands/swarm.rs (close handler)`

#### AI Suggested Improvement

- Report number of browser contexts and tabs released on close
- Note: '11 tracked task(s) retained for history. Use swarm list --clear to remove.'
- This would give users confidence that resources are actually freed and that task history is preserved

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

**Completion Status:** Successful — All 9 task steps completed without errors. Swarm session created with specified options, 10 X-SQL extraction jobs and 1 plain scrape job submitted and completed, results retrieved and verified, session closed cleanly.

**Success Rate:** 100% — every step of the task succeeded on the first attempt with only minor workarounds (Git Bash quoting, stale task cleanup).

**Issues Found:** 7

**Major Blockers:** None. The task was completable without any blocking issues. The two workarounds (Git Bash quoting and stale task cleanup) were minor friction points, not blockers.

**Most Confusing Aspects:** 1. Git Bash argument quoting: Required individually quoting dashed arguments when using b4w.ps1 from bash, which is not obvious to first-time users. The documentation covers this but the error when you get it wrong is not actionable.
2. Selector discovery for product detail pages: htmlsnapshot inspect surfaced specs table rows as the repeating pattern, not the product title/price/image. Required using summary + get html to find the right IDs.
3. Stale task warning at swarm create: The warning is clear about the problem but requires a 2-3 step manual recovery flow (clear, optionally close, recreate) instead of a one-click fix.

**Most Valuable Improvements:** 1. Auto-include source URL in swarm query resultSet rows — this would make results self-contained and eliminate the need to cross-reference task IDs.
2. Add --clear-stale flag to swarm create — reduces a 3-step recovery flow to one command.
3. Improve swarm workflow visibility in main help output — the parallel scraping capability is powerful but hidden in the alphabetical command list.
4. Add a Git Bash usage hint to the help output or first-run experience — many Windows users will hit the quoting issue.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Git Bash argument quoting required for dashed flags on Windows

On Windows Git Bash, run: `./b4w.ps1 swarm query --sql @query.sql --seed-file urls.txt --refresh`
Without individual quoting, arguments like `--sql` may be silently consumed by PowerShell's parameter binder, leading to confusing errors or silent failures.

#### Issue 2: swarm query resultSet does not include the source URL

Run `swarm result <id>` for an X-SQL query job. The `resultSet` contains `{title, price, image_url}` but no `url` field identifying which page the data came from.

#### Issue 3: Stale task cleanup requires three-command recovery flow

1. Run swarm create after a previous swarm session left tasks.
2. Observe: 'Note: 13 swarm task(s) from prior sessions are still tracked.'
3. Must run: swarm list --clear, swarm close, swarm create again.

#### Issue 4: swarm submit documentation says resultSet is 'empty' but it contains a URL row

Run `swarm submit <url>` without `--sql`, then check `swarm result <id>`. The documentation says 'the resultSet will be empty'.

#### Issue 5: htmlsnapshot inspect did not surface product-card container pattern

On a MockSite product detail page, run `htmlsnapshot inspect`. The auto-discovery picks up `tr` (specs table) as the primary repeating pattern and suggests `th`, `td`, `tr` selectors — not the product title/price/image selectors the user likely wants.

#### Issue 6: Swarm workflow discoverability from help output is limited

Run `./b4w.ps1 help`. The swarm commands appear in a compact '[Swarm]' section. A new user may not realize the parallel scraping capability exists without reading SKILL.md or running `./b4w.ps1 help swarm`.

#### Issue 7: No swarm close confirmation or resource summary on close

Run `swarm close`. Output is just 'Session closed. Browser terminated.' with no summary of what was released or whether any tasks remain tracked.

