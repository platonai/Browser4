# Issues: plugin-media-video-detection

> **Source:** `20261004-171100-plugin-media-video-detection.full.md` | **Date:** 20261004-171100 | **Mode:** dev

## Scenario Background

### Task

**Environment / setup.** The `browser4-media` plugin was not installed in the dev runtime bundle (`plugin list` showed only captcha, images, markdown), so I installed the locally built JAR (`browser4-plugins/browser4-media/target/browser4-media-4.13.27-SNAPSHOT.jar`) via `plugin install`, restarted the server, and confirmed it loaded. All work ran in a named session `media-eval` (headless). System ffmpeg/ffprobe 9.0 were available.

### Step 1–2: `html5_video.asp` detection

`media.detectVideos` returned **4 sources** (tool output saved to `.test-sessions/20261004T1433038523034Z/detectVideos_html5_video.json`):

| # | tagName | source URL | MIME | dims | controls |
|---|---|---|---|---|---|
| 1 | `video` | `https://www.w3schools.com/html/mov_bbb.mp4` | *(absent)* | 320×176 | true |
| 2 | `source` | `mov_bbb.ogg` → `https://www.w3schools.com/html/mov_bbb.ogg` | `video/ogg` | 320×176 | true |
| 3 | `video` | `data:video/mp4;base64,AAAAHGZ0eX…` (≈740 B) | *(absent)* | – | false |
| 4 | `source` | `data:video/webm;base64,GkXfo0Ag…` | `video/webm` | – | false |

- **Counts (as reported):** 2 `<video>`, 2 `<source>`, 0 iframe embeds; **no HLS/DASH** (all `isHls`/`isDash` false).
- **Ground-truth cross-check (eval):** the top document actually has **2 `<video>` elements** — `#video1` (controls, sources `mov_bbb.mp4`+`mov_bbb.ogg`) and `#video2` (width=480, no controls, same sources) — each with 2 `<source>` children. The two `data:` entries come from a **same-origin Viously ad iframe** (`#viously_0`), not the article. Because detection dedupes by URL, **`#video2` is silently missing** and the `.mp4` source children collapse into their video entries. Element-level counts are therefore wrong even though the unique source list is correct.

### Step 3: `media.download`

```
{"type":"…DownloadResult","description":"DownloadResult(url=https://www.w3schools.com/html/mov_bbb.mp4,
 filePath=D:\…\.test-sessions\20261004T1433038523034Z\media\mov_bbb.mp4,
 bytesDownloaded=788493, contentType=video/mp4, durationMs=9282, success=true, error=null)"}
```
Verified: `success=true`, `bytesDownloaded=788493 > 0`, file exists at the reported path with exactly 788,493 bytes (`ISO Media, MP4 v2`). A second download (`mov_bbb.ogg`, 614,492 bytes) also succeeded.

### Step 4: `html_youtube.asp`

**The page no longer embeds a live YouTube iframe.** The DOM contains zero iframes with a YouTube `src` (all iframes are ad/consent frames), and the raw HTML fetched directly from the server also has none — `/embed/tgbNymZ7vqY` appears only 4× as escaped **code-sample text** inside `<span class="tagcolor">` blocks. `detectVideos` on this page found only the 2 ad-frame `data:` entries (same as entries 3–4 above). To verify the capability itself, I scanned `tryit.asp?filename=tryhtml_youtubeiframe`, where detection worked: `tagName=iframe`, `https://www.youtube.com/embed/tgbNymZ7vqY`, **`isIframe=true`**, 420×345.

### Step 5: `tryit.asp?filename=tryhtml5_video`

- `eval`: the **top-level document contains 0 `<video>` elements**; the demo video (width=400, controls, sources `mov_bbb.mp4`/`.ogg`) lives inside the same-origin `#iframeResult` frame.
- `media.detectVideos` (backend scan) found it: `video` → `https://www.w3schools.com/html/mov_bbb.mp4` (320×176 intrinsic, controls=true) + `source` → `mov_bbb.ogg` (`video/ogg`), plus the 2 ad-frame `data:` entries (4 total). So backend detection reaches same-origin iframes, but the entries carry no marker that they came from a frame.

### Step 6: `media.getInfo` on the downloaded MP4

