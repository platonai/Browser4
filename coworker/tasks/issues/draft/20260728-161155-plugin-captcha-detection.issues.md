# Issues: plugin-captcha-detection

> **Source:** `20260728-161155-plugin-captcha-detection.full.md` | **Date:** 20260728-161155 | **Mode:** dev

## Scenario Background

### Task

### CAPTCHA Detection Results

| # | Page | CAPTCHA Type | Detected? | Details |
|---|------|-------------|-----------|---------|
| 1 | `google.com/recaptcha/api2/demo` | RECAPTCHA_V2 | ✅ Yes (via `eval`) | Site key: `6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-`, confidence: 0.95 |
| 2 | `accounts.hcaptcha.com/demo` | HCAPTCHA | ✅ Yes (via `eval`) | Site key: `a5f74b19-9e45-40e0-b45d-47ff91b7a6c2`, confidence: 0.95 |
| 3 | `demo.turnstile.workers.dev` | TURNSTILE | ✅ Yes (via `eval`) | Site key: `1x00000000000000000000AA` (test key), confidence: 0.95 |
| 4 | `en.wikipedia.org/wiki/CAPTCHA` | (none) | ✅ Correctly negative | All 3 detectors returned `isPresent: false` — zero false positives |
| 5 | `google.com/recaptcha/api2/demo` | RECAPTCHA_V2 | Balance: `$0.00` | No CAPTCHA solving service configured |

### Critical Finding: `captcha_detect` Tool Broken
The `captcha_detect` tool (called via `plugin-captcha` CLI command) returns **false negatives** — `isPresent: false` on the reCAPTCHA demo page where the widget is clearly present. Root cause: `JsUtils.toCDPCompatibleExpression()` corrupts already-invoked IIFEs (`(() => { ... })()`) by double-wrapping them, causing `TypeError: (intermediate value)(...) is not a function`. I worked around this by running the detection JS directly via `eval --file`. All three CAPTCHA types were correctly detected; no false positives occurred on the control page.

### Execution Context

**Key Commands:**

**Key decisions**: Used `eval --file` to work around the broken `captcha_detect` tool. Used direct HTTP calls for `captcha_getBalance` since `plugin-captcha` CLI only dispatches to `captcha_detect` (first alphabetic match). Wrote detection JS scripts to `.test-sessions/` for clean `--file` invocation.

