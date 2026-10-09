---
title: "Attach — Connect to an Existing Browser"
description: "Reference for the attach command. Connect to an already-running Chrome or Edge instance via CDP instead of launching a new browser."
tier: procedure
---

# Attach — Connect to an Existing Browser

Instead of launching a new browser, `attach` connects to an already-running Chrome or Edge instance via the Chrome DevTools Protocol (CDP).

## Quick Start

```bash
# 1. Make the target browser debuggable — start it with a debug port.
#    A separate profile keeps it independent of your everyday browser:
chrome --remote-debugging-port=9222 --user-data-dir=/tmp/b4-attach-profile

# 2. Attach by channel name (or by endpoint, see below)
browser4-cli attach --cdp chrome

# 3. Interact with your existing tabs
browser4-cli snapshot
browser4-cli screenshot --filename current-state.png

# 4. Save state for future headless sessions
browser4-cli state-save auth.json
```

> **Chrome's built-in remote debugging works too.** `chrome://inspect/#remote-debugging` →
> *"Allow remote debugging for this browser instance"* (recent Chrome builds) publishes a
> browser-level WebSocket and answers **every `/json*` path with HTTP 404**. Browser4 attaches to it
> over that socket — `attach --cdp chrome` resolves the URL from `DevToolsActivePort` automatically,
> and you can always pass it explicitly:
>
> ```bash
> browser4-cli attach --cdp ws://127.0.0.1:9222/devtools/browser/<uuid>
> ```
>
> The path is the second line of the profile's `DevToolsActivePort` file. `attach --extension` remains
> available as an alternative for that browser.

## When to Use

Use **attach** to connect to an already-running browser instead of launching a new one — ideal for debugging live sessions, reusing authenticated browser state, or inspecting Electron/cloud browser instances. Use **goto** for normal automated sessions where no existing browser is needed.

## How It Works

`attach` resolves the target browser in layers, then verifies the CDP endpoint before binding the session. All subsequent commands (`snapshot`, `click`, `screenshot`, etc.) operate on the attached browser's tabs.

### Endpoint resolution (channel name)

When you pass a channel name (`attach --cdp chrome`), the CLI gathers every candidate endpoint and
probes them in order until one can host a page — a candidate that answers `/json/version` but exposes
no page target never wins over one that does:

1. **Channel default port** — the channel's conventional port (9222 for Chrome) is probed first. A
   browser you deliberately start with `chrome --remote-debugging-port=9222` expresses the clearest
   intent and always wins over a leftover Browser4-managed browser running on a random port. When
   nothing answers there, discovery continues below.
2. **Published by the browser itself** — `<user-data-dir>/DevToolsActivePort` (the port file Chrome
   writes in every remote-debugging mode, including the built-in `chrome://inspect` toggle, **with
   the browser-level WebSocket path on its second line**), followed by the `--remote-debugging-port=N`
   value from the browser's command line. The `--user-data-dir` of each running browser process is
   checked first, then the channel's conventional user data directory (a browser on its default
   profile carries no `--user-data-dir` on the command line). A stale file left behind by an exited
   browser is harmless: the port no longer answers, so the candidate is dropped.
3. **Process scan** — enumerates running processes whose command line contains `--remote-debugging-port=N`:
   - `N != 0` (e.g. `chrome --remote-debugging-port=9222`): use that port directly.
   - `N == 0` (Browser4-launched browsers use this — Chrome picks a free port at random): the requested value is not a usable endpoint, so the real port comes from the browser's own `DevToolsActivePort` (tier 2). Only when that file cannot be located does the CLI fall back to asking the process which ports it is listening on — Windows only (`Get-NetTCPConnection` keyed to the process id) — probing each listener for a page target. This is what makes Browser4-managed browsers (random debug port) discoverable via `attach --cdp chrome`.
     > **⚠ The listening-port tier is Windows-only.** Only *tier 2* above is platform-independent:
     > a browser started with `--remote-debugging-port=0` (which is what Browser4-managed browsers
     > use) is still resolved from its `<user-data-dir>/DevToolsActivePort`, because the CLI reads
     > that `--user-data-dir` from the running process — on Linux and macOS too. Only when that file
     > cannot be located (a default profile, or an install root the CLI does not know) does
     > resolution fall back to the default port and the 9222–9333 scan; there, pass an explicit
     > endpoint (`--cdp http://localhost:9222`, `--cdp host:port`, `--cdp 9222`) or start the target
     > browser with a fixed `--remote-debugging-port`.
