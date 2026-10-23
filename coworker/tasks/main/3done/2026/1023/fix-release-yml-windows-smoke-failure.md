Title: Fix release.yml failure for tag v4.14.3
Description: The release.yml workflow run 39971779173 (tag v4.14.3) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 39971779173) for tag $Tag failed in CI.

## Context

- **Workflow:** release.yml
- **Tag:** v4.14.3
- **Run ID:** 39971779173
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/39971779173


## Failed Jobs

- **Smoke test (Windows x64)** (job ID: 120498013072)

## Release Changelog

```text
Commits since v4.14.2:
84df2c24fb fix(ci): dedupe duplicated Agent param in monitor-ci.ps1 39a138a90f docs(plugin): fix stale facts and broken links in plugin-outside-dev guide 0ba4055235 feat(agents): add opencode as the second agent backend (dsh preferred) 54497eb8e1 docs(skill): keep browser4-cli SKILL.md under the 500-line cap b087cffa17 feat(coding): isolate agent run workspaces (mirror, shell sandbox, manifest, GC) 9bb163d107 Merge remote-tracking branch 'origin/4.14.x' into 4.14.x d8dfcfdd69 Merge remote-tracking branch 'origin/4.14.x' into 4.14.x 7841c20133 fix: close retry, rebuild, and swarm abort gaps found in review acbe2cb25f refactor(agent-runnable): replace CodingTaskDetector auto-guessing with an explicit --coding flag 78e896fb15 docs(dev): design coding-run workspace isolation and shell sandbox 751feea6f1 docs(dev): record the agent-run supervision round 5bd970355b refactor(chrome): split Browser4WebDriver into script, parser and storage layers 02f382646a fix(agent): make coding runs converge on a repository refactor ee10a3607b Merge branch '4.14.x' of https://github.com/platonai/Browser4 into 4.14.x 352e05ef82 Auto-bump version to 4.14.3-SNAPSHOT 9298a11044 Merge branch '4.14.x' of https://github.com/platonai/Browser4 into 4.14.x 1b986c2443 Merge branch '4.14.x' of https://github.com/platonai/Browser4 into 4.14.x 289c3935c6 Merge branch '4.14.x' of https://github.com/platonai/Browser4 into 4.14.x 9236948f94 Merge branch 'wip/local-4.14.x-20261003' into 4.14.x 7a51dbbdbf wip: local uncommitted work from 4.14.x before upstream pull (140 commits behind)
```

## Reproduce

