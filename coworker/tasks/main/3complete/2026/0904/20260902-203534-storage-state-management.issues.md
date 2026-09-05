# Issues: storage-state-management

> **Source:** `20260902-203534-storage-state-management.full.md` | **Date:** 20260902-203534 | **Mode:** dev

## Scenario Background

### Task

**All 17 task steps were completed successfully**, with one required workaround (step 2's `--path /` flag — see Issues 1–3).

- Steps 1–2: Navigated to `http://localhost:18080/generated/interactive-1.html` (an existing DEFAULT session was already on this page; the backend is the local dev build, server version 4.13.13-SNAPSHOT). The `session_id` cookie was set with `--domain localhost --httpOnly --secure`; `--path /` was omitted because the flag is broken (every attempt failed with an internal backend error). The resulting cookie was verified via `cookie-list` to have exactly the requested scoping: `domain: localhost`, `path: "/"`, `httpOnly: true`, `secure: true` (path defaults to `/` when unset), so the end state matches the task requirement.
- Step 3: `theme` cookie set with `--sameSite Lax --expires 1788985438` (timestamp computed as now + 1 week via `date -d "+1 week" +%s`). Verified: `theme=dark`, `sameSite: Lax`, `expires: 1788985438.0`.
- Steps 4–9 (cookie lifecycle): `cookie-list` showed both cookies; `cookie-list --domain localhost` returned exactly the two; `cookie-get theme` returned `"value": "dark"`; `cookie-delete session_id` removed it (confirmed by list); `cookie-clear` emptied the jar (confirmed `[]`).
- Steps 10–12 (localStorage): `localstorage-set user_prefs '{"lang":"en","tz":"UTC"}'` stored the JSON verbatim (round-trip confirmed via `localstorage-list` and `localstorage-get`); delete and clear both worked.
- Steps 13 (sessionStorage): `sessionstorage-set visit_count 1` → list/get/delete/clear all worked.
- Steps 14–16 (state roundtrip): set `test_cookie` + a localStorage key, `state-save .test-sessions/browser_state.json` produced the documented JSON shape; cleared cookies+localStorage to `[]`; `state-load` restored both (summary: `cookies: 1, origins: 1, localStorage entries: 1`), verified via list commands.
- Step 17: `browser_state.json` deleted; cookie jar and localStorage left empty.

### Execution Context

**Preparation:** verified repo root, created `.test-sessions/`, ran `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` and `skills/browser4-cli/references/storage-state.md` completely, plus `./b4w.ps1 cookie-set --help` / `state-save --help` / `state-load --help`.

**Commands (chronological):** `goto` → cookie-set probes (basic; with `--domain`; `--domain --path /`; `--path /`; `--httpOnly`; `--secure`; `--path=/`; `--sameSite Lax`; `--expires <ts>`) → `cookie-list` → cookie-delete ×5 (probe cleanup) → `cookie-set session_id … --domain localhost --httpOnly --secure` → `cookie-set theme dark --domain localhost --sameSite Lax --expires 1788985438` → `cookie-list` → `cookie-list --domain localhost` → `cookie-get theme` → `cookie-delete session_id` → `cookie-list` → `cookie-clear` → `cookie...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: cookie-set fails with an opaque internal error whenever --path is used (documented example broken)

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
(or any cookie-set that includes --path, e.g. `cookie-set t v --path /` or `--path=/`; the exact example printed by `./b4w.ps1 cookie-set --help` fails verbatim).

#### Expected Behavior

The cookie should be set with the requested domain, path, HttpOnly and Secure attributes; output `Cookie set: session_id`. This is the documented behavior in `cookie-set --help` and in skills/browser4-cli/references/storage-state.md.

#### Actual Behavior

ERROR: browser_load_storage_state failed: Invalid cookie fields help: tab.loadStorageState(state: String) ... Restores cookies plus localStorage from a JSON string previously returned by tab.saveStorageState(). The raw binary exits 1. Flag-isolation matrix: cookie-set succeeds with no flags, with --domain, --httpOnly, --secure, --sameSite Lax, and --expires <ts> individually and combined; it fails in every combination that includes --path (with url-derived domain or with --domain localhost, `--path /` or `--path=/`). No cookie is set when the call fails. The cookie cannot be set with a non-root path at all, i.e. path scoping is entirely broken.

#### Root Cause Analysis

CLI handle_cookie_set (cli/browser4-cli/src/main.rs:4238-4240) adds "path" to the cookie map inside the storage-state JSON posted to the browser_load_storage_state MCP tool. Backend Browser4WebDriver.loadStorageState (browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:955-988) parses the state and calls normalizeStorageStateCookie (line 292-313), which passes "path" through, then browserProtocol.setCookies(cookies) (line 959). The literal message "Invalid cookie fields" exists in no repo source or scanned runtime JAR, so it is thrown inside a dependency (likely the CDP setCookies/validation layer) when a cookie entry carries "path" together with url/domain. Requires follow-up: decompile the pulsar/cdt dependency's setCookies used by the dev backend, and reconcile with the e2e test cli/browser4-cli/tests/e2e/scenarios/browser.rs:338 that asserts --path= works.

#### Code Pointer

`cli/browser4-cli/src/main.rs:4238 (handle_cookie_set adds path); browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:292 (normalizeStorageStateCookie) / :955 (loadStorageState)`

#### AI Suggested Improvement

- Fix the backend/dependency path so storage-state cookies carrying an explicit "path" are accepted (check pulsar BrowserProtocol.setCookies validation of path + url/domain combos)
- Add a CLI-side e2e/unit test that exercises cookie-set with --path / --domain localhost --httpOnly --secure and asserts success, matching the help example
- Until fixed, if --path cannot be honored, validate client-side and fail fast with a clear message instead of the backend error

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Core product defect, high severity justified; reproduced at the CLI layer (main.rs:4238 adds `path` to the storage-state cookie) and the backend accepts `path` in normalization but the downstream pulsar `setCookies` validation rejects the domain+path combo. Fix at the pulsar/backend boundary as suggested, and ship the CLI regression test matching the help example.

---

### Issue 2: b4w.ps1 dev wrapper always exits 0, masking every CLI failure

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 nosuchcommand_xyz; echo $?
./b4w.ps1 cookie-set x 1 --path / >/dev/null; echo $?
Both print an error but exit 0. The raw binary behaves correctly: cli/browser4-cli/target/debug/browser4-cli.exe nosuchcommand_xyz exits 2, and a failing cookie-set exits 1.

#### Expected Behavior

The wrapper must propagate the CLI's exit code so scripts, CI and AI agents can detect failures (`exit $LASTEXITCODE`, as the script already does in its own delegation path at line 62).

#### Actual Behavior

Every command invoked as ./b4w.ps1 <command> exits 0 even when the CLI printed `ERROR: ...` or `Error: Unknown command: ...`. A caller chaining commands with && or checking $? cannot distinguish success from failure — a failed cookie-set silently looks like success.

#### Root Cause Analysis

b4w.ps1 ends its CLI-invocation section with `Set-Location $OriginalCwd` (line 839) and never calls `exit $LASTEXITCODE`; the last executed statement is a successful cmdlet, so pwsh reports 0. Earlier delegation branches (line 61-62, 100, 155) correctly use `exit $LASTEXITCODE`, so this is an oversight in the main path.

#### Code Pointer

`b4w.ps1:823-839 (capture $LASTEXITCODE after the & $Exe / cargo run invocation, then `exit $code` after restoring CWD)`

#### AI Suggested Improvement

- After the CLI invocation, store `$cliExit = $LASTEXITCODE`, restore the original CWD, then `exit $cliExit`
- Add the same treatment to the `cargo run` fallback branch (cargo propagates the child exit code)
- Add a smoke check (e.g. in bin/test.ps1 or CI) asserting `./b4w.ps1 nosuchcommand; $? -ne 0`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed at code level — the wrapper's CLI-invocation path ends with `Set-Location $OriginalCwd` and never exits with `$LASTEXITCODE`, so every failure is masked. This is the highest-leverage fix of the batch (one line, unblocks reliable scripting/CI for everything else, including the regression test requested in Issue 1); also cover the `cargo run` fallback branch.

---

### Issue 3: Documented cookie-set examples that use --path cannot work; docs mislead first-time users

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run either documented example:
1) `./b4w.ps1 cookie-set --help` -> Examples: `browser4-cli cookie-set session abc123 --domain localhost --path / --httpOnly --secure`
2) skills/browser4-cli/references/storage-state.md, Cookies section: `browser4-cli cookie-set session abc123 --domain example.com --path / --httpOnly --secure --sameSite Lax`

#### Expected Behavior

Documented examples should reflect working invocations (or be removed until the underlying defect is fixed).

#### Actual Behavior

Both examples fail with the internal error described in Issue 1, and nothing in the docs warns that path scoping is unsupported. A first-time user following the docs (as this evaluation did, per the task's explicit `--path /` instruction) hits the failure immediately.

#### Root Cause Analysis

help.rs hard-codes example lines for cookie-set that include --path (cli/browser4-cli/src/help.rs:2506), and storage-state.md:102 shows the same pattern. The examples predate or ignore the backend rejection of cookie entries with a path field (see Issue 1).

#### Code Pointer

`cli/browser4-cli/src/help.rs:2506; skills/browser4-cli/references/storage-state.md:102`

#### AI Suggested Improvement

- Fix Issue 1 first, then re-run the documented examples to confirm
- If path support cannot be shipped immediately, replace the --path examples with working variants (--domain + flags) and add a note that path currently defaults to /
- Add a doc/help test that executes every example shown in --help output against the mock server

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] Real documentation defect, but it is the doc-side symptom of Issue 1 — apply the cheap interim mitigation now (replace the `--path` examples with working variants plus a "path defaults to /" note), then re-verify once Issue 1 lands. The suggested help-example doc-test against the mock server is a worthwhile improvement to fold in.

---

### Issue 4: Cookie error reporting leaks backend internals and gives no hint about the invalid field

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 cookie-set t v --path /
Observe the full message: `ERROR: browser_load_storage_state failed: Invalid cookie fields help: tab.loadStorageState(state: String)  Restores cookies plus localStorage from a JSON string previously returned by tab.saveStorageState().`

#### Expected Behavior

A user-actionable error such as: `cookie-set failed: the --path option could not be applied (cookie rejected by the browser backend). Try without --path (cookies default to path "/").` The backend tool name, signature and help text should not leak into the user-facing error.

#### Actual Behavior

The error dumps the internal MCP tool name (browser_load_storage_state), a fragment of backend validation text ("Invalid cookie fields"), and the backend tool's signature/description. It gives no indication which cookie field was invalid, that --path is the trigger, or what to do instead.

#### Root Cause Analysis

The CLI (handle_cookie_set in main.rs) forwards the raw backend WebDriverException message unmodified into its ERROR output, and the backend exception message itself embeds tool-spec help text ("help: tab.loadStorageState(...)"). No client-side validation exists to catch invalid option combinations before the round trip.

#### Code Pointer

`cli/browser4-cli/src/main.rs:4278-4286 (error propagation in handle_cookie_set); backend error formatting in the tool-executor layer that appends "help:" + tool spec`

#### AI Suggested Improvement

- Sanitize/map backend error strings in the CLI: extract the root cause sentence and drop embedded tool signatures/spec text
- Validate cookie fields client-side (name/value non-blank, path format, sameSite enum, expires parseable) and report the offending option by name
- Include the offending flag in the message: "option '--path' was rejected: ..."

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Error hygiene is independent of Issue 1 and remains valuable once `--path` works, since raw backend WebDriverException text (tool name, signature, "help:" fragment) leaks on every failure path. Prefer the client-side validation + sanitization suggested, including naming the offending flag — but keep the fallback mapping so unexpected backend errors are still trimmed to their root sentence.

---

### Issue 5: cookie-get/cookie-list serialize expires as a float (1788985438.0) and results are unordered

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 cookie-set theme dark --domain localhost --expires 1788985438
./b4w.ps1 cookie-get theme
./b4w.ps1 cookie-list
Run cookie-list twice and compare row order.

#### Expected Behavior

expires should be the integer Unix timestamp (seconds) that the docs and the --expires flag semantics promise (`--expires takes a Unix timestamp`), and repeated lists should be deterministic (e.g. sorted by name) for scriptable consumption.

#### Actual Behavior

expires is emitted as `1788985438.0` (JSON float) in both cookie-list and cookie-get, while the CLI accepted the value as an integer; two consecutive cookie-list runs returned the same two cookies in different orders.

#### Root Cause Analysis

Backend normalization converts expires via toDoubleOrNull (Browser4WebDriver.normalizeStorageStateCookie, line 304) and the CDP cookie model stores it as a double, which the CLI prints verbatim; list ordering comes from CDP's Network.getAllCookies, which has no stable order.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/Browser4WebDriver.kt:304 (expires as Double); cli cookie-list formatting in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Emit expires as an integer when the value is whole (seconds since epoch) in the CLI/backend serialization
- Sort cookie-list output deterministically (by domain, path, name) before printing
- Add a regression test asserting the integer shape of expires in cookie-list output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate scriptability defect — the CLI accepts `--expires` as an integer but emits `1788985438.0`, and ordering varies between runs. Small fix (integer emission when whole, deterministic sort before printing) with a regression test; can batch with Issue 6 as a formatting/polish change.

---

### Issue 6: state-save/state-load print file paths with mixed Windows/Unix separators

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 state-save .test-sessions/browser_state.json
Observe: `Storage state saved: D:\workspace\Browser4\Browser4-4.13\.test-sessions/browser_state.json` (same for state-load).

#### Expected Behavior

A single consistent path format — e.g. `D:\workspace\Browser4\Browser4-4.13\.test-sessions\browser_state.json` on Windows (forward slashes are also acceptable if used consistently).

#### Actual Behavior

The printed path concatenates a backslash root (`D:\workspace\Browser4\Browser4-4.13\`) with the user-supplied forward-slash relative path (`.test-sessions/browser_state.json`). Harmless visually, but the inconsistent separators are confusing to users and can break naive path parsers in scripts.

#### Root Cause Analysis

The CLI resolves the default/current directory via a Windows-style absolute path and appends the raw user-supplied filename without normalizing separators (path join at print/display time in state-save/state-load handlers in main.rs).

#### Code Pointer

`cli/browser4-cli/src/main.rs (state-save / state-load output formatting)`

#### AI Suggested Improvement

- Normalize the joined path with the platform separator (or PathBuf display) before printing
- Reuse the same helper for the saved-file message and the summary line

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Trivial cosmetic defect with a one-line fix (normalize via `PathBuf` display/canonicalization before printing); the mixed separators can genuinely break naive path parsers in scripts. Batch with Issue 5; no urgency but no reason to defer.

---

## Overall Assessment

**Completion Status:** Successful - all 17 task steps completed. The cookie, localStorage, sessionStorage, and state save/load workflows all functioned end-to-end against the local dev backend (4.13.13-SNAPSHOT). One workaround was required: the --path / flag on cookie-set is broken (Issues 1-3), so it was omitted and the resulting cookie was verified via cookie-list to carry the exact required scoping (domain=localhost, path="/", httpOnly, secure) since CDP defaults path to "/". End state: browser_state.json deleted, cookie jar and localStorage empty.

**Success Rate:** 95%

**Issues Found:** 6

**Major Blockers:** None that prevented completion. The --path flag of cookie-set is fully broken (any use fails with an opaque backend error), which blocked the literal instruction in step 2 and makes non-root cookie paths impossible to set; a verified workaround (omit --path; path defaults to "/") kept the task on track. Separately, the b4w.ps1 dev wrapper masks all CLI exit codes, so failures are silent to scripts using the prescribed ./b4w.ps1 invocation.

**Most Confusing Aspects:** 1) cookie-set with the documented example flags fails with a message that looks like an internal crash ('browser_load_storage_state failed: Invalid cookie fields help: tab.loadStorageState(state: String)...') and gives no clue that --path is the culprit. 2) Every command, including ones printing ERROR, exits 0 through ./b4w.ps1, so a first-time user cannot tell whether a storage operation actually succeeded (the failed cookie-set from the docs example looks like success to a script). 3) When cookie-set succeeds the output is minimal ('Cookie set: session_id') with no attribute summary, so users must run cookie-list to confirm httpOnly/secure/expires actually applied.

**Most Valuable Improvements:** 1) Fix the backend rejection of cookie entries with a path field and restore the documented cookie-set --path behavior (highest value: it is the only way to set non-root-path cookies and its documented example is broken). 2) Make b4w.ps1 propagate the CLI exit code so failures are detectable. 3) Map backend error strings to user-actionable messages that name the offending option. 4) Add help-example conformance checks so every --help example is executed and verified. Otherwise the storage command family is well designed, consistently named, and well documented (per-command help, storage-state.md, and the e2e-state roundtrip all worked first try).

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: cookie-set fails with an opaque internal error whenever --path is used (documented example broken)

./b4w.ps1 cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
(or any cookie-set that includes --path, e.g. `cookie-set t v --path /` or `--path=/`; the exact example printed by `./b4w.ps1 cookie-set --help` fails verbatim).

#### Issue 2: b4w.ps1 dev wrapper always exits 0, masking every CLI failure

./b4w.ps1 nosuchcommand_xyz; echo $?
./b4w.ps1 cookie-set x 1 --path / >/dev/null; echo $?
Both print an error but exit 0. The raw binary behaves correctly: cli/browser4-cli/target/debug/browser4-cli.exe nosuchcommand_xyz exits 2, and a failing cookie-set exits 1.

#### Issue 3: Documented cookie-set examples that use --path cannot work; docs mislead first-time users

Run either documented example:
1) `./b4w.ps1 cookie-set --help` -> Examples: `browser4-cli cookie-set session abc123 --domain localhost --path / --httpOnly --secure`
2) skills/browser4-cli/references/storage-state.md, Cookies section: `browser4-cli cookie-set session abc123 --domain example.com --path / --httpOnly --secure --sameSite Lax`

#### Issue 4: Cookie error reporting leaks backend internals and gives no hint about the invalid field

./b4w.ps1 cookie-set t v --path /
Observe the full message: `ERROR: browser_load_storage_state failed: Invalid cookie fields help: tab.loadStorageState(state: String)  Restores cookies plus localStorage from a JSON string previously returned by tab.saveStorageState().`

#### Issue 5: cookie-get/cookie-list serialize expires as a float (1788985438.0) and results are unordered

./b4w.ps1 cookie-set theme dark --domain localhost --expires 1788985438
./b4w.ps1 cookie-get theme
./b4w.ps1 cookie-list
Run cookie-list twice and compare row order.

#### Issue 6: state-save/state-load print file paths with mixed Windows/Unix separators

./b4w.ps1 state-save .test-sessions/browser_state.json
Observe: `Storage state saved: D:\workspace\Browser4\Browser4-4.13\.test-sessions/browser_state.json` (same for state-load).

