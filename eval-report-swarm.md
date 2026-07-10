# Browser4-CLI Usability Evaluation Report — Swarm

**Date:** 2026-07-09
**Evaluator:** Claude (AI Agent)
**Task:** Swarm parallel extraction — create swarm session, submit X-SQL extraction jobs across 10 product URLs, submit plain scrape job, poll statuses, retrieve results, list tasks, close session
**CLI invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`

---

## A. Task Result

All 9 task steps were completed, but with significant reliability issues affecting the quality of results:

1. ✅ Created swarm session with `--display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4`
2. ✅ Created seed file with 10 product URLs (6 Electronics + 4 Home)
3. ✅ Wrote X-SQL extraction query extracting title, price, and image URL
4. ✅ Submitted 10 X-SQL extraction jobs via `swarm query --sql @file --seed-file --refresh`
5. ✅ Submitted 1 plain scrape job via `swarm submit --refresh --parse --store-content`
6. ⚠️ Polled statuses — but `isDone` never became `true` (always `null`), requiring workaround
7. ⚠️ Retrieved results — first batch: 8/10 had data (titles+prices, but no images); second batch: 0/10 had data. Plain scrape succeeded.
8. ✅ Listed all 21 swarm tasks — all showed "pending" despite having retrievable results
9. ✅ Closed swarm session to release resources

**Net result:** The task workflow is functional but unreliable. The X-SQL extraction via `swarm query` produces flaky results (sometimes returns data, sometimes empty), the `isDone` status field is broken preventing proper polling, and the `DOM_FIRST_IMG` function doesn't work as expected for extracting image URLs.

---

## B. Execution Trace

| Step | Command | Notes |
|------|---------|-------|
| Prep | `help` | Help output comprehensive |
| Prep | Read `skills/browser4-cli/SKILL.md` + `swarm.md` + `x-sql.md` | Documentation clear for basic usage |
| 1 | `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` | Created successfully |
| 2 | Created `swarm-seed.txt` with 10 URLs | File-based, no issues |
| 3 | Created `swarm-extract.sql` with X-SQL query | Used `DOM_LOAD_AND_SELECT(@url, ':root')` |
| 4 | `swarm query --sql @swarm-extract.sql --seed-file swarm-seed.txt --refresh` | 10 jobs submitted, task IDs returned |
| 5 | `swarm submit http://localhost:18080/ec/dp/B0E000001 --refresh --parse --store-content` | 1 job submitted, distinct from query jobs |
| 6a | `swarm status <id>` (multiple times) | `isDone` always `null`; `statusCode: 200` eventually |
| 6b | `swarm list` (multiple times) | All tasks showed "pending" even when results available |
| 7a | `swarm result <id>` (batch 1, 11 jobs) | 8/10 X-SQL jobs had data; 2 empty; 1 scrape job had URL |
| 7b | Re-submitted with fixed SQL (`DOM_FIRST_ATTR`) + `--wait` | `--wait` timed out at 300s (0/10 completed); all 10 results empty |
| 7c | Tested same X-SQL via `htmlsnapshot query` | Worked correctly — returned title, price, image_url |
| 8 | `swarm list` | 21 tasks total, all "pending" |
| 9 | `swarm close` | Closed successfully |

### Important Decisions

1. Used absolute paths for `--sql` and `--seed-file` to avoid confusion with cargo's working directory (`cli/browser4-cli/`).
2. Tested the X-SQL query via `htmlsnapshot query` when swarm results were empty — confirmed the query is valid.
3. Re-submitted with `--wait` flag to test the "block until complete" feature — it always times out due to the `isDone` bug.
4. Used `DOM_FIRST_ATTR(DOM, '#product-image', 'src')` instead of `DOM_FIRST_IMG(DOM, '#product-image')` after discovering the latter returns empty strings.

### Workarounds Required

1. **Polling without `isDone`**: Instead of checking `isDone: true`, polled `swarm result` directly and checked if `resultSet` was non-empty or `pageContentBytes > 0`.
2. **Image URL extraction**: Used `DOM_FIRST_ATTR` instead of `DOM_FIRST_IMG` to extract image src values.
3. **`--wait` broken**: Could not rely on `--wait` to block until completion; had to manually poll with `swarm result`.
4. **Working directory**: Used absolute paths to avoid the `cli/browser4-cli/` working directory confusion.

---

## C. Issues Found

### Issue 1: `swarm status` never sets `isDone: true` — always returns `null`

**Severity:** Critical

**Category:** Reliability

**Reproduction:**
```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- swarm create --display-mode HEADLESS
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- swarm query --sql @query.sql --seed-file urls.txt --refresh
# Wait for backend to process
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- swarm status <any-task-id>
```

