# Issues: crawl-link-options

> **Source:** `20260916-201530-crawl-link-options.full.md` | **Date:** 20260916-201530 | **Mode:** dev

## Scenario Background

### Task

**All 4 acceptance criteria met.** The `crawl` link-discovery options (depth control, CSS selectors, URL patterns, seed files) behave exactly as specified against MockSite on `localhost:18080`.

| AC | Command (via `./b4w.ps1`) | Result |
|---|---|---|
| **AC1** depth 0 | `crawl "…/generated/crawl/index.html" --depth 0 --refresh` | ✅ `Crawl completed. 1 pages found.` → `depth=0 \| …/index.html \| Crawl Test Hub`. No product content. Exit 0, ~3.5 s |
| **AC2** `-d 2 -ol "a.product" -olp "/product/"` | (needs MSYS workaround, see below) | ✅ 10 pages: hub (d0) + Widgets Alpha/Beta/Gamma (d1) + Delta/Epsilon/Zeta/Lambda/Mu/Nu (d2). **Zero** `category/*` pages — `category-link` anchors correctly not followed |
| **AC3** `--depth 3 --refresh -ol "a.product"` | | ✅ 15 pages across depths 0–3; five depth-3 terminals: **Deep Widget Theta, Iota, Kappa, Lambda Prime, Mu Pro** |
| **AC4** `--seed-file … --depth 0 --refresh` | | ✅ `URLs: 2`; only Widget Alpha + Widget Gamma. No Widget Beta, no Crawl Test Hub |

**AC2 required one deviation.** Run literally as specified, `./b4w.ps1 … -olp "/product/"` **fails fast (exit 1)** because Git Bash rewrites the leading-slash value to `C:/Program Files/Git/product/` before PowerShell starts. I confirmed the rewrite independently with a probe (`argv[1] = <C:/Program Files/Git/product/>`), then re-ran with the documented workaround `MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 …`. Worth stating plainly: **`b4w.ps1` detected the mangling itself and refused to run** rather than silently producing wrong results — that is a notably good failure mode, and the AC still passed on the first workaround attempt.

**Bonus workflows verified:** X-SQL extraction (`--sql @file.sql --format table` → correct correlated rows), background mode + `crawl result` polling, and the no-match diagnostic (which correctly distinguishes *"selector matched 3 elements but the pattern filtered them all"* from *"selector matched nothing"*). Error paths all exit 1 with actionable text: no URLs, empty seed file, comments-only seed file, invalid `--format`, invalid `--parallel`.

### Execution Context

**Preparation:** verified cwd; `./b4w.ps1 help`; read `skills/browser4-cli/SKILL.md` and `references/crawl.md` in full; confirmed MockSite already up (`HTTP 200` on port 18080); mapped the fixture graph by fetching the hub and product pages directly (`curl`) — hub → 3 products; product 1/2/3 → 4/5, 6/7, 8/9; product 4/5/7 → `depth3/8,9,10.html` — **before** crawling, so I could validate counts rather than accept them.

**Commands:** 4 AC crawls, 1 deliberate mangled-form AC2, 1 depth-3-without-selector probe, 1 no-match-pattern probe, 4 error-path probes, 1 X-SQL extraction run, 1 background + `crawl result` cycle, `crawl list` / `crawl status` checks, plus an argv probe.

**Key decisions:** (1) Confirmed the Git Bash rewrite with a minimal `pwsh -File` argv probe instead of inferring it f...

(truncated — see full.md for complete trace)

---

## Issues Found (3 issues)

### Issue 1: crawl list table columns misaligned: TASK ID cell overflows its computed width

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 crawl list --limit 5

#### Expected Behavior

A single aligned table: all rows the same width, with COMMAND/DESCRIPTION/STARTED/FINISHED/DURATION/STATUS lining up under their headers.

#### Actual Behavior

