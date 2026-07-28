# Issues: plugin-pptx-generation

> **Source:** `20260728-021911-plugin-pptx-generation.full.md` | **Date:** 20260728-021911 | **Mode:** dev

## Scenario Background

### Task

I successfully tested the **browser4-pptx** plugin by generating PowerPoint presentations from three web pages of varying complexity:

| Page | Slide Count | Block Count | Image Count | File Size | Duration |
|------|------------|-------------|-------------|-----------|----------|
| **httpbin.org/html** (minimal) | 2 | 2 | 0 | 28 KB | 1.1s |
| **Wikipedia Solar System** (large featured article) | 48 | 213 | 52 | 203 KB | 70.1s |
| **Wikipedia Moon** (medium article) | 69 | 323 | 72 | 304 KB | 201.9s |

**How well does `pptx.generate` handle pages of varying complexity?**

- **Minimal pages** (httpbin): Works instantly and produces a tiny but valid PPTX. A simple page with just headings and paragraphs produces 2 clean slides.
- **Rich Wikipedia articles**: Works well but is very slow (1–3 minutes per page) due to image downloading. The content-to-slide mapping is sensible — headings become section slides, and content flows into content slides.
- **Content type mapping**: Headings, paragraphs, images, tables, lists, and blockquotes are all extracted. Images are downloaded and embedded. Tables preserve row/header structure. Lists are rendered with bullets/numbers. The title slide includes page title + URL.

**Notable observation**: The Moon article (69 slides) produced more slides than the Solar System featured article (48 slides), despite Solar System being longer/more comprehensive. This is because slide count is driven by heading hierarchy depth and the `maxContentBlocksPerSlide` (default: 6) setting — the Moon article appears to have more fine-grained heading sections.

**Important caveats**: Three issues were discovered that significantly impact usability (detailed in Section C below).

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — Verified available commands
2. `./b4w.ps1 plugin list` — Discovered pptx plugin not installed
3. `./b4w.ps1 plugin install ...` — Installed the pptx JAR
4. `./b4w.ps1 kill-all` → `./b4w.ps1 plugin-pptx` — Plugin failed to load (missing POI deps)
5. `mvn dependency:copy-dependencies` — Copied transitive POI deps
6. Copied POI JARs into runtime bundle plugins directory
7. `./b4w.ps1 goto "https://en.wikipedia.org/wiki/Solar_System"` — Navigation
8. `./b4w.ps1 plugin-pptx` — **Timed out after 30s** (image downloading took 70s)
9. `curl POST /mcp/call-tool` with `--max-time 600` — **Success** (Solar System: 48 slides)
10. `./b4w.ps1 goto "https://en.wikipedia.org/wiki/Moon"` → curl call — **Success** (Moon: 69 slides)
11. `./b4w.ps1 goto "https://httpbin.org/html"` → `./b4w.ps1 plugin-pptx` — **Success** (httpbin: 2 slides)

**Workarounds required:**
- Manually copied Apache POI and all transitive dependencies (10+ JARs) into the runtime's plugins directory
- Used direct `curl` calls instead of `plugin-pptx` for Wikipedia pages due to 30s CLI timeout
- Had to restart the server multiple times during debugging

**Important decisions made:**
- Used `plugin-pptx` dynamic command (discovered by reading CLI source code — not documented in SKILL.md)
- Bypassed the CLI for Wikipedia pages because the hardcoded 30-second MCP tool call timeout is insufficient

---

## Issues Found (8 issues)

### Issue 1: PPTX plugin JAR does not bundle Apache POI dependencies

**Severity:** Critical
**Category:** Product

#### Reproduction

Install browser4-pptx plugin and restart server. Check server log for 'Post-processing of merged bean definition failed' error during PptxAutoConfiguration loading.

#### Expected Behavior

The plugin JAR should self-contain all its private dependencies (poi, poi-ooxml, xmlbeans, commons-compress, commons-io, curvesapi, commons-collections4, SparseBitSet) so it loads without manual intervention.

