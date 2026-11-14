Title: Fix release.yml failure for tag v4.16.7
Description: The release.yml workflow run 43600201389 (tag v4.16.7) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 43600201389) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.16.7
- **Run ID:** 43600201389
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/43600201389


## Failed Jobs

- **Publish GitHub release** (job ID: 132475393191)

## Release Changelog

```text
Commits since v4.16.6:
6ae3a59072 test(browser4-browser): assert the vi-data staleness short circuit precedes compute 479dacf119 refactor(coding): remove the shell syntax gate 97a090f461 refactor(agent): drop the engine tag from the interactive banner 769f219c51 fix(cli): keep env server/session defaults out of batch steps 14e17c4d4a fix(cli): stop the WMI fallback test from popping a terminal window d376d9425a fix(cli): stop the AOT training run from showing a console window a14347680e fix(coding): let --unrestricted lift the command tier, not only the syntax gate 41d60c3706 docs(skill): keep agent.md under the 500-line cap 24af82d755 docs(copilot): record progress and remaining work in the efficiency plan 9dd0cc0c5f feat(coding): add `coding.readFiles` for a bounded multi-file read 6612798180 feat(agent): report a run's four-bucket timings on the task result 30a0030653 docs(copilot): agent dev efficiency plan v3, review and timing script cf94abbc44 perf(agentic): price output tokens in the coding prompt d7b0796439 feat(agentic): attribute every tool round to model, tools and callbacks 6c336fbd33 perf(coding): short-circuit Maven builds whose inputs are unchanged ce9cbe9d08 chore(issues): record the #640 fix b1fb027d6c fix(cli): scope the e2e process sweep to this checkout (#640) 3037dc69e0 chore(issues): link #640 for the e2e sweep incident c8dfc08bae chore(issues): file the e2e sweep killing a neighbouring checkout's backend 733b3236b5 docs(agent): state that a resume keeps --unrestricted and cannot drop it acd64347b1 test(agent): cover --unrestricted end to end (REPL and command_run) 6eb70dce14 fix(coding): keep the runtime vetoes visible inside block parentheses be4d6aa025 fix(agent): run and report an unrestricted task truthfully d3eb553855 docs(agent): state what --unrestricted does and does not lift e651dce9b4 fix(agent): describe an unrestricted task truthfully f2f35e2deb fix(coding): classify every syntax refusal consistently ef1ae5c1e2 fix(agent): make the unrestricted prompt block reachable and name the vetoes 87dbc5f0b4 test(coding): gate the cmd.exe cases to Windows and cover the lift everywhere ece8e59edc fix(coding): keep the runtime vetoes when --unrestricted lifts the syntax gate 256c16c203 fix(agent): rethrow a compaction token-budget abort instead of retrying it ca0c40561e feat(coding): let `--unrestricted` lift the shell's syntax gate 409f2a9446 feat(agent): add `agent run --unrestricted` to run with the OS user's own permissions 3c9f811492 fix(agent): reset the token budget per run and charge compaction calls 218e534339 feat(coding): support validated cmd.exe if conditionals 93ce54bdb5 feat(coding): add a boot probe and session-scoped worktree reuse 6bbb6c141c feat(recover): add -Prune to reclaim isolated worktrees on demand acb485737f feat(coding): reclaim stale isolated worktrees 9c522687f5 feat(coding): protect the launch path and refuse task-driven bootstrap cfc057bc85 feat(recover): add `b4w recover` and keep the previous runtime bundle 26e795e6e4 feat(agent): run coding tasks in a git worktree and merge verified patches 7e4191106f fix(coding): add module resolver, isolation gate and worktree merge primitives 553664f4d0 refactor(agent): delete the orphaned ContextToAction inference events and TextToAction 3b9f7e7576 refactor(agent): remove deprecated generate() method in ContextToAction 591cf99899 fix(rest): bind Kotlin @RequestBody DTOs on Jackson 3 127724f37f refactor(agent): delete the dead system-prompt v1 chain 1ad98c0599 refactor(agent)!: remove session.act() and the act REST/CLI entry points da2f3fec43 refactor(agent): remove the observe-act engine, keep only the CLI tool loop 96a34bc773 revert(agent): drop @Deprecated from the PerceptiveAgent.run chain 5b4f219dd9 refactor(agent): deprecate PerceptiveAgent.run in favour of command.run 49a22abcb7 fix(cli): carry task context into follow-up menu and stop capping full-mode tables f106139079 feat(agent): carry one session context across tasks and predict the next task e9e32e697f fix(ci): restore the PowerShell gate for agent.md and `star` bc81e91fea Auto-bump version to 4.16.7-SNAPSHOT
```

## Reproduce

`ash
# View all failed logs
gh run view 43600201389 --log-failed

# View the run in browser
gh run view 43600201389 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
Publish GitHub release	Reconcile release assets	2026-11-14T14:20:07.1871415Z 
Publish GitHub release	Reconcile release assets	2026-11-14T14:20:07.1871576Z if [ "success" != "success" ]; then
Publish GitHub release	Reconcile release assets	2026-11-14T14:20:07.1871930Z   echo "::warning::Create or update GitHub Release reported a failure; re-uploading whatever the release is missing"
Publish GitHub release	Reconcile release assets	2026-11-14T14:20:07.1872257Z fi
Publish GitHub release	Reconcile release assets	2026-11-14T14:20:07.1872400Z 
Publish GitHub release	Reconcile release assets	2026-11-14T14:20:07.1872637Z printf '%s\n' "$RELEASE_ASSET_MANIFEST" > "$RUNNER_TEMP/release-asset-manifest.txt"

══ block 2 ══
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:05:51.5523400Z run 43604336388: status=in_progress (2730s elapsed, 1170s left)
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:06:07.3016442Z run 43604336388: status=completed (2746s elapsed, 1154s left)
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:06:07.3034401Z ##[error]sync-to-oss.yml run 43604336388 concluded 'failure' -- the OSS CDN may still serve an older release.
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:06:07.3039675Z ##[error]Run URL: https://github.com/platonai/Browser4/actions/runs/43604336388
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:06:07.3040914Z ##[error]Re-check it, then re-trigger with: gh workflow run sync-to-oss.yml -f tag_name=v4.16.7
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:06:07.3042821Z ##[error]Process completed with exit code 1.

══ block 3 ══
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:06:07.3039675Z ##[error]Run URL: https://github.com/platonai/Browser4/actions/runs/43604336388
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:06:07.3040914Z ##[error]Re-check it, then re-trigger with: gh workflow run sync-to-oss.yml -f tag_name=v4.16.7
Publish GitHub release	Sync to Aliyun OSS CDN	2026-11-14T15:06:07.3042821Z ##[error]Process completed with exit code 1.
Publish GitHub release	Post Checkout repository	﻿2026-11-14T15:06:07.3130810Z Post job cleanup.
Publish GitHub release	Post Checkout repository	2026-11-14T15:06:07.3758208Z [command]/usr/bin/git version
Publish GitHub release	Post Checkout repository	2026-11-14T15:06:07.3799513Z git version 2.56.1

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
