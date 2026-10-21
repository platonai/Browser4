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

## Evidence rules

Read a report — yours or a user's — with these rules, or the wrong conclusion is
one line away:

| Observation | Proves | Does **not** prove |
|---|---|---|
| `GET /json/version` → 200 | a CDP HTTP discovery surface exists (legacy mode) | that it has a page target |
| `GET /json` → `[]` | that endpoint lists no page | that the browser has no tabs (it may be the wrong port) |
| every `/json*` and `/devtools/browser/<uuid>` → 404 | no HTTP discovery on that port | that the WebSocket is unusable — a WS endpoint answers 404 to a non-upgrade GET |
| WS upgrade OK + `Target.getTargets` with a `page` entry | the endpoint is attachable | — |
| WS upgrade OK + `Target.getTargets` empty | the instance is alive without pages, or is not the instance you meant | — |

`DevToolsActivePort` (user-data dir root, not `Default/`) is `<port>\n/devtools/browser/<uuid>`,
and it survives the browser: treat a port read from it as a **candidate**, never as
a live endpoint until it answers.

For the full narrative — endpoint topology of both Chrome remote-debugging modes,
why the resolver must be page-target aware, the Windows pitfalls and the fix that
shipped — see [cdp-attach-websocket-4.13.x.md](../cdp-attach-websocket-4.13.x.md) (Chinese).

