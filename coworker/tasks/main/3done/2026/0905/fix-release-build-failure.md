Title: Fix release.yml failure for tag v4.13.13
Description: The release.yml workflow run 33959789469 (tag v4.13.13) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 33959789469) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.13.13
- **Run ID:** 33959789469
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/33959789469


## Failed Jobs

- **Build runtime bundle (macOS ARM64)** (job ID: 101292190577)
- **Build runtime bundle (Windows x64)** (job ID: 101292190586)
- **Build runtime bundle (Linux x64)** (job ID: 101292190575)

## Release Changelog

```text
Commits since v4.14.6-ci.4:
f0ae9ac334 docs: align snapshot -i tip and quick-patterns table with interactive-rendering semantics 05c21189d4 test(e2e): real-browser regressions for cookie path scoping and tab-switch capture 6d0801e4ba refactor(rest): use kotlin.time Duration for crawl timeouts 4ef3c78c77 feat(backend): htmlsnapshot query reads the live DOM when targeting the current page 67bac537c1 fix(bundle): rename-then-delete directory reset for idempotent runtime rebuilds c54d2048d8 docs: refresh CLI skill docs, readmes, mvn wrapper docs 0906407f39 feat(backend): live-DOM htmlsnapshot, crawl depth/dedup/sort, cookie validation, extract envelope 3b8613d78e feat(cli): eval/cookie/select/get/extract UX, bundle rebuild health, wrapper fixes 71e826c076 chore(coworker): process 1ready batch, archive session doc a0d2c76879 fix(coworker): align GUI issue-review reading with coworker review f01fc26f52 fix(coworker): delegate b4w coworker start to start.ps1 and track background PIDs 81f6bd7d5d feat: extend cloc.ps1 to count PowerShell, Bash, JavaScript, and Rust fc3ebafd69 Auto-bump version to 4.13.13-SNAPSHOT 2b5b3581da feat(release): support non-interactive release triggering via BROWSER4_RELEASE_ASSUME_YES 1ce21f3cb3 feat(release): allow maintenance-branch (X.Y.x) releases alongside main 41ebd295ac fix(cli): attach --extension uses published extension ID and opens connect page in Edge for msedge channel 8948dfb204 test(session): cover unified session id resolution and named context dirs e5d994da70 fix(session): unify session id resolution, lazily create named context dirs 06a0acc7e5 fix(session): named sessions bind a dedicated chrome user data dir 763036b6bb chore(deps): bump browser4-base to 4.11.10 e19e0b3c22 fix(test): stop mock EC server stale-port skips from breaking later test classes 0d929fa51b fix(ci): point nightly webminer tests at renamed browser4-web-miner skill path ba17a75369 chore: update browser4-base version to 4.11.8 in pom.xml f23e359b18 ci(release): tag releases from main instead of force-syncing main to tag c4ea59b4d1 Auto-bump version to 4.13.12-SNAPSHOT a6e7cb9b51 fix(cli): harden browser4-cli self-upgrade and isolate it in tests c0d071ce79 fix(browser): never launch an extra headed browser for headless sessions 0e1b7870ca Auto-bump version to 4.13.11-SNAPSHOT d9de5cb486 refactor: move driver behavior from BrowserTabToolExecutor into Browser4WebDriver 011f4e3a49 fix: harden drag against occlusion, frames and layout shifts bb2c1dcafe Merge remote-tracking branch 'origin/main' into 4.13.x 5eaa366eb8 refactor(skills): rename web-miner skill to browser4-web-miner a92411d85f fix: browser automation hardening — drag, dialog, select, cookie domain, export alias, X-SQL hint (#576) 7b54748ef1 docs(skills): move SKILL methodology doc under the browser4-cli skill bd00758f25 feat(cli): add webminer command running the web-miner skill natively a7101b7503 Auto-bump version to 4.13.10-SNAPSHOT 8b22bc9691 ci: give the browser4-test container 1g /dev/shm for headless Chrome 7125b0560d docs(coworker): archive CI v4.13.9-ci.3 failure task as resolved ec31ed84d1 fix(core): port robust storage-state restore to Browser4WebDriver 1116207e8b fix(executor): don't stack a second full-timeout body wait after the readyState poll dadf281267 refactor(executor): remove unused NAVIGATION_TRIGGERING_ACTIONS constant 421497620f fix(executor): waitForNavigation tool and LoginHandler poll readyState instead of no-op waits 7c000b6743 fix(executor): poll document.readyState in navigate/open instead of no-op waitForNavigation 226e24adbf fix(executor): poll document.readyState instead of waitForNavigation for same-URL navigations b222e74591 fix(cli): give state-load the navigation timeout budget; collect container logs in CI 15bc0e4ed8 fix(swarm): don't fail scrape tasks served from the WebDB cache 2cc7ae7222 Merge remote-tracking branch 'origin/main' into 4.13.x c0da22f658 fix(swarm): guarantee task termination and bounded retries (#577) (#578) cd5da38a27 docs(ci): note that fork PR gate runs require maintainer approval (#579) f40cbda552 Add badge row to README 0bb1062042 Merge remote-tracking branch 'origin/main' into 4.13.x 6da158d80f Format friend links in README.zh.md cf8c42f54d Update README.md cbff09c9c8 Fix formatting of friend links in README 9d7e7fce8c Add links section with related project references eb2e5213dd Add friend links to README.zh.md 0e0aeaaae1 Add DeepSeek Harness integration to README b5ff0021ce Add DeepSeek Harness integration to README b6708744d2 Update README.zh.md c18e284d2c add weixin a788a0e36b add weixin 722d27a540 add weixin 0feb4bd0af add weixin ba076d7c08 Auto-bump version to 4.13.9-SNAPSHOT ded14e9873 feat(cli): sync bundled skills into ~/.agents/skills on install/upgrade 8be4d49cd4 Auto-bump version to 4.13.8-SNAPSHOT e7c6d7dcbf feat(session): upgrade to pulsar 4.11.5 and recover lost driver links in place 1b4fde0094 fix(session): attach sessions must reuse the bound browser and never be silently recreated 3ebcf49baa fix(session): recover lost driver link on the same browser before recreating the session ce36cdb2a5 Auto-bump version to 4.13.7-SNAPSHOT 1219dbd0f7 fix(cli): diagnose headed launch failures via Browser4-managed chrome only
```

