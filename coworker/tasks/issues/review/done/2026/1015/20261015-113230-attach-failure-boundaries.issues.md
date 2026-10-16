# Issues: attach-failure-boundaries

> **Source:** `20261015-113230-attach-failure-boundaries.issues.json` | **Date:** 20261015-113230 | **Mode:** dev

## Scenario Background

### Task

**All four acceptance criteria passed.** The `attach --cdp` failure paths fail loudly and correctly; no false success was observed in any failure case.

| Step | Criterion | Result |
|---|---|---|
| A (2–3) | Unreachable endpoint fails loudly, no false success | **PASS** |
| B (5) | Reachable-but-page-less endpoint fails, no false success | **PASS** |
| C (7) | Successful attach reports the real current page | **PASS** |
| D (8–9) | `attach --cdp chrome` discovers random-port Browser4 browsers | **PASS** (with a significant caveat — see Issue 2) |

**Step A** — `./b4w.ps1 -s bad attach --cdp http://127.0.0.1:19999` → **exit 1**, **stdout 0 bytes**, stderr:

```
Error: [CDP_ERROR] attach_browser failed: CDP endpoint http://127.0.0.1:19999 is not
reachable: nothing is listening on that address (connection refused). Start the target
browser with --remote-debugging-port and retry attach.
```

This satisfies all three sub-requirements — names the endpoint, says it is not reachable, suggests how to fix/retry — and prints no page header and no "Session opened". I also verified the same loud failure across the other accepted endpoint forms (`--cdp 19999`, `--cdp 127.0.0.1:19999`, `--cdp ws://127.0.0.1:19999/devtools/browser/…`), and confirmed a **failed attach does not clobber an existing working session** (session `good2` stayed bound to its port afterwards).

**Step B** — I tested two reachable-but-unattachable endpoints. A mock CDP server that answers `/json/version` correctly but returns `[]` on `/json` (true zero-page-target case):

```
CDP endpoint http://127.0.0.1:19889 is reachable but has no page targets
(browser=Chrome/999.0.0.0 pages=0). Open a tab in the target browser, then retry attach
— or attach through the extension: browser4-cli attach --extension
```

and a plain HTTP server (404 on the discovery path):

```
CDP endpoint http://127.0.0.1:19888 is reachable but is not a Chrome DevTools discovery
endpoint (GET /json/version → HTTP 404). …
```

Both **exit 1 with empty stdout**.

**Step C** — Attaching to the real port produced the browser's genuine live page:

```
Attached to Google Chrome 155.0.8059.79 at http://localhost:41698
Session opened: good (6af112bb-d766-4962-9f0e-69ef49e5df2d)
Current page: https://example.com/
```

I proved the reported page is read from the live target rather than fabricated: I navigated the browser to `https://example.com/?probe=attach-real-page` and a *fresh* attach then reported exactly that probe URL. `page-info` on the attached session matched `GET /json` truth ("Example Domain" / `https://example.com/`).

**Step D** — `attach --cdp chrome` **succeeded** (exit 0, no "Could not find a running chrome browser"), resolving a random port that could not have come from the 9222–9333 sweep:

```
Attached to Google Chrome 155.0.8059.79 at http://localhost:35616
Session opened: ch (7b680036-7628-4f47-a9e7-28bd4d6d8531)
Current page: about:blank
```

The session was usable (`page-info` → `about:blank`, `tab-list` → 1 real tab). **But it bound a different browser than the one the scenario opened** — Issue 2 below.

---

### Execution Context

**Setup.** Confirmed repo root; ran `./b4w.ps1 help` and read `skills/browser4-cli/SKILL.md` plus `references/attach.md` and `--help attach` in full. Verified port 19999 free. The first command auto-started the backend ("Server ready in 6.6s") from the existing local runtime bundle — no manual server setup needed.

**Step A.** Ran the unreachable attach; captured stdout/stderr/exit code to the scratch dir. Also ran three additional endpoint forms (bare port, `host:port`, `ws://`) and a session-integrity test (failed attach into an already-bound session).

**Step B.** Wrote `mock_cdp_nopages.py` (a mock CDP endpoint answering `/json/version` validly but `/json` with `[]`) and started a plain `python -m http.server`, both bound to 127.0.0.1 in the scratch dir. Attached to each; both failed l...

(truncated — see 20261015-113230-attach-failure-boundaries.issues.json for the complete execution trace)

---

## Issues Found (4 issues)

### Issue 1: page-info / tab-list report "active": false for every tab of a Browser4-managed session

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s pi open --headless https://example.com
./b4w.ps1 -s pi page-info --json          # -> the only tab comes back "active": false

# now attach to the SAME browser and repeat
./b4w.ps1 -s piatt attach --cdp <that browser's port>
./b4w.ps1 -s piatt page-info --json       # -> "active": true

