# Issues: windows-click-stack-overflow

> **Source:** self-observed while adding the experience end-to-end round trip (`test_e2e_experience_web_roundtrip`) on `4.14.x` @ `a7ac3818a6`, Windows 11 / JDK 25 / debug CLI build | **Date:** 20261001-224111 | **Mode:** dev

## Scenario Background

### Task

The `experience save|query|list|deep-learn` commands had no layer that ever drove the CLI end to end
(they were marked `E2eCoverage::Excluded`). A new real-page scenario was written for them: drive the
`/experience` fixture with a real browser, read the expected value from the DOM, record the selectors
that actually worked with `experience save --facts`, then replay that knowledge in a fresh session using
only the selectors `experience query` returns.

The first attempt died at the very first `click` in the flow (exit code `-1073741571`,
`0xC00000FD` = `STATUS_STACK_OVERFLOW`). The crash turned out to be **unrelated to the experience
work**: the pre-existing `test_e2e_mouse_trusted_click` fails identically on this machine, and a
standalone CLI reproduces it on any page, any target. The new scenario now submits its fixture form with
`fill` + `press Enter` instead (trusted CDP key events, handled by the same page handler as the submit
button) and documents the two-line switch back to `click`.

### Execution Context

| Step | Command | Outcome |
|---|---|---|
| First hit | `cargo test --test e2e -- --scenario=test_e2e_experience_web_roundtrip --level EXTENDED --max-failures=0` | `open`, `eval` ×3 and `fill` succeed; the following `click` exits `-1073741571` with empty stdout/stderr except the overflow notice |
| Manual repro | `browser4-cli open "http://127.0.0.1:18099/experience-replay-fixture.html?nonce=abc"` → `fill "#search-box" "abc-target"` → `click "#search-button"` | `fill` prints the post-command snapshot; `click` prints nothing and aborts |
| Rule out the snapshot | `browser4-cli click "#search-button" --no-snapshot` | Same abort |
| Rule out the target | `browser4-cli click "#click-target"` (interactive fixture), `browser4-cli click "#search-box-legacy"` (disabled input) | Same abort for both |
| Controls that work | `fill`, `press Enter`, `hover`, `eval`, `open`, `goto` on the same session/page | All exit 0 |
| Control (pre-existing scenario) | `cargo test --test e2e -- --scenario=test_e2e_mouse_trusted_click --level EXTENDED --max-failures=0` | **FAILED** at `click #click-target`, `actual: -1073741571` |
| Backtrace attempt | `RUST_BACKTRACE=1 RUST_BACKTRACE_FULL=1 browser4-cli click "#click-target"` | Only `thread 'main' (…) has overflowed its stack`; no frames |
| Isolate from the backend | `docs-dev/fake-mcp-server.ps1` on 18077, then `click` / `hover` / `fill` / `press` / `eval` with the same payloads | `click` and `dblclick` overflow with an empty **and** a non-empty payload; the other four exit 0; the click sends no tool request at all |
| Pin the frame | temporary `eprintln!` tracing through `with_session` → `call_tool` → `call_tool_with_timeout` → `normalize_refs` → `resolve_ref` | last output is `resolve_ref: enter raw=#x`; the next statement is `regex::Regex::new(...)` |
| Bigger stack | `RUSTFLAGS="-C link-arg=/STACK:8388608" cargo build`, then the same click | **exit 0** — the depth is bounded, not a runaway loop |
| Optimized build | `cargo build --release`, then `click` / `dblclick` at the default 1 MB reserve | **exit 0** — release frames fit where debug frames do not |
| Environment | `browser4-cli --version` → `4.14.0-rc.6`, CLI built by `cargo test --test e2e --no-run` (debug profile) | Backend from the freshly assembled runtime bundle (rebuilt from this working tree) |

---

## Issues Found (2 issues)

### Issue 1: `click` aborts `browser4-cli` with a main-thread stack overflow on Windows (debug builds)

**Severity:** High for Windows development and the Windows e2e suite (debug profile); the released
release-profile binary is unaffected — see "Root Cause Analysis".

**Category:** Reliability

#### Status (2026-10-01)

All five items are settled — **1, 2, 3 and 4 implemented and verified**; 5 deliberately declined with a
rationale:

