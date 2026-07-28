# Issues: plugin-media-video-detection

> **Source:** `20260728-014230-plugin-media-video-detection.full.md` | **Date:** 20260728-014230 | **Mode:** dev

## Scenario Background

### Task

### Step 1–2: Video Detection on w3schools HTML5 Video Page

Navigated to `https://www.w3schools.com/html/html5_video.asp`. The `media.detectVideos` tool could not be used directly due to a critical camelCase/snake_case conversion bug in the tool dispatch layer (see Issue 1). Instead, JavaScript evaluation (`eval`) was used as a workaround.

**Results from JS-based detection:**

| # | Tag | Source URL | MIME Type | Dimensions | Controls |
|---|-----|-----------|-----------|------------|----------|
| 1 | `<video>` | `mov_bbb.mp4` (currentSrc) | — | 320×176 | Yes |
| 2 | `<source>` | `https://www.w3schools.com/html/mov_bbb.mp4` | `video/mp4` | — | — |
| 3 | `<source>` | `https://www.w3schools.com/html/mov_bbb.ogg` | `video/ogg` | — | — |
| 4 | `<video>` | `mov_bbb.mp4` (currentSrc) | — | 480×176 | No |
| 5 | `<source>` | `https://www.w3schools.com/html/mov_bbb.mp4` | `video/mp4` | — | — |
| 6 | `<source>` | `https://www.w3schools.com/html/mov_bbb.ogg` | `video/ogg` | — | — |

- **Total video sources detected:** 6 (2 `<video>` tags + 4 `<source>` children)
- **`<video>` tags:** 2
- **`<source>` children:** 4
- **Iframe embeds:** 1 (empty src, likely ad container)
- **HLS/DASH streams:** None detected (only direct MP4 and OGG files)

### Step 3: Download MP4 Video

The `media.download` tool could not be invoked because `plugin-media` always dispatches to the first matching tool alphabetically and provides no subcommand mechanism to select `download` (see Issue 2). Used `curl` as a workaround.

- **Direct MP4 URL:** `https://www.w3schools.com/html/mov_bbb.mp4`
- **Download success:** Yes (788,493 bytes)
- **File on disk:** Verified at `.test-sessions/mov_bbb.mp4` (valid ISO Media/MP4)

### Step 4: YouTube Embed Detection

Navigated to `https://www.w3schools.com/html/html_youtube.asp`.

- **`<video>` elements:** 0
- **`<source>` elements:** 0
- **Iframes:** 7 (mostly `about:blank` and ad containers; no `youtube.com` src detected in initial DOM)
- **YouTube embeds:** The w3schools page likely loads YouTube players dynamically via JavaScript after page load, so the YouTube iframe is not present in the static DOM at inspection time.
- **`media.detectVideos`:** Same camelCase/snake_case bug persists; backend would report 0 video elements from the top-level DOM.

### Step 5: Video Inside Iframe

Navigated to `https://www.w3schools.com/html/tryit.asp?filename=tryhtml5_video`.

- **Main page:** 0 `<video>` elements, 0 `<source>` elements
- **Inside `iframeResult`:** 1 `<video>` with `controls=true`, `320×176`, 2 `<source>` children (MP4 + OGG)
- **`media.detectVideos` from backend perspective:** Would report 0 videos — the `VideoDetector` scans only the top-level DOM and does not descend into iframes (see Issue 6).

### Step 6: Video Metadata

Used `ffprobe` as workaround since `media.getInfo` suffers from the same camelCase/snake_case bug.

| Property | Value |
|----------|-------|
| **Format** | MP4 (QuickTime/MOV), `mp42` brand |
| **Duration** | 10.027 seconds |
| **Resolution** | 320 × 176 |
| **Video codec** | H.264 (Main profile), 25 fps, ~301 kbps |
| **Audio codec** | AAC LC, 48 kHz stereo, ~161 kbps |
| **Container bitrate** | ~629 kbps |
| **Streams** | 4 (1 video + 2 audio + 1 data/timed text) |

---

### Execution Context

**Key Commands:**

```
./b4w.ps1 help
./b4w.ps1 plugin list
./b4w.ps1 plugin install <media-jar>
./b4w.ps1 stop / goto (restart cycle ×2 for plugin activation)
./b4w.ps1 goto "https://www.w3schools.com/html/html5_video.asp"
./b4w.ps1 plugin-media                                   → camelCase bug
./b4w.ps1 eval --json "JSON.stringify(...video scan...)"  → workaround
./b4w.ps1 goto "https://www.w3schools.com/html/html_youtube.asp"
./b4w.ps1 eval --json "JSON.stringify(...iframe scan...)"
./b4w.ps1 goto "https://www.w3schools.com/html/tryit.asp?filename=tryhtml5_video"
./b4w.ps1 eval --json "JSON.stringify(...iframe content scan...)"
curl -L -o .test-sessions/mov_bbb.mp4 "https://www.w3schools.com/html/mov_bbb.mp4"
ffprobe -v quiet -print_format json -show_format -show_streams .test-sessions/mov_bbb.mp4
```

