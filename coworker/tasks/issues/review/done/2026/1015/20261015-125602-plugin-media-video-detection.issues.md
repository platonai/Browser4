# Issues: plugin-media-video-detection

> **Source:** `20261015-125602-plugin-media-video-detection.issues.json` | **Date:** 20261015-125602 | **Mode:** dev

## Scenario Background

### Task

I tested the **browser4-media** plugin end-to-end on three w3schools pages plus an HLS control page, using only `./b4w.ps1` commands.

**Setup note (the dominant obstacle):** the media plugin was **not installed** in the running backend — `plugin list` showed 4 plugins (captcha, images, markdown, swarm) and `GET /mcp/tools` listed 304 tools with zero `media_*` entries. I installed the locally-built JAR (`browser4-plugins/browser4-media/target/browser4-media-4.14.1-SNAPSHOT.jar`), ran `./b4w.ps1 stop`, and the next command auto-started the backend with the plugin loaded (7 tools: `media_detect_videos`, `media_download`, `media_get_info`, `media_process`, `media_extract_audio`, `media_trim`, `media_compress`). The JAR is newer than all plugin sources, so the checked-out code was tested. Both `tool call media_detect_videos` and the dotted `tool call media.detectVideos` work, as does the documented `plugin-media detectVideos` / `plugin media` form.

**Step 2 — `html5_video.asp`:** `media.detectVideos` returned **4 unique sources**:

| # | tag | source | MIME | dims | controls |
|---|---|---|---|---|---|
| 0 | `video` | `https://www.w3schools.com/html/mov_bbb.mp4` | *(absent)* | 320×176 | true |
| 1 | `source` | `mov_bbb.ogg` → resolved `…/mov_bbb.ogg` | video/ogg | 320×176 | true |
| 2 | `video` | `data:video/mp4;base64,…` (1142 chars) | *(absent)* | null | false |
| 3 | `source` | `data:video/webm;base64,…` (271 chars) | video/webm | null | false |

