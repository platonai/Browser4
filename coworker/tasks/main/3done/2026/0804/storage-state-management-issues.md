# Issues: storage-state-management

> **Source:** `20260804-190305-storage-state-management.full.md` | **Date:** 20260804-190305 | **Mode:** dev

## Scenario Background

### Task

All 17 task steps completed successfully:

1. ✅ Navigated to `http://localhost:18080/generated/interactive-1.html`
2. ✅ Set `session_id=abc123` cookie with `--domain localhost`, `--path /`, `--httpOnly`, `--secure`
3. ✅ Set `theme=dark` cookie with `--sameSite Lax`, `--expires 1786474749` (one week)
4. ✅ Listed all cookies — both present with correct attributes
5. ✅ Filtered by `--domain localhost` — both cookies returned
6. ✅ Got `theme` cookie value — `"dark"` confirmed
7. ✅ Deleted `session_id` cookie
8. ✅ Listed cookies — only `theme` remained
9. ✅ Cleared all cookies — jar empty (`[]`)
10. ✅ Set `user_prefs` localStorage to `{"lang":"en","tz":"UTC"}` (required workaround)
11. ✅ Listed localStorage and got `user_prefs` — value verified
12. ✅ Deleted `user_prefs`, cleared localStorage
13. ✅ Set `visit_count=1` in sessionStorage, listed, got, deleted, cleared
14. ✅ Set `test_cookie`, saved state to `browser_state.json`
15. ✅ Cleared cookies and localStorage
16. ✅ Loaded state — `test_cookie` restored successfully
17. ✅ Deleted `browser_state.json`

### Execution Context

**Key Commands:**

1. `./b4w.sh goto "http://localhost:18080/generated/interactive-1.html"` — navigation
2. `./b4w.sh cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure` — set first cookie
3. `./b4w.sh cookie-set theme dark --sameSite Lax --expires 1786474749` — set second cookie
4. `./b4w.sh cookie-list` — verify both set
5. `./b4w.sh cookie-list --domain localhost` — domain filter
6. `./b4w.sh cookie-get theme` — get specific cookie
7. `./b4w.sh cookie-delete session_id` — delete cookie
8. `./b4w.sh cookie-list` — confirm deletion
9. `./b4w.sh cookie-clear` + `cookie-list` — clear and verify
10. `pwsh -NoProfile ... -Command "& './b4w.ps1' localstorage-set user_prefs '{\"lang\":\"en\",\"tz\":\"UTC\"}'"` — **workaround required**
11. `./b4w.sh localstorage-list` + `localstorage-get user_prefs` — verify
12. `./b4w.sh localstorage-delete user_prefs` + `localstorage-clear` — cleanup
13. `./b4w.sh sessionstorage-set/list/get/delete/clear` — full sessionStorage lifecycle
14. `./b4w.sh state-save .test-sessions/browser_state.json` — save state
15. `./b4w.sh cookie-clear` + `localstorage-clear` — clear for test
16. `./b4w.sh state-load .test-sessions/browser_state.json` + `cookie-list` — restore and verify
17. `rm .test-sessions/browser_state.json` — cleanup

**Key decisions:**
- Used `./b4w.sh` instead of `$(./b4w.ps1)` per SKILL.md warning that `$()` is command substitution in bash
- Used direct `pwsh` invocation with escaped quotes for JSON localStorage value after `./b4w.sh` failed
- Created all temp files in `.test-sessions/` as required

**Workarounds required:**
- Step 10 (localStorage JSON value): `./b4w.sh` failed with "too many arguments" — had to use direct pwsh with escaped quotes
- Step 0 (help): `$(./b4w.ps1) help` failed as predicted by SKILL.md — used `./b4w.sh help` instead

---

## Issues Found (8 issues)

### Issue 1: Task-required $(./b4w.ps1) invocation format is broken in bash

**Severity:** High
**Category:** Documentation

#### Reproduction

In Git Bash, run: $(./b4w.ps1) help
Result: "b4w: command not found"

#### Expected Behavior

The help output should display, or the task instructions should use a working invocation method.

#### Actual Behavior

The command fails because $(...) is bash command substitution — it runs ./b4w.ps1, captures stdout, then tries to execute that output as a command. SKILL.md line 27 explicitly warns about this, yet the task instructions mandate the broken format.