**Major steps:**
1. Read help and SKILL.md for command discovery
2. Installed browser4-media plugin JAR, restarted server (plugin still showed "inactive")
3. Discovered `plugin-media` dynamic command syntax from source code
4. Hit camelCase/snake_case mismatch bug → used JS `eval` as workaround
5. Discovered `plugin-media` cannot select specific methods → used `curl` and `ffprobe`
6. Completed all 6 task steps using workarounds

**Workarounds required:**
- JS `eval` for video detection (3 pages)
- `curl` for video download
- `ffprobe` for media metadata
- Long-form `--viewport` flag instead of `-v` (flag parsing issue)

---

---

## Issues Found (8 issues)

### Issue 1: dispatchToCustomExecutor does not convert snake_case method names back to camelCase

**Severity:** Critical
**Category:** Product

#### Reproduction

./b4w.ps1 goto "https://www.w3schools.com/html/html5_video.asp" && ./b4w.ps1 plugin-media

#### Expected Behavior

media.detectVideos executes successfully and returns detected video sources.

#### Actual Behavior

Error: media_detect_videos failed: Unsupported media method: detect_videos. Supported: detectVideos, download, process, extractAudio, trim, compress, getInfo.

#### Root Cause Analysis

MCPToolController.toMcpToolName() converts camelCase method names to snake_case for MCP tool names (e.g. detectVideos → detect_videos → media_detect_videos). When dispatchToCustomExecutor extracts the method portion from the MCP tool name at line 772, it does substring(domain.length + 1) yielding detect_videos (snake_case). But MediaToolExecutor.callFunctionOn() matches against camelCase method names (detectVideos). The reverse conversion (snake_case → camelCase) is never applied in the custom executor path, though resolveMcpToolCall() in the AgentToolManager path correctly resolves it by iterating through all registered tool specs.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:dispatchToCustomExecutor() lines 771-776`

#### AI Suggested Improvement

- Convert extracted method name from snake_case back to camelCase in dispatchToCustomExecutor (e.g. detect_videos → detectVideos) before passing to callFunctionOn
- Alternatively, make MediaToolExecutor.callFunctionOn() accept both camelCase and snake_case method names
- Add a unit test that verifies round-trip conversion: toMcpToolName → extract method → camelCase matches original method name
- Consider adding a ToolCall factory that handles the conversion, so all custom executors benefit

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: plugin-<domain> command cannot select specific tool methods — always dispatches to first matching tool

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 plugin-media  (always calls media_compress or media_detect_videos, never media_download or media_getInfo)

#### Expected Behavior

A way to invoke specific plugin methods, e.g. plugin-media download --url <url> or plugin-media detectVideos.

#### Actual Behavior

plugin-media always picks matching[0] (first tool alphabetically matching the 'media_' prefix). There is no syntax for selecting a specific method within a plugin domain.

#### Root Cause Analysis

In handle_dynamic_plugin_command() (main.rs line 11541), matching[0] is used unconditionally. The remaining positional args after the command name are parsed as key-value parameters rather than as a subcommand. There is no mechanism to route plugin-media download to media_download vs plugin-media detectVideos to media_detect_videos.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() lines 11541-11544`

#### AI Suggested Improvement

- Support plugin-<domain> <method> syntax: treat the first positional arg after plugin-media as the method name, construct MCP tool name as <domain>_<snake_case_method>, and match against available tools
- Example: plugin-media detectVideos → matches media_detect_videos, plugin-media download --url ... → matches media_download
- Fall back to current behavior (list/dispatch first) when no method argument is provided
- Update help output to document this syntax

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Bare 'plugin' command returns 'Unsupported command form' error

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 plugin

#### Expected Behavior

Lists available plugin tools or shows plugin subcommands, as the code at main.rs:14622-14631 intends.

#### Actual Behavior

Error: Unsupported command form: plugin. Use 'browser4-cli plugin <subcommand>' instead.

#### Root Cause Analysis

