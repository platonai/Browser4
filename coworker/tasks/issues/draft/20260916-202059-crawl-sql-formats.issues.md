# Issues: crawl-sql-formats

> **Source:** `20260916-202059-crawl-sql-formats.full.md` | **Date:** 20260916-202059 | **Mode:** dev

## Scenario Background

### Task

**Successful.** Both acceptance criteria pass, plus the step-4 equality check.

| AC | Result |
|----|--------|
| **AC5** — `--sql @file` + CSV | **PASS.** Exit 0, `results.csv` (163 B) with header `url,title,price` and 2 data rows: `Widget Alpha`/`$10.00`, `Widget Beta`/`$20.00` |
| **AC6** — `--sql-stdin` + table | **PASS.** Exit 0, aligned table with matching headers and the same two rows |
| **Step 4** — re-run and compare | **PASS.** All three outputs (CSV ×2, table ×1) are equal after parsing |

All three runs reported `servedFromStore: false` with identical content lengths (6032/5982), so the equality check compares three independent fresh fetches, not cache hits.

### Execution Context

**Key Commands:**

**Steps:** Read `SKILL.md` + `references/crawl.md` → confirmed MockSite pages (`#productTitle`, `#product-price`) → AC5 → verified CSV → AC6 via stdin redirect → verified table → re-ran AC5 to `results2.csv` → parsed all three outputs in Python and asserted equality → probed error paths, stdin edge cases, and the sibling **`htmlsnapshot query`** command for error-semantics comparison.

**Decisions:** Ran every command from the repo root as instructed, passing explicit relative paths into the scratch dir (e.g. `--sql "@.test-sessions/…/extract.sql"`). Wrote no files to the repo root.

**Workarounds:** None required for the acceptance criteria. MockSite was already running on 18080 (PID 58160), so the launcher's "port in use" error was benign — I verified the existing instance served the target pages correctly rather than killing it.

**Notable positives:** `crawl --help` and `references/crawl.md` were accurate for every documented flag and format I exercised. Genuine local failures fail fast and well — a missing `@file` and an empty `--sql-stdin` both exit 1 with actionable messages and no hang. The unquoted `@file` form also works correctly through `./b4w.ps1` from bash, despite the PowerShell-splatting warning in the docs.

---

---

## Issues Found (5 issues)

### Issue 1: crawl --sql-base64 <value> is silently ignored — crawl runs with no extraction and exits 0

**Severity:** High
**Category:** Product

#### Reproduction

B64=$(base64 -w0 extract.sql)
./b4w.ps1 crawl --seed-file seed.txt --sql-base64 "$B64" --format csv

Contrast with the legacy boolean form, which works:
./b4w.ps1 crawl --seed-file seed.txt --sql "$B64" --sql-base64 --format csv

#### Expected Behavior

The value form decodes the base64 and runs the X-SQL extraction, exactly as `htmlsnapshot query --sql-base64 "$B64"` does (verified working). crawl should print `X-SQL extraction: enabled` and emit the extracted rows.

#### Actual Behavior

The flag is dropped entirely. The CLI prints `Warning: --format CSV has no effect without --sql.`, submits the crawl with no `--sql`, and ends with `Crawl completed. 0 pages found.` plus link-discovery tips — exit code 0. No error is reported for the ignored flag. The workaround is to re-encode the invocation as `--sql <b64> --sql-base64`, which succeeds.

#### Root Cause Analysis