#### Root Cause Analysis

The task template uses $(./b4w.ps1) syntax which is command substitution in bash. While SKILL.md documents this as a known issue, the task instructions themselves prescribe the broken format. This creates a contradiction where following the task instructions leads to immediate failure, and following the documentation requires deviating from the task instructions.

#### AI Suggested Improvement

- Update task templates to use ./b4w.sh <command> (bash) or ./b4w.ps1 <command> (PowerShell) instead of $(./b4w.ps1)
- Add a wrapper script or alias that makes the $(...) format work, e.g., a shell function that passes arguments through
- Add a first-run check in the task harness that detects bash and prints a clear migration message

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The task template prescribes `$(./b4w.ps1)` which is bash command substitution — guaranteed to fail. SKILL.md line 27 already warns about this exact anti-pattern, creating a direct contradiction between the task instructions and the project's own documentation. Fix the template to use `./b4w.sh` (bash) or `./b4w.ps1` (PowerShell) directly.

---

### Issue 2: JSON values with commas/spaces cannot be set via b4w.sh wrapper

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.sh localstorage-set user_prefs '{"lang":"en","tz":"UTC"}'
Result: "Error: error: too many arguments: expected 2, received 3"

#### Expected Behavior

The JSON string should be treated as a single value argument.

#### Actual Behavior

The CLI reports "too many arguments" because the JSON value containing commas and spaces is split into multiple arguments by the time it reaches the argument parser. The b4w.sh wrapper's quoting mechanism (line 45-48) does not sufficiently protect against PowerShell's argument parsing when the value contains double-quote characters.

#### Root Cause Analysis

The b4w.sh wrapper escapes double quotes within arguments as \" (line 47) and wraps each argument in double quotes. When PowerShell receives the resulting string, the embedded escaped quotes interact with PowerShell's own parsing, causing the value to be split. The direct pwsh invocation `pwsh -Command "& './b4w.ps1' localstorage-set user_prefs '{\"lang\":\"en\",\"tz\":\"UTC\"}'"` works correctly, suggesting the issue is specifically in the b4w.sh argument transformation pipeline.

#### Code Pointer

`b4w.sh:45-48 (argument escaping loop)`

#### AI Suggested Improvement

- Add a --value-base64 flag to storage set commands for safe binary/JSON values
- Add a --file flag to read the value from a file, avoiding shell quoting entirely
- Document the pwsh workaround explicitly in the storage-state.md reference
- Fix the b4w.sh argument quoting to handle embedded double-quotes in PowerShell -Command strings

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Genuine bug in the b4w.sh argument-escaping pipeline (line 45-48). Embedded double-quote characters in JSON values interact destructively with PowerShell's `-Command` string parsing, causing argument splitting. The direct pwsh workaround confirms the underlying CLI is fine — the shell wrapper just needs its escape logic hardened for this case.

---

### Issue 3: state-save does not include sessionStorage despite 'complete browser state' description

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Set a sessionStorage item, run state-save, clear sessionStorage, run state-load — sessionStorage is not restored.

#### Expected Behavior

