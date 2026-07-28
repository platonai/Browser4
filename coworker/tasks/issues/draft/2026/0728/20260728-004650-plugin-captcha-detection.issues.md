# Issues: plugin-captcha-detection

> **Source:** `20260728-004650-plugin-captcha-detection.full.md` | **Date:** 20260728-004650 | **Mode:** dev

## Scenario Background

### Task

The browser4-captcha plugin evaluation was completed, though with significant blockers. The `captcha.detect` tool was **not functional** due to a critical plugin bean initialization failure — the CaptchaAutoConfiguration's `@Bean` methods are never processed when loaded via PluginClasspathEnhancer. Detection was performed via JavaScript `eval` as a workaround.

### Detection Results (via eval simulation)

| Step | Page | isPresent | captchaType | siteKey | confidence |
|------|------|-----------|-------------|---------|------------|
| 2 | reCAPTCHA v2 Demo | ✅ true | RECAPTCHA_V2 | `6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-` | 0.95 |
| 3 | hCaptcha Demo | ✅ true | HCAPTCHA | `a5f74b19-9e45-40e0-b45d-47ff91b7a6c2` | 0.95 |
| 4 | Turnstile Demo | ✅ true | TURNSTILE | `1x00000000000000000000AA` | 0.95 |
| 5 | Wikipedia CAPTCHA | ⚠️ **FALSE POSITIVE** | IMAGE | (article illustration URL) | 0.80 |
| 6 | Solver Balance | ❌ Unavailable | — | — | — |

**Key findings:**
- reCAPTCHA v2, hCaptcha, and Turnstile were all correctly identified
- **False positive on Wikipedia**: The article contains an illustrative CAPTCHA image (`Modern-captcha.jpg`) — a more sophisticated detector should distinguish interactive widgets from article illustrations
- **Plugin tools unavailable throughout** — `captcha.detect`, `captcha.solve`, `captcha.getBalance` were never accessible
- Detection speed: all eval-based detections completed in < 3s

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` / `./b4w.sh help` — learned available commands
2. `./b4w.ps1 plugin list` — no plugins installed initially
3. `cp` plugin JAR → runtime bundles `plugins/` directory
4. `./b4w.ps1 plugin-captcha` → **failed**: "No plugin tools found for 'captcha'"
5. `./b4w.sh goto <url>` — navigated to each test page
6. `./b4w.sh eval --json --file .test-sessions/detect_captcha.js` — workaround detection
7. `curl POST /mcp/call-tool` with `captcha_detect` / `captcha_getBalance` → "Unknown tool"
8. `/actuator/beans` — confirmed only CaptchaAutoConfiguration exists; none of its @Bean methods were processed

**Workarounds Applied During Task:**

- **b4w.sh vs b4w.ps1**: PowerShell parameter binder intercepts `-v` (maps to `-Verbose`) and `-i` (maps to `-InformationAction`), making `snapshot -v 0` and `snapshot -i` unusable with `b4w.ps1`. Had to switch to `b4w.sh`
- **eval instead of captcha.detect**: Wrote a custom JavaScript detection script since plugin tools were unavailable
- **Plugin JAR installation**: Copying the JAR to the plugins directory wasn't sufficient — `plugin install` returned 409 Conflict, plugin remained "inactive"

---

---

## Issues Found (8 issues)

### Issue 1: CAPTCHA plugin @Bean methods not processed — tools unavailable

**Severity:** Critical
**Category:** Product

#### Reproduction

1. Copy browser4-captcha JAR to runtime plugins/ directory. 2. Start server. 3. Run `./b4w.sh plugin-captcha`. 4. Check /actuator/beans — only CaptchaAutoConfiguration exists, none of its @Bean methods (captchaConfig, captchaDetector, captchaSolver, captchaToolExecutor) are created.

#### Expected Behavior

CaptchaToolExecutor bean is created and registered in CustomToolRegistry. Tools captcha_detect, captcha_solve, captcha_getBalance, captcha_solveImage appear in /mcp/tools and are callable.

#### Actual Behavior

PluginClasspathEnhancer finds the JAR. PluginManager detects CaptchaAutoConfiguration as a PluginMount bean (8 found, including captcha). But getToolExecutors() returns empty list — the captchaToolExecutor bean was never created. No captcha tools in /mcp/tools. No warning logged from CaptchaAutoConfiguration.

#### Root Cause Analysis

The captcha plugin JAR is added to the classpath dynamically by PluginClasspathEnhancer AFTER Spring Boot has already scanned for auto-configuration imports (META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports). The @AutoConfiguration class is found by PluginManager as a PluginMount bean, but Spring does not process its @Bean methods because it was never imported through the standard auto-configuration mechanism. The class exists as a regular singleton bean, not as a processed @Configuration class with its bean definitions.

#### Code Pointer

`browser4-boot/src/main/kotlin/ai/platon/browser4/boot/plugin/PluginClasspathEnhancer.kt:enhance() — classpath enhancement happens too late. browser4-boot/src/main/kotlin/ai/platon/browser4/boot/plugin/PluginManager.kt:wireToolMount() — should create beans from the auto-config class if its @Bean methods aren't processed by Spring.`

