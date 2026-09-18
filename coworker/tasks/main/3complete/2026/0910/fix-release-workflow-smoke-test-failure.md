Title: Fix release.yml failure for tag v4.13.17
Description: The release.yml workflow run 34439006612 (tag v4.13.17) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 34439006612) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.13.17
- **Run ID:** 34439006612
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/34439006612


## Failed Jobs

- **Smoke test (macOS ARM64)** (job ID: 102756495195)
- **Smoke test (Windows x64)** (job ID: 102756495220)
- **Smoke test (Linux x64)** (job ID: 102756495107)

## Release Changelog

```text
Commits since v4.14.6-ci.4:
8eabd1acf2 feat(agentic,rest,cli): backport weibo2x lessons plan v2 fixes from 4.14.x 89a2dafcb3 docs: update quick start instructions in README files f4f151df6d Auto-bump version to 4.13.17-SNAPSHOT e0603f4973 chore(extension): bump extension version to 0.2.2 for release 5a5e38eee8 fix(rest,cli): keep extension sessions connected - ws size limit, rebind, auto re-attach f2c4fce678 fix(extension): stabilize relay connection, keep sessions alive and never grab user tabs 2a7f2f404f Auto-bump version to 4.13.16-SNAPSHOT b20ab33a26 Auto-bump version to 4.13.15-SNAPSHOT 6b2d232b13 chore(deps): bump browser4-base to 4.11.16 (external browser identity API) b12d236b45 refactor(session,browser): use the new pure external browser identity for attached browsers a315804781 fix(rest,skeleton): bind session drivers to the active page tab after attach/reconnect 1ab2ae0a46 fix(test-production): evaluate ~/.local/bin standalone path only on Unix, not Windows 9bab034609 docs(skills): open a headed browser when user interaction (login/CAPTCHA) is required 987bf9aba9 fix(cli,backend): resolve 2026-09-05 eval issues across crawl, htmlsnapshot, swarm and x-sql 0a36d906f4 Auto-bump version to 4.13.14-SNAPSHOT f20069d750 chore(coworker): archive resolved v4.13.13 release-failure task f99d678ec1 fix(bundle): don't pre-create the jlink output directory f0ae9ac334 docs: align snapshot -i tip and quick-patterns table with interactive-rendering semantics 05c21189d4 test(e2e): real-browser regressions for cookie path scoping and tab-switch capture 6d0801e4ba refactor(rest): use kotlin.time Duration for crawl timeouts 4ef3c78c77 feat(backend): htmlsnapshot query reads the live DOM when targeting the current page 67bac537c1 fix(bundle): rename-then-delete directory reset for idempotent runtime rebuilds c54d2048d8 docs: refresh CLI skill docs, readmes, mvn wrapper docs 0906407f39 feat(backend): live-DOM htmlsnapshot, crawl depth/dedup/sort, cookie validation, extract envelope 3b8613d78e feat(cli): eval/cookie/select/get/extract UX, bundle rebuild health, wrapper fixes 71e826c076 chore(coworker): process 1ready batch, archive session doc a0d2c76879 fix(coworker): align GUI issue-review reading with coworker review f01fc26f52 fix(coworker): delegate b4w coworker start to start.ps1 and track background PIDs 81f6bd7d5d feat: extend cloc.ps1 to count PowerShell, Bash, JavaScript, and Rust fc3ebafd69 Auto-bump version to 4.13.13-SNAPSHOT 2b5b3581da feat(release): support non-interactive release triggering via BROWSER4_RELEASE_ASSUME_YES 1ce21f3cb3 feat(release): allow maintenance-branch (X.Y.x) releases alongside main 41ebd295ac fix(cli): attach --extension uses published extension ID and opens connect page in Edge for msedge channel 8948dfb204 test(session): cover unified session id resolution and named context dirs e5d994da70 fix(session): unify session id resolution, lazily create named context dirs 06a0acc7e5 fix(session): named sessions bind a dedicated chrome user data dir 763036b6bb chore(deps): bump browser4-base to 4.11.10 e19e0b3c22 fix(test): stop mock EC server stale-port skips from breaking later test classes 0d929fa51b fix(ci): point nightly webminer tests at renamed browser4-web-miner skill path ba17a75369 chore: update browser4-base version to 4.11.8 in pom.xml f23e359b18 ci(release): tag releases from main instead of force-syncing main to tag c4ea59b4d1 Auto-bump version to 4.13.12-SNAPSHOT a6e7cb9b51 fix(cli): harden browser4-cli self-upgrade and isolate it in tests c0d071ce79 fix(browser): never launch an extra headed browser for headless sessions 0e1b7870ca Auto-bump version to 4.13.11-SNAPSHOT d9de5cb486 refactor: move driver behavior from BrowserTabToolExecutor into Browser4WebDriver 011f4e3a49 fix: harden drag against occlusion, frames and layout shifts bb2c1dcafe Merge remote-tracking branch 'origin/main' into 4.13.x 5eaa366eb8 refactor(skills): rename web-miner skill to browser4-web-miner a92411d85f fix: browser automation hardening — drag, dialog, select, cookie domain, export alias, X-SQL hint (#576) 7b54748ef1 docs(skills): move SKILL methodology doc under the browser4-cli skill bd00758f25 feat(cli): add webminer command running the web-miner skill natively a7101b7503 Auto-bump version to 4.13.10-SNAPSHOT 8b22bc9691 ci: give the browser4-test container 1g /dev/shm for headless Chrome 7125b0560d docs(coworker): archive CI v4.13.9-ci.3 failure task as resolved ec31ed84d1 fix(core): port robust storage-state restore to Browser4WebDriver 1116207e8b fix(executor): don't stack a second full-timeout body wait after the readyState poll dadf281267 refactor(executor): remove unused NAVIGATION_TRIGGERING_ACTIONS constant 421497620f fix(executor): waitForNavigation tool and LoginHandler poll readyState instead of no-op waits 7c000b6743 fix(executor): poll document.readyState in navigate/open instead of no-op waitForNavigation 226e24adbf fix(executor): poll document.readyState instead of waitForNavigation for same-URL navigations b222e74591 fix(cli): give state-load the navigation timeout budget; collect container logs in CI 15bc0e4ed8 fix(swarm): don't fail scrape tasks served from the WebDB cache 2cc7ae7222 Merge remote-tracking branch 'origin/main' into 4.13.x c0da22f658 fix(swarm): guarantee task termination and bounded retries (#577) (#578) cd5da38a27 docs(ci): note that fork PR gate runs require maintainer approval (#579) f40cbda552 Add badge row to README 0bb1062042 Merge remote-tracking branch 'origin/main' into 4.13.x 6da158d80f Format friend links in README.zh.md cf8c42f54d Update README.md cbff09c9c8 Fix formatting of friend links in README 9d7e7fce8c Add links section with related project references eb2e5213dd Add friend links to README.zh.md 0e0aeaaae1 Add DeepSeek Harness integration to README b5ff0021ce Add DeepSeek Harness integration to README b6708744d2 Update README.zh.md c18e284d2c add weixin a788a0e36b add weixin 722d27a540 add weixin 0feb4bd0af add weixin ba076d7c08 Auto-bump version to 4.13.9-SNAPSHOT ded14e9873 feat(cli): sync bundled skills into ~/.agents/skills on install/upgrade 8be4d49cd4 Auto-bump version to 4.13.8-SNAPSHOT e7c6d7dcbf feat(session): upgrade to pulsar 4.11.5 and recover lost driver links in place 1b4fde0094 fix(session): attach sessions must reuse the bound browser and never be silently recreated 3ebcf49baa fix(session): recover lost driver link on the same browser before recreating the session ce36cdb2a5 Auto-bump version to 4.13.7-SNAPSHOT 1219dbd0f7 fix(cli): diagnose headed launch failures via Browser4-managed chrome only
```

