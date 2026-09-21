# Issues: storage-state-management

> **Source:** `20260905-181853-storage-state-management.full.md` | **Date:** 20260905-181853 | **Mode:** dev

## Scenario Background

### Task

**All 17 task steps completed successfully (100%).** Every cookie, localStorage, and sessionStorage operation behaved correctly and verifiably:

- Cookies `session_id` (`--domain localhost --path / --httpOnly --secure`) and `theme` (`--sameSite Lax --expires 7d`) were set, listed, domain-filtered, read back (`dark`), deleted, and cleared.
- localStorage JSON value survived set → list → get verbatim (`{"lang":"en","tz":"UTC"}`).
- sessionStorage full lifecycle (set/list/get/delete/clear) worked.
- `state-save` → wipe → `state-load` roundtrip restored the `session_id` cookie **with all attributes** (httpOnly, secure, domain, path) plus the localStorage entry; summary line reported counts (`cookies: 1, origins: 1, localStorage entries: 1`).
- `browser_state.json` created inside `.test-sessions/` and deleted at the end; browser returned to its clean baseline (empty cookie jar, empty localStorage).

One failure was encountered mid-task (`--path /` rejected) and worked around cleanly — details below. All state was left clean.

### Execution Context

| Step | Command | Result |
|---|---|---|
| Prep | `./b4w.ps1 help`; read `skills/browser4-cli/SKILL.md` + `references/storage-state.md`; per-command `--help` for all cookie/storage/state commands | Commands and flags discovered from docs; storage family was easy to find (`[Storage]` section in help, Command Map row → storage-state.md) |
| 1 | `./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"` | Reconnected to pre-existing DEFAULT session already on the page ("Already at … page unchanged") — daemon auto-start worked instantly |
| 2 | `./b4w.ps1 cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure` | **Failed:** `option '--path' was rejected: 'C:/Program Files/Git/' is not a valid cookie path` — Git Bash MSYS rewrote the bare `/` argument before p...

(truncated — see full.md for complete trace)

---

## Issues Found (3 issues)

### Issue 1: Git Bash MSYS path conversion breaks the documented `--path /` examples when invoking ./b4w.ps1 directly

**Severity:** Medium
**Category:** Product

#### Reproduction

From Git Bash on Windows: ./b4w.ps1 cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
→ Error: option '--path' was rejected: 'C:/Program Files/Git/' is not a valid cookie path (must start with '/') (exit 1).
The bare `/` argument is rewritten by MSYS2 to the Git installation root before pwsh starts. Workaround: MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 … or ./b4w.sh …

#### Expected Behavior

b4w.ps1's mangled-argument guard (added precisely to fail fast on this MSYS conversion class) should detect the rewrite and print its 'Run this command via ./b4w.sh' guidance, so a first-time user understands the failure. The documented examples using `--path /` (cookie-set --help, cookie-delete --help, references/storage-state.md) should work as written on a supported shell combo.

#### Actual Behavior

The guard silently missed the mangled argument, and the CLI reported a confusing validation error naming a path the user never typed ('C:/Program Files/Git/'). The cookie was not set; a first-time user following the help example verbatim hits a dead end with no hint that the shell is at fault.

#### Root Cause Analysis

MSYS2 path conversion rewrites an argument consisting of exactly '/' into '<GitRoot>/' when Git Bash spawns pwsh. b4w.sh solves this by exporting MSYS2_ARG_CONV_EXCL='*', but b4w.ps1 invoked directly is unprotected for this specific case: its guard (b4w.ps1 lines ~116-129) compares $ArgFwd.StartsWith($RootFwd) with a length check `$ArgFwd.Length -gt $RootFwd.Length`, and the mangled value 'C:/Program Files/Git/' equals RootFwd exactly (same length), so the guard does not fire and forwards the mangled token to the CLI. Guard code needs `-ge` (or trimming) to catch the exact-root case.

#### Code Pointer

`b4w.ps1 lines 116-129 (MSYS mangled-argument guard; `-gt` should be `-ge`). Secondary: skills/browser4-cli/references/storage-state.md cookie examples and cli/browser4-cli cookie-set/cookie-delete help text could add a Git Bash note.`