Breakdown: `<video>` tags = 2 reported (3 exist in the DOM), `<source>` children = 2 reported (6 exist), **iframe embeds = 0** (none of the page's 19 iframes is a player embed). **No HLS or DASH streams** were detected (none are present). Ground truth via `eval`: the main document has 2 `<video>` elements each with 2 `<source>` children (`mov_bbb.mp4`, `mov_bbb.ogg`), and a same-origin `about:blank` video-ad iframe (Viously) contributes the two `data:` URIs. The tool reports **unique URLs, not elements** — URL dedup collapses the duplicates and, because the `<video>` is emitted before its children, the `<source type="video/mp4">` entry is dropped, so **no entry carries the MP4's MIME type**, and the ad unit's videos appear indistinguishable from page videos.

**Step 3 — download:** `media_download` on the detected MP4 returned `success=true, bytesDownloaded=788493, contentType=video/mp4, durationMs=4812`. The file exists on disk (788,493 bytes, `file` → "ISO Media, MP4 v2"). By default it landed in `<runtime-bundle>/downloads/media/mov_bbb.mp4` — i.e. inside `browser4-apps/browser4-bundle/target/runtime-bundle/_work/…`, deep in the build output tree. Passing `outputPath`/`filename` works (absolute paths anywhere, missing dirs created), which is the workaround for a sane location; `config` exposes no `media.*` key.

**Step 4 — `html_youtube.asp`:** detection returned only the ad-iframe `data:` URIs — **no YouTube embed**. This is correct behaviour: the page's `youtube.com/embed` URLs exist only as escaped code samples (`&lt;iframe …&gt;`); the rendered DOM has 8 iframes, none from YouTube. Control test on `tryit.asp?filename=tryhtml_youtubeiframe` (a live embed) detected the iframe correctly (`isIframe=true`, `420×345`, `https://www.youtube.com/embed/tgbNymZ7vqY`), so the iframe path works.

**Step 5 — `tryit.asp?filename=tryhtml5_video`:** `eval` showed the main document has 0 `<video>` elements while the same-origin `#iframeResult` frame contains 1 `<video width=400 controls>` with two `<source>` children. `media.detectVideos` (backend view) found that iframe's video (`mov_bbb.mp4` + `mov_bbb.ogg`) plus the ad-frame `data:` URIs — same-origin iframe scanning works, but results carry no indication of which frame they came from.

**Step 6 — `media.getInfo`** on the downloaded file: `format=mov,mp4,m4a,3gp,3g2,mj2`, `duration=10.026667 s`, `320×176`, `codec=h264`, `bitrate=629116`, `streams=4` (0: video h264 320×176, 1: audio aac, 2: audio aac, 3: data bin_data). Local `ffprobe` returns byte-identical values.

**Extra probe:** on the hls.js demo (a page actively playing an HLS stream), detection reported a single `blob:https://…` video with `isHls=false` — the m3u8 URL was never surfaced, so HLS detection does not work for the standard hls.js/MSE case.

### Execution Context

**Preparation:** `pwd` (repo root) → `./b4w.ps1 help` → read `skills/browser4-cli/SKILL.md` in full → `--help plugins|plugin|plugin install|plugin list|plugin info|tool call` → `plugin list` → `./b4w.ps1 status`.

**Environment discovery:** `curl http://localhost:8282/mcp/tools` (304 tools, no media), `find` for the plugin module, read `browser4-plugins/browser4-media/README.md` + manifest, inspected the runtime bundle's `plugins/` dir, checked jar-vs-source timestamps, `which ffmpeg ffprobe`.

**Key commands:** `plugin install browser4-plugins/browser4-media/target/browser4-media-4.14.1-SNAPSHOT.jar` → `stop` → `plugin list` (media loaded) → `-s media-eval goto <url>` ×4 → `tool call media_detect_videos` ×5 → `tool call media.detectVideos` (dotted alias test) → `tool call media_download` ...

(truncated — see 20261015-125602-plugin-media-video-detection.issues.json for the complete execution trace)

---

## Issues Found (12 issues)

### Issue 1: browser4-media is not installed (or bundled); every media.* tool is missing out of the box

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 plugin list
curl -s http://127.0.0.1:8282/mcp/tools | grep -o -i '"media[a-z0-9_\.]*"'   # no output
./b4w.ps1 -s media-eval tool call media_detect_videos

#### Expected Behavior

The media tools are available after setup, or the CLI says which plugin provides them and how to install it (the main help does describe the generic install recipe).

#### Actual Behavior

plugin list shows only captcha/images/markdown/swarm; GET /mcp/tools lists 304 tools with zero media_* names; calling media.detectVideos fails with no hint that a plugin supplies it. Workaround that succeeded: ./b4w.ps1 plugin install browser4-plugins/browser4-media/target/browser4-media-4.14.1-SNAPSHOT.jar, then ./b4w.ps1 stop (next command auto-started the backend and loaded the plugin; 7 media tools appeared).

#### Root Cause Analysis

The runtime bundle only ever receives browser4-swarm: build-runtime-bundle.ps1 copies the swarm JAR explicitly into <bundle>/plugins and leaves every other first-party plugin to a manual install. browser4-media compiles to browser4-plugins/browser4-media/target/browser4-media-4.14.1-SNAPSHOT.jar but nothing in the dev-mode startup path scans that directory or warns that a repo plugin is present-but-not-installed, so the checked-out media code is unreachable until a human reads plugin install --help or the main help's 'Server plugins' section.

#### Code Pointer

`browser4-apps/browser4-bundle/build-runtime-bundle.ps1 (swarm-only bundling, ~lines 985-1167); cli/browser4-cli/src/main.rs (handle_plugin_list, ~line 21704)`

#### AI Suggested Improvement

- Make dev-mode startup (or `status`/`doctor`) compare browser4-plugins/*/target/*.jar against the bundle's plugins/ dir and print an actionable 'built but not installed' warning with the exact install command.
- Consider bundling browser4-media with the dev-mode bundle the way swarm is bundled, since it has no external service dependency (unlike captcha).
- If neither is desirable, name the first-party plugins and their tool domains in the main help so a user learns media.* exists at all.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `plugin-<domain> <method> --help` reports the command as unknown, so plugin tools have no help

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 -s media-eval plugin-media detectVideos --help
./b4w.ps1 -s media-eval plugin-media --help

#### Expected Behavior

The media domain's method list and arguments, matching `browser4-cli --help <command>` for static commands.

#### Actual Behavior

Prints "Plugin tool domain 'media' is not a static CLI command. ... Unknown command: plugin-media" followed by the full 350-line generic help — while the identical command without --help runs successfully. There is no way to enumerate a plugin domain's methods or arguments from the CLI.

#### Root Cause Analysis

Dynamic plugin commands are resolved at dispatch time from the plugin registry, but the --help path only consults the static command table/help.rs generators, so a dynamic name falls through to unknown-command handling and the generic banner. The method list itself is only ever exposed by the bare `plugin` command, which is not what a user types next to the command they are curious about.

#### Code Pointer

`cli/browser4-cli/src/main.rs (command dispatch/help handling for plugin-* names); cli/browser4-cli/src/help.rs:generate_help()`

#### AI Suggested Improvement

- Resolve `plugin-<domain>` (and `plugin <domain>`) in the help path before the static lookup fails, and print the domain's methods with their signatures from the ToolSpec registry.
- Support `plugin-<domain> <method> --help` to render the individual ToolSpec (arguments, defaults, description).
- Route the unknown-command path away from dumping the whole help when the name matches an installed plugin domain.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Tool arguments are not discoverable anywhere except from a deliberately failing call

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

curl -s http://127.0.0.1:8282/mcp/tools        # names only
curl -s http://127.0.0.1:8282/mcp/tools/media_download   # 404
curl -s -X POST http://127.0.0.1:8282/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'   # 404
./b4w.ps1 plugin info browser4-media   # manifest only
./b4w.ps1 -s media-eval tool call media_download   # only this reveals the signature

#### Expected Behavior

A `tool list`/`tool describe <name>` (or an MCP tools/list with inputSchema) that shows argument names, types, defaults and descriptions.

#### Actual Behavior

GET /mcp/tools returns a bare name array; per-tool and JSON-RPC endpoints 404; plugin info/plugin list show no tools. The only discovery route is calling the tool with wrong arguments and reading the MISSING_REQUIRED_ARG error (which does print the full signature, including the optional outputPath/filename parameters).

#### Root Cause Analysis

The backend exposes ToolSpec (arguments, description, help) internally but only publishes tool names over HTTP; the CLI has no command that renders a ToolSpec, and plugins' own READMEs (the real documentation, e.g. browser4-plugins/browser4-media/README.md) live in the repo and are not reachable from the CLI's skill files.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/mcp/server/Browser4MCPServer.kt (tool listing); browser4-rest MCPToolController (GET /mcp/tools); cli/browser4-cli/src (add `tool list` / `tool describe <name>`)`

#### AI Suggested Improvement

- Add `tool list [--json]` and `tool describe <mcp-name>` to the CLI, rendering name, domain, arguments, defaults and description from the backend's ToolSpec registry.
- Extend GET /mcp/tools with an `?includeSchema=true` mode (or a /mcp/tools/{name} endpoint) returning the input schema.
- Surface the same output from `plugin info <name>` as an 'Exposes tools' section.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: detectVideos mixes same-origin iframe and ad-unit videos into one flat list with no frame attribution

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s media-eval goto "https://www.w3schools.com/html/html5_video.asp"
./b4w.ps1 -s media-eval tool call media_detect_videos

#### Expected Behavior

Results attributable to the frame they came from, or ad/third-party iframes excluded — a user scanning 'the page' should not receive video-ad creatives as page videos.

#### Actual Behavior

4 entries; entries 2-3 are data:video/mp4;base64,… and data:video/webm;base64,… belonging to a Viously video-ad unit inside a same-origin about:blank iframe. They carry tagName 'video'/'source', isIframe:false, and there is no frameUrl/depth field, so they are indistinguishable from page content. The DOM audit shows 3 video elements across 2 documents versus 4 reported entries.

#### Root Cause Analysis

DETECTION_SCRIPT runs __b4_scan_videos(document) and then loops over every iframe whose contentDocument is readable (about:blank iframes inherit the parent origin, so ad units qualify) into the same accumulator; the VideoSource data class has no field recording which document/frame an entry came from, and no ad/third-party filter is applied.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt (VideoSource data class; same-origin iframe loop at the end of DETECTION_SCRIPT)`

#### AI Suggested Improvement

- Add `frameUrl`, `frameDepth` (and optionally `isCrossOrigin`) to VideoSource and populate them per document during the scan.
- Add an option (e.g. `includeIframes=false`) or at least flag entries that came from a nested document so agents can filter them.
- Consider skipping iframes whose URL is about:blank or matches known ad domains by default.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: URL dedup collapses per-element results and silently drops the primary video's MIME type

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s media-eval goto "https://www.w3schools.com/html/html5_video.asp"
./b4w.ps1 -s media-eval tool call media_detect_videos
eval "document.querySelectorAll('video').length + ' videos / ' + document.querySelectorAll('video source').length + ' sources'"  # 2 videos / 4 sources

#### Expected Behavior

Either one entry per DOM element, or a deduplicated view that preserves the fields (notably the MIME `type`) and the element count.

#### Actual Behavior

The main document's 2 <video> + 4 <source> collapse into 2 entries; the MP4 entry has no `type` at all even though the markup declares <source src="mov_bbb.mp4" type="video/mp4">. The type-carrying <source> was discarded because its resolvedUrl equals the <video>'s currentSrc and the <video> (type null) was emitted first. Reported 'total sources' (4) therefore matches neither element count (6) nor URL count in the DOM.

#### Root Cause Analysis

parseResult() calls distinctBy { it.resolvedUrl ?: it.srcUrl } after the JS has already suppressed duplicates first-seen-wins, and __b4_scan_videos emits the <video> before iterating its <source> children, so the richer entry never survives. isHls/isDash are likewise computed per element from src only.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt:parseResult() and DETECTION_SCRIPT (video/source iteration order)`

#### AI Suggested Improvement

- Prefer the entry with more information when deduplicating (keep the first non-null `type`, posterUrl, dimensions) instead of first-seen-wins.
- Add a `duplicateCount`/`elementCount` field, or a `distinct=false` mode returning every element.
- Emit <source> children before the parent <video>, or merge source metadata into the parent entry.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: HLS streams played through hls.js/MSE are reported as blob: sources with isHls=false

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s media-eval goto "https://hlsjs.video-dev.org/demo/"
./b4w.ps1 -s media-eval tool call media_detect_videos
# page state: typeof Hls === 'function', Hls.instances === undefined, video.src === blob:https://hlsjs.video-dev.org/…

#### Expected Behavior

The HLS manifest URL is surfaced (or at least isHls=true), since the page is actively playing https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8.

#### Actual Behavior

Single entry: srcUrl/resolvedUrl = blob:https://hlsjs.video-dev.org/8e7f22de-…, isHls=false, isDash=false. The blob: URL is unusable for media.download, so the detection is a dead end for the most common HLS deployment.

#### Root Cause Analysis

The HLS branch only reads the static `Hls.instances` array (undefined on this page) and never inspects the manifest URL — yet the m3u8 is discoverable in the page (URL query string / input value). MSE-loaded media always exposes only blob: URLs to the DOM, so an element-based probe cannot see the stream URL without the player's own state.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt (final section of DETECTION_SCRIPT: Hls.instances / dashjs.getMediaPlayers branch)`

#### AI Suggested Improvement

- Track Hls/dashjs instances more robustly (hook the constructor or read Hls.DefaultConfig/MediaSource, or scan for .m3u8/.mpd URLs in page text, script contents and the page URL query) instead of relying on Hls.instances.
- Mark blob:/mediasource: sources as 'opaque (MSE)' so callers know the URL cannot be downloaded.
- Add a test that loads a page with a data-driven HLS player and asserts isHls=true.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Default download destination is inside the runtime-bundle build tree and cannot be configured from the CLI

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s media-eval tool call media_download --json '{"url":"https://www.w3schools.com/html/mov_bbb.mp4"}'
# filePath = .../browser4-apps/browser4-bundle/target/runtime-bundle/_work/browser4-bundle-runtime-windows-x64/browser4-bundle-runtime-windows-x64/downloads/media/mov_bbb.mp4
./b4w.ps1 config list

#### Expected Behavior

Downloads land somewhere the user can find (a documented, stable directory) and the location can be set through the CLI's config surface.

#### Actual Behavior

Files are written relative to the backend process's working directory — the runtime bundle tree under target/runtime-bundle/_work/…, which is regenerated by bundles builds. config list exposes only server/timeout/proxy/session/extension_id, and server-side keys are limited to agent.llm.maxRequestTokens and agent.token.budget.total, so media.download.dir cannot be changed without editing backend config files. The README's 'downloads/media' implies the caller's working directory.

#### Root Cause Analysis

MediaConfig's media.download.dir defaults to the relative path 'downloads/media', resolved against the JVM's CWD (the bundle root in dev mode). The CLI bundles only the five CLI defaults plus two agent keys, so no supported path reaches plugin properties.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/config/MediaConfig.kt (media.download.dir) and service/MediaDownloader.kt; cli/browser4-cli src (config key allow-list)`

#### AI Suggested Improvement

- Default to a stable user-visible directory (e.g. <workspace app-data>/downloads/media) and print the absolute path prominently in the result (the value is currently only visible inside the embedded DownloadResult string).
- Allow arbitrary server-side keys such as media.download.dir through `config set`, or document the config file/property to edit.
- Consider a `tool call ... --out <dir>`-style CLI-level output redirection so callers control where files land on their own machine.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: browser4-media is not bundled and the workspace had no signal that the repo builds a media plugin

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 help        # 'Server plugins' section names browser4-captcha as the example only
./b4w.ps1 plugin list # captcha, images, markdown, swarm
./b4w.ps1 plugin info browser4-media   # before install: not found / after: manifest only, no tool names

#### Expected Behavior

A discoverable list of first-party plugins available in the checkout, with the tool domain each one adds.

#### Actual Behavior

Nothing in the CLI names browser4-media or its media.* domain; the only in-repo description is browser4-plugins/browser4-media/README.md, which is not part of the CLI skill bundle (skills/browser4-cli never mentions media or video tools).

#### Root Cause Analysis

The help text and skill files were written for the generic plugin mechanism (captcha used as the example) and were not extended when browser4-media was added to browser4-plugins/pom.xml; the runtime bundle's plugin set is fixed at build time (swarm only).

#### Code Pointer

`cli/browser4-cli/src/help.rs (Server plugins section, ~line 592); skills/browser4-cli/SKILL.md §3 command map`

#### AI Suggested Improvement

- List the first-party plugin modules and their tool domains in the main help (or a `plugin available` subcommand that scans browser4-plugins/*/target).
- Add a media section to the browser4-cli skill (detectVideos/download/getInfo with examples), mirroring the existing captcha guidance.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: Full base64 data: URIs are echoed verbatim in detection output

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s media-eval tool call media_detect_videos   # on w3schools pages whose ad iframe uses data: URIs