#### Actual Behavior

The plugin JAR (76 KB) contains only its own compiled classes. None of the Apache POI transitive dependencies are bundled. Spring fails to create the pptxGenerator bean because PptxGenerator.class references org.apache.poi.xslf.usermodel.XMLSlideShow which is not on the classpath.

#### Root Cause Analysis

The plugin's pom.xml declares poi and poi-ooxml with default (compile) scope but the build does not produce a fat JAR. The PluginClasspathEnhancer only adds JARs to the classpath but does not resolve transitive dependencies. The 'Post-processing of merged bean definition failed' error message is unhelpful — it hides a NoClassDefFoundError for POI classes.

#### Code Pointer

`browser4-plugins/browser4-pptx/pom.xml — needs maven-assembly-plugin or maven-shade-plugin to bundle private dependencies`

#### AI Suggested Improvement

- Add maven-shade-plugin to the pptx plugin build to create a fat JAR that includes org.apache.poi:* and all transitive runtime dependencies
- Alternatively, use maven-assembly-plugin with jar-with-dependencies descriptor
- Add a build-time check that verifies all non-provided dependencies are present in the output JAR
- Improve error message: catch NoClassDefFoundError during bean creation and log 'Plugin JAR is missing dependency: org.apache.poi.xslf.usermodel.XMLSlideShow. Ensure all private dependencies are bundled.'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: CLI hardcoded 30-second MCP tool timeout is inadequate for long-running tools

**Severity:** High
**Category:** Reliability

#### Reproduction

Navigate to a Wikipedia article with images (e.g. Solar System) and run `browser4-cli plugin-pptx`. The command times out after 30 seconds with 'HTTP request timed out' while the server is still processing.

#### Expected Behavior

The CLI should either use a longer timeout for known-long tools, allow timeout configuration, or support asynchronous tool execution with status polling.

#### Actual Behavior

The CLI returns 'HTTP request timed out [timeout=30s]' while the server continues processing. The PPTX eventually completes but the CLI user gets an error. The only workaround is to use curl directly.

#### Root Cause Analysis

The CLI's `handle_dynamic_plugin_command` function calls `call_tool()` which uses `call_tool_with_timeout(client, base_url, tool, args, None)` where `None` maps to a 30-second default. There is no mechanism for tools to declare expected execution time, and no `--timeout` flag for plugin commands.

#### Code Pointer

`cli/browser4-cli/src/http.rs:call_tool_with_timeout() — default timeout logic; cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() — needs timeout override`

#### AI Suggested Improvement

- Add a `--timeout <seconds>` global flag to allow users to override the default 30s timeout per command
- Add per-tool timeout overrides in the tool registry (e.g., pptx_generate could declare a 300s timeout)
- Support async tool execution pattern: `plugin-pptx --async` returns a task ID, then `plugin-pptx result <id>` retrieves the result
- Increase the default timeout for dynamic plugin commands to at least 120s

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: No documentation for plugin commands (plugin-pptx, plugin-markdown, etc.)

**Severity:** High
**Category:** Discoverability

#### Reproduction

Run `browser4-cli help` and search for any mention of plugin commands like plugin-pptx or the plugin-<name> pattern. Neither the main help nor the skill file documents how to use installed plugins.

#### Expected Behavior

The help output and SKILL.md should document the plugin-<domain> command pattern and list available plugin commands. There should be discoverable documentation for each plugin's tools and parameters.

#### Actual Behavior

The help output shows `plugin list`, `plugin info`, `plugin install`, `plugin remove` but never mentions the `plugin-<domain>` pattern. The SKILL.md discusses plugin installation but not plugin usage. Users must read CLI source code to discover `plugin-pptx`.

#### Root Cause Analysis

The `plugin-<domain>` command is implemented in main.rs:14615-14631 as a dynamic fallback but is never documented. The SKILL.md plugin section only covers plugin lifecycle management (install/remove), not plugin tool invocation.

#### Code Pointer

