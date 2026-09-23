Title: Fix release.yml failure for tag v4.13.21
Description: The release.yml workflow run 35860129386 (tag v4.13.21) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 35860129386) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.13.21
- **Run ID:** 35860129386
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/35860129386


## Failed Jobs

- **Publish GitHub release** (job ID: 107192798985)

## Release Changelog

```text
Commits since v4.14.0-rc.5:
6bf3578e47 chore(coworker): record the v4.13.21 release-gate failure triage 32b2dc3c92 test(e2e): fix the 7 scenarios the v4.13.21 release gate failed on 3c95be6437 chore(coworker): file the bot-stealth issue report and its resolution b2c57bb7b5 test(e2e): add opt-in stealth scenarios and raw-CDP probe scripts 41a5007982 fix(cli): act on the bot-stealth report's CLI findings 571bd10693 fix(browser): fail loudly on an unresolvable fill target, encode full-page PNG 0e694f535c fix(stealth): stop advertising the headless token 27e48f8c2f fix(rws): stream scenario output live instead of a silent file relay be33060f29 fix(cli): make the storage-state default-path test independent of leftover state b939bfc4e7 ci(smoke): cover packaged module sources in the smoke-test paths 0fa4fe9b4a Merge branch 'fix/e2e-force-rebuild-bundle' into 4.13.x fb2ebe3f2b fix(e2e): honour --force-rebuild-bundle 30a8633066 fix(stealth): press where the pointer was moved to 603e112599 Merge branch 'fix/trusted-click-input' into 4.13.x 2521c25a44 feat(stealth): dispatch clicks as trusted CDP input a86f89b4eb feat(stealth): humanise the pointer move before a click 043f6785b7 Merge branch 'fix/console-capture-over-cdp' into 4.13.x d53c42a4f3 feat(console): measure the Console domain, harden the capture, and pin the trade-off 0a99a5dd59 chore(code-mirror): refresh the generated WebDriver mirror e5dbfb64a5 chore(deps): upgrade browser4-base to 4.11.18 680bf16988 docs(crawl): record the --timeout end-to-end gap left open by section 27 40a8c97985 feat(crawl): let a request set its own task budget (--timeout) 5b2c450cee fix(crawl): load a page that delivered nothing once more, and never report the loss it disproves 06572fe4b3 feat(stealth): capture console messages over CDP instead of patching the page 36efd70a9a fix(crawl): -readonly wins over -refresh, and a store serve is a delivered page 5fce62557f feat(driver): say who waited for a driver, how long, and who holds the drivers 502ab4b6e6 fix(crawl): publish the in-flight view as an aggregate, under one lock be4505e9ee fix(crawl): spend -top-links on distinct pages, and shape a discovered href once 8ce59abe1b docs(ci): record the v4.13.21-ci.1 gate result, and the gain it proves 9126e70c0c test(crawl): assert crawl status through CrawlStatus, not literals 8ac4d359d3 Auto-bump version to 4.13.21-SNAPSHOT 60e35a8da8 test(crawl): one status vocabulary, and a ScrapeServiceTests that no longer wastes 100s per load db0d85e05f ci(gate): raise the test budget to 50 minutes for the crawl-heavy 4.13.x suite 4ad80c4ad4 test(crawl): fix the two wait-loop defects that failed the v4.13.20 gate 98594e3581 chore(release): 4.13.20 19a43acf83 chore: ship pending workspace changes (js test suite removal, agentic skills, coworker drafts) 803fa21e92 docs(cli): correct htmlsnapshot/regex/status claims, sync CLI help, halve SKILL.md 2ffed42997 docs(ci): record the CrawlParallelTabsTest timeout, with the numbers that locate it ae250c3a97 Auto-bump version to 4.13.20-SNAPSHOT ed9b8d2037 test(xsql): cover the page visit path under the gate that runs it 68850d6084 docs: point the XSQL hyperlink references at its current name 9b22e113db test(xsql): pin the read-only contract of the X-SQL the engine resolves 8a3409459f fix(crawl): let the crawl's X-SQL read the page the round already has 0661e3a970 fix(xsql): seal the X-SQL read-only and freeze the page its UDF resolves 298b4f31dc fix(crawl): withhold a row when the load returned no document for its URL 481444ce36 docs(crawl): record the real-browser verification, with the pre-existing flake pinned 7ffc190e56 fix(crawl): settle and report every submitted URL (depth identity, sessions, round budget) 13096ebbbb refactor(crawl): split the 1887-line CrawlService into a crawl package 2d8e0d627e feat(crawl): collect crawl units in parallel, each on its own browser tab 177df7045d fix(protocol): lease a specified web driver so a fetch never shares its tab (#592) f763bfc6fc fix(crawl): settle every submitted page and report the ones that never arrive (#592) 864bfb057b chore(coworker): archive resolved v4.13.19-ci.2 swarm-test task 86fe8e4d38 fix(protocol): retry a snapshot-origin refusal promptly, not with the 30-45s backoff e018b73107 chore(coworker): archive resolved v4.13.19-ci.1 install-script-test task f7a94c46d9 fix(install): replace the non-ASCII em dash in install-browser4-cli.sh 871933cdd7 ci: run the installer test suites on every CI round 1a62bd71b4 fix(install): harden both installers and make the install test suites real 832d82e099 Auto-bump version to 4.13.19-SNAPSHOT c9f720a9d5 chore(coworker): archive resolved v4.13.18 release npm-publish task 50befcbdb1 fix(release,ci): wait minutes, not seconds, for the npm publish to appear 194a2c3336 docs(ci): ci.6 is green - record the decisive evidence c7197316b3 docs(ci): why the monitor-ci extraction fix is deferred (ps1 tests run on main only) 1ef6101244 docs(ci): tidy the report order and add the independent pool-fix re-check f3c1a6202c docs(ci): verify the driver-pool fix independently and record follow-ups 26607ceeff docs(ci): ci.5 flaky triage, local-vs-CI count delta, monitor-ci note f587ee1862 fix(coworker): task update f8dccd2bda chore(coworker): archive resolved v4.13.18-ci.5 CI tag-failure task 2671e1574d fix(protocol): keep polling for a driver when the resource guard refuses 07b6019ace docs(ci): record the ci.4 verification and the per-class re-checks 39779e079c ci: document that excluded_groups replaces the pom default efc650490a ci: make the test verdict honest and enumerate failures in one run 2a275f8b73 fix(test): normalise the inherited run directory to an absolute path f6886ed907 chore(coworker): archive resolved v4.13.18-ci.2 CI tag-failure task 4d0af69110 fix(protocol): accept local-file fetches in the snapshot origin guard 2a0ff2bf5a feat(test): give every test run its own .test-sessions subdirectory 7efa9b4565 chore(coworker): archive resolved v4.13.18 CI tag-failure task cae4042735 fix(test): deserialize crawl poll responses with Kotlin-aware Jackson fc4b6f9ddf test(extension): repair ExtensionWebSocketHandlerTest against the current handler 992c911fe8 feat(swarm): batch-aware status, MCP batch tools, and a truthful isDone signal d924559079 fix(swarm): batch ids, truthful completion tracking, no more hung CLI calls 950ab1776d fix(htmlsnapshot): annotate captured HTML with vi boxes and the page URL da5772cc72 test(tests-production): add named-session and attach/close verification scripts 483f372d10 fix(tests-production): resolve the CLI exe behind npm .cmd shims without aborting the run bd18d2b7d8 fix(release,ci): match only the run the tag push triggered 0125299de7 Auto-bump version to 4.13.18-SNAPSHOT 5935e913c4 chore(coworker): close redundant v4.13.17 release-failure task af3de3578e chore(coworker): archive resolved v4.13.17 smoke-test failure task 0f2a3a292f fix(ci): serve the smoke-test fixture page from its own temp dir 6be3365514 docs(skills): add browser-modes reference and document SUPERVISED mode 8eabd1acf2 feat(agentic,rest,cli): backport weibo2x lessons plan v2 fixes from 4.14.x
```

