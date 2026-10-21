# Issues: network-observability

> **Source:** `20261013-170327-network-observability.issues.json` | **Date:** 20261013-170327 | **Mode:** dev

## Scenario Background

### Task

Executed the full network observability/interception scenario against MockSite (`http://localhost:18080`) using `./b4w.ps1` in a dedicated session (`net-eval`), plus one auxiliary session (`net-eval2`) for a controlled root-cause experiment.

**Outcome — all 12 steps completed and all 10 acceptance criteria met:**

| AC | Result | Evidence |
|---|---|---|
| AC1 | ✅ JSON array printed on first call | `network requests` → 4-entry JSON array. Note: it was **already populated** (document + both fetches + favicon from the pre-command navigation) — see Issue 5 |
| AC2 | ✅ Both fixture fetches tracked | `230364.2` ok.json, `230364.3` missing.json |
| AC3 | ✅ Filters correct | `--filter network-endpoint-ok` → 1 entry; `--type xhr,fetch` → 2; `--status 4xx` → 3 (both fetches + favicon 404), excludes the 200 document |
| AC4 | ✅ Detail has headers/timing/`responseBody` | 24-key `request` object with `requestHeaders`/`responseHeaders`/`timing`, `responseBody` = the 404 JSON |
| AC5 | ✅ HAR 1.2 with both fixture URLs | `{"log":{"version":"1.2",...,8 entries}}`, statuses present per entry |
| AC6 | ✅ Mock route | `#results` → `ok: 200 {"routed":true}` |
| AC7 | ✅ Abort route | `#results` → `missing: error TypeError: Failed to fetch` |
| AC8 | ✅ Unroute | targeted unroute restored `ok: 404` while abort stayed; `unroute` (all) restored `missing: 404` |
| AC9 | ✅ Clear | `network requests --clear` → `[]`; next call → `[]` |
| AC10 | ✅/⚠ Routes/unroutes name the pattern (`{"routed":"…"}`, `{"unrouted":"…"}`) — **but three "silent success" behaviors were found and recorded** (Issues 3, 4, and the missing action in route output, Issue 8) |

Baseline matched expectations: `#results` initially `ok: 404 {…MockSite 404 JSON…} missing: 404`.

### Execution Context

**Key Commands:**

**Workarounds/decisions:**
- **Artifact location:** the scenario suggested `./target/network/`; per the session rule that all scratch files live in the run directory, artifacts were written to `.test-sessions/20261013T0154334271610Z/network/` (absolute path passed to `network har stop`), analyzed, then **deleted at the end** (verified: no `.har` files remain). Evidence logs are kept in `.test-sessions/20261013T0154334271610Z/netlogs/`.
- A controlled A/B experiment (auxiliary session `net-eval2`) isolated the root cause of missing response bodies: two identical fetches differing only in whether the JS reads the body (`r.text()` vs `r.status`) → `finished:true` vs `finished:false`.
- Source was inspected to confirm/challenge observed behavior (`NetworkObserver.kt`, `HarBuilder.kt`, `RouteManager.kt`, `Browser4WebDriver.kt`, `cli/browser4-cli/src/main.rs`), and the live backend was probed directly via HTTP.

**Session lifecycle:** both sessions closed cleanly; `list` shows only a pre-existing stale session from an earlier run.

---

---

## Issues Found (13 issues)

### Issue 1: Response bodies are never captured for fetches whose body the page never reads (har --content text/all, network request)

**Severity:** High
**Category:** Product

#### Reproduction

1) Fresh session on http://localhost:18080/b4/network-fixture.html (its second fetch reads only `missing.status`, never the body).
2) ./b4w.ps1 -s S network requests --filter network-endpoint-ok -> note finished=false for the unconsumed one.
3) ./b4w.ps1 -s S network har start --content text; goto away/back; network har stop out.har -> entry for /api/network-endpoint-missing.json has content={size,mimeType} with NO text and time=-1.
4) ./b4w.ps1 -s S network request <id-of-missing> -> no responseBody field at all.
A/B control: eval --await --file with `fetch(url).then(r=>r.text())` vs `fetch(url).then(r=>r.status)` against the same URL -> finished=true vs finished=false, same encodedDataLength (173).

#### Expected Behavior

The response body is captured (bytes were fully transferred, encodedDataLength=173), or the entry states why the body is unavailable (e.g. an _error/'body unavailable' note) instead of looking like a request still in flight.

