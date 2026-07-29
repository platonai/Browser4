# Issues: plugin-image-detection-download

> **Source:** `20260728-163950-plugin-image-detection-download.full.md` | **Date:** 20260728-163950 | **Mode:** dev

## Scenario Background

### Task

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

### Execution Context

**Key Commands:**

**Major steps:** Verify directory → read skill docs → install plugin → restart server → navigate to Wikipedia → debug `image.detectImages` returning empty → trace to `evaluate` vs `evaluateValue` bug → work around detection with `eval` → test all three download tools (all fail) → work around download with `curl` → verify files on disk.

**Workarounds required:** 3 (custom JS eval for detection, curl for MCP calls, curl for downloads)

---

---

## Issues Found (10 issues)

### Issue 1: image.detectImages returns empty results — driver.evaluate() vs evaluateValue() mismatch

**Severity:** Critical
**Category:** Product

#### Reproduction

curl -X POST http://localhost:8182/mcp/call-tool -H 'Content-Type: application/json' -d '{"tool":"image_detectImages","arguments":{"sessionId":"<id>"}}'

#### Expected Behavior

Returns list of 588+ detected images from the Wikipedia flags page.

#### Actual Behavior

Returns empty array [] with no error.

#### Root Cause Analysis

ImageDetector.detect() calls driver.evaluate(DETECTION_SCRIPT) which uses CDP Runtime.evaluate with returnByValue=false. For the large JSON string returned by the detection script (588 img entries), the CDP response's result.value is null when returnByValue is false for non-trivial strings. The CLI eval command uses evaluateValue() (returnByValue=true) which works fine. Fix: change driver.evaluate() to driver.evaluateValue() in ImageDetector.kt:93.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDetector.kt:detect() line 93 — change driver.evaluate(DETECTION_SCRIPT) to driver.evaluateValue(DETECTION_SCRIPT)`

#### AI Suggested Improvement

- Change driver.evaluate(DETECTION_SCRIPT) to driver.evaluateValue(DETECTION_SCRIPT) in ImageDetector.detect()
- Add a unit test that verifies detection returns results when the page has images
- Consider adding a warning log when detection script returns empty/zero results on a page known to have images

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: MCP tool name snake_case/camelCase mismatch breaks dispatch

**Severity:** High
**Category:** Product

#### Reproduction

Call image_detect_images (snake_case, as advertised by /mcp/tools) via MCP endpoint. The dispatch derives method='detect_images' but executor expects 'detectImages'.

#### Expected Behavior

Both image_detect_images and image_detectImages should work.

#### Actual Behavior

image_detect_images returns error: 'Unsupported image method: detect_images'. image_detectImages (camelCase, undocumented) works.

#### Root Cause Analysis

toMcpToolName() converts camelCase method names to snake_case for MCP tool names. But dispatchToCustomExecutor() derives the method name by simple substring (domain_ prefix removal), producing snake_case that doesn't match the executor's camelCase method keys. The /mcp/tools endpoint advertises snake_case names, which don't work. A reverse lookup from snake_case to camelCase is needed.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:dispatchToCustomExecutor() line ~772 — add snake_case→camelCase reverse conversion before calling executor`

#### AI Suggested Improvement

- In dispatchToCustomExecutor(), convert snake_case method name back to camelCase before passing to executor.callFunctionOn()
- Or add a reverse lookup by checking each toolSpec key against the derived method name using case-insensitive matching
- Add test: verify toMcpToolName() round-trips correctly through the dispatch path for all registered tool executors

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: image.download fails with 'Network is unreachable' from OkHttp

**Severity:** High
**Category:** Reliability

#### Reproduction

curl -X POST http://localhost:8182/mcp/call-tool -d '{"tool":"image_download","arguments":{"sessionId":"<id>","url":"https://en.wikipedia.org/static/images/icons/enwiki-25.svg"}}'

#### Expected Behavior

Image downloads successfully via OkHttp.

#### Actual Behavior

