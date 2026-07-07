# Swarm Parallel Scraping & X-SQL Extraction — Usability Evaluation

## A. Task Result

**All 10 steps completed successfully.**

| Step | Action | Result |
|------|--------|--------|
| 1 | Created swarm session (HEADLESS, 2 contexts, 4 tabs) | Session SWARM created |
| 2 | Created seed file with 10 MockSite URLs (6 Electronics + 4 Home) | File: `seed-urls.txt` |
| 3 | Wrote X-SQL query for title, price, image URL | File: `extract-query.sql` |
| 4 | Submitted 10 X-SQL extraction jobs via `swarm query` | 10 jobs submitted with UUIDs |
| 5 | Submitted 1 plain scrape via `swarm submit` | 1 job submitted |
| 6 | Polled status for all 11 jobs | All completed with `done: true`, statusCode 200 |
| 7 | Retrieved results via `swarm status` and `swarm result` | 10 structured extractions + 1 plain URL |
| 8 | Listed swarm tasks via `swarm list` | 8 tracked tasks displayed |
| 9 | Closed swarm session via `close` | Session closed successfully |

### Extracted Data (first job example):
```json
{
  "image_url": "/ec/static/img/placeholder.png",
  "price": "$899.99",
  "title": "4K OLED TV 55",
  "url": "http://localhost:18080/ec/dp/B0E000001"
}
```

### Comparison: `swarm query` vs `swarm submit`

| Aspect | `swarm query` | `swarm submit` |
|--------|--------------|----------------|
| X-SQL required | Yes (`--sql` mandatory) | Optional |
| Result content | Structured fields (title, price, image_url) | URL only |
| Output format | `resultSet` array with extracted fields | `resultSet` with URL only |
| Use case | Structured data extraction | Plain page fetch/cache |

---

## B. Execution Trace

### Commands Used

```bash
# Prep: read documentation
cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- help

# Create swarm session
cargo run -- swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4

# Submit X-SQL extraction jobs (10 products)
cargo run -- swarm query --sql @../../extract-query.sql --seed-file ../../seed-urls.txt --refresh

# Submit plain scrape for comparison
cargo run -- swarm submit "http://localhost:18080/ec/dp/B0E000001" --refresh --store-content

# Poll status for all jobs (11 total)
cargo run -- swarm status ca40ced0-2239-4209-9d81-34bcd50e50c1
cargo run -- swarm status 4f2f9c6f-581d-41d0-83cc-d5080b18c607
# ... (8 more query jobs)

# Fetch results
cargo run -- swarm result ca40ced0-2239-4209-9d81-34bcd50e50c1
cargo run -- swarm result 4f2f9c6f-581d-41d0-83cc-d5080b18c607

# List all tasks
cargo run -- swarm list

# Close session
cargo run -- close
```

### Major Steps

1. **Environment setup** — Verified MockSite running on localhost:18080, confirmed repo root, read SKILL.md and CLI README comprehensively.
2. **HTML structure discovery** — Used `curl` to fetch a MockSite product page to identify CSS selectors (`#productTitle`, `#product-price`, `#product-image`).
3. **File preparation** — Created `seed-urls.txt` (10 URLs) and `extract-query.sql` (X-SQL with `DOM_LOAD_AND_SELECT`).
4. **Swarm session** — Created with custom parallelism limits.
5. **Job submission** — Two methods compared: `swarm query` (X-SQL extraction) and `swarm submit` (plain scrape).
6. **Status polling** — All jobs completed within ~20 seconds. Status returned both metadata and results.
7. **Result retrieval** — Both `swarm status` and `swarm result` returned identical completed payloads.
8. **Task listing** — `swarm list` showed partial history (8 tasks, not all 11).
9. **Cleanup** — `close` released swarm resources.

### Important Decisions

- Used `@file` syntax for X-SQL to avoid Windows shell escaping issues (as recommended in SKILL.md §5 Critical Warnings).
- Used `DOM_FIRST_ATTR(DOM, '#product-image', 'src')` instead of `DOM_FIRST_IMG` based on the documented memory about `DOM_FIRST_IMG` returning empty.
- Used relative paths (`../../filename`) for `--sql @file` and `--seed-file` since `cargo run` executes from `cli/browser4-cli/`.

### Workarounds Required

- **File path resolution**: Files at repo root needed `../../` prefix because `cargo run`'s CWD is `cli/browser4-cli`. Not immediately obvious to new users.
- **UUID task IDs**: Documentation examples show sequential `scrape-task-N` IDs, but actual IDs are UUIDs. Required copy/paste for each status check.
- **Manual status polling**: No built-in `--wait` flag for `swarm query`/`swarm submit` to block until completion. Required manual polling loop.

