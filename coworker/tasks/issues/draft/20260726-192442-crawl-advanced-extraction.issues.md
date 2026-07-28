# Issues: crawl-advanced-extraction

> **Source:** `20260726-192442-crawl-advanced-extraction.full.md` | **Date:** 20260726-192442 | **Mode:** dev

## Scenario Background

### Task

The task was **partially completed** with significant issues:

| Step | Description | Outcome |
|------|-------------|---------|
| 1 | Create seed file with 3 product URLs | ✅ Done |
| 2 | Write X-SQL query to file | ✅ Done (`extract_products.sql`) |
| 3 | Crawl with depth 0 + X-SQL + flags | ⚠️ Ran, but X-SQL returned no data; only 1/3 URLs processed |
| 4 | Background crawl | ✅ Submitted and completed |
| 5 | List crawl tasks | ✅ Listed |
| 6 | `--ignore-url-query` crawl | ⚠️ Completed but only 1/3 URLs processed |
| 7 | `--no-norm` crawl | ⚠️ Completed; no observable difference from default |
| 8 | `--readonly` crawl | ⚠️ Completed; no observable difference from default |
| 9 | Final crawl list + wait for background | ✅ All tasks listed; background task completed |

**Extraction result:** Title "4K OLED TV 55" and price "$899.99" were successfully extracted from product page B0E000001 using `htmlsnapshot get text`. However, X-SQL (`htmlsnapshot query` and `crawl --sql`) produced no results. Only 1 of 3 seed URLs was ever crawled.

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — discover available commands
2. `./b4w.ps1 status` — check server health (revealed version mismatch)
3. `curl` — verify MockSite is running and pages are accessible
4. `./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"` — navigate to product page
5. `./b4w.ps1 htmlsnapshot` — capture page for extraction
6. `./b4w.ps1 htmlsnapshot get text "#productTitle"` — extract title (worked ✅)
7. `./b4w.ps1 htmlsnapshot get text "#product-price"` — extract price (worked ✅)
8. `./b4w.ps1 htmlsnapshot query --sql @extract_products.sql` — X-SQL extraction (failed ❌)
9. `./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --sql @extract_products.sql --refresh --parse --expires 1h --priority -2000 --page-load-timeout 30s` — Step 3 crawl
10. `./b4w.ps1 crawl ... --background` — Step 4 background crawl
11. `./b4w.ps1 crawl list` — Steps 5, 9
12. `./b4w.ps1 crawl ... --ignore-url-query` — Step 6
13. `./b4w.ps1 crawl ... --no-norm` — Step 7
14. `./b4w.ps1 crawl ... --readonly` — Step 8
15. `./b4w.ps1 crawl result <id>` — inspect individual results

**Workarounds:**
- Used `htmlsnapshot get text` instead of X-SQL for data extraction
- Used simplified crawl commands (without `--sql`) after X-SQL failures

---

---

## Issues Found (11 issues)

