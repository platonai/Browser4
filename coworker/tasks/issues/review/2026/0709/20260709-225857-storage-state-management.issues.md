# Issues: storage-state-management

> **Source:** `20260709-225857-storage-state-management.full.md` | **Date:** 20260709-225857 | **Mode:** dev

## Scenario Background

### Task

All 17 steps completed successfully:

| Step | Operation | Result |
|------|-----------|--------|
| 1 | Navigate to interactive-1.html | ✓ Page loaded |
| 2 | Set `session_id` cookie (abc123, httpOnly, secure) | ✓ Set |
| 3 | Set `theme` cookie (dark, SameSite=Lax, expires) | ✓ Set |
| 4 | List all cookies | ✓ Both shown |
| 5 | Filter cookies by domain `localhost` | ✓ Both shown |
| 6 | Get `theme` cookie value | ✓ `"dark"` confirmed |
| 7 | Delete `session_id` cookie | ✓ Deleted |
| 8 | List cookies — verify removal | ✓ Only `theme` remains |
| 9 | Clear all cookies, verify empty | ✓ `[]` empty jar |
| 10 | Set localStorage `user_prefs` = `{"lang":"en","tz":"UTC"}` | ✓ Set |
| 11 | List and get localStorage `user_prefs` | ✓ Value confirmed |
| 12 | Delete key, clear all localStorage | ✓ Cleared |
| 13 | sessionStorage lifecycle (set/list/get/delete/clear) | ✓ All OK |
| 14 | Set test cookie, save state to `browser_state.json` | ✓ Saved |
| 15 | Clear all cookies and localStorage | ✓ Cleared |
| 16 | Load state, verify cookie restored | ✓ `test_cookie` restored |
| 17 | Delete `browser_state.json` | ✓ Cleaned up |

### Execution Context

**Key Commands:**

```
cargo run --manifest-path ... -- goto "http://localhost:18080/generated/interactive-1.html"
cargo run --manifest-path ... -- cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
cargo run --manifest-path ... -- cookie-set theme dark --sameSite Lax --expires 1784246400
cargo run --manifest-path ... -- cookie-list
cargo run --manifest-path ... -- cookie-list --domain localhost
cargo run --manifest-path ... -- cookie-get theme
cargo run --manifest-path ... -- cookie-delete session_id
cargo run --manifest-path ... -- cookie-list
cargo run --manifest-path ... -- cookie-clear
cargo run --manifest-path ... -- cookie-list
cargo run --manifest-path ... -- localstorage-set user_prefs '{"lang":"en","tz":"UTC"}'
cargo run --manifest-path ... -- localstorage-list
cargo run --manifest-path ... -- localstorage-get user_prefs
cargo run --manifest-path ... -- localstorage-delete user_prefs
cargo run --manifest-path ... -- localstorage-clear
cargo run --manifest-path ... -- sessionstorage-set visit_count 1
cargo run --manifest-path ... -- sessionstorage-list
cargo run --manifest-path ... -- sessionstorage-get visit_count
cargo run --manifest-path ... -- sessionstorage-delete visit_count
cargo run --manifest-path ... -- sessionstorage-clear
cargo run --manifest-path ... -- cookie-set test_cookie restore_test --domain localhost --path /
cargo run --manifest-path ... -- state-save .../browser_state.json
cargo run --manifest-path ... -- cookie-clear && ... localstorage-clear
cargo run --manifest-path ... -- state-load .../browser_state.json
cargo run --manifest-path ... -- cookie-list
rm .../browser_state.json
cargo run --manifest-path ... -- close
```

**Important decisions:**
- Used `cargo run --manifest-path` from repo root as the `$cliInvocation` pattern — this builds from local source (not the globally installed binary)
- Computed Unix timestamp for "one week from July 10, 2026" = `1784246400`
- Used single-quote shell escaping for JSON value in `localstorage-set`

**Workarounds required:** None. All commands worked as documented on first attempt.

---

## Issues Found (5 issues)
> **Review complete:** 0 approved, 5 deferred/rejected

### Issue 1: Typo "entrie(s)" in localStorage/sessionStorage clear output

**Severity:** Low
**Category:** Product

#### Review Result

**Decision:** WONTFIX

**Summary:** - Change `"entrie(s)"` to `"entries"` in the output string

---

### Issue 2: `cookie-get` returns full JSON object instead of just the value

**Severity:** Low
**Category:** Documentation / UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `--value-only` flag to `cookie-get` that returns just the value string, matching `localstorage-get`/`sessionstorage-get` behavior

---

### Issue 3: `state-save` stores sessionStorage silently, undocumented

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a note to the `state-save`/`state-load` documentation: "Note: sessionStorage is not persisted because it is inherently session-scoped. Only cookies and localStorage are saved/restored."

---

### Issue 4: Verbose "Reconnected to existing session" output on every command

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Suppress the "Reconnected" message when `--quiet` or `--json` is used (it may already be suppressed — verify)

---

### Issue 5: No `--json` flag used — output format inconsistency between commands

**Severity:** Low
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Ensure `--json` produces consistent `{"status":"ok","command":"...","output":{...}}` envelopes for ALL commands including `*-clear`

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Typo "entrie(s)" in localStorage/sessionStorage clear output

```
browser4-cli localstorage-clear
browser4-cli sessionstorage-clear
```

#### Issue 2: `cookie-get` returns full JSON object instead of just the value

```
browser4-cli cookie-get theme
```

#### Issue 3: `state-save` stores sessionStorage silently, undocumented

1. Set a sessionStorage value
2. Run `state-save`
3. Clear sessionStorage
4. Run `state-load`

#### Issue 4: Verbose "Reconnected to existing session" output on every command

Run any command when an existing session is active.

#### Issue 5: No `--json` flag used — output format inconsistency between commands

Run `cookie-list` vs `cookie-get` vs `localstorage-list`.
