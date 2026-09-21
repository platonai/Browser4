# Issues: webminer-structuring-routing

> **Source:** `20260902-210752-webminer-structuring-routing.full.md` | **Date:** 20260902-210752 | **Mode:** dev

## Scenario Background

### Task

All five acceptance criteria for **SKILL.md §4d (Structuring Extracted Pages / WebMiner)** were met against the locally-built CLI (`browser4-cli 4.13.13` via `./b4w.ps1`) and a freshly-booted MockSite on `localhost:18080`:

- **AC1 — Small corpus + free pipeline:** 3 product pages exported by hand, then `webminer all` ran the full encode → cluster → views pipeline. Outputs verified on disk: encoded dataset CSV, 3× `result.csv` + `clusteringInfo.txt`, and a 12-artifact views directory (`index.html`, `*.xlsx`, `*.json`, `*.html`, `prompts/`). At 3 pages (≪ 1,000), the SMILE free branch is unambiguously correct.
- **AC2 — Production-scale decision point:** Documented (`.test-sessions/work/production-scale-decision.md`): a 1,200-page/day export ≈ 18 MB HTML ≈ 75K+ feature rows exceeds the SKILL.md §4d free-tier ceiling (< 1,000 pages) → the Apache Spark commercial pipeline is the correct branch; acquisition patterns unchanged, corpus routed to the commercial deployment. Supporting evidence observed: the free run is capped at `--max-files 40` by default and clusters in-process on one JVM.
- **AC3 — Single-page acquisition:** `goto` → `htmlsnapshot` → `htmlsnapshot export --file` × 3 distinct MockSite product pages (`/ec/dp/B0E000001…003`); corpus dir verified with 3 × ~15 KB HTML files.
- **AC4 — Bulk known-URL acquisition:** `crawl --seed-file <8 URLs> --depth 0 --refresh` fetched all 8 (6 products + 2 correctly reported as "Error 404"), then `webdb export` staged the 6 cached pages as `.htm` into a WebMiner input dir, which itself ran cleanly through `webminer all`.
- **AC5 — High-throughput acquisition:** `swarm create --display-mode HEADLESS --clear-stale`, then `swarm query --seed-file <same 8 URLs> --sql @query.sql --refresh --wait` — 8 jobs completed in **6 s** (vs ~40 s sequential crawl, i.e. ~5–6× throughput). `swarm result` verified correct structured rows (`url`/`title`/`price`). Session closed.

### Execution Context

**Key Commands:**

**Decisions of note.** Quoted each arg for the bash→pwsh boundary (swarm.md documents this pitfall); used `--sql @file` (not inline SQL) per Windows quoting guidance; treated the two 404 URLs in the seed list as a deliberate error-handling probe (crawl reported them explicitly; swarm returned blank rows with status 200 — see Issue 4). Verified all temp/artifact files stayed under `.test-sessions/` (git-ignored; repo root left clean).

---

## Issues Found (9 issues)

### Issue 1: webdb export appends an unrelated auto-snapshot block (### Page / ### Snapshot) after its JSON summary

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 webdb export "http://localhost:18080/ec/dp/B0E000001" ".test-sessions/work/export-repro"
(any invocation of `webdb export` while a browser session is active; also reproduced with an invalid URL argument)

#### Expected Behavior

The command should print only its result (the JSON export summary, e.g. {"total":1,"succeeded":1,...}), like sibling commands such as `htmlsnapshot export`, which prints a single confirmation line.

#### Actual Behavior

After the JSON summary, stdout continues with an unrelated '### Page / - Page URL / - Page Title / ### Snapshot / [Snapshot](...)' block describing the CURRENT tab (a page that has nothing to do with the export), plus a tip on stderr. A new snapshot YAML file is written to disk on every export invocation (e.g. snapshot-2026-09-02T21-02-34-339Z.yml). Mixed JSON + human page dump is hard to parse and the dumped page is contextually wrong.

#### Root Cause Analysis

