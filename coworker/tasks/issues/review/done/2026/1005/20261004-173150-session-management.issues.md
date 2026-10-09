# Issues: session-management

> **Source:** `20261004-173150-session-management.full.md` | **Date:** 20261004-173150 | **Mode:** dev

## Scenario Background

### Task

All eight task steps completed successfully:

1. **research** session opened and navigated to `https://en.wikipedia.org/wiki/Browser_automation` — Wikipedia redirected it to `https://en.wikipedia.org/wiki/Headless_browser`; the CLI explicitly reported the redirect (`Navigated to … (redirected from …)`) and loaded the article.
2. **news** session opened and navigated to `https://news.ycombinator.com` (title "Hacker News").
3. Snapshot of the **news** session confirmed the HN front page (story rows, vote links, `login?goto=news` nav, etc.).
4. Snapshot of the **research** session confirmed the Wikipedia article (Wikipedia banner, `returnto=Headless+browser`, article tree).
5. `list --verbose` showed both named sessions with IDs, status Active, timestamps, connection, and next-open behavior.
6. `-s news close` succeeded — "Session closed. Browser terminated."
7. `list` afterwards showed only **research**.
8. `close-all` closed the remaining session(s); a final `list` reported "No active browser sessions."

Result: task **Successful**. Two output-quality anomalies surfaced (a permanently wrong `close-all` count and a missing page-URL detail in `list`), but no step failed and no blocker was hit.

### Execution Context

**Key Commands:**

```
help ; help list
-s research goto "https://en.wikipedia.org/wiki/Browser_automation"
-s news goto "https://news.ycombinator.com"
-s news snapshot -v 0 --stdout                      # verified HN (piped through head)
-s research snapshot -v 0 --stdout                  # verified Wikipedia (saved to scratch)
list --verbose ; list ; list --json ; list --all    # session inventory
-s news close
list --verbose ; list --json                        # confirmed only 'research'
close-all ; list                                    # closed remaining, confirmed empty
close-all (x4 more) ; status                        # characterizing the count anomaly
```
Investigation commands: read `~/.browser4/cli-state.json.bak`, `session-registry.json`, `cli-managed-processes.json`, and `D:/workspace/Browser4/Browser4-4.13/.browser4-cli-state/cli-state.json`; grep/read of `cli/browser4-cli/src/main.rs` and `state.rs`.

**Major steps / decisions:** followed the documented core loop; used `goto` (auto-creates sessions) rather than `open`; used the global `-s <name>` **before** the command as the SKILL warns; bounded the first `--stdout` snapshot with `head` per the warning not to dump full trees; then switched to redirecting output into the run scratch dir `.test-sessions/20261004T1433038523034Z/` (files: `snap-research.txt`, `pipeprobe-*.txt`) to keep full evidence without flooding the terminal.

**Workarounds:** none required for the task itself. All commands worked first try; the backend was already running (started 17:25:20Z), so no build/startup wait was needed. Two anomalies were investigated rather than worked around.

---

---

## Issues Found (5 issues)

### Issue 1: close-all permanently reports "Closed 1 session(s)" even when no sessions exist

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. ./b4w.ps1 close-all          # after all sessions are closed
2. ./b4w.ps1 list               # -> "No active browser sessions."
3. ./b4w.ps1 close-all          # -> "Closed 1 session(s)" again
4. Repeat step 3 any number of times — it never reaches 0

#### Expected Behavior

After all sessions are closed, close-all should report "Closed 0 session(s)" (or "No sessions to close"), and repeated invocations should stay at 0. Its count should match what `list` shows, as the code comment claims.

#### Actual Behavior

close-all reported "Closed 2 session(s)" the first time (when list showed only 1 named session), then "Closed 1 session(s)" on every subsequent run even though list reported "No active browser sessions."

#### Root Cause Analysis