**Expected:** `"isDone": true` when the job completes, as documented in `swarm.md`: "Wait for `isDone: true` before calling `swarm result`."

**Actual:** `"isDone": null` for all jobs, even after `statusCode` changes to 200 and results are retrievable via `swarm result`. The `lastModifiedTime` updates but `isDone` never transitions from `null` to `true`.

Example status output for a job whose results are already available:
```json
{
  "id": "86f0cd83-c2a1-4ad3-8d5f-000e0dffcde0",
  "isDone": null,
  "lastModifiedTime": "2026-07-08T19:30:44.407077119Z",
  "message": "",
  "statusCode": 200
}
```

**Root Cause:** The swarm job lifecycle in the backend does not set the `isDone` field when jobs complete. The field appears to be initialized as `null` but never updated to `true`. This could be in the `SwarmController` or the underlying task runner — the field mapping between the internal job state and the API response is incomplete.

**Code Pointer:** `browser4-rest/.../SwarmController.java` — the endpoint that returns swarm status, or the DTO mapping from internal task state to the status response.

**AI Suggested Improvement:**
- Set `isDone` to `true` in the job completion callback/handler when `statusCode` transitions to a terminal state (200, 4xx, 5xx)
- Alternatively, if the field isn't tracked internally, derive it from the existing state: `isDone = statusCode != 201` or check if the result payload is available
- Update `swarm.md` documentation to clarify what `isDone: null` means (currently docs only mention `true`/`false`)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `--wait` flag always times out because it relies on broken `isDone`

**Severity:** Critical

**Category:** Reliability

**Reproduction:**
```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- swarm query --sql @query.sql --seed-file urls.txt --refresh --wait
```

**Expected:** `--wait` blocks until all jobs complete, then prints a progress summary.

**Actual:** `--wait` polls for 300 seconds (5 minutes) and always times out with `0 of N job(s) completed`, even though results are already available via `swarm result`. Output:
```
Waiting for 10 job(s) to complete...
  ... 0/10 job(s) completed (elapsed: 31s)
  ... 0/10 job(s) completed (elapsed: 61s)
  ...
Timeout after 300s. 0 of 10 job(s) completed. 10 job(s) still pending.
```

**Root Cause:** `--wait` polls every 2 seconds using `swarm status` and checks `isDone: true`. Since `isDone` is never set to `true` (Issue 1), `--wait` is effectively non-functional.

**Code Pointer:** Same as Issue 1 — fix `isDone` and `--wait` will work automatically. The wait loop is likely in the CLI's swarm command handler.

**AI Suggested Improvement:**
- Fix Issue 1 (isDone tracking) — this will automatically fix `--wait`
- As a defensive measure, also check for terminal `statusCode` values (200, 4xx, 5xx) as a fallback completion indicator in the wait loop
- Add a progress update that shows actual job status (e.g., "5/10 fetched, 3/10 extracted") rather than just completed/pending binary

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `swarm query` X-SQL extraction produces empty `resultSet` inconsistently

**Severity:** High

**Category:** Reliability

**Reproduction:**
```
# Batch 1: 8/10 jobs returned data, 2 empty
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- swarm query --sql @query.sql --seed-file urls.txt --refresh
# Check results - some have data, some are empty

# Batch 2: Re-submit same query, same URLs, same flags
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- swarm query --sql @query.sql --seed-file urls.txt --refresh
# Check results - ALL empty despite pages being fetched (4-5KB each)
```

**Expected:** Every job returns a `resultSet` with extracted data (title, price, image URL) since the X-SQL query is valid and all product pages have identical HTML structure.

**Actual:**
- First submission: 8/10 jobs returned `[{url, title, price, image_url}]`, 2 returned `[]`
- Second submission (same query, same URLs): All 10 returned `[]`
- The same X-SQL query tested via `htmlsnapshot query` works correctly every time

The pages ARE fetched (4-5 KB `pageContentBytes` in each response), but the X-SQL evaluation produces no rows.

**Root Cause:** Likely a race condition or state management issue in the swarm X-SQL execution path. The `htmlsnapshot query` path (which uses a different code path — interactive session with stored snapshot) works consistently. The swarm path may evaluate X-SQL against a page state that isn't fully parsed/loaded, or the page storage/cache from previous runs interferes. Specifically:
- The second batch may be hitting cached pages that were parsed in a way that the X-SQL engine can't query
- Or the X-SQL evaluation is skipped/missed on some execution paths
- Or there's a timing issue where the page is fetched but the DOM isn't available when X-SQL runs

**Code Pointer:** `browser4-rest/.../SwarmController.java` — the swarm query endpoint's interaction with the X-SQL engine and page storage. Also `StreamingCrawlerMetrics` / task runner for the job execution pipeline.

