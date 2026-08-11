Title: Fix ci.yml failure for tag v4.13.0-ci.8
Description: The ci.yml workflow run 31520199860 (tag v4.13.0-ci.8) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 31520199860) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.0-ci.8
- **Run ID:** 31520199860
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/31520199860



## Reproduce

`ash
# View all failed logs
gh run view 31520199860 --log-failed

# View the run in browser
gh run view 31520199860 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:11:20.8574506Z   CARGO_INCREMENTAL: 0
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:11:20.8574813Z   CARGO_TERM_COLOR: always
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:11:20.8575115Z   CACHE_ON_FAILURE: false
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:11:20.8575601Z   DOCKER_TAGS: browser4:b289a786340a0aa3d624deb646c63e3654c3477f,browser4:latest
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:11:20.8576204Z   BROWSER4_E2E_SERVICE_URL: http://localhost:8182
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:11:20.8576650Z   BROWSER4_E2E_FIXTURE_HOST: host.docker.internal

══ block 2 ══
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:12:05.3302140Z final cleanup:
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:12:05.3302766Z 2026-08-11 18:12:05.263616359 +00:00 test test_e2e_command_coverage ...     1. browser4 final service cleanup: 0.07s
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:12:05.3309523Z ^[[1m^[[91merror^[[0m: test failed, to rerun pass `--test e2e`
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:12:05.3426766Z ##[error]Process completed with exit code 101.

══ block 3 ══
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:12:05.3302766Z 2026-08-11 18:12:05.263616359 +00:00 test test_e2e_command_coverage ...     1. browser4 final service cleanup: 0.07s
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:12:05.3309523Z ^[[1m^[[91merror^[[0m: test failed, to rerun pass `--test e2e`
ci-build	Run browser4-cli E2E Tests	2026-08-11T18:12:05.3426766Z ##[error]Process completed with exit code 101.

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
