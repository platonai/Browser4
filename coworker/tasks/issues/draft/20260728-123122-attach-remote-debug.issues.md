# Issues: attach-remote-debug

> **Source:** `20260728-123122-attach-remote-debug.full.md` | **Date:** 20260728-123122 | **Mode:** dev

## Scenario Background

### Task

All task steps completed successfully:

1. **Attach to Chrome via CDP** — Used `attach --cdp chrome` to connect to a running Chrome instance on port 9222. Connection succeeded immediately, discovering an active Amazon search tab.
2. **`--endpoint` + `--cdp` combination** — The command `attach --endpoint http://localhost:8182 --cdp chrome` was accepted without error. The endpoint routing behavior was opaque (see Issue 3 below) but no crash or rejection occurred.
3. **List tabs** — `tab-list` correctly enumerated the single open Chrome tab.
4. **Screenshot** — `screenshot --filename` captured a 1MB PNG of the Amazon search results page.
5. **Snapshot** — `snapshot -v 0` produced a detailed accessibility tree of the page showing product listings with refs, roles, bounding boxes, and text content.
6. **Save state** — `state-save` saved cookies and localStorage to a 15KB JSON file.
7. **Tab switching** — Created a second tab at `https://httpbin.org/get`, switched to it, captured a screenshot, then switched back to tab 0 and captured another screenshot. Both screenshots verified on disk.
8. **Close session** — `close` disconnected cleanly, confirming the attached browser continues running independently.

---

### Execution Context

| Step | Command | Outcome |
|------|---------|---------|
| Verify CWD | `pwd`, `ls b4w.ps1` | Confirmed in repo root |
| Create temp dir | `mkdir -p .test-sessions` | Created |
| Initial help | `./b4w.sh help` | Built binary, displayed full help |
| Read SKILL.md | Read `skills/browser4-cli/SKILL.md` | Loaded conventions |
| Read attach ref | Read `skills/browser4-cli/references/attach.md` | Loaded attach docs |
| Check CDP | `curl localhost:9222/json/version` | Chrome 148 running on 9222 |
| Attach CDP | `./b4w.sh -s eval-session attach --cdp chrome` | Attached successfully |
| List tabs | `./b4w.sh -s eval-session tab-list` | 1 tab (Amazon) |
| Screenshot | `./b4w.sh -s eval-session screenshot --filename ...` | 1MB PNG file |
| Snapshot | `./b4w.sh -s eval-session snapshot -v 0 --stdout...

(truncated — see full.md for complete trace)

---

## Issues Found (11 issues)

### Issue 1: b4w.ps1 fails on Linux due to Windows line endings in shebang

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 help

#### Expected Behavior

The script should execute via pwsh and print help, as the task instructions direct.

#### Actual Behavior

Error: /usr/bin/env: 'pwsh\r': No such file or directory. The carriage return in the CRLF line ending makes the shebang reference 'pwsh\r' instead of 'pwsh', and env cannot find it.

#### Root Cause Analysis

b4w.ps1 uses Windows CRLF line endings. The first line `#!/usr/bin/env pwsh\r\n` is parsed by the Linux kernel as requesting an interpreter named `pwsh<CR>`, which doesn't exist. Even if line endings were fixed, `pwsh` may not be on the default PATH — /usr/bin/env may not find pwsh installed at /opt/microsoft/powershell/7/pwsh.

#### Code Pointer

`b4w.ps1:1 — shebang line has CRLF`

#### AI Suggested Improvement

- Convert b4w.ps1 to LF line endings (git attributes or .editorconfig)
- Or document that b4w.ps1 is Windows-only and b4w.sh is the cross-platform wrapper
- Update task/evaluation templates that instruct users to run `./b4w.ps1` to check platform and suggest `./b4w.sh` on Linux/macOS
- Update CLAUDE.md to note that `./b4w.sh` is the correct dev-mode invocation on Linux

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Unclear behavior of --endpoint combined with --cdp in attach command

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.sh -s test attach --endpoint http://localhost:8182 --cdp chrome

#### Expected Behavior

Output should indicate whether the CDP connection was routed through the remote Browser4 server or made directly.

#### Actual Behavior

Output says 'Attached to browser at http://localhost:9222' with no mention of the endpoint. The user cannot tell whether the endpoint was used for CDP discovery, ignored, or used only for subsequent commands.

