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

---

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

---

## Issues Found (5 issues)

### Issue 1: Typo "entrie(s)" in localStorage/sessionStorage clear output

**Severity:** Low
**Category:** Product

#### Reproduction

```
browser4-cli localstorage-clear
browser4-cli sessionstorage-clear
```

#### Expected Behavior

Output reads "Cleared: 0 entries" or "Cleared 0 entries."

#### Actual Behavior

Output reads "Cleared: 0 entrie(s)." — the word "entrie(s)" is misspelled (should be "entries") and the parenthesized plural form is grammatically awkward.

#### Root Cause Analysis

Typo in the output format string in the clear command handler. The parenthesized `(s)` pattern is also an unusual/non-standard i18n shortcut.

#### Code Pointer

`(Likely in the CLI output formatting for `localstorage-clear`/`sessionstorage-clear` commands.)`

#### AI Suggested Improvement

- Change `"entrie(s)"` to `"entries"` in the output string
- Or use proper singular/plural logic: `"1 entry"` vs `"N entries"`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 2: `cookie-get` returns full JSON object instead of just the value

**Severity:** Low
**Category:** Documentation / UX

#### Reproduction

```
browser4-cli cookie-get theme
```

#### Expected Behavior

The value `"dark"` is returned (since the command is named "get" and takes a cookie name, implying value retrieval).

#### Actual Behavior

The full cookie JSON object is returned:
```json
{
  "domain": "localhost",
  "expires": 1784246400.0,
  "httpOnly": false,
  "name": "theme",
  "path": "/",
  "sameSite": "Lax",
  "secure": false,
  "value": "dark"
}
```

#### Root Cause Analysis

The command is designed to return the full cookie descriptor for completeness. However, this is inconsistent with `localstorage-get` which returns only the value (e.g., `1` for `sessionstorage-get visit_count`). Users may expect `cookie-get` to behave symmetrically.

#### Code Pointer

`(Likely in the cookie-get command handler — compare with localstorage-get for consistency.)`

#### AI Suggested Improvement

- Add a `--value-only` flag to `cookie-get` that returns just the value string, matching `localstorage-get`/`sessionstorage-get` behavior
- Alternatively, document the full-object return format explicitly in `cookie-get --help` and the README

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 3: `state-save` stores sessionStorage silently, undocumented

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. Set a sessionStorage value
2. Run `state-save`
3. Clear sessionStorage
4. Run `state-load`

#### Expected Behavior

The README says `state-save` saves "cookies & localStorage" only. Either sessionStorage should not be restored, or the documentation should list it.

#### Actual Behavior

The README description reads: "Save cookies & localStorage to a JSON file" — sessionStorage is not mentioned. The output file also doesn't show sessionStorage in its structure (`cookies` + `origins[].localStorage` only). This means `state-save`/`state-load` cannot save/restore sessionStorage, which is expected (sessionStorage is session-bound), but this isn't documented.

#### Root Cause Analysis

Documentation gap. The `state-save`/`state-load` description omits clarifying that sessionStorage is intentionally excluded (it's session-scoped by nature).

#### Code Pointer

`(In `cli/browser4-cli/README.md` around the Browser Storage section.)`

#### AI Suggested Improvement

- Add a note to the `state-save`/`state-load` documentation: "Note: sessionStorage is not persisted because it is inherently session-scoped. Only cookies and localStorage are saved/restored."
- Inline with the browser storage command table

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 4: Verbose "Reconnected to existing session" output on every command

**Severity:** Low
**Category:** UX

#### Reproduction

Run any command when an existing session is active.

#### Expected Behavior

The output focuses on the command result. Session reconnect info could be a brief one-liner or suppressed after the first invocation.

#### Actual Behavior

Every command prints "Reconnected to existing session on <URL>" which adds noise. For example, `cookie-set` prints:
```
Reconnected to existing session on http://localhost:18080/generated/interactive-1.html
Cookie set: session_id
```

#### Root Cause Analysis

Session reconnection status is emitted at `info` level unconditionally. For rapid-fire CLI usage, this adds cognitive load.

#### AI Suggested Improvement

- Suppress the "Reconnected" message when `--quiet` or `--json` is used (it may already be suppressed — verify)
- Consider demoting this to a `--verbose`-only message or suppressing it for non-navigation commands where reconnect is an implementation detail

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

---

### Issue 5: No `--json` flag used — output format inconsistency between commands

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `cookie-list` vs `cookie-get` vs `localstorage-list`.

#### Expected Behavior

Consistent output format (or clearly documented differences).

#### Actual Behavior

All storage commands produce JSON arrays/objects (consistent 👍), but the `localstorage-clear` and `sessionstorage-clear` commands produce human-readable text ("localStorage cleared: 0 entrie(s).") rather than a JSON envelope even though `--json` is a global flag. It's unclear whether `--json` wraps these messages or if the commands ignore it.

#### Root Cause Analysis

The `--json` flag's interaction with each subcommand may not be uniform. Some commands emit human-readable strings by default instead of JSON.

#### AI Suggested Improvement

- Ensure `--json` produces consistent `{"status":"ok","command":"...","output":{...}}` envelopes for ALL commands including `*-clear`
- Add `localstorage-list --json` validation to tests

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI review unavailable — defaulted to DEFER]

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

