# Issues: plugin-pptx-generation

> **Source:** `20260728-174734-plugin-pptx-generation.full.md` | **Date:** 20260728-174734 | **Mode:** dev

## Scenario Background

### Task

**Partially Successful.** The `pptx.generate` tool was successfully tested on a minimal page (httpbin.org/html) where it produced a valid 2-slide, 28KB PPTX file in 360ms. However, it **failed on all three tested Wikipedia pages** (Solar System: 213 blocks extracted, Moon: 323 blocks, Planet: 182 blocks) — content extraction succeeded reliably, but image downloading consistently exceeded the server's 5-minute async request timeout, causing a `MonoCoroutine was cancelled` error.

Key verification data for the successful case:
- **filePath:** `.test-sessions/presentation_20260729_012333.pptx` (server CWD)
- **slideCount:** 2 (> 5 expected — but page had minimal structure, 2 is correct for 2 blocks)
- **blockCount:** 2 (> 20 expected — but page is minimal, 2 is correct)
- **imageCount:** 0 (> 0 expected — page has no images, 0 is correct)
- **durationMs:** 360ms
- **File size:** 28KB (> 10KB ✓), valid PPTX ZIP with 39 entries

The tool correctly adapts to page complexity — httpbin produced 2 slides from 2 blocks, while extraction on Wikipedia correctly identified 182-323 blocks. The failure mode is isolated to image downloading for pages with many external images.

### Execution Context

**Key Commands:**

**Key workarounds required:**
1. Manually copied 10 missing JAR dependencies (poi, poi-ooxml, poi-ooxml-lite, xmlbeans, SparseBitSet, commons-compress, commons-io, commons-collections4, curvesapi, commons-math3) to the server's plugins directory
2. Set `BROWSER4_CLI_HTTP_TIMEOUT_SECS=600` to override the 30s default timeout — plugin tools have no dedicated timeout category
3. PPTX files were written to the **server's CWD** `.test-sessions/`, not the repo root — discovered via `find`

**Pages tested:** httpbin.org/html (✓), Wikipedia Solar System (✗ timeout), Wikipedia Moon (✗ timeout), Wikipedia Planet (✗ timeout)

---