---

## C. Issues Found

### Issue 1: `swarm list` does not show tasks submitted via `swarm query`

**Severity:** High

**Category:** Product

**Reproduction:**
```bash
swarm query --sql @query.sql --seed-file urls.txt --refresh
swarm list
```
Only the `swarm submit` task and pre-existing stale tasks appear; none of the 10 `swarm query` jobs are listed.

**Expected:** `swarm list` should show all swarm-submitted tasks, including those from `swarm query`, with their current status.

**Actual:** `swarm list` shows 8 tasks — 7 are stale tasks from earlier sessions (books.toscrape.com), 1 is the `swarm submit` task. The 10 `swarm query` tasks are completely absent from the list output.

**Root Cause:** `swarm query` tasks appear to use a different tracking mechanism than `swarm submit` tasks. The `swarm list` command likely reads from a task registry that `swarm query` does not write to. The query tasks are accessible only via their UUID with `swarm status`/`swarm result`, but there is no way to rediscover them if the UUID is lost.

**Code Pointer:** Likely in the backend — `swarm query` handler may not register tasks in the same task tracker that `swarm list` reads from.

**AI Suggested Improvement:**
- Register all `swarm query` tasks in the same task tracking system used by `swarm list`
- If tasks are too ephemeral for full tracking, at minimum document that `swarm query` tasks won't appear in `swarm list` and recommend saving task IDs
- Add a `--name` option to `swarm query` to give human-readable labels

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Task IDs are non-sequential UUIDs, making them hard to reference

**Severity:** Medium

**Category:** UX

**Reproduction:**
```bash
swarm query --sql @query.sql --seed-file urls.txt --refresh
# Output: Task ID: ca40ced0-2239-4209-9d81-34bcd50e50c1
```

**Expected:** Human-readable or sequential task IDs (e.g., `scrape-task-1`, `scrape-task-2`) as shown in all documentation examples.

**Actual:** Task IDs are 36-character UUIDs (`ca40ced0-2239-4209-9d81-34bcd50e50c1`). Users must copy/paste these long strings for every `swarm status` or `swarm result` call. Easy to lose or mistype.

**Root Cause:** The backend generates UUID task IDs for `swarm query` jobs. The documentation examples use `scrape-task-N` as placeholder IDs which don't match what users actually see.

**Code Pointer:** Backend task ID generation for swarm query handler.

**AI Suggested Improvement:**
- Use shorter, human-readable prefixes: `sq-1`, `sq-2` for swarm query, `ss-1` for swarm submit
- At minimum, update documentation to show actual UUID format so users aren't surprised
- Add a `swarm list` that works for all submission types to make task ID rediscovery easy

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: No `swarm close` command — session closed with generic `close`

**Severity:** Low

**Category:** Discoverability

**Reproduction:**
1. Create a swarm session: `swarm create ...`
2. Try to close it: `swarm close` → command not found
3. The correct command is the generic `close`

**Expected:** A `swarm close` subcommand that mirrors `swarm create`, or at minimum a hint after `swarm create` that says "use `close` to release resources."

**Actual:** `close` is a top-level command with no swarm-specific variant. Users may try `swarm close` first and get an error. The swarm reference docs do mention this (`browser4-cli close`), but it's easy to miss.

**Root Cause:** `close` is a session-level command, not a swarm subcommand. While technically correct (swarm is just a session), the asymmetric naming (`swarm create` vs `close`) violates user expectations of symmetry.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — could add `swarm close` as an alias for `close`.

**AI Suggested Improvement:**
- Add `swarm close` as an alias for `close` when a swarm session is active
- Add a post-creation hint: "Swarm session created: SWARM. Use `browser4-cli close` to release resources when done."
- The hint already exists in the docs but not in the CLI output

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `swarm status` and `swarm result` return identical JSON for completed jobs

**Severity:** Low

**Category:** Product

**Reproduction:**
```bash
swarm status ca40ced0-2239-4209-9d81-34bcd50e50c1  # Returns full resultSet
swarm result ca40ced0-2239-4209-9d81-34bcd50e50c1  # Returns identical JSON
```

**Expected:** `swarm status` should return lightweight status metadata (isDone, status, progress) while `swarm result` should return just the result payload (resultSet). Or at minimum, `swarm result` should fail or warn if the job is not done.

