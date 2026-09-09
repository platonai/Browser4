# Issues: crawl-sql-formats

> **Source:** `20260905-173905-crawl-sql-formats.full.md` | **Date:** 20260905-173905 | **Mode:** dev

## Scenario Background

### Task

**Task outcome: Successful — both acceptance criteria passed.**

- **AC5 (SQL from file, CSV output) — PASS.** `crawl --seed-file … --sql @xsql-extract.sql --format csv -o xsql-results.csv` exited 0, printed `X-SQL extraction: enabled`, `2 pages crawled, 2 rows extracted.`, and `Results written to …`. The CSV file exists and contains a header row (`url,title,price`) plus exactly 2 data rows: product/1.html → **Widget Alpha / $10.00** and product/2.html → **Widget Beta / $20.00** (values match the fixtures served at `localhost:18080/generated/crawl/product/1.html` and `2.html`).
- **AC6 (SQL from stdin, table format) — PASS.** The equivalent `--sql-stdin --format table` run (query piped via `< xsql-extract.sql`) exited 0 and printed an aligned grid table with column headers `url | title | price` and the same two rows. Note: despite the doc-template `FROM DOM_LOAD_AND_SELECT(@url, 'body')` shaping the resultSet as one row per page, headers matched the selected fields exactly.
- **Cross-check — PASS.** Re-running the file-based crawl produced a byte-identical CSV to the first run, and the table output from the stdin run carries the same titles/prices/URLs — extraction is identical regardless of SQL input method.

All commands ran through `./b4w.ps1` from the repo root; the daemon/backend auto-started without intervention; no workarounds were needed to complete the task.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` → captured to `xsql-help.txt`; confirmed `crawl` and its flags; read `skills/browser4-cli/SKILL.md` (§4e X-SQL quickstart, §5 warnings, reference map) and `skills/browser4-cli/references/crawl.md` (MockSite pattern with `#productTitle` / `#product-price` / `DOM_LOAD_AND_SELECT(@url, 'body')`).
2. `./b4w.ps1 crawl --help` → documented examples for exactly the AC5/AC6 command shapes.
3. Verified MockSite (HTTP 200) and inspected both product fixtures with curl → confirmed `#productTitle` / `#product-price` markup and values (Widget Alpha/$10.00, Widget Beta/$20.00).
4. Prepared `xsql-seeds.txt` (2 product URLs + `#` comment line) and `xsql-extract.sql`:
   ```sql
   SELECT DOM_BASE_URI(dom) AS url,
          DOM_FIRST_TEXT(dom, '#productTitle') AS title,
          DOM_FIRST_TEXT(dom, '#product-price') AS price
   FROM DOM_LOAD_AND_SELECT(@url, 'body')
   ```
5. **AC5:** `./b4w.ps1 crawl --seed-file .test-sessions/xsql-seeds.txt --sql @.test-sessions/xsql-extract.sql --format csv -o .test-sessions/xsql-results.csv` → exit 0; verified CSV (header + 2 rows). Crawl took ~10–16 s for 2 pages (within the documented 5–7 s/page cadence).
6. **AC6:** `./b4w.ps1 crawl --seed-file … --sql-stdin --format table < .test-sessions/xsql-extract.sql` → exit 0; table on stdout with matching headers/rows.
7. **Comparison:** re-ran the step-2 file invocation to `xsql-results-rerun.csv` — identical content to the first CSV and to the table run.
8. **Evaluation probes** (evidence for findings): split stdout/stderr capture of a table run (all status on stdout, stderr empty); CSV-to-stdout without `-o` (7 status lines precede the CSV); missing `@file` (clean client-side error, exit 1); PowerShell `@` splatting parse behavior (`pwsh -NoProfile -Command 'Write-Output @extract.sql'` → ParserError, quoted form works); raw-byte table inspection (header alignment is correct padding, not a defect). Grepped CLI source for code pointers (`help.rs:1631`, `main.rs:11965/12199/12326/12344`).

**Decisions:** followed the documented `crawl.md` MockSite X-SQL pattern (added a `url` column via `DOM_BASE_URI` for per-seed traceability); used lowercase function names per the brief's examples (case-insensitivity documented and confirmed working); ran the AC commands as literally specified (no `--depth 0`, relying on the CLI's "Link discovery disabled (no --out-link-selector)" auto-handling of seed-file mode).

**Workarounds:** none required for task completion.

---



Report copy saved to `.test-sessions/xsql-eval-report.json` (all scratch files — seeds, SQL, logs, CSVs, report — are inside `.test-sessions/`, nothing in the repo root).

---

## Issues Found (2 issues)

### Issue 1: Crawl status/progress chatter is written to stdout and pollutes CSV/table payload when redirected

**Severity:** Medium
**Category:** UX

#### Reproduction

