# Issues: session-management

> **Source:** `20261015-133022-session-management.issues.json` | **Date:** 20261015-133022 | **Mode:** dev

## Scenario Background

### Task

**All 8 task steps completed successfully.**

| # | Step | Outcome |
|---|------|---------|
| 1 | Open named session `research` → `https://en.wikipedia.org/wiki/Browser_automation` | ✅ Session `9bfcfe43…` created (headless). Wikipedia itself redirects this title to `/wiki/Headless_browser` — verified via the page's own metadata (`mw.config.get('wgRedirectedFrom')` = `Browser_automation`), so this is correct navigation, not a CLI fault. |
| 2 | Open named session `news` → `https://news.ycombinator.com` | ✅ Session `9b4e7a1e…`, URL + title "Hacker News" |
| 3 | Snapshot in `news` | ✅ `snapshot -i -v 0` showed HN nav (new/past/comments/ask/show/jobs/submit/login) and story links |
| 4 | Switch back to `research`, snapshot | ✅ Still on the Wikipedia article (session state preserved while the other session ran); snapshot showed Wikipedia chrome + article |
| 5 | List all sessions with full details | ✅ Both sessions `Active · Headless · Reuse`, full URLs, IDs, timestamps (`list --verbose`, `list --json`) |
| 6 | Close `news` | ✅ "Session closed. Browser terminated." |
| 7 | List again | ✅ Only `research` remained |
| 8 | Close all remaining | ✅ `close-all` → "Closed 1 session(s)"; `list` → "No active browser sessions."; backend `doctor status --section sessions` → `total: 0` |

**Caveat on step 8 (investigated, not a task failure):** the session bookkeeping says zero sessions, but the underlying Chrome processes for several closed sessions are still running (Issue 2).

### Execution Context

**Key Commands:**

**Workarounds / investigations**
- `curl` to Wikipedia failed from this host (SSL error) → verified the redirect inside the page instead (`eval "mw.config.get('wgRedirectedFrom')"`), which is authoritative.
- Cross-checked `list --json --pretty` vs `--pretty list --json` to isolate the `--pretty` position behaviour (Issue 4).
- Cross-checked `-s` placement (`open -s x <url>`, `goto <url> -s x`) → Issue 5.
- Lifecycle experiment: set a localStorage marker → `close` → reopen (new session id, **marker gone**) vs set marker → `stop` → reopen (same session id, **marker intact**) → Issues 1 and 3.
- Process forensics via `Get-CimInstance Win32_Process` (profile dirs are `--user-data-dir=…\context\groups\named\PULSAR_CHROME\cx.<SESSION-ID>\…`) → Issue 2.
- CLI-generated snapshot YAMLs are written to `<cwd>/.browser4-cli/snapshot/` (the repo root here) — that is the tool's documented default; I left them in place and copied the ones I used into the run directory, along with all command outputs (`ms-00`…`ms-33`) and `ms-evidence.md`.
- Intentionally did **not** run `kill-all` (cross-workspace scope) — the leaked browser processes remain as evidence.



**Overall usability rating: 7/10.** The multi-session workflow this task exercises worked exactly as documented — create, coexist, switch, verify, list, close, close-all — with clear output, correct exit codes, and excellent help/skill documentation (the `-s`-before-command rule and snapshot modes were easy to learn). It loses points where the docs and behaviour disagree on session-lifecycle semantics, where success messages overstate what happened (browser termination), and on two small argument-handling rough edges that silently swallow user intent.

Evidence for every finding is saved in `.test-sessions/20261015T0901431699046Z/` (`ms-00`…`ms-33` outputs, `ms-evidence.md`, process dumps, and snapshot copies); the only sessions/processes left behind are the pre-existing leaked browsers described in Issue 2, which I deliberately left untouched rather than running the cross-workspace `kill-all`.

---

## Issues Found (5 issues)

### Issue 1: Closing a named session then reopening it silently discards all browser state (new session id, fresh profile)

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1) ./b4w.ps1 -s research open --headless https://example.com
2) ./b4w.ps1 -s research localstorage-set eval-key eval-value-12345  (read back: eval-value-12345)
3) ./b4w.ps1 -s research close        -> "Session closed. Browser terminated."
4) ./b4w.ps1 -s research open --headless https://example.com   -> "Session opened: research (46c69ce8-...)" (previous id was 743ea2cf-...)
5) ./b4w.ps1 -s research localstorage-get eval-key  -> "Error: localStorage key not found: eval-key" (exit 1)

#### Expected Behavior

SKILL.md §2 Sessions: "Named sessions isolate browser state (cookies, localStorage, tabs) in a dedicated browser profile directory keyed by the session id — reopening always restores the same profile." So reopening 'research' should restore the same profile and eval-key should still be readable. The existence of `open --fresh` ("so tabs, cookies, and location state from a prior run are not inherited") implies that a plain `open` DOES inherit prior state.

