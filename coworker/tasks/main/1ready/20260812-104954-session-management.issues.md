# Issues: session-management

> **Source:** `20260812-104954-session-management.full.md` | **Date:** 20260812-104954 | **Mode:** dev

## Scenario Background

### Task

**Outcome: Successful.** All 8 steps completed without errors:

1. ✅ Named session "research" opened and navigated to Wikipedia (redirected from `Browser_automation` → `Headless_browser`)
2. ✅ Named session "news" opened and navigated to Hacker News
3. ✅ Snapshot in "news" session confirmed Hacker News content
4. ✅ Snapshot in "research" session confirmed Wikipedia article content
5. ✅ Session list showed both named sessions with full details (IDs, status, timestamps, connection type, next-open behavior)
6. ✅ "news" session closed cleanly
7. ✅ Session list confirmed only "research" remained
8. ✅ `close-all` cleared the remaining session; final list confirmed empty

### Execution Context

| Step | Command | Notes |
|------|---------|-------|
| 1 | `./b4w.ps1 -s research goto "https://en.wikipedia.org/wiki/Browser_automation"` | First launch: Maven build + JVM startup (~6.4s server start). URL redirected to `/wiki/Headless_browser` — CLI reported the redirect clearly |
| 2 | `./b4w.ps1 -s news goto "https://news.ycombinator.com"` | Fast — server already running. Session created instantly |
| 3 | `./b4w.ps1 -s news snapshot -i --stdout` | Output 109KB even in interactive-only mode (HN has many links) |
| 4 | `./b4w.ps1 -s research snapshot -i --stdout \| head -30` | Limited output with `head` to avoid terminal flood |
| 5 | `./b4w.ps1 list` | Clear table output: Name, Session ID, Status, Created, Last Access, Connection, Next open |
| 6 | `./b4w.ps1 -s news close` | Clean: "S...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: First-launch build step not documented in latency notes

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run `./b4w.ps1 -s research goto "https://example.com"` from a clean source tree with no pre-built bundle.

#### Expected Behavior

The SKILL.md's first-run latency note should accurately describe what happens: 'Maven build of runtime bundle (~1-3 min) + JVM startup (~10s)'.

#### Actual Behavior

SKILL.md says 'The Browser4 backend (Spring Boot + JVM) takes ~10s to start on first launch.' It omits the Maven build step entirely, which took significantly longer than 10s and involved building `browser4-apps/browser4-bundle`. The spinner only started after the build completed, so the user sees a long silent period before any progress indicator appears.

#### Root Cause Analysis

The SKILL.md latency note only covers the server startup phase (spinner-visible) but not the prerequisite `mvn package` step that happens before the spinner. For source-tree users, this is the dominant latency source on first run.

#### Code Pointer

`skills/browser4-cli/SKILL.md:32 — the first-run latency callout`

#### AI Suggested Improvement

- Update the first-run latency note to mention the build step: 'First run from source builds the runtime bundle via Maven (~1-3 min) + JVM startup (~10s). Subsequent commands are instant.'
- Show build progress output (not silent) so users know something is happening before the spinner appears
- Consider a pre-build check: if the bundle JAR is missing, print 'Building runtime bundle (first run only)...' before invoking Maven

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: No end-to-end multi-session workflow example in documentation

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Search SKILL.md and help output for an example showing: create named sessions, switch between them, list sessions, close individual sessions, close all.

#### Expected Behavior

The documentation should include a concrete example of the multi-session workflow — it's a core feature that differentiates Browser4 from single-session tools.

#### Actual Behavior

SKILL.md mentions sessions briefly ('Named sessions isolate browser state...Use `-s <name>` to target a named session') but provides no end-to-end example. The help output lists session commands under '[Browser sessions]' but has no workflow template. A user must discover the workflow by reading command descriptions individually.

#### Root Cause Analysis

The documentation prioritizes single-session workflows (goto → snapshot → interact → extract). Multi-session management is treated as an advanced feature with no guided path. The session lifecycle (create, switch, list, close, close-all) is implicit across scattered command docs.

#### Code Pointer

`skills/browser4-cli/SKILL.md — could add a 'Multi-Session Workflow' section under §6 Quick Patterns`

#### AI Suggested Improvement

- Add a 'Multi-Session Workflow' quick pattern to SKILL.md §6 showing: create named sessions with `-s`, switch with `-s`, list with `list`, close with `close`, cleanup with `close-all`
- Add `--json` support to the `list` command for machine-readable session data
- Consider a `session-info` command that shows details of the current session (name, ID, uptime, tab count) without listing all sessions

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: snapshot -i produces excessive output for link-heavy pages

**Severity:** Low
**Category:** UX

#### Reproduction

Run `./b4w.ps1 -s news goto "https://news.ycombinator.com"` then `./b4w.ps1 -s news snapshot -i --stdout`.

#### Expected Behavior

Interactive-only mode should produce a concise list of actionable elements. For Hacker News (~30 stories), the output should be manageable for a human to scan.

#### Actual Behavior

Output was 109.7KB — far too large to read in a terminal. Hacker News' ~30 story links + nav links + comment links produce hundreds of interactive elements. The `-i` flag doesn't help enough because every link is technically interactive.

#### Root Cause Analysis

`snapshot -i` strips non-interactive containers (div, span) but keeps ALL links, buttons, and inputs. On content-heavy pages where links ARE the content, `-i` offers little reduction. There's no intermediate filtering level between 'full AX tree' and 'all interactive elements.'

#### AI Suggested Improvement

- Consider a `--links-only` or `--depth N` flag to limit link enumeration depth
- Add a `--count` mode that shows element counts by type without full trees: '30 links, 2 buttons, 1 textbox'
- Add a `--summary` flag showing a compact page overview before the full tree
- Document the expected output size for common page types so users know when to use `head` or `snapshot grep`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `list` command name too generic for session listing

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Read `./b4w.ps1 help` and try to find the command for listing browser sessions without reading the section headers.

#### Expected Behavior

The session-listing command should be easily discoverable by name. A user scanning the command list should be able to guess that `session-list` or `list-sessions` lists browser sessions.

#### Actual Behavior

The command is named `list` — a generic verb that could mean many things. It appears under the '[Browser sessions]' section header, but if a user skips section headers and scans command names, `list` doesn't suggest 'browser sessions.' Compare with `cookie-list`, `localstorage-list`, `tab-list`, `agent list`, `plugin list`, `swarm list`, `crawl list` — all other domain-specific lists use a domain-prefixed naming pattern.

#### Root Cause Analysis

Inconsistent naming: all other list commands use `<domain> list` or `<domain>-list` patterns. `list` (sessions) is the only bare `list` command. This breaks the pattern users learn from other commands.

#### AI Suggested Improvement

- Add a `session-list` alias (or rename `list` to `session-list`) for consistency with `tab-list`, `cookie-list`, etc.
- Keep `list` as a shorthand alias for backward compatibility
- In help output, consider listing it as `list` / `session-list` to signal the domain

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: `close` vs `tab-close` namespace inconsistency

**Severity:** Low
**Category:** UX

#### Reproduction

Compare the command names in the help output: tab commands use `tab-` prefix (`tab-list`, `tab-new`, `tab-close`, `tab-select`). Session commands use bare names (`open`, `close`, `list`, `attach`).

#### Expected Behavior

Commands in the same conceptual domain should use consistent naming patterns. If tab commands are `tab-<action>`, session commands should be `session-<action>` or at least consistently unprefixed.

#### Actual Behavior

Two naming conventions coexist: domain-prefixed (`tab-*`, `cookie-*`, `localstorage-*`, `sessionstorage-*`) vs bare (`open`, `close`, `list`, `attach`). This is confusing: `tab-close` closes a tab, but `close` closes an entire session.

#### Root Cause Analysis

Session commands were likely created first with bare names (as the original/default domain). Later domain commands (tabs, storage) adopted a prefixed convention for namespacing. The legacy bare names for sessions were never updated for consistency.

#### AI Suggested Improvement

- Add `session-open`, `session-close`, `session-list` as canonical names, keeping bare `open`/`close`/`list` as aliases
- Or alternatively, document the rationale for the naming split clearly in help: 'Session commands (open, close, list) target entire browser sessions. Domain-prefixed commands (tab-*, cookie-*, etc.) target sub-resources within a session.'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: No --json output support for `list` command

**Severity:** Low
**Category:** Product

#### Reproduction

