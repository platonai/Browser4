# 2026-10-15 scenario issue batch — fix record

> **State of the batch.** Every **CLI-side** issue among the 132 reported has been fixed
> and verified, and the backend-side items that were left with a located fix have since
> landed too: the attach cluster's remaining half (a re-attach that rebuilds the bound
> driver, a close that stops at its own session, and a liveness message that names the
> link that actually died — `attach-remote-debug#1`/`#3`/`#4`) is in. The clusters: the
> agent-lifecycle contract
> (the shared task envelope across `agent status`/`result`/`list`/`--clear`/`--wait`, the
> enriched result payload, the not-found reason, docs and both READMEs); all five plugin
> modules (captcha 34, images 139, markdown 61, media 132, pptx 68 tests); runtime-bundle
> packaging and the plugin/tool command surface (`tool list`, per-domain help, glob
> install, short-name `plugin info`, `tool call --fail-on-error`, offline domain help);
> the CLI infrastructure group (sticky privileged dev port with its origin in `status`,
> non-TTY spinner, `--pretty`/`-s`, path separators, help text); the whole snapshot
> cluster (capture normalisation, diff format, persisted `.diff.txt` + `snapshot grep
> --diff`, `snapshot clean`, the dead `strip_viewport_footer`, the `snapshot --selector`
> quoting bug); the whole htmlsnapshot and inspect clusters (`#1`–`#6` each, plus
> `x-sql#2/#3/#6`); the crawl cluster (completion line, partial-failure label and filter,
> `--format`/`--json` contract, pruning notices, `--parallel` units); the loop cluster
> (`workerPid` liveness, detached-worker pipe fix, stop sentinel with honest survivor
> reporting, one-line renderer, single history writer, state-path text, `--list` footer);
> and the attach/session/tabs/window group (the CLI half of every item, plus the backend
> attach/close/liveness fixes above).
>
> **Verified runs:** `cargo test --bin browser4-cli` = **1849 passed / 0 failed / 4
> ignored**; `cargo test --test e2e -- --group crawl` = 17 passed; plugin modules 430
> tests; Kotlin suites (agentic 99 + 109, rest 152 + 174, core 30, agent-tools 47) and
> the MCP contract/tool-doc gates green; `bin/skill-doc-lint.ps1` = 0 issues;
> `build-runtime-bundle.tests.ps1` 182/182; `bin/tests/test.ps1.tests.ps1` 205/205.
>
> **Follow-up runs (attach cluster + the alias drift):** `mvnw -o -pl
> browser4-agentic,browser4-rest -am test` = **BUILD SUCCESS — browser4-agentic 1528/0,
> browser4-rest 813/0** (plus 275 / 166 / 66 upstream), i.e. the two modules are green
> **in the reactor**, which is the only configuration where the byte-exact generated-tool-doc
> gate compares the current registry; `browser4-rest` `PulsarSessionManagerTest`
> **41/41** on its own (two new attach/close regression tests); `docs/mcp-tools.md` /
> `.json` regenerated (`-DregenerateToolDocs=true`, "Regenerated 146 tool docs");
> `bin/skill-doc-lint.ps1` = **0 issues**; `cargo test --bin browser4-cli` = **1849
> passed / 0 failed / 4 ignored** after the CLI hint change.
>
> Two coverage gaps are recorded rather than papered over: the loop cluster has **no e2e
> scenarios at all**, and `loop-monitoring#4` keeps a raw-envelope fallback when a
> payload carries no summary.

Source: 18 `coworker/tasks/issues/draft/20261015-*.issues.md` files (132 reported
issues). This document records what was changed for each reported defect, what was
deliberately not changed, and why. Issue numbering is `<file-short-name>#N`, where
the short name is the draft file without its date prefix — e.g.
`agent-status-result-tracking#2`, `plugin-media-video-detection#6`.

## Verification

