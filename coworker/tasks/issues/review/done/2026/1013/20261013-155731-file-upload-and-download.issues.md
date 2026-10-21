# Issues: file-upload-and-download

> **Source:** `20261013-155731-file-upload-and-download.issues.json` | **Date:** 20261013-155731 | **Mode:** dev

## Scenario Background

### Task

The scenario was executed end-to-end against MockSite (`http://localhost:18080`) and the local dev backend (dev port 8283, bundle `4.14.0-rc.10`, system Chrome 155 headless).

**Upload half: fully successful.** All four upload acceptance criteria passed:

- **AC1 ✅** `upload "#file-input" <alpha.txt>` → `uploadCount=1`, `uploadName="alpha.txt"`.
- **AC2 ✅** `upload "#file-input" <alpha.txt> <beta.txt>` → `uploadCount=2`, `uploadName="alpha.txt"`, `files=["alpha.txt","beta.txt"]` — both paths handled separately.
- **AC3 ✅** `upload "#fill-target" <alpha.txt>` → exit 1, `upload: the target [#fill-target] is an <input> without type="file" — only file inputs accept uploads.`
- **AC4 ✅** `upload "#file-input" <nonexistent>` → exit 2, `upload: file(s) not found or not readable on this machine: …` — emitted client-side (verified in `main.rs:27245-27296`), before any backend/CDP call, ~0 ms beyond the ~4.6 s wrapper overhead.

