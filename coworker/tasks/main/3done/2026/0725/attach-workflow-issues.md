# Issues: attach-workflow

> **Source:** `20260724-221712-attach-workflow.full.md` | **Date:** 20260724-221712 | **Mode:** dev

## Scenario Background

### Task

**Task completion status: PARTIAL** — 8 of 17 steps fully passed as specified; 4 additional steps passed with workarounds; 5 steps had verification failures due to design limitations.

---

### Execution Context

| Step | Command | Result | Notes |
|------|---------|--------|-------|
| Pre | `kill-all` | ✅ | Clean slate |
| 1 | `attach --extension` | ✅ | Session `5af840a9` created, connection healthy in 1s |
| 2 | `list` | ✅ | Extension, Active, Reuse |
| 3 | `goto https://example.com/1` | ✅ | Title "Example Domain", snapshot generated |
| 4 | `list` | ✅ | Last Access updated from 06:07:06 → 06:07:32 |
| 5 | `goto chrome://version/` | ⚠️ | Page Title EMPTY (expected "Chrome"/"Version") |
| 6 | `snapshot grep "user-data-dir"` | ✅ | **Critical regression check passed** — no matches |
| 7 | `goto https://example.com/2` | ❌→✅ | Session went **Stale** after chrome://version; re-attached and retried successfully |
| 8 | `list` (after retry) | ✅ | Extension, Active with new session ID |
| 9 | `open` | ❌ |...

(truncated — see full.md for complete trace)

---

## Issues Found (10 issues)

### Issue 1: Extension session goes stale after navigating to chrome://version

**Severity:** High
**Category:** Reliability

#### Reproduction

```
./b4w.ps1 attach --extension
./b4w.ps1 goto https://example.com/1     # works
./b4w.ps1 goto chrome://version/          # works but title empty
./b4w.ps1 goto https://example.com/2     # fails: "session is no longer healthy"
```

#### Expected Behavior

Extension session remains healthy across any navigation, including chrome:// internal pages.

#### Actual Behavior

After navigating to `chrome://version/`, the extension WebSocket disconnects. The session status changes from "Active" to "Stale". Subsequent `goto` fails with: "Attached session ... is no longer healthy. The browser or extension may have disconnected."

#### Root Cause Analysis

Navigating to `chrome://version/` causes the extension's WebSocket relay to disconnect. This is likely because chrome:// pages run in a different process/context that may not persist the extension's content script connection, or the page transition interrupts the WebSocket handshake. The guard fix correctly catches this and prevents silent fallthrough, but the underlying connection fragility remains a reliability concern.

#### Code Pointer

``browser4-rest` — WebSocket relay handler for extension sessions; likely in or near the `ws/extension/{sessionId}` endpoint handler.`

#### AI Suggested Improvement

- Investigate why chrome:// page navigation causes extension WebSocket disconnection — the extension's content script or background script may lose context on privileged pages
- Implement automatic WebSocket reconnection with exponential backoff in the extension relay
- Add a `--reconnect` flag to `attach` to automatically re-establish broken extension connections
- Document that chrome:// and extension:// pages may cause disconnection in the attach reference

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Investigate why chrome:// page navigation causes extension WebSocket disconnection

---

---

### Issue 2: `open` command does not create a new session when an extension session occupies the DEFAULT name

**Severity:** High
**Category:** UX

#### Reproduction

```
./b4w.ps1 attach --extension                        # DEFAULT = extension
./b4w.ps1 open                                       # says "Session already open"
```

#### Expected Behavior

`open` should create a new regular Browser4 session distinct from the extension session, or at minimum provide clear guidance on how to create a parallel Browser4 session.

#### Actual Behavior

"Using existing session DEFAULT ... Session already open: <extension-session-id>". The command silently reuses the extension session without creating a new Browser4 session.

#### Root Cause Analysis

The `open` command checks if a session with the requested name exists and reuses it. There is no mechanism to have two sessions with the same name, and no automatic fallback to create a differently-named session. The session namespace is flat — one session per name.

#### Code Pointer

`CLI `open` command handler — likely in `cli/browser4-cli/src/` session management, and backend session registry.`

#### AI Suggested Improvement