#### Actual Behavior

Tracking entry keeps finished=false forever, timings.receive=-1, HAR time=-1, no body embedded under --content text (mime application/json qualifies), network request detail omits responseBody entirely, and no _error or note explains it. A 404 API call therefore appears bodiless and un-timed in the HAR.

#### Root Cause Analysis

Body capture (and the finished flag) is driven by the CDP Network.loadingFinished event. Chrome does not deliver loadingFinished for a fetch response whose body stream is never consumed/drained by page JS (observed: identical requests differ only by body consumption). NetworkObserver never marks such requests finished, so HarBuilder and networkRequestDetail skip body/timing. Inferred from A/B evidence and NetworkObserver.kt's loadingFinished-driven capture; confirm the exact Chrome event behavior for unconsumed streams.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/network/NetworkObserver.kt (onLoadingFinished handler / harStop body drain); TrackedNetworkRequest.kt (finished, responseBodyBytes); browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/network/HarBuilder.kt:totalTimeMillis()`

#### AI Suggested Improvement

- Fall back to Network.getResponseBody for requests whose bytes arrived (encodedDataLength > 0) even when loadingFinished never fired, e.g. before building the HAR / answering a detail query.
- Mark such entries explicitly in tracking and HAR output (e.g. finished=false plus a bodyUnavailable reason) so consumers can distinguish 'no body' from 'still in flight'.
- Add an e2e fixture variant (or extend network-fixture.html) asserting the unread-body case, since the shipped fixture already hits it.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: HAR entry timings are ~1000x too large (microseconds emitted where HAR 1.2 requires milliseconds)

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s S network har start --content text; ./b4w.ps1 -s S goto <url>; ./b4w.ps1 -s S network har stop out.har; then inspect an entry: e.g. entry 6 has time=11 but timings={send:196,wait:9173,receive:0}. sum(timings) ~ 600-900x the entry's own time for every entry.

#### Expected Behavior

HAR 1.2 timings in milliseconds, consistent with the entry's total `time` (a localhost request that took ~11 ms should show wait ~9 ms, not 9173).

#### Actual Behavior

timings.wait/send values are 1000x the true duration; Chrome DevTools / HAR viewers display ~9 second waits for 8-11 ms localhost requests. timings.receive is consequently always 0 (clamped total minus inflated parts).

#### Root Cause Analysis

