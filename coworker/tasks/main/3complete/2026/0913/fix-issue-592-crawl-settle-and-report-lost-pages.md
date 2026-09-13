Title: Fix Browser4 issue #592 — a crawl silently drops pages and keeps working after it reports completed
Description: Issue #592 reports a depth-2 crawl returning fewer pages than it discovered while reporting status=OK, a snapshot-origin refusal that loses the page instead of retrying onto a fresh tab, and a task that keeps submitting links minutes after it was reported completed. Fix the visible/lossy half in browser4-rest (settle bookkeeping, failed-page reporting, terminal state), verify with unit + fixture integration tests, and commit.
Prompt: Fix https://github.com/platonai/Browser4/issues/592 (the observability/lifecycle half; the shared-tab root cause is out of scope for this repository).

## Context

- **Issue:** https://github.com/platonai/Browser4/issues/592
- **Branch:** `4.13.x`, based on `864bfb057b`
- **Evidence:** local run of `CrawlFixtureMetadataTest` on a 20-core Windows host returned 8 of 10
  pages with `status=OK`; the same commit passed on a 4-core Linux CI runner.

## Problem

Five gaps in `CrawlService`, all of the same family — *a crawl can lose work and still look successful*:

1. The round completed on `submittedCount == completedCount`, a self-reported count.
2. Counting only at the end of a parse handler let handler A's children be submitted *after* the
   counters of handlers B..E had caught up, so the round completed before A's children were queued.
   That is the "expected 10 pages, got 8" symptom.
3. `ParsableHyperlink` only registers `onHTMLDocumentParsed`, so a fetch that fails (guard refusal
   with an exhausted retry budget, dropped task, 408/417) never reached the crawl at all.
4. `crawlDepthN` returns as soon as its own latch fires while the shared session's parse pipeline
   still holds work; late duplicate parse events kept submitting links after the task was terminal.
5. A depth-1/N round timeout was swallowed by the per-seed `catch (e: Exception)`
   (`TimeoutCancellationException` is an `Exception`), so the terminal write replaced the TIMEOUT
   record with `OK`.

## Fix

- New `CrawlLedger` (`browser4-rest/.../service/CrawlLedger.kt`): a pure, unit-testable settle
  state machine. `enter()/leave()` make "the round is complete" and "a handler is running" mutually
  exclusive; `recordFailure()` gives a failed URL a terminal outcome (idempotent — `onLoaded` fires
  per retry); `submit()` dedupes by URL; `close()` refuses all late work; `outstanding()` lists the
  URLs a closed round never got back. Counting is deliberately URL-agnostic: a page's final URL can
  differ from the one it was submitted under.
- Both crawl loops (`crawlDepthN`, `crawlDepth1`) use the ledger and attach
  `crawlEventHandlers.onLoaded` to every submitted URL, classifying retry/canceled/failed/success
  exactly as `XSQLHyperlink.CrawlEventHandlers` does (`!isFetched` alone is *not* a failure — a
  page-store hit legitimately completes with `isFetched == false`). The `currentDepth == null` path,
  which used to drop a page *and* tick the completion counter, now reports the loss.
- Data model: `CrawlResponse.failedPages` / `pagesExpected`, plus `CrawlFailedPage` and `CrawlRound`.
  Invariant: `pagesFound + failedPages.size == pagesExpected`. The set of `status` values is
  deliberately unchanged — the CLI poller treats only OK/SC_OK/TIMEOUT/ERROR as terminal — so loss is
  expressed through `failedPages` and `diagnostic`.
- Terminal state: a timed-out round is no longer overwritten with `OK`; the loss note is *appended*
  to the existing diagnostic; the seed loop rethrows `CancellationException`.
- CLI: a `⚠ N of M submitted page(s) were never delivered` warning naming each lost URL (depth,
  status, reason), plus `failed_pages` / `pages_expected` JSON fields.
