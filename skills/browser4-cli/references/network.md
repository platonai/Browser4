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

Tracking follows the tab, not the first `network` command: the browser layer
enables the CDP `Network` domain during navigation setup, and the observer
registers its listeners when the tab's driver is created — so the tab's earlier
navigations are usually already in the buffer by the time the first
`network requests` runs. The buffer is bounded in memory (currently 5,000
entries; the oldest are evicted first) and `--clear` empties it.

## Commands

### `network requests`

List tracked requests. The default output is a **compact table**
(`status  method  type  url` plus the request id in brackets), so a busy page stays
readable; `--json` prints the CLI's JSON envelope with the full per-request payload
(headers and the raw timing object) in `output.result`. Narrow the list with
`--filter`/`--type`/`--status`, and use `network request <id>` for one entry in
detail (that is also the only place a response body appears — list entries never
carry bodies). Options:

| Option | Meaning |
|--------|---------|
| `--filter <text>` | Only requests whose URL contains this text (case-insensitive) |
| `--type <csv>` | CDP resource types, e.g. `xhr,fetch` (also `script`, `document`, `image`, …) |
| `--method <m>` | HTTP method, e.g. `POST` |
| `--status <s>` | `200`, `2xx`/`3xx`/`4xx`/`5xx`, a range like `400-499`, or comma-separated combos `2xx,4xx` |
| `--limit <n>` | Print only the newest `<n>` rows; the header still reports the total and the window |
| `--clear` | Drop all tracked requests first |
| `--json` | JSON envelope instead of the table; the buffer is in `output.result` |