`webdb-export`/`webdb-normalize` are missing from the `no_snapshot_commands()` exclusion list in the CLI's generic dispatch path, so the post-command auto-snapshot routine (`post_command_snapshot`) runs after every export: it captures the current viewport of the active tab, saves a snapshot file, and prints the '### Page/### Snapshot' block. Sibling `htmlsnapshot-export` IS in the exclusion list, hence no such output there. Verified in source.

#### Code Pointer

`cli/browser4-cli/src/main.rs:362 (no_snapshot_commands() — add "webdb-export" and "webdb-normalize"; the printing routine is main.rs:1041 post_command_snapshot, invoked from the generic dispatch at main.rs:18435)`

#### AI Suggested Improvement

- Add "webdb-export" and "webdb-normalize" to the no_snapshot_commands() list so read-only cache-export commands never trigger a viewport snapshot
- Add a regression test asserting `webdb export` emits only the JSON summary (no snapshot file written, no '### Page' block)
- Consider suppressing the trailing tip on stderr for non-interactive commands or routing it through the same --quiet logic as the snapshot block

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified defect with a trivial, precedent-backed fix — mirror the existing `htmlsnapshot-export` entry in `no_snapshot_commands()` — plus a worthwhile regression test asserting no snapshot file and no '### Page' block. The stderr-tip suppression is optional scope creep; do it only if the tip is equally noisy for other non-interactive commands.

---

### Issue 2: webdb.md bundled reference documents rejected hyphenated command forms (webdb-export) in every example

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Open skills/browser4-cli/references/webdb.md and run its Quick start literally:
browser4-cli webdb-export "https://example.com" ./out
Actual session result:
./b4w.ps1 webdb-export "http://localhost:18080/ec/dp/B0E000001" ".test-sessions/work/export-repro2"
→ 'Error: Unsupported command form: webdb-export. Use 'browser4-cli webdb export' instead.'

#### Expected Behavior

The bundled reference (the file a user opens from the SKILL.md reference map) should teach the supported invocation form for every example.

#### Actual Behavior