#### AI Suggested Improvement

- Move PluginClasspathEnhancer to run BEFORE Spring Boot auto-configuration import scanning
- Or: In PluginManager.wireToolMount(), detect when getToolExecutors() returns empty and manually instantiate the bean chain (captchaConfig → captchaDetector → captchaSolver → captchaToolExecutor)
- Or: Use Spring's ConfigurableApplicationContext to programmatically register the @Configuration class from the plugin JAR so its @Bean methods are processed
- Or: Document that plugins must be placed in the plugins/ directory BEFORE starting the server for the first time (cold start), and add a 'plugin reload' command that triggers a full context refresh

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Plugin tools not discoverable via CLI help or command completion

**Severity:** High
**Category:** Discoverability

#### Reproduction

Run `./b4w.sh help`. Search for 'captcha', 'detect', or 'solve' — no mention of CAPTCHA tools anywhere in the help output.

#### Expected Behavior

Plugin tools should appear in help output or at minimum be discoverable via `plugin <domain>` or `plugin list` commands. A user reading the captcha.detect documentation should be able to find how to invoke it.

#### Actual Behavior

The `captcha.detect` tool name is completely absent from CLI help. The only way to discover plugin tools is `plugin-<domain>` which lists available tools. But even when working, the dynamic plugin command only invokes the FIRST tool (alphabetically), with no way to select a specific method.

#### Root Cause Analysis

Plugin tools are registered dynamically via CustomToolRegistry at server startup. The CLI has no built-in knowledge of plugin tool names. The `plugin-<domain>` mechanism only picks the first matching tool. There's no `plugin-<domain> <method>` syntax to select specific tools.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() line 11541 — only uses matching[0]. main.rs:14621 — only plugin-<domain> prefix supported, no method selection syntax.`

#### AI Suggested Improvement

- Add `plugin-<domain> <method>` syntax support in handle_dynamic_plugin_command() to allow method selection
- List plugin tool names in `--help` output by fetching /mcp/tools at startup
- Add a `tools` or `tools-list` command that shows all available MCP tools including plugin tools
- Document the plugin tool invocation syntax prominently in SKILL.md

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: b4w.ps1 PowerShell flag interception breaks short flags (-v, -i)

**Severity:** High
**Category:** UX

#### Reproduction

Run `./b4w.ps1 snapshot -v 0 --stdout`. The -v flag is consumed by PowerShell as -Verbose, and the CLI receives 'snapshot 0 --stdout' instead of 'snapshot -v 0 --stdout'.

#### Expected Behavior

Flags should pass through to the browser4-cli binary regardless of PowerShell's parameter binder.

#### Actual Behavior

