# Issues: crawl-advanced-extraction

> **Source:** `20260902-193524-crawl-advanced-extraction.full.md` | **Date:** 20260902-193524 | **Mode:** dev

## Scenario Background

### Task

All nine mandated steps were executed successfully and produced the expected outcome, with two data-integrity anomalies discovered along the way (both documented below).

1. **Seed file** created (`.test-sessions/seed-urls.txt`) with the three Electronics product URLs.
2. **X-SQL query file** created (`.test-sessions/extract.sql`) using the selectors documented in `crawl.md` (`#productTitle`, `#product-price`) — verified against live MockSite HTML first.
3. **Foreground crawl** (task `091e8602`): `--seed-file … --depth 0 --sql @extract.sql --refresh --parse --expires 1h --priority 1 --page-load-timeout 30s` → **3/3 pages, correct rows**: 4K OLED TV 55 / $899.99, Wireless Noise-Cancelling Headphones / $199.99, Portable Bluetooth Speaker / $49.99.
4. **Background crawl** (task `90258b87`): returned immediately with task ID; `crawl status` → PROCESSING, later `crawl result` → status OK, 3 pages, same correct extraction.
5. **`crawl list` while running** showed the background task as `processing` alongside completed tasks (16 tracked at that point).
6. **`--ignore-url-query` crawl** ran; side-by-side with an identical no-flag baseline, outputs were byte-identical (→ Issue 4).
7. **`--no-norm` crawl** ran; also byte-identical to its baseline (→ Issues 3/4).
8. **`--readonly` crawl** ran; first attempt returned **wrong content for B0E000002** (Wireless Mouse / $24.99 instead of Wireless Noise-Cancelling Headphones / $199.99), reproducible 3×; corrected after a non-readonly refresh of that URL (→ Issue 2).
9. **Final `crawl list`**: 39/39 tasks completed, none running or queued; background task result retrieved and verified.

The mock-site crawl workload itself is a clean, well-documented workflow: `crawl.md` contains a verbatim MockSite recipe (seed file + `extract.sql` + command) that worked on the first attempt, and the CLI `--help` output matches the reference documentation for every flag used.

### Execution Context

