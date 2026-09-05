# Issues: crawl-sql-formats

> **Source:** `20260902-195619-crawl-sql-formats.full.md` | **Date:** 20260902-195619 | **Mode:** dev

## Scenario Background

### Task

**Task outcome: SUCCESSFUL — both acceptance criteria met, and the X-SQL crawl workflow works end-to-end.**

- **AC5 (SQL from file + CSV output):** ✅ `crawl --seed-file seeds.txt --sql @extract.sql --format csv -o results.csv` completed with "2 pages crawled, 2 rows extracted. Results written to …/results.csv". The CSV contains the header row `url,title,price` plus 2 data rows: `Widget Alpha / $10.00` and `Widget Beta / $20.00`, one per seed URL. Two runs produced byte-identical CSVs.
- **AC6 (SQL from stdin + table format):** ✅ `crawl --seed-file seeds.txt --sql-stdin --format table < extract.sql` produced an aligned grid (`url | title | price`) with the same column headers and identical extracted content (Widget Alpha/$10.00, Widget Beta/$20.00) as the CSV run.
- **Step 4 (comparison):** ✅ Re-ran the file-based crawl → `results2.csv` is byte-identical to `results.csv`, and both CSV rows match the table-format run cell-for-cell. Extraction is deterministic and independent of how the SQL was provided.

All files were kept under `.test-sessions/crawl-xsql/`. The local MockSite (`localhost:18080`) served the fixture pages, and the CLI daemon auto-started the locally-built backend without any manual setup.

### Execution Context

