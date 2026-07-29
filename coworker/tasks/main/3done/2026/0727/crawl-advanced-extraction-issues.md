# Issues: crawl-advanced-extraction

> **Source:** `20260727-061542-crawl-advanced-extraction.full.md` | **Date:** 20260727-061542 | **Mode:** dev

## Scenario Background

### Task

**Task partially completed.** The crawl commands were all executed successfully from the CLI perspective (no crashes), but the results were incorrect:

- All crawls only processed **1 of 3 seed URLs** — the other 2 were silently dropped
- X-SQL data extraction returned **"No extracted data"** for all attempts
- The `--background` crawl completed but its result was lost after `crawl clear`
- The `--ignore-url-query`, `--no-norm`, and `--readonly` variants all executed but exhibited the same 1-page-only behavior

No extracted product data (title + price) was obtained from any crawl run.

### Execution Context

| Step | Command | Outcome |
|------|---------|---------|
| 0 | `pwd` → repo root | Confirmed working directory |
| 1 | `./b4w.ps1 help` | Help output loaded successfully |
| 2 | Read `SKILL.md` + `crawl.md` + `x-sql.md` | Documentation studied |
| Prep | `./bin/test.ps1 mock-site` | MockSite started on :18080 |
| Prep | Created `eval-seed-urls.txt` (3 URLs) + `eval-extract.sql` | Files created |
| 3 | `crawl --seed-file ... --depth 0 --sql @... --refresh --parse --expires 1h --priority 1 --page-load-timeout 30s` | 1 page found, "No extracted data" |
| Debug | `goto` → `snapshot -v 0 --stdout` | **Failed** — PowerShell intercepted `-v` flag |
| Debug | `pwsh -Command "& './b4w.ps1' 'snapshot' '-v' '0' '--stdout'"` | **Workaround found** — manual pwsh quoting |
| Debug | `htmlsnapshot` → `i...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: Crawl silently drops URLs — only first seed URL is processed

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```bash
# Create seed file with 3 distinct URLs:
echo "http://localhost:18080/ec/dp/B0E000001" > urls.txt
echo "http://localhost:18080/ec/dp/B0E000002" >> urls.txt
echo "http://localhost:18080/ec/dp/B0E000003" >> urls.txt
./b4w.ps1 crawl --seed-file urls.txt --depth 0
```

#### Expected Behavior

All 3 distinct product pages are fetched, yielding 3 pages found.

#### Actual Behavior

Only 1 page found (always the first URL). The other 2 URLs are silently dropped with no error, warning, or indication of failure. The output says "Crawl completed" with status "OK."

#### Root Cause Analysis

Likely a URL deduplication or normalization bug in the crawl backend that incorrectly treats distinct URLs (differing only in the final path segment `/dp/B0E00000{1,2,3}`) as duplicates. The `crawl.md` docs state: "Visited URLs are normalized: lowercase, trailing slash removed, query string always stripped for dedup purposes." The MockSite HTML includes `<link rel="normalizedURI" href="http://localhost:18080/ec/dp/B0E000001">` which may be interfering. Alternatively, the seed file parser may have a bug that only reads the first non-comment line.

#### Code Pointer

`Backend crawl task processing — likely in `browser4-rest` crawl service or `PulsarWebDriver` URL normalization/dedup logic.`

#### AI Suggested Improvement

- Log a warning for each seed URL that was skipped during deduplication, including the reason (e.g., "Skipping URL X: normalized to same key as URL Y")
- Add a `--verbose` flag to crawl that shows per-URL processing status (fetched/skipped/error)
- Verify seed file parsing reads all lines correctly with a unit test for multi-line seed files
- Check if the `<link rel="normalizedURI">` meta tag in MockSite HTML is causing all product pages to resolve to the same normalized URL

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
take all sugguestions. investigate the backend server, ensure /dp/B0E00000{1,2,3} are normalized to different urls, make sure backend server refresh `<link rel="normalizedURI">` before the page source saving to the storage, or htmlsnapshot calls.

---

### Issue 2: X-SQL extraction returns "No extracted data" with valid query

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
# Create valid X-SQL query file:
cat > query.sql << 'EOF'
SELECT
    DOM_BASE_URI(DOM) AS url,
    DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
    DOM_FIRST_TEXT(DOM, '#product-price') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'body')
EOF
./b4w.ps1 crawl --seed-file urls.txt --depth 0 --sql @query.sql --refresh
```

