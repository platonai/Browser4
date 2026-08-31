---
title: "Network Inspection, HAR & Routing"
description: "Use when you need to see what the page loaded — XHR/fetch calls, status codes, headers, response bodies — or record a HAR file: network requests, network request, network har start/stop."
tier: procedure
---

# Network Inspection, HAR & Routing

Inspect what a page actually loaded over the network, record a HAR 1.2
archive that Chrome DevTools (or any HAR viewer) can open, and route
(mock/abort) matching requests.

## Quick Start

```bash
browser4-cli goto https://example.com
browser4-cli network requests                     # 1. LIST — every request the page made
browser4-cli network requests --filter api --status 2xx   # filter by URL / status
browser4-cli network request 1234.5               # 2. DETAIL — headers + response body of one request
browser4-cli network har start --content text     # 3. RECORD — capture traffic + text bodies
browser4-cli network har stop ./capture.har       # 4. SAVE — stop and write a .har file
browser4-cli network route "**/api/users" --body '{"users":[]}'   # 5. MOCK — answer matching requests
browser4-cli network unroute "**/api/users"       # 6. RESTORE — remove the route
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

Stop recording and emit the HAR 1.2 document. With a path (positional, matching
agent-browser's `har stop [path]`, or `--path`), writes the file
(pretty-printed) and prints a summary; without it, prints the full HAR JSON to
stdout (useful with `--json` for scripting).

```bash
browser4-cli network har stop ./capture.har
browser4-cli --json network har stop   # HAR JSON on stdout
```

### `network route <urlPattern> [--abort|--body <text>] [--content-type <mime>] [--resource-type <csv>]`

Intercept matching requests via the CDP `Fetch` domain. Provide **exactly one
action**: `--abort` or `--body` (combining both is rejected).

| Option | Meaning |
|--------|---------|
| `<urlPattern>` | `*` matches every request; plain text matches URLs **containing** it; `*` globs are supported (`**/api/users`) |
| `--abort` | Fail matching requests instead of sending them (`Fetch.failRequest`) |
| `--body <text>` | Answer matching requests with this mock response body (e.g. a JSON string) |
| `--content-type <mime>` | Content-Type header for the mock response (e.g. `application/json`) |
| `--resource-type <csv>` | Only intercept these CDP resource types, e.g. `xhr,fetch` (also `script`, `image`, …) |
| `--type <csv>` | Alias of `--resource-type` |

Routes are matched in registration order; requests that match no route (or
whose resource type filter rejects them) continue unchanged.

```bash
browser4-cli network route "**/api/users" --body '{"users":[]}' --content-type application/json
browser4-cli network route "**/analytics*" --abort --resource-type xhr,fetch
```

### `network unroute [urlPattern]`

Remove routes. With a pattern, only the route registered with that exact
pattern is removed; without one, every route is removed and Fetch
interception is disabled.

```bash
browser4-cli network unroute "**/api/users"   # remove one route
browser4-cli network unroute                  # remove all routes
```

## How It Works

- The backend enables CDP `Network` on the tab and listens for
  `requestWillBeSent` / `responseReceived` / `loadingFinished` / `loadingFailed`
  (plus the `*ExtraInfo` header events).
- `network har start` arms body capture: the Network domain is re-enabled with
  larger buffers (like agent-browser) and on `loadingFinished` response bodies
  are fetched via `Network.getResponseBody` before Chrome evicts them (e.g. on
  navigation) and embedded per the content mode.
- `network route` pushes the pattern into `Fetch.enable` (patterns are replaced
  on every change) and paused requests are resolved via
  `Fetch.failRequest` / `Fetch.fulfillRequest` / `Fetch.continueRequest`.
- The HAR document follows the [HAR 1.2 spec](https://w3c.github.io/web-performance/specs/HAR/Overview.html):
  `log.version`, `creator`, optional `log.browser` (from `Browser.getVersion`),
  one `page`, and one `entry` per request with
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

## Flags

Common options across the network commands:

| Flag | Commands | Meaning |
|------|----------|---------|
| `--filter <text>` | `network requests` | Only requests whose URL contains this text (case-insensitive) |
| `--type <csv>` | `network requests` | CDP resource types, e.g. `xhr,fetch` |
| `--method <m>` | `network requests` | HTTP method, e.g. `POST` |
| `--status <s>` | `network requests` | `200`, `2xx`, `400-499`, or comma-separated combos `2xx,4xx` |
| `--clear` | `network requests` | Drop all tracked requests first |
| `--content <mode>` | `network har start` | `none` (default), `text`, or `all` (binary base64) |
| `--path <file>` | `network har stop` | Output `.har` file path (alias of the positional path) |
| `--abort` | `network route` | Fail matching requests instead of sending them |
| `--body <text>` | `network route` | Answer matching requests with this mock response body |
| `--content-type <mime>` | `network route` | Content-Type header for the mock response |
| `--resource-type <csv>` | `network route` | Only intercept these CDP resource types (alias: `--type`) |

`network route` requires exactly one action: `--abort` or `--body`.

## Errors & Recovery

- **No requests tracked** — tracking is lazy: the CDP `Network` domain is
  enabled on the first `network`/`har` command, so traffic that happened
  before it is not recorded. Re-run the page interaction and check again.
- **"Unknown network request id"** — request ids are per tab and per session;
  a closed tab or a new session invalidates them. Re-list with
  `network requests` to get current ids.
- **Missing response bodies** — `Network.getResponseBody` is best-effort:
  Chrome evicts bodies on navigation, so detail queries and HAR `text`/`all`
  modes may return metadata-only entries. Navigate while recording
  (`network har start` → navigate → `network har stop`) to capture bodies.
- **Route not taking effect** — routes are matched in registration order and
  respect `--resource-type` filters; a later route wins over an earlier one
  for the same URL, and requests rejected by type filters continue unchanged.
  Check the active routes with `network unroute` (removes everything) or
  re-register the route.
- **HAR file looks wrong / empty** — the CLI only writes a file when the
  backend returned a real HAR document; a backend error is printed verbatim
  instead of being saved as a fake `.har`.

## Notes & Limitations

- Tracking is per tab and per session; a new tab starts with an empty buffer.
- Redirect hops are kept as separate entries (one per `requestWillBeSent`, like
  agent-browser), so a redirected request may appear multiple times with the
  same request id and different URLs; the detail query resolves to the latest
  hop. WebSocket frames are not tracked.
- `Network.getResponseBody` is best-effort: bodies can be evicted by Chrome on
  navigation, in which case entries keep headers/timing metadata only.
- The `har` document is produced by the backend; `network har stop <path>`
  writes the file on the machine running the CLI.