## Reproduce

`ash
# View all failed logs
gh run view 35860129386 --log-failed

# View the run in browser
gh run view 35860129386 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4107018Z [36;1m[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4107367Z [36;1mif [ -z "$RUN_ID" ]; then[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4107822Z [36;1m  echo "::error::Could not find sync-to-oss.yml workflow run"[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4108267Z [36;1m  exit 1[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4108601Z [36;1mfi[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4108923Z [36;1m[0m

══ block 2 ══
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4112975Z [36;1m[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4113318Z [36;1mif [ "$CONCLUSION" != "success" ]; then[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4113863Z [36;1m  echo "::error::OSS sync workflow failed — CDN may still serve an older release."[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4114561Z [36;1m  echo "::error::Verify the run at https://github.com/platonai/Browser4/actions/runs/${RUN_ID} and re-trigger if needed."[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4115162Z [36;1m  exit 1[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4115536Z [36;1mfi[0m

══ block 3 ══
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4113318Z [36;1mif [ "$CONCLUSION" != "success" ]; then[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4113863Z [36;1m  echo "::error::OSS sync workflow failed — CDN may still serve an older release."[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4114561Z [36;1m  echo "::error::Verify the run at https://github.com/platonai/Browser4/actions/runs/${RUN_ID} and re-trigger if needed."[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4115162Z [36;1m  exit 1[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4115536Z [36;1mfi[0m
Publish GitHub release	Sync to Aliyun OSS CDN	2026-09-23T13:05:01.4115823Z [36;1m[0m

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