`ash
# View all failed logs
gh run view 39971779173 --log-failed

# View the run in browser
gh run view 39971779173 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.1875391Z ##[group]Run if ! command -v rustup &>/dev/null; then
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.1875775Z if ! command -v rustup &>/dev/null; then
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.1876526Z   curl --proto '=https' --tlsv1.2 --retry 10 --retry-connrefused --location --silent --show-error --fail https://win.rustup.rs/x86_64 --output 'D:\a\_temp\rustup-init.exe'
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.1878094Z   'D:\a\_temp\rustup-init.exe' --default-toolchain none --no-modify-path -y
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.1878505Z   echo "$CARGO_HOME\bin" >> $GITHUB_PATH
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.1878773Z fi

══ block 2 ══
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.2611299Z     status=$?
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.2611492Z   fi
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.2611772Z   if [[ $attempt == 5 ]] || ! grep -Fq 'checksum failed for ' "$log" \
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.2612292Z       || ! grep -Fq 'this is likely due to an ongoing update of the official release server' "$log"; then
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.2612723Z     exit "$status"
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:40.2612941Z   fi

══ block 3 ══
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0546075Z ##[end-action id=__dtolnay_rust-toolchain.__run_8;outcome=success;conclusion=success;duration_ms=194]
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0550079Z ##[start-action display=Work around spurious network errors in curl 8.0;id=__dtolnay_rust-toolchain.__run_9]
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0687116Z ##[group]Run # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0687853Z # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0688475Z if rustc +"$toolchain" --version --verbose | grep -q '^release: 1\.7[01]\.'; then
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0688934Z   echo CARGO_HTTP_MULTIPLEXING=false >> $GITHUB_ENV

══ block 4 ══
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0550079Z ##[start-action display=Work around spurious network errors in curl 8.0;id=__dtolnay_rust-toolchain.__run_9]
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0687116Z ##[group]Run # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0687853Z # https://rust-lang.zulipchat.com/#narrow/stream/246057-t-cargo/topic/timeout.20investigation
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0688475Z if rustc +"$toolchain" --version --verbose | grep -q '^release: 1\.7[01]\.'; then
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0688934Z   echo CARGO_HTTP_MULTIPLEXING=false >> $GITHUB_ENV
Smoke test (Windows x64)	Setup Rust toolchain	2026-10-23T15:25:45.0689230Z fi

══ block 5 ══
Smoke test (Windows x64)	Run smoke test	2026-10-23T15:30:23.8502933Z No active session — creating a new one.
Smoke test (Windows x64)	Run smoke test	2026-10-23T15:30:24.0678170Z Session opened: 4ac55f05-3a38-4de2-9c47-431911a97897
Smoke test (Windows x64)	Run smoke test	2026-10-23T15:30:26.8107877Z ❌ Navigation failed
Smoke test (Windows x64)	Run smoke test	2026-10-23T15:30:26.8111223Z   URL: http://127.0.0.1:59961/test.html
Smoke test (Windows x64)	Run smoke test	2026-10-23T15:30:26.8112410Z   Session:

... (truncated — run gh run view 39971779173 --log-failed for full logs)

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

**Outcome:** implemented in the working tree in
`cli/scripts/smoke-test-runtime-bundle.sh` (plus its README). The failing
navigation itself is a runner-side transient, not a product regression, but the
smoke harness had two real defects that turned it into an undiagnosable red
release gate. **No commit was created in this session** — process execution was
blocked (see Verification).

**Categorisation: infrastructure / flake, not a test-assertion change or a code
regression.**

- The v4.14.2 → v4.14.3 diff (`3f8b5d8a50...84df2c24fb`) contains **no browser
  driver, CLI (Rust), release workflow or smoke-script changes**: 26 files, all
  agent/coding prompt code, PowerShell tooling, docs and version bumps.
- v4.14.2 passed all three smoke jobs (Windows `Run smoke test` 52 s). v4.14.3
  failed only Windows, at the first `open` step, 2.7 s after the session opened —
  an immediate navigation error, not a timeout.
- Linux and macOS ran the same bundle/CLI code and passed.
- **Probable trigger (hypothesis):** the `win25` runner image released
  2026-10-21 moved Google Chrome `155.0.8059.40` → `156.0.8078.48`
  (`actions/runner-images` release `win25/20261021.299`). The 10-22 v4.14.2 run
  and the 10-23 v4.14.3 run can land on different rollout generations of the
  same `windows-latest` label, which would make the first navigation fail on one
  Chrome build but not another. This is an environment change, not a repository
  change; the next failure's uploaded server log will confirm or refute it.

**Root cause of the missing diagnostics:** the script hard-coded
`SERVER_LOG_DIR="$TEMP_DIR/server-logs"`, overriding the job-level
`BROWSER4_SERVER_LOG_DIR=${{ runner.temp }}/browser4-server-logs` that the
`Upload server logs on failure` step archives. The failure annotation was
`No files were found with the provided path: D:\a\_temp/browser4-server-logs/`,
so the navigation error detail was lost with the ephemeral runner.

**Fix:**

1. Honour `BROWSER4_SERVER_LOG_DIR` when set (fall back to the temp dir for
   local runs), so the next failed run uploads the server startup log.
2. Retry the two navigation gates (`open` up to 3 attempts, `goto` up to 2)
   because navigation replaces the document and is safe to replay. `type` and
   `click` are deliberately not retried — they mutate the page.
3. Before a retry, `close` the failed session; the last `open` retry also runs
   `kill-all` so a wedged backend, not just a wedged tab, gets a fresh start.
4. Re-probe the Python fixture server before each retry and restart it if it
   died mid-run, so a dead fixture port is not mistaken for a browser fault.

A persistent regression still fails every attempt, so the gate is not weakened;
a one-shot runner hiccup no longer blocks the release.

**Verification:** static review of the bash (no shell available in this
session: every `pwsh` call, and a delegated subagent, failed at sandbox
provisioning with `SetNamedSecurityInfoW failed (Win32 5):
grantWrite(D:\workspace\Browser4\Browser4-4.14)`). To validate on a host with
shell access:

```bash
bash -n cli/scripts/smoke-test-runtime-bundle.sh
# then, with a built CLI + bundle archive:
BROWSER4_SERVER_LOG_DIR=/tmp/b4-smoke-logs \
  bash cli/scripts/smoke-test-runtime-bundle.sh <cli> <bundle> 0 300
# a failing run must now leave browser4-server-*.log under that directory
```

