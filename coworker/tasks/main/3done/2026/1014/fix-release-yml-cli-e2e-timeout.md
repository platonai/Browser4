Title: Fix release.yml failure for tag v4.14.0
Description: The release.yml workflow run 38527155657 (tag v4.14.0) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 38527155657) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.14.0
- **Run ID:** 38527155657
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/38527155657


## Failed Jobs

- **Build core artifacts and Docker image** (job ID: 115730185980)

## Release Changelog

```text
Commits since v4.14.0-rc.8:
3741ead296 chore(release): 4.14.0 9f5470727e docs(coworker): file the latest scenario evaluations and refresh the review template 19e575f9ea feat(cli): let `list --all` span every workspace's persisted sessions 185175f6eb fix(rws): bound the native-command drain so a leftover descendant cannot park the runner a009882764 fix(bin): bound the child output drain so a leftover descendant cannot hang the runner ddc98239c2 fix(bin): reject unknown arguments for the ps target a0b206124b fix(scripts): emit UTF-8 instead of the OEM code page 0dc4db42ad fix(ci): keep the two newly-run suites honest about their environment 4107ddc8b5 Auto-bump version to 4.14.0-rc.10 a61e3776d2 fix(mcp): keep page_url/page_title with the built-in tab tools 201b052580 chore(cli): sync CLI version to 4.14.0-rc.9 92e381a947 Bump version to v4.14.0-rc.9 73f550a6e3 docs(skills): keep the crawl reference inside the 500-line cap 7debec943d test(cli): assert the server-free local command surface offline 1d77753843 fix(cli): stop advertising an MCP tool for two purely local commands 1a6abde704 test(real-world-scenarios): guard command coverage so it cannot drift again 411d86c62f test(real-world-scenarios): add the first six Wave 1 scenario tasks 3ed365de6f docs(cli): record the CLI command coverage audit and the plan behind it a60c1a7487 feat(cli): make help accurate, complete, offline and instant d0118d4130 fix(crawl): refuse a seed that is not a URL, in the CLI and over MCP (issue #616) 1cfdf646e0 feat: annotate classes with @Beta to indicate experimental status 7ef1c4da2d refactor: update imports to use ai.platon.browser4.common.B4Constants 7918f76863 Merge branch '4.14.x' of https://github.com/platonai/Browser4 into 4.14.x 40a47a7dba docs: 更新中英文 README 的联系信息及展示图片 7f9a63165c refactor(rest): share the session, envelope and SSE rules behind the scrape and command faces d60023858b ci(nightly): run the E2E/E2ETest JVM suites here, the only place they can run d4f5ce4dc8 test(formats): prove the A2 refusal on a real backend b096120a08 feat(formats): implement the `strict` three-state contract 5434506e71 docs(scrape): give `scrape` its own tips, and record the verified numbers e79145ed7f feat(cli): `scrape --output <dir>`, so binary formats can reach the caller cb9441c648 feat(formats): serve artifact bytes over HTTP, so a remote caller can fetch them aeaad1fe69 feat(formats): implement `pdf`, the second live-tab format 6a64317ee2 fix(formats): require an addressed session, and record the A1-A6 decisions d26b926b13 fix(formats): refuse an unusable contributor at registration, not at call time 7898d464c0 feat(formats): implement `screenshot`, and fix the two live-stage bugs it exposed 87b452f234 fix(formats): drop the request-level `url` and `expires` instead of half-honouring them 6ac1db216f test(formats): assert the formats layer against a real page built to exercise it bacdf71ea2 feat(cli): register `scrape` / `scrape formats` as static commands (Phase 1c A7) a7b027e0d8 docs(formats): document `page scrape` and put the page domain under the doc gates 16fc3c95c7 feat(formats): expose the formats layer over REST at api/scrape (Phase 1c A5) f97e782c62 feat(formats): expose the formats layer as the `page` tool domain (Phase 1c A1/A2/A7) 866c417059 docs(formats): record the verified Phase 1c environment and the expires mechanism 911ff3cf97 feat(formats): add the Firecrawl-compatible `formats` layer (model, engine, SPI wiring) e0a7d20858 Merge 4.13.x into 4.14.x: the closeTab resurrection fix and the e2e gate realignment 9d5f28f0af Auto-bump version to 4.13.28-SNAPSHOT c768247b50 fix(agentic): verify tab close and retry to stop recovery resurrecting tabs d86b69fc8b Merge 4.13.x into 4.14.x: the 20261004 issue batch, the browser-level CDP WebSocket attach, and the URL-identity upgrade tolerance 8bbab47b38 chore(ci): add task record for the v4.13.27 release.yml failure 7e86e0355e fix(cli): realign the e2e gate with the 20261004 batch; fix agent list --clear ee7ff47b81 chore(ci): add task record for the v4.13.27 release.yml failure eec5fc89d3 fix(cli): register tool-call in the e2e tested-commands list 8889a9b933 fix(issues): close out 20261004 issue-report batch (18 reports) 6514a3ccc4 Auto-bump version to 4.13.27-SNAPSHOT e333f8c871 Bump version to v4.13.26 5104ea801e docs(dev): record the URL normalization backport onto 4.13.x f868671d2d fix(url): adapt the url-normalization backport to the 4.13.x harness 089e5b0b11 fix(crawl): order a queued url's query the way the engine's identity does 4230741a61 fix(protocol): the snapshot origin guard must not read a reordered query as another document faca27ece7 chore(deps): consume pulsar 4.11.24 b838a78962 docs(url): state the invariant — normalize for the key, href for the address d8a3c6c468 docs(url): audit the identity plumbing — normalize() is the only key 046f67e98b docs(url): record the base-library definition change and the canonical-form scope ebd6ae434a feat(url): consume pulsar-common 4.11.23, validate at the boundaries, go to the href 0fc7ca451c fix(rest): one escape, one url check, and a NormURL that keeps its detail 218b1b4f7d docs(url): record the Phase B fixes and the full verification log (2212 tests) 2a294c18f7 fix(experience): one URL spelling for the store, the pattern and the matcher 673bffc340 docs(url): the base-library URLUtilsTest runs green (43/43) 460dd36500 docs(url): record the executed fixes and the verification results 749215926c fix(url): make URL normalization honour its own contract e9d3d6c487 docs(url): add URL normalization review and fix plan 61a38a1737 Auto-bump version to 4.13.26-SNAPSHOT 672ddc0759 chore(cli): sync the CLI version files to 4.13.25 a72607696d Bump version to v4.13.25 6be7b364dd docs(dev): track all three CDP-attach open items, not just the two issues 5dab332283 docs(dev): link the non-Windows listening-port gap to its issue ae997ea376 docs(dev): record the doc-line-cap and release-order traps 83457578fe chore(cli): sync the CLI version files to 4.13.24 fa50ad1295 Bump version to v4.13.24 bb2f064bad docs(cli): keep browser-modes.md inside the 500-line decision-doc cap bb0e954837 fix(cli): compile on non-Windows after the process-list refactor f71abc913c docs(dev): record the WebSocket-attach findings and the 4.11.23 release run 8ae154f398 chore(cli): add CDP endpoint and WebSocket probe scripts e9ab82fe4f docs(cli): document WebSocket attach, extension_id and portable profiles 5c6c4054af feat(cli,rest): attach over the browser-level CDP WebSocket f3d870c8c1 feat(cli): add config extension_id 2aec2f4ce4 feat(cli): pick attach endpoints by page target, WebSocket-only browsers included a353fa1be6 chore(deps): bump browser4-base to 4.11.23
```