PowerShell's param() block intercepts -v (matching -Verbose), -i (matching -InformationAction). The CLI receives truncated arguments. The workaround (./b4w.ps1 -- snapshot -v 0) also fails because the -- handling strips the flag. Only ./b4w.sh works correctly on Linux.

#### Root Cause Analysis

b4w.ps1 line 442-446 builds a quoted argument list using Invoke-Expression, but the arguments have already been processed by PowerShell's param() block which strips matching common parameters before they reach the $RemainingArgs variable.

#### Code Pointer

`b4w.ps1:16-20 — param() block should not use standard PowerShell parameter names. b4w.ps1:442-446 — SafeArgs quoting happens too late; flags already consumed by param().`

#### AI Suggested Improvement

- Rename param() block parameters to avoid collisions: -Rebuild → -B4wRebuild, remove -RemainingArgs and use $args instead
- Or: Use --% (stop-parsing symbol) in the wrapper to prevent PowerShell from interpreting any subsequent tokens
- Or: Document that b4w.sh must be used on Linux/macOS for any command with short flags

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Image CAPTCHA detector produces false positives on educational content

**Severity:** Medium
**Category:** Product

#### Reproduction

Navigate to https://en.wikipedia.org/wiki/CAPTCHA and run CAPTCHA detection. The article contains an illustrative CAPTCHA image (Modern-captcha.jpg) which is detected as isPresent=true with type IMAGE.

#### Expected Behavior

The detector should distinguish between interactive CAPTCHA widgets and static images that happen to depict CAPTCHAs (e.g., in articles, documentation, tutorials).

#### Actual Behavior

The IMAGE detector triggers on any <img> element whose src or id contains 'captcha', producing a false positive on Wikipedia's article about CAPTCHA.

#### Root Cause Analysis

The ImageCaptchaDetector likely uses a simple src/id substring match for 'captcha' without additional heuristics (input field proximity, form context, visibility, size constraints) to distinguish real CAPTCHA images from article illustrations.

#### Code Pointer

`browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/detection/ImageCaptchaDetector.kt — needs additional context checks`

#### AI Suggested Improvement

- Add context heuristics: check if the image is inside a form, near an input field, or part of an interactive widget
- Check image dimensions — real CAPTCHA images are typically small (200-400px); article illustrations are often larger
- Check if the page is a known documentation/wiki site — could use domain allowlist/blocklist
- Lower confidence for pure img-src matches without form/interaction context

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Plugin installation workflow is confusing and error-prone

**Severity:** Medium
**Category:** UX

#### Reproduction

1. Copy plugin JAR to runtime plugins/ directory. 2. Start server. 3. `./b4w.sh plugin list` shows 'inactive (restart required)'. 4. Restart server. 5. Still shows 'inactive (restart required)'. 6. Try `./b4w.sh plugin install` → 409 Conflict.

#### Expected Behavior

Clear, working plugin installation flow: install → restart → active. Or install → active immediately (hot deploy).

#### Actual Behavior

Plugin shows as 'inactive' after multiple restarts. The `plugin install` CLI command returns HTTP 409 Conflict when the JAR already exists in the plugins directory. No `--replace` flag is exposed in the CLI. The only documented way to resolve 'inactive' status is unclear.

#### Root Cause Analysis

The 'inactive' status logic in PluginService/PluginController is unclear. The plugin was detected by PluginClasspathEnhancer and PluginManager, but shows as inactive in plugin list. The 409 Conflict on reinstall suggests the API doesn't support reinstall/replace of existing plugins.

#### Code Pointer

`browser4-boot/src/main/kotlin/ai/platon/browser4/boot/plugin/PluginService.kt:install() — returns 409 for existing files. cli/browser4-cli/src/main.rs:handle_plugin_install() — has `replace` param in code but not exposed as CLI flag.`

#### AI Suggested Improvement

- Add --replace flag to `plugin install` CLI command
- Clarify 'inactive' status: what does it mean and how to fix it?
- Add `plugin activate <name>` command for hot-activation
- Document the exact plugin installation workflow step by step

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Dynamic plugin command only supports one tool per domain (first alphabetical match)

