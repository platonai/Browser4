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

`attach` resolves the target browser in layers, then verifies the CDP endpoint before binding the session. All subsequent commands (`snapshot`, `click`, `screenshot`, `agent run`, etc.) operate on the attached browser's tabs — `agent run` inherits the session, so the task drives the attached browser instead of starting another one.

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

**The endpoint is verified again after binding.** A debugging port can change hands between the
probe and the actual attach (another Chrome instance grabs it, or the first one exits), which used to
mean the session silently drove a different browser. The bound browser's product is therefore re-read
and compared with what the probe reported: a mismatch fails loudly, naming both products, the port and
every page target that was observed. Attach failures carry dedicated **non-retryable** codes, so a
retry loop cannot spin on them:

| Code | HTTP | Meaning |
|---|---|---|
| `ENDPOINT_UNREACHABLE` | 502 | nothing answered at the endpoint (connection refused/reset) |
| `ENDPOINT_NOT_CDP` | 502 | something answered, but it is not a CDP/DevTools endpoint |
| `ENDPOINT_NO_PAGE_TARGETS` | 409 | a CDP endpoint with no `page` target to drive |
| `ENDPOINT_WRONG_BROWSER` | 409 | the endpoint answered as a different browser family than the requested `channel` (e.g. `--cdp msedge` → Chrome); nothing was bound |

**An attached session never launches a browser of its own.** While the attached browser is not
connected — the extension has not approved yet, its WebSocket dropped, or the CDP endpoint died — a
page tool fails with `SESSION_UNHEALTHY` and the re-attach command instead of starting a second
Chrome next to yours. Browser launch is lazy anyway: creating a session (including the agent session
behind `agent run`) opens no browser until a tool actually needs a page, so `agent run` against an
attached browser never adds a window of its own.

**Multi-instance hazard — attaching to a browser another session already drives.** Attaching to a
browser that a live Browser4 session owns is allowed (it is the documented workflow), but it is
reported rather than silent: a warning names the owning session, the attach response carries
`ownedBySessionId`, and `list` shows it on the row. Two sessions driving one browser share its tabs
and profile, so closing one must not be expected to shut the other's browser down.

The attach response is JSON, so scripts can branch on the observed facts instead of parsing prose:
`{sessionId, browser, browserFamily, browserVersion, endpoint, port, pageTargets, pageTargetUrls,
ownedBySessionId, reboundFromPort, warning?}`.

### Verify the Actual Browser After Attach

`attach` reports **which browser actually connected**, not just the channel you requested:

- **Extension attach (`--extension`)** prints `Extension connected and healthy!` followed by `Connected browser: Google Chrome 138`. Identity comes from the extension WebSocket handshake User-Agent — Chrome and Edge run the same extension id, and Edge advertises an `Edg/` UA token, so the User-Agent is the only reliable signal.
- **CDP attach (`--cdp`)** prints `Attached to Google Chrome 138 at http://localhost:9222`. Identity comes from the browser's `GET /json/version` response.

**Channel identity is enforced, not just warned about.** When an explicitly requested
channel (`--cdp msedge`, `--extension msedge`) resolves to a browser of a different
family, the attach **fails**.

For CDP the CLI sends the channel to the backend, which compares it with the product the
endpoint reports **before binding anything** — no session, no driver, no claimed tab:

```text
Error: [ENDPOINT_WRONG_BROWSER] attach_browser failed: attach refused: channel 'msedge' was
requested, but endpoint http://localhost:9222 reports Google Chrome (Chrome/155.0.8059.79) — a
different browser family. Chrome and Edge share the conventional debugging port, so a channel
name can resolve the wrong browser. Name the channel you meant (`--cdp msedge` / `--cdp chrome`),
or pass that browser's exact endpoint so the wrong instance cannot be picked.
```

For the extension transport the family is only knowable after the relay handshake, so the
session has already been created when the mismatch is detected — it is then disconnected
and forgotten:

