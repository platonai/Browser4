# Issues: plugin-image-detection-download

> **Source:** `20261015-124108-plugin-image-detection-download.issues.json` | **Date:** 20261015-124108 | **Mode:** dev

## Scenario Background

### Task

**Page:** `https://en.wikipedia.org/wiki/Gallery_of_sovereign_state_flags` — Wikipedia **redirected** this to `https://en.wikipedia.org/wiki/List_of_national_flags_of_sovereign_states` (the CLI reported the redirect clearly).

### 2–3. Detection results (`image_detect_images`, minWidth 100 / minHeight 60)

| Metric | Value |
|---|---|
| Distinct image sources detected (dedup by resolved URL) | **255** |
| `<img>` tags | **249** |
| CSS backgrounds (`tagName: background`) | **1** |
| Meta tags (OG/Twitter) | **1** |
| — plus `<source>` (`<picture>`) | 2 |
| — plus `<link>` (favicon / apple-touch-icon) | 2 |
| Passing ≥100px × ≥60px | **241** (14 excluded) |
| DOM cross-check | `document.images.length` = **440 elements, 249 unique srcs** — dedup works as documented (440 → 249) |

**Top 5 largest by dimensions** (rendered/layout dims — all flags were lazy-unloaded, so `naturalWidth/Height` were absent):

| # | Size | Image |
|---|---|---|
| 1 | 250 × 305 | Flag of Nepal |
| 2 | 250 × 250 | Banner of the Qulla Suyu (1979) |
| 3 | 250 × 250 | Flag of Switzerland (Pantone) |
| 4 | 250 × 250 | Flag of Vatican City (2023–present) |
| 5 | 250 × 217 | Flag of Belgium |

*(6th: Flag of Niger, 250 × 214)*

### 4. Single downloads — 3/3 succeeded

| Flag | Bytes | Saved as | Decodes as |
|---|---|---|---|
| Nepal | 5,204 | `250px-Flag_of_Nepal.svg.webp` | 250×305 WebP ✓ |
| Switzerland | 158 | `250px-Flag_of_Switzerland_%28Pantone%29.svg.webp` | 250×250 WebP ✓ |
| Vatican City | 11,610 | `250px-Flag_of_Vatican_City_%282023%E2%80%93present%29.svg.webp` | 250×250 WebP ✓ |

Note: all three URLs were `.svg.png` (PNG thumbnails) but were saved as **WebP** via content negotiation (documented behavior — the extension is corrected to the actual content type).

### 5. Bulk download (`image_download_all`, minWidth 200 / minHeight 120)

**totalAttempted=227 · successful=227 · failed=0 · totalBytesDownloaded=513,626** — completed in ~18 s (concurrency 5).

### 6. Disk verification

- **227 files** on disk, **513,626 bytes total — exactly matching the reported total**
- **227/227 decode** as valid images (PIL); smallest dimension 250×125; all ≥ 200×120
- **105 files are < 1 KB** (min 54 bytes, e.g. Monaco/Germany/Indonesia) — these are *valid* lossless-WebP renderings of flat-color flags, so the task's ">1 KB each" heuristic does not hold for this content. Every file was verified decodable, so no truncation occurred.

---

### Execution Context

**Key Commands:**

```
./b4w.ps1 help ; ./b4w.ps1 plugin list ; ./b4w.ps1 plugin install <jar> ; ./b4w.ps1 stop
./b4w.ps1 -s img-eval goto "https://en.wikipedia.org/wiki/Gallery_of_sovereign_state_flags"
./b4w.ps1 -s img-eval tool call image_detect_images --json '{"minWidth":100,"minHeight":60}'
./b4w.ps1 -s img-eval tool call image_detect_images                      # unfiltered baseline
./b4w.ps1 -s img-eval tool call image_download --json '{"url":…,"outputPath":…}'   # ×3
./b4w.ps1 -s img-eval --timeout 900 tool call image_download_all --json '{"minWidth":200,"minHeight":120,…}'
./b4w.ps1 -s img-eval eval "document.images.length + …"                  # DOM cross-check
./b4w.ps1 plugin image download --url … --output-path …                  # undocumented path, tested once
```

**Key decisions / workarounds required:**