1. **Preparation:** Verified repo root (`D:/workspace/Browser4/Browser4-4.13`), created `.test-sessions/`, confirmed MockSite up (HTTP 200) and fixture pages contain `<h1 id="productTitle">` / `<p id="product-price">` (Widget Alpha — $10.00, Widget Beta — $20.00).
2. **Documentation study:** Ran `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` fully, read `references/crawl.md`, and read `--help crawl` output. The `crawl` command's help and crawl.md both document X-SQL extraction, `--sql @file`, `--sql-stdin`, `--format` and contain examples that match the acceptance criteria verbatim.
3. **Scaffolding (`.test-sessions/crawl-xsql/`):** Created `seeds.txt` (2 product URLs) and `extract.sql` (`SELECT DOM_BASE_URI(dom) AS url, DOM_FIRST_TEXT(dom, '#productTitle') AS title, DOM_FIRST_TEXT(...

(truncated — see full.md for complete trace)

---

## Issues Found (4 issues)

### Issue 1: b4w.ps1 wrapper swallows the CLI exit code — every failed command reports success

**Severity:** High
**Category:** Reliability

#### Reproduction

Run any failing command through the documented dev wrapper and check $?: (1) ./b4w.ps1 crawl --seed-file .test-sessions/crawl-xsql/seeds.txt --format xml > /dev/null 2>&1; echo $?  → prints 0 (2) ./b4w.ps1 crawl --seed-file .test-sessions/crawl-xsql/no-such-seeds.txt --sql-stdin --format table < extract.sql > /dev/null 2>&1; echo $?  → prints 0 (3) ./b4w.ps1 crawl --seed-file seeds.txt --sql @.test-sessions/crawl-xsql/does-not-exist.sql > /dev/null 2>&1; echo $?  → prints 0. Same commands against cli/browser4-cli/target/debug/browser4-cli.exe directly print 1. b4w.sh and b4w.bat delegate to b4w.ps1 and inherit the same behaviour.

#### Expected Behavior

The wrapper should propagate the CLI's non-zero exit code so scripts, CI, and &&-chains can detect failure. Error messages even print a documented 'Invalid --format' message, yet the process reports success.

#### Actual Behavior

b4w.ps1 prints the error message but exits 0 in every failure case observed (invalid --format, missing seed file, missing SQL file). The CLI binary itself correctly exits 1 for the same inputs.

#### Root Cause Analysis

In b4w.ps1 the final invocation block (lines ~823-839) runs the exe via Invoke-Expression and then ends with Set-Location $OriginalCwd — there is no 'exit $LASTEXITCODE' after invoking the binary. A PowerShell script that ends without an explicit exit returns 0 unless a terminating error occurred, so the native command's exit status is discarded. b4w.sh 'exec pwsh -File b4w.ps1' and b4w.bat 'exit /b %ERRORLEVEL%' both inherit pwsh's exit code, so every documented wrapper invocation path is affected.

#### Code Pointer

`b4w.ps1:823-839 (final exe invocation block; add 'exit $LASTEXITCODE' after Invoke-Expression). Check whether the subcommand delegation branches (coworker/test/sc/build) that already do 'exit $LASTEXITCODE' confirm the intended pattern.`

#### AI Suggested Improvement

- Add 'exit $LASTEXITCODE' after the Invoke-Expression in the final CLI invocation block of b4w.ps1 (before/after Set-Location restore).
- Add a regression check in the repo's script tests asserting that a failing CLI invocation via ./b4w.ps1 and ./b4w.sh propagates a non-zero code.
- Consider the same audit for the direct `& $Exe` path (no-args case) and any other wrapper scripts.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified at b4w.ps1:823-839 — the final invocation block ends with a successful `Set-Location $OriginalCwd` and no `exit $LASTEXITCODE`, unlike every subcommand branch in the same file (lines 62, 100, 155, 196, 233, 247, 264, 283, 297), so pwsh exits 0 and b4w.sh/b4w.bat inherit false success. High severity is fair since the wrapper is the documented dev entry point and CI/`&&`-chains can't detect failure. Fix should also cover the `cargo run` fallback and no-args paths.

---

### Issue 2: crawl list table: header and separator misaligned with data columns when task IDs exceed the capped column width

**Severity:** Low
**Category:** UX

#### Reproduction

Run several crawls, then: ./b4w.ps1 crawl list. Observe that the 'TASK ID' header column and its dashed separator are ~12 chars wide while every data row's 36-char UUID spills past them, so COMMAND/DESCRIPTION/STARTED headers do not line up with their data columns.

#### Expected Behavior

Header, separator, and data rows should share the same column widths so columns line up vertically.

#### Actual Behavior

Header row and separator are visibly misaligned with the data rows (header says the ID column is ~12 wide; data cells are 36 chars, shifting all subsequent columns right). Data rows align with the separator, but the header does not.

#### Root Cause Analysis

In format_async_task_list the column width for the ID is computed as task_id length capped: 'let id_w = page.iter().map(|t| t.task_id.len()).max().unwrap_or(8).max(8).min(12);' — capped at 12 with a comment '(capped for readability)', but the data cell is formatted with the full 36-char UUID and no truncation (unlike desc_w which truncates with an ellipsis). Header and separator are then rendered with the same {:<id_w$} width, so they reserve 12 chars while data occupies 36, breaking alignment for every row.

#### Code Pointer

`cli/browser4-cli/src/state.rs:1076 in format_async_task_list (id_w cap) and the header/separator rendering at state.rs:1094-1111.`

#### AI Suggested Improvement

- Remove the .min(12) cap for id_w (UUIDs are fixed 36 chars; the width is predictable), or truncate displayed task IDs to the capped width with an ellipsis like the description column does.
- Alternatively compute widths over headers+separator+data together so any cap is applied consistently to all three rows.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed at state.rs:1076 — `id_w` is capped `.min(12)` while the data row (state.rs:1135-1148) formats the full untruncated `task_id`, and desc_w's ellipsis truncation (1121-1123) shows the author already had a truncation idiom for exactly this problem. Since UUIDs are fixed 36 chars, dropping the cap or truncating IDs with an ellipsis restores alignment cheaply.

---

### Issue 3: Raw OS-locale error text leaks into English CLI error messages, and file-error styles are inconsistent

**Severity:** Low
**Category:** UX

#### Reproduction

On a Chinese-locale Windows: ./b4w.ps1 crawl --seed-file .test-sessions/crawl-xsql/no-such-seeds.txt --sql-stdin --format table < .test-sessions/crawl-xsql/extract.sql → prints 'Error: Failed to read seed file '.test-sessions/crawl-xsql/no-such-seeds.txt': 系统找不到指定的文件。 (os error 2)'. Compare with a missing SQL file: 'Failed to read file ...\n  Tried: <absolute path>' (no 'Error:' prefix, but friendlier and locale-independent).

#### Expected Behavior

Consistent, locale-independent English error messages for user-facing failures, e.g. 'Error: Failed to read seed file '...': file not found (tried: <abs path>)'.

#### Actual Behavior

The seed-file error embeds the raw Rust io::Error Display text, which on Chinese-locale Windows surfaces as '系统找不到指定的文件。 (os error 2)'. The missing-SQL-file error (resolve_sql_file) uses clean English and even lists the paths tried, but lacks the 'Error:' prefix used by other errors. Two neighbouring file-read failures thus produce stylistically different messages, one of which is not portable.

#### Root Cause Analysis

handle_crawl (and the swarm submit/query handlers) map the read failure directly: std::fs::read_to_string(file_path).map_err(|e| format!("Failed to read seed file '{}': {}", file_path, e)) — the io::Error Display string includes the OS message in the system locale. resolve_sql_file instead swallows the raw error and emits a curated 'Tried:' hint. Both sites should share one helper.

#### Code Pointer

`cli/browser4-cli/src/main.rs:10896 (handle_crawl, seed-file read); identical pattern at main.rs:9612 (handle_swarm_submit) and main.rs:9792 (handle_swarm_query); contrast with main.rs:6287 (resolve_sql_file).`

#### AI Suggested Improvement

- Map the failure through error.kind(): report 'file not found' for io::ErrorKind::NotFound instead of printing the raw OS string, and keep the raw error only in a verbose/--json channel.
- Reuse resolve_sql_file's 'Tried: <paths>' hint style for seed files, and normalize the prefix ('Error:') across both messages.
- Add a cross-locale test asserting the error output contains no non-ASCII text.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified — main.rs:9612/9792/10896 embed the raw `io::Error` Display string (OS message in system locale) for seed-file reads, while the missing-file path of resolve_sql_file (main.rs:6286-6291) emits a curated, locale-independent "Tried:" message. One shared helper mapping `error.kind()` to stable English text plus a cross-locale test is the right fix; note resolve_sql_file's own absolute-path branch (main.rs:6253-6255) also embeds the raw error, so it should use the same helper.

---

### Issue 4: Crawl progress output repeats identical lines on every poll, reading as stuck or spammy

**Severity:** Low
**Category:** UX

#### Reproduction

Run a crawl of 2 seed pages and watch stdout: ./b4w.ps1 crawl --seed-file .test-sessions/crawl-xsql/seeds.txt --sql @.test-sessions/crawl-xsql/extract.sql --format csv -o .test-sessions/crawl-xsql/results.csv. During the ~40s run the terminal prints the same 'Crawling... 1 pages found so far' line ~12 times and repeats the identical 'Crawling... 1/2 seeds done, 1 pages found, 1 rows extracted (...)' summary at 16s/26s/36s with no new information between repetitions.

#### Expected Behavior

Progress output that changes only when state changes (or a single updating line/spinner), plus the final summary — so a user can tell the crawl is progressing without reading duplicate lines.

#### Actual Behavior

Each poll iteration prints regardless of whether anything changed: a heartbeat line 'Crawling... N pages found so far' every poll while a page loads (~20s per page), and the full per-seed summary line repeats verbatim (only the elapsed seconds differ) while the second page is still being fetched.

#### Root Cause Analysis

The polling loop prints on every status check: the else-branch prints 'Crawling... {} pages found so far' whenever pages_found > 0 (main.rs:11452), and the extraction-progress branch prints the '{}/{} seeds done, ...' summary (main.rs:11186) whenever the previous poll also had extracted rows — neither branch tracks whether its output actually changed since the last print. Polling every few seconds therefore duplicates lines.

#### Code Pointer

`cli/browser4-cli/src/main.rs:11452 and cli/browser4-cli/src/main.rs:11186 (crawl foreground progress loop in handle_crawl).`

#### AI Suggested Improvement

- Track last-printed (pages_found, seeds_done, rows_extracted) and only print when one of them changes.
- Use a single self-updating line (carriage return) when stdout is a TTY, falling back to change-only prints when piped.
- Rate-limit the 'pages found so far' heartbeat (e.g. at most every 10-15s) so a 20s page load yields one line, not twelve.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Confirmed — the "still running" arm at main.rs:11452 prints on every poll with no rate limit or change gate, and the per-seed summary at main.rs:11185 is interval-gated but not change-gated, so identical lines repeat while a page loads. Change-tracking or a TTY-only updating line, as suggested, is the correct minimal fix.

---

## Overall Assessment

**Completion Status:** Successful — both acceptance criteria (AC5: SQL-from-file + CSV output file; AC6: SQL-from-stdin + table output) verified with correct extraction, byte-identical repeat CSV runs, and content-identical CSV-vs-table results. The usability evaluation was completed alongside with 4 issues recorded.

**Success Rate:** 100%

**Issues Found:** 4

**Major Blockers:** None — the task flow worked end-to-end on the first attempt for every acceptance criterion. The exit-code swallowing in the dev wrapper (Issue 1) is the most serious defect found but did not block the task since failures were detected via error messages and direct-binary checks.

**Most Confusing Aspects:** For a first-time user: (1) every failed command exits 0 through the documented ./b4w.ps1 invocation, so shell-level success detection silently lies — the error text is the only signal; (2) the raw OS error string in Chinese ('系统找不到指定的文件。') inside an English error is jarring on non-English Windows; (3) ~40s of repetitive, unchanged progress lines for a 2-page crawl gives no clear sense of progress or ETA; (4) the crawl list table header misalignment makes the listing look broken at a glance. Positively, the crawl help text, SKILL.md, and crawl.md were easy to discover and contained examples matching the task verbatim, and the auto-started daemon/backend required zero setup.

**Most Valuable Improvements:** 1) Propagate the CLI exit code through b4w.ps1 (exit $LASTEXITCODE) so wrapper invocations are script-safe. 2) Fix the crawl list table column widths so header/separator/data align. 3) Localize-proof and unify file-error messages (seed file vs --sql @file). 4) Deduplicate/rate-limit crawl progress polling output.

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

#### Issue 1: b4w.ps1 wrapper swallows the CLI exit code — every failed command reports success

Run any failing command through the documented dev wrapper and check $?: (1) ./b4w.ps1 crawl --seed-file .test-sessions/crawl-xsql/seeds.txt --format xml > /dev/null 2>&1; echo $?  → prints 0 (2) ./b4w.ps1 crawl --seed-file .test-sessions/crawl-xsql/no-such-seeds.txt --sql-stdin --format table < extract.sql > /dev/null 2>&1; echo $?  → prints 0 (3) ./b4w.ps1 crawl --seed-file seeds.txt --sql @.test-sessions/crawl-xsql/does-not-exist.sql > /dev/null 2>&1; echo $?  → prints 0. Same commands against cli/browser4-cli/target/debug/browser4-cli.exe directly print 1. b4w.sh and b4w.bat delegate to b4w.ps1 and inherit the same behaviour.

#### Issue 2: crawl list table: header and separator misaligned with data columns when task IDs exceed the capped column width

Run several crawls, then: ./b4w.ps1 crawl list. Observe that the 'TASK ID' header column and its dashed separator are ~12 chars wide while every data row's 36-char UUID spills past them, so COMMAND/DESCRIPTION/STARTED headers do not line up with their data columns.

#### Issue 3: Raw OS-locale error text leaks into English CLI error messages, and file-error styles are inconsistent

On a Chinese-locale Windows: ./b4w.ps1 crawl --seed-file .test-sessions/crawl-xsql/no-such-seeds.txt --sql-stdin --format table < .test-sessions/crawl-xsql/extract.sql → prints 'Error: Failed to read seed file '.test-sessions/crawl-xsql/no-such-seeds.txt': 系统找不到指定的文件。 (os error 2)'. Compare with a missing SQL file: 'Failed to read file ...\n  Tried: <absolute path>' (no 'Error:' prefix, but friendlier and locale-independent).

#### Issue 4: Crawl progress output repeats identical lines on every poll, reading as stuck or spammy

Run a crawl of 2 seed pages and watch stdout: ./b4w.ps1 crawl --seed-file .test-sessions/crawl-xsql/seeds.txt --sql @.test-sessions/crawl-xsql/extract.sql --format csv -o .test-sessions/crawl-xsql/results.csv. During the ~40s run the terminal prints the same 'Crawling... 1 pages found so far' line ~12 times and repeats the identical 'Crawling... 1/2 seeds done, 1 pages found, 1 rows extracted (...)' summary at 16s/26s/36s with no new information between repetitions.

