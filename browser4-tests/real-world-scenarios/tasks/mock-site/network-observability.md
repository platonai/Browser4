# network-observability

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario covers the network observability and interception family: `network requests`,
`network request <id>`, `network route`, `network routes`, `network unroute`, `network har start`,
`network har stop`.

Fixture: `http://localhost:18080/b4/network-fixture.html`. On load it issues two fetches —
`/api/network-endpoint-ok.json` and `/api/network-endpoint-missing.json`. **MockSite serves neither
route**, so by default both fail (the `ok` fetch receives MockSite's 404 page, the `missing` fetch a
plain 404). That is the expected baseline here: a mocked **200** for the `ok` endpoint only appears
after `network route` installs one. Use an absolute artifact directory, e.g. `./target/network/`, and
clean it up at the end.

## Steps

### 1. Baseline traffic

1. Open a fresh session and navigate to `http://localhost:18080/b4/network-fixture.html`.
2. Read `#results` to see what the page itself observed (both fetches should report a failure status).
3. List the tracked requests; then filter by URL substring, by resource type (`--type xhr,fetch`), and by status (`--status 4xx`).
4. Take the `requestId` of the `network-endpoint-ok.json` entry and fetch its full detail.

### 2. HAR recording

5. Start a HAR recording that embeds text-like response bodies.
6. Navigate away (`http://localhost:18080/b4/frame-other.html`) and back to the network fixture, so new traffic flows while the recording is active.
7. Stop the recording into a `.har` file under `./target/network/` and inspect its structure.

### 3. Interception

8. Route `**/api/network-endpoint-ok.json` to a mock JSON body, then navigate away and back so the page re-issues its fetches.
9. List the active routes and check that the route you just installed is reported with its pattern, action and mock content type — listing must not disturb it.
10. Route `**/api/network-endpoint-missing.json` to abort, then navigate away and back again.
11. List the active routes again; both routes should now be reported, in registration order.
12. Remove the routes, then navigate away and back one final time.

### 4. Cleanup

13. List the active routes one last time and confirm no route is reported.
14. Clear the tracked request buffer and confirm it is empty.
15. Close the session and delete the artifacts created in `./target/network/`.

## Acceptance Criteria

1. **AC1 — tracking is enabled on demand:** the first `network requests` call prints a JSON array (it may be empty until a navigation happens); after the navigation the array is populated.
2. **AC2 — both fixture fetches are tracked:** the list contains an entry for `network-endpoint-ok` and one for `network-endpoint-missing`.
3. **AC3 — filters work:** `network requests --filter network-endpoint-ok` keeps only that URL; `network requests --status 4xx` returns the failing fetches and excludes any 2xx request.
4. **AC4 — request detail:** `network request <requestId>` prints JSON containing request/response headers, timing and a `responseBody` field.
5. **AC5 — HAR:** `network har start --content text` confirms recording; `network har stop <path>` writes a HAR 1.2 document whose `log.entries[]` contains entries for both fixture URLs, each with its `response.status`.
6. **AC6 — route with a mock body:** after `network route "**/api/network-endpoint-ok.json" --body '{"routed":true}' --content-type application/json` and a re-navigation, `#results` contains `ok: 200` **and** `{"routed":true}`.
7. **AC7 — route with abort:** after `network route "**/api/network-endpoint-missing.json" --abort` and a re-navigation, `#results` contains `missing: error`.
8. **AC8 — route inventory is read-only:** `network routes` lists the registered routes — pattern, action and mock content type, in registration order — and removing a route is still required to stop it: the page behaviour asserted in AC6/AC7 is unchanged immediately after a `network routes` call, and the listing reports both routes once both are installed. After `network unroute`, `network routes` reports no active routes instead of failing.
9. **AC9 — unroute:** after `network unroute`, a re-navigation restores the unrouted response (`missing: 404`).
10. **AC10 — clear:** `network requests --clear` empties the buffer — the next `network requests` no longer mentions the fixture URLs.
11. **AC11 — no silent success:** every route/unroute reports what it changed, naming the URL pattern, and `network routes` never mutates the set it reports; a command that appears to succeed without changing the observed page behavior must be recorded as a finding with the exact command and output.

## What to evaluate while doing this

- Is the `**` glob syntax of `network route` explained where a first-time user would look (`network route --help`, the help index, the skill reference)?
- Does `network requests` explain why the buffer may be empty on the very first call (tracking starts on first use)?
- Does `network routes` make it obvious that inspecting the active routes is read-only, so a first-time user does not `network unroute` just to find out what is registered?
- Are the HAR artifact path and the "recording" state discoverable from the command output alone?