#### Actual Behavior

`close` removes the local session record; the next `open -s research` mints a NEW session id and starts a fresh browser profile. All cookies/localStorage from before the close are gone, with no warning. For comparison, `stop` + reopen revived the SAME id (46c69ce8) with localStorage intact — i.e. close destroys state while stop preserves it, which is the opposite of what the SKILL says.

#### Root Cause Analysis

`handle_close()` (main.rs:3399) calls the backend close_session tool and then unconditionally deletes the local record via `clear_state()` (main.rs:3419), which also drops the name->session-id mapping. The browser profile directory is keyed by session id (verified from the live process cmdline: --user-data-dir=...\groups\named\PULSAR_CHROME\cx.<SESSION-ID>\PULSAR_CHROME), so a new id = a new, empty profile. `stop` instead marks records stale (main.rs:5315), so the id and profile survive. The SKILL text describes a different model than the code implements.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_close() (clear_state ~line 3419); skills/browser4-cli/SKILL.md §2 Sessions`

#### AI Suggested Improvement

- Decide the intended semantics and align doc + code: either keep a name->session-id/profile mapping across `close` so `open -s <name>` reattaches to the same profile, or state explicitly in SKILL.md and `close --help` that `close` discards the session's persisted state while `stop` preserves it.
- If state loss is intended, make `open` warn when it creates a fresh profile for a previously-known name (e.g. "previous profile for 'research' was closed; starting fresh — use state-save/state-load to migrate").
- Have `close` print what was discarded ("Session closed; browser profile <dir> removed/discarded").
- Follow the AGENTS.md 'Documentation Update Rule' (README, SKILL.md, help.rs, tips.rs) when updating the wording.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `close`/`close-all` report the browser terminated while its Chrome process keeps running (misleading success + process leak)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 close-all                    -> "Closed 6 session(s)"
2) powershell: Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" | ? { $_.CommandLine -notmatch '--type=' } | select -ExpandProperty CommandLine
3) Six live processes survive whose --user-data-dir contains cx.<session-id> matching the sessions just closed: cx.82f06ef1 (books-eval), cx.68f8ca31 (cap), cx.3f2e94a0 (img-eval), cx.55c3a9b5 (md-test), cx.660cfaf8 (media-eval), cx.6d6503bb (pptx-eval, Active at baseline).
4) Single-session variant: ./b4w.ps1 -s research close -> "Session closed. Browser terminated.", yet the cx.46c69ce8 browser process is still alive minutes later (verified twice).

#### Expected Behavior

After close/close-all report success — and especially after the literal message "Browser terminated." — the managed browser process should be gone, or the command should say it could not be terminated (and how to reap it).

#### Actual Behavior

Browser processes remain alive while the CLI claims success; `list` and `doctor status --section sessions` report zero sessions, so the user believes the browsers are gone. Some leaked processes had been alive for ~2 hours (started 19:41-20:57, observed alive at 21:33). Browsers spawned by the current backend instance and then closed did terminate correctly, so the leak affects sessions whose browser was spawned by a previous backend instance (stale/revived sessions).

#### Root Cause Analysis

handle_close() (main.rs:3399) only warns when the close_session MCP call itself fails (warn_if_session_close_failed, main.rs:3380), then prints the hardcoded "Session closed. Browser terminated." (main.rs:3449) without verifying the process for managed sessions — unlike the attached-session path, which probes CDP (cdp_endpoint_alive, main.rs:3458) and reports the true outcome. Backend restarts orphan browser processes that later close/close-all calls cannot kill; the code comment at main.rs:3378 expects such orphans to be "reaped by the backend after its idle timeout", but ~2-hour-old processes were still alive.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_close() (~3399-3452) and the close-all handler (~4277); backend close_session handling`

#### AI Suggested Improvement