All downloads fail with 'IO error: Network is unreachable'. Times out after 30-120 seconds. curl from the same shell succeeds.

#### Root Cause Analysis

OkHttp client in ImageDownloader cannot reach external URLs in this environment while system curl and Chrome can. Likely a JVM-level network configuration issue (proxy, DNS, or IPv4/IPv6). The 120-second timeout is excessive — OkHttp default connect timeout is 10s but observed 30-120s suggests retry or DNS resolution delays.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDownloader.kt — OkHttp client configuration and error handling. Also check ImageAutoConfiguration.kt for OkHttpClient bean setup.`

#### AI Suggested Improvement

- Configure OkHttpClient with explicit connect/read timeouts (e.g., 15s) instead of relying on defaults
- Add a connectivity check before attempting downloads and surface a clear error message
- Consider falling back to browser-mediated download (via CDP Network.loadNetworkResource or fetch) when direct HTTP fails
- Log the specific IOException details (DNS failure vs connect timeout vs route) for easier diagnosis

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Plugin 'inactive (restart required)' status is permanently misleading

**Severity:** Medium
**Category:** UX

#### Reproduction

1. Install plugin: ./b4w.ps1 plugin install <jar>
2. Stop server: ./b4w.ps1 stop
3. Restart and check: ./b4w.ps1 plugin list
Plugin still shows 'inactive (restart required)' even though server logs confirm it's loaded and running.

#### Expected Behavior

After server restart, plugin status should show 'active' or 'running'.

#### Actual Behavior

Plugin always shows 'inactive (restart required)' in plugin list output, even when server logs confirm: '✓ Registered tool executor for domain image' and 'browser4-images plugin started'.

#### Root Cause Analysis

The status string is hardcoded or derived from a condition that never clears. Investigation needed in PluginService or PluginController to understand why the status field doesn't update after a successful plugin load.

#### Code Pointer

`browser4-rest or browser4-browser-plugin module — PluginService or PluginController where plugin status is computed for the plugin list endpoint`

#### AI Suggested Improvement

- Fix the status detection to reflect actual plugin activation state after server startup
- Consider adding a 'status' field to the plugin list JSON output showing actual runtime state (loaded/running/error)
- Add an integration test that installs a plugin, restarts, and verifies the status shows as active

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: No CLI commands for image plugin tools — requires raw MCP HTTP calls

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Try to use image detection from browser4-cli directly. No image, detect-images, or download command exists in the CLI.

#### Expected Behavior

CLI commands like `browser4-cli image detect` or `browser4-cli image-download` that wrap the plugin's MCP tools.

#### Actual Behavior

Image tools are only accessible via raw HTTP POST to http://localhost:8182/mcp/call-tool with JSON body. Not documented in help output or SKILL.md.

#### Root Cause Analysis

Plugin tools are registered as AI agent tools (for LLM use) but not exposed as CLI commands. The CLI-to-backend bridge only maps specific tools to commands; plugin tools need explicit mapping or a generic 'call-tool' CLI command.

#### Code Pointer

`cli/browser4-cli/src/ — commands registration. A generic 'mcp-call' or plugin-tool CLI command could be added.`

#### AI Suggested Improvement

- Add a generic CLI command like `browser4-cli tool <domain>.<method> [args...]` that forwards to /mcp/call-tool
- Or add specific `browser4-cli image detect`, `browser4-cli image download` commands
- Document plugin tool access in SKILL.md
- List available plugin tools in `plugin info <name>` output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: image.downloadAll returns success with all zeros, masking the detection failure

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Call image.downloadAll on a page with 588 images. Returns success with totalAttempted=0, successful=0, failed=0.

#### Expected Behavior

Should return error or at minimum a non-zero attempted count when the page has images.

#### Actual Behavior

Returns BulkDownloadSummary with all fields zero (totalAttempted=0, successful=0, failed=0, totalBytesDownloaded=0, results=[]). No error or warning.

#### Root Cause Analysis

downloadAll depends on imageDetector.detect() which returns empty list (Issue 1). The empty list propagates silently — no warning when a page with images produces zero detections.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/tools/ImageToolExecutor.kt:callFunctionOn() lines 165-183 — downloadAll handler`