1. **The plugin was not installed.** `plugin list` showed only `browser4-captcha` and `browser4-swarm`; the bundle build script only auto-bundles swarm. I found the JAR at `browser4-plugins/browser4-images/target/browser4-images-4.14.1-SNAPSHOT.jar` (no source file newer than the JAR, so it matched the checkout), installed it with `plugin install`, and restarted via `stop`.
2. **Rebuilding the plugin from source failed** in this environment: `mvn -pl browser4-plugins/browser4-images -DskipTests package` → `Failed connecting to the daemon in 4 retries` (Kotlin compile **and** kapt), also with `-Dkotlin.compiler.execution.strategy=in-process -Dkapt.use.worker.api=false`. Likely sandbox-related (daemon socket), not a product defect — noted, not filed as an issue.
3. **All first download attempts timed out.** The machine uses a Windows system proxy (`127.0.0.1:10808`, verified listening); Chrome picks it up, the plugin's OkHttp client does not (`java.net.useSystemProxies=false`) — exactly the scenario the plugin README documents. Restarting the backend with `HTTPS_PROXY=http://127.0.0.1:10808` made every download work. I chose the env-var route over editing the shared `~/.browser4/config/conf-enabled/application-private.properties`.
4. **Bulk download needed a raised CLI timeout** (`--timeout 900`); the 30 s default would have aborted it.
5. Analysis of the 118–124 KB JSON payloads was done with Python scripts in the run's scratch dir (no `jq` available); all artifacts live under `.test-sessions/20261015T0901431699046Z/`.

---

---

## Issues Found (10 issues)

### Issue 1: browser4-images plugin is not installed or bundled by default; task silently impossible until discovered manually

**Severity:** High
**Category:** Discoverability

#### Reproduction

cd D:/workspace/Browser4/Browser4-4.14 && ./b4w.ps1 plugin list
# then try: ./b4w.ps1 -s s1 tool call image_detect_images

#### Expected Behavior

Either the plugin ships enabled in the dev bundle, or `plugin list` / the missing-tool error names it as available-but-not-installed (e.g. 'browser4-images is available at browser4-plugins/browser4-images/target/*.jar — run plugin install').

#### Actual Behavior

`plugin list` shows only browser4-captcha and browser4-swarm. `tool call image_detect_images` fails as an unknown tool with no hint that the plugin exists, where to find its JAR, or that it must be installed. The only in-CLI hint is a '── Server plugins ──' note buried at the very bottom of the long `help` output.

#### Root Cause Analysis

The runtime-bundle build script (build-runtime-bundle.ps1) bundles only the browser4-swarm plugin; browser4-images is never copied into the backend's plugins/ directory, and the CLI's plugin listing has no notion of 'known first-party plugin, not installed'. The plugin's own README documents installation as a Maven <dependency> for developers, not as a CLI install step for users.

#### Code Pointer

`browser4-apps/browser4-bundle/build-runtime-bundle.ps1 (plugin bundling step, ~line 985-1160); browser4-plugins/browser4-images/README.md (Installation)`

#### AI Suggested Improvement