**Severity:** Medium
**Category:** Product

#### Reproduction

When a plugin registers multiple tools in the same domain (e.g., captcha: detect, solve, solveImage, getBalance), `plugin-captcha` always invokes the first alphabetically matching tool (captcha_detect). There is no way to invoke captcha_solve or captcha_getBalance from the CLI.

#### Expected Behavior

Should be able to select which plugin tool to invoke, e.g., `plugin-captcha detect`, `plugin-captcha solve`, `plugin-captcha getBalance`.

#### Actual Behavior

handle_dynamic_plugin_command() filters tools by domain prefix and always uses `matching[0]` (the first match). There is no mechanism to pass a method name.

#### Root Cause Analysis

The dynamic plugin command was designed with only one tool per domain in mind. handle_dynamic_plugin_command() doesn't parse a method name from the remaining CLI arguments.

#### Code Pointer

`cli/browser4-cli/src/main.rs:11540-11541 — `let tool_name = matching[0].to_string();` should select based on a method argument. main.rs:11543-11550 — remaining args only parse --key value pairs, not positional method names.`

#### AI Suggested Improvement

- Parse the first positional argument after `plugin-<domain>` as the method name
- Filter matching tools by both domain AND method: `{domain}_{method}`
- If no method specified and only one tool matches, use it; if multiple match, list them
- Expose `plugin-<domain> <method>` in the CLI help

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Server starts from stale installed runtime, not locally-built JAR

**Severity:** Low
**Category:** Reliability

#### Reproduction

Run `./b4w.ps1 status`. CLI version is 4.12.1 but installed backend is v4.11.15. The global `ensure_server_running()` uses find_or_install_runtime() which prefers the installed bundle over the local build.

#### Expected Behavior

In dev mode, the CLI should auto-start the locally-built backend JAR (browser4-rest/target/browser4-rest-4.12.1-SNAPSHOT.jar) rather than the globally installed v4.11.15 bundle.

#### Actual Behavior

On first start, the server ran v4.11.15 from ~/.local/share/browser4/runtime/. After stopping and restarting in the repo directory, it found a local runtime bundle from browser4-apps/browser4-bundle/target/ (v4.12.1-SNAPSHOT). The behavior depends on CWD and whether a local bundle exists.

#### Root Cause Analysis

resolve_server_launch_spec() searches for the runtime bundle in multiple locations. The global installed runtime takes precedence in some code paths. The browser4-rest JAR is not directly used unless running via `mvn spring-boot:run`.

#### Code Pointer

`cli/browser4-cli/src/daemon.rs:resolve_server_launch_spec() and find_or_install_runtime()`

#### AI Suggested Improvement

- In dev mode (when running from repo root), prioritize the locally-built JAR over the installed bundle
- Document the expected server startup behavior in SKILL.md or CLAUDE.md
- Add a `--dev` flag to explicitly request local JAR

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: No --replace flag exposed in plugin install CLI

**Severity:** Low
**Category:** UX

#### Reproduction

Run `./b4w.sh plugin install` with no flags. There is no --replace option shown in help.

#### Expected Behavior

The `plugin install` command should expose a --replace flag for reinstalling/upgrading plugins.

#### Actual Behavior

The handle_plugin_install function in main.rs reads a `replace` parameter from tool_params (line 11396-11399), but no CLI flag exposes it. The user has no way to pass replace=true from the command line.

#### Root Cause Analysis