- `open` should create a new session with an auto-generated unique name when DEFAULT is occupied (e.g., "browser4-1", "browser4-2")
- Display a clear message: "Session DEFAULT is occupied by an Extension session. Creating new session 'browser4-1' instead. Use -s <name> to specify a custom name."
- Add `open --new` flag to explicitly request a new session even if one exists
- Allow multiple sessions to coexist with different names; `goto` and other commands without `-s` should use the most recently opened/created session as default

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
support truly concurrent named sessions: without -s <name>, create a DEFAULT session, with -s <name>, create a named session, no matter how to connect to the browser. display a clear message.

---

---

---

---

---

### Issue 3: `./b4w.ps1` cannot pass `-s <session>` flag from bash

**Severity:** High
**Category:** CLI Experience

#### Reproduction

```bash
./b4w.ps1 -s myname open
./b4w.ps1 -s myname goto https://example.com
```

#### Expected Behavior

The `-s` flag is forwarded to the browser4-cli binary as a session name selector.

#### Actual Behavior

PowerShell error: "A positional parameter cannot be found that accepts argument 'open'". PowerShell consumes `-s` as an abbreviation for `-ScriptArgs` (the only parameter in the `param()` block starting with 'S'), breaking the argument forwarding.

#### Root Cause Analysis

PowerShell's parameter prefix matching resolves `-s` to `-ScriptArgs` before the `ValueFromRemainingArguments` capture. The `b4w.ps1` script's `param()` block has `[switch]$Rebuild` and `[Parameter(ValueFromRemainingArguments = $true)][string[]]$ScriptArgs`. PowerShell sees `-s` and matches it to `-ScriptArgs`, consuming the next argument as its value and misplacing the remaining arguments.

#### Code Pointer

``b4w.ps1:3-6` — the `param()` block. Root fix is renaming `$ScriptArgs` to something that doesn't start with 'S' (e.g., `$RemainingArgs`), or using `--%` or a passthrough mechanism.`

#### AI Suggested Improvement

- Rename `$ScriptArgs` to `$CliArgs` or `$RemainingArgs` to avoid prefix collision with `-s` (the most important CLI flag)
- Add explicit passthrough support: `./b4w.ps1 -- -s myname open` should work
- Document the `-s` workaround in SKILL.md: use the raw binary path for named sessions, or use the `--` separator
- Add a `b4w.sh` wrapper that correctly forwards `-s` without PowerShell interference

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
1. rename `$ScriptArgs` 2.  Add explicit passthrough support

---

---

---

---

---

---

### Issue 4: Page title is empty when navigating to chrome://version

**Severity:** Medium
**Category:** Product

#### Reproduction

```
./b4w.ps1 attach --extension
./b4w.ps1 goto chrome://version/
```
Output shows `- Page Title: ` (empty).

#### Expected Behavior

Page title should reflect the chrome://version page content (e.g., "Chrome 版本" or "Version").

#### Actual Behavior

Page title field is empty/blank.

#### Root Cause Analysis

The accessibility tree for chrome:// internal pages may not expose a standard document title. The snapshot extraction likely reads `document.title` which may be empty or not populated for chrome:// pages. This could also be a CDP limitation where `Page.getNavigationHistory` or similar APIs return an empty title for privileged pages.

#### Code Pointer

`Backend snapshot/title extraction logic — likely in `PulsarWebDriver`'s page info capture.`

#### AI Suggested Improvement

- Fall back to the URL or page type when `document.title` is empty for chrome:// and other privileged pages
- Use CDP `Runtime.evaluate` to try alternate title sources (e.g., heading text, `<title>` tag via DOM)
- Display "chrome://version" or "(internal page)" as a fallback label instead of blank

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

### Issue 5: Cannot run two concurrent sessions (Extension + Browser4) simultaneously

**Severity:** Medium
**Category:** Product

#### Reproduction

```
./b4w.ps1 attach --extension     # extension session on DEFAULT
./b4w.ps1 open                    # fails to create Browser4 session
# OR:
./b4w.ps1 open                    # Browser4 session on DEFAULT
./b4w.ps1 attach --extension     # replaces Browser4 session on DEFAULT
```

#### Expected Behavior

Extension and Browser4 sessions should coexist as independent sessions with different names and connection types.

#### Actual Behavior

Only one session exists per name. Starting a new session type replaces the previous one for the "(default)" name. The session namespace is flat — no support for multiple concurrent sessions of different types.

#### Root Cause Analysis