**AI Suggested Improvement:**
- Add integration tests that submit swarm X-SQL queries and verify non-empty resultSets
- Ensure X-SQL evaluation always runs after page fetch completes, with proper ordering guarantees (fetch → parse → X-SQL evaluate → store result)
- Log warnings when X-SQL produces empty resultSets for pages that were successfully fetched
- Consider adding a `--parse` flag to `swarm query` (like `swarm submit` has) to force explicit parse-before-query

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `DOM_FIRST_IMG` returns empty string for valid `<img>` elements

**Severity:** Medium

**Category:** Product

**Reproduction:**
```sql
-- This returns empty string for image_url:
SELECT DOM_FIRST_IMG(DOM, '#product-image') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, ':root')
WHERE DOM_IS_NOT_NIL(DOM)

-- This works correctly:
SELECT DOM_FIRST_ATTR(DOM, '#product-image', 'src') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, ':root')
WHERE DOM_IS_NOT_NIL(DOM)
```

Page HTML:
```html
<img id="product-image" class="product-image" src="/ec/static/img/placeholder.png" alt="4K OLED TV 55" />
```

**Expected:** `DOM_FIRST_IMG(DOM, '#product-image')` returns `/ec/static/img/placeholder.png` (the `src` attribute value).

**Actual:** Returns empty string `""`.

**Root Cause:** `DOM_FIRST_IMG` may be looking for a background image or CSS `url()` reference rather than an `<img>` element's `src` attribute. Or it may have an issue with how the Jsoup-parsed DOM represents `<img>` elements. The function might be designed for a different use case than extracting `<img src>` values.

**Code Pointer:** `DomSelectFunctions.kt` — `DOM_FIRST_IMG` function implementation

**AI Suggested Improvement:**
- Fix `DOM_FIRST_IMG` to extract the `src` attribute from `<img>` elements (this is the most intuitive behavior)
- If the current behavior is intentional, document clearly in x-sql.md what `DOM_FIRST_IMG` actually extracts vs what `DOM_FIRST_ATTR(..., 'src')` extracts
- Add a code example in x-sql.md showing how to extract image URLs using both approaches

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `swarm list` always shows "pending" regardless of actual job state

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:**
```
# Submit jobs, wait for them to process, retrieve results successfully, then:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- swarm list
```

**Expected:** Tasks that have completed (results available via `swarm result`) show a status like "done", "complete", or "200".

**Actual:** All tasks show "pending" forever. Even after `swarm result` successfully returns data, `swarm list` still shows "pending".

**Root Cause:** `swarm list` likely reads from the same task tracking state that uses `isDone` to determine display status. Since `isDone` is never set to `true` (Issue 1), all tasks remain "pending" indefinitely.

**Code Pointer:** CLI task tracking module — the list display logic that maps internal task state to the STATUS column.

**AI Suggested Improvement:**
- Fix Issue 1 (isDone tracking) — this should automatically fix the list display
- Add more granular statuses beyond binary done/not-done: "queued", "fetching", "extracting", "done", "error"
- Show statusCode in the list output when available (e.g., "done (200)" or "error (500)")

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Working directory confusion when running from source

**Severity:** Low

**Category:** Discoverability / Documentation

**Reproduction:**
```
# From repo root:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- swarm query --sql @query.sql --seed-file urls.txt
# Fails: can't find query.sql because cwd is cli/browser4-cli/
```

**Expected:** File paths resolve relative to the repo root where the user is running the command.