In `handle_crawl`, the query is resolved only from the `sql` param: `let query_raw = ... tool_params.get("sql") ...;` followed by `let has_sql = query_raw.is_some();`. A non-empty `sqlBase64` string never sets `query_raw`, so `has_sql` is false and `maybe_decode_base64_sql` is never reached (it is only called in the `Some(q)` arm of the `match`). The sibling `htmlsnapshot query` path was fixed for exactly this case and has a guard that sets `has_sql_base64_value` and permits an empty `sql_raw`; that fix was never mirrored into `handle_crawl`, which has no equivalent guard and therefore degrades to a silent no-op rather than an error. Because `maybe_decode_base64_sql` checks the direct-value mode first, a fix that seeds `query_raw` with `Some(String::new())` when `sqlBase64` is a non-empty string will decode correctly.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_crawl() — the `let query_raw` / `let has_sql = query_raw.is_some();` block at ~line 13613-13618. Mirror the guard at cli/browser4-cli/src/main.rs:7855-7865.`

#### AI Suggested Improvement

- In `handle_crawl`, treat a non-empty string `sqlBase64` as SQL present: set `query_raw = Some(String::new())` (or an explicit Option) when `tool_params["sqlBase64"]` is a non-empty string, so `has_sql` is true and `maybe_decode_base64_sql` decodes it.
- If neither `sql` nor `sqlBase64` yields SQL, keep the existing `--sql is required` error rather than silently proceeding without extraction.
- Add a regression test alongside the existing `maybe_decode_base64_sql` unit tests asserting that `handle_crawl`-style param resolution with only `sqlBase64` set produces `has_sql == true`.
- Audit the other command handlers that accept `--sql-base64` (the swarm/query blocks in commands.rs) for the same missing standalone-base64 handling.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: htmlsnapshot query silently swallows X-SQL syntax errors: prints "No data", exits 0

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 htmlsnapshot query "http://localhost:18080/generated/crawl/product/1.html" --sql "SELECT FROM WHERE bogus" --format table

Compare the machine-readable envelope:
./b4w.ps1 htmlsnapshot query "http://localhost:18080/generated/crawl/product/1.html" --sql "SELECT FROM WHERE bogus" --json

#### Expected Behavior

The command reports the X-SQL syntax error and exits nonzero, consistent with the documented exit-code contract in SKILL.md §4e ('exits nonzero when the server returns an error envelope … so scripts can detect failure without parsing the JSON').

#### Actual Behavior

Human-readable mode prints `No data.` / `0 rows returned.` followed by generic shell-quoting tips about CSS selectors — no mention of the error at all. Exit code 0. The --json envelope contains full knowledge of the failure (inner `statusCode: 400`, `status: "Bad Request"`, `message: "X-SQL syntax error: Syntax error in SQL statement ..."`) wrapped in an outer envelope of `"status":"ok"`, still exit 0. A user who mistypes a query is told their result set is empty and is nudged toward inspecting selectors, actively misdirecting them away from the real cause.

#### Root Cause Analysis

Two status checks in `handle_html_snapshot_query` classify only 417 and 5xx as failures, but this backend returns HTTP 400 for an X-SQL engine error. The exit-code mapping at the end of the handler is `if status_code == 417 || (status_code >= 500 && result_set_empty) { return Err(...) }` — a 400 with an empty resultSet matches neither disjunct and falls through to `Ok(())`. The human-readable branch earlier in the same function is likewise gated on `if status_code == 417`, so it also skips the error rendering. The comments above both checks show the author intended to catch H2/X-SQL engine errors ('the query itself failed with an H2/X-SQL engine error that carries a message'), but assumed those arrive as 417; in this build they arrive as 400.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_query() — exit-code mapping block at ~line 8116-8152 (`if status_code == 417 || (status_code >= 500 && result_set_empty)`) and the human-readable status check at ~line 7954 (`if status_code == 417`).`

#### AI Suggested Improvement

- Extend the exit-code predicate to treat any non-2xx `statusCode` carrying a non-empty `message` as a failure, e.g. `status_code == 417 || status_code >= 400 && result_set_empty` — in particular add 400, which is what the engine emits for a syntax error.
- Apply the same predicate to the human-readable branch so the server `message` is printed instead of the generic quoting tips when the query itself failed.
- Do not print selector/quoting tips when a server-side error message is present; they are only appropriate for a query that executed and matched nothing.
- Consider having the CLI's outer envelope report `"status":"error"` when the inner result carries a non-2xx statusCode, so `--json` consumers are not misled by `"status":"ok"`.
- Add a regression test with a 400 + `message` + empty resultSet payload asserting nonzero exit and that the message reaches stderr.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: crawl's X-SQL failure message tells users to re-run with --verbose, but --verbose emits no diagnostics in the --sql path

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 crawl --seed-file seed.txt --sql "SELECT FROM WHERE bogus" --format csv > a.log 2>&1
./b4w.ps1 crawl --seed-file seed.txt --sql "SELECT FROM WHERE bogus" --format csv --verbose > b.log 2>&1
diff <(sed -E 's/[0-9a-f-]{36}/UUID/g; s/\([0-9]+s elapsed\)/(Xs elapsed)/g' a.log) <(sed -E 's/[0-9a-f-]{36}/UUID/g; s/\([0-9]+s elapsed\)/(Xs elapsed)/g' b.log)

#### Expected Behavior

`--verbose` adds per-page diagnostics (which page failed, and with what extractionError) as the help text promises: 'Show per-URL processing status in crawl results.'

#### Actual Behavior

The two outputs are identical after normalizing task IDs and timings — no additional diagnostics. The failure message itself reads: `⚠ X-SQL query failed on 2/2 page(s). Try re-running with --verbose for per-page diagnostics.` The only way to obtain the actual error is to already have the task ID and run `crawl result <taskId>`, which the message does not mention.

#### Root Cause Analysis