webdb.md uses the hyphenated form `webdb-export`/`webdb-normalize` in its Quick start, When-to-Use, Commands, and Common patterns sections (every executable example). The CLI rejects that form with an error, so a first-time user following the reference fails on the first command of the documented workflow (AC4's 'export crawled pages after a crawl' pattern). The error message is helpful and self-correcting, but the reference is stale relative to both the top-level help ('webdb export') and SKILL.md §3 ('webdb export', 'webdb normalize').

#### Root Cause Analysis

The CLI migrated user-facing command forms from hyphenated (webdb-export) to spaced prefix form (webdb export) — legacy aliases are deliberately rejected with a redirecting error (main.rs:17223). The bundled reference file skills/browser4-cli/references/webdb.md was not updated to the new syntax, while SKILL.md and help output were.

#### Code Pointer

`skills/browser4-cli/references/webdb.md (all examples use webdb-export/webdb-normalize)`

#### AI Suggested Improvement

- Update every example in webdb.md to the spaced forms `webdb export` / `webdb normalize`
- Add an explicit note in webdb.md that hyphenated aliases are rejected and that help normalizes to the spaced form (help.rs:40 already maps it)
- Grep the remaining reference files for other hyphenated forms the CLI now rejects as part of the same sweep

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Real stale-documentation defect against a deliberate CLI migration; the self-correcting error only helps users who already ran a command. Keep the suggested sweep to *all* reference files, not just webdb.md, and add the explicit "hyphenated aliases are rejected" note so the file documents the migration rather than silently churning examples.

---

### Issue 3: swarm close reports completed jobs as 'marked as failed (closed)' although they completed successfully

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 swarm create --display-mode HEADLESS --clear-stale
./b4w.ps1 swarm query --seed-file .test-sessions/work/seed-urls.txt --sql @.test-sessions/work/query.sql --refresh --wait
(all 8 jobs print 'done')
./b4w.ps1 swarm result <task-id>   (results fetched)
./b4w.ps1 swarm close
→ 'Swarm session closed. Browser terminated. All pending tasks were already finished. 8 locally tracked pending task(s) marked as failed (closed).'
Immediately after: ./b4w.ps1 swarm list shows all 8 tasks as STATUS=completed.

#### Expected Behavior

Closing the swarm session after jobs completed should either report 'N task(s) cleaned up' or silently close; it must not claim successfully completed tasks were marked as failed.

#### Actual Behavior

The close message is self-contradictory ('All pending tasks were already finished' AND '8 locally tracked pending task(s) marked as failed (closed)'). `swarm list` afterwards shows the same tasks as 'completed', so the 'failed (closed)' marking either never took effect or is immediately contradicted by the live backend query. A user watching job status would reasonably conclude their 8 extraction jobs failed.

#### Root Cause Analysis

At close time the CLI marks every locally tracked task that is not in a locally-terminal state as 'failed (closed)' without reconciling with backend state (tasks had finished and results were fetched, yet local tracking still considered them pending — likely a local-state transition gap after `swarm result`). The message wording (main.rs:9565) then overstates what happened. Needs verification of where local task state is transitioned after result fetch.

#### Code Pointer

`cli/browser4-cli/src/main.rs:9470-9565 (swarm close local-task finalization; message at 9565)`

#### AI Suggested Improvement

- Before marking tasks failed(closed), re-query backend completion (isDone/statusCode) and label only genuinely unfinished tasks as failed; report completed ones as 'completed/archived'
- Rephrase the message to distinguish 'aborted while queued/processing' from 'finished, removed from tracker'
- Add a unit/integration test: swarm query --wait → swarm result → swarm close must not emit 'marked as failed' for completed tasks

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The misleading 'marked as failed (closed)' wording is verified and must be fixed, but the claimed local-state transition gap after `swarm result` is unverified — investigate it during the fix rather than assuming it. Minimum deliverable: reword the close message to distinguish 'aborted while queued/processing' from 'finished, removed from tracker' and reconcile against backend completion before labeling anything 'failed'.

---

### Issue 4: swarm query results do not surface page HTTP failures: a 404 URL completes with statusCode 200 and blank fields

**Severity:** Low
**Category:** Product

#### Reproduction

Run ./b4w.ps1 swarm query --seed-file <file-with-nonexistent-page> --sql @query.sql --wait, then ./b4w.ps1 swarm result <task-id> for the 404 URL (e.g. http://localhost:18080/ec/dp/B0E000007 → title '' and price '' rows).

#### Expected Behavior

A job whose target page returned HTTP 404 should surface the failure (non-200 statusCode, error message, or an explicit 'page missing' marker) so the user can distinguish 'page does not exist' from 'extraction selector did not match'.

#### Actual Behavior

The swarm job for the 404 product URL completed with statusCode 200, error null, message null, and a resultSet row with empty strings for title/price. The crawl path, by contrast, explicitly reported the page as 'Error 404' in its output. In swarm, rows are silently lost — a pipeline consuming swarm results cannot tell a missing page from a failed selector.

#### Root Cause Analysis

The swarm worker/scrape path appears to treat any HTTP response (including 404 bodies) as a successful page load and never records the HTTP status on the job result. Whether the page's HTTP status is available at the X-SQL layer or dropped in the job result assembly needs backend investigation (browser4-rest swarm executor / result payload).

#### AI Suggested Improvement

- Include the page HTTP status (or a resolved-URL/page-existence flag) in swarm job results, e.g. an httpStatus field alongside resultSet/pageContentBytes
- Make `swarm status`/`swarm result` report jobs whose page returned 4xx/5xx as failed or at least annotate rows
- Document the current behavior in swarm.md's 'Errors & Recovery' table if a backend change is deferred

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Valid data-fidelity gap, but the fix is a backend (browser4-rest) job-result schema change requiring a product decision on versioning — out of scope for a CLI usability pass and Low severity. Land the cheap half now: document the 404→statusCode-200 behavior in swarm.md's 'Errors & Recovery' table and note the httpStatus field as the planned remediation.

---

### Issue 5: crawl progress output duplicates and interleaves two reporting formats with unchanged counts

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/work/seed-urls.txt --depth 0 --refresh
Observed output begins:
Crawling... 1 pages found so far
Crawling... 1/8 pages found (6s elapsed)
Crawling... 1 pages found so far
Crawling... 2 pages found so far
Crawling... 3 pages found so far
Crawling... 3 pages found so far
Crawling... 4 pages found so far ...

#### Expected Behavior

Progress should be monotonic and emitted in one consistent format (e.g. 'Crawling... 3/8 pages (12s elapsed)'), with no repeated same-count lines.

#### Actual Behavior

Two progress printers interleave: a bare 'Crawling... N pages found so far' line (repeated even when the count did not change) and a periodic 'N/8 pages found (Xs elapsed)' line. The same count (e.g. '1', '3') prints multiple times consecutively, which reads as stalling and makes elapsed-time tracking noisy for scripts.

#### Root Cause Analysis

Two independent progress-reporting paths print to stdout without coordination: a client-side printer on status poll (cli/browser4-cli/src/main.rs:11452, re-emits on every poll regardless of count change) and a server/elapsed-format reporter. The bare printer should suppress lines whose count is unchanged.

#### Code Pointer

`cli/browser4-cli/src/main.rs:11452 (cli_println!("Crawling... {} pages found so far", pages_found))`

#### AI Suggested Improvement

- Track last printed count and suppress repeats; prefer the single 'N/total pages found (Xs elapsed)' format
- When counts do change, emit only the richer format (with total and elapsed) to keep one consistent shape for humans and parsers

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear CLI-local bug with a specific line pointer; suppress same-count repeats and converge on the single 'N/total pages (Xs elapsed)' format as canonical. Add the unchanged-count regression check to the fix so the duplicate printer can't silently regress.

---

### Issue 6: webminer and experience command families are filed under the '[Skills]' help section

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run ./b4w.ps1 help and scroll to the [Skills] section: it lists `skills` (agent instructions), the 7 `webminer` entries (an ML data pipeline) AND the 4 `experience` entries (agent memory) under one header. The [Agent] section holds extract/summarize/chat/agent but not experience.

#### Expected Behavior

Help sections should group related command families: WebMiner (a local data-processing pipeline) deserves its own section or placement near data/extraction families, and `experience` belongs with [Agent] (or its own section).

#### Actual Behavior

A first-time user scanning help for the WebMiner commands advertised in SKILL.md §4d finds them under '[Skills]' — which per the section content means 'AI agent instruction files' — and must already know where to look. The [Skills] header ends up covering three unrelated families.

#### Root Cause Analysis

CommandDef.category is Category::Skills for skills, all webminer-* (e.g. commands.rs:710 webminer-all) and experience-* (e.g. commands.rs:3623 experience-save), and help.rs renders sections purely by category, producing one mixed [Skills] block.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:710 (webminer-all category: Category::Skills), commands.rs:3623 (experience-save), and the help section renderer in cli/browser4-cli/src/help.rs`

#### AI Suggested Improvement

- Introduce a dedicated category for webminer (e.g. Category::WebMiner or 'Pipeline') and assign all webminer-* commands to it so help renders a distinct '[WebMiner]' block
- Reassign experience-* to the Agent category (or a 'Memory' category) so it appears near agent/chat commands
- Add a help-category consistency test asserting each prefix family (webminer, experience, crawl, swarm) maps to exactly one section

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate discoverability defect — '[Skills]' currently covers three unrelated families. Prefer dedicated Category::WebMiner plus moving experience-* under Agent (or its own 'Memory' category); the proposed per-family single-section test is reasonable but allow for aliases/shared families so it isn't brittle.

---

### Issue 7: help output for value-taking options omits value placeholders (e.g. '--max-files' vs '--max-files <n>')

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 webminer all --help
Options are rendered as:
  --max-files    Maximum number of HTML files to process (default: 40)
  --output       Where to write the clustered results (default: <html-dir>-ml-output)
  --resume       Resume a previous run (optionally with a project id)
(compare the global section of the same help: '--timeout <seconds>', '--server <url>').

#### Expected Behavior

Options that take a value should show a placeholder (--max-files <n>, --output <dir>) so users know a value must follow; boolean flags should be visually distinct from value-taking flags.

#### Actual Behavior

webminer all's options render as bare flags with no indication that --max-files/--output require a value argument, while --resume (boolean) looks identical in shape. A first-time user cannot tell flag-taking-value from boolean switches; the SKILL.md files carry the value info but CLI help alone does not.

#### Root Cause Analysis

OptionDef has no value-placeholder concept; help.rs renders every option as '--name    description'. Global options encode placeholders directly in their name text ('--timeout <seconds>') — an ad-hoc convention not applied to command options.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:708-740 (webminer-all OptionDefs) and the option renderer in cli/browser4-cli/src/help.rs`

#### AI Suggested Improvement

- Add an optional 'value placeholder' field (or value_type) to OptionDef and render '--max-files <n>' / '--output <dir>' / '--resume' (no placeholder) in help
- Apply the same convention to crawl/swarm/htmlsnapshot options that take values (--seed-file <file>, --sql <query>) for consistency with global options

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Genuine usability gap — boolean vs. value-taking flags are visually indistinguishable. The OptionDef placeholder field is the right fix and should be applied across webminer/crawl/swarm/htmlsnapshot options for consistency; if the full refactor is large, first backfill the cheap convention (encode `<placeholder>` in name text) so help is at least correct everywhere.

---

### Issue 8: MockSite dev boot (bin/test.ps1 mock-site) fails intermittently with 'Failed connecting to the daemon in 4 retries' and offers no recovery

**Severity:** Low
**Category:** Reliability

#### Reproduction

1) pwsh -File ./bin/test.ps1 mock-site --force  (first attempt ran concurrently with a b4w.ps1 Maven build; failed in browser4-common kotlin compile with 'Failed connecting to the daemon in 4 retries')
2) Re-run alone (no concurrent build): fails again with the same error
3) Manually kill stale KotlinCompileDaemon java processes (three 8 GB daemons existed)
4) Third run succeeds and MockSiteApplication starts on 18080