- Tests: `CrawlLedgerTest` (13) — including the in-flight-handler regression and the
  failure-must-settle rule; `CrawlResponseTest` (+4, including legacy JSONL restore);
  `CrawlFixtureMetadataTest` — `assertNoLostPages()` conservation assertion that names the lost URLs,
  and `testBackToBackCrawlsLoseNoPages()` which submits two crawls while the first still runs;
  a new CLI e2e scenario `test_e2e_crawl_foreground_reports_lost_pages`.
- Docs: `docs-dev/copilot/ci-stabilization-4.13.x.md` §14, `skills/browser4-cli/references/crawl.md`.

## Verification

| Layer | Command | Result |
|---|---|---|
| Unit (targeted) | `-pl browser4-rest -am test -Dtest=CrawlLedgerTest,CrawlResponseTest` | 30 / 0 / 0 |
| Unit (module) | `-pl browser4-rest -am test` | 348 / 0 / 0, BUILD SUCCESS |
| Rust | `cargo test --bin browser4-cli` | 1195 passed / 0 failed |
| Rust e2e | `cargo test --test e2e -- --group=crawl` | 14 passed / 1 failed — the failure (`test_e2e_crawl_foreground_with_sql`) reproduces on the pre-change baseline (`git stash` check) and is unrelated |
| Integration | `-Pall-test-modules -pl browser4-tests/browser4-rest-tests -am -DrunITs=true -Dtest=CrawlFixtureMetadataTest` | **5 / 0 / 0**, 294.9 s |

The integration run on the same 20-core host that produced the 8/10 result now logs
`Crawl task ... completed: 10 pages, 0 lost, status OK` — guard refusals still occur
(`Retry(1601) rs: TabOriginMismatchException`), but they no longer turn into "two pages short, OK".

## Scope note

The root cause named in §13.4 of the CI-stabilization notes — concurrent fetches sharing one tab —
was left open in the first commit and fixed in the second one, see below.

## Second commit: the root cause (a session-bound driver had no lease)

**Mechanism (confirmed, not inferred).** `PrivacyManagedBrowserFetcher.fetchDeferred` first asks for a
"specified" web driver (`page.getBeanOrNull(WebDriver)` → `page.conf.getBeanOrNull(WebDriver)`, which
inherits from the session config, i.e. `session.bindDriver(driver)`). When one is found the fetch uses
it directly and **bypasses the driver pool's poll/put lease entirely** — only the "no specified driver"
branch goes through `privacyManager.run { … }`. A crawl's session has one bound driver, so every fetch
of that crawl drove the same tab concurrently.

**Evidence.** A temporary probe in `ConcurrentStatefulDriverPool.poll/offer` produced *zero* output in a
whole integration run → the crawl never touched the pool. The guard lines in the same run named one
driver id (`driver #2`) 234+ times, from several worker threads within 2 ms of each other.

**Fix.** New `DriverLeaseRegistry` (per-driver `Mutex`); `fetchDeferred` takes the lease before driving
a specified driver and releases it afterwards, and when the lease cannot be taken within 60 s it leases
an independent driver from the pool instead of sharing the tab (logged).

**Verification** (same 20-core Windows host, same fixture test):

| Metric | Before | After |
|---|---|---|
| `Tab origin mismatch` | 282 | **0** |
| `will be retired` | 141 | **0** |
| `CrawlFixtureMetadataTest` | 5/0/0, 294.9 s / 327.9 s | **5/0/0, 234.9 s** |
| `browser4-protocol` suite | — | 91/0/0 (2 skipped) |
| New `DriverLeaseRegistryTest` | — | 5/0/0 |

**Remaining throughput note.** Refusals dropping to zero means fetches on one session tab are now
serialized. A crawl that wants parallelism should stop binding a session driver and lease tabs from
the pool instead — `crawlDepthN` already asks for `maxOpenTabs(8)`, but the pool reads that at
construction, so the setting often arrives too late. That is a throughput follow-up, not a correctness
one.