## Reproduce

`ash
# View all failed logs
gh run view 38527155657 --log-failed

# View the run in browser
gh run view 38527155657 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6321354Z  * [new branch]            fix/extension-external-browser-id -> origin/fix/extension-external-browser-id
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6322095Z  * [new branch]            fix/headless-stealth-consistency -> origin/fix/headless-stealth-consistency
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6322802Z  * [new branch]            fix/nightly-cli-e2e-failures -> origin/fix/nightly-cli-e2e-failures
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6323460Z  * [new branch]            fix/ps1-m6-crawl-cap    -> origin/fix/ps1-m6-crawl-cap
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6324176Z  * [new branch]            fix/smoke-paths-and-storage-state-test -> origin/fix/smoke-paths-and-storage-state-test
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6325217Z  * [new branch]            fix/stealth-pointer-jitter-and-page-hygiene -> origin/fix/stealth-pointer-jitter-and-page-hygiene

══ block 2 ══
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6343425Z  * [new branch]            worktree-experience-workflow -> origin/worktree-experience-workflow
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6344159Z  * [new branch]            worktree-fingerprint-loader-tests -> origin/worktree-fingerprint-loader-tests
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6345059Z  * [new branch]            worktree-fix-7-e2e-test-failures -> origin/worktree-fix-7-e2e-test-failures
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6345784Z  * [new branch]            worktree-fix-all-plugin-issues -> origin/worktree-fix-all-plugin-issues
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6346436Z  * [new branch]            worktree-fix-build-ps51 -> origin/worktree-fix-build-ps51
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6347097Z  * [new branch]            worktree-fix-chat-e2e-coverage -> origin/worktree-fix-chat-e2e-coverage

══ block 3 ══
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6351934Z  * [new branch]            worktree-fix-daily-maintenance-workflow -> origin/worktree-fix-daily-maintenance-workflow
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6352733Z  * [new branch]            worktree-fix-dsh-ps1-resolution -> origin/worktree-fix-dsh-ps1-resolution
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6353458Z  * [new branch]            worktree-fix-e2e-test-failures -> origin/worktree-fix-e2e-test-failures
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6354179Z  * [new branch]            worktree-fix-edit-file-in-editor -> origin/worktree-fix-edit-file-in-editor
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6355040Z  * [new branch]            worktree-fix-editor-startprocess -> origin/worktree-fix-editor-startprocess
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6355750Z  * [new branch]            worktree-fix-goto-enter-runs -> origin/worktree-fix-goto-enter-runs

══ block 4 ══
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6385938Z  * [new branch]            worktree-merge-3-prs-to-412 -> origin/worktree-merge-3-prs-to-412
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6386660Z  * [new branch]            worktree-merge-prs-ci-threshold -> origin/worktree-merge-prs-ci-threshold
Build core artifacts and Docker image	Checkout repository	2026-10-14T07:15:55.6387490Z  * [new branch]            worktree-monitor-workflow-failure-handler -> o

... (truncated — run gh run view 38527155657 --log-failed for full logs)

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

### Categorization
**Infrastructure / budget issue** — no test failed. The step was killed by its own
timeout after every scenario had already passed.

### Root Cause
`release.yml`'s `Run browser4-cli E2E Tests` step ran the full suite
(`--level=EXTENDED --enable-all`) under `timeout-minutes: 15`. The 214-scenario
suite needs longer than that against the Dockerized service, so GitHub killed the
step while the harness was in its final cleanup. The failing job's log ends with:

```text
[5/5] No processes to wait for, skipping.
test result: ok. 214 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
per-test timing:
  ...
final cleanup:
    1. browser4 final service cleanup: 0.22s
##[error]The action 'Run browser4-cli E2E Tests' has timed out after 15 minutes.
```

The same command already runs with a 30-minute budget in `nightly.yml` — nightly
run 38500964849 executed that step in 14m24s and passed, i.e. the release run was
cut off roughly one minute short of finishing.

### Fix
`release.yml`: `timeout-minutes: 15` → `30` for the CLI E2E step, aligning it with
`nightly.yml`/`nightly-cli.yml` for the identical command.

### Files Changed
- `.github/workflows/release.yml`

### Verification
- The failing run's own log shows `214 passed; 0 failed` before the timeout, so the
  suite itself is green; only the budget was wrong.
- `nightly.yml` completes the same command in ~14m24s inside its 30-minute budget
  (run 38500964849), which is the headroom the fix restores.
- Nothing was published for v4.14.0 before the timeout (no GitHub Release, npm still
  at 4.14.0-rc.9, no 4.14.0 on Maven Central, Docker push skipped), so the tag was
  moved to the fixed commit and re-pushed.
