# Issues: tab-workflow

> **Source:** `20260725-171650-tab-workflow.full.md` | **Date:** 20260725-171650 | **Mode:** dev

## Scenario Background

### Task

**Task:** Complete lifecycle testing of `tab-list`, `tab-new`, `tab-select`, `tab-close` across Regular, Extension, and Mixed session types per the test plan.

**Completion:** All verifiable steps in Part A (Regular) and Part B (Extension) were completed successfully. Part C (Mixed) was partially completed — session isolation was verified using `-s` workaround, but the DEFAULT session could not be switched due to a missing `session` command.

---

### Execution Context

**Key Commands:**

All commands invoked as `./b4w.ps1 <command>` from `D:/workspace/Browser4/Browser4-4.12`.

**Part A — Regular Session (17 steps, all completed):**
```
kill-all → open → goto https://example.com
tab-list → tab-list --json → --json tab-list
tab-new https://httpbin.org/links/10 → tab-list
tab-new (FAILED) → tab-new about:blank (workaround) → tab-list
tab-select 2 → goto https://example.com
tab-select 1 → tab-select 0 → snapshot --interactive
tab-select 2 → goto https://httpbin.org/get → tab-list
tab-close 1 → tab-list
tab-close → tab-list
tab-close → tab-list (last tab → about:blank)
tab-new https://example.com → tab-list
tab-new https://httpbin.org/get → tab-close --guid <guid> → tab-list
tab-new https://httpbin.org/links/10 → tab-select 0 → tab-select --guid <guid>
tab-select 99 → tab-list (error handling)
tab-close --guid "nonexistent-guid-12345" (error handling)
rapid: tab-new + tab-new + tab-list
```

**Part B — Extension Session (8 steps, all completed):**
```
kill-all → attach --extension → goto https://example.com
tab-list → tab-new https://httpbin.org/links/10 → tab-list → list
tab-select 0 → goto https://example.com → list
tab-close 1 → tab-list → tab-close → tab-list
tab-new https://httpbin.org/get → list
tab-new https://httpbin.org/links/10 → tab-close --guid <guid> → tab-list
```

**Part C — Mixed Sessions (10 steps, partially completed):**
```
kill-all → attach --extension → goto https://example.com → open → goto https://httpbin.org/get
list → tab-list → tab-new https://example.com → tab-list + list
open -s <ext-id> (CORRUPTED session!) → kill-all + restart
attach --extension → goto → open → goto → list → tab-list → tab-new → tab-list + list
-s <ext-id> tab-list → -s <ext-id> tab-new → -s <ext-id> tab-list → tab-list (default)
tab-close 1 → tab-list + -s <ext-id> tab-list
-s <ext-id> tab-close → -s <ext-id> tab-list + tab-list
kill-all → open → goto → tab-new × 2 → tab-list
```

**Workarounds Applied During Task:**

1. `tab-new` without URL: used `tab-new about:blank` instead
2. `snapshot -i`: used `snapshot --interactive` (PowerShell intercepts `-i`)
3. `tab-list --json`: impossible to get tab data in JSON; verified via human-readable table
4. Cross-session tab targeting: used `-s <session-id>` prefix for every command targeting the non-default session (no `session`/`switch`/`use` command exists)
5. `tab-list` extraction of GUIDs: parsed from human-readable table since `--json` doesn't include tab arrays

---

---

## Issues Found (12 issues)

### Issue 1: `tab-list --json` returns no tab data

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```
./b4w.ps1 --json tab-list
```

#### Expected Behavior

A JSON object or array containing the tab list with `index`, `guid`, `url`, `title` keys for each tab.

#### Actual Behavior

Returns `{"command":"tab-list","output":{"page_title":"...","page_url":"...","snapshot_path":"..."},"status":"ok"}` — only page/snapshot metadata, **zero tab data**. When `--json` is placed after the command (`tab-list --json`), it's silently ignored and the human-readable table is shown instead.

#### Root Cause Analysis

`tab-list` appears to always trigger a full page snapshot, and the JSON serialization only includes the snapshot result, not the actual tab listing. The tab data exists (visible in human-readable output) but is not included in the JSON response envelope.

#### Code Pointer

`CLI-side: `tab-list` handler likely needs to include `tabs` array in the output serialization.`

#### AI Suggested Improvement