- After a successful close_session call, verify the browser actually exited (probe the session's CDP endpoint / track the PID in the session record) and only then print "Browser terminated."; otherwise print a warning naming the surviving PID.
- Persist browser PIDs per session (cli-managed-processes.json already exists) so close/close-all can kill orphans spawned by previous backend instances, and surface them in `list --verbose` or `doctor`.
- Make close-all report the real outcome, e.g. "Closed 6 session(s); 6 browser process(es) still running (use `kill-all`)".
- Consider reaping orphans at backend startup (sessions/*.json records could carry the browser PID).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: SKILL.md's `stop` documentation is inverted in this build: records are kept and revived, not deleted

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s research open --headless https://example.com
./b4w.ps1 stop
./b4w.ps1 list --verbose
./b4w.ps1 -s research goto https://example.com

#### Expected Behavior

SKILL.md §2: "stop … removes every sessions/*.json record, so the next `list` prints 'No active browser sessions.' and the documented Stale / Refresh rows never appear"; and "Use close/close-all when the CLI must stay able to list and reconnect".

#### Actual Behavior

Stop printed: "Marked 1 session record(s) stale — they stay in `list` and are revived by `open`/`goto` after the backend restarts."; the record file persisted on disk; `list` showed the 'research' row (Status Unknown, Next open Refresh) plus a note that the backend is unreachable — it did NOT print "No active browser sessions.". The next `goto` revived the SAME session id with its localStorage intact. Conversely, `close` DID drop the record entirely (row disappeared). So the doc's guidance steers users to the command that destroys state and away from the one that preserves it. Additionally, the 'Next open: Refresh' label (documented as 'opens fresh') was wrong: the session was reattached, not refreshed.

#### Root Cause Analysis

Documentation lag: the implemented semantics (main.rs:5315 marks records stale on stop; close/close-all clear the record) are the reverse of the SKILL paragraph. The stop/close lifecycle was likely reworked without updating SKILL.md. The stale-row prediction text and 'Next open' classification in `list` add a second misleading layer.

#### Code Pointer

`skills/browser4-cli/SKILL.md §2 Sessions (the `stop` blockquote); cli/browser4-cli/src/main.rs:5315 (stop message) and the `list` Next-open classification`

#### AI Suggested Improvement

- Rewrite the stop paragraph: `stop` marks records stale (they remain listed and are revived by `open`/`goto` with the same session id); `close` is the command that drops the record and its profile state.
- Reword the `list` prediction so a post-stop row does not read 'Next open: Refresh' when the next open actually reattaches to the still-running browser.
- Cross-reference this from the `close`/`stop` command help so users pick the right command without reading the deep-dive docs.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `--pretty` is silently ignored when placed after the command name

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 --pretty list --json     # pretty-printed JSON (works)
./b4w.ps1 list --json --pretty     # single-line compact JSON, exit 0, no warning
./b4w.ps1 --pretty status --json   # pretty (works)
./b4w.ps1 status --json --pretty   # compact, exit 0 (same bug)

#### Expected Behavior

Either the flag works wherever it appears (like `-q`, which IS hoisted after the command) or it is rejected with the existing 'global flags must appear before the command' hint.

#### Actual Behavior

Silently ignored — exit code 0 and no diagnostic, so users and scripts cannot tell the flag did nothing. `--pretty` is documented under Global options without a position caveat; only `-q/--quiet`, `--timeout` (hoisted) and `--json` (never hoisted) are discussed in the 'Global flags after the command' note.

#### Root Cause Analysis

args.rs:110 only sets the pretty flag when `!seen_command`. A post-command `--pretty` is pushed into the command's args; validate_command_options tolerates it because LATE_GLOBAL_FLAG_KEYS (args.rs:395) includes "pretty", and hoist_post_command_global_flags (main.rs:26677) hoists only `-q/--quiet` and `--timeout`. Nothing ever consumes the token, so it is dropped on the floor.

#### Code Pointer

`cli/browser4-cli/src/main.rs:hoist_post_command_global_flags(); cli/browser4-cli/src/args.rs:110 and :395`

#### AI Suggested Improvement

- Hoist `--pretty` alongside `-q/--quiet` in hoist_post_command_global_flags (set flags.pretty when seen post-command).
- Alternatively remove "pretty" from LATE_GLOBAL_FLAG_KEYS so the token is rejected as a stray global flag with the existing 'global flags must appear before the command' hint (args.rs:513).
- Add a unit test mirroring hoist_global_flags_hoists_quiet_after_command for --pretty.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Misplaced `-s/--session` produces no hint about the global-flag position rule

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 open -s research https://example.com
-> error: unknown option -s for 'open' (the command takes 1 positional argument). Supported options: --headed, --headless, --fresh, --profile, --profile-mode, --interact-level. (exit 1)
./b4w.ps1 goto https://example.com -s test   -> same style of error

#### Expected Behavior

Because the docs stress "`-s` is a GLOBAL flag — its position is part of the syntax" (and the usage line shows [-s <session>] before <command>), the error should say so, e.g. "`-s/--session` is a global flag; place it before the command: browser4-cli -s research open <url>". The CLI already appends this class of hint for other misplaced global flags (global_flag_hint).

#### Actual Behavior

A generic 'unknown option' error. This is the single most likely mistake for a first-time user (the author of this report hit it while preparing), yet the error teaches nothing about placement; the user must dig the rule out of the docs.

#### Root Cause Analysis

Unknown-option errors are produced by validate_command_options (args.rs:422) with no session-flag special case, and is_global_flag_token (args.rs:377) — which drives the placement hint — lists -q/--json/--pretty/--show-tip/--help-json/--timeout but not -s/--session. So the targeted hint can never fire for the session flag.

#### Code Pointer

`cli/browser4-cli/src/args.rs:is_global_flag_token() and validate_command_options()`

#### AI Suggested Improvement

- Append the placement hint when the rejected option key is `s`/`session` in the unknown-option error path (name `--session` explicitly, since `-s` is reserved globally for it).
- Add `-s`/`--session` to the global-flag token set used for hints, taking care that some commands may legitimately define their own `-s` (mention the alternative spelling when relevant, e.g. snapshot's `--selector`).
- Add a CLI test asserting the hint appears for `open -s x <url>`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 8 requested steps completed on the first attempt with the documented commands: named sessions 'research' and 'news' ran concurrently and independently, snapshots verified each page, the session list showed both with full details, closing 'news' left only 'research', and the final close-all left zero sessions (verified via list and the backend's doctor status). The Wikipedia URL change during step 1 was investigated and is Wikipedia's own redirect, not a CLI fault.

**Success Rate:** 100% of task steps (8/8); 2 follow-up lifecycle checks (close→reopen state, stop→restart revival) revealed the findings above.

**Issues Found:** 5

**Major Blockers:** None for the task itself. Environment notes: 6 leftover sessions from earlier phases had to be closed first for a clean baseline; direct curl from this host is blocked (SSL), which only affected an out-of-band verification.

**Most Confusing Aspects:** 1) close vs stop semantics are undocumented in the CLI's own output and described backwards in SKILL.md — closing a named session silently discards its cookies/localStorage (new session id on reopen) while stop preserves them. 2) 'Session closed. Browser terminated.' / 'Closed N session(s)' are printed even when the Chrome process survives. 3) `--pretty` works before the command but is silently ignored after it, while `-q` does work after it. 4) The `-s` position rule is heavily documented but its misuse yields a plain 'unknown option' error with no hint.

**Most Valuable Improvements:** 1) Make session-lifecycle state semantics explicit and consistent: either keep the profile across close (and say so), or warn loudly that close discards state while stop preserves it. 2) Verify actual browser termination before printing 'Browser terminated.' and track/kill orphaned browser PIDs (the leaked headless Chromes accumulated across phases). 3) Fix the --pretty/global-flag plumbing (hoist or reject-with-hint) and add the -s placement hint to the unknown-option error.

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

#### Issue 1: Closing a named session then reopening it silently discards all browser state (new session id, fresh profile)

1) ./b4w.ps1 -s research open --headless https://example.com
2) ./b4w.ps1 -s research localstorage-set eval-key eval-value-12345  (read back: eval-value-12345)
3) ./b4w.ps1 -s research close        -> "Session closed. Browser terminated."
4) ./b4w.ps1 -s research open --headless https://example.com   -> "Session opened: research (46c69ce8-...)" (previous id was 743ea2cf-...)
5) ./b4w.ps1 -s research localstorage-get eval-key  -> "Error: localStorage key not found: eval-key" (exit 1)

#### Issue 2: `close`/`close-all` report the browser terminated while its Chrome process keeps running (misleading success + process leak)

1) ./b4w.ps1 close-all                    -> "Closed 6 session(s)"
2) powershell: Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" | ? { $_.CommandLine -notmatch '--type=' } | select -ExpandProperty CommandLine
3) Six live processes survive whose --user-data-dir contains cx.<session-id> matching the sessions just closed: cx.82f06ef1 (books-eval), cx.68f8ca31 (cap), cx.3f2e94a0 (img-eval), cx.55c3a9b5 (md-test), cx.660cfaf8 (media-eval), cx.6d6503bb (pptx-eval, Active at baseline).
4) Single-session variant: ./b4w.ps1 -s research close -> "Session closed. Browser terminated.", yet the cx.46c69ce8 browser process is still alive minutes later (verified twice).

#### Issue 3: SKILL.md's `stop` documentation is inverted in this build: records are kept and revived, not deleted

./b4w.ps1 -s research open --headless https://example.com
./b4w.ps1 stop
./b4w.ps1 list --verbose
./b4w.ps1 -s research goto https://example.com

#### Issue 4: `--pretty` is silently ignored when placed after the command name

./b4w.ps1 --pretty list --json     # pretty-printed JSON (works)
./b4w.ps1 list --json --pretty     # single-line compact JSON, exit 0, no warning
./b4w.ps1 --pretty status --json   # pretty (works)
./b4w.ps1 status --json --pretty   # compact, exit 0 (same bug)

#### Issue 5: Misplaced `-s/--session` produces no hint about the global-flag position rule

./b4w.ps1 open -s research https://example.com
-> error: unknown option -s for 'open' (the command takes 1 positional argument). Supported options: --headed, --headless, --fresh, --profile, --profile-mode, --interact-level. (exit 1)
./b4w.ps1 goto https://example.com -s test   -> same style of error

