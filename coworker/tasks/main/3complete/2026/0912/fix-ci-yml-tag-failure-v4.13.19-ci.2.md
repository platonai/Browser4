Title: Fix ci.yml failure for tag v4.13.19-ci.2
Description: The ci.yml workflow run 34712923110 (tag v4.13.19-ci.2) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 34712923110) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.19-ci.2
- **Run ID:** 34712923110
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/34712923110



## Reproduce

`ash
# View all failed logs
gh run view 34712923110 --log-failed

# View the run in browser
gh run view 34712923110 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Check Test Status	﻿2026-09-12T19:18:08.1664862Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T19:18:08.1665280Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1665628Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1665962Z [36;1m  echo "📊 Test Results:"[0m

══ block 2 ══
ci-build	Check Test Status	﻿2026-09-12T19:18:08.1664862Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T19:18:08.1665280Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1665628Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1665962Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1666257Z [36;1m  echo "  - Total Tests: 2049"[0m

══ block 3 ══
ci-build	Check Test Status	﻿2026-09-12T19:18:08.1664862Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T19:18:08.1665280Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1665628Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1665962Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1666257Z [36;1m  echo "  - Total Tests: 2049"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1666553Z [36;1m  echo "  - Failed Tests: 2"[0m

══ block 4 ══
ci-build	Check Test Status	2026-09-12T19:18:08.1665962Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1666257Z [36;1m  echo "  - Total Tests: 2049"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1666553Z [36;1m  echo "  - Failed Tests: 2"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1666850Z [36;1m  echo "  - Passed Tests: 1991"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1667153Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1667618Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.SwarmCrawlFixtureTest"[0m

══ block 5 ══
ci-build	Check Test Status	2026-09-12T19:18:08.1666850Z [36;1m  echo "  - Passed Tests: 1991"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1667153Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1667618Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.SwarmCrawlFixtureTest"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1668203Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1668487Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1669012Z [36;1m    echo "❌ Failed Tests:"[0m

══ block 6 ══
ci-build	Check Test Status	2026-09-12T19:18:08.1667153Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1667618Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.SwarmCrawlFixtureTest"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1668203Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1668487Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1669012Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1669301Z [36;1m    for test in $FAILED_LIST; do[0m

══ block 7 ══
ci-build	Check Test Status	2026-09-12T19:18:08.1668203Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1668487Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1669012Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1669301Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-09-12T19:18:08.1669591Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	2026-09-12T1

... (truncated — run gh run view 34712923110 --log-failed for full logs)

## Instructions

1. **Categorize the failure:** Is it a test assertion change, a real regression, or an
   infrastructure/flake issue? Check the release changelog above to see what code changed.
2. **If tests need updating** (assertion format changed, output text changed):
   - Update the test assertions to match the new expected output
   - Check cli/browser4-cli/tests/e2e/scenarios/ for Rust E2E tests
   - Look at the recent commits for patterns in how test assertions are structured
3. **If it is a real regression:**
   - Identify the root cause from the error diagnostics
   - Trace the code path using the repository structure
   - Apply the minimal fix
4. **If it is a flaky test** (same test fails sporadically across runs):
   - Do NOT delete or skip the test
   - Add retry logic or fix the race condition
   - Check CLAUDE.md "Known CDP pitfalls" for common causes
5. **Verify:** Run the relevant test suite locally or examine the CI output for
   the specific test that failed. Make sure your change would resolve it.
6. **Commit:** Use a conventional-commit message, e.g.:
   `fix(test): update test assertions for changed CLI output`

## Resolution

**Outcome: load-sensitive flake — fixed in the product code, not in the test. Commit `86fe8e4d38`.**

### Categorization

Flake, not a regression and not an assertion change. Between the last green `ci.yml`
run (ci.6, `f3c1a6202c`: `SwarmCrawlFixtureTest` `3 / 0`, 33.56 s) and this red run
(`e018b73107`) nothing changed in the guard, the driver pool, the retry runner or the
test — the diff is installer scripts, one `ci.yml` step, a version bump and docs. The
same conflict that is suspected of causing the failure is present in the *green* run
too (74 `Tab origin mismatch` refusals there, 78 here), and there are 0
`withTimeout` cancellations in either. No test was deleted, skipped or weakened.

### Diagnosis (from the failing run's own log)

1. Test 1 (`testSubmitGeneratedCrawlProductUrl`) submits at `19:11:37.372` and polls
   for 2 minutes (`waitForScrapeCompletion`, deadline `19:13:37`).
2. Concurrent fetches in the same run fight over the same fixture pages, so the
   snapshot-origin guard refuses this fetch repeatedly:
   `Tab origin mismatch: refusing to capture '…/product/1.html' for fetch '…'` at
   `19:11:20.9 / 19:11:33.1 / 19:12:09.4 / 19:12:48.5 / 19:13:34.5`, each followed by
   `Trying Nth 32-43s later`.
3. A refusal is handled as "retire the driver + CRAWL retry", and the delay came from
   `page.retryDelay ?: retryDelayPolicy(...)` — the default policy
   (`AbstractTaskRunner.retryDelayPolicy`) is a 30-45 s *remote-failure backoff*.
   Three retries therefore span 90-135 s, more than the caller's whole 2-minute wait:
   **the caller always gives up first**.
4. Test 1 failed on `assertTrue(finalStatus.isDone)` at `19:13:37` with the task still
   `202/1601` ("retrying"); the page printed `Gone … got 408 … retry budget exhausted
   (4)` only at `19:14:09.779` — 32 s *after* the deadline.
5. Test 2 then read the failed page (`417`) and failed `assertEquals(200, statusCode)`
   as well. The real defect is not "the fetch failed", it is "the retry cadence does
   not fit the caller's deadline".

### Fix

* `browser4-protocol/.../emulator/Exceptions.kt`: new `TabOriginMismatchException`
  (still a `WebDriverException`, so the existing catch semantics — retire + CRAWL
  retry — are unchanged; the refused driver is retired there and closed when it is
  returned to the pool, so the retry already lands on a fresh driver/tab), plus
  `TAB_ORIGIN_MISMATCH_RETRY_DELAY = 10 s` and `crawlRetryDelayFor(e)`, which returns
  the prompt delay for a guard refusal and null for every other driver failure.
* `InteractiveBrowserEmulator`: both guard refusal sites throw the new type; the
  `WebDriverException` catch sets `task.page.retryDelay` for a refusal, and logs it as
  `[Handled]` without a stack trace instead of `[Unexpected]` (the guard already logs
  the document detail; a refusal is expected, not a surprise).
* 10 s is not a new magic number: `MultiPrivacyContextManager`'s "No driver available"
  retry and `StreamingTaskRunner`'s cancel path (`page.retryDelay ?: 10 s`) are the
  module's existing prompt-retry precedents.
* Tests (`ExceptionsTest`, unit only): the subtype contract, the driver it carries, the
  prompt delay being below the 30 s backoff floor, 4 attempts × 10 s fitting inside a
  caller's 2-minute wait, and every other driver failure keeping the default policy.

### Verification

* `./mvnw -ntp -o -pl browser4-core/browser4-protocol -am test` (full module suite):
  **86 tests, 0 failures, 0 errors, 2 skipped**; `ExceptionsTest` **13 / 13**.
* The two boundary assertions in the new test (delay < 30 s, 4 × delay < 2 min) encode
  the failure mode itself, so a future regression to the backoff fails the suite.
* Next run's observable signal: `Trying Nth 10s later` instead of `32-43s later`.

### Scope note

Only the amplification is fixed. The root cause — concurrent fetches sharing one tab
(issue #592, tab/driver reuse, outside this repository's control) — remains, so a
*sustained* contention now fails fast inside the caller's window instead of after it.
Recorded as section 13 of `docs-dev/copilot/ci-stabilization-4.13.x.md`.
