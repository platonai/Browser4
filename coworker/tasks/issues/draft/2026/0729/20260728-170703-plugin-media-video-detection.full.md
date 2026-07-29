---

## Deliverables

# A. Task Result

### Step 1-2: Video Detection on w3schools HTML5 Video Page

`media.detectVideos` found **2 video sources**:

| # | Tag | URL | MIME | Dimensions | Controls | HLS/DASH |
|---|-----|-----|------|------------|----------|-----------|
| 1 | `video` | `https://www.w3schools.com/html/mov_bbb.mp4` | (none) | 320×176 | Yes | No |
| 2 | `source` | `https://www.w3schools.com/html/mov_bbb.ogg` | `video/ogg` | 320×176 | (inherited) | No |

- **By tag type:** 1 `<video>`, 1 `<source>`, 0 `<iframe>` embeds
- **HLS/DASH streams:** None detected
- **⚠ Limitation:** The page actually has 2 `<video>` elements and 4 `<source>` children, but the URL-based deduplication collapsed them

### Step 3: Media Download

| Property | Value |
|---|---|
| **success** | `true` ✓ |
| **bytesDownloaded** | 788,493 (> 0) ✓ |
| **filePath** | `.test-sessions/mov_bbb.mp4` — exists on disk ✓ |
| **contentType** | `video/mp4` |

### Step 4: YouTube Page

`media.detectVideos` on `html_youtube.asp` returned `[]` — **no video sources detected**. The page references YouTube in tutorial code examples but does not embed live YouTube iframes in the current browser context.

### Step 5: Tryit Editor (iframe video)

- **Eval:** `iframeResult` (same-origin) contains 1 `<video>` element
- **media.detectVideos:** `[]` — detection does not search inside iframe content documents

### Step 6: Media Info (ffprobe)

| Property | Value |
|---|---|
| **Format** | MP4 (ISO Base Media) |
| **Duration** | 10.027 seconds |
| **Resolution** | 320 × 176 |
| **Codec** | H.264 (video), AAC (audio) |
| **Bitrate** | ~629 kbps |
| **Streams** | 4 total: 1 video (h264) + 2 audio (aac) + 1 data |

# B. Execution Trace

- **Commands used:** `help`, `plugin list`, `plugin install`, `kill-all`, `goto` ×3, `plugin-media` ×3, `eval` ×6, `snapshot`, `list`, direct `curl` MCP calls ×2
- **Code modified:** `browser4-rest/.../MCPToolController.kt:771-777` — fixed snake_case → camelCase method name resolution in `dispatchToCustomExecutor`
- **Workarounds:** Manual plugin install, server restart, code fix + JAR repackaging, direct HTTP calls for specific tools (CLI plugin command only invokes first matching tool)

# C & D: Issues Found & Overall Assessment

