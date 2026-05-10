# browser4-cli external-service E2E implementation plan

## Summary

The current `browser4-cli` E2E harness still assumes it can launch its own local Browser4 jar and serve test fixtures on `127.0.0.1`. That works for host-only runs, but it does not match the CI goal described in the task: validate the CLI against an already-running Browser4 service, including when Browser4 runs inside Docker and the Rust fixture server runs on the host.

## Problem

When `ci.yml`, `nightly.yml`, and `release.yml` run, the `browser4-cli` tests need to pass before the pipeline can proceed. The current setup has two coupled problems:

1. `sdks/browser4-cli/tests/e2e.rs` always resolves a local jar from `BROWSER4_E2E_JAR_PATH` or `browser4-app/browser4-agents/target/Browser4.jar`, then restarts a fresh Browser4 instance for each browser-backed scenario.
2. The fixture server binds to `127.0.0.1`, and the tests hand Browser4 URLs like `http://127.0.0.1:<port>/interactive`. That is only reachable from the host itself, not from a Browser4 service running inside a Docker container.
3. The current workflow ordering still runs the CLI E2E step before the Docker image is started and health-checked, so CI is not yet validating the intended containerized service path.

Because of those assumptions, the test harness is fragile in Dockerized CI and does not cleanly support the desired "external Browser4 service" mode.

## Solution

- Refactor `e2e.rs` so browser-backed scenarios can connect to an already-running Browser4 service via environment configuration, while preserving the current local-jar fallback for developer runs.
- Split the fixture server's bind address from the URL that tests hand to Browser4. The server should be able to listen on a host-reachable interface while still generating a Docker-reachable hostname for Browser4 to visit.
- Update CI workflows to start Browser4 first, wait for health, then run `browser4-cli` E2Es against that external service.
- Make Docker-to-host networking explicit instead of relying on `127.0.0.1`. On GitHub-hosted Linux runners, the most portable approach is to provide the container a host alias such as `host.docker.internal` via `--add-host=host.docker.internal:host-gateway`, then have the test harness generate fixture URLs with that hostname.

## Implementation plan

### 1. Add explicit external-service configuration to `e2e.rs`

Introduce a small config layer for the browser-backed scenarios:

- `BROWSER4_E2E_SERVICE_URL` as the primary external Browser4 endpoint.
- `BROWSER4_E2E_SERVER_URL` as a backward-compatible alias.
- Keep `BROWSER4_E2E_JAR_PATH` and the current jar lookup only as the fallback path when no external service URL is provided.

Recommended behavior:

1. If `BROWSER4_E2E_SERVICE_URL` is set, use it.
2. Else if `BROWSER4_E2E_SERVER_URL` is set, use it.
3. Else fall back to the existing local-jar startup path.

That keeps local development simple while making CI deterministic.

### 2. Separate fixture bind address from fixture public URL

Refactor `FixtureServer` so it no longer hardcodes `127.0.0.1` for both concerns.

Suggested config:

- `BROWSER4_E2E_FIXTURE_BIND_HOST` (default `127.0.0.1` for host-only runs, `0.0.0.0` for Docker-reachable runs)
- `BROWSER4_E2E_FIXTURE_PUBLIC_HOST` (default `127.0.0.1`, overridden to `host.docker.internal` in Docker CI)

Implementation details:

- Bind the TCP listener to `{bind_host}:0`.
- Keep using the dynamically assigned port.
- Build `fixture_base_url` from `{public_host}:{port}` rather than from the listener address.

This keeps the fixture server reachable from the container without breaking host-local runs.

### 3. Preserve scenario boundaries

The browser-backed scenarios should use the external Browser4 service when configured:

- `test_e2e_session_and_navigation`
- `test_e2e_interaction_console_and_export`
- `test_e2e_mouse_and_dialog`
- `test_e2e_tab_commands`

The mock-backed scenario should remain self-contained:

- `test_e2e_agent_and_collective_commands`

That means `run_named_scenario` / `restart_browser4()` should become mode-aware so external-service runs do not try to spawn or restart a jar between scenarios, while the mock scenario can still overwrite `ctx.browser4_base_url` with `MockBrowser4Server::start()`.

### 4. Update workflow orchestration

For `ci.yml`, `nightly.yml`, and `release.yml`:

1. Build the application image.
2. Start the Browser4 container.
3. Wait for health.
4. Run `browser4-cli` E2Es with environment overrides, instead of expecting the test harness to boot the server itself.

Workflow env for the CLI step should include at least:

- `BROWSER4_E2E_SERVICE_URL=http://localhost:8182`
- `BROWSER4_E2E_FIXTURE_BIND_HOST=0.0.0.0`
- `BROWSER4_E2E_FIXTURE_PUBLIC_HOST=host.docker.internal`

This matches the runtime topology:

- CLI test process runs on the GitHub runner host.
- Browser4 runs in Docker and is exposed on host port `8182`.
- Browser4 reaches the host-served fixture pages through the explicit host alias.

### 5. Extend Docker startup support for host aliasing

`./.github/actions/start-application/action.yml` should gain a way to pass extra `docker run` arguments or at minimum inject:

- `--add-host=host.docker.internal:host-gateway`

Without that, the containerized Browser4 service will not reliably resolve `host.docker.internal` on Linux runners.

If the action should stay generic, expose a dedicated input such as `extra_docker_args` and let the workflows supply the host alias there.

### 6. Update test-running documentation and scripts

Refresh the browser4-cli test documentation to describe both execution modes:

- Local fallback mode: local jar auto-started by the harness.
- External-service mode: Browser4 started separately and injected through env vars.

The minimum touch points are:

- The header comment in `sdks/browser4-cli/tests/e2e.rs`
- `bin/test.ps1` help text or inline comments for `browser4-cli`

## Validation plan

After implementation, verify the two supported paths explicitly:

1. **Local fallback path**
   - Run the browser4-cli E2Es with no external-service env vars.
   - Confirm the harness still boots Browser4 from the local jar and all browser-backed scenarios pass.
2. **External-service path**
   - Start Browser4 separately.
   - Run the browser4-cli E2Es with `BROWSER4_E2E_SERVICE_URL` plus fixture host overrides.
   - Confirm Browser4 can open the host-served fixture pages and the interaction/tab scenarios still pass.
3. **Workflow path**
   - Verify the reordered workflow uses the Docker-started service, not a locally spawned jar.
   - Confirm the Docker container can resolve the chosen host alias and load `/interactive` and `/other`.

## Risks and watch-outs

- The biggest regression risk is accidentally breaking local developer runs while enabling the external-service mode. Keep the local fallback path intact and covered.
- Do not route the mock `agent/co` scenario through the real Browser4 service; it exists to validate CLI request shaping, not browser automation.
- Avoid hardcoding `host.docker.internal` inside the Rust harness. Keep it configurable so non-GitHub environments can supply a different host alias or IP.
- Reordering the workflows matters as much as the Rust refactor. If the CI step still runs before the container is healthy, the harness changes alone will not solve the task.

## References

- `sdks/browser4-cli/tests/e2e.rs`
- `sdks/browser4-cli/src/daemon.rs`
- `.github/workflows/ci.yml`
- `.github/workflows/nightly.yml`
- `.github/workflows/release.yml`
- `.github/actions/start-application/action.yml`

---

**Plan Date**: 2026-04-06
**Prepared By**: Copilot CLI
**Status**: Ready for implementation