Header and separator rows are 103 chars; every data row is 127 chars. The 36-char task UUID is written into a column sized for 12 chars, so all columns to its right (COMMAND through STATUS) are pushed 24 chars out of alignment. On a standard 80- or 120-column terminal the data rows wrap and STATUS lands on a second line, making the table hard to scan. Measured with `awk '{printf "len=%3d |%s|\n", length($0), $0}'`.

#### Root Cause Analysis

In the crawl-list renderer, the TASK ID column width is computed as `page.iter().map(|t| t.task_id.len()).max().unwrap_or(8).max(8).min(12)` — the trailing `.min(12)` caps it at 12 (the header "TASK ID" is only 7 chars, so header/separator stop at 12). The data row then formats the raw `entry.task_id` (a 36-char UUID) with `{:<id_w$}`. Rust's format width is a MINIMUM, not a maximum, so the UUID simply overflows. The same function already truncates the DESCRIPTION cell (`if desc.len() > desc_w { desc = format!("{}…", &desc[..desc_w - 1]); }`), so the truncation pattern exists — it was just never applied to the ID column.

#### Code Pointer

`cli/browser4-cli/src/state.rs:1155 (id_w computation) and cli/browser4-cli/src/state.rs:1228-1243 (row rendering)`

#### AI Suggested Improvement

- Remove the `.min(12)` cap so id_w sizes to the real 36-char UUID — this keeps the table aligned AND keeps the ID copy-pasteable for `crawl status <id>` / `crawl cancel <id>`.
- Do NOT fix this by truncating task_id the way `desc` is truncated: a truncated UUID cannot be pasted into `crawl status`, which is the main reason users run `crawl list`.
- If a narrower table is genuinely wanted, render the full ID on its own line above each row instead of capping the column.
- Add a unit test asserting every emitted row (header, separator, data) is the same length — the existing test at state.rs:1814 only checks the summary count line, which is why this slipped through.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: crawl status exits 0 for a task ID that does not exist

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl status 00000000-0000-0000-0000-000000000000; echo $?

#### Expected Behavior

A non-zero exit code, so scripts and agents can detect a typo'd, expired, or cleared task ID. This matches the CLI's own stated contract for `htmlsnapshot query`, which the docs describe as exiting non-zero on error so "scripts can detect failure without parsing the JSON".

#### Actual Behavior

Exits 0. Prints `Crawl 00000000-...: Not Found` followed by a full JSON record containing `"status": "Not Found"` and `"error": "Task not found: 00000000-..."`. The failure is visible to a human reading stdout but invisible to any script checking `$?`.

#### Root Cause Analysis

`handle_crawl_status` prints the summary and the raw record and then returns `Ok(())` unconditionally — the "Not Found" status is treated as a successful query of a missing task rather than as an error. The response is parsed (a `crawl_task_summary_line` helper renders `Crawl <id>: <status>`), so the status string is already in hand at the point the decision is made; nothing inspects it before returning.

#### Code Pointer

`cli/browser4-cli/src/main.rs:13143 (handle_crawl_status, final `Ok(())`)`

#### AI Suggested Improvement

- Return `Err(format!("Task not found: {id}"))` when the parsed record's status is `Not Found`, so the CLI exits 1 — consistent with `crawl` submission errors, which already exit 1.
- Apply the same rule to `crawl result <id>` for a missing ID, so the two polling subcommands agree.
- Document the exit-code contract for the `crawl status` / `crawl result` / `crawl cancel` subcommands in references/crawl.md's Error handling table, which currently covers only the top-level `crawl` command.
- Note this does not affect the success path: `crawl status` on a real completed task works correctly.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: crawl --help advertises a -olp example that cannot run from Git Bash, and omits the MSYS caveat

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 --help crawl   # then run its own Examples entry:
./b4w.ps1 crawl https://example.com -d 2 -ol "a.product" -olp "/product/"

#### Expected Behavior

Either the help's own example is runnable as printed, or the Notes section warns that leading-slash pattern values are rewritten by Git Bash's MSYS path conversion and gives the workaround.

#### Actual Behavior