| # | Item | State |
|---|---|---|
| 1 | Hoist the element-ref regex out of `resolve_ref` (`OnceLock`) | ✅ `state.rs` — `ref_pattern()` |
| 2 | Drop the avoidable async layer from the click arm; box the follow bookkeeping | ✅ `main.rs` — `!follow` goes straight to `handle_tool_command`; follow is boxed at the call site *and* per inner call |
| 3 | Windows-sized main-thread stack reserve | ✅ `cli/browser4-cli/build.rs` — `/STACK:8388608` (msvc) / `-Wl,--stack,8388608` (gnu), Windows targets only; pinned by a PE-header unit test |
| 4 | Windows smoke gate in CI | ✅ `.github/workflows/nightly-cli.yml` — `windows-smoke` job (SMOKE set + this regression; every selected scenario is `requires_browser4: false`, so no Java/Chrome/Docker) |
| 5 | Switch the experience scenario back to `click` | ⏭️ deliberately not done — see item 5 below |

Verification (Windows, debug):

- the browser-free reproducer exits 0 for `click`, `dblclick`, `click e5`, `click --follow`,
  `dblclick --follow` (all of them overflowed before);
- the PE header now reports `SizeOfStackReserve = 8388608` for the debug binary, where a build made
  before the flag reports `1048576` — i.e. the reserve is what changed, and the default really was 1 MB;
- `cargo test --bin browser4-cli` green (1459 passed, incl. the new PE-header and `resolve_ref` tests);
- `--level=SMOKE --max-failures=0` green locally (7/7, the exact command the Windows job runs);
- the new mock scenario `test_e2e_mock_click_is_stack_safe` passes, and the pre-existing real-browser
  `test_e2e_mouse_trusted_click` passes again.

#### Reproduction

Fastest, browser-free (about a second):

```powershell
# 1) A fake MCP endpoint: readiness probe + a canned tool response
pwsh -NoProfile -File ./docs-dev/fake-mcp-server.ps1 -Port 18077

# 2) Any click on a debug build aborts
cli/browser4-cli/target/debug/browser4-cli.exe --server=http://127.0.0.1:18077 click "#x"
# stderr: thread 'main' (…) has overflowed its stack
# exit:   -1073741571  (0xC00000FD = STATUS_STACK_OVERFLOW)
# stdout: (empty)          hover/fill/press/eval against the same server exit 0
```

With a real session and a real page:

```powershell
# 1) Start a session on any page (any local page works; the fixture here is served by python -m http.server)
browser4-cli open "http://127.0.0.1:18099/mcp-tool-controller-interactive-fixture.html" --profile-mode=SEQUENTIAL

# 2) Any click aborts the process
browser4-cli click "#click-target"
# stderr: thread 'main' (340540) has overflowed its stack
# exit:   -1073741571  (0xC00000FD = STATUS_STACK_OVERFLOW)
# stdout: (empty)
```

The same happens with `--no-snapshot`, with a disabled target, and through the e2e harness:

```powershell
cd cli/browser4-cli
cargo test --test e2e -- --scenario=test_e2e_mouse_trusted_click --level EXTENDED --nocapture --max-failures=0
# assertion `left == right` failed: Command ["click", "#click-target"] failed (exit=-1073741571)
```

#### Expected Behavior

`click` returns the click result (and, when applicable, the navigation/new-tab follow-up), the way
`fill`, `press` and `hover` do on the same page and session.

#### Actual Behavior

The CLI aborts before printing anything at all — no banner, no error envelope, no snapshot:

```
exit code: -1073741571 (0xC00000FD)
stdout:    (empty)
stderr:    thread 'main' (350224) has overflowed its stack
```

Consequences on this machine (Windows):

- the whole click-based e2e family is red — `test_e2e_mouse_trusted_click` (pre-existing, Basic level),
  the mouse/drag/pointer scenarios that begin with a click, and any new scenario that needs a click;
- real usage is equally broken: `browser4-cli click <selector>` cannot be used at all;
- it is invisible to CI: the CLI e2e stage runs on Linux (`ci.yml` Docker job, `nightly-cli.yml`), while
  the crash is Windows-specific.

#### Root Cause Analysis

**Stack exhaustion in the debug build's nested async call chain — not infinite recursion.** The depth is
bounded: it is simply deeper than the *debug* build fits into the **1 MB main-thread stack reserve that
Windows gives an executable by default** (Linux/macOS give ~8 MB), and `click`/`dblclick` sit one async
layer deeper than the commands that still work.