#### Expected Behavior

The current (and in this case only) tab of a managed session is flagged active, exactly as it is for an attached session, so that `active` is a usable machine-readable field and the human output is stable.

#### Actual Behavior

Same browser, same tab, same GUID (E25C461F52957C463E783FF6568D69A1): the managed session reports "active": false, the attached session reports "active": true. The human output also changes shape for the same command: the managed session prints the bare two-line form ("Title:/URL:") and loses the "▶ Active page:" header, while the attached session prints the header form. Any script that picks the current tab via `.pages[] | select(.active)` silently selects nothing on a managed session. `tab-list` renders from the same backend call, so it loses its "▶" index marker too.

#### Root Cause Analysis

handle_page_info() calls the MCP tool `browser_tabs` (action=list), which is served by SystemStatusController.sessionTabs(). That function computes `"active" to (wd.guid == frontGuid)` where `frontGuid = (browser.frontDriver as? AbstractWebDriver)?.guid` -- with NO fallback when frontGuid is null. The front driver is only set after bringToFront(), so a freshly opened single-tab managed session has frontDriver == null and every tab compares unequal. PagesController.getPages() has exactly this fallback (`val activeGuid = frontGuid ?: drivers.firstOrNull()?.guid`, line 209) together with a comment explaining the situation; SystemStatusController was never given the same treatment. The CLI itself is aware the field is unreliable -- main.rs:3480 says "When the active-tab field is available (see Issue 2)" and defensively falls back to tabs.first().

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/SystemStatusController.kt:sessionTabs() (the `"active" to (wd.guid == frontGuid)` mapping, ~line 316-327)`

#### AI Suggested Improvement

- Mirror the fallback already used in PagesController.getPages(): `val frontGuid = (browser.frontDriver as? AbstractWebDriver)?.guid ?: browser.listDrivers().filterIsInstance<AbstractWebDriver>().firstOrNull()?.guid`.
- Add a regression test that opens a single-tab managed session and asserts exactly one tab reports active=true, and that the same tab keeps active=true after `attach`.
- Consider making the CLI's page-info output shape independent of the flag (always print the "▶ Active page:" / tab-count form for a single tab) so the format does not silently vary by session type.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 2: attach --cdp chrome silently binds a different Browser4 browser than the one the session opened

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s managed open --headless https://example.com   # launches a Browser4 browser at a random port (observed 41698)
./b4w.ps1 -s ch attach --cdp chrome                          # exit 0, but binds 35616

#### Expected Behavior

Per scenario step 8, `attach --cdp chrome` finds the Browser4-managed browser -- i.e. the one this session/run opened -- so the user lands on the page they were working with (https://example.com/).

#### Actual Behavior

Exit 0 with "Attached to Google Chrome 155.0.8059.79 at http://localhost:35616" / "Current page: about:blank". Port 35616 belongs to a DIFFERENT Browser4 Chrome: PID 1248, user-data-dir C:\Users\pereg\.browser4\workspaces\Browser4-4.14-04e5248d\app-data\browser\chrome\default\PULSAR_CHROME, process start 19:01:01 -- a leftover from an earlier session, whereas this run's browser (PID 36892, profile cx.e0c50473-…) started at 19:24:52. The choice is deterministic: re-running bound 35616 again. Three Browser4 Chromes were live (35616, 35116, 41698), all launched with --remote-debugging-port=0. The command is not a false success -- it prints the endpoint and page -- but it silently binds a browser the user never opened, which is the practical failure mode for the documented 'attach to Browser4-managed browsers' workflow.

#### Root Cause Analysis

resolve_channel_to_endpoint() (daemon.rs) Stage 1 builds candidates from devtools_active_port_dirs(), which pushes each `--user-data-dir` parsed from the running processes' command lines IN OS ENUMERATION ORDER, then appends the channel's conventional roots. first_attachable_endpoint() returns the FIRST candidate that answers with at least one page target, so the winner is effectively the lowest/first-enumerated process (PID 1248 here). There is no preference for -- and no awareness of -- the calling CLI's own session, workspace, backend, or profile; a leftover browser from any earlier session outranks the browser this run just created. The code comment at Stage 0 shows the authors already reasoned about leftovers beating an explicit 9222 browser, but that guard does not cover a leftover beating the caller's own managed browser, and nothing warns when the bound endpoint is not the session's own browser.

#### Code Pointer

`cli/browser4-cli/src/daemon.rs:resolve_channel_to_endpoint() / devtools_active_port_dirs() (~line 5023 and 5279)`

#### AI Suggested Improvement

- Prefer the calling session's own browser: the backend knows the session's bound browser and its profile/context directory, so expose that (or the browser's PID) to the CLI and rank it ahead of all other candidates.
- When the chosen candidate is not the session's own browser, print an explicit warning naming the conflict (analogous to the existing channel-mismatch warning): e.g. "attached to a Browser4 browser that is NOT the one opened by session 'managed' (expected port 41698); pass --cdp http://localhost:41698 to target it".
- Rank candidates by recency/ownership rather than process-enumeration order (newest browser first is a strictly better default than lowest PID, and at minimum makes the outcome explainable).
- Surface the resolved endpoint and its owning profile in `list` so the user can audit which browser a session is really driving.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 3: attach failures surface internal tool jargon and a misclassified error code

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s bad attach --cdp http://127.0.0.1:19999
./b4w.ps1 --json -s bad attach --cdp http://127.0.0.1:19999

#### Expected Behavior

A user-facing error sentence. The message body is excellent and should stay; the surrounding wrapper should not leak internal tool names, and the error code should reflect the actual condition (nothing is listening) so machine consumers can branch on it.

#### Actual Behavior

Human output: `Error: [CDP_ERROR] attach_browser failed: CDP endpoint http://127.0.0.1:19999 is not reachable: …`. `attach_browser` is an internal MCP tool name and `[CDP_ERROR]` an internal error code; neither means anything to a first-time user. The code is also semantically wrong: ToolErrorCode.kt documents CDP_ERROR as "The browser refused the command; retrying may help.", yet this message explicitly contains "connection refused", which line 230 maps to UPSTREAM_ERROR -- retrying alone will NOT help here. Additionally the --json envelope flattens the tool-level code to the generic "code":"COMMAND_FAILED", so a machine consumer cannot distinguish "not reachable" from "has no page targets" without string-matching the free-text message.

