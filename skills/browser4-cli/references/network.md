---
title: "Network Inspection & HAR"
description: "Use when you need to see what the page loaded — XHR/fetch calls, status codes, headers, response bodies — or record a HAR file: network requests, network request, network har start/stop."
tier: procedure
---

# Network Inspection & HAR

Inspect what a page actually loaded over the network and record a HAR 1.2
archive that Chrome DevTools (or any HAR viewer) can open.

## Quick Start

```bash
browser4-cli goto https://example.com
browser4-cli network requests                     # 1. LIST — every request the page made
browser4-cli network requests --filter api --status 2xx   # filter by URL / status
browser4-cli network request 1234.5               # 2. DETAIL — headers + response body of one request
browser4-cli network har start --content text     # 3. RECORD — capture traffic + text bodies
browser4-cli network har stop ./capture.har       # 4. SAVE — stop and write a .har file
```

## When to Use

- **Debugging API calls** — the page's XHR/fetch requests, their status, timing,
  and payloads. Agents use this to verify what a SPA actually returned.
- **Verifying loads** — confirm that scripts/styles/APIs loaded (or failed) with
  `--status 4xx` / `--status 5xx`.
- **Performance analysis** — inspect resource types, sizes, and timing, or export
  a HAR and open it in Chrome DevTools (`Network` tab → import).
- **Reproducing bugs** — a HAR file captures request/response pairs (headers,
  bodies, timings) for sharing with developers.

Network tracking is **opt-in and lazy**: the CDP `Network` domain is enabled on
the first `network`/`har` command, and requests are kept in a bounded in-memory
buffer (oldest evicted). `--clear` empties the buffer.

## Commands

### `network requests`

List tracked requests. Options:

| Option | Meaning |
|--------|---------|
| `--filter <text>` | Only requests whose URL contains this text (case-insensitive) |
| `--type <csv>` | CDP resource types, e.g. `xhr,fetch` (also `script`, `document`, `image`, …) |
| `--method <m>` | HTTP method, e.g. `POST` |
| `--status <s>` | `200`, `2xx`/`3xx`/`4xx`/`5xx`, a range like `400-499`, or comma-separated combos `2xx,4xx` |
| `--clear` | Drop all tracked requests first |

```bash
browser4-cli network requests
browser4-cli network requests --filter api/users --status 2xx
browser4-cli network requests --type xhr,fetch --method POST --status 4xx,5xx
browser4-cli network requests --clear
```

### `network request <requestId>`

Full detail of one request: request/response headers, status, timing, MIME type,
and the response body (fetched on demand; bodies over 1 MB are skipped).

```bash
browser4-cli network request 1234.5
```

### `network har start [--content <mode>]`

Start a HAR recording session. Content mode controls which response bodies are
embedded:

| Mode | Bodies embedded |
|------|-----------------|
| `none` (default) | none — smallest file, fastest recording |
| `text` | text-like MIME types only (text/*, json, xml, javascript, form data) |
| `all` | every body up to 2 MB; binary content base64-encoded |

Bodies are capped at 2 MB each and 64 MB total per recording. Requests that
finish while recording are captured; already-finished requests are kept as
metadata-only entries.

### `network har stop [path]`

Stop recording and emit the HAR 1.2 document. With `--path`, writes the file
(pretty-printed) and prints a summary; without it, prints the full HAR JSON to
stdout (useful with `--json` for scripting).

```bash
browser4-cli network har stop ./capture.har
browser4-cli --json network har stop   # HAR JSON on stdout
```

## How It Works

- The backend enables CDP `Network` on the tab and listens for
  `requestWillBeSent` / `responseReceived` / `loadingFinished` / `loadingFailed`
  (plus the `*ExtraInfo` header events).
- `network har start` arms body capture: on `loadingFinished`, response bodies
  are fetched via `Network.getResponseBody` before Chrome evicts them (e.g. on
  navigation) and embedded per the content mode.
- The HAR document follows the [HAR 1.2 spec](https://w3c.github.io/web-performance/specs/HAR/Overview.html):
  `log.version`, `creator`, one `page`, and one `entry` per request with
  `request`/`response`/`cache`/`timings`, `serverIPAddress`, `_resourceType`,
  and `_error` (failure reason) fields.

## Patterns

### Verify a failed API call

```bash
browser4-cli network requests --status 4xx,5xx
browser4-cli network request <requestId>   # error text + response body
```

### Record a session for DevTools

```bash
browser4-cli network har start --content all
browser4-cli goto https://example.com/login && browser4-cli fill ... && browser4-cli press Enter
browser4-cli network har stop ./session.har
# open chrome://net-export → "Import HAR" (or DevTools → Network → import)
```

### Watch requests during an interaction

```bash
browser4-cli network requests --clear
browser4-cli click "button[data-testid=submit]"
browser4-cli network requests --type xhr --method POST
```

## Notes & Limitations

- Tracking is per tab and per session; a new tab starts with an empty buffer.
- Redirect hops and websocket frames are not expanded into separate entries
  (the final request is recorded).
- `Network.getResponseBody` is best-effort: bodies can be evicted by Chrome on
  navigation, in which case entries keep headers/timing metadata only.
- The `har` document is produced by the backend; `network har stop --path`
  writes the file on the machine running the CLI.