- **Format:** `mov,mp4,m4a,3gp,3g2,mj2` (MP4); **duration:** 10.026667 s; **resolution:** 320×176; **codec:** h264; **bitrate:** 629,116 bps
- **Streams: 4** — 1× video (h264, 320×176), 2× audio (aac, aac), 1× data (`bin_data`)
- Independently cross-checked with system `ffprobe` 9.0 — identical values. Note the result is a Kotlin `toString` blob (see Issue 4), requiring string parsing.

### Execution Context

**Key Commands:**

**Major steps:** (1) discovered the plugin was not installed and installed it from the source-tree JAR + restart; (2) learned the `plugin-<domain> <method>` invocation from the generic help (no per-plugin help exists); (3) probed each page's DOM with `eval --file` to establish ground truth before/after `detectVideos`; (4) wrote every scratch file (probe scripts, detection JSON, downloads, raw HTML) under `.test-sessions/20261004T1433038523034Z/`.

**Workarounds required:** manual plugin JAR discovery + install + restart; used `eval --file` to dodge shell quoting; read the plugin README/source to learn method names and parameter spellings; used a direct `curl` MCP call to distinguish server-side from CLI-side serialization. One `curl` to a tryit URL hung (no timeout) and had to be killed — a shell hygiene issue, not a browser4 defect.

**Sections C and D (machine-readable):**

---

## Issues Found (11 issues)

### Issue 1: Global --json silently suppresses all plugin-* output (exit 0, zero bytes on stdout and stderr)

**Severity:** High
**Category:** Reliability

#### Reproduction

`./b4w.ps1 -s media-eval --json plugin-media detectVideos` with stdout/stderr redirected to files -> both are 0 bytes, exit code 0. Same for `--json plugin-media getInfo --filePath <file>`. The same command works when --json is omitted, and core commands (`--json page-info`) work in either position.

#### Expected Behavior

A JSON envelope like other commands (e.g. {"status":"ok","command":"plugin-media","output":...}) or at minimum the tool's raw JSON payload.

#### Actual Behavior

Complete silence with a success exit code. A script or agent running with --json cannot distinguish 'no videos found' from 'output suppressed'.

#### Root Cause Analysis

handle_dynamic_plugin_command prints results only via the cli_println! macro (main.rs:17630). That macro suppresses all output when json_active() is true (main.rs:266-277), and dynamic plugin commands have no JSON-envelope fallback, unlike built-in commands.

#### Code Pointer

`cli/browser4-cli/src/main.rs:17630 (handle_dynamic_plugin_command) and cli/browser4-cli/src/main.rs:266 (cli_println! macro)`

#### AI Suggested Improvement

- In handle_dynamic_plugin_command, when global json is active, print a proper envelope with the tool result (parse it as JSON when possible, otherwise wrap the string)
- Never let --json turn a successful command into fully silent output; fall back to printing the raw payload
- Add a regression test asserting non-empty stdout for `--json plugin-<name> <method>`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Misspelled or unknown plugin method silently executes the alphabetically-first tool

**Severity:** High
**Category:** Reliability

#### Reproduction

`./b4w.ps1 -s media-eval plugin-media frobnicate` -> prints the detectVideos JSON array, exit 0. A typo such as `plugin-media downlaod --url X --outputPath Y` therefore runs video detection instead of erroring.

#### Expected Behavior

An error such as: "Unknown method 'frobnicate' for plugin 'media'. Available methods: detectVideos, download, getInfo, process, extractAudio, trim, compress" with a nonzero exit.

#### Actual Behavior