#### Expected Behavior

Structured data extracted: 3 rows with url, title, and price columns.

#### Actual Behavior

"No extracted data." No error message, no indication of which part failed (query parsing, page loading, selector matching). The `htmlsnapshot query` command also returns `resultSet: null`.

#### Root Cause Analysis

Possibly related to Issue 1 (only 1 URL processed, and that page may fail SQL extraction for a different reason). Even if the crawl processes the page, the X-SQL execution may fail silently. The `htmlsnapshot query` test returned `pageStatusCode: 1600` and `resultSet: null` — status code 1600 is non-standard and may indicate a backend error during query execution against the DOM.

#### Code Pointer

`Backend X-SQL query execution in `browser4-rest` — the `DOM_LOAD_AND_SELECT` function handler or the scrape query pipeline.`

#### AI Suggested Improvement

- Return per-page error details in crawl results when X-SQL extraction fails (e.g., "Page X: query returned 0 rows" or "Page X: selector '#productTitle' matched 0 elements")
- Log the actual X-SQL query that was executed (with `@url` resolved) for debugging
- Add a `--dry-run` or `--validate-sql` flag to test queries before running a full crawl
- Investigate `pageStatusCode: 1600` — document what this code means and whether it indicates an error condition

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: PowerShell flag interception breaks `./b4w.ps1` with short flags

**Severity:** High
**Category:** UX / Documentation

#### Reproduction

```bash
# In Git Bash:
./b4w.ps1 snapshot -v 0 --stdout
```

#### Expected Behavior

Snapshot captured with viewport 0, printed to stdout.

#### Actual Behavior

- First attempt: `Error: Unknown command: 'snapshot-0'. Did you mean: 'snapshot'?` — PowerShell concatenated `snapshot -v 0` into `snapshot-0`
- Attempt with `--` separator: `./b4w.ps1 -- snapshot -v 0 --stdout` → PowerShell error: "Parameter cannot be processed because the parameter name '' is ambiguous"

#### Root Cause Analysis

PowerShell's parameter binder intercepts `-v` (matching `-Verbose`) and `-i` (matching `-InformationAction`) before they reach the script's `$RemainingArgs`. The documented `--` separator workaround from SKILL.md doesn't work when invoking `./b4w.ps1` from Git Bash because PowerShell processes the `--` as a parameter name rather than a separator. The effective workaround (`pwsh -Command "& './b4w.ps1' 'snapshot' '-v' '0' '--stdout'"`) requires individually quoting every argument — not documented in SKILL.md.

#### Code Pointer

``b4w.ps1` — the `param()` block and `--` passthrough logic at line 48.`

#### AI Suggested Improvement

- Fix the `b4w.ps1` script to properly handle `--` as a stop-parsing token (use `--%` or restructure param block)
- Provide a `b4w.sh` wrapper that actually works in Git Bash (current version fails with "The term '/d/workspace/.../b4w.ps1' is not recognized")
- Add a dedicated section to SKILL.md covering Git Bash invocation patterns with concrete, tested examples
- Consider providing a `b4w` alias/symlink that handles the PowerShell wrapping transparently
- Add a pre-flight check that detects when flags are being intercepted and prints a helpful diagnostic

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `crawl clear` removes task data but leaves orphaned list entries showing "not found"

**Severity:** Medium
**Category:** Reliability / UX

#### Reproduction

```bash
./b4w.ps1 crawl --seed-file urls.txt --depth 0    # task completes
./b4w.ps1 crawl clear                               # clears 58 tasks
./b4w.ps1 crawl list                                # recent tasks show "not found"
./b4w.ps1 crawl result <task-id>                    # "Task not found"
```

#### Expected Behavior

After `crawl clear`, all cleared tasks are removed from `crawl list`. Task results should remain accessible for non-cleared tasks.