The example fails with exit 1: `Error: argument 'C:/Program Files/Git/product/' (probably typed as '/product/') was rewritten by Git Bash's MSYS path conversion before PowerShell started.` The `--help crawl` output (Notes and Examples sections, both reviewed in full) contains no mention of Git Bash, MSYS, or path conversion. The caveat exists only in the separate skill reference skills/browser4-cli/references/crawl.md, which a user reading `--help` has no reason to open.

#### Root Cause Analysis

The Git Bash / MSYS2 leading-slash caveat was documented in the crawl reference doc when it was discovered, but the corresponding note was never added to the CLI's own help text — which is the surface most users actually read. The help text's Examples block still carries the `/product/` form verbatim. The underlying rewrite is a Git Bash behavior (arguments beginning with `/` are converted to Windows paths before the native `pwsh` process starts) and cannot be fixed in the CLI itself — only surfaced earlier.

#### Code Pointer

`cli/browser4-cli/src/main.rs — the crawl command's help/usage text (the `Notes:` and `Examples:` blocks printed by `--help crawl`)`

#### AI Suggested Improvement

- Add one line to the crawl `--help` Notes: leading-slash values such as `-olp "/product/"` are rewritten by Git Bash before the CLI sees them; run via `./b4w.sh`, or export `MSYS2_ARG_CONV_EXCL='*'`, or drop the leading slash (`-olp "product/"`).
- Consider an inline marker in the Examples block itself, since that is the line users copy. The crawl.md reference already documents this correctly — reuse its wording.
- Credit where due: `b4w.ps1` already detects the rewrite and fails fast with the exact remediation, so this is a documentation-alignment gap, not a silent-wrong-result bug. Do not weaken that guard.

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

**Completion Status:** Successful — all four acceptance criteria (AC1–AC4) met against MockSite on localhost:18080, with the link-discovery graph independently verified against the raw fixture HTML before crawling.

**Success Rate:** 100% — 4/4 ACs passed. One AC (AC2) required the documented MSYS2_ARG_CONV_EXCL workaround; it passed first try once applied.

**Issues Found:** 3

**Major Blockers:** None. The single friction point — Git Bash rewriting -olp "/product/" into C:/Program Files/Git/product/ — was caught by b4w.ps1 itself, which refused to run and printed both workarounds, so AC2 completed immediately on retry.

**Most Confusing Aspects:** 1) The Git Bash leading-slash rewrite: the CLI's own help advertises the exact -olp "/product/" form that cannot run from the prescribed ./b4w.ps1 invocation in Git Bash, and the caveat lives only in a reference doc. 2) The depth-without-selector semantic — --depth 3 with no --out-link-selector silently processes only seed URLs; this is documented in three places and the CLI prints a clear 'Note: Link discovery disabled (no --out-link-selector)' line, but the asymmetry between --depth and -ol (one flag silently neuters the other) is inherently surprising. 3) crawl status / crawl result dumping a full raw JSON record for what is described as a 'compact one-line summary'.

**Most Valuable Improvements:** 1) Fix the crawl list column-width cap (state.rs:1155) — it misaligns the default output of the primary task-management command on every single run and causes row wrapping on standard terminals. 2) Make crawl status / crawl result exit non-zero for unknown task IDs, matching the exit-code contract the CLI already advertises for htmlsnapshot query. 3) Surface the Git Bash / MSYS caveat in crawl --help rather than only in the bundled reference doc.

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

#### Issue 1: crawl list table columns misaligned: TASK ID cell overflows its computed width

./b4w.ps1 crawl list --limit 5

#### Issue 2: crawl status exits 0 for a task ID that does not exist

./b4w.ps1 crawl status 00000000-0000-0000-0000-000000000000; echo $?

#### Issue 3: crawl --help advertises a -olp example that cannot run from Git Bash, and omits the MSYS caveat

./b4w.ps1 --help crawl   # then run its own Examples entry:
./b4w.ps1 crawl https://example.com -d 2 -ol "a.product" -olp "/product/"