- Bundle browser4-images with the runtime bundle the same way browser4-swarm is bundled (version-matched JAR from the Maven local repo), or\.
- Make `plugin list` enumerate first-party plugin JARs found under browser4-plugins/*/target/ and mark them 'available — not installed' with the install command.
- When `tool call <name>` targets an unknown snake_case tool whose domain matches an available-but-uninstalled plugin, fail with an actionable message naming the JAR and the install command.
- Promote the '── Server plugins ──' help section into the plugin command's own help page (`--help plugins`), which currently lists only list/info/install/remove.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: All image downloads time out behind a system proxy; CLI reports a generic 30s HTTP timeout before the plugin's actionable error can surface

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s img-eval tool call image_download --json '{"url":"https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9b/Flag_of_Nepal.svg/250px-Flag_of_Nepal.svg.png","outputPath":"…"}'
# (machine has Windows system proxy 127.0.0.1:10808 in HKCU Internet Settings; no HTTPS_PROXY env var)

#### Expected Behavior

The download succeeds (the browser reaches the same host fine), or the error explains that the downloader does not use the OS proxy and names the fix: image.download.proxy / HTTPS_PROXY.

#### Actual Behavior

Error: HTTP request timed out [tool=image_download, endpoint=http://127.0.0.1:8282/mcp/call-tool, timeout=30s, …]: error sending request for url (http://127.0.0.1:8282/mcp/call-tool). No cause, no hint. All three downloads failed identically; after restarting the backend with HTTPS_PROXY=http://127.0.0.1:10808 every download succeeded in <3s.

#### Root Cause Analysis

Two compounding causes. (1) Chrome uses the WinINET system proxy while the plugin's OkHttp client deliberately does not (java.net.useSystemProxies=false), so direct egress is blocked for the backend — documented in the plugin README but invisible from the CLI. (2) Timeout inversion: the CLI's default HTTP timeout for tool calls is 30s (cli/browser4-cli/src/main.rs:2149) while the plugin's per-download read timeout is 120s/60s (ImageAutoConfiguration.kt/ImageConfig), so the CLI aborts first and the plugin's own error path (which appends a proxy hint for network-style IO errors) never reaches the user.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/config/ImageAutoConfiguration.kt:resolveProxy(); cli/browser4-cli/src/main.rs:2149 (30s default)`

#### AI Suggested Improvement

- Have the plugin auto-detect the OS proxy as a fallback (or default java.net.useSystemProxies=true for its OkHttp client) so behaviour matches the browser it is bolted onto.
- When a plugin tool times out, include the target tool's own timeout and the likely cause class in the CLI error (e.g. 'backend still running image_download — downloads do not use the OS proxy; see image.download.proxy / HTTPS_PROXY').
- Default the tool-call HTTP timeout to at least the plugin's maximum internal timeout, or expose a per-tool declared timeout.
- Document the proxy requirement in the CLI help for `tool call`, not only in the plugin README.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Detection results omit the documented `type` (MIME) field on every entry

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s img-eval tool call image_detect_images > out.json ; grep -c '"type"' out.json   # → 0 over 255 entries

#### Expected Behavior

Each ImageSource carries `type` (MIME), as documented in the plugin README's field list ('Each entry contains srcUrl, resolvedUrl, type (MIME), width, …').

#### Actual Behavior

No entry in either the filtered or unfiltered result contains a `type` key — 0/255 occurrences.

#### Root Cause Analysis

In ImageDetector's JS probe the `type` field is hard-coded to null for <img>, <a>, background, meta and picture paths; only <source> and <link> read getAttribute('type'), and neither element carried a type attribute on this page. Jackson omits null fields, so the key never appears. A MIME type is only knowable from Content-Type at download time, so the documented schema over-promises for DOM-detected entries.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDetector.kt (ImageSource.type, JS probe lines ~190, 222, 260, 358)`

#### AI Suggested Improvement

- Update the README to state that `type` is only populated for elements that declare a type attribute (<source>/<link>) and is otherwise omitted, or
- Derive a provisional MIME type from the URL extension for <img> entries (svg/png/jpg/webp) so the documented field is actually useful.
- Consider emitting nulls explicitly (Jackson NON_NULL → ALWAYS for this DTO) so consumers see a stable schema.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Dimension filters silently drop every source without dimensions — including the largest image on the page (1280px og:image)

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s img-eval tool call image_download_all --json '{"minWidth":200,"minHeight":120}'
# compare with the unfiltered detect list: the meta entry https://thumb.wikimedia.org/.../UN_Members_Flags2.JPG/1280px-… is absent from the downloads

#### Expected Behavior

A caller asking for 'images at least 200×120' expects the page's biggest image (the 1280px og:image) to be included, or at least to be told it was skipped for having unknown dimensions.

#### Actual Behavior

meta, link (favicon/apple-touch-icon) and <source> entries carry no width/height, so the filter evaluates (null ?? null ?? 0) >= minWidth → false and drops them. The 1280px OG photo was silently excluded; only 227 <img> entries were attempted. Exclusion is silent — totalAttempted just reads 227.

#### Root Cause Analysis

ImageToolExecutor's filter computes `(img.naturalWidth ?: img.width ?: 0) >= minW`, treating 'unknown dimensions' as zero, which is indistinguishable from 'too small'. There is no reporting of how many entries were dropped, and no way to opt dimension-less sources in.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/tools/ImageToolExecutor.kt (detectImages / downloadAll filter blocks)`

#### AI Suggested Improvement

- Treat null dimensions as 'unknown, keep' when a filter is present, or add an `includeUnknownDimensions` (default true) flag.
- Report filtered counts in the result (e.g. `totalDetected`, `filteredOut`, plus per-reason counts) so a surprising 227 is explainable.
- Alternatively fetch Content-Length/dimensions for candidate meta/link entries before filtering.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Single-object tool results are Java toString() strings, not structured JSON (lists are structured)

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s img-eval tool call image_download --json '{"url":"…"}'
# vs ./b4w.ps1 -s img-eval tool call image_detect_images

#### Expected Behavior

Machine-readable JSON for every tool result, consistent with the detection call.

#### Actual Behavior

image_download returns {"type":"ai.platon.pulsar.images.service.ImageDownloader.ImageDownloadResult","description":"ImageDownloadResult(url=…, filePath=…, bytesDownloaded=5204, contentType=image/webp, width=null, height=null, durationMs=2866, success=true, error=null)"} — the payload is an unstructured toString inside `description`, with nested parentheses/commas, so consumers must regex-parse it. image_detect_images returns a clean JSON array of objects in the same CLI.

#### Root Cause Analysis

The backend serializes a single non-serializable/undecorated result object as {type, description=toString()}, while list results are serialized structurally. The CLI prints whatever the backend returns (handle_tool_call does cli_println!("{}", result)).

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_tool_call() (~line 22660) and the backend's tool-result serializer for object payloads`

#### AI Suggested Improvement

- Serialize plugin tool results (ImageDownloadResult, BulkDownloadSummary, …) as proper JSON objects with named fields.
- If a plain-text fallback must exist, put it in a separate field and keep the structured payload intact.
- Add a CLI-side test asserting a single-object tool result is parseable as JSON.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: No documented way to list MCP tool names; `tool list` silently prints the whole general help

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 tool list        # → prints the full top-level help, exit 0
./b4w.ps1 --help tool      # → 2-line stub, no --json argument syntax, no name listing

#### Expected Behavior

A way to enumerate available tool names (or a clear error for an unknown subcommand).

#### Actual Behavior

`tool list` is not a subcommand: the CLI prints the entire command reference as if help had been requested, with exit code 0 — a user reasonably concludes it worked. `--help tool` documents only 'tool call <name>' with no argument syntax. Tool names are discoverable only via the undocumented bare `plugin` command (109 noisy lines mixing built-ins like `plugin click`/`plugin agent` with real plugin tools) or by calling GET /mcp/tools directly.

#### Root Cause Analysis

The `tool` command exposes only `call`; unknown subcommands fall through to the general help. Meanwhile the tool-call help text says 'list available tools with `plugin list` or GET /mcp/tools' — but `plugin list` lists plugins/JARs, not tool names, so the documented instruction does not work. A dynamic plugin-command path (`plugin <domain> <method>`, e.g. `plugin image download --url …`, verified working) exists but is absent from `--help plugins`.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2404 (tool call description); cli/browser4-cli/src/main.rs:29667 (error hint) and main.rs:22539 (handle_dynamic_plugin_command)`

#### AI Suggested Improvement

- Add `tool list [--filter <substr>]` that prints names from GET /mcp/tools (the CLI already fetches this endpoint).
- Fix the hint to say `tool list` / GET /mcp/tools instead of `plugin list`.
- Make unknown subcommands (e.g. `tool list` today) exit non-zero with 'unknown subcommand; did you mean …' rather than printing general help with exit 0.
- Filter the bare `plugin` listing to genuinely plugin-provided tools and document `plugin <domain> <method>` in `--help plugins`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Mixed natural-vs-rendered dimension filtering produces surprising survivors (a 50px icon passes minWidth=100)

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s img-eval tool call image_detect_images --json '{"minWidth":100,"minHeight":60}' | python -c "import json,sys; print([e['resolvedUrl'] for e in json.load(sys.stdin) if e.get('naturalWidth')])"

#### Expected Behavior

A minimum-size filter should behave predictably — either always on intrinsic size or always on rendered size.

#### Actual Behavior

The 50×50-rendered enwiki-25.svg logo survives minWidth:100 because its naturalWidth is 150; meanwhile every flag thumbnail is evaluated by rendered size because it is unloaded (naturalWidth null). Two different criteria are applied inside one result set.

#### Root Cause Analysis

The documented rule (`naturalWidth ?: width`) mixes intrinsic and layout dimensions depending on lazy-load state, so which criterion applies varies per image.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/tools/ImageToolExecutor.kt`

#### AI Suggested Improvement

- Offer an explicit choice (`dimensionBasis: natural|rendered`) defaulting to rendered for consistency.
- At minimum, include both dimensions in the result and state in the tool help which one the filter used for each entry.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Downloaded filenames retain URL percent-encoding

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s img-eval tool call image_download --json '{"url":"https://…/Flag_of_Switzerland_%28Pantone%29.svg/250px-Flag_of_Switzerland_%28Pantone%29.svg.png"}'

#### Expected Behavior

A human-readable filename, e.g. 250px-Flag_of_Switzerland_(Pantone).svg.webp.

#### Actual Behavior

Saved as 250px-Flag_of_Switzerland_%28Pantone%29.svg.webp (also seen with %E2%80%93 for en-dashes across the bulk set).

#### Root Cause Analysis

ImageUtils.suggestFilename() takes the raw last path segment and passes it to sanitizeFilename() without percent-decoding.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageUtils.kt:suggestFilename()`

#### AI Suggested Improvement

- Percent-decode the path segment (URLDecoder with UTF-8) before sanitizing.
- Strip/replace characters illegal on Windows (% is legal but confusing in shells) and collapse resulting runs.
- Cover this with a unit test alongside the existing ImageUtilsTest cases.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: ImageDownloadResult width/height are always null for single downloads but populated in bulk results

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s img-eval tool call image_download --json '{"url":"…"}'   # width=null, height=null
download_all results in the same session: width=250, height=167 etc.

#### Expected Behavior

The same result type should carry the same information regardless of which tool produced it — or the docs should say dimensions are only available via bulk detection.

#### Actual Behavior

Single downloads return width=null/height=null (also true for a 250×305 WebP that PIL decodes fine), while BulkDownloadSummary entries carry real dimensions. The README lists width/height among ImageDownloadResult's fields without qualification.

#### Root Cause Analysis

ImageDownloader only fills width/height from the detection context (result.copy(width = image.naturalWidth ?: image.width, …) at the bulk path); the single-download path has no detection context and never decodes the bytes, so the fields stay null.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDownloader.kt:358`

#### AI Suggested Improvement

- Populate dimensions for single downloads by reading the image header from the downloaded bytes (ImageIO, with a WebP-aware fallback), which also validates the payload.
- Otherwise document 'width/height are populated only when detection data is available (downloadAll)'. 

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: Plugin manifest reports a stale version (4.12.0-rc.1) for a 4.14.1-SNAPSHOT JAR

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 plugin list
# → browser4-images-4.14.1-SNAPSHOT.jar  v4.12.0-rc.1 sdk 4.14.1-SNAPSHOT loaded

#### Expected Behavior

The displayed plugin version matches the artifact being loaded.

#### Actual Behavior

The JAR file name and SDK are 4.14.1-SNAPSHOT while the manifest version reads v4.12.0-rc.1 — confusing when diagnosing version skew (the bundle builder refuses mismatched plugin versions elsewhere, so a stale manifest undermines that guarantee).

#### Root Cause Analysis

META-INF/browser4-plugin.json contains a hard-coded "version": "4.12.0-rc.1" that is not filtered from the Maven project version at build time.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/resources/META-INF/browser4-plugin.json`

#### AI Suggested Improvement

- Resource-filter the version field from ${project.version} at package time.
- Add a build-time check that the manifest version matches the artifact version.

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

**Completion Status:** Successful — all six task steps completed: 255 images detected (249 <img>, 1 CSS background, 1 meta, 2 <source>, 2 <link>), 241 passing the 100×60 filter, 3/3 single flag downloads verified on disk, and a 227/227 bulk download totalling 513,626 bytes with every file decodable. Completion required two unplanned workarounds: installing the browser4-images plugin by hand and supplying HTTPS_PROXY to the backend.

**Success Rate:** 85% — every step eventually succeeded, but the first three download attempts failed 100% until the proxy was configured, and the plugin had to be discovered and installed before any image tool worked at all.

**Issues Found:** 10

**Major Blockers:** 1) browser4-images was not installed in the backend (only captcha and swarm were), so every image tool was missing until I located the JAR under browser4-plugins/browser4-images/target/ and ran plugin install + stop. 2) Every download timed out with a generic 'HTTP request timed out … timeout=30s' because the machine reaches the internet through a Windows system proxy (127.0.0.1:10808) that Chrome uses and the plugin's OkHttp client does not; restarting the backend with HTTPS_PROXY fixed it instantly. 3) Rebuilding the plugin JAR from source failed in this environment (Kotlin/kapt 'Failed connecting to the daemon in 4 retries'), so I used the existing source-matched JAR.

**Most Confusing Aspects:** A first-time user cannot tell that plugin tools exist or that a plugin is missing: plugin list shows only two unrelated plugins, tool call fails without naming the available-but-uninstalled plugin, and the discovery hint points at `plugin list` which does not list tools. Once installed, the download failures are misleading — the CLI blames its own 30s timeout while the real cause (no proxy for the backend's HTTP client) is only documented in the plugin's README. Tool names are also split between two spellings (image.detectImages in help/READMEs vs image_detect_images on the CLI), and the same download can be invoked two different ways (tool call vs an undocumented `plugin image download`) with no help text either way.

**Most Valuable Improvements:** 1) Make plugin availability self-evident: bundle browser4-images with the runtime bundle or list first-party plugin JARs in plugin list with an install hint, and make unknown tool errors name the plugin to install. 2) Fix the download-proxy trap: use the OS proxy by default in the image downloader, and align the CLI tool-call timeout with the plugin's internal timeouts so the actionable error is what the user actually sees. 3) Return structured JSON for single-object tool results instead of a toString() wrapper. 4) Report filtered-out counts (and don't silently drop dimension-less sources such as the page's 1280px og:image).

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

#### Issue 1: browser4-images plugin is not installed or bundled by default; task silently impossible until discovered manually

cd D:/workspace/Browser4/Browser4-4.14 && ./b4w.ps1 plugin list
# then try: ./b4w.ps1 -s s1 tool call image_detect_images

#### Issue 2: All image downloads time out behind a system proxy; CLI reports a generic 30s HTTP timeout before the plugin's actionable error can surface

./b4w.ps1 -s img-eval tool call image_download --json '{"url":"https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9b/Flag_of_Nepal.svg/250px-Flag_of_Nepal.svg.png","outputPath":"…"}'
# (machine has Windows system proxy 127.0.0.1:10808 in HKCU Internet Settings; no HTTPS_PROXY env var)

#### Issue 3: Detection results omit the documented `type` (MIME) field on every entry

./b4w.ps1 -s img-eval tool call image_detect_images > out.json ; grep -c '"type"' out.json   # → 0 over 255 entries

#### Issue 4: Dimension filters silently drop every source without dimensions — including the largest image on the page (1280px og:image)

./b4w.ps1 -s img-eval tool call image_download_all --json '{"minWidth":200,"minHeight":120}'
# compare with the unfiltered detect list: the meta entry https://thumb.wikimedia.org/.../UN_Members_Flags2.JPG/1280px-… is absent from the downloads

#### Issue 5: Single-object tool results are Java toString() strings, not structured JSON (lists are structured)

./b4w.ps1 -s img-eval tool call image_download --json '{"url":"…"}'
# vs ./b4w.ps1 -s img-eval tool call image_detect_images

#### Issue 6: No documented way to list MCP tool names; `tool list` silently prints the whole general help

./b4w.ps1 tool list        # → prints the full top-level help, exit 0
./b4w.ps1 --help tool      # → 2-line stub, no --json argument syntax, no name listing

#### Issue 7: Mixed natural-vs-rendered dimension filtering produces surprising survivors (a 50px icon passes minWidth=100)

./b4w.ps1 -s img-eval tool call image_detect_images --json '{"minWidth":100,"minHeight":60}' | python -c "import json,sys; print([e['resolvedUrl'] for e in json.load(sys.stdin) if e.get('naturalWidth')])"

#### Issue 8: Downloaded filenames retain URL percent-encoding

./b4w.ps1 -s img-eval tool call image_download --json '{"url":"https://…/Flag_of_Switzerland_%28Pantone%29.svg/250px-Flag_of_Switzerland_%28Pantone%29.svg.png"}'

#### Issue 9: ImageDownloadResult width/height are always null for single downloads but populated in bulk results

./b4w.ps1 -s img-eval tool call image_download --json '{"url":"…"}'   # width=null, height=null
download_all results in the same session: width=250, height=167 etc.

#### Issue 10: Plugin manifest reports a stale version (4.12.0-rc.1) for a 4.14.1-SNAPSHOT JAR

./b4w.ps1 plugin list
# → browser4-images-4.14.1-SNAPSHOT.jar  v4.12.0-rc.1 sdk 4.14.1-SNAPSHOT loaded

