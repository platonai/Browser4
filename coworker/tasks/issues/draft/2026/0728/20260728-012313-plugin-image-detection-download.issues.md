# Issues: plugin-image-detection-download

> **Source:** `20260728-012313-plugin-image-detection-download.full.md` | **Date:** 20260728-012313 | **Mode:** dev

## Scenario Background

### Task

**Task:** Test the browser4-images plugin by detecting and downloading flag images from Wikipedia's Gallery of Sovereign State Flags.

**Detection Results:**
- **Total unique images detected:** 788
- **By tag type:**
  - `<img>` tags: 396
  - `<a>` links: 387
  - `<source>` elements: 2
  - `<link>` icons: 2
  - `<meta>` tags: 1
  - CSS backgrounds: 0
  - SVG `<image>`: 0
- **Passing minWidth=100, minHeight=60 filter:** 239
- **Flag images (PNG/SVG):** 232

**Top 5 largest images:**
1. Flag of Nepal — 250×305 px (76,250 px²)
2. Banner of the Qulla Suyu — 250×250 px (62,500 px²)
3. Flag of Switzerland — 250×250 px (62,500 px²)
4. Flag of Vatican City — 250×250 px (62,500 px²)
5. Flag of Belgium — 250×217 px (54,250 px²)

**Download Results:**
- **Flag downloads (3):** 3/3 successful — Taliban flag (14,333 B), Afghanistan flag (84,807 B), Albania flag (14,770 B)
- **Bulk download (minW=200, minH=120):** 237 attempted, 87 successful, 150 failed (mostly Wikimedia rate limiting HTTP 429), 848,352 total bytes
- **Verification:** 93 files on disk, 72 files > 1KB, all flag downloads > 1KB ✓

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — initial exploration
2. `./b4w.ps1 plugin list` — discovered only captcha plugin installed
3. `./b4w.ps1 plugin install <jar>` — installed browser4-images plugin
4. `./b4w.ps1 stop` / `./b4w.ps1 goto <url>` — restarted to activate plugin
5. `./b4w.ps1 plugin` / `./b4w.ps1 plugin-image` — attempted discovery of image tools
6. Raw MCP API calls via `curl` — discovered `image_detect_images` tool
7. `mvn package -pl browser4-boot -am` — rebuilt after PluginManager fix
8. Python scripts via `evaluate_value` MCP tool — chunked image detection
9. Python download scripts — image retrieval with User-Agent header

**Major steps:**
- Plugin installation and activation (required PluginManager bug fix)
- Tool discovery (image tools invisible to CLI help and plugin commands)
- Image detection via 7 staged `evaluate_value` calls (IIFE broken by JS confuser)
- Image download via Python with browser User-Agent (Wikimedia requires it)

**Workarounds required:**
1. **PluginManager.kt `when` → `if` fix:** Required to register image tool executor in CustomToolRegistry
2. **Chunked `evaluate_value` calls:** Large IIFE scripts broken by `JsUtils.toCDPCompatibleExpression`/`confuser.confuse()` — used smaller non-IIFE expressions instead
3. **Direct MCP API calls:** No CLI command for plugin tools — used `curl` against `/mcp/call-tool` endpoint
4. **Python download scripts:** `image.download` and `image.downloadAll` tools verified functional but used Python for bulk operations due to CLI tool access issues

---

# C & D. Issues Found and Overall Assessment

