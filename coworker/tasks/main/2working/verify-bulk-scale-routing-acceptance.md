# Issues: bulk-scale-routing

> **Source:** `20260905-165535-bulk-scale-routing.full.md` | **Date:** 20260905-165535 | **Mode:** dev

## Scenario Background

### Task

**All six acceptance criteria passed**, exercising every branch of SKILL.md §4b against a live backend and MockSite:

- **AC1 (Single list page):** `goto` → `htmlsnapshot` (capture) → `htmlsnapshot inspect` (auto-discovered `.product-card`, 6 matches) → X-SQL with `DOM_LOAD_AND_SELECT(@url, '.product-card')` returned **6 rows** of correlated `title + price + href` — one per product card.
- **AC2 (Multiple known URLs):** `crawl --seed-file ... --depth 0 --sql @ac2-query.sql --format table --refresh` returned **3 rows** (URL/title/price) for the 3 seed URLs in ~36 s, matching the documented ~5–7 s/page cadence.
- **AC3 (Crawl from start URL):** `crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"` discovered **10 pages** (hub + `product/1..9` at depths 1–2); all category/guide/anchor links were correctly excluded by the selector+pattern filters. First invocation was blocked by the CLI's MSYS path-conversion guard (see Issue 3) and succeeded after `MSYS2_ARG_CONV_EXCL='*'`.
- **AC4 (Parallel swarm):** `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` → `swarm query --sql @ac2-query.sql --seed-file ... --refresh` → polled `swarm list`/`swarm status` → `swarm result` returned one structured row per job for all **3 URLs** (~13 s); session closed with `swarm close`.
- **AC5 (Repeated monitoring):** `-s price-watch goto <product page>` → `loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "…#product-price…"` ran **2 iterations exactly 10 s apart**, each returning `$899.99`. `loop --list` and `loop --status --name` showed live progress (1/2 iters) mid-run; state auto-cleaned on completion.
- **AC6 (Few URLs in a shell script):** a PowerShell `ForEach-Object` loop over 3 product URLs calling `goto` → `htmlsnapshot` → `htmlsnapshot get text "#productTitle"` with `Start-Sleep -Seconds 2` extracted all 3 titles correctly — no crawl/swarm/loop needed.

The documentation-to-behavior fidelity is high: command names, flag spellings (`-d`, `-ol`, `-olp`, `--seed-file`, `@sql` file prefix, `@url` unquoted placeholder, single-quoted CSS selectors), output formats, and timing expectations in SKILL.md §4b and the crawl/swarm/loop/htmlsnapshot references all matched observed behavior. The five issues below are refinements, not blockers.

### Execution Context

**Key Commands:**

**Key decisions:** (1) Followed the documented selector-discovery workflow (`htmlsnapshot inspect`) rather than guessing selectors — it auto-discovered `.product-card` with `div.product-title`/`.product-price` suggestions, which exactly matched the query used. (2) Reused one query file (`#productTitle`/`#product-price`/`DOM_BASE_URI`) across AC2 and AC4 to compare crawl vs swarm fairly. (3) Ran AC5's loop in the background to satisfy "check status while it runs". (4) All scratch files (SQL, seed files, logs, PS1 script) kept under `.test-sessions/`.

**Workarounds required:** (1) AC3 — the documented `./b4w.ps1` invocation from Git Bash mangles a `/`-leading `-olp` value; the CLI refused with a precise fix message; re-ran with `MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 …` (or `./b4w.sh`). This guard made the workaround trivial. (2) None other — every documented example ran as written once invoked in PowerShell-friendly form.

---

## Issues Found (5 issues)

### Issue 1: loop interval/count examples put -i/-n after `--`, contradicting the documented grammar and real CLI behavior

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1) skills/browser4-cli/SKILL.md §4b (~line 327) shows: `loop -- eval "..." -i 3600`. 2) skills/browser4-cli/references/loop.md Quick start (~line 24) shows: `browser4-cli loop -- eval "document.title" -i 300` ("Run eval every 5 minutes") and the Subcommand examples (~lines 77-81) show `loop -- eval "document.title" -i 300`, `loop -- snapshot -i 600`, `loop -- screenshot --full-page -i 1800`. Running the documented form: `./b4w.ps1 loop -- eval "document.title" -i 3 -n 1`