#### AI Suggested Improvement

- Change the guard's `$ArgFwd.Length -gt $RootFwd.Length` to `-ge` (or strip the trailing slash before comparing) so an exactly-root rewrite is detected and the existing fail-fast guidance prints
- Add a Git Bash note next to `--path /` examples in storage-state.md and the command help: 'Git Bash: use ./b4w.sh or prefix MSYS2_ARG_CONV_EXCL=\'*\''
- When a --path/--domain validation failure receives a value that looks like a rewritten Windows path, have the CLI append a hint that MSYS path conversion may have mangled the argument

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Root cause verified against b4w.ps1:119 — the `-gt` check misses an exactly-root-length rewrite, and the observed `C:/Program Files/Git/` value equals `RootFwd` precisely. Apply the `-ge` fix but normalize trailing slashes on both sides first, since a rewrite arriving without the trailing slash would fail the `StartsWith` prefix check even after the comparison change; the docs note in storage-state.md is worth doing alongside, while the CLI-side hint is a lower-priority nice-to-have.

---

### Issue 2: cookie-delete reports success when no matching cookie exists — misleading output, inconsistent with other storage delete commands

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 cookie-delete no_such_cookie
→ prints 'Cookie deleted: no_such_cookie', exit 0 — even though no such cookie exists (verify with cookie-list: jar unchanged).
Contrast: ./b4w.ps1 localstorage-delete no_such_key → 'localStorage key not present: no_such_key' (exit 0); ./b4w.ps1 cookie-get no_such_cookie → 'Error: Cookie not found: no_such_cookie' (exit 1).

#### Expected Behavior

The command should not claim a deletion that did not happen. Either report 'Cookie not found: no_such_cookie (nothing to delete)' like the storage families, or delete-then-verify and only print 'Cookie deleted' when the cookie actually existed.

#### Actual Behavior

Deleting a nonexistent cookie prints an unconditional success line. In automation this is a silent failure: if the domain default or name is wrong (e.g. the cookie lives on another domain and no --domain was passed), a script/AI believes cleanup of an auth cookie succeeded while the cookie remains in the jar.

#### Root Cause Analysis

cli/browser4-cli/src/main.rs handle_cookie_delete (line 4593) discards the tool result (`let _ = call_session_tool(...)` at line 4620) and unconditionally prints 'Cookie deleted: {}' at line 4621. CDP Network.deleteCookies does not error when the cookie is absent, so nothing downstream distinguishes 'deleted' from 'did not exist'. handle_storage_delete in the same file already distinguishes the not-present case for localstorage/sessionstorage.

#### Code Pointer

`cli/browser4-cli/src/main.rs:4593-4623 handle_cookie_delete`

#### AI Suggested Improvement

- Look up the cookie first (cookie-get path exists at main.rs:4463) or check the tool response, and print 'Cookie deleted: X' only when it existed, otherwise 'Cookie not found: X' (keep exit 0 for idempotency, or add a --strict flag that exits nonzero)
- Align wording with localstorage-delete/sessionstorage-delete ('key not present') for a consistent storage-family vocabulary
- Add a regression unit test covering delete-of-missing-cookie output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified at main.rs:4620-4621 — the result is discarded and success is printed unconditionally, a genuine silent-failure hazard for automation, and cookie-get already provides the lookup machinery to fix it cheaply. Prefer the storage-family wording ('Cookie not found: X (nothing to delete)') with exit 0 to keep delete idempotent, matching localstorage-delete/sessionstorage-delete behavior.

---

### Issue 3: state-load error output mixes English CLI text with OS-locale error strings (Chinese on zh-CN Windows)

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 state-load .test-sessions/does-not-exist.json
→ 'Error: Failed to read storage state file D:\workspace\...\does-not-exist.json: 系统找不到指定的文件。 (os error 2)' (exit 1)

#### Expected Behavior