HarBuilder.deltaMillis() converts CDP timing offsets with (end-start)*1000, assuming seconds, while this Chrome build reports ResourceTiming offsets in milliseconds (the same file's totalTimeMillis converts the monotonic clock correctly, which is why `time` is right and `timings` are not). Inconsistency is provable internally: sum(timings) vs time.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/network/HarBuilder.kt:buildTimings() and deltaMillis() (lines ~194-213 and ~331-334)`

#### AI Suggested Improvement

- Fix the unit conversion in deltaMillis (drop the *1000 for ResourceTiming offsets, or scale from requestTime consistently), and add a unit test asserting sum(timings) is within a small tolerance of `time`.
- Derive `receive` from the monotonic total rather than a subtraction of possibly-clamped parts.
- Validate the produced HAR against the spec/DevTools import as part of the e2e suite.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: network unroute <pattern> reports success for a pattern that was never registered

**Severity:** Medium
**Category:** Reliability

#### Reproduction

With zero active routes: ./b4w.ps1 -s S network unroute "**/never-registered-pattern" -> {"unrouted":"**/never-registered-pattern"}, exit 0. (Also ./b4w.ps1 -s S network unroute -> {"unrouted":"all"} when no routes exist.)

#### Expected Behavior

Either an explicit 'no route registered with that pattern' result, or a report of how many routes were removed (e.g. {"unrouted":"...", "removed":0}).

#### Actual Behavior

Unconditional success echo naming the pattern, identical to removing a live route. Scripts and humans cannot tell a no-op from a real removal — a direct violation of the scenario's AC10 'no silent success' rule.

#### Root Cause Analysis

RouteManager.unroute() does routes.removeAll{...} and returns mapOf("unrouted" to (urlPattern ?: "all")) without checking whether anything matched; the CLI prints it verbatim.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/network/RouteManager.kt:unroute() (line ~131-146)`

#### AI Suggested Improvement

- Return the number of routes removed (and/or a boolean matched) from unroute() and surface it in the CLI output.
- Exit non-zero (or print a warning to stderr) when a pattern matched nothing.
- Align the shape with agent-browser intentionally if compatibility matters, but add the count as an extra field rather than dropping the signal.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: network har stop succeeds silently when no recording is active (and writes an entry-less HAR file)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

After a previous har stop (no new har start): ./b4w.ps1 -s S network har stop -> prints a HAR document with no log.entries, exit 0. With a path: ./b4w.ps1 -s S network har stop out.har -> 'HAR saved to out.har (0 entries, content mode: text)', exit 0.

#### Expected Behavior

An explicit 'no HAR recording is active' error or warning; nothing misleading is written or claimed. The documented contract ('the CLI only writes a file when the backend returned a real HAR document') should cover this case.

#### Actual Behavior

Silent success. The output document omits log.entries entirely (HAR 1.2 requires a `entries` array, which would be [] here), so it is not a valid HAR; the '(0 entries)' suffix is the only weak hint, and it is indistinguishable from a recording that legitimately captured nothing. There is also no command to query whether a recording is active.

#### Root Cause Analysis

NetworkObserver.harStop() builds the HAR from the current buffer regardless of the harRecording flag and returns no recording-state error; the payload {recording=false,...} is not surfaced by the CLI. The empty entries list is then dropped in serialization (see the omitted-fields issue below).

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/network/NetworkObserver.kt:harStop() (line ~301); cli/browser4-cli/src/main.rs:handle_har_stop()/parse_har_stop_payload()`

#### AI Suggested Improvement

- Have harStop() return an explicit 'no active recording' error (or a warning field the CLI prints) when harRecording == false.
- Include recording state in `network har stop` output; consider a `network har status`-style query so 'am I recording?' is answerable without stopping.
- Always emit log.entries (even empty) for spec-valid output.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Documentation: 'tracking is opt-in and lazy ... traffic that happened before the first network command is not recorded' contradicts observed behavior

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s S open --headless http://localhost:18080/b4/network-fixture.html (fresh session); ./b4w.ps1 -s S network requests -> the very first network command already returns 4 entries, including the document request and both fetches from the navigation that preceded it.

#### Expected Behavior

Either the documented behavior (first call empty or containing only post-enable traffic; user re-navigates to populate), or documentation that matches reality (traffic is captured from the first navigation because the CDP Network domain is already enabled).

#### Actual Behavior

The first call is fully populated with pre-command traffic. network.md (Quick Start + 'Errors & Recovery') and `network requests --help` ('Network tracking is enabled on first use') set the wrong expectation. The 'already-finished requests are kept as metadata-only entries' claim is also inaccurate: a pre-recording fetch entry was delivered with its body embedded.

#### Root Cause Analysis

The underlying Pulsar layer calls Network.enable during navigation setup (PulsarWebDriver.enableAPIAgents0() -> browserProtocol.networkEnable(); NetworkManager.enable()), so the Network domain is live before the CLI's first network command. NetworkObserver.ensureEnabled() is a second, lazy enable that only guarantees buffers/listeners; the docs describe only that layer. The 'metadata-only' wording ignores that bodies may still be retrievable from Chrome's buffers when the HAR is built.

#### Code Pointer

`skills/browser4-cli/references/network.md (lines ~37-39 and ~197-199 'No requests tracked'); cli/browser4-cli/src/main.rs `network requests` help text ('Network tracking is enabled on first use')`

#### AI Suggested Improvement

- Rewrite the tracking-lifecycle section to state that the Network domain may already be enabled by the browser layer, so requests from the current tab's earlier navigations can appear; keep 're-run the interaction' as a remedy only for evicted/rotated entries.
- Correct the metadata-only claim to 'bodies are embedded when still retrievable from the browser'.
- Make the buffered-entry cap/eviction behavior explicit (the 'bounded in-memory buffer' size is never stated).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: --help <command> --examples is a documented dead end that misreports a healthy backend as down

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 network route --help --examples -> 'No examples available for 'network route' — is the backend running?' (backend healthy; it just served the session's commands). ./b4w.ps1 goto --help --examples -> built-in examples, then 'Tool-level examples are unavailable: is the backend running?'. Direct probe: curl http://localhost:8282/mcp/tools/specs -> {"tools":[]}.

#### Expected Behavior

Every help page advertises 'Runnable examples: browser4-cli network route --help --examples'; the network family should provide examples, and an empty/absent spec list should be reported as such ('the backend advertises no examples'), never as a possible outage.

#### Actual Behavior

The advertised examples command yields nothing for all network commands, and the reason given wrongly blames the backend. Exit is still 0. A first-time user cannot tell whether the feature is missing, the backend is broken, or they typed it wrong.

#### Root Cause Analysis

fetch_tool_spec_values_within() collapses every failure mode (connect error, non-JSON, empty list) into an empty Vec; build_tool_examples() maps 'specs.is_empty()' to 'is the backend running?'. The backend currently serves no tool specs at /mcp/tools/specs, so the message fires on every command even when healthy.

#### Code Pointer

`cli/browser4-cli/src/main.rs:build_tool_examples() and fetch_tool_spec_values_within() (lines ~20996-21015, ~21322-21340); backend endpoint /mcp/tools/specs serving {"tools":[]}`

#### AI Suggested Improvement

- Distinguish 'HTTP request failed' from 'server responded with zero specs' and word the latter as 'the backend advertises no tool examples'.
- Ship built-in examples for the network family (route/unroute/har are exactly the commands where the ** glob and --content modes need examples).
- Only advertise the --help --examples hint on commands that actually have examples, or make the hint indicate where the examples come from.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: No command can list active routes; docs tell users to destroy all routes to inspect them

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

browser4-cli --help network (and full help index) lists only: network requests, network request, network route, network unroute, network har start, network har stop. network.md 'Route not taking effect' advises: 'Check the active routes with network unroute (removes everything) or re-register the route.'

#### Expected Behavior

A read-only way to see registered routes (pattern, action, content type, resource-type filter), e.g. `network routes`.

#### Actual Behavior

None exists. The documented workaround destroys the interception state, and after several route/unroute calls a user has no way to audit what is installed.

#### Root Cause Analysis

RouteManager.routeList() exists but is not exposed by any MCP tool; the CLI/MCP surface has no routes-listing command. (MCP tool dump: browser_network_requests/request/route/unroute only.)

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/network/RouteManager.kt:routeList(); browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt (tool dispatch); cli/browser4-cli/src/main.rs (network subcommand family)`

#### AI Suggested Improvement

- Expose routeList() as `network routes` (and an MCP tool) printing pattern, action, contentType, resourceTypes, registration order.
- Update network.md to point at it instead of the destructive workaround.
- Return the same shape from route/unroute (see the missing-action issue) so scripts can reconcile state.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: network route output omits the action, so --abort and --body routes are indistinguishable

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s S network route "**/api/network-endpoint-missing.json" --abort -> {"routed":"**/api/network-endpoint-missing.json"} (identical to the --body mock case run earlier for the ok endpoint, including contentType/resource-type silence).

#### Expected Behavior

The confirmation states what was installed (action abort|body, content type, resource-type filter, and where it sits in the match order).

#### Actual Behavior

Only the pattern is echoed. Under AC10's 'reports what it changed' rule this is thin: an operator auditing several routes cannot tell mock from abort from the output, and there is no listing command to fall back on (see the route-listing issue).

#### Root Cause Analysis

RouteManager.route() returns mapOf("routed" to urlPattern) unconditionally ('matching agent-browser' per the code comment); the CLI prints the map verbatim.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/network/RouteManager.kt:route() (line ~112-127)`

#### AI Suggested Improvement

- Include action (abort|mock), contentType, resourceTypes and the route count/order in the response map.
- Keep the `routed`/`unrouted` keys for compatibility and add fields; print a one-line human summary by default.
- Have the CLI render a short confirmation line (pattern + action) instead of raw JSON in default output mode.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: HAR document drops spec-required/expected fields the builder populates (cache, cookies, queryString, redirectURL, page title, _error)

**Severity:** Low
**Category:** Product

#### Reproduction

Record a HAR and inspect: log.pages[0] has no `title`; entries have no `cache`; request objects have no `cookies`/`queryString` (even for URLs with a query); response objects have no `redirectURL`; no `_error` key on failures. HarBuilder.buildEntry() sets all of these (cache={}, cookies=[], redirectURL='', title='', _error='').

#### Expected Behavior

HAR 1.2 documents carry these keys (empty objects/arrays where nothing applies), which the docs also claim ('one entry per request with request/response/cache/timings ... and _error').

#### Actual Behavior

The keys never appear in the emitted document, so the produced file is non-conformant for strict HAR consumers and the documentation overstates it.

#### Root Cause Analysis

Observed values are exactly the empty-string/empty-collection defaults, and the CLI was verified to pretty-print the backend payload verbatim (serde_json, no field filtering), so the strip happens backend-side in a Jackson/NON_EMPTY-style inclusion policy between HarBuilder and the HTTP response. The exact serialization hop still needs confirmation (none of the currently greppable mapper configurations obviously apply to this path).

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/pulsar/chrome/network/HarBuilder.kt:buildEntry()/build() (values are produced here); serialization leaving values out is downstream (investigate the mapper used for the browser_har_stop tool result, e.g. MCPToolController / AgentToolManager result rendering)`

#### AI Suggested Improvement

- Trace and pin the tool-result serialization to a mapper that preserves empty collections/strings (or drop the empties in HarBuilder deliberately and note the deviation).
- Add a HAR-shape test asserting `cache`, `cookies`, `queryString`, `redirectURL`, `pages[].title` exist.
- If strict conformance is out of scope, soften network.md's HAR 1.2 conformance claim.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: network requests prints full headers for every entry — a wall of JSON that is hard to scan

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s S network requests -> one line, ~4 KB for four requests; each entry embeds complete request/response header maps plus the raw timing object.

#### Expected Behavior

A list view optimized for scanning (one line per request: id, method, status, type, size, short URL), with headers/timing available on demand or behind a flag.

#### Actual Behavior

The only mode is the full dump (the network fixture's favicon entry alone carries 10 request headers and 45 fields). On busy pages the output will be enormous and the JSON is effectively unreadable in a terminal.

#### Root Cause Analysis

The CLI prints the backend's per-request payload verbatim; there is no compact/table renderer or --verbose/--brief switch for this command family.

#### Code Pointer

`cli/browser4-cli/src/main.rs (network requests handling; compare with other commands that have human-readable default output)`

#### AI Suggested Improvement

- Default to a terse table; add --verbose/--full (and keep --json for machines).
- Consider truncating long header maps in the default view with a hint on how to get the full entry via `network request <id>`.
- Document the output modes in network.md's flag table.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: network requests --clear gives no feedback about what it dropped

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s S network requests --clear -> [] (identical output to a listing of an already-empty buffer).

#### Expected Behavior

Confirmation such as 'cleared 7 tracked requests' (or at least a distinguishable marker), so a user can tell a successful clear from a no-op.

#### Actual Behavior

Prints an empty JSON array; no count, no message.

#### Root Cause Analysis

The clear path returns the (now empty) filtered list rather than reporting the number removed.

#### Code Pointer

`cli/browser4-cli/src/main.rs (network requests --clear handling); backend MCPToolController/tool executor for browser_network_requests`

#### AI Suggested Improvement

- Print the number of dropped entries (stderr or a small summary) when --clear is used.
- Keep stdout machine-clean for --json by reporting the count in the JSON envelope instead.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: Unknown request id is reported as [INTERNAL] plus a raw tool-help dump with Kotlin signatures

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s S network request bogus-id-123 -> '[INTERNAL] browser_network_request failed: Unknown network request id: bogus-id-123' followed by the tool description, examples, and a Kotlin signature line 'tab.networkRequestDetail(requestId: String)'. Exit 1.

#### Expected Behavior

A clean user-level error (e.g. "Unknown network request id 'bogus-id-123' — run `network requests` to list current ids"), with no INTERNAL tag and no internal API signatures.

#### Actual Behavior

A user-input error is labeled INTERNAL, and the remediation is buried under a verbose spec dump referencing the MCP tool name (browser_network_request) rather than the CLI command (network request).

#### Root Cause Analysis

Tool failures are rendered with a generic '[INTERNAL] <mcp tool name> failed:' prefix and the tool's help blob; the CLI passes the MCP error through without mapping tool names to CLI command names or classifying user errors.

#### Code Pointer

`cli/browser4-cli/src/main.rs (tool error rendering / call_tool error path); browser4-agentic BrowserTabToolExecutor networkRequestDetail error message`

#### AI Suggested Improvement

- Classify 'Unknown network request id' as a user error (no INTERNAL prefix) and render the CLI command name.
- Suppress the tool-spec help block on failures, or gate it behind --verbose.
- Add the same id-recovery hint the docs already contain ('re-list with network requests').

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 13: False 'Possible blocked/challenge page' warning fires on small but valid pages

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s S goto http://localhost:18080/b4/frame-other.html (a valid local fixture returning 200 with a heading, a button and a state div) -> stderr warning: 'Possible blocked/challenge page — detected page text is implausibly empty (50 chars) after a successful navigation.' Happened on both navigations to this fixture.

#### Expected Behavior

No challenge warning for a plainly valid 200 response from a local server (the page text is intentionally short), or at least wording that does not suggest a block.

#### Actual Behavior

Two alarming warnings during a routine local test run; on a first-time user it reads like the tool is being blocked. The advice ('inspect with snapshot before escalating') sends users to investigate a non-problem.

#### Root Cause Analysis

The challenge probe's 'implausibly empty body' heuristic (visible character count with no iframes) has no baseline for intentionally small pages, and the warning text is framed as a possible block rather than a low-content note. SKILL.md itself calls this a weak signal, but the CLI message does not convey the low confidence.

#### Code Pointer

`cli/browser4-cli/src/main.rs:29246 (message 'page text is implausibly empty ... after a successful navigation') and the surrounding detection code (~29240-29510)`

#### AI Suggested Improvement

- Soften the message for the weak signal and separate it from the strongly-named 'blocked/challenge' text (e.g. 'page has little visible text; if content is missing, inspect with snapshot').
- Skip or downgrade the warning for localhost/127.0.0.1 targets and for responses whose status and title look normal.
- Consider a content-length/body-size signal instead of only visible text length.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 12 scenario steps executed and all 10 acceptance criteria were met. Both mock (--body) and abort routes worked on the first try and were cleanly reversible; HAR produced a valid 1.2 document containing both fixture URLs. However, several commands reported success without changing anything (unroute of a never-registered pattern, har stop with no recording), and the HAR/detail output silently omits response bodies for one of the fixture's two fetches, which is a data-quality gap in the headline feature.

**Success Rate:** 100% of steps and acceptance criteria completed as scripted; 0 blocking failures. Roughly 3 of 10 ACs were satisfied in a way that exposed defects (AC1 vs documented tracking lifecycle, AC5 body coverage, AC10 silent-success cases).

**Issues Found:** 13

**Most Confusing Aspects:** 1) The tracking lifecycle: the docs and help say tracking is lazy and starts on first use, but the first `network requests` call already returned traffic from a navigation that happened before any network command — a user cannot tell which model is true, or when the buffer started. 2) Silent-success semantics: `network unroute <pattern>` and `network har stop` both exit 0 while changing nothing (one of them even writes an entry-less .har). 3) Discoverability of state: there is no way to list active routes or to ask whether a HAR recording is running, and the advertised `--help --examples` path answers 'is the backend running?' on a perfectly healthy backend. 4) The `**` glob syntax is explained in `network route --help`, but the identical phrase 'plain text matches URLs containing it' next to 'glob supported' leaves the exact matching semantics (contains vs full glob) genuinely ambiguous — I confirmed behavior only by experiment.

**Most Valuable Improvements:** 1) Make every state-changing network command report precisely what changed (route action + count, unroute removal count, cleared-entry count, recording state) instead of echoing the pattern. 2) Fix the HAR defects that make the artifact untrustworthy: 1000x timings, missing bodies for unconsumed fetches, and dropped spec fields. 3) Add read-only state commands: `network routes` and a `network har status` query. 4) Correct the tracking-lifecycle documentation (and the 'metadata-only entries' claim) so users can predict what the buffer contains. 5) Make `--help --examples` either deliver examples for the network family or stop blaming the backend. 6) A compact default listing for `network requests` (one line per request) would transform its usability on real pages.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: Response bodies are never captured for fetches whose body the page never reads (har --content text/all, network request)

1) Fresh session on http://localhost:18080/b4/network-fixture.html (its second fetch reads only `missing.status`, never the body).
2) ./b4w.ps1 -s S network requests --filter network-endpoint-ok -> note finished=false for the unconsumed one.
3) ./b4w.ps1 -s S network har start --content text; goto away/back; network har stop out.har -> entry for /api/network-endpoint-missing.json has content={size,mimeType} with NO text and time=-1.
4) ./b4w.ps1 -s S network request <id-of-missing> -> no responseBody field at all.
A/B control: eval --await --file with `fetch(url).then(r=>r.text())` vs `fetch(url).then(r=>r.status)` against the same URL -> finished=true vs finished=false, same encodedDataLength (173).

#### Issue 2: HAR entry timings are ~1000x too large (microseconds emitted where HAR 1.2 requires milliseconds)

./b4w.ps1 -s S network har start --content text; ./b4w.ps1 -s S goto <url>; ./b4w.ps1 -s S network har stop out.har; then inspect an entry: e.g. entry 6 has time=11 but timings={send:196,wait:9173,receive:0}. sum(timings) ~ 600-900x the entry's own time for every entry.

#### Issue 3: network unroute <pattern> reports success for a pattern that was never registered

With zero active routes: ./b4w.ps1 -s S network unroute "**/never-registered-pattern" -> {"unrouted":"**/never-registered-pattern"}, exit 0. (Also ./b4w.ps1 -s S network unroute -> {"unrouted":"all"} when no routes exist.)

#### Issue 4: network har stop succeeds silently when no recording is active (and writes an entry-less HAR file)

After a previous har stop (no new har start): ./b4w.ps1 -s S network har stop -> prints a HAR document with no log.entries, exit 0. With a path: ./b4w.ps1 -s S network har stop out.har -> 'HAR saved to out.har (0 entries, content mode: text)', exit 0.

#### Issue 5: Documentation: 'tracking is opt-in and lazy ... traffic that happened before the first network command is not recorded' contradicts observed behavior

./b4w.ps1 -s S open --headless http://localhost:18080/b4/network-fixture.html (fresh session); ./b4w.ps1 -s S network requests -> the very first network command already returns 4 entries, including the document request and both fetches from the navigation that preceded it.

#### Issue 6: --help <command> --examples is a documented dead end that misreports a healthy backend as down

./b4w.ps1 network route --help --examples -> 'No examples available for 'network route' — is the backend running?' (backend healthy; it just served the session's commands). ./b4w.ps1 goto --help --examples -> built-in examples, then 'Tool-level examples are unavailable: is the backend running?'. Direct probe: curl http://localhost:8282/mcp/tools/specs -> {"tools":[]}.

#### Issue 7: No command can list active routes; docs tell users to destroy all routes to inspect them

browser4-cli --help network (and full help index) lists only: network requests, network request, network route, network unroute, network har start, network har stop. network.md 'Route not taking effect' advises: 'Check the active routes with network unroute (removes everything) or re-register the route.'

#### Issue 8: network route output omits the action, so --abort and --body routes are indistinguishable

./b4w.ps1 -s S network route "**/api/network-endpoint-missing.json" --abort -> {"routed":"**/api/network-endpoint-missing.json"} (identical to the --body mock case run earlier for the ok endpoint, including contentType/resource-type silence).

#### Issue 9: HAR document drops spec-required/expected fields the builder populates (cache, cookies, queryString, redirectURL, page title, _error)

Record a HAR and inspect: log.pages[0] has no `title`; entries have no `cache`; request objects have no `cookies`/`queryString` (even for URLs with a query); response objects have no `redirectURL`; no `_error` key on failures. HarBuilder.buildEntry() sets all of these (cache={}, cookies=[], redirectURL='', title='', _error='').

#### Issue 10: network requests prints full headers for every entry — a wall of JSON that is hard to scan

./b4w.ps1 -s S network requests -> one line, ~4 KB for four requests; each entry embeds complete request/response header maps plus the raw timing object.

#### Issue 11: network requests --clear gives no feedback about what it dropped

./b4w.ps1 -s S network requests --clear -> [] (identical output to a listing of an already-empty buffer).

#### Issue 12: Unknown request id is reported as [INTERNAL] plus a raw tool-help dump with Kotlin signatures

./b4w.ps1 -s S network request bogus-id-123 -> '[INTERNAL] browser_network_request failed: Unknown network request id: bogus-id-123' followed by the tool description, examples, and a Kotlin signature line 'tab.networkRequestDetail(requestId: String)'. Exit 1.

#### Issue 13: False 'Possible blocked/challenge page' warning fires on small but valid pages

./b4w.ps1 -s S goto http://localhost:18080/b4/frame-other.html (a valid local fixture returning 200 with a heading, a button and a state div) -> stderr warning: 'Possible blocked/challenge page — detected page text is implausibly empty (50 chars) after a successful navigation.' Happened on both navigations to this fixture.

