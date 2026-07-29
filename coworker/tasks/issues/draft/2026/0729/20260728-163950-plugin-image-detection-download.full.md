---

# Evaluation Report: browser4-images Plugin

## A. Task Result

**Partially Successful.** The task was completed using workarounds because the native plugin tools were non-functional.

### Detection Results (via `eval` workaround):

| Metric | Value |
|---|---|
| **Total images detected** | 591 (588 `<img>`, 2 `<link>`, 1 `<meta>`, 0 backgrounds) |
| **Passing min 100×60 filter** | 239 |
| **Top 5 largest by dimensions** | Nepal (250×305), Qulla Suyu (250×250), Switzerland (250×250), Vatican City (250×250), Belgium (250×217) |

### Download Results:

| Tool | Result |
|---|---|
| `image.detectImages` | **Broken** — returned empty `[]` |
| `image.download` | **Failed** — "Network is unreachable" (OkHttp) |
| `image.downloadAll` | **Empty** — 0 attempted (depends on broken detect) |
| `image.downloadBatch` | **Failed** — 2/2 failed with network errors |
| `curl` workaround | **Success** — 3 flags downloaded (Albania 14.4KB, Brazil 20.7KB, Japan 3.1KB) |

### File verification:
- ✓ Flag_of_Albania.png: 14,770 bytes (>1KB)
- ✓ Flag_of_Brazil.png: 21,238 bytes (>1KB)  
- ✓ Flag_of_Japan.png: 3,181 bytes (>1KB)

## B. Execution Trace

**Commands used:** `help`, `plugin list`, `plugin install`, `stop`, `goto`, `eval`, `eval --file`, `snapshot -v 0`, `doctor log`, `curl` (MCP endpoint)

**Major steps:** Verify directory → read skill docs → install plugin → restart server → navigate to Wikipedia → debug `image.detectImages` returning empty → trace to `evaluate` vs `evaluateValue` bug → work around detection with `eval` → test all three download tools (all fail) → work around download with `curl` → verify files on disk.

**Workarounds required:** 3 (custom JS eval for detection, curl for MCP calls, curl for downloads)

---

## C. Issues Found