#### Expected Behavior

Opaque inline payloads summarised (e.g. 'data:video/mp4;base64,<2900 B>') so the payload stays readable and cheap for an LLM context.

#### Actual Behavior

The 1142-char and 271-char base64 strings are printed twice each (srcUrl and resolvedUrl); a 4-source page produces 3.4 KB of output dominated by base64 that cannot be used for a download anyway.

#### Root Cause Analysis

The detection script stores the raw attribute value and the result is serialized as-is; there is no truncation or data-URI special case anywhere between the JS probe and the CLI print.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt (DETECTION_SCRIPT result assembly) / MediaToolExecutor result serialization`

#### AI Suggested Improvement

- Replace data: URIs with a truncated form plus a byte length (and optionally a sourceKind field), keeping the full value behind an opt-in flag.
- Drop resolvedUrl when it equals srcUrl — the duplication doubles every payload for no information gain.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: Tool-call error help prints the tool signature twice

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s media-eval tool call media_get_info --json '{"filePath":"D:/tmp/missing.mp4"}'

#### Expected Behavior

The signature appears once, followed by the description.

#### Actual Behavior

"[INVALID_ARGUMENT] media_get_info failed: File not found: … help: Get media file metadata (…) using ffprobe.\nmedia.getInfo(filePath: String)\n\nmedia.getInfo(filePath: String)\n\nReturns detailed information…" — the signature line is repeated.

#### Root Cause Analysis

The framework already prepends the tool signature when composing a usage-error help block, while the plugin's ToolSpec.help string begins with the same signature line; the same pattern exists in the captcha/images/markdown plugins, so every plugin tool error is affected.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/tools/MediaToolExecutor.kt (help strings, e.g. the getInfo spec at ~line 166); browser4-rest MCPToolController.buildErrorMessage (~line 2040)`