### Issue 1: X-SQL query (htmlsnapshot query) returns no results with correct syntax

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot query --sql "SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, ':root')" --format table
```
Also reproducible with `crawl --sql @file`.

#### Expected Behavior

The query returns at least 1 row with the page title.

#### Actual Behavior

`No data. 0 rows returned.` — even when using the exact example from the help text.

#### Root Cause Analysis

Likely related to the backend version mismatch (v4.11.15 vs v4.12.0). The X-SQL engine in the older backend may handle `load_and_select` or the snapshot storage differently. Could also be a SQLTemplate URL replacement issue where `@url` is not properly resolved. The `htmlsnapshot get text` path works correctly against the same stored snapshot, proving the snapshot is valid and the CSS selectors work.

#### Code Pointer

`Backend: `SQLTemplate.createSQL()` / `DomFunctionTables.kt` — `DOM_LOAD_AND_SELECT` implementation; possibly the snapshot-to-DOM bridge in the older backend release.`

#### AI Suggested Improvement

- Fix the backend auto-start to use the locally-built JAR (see Issue 1) so X-SQL testing matches the code under evaluation
- Add a diagnostic mode to `htmlsnapshot query` that shows what `@url` resolves to
- Return a meaningful error when `load_and_select` resolves to 0 rows (e.g., "Page loaded but no elements matched CSS selector ':root'")

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: Crawl silently drops all but the first seed URL

**Severity:** Critical
**Category:** Reliability / Product

#### Reproduction

```bash
cat > seed_urls.txt << 'EOF'
http://localhost:18080/ec/dp/B0E000001
http://localhost:18080/ec/dp/B0E000002
http://localhost:18080/ec/dp/B0E000003
EOF
./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --refresh --expires 1h --page-load-timeout 30s
```

#### Expected Behavior

All 3 URLs are fetched; `pagesFound` = 3.

#### Actual Behavior

`pagesFound` = 1 every time. Only the first URL in the seed file is fetched. The other 2 are silently dropped — no error, no warning, no mention in output.

#### Root Cause Analysis

Confirmed with a test using only URLs 2 and 3 — in that case URL 2 was fetched and URL 3 was dropped. The crawl always processes exactly the first seed URL and stops. Likely a bug in the crawl loop that terminates early after the first page completes, or a deduplication bug where subsequent URLs are incorrectly flagged as already visited. Could be specific to `--refresh` + `--expires` interaction.

#### Code Pointer

`Backend: crawl task execution loop — the iteration over seed URLs likely exits after the first URL's completion.`

#### AI Suggested Improvement

- Fix the crawl loop to iterate through all seed URLs
- Add a warning when seed URLs are skipped: "Warning: 2 of 3 seed URLs were not processed"
- Add a `--verbose` flag to crawl that shows per-URL status (fetched/skipped/error)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: Backend version mismatch — installed backend is stale

**Severity:** High
**Category:** Reliability / Setup

#### Reproduction

```
./b4w.ps1 status
```

#### Expected Behavior

The CLI auto-starts a backend built from the local source tree, matching the currently checked-out code.

#### Actual Behavior

The CLI starts the pre-installed backend bundle (v4.11.15) while the CLI itself is v4.12.0 from local source. The output explicitly warns: "Version mismatch: CLI is 4.12.0 but installed backend is v4.11.15."

#### Root Cause Analysis

The daemon auto-start mechanism uses the globally installed backend bundle (from `browser4-cli install`) rather than building and running the backend from the local Maven project. The `./b4w.ps1` wrapper compiles the CLI from local source but doesn't handle the backend similarly.

#### Code Pointer

``b4w.ps1` — daemon startup section; `CLAUDE.md` instructions claim "the CLI daemon auto-starts the locally-built backend JAR."`

#### AI Suggested Improvement

- Update `b4w.ps1` to check if a local `browser4-rest` build exists and prefer it over the installed bundle
- Add a `--dev-backend` flag to `b4w.ps1` that forces building and running the local backend JAR
- Document the `mvn spring-boot:run` alternative prominently in CLAUDE.md or SKILL.md

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: Silent failure when X-SQL extraction produces no results

**Severity:** High
**Category:** UX / Discoverability

#### Reproduction

```bash
./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --sql @extract_products.sql --refresh
```

#### Expected Behavior

A clear error or warning when X-SQL extraction is enabled but produces 0 rows (distinct from "no pages crawled").

#### Actual Behavior

The crawl completes with `pagesFound: 1` and status `OK`, but there is no `resultSet` in the JSON output. The CLI says "No extracted data" only at the very end, after 80+ seconds of waiting. The user cannot tell whether the X-SQL query has a syntax error, the selectors don't match, or the extraction engine failed.

#### Root Cause Analysis

The crawl result doesn't distinguish between "X-SQL was not requested" and "X-SQL was requested but returned 0 rows." When there are 0 rows, the `resultSet` is simply omitted from the JSON.

#### Code Pointer

`Backend: crawl result serialization — should include `resultSet: []` (empty array) vs omitting the field.`