```json
{
  "issues": [
    {
      "title": "captcha_detect returns false negatives — IIFE double-wrapping breaks detection JavaScript",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "1. Install captcha plugin and restart server\n2. Open session: ./b4w.ps1 open\n3. Navigate: ./b4w.ps1 goto \"https://www.google.com/recaptcha/api2/demo\"\n4. Run: ./b4w.ps1 plugin-captcha --json\n5. Observe isPresent=false",
      "expected": "captcha_detect returns isPresent=true, captchaType=RECAPTCHA_V2, siteKey=6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-",
      "actual": "Returns isPresent=false, captchaType=UNKNOWN. Server log shows: TypeError: (intermediate value)(...) is not a function. The detection JS (already an IIFE: (() => {...})()) is corrupted by JsUtils.toCDPCompatibleExpression() which wraps it again, so the first invocation's JSON string result gets called as a function.",
      "rootCause": "JsUtils.toCDPCompatibleExpression() in ai.platon.pulsar.common.js.JsUtils transforms the CaptchaSolveScripts detection JavaScript (which are already-invoked IIFEs like `(() => { ... })()`) into double-wrapped form `((() => { ... })())()`. The first invocation returns a JSON string, then attempting to call that string as a function produces the TypeError. Exception is silently caught by ChainedCaptchaDetector (catch-all `catch (_: Exception)`), returning NOT_PRESENT with no error indication.",
      "codePointer": "browser4-core/browser4-browser/src/main/kotlin/ai/platon/browser4/chrome/protocol/JsHandler.kt:evaluateValueDetail() — calls JsUtils.toCDPCompatibleExpression() which corrupts already-invoked IIFEs. Fix should be in JsUtils.toCDPCompatibleExpression() to detect and preserve already-invoked function expressions.",
      "suggestion": "- In JsUtils.toCDPCompatibleExpression(), detect already-invoked IIFEs (patterns like `(function...{})(...)` or `(()=>{...})()`) and pass them through unchanged\n- Add a unit test: `toCdpCompatibleExpressionDoesNotDoubleWrapAlreadyInvokedArrowIIFE` with input `(() => { return 3 })()` expecting unchanged output\n- In ChainedCaptchaDetector, log exceptions at WARN level instead of silently swallowing them (currently uses `catch (_: Exception) {}` with no logging)\n- In RecaptchaDetector, change `logger.trace(...)` to `logger.warn(...)` so detection failures are visible in standard logs"
    },
    {
      "title": "plugin-captcha CLI cannot select specific captcha methods — always calls captcha_detect",
      "severity": "High",
      "category": "Product",
      "reproduction": "Run `./b4w.ps1 plugin-captcha` — always dispatches to captcha_detect (first alphabetic match). Try to call captcha_getBalance or captcha_solve — no way to specify which method.",
      "expected": "CLI should support method selection: `plugin-captcha detect`, `plugin-captcha getBalance`, `plugin-captcha solve ...`",
      "actual": "handle_dynamic_plugin_command() filters tools by domain prefix (`captcha_`), picks matching[0] (first alphabetical match), and ignores all other methods. Only captcha_detect is reachable.",
      "rootCause": "cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() line 11993-12013: filters tools by `${domain}_` prefix, then takes only the first match with `matching[0]`. No mechanism to pass a method/function name to select among multiple tools in the same domain.",
      "codePointer": "cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() starting at line 11944. The method selection logic needs to parse a subcommand from remaining args and match against tool suffixes.",
      "suggestion": "- Accept subcommand syntax: `plugin-captcha detect`, `plugin-captcha solve`, `plugin-captcha getBalance`\n- Parse the first positional arg after `plugin-<domain>` as the method name\n- Match against tool names as `{domain}_{method}` where method is snake_cased from the subcommand\n- Alternatively, expose all plugin tools as top-level commands (e.g., `captcha-detect`, `captcha-get-balance`) via dynamic registration in commands_map()"
    },
    {
      "title": "Plugin remains inactive after server restart — confusing status message",
      "severity": "High",
      "category": "UX",
      "reproduction": "1. Install plugin: ./b4w.ps1 plugin install <jar>\n2. Stop server: ./b4w.ps1 stop\n3. Run any command to auto-start server\n4. Check: ./b4w.ps1 plugin list\n5. Plugin still shows \"inactive (restart required)\"",
      "expected": "After server restart, plugin should show as \"active\"",
      "actual": "Plugin list shows \"inactive (restart required)\" even after the server process was fully stopped and restarted. Despite the misleading status, the plugin IS actually loaded (verified via server logs: \"CAPTCHA detection chain initialized with 4 detectors\").",
      "rootCause": "The plugin state is likely cached/persisted separately from the JVM process. The status check may read a stale state file rather than querying the live server's plugin registry. The `plugin list` command may be reading from a local state file rather than querying the server for active plugin status.",
      "codePointer": "Investigate how plugin status is determined — likely in the plugin list handler in cli/browser4-cli/src/main.rs or the backend plugin management code. The status check should query the live server rather than a cached state file.",
      "suggestion": "- Query live server for plugin status instead of reading from a cached state file\n- After server restart, refresh the plugin state automatically\n- The `plugin list` output should distinguish \"installed on disk\" vs \"active in running server\""
    },
    {
      "title": "plugin-captcha output is raw Java toString — not user-readable",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run `./b4w.ps1 plugin-captcha --json` — output is Java class descriptor wrapped in JSON: {\"type\":\"ai.platon.pulsar.captcha.CaptchaDetectionResult\",\"description\":\"CaptchaDetectionResult(isPresent=false,...)\"}",
      "expected": "Structured JSON with clear field names: {\"isPresent\": false, \"captchaType\": \"UNKNOWN\", \"siteKey\": null, \"confidence\": 0.0}",
      "actual": "Output wraps the Java object's toString() in a JSON envelope with type and description fields. The actual detection fields are flattened into a single description string.",
      "rootCause": "The MCP tool response serialization uses Jackson's default toString() for complex objects returned from tool executors rather than proper JSON serialization. CaptchaDetectionResult likely lacks @JsonSerialize or the MCP response handler doesn't use Jackson serialization for non-primitive return types.",
      "codePointer": "browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/ — MCP tool response serialization. Also browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/CaptchaDetectionResult.kt should ensure proper Jackson serialization.",
      "suggestion": "- Add @JsonSerialize or ensure CaptchaDetectionResult is serialized via Jackson rather than toString()\n- The MCP response handler should detect complex return types and serialize them as structured JSON\n- Output should match the field names from CaptchaDetectionResult: isPresent, captchaType, siteKey, confidence, metadata"
    },
    {
      "title": "No captcha plugin documentation in SKILL.md or built-in help",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "1. Run ./b4w.ps1 help — no mention of captcha, plugin-captcha, or captcha.detect\n2. Read skills/browser4-cli/SKILL.md — no reference to captcha plugin or its tools\n3. Search for usage examples — none found in documentation",
      "expected": "SKILL.md should document how to use plugin tools (plugin-<name> syntax), and help output should list available plugin commands or at least mention the plugin system.",
      "actual": "No mention of the plugin command system in the help output's command list. No documentation of captcha.detect, captcha.solve, captcha.getBalance, or how to invoke them. The only way to discover the plugin-captcha command is to know about the plugin-<name> convention (not documented in help) and know that a captcha plugin is installed.",
      "rootCause": "The help system lists only built-in commands from commands_map(). Dynamic plugin commands (plugin-<name>) are not integrated into the help output. SKILL.md covers built-in features but doesn't explain the plugin command dispatch system.",
      "codePointer": "cli/browser4-cli/src/main.rs:print_help() — should list available plugin domains. skills/browser4-cli/SKILL.md — should add a plugin section.",
      "suggestion": "- Add a \"Plugins\" section to SKILL.md explaining the plugin-<domain> command syntax\n- Add a `--help plugins` category that lists available plugin domains and their tools\n- After plugin install, print usage examples: \"Try: browser4-cli plugin-captcha to detect CAPTCHAs\"\n- Include captcha plugin commands in the main help output when the plugin is installed"
    },
    {
      "title": "Silent exception swallowing in ChainedCaptchaDetector hides failures",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "1. Call captcha_detect on a page with a CAPTCHA\n2. The detector throws an internal exception\n3. No error is reported — returns NOT_PRESENT silently",
      "expected": "When a detector throws an exception, it should be logged at WARN level. When ALL detectors fail, the chain should report the failures.",
      "actual": "ChainedCaptchaDetector uses `catch (_: Exception) {}` with no logging. Individual detectors use `logger.trace(...)` which is not visible at default log levels. All failures are completely silent.",
      "rootCause": "ChainedCaptchaDetector.detect() line 41: `catch (_: Exception) { // Continue to next detector }` — no logging. RecaptchaDetector.detect() line 67: `logger.trace(\"reCAPTCHA detection error: {}\", e.message)` — TRACE level is not visible by default.",
      "codePointer": "browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/detection/ChainedCaptchaDetector.kt:41 and RecaptchaDetector.kt:67",
      "suggestion": "- In ChainedCaptchaDetector, log each detector failure at DEBUG level: `logger.debug(\"Detector {} failed: {}\", detector::class.simpleName, e.message)`\n- In individual detectors, change `logger.trace` to `logger.warn` for detection errors\n- If ALL detectors fail, log a collective WARN summarizing the failures"
    },
    {
      "title": "Confidence values are hardcoded rather than computed from signal strength",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "Check capture code: all three detectors (RecaptchaDetector, HCaptchaDetector, TurnstileDetector) use `confidence = 0.95f` regardless of how the CAPTCHA was detected (API object vs iframe vs div).",
      "expected": "Confidence should reflect signal strength: e.g., finding the API object + iframe + .g-recaptcha div with siteKey → 0.95; finding only an iframe → 0.70; finding only a script reference → 0.50.",
      "actual": "All detections return hardcoded confidence 0.95. The developer cannot distinguish between strong detections (multiple signals) and weak detections (single signal, ambiguous).",
      "rootCause": "Detectors set `confidence = 0.95f` directly in the CaptchaDetectionResult.found() call with no signal-weighting logic. The detection JS returns multiple signals (hasGrecaptcha, iframe count, div count, siteKey presence) but the result doesn't compute a confidence score from them.",
      "codePointer": "browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/detection/RecaptchaDetector.kt:58-64, HCaptchaDetector.kt:51-56, TurnstileDetector.kt:56-63",
      "suggestion": "- Compute confidence from weighted signals: API object present (+0.4), iframe found (+0.3), div with sitekey (+0.2), siteKey extracted (+0.1)\n- Return the computed confidence in metadata along with the hardcoded value\n- Expose signal details (which signals fired) in the detection result for debugging"
    },
    {
      "title": "Cannot discover captcha tools via CLI without knowing plugin-<name> convention",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "As a new user, try to find how to use the captcha plugin:\n1. `./b4w.ps1 help` — no plugin commands listed\n2. `./b4w.ps1 help --help plugins` — no such category\n3. `./b4w.ps1 captcha` — unknown command\n4. `./b4w.ps1 plugin list` — shows plugin is installed but not how to use it",
      "expected": "The help system should guide users to the plugin-<name> invocation pattern. `plugin list` output should include usage hints. `plugin info <name>` should show available methods.",
      "actual": "The only way to discover `plugin-captcha` is to know about the `plugin-<name>` convention, which is not documented in help, not hinted in `plugin list` output, and not mentioned in SKILL.md.",
      "rootCause": "The plugin-<name> command dispatch is implemented but not surfaced to users. plugin list shows 'browser4-captcha-4.12.1-SNAPSHOT.jar v1.0.0' but doesn't say 'Use plugin-captcha to access its tools'.",
      "codePointer": "cli/browser4-cli/src/main.rs: the plugin list handler should suggest the plugin-<name> command format. The print_help() function should include a plugins section.",
      "suggestion": "- In `plugin list` output, add a column or footer: \"Usage: browser4-cli plugin-captcha\"\n- Add `plugin info <name>` command that lists available methods/tools\n- Include plugin domains in the bottom of `help` output: \"Available plugin commands: plugin-captcha\"\n- After `plugin install`, print: \"Installed! Use: browser4-cli plugin-<name>\""
    },
    {
      "title": "Short flags warning displayed on every command — noisy and confusing",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any command with short flags: `./b4w.ps1 snapshot -v 0 --stdout` — warning about PowerShell parameter binding appears on stderr every time.",
      "expected": "Warning should appear once per session or only when the issue actually manifests (flags are being intercepted).",
      "actual": "Every command with `-v`, `-i`, or other short flags prints a warning on stderr. The warning is about PowerShell possibly intercepting flags, but in this context (b4w.sh on Linux) it's irrelevant and confusing.",
      "rootCause": "The short-flag detection warning fires unconditionally whenever short flags are detected in args, regardless of whether the current shell environment actually has the PowerShell parameter binding issue.",
      "codePointer": "cli/browser4-cli/src/main.rs or b4w.ps1 — the flag warning logic should be shell-context-aware.",
      "suggestion": "- Only show the short-flags warning when running under an actual PowerShell environment (check $PSVersionTable or parent process)\n- Show the warning once per session, not on every invocation\n- Use `--viewport` in examples to avoid triggering the warning"
    },
    {
      "title": "CLI rebuilds from source on every invocation — adds ~2s latency",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any ./b4w.ps1 command. Observe \"Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.4Xs\" before the actual command output on every single invocation.",
      "expected": "First invocation builds, subsequent invocations use cached binary. Or at least an option to skip rebuild when no source changes.",
      "actual": "Every invocation rebuilds the Rust binary even when no source files have changed. This adds 0.4-0.5s overhead per command. For the captcha detection task with 15+ commands, this added ~7.5s of unnecessary build time.",
      "rootCause": "The b4w.ps1 wrapper script uses `cargo run` which checks timestamps and recompiles. The build system may not be caching the binary effectively, or the wrapper always triggers a rebuild check.",
      "codePointer": "b4w.ps1 — the cargo invocation could use `cargo build --bin browser4-cli` separately and then run the binary directly, or check if the binary is up-to-date before rebuilding.",
      "suggestion": "- Modify b4w.ps1 to check if the binary is newer than all source files before rebuilding\n- Use `cargo build` + direct binary invocation pattern instead of `cargo run`\n- Consider a watch mode or daemon that keeps the binary compiled"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — All CAPTCHA types were detected correctly using eval --file workaround, but the captcha_detect tool itself is broken (returns false negatives). The balance check worked via direct API. Task objectives were met through workarounds, revealing significant usability and reliability issues.",
    "successRate": "75% — 3/4 detection targets succeeded via primary tool (eval workaround); the captcha_detect tool succeeded 0% of the time. Balance check and false-positive testing both passed.",
    "issuesFound": 10,
    "majorBlockers": "The captcha_detect tool itself is non-functional due to an IIFE double-wrapping bug in JsUtils.toCDPCompatibleExpression(). This makes the primary plugin tool unusable without the eval workaround. Additionally, the plugin-captcha CLI mechanism cannot call any captcha method other than detect (no method selection), preventing access to captcha.solve and requiring direct HTTP calls for captcha.getBalance.",
    "mostConfusingAspects": "1) The plugin-captcha invocation convention (plugin-<name>) is undiscoverable — not in help, not in plugin list output, not in SKILL.md. 2) The plugin shows 'inactive (restart required)' even after server restart, but actually IS loaded. 3) captcha_detect silently returns isPresent=false with no error indication — you only discover the bug by cross-checking with eval or checking server logs. 4) The raw Java toString output format requires parsing nested text to extract detection results.",
    "mostValuableImprovements": "1) Fix the IIFE double-wrapping bug in JsUtils.toCDPCompatibleExpression() — this makes the captcha_detect tool work. 2) Add method selection to plugin-captcha (e.g., plugin-captcha detect/solve/getBalance). 3) Document the plugin system in SKILL.md and help output. 4) Make detection errors visible (WARN-level logging, not silent catch). 5) Return structured JSON from plugin tools instead of Java toString().",
    "usabilityRating": 3
  }
}
```

---

## Issues Found (10 issues)

### Issue 1: captcha_detect returns false negatives — IIFE double-wrapping breaks detection JavaScript

**Severity:** Critical
**Category:** Product

#### Reproduction

1. Install captcha plugin and restart server
2. Open session: ./b4w.ps1 open
3. Navigate: ./b4w.ps1 goto "https://www.google.com/recaptcha/api2/demo"
4. Run: ./b4w.ps1 plugin-captcha --json
5. Observe isPresent=false

#### Expected Behavior

captcha_detect returns isPresent=true, captchaType=RECAPTCHA_V2, siteKey=6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-

#### Actual Behavior

Returns isPresent=false, captchaType=UNKNOWN. Server log shows: TypeError: (intermediate value)(...) is not a function. The detection JS (already an IIFE: (() => {...})()) is corrupted by JsUtils.toCDPCompatibleExpression() which wraps it again, so the first invocation's JSON string result gets called as a function.

#### Root Cause Analysis

JsUtils.toCDPCompatibleExpression() in ai.platon.pulsar.common.js.JsUtils transforms the CaptchaSolveScripts detection JavaScript (which are already-invoked IIFEs like `(() => { ... })()`) into double-wrapped form `((() => { ... })())()`. The first invocation returns a JSON string, then attempting to call that string as a function produces the TypeError. Exception is silently caught by ChainedCaptchaDetector (catch-all `catch (_: Exception)`), returning NOT_PRESENT with no error indication.

#### Code Pointer

`browser4-core/browser4-browser/src/main/kotlin/ai/platon/browser4/chrome/protocol/JsHandler.kt:evaluateValueDetail() — calls JsUtils.toCDPCompatibleExpression() which corrupts already-invoked IIFEs. Fix should be in JsUtils.toCDPCompatibleExpression() to detect and preserve already-invoked function expressions.`

#### AI Suggested Improvement

- In JsUtils.toCDPCompatibleExpression(), detect already-invoked IIFEs (patterns like `(function...{})(...)` or `(()=>{...})()`) and pass them through unchanged
- Add a unit test: `toCdpCompatibleExpressionDoesNotDoubleWrapAlreadyInvokedArrowIIFE` with input `(() => { return 3 })()` expecting unchanged output
- In ChainedCaptchaDetector, log exceptions at WARN level instead of silently swallowing them (currently uses `catch (_: Exception) {}` with no logging)
- In RecaptchaDetector, change `logger.trace(...)` to `logger.warn(...)` so detection failures are visible in standard logs

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: plugin-captcha CLI cannot select specific captcha methods — always calls captcha_detect

**Severity:** High
**Category:** Product

#### Reproduction

Run `./b4w.ps1 plugin-captcha` — always dispatches to captcha_detect (first alphabetic match). Try to call captcha_getBalance or captcha_solve — no way to specify which method.

#### Expected Behavior

CLI should support method selection: `plugin-captcha detect`, `plugin-captcha getBalance`, `plugin-captcha solve ...`

#### Actual Behavior

handle_dynamic_plugin_command() filters tools by domain prefix (`captcha_`), picks matching[0] (first alphabetical match), and ignores all other methods. Only captcha_detect is reachable.

#### Root Cause Analysis

cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() line 11993-12013: filters tools by `${domain}_` prefix, then takes only the first match with `matching[0]`. No mechanism to pass a method/function name to select among multiple tools in the same domain.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_dynamic_plugin_command() starting at line 11944. The method selection logic needs to parse a subcommand from remaining args and match against tool suffixes.`