```json
{
  "issues": [
    {
      "title": "snake_case/camelCase mismatch in custom executor dispatch breaks plugin tools",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "./b4w.ps1 plugin-media after installing browser4-media plugin",
      "expected": "media.detectVideos returns video source list from the current page",
      "actual": "ERROR: media_detect_videos failed: Unsupported media method: detect_videos. Supported: detectVideos, download, process, extractAudio, trim, compress, getInfo.",
      "rootCause": "dispatchToCustomExecutor() extracts the method name from the MCP tool name as a raw snake_case substring (e.g. 'detect_videos'), but the ToolExecutor.callFunctionOn() when-block expects camelCase method names (e.g. 'detectVideos'). The resolveMcpToolCall() path (used by the agent dispatch) correctly reverse-looks up method names via tool specs, but dispatchToCustomExecutor() does not.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:dispatchToCustomExecutor() lines 771-776",
      "suggestion": "- Convert the raw snake_case method name to camelCase by reverse-matching through executor.getToolSpecs() keys using toMcpToolName(domain, specMethod) == toolName\n- Alternatively, add a general-purpose snakeToCamelCase() utility and apply it to all methods extracted from MCP tool names\n- Add a unit test that verifies media_detect_videos → detectVideos resolution"
    },
    {
      "title": "plugin-<domain> command only invokes the first matching tool — no way to select a specific tool",
      "severity": "High",
      "category": "UX",
      "reproduction": "./b4w.ps1 plugin-media — always calls media_detect_videos regardless of intent",
      "expected": "A way to specify which tool to call, e.g. plugin-media download <url> or plugin-media.getInfo <file>",
      "actual": "handle_dynamic_plugin_command() takes the first matching tool name (matching[0]) and calls it. All 7 media tools match prefix 'media_', and only media_detect_videos is ever invoked. Other tools like download, getInfo are unreachable via the CLI.",
      "rootCause": "The CLI's handle_dynamic_plugin_command() at main.rs:11992-12012 filters tools by domain prefix, then unconditionally uses matching[0]. There is no subcommand or method-selection mechanism. The design assumes one tool per domain, but plugins typically register multiple tools.",
      "codePointer": "cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() lines 11992-12012",
      "suggestion": "- Extend the CLI to accept plugin-<domain>.<method> format (e.g. plugin-media.download)\n- Or use an additional positional argument: plugin-media download <args>\n- The second non-prefixed positional arg could be matched against known tool names in the domain\n- Update help output to list available subcommands when plugin-<domain> is run bare"
    },
    {
      "title": "Aggressive URL-based deduplication in VideoDetector loses distinct video elements",
      "severity": "High",
      "category": "Product",
      "reproduction": "Navigate to https://www.w3schools.com/html/html5_video.asp and run media.detectVideos",
      "expected": "4 results: 2 <video> elements (one with controls, one without) and 4 <source> children",
      "actual": "2 results: 1 <video> and 1 <source>. The second <video> and 3 <source> children were lost because they had the same resolvedUrl as the first occurrences.",
      "rootCause": "VideoDetector.parseResult() at line 104 applies .distinctBy { it.resolvedUrl ?: it.srcUrl } which treats elements with the same media URL as duplicates. Different DOM elements (separate <video> tags, separate <source> children) that happen to reference the same URL are incorrectly collapsed.",
      "codePointer": "browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt:parseResult() line 104",
      "suggestion": "- Use a compound key for deduplication: tagName + resolvedUrl, or include a DOM path/sequence number\n- Or remove distinctBy entirely and let consumers deduplicate as needed — the raw detection should report what exists in the DOM\n- Add a 'sourceIndex' or 'elementIndex' field to VideoSource so consumers can distinguish multiple elements with the same URL"
    },
    {
      "title": "VideoDetector does not search inside iframe content documents",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "Navigate to https://www.w3schools.com/html/tryit.asp?filename=tryhtml5_video and run media.detectVideos",
      "expected": "Detection finds the <video> element inside the iframeResult iframe (same-origin), or at minimum reports that videos exist but are inside iframes",
      "actual": "Returns []. No videos detected.",
      "rootCause": "The DETECTION_SCRIPT runs document.querySelectorAll('video') in the main page context only. It does not attempt to access iframe.contentDocument for same-origin iframes. The <iframe> detection logic only checks iframe src attributes for known player domains (YouTube, Vimeo, etc.), not for embedded video content.",
      "codePointer": "browser4-plugins/browser4-media/src/main/kotlin/ai/platon/pulsar/media/service/VideoDetector.kt:DETECTION_SCRIPT",
      "suggestion": "- Extend the detection script to iterate over same-origin iframes and query for video elements inside them\n- For cross-origin iframes, at minimum report them as potential video containers\n- Add an isInIframe field to VideoSource to indicate the detection context"
    },
    {
      "title": "Plugin tools are undocumented in CLI help and invisible to users",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "Run ./b4w.ps1 help or ./b4w.ps1 --help agent and look for media tools",
      "expected": "Plugin tools appear in help output or at minimum there is a hint about how to discover them",
      "actual": "No mention of plugin tools in help output. Running ./b4w.ps1 plugin (bare) gives error 'Unsupported command form: plugin. Use browser4-cli plugin <subcommand> instead.' No hint to use plugin-media or how to list available tool domains.",
      "rootCause": "The CLI help system only documents built-in commands. Dynamic plugin commands (plugin-<domain>) are discovered at runtime from the server's /mcp/tools endpoint, but this discovery is not surfaced in help output. The bare 'plugin' command is blocked by preferred_spaced_command_form() before reaching the dynamic plugin handler.",
      "codePointer": "cli/browser4-cli/src/main.rs: lines 15080-15090 (preferred_spaced_command_form blocks bare 'plugin') and help generation code",
      "suggestion": "- Add a 'Plugin Tools' section to the main help output listing available plugin-<domain> commands\n- Fix the bare 'plugin' command to work and list available tool domains (it already has the code for this at line 11981-11989 but can't be reached)\n- Add a '--help plugins' category filter\n- Consider listing plugin tools in the output of ./b4w.ps1 --help agent"
    },
    {
      "title": "Complex return types wrapped in description maps lose structured data",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Call media.download or media.getInfo — observe the response format",
      "expected": "Clean JSON with fields like {\"success\": true, \"filePath\": \"...\", \"bytesDownloaded\": 788493}",
      "actual": "Result wrapped in {\"type\":\"...DownloadResult\",\"description\":\"DownloadResult(url=..., filePath=..., ...)\"} — a toString() representation in a description field. Consumers must parse the description string to extract field values.",
      "rootCause": "AbstractToolExecutor.callFunctionOn(ToolCall, Any) at line 76-86 wraps non-primitive, non-collection results in a description map to prevent Jackson from walking into internal object graphs. While this prevents serialization errors, it makes the output effectively unparseable for machine consumers.",
      "codePointer": "browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/AbstractToolExecutor.kt: lines 76-86",
      "suggestion": "- Add a marker interface (e.g. SafeToSerialize) that domain result classes can implement to opt into direct JSON serialization\n- Or annotate result data classes with Jackson annotations so they serialize cleanly\n- As a minimum, include a 'fields' map alongside the description so machines can access individual fields without parsing"
    },
    {
      "title": "Media plugin must be manually installed — not part of default dev setup",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Start fresh with only the repository. Run ./b4w.ps1 plugin list",
      "expected": "browser4-media appears in the plugin list or there are docs explaining how to enable it",
      "actual": "The plugin JAR exists at browser4-plugins/browser4-media/target/ but is not installed. User must discover the plugin install command and know the JAR path. No README or CLAUDE.md mentions this requirement.",
      "rootCause": "The dev mode auto-starts the backend from a pre-built runtime bundle. Plugins are not automatically installed — they must be explicitly installed via plugin install. There is no documentation linking the task (testing the media plugin) to the setup steps required.",
      "codePointer": "",
      "suggestion": "- Add browser4-media to the default set of plugins included in the dev runtime bundle\n- Or document in CLAUDE.md that plugin testing requires manual plugin install steps\n- Add a --with-plugins flag or similar to the dev server startup that auto-installs all locally-built plugins"
    },
    {
      "title": "Server restart required after plugin install with no auto-restart option",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.ps1 plugin install <jar> then try to use the plugin",
      "expected": "Plugin activates immediately or CLI offers to restart the server",
      "actual": "Plugin installs but shows 'inactive (restart required)'. User must manually kill-all and restart. No hint about the activation workflow.",
      "rootCause": "Plugin JARs are loaded at JVM startup by Spring Boot's classpath scanning. Hot-reload of plugin JARs is not implemented. The CLI's plugin install command could detect when the server is running and offer a restart.",
      "codePointer": "cli/browser4-cli/src/main.rs:handle_plugin_install()",
      "suggestion": "- After plugin install, if the server is running, print a clear message: 'Run browser4-cli kill-all then retry your command to activate'\n- Or add a --restart flag to plugin install that kills and restarts the server automatically\n- Consider implementing hot-deploy for plugins in dev mode"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — All task steps were completed but required a code fix to the dispatch layer, manual JAR patching, and direct HTTP calls to work around CLI limitations. The media plugin's core functionality (detection, download, ffprobe) works correctly once the dispatch bug is fixed.",
    "successRate": "75% — 6 of 8 task sub-steps succeeded directly; 2 required workarounds (dispatch bug fix, direct HTTP calls for tool selection)",
    "issuesFound": 8,
    "majorBlockers": "The snake_case/camelCase dispatch bug (Issue 1) completely prevented plugin tool invocation from the CLI. Without the code fix, no plugin tools could be called. The CLI's inability to select a specific tool within a plugin domain (Issue 2) required using curl for download and getInfo.",
    "mostConfusingAspects": "1) Discovering that plugin tools exist at all — no mention in help, bare 'plugin' command rejected. 2) Figuring out the invocation syntax (plugin-media vs media.detectVideos vs other forms). 3) Understanding why the command that was found (plugin-media) produced a cryptic 'Unsupported method' error instead of a clear 'use plugin-media.detectVideos' suggestion. 4) Realizing that plugin-media always calls the same tool and there's no way to reach download/getInfo through the CLI.",
    "mostValuableImprovements": "1) Fix the snake_case/camelCase dispatch bug — this is a one-line logical fix that unblocks all plugin tools. 2) Support plugin-<domain>.<method> syntax so users can invoke specific tools. 3) Add plugin tool discovery to help output and make bare 'plugin' command work. 4) Improve structured output for tool results — the current description-map wrapping makes machine consumption impossible.",
    "usabilityRating": 4
  }
}
```