The unknown method name is dropped and the first matching tool (media_detect_videos, alphabetically first in the server's tool list) runs with the remaining arguments; exit code 0.

#### Root Cause Analysis

resolve_plugin_method (main.rs:17485-17519) falls back to `matching[0]` whenever the first positional does not resolve to a `domain_method` tool; the unmatched positional is simply not propagated into tool params.

#### Code Pointer

`cli/browser4-cli/src/main.rs:17485 (resolve_plugin_method)`

#### AI Suggested Improvement

- When a positional method name is present but matches no tool, return a Usage error listing the domain's available methods
- Keep the 'first matching tool' fallback only when no method positional was supplied at all
- Accept kebab-case method spellings (`detect-videos`) as aliases and list them in the error message

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: browser4-media plugin absent from dev runtime bundle; no in-CLI guidance to install it

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

From the repo root: `./b4w.ps1 plugin list` -> only browser4-captcha, browser4-images, browser4-markdown are listed, even though browser4-plugins/browser4-media/target/browser4-media-4.13.27-SNAPSHOT.jar exists in the checkout. `./b4w.ps1 plugin-media detectVideos` before installing fails with "No plugin tools found for 'media'." Workaround used: `./b4w.ps1 plugin install browser4-plugins/browser4-media/target/browser4-media-4.13.27-SNAPSHOT.jar`, then `./b4w.ps1 stop` (next command auto-restarts).

#### Expected Behavior

Either the dev bundle ships the media plugin like the other repo plugins, or the CLI surfaces that repo plugins exist and can be installed (e.g. `plugin list --available`, an install hint in the 'no plugin tools found' error naming the target/*.jar path, or a pointer to browser4-plugins/*/README.md).

#### Actual Behavior

The plugin is invisible: `plugin list` shows installed plugins only, `plugin info` only describes installed plugins, and no help/error text mentions that more plugins are built in the source tree or how to activate them.

#### Root Cause Analysis

The runtime bundle packages only a subset of plugin JARs (captcha/images/markdown), while browser4-media is a separately built module under browser4-plugins/browser4-media/target. Discovery surfaces (`plugin list`, the no-tools error path in handle_dynamic_plugin_command) enumerate only already-installed plugin tools, so a built-but-uninstalled plugin cannot be found from the CLI.

#### Code Pointer

`browser4-apps/browser4-bundle/build-runtime-bundle.ps1 (bundled plugin set); cli/browser4-cli/src/main.rs:17576 (no-plugin-tools error path)`

#### AI Suggested Improvement

- Include browser4-media in the dev runtime bundle alongside the other three plugins
- Add `plugin list --available` (or `plugin discover`) listing repo-built JARs that are not installed
- When no tools match a plugin domain, include an install hint with the conventional JAR path (`browser4-plugins/<name>/target/*.jar`) and the `plugin install` command

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: media.download / media.getInfo return Kotlin toString blobs instead of structured JSON

**Severity:** Medium
**Category:** Product

#### Reproduction

`./b4w.ps1 -s media-eval plugin-media getInfo --filePath <...>/mov_bbb.mp4` -> {"type":"ai.platon.pulsar.media.service.FFmpegProcessManager.ProbeResult","description":"ProbeResult(format=mov,mp4,..., streams=[StreamInfo(index=0, ...)])"}. A direct POST to /mcp/call-tool returns the same wrapper in the text content block. media.download behaves identically (DownloadResult(...)).

#### Expected Behavior

Structured fields as JSON (success, filePath, bytesDownloaded, contentType, error; format, duration, width, height, codec, bitrate, streams[]) so agents and scripts can parse without string surgery.

#### Actual Behavior

All values are collapsed into a toString string inside a description field; numerics/nulls must be extracted with regex, and fields like streams[] lose their structure. detectVideos (returns List<VideoSource>) is fine, so the payload shape is inconsistent across tools of the same plugin.

#### Root Cause Analysis

AbstractToolExecutor.callFunctionOn wraps any return value that is not String/Number/Boolean/Map/Collection/Array/DirectValue as {"type": qualifiedName, "description": value.toString()} (AbstractToolExecutor.kt:78-100). MediaToolExecutor returns Kotlin data classes (DownloadResult, ProbeResult), so they take the toString path.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/AbstractToolExecutor.kt:78-100; alternatively browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/tools/MediaToolExecutor.kt (return Maps)`

#### AI Suggested Improvement

- Serialize tool return values that are Jackson-mappable data classes via convertValue(value, Map::class.java) before wrapping
- Or have MediaToolExecutor return Map<String, Any?> for download/getInfo (and the other FFmpeg tools)
- Keep the type/description fallback only for genuinely non-serializable objects (e.g. WebDriver graphs)
- Add a test asserting that getInfo/download results parse into named JSON fields

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: detectVideos deduplicates by URL and hides distinct <video> elements (element counts are wrong)

**Severity:** Medium
**Category:** Product

#### Reproduction

On https://www.w3schools.com/html/html5_video.asp: `eval` shows 2 <video> elements (#video1 controls=true; #video2 width=480, controls=false, same two <source> children). `plugin-media detectVideos` returns 4 entries with no entry matching #video2 (only one controls=true video entry at 320x176, plus a .ogg source and two data: videos from an ad iframe).

#### Expected Behavior

Element-level reporting (or at least counts reflecting both <video> elements) — the task-relevant fact 'the page contains two video demos' should not be silently collapsed.

#### Actual Behavior

#video2 is invisible because its resolved URLs are identical to #video1's; the .mp4 <source> children are also collapsed into their parent video entries. Reported 'video' entries (2) actually correspond to #video1 + the ad-frame video, not to the two page videos.

#### Root Cause Analysis

Deduplication keyed on resolvedUrl: the JS probe's __b4_video_seen map (VideoDetector.kt:130-135) and the Kotlin distinctBy { it.resolvedUrl ?: it.srcUrl } (VideoDetector.kt:104). Element identity (id, DOM index, containing frame) is not part of the key, so a second element with the same sources can never be reported.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt:104,130-135`

#### AI Suggested Improvement

- Include element identity (element id/index and frame context) in the dedup key, or dedupe only exactly-identical element records
- Add per-entry fields `id`, `index`, and `sourceFrame`/`frameUrl`
- Consider a `elementCount` summary (videos, sources, iframes found) alongside the deduplicated source list

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Plugin tool parameters are camelCase-only and undocumented; unknown flags are silently ignored

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

`./b4w.ps1 -s media-eval plugin-media download --url https://www.w3schools.com/html/mov_bbb.ogg --output-path <scratch>/kebab-test --filename kebab_test.ogg` -> the file is written to the default `<runtime-bundle>/downloads/media/kebab_test.ogg` (the --output-path value was ignored), exit 0. Re-running with `--outputPath` writes to the requested directory.

#### Expected Behavior

Either kebab-case is normalized to the tool's camelCase parameter (CLI convention elsewhere uses kebab: --wait-selector, --auto-dismiss-dialogs, --no-boxes), or an unmatched flag produces a warning/error; and per-tool parameter names are documented where users look.

#### Actual Behavior

Unknown flags are dropped without warning. A user guessing --output-path gets a successful download into a location deep inside the build tree they were never told about; the documented default (media.download.dir=downloads/media) is relative to the backend runtime bundle, not the CWD.

#### Root Cause Analysis

handle_dynamic_plugin_command passes raw --key value pairs straight into tool arguments (main.rs:17592-17604) with no case normalization and no validation against the tool's ToolSpec arguments (url, outputPath, filename). The ToolSpec metadata exists but is not exposed by `plugin info` or help.

#### Code Pointer

`cli/browser4-cli/src/main.rs:17592-17604 (argument pass-through); param names in browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/tools/MediaToolExecutor.kt (ToolSpec.Arg)`

#### AI Suggested Improvement

- Normalize --kebab-case flags to camelCase for plugin tool params (and accept both)
- Warn on parameters that do not match any ToolSpec argument for the resolved method
- Expose each plugin method's ToolSpec args/description via `plugin info <name> --tools` and `help plugin-<name>`
- Print the resolved default output directory in download results/help

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: No CLI way to discover plugin methods; bare `plugin` is rejected though help advertises it; `help plugin-media` is unknown

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1) `./b4w.ps1 plugin` -> "Error: Unsupported command form: plugin. Use 'browser4-cli plugin <subcommand>' instead.", although the top-level help states "plugin — list all available plugin tool domains". 2) `./b4w.ps1 help plugin-media` -> "Unknown command: plugin-media" followed by a generic help dump. 3) `./b4w.ps1 plugin info browser4-media` lists manifest metadata but no callable methods or parameters.