#### Expected Behavior

The mock-site launcher should start reliably (it is the documented prerequisite for test scenarios: 'test.ps1 mock-site --force'). On a Kotlin-daemon failure it should surface a diagnosable error and/or recover (retry, daemon cleanup), since scenario instructions tell users to retry only once.

#### Actual Behavior

The launcher failed twice in a row with an opaque Kotlin compiler daemon connection error and no hint about stale daemons; recovery required manual process inspection/killing (taskkill of KotlinCompileDaemon PIDs). After cleanup the same command succeeded. The two failed attempts also left the previously-running stale MockSite dead, blocking the scenario until a manual restart.

#### Root Cause Analysis

Kotlin-maven-plugin (2.3.21) could not connect to a usable compile daemon. Contributing factors: (a) the first attempt overlapped another Maven build (daemon contention — user error), but (b) the second isolated attempt still failed, indicating stale/crowded daemon state left over from earlier crashed builds (three lingering daemons, ~8 GB each). bin/test.ps1 has no daemon-health preflight or automatic retry, so the failure surfaced as a generic Maven [ERROR] with a '-rf :browser4-common' resume hint only.

#### Code Pointer

`bin/test.ps1 (Invoke-MockSiteBoot, ~line 455-600)`

#### AI Suggested Improvement

