# Issues: bulk-scale-routing

> **Source:** `20260814-072407-bulk-scale-routing.full.md` | **Date:** 20260814-084524 | **Mode:** production

## Scenario Background

### Task

All six acceptance criteria were completed successfully, each reproducing the exact branch of SKILL.md §4b:

- **AC1 (single list page):** `htmlsnapshot query` with `DOM_LOAD_AND_SELECT(@url, '.product-card')` returned 6 rows (title + price + link) from `http://localhost:18080/ec/b?node=1292115012` — one row per product card.
- **AC2 (multiple known URLs):** `crawl --seed-file seed-urls.txt --depth 0 --sql @ac2-product-query.sql --format table --refresh` returned exactly 3 rows, one per seed URL, each with `DOM_BASE_URI` + `#productTitle` + `#product-price`.
- **AC3 (crawl from start URL):** `crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"` discovered 10 pages (hub + 9 product pages, including depth-2 pages reachable only via product→product links). Category/guide/utility links present in the fixture HTML were correctly excluded.
- **AC4 (parallel execution):** `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` → `swarm query --sql @ac2-product-query.sql --seed-file seed-urls.txt --refresh` → 3 tasks, polled via `swarm status`/`swarm list`, results fetched with `swarm result` (one row each), then `swarm close`.
- **AC5 (repeated monitoring):** `-s price-watch goto ...` then `loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "..."` executed 2 iterations, both returning `$899.99`; `loop --list` and `loop --status` worked mid-run, and the state was auto-cleaned on completion as documented.
- **AC6 (few URLs in a shell script):** A PowerShell `ForEach-Object` loop over 3 URLs calling `goto` → `htmlsnapshot` → `htmlsnapshot get text "#productTitle"` with `Start-Sleep 2` extracted all 3 titles without needing crawl/swarm/loop.

The backend was the released runtime bundle (browser4-cli 4.13.4 ↔ server 4.13.4-SNAPSHOT at localhost:18182, health UP), and all temporary files were created under `../../../../../../.test-sessions`.

### Execution Context

**Preparation:** Verified cwd was the repo root; created `../../../../../../.test-sessions`; ran `browser4-cli help`; downloaded and read `https://browser4.io/SKILL.md` in full (45.8 KB); read the local SKILL.md §4b and the crawl/swarm/loop/htmlsnapshot/x-sql reference docs; confirmed MockSite (HTTP 200 on :18080) and backend health (`browser4-cli status`).

**Commands and steps per AC:**

