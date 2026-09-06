# Issues: crawl-advanced-extraction

> **Source:** `20260905-171533-crawl-advanced-extraction.full.md` | **Date:** 20260905-171533 | **Mode:** dev

## Scenario Background

### Task

All 9 steps succeeded against MockSite (localhost:18080), and the full evaluation report is saved at `.test-sessions/evaluation-report-crawl.md` (all scratch files kept under `.test-sessions/`).

**Extracted data** (identical across the foreground, background, and `--readonly` crawls):

| url | title | price |
|---|---|---|
| http://localhost:18080/ec/dp/B0E000001 | 4K OLED TV 55 | $899.99 |
| http://localhost:18080/ec/dp/B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 |
| http://localhost:18080/ec/dp/B0E000003 | Portable Bluetooth Speaker | $49.99 |

- Seed file `.test-sessions/ec-seed-urls.txt`; X-SQL file `.test-sessions/extract-title-price.sql` (`#productTitle` / `#product-price`, verified against live markup first).
- Foreground crawl (`--depth 0 --sql @file --format table --refresh --parse --expires 1h --priority 1 --page-load-timeout 30s`): exit 0, 3 rows in ~25 s.
- Background crawl: task `66c3f13e-14dc-47f5-b523-4731f04cfaf4`, submitted in 0.56 s; `crawl list` mid-run showed `processing`, later `completed`; `crawl status` → `OK` with all rows.
- `--ignore-url-query`, `--no-norm`, `--readonly` crawls all ran cleanly; the first two produced output byte-identical to their baselines at depth 0 (finding #4 below).

### Execution Context

1. **Prep** — verified repo root; checked MockSite (HTTP 200); read `SKILL.md` and `references/crawl.md` fully; ran `./b4w.ps1 help` and `./b4w.ps1 crawl --help`; confirmed every required flag exists.
2. **Seed + query files** created under `.test-sessions/`; selectors validated against the fixture HTML.
3. **Foreground crawl** → 3 rows extracted (first command auto-started the backend; 24.7 s wall).
4. **Background crawl** → task ID noted; **step 5** `crawl list` while running showed `processing`.
5. **Steps 6–8** — flag-variant crawls, each with a control run: distinct-query seeds (±`--ignore-url-query`), trailing-slash seeds (±`--no-norm`, MockSite 404s the slash variant), and a full `--readonly` extraction crawl.
6. **Step 9** — final `crawl list`: all 13 session tasks completed; backg...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: Invalid values for --expires/--page-load-timeout/--priority are silently accepted with exit 0

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/one-url.txt --depth 0 --expires 1x → exit 0, crawl 'completed'
./b4w.ps1 crawl --seed-file .test-sessions/one-url.txt --depth 0 --page-load-timeout banana → exit 0, crawl 'completed'
./b4w.ps1 crawl --seed-file .test-sessions/one-url.txt --depth 0 --priority -5 → exit 0, crawl 'completed'

#### Expected Behavior

Invalid values for typed options should be rejected with a clear message and non-zero exit (as the CLI already does for --format: 'Invalid --format ... Expected: json, csv, or table'). The user must be able to detect that '--expires 1x' or '--priority -5' was not applied.

#### Actual Behavior

All three invalid values produced exit 0 and a normal 'Crawl completed. 1 pages found.' The crawl runs with the option silently dropped/defaulted; the user believes the requested 1h expiry / 30s timeout / priority was honored.

#### Root Cause Analysis

The CLI parses these crawl options as raw strings and forwards them to the backend without type/duration validation; backend LoadOptions parsing fails softly and falls back to defaults, with no error surfaced to the CLI. Investigation needed to confirm whether the backend logs the parse failure.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2994 (crawl OptionDef block) — add CLI-side validation for --expires (duration), --page-load-timeout (duration/seconds), --priority (integer) mirroring the existing --format validation`

#### AI Suggested Improvement

- Validate --expires and --page-load-timeout client-side against the documented duration format (\d+(ms|s|m|h|d)) and reject anything else with a non-zero exit naming the offending value
- Validate --priority is a finite integer; reject negatives or clamp with an explicit warning
- If validation must stay server-side, propagate the backend parse failure into the crawl result/error so the CLI exits non-zero

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified — `commands.rs:3047-3054` forwards all three values as raw strings with no validation, so `--expires 1x` silently becomes a default. Add CLI-side validation mirroring `--format`'s reject-with-nonzero-exit pattern, and define one canonical duration format in this change since Issue 6's help text must match it.

---

### Issue 2: Fragment-only hrefs ('#') treated as out-links: silent empty discovery, missing warning, and trailing-'#' URL artifact

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -olp "node=" -d 1 --refresh
→ 'Crawl completed. 1 pages found.' with the seed listed as 'http://localhost:18080/ec/b?node=1292115012#' and zero out-link pages fetched; crawl status shows linksDiscovered: 1; no 'Link discovery found no out-links' warning. Same command without -olp discovers 7 links and fetches 6 pages (so the page does contain discoverable links).

#### Expected Behavior

Fragment-only anchors (href='#', '#details') cannot navigate to a new document and should be skipped during link extraction. When a discovery crawl fetches no NEW pages, the CLI should print the documented diagnostic warning (crawl.md: '⚠ Link discovery found no out-links'). Reported page URLs should be canonical (no '#' appended).

#### Actual Behavior

href='#' nav anchors (present on MockSite's browser-served Electronics listing) are resolved against the page URL and counted as discovered out-links. With -olp 'node=' they match after resolution (the seed URL carries '?node=…'), dedupe against the visited seed, and yield linksDiscovered=1 — so the CLI's warning gate (links_discovered == 0) never fires and the crawl reports a hollow success. The page listing also renders the seed URL with a spurious trailing '#'.

#### Root Cause Analysis

Backend out-link extraction resolves fragment-only hrefs to the current page instead of discarding them; the CLI warning gate at main.rs only checks linksDiscovered == 0, so 'discovered but all already-visited' is treated as success. The trailing '#' comes from server-side URL canonicalization that re-appends an empty fragment (visible only in depth>=1 crawls; depth-0 reports seed URLs verbatim). --out-link-pattern is applied to the resolved absolute URL, which is not documented.

#### Code Pointer

`cli/browser4-cli/src/main.rs:12414 (warning gate: discovery_requested && links_discovered == 0) and backend out-link resolution in browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt (normalizeForVisit ~line 1348); the '#'-appending URL canonicalization is server-side (needs follow-up)`

#### AI Suggested Improvement

- Filter hrefs that are empty or fragment-only ('#', '#section') before resolution — they can never lead to a new document
- Change the warning gate to fire when discovery was requested and zero NEW pages were fetched from out-links (not just linksDiscovered == 0)
- Fix URL canonicalization/reporting to not append a bare '#' when the fragment is empty

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified the warning gate only checks `links_discovered == 0`, so fragment-only hrefs resolving back to the seed yield a hollow success. Filter empty/fragment-only hrefs before resolution (primary fix), and treat the gate change as a deliberate semantic decision — firing when zero NEW pages came from out-links needs a revised gate comment and CLI regression tests; fix the trailing-'#' canonicalization server-side, since Issue 5's MockSite '#' anchors are what trigger this in practice.

---

### Issue 3: crawl result ignores --format and is byte-identical to crawl status; docs promise 'same output as a foreground crawl'

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-urls.txt --depth 0 --sql @.test-sessions/extract-title-price.sql --format table --background (task 66c3f13e-14dc-47f5-b523-4731f04cfaf4)
./b4w.ps1 crawl result 66c3f13e-14dc-47f5-b523-4731f04cfaf4 > r.json
./b4w.ps1 crawl status 66c3f13e-14dc-47f5-b523-4731f04cfaf4 > s.json
diff r.json s.json → identical

#### Expected Behavior

crawl.md: 'crawl result — Returns the same output as a foreground crawl: page listing (without --sql) or formatted extraction data (with --sql).' Since the task was submitted with --format table, result should print the aligned table. crawl status should print a compact human status (CREATED/PROCESSING/completed, pages so far, progress).

#### Actual Behavior

crawl result returns the raw server JSON envelope (pages[], extracted[], seedStatuses[]) regardless of the submission's --format. crawl status on a completed task returns exactly the same raw JSON — diff shows the files byte-identical — so the two commands are indistinguishable and neither gives the human-readable view a foreground crawl produces.

#### Root Cause Analysis

handle_crawl_result (main.rs) fetches the raw task JSON and prints it without re-applying the stored --format; crawl status shares the same code path when the task is terminal. The CLI-side table/CSV/JSON formatting applied to foreground crawls is not applied to stored tasks.

#### Code Pointer

`cli/browser4-cli/src/main.rs:11615 (handle_crawl_result) and crawl-list/status polling path at cli/browser4-cli/src/main.rs:11352; doc: skills/browser4-cli/references/crawl.md 'crawl result' section`

#### AI Suggested Improvement

- Persist the submission's --format with the task and have crawl result render table/csv/json output exactly like a foreground crawl (add --format override on the result command)
- Make crawl status print a compact human summary (state, pages found/discovered, elapsed, seed statuses) and only dump full JSON with an explicit --json flag
- Update crawl.md wording to state that crawl result returns the raw JSON envelope until rendering is implemented

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified `handle_crawl_status` and `handle_crawl_result` are byte-for-byte the same raw-JSON printer, contradicting `crawl.md:407-409`. Stage the work: first make `crawl status` a compact human summary and correct the doc promise, then persist `--format` with the task and route `crawl result` through the same table/csv/json formatter used by foreground crawls.

---

### Issue 4: --ignore-url-query and --no-norm have no observable effect in depth-0 mode; help text overpromises

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-dup-query.txt --depth 0 → 2 pages found, URLs reported verbatim with query strings
./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-dup-query.txt --depth 0 --ignore-url-query → identical output
./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-trailing-slash.txt --depth 0 → 2 pages (slash variant 404s)
./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-trailing-slash.txt --depth 0 --no-norm → identical output

#### Expected Behavior

Help says --ignore-url-query 'Remove query parameters from URLs during normalization' and --no-norm 'Disable URL normalization' — a first-time user following the task 'run a crawl with --ignore-url-query to strip query parameters from URLs ... and observe the difference' expects the fetched/reported URLs to lose query strings, or at least some observable difference.

#### Actual Behavior

At depth 0 (bulk fetch — the mode both the docs' quick start and this task use), output is byte-identical with and without either flag: seed URLs are fetched and reported verbatim (query strings intact; trailing-slash variant fetched as-is and 404s). The flags only affect discovered out-link hrefs in depth>=1 crawls (crawl.md 'URL deduplication' section) — but even there the effect is hard to observe, and crawl.md never states seeds are exempt.

#### Root Cause Analysis

The options are forwarded to backend LoadOptions where they apply to out-link normalization, while depth-0 seeds bypass that path entirely. CLI help text (commands.rs OptionDef descriptions) describes the general intent without the scope caveat, so users cannot predict when the flags do nothing.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2994 (OptionDef descriptions for ignore-url-query/no-norm); docs: skills/browser4-cli/references/crawl.md 'URL deduplication' section`

#### AI Suggested Improvement

- Reword help: '--ignore-url-query: strip query parameters from discovered out-link hrefs before loading (no effect on seed URLs in depth-0 bulk fetch)' and likewise for --no-norm
- Add a crawl.md note: 'These flags only affect link discovery; depth-0 seed crawls are unaffected'
- Consider printing a stderr note when the flags are given without link discovery so users learn they have no effect

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified the help strings at `commands.rs:2994-2995` overpromise while `crawl.md:336-344` already scopes the flags to extracted link hrefs — depth-0 seeds fetched verbatim is correct seed semantics, not a bug to change. Fix the OptionDef descriptions to state the link-discovery-only scope, add the crawl.md note, and update the evaluation task materials that promise an observable depth-0 difference.

---

### Issue 5: MockSite 'predictable product pages' promise breaks for link discovery: browser DOM differs from static HTML

**Severity:** Low
**Category:** Documentation

#### Reproduction

curl -s http://localhost:18080/ec/b?node=1292115012 → 25 <a href> anchors (7 category links with real hrefs, 10 product links, 3 home links), 13531 bytes
./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 1 --refresh → only 7 links discovered (6 product links)
./b4w.ps1 open --headless ... + eval 'JSON.stringify([...document.querySelectorAll("a[href]")].map(a=>a.getAttribute("href")))' → ["#","#","#","/ec/dp/B0E000001"..."/ec/dp/B0E000006"] (9 anchors; nav links are href='#')

#### Expected Behavior

crawl.md's 'Testing locally with MockSite' section presents MockSite as 'predictable product pages for testing crawl extraction'. A user debugging link discovery against it should be able to tell fixture behavior from crawler behavior.

#### Actual Behavior

MockSite serves a browser variant (JS-hydrated page: nav anchors kept as href='#', 6 product cards) that differs from the static HTML curl receives (25 real anchors). Crawls therefore discover only a subset of the links visible in the raw HTML, and combined with the '#'-href handling (see related issue) produce confusing depth-1 results. Nothing in crawl.md warns that the browser-rendered DOM differs from the raw fixture HTML.

#### Root Cause Analysis

MockSite fixture design: the page's JS variant rewrites navigation links to href='#' and renders only a subset of products; the crawl fetch pipeline captures the browser DOM (9944 bytes vs 13531 static). Not a crawler defect, but undocumented fixture behavior that undermines the docs' predictability claim.

#### Code Pointer

`skills/browser4-cli/references/crawl.md 'Testing locally with MockSite' section; fixture sources under browser4-tests/pulsar-tests-common/src/main/resources/static/b4/ (needs follow-up to confirm exact files)`

#### AI Suggested Improvement

- Add a crawl.md note: 'MockSite serves a JS variant to browsers — category/nav anchors appear as href="#" and the product list is a subset; verify expected links with eval or htmlsnapshot inspect before debugging discovery counts'
- Consider making the MockSite browser variant static (real hrefs) so link-discovery behavior is deterministic for test scenarios

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Corroborated by Issue 2's own repro evidence (browser DOM contains `href='#'` nav anchors the static HTML lacks), so the docs note is warranted. Add the crawl.md caveat now; treat MockSite fixture unification as optional and gated on checking which e2e/fixture tests depend on the JS-variant DOM before changing it.

---

### Issue 6: --page-load-timeout help contradicts accepted values and reference docs

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 crawl --help → '--page-load-timeout <seconds>  Maximum time to wait for page load'
crawl.md flags table: '--page-load-timeout | string | Max wait for each page load' (no format)
Task instructions and SKILL examples use '30s'; both '--page-load-timeout 30s' and '--page-load-timeout 30' run successfully (exit 0).

#### Expected Behavior

Help text should state the accepted value forms (seconds number, duration string, or both) consistently with crawl.md and actual behavior.

#### Actual Behavior

Help implies a plain seconds number ('<seconds>'), crawl.md implies an untyped string, and both '30' and '30s' are accepted. A user cannot tell which form is correct; related flag --expires documents the duration form ('1d, 1h, 30m') while --page-load-timeout does not.

#### Root Cause Analysis

OptionDef help string in commands.rs and the crawl.md flags table were written inconsistently (one says seconds, the other string) while the backend accepts both forms.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2994 (--page-load-timeout OptionDef); skills/browser4-cli/references/crawl.md LoadOptions flags table`

#### AI Suggested Improvement

- Change help to '--page-load-timeout <dur>  Max wait per page load (seconds number or duration such as 30s, 1m)'
- Align crawl.md flags table with the same wording

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified the three-way contradiction: help says `<seconds>`, `crawl.md:198` says untyped string, and the backend plus `commands.rs:6108` accept both `30` and `30s`. Land this in the same change as Issue 1 so help text, the crawl.md flags table, and the new validation error messages all advertise one canonical format.

---

## Overall Assessment

**Completion Status:** Successful — all 9 required steps executed; 3 product records extracted correctly and consistently across foreground, background, and readonly crawls.

**Success Rate:** 95%

**Issues Found:** 6

**Major Blockers:** None. The core crawl workflow (seed file → X-SQL @file → depth-0 fetch with LoadOptions flags → background submit → status/result) worked end-to-end on the first attempt.

**Most Confusing Aspects:** For a first-time user: (1) --ignore-url-query and --no-norm produce zero observable change on the depth-0 crawls the docs and task prescribe, so 'observe the difference' cannot be satisfied; (2) invalid option values (--expires 1x, --page-load-timeout banana) are silently accepted with exit 0, so typos are undetectable; (3) a background crawl with --format table returns raw JSON from crawl result/status, unlike the foreground table output, and status and result are byte-identical; (4) pattern-filtered link-discovery crawls can silently fetch nothing while reporting success without the documented warning.

**Most Valuable Improvements:** 1) CLI-side validation of --expires/--page-load-timeout/--priority with non-zero exits (silent defaulting is the most dangerous failure mode observed). 2) Skip fragment-only hrefs in out-link extraction and fire the no-out-links diagnostic whenever zero new pages are fetched. 3) Make crawl result honor the stored --format and give crawl status a compact human summary. 4) Clarify the depth-0 scope of --ignore-url-query/--no-norm in help text and crawl.md.

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

#### Issue 1: Invalid values for --expires/--page-load-timeout/--priority are silently accepted with exit 0

./b4w.ps1 crawl --seed-file .test-sessions/one-url.txt --depth 0 --expires 1x → exit 0, crawl 'completed'
./b4w.ps1 crawl --seed-file .test-sessions/one-url.txt --depth 0 --page-load-timeout banana → exit 0, crawl 'completed'
./b4w.ps1 crawl --seed-file .test-sessions/one-url.txt --depth 0 --priority -5 → exit 0, crawl 'completed'

#### Issue 2: Fragment-only hrefs ('#') treated as out-links: silent empty discovery, missing warning, and trailing-'#' URL artifact

./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -olp "node=" -d 1 --refresh
→ 'Crawl completed. 1 pages found.' with the seed listed as 'http://localhost:18080/ec/b?node=1292115012#' and zero out-link pages fetched; crawl status shows linksDiscovered: 1; no 'Link discovery found no out-links' warning. Same command without -olp discovers 7 links and fetches 6 pages (so the page does contain discoverable links).

#### Issue 3: crawl result ignores --format and is byte-identical to crawl status; docs promise 'same output as a foreground crawl'

./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-urls.txt --depth 0 --sql @.test-sessions/extract-title-price.sql --format table --background (task 66c3f13e-14dc-47f5-b523-4731f04cfaf4)
./b4w.ps1 crawl result 66c3f13e-14dc-47f5-b523-4731f04cfaf4 > r.json
./b4w.ps1 crawl status 66c3f13e-14dc-47f5-b523-4731f04cfaf4 > s.json
diff r.json s.json → identical

#### Issue 4: --ignore-url-query and --no-norm have no observable effect in depth-0 mode; help text overpromises

./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-dup-query.txt --depth 0 → 2 pages found, URLs reported verbatim with query strings
./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-dup-query.txt --depth 0 --ignore-url-query → identical output
./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-trailing-slash.txt --depth 0 → 2 pages (slash variant 404s)
./b4w.ps1 crawl --seed-file .test-sessions/ec-seed-trailing-slash.txt --depth 0 --no-norm → identical output

#### Issue 5: MockSite 'predictable product pages' promise breaks for link discovery: browser DOM differs from static HTML

curl -s http://localhost:18080/ec/b?node=1292115012 → 25 <a href> anchors (7 category links with real hrefs, 10 product links, 3 home links), 13531 bytes
./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 1 --refresh → only 7 links discovered (6 product links)
./b4w.ps1 open --headless ... + eval 'JSON.stringify([...document.querySelectorAll("a[href]")].map(a=>a.getAttribute("href")))' → ["#","#","#","/ec/dp/B0E000001"..."/ec/dp/B0E000006"] (9 anchors; nav links are href='#')

#### Issue 6: --page-load-timeout help contradicts accepted values and reference docs

./b4w.ps1 crawl --help → '--page-load-timeout <seconds>  Maximum time to wait for page load'
crawl.md flags table: '--page-load-timeout | string | Max wait for each page load' (no format)
Task instructions and SKILL examples use '30s'; both '--page-load-timeout 30s' and '--page-load-timeout 30' run successfully (exit 0).



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. CLI changes verified with `cargo test --bin browser4-cli`; backend changes verified by compiling `browser4-rest`/`browser4-agent-tools` and running `CrawlServiceTest` + `InspectDocumentTest`.

| Issue | Decision | Resolution |
|---|---|---|
| 1 — invalid `--expires`/`--page-load-timeout`/`--priority` silently accepted (Medium) | ACCEPT | Fixed: CLI-side validation (`validate_crawl_option_tokens` + `is_duration_value`) rejects malformed durations and non-integer/negative priorities with a non-zero exit naming the offending value, mirroring the `--format` reject pattern. Unit tests added. |
| 2 — fragment-only `#` hrefs treated as out-links (Medium) | ACCEPT with improvements | Fixed: `CrawlService.extractOutLinks` skips fragment-only anchors before resolution; `normalizeForVisit` strips fragments for dedup so a resolved `url#` dedupes against the seed; CLI warning gate now also fires when links were "discovered" but zero NEW pages were fetched (fragment/self-reference/duplicate case), with tips. |
| 3 — `crawl result` ignores `--format`, identical to status (Medium) | ACCEPT with improvements | Partially addressed: `crawl status`/`crawl result` now print a compact one-line human summary (state, pages, links, seed errors) ahead of the raw record, and `crawl result` explains when a task is still PROCESSING; rendering result sets through the stored `--format` remains a follow-up (needs task-side format persistence). Docs (`crawl.md`) updated to the actual contract. |
| 4 — `--ignore-url-query`/`--no-norm` depth-0 scope (Medium) | ACCEPT | Fixed (docs/help): OptionDef descriptions and crawl.md now state the flags only affect discovered out-link hrefs; depth-0 seed URLs are fetched verbatim by design. |
| 5 — MockSite browser-vs-curl DOM (Low) | ACCEPT | Fixed (docs): crawl.md now warns the JS-hydrated browser DOM can differ from static HTML (nav anchors `href="#"`, subset product list) and to verify with `eval`/`htmlsnapshot inspect`. |
| 6 — `--page-load-timeout` help contradiction (Low) | ACCEPT | Fixed: help and crawl.md advertise one canonical form — seconds number or duration (`30s`, `1m`) — matching the backend. |