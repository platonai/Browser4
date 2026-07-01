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
browser4-cli attach --cdp=chrome

# 3. Interact with your existing tabs
browser4-cli snapshot
browser4-cli screenshot --filename=current-state.png

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
browser4-cli attach --cdp=chrome
browser4-cli attach --cdp=chrome-canary
browser4-cli attach --cdp=msedge
browser4-cli attach --cdp=msedge-dev
```

Supported channels: `chrome`, `chrome-beta`, `chrome-dev`, `chrome-canary`, `msedge`, `msedge-beta`, `msedge-dev`, `msedge-canary`.

The target browser must have remote debugging enabled: go to `chrome://inspect/#remote-debugging` and check "Allow remote debugging for this browser instance".

### 2. Attach by CDP Endpoint URL

```bash
# Start Chrome with remote debugging
google-chrome --remote-debugging-port=9222

# Connect by URL
browser4-cli attach --cdp=http://localhost:9222
```

Also accepts WebSocket URLs (`ws://localhost:9222/devtools/...`), bare ports (`--cdp=9222`), and `host:port` (`--cdp=localhost:9222`). Works with Chrome, Edge, Electron apps, and cloud browser services.

### 3. Attach to a Remote Browser4 Server

```bash
browser4-cli attach --endpoint=http://browser4-server:8182 --cdp=chrome
```

When `--endpoint` is used alone (without `--cdp`), it switches the CLI to the remote server for subsequent commands.

### 4. Named Sessions

```bash
browser4-cli attach --cdp=chrome -s=debug-session
browser4-cli -s=debug-session snapshot
browser4-cli -s=debug-session screenshot --filename=state.png
```

### 5. Debug a Remote Browser via SSH Tunnel

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

## Flags

| Flag | Description |
|------|-------------|
| `--cdp=<channel\|url\|port>` | Channel name, CDP URL, WebSocket URL, bare port, or `host:port` |
| `--endpoint=<server-url>` | Browser4 server URL; when used alone, switches CLI to that server |
| `-s=<name>` | Name for the attached session (for `-s=<name>` targeting later) |

## Errors & Recovery

| Symptom | Recovery |
|----------|---------|
| Cannot find target browser | Verify remote debugging is enabled; check the browser is running |
| No matching channel found | Verify channel name spelling; try a CDP URL or port instead |
| No CDP endpoint listening | Verify the port is correct and not blocked by a firewall |
