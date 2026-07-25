---
title: "Attach — Connect to an Existing Browser"
description: "Reference for the attach command. Connect to an already-running Chrome or Edge instance via CDP instead of launching a new browser."
tier: procedure
---

# Attach — Connect to an Existing Browser

Instead of launching a new browser, `attach` connects to an already-running Chrome or Edge instance via the Chrome DevTools Protocol (CDP).

## Quick Start

```bash
# 1. In Chrome, go to chrome://inspect/#remote-debugging
#    and enable "Allow remote debugging for this browser instance"

# 2. Attach by channel name
browser4-cli attach --cdp chrome

# 3. Interact with your existing tabs
browser4-cli snapshot
browser4-cli screenshot --filename current-state.png

# 4. Save state for future headless sessions
browser4-cli state-save auth.json
```

## When to Use

Use **attach** to connect to an already-running browser instead of launching a new one — ideal for debugging live sessions, reusing authenticated browser state, or inspecting Electron/cloud browser instances. Use **goto** for normal automated sessions where no existing browser is needed.

## How It Works

`attach` scans running processes, probes default debugging ports, and falls back to a port-range scan to find a Chromium-based browser with remote debugging enabled. Once connected, all subsequent commands (`snapshot`, `click`, `screenshot`, etc.) operate on the attached browser's tabs.

## Patterns

### 1. Attach by Channel Name (Simplest)

```bash
browser4-cli attach --cdp chrome
browser4-cli attach --cdp chrome-canary
browser4-cli attach --cdp msedge
browser4-cli attach --cdp msedge-dev
```

Supported channels: `chrome`, `chrome-beta`, `chrome-dev`, `chrome-canary`, `msedge`, `msedge-beta`, `msedge-dev`, `msedge-canary`.

The target browser must have remote debugging enabled: go to `chrome://inspect/#remote-debugging` and check "Allow remote debugging for this browser instance".

### 2. Attach by CDP Endpoint URL

```bash
# Start Chrome with remote debugging
google-chrome --remote-debugging-port 9222

# Connect by URL
browser4-cli attach --cdp http://localhost:9222
```

Also accepts WebSocket URLs (`ws://localhost:9222/devtools/...`), bare ports (`--cdp 9222`), and `host:port` (`--cdp localhost:9222`). Works with Chrome, Edge, Electron apps, and cloud browser services.

### 3. Attach to a Remote Browser4 Server

```bash
browser4-cli attach --endpoint http://browser4-server:8182 --cdp chrome
```

When `--endpoint` is used alone (without `--cdp`), it switches the CLI to the remote server for subsequent commands.

### 4. Named Sessions

```bash
browser4-cli attach --cdp chrome -s debug-session
browser4-cli -s debug-session snapshot
browser4-cli -s debug-session screenshot --filename state.png
```

> **Important:** When the default (unnamed) session slot is already occupied (e.g., by a prior `open` or `attach`), `attach --extension` without `-s <name>` will fail with "An unnamed session already exists." Use `-s <name>` to create a named session instead, or `close` the existing unnamed session first.

### 5. Attach via Browser4 Extension

```bash
browser4-cli attach --extension
browser4-cli attach --extension chrome-canary
browser4-cli attach --extension msedge
```

Connect through the Browser4 Chrome Extension installed in the target browser. This is the easiest way to attach: no remote debugging flag or port configuration needed. The extension opens an about:blank tab and relays CDP commands over WebSocket.

**Supported channels:** `chrome` (default), `chrome-canary`, `msedge`, `msedge-dev`.

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
| Extension-attached (via `attach --extension`) | `close` disconnects from the extension relay — your Chrome browser and its tabs remain untouched |
| CDP-attached (via `attach --cdp`) | `close` disconnects from the remote debugging port — the browser continues running |

The `disconnect` alias is available as a more accurate command name for attached sessions, but it's identical to `close` in behavior.
