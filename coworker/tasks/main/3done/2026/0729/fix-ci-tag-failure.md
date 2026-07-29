Title: Fix ci.yml failure for tag v4.12.2-ci.1
Description: The ci.yml run 30487474169 (tag v4.12.2-ci.1) has failed. Minimal error messages extracted from failed job logs below. Please reproduce and fix the root cause.
Prompt: The ci.yml run 30487474169 for tag v4.12.2-ci.1 failed.

## Reproduce
`ash
gh run view 30487474169 --log-failed
gh run view 30487474169 --web
`

## Errors

══ block 1 ══
ci-build	Check Test Status	﻿2026-07-29T20:16:10.4741053Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-07-29T20:16:10.4741464Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4741810Z [36;1m  echo "❌ Tests failed with status: failed"[0m

══ block 2.5 ══
ci-build	Check Test Status	﻿2026-07-29T20:16:10.4741053Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-07-29T20:16:10.4741464Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4741810Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4742136Z [36;1m  echo "📊 Test Results:"[0m

══ block 4 ══
ci-build	Check Test Status	﻿2026-07-29T20:16:10.4741053Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-07-29T20:16:10.4741464Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4741810Z [36;1m  echo "❌ Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4742136Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4742426Z [36;1m  echo "  - Total Tests: 361"[0m

══ block 5.5 ══
ci-build	Check Test Status	2026-07-29T20:16:10.4742136Z [36;1m  echo "📊 Test Results:"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4742426Z [36;1m  echo "  - Total Tests: 361"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4742711Z [36;1m  echo "  - Failed Tests: 1"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4743000Z [36;1m  echo "  - Passed Tests: 360"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4743478Z [36;1m  echo "  - Skipped Tests: 0"[0m

══ block 7 ══
ci-build	Check Test Status	2026-07-29T20:16:10.4743000Z [36;1m  echo "  - Passed Tests: 360"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4743478Z [36;1m  echo "  - Skipped Tests: 0"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4743879Z [36;1m  FAILED_LIST="ai.platon.browser4.api.snapshot.ViewportSpecTest"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4744300Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4744575Z [36;1m    echo ""[0m

══ block 8.5 ══
ci-build	Check Test Status	2026-07-29T20:16:10.4743478Z [36;1m  echo "  - Skipped Tests: 0"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4743879Z [36;1m  FAILED_LIST="ai.platon.browser4.api.snapshot.ViewportSpecTest"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4744300Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4744575Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4744808Z [36;1m    echo "❌ Failed Tests:"[0m

══ block 10 ══
ci-build	Check Test Status	2026-07-29T20:16:10.4744300Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4744575Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4744808Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4745085Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4745356Z [36;1m      echo "  - $test"[0m

══ block 11.5 ══
ci-build	Check Test Status	2026-07-29T20:16:10.4744575Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4744808Z [36;1m    echo "❌ Failed Tests:"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4745085Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4745356Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	2026-07-29T20:16:10.4745604Z [36;1m    done[0m

══ block 13 ══
ci-build	Check Test Status	2026-07-29T20:16:10.4796792Z   MAVEN_CMD: ./mvnw
ci-build	Check Test Status	2026-07-29T20:16:10.4797007Z ##[endgroup]
ci-build	Check Test Status	2026-07-29T20:16:10.4862854Z ❌ Tests failed with status

... (truncated — full logs: gh run view 30487474169 --log-failed)

## Instructions
1. Examine the error messages above to understand what failed
2. Reproduce the failure by checking the relevant code paths
3. Fix the root cause — do not just silence the error
4. Verify the fix: build succeeds and relevant tests pass
5. Commit with a conventional-commit message