`cli/browser4-cli/src/main.rs:14615-14631 — dynamic plugin command dispatch; skills/browser4-cli/SKILL.md — missing plugin usage section`

#### AI Suggested Improvement

- Add a 'Plugin Tools' section to the help output listing available plugin-<domain> commands (discovered via /mcp/tools)
- Add a 'Using Plugins' section to SKILL.md with the plugin-<domain> <method> pattern
- Add `browser4-cli plugin-pptx --help` support showing available methods and their parameters
- Include a real example: `browser4-cli goto 'https://example.com' && browser4-cli plugin-pptx`
- Add `browser4-cli plugin` (bare) listing: 'Available plugin commands: plugin-pptx, plugin-markdown, ...'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: CLI requires reading source code to discover plugin invocation pattern

**Severity:** High
**Category:** Discoverability

#### Reproduction

As a first-time user wanting to use the pptx plugin: (1) Read `browser4-cli help` — no mention of plugin tool invocation. (2) Read SKILL.md — discusses plugin install/remove but not invocation. (3) Try `browser4-cli pptx.generate` — 'Unknown command'. (4) Try `browser4-cli pptx generate` — 'Unknown command'. (5) Try `browser4-cli --json plugin list` — shows installed plugins but no usage instructions. The pattern `plugin-<domain>` is only discoverable by reading CLI Rust source code.

#### Expected Behavior

A user should be able to discover how to use installed plugins from `browser4-cli help`, the SKILL.md, or at minimum from the output of `browser4-cli plugin list` or `browser4-cli plugin info <name>`.

#### Actual Behavior

No command, help text, skill file, or plugin management output documents the `plugin-<domain>` invocation pattern. The `plugin info` command was not tested but likely doesn't include usage syntax either.

#### Root Cause Analysis

The plugin-<domain> command system was added as a dynamic dispatch mechanism without corresponding documentation updates to help output, SKILL.md, or plugin info output.

#### Code Pointer

`cli/browser4-cli/src/main.rs:14615-14631 — the dynamic plugin dispatch; skills/browser4-cli/SKILL.md — no plugin usage docs`

#### AI Suggested Improvement

- Document the plugin invocation pattern prominently in help output: 'Plugin tools: plugin-<name> <method> [args...]'
- Add usage instructions to `plugin info` output: 'Usage: browser4-cli plugin-pptx [--outputPath <path>]'
- Add a tip after `plugin install`: 'After server restart, use: browser4-cli plugin-<name>'
- Consider adding aliases so `browser4-cli pptx generate` also works as a more intuitive alternative

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: plugin-pptx dynamic command does not pass arguments to the tool

**Severity:** Medium
**Category:** Product

#### Reproduction

Run `browser4-cli plugin-pptx --outputPath /custom/dir`. The outputPath argument is silently ignored.

#### Expected Behavior

The dynamic plugin command should pass user-supplied arguments (like --outputPath) to the underlying MCP tool.

#### Actual Behavior

Looking at the code, handle_dynamic_plugin_command uses the first matching tool and passes raw parsed args as tool params, but it doesn't consume positional arguments for the method name. For pptx with one method (generate) this works by accident, but the outputPath parameter can't be passed because the arg parsing strips positional args and only passes --key value pairs.

#### Root Cause Analysis