- Add a `tabs` array to the JSON output containing `[{index, guid, url, title}, ...]` for each tab
- Ensure `tab-list --json` (global flag after command) is also handled, not silently ignored
- The JSON response should not include snapshot metadata unless explicitly requested; the primary payload should be the tab list

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
take all suggestions

---

### Issue 2: No command to switch the default session

**Severity:** Critical
**Category:** Discoverability / Product

#### Reproduction

```
./b4w.ps1 attach --extension
./b4w.ps1 open
./b4w.ps1 session <ext-session-id>     # "Unknown command: session"
./b4w.ps1 switch <ext-session-id>      # "Unknown command: switch"
./b4w.ps1 use <ext-session-id>         # "Unknown command: use"
```

#### Expected Behavior

A command (`session`, `switch`, `use`, or similar) that changes which session is the DEFAULT, so subsequent tab commands without `-s` target that session.

#### Actual Behavior

No such command exists. Users must prefix every command with `-s <session-id>` to target a non-default session. The `list` command shows a "(default)" marker but provides no way to change it.

#### Root Cause Analysis

The DEFAULT session concept appears to track only the most-recently-created session with no explicit switch mechanism. The CLI design assumes users will always use `-s` to target named sessions, but this is undiscoverable and breaks the "tab commands operate on the current default session" contract.

#### Code Pointer

`Needs a new `session` subcommand in the CLI or a mechanism in the CLI session manager to update the default session pointer.`

#### AI Suggested Improvement

- Add a `session <session-id>` command that changes the default session (equivalent to what `open -s` does for Browser4 sessions but without corrupting extension sessions)
- Add a `session --default <session-id>` flag to `list` output hint
- Document this command in help output under "Browser sessions" section
- The `open -s <ext-id>` should either reconnect to the extension session or refuse with a clear error, not create a new Browser4 session

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `open -s <ext-session-id>` corrupts extension session

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```
./b4w.ps1 attach --extension
# Note session ID: 74e05b2b-9b3e-4298-bd80-a323b914e599
./b4w.ps1 list                    # CONNECTION: Extension ✅
./b4w.ps1 open -s 74e05b2b-9b3e-4298-bd80-a323b914e599
./b4w.ps1 list                    # CONNECTION: Browser4 ❌ (was Extension!)
```

#### Expected Behavior

Either: (a) reconnect to the existing extension session preserving Connection type, or (b) refuse with a clear error explaining that `open` cannot target extension sessions.

#### Actual Behavior

The CLI prints "Creating a new Browser4 session" and changes the session's CONNECTION type from "Extension" to "Browser4", permanently corrupting the session's transport type.

#### Root Cause Analysis

`open -s` unconditionally treats the target as a Browser4-launched session and overwrites the session metadata, even when the session was created via `attach --extension`. There's no validation that the session type supports the requested operation.

#### Code Pointer

`Session manager or `open` command handler — needs a guard checking the existing session's connection type before overwriting.`

#### AI Suggested Improvement

- Add a check in `open`: if the named session has CONNECTION type "Extension", either reconnect via the extension channel or emit a clear error: "Session <id> is an extension session — use `attach --extension` to reconnect or `-s <id>` to target it with other commands"
- Never silently change a session's CONNECTION type after creation
- Add an integration test verifying that extension sessions survive `open -s` attempts

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `tab-new` without URL fails despite documented optional argument

**Severity:** High
**Category:** Reliability

#### Reproduction

```
./b4w.ps1 tab-new
```

#### Expected Behavior

Creates a new tab with `about:blank` (the documented default value).

#### Actual Behavior

```
ERROR: browser_tabs failed: Missing parameter 'url' for newTab help: Create a new tab. Returns guid and url
browser.newTab(Arg(name=url, type=String, defaultValue=about:blank))
```

Note: the error message itself states `defaultValue=about:blank`, confirming the backend expects the parameter to be optional.

#### Root Cause Analysis

The CLI is likely not forwarding the `url` parameter when it's omitted, or it's sending `null`/empty string which the backend rejects. The backend function signature has a default value, but the CLI-to-backend marshalling may require explicit parameter passing.

#### Code Pointer

`CLI `tab-new` handler — the parameter forwarding logic between CLI args and the `browser.newTab` backend call.`

#### AI Suggested Improvement

