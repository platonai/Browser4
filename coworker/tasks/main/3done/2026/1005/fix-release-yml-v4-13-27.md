Title: Fix release.yml failure for tag v4.13.27
Description: The release.yml workflow run 37332660300 (tag v4.13.27) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 37332660300) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.13.27
- **Run ID:** 37332660300
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/37332660300


## Failing Tests

- test_e2e_tab_commands ⚠️ (also fails in other recent runs — may be flaky)
- test_e2e_open_reconnect_warns_when_display_flag_ignored ⚠️ (also fails in other recent runs — may be flaky)
- test_e2e_agent_list_prunes_terminal_tasks ⚠️ (also fails in other recent runs — may be flaky)
- test_e2e_crawl_foreground ⚠️ (also fails in other recent runs — may be flaky)
- test_e2e_crawl_foreground_no_links_discovered ⚠️ (also fails in other recent runs — may be flaky)
- test_e2e_snapshot_grep ⚠️ (also fails in other recent runs — may be flaky)
- test_e2e_snapshot_grep_flags ⚠️ (also fails in other recent runs — may be flaky)
- test_e2e_snapshot_grep_unicode ⚠️ (also fails in other recent runs — may be flaky)
- test_e2e_htmlsnapshot_query_table_format ⚠️ (also fails in other recent runs — may be flaky)

## Failed Jobs

- **Build core artifacts and Docker image** (job ID: 111839534976)

## Release Changelog

```text
Commits since v4.14.0-rc.7:
ee7ff47b81 chore(ci): add task record for the v4.13.27 release.yml failure eec5fc89d3 fix(cli): register tool-call in the e2e tested-commands list 8889a9b933 fix(issues): close out 20261004 issue-report batch (18 reports) 6514a3ccc4 Auto-bump version to 4.13.27-SNAPSHOT e333f8c871 Bump version to v4.13.26 5104ea801e docs(dev): record the URL normalization backport onto 4.13.x f868671d2d fix(url): adapt the url-normalization backport to the 4.13.x harness 089e5b0b11 fix(crawl): order a queued url's query the way the engine's identity does 4230741a61 fix(protocol): the snapshot origin guard must not read a reordered query as another document faca27ece7 chore(deps): consume pulsar 4.11.24 b838a78962 docs(url): state the invariant — normalize for the key, href for the address d8a3c6c468 docs(url): audit the identity plumbing — normalize() is the only key 046f67e98b docs(url): record the base-library definition change and the canonical-form scope ebd6ae434a feat(url): consume pulsar-common 4.11.23, validate at the boundaries, go to the href 0fc7ca451c fix(rest): one escape, one url check, and a NormURL that keeps its detail 218b1b4f7d docs(url): record the Phase B fixes and the full verification log (2212 tests) 2a294c18f7 fix(experience): one URL spelling for the store, the pattern and the matcher 673bffc340 docs(url): the base-library URLUtilsTest runs green (43/43) 460dd36500 docs(url): record the executed fixes and the verification results 749215926c fix(url): make URL normalization honour its own contract e9d3d6c487 docs(url): add URL normalization review and fix plan 61a38a1737 Auto-bump version to 4.13.26-SNAPSHOT 672ddc0759 chore(cli): sync the CLI version files to 4.13.25 a72607696d Bump version to v4.13.25 6be7b364dd docs(dev): track all three CDP-attach open items, not just the two issues 5dab332283 docs(dev): link the non-Windows listening-port gap to its issue ae997ea376 docs(dev): record the doc-line-cap and release-order traps 83457578fe chore(cli): sync the CLI version files to 4.13.24 fa50ad1295 Bump version to v4.13.24 bb2f064bad docs(cli): keep browser-modes.md inside the 500-line decision-doc cap bb0e954837 fix(cli): compile on non-Windows after the process-list refactor f71abc913c docs(dev): record the WebSocket-attach findings and the 4.11.23 release run 8ae154f398 chore(cli): add CDP endpoint and WebSocket probe scripts e9ab82fe4f docs(cli): document WebSocket attach, extension_id and portable profiles 5c6c4054af feat(cli,rest): attach over the browser-level CDP WebSocket f3d870c8c1 feat(cli): add config extension_id 2aec2f4ce4 feat(cli): pick attach endpoints by page target, WebSocket-only browsers included a353fa1be6 chore(deps): bump browser4-base to 4.11.23
```