- Add a Kotlin daemon preflight/cleanup step before the mock-site Maven build (detect zombie KotlinCompileDaemon processes from prior failed builds and kill them, or run with one automatic retry)
- Surface a clearer error message when kotlin-maven-plugin reports daemon connection failures, pointing at 'kill stale java/KotlinCompileDaemon processes and re-run'
- Document that concurrent Maven-driven builds (b4w.ps1 build + mock-site boot) must not run simultaneously

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The launcher failure and manual-recovery requirement are real and it gates a documented scenario prerequisite, but the root cause is environmental (stale daemons, concurrent builds). Prefer one automatic retry plus a diagnosable error message over auto-killing daemon processes — indiscriminate taskkill can kill a *legitimate* concurrent build's daemons; document the no-concurrent-Maven-builds rule regardless.

---

### Issue 9: Timestamps in snapshot/capture output and snapshot filenames are UTC without any indication, so files appear to belong to the previous day

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 htmlsnapshot at e.g. 04:58 local time (UTC+8) on 2026-09-03:
Output shows 'captured 2026-09-02T20:57:58.678Z' and the auto-snapshot path is snapshot-2026-09-02T20-57-49-932Z.yml.

#### Expected Behavior

Timestamps should be in local time, or explicitly marked UTC everywhere they appear so the file date does not contradict the user's clock.