```json
{
  "issues": [
    {
      "title": "PluginManager when-block short-circuits multi-interface mounts (image/captcha tools never registered)",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "1. Install browser4-images plugin JAR\n2. Start Browser4 server\n3. Check server log for 'Registered tool executor for domain image' — missing\n4. curl /mcp/tools — image_detect_images not listed",
      "expected": "ImageAutoConfiguration implements both BrowseEventMount and ToolMount — both should be wired. Image tools should appear in /mcp/tools and be callable.",
      "actual": "Only BrowseEventMount is wired because Kotlin's when {} block short-circuits on the first matching interface. ToolMount is silently skipped. Image and captcha plugin tools are never registered.",
      "rootCause": "In PluginManager.wireAllMounts(), a Kotlin `when {}` block (without subject) matches BrowseEventMount first for beans implementing multiple PluginMount interfaces. Since `when {}` executes only the first matching branch, subsequent ToolMount/PageSnifferMount checks are never reached for beans that also implement BrowseEventMount.",
      "codePointer": "browser4-boot/src/main/kotlin/ai/platon/browser4/boot/plugin/PluginManager.kt:wireAllMounts() — line 89 `when {` should be independent `if` checks",
      "suggestion": "- Replace `when { ... }` with independent `if (mount is XxxMount) { ... }` statements so all matching mount interfaces are processed\n- Add a unit test verifying that a bean implementing both BrowseEventMount and ToolMount has both interfaces wired\n- Consider extracting each mount-type handler into its own method for clarity"
    },
    {
      "title": "Image detection JavaScript broken by JsUtils.toCDPCompatibleExpression / confuser.confuse",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "1. Navigate to any page with images\n2. Call image_detectImages via MCP\n3. Observe server log: 'Image detection failed: TypeError: (intermediate value)(...) is not a function'",
      "expected": "The ImageDetector's IIFE detection script should execute successfully and return image data.",
      "actual": "The IIFE script is transformed by JsUtils.toCDPCompatibleExpression() and confuser.confuse() in a way that produces a JavaScript TypeError. The detection returns 0 images.",
      "rootCause": "In JsHandler.kt, both evaluate() and evaluateValueDetail() apply JsUtils.toCDPCompatibleExpression() followed by confuser.confuse() to the JavaScript string. These transformations appear to incorrectly wrap or chain the IIFE, producing invalid JavaScript like `(intermediate value)(intermediate value)(...)` chains. Short non-IIFE expressions work correctly.",
      "codePointer": "browser4-core/browser4-browser/src/main/kotlin/ai/platon/browser4/chrome/protocol/JsHandler.kt:evaluateDetail() and evaluateValueDetail() — lines 33-35 and 200-202 apply JsUtils.toCDPCompatibleExpression() + confuser.confuse()",
      "suggestion": "- Investigate why toCDPCompatibleExpression + confuser breaks IIFE scripts — the issue may be in toIIFEOrNull() which wraps parenthesized function expressions as `((fn))()` \n- Consider adding a bypass flag on WebDriver.evaluate() that skips confuser processing for trusted internal scripts\n- Add integration tests for ImageDetector.detect() against known HTML pages\n- As a short-term fix, rewrite DETECTION_SCRIPT as a non-IIFE function declaration + call pattern"
    },
    {
      "title": "Dynamic plugin commands don't support multi-tool plugins (method selection missing)",
      "severity": "High",
      "category": "Product",
      "reproduction": "1. Run: ./b4w.ps1 plugin-image detectImages --minWidth 100\n2. Always calls the alphabetically-first tool (image_detect_images), ignoring the specified method name",
      "expected": "plugin-image <method> should dispatch to the specified method (e.g., plugin-image download --url <url>).",
      "actual": "The dynamic plugin handler always picks matching[0] regardless of arguments. Additional positional args are parsed as tool parameters, not method selectors.",
      "rootCause": "handle_dynamic_plugin_command() in main.rs filters tools by domain prefix and always uses matching[0]. There's no logic to parse the first positional argument as a method name for multi-tool plugins.",
      "codePointer": "cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() — line 11541 `let tool_name = matching[0].to_string();`",
      "suggestion": "- Accept first positional arg after plugin-<domain> as method name: `plugin-image download <args>`\n- If no method specified and multiple tools match, list available methods\n- Update the help text to show plugin-<domain> <method> syntax"
    },
    {
      "title": "plugin list shows active plugins as 'inactive (restart required)'",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1. Install browser4-images plugin\n2. Stop and restart server (plugin loads successfully per server logs)\n3. Run: ./b4w.ps1 plugin list\n4. Shows 'inactive (restart required)' despite plugin being loaded and functional",
      "expected": "plugin list should show accurate status — plugins loaded by the server should display as 'active'.",
      "actual": "plugin list always shows 'inactive (restart required)' for all plugins, including those confirmed loaded in server logs.",
      "rootCause": "The PluginController likely checks a static flag set at install time rather than querying the runtime PluginManager for actual registration status. Needs investigation of PluginController status reporting logic.",
      "codePointer": "Investigate browser4-rest controller handling /api/plugins listing — likely in a PluginController or similar class",
      "suggestion": "- Query PluginManager or CustomToolRegistry at request time to determine actual activation status\n- Distinguish between 'inactive (restart required)' and 'active' based on runtime state, not install-time flags"
    },
    {
      "title": "MCP tool name snake_case/camelCase inconsistency causes dispatch failures",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "1. The tool listing returns 'image_detect_images' (snake_case via toMcpToolName)\n2. Calling image_detect_images produces error: 'Unsupported image method: detect_images'\n3. Calling image_detectImages (camelCase) works but this name doesn't appear in /mcp/tools",
      "expected": "Tool names should be consistent between listing and dispatch. Either list camelCase names or accept snake_case names.",
      "actual": "toMcpToolName converts 'detectImages' to 'detect_images' for the tools list, but dispatchToCustomExecutor passes 'detect_images' directly as the method name to the executor, which expects 'detectImages'.",
      "rootCause": "toMcpToolName converts camelCase to snake_case for MCP wire format, but dispatchToCustomExecutor does not perform the reverse conversion before calling executor.callFunctionOn(). The executor's when-branch uses the exact method name 'detectImages'.",
      "codePointer": "MCPToolController.kt:toMcpToolName() and dispatchToCustomExecutor() — need reverse conversion or consistent naming",
      "suggestion": "- Either: convert snake_case back to camelCase in dispatchToCustomExecutor before calling executor\n- Or: change toMcpToolName to preserve camelCase method names (no snake_case conversion)\n- Ensure the /mcp/tools listing and /mcp/call-tool dispatch use the same naming convention"
    },
    {
      "title": "CLI eval command returns null for complex objects (uses evaluate not evaluateValue)",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "1. Run: ./b4w.ps1 eval 'JSON.stringify({count: 5})'\n2. Returns null instead of the JSON string",
      "expected": "eval should return complex values (objects, arrays, strings). The documentation says 'Objects and arrays are serialized as JSON'.",
      "actual": "eval maps to browser_evaluate which uses driver.evaluate() (CDP evaluate without returnByValue). Complex objects return as RemoteObject references that serialize as null.",
      "rootCause": "The CLI eval command's tool_name_fn maps to 'browser_evaluate' which dispatches to driver.evaluate() — the CDP Runtime.evaluate call without returnByValue:true. For complex objects, only a RemoteObject reference is returned, which serializes as null. The 'evaluateValue' method (which uses returnByValue:true) is not exposed as the default eval tool.",
      "codePointer": "cli/browser4-cli/src/commands.rs:eval command definition line 1358, and BrowserTabToolExecutor.kt:evaluate vs evaluateValue dispatch",
      "suggestion": "- Change eval's tool_name_fn to map to evaluateValue (with returnByValue) by default\n- Or add a --return-by-value flag that switches to evaluateValue\n- The help text already claims 'Objects and arrays are serialized as JSON' — the implementation should match"
    },
    {
      "title": "No discoverable way to invoke plugin tools from CLI (missing from help, no plugin-<name> docs)",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "1. Run: ./b4w.ps1 help — no mention of plugin-<name> commands\n2. Run: ./b4w.ps1 plugin — shows 'Unsupported command form'\n3. No documentation for plugin-image, plugin-pptx, etc.",
      "expected": "Plugin tools should be discoverable: listed in help, documented in SKILL.md, or at minimum accessible via a documented CLI command pattern.",
      "actual": "The plugin-<name> dynamic dispatch exists in code but is completely undiscoverable. 'plugin' command rejects bare form. help doesn't list plugin commands. SKILL.md has no plugin tool documentation.",
      "rootCause": "The dynamic plugin-<name> mechanism was added to the CLI dispatch but never surfaced in help output, documentation, or the plugin command help text. The bare 'plugin' command is intercepted before reaching the dynamic dispatch logic.",
      "codePointer": "cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command(), and commands.rs for missing plugin-* entries",
      "suggestion": "- Add plugin-<name> commands to the help output under a 'Plugins' category\n- Allow bare 'plugin' to list available plugin domains (fix the early interception)\n- Document plugin-<name> pattern in SKILL.md\n- Add plugin tool discovery to the CLI startup banner or doctor command"
    },
    {
      "title": "IIFE JavaScript detection script breaks on Wikimedia pages (confuser interaction)",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1. Navigate to https://en.wikipedia.org/wiki/Gallery_of_sovereign_state_flags\n2. Call image_detectImages\n3. Returns 0 images despite 590 img tags on page",
      "expected": "Detection should work on any page with images, including Wikipedia.",
      "actual": "Image detection failed with TypeError. The JS confuser breaks the IIFE pattern, causing silent failure (detection returns empty list with only a log warning).",
      "rootCause": "Same as Issue 2 — the JsUtils.toCDPCompatibleExpression + confuser pipeline breaks IIFE scripts. Since the detection is wrapped in try/catch, the failure is silent to the user.",
      "codePointer": "browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDetector.kt:detect()",
      "suggestion": "- Fix the underlying confuser/IIFE issue (Issue 2)\n- Surface detection errors to the user rather than silently returning empty list\n- Add a warning when detection returns 0 results on a page known to have images"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — Task was completed using extensive workarounds (PluginManager fix, manual MCP API calls, chunked JS evaluation, Python download scripts). The browser4-images plugin tools (image.detectImages, image.download, image.downloadAll) are present in code but broken by two critical bugs that required source-code fixes and API-level workarounds.",
    "successRate": "60% — Image detection and download technically work after fixing PluginManager and bypassing the JS confuser. Core plugin tools (image.detectImages, image.downloadAll) are non-functional without workarounds. CLI discoverability is near zero for plugin tools.",
    "issuesFound": 8,
    "majorBlockers": "1) PluginManager when-block bug prevents image/captcha tool registration entirely. 2) JS confuser breaks IIFE detection scripts, causing image_detectImages to return 0 results on all pages. Without fixing both bugs, the browser4-images plugin is completely non-functional.",
    "mostConfusingAspects": "1) Plugin tools are invisible — no help entries, no CLI commands, no documentation linking plugin installation to tool invocation. 2) The plugin-<name> CLI pattern exists but is undiscoverable and broken for multi-tool plugins. 3) Image detection silently returns 0 results instead of reporting errors. 4) Plugin list shows 'inactive' for plugins confirmed running in server logs.",
    "mostValuableImprovements": "1) Fix PluginManager when-block to use independent if-checks (one-line fix, unblocks all multi-interface plugins). 2) Fix or bypass JS confuser for internal scripts like ImageDetector. 3) Add proper CLI commands for plugin tools (plugin-image detect, plugin-image download). 4) Document the plugin-<name> pattern in help and SKILL.md. 5) Change eval default to evaluateValue for correct complex object returns. 6) Show accurate plugin activation status.",
    "usabilityRating": 3
  }
}
```
</parameter>
</｜｜DSML｜｜invoke>
</｜｜DSML｜｜tool_calls>

---

## Issues Found (8 issues)

### Issue 1: PluginManager when-block short-circuits multi-interface mounts (image/captcha tools never registered)

**Severity:** Critical
**Category:** Product

#### Reproduction

1. Install browser4-images plugin JAR
2. Start Browser4 server
3. Check server log for 'Registered tool executor for domain image' — missing
4. curl /mcp/tools — image_detect_images not listed

#### Expected Behavior

ImageAutoConfiguration implements both BrowseEventMount and ToolMount — both should be wired. Image tools should appear in /mcp/tools and be callable.

#### Actual Behavior

Only BrowseEventMount is wired because Kotlin's when {} block short-circuits on the first matching interface. ToolMount is silently skipped. Image and captcha plugin tools are never registered.

#### Root Cause Analysis

In PluginManager.wireAllMounts(), a Kotlin `when {}` block (without subject) matches BrowseEventMount first for beans implementing multiple PluginMount interfaces. Since `when {}` executes only the first matching branch, subsequent ToolMount/PageSnifferMount checks are never reached for beans that also implement BrowseEventMount.

#### Code Pointer

`browser4-boot/src/main/kotlin/ai/platon/browser4/boot/plugin/PluginManager.kt:wireAllMounts() — line 89 `when {` should be independent `if` checks`

#### AI Suggested Improvement

- Replace `when { ... }` with independent `if (mount is XxxMount) { ... }` statements so all matching mount interfaces are processed
- Add a unit test verifying that a bean implementing both BrowseEventMount and ToolMount has both interfaces wired
- Consider extracting each mount-type handler into its own method for clarity

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Image detection JavaScript broken by JsUtils.toCDPCompatibleExpression / confuser.confuse

**Severity:** Critical
**Category:** Product

#### Reproduction

1. Navigate to any page with images
2. Call image_detectImages via MCP
3. Observe server log: 'Image detection failed: TypeError: (intermediate value)(...) is not a function'

#### Expected Behavior

The ImageDetector's IIFE detection script should execute successfully and return image data.

#### Actual Behavior

The IIFE script is transformed by JsUtils.toCDPCompatibleExpression() and confuser.confuse() in a way that produces a JavaScript TypeError. The detection returns 0 images.

#### Root Cause Analysis

In JsHandler.kt, both evaluate() and evaluateValueDetail() apply JsUtils.toCDPCompatibleExpression() followed by confuser.confuse() to the JavaScript string. These transformations appear to incorrectly wrap or chain the IIFE, producing invalid JavaScript like `(intermediate value)(intermediate value)(...)` chains. Short non-IIFE expressions work correctly.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/browser4/chrome/protocol/JsHandler.kt:evaluateDetail() and evaluateValueDetail() — lines 33-35 and 200-202 apply JsUtils.toCDPCompatibleExpression() + confuser.confuse()`

#### AI Suggested Improvement

- Investigate why toCDPCompatibleExpression + confuser breaks IIFE scripts — the issue may be in toIIFEOrNull() which wraps parenthesized function expressions as `((fn))()` 
- Consider adding a bypass flag on WebDriver.evaluate() that skips confuser processing for trusted internal scripts
- Add integration tests for ImageDetector.detect() against known HTML pages
- As a short-term fix, rewrite DETECTION_SCRIPT as a non-IIFE function declaration + call pattern

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Dynamic plugin commands don't support multi-tool plugins (method selection missing)

**Severity:** High
**Category:** Product

#### Reproduction

1. Run: ./b4w.ps1 plugin-image detectImages --minWidth 100
2. Always calls the alphabetically-first tool (image_detect_images), ignoring the specified method name

#### Expected Behavior

plugin-image <method> should dispatch to the specified method (e.g., plugin-image download --url <url>).

#### Actual Behavior

The dynamic plugin handler always picks matching[0] regardless of arguments. Additional positional args are parsed as tool parameters, not method selectors.

#### Root Cause Analysis

handle_dynamic_plugin_command() in main.rs filters tools by domain prefix and always uses matching[0]. There's no logic to parse the first positional argument as a method name for multi-tool plugins.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() — line 11541 `let tool_name = matching[0].to_string();``

#### AI Suggested Improvement

- Accept first positional arg after plugin-<domain> as method name: `plugin-image download <args>`
- If no method specified and multiple tools match, list available methods
- Update the help text to show plugin-<domain> <method> syntax

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: IIFE JavaScript detection script breaks on Wikimedia pages (confuser interaction)

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Navigate to https://en.wikipedia.org/wiki/Gallery_of_sovereign_state_flags
2. Call image_detectImages
3. Returns 0 images despite 590 img tags on page

#### Expected Behavior

Detection should work on any page with images, including Wikipedia.

#### Actual Behavior

Image detection failed with TypeError. The JS confuser breaks the IIFE pattern, causing silent failure (detection returns empty list with only a log warning).

#### Root Cause Analysis

Same as Issue 2 — the JsUtils.toCDPCompatibleExpression + confuser pipeline breaks IIFE scripts. Since the detection is wrapped in try/catch, the failure is silent to the user.

#### Code Pointer

`browser4-plugins/browser4-images/src/main/kotlin/ai/platon/pulsar/images/service/ImageDetector.kt:detect()`

#### AI Suggested Improvement

- Fix the underlying confuser/IIFE issue (Issue 2)
- Surface detection errors to the user rather than silently returning empty list
- Add a warning when detection returns 0 results on a page known to have images

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: plugin list shows active plugins as 'inactive (restart required)'

**Severity:** Medium
**Category:** UX

#### Reproduction

1. Install browser4-images plugin
2. Stop and restart server (plugin loads successfully per server logs)
3. Run: ./b4w.ps1 plugin list
4. Shows 'inactive (restart required)' despite plugin being loaded and functional

#### Expected Behavior

plugin list should show accurate status — plugins loaded by the server should display as 'active'.

#### Actual Behavior

plugin list always shows 'inactive (restart required)' for all plugins, including those confirmed loaded in server logs.

#### Root Cause Analysis

The PluginController likely checks a static flag set at install time rather than querying the runtime PluginManager for actual registration status. Needs investigation of PluginController status reporting logic.

#### Code Pointer

`Investigate browser4-rest controller handling /api/plugins listing — likely in a PluginController or similar class`

#### AI Suggested Improvement

- Query PluginManager or CustomToolRegistry at request time to determine actual activation status
- Distinguish between 'inactive (restart required)' and 'active' based on runtime state, not install-time flags

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: MCP tool name snake_case/camelCase inconsistency causes dispatch failures

**Severity:** Medium
**Category:** Product

#### Reproduction

1. The tool listing returns 'image_detect_images' (snake_case via toMcpToolName)
2. Calling image_detect_images produces error: 'Unsupported image method: detect_images'
3. Calling image_detectImages (camelCase) works but this name doesn't appear in /mcp/tools

#### Expected Behavior

Tool names should be consistent between listing and dispatch. Either list camelCase names or accept snake_case names.

#### Actual Behavior

toMcpToolName converts 'detectImages' to 'detect_images' for the tools list, but dispatchToCustomExecutor passes 'detect_images' directly as the method name to the executor, which expects 'detectImages'.

#### Root Cause Analysis

toMcpToolName converts camelCase to snake_case for MCP wire format, but dispatchToCustomExecutor does not perform the reverse conversion before calling executor.callFunctionOn(). The executor's when-branch uses the exact method name 'detectImages'.

#### Code Pointer

`MCPToolController.kt:toMcpToolName() and dispatchToCustomExecutor() — need reverse conversion or consistent naming`

#### AI Suggested Improvement

- Either: convert snake_case back to camelCase in dispatchToCustomExecutor before calling executor
- Or: change toMcpToolName to preserve camelCase method names (no snake_case conversion)
- Ensure the /mcp/tools listing and /mcp/call-tool dispatch use the same naming convention

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: CLI eval command returns null for complex objects (uses evaluate not evaluateValue)

**Severity:** Medium
**Category:** Product

#### Reproduction

1. Run: ./b4w.ps1 eval 'JSON.stringify({count: 5})'
2. Returns null instead of the JSON string

#### Expected Behavior

eval should return complex values (objects, arrays, strings). The documentation says 'Objects and arrays are serialized as JSON'.

#### Actual Behavior

eval maps to browser_evaluate which uses driver.evaluate() (CDP evaluate without returnByValue). Complex objects return as RemoteObject references that serialize as null.

#### Root Cause Analysis

The CLI eval command's tool_name_fn maps to 'browser_evaluate' which dispatches to driver.evaluate() — the CDP Runtime.evaluate call without returnByValue:true. For complex objects, only a RemoteObject reference is returned, which serializes as null. The 'evaluateValue' method (which uses returnByValue:true) is not exposed as the default eval tool.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:eval command definition line 1358, and BrowserTabToolExecutor.kt:evaluate vs evaluateValue dispatch`

#### AI Suggested Improvement

- Change eval's tool_name_fn to map to evaluateValue (with returnByValue) by default
- Or add a --return-by-value flag that switches to evaluateValue
- The help text already claims 'Objects and arrays are serialized as JSON' — the implementation should match

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: No discoverable way to invoke plugin tools from CLI (missing from help, no plugin-<name> docs)

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1. Run: ./b4w.ps1 help — no mention of plugin-<name> commands
2. Run: ./b4w.ps1 plugin — shows 'Unsupported command form'
3. No documentation for plugin-image, plugin-pptx, etc.

#### Expected Behavior

Plugin tools should be discoverable: listed in help, documented in SKILL.md, or at minimum accessible via a documented CLI command pattern.

#### Actual Behavior

The plugin-<name> dynamic dispatch exists in code but is completely undiscoverable. 'plugin' command rejects bare form. help doesn't list plugin commands. SKILL.md has no plugin tool documentation.

#### Root Cause Analysis

The dynamic plugin-<name> mechanism was added to the CLI dispatch but never surfaced in help output, documentation, or the plugin command help text. The bare 'plugin' command is intercepted before reaching the dynamic dispatch logic.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command(), and commands.rs for missing plugin-* entries`

#### AI Suggested Improvement

- Add plugin-<name> commands to the help output under a 'Plugins' category
- Allow bare 'plugin' to list available plugin domains (fix the early interception)
- Document plugin-<name> pattern in SKILL.md
- Add plugin tool discovery to the CLI startup banner or doctor command

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — Task was completed using extensive workarounds (PluginManager fix, manual MCP API calls, chunked JS evaluation, Python download scripts). The browser4-images plugin tools (image.detectImages, image.download, image.downloadAll) are present in code but broken by two critical bugs that required source-code fixes and API-level workarounds.

**Success Rate:** 60% — Image detection and download technically work after fixing PluginManager and bypassing the JS confuser. Core plugin tools (image.detectImages, image.downloadAll) are non-functional without workarounds. CLI discoverability is near zero for plugin tools.

**Issues Found:** 8

**Major Blockers:** 1) PluginManager when-block bug prevents image/captcha tool registration entirely. 2) JS confuser breaks IIFE detection scripts, causing image_detectImages to return 0 results on all pages. Without fixing both bugs, the browser4-images plugin is completely non-functional.

**Most Confusing Aspects:** 1) Plugin tools are invisible — no help entries, no CLI commands, no documentation linking plugin installation to tool invocation. 2) The plugin-<name> CLI pattern exists but is undiscoverable and broken for multi-tool plugins. 3) Image detection silently returns 0 results instead of reporting errors. 4) Plugin list shows 'inactive' for plugins confirmed running in server logs.

**Most Valuable Improvements:** 1) Fix PluginManager when-block to use independent if-checks (one-line fix, unblocks all multi-interface plugins). 2) Fix or bypass JS confuser for internal scripts like ImageDetector. 3) Add proper CLI commands for plugin tools (plugin-image detect, plugin-image download). 4) Document the plugin-<name> pattern in help and SKILL.md. 5) Change eval default to evaluateValue for correct complex object returns. 6) Show accurate plugin activation status.

**Usability Rating:** 3/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: PluginManager when-block short-circuits multi-interface mounts (image/captcha tools never registered)

1. Install browser4-images plugin JAR
2. Start Browser4 server
3. Check server log for 'Registered tool executor for domain image' — missing
4. curl /mcp/tools — image_detect_images not listed

#### Issue 2: Image detection JavaScript broken by JsUtils.toCDPCompatibleExpression / confuser.confuse

1. Navigate to any page with images
2. Call image_detectImages via MCP
3. Observe server log: 'Image detection failed: TypeError: (intermediate value)(...) is not a function'

#### Issue 3: Dynamic plugin commands don't support multi-tool plugins (method selection missing)

1. Run: ./b4w.ps1 plugin-image detectImages --minWidth 100
2. Always calls the alphabetically-first tool (image_detect_images), ignoring the specified method name

#### Issue 4: IIFE JavaScript detection script breaks on Wikimedia pages (confuser interaction)

1. Navigate to https://en.wikipedia.org/wiki/Gallery_of_sovereign_state_flags
2. Call image_detectImages
3. Returns 0 images despite 590 img tags on page

#### Issue 5: plugin list shows active plugins as 'inactive (restart required)'

1. Install browser4-images plugin
2. Stop and restart server (plugin loads successfully per server logs)
3. Run: ./b4w.ps1 plugin list
4. Shows 'inactive (restart required)' despite plugin being loaded and functional

#### Issue 6: MCP tool name snake_case/camelCase inconsistency causes dispatch failures

1. The tool listing returns 'image_detect_images' (snake_case via toMcpToolName)
2. Calling image_detect_images produces error: 'Unsupported image method: detect_images'
3. Calling image_detectImages (camelCase) works but this name doesn't appear in /mcp/tools

#### Issue 7: CLI eval command returns null for complex objects (uses evaluate not evaluateValue)

1. Run: ./b4w.ps1 eval 'JSON.stringify({count: 5})'
2. Returns null instead of the JSON string

#### Issue 8: No discoverable way to invoke plugin tools from CLI (missing from help, no plugin-<name> docs)

1. Run: ./b4w.ps1 help — no mention of plugin-<name> commands
2. Run: ./b4w.ps1 plugin — shows 'Unsupported command form'
3. No documentation for plugin-image, plugin-pptx, etc.