## Reproduce

`ash
# View all failed logs
gh run view 33959789469 --log-failed

# View the run in browser
gh run view 33959789469 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2860770Z  * [new branch]            worktree-experience-workflow -> origin/worktree-experience-workflow
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2862580Z  * [new branch]            worktree-fingerprint-loader-tests -> origin/worktree-fingerprint-loader-tests
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2864490Z  * [new branch]            worktree-fix-7-e2e-test-failures -> origin/worktree-fix-7-e2e-test-failures
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2866300Z  * [new branch]            worktree-fix-all-plugin-issues -> origin/worktree-fix-all-plugin-issues
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2868260Z  * [new branch]            worktree-fix-build-ps51 -> origin/worktree-fix-build-ps51
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2869980Z  * [new branch]            worktree-fix-chat-e2e-coverage -> origin/worktree-fix-chat-e2e-coverage

══ block 2 ══
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2881770Z  * [new branch]            worktree-fix-daily-maintenance-workflow -> origin/worktree-fix-daily-maintenance-workflow
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2883770Z  * [new branch]            worktree-fix-dsh-ps1-resolution -> origin/worktree-fix-dsh-ps1-resolution
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2885620Z  * [new branch]            worktree-fix-e2e-test-failures -> origin/worktree-fix-e2e-test-failures
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2887370Z  * [new branch]            worktree-fix-edit-file-in-editor -> origin/worktree-fix-edit-file-in-editor
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2889400Z  * [new branch]            worktree-fix-editor-startprocess -> origin/worktree-fix-editor-startprocess
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2891140Z  * [new branch]            worktree-fix-goto-enter-runs -> origin/worktree-fix-goto-enter-runs

══ block 3 ══
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2947210Z  * [new branch]            worktree-merge-3-prs-to-412 -> origin/worktree-merge-3-prs-to-412
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2948900Z  * [new branch]            worktree-merge-prs-ci-threshold -> origin/worktree-merge-prs-ci-threshold
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2951050Z  * [new branch]            worktree-monitor-workflow-failure-handler -> origin/worktree-monitor-workflow-failure-handler
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2953200Z  * [new branch]            worktree-mouse-keyboard-test-pages -> origin/worktree-mouse-keyboard-test-pages
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2955320Z  * [new branch]            worktree-optimize-backend-startup -> origin/worktree-optimize-backend-startup
Build runtime bundle (macOS ARM64)	Checkout repository	2026-09-05T10:29:30.2957160Z  * [new branch]            worktree-perf-timing-inspect-summary -> origin/worktree-perf-timing-inspect-summary

══ block 4 ══
Build runtime bundle (macOS ARM64)	Build Browser4 runtime bundle	2026-09-05T10:31:47.9441510Z Running jlink with modules: java.base,java.compiler,java.desktop,java.instrument,java.naming,java.net.http,java.prefs,java.scripting,java.security.jgss,java.security.sasl,java.sql,jdk.net,jdk.unsupported,java.management,jdk.crypto.ec
Build runtime bundle (macOS ARM64)	Build Browser4 runtime bundle	2026-09-05T10:31:47.9442810Z Using jlink compression mode: zip-9
Build runtime bundle (macOS ARM64)	Build Browser4 runtime bundle	2026-09-05T10:31:48.0699470Z Error: directory 

... (truncated — run gh run view 33959789469 --log-failed for full logs)

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