#### Actual Behavior

The capture timestamp and the YAML snapshot filenames render in UTC while the surrounding output uses local conventions; a user in UTC+8 correlating files by date sees 'yesterday' timestamps for files created now.

#### Root Cause Analysis

The CLI renders the backend's UTC ISO-8601 capture timestamp verbatim in human-readable output and encodes the same UTC time in generated filenames, without converting to local time or drawing attention to the Z suffix.

#### Code Pointer

`cli/browser4-cli/src/snapshot.rs (resolve_output_path / snapshot filename generation) and the htmlsnapshot capture output renderer in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Convert displayed capture times to local time for human-readable output (keep UTC in --json for determinism)
- Or append '(UTC)' / keep a visible 'Z' in every human-facing timestamp and snapshot filename
- Keep filename timestamps sorted/stable (UTC is fine for sorting) but surface local time in the printed 'captured …' summary

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Note the 'Z' suffix is itself a UTC indication, so the real change is display-only: keep UTC in filenames (stable sorting, unambiguous artifacts) and convert the human-facing 'captured …' line to local time. The 'without any indication' phrasing overstates the problem, but the suggested fix is correct and cheap.

---

## Overall Assessment

**Completion Status:** Successful — all five acceptance criteria (AC1-AC5) were demonstrated end-to-end against the locally-built CLI (browser4-cli 4.13.13) and MockSite on localhost:18080; the free WebMiner pipeline ran on two real local corpora and the production-scale decision point was documented.

**Success Rate:** 95%

**Issues Found:** 9

**Major Blockers:** None. Environment setup consumed the most effort: the MockSite pre-running on port 18080 was stale (no /ec routes), and two attempts to boot the fresh mock site failed on Kotlin compiler daemon connection errors until stale KotlinCompileDaemon processes were killed manually. After that, every browser4-cli workflow executed as documented.

**Most Confusing Aspects:** 1) webdb export takes comma-separated URLs while crawl/swarm accept --seed-file files — passing a file path is silently treated as a URL (normalized bizarrely to https://cn.bing.com/) with no usage hint. 2) webdb export prints an unrelated current-page snapshot block after its JSON summary. 3) swarm close announces completed jobs as 'marked as failed (closed)' while swarm list immediately shows them completed. 4) The bundled webdb.md reference teaches command forms the CLI rejects. 5) UTC timestamps make snapshot files look a day old in UTC+8.

**Most Valuable Improvements:** 1) Add webdb-export/webdb-normalize to the no_snapshot_commands() exclusion list (fixes the stray snapshot output; one-line change with a clear code pointer). 2) Update webdb.md to the spaced command forms the CLI accepts. 3) Reconcile swarm close's 'failed (closed)' labeling with actual backend completion state. 4) Surface HTTP status (404) in swarm results so empty extractions are distinguishable from failed page loads.

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

#### Issue 1: webdb export appends an unrelated auto-snapshot block (### Page / ### Snapshot) after its JSON summary

./b4w.ps1 webdb export "http://localhost:18080/ec/dp/B0E000001" ".test-sessions/work/export-repro"
(any invocation of `webdb export` while a browser session is active; also reproduced with an invalid URL argument)

#### Issue 2: webdb.md bundled reference documents rejected hyphenated command forms (webdb-export) in every example

Open skills/browser4-cli/references/webdb.md and run its Quick start literally:
browser4-cli webdb-export "https://example.com" ./out
Actual session result:
./b4w.ps1 webdb-export "http://localhost:18080/ec/dp/B0E000001" ".test-sessions/work/export-repro2"
→ 'Error: Unsupported command form: webdb-export. Use 'browser4-cli webdb export' instead.'

#### Issue 3: swarm close reports completed jobs as 'marked as failed (closed)' although they completed successfully

./b4w.ps1 swarm create --display-mode HEADLESS --clear-stale
./b4w.ps1 swarm query --seed-file .test-sessions/work/seed-urls.txt --sql @.test-sessions/work/query.sql --refresh --wait
(all 8 jobs print 'done')
./b4w.ps1 swarm result <task-id>   (results fetched)
./b4w.ps1 swarm close
→ 'Swarm session closed. Browser terminated. All pending tasks were already finished. 8 locally tracked pending task(s) marked as failed (closed).'
Immediately after: ./b4w.ps1 swarm list shows all 8 tasks as STATUS=completed.

#### Issue 4: swarm query results do not surface page HTTP failures: a 404 URL completes with statusCode 200 and blank fields

Run ./b4w.ps1 swarm query --seed-file <file-with-nonexistent-page> --sql @query.sql --wait, then ./b4w.ps1 swarm result <task-id> for the 404 URL (e.g. http://localhost:18080/ec/dp/B0E000007 → title '' and price '' rows).

#### Issue 5: crawl progress output duplicates and interleaves two reporting formats with unchanged counts

./b4w.ps1 crawl --seed-file .test-sessions/work/seed-urls.txt --depth 0 --refresh
Observed output begins:
Crawling... 1 pages found so far
Crawling... 1/8 pages found (6s elapsed)
Crawling... 1 pages found so far
Crawling... 2 pages found so far
Crawling... 3 pages found so far
Crawling... 3 pages found so far
Crawling... 4 pages found so far ...

#### Issue 6: webminer and experience command families are filed under the '[Skills]' help section

Run ./b4w.ps1 help and scroll to the [Skills] section: it lists `skills` (agent instructions), the 7 `webminer` entries (an ML data pipeline) AND the 4 `experience` entries (agent memory) under one header. The [Agent] section holds extract/summarize/chat/agent but not experience.

#### Issue 7: help output for value-taking options omits value placeholders (e.g. '--max-files' vs '--max-files <n>')

./b4w.ps1 webminer all --help
Options are rendered as:
  --max-files    Maximum number of HTML files to process (default: 40)
  --output       Where to write the clustered results (default: <html-dir>-ml-output)
  --resume       Resume a previous run (optionally with a project id)
(compare the global section of the same help: '--timeout <seconds>', '--server <url>').

#### Issue 8: MockSite dev boot (bin/test.ps1 mock-site) fails intermittently with 'Failed connecting to the daemon in 4 retries' and offers no recovery

1) pwsh -File ./bin/test.ps1 mock-site --force  (first attempt ran concurrently with a b4w.ps1 Maven build; failed in browser4-common kotlin compile with 'Failed connecting to the daemon in 4 retries')
2) Re-run alone (no concurrent build): fails again with the same error
3) Manually kill stale KotlinCompileDaemon java processes (three 8 GB daemons existed)
4) Third run succeeds and MockSiteApplication starts on 18080

#### Issue 9: Timestamps in snapshot/capture output and snapshot filenames are UTC without any indication, so files appear to belong to the previous day

Run ./b4w.ps1 htmlsnapshot at e.g. 04:58 local time (UTC+8) on 2026-09-03:
Output shows 'captured 2026-09-02T20:57:58.678Z' and the auto-snapshot path is snapshot-2026-09-02T20-57-49-932Z.yml.

