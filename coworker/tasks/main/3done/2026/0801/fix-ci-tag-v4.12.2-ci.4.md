Title: Fix ci.yml failure for tag v4.12.2-ci.4
Description: The ci.yml run 30707028605 (tag v4.12.2-ci.4) has failed. Minimal error messages extracted from failed job logs below. Please reproduce and fix the root cause.
Prompt: The ci.yml run 30707028605 for tag v4.12.2-ci.4 failed.

## Reproduce
`ash
gh run view 30707028605 --log-failed
gh run view 30707028605 --web
`

## Errors

══ block 1 ══
ci-build	Check Test Status	﻿2026-08-01T16:08:42.8867565Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-01T16:08:42.8868065Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8868479Z [36;1m  echo "❌ Tests failed with status: failed"[0m

══ block 2.5 ══
ci-build	Check Test Status	﻿2026-08-01T16:08:42.8867565Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-01T16:08:42.8868065Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8868479Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8868901Z [36;1m  echo "📊 Test Results:"[0m

══ block 4 ══
ci-build	Check Test Status	﻿2026-08-01T16:08:42.8867565Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-01T16:08:42.8868065Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8868479Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8868901Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8869254Z [36;1m  echo "  - Total Tests: 1915"[0m

══ block 5.5 ══
ci-build	Check Test Status	2026-08-01T16:08:42.8868901Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8869254Z [36;1m  echo "  - Total Tests: 1915"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8869613Z [36;1m  echo "  - Failed Tests: 8"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8869979Z [36;1m  echo "  - Passed Tests: 1851"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8870348Z [36;1m  echo "  - Skipped Tests: 56"[0m

══ block 7 ══
ci-build	Check Test Status	2026-08-01T16:08:42.8869979Z [36;1m  echo "  - Passed Tests: 1851"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8870348Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8871385Z [36;1m  FAILED_LIST="ai.platon.pulsar.chrome.dom.PulsarWebDriverMockSiteJsFullTests ai.platon.pulsar.chrome.dom.PulsarWebDriverMockSiteJsTests ai.platon.pulsar.chrome.dom.SnapshotServiceFullCoverageTest"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8872441Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8872800Z [36;1m    echo ""[0m

══ block 8.5 ══
ci-build	Check Test Status	2026-08-01T16:08:42.8870348Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8871385Z [36;1m  FAILED_LIST="ai.platon.pulsar.chrome.dom.PulsarWebDriverMockSiteJsFullTests ai.platon.pulsar.chrome.dom.PulsarWebDriverMockSiteJsTests ai.platon.pulsar.chrome.dom.SnapshotServiceFullCoverageTest"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8872441Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8872800Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8873112Z [36;1m    echo "❌ Failed Tests:"[0m

══ block 10 ══
ci-build	Check Test Status	2026-08-01T16:08:42.8872441Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8872800Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8873112Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8873734Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8874155Z [36;1m      echo "  - $test"[0m

══ block 11.5 ══
ci-build	Check Test Status	2026-08-01T16:08:42.8872800Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8873112Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8873734Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-08-01T16:08:42.8874155Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	2026-08-01T16:08:42

... (truncated — full logs: gh run view 30707028605 --log-failed)

## Instructions
1. Examine the error messages above to understand what failed
2. Reproduce the failure by checking the relevant code paths
3. Fix the root cause — do not just silence the error
4. Verify the fix: build succeeds and relevant tests pass
5. Commit with a conventional-commit message