#### AI Suggested Improvement

- Include `resultSet` in the result JSON even when empty: `"resultSet": []`
- Add a `"extractionStatus"` field: `"success"`, `"no_match"`, `"error"`
- When `--sql` is provided and 0 rows returned, show a distinct warning: "X-SQL query executed but matched 0 elements across 1 page"
- Validate X-SQL syntax at crawl submission time rather than silently failing at runtime

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: X-SQL function name inconsistency in documentation

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read the X-SQL reference at `skills/browser4-cli/references/x-sql.md` and `x-sql-dom-load-select.md` — both use `DOM_LOAD_AND_SELECT`. Then run `./b4w.ps1 htmlsnapshot query --help` — the examples use `load_and_select` (lowercase, no DOM_ prefix).

#### Expected Behavior

A single, consistent function name across all documentation and help output.

#### Actual Behavior

Reference docs use `DOM_LOAD_AND_SELECT(...)`, but CLI help and crawl.md examples use `load_and_select(...)`. Both are listed as valid SQL aliases, but a new user encountering the reference docs first will write the wrong name, and there's no indication that `load_and_select` is the canonical form for inline queries.

#### Root Cause Analysis

X-SQL aliases map multiple names to the same function, but the documentation doesn't establish which name is canonical. The reference docs prefer the `DOM_` prefix form while the examples prefer the unprefixed form. No guidance tells users which to use when.

#### Code Pointer

``skills/browser4-cli/references/x-sql-dom-load-select.md:20` — uses `DOM_LOAD_AND_SELECT`; `skills/browser4-cli/references/crawl.md:72` — uses `load_and_select`.`

#### AI Suggested Improvement

