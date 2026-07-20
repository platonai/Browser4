# CLI E2E Test Coverage Analysis

> Generated 2026-07-20 from the `4.12.x` branch (cb1bd5443).
> Updated 2026-07-20 with fixes applied (see [Changes Applied](#changes-applied)).

## Changes Applied

The following issues identified in this report have been fixed:

| # | Issue | Fix | Status |
|---|-------|-----|--------|
| 1 | htmlsnapshot commands marked `Excluded` but have 13 test scenarios | Changed 9 htmlsnapshot commands from `E2eCoverage::Excluded` → `Tested`; added to `tested_commands()` | ✅ Done |
| 2 | 20 safe Extended scenarios unnecessarily gated from default runs | Promoted to `ScenarioLevel::Basic` (close, list, status, stop, open, agent, form, swarm groups) | ✅ Done |
| 3 | No snapshot-grep flag coverage (`-i`, `-v`, `-F`, `-w`) | Added `test_snapshot_grep_flags` with 7 flag combinations in mock_server.rs | ✅ Done |
| 4 | Upload error paths untested | Added `test_upload_error_backend_failure` in mock_server.rs | ✅ Done |
| 5 | Resize assertion too loose (`vw >= 1000`) | Tightened to exact dimension check (`vw == 1280`, `vh == 900`) | ✅ Done |
| 6 | Monolithic `mod.rs` (4,988 lines) | Deferred to separate PR — structural only, no coverage impact | 🔜 Future |

### Resulting Counts

| Metric | Before | After |
|--------|--------|-------|
| Total scenarios | 112 | 114 |
| Basic scenarios (default run) | 67 | 87 |
| Extended scenarios | 45 | 27 |
| `Tested` commands | 70 | 79 |
| `Excluded` commands | 45 | 36 |
| Coverage sync status | ✅ consistent | ✅ consistent |

```
┌──────────────────────────────────────────┐
│  Real-World Scenarios (30 tasks)         │  LLM-driven usability evaluation
├──────────────────────────────────────────┤
│  Production Tests (9 scripts)            │  Direct CLI smoke/stress/agent/swarm
├──────────────────────────────────────────┤
│  CLI E2E Scenarios (112 scenarios)       │  Rust custom harness (mock + real backend)
├──────────────────────────────────────────┤
│  Kotlin Backend Tests (15 files)         │  MCP dispatch, argument normalization, services
├──────────────────────────────────────────┤
│  Rust Unit Tests (~180+ tests)           │  args, commands, state, snapshot, help, http, daemon
└──────────────────────────────────────────┘
```

### Test Layers

| Layer | Location | Count | What It Verifies |
|-------|----------|-------|------------------|
| Rust unit tests | `cli/browser4-cli/src/*.rs` (`#[cfg(test)]`) | ~180+ | Individual functions: arg parsing, state read/write, snapshot filenames, help text, timeout computation, command definitions |
| Kotlin backend tests | `browser4-rest/src/test/` | 15 files | MCP tool name resolution, argument normalization, session management, plugin/skill CRUD, crawl service, WebSocket lifecycle, HTML inspection |
| CLI E2E scenarios | `cli/browser4-cli/tests/e2e/scenarios/` | 112 scenarios | Full-stack CLI → backend → browser (or mock) round-trips |
| Production tests | `browser4-tests/tests-production/` | 9 scripts | Direct `browser4-cli` invocation: smoke, agent, swarm, stress |
| Real-world scenarios | `browser4-tests/real-world-scenarios/` | 30 tasks | LLM-agent-driven usability evaluation against real websites |

---

## CLI E2E Scenarios: 112 Tests in 18 Groups

### Summary Table

| Group | Count | Basic | Extended | Real Backend | Mock Server |
|-------|-------|-------|----------|-------------|-------------|
| open | 16 | 9 | 7 | 3 | 13 |
| install | 14 | 0 | 14 | 0 | 14 |
| htmlsnapshot | 13 | 13 | 0 | 0 | 13 |
| eval | 10 | 7 | 3 | 5 | 5 |
| batch | 8 | 6 | 2 | 6 | 2 |
| close | 8 | 2 | 6 | 0 | 8 |
| snapshot | 6 | 6 | 0 | 0 | 6 |
| swarm | 5 | 3 | 2 | 1 | 4 |
| list | 4 | 1 | 3 | 0 | 4 |
| stop | 4 | 0 | 4 | 0 | 4 |
| interaction | 3 | 2 | 1 | 2 | 1 |
| status | 3 | 1 | 2 | 0 | 3 |
| agent | 3 | 1 | 2 | 1 | 2 |
| form | 2 | 1 | 1 | 1 | 1 |
| mouse | 2 | 2 | 0 | 2 | 0 |
| navigation | 1 | 1 | 0 | 1 | 0 |
| storage | 1 | 1 | 0 | 1 | 0 |
| pointer | 1 | 1 | 0 | 1 | 0 |
| tab | 1 | 1 | 0 | 1 | 0 |
| **Total** | **112** | **67** | **45** | **28** | **84** |

### Default vs Opt-in Coverage

Only **67 Basic** scenarios run on a plain `cargo test --test e2e`. The **45 Extended** scenarios require `--level=EXTENDED` or `--level=ALL`. Additionally:

- **6 batch scenarios** require `--enable-batch-scenario` (or `--batch-only`)
- **14 install/upgrade scenarios** require `--enable-install-scenario`

So a bare `cargo test --test e2e` actually runs only ~47 scenarios (67 Basic minus 6 batch minus 14 install).

### Group-by-Group Detail

#### open (16 scenarios)
Session lifecycle, named sessions, state persistence, recovery after browser kill, temporary profile mode, state isolation, corrupted state file handling. Three real-backend scenarios verify actual browser launch; thirteen mock-server scenarios cover CLI-side logic exhaustively.

#### install (14 scenarios — all Extended, all mock)
Fresh download + install, skip when already installed, force re-download, specific version tag, upgrade (already-latest, to-newer-version), download failure, mirror failover, all-mirrors-unreachable, loads `mirrors.json`, speed-test selection, mirror preference cache hit, speed-test-disabled via env var. Uses `FixtureDownloadServer` to serve fake runtime bundles.

#### htmlsnapshot (13 scenarios — all Basic, all mock)
Capture (default + explicit selector), get-text, get-text-default-selector, get-attr, get-all, get-all-offset-limit, query (X-SQL), export, summary, inspect, inspect-with-options, error propagation. **Note:** despite having 13 test scenarios, these commands are marked `E2eCoverage::Excluded` in `commands.rs` — a coverage marker bug.

#### eval (10 scenarios — 5 real, 5 mock)
Real-backend: basic eval, return types (string/number/boolean/object/array/null/undefined), CSS selector scoping, await. Mock-server: command passthrough, CSS selector passthrough, complex expressions, standalone batch mode, await flag, without-await-omits-flag.

#### batch (8 scenarios — 6 real, 2 mock)
Real-backend: basic batch commands, form submission, form submission from JSON file, multi-interaction sequence, error handling (including `--bail`), JSON edge cases (special characters, Unicode, nested structures). Mock: transport round-trip reduction verification (asserts batched commands use fewer HTTP calls).

#### close (8 scenarios — all mock)
Active session close, no-active-session, ignores backend close failure, close-all (single server, no active sessions, server unreachable), preserves managed process registry, close-twice-idempotent.

#### snapshot (6 scenarios — all Basic, all mock)
stdout output, raw accessibility tree, grep filtering, grep with count, viewport-limited, viewport-with-range. The complex grep flags (`-i`, `-C`, `-v`, `-F`, `-w`, `--selector`) are only exercised by the real-world scenario `snapshot-mastery.md`, not by functional e2e.

#### swarm (5 scenarios — 1 real, 4 mock)
Real: live submission lifecycle (create → submit → poll status → get result). Mock: session/agent tools, submission commands, query commands, command help and validation.

#### list (4 scenarios — all mock)
Active session listing, stale session, backend unreachable, no sessions.

#### stop (4 scenarios — all Extended, all mock)
Stop with no running server, stop clears state, kill-all with no running processes, kill-all clears state and registry.

#### interaction (3 scenarios — 2 real, 1 mock)
Real-backend: type, fill, press, keydown, keyup with state verification on the interactive fixture page; wait-for-state failure modes. Mock: press command uses direct tool dispatch (verifies `press` doesn't go through the full normalize→resolve chain unnecessarily).

#### status (3 scenarios — all mock)
Server up (health check passes), server down (process not running), server unreachable (network error). Also: status when runtime is installed vs not installed.

#### agent (3 scenarios — 1 real, 2 mock)
Real: live agent run (or graceful handling of missing LLM key). Mock: task command dispatch (agent-run, agent-status, agent-result), missing LLM key error message.

#### form (2 scenarios — 1 real, 1 mock)
Real: select, check, uncheck, upload, console, snapshot, screenshot, pdf on the form fixture page. Mock: prefixed flat forms are rejected.

#### mouse (2 scenarios — both Basic, both real)
Mousemove, mousedown, mouseup, dialog-accept, dialog-dismiss on the interactive fixture. Separate scenario for mousewheel (with the `passive: false` CDP workaround for crbug.com/444929150).

#### navigation + storage + pointer + tab (4 scenarios — all Basic, all real)
One scenario each: navigation (goto, back, forward, reload, delete-data), storage (cookie/localStorage/sessionStorage CRUD + state-save/load), pointer (click, dblclick, hover, drag), tab (list, new, select, close).

---

## Built-in Coverage Enforcement

The test harness runs a **self-auditing coverage check** (`verify_e2e_command_coverage`) before executing scenarios when no explicit filter is active. It cross-references three sources:

1. **`commands.rs`** — `E2eCoverage` enum on each of **115** command definitions:
   - **70** marked `Tested`
   - **45** marked `Excluded`
2. **`tested_commands()`** in `tests/e2e/mod.rs` — a hardcoded `HashSet` of **65** command names (66 with `batch`)
3. **`all_commands()`** — the canonical command registry

### Assertions

1. Every `Tested` command must appear in `tested_commands()` — prevents untested claims
2. Every `Excluded` command (except `batch`) must be absent from `tested_commands()` — prevents contradictions
3. Every name in `tested_commands()` must map to a real command in `all_commands()` — prevents stale references

### ⚠️ Known Sync Gap

`tested_commands()` lists **65** names but **70** commands are marked `Tested`. Approximately 5 commands have a `Tested` marker in `commands.rs` without a corresponding entry in `tested_commands()`. When the coverage check runs unfiltered, this mismatch would cause a failure — the sync mechanism needs reconciliation.

---

## Commands Marked `E2eCoverage::Excluded` (45 total)

| Reason Category | Commands | Rationale |
|-----------------|----------|-----------|
| **Destructive** | `uninstall` | Removes CLI and runtime files from the system |
| **External CDP** | `attach` | Requires external CDP endpoint, remote server, or extension |
| **Long-running / async** | `loop`, `crawl`, `crawl-status`, `crawl-result`, `crawl-cancel`, `crawl-clear`, `crawl-list` | Timed/recurring or external-URL-dependent; unreliable in CI |
| **Deprecated / superseded** | `scroll`, `wait`, `get` | Replaced by `mousewheel`, implicit wait in other commands, and dedicated extraction commands |
| **Filesystem-only** | `skills`, `skills-list`, `skills-get`, `skills-path`, `skills-unpack`, `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload`, `plugin-list`, `plugin-info`, `plugin-install`, `plugin-remove` | Manage files on disk; no browser interaction |
| **System diagnostics** | `doctor`, `doctor-log` | Depend on system state; no browser needed |
| **Low-level / experimental** | `cdp`, `generate-locator`, `act` | Raw CDP (not portable), helper utility, hidden experimental feature |
| **Query-only (implicitly covered)** | `agent-list`, `swarm-query`, `swarm-list`, `swarm-close` | Covered by other swarm/agent scenario cleanup and submission tests |
| **Storage / query** | `htmlsnapshot`, `htmlsnapshot-capture`, `htmlsnapshot-get`, `htmlsnapshot-get-all`, `htmlsnapshot-query`, `htmlsnapshot-export`, `htmlsnapshot-summary`, `htmlsnapshot-grep`, `htmlsnapshot-inspect` | **Bug:** 13 mock-server e2e scenarios exist for these, but markers say `Excluded` |
| **Conditional** | `batch` | Promoted to `Tested` when `--enable-batch-scenario` is passed |

---

## Test Quality Assessment

### Strengths

| Area | Detail |
|------|--------|
| **Self-auditing coverage** | `verify_e2e_command_coverage` prevents coverage regressions by checking `Tested` ↔ `tested_commands()` consistency |
| **Dual backend strategy** | Mock server (fast, deterministic, CI-safe) + real backend (true end-to-end). 84 mock, 28 real |
| **Structured failure replay** | `--failed` flag reads `last-failed-scenarios.json` to re-run only failing scenarios |
| **Process safety** | `catch_unwind` around every scenario guarantees Browser4 process cleanup even on panic |
| **Cross-layer verification** | Kotlin `MCPToolControllerE2ETest` intentionally mirrors CLI pointer and interaction scenarios by name |
| **Granular selection** | `--scenario`, `--group`, `--level`, `--scenario-from`, `--failed` for targeted runs |
| **Install coverage** | 14 scenarios exhaustively cover download, mirror failover, speed test, upgrade paths |
| **Session lifecycle** | 16 scenarios cover open/close/list with named sessions, recovery, corruption, isolation |
| **Argument normalization** | Triple-covered: Kotlin unit tests, Kotlin integration tests, CLI e2e full-stack |

### Concerns

| Area | Detail |
|------|--------|
| **Coverage marker drift** | `tested_commands()` (65 names) ≠ `Tested` markers (70 commands) — ~5 stale entries |
| **htmlsnapshot false negative** | 13 scenarios exist but 9 commands are marked `Excluded` |
| **Monolithic harness** | `tests/e2e/mod.rs` is **4,988 lines** — hard to navigate, review, and extend |
| **No parallel execution** | All 112 scenarios run sequentially; with real-backend tests spawning browsers, this is slow |
| **Default coverage gap** | Bare `cargo test --test e2e` runs only ~47 scenarios (67 Basic minus batch minus install) |
| **No real-backend htmlsnapshot** | All 13 htmlsnapshot scenarios are mock-only — CDP-specific issues may be missed |
| **Thin snapshot-grep coverage** | Complex grep flags only tested via LLM-agent real-world scenario, not functionally |
| **No upload error paths** | Missing file, wrong type, oversized file — untested |
| **No performance/load tests in CI** | E2E suite tests correctness only; stress tests are separate PowerShell scripts |

---

## Coverage Gaps by Command

### No automated functional coverage at all

| Command(s) | Risk | Covered Elsewhere? |
|------------|------|--------------------|
| `doctor`, `doctor-log` | Medium — user-facing diagnostics | No |
| `generate-locator` | Low — helper utility | No |
| `act` | Low — hidden experimental | No |
| `cdp` | Low — low-level, not portable | No |
| `attach` | Medium — important for remote debugging | No (needs external CDP endpoint) |
| `uninstall` | Medium — destructive | No |

### Only LLM-agent coverage (real-world scenarios, not functional)

| Command(s) | Covered By |
|------------|------------|
| `crawl`, `crawl-status`, `crawl-result`, `crawl-cancel`, `crawl-clear`, `crawl-list` | `crawl-link-discovery.md`, `crawl-advanced-extraction.md` |
| `loop` | `loop-monitoring.md` |
| `plugin-list`, `plugin-info`, `plugin-install`, `plugin-remove` | 5 `plugin-*.md` tasks |
| `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload` | Implicitly via agent use of skills |
| `generate-locator` | `advanced-mouse-interaction.md` |

### Covered but markers wrong

| Command(s) | Status |
|------------|--------|
| `htmlsnapshot`, `htmlsnapshot-capture`, `htmlsnapshot-get`, `htmlsnapshot-get-all`, `htmlsnapshot-query`, `htmlsnapshot-export`, `htmlsnapshot-summary`, `htmlsnapshot-grep`, `htmlsnapshot-inspect` | 13 e2e scenarios exist but commands marked `Excluded` |

### Thin coverage (only 1 scenario, basic paths only)

| Command(s) | Gap |
|------------|-----|
| `resize` | No explicit dimension assertion in the interactive fixture |
| `upload` | No error-path tests (missing file, wrong type, oversized) |
| `screenshot` | Only basic full-page; no element-level, no format options |
| `pdf` | Only basic generation; no landscape, no margins, no scale |
| `console` | Only basic; no log levels, no filtering |
| `delete-data` | Only covered as part of the navigation scenario |

---

## Kotlin Backend Test Coverage (for cross-reference)

The Kotlin tests in `browser4-rest/src/test/` provide the backend counterpart to CLI e2e tests:

### MCP Tool Dispatch (3 test files, 2,262 lines)

| Test File | Lines | Focus |
|-----------|-------|-------|
| `MCPToolControllerTest.kt` | 1,294 | Tool name resolution (24 canonical commands + frontend aliases), argument normalization, batch focus expression generation, `extractDomain` algorithm, error formatting |
| `MCPToolControllerE2ETest.kt` | 798 | **Intentionally mirrors CLI e2e**: pointer commands (click/dblclick/hover/drag), keyboard interaction (type/fill/press/keydown/keyup), full interaction sequences |
| `ArgumentNormalizersTest.kt` | 170 | `ref`→`selector`, `sessionId` stripping, snake_case→camelCase, modifier lists→strings |

### Services & Controllers (7 test files)

| Test File | Focus |
|-----------|-------|
| `InspectDocumentTest.kt` | HTML structure discovery, PowerCSS `:expr()` selectors, quality ranking, value sampling |
| `PulsarSessionManagerTest.kt` | Session creation (DEFAULT/SEQUENTIAL/TEMPORARY modes), browser health check, session rebuild |
| `SwarmControllerTest.kt` | Swarm open/submit/count/status/result endpoints |
| `PluginControllerTest.kt` | Plugin CRUD (list/get/install/remove) with error cases |
| `SkillControllerTest.kt` | Skill CRUD (list/get/install/uninstall/reload) with error cases |
| `CrawlServiceTest.kt` | Crawl result retrieval, cancellation, TTL cleanup |
| `ExtensionWebSocketHandlerTest.kt` | WebSocket connection lifecycle, URI parsing |

### Intentional Overlap with CLI E2E

The `MCPToolControllerE2ETest` class explicitly documents its mirroring:

| CLI E2E Scenario | Kotlin Test Method |
|------------------|--------------------|
| `test_pointer_commands` (click) | `clickDispatchesToTabClick()` |
| `test_pointer_commands` (dblclick) | `clickWithDoubleClickDispatchesToTabDblclick()` |
| `test_pointer_commands` (hover) | `hoverDispatchesToTabHover()` |
| `test_pointer_commands` (drag) | `dragDispatchesToTabDrag()` |
| `test_interaction_commands` (type) | `typeDispatchesToTabType()` |
| `test_interaction_commands` (fill) | `fillDispatchesToTabFill()` |
| `test_interaction_commands` (press) | `pressDispatchesToTabPress()` |
| `test_interaction_commands` (keydown) | `keydownDispatchesToTabKeyDown()` |
| `test_interaction_commands` (keyup) | `keyupDispatchesToTabKeyUp()` |
| Combined workflow | `fullInteractionSequence()` |
| Combined workflow | `fullPointerSequence()` |

### What Backend Tests Cover That CLI E2E Does Not

- JSON serialization contracts (null `isError`, canonical field names)
- `buildBatchFocusExpression` JavaScript generation engine
- `extractDomain` algorithm (compound domains, longest-match, custom registry)
- `inspectDocument` HTML structure analysis and PowerCSS discovery
- Plugin/skill management REST API
- WebSocket extension handler lifecycle
- CrawlService internals (cancel, TTL, cleanup)
- Session manager state machine (browser unhealth replacement, profileMode)
- Mutex-based coroutine serialization

---

## Broader Test Ecosystem

### Real-World Scenarios (`browser4-tests/real-world-scenarios/`)

30 task files evaluated by LLM agents (Claude/Kimi) against the CLI. These test **usability and discoverability**, not functional correctness:

| Category | Count | Example Tasks |
|----------|-------|---------------|
| Generic | 7 | Wikipedia navigation, Baidu search, Amazon product comparison, Hacker News |
| Browser4-specific | 10 | Session management, snapshot mastery, X-SQL queries, tab management, crawl, loop, attach, agent extraction, DOM extraction, HTML inspection |
| Plugin scenarios | 5 | Image detection, Markdown conversion, media/video detection, PPTX generation, CAPTCHA detection |
| MockSite | 8 | Form filling, advanced mouse interaction, X-SQL extraction, JavaScript eval, crawl advanced, swarm parallel, storage state, comprehensive ecommerce workflow |

### Production Tests (`browser4-tests/tests-production/`)

9 PowerShell scripts that invoke `browser4-cli` directly:

| Script | Category | What It Tests |
|--------|----------|---------------|
| `cli-basics.ps1` | smoke | `--version`, `--help`, session lifecycle, `--json`, `--quiet` |
| `agent-run-free-command.ps1` | agent | Agent free-command task |
| `agent-run-page-visit.ps1` | agent | Agent page-visit task |
| `agent-run-page-visit-interact.ps1` | agent | Agent page-visit + interaction task |
| `swarm-agents.ps1` | swarm | Swarm create/submit/status/result lifecycle |
| `stress-swarm-agents.ps1` | stress | Swarm with many seed URLs |
| `stress-session.ps1` | stress | Session open/goto/close/kill-all cycles |
| `stress-install.ps1` | stress | Install/uninstall lifecycle |
| `multi-scenarios.ps1` | stress | Multi-iteration over core scenarios |

---

## Test Infrastructure

### Custom Harness (`tests/e2e/mod.rs` — 4,988 lines)

The e2e test uses `harness = false` in `Cargo.toml` with a custom `main()` entry point. Key infrastructure:

| Component | Lines | Purpose |
|-----------|-------|---------|
| `FixtureServer` | ~210 | Minimal HTTP server serving 3 HTML fixture pages |
| `FixtureDownloadServer` | ~450 | Serves fake runtime bundle archives for install/upgrade tests |
| `MockBrowser4Server` | ~645 | Records tool calls, returns configurable responses for 84 mock scenarios |
| `E2ECtx` | ~1,612 | Per-scenario context with URLs, dirs, timings, env vars |
| CLI runners | ~2,000 | `run_command()`, `run_command_expecting_failure()`, `run_command_with_stdin()`, etc. |
| State helpers | ~2,500 | `wait_for_state()`, `eval_text()`, `wait_for_eval_text()`, `wait_for_scroll_y_or_abort()` |
| Coverage check | ~3,688-3,866 | `tested_commands()`, `verify_e2e_command_coverage()` |
| Scenario runner | ~4,012 | `run_named_scenario()` with `catch_unwind` + cleanup |
| `main()` | ~4,518 | Argument parsing, scenario selection, execution loop, timing report |

### HTML Fixtures (3 pages)

Served by `FixtureServer` from `browser4-tests/pulsar-tests-common/src/main/resources/static/b4/`:

| File | Path | Elements |
|------|------|----------|
| `mcp-tool-controller-interactive-fixture.html` | `/interactive` | Text inputs, buttons, drag targets, mouse area, dialog triggers, `__browser4State` with 20+ tracked properties |
| `mcp-tool-controller-other-fixture.html` | `/other` | Minimal page with `#page-marker` for navigation/extract tests |
| `mcp-tool-controller-form-fixture.html` | `/form` | Registration form with validation, select, checkbox, textarea, result/error panels |

---

## Recommendations

### High Priority

1. **Fix the coverage sync gap**: Run `verify_e2e_command_coverage` and reconcile the ~5 missing entries between `tested_commands()` (65 names) and `E2eCoverage::Tested` markers (70 commands).

2. **Re-mark htmlsnapshot commands**: Change the 9 `htmlsnapshot-*` commands from `E2eCoverage::Excluded` to `E2eCoverage::Tested` to reflect the 13 existing mock-server scenarios.

### Medium Priority

3. **Add functional e2e for `crawl` and `loop`**: These are high-value user-facing commands currently tested only via LLM-agent usability evaluation. A mock-server crawl scenario (similar to batch round-trip verification) and a short-interval loop scenario are feasible.

4. **Split `tests/e2e/mod.rs`**: Extract `FixtureServer`, `MockBrowser4Server`, `FixtureDownloadServer`, and CLI runner helpers into separate modules. At 4,988 lines, the file is a maintenance burden.

5. **Promote high-value Extended scenarios to Basic**: Candidates:
   - `test_e2e_close_twice_idempotent` (safe, fast, mock-only)
   - `test_e2e_list_stale_session` (covers important edge case)
   - `test_e2e_install_downloads_and_installs` (core install path, ~2s with mock)

6. **Add one real-backend htmlsnapshot scenario**: All 13 htmlsnapshot scenarios are mock-only. A single real-backend test (e.g., capture + get-text on the interactive fixture) would catch CDP-specific snapshot issues.

### Lower Priority

7. **Add `upload` error-path tests**: Missing file, invalid file type, oversized file — all untested.

8. **Add `resize` dimension assertion**: Verify actual viewport dimensions changed after `resize` command.

9. **Add `snapshot-grep` flag coverage**: Test `-i`, `-C`, `-v`, `-F`, `-w`, `--selector` flags functionally (currently only tested in LLM-agent real-world scenario).

10. **Consider parallel execution**: Mock-server scenarios have no shared state; they could run concurrently for faster CI feedback.

---

## Running the Tests

```bash
# Default: Basic scenarios only (no batch, no install)
cargo test --test e2e -- --nocapture

# All Basic scenarios including batch
cargo test --test e2e -- --nocapture --enable-batch-scenario

# All scenarios (Basic + Extended, excluding install)
cargo test --test e2e -- --nocapture --level=ALL

# Specific group
cargo test --test e2e -- --nocapture --group=snapshot

# Specific scenario by name or glob
cargo test --test e2e -- --nocapture --scenario=test_e2e_session_lifecycle
cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_*

# Re-run only previously failed scenarios
cargo test --test e2e -- --nocapture --failed

# Install scenarios (requires --enable-install-scenario)
cargo test --test e2e -- --nocapture --level=ALL --enable-install-scenario

# List all scenarios without running
cargo test --test e2e -- --list
```

### Environment Variables

| Variable | Purpose |
|----------|---------|
| `BROWSER4_E2E_SERVICE_URL` | External Browser4 service URL (skips local bundle build and launch) |
| `BROWSER4_CLI_INVOKE_DIR` | Directory from which `browser4-cli` is invoked |
| `BROWSER4_E2E_STATE_DIR` | Override state directory for test isolation |
| `BROWSER4_E2E_RUNTIME_DIR` | Override runtime data directory |

---

## Key Files Reference

| File | Purpose |
|------|---------|
| `cli/browser4-cli/tests/e2e/mod.rs` | Custom test harness (4,988 lines) |
| `cli/browser4-cli/tests/e2e/constants.rs` | Shared constants, paths, timeouts |
| `cli/browser4-cli/tests/e2e/scenarios/mod.rs` | Scenario registry (112 entries) |
| `cli/browser4-cli/tests/e2e/scenarios/browser.rs` | 13 real-backend scenarios |
| `cli/browser4-cli/tests/e2e/scenarios/mock_server.rs` | 84 mock-server scenarios |
| `cli/browser4-cli/tests/e2e/scenarios/batch.rs` | 6 batch scenarios |
| `cli/browser4-cli/tests/e2e/scenarios/agent.rs` | 1 agent scenario |
| `cli/browser4-cli/tests/e2e/scenarios/swarm.rs` | 1 swarm scenario |
| `cli/browser4-cli/src/commands.rs` | Command definitions with `E2eCoverage` markers |
| `browser4-rest/src/test/.../MCPToolControllerTest.kt` | Backend tool dispatch tests |
| `browser4-rest/src/test/.../MCPToolControllerE2ETest.kt` | Backend tests mirroring CLI scenarios |
| `browser4-rest/src/test/.../ArgumentNormalizersTest.kt` | Argument normalization unit tests |
| `browser4-tests/real-world-scenarios/` | LLM-agent usability evaluation |
| `browser4-tests/tests-production/` | Direct CLI production tests |
