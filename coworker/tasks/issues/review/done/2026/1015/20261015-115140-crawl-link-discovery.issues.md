# Issues: crawl-link-discovery

> **Source:** `20261015-115140-crawl-link-discovery.issues.json` | **Date:** 20261015-115140 | **Mode:** dev

## Scenario Background

### Task

All nine requested steps were executed against `http://books.toscrape.com/` (which 301-redirects to `https://`). Every crawl completed; the intended artifacts were produced.

| # | Step | Outcome |
|---|------|---------|
| 1 | Depth-1 crawl | ✔ Task `38d6aced-…-ac442e7f0887`, 10 pages found, 10 links discovered, ~37 s, exit 0 |
| 2 | `--out-link-selector` (catalogue links) | ✔ `-ol "a[href*='catalogue']"` — matched the site's relative `catalogue/...` hrefs |
| 3 | `--out-link-pattern ".*catalogue.*"` | ✔ Applied without shell mangling (no leading `/`) |
| 4 | `--top-links 10` | ✔ Exactly `10 link(s) discovered`; default would have been 20 |
| 5 | Table-format output | ✔ Default output = `depth=1 \| url \| title` listing; an aligned table was also produced via `--format table --sql` |
| 6 | CSV to file via `--output` | ✔ **required adding `--sql`** — plain `--format csv -o file` is refused pre-flight (exit 1) |
| 7 | JSON output | ✔ `--format json` (with `--sql`) → pretty JSON array; the global `--json` flag also yields a JSON envelope without `--sql` |
| 8 | Seed file + `crawl --seed-file` | ✔ 3 known book detail URLs (`a-light-in-the-attic_1000`, `tipping-the-velvet_999`, `soumission_998`), depth 0, 3 pages, exit 0, ~5 s |
| 9 | `crawl list` | ✔ 7 tracked tasks at that point, all "completed"; the extraction-failure run (exit 6) is mislabeled as completed (Issue 3) |

The CSV artifact (`crawl2.csv`) contains 10 rows with `url,title`; the JSON artifact (`crawl3.json.out`) contains the same 10 rows as objects. All scratch artifacts are under `.test-sessions/20261015T0901431699046Z/`.

**Fidelity note:** steps 6–7 as literally written ("same crawl, CSV/JSON via `--format`, save with `--output`") do not work without `--sql` — this build's `--format csv|json` renders only X-SQL result sets. I satisfied the task's intent by adding a minimal X-SQL query (`crawl-query.sql`: `SELECT DOM_BASE_URI(dom) AS url, DOM_DOC_TITLE(dom) AS title FROM DOM_LOAD_AND_SELECT(@url, ':root')`). Also worth noting: with `-ol "a[href*='catalogue']"` + `--top-links 10`, the budget is filled by the sidebar's *category* links (first in document order), so no book *detail* page is ever crawled — expected for the literal selector, but surprising if you read "top links" as "most relevant links".

### Execution Context

**Key Commands:**

**Preparation:** `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` and `references/crawl.md` in full, `./b4w.ps1 crawl --help`, `./b4w.ps1 status`, `AGENTS.md`.

**Main sequence (17 browser4-cli invocations, 9 of them full crawls + 5 pre-flight probes):**

1. `goto "http://books.toscrape.com/"` — auto-opened session `9bfa54f3-…` (headless), redirected to https; then two `eval` calls to enumerate `a[href*='catalogue']` hrefs and confirm document order (sidebar categories first).
2. Crawls (each ~5–46 s; the CLI reported progress every ~10 s):
   - `crawl "<url>" -d 1 -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10` → table/listing, exit 0 (tasks `38d6aced`).
   - `… --format csv -o crawl2.csv` → **refused, exit 1, no file** ("Add --sql, or drop --format…"); same refusal with a `.txt` filename; with `-o` omitted → warning on stderr + plain listing, exit 0.
   - `… --sql @crawl-query.sql --format csv -o crawl2.csv` → first attempt used `DOM_FIRST_TEXT(dom,'title')` (empty: `title` is not inside the `body` row scope) → exit 6 `ok: 0, failed: 10`; `crawl result <id> --verbose` gave the exact per-page reason. Fixed to `DOM_DOC_TITLE(dom)` → exit 0, 10 filled rows.
   - Same crawl with `--format table` and `--format json` → aligned table / JSON array, both exit 0.
   - `--json crawl "<url>" -d 1 …` → single-line JSON envelope (3 077 bytes), exit 0.
   - `crawl --seed-file seed-urls.txt -d 0` (3 book URLs + a `#` comment line) → 3 pages, exit 0.