4. **Port-range scan** — concurrently probes 9222–9333 for any CDP endpoint that exposes a page target, as a last resort.

> **Several Chrome instances at once?** A channel name (`attach --cdp chrome`) binds the first
> attachable candidate in the order above, which may be any running Chrome of that channel — it does
> not know which instance "belongs" to this terminal. The CLI prints the browser, its version and
> the resolved endpoint/URL after attaching, so verify the port. To target a specific instance
> deterministically, pass its endpoint explicitly (`--cdp http://localhost:9222`).

Resolution returns an `http://localhost:<port>` endpoint when a candidate lists page targets over
`/json`. For a **WebSocket-only** browser (built-in remote debugging) it returns the browser-level
WebSocket URL instead, which the CLI prints as it attaches.

When *no* candidate can host a page, the CLI fails with an error that names every endpoint it found —
`port 9222 (no /json)`, `port 51343 (Chrome/153.0.8010.48 · pages=0)` — instead of handing an
unattachable endpoint to the backend.

### Endpoint verification

Before the session is bound, the resolved endpoint is verified — over HTTP, or over the socket itself
when it is a browser-level WebSocket:

- **HTTP endpoints:** `GET /json/version` must succeed (the browser is reachable and its identity is captured) and `GET /json` must list at least one `page` target.
- **WebSocket endpoints:** the socket must answer `Browser.getVersion`, and `Target.getTargets` must list at least one page target — pages are then driven through `ws://<host>:<port>/devtools/page/<targetId>`.
- **HTTP 404 on `/json/version`** identifies Chrome's built-in remote debugging; the error says so and points at the browser-level WebSocket URL (or at `browser4-cli attach --extension`).

Both failures produce a loud error (naming the endpoint and how to fix it) instead of a silent success. After a successful attach, the CLI prints the target browser's real current page URL so you can confirm it is driving the browser you intended.

### Verify the Actual Browser After Attach

`attach` reports **which browser actually connected**, not just the channel you requested:

- **Extension attach (`--extension`)** prints `Extension connected and healthy!` followed by `Connected browser: Google Chrome 138`. Identity comes from the extension WebSocket handshake User-Agent — Chrome and Edge run the same extension id, and Edge advertises an `Edg/` UA token, so the User-Agent is the only reliable signal.
- **CDP attach (`--cdp`)** prints `Attached to Google Chrome 138 at http://localhost:9222`. Identity comes from the browser's `GET /json/version` response.

**Channel-mismatch warning:** when the actual browser family conflicts with the requested channel (e.g. `attach --extension msedge` landing on Chrome), the CLI warns immediately with ⚠:

```text
⚠  Requested channel was 'msedge', but the browser that actually connected is Google Chrome 138 — you may have attached to the WRONG browser, and login state on this browser likely differs.
   Run `close`, then re-run `browser4-cli attach --extension msedge` and approve the connection in the correct browser.
```

Always **check the printed browser** right after attaching — the silent failure mode is driving the wrong profile and later reporting "lost login state".

**Session listings also show the real browser:**