#### Expected Behavior

A discoverable method list per plugin (detectVideos, download, getInfo, process, extractAudio, trim, compress) with signatures, reachable from help/plugin info/bare `plugin`.

#### Actual Behavior

Methods are discoverable only by reading the plugin's source README; the CLI's own advertised discovery command errors out, and typos fall back to running a different tool (see separate issue).

#### Root Cause Analysis

preferred_prefixed_group_form maps "plugin" => Some("plugin <subcommand>") (main.rs:20283) and the guard at main.rs:21649 rejects bare `plugin` before the dynamic-plugin dispatch; the is_bare_plugin branch (main.rs:21696-21707) that would list tool domains is therefore dead code. help resolution (help.rs) has no support for dynamic plugin-<name> commands.

#### Code Pointer

`cli/browser4-cli/src/main.rs:20283,21649,21696; cli/browser4-cli/src/help.rs (print_help unknown-command path)`

#### AI Suggested Improvement

- Remove `plugin` from preferred_prefixed_group_form (or special-case bare `plugin` to reach the listing branch) so tool domains can be listed
- Extend `plugin info <name>` to list the plugin's MCP tools with arg names/types
- Teach `help plugin-<name>` (and `<cmd> --help`) to print the tools and a usage example
- Update the pre-existing unit test that pins "plugin" -> "plugin <subcommand>" accordingly

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: media.getInfo on an unparsable file returns an all-null success result