The session management system uses a flat name→session mapping. The `attach --extension` and `open` commands both target the same DEFAULT slot. The architecture does not support maintaining separate named sessions simultaneously (though the `-s <name>` flag implies it should).

#### Code Pointer

`Backend session registry — likely in `browser4-rest` session management, and CLI session bookkeeping.`

#### AI Suggested Improvement

- Support truly concurrent named sessions — each `-s <name>` should have its own independent browser/extension connection
- When `attach --extension` is used, auto-name the session (e.g., "ext-chrome") instead of claiming DEFAULT
- When `open` runs and DEFAULT is occupied by an extension session, create a new "browser4" session instead
- Update `list` to show all concurrent sessions regardless of type

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
support truly concurrent named sessions: without -s <name>, create a DEFAULT session, with -s <name>, create a named session, no matter how to connect to the browser.

---

---

---

---

---

---

---

---

---

---

---

---

---

### Issue 6: `--extension` flag not documented in `skills/browser4-cli/references/attach.md`

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read `skills/browser4-cli/references/attach.md` — no mention of `--extension`. Compare with `./b4w.ps1 attach --help` which does document it.

#### Expected Behavior

The SKILL.md reference document (`attach.md`) should cover all attach modes, including `--extension`.

#### Actual Behavior

`attach.md` documents `--cdp` and `--endpoint` but completely omits `--extension`. The flag exists in the CLI and is documented in `--help` output but not in the skill reference that AI agents (and users) are directed to read.

#### Root Cause Analysis

The `attach.md` reference file was not updated when the `--extension` flag was added to the `attach` command.

#### Code Pointer

``skills/browser4-cli/references/attach.md` — needs a new section for "Attach by Browser4 Extension".`

#### AI Suggested Improvement

- Add a "### 6. Attach via Browser4 Extension" section to `attach.md` with usage examples
- Document the extension relay endpoint format, browser channel options, and health check behavior
- Include troubleshooting for extension disconnection and re-attach workflow
- Add `--extension` to the Flags table

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

### Issue 7: No `--extension` usage example in `attach --help` output

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `./b4w.ps1 attach --help`. The `--extension` flag is described in the Options section but no example is given.

#### Expected Behavior

At least one example showing `browser4-cli attach --extension`.

#### Actual Behavior

Examples section shows only `--cdp` and `--endpoint` examples. A new user reading the help cannot see the invocation pattern for extension attach without trial and error.

#### Root Cause Analysis

Help text examples were not updated when the `--extension` flag was added.

#### Code Pointer

`CLI argument parser for `attach` — help text generation in `cli/browser4-cli/src/`.`

#### AI Suggested Improvement

- Add example: `browser4-cli attach --extension`
- Add example: `browser4-cli attach --extension chrome-canary`
- Add example: `browser4-cli attach --extension msedge`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

### Issue 8: "current page: about:blank" is misleading for extension-attached sessions

**Severity:** Low
**Category:** UX

#### Reproduction

```
./b4w.ps1 attach --extension
./b4w.ps1 goto https://example.com
```
Output: "Using existing session DEFAULT (current page: about:blank)."

#### Expected Behavior

The backend should report the actual current page of the attached browser, or indicate that the current page is unknown for extension-attached sessions.

#### Actual Behavior

The output always shows "(current page: about:blank)" for extension sessions, even when Chrome is displaying a different page. This gives a false impression that the browser is on a blank page.

#### Root Cause Analysis

The extension relay does not query the attached browser's current URL when establishing the session. The session state is initialized with a default value (about:blank) rather than being populated from the actual browser state.

#### Code Pointer

`Session initialization logic — likely in `MCPToolController` or session management in `browser4-rest`.`

#### AI Suggested Improvement

- Query the attached browser's current active tab URL during extension session creation
- If the URL cannot be determined, display "(current page: unknown)" instead of "(current page: about:blank)"
- Alternatively, omit the "(current page: ...)" parenthetical for extension-attached sessions

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
open a about:blank tab and attach to it is the design

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

### Issue 9: Session ID format inconsistency — UUID for extension, "DEFAULT" string for Browser4

**Severity:** Low
**Category:** UX

#### Reproduction

```
./b4w.ps1 attach --extension   # Session ID: 5af840a9-ae5a-4843-ab2f-753dce429158
./b4w.ps1 open                  # Session ID: DEFAULT
./b4w.ps1 list                  # Extension shows UUID, Browser4 shows "DEFAULT"
```

