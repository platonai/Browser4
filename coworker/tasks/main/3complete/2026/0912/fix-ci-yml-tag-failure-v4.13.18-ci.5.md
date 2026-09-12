Title: Fix ci.yml failure for tag v4.13.18-ci.5
Description: The ci.yml workflow run 34694569292 (tag v4.13.18-ci.5) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 34694569292) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.18-ci.5
- **Run ID:** 34694569292
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/34694569292



## Reproduce

`ash
# View all failed logs
gh run view 34694569292 --log-failed

# View the run in browser
gh run view 34694569292 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Check Test Status	﻿2026-09-12T13:02:23.2843830Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T13:02:23.2844524Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2845167Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2845779Z [36;1m  echo "📊 Test Results:"[0m

══ block 2 ══
ci-build	Check Test Status	﻿2026-09-12T13:02:23.2843830Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T13:02:23.2844524Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2845167Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2845779Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2846560Z [36;1m  echo "  - Total Tests: 1985"[0m

══ block 3 ══
ci-build	Check Test Status	﻿2026-09-12T13:02:23.2843830Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T13:02:23.2844524Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2845167Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2845779Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2846560Z [36;1m  echo "  - Total Tests: 1985"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2847105Z [36;1m  echo "  - Failed Tests: 1"[0m

══ block 4 ══
ci-build	Check Test Status	2026-09-12T13:02:23.2845779Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2846560Z [36;1m  echo "  - Total Tests: 1985"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2847105Z [36;1m  echo "  - Failed Tests: 1"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2847495Z [36;1m  echo "  - Passed Tests: 1935"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2847826Z [36;1m  echo "  - Skipped Tests: 49"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2848222Z [36;1m  FAILED_LIST="ai.platon.pulsar.browser.TestLoadResources"[0m

══ block 5 ══
ci-build	Check Test Status	2026-09-12T13:02:23.2847495Z [36;1m  echo "  - Passed Tests: 1935"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2847826Z [36;1m  echo "  - Skipped Tests: 49"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2848222Z [36;1m  FAILED_LIST="ai.platon.pulsar.browser.TestLoadResources"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2848644Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2848942Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2849182Z [36;1m    echo "❌ Failed Tests:"[0m

══ block 6 ══
ci-build	Check Test Status	2026-09-12T13:02:23.2847826Z [36;1m  echo "  - Skipped Tests: 49"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2848222Z [36;1m  FAILED_LIST="ai.platon.pulsar.browser.TestLoadResources"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2848644Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2848942Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2849182Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2849477Z [36;1m    for test in $FAILED_LIST; do[0m

══ block 7 ══
ci-build	Check Test Status	2026-09-12T13:02:23.2848644Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2848942Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2849182Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2849477Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2849765Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	2026-09-12T13:02:23.2850017Z [36;1m    done[0m

══ block 8

... (truncated — run gh run view 34694569292 --log-failed for full logs)

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

## Resolution (2026-09-12)

**Category:** load-sensitive flake in a long-standing product path (not a test-assertion
change, and not a regression from the tag's own commit).

**Failing test:** `TestLoadResources#testLoadResource`,
`assertTrue(resourceUrl) { page.protocolStatus.isSuccess }` (line 44) on the 2nd resource
URL (`/json`). ci.4 (success) → ci.5 (fail) differ only by the docs-only commit
`07b6019ace`, so no code changed; the same commit passes when the machine is quiet.

**Root cause:** the resource guard (`AppSystemInfo.isSystemOverCriticalLoad`, CPU
threshold 0.85 of the *whole host* — a shared 64-core runner) refused the *single*
driver-creation attempt for a fresh temporary privacy context, whose pool had no standby
driver. `LoadingWebDriverPool` then blocked in `statefulDriverPool.poll(60 s)` **without
re-evaluating the guard and without any log** on the over-critical-load branch — the run's
own log shows a silent 60.009 s gap (`12:59:31.405` → `13:00:31.414`), then
`Driver pool is exhausted | active: 0, standby: 0, waiting: 0, working: 0, slots: 50` →
`crawlRetry` (status 1601) → `protocolStatus.isSuccess == false`. A transient CPU spike
therefore turned into a hard failure a minute after the load had settled.

**Fix:** commit `2671e1574d` `fix(protocol): keep polling for a driver when the resource
guard refuses` — `LoadingWebDriverPool.pollWebDriver` now waits through
`pollDriverInSlices`: 500 ms slices (`POLLING_SLICE`), each slice re-running
`resourceSafeCreateDriverIfNecessary` so the guard is re-evaluated; the wait also ends
immediately once the pool is retired/closed instead of burning the full timeout. The
over-critical-load branch logs a throttled (1 min) message with a *stable* text, since
`ThrottlingLogger` keys on the formatted message. The test itself was **not** modified,
retried or skipped — the race (waiter vs. transient guard refusal) is what got fixed.

**Verification:** new pure-mock `LoadingWebDriverPoolTest` (2 cases, no browser):
`testPollCreatesDriverWhenTheResourceGuardAllowsItAgain` (guard refuses, allowed 1 s
later) returns the driver after **1.13 s**; the control run with the previous one-shot
behaviour fails **37.99 s** later with `WebDriverPoolExhaustedException` — the CI
signature. `testPollFailsFastWhenThePoolIsRetired` fails fast instead of waiting 30 s.
`./mvnw -ntp -o -pl browser4-core/browser4-protocol test` →
`Tests run: 82, Failures: 0, Errors: 0, Skipped: 2`, BUILD SUCCESS.