#### Actual Behavior

`crawl clear` removed 58 tasks but left 73 tasks in the list. Tasks that were completed seconds before clearing now show "not found" status and return "Task not found" when queried. The task TTL is 60 minutes, so results should still be available.

#### Root Cause Analysis

The task metadata store and task result store appear to have different lifecycles. `crawl clear` may be cleaning the result data without cleaning the tracking metadata, or the result data is stored in a short-lived cache separate from the task list.

#### Code Pointer

`Backend crawl task management — task store cleanup logic in `browser4-rest`.`

#### AI Suggested Improvement

- Ensure `crawl clear` atomically removes both the task metadata AND the result data
- Add a `--task-ttl` flag to `crawl` so users can control how long results persist
- In `crawl list`, show a "Result available" column so users know whether result data can still be retrieved
- Display a warning when `crawl clear` is about to remove recently-completed tasks
- Add a `crawl result --all` option to bulk-export results before clearing

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: CSS selector discovery requires manual HTML inspection — no guided workflow

**Severity:** Medium
**Category:** Discoverability / UX

#### Reproduction

1. Follow the crawl.md examples using `h1` and `.price` as selectors
2. Get "No extracted data" with no hint about selector mismatch

#### Expected Behavior

Documentation examples should either use selectors that match the actual page, or the tool should provide a guided selector-discovery workflow before running extraction.

#### Actual Behavior

The SKILL.md example uses `.title` and `.price` — generic selectors that don't match MockSite's `#productTitle` and `#product-price`. The user must independently discover `htmlsnapshot export` + manual HTML inspection to find the correct IDs. `htmlsnapshot inspect` only found `tr` patterns (specs table), missing the title and price elements entirely.

#### Root Cause Analysis

`htmlsnapshot inspect` auto-discovery heuristics prioritize repeating patterns (like table rows) over singleton elements (like the single product title). The documentation examples use hypothetical CSS classes that may not exist on real pages.

#### Code Pointer

``htmlsnapshot inspect` — pattern detection heuristics in the backend; SKILL.md and crawl.md — documentation examples.`

#### AI Suggested Improvement

- Extend `htmlsnapshot inspect` to also report singleton elements with semantic meaning: headings, elements with `id` attributes, elements with price-like text patterns
- Add a `--discover` flag to `crawl` that previews available selectors on the first seed URL before running the full crawl
- Update SKILL.md examples to use ID-based selectors as the primary recommendation (more stable than class-based)
- Add a `htmlsnapshot suggest` command that recommends selectors for common extraction patterns (title, price, image, description)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: SKILL.md `--` passthrough example doesn't work in Git Bash

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Following SKILL.md: "When using `b4w.ps1` directly, pass flags after `--` (e.g. `./b4w.ps1 -- snapshot -i`)"

```bash
./b4w.ps1 -- snapshot -v 0 --stdout
```

#### Expected Behavior

Snapshot captured successfully.

#### Actual Behavior

`Parameter cannot be processed because the parameter name '' is ambiguous.`

#### Root Cause Analysis

The SKILL.md documents the `--` separator pattern but this only works in native PowerShell, not when `b4w.ps1` is invoked from Git Bash. The Git Bash → PowerShell boundary adds an extra parsing layer that breaks the separator.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — the "PowerShell wrapper tip" section around line 359.`

#### AI Suggested Improvement

- Test and document the working Git Bash invocation pattern: `pwsh -Command "& './b4w.ps1' 'snapshot' '-v' '0' '--stdout'"`
- Add platform-specific invocation sections: "From PowerShell", "From Git Bash", "From Command Prompt"
- Create a `b4w` shell script wrapper that handles the PowerShell invocation correctly for Git Bash users
- Add a `b4w` shell function example users can add to `.bashrc`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: `crawl list` shows overwhelming number of historical tasks with no filtering

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
./b4w.ps1 crawl list   # Shows 97 tracked tasks spanning weeks
```

#### Expected Behavior

A manageable list with sensible defaults (recent tasks first, paginated, with filtering options).

#### Actual Behavior