**Actual:** Both commands return the identical full JSON payload including `resultSet`, `pageContentBytes`, `finishTime`, etc. The only difference is that `swarm result` on an incomplete job would likely return an error (not tested since all jobs completed quickly).

**Root Cause:** The backend likely returns the same endpoint response for both status and result queries. While functional, this makes the two-command API feel redundant — users learn they can skip `swarm result` and just poll `swarm status` until done.

**AI Suggested Improvement:**
- `swarm status`: return only metadata fields (id, done, status, lastModifiedTime, pageStatusCode) — omit resultSet
- `swarm result`: return only the result payload (resultSet) — error if not done
- Document the distinction clearly

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: File path resolution is confusing with `cargo run` working directory

**Severity:** Medium

**Category:** UX

**Reproduction:**
```bash
# From repo root, files are at ./
# But cargo run executes from cli/browser4-cli/
swarm query --sql @extract-query.sql --seed-file seed-urls.txt --refresh
# File not found errors
```

**Expected:** Clear error messages when files are not found, showing the resolved absolute path that was tried. Or documentation that explains the working directory behavior.

**Actual:** When using `cargo run`, the CWD is `cli/browser4-cli/`, so relative paths must be `../../filename`. If the file is not found, the error message may not clearly indicate the path resolution failure. Users must discover this through trial and error.

**Root Cause:** `cargo run` sets CWD to the crate directory. The `@file` syntax resolves paths relative to CWD. No absolute path resolution or helpful error messaging exists.

**AI Suggested Improvement:**
- When a file referenced via `@` or `--seed-file` is not found, show the absolute path that was tried
- Document the CWD behavior in the swarm reference and SKILL.md
- Consider resolving paths relative to the original invocation directory, not the cargo CWD

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `swarm list` shows stale tasks from previous sessions

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```bash
swarm create --display-mode HEADLESS
swarm submit "http://localhost:18080/ec/dp/B0E000001" --refresh --store-content
swarm list
# Shows tasks from previous sessions alongside current ones
```

**Expected:** `swarm list` should show only tasks relevant to the current swarm session, or clearly separate active vs. historical tasks.

**Actual:** The list includes 7 tasks from previous sessions (books.toscrape.com URLs, older localhost URLs) all showing `pending` status. This clutters the output and can mislead users into thinking old jobs are still running.

**Root Cause:** The task tracking system persists tasks across sessions without cleanup. Stale tasks from earlier test runs accumulate and are never garbage-collected.

**AI Suggested Improvement:**
- Scope `swarm list` to the current session by default; add `--all` flag for cross-session history
- Auto-expire or mark tasks as stale after session close
- Show task age or session origin in the list output
- Add `swarm list --clear` to remove stale entries

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Extracted image URLs are relative, not absolute

**Severity:** Low

**Category:** Product

**Reproduction:**
```sql
SELECT DOM_FIRST_ATTR(DOM, '#product-image', 'src') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, ':root')
```
Result: `"image_url": "/ec/static/img/placeholder.png"`

**Expected:** Absolute image URL (e.g., `http://localhost:18080/ec/static/img/placeholder.png`).

**Actual:** The extracted URL is relative (`/ec/static/img/placeholder.png`). Users must manually prepend the base URL to get usable links. The `DOM_BASE_URI` for the page returns the page URL, not the image base.

**Root Cause:** `DOM_FIRST_ATTR` returns the raw attribute value. While `DOM_FIRST_IMG` is documented to return "absolute src", it returns empty (per known issue). There's no `DOM_ABS_ATTR` function to resolve relative URLs.

**AI Suggested Improvement:**
- Fix `DOM_FIRST_IMG` to return absolute image URLs as documented
- Add `DOM_FIRST_ABS_ATTR(DOM, selector, attr)` to resolve relative URLs to absolute
- Document the current behavior so users know to construct absolute URLs manually

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: No `--wait` flag for swarm job submission to block until completion

**Severity:** Medium

**Category:** UX

**Reproduction:**
```bash
swarm query --sql @query.sql --seed-file urls.txt --refresh
# Returns immediately with task IDs
# User must manually poll each one
```

**Expected:** Option to wait for all jobs to complete before returning, or at minimum a convenience command like `swarm wait --all` that blocks until all pending jobs are done.

**Actual:** Jobs are submitted asynchronously and the CLI returns immediately. Users must script their own polling loops, which is tedious for 10+ jobs.

**Root Cause:** The swarm is designed for asynchronous operation. While this is correct for long-running jobs, there's no convenience mechanism for short jobs where synchronous behavior would be preferred.