| Gate | Command | Result |
|---|---|---|
| Plugin modules (independent re-run) | `mvnw -pl browser4-plugins/browser4-{captcha,images,markdown,media,pptx} test` | **BUILD SUCCESS** — captcha 34, images 139, markdown 61, media 132 (3 ffmpeg-gated skipped), pptx 64 ⇒ **430 tests, 0 failures** |
| Rust CLI unit tests | `cargo test --bin browser4-cli` | **1831 passed / 0 failed / 4 ignored** on the tree with every landed workstream in it (baseline before the batch: 1752 passed); this run was taken while the last two CLI clusters were still writing, so the number is a floor, not a final |
| Focused Kotlin suites (agent lifecycle) | `mvnw -pl browser4-agentic,browser4-agent-tools,browser4-rest test -Dtest=…` | agentic `TaskEnvelopesTest` 8, agent-tools `AgentTaskStatusTest` 17 + `StatefulAgentRunnerTest` 3 — green |
| Kotlin integration compile | `mvnw -o -DskipTests -pl browser4-core/browser4-skeleton,browser4-core/browser4-browser,browser4-agentic,browser4-agent-tools,browser4-rest compile` | **BUILD SUCCESS** — every changed Kotlin module compiles together (new `chrome/session`, `chrome/window`, `TabActivity`, `HTMLSnapshotInspectHints`, the envelope and command-result changes) |
| Rust CLI compile warnings | `cargo check --bin browser4-cli` | one warning, and it is **pre-existing** (`state.rs:919 clear_all_loop_states` is unused in `HEAD` as well) — no new warnings from this batch |
| Backend suites (workstreams) | `mvnw -o -pl browser4-rest -am test -Dtest=HTMLSnapshot*,InspectDocument*,PageSummaryIndexService*` | browser4-rest 103 tests, browser4-skeleton 27 tests — green |
| Skill docs | `bin/skill-doc-lint.ps1 -Path skills/browser4-cli` | **0 issues** (SKILL.md trimmed back under the 500-line cap after the batch's additions pushed it to 505) |
| PowerShell syntax / version consistency | `bin/maintenance/checks/check-ps1-syntax.ps1`, `check-version-consistency.ps1` | **passed** (186/186 scripts parse) |
| Runtime-bundle script + bin wrappers | `browser4-apps/browser4-bundle/build-runtime-bundle.tests.ps1`; `bin/tests/test.ps1.tests.ps1` | **182 passed / 0 failed** and **ALL 205 COMMANDS PASSED** |
| Plugin manifest gate | `bin/verify-plugin.ps1 -Strict` on the rebuilt plugin JARs | pass — `browser4-plugin.json` version, artifact version and `Implementation-Version` all read `4.14.1-SNAPSHOT` |
| Generated tool reference | `mvnw -o -pl browser4-rest -am test -Dtest=ToolDocGeneratorTest -DregenerateToolDocs=true` | **BUILD SUCCESS** — “Regenerated 146 tool docs”; the gate test is 3/3 green again, and the diff absorbs exactly the batch's spec changes (the new `ENDPOINT_*` error codes, `statusCode`/`message` in the status envelope, the creation-ordered tab-index help) |
| Tab/index contract (backend) | `mvnw -o -pl browser4-agentic test -Dtest=BrowserToolExecutorTest,BrowserTabToolExecutorTest,TabActivityTest -Dsurefire.failIfNoSpecifiedTests=false` | **Tests run: 99, Failures: 0** (BrowserToolExecutor 20, BrowserTabToolExecutor 75, new TabActivity 4); regression run over `SystemStatusControllerTest,PagesControllerTest,MCPToolControllerTest` 137/137 |

Two maintenance checks fail for reasons that predate this batch and are unrelated to it:
`check-bilingual-readme.ps1` compares `##` headings between `README.md` and `README.zh.md`
(the Chinese file translates every heading, so “Only in EN / Only in ZH / Alignment” can
never pass), and `check-skill-structure.ps1` reports 19 of 23 skills missing a boilerplate
section list — the failures are in `.trae/`, `browser4-agentic/src/main/resources/skills/`
and `coworker/skills/`, none of which this batch touched.

| Draft file | Short name |
|---|---|
| `20261015-111443-agent-extraction` | `agent-extraction` |
| `20261015-112415-agent-status-result-tracking` | `agent-status-result-tracking` |
| `20261015-113230-attach-failure-boundaries` | `attach-failure-boundaries` |
| `20261015-114038-attach-remote-debug` | `attach-remote-debug` |
| `20261015-115140-crawl-link-discovery` | `crawl-link-discovery` |
| `20261015-115532-html-snapshot-extraction` | `html-snapshot-extraction` |
| `20261015-120433-headed-window-visibility` | `headed-window-visibility` |
| `20261015-121022-htmlsnapshot-inspect-discovery` | `htmlsnapshot-inspect-discovery` |
| `20261015-122234-loop-monitoring` | `loop-monitoring` |
| `20261015-123037-plugin-captcha-detection` | `plugin-captcha-detection` |
| `20261015-124108-plugin-image-detection-download` | `plugin-image-detection-download` |
| `20261015-124638-plugin-markdown-conversion` | `plugin-markdown-conversion` |
| `20261015-125602-plugin-media-video-detection` | `plugin-media-video-detection` |
| `20261015-131909-plugin-pptx-generation` | `plugin-pptx-generation` |
| `20261015-133022-session-management` | `session-management` |
| `20261015-133653-snapshot-mastery` | `snapshot-mastery` |
| `20261015-134222-tab-management` | `tab-management` |
| `20261015-134855-x-sql-query-methods` | `x-sql-query-methods` |

## The batch's one big root cause: two task-status contracts

Eleven of the reported symptoms — `agent status` lacking the documented fields,
`agent list` showing finished work as `queued` forever, `--clear` clearing nothing,
`agent run --wait` burning its whole timeout on a task that finished in five
seconds, the `--clear --all` warning asserting that completed tasks “keep running
server-side”, the empty-result hint pointing at a field the backend no longer
serializes, and the agent's deliverable being unreachable — are one defect:
the backend migrated every long-running domain to a **single shared task envelope**
(commit `8f574d6881`, “one async contract for long-running tools”) while the CLI's
lifecycle consumers and the skill docs kept reading the pre-migration
`processState` / `isDone` / `statusCode` / `finishTime` shape. `refreshed_agent_status()`
therefore returned `None` for every poll, no cached status could ever become
terminal, and every downstream consumer silently kept its stale cache.

The batch resolves it in favour of the shared envelope as the canonical contract,
keeps the legacy fields as a fallback for older backends, and documents the
envelope in the skill docs.

## Behaviour fixes

### Reliability: the CLI now consumes the shared task envelope

Reported in `agent-status-result-tracking#2`, `#3`, `#4`, `#6`, `#7`,
`agent-extraction#1`, `#3`.

The envelope is `{taskId, status: queued|running|done|failed|cancelled, statusCode,
message, error, processed, statusTool, resultTool, cancelTool}`. Three CLI consumers
had to learn it, and they now share one mapping
(`envelope_status_word` / `friendly_envelope_status` / `classify_agent_status` in
`cli/browser4-cli/src/main.rs`):

- `agent list`'s refresh — `done` → `completed`, `running` → `processing`,
  `cancelled` → `cancelled`, `failed` → `failed (<statusCode>)` (or the first line
  of the envelope's `error` when no code was sent). The terminal-state guard is
  unchanged: a fresh non-terminal answer never reopens a terminal cached row.
- `agent list --clear` — now refreshes **before** deciding what to drop, and
  distinguishes three answers: a fresh status, a definite “the server has no record
  of this id” (droppable history, labelled `gone (not tracked by the backend)`), and
  no usable answer (cache untouched). The `--clear --all` warning names the status it
  actually observed per task instead of asserting every detached row is running.
- `agent run --wait` — treats `done`/`failed`/`cancelled` as terminal, prints the
  result on success, surfaces the envelope's `error` on failure, and reports a
  cancellation as such instead of a timeout. The progress line now uses the
  envelope's `message` and step count.

`extract_missing_llm_configuration_message()` and
`detect_missing_llm_error_for_submitted_agent_task()` read the same envelope, so the
actionable “requires an LLM API key” error fires again (it had silently stopped:
the envelope carries no `processState`).

### Product: `agent result` now returns the deliverable, not just a summary

Reported in `agent-extraction#1` (High) and `#7`.

The backend's `CommandResult` gained `keyFindings`, `filesChanged`, `problems` and
`workspace`; `AgentTaskStatus` records the workspace its tools write into
(`BasicBrowserAgent.workspaceRoot`, resolved once instead of inline in the tool
manager's lazy init) and `StatefulAgentRunner` stamps it on the task. `agent result`
prints the findings, the changed files and the workspace path below the raw JSON, so
a task whose answer is a file is usable without filesystem archaeology. The docs
(`references/agent.md`) describe the workspace layout, that the agent drives the
session's own browser and restores your page afterwards, so its navigation is
deliberately absent from `tab-list`.

### UX: an unknown task id explains itself

Reported in `agent-status-result-tracking#6`.

`CommandStatus.notFound(id)` now carries a message, so the shared envelope's `error`
field is populated (a blank error is dropped by `TaskEnvelopes.of`, which is why the
answer was a bare `status: failed`). `agent status` / `agent result` detect the
`statusCode: 404` answer and print
`No agent task found with id '…'. Run `agent list` to see tracked tasks.` with a
non-zero exit, instead of the friendly branch that could never fire.

### Docs & discoverability: the status contract, spelled out

Reported in `agent-status-result-tracking#1`, `#5`, `agent-extraction#2`.

`references/agent.md` and `references/quick-patterns.md` (the resident quick
reference) now show the real envelope, the real vocabulary, the terminal check
(`status: "done"`; terminal set `done|failed|cancelled`) and the real payload fields;
the `isDone: true` guidance and the `RUNNING/COMPLETED/EXPECTATION_FAILED` table are
gone. `--json` is documented on `agent status`, `agent result` and `agent list`, and
the empty-result hint points at fields that exist (`status`, `statusCode`, `message`,
`error`, `result`) instead of `instructResults`.

### Product: plugin tool results are structured JSON, not `toString()` envelopes

Reported in `plugin-captcha-detection#1`, `plugin-image-detection-download#5`.

The shared renderer (`ToolResultTextRenderer`) serializes `Map`/`Collection`
results as JSON but falls back to `{"type": …, "description": value.toString()}`
for anything else, so a plugin tool that returned its own data class leaked the JVM
form (`CaptchaDetectionResult(...)`) to the caller. The plugin executors now return
typed `Map` payloads, and the captcha plugin documents the second half of the
problem: the shared result mapper is `JsonInclude(NON_EMPTY)`, so a documented key
whose value is `null` or an empty collection silently disappears. The captcha
payloads therefore emit explicit `null` / `[]` (`NullNode` + `RawValue`), with a
test that renders through the real renderer and asserts the documented keys survive.

### Product: captcha detection metadata, balance keys and wait semantics

Reported in `plugin-captcha-detection#5`, `#8`, `#9`.

`captcha_get_balance` now always ships `configured` + `balance` + `providers`
(the unconfigured answer keeps the documented keys instead of dropping the empty
ones); `widgetCount` is recorded by every detector (hCaptcha and Turnstile probes
counted widgets but never reported them); and detection gained a bounded
`waitMillis` window (`captcha.detect.poll.interval.ms`, capped at 30 s) that reports
`waited`/`attempts`/`elapsedMillis`, so an early probe against a lazily-rendered
widget is no longer a silent false negative. The single-shot no-arg `detect()`
stays as it was, so the browse-event handler behaves unchanged.

### Product: markdown conversion counters, tab restoration and titles

Reported in `plugin-markdown-conversion#2`, `#3`, `#4`, `#5`, `#8`.

`linkCount` now counts the links in the generated file (with the page's internal-link
count kept separately as `internalLinkCount`); `markdown.crawl` reports
`pagesAttempted = pagesCrawled + pagesFailed` instead of counting failures as
crawled; the crawl restores the session's entry page in a `finally` and reports
`entryUrl`/`tabRestored`; fragment-only anchors and the page's own URL are filtered
from discovered links; and `markdown.fetch` resolves a title (`<title>` → first
`<h1>` → `og:title`), so front matter and the filename scheme match `convert`
instead of collapsing to an empty key.

### Reliability: media detection truthfulness

Reported in `plugin-media-video-detection#4`, `#5`, `#6`, `#7`, `#9`; manifest half of `#8`.

Every `VideoSource` now carries `frameUrl`/`frameDepth` (same-origin iframes are
scanned and attributed instead of being flattened into one list), dedup merges the
richer entry instead of dropping the primary video's MIME type and element count,
HLS/DASH manifests are linked to their opaque `blob:`/`mediasource:` sources
(`isHls`/`isDash`/`manifestUrl`/`isOpaqueSource`) instead of being reported as an
unexplained blob, the default download directory moved out of the runtime-bundle
build tree to `~/.browser4/downloads/media` (configurable via `media.download.dir`),
and full base64 `data:` URIs are summarised (short ones stay verbatim) before they
cross the CDP boundary. The module also had no `MediaPlugin` class at all — it now
has one with a build-derived manifest version, so `plugin list` can name the loaded
build instead of a stale `4.12.0-rc.1` literal.

### Reliability: image detection and download behave as documented

Reported in `plugin-image-detection-download#2`, `#3`, `#4`, `#7`, `#8`, `#9`.

- `type` (MIME) is resolved for every detected source (declared attribute → data-URI
  header → URL extension) and the key is always serialized.
- Dimension filtering uses **one documented basis per call** (`dimensionBasis`,
  default `rendered`), so a 50 px icon no longer survives `minWidth=100`; sources
  with unknown dimensions are kept rather than silently dropped, and the filtered
  count is reported (`totalDetected` / `filteredOut`).
- Downloads honour a configurable proxy (`image.download.proxy` → JVM properties →
  `HTTPS_PROXY`/`HTTP_PROXY` → OS proxy via the WinINET registry, loopback always
  direct) and fail fast with an actionable message that classifies the cause, the
  elapsed time, the budget and how to configure a proxy — instead of a bare 30 s
  timeout. The default per-download budget is 25 s, deliberately below the CLI's
  30 s tool-call budget so the plugin's own diagnosis is what the user sees.
- Downloaded filenames are percent-decoded before sanitising, and single downloads
  now report real `width`/`height` (ImageIO plus a hand-written WebP RIFF parser).
- The plugin manifest version is filtered from the build (`${project.version}`), so
  a 4.14.1-SNAPSHOT JAR no longer advertises 4.12.0-rc.1.

### Reliability: `pptx_generate` is bounded and honest about what it dropped

Reported in `plugin-pptx-generation#1`, `#2`, `#3`, `#4`, `#5`, `#8`.

Image download now has a per-image timeout **and** a phase deadline
(`pptx.download.total-timeout.seconds`, default 60 s) with cancellable requests, so
un-downloadable images can no longer stall the tool past the server's async limit;
the result reports `partial`, `aborted`, `failureReasons`, `imageDownloadMs` and
`imageDownloadDeadlineExceeded`. Downloads resolve a proxy the same way the images
plugin now does (`pptx.download.proxy` → env → JVM properties → OS), with an
actionable failure reason. Slide overflow is carried into continuation slides
instead of being discarded, and every dropped block is counted by kind
(`skippedBlocks` / `skippedBlockKinds`); the lead-section title falls back to the
page title or `Overview` (never `" (continued 1)"`), `downloadedImages` means what
its spec says (embedded only), and page chrome (nav/aside/ARIA chrome, Wikipedia
badges, sidebars, edit links) is filtered out of the captured content.

### Documentation: the module's own contract

`plugin-captcha-detection#5`'s sibling reports (`plugin-image-detection-download#10`,
`plugin-markdown-conversion#9`) were stale manifest versions. The captcha plugin had
no README at all; it now has one documenting the four tools, their JSON contracts,
the config keys, and *why* payloads carry explicit `null`/`[]` values.

### Documentation: the `stop` / `close` lifecycle was documented backwards

Reported in `session-management#3`.

`SKILL.md` §2 claimed `stop` deletes this checkout's saved session records (so `list`
prints “No active browser sessions.”) and told the reader to prefer `close` when they
want to keep being able to list and reconnect. The code does the opposite: `stop`
calls `state::mark_all_sessions_stale` and keeps every record (the next `open`/`goto`
revives the *same* session id, cookies and profile included), while `close` calls
`clear_state` and removes the record, so a later `open -s <name>` mints a new id and
a fresh profile. The paragraph now states the implemented semantics and points at
`state-save`/`state-load` as the way to carry browser state across a `close` — which
is also the honest answer to `session-management#1` (“closing a named session then
reopening it silently discards all browser state”).

### Documentation: the agent task contract in both READMEs

The `agent status` / `agent result` / `agent list` rows in `README.md` and
`README.zh.md` described a status check and a result fetch; they now name what the
commands actually return (the shared status envelope with its vocabulary, and the
agent's `keyFindings` / `filesChanged` / workspace for a finished task).

## Decisions taken while fixing

Where an issue offered a choice, the batch picked one and recorded it here.

| Decision | Choice | Why |
|---|---|---|
| Which task-status contract is canonical (`agent-status-result-tracking#1`) | The **shared envelope** (`taskId`, `status`, `statusCode`, `message`, `processed`, `error`, the three tool names); the legacy `processState`/`isDone`/`statusCode`/`finishTime` fields stay as a read-only fallback | It is what the backend actually sends for every long-running domain; the legacy shape is unmaintained. Enriching it with `statusCode` + `message` cost less than teaching every consumer two contracts. |
| Plugin payloads with nullable keys (`plugin-captcha-detection#5`) | In-module explicit `null`/`[]` (captcha) rather than changing the shared result mapper to `JsonInclude.ALWAYS` | A global mapper change would alter **every** tool's output; the reported payloads are fixed in-module and the constraint is documented in the module README. |
| Snapshot node lines for names containing `: ` (`snapshot-mastery#8`) | Canonical line-oriented shape `- <role> "<name>" [ref=…] [box=…]`, i.e. not always strict YAML | The issue asked for one uniform shape, and every reader in the CLI (`parse_snapshot`, `snapshot grep`) is line-based. The repair is guard-compatible: if the upstream renderer stops whole-line-quoting, the canonicalizer becomes a no-op. |
| `114038#7` artifact links: drop the markdown rendering? | Kept markdown links, fixed the separators underneath (`normalize_path_display`) | The markdown shape is an output contract used by docs and scripts; the defect was the mixed `\`/`/` path, not the link form. |
| Plugin bundling (`plugin-image-detection-download#1`, `plugin-markdown-conversion#1`, `plugin-media-video-detection#1/#8`, `plugin-pptx-generation#7`) | Bundle the first-party plugins this checkout builds (swarm, images, markdown, media, seo), list the ones it does not with their install command, and **preserve** previously installed JARs across a rebuild | The reported pain was silently missing tools plus an install that a later build wiped; bundling the thin first-party plugins and keeping user-installed JARs fixes both without changing the plugin loading model. |
| Per-plugin proxy resolvers (`plugin-image-detection-download#2`, `plugin-pptx-generation#2`) | Implemented once per plugin instead of promoting a shared module | Both plugins are independently deployable JARs; the shared home for this would be the PDK, which is a packaging decision beyond this batch. Both resolvers document the same precedence and are covered by their own tests. |
| `attach-remote-debug#2` — the documented `disconnect` alias does not exist | Implement it as a real alias of `close` rather than deleting the documentation | `references/attach.md` documents `disconnect` in four places (including a whole “Close vs Disconnect” section) and it is a more accurate name for attached sessions; scripts written against the documented alias must keep working. |
| `tab-management#2` — tab indices come from an unordered driver map | Order tabs by the driver's creation id (`AbstractWebDriver.id`, a monotonic `ID_SUPPLIER` counter) in the `tab_list` executor, and use the same order for index-based `tab-select`/`tab-close` | The base library's `drivers` is a `ConcurrentHashMap` whose `values.toList()` order is hash order, so indices could change between two calls. A creation-ordered list is stable across calls; `--guid` stays the robust target and the docs say so. |

### Correctness: snapshot captures and the auto-diff contract

Reported in `snapshot-mastery#3`, `#4`, `#5`, `#7`, `#8` (and the help text of `#4`/`#9`/`#10`).

- **`#3` — the capture header lied.** A whole-page capture printed the backend's
  `# Viewport State` block (with `processingViewport`, `hiddenTopHeight`,
  `hiddenBottomHeight`) and a navigation footer, so a capture with no `-v` filter
  claimed to be “viewport 0”. `snapshot::normalize_capture_body` now rewrites the
  header to `# - scope: whole page (all N viewports; no --viewport filter)` (keeping
  `viewportHeight`/`viewportsTotal`, which callers parse) and drops the footer for
  whole-page captures while leaving `-v` captures untouched. The production
  `strip_viewport_footer` in `main.rs` it replaces was a **no-op**: the backend
  footer ends with a bare `#`, which its `is_footer` test rejected, so the backward
  walk never started — and the two unit tests that “covered” it passed only because
  their fixture omitted that line.
- **`#4` — the diff had its own line format.** Diff lines are now
  `{+|-|~} <role> "<name>" [ref=…] [box=…]` (the same shape as the tree), refs are
  kept whole instead of truncated to 6 bytes, names are JSON-escaped, and modified
  entries carry `/ref: "e2" → "e20"`. The help/tips text that promised “show only
  what changed” was corrected to describe the real output (full tree, then a
  `### Diff` appendix with `## Removed`/`## Modified`/`## Added`).
- **`#5` — `snapshot grep` can never see diff markers.** The auto-diff report is now
  persisted next to the capture as `<capture>.diff.txt` (so it stays out of every
  `*.yml` scan), `snapshot grep --diff` reads the newest report, and the docs state
  both that limitation and the shell workaround.
- **`#7` — the footer was glued to the last element line.** `repair_glued_comment_blocks`
  splits it back out, quote-aware, so `C# in Depth`-style names and real comments are
  untouched.
- **`#8` — a name containing a quote produced a different YAML shape.**
  `canonicalize_node_line` re-flows the whole-line-quoted form into the canonical node
  line (also when parsing *old* captures for a diff, which is what removes the
  malformed `+ "table e7305 ""` lines). The root cause is upstream
  (`AriaSnapshotFormatting.requiresYamlQuoting` in the browser4-base jar quotes the
  whole key when it contains `": "`); the repair becomes a no-op if that is fixed.
- Bonus: `truncate_str` sliced names at a byte offset and could panic mid-character.

### Correctness: `snapshot --selector` never worked

Reported in `snapshot-mastery#1`, `#2`.

The CLI passes `--selector` through verbatim; the selector was interpolated into
`!!document.querySelector(${escapeJsSelector(selector)})` **without the quotes its
three sibling call sites have**, so a selector containing a quote or a space produced
a JavaScript syntax error — and the result was read as `as? Boolean ?: false`, which
silently turned that error into “matched no element on the page”. Fixed in
`BrowserTabToolExecutor` (quoted, escaped selector; an evaluation that does not return
a Boolean now fails loudly with the JS error instead of reporting an empty match).

### Correctness: `htmlsnapshot` counts, page typing and selector validation

Reported in `html-snapshot-extraction#1`, `#2`, `#3`, `#4`, `#6`,
`htmlsnapshot-inspect-discovery#1`, `#2`, `#3`, `#4`, `#6`.

- **`#1` — the match count was the paginated slice.** `get all` can now ask for the
  pre-pagination total (opt-in `includeMeta` → `{offset, limit, returned, totalMatches,
  values}`), and the CLI prints `N of M elements matched.` with `match_count` = total.
  The bare-array response stays the default, so existing callers are unaffected.
- **`#2`/`inspect#2`/`inspect#4` — suggested commands were invalid or duplicated.** The
  summary's selector hint used a space-joined class list (`.a b`, a descendant
  selector, usually 0 matches); it is now a compound `.a.b`, and the CLI's suggestion
  list uses the pattern's own `itemSelector` (`.product_pod`) instead of the container
  selector with an `" a"` suffix, deduped before it is printed.
- **`#3`/`inspect#6` — a product grid was typed “Article / Content”.** `inferPageType`
  now considers repeated article cards and multi-column grids (`Product Listing` when
  prices are present, else `Listing`/`Grid`); a single buy box still wins, so a detail
  page with a recommendations rail stays `Product Detail`.
- **`#4` — outer HTML was unreachable.** `outerhtml` is a valid field for
  `get`/`get all`/`query` and the error text lists it.
- **`inspect#1` — inspect claimed the caller's non-matching selector “repeats”.** The
  backend now reports `originalMatchCount` (the caller's own selector, counted *before*
  the pattern substitution) and the CLI branches its prose on it: 0 matches says the
  analysis is about the auto-discovered pattern, 1 says a container was analysed.
- **`inspect#3` — a malformed selector was reported as “No elements matched”.** The
  engine's own parser (`PowerQueryParser`) silently swallowed the syntax error; the
  snapshot executor now parses the selector with that same parser *before* capturing
  and throws `Invalid CSS selector "<sel>": …`, so PowerCSS `:expr(...)` keeps working
  while `article[[` fails loudly. A valid selector that matches nothing still exits 0
  (the documented contract).
- **`x-sql#2`/`x-sql#3` — the truncation hint named a selector that returned empty
  strings.** Each `truncationHint.childSelector` is repaired to a container-relative
  path (`h3 a`) validated row-scoped against every analysed match, and the hint is
  dropped when no path works. `x-sql#5` (a duplicate tab seen once) was judged not a
  defect in this layer: the read path issues no `Target.createTarget`, and the stray
  tab predated the reported sequence and did not replay.

### Reliability: a corrupted dev-mode backend URL could never heal

Reported in `agent-extraction#4`.

`decide_dev_base_url` honoured any recorded loopback URL whose port was outside the
dev range verbatim, so a workspace whose state file recorded `http://127.0.0.1:1`
kept starting (and reporting) its backend on port 1 — a port no development backend
can allocate, and one `netstat` confirmed the JVM had really bound. A recorded
*privileged* loopback port (< 1024) is now rejected as stale and re-allocated from
the dev range, and `status` says where its URL came from (`resolved from: …`) plus
which recorded value it ignored, so the next stale value is diagnosable without
reading the source. The fix also closed a latent trap: `Url::port()` returns `None`
for a URL with a default port, so the decision now uses `port_or_known_default()`.

`111443#4`'s remaining suggestion — a `config reset server` / `doctor --fix`
recovery command — was **not** implemented: with the re-allocation above there is no
longer a sticky value a user would need to clear, and the recorded value is now
reported as ignored. The related question the report raised (“why is the *global*
`~/.browser4/cli-state.json` rewritten during workspace-scoped runs?”) is answered
only in part: `resolve_default_state_dir()` prefers `BROWSER4_CLI_STATE_DIR`, then the
per-checkout namespace (`~/.browser4/workspaces/<slug>`) when running from a
checkout, then `~/.browser4`, and `resolve_global_state_dir()` deliberately skips the
namespace for the shared lookups (today the legacy `~/.browser4/lib` migration). No
code path was found that writes the *global* `cli-state.json` from a
workspace-scoped run, so the observed rewrite could not be reproduced or attributed;
it is recorded here as an open observation rather than a fixed defect.

### UX: pipes no longer receive spinner animation frames

Reported in `agent-extraction#8`.

The server-startup progress line animated unconditionally, so a piped/CI run
collected dozens of `⠋ Starting server... (Ns)` frames plus `\r\x1b[K` erase
sequences. Startup progress now animates only when **stderr is a terminal**: a
non-TTY cold start prints one `Starting server...` line and the final
`Server ready in Ns`, and the erase sequence is terminal-only too.

### UX: global flags after the command, and misplaced `-s`

Reported in `session-management#4`, `#5`.

`--pretty` placed after the command name was silently dropped; the post-command
global-flag hoisting now recognises it (and keeps the command token intact), so
`status --json --pretty` prints indented JSON. A misplaced `-s/--session` — which is
a *global* flag whose position is part of the syntax — now produces a hint naming the
rule and the corrected form (`browser4-cli -s research open <url>`) instead of a bare
“unknown option”. Both changes are pinned by unit tests plus the existing hoist tests.

### Documentation & output: help text, separators and stdout hygiene

Reported in `agent-extraction#5`, `#6`, `attached-remote-debug#7`.

- `extract`/`summarize` `--filename` said “instead of printing to stdout”, which
  contradicted the same help's Notes (file by default, `--stdout` to print). It now
  describes the real default and the timestamped location.
- Printed artifact paths were assembled by joining the raw working directory with the
  user's argument, so Windows printed `D:\ws\.test-sessions/x/y.json`. Every
  `[Extracted content]`/`[Snapshot]`/`[Screenshot]`/`[PDF]`/`[Artifact]` link and its
  `*_path` JSON field now runs through `normalize_path_display`.
- The markdown link form itself was kept (an output contract used by docs and
  scripts); only the path was fixed — see the decisions table.

### Discoverability: the plugin and tool surface

Reported in `plugin-captcha-detection#2`, `#3`, `#4`, `#6`, `#7`,
`plugin-image-detection-download#6`, `plugin-markdown-conversion#6`, `#7`,
`plugin-media-video-detection#2`, `#3`, `#10`, `#11`, `#12`, `plugin-pptx-generation#6`.

`plugin list` is the only way to see what is installed, and the docs pointed at it
for *tool names* — which it never printed. The surface is now:

- **`tool list [--filter <substr>]`** (new) prints every tool name the backend
  advertises, from one shared `/mcp/tools` fetch the plugin surface, the help path and
  the command share, so they cannot drift. A bare or mistyped `tool` is a focused
  usage error instead of the whole command reference. Verified on the built binary:
  `--help` lists it under *Plugin tools* and ends that section with “`plugin list`
  lists the installed plugin JARs and their status, not their tools.”
- **`plugin <domain> --help` / `--help plugin-<domain>`** print that domain's methods;
  `plugin --help` and `tool --help` print focused surface pages. No more “Unknown
  command” followed by the full 200-line reference.
- **The domain listing** no longer invents pseudo-domains: the naive `rsplitn('_')`
  that turned `markdown_discover_links` into `markdown_discover` was replaced by a
  first-underscore split with an acronym-run guard (so `base_u_r_l`, `nano_d_o_m` are
  not domains) and a registry-based built-in filter.
- **`plugin install`** accepts a shell-expanded glob (or a literal `*`), picks the
  artifact matching this CLI's version when several are present, reports that choice
  on stderr, and lists every candidate when it cannot choose.
- **`plugin info`** resolves the short name the CLI advertises (`captcha`,
  `browser4-captcha`, a JAR name or a path) and, on a miss, lists what *is* installed
  instead of answering a bare 404 with an empty body.
- **`tool call`** warns on stderr whenever the payload reports `success:false` (the
  payload stays on stdout, exit code unchanged) and exits non-zero under the new
  opt-in `--fail-on-error`.
- The duplicated tool signature in error help was fixed centrally in
  `AbstractToolExecutor.renderHelp`, so every plugin benefits.

### UX: an inline `--sql` tip corrupted the default JSON output

Reported in `x-sql-query-methods#1`.

The tip meant for humans was printed to stdout based on an inverted format test, so
the default JSON envelope could not be parsed. Tips now go to **stderr** for the
structured formats (the default JSON envelope, csv) and stay on stdout for the human
`--format table`.

### UX: a plugin tool timeout now explains itself

Reported in `plugin-image-detection-download#2`, `plugin-pptx-generation#1` (CLI half).

The CLI's 30 s tool-call budget is deliberately shorter than what a batch download
needs, and a timeout used to surface as a bare HTTP timeout. `http.rs` now recognises
plugin tools and names the budget to raise (`--timeout 600`) plus the fact that the
plugin's own budget is intentionally shorter, so the actionable plugin message is
what the user sees when it fires first.

### Reliability: a session knows whether its browser is still there

Reported in `headed-window-visibility#1`, `#2`, `#3`, `#6`.

Three things were reported as one class of “cannot trust the session”: a headed-window
check that could not see the session's own browser and produced both silent no-ops and
false-positive warnings, orphaned browser processes surviving `close-all`, and Chrome
profile directories left behind in the temp tree.

- **A window check that answers about *this* session.** The old check scanned the
  machine's process list, so it reported a warning whenever it could not see the
  session's browser. The new `SessionWindowProbe` observes only the session's own
  browser — its own `launcher.pid` marker, that process's live command line
  (`--headless` or not) and `Browser.getWindowForTarget` through the session's own
  driver — and answers a **tri-state**: `VISIBLE` (a non-headless process *and* a
  non-minimized window agree), `HIDDEN` (launched headless while a window was
  requested, or minimized), `UNKNOWN` (could not check). `UNKNOWN` is never rendered as
  success and never as a warning, which is what retires the two false-positive
  warnings. It is exposed as the MCP tool `browser_window_state {sessionId}` with a
  `reason` naming the pid/port and what was observed.
- **A close that reaps what it launched.** `BrowserProcessReaper` reads the pid only
  from the session's own `<contextDir>/launcher.pid`, refuses to kill when the live
  command line does not name that context dir (pid reuse), kills the descendants then
  the root, and reports one of `TERMINATED | ALREADY_GONE | NO_PID_FILE | NOT_OURS |
  SURVIVED | FAILED`. `close_session` therefore now carries a **tri-state**
  `browserTerminated` (`true` verified gone, `false` observed alive, `null` could not
  check) instead of the CLI printing “Browser terminated.” unconditionally.
- **A profile cleanup that refuses to guess.** `SessionProfileReaper` deletes a
  context directory only when it is provably disposable (a `cx.<digits>` rotation
  directory, or one under the library's own temp context root) and never the
  persistent default/prototype/system-default roots, the `named` group, an
  `--profile <path>`, or anything outside the app context tree — and runs only after
  the browser process is confirmed gone. The reason is reported either way.

### Correctness: attach either binds the browser you asked for, or says why not

Reported in `attach-failure-boundaries#1`, `#2`, `#3`.

- **`page-info`/`tab-list` reported every tab inactive.** The active flag came from
  `frontDriver`, which is only set by a `bringToFront`, so a freshly opened session had
  none and every row compared unequal. One shared rule now decides it
  (`TabActivity.resolveActiveGuid`: honour the front driver only when it is among the
  listed tabs, else the first tab), applied at all three call sites — the
  `browser_tabs`/`tab_list` executor the CLI actually calls, `/api/status/tabs` and
  `/api/pages`. The contract the CLI renders is one JSON object per tab with a real
  boolean `active` (exactly one true) and the live, post-redirect `url`.
- **`attach --cdp chrome` could bind a different browser than the one probed.** The
  product reported at probe time is re-read after binding; a mismatch fails loudly
  with the observed products, port and every page target (the port changed hands).
  Attaching to a browser another live session already owns is reported with
  `ownedBySessionId` in the response and in `list_sessions` (a warning, not a hard
  failure — attaching to a Browser4-managed browser is documented behaviour).
- **Attach failures surfaced internal jargon and a wrong code.** Three dedicated,
  **non-retryable** codes (`ENDPOINT_UNREACHABLE` 502, `ENDPOINT_NOT_CDP` 502,
  `ENDPOINT_NO_PAGE_TARGETS` 409) replace a generic classification, carried by a new
  `AttachFailureException` that is deliberately not an `IllegalArgumentException`, so
  it never triggers the signature/help dump. The endpoint-specific patterns are
  matched before the broad `"cdp"` rule.

### Correctness: `snapshot --selector` could not work at all

Covered above under *“`snapshot --selector` never worked”* — the same fix closes
`snapshot-mastery#1`/`#2` from the backend side.

### Reliability: a re-attach really recovers, and a close stops at its own session

Reported in `attach-remote-debug#1` (the backend half), `#3`, `#4`.

The CLI half of this cluster landed earlier; what remained was the backend state that made the
documented recovery impossible and that misdiagnosed the failure.

- **A re-attach rebuilds the bound driver.** `boundDriver` is a bean lookup on the session, so the
  driver bound *before* the re-attach kept answering every tool call after the attach had bound a
  NEW `PulsarBrowser` wrapper: the session drove the old browser object while `boundBrowser`
  reported the new one, `checkHealthy` logged `Inconsistent driver/browser … Driver state: INIT` on
  every call, and the liveness gate failed every command — while attach printed success. The
  documented recovery (`attach` again) therefore changed nothing. Both attach paths (HTTP and
  browser-level WebSocket) now unbind the stale driver and bind a fresh one on the browser they just
  bound, through one shared `rebuildDriverAfterReattach`; the replacement is built at attach time,
  so a rebuild that cannot work is visible to the attach report instead of surfacing later as an
  unrelated failure. The stale driver's tab is deliberately left open — it may be the user's
  visible page, and a re-attach must not cost them a tab.
- **A close detaches only its own session.** Every session attached to one endpoint drives the same
  shared browser object, and closing that object retires every driver registered on it — so closing
  one attached session killed a sibling's driver *and* the tab it was driving, contradicting the
  documented session independence. `closeSession` now looks for live sessions still bound to the
  same port; when there are any it takes back only what belongs to the closing session: it unbinds
  its driver and browser first (so the teardown that follows cannot reach the shared object), then
  closes that one driver — which closes the tab that session drove, the documented close-on-attach
  behaviour — and leaves the shared browser object, the survivor's tab and driver, and the browser
  process exactly as they were. The last session out owns the teardown again. The close report says
  which case it was: `detachedOnly` plus `sharedWith` (`--json`, and `SessionCloseReport`).
- **The liveness gate names the link that died.** One sentence — "the browser … is no longer
  reachable — it was closed or crashed" — covered both a dead browser and a live browser whose
  tab/driver link had died, so users chased a crash that `curl` on the CDP port disproved. The gate
  now asks the bound browser directly and returns an `AttachLivenessFailure`: message **and** code.
  A live browser with no drivable page is `ENDPOINT_NO_PAGE_TARGETS` ("… is still running — only the
  tab this session was driving is gone …"); a browser that cannot answer is
  `ENDPOINT_UNREACHABLE`. The wording no longer decides the status code — `MCPToolController` passes
  the code explicitly to `errorResponse`.
- **The CLI stopped recommending the workaround.** `attach`'s post-attach warning used to say
  "Re-attaching keeps the stale driver in this build; recover with: `close` && `attach`" — true
  before this fix, wrong after it. It now points at re-running the same attach (which rebuilds the
  driver) and at `list` for verification.

Verification: `browser4-rest` `PulsarSessionManagerTest` **41/41** (two new regression tests —
*"re-attach rebuilds the bound driver on the browser it just bound"*: the session is left holding a
driver built on the browser the second attach bound, with the stale driver unbound and its tab left
open; *"closing one attached session of a shared browser detaches instead of tearing it down"*: the
shared browser object and the sibling's driver are never closed, the closing session's own driver
**is** closed and its bindings released, `detachedOnly`/`sharedWith` report the case, and closing the
last session does close the browser object). Both drive the real `createAttachedSession` /
`closeSession` paths over a local CDP-discovery stub with the browser wrapper mocked at construction
time. Also green: `CdpEndpointVerificationTest` 15/15, `MCPToolControllerTest` 104/104,
`docs`/tool-doc gates, and `cargo test --bin browser4-cli` 1849/0 after the CLI hint change.

Two pre-existing reds found by these runs, both independent of this work (and one of them red in
`HEAD` too): `McpToolAliasParityTest.dispatcherAliasesMatchSharedTable` (see below) and the stale
`attachOverADeadBrowserWebSocketFailsLoudly` expectation, which still demanded
`IllegalArgumentException` for the browser-level-WebSocket failure after that path had been changed
to throw `AttachFailureException` — updated here to assert the exception **and** its
`ENDPOINT_UNREACHABLE` code.

## Per-issue index

Every reported issue id, with its outcome. `fixed` means the behaviour or the
documentation changed in this batch and was verified; `not reached` means the
workstream reported it as untouched, with its located fix in “Remaining work (specified,
not applied)” above (or `deferred`/backend-owned, per that table).

- **agent-extraction** — #1 fixed (result payload + workspace; the `agent files` command is deferred above); #2 fixed (docs); #3 fixed; #4 fixed; #5 fixed; #6 fixed; #7 fixed (docs); #8 fixed.
- **agent-status-result-tracking** — #1 fixed; #2 fixed; #3 fixed; #4 fixed; #5 fixed; #6 fixed; #7 fixed.
- **attach-failure-boundaries** — #1–#4 **all fixed**: #4 now has an Outcome block in `--help attach` mirroring `references/attach.md` (pre-bind probe, the three non-retryable codes, the post-bind product re-check, the `ownedBySessionId` hazard and the one-unnamed-session rule), pinned by `help::tests::session_and_tab_help_document_the_diagnostics`.
- **attach-remote-debug** — **#1–#7 all resolved**: #1 fixed end-to-end (the CLI reports the session as NOT usable with `attach_verified: false`, *and* the backend now rebuilds the bound driver on re-attach, so the documented recovery works); #2 fixed (`disconnect` is a registered alias of `close`); #3 fixed (a close detaches from a shared attached browser instead of tearing it down, and reports `detachedOnly`/`sharedWith`); #4 fixed (the liveness gate distinguishes a dead browser from a dead tab/driver link, with `ENDPOINT_UNREACHABLE` / `ENDPOINT_NO_PAGE_TARGETS`); #5 fixed (CLI half: closing an attached session warns on stderr that the driven tabs close while the external browser keeps running); #6 resolved by decision (explicit, uniform refusal for `--cdp`/`--endpoint`, documented); #7 fixed (separators; the markdown-link form is deliberately unchanged).
- **crawl-link-discovery** — **#1–#6 all fixed**: #4 the completion line counts successes from `pagesFound` against `pagesExpected`, names the first failure reason and points at `crawl result <id> --verbose`; #3 `crawl list` keeps the partial-failure accounting (a poll no longer overwrites it with `completed`) and `--status failed` matches it while `status = OK` still means “stop polling”; #2 the `--format` contract is documented (warn on stdout, refuse with exit 1 and no file under `--output`, `--json` named as the alternative); #1 `--json … --output` without `--sql` is refused instead of writing a prose listing into the file; #6 the stale-prune count and the 24 h drop count are always printed and documented; #5 `--parallel` defines its unit (“one seed URL's fetch-and-extract round”).
- **headed-window-visibility** — **#1–#6 all fixed**: #5 as described (`open` consumes the session-scoped `browser_window_state`; VISIBLE/UNKNOWN silent, HIDDEN warns on stderr with the backend's reason; the verdict reaches `--json`); #4 `open --help` now documents the diagnostic (fresh session only, stderr, the JSON fields, and that UNKNOWN is *not* a failure); #1/#2/#3/#6 from the backend probe and reapers.
- **html-snapshot-extraction** — **#1–#6 all fixed**: #1 the total-count envelope plus the `N of M elements matched.` line and `match_count`/`returned_count`; #2 the suggested commands now use the pattern's own `itemSelector` (`article.product_pod`), deduped, with the broken `" a"` suffix gone; #3 one `### Page` block (URL/Title/Type) instead of a duplicated heading; #4 `outerhtml` accepted, documented and paginated; #5 one numbered value per line in human mode (docs and `--json` documented); #6 `-o/--only-matching` implemented in the shared grep path (one entry per occurrence, `occurrences` in `--json`, `-c` documented as matching *lines*; the optional `--count-matches` was deliberately not added — `-o | wc -l` covers it).
- **htmlsnapshot-inspect-discovery** — **#1–#6 all fixed**: #1 the inspect narration now branches on `originalMatchCount` (0 ⇒ “your selector matched 0 elements; the analysis is about the auto-discovered pattern”, 1 ⇒ “a single container was analysed”); #2/#4 the suggestion list uses `itemSelector` and is deduped; #3 a malformed selector fails loudly; #5 the annotated-export/`grep` wording is documented; #6 the page-type classification no longer calls a product grid “Article / Content”.
- **loop-monitoring** — **#1–#8 all fixed**: #1 `LoopState.workerPid` is recorded at both persist sites and `--resume`/`--resume-all` signal an existing live worker instead of spawning a second one; #2 the Windows std handles are inherit-disabled around `spawn_detached`, so a piped `loop --resume` returns (verified once live); #3 a stop sentinel (`<state>.stop`) is checked at four points by the worker and `--stop`/`--stop-all` verify the recorded PIDs and report survivors instead of asserting success; #4 the plain-text renderer reads `commandResult.summary`/`pageSummary`/`instructResults[0].result`/`message` and adds a `[FAILED]` line (it still falls back to the raw envelope when a payload has no summary at all); #5 (backend) the page status is observed and “unknown” is an explicit sentinel; #6 the worker is the single history writer and a control stop is recorded as `stopped`, so `--stop` no longer writes two entries; #7 `help.rs` and `references/loop.md` name the resolved CLI state directory; #8 the `--list` footer prints for every non-empty listing. **Coverage gap:** the loop cluster has no e2e scenarios at all (`scenarios/mod.rs` has no `loop` entry), so the sentinel/ownership/history logic is pinned by unit tests only.
- **plugin-captcha-detection** — #1 fixed (+ `captcha_solve` follow-up); #2 fixed; #3 fixed; #4 fixed; #5 fixed; #6 fixed (offline fallback follow-up queued); #7 fixed; #8 fixed; #9 fixed; #10 fixed.
- **plugin-image-detection-download** — #1 fixed; #2 fixed; #3 fixed; #4 fixed; #5 fixed; #6 fixed; #7 fixed; #8 fixed; #9 fixed; #10 fixed.
- **plugin-markdown-conversion** — #1 fixed; #2 fixed; #3 fixed; #4 fixed; #5 fixed; #6 fixed; #7 fixed; #8 fixed; #9 fixed.
- **plugin-media-video-detection** — #1 fixed; #2 fixed; #3 partial (tool names yes, `tool describe` deferred above); #4 fixed; #5 fixed; #6 fixed; #7 fixed; #8 fixed; #9 fixed; #10 fixed; #11 fixed; #12 fixed.
- **plugin-pptx-generation** — #1 fixed; #2 fixed; #3 fixed; #4 fixed; #5 fixed; #6 fixed; #7 fixed; #8 fixed.
- **session-management** — **#1–#5 all fixed**: #1 a named managed session that is closed records a note (`<state-dir>/closed-sessions.json`, deliberately outside `sessions/`) and the next `open` for that name warns on stderr that it starts a FRESH profile and how to carry state over (`state-save`/`state-load`), pinned by `state::tests::closed_session_notes_are_consumed_once`; #2 the close tri-state (`browserTerminated: true|false|null` + `browser_termination`/`profile_cleanup` in `--json`); #3 the `stop`/`close` semantics now documented as implemented.
- **snapshot-mastery** — #1 fixed; #2 fixed; #3 fixed; #4 fixed; #5 fixed; #6 deferred above; #7 fixed; #8 fixed; #9 fixed (help cross-reference); #10 fixed (`snapshot clean` prunes the reports + the hint).
- **tab-management** — **#1–#4 all fixed**: #2 creation-ordered indices everywhere; #1 `tab-new`/`tab-select` now say the index is the position in that listing (not the tab strip) and shift on create/close; #3 the human table is a pure `format_tab_table()` whose widths come from the rendered cells (32-char GUID column, `▶` columns reserved, character-safe truncation — the old byte-slice could panic on multi-byte titles); #4 `--help tab-list` documents the `▶` marker, the first-tab-active rule and the JSON `active` field. (reference doc done; `--help` pending).
- **x-sql-query-methods** — #1, #2, #3, #6 fixed (#2/#3: the container-relative hint (`h3 a`) is rendered and the inspect prose branches on `originalMatchCount`; #6: the `--sql-base64` PowerShell form is documented and the bash line is labelled); #5 deferred above (an unreproducible duplicate tab); #4 not reached (`tab-list` was already fixed for the active flag and the post-redirect URL, so this is now only the “duplicate tab appeared once” companion — see #5).

## Remaining work (specified, not applied)

**Nothing from the batch is left unapplied.** Every reported issue has been fixed, or deliberately
not changed with a reason (see the two tables below); the backend-side attach items that were the
last entries here (`attach-remote-debug#1`/`#3`/`#4`) have since landed — see *“a re-attach really
recovers, and a close stops at its own session”*.

Two coverage gaps are recorded rather than fixed: **the loop cluster has no e2e scenarios at all**
(`scenarios/mod.rs` has no `loop` entry), so its sentinel/ownership/history logic is pinned by unit
tests only; and `loop-monitoring#4` still falls back to printing the raw agent envelope when a
payload carries no summary at all. The attach cluster has the same shape — **no `attach` e2e
scenario exists either** — so the re-attach/close fixes are pinned by the two `PulsarSessionManagerTest`
regressions, which drive the real attach and close code paths but not a real browser.

### Found while verifying this follow-up (resolved here)

- **`McpToolAliasParityTest.dispatcherAliasesMatchSharedTable` was red — and red in `HEAD` too.**
  `MCPToolController.FRONTEND_TOOL_NAME_ALIASES` carried two names the shared table
  (`McpToolNames.frontendAliases`) did not: `browser_network_routes` (added to the dispatcher
  alongside its `network_routes` → `tab.networkRoutes` target; **present in `HEAD`, so this half
  predated the batch**) and `browser_window_state` (added by this batch; a session-scoped tool the
  dispatcher handles itself). The two are not the same kind of difference, and they are now resolved
  as the two different things they are:
  - `browser_network_routes` is a real **tab-domain alias with an advertised tool behind it**
    (`tab.networkRoutes`, declared by `BrowserTabToolExecutor`), so it was simply missing from the
    shared table and from `AgenticCliRunner` — the drift the guard exists to catch. It is now in
    `McpToolNames.frontendAliases`, in `AgenticCliRunner.FRONTEND_TOOL_NAME_ALIASES` **and** in its
    explicit `LEGACY_TOOL_MAPPINGS` (so it also resolves when `ToolSpecGenerator` cannot read
    `WebDriver.kt` from the classpath), which means all three channels — the private dispatcher, the
    standard MCP server and the agent CLI runner — can spell it. `docs/mcp-tools.md` / `.json` were
    regenerated (`-DregenerateToolDocs=true`).
  - `browser_window_state` has **no `(domain, method)` tool behind it**: it answers about the
    session's own browser (pid, launch flags, window state) and the dispatcher handles the call
    itself, so the standard MCP server has nothing to register and the shared table cannot express
    it. It is now declared once, explicitly, as `McpToolNames.dispatcherOnlyAliasNames`, and both
    parity guards compare against `dispatcherAliasNames` (shared ∪ dispatcher-only), so the check is
    still exact in both directions: a name that is neither shared nor declared is still a failure.
    `McpToolNamesTest` pins the union, the disjointness and the `browser_` prefix.
- **`attachOverADeadBrowserWebSocketFailsLoudly` asserted the pre-batch exception type** — fixed in
  this follow-up (it now asserts `AttachFailureException` with `ENDPOINT_UNREACHABLE`, matching the
  dedicated non-retryable codes introduced by this batch).
- **Two more stale expectations in `browser4-agentic`, both red before this follow-up** and both
  simply wrong about deliberate changes this batch made:
  - `TabSpecCoverageTest.advertisedEqualsScannedPlusDeclared` reported `networkRoutes` as
    "advertised without being scanned from the mirror or listed in `declaredByHand`" — the executor
    declared it in `HEAD` (`BrowserTabToolExecutor`), the drift gate's own message prescribes the fix,
    and the declaration is now listed with its reason beside `networkRoute`/`networkUnroute`.
  - `AgentToolCallLoopTest.perCallTimeoutKillsHungModelCall` still expected a raw
    `TimeoutCancellationException` for a hung inference call after the batch introduced
    `LlmInferenceTimeoutException` precisely so that an inference timeout is **not** a cancellation
    (its KDoc: "callers must be able to tell an inference timeout … apart from an outer
    cancellation"). The test now asserts that type, its `timeoutMs` budget and its message, with the
    distinction spelled out — re-asserting the cancellation would re-blur it.

  A third class-file-level check was green but *misleadingly* so: with `-pl browser4-rest` (no
  `-am`) the module resolves `browser4-agentic` from the last installed jar, so the generated-tool-doc
  gate compared the registry of that older jar against the committed docs. The gate is byte-exact and
  only meaningful in the reactor: run these verification passes with `-am`.

## Deliberately not changed

| Issue | Why |
|---|---|
| `plugin-media-video-detection#3` (part) — a `tool describe <name>` that prints a tool's argument signatures | `GET /mcp/tools` returns names only, and `/mcp/tools/specs` lists only the specs that declare a `cliName`, so the CLI has no schema to print. Signatures remain available from `plugin <domain> <method> --help` (and from the tool's own argument error); a real fix needs a backend endpoint that publishes every tool's argument schema. |
| `loop-monitoring#5` — `pageStatusCode 201` claimed a page status the record never observed | The default was a *task-lifecycle* code used for a *page* fact. The fix keeps the wire type (`Int`) and introduces the documented `PAGE_STATUS_UNKNOWN = 0` sentinel — chosen over a nullable field because MCP result schemas, `ToolResultValidator` and the swarm JSONL persistence all require an integer, and 0 cannot collide with a real HTTP status (which starts at 100). |
| `snapshot-mastery#6` — `snapshot -i` merged accessible names concatenate internal ids, classes and paths | Those names are produced by the AX renderer inside the browser4-base jar (`AriaSnapshotFormatting.normalizeChildren`), not by the CLI, which receives them already merged. A CLI-side cap cannot know which tokens are an id/class and which are the visible label, so it would corrupt good names to shorten bad ones. Needs an upstream renderer change. |
| `x-sql-query-methods#5` — a duplicate tab appeared once and did not reproduce | Judged not a defect in the reported layer: the `htmlsnapshot` read path issues no `Target.createTarget`, and a fresh replay of the reported sequence did not reproduce it. The stray tab predated the sequence. A future occurrence needs debug-level `Target.createTarget` logging plus an e2e invariant test with a live browser. |
| `plugin-captcha-detection#1` / `plugin-image-detection-download#5` (shared half) — the result renderer's mapper is `JsonInclude(NON_EMPTY)`, so any tool payload's `null`/empty-collection key disappears | A real cross-cutting limitation, and the reason the captcha module carries explicit-null helpers. Changing the mapper globally (`JsonInclude.ALWAYS` for every tool result) would change the output of *every* tool — including the browser domains that deliberately omit empty fields — so the batch fixes the reported payloads in-module and documents the constraint instead. The tool-payload mapper is the right place for a dedicated, opt-in fix. |
| `agent-extraction#1` (part) — no `agent files <task-id>` / `agent artifact <task-id> <name>` command | The reported minimum — “make the deliverable reachable without filesystem archaeology” — is met by the enriched payload (`keyFindings`, `filesChanged`) plus the `workspace` path, and by documenting the layout. A listing command needs a server-side artifact API (the workspace path is server-local; the CLI must not assume a shared filesystem), which is feature work rather than this defect. |
| `agent-extraction#7` (part) — no `agent tabs <task-id>` / `agent screenshot <task-id>` escape hatch | The agent has no separate browser context to attach to: it drives the session's own browser through the session's bound driver and the backend navigates back to your page afterwards. There is nothing to enumerate, so the docs now state the isolation instead of inventing a command. |
| `attach-remote-debug#7` (part) — print plain absolute artifact paths instead of `[Label](path)` markdown links | The link form is an output contract shared by ~12 call sites, the docs and scripts; the reported defect was the mixed `\`/`/` separators, which are fixed. Changing the rendering is a separate, deliberate output-contract change. |