#### AI Suggested Improvement

- Accept subcommand syntax: `plugin-captcha detect`, `plugin-captcha solve`, `plugin-captcha getBalance`
- Parse the first positional arg after `plugin-<domain>` as the method name
- Match against tool names as `{domain}_{method}` where method is snake_cased from the subcommand
- Alternatively, expose all plugin tools as top-level commands (e.g., `captcha-detect`, `captcha-get-balance`) via dynamic registration in commands_map()

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Plugin remains inactive after server restart — confusing status message

**Severity:** High
**Category:** UX

#### Reproduction

1. Install plugin: ./b4w.ps1 plugin install <jar>
2. Stop server: ./b4w.ps1 stop
3. Run any command to auto-start server
4. Check: ./b4w.ps1 plugin list
5. Plugin still shows "inactive (restart required)"

#### Expected Behavior

After server restart, plugin should show as "active"

#### Actual Behavior

Plugin list shows "inactive (restart required)" even after the server process was fully stopped and restarted. Despite the misleading status, the plugin IS actually loaded (verified via server logs: "CAPTCHA detection chain initialized with 4 detectors").

#### Root Cause Analysis

The plugin state is likely cached/persisted separately from the JVM process. The status check may read a stale state file rather than querying the live server's plugin registry. The `plugin list` command may be reading from a local state file rather than querying the server for active plugin status.

