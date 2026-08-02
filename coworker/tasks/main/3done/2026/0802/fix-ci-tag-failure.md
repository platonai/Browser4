Title: Fix ci.yml failure for tag v4.12.2-ci.5
Description: The ci.yml run 30733988186 (tag v4.12.2-ci.5) has failed. Minimal error messages extracted from failed job logs below. Please reproduce and fix the root cause.
Prompt: The ci.yml run 30733988186 for tag v4.12.2-ci.5 failed.

## Reproduce
`ash
gh run view 30733988186 --log-failed
gh run view 30733988186 --web
`

## Errors

══ block 1 ══
ci-build	Check Test Status	锘?026-08-02T05:33:33.8361518Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-02T05:33:33.8362137Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8362715Z [36;1m  echo "鉂?Tests failed with status: failed"[0m

══ block 2.5 ══
ci-build	Check Test Status	锘?026-08-02T05:33:33.8361518Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-02T05:33:33.8362137Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8362715Z [36;1m  echo "鉂?Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8363292Z [36;1m  echo "馃搳 Test Results:"[0m

══ block 4 ══
ci-build	Check Test Status	锘?026-08-02T05:33:33.8361518Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-02T05:33:33.8362137Z [36;1mif [ "failed" != "success" ]; then[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8362715Z [36;1m  echo "鉂?Tests failed with status: failed"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8363292Z [36;1m  echo "馃搳 Test Results:"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8363757Z [36;1m  echo "  - Total Tests: 1915"[0m

══ block 5.5 ══
ci-build	Check Test Status	2026-08-02T05:33:33.8363292Z [36;1m  echo "馃搳 Test Results:"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8363757Z [36;1m  echo "  - Total Tests: 1915"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8364097Z [36;1m  echo "  - Failed Tests: 8"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8364462Z [36;1m  echo "  - Passed Tests: 1851"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8364778Z [36;1m  echo "  - Skipped Tests: 56"[0m

══ block 7 ══
ci-build	Check Test Status	2026-08-02T05:33:33.8364462Z [36;1m  echo "  - Passed Tests: 1851"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8364778Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8365757Z [36;1m  FAILED_LIST="ai.platon.pulsar.chrome.dom.PulsarWebDriverMockSiteJsFullTests ai.platon.pulsar.chrome.dom.PulsarWebDriverMockSiteJsTests ai.platon.pulsar.chrome.dom.SnapshotServiceFullCoverageTest"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8366721Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367028Z [36;1m    echo ""[0m

══ block 8.5 ══
ci-build	Check Test Status	2026-08-02T05:33:33.8364778Z [36;1m  echo "  - Skipped Tests: 56"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8365757Z [36;1m  FAILED_LIST="ai.platon.pulsar.chrome.dom.PulsarWebDriverMockSiteJsFullTests ai.platon.pulsar.chrome.dom.PulsarWebDriverMockSiteJsTests ai.platon.pulsar.chrome.dom.SnapshotServiceFullCoverageTest"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8366721Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367028Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367295Z [36;1m    echo "鉂?Failed Tests:"[0m

══ block 10 ══
ci-build	Check Test Status	2026-08-02T05:33:33.8366721Z [36;1m  if [ -n "$FAILED_LIST" ]; then[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367028Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367295Z [36;1m    echo "鉂?Failed Tests:"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367602Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367947Z [36;1m      echo "  - $test"[0m

══ block 11.5 ══
ci-build	Check Test Status	2026-08-02T05:33:33.8367028Z [36;1m    echo ""[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367295Z [36;1m    echo "鉂?Failed Tests:"[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367602Z [36;1m    for test in $FAILED_LIST; do[0m
ci-build	Check Test Status	2026-08-02T05:33:33.8367947Z [36;1m      echo "  - $test"[0m
ci-build	Check Test Status	2026-08-02T05:33:33

... (truncated — full logs: gh run view 30733988186 --log-failed)

## Instructions
1. Examine the error messages above to understand what failed
2. Reproduce the failure by checking the relevant code paths
3. Fix the root cause — do not just silence the error
4. Verify the fix: build succeeds and relevant tests pass
5. Commit with a conventional-commit message