In Git Bash from the repo root run:
  ./b4w.ps1 crawl --seed-file .test-sessions/xsql-seeds.txt --sql @.test-sessions/xsql-extract.sql --format csv > out.csv
(2 seed URLs; any X-SQL query works). Then inspect out.csv. Same with --format table > out.txt.
Also observed with stderr split: the 2> stderr file stays EMPTY while all status lines land on stdout.

#### Expected Behavior

When a structured payload (CSV/table) is written to stdout, the stdout stream should contain only that payload. Status lines ("Note: Link discovery disabled...", "Crawl task submitted:", "Crawling...", "N pages crawled, N rows extracted.") belong on stderr, or should be suppressed when stdout is not a TTY / when a machine format is selected.

#### Actual Behavior

The resulting file contains 7 status/progress lines before the CSV header row (CSV starts at line 8), so piping stdout into a file or downstream parser yields corrupt structured output. The table run is equally polluted (progress block precedes the table). Only the -o <file> flag produces clean machine-readable output, but the help text describes -o as merely "Write results to a file instead of stdout", implying stdout is a first-class payload channel, and the Output-formats documentation shows bare CSV/table samples without any warning that stdout additionally carries status lines.

#### Root Cause Analysis

All crawl status and payload text is routed through the same cli_println! stdout macro: the "Link discovery disabled" note (~main.rs:11965), the per-seed progress line (main.rs:12199), the summary "N pages crawled, N rows extracted." (main.rs:12326), and the formatted payload itself (main.rs:12344-12346 CrawlOutput::Stdout). There is no TTY/format-aware stream separation. --json mode gets special suppression handling (json_field/cli_println honoring --json), but csv/table do not.

#### Code Pointer

`cli/browser4-cli/src/main.rs:12344 (CrawlOutput::Stdout emission); progress lines at main.rs:11965 and main.rs:12199`

#### AI Suggested Improvement

- Emit all human status/progress lines (note, task submission, Crawling..., counts) to stderr so stdout carries only the payload; or suppress them when stdout is redirected / a machine format (csv) is in play.
- Keep the -o file path behavior as-is (payload to file, status to stdout for terminal users) and document -o as the supported route for machine consumption.
- Mirror the existing --json suppression pattern and add an automated test asserting that `--format csv` with redirected stdout yields a file whose first line is the CSV header.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified — status chatter and CSV payload share `cli_println!` on stdout, so redirected/`|` output is corrupted while `-o` stays clean, contradicting the help text's description of stdout as a payload channel. Fix design must pick one approach (route human status to stderr, or suppress when stdout is not a TTY and a machine format is selected) and cover both `csv`/`table` plus the existing `--json` path with regression tests; recommend documenting `-o` as the canonical machine-consumption route.

---

### Issue 2: crawl --help examples use unquoted --sql @file which fails at parse time in PowerShell (splatting)

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1) ./b4w.ps1 crawl --help — the examples show:
     browser4-cli crawl --seed-file urls.txt --sql @extract.sql --format csv -o results.csv
     browser4-cli crawl --seed-file urls.txt --sql-stdin --format table < query.sql