**Severity:** Low
**Category:** Reliability

#### Reproduction

`./b4w.ps1 -s media-eval plugin-media getInfo --filePath .test-sessions/20261004T1433038523034Z/probe_videos.js` -> {"type":"...ProbeResult","description":"ProbeResult(format=null, duration=null, width=null, height=null, codec=null, bitrate=null, streams=[])"}, exit 0.

#### Expected Behavior

An error stating ffprobe could not read the file (as happens for a missing file: exit 1, "File not found: ...").

#### Actual Behavior

Silent empty result that is indistinguishable from a valid file with no metadata; users cannot tell they probed the wrong file.

#### Root Cause Analysis

FFmpegProcessManager.probe returns an empty ProbeResult() when ffprobe exits nonzero or JSON parsing fails (FFmpegProcessManager.kt:116-119, 169-172). The failure is only logged, and the ffprobe invocation uses `-v quiet`, suppressing stderr detail.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/FFmpegProcessManager.kt:107-172`

#### AI Suggested Improvement

- Model ProbeResult with a success/error field (or throw) when ffprobe fails or output cannot be parsed
- Surface the ffprobe exit code and stderr (drop `-v quiet` or capture stderr separately)
- Have MediaToolExecutor convert that into an ERROR line + nonzero exit like the missing-file case

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: media.download HTTP failures report a truncated error ("HTTP 404: ") and exit 0

**Severity:** Low
**Category:** Reliability

#### Reproduction

`./b4w.ps1 -s media-eval plugin-media download --url https://www.w3schools.com/html/definitely_not_a_video_404.mp4` -> DownloadResult(..., success=false, error="HTTP 404: "), exit code 0.

#### Expected Behavior

An informative error including the status (e.g. "HTTP 404 Not Found for <url>"), and ideally a nonzero exit code for scripted callers when success=false.

#### Actual Behavior

The status message is empty; the only signal is success=false inside a toString blob, and the CLI exits 0.

#### Root Cause Analysis

MediaDownloader constructs the error string from the HTTP response without including the status line/message (empty for HTTP/2 responses) or the URL; the CLI treats the tool call as successful regardless of the DownloadResult.success flag.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/MediaDownloader.kt (download error construction); cli exit-code mapping in cli/browser4-cli/src/main.rs (handle_dynamic_plugin_command)`

#### AI Suggested Improvement

- Build the error as code + reason + URL (include a short body snippet for JSON/proxy errors)
- Consider exit code 1 when the returned DownloadResult has success=false (or document the in-band convention in help)
- Mark the filePath field explicitly empty/absent on failure

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: Videos discovered inside same-origin iframes are reported as page videos (no frame origin), so ad creatives pollute results

**Severity:** Low
**Category:** UX

#### Reproduction

On html5_video.asp and the tryit pages, detectVideos returns two data: URI entries (video + source). These come from the same-origin Viously ad iframe (`#viously_0`); the entries carry isIframe=false and no dimensions, looking exactly like main-document videos.

#### Expected Behavior

A field identifying the containing frame (e.g. sourceFrame/frameUrl) or an option to include/exclude nested frames, so users can distinguish article videos from ad creatives.