#### Expected Behavior

The interval/count flags should take effect (3 s interval, 1 iteration), as the example's comment promises, since the same file's 'Argument grammar' section states loop flags must appear BEFORE `--`.

#### Actual Behavior

The CLI emits `[WARN] \`-i\` after \`--\` is treated as a nested browser4-cli argument, not a loop option. Did you mean \`loop -i <value> -- ...\`?` (and the same for -n), the loop header shows the DEFAULTS ('every 3600s, up to 604800s', unbounded count), and iteration 1 fails with `[ERROR] ... unexpected positional arguments (this command accepts 2): ["-i", "-n"]`. A user copying the Quick start example gets an infinite (default) loop whose every iteration errors, instead of a 5-minute monitor.

#### Root Cause Analysis

The CLI was changed so that everything after `--` is passed verbatim to the nested browser4-cli process (known loop flags there now only produce a warning). The grammar box in loop.md documents the new rule, but the Quick start and Subcommand example blocks in the same file — and the §4b decision tree in SKILL.md — still show the pre-change syntax with `-i` after `--` and were never updated.

#### Code Pointer

`skills/browser4-cli/references/loop.md (Quick start ~line 24; Subcommand examples ~lines 77-81); skills/browser4-cli/SKILL.md §4b (~line 327). Reference behavior verified against cli/browser4-cli/src/main.rs:handle_loop() (line 13138).`

#### AI Suggested Improvement

- Move the interval/count flags before `--` in every example: `loop -i 300 -- eval "document.title"`, `loop -i 600 -- snapshot`, `loop -i 1800 -- screenshot --full-page`
- In SKILL.md §4b change `loop -- eval "..." -i 3600` to `loop -i 3600 -- eval "..."`
- Add a one-line note under the Quick start examples that flags after `--` belong to the nested command and are warned about, so the grammar rule is visible at first use

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Confirmed — loop.md's own grammar section (lines 60-72) says flags must precede `--` and even labels the after-`--` form "Wrong", yet lines 23 and 78-81 show `-i` after `--` as working examples; the doc is internally contradictory and a copied example becomes an erroring default-interval loop. Improvement: the same stale `loop -- eval "..." -i 3600` form also appears in `skills/browser4-cli/references/decision-trees.md:69` (not mentioned in the report) — grep the whole skill tree for `loop --` before closing.

---

### Issue 2: Loop reports success (exit 0, checkmark summary) when every iteration failed — silent false success for monitoring

**Severity:** Medium
**Category:** Reliability

#### Reproduction

`./b4w.ps1 loop -i 1 -n 2 -- eval` (nested eval intentionally invalid — no expression). Output: two `[ERROR] Iteration N: Error: A JavaScript expression is required...` lines, then `========================================` and `✓  Loop finished — 2 iteration(s) completed.`; `echo $?` reports 0; `loop --list` shows no persisted loop (state auto-cleared).

#### Expected Behavior

A monitoring loop whose iterations all failed should surface the failure: a summary like '2/2 iterations failed', a non-zero exit code (or at least an explicit warning), so scripts/agents don't mistake a fully broken check for success.

#### Actual Behavior

The loop exits 0 with a checkmark 'Loop finished' summary and auto-clears its state even when every iteration errored. Only the JSON output mode records per-iteration `ok:false`. For the §4b use case ('Repeated monitoring'), a broken check (bad selector, session down, page changed) silently looks like a successful monitoring run.

#### Root Cause Analysis