- When `url` is not provided by the user, omit it entirely from the backend call (don't send `null` or empty string) so the backend's default value (`about:blank`) takes effect
- Or, explicitly pass `about:blank` as the URL when no argument is given
- Add a test case for `tab-new` with no arguments

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: `tab-list` output is polluted with page metadata and snapshot data

**Severity:** High
**Category:** UX

#### Reproduction

```
./b4w.ps1 tab-list
```

#### Expected Behavior

A clean table of tabs with Index, GUID, Title, URL columns on stdout. Page metadata and snapshot info should not appear — they're unrelated to listing tabs.

#### Actual Behavior

Every `tab-list` call also outputs `### Page` (URL + Title), `### Snapshot` (file path), and `💡 Tip: Run snapshot -v 0...`. This mixes two unrelated concerns and makes the output hard to scan.

#### Root Cause Analysis

`tab-list` triggers an automatic page snapshot as a side effect, and the snapshot's metadata is appended to the output. The snapshot is unnecessary for the tab listing operation.

#### Code Pointer

`CLI command handler — the `tab-list` command should not trigger a snapshot.`

#### AI Suggested Improvement

- Remove the automatic snapshot trigger from `tab-list`
- If a snapshot is needed after tab-list for workflow continuity, make it opt-in via a `--snapshot` flag
- Alternatively, separate the snapshot output to stderr so stdout contains only the tab table

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: `tab-select` and `tab-close` outputs are uninformative

**Severity:** High
**Category:** UX

#### Reproduction

```
./b4w.ps1 tab-select 1
# Output: {"type":"ai.platon.browser4.chrome.PulsarWebDriver","description":"Driver#3"}

./b4w.ps1 tab-close 1
# Output: true
```

#### Expected Behavior

`tab-select` should say "Switched to tab 1 (https://httpbin.org/get)" — telling the user which tab they're now on. `tab-close` should say "Closed tab 1 (https://httpbin.org/get)" or similar.

#### Actual Behavior

`tab-select` dumps a raw internal driver JSON object. `tab-close` prints bare `true`. Neither provides context about what tab was affected.

#### Root Cause Analysis

The CLI is printing raw backend return values rather than formatting user-friendly messages.

#### Code Pointer

`CLI output formatting layer — `tab-select` and `tab-close` command handlers.`

#### AI Suggested Improvement

- `tab-select`: "Switched to tab <index> (<url>)" — matching the format used by `tab-new`
- `tab-close`: "Closed tab <index> (<url>)" or "Closed tab with GUID <guid>" when using `--guid`
- `tab-close` (no args): "Closed current tab (<url>)"
- The raw driver object should never be shown to users

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: GUID format inconsistent between session types

**Severity:** High
**Category:** Product / UX

#### Reproduction

```
# Regular session:
./b4w.ps1 open → tab-list
# GUID: 2AAA0C47D288D3943BA85D31AA8D084C (32-char hex)

# Extension session:
./b4w.ps1 attach --extension → tab-list
# GUID: 217100004 (numeric Chrome tab ID)
```

#### Expected Behavior

GUIDs have a consistent format regardless of session type (e.g., always 32-char hex, or always UUID format). Users should be able to write scripts that parse GUIDs without session-type-specific logic.

#### Actual Behavior

Regular sessions use 32-character uppercase hex strings. Extension sessions use numeric Chrome tab IDs (9-10 digits). Furthermore, when the last tab in an extension session is closed and Chrome auto-creates a `about:blank` replacement, that replacement gets a hex GUID — so even within the same extension session, GUID formats can differ.

#### Root Cause Analysis

Regular sessions likely generate their own GUIDs, while extension sessions use Chrome's native tab IDs directly.

#### Code Pointer

`Tab GUID generation — extension session path vs. regular session path in the tab management layer.`

#### AI Suggested Improvement

- Normalize GUIDs to a consistent format across all session types (e.g., always 32-char hex)
- If Chrome tab IDs must be preserved for extension debugging, prefix them with a type indicator (e.g., `chrome:217100004`) to make the distinction explicit
- Document the GUID format and any differences in the SKILL.md

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: `snapshot -i` short flag fails on Windows/PowerShell

**Severity:** Medium
**Category:** Reliability / Platform

#### Reproduction

```
./b4w.ps1 snapshot -i
# Exit code 1
# b4w.ps1: Parameter cannot be processed because the parameter name 'i' is ambiguous.
# Possible matches include: -InformationAction -InformationVariable.
```

#### Expected Behavior

`-i` should work as the short form of `--interactive`, as documented in `snapshot --help`.

#### Actual Behavior

PowerShell intercepts `-i` before it reaches the CLI, treating it as an ambiguous PowerShell common parameter.

#### Root Cause Analysis

The `b4w.ps1` PowerShell wrapper script doesn't use `--%` (stop-parsing symbol) or proper parameter escaping to prevent PowerShell from intercepting single-character flags that collide with PowerShell's common parameters.

#### Code Pointer

``b4w.ps1` — the PowerShell wrapper script needs to use `--%` or escape single-character flags to prevent PowerShell parameter binding.`

#### AI Suggested Improvement

- Use `--%` stop-parsing token in the b4w.ps1 wrapper to prevent PowerShell from intercepting CLI flags
- Or explicitly document that on Windows, short flags like `-i`, `-c`, `-d`, `-s`, `-v` must be replaced with their long forms (`--interactive`, `--compact`, `--depth`, `--selector`, `--viewport`) when using the `.ps1` wrapper
- Add a note in SKILL.md about this Windows-specific limitation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: `tab-list --help` is too sparse

**Severity:** Medium
**Category:** Documentation / Discoverability

#### Reproduction

```
./b4w.ps1 tab-list --help
```

#### Expected Behavior

Help text showing available flags (`--json`, `--verbose`, etc.), description of output format, and an example.

#### Actual Behavior

```
browser4-cli tab-list

List all tabs
```

No flags documented, no examples, no mention of `--json` support. Users have no way to discover from the CLI how to get machine-readable output.

#### Root Cause Analysis

The `tab-list` command definition in the CLI has minimal help text. The `--json` flag, while a global option, doesn't actually produce useful output for `tab-list` (see Issue 1), so the two issues compound.

#### Code Pointer

`CLI command registration for `tab-list` — the help text and flag definitions.`

#### AI Suggested Improvement

- Document `--json` flag in `tab-list --help` and ensure it produces proper tab array output
- Add a usage example: `browser4-cli tab-list --json`
- Document all supported flags explicitly

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: `tab-list` in SKILL.md is minimally documented

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read `skills/browser4-cli/SKILL.md` — search for tab documentation.

#### Expected Behavior

A section describing tab commands, their flags, output formats, and session scoping behavior.

#### Actual Behavior

Tab commands appear only in a one-liner in the command map table: "`screenshot`, `scroll`, `wait`, `resize`, `tab-*` | Visual capture & viewport control | Screenshots, tab management. `tab-select` / `tab-close` accept `--guid <guid>` for stable tab IDs; use `tab-list --json` to see full GUIDs." No dedicated section, no examples, no explanation of session scoping.

#### Root Cause Analysis

Tab commands are grouped under "Visual capture & viewport control" rather than having their own section. The documentation mentions `tab-list --json` but this doesn't actually work (Issue 1).

#### Code Pointer

``skills/browser4-cli/SKILL.md` — needs a dedicated tab management section.`

#### AI Suggested Improvement

- Add a dedicated "Tab Management" subsection under §4 (Decision Trees) or §6 (Quick Patterns)
- Show the full tab lifecycle: list → create → select → close
- Document `--guid` operations with examples
- Document the `-s` flag for cross-session tab targeting
- Document that `--json` with `tab-list` is a global flag (must be `--json tab-list`, not `tab-list --json`)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: `tab-new` insert position is inconsistent

**Severity:** Low
**Category:** Reliability / UX

#### Reproduction

```
# First tab-new: inserts at index 0 (beginning)
./b4w.ps1 tab-new https://example.com
# "Switched to tab 0 (https://example.com)"

# Second tab-new: inserts at index 2 (not consistent)
./b4w.ps1 tab-new https://httpbin.org/get
# "Switched to tab 1 (https://httpbin.org/get)"  — when there are already 2 tabs
```

#### Expected Behavior

Consistent insert position — either always at the end or always adjacent to the current tab.

#### Actual Behavior

The insert position appears to vary. In some cases new tabs appear at index 0, in others they appear after the current tab. The behavior is not documented anywhere.

#### Root Cause Analysis

The tab creation logic may depend on which tab is currently active or may delegate to Chrome's native behavior which varies.

#### Code Pointer

`Tab creation logic — needs explicit positioning policy.`

#### AI Suggested Improvement

- Standardize tab insertion: always after the current tab, or always at the end
- Document the chosen behavior in `tab-new --help` and SKILL.md
- The "Switched to tab N" message should always match the actual index in the tab list

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: Closing last tab silently creates `about:blank` without clear indication

**Severity:** Low
**Category:** UX

#### Reproduction

```
./b4w.ps1 tab-close    # On the last remaining tab
# Output: true
./b4w.ps1 tab-list
# Still shows 1 tab (about:blank) with a different GUID
```

#### Expected Behavior

Either (a) "No tabs found." with empty list, or (b) a clear message like "Last tab replaced with about:blank (Chrome requires at least one open tab)."

#### Actual Behavior

`tab-close` returns `true` (implying success), but the tab was replaced rather than removed. A new tab with a different GUID appears. Users relying on GUID stability may be confused.

#### Root Cause Analysis

Chrome automatically creates a new tab when the last one is closed in a window. Browser4-cli doesn't communicate this to the user.

#### Code Pointer

``tab-close` handler — should detect the "last tab replaced" scenario and surface it.`

#### AI Suggested Improvement

- When closing the last tab, emit a warning: "Note: Chrome requires at least one open tab. A new blank tab was created."
- Update SKILL.md to document this Chrome behavior
- The task plan's expectation of "No tabs found" after closing all tabs should be documented as unreachable for Browser4-launched sessions (possible only in certain extension scenarios)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `tab-list --json` returns no tab data

```
./b4w.ps1 --json tab-list
```

#### Issue 2: No command to switch the default session

```
./b4w.ps1 attach --extension
./b4w.ps1 open
./b4w.ps1 session <ext-session-id>     # "Unknown command: session"
./b4w.ps1 switch <ext-session-id>      # "Unknown command: switch"
./b4w.ps1 use <ext-session-id>         # "Unknown command: use"
```

#### Issue 3: `open -s <ext-session-id>` corrupts extension session

```
./b4w.ps1 attach --extension
# Note session ID: 74e05b2b-9b3e-4298-bd80-a323b914e599
./b4w.ps1 list                    # CONNECTION: Extension ✅
./b4w.ps1 open -s 74e05b2b-9b3e-4298-bd80-a323b914e599
./b4w.ps1 list                    # CONNECTION: Browser4 ❌ (was Extension!)
```

#### Issue 4: `tab-new` without URL fails despite documented optional argument

```
./b4w.ps1 tab-new
```

#### Issue 5: `tab-list` output is polluted with page metadata and snapshot data

```
./b4w.ps1 tab-list
```

#### Issue 6: `tab-select` and `tab-close` outputs are uninformative

```
./b4w.ps1 tab-select 1
# Output: {"type":"ai.platon.browser4.chrome.PulsarWebDriver","description":"Driver#3"}

./b4w.ps1 tab-close 1
# Output: true
```

#### Issue 7: GUID format inconsistent between session types

```
# Regular session:
./b4w.ps1 open → tab-list
# GUID: 2AAA0C47D288D3943BA85D31AA8D084C (32-char hex)

# Extension session:
./b4w.ps1 attach --extension → tab-list
# GUID: 217100004 (numeric Chrome tab ID)
```

#### Issue 8: `snapshot -i` short flag fails on Windows/PowerShell

```
./b4w.ps1 snapshot -i
# Exit code 1
# b4w.ps1: Parameter cannot be processed because the parameter name 'i' is ambiguous.
# Possible matches include: -InformationAction -InformationVariable.
```

#### Issue 9: `tab-list --help` is too sparse

```
./b4w.ps1 tab-list --help
```

#### Issue 10: `tab-list` in SKILL.md is minimally documented

Read `skills/browser4-cli/SKILL.md` — search for tab documentation.

#### Issue 11: `tab-new` insert position is inconsistent

```
# First tab-new: inserts at index 0 (beginning)
./b4w.ps1 tab-new https://example.com
# "Switched to tab 0 (https://example.com)"

# Second tab-new: inserts at index 2 (not consistent)
./b4w.ps1 tab-new https://httpbin.org/get
# "Switched to tab 1 (https://httpbin.org/get)"  — when there are already 2 tabs
```

#### Issue 12: Closing last tab silently creates `about:blank` without clear indication

```
./b4w.ps1 tab-close    # On the last remaining tab
# Output: true
./b4w.ps1 tab-list
# Still shows 1 tab (about:blank) with a different GUID
```