```bash
browser4-cli network requests
browser4-cli network requests --filter api/users --status 2xx
browser4-cli network requests --type xhr,fetch --method POST --status 4xx,5xx
browser4-cli network requests --limit 20
browser4-cli --json network requests | jq '.output.result[] | select(.status >= 400) | .url'
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
finish while recording are captured; for requests that had already finished,
bodies are embedded only when still retrievable from the browser.

### `network har stop [path]`

Stop recording and emit the HAR 1.2 document. With a path (positional, matching
agent-browser's `har stop [path]`, or `--path`), writes the file
(pretty-printed) and prints a summary; without it, prints the full HAR JSON to
stdout (useful with `--json` for scripting).

Stopping with nothing recording is not an error: the recorder only flips off
(`recording: false` in the backend payload) and the document is still built
from every request tracked since the tab was created — so `0 entries` means
nothing was recorded, not that the command failed. The saved-file summary reads
`HAR saved to <path> (<n> entries, content mode: <mode>)`.

> **Note:** an explicit "no HAR recording is active" error or warning is expected
> here instead of silent success — the recording state is not shown in the CLI
> output today.

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

Remove routes. With a pattern, only the route(s) registered under that exact
pattern are removed; without one, every route is removed and Fetch
interception is disabled. The command echoes what it acted on:
`{"unrouted":"<pattern>"}`, or `{"unrouted":"all"}` for the bare form, plus
`removed`/`routes` counts.

> Removing a pattern that was never registered is rejected (exit non-zero, the
> message names the pattern) — a typo used to be acknowledged as if it had
> removed something. Use `network routes` to see what is actually registered.

```bash
browser4-cli network routes                   # list the active routes (read-only)
browser4-cli network unroute "**/api/users"   # remove one route
browser4-cli network unroute                  # remove all routes
```

## How It Works

- The browser layer enables CDP `Network` during navigation setup; the observer
  registers its listeners
  (`requestWillBeSent` / `responseReceived` / `loadingFinished` / `loadingFailed`,
  plus the `*ExtraInfo` header events) when the tab's driver is created — so
  tracking starts with the tab's first navigation. The first `network requests`,
  `network request`, or `har` command re-applies the enable, and `har start`
  adds the larger buffers described below.
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
  and `_error` (failure reason) fields. `time` and every `timings` value are in
  **milliseconds** (the HAR 1.2 unit); `time` is the entry's total duration, and
  is `-1` for an entry that never finished.

> **Note:** the emitted document currently omits `cache`, `request.cookies`,
> `request.queryString`, `response.redirectURL`, `pages[].title` and `_error`
> even though `HarBuilder.buildEntry()` populates them (the empty values are
> dropped downstream by the tool-result serialization's NON_EMPTY-style policy),
> so strict HAR consumers see a non-conformant file; the deviation is tracked.

> **Note:** `time` is derived from the tab's monotonic clock while the `timings`
> parts come from Chrome's `ResourceTiming` offsets, so the two can disagree for
> the same entry — a ~1000x mismatch was observed in the 2026-10-13 evaluation
> (validate with a HAR viewer if the numbers look off).

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

- **No requests tracked** — traffic is recorded from navigation time, not from
  the first `network` command: the browser layer enables the CDP `Network`
  domain during navigation setup, so the tab's earlier loads are normally in the
  buffer already (a fresh session plus one `open` is enough for the document
  request to show up). An empty result means the tab has not loaded anything, or
  the buffer was emptied with `--clear`; entries missing from a long session
  were evicted from the bounded buffer — re-run the interaction to record them
  again.
- **"Unknown network request id"** — request ids are per tab and per session;
  a closed tab or a new session invalidates them. Re-list with
  `network requests` to get current ids.
- **Missing response bodies** — a body is only captured when Chrome emits
  `Network.loadingFinished`; for a `fetch()` whose body the page never reads or
  drains, that event may never arrive, so the entry stays `finished: false`,
  `timings.receive` and HAR `time` are `-1`, and `network request <id>` shows an
  empty/absent `responseBody` even though the bytes were transferred.
  `Network.getResponseBody` is also best-effort: Chrome evicts bodies on
  navigation, so detail queries and HAR `text`/`all` modes may return
  metadata-only entries. Workaround — make the page read the body before you
  inspect it:

  ```bash
  browser4-cli eval --await "fetch('/api/x').then(r => r.text())"
  browser4-cli network requests --filter /api/x
  browser4-cli network request <requestId>
  ```

  Or record with `network har start --content text` and navigate while
  recording (`har start` → navigate → `har stop`) so bodies are drained on
  `loadingFinished`.
- **Route not taking effect** — routes are matched in registration order and
  respect `--resource-type` filters; a later route wins over an earlier one
  for the same URL, and requests rejected by type filters continue unchanged.
  There is no read-only listing command, so treat each registration as the
  record: `network route` prints `{"routed":"<pattern>"}` when it installs a
  route, and `network unroute "<pattern>"` removes only that route and leaves
  the others in place. Audit one route at a time instead of dropping them all:

  ```bash
  browser4-cli network route "**/api/users" --body '{"users":[]}'
  # -> {"routed":"**/api/users"}
  browser4-cli network unroute "**/api/users"
  # -> {"unrouted":"**/api/users"}   (other routes stay registered)
  ```

  Use bare `network unroute` only when you intend to drop every route.
- **HAR file looks wrong / empty** — the CLI only writes a file when the
  backend returned a real HAR document; a backend error is printed verbatim
  instead of being saved as a fake `.har`.

> **Note:** a read-only `network routes` listing exists (pattern, action, content
> type, resource-type filter) — inspect the active routes instead of removing them.
> `network route` also echoes the action it installed.

## Notes & Limitations

- Tracking is per tab and per session; a new tab starts with an empty buffer.
- Redirect hops are kept as separate entries (one per `requestWillBeSent`, like
  agent-browser), so a redirected request may appear multiple times with the
  same request id and different URLs; the detail query resolves to the latest
  hop. WebSocket frames are not tracked.
- `Network.getResponseBody` is best-effort: bodies can be evicted by Chrome on
  navigation, in which case entries keep headers/timing metadata only.
- A response body is captured only once Chrome delivers
  `Network.loadingFinished`. A `fetch()` whose body the page never reads or
  drains may never produce that event, so the entry keeps `finished: false`,
  `timings.receive: -1`, HAR `time: -1`, and an empty/absent `responseBody` in
  the detail output even though the bytes were transferred (see "Missing
  response bodies" under Errors & Recovery for the workaround).
- The `har` document is produced by the backend; `network har stop <path>`
  writes the file on the machine running the CLI.
