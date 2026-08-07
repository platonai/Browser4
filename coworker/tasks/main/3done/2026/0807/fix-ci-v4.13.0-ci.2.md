Title: Fix ci.yml failure for tag v4.13.0-ci.2
Description: The ci.yml workflow run 31161370803 (tag v4.13.0-ci.2) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 31161370803) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.0-ci.2
- **Run ID:** 31161370803
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/31161370803



## Reproduce

`ash
# View all failed logs
gh run view 31161370803 --log-failed

# View the run in browser
gh run view 31161370803 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Check Test Status	﻿2026-08-07T08:32:32.2832508Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-07T08:32:32.2832908Z ^[[36;1mif [ "failed" != "success" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833253Z ^[[36;1m  echo "❌ Tests failed with status: failed"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833587Z ^[[36;1m  echo "📊 Test Results:"^[[0m

══ block 2 ══
ci-build	Check Test Status	﻿2026-08-07T08:32:32.2832508Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-07T08:32:32.2832908Z ^[[36;1mif [ "failed" != "success" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833253Z ^[[36;1m  echo "❌ Tests failed with status: failed"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833587Z ^[[36;1m  echo "📊 Test Results:"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833881Z ^[[36;1m  echo "  - Total Tests: 1708"^[[0m

══ block 3 ══
ci-build	Check Test Status	﻿2026-08-07T08:32:32.2832508Z ##[group]Run if [ "failed" != "success" ]; then
ci-build	Check Test Status	2026-08-07T08:32:32.2832908Z ^[[36;1mif [ "failed" != "success" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833253Z ^[[36;1m  echo "❌ Tests failed with status: failed"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833587Z ^[[36;1m  echo "📊 Test Results:"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833881Z ^[[36;1m  echo "  - Total Tests: 1708"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2834198Z ^[[36;1m  echo "  - Failed Tests: 2"^[[0m

══ block 4 ══
ci-build	Check Test Status	2026-08-07T08:32:32.2833587Z ^[[36;1m  echo "📊 Test Results:"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2833881Z ^[[36;1m  echo "  - Total Tests: 1708"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2834198Z ^[[36;1m  echo "  - Failed Tests: 2"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2834509Z ^[[36;1m  echo "  - Passed Tests: 1657"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2834833Z ^[[36;1m  echo "  - Skipped Tests: 49"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2835242Z ^[[36;1m  FAILED_LIST="ai.platon.pulsar.browser.PulsarWebDriverTests"^[[0m

══ block 5 ══
ci-build	Check Test Status	2026-08-07T08:32:32.2834509Z ^[[36;1m  echo "  - Passed Tests: 1657"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2834833Z ^[[36;1m  echo "  - Skipped Tests: 49"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2835242Z ^[[36;1m  FAILED_LIST="ai.platon.pulsar.browser.PulsarWebDriverTests"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2835676Z ^[[36;1m  if [ -n "$FAILED_LIST" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2835965Z ^[[36;1m    echo ""^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2836751Z ^[[36;1m    echo "❌ Failed Tests:"^[[0m

══ block 6 ══
ci-build	Check Test Status	2026-08-07T08:32:32.2834833Z ^[[36;1m  echo "  - Skipped Tests: 49"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2835242Z ^[[36;1m  FAILED_LIST="ai.platon.pulsar.browser.PulsarWebDriverTests"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2835676Z ^[[36;1m  if [ -n "$FAILED_LIST" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2835965Z ^[[36;1m    echo ""^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2836751Z ^[[36;1m    echo "❌ Failed Tests:"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2837154Z ^[[36;1m    for test in $FAILED_LIST; do^[[0m

══ block 7 ══
ci-build	Check Test Status	2026-08-07T08:32:32.2835676Z ^[[36;1m  if [ -n "$FAILED_LIST" ]; then^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2835965Z ^[[36;1m    echo ""^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2836751Z ^[[36;1m    echo "❌ Failed Tests:"^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2837154Z ^[[36;1m    for test in $FAILED_LIST; do^[[0m
ci-build	Check Test Status	2026-08-07T08:32:32.2837465Z ^[[36;1m      echo "  - $test"^[[0m
ci-build

... (truncated — run gh run view 31161370803 --log-failed for full logs)

## Resolution

### Categorization
**Test assertion format change** — the test assertions were not updated to match the new `vi` attribute format introduced by a prior commit.

### Root Cause
Commit `c0f839513` ("feat: compress vi attribute with base-36 integer encoding") changed the `vi` attribute format from space-separated decimal integers (e.g., `vi="124 457 201 51"`) to comma-separated base-36 integers (e.g., `vi="3g,cp,5k,1f"`). The default compression mode changed from `"none"` to `"base36"` in the injected Browser4 runtime JavaScript.

Two tests in `PulsarWebDriverTests` used a regex that only matched the old space-separated decimal format (`\d+(?:\.\d+)? \d+...`). With the new base-36 format, the regex matched zero `vi` attributes, causing both tests to fail with "got none".

These tests were not caught in the v4.13.0-ci.1 CI run because the `pulsar-it-tests` module's PulsarWebDriverTests class wasn't executed (likely excluded by Maven reactor ordering after the browser4-rest-tests module failed with 5 errors from the lazy-init issue).

### Fix
Updated `testPageSourceReturnsViAttributes` and `testOuterHTMLReturnsViAttributes` in `PulsarWebDriverTests.kt`:
- Updated the `vi` regex to match all three ViBox-supported formats:
  - Base-36 comma-separated (default): `vi="3g,cp,5k,1f"`
  - Compact decimal comma-separated: `vi="0,0,1920,1080"`
  - Legacy space-separated decimal: `vi="123.5 456.7 200.3 50.8"`
- Updated the value validation to split on comma when present (new formats) or space (legacy), and to accept base-36 alphanumeric characters in addition to digits.

### Files Changed
- `browser4-tests/pulsar-it-tests/src/test/kotlin/ai/platon/pulsar/browser/PulsarWebDriverTests.kt`

### Verification
- The regex was tested against all three vi format strings and correctly matches them
- The regex correctly rejects non-vi attributes (like `xxvi=`)
- The `testOuterHTMLSelectorReturnsViAttributes` test (which only checks `html.contains("vi=")`) was already compatible with the new format and didn't need changes

### Instructions (completed)
1. **Categorize the failure:** Test assertion format change — tests needed updating for new vi attribute encoding
2. **If tests need updating:** ✅ Updated test assertions to match all three vi formats
3. **If it is a real regression:** N/A — not a regression
4. **If it is a flaky test:** N/A — not flaky
5. **Verify:** ✅ Regex verified against all format variants
6. **Commit:** See fix commit
