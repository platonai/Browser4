Title: Fix ci.yml failure for tag v4.13.0-ci.1
Description: The ci.yml workflow run 31156415848 (tag v4.13.0-ci.1) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 31156415848) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.0-ci.1
- **Run ID:** 31156415848
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/31156415848



## Reproduce

`ash
# View all failed logs
gh run view 31156415848 --log-failed

# View the run in browser
gh run view 31156415848 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Check Test Status	﻿2026-08-07T07:13:52.9461075Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-07T07:13:52.9461691Z ^[[36;1mif [ "failed" != "success" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9462242Z ^[[36;1m  echo "❌ Tests failed with status: failed"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9462750Z ^[[36;1m  echo "📊 Test Results:"^[[0m

══ block 2 ══
ci-build	Check Test Status	﻿2026-08-07T07:13:52.9461075Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-07T07:13:52.9461691Z ^[[36;1mif [ "failed" != "success" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9462242Z ^[[36;1m  echo "❌ Tests failed with status: failed"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9462750Z ^[[36;1m  echo "📊 Test Results:"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9463205Z ^[[36;1m  echo "  - Total Tests: 1631"^[[0m

══ block 3 ══
ci-build	Check Test Status	﻿2026-08-07T07:13:52.9461075Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-07T07:13:52.9461691Z ^[[36;1mif [ "failed" != "success" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9462242Z ^[[36;1m  echo "❌ Tests failed with status: failed"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9462750Z ^[[36;1m  echo "📊 Test Results:"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9463205Z ^[[36;1m  echo "  - Total Tests: 1631"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9463652Z ^[[36;1m  echo "  - Failed Tests: 5"^[[0m

══ block 4 ══
ci-build	Check Test Status	2026-08-07T07:13:52.9462750Z ^[[36;1m  echo "📊 Test Results:"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9463205Z ^[[36;1m  echo "  - Total Tests: 1631"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9463652Z ^[[36;1m  echo "  - Failed Tests: 5"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9464112Z ^[[36;1m  echo "  - Passed Tests: 1613"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9475159Z ^[[36;1m  echo "  - Skipped Tests: 13"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9476194Z ^[[36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.common.PromptsTest ai.platon.pulsar.rest.api.service.ScrapeServiceTests"^[[0m

══ block 5 ══
ci-build	Check Test Status	2026-08-07T07:13:52.9464112Z ^[[36;1m  echo "  - Passed Tests: 1613"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9475159Z ^[[36;1m  echo "  - Skipped Tests: 13"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9476194Z ^[[36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.common.PromptsTest ai.platon.pulsar.rest.api.service.ScrapeServiceTests"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9477470Z ^[[36;1m  if [ -n "$FAILED_LIST" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9477920Z ^[[36;1m    echo ""^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9478294Z ^[[36;1m    echo "❌ Failed Tests:"^[[0m

══ block 6 ══
ci-build	Check Test Status	2026-08-07T07:13:52.9475159Z ^[[36;1m  echo "  - Skipped Tests: 13"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9476194Z ^[[36;1m  FAILED_LIST="ai.platon.pulsar.rest.api.common.PromptsTest ai.platon.pulsar.rest.api.service.ScrapeServiceTests"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9477470Z ^[[36;1m  if [ -n "$FAILED_LIST" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9477920Z ^[[36;1m    echo ""^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9478294Z ^[[36;1m    echo "❌ Failed Tests:"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9478746Z ^[[36;1m    for test in $FAILED_LIST; do^[[0m

══ block 7 ══
ci-build	Check Test Status	2026-08-07T07:13:52.9477470Z ^[[36;1m  if [ -n "$FAILED_LIST" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9477920Z ^[[36;1m    echo ""^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52.9478294Z ^[[36;1m    echo "❌ Failed Tests:"^[[0m
ci-build	Check Test Status	2026-08-07T07:13:52

... (truncated — run gh run view 31156415848 --log-failed for full logs)

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