#### AI Suggested Improvement

- Strip a leading signature line from ToolSpec.help when the framework is going to prepend its own, or stop prepending it when the help already starts with the signature.
- Apply the fix in the shared tool-error path so all four plugins are corrected at once.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: `plugin` domain listing mixes in core tools and mangles camelCase names

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 plugin

#### Expected Behavior

The installed plugins' tool domains and methods (e.g. media -> detectVideos, download, getInfo…), as the heading 'Available plugin commands' and the help text promise.

#### Actual Behavior

115 entries that include core built-in tools (click, fill, hover, eval, navigate, screenshot, scroll, select, type, upload…) plus garbled conversions of camelCase API names: base_u_r (baseURL), document_u_r (documentURI), nano_d_o_m, outer_h_t_m. media_download is absent from the list although `plugin media_download` / `plugin-media download` work.

#### Root Cause Analysis

The listing is generated by snake_casing every registered MCP tool name and truncating to two underscore-separated tokens; the transformation is lossy for names containing acronyms (baseURL -> base_u_r) and blind to whether the tool comes from a plugin or the core driver, and the source list does not cover every method (media.download).

#### Code Pointer

`cli/browser4-cli/src/main.rs (bare `plugin` listing handler)`

#### AI Suggested Improvement

- Derive the list from the plugin registry's ToolSpec domains (plugin-provided only, or a clearly separated second section for core tools) instead of snake_casing raw tool names.
- Group by domain and list methods under it, so `plugin media` -> detectVideos/download/getInfo/process/extractAudio/trim/compress is visible.
- Fix the abbreviation handling (URL/DOM/HTML/URI) and add a test asserting baseURL/DocumentURI style names render intact.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: Docs claim `plugin list` enumerates available tools; it does not

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 --help tool call
# and skills/browser4-cli/SKILL.md §3 command-map row: 'tool call <mcp-name> … List tools via `plugin list` or GET /mcp/tools'
./b4w.ps1 plugin list