#### Actual Behavior

Users see unexplainable data: URIs with no context; `isIframe` only marks <iframe> embeds from known player domains (YouTube/Vimeo/...), not videos found inside frames, and same-origin ad frames are scanned by default.

#### Root Cause Analysis

The recursive same-origin iframe scan calls __b4_scan_videos(frameDoc) without passing frame context (VideoDetector.kt:251-264); the item schema has no frame field, so nested hits are indistinguishable from top-document hits.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt:140-179,251-264`

#### AI Suggested Improvement

- Add `sourceFrame`/`frameUrl` to VideoSource and set it for hits found in nested frames
- Add a flag (e.g. --include-frames / default on) or a filter to exclude frame-sourced videos
- Document that same-origin iframes are scanned recursively

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: MIME type absent for <video> elements whose type is declared on <source> children

**Severity:** Low
**Category:** Product

#### Reproduction

On html5_video.asp, the first detectVideos entry (mov_bbb.mp4) has no type field at all, while the DOM declares <source src="mov_bbb.mp4" type="video/mp4">. The type appears only on the .ogg source entry.

#### Expected Behavior

Video entries inherit the MIME type of their first usable <source> child (or expose all candidate types), since the task of identifying a direct MP4 URL benefits from a declared MIME.

#### Actual Behavior

type is null/omitted for the primary video; consumers must infer MIME from the URL extension or sibling source entries.

#### Root Cause Analysis

The probe sets type: el.getAttribute('type') || null for the <video> element (VideoDetector.kt:146-158) and never falls back to the child <source> type; JSON serialization also omits null fields.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt:146-158`

#### AI Suggested Improvement

- Fall back to the first <source> type when the <video> has no type attribute
- Expose a sourceTypes/sources array on the video entry
- Optionally infer MIME from the currentSrc extension as a last resort

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

**Completion Status:** Successful — all six steps produced verified results. Note the step-4 premise is outdated: https://www.w3schools.com/html/html_youtube.asp currently contains no live YouTube iframe (verified in both the rendered DOM and the raw server HTML; /embed/ URLs appear only as escaped code-sample text), so detectVideos correctly reported no YouTube embed there. The iframe-embed detection capability was additionally verified on tryit.asp?filename=tryhtml_youtubeiframe, where the YouTube iframe was detected with isIframe=true.

**Success Rate:** 95% — every task step completed with verified evidence; the only unmet expectation is the task's assumption that html_youtube.asp embeds a YouTube iframe (a site-side change, not a CLI failure).

**Issues Found:** 11

**Major Blockers:** None blocking. Setup friction: browser4-media was not installed in the dev runtime bundle, so the JAR had to be located in the source tree, installed via `plugin install`, and the server restarted before any media.* tool could run.

**Most Confusing Aspects:** Plugin lifecycle/discovery: media tools exist only after manually installing a repo JAR; bare `plugin` is rejected despite the help advertising it; `help plugin-media` is unknown; a typo'd method silently runs a different tool; parameters are camelCase-only and unknown flags are ignored silently. Also, global `--json` turns plugin commands into silent no-ops with exit 0, and detectVideos element counts are misleading because URL-based dedup hides a whole <video> element.

**Most Valuable Improvements:** 1) Make global --json emit a JSON envelope (or fail loudly) for plugin-* commands. 2) Error on unknown plugin methods instead of falling back to the alphabetically-first tool. 3) Return structured JSON maps instead of toString description blobs for download/getInfo. 4) Surface plugin method lists and parameter metadata via `plugin info`/help, and normalize/validate tool flags (kebab-case aliases, warn on unknowns). 5) Fix detectVideos dedup so element counts are accurate and mark frame-sourced videos.

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

#### Issue 1: Global --json silently suppresses all plugin-* output (exit 0, zero bytes on stdout and stderr)

`./b4w.ps1 -s media-eval --json plugin-media detectVideos` with stdout/stderr redirected to files -> both are 0 bytes, exit code 0. Same for `--json plugin-media getInfo --filePath <file>`. The same command works when --json is omitted, and core commands (`--json page-info`) work in either position.

#### Issue 2: Misspelled or unknown plugin method silently executes the alphabetically-first tool

