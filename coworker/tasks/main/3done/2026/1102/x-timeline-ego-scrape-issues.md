# Issues: x-timeline-ego-scrape

> **Source:** self-observed while running the task "重新构建，打开ego访问x.com，总结最新30条消息" on `main` @ `4.16.0-SNAPSHOT`, Windows 11 / pwsh 7 / source-tree CLI (`b4w.ps1`) | **Date:** 20261103-023112 | **Mode:** dev

## Scenario Background

### Task

Rebuild Browser4 from source, open x.com in the user's already-logged-in **system ego** browser,
and summarize the newest 30 timeline posts. The task required: a full runtime-bundle rebuild,
attaching over CDP to a browser that carries the user's X session (a managed launch uses an
isolated profile and lands on the onboarding page), then segmented scrolling + per-segment
scraping + cumulative de-duplication (X unloads off-screen `article` nodes, so scroll-fast-then-
scrape-once yields only ~5-8 of 30). The completed workflow is now codified as the project skill
`.trae/skills/x-timeline-ego/` (SKILL.md + `scripts/scrape-x-timeline.ps1`).

### Execution Context

| Step | Command | Outcome |
|---|---|---|
| Rebuild via wrapper | `pwsh ./b4w.ps1 -Rebuild open --channel ego-lite --headed "https://x.com/home"` | **FAIL** — Kotlin compile error in `browser4-protocol`; retrying the same module alone passed, then full `mvnw install` passed (Issue 2) |
| Managed launch lands on login | `open --channel ego-lite` → page is `x.com/i/jf/onboarding/web?...mode=login` | Managed profile has no X session; no hint is printed that the redirect means "wrong profile, use attach" (Issue 5) |
| Backend reaped per command | each `pwsh ./b4w.ps1 ...` Shell call ends → next call prints `Server health: UNREACHABLE`, sessions gone | Detached backend JVM is a child of the caller's process tree and dies with the host shell's job object (Issue 1) |
| Workaround that worked | launch via WMI `Win32_Process.Create`, or chain attach+scrape in one foreground command | Backend survives; attach `-s ego-user --cdp 9222` succeeded |
| eval with a script file | `b4w eval -file .tmp-scrape.js` | `TARGET_NOT_FOUND: No element matches selector ".tmp-scrape.js"` — the file path was treated as a CSS selector (Issue 3) |
| eval return parsing | `window.scrollY` then `[int]($cur + [int]$vh * 1.5)` | Produced `104654.66406251474.5` — returns are string-typed and stdout mixes log lines with the value (Issue 3) |
| Re-attach same name | `attach -s ego-user --cdp 9222` twice | Second attach conflicts with the existing session; manual `-s ego-user close` required first, no `--force` (Issue 4) |
| Final result | segmented scroll (1.5 viewport, 1.5s settle, grab per segment, dedupe by `handle|body`) | 103 unique posts collected → newest 30 summarized; task completed |

---

## Issues Found (5 issues)

### Issue 1: Dev-server backend is killed with the calling shell (Windows job object)

**Severity:** High — makes any multi-command dev workflow (open → eval → eval …) unreliable on Windows
unless the caller knows to self-detach.

**Category:** Reliability / Developer Experience

#### Reproduction

```powershell
pwsh ./b4w.ps1 open --channel ego-lite --headed "https://x.com/home"   # OK, session opened
pwsh ./b4w.ps1 status                                                  # Server health: UNREACHABLE
```

The backend that `b4w.ps1` detached a minute earlier is gone; every session with it.

#### Expected Behavior

Once the dev server reports `Server ready`, it survives the exit of the CLI process that launched
it, on every platform, without the caller resorting to WMI.

#### Actual Behavior

The launcher spawns the backend JVM as a descendant of the current shell. Host shells that run
commands inside a Windows job object (the Trae Shell tool does) terminate the whole tree when the
command finishes, so the "detached" backend dies silently. The next command then cold-starts a new
JVM (~10-30s) with zero sessions, and `attach`-based workflows lose their browser binding.