The `handle_dynamic_plugin_command` function at line 11544-11550 parses args with `parse_raw_args` but the tool spec defines `outputPath` as a named argument. The arg parsing may not correctly map `--outputPath` to the tool parameter.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() — line 11544 arg parsing; line 11540-11541 always picks the first matching tool without method selection`

#### AI Suggested Improvement

- Support method selection by checking the first positional arg after plugin-<domain> (e.g., `plugin-pptx generate`)
- Pass all remaining --key value pairs as tool arguments
- Add `plugin-pptx --help` to show available methods and their parameters for a domain
- Add method selection when multiple tools exist in a domain (e.g., if pptx gains pptx_convert in the future)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Image download takes 1-3+ minutes for Wikipedia articles with no progress indication

**Severity:** Medium
**Category:** UX

#### Reproduction

Run pptx.generate on any Wikipedia article. Between 'extracted N blocks' and 'pptx.generate complete' log lines, there is a multi-minute silence with no progress output.

#### Expected Behavior

The tool should log progress during image downloading (e.g., 'Downloading image 3/52...', 'Image download failed, skipping') so users and monitoring systems know the tool is still working.

#### Actual Behavior

Zero progress logs between content extraction and completion. The user sees only the initial 'extracted N blocks' log, then minutes of silence, then the final completion log. This makes the tool appear hung.

#### Root Cause Analysis

The PptxImageDownloader.downloadImages function downloads images concurrently but only logs at the end (download count). Individual download failures are logged at DEBUG level only. There is no periodic progress reporting.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxImageDownloader.kt:downloadImages() — needs progress logging`

#### AI Suggested Improvement

- Log progress every N images: 'Downloaded 10/52 images (5 failures)...' 
- Upgrade image download failure logs from DEBUG to INFO or WARN level so they appear in default logging
- Consider adding a `--skip-images` flag to pptx.generate for users who want fast text-only PPTX generation
- Add a configurable per-image timeout (currently relies on OkHttp's 30s connect / 60s read timeouts)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Unhelpful error message when plugin fails to load

**Severity:** Medium
**Category:** UX

#### Reproduction

Install pptx plugin, restart server, run `browser4-cli plugin-pptx`. The error is 'No plugin tools found for pptx'. The actual root cause (missing POI dependencies) is only visible in the server log as 'Post-processing of merged bean definition failed'.

#### Expected Behavior

The CLI should surface actionable error information. When no tools are found for a domain, it should suggest checking `plugin list` status and server logs. When the server log contains a bean creation error, the CLI should relay a summary.

#### Actual Behavior

CLI shows 'No plugin tools found for pptx' with a list of unrelated available plugin commands. The user has no indication of what went wrong or how to fix it. The server log has the real error but users wouldn't know to look there.

#### Root Cause Analysis

The `handle_dynamic_plugin_command` function queries /mcp/tools and lists matching tools. When a plugin's auto-configuration fails, its tools are never registered, so the list is empty. The error message doesn't guide the user to check server logs or plugin status.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() — error message at line 11524-11538; browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/config/PptxAutoConfiguration.kt:getToolExecutors() — error is silently caught`

#### AI Suggested Improvement

- After 'No plugin tools found', suggest: 'The plugin may have failed to initialize. Check plugin status with `browser4-cli plugin list` and review server logs with `browser4-cli doctor log`.'
- Log the full stack trace (not just message) when getToolExecutors() fails, so the server log has diagnostic details
- Add a `plugin status <name>` command that reports whether a plugin loaded successfully and why it failed if not

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Generated PPTX uses presentation.pptx as filename when page title is empty

**Severity:** Low
**Category:** Product

#### Reproduction

Navigate to httpbin.org/html (which returns empty page title) and run pptx.generate. The output filename is 'presentation_YYYYMMDD_HHmmss.pptx' instead of using the URL or a more descriptive name.

#### Expected Behavior

When page title is empty, the filename should fall back to a URL-derived name (e.g., 'httpbin_org_html_YYYYMMDD_HHmmss.pptx') rather than the generic 'presentation'.

#### Actual Behavior

The output file is named 'presentation_20260728_100236.pptx' because httpbin.org/html returns an empty <title>. The generic name provides no hint about the source page.

#### Root Cause Analysis

In PptxGenerator.sanitizeFilename(), when name is empty after sanitization, it defaults to 'presentation'. The empty title case should use the URL hostname + path as a fallback.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxGenerator.kt:sanitizeFilename() — the `ifBlank { 'presentation' }` fallback`

#### AI Suggested Improvement

- Extract a filename-friendly slug from the URL when title is blank (e.g., URL host + path with special chars replaced)
- Example: https://httpbin.org/html → httpbin_org_html_YYYYMMDD_HHmmss.pptx
- Keep 'presentation' as a last-resort fallback only when both title and URL are unusable

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — All three PPTX files were generated and verified valid, but required major workarounds: manually copying 10+ POI dependency JARs, using curl instead of the CLI for large pages, and reading CLI source code to discover the plugin-pptx invocation pattern.

**Success Rate:** 60% — 3 of 5 test scenarios produced valid PPTX files, but 5 of 5 required workarounds or exhibited issues. The core PPTX generation logic works correctly; the problems are entirely in packaging, CLI timeout handling, and discoverability.

**Issues Found:** 8

**Major Blockers:** 1) PPTX plugin JAR does not bundle Apache POI dependencies (Critical — requires manual installation of 10+ JARs). 2) CLI hardcoded 30s timeout makes the tool unusable for pages with images without resorting to curl. 3) The plugin-<domain> invocation pattern is undocumented and undiscoverable.

