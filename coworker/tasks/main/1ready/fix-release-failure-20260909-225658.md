Title: Fix release.yml failure for tag v4.14.0-rc.5
Description: The release.yml workflow run 34410301097 (tag v4.14.0-rc.5) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 34410301097) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.14.0-rc.5
- **Run ID:** 34410301097
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/34410301097


## Failed Jobs

- **Smoke test (macOS ARM64)** (job ID: 102674960463)
- **Smoke test (Windows x64)** (job ID: 102674960493)
- **Smoke test (Linux x64)** (job ID: 102674960475)

## Release Changelog

```text
Commits since v4.14.0-rc.4:
fa2d9769ac chore(cli): sync CLI version to 4.14.0-rc.5 0be36cb88c Bump version to v4.14.0-rc.5 d97bebe587 Merge origin/4.13.x into 4.14.x: extension session relay fixes + external browser identity + version bumps 15e6ea1b25 feat(agentic,rest,cli): implement weibo2x lessons plan v2 fixes 947f6b4f35 fix(extension,cli): make agent-driven attach --extension work against locally loaded Edge extension 34f0c2cf2b Merge origin/4.13.x into 4.14.x: extension attach binding fix + eval fixes + docs 8a149844e5 feat(agentic): harden cli tool loop with per-call timeout and round digest 89a2dafcb3 docs: update quick start instructions in README files f4f151df6d Auto-bump version to 4.13.17-SNAPSHOT e0603f4973 chore(extension): bump extension version to 0.2.2 for release 5a5e38eee8 fix(rest,cli): keep extension sessions connected - ws size limit, rebind, auto re-attach f2c4fce678 fix(extension): stabilize relay connection, keep sessions alive and never grab user tabs 2a7f2f404f Auto-bump version to 4.13.16-SNAPSHOT b20ab33a26 Auto-bump version to 4.13.15-SNAPSHOT 6b2d232b13 chore(deps): bump browser4-base to 4.11.16 (external browser identity API) b12d236b45 refactor(session,browser): use the new pure external browser identity for attached browsers a315804781 fix(rest,skeleton): bind session drivers to the active page tab after attach/reconnect 1ab2ae0a46 fix(test-production): evaluate ~/.local/bin standalone path only on Unix, not Windows 9bab034609 docs(skills): open a headed browser when user interaction (login/CAPTCHA) is required 987bf9aba9 fix(cli,backend): resolve 2026-09-05 eval issues across crawl, htmlsnapshot, swarm and x-sql 0a36d906f4 Auto-bump version to 4.13.14-SNAPSHOT f20069d750 chore(coworker): archive resolved v4.13.13 release-failure task f99d678ec1 fix(bundle): don't pre-create the jlink output directory
```

## Reproduce

`ash
# View all failed logs
gh run view 34410301097 --log-failed

# View the run in browser
gh run view 34410301097 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:06.3958420Z ##[group]Run if ! command -v rustup &>/dev/null; then
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:06.3958740Z if ! command -v rustup &>/dev/null; then
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:06.3959340Z   curl --proto '=https' --tlsv1.2 --retry 10 --retry-connrefused --location --silent --show-error --fail https://sh.rustup.rs | sh -s -- --default-toolchain none -y
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:06.3959920Z   echo "$CARGO_HOME/bin" >> $GITHUB_PATH
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:06.3960130Z fi
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:06.4020580Z shell: /bin/bash --noprofile --norc -e -o pipefail {0}

══ block 2 ══
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0417210Z ##[end-action id=__dtolnay_rust-toolchain.__run_8;outcome=success;conclusion=success;duration_ms=123]
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0419960Z ##[start-action display=Work around spurious network errors in curl 8.0;id=__dtolnay_rust-toolchain.__run_9]
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0436860Z ##[group]Run # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0437590Z # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0438180Z if rustc +stable --version --verbose | grep -q '^release: 1\.7[01]\.'; then
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0438660Z   echo CARGO_HTTP_MULTIPLEXING=false >> $GITHUB_ENV

══ block 3 ══
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0419960Z ##[start-action display=Work around spurious network errors in curl 8.0;id=__dtolnay_rust-toolchain.__run_9]
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0436860Z ##[group]Run # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0437590Z # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0438180Z if rustc +stable --version --verbose | grep -q '^release: 1\.7[01]\.'; then
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0438660Z   echo CARGO_HTTP_MULTIPLEXING=false >> $GITHUB_ENV
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-09T22:50:14.0438990Z fi

══ block 4 ══
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:44.9577180Z      = note: `#[warn(unused_variables)]` (part of `#[warn(unused)]`) on by default
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:44.9679000Z 
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:44.9780280Z warning: unused variable: `timeout`
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:44.9888170Z     --> src/managed_processes.rs:1359:9
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:44.9989700Z      |
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:45.0092820Z 1359 |     let timeout = std::time::Duration::from_millis(timeout_ms);

══ block 5 ══
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:44.9888170Z     --> src/managed_processes.rs:1359:9
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:44.9989700Z      |
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:45.0092820Z 1359 |     let timeout = std::time::Duration::from_millis(timeout_ms);
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51:45.0194850Z      |         ^^^^^^^ help: if this is intentional, prefix it with an underscore: `_timeout`
Smoke test (macOS ARM64)	Build CLI binary	2026-09-09T22:51

... (truncated — run gh run view 34410301097 --log-failed for full logs)

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