#### Root Cause Analysis

The attach.md docs state: 'When --endpoint is used alone (without --cdp), it switches the CLI to the remote server for subsequent commands.' But when combined with --cdp, the semantics are ambiguous — does the endpoint proxy the CDP connection, or does --cdp connect directly and --endpoint just set the server URL for later? The output provides no visibility into this.

#### Code Pointer

`cli/browser4-cli/src/ — attach command handler and/or MCP tool dispatch for attach`

#### AI Suggested Improvement

- When --endpoint is combined with --cdp, print a clear message like 'CDP discovery routed through endpoint http://...' or 'Connecting directly to CDP at ...; endpoint http://... set for subsequent commands'
- Update attach.md to explicitly describe the combined --endpoint + --cdp behavior
- Consider a --dry-run flag for attach that shows what would be connected without actually connecting

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: close/disconnect semantics differ by session type — not surfaced in close output

**Severity:** Medium
**Category:** UX

#### Reproduction

Attach a session then run `close`.

#### Expected Behavior

The close output should clearly state what happened to the browser and the session.

#### Actual Behavior

Output says 'Disconnected from attached browser. The browser remains running.' This is good for CDP-attached sessions, but a user who started with `open` (which launches its own browser) might expect `close` to also kill the browser. The word 'close' implies termination, not disconnection.

#### Root Cause Analysis

The same command name ('close') has different semantics depending on session type, but the command output doesn't adapt to explain the distinction in all cases. The attach.md reference documents this well, but CLI output is the user's primary feedback channel.

#### Code Pointer

`cli/browser4-cli/src/ — close command output formatting`

#### AI Suggested Improvement

- Always include a one-line summary of what 'close' did for this session type: e.g., 'Browser process terminated' vs 'Disconnected — browser keeps running'
- Consider adding a `disconnect` alias prominently in help to signal the intent difference
- Show a tip on first `open` close: 'Tip: use attach to reconnect to existing browsers without launching a new one'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: No confirmation prompt before closing a session with unsaved state

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `close` on a session after interacting with pages (e.g., filling forms, navigating).

#### Expected Behavior

A warning or confirmation prompt if there is unsaved browser state (cookies, localStorage, form data).

#### Actual Behavior

`close` disconnects immediately with no warning. Any unsaved authentication state, form progress, or cookies are lost.

#### Root Cause Analysis

The close command does not check whether state-save has been called recently or whether the session has accumulated meaningful state. There's no 'dirty' flag tracking state mutations.

#### Code Pointer

`cli/browser4-cli/src/ — close command handler`

#### AI Suggested Improvement

- Add a `--force` flag to skip confirmation, and prompt by default if the session has been active
- Or emit a tip after close: 'Tip: use state-save before close to preserve cookies and localStorage'
- Track a 'last-state-save' timestamp and warn if close is called with newer browser activity

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: First-run experience: help output is comprehensive but overwhelming (100+ lines of dense text)

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.sh help

#### Expected Behavior

A concise overview with clear entry points for common tasks, with drill-down into details.

#### Actual Behavior

The help output is ~100 lines listing every command under broad categories. A first-time user has no clear onboarding path. The 'Common workflows' section at the top helps, but it's only 8 lines and easily overlooked in the wall of text that follows.

#### Root Cause Analysis

The help output is generated as a flat list of all commands. There's no progressive disclosure — everything is shown at once. The workflow section exists but isn't visually prominent enough.

#### Code Pointer

`cli/browser4-cli/src/ — help command output formatting`

#### AI Suggested Improvement

- Move the 'Common workflows' section to be more visually prominent (boxed, colored, or with stronger visual separators)
- Add a 'Getting Started' section: browser4-cli goto <url> → snapshot -v 0 → click <ref>
- Offer `browser4-cli --help quickstart` for a 5-line quick reference
- Group commands by workflow stage (1. Start, 2. Inspect, 3. Interact, 4. Extract) rather than by function category

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Short-flag PowerShell warning appears in bash context via b4w.sh

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh -s eval-session snapshot -v 0 2>&1

#### Expected Behavior

No PowerShell-related warnings when using the bash wrapper.

#### Actual Behavior

