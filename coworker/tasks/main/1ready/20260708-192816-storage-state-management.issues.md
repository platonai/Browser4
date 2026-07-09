# Issues: storage-state-management

> **Source:** `20260708-192816-storage-state-management.full.md` | **Date:** 20260708-192816 | **Mode:** dev

## Scenario Background

### Task

All steps completed successfully:
- ✅ Navigated to MockSite interactive test page
- ✅ Set `session_id` cookie with `--httpOnly`, `--secure`, `--domain localhost`, `--path /`
- ✅ Set `theme` cookie with `--sameSite Lax`, `--expires` one week from now
- ✅ Listed, filtered, got, deleted, and cleared cookies
- ✅ Set, listed, got, deleted, and cleared localStorage
- ✅ Set, listed, got, deleted, and cleared sessionStorage
- ✅ Saved state to `browser_state.json`, cleared everything, loaded state, verified restoration
- ✅ Cleaned up `browser_state.json`

---

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `goto "http://localhost:18080/generated/interactive-1.html"` | Navigate to test page |
| 2 | `cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure` | Set session_id cookie |
| 3 | `cookie-set theme dark --sameSite Lax --expires 1784160000` | Set theme cookie |
| 4 | `cookie-list` | List all cookies |
| 5 | `cookie-list --domain localhost` | Filter by domain |
| 6 | `cookie-get theme` | Get theme cookie value |
| 7 | `cookie-delete session_id` | Delete session_id |
| 8 | `cookie-clear` + `cookie-list` | Clear and verify empty |
| 9 | `localstorage-set`, `list`, `get`, `delete`, `clear` | Full localStorage lifecycle |
| 10 | `sessionstorage-set`, `list`, `get`, `delete`, `clear` | Full sessionStorage lifecycle |
| 11 | `cookie-set test_state restorable --domain localhost --path /` | Set test cookie |
| 12 | `state-save browser_state.json` | Save state |
| 13 | `cookie-clear` + `localstorage-clear` | Clear everything |
| 14 | `state-load <path>` | Restore state |
| 15 | `cookie-list` | Verify restoration |
| 16 | `rm browser_state.json` | Cleanup |

**Key decisions:**
- Used `cargo run --manifest-path ...` for local source invocation
- Used `date -d "2026-07-16" +%s` to compute Unix timestamp for cookie expiry
- Used single quotes to protect JSON string in localStorage-set
- Used absolute path for state-load after discovering CWD behavior

**Workarounds:**
- Had to discover that `cargo run --manifest-path` changes CWD to the manifest directory, so `browser_state.json` was saved in `cli/browser4-cli/` rather than repo root

---

---

## Issues Found (8 issues)

### Issue 1: Typo "entrie(s)" in clear command output

**Severity:** Low
**Category:** Product

#### Reproduction

Run `localstorage-clear` or `sessionstorage-clear`:
```
cargo run -- -- localstorage-clear
```

#### Expected Behavior

"sessionStorage cleared: 0 entries."

#### Actual Behavior

"localStorage cleared: 0 entrie(s)."

#### Root Cause Analysis

Typo in the output/format string. The word "entries" is misspelled as "entrie(s)".

#### Code Pointer

`Likely in `cli/browser4-cli/src/` — the localized string or format string for the clear command output message.`

#### AI Suggested Improvement