#### Code Pointer

`Investigate how plugin status is determined — likely in the plugin list handler in cli/browser4-cli/src/main.rs or the backend plugin management code. The status check should query the live server rather than a cached state file.`

#### AI Suggested Improvement

- Query live server for plugin status instead of reading from a cached state file
- After server restart, refresh the plugin state automatically
- The `plugin list` output should distinguish "installed on disk" vs "active in running server"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: plugin-captcha output is raw Java toString — not user-readable

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `./b4w.ps1 plugin-captcha --json` — output is Java class descriptor wrapped in JSON: {"type":"ai.platon.pulsar.captcha.CaptchaDetectionResult","description":"CaptchaDetectionResult(isPresent=false,...)"}

#### Expected Behavior

Structured JSON with clear field names: {"isPresent": false, "captchaType": "UNKNOWN", "siteKey": null, "confidence": 0.0}

#### Actual Behavior

Output wraps the Java object's toString() in a JSON envelope with type and description fields. The actual detection fields are flattened into a single description string.

#### Root Cause Analysis

The MCP tool response serialization uses Jackson's default toString() for complex objects returned from tool executors rather than proper JSON serialization. CaptchaDetectionResult likely lacks @JsonSerialize or the MCP response handler doesn't use Jackson serialization for non-primitive return types.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/ — MCP tool response serialization. Also browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/CaptchaDetectionResult.kt should ensure proper Jackson serialization.`

#### AI Suggested Improvement

- Add @JsonSerialize or ensure CaptchaDetectionResult is serialized via Jackson rather than toString()
- The MCP response handler should detect complex return types and serialize them as structured JSON
- Output should match the field names from CaptchaDetectionResult: isPresent, captchaType, siteKey, confidence, metadata

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: No captcha plugin documentation in SKILL.md or built-in help

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. Run ./b4w.ps1 help — no mention of captcha, plugin-captcha, or captcha.detect
2. Read skills/browser4-cli/SKILL.md — no reference to captcha plugin or its tools
3. Search for usage examples — none found in documentation

#### Expected Behavior

SKILL.md should document how to use plugin tools (plugin-<name> syntax), and help output should list available plugin commands or at least mention the plugin system.

#### Actual Behavior

No mention of the plugin command system in the help output's command list. No documentation of captcha.detect, captcha.solve, captcha.getBalance, or how to invoke them. The only way to discover the plugin-captcha command is to know about the plugin-<name> convention (not documented in help) and know that a captcha plugin is installed.

#### Root Cause Analysis

The help system lists only built-in commands from commands_map(). Dynamic plugin commands (plugin-<name>) are not integrated into the help output. SKILL.md covers built-in features but doesn't explain the plugin command dispatch system.

#### Code Pointer

`cli/browser4-cli/src/main.rs:print_help() — should list available plugin domains. skills/browser4-cli/SKILL.md — should add a plugin section.`

#### AI Suggested Improvement

- Add a "Plugins" section to SKILL.md explaining the plugin-<domain> command syntax
- Add a `--help plugins` category that lists available plugin domains and their tools
- After plugin install, print usage examples: "Try: browser4-cli plugin-captcha to detect CAPTCHAs"
- Include captcha plugin commands in the main help output when the plugin is installed

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Silent exception swallowing in ChainedCaptchaDetector hides failures

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1. Call captcha_detect on a page with a CAPTCHA
2. The detector throws an internal exception
3. No error is reported — returns NOT_PRESENT silently

#### Expected Behavior

When a detector throws an exception, it should be logged at WARN level. When ALL detectors fail, the chain should report the failures.

#### Actual Behavior

ChainedCaptchaDetector uses `catch (_: Exception) {}` with no logging. Individual detectors use `logger.trace(...)` which is not visible at default log levels. All failures are completely silent.

#### Root Cause Analysis

ChainedCaptchaDetector.detect() line 41: `catch (_: Exception) { // Continue to next detector }` — no logging. RecaptchaDetector.detect() line 67: `logger.trace("reCAPTCHA detection error: {}", e.message)` — TRACE level is not visible by default.

#### Code Pointer

`browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/detection/ChainedCaptchaDetector.kt:41 and RecaptchaDetector.kt:67`

#### AI Suggested Improvement

- In ChainedCaptchaDetector, log each detector failure at DEBUG level: `logger.debug("Detector {} failed: {}", detector::class.simpleName, e.message)`
- In individual detectors, change `logger.trace` to `logger.warn` for detection errors
- If ALL detectors fail, log a collective WARN summarizing the failures

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Confidence values are hardcoded rather than computed from signal strength

**Severity:** Medium
**Category:** Product

#### Reproduction

Check capture code: all three detectors (RecaptchaDetector, HCaptchaDetector, TurnstileDetector) use `confidence = 0.95f` regardless of how the CAPTCHA was detected (API object vs iframe vs div).

#### Expected Behavior

Confidence should reflect signal strength: e.g., finding the API object + iframe + .g-recaptcha div with siteKey → 0.95; finding only an iframe → 0.70; finding only a script reference → 0.50.

#### Actual Behavior

All detections return hardcoded confidence 0.95. The developer cannot distinguish between strong detections (multiple signals) and weak detections (single signal, ambiguous).

#### Root Cause Analysis

Detectors set `confidence = 0.95f` directly in the CaptchaDetectionResult.found() call with no signal-weighting logic. The detection JS returns multiple signals (hasGrecaptcha, iframe count, div count, siteKey presence) but the result doesn't compute a confidence score from them.

#### Code Pointer

`browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/detection/RecaptchaDetector.kt:58-64, HCaptchaDetector.kt:51-56, TurnstileDetector.kt:56-63`

#### AI Suggested Improvement

- Compute confidence from weighted signals: API object present (+0.4), iframe found (+0.3), div with sitekey (+0.2), siteKey extracted (+0.1)
- Return the computed confidence in metadata along with the hardcoded value
- Expose signal details (which signals fired) in the detection result for debugging

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Cannot discover captcha tools via CLI without knowing plugin-<name> convention

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

As a new user, try to find how to use the captcha plugin:
1. `./b4w.ps1 help` — no plugin commands listed
2. `./b4w.ps1 help --help plugins` — no such category
3. `./b4w.ps1 captcha` — unknown command
4. `./b4w.ps1 plugin list` — shows plugin is installed but not how to use it

#### Expected Behavior

The help system should guide users to the plugin-<name> invocation pattern. `plugin list` output should include usage hints. `plugin info <name>` should show available methods.

#### Actual Behavior

The only way to discover `plugin-captcha` is to know about the `plugin-<name>` convention, which is not documented in help, not hinted in `plugin list` output, and not mentioned in SKILL.md.

#### Root Cause Analysis

The plugin-<name> command dispatch is implemented but not surfaced to users. plugin list shows 'browser4-captcha-4.12.1-SNAPSHOT.jar v1.0.0' but doesn't say 'Use plugin-captcha to access its tools'.

#### Code Pointer

`cli/browser4-cli/src/main.rs: the plugin list handler should suggest the plugin-<name> command format. The print_help() function should include a plugins section.`

#### AI Suggested Improvement

- In `plugin list` output, add a column or footer: "Usage: browser4-cli plugin-captcha"
- Add `plugin info <name>` command that lists available methods/tools
- Include plugin domains in the bottom of `help` output: "Available plugin commands: plugin-captcha"
- After `plugin install`, print: "Installed! Use: browser4-cli plugin-<name>"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: Short flags warning displayed on every command — noisy and confusing

**Severity:** Low
**Category:** UX

#### Reproduction

Run any command with short flags: `./b4w.ps1 snapshot -v 0 --stdout` — warning about PowerShell parameter binding appears on stderr every time.

#### Expected Behavior

Warning should appear once per session or only when the issue actually manifests (flags are being intercepted).

#### Actual Behavior

Every command with `-v`, `-i`, or other short flags prints a warning on stderr. The warning is about PowerShell possibly intercepting flags, but in this context (b4w.sh on Linux) it's irrelevant and confusing.

#### Root Cause Analysis

The short-flag detection warning fires unconditionally whenever short flags are detected in args, regardless of whether the current shell environment actually has the PowerShell parameter binding issue.

#### Code Pointer

`cli/browser4-cli/src/main.rs or b4w.ps1 — the flag warning logic should be shell-context-aware.`

#### AI Suggested Improvement

- Only show the short-flags warning when running under an actual PowerShell environment (check $PSVersionTable or parent process)
- Show the warning once per session, not on every invocation
- Use `--viewport` in examples to avoid triggering the warning

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: CLI rebuilds from source on every invocation — adds ~2s latency

**Severity:** Low
**Category:** UX

#### Reproduction

Run any ./b4w.ps1 command. Observe "Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.4Xs" before the actual command output on every single invocation.

#### Expected Behavior

First invocation builds, subsequent invocations use cached binary. Or at least an option to skip rebuild when no source changes.

#### Actual Behavior

Every invocation rebuilds the Rust binary even when no source files have changed. This adds 0.4-0.5s overhead per command. For the captcha detection task with 15+ commands, this added ~7.5s of unnecessary build time.

#### Root Cause Analysis

The b4w.ps1 wrapper script uses `cargo run` which checks timestamps and recompiles. The build system may not be caching the binary effectively, or the wrapper always triggers a rebuild check.

#### Code Pointer

`b4w.ps1 — the cargo invocation could use `cargo build --bin browser4-cli` separately and then run the binary directly, or check if the binary is up-to-date before rebuilding.`

#### AI Suggested Improvement

- Modify b4w.ps1 to check if the binary is newer than all source files before rebuilding
- Use `cargo build` + direct binary invocation pattern instead of `cargo run`
- Consider a watch mode or daemon that keeps the binary compiled

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — All CAPTCHA types were detected correctly using eval --file workaround, but the captcha_detect tool itself is broken (returns false negatives). The balance check worked via direct API. Task objectives were met through workarounds, revealing significant usability and reliability issues.

**Success Rate:** 75% — 3/4 detection targets succeeded via primary tool (eval workaround); the captcha_detect tool succeeded 0% of the time. Balance check and false-positive testing both passed.

**Issues Found:** 10

**Major Blockers:** The captcha_detect tool itself is non-functional due to an IIFE double-wrapping bug in JsUtils.toCDPCompatibleExpression(). This makes the primary plugin tool unusable without the eval workaround. Additionally, the plugin-captcha CLI mechanism cannot call any captcha method other than detect (no method selection), preventing access to captcha.solve and requiring direct HTTP calls for captcha.getBalance.

**Most Confusing Aspects:** 1) The plugin-captcha invocation convention (plugin-<name>) is undiscoverable — not in help, not in plugin list output, not in SKILL.md. 2) The plugin shows 'inactive (restart required)' even after server restart, but actually IS loaded. 3) captcha_detect silently returns isPresent=false with no error indication — you only discover the bug by cross-checking with eval or checking server logs. 4) The raw Java toString output format requires parsing nested text to extract detection results.

