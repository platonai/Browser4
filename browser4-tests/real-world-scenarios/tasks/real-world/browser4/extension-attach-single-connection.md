# extension-attach-single-connection

This scenario pins the browser-connection footprint of `attach --extension`: the
extension must drive the browser the user is **already** running (Edge here), and
must not open another browser, expose a CDP port, or leave a second session
behind. It is the real-browser guard for the 2026-10-25 fixes that made the CLI,
`agent run` and the agent's `b4.run` subprocess all resolve the *same* attached
session.

Why this needs a dedicated scenario: "connected" and "opened a second browser"
look almost identical from inside the CLI — both print `Session ready`. Only the
process table, the listening ports and the session list tell them apart.

## Setup

- Microsoft Edge running with the Browser4 extension installed and enabled
  (Web Store id `jdcmdidbgjeebbhkoepjgifeibipfimi`). The extension must be able to
  open a connect page in that Edge.
- A `browser4-cli` build from this checkout (the session-inheritance and
  `BROWSER4_CLI_BIN` fixes must be present).
- Windows PowerShell is used for the process/port checks below. On macOS/Linux use
  the equivalents noted inline (`ps`, `lsof`).
- If Edge or the extension is unavailable, run the CLI steps anyway and record the
  outcome as a usability finding — do not assert the process checks.

Define the footprint helper once per shell and reuse it for every "before/after":

```powershell
function Get-BrowserFootprint {
    $p = Get-CimInstance Win32_Process -Filter "Name='chrome.exe' OR Name='msedge.exe'"
    [pscustomobject]@{
        # Browser4-managed browsers: chrome roots carrying the PULSAR_CHROME profile.
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

macOS/Linux equivalent:

```bash
ps -eo pid,args | grep -E 'chrome|chromium' | grep PULSAR_CHROME | grep -v -- '--type='
ps -eo pid,args | grep -E '(chrome|chromium|msedge)' | grep -v -- '--type='
lsof -nP -iTCP:9222 -sTCP:LISTEN
```

## Steps

### A. Baseline

1. `browser4-cli --version` — record the version.
2. `browser4-cli status` — record the `Server URL` (the backend port used below).
3. `browser4-cli list` — record every existing session id.
4. Run `Get-BrowserFootprint` and record the three counts plus
   `Get-NetTCPConnection -State Listen -LocalPort 9222,8088` as the baseline.
   A dev checkout normally starts at `Browser4Roots = 0`.

### B. Attach through the extension

5. `browser4-cli attach --extension msedge`
   - MUST print `Connected browser: Microsoft Edge …` and
     `Session ready: <id>` within the 60 s window.
   - MUST NOT time out, and MUST NOT open a second browser window.
6. `browser4-cli list` — the attached session MUST appear exactly once with
   `Display: Attached` and a `Connection` of `Extension (Microsoft Edge …)`.
   Record the session id as `SESSION_ID`. A `Connection` of `Browser4`/CDP, or a
   second row for the same browser, is a regression.
7. `browser4-cli tab-list` — MUST show only the extension session's own tab
   (usually `about:blank`), not the user's whole tab set.

### C. No browser was opened, no CDP port was exposed

8. Run `Get-BrowserFootprint` again:
   - `Browser4Roots` MUST equal the baseline — attaching through the extension
     must not launch a Browser4 browser. (The connect page is a **tab** inside the
     existing Edge; a new Edge/Chrome root process is the regression.)
   - `EdgeRoots` MUST equal the baseline.
9. `Get-NetTCPConnection -State Listen -LocalPort 9222` MUST be empty and
   `Test-NetConnection 127.0.0.1 -Port 9222 -InformationLevel Quiet` MUST return
   `False`: the extension relay never exposes a CDP endpoint.

### D. A second attach must be refused, not silently connected

10. Run `browser4-cli attach --extension msedge` again (still unnamed).
    - It MUST fail with a non-zero exit and name the existing session
      (`An unnamed session already exists: <SESSION_ID>`), and MUST NOT print
      `Session ready`.
    - `browser4-cli list` MUST still show exactly the same single `SESSION_ID`,
      and `Get-BrowserFootprint` MUST be unchanged.
    - A second session id, a new tab, or a new browser process here is the
      "one more browser" regression.

### E. Disconnect must leave the user's browser alone

11. `browser4-cli disconnect`
    - MUST report that the extension session was disconnected — it detaches; it
      never kills the external browser.
    - `Get-BrowserFootprint` MUST still show the same `EdgeRoots` (the user's Edge
      is untouched) and the same `Browser4Roots`.
    - `browser4-cli list` MUST NOT contain a live session for `SESSION_ID`.
12. The extension may keep the tab/group it created for the session open — record
    what remains; leftover tabs are not a failure, a leftover **connection** is.

### F. Cleanup

13. Close anything the scenario created:
    `browser4-cli close`, and if a named probe session exists,
    `browser4-cli -s <name> close`. Then confirm `browser4-cli list` has no live
    session from this scenario and the footprint matches the baseline.

## Acceptance

- Exactly one section: `attach --extension msedge` creates one session whose
  `Connection` is `Extension (Microsoft Edge …)`; there is never a second session
  for the same attach.
- `Browser4Roots`, `EdgeRoots` and the set of listening CDP ports are unchanged by
  the attach — no Browser4-launched browser, no `:9222`.
- A repeat unnamed attach is refused with the existing session id and changes
  neither the session list nor the process footprint.
- `disconnect` detaches and leaves the user's Edge running.
- Any new browser root process, any new session id after a repeat attach, or a
  listening `:9222` is a regression: record the exact command, output, and the
  process/port evidence.
