Title: Fix release.yml failure for tag v4.13.18
Description: The release.yml workflow run 34706653079 (tag v4.13.18) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 34706653079) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.13.18
- **Run ID:** 34706653079
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/34706653079


## Failed Jobs

- **Publish browser4-cli to npm** (job ID: 103590794217)

## Release Changelog

```text
Commits since v4.14.6-ci.4:
194a2c3336 docs(ci): ci.6 is green - record the decisive evidence c7197316b3 docs(ci): why the monitor-ci extraction fix is deferred (ps1 tests run on main only) 1ef6101244 docs(ci): tidy the report order and add the independent pool-fix re-check f3c1a6202c docs(ci): verify the driver-pool fix independently and record follow-ups 26607ceeff docs(ci): ci.5 flaky triage, local-vs-CI count delta, monitor-ci note f587ee1862 fix(coworker): task update f8dccd2bda chore(coworker): archive resolved v4.13.18-ci.5 CI tag-failure task 2671e1574d fix(protocol): keep polling for a driver when the resource guard refuses 07b6019ace docs(ci): record the ci.4 verification and the per-class re-checks 39779e079c ci: document that excluded_groups replaces the pom default efc650490a ci: make the test verdict honest and enumerate failures in one run 2a275f8b73 fix(test): normalise the inherited run directory to an absolute path f6886ed907 chore(coworker): archive resolved v4.13.18-ci.2 CI tag-failure task 4d0af69110 fix(protocol): accept local-file fetches in the snapshot origin guard 2a0ff2bf5a feat(test): give every test run its own .test-sessions subdirectory 7efa9b4565 chore(coworker): archive resolved v4.13.18 CI tag-failure task cae4042735 fix(test): deserialize crawl poll responses with Kotlin-aware Jackson fc4b6f9ddf test(extension): repair ExtensionWebSocketHandlerTest against the current handler 992c911fe8 feat(swarm): batch-aware status, MCP batch tools, and a truthful isDone signal d924559079 fix(swarm): batch ids, truthful completion tracking, no more hung CLI calls 950ab1776d fix(htmlsnapshot): annotate captured HTML with vi boxes and the page URL da5772cc72 test(tests-production): add named-session and attach/close verification scripts 483f372d10 fix(tests-production): resolve the CLI exe behind npm .cmd shims without aborting the run bd18d2b7d8 fix(release,ci): match only the run the tag push triggered 0125299de7 Auto-bump version to 4.13.18-SNAPSHOT 5935e913c4 chore(coworker): close redundant v4.13.17 release-failure task af3de3578e chore(coworker): archive resolved v4.13.17 smoke-test failure task 0f2a3a292f fix(ci): serve the smoke-test fixture page from its own temp dir 6be3365514 docs(skills): add browser-modes reference and document SUPERVISED mode 8eabd1acf2 feat(agentic,rest,cli): backport weibo2x lessons plan v2 fixes from 4.14.x 89a2dafcb3 docs: update quick start instructions in README files f4f151df6d Auto-bump version to 4.13.17-SNAPSHOT e0603f4973 chore(extension): bump extension version to 0.2.2 for release 5a5e38eee8 fix(rest,cli): keep extension sessions connected - ws size limit, rebind, auto re-attach f2c4fce678 fix(extension): stabilize relay connection, keep sessions alive and never grab user tabs 2a7f2f404f Auto-bump version to 4.13.16-SNAPSHOT b20ab33a26 Auto-bump version to 4.13.15-SNAPSHOT 6b2d232b13 chore(deps): bump browser4-base to 4.11.16 (external browser identity API) b12d236b45 refactor(session,browser): use the new pure external browser identity for attached browsers a315804781 fix(rest,skeleton): bind session drivers to the active page tab after attach/reconnect 1ab2ae0a46 fix(test-production): evaluate ~/.local/bin standalone path only on Unix, not Windows 9bab034609 docs(skills): open a headed browser when user interaction (login/CAPTCHA) is required 987bf9aba9 fix(cli,backend): resolve 2026-09-05 eval issues across crawl, htmlsnapshot, swarm and x-sql 0a36d906f4 Auto-bump version to 4.13.14-SNAPSHOT f20069d750 chore(coworker): archive resolved v4.13.13 release-failure task f99d678ec1 fix(bundle): don't pre-create the jlink output directory f0ae9ac334 docs: align snapshot -i tip and quick-patterns table with interactive-rendering semantics 05c21189d4 test(e2e): real-browser regressions for cookie path scoping and tab-switch capture 6d0801e4ba refactor(rest): use kotlin.time Duration for crawl timeouts 4ef3c78c77 feat(backend): htmlsnapshot query reads the live DOM when targeting the current page 67bac537c1 fix(bundle): rename-then-delete directory reset for idempotent runtime rebuilds c54d2048d8 docs: refresh CLI skill docs, readmes, mvn wrapper docs 0906407f39 feat(backend): live-DOM htmlsnapshot, crawl depth/dedup/sort, cookie validation, extract envelope 3b8613d78e feat(cli): eval/cookie/select/get/extract UX, bundle rebuild health, wrapper fixes 71e826c076 chore(coworker): process 1ready batch, archive session doc a0d2c76879 fix(coworker): align GUI issue-review reading with coworker review f01fc26f52 fix(coworker): delegate b4w coworker start to start.ps1 and track background PIDs 81f6bd7d5d feat: extend cloc.ps1 to count PowerShell, Bash, JavaScript, and Rust fc3ebafd69 Auto-bump version to 4.13.13-SNAPSHOT 2b5b3581da feat(release): support non-interactive release triggering via BROWSER4_RELEASE_ASSUME_YES 1ce21f3cb3 feat(release): allow maintenance-branch (X.Y.x) releases alongside main 41ebd295ac fix(cli): attach --extension uses published extension ID and opens connect page in Edge for msedge channel 8948dfb204 test(session): cover unified session id resolution and named context dirs e5d994da70 fix(session): unify session id resolution, lazily create named context dirs 06a0acc7e5 fix(session): named sessions bind a dedicated chrome user data dir 763036b6bb chore(deps): bump browser4-base to 4.11.10 e19e0b3c22 fix(test): stop mock EC server stale-port skips from breaking later test classes 0d929fa51b fix(ci): point nightly webminer tests at renamed browser4-web-miner skill path ba17a75369 chore: update browser4-base version to 4.11.8 in pom.xml f23e359b18 ci(release): tag releases from main instead of force-syncing main to tag c4ea59b4d1 Auto-bump version to 4.13.12-SNAPSHOT a6e7cb9b51 fix(cli): harden browser4-cli self-upgrade and isolate it in tests c0d071ce79 fix(browser): never launch an extra headed browser for headless sessions 0e1b7870ca Auto-bump version to 4.13.11-SNAPSHOT d9de5cb486 refactor: move driver behavior from BrowserTabToolExecutor into Browser4WebDriver 011f4e3a49 fix: harden drag against occlusion, frames and layout shifts bb2c1dcafe Merge remote-tracking branch 'origin/main' into 4.13.x 5eaa366eb8 refactor(skills): rename web-miner skill to browser4-web-miner a92411d85f fix: browser automation hardening — drag, dialog, select, cookie domain, export alias, X-SQL hint (#576) 7b54748ef1 docs(skills): move SKILL methodology doc under the browser4-cli skill bd00758f25 feat(cli): add webminer command running the web-miner skill natively a7101b7503 Auto-bump version to 4.13.10-SNAPSHOT 8b22bc9691 ci: give the browser4-test container 1g /dev/shm for headless Chrome 7125b0560d docs(coworker): archive CI v4.13.9-ci.3 failure task as resolved ec31ed84d1 fix(core): port robust storage-state restore to Browser4WebDriver 1116207e8b fix(executor): don't stack a second full-timeout body wait after the readyState poll dadf281267 refactor(executor): remove unused NAVIGATION_TRIGGERING_ACTIONS constant 421497620f fix(executor): waitForNavigation tool and LoginHandler poll readyState instead of no-op waits 7c000b6743 fix(executor): poll document.readyState in navigate/open instead of no-op waitForNavigation 226e24adbf fix(executor): poll document.readyState instead of waitForNavigation for same-URL navigations b222e74591 fix(cli): give state-load the navigation timeout budget; collect container logs in CI 15bc0e4ed8 fix(swarm): don't fail scrape tasks served from the WebDB cache 2cc7ae7222 Merge remote-tracking branch 'origin/main' into 4.13.x c0da22f658 fix(swarm): guarantee task termination and bounded retries (#577) (#578) cd5da38a27 docs(ci): note that fork PR gate runs require maintainer approval (#579) f40cbda552 Add badge row to README 0bb1062042 Merge remote-tracking branch 'origin/main' into 4.13.x 6da158d80f Format friend links in README.zh.md cf8c42f54d Update README.md cbff09c9c8 Fix formatting of friend links in README 9d7e7fce8c Add links section with related project references eb2e5213dd Add friend links to README.zh.md 0e0aeaaae1 Add DeepSeek Harness integration to README b5ff0021ce Add DeepSeek Harness integration to README b6708744d2 Update README.zh.md c18e284d2c add weixin a788a0e36b add weixin 722d27a540 add weixin 0feb4bd0af add weixin ba076d7c08 Auto-bump version to 4.13.9-SNAPSHOT ded14e9873 feat(cli): sync bundled skills into ~/.agents/skills on install/upgrade 8be4d49cd4 Auto-bump version to 4.13.8-SNAPSHOT e7c6d7dcbf feat(session): upgrade to pulsar 4.11.5 and recover lost driver links in place 1b4fde0094 fix(session): attach sessions must reuse the bound browser and never be silently recreated 3ebcf49baa fix(session): recover lost driver link on the same browser before recreating the session ce36cdb2a5 Auto-bump version to 4.13.7-SNAPSHOT 1219dbd0f7 fix(cli): diagnose headed launch failures via Browser4-managed chrome only
```

