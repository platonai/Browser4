Title: Fix ci.yml failure for tag v4.13.0-ci.6
Description: The ci.yml workflow run 31514569110 (tag v4.13.0-ci.6) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 31514569110) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.0-ci.6
- **Run ID:** 31514569110
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/31514569110



## Reproduce

`ash
# View all failed logs
gh run view 31514569110 --log-failed

# View the run in browser
gh run view 31514569110 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Maven Build	2026-08-11T16:52:10.3143846Z with:
ci-build	Maven Build	2026-08-11T16:52:10.3144064Z   skip_tests: true
ci-build	Maven Build	2026-08-11T16:52:10.3144303Z   timeout_minutes: 15
ci-build	Maven Build	2026-08-11T16:52:10.3144577Z   maven_profiles: all-main-modules
ci-build	Maven Build	2026-08-11T16:52:10.3144865Z   maven_args: -B -V
ci-build	Maven Build	2026-08-11T16:52:10.3145104Z   clean_before_build: true

══ block 2 ══
ci-build	Maven Build	2026-08-11T16:52:10.6412401Z ^[[36;1mecho "  - Clean Before Build: true"^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6412721Z ^[[36;1mecho "  - Parallel Builds: false"^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6413021Z ^[[36;1mecho "  - Timeout: 15 minutes"^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6413325Z ^[[36;1mecho "  - Additional Args: -B -V"^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6413602Z ^[[36;1m^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6413827Z ^[[36;1m# Build Maven command^[[0m

══ block 3 ══
ci-build	Maven Build	2026-08-11T16:52:10.6422307Z ^[[36;1mecho ""^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6422531Z ^[[36;1m^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6422737Z ^[[36;1m# Execute with timeout^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6423021Z ^[[36;1mtimeout_seconds=$(( 15 * 60 ))^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6423288Z ^[[36;1m^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6423543Z ^[[36;1mif timeout $timeout_seconds $build_cmd; then^[[0m

══ block 4 ══
ci-build	Maven Build	2026-08-11T16:52:10.6422531Z ^[[36;1m^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6422737Z ^[[36;1m# Execute with timeout^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6423021Z ^[[36;1mtimeout_seconds=$(( 15 * 60 ))^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6423288Z ^[[36;1m^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6423543Z ^[[36;1mif timeout $timeout_seconds $build_cmd; then^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6424047Z ^[[36;1m  end_time=$(date +%s)^[[0m

══ block 5 ══
ci-build	Maven Build	2026-08-11T16:52:10.6423021Z ^[[36;1mtimeout_seconds=$(( 15 * 60 ))^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6423288Z ^[[36;1m^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6423543Z ^[[36;1mif timeout $timeout_seconds $build_cmd; then^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6424047Z ^[[36;1m  end_time=$(date +%s)^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6424356Z ^[[36;1m  build_time=$((end_time - start_time))^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6424667Z ^[[36;1m  echo ""^[[0m

══ block 6 ══
ci-build	Maven Build	2026-08-11T16:52:10.6426746Z ^[[36;1m  build_time=$((end_time - start_time))^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6427050Z ^[[36;1m  echo ""^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6427381Z ^[[36;1m  echo "❌ Maven build failed or timed out after ${build_time} seconds"^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6427806Z ^[[36;1m  echo "status=failed" >> $GITHUB_OUTPUT^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6428160Z ^[[36;1m  echo "build_time=$build_time" >> $GITHUB_OUTPUT^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6428477Z ^[[36;1m  exit 1^[[0m

══ block 7 ══
ci-build	Maven Build	2026-08-11T16:52:10.6427050Z ^[[36;1m  echo ""^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6427381Z ^[[36;1m  echo "❌ Maven build failed or timed out after ${build_time} seconds"^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6427806Z ^[[36;1m  echo "status=failed" >> $GITHUB_OUTPUT^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6428160Z ^[[36;1m  echo "build_time=$build_time" >> $GITHUB_OUTPUT^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6428477Z ^[[36;1m  exit 1^[[0m
ci-build	Maven Build	2026-08-11T16:52:10.6428696Z ^[[36;1mfi^[[0m

══ block 8 ══
ci-build	Maven Build	2026-08-11T16:52:10.6564615Z   - Clean Before Build: true
ci-build	Maven Build	2026-08-11T16:52:10.6565022Z   - Parallel Builds: false
ci-build	Maven Build	2026-08-11T16:52:10.6565421Z   - Timeout: 1

... (truncated — run gh run view 31514569110 --log-failed for full logs)

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