**Most Valuable Improvements:** 1) Fix the IIFE double-wrapping bug in JsUtils.toCDPCompatibleExpression() — this makes the captcha_detect tool work. 2) Add method selection to plugin-captcha (e.g., plugin-captcha detect/solve/getBalance). 3) Document the plugin system in SKILL.md and help output. 4) Make detection errors visible (WARN-level logging, not silent catch). 5) Return structured JSON from plugin tools instead of Java toString().

**Usability Rating:** 3/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: captcha_detect returns false negatives — IIFE double-wrapping breaks detection JavaScript

1. Install captcha plugin and restart server
2. Open session: ./b4w.ps1 open
3. Navigate: ./b4w.ps1 goto "https://www.google.com/recaptcha/api2/demo"
4. Run: ./b4w.ps1 plugin-captcha --json
5. Observe isPresent=false

#### Issue 2: plugin-captcha CLI cannot select specific captcha methods — always calls captcha_detect

Run `./b4w.ps1 plugin-captcha` — always dispatches to captcha_detect (first alphabetic match). Try to call captcha_getBalance or captcha_solve — no way to specify which method.

#### Issue 3: Plugin remains inactive after server restart — confusing status message

1. Install plugin: ./b4w.ps1 plugin install <jar>
2. Stop server: ./b4w.ps1 stop
3. Run any command to auto-start server
4. Check: ./b4w.ps1 plugin list
5. Plugin still shows "inactive (restart required)"

