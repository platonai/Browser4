Title: Fix release.yml failure for tag v4.14.0-rc.6
Description: The release.yml workflow run 35265014949 (tag v4.14.0-rc.6) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 35265014949) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.14.0-rc.6
- **Run ID:** 35265014949
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/35265014949


## Failed Jobs

- **Publish GitHub release** (job ID: 105364852566)

## Release Changelog

```text
Commits since v4.14.0-rc.5:
cebc2ac411 chore(cli): sync CLI version to 4.14.0-rc.6 739c3d1c74 Bump version to v4.14.0-rc.6 befd1f1c74 fix(e2e): compile the crawl scenario table and read status lines from both streams 2198a191d1 fix(driver): reuse a standby driver before creating a new tab e57158eac7 test(crawl): poll CrawlServiceTest on any non-terminal status 5435a7d10e docs(crawl): keep crawl.md inside the M6 cap after the 4.13.x merge ae5d57e19e Merge branch 'feat/mcp-channel-parity' into 4.14.x 842ec657f5 Merge branch '4.13.x' into 4.14.x 4c62773501 fix(test): stop the knowledge-store concurrency test from hanging CI 2ffed42997 docs(ci): record the CrawlParallelTabsTest timeout, with the numbers that locate it e89afcc95d feat(observability): tool-call spans, opt-in tracing, and the tab coverage gate ae250c3a97 Auto-bump version to 4.13.20-SNAPSHOT ed9b8d2037 test(xsql): cover the page visit path under the gate that runs it 68850d6084 docs: point the XSQL hyperlink references at its current name 9b22e113db test(xsql): pin the read-only contract of the X-SQL the engine resolves 8a3409459f fix(crawl): let the crawl's X-SQL read the page the round already has 0661e3a970 fix(xsql): seal the X-SQL read-only and freeze the page its UDF resolves 31cd882da7 feat(mcp): result contracts for the html_snapshot family 5064e3a34a feat(mcp): result contracts for webdb and skill, and arrays are schemas too 9f346742e1 fix(experience): make stats updates atomic, and re-enable the concurrency suite b32660f89f fix(experience): let the knowledge store survive concurrent writers 0d4e372dc4 feat(mcp): a callable example for every advertised tool 298b4f31dc fix(crawl): withhold a row when the load returned no document for its URL 3d6d983294 feat(cli): isolate development-mode backends per checkout ce456fb353 feat(mcp): result contracts for the experience and memory domains 1e6d100c5e feat(cli): runnable examples and actionable failure tips b4e181b479 feat(mcp): publish example executability, close the example and alias gaps 481444ce36 docs(crawl): record the real-browser verification, with the pre-existing flake pinned 7ffc190e56 fix(crawl): settle and report every submitted URL (depth identity, sessions, round budget) 64576071be feat(mcp): callable examples for 62 more tools (requirement 2) 28b7d95743 test(mcp): contract matrix over every advertised tool (Phase 6, requirement 3) 1482a1a694 feat(mcp): one batch primitive for both channels (Phase 5, requirements 12-14) 13096ebbbb refactor(crawl): split the 1887-line CrawlService into a crawl package 9e54e4f86d chore: ignore the root-level runtime knowledge store 523e6908d4 chore: stop tracking runtime knowledge files written from the repo root 8f574d6881 feat(mcp): one async contract for long-running tools (Phase 4, requirement 11) f6e956bd2b feat(mcp): reuse idempotent read results (Phase 4, requirement 10) 55e0261055 docs(mcp): record requirement 9 completion and the /mcp/tools (G5) fix ceaa98a691 fix(mcp): never freeze the session segment of /mcp/tools (G5) 80e826f8cc feat(mcp): token-bucket rate limiting for both channels (Phase 4, requirement 9) 4eec95eb80 fix(mcp): stop declaring optional arguments as required 97b57dfc9f feat(mcp): observe built-in contract violations in shadow mode 46ca12988c docs(mcp): link the Phase 3 log entry to the tab contract fix commit febb5ac8cb feat(mcp): structured call logging and per-tool metrics (Phase 3, requirements 7 and 8) 9e4b4cbeb3 fix(mcp): advertise callable tab tool contracts 2d8e0d627e feat(crawl): collect crawl units in parallel, each on its own browser tab 64d0e061b0 docs(mcp): mark Phase 2 complete (requirements 4/5/6) fb94d30871 feat(mcp): declare and validate what a tool returns (Phase 2, requirement 6) ba9a2e7299 docs(mcp): record Phase 2 status (codes and validation done, output validation open) 547f5c7e1e feat(mcp): give failures a code and reject malformed calls early (Phase 2) adc155e454 docs(mcp): record the Phase 1 outcome and what is left c639994e29 feat(mcp): ship the tool reference with the code (Phase 1) 104ffb5e64 feat(mcp): make an advertised tool an executable tool (Phase 0.3/0.4) f6692f123c feat(mcp): make the tool schema tell the truth (Phase 0.1/0.2/0.6) 177df7045d fix(protocol): lease a specified web driver so a fetch never shares its tab (#592) f763bfc6fc fix(crawl): settle every submitted page and report the ones that never arrive (#592) 2cc9936865 fix(mcp): refresh the tool list for late-registered executors 3de007bbb3 test(mcp): prove --app mcp dispatch and document the BOM rebuild requirement 2e191e8e59 feat(mcp): migrate the standard MCP server to stateless Streamable HTTP 913e3cf82d feat(mcp): align the standard MCP server with the private dispatcher 864bfb057b chore(coworker): archive resolved v4.13.19-ci.2 swarm-test task 86fe8e4d38 fix(protocol): retry a snapshot-origin refusal promptly, not with the 30-45s backoff e018b73107 chore(coworker): archive resolved v4.13.19-ci.1 install-script-test task f7a94c46d9 fix(install): replace the non-ASCII em dash in install-browser4-cli.sh 871933cdd7 ci: run the installer test suites on every CI round 1a62bd71b4 fix(install): harden both installers and make the install test suites real 832d82e099 Auto-bump version to 4.13.19-SNAPSHOT c9f720a9d5 chore(coworker): archive resolved v4.13.18 release npm-publish task 50befcbdb1 fix(release,ci): wait minutes, not seconds, for the npm publish to appear f793b98c5b fix(skills): satisfy the SKILL document lint on the merged tree e01158753e fix(ci): keep VersionInfo.ps1 out of the git-ignored lib/ directory 350d417d74 fix(ci): support pre-release VERSIONs in trigger-ci.ps1 5a76c172ba Merge remote-tracking branch 'origin/4.13.x' into 4.14.x b3db019b72 fix(browser): read cookies via raw CDP; honour `open --profile <path>` 14fb46d4b8 fix(build): repair the test sources the 4.13.x merge left uncompilable 194a2c3336 docs(ci): ci.6 is green - record the decisive evidence 695100de56 Merge branch '4.13.x' into 4.14.x c7197316b3 docs(ci): why the monitor-ci extraction fix is deferred (ps1 tests run on main only) 1ef6101244 docs(ci): tidy the report order and add the independent pool-fix re-check f3c1a6202c docs(ci): verify the driver-pool fix independently and record follow-ups 26607ceeff docs(ci): ci.5 flaky triage, local-vs-CI count delta, monitor-ci note f587ee1862 fix(coworker): task update f8dccd2bda chore(coworker): archive resolved v4.13.18-ci.5 CI tag-failure task 2671e1574d fix(protocol): keep polling for a driver when the resource guard refuses 07b6019ace docs(ci): record the ci.4 verification and the per-class re-checks 39779e079c ci: document that excluded_groups replaces the pom default efc650490a ci: make the test verdict honest and enumerate failures in one run 2a275f8b73 fix(test): normalise the inherited run directory to an absolute path f6886ed907 chore(coworker): archive resolved v4.13.18-ci.2 CI tag-failure task 4d0af69110 fix(protocol): accept local-file fetches in the snapshot origin guard 2a0ff2bf5a feat(test): give every test run its own .test-sessions subdirectory 7efa9b4565 chore(coworker): archive resolved v4.13.18 CI tag-failure task cae4042735 fix(test): deserialize crawl poll responses with Kotlin-aware Jackson fc4b6f9ddf test(extension): repair ExtensionWebSocketHandlerTest against the current handler 992c911fe8 feat(swarm): batch-aware status, MCP batch tools, and a truthful isDone signal d924559079 fix(swarm): batch ids, truthful completion tracking, no more hung CLI calls 710ddcc965 test(browser): add a driver-level real-browser test for the capture annotations (#588) b642b723e5 fix(htmlsnapshot): annotate captured HTML with vi boxes and the page URL (#588) 950ab1776d fix(htmlsnapshot): annotate captured HTML with vi boxes and the page URL da5772cc72 test(tests-production): add named-session and attach/close verification scripts 483f372d10 fix(tests-production): resolve the CLI exe behind npm .cmd shims without aborting the run bd18d2b7d8 fix(release,ci): match only the run the tag push triggered e20b841352 docs(SKILL): update installation instructions and remove outdated config command 0125299de7 Auto-bump version to 4.13.18-SNAPSHOT 5935e913c4 chore(coworker): close redundant v4.13.17 release-failure task af3de3578e chore(coworker): archive resolved v4.13.17 smoke-test failure task 0f2a3a292f fix(ci): serve the smoke-test fixture page from its own temp dir 6be3365514 docs(skills): add browser-modes reference and document SUPERVISED mode 8eabd1acf2 feat(agentic,rest,cli): backport weibo2x lessons plan v2 fixes from 4.14.x
```