- Standardize on one canonical name throughout all documentation (recommend `load_and_select` for inline SQL since it's shorter and matches the examples users see in `--help`)
- Add a prominent note at the top of `x-sql-dom-load-select.md` explaining the alias: "Also available as `load_and_select`"
- Ensure all code examples in reference docs use the same name as the CLI help text

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: Misleading "Still waiting for crawl to start..." progress messages

**Severity:** Medium
**Category:** UX / Reliability

#### Reproduction

Run any `crawl` command without `--background`.

#### Expected Behavior

Status messages accurately reflect the crawl's state (e.g., "Crawling... 1/3 pages fetched").

#### Actual Behavior

The CLI repeatedly prints "Still waiting for crawl to start... (16s/32s/48s/... elapsed)" for 80-100 seconds, then suddenly shows "Crawl completed." The crawl IS running during this time (pages are being fetched), but the status polling doesn't detect the transition from "created" to "running."

#### Root Cause Analysis

The CLI's status polling mechanism checks a status field that stays "created" throughout the crawl lifecycle. The crawl may go directly from "created" to "completed" without transitioning through a "running" state, or the backend status reporting doesn't expose the intermediate running state.

#### Code Pointer

`CLI: crawl status polling in `commands.rs` or equivalent — the `wait_for_completion` loop likely checks for a "started"/"running" status that the backend never reports.`

#### AI Suggested Improvement

- Show a countdown or pages-fetched-so-far progress instead of "waiting to start"
- Display "Crawling (1 page fetched so far)..." based on intermediate results
- Reduce the polling interval from 16s to something more responsive (e.g., 5s)
- If the backend doesn't support progress reporting, at least change the message to "Waiting for crawl... (N seconds)" without the misleading "to start" suffix

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 7: Crawl with `--parse` produces empty titles in results

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --refresh --parse --expires 1h --page-load-timeout 30s
# Compare with same crawl without --parse:
./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --refresh --expires 1h --page-load-timeout 30s
```

#### Expected Behavior

`--parse` should not affect the page title extraction.

#### Actual Behavior

Crawls with `--parse` show `"title": ""` in the JSON result. Without `--parse`, the title shows correctly (e.g., "Product: 4K OLED TV 55"). In the CLI text output, both show the title, but the JSON result differs.

#### Root Cause Analysis

The `--parse` flag may trigger a different page processing path that doesn't preserve the `<title>` element extraction, or the parsing step overwrites the title metadata with an empty string.

#### Code Pointer

`Backend: page parsing logic — the title extraction after `--parse` likely fails to read the parsed DOM's title.`

#### AI Suggested Improvement

- Ensure `--parse` preserves page title extraction in the result metadata
- Add a test for `--parse` title extraction

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 8: Excessive crawl startup delay (~80-100s) for simple local requests

**Severity:** Medium
**Category:** Performance / UX

#### Reproduction

Run any crawl against localhost:18080. The crawl takes 80-100 seconds to process even a single 4KB page.

#### Expected Behavior

A fetch of a 4KB page from localhost should complete in under 5 seconds.

#### Actual Behavior

Every crawl takes 80-100 seconds from submission to completion. A direct `goto` + `htmlsnapshot` completes the same page load in ~2 seconds.

#### Root Cause Analysis

The "Still waiting for crawl to start..." messages suggest the backend's crawl task dispatcher has a delay before picking up queued tasks. The 16-second polling interval compounds this. There may be an intentional startup delay in the crawl infrastructure or a congested task queue.

#### Code Pointer

`Backend: crawl task dispatcher — startup delay or task scheduling logic.`

#### AI Suggested Improvement

- Profile the crawl task dispatch pipeline to identify where the 80s delay originates
- Reduce the default polling interval from 16s to 5s
- For simple depth-0 crawls, consider a fast-path that skips the full task queue

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 9: Stale "not found" tasks persist across sessions and pollute crawl list

**Severity:** Low
**Category:** UX

#### Reproduction

Run `./b4w.ps1 crawl list` — 4 tasks from July 10-20, 2026 show status "not found" alongside current tasks.

#### Expected Behavior

Stale tasks from previous sessions are either auto-cleaned or clearly separated from active tasks.

#### Actual Behavior

4 old "not found" tasks from 1-2 weeks ago are interleaved with today's tasks in the list. `crawl clear` only removed 1 task (the only one in a terminal state). "not found" tasks cannot be cleared.

#### Root Cause Analysis

"Not found" tasks are ones where the backend no longer has the task data (likely evicted or from a different backend instance), but the CLI still tracks them in its task store. The `crawl clear` command only removes tasks in terminal states (completed/failed/cancelled), not "not found" tasks.

#### Code Pointer

`CLI: task store cleanup in `crawl clear` — should also remove tasks with "not found" status.`

#### AI Suggested Improvement

- Make `crawl clear` also remove "not found" tasks
- Add a `--force` option to `crawl clear` that removes all tracked tasks regardless of status
- Auto-expire "not found" tasks after a configurable TTL (e.g., 24h)
- Visually separate stale tasks from active ones in the list output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 10: `--no-norm`, `--readonly`, and `--ignore-url-query` produce no observable difference

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run crawls with each of `--no-norm`, `--readonly`, `--ignore-url-query` and compare outputs to a baseline crawl without these flags.

#### Expected Behavior

Each flag should produce a visibly different behavior or at least indicate in the output that it took effect.

#### Actual Behavior

All three crawls produced identical output: 1 page found, same URL, same stats. There is no indication in the output that these flags had any effect. For a first-time user, it's impossible to verify that these flags are working.

#### Root Cause Analysis

The effects of these flags are invisible for simple depth-0 crawls against static local pages:
- `--no-norm`: URL normalization differences only matter when URLs would be normalized differently
- `--readonly`: Only matters if the crawl would otherwise perform destructive operations
- `--ignore-url-query`: Only matters when URLs contain query strings

#### AI Suggested Improvement

- Add a "flags applied" section to the crawl output showing which options are active
- For `--readonly`, display "Running in read-only mode" when the crawl starts
- Include the effective LoadOptions in the crawl result JSON for auditability
- Document which flags have visible effects vs. which are preventive/safety measures

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 11: PowerShell wrapper conflicting with `-v` and `-i` short flags

**Severity:** Low
**Category:** Documentation

#### Reproduction

The SKILL.md documents this at line 359: "When running `b4w.ps1` directly in PowerShell, short flags like `-i` and `-v` may be intercepted by PowerShell's parameter binder." The workaround is to pass flags after `--` or use `b4w.bat`.

#### Expected Behavior

Either the wrapper handles this transparently, or the documentation makes the workaround more prominent for first-time users.

#### Actual Behavior

This warning is buried in the Installation section at the bottom of SKILL.md. A user reading the Quick Patterns section would never see it and would encounter confusing failures.

#### Root Cause Analysis

PowerShell's parameter binding intercepts flags that match common parameters (`-i` → `-InformationAction`, `-v` → `-Verbose`). The `b4w.ps1` wrapper uses `ValueFromRemainingArguments` which should handle this, but the mention at line 359 suggests it doesn't always work.

#### AI Suggested Improvement

- Move the PowerShell flag warning to a more prominent location (e.g., §1 Core Loop or a dedicated "Platform Notes" callout near the top)
- Add the `--` workaround to the copy-paste template in §1
- Document the platform-specific invocation (bash `./b4w.ps1` vs PowerShell `. .\b4w.ps1`) more clearly

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
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: X-SQL query (htmlsnapshot query) returns no results with correct syntax

```bash
./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot query --sql "SELECT dom_first_text(dom, 'h1') AS title FROM load_and_select(@url, ':root')" --format table
```
Also reproducible with `crawl --sql @file`.

#### Issue 2: Crawl silently drops all but the first seed URL

```bash
cat > seed_urls.txt << 'EOF'
http://localhost:18080/ec/dp/B0E000001
http://localhost:18080/ec/dp/B0E000002
http://localhost:18080/ec/dp/B0E000003
EOF
./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --refresh --expires 1h --page-load-timeout 30s
```

#### Issue 3: Backend version mismatch — installed backend is stale

```
./b4w.ps1 status
```

#### Issue 4: Silent failure when X-SQL extraction produces no results

```bash
./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --sql @extract_products.sql --refresh
```

#### Issue 5: X-SQL function name inconsistency in documentation

Read the X-SQL reference at `skills/browser4-cli/references/x-sql.md` and `x-sql-dom-load-select.md` — both use `DOM_LOAD_AND_SELECT`. Then run `./b4w.ps1 htmlsnapshot query --help` — the examples use `load_and_select` (lowercase, no DOM_ prefix).

#### Issue 6: Misleading "Still waiting for crawl to start..." progress messages

Run any `crawl` command without `--background`.

#### Issue 7: Crawl with `--parse` produces empty titles in results

```bash
./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --refresh --parse --expires 1h --page-load-timeout 30s
# Compare with same crawl without --parse:
./b4w.ps1 crawl --seed-file seed_urls.txt --depth 0 --refresh --expires 1h --page-load-timeout 30s
```

#### Issue 8: Excessive crawl startup delay (~80-100s) for simple local requests

Run any crawl against localhost:18080. The crawl takes 80-100 seconds to process even a single 4KB page.

#### Issue 9: Stale "not found" tasks persist across sessions and pollute crawl list

Run `./b4w.ps1 crawl list` — 4 tasks from July 10-20, 2026 show status "not found" alongside current tasks.

#### Issue 10: `--no-norm`, `--readonly`, and `--ignore-url-query` produce no observable difference

Run crawls with each of `--no-norm`, `--readonly`, `--ignore-url-query` and compare outputs to a baseline crawl without these flags.

#### Issue 11: PowerShell wrapper conflicting with `-v` and `-i` short flags

The SKILL.md documents this at line 359: "When running `b4w.ps1` directly in PowerShell, short flags like `-i` and `-v` may be intercepted by PowerShell's parameter binder." The workaround is to pass flags after `--` or use `b4w.bat`.