The help text says state-save saves 'cookies and localStorage'. The task instructions say to save 'complete browser state'. SessionStorage is excluded, which is understandable (it's session-scoped) but the mismatch between task description and actual behavior creates confusion.

#### Actual Behavior

state-save only persists cookies and localStorage. sessionStorage is not included in the saved state file.

#### Root Cause Analysis

SessionStorage is intentionally scoped to the browsing session and is not typically persisted across sessions. However, the CLI help text ('Save cookies and localStorage to a JSON file') and the task instructions ('save the complete browser state') use inconsistent language that could mislead users into expecting sessionStorage preservation.

#### AI Suggested Improvement

- Update state-save help text to clarify: 'Save cookies and localStorage to a JSON file (sessionStorage is not persisted)'
- Consider adding state-save --include-session-storage flag for completeness
- Update task instructions to say 'browser storage state' rather than 'complete browser state'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The CLI help text ("cookies and localStorage") is already accurate. The problem is the task instructions saying "complete browser state," which overpromises. Fix the task phrasing to "browser storage state" or similar. The suggested `--include-session-storage` flag is a feature request, not a bug — sessionStorage is correctly excluded by design.

---

### Issue 4: Storage command help output lacks usage examples

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Run ./b4w.sh cookie-set --help — it shows options but no examples.

#### Expected Behavior

The --help output for storage commands should include at least one usage example showing common flag combinations, similar to how the storage-state.md reference doc provides examples.

#### Actual Behavior

cookie-set --help only shows: Arguments: <name>, <value>; Options: --domain, --path, --expires, --httpOnly, --secure, --sameSite. No examples of how to combine these flags.

#### Root Cause Analysis

The CLI help system provides argument/option reference but not usage examples. The storage-state.md reference doc fills this gap, but users who only use --help won't discover the patterns. This is a consistent pattern across all storage commands.

#### AI Suggested Improvement

- Add a one-line example to each storage command's --help output (e.g., 'Example: cookie-set session abc123 --domain .example.com --path / --httpOnly --secure')
- Add a reference pointer in --help: 'See skills/browser4-cli/references/storage-state.md for more examples'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid discoverability gap. Storage commands like `cookie-set` have six optional flags (domain, path, expires, httpOnly, secure, sameSite) with non-obvious value formats. A one-line example in `--help` output is cheap to add and prevents users from needing to hunt down the reference doc. Cross-issue pattern: Issues 3, 4, 8 all share the root cause of documentation/help text coverage gaps.

---

### Issue 5: Typo in clear-confirmation messages: 'entrie(s)'

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.sh localstorage-clear
Output: "localStorage cleared: 0 entrie(s)."
./b4w.sh sessionstorage-clear
Output: "sessionStorage cleared: 0 entrie(s)."

#### Expected Behavior

"localStorage cleared: 0 entries." or "localStorage cleared: 0 entry/entries."

#### Actual Behavior

The word "entrie(s)" is a misspelling — it should be "entries" or "entry/entries".

#### Root Cause Analysis

Likely a typo in the CLI output formatting code for the clear commands. The '(s)' parenthetical pluralization pattern is used, but the base word is misspelled as 'entrie' instead of 'entry'.

#### AI Suggested Improvement

- Change "entrie(s)" to "entries" or "entry/entries" in the clear-command output strings

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Straightforward typo — "entrie(s)" is not a word. Replace with "entries" or "entry/entries" in the clear-command output strings. Trivial fix with no design surface.

---

### Issue 6: cookie-list has no --name filter — must use separate cookie-get command

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh cookie-list --help
Only shows --domain and --path filters. No --name option.

#### Expected Behavior

Users might expect `cookie-list --name session_id` to filter by name, similar to --domain and --path.

#### Actual Behavior

cookie-list only filters by domain and path. Finding a specific cookie by name requires the separate cookie-get command.

#### Root Cause Analysis

The cookie-list command was designed with domain/path filtering (browser-centric) but without name filtering. This is a design choice — cookie-get serves the single-cookie use case — but it's not immediately obvious to new users.

#### AI Suggested Improvement

- Add a --name filter to cookie-list for consistency with --domain and --path
- Or: document the distinction clearly in --help output ("Use cookie-get <name> to look up a specific cookie")

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] `cookie-get <name>` already covers single-cookie lookup. Adding `--name` to `cookie-list` is a feature request, not a defect. The low-cost improvement (documenting cookie-get in cookie-list's `--help`) should be done now; the `--name` flag itself can wait until users actually ask for it.

---

### Issue 7: No --json flag support on cookie-list despite JSON being the default output

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.sh cookie-list --help — no --json option listed. ./b4w.sh --json cookie-list — works but not discoverable from command help.

#### Expected Behavior

The --json flag should be listed in per-command --help if the command supports it, or the default JSON output behavior should be documented.

#### Actual Behavior

cookie-list outputs JSON by default, but --help doesn't mention this or the --json flag. Users might not realize they can pipe the output to jq or other JSON tools.

#### Root Cause Analysis

cookie-list always outputs JSON (it's the default format), so --json may be treated as redundant. However, the SKILL.md describes --json as a general output mode flag, and its absence from per-command help reduces discoverability.

#### AI Suggested Improvement

- Add --json to the options list in cookie-list --help with a note that JSON is the default output format
- Alternatively, add a note at the top: 'Output is always JSON'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] `--json` is a global CLI flag (`./b4w.sh --json cookie-list`), not a subcommand option. In CLI frameworks like clap, per-command `--help` does not enumerate global flags — that's the global help's job. The default output is already JSON. Adding `--json` to every subcommand's help would be noise and break consistency with how global flags work across the rest of the CLI.

---

### Issue 8: cookie-set --domain default behavior is undocumented

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run ./b4w.sh cookie-set test val without --domain — where is the cookie scoped?

#### Expected Behavior

The default domain behavior should be documented in --help or storage-state.md.

#### Actual Behavior

When --domain is omitted, the cookie is likely set for the current page's domain, but this is not documented. Users must either know CDP cookie behavior or experiment.

#### Root Cause Analysis

The CLI delegates to CDP's Network.setCookie which defaults to the current page's domain. This implicit behavior is not surfaced in the CLI documentation.

#### AI Suggested Improvement

- Document the default domain behavior in cookie-set --help: 'Defaults to the current page domain if omitted'
- Add the same note to storage-state.md

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] One-line documentation gap: "Defaults to the current page domain if omitted." Users shouldn't need to know CDP's `Network.setCookie` behavior to use the CLI. Same fix applies to both `--help` output and `storage-state.md`. Cross-issue pattern with Issue 3 and 4 — all three are documentation coverage gaps in the help/reference surface.