#### Expected Behavior

Following the documented pointer reveals the callable tool names (the main help's own 'Use `plugin list` to see installed plugins and their status' wording is the accurate one).

#### Actual Behavior

plugin list prints only fileName/version/sdk/manifest/description/status per plugin; plugin info adds the manifest fields. Neither lists a single tool name, so the documented discovery path leads nowhere and the user must fall back to GET /mcp/tools (names only, no schemas).

#### Root Cause Analysis

The skill text and the `tool call --help` string bundle two different discovery mechanisms into 'plugin list or GET /mcp/tools'; only the second works today, and the plugin list output format was never extended with a tools column/section.

#### Code Pointer

`skills/browser4-cli/SKILL.md (§3 command map, tool call row); cli/browser4-cli/src/main.rs (handle_plugin_list) / cli/browser4-cli/src/commands (tool-call help text)`

#### AI Suggested Improvement

- Either add an 'Exposes tools:' line to `plugin list`/`plugin info` output, or correct the docs to say GET /mcp/tools (and add a real `tool list` command).
- Keep the help text and skill file in sync with whatever is implemented, since both currently point at `plugin list`.

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

**Completion Status:** Successful — all six task steps were carried out and every requested datum was produced (detection inventory, MP4 download verified on disk, YouTube-page behaviour, iframe evaluation, ffprobe metadata), but only after a manual plugin install and backend restart that the task setup did not anticipate; two of the sub-questions (HLS/DASH detection, YouTube embed detection) required additional control pages to answer honestly.

**Success Rate:** 85% — the media workflow itself was reached on the first try once installed and every command returned useful data; markdown-quality answers for steps 4 and 6 were only possible after extra investigation because the page had no live embed and because HLS detection silently failed.

**Issues Found:** 12

**Major Blockers:** The browser4-media plugin was not installed in the running backend: none of the seven media.* tools existed (304 MCP tools, zero media_*), and nothing in `plugin list`, `plugin info`, `tool call --help` or the SKILL.md said a media plugin existed or how to load it. The task only proceeded after `plugin install <repo jar>` + `stop` + auto-restart. Secondary blocker: the assigned YouTube page contains no live YouTube embed (only escaped code samples), so the 'is the embed detected' question could not be answered from that page alone.

**Most Confusing Aspects:** 1) The task's tool names (media.detectVideos) match neither the CLI's documented snake_case convention nor the `plugin-media` command form, and there is no command that lists a plugin's tools or their arguments — the only schema discovery is triggering an error. 2) `plugin-media --help` answers 'Unknown command' for a command that works. 3) Detection output silently merges same-origin iframes (including video-ad creatives) with no frame attribution, and dedupes by URL so element counts and the MP4's MIME type vanish. 4) The default download lands deep inside the build-output tree with no config key to change it. 5) The bare `plugin` listing is a 115-entry mix of core tools and mangled names (base_u_r, nano_d_o_m).

