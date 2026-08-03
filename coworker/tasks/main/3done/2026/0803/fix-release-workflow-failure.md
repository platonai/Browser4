Title: Fix release.yml failure for tag v4.12.3
Description: The release.yml run 30784741379 (tag v4.12.3) has failed. Minimal error messages extracted from failed job logs below. Please reproduce and fix the root cause.
Prompt: The release.yml run 30784741379 for tag v4.12.3 failed.

## Reproduce
`ash
gh run view 30784741379 --log-failed
gh run view 30784741379 --web
`

## Errors

══ block 1 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:41:04.3220589Z   CARGO_INCREMENTAL: 0
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:41:04.3220830Z   CARGO_TERM_COLOR: always
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:41:04.3221082Z   CACHE_ON_FAILURE: false
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:41:04.3221386Z   BROWSER4_E2E_SERVICE_URL: http://localhost:8182
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:41:04.3221748Z   BROWSER4_E2E_FIXTURE_HOST: host.docker.internal

══ block 2 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.4464567Z note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.6482925Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 close-all
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.7008937Z FAILED (9.57s) - Expected missing-session guidance in output:
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.7009655Z 
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.7011510Z 🔐 Session required

══ block 3 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.7021307Z     7. browser4 session cleanup (close-all): 0.20s
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.7021909Z     8. browser4 service remains healthy after close-all: 0.05s
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.7023028Z 2026-08-03 04:42:06.700694552 +00:00 progress [------------------------] 1/135 (0%) test_e2e_session_lifecycle => FAILED | start=04:41:57 | end=04:42:06 | elapsed=9s
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:06.7024061Z 2026-08-03 04:42:06.700950638 +00:00 testing test_e2e_newly_opened_session_shows_active ... 
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:42:11.7573622Z browser4-cli command completed in 5s with exit code 0: --server=http://localhost:8182 open http://host.docker.internal:42573/interactive --profile-mode=SEQUENTIAL --interact-level=FASTEST | sessionId=b599cb3c-a043-4815-aead-188ad621666b

══ block 4 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:44:39.5879010Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval void 0
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:44:39.7884509Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 close-all
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:44:39.8381968Z FAILED (13.51s) - Expected undefined, empty string, or quoted-empty for `void 0`, got: ""
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:44:39.8383320Z 💡 Tip: Eval returned empty/null. The page context may be stale.
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:44:39.8383947Z Try re-navigating with: goto <url>

══ block 5 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:44:39.8394164Z     15. browser4 session cleanup (close-all): 0.20s
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:44:39.8394858Z     16. browser4 service remains healthy after close-all: 0.05s
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T04:44:39.8396011Z 2026-08-03 04:44:39.838040560 +00:00 progress [##----------------------] 9/135 (6%) test_e2e_eval_return_types => FAILED | start=04:44:26 | end=04:44:39 | elapsed=13s
Build core artifacts and Docker image	Run browser4-cli

... (truncated — full logs: gh run view 30784741379 --log-failed)

## Instructions
1. Examine the error messages above to understand what failed
2. Reproduce the failure by checking the relevant code paths
3. Fix the root cause — do not just silence the error
4. Verify the fix: build succeeds and relevant tests pass
5. Commit with a conventional-commit message