#### Expected Behavior

Consistent session ID format across all session types (UUID for all sessions).

#### Actual Behavior

Extension sessions have UUID-based session IDs (e.g., `5af840a9-ae5a-4843-ab2f-753dce429158`), while regular Browser4 sessions created via `open` show `DEFAULT` as the session ID.

#### Root Cause Analysis

The two session creation paths (extension vs. regular) use different ID generation strategies. Extension sessions generate a UUID, while regular sessions may use the session name as the ID.

#### Code Pointer

`Session creation logic in `MCPToolController` or `PulsarWebDriver`.`

#### AI Suggested Improvement

- Generate UUID-based session IDs for ALL session types, including regular Browser4 sessions
- Use the session name (e.g., "DEFAULT") only as the display name in the Name column, not as the Session ID

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

### Issue 10: `close` on an extension session shows "Disconnected from attached browser" — discoverability of disconnect-vs-close semantics

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```
./b4w.ps1 attach --extension
./b4w.ps1 close
```

#### Expected Behavior

Clear indication of what "close" means for an extension-attached session vs. a regular Browser4 session.

#### Actual Behavior

"Disconnected from attached browser. The browser remains running." This is actually correct behavior (don't kill the user's Chrome), but the command is still called `close` which implies closing the browser. A new user may be confused about what `close` does for different session types.

#### Root Cause Analysis

The `close` command has different semantics depending on session type (close Browser4 browser vs. disconnect from attached browser), but the command name doesn't differentiate these.

#### AI Suggested Improvement

- Add a `disconnect` alias or subcommand that more accurately describes the extension session behavior
- Add a tip/warning before closing: "This will disconnect from your Chrome browser without closing it. Use `kill-all` to force-close browser processes."
- Document the close/disconnect distinction in `attach.md`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Extension session goes stale after navigating to chrome://version

```
./b4w.ps1 attach --extension
./b4w.ps1 goto https://example.com/1     # works
./b4w.ps1 goto chrome://version/          # works but title empty
./b4w.ps1 goto https://example.com/2     # fails: "session is no longer healthy"
```

#### Issue 2: `open` command does not create a new session when an extension session occupies the DEFAULT name

```
./b4w.ps1 attach --extension                        # DEFAULT = extension
./b4w.ps1 open                                       # says "Session already open"
```

#### Issue 3: `./b4w.ps1` cannot pass `-s <session>` flag from bash

```bash
./b4w.ps1 -s myname open
./b4w.ps1 -s myname goto https://example.com
```

#### Issue 4: Page title is empty when navigating to chrome://version

```
./b4w.ps1 attach --extension
./b4w.ps1 goto chrome://version/
```
Output shows `- Page Title: ` (empty).

#### Issue 5: Cannot run two concurrent sessions (Extension + Browser4) simultaneously

```
./b4w.ps1 attach --extension     # extension session on DEFAULT
./b4w.ps1 open                    # fails to create Browser4 session
# OR:
./b4w.ps1 open                    # Browser4 session on DEFAULT
./b4w.ps1 attach --extension     # replaces Browser4 session on DEFAULT
```

#### Issue 6: `--extension` flag not documented in `skills/browser4-cli/references/attach.md`

Read `skills/browser4-cli/references/attach.md` — no mention of `--extension`. Compare with `./b4w.ps1 attach --help` which does document it.

#### Issue 7: No `--extension` usage example in `attach --help` output

Run `./b4w.ps1 attach --help`. The `--extension` flag is described in the Options section but no example is given.

#### Issue 8: "current page: about:blank" is misleading for extension-attached sessions

```
./b4w.ps1 attach --extension
./b4w.ps1 goto https://example.com
```
Output: "Using existing session DEFAULT (current page: about:blank)."

#### Issue 9: Session ID format inconsistency — UUID for extension, "DEFAULT" string for Browser4

```
./b4w.ps1 attach --extension   # Session ID: 5af840a9-ae5a-4843-ab2f-753dce429158
./b4w.ps1 open                  # Session ID: DEFAULT
./b4w.ps1 list                  # Extension shows UUID, Browser4 shows "DEFAULT"
```

#### Issue 10: `close` on an extension session shows "Disconnected from attached browser" — discoverability of disconnect-vs-close semantics

```
./b4w.ps1 attach --extension
./b4w.ps1 close
```

