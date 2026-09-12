Title: Fix ci.yml failure for tag v4.13.18-ci.2
Description: The ci.yml workflow run 34678560474 (tag v4.13.18-ci.2) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 34678560474) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.18-ci.2
- **Run ID:** 34678560474
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/34678560474



## Reproduce

`ash
# View all failed logs
gh run view 34678560474 --log-failed

# View the run in browser
gh run view 34678560474 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Check Test Status	﻿2026-09-12T06:54:03.8697902Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T06:54:03.8698297Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8698636Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699007Z [36;1m  echo "📊 Test Results:"[0m

══ block 2 ══
ci-build	Check Test Status	﻿2026-09-12T06:54:03.8697902Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T06:54:03.8698297Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8698636Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699007Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699295Z [36;1m  echo "  - Total Tests: 1981"[0m

══ block 3 ══
ci-build	Check Test Status	﻿2026-09-12T06:54:03.8697902Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T06:54:03.8698297Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8698636Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699007Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699295Z [36;1m  echo "  - Total Tests: 1981"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699589Z [36;1m  echo "  - Failed Tests: 1"[0m

══ block 4 ══
ci-build	Check Test Status	2026-09-12T06:54:03.8699007Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699295Z [36;1m  echo "  - Total Tests: 1981"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699589Z [36;1m  echo "  - Failed Tests: 1"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8699867Z [36;1m  echo "  - Passed Tests: 1931"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8700155Z [36;1m  echo "  - Skipped Tests: 49"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8700539Z [36;1m  FAILED_LIST="ai.platon.pulsar.basic.session.PulsarSessionTests"[0m

══ block 5 ══
ci-build	Check Test Status	2026-09-12T06:54:03.8699867Z [36;1m  echo "  - Passed Tests: 1931"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8700155Z [36;1m  echo "  - Skipped Tests: 49"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8700539Z [36;1m  FAILED_LIST="ai.platon.pulsar.basic.session.PulsarSessionTests"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8700942Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8701245Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8701485Z [36;1m    echo "❌ Failed Tests:"[0m

══ block 6 ══
ci-build	Check Test Status	2026-09-12T06:54:03.8700155Z [36;1m  echo "  - Skipped Tests: 49"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8700539Z [36;1m  FAILED_LIST="ai.platon.pulsar.basic.session.PulsarSessionTests"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8700942Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8701245Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8701485Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8701776Z [36;1m    for test in $FAILED_LIST; do[0m

══ block 7 ══
ci-build	Check Test Status	2026-09-12T06:54:03.8700942Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8701245Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8701485Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8701776Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8702049Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	2026-09-12T06:54:03.8702311Z [36;1m   

... (truncated — run gh run view 34678560474 --log-failed for full logs)

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

---

## Resolution (2026-09-12)

**Categorized as:** real (deterministic) product-code regression in the snapshot origin
guard — not a test-assertion change, not a flake.

**Failing test:** `PulsarSessionTests#testLoadLocalFile`,
`assertEquals("Hello", document.selectFirstTextOrNull("h1"))` → `expected: <Hello> but
was: <null>`. The run's own log shows the cause: all four fetch attempts were refused by
the guard in `InteractiveBrowserEmulator.captureNavigationSnapshot` —

```
Tab origin mismatch: refusing to capture 'file:///tmp/pulsar/test.html' for fetch
'http://localfile.internal?path=…' — the snapshot still shows an earlier fetch's document
and no main-document request was issued for this navigation
```

**Root cause:** a local-file fetch (`http://localfile.internal?path=<base64>`) is served
by `PulsarWebDriver` itself: it decodes the path and navigates the tab to that path's
`file://` URL. The guard recognises a legitimately committed document in exactly two
ways — the committed URL is the fetch URL, or an HTTP(S) redirect of it is confirmed by
the main-document request on this fetch's `NavigateEntry`. A `file://` translation
matches neither: it is never URL-equal to `http://localfile.internal?path=…`, and a
`file://` navigation emits no main-document `Network.requestWillBeSent`, so
`NavigateEntry.mainRequestId` stays null forever. The browser had loaded exactly the
requested document; the guard simply could not see it. Latent since the guard landed
(`0906407f39`, 2026-09-04) — it surfaced only in the first run whose reactor reached
`pulsar-it-tests`, the previous run having aborted at `CrawlFixtureMetadataTest`.

**Fix:** commit `4d0af69110` `fix(protocol): accept local-file fetches in the snapshot
origin guard` — new `UrlDocumentMatcher.referToSameLocalFile` decodes the fetch URL's
`path` parameter and accepts the snapshot only when the committed document is the
`file://` URI of exactly that file (normalized filesystem-path comparison), delegating
from `InteractiveBrowserEmulator`. The guard's purpose is unchanged: a `file://` document
from an earlier fetch, or any non-local document, is still rejected.

**Verification:** real browser, locally, with a **fresh page key** — the persistent
`FileBackendPageStore` serves the previous content when a `-refresh` refetch fails, which
is why a first control run passed despite the same guard rejection:

- without the fix: `Tests run: 1, Failures: 1` — `expected: <Hello> but was: <null>` at
  `PulsarSessionTests.kt:93`, identical to CI; fetch logged `got 1601 0 <- 0`
- with the fix: `Tests run: 1, Failures: 0` (21.2 s); fetch logged `got 200 2.0 KiB`
- `mvn -pl browser4-core/browser4-protocol test` → `Tests run: 80, Failures: 0`
  (incl. 5 new `UrlDocumentMatcherTest` cases)
