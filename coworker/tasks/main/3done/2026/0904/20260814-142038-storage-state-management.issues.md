# Issues: storage-state-management

> **Source:** `20260814-142038-storage-state-management.full.md` | **Date:** 20260814-142038 | **Mode:** dev

## Scenario Background

### Task

All 17 task steps completed successfully with zero errors and zero retries, using only `./b4w.ps1` invocations against the local dev backend:

| Step | Command | Result |
|---|---|---|
| 1 | `goto http://localhost:18080/generated/interactive-1.html` | ✅ Page loaded (already present in running DEFAULT session) |
| 2 | `cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure` | ✅ "Cookie set: session_id" |
| 3 | `cookie-set theme dark --sameSite Lax --expires 1787321707` | ✅ "Cookie set: theme" (ts = 2026-08-21, exactly +7 days) |
| 4 | `cookie-list` | ✅ Both cookies with all attributes correct (session_id: httpOnly=true, secure=true, path=/; theme: sameSite=Lax, expires set) |
| 5 | `cookie-list --domain localhost` | ✅ Filtered to localhost cookies |
| 6 | `cookie-get theme` | ✅ value "dark" (returned as full cookie JSON object) |
| 7 | `cookie-delete session_id` | ✅ "Cookie deleted: session_id" |
| 8 | `cookie-list` | ✅ Only theme remains |
| 9 | `cookie-clear` + `cookie-list` | ✅ "Cookies cleared." → `[]` |
| 10 | `localstorage-set user_prefs '{"lang":"en","tz":"UTC"}'` | ✅ "localStorage key set: user_prefs" |
| 11 | `localstorage-list` + `localstorage-get user_prefs` | ✅ Returns exact JSON `{"lang":"en","tz":"UTC"}` |
| 12 | `localstorage-delete user_prefs` + `localstorage-clear` | ✅ Cleared → `[]` |
| 13 | `sessionstorage-set visit_count 1` → list → get → delete → clear | ✅ get returned `1`; final list `[]` |
| 14 | `cookie-set test_cookie restore_me` + `state-save .test-sessions/browser_state.json` | ✅ Saved (cookies: 1, origins: 1) |
| 15 | `cookie-clear` + `localstorage-clear` | ✅ Empty |
| 16 | `state-load .test-sessions/browser_state.json` + `cookie-list` | ✅ test_cookie restored (value "restore_me") |
| 17 | `rm .test-sessions/browser_state.json` | ✅ Cleaned up |

### Execution Context

**Key Commands:**

**Preparation:** Read `skills/browser4-cli/SKILL.md` fully and `references/storage-state.md`. Both were accurate — the storage doc's command examples mapped 1:1 to the task steps, and `cookie-set --help` documented `--domain`, `--path`, `--expires` (Unix timestamp), `--httpOnly`, `--secure`, and the case-sensitive `--sameSite` values, with examples that matched the task almost verbatim.

**Decisions / workarounds:**
- The backend was already running from a prior session (first `goto` printed "Using existing session DEFAULT… Already at… page unchanged"), so no cold-start latency was exercised; no workaround needed.
- `--expires` requires a Unix timestamp, so I computed +7 days with `date -d '+7 days' +%s` → `1787321707` (2026-08-21). This is documented but is a real friction point (see Issue 3).
- The task says to save state to `browser_state.json`, while the session rules require all temp files under `./.test-sessions/`; I resolved the conflict by saving to `.test-sessions/browser_state.json` (same filename, temp location) and deleting it in step 17.
- `state-save` accepts relative paths resolved against the CLI's CWD (verified in `cli/browser4-cli/src/main.rs:3903 handle_state_save` — the CLI fetches the storage state and writes the file locally), so the temp-dir path worked directly.
- Root-cause investigations (for issue reporting): confirmed `cookie-get` prints the entire cookie object in `handle_cookie_get` (`cli/browser4-cli/src/main.rs:4019`); confirmed `cookie-list`/`cookie-get` are implemented client-side over the `browser_save_storage_state` tool with local filtering; confirmed snapshot files land in CWD-relative `.browser4-cli/snapshot/` (`cli/browser4-cli/src/snapshot.rs:9`), which is gitignored but not documented in the skill.

---

## Issues Found (5 issues)

### Issue 1: cookie-get returns the full cookie object instead of the value, inconsistent with localstorage-get/sessionstorage-get

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 cookie-set theme dark --sameSite Lax --expires 1787321707
./b4w.ps1 cookie-get theme
Compare with: ./b4w.ps1 localstorage-get user_prefs

#### Expected Behavior

A storage getter returns the bare value: cookie-get theme should print `dark`, matching localstorage-get/sessionstorage-get which print the raw value (e.g. `{"lang":"en","tz":"UTC"}` and `1`).

#### Actual Behavior

cookie-get theme prints the entire cookie object: {"name":"theme","value":"dark","domain":"localhost","path":"/","expires":1787321707.0,"httpOnly":false,"secure":false,"sameSite":"Lax"}. The task step "get the value of the theme cookie to verify it is dark" forces the user to parse a JSON object, while the sibling storage getters return raw values.