The per-page rendering block (`page_lines`, which contains both the `if verbose { Seed URL Status: ... }` section and the per-page `⚠ {extractionError}` lines) lives entirely inside the `else` branch of the `if has_sql { ... } else { ... }` split, i.e. the non-SQL path. When `--sql` is supplied the handler takes the `has_sql` branch, which prints only the one-line summary at line ~14092 and never renders `page_lines`. The flag is therefore structurally unreachable in precisely the case where the message recommends it. The extraction errors are present in the task record (`pages[].extractionError`) and are already parsed from the same JSON, so the data is available at the point the message is built.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_crawl() — the message at ~line 14092, and the `page_lines` / `if verbose` rendering that is confined to the `else` branch starting ~line 14150.`

#### AI Suggested Improvement

- Either render per-page `extractionError` values in the `has_sql` branch when `verbose` is set (the data is already in `parsed["pages"]`), or change the message to name a remedy that works (e.g. print the task ID and point at `crawl result <taskId>`).
- Prefer the former: when every page failed, the underlying reason is the single most useful thing to show, and the CLI already holds it without an extra round-trip.
- Add a test asserting that `--verbose` output for a failing `--sql` crawl differs from non-verbose output.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: crawl exits 0 when X-SQL fails on 100% of pages, so automation cannot detect total extraction failure

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl --seed-file seed.txt --sql "SELECT FROM WHERE bogus" --format csv -o out.csv; echo $?

#### Expected Behavior

A run in which every page's extraction failed yields no data file and no rows; for scripted use this should be distinguishable from success by exit code, consistent with the nonzero-exit convention the CLI already applies to X-SQL failures elsewhere.

#### Actual Behavior

Exit code 0, with `No extracted data.` on stdout and no `out.csv` written. Success (2 rows), a valid query matching nothing (`⚠ X-SQL returned 2 rows but all fields are empty`), and a total query failure (`⚠ X-SQL query failed on 2/2 page(s)`) are all exit 0 and require parsing the warning text to tell apart. `crawl result <taskId>` for the failed run also reports status `OK — 2 page(s) found` with no top-level failure indication; the errors appear only in `pages[].extractionError`.

#### Root Cause Analysis

The crawl success classification is based on the task status alone (`OK`/`SC_OK`), which the backend sets because all pages were fetched — the fetch succeeded even though extraction did not. The CLI computes `extraction_error_count` and prints a distinct warning for it, but never propagates it to the exit code, and the docs' error-handling table documents only per-page tolerance ('X-SQL failure on one page | Page logged with error; other pages continue normally') without defining behaviour for the all-pages-failed case.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_crawl() — the `extraction_error_count` computation and summary block at ~line 14076-14102.`

#### AI Suggested Improvement

- Exit nonzero when `extraction_error_count > 0 && all_extracted.is_empty()` — extraction failed on every page and produced nothing, which is never a useful success.
- Keep exit 0 for the legitimate 'query ran, matched nothing' case (empty fields) so the two conditions stay distinguishable to scripts.
- Document the chosen exit code in the crawl error-handling table in skills/browser4-cli/references/crawl.md, which currently does not state an exit code for X-SQL failure.
- Optionally surface the first `extractionError` in the summary block rather than only via `crawl result`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: --sql-base64 is documented with two contradictory signatures; --help describes the one that does not work

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 help crawl | grep -A1 sql-base64
grep -n 'sql-base64' skills/browser4-cli/references/crawl.md

#### Expected Behavior

The two surfaces agree on one signature, and it is the one that works.

#### Actual Behavior

`help crawl` documents `--sql-base64 <base64>` ('Base64-encoded X-SQL query'), matching the CLI's own source comment describing direct-value mode as the primary form. `references/crawl.md` documents it as type `bool` ('Base64-decode the query value before execution'), i.e. the legacy `--sql <b64> --sql-base64` form. Only the boolean form actually functions for `crawl` (see the first issue), so a user following `--help` — the primary discoverability surface — is led directly to a silent no-op, while the reference doc happens to describe the only working invocation.

#### Root Cause Analysis