---

## Overall Assessment

**Completion Status:** Successful — all 17 task steps completed. One workaround required (JSON value quoting for localStorage-set).

**Success Rate:** 94% — 16/17 steps worked first try with ./b4w.sh; step 10 required direct pwsh invocation.

**Issues Found:** 8

**Major Blockers:** The task-mandated $(./b4w.ps1) invocation format fails in bash (documented in SKILL.md but prescribed by task instructions). Users must discover ./b4w.sh on their own. The JSON quoting issue for localStorage-set is a secondary blocker that requires knowing the pwsh workaround.

**Most Confusing Aspects:** 1. The task says use $(./b4w.ps1) but SKILL.md says that doesn't work in bash — a new user would be stuck at step 0. 2. JSON values with commas/spaces in localStorage-set fail silently with a misleading 'too many arguments' error. 3. The distinction between cookie-list --domain (filter) and cookie-get (single lookup) is not obvious from help output alone.

**Most Valuable Improvements:** 1. Fix task templates to use ./b4w.sh instead of $(./b4w.ps1). 2. Add --file or --stdin flag to storage-set commands for safe JSON/binary values. 3. Add usage examples to per-command --help output. 4. Fix the 'entrie(s)' typo in clear messages.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Task-required $(./b4w.ps1) invocation format is broken in bash

In Git Bash, run: $(./b4w.ps1) help
Result: "b4w: command not found"

#### Issue 2: JSON values with commas/spaces cannot be set via b4w.sh wrapper

./b4w.sh localstorage-set user_prefs '{"lang":"en","tz":"UTC"}'
Result: "Error: error: too many arguments: expected 2, received 3"

#### Issue 3: state-save does not include sessionStorage despite 'complete browser state' description

Set a sessionStorage item, run state-save, clear sessionStorage, run state-load — sessionStorage is not restored.

#### Issue 4: Storage command help output lacks usage examples

Run ./b4w.sh cookie-set --help — it shows options but no examples.

#### Issue 5: Typo in clear-confirmation messages: 'entrie(s)'

./b4w.sh localstorage-clear
Output: "localStorage cleared: 0 entrie(s)."
./b4w.sh sessionstorage-clear
Output: "sessionStorage cleared: 0 entrie(s)."

#### Issue 6: cookie-list has no --name filter — must use separate cookie-get command

./b4w.sh cookie-list --help
Only shows --domain and --path filters. No --name option.

#### Issue 7: No --json flag support on cookie-list despite JSON being the default output

./b4w.sh cookie-list --help — no --json option listed. ./b4w.sh --json cookie-list — works but not discoverable from command help.

#### Issue 8: cookie-set --domain default behavior is undocumented

Run ./b4w.sh cookie-set test val without --domain — where is the cookie scoped?