#### Root Cause Analysis

ToolErrorCode.classify() is a first-match-wins `when` chain in which the broad `"cdp" in text || "devtools" in text || "browserprotocol" in text -> CDP_ERROR` (ToolErrorCode.kt:224) is evaluated BEFORE the specific `"connection refused" in text || "connection reset" in text -> UPSTREAM_ERROR` (line 230). Every attach error message names a "CDP endpoint", so it always matches the generic CDP rule first and is never reclassified. The CLI then renders the raw MCP error string verbatim, prefixing only "Error: " (format_cli_error_output, main.rs:27891), and maps the transport-level status to a single COMMAND_FAILED code.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/ToolErrorCode.kt:classify() (move/extend the connection-refusal rule above line 224); cli/browser4-cli/src/main.rs:format_cli_error_output()`

#### AI Suggested Improvement

- In classify(), evaluate the specific `"connection refused"` / `"connection reset"` rule before the generic `"cdp"` substring rule so a refused connection is UPSTREAM_ERROR, not CDP_ERROR.
- Strip or down-rank the internal wrapper before display: render `Error: CDP endpoint http://127.0.0.1:19999 is not reachable: …` and keep `[CDP_ERROR] attach_browser` only in verbose/debug output.
- Propagate a specific machine-readable code into the --json envelope (e.g. ENDPOINT_UNREACHABLE / NO_PAGE_TARGETS) instead of the catch-all COMMAND_FAILED, so callers can branch without parsing prose.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 4: --help attach omits the endpoint-verification contract, its failure modes, and the multi-instance hazard

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 --help attach

#### Expected Behavior

The help for a command whose whole purpose is binding an external browser should state that the endpoint is verified before the session is bound, name the failure modes a user will hit, and warn that a channel name may bind any running Chrome of that channel.

#### Actual Behavior

`--help attach` lists the three options and a few resolution notes (channel resolution, WebSocket URLs, --endpoint, extension caveats) but never mentions that the endpoint is verified before binding, never names the observable errors ("is not reachable", "reachable but has no page targets", "not a Chrome DevTools discovery endpoint"), and never warns that a channel name does not identify which instance it will bind. All of that exists only in references/attach.md (Endpoint verification, Errors & Recovery, and the "Several Chrome instances at once?" note), which the help reaches only via a "See also:" line. A user who attaches by channel name and lands on about:blank has no signal in `--help` that a different browser was chosen or that they can pin the target with an explicit endpoint.

#### Root Cause Analysis

The attach help text in the CLI's help tables documents flags and resolution mechanics but not the verification/outcome contract that the reference document is built around; the two have drifted, with the reference considerably ahead. The multi-instance caveat is placed in the reference as a note rather than in the help as a warning.

#### Code Pointer

`cli/browser4-cli/src/help.rs (the `attach` command help entry)`

#### AI Suggested Improvement