In cli/browser4-cli/src/main.rs handle_loop(), the completion summary at ~line 14036 prints `✓ Loop finished — {total} iteration(s) completed.` where `total = iteration.saturating_sub(1)` counts attempts regardless of per-iteration success, and the function returns Ok(()) unconditionally on normal completion — per-iteration failures (results[i].ok == false) are never aggregated into the summary or the exit code.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_loop() — completion summary and exit path around lines 14020-14068`

#### AI Suggested Improvement

- Track the number of failed iterations and print them in the summary, e.g. '✓ Loop finished — 3 iteration(s) completed, 1 failed' or '✗ Loop finished — all 2 iteration(s) failed'
- Return a non-zero exit code when any (or all) iterations failed, and document it (loop.md Error handling section currently only says errors are logged to stderr and the loop continues)
- State in loop.md whether failed iterations count toward --count; observed behavior is that they do

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Confirmed at main.rs:14037/14068 — the summary counts attempts (`iteration.saturating_sub(1)`) while per-iteration `ok:false` results are never aggregated, and every exit path (including the Ctrl+C branches) returns `Ok(())`. This is the highest-impact finding of the five and compounds Issue 1: the user base is silent false-success in the core §4b monitoring use case. Improvement: the summary must always report failure counts, but for the exit code the design decision is "non-zero when ALL failed" vs "when ANY failed" — for long infinite monitoring loops any-failed makes the exit code nearly meaningless; I'd default to all-failed or an explicit flag, and document both the threshold and whether failed iterations consume `--count` (they do, per the count break at iteration top).

---

### Issue 3: crawl.md Git Bash caveat is stale: CLI now hard-refuses MSYS-mangled -olp patterns instead of silently filtering all links

**Severity:** Low
**Category:** Documentation

#### Reproduction

From Git Bash: `./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"`. crawl.md lines 180-187 and its error-handling table (~line 374) state that in this situation the crawl 'silently reports only the seed page' (exit 0).

#### Expected Behavior

The documented failure mode is a silent, wrong crawl result — the doc tells the user the pattern value 'filters out every link' and the crawl 'silently reports only the seed page'.

#### Actual Behavior

The current CLI detects the rewrite before submitting and exits 1 with: `Error: argument 'C:/Program Files/Git/product/' (probably typed as '/product/') was rewritten by Git Bash's MSYS path conversion before PowerShell started. ... Run this command via ./b4w.sh instead ... Or export MSYS2_ARG_CONV_EXCL='*' and re-run`. No silent wrong result is possible via this path — the guard message is excellent, but the reference doc describes behavior the tool no longer exhibits.

#### Root Cause Analysis

A pre-submission guard for MSYS-rewritten slash-leading arguments was added in the CLI (main.rs ~lines 18180-18264, message at 18258) after crawl.md's Git Bash caveat paragraph was written; the docs were not synced with the new behavior.

#### Code Pointer

`skills/browser4-cli/references/crawl.md lines 180-187 and the 'No links found (depth >= 1)' row of the Error handling table; guard implemented at cli/browser4-cli/src/main.rs:18258`

#### AI Suggested Improvement

- Rewrite the crawl.md caveat to describe the actual behavior: the CLI refuses the rewritten value with exit 1 and a message naming the two fixes (./b4w.sh, or MSYS2_ARG_CONV_EXCL='*')
- In the error-handling table, note that the shell-mangled-pattern diagnosis applies to the case where a non-slash-leading pattern was mangled, and point to the guard message for the slash-leading case

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed — crawl.md:180-187 describes a silent wrong-result failure mode, while the pre-submission MSYS guard (main.rs ~18271-18277) now refuses rewritten slash-leading values with exit 1 and actionable remediation; the doc describes behavior the tool no longer exhibits. The suggested rewrite (describe the guard message and both fixes, keep the table row for non-slash-leading mangling) matches observed behavior; a straightforward doc-sync, Low severity appropriate.

---

### Issue 4: htmlsnapshot capture/inspect 'Try these next' tips suggest `get` with --limit, which single `get` silently ignores

**Severity:** Low
**Category:** UX

#### Reproduction

1) `./b4w.ps1 htmlsnapshot` on any page with an h1 — output includes the tip `htmlsnapshot get text "h1" --limit 5   # page heading`. 2) Run that exact command: `./b4w.ps1 htmlsnapshot get text "h1" --limit 5` — prints exactly ONE value and exits 0. 3) `./b4w.ps1 htmlsnapshot get --help` lists only `--page`, `--page-size`, `--all` — no `--limit`. The inspect output tips (main.rs lines 8189-8200) similarly show `get attr "img[src]" src --limit 20` (single-get form).

#### Expected Behavior

Tips copied from command output should behave as implied: `--limit 5` should return up to 5 values (i.e. the tip should use `htmlsnapshot get all text "h1" --limit 5`), or the unsupported flag should be rejected rather than silently ignored.