2) In a PowerShell prompt (the project's primary dev shell on Windows — ./b4w.ps1 is PowerShell), copy-paste the first example verbatim. Probe: pwsh -NoProfile -Command 'Write-Output @extract.sql' reproduces the failure:
     ParserError: The splatting operator '@' cannot be used to reference variables in an expression. '@extract' can be used only as an argument to a command.
The CLI never runs. Quoting ("@extract.sql") works.

#### Expected Behavior

Examples printed by --help should be copy-paste-safe in the project's documented shells (PowerShell is listed first in the SKILL.md invocation table: `./b4w.ps1 <command>`). The help should quote the @ path (--sql "@extract.sql") or note the quoting requirement, consistent with the SKILL.md Critical Warning: "On PowerShell, always quote @file paths (--sql "@query.sql") — an unquoted @ is read as the splatting operator."

#### Actual Behavior

The unquoted example fails in PowerShell with a ParserError whose message mentions only splatting-operator syntax — no hint that quoting the path fixes it. The example is only usable from POSIX-style shells (Git Bash / bash), where @ is literal. The same unquoted form appears in skills/browser4-cli/references/crawl.md ("X-SQL from file (@ prefix)" example and the MockSite crawl example) and in the crawl --help examples.

#### Root Cause Analysis

The example strings in help.rs were authored for POSIX shell conventions where @ is literal; PowerShell's argument grammar treats a token beginning with @ as the splatting operator and raises a parse error before the process is spawned. SKILL.md documents the pitfall, but the in-tree --help examples and crawl.md reference do not apply the documented quoting, leaving the most visible documentation contradicting the rule.

#### Code Pointer

`cli/browser4-cli/src/help.rs:1631 (crawl example strings; update the asserting unit test at help.rs:3472 in lockstep); skills/browser4-cli/references/crawl.md ("X-SQL from file (@ prefix)" example and the MockSite crawl example)`

#### AI Suggested Improvement

- Quote the @ path in every example: --sql "@extract.sql" (help.rs crawl examples, crawl.md quickstart/MockSite examples), and update the string-assertion test at help.rs:3472.
- Add a one-line note on the --sql option row in crawl --help: "@file paths must be quoted on PowerShell (--sql "@query.sql")", or point to the shell-quoting reference.
- Keep the existing clean client-side error for a missing @file ("Failed to read file ... Tried: ...", exit 1) — it is good; the gap is only that a PowerShell parse error never reaches the CLI, so help/docs are the only fix surface.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified the unquoted example at `help.rs:1631` contradicts the project's own SKILL.md quoting rule for PowerShell, and a copy-paste from `--help` raises a ParserError with no actionable hint. Fix is low-risk: quote `@extract.sql` in the help example, sync the asserting test at `help.rs:3472` and `skills/browser4-cli/references/crawl.md`, and optionally add the one-line quoting note to the `--sql` option row; no behavioral code change needed.

---

## Overall Assessment

**Completion Status:** Successful — both acceptance criteria (AC5 CSV-from-file, AC6 table-from-stdin) verified end-to-end against the real backend and MockSite fixtures, including the final cross-check that re-running the file-based crawl produces extraction identical to the stdin run.

**Success Rate:** 100%

**Issues Found:** 2

**Major Blockers:** None. Dev-mode daemon/backend auto-started; the first ./b4w.ps1 command exited 0. MockSite was already serving localhost:18080. Both crawls completed with exit 0 on the first attempt using only documented flags and the documented X-SQL pattern.

**Most Confusing Aspects:** For a first-time user the crawl output stream is the main confusion point: status lines (task id, "Crawling...", counts) are interleaved on stdout with the actual CSV/table payload, so a redirected run's output file is not the format it claims to be. Secondary: help examples show --sql @file unquoted while SKILL.md warns that unquoted @ breaks in PowerShell — two authoritative docs contradict each other for the primary Windows shell.

**Most Valuable Improvements:** Separate status/progress output from the stdout payload (stderr or TTY-aware suppression) so `--format csv/table > file` produces clean structured output; and quote @file paths in all help/reference examples so they are copy-paste-safe in PowerShell. Both have single-point fixes (main.rs output path, help.rs example strings).

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

#### Issue 1: Crawl status/progress chatter is written to stdout and pollutes CSV/table payload when redirected

In Git Bash from the repo root run:
  ./b4w.ps1 crawl --seed-file .test-sessions/xsql-seeds.txt --sql @.test-sessions/xsql-extract.sql --format csv > out.csv
(2 seed URLs; any X-SQL query works). Then inspect out.csv. Same with --format table > out.txt.
Also observed with stderr split: the 2> stderr file stays EMPTY while all status lines land on stdout.

#### Issue 2: crawl --help examples use unquoted --sql @file which fails at parse time in PowerShell (splatting)

1) ./b4w.ps1 crawl --help — the examples show:
     browser4-cli crawl --seed-file urls.txt --sql @extract.sql --format csv -o results.csv
     browser4-cli crawl --seed-file urls.txt --sql-stdin --format table < query.sql
2) In a PowerShell prompt (the project's primary dev shell on Windows — ./b4w.ps1 is PowerShell), copy-paste the first example verbatim. Probe: pwsh -NoProfile -Command 'Write-Output @extract.sql' reproduces the failure:
     ParserError: The splatting operator '@' cannot be used to reference variables in an expression. '@extract' can be used only as an argument to a command.
The CLI never runs. Quoting ("@extract.sql") works.



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. CLI changes verified with `cargo test --bin browser4-cli` (full suite green).

| Issue | Decision | Resolution |
|---|---|---|
| 1 — crawl status chatter pollutes stdout CSV/table payload (Medium) | ACCEPT | Fixed: when an X-SQL payload goes directly to stdout (no `-o`) with stdout redirected (non-TTY), all status/progress chatter (`Note:`, task submission, `Crawling...`, summaries) is routed to stderr via the new `crawl_status_println!` macro, so `--format csv > out.csv` yields a clean CSV. TTY and `-o` behavior unchanged. Docs already present on `-o`; help text notes the machine-consumption route. |
| 2 — crawl help examples use unquoted `--sql @file` (Medium) | ACCEPT | Fixed: `help.rs` crawl examples now quote the `@file` path (`--sql "@extract.sql"`) and crawl.md examples were quoted consistently (PowerShell splatting-safe), matching SKILL.md's warning; the string-assertion test was updated in lockstep. |