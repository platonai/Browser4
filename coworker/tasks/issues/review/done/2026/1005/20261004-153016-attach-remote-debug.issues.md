# Issues: attach-remote-debug

> **Source:** `20261004-153016-attach-remote-debug.full.md` | **Date:** 20261004-153016 | **Mode:** dev

## Scenario Background

### Task

All eight task steps were completed against a real Chrome 154 instance (started with `--remote-debugging-port=9222` and a scratch profile). The attachment worked, and the attached browser was successfully driven: tabs enumerated, page navigated, screenshots and accessibility-tree snapshots captured, browser state saved, tabs switched and screenshotted, and the session closed — twice via `attach --cdp` (channel + explicit URL) and once through `attach --endpoint`.

Key outcomes:
- `attach --cdp chrome` **attached, but to the wrong Chrome** — a leftover Browser4-managed headless instance on port 1576, not the Chrome explicitly started on 9222. The documented fallback `attach --cdp http://localhost:9222` targeted the intended browser correctly.
- `attach --endpoint http://localhost:18182 --cdp chrome` worked only with `-s <name>`; the named attach **aliased the existing session ID** (two names, one session ID in `list`), and closing either name removed both.
- Screenshots (5 PNGs), an AX-tree snapshot, `state-save` JSON, `tab-list --json`, backend logs, and the CLI state file `~/.browser4/cli-state.json` were all captured as evidence under `.test-sessions/20261004T1433038523034Z/trace/`.
- `close` on the CDP-attached sessions **terminated the user-started Chrome processes** (all three times; the headed browser dies with its last tab/window), directly contradicting `attach.md`'s "the browser process continues running". This was reproduced in a controlled 2-tab experiment where the browser still died though a second, untouched tab existed.

### Execution Context

**Key Commands:**

**Major steps:**
1. Verified repo root, ran `help`, read `skills/browser4-cli/SKILL.md` and `references/attach.md`.
2. Started Chrome 154 with `--remote-debugging-port=9222 --user-data-dir=<scratch>`; confirmed CDP on 9222 answers.
3. `attach --cdp chrome` → attached to `http://localhost:1576` (a leftover Browser4-managed headless Chrome). Investigated via process list; no alternative offered.
4. Listed sessions (`list` — found a leftover `pywiki` session plus my `(default)`) and tabs (`tab-list` — 1 tab, about:blank).
5. `screenshot` (blank page) then `goto https://example.com`, `screenshot`, `snapshot -v 0 --stdout` (real AX tree; screenshot visually verified).
6. `state-save` → JSON with cookies + localStorage for example.com.
7. Created 2 tabs (`example.org`, iana.org), `tab-select 0/1/2`, `page-info`, `screenshot` each (iana screenshot visually verified). Discovered `tab-list --json` reports `"active": false` for all tabs.
8. `attach --endpoint http://localhost:18182 --cdp chrome` → refused (unnamed slot occupied) → retried with `-s endpoint-test` → attached, reused session ID, `list` showed two names for one ID; `-s endpoint-test close` removed both names and printed "Session closed. Browser terminated." while the browser stayed alive.
9. Bad-endpoint test (`--endpoint http://localhost:1`) → clean exit 1 with raw reqwest error; no config damage.
10. `attach --cdp http://localhost:9222` → correct browser; `close` → browser process died (contradicts docs).
11. Controlled experiment: fresh Chrome with 2 tabs → attach → `close` → both tabs closed (backend log: "Closing browser with 2 drivers/devtools"), whole browser dead though a second untouched tab existed.
12. Root-caused the mislabeling via `~/.browser4/cli-state.json` (`sessionKind: browser4Launched` written by `attach --cdp`) and source inspection (`state.rs:404-407` clobbers legacy flags from `kind`; attach handler never sets `kind`).

**Workarounds required:** explicit `--cdp http://localhost:9222` to reach the intended browser; `-s <name>` for the `--endpoint` attach; `tab-new` to obtain multiple tabs (browser had only one).



**Raw evidence** (full command outputs, screenshots, state JSON, backend log excerpts) is in `.test-sessions/20261004T1433038523034Z/trace/`. No browser4 sessions of mine remain open (only the pre-existing `pywiki` session from an earlier run, which I deliberately left untouched); all three Chrome instances I started were terminated by `close` as described above.

---

## Issues Found (7 issues)

### Issue 1: close on a CDP-attached session terminates the user's browser, contradicting the docs

**Severity:** High
**Category:** Product

#### Reproduction

1) Start Chrome: chrome --remote-debugging-port=9222 --user-data-dir=<tmp> https://example.com https://example.org  2) ./b4w.ps1 attach --cdp http://localhost:9222  3) ./b4w.ps1 close  4) Check: curl http://localhost:9222/json/version and the chrome PID

#### Expected Behavior

Per skills/browser4-cli/references/attach.md (Close vs Disconnect): 'CDP-attached (via attach --cdp): close disconnects from the remote debugging port - the browser process continues running. The tab Browser4 was bound to is closed with the session.'

#### Actual Behavior

Printed 'Session closed. Browser terminated.'; port 9222 stopped answering and the Chrome process was gone. Reproduced 3x (twice with a single tab, once with two tabs where the second tab was never interacted with beyond appearing in tab-list). Backend log: 'Closing browser with 2 drivers/devtools ... | cx.ext.attach.port.9222' then 'Browser is closed successfully ... Inactive,Disconnected'. A leftover Browser4-managed *headless* Chrome survived its own close (headless has no window), confirming the mechanism: close closes every tab enrolled as a session driver (tab-list 'recovers' tabs as drivers), and a headed Chrome exits when its last window closes.

#### Root Cause Analysis

Backend close_session closes all tabs the session holds drivers for, not just the bound tab. When those are the browser's only tabs, closing the last one shuts down the headed Chrome process. The CLI prints the generic managed-browser message because of the separate state bug (see Issue 2), so the misleading output and the destructive behavior compound. Whether the backend additionally issues a browser-level terminate vs. relying on Chrome's last-window behavior needs code-level confirmation; the user-visible outcome is the same.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt (closeSession / session deletion -> PulsarBrowser.close, external pulsar artifact) and cli/browser4-cli/src/main.rs:3083-3091 (close message branch); docs at skills/browser4-cli/references/attach.md:276-287`

#### AI Suggested Improvement

- For CDP-attached sessions, either (a) close only the tab Browser4 was bound to and leave other tabs (and the browser) alone, or (b) if all tabs must close, open an about:blank replacement tab before closing the last one so the browser survives.
- Do not enroll tabs that were merely enumerated by tab-list into the set that close() destroys.
- Make the close output reflect what actually happened (probe the endpoint after close) instead of a fixed string.
- Update attach.md to describe the real behavior (which tabs are closed, when the browser can exit) rather than promising 'the browser process continues running'.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: attach --cdp never persists SessionKind::CdpAttached: list shows 'Browser4' and close prints the managed-browser message

**Severity:** High
**Category:** Product

#### Reproduction

1) ./b4w.ps1 attach --cdp http://localhost:9222  2) cat ~/.browser4/cli-state.json  3) ./b4w.ps1 list  4) ./b4w.ps1 close

#### Expected Behavior

The state file records the session as an external attach (sessionKind 'cdpAttached', isAttached true); list shows 'CDP: http://localhost:9222 (Google Chrome 154)' per SKILL.md; close prints 'Disconnected from attached browser. The browser remains running.' (the message that main.rs:3087 is written to print).

#### Actual Behavior

cli-state.json contains "sessionKind": "browser4Launched" and cdpEndpoint but no attachType/isAttached; list shows Connection 'Browser4'; close prints 'Session closed. Browser terminated.'. Additionally the documented protection 'Disconnected attached sessions are never silently replaced' is bypassed because state.is_attached is false wherever it is consulted.

#### Root Cause Analysis

The attach handler (cli/browser4-cli/src/main.rs:2418-2425) sets the deprecated legacy fields is_attached/attach_type but never sets state.kind. write_state_to_dir (cli/browser4-cli/src/state.rs:404-407) then recomputes is_attached and attach_type *from* kind, clobbering them back to Browser4Launched/false before serialization. migrate_legacy_kind cannot repair the file later because the persisted file never contains is_attached=true.

#### Code Pointer

`cli/browser4-cli/src/state.rs:404-407 (write_state_to_dir sync) and cli/browser4-cli/src/main.rs:2418-2425 (attach state write); consumers at cli/browser4-cli/src/main.rs:4193-4209 (connection_label) and :3073-3091 (handle_close)`

#### AI Suggested Improvement

- In the CDP attach handler set state.kind = SessionKind::CdpAttached (and ExtensionAttached for --extension) in addition to (or instead of) the legacy fields.
- Make write_state_to_dir stop clobbering explicitly-set legacy fields, or delete the legacy fields entirely now that kind exists.
- Add a unit test asserting the persisted state after attach --cdp has sessionKind=cdpAttached and that list/close consume kind, not is_attached.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: attach --cdp chrome silently picks a leftover Browser4-managed Chrome instead of the browser started with --remote-debugging-port=9222

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1) Have any leftover Browser4-managed Chrome running (started with --remote-debugging-port=0 in a PULSAR_CHROME profile). 2) Start Chrome with --remote-debugging-port=9222. 3) ./b4w.ps1 attach --cdp chrome

#### Expected Behavior

Attach to the browser configured with the documented channel flag (port 9222), or, when several Chrome instances are attachable, list them and ask the user to choose (or at least warn prominently before choosing).

#### Actual Behavior

'Attached to Google Chrome 154.0.8037.93 at http://localhost:1576' - the leftover managed instance won; the user's explicitly-flagged browser on 9222 was ignored. Only the printed port hints at the mismatch, and a first-time user following the quick start has no reason to notice it.

#### Root Cause Analysis

The documented endpoint-resolution order (attach.md, Endpoint resolution) probes tier 1 - the DevToolsActivePort of *any* running Chrome process - before the channel's conventional port (9222, tier 3). A leftover Browser4-managed browser therefore beats the user's explicitly-started one. attach.md presents 'attach --cdp chrome' as the simplest path without warning about this precedence.

#### Code Pointer

`cli/browser4-cli/src/main.rs (attach channel/endpoint resolution; see resolve_cdp_endpoint and the candidate probe order described in attach.md)`

#### AI Suggested Improvement

- When more than one candidate can host a page, print all of them ('found 2 browsers: localhost:9222 (Chrome 154), localhost:1576 (Chrome 154, Browser4-managed)') and require an explicit choice, or prefer the channel-default port when it answers.
- At minimum, emit a prominent warning when the chosen candidate is a Browser4-managed browser (random debug port) rather than the channel's conventional port.
- Document the precedence and the explicit-endpoint escape hatch directly in the Quick Start of attach.md.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Named attach reuses an existing session ID: two session names alias one session, and closing either removes both

**Severity:** Medium
**Category:** UX

#### Reproduction

1) ./b4w.ps1 attach --cdp chrome  (creates unnamed session 420e9b4b)  2) ./b4w.ps1 -s endpoint-test attach --endpoint http://localhost:18182 --cdp chrome  3) ./b4w.ps1 list  4) ./b4w.ps1 -s endpoint-test close  5) ./b4w.ps1 list

#### Expected Behavior

A named session is a distinct session (SKILL.md: 'Named sessions isolate browser state ... keyed by the session id'), or the tool states that the existing session was reused instead of reporting 'Session opened'.

#### Actual Behavior

'Session opened: endpoint-test (420e9b4b-...)' - the same ID list already showed for '(default)'. list displays two rows (endpoint-test and (default)) with identical Session ID and timestamps. Closing the named alias deletes the shared underlying session and both name entries disappear from list.

#### Root Cause Analysis

Backend log: 'Re-attached session 420e9b4b-... to browser at port 1576 (existing driver kept)' - attach with -s associates the new name with the backend session already bound to that browser instead of creating an independent session. Possibly intended attach semantics (attach help: 'When attaching, the current session slot is associated with the external browser'), but the 'Session opened' wording and the duplicate-ID listing contradict the session-isolation model and are undocumented.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/session/PulsarSessionManager.kt (attach / session reuse) and cli/browser4-cli/src/main.rs (attach 'Session opened' output)`

#### AI Suggested Improvement