- Add a short "Outcome" block to `--help attach` mirroring references/attach.md: the endpoint is verified before the session is bound; unreachable, page-less, and non-CDP endpoints all fail loudly with non-zero exit.
- Promote the multi-instance caveat into the help: state plainly that `--cdp chrome` binds the first attachable candidate and may be a browser other than the one you just opened, and that `--cdp http://localhost:<port>` pins the instance.
- Include one failure example in the Examples section (e.g. the "reachable but has no page targets" error and its fix) so users can recognise the message without leaving the terminal.
- Have `--help attach` list `-s <name>` explicitly, since the reference documents it but the help's Options block does not.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful - all four acceptance criteria passed and every scenario step was executed. Step A: unreachable endpoint exits 1 with an error naming the endpoint, stating it is not reachable, and suggesting the fix; stdout was 0 bytes with no page header and no "Session opened" (also verified for the bare-port, host:port and ws:// forms). Step B: both a page-less mock CDP endpoint ("reachable but has no page targets (browser=Chrome/999.0.0.0 pages=0)") and a plain HTTP server ("not a Chrome DevTools discovery endpoint (GET /json/version -> HTTP 404)") failed with exit 1 and empty stdout. Step C: a successful attach reported the browser's real live page, confirmed by mutating the page to a probe URL and re-attaching (the new URL was reported) and by matching page-info against CDP /json truth. Step D: `attach --cdp chrome` succeeded by discovering a Browser4 browser on a random port (35616, outside the 9222-9333 sweep) with no "Could not find a running chrome browser", and the session was usable - but it bound a different browser than the one this run opened (Issue 2). Cleanup completed: all test sessions closed, mock servers stopped, this run's browser terminated; only two pre-existing stale sessions from 2026-10-14 remain.

**Success Rate:** 100% of the scenario's acceptance criteria passed. All 10 steps executed; 4 of 4 criteria met. The one caveat (Issue 2) is a quality-of-success concern in step D, not a failed step.

**Issues Found:** 4

**Major Blockers:** None. Every step completed on the first attempt against the locally-built backend, which auto-started from the source tree in 6.6s. No manual server setup, no retries, no port conflicts. All failure-path results were reproducible.

**Most Confusing Aspects:** 1) `attach --cdp chrome` reporting success with "Current page: about:blank" on a browser the run never opened - the endpoint it printed (35616) did not match the browser this run had just created (41698), and nothing in the output flagged that the bound browser belonged to a different, earlier session. The help text does not mention this hazard at all (Issue 4). 2) `page-info` printing two different output shapes for the same command and the same page depending on whether the session was launched by Browser4 or attached to it, with the underlying `active` field disagreeing between the two - which silently breaks any script that selects the current tab by `.active` (Issue 1). 3) Error messages opening with `Error: [CDP_ERROR] attach_browser failed:` - internal tool and error-code jargon in the one message a first-time user is most likely to read (Issue 3). 4) The distinction between `-s` session names and browser instances: the CLI treats "a session" and "the browser it drives" as separable in ways that are not obvious until you have several browsers running.

**Most Valuable Improvements:** 1) Fix the `active` flag in SystemStatusController.sessionTabs() by mirroring the frontDriver fallback already present in PagesController - it is a one-line fix that restores a machine-readable field, stabilises page-info/tab-list output, and removes a silent trap for scripts (Issue 1). 2) Make channel-name discovery session-aware, or at minimum warn loudly when the bound endpoint is not the session's own browser, and prefer newest-owned candidates over PID order (Issue 2). 3) Keep the excellent error message bodies but strip the internal `[CDP_ERROR] attach_browser failed:` wrapper and propagate a specific machine-readable code into the --json envelope (Issue 3). 4) Fold the verification contract, the concrete failure-mode messages and the multi-instance caveat from references/attach.md into `--help attach` (Issue 4). Overall the failure-path behaviour this scenario guards is genuinely solid: every unreachable, page-less and non-discoverable endpoint failed loudly with an actionable message and a non-zero exit, and failed attaches left no stale session records and did not disturb already-bound sessions.

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

#### Issue 1: page-info / tab-list report "active": false for every tab of a Browser4-managed session

./b4w.ps1 -s pi open --headless https://example.com
./b4w.ps1 -s pi page-info --json          # -> the only tab comes back "active": false

# now attach to the SAME browser and repeat
./b4w.ps1 -s piatt attach --cdp <that browser's port>
./b4w.ps1 -s piatt page-info --json       # -> "active": true

#### Issue 2: attach --cdp chrome silently binds a different Browser4 browser than the one the session opened

./b4w.ps1 -s managed open --headless https://example.com   # launches a Browser4 browser at a random port (observed 41698)
./b4w.ps1 -s ch attach --cdp chrome                          # exit 0, but binds 35616

#### Issue 3: attach failures surface internal tool jargon and a misclassified error code

./b4w.ps1 -s bad attach --cdp http://127.0.0.1:19999
./b4w.ps1 --json -s bad attach --cdp http://127.0.0.1:19999

#### Issue 4: --help attach omits the endpoint-verification contract, its failure modes, and the multi-instance hazard

./b4w.ps1 --help attach

