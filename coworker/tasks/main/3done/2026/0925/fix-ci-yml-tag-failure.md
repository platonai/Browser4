Title: Fix ci.yml failure for tag v4.13.22-ci.2
Description: The ci.yml workflow run 36172619505 (tag v4.13.22-ci.2) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 36172619505) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.22-ci.2
- **Run ID:** 36172619505
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/36172619505



## Reproduce

`ash
# View all failed logs
gh run view 36172619505 --log-failed

# View the run in browser
gh run view 36172619505 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Check Test Status	﻿2026-09-25T19:23:16.6288771Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-25T19:23:16.6289191Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6289553Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6289904Z [36;1m  echo "📊 Test Results:"[0m

══ block 2 ══
ci-build	Check Test Status	﻿2026-09-25T19:23:16.6288771Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-25T19:23:16.6289191Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6289553Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6289904Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6290209Z [36;1m  echo "  - Total Tests: 2172"[0m

══ block 3 ══
ci-build	Check Test Status	﻿2026-09-25T19:23:16.6288771Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-09-25T19:23:16.6289191Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6289553Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6289904Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6290209Z [36;1m  echo "  - Total Tests: 2172"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6290529Z [36;1m  echo "  - Failed Tests: 2"[0m

══ block 4 ══
ci-build	Check Test Status	2026-09-25T19:23:16.6289904Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6290209Z [36;1m  echo "  - Total Tests: 2172"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6290529Z [36;1m  echo "  - Failed Tests: 2"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6290848Z [36;1m  echo "  - Passed Tests: 2156"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6291184Z [36;1m  echo "  - Skipped Tests: 14"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6291905Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CommandXSqlTest ai.platon.pulsar.rest.api.controller.CrawlDeliveryRetryTest"[0m

══ block 5 ══
ci-build	Check Test Status	2026-09-25T19:23:16.6290848Z [36;1m  echo "  - Passed Tests: 2156"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6291184Z [36;1m  echo "  - Skipped Tests: 14"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6291905Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CommandXSqlTest ai.platon.pulsar.rest.api.controller.CrawlDeliveryRetryTest"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6292614Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6292907Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6293147Z [36;1m    echo "❌ Failed Tests:"[0m

══ block 6 ══
ci-build	Check Test Status	2026-09-25T19:23:16.6291184Z [36;1m  echo "  - Skipped Tests: 14"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6291905Z [36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.controller.CommandXSqlTest ai.platon.pulsar.rest.api.controller.CrawlDeliveryRetryTest"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6292614Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6292907Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6293147Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6293451Z [36;1m    for test in $FAILED_LIST; do[0m

══ block 7 ══
ci-build	Check Test Status	2026-09-25T19:23:16.6292614Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6292907Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6293147Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-09-25T19:23:16.6293451Z [36;1m    

... (truncated — run gh run view 36172619505 --log-failed for full logs)

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