#### AI Suggested Improvement

- Add a warning when detect() returns 0 results but driver.pageSource contains <img> tags (quick sanity check)
- Consider returning an error or at least logging a warning when downloadAll finds zero images
- The BulkDownloadSummary could include a warning field

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: No plugin tool documentation in help or SKILL.md

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run ./b4w.ps1 help, search for 'image' or 'plugin tool'. Read skills/browser4-cli/SKILL.md, search for image tools.

#### Expected Behavior

Documentation explaining how to use plugin-provided tools (image detection, download, etc.).

#### Actual Behavior

No mention of image tools in help output or SKILL.md. Plugin section only covers install/list/remove. Tools are only discoverable via /mcp/tools HTTP endpoint.

#### Root Cause Analysis

Plugin tools are designed for AI agent consumption, not direct CLI use. But the documentation gap means users don't know these tools exist or how to call them.

#### Code Pointer

`skills/browser4-cli/SKILL.md and cli/browser4-cli/src/ help output`

#### AI Suggested Improvement

- Add a 'Plugin Tools' section to SKILL.md listing available plugin tools per installed plugin
- Add `plugin tools <name>` CLI command to list tools provided by a plugin
- Include tool schemas (arguments, return types) in plugin info output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Server restart takes 15+ seconds on every stop/start cycle

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 stop followed by any command. Observe 15s startup delay.

#### Expected Behavior

Faster server restart (< 5s) or instant plugin hot-reload.

#### Actual Behavior

Server startup takes approximately 15 seconds on every cold start. The JVM loading time is the bottleneck.

#### Root Cause Analysis

JVM startup + Spring Boot initialization + Chrome browser launch all happen sequentially. Plugin hot-reload could avoid JVM restart.

#### Code Pointer

`browser4-rest and browser4-browser startup configuration`

#### AI Suggested Improvement

- Consider implementing hot-reload for plugin JARs (watch plugins/ directory)
- Add a progress indicator during startup (currently only shows elapsed seconds)
- Cache the JVM process between plugin changes when possible

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: ArgumentNormalizer strips sessionId requiring manual re-add in dispatch

**Severity:** Low
**Category:** Product

#### Reproduction

Any MCP tool call through dispatchToToolExecutor — DefaultArgumentNormalizer.normalize() removes sessionId from args.

#### Expected Behavior

Argument normalization should preserve sessionId and other essential parameters.

#### Actual Behavior

sessionId is explicitly removed in DefaultArgumentNormalizer (line 19), then re-added manually in dispatchToToolExecutor (lines 713-718). This is error-prone.

#### Root Cause Analysis

Design choice to strip sessionId from normalized args since many tool executors don't need it. But custom executors DO need it for WebDriver access. The re-add logic is fragile.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/ArgumentNormalizers.kt:19 — DefaultArgumentNormalizer.normalize() sessionId removal`

#### AI Suggested Improvement

- Don't strip sessionId in the default normalizer; let individual normalizers decide
- Or pass sessionId separately through the dispatch chain rather than in args
- Add a comment documenting why sessionId is removed and re-added

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: Plugin download error message is unhelpful ('Network is unreachable' after 120s)

**Severity:** Low
**Category:** UX

#### Reproduction

Attempt image.download when OkHttp cannot reach the target URL.

#### Expected Behavior

Clear error indicating connectivity issue with a suggested fix (check proxy, firewall, DNS).

#### Actual Behavior

Generic 'IO error: Network is unreachable' after a 30-120 second wait. No suggestion for troubleshooting.

#### Root Cause Analysis

OkHttp's IOException is passed through without enrichment. No differentiation between DNS failure, connect timeout, or route issues.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDownloader.kt:250-259 — catch block for IOException`

#### AI Suggested Improvement