```json
{
  "issues": [
    {
      "title": "PPTX plugin JAR missing all third-party dependencies (POI not bundled)",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "mvn package -pl browser4-plugins/browser4-pptx && jar tf browser4-plugins/browser4-pptx/target/browser4-pptx-4.12.1-SNAPSHOT.jar | grep poi → no output. The JAR contains only com/platon/pulsar/pptx/* classes. Install the plugin and restart — bean creation fails with 'Post-processing of merged bean definition failed' for pptxGenerator because org.apache.poi.xslf.usermodel.XMLSlideShow is not on the classpath.",
      "expected": "The plugin JAR should be self-contained with all non-provided dependencies bundled (as the README claims: 'Bundled in plugin JAR: Apache POI'). Installing the plugin should make it immediately usable.",
      "actual": "The JAR is a thin JAR containing only the plugin's own classes. Missing: poi, poi-ooxml, poi-ooxml-lite, xmlbeans, SparseBitSet, commons-compress, commons-io, commons-collections4, curvesapi, commons-math3. The plugin fails to load until all 10 JARs are manually copied to the plugins directory.",
      "rootCause": "The root pom.xml defines maven-shade-plugin version 3.6.1 but no module (including browser4-pdk which is the parent of browser4-pptx) actually configures a shade execution. The maven-jar-plugin produces a standard thin JAR. The POM correctly marks POI dependencies without <scope>provided</scope>, but the build pipeline never runs a shade/dependency-copy step for plugin modules.",
      "codePointer": "pom.xml:598 (shade plugin version defined but never executed); browser4-pdk/pom.xml (missing shade plugin execution configuration).",
      "suggestion": "- Add maven-shade-plugin execution to browser4-pdk/pom.xml that bundles compile-scope dependencies into each plugin JAR\n- Alternatively, configure maven-dependency-plugin to copy runtime dependencies alongside each plugin JAR during the bundle assembly step\n- Add a build-time validation test that verifies each plugin JAR contains its expected third-party classes (e.g. check for org.apache.poi.xslf.usermodel.XMLSlideShow in the pptx JAR)\n- Update the README to accurately describe the current packaging (thin JAR requiring manual dependency management)"
    },
    {
      "title": "Image download timeout makes PPTX generation unusable on content-rich pages",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "1. Navigate to https://en.wikipedia.org/wiki/Planet (68 images). 2. Run `plugin pptx --outputPath .test-sessions/`. 3. Blocks are extracted (182) but the request times out after exactly 5 minutes with `AsyncRequestTimeoutException` and `MonoCoroutine was cancelled`. No image download log entries appear before the timeout. This happened on all three Wikipedia pages tested.",
      "expected": "PPTX generation should complete within a reasonable time (under 2-3 minutes) for a typical Wikipedia article. Slow or unreachable images should be skipped quickly (within a few seconds each).",
      "actual": "Generation hangs during image download and exceeds the server's 5-minute async request timeout. No PPTX file is produced. The server becomes degraded, returning 503 for subsequent requests until restarted.",
      "rootCause": "Two compounding factors: (1) The per-image OkHttp read timeout is 60 seconds (hardcoded in PptxAutoConfiguration.pptxDownloadClient()), so each slow image can consume up to 60s. With 3 concurrent downloads and 68 images, worst case is 68/3 × 60s ≈ 23 minutes. (2) The Spring Boot async request timeout defaults to 300,000ms (5 minutes) and is not configurable through the CLI or plugin properties. The semaphore-gated concurrent downloads can't complete within that window for image-heavy pages. Additionally, some Wikipedia images may return error responses that aren't handled quickly.",
      "codePointer": "browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/config/PptxAutoConfiguration.kt:88-95 (OkHttpClient builder with 60s read timeout — should use configurable timeout or much shorter default, e.g. 10s). Also: PptxImageDownloader.kt:downloadImages() uses coroutineScope which awaits all downloads even if many have already failed.",
      "suggestion": "- Reduce per-image OkHttp read timeout from 60s to 10s (with config override via pptx.download.timeout.seconds)\n- Set per-request timeouts on individual OkHttp calls rather than relying solely on the global client timeout\n- Add an overall generation deadline that gracefully cancels remaining downloads and writes a partial PPTX rather than failing entirely\n- Implement fast-failure: after N consecutive download failures, skip remaining images\n- Increase Spring Boot async request timeout to 10 minutes for PPTX generation specifically, or make it configurable via pptx.generate.timeout.seconds\n- Add progress logging for image downloads (currently no log entries between 'extracted N blocks' and 'complete' or timeout)\n- Consider lazy image download: embed a placeholder and download/embed images only if they respond within 5s"
    },
    {
      "title": "Plugin command not discoverable from help output",
      "severity": "High",
      "category": "Discoverability",
      "reproduction": "Run `./b4w.ps1 help` and search for 'plugin', 'pptx', or any plugin tool invocation. Run `./b4w.ps1 help --help plugin`.",
      "expected": "The help output should document the `plugin <domain>` command for invoking plugin tools, or there should be a dedicated `pptx generate` command. A section like 'Plugins' in the help should list available plugin domains and their tools.",
      "actual": "The main `help` output lists `plugin list`, `plugin info`, `plugin install`, and `plugin remove` — but NOT the `plugin <domain>` invocation form. The `plugin pptx` form is only discoverable by reading the CLI source code (main.rs:handle_dynamic_plugin_command). There is no `pptx` or `pptx generate` entry anywhere in help output.",
      "rootCause": "The dynamic plugin command dispatch in main.rs handles `plugin <domain>` generically but this is not advertised as a user-facing command. The help text is generated from hardcoded CommandDef entries, and dynamic plugin commands have no CommandDef.",
      "codePointer": "cli/browser4-cli/src/main.rs:11944 (handle_dynamic_plugin_command — works but undiscoverable); main.rs command registration (missing plugin tool entries in help generation).",
      "suggestion": "- Add a 'Plugin Tools' section to the main help output listing available plugin domains and their tools (e.g. 'plugin pptx → pptx.generate()')\n- Generate help text dynamically from the server's /mcp/tools endpoint so newly installed plugins appear automatically\n- Add aliases so users can type `pptx generate` instead of `plugin pptx`\n- Add `./b4w.ps1 plugin --help` showing available plugin tool domains"
    },
    {
      "title": "Relative outputPath resolves to server CWD, not CLI CWD",
      "severity": "High",
      "category": "UX",
      "reproduction": "Run `./b4w.ps1 plugin pptx --outputPath .test-sessions/` from repo root. The file is created at `<runtime-bundle>/.test-sessions/presentation_*.pptx`, not `<repo-root>/.test-sessions/presentation_*.pptx`.",
      "expected": "The output path should resolve relative to the user's current working directory (where the CLI was invoked), or the CLI should translate the path before sending it to the server.",
      "actual": "The path `.test-sessions/` is passed verbatim to the server, which resolves it relative to its own working directory (the runtime bundle directory). Users must search for their files using `find`.",
      "rootCause": "The CLI's handle_dynamic_plugin_command passes through arguments as-is without resolving relative paths. The server's PptxToolExecutor resolves paths relative to the JVM's working directory, which is the runtime bundle directory.",
      "codePointer": "cli/browser4-cli/src/main.rs:12016 (parse_raw_args passes outputPath verbatim); browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/tools/PptxToolExecutor.kt:91 (Path.of(outputPath) resolves relative to server CWD).",
      "suggestion": "- The CLI should resolve relative paths to absolute paths before sending to the server\n- Or the tool response should clearly indicate the absolute path (it already includes filePath — but the JSON output shows a relative path, causing confusion)\n- The PptxToolExecutor should log the absolute path prominently"
    },
    {
      "title": "Plugin tools have no dedicated timeout category (default 30s)",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Run `./b4w.ps1 plugin pptx --outputPath .test-sessions/` on any non-trivial page. The request times out in 30 seconds with 'HTTP request timed out [tool=pptx_generate, timeout=30s]'.",
      "expected": "Long-running plugin operations should have a reasonable timeout (e.g., 5 minutes for PPTX generation). There should be a dedicated environment variable like BROWSER4_CLI_PPTX_TIMEOUT_SECS or the tool should default to a higher timeout category.",
      "actual": "Plugin tools fall through to the default 30-second timeout in timeout_for_tool(). Users must discover BROWSER4_CLI_HTTP_TIMEOUT_SECS through source code reading or trial and error.",
      "rootCause": "In timeout_for_tool() (http.rs:129), the tool name 'pptx_generate' doesn't match any of the known categories (navigation, text_input, agent, snapshot, wait, batch, crawl). It falls through to the default 30-second timeout.",
      "codePointer": "cli/browser4-cli/src/http.rs:129-145 (timeout_for_tool — missing plugin tool category).",
      "suggestion": "- Add a plugin tool timeout category with a generous default (e.g., 300s) controlled by BROWSER4_CLI_PLUGIN_TIMEOUT_SECS\n- Or allow individual tools to declare their expected timeout via the server's /mcp/tools endpoint and have the CLI honor it\n- At minimum, document BROWSER4_CLI_HTTP_TIMEOUT_SECS prominently in the output of timed-out plugin commands"
    },
    {
      "title": "Plugin list always shows 'inactive (restart required)' after restart",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Install a plugin, restart the server, run `plugin list`. All plugins still show 'inactive (restart required)' even though the server log confirms they loaded successfully ('✓ Registered custom tool executor for domain: pptx').",
      "expected": "After server restart, plugin list should show active plugins with their current status. Or at minimum, a different message like 'active' or 'running'.",
      "actual": "All 5 plugins show 'inactive (restart required)' even after multiple restarts, even though 4 of 5 are actually working (pptx, images, markdown, media all registered their tool executors).",
      "rootCause": "The plugin list display logic reads plugin status from the file system or installation metadata rather than from the runtime PluginManager state. The 'inactive' status reflects the installation state ('needs restart to activate') rather than the runtime state.",
      "codePointer": "cli/browser4-cli/src/main.rs (handle_plugin_list) or the corresponding backend endpoint that returns plugin status.",
      "suggestion": "- Query the runtime PluginManager for actual plugin activation status\n- Show 'active' for plugins whose tool executors are registered in CustomToolRegistry\n- Add a separate column showing install state vs runtime state"
    },
    {
      "title": "Server degraded (503) after PPTX timeout — requires manual restart",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "1. Trigger a PPTX generation that times out (e.g., on any Wikipedia page). 2. Wait for the 5-minute async timeout. 3. Try any subsequent MCP tool call — returns 503 Service Unavailable.",
      "expected": "After a tool timeout, the server should recover gracefully and handle new requests. The timed-out operation's resources should be released.",
      "actual": "The server returns 503 for all subsequent requests. The session's thread pool appears exhausted. Only a full server restart (./b4w.ps1 stop && ./b4w.ps1 goto ...) recovers normal operation.",
      "rootCause": "The PPTX generation runs on a coroutine dispatched to the IO thread pool. When the Spring async request times out after 5 minutes, the coroutine is cancelled but the OkHttp connections and thread pool resources may not be properly released. The server's session worker threads remain tied up, preventing new requests from being processed.",
      "codePointer": "browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxImageDownloader.kt:131-155 (downloadImages uses coroutineScope which doesn't have a cancellation-aware timeout; OkHttp connections may leak on cancellation).",
      "suggestion": "- Wrap image downloads in a withTimeout() block to ensure cancellation propagates properly\n- Close OkHttp response bodies in finally blocks to prevent connection leaks on cancellation\n- Add server-side health checks that detect and recover from stuck worker threads"
    },
    {
      "title": "No --help output for plugin tool commands",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "Run `./b4w.ps1 plugin pptx --help` or `./b4w.ps1 pptx --help`.",
      "expected": "Should show the tool's arguments (outputPath), description, and an example invocation.",
      "actual": "No help available — `plugin pptx --help` is not recognized. Users must read the plugin README or source code to discover the `--outputPath` argument.",
      "rootCause": "Dynamic plugin commands have no CommandDef with associated help text. The argument parsing is generic (parse_raw_args). The tool spec from the server (ToolSpec with arguments list) is never surfaced to the CLI user.",
      "codePointer": "cli/browser4-cli/src/main.rs:12016 (parse_raw_args with no help integration); main.rs command registration (no dynamic help generation for plugin tools).",
      "suggestion": "- Query the server's /mcp/tools endpoint for tool specs and generate help text from the ToolSpec metadata (arguments, description, help fields)\n- Support `plugin pptx --help` to show: pptx.generate(outputPath?: String) — description, argument list, return type"
    },
    {
      "title": "Content extraction silently skips boilerplate — 'Planet' page title would be filtered",
      "severity": "Low",
      "category": "Product",
      "reproduction": "The word 'Planet' appears in the extraction boilerplate filter (CONTENT_EXTRACTION_SCRIPT:183-186). Short heading text matching boilerplate patterns like 'home', 'about', 'contact', 'privacy', 'terms', 'copyright' is silently discarded.",
      "expected": "Reasonable content filtering, but the boilerplate regex should be reviewed for false positives. The filter should be documented so users understand why some content is excluded.",
      "actual": "The filter regex `/^(menu|search|login|sign up|sign in|subscribe|share|follow|next|previous|prev|back|top|scroll|close|accept|cancel|submit|reset|loading|home|about|contact|privacy|terms|copyright|all rights reserved)$/i` is applied to heading text. 'Planet' is NOT affected (not in the list), but legitimate content headings that match (e.g., 'Contact' on a company page) would be silently dropped.",
      "rootCause": "The shouldSkipText() function filters short text (<2 chars) and boilerplate terms. This is a reasonable heuristic but is not configurable and not documented.",
      "codePointer": "browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PageContentExtractor.kt:182-187 (shouldSkipText with hardcoded boilerplate regex).",
      "suggestion": "- Make the boilerplate filter configurable via pptx.extract.skip-patterns property\n- Log skipped content at DEBUG level so users can audit what was excluded\n- Document the boilerplate filter in the README"
    },
    {
      "title": "CLI silently succeeds (exit 0) but produces no output with --json on certain failures",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "Run `./b4w.ps1 --json plugin pptx --outputPath .test-sessions/` after a previous timeout has degraded the server. Exit code is 0 but no JSON output is produced.",
      "expected": "Either JSON error output or a non-zero exit code.",
      "actual": "Exit code 0 with no stdout output. The user sees nothing and has no indication that the command failed.",
      "rootCause": "Unclear — possibly related to the 503 response being handled by the reqwest error path without proper JSON error formatting when --json mode is active, or the error message is written to stderr which the shell redirection didn't capture.",
      "codePointer": "cli/browser4-cli/src/http.rs:327-407 (call_tool_with_timeout error handling and JSON output paths).",
      "suggestion": "- Ensure all error paths produce JSON output when --json is active\n- Set non-zero exit code on tool failures\n- Never silently exit 0 when the tool call failed"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — The plugin works correctly for simple, image-free pages (httpbin.org: 2 slides, 28KB valid PPTX, 360ms). Content extraction is reliable (correctly identifies 182-323 blocks on Wikipedia pages). However, PPTX generation fails on all tested Wikipedia pages due to image download timeouts exceeding the 5-minute server async request limit. The plugin required manual installation of 10 missing JAR dependencies before it could load.",
    "successRate": "25% — 1 of 4 pages tested produced a complete PPTX file (httpbin.org). 3 Wikipedia pages (Solar System, Moon, Planet) all failed during image download. Content extraction succeeded on all 4 pages (100% extraction success rate).",
    "issuesFound": 10,
    "majorBlockers": "1. Plugin JAR packaging is broken — 10 missing runtime dependencies must be manually installed. 2. Image downloading exceeds the 5-minute server timeout on any page with more than ~20 images, making the plugin unusable for most real-world web pages. 3. After a timeout, the server enters a degraded state requiring manual restart.",
    "mostConfusingAspects": "1. Plugin invocation requires `plugin pptx` syntax — not documented in help, no --help available, not intuitive. 2. PPTX files silently appear in the server's working directory, not the user's current directory. 3. After installing the plugin, it appears 'inactive (restart required)' even after restart, yet may actually work. 4. No indication that a 30-second default timeout exists for plugin tools — failure message gives no hint about BROWSER4_CLI_HTTP_TIMEOUT_SECS.",
    "mostValuableImprovements": "1. Fix the build to bundle third-party dependencies in plugin JARs (shade plugin configuration). 2. Reduce per-image download timeout to 5-10s with fast failure, and add an overall generation deadline with partial-result fallback. 3. Resolve output paths relative to CLI CWD and display absolute paths in results. 4. Add dedicated timeout category for plugin tools with a reasonable default (5+ minutes). 5. Document `plugin <domain>` in help output with dynamically generated tool descriptions.",
    "usabilityRating": 3
  }
}
```

