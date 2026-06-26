---
title: "Attach — Connect to an Existing Browser"
description: "Reference for the attach command. Connect to an already-running Chrome or Edge instance via CDP instead of launching a new browser."
---

# Attach — Connect to an Existing Browser

Instead of launching a new browser, `attach` connects to an already-running Chrome or Edge instance via the Chrome DevTools Protocol (CDP).

## Quick Syntax

```bash
browser4-cli attach --cdp=<channel|url|port> [--endpoint=<server-url>] [-s=<name>]
```

- `--cdp` — channel name, CDP URL, WebSocket URL, bare port, or `host:port`
- `--endpoint` — (optional) Browser4 server URL; when used alone without `--cdp`, switches the CLI to that server
- `-s` — (optional) give the attached session a name

## Attach by Channel Name

The simplest mode. The target browser must have remote debugging enabled: go to `chrome://inspect/#remote-debugging` and check "Allow remote debugging for this browser instance".

```bash
browser4-cli attach --cdp=chrome
browser4-cli attach --cdp=chrome-canary
browser4-cli attach --cdp=msedge
browser4-cli attach --cdp=msedge-dev
```

Supported channels: `chrome`, `chrome-beta`, `chrome-dev`, `chrome-canary`, `msedge`, `msedge-beta`, `msedge-dev`, `msedge-canary`.

The CLI scans running processes, probes default debugging ports, and falls back to a port-range scan to find the browser automatically.

## Attach by CDP Endpoint URL

Connect to any Chromium-based browser with a known debugging port:

```bash
# Start Chrome with remote debugging
google-chrome --remote-debugging-port=9222

# Connect by URL
browser4-cli attach --cdp=http://localhost:9222
```

Also accepts WebSocket URLs (`ws://localhost:9222/devtools/...`), bare ports (`--cdp=9222`), and `host:port` (`--cdp=localhost:9222`). Works with Chrome, Edge, Electron apps, and cloud browser services (Browserbase, etc.).

## Attach to a Remote Browser4 Server

Point the CLI at a remote Browser4 instance:

```bash
browser4-cli attach --endpoint=http://browser4-server:8182 --cdp=chrome
```

When `--endpoint` is used alone (without `--cdp`), it switches the CLI to the remote server for subsequent commands.

## Named Sessions

Use `-s` to name the attached session for later targeting:

```bash
browser4-cli attach --cdp=chrome -s=debug-session
browser4-cli -s=debug-session snapshot
browser4-cli -s=debug-session screenshot --filename=state.png
```

## Workflows

### Connect to Your Running Chrome

```bash
# 1. In Chrome, go to chrome://inspect/#remote-debugging
#    and enable "Allow remote debugging for this browser instance"

# 2. Attach by channel name
browser4-cli attach --cdp=chrome

# 3. Interact with your existing tabs
browser4-cli snapshot
browser4-cli screenshot --filename=current-state.png

# 4. Save state for future headless sessions
browser4-cli state-save auth.json
```

### Debug a Remote Browser via SSH Tunnel

```bash
# On the remote machine: start Chrome with debugging
google-chrome --remote-debugging-port=9222

# On your machine: create an SSH tunnel
ssh -L 9222:localhost:9222 user@remote-host

# Attach and inspect
browser4-cli attach --cdp=http://localhost:9222
browser4-cli snapshot
browser4-cli screenshot --filename=remote-state.png
```

## Error Handling

- `attach` exits non-zero when it cannot find the target browser (no matching channel, no CDP endpoint listening on the given port).
- `attach` exits non-zero when `--cdp` is a channel name and no running browser with remote debugging enabled is found for that channel.