**Most Valuable Improvements:** 1) Make the media plugin reachable out of the box — bundle it, or have startup/status report 'built but not installed' plugins with the exact install command. 2) Add real tool discovery: `tool list`/`tool describe`, plugin tool names in `plugin list`, and working `--help` for dynamic plugin commands. 3) Give detection results frame attribution (frameUrl/depth) and fix the dedup so MIME/counts survive. 4) Make the download directory configurable and clearly reported (absolute path, stable location). 5) Fix HLS detection for MSE-based players (or mark blob/MSE sources as opaque) and truncate data: URIs in output.

**Usability Rating:** 5/10

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

#### Issue 1: browser4-media is not installed (or bundled); every media.* tool is missing out of the box

./b4w.ps1 plugin list
curl -s http://127.0.0.1:8282/mcp/tools | grep -o -i '"media[a-z0-9_\.]*"'   # no output
./b4w.ps1 -s media-eval tool call media_detect_videos

#### Issue 2: `plugin-<domain> <method> --help` reports the command as unknown, so plugin tools have no help

./b4w.ps1 -s media-eval plugin-media detectVideos --help
./b4w.ps1 -s media-eval plugin-media --help

#### Issue 3: Tool arguments are not discoverable anywhere except from a deliberately failing call

curl -s http://127.0.0.1:8282/mcp/tools        # names only
curl -s http://127.0.0.1:8282/mcp/tools/media_download   # 404
curl -s -X POST http://127.0.0.1:8282/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'   # 404
./b4w.ps1 plugin info browser4-media   # manifest only
./b4w.ps1 -s media-eval tool call media_download   # only this reveals the signature