The CLI grew two modes for the flag (direct-value and legacy boolean) and the option is declared `is_bool: false` in the command registry, while crawl.md was written against the boolean semantics and never updated. Compounding this, `handle_crawl` implements only enough of the contract to accept the value into params, without the standalone handling that `htmlsnapshot query` received.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2909 (option definition) and cli/browser4-cli/src/main.rs:7689-7725 (mode documentation comment); doc side: skills/browser4-cli/references/crawl.md, 'X-SQL extraction flags' table.`

#### AI Suggested Improvement

- Decide one canonical signature — the direct-value `--sql-base64 <base64>` is the better one, since it needs no second flag — and document it identically in `--help` and crawl.md.
- Fix `handle_crawl` so the canonical form works (see the first issue) before or alongside the doc change; otherwise the corrected doc will describe broken behaviour.
- Keep the legacy boolean form working for backwards compatibility, but mark it as deprecated in both surfaces.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — AC5 (SQL from file, CSV output) and AC6 (SQL from stdin, table output) both pass, and the step-4 re-run comparison confirms all three outputs are identical in content. Verified programmatically (parsed CSV and table into dicts and asserted equality) and confirmed that all three runs fetched fresh rather than serving cached pages.

**Success Rate:** 100% of the required task steps (AC5, AC6, and the step-4 comparison) succeeded on the first attempt; no workarounds were needed for the acceptance criteria.

**Issues Found:** 5

**Major Blockers:** None for the assigned task. Both blockers found were in adjacent paths I probed rather than in the acceptance criteria themselves: `crawl --sql-base64 <value>` silently no-ops (so the third documented X-SQL input method is unusable), and `htmlsnapshot query` reports a failed query as an empty result with exit 0. The two methods the task actually requires — `--sql @file` and `--sql-stdin` — both worked correctly and identically.

**Most Confusing Aspects:** The two X-SQL input paths that are documented as equivalent are not: `--sql @file` and `--sql-stdin` behave identically (as documented), but `--sql-base64` silently does nothing in crawl while the same flag works in `htmlsnapshot query`. Second, a failed extraction is reported as a successful run — exit code 0 whether the query returned rows, matched nothing, or failed to parse — so the only way to tell is to read the warning prose, and the warning in the crawl case points at a `--verbose` flag that produces no extra output. Third, `--sql-base64` has two contradictory signatures across `--help` and the reference doc, and the one shown in `--help` is the broken one.

**Most Valuable Improvements:** 1) Make `handle_crawl` accept a standalone `--sql-base64 <value>` the way `handle_html_snapshot_query` already does — never let a supplied flag be silently dropped; if SQL cannot be resolved, fail with the existing `--sql is required` error. 2) Classify any non-2xx X-SQL statusCode as a failure in `htmlsnapshot query` (the backend returns 400 for syntax errors, which the current 417/5xx-only predicate misses) so the error message reaches the user and the exit code is nonzero. 3) Render per-page `extractionError` values in the `--sql` branch when `--verbose` is set — the data is already parsed — instead of pointing users at a flag that does nothing. 4) Exit nonzero when extraction failed on every page and produced no rows, while keeping exit 0 for a query that legitimately matched nothing.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: crawl --sql-base64 <value> is silently ignored — crawl runs with no extraction and exits 0

B64=$(base64 -w0 extract.sql)
./b4w.ps1 crawl --seed-file seed.txt --sql-base64 "$B64" --format csv

Contrast with the legacy boolean form, which works:
./b4w.ps1 crawl --seed-file seed.txt --sql "$B64" --sql-base64 --format csv

#### Issue 2: htmlsnapshot query silently swallows X-SQL syntax errors: prints "No data", exits 0

./b4w.ps1 htmlsnapshot query "http://localhost:18080/generated/crawl/product/1.html" --sql "SELECT FROM WHERE bogus" --format table

Compare the machine-readable envelope:
./b4w.ps1 htmlsnapshot query "http://localhost:18080/generated/crawl/product/1.html" --sql "SELECT FROM WHERE bogus" --json

#### Issue 3: crawl's X-SQL failure message tells users to re-run with --verbose, but --verbose emits no diagnostics in the --sql path

./b4w.ps1 crawl --seed-file seed.txt --sql "SELECT FROM WHERE bogus" --format csv > a.log 2>&1
./b4w.ps1 crawl --seed-file seed.txt --sql "SELECT FROM WHERE bogus" --format csv --verbose > b.log 2>&1
diff <(sed -E 's/[0-9a-f-]{36}/UUID/g; s/\([0-9]+s elapsed\)/(Xs elapsed)/g' a.log) <(sed -E 's/[0-9a-f-]{36}/UUID/g; s/\([0-9]+s elapsed\)/(Xs elapsed)/g' b.log)

#### Issue 4: crawl exits 0 when X-SQL fails on 100% of pages, so automation cannot detect total extraction failure

./b4w.ps1 crawl --seed-file seed.txt --sql "SELECT FROM WHERE bogus" --format csv -o out.csv; echo $?

#### Issue 5: --sql-base64 is documented with two contradictory signatures; --help describes the one that does not work

./b4w.ps1 help crawl | grep -A1 sql-base64
grep -n 'sql-base64' skills/browser4-cli/references/crawl.md