- Catch specific IOException subclasses (SocketTimeoutException, UnknownHostException, ConnectException) and provide targeted error messages
- Add suggestions: 'Check network connectivity', 'Verify proxy settings in JAVA_OPTS', 'Try using --server flag'
- Reduce default connect timeout from implicit 120s to explicit 15s

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

**Completion Status:** Partially Successful — Task completed using workarounds (eval for detection, curl for download). The native image.detectImages, image.download, image.downloadAll, and image.downloadBatch tools are all non-functional due to two critical bugs.

**Success Rate:** 40% — 2 of 5 plugin tools work (plugin install, plugin list), but the 3 core image tools (detectImages, download, downloadAll) are broken. Task goals achieved via workarounds.

**Issues Found:** 10

**Major Blockers:** 1) image.detectImages returns empty results due to driver.evaluate() vs evaluateValue() mismatch. 2) image.download fails with JVM network errors (OkHttp cannot reach external URLs). These two bugs cascade: downloadAll depends on detectImages and both detection and download are broken.

**Most Confusing Aspects:** 1) Plugin shows 'inactive (restart required)' even after restarting server — but server logs show it IS active. 2) MCP tool names use snake_case (image_detect_images) but the actual dispatch requires camelCase (image_detectImages) — the advertised names don't work. 3) No CLI commands for plugin tools — must discover and call /mcp/call-tool HTTP endpoint manually.

**Most Valuable Improvements:** 1) Fix driver.evaluate() → driver.evaluateValue() in ImageDetector (one-line fix). 2) Add snake_case→camelCase reverse mapping in MCP dispatch. 3) Add CLI commands for plugin tools. 4) Configure OkHttp with proper timeouts and network settings. 5) Fix plugin status display to show actual runtime state.

**Usability Rating:** 3/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: image.detectImages returns empty results — driver.evaluate() vs evaluateValue() mismatch

curl -X POST http://localhost:8182/mcp/call-tool -H 'Content-Type: application/json' -d '{"tool":"image_detectImages","arguments":{"sessionId":"<id>"}}'

#### Issue 2: MCP tool name snake_case/camelCase mismatch breaks dispatch

Call image_detect_images (snake_case, as advertised by /mcp/tools) via MCP endpoint. The dispatch derives method='detect_images' but executor expects 'detectImages'.

#### Issue 3: image.download fails with 'Network is unreachable' from OkHttp

curl -X POST http://localhost:8182/mcp/call-tool -d '{"tool":"image_download","arguments":{"sessionId":"<id>","url":"https://en.wikipedia.org/static/images/icons/enwiki-25.svg"}}'

#### Issue 4: Plugin 'inactive (restart required)' status is permanently misleading

1. Install plugin: ./b4w.ps1 plugin install <jar>
2. Stop server: ./b4w.ps1 stop
3. Restart and check: ./b4w.ps1 plugin list
Plugin still shows 'inactive (restart required)' even though server logs confirm it's loaded and running.

#### Issue 5: No CLI commands for image plugin tools — requires raw MCP HTTP calls

Try to use image detection from browser4-cli directly. No image, detect-images, or download command exists in the CLI.

#### Issue 6: image.downloadAll returns success with all zeros, masking the detection failure

Call image.downloadAll on a page with 588 images. Returns success with totalAttempted=0, successful=0, failed=0.

#### Issue 7: No plugin tool documentation in help or SKILL.md

Run ./b4w.ps1 help, search for 'image' or 'plugin tool'. Read skills/browser4-cli/SKILL.md, search for image tools.

#### Issue 8: Server restart takes 15+ seconds on every stop/start cycle

Run ./b4w.ps1 stop followed by any command. Observe 15s startup delay.

#### Issue 9: ArgumentNormalizer strips sessionId requiring manual re-add in dispatch

Any MCP tool call through dispatchToToolExecutor — DefaultArgumentNormalizer.normalize() removes sessionId from args.

#### Issue 10: Plugin download error message is unhelpful ('Network is unreachable' after 120s)

Attempt image.download when OkHttp cannot reach the target URL.