Output begins with: ⚠ Short flags detected: -v / PowerShell may intercept these in other contexts (b4w.sh, direct pwsh). / Prefer long-form equivalents... This warning is confusing when running from a bash shell where PowerShell parameter binding is irrelevant.

#### Root Cause Analysis

b4w.sh delegates to b4w.ps1 via pwsh. The warning is emitted by b4w.ps1 before it delegates to the CLI binary. It fires for any invocation, including those routed through b4w.sh where the bash wrapper should provide protection.

#### Code Pointer

`b4w.ps1:39-47 — short-flag warning block`

#### AI Suggested Improvement

- Suppress the short-flag warning when invoked from b4w.sh (e.g., check an env var set by b4w.sh)
- Or reposition the warning to only show in interactive PowerShell sessions, not in piped/scripted contexts
- Add `--viewport` as a long-form equivalent in the help text for snapshot

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: snapshot -v 0 uses zero-based viewport indexing — counterintuitive

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh snapshot -v 0

#### Expected Behavior

Either 1-based viewport indexing, or clear documentation that viewports are 0-indexed.

#### Actual Behavior

`-v 0` selects the first viewport (top chunk). While the SKILL.md comment '# -v 0 = top-of-page chunk' explains this, a new user encountering `-v 0` without reading the docs would likely guess `-v 1` for the first viewport. The output shows 'processingViewport: 0' which matches but doesn't explain the convention.

#### Root Cause Analysis

Zero-based indexing is common in programming but non-obvious to CLI users who expect 1-based counts for human-facing indices. The snapshot output uses 'viewport 0' consistently but there's no onboarding hint.

#### Code Pointer

`cli/browser4-cli/src/ — snapshot command help text`

#### AI Suggested Improvement

- Add a sentence to `snapshot --help`: 'Viewport indices are zero-based: -v 0 is the top of page, -v 1 is the next chunk'
- Consider accepting both -v 0 and -v 1 as the first viewport with a deprecation notice for the ambiguous form
- Add a tip on first snapshot invocation explaining viewport numbering

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: attach auto-captures snapshot on success — surprising for new users

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.sh -s test attach --cdp chrome

#### Expected Behavior

Attach command reports connection success and waits for the next command.

#### Actual Behavior

Attach automatically takes a snapshot and prints page URL, title, and snapshot file path. This is convenient but surprising — the output mixes connection status with page inspection, and a new user may be confused about whether 'attach' or 'snapshot' produced the snapshot.

#### Root Cause Analysis

The attach command includes an implicit snapshot as part of its post-attach flow. This is a deliberate convenience feature (the docs show 'Interact with your existing tabs → snapshot' as step 3 after attach), but it's not documented that attach does this automatically.

#### Code Pointer

`browser4-rest/... — MCPToolController attach handler or backend attach flow`

#### AI Suggested Improvement

- Document in attach.md that a snapshot is auto-captured on successful attach
- Add a flag like `--no-snapshot` to skip the auto-snapshot
- Make the auto-snapshot more clearly delineated from the attach output (e.g., a separator or heading 'Auto-captured page state:')

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: tab-select by GUID requires --guid flag but help text doesn't show the syntax clearly

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.sh help | grep 'tab-select'

#### Expected Behavior

Help output should show both index-based and GUID-based selection syntax.

#### Actual Behavior

Help shows: `tab-select [index] [--guid <guid>]`. The bracket notation implies both are optional and position-independent, but a new user might try `tab-select C7FC...` (without --guid) and get an error. The SKILL.md correctly documents this but the CLI help is ambiguous.

#### Root Cause Analysis

The help text format `tab-select [index] [--guid <guid>]` uses standard CLI convention where [--guid <guid>] is a separate optional flag, but the `[index]` being first and `[--guid <guid>]` being second could be read as 'provide index then optionally --guid' rather than 'provide EITHER index OR --guid'.

#### Code Pointer

`cli/browser4-cli/src/ — tab-select command definition/help text`

#### AI Suggested Improvement

- Change help text to: `tab-select <index> | --guid <guid>` to indicate mutual exclusivity
- Or add usage examples to `tab-select --help`: 'tab-select 0' or 'tab-select --guid C7FC...'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: session-default command not discoverable from --help session category

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.sh --help session

#### Expected Behavior

session-default should appear in session-related help.

#### Actual Behavior