97 tasks displayed, many from weeks ago with "not found" status. The `--limit N` hint is at the bottom. No way to filter by date, status, or URL pattern from the CLI.

#### Root Cause Analysis

The crawl task store accumulates tasks indefinitely. No automatic cleanup of old/stale tasks. No server-side filtering options exposed to the CLI.

#### Code Pointer

``browser4-cli` crawl list command; backend task store.`

#### AI Suggested Improvement

- Default `crawl list` to showing the most recent 20 tasks
- Add `--status` filter (e.g., `--status completed`, `--status running`)
- Add `--since` filter (e.g., `--since 1h`, `--since 1d`)
- Auto-clean tasks older than the TTL (60 min default) from the list display
- Make `crawl clear` more aggressive by default or add a `--force` flag to clear all terminal tasks without confirmation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: `crawl list` DESCRIPTION column only shows first URL, not seed file or full command

**Severity:** Low
**Category:** UX

#### Reproduction

Run multiple crawls with different seed files and options. Then `crawl list`.

#### Expected Behavior

The DESCRIPTION column distinguishes crawl runs by seed file name, SQL query file, or key options.

#### Actual Behavior

All crawls show "http://localhost:18080/ec/dp/B0E000001" in the DESCRIPTION column — just the first URL — making it impossible to tell which crawl used which configuration from the list view.

#### Root Cause Analysis

The task description is derived from the first URL rather than from the seed file path or command parameters.

#### Code Pointer

``browser4-rest` task creation — description field population logic.`

#### AI Suggested Improvement

- Use the seed file basename as the description when `--seed-file` is provided
- Append key flags to the description (e.g., "+SQL", "+refresh", "+bg")
- Allow users to set a custom label via `--label "my crawl run"`
- Show seed file path in `crawl status <id>` output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Crawl silently drops URLs — only first seed URL is processed

```bash
# Create seed file with 3 distinct URLs:
echo "http://localhost:18080/ec/dp/B0E000001" > urls.txt
echo "http://localhost:18080/ec/dp/B0E000002" >> urls.txt
echo "http://localhost:18080/ec/dp/B0E000003" >> urls.txt
./b4w.ps1 crawl --seed-file urls.txt --depth 0
```

#### Issue 2: X-SQL extraction returns "No extracted data" with valid query

```bash
# Create valid X-SQL query file:
cat > query.sql << 'EOF'
SELECT
    DOM_BASE_URI(DOM) AS url,
    DOM_FIRST_TEXT(DOM, '#productTitle') AS title,
    DOM_FIRST_TEXT(DOM, '#product-price') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'body')
EOF
./b4w.ps1 crawl --seed-file urls.txt --depth 0 --sql @query.sql --refresh
```

#### Issue 3: PowerShell flag interception breaks `./b4w.ps1` with short flags

```bash
# In Git Bash:
./b4w.ps1 snapshot -v 0 --stdout
```

#### Issue 4: `crawl clear` removes task data but leaves orphaned list entries showing "not found"

```bash
./b4w.ps1 crawl --seed-file urls.txt --depth 0    # task completes
./b4w.ps1 crawl clear                               # clears 58 tasks
./b4w.ps1 crawl list                                # recent tasks show "not found"
./b4w.ps1 crawl result <task-id>                    # "Task not found"
```

#### Issue 5: CSS selector discovery requires manual HTML inspection — no guided workflow

1. Follow the crawl.md examples using `h1` and `.price` as selectors
2. Get "No extracted data" with no hint about selector mismatch

#### Issue 6: SKILL.md `--` passthrough example doesn't work in Git Bash

Following SKILL.md: "When using `b4w.ps1` directly, pass flags after `--` (e.g. `./b4w.ps1 -- snapshot -i`)"

```bash
./b4w.ps1 -- snapshot -v 0 --stdout
```

#### Issue 7: `crawl list` shows overwhelming number of historical tasks with no filtering

```bash
./b4w.ps1 crawl list   # Shows 97 tracked tasks spanning weeks
```

#### Issue 8: `crawl list` DESCRIPTION column only shows first URL, not seed file or full command

Run multiple crawls with different seed files and options. Then `crawl list`.

