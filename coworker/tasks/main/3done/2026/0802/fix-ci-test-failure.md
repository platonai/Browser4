Title: Fix ci.yml failure for tag v4.12.2-ci.7
Description: The ci.yml run 30761762310 (tag v4.12.2-ci.7) has failed. Minimal error messages extracted from failed job logs below. Please reproduce and fix the root cause.
Prompt: The ci.yml run 30761762310 for tag v4.12.2-ci.7 failed.

## Reproduce
`ash
gh run view 30761762310 --log-failed
gh run view 30761762310 --web
`

## Errors

══ block 1 ══
ci-build	Check Test Status	锘?026-08-02T18:53:50.6540313Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-02T18:53:50.6540638Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6540922Z [36;1m  echo "鉂?Tests failed with status: failed"[0m

══ block 2 ══
ci-build	Check Test Status	锘?026-08-02T18:53:50.6540313Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-02T18:53:50.6540638Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6540922Z [36;1m  echo "鉂?Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6541199Z [36;1m  echo "馃搳 Test Results:"[0m

══ block 3 ══
ci-build	Check Test Status	锘?026-08-02T18:53:50.6540313Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-02T18:53:50.6540638Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6540922Z [36;1m  echo "鉂?Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6541199Z [36;1m  echo "馃搳 Test Results:"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6541446Z [36;1m  echo "  - Total Tests: 1915"[0m

══ block 4 ══
ci-build	Check Test Status	2026-08-02T18:53:50.6541199Z [36;1m  echo "馃搳 Test Results:"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6541446Z [36;1m  echo "  - Total Tests: 1915"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6541686Z [36;1m  echo "  - Failed Tests: 6"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6541949Z [36;1m  echo "  - Passed Tests: 1853"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6542205Z [36;1m  echo "  - Skipped Tests: 56"[0m

══ block 5 ══
ci-build	Check Test Status	2026-08-02T18:53:50.6541949Z [36;1m  echo "  - Passed Tests: 1853"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6542205Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6542570Z [36;1m  FAILED_LIST="ai.platon.pulsar.chrome.dom.SnapshotServiceFullCoverageTest"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6542944Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543176Z [36;1m    echo ""[0m

══ block 6 ══
ci-build	Check Test Status	2026-08-02T18:53:50.6542205Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6542570Z [36;1m  FAILED_LIST="ai.platon.pulsar.chrome.dom.SnapshotServiceFullCoverageTest"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6542944Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543176Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543368Z [36;1m    echo "鉂?Failed Tests:"[0m

══ block 7 ══
ci-build	Check Test Status	2026-08-02T18:53:50.6542944Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543176Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543368Z [36;1m    echo "鉂?Failed Tests:"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543603Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543834Z [36;1m      echo "  - $test"[0m

══ block 8 ══
ci-build	Check Test Status	2026-08-02T18:53:50.6543176Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543368Z [36;1m    echo "鉂?Failed Tests:"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543603Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6543834Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	2026-08-02T18:53:50.6544068Z [36;1m    done[0m

══ block 9 ══
ci-build	Check Test Status	2026-08-02T18:53:50.6588670Z   MAVEN_CMD: ./mvnw
ci-build	Check Test Status	2026-08-02T18:53:50.6588854Z ##[endgroup]
ci-build	Check Test Status	2026-08-02T18:53:50.6649771Z 鉂?Tests 

... (truncated — full logs: gh run view 30761762310 --log-failed)

## Instructions
1. Examine the error messages above to understand what failed
2. Reproduce the failure by checking the relevant code paths
3. Fix the root cause — do not just silence the error
4. Verify the fix: build succeeds and relevant tests pass
5. Commit with a conventional-commit message