- Change "entrie(s)" to "entries" in both `localstorage-clear` and `sessionstorage-clear` output messages
- Consider dropping the "(s)" notation entirely and using proper pluralization based on count

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 2: `state-save` and `state-load` file paths resolved relative to binary directory, not user CWD

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
cd /home/vincent/workspace/Browser4
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- state-save browser_state.json
# File saved at: /home/vincent/workspace/Browser4/cli/browser4-cli/browser_state.json
# NOT at: /home/vincent/workspace/Browser4/browser_state.json
```

#### Expected Behavior

File should be saved relative to the user's current working directory (`$PWD`), matching shell convention.

#### Actual Behavior

File is saved relative to the binary's working directory (the Cargo manifest directory when running via `cargo run`).

#### Root Cause Analysis

`cargo run --manifest-path` changes the process working directory to the manifest directory before executing the binary. The CLI uses `std::env::current_dir()` or equivalent, which resolves to the manifest directory rather than the user's original CWD.

#### Code Pointer

``cli/browser4-cli/src/state.rs` — wherever file paths are resolved for `state-save` and `state-load`.`

#### AI Suggested Improvement

- Preserve the original CWD before cargo changes it, or resolve paths relative to an environment variable
- Document this behavior in the README if it cannot be changed
- Consider using `BROWSER4_CLI_STATE_DIR` or a similar convention for default save paths

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] This is inherent `cargo run --manifest-path` behavior (cargo changes CWD to the manifest directory). When browser4-cli is installed as a global binary, `std::env::current_dir()` correctly resolves to the user's CWD. This only manifests during development with `cargo run`. Per historical review patterns, development-mode friction (cargo run overhead, cd into subdirs) is WONTFIX. Workaround: use absolute paths or `cd` to the manifest directory first.

---

### Issue 3: `cookie-get` returns full JSON object, no way to get value-only output

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```
cargo run -- -- cookie-get theme
```
Output:
```json
{"domain":"localhost","expires":1784160000,"httpOnly":false,"name":"theme","path":"/","sameSite":"Lax","secure":false,"value":"dark"}
```

#### Expected Behavior

Should have a flag (e.g., `--value-only`) to extract just the value, similar to how `get attr` works for page elements.

#### Actual Behavior

Only the full JSON object is returned. For shell scripting, you'd need to pipe through `jq`.

#### Root Cause Analysis

No `--value-only` or similar flag exists for `cookie-get`. The design prioritizes machine-readability via `--json` mode but doesn't offer a script-friendly single-value extraction.

#### Code Pointer

``cli/browser4-cli/src/args.rs` or the cookie command handler.`

#### AI Suggested Improvement

- Add a `--value-only` flag to `cookie-get` that prints only the cookie value (no JSON wrapper)
- Alternatively, support `cookie-get <name> value` syntax mirroring `get attr <selector> <name>`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] AI agents parse the JSON output directly and can extract the value field trivially. For shell scripting, `jq -r '.value'` achieves the same result. A `--value-only` flag is a nice-to-have convenience but doesn't block any agent workflow. Defer until there's clearer demand or a broader output-format consistency pass across all commands.

---

### Issue 4: `state-save` does not save sessionStorage

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. Set a sessionStorage value: `sessionstorage-set test_key test_value`
2. Save state: `state-save test.json`
3. Clear sessionStorage
4. Load state: `state-load test.json`
5. Check sessionStorage: `sessionstorage-list` → empty

#### Expected Behavior

Either sessionStorage should be saved and restored, or the documentation should clearly state that sessionStorage is excluded.

#### Actual Behavior

The README says `state-save` saves "cookies & localStorage" — which is factually correct. However, a new user who sees "browser state" or "storage state" might reasonably expect sessionStorage to be included. The output message says "Storage state saved" which is ambiguous.

#### Root Cause Analysis

The term "state" is overloaded — it means "cookies + localStorage" but the name suggests broader coverage.

#### Code Pointer

``cli/browser4-cli/src/state.rs` — `state-save` implementation.`

#### AI Suggested Improvement

- Update the `state-save` description in help and README to explicitly state "cookies and localStorage only (not sessionStorage, IndexedDB, or Cache API)"
- Consider adding sessionStorage to the saved state if feasible
- Or rename to `storage-save` / `storage-load` for more accurate naming

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 5: No way to run from source documented for developers

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Try to find documentation on how to run browser4-cli from the local source tree without installing globally.

#### Expected Behavior

A CONTRIBUTING.md, DEVELOPMENT.md, or section in README explaining `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` for local development.

#### Actual Behavior

README only documents global installation via npm, standalone installers, or platform scripts. The `--help` output shows `browser4-cli <command>` syntax, which assumes the binary is on PATH.

#### Root Cause Analysis

Missing developer documentation. The README targets end users only.

#### Code Pointer

``cli/README.md` — should include a development section.`

#### AI Suggested Improvement

- Add a "Development" section to `cli/README.md` with `cargo run` invocation patterns
- Include instructions for running against a local backend server
- Document the `--server` flag for pointing to a dev backend

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Missing developer documentation only affects contributors to browser4-cli, not AI agents using the tool for their tasks. The target audience (end users) already has installation docs via npm, standalone installers, and platform scripts. A CONTRIBUTING.md or DEVELOPMENT.md would be valuable but is not blocking for the core product. Defer until the project matures and attracts more external contributors.

---

### Issue 6: `cookie-set` confirmation message does not echo set attributes

**Severity:** Low
**Category:** UX

#### Reproduction

```
cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
```
Output: `Cookie set: session_id`

#### Expected Behavior

Output should include the key attributes that were set (domain, path, flags) for immediate verification.

#### Actual Behavior

Only the cookie name is echoed. To verify the flags took effect, you must immediately run `cookie-list`.

#### Root Cause Analysis

The success message is minimal and doesn't confirm the options were applied.

#### Code Pointer

``cli/browser4-cli/src/` — cookie-set command handler output.`

#### AI Suggested Improvement

- Echo at least the domain, path, and any boolean flags in the confirmation message
- Example: `Cookie set: session_id=abc123 (domain=localhost, path=/, httpOnly, secure)`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Echoing set attributes in the confirmation message would save AI agents an extra `cookie-list` round-trip for verification. However, the suggested fix of echoing the cookie value (e.g., `session_id=abc123`) should be reconsidered — echoing sensitive cookie values to stdout is a security concern. The confirmation should echo attributes (domain, path, flags) but not the value.

---

### Issue 7: `--json` global flag behavior not obvious for storage commands

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run storage commands without `--json` — they emit human-readable text (e.g., "Cookie set: session_id"). With `--json`, they emit JSON. The relationship between `--json` mode and commands that already emit JSON (like `cookie-list`) is unclear.

#### Expected Behavior

Documentation should clarify the interaction between `--json` flag and commands that natively produce JSON output.

#### Actual Behavior

Both `cookie-list` and `cookie-list --json` appear to produce similar JSON output, making it unclear what `--json` adds. The README says `--json` wraps output in `{"status":"ok","command":"<name>","output":{...}}` but this wasn't immediately obvious.

#### Root Cause Analysis

The `--json` flag's envelope wrapper vs. commands' native JSON output is a subtle distinction not surfaced in per-command help.

#### Code Pointer

``cli/README.md` lines 117-128 (global options section).`

#### AI Suggested Improvement

- Add an example in the README showing the difference between `cookie-list` and `cookie-list --json` output
- Consider adding `--json` examples to the storage command documentation
- Document `--json` envelope behavior per command group

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 8: Help text and README disagree on `wait` command syntax

**Severity:** Low
**Category:** Documentation

#### Reproduction

Compare `--help` output for `wait` with the README's waiting section:
- Help: `wait [target]` — listed as: "Wait for a condition: element, time, text, URL pattern, page load, or JS expression"
- README: Shows detailed table with six modes (selector, time, text, url, load, fn)

#### Expected Behavior

Both should be consistent. The help text could include the mode summary inline.

#### Actual Behavior

The help text is minimal; users must know to run `wait --help` for the full details. The README has comprehensive documentation but users might not find it.

#### Root Cause Analysis

Top-level help intentionally keeps commands terse; detailed mode documentation requires `wait --help` or consulting the README.

#### Code Pointer

``cli/browser4-cli/src/args.rs` — help text strings.`

#### AI Suggested Improvement

- Add a brief hint in the top-level help: "Run `browser4-cli wait --help` for all wait modes"
- Ensure `wait --help` covers all six modes with examples matching the README

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] The top-level help and README serve different purposes and don't actually disagree — the help is intentionally terse (showing `wait [target]` with a brief description), while the README provides comprehensive documentation. Standard CLI convention is that `command --help` provides full details. Adding a hint to run `wait --help` for mode details is a minor polish item. Defer as low-priority documentation refinement, especially since this is about the `wait` command which is tangential to the storage-state-management scenario being evaluated.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Typo "entrie(s)" in clear command output

Run `localstorage-clear` or `sessionstorage-clear`:
```
cargo run -- -- localstorage-clear
```

#### Issue 2: `state-save` and `state-load` file paths resolved relative to binary directory, not user CWD

```bash
cd /home/vincent/workspace/Browser4
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- state-save browser_state.json
# File saved at: /home/vincent/workspace/Browser4/cli/browser4-cli/browser_state.json
# NOT at: /home/vincent/workspace/Browser4/browser_state.json
```

#### Issue 3: `cookie-get` returns full JSON object, no way to get value-only output

```
cargo run -- -- cookie-get theme
```
Output:
```json
{"domain":"localhost","expires":1784160000,"httpOnly":false,"name":"theme","path":"/","sameSite":"Lax","secure":false,"value":"dark"}
```

#### Issue 4: `state-save` does not save sessionStorage

1. Set a sessionStorage value: `sessionstorage-set test_key test_value`
2. Save state: `state-save test.json`
3. Clear sessionStorage
4. Load state: `state-load test.json`
5. Check sessionStorage: `sessionstorage-list` → empty

#### Issue 5: No way to run from source documented for developers

Try to find documentation on how to run browser4-cli from the local source tree without installing globally.

#### Issue 6: `cookie-set` confirmation message does not echo set attributes

```
cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
```
Output: `Cookie set: session_id`

#### Issue 7: `--json` global flag behavior not obvious for storage commands

Run storage commands without `--json` — they emit human-readable text (e.g., "Cookie set: session_id"). With `--json`, they emit JSON. The relationship between `--json` mode and commands that already emit JSON (like `cookie-list`) is unclear.

#### Issue 8: Help text and README disagree on `wait` command syntax

Compare `--help` output for `wait` with the README's waiting section:
- Help: `wait [target]` — listed as: "Wait for a condition: element, time, text, URL pattern, page load, or JS expression"
- README: Shows detailed table with six modes (selector, time, text, url, load, fn)