**Most Confusing Aspects:** Discovering how to invoke the PPTX plugin required reading CLI Rust source code — neither `browser4-cli help`, `plugin list`, `plugin info`, nor SKILL.md documents the `plugin-pptx` pattern. After discovering it, the command returned unhelpful errors ('No plugin tools found') when the real problem was missing POI dependencies. After fixing that, the command timed out silently while the server kept working.

**Most Valuable Improvements:** 1) Bundle plugin private dependencies (maven-shade-plugin). 2) Add --timeout flag or async execution for long-running tools. 3) Document plugin-<domain> invocation in help and SKILL.md. 4) Add progress logging during image downloads. 5) Improve error messages to guide users to server logs when plugins fail to load.

**Usability Rating:** 3/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: PPTX plugin JAR does not bundle Apache POI dependencies

Install browser4-pptx plugin and restart server. Check server log for 'Post-processing of merged bean definition failed' error during PptxAutoConfiguration loading.

#### Issue 2: CLI hardcoded 30-second MCP tool timeout is inadequate for long-running tools

Navigate to a Wikipedia article with images (e.g. Solar System) and run `browser4-cli plugin-pptx`. The command times out after 30 seconds with 'HTTP request timed out' while the server is still processing.

#### Issue 3: No documentation for plugin commands (plugin-pptx, plugin-markdown, etc.)

Run `browser4-cli help` and search for any mention of plugin commands like plugin-pptx or the plugin-<name> pattern. Neither the main help nor the skill file documents how to use installed plugins.

#### Issue 4: CLI requires reading source code to discover plugin invocation pattern

As a first-time user wanting to use the pptx plugin: (1) Read `browser4-cli help` — no mention of plugin tool invocation. (2) Read SKILL.md — discusses plugin install/remove but not invocation. (3) Try `browser4-cli pptx.generate` — 'Unknown command'. (4) Try `browser4-cli pptx generate` — 'Unknown command'. (5) Try `browser4-cli --json plugin list` — shows installed plugins but no usage instructions. The pattern `plugin-<domain>` is only discoverable by reading CLI Rust source code.

#### Issue 5: plugin-pptx dynamic command does not pass arguments to the tool

Run `browser4-cli plugin-pptx --outputPath /custom/dir`. The outputPath argument is silently ignored.

#### Issue 6: Image download takes 1-3+ minutes for Wikipedia articles with no progress indication

Run pptx.generate on any Wikipedia article. Between 'extracted N blocks' and 'pptx.generate complete' log lines, there is a multi-minute silence with no progress output.

#### Issue 7: Unhelpful error message when plugin fails to load

Install pptx plugin, restart server, run `browser4-cli plugin-pptx`. The error is 'No plugin tools found for pptx'. The actual root cause (missing POI dependencies) is only visible in the server log as 'Post-processing of merged bean definition failed'.

#### Issue 8: Generated PPTX uses presentation.pptx as filename when page title is empty

Navigate to httpbin.org/html (which returns empty page title) and run pptx.generate. The output filename is 'presentation_YYYYMMDD_HHmmss.pptx' instead of using the URL or a more descriptive name.