#### Root Cause Analysis

handle_cookie_get in cli/browser4-cli/src/main.rs (line ~4043) prints the whole matched cookie object with serde_json::to_string_pretty instead of extracting the "value" field. cookie-get is implemented client-side over the browser_save_storage_state tool, so the full object is available and the CLI chooses to print all of it.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_cookie_get — print cookie["value"] (or add --full/--json flag to show the whole object)`

#### AI Suggested Improvement

- Print just the value by default (cookie.get("value")), matching localstorage-get/sessionstorage-get semantics
- Add a --full or --json flag to display the complete cookie object when attributes are needed
- Document the output convention in `cookie-get --help` Notes (e.g. "Prints the raw value; use --full for all attributes")

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified — `handle_cookie_get` pretty-prints the whole object while `handle_storage_get` prints only the value, a real task-blocking inconsistency. Implement value-by-default with a `--full`/`--json` escape hatch, but handle the same-name-different-domain case: a name-only default output is ambiguous, so state which cookie matched (or prefer exact domain match) when the value alone is printed.

---

### Issue 2: --expires accepts only a Unix timestamp — no ISO dates or relative durations

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 cookie-set theme dark --expires 7d   # or --expires 2026-08-21T00:00:00Z

#### Expected Behavior

A first-time user should be able to express "one week from now" directly (ISO 8601 date or a relative duration like 7d), as the task phrased it.

#### Actual Behavior

Only an integer Unix timestamp (seconds since epoch) is accepted; the user must compute it externally (e.g. `date -d '+7 days' +%s`). The limitation is documented in help Notes, so it is discoverable, but it adds friction to a common operation and is error-prone (wrong epoch unit or timezone math silently produces a past/2038 expiry).

#### Root Cause Analysis

The CLI passes the --expires value through verbatim to the CDP cookie payload (handle_cookie_set builds the cookie map and the backend/upstream driver expects a numeric expiry). No parsing/conversion layer exists for date expressions.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_cookie_set — parse the --expires argument (ISO 8601 / relative durations) before inserting into the cookie map`

#### AI Suggested Improvement

- Accept ISO 8601 datetimes (`--expires 2026-08-21T00:00:00Z`) and relative durations (`--expires 7d`, `--expires 1w`, `--expires 30m`) in addition to raw epoch seconds
- Keep a small pure-Rust date parser (chrono or manual) to avoid new dependencies
- Update `cookie-set --help` and storage-state.md examples to show the friendlier forms

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified — `--expires` parses strictly as `i64` (main.rs:4243). The dependency objection in the suggestion is moot: `chrono = "0.4"` is already in Cargo.toml (line 30), so RFC 3339 and duration parsing add nothing new. Accept raw seconds plus `N[s/m/h/d/w]` suffixes first (highest value, trivial parser), defer full ISO 8601 if test budget is tight; keep the existing past-expiry warning for all forms.

---

### Issue 3: cookie-list/cookie-get serialize expires as a float (1787321707.0)

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 cookie-set theme dark --expires 1787321707
./b4w.ps1 cookie-list

#### Expected Behavior

Integer timestamp in JSON output: "expires": 1787321707 (as shown in the storage-state.md file-format example, which uses an integer).

#### Actual Behavior

Output contains "expires": 1787321707.0 — a float-formatted number. It roundtrips through state-save/state-load correctly, but it is cosmetically odd, differs from the documented format, and breaks strict integer parsers (e.g. Go/Java structs expecting a long) when users pipe cookie-list into their own tooling.

#### Root Cause Analysis

The expiry value originates from the browser/upstream driver (PulsarWebDriver) which serializes the numeric expiry as a Double in its JSON; the CLI's handle_cookie_list re-prints the backend JSON verbatim without normalizing number types.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_cookie_list (and handle_cookie_get) — normalize expires to an integer before printing; alternatively fix serialization in the upstream driver's getCookies()`

#### AI Suggested Improvement

- Normalize "expires" to an integer (truncate .0) when rendering cookie output in the CLI
- Add a unit test asserting cookie-list emits integer expires values

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified — cookie-set sends an integer but read-back via `browser_save_storage_state` re-serializes as a Double and cookie-list prints it verbatim, contradicting the integer in storage-state.md:49. Normalize `expires` to an integer in `handle_cookie_list`/`handle_cookie_get` rendering with a unit test, as suggested; fixing it CLI-side is more robust than the upstream driver since cookie output re-prints whatever the backend returns.

---

### Issue 4: cookie-set success message does not echo the effective cookie attributes

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure

#### Expected Behavior

Confirmation that the flags were applied, e.g. echoing the resulting cookie attributes (domain, path, httpOnly, secure) or a summary of the stored cookie.

#### Actual Behavior

Output is only "Cookie set: session_id". There is no signal that --domain/--path/--httpOnly/--secure took effect; the user must run cookie-list (and mentally match up fields) to confirm. A typo'd flag name that the parser silently ignored would go unnoticed.

#### Root Cause Analysis

handle_cookie_set prints a fixed success string with only the name after the backend call succeeds; it does not read back the cookie or re-render the params it sent.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_cookie_set — after the set call, fetch and print the resulting cookie (or echo the sent params)`

