Title: Fix ci.yml failure for tag v4.13.18-ci.1
Description: The ci.yml workflow run 34676747184 (tag v4.13.18-ci.1) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 34676747184) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.18-ci.1
- **Run ID:** 34676747184
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/34676747184



## Reproduce

`ash
# View all failed logs
gh run view 34676747184 --log-failed

# View the run in browser
gh run view 34676747184 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Check Test Status	﻿2026-09-12T06:20:23.3675399Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T06:20:23.3675802Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676149Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676496Z [36;1m  echo "📊 Test Results:"[0m

══ block 2 ══
ci-build	Check Test Status	﻿2026-09-12T06:20:23.3675399Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T06:20:23.3675802Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676149Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676496Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676785Z [36;1m  echo "  - Total Tests: 1902"[0m

══ block 3 ══
ci-build	Check Test Status	﻿2026-09-12T06:20:23.3675399Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-12T06:20:23.3675802Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676149Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676496Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676785Z [36;1m  echo "  - Total Tests: 1902"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3677098Z [36;1m  echo "  - Failed Tests: 3"[0m

══ block 4 ══
ci-build	Check Test Status	2026-09-12T06:20:23.3676496Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3676785Z [36;1m  echo "  - Total Tests: 1902"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3677098Z [36;1m  echo "  - Failed Tests: 3"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3677396Z [36;1m  echo "  - Passed Tests: 1885"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3677695Z [36;1m  echo "  - Skipped Tests: 14"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3678178Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CrawlFixtureMetadataTest"[0m

══ block 5 ══
ci-build	Check Test Status	2026-09-12T06:20:23.3677396Z [36;1m  echo "  - Passed Tests: 1885"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3677695Z [36;1m  echo "  - Skipped Tests: 14"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3678178Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CrawlFixtureMetadataTest"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3678667Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3678963Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3679215Z [36;1m    echo "❌ Failed Tests:"[0m

══ block 6 ══
ci-build	Check Test Status	2026-09-12T06:20:23.3677695Z [36;1m  echo "  - Skipped Tests: 14"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3678178Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CrawlFixtureMetadataTest"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3678667Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3678963Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3679215Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3679505Z [36;1m    for test in $FAILED_LIST; do[0m

══ block 7 ══
ci-build	Check Test Status	2026-09-12T06:20:23.3678667Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3678963Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3679215Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3679505Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-09-12T06:20:23.3679803Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	202

... (truncated — run gh run view 34676747184 --log-failed for full logs)

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

**Categorized as:** test-harness decoding bug (not a product regression, not a flake).

**Root cause:** the three crawl tests polled `GET /api/crawl/{id}/result` through
`expectBody<CrawlResponse>()`, whose client-side converter lacks the Jackson Kotlin
module. `CrawlResponse`'s all-default constructor gives Jackson a parameterless
instantiation path, so the class was instantiated but no field was ever bound: every
`val` property (`status`, `pages`, `readonlyNote`, ...) has no setter, so Jackson
silently skipped it. `status` stayed at its `"CREATED"` default, the poll loop never
saw a terminal status, and each test burned its full 6-minute deadline — while the
run's own server log shows every crawl completing with 10 pages
("Crawl task bb266d99-7dcf-4536-bfa7-9ceb70b408a1 completed: 10 pages").
`ScrapeResponse`, used by the passing swarm tests, binds fine because its properties
are `var`.

**Fix:** commit cae4042735 — fetch the raw body and deserialize it with
`jacksonObjectMapper()` (+ `JavaTimeModule` for the `Instant` fields). Byte-identical
to the fix already applied on 4.14.x (45b7d72c29; same blob 8139325a3e).

**Verification:** `mvn -ntp -P all-test-modules -pl browser4-tests/browser4-rest-tests
-Dsurefire.excludedGroups=ManualOnly -Dtest=CrawlFixtureMetadataTest test`
→ `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 198.0 s`
(previously 3 errors, each after a 361 s timeout).