Isolation used a fake MCP endpoint with no browser at all
(`docs-dev/fake-mcp-server.ps1`, serving `/actuator/health`, `/mcp/tools` and `/mcp/call-tool`):

| Command | Empty text payload | Non-empty payload |
|---|---|---|
| `click` / `dblclick` | **overflow** | **overflow** |
| `hover`, `fill`, `press`, `eval` | exit 0 | exit 0 |

So the response payload is irrelevant, and the crash is entirely client-side — the click never even sends
its tool request (the fake server logs only the `/actuator/health` + `/mcp/tools` readiness probes).

Temporary `eprintln!` tracing pinned the last executed statement:

```
[trace] call_tool_with_timeout: ENTER tool=browser_click
[trace] clear_tool_error_meta: done
[trace] normalize_refs: resolving ref=#x
[trace] resolve_ref: enter raw=#x        ← next statement is regex::Regex::new(...); no further output
```

`resolve_ref` (`cli/browser4-cli/src/state.rs:1445`) compiles
`regex::Regex::new(r"(?i)^e(\d+)$")` **on every call** — a stack-hungry call in unoptimized code, and the
deepest frame of the chain. The chain for click is:

```
run → handle_navigation_action → handle_tool_command → handle_tool_command_with_options
    → with_session → with_session_paginated → closure future → call_tool → call_tool_with_result
    → call_tool_with_timeout → normalize_refs → resolve_ref
```

Nothing is boxed, so in a debug build every `async fn` state machine is inlined into its caller's frame;
`handle_navigation_action` (reached only by `click`/`dblclick`, `main.rs:25966-25980`) is the extra layer
that `hover`/`fill`/`press` do not have — they run the same chain minus one frame and stay just under the
limit.

Two controlled experiments confirm the mechanism (both on the same debug sources):

```powershell
# 1) Bigger reserve → the same click succeeds (8 MB, the Linux default)
$env:RUSTFLAGS = "-C link-arg=/STACK:8388608"; cargo build
target/debug/browser4-cli.exe --server=http://127.0.0.1:18077 click "#x"   # exit 0, full output

# 2) Optimized build at the default 1 MB reserve → succeeds as well
cargo build --release
target/release/browser4-cli.exe --server=http://127.0.0.1:18077 click "#x" # exit 0
target/release/browser4-cli.exe --server=http://127.0.0.1:18077 dblclick "#x" # exit 0
```

Reach: `cargo run` / `cargo test --test e2e` (debug) on Windows — the documented development workflow and
the whole Windows click e2e family; **not** the released binary, which is built in release profile. Linux
CI cannot see it for two reasons (8 MB stacks and an optimized or differently-sized chain), and
`nightly-cli.yml` is ubuntu-only.

#### Code Pointer

- `cli/browser4-cli/src/main.rs:25966-25980` — `"click" | "dblclick"` → `handle_navigation_action`
- `cli/browser4-cli/src/main.rs:2809-2910` — `handle_navigation_action` / `verify_click_navigation`
- `cli/browser4-cli/src/http.rs:123-189` — `is_navigation_triggering_tool`, `timeout_for_tool`

#### AI Suggested Improvement

Ordered by value per line changed — items 1–4 are **done** (see Status above):

1. ✅ **Hoist the regex out of `resolve_ref`** (`state.rs:1445`) into a `OnceLock<Regex>` — it used to
   recompile `(?i)^e(\d+)$` on every ref-bearing tool call, and that compilation is exactly where the
   stack ran out.
2. ✅ **Drop the avoidable async layer**: the `"click" | "dblclick"` arm (`main.rs:25966`) now calls
   `handle_tool_command` directly when `!follow`, and the follow bookkeeping is boxed (at the call site
   and at each of its own tool calls — both are required, verified empirically). The boxings stay even
   though item 3 now provides headroom, because they also reduce the *debug* footprint of a path that is
   otherwise near the limit.
3. ✅ **Linux-sized stack on Windows** (`cli/browser4-cli/build.rs`): `cargo:rustc-link-arg=/STACK:8388608`
   for `msvc`, `-Wl,--stack,8388608` for `gnu`, emitted only when `CARGO_CFG_TARGET_OS=windows`. Costs
   address space only. `main.rs::test_windows_binary_reserves_a_linux_sized_main_thread_stack` parses the
   PE header of the test binary and fails if the reserve drops below 8 MiB, so deleting the flag cannot
   pass unnoticed.