#### Issue 4: plugin-captcha output is raw Java toString — not user-readable

Run `./b4w.ps1 plugin-captcha --json` — output is Java class descriptor wrapped in JSON: {"type":"ai.platon.pulsar.captcha.CaptchaDetectionResult","description":"CaptchaDetectionResult(isPresent=false,...)"}

#### Issue 5: No captcha plugin documentation in SKILL.md or built-in help

1. Run ./b4w.ps1 help — no mention of captcha, plugin-captcha, or captcha.detect
2. Read skills/browser4-cli/SKILL.md — no reference to captcha plugin or its tools
3. Search for usage examples — none found in documentation

#### Issue 6: Silent exception swallowing in ChainedCaptchaDetector hides failures

1. Call captcha_detect on a page with a CAPTCHA
2. The detector throws an internal exception
3. No error is reported — returns NOT_PRESENT silently

#### Issue 7: Confidence values are hardcoded rather than computed from signal strength

Check capture code: all three detectors (RecaptchaDetector, HCaptchaDetector, TurnstileDetector) use `confidence = 0.95f` regardless of how the CAPTCHA was detected (API object vs iframe vs div).

#### Issue 8: Cannot discover captcha tools via CLI without knowing plugin-<name> convention

As a new user, try to find how to use the captcha plugin:
1. `./b4w.ps1 help` — no plugin commands listed
2. `./b4w.ps1 help --help plugins` — no such category
3. `./b4w.ps1 captcha` — unknown command
4. `./b4w.ps1 plugin list` — shows plugin is installed but not how to use it

#### Issue 9: Short flags warning displayed on every command — noisy and confusing

Run any command with short flags: `./b4w.ps1 snapshot -v 0 --stdout` — warning about PowerShell parameter binding appears on stderr every time.

#### Issue 10: CLI rebuilds from source on every invocation — adds ~2s latency

Run any ./b4w.ps1 command. Observe "Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.4Xs" before the actual command output on every single invocation.