#### Actual Behavior

A first-time user following the built-in tip gets one value (get semantics) with the --limit flag silently discarded — no error, no hint that `get all` is the multi-result form. The neighboring tip lines use `get all ... --limit 20` correctly, so the inconsistency is visible in the same block.

#### Root Cause Analysis

The next-step tip templates in the CLI were assembled from get-all style examples, but the h1/attr lines kept the single-get command prefix (`get text` / `get attr` without `all`); single get's argument parser accepts and ignores the extra flag instead of validating it against the documented option set.

#### Code Pointer

`cli/browser4-cli/src/main.rs:6616 (capture tips) and lines 8181, 8189-8200 (inspect tips)`

#### AI Suggested Improvement

- Change the tips to the multi-result form: `htmlsnapshot get all text "h1" --limit 5`, `htmlsnapshot get all attr "img[src]" src --limit 20`, etc.
- Optionally make single `get` reject unknown/unsupported flags like --limit with a 'did you mean `get all ... --limit N`?' error instead of ignoring them

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Confirmed — main.rs:6616 and 6619 show single-`get` forms with `--limit` sandwiched between correct `get all ... --limit` lines (6618, 6620), and the usage text at 6675 documents no `--limit` for single get; the tip is wrong regardless of whether the parser silently drops the flag or errors. Improvement: fix all four tip strings to the `get all` form first; treat the "reject --limit on single get with a did-you-mean hint" as optional — verify the silent-ignore claim during the fix (the repro result is consistent with `--limit` being parsed but consumed only by the `all` variant, but worth confirming before investing in parser strictness).

---

### Issue 5: swarm.md stale-task handling text doesn't match observed non-interactive behavior

**Severity:** Low
**Category:** Documentation

#### Reproduction

With completed swarm tasks tracked from a prior session, run non-interactively: `./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` (no --clear-stale).

#### Expected Behavior

Per swarm.md (~line 153): stale tasks trigger a warning and an interactive `Clear them now? [Y/n]` prompt; 'Use `swarm create --clear-stale` for non-interactive/scripted use' — implying scripts must pass --clear-stale or the stale tasks persist.

#### Actual Behavior

The command printed `Auto-cleaned 8 completed swarm task(s) from prior sessions.` and created the session — no prompt, no --clear-stale flag needed. The behavior is friendly (non-TTY invocation auto-cleans instead of hanging on a prompt), but the doc text misleads script authors into believing they must add the flag.

#### Root Cause Analysis

The CLI treats a non-TTY invocation as an implicit 'yes' to the stale-task cleanup prompt (or auto-cleans completed tasks when stdin is not interactive), a behavior that post-dates or was never reflected in the swarm.md wording.

#### Code Pointer

`skills/browser4-cli/references/swarm.md ~line 153 ('Stale tasks' tip); observed behavior originates in the swarm-create handler around cli/browser4-cli/src/main.rs:10272 ('Auto-cleaned ... swarm task(s)' message)`

#### AI Suggested Improvement

- Update swarm.md to state: in interactive (TTY) sessions stale tasks trigger the [Y/n] prompt; in non-interactive/scripted invocations completed stale tasks are auto-cleaned with a printed notice, so --clear-stale is only needed to force cleanup in a TTY
- Keep the note that `swarm list --clear` removes stale entries manually

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Confirmed — the code auto-prunes *terminal/completed* swarm tasks unconditionally with a printed notice (main.rs:10266-10277, no TTY check), while the [Y/n] prompt (10309) fires only for *pending* non-terminal tasks and only in a TTY; non-TTY pending invocations get a warning and proceed. swarm.md:153's "use --clear-stale for non-interactive/scripted use" is stale and overstates the flag's necessity. Improvement: the doc rewrite should capture the three-way split precisely (completed → always auto-cleaned; pending → TTY prompt / non-TTY warning, no auto-clean; `--clear-stale` force-clears pending in either mode), not just the interactive-vs-scripted dichotomy the suggestion sketches.

---

## Overall Assessment

