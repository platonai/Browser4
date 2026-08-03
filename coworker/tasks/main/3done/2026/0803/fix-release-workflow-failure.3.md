Title: Fix release.yml failure for tag v4.12.3
Description: The release.yml run 30787713648 (tag v4.12.3) has failed. Minimal error messages extracted from failed job logs below. Please reproduce and fix the root cause.
Prompt: The release.yml run 30787713648 for tag v4.12.3 failed.

## Reproduce
`ash
gh run view 30787713648 --log-failed
gh run view 30787713648 --web
`

## Errors

══ block 1 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:46:59.9666621Z   CARGO_INCREMENTAL: 0
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:46:59.9666869Z   CARGO_TERM_COLOR: always
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:46:59.9667129Z   CACHE_ON_FAILURE: false
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:46:59.9667449Z   BROWSER4_E2E_SERVICE_URL: http://localhost:8182
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:46:59.9667829Z   BROWSER4_E2E_FIXTURE_HOST: host.docker.internal

══ block 2 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:01.7048853Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval window.innerWidth.toString()
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:01.9057232Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval window.innerHeight.toString()
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:02.1065474Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval --await new Promise(resolve => setTimeout(() => resolve('async-done'), 50))
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:02.3072999Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 eval --await Promise.resolve({status: 200, ok: true})
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:02.5080698Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 close

══ block 3 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:02.7575540Z 2026-08-03 05:51:02.757312405 +00:00 progress [##----------------------] 11/135 (8%) test_e2e_eval_await_command => ok | start=05:50:50 | end=05:51:02 | elapsed=12s
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:02.7577033Z 2026-08-03 05:51:02.757575427 +00:00 testing test_e2e_agent_run_live_or_missing_llm_key ... 
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:05.8079437Z browser4-cli command completed in 3s with exit code 1: --server=http://localhost:8182 agent run Navigate to http://host.docker.internal:34205/interactive and report the page title.
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:06.0088133Z browser4-cli command completed in 0s with exit code 0: --server=http://localhost:8182 close-all
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:06.0580681Z ok (3.30s)

══ block 4 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:54.2325980Z ok (1.30s)
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:54.2327180Z 2026-08-03 05:51:54.232482092 +00:00 progress [##----------------------] 14/135 (10%) test_e2e_crawl_submission_live => ok | start=05:51:52 | end=05:51:54 | elapsed=1s
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:54.2328364Z 2026-08-03 05:51:54.232730131 +00:00 testing test_e2e_wait_for_state_failure_modes ... 
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:51:59.0849774Z browser4-cli command completed in 4s with exit code 0: --server=http://localhost:8182 open http://host.docker.internal:34205/interactive --profile-mode=SEQUENTIAL | sessionId=092214d9-7d03-4815-982d-591fcb215479
Build core artifacts and Docker image	Run browser4-cli E2E Tests	2026-08-03T05:52:03.4873114Z browser4-cli command completed in 4s with exit code 0: --server=http://localhost:8182 goto http://host.docker.internal:34205/interactive

══ block 5 ══
Build core artifacts and Docker image	Run browser4-cli E2E Tests	202

... (truncated — full logs: gh run view 30787713648 --log-failed)

## Instructions
1. Examine the error messages above to understand what failed
2. Reproduce the failure by checking the relevant code paths
3. Fix the root cause — do not just silence the error
4. Verify the fix: build succeeds and relevant tests pass
5. Commit with a conventional-commit message