- `list` — the Connection column prefers the backend-reported actual browser over the locally requested channel and annotates conflicts, e.g. `Extension (requested msedge · actual Google Chrome 138.0.0.0)` or `CDP (requested msedge · actual Google Chrome 138)`; without a conflict it reads `Extension (Google Chrome 138)` / `CDP: http://localhost:9222 (Google Chrome 138)`.
- `status` — when a session is active it prints a current-session block: Name / Session ID / Status / Display / Connection / Next open.

**Disconnected attached sessions are never silently replaced.** If an attached session goes stale (extension relay dropped, browser closed), subsequent commands fail with an explicit error instead of quietly launching a fresh Browser4 browser (which would have no profile or login state):

```text
The attached browser session <session-id> is no longer reachable (it was NOT replaced with a new browser, so no login state was lost — the old browser may still be running).
Re-attach to the same browser explicitly: `browser4-cli attach --extension msedge`
Then verify the connection shows the browser you expect (use `browser4-cli list`).
```

Re-run the suggested attach command, then confirm with `list` that the connection shows the browser you expect.

## Patterns

### 1. Attach by Channel Name (Simplest)

```bash
browser4-cli attach --cdp chrome
browser4-cli attach --cdp chrome-canary
browser4-cli attach --cdp msedge
browser4-cli attach --cdp msedge-dev
```

Supported channels: `chrome`, `chrome-beta`, `chrome-dev`, `chrome-canary`, `msedge`, `msedge-beta`, `msedge-dev`, `msedge-canary`.

The target browser must have remote debugging enabled in one of two ways: start it with
`--remote-debugging-port=<port>` (the debug port is refused on Chrome's default profile, so pass a
separate `--user-data-dir` as well), or enable the built-in
`chrome://inspect/#remote-debugging` toggle, whose browser-level WebSocket is attached to directly —
see the Quick Start note.

### 2. Attach by CDP Endpoint URL

```bash
# Start Chrome with remote debugging
google-chrome --remote-debugging-port 9222

# Connect by URL
browser4-cli attach --cdp http://localhost:9222
```

Also accepts WebSocket URLs (`ws://localhost:9222/devtools/...`), bare ports (`--cdp 9222`), and `host:port` (`--cdp localhost:9222`). Works with Chrome, Edge, Electron apps, and cloud browser services.

> **A browser-level WebSocket URL is used as-is.** `ws://host:port/devtools/browser/<uuid>` attaches
> over that socket (pages are discovered with `Target.getTargets`), which is what Chrome's built-in
> remote debugging publishes. A *page-level* socket (`ws://…/devtools/page/<id>`) carries a single
> tab and is treated as a host:port hint: page targets are resolved there over `GET /json`.

### 3. Attach to a Remote Browser4 Server

```bash
browser4-cli attach --endpoint http://browser4-server:18182 --cdp chrome
```

When `--endpoint` is used alone (without `--cdp`), it switches the CLI to the remote server for subsequent commands.

### 4. Named Sessions

```bash
browser4-cli -s debug-session attach --cdp chrome   # -s is global: before the command
browser4-cli -s debug-session snapshot
browser4-cli -s debug-session screenshot --filename state.png
```

> **Important:** When the default (unnamed) session slot is already occupied (e.g., by a prior `open` or `attach`), `attach --extension` without `-s <name>` will fail with "An unnamed session already exists." Use `-s <name>` to create a named session instead, or `close` the existing unnamed session first.

> **Session-slot semantics:** each distinct `-s <name>` (and the unnamed default slot)
> resolves to its OWN backend session id; attaching the same browser under two names
> creates two independent sessions rather than aliasing one. Re-attaching an EXISTING
> name to an endpoint reuses that name's session (an idempotent re-attach to the same
> port keeps the existing driver; attaching to a different port rebinds it and logs a
> warning). Closing one name never tears down a different name's session.

### 5. Attach via Browser4 Extension

```bash
browser4-cli attach --extension
browser4-cli attach --extension chrome-canary
browser4-cli attach --extension msedge
```