3. `crawl list` (showed 7 tasks, "Pruned 29 completed tasks from local tracking"), `crawl status <partial-failure id>` (raw record; stderr summary says "OK"), `crawl list --status failed` (no match — see Issue 3).
4. Output-matrix probes with a 1-URL seed file: `--format csv` / `--format json` (± `-o`), and `--json -o probe-json-o.json` (envelope → stdout, plain listing → the `.json` file — Issue 2).

**Decisions / workarounds:** dropped leading-slash values from `-olp` to avoid MSYS path rewriting (per SKILL.md warning); wrote the X-SQL query to a file and used `@file` to avoid quoting issues; used `crawl result --verbose` to diagnose the empty-extraction run; used the global `--json` for JSON output without `--sql`.

---

---

## Issues Found (6 issues)

### Issue 1: Global --json combined with --output writes a non-JSON plain-text listing into the output file (guardrail bypassed)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 --json crawl --seed-file probe-seed.txt -d 0 -o probe-json-o.json
(the seed file holds one URL: http://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html)

#### Expected Behavior

The output file named .json should contain JSON (the envelope that --json produces on stdout), or the command should refuse/warn like the --format json -o path does. The help states --format csv|json with --output but without --sql is refused precisely because such a file 'lies about its content'.

#### Actual Behavior

Exit 0 and no warning: the JSON envelope is printed to stdout, while the -o file receives the human-readable listing ('Crawl completed. 1 page found.' / 'depth=0 | <url> | <title>' / 'Parallelism: …') — a .json file that is not JSON. The identical content is refused (exit 1, no file) when requested via --format json -o x.json.

#### Root Cause Analysis

The guardrail in handle_crawl only inspects the --format option (main.rs:18438: `if !has_sql && (format == "csv" || format == "json")`) and never consults the global --json flag or the output filename. The -o writer for the non-SQL path unconditionally writes the plain listing (write_crawl_output), so the two JSON output channels behave inconsistently.

#### Code Pointer

`cli/browser4-cli/src/main.rs:18433 (format guardrail in handle_crawl) and cli/browser4-cli/src/main.rs:18230 (write_crawl_output)`

#### AI Suggested Improvement

- Apply the same guardrail to the global --json flag: refuse `--json … -o <file>` without --sql, or better, write the JSON envelope into the file instead of the listing.
- Alternatively emit a warning naming the mismatch (like the stdout path of --format csv/json already does).
- Add an output-matrix unit test covering {--json, --format json} × {-o, stdout} × {--sql, no --sql} to lock the contract down.
- Update the --json help wording ('Emit JSON to stdout only') to state what -o does in that combination.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 2: crawl --help says --format csv/json 'has no effect without --sql', but with --output it is a hard refusal; refusal omits the working --json alternative

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 crawl "http://books.toscrape.com/" -d 1 -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10 --format csv -o out.csv
./b4w.ps1 crawl "…" --format json -o out.json
./b4w.ps1 crawl "…" --format csv          # no -o
./b4w.ps1 crawl --help                     # documents '--format … Has no effect without --sql'

#### Expected Behavior

Help should describe the real contract, and the recovery advice should name every supported path. For a JSON listing of a plain crawl, `--json` (global flag) is a supported alternative: `./b4w.ps1 --json crawl "<url>" -d 1 -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10` returns a clean JSON envelope with the pages array.

#### Actual Behavior

Without -o: warning on stderr ('Warning: --format CSV has no effect without --sql…'), crawl proceeds as plain text, exit 0. With -o: pre-flight refusal, exit 1, no file written — regardless of the filename extension (a .txt name is refused too). The refusal message says only 'Add --sql, or drop --format (and use a non-CSV filename)' and never mentions the --json flag, which solves the JSON case without X-SQL.

#### Root Cause Analysis

The runtime guardrail (added at main.rs:18433-18453: warn on stdout because there is no misleading file; refuse when -o would create one) is stricter than the help text, which was not updated and still claims 'no effect'. The refusal message also predates/ignores the global --json envelope path (verified working for both depth 0 and depth 1 crawls), and crawl.md's output-format section repeats only 'Output format: json, csv, or table'.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:4223 (crawl --format help text) and cli/browser4-cli/src/main.rs:18438 (refusal message)`

#### AI Suggested Improvement

- Rewrite the --format help to the actual contract: on stdout it warns and prints the plain listing; combined with --output and without --sql it exits non-zero and writes nothing.
- In the refusal message, add the JSON alternative for the JSON case: 'use the global --json flag (before the command) for a JSON page listing, or add --sql for X-SQL result sets'.
- Mention the global --json path in the crawl --help 'Notes' and in skills/browser4-cli/references/crawl.md (Output formats / Page listing sections), which currently documents --json only in the Modes paragraph.
- Consider allowing --format json on the -o path by writing the same envelope --json produces, eliminating the gap.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 3: crawl list reports a partially failed crawl (exit 6) as 'completed'; --status failed never matches it

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl "http://books.toscrape.com/" -d 1 -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10 --sql "SELECT DOM_BASE_URI(dom) AS url, DOM_FIRST_TEXT(dom,'title') AS title FROM DOM_LOAD_AND_SELECT(@url,'body')" --format csv -o out.csv   # exits 6, stderr: 'crawl completed with errors: ok: 0, failed: 10'
./b4w.ps1 crawl list                    # the task's STATUS column shows 'completed'
./b4w.ps1 crawl list --status failed    # 'No crawl tasks matching status filter 'failed'.'

#### Expected Behavior

The task exit code (6, PartialFailure) is numeric truth; the history view should preserve it. crawl.md promises the local task is stored as 'partial failure (<ok> of <pages> pages ok)', and `crawl list --status failed` should find such tasks.

#### Actual Behavior

The STATUS column shows 'completed' and the failed filter matches nothing, so a user auditing crawl history cannot tell which runs were clean. The documented 'partial failure (0 of 10 pages ok)' label never surfaces anywhere: `crawl status <id>` prints the raw backend record (status OK) plus the stderr summary 'OK — 10 page(s) found'; only `crawl result <id> --verbose` reveals the per-page extractionError rows.

#### Root Cause Analysis

Two interacting gaps: (1) handle_crawl writes the richer label locally (main.rs:19212-19216, update_async_task_status sets last_status = 'partial failure (0 of 10 pages ok)'), but handle_crawl_list unconditionally overwrites last_status with the backend-derived friendly status on every refresh (main.rs:16889-16900: friendly_crawl_status('OK') → 'completed'); (2) even if preserved, crawl_status_filter_matches('failed', 'partial failure …') returns false since the label neither starts with 'failed' nor contains 'error'/'timeout' (main.rs:15597-15608).

#### Code Pointer

`cli/browser4-cli/src/main.rs:16886 (handle_crawl_list status refresh), cli/browser4-cli/src/main.rs:19210 (partial-failure label write), cli/browser4-cli/src/main.rs:15597 (crawl_status_filter_matches)`

#### AI Suggested Improvement

- When refreshing a tracked crawl in handle_crawl_list, do not downgrade a locally recorded partial-failure label: either keep it, or recompute it from the result record (count pages with extractionError / 0-byte content / failedPages) and store e.g. 'failed (partial: 0 of 10 pages ok)'.
- Make crawl_status_filter_matches recognise the partial-failure label ('partial' → matches 'failed').
- Optionally add PAGES OK/FAILED columns to `crawl list` from pages_found/pages_failed so the distinction is visible without a filter.
- Add a regression test: crawl that exits 6 → next `crawl list` shows the failed accounting and matches `--status failed` (alongside the existing tests at main.rs:40181+).

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 4: stdout prints '10 pages crawled, 10 rows extracted.' on a run whose every page failed extraction, right before 'Summary: ok: 0, failed: 10'

**Severity:** Low
**Category:** UX

#### Reproduction

Same run as Issue 3's reproduction (X-SQL query returning empty fields on every page). stdout tail: '10 pages crawled, 10 rows extracted.' then 'Summary: ok: 0, failed: 10'; stderr: 'crawl completed with errors: ok: 0, failed: 10. Use crawl result <id> or --verbose for per-page diagnostics.'

#### Expected Behavior

The stdout summary should not read as plain success when no page produced usable content; the cause should be visible without a second command.

#### Actual Behavior

Two adjacent stdout lines look contradictory ('10 rows extracted' vs 'failed: 10'). The actual reason — 'The X-SQL extracted no data: every field is empty for <url>' — exists per page but is only reachable via `crawl result <id> --verbose` (or the crawl's own --verbose flag).

#### Root Cause Analysis

In handle_crawl the success-path summary is printed before the failure accounting (main.rs:19177-19198), and crawl_failure_counts folds extractionErrors into failed_total without reflecting them in the 'N rows extracted' wording; the per-page extractionError strings are only emitted by the result-record printer.

#### Code Pointer

`cli/browser4-cli/src/main.rs:19177 (page summary print) and cli/browser4-cli/src/main.rs:19197 (crawl_failure_counts accounting)`

#### AI Suggested Improvement

- When failed_total > 0, adjust the rows line, e.g. '10 rows extracted (10 pages with empty/failed extraction)' before the Summary line.
- Echo the first per-page extractionError reason (truncated) in the stderr error message next to the 'use crawl result …' hint.
- Ensure the hint mentions that --verbose on the crawl itself prints the reasons inline (it does, but the wording currently only points at `crawl result`).

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 5: crawl --parallel help and the completion report use different vocabulary; 'peak 1 unit(s) in flight' is unexplained

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 crawl "http://books.toscrape.com/" -d 1 -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10
# header: 'Parallel tabs: server default'; footer: 'Parallelism: budget 4 tab(s), peak 1 unit(s) in flight'
./b4w.ps1 crawl --help   # '--parallel <n>  Collect up to <n> pages/tabs at the same time (default: 4, 1 = sequential). Each parallel unit needs its own browser tab'

#### Expected Behavior

A first-time user should be able to tell from --help why a single-seed crawl of 11 pages reports peak 1, and what a 'unit' is.

#### Actual Behavior

'unit' appears only in the completion report and is undefined in --help; the help phrase 'pages/tabs' suggests per-page concurrency, so 'peak 1 unit(s) in flight' (correct: a unit is a seed URL / seed round, and a single-seed crawl can never exceed 1) reads like the flag was ignored.

#### Root Cause Analysis

The help text (commands.rs:4238) describes parallelism granularity as pages/tabs, while the backend report and crawl.md count 'units = seed URLs' ('A crawl is a set of independent units — one per seed URL'); the two vocabularies were never reconciled, and the reference's explanation was not folded into the embedded help.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:4238 (crawl --parallel help text)`

#### AI Suggested Improvement

- Extend the --parallel help: 'units are seed URLs, one tab each; links discovered inside a seed round are collected from the shared tab pool — a single-seed crawl therefore reports peak 1 by design'.
- Make the footer self-describing: 'peak 1 seed unit(s) in flight (1 seed URL submitted)'.
- Copy the first sentence of crawl.md's Parallelism section into the embedded --help.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 6: crawl list silently prunes local task records (stale cleanup + 24h retention); documented as a pure listing

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 crawl list
# observed: 'Pruned 29 completed tasks from local tracking (routine cleanup).' and rows absent afterwards

#### Expected Behavior

crawl.md's subcommands section documents `crawl clear` / `crawl clear --all` as the only ways history is removed; a list command should be a read, or its retention behavior should be documented.

#### Actual Behavior

`crawl list` mutates the local tracking store: entries the backend no longer knows are removed (notice only printed when more than 20 are pruned at once), and crawl entries older than 24h are dropped silently on every list. crawl.md's `crawl list` section mentions neither retention nor pruning.

#### Root Cause Analysis

handle_crawl_list performs housekeeping before printing: it deletes stale ids (main.rs:16919-16929, message gated on stale_ids.len() > 20) and retains only crawl tasks submitted within the last 24h (main.rs:16930-16937), then rewrites the store — behavior added without updating the reference doc.

#### Code Pointer

`cli/browser4-cli/src/main.rs:16919 (stale pruning) and cli/browser4-cli/src/main.rs:16930 (24h age retention in handle_crawl_list)`

#### AI Suggested Improvement

- Document in crawl.md (`crawl list` row/section) that the local list is housekeeping state, not an audit log: entries disappear when the backend forgets the task and after 24h, without affecting backend records or checkpoints.
- Print the prune notice whenever anything is removed (not only when >20 stale entries), so the disappearance is observable.
- Consider a `--no-prune` mode for users who want a pure read.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 9 task steps completed and produced their artifacts (table listing, crawl2.csv with 10 url/title rows, JSON array, 3-URL seed-file crawl, crawl list). Steps 6 and 7 required the documented --sql addition because plain --format csv/json with --output is refused; step 7 also has a no-SQL alternative (--json). The single non-zero exit (6) came from an intentionally bad test query and was correctly reported.

**Success Rate:** 90% — 9/9 steps delivered, but step 6 failed on its literal first attempt (refusal) and needed re-running with --sql, and step 5's 'table' was the plain listing until --format table --sql was added.

**Issues Found:** 6

**Major Blockers:** None. The only hard stop — the pre-flight refusal of --format csv/json with --output and no --sql — was non-destructive, exited 1 without writing a file, and named its own fix ('Add --sql'), which worked on the first retry.

**Most Confusing Aspects:** 1) A plain crawl cannot emit CSV/JSON through --format unless --sql is added, yet the global --json flag does produce JSON — and neither the help nor the refusal message connects these paths; --json combined with -o silently writes a non-JSON listing into the .json file. 2) --top-links truncates in document order, so the 'catalogue' selector spends the whole budget on sidebar category links and no book detail page is ever crawled — the pattern matches everything, which hides why. 3) 'peak 1 unit(s) in flight' for a depth-1 crawl with the default --parallel reads like the flag is broken. 4) A crawl that exited 6 appears as 'completed' in crawl list and never matches --status failed. 5) An all-empty X-SQL extraction produces '10 rows extracted' and 'failed: 10' on adjacent stdout lines, with the reason only in a follow-up command.