## Reproduce

`ash
# View all failed logs
gh run view 34706653079 --log-failed

# View the run in browser
gh run view 34706653079 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
Publish browser4-cli to npm	Re-check npm version before publish	2026-09-12T17:17:01.9545449Z [36;1mif [ "$SHOULD_PUBLISH" != "true" ]; then[0m
Publish browser4-cli to npm	Re-check npm version before publish	2026-09-12T17:17:01.9545964Z [36;1m  echo "::notice::Skipping npm publish because $PACKAGE_NAME@$CLI_VERSION already exists on npm."[0m
Publish browser4-cli to npm	Re-check npm version before publish	2026-09-12T17:17:01.9546537Z [36;1melif [ "$LOOKUP_STATUS" = "failed" ]; then[0m
Publish browser4-cli to npm	Re-check npm version before publish	2026-09-12T17:17:01.9547929Z [36;1m  echo "::warning::Unable to query npm version for $PACKAGE_NAME before publish. Proceeding because the prepare job determined a publish was required."[0m
Publish browser4-cli to npm	Re-check npm version before publish	2026-09-12T17:17:01.9548606Z [36;1mfi[0m
Publish browser4-cli to npm	Re-check npm version before publish	2026-09-12T17:17:01.9548790Z [36;1m[0m

══ block 2 ══
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4818725Z [36;1mfor binary in "${EXPECTED_BINARIES[@]}"; do[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4819128Z [36;1m  if [ ! -f "cli/bin/$binary" ]; then[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4819510Z [36;1m    echo "ERROR: Missing cli/bin/$binary"[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4819880Z [36;1m    ERRORS=$((ERRORS + 1))[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4820172Z [36;1m  else[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4820476Z [36;1m    SIZE=$(stat -c%s "cli/bin/$binary" 2>/dev/null)[0m

══ block 3 ══
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4820476Z [36;1m    SIZE=$(stat -c%s "cli/bin/$binary" 2>/dev/null)[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4820874Z [36;1m    if [ "$SIZE" -lt "$MIN_SIZE" ]; then[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4821354Z [36;1m      echo "ERROR: cli/bin/$binary is too small ($SIZE bytes, expected >= $MIN_SIZE)"[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4821830Z [36;1m      ERRORS=$((ERRORS + 1))[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4822132Z [36;1m    else[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4822429Z [36;1m      echo "OK: cli/bin/$binary ($SIZE bytes)"[0m

══ block 4 ══
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4823438Z [36;1m[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4823673Z [36;1mif [ "$ERRORS" -gt 0 ]; then[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4824192Z [36;1m  echo "Error: $ERRORS binary issues found"[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4824526Z [36;1m  exit 1[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4824750Z [36;1mfi[0m
Publish browser4-cli to npm	Verify all packaged binaries exist	2026-09-12T17:17:03.4824969Z [36;1m[0m

══ block 5 ══
Publish browser4-cli to npm	Verify npm package was published	2026-09-12T17:17:50.1850888Z npm registry has not reported browser4-cli@4.13.18 yet (attempt 4/5); retrying in 10s...
Publish browser4-cli to npm	Verify npm package was published	2026-09-12T17:18:00.4661417Z Unable to verify browser4-cli@4.13.18 on npm after publish
Publish browser4-cli to npm	Verify npm package was published	2026-09-12T17:18:00.4674230Z ##[error]Process completed with exit code 1.
Publish browser4-cli to npm	npm publish summary	﻿2026-09-12T17:18:00

... (truncated — run gh run view 34706653079 --log-failed for full logs)

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

## Resolution (2026-09-13)

**Category:** infrastructure/registry timing — the publish itself succeeded; the CI
verification window was too short. Not a regression, not a test-assertion change.

**Failing job:** `Publish browser4-cli to npm` (job 103590794217) — step
`Verify npm package was published`. The run's own log shows the publish step succeeding:

```
17:17:19 npm notice Your package is being processed and may take a few minutes to become available.
17:17:19 + browser4-cli@4.13.18
17:17:19 npm registry has not reported browser4-cli@4.13.18 yet (attempt 1/5); retrying in 10s...
17:18:00 Unable to verify browser4-cli@4.13.18 on npm after publish
```

**The publish worked.** `npm publish --provenance` exited 0 (`+ browser4-cli@4.13.18`) and
the version is on the registry now: `npm view browser4-cli@4.13.18 version` → `4.13.18`,
`dist-tags.latest = 4.13.18`. Only the post-publish availability check failed.

**Root cause:** npm's registry processes publishes asynchronously — the notice above is the
registry's own explanation. The verification step allowed `5 x 10 s ≈ 50 s` (4 sleeps + 5
lookups) and this time the version was still invisible to `npm view` 41 s after publish.
That async notice does not appear in the publish step of the three previous green releases
(v4.13.14/15/16 — checked per run), and the old window was already marginal (v4.13.15 needed
one retry, 21 s), so a registry-side behaviour change outran a hardcoded timing budget. The
failure was expensive because `publish-github-release` requires `publish-cli-npm.result ==
'success'`, so a successful npm publish also blocked the v4.13.18 GitHub release.

**Fix:** commit `50befcbdb1` `fix(release,ci): wait minutes, not seconds, for the npm publish
to appear`

- new `cli/scripts/wait-for-npm-version.sh`: polls for the exact version (600 s budget, 15 s
  interval), retries 404s quietly and other registry errors with a WARN, never sleeps past
  the deadline, and on timeout prints the last registry answer plus manual re-check commands
  (npmjs.com URL). Exit 0 = visible, 1 = not visible;
- the same inline loop existed **verbatim** in `release.yml` and `release-cli.yml` — both now
  call the script, so the next fix cannot land in only one of them;
- `release.yml`: the new bash suite runs in the `test-install-scripts` job (Linux);
- `cli/scripts/README.md`, `docs-dev/copilot/ci-stabilization-4.13.x.md` §10 document it.

**The gate is not weakened:** a version that never appears still exits 1 and fails the job.

**Verification**

- `bash cli/scripts/tests/wait-for-npm-version.tests.sh` → `All 11 tests passed` (~20 s,
  stubbed `npm`, no network);
- control experiment: restoring the old "max 5 attempts" limit makes exactly the
  `keeps polling past the old 5-attempt window` case fail (`1 of 11`) — the test
  discriminates (patch reverted);
- real registry: `bash cli/scripts/wait-for-npm-version.sh browser4-cli 4.13.18` →
  `Verified browser4-cli@4.13.18 on npm after 2s (attempt 1)` (exit 0); a non-existent
  version with a 6 s budget → exit 1 with `npm error code E404` and the re-check URL;
- both workflows parse with PyYAML; the three changed `run:` blocks pass `bash -n`.

**Also found (reported, not changed):** `cli/scripts/tests/install-browser4-cli.tests.sh`'s
`test()` helper drops everything after the second argument (`local fn="$2"; if "$fn"`), so
every `test "<name>" bash -c "..."` assertion runs a **bare `bash`** — which exits 0 with
/dev/null stdin, i.e. those assertions pass vacuously. With the helper corrected
(`shift` + `"$@"`) the suite reports `56 / 57 passed`, exposing one real failure
(`double dash in --version handled (no operator parsing)` — its body greps `--locate` output
for "version", a word that never appears there, and does not match the test's name). Left
alone because fixing the helper turns the release's `test-install-scripts` job red and the
"correct" assertion needs its own judgement call; the new suite uses `shift` + `"$@"`.

**Follow-up for the maintainer:** completing the v4.13.18 release does not need this fix —
`browser4-cli@4.13.18` is already on npm, so a re-run/re-dispatch takes
`should_publish=false`, skips the verification step entirely and proceeds to the GitHub
release. This fix protects the *next* release (v4.13.19 and later).