The replace parameter exists in the code but wasn't wired to a CLI flag (--replace or --force).

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_plugin_install() line 11396 — replace param read but not mapped to CLI flag. cli/browser4-cli/src/commands.rs — plugin-install command definition missing --replace flag.`

#### AI Suggested Improvement

- Add --replace / --force flag to the plugin-install command definition in commands.rs
- Map it to the replace parameter in handle_plugin_install()

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — CAPTCHA detection was performed via JavaScript eval workaround instead of the intended captcha.detect tool. Three CAPTCHA types were successfully detected; false positive test and solver balance check completed. The plugin's core tools were never functional.

**Success Rate:** 60% — 4 of 7 test objectives completed with workarounds; 3 could not be completed as intended (plugin tools unavailable, solver balance unavailable)

**Issues Found:** 8

**Major Blockers:** CAPTCHA plugin @Bean methods not processed when loaded via PluginClasspathEnhancer — captchaToolExecutor never created. No CAPTCHA tools registered in CustomToolRegistry. This is a fundamental plugin lifecycle issue that prevents ALL plugin tool functionality.

**Most Confusing Aspects:** 1. The 'plugin-<domain>' invocation syntax is not mentioned in help. 2. The plugin shows as 'inactive (restart required)' after restart with no clear resolution path. 3. b4w.ps1 silently drops -v and -i flags due to PowerShell parameter binding — commands fail with no error. 4. The banner saying 'It is strongly recommended to launch pwsh...' on every b4w.sh invocation creates uncertainty about which wrapper to use.

**Most Valuable Improvements:** 1. Fix plugin @Bean method processing so plugin tools actually work. 2. Add `plugin-<domain> <method>` syntax for multi-tool plugins. 3. List plugin tools in help output. 4. Add --replace to `plugin install`. 5. Fix b4w.ps1 flag interception or prominently document b4w.sh as the Linux wrapper. 6. Make the 'inactive (restart required)' status actionable.

**Usability Rating:** 3/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: CAPTCHA plugin @Bean methods not processed — tools unavailable

1. Copy browser4-captcha JAR to runtime plugins/ directory. 2. Start server. 3. Run `./b4w.sh plugin-captcha`. 4. Check /actuator/beans — only CaptchaAutoConfiguration exists, none of its @Bean methods (captchaConfig, captchaDetector, captchaSolver, captchaToolExecutor) are created.

#### Issue 2: Plugin tools not discoverable via CLI help or command completion

Run `./b4w.sh help`. Search for 'captcha', 'detect', or 'solve' — no mention of CAPTCHA tools anywhere in the help output.

#### Issue 3: b4w.ps1 PowerShell flag interception breaks short flags (-v, -i)

Run `./b4w.ps1 snapshot -v 0 --stdout`. The -v flag is consumed by PowerShell as -Verbose, and the CLI receives 'snapshot 0 --stdout' instead of 'snapshot -v 0 --stdout'.

#### Issue 4: Image CAPTCHA detector produces false positives on educational content

Navigate to https://en.wikipedia.org/wiki/CAPTCHA and run CAPTCHA detection. The article contains an illustrative CAPTCHA image (Modern-captcha.jpg) which is detected as isPresent=true with type IMAGE.

#### Issue 5: Plugin installation workflow is confusing and error-prone

1. Copy plugin JAR to runtime plugins/ directory. 2. Start server. 3. `./b4w.sh plugin list` shows 'inactive (restart required)'. 4. Restart server. 5. Still shows 'inactive (restart required)'. 6. Try `./b4w.sh plugin install` → 409 Conflict.

#### Issue 6: Dynamic plugin command only supports one tool per domain (first alphabetical match)

When a plugin registers multiple tools in the same domain (e.g., captcha: detect, solve, solveImage, getBalance), `plugin-captcha` always invokes the first alphabetically matching tool (captcha_detect). There is no way to invoke captcha_solve or captcha_getBalance from the CLI.

#### Issue 7: Server starts from stale installed runtime, not locally-built JAR

Run `./b4w.ps1 status`. CLI version is 4.12.1 but installed backend is v4.11.15. The global `ensure_server_running()` uses find_or_install_runtime() which prefers the installed bundle over the local build.

#### Issue 8: No --replace flag exposed in plugin install CLI

Run `./b4w.sh plugin install` with no flags. There is no --replace option shown in help.