- Either create a genuinely distinct session for each -s name, or report 'Re-attached existing session <id> as <name>' and show one list row per session ID with all its aliases.
- Reject or warn when -s <name> resolves to an alias of an existing session ID.
- Document attach's session-slot semantics explicitly in attach.md.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: No way to tell which tab is active: tab-list's active flag is always false and page-info marks nothing

**Severity:** Medium
**Category:** Product

#### Reproduction

1) Open several tabs. 2) ./b4w.ps1 tab-select 2  (prints 'Switched to tab 2'). 3) ./b4w.ps1 tab-list  4) ./b4w.ps1 tab-list --json  5) ./b4w.ps1 page-info

#### Expected Behavior

SKILL.md: '--json tab-list ... returns {status, command, output:{tabs:[..., "active"], count}}' - the active tab should be flagged; page-info help says it answers 'what page am I on?'; the human tab-list table should visually mark the active row.

#### Actual Behavior

tab-list --json reports "active": false for every tab immediately after tab-select (verified with 3 tabs); the tab-list table has no active indicator; page-info prints all pages ('Page 0..N') with no marker for the current one. After several operations a user/script cannot determine the active tab.

#### Root Cause Analysis

The CLI parses the tab 'active' field with .unwrap_or(false) (cli/browser4-cli/src/main.rs:850) and the backend browser_tabs tool evidently does not populate it (or emits false). page-info intentionally returns all pages (per its help) but the renderer never marks the current index.

#### Code Pointer

