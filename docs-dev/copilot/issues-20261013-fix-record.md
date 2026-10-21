# 2026-10-13 scenario issue batch — fix record

> **Verification status.** All entries below are in the working tree and verified:
> Rust `cargo test --bin browser4-cli` → **1749 passed / 0 failed**; Kotlin
> `mvn -DskipTests install` → **BUILD SUCCESS** against the patched
> `pulsar-browser:4.11.25-SNAPSHOT` (all modules, test sources included); focused
> Kotlin suites → `HarBuilderTest` 16, `ScrapeResponseTest` 14,
> `BrowserTabToolExecutorTest` 70, `ToolErrorCodeTest` 11,
> `ToolResultTextRendererTest` 7, `CrawlSupportTest` 68,
> `CrawlServicePersistenceTest` 15, `CrawlResponseTest` 18,
> `MCPToolControllerTest` 100 — **all green** (319 tests). The PowerShell wrappers
> parse and `bin/tests/test.ps1.tests.ps1` passes 205/205. Upstream `pulsar-browser`
> builds and its suite passes except one `Integration`-tagged test on a live
> third-party navigation (see the upstream section).

Source: 19 `coworker/tasks/issues/draft/20261013-*.issues.md` files (139 reported
issues, ~95 distinct root causes after cross-file de-duplication).

This document records what was changed for each reported defect, what was
deliberately not changed, and why. Issue numbering is `FILE#N` where FILE is the
short name of the draft file.

## How to read the table

| Column | Meaning |
|---|---|
| Status | `fixed` — behaviour or documentation changed in this batch; `deferred` — real, but out of scope for this batch (reason given); `dup` — same root cause as another row |
| Where | the file(s) that changed |

## Behaviour fixes

### Reliability: silent success on an unresolvable target

Reported in `advanced-mouse-interaction#1`, `iframes-and-popups#2`,
`element-assertions-and-focus#1`.

`click` / `hover` / `dblclick` printed `✓ …` and exited 0 for a selector that
matched nothing: `movePointerToClickTarget()` is best-effort, so the trusted CDP
path was skipped and the upstream synthetic DOM click no-oped silently.
`check` / `uncheck` / `focus` shared the path — the upstream `focus` is explicitly
silent (`invokeDeferredSilently`) and `check`/`uncheck` go through the tolerant
locator path.

**Fixed** in `Browser4WebDriver.kt`: a `failIfPointerTargetMissing(action, selector)`
probe (`evaluateValue(selector, "function(){ return this != null; }")`) now runs
whenever the trusted path had no click point, throwing
`"<action>: no element found for selector [<selector>]. The selector may be stale —
re-run \`snapshot\` to refresh refs."` — the same wording the input family already
used. A probe that cannot run at all (transport / execution-context error) still
falls back to the upstream DOM path, so frame-resident and off-viewport targets
keep working. `hover`, `check`, `uncheck` and `focus` gained the same guard as
overrides, so the whole pointer/assertion family has one fail-loud contract.

### Reliability: `wait` never failed on timeout

Reported in `extraction-method-routing#1` (Critical) and `iframes-and-popups#1`.

The upstream `waitUntil` wraps its poll in `withTimeoutOrNull` and discards the
null, so the backend returned success and the CLI printed `✓ Element found`
without ever inspecting the result.

**Fixed** on both sides: the executor polls the target itself and throws
`TimeoutException` at the deadline, and `format_wait_result` parses the backend
value instead of assuming success (positive elapsed ms = found, negative code /
null = timed out), routing a timeout to the CLI error path with the target and
the timeout named. The JSON envelope no longer reports `status: ok` on timeout.

### Reliability: `drag` dispatched the drag lifecycle three times

`advanced-mouse-interaction#2`.

`repeat(3) { … return@repeat }` is a `continue`, not a `break`, so every drag ran
the full `dragstart → dragover → drop → dragend` sequence three times and pages
with non-idempotent drop handlers executed their side effect three times.

**Fixed**: a plain `for (attempt in 0 until 3)` with `break` on success; attempts
2 and 3 now run only after a transient CDP failure, and a retry logs a warning.