```text
Error: Attached to the WRONG browser: the requested channel 'msedge' (extension)
resolved to a browser that reports Google Chrome 138 (family: chrome).
The session was disconnected and its local record removed, so no command will drive that browser.
The connect page was opened in, or answered by, Google Chrome 138. Install and enable the
Browser4 Extension in Microsoft Edge, then re-run `browser4-cli attach --extension msedge`.
```

Why a hard failure rather than a warning: Chrome and Edge share the conventional debug
port 9222, and both run the same Web Store extension id, so a wrong-family bind looks
exactly like a working one until the session starts reporting missing login state. After a
refused attach no command can silently drive the wrong browser; the external browser keeps
running, and re-running the same attach after installing the extension in the right browser
(or naming the right channel) is the fix — no `close` needed.

With an explicit endpoint (`--cdp http://localhost:9222`, `--cdp 9222`, a WebSocket URL)
there is no channel to compare against: `Attached to …` names the product that answered,
and a Brave/Opera/Chromium fork on that endpoint is what you asked for.

**Which browser opens the extension connect page.** `attach --extension <channel>` opens
the `chrome-extension://…/connect.html` page **only in that channel's own browser** — the
CLI never falls back to the other family, and never hands the URL to the OS default
handler, when a channel was requested. It also scans the profiles of that family first
when resolving a locally loaded (unpacked) extension id, so Chrome's dev-mode id is never
used to open an Edge connect page. If the requested browser is not installed, the CLI
says so and prints the URL to open by hand:

```text
⚠  Could not launch Microsoft Edge — open this URL in Microsoft Edge to authorize the connection:
  chrome-extension://<id>/connect.html?mcpRelayUrl=...
```

A bare `attach --extension` (no channel) deliberately uses Chrome first, then Edge, then
the OS default browser — nothing was requested, so the extension that answers decides.
The channel recorded for the session is then the **actual** family, not a guess.

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

**The error says WHICH link died.** Two failures look alike and are not: the browser process is gone, or the browser is fine and only the tab/driver link died (the tab was closed, the page crashed, or another session detached it). The first reads *"… is no longer reachable — it was closed or crashed"* (`ENDPOINT_UNREACHABLE`); the second reads *"… is still running — only the tab this session was driving is gone"* (`ENDPOINT_NO_PAGE_TARGETS`). Never start hunting for a crashed browser on the second message: `curl http://localhost:<port>/json/version` answers 200 and the browser is untouched.

**Re-attach IS the recovery.** `attach` rebuilds an unhealthy bound driver on the browser it binds, so re-running the same attach command restores a session whose driven tab was lost — no `close` needed first. If the post-attach page read still fails, the browser itself is gone (or the port now answers as a different browser) and the failure says so.

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
browser4-cli attach --endpoint http://browser4-server:18182
browser4-cli attach --endpoint http://browser4-server:18182 --cdp http://browser-host:9222
```

When `--endpoint` is used alone (without `--cdp`), it switches the CLI to the remote server for subsequent commands and clears any attach markers left on the slot by a previous attach.

**A channel name and a remote `--endpoint` cannot be combined.** `--cdp chrome` resolves
a channel on **this** machine (running processes, their debug ports, their
`DevToolsActivePort` files) and the resolved `http://localhost:<port>` would then be looked
up on the *server*. The CLI refuses that combination:

```text
Error: --cdp chrome resolves a channel name on THIS machine, but --endpoint http://browser4-server:18182
names a server that runs elsewhere: the resolved localhost port would be looked up on the server, not here.
```

Pass the CDP URL that is reachable **from the server** instead. A loopback `--endpoint`
(`http://127.0.0.1:18182`, `http://localhost:18182`) is allowed, because then the CLI and
the server are the same machine and the resolution is valid.

**One transport per invocation.** `--cdp`, `--extension` and `--endpoint` name three
different transports and are mutually exclusive. `attach --extension msedge --cdp
http://localhost:9222` is refused rather than letting one flag silently win; the same
applies to `--extension` + `--endpoint` (the extension relay only talks to the local
server).

### 4. Named Sessions

```bash
browser4-cli -s debug-session attach --cdp chrome   # -s is global: before the command
browser4-cli -s debug-session snapshot
browser4-cli -s debug-session screenshot --filename state.png
```