Deterministic, locale-independent error text so scripts and AI agents can rely on stable messages, e.g. '…: no such file or directory (os error 2)'.

#### Actual Behavior

The raw io::Error Display string is rendered in the OS language (Chinese on this Windows 11 zh-CN machine) inside an otherwise-English CLI message. Output varies by machine locale and is confusing in mixed-language log files.

#### Root Cause Analysis

handle_state_load (main.rs:4142) interpolates the raw io::Error into the message at line 4156. Rust's io::Error Display is locale-dependent for OS errors; the CLI should map io::ErrorKind (NotFound) to a stable English description instead of printing the raw OS string.

#### Code Pointer

`cli/browser4-cli/src/main.rs:4142-4156 handle_state_load`

#### AI Suggested Improvement

- Map common io::ErrorKind values (NotFound, PermissionDenied, AlreadyExists) to fixed English text before formatting
- Audit other file-touching handlers (state-save, htmlsnapshot export, crawl seed files) for the same raw io::Error interpolation pattern

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified at main.rs:4154-4160 — raw io::Error Display is locale-dependent on Windows (FormatMessageW), and the same interpolation recurs at main.rs:4137 and in other file-touching handlers, so the audit is warranted. Map ErrorKind::NotFound/PermissionDenied/AlreadyExists to fixed English text but keep the raw OS error as a fallback for unclassified codes, and assert the stable prefix in a regression test rather than matching the full message.

---

## Overall Assessment

**Completion Status:** Successful — all 17 task steps completed; every cookie/storage/state operation verified end-to-end, one shell-integration failure worked around cleanly

**Success Rate:** 100% — 17/17 task steps succeeded (one required an MSYS env-var workaround for a Git Bash argument-conversion defect)

**Issues Found:** 3

**Major Blockers:** None — the only failure (--path / rejected) was diagnosed quickly from the error message and retried with MSYS2_ARG_CONV_EXCL='*'

**Most Confusing Aspects:** The --path / failure under ./b4w.ps1 from Git Bash: the CLI quoted 'C:/Program Files/Git/' as an invalid path — a value the user never typed — with no hint that the shell rewrote the argument (b4w.ps1's own guard, which exists to catch exactly this, missed the exact-root case). Secondary: docs route Git Bash users to ./b4w.sh while the task instructions mandated ./b4w.ps1, and only one of those routes works.

**Most Valuable Improvements:** Fix the b4w.ps1 MSYS guard length check (-gt → -ge) so --path / fails fast with the existing 'use ./b4w.sh' guidance; make cookie-delete report not-found instead of an unconditional 'Cookie deleted' success; unify delete-family vocabulary and error locale-independence across storage commands

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

#### Issue 1: Git Bash MSYS path conversion breaks the documented `--path /` examples when invoking ./b4w.ps1 directly

From Git Bash on Windows: ./b4w.ps1 cookie-set session_id abc123 --domain localhost --path / --httpOnly --secure
→ Error: option '--path' was rejected: 'C:/Program Files/Git/' is not a valid cookie path (must start with '/') (exit 1).
The bare `/` argument is rewritten by MSYS2 to the Git installation root before pwsh starts. Workaround: MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 … or ./b4w.sh …

#### Issue 2: cookie-delete reports success when no matching cookie exists — misleading output, inconsistent with other storage delete commands

./b4w.ps1 cookie-delete no_such_cookie
→ prints 'Cookie deleted: no_such_cookie', exit 0 — even though no such cookie exists (verify with cookie-list: jar unchanged).
Contrast: ./b4w.ps1 localstorage-delete no_such_key → 'localStorage key not present: no_such_key' (exit 0); ./b4w.ps1 cookie-get no_such_cookie → 'Error: Cookie not found: no_such_cookie' (exit 1).

#### Issue 3: state-load error output mixes English CLI text with OS-locale error strings (Chinese on zh-CN Windows)

./b4w.ps1 state-load .test-sessions/does-not-exist.json
→ 'Error: Failed to read storage state file D:\workspace\...\does-not-exist.json: 系统找不到指定的文件。 (os error 2)' (exit 1)