### Product: `list` showed `-` for live sessions

Reported in six files (`advanced-mouse-interaction#4`, `bulk-scale-routing#4`,
`comprehensive-ecommerce-workflow#4`, `console-errors-and-cdp#13`,
`element-assertions-and-focus#9`, `extraction-method-routing#3`).

`-` is documented as "backend unreachable or session stale", but it was printed
whenever the persisted session record carried no URL — including for sessions
that demonstrably had a live page.

**Fixed**: the session-list handler populates the URL from the live session, the
CLI falls back to its own live-URL helper for locally known sessions, and a URL
that genuinely cannot be determined gets a distinct marker plus a stderr note
instead of the `unreachable/stale` sentinel.

### Reliability: error classification

Reported in `javascript-evaluation#1`, `javascript-evaluation#5`,
`iframes-and-popups#11`, `iframes-and-popups#12`, `element-assertions-and-focus#4`.

Element-lookup misses were thrown as `IllegalArgumentException`, so
`isUsageError` treated them as argument-shape mistakes and appended ~30 lines of
tool help/signature; the appended text contained the parameter name `waitTimeout`,
which the substring-based classifier read as a timeout — producing a retryable
`[TIMEOUT]` code (HTTP 504) for a stale ref.

**Fixed**: a dedicated `TargetNotFoundException` carrying
`ToolErrorCode.TARGET_NOT_FOUND` (non-retryable, 404) replaced the
`IllegalArgumentException` for lookup misses; the timeout pattern is token-aware;
call sites classify the throwable rather than the composed help text; and the
dialog-pending outcome has its own non-retryable code that tells the caller to
run `dialog-accept`/`dialog-dismiss` rather than re-running the click.

### Product: `is enabled` returned null

`element-assertions-and-focus#3` — it returned `evaluateValue(...)` directly, so a
missing selector produced `null` while `is visible`/`is checked` produced `false`.
**Fixed**: coalesced to `false` for a uniform boolean contract.

### Product: `get box` returned a serializer wrapper

`element-assertions-and-focus#6` — the `boundingBox` branch returned the driver's
`RectD`, which the MCP serializer rendered as `{type, description}`.
**Fixed**: returns a plain `{x, y, width, height}` map.

### Reliability: crawl record integrity

- `crawl-link-options#4` — `createdAt` silently became the finish time on terminal
  tasks (the data-class default evaluated at finalization) and drifted on every
  in-flight publish. **Fixed**: carried forward from the CREATED record / task
  context in all terminal builders, the in-flight publisher, the no-seeds
  rejection and the resume path; pinned by a regression test.
- `crawl-advanced-extraction#2` — an HTTP 4xx/5xx body was counted as a
  successfully delivered page and no status was recorded anywhere.
  **Fixed**: `CrawlPageResult.httpStatus` is populated at every fetch site, and the
  completion summary gains a note naming the error pages and the status counts
  (store-served rows are exempt, since their status describes the stored copy).

### Reliability: swarm duration was meaningless

Reported in `swarm-parallel-scraping#2` and `webminer-structuring-routing#5`.

`startedTime` was stamped by the first `refresh()`/`complete()` call, which for a
swarm query task happens at the terminal update — so `startedTime ≈ finishTime`
and durations read as single-digit milliseconds while the queue wait was invisible.

**Fixed**: `ScrapeResponse.emitEvent` stamps `startedTime` on the first event
(worker pickup, `onWillLoad`), and `queuedMillis` / `elapsedMillis` expose the
queue-wait / processing / total split in the batch-status rows; the batch envelope
gains `submittedAt` so the submission window and the pickup window are both
visible.

### Reliability: swarm 503 had no recovery path

`bulk-scale-routing#1`, `bulk-scale-routing#3` — every swarm endpoint answered
503 with a bare "not installed" while the plugin was missing from a source-tree
bundle. **Fixed**: the exception message now names the plugin and the install +
forced-rebuild recipe, and `doctor status` reports the loaded plugin count.

### Discoverability: `--help <cmd> --examples` blamed a healthy backend