**Actual:** File paths resolve relative to `cli/browser4-cli/` (cargo's working directory). The user must either:
- Use absolute paths
- Use paths relative to `cli/browser4-cli/` (e.g., `../../query.sql`)
- `cd cli/browser4-cli` first

**Root Cause:** `cargo run --manifest-path` sets the working directory to the manifest's directory, not the user's current directory. The `development.md` documents this but it's easy to miss when following tutorials that assume `browser4-cli` is installed globally.

**AI Suggested Improvement:**
- Add a prominent note/warning at the top of `swarm.md` and `crawl.md` about path handling when running from source
- Add an example using absolute paths as the recommended pattern for `cargo run` usage
- Consider a `--repo-root` flag or environment variable that the CLI could use to resolve relative paths from the repo root

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: No batch status or batch result command for swarm

**Severity:** Low

**Category:** UX

**Reproduction:**
```
# After submitting 10+ jobs, must poll each individually:
cargo run ... -- swarm status <id1>
cargo run ... -- swarm status <id2>
# ... repeat for all IDs
```

**Expected:** A way to check status or retrieve results for all swarm jobs at once, e.g. `swarm status --all` or `swarm result --all`.

**Actual:** Each task ID must be queried individually. `swarm list` shows task descriptions but not status details or results. For bulk operations with many URLs, this becomes tedious.

**Root Cause:** The swarm CLI was designed around individual task ID tracking. There's no aggregation endpoint or CLI-side batch operation.

**AI Suggested Improvement:**
- Add `swarm status --all` to show status of all tracked swarm tasks
- Add `swarm result --all` to fetch and display results for all completed tasks
- Consider adding a `--format table` option for human-readable multi-result display
- Add a summary line to `swarm list` showing X/Y completed

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: `swarm query` vs `swarm submit` naming is confusing for first-time users

**Severity:** Low

**Category:** Discoverability / Documentation

**Reproduction:** A new user reads the swarm documentation and sees both `swarm submit` and `swarm query`. The distinction ("submit" for plain scrape, "query" for X-SQL extraction) is not immediately obvious from the command names alone.

**Expected:** Clear, intuitive command names that reflect what each does. Or at minimum, the help output and docs should clearly differentiate them.

**Actual:** 
- `swarm submit --help` says "Submit URL(s) or X-SQL payloads as scrape jobs" — implying it also handles X-SQL
- `swarm query --help` says "Submit an X-SQL query to extract structured data from a loaded webpage"
- The docs say "Prefer `swarm query` over `swarm submit --sql`", suggesting both can do X-SQL
- In practice, `swarm submit` without `--sql` does a plain scrape (stores content, optionally parses), while `swarm query` does X-SQL extraction

**Root Cause:** The commands evolved to have overlapping functionality. `swarm submit` can accept `--sql` but `swarm query` is the preferred way to submit X-SQL jobs. This split is documented but counterintuitive.

**AI Suggested Improvement:**
- Clarify in `swarm submit --help` that for X-SQL, `swarm query` is preferred
- Consider consolidating into a single `swarm submit` command with clear sub-modes (e.g., `swarm submit --mode scrape` vs `--mode extract`)
- Add a comparison table to `swarm.md` showing the differences side-by-side

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
**Partially successful.** All workflow steps were completed, but the quality of extracted data was inconsistent:
- First swarm query batch: 8/10 jobs returned partial data (titles + prices, no images)
- Second swarm query batch: 0/10 jobs returned data (all empty resultSets)
- Plain scrape job: worked correctly
- `htmlsnapshot query` (non-swarm): worked correctly with full data

### Estimated Task Success Rate
**~60%** — The workflow mechanics work (session creation, job submission, result retrieval, session close), but the core value proposition (reliable X-SQL extraction at scale) is undermined by the empty resultSet and isDone bugs.

### Number of Issues Found
**8 issues:** 2 Critical, 1 High, 2 Medium, 3 Low

### Major Blockers
1. **`isDone` never being `true`** (Issue 1) — breaks the documented polling workflow and the `--wait` flag, making it impossible to programmatically know when jobs are complete.
2. **Empty resultSet inconsistency** (Issue 3) — the core extraction mechanism is unreliable, sometimes returning data and sometimes not for identical inputs.

### Most Confusing Aspects
1. Why `swarm status` shows `isDone: null` when results are available — the disconnect between "job done" and "isDone field" is deeply confusing.
2. Why `DOM_FIRST_IMG` doesn't work for `<img src>` — the function name strongly suggests it extracts image URLs.
3. Why `swarm list` says "pending" for everything — no way to get a quick status overview without manually checking each task.

### Most Valuable Improvements
1. **Fix `isDone` tracking** — this single fix would resolve Issues 1, 2, and 5, restoring trust in the swarm workflow.
2. **Make X-SQL extraction reliable in swarm mode** — ensure the same query produces the same results whether run via `swarm query` or `htmlsnapshot query`.
3. **Add batch operations** — `swarm status --all` and `swarm result --all` would dramatically improve the UX for multi-URL workflows.
4. **Add granular statuses** — "queued", "fetching", "extracting", "done" instead of just "pending".

### Overall Usability Rating
**5/10**

The foundation is solid — the swarm session lifecycle, seed file processing, and result retrieval API all work. However, the reliability issues with `isDone` tracking and X-SQL extraction make the feature feel unfinished. A new user would be confused by the disconnect between documentation ("wait for isDone: true") and reality ("isDone is always null"), and would lose confidence when extraction results are inconsistently empty. The `htmlsnapshot query` path works correctly, suggesting the X-SQL engine itself is sound — the bugs are in the swarm integration layer.

With the 3 critical/high issues fixed, the rating would rise to **7-8/10**. The command structure is well-designed, the documentation (aside from the isDone mismatch) is clear, and the seed file + X-SQL pattern is powerful when it works.