Observed repeatedly during this task; the only reliable escapes were (a) one long foreground
command chain, or (b) `Invoke-CimMethod Win32_Process.Create` to escape the job object.

#### Root Cause Analysis

Windows job objects kill the entire process tree by default; the CLI's "start backend in
background" path creates a child process but does not breakaway from the job
(`JOB_OBJECT_LIMIT_BREAKAWAY_OK` / `CreateProcess` with `CREATE_BREAKAWAY_FROM_JOB`, or simply
spawning via WMI/`schtasks`). Nothing in `daemon.rs`/`b4w.ps1` currently does this.

#### Code Pointer

- `cli/browser4-cli/src/daemon.rs` — backend spawn path
- `b4w.ps1` — dev wrapper that triggers server start

#### AI Suggested Improvement

1. On Windows, spawn the backend with breakaway-from-job semantics (or via WMI
   `Win32_Process.Create`, which this task verified survives), controlled by a
   `b4w start --detach` / config knob; keep current behavior for `--foreground` debugging.
2. Add an e2e/install-scenario test: start server from a child shell, kill the shell, assert
   `/status` still answers. Existing install-scenario e2e
   (`cargo test --test e2e -- --scenario '*install*'`) is the natural home.
3. Document the behavior in `skills/browser4-cli/SKILL.md` until the fix lands.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Real and highest priority — `daemon.rs:56-59` spawns the backend with `DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP` but no job breakaway, and no `--detach`/`--foreground` knob exists yet, so the premise holds and the wrapper cannot fix it. The proposed fix is incomplete: `CREATE_BREAKAWAY_FROM_JOB` fails with `ERROR_ACCESS_DENIED` unless the host job sets `JOB_OBJECT_LIMIT_BREAKAWAY_OK`, so detect job membership first and fall back to WMI `Win32_Process.Create`/`schtasks`, with an e2e that creates its own `KILL_ON_JOB_CLOSE` job rather than relying on the CI shell.

---

### Issue 2: `b4w -Rebuild` dies on a flaky incremental Kotlin compile in `browser4-protocol`

**Severity:** Medium — the documented rebuild path intermittently fails; full Maven build is the
only reliable route.

**Category:** Build / Test Infrastructure

#### Reproduction

```powershell
pwsh ./b4w.ps1 stop
pwsh ./b4w.ps1 -Rebuild open --channel ego-lite --headed "https://x.com/home"
# → Maven build fails: compilation failure in browser4-core/browser4-protocol (Kotlin)

# Same tree, seconds later:
.\mvnw.cmd -pl browser4-core/browser4-protocol -am -DskipTests compile   # SUCCESS
.\mvnw.cmd -DskipTests install -P"all-main-modules,asset-bundle"         # SUCCESS (32 modules)
```

#### Expected Behavior

`-Rebuild` either compiles deterministically or retries the failing module non-incrementally.

#### Actual Behavior

Incremental Kotlin compilation state appears to get corrupted/poisoned; the wrapper reports BUILD
FAILURE and aborts without recovery, even though the sources are fine.

#### Root Cause Analysis

Kotlin incremental compilation caches under `target/` disagreeing with the actual sources after
checkouts/branch switches. The wrapper's Maven invocation does not pass `-e`/retry and reuses
whatever incremental state is on disk.

#### Code Pointer

- `b4w.ps1` (`-Rebuild` path) → `browser4-apps/browser4-bundle/build-runtime-bundle.ps1` — Maven invocation
- `browser4-core/browser4-protocol` — module where the failure surfaced

#### AI Suggested Improvement