#### AI Suggested Improvement

- After setting, read back the cookie and print its attributes (one line, e.g. `Cookie set: session_id (localhost, path=/, httpOnly, secure)`)
- At minimum echo the flags that were actually forwarded, so silent flag-ignore bugs become visible

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified — success line is a fixed `"Cookie set: {name}"` (main.rs:4289). Prefer echoing the exact params that were forwarded (domain/path/httpOnly/secure/expires/sameSite) over a read-back roundtrip — it's one line, no extra backend call, no race, and it makes a silently-ignored flag visible. Note the typo'd-flag scenario is weaker than claimed if clap rejects unknown options, but the echo still serves the confirmation purpose.

---

### Issue 5: Snapshot file location (.browser4-cli/snapshot/ in CWD) is undocumented in the skill docs

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run any ./b4w.ps1 goto <url> (or any interaction command) from a project directory, then observe `ls -a`.

#### Expected Behavior

The skill documentation (SKILL.md or references/snapshot.md) explains where snapshot files are written, that they accumulate, and how to clean them (snapshot list / snapshot clean).

#### Actual Behavior

Every navigation/interaction writes a YAML file into a hidden `.browser4-cli/snapshot/` directory under the current working directory. Neither SKILL.md nor references/snapshot.md mentions this on-disk location or the existence of `snapshot list`/`snapshot clean` for managing accumulated files. The files are gitignored in this repo and the path is printed in command output, but a first-time user discovering a hidden directory full of files in their project has no documented explanation.

#### Root Cause Analysis

SNAPSHOT_DIR is hardcoded to CWD-relative [".browser4-cli", "snapshot"] in cli/browser4-cli/src/snapshot.rs:9; the docs describe snapshot output modes (--stdout, viewports) but never the default file-on-disk behavior.

#### Code Pointer

`skills/browser4-cli/references/snapshot.md — add a 'Where snapshots are stored' section (and optionally a note in SKILL.md §2 Key Concepts)`

#### AI Suggested Improvement

- Document the snapshot directory (CWD/.browser4-cli/snapshot/) and the `snapshot list` / `snapshot clean` commands in references/snapshot.md and the SKILL.md snapshot section
- Consider printing a one-time hint on first snapshot save (e.g. "Snapshots are saved to .browser4-cli/snapshot/ — use `snapshot clean` to remove old files")

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified that `snapshot-list`/`snapshot-clean` are completely absent from all skill docs and snapshot.md covers only output modes — the claim is slightly overbroad that SKILL.md never mentions the location (SKILL.md:101 and agent.md:103 do, for extract/summarize). Add a "Where snapshots are stored" section to snapshot.md documenting `.browser4-cli/snapshot/` accumulation plus `snapshot-list`/`snapshot-clean`, and reference it from SKILL.md rather than duplicating.

---

## Overall Assessment

**Completion Status:** Successful — all 17 task steps completed exactly as specified, verified at each stage, and the temporary state file was cleaned up. No errors, no retries, no workarounds needed beyond computing the Unix timestamp for --expires.

**Success Rate:** 100% — 17/17 task steps succeeded; every storage command (cookie/localStorage/sessionStorage/state roundtrip) worked first try against the local backend.

**Issues Found:** 5

**Most Confusing Aspects:** The inconsistent getter semantics across the Storage family (cookie-get returns a full JSON object while localstorage-get/sessionstorage-get return bare values); having to compute a Unix epoch timestamp for --expires when the task is phrased in human terms; not knowing where snapshot files land on disk or that they accumulate in the project directory.

**Most Valuable Improvements:** Align storage getter output conventions (bare value by default, full object via flag); accept human-friendly --expires values (ISO dates / relative durations); echo effective cookie attributes on cookie-set; document the snapshot directory and cleanup commands.

**Usability Rating:** 8/10

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

#### Issue 1: cookie-get returns the full cookie object instead of the value, inconsistent with localstorage-get/sessionstorage-get

./b4w.ps1 cookie-set theme dark --sameSite Lax --expires 1787321707
./b4w.ps1 cookie-get theme
Compare with: ./b4w.ps1 localstorage-get user_prefs

#### Issue 2: --expires accepts only a Unix timestamp — no ISO dates or relative durations

./b4w.ps1 cookie-set theme dark --expires 7d   # or --expires 2026-08-21T00:00:00Z

#### Issue 3: cookie-list/cookie-get serialize expires as a float (1787321707.0)

./b4w.ps1 cookie-set theme dark --expires 1787321707
./b4w.ps1 cookie-list

#### Issue 4: cookie-set success message does not echo the effective cookie attributes

./b4w.ps1 cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure

#### Issue 5: Snapshot file location (.browser4-cli/snapshot/ in CWD) is undocumented in the skill docs

Run any ./b4w.ps1 goto <url> (or any interaction command) from a project directory, then observe `ls -a`.

