Title: Fix ci.yml failure for tag v4.13.22-ci.1
Description: The ci.yml workflow run 36164169973 (tag v4.13.22-ci.1) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 36164169973) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.22-ci.1
- **Run ID:** 36164169973
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/36164169973



## Reproduce

`ash
# View all failed logs
gh run view 36164169973 --log-failed

# View the run in browser
gh run view 36164169973 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Check Test Status	﻿2026-09-25T17:43:07.5802305Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-25T17:43:07.5802713Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803064Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803427Z [36;1m  echo "📊 Test Results:"[0m

══ block 2 ══
ci-build	Check Test Status	﻿2026-09-25T17:43:07.5802305Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-25T17:43:07.5802713Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803064Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803427Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803735Z [36;1m  echo "  - Total Tests: 2316"[0m

══ block 3 ══
ci-build	Check Test Status	﻿2026-09-25T17:43:07.5802305Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-25T17:43:07.5802713Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803064Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803427Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803735Z [36;1m  echo "  - Total Tests: 2316"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5804050Z [36;1m  echo "  - Failed Tests: 8"[0m

══ block 4 ══
ci-build	Check Test Status	2026-09-25T17:43:07.5803427Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5803735Z [36;1m  echo "  - Total Tests: 2316"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5804050Z [36;1m  echo "  - Failed Tests: 8"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5804355Z [36;1m  echo "  - Passed Tests: 2252"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5804654Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5805966Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CommandXSqlTest ai.platon.pulsar.rest.api.controller.CrawlDeliveryRetryTest ai.platon.pulsar.rest.api.controller.CrawlLinkDiscoveryTest ai.platon.pulsar.rest.api.controller.CrawlParallelTabsTest ai.platon.pulsar.rest.api.controller.CrawlXSqlE2ETest"[0m

══ block 5 ══
ci-build	Check Test Status	2026-09-25T17:43:07.5804355Z [36;1m  echo "  - Passed Tests: 2252"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5804654Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5805966Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CommandXSqlTest ai.platon.pulsar.rest.api.controller.CrawlDeliveryRetryTest ai.platon.pulsar.rest.api.controller.CrawlLinkDiscoveryTest ai.platon.pulsar.rest.api.controller.CrawlParallelTabsTest ai.platon.pulsar.rest.api.controller.CrawlXSqlE2ETest"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5807270Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5807563Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5807845Z [36;1m    echo "❌ Failed Tests:"[0m

══ block 6 ══
ci-build	Check Test Status	2026-09-25T17:43:07.5804654Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5805966Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CommandXSqlTest ai.platon.pulsar.rest.api.controller.CrawlDeliveryRetryTest ai.platon.pulsar.rest.api.controller.CrawlLinkDiscoveryTest ai.platon.pulsar.rest.api.controller.CrawlParallelTabsTest ai.platon.pulsar.rest.api.controller.CrawlXSqlE2ETest"[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5807270Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-25T17:43:07.5807563Z [36;1m    echo ""[0m
ci-build	Check Test Status	202

... (truncated — run gh run view 36164169973 --log-failed for full logs)

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
