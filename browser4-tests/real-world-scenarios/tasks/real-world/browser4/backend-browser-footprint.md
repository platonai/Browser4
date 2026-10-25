# backend-browser-footprint

This scenario pins the browser footprint of a **default backend start** and of the
commands that follow it:

- MCP-over-HTTP is opt-in (default **off**): a plain backend start must not listen
  on `:8088` and must not launch the headless Chrome that endpoint used to acquire
  eagerly.
- A backend started for one session owns exactly **one** Browser4-launched
  browser. A second one at startup is the regression this scenario catches.
- The managed-process registry must point at the process that actually owns the
  listening port, so the next command reuses that backend instead of allocating
  the next dev port and starting a second backend (with its own browser).

Background: both failures were real and looked harmless — the extra MCP browser
was invisible (headless), and the second backend only showed up as a different
port in `status`. They are visible in the process table, the listening ports and
the backend log, which is what this scenario checks.

## Setup

- A `browser4-cli` build from this checkout (the default-off
  `mcp.http.enabled` and the port-owner pid fixes must be present).
- Windows PowerShell for the process/port checks. macOS/Linux equivalents:
  `ps -eo pid,args | grep PULSAR_CHROME`, `lsof -nP -iTCP:<port> -sTCP:LISTEN`.
- The backend log directory. Development checkout:
  `$env:TEMP\browser4\browser4-cli\tmp\cli\`. Production: `BROWSER4_SERVER_LOG_DIR`
  (default `RUNTIME_DIR/logs`).
- Stay on a machine where the user's own Chrome/Edge may run: the checks count
  **Browser4-managed** browsers (chrome roots whose command line carries
  `PULSAR_CHROME`), never the user's browsers.

Process helper:

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

## Steps

### A. Clean baseline

1. `browser4-cli close-all` (close any sessions from earlier runs), then
   `browser4-cli stop` (stop the backend).
2. Wait a few seconds, then confirm the baseline:
   - `Get-BrowserFootprint` → record `Browser4Roots` as `BASE_B4`. It is `0` on a
     machine running no other Browser4 workspace; another checkout's backend can
     contribute a browser, so assert **deltas** against this number rather than
     absolute counts.
   - `Get-NetTCPConnection -State Listen -LocalPort 8088` → empty.
   - No backend listening on the dev range (`8282`–`8290`) or `18182`.

### B. One session, one browser, no MCP endpoint

3. `browser4-cli open --headless https://example.com`
   - Record the `Server URL` from `browser4-cli status` as `SERVER_PORT`.
   - Record the newest backend log file from the log directory.
4. `Get-BrowserFootprint` MUST show `Browser4Roots = BASE_B4 + 1` — the session's
   own browser, and nothing else. `BASE_B4 + 2` means an extra (usually headless,
   MCP) browser was launched with the backend.
5. MCP-over-HTTP is off by default:
   - `Get-NetTCPConnection -State Listen -LocalPort 8088` MUST be empty.
   - `Test-NetConnection 127.0.0.1 -Port 8088 -InformationLevel Quiet` MUST return
     `False`.
   - The backend log MUST NOT contain `Creating MCP HTTP server session` or
     `MCP HTTP server listening`.
6. `browser4-cli page-info` MUST report `https://example.com/`, proving the
   browser that was counted in step 4 is the session's browser.

### C. The next command reuses the same backend

7. Record the port owner of `SERVER_PORT`:
   `(Get-NetTCPConnection -State Listen -LocalPort $SERVER_PORT | Select-Object -First 1).OwningProcess`
   Record it as `SERVER_PID`.
8. Run an unrelated command, e.g. `browser4-cli list` and then
   `browser4-cli page-info` again. After each:
   - The port owner of `SERVER_PORT` MUST still be `SERVER_PID`.
   - No additional port in `8282`–`8290` may be listening: a second backend here
     is the regression (a mis-recorded managed pid makes the CLI think the running
     server is a stranger and allocate the next port).
   - `Get-BrowserFootprint` MUST still show `Browser4Roots = BASE_B4 + 1`.
9. If the managed-process registry is discoverable
   (`~/.browser4/workspaces/<checkout>-<hash>/cli-managed-processes.json` in a
   checkout, `~/.browser4/cli-managed-processes.json` for an installed CLI), its
   entry for `SERVER_PORT` MUST carry `pid = SERVER_PID`. A dead pid there is the
   root cause of step 8's second backend.

### D. Opt-in MCP-over-HTTP still works (optional)

Skip this section when a second backend must not be started on the machine; a
skip is not a failure. Use an isolated state directory so the main workspace is
untouched:

10. In a new shell:
    ```powershell
    $env:BROWSER4_CLI_STATE_DIR = Join-Path $env:TEMP 'b4-mcp-optin'
    $env:BROWSER4_SERVER_OPTS = '-Dmcp.http.enabled=true'
    browser4-cli open --headless https://example.com
    ```
    - `Get-NetTCPConnection -State Listen -LocalPort 8088` MUST now be non-empty
      (or the backend log MUST contain `MCP HTTP server listening`).
    - `Get-BrowserFootprint` MUST increase by **two** relative to step 4: the
      opt-in backend's own session browser plus the MCP session's browser. (This
      is exactly the cost that justifies the default-off setting.)
11. Clean up the opt-in backend: `browser4-cli stop`, then clear
    `$env:BROWSER4_CLI_STATE_DIR` / `$env:BROWSER4_SERVER_OPTS`, and remove the
    temporary state directory. Confirm `Browser4Roots` is back to the step 4
    value.

### E. Cleanup

12. `browser4-cli close-all`, `browser4-cli stop`, and confirm
    `Get-BrowserFootprint` shows `Browser4Roots = 0` and no scenario port listens.

## Acceptance

- A default backend start listens on exactly one Browser4 port, does **not**
  listen on `:8088`, and logs no `Creating MCP HTTP server session`.
- With one open session, `Browser4Roots` is exactly `BASE_B4 + 1`; no second
  headless Chrome exists.
- Subsequent commands keep the same backend port and pid, open no additional dev
  port, and start no second browser.
- When the registry is inspected, its recorded pid equals the port owner.
- The optional opt-in check, when run, shows `:8088` listening with its own
  session browser plus one MCP browser, and cleans up completely.
- Any default `:8088` listener, a second Browser4-launched browser at startup, a
  new backend port after an unrelated command, or a registry pid that is not the
  port owner is a regression: record the exact commands, ports, pids and log
  lines.
