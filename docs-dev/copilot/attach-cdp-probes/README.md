# attach-cdp-probes

Evidence-gathering scripts for the `attach --cdp` endpoint discovery path.

| Script | Purpose |
|---|---|
| `probe-cdp-endpoints.ps1` | Lists every CDP candidate the CLI would consider (DevToolsActivePort, `--remote-debugging-port`, browser-process listeners), probes `/json/version` + `/json` over HTTP, then asks the **browser-level WebSocket** for its targets (`Target.getTargets`). |

## Why

`platonai/Browser4#611` reports that on recent Chrome the built-in
`chrome://inspect/#remote-debugging` toggle ("Allow remote debugging for this
browser instance") publishes an endpoint that answers `/json/version`, but lists
no page targets, so `attach --cdp` refuses it — while `attach --extension` works.

The open question is whether that endpoint is *unattachable* or merely
*WebSocket-only*: a plain HTTP `GET /devtools/browser/<uuid>` legitimately returns
404 on a WebSocket endpoint, so HTTP 404s alone prove nothing. This script settles
it by completing a real WebSocket upgrade and reading the target list.

## Usage

```powershell
pwsh ./probe-cdp-endpoints.ps1                     # discover candidates
pwsh ./probe-cdp-endpoints.ps1 -Port 9222          # probe one port
pwsh ./probe-cdp-endpoints.ps1 -Port 9222 -BrowserPath '/devtools/browser/<uuid>'
```

Exit codes: `0` — at least one page target is reachable over a browser-level
WebSocket; `1` — no candidate found at all; `2` — candidates exist but none
exposes a page target, over HTTP or WebSocket.

Read-only: the script issues discovery requests and `Target.getTargets` only.
Stale `DevToolsActivePort` files (left behind by an exited browser) show up as
"no HTTP answer" and can be ignored.