```json
{
  "issues": [
    {
      "title": "image.detectImages returns empty results — driver.evaluate() vs evaluateValue() mismatch",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "curl -X POST http://localhost:8182/mcp/call-tool -H 'Content-Type: application/json' -d '{\"tool\":\"image_detectImages\",\"arguments\":{\"sessionId\":\"<id>\"}}'",
      "expected": "Returns list of 588+ detected images from the Wikipedia flags page.",
      "actual": "Returns empty array [] with no error.",
      "rootCause": "ImageDetector.detect() calls driver.evaluate(DETECTION_SCRIPT) which uses CDP Runtime.evaluate with returnByValue=false. For the large JSON string returned by the detection script (588 img entries), the CDP response's result.value is null when returnByValue is false for non-trivial strings. The CLI eval command uses evaluateValue() (returnByValue=true) which works fine. Fix: change driver.evaluate() to driver.evaluateValue() in ImageDetector.kt:93.",
      "codePointer": "browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDetector.kt:detect() line 93 — change driver.evaluate(DETECTION_SCRIPT) to driver.evaluateValue(DETECTION_SCRIPT)",
      "suggestion": "- Change driver.evaluate(DETECTION_SCRIPT) to driver.evaluateValue(DETECTION_SCRIPT) in ImageDetector.detect()\n- Add a unit test that verifies detection returns results when the page has images\n- Consider adding a warning log when detection script returns empty/zero results on a page known to have images"
    },
    {
      "title": "MCP tool name snake_case/camelCase mismatch breaks dispatch",
      "severity": "High",
      "category": "Product",
      "reproduction": "Call image_detect_images (snake_case, as advertised by /mcp/tools) via MCP endpoint. The dispatch derives method='detect_images' but executor expects 'detectImages'.",
      "expected": "Both image_detect_images and image_detectImages should work.",
      "actual": "image_detect_images returns error: 'Unsupported image method: detect_images'. image_detectImages (camelCase, undocumented) works.",
      "rootCause": "toMcpToolName() converts camelCase method names to snake_case for MCP tool names. But dispatchToCustomExecutor() derives the method name by simple substring (domain_ prefix removal), producing snake_case that doesn't match the executor's camelCase method keys. The /mcp/tools endpoint advertises snake_case names, which don't work. A reverse lookup from snake_case to camelCase is needed.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:dispatchToCustomExecutor() line ~772 — add snake_case→camelCase reverse conversion before calling executor",
      "suggestion": "- In dispatchToCustomExecutor(), convert snake_case method name back to camelCase before passing to executor.callFunctionOn()\n- Or add a reverse lookup by checking each toolSpec key against the derived method name using case-insensitive matching\n- Add test: verify toMcpToolName() round-trips correctly through the dispatch path for all registered tool executors"
    },
    {
      "title": "image.download fails with 'Network is unreachable' from OkHttp",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "curl -X POST http://localhost:8182/mcp/call-tool -d '{\"tool\":\"image_download\",\"arguments\":{\"sessionId\":\"<id>\",\"url\":\"https://en.wikipedia.org/static/images/icons/enwiki-25.svg\"}}'",
      "expected": "Image downloads successfully via OkHttp.",
      "actual": "All downloads fail with 'IO error: Network is unreachable'. Times out after 30-120 seconds. curl from the same shell succeeds.",
      "rootCause": "OkHttp client in ImageDownloader cannot reach external URLs in this environment while system curl and Chrome can. Likely a JVM-level network configuration issue (proxy, DNS, or IPv4/IPv6). The 120-second timeout is excessive — OkHttp default connect timeout is 10s but observed 30-120s suggests retry or DNS resolution delays.",
      "codePointer": "browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDownloader.kt — OkHttp client configuration and error handling. Also check ImageAutoConfiguration.kt for OkHttpClient bean setup.",
      "suggestion": "- Configure OkHttpClient with explicit connect/read timeouts (e.g., 15s) instead of relying on defaults\n- Add a connectivity check before attempting downloads and surface a clear error message\n- Consider falling back to browser-mediated download (via CDP Network.loadNetworkResource or fetch) when direct HTTP fails\n- Log the specific IOException details (DNS failure vs connect timeout vs route) for easier diagnosis"
    },
    {
      "title": "Plugin 'inactive (restart required)' status is permanently misleading",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1. Install plugin: ./b4w.ps1 plugin install <jar>\n2. Stop server: ./b4w.ps1 stop\n3. Restart and check: ./b4w.ps1 plugin list\nPlugin still shows 'inactive (restart required)' even though server logs confirm it's loaded and running.",
      "expected": "After server restart, plugin status should show 'active' or 'running'.",
      "actual": "Plugin always shows 'inactive (restart required)' in plugin list output, even when server logs confirm: '✓ Registered tool executor for domain image' and 'browser4-images plugin started'.",
      "rootCause": "The status string is hardcoded or derived from a condition that never clears. Investigation needed in PluginService or PluginController to understand why the status field doesn't update after a successful plugin load.",
      "codePointer": "browser4-rest or browser4-browser-plugin module — PluginService or PluginController where plugin status is computed for the plugin list endpoint",
      "suggestion": "- Fix the status detection to reflect actual plugin activation state after server startup\n- Consider adding a 'status' field to the plugin list JSON output showing actual runtime state (loaded/running/error)\n- Add an integration test that installs a plugin, restarts, and verifies the status shows as active"
    },
    {
      "title": "No CLI commands for image plugin tools — requires raw MCP HTTP calls",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "Try to use image detection from browser4-cli directly. No image, detect-images, or download command exists in the CLI.",
      "expected": "CLI commands like `browser4-cli image detect` or `browser4-cli image-download` that wrap the plugin's MCP tools.",
      "actual": "Image tools are only accessible via raw HTTP POST to http://localhost:8182/mcp/call-tool with JSON body. Not documented in help output or SKILL.md.",
      "rootCause": "Plugin tools are registered as AI agent tools (for LLM use) but not exposed as CLI commands. The CLI-to-backend bridge only maps specific tools to commands; plugin tools need explicit mapping or a generic 'call-tool' CLI command.",
      "codePointer": "cli/browser4-cli/src/ — commands registration. A generic 'mcp-call' or plugin-tool CLI command could be added.",
      "suggestion": "- Add a generic CLI command like `browser4-cli tool <domain>.<method> [args...]` that forwards to /mcp/call-tool\n- Or add specific `browser4-cli image detect`, `browser4-cli image download` commands\n- Document plugin tool access in SKILL.md\n- List available plugin tools in `plugin info <name>` output"
    },
    {
      "title": "image.downloadAll returns success with all zeros, masking the detection failure",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "Call image.downloadAll on a page with 588 images. Returns success with totalAttempted=0, successful=0, failed=0.",
      "expected": "Should return error or at minimum a non-zero attempted count when the page has images.",
      "actual": "Returns BulkDownloadSummary with all fields zero (totalAttempted=0, successful=0, failed=0, totalBytesDownloaded=0, results=[]). No error or warning.",
      "rootCause": "downloadAll depends on imageDetector.detect() which returns empty list (Issue 1). The empty list propagates silently — no warning when a page with images produces zero detections.",
      "codePointer": "browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/tools/ImageToolExecutor.kt:callFunctionOn() lines 165-183 — downloadAll handler",
      "suggestion": "- Add a warning when detect() returns 0 results but driver.pageSource contains <img> tags (quick sanity check)\n- Consider returning an error or at least logging a warning when downloadAll finds zero images\n- The BulkDownloadSummary could include a warning field"
    },
    {
      "title": "Server restart takes 15+ seconds on every stop/start cycle",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run ./b4w.ps1 stop followed by any command. Observe 15s startup delay.",
      "expected": "Faster server restart (< 5s) or instant plugin hot-reload.",
      "actual": "Server startup takes approximately 15 seconds on every cold start. The JVM loading time is the bottleneck.",
      "rootCause": "JVM startup + Spring Boot initialization + Chrome browser launch all happen sequentially. Plugin hot-reload could avoid JVM restart.",
      "codePointer": "browser4-rest and browser4-browser startup configuration",
      "suggestion": "- Consider implementing hot-reload for plugin JARs (watch plugins/ directory)\n- Add a progress indicator during startup (currently only shows elapsed seconds)\n- Cache the JVM process between plugin changes when possible"
    },
    {
      "title": "ArgumentNormalizer strips sessionId requiring manual re-add in dispatch",
      "severity": "Low",
      "category": "Product",
      "reproduction": "Any MCP tool call through dispatchToToolExecutor — DefaultArgumentNormalizer.normalize() removes sessionId from args.",
      "expected": "Argument normalization should preserve sessionId and other essential parameters.",
      "actual": "sessionId is explicitly removed in DefaultArgumentNormalizer (line 19), then re-added manually in dispatchToToolExecutor (lines 713-718). This is error-prone.",
      "rootCause": "Design choice to strip sessionId from normalized args since many tool executors don't need it. But custom executors DO need it for WebDriver access. The re-add logic is fragile.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/ArgumentNormalizers.kt:19 — DefaultArgumentNormalizer.normalize() sessionId removal",
      "suggestion": "- Don't strip sessionId in the default normalizer; let individual normalizers decide\n- Or pass sessionId separately through the dispatch chain rather than in args\n- Add a comment documenting why sessionId is removed and re-added"
    },
    {
      "title": "No plugin tool documentation in help or SKILL.md",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Run ./b4w.ps1 help, search for 'image' or 'plugin tool'. Read skills/browser4-cli/SKILL.md, search for image tools.",
      "expected": "Documentation explaining how to use plugin-provided tools (image detection, download, etc.).",
      "actual": "No mention of image tools in help output or SKILL.md. Plugin section only covers install/list/remove. Tools are only discoverable via /mcp/tools HTTP endpoint.",
      "rootCause": "Plugin tools are designed for AI agent consumption, not direct CLI use. But the documentation gap means users don't know these tools exist or how to call them.",
      "codePointer": "skills/browser4-cli/SKILL.md and cli/browser4-cli/src/ help output",
      "suggestion": "- Add a 'Plugin Tools' section to SKILL.md listing available plugin tools per installed plugin\n- Add `plugin tools <name>` CLI command to list tools provided by a plugin\n- Include tool schemas (arguments, return types) in plugin info output"
    },
    {
      "title": "Plugin download error message is unhelpful ('Network is unreachable' after 120s)",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Attempt image.download when OkHttp cannot reach the target URL.",
      "expected": "Clear error indicating connectivity issue with a suggested fix (check proxy, firewall, DNS).",
      "actual": "Generic 'IO error: Network is unreachable' after a 30-120 second wait. No suggestion for troubleshooting.",
      "rootCause": "OkHttp's IOException is passed through without enrichment. No differentiation between DNS failure, connect timeout, or route issues.",
      "codePointer": "browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDownloader.kt:250-259 — catch block for IOException",
      "suggestion": "- Catch specific IOException subclasses (SocketTimeoutException, UnknownHostException, ConnectException) and provide targeted error messages\n- Add suggestions: 'Check network connectivity', 'Verify proxy settings in JAVA_OPTS', 'Try using --server flag'\n- Reduce default connect timeout from implicit 120s to explicit 15s"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — Task completed using workarounds (eval for detection, curl for download). The native image.detectImages, image.download, image.downloadAll, and image.downloadBatch tools are all non-functional due to two critical bugs.",
    "successRate": "40% — 2 of 5 plugin tools work (plugin install, plugin list), but the 3 core image tools (detectImages, download, downloadAll) are broken. Task goals achieved via workarounds.",
    "issuesFound": 10,
    "majorBlockers": "1) image.detectImages returns empty results due to driver.evaluate() vs evaluateValue() mismatch. 2) image.download fails with JVM network errors (OkHttp cannot reach external URLs). These two bugs cascade: downloadAll depends on detectImages and both detection and download are broken.",
    "mostConfusingAspects": "1) Plugin shows 'inactive (restart required)' even after restarting server — but server logs show it IS active. 2) MCP tool names use snake_case (image_detect_images) but the actual dispatch requires camelCase (image_detectImages) — the advertised names don't work. 3) No CLI commands for plugin tools — must discover and call /mcp/call-tool HTTP endpoint manually.",
    "mostValuableImprovements": "1) Fix driver.evaluate() → driver.evaluateValue() in ImageDetector (one-line fix). 2) Add snake_case→camelCase reverse mapping in MCP dispatch. 3) Add CLI commands for plugin tools. 4) Configure OkHttp with proper timeouts and network settings. 5) Fix plugin status display to show actual runtime state.",
    "usabilityRating": 3
  }
}
```