`./b4w.ps1 -s media-eval plugin-media frobnicate` -> prints the detectVideos JSON array, exit 0. A typo such as `plugin-media downlaod --url X --outputPath Y` therefore runs video detection instead of erroring.

#### Issue 3: browser4-media plugin absent from dev runtime bundle; no in-CLI guidance to install it

From the repo root: `./b4w.ps1 plugin list` -> only browser4-captcha, browser4-images, browser4-markdown are listed, even though browser4-plugins/browser4-media/target/browser4-media-4.13.27-SNAPSHOT.jar exists in the checkout. `./b4w.ps1 plugin-media detectVideos` before installing fails with "No plugin tools found for 'media'." Workaround used: `./b4w.ps1 plugin install browser4-plugins/browser4-media/target/browser4-media-4.13.27-SNAPSHOT.jar`, then `./b4w.ps1 stop` (next command auto-restarts).

#### Issue 4: media.download / media.getInfo return Kotlin toString blobs instead of structured JSON

`./b4w.ps1 -s media-eval plugin-media getInfo --filePath <...>/mov_bbb.mp4` -> {"type":"ai.platon.pulsar.media.service.FFmpegProcessManager.ProbeResult","description":"ProbeResult(format=mov,mp4,..., streams=[StreamInfo(index=0, ...)])"}. A direct POST to /mcp/call-tool returns the same wrapper in the text content block. media.download behaves identically (DownloadResult(...)).

#### Issue 5: detectVideos deduplicates by URL and hides distinct <video> elements (element counts are wrong)

On https://www.w3schools.com/html/html5_video.asp: `eval` shows 2 <video> elements (#video1 controls=true; #video2 width=480, controls=false, same two <source> children). `plugin-media detectVideos` returns 4 entries with no entry matching #video2 (only one controls=true video entry at 320x176, plus a .ogg source and two data: videos from an ad iframe).

#### Issue 6: Plugin tool parameters are camelCase-only and undocumented; unknown flags are silently ignored

`./b4w.ps1 -s media-eval plugin-media download --url https://www.w3schools.com/html/mov_bbb.ogg --output-path <scratch>/kebab-test --filename kebab_test.ogg` -> the file is written to the default `<runtime-bundle>/downloads/media/kebab_test.ogg` (the --output-path value was ignored), exit 0. Re-running with `--outputPath` writes to the requested directory.

#### Issue 7: No CLI way to discover plugin methods; bare `plugin` is rejected though help advertises it; `help plugin-media` is unknown

1) `./b4w.ps1 plugin` -> "Error: Unsupported command form: plugin. Use 'browser4-cli plugin <subcommand>' instead.", although the top-level help states "plugin — list all available plugin tool domains". 2) `./b4w.ps1 help plugin-media` -> "Unknown command: plugin-media" followed by a generic help dump. 3) `./b4w.ps1 plugin info browser4-media` lists manifest metadata but no callable methods or parameters.

#### Issue 8: media.getInfo on an unparsable file returns an all-null success result

`./b4w.ps1 -s media-eval plugin-media getInfo --filePath .test-sessions/20261004T1433038523034Z/probe_videos.js` -> {"type":"...ProbeResult","description":"ProbeResult(format=null, duration=null, width=null, height=null, codec=null, bitrate=null, streams=[])"}, exit 0.

#### Issue 9: media.download HTTP failures report a truncated error ("HTTP 404: ") and exit 0

`./b4w.ps1 -s media-eval plugin-media download --url https://www.w3schools.com/html/definitely_not_a_video_404.mp4` -> DownloadResult(..., success=false, error="HTTP 404: "), exit code 0.

#### Issue 10: Videos discovered inside same-origin iframes are reported as page videos (no frame origin), so ad creatives pollute results

On html5_video.asp and the tryit pages, detectVideos returns two data: URI entries (video + source). These come from the same-origin Viously ad iframe (`#viously_0`); the entries carry isIframe=false and no dimensions, looking exactly like main-document videos.

#### Issue 11: MIME type absent for <video> elements whose type is declared on <source> children

On html5_video.asp, the first detectVideos entry (mov_bbb.mp4) has no type field at all, while the DOM declares <source src="mov_bbb.mp4" type="video/mp4">. The type appears only on the .ogg source entry.