## Reproduce

`ash
# View all failed logs
gh run view 37332660300 --log-failed

# View the run in browser
gh run view 37332660300 --web
`

## Error Diagnostics

## Failing Tests

- test_e2e_tab_commands
- test_e2e_open_reconnect_warns_when_display_flag_ignored
- test_e2e_agent_list_prunes_terminal_tasks
- test_e2e_crawl_foreground
- test_e2e_crawl_foreground_no_links_discovered
- test_e2e_snapshot_grep
- test_e2e_snapshot_grep_flags
- test_e2e_snapshot_grep_unicode
- test_e2e_htmlsnapshot_query_table_format

## Error Details

══ block 1 ══
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5308517Z  * [new branch]            fix/extension-external-browser-id -> origin/fix/extension-external-browser-id
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5309272Z  * [new branch]            fix/headless-stealth-consistency -> origin/fix/headless-stealth-consistency
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5310006Z  * [new branch]            fix/nightly-cli-e2e-failures -> origin/fix/nightly-cli-e2e-failures
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5310663Z  * [new branch]            fix/ps1-m6-crawl-cap    -> origin/fix/ps1-m6-crawl-cap
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5311413Z  * [new branch]            fix/smoke-paths-and-storage-state-test -> origin/fix/smoke-paths-and-storage-state-test
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5312359Z  * [new branch]            fix/stealth-pointer-jitter-and-page-hygiene -> origin/fix/stealth-pointer-jitter-and-page-hygiene

══ block 2 ══
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5333016Z  * [new branch]            worktree-experience-workflow -> origin/worktree-experience-workflow
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5333786Z  * [new branch]            worktree-fingerprint-loader-tests -> origin/worktree-fingerprint-loader-tests
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5334740Z  * [new branch]            worktree-fix-7-e2e-test-failures -> origin/worktree-fix-7-e2e-test-failures
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5335510Z  * [new branch]            worktree-fix-all-plugin-issues -> origin/worktree-fix-all-plugin-issues
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5336205Z  * [new branch]            worktree-fix-build-ps51 -> origin/worktree-fix-build-ps51
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5336917Z  * [new branch]            worktree-fix-chat-e2e-coverage -> origin/worktree-fix-chat-e2e-coverage

══ block 3 ══
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5342102Z  * [new branch]            worktree-fix-daily-maintenance-workflow -> origin/worktree-fix-daily-maintenance-workflow
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5343224Z  * [new branch]            worktree-fix-dsh-ps1-resolution -> origin/worktree-fix-dsh-ps1-resolution
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5344834Z  * [new branch]            worktree-fix-e2e-test-failures -> origin/worktree-fix-e2e-test-failures
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5345679Z  * [new branch]            worktree-fix-edit-file-in-editor -> origin/worktree-fix-edit-file-in-editor
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5346499Z  * [new branch]            worktree-fix-editor-startprocess -> origin/worktree-fix-editor-startprocess
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5347322Z  * [new branch]            worktree-fix-goto-enter-runs -> origin/worktree-fix-goto-enter-runs

══ block 4 ══
Build core artifacts and Docker image	Checkout repository	2026-10-05T15:25:36.5381395Z  * [new branch]            worktree-merge-3-prs-to-412 -> origin/worktree-merge-3-prs-to

... (truncated — run gh run view 37332660300 --log-failed for full logs)

## Investigation Hints (E2E test failures)

- E2E tests live in `cli/browser4-cli/tests/e2e/scenarios/`
- Test registration is in `cli/browser4-cli/tests/e2e/scenarios/mod.rs`
- The fixture HTTP server is in the Rust test harness
- These tests run against a real Browser4 backend; backend changes can break them
- Check if fixture server port resolution, HTML fixtures, or state verification changed
- Key files (per CLAUDE.md): `PulsarWebDriver.kt`, `MCPToolController.kt`, `commands.rs`, `main.rs`
- Run locally: `cd cli/browser4-cli && cargo test --test e2e -- --nocapture`
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