**Completion Status:** Successful — all six acceptance criteria (AC1-AC6) of the §4b bulk/scale scenario were completed and verified against the live local backend and MockSite: single-page X-SQL extraction (6 correlated rows), seed-file crawl (3/3 rows), link-discovery crawl (10 pages, product links only), parallel swarm extraction (3/3 jobs), loop monitoring (2 iterations on a named session), and a PowerShell few-URL loop (3/3 titles).

**Success Rate:** 95% — every documented workflow executed as documented on the first or second attempt; the only retry was AC3's /-leading -olp value, which the CLI's MSYS guard refused with an actionable message (documented workaround applied). No task step was abandoned.

**Issues Found:** 5

**Major Blockers:** None. The daemon/backend auto-started cleanly, MockSite was reachable, and no command failed in a way that required abandoning a step.

**Most Confusing Aspects:** 1) The only genuine trap for a first-time user: loop examples in SKILL.md §4b and loop.md place -i/-n AFTER `--`, which the CLI treats as nested-subcommand arguments (warn + failing iterations + default 3600 s/infinite loop) — the same files' grammar section says the opposite. 2) From Git Bash, a /-leading -olp value is refused by the CLI with an explanation (clear once seen, but the task's canonical invocation ./b4w.ps1 hits it while ./b4w.sh does not). 3) Built-in 'Try these next' tips that mix `get` and `get all` forms with --limit blur which command returns multiple results.

**Most Valuable Improvements:** 1) Fix the loop examples in SKILL.md §4b and loop.md so the interval/count precede `--` — this is the difference between a working 5-minute monitor and a silently failing infinite loop. 2) Make loop completion surface per-iteration failures (summary line + non-zero exit when all iterations failed) so monitoring scripts can trust exit codes. 3) Sync crawl.md's Git Bash caveat with the new MSYS guard behavior. 4) Correct the htmlsnapshot tips to use `get all ... --limit N`. 5) Update swarm.md's stale-task wording for non-interactive auto-clean.

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

#### Issue 1: loop interval/count examples put -i/-n after `--`, contradicting the documented grammar and real CLI behavior

1) skills/browser4-cli/SKILL.md §4b (~line 327) shows: `loop -- eval "..." -i 3600`. 2) skills/browser4-cli/references/loop.md Quick start (~line 24) shows: `browser4-cli loop -- eval "document.title" -i 300` ("Run eval every 5 minutes") and the Subcommand examples (~lines 77-81) show `loop -- eval "document.title" -i 300`, `loop -- snapshot -i 600`, `loop -- screenshot --full-page -i 1800`. Running the documented form: `./b4w.ps1 loop -- eval "document.title" -i 3 -n 1`

#### Issue 2: Loop reports success (exit 0, checkmark summary) when every iteration failed — silent false success for monitoring

`./b4w.ps1 loop -i 1 -n 2 -- eval` (nested eval intentionally invalid — no expression). Output: two `[ERROR] Iteration N: Error: A JavaScript expression is required...` lines, then `========================================` and `✓  Loop finished — 2 iteration(s) completed.`; `echo $?` reports 0; `loop --list` shows no persisted loop (state auto-cleared).

#### Issue 3: crawl.md Git Bash caveat is stale: CLI now hard-refuses MSYS-mangled -olp patterns instead of silently filtering all links

From Git Bash: `./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"`. crawl.md lines 180-187 and its error-handling table (~line 374) state that in this situation the crawl 'silently reports only the seed page' (exit 0).

#### Issue 4: htmlsnapshot capture/inspect 'Try these next' tips suggest `get` with --limit, which single `get` silently ignores

1) `./b4w.ps1 htmlsnapshot` on any page with an h1 — output includes the tip `htmlsnapshot get text "h1" --limit 5   # page heading`. 2) Run that exact command: `./b4w.ps1 htmlsnapshot get text "h1" --limit 5` — prints exactly ONE value and exits 0. 3) `./b4w.ps1 htmlsnapshot get --help` lists only `--page`, `--page-size`, `--all` — no `--limit`. The inspect output tips (main.rs lines 8189-8200) similarly show `get attr "img[src]" src --limit 20` (single-get form).

#### Issue 5: swarm.md stale-task handling text doesn't match observed non-interactive behavior

With completed swarm tasks tracked from a prior session, run non-interactively: `./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` (no --clear-stale).