**Download half: AC6 failed.** The download directory configuration reached Chrome correctly (Chrome's own download records name the configured absolute path as `filePath`), the "Download" link click started downloads, but **every download ended in Chrome's own download manager as "失败 – 下载错误" (Failed – Download error), and no file ever appeared** — not at the configured directory, not in `~/Downloads`, not in the browser profile, not transiently (no `.crdownload` ever observed inside an external target directory). `wait --download` therefore timed out (with a correct, clear message). The project's own `test_e2e_download_command` scenario was then run twice on the same machine and **passed both times**, so the download machinery demonstrably works here under the e2e harness — the failure is specific to the CLI dev-mode flow, and the exact backend-side difference could not be fully pinned down within this session.

- **AC5 ⚠️ partial** — the directory *is* applied (Chrome records it), but `download --dir` confirms nothing: it prints only `{}`.
- **AC6 ❌** — never completes; see Issue 1.
- **AC7 ✅** (negative half) — `wait --download` never returned early, no `.crdownload` leftovers; on an empty existing dir it failed with `Timed out after 3000 ms waiting for a download in <abs-path>` (exit 1); on a missing dir with `Download directory does not exist: <abs-path>. Create it or pass --dir <path> …`.
- **AC8 ⚠️ partial** — every path the CLI printed (timeout and missing-dir messages) was the exact absolute path passed; but `upload`/`download` print no paths on success, so there is little to check.

### Execution Context

**Setup:** `pwd` confirmed repo root; `./b4w.ps1 help`; read `skills/browser4-cli/SKILL.md` + `references/upload.md`; MockSite already up (HTTP 200 on :18080); created `target/upload-download/{alpha.txt,beta.txt}`; `./b4w.ps1 status` (backend UP, dev port 8283).

**Upload flow:** `-s updown open --headless <fixture>` → `upload` ×1 (single) → `eval` state read → `upload` ×1 (both files) → `eval` → `upload "#fill-target"` (expected fail) → `upload` nonexistent path (expected fail). All as documented; state verified via `eval "JSON.stringify({uploadCount…})" --json`.

**Download flow (all attempts failed to land a file):** `download --dir <abs>` (prints `{}`), `wait --download` on a missing dir (clear error), `wait --download` on an empty dir (clear timeout), `goto` blob fixture → `snapshot -...

(truncated — see 20261013-155731-file-upload-and-download.issues.json for the complete execution trace)

---

## Issues Found (9 issues)

### Issue 1: Downloads never complete in the dev-mode CLI flow — files land nowhere, Chrome reports 'Failed – Download error'

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 -s updown open --headless http://localhost:18080/assets/download-blob.html
2) ./b4w.ps1 -s updown download --dir D:/workspace/Browser4/Browser4-4.14/target/upload-download/downloads
3) ./b4w.ps1 -s updown snapshot -i -v 0   # ref of the Download link
4) ./b4w.ps1 -s updown click <ref>
5) ./b4w.ps1 -s updown wait --download --dir <same dir> --timeout 20000   # times out
Reproduced with: blob fixture, a same-origin HTTP link with download attribute, and a plain HTTP link with Content-Disposition served from a local fixture server; into D:/workspace/.../downloads, C:/Users/pereg/Downloads and %TEMP% dirs; named and default sessions; headless and --headed; --profile-mode=SEQUENTIAL; localhost and 127.0.0.1. Chrome's own chrome://downloads UI shows every item as 失败 - 下载错误 ('Failed - Download error') and no file appears anywhere on disk (searched the whole workspace, ~/.browser4, %TEMP% profile trees, ~/Downloads).

#### Expected Behavior

Clicking the fixture's Download link writes example.txt ('Hello world') into the configured directory; wait --download returns when it completes (AC6).

#### Actual Behavior

Chrome accepts the download behavior config (its download records store the configured absolute path as filePath) and starts the download, but every download ends interrupted; chrome://histograms shows Download.InterruptedReason = FILE_FAILED; no bytes ever reach the target directory (no .crdownload ever appears there). Meanwhile `cargo test`-driven test_e2e_download_command passes twice on the same machine, so the failure is specific to the dev-backend/session context used by normal CLI invocations, not to Chrome or the OS.

#### Root Cause Analysis

Not fully determined. Evidence narrows it to a backend/browser-context difference: same Chrome binary, byte-identical launch arguments, same flags in both the failing (CLI on dev backend 8283) and passing (e2e's dedicated backend with its own app-data root) cases; the only visible deltas are the backend instance/app-data root and the browser profile location (workspace app-data vs %TEMP%/browser4-pereg/context/groups/... cx.<session>). Chrome-side data shows the write fails at the file level (FILE_FAILED) while path validation succeeded, i.e. Chrome's download file creation/rename fails instantly. A parked '未确认 *.crdownload' artifact (a CRX) also exists in every session profile's Download Service cache, hinting at a browser-wide download-protection/hold state in these profiles; needs backend-side instrumentation (e.g. compare Browser.setDownloadBehavior state and profile seeding between a dev-backend browser and an e2e-backend browser).

#### Code Pointer

`cli/browser4-cli/src/commands.rs:1966 (download CommandDef → execute_cdp_command; there is no Kotlin-side download handling in browser4-core, so the backend/context configuration is the likely fix site)`

#### AI Suggested Improvement

- Instrument the backend to log the effective download behavior/context state at browser launch and before/after each download command, then diff a dev-backend browser against the passing e2e browser (the e2e path is a working reproducer to bisect against)
- Check whether the backend applies its own Browser.setDownloadBehavior (or profile/context initialization) after the CLI-issued one, and whether per-session profiles are seeded from a template that carries a stale Download Service/scanning state
- Add an automated smoke check that fails CI when a CLI-driven download does not complete, since test_e2e_download_command passes while the normal CLI flow is broken

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Backend JVM died mid-session; all sessions lost and silently replaced with a new session id

**Severity:** High
**Category:** Reliability

#### Reproduction

Observed once. Recurring context: session in active use with repeated download attempts. A snapshot command returned 'Error: No active browser session…'; ./b4w.ps1 list reported 'Browser4 backend is not started or unreachable at http://127.0.0.1:8283'; the backend log (bundle logs/pulsar.log) ends abruptly at the last successful tool call with no shutdown/exception record; the JVM process was gone. The next command auto-restarted the backend (11.6 s) and opened a NEW session with a different session id.

#### Expected Behavior

Either the backend stays up, or a crash is recorded with a diagnosable cause; a restarted backend should keep (or explicitly report the loss of) existing session identity.

#### Actual Behavior

Backend process disappeared without a log trace; the CLI surfaced 'No active browser session' (misleading — the session had been active seconds earlier) and, on retry, silently created a new session; saved session state pointed at the dead backend until re-opened.

#### Root Cause Analysis

Unknown; the JVM vanished with no shutdown line and no hs_err file. Timing correlates with the download-failure investigation (heavy CDP/download activity), but causation is not established. Needs reproduction with backend JVM monitoring (-XX:+HeapDumpOnOutOfMemoryError, native crash logging) — note the machine also hosted ~100 orphaned Chrome processes and a 4 GB Kotlin build daemon at the time.

#### Code Pointer

`browser4-apps/browser4-bundle backend launcher / daemon supervision (cli/browser4-cli/src daemon side)`

#### AI Suggested Improvement

- Enable JVM crash diagnostics (hs_err path reporting, heap dump on OOM) and surface the cause in doctor log
- Surface backend-death clearly at the CLI ('backend stopped unexpectedly; previous session state was lost') instead of 'No active browser session'
- Keep session identity across a backend restart where possible, or state that it changed

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: download --dir prints only '{}' — no confirmation, no validation of the configured directory (AC5 not user-visible)

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 download --dir D:/workspace/Browser4/Browser4-4.14/target/upload-download/downloads   (directory may or may not exist)

#### Expected Behavior

A human-readable confirmation naming the directory (e.g. 'Downloads will be saved to <abs path>'), and at least a warning when the directory does not exist or is not writable.

#### Actual Behavior

Output is exactly `{}` (the raw CDP result). A nonexistent directory is accepted silently; only later does `wait --download` fail with 'Download directory does not exist…', and the browser download itself just never completes.

#### Root Cause Analysis

The download command maps to the generic execute_cdp_command passthrough and the CLI prints the raw tool result verbatim; no CLI-side formatting/validation layer exists for this command family.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:1966-1987 (download CommandDef) and the CLI result printer in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Print a confirmation line echoing the resolved absolute directory and the behavior
- Resolve the --dir to an absolute path client-side and validate existence/writability before issuing the CDP call (mirroring the upload command's client-side file checks)
- Warn (or fail) when the directory does not exist instead of silently accepting it

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: wait --download can report success without any download having happened (pre-existing files count as 'complete')

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) mkdir <dir> && write any file into <dir> (e.g. a README)
2) ./b4w.ps1 wait --download --dir <dir> --timeout 20000
It returns 'Download complete in <dir>' immediately, with no download ever started.

#### Expected Behavior

The wait should be tied to a download that completes during the wait (or accept a filename/target argument), so it cannot succeed on unrelated pre-existing content; conversely it should keep waiting for a download the user starts afterwards.

#### Actual Behavior

Success condition is 'at least one non-.crdownload/.tmp/.part file exists AND no .crdownload/.tmp/.part file exists' — any pre-existing file satisfies it instantly. In an empty directory it works correctly (times out with a clear message).

#### Root Cause Analysis

handle_wait_download treats the directory contents as the only signal; it snapshots nothing at start, so it cannot distinguish files that existed before the call from files the awaited download produced.

#### Code Pointer

`cli/browser4-cli/src/main.rs:7478 (handle_wait_download)`

#### AI Suggested Improvement

- Record the directory listing when the wait starts and only count files that appear (or change) afterwards
- Add an optional expected filename/pattern (e.g. wait --download --name example.txt) for unambiguous waits
- Document the polling semantics and the pre-existing-file caveat in help and SKILL.md (help currently only says 'polls for .crdownload files')

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: eval runs in an undocumented isolated world (with silent fallback to the page world), breaking window state expectations

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1) ./b4w.ps1 eval "window.__probe = 42"
2) Trigger page JS that reads window.__probe (or set an inline onclick attribute and click the element; then read element.onclick via eval)
The eval-written global is invisible to page scripts and inline attribute handlers appear 'missing' (#deepScan reflection: element.onclick === null in the eval world). Backend log shows Page.createIsolatedWorld worldName="__browser4_runtime__", and on failure 'Failed to inject scripts into isolated world, falling back to page world' — so the execution context can differ between sessions.

#### Expected Behavior

Documented, consistent JS execution context; either stated that eval runs isolated (with the visibility rules that implies) or always run in the page world like other automation tools' frame.evaluate.

#### Actual Behavior

Silent context inconsistency: in some sessions eval sees page-created globals (page-world fallback), in others it does not (isolated world). Nothing in SKILL.md, references/eval.md or help mentions isolated worlds or the fallback. This produced significant debugging confusion (e.g. verifying a click handler fired).

#### Root Cause Analysis

The backend creates an isolated world named __browser4_runtime__ for instrumentation and evaluates in it when creation succeeds; when the isolated world cannot be created it silently falls back to the main world. The CLI documentation describes eval only as 'execute JavaScript in the page'.

#### Code Pointer

`backend IsolatedWorldManager (ai.platon.pulsar.chrome) / cli/browser4-cli eval dispatch; docs: skills/browser4-cli/references/eval.md`

#### AI Suggested Improvement

- Document the isolated world (name, when it is used, the page-world fallback) in eval.md and SKILL.md
- Make the behavior deterministic (always isolated or always page world) and/or expose a flag to choose
- Emit a one-line stderr note when the fallback occurs so agents are not silently switched

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Default download directory './downloads' is resolved by two different processes — configure and wait can watch different directories

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run ./b4w.ps1 download (no --dir) from directory A, then ./b4w.ps1 wait --download (no --dir) from directory B (or with the browser process having a different cwd).

#### Expected Behavior

One documented resolution rule for relative/default download paths, ideally resolved client-side to an absolute path before being sent to the browser.

#### Actual Behavior

`download` forwards the string 'downloads' to CDP unchanged (Chrome resolves it against the browser process's working directory), while `wait --download` resolves the same default against the CLI process's cwd (PathBuf::from("downloads")). Help text says 'default: ./downloads' for both, implying they agree.

#### Root Cause Analysis

No client-side canonicalization of --dir; two independent interpretations of the same relative default.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:1980 (download default) and cli/browser4-cli/src/main.rs:7481-7492 (wait default)`

#### AI Suggested Improvement

- Resolve --dir (and the default) to an absolute path in the CLI before dispatching to CDP/using it for polling
- Update help/SKILL.md to state where relative paths resolve (currently unstated)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: --headed session falsely reports 'running HEADLESS'; browser actually launched headed

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s <name> open --headed <url>

#### Expected Behavior

No warning when the session was created headed and really is headed.

#### Actual Behavior

Prints '⚠ No visible Browser4 window: Browser4-managed browsers are running HEADLESS even though this new session was started with --headed.' The subsequent browser launch-arguments file contains no --headless flag, so the browser was headed; the machine-wide detection heuristic (which counts other sessions' browsers) produced a false alarm.

#### Root Cause Analysis

The display-mode check inspects all PULSAR_CHROME browsers machine-wide instead of the new session's browser (the warning even admits other sessions can skew it).

#### Code Pointer

`cli/browser4-cli/src/main.rs (open --headed warning path; ChromeLauncher display detection in the backend)`

#### AI Suggested Improvement

- Scope the detection to the session/browser being created (match by session profile dir or CDP target)
- If detection cannot be scoped, phrase it as 'could not confirm a visible window' instead of asserting headless

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: False-positive 'possible blocked/challenge page' advisory on small legitimate pages

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 goto http://localhost:18080/assets/download-blob.html   (and the download fixture page for this scenario)

#### Expected Behavior

No challenge warning for an obviously small, legitimate local fixture page (SKILL.md states widget/demo pages are exempt from the 'implausibly empty body' signal).

#### Actual Behavior

'⚠ Possible blocked/challenge page — detected page text is implausibly empty (30 chars) after a successful navigation.' fires on both the blob fixture and a minimal local fixture page; it advises a retry that wastes time on pages that are legitimately tiny.

#### Root Cause Analysis

The empty-body heuristic ignores the documented exemption for widget/demo pages (or its exemption criteria do not match these pages).

#### Code Pointer

`cli/browser4-cli/src/main.rs (goto/open post-navigation probe and challenge signature detection)`

#### AI Suggested Improvement

- Apply the documented widget/demo exemption (small pages with a single interactive element) or require a minimum text threshold before warning
- Suppress the advisory for loopback origins, where a challenge is implausible

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: upload prints no confirmation of what was uploaded

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 upload "#file-input" <alpha.txt> <beta.txt>

#### Expected Behavior

A one-line confirmation naming the target and the files (e.g. 'Uploaded 2 files to #file-input: alpha.txt, beta.txt').

#### Actual Behavior

Only the standard '### Page / ### Snapshot' block is printed; the upload outcome must be inferred by reading page state via eval/get.

#### Root Cause Analysis

The upload executor returns an empty result and the CLI has no success-line formatter for it.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (upload CommandDef) / backend upload executor`

#### AI Suggested Improvement

- Print the target selector and the uploaded file names on success
- Include the resolved absolute paths (useful for AC8-style verification)

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

**Completion Status:** Partially Successful — the entire upload half of the scenario meets all acceptance criteria with clear, correct behavior and excellent error messages. The download half could not be completed: every download attempt through the normal CLI dev-mode flow ended as a Chrome-side failure ('Failed - Download error'; no file ever written anywhere), reproduced across ~10 attempts and every controllable variable. wait --download behaved correctly (clear timeout / missing-dir errors, no early return), and the project's own e2e download scenario passes twice on the same machine, so the failure is specific to the flow under test and needs backend-side root-cause work.

**Success Rate:** 60% — steps 1-6 (upload, incl. both failure modes) fully passed; step 7 (configure download dir) functionally applied but not confirmable in output; step 8 (navigate + click) executed correctly; step 9 (download completion) failed; step 10 (cleanup) done. ACs: 1,2,3,4,7 pass; 5 partial; 6 fail; 8 partial.

**Issues Found:** 9

**Major Blockers:** AC6/AC6-adjacent download completion is completely broken in the dev-mode CLI flow on this machine: no download ever lands, which blocks the second half of the scenario and left wait --download only verifiable in its negative paths.

**Most Confusing Aspects:** 1) `download --dir` succeeding with a bare `{}` and no confirmation, while the actual download silently fails inside Chrome. 2) The undocumented isolated-world execution of `eval` (with a silent page-world fallback): window state written via eval is invisible to page scripts, and inline onclick handlers look broken when inspected — this cost substantial debugging time. 3) A backend crash that silently replaced the session ('No active browser session' with no hint the backend had died). 4) The --headed warning claiming headless when the browser was in fact headed.

**Most Valuable Improvements:** 1) Make downloads work in the standard dev-mode flow and add a CI check that catches the CLI-vs-e2e divergence (the e2e passes while the CLI flow fails — highest value). 2) Human-readable confirmations and client-side validation for `download --dir` (echo the absolute directory, reject/warn on missing dirs). 3) Make `wait --download` immune to pre-existing files and document its polling semantics. 4) Document (or make deterministic) the eval world model. 5) Improve backend-death reporting so session loss is explicit rather than 'No active browser session'.

**Usability Rating:** 5/10

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

#### Issue 1: Downloads never complete in the dev-mode CLI flow — files land nowhere, Chrome reports 'Failed – Download error'

1) ./b4w.ps1 -s updown open --headless http://localhost:18080/assets/download-blob.html
2) ./b4w.ps1 -s updown download --dir D:/workspace/Browser4/Browser4-4.14/target/upload-download/downloads
3) ./b4w.ps1 -s updown snapshot -i -v 0   # ref of the Download link
4) ./b4w.ps1 -s updown click <ref>
5) ./b4w.ps1 -s updown wait --download --dir <same dir> --timeout 20000   # times out
Reproduced with: blob fixture, a same-origin HTTP link with download attribute, and a plain HTTP link with Content-Disposition served from a local fixture server; into D:/workspace/.../downloads, C:/Users/pereg/Downloads and %TEMP% dirs; named and default sessions; headless and --headed; --profile-mode=SEQUENTIAL; localhost and 127.0.0.1. Chrome's own chrome://downloads UI shows every item as 失败 - 下载错误 ('Failed - Download error') and no file appears anywhere on disk (searched the whole workspace, ~/.browser4, %TEMP% profile trees, ~/Downloads).

#### Issue 2: Backend JVM died mid-session; all sessions lost and silently replaced with a new session id

Observed once. Recurring context: session in active use with repeated download attempts. A snapshot command returned 'Error: No active browser session…'; ./b4w.ps1 list reported 'Browser4 backend is not started or unreachable at http://127.0.0.1:8283'; the backend log (bundle logs/pulsar.log) ends abruptly at the last successful tool call with no shutdown/exception record; the JVM process was gone. The next command auto-restarted the backend (11.6 s) and opened a NEW session with a different session id.

#### Issue 3: download --dir prints only '{}' — no confirmation, no validation of the configured directory (AC5 not user-visible)

./b4w.ps1 download --dir D:/workspace/Browser4/Browser4-4.14/target/upload-download/downloads   (directory may or may not exist)

#### Issue 4: wait --download can report success without any download having happened (pre-existing files count as 'complete')

1) mkdir <dir> && write any file into <dir> (e.g. a README)
2) ./b4w.ps1 wait --download --dir <dir> --timeout 20000
It returns 'Download complete in <dir>' immediately, with no download ever started.

#### Issue 5: eval runs in an undocumented isolated world (with silent fallback to the page world), breaking window state expectations

1) ./b4w.ps1 eval "window.__probe = 42"
2) Trigger page JS that reads window.__probe (or set an inline onclick attribute and click the element; then read element.onclick via eval)
The eval-written global is invisible to page scripts and inline attribute handlers appear 'missing' (#deepScan reflection: element.onclick === null in the eval world). Backend log shows Page.createIsolatedWorld worldName="__browser4_runtime__", and on failure 'Failed to inject scripts into isolated world, falling back to page world' — so the execution context can differ between sessions.

#### Issue 6: Default download directory './downloads' is resolved by two different processes — configure and wait can watch different directories

Run ./b4w.ps1 download (no --dir) from directory A, then ./b4w.ps1 wait --download (no --dir) from directory B (or with the browser process having a different cwd).

#### Issue 7: --headed session falsely reports 'running HEADLESS'; browser actually launched headed

./b4w.ps1 -s <name> open --headed <url>

#### Issue 8: False-positive 'possible blocked/challenge page' advisory on small legitimate pages

./b4w.ps1 goto http://localhost:18080/assets/download-blob.html   (and the download fixture page for this scenario)

#### Issue 9: upload prints no confirmation of what was uploaded

./b4w.ps1 upload "#file-input" <alpha.txt> <beta.txt>

