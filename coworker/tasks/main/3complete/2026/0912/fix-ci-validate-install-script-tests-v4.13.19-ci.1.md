Title: Fix ci.yml failure for tag v4.13.19-ci.1
Description: The ci.yml workflow run 34712389151 (tag v4.13.19-ci.1) failed. Investigate the root cause, apply a fix, verify with tests, and commit.
Prompt: The workflow $WorkflowName (run 34712389151) for tag $Tag failed in CI.

## Context

- **Workflow:** ci.yml
- **Tag:** v4.13.19-ci.1
- **Run ID:** 34712389151
- **Run URL:** https://github.com/platonai/Browser4/actions/runs/34712389151



## Reproduce

`ash
# View all failed logs
gh run view 34712389151 --log-failed

# View the run in browser
gh run view 34712389151 --web
`

## Error Diagnostics

## Error Details

══ block 1 ══
ci-build	Validate install script tests	2026-09-12T18:54:00.3163158Z [0;36m============================================[0m
ci-build	Validate install script tests	2026-09-12T18:54:00.3164227Z 
ci-build	Validate install script tests	2026-09-12T18:54:00.3164789Z [0;31mFAILURES: 1 tests failed[0m
ci-build	Validate install script tests	2026-09-12T18:54:00.3190245Z ##[error]Process completed with exit code 1.

══ block 2 ══
ci-build	Validate install script tests	2026-09-12T18:54:00.3164227Z 
ci-build	Validate install script tests	2026-09-12T18:54:00.3164789Z [0;31mFAILURES: 1 tests failed[0m
ci-build	Validate install script tests	2026-09-12T18:54:00.3190245Z ##[error]Process completed with exit code 1.

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

**Outcome: fixed and committed — `f7a94c46d9`.**

### Diagnosis

`ci.yml` run 34712389151 (`ci-build`, ubuntu-latest) failed the new step
`Validate install script tests` at exactly one assertion: `no non-ASCII bytes`
(`65 / 66 passed`). This is **not** a product regression and **not** a flake —
the assertion is correct and the installer really did contain a non-ASCII byte.

Root cause: `cli/scripts/install-browser4-cli.sh`, in `check_symlinks()`, had

```
  # Check in install dir first — anything here is ours
```

a UTF-8 em dash (3 bytes, `e2 80 94`). It dates from 2026-07-21 (blame
`87e85c6e41c`) and is identical on `origin/4.14.x` and `origin/main`. The
assertion only started *executing* in this round because of two recent commits:

- `1a62bd71b4` fixed the suite's `test()` helper (`local fn="$2"; if "$fn"` ->
  `shift; "$@"`), which had been dropping `-c "..."` and making all 53
  assertions pass without running;
- `871933cdd7` wired the suite into every CI round, so the Linux runner's
  UTF-8 locale ran the byte check for the first time.

### Local false-green (important)

This machine cannot detect the violation and cannot verify the fix with the
suite's own command: Windows Git Bash + zh-CN codepage decodes the file as GBK,
where the em dash bytes fall outside `\x00-\x7F` in a way `grep -P` does not
report. Control experiment: the assertion returns 0 even when run against a
copy with 3 confirmed non-ASCII bytes. The author's "66 / 66" in §11.4 and CI's
"65 / 66" are therefore both truthful — they are different checks.

### Fix

One line: the em dash became an ASCII `-`. The file is now zero bytes above
0x7F, so on the POSIX/UTF-8 runner `grep -Pn '[^\x00-\x7F]'` cannot match and
the assertion passes. The test was deliberately **not** weakened, skipped, or
deleted — the file was made to satisfy it.

### Verification

- `python` byte count of the installer -> `0` bytes > 0x7F.
- `bash cli/scripts/tests/install-browser4-cli.tests.sh` -> `66 / 66 passed`.
- `bash cli/scripts/tests/wait-for-npm-version.tests.sh` -> `All 11 tests passed`.
- `bash -n cli/scripts/install-browser4-cli.sh` -> OK.
- Cross-checked `release.yml`: `Run install-browser4-cli.tests.sh` (Linux) runs
  the same suite, so this commit also clears the release-side red point.

### Scope note

`b4w.sh` (15), `b4w.ps1` (3051) and `cli/scripts/smoke-test-runtime-bundle.sh`
(78) also contain non-ASCII, but no suite asserts ASCII for them (each
installer suite checks only its own installer), so they are outside this CI
gate and were left untouched.

Written up in `docs-dev/copilot/ci-stabilization-4.13.x.md` section 12.