count_tracked_sessions() (main.rs:4507) counts the default session via read_state(Some(&dir), None). read_state (state.rs:311-318) falls back to the CWD-relative ./.browser4-cli-state/cli-state.json whenever ~/.browser4/cli-state.json is missing — and this fallback file exists from 2026-08-13 and holds a dead session id (39f32e32-…). clear_all_state() (state.rs:423) only deletes the primary ~/.browser4 state files and ~/.browser4/sessions/*.json; it never deletes the fallback file, so the phantom default session is re-read and re-counted on every run forever. The count therefore can never reach 0. Additionally, handle_list() intentionally hides this session (backend_knows_session filter, main.rs:4424-4427) while `status` still displays it as Stale — so the number close-all reports is invisible in list.

#### Code Pointer

`cli/browser4-cli/src/main.rs:count_tracked_sessions() and handle_close_all(); cli/browser4-cli/src/state.rs:clear_all_state() / read_state() fallback (state.rs:311-318)`

#### AI Suggested Improvement

- Make count_tracked_sessions() apply the same backend-knows-session filter that handle_list() uses, so the reported count matches what list would show.
- Make clear_all_state() also delete the fallback state file (fallback_state_dir()) or, better, only use the fallback for reads when the primary directory is genuinely unwritable (as the docs state) rather than when the primary file merely does not exist.
- Treat a default session the backend no longer knows as stale-and-closed: have close-all verify at least one session was actually closed (or the backend knew it) before printing a non-zero count, and print "No sessions to close" at 0.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: SKILL.md says `list` shows each session's current page URL; no list variant does

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Open two sessions on different sites, then run:
./b4w.ps1 list
./b4w.ps1 list --verbose
./b4w.ps1 list --json

#### Expected Behavior

Per skills/browser4-cli/SKILL.md §2 Sessions ("`browser4-cli list` shows every session and its current page URL"): the output should include the current page URL for each session — which is also the natural meaning of the task's "list all active sessions with full details".

#### Actual Behavior

Output columns are only Name | Session ID | Status | Created | Last Access | Connection | Next open. The URL is absent in plain, --verbose, --all, and --json output (JSON contains no url field at all). To learn which page a session is on, the user must switch into it and run page-info/tab-list.

#### Root Cause Analysis

The list implementation (handle_list, main.rs:4363+) builds SessionRow without any URL field, and the backend-session record merge does not carry one through. Either the SKILL.md sentence documents planned/removed functionality or list lost the URL column in a refactor. help list also does not mention a URL, so the documentation set is internally inconsistent.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_list() / SessionRow (main.rs:4363-4470); docs in skills/browser4-cli/SKILL.md §2 Sessions`

#### AI Suggested Improvement

- Add a URL (or truncated title+URL) column to list, populated from the backend session record — this matches the documented contract and makes multi-session work much easier.
- At minimum, correct SKILL.md so it does not promise a URL column that does not exist, and note how to inspect a session's current page.
- If adding a column crowds the table, expose it in --json and --verbose only.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: list hides a stale default session that status still reports, so the two commands disagree

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 list     # -> "No active browser sessions."
./b4w.ps1 status   # -> Session: Name: (default), Session ID: 39f32e32-…, Status: Stale, Next open: Refresh

#### Expected Behavior

Session inventory commands should agree: either show the stale default session in list (with its Stale status) or omit it from status too.

#### Actual Behavior

list (plain, --json, --all, --verbose) omits the default session entirely and prints the empty-state message, while status one command later reports that same session as Stale / Refresh.

#### Root Cause Analysis

handle_list() gates the default-session row on backend_knows_session (main.rs:4424-4427): if the backend session records do not contain the locally-saved id, the row is dropped. handle_status() reads the same local state without that gate, so the stale record appears there. This inconsistency is what makes the close-all phantom count (issue 1) undiscoverable from list.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_list() default-session block (main.rs:4421-4446) vs the status handler`

#### AI Suggested Improvement

- Include backend-unknown saved sessions in list with Status=Stale and Next open=Refresh instead of hiding them; users can then see (and expect) what close-all counts.
- If hiding is intentional, make status apply the same rule so both commands tell one story.
- Consider a one-line hint in list's empty state when a stale default record exists locally ("a stale default session is still tracked; run close-all").

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `list --verbose` help text describes a truncation that can never occur

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 help list

#### Expected Behavior

Help should describe an observable difference between list and list --verbose, or say that --verbose is a no-op for current session IDs.

#### Actual Behavior

Help says: "Use --verbose to show full session IDs without truncation (UUIDs are 36 chars, truncated to 40 by default)" — a 36-char value truncated to 40 chars is a no-op, and the session IDs rendered with and without --verbose are byte-for-byte identical in every run observed.

#### Root Cause Analysis

The table sets max_width 40 for the session-id column when not verbose and 0 (unlimited) when verbose (main.rs:4457), but UUIDs are 36 chars, so both settings render the full ID. The help text appears to be a leftover from a format where the ID column (name+ID or a longer GUID) exceeded 40 chars.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_list() table construction (main.rs:4457-4464); help text in the list command's help block`

#### AI Suggested Improvement

- Reword the help note to state the actual rule ("--verbose removes the 40-character cap on session IDs; current UUIDs are 36 chars and always fit") or drop --verbose if it adds nothing today.
- If the cap was meant to bound name+id display, apply it to the combined cell so the flag has a visible effect.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: One-off exit code 101 when piping a large snapshot --stdout into head (not reproducible)

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s news snapshot -v 0 --stdout 2>&1 | head -60   # observed once, exit 101
# Three subsequent attempts of the identical form (different page, same size class) all exited 0

#### Expected Behavior

Piping bounded large CLI output into head (the documented way to bound snapshot dumps) should terminate cleanly, exit 0, or fail with a clear message — not a Rust panic-style exit code.

#### Actual Behavior

The first invocation exited 101 (Rust panic exit code) with the snapshot content itself printed normally; the same command shape later exited 0 three times, including on the same Wikipedia page.

#### Root Cause Analysis

Not determined; possibly Rust's println!/write to a closed stdout raising EPIPE (panic → 101) when the writer outlives head, or a transient interaction with the pwsh b4w.ps1 wrapper's stream handling. The reproducibility failure means the trigger condition is unknown — candidates to check: first-command-after-daemon-start timing, stderr/stdout interleaving (2>&1), and which stream the snapshot tree is written on. A Rust-side use of writeln!(stdout) with an EPIPE-ignoring wrapper would make this deterministic.

#### Code Pointer

`cli/browser4-cli/src/main.rs (snapshot --stdout write path); wrapper b4w.ps1`

#### AI Suggested Improvement

- Make stdout writes EPIPE-tolerant (ignore BrokenPipe or exit 0/141 silently) in the snapshot output path.
- Log/annotate the panic if it recurs so the trigger can be identified; a single non-reproducible 101 does not justify more invasive change yet.
- Add a test that pipes a >64KB --stdout snapshot through a closing reader to check exit behavior.

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

**Completion Status:** Successful — all 8 task steps completed: both named sessions created and navigated, both snapshotted and verified, sessions listed, 'news' closed, 'research' confirmed remaining, then all sessions closed.

**Success Rate:** 100% of the 8 task steps completed (2 output-quality anomalies found, but no step failed and no workaround was needed)

**Issues Found:** 5

**Most Confusing Aspects:** close-all reporting "Closed 1 session(s)" on every run with zero sessions listed, and list/status disagreeing about a phantom default session (list says none, status says Stale). Also, list promises 'full details' but shows no page URL, so after step 5 a user still cannot tell which site each session is on without switching into it.

**Most Valuable Improvements:** 1) Fix the fallback-state/close-all asymmetry so phantom sessions stop being counted and reported (also aligns list and status). 2) Add the documented page URL to list output. 3) Reconcile the list documentation (URL column, --verbose truncation note) with actual behavior.

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

#### Issue 1: close-all permanently reports "Closed 1 session(s)" even when no sessions exist

1. ./b4w.ps1 close-all          # after all sessions are closed
2. ./b4w.ps1 list               # -> "No active browser sessions."
3. ./b4w.ps1 close-all          # -> "Closed 1 session(s)" again
4. Repeat step 3 any number of times — it never reaches 0

#### Issue 2: SKILL.md says `list` shows each session's current page URL; no list variant does

Open two sessions on different sites, then run:
./b4w.ps1 list
./b4w.ps1 list --verbose
./b4w.ps1 list --json

#### Issue 3: list hides a stale default session that status still reports, so the two commands disagree

./b4w.ps1 list     # -> "No active browser sessions."
./b4w.ps1 status   # -> Session: Name: (default), Session ID: 39f32e32-…, Status: Stale, Next open: Refresh

#### Issue 4: `list --verbose` help text describes a truncation that can never occur

./b4w.ps1 help list

#### Issue 5: One-off exit code 101 when piping a large snapshot --stdout into head (not reproducible)

./b4w.ps1 -s news snapshot -v 0 --stdout 2>&1 | head -60   # observed once, exit 101
# Three subsequent attempts of the identical form (different page, same size class) all exited 0