Connect through the Browser4 Chrome Extension installed in the target browser. This is the easiest way to attach: no remote debugging flag or port configuration needed. The extension opens an about:blank tab and relays CDP commands over WebSocket.

**Supported channels:** `chrome` (default), `chrome-canary`, `msedge`, `msedge-dev`.

**Locally loaded (unpacked) extension:** an extension loaded with *Load unpacked* gets a path-derived ID instead of the Web Store ID, so the connect page needs that ID. The CLI resolves it automatically by scanning the user-data directories it can see — the `--user-data-dir` of every running Chrome/Edge process first (which covers portable builds and custom profiles), then the conventional per-user locations — for a locally loaded "Browser4 Extension". When that fails (the browser is not running, the extension is not recorded in its profile, several profiles loaded different folders), set the ID explicitly — persistently:

```bash
browser4-cli config set extension_id <id>   # 32 lowercase alphanumeric characters
browser4-cli config list                    # shows the effective value
```

or per shell with the `BROWSER4_EXTENSION_ID` environment variable, which wins over the config value. Read the id from `chrome://extensions` with Developer mode enabled. Resolution order is: `BROWSER4_EXTENSION_ID` → `config set extension_id` → auto-detected unpacked extension → published Web Store id.

**How it works:** The extension finds or opens a small WebSocket relay, and the CLI connects to it. All subsequent commands operate on the extension's active tab. This mode keeps your existing browser tabs and session intact — the browser is not launched by Browser4.

**Auto-approval token (skip the connection dialog):** The extension auto-generates a per-browser auth token (visible on the Connect and Status pages). Set the `BROWSER4_EXTENSION_TOKEN` environment variable to this value to bypass the manual approval dialog:

```bash
# macOS / Linux
export BROWSER4_EXTENSION_TOKEN=<token-from-extension>

# Windows PowerShell (persistent, new terminals only)
[Environment]::SetEnvironmentVariable("BROWSER4_EXTENSION_TOKEN", "<token-from-extension>", "User")

# Windows PowerShell (current terminal immediately, dies with the terminal)
$env:BROWSER4_EXTENSION_TOKEN = "<token-from-extension>"
```

When the env var is set, the CLI appends `&token=...` to the connect page URL — the extension validates the token against its stored copy and auto-approves the connection. If you regenerate the token from the extension UI, update your env var to match.

**Troubleshooting:**
- Navigating to `chrome://` internal pages (e.g., `chrome://version/`) may disconnect the extension WebSocket. If the session goes stale, run `close` first, then re-attach with `attach --extension`.
- When the default (unnamed) session slot is already occupied by another session, `attach --extension` requires `-s <name>` to create a named session.
- The extension creates a blank tab for the relay — "current page: about:blank" is normal for a freshly attached extension session.
- Use `--endpoint` together with `--extension` to connect through a remote Browser4 server.

### 6. Debug a Remote Browser via SSH Tunnel

```bash
# On the remote machine: start Chrome with debugging
google-chrome --remote-debugging-port 9222

# On your machine: create an SSH tunnel
ssh -L 9222:localhost:9222 user@remote-host

# Attach and inspect
browser4-cli attach --cdp http://localhost:9222
browser4-cli snapshot
browser4-cli screenshot --filename remote-state.png
```

## Flags

| Flag | Description |
|------|-------------|
| `--cdp <channel\|url\|port>` | Channel name, CDP URL, WebSocket URL, bare port, or `host:port` |
| `--endpoint <server-url>` | Browser4 server URL; when used alone, switches CLI to that server |
| `--extension [channel]` | Connect via Browser4 Chrome Extension; optionally specify channel (chrome, chrome-canary, msedge, etc.) |
| `-s <name>` | Name for the attached session (for `-s <name>` targeting later) |

## Errors & Recovery