1. On compile failure inside `-Rebuild`, auto-retry the failed module with
   `-Dkotlin.compiler.incremental=false` (or after deleting that module's `target/kotlin*` caches)
   before giving up.
2. Surface the failing module + the "retry without incremental compile" hint in the CLI error
   output instead of a bare BUILD FAILURE.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The flake is real but the cause is misattributed — `daemon.rs:8628` already runs `mvn clean` before install and `maven_failure_message` (`daemon.rs:8413`) already prints a 20-line tail plus rebuild advice, so poisoned incremental state and a "bare BUILD FAILURE" do not fit; the code pointer is also wrong, since `build-runtime-bundle.ps1:998-1024` is the path that already cleans and already handles GraalVM. The documented GraalVM `UseJVMCICompiler` / Kotlin-daemon handshake failure fits the "same tree passes seconds later" signature, and that workaround (`-Dkotlin.compiler.daemon=false`, plus `-e`) is missing from the CLI path at `daemon.rs:8634` — mirror it, retry once in-process, and fix the contradictory "falling back to download" line at `daemon.rs:8664`.

---

### Issue 3: `eval` argument handling is a footgun — no `-file`, misleading selector error, polluted stdout

**Severity:** Medium — any automation that passes non-trivial JS hits at least one of these.

**Category:** CLI UX / Error Semantics

#### Reproduction

```powershell
# 1) Passing a file path is interpreted as a CSS selector
pwsh ./b4w.ps1 -s ego-user eval -file .tmp-scrape.js
# Error: [TARGET_NOT_FOUND] browser_evaluate failed: No element matches selector ".tmp-scrape.js".

# 2) Return value shares stdout with log lines; callers must "take the last non-empty line"
pwsh ./b4w.ps1 -s ego-user eval "window.scrollY"
# (log lines) ... "104654.6640625"   ← string, not a number

# 3) String-typed numerics break naive arithmetic in the caller
[int]($cur + [int]$vh * 1.5)   # "104654.6640625" + 1474.5 → "104654.66406251474.5"
```

#### Expected Behavior

- A documented way to eval a script from a file (or a clear "not supported, use
  `Get-Content -Raw`" error when the argument looks like a path).
- When the argument is not valid JS and resembles a path/selector, the error names the real cause
  instead of reporting it as a missing selector.
- The eval result is printed alone on stdout (logs to stderr), ideally JSON-typed
  (`104654.6640625` as a number, not a quoted string).

#### Actual Behavior

All three sub-behaviors above; each cost debugging cycles during the x.com scrape.

#### Root Cause Analysis

`eval` shares the generic element-command argument path (hence the selector error), and its output
pipeline is the same one used for human-oriented command output, so log lines and stringified
values leak into machine-consumed stdout.

#### Code Pointer

- `cli/browser4-cli/src/commands.rs` — `eval` CommandDef / argument mapping
- `cli/browser4-cli/src/main.rs` — eval dispatch and output rendering
- `skills/browser4-cli/SKILL.md` — eval documentation (no `-file` mentioned either way)

#### AI Suggested Improvement

1. Add `eval --file <path>` (reads UTF-8, passes contents as the JS string), or explicitly reject
   path-like arguments with a hint.
2. Route eval's return value to stdout as pure JSON and everything else to stderr; add a
   `--raw`/`--json` flag if backward compatibility is a concern.
3. Regression test: eval returning a number must round-trip as a JSON number.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Two thirds of the ask already ships — `--file`, `--stdin`, `--base64` and native-typed `--json` are at `commands.rs:2194-2198` and documented in `docs/eval-command-output.md` — so those parts should be dropped from the issue, not re-implemented. The genuine defect is narrower and precisely diagnosable: a second positional is silently bound to `ref` (`commands.rs:2189-2190`), which is exactly why `-file .tmp-scrape.js` surfaces as `TARGET_NOT_FOUND` for `.tmp-scrape.js`; reject extra positionals and single-dash `-file` with a usage error naming `--file`, and make `SKILL.md` lead with the shipped flags — keep Medium.

---

### Issue 4: `attach` ergonomics — re-attach needs a manual `close`, and there is no system-browser discovery

**Severity:** Low-Medium

**Category:** CLI UX

#### Reproduction

```powershell
pwsh ./b4w.ps1 -s ego-user attach --cdp 9222   # OK
# ... backend restarted (see Issue 1) ...
pwsh ./b4w.ps1 -s ego-user attach --cdp 9222   # conflicts with existing session
pwsh ./b4w.ps1 -s ego-user close               # manual cleanup required
pwsh ./b4w.ps1 -s ego-user attach --cdp 9222   # OK again
```

And to even learn that the user's logged-in ego listens on 9222, one must dig through process
command lines:

```powershell
Get-CimInstance Win32_Process -Filter "Name='ego.exe'" |
  Where-Object CommandLine -match 'remote-debugging-port'
```

#### Expected Behavior

- `attach --cdp 9222 --force` (or default reattach semantics for an identical endpoint) replaces
  the stale binding.
- Something like `attach --cdp ego-lite --discover` enumerates local browser processes with
  `--remote-debugging-port` and offers/attaches to the matching channel binary.

#### Actual Behavior

Stale session conflicts must be cleared by hand; discovery is entirely manual, so "use my
logged-in system browser" — a top automation use case — has no first-class path.

#### Root Cause Analysis

Attach assumes a fresh session name and a caller-supplied port; no reconciliation with existing
sessions, no integration with the channel detection that already exists for managed launches.

#### Code Pointer

- `cli/browser4-cli/src/main.rs` — `attach` dispatch
- `cli/browser4-cli/src/commands.rs` — attach CommandDef
- backend attach path (`browser4-rest` / session manager) — stale-binding behavior

#### AI Suggested Improvement

1. Add `--force` to attach: close+reattach in one step when the session exists.
2. Add discovery: scan local processes for known channel binaries (chrome/edge/ego/…) carrying
   `--remote-debugging-port`, list them via `attach --list`, and accept a channel name for
   `--cdp` when exactly one match exists.
3. Cover both with e2e against the fake CDP harness.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Not a duplicate of Issue 1, but two of its three claims no longer reproduce: `-s` named sessions skip the unnamed-slot guard (`main.rs:824`) and `PulsarSessionManager` re-attaches idempotently on the same port, keeping or rebinding the driver instead of failing (`PulsarSessionManager.kt:683-705`, `891-912`), while channel attaches already scan for `--remote-debugging-port` and even enumerate listening ports (`daemon.rs:6099-6127`). Drop `--force` and the manual-`close` claim, keep only `attach --list` enumeration plus docs, and downgrade to Low so it stays consistent with Issue 1/2 priority.

---

### Issue 5: Managed launch that lands on a login redirect fails silently

**Severity:** Low

**Category:** UX / Observability

#### Reproduction

```powershell
pwsh ./b4w.ps1 open --channel ego-lite --headed "https://x.com/home"
# Session opened. Current page: https://x.com/i/jf/onboarding/web?...mode=login
```

The command reports success; the user only learns the profile is unauthenticated by opening the
window or evaluating `location.href`.

#### Expected Behavior

When a managed (isolated-profile) launch is redirected to a well-known auth/onboarding interstitial
—or more generally when the final URL's origin/path clearly diverges from the requested one— the
CLI prints a hint: "landed on a login page; the managed profile has no session here, consider
`attach --cdp` to a logged-in browser".

#### Actual Behavior

Silent success. During this task it caused a full wasted round of work before the redirect was
noticed.

#### Root Cause Analysis

`open` treats navigation completion as success regardless of where it landed; no post-navigation
URL/redirect inspection, no auth-wall heuristics.

#### Code Pointer

- `cli/browser4-cli/src/main.rs` — `open` dispatch and result rendering
- `cli/browser4-cli/src/commands.rs` — open CommandDef

#### AI Suggested Improvement

1. After `open`, if the final URL matches a small configurable login-wall pattern list (x.com
   onboarding/login, google signin, etc.) or the redirect left the requested origin, print a
   one-line hint naming `attach --cdp` as the remedy. Keep it a hint — do not fail the command.
2. Alternatively/additionally: `open` gains `--expect-url <glob>` that warns on mismatch.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Genuine and cheap: `open` inspects the post-navigation URL only for the chrome-error interstitial (`main.rs:3681-3741`), which is the natural insertion point for a hint-only login-wall check. Prefer a small configurable pattern list plus opt-in `--expect-url` over generic origin divergence (routine x.com redirect chains would false-positive), send the hint to stderr, and word the remedy to match the discovery story Issue 4 actually ships — keep Low.
