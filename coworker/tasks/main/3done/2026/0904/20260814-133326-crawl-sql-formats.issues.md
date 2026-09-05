# Issues: crawl-sql-formats

> **Source:** `20260814-133326-crawl-sql-formats.full.md` | **Date:** 20260814-133326 | **Mode:** dev

## Scenario Background

### Task

**Successful — both acceptance criteria met.**

- **AC5 — SQL from file with CSV output:** `./b4w.ps1 crawl --seed-file .test-sessions/seed-urls.txt --sql @.test-sessions/extract.sql --format csv -o .test-sessions/results.csv` completed in ~16s (first command also auto-started the backend), printing "2 pages crawled, 2 rows extracted." and "Results written to .test-sessions/results.csv". The CSV is valid: header row `title,price,url` plus 2 data rows — `Widget Alpha,$10.00` and `Widget Beta,$20.00` — one per seed URL.
- **AC6 — SQL from stdin with table output:** `./b4w.ps1 crawl --seed-file .test-sessions/seed-urls.txt --sql-stdin --format table < .test-sessions/extract.sql` printed an aligned grid table (header, separator, 2 rows) with columns `title | price | url` matching the query's selected fields, content identical to AC5.
- **Step 4 — comparison:** Re-ran both variants with `--format table` and diffed the outputs; after normalizing the per-run task UUID, the outputs are **byte-identical** — extraction is the same regardless of how the SQL is provided.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — rich main help with Quick Start, workflows, and per-section command map.
2. Read `../../../../../../skills/browser4-cli/SKILL.md` fully and `../../../../../../skills/browser4-cli/references/crawl.md` (X-SQL flags, output formats, MockSite section).
3. `./bin/test.ps1 mock-site` (backgrounded, log → `.test-sessions/mocksite.log`). MockSite wasn't running initially (curl → 000); startup took ~4.5 min: ~2.5 min of **silent** pre-flight Maven install, then Spring Boot boot and "Mock site is ready at http://localhost:18080/". Verified `http://localhost:18080/generated/crawl/product/1.html` → HTTP 200.
4. Inspected the fixture source (`browser4-tests/pulsar-tests-common/.../static/generated/crawl/product/1.html`) to confirm `#productTitle` and `#product-price` and the real product names (Widget Alpha/Beta).
5. Created `../../../../../../.test-sessions/seed-urls.txt` (product/1.html, product/2.html) and `../../../../../../.test-sessions/extract.sql` (`dom_first_text(dom,'#productTitle') AS title`, `dom_first_text(dom,'#product-price') AS price`, `dom_base_uri(dom) AS url` FROM `dom_load_and_select(@url,'body')`).
6. `./b4w.ps1 help crawl` — per-command help; both target invocations appear verbatim in its Examples section.
7. Ran AC5 (CSV), verified CSV content; ran AC6 (table), verified table; re-ran both with `--format table` and diffed after UUID normalization → identical.