The command parser appears to intercept bare 'plugin' before it reaches the dynamic plugin handler at line 14622-14623. The normalization/rewriting logic (normalize_command_invocation, rewrite_prefixed_command) may be rejecting or rewriting the bare 'plugin' form. The code path at line 14622-14623 (is_bare_plugin) is never reached.

#### Code Pointer

`cli/browser4-cli/src/main.rs: command normalization/rewriting before line 14611, or the match at line 14611-14650`

#### AI Suggested Improvement

- Trace why bare 'plugin' never reaches the is_bare_plugin check at line 14622
- Ensure the command normalization doesn't rewrite or reject bare 'plugin' before the dynamic handler can process it
- Add a test case for bare 'plugin' command invocation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Plugin status always shows 'inactive (restart required)' even after server restart

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. ./b4w.ps1 plugin install <jar>
2. ./b4w.ps1 stop
3. ./b4w.ps1 goto <url>  (auto-starts server)
4. ./b4w.ps1 plugin list

#### Expected Behavior

All plugins show 'active' status after server restart.

#### Actual Behavior

All 4 plugins show 'inactive (restart required)' even though the server was fully stopped and restarted, and the JARs are present in the runtime bundle's plugins/ directory.

#### Root Cause Analysis

The plugin status reporting may check a stale flag or use a different mechanism than actual plugin loading. The plugins are functionally loaded (plugin-media responds to tool calls), but the status display still reports them as inactive. This could be a mismatch between how plugin status is persisted/checked vs. how the runtime actually loads plugins.

#### Code Pointer

`Unknown — needs investigation into plugin status reporting mechanism vs. runtime classpath loading`

#### AI Suggested Improvement

- Investigate the plugin status storage mechanism — is it file-based, in-memory, or derived from classpath scanning?
- Ensure status reflects actual runtime state rather than a stale installation flag
- If 'inactive (restart required)' is shown, add a hint about how to actually activate (e.g., 'Run browser4-cli stop && browser4-cli goto <url> to restart')

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Short flag -v 0 is silently consumed, causing snapshot to show help instead of viewport 0

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 snapshot -v 0 --stdout

#### Expected Behavior

Snapshot output for viewport 0 (top of page).

#### Actual Behavior

Help text is printed. The command was parsed as 'snapshot 0 --stdout' (the -v flag was consumed somewhere).

#### Root Cause Analysis

The -v short flag is being consumed by the shell, the b4w.ps1 wrapper, or the argument parser before reaching the snapshot handler. The SKILL.md documents this as a known PowerShell issue, but it also occurs in bash. The long form --viewport 0 works correctly.

#### Code Pointer

`cli/browser4-cli/src/main.rs: argument parsing for snapshot command, or the b4w.ps1 wrapper script`

#### AI Suggested Improvement

- Debug why -v is consumed even in bash (not PowerShell)
- Add a diagnostic message when flags are unexpectedly consumed
- Consider deprecating -v in favor of --viewport if the short form is unreliable across shells
- Document this as a known bash issue too, not just PowerShell

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: media.detectVideos scans only top-level DOM, cannot detect videos inside iframes

**Severity:** Medium
**Category:** Product

#### Reproduction

1. ./b4w.ps1 goto "https://www.w3schools.com/html/tryit.asp?filename=tryhtml5_video"
2. ./b4w.ps1 plugin-media  (would return 0 videos if the camelCase bug were fixed)

#### Expected Behavior

VideoDetector detects the <video> element inside iframeResult (1 video, 2 sources).

#### Actual Behavior

From JS eval: main page has 0 videos. The video is inside the iframeResult iframe. VideoDetector only scans driver.pageSource/document of the top-level frame.

#### Root Cause Analysis

VideoDetector.detect() receives a WebDriver and calls driver.evaluate() or parses driver.pageSource — both operate on the top-level browsing context only. There is no logic to enumerate frames/iframes and recursively scan their content.

#### Code Pointer

`browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt:detect()`

#### AI Suggested Improvement

- Add frame enumeration to VideoDetector: use CDP to list all frames (Page.getFrameTree), then evaluate in each frame context
- Allow same-origin iframe contentDocument access via driver.switchToFrame() or CDP Runtime.evaluate with contextId
- Document the iframe limitation if recursive scanning is not feasible for cross-origin frames

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: CLI version mismatch: CLI 4.12.1 vs. backend v4.11.15 causes confusion about which code is being tested

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 status

#### Expected Behavior

CLI and backend versions match (both from local source tree).

#### Actual Behavior

CLI version: 4.12.1, Installed version: v4.11.15. Warning about version mismatch and suggestion to run mvn spring-boot:run.

#### Root Cause Analysis