#### Issue 4: detectVideos mixes same-origin iframe and ad-unit videos into one flat list with no frame attribution

./b4w.ps1 -s media-eval goto "https://www.w3schools.com/html/html5_video.asp"
./b4w.ps1 -s media-eval tool call media_detect_videos

#### Issue 5: URL dedup collapses per-element results and silently drops the primary video's MIME type

./b4w.ps1 -s media-eval goto "https://www.w3schools.com/html/html5_video.asp"
./b4w.ps1 -s media-eval tool call media_detect_videos
eval "document.querySelectorAll('video').length + ' videos / ' + document.querySelectorAll('video source').length + ' sources'"  # 2 videos / 4 sources

#### Issue 6: HLS streams played through hls.js/MSE are reported as blob: sources with isHls=false

./b4w.ps1 -s media-eval goto "https://hlsjs.video-dev.org/demo/"
./b4w.ps1 -s media-eval tool call media_detect_videos
# page state: typeof Hls === 'function', Hls.instances === undefined, video.src === blob:https://hlsjs.video-dev.org/…

#### Issue 7: Default download destination is inside the runtime-bundle build tree and cannot be configured from the CLI

./b4w.ps1 -s media-eval tool call media_download --json '{"url":"https://www.w3schools.com/html/mov_bbb.mp4"}'
# filePath = .../browser4-apps/browser4-bundle/target/runtime-bundle/_work/browser4-bundle-runtime-windows-x64/browser4-bundle-runtime-windows-x64/downloads/media/mov_bbb.mp4
./b4w.ps1 config list

#### Issue 8: browser4-media is not bundled and the workspace had no signal that the repo builds a media plugin

./b4w.ps1 help        # 'Server plugins' section names browser4-captcha as the example only
./b4w.ps1 plugin list # captcha, images, markdown, swarm
./b4w.ps1 plugin info browser4-media   # before install: not found / after: manifest only, no tool names

#### Issue 9: Full base64 data: URIs are echoed verbatim in detection output

./b4w.ps1 -s media-eval tool call media_detect_videos   # on w3schools pages whose ad iframe uses data: URIs

#### Issue 10: Tool-call error help prints the tool signature twice

./b4w.ps1 -s media-eval tool call media_get_info --json '{"filePath":"D:/tmp/missing.mp4"}'

#### Issue 11: `plugin` domain listing mixes in core tools and mangles camelCase names

./b4w.ps1 plugin

#### Issue 12: Docs claim `plugin list` enumerates available tools; it does not

./b4w.ps1 --help tool call
# and skills/browser4-cli/SKILL.md §3 command-map row: 'tool call <mcp-name> … List tools via `plugin list` or GET /mcp/tools'
./b4w.ps1 plugin list