**Decisions made:** omitted `--depth 0`/`--refresh` (not in the AC commands — the CLI compensated with "Note: Link discovery disabled (no --out-link-selector). Processing seed URLs only."); used an unquoted `@file` path from bash (worked — the PowerShell splatting warning didn't apply to the shebang invocation).

**Workarounds required:** none for the CLI itself. For MockSite: background the launcher and poll the port, because the pre-flight build phase emits no progress.

---

---

## Issues Found (5 issues)

### Issue 1: MockSite launcher gives no progress feedback during multi-minute pre-flight Maven phase

**Severity:** Medium
**Category:** UX

#### Reproduction

1. Stop MockSite. 2. Run `pwsh ./bin/test.ps1 mock-site` (fresh-ish local repo where the browser4-rest pre-flight install takes minutes). 3. Observe: after `[PASS] InstallDependencyBOM completed successfully` the output is silent for ~2.5 minutes (log file froze at 269 bytes) while a Maven java child ran at ~88% CPU; then all Maven output and `[PASS] InstallBrowser4Rest completed successfully` dump at once, followed by the Spring Boot banner.

#### Expected Behavior

Continuous progress indication during the long build phase (streamed Maven output, a spinner with elapsed time, or at least a banner saying the install phase can take 10-15 min on a cold machine), so a first-time user knows the script is working and not hung.

#### Actual Behavior

Complete silence for minutes between the two pre-flight phases. A user cannot tell whether the launcher is building, stuck, or crashed; the only clue is CPU usage. (The docstring in the script does mention 10-15 min cold, but that text is never printed at runtime.)

#### Root Cause Analysis

The pre-flight phase (Phase 2: `mvn install` of browser4-rest and transitive deps) is invoked through `Invoke-CommandAndReport` which appears to capture/buffer Maven's stdout and only flush it when the phase completes, and it prints no phase-start banner or elapsed timer. The silent gap is the duration of the Maven install.

#### Code Pointer

`bin/test.ps1 — mock-site pre-flight block (~line 580-600) and Invoke-CommandAndReport function`

#### AI Suggested Improvement

- Print a phase-start banner with the estimated duration before Phase 2 (e.g. "Installing browser4-rest and transitive deps — can take 10-15 min on first run") and an elapsed-time ticker or spinner while the phase runs
- Stream Maven output live (or at least line-buffer it) instead of dumping it on completion
- If buffering is intentional to keep the banner output clean, write progress to a separate visible stream (stderr) so `> log` captures don't hide it

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 2: crawl.md MockSite docs cover only /ec/dp/ routes — the /generated/crawl/ fixture site is undocumented

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. Read skills/browser4-cli/references/crawl.md section "Testing locally with MockSite". 2. Try to find any mention of the fixture pages used by crawl scenarios at http://localhost:18080/generated/crawl/ (product/1.html … product/11.html, index.html, category pages). 3. There is none — only /ec/dp/B0E000001-style routes and Amazon-style selectors are documented.

#### Expected Behavior

The MockSite section documents the fixture site that crawl e2e scenarios actually target (URL scheme /generated/crawl/**, the product/N.html detail pages with #productTitle/#product-price, and index/category pages), or at least links to it.

#### Actual Behavior

A first-time user running a crawl scenario against /generated/crawl/ finds zero documentation of those URLs; the doc's MockSite examples point at a different route tree (/ec/…). The selector table (#productTitle, #product-price) happens to apply, so extraction works, but the discrepancy is confusing and erodes trust in the docs.

#### Root Cause Analysis

crawl.md's MockSite section was written for the Amazon-mock (/ec/) portion of MockSite; the static /generated/crawl/** fixtures under pulsar-tests-common/src/main/resources/static were added later (used by crawl e2e scenarios such as this one) without a corresponding docs update.

#### Code Pointer

`skills/browser4-cli/references/crawl.md — "Testing locally with MockSite" section`

#### AI Suggested Improvement

- Add a subsection documenting the /generated/crawl/** fixture tree (product/N.html detail pages with #productTitle/#product-price/#product-sku, index.html hub, category/*.html listings) and a worked example
- Cross-link from the /ec/dp/ examples to the fixture site, clarifying they are two different MockSite areas
- Note the fixture detail pages intentionally include extra storefront copy so selectors must target IDs

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 3: Documented completion message differs from actual crawl output

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. Read crawl.md "Output formats → Page listing" which shows completion output `Crawl completed. 3 pages found.`. 2. Run `./b4w.ps1 crawl --seed-file <2-urls> --sql @extract.sql --format csv -o out.csv`. 3. Actual terminal output: `2 pages crawled, 2 rows extracted.` and `Results written to out.csv` — the documented phrase "Crawl completed" never appears.

#### Expected Behavior

Docs show the actual completion message (or the CLI prints the documented message).

#### Actual Behavior

Terminology drift between doc example and real output. The task's own acceptance criteria suggested "e.g. 'Crawl completed'" — a user grepping for that string after a run won't find it.

#### Root Cause Analysis

crawl.md's example output was written for an older message format; the current CLI prints "N pages crawled, N rows extracted." for SQL mode (and possibly a different string for no-SQL mode — untested here).

#### Code Pointer

`skills/browser4-cli/references/crawl.md — Output formats section (example transcript)`

#### AI Suggested Improvement

- Update the doc example transcript to the actual completion strings for both modes (SQL and page-listing)
- Consider unifying the message to always include "Crawl completed" as the first phrase for greppability

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 4: crawl commands are listed under the [Swarm] section in the main help output

**Severity:** Low
**Category:** Discoverability

#### Reproduction

1. Run `./b4w.ps1 help`. 2. Scan the section headers. 3. Observe that `crawl [url]` and its subcommands (status/result/cancel/clear/list) appear inside the `[Swarm]` block, after `swarm close` — there is no `[Crawl]` header.

#### Expected Behavior

crawl gets its own section header (e.g. `[Crawl]`) consistent with how other command families (Keyboard, Mouse, Tabs, Storage, Agent, Swarm) are grouped.

#### Actual Behavior

A first-time user scanning section headers for the crawl command family won't find it; crawl is visually subordinate to swarm, and the doc's own Command Map lists `crawl` and `swarm` as sibling approaches, which the help output contradicts.

#### Root Cause Analysis

The help command-map likely categorizes both swarm and crawl under one group (scale/parallel scraping) and renders a single `[Swarm]` header for the group.

#### Code Pointer

`cli/browser4-cli help/command-map rendering — crawl/swarm group header`

#### AI Suggested Improvement

- Add a dedicated `[Crawl]` section header for crawl and its subcommands
- Or rename the shared header to something covering both (e.g. `[Scale]` or `[Crawl & Swarm]`)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 5: PowerShell @-splatting quoting guidance leaves bash/Linux invocation of ./b4w.ps1 ambiguous

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. Read SKILL.md §5 warning: "On PowerShell, always quote @file paths (--sql \"@query.sql\") — an unquoted @ is read as the splatting operator." 2. From bash, run `./b4w.ps1 crawl --seed-file s.txt --sql @extract.sql --format csv -o out.csv` (unquoted @). 3. It works (pwsh shebang invocation passes args literally). 4. The docs never state what a Linux/macOS user invoking ./b4w.ps1 (as this evaluation requires) should do — quote or not.

#### Expected Behavior

Documentation states clearly per-shell: PowerShell console → quote @paths; bash via ./b4w.sh → wrapper quotes automatically; bash via ./b4w.ps1 → args pass through literally, no quoting needed (verified).

#### Actual Behavior

Behavior from bash is undocumented; a cautious user following the PowerShell warning may add inner quotes (`--sql '"@extract.sql"'`) whose effect on the @-file parsing is untested/unknown, or may avoid ./b4w.ps1 entirely.

#### Root Cause Analysis

The shell-quoting reference was written Windows-first (pwsh splatting); the shebang-based ./b4w.ps1 invocation path from bash is a supported entry point (per SKILL.md's own table) but its quoting semantics were never documented.

#### Code Pointer

`skills/browser4-cli/SKILL.md §5 Critical Warnings and references/shell-quoting.md`

#### AI Suggested Improvement

- Add a one-line note to the shell table: from bash, `./b4w.ps1` receives @paths literally (no splatting), no quoting needed
- Extend shell-quoting.md with a Linux/bash subsection covering @file, --sql-stdin, and eval --file/--stdin usage

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

## Overall Assessment

**Completion Status:** Successful — AC5 (CSV from --sql @file) and AC6 (table from --sql-stdin) both passed, and the step-4 comparison confirmed byte-identical extraction between input methods.

**Success Rate:** 100% — every task step succeeded on the first attempt; no CLI retries or workarounds needed.

**Issues Found:** 5

**Major Blockers:** None. The only friction was the ~2.5-minute silent pre-flight Maven phase of ./bin/test.ps1 mock-site (recorded as an issue); the CLI itself (auto backend start, crawl, both SQL input methods, both output formats) worked first try.

**Most Confusing Aspects:** 1) MockSite launcher appearing hung during the silent Maven pre-flight. 2) crawl.md's MockSite examples pointing at /ec/dp/ routes while the scenario (and fixtures) use /generated/crawl/. 3) The PowerShell-only @file quoting warning leaving bash users unsure how to pass @paths.

**Most Valuable Improvements:** 1) Stream progress output (or at least a phase banner + elapsed ticker) during bin/test.ps1 mock-site pre-flight. 2) Document the /generated/crawl/** fixture site in crawl.md. 3) Give crawl its own section header in `help` and align doc transcripts with actual output strings.

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

#### Issue 1: MockSite launcher gives no progress feedback during multi-minute pre-flight Maven phase

1. Stop MockSite. 2. Run `pwsh ./bin/test.ps1 mock-site` (fresh-ish local repo where the browser4-rest pre-flight install takes minutes). 3. Observe: after `[PASS] InstallDependencyBOM completed successfully` the output is silent for ~2.5 minutes (log file froze at 269 bytes) while a Maven java child ran at ~88% CPU; then all Maven output and `[PASS] InstallBrowser4Rest completed successfully` dump at once, followed by the Spring Boot banner.

#### Issue 2: crawl.md MockSite docs cover only /ec/dp/ routes — the /generated/crawl/ fixture site is undocumented

1. Read skills/browser4-cli/references/crawl.md section "Testing locally with MockSite". 2. Try to find any mention of the fixture pages used by crawl scenarios at http://localhost:18080/generated/crawl/ (product/1.html … product/11.html, index.html, category pages). 3. There is none — only /ec/dp/B0E000001-style routes and Amazon-style selectors are documented.

#### Issue 3: Documented completion message differs from actual crawl output

1. Read crawl.md "Output formats → Page listing" which shows completion output `Crawl completed. 3 pages found.`. 2. Run `./b4w.ps1 crawl --seed-file <2-urls> --sql @extract.sql --format csv -o out.csv`. 3. Actual terminal output: `2 pages crawled, 2 rows extracted.` and `Results written to out.csv` — the documented phrase "Crawl completed" never appears.

#### Issue 4: crawl commands are listed under the [Swarm] section in the main help output

1. Run `./b4w.ps1 help`. 2. Scan the section headers. 3. Observe that `crawl [url]` and its subcommands (status/result/cancel/clear/list) appear inside the `[Swarm]` block, after `swarm close` — there is no `[Crawl]` header.

#### Issue 5: PowerShell @-splatting quoting guidance leaves bash/Linux invocation of ./b4w.ps1 ambiguous

1. Read SKILL.md §5 warning: "On PowerShell, always quote @file paths (--sql \"@query.sql\") — an unquoted @ is read as the splatting operator." 2. From bash, run `./b4w.ps1 crawl --seed-file s.txt --sql @extract.sql --format csv -o out.csv` (unquoted @). 3. It works (pwsh shebang invocation passes args literally). 4. The docs never state what a Linux/macOS user invoking ./b4w.ps1 (as this evaluation requires) should do — quote or not.