4. ✅ **Windows smoke gate** (`.github/workflows/nightly-cli.yml` → `windows-smoke`, 03:00 UTC): runs
   `--level=SMOKE --max-failures=0` plus `--scenario=test_e2e_mock_click_is_stack_safe`. Every SMOKE
   scenario declares `requires_browser4: false` (mock server / CLI state only), so the job needs no Java,
   Chrome or Docker — it avoids the CLI-managed-backend flakiness that killed the old three-platform
   matrix job while covering the Windows-only half of the CLI. Raising it to `--level=BASIC` later is a
   one-word change.
5. ⏭️ **The experience scenario keeps `fill` + `press Enter` on purpose.** With items 1–2 in place its
   `click` variant would pass, but the code-side margin measured by the reproducer is empirical (removing
   either `Box::pin` flips `--follow` back over the limit), and
   `test_e2e_experience_web_roundtrip` must not fail for a CLI-side stack reason: the click path now has
   its own coverage (`test_e2e_mock_click_is_stack_safe`, `test_e2e_mouse_trusted_click`). The two-line
   switch back (round 1 `["press", "Enter", EXPERIENCE_SEARCH_BOX_SELECTOR]` →
   `["click", EXPERIENCE_SEARCH_BUTTON_SELECTOR]`, round 2 `["press", "Enter", &search_input]` →
   `["click", &search_button]`) stays documented in the scenario's doc comment; the fixture already routes
   both entry points through one submit handler.

Regression tests: `docs-dev/fake-mcp-server.ps1` reproduces the original crash in about a second with no
browser, `test_e2e_mock_click_is_stack_safe` pins it in the suite (and in the Windows job), and the
PE-header unit test pins the build flag — `browser4-cli --server=http://127.0.0.1:18077 click "#x"` must
exit 0 on Windows/debug.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `--use-maven-startup` / `BROWSER4_E2E_USE_MAVEN_STARTUP` is a dead knob

**Severity:** Low
**Category:** Test Infrastructure

#### Reproduction

```powershell
# The harness passes --use-maven-startup to every CLI child and then asserts a message
grep -rn "Starting server via Maven" cli/browser4-cli
# cli/browser4-cli/tests/e2e/mod.rs:2587  (the assertion)
# → no producer in cli/browser4-cli/src
```

and, when the knob is used:

```powershell
$env:BROWSER4_E2E_USE_MAVEN_STARTUP = "1"
cd cli/browser4-cli
cargo test --test e2e -- --scenario=test_e2e_mouse_trusted_click --level EXTENDED --max-failures=0
# mod.rs:2596-2603 asserts stderr contains "Starting server via Maven spring-boot:run"
```

#### Expected Behavior

Either the flag makes the CLI start the backend from Maven (`spring-boot:run`) and says so, or the
knob does not exist.

#### Actual Behavior

`--use-maven-startup` is still defined (`tests/e2e/constants.rs:9`), still threaded through
`E2ECtx.use_maven_startup`, still passed to every CLI child (`mod.rs:2786`), and still asserted
(`mod.rs:2588-2603`) — but nothing in `cli/browser4-cli/src` produces the message. The setting is a
no-op that turns a debugging aid into a failing assertion, and it is the documented way to make a
scenario exercise the checked-out backend sources.

#### Root Cause Analysis

The CLI-side implementation was removed (or never landed) while the harness side stayed, so the two
halves drifted. Nobody noticed because the knob is off by default and no workflow sets it.

#### Code Pointer

- `cli/browser4-cli/tests/e2e/constants.rs:9` — `USE_MAVEN_STARTUP_FLAG`
- `cli/browser4-cli/tests/e2e/mod.rs:122-132, 2786, 2588-2603` — env read, flag injection, assertion
- `cli/browser4-cli/src/daemon.rs` — backend launch path (no Maven branch)

#### AI Suggested Improvement

- Decide the intent: re-implement the Maven startup (so a scenario can test source changes without
  rebuilding the runtime bundle) or delete the knob (flag, env var, ctx field, assertion and the
  `HELP_TEXT` entry) so it cannot mislead.
- Meanwhile, document in `docs/TESTING.md` that `--force-rebuild-bundle` is the supported way to make a
  scenario test checked-out backend sources — and note that it also skips Maven when
  `browser4-apps/browser4-bundle/target/Browser4Bundle.jar` already exists.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