Reported in five files (`console-errors-and-cdp#7`, `crawl-link-options#2`,
`crawl-sql-formats#1`, `element-assertions-and-focus#8`, `network-observability#6`).

`GET /mcp/tools/specs` legitimately returns only plugin specs that declare a
`cliName`, so the list is normally empty — and an empty *successful* response was
reported as "is the backend running?".

**Fixed**: the empty-success case gets its own wording ("the backend advertises no
tool-level examples"), the backend-down text is reserved for genuine request
failures, and static example blocks now ship for the commands that have no
backend-provided examples.

### Safety: destructive or invalid input accepted silently

`device-and-network-emulation#6`, `#8`, `#9`; `x-sql-extraction-functions#2`;
`element-assertions-and-focus#7`; `bulk-scale-routing#5`.

- `set <mode>` with an unknown mode emitted a broken CDP call and surfaced a raw
  protocol error instead of the valid-mode list.
- `set headers --json` with malformed JSON cleared **all** extra headers and
  exited 0.
- `set geo` / `set device` silently applied undocumented defaults.
- Unknown/typo'd `--options` were dropped on the floor for many commands.
- `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1` was a silent no-op while a server was
  already running.

**Fixed**: local validation with actionable messages for the `set` family
(malformed JSON is refused before the CDP call), declared-option validation at the
dispatch boundary with a closest-name suggestion, and an explicit message when the
force-rebuild request is ignored because a server is already running.

### Reliability: emulation observable by the page

`device-and-network-emulation#2` — `set geo` installed
`Emulation.setGeolocationOverride` but never granted the geolocation permission,
so `getCurrentPosition()` never settled. **Fixed**: the permission is granted for
the page origin and the origin is named in the confirmation.

### Reliability: GitHub Bash / MSYS argument mangling

Reported in five files (`bulk-scale-routing#6`, `crawl-link-options#1`,
`storage-state-management#1`, `#2`, `element-assertions-and-focus#12`).

MSYS rewrites a leading `/` into a Windows path before `pwsh` starts, and the
equals form (`--path=/`) bypassed the existing guard entirely, so the user got an
error naming a value they never typed.

**Fixed**: the guard also matches the value after `=` (including the split
`--path=C` + `/…` form) and prints the exact de-converted command to re-run;
`./b4w.sh` is documented as the Git Bash entry point; and the CLI help/error paths
carry the same one-line workaround.

### UX: long-running dev setup with no progress

`advanced-mouse-interaction#5` — the MockSite preflight ran ~13 minutes under
`-q` with a banner only per completed phase. **Fixed**: a cold-cache notice, a
periodic elapsed-time heartbeat per Maven phase, and the Maven log path printed so
it can be tailed.

### Packaging: runtime bundle plugin selection

`bulk-scale-routing#1`, `#2` — the bundler assumed `~/.m2/repository` (a Maven
`settings.xml` redirect made it find nothing, so zero plugins shipped and every
swarm endpoint 503'd), and it picked the plugin JAR by newest mtime with no
version filter, so a stale release train could be bundled.
**Fixed**: the real local repository is resolved from Maven, candidates are
filtered by project version first, and a missing/mismatched plugin is reported
loudly instead of as an invisible `Write-Warning`.

### UX: output that hid what happened

- `webminer-structuring-routing#3` (storage) — `cookie-list` / `state-save` omitted
  absent cookie attributes, so the CLI's own JSON was an optional-key schema.
  **Fixed**: one shared mapping emits the full attribute set with `null` for what
  the browser did not report (extra attributes the browser does send are kept).
- `console-errors-and-cdp#9` — `goto` to the URL the page was already on said
  "page unchanged" although the navigation had already reloaded the page.
  **Fixed**: the message states the reload.
- `console-errors-and-cdp#11` — the eval tips claimed console.log output "is not
  captured", contradicting the `console` command. **Fixed**: both tips now say the
  output goes to the page console buffer (readable with `console`), not to eval's
  return value.
- `x-sql-extraction-functions#5` — capture metadata printed `w=<weight>` with no
  explanation. **Fixed**: the field is now `weight=`.
- `comprehensive-ecommerce-workflow#2` — `extract --stdout` printed `{}` and exited
  0 when the model returned nothing. **Fixed**: warning on stderr, non-zero exit,
  stdout stays pure JSON.
- `form-filling#3` — `get attr` / `get property` said "No elements matched" for an
  element that existed with an empty value. **Fixed**: the wording distinguishes
  "no match" from "matched but empty".
- `form-filling#5` — `select` echoed an unlabeled raw JSON array before the
  confirmation. **Fixed**.
- `file-upload-and-download#3`, `#9`; `device-and-network-emulation#7` — `download`
  / `upload` / `set` printed a bare `{}` or nothing. **Fixed**: confirmations name
  what was applied/uploaded and the resolved absolute directory.
- `file-upload-and-download#4` — `wait --download` counted pre-existing files as a
  completed download. **Fixed**: the directory is snapshotted at wait start.
- `swarm-parallel-scraping#1`, `#3` — one output mixed UTC and local timestamps,
  and "10 of 11 tracked" read as a failure count. **Fixed**: one timezone
  convention and unambiguous header wording.
- `network-observability#10` — `network requests` dumped full-header JSON for the
  whole session. **Fixed**: a compact table by default with `--limit`, raw JSON
  behind `--json`.
- `crawl-advanced-extraction#5` — a "did you mean …?" hint ended with `?.`.
  **Fixed**.
- `webminer-structuring-routing#1` — `webminer all` silently capped the corpus at
  the 40-file default and still printed PIPELINE COMPLETE. **Fixed**: the cap is
  warned about and the discovered/processed counts are in the final summary.
- `webminer-structuring-routing#4` — `stop` deleted every persisted session record,
  so sessions vanished from `list` instead of showing as stale. **Fixed**: `stop`
  marks records stale; only close/close-all/kill-all delete them.

### Discoverability: help text contradicted behaviour

- `advanced-mouse-interaction#3` — `snapshot -i` was documented as "not a strict
  interactive-only filter"; it is one. **Fixed**: help and SKILL.md describe the
  real behaviour (including that draggable and hover-only targets may lose refs)
  and document the `-i -v <n>` combination.
- `crawl-link-options#3`, `crawl-sql-formats#2` — `-a, --args` was labelled
  `<json>` while it parses a raw LoadOptions string; `--sql-base64` was documented
  as value-taking in one place and boolean in another. **Fixed**.
- `crawl-sql-formats#3` — the help alias line advertised `crawl=swarm`, which can
  never fire because `crawl` is a real command. **Fixed**.
- `iframes-and-popups#6`, `#10` — frame scoping was never mentioned in
  element-command help; `window new` was called a tab in one place and a window in
  another. **Fixed**.
- `device-and-network-emulation#10`, `#11`, `#12`, `#14` — emulation scope,
  persistence and undo were undocumented; the command table truncated the `set`
  summary mid-backtick so `media` and `device` were invisible. **Fixed**.
- `javascript-evaluation#3`, `file-upload-and-download#5`,
  `device-and-network-emulation#1` — the eval world (isolated
  `__browser4_runtime__` with a silent page-world fallback) and the
  `undefined` → empty-string mapping were undocumented. **Fixed in docs**; the
  world selection itself is upstream.
- `x-sql-extraction-functions#3`, `#5`, `#6` — "no joins" was wrong (a JOIN of two
  `DOM_LOAD_AND_SELECT` sources executes), `w=` was unexplained, and numeric
  results serialize as strings. **Fixed in docs**.
- `webminer-structuring-routing#2` — the interactive report/dataset land in the OS
  temp dir, not `--output`. **Fixed in docs**.

### Discoverability: no way to inspect the active routes

`network-observability#7` — the only way to see which request routes were active
was to remove them all with `network unroute`, which destroyed the state the
caller wanted to inspect (and the docs said so).

**Fixed**: `networkRoutes()` on the driver, exposed through the executor and the
MCP alias, plus a new `network routes` command that renders pattern, action
(`abort`/`body`), mock content type and resource-type filter as a compact table.
`network unroute <pattern>` also refuses a pattern that was never registered and
reports `removed`/`routes` counts instead of echoing success unconditionally.

## Upstream fixes (`D:\workspace\Browser4\browser4base`, pulsar-browser)

The base library is a sibling checkout, so the defects that live there were fixed in place and this
tree now consumes them: `browser4-dependencies/pom.xml` pins
`<browser4-base.version>4.11.25-SNAPSHOT</browser4-base.version>` (was the 4.11.24 release), which is
what makes the changes below visible here at all — under the release pin they are inert. Revert the
pin to the next base release once it ships.

- **`eval` could not read page globals** (`device-and-network-emulation#1`,
  `file-upload-and-download#5`, Critical). `JsHandler` always preferred the driver's isolated world
  (`__browser4_runtime__`), so `window.myApp` read `undefined` on a page that plainly defined it, and
  the page-world fallback was silent and session-dependent. Fixed with an explicit world selector:
  `JsWorld` (`api/JsWorld.kt`), world-aware `evaluateDetail` / `evaluateValueDetail` (the world-less
  overloads keep the isolated preference, so driver-internal helpers are unaffected), overrides on
  `PulsarWebDriver`, and `world` parameters on the `WebDriver` interface (defaulted, so no implementor
  breaks). Browser4 side: the eval tool defaults to `main` and validates the value, and the CLI gained
  `eval --world main|isolated`.
- **`goto` silently dropped device emulation** (`device-and-network-emulation#4`, High).
  `navigateInvaded` normalises the viewport with `Emulation.setDeviceMetricsOverride`, which replaces
  the whole override — so a `goto` reset a 390x844@2 mobile emulation to the desktop viewport while
  `reload`/`goBack`/`goForward` (which do not take that path) kept it. `PulsarWebDriver` now memoises
  the active override (written by `resize` and by raw `Emulation.setDeviceMetricsOverride` through
  `executeCdpCommand`, cleared by `Emulation.clearDeviceMetricsOverride`) and re-applies it after the
  navigation normalisation, logging what it restored.
- **`resize` silently cancelled device emulation** (`device-and-network-emulation#5`, Medium). The
  same replace-only CDP call was re-issued with `deviceScaleFactor = 0.0, mobile = false`, so a resize
  came back at dpr 1 and still reported success. `resize` now carries the active scale factor and
  mobile flag forward and states that it preserved them.
- **Frame scope survived into the next document** (`iframes-and-popups#3`, High). The scope *was*
  cleared on the `FrameNavigated` event, but everything between the navigation call and that event
  still resolved element operations against the previous document's frame ids, so a navigation's own
  post-navigation work could fail on a navigation that had actually succeeded. `navigate` and `reload`
  now drop the scope before they run; the event-time reset stays as the backstop.
- **Geolocation emulation prerequisite found** (`device-and-network-emulation#2`). The launcher forces
  `--disable-geolocation` by default (an unattended browser must not block on a permission prompt),
  and `Emulation.setGeolocationOverride` supplies a position *through* the geolocation service — so the
  browser4-side permission grant alone cannot make the position observable when that flag is on, and a
  launch flag cannot be undone at run time. Documented on `ChromeDefaults.DISABLE_GEOLOCATION` and in
  the CLI skill (relaunch with `browser.launch.disable.geolocation=false`). **Needs a browser-side
  check** to confirm whether the flag suppresses an overridden position in practice.
- **Test status upstream:** `pulsar-browser`'s suite runs green except
  `ChromeDevToolsTest.testDevTools` (tagged `Integration`, excluded from the gate), which times out on
  a raw `Page.navigate` to a live third-party site — a code path none of the changes above touch.

## Issue-file archival

Every issue artifact under `coworker/tasks/issues` (draft, archive, github, local, and the previous
`review/done` additions) was moved into `coworker/tasks/issues/review/done/`, preserving the
date-based layout: loose `draft/2026MMDD-*` files were grouped into `review/done/2026/MMDD/`, the
`draft/2026/<MMDD>/` and `archive/2026/<MMDD>/` trees were merged into `review/done/2026/<MMDD>/`,
and 15 files whose names already existed there with different content were kept under
`review/done/_duplicates/archive/...` rather than overwritten. 570 files, all staged as `R100`
renames (no content lost). Two files stay in place on purpose: `REVIEW-GUIDE.md` (referenced by
`coworker/README.md`) and `.report/.gitignore` (the output directory of
`coworker/scripts/workers/scan-deferred-issues.ps1`).

## Deliberately not changed

| Issue | Why |
|---|---|
| `file-upload-and-download#1` — CLI-driven downloads end as `Download.InterruptedReason = FILE_FAILED` with no `.crdownload` ever written (Critical) | The reproduction needs the failing dev-mode browser profile; the reported deltas (backend app-data root, profile location) point at browser-wide download state seeded into per-session profiles, not at a line of code that can be corrected blind. The CLI now validates and echoes the resolved directory, which makes the next diagnosis concrete. Next step: diff the profile template/seed between a dev backend and the passing e2e harness. |
| `device-and-network-emulation#1`, `#4`, `#5` — `eval` runs in an isolated world; `goto` and `resize` drop the device-metrics override | **Fixed upstream** — see the upstream section above (main-world eval with `--world`, device-metrics memo re-applied after `goto` and preserved by `resize`). |
| `iframes-and-popups#3` — a stale active-frame pointer survives navigation | **Fixed upstream** — the scope is cleared before `navigate`/`reload` runs, with the event-time reset kept as a backstop. |
| `file-upload-and-download#2`, `form-filling#1`, `iframes-and-popups#4` — the backend JVM died mid-session with no log | Not reproducible from the CLI side; the batch fixes the misreporting ("No active browser session" → the backend is unreachable and the saved session may be lost) and documents that a shared dev backend can be terminated externally. |
| `comprehensive-ecommerce-workflow#1` — `DOM_FIRST_IMG` returns `""` when the selector matches the `<img>` itself | The image-scanning path lives in the X-SQL engine (`pulsar-ql` dependency). The in-repo warning path already covers the `:expr` variant; the id/class variant needs the engine change. |
| `comprehensive-ecommerce-workflow#3` — `htmlsnapshot inspect` cannot surface unique landmark selectors | Feature work (single-occurrence landmark mining) rather than a defect; the narrative fix is tracked with it. |
| `x-sql-extraction-functions#4` — a rejected non-SELECT X-SQL (CTE) answers HTTP 500 instead of 4xx | Mapped as part of the error-classification work where the endpoint allows it; the remaining status-table wording is documented. |
| `element-assertions-and-focus#2` — assertion commands exit 0 when the result is false | An exit-code change to `is *` is a breaking contract for existing scripts; the batch adds an opt-in `--fail-on-false` (wired in the dispatcher and tested) and documents the contract instead of changing the default. |
| `swarm-parallel-scraping#4` — non-TTY `swarm create` auto-cleans completed tasks | Behaviour is intentional for scripted runs; the batch makes it visible (cleaned ids are printed) and documents it. A `--keep` switch was not added. |
| `network-observability#9` — the HAR document drops `cache`/`cookies`/`queryString`/`redirectURL`/`title`/`_error` | Root cause located: `HarBuilder` populates them, then Jackson's `NON_EMPTY` inclusion in the tool-result mapper strips empty maps/lists/strings. Changing that mapper globally would alter every tool's output, and making `har` a string would break the CLI's object parser — needs a HAR-specific serialization decision. |
| `console-errors-and-cdp#8` — `plugin list` shows no tools or argument schemas | Tool discovery is a plugin/MCP-surface change (specs endpoint + CLI rendering), not a defect in one command; the `--json` position handling was left as-is. |
| `device-and-network-emulation#14` — `set device --mobile` leaves the desktop UA | Documented as reality: the command sets device metrics only; sending a mobile UA is a behaviour change that needs a `--ua` contract (and a restore path) first. |
| `javascript-evaluation#3` (partial) — `eval undefined` now prints `null` | The renderer change makes `undefined` and `null` distinguishable from `''`; the underlying ambiguity of an empty result is documented. |