| Symptom | Recovery |
|----------|---------|
| Cannot find target browser | Verify remote debugging is enabled; check the browser is running |
| No matching channel found | Verify channel name spelling; try a CDP URL or port instead |
| No CDP endpoint listening | Verify the port is correct and not blocked by a firewall |
| `CDP endpoint ... is not reachable` | Start the target browser with `--remote-debugging-port` and retry; the endpoint named in the error is not answering |
| `... is reachable but is not a Chrome DevTools discovery endpoint (GET /json/version → HTTP 404)` | Something answered HTTP but not on the CDP discovery path — any ordinary (non-CDP) web server returns the same 404. One possibility is Chrome's built-in remote debugging (`chrome://inspect/#remote-debugging`); if that is the target, pass its browser-level socket instead: `attach --cdp ws://127.0.0.1:<port>/devtools/browser/<uuid>` (second line of `DevToolsActivePort`), or use `attach --extension` |
| `Found a running chrome browser, but it serves no CDP HTTP discovery endpoint ... port 9222 (no /json)` | Built-in-mode endpoint whose socket URL could not be discovered automatically (it was found by the port sweep, not by `DevToolsActivePort`). Pass the browser-level WebSocket explicitly, or attach through the extension |
| `CDP endpoint ws://… is not usable over its browser-level WebSocket` | The socket URL is stale or the browser is gone. Re-read the profile's `DevToolsActivePort` (the file survives the browser), or start a browser with `--remote-debugging-port` |
| `... reachable but has no page targets` / `... it lists no page target` | Open a tab in the target browser, then retry attach — the browser has nothing to navigate yet; `attach --extension` also works |
| Attached, but the reported page looks wrong | The CLI prints the real current page URL after attach; if it does not match the window you expect, the endpoint pointed at a different browser — target the correct port |
| Attached to the wrong browser (requested msedge, Chrome connected) | The CLI prints `Connected browser:` / `Attached to …` plus a ⚠ warning when the actual family conflicts with the requested channel — run `close`, then re-run attach with the correct channel and approve it in the correct browser |
| Attached session went stale after a disconnect | The error states the session was NOT replaced with a new browser — re-run `attach --extension …` / `attach --cdp …` explicitly, then verify with `list` |
| Extension session goes stale | Run `close` first, then re-attach with `attach --extension`; avoid navigating to chrome:// internal pages |
| Extension not found / not installed | Install the Browser4 Chrome Extension in the target browser first |

## Close vs Disconnect

When you're done with an attached session, use `close` or its alias `disconnect`:

```bash
browser4-cli close       # or: browser4-cli disconnect
```

**Close/disconnect semantics by session type:**

| Session Type | Behavior |
|-------------|----------|
| Browser4-launched (via `open`) | `close` terminates the browser process |
| Extension-attached (via `attach --extension`) | `close` disconnects from the extension relay — Chrome keeps running. **The tab(s) Browser4 drove are removed** (`chrome.tabs.remove`); tabs you opened yourself and never touched through the session stay open |
| CDP-attached (via `attach --cdp`) | `close` disconnects from the remote debugging port — the browser process continues running. The tab(s) the session held drivers for are closed. To stop the headed Chrome from exiting when those were its last tabs, Browser4 first opens a standalone `about:blank` tab (never driven by the session), so a window always survives. The command then probes the endpoint and reports whether the browser is actually still running. |

> **Keep the page you were working on:** `close` on an attached session closes the
> tab the session was driving (the browser process itself survives, kept alive by a
> fresh `about:blank` tab). Save the URL first (`page-info`) if you need to reopen
> it after re-attaching.
>
> **Exceptions where the browser can still exit:** WebSocket-only endpoints (the
> built-in `chrome://inspect` mode, which answers 404 on `/json`) cannot be kept
> alive over HTTP, and a browser that was already closed obviously cannot survive —
> in those cases `close` says so instead of claiming the browser is still running.

The `disconnect` alias is available as a more accurate command name for attached sessions, but it's identical to `close` in behavior.