**Most Valuable Improvements:** 1) Reconcile the crawl help/reference with the real --format contract (warn on stdout, refuse with --output, non-zero exit) and mention the --json alternative in both the help and the refusal message. 2) Make the --json/-o combination either write the envelope or refuse — never silently write a plain-text file under a .json name. 3) Preserve or recompute the partial-failure label in crawl list and let --status failed match it. 4) Surface per-page extraction failure reasons in the crawl's own output instead of requiring crawl result --verbose. 5) Explain parallel units (seed rounds) in the --parallel help and completion report.

**Usability Rating:** 8/10

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

#### Issue 1: Global --json combined with --output writes a non-JSON plain-text listing into the output file (guardrail bypassed)

./b4w.ps1 --json crawl --seed-file probe-seed.txt -d 0 -o probe-json-o.json
(the seed file holds one URL: http://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html)

#### Issue 2: crawl --help says --format csv/json 'has no effect without --sql', but with --output it is a hard refusal; refusal omits the working --json alternative

./b4w.ps1 crawl "http://books.toscrape.com/" -d 1 -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10 --format csv -o out.csv
./b4w.ps1 crawl "…" --format json -o out.json
./b4w.ps1 crawl "…" --format csv          # no -o
./b4w.ps1 crawl --help                     # documents '--format … Has no effect without --sql'

#### Issue 3: crawl list reports a partially failed crawl (exit 6) as 'completed'; --status failed never matches it

./b4w.ps1 crawl "http://books.toscrape.com/" -d 1 -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10 --sql "SELECT DOM_BASE_URI(dom) AS url, DOM_FIRST_TEXT(dom,'title') AS title FROM DOM_LOAD_AND_SELECT(@url,'body')" --format csv -o out.csv   # exits 6, stderr: 'crawl completed with errors: ok: 0, failed: 10'
./b4w.ps1 crawl list                    # the task's STATUS column shows 'completed'
./b4w.ps1 crawl list --status failed    # 'No crawl tasks matching status filter 'failed'.'

#### Issue 4: stdout prints '10 pages crawled, 10 rows extracted.' on a run whose every page failed extraction, right before 'Summary: ok: 0, failed: 10'

Same run as Issue 3's reproduction (X-SQL query returning empty fields on every page). stdout tail: '10 pages crawled, 10 rows extracted.' then 'Summary: ok: 0, failed: 10'; stderr: 'crawl completed with errors: ok: 0, failed: 10. Use crawl result <id> or --verbose for per-page diagnostics.'

#### Issue 5: crawl --parallel help and the completion report use different vocabulary; 'peak 1 unit(s) in flight' is unexplained

./b4w.ps1 crawl "http://books.toscrape.com/" -d 1 -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10
# header: 'Parallel tabs: server default'; footer: 'Parallelism: budget 4 tab(s), peak 1 unit(s) in flight'
./b4w.ps1 crawl --help   # '--parallel <n>  Collect up to <n> pages/tabs at the same time (default: 4, 1 = sequential). Each parallel unit needs its own browser tab'

#### Issue 6: crawl list silently prunes local task records (stale cleanup + 24h retention); documented as a pure listing

./b4w.ps1 crawl list
# observed: 'Pruned 29 completed tasks from local tracking (routine cleanup).' and rows absent afterwards