> **Important:** When the default (unnamed) session slot is already occupied (e.g., by a prior `open` or `attach`), `attach --extension` without `-s <name>` will fail with "An unnamed session already exists." Use `-s <name>` to create a named session instead, or `close` the existing unnamed session first.
>
> The restriction is **not** extension-specific: `attach --cdp <channel|url>` and `attach --endpoint <url> --cdp …` are refused the same way while the unnamed slot drives a browser, because binding them would displace that session. The error names the pattern to use: `browser4-cli -s <name> attach --cdp chrome`.

> **Session-slot semantics:** each distinct `-s <name>` (and the unnamed default slot)
> resolves to its OWN backend session id; attaching the same browser under two names
> creates two independent sessions rather than aliasing one. Re-attaching an EXISTING
> name to an endpoint reuses that name's session (an idempotent re-attach to the same
> port keeps the existing driver when it is healthy, and rebuilds it when it is not;
> attaching to a different port rebinds it and logs a warning). Closing one name never
> tears down a different name's session: when another session still drives the same
> browser, `close` detaches only — it closes the tab that session drove, leaves the
> shared browser object, the other session's tab and driver, and the browser process
> exactly as they were. Closing the last session attached to that browser is the one
> that tears the shared browser object down. `close --json` reports which case it was:
> `detachedOnly` plus the `sharedWith` session ids.

### 5. Attach via Browser4 Extension

```bash
browser4-cli attach --extension
browser4-cli attach --extension chrome-canary
browser4-cli attach --extension msedge
```

Connect through the Browser4 Chrome Extension installed in the target browser. This is the easiest way to attach: no remote debugging flag or port configuration needed. The extension opens an about:blank tab and relays CDP commands over WebSocket.

**Supported channels:** `chrome` (default), `chrome-beta`, `chrome-dev`, `chrome-canary`, `msedge`, `msedge-beta`, `msedge-dev`, `msedge-canary`. An unknown channel name is refused up front (it would otherwise behave like a default chrome request).