---

## Issues Found (10 issues)

### Issue 1: PPTX plugin JAR missing all third-party dependencies (POI not bundled)

**Severity:** Critical
**Category:** Product

#### Reproduction

mvn package -pl browser4-plugins/browser4-pptx && jar tf browser4-plugins/browser4-pptx/target/browser4-pptx-4.12.1-SNAPSHOT.jar | grep poi → no output. The JAR contains only com/platon/pulsar/pptx/* classes. Install the plugin and restart — bean creation fails with 'Post-processing of merged bean definition failed' for pptxGenerator because org.apache.poi.xslf.usermodel.XMLSlideShow is not on the classpath.

#### Expected Behavior

The plugin JAR should be self-contained with all non-provided dependencies bundled (as the README claims: 'Bundled in plugin JAR: Apache POI'). Installing the plugin should make it immediately usable.

#### Actual Behavior

The JAR is a thin JAR containing only the plugin's own classes. Missing: poi, poi-ooxml, poi-ooxml-lite, xmlbeans, SparseBitSet, commons-compress, commons-io, commons-collections4, curvesapi, commons-math3. The plugin fails to load until all 10 JARs are manually copied to the plugins directory.

#### Root Cause Analysis

The root pom.xml defines maven-shade-plugin version 3.6.1 but no module (including browser4-pdk which is the parent of browser4-pptx) actually configures a shade execution. The maven-jar-plugin produces a standard thin JAR. The POM correctly marks POI dependencies without <scope>provided</scope>, but the build pipeline never runs a shade/dependency-copy step for plugin modules.

#### Code Pointer

`pom.xml:598 (shade plugin version defined but never executed); browser4-pdk/pom.xml (missing shade plugin execution configuration).`

#### AI Suggested Improvement

- Add maven-shade-plugin execution to browser4-pdk/pom.xml that bundles compile-scope dependencies into each plugin JAR
- Alternatively, configure maven-dependency-plugin to copy runtime dependencies alongside each plugin JAR during the bundle assembly step
- Add a build-time validation test that verifies each plugin JAR contains its expected third-party classes (e.g. check for org.apache.poi.xslf.usermodel.XMLSlideShow in the pptx JAR)
- Update the README to accurately describe the current packaging (thin JAR requiring manual dependency management)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Image download timeout makes PPTX generation unusable on content-rich pages

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1. Navigate to https://en.wikipedia.org/wiki/Planet (68 images). 2. Run `plugin pptx --outputPath .test-sessions/`. 3. Blocks are extracted (182) but the request times out after exactly 5 minutes with `AsyncRequestTimeoutException` and `MonoCoroutine was cancelled`. No image download log entries appear before the timeout. This happened on all three Wikipedia pages tested.

#### Expected Behavior

PPTX generation should complete within a reasonable time (under 2-3 minutes) for a typical Wikipedia article. Slow or unreachable images should be skipped quickly (within a few seconds each).

#### Actual Behavior

Generation hangs during image download and exceeds the server's 5-minute async request timeout. No PPTX file is produced. The server becomes degraded, returning 503 for subsequent requests until restarted.

#### Root Cause Analysis

Two compounding factors: (1) The per-image OkHttp read timeout is 60 seconds (hardcoded in PptxAutoConfiguration.pptxDownloadClient()), so each slow image can consume up to 60s. With 3 concurrent downloads and 68 images, worst case is 68/3 × 60s ≈ 23 minutes. (2) The Spring Boot async request timeout defaults to 300,000ms (5 minutes) and is not configurable through the CLI or plugin properties. The semaphore-gated concurrent downloads can't complete within that window for image-heavy pages. Additionally, some Wikipedia images may return error responses that aren't handled quickly.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/config/PptxAutoConfiguration.kt:88-95 (OkHttpClient builder with 60s read timeout — should use configurable timeout or much shorter default, e.g. 10s). Also: PptxImageDownloader.kt:downloadImages() uses coroutineScope which awaits all downloads even if many have already failed.`

#### AI Suggested Improvement

- Reduce per-image OkHttp read timeout from 60s to 10s (with config override via pptx.download.timeout.seconds)
- Set per-request timeouts on individual OkHttp calls rather than relying solely on the global client timeout
- Add an overall generation deadline that gracefully cancels remaining downloads and writes a partial PPTX rather than failing entirely
- Implement fast-failure: after N consecutive download failures, skip remaining images
- Increase Spring Boot async request timeout to 10 minutes for PPTX generation specifically, or make it configurable via pptx.generate.timeout.seconds
- Add progress logging for image downloads (currently no log entries between 'extracted N blocks' and 'complete' or timeout)
- Consider lazy image download: embed a placeholder and download/embed images only if they respond within 5s

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Plugin command not discoverable from help output

**Severity:** High
**Category:** Discoverability

#### Reproduction

Run `./b4w.ps1 help` and search for 'plugin', 'pptx', or any plugin tool invocation. Run `./b4w.ps1 help --help plugin`.

#### Expected Behavior

The help output should document the `plugin <domain>` command for invoking plugin tools, or there should be a dedicated `pptx generate` command. A section like 'Plugins' in the help should list available plugin domains and their tools.

#### Actual Behavior

The main `help` output lists `plugin list`, `plugin info`, `plugin install`, and `plugin remove` — but NOT the `plugin <domain>` invocation form. The `plugin pptx` form is only discoverable by reading the CLI source code (main.rs:handle_dynamic_plugin_command). There is no `pptx` or `pptx generate` entry anywhere in help output.

#### Root Cause Analysis

The dynamic plugin command dispatch in main.rs handles `plugin <domain>` generically but this is not advertised as a user-facing command. The help text is generated from hardcoded CommandDef entries, and dynamic plugin commands have no CommandDef.

#### Code Pointer

`cli/browser4-cli/src/main.rs:11944 (handle_dynamic_plugin_command — works but undiscoverable); main.rs command registration (missing plugin tool entries in help generation).`

#### AI Suggested Improvement

- Add a 'Plugin Tools' section to the main help output listing available plugin domains and their tools (e.g. 'plugin pptx → pptx.generate()')
- Generate help text dynamically from the server's /mcp/tools endpoint so newly installed plugins appear automatically
- Add aliases so users can type `pptx generate` instead of `plugin pptx`
- Add `./b4w.ps1 plugin --help` showing available plugin tool domains

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Relative outputPath resolves to server CWD, not CLI CWD

**Severity:** High
**Category:** UX

#### Reproduction

Run `./b4w.ps1 plugin pptx --outputPath .test-sessions/` from repo root. The file is created at `<runtime-bundle>/.test-sessions/presentation_*.pptx`, not `<repo-root>/.test-sessions/presentation_*.pptx`.

#### Expected Behavior

The output path should resolve relative to the user's current working directory (where the CLI was invoked), or the CLI should translate the path before sending it to the server.

#### Actual Behavior

The path `.test-sessions/` is passed verbatim to the server, which resolves it relative to its own working directory (the runtime bundle directory). Users must search for their files using `find`.

#### Root Cause Analysis

The CLI's handle_dynamic_plugin_command passes through arguments as-is without resolving relative paths. The server's PptxToolExecutor resolves paths relative to the JVM's working directory, which is the runtime bundle directory.

#### Code Pointer

`cli/browser4-cli/src/main.rs:12016 (parse_raw_args passes outputPath verbatim); browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/tools/PptxToolExecutor.kt:91 (Path.of(outputPath) resolves relative to server CWD).`

#### AI Suggested Improvement

- The CLI should resolve relative paths to absolute paths before sending to the server
- Or the tool response should clearly indicate the absolute path (it already includes filePath — but the JSON output shows a relative path, causing confusion)
- The PptxToolExecutor should log the absolute path prominently

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Plugin tools have no dedicated timeout category (default 30s)

**Severity:** High
**Category:** Reliability

#### Reproduction

Run `./b4w.ps1 plugin pptx --outputPath .test-sessions/` on any non-trivial page. The request times out in 30 seconds with 'HTTP request timed out [tool=pptx_generate, timeout=30s]'.

#### Expected Behavior

Long-running plugin operations should have a reasonable timeout (e.g., 5 minutes for PPTX generation). There should be a dedicated environment variable like BROWSER4_CLI_PPTX_TIMEOUT_SECS or the tool should default to a higher timeout category.

#### Actual Behavior

Plugin tools fall through to the default 30-second timeout in timeout_for_tool(). Users must discover BROWSER4_CLI_HTTP_TIMEOUT_SECS through source code reading or trial and error.

#### Root Cause Analysis

In timeout_for_tool() (http.rs:129), the tool name 'pptx_generate' doesn't match any of the known categories (navigation, text_input, agent, snapshot, wait, batch, crawl). It falls through to the default 30-second timeout.

#### Code Pointer

`cli/browser4-cli/src/http.rs:129-145 (timeout_for_tool — missing plugin tool category).`

#### AI Suggested Improvement

- Add a plugin tool timeout category with a generous default (e.g., 300s) controlled by BROWSER4_CLI_PLUGIN_TIMEOUT_SECS
- Or allow individual tools to declare their expected timeout via the server's /mcp/tools endpoint and have the CLI honor it
- At minimum, document BROWSER4_CLI_HTTP_TIMEOUT_SECS prominently in the output of timed-out plugin commands

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Plugin list always shows 'inactive (restart required)' after restart

**Severity:** Medium
**Category:** UX

#### Reproduction

Install a plugin, restart the server, run `plugin list`. All plugins still show 'inactive (restart required)' even though the server log confirms they loaded successfully ('✓ Registered custom tool executor for domain: pptx').

#### Expected Behavior

After server restart, plugin list should show active plugins with their current status. Or at minimum, a different message like 'active' or 'running'.

#### Actual Behavior

All 5 plugins show 'inactive (restart required)' even after multiple restarts, even though 4 of 5 are actually working (pptx, images, markdown, media all registered their tool executors).

#### Root Cause Analysis

The plugin list display logic reads plugin status from the file system or installation metadata rather than from the runtime PluginManager state. The 'inactive' status reflects the installation state ('needs restart to activate') rather than the runtime state.

#### Code Pointer

`cli/browser4-cli/src/main.rs (handle_plugin_list) or the corresponding backend endpoint that returns plugin status.`

#### AI Suggested Improvement

- Query the runtime PluginManager for actual plugin activation status
- Show 'active' for plugins whose tool executors are registered in CustomToolRegistry
- Add a separate column showing install state vs runtime state

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Server degraded (503) after PPTX timeout — requires manual restart

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. Trigger a PPTX generation that times out (e.g., on any Wikipedia page). 2. Wait for the 5-minute async timeout. 3. Try any subsequent MCP tool call — returns 503 Service Unavailable.

#### Expected Behavior

After a tool timeout, the server should recover gracefully and handle new requests. The timed-out operation's resources should be released.

#### Actual Behavior

The server returns 503 for all subsequent requests. The session's thread pool appears exhausted. Only a full server restart (./b4w.ps1 stop && ./b4w.ps1 goto ...) recovers normal operation.

#### Root Cause Analysis

The PPTX generation runs on a coroutine dispatched to the IO thread pool. When the Spring async request times out after 5 minutes, the coroutine is cancelled but the OkHttp connections and thread pool resources may not be properly released. The server's session worker threads remain tied up, preventing new requests from being processed.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxImageDownloader.kt:131-155 (downloadImages uses coroutineScope which doesn't have a cancellation-aware timeout; OkHttp connections may leak on cancellation).`

#### AI Suggested Improvement

- Wrap image downloads in a withTimeout() block to ensure cancellation propagates properly
- Close OkHttp response bodies in finally blocks to prevent connection leaks on cancellation
- Add server-side health checks that detect and recover from stuck worker threads

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: No --help output for plugin tool commands

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Run `./b4w.ps1 plugin pptx --help` or `./b4w.ps1 pptx --help`.

#### Expected Behavior

Should show the tool's arguments (outputPath), description, and an example invocation.

#### Actual Behavior

No help available — `plugin pptx --help` is not recognized. Users must read the plugin README or source code to discover the `--outputPath` argument.

#### Root Cause Analysis

Dynamic plugin commands have no CommandDef with associated help text. The argument parsing is generic (parse_raw_args). The tool spec from the server (ToolSpec with arguments list) is never surfaced to the CLI user.

#### Code Pointer

`cli/browser4-cli/src/main.rs:12016 (parse_raw_args with no help integration); main.rs command registration (no dynamic help generation for plugin tools).`

#### AI Suggested Improvement

- Query the server's /mcp/tools endpoint for tool specs and generate help text from the ToolSpec metadata (arguments, description, help fields)
- Support `plugin pptx --help` to show: pptx.generate(outputPath?: String) — description, argument list, return type

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: Content extraction silently skips boilerplate — 'Planet' page title would be filtered

**Severity:** Low
**Category:** Product

#### Reproduction

The word 'Planet' appears in the extraction boilerplate filter (CONTENT_EXTRACTION_SCRIPT:183-186). Short heading text matching boilerplate patterns like 'home', 'about', 'contact', 'privacy', 'terms', 'copyright' is silently discarded.

#### Expected Behavior

Reasonable content filtering, but the boilerplate regex should be reviewed for false positives. The filter should be documented so users understand why some content is excluded.

#### Actual Behavior

The filter regex `/^(menu|search|login|sign up|sign in|subscribe|share|follow|next|previous|prev|back|top|scroll|close|accept|cancel|submit|reset|loading|home|about|contact|privacy|terms|copyright|all rights reserved)$/i` is applied to heading text. 'Planet' is NOT affected (not in the list), but legitimate content headings that match (e.g., 'Contact' on a company page) would be silently dropped.

#### Root Cause Analysis

The shouldSkipText() function filters short text (<2 chars) and boilerplate terms. This is a reasonable heuristic but is not configurable and not documented.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PageContentExtractor.kt:182-187 (shouldSkipText with hardcoded boilerplate regex).`

#### AI Suggested Improvement

- Make the boilerplate filter configurable via pptx.extract.skip-patterns property
- Log skipped content at DEBUG level so users can audit what was excluded
- Document the boilerplate filter in the README

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: CLI silently succeeds (exit 0) but produces no output with --json on certain failures

**Severity:** Low
**Category:** Reliability

#### Reproduction

Run `./b4w.ps1 --json plugin pptx --outputPath .test-sessions/` after a previous timeout has degraded the server. Exit code is 0 but no JSON output is produced.

#### Expected Behavior

Either JSON error output or a non-zero exit code.

#### Actual Behavior

Exit code 0 with no stdout output. The user sees nothing and has no indication that the command failed.

#### Root Cause Analysis

Unclear — possibly related to the 503 response being handled by the reqwest error path without proper JSON error formatting when --json mode is active, or the error message is written to stderr which the shell redirection didn't capture.

#### Code Pointer

`cli/browser4-cli/src/http.rs:327-407 (call_tool_with_timeout error handling and JSON output paths).`

#### AI Suggested Improvement

- Ensure all error paths produce JSON output when --json is active
- Set non-zero exit code on tool failures
- Never silently exit 0 when the tool call failed

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

**Completion Status:** Partially Successful — The plugin works correctly for simple, image-free pages (httpbin.org: 2 slides, 28KB valid PPTX, 360ms). Content extraction is reliable (correctly identifies 182-323 blocks on Wikipedia pages). However, PPTX generation fails on all tested Wikipedia pages due to image download timeouts exceeding the 5-minute server async request limit. The plugin required manual installation of 10 missing JAR dependencies before it could load.

**Success Rate:** 25% — 1 of 4 pages tested produced a complete PPTX file (httpbin.org). 3 Wikipedia pages (Solar System, Moon, Planet) all failed during image download. Content extraction succeeded on all 4 pages (100% extraction success rate).

**Issues Found:** 10

**Major Blockers:** 1. Plugin JAR packaging is broken — 10 missing runtime dependencies must be manually installed. 2. Image downloading exceeds the 5-minute server timeout on any page with more than ~20 images, making the plugin unusable for most real-world web pages. 3. After a timeout, the server enters a degraded state requiring manual restart.

**Most Confusing Aspects:** 1. Plugin invocation requires `plugin pptx` syntax — not documented in help, no --help available, not intuitive. 2. PPTX files silently appear in the server's working directory, not the user's current directory. 3. After installing the plugin, it appears 'inactive (restart required)' even after restart, yet may actually work. 4. No indication that a 30-second default timeout exists for plugin tools — failure message gives no hint about BROWSER4_CLI_HTTP_TIMEOUT_SECS.

**Most Valuable Improvements:** 1. Fix the build to bundle third-party dependencies in plugin JARs (shade plugin configuration). 2. Reduce per-image download timeout to 5-10s with fast failure, and add an overall generation deadline with partial-result fallback. 3. Resolve output paths relative to CLI CWD and display absolute paths in results. 4. Add dedicated timeout category for plugin tools with a reasonable default (5+ minutes). 5. Document `plugin <domain>` in help output with dynamically generated tool descriptions.

**Usability Rating:** 3/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: PPTX plugin JAR missing all third-party dependencies (POI not bundled)

mvn package -pl browser4-plugins/browser4-pptx && jar tf browser4-plugins/browser4-pptx/target/browser4-pptx-4.12.1-SNAPSHOT.jar | grep poi → no output. The JAR contains only com/platon/pulsar/pptx/* classes. Install the plugin and restart — bean creation fails with 'Post-processing of merged bean definition failed' for pptxGenerator because org.apache.poi.xslf.usermodel.XMLSlideShow is not on the classpath.

#### Issue 2: Image download timeout makes PPTX generation unusable on content-rich pages

1. Navigate to https://en.wikipedia.org/wiki/Planet (68 images). 2. Run `plugin pptx --outputPath .test-sessions/`. 3. Blocks are extracted (182) but the request times out after exactly 5 minutes with `AsyncRequestTimeoutException` and `MonoCoroutine was cancelled`. No image download log entries appear before the timeout. This happened on all three Wikipedia pages tested.

#### Issue 3: Plugin command not discoverable from help output

Run `./b4w.ps1 help` and search for 'plugin', 'pptx', or any plugin tool invocation. Run `./b4w.ps1 help --help plugin`.

#### Issue 4: Relative outputPath resolves to server CWD, not CLI CWD

Run `./b4w.ps1 plugin pptx --outputPath .test-sessions/` from repo root. The file is created at `<runtime-bundle>/.test-sessions/presentation_*.pptx`, not `<repo-root>/.test-sessions/presentation_*.pptx`.

#### Issue 5: Plugin tools have no dedicated timeout category (default 30s)

Run `./b4w.ps1 plugin pptx --outputPath .test-sessions/` on any non-trivial page. The request times out in 30 seconds with 'HTTP request timed out [tool=pptx_generate, timeout=30s]'.

#### Issue 6: Plugin list always shows 'inactive (restart required)' after restart

Install a plugin, restart the server, run `plugin list`. All plugins still show 'inactive (restart required)' even though the server log confirms they loaded successfully ('✓ Registered custom tool executor for domain: pptx').

#### Issue 7: Server degraded (503) after PPTX timeout — requires manual restart

1. Trigger a PPTX generation that times out (e.g., on any Wikipedia page). 2. Wait for the 5-minute async timeout. 3. Try any subsequent MCP tool call — returns 503 Service Unavailable.

#### Issue 8: No --help output for plugin tool commands

Run `./b4w.ps1 plugin pptx --help` or `./b4w.ps1 pptx --help`.

#### Issue 9: Content extraction silently skips boilerplate — 'Planet' page title would be filtered

The word 'Planet' appears in the extraction boilerplate filter (CONTENT_EXTRACTION_SCRIPT:183-186). Short heading text matching boilerplate patterns like 'home', 'about', 'contact', 'privacy', 'terms', 'copyright' is silently discarded.

#### Issue 10: CLI silently succeeds (exit 0) but produces no output with --json on certain failures

Run `./b4w.ps1 --json plugin pptx --outputPath .test-sessions/` after a previous timeout has degraded the server. Exit code is 0 but no JSON output is produced.