Run `./b4w.ps1 list --json` or `./b4w.ps1 --json list`.

#### Expected Behavior

Session list should support structured JSON output for scripting and machine consumption, consistent with `tab-list --json`.

#### Actual Behavior

The `list` command outputs a formatted table. The help text and SKILL.md mention `--json` support for `tab-list`, `htmlsnapshot get`, `htmlsnapshot query`, and `eval`, but `list` is not in the documented JSON-supporting commands. There's no way to programmatically query session state.

#### Root Cause Analysis

JSON output was added to newer commands (tabs, htmlsnapshot) but not backported to the older session `list` command. The output formatting code likely predates the `--json` infrastructure.

#### AI Suggested Improvement

- Add `--json` output support to `list`: `{"sessions":[{"name":"research","id":"...","status":"Active",...}],"count":2}`
- This enables scripting patterns like: `./b4w.ps1 --json list | jq '.sessions[] | select(.status=="Active") | .name'`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: URL redirect information buried in navigation output

**Severity:** Low
**Category:** UX

#### Reproduction

Run `./b4w.ps1 -s research goto "https://en.wikipedia.org/wiki/Browser_automation"`.

#### Expected Behavior

The redirect from `Browser_automation` to `Headless_browser` should be surfaced prominently — ideally with a dedicated line like '⚠ Redirected to: ...' before the page info.

#### Actual Behavior

The redirect is reported inline: 'Navigated to https://en.wikipedia.org/wiki/Headless_browser (redirected from https://en.wikipedia.org/wiki/Browser_automation)'. It's present but easy to miss in the output flow. A user who only scans the Page URL/Title section might not notice the requested URL differed from the final URL.

#### Root Cause Analysis

The redirect notice is embedded in a prose sentence rather than called out as a distinct status line. There's no visual distinction between 'navigated directly' and 'was redirected.'

#### AI Suggested Improvement

- Show redirects as a distinct, scannable line: '🔀 Redirect: https://en.wikipedia.org/wiki/Browser_automation → https://en.wikipedia.org/wiki/Headless_browser'
- Consider adding a `--follow-redirects=false` flag to stop at the first redirect and let the user decide

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 8 task steps completed without errors

**Success Rate:** 100% — every command executed correctly on first attempt

**Issues Found:** 7

**Most Confusing Aspects:** 1) The first-launch experience: a long silent Maven build with no progress indicator before the spinner appears, not matching the documented '~10s' latency. 2) The `list` command name — scanning help for 'how to list sessions' doesn't lead to `list` intuitively since all other list commands use domain prefixes. 3) The snapshot output size for link-heavy pages makes `-i` mode less useful than expected for quick verification.

**Most Valuable Improvements:** 1) Add a multi-session workflow example to SKILL.md showing create/switch/list/close patterns. 2) Add `--json` output to the session `list` command for scripting. 3) Document the Maven build step in first-launch latency notes. 4) Consider a `session-list` alias for the `list` command to match the `tab-list`/`cookie-list` naming convention.

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

#### Issue 1: First-launch build step not documented in latency notes

Run `./b4w.ps1 -s research goto "https://example.com"` from a clean source tree with no pre-built bundle.

#### Issue 2: No end-to-end multi-session workflow example in documentation

Search SKILL.md and help output for an example showing: create named sessions, switch between them, list sessions, close individual sessions, close all.

#### Issue 3: snapshot -i produces excessive output for link-heavy pages

Run `./b4w.ps1 -s news goto "https://news.ycombinator.com"` then `./b4w.ps1 -s news snapshot -i --stdout`.

#### Issue 4: `list` command name too generic for session listing

Read `./b4w.ps1 help` and try to find the command for listing browser sessions without reading the section headers.

#### Issue 5: `close` vs `tab-close` namespace inconsistency

Compare the command names in the help output: tab commands use `tab-` prefix (`tab-list`, `tab-new`, `tab-close`, `tab-select`). Session commands use bare names (`open`, `close`, `list`, `attach`).

#### Issue 6: No --json output support for `list` command

Run `./b4w.ps1 list --json` or `./b4w.ps1 --json list`.

#### Issue 7: URL redirect information buried in navigation output

Run `./b4w.ps1 -s research goto "https://en.wikipedia.org/wiki/Browser_automation"`.