1. **AC1:** `browser4-cli goto "http://localhost:18080/ec/b?node=1292115012"` → `browser4-cli htmlsnapshot` → `browser4-cli htmlsnapshot inspect` (auto-discovered `.product-card` with `.product-title`/`.product-price`) → wrote `../../../../../../.test-sessions/ac1-listing-query.sql` → `browser4-cli htmlsnapshot query "<url>" --sql "@.test-sessions/ac1-listing-query.sql"` → 6 rows.
2. **AC2:** Wrote `../../../../../../.test-sessions/seed-urls.txt` (B0E00000...

(truncated — see full.md for complete trace)

---

## Issues Found (5 issues)

### Issue 1: Crawl reports every page at depth=1 regardless of actual discovery depth

**Severity:** Medium
**Category:** Reliability

#### Reproduction

browser4-cli crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"
then: browser4-cli crawl result <task-id> --json
Result: every page in the pages[] array has "depth":1, including the seed URL (http://localhost:18080/generated/crawl/index.html) and pages that are only reachable at depth 2 (product/4.html ... product/9.html, found via product->product links).

#### Expected Behavior

The seed URL should be reported at depth=0, pages linked from the hub at depth=1, and pages found via product->product links at depth=2 (matching crawl.md's documented 'depth=0 | URL | Page 1 Title' output).

#### Actual Behavior

All 10 pages are reported as "depth":1. The crawl itself recurses correctly (10 unique pages, no category pages, terminates), so only the reported depth is wrong, but users cannot verify link depth or filter results by depth.

#### Root Cause Analysis

In CrawlService.crawlDepthN the recorded depth is `extractDepth(page) ?: 1`. extractDepth parses `page.configuredUrl` for a '-depth N' marker, which is apparently not preserved in configuredUrl for pages loaded through the parse handler, so the silent `?: 1` fallback applies to every page (seed and depth-2 pages alike). The internal recursion still knows the real depth (it stops at maxDepth), so only the reporting is affected. Needs backend verification of what configuredUrl contains for each page.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:742 (crawlDepthN) and :1098 (extractDepth)`

#### AI Suggested Improvement

- Track depth explicitly in the parse handler (e.g. a ConcurrentHashMap<normalizedUrl, depth> populated from buildArgsForDepth) and record it in CrawlPageResult instead of scraping the configured URL
- Remove the silent `?: 1` fallback or apply it only to the seed so a missing depth fails loudly
- Add a backend integration test asserting seed=0, direct links=1, second-level links=2 for a depth-2 crawl

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified at CrawlService.kt:795 — `depths[key] ?: extractDepth(page) ?: 1` silently defaults every page to depth 1 when lookup misses, contradicting crawl.md's documented depth semantics; this is the only Medium-severity, correctness-adjacent finding and should be fixed first. A `depths` map already exists, so the fix is to guarantee population at discovery time for every URL, remove the silent fallback for non-seed pages, and add the seed=0/direct=1/second-level=2 integration assertion.

---

### Issue 2: Swarm jobs can sit in 'queued' for 20-25s while sibling jobs complete, looking stuck with no worker visibility

**Severity:** Low
**Category:** Reliability

#### Reproduction

browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
browser4-cli swarm query --sql "@q.sql" --seed-file seeds.txt --refresh
browser4-cli swarm status <id> (poll)
Run 1: B0E000001 stayed statusCode=201 'queued' for ~25s while B0E000002/B0E000003 completed in ~1s each. Run 2 (same commands): B0E000001 completed instantly while the other two took ~18s each.

#### Expected Behavior

With 2 contexts and 3 jobs, jobs should be picked up promptly in submission order and roughly evenly; `swarm status` should not look like the job is stuck while others succeed.

#### Actual Behavior

Job pickup order and latency vary widely between runs (different task is 'slow' each time). One worker context appears warm (instant) while the other takes ~18s, and the queued job shows only statusCode 201 with no indication of worker warm-up.

#### Root Cause Analysis

The swarm worker pool initializes browser contexts lazily and job->context assignment does not appear to be strict FIFO; the client has no visibility into per-worker state, so a job waiting for a cold context is indistinguishable from a stuck one. The CLI's own warning text ('If jobs appear stuck...') amplifies the impression. Backend scheduling in SwarmService needs investigation to confirm exact worker pick-up order.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt (task dispatch/worker pool); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/config/StartupWarmer.kt (swarm pool warming)`

#### AI Suggested Improvement

- Warm the swarm browser pool during `swarm create` so no job hits a cold context
- Include per-worker state (initializing/ready/busy) in `swarm status`/`swarm list` output
- Assign jobs FIFO so the first submitted job starts first
- Adjust the CLI 'stuck' guidance to mention expected worker warm-up latency

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The symptom is real but the FIFO/scheduling half of the root cause is unverified, so accept the cheap high-value parts — per-worker state (initializing/ready/busy) in `swarm status`, pool warming at `swarm create`, and CLI text explaining warm-up latency — and investigate the dispatcher before changing job-assignment order. This shares the "looks stuck" theme with Issue 3 but is a separate command with a separate fix, not a duplicate.

---

### Issue 3: Crawl progress output is repetitive and slow-looking for small local jobs (26s for 3 pages; ~56s 'waiting for first page' for 10 pages)

**Severity:** Low
**Category:** UX

#### Reproduction

browser4-cli crawl --seed-file .test-sessions/seed-urls.txt --depth 0 --sql "@q.sql" --format table --refresh   # 3 localhost pages, 26s
browser4-cli crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"   # prints 'Crawling... waiting for first page (6s/16s/26s/36s/46s/56s elapsed, 1 URLs queued)' for ~56s

#### Expected Behavior

Local pages should complete in a few seconds, and progress should show meaningful per-URL detail rather than near-identical lines that make the crawl look stalled.

#### Actual Behavior

Each page takes ~5-7s (backend parse/load pipeline) and the CLI prints essentially the same 'waiting for first page' / 'N pages found so far' line every poll (~2s interval), which reads as a hang. The depth-0 mode did show '1/3 seeds done ... 1 rows extracted' per seed, which was much clearer.

#### Root Cause Analysis

crawlDepthN processes pages sequentially through Pulsar's parse pipeline (engine-level latency dominates); the CLI polls every 2s but backend state only changes on page completion, so output lines repeat. Not a correctness failure, but a real UX/confidence problem for first-time users.

#### Code Pointer

`cli/browser4-cli/src/main.rs:10846 (crawl polling loop) and :10970 (progress messages); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt (per-page processing)`

#### AI Suggested Improvement

- Emit a per-URL completion line as soon as each page finishes (like depth-0 mode does), including in link-discovery mode
- Replace repeated identical progress lines in place (e.g. carriage-return update) instead of appending duplicates
- Document expected per-page latency in crawl.md so users don't interpret slow local crawls as failures

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Repeated near-identical poll lines genuinely read as a hang; backfill the depth-0 per-seed completion style into link-discovery mode and replace appended duplicates with in-place (CR) updates — CLI-only, low risk. The ~5-7s/page engine latency deserves a crawl.md note documenting expected timing rather than backend restructuring.

---

### Issue 4: 'No active session — creating a new one.' is written to stderr and can appear detached from the 'Session opened:' confirmation in merged/scripted output

**Severity:** Low
**Category:** UX

#### Reproduction

browser4-cli -s demo goto http://localhost:18080/ec/dp/B0E000001; browser4-cli -s demo goto http://localhost:18080/ec/dp/B0E000002
Capture combined stdout+stderr: the line 'No active session — creating a new one.' appears after the second command's output, far from the 'Session opened: demo (...)' line it belongs to.

#### Expected Behavior

The new-session notice should appear adjacent to 'Session opened:' (same stream, same command), or be omitted in favor of the 'Session opened:' message.

#### Actual Behavior

The informational notice goes to stderr via eprintln! while the success message goes to stdout; in merged/captured output (and command chains), the notice surfaces at the very end, making it look like a stray error from the last command. This confused the evaluator initially before source inspection showed it was intentional.

#### Root Cause Analysis

cli/browser4-cli/src/main.rs:1252 uses eprintln! for an informational message while the paired 'Session opened:' message uses println! (cli_println!), so stream separation reorders them in merged output. stderr is conventionally reserved for warnings/errors, which makes the notice look alarming.

#### Code Pointer

`cli/browser4-cli/src/main.rs:1248-1253 (get_or_create_navigation_session)`

#### AI Suggested Improvement

- Emit 'No active session — creating a new one.' to stdout immediately before 'Session opened:' (or drop it and let 'Session opened:' carry the message)
- Keep stderr strictly for warnings/errors so informational session lifecycle messages are consistently on stdout

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified at main.rs:1314 — an informational notice is emitted via `eprintln!` while its paired 'Session opened:' goes to stdout, so merged/scripted output detaches them and the notice reads as a stray error. Move it to stdout immediately before 'Session opened:' (or drop it) and reserve stderr for actual warnings/errors.

---

### Issue 5: Loop output contains non-ASCII glyphs (—, ✓) that are corrupted when redirected to a file on Windows PowerShell

**Severity:** Low
**Category:** UX

#### Reproduction

Create a script that runs: browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> loop-run.log
Then inspect loop-run.log: lines contain '鈥?every 10s...' and '鉁? Loop finished' instead of '— every 10s' and '✓ Loop finished'.

#### Expected Behavior

Redirected loop logs should preserve UTF-8 characters (— and ✓).

#### Actual Behavior

The em-dash and checkmark are mangled into mojibake in the redirected file. Console display is fine; only scripted/logging workflows are affected.

#### Root Cause Analysis

The CLI emits UTF-8, but Windows PowerShell 5.1's `*>` redirection decodes the byte stream using the system ANSI codepage (GBK in this environment), corrupting non-ASCII characters. Cosmetic, but it degrades log-based automation on a first-class platform.

#### Code Pointer

`cli/browser4-cli/src/main.rs (loop command output formatting with —/✓ glyphs)`

#### AI Suggested Improvement

- Use ASCII-safe framing characters (e.g. '-' and 'OK') in loop iteration/history output, or
- Document in loop.md/shell-quoting.md that Windows users should set `[Console]::OutputEncoding = [Text.Encoding]::UTF8` before redirecting loop output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The CLI emits correct UTF-8 — the mojibake is PowerShell 5.1 `*>` redirection decoding with the ANSI codepage, and ✓/— glyphs appear across dozens of status/success lines, so ASCII-ifying them globally is disproportionate churn. Apply the documentation fix (loop.md/shell-quoting.md: set `[Console]::OutputEncoding = [Text.Encoding]::UTF8` or use pwsh for redirected logs) and leave console output as-is.

---

## Overall Assessment

**Completion Status:** Successful — all six acceptance criteria (AC1-AC6) were executed and verified against the released browser4-cli 4.13.4 / server 4.13.4, and the usability evaluation was completed.

**Success Rate:** 100% — every task step succeeded on the first attempt; no workaround was required for correctness (only the documented PowerShell quoting practice and a hidden background process to observe loop status mid-run).

**Issues Found:** 5

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Install browser4-cli: `cargo install --path cli/browser4-cli`
3. Ensure the backend server is running.
4. All commands: `browser4-cli <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Crawl reports every page at depth=1 regardless of actual discovery depth

browser4-cli crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"
then: browser4-cli crawl result <task-id> --json
Result: every page in the pages[] array has "depth":1, including the seed URL (http://localhost:18080/generated/crawl/index.html) and pages that are only reachable at depth 2 (product/4.html ... product/9.html, found via product->product links).

#### Issue 2: Swarm jobs can sit in 'queued' for 20-25s while sibling jobs complete, looking stuck with no worker visibility

browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
browser4-cli swarm query --sql "@q.sql" --seed-file seeds.txt --refresh
browser4-cli swarm status <id> (poll)
Run 1: B0E000001 stayed statusCode=201 'queued' for ~25s while B0E000002/B0E000003 completed in ~1s each. Run 2 (same commands): B0E000001 completed instantly while the other two took ~18s each.

#### Issue 3: Crawl progress output is repetitive and slow-looking for small local jobs (26s for 3 pages; ~56s 'waiting for first page' for 10 pages)

browser4-cli crawl --seed-file .test-sessions/seed-urls.txt --depth 0 --sql "@q.sql" --format table --refresh   # 3 localhost pages, 26s
browser4-cli crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"   # prints 'Crawling... waiting for first page (6s/16s/26s/36s/46s/56s elapsed, 1 URLs queued)' for ~56s

#### Issue 4: 'No active session — creating a new one.' is written to stderr and can appear detached from the 'Session opened:' confirmation in merged/scripted output

browser4-cli -s demo goto http://localhost:18080/ec/dp/B0E000001; browser4-cli -s demo goto http://localhost:18080/ec/dp/B0E000002
Capture combined stdout+stderr: the line 'No active session — creating a new one.' appears after the second command's output, far from the 'Session opened: demo (...)' line it belongs to.

#### Issue 5: Loop output contains non-ASCII glyphs (—, ✓) that are corrupted when redirected to a file on Windows PowerShell

Create a script that runs: browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "document.querySelector('#product-price').textContent.trim()" *> loop-run.log
Then inspect loop-run.log: lines contain '鈥?every 10s...' and '鉁? Loop finished' instead of '— every 10s' and '✓ Loop finished'.

