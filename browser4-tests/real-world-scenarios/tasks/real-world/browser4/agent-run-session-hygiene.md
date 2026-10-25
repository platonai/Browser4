# agent-run-session-hygiene

This scenario pins where `agent run` actually executes: the CLI's **current**
session — the browser attached through `attach --extension` / `attach --cdp`, or
the `-s <name>` slot — and never the backend's DEFAULT session. When it regressed
(before 2026-10-25) the task found no page in its own session and launched a
second, Browser4-owned browser on its first navigation, even though the CLI kept
reporting the attached Edge as the session.

The regression is invisible in the CLI's output: the task succeeds and prints a
result. It is only visible in (a) the backend log's `command_run … session=` id,
(b) the session list, and (c) the process table.

## Setup

- Microsoft Edge running with the Browser4 extension (id
  `jdcmdidbgjeebbhkoepjgifeibipfimi`) installed and enabled.
- A `browser4-cli` build from this checkout (the `resolve_agent_session_id`
  session-inheritance fix must be present).
- The backend log for the running server. Development checkout:
  `$env:TEMP\browser4\browser4-cli\tmp\cli\browser4-server-*-port<port>-*.log`.
  Production: the directory from `BROWSER4_SERVER_LOG_DIR` (default
  `RUNTIME_DIR/logs`). Resolve the newest one:

  ```powershell
  $log = Get-ChildItem "$env:TEMP\browser4" -Recurse -Filter 'browser4-server-*.log' `
      -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $log.FullName
  ```

- Process helper (same as `extension-attach-single-connection.md`):

  ```powershell
  function Get-BrowserFootprint {
      $p = Get-CimInstance Win32_Process -Filter "Name='chrome.exe' OR Name='msedge.exe'"
      [pscustomobject]@{
          Browser4Roots = @($p | Where-Object {
              $_.Name -eq 'chrome.exe' -and $_.CommandLine -match 'PULSAR_CHROME' -and
              $_.CommandLine -notmatch '--type='
          }).Count
          ChromeRoots = @($p | Where-Object {
              $_.Name -eq 'chrome.exe' -and $_.CommandLine -notmatch '--type='
          }).Count
          EdgeRoots = @($p | Where-Object {
              $_.Name -eq 'msedge.exe' -and $_.CommandLine -notmatch '--type='
          }).Count
      }
  }
  ```

- Use a cheap, deterministic task (`https://example.com`) so the run finishes in
  under ~2 minutes. If the run is not possible (no extension, no LLM key), record
  that as a usability finding instead of asserting.

## Steps

### A. Attach and record the expected session

1. If no extension session exists yet, run
   `browser4-cli attach --extension msedge`.
2. `browser4-cli list` — record the attached session id as `SESSION_ID`, and
   `browser4-cli page-info` — record the pre-task URL (usually `about:blank`).
3. Run `Get-BrowserFootprint` and record `Browser4Roots` / `EdgeRoots` as the
   baseline.
4. Note the newest backend log file (see Setup).

### B. Run the documented one-shot form — no `-s`

5. Submit the task exactly as a user would:
   `browser4-cli agent run "Open https://example.com in the current session and report the page title." --follow`
   - MUST complete successfully and report `Example Domain`.
   - Record the task id.

### C. The task ran in the attached session

6. Search the backend log for this task:
   - `tool=command_run … session=<SESSION_ID>` MUST be present.
   - `Submitting agent task <task-id> (session=<SESSION_ID>, …)` MUST be present.
   - A different session id, or no session id on `command_run`, means the task
     landed on the backend DEFAULT session — the regression this scenario exists
     for (it would have opened a second browser).
7. Count extension handshakes for this session:
   `Select-String -Path $log.FullName -Pattern "Extension handshake for session <SESSION_ID>" | Measure-Object`
   - Exactly one handshake per attach. A new handshake during the run means a
     second relay connection was opened.
8. `browser4-cli list` after completion — `SESSION_ID` MUST still be the session
   the task used, and no new session id may appear (compare with step 2).
9. Run `Get-BrowserFootprint` again: `Browser4Roots` MUST equal the baseline (the
   task did not launch a Browser4 browser) and `EdgeRoots` MUST be unchanged.
   `Get-NetTCPConnection -State Listen -LocalPort 9222` MUST still be empty.

### D. The session is usable and the page was restored

10. `browser4-cli page-info` — the URL MUST be the pre-task URL recorded in
    step 2. The agent runner restores the user's page after a task; a different
    page (or a failed read) means the task drove a different tab/browser.
11. `browser4-cli snapshot -i` (or `tab-list`) MUST still work on `SESSION_ID` —
    the attached session stayed healthy across the task.
12. Corroborating evidence (not the primary assertion): `browser4-cli agent result
    <task-id>` — the summary should refer to the extension/Edge connection or to
    content only reachable in that browser.

### E. The agent's own CLI saw the same session

13. In the task, the agent's `b4.run` calls execute a child `browser4-cli`. Ask
    the agent to run `status` first (e.g. a task like
    `"Run browser4-cli status, then open https://example.com in the current
    session and report the title"`), then verify the run's tool trace / result:
    `status` MUST report `Connection: Extension (Microsoft Edge …)`, which is only
    possible when the child CLI resolves the same checkout state namespace as the
    parent (`BROWSER4_CLI_INVOKE_DIR` passthrough).
14. If the agent attempted `attach --extension` on its own, it MUST have been
    refused (`An unnamed session already exists`); `browser4-cli list` MUST NOT
    show a new session for that attempt.

### F. Cleanup

15. Close only what this scenario created (`browser4-cli close` for the removed
    session, plus any named probe session). Leave the user's Edge running.

## Acceptance

- `command_run` and `Submitting agent task` in the backend log carry
  `SESSION_ID` — the same id `list` shows before and after the run.
- No new session id, no second extension handshake, and no new Browser4 browser
  root process appear while the task runs.
- The task succeeds from the attached browser, the pre-task page is restored, and
  the session is still usable afterwards.
- The agent's `b4.run status` inside the task reports the extension connection
  (not an empty/default session).
- Any new browser, new session id, missing `command_run session=`, or second
  handshake is a regression: record the exact command, the log lines, and the
  process/port evidence.