`cli/browser4-cli/src/main.rs:850 (TabInfo.active parse) and the backend browser_tabs tab serializer; page-info renderer in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Populate the active flag in the backend tab listing (the session's current/active target) and use it in both the table (e.g. a '*' marker) and JSON.
- Even without backend support, mark the tab whose GUID matches the session's current target in tab-list, and mark the current page in page-info output.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: attach.md references a 'page-url' command that does not exist

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 help page-url

#### Expected Behavior

The command referenced by attach.md ('Save the URL first (page-url) if you need to reopen it after re-attaching') exists, or the docs name a real command.

#### Actual Behavior

'Unknown command: page-url' followed by the generic help. No page-url command exists in the CLI source; page-info provides title/URL.

#### Root Cause Analysis

Documentation drift: attach.md:285 names a command that was never implemented or was renamed (page-info / page-url variants).

#### Code Pointer

`skills/browser4-cli/references/attach.md:285`

#### AI Suggested Improvement

- Replace 'page-url' with the actual command (page-info) or add a page-url alias.
- Add a docs lint/test that every backticked command in the skill references exists in the CLI command table.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Unreachable --endpoint fails with a raw transport error and mutates local state before failing

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 attach --endpoint http://localhost:1 --cdp chrome

#### Expected Behavior

A clear user-facing message such as 'Cannot reach Browser4 server at http://localhost:1 - is it running?', with exit != 0 and no local session-state changes for an attach that never happened.

#### Actual Behavior

Prints 'Replaced stale unnamed session 420e9b4b-... Creating new session.' and then 'Error: HTTP request failed [tool=attach_browser, endpoint=http://localhost:1/mcp/call-tool, timeout=600s]: error sending request for url (http://localhost:1/mcp/call-tool)' with exit 1. The 'Replaced stale unnamed session' line also appeared on every fresh attach in this run, naming an obsolete session ID (39f32e32) that had already been replaced twice earlier, indicating stale unnamed-session bookkeeping.

#### Root Cause Analysis

The attach handler replaces/creates local unnamed-session bookkeeping before contacting the server, so a failed attach has already mutated local state; the transport error from reqwest is embedded verbatim rather than translated into a server-reachability hint. The recurring 'Replaced stale unnamed session 39f32e32' suggests the local state can retain a session ID that no longer exists server-side.

#### Code Pointer

`cli/browser4-cli/src/main.rs (attach handler: local state replacement before call_tool; error rendering), cli/browser4-cli/src/http.rs (call_tool error formatting)`

#### AI Suggested Improvement

- Perform the server call first (or a cheap health probe) and only update local state after success.
- Wrap transport errors with a friendly 'Server not reachable at <url>' prefix plus the underlying cause.
- Investigate why the unnamed-session record reverted to a long-dead session ID instead of being cleared/replaced cleanly.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful with caveats - all 8 task steps completed against a real Chrome 154; the intended browser was only reached via the documented explicit-URL fallback (attach --cdp http://localhost:9222) because the channel form attached to a leftover managed instance, and close did not behave as documented (it terminated the user's browser).

**Success Rate:** 90% - every step produced a result, but 2 of 8 steps (channel attach to the intended browser; close preserving the browser) needed workarounds or contradicted documentation.

**Issues Found:** 7

**Major Blockers:** None that stopped the task. The two most consequential behaviors: attach --cdp chrome can silently target the wrong Chrome when a leftover Browser4-managed instance exists, and close on a CDP-attached session terminated every user-started Chrome used in this run instead of disconnecting (contradicting attach.md). Root cause of the misleading session-type reporting was pinned to persisted state: attach --cdp writes sessionKind=browser4Launched because the attach handler never sets SessionKind::CdpAttached and write_state recomputes the legacy flags from kind (state.rs:404-407).

**Most Confusing Aspects:** 1) attach --cdp chrome reporting port 1576 when the browser was deliberately started on 9222 - no hint that a leftover managed Chrome takes precedence. 2) -s <name> attach reporting 'Session opened' with an ID already owned by another session name, creating two names for one session. 3) No active-tab marker anywhere (tab-list table, tab-list --json active=false, page-info lists all pages). 4) close on an attached session being destructive despite docs promising the browser survives.

**Most Valuable Improvements:** 1) Fix the attach state write so kind=CdpAttached is persisted (restores correct list/close reporting and the 'never silently replaced' protection). 2) Make close non-destructive for CDP attaches (close only the bound tab, or keep a replacement tab alive) and align attach.md with reality. 3) When multiple attach candidates exist, list them and warn/prefer the explicitly-configured port instead of silently choosing a Browser4-managed leftover. 4) Populate the active-tab flag. 5) Document (or reject) named-attach aliasing.

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

#### Issue 1: close on a CDP-attached session terminates the user's browser, contradicting the docs

1) Start Chrome: chrome --remote-debugging-port=9222 --user-data-dir=<tmp> https://example.com https://example.org  2) ./b4w.ps1 attach --cdp http://localhost:9222  3) ./b4w.ps1 close  4) Check: curl http://localhost:9222/json/version and the chrome PID

#### Issue 2: attach --cdp never persists SessionKind::CdpAttached: list shows 'Browser4' and close prints the managed-browser message

1) ./b4w.ps1 attach --cdp http://localhost:9222  2) cat ~/.browser4/cli-state.json  3) ./b4w.ps1 list  4) ./b4w.ps1 close

#### Issue 3: attach --cdp chrome silently picks a leftover Browser4-managed Chrome instead of the browser started with --remote-debugging-port=9222

1) Have any leftover Browser4-managed Chrome running (started with --remote-debugging-port=0 in a PULSAR_CHROME profile). 2) Start Chrome with --remote-debugging-port=9222. 3) ./b4w.ps1 attach --cdp chrome

#### Issue 4: Named attach reuses an existing session ID: two session names alias one session, and closing either removes both

1) ./b4w.ps1 attach --cdp chrome  (creates unnamed session 420e9b4b)  2) ./b4w.ps1 -s endpoint-test attach --endpoint http://localhost:18182 --cdp chrome  3) ./b4w.ps1 list  4) ./b4w.ps1 -s endpoint-test close  5) ./b4w.ps1 list

#### Issue 5: No way to tell which tab is active: tab-list's active flag is always false and page-info marks nothing

1) Open several tabs. 2) ./b4w.ps1 tab-select 2  (prints 'Switched to tab 2'). 3) ./b4w.ps1 tab-list  4) ./b4w.ps1 tab-list --json  5) ./b4w.ps1 page-info

#### Issue 6: attach.md references a 'page-url' command that does not exist

./b4w.ps1 help page-url

#### Issue 7: Unreachable --endpoint fails with a raw transport error and mutates local state before failing

./b4w.ps1 attach --endpoint http://localhost:1 --cdp chrome