**AI Suggested Improvement:**
- Add `--wait` flag to `swarm query` and `swarm submit` that blocks until all submitted jobs complete
- Add `swarm wait [task-id|--all]` command to block until specific or all jobs complete
- Show a summary table of all job statuses after `--wait` completes

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: Documentation example task IDs don't match reality

**Severity:** Low

**Category:** Documentation

**Reproduction:**
Read swarm.md which shows: `swarm status scrape-task-4` and `swarm result scrape-task-4`.

**Expected:** Documentation examples should use realistic task ID formats that match what users will see.

**Actual:** All documentation uses sequential `scrape-task-N` IDs, but actual task IDs are UUIDs. New users will look for `scrape-task-1` in their output and be confused when they see `ca40ced0-2239-4209-9d81-34bcd50e50c1`.

**Root Cause:** The documentation was written with placeholder IDs that don't match the actual ID generation scheme used by the backend.

**AI Suggested Improvement:**
- Update all documentation examples to use realistic UUID-format IDs
- Or change the backend to generate the documented `scrape-task-N` format
- Use consistent placeholders like `<task-id>` in examples with a note about the actual format

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: `swarm query` help output is minimal — key options not shown

**Severity:** Low

**Category:** Discoverability

**Reproduction:**
```bash
cargo run -- help | grep -A2 "swarm query"
```
Output: `swarm query <url> — Submit an X-SQL query to extract structured data from a loaded webpage`

**Expected:** The help output should list `--sql`, `--seed-file`, `--refresh`, `--deadline`, `--expires` as options under `swarm query`.

**Actual:** The top-level help only shows a one-line description. Users must know to run `swarm query --help` to see the available options. This is consistent with CLI conventions but makes discovery harder for users scrolling through the main help.

**Root Cause:** The main help intentionally shows only summaries; detailed options require per-command `--help`. However, for commands with required options like `--sql`, surfacing this in the summary would improve discoverability.

**AI Suggested Improvement:**
- Include `--sql <query>` in the main help summary for `swarm query` since it's required
- Add `[options]` hint to the summary line: `swarm query <url> [--sql, --seed-file, --refresh]`

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
**Fully completed.** All 10 steps of the swarm workflow executed successfully:
- Swarm session created with custom parallelism settings
- 10 X-SQL extraction jobs submitted and completed (100% success rate)
- 1 plain scrape job submitted for comparison
- All results retrieved with correct structured data
- Session properly closed

### Estimated Task Success Rate
**100%** — Every job completed with `statusCode: 200` and returned the expected extracted fields. No retries needed. No failures encountered.

### Number of Issues Found
**10 issues** (2 High, 4 Medium, 4 Low)

### Major Blockers
None. The swarm workflow works reliably for the tested workload. The issues found are about usability, discoverability, and documentation — not about core functionality failures.

### Most Confusing Aspects
1. **Task ID format mismatch** — Documentation shows `scrape-task-4` but real IDs are UUIDs
2. **`swarm list` gap** — Query tasks don't appear in the task list; users can lose track of their jobs
3. **File path resolution** — Working directory behavior with `cargo run` requires trial and error
4. **No synchronous wait** — Must manually poll each job; no `--wait` convenience flag

### Most Valuable Improvements
1. Fix `swarm list` to show all submission types (highest impact, currently broken UX)
2. Add `--wait` flag to block until jobs complete (biggest quality-of-life improvement)
3. Human-readable task IDs or shorter prefixes (reduces friction for every status check)
4. Stale task cleanup in `swarm list` (keeps output clean and relevant)

### Overall Usability Rating
**7/10**

The swarm feature is functionally solid — all jobs completed correctly, extraction was accurate, and the parallel execution worked as expected. The X-SQL query language is powerful and well-documented. However, the usability is held back by several papercuts: non-discoverable task IDs, incomplete task listing, confusing file paths, and the absence of synchronous convenience features. These issues don't prevent task completion but add unnecessary friction to what should be a smooth workflow.

**Strengths:**
- X-SQL extraction is powerful and produces correctly structured output
- Parallel execution works transparently
- Documentation (SKILL.md + references) is comprehensive and well-organized
- Session lifecycle is clean (create → use → close)
- `@file` syntax for SQL files avoids shell escaping headaches

**Weaknesses:**
- Task discovery and management is fragmented (`swarm list` incomplete)
- UUID task IDs are user-hostile for interactive use
- No built-in wait/synchronization mechanism
- File path behavior is environment-dependent and poorly documented
- Image URLs are returned as relative paths