**Picking the browser:** with a channel, the connect page is opened in **that channel's own
executable** (Edge Beta → Edge Beta, Chrome Canary → Chrome Canary); there is no
cross-family and no cross-channel fallback. If that browser is not installed, the CLI
prints the URL for a manual open in it. Without a channel the order is Chrome → Edge → the
OS default handler. Whatever browser answers is verified against the requested family (see
[Verify the Actual Browser After Attach](#verify-the-actual-browser-after-attach)): a
mismatch fails the attach and disconnects the session.

**Locally loaded (unpacked) extension:** an extension loaded with *Load unpacked* gets a path-derived ID instead of the Web Store ID, so the connect page needs that ID. The CLI resolves it automatically by scanning the user-data directories it can see — the `--user-data-dir` of every running browser process **of the requested family first** (which covers portable builds and custom profiles), then the conventional per-user locations — for a locally loaded "Browser4 Extension". The family order matters: an unpacked id registered in a Chrome profile is meaningless to Edge, so `attach --extension msedge` scans Edge profiles before Chrome's. When resolution fails (the browser is not running, the extension is not recorded in its profile, several profiles loaded different folders), set the ID explicitly — persistently:

```bash
browser4-cli config set extension_id <id>   # 32 lowercase alphanumeric characters
browser4-cli config list                    # shows the effective value
```

or per shell with the `BROWSER4_EXTENSION_ID` environment variable, which wins over the config value. Read the id from `chrome://extensions` with Developer mode enabled. Resolution order is: `BROWSER4_EXTENSION_ID` → `config set extension_id` → auto-detected unpacked extension → published Web Store id. Note that one configured id applies to every family: with different unpacked ids in Chrome and Edge, let auto-detection pick per browser rather than setting the config.

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
- A page-triggered `window.open` that the browser's popup blocker refuses (no user gesture) does not create a tab: the
  relay drops the refused `Page.windowOpen` event, so loading a page with a popup test no longer leaves a stray
  `about:blank` tab in the attached browser. Popups opened by a real user gesture are unaffected.
- `--extension` cannot be combined with `--endpoint`: the relay connects to the **local** Browser4 server. Switch servers with `attach --endpoint <url>` on its own, and run the extension attach against the local server.
- "Timed out waiting for the extension to connect" names the browser the extension must be installed in; `--extension msedge` waiting means the Edge copy, not the Chrome one.

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
| `--endpoint <server-url>` | Browser4 server URL; when used alone, switches CLI to that server. Mutually exclusive with `--extension`; a channel name with a non-loopback `--endpoint` is refused |
| `--extension [channel]` | Connect via Browser4 Chrome Extension; optionally specify channel (chrome, chrome-beta, chrome-dev, chrome-canary, msedge, msedge-beta, msedge-dev, msedge-canary). Mutually exclusive with `--cdp` and `--endpoint` |
| `-s <name>` | Name for the attached session (for `-s <name>` targeting later) |

## Errors & Recovery

| Symptom | Recovery |
|----------|---------|
| Cannot find target browser | Verify remote debugging is enabled; check the browser is running |
| No matching channel found | Verify channel name spelling; try a CDP URL or port instead |
| `Unknown browser channel for --extension: …` | The value must be one of the supported channels. A typo would otherwise behave like a default chrome request |
| `--cdp`/`--extension`/`--endpoint` "cannot be combined" | One transport per invocation — drop the extra flag |
| `--cdp <channel>` "resolves a channel name on THIS machine, but --endpoint …" | Pass the CDP URL reachable from the server (`--cdp http://browser-host:9222`), or use the server's loopback URL if it runs on this machine |
| No CDP endpoint listening | Verify the port is correct and not blocked by a firewall |
| `CDP endpoint ... is not reachable` | Start the target browser with `--remote-debugging-port` and retry; the endpoint named in the error is not answering |
| `... is reachable but is not a Chrome DevTools discovery endpoint (GET /json/version → HTTP 404)` | Something answered HTTP but not on the CDP discovery path — any ordinary (non-CDP) web server returns the same 404. One possibility is Chrome's built-in remote debugging (`chrome://inspect/#remote-debugging`); if that is the target, pass its browser-level socket instead: `attach --cdp ws://127.0.0.1:<port>/devtools/browser/<uuid>` (second line of `DevToolsActivePort`), or use `attach --extension` |
| `Found a running chrome browser, but it serves no CDP HTTP discovery endpoint ... port 9222 (no /json)` | Built-in-mode endpoint whose socket URL could not be discovered automatically (it was found by the port sweep, not by `DevToolsActivePort`). Pass the browser-level WebSocket explicitly, or attach through the extension |
| `CDP endpoint ws://… is not usable over its browser-level WebSocket` | The socket URL is stale or the browser is gone. Re-read the profile's `DevToolsActivePort` (the file survives the browser), or start a browser with `--remote-debugging-port` |
| `... reachable but has no page targets` / `... it lists no page target` | Open a tab in the target browser, then retry attach — the browser has nothing to navigate yet; `attach --extension` also works |
| Attached, but the reported page looks wrong | The CLI prints the real current page URL after attach; if it does not match the window you expect, the endpoint pointed at a different browser — target the correct port |
| `[ENDPOINT_WRONG_BROWSER] attach refused: channel 'msedge' …` | The channel resolved a browser of the other family (Chrome and Edge share port 9222). Nothing was bound. Name the channel you meant, or start the target browser with `--remote-debugging-port=<port>` and pass its exact endpoint |
| `Attached to the WRONG browser: the requested channel 'msedge' …` | Extension transport only: the browser that answered the relay differs from the requested channel. The session was disconnected and its local record removed; the browser keeps running. Install/enable the extension in the requested browser, then re-run the same attach |
| `Could not launch Microsoft Edge — open this URL in Microsoft Edge …` | The requested browser's executable was not found. Open the printed `chrome-extension://` URL in that browser by hand; the CLI never substitutes the other family |
| `Timed out waiting for the extension to connect` | Install/enable the Browser4 Extension in the browser named by the message, approve the connection there, and re-run |
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