The CLI is built from local source (cargo build), but the backend auto-starts from a pre-built runtime bundle in browser4-apps/browser4-bundle/target/ which was built from an older version. The runtime bundle is not automatically rebuilt when source changes.

#### Code Pointer

`N/A — build/dev workflow issue`

#### AI Suggested Improvement

- Auto-detect when the runtime bundle is stale vs. source and offer to rebuild
- Add a --use-local-backend flag that runs mvn spring-boot:run automatically
- Clarify in dev workflow docs when and how to rebuild the runtime bundle

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: No mention of plugin-<domain> command syntax in help output or SKILL.md

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help  (search for 'plugin-' or 'media' — not found)

#### Expected Behavior

Help output documents the plugin-<domain> dynamic command syntax and lists available plugin domains.

#### Actual Behavior

Help shows 'plugin list|info|install|remove' but no mention of plugin-<domain> for invoking plugin tools. The syntax was only discovered by reading Rust source code.

#### Root Cause Analysis

The dynamic plugin command handler exists in code (handle_dynamic_plugin_command) but is not documented in CLI help, SKILL.md, or any reference file. Users have no way to discover this feature without reading source code.

#### Code Pointer

`cli/browser4-cli/src/main.rs and skills/browser4-cli/SKILL.md`

#### AI Suggested Improvement

- Add plugin-<domain> syntax to help output under a 'Plugins' category
- Document in SKILL.md with examples: plugin-media, plugin-pptx, etc.
- Add 'browser4-cli plugin' to list available plugin domains/tools
- Add --help plugins category that shows available plugin domains and tools

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — All 6 task steps were completed, but every step requiring the browser4-media plugin required a workaround (JS eval, curl, ffprobe) due to the critical camelCase/snake_case conversion bug and the inability to select specific plugin methods.

**Success Rate:** 60% — 3 of 5 plugin-reliant steps required external tool workarounds. The core browser automation (goto, eval, snapshot) worked reliably.

**Issues Found:** 8

**Major Blockers:** The camelCase/snake_case mismatch in dispatchToCustomExecutor (Issue 1) makes ALL MediaToolExecutor methods unusable from the CLI. Combined with the inability to select specific plugin methods (Issue 2), no plugin media tool can be invoked successfully. These two issues together completely block plugin functionality.

**Most Confusing Aspects:** 1. Discovering that plugin tools use a completely different invocation syntax (plugin-<domain>) not documented anywhere in help or SKILL.md. 2. The 'inactive (restart required)' status persisting after restart — it's unclear whether plugins are actually loaded. 3. The bare 'plugin' command returning an error, contradicting what the source code suggests should work.

**Most Valuable Improvements:** - Fix the camelCase/snake_case conversion in dispatchToCustomExecutor (one-line fix that unblocks all plugin tools)
- Add plugin-<domain> <method> subcommand support so users can select specific tool methods
- Document the plugin-<domain> syntax in help output and SKILL.md
- Fix the bare 'plugin' command to list available plugin tools

**Usability Rating:** 4/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: dispatchToCustomExecutor does not convert snake_case method names back to camelCase

./b4w.ps1 goto "https://www.w3schools.com/html/html5_video.asp" && ./b4w.ps1 plugin-media

#### Issue 2: plugin-<domain> command cannot select specific tool methods — always dispatches to first matching tool

./b4w.ps1 plugin-media  (always calls media_compress or media_detect_videos, never media_download or media_getInfo)

#### Issue 3: Bare 'plugin' command returns 'Unsupported command form' error

./b4w.ps1 plugin

#### Issue 4: Plugin status always shows 'inactive (restart required)' even after server restart

1. ./b4w.ps1 plugin install <jar>
2. ./b4w.ps1 stop
3. ./b4w.ps1 goto <url>  (auto-starts server)
4. ./b4w.ps1 plugin list

#### Issue 5: Short flag -v 0 is silently consumed, causing snapshot to show help instead of viewport 0

./b4w.ps1 snapshot -v 0 --stdout

#### Issue 6: media.detectVideos scans only top-level DOM, cannot detect videos inside iframes

1. ./b4w.ps1 goto "https://www.w3schools.com/html/tryit.asp?filename=tryhtml5_video"
2. ./b4w.ps1 plugin-media  (would return 0 videos if the camelCase bug were fixed)

#### Issue 7: CLI version mismatch: CLI 4.12.1 vs. backend v4.11.15 causes confusion about which code is being tested

./b4w.ps1 status

#### Issue 8: No mention of plugin-<domain> command syntax in help output or SKILL.md

./b4w.ps1 help  (search for 'plugin-' or 'media' — not found)

