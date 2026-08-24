Title: Fix ci.yml failure for tag v4.13.9-ci.3
Description: The ci.yml workflow run 32597804317 (tag v4.13.9-ci.3) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 32597804317) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.9-ci.3
- **Run ID:** 32597804317
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/32597804317



## Reproduce

`ash
# View all failed logs
gh run view 32597804317 --log-failed

# View the run in browser
gh run view 32597804317 --web
`

## Error Diagnostics

## Failing Tests

- test_e2e_navigation_and_storage
- test_e2e_storage_state_commands
- test_e2e_interaction_commands
- test_e2e_pointer_commands
- test_e2e_eval_command

## Error Details

══ block 1 ══
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:05:58.0985448Z   CARGO_INCREMENTAL: 0
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:05:58.0985636Z   CARGO_TERM_COLOR: always
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:05:58.0985825Z   CACHE_ON_FAILURE: false
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:05:58.0986149Z   DOCKER_TAGS: browser4:dadf281267a4ef380926ed0ccdda044cfcf7425f,browser4:latest
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:05:58.0986532Z   BROWSER4_E2E_SERVICE_URL: http://localhost:8182
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:05:58.0986812Z   BROWSER4_E2E_FIXTURE_HOST: host.docker.internal

══ block 2 ══
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2808794Z      [1m[94m= [0m[1mnote[0m: `#[warn(unused_variables)]` (part of `#[warn(unused)]`) on by default
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2809761Z 
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2810242Z [1m[33mwarning[0m[1m: unused variable: `timeout`[0m
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2810931Z     [1m[94m--> [0msrc/managed_processes.rs:1163:9
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2811530Z      [1m[94m|[0m
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2812342Z [1m[94m1163[0m [1m[94m|[0m     let timeout = std::time::Duration::from_millis(timeout_ms);

══ block 3 ══
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2810931Z     [1m[94m--> [0msrc/managed_processes.rs:1163:9
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2811530Z      [1m[94m|[0m
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2812342Z [1m[94m1163[0m [1m[94m|[0m     let timeout = std::time::Duration::from_millis(timeout_ms);
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2813473Z      [1m[94m|[0m         [1m[33m^^^^^^^[0m [1m[33mhelp: if this is intentional, prefix it with an underscore: `_timeout`[0m
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2814183Z 
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2814615Z [1m[33mwarning[0m[1m: unused variable: `poll`[0m

══ block 4 ══
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2811530Z      [1m[94m|[0m
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2812342Z [1m[94m1163[0m [1m[94m|[0m     let timeout = std::time::Duration::from_millis(timeout_ms);
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2813473Z      [1m[94m|[0m         [1m[33m^^^^^^^[0m [1m[33mhelp: if this is intentional, prefix it with an underscore: `_timeout`[0m
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2814183Z 
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2814615Z [1m[33mwarning[0m[1m: unused variable: `poll`[0m
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:06:34.2815289Z     [1m[94m--> [0msrc/managed_processes.rs:1164:9

══ block 5 ══
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:09:24.5515287Z 
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:09:24.5515825Z thread 'main' (29346) panicked at tests/e2e/mod.rs:2551:5:
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:09:24.5516525Z assertion `left == right` failed: Command ["goto", "http://host.docker.internal:43815/interactive"] failed (exit=-1):
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:09:24.5517132Z stdout:>>>
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:09:24.5517599Z Using existing session DEFAULT (current page: http://host.docker.internal:43815/interactive).
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:09:24.5518082Z

══ block 6 ══
ci-build	Run browser4-cli E2E Tests	2026-08-22T21:09:24.5520281Z note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace
ci-build	Run browser4-cli E2E Tests	2026-

... (truncated — run gh run view 32597804317 --log-failed for full logs)

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

## Resolution (2026-08-23)

Root cause chain (two independent defects, both fixed on 4.13.x):

1. **Same-URL goto dead wait (~150s > 120s harness timeout).**
   test_e2e_navigation_and_storage does open(url) then goto(same url). On
   dadf281267, waitForPotentialNavigation polled readyState 30s and then
   unconditionally waited for body with the full 30s budget, and
   AgentToolManager.onDidNavigate stacked a 60s-default body wait on top —
   ~150s per navigation action when the page context is wedged, killing the
   CLI via the 120s harness timeout (exit=-1).
   Fixed in 1116207e8b: body wait uses the short 10s DOM-ready budget only
   when the document actually became ready; otherwise warn and return.

2. **Storage-state restore races the navigation (wedged page context).**
   test_e2e_storage_state_commands hung on 'eval document.cookie...' 30s x5
   (HTTP timeout). Upstream pulsar-browser loadStorageState calls open(origin)
   whose no-arg waitForNavigation() short-circuits as soon as the tab has any
   URL, then immediately evaluates window.localStorage — racing the navigation
   and evaluating against an opaque-origin provisional document
   (SecurityError: Access is denied for this document). That wedges the page
   context so every later eval hangs and subsequent tests fail in a chain.
   Fixed in ec31ed84d1: ported the 4.14.x Browser4WebDriver loadStorageState
   override (a46a86fb12) — navigate to each origin, poll location.origin until
   the document commits, then restore localStorage.

Verification:
- browser4-agentic 623 tests green (24 executor + 8 manager, incl. 3 new
  regression tests for the stacked body wait)
- browser4-browser 182 tests green (21 Browser4WebDriverTest, incl. 6 new
  storage-state helper tests)
- agentic + rest compile clean
- Both commits pushed to origin/4.13.x

Next: re-run CI on a new tag to confirm the e2e chain is green.