## Reproduce

`ash
# View all failed logs
gh run view 34439006612 --log-failed

# View the run in browser
gh run view 34439006612 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:28:58.2765550Z ##[group]Run if ! command -v rustup &>/dev/null; then
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:28:58.2765890Z [36;1mif ! command -v rustup &>/dev/null; then[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:28:58.2766590Z [36;1m  curl --proto '=https' --tlsv1.2 --retry 10 --retry-connrefused --location --silent --show-error --fail https://sh.rustup.rs | sh -s -- --default-toolchain none -y[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:28:58.2767210Z [36;1m  echo "$CARGO_HOME/bin" >> $GITHUB_PATH[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:28:58.2767480Z [36;1mfi[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:28:58.2820010Z shell: /bin/bash --noprofile --norc -e -o pipefail {0}

══ block 2 ══
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0016000Z ##[end-action id=__dtolnay_rust-toolchain.__run_8;outcome=success;conclusion=success;duration_ms=79]
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0018500Z ##[start-action display=Work around spurious network errors in curl 8.0;id=__dtolnay_rust-toolchain.__run_9]
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0034900Z ##[group]Run # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0035550Z [36;1m# https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0036080Z [36;1mif rustc +stable --version --verbose | grep -q '^release: 1\.7[01]\.'; then[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0036480Z [36;1m  echo CARGO_HTTP_MULTIPLEXING=false >> $GITHUB_ENV[0m

══ block 3 ══
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0018500Z ##[start-action display=Work around spurious network errors in curl 8.0;id=__dtolnay_rust-toolchain.__run_9]
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0034900Z ##[group]Run # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0035550Z [36;1m# https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0036080Z [36;1mif rustc +stable --version --verbose | grep -q '^release: 1\.7[01]\.'; then[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0036480Z [36;1m  echo CARGO_HTTP_MULTIPLEXING=false >> $GITHUB_ENV[0m
Smoke test (macOS ARM64)	Setup Rust toolchain	2026-09-10T05:29:05.0036740Z [36;1mfi[0m

══ block 4 ══
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1098450Z      [1m[94m= [0m[1mnote[0m: `#[warn(unused_variables)]` (part of `#[warn(unused)]`) on by default
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1200390Z 
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1302030Z [1m[33mwarning[0m[1m: unused variable: `timeout`[0m
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1402160Z     [1m[94m--> [0msrc/managed_processes.rs:1163:9
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1503560Z      [1m[94m|[0m
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1604760Z [1m[94m1163[0m [1m[94m|[0m     let timeout = std::time::Duration::from_millis(timeout_ms);

══ block 5 ══
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1402160Z     [1m[94m--> [0msrc/managed_processes.rs:1163:9
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1503560Z      [1m[94m|[0m
Smoke test (macOS ARM64)	Build CLI binary	2026-09-10T05:30:31.1604760Z [1m[94m1163[0m [1m[94m|[0m     let timeout = std

... (truncated — run gh run view 34439006612 --log-failed for full logs)

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

## Resolution

**Outcome:** fixed in 0f2a3a292f (`fix(ci): serve the smoke-test fixture page from its own temp dir`).

**Categorisation:** CI-harness bug (not a product regression, not a flake).

**Root cause:** `cli/scripts/smoke-test-runtime-bundle.sh` started
`python3 -m http.server` without `--directory`, so it served the CI workspace root
instead of `$TEMP_DIR` where the fixture `test.html` is written. Every smoke run
browsed Python's 404 page ("Error code: 404 - Nothing matches the given URI." — the
page title/snapshot in the failure log). Earlier 4.13.x backends tolerated `type`
on a missing element, so the suite passed anyway (see the green 4.13.x run
34321679775, "SMOKE TEST PASSED (11/11 steps OK)"); the weibo2x backport
(8eabd1acf2) makes `type` fail loudly on a missing selector, which turned the
harness bug into three red jobs on v4.13.17.

**Fix:** backport of 5bfb03dade (4.14.x) — start the server with
`--directory "$TEMP_DIR"` and make the readiness probe `curl -sf`, so a server
serving the wrong directory aborts with a clear FATAL message instead of silently
handing the browser a 404 page.

**Verification:** locally, the old invocation returns the exact 404 body from CI and
`curl -sf` exits 22, the new invocation serves the fixture (title "Smoke Test",
`#input1` present) and `curl -sf` exits 0; the full script driven against a stubbed
CLI reaches all 11 steps and exits 0. Upstream evidence: the same change made run
34415858047 (v4.14.0-rc.5) green on the identical Linux/macOS/Windows matrix.

**Deliberately not backported** (upstream 4.14.x harness hardening, not required on
4.13.x today): d4faa02046 (best-effort EXIT-trap cleanup on Windows) — the 09-09
4.13.x Windows smoke job passed 11/11 with this same cleanup code, so the
"Device or resource busy" false red is not manifesting here; 52cc0a3915
(`BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1`) — its failure mode (local Maven rebuild of
`browser4-bundle` failing on the runners) does not occur on 4.13.x, where the local
bundle build succeeds (`Building local Browser4 runtime bundle ...` in the failing
run). Note that on 4.13.x the smoke test therefore exercises the locally rebuilt
bundle rather than the downloaded archive; that is a separate test-validity gap.