- **Preparation**: verified `pwd` = repo root; `mkdir -p .test-sessions`; confirmed MockSite healthy (HTTP 200 on `localhost:18080`); read `./b4w.ps1 help` (full output), `SKILL.md` (771 lines), `references/crawl.md` (447 lines), and `crawl --help`; verified the three product URLs and their DOM selectors via direct HTTP requests.
- **Commands** (~25 crawl invocations): the step-3 foreground crawl; the `--background` crawl; `crawl list` (mid-run and final); `crawl status`/`crawl result` on the background task; variant crawls for `--ignore-url-query`, `--no-norm`, `--readonly` — each run both with the flag and as a baseline; duplicate-seed (exact, mixed-case, query-variant) crawls to probe the documented dedup rules; link-discovery crawls from the Electronics category page (`/ec/b?node=12921...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: Link-discovery crawl page listings report identical, wrong titles for every page (no --sql)

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.sh crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -olp "/ec/dp" --depth 1 --page-load-timeout 20s  (repeat with --refresh). Expected: each of the 6 discovered /ec/dp/* pages listed with its own title. Actual: run 1 listed all 6 pages with title "Product: Wireless Noise-Cancelling Headphones" (B0E000002); run 2 listed all 6 with "Product: USB-C Hub 7-in-1" (B0E000005). URLs in the listing were correct; only the per-page titles were uniformly wrong and silently.

#### Expected Behavior

Each crawled page listed with its own title (Product: 4K OLED TV 55 / Product: Wireless Mouse / etc.), as the X-SQL extraction in the same run configuration produced per-page-correct rows.

#### Actual Behavior

All 6 pages in the page listing showed the same single page's title (the wrong page varied between runs). No error or warning was emitted. A same-configuration crawl with --sql extracted correct per-page data, so the corruption is specific to the metadata/title capture path of rapid multi-page link crawls.

#### Root Cause Analysis

Race in the backend crawl page-metadata capture: when several URLs are fetched in quick succession (link-discovery mode fetches 6 pages back-to-back, ~3-4s apart), the title recorded for each page is read from a shared tab/document state that has already advanced to another page (a later fetch), so every entry ends up stamped with whichever document won the race. Likely in the crawl portal/loader that captures {url, title} after navigation completes. Needs investigation in browser4-rest crawl executor + PulsarWebDriver portal-load path; correlate with Issue 2 (same symptom family: page content/meta bound to the wrong URL under rapid sequential fetches).

#### AI Suggested Improvement

- Capture title/URL metadata atomically with the navigation that produced the document (e.g., per-fetch unique tab or per-navigation snapshot token) instead of reading shared current-page state
- Add an integration test: link-discovery crawl over N mock pages asserting each page's stored title matches its URL
- Consider validating stored page content against the requested URL (host/path sanity check) and logging a warning on mismatch

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] High-severity silent data corruption in a mainline path — per-page metadata must be captured atomically with the navigation that produced it (per-fetch tab/snapshot token), plus the N-page integration test asserting each stored title matches its URL. This is the parent of Issue 2's corruption and the fix should cover both title capture and store writes.

---

### Issue 2: --readonly crawl silently returned another product's content (B0E000002 served B0E000006's HTML)

**Severity:** High
**Category:** Reliability

#### Reproduction

1) Run a 6-page link-discovery crawl (e.g. crawl --seed-file of the Electronics category with -olp "dp/" --depth 1 --sql @extract.sql). 2) Then run: ./b4w.ps1 crawl --seed-file seed-one.txt --depth 0 --sql @extract.sql --refresh --readonly (seed = http://localhost:18080/ec/dp/B0E000002). Actual: 3 consecutive runs returned "Wireless Mouse | $24.99" (B0E000006's product) for B0E000002, while curl and non-readonly crawls of the same URL returned "Wireless Noise-Cancelling Headphones | $199.99". After a non-readonly --refresh crawl of B0E000002, the readonly crawl returned correct data (4/4 runs afterward). Repro is not deterministic — one-off corruption observed in a ~100s window.

#### Expected Behavior

--readonly crawl returns the current content of the requested URL (same as non-readonly), or at minimum warns when it serves stored content instead of a live fetch.

#### Actual Behavior

The readonly path served a stale, corrupted stored copy: B0E000002's store entry contained B0E000006's document. Silent — no error, no cache-age indication, output looked like a successful crawl.

#### Root Cause Analysis

The crawl page store (WebDB) entry for B0E000002 was overwritten with B0E000006's HTML during the rapid multi-page fetch window (write race: store keyed by one URL while the shared tab/document belonged to another fetch — same family as Issue 1), and the --readonly fetch path reads from that store rather than fetching live, so it faithfully served the corrupted entry. Evidence: readonly results were wrong only between the 6-page crawl and a non-readonly refresh of B0E000002; live server always correct. Needs investigation: store write path (which document gets persisted under which key) and the readonly read path (why no live fetch / no staleness signal).

#### AI Suggested Improvement

- Serialize or tokenize store writes: persist only the document produced by the navigation for that URL key (see Issue 1 fix)
- Make the readonly path report that it serves cached/stored content (age, fetch time) or verify freshness with a conditional request
- Add a data-integrity self-check comparing a stored page's canonical URL/title against its key when serving

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] Same shared-tab race during rapid multi-page fetches as Issue 1 — its own root-cause analysis cites the same family and its suggested fix defers to Issue 1's. Fold the one unique requirement (--readonly must surface that it served stored content, with age, or verify freshness) into Issue 1's acceptance criteria so the High severity isn't lost in consolidation.

---

### Issue 3: crawl.md URL-deduplication rules contradicted by seed-file behavior: duplicates are all fetched

**Severity:** Medium
**Category:** Documentation

#### Reproduction

printf 'http://localhost:18080/ec/dp/B0E000001\nhttp://localhost:18080/ec/dp/B0E000001\n' > dup.txt; ./b4w.ps1 crawl --seed-file dup.txt --depth 0 --sql @extract.sql --refresh. Also case-variant seeds (…/ec/dp/B0E000001 + …/EC/dp/b0e000001) and query-variant seeds (…?src=1 + …?src=2). crawl.md states: 'Visited URLs are normalized: lowercase, trailing slash removed, query string always stripped for dedup purposes. The same URL is never visited twice within a crawl session.'

#### Expected Behavior

Per crawl.md: exact duplicate seeds collapse to 1 page; mixed-case seeds collapse (lowercase); query-variant seeds collapse (query stripped).

#### Actual Behavior

All three probes fetched every seed: exact duplicates → 2 pages crawled / 2 identical rows; mixed-case → 2 pages, uppercase URL preserved verbatim in output, second page returned empty rows; query variants → 2 pages with query strings preserved. Dedup/normalization was never applied to seed URLs in depth-0 mode.

#### Root Cause Analysis

Dedup normalization (if implemented at all) appears to apply only to URLs discovered during link following, not to seed-file URLs, which are queued as-is. crawl.md does not state this scope, and its 'URL deduplication' section is written as a universal rule. Needs verification in the crawl queue builder (backend) whether seed dedup was intended but not implemented, or docs need scoping.

#### AI Suggested Improvement

- Scope the documentation: state that seeds are always queued verbatim and dedup applies to discovered links only (or implement seed dedup if intended)
- If normalization is intended for seeds, apply lowercase/query-strip before queueing and document the precedence of --no-norm/--ignore-url-query over it

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verify in the backend queue builder whether seed dedup was intended before choosing docs-vs-code; either way crawl.md must stop stating normalization/dedup as a universal rule and say seeds are queued verbatim. Treat Issue 4's flag-scoping items as acceptance criteria for this same doc workstream so the dedup section and LoadOptions table stay mutually consistent.

---

### Issue 4: --ignore-url-query and --no-norm have no observable effect in seed-file depth-0 crawls, and docs don't scope them

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Side-by-side runs of crawl --seed-file seeds.txt --depth 0 --sql @extract.sql --refresh with (a) no flag, (b) --ignore-url-query, (c) --no-norm, using query-variant, mixed-case and exact-duplicate seeds. All outputs byte-identical in every pairing.

#### Expected Behavior

Each flag should have a describable, observable effect for the mode it is used in, and the docs should say which mode that is.

#### Actual Behavior

Zero observable difference in the bulk-fetch mode a first-time user is most likely to try them in (the docs' own 'Bulk fetch' example). crawl.md describes --ignore-url-query only as affecting 'extracted link hrefs' (link-discovery mode) and --no-norm as 'LoadOptions-level normalization' — neither statement tells the user that in seed mode these flags do nothing, so runs look like silent no-ops.

#### Root Cause Analysis

The flags operate on LoadOptions/link-href processing paths that seed-file fetches never exercise; the LoadOptions-flags table and dedup section give no mode scoping. Demonstrated demonstration gap: MockSite pages cannot exercise the link-href path because JS-rendered links carry no query strings.

#### Code Pointer

`skills/browser4-cli/references/crawl.md (LoadOptions flags table + 'URL deduplication' section)`

#### AI Suggested Improvement

- Add an 'Affects' column or note to each LoadOptions flag: 'seed URLs / discovered link hrefs / fetch request' with examples of observable behavior
- Explicitly state that seed URLs are always fetched verbatim in depth-0 mode regardless of --ignore-url-query/--no-norm
- Surface a hint when a flag cannot take effect for the current invocation mode

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] Same reproduction probes and same crawl.md sections as Issue 3 — both stem from docs overstating seed URL normalization. Keep the 'Affects' scoping column and the "seeds fetched verbatim" statement as explicit acceptance criteria for Issue 3's fix; the no-op hint suggestion only survives if the backend decision keeps the flags seed-inactive.

---

### Issue 5: Git Bash path mangling silently corrupts -olp regexes starting with '/' when invoking ./b4w.ps1

**Severity:** Medium
**Category:** Reliability

#### Reproduction

In Git Bash: ./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -olp "/ec/b" --depth 1. Actual: crawl completes with '1 pages found' and no diagnostic. With --verbose, the diagnostic shows: 'the out-link pattern 'C:/Program Files/Git/ec/b' filtered them all'. The same command via ./b4w.sh works (6 pages found).

#### Expected Behavior

The pattern /ec/b should be passed to the CLI verbatim and match the extracted links, or the CLI should detect the mangled Windows-path pattern and warn.

#### Actual Behavior

MSYS2 converted the leading-slash argument to a Windows path (C:/Program Files/Git/ec/b) before pwsh received it, silently filtering every link. Without --verbose the failure is indistinguishable from 'no links on the page'. crawl.md's Quick start and examples all use leading-slash patterns (-olp "/product/", "a.product-link" examples), and the shell-quoting reference covers PowerShell quoting but not Git Bash path mangling.

#### Root Cause Analysis

MSYS2 path conversion applies to leading-'/' arguments when bash spawns the native pwsh.exe (env shebang path of ./b4w.ps1); b4w.sh's exec path does not trigger it. Neither the docs nor the wrappers neutralize it.

#### Code Pointer

`skills/browser4-cli/references/crawl.md (link discovery examples) and skills/browser4-cli/references/shell-quoting.md`

#### AI Suggested Improvement

- Document the Git Bash rule in shell-quoting.md: quote patterns so they don't start with '/' (e.g. "dp/"), or prefix invocations with MSYS_NO_PATHCONV=1, or prefer ./b4w.sh
- Have the CLI warn when an -olp value looks like an absolute Windows path (contains ':/') — almost certainly user error
- Consider surfacing the 'filtered them all' diagnostic without requiring --verbose

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Real silent failure mode for Git Bash users, but a working workaround already exists (./b4w.sh) — primary fix is documenting the MSYS2 path-conversion rule in shell-quoting.md plus a cheap CLI warning when -olp contains ':/'. Issue 6 partly compensates by making the failure diagnosable, so do not fix Issue 6 as a substitute for this one.

---

### Issue 6: Helpful 'selector matched but pattern filtered all' diagnostic only appears with --verbose

**Severity:** Low
**Category:** UX

#### Reproduction

Run the crawl from Issue 5 without --verbose (identical flags, depth 1): output ends at 'Crawl completed. 1 pages found.' with no explanation. Re-run adding --verbose: a detailed Diagnostic + Tips block appears explaining the pattern filtered all 9 matched anchors.

#### Expected Behavior

The diagnostic explaining why 0 links were followed should appear by default — it is the single most useful error-recovery hint for link-discovery crawls that return nothing.

#### Actual Behavior

Users who omit --verbose (the documented examples never use it) get a silent near-empty result and no pointer to the cause.

#### Root Cause Analysis

The crawl completion path appears to emit the pattern-filter diagnostic only under the --verbose flag.

#### Code Pointer

`cli/browser4-cli/src/ (crawl result rendering — diagnostic emission gated on verbose)`

#### AI Suggested Improvement

- Emit the 'matched N element(s) but pattern filtered them all' diagnostic whenever all matches are filtered, regardless of --verbose
- If kept behind --verbose, print a one-line hint ('0 pages found — re-run with --verbose for a diagnostic') in the default path

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The pattern-filtered-all diagnostic is the single most useful recovery hint for empty link-discovery results and must not require --verbose; emit it whenever element matches exist but all were filtered. Low-cost fix that also turns Issue 5's mangled-pattern failure loud for users who skip the docs.

---

### Issue 7: Crawl page listing shows confusing depth labels and a '#'-suffixed seed URL when pages contain fragment-only links

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" --depth 1 (no -olp, or with a non-matching pattern). The JS-rendered page contains three <a href="#"> anchors. Output: 'Crawl completed. 1 pages found.  depth=1 | http://localhost:18080/ec/b?node=1292115012# | Category: Electronics'.

#### Expected Behavior

The seed page (depth 0, unmodified URL) either listed with its true depth and URL, or not listed at all when 0 new pages were discovered.

#### Actual Behavior

The seed itself is listed as 'depth=1' with a trailing '#' fragment appended to its URL, implying a discovery event occurred when none did; '1 pages found' reads as if the crawl found one page beyond the seed.

#### Root Cause Analysis

Fragment-only hrefs resolve against the seed URL and are queued/deduped as URL+'#', and the listing appears to include the seed when fragment links reference it, mislabeled as depth 1. Minor, but misleading output for a first-time user evaluating link discovery.

#### AI Suggested Improvement

- Strip empty fragments during link resolution/normalization so '#' hrefs resolve to the base URL and dedup against the seed
- Label listing rows with the depth at which they were actually discovered and exclude seeds from the 'pages found' count, or state explicitly that the count includes seeds

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Fragment-only '#' hrefs should be stripped during resolution so they collapse to the base URL and dedup against the seed, eliminating the phantom depth=1 discovery. Also clarify listing semantics — label rows with true discovery depth and state whether the count includes seeds — since the current output misleads first-time users about what was found.

---

### Issue 8: Progress output repeats identical lines and truncates identifiers during long crawls

**Severity:** Low
**Category:** UX

#### Reproduction

Watch any crawl of >= 3 URLs: the same 'Crawling... N pages found so far' line prints repeatedly at ~10s intervals even when nothing changed, and per-seed progress lines truncate the URL/title (e.g. 'http://localhost:18080/ec/d... / Wireless Noise-Cancelling H...').

#### Expected Behavior

Progress lines that only print on actual state change, with full or at least distinguishable identifiers.

#### Actual Behavior

Idle polling spam and truncated identifiers make long foreground crawls harder to read; with several concurrent crawls the interleaved repeats are genuinely confusing.

#### Root Cause Analysis

Foreground crawl progress poller reprints the same aggregate status each poll and abbreviates long values in the per-seed lines.

#### Code Pointer

`cli/browser4-cli/src/ (crawl foreground progress reporting)`

#### AI Suggested Improvement

- Print a line only when the reported counters (pages found / rows / seeds done) change, or use a single-line update
- Truncate URLs from the right with an ellipsis but keep the differing path suffix, or show full URLs for <= 3 concurrent items

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Purely cosmetic with no correctness impact; defer to a polish round after the High/Medium fixes land. When taken, reprint progress only when counters actually change (or use a single-line update) and truncate URLs keeping the informative path suffix rather than the shared prefix.

---

## Overall Assessment

**Completion Status:** Successful — every mandated step (seed file, X-SQL file, foreground crawl, background crawl with task ID, mid-run and final crawl list, --ignore-url-query/--no-norm/--readonly variant crawls) executed and completed; final verification crawl returned correct data 3/3. Two silent data-integrity anomalies were encountered during variant runs and are documented as High-severity reliability issues; neither blocked task completion.

**Success Rate:** 95

**Issues Found:** 8

**Major Blockers:** None. The readonly fetch path returned wrong content for one URL for a ~100s window (reproducible 3x, then healed after a non-readonly refresh); a non-deterministic store-corruption race — worth fixing before relying on --readonly crawl output.

**Most Confusing Aspects:** 1) URL-dedup and normalization behavior contradicts crawl.md (duplicate/case/query-variant seeds are all fetched). 2) --ignore-url-query and --no-norm are silent no-ops in the bulk-fetch mode shown in the docs' own example. 3) Git Bash mangles leading-slash -olp regexes under ./b4w.ps1, silently returning ~0 pages, with no diagnostic unless --verbose. 4) Without --sql, page listings in link-discovery mode carry uniformly wrong titles (2/2 runs).

**Most Valuable Improvements:** 1) Fix the page-metadata/store race so crawled titles and readonly content always belong to the requested URL. 2) Scope the normalization/dedup and LoadOptions-flag documentation to the modes where each flag acts, and warn on no-op flags. 3) Document the Git Bash leading-slash regex mangling (b4w.sh or MSYS_NO_PATHCONV=1) and emit the 'pattern filtered all' diagnostic by default.

**Usability Rating:** 6/10

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

#### Issue 1: Link-discovery crawl page listings report identical, wrong titles for every page (no --sql)

./b4w.sh crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -olp "/ec/dp" --depth 1 --page-load-timeout 20s  (repeat with --refresh). Expected: each of the 6 discovered /ec/dp/* pages listed with its own title. Actual: run 1 listed all 6 pages with title "Product: Wireless Noise-Cancelling Headphones" (B0E000002); run 2 listed all 6 with "Product: USB-C Hub 7-in-1" (B0E000005). URLs in the listing were correct; only the per-page titles were uniformly wrong and silently.

#### Issue 2: --readonly crawl silently returned another product's content (B0E000002 served B0E000006's HTML)

1) Run a 6-page link-discovery crawl (e.g. crawl --seed-file of the Electronics category with -olp "dp/" --depth 1 --sql @extract.sql). 2) Then run: ./b4w.ps1 crawl --seed-file seed-one.txt --depth 0 --sql @extract.sql --refresh --readonly (seed = http://localhost:18080/ec/dp/B0E000002). Actual: 3 consecutive runs returned "Wireless Mouse | $24.99" (B0E000006's product) for B0E000002, while curl and non-readonly crawls of the same URL returned "Wireless Noise-Cancelling Headphones | $199.99". After a non-readonly --refresh crawl of B0E000002, the readonly crawl returned correct data (4/4 runs afterward). Repro is not deterministic — one-off corruption observed in a ~100s window.

#### Issue 3: crawl.md URL-deduplication rules contradicted by seed-file behavior: duplicates are all fetched

printf 'http://localhost:18080/ec/dp/B0E000001\nhttp://localhost:18080/ec/dp/B0E000001\n' > dup.txt; ./b4w.ps1 crawl --seed-file dup.txt --depth 0 --sql @extract.sql --refresh. Also case-variant seeds (…/ec/dp/B0E000001 + …/EC/dp/b0e000001) and query-variant seeds (…?src=1 + …?src=2). crawl.md states: 'Visited URLs are normalized: lowercase, trailing slash removed, query string always stripped for dedup purposes. The same URL is never visited twice within a crawl session.'

#### Issue 4: --ignore-url-query and --no-norm have no observable effect in seed-file depth-0 crawls, and docs don't scope them

Side-by-side runs of crawl --seed-file seeds.txt --depth 0 --sql @extract.sql --refresh with (a) no flag, (b) --ignore-url-query, (c) --no-norm, using query-variant, mixed-case and exact-duplicate seeds. All outputs byte-identical in every pairing.

#### Issue 5: Git Bash path mangling silently corrupts -olp regexes starting with '/' when invoking ./b4w.ps1

In Git Bash: ./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -olp "/ec/b" --depth 1. Actual: crawl completes with '1 pages found' and no diagnostic. With --verbose, the diagnostic shows: 'the out-link pattern 'C:/Program Files/Git/ec/b' filtered them all'. The same command via ./b4w.sh works (6 pages found).

#### Issue 6: Helpful 'selector matched but pattern filtered all' diagnostic only appears with --verbose

Run the crawl from Issue 5 without --verbose (identical flags, depth 1): output ends at 'Crawl completed. 1 pages found.' with no explanation. Re-run adding --verbose: a detailed Diagnostic + Tips block appears explaining the pattern filtered all 9 matched anchors.

#### Issue 7: Crawl page listing shows confusing depth labels and a '#'-suffixed seed URL when pages contain fragment-only links

./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" --depth 1 (no -olp, or with a non-matching pattern). The JS-rendered page contains three <a href="#"> anchors. Output: 'Crawl completed. 1 pages found.  depth=1 | http://localhost:18080/ec/b?node=1292115012# | Category: Electronics'.

#### Issue 8: Progress output repeats identical lines and truncates identifiers during long crawls

Watch any crawl of >= 3 URLs: the same 'Crawling... N pages found so far' line prints repeatedly at ~10s intervals even when nothing changed, and per-seed progress lines truncate the URL/title (e.g. 'http://localhost:18080/ec/d... / Wireless Noise-Cancelling H...').