## Reproduce

`ash
# View all failed logs
gh run view 35265014949 --log-failed

# View the run in browser
gh run view 35265014949 --web
`

## Error Diagnostics

(No specific error patterns or test failures matched — last 40 log lines)
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6923219Z   CONTAINER_NAME: browser4
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6923526Z   NETWORK_NAME: browser4_backend
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6923861Z   DOCKER_USERNAME: galaxyeye88
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6924234Z   PRODUCTION_JAR_MODULE_NAME: browser4-standalone
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6924715Z   PRODUCTION_JAR_MODULE_PATH: browser4-apps/browser4-standalone
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6925344Z   PRODUCTION_JAR_NAME: Browser4.jar
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6925816Z   PRODUCTION_JAR_PATH: browser4-apps/browser4-standalone/target/Browser4.jar
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6926312Z   BUNDLE_MODULE_NAME: browser4-bundle
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6926991Z   BUNDLE_MODULE_PATH: browser4-apps/browser4-bundle
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6927408Z   BUNDLE_JAR_NAME: Browser4Bundle.jar
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6927887Z   BUNDLE_JAR_PATH: browser4-apps/browser4-bundle/target/Browser4Bundle.jar
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6928347Z   JAVA_VERSION: 25
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6928640Z   BUNDLE_JAVA_VERSION: 25
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6928945Z   NODE_VERSION: 24
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6929244Z   MAVEN_OPTS: -Xmx3g -XX:+UseG1GC
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6929586Z   CLI_PACKAGE_DIR: cli
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6929893Z   CLI_PACKAGE_NAME: browser4-cli
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6930264Z   CLI_MANIFEST_PATH: cli/browser4-cli/Cargo.toml
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6932857Z   GITHUB_TOKEN: ***
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:28.6933148Z ##[endgroup]
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:33.7210791Z 👩‍🏭 Creating new GitHub release for tag v4.14.0-rc.6...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4351371Z ⬆️ Uploading Browser4.jar...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4353711Z ⬆️ Uploading browser4-cli-darwin-arm64...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4354596Z ⬆️ Uploading browser4-cli-darwin-x64...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4355457Z ⬆️ Uploading browser4-cli-linux-arm64...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4356445Z ⬆️ Uploading browser4-cli-linux-musl-arm64...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4357638Z ⬆️ Uploading browser4-cli-linux-musl-x64...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4358504Z ⬆️ Uploading browser4-cli-linux-x64...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4359384Z ⬆️ Uploading browser4-cli-win32-x64.exe...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4360874Z ⬆️ Uploading browser4-bundle-runtime-darwin-arm64.tar.gz...
Publish GitHub release	Create or update GitHub Release	2026-09-17T20:13:37.4362195Z ⬆️ Uploading browser4-bundle-runtime-linux-x64.tar.gz...
Publish GitHub release	Create or update GitHub Release	2026-09-17T

... (truncated — run gh run view 35265014949 --log-failed for full logs)

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