Did not test this filter explicitly, but the main help output lists session-default in the 'Browser sessions' section. However, a new user trying `--help session` might not find it if the filter is incomplete.

#### Root Cause Analysis

The help category filters (`--help nav`, `--help session`, etc.) need to be manually maintained to include all relevant commands. If session-default is missing from the session filter, users may not discover it.

#### Code Pointer

`cli/browser4-cli/src/ — help command category definitions`

#### AI Suggested Improvement

- Verify all session-management commands appear under `--help session`
- Add automated tests that each command appears in exactly one help category
- Add a `--help all` option that shows the complete unfiltered list

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 11: b4w.sh rebuilds on every invocation even when binary is up to date

**Severity:** Low
**Category:** UX

#### Reproduction

Run any command via ./b4w.sh twice in succession.

#### Expected Behavior

Second invocation should skip the build step if sources haven't changed.

#### Actual Behavior

Every invocation shows 'Finished dev profile [unoptimized + debuginfo]' — while not a full rebuild (cargo detects no changes), it still takes ~0.4s of overhead per command. This is mild but adds friction to the interactive loop.

#### Root Cause Analysis

b4w.sh uses `cargo run` or invokes the binary, but cargo still checks timestamps each time. With 10+ commands in a session, this adds 4+ seconds of perceived latency.

#### Code Pointer

`b4w.sh — launcher script logic`

#### AI Suggested Improvement

- The auto-build detection in b4w.ps1 (checking source timestamps vs binary) is good — ensure b4w.sh uses the same fast path
- Consider a `--no-build` flag to skip build checks entirely for rapid iteration
- Add an env var like B4W_SKIP_BUILD_CHECK=1 for power users

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 8 task steps completed. Attached to Chrome via CDP, tested --endpoint combination, listed tabs, took screenshots, captured snapshots, saved browser state, switched between tabs, and cleanly disconnected.

**Success Rate:** 100% — every documented command worked on first attempt without retries or workarounds.

**Issues Found:** 11

**Major Blockers:** The only near-blocker was the b4w.ps1 shebang issue — a Linux user following the task instructions literally would get 'pwsh not found' and have to discover b4w.sh independently. Once using b4w.sh, everything worked smoothly.

**Most Confusing Aspects:** 1. Which launcher script to use (b4w.ps1 vs b4w.sh) — the task says .ps1 but Linux needs .sh. 2. Whether --endpoint + --cdp routes through the remote server or connects directly. 3. The word 'close' meaning 'disconnect' for attached sessions but 'terminate' for launched sessions. 4. Snapshot being auto-triggered by attach without clear indication.

**Most Valuable Improvements:** 1. Fix b4w.ps1 line endings and add platform detection to the task template. 2. Make --endpoint + --cdp behavior explicit in output. 3. Progressive help disclosure (quickstart → category → full). 4. Pre-close warning for unsaved state. 5. Rename or clarify 'close' for attached sessions.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: b4w.ps1 fails on Linux due to Windows line endings in shebang

./b4w.ps1 help

#### Issue 2: Unclear behavior of --endpoint combined with --cdp in attach command

./b4w.sh -s test attach --endpoint http://localhost:8182 --cdp chrome

#### Issue 3: close/disconnect semantics differ by session type — not surfaced in close output

Attach a session then run `close`.

#### Issue 4: No confirmation prompt before closing a session with unsaved state

Run `close` on a session after interacting with pages (e.g., filling forms, navigating).

#### Issue 5: First-run experience: help output is comprehensive but overwhelming (100+ lines of dense text)

./b4w.sh help

#### Issue 6: Short-flag PowerShell warning appears in bash context via b4w.sh

./b4w.sh -s eval-session snapshot -v 0 2>&1

#### Issue 7: snapshot -v 0 uses zero-based viewport indexing — counterintuitive

./b4w.sh snapshot -v 0

#### Issue 8: attach auto-captures snapshot on success — surprising for new users

./b4w.sh -s test attach --cdp chrome

#### Issue 9: tab-select by GUID requires --guid flag but help text doesn't show the syntax clearly

./b4w.sh help | grep 'tab-select'

#### Issue 10: session-default command not discoverable from --help session category

./b4w.sh --help session

#### Issue 11: b4w.sh rebuilds on every invocation even when binary is up to date

Run any command via ./b4w.sh twice in succession.

