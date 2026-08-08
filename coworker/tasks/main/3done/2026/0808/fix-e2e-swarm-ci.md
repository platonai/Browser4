Title: Fix ci.yml failure for tag v4.13.0-ci.4
Description: The ci.yml workflow run 31251995692 (tag v4.13.0-ci.4) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 31251995692) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.0-ci.4
- **Run ID:** 31251995692
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/31251995692



## Reproduce

`ash
# View all failed logs
gh run view 31251995692 --log-failed

# View the run in browser
gh run view 31251995692 --web
`

## Error Diagnostics

## Failing Tests

- test_e2e_swarm_session_and_agent_tools
- test_e2e_swarm_submission_commands
- test_e2e_swarm_query_commands
- test_e2e_swarm_query_validation_errors
- test_e2e_swarm_list_and_clear
- test_e2e_swarm_close_session

## Error Details

══ block 1 ══
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:18:53.1632769Z   CARGO_INCREMENTAL: 0
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:18:53.1632966Z   CARGO_TERM_COLOR: always
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:18:53.1633166Z   CACHE_ON_FAILURE: false
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:18:53.1633464Z   DOCKER_TAGS: browser4:c23019d807fd6730e7737d3a74fe1cde5cc83fcd,browser4:latest
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:18:53.1633825Z   BROWSER4_E2E_SERVICE_URL: http://localhost:8182
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:18:53.1634102Z   BROWSER4_E2E_FIXTURE_HOST: host.docker.internal

══ block 2 ══
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:21.3446141Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 mouseup left
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:21.5453223Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval document.getElementById('state-log').textContent
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:21.7461322Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval (() => { setTimeout(() => document.getElementById('prompt-target').click(), 100); return 'scheduled'; })()
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:21.9468639Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 dialog-accept accepted by cli
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:22.1474945Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval document.getElementById('state-log').textContent
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:22.3482783Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval (() => { setTimeout(() => document.getElementById('confirm-target').click(), 100); return 'scheduled'; })()

══ block 3 ══
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:21.9468639Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 dialog-accept accepted by cli
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:22.1474945Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval document.getElementById('state-log').textContent
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:22.3482783Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval (() => { setTimeout(() => document.getElementById('confirm-target').click(), 100); return 'scheduled'; })()
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:22.5488979Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 dialog-dismiss
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:22.7495893Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval document.getElementById('state-log').textContent
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:22.9503650Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 close

══ block 4 ══
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:51.3719131Z no sessionId in persisted state file
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:51.3719460Z note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:51.3719822Z FAILED (0.20s) - no sessionId in persisted state file
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:51.3720091Z     1. reset CLI artifacts: 0.00s
ci-build	Run browser4-cli E2E Tests	2026-08-08T10:22:51.3720419Z     2. mock Browser4 server start: 0.00s
ci-build	Run b

... (truncated — run gh run view 31251995692 --log-failed for full logs)

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
