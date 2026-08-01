Title: Fix ci.yml failure for tag v4.12.2-ci.3
Description: The ci.yml run 30697315692 (tag v4.12.2-ci.3) has failed. Minimal error messages extracted from failed job logs below. Please reproduce and fix the root cause.
Prompt: The ci.yml run 30697315692 for tag v4.12.2-ci.3 failed.

## Reproduce
`ash
gh run view 30697315692 --log-failed
gh run view 30697315692 --web
`

## Errors

══ block 1 ══
ci-build	Check Test Status	锘?026-08-01T11:26:40.8749202Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-01T11:26:40.8749615Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8749961Z [36;1m  echo "鉂?Tests failed with status: failed"[0m

══ block 2.5 ══
ci-build	Check Test Status	锘?026-08-01T11:26:40.8749202Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-01T11:26:40.8749615Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8749961Z [36;1m  echo "鉂?Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8750290Z [36;1m  echo "馃搳 Test Results:"[0m

══ block 4 ══
ci-build	Check Test Status	锘?026-08-01T11:26:40.8749202Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-01T11:26:40.8749615Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8749961Z [36;1m  echo "鉂?Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8750290Z [36;1m  echo "馃搳 Test Results:"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8750584Z [36;1m  echo "  - Total Tests: 1852"[0m

══ block 5.5 ══
ci-build	Check Test Status	2026-08-01T11:26:40.8750290Z [36;1m  echo "馃搳 Test Results:"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8750584Z [36;1m  echo "  - Total Tests: 1852"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8750877Z [36;1m  echo "  - Failed Tests: 1"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8751167Z [36;1m  echo "  - Passed Tests: 1802"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8751460Z [36;1m  echo "  - Skipped Tests: 49"[0m

══ block 7 ══
ci-build	Check Test Status	2026-08-01T11:26:40.8751167Z [36;1m  echo "  - Passed Tests: 1802"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8751460Z [36;1m  echo "  - Skipped Tests: 49"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8751871Z [36;1m  FAILED_LIST="ai.platon.pulsar.browser.TestWebDriverPoolManager"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8752327Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8752614Z [36;1m    echo ""[0m

══ block 8.5 ══
ci-build	Check Test Status	2026-08-01T11:26:40.8751460Z [36;1m  echo "  - Skipped Tests: 49"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8751871Z [36;1m  FAILED_LIST="ai.platon.pulsar.browser.TestWebDriverPoolManager"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8752327Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8752614Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8752854Z [36;1m    echo "鉂?Failed Tests:"[0m

══ block 10 ══
ci-build	Check Test Status	2026-08-01T11:26:40.8752327Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8752614Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8752854Z [36;1m    echo "鉂?Failed Tests:"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8753487Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8753784Z [36;1m      echo "  - $test"[0m

══ block 11.5 ══
ci-build	Check Test Status	2026-08-01T11:26:40.8752614Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8752854Z [36;1m    echo "鉂?Failed Tests:"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8753487Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8753784Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	2026-08-01T11:26:40.8754042Z [36;1m    done[0m

══ block 13 ══
ci-build	Check Test Status	2026-08-01T11:26:40.8804467Z   MAVEN_CMD: ./mvnw
ci-build	Check Test Status	2026-08-01T11:26:40.8804688Z ##[endgroup]
ci-build	Check Test Status	2026-08-01T11:26:40.8874670Z 鉂?Tests failed wi

... (truncated — full logs: gh run view 30697315692 --log-failed)

## Instructions
1. Examine the error messages above to understand what failed
2. Reproduce the failure by checking the relevant code paths
3. Fix the root cause — do not just silence the error
4. Verify the fix: build succeeds and relevant tests pass
5. Commit with a conventional-commit message
