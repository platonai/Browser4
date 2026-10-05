# Issues: plugin-captcha-detection

> **Source:** `20261004-164400-plugin-captcha-detection.full.md` | **Date:** 20261004-164400 | **Mode:** dev

## Scenario Background

### Task

The browser4-captcha plugin was installed from the locally built source (`browser4-plugins/browser4-captcha/target/browser4-captcha-4.13.27-SNAPSHOT.jar`), loaded by the dev backend (4.13.27-SNAPSHOT), and exercised against four live pages with a dedicated session (`-s captcha-eval`).

| # | Page | `captcha.detect` result | Verified against page DOM | Verdict |
|---|------|------------------------|---------------------------|---------|
| 1 | `https://www.google.com/recaptcha/api2/demo` | `isPresent=false`, `UNKNOWN`, `siteKey=null`, `confidence=0.0` | Live reCAPTCHA v2 widget present (anchor iframe with `k=6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-`, 2 recaptcha iframes, `.g-recaptcha` div) | ❌ **False negative — product bug** |
| 2 | `https://accounts.hcaptcha.com/demo` | `isPresent=true`, `HCAPTCHA`, siteKey `a5f74b19-9e45-40e0-b45d-47ff91b7a6c2`, `confidence=0.95` | Widget iframe present | ✅ Correct |
| 3 | `https://demo.turnstile.workers.dev` | `isPresent=true`, `TURNSTILE`, siteKey `1x00000000000000000000AA`, `confidence=0.95` | `.cf-turnstile` present | ✅ Correct |
| 4 | `https://en.wikipedia.org/wiki/CAPTCHA` (negative control) | `isPresent=false`, `UNKNOWN`, `confidence=0.0` | No CAPTCHA elements | ✅ Correct — no false positive |
| 5 | Back to reCAPTCHA demo | `isPresent=false` (reproduced 3×) | same as #1 | ❌ Reproducible |

**Requested details for step 2 (reCAPTCHA):** the tool reported `isPresent=false`, `captchaType=UNKNOWN`, `siteKey=null`, `confidence=0.0`. The site key actually on the page — extracted independently via the same detection logic once the pipeline bug was bypassed — is **`6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-`** (Google's public v2 demo key). Detection returned a wrong answer, silently, with no error.

**Step 6:** `captcha_get_balance` returned **`0.0`** in 0.02–0.07 s, and `captcha_solve` returned `status=FAILED, provider=NONE, error="All 0 solver(s) failed to solve the CAPTCHA"`. **No solving service is configured** (no API keys in `application.properties`), but neither reply says so — `0.0` is indistinguishable from a genuine zero balance.

**Step 7 summary:** hCaptcha and Turnstile detected correctly (confidence 0.95 each); Google reCAPTCHA v2 was **not** detected despite the widget being live (root cause found and proven, see Issue 1/2). Detection is fast and consistent: **0.22–0.35 s per call** on all four pages (one cold hCaptcha call took 3.45 s right after navigation; repeats were ~0.24 s). No false positives on the Wikipedia article. The overall task is **partially successful** — the flagship scenario (reCAPTCHA) produced a wrong result.

### Execution Context

1. **Setup** — `pwd` (repo root ✅), `./b4w.ps1 help`, `./b4w.ps1 --help-json`, read `skills/browser4-cli/SKILL.md` fully. The captcha plugin is *not* mentioned anywhere in CLI help or SKILL.md; found it by grepping the repo (`browser4-plugins/browser4-captcha`), then read the plugin's `CaptchaToolExecutor`, detectors, and `docs/config.md` §CAPTCHA.
2. **Plugin install** — `./b4w.ps1 status` (backend up, bundle matches checkout), `./b4w.ps1 plugin list` → `No plugins installed.` Discovered `plugin install <jar>` from help; installed the local 4.13.27-SNAPSHOT jar; restarted via `./b4w.ps1 stop` (next command auto-starts the dev backend). `plugin list` then showed the plugin `loaded`.
3. **Invocation problem** — no CLI command reaches plugin tools. `help captcha` → `Unknown command: captcha`...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: captcha.detect returns isPresent=false on Google's live reCAPTCHA v2 demo (false negative)

**Severity:** High
**Category:** Product

#### Reproduction

1) ./b4w.ps1 plugin install browser4-plugins/browser4-captcha/target/browser4-captcha-4.13.27-SNAPSHOT.jar ; ./b4w.ps1 stop ; 2) ./b4w.ps1 -s captcha-eval goto "https://www.google.com/recaptcha/api2/demo" ; 3) curl -s -X POST http://localhost:18182/mcp/call-tool -H "Content-Type: application/json" -d '{"tool":"captcha_detect","arguments":{"sessionId":"<sid>"}}'

#### Expected Behavior

isPresent=true, captchaType=RECAPTCHA_V2, siteKey=6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-, confidence>0.9

#### Actual Behavior

isPresent=false, captchaType=UNKNOWN, siteKey=null, confidence=0.0 — no error, no warning; reproduced 3x on repeated navigation. The page genuinely hosts the widget (anchor iframe src contains k=6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-).

#### Root Cause Analysis

CaptchaSolveScripts.DETECT_RECAPTCHA contains the regex /recaptcha\.execute\([^,]+,\s*['"](\w+)['"]/ whose escaped '(' makes the raw '(' / ')' counts over the whole script 39/38. Every script goes through JsUtils.toCDPCompatibleExpression -> toIIFEOrNull -> isAlreadyInvokedIIFE (pulsar-common-4.11.24.jar), which counts raw parentheses only (no string/regex/comment awareness). With the count unbalanced it fails to recognize the already-invoked IIFE and wraps it again as ((<expr>))(); — calling the result value as a function. CDP raises TypeError: (intermediate value)(...) is not a function; evaluateValue returns Evaluate.result.value (null) and ignores exceptionDetails, so RecaptchaDetector's 'as? String ?: return NOT_PRESENT' turns an infrastructure failure into a silent 'no CAPTCHA'. hCaptcha/Turnstile scripts are also IIFEs but have balanced raw parens, which is why only reCAPTCHA fails.

#### Code Pointer

`browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/CaptchaSolveScripts.kt (DETECT_RECAPTCHA) and detection/RecaptchaDetector.kt:41-47; framework root cause in ai.platon.pulsar.common.js.JsUtils.isAlreadyInvokedIIFE (pulsar-common jar, not in this checkout)`

#### AI Suggested Improvement

- Rewrite DETECT_RECAPTCHA using the named-function + invocation pattern that browser4-images/ImageDetector.kt:151 and browser4-media/VideoDetector.kt:124 already use to dodge this exact pitfall
- At minimum remove unmatched raw parens from the script (e.g. use \x28 for the literal '(' in the recaptcha.execute regex); verify with ./b4w.ps1 eval --file <script> before shipping
- Make RecaptchaDetector fail loudly (log warn / throw) when the evaluation result is null or not a String, instead of silently returning NOT_PRESENT, so infra failures do not masquerade as 'no CAPTCHA'
- Add an E2E regression test against a reCAPTCHA v2 fixture asserting isPresent=true and the expected siteKey

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: eval silently returns null for valid JavaScript whose raw parenthesis counts are unbalanced

**Severity:** High
**Category:** Reliability

#### Reproduction

printf '%s' '(() => "(".length)()' > q1.js && ./b4w.ps1 -s captcha-eval eval --file q1.js  → null. Control: '(() => "ab".length)()' → 2. Raw CDP for the same input: ./b4w.ps1 -s captcha-eval cdp Runtime.evaluate --json '{"expression":"(() => \"(\".length)()","returnByValue":true}' → {"result":{"value":1}}

#### Expected Behavior

The expression evaluates to 1 (it is valid JS); any failure is reported as an error with the CDP exceptionDetails text.

#### Actual Behavior

Returns the literal null with the misleading hint 'Expression returned null. The queried element or property may not exist on this page.' Any expression with a raw '(' not matched by a raw ')' — e.g. a regex escape \(, a lone '(' inside a string, a comment — is mis-wrapped as ((<expr>))(); and silently nulled. This also silently corrupts plugin/vendor scripts (see the reCAPTCHA issue).

#### Root Cause Analysis

ai.platon.pulsar.common.js.JsUtils.toCDPCompatibleExpression calls toIIFEOrNull, whose isAlreadyInvokedIIFE walks the raw character stream incrementing/decrementing a depth counter for '(' and ')' without skipping strings, regex literals, or comments. When the counter never returns to 0 the already-invoked IIFE is re-wrapped with StringConcat recipe "(…)(…);". The resulting TypeError is not surfaced: the evaluateValue family returns Evaluate.result.value (null for a thrown error) and ignores Evaluate.exceptionDetails.

#### Code Pointer

`ai.platon.pulsar.common.js.JsUtils.toCDPCompatibleExpression / isAlreadyInvokedIIFE / toIIFEOrNull (pulsar-common jar — external dependency, upstream fix); in-repo consumers: browser4-plugins/browser4-captcha/.../CaptchaSolveScripts.kt`

#### AI Suggested Improvement

- Fix isAlreadyInvokedIIFE to be JS-aware: strip/skip string literals, template literals, regex literals and comments before counting, or detect an invoked IIFE with a real JavaScript parser
- Make toIIFEOrNull refuse to re-wrap a string that already parses as a complete expression
- Propagate Evaluate.exceptionDetails as an error (or at least log it) in the evaluateValue/evaluateValueDetail paths instead of returning null
- Add unit tests for: regex containing \(, lone '(' in a string, parens in template literals and comments, and a balanced control

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: No CLI command can invoke plugin-provided agent tools (captcha.detect etc.)

**Severity:** High
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help captcha → "Unknown command: captcha"; ./b4w.ps1 --help-json contains no captcha entry; ./b4w.ps1 help lists only plugin list|info|install|remove. The tool only responds to a hand-built POST http://localhost:18182/mcp/call-tool {"tool":"captcha_detect","arguments":{"sessionId":"…"}}.

#### Expected Behavior

A documented CLI path to call a plugin's agent tools, e.g. `browser4-cli captcha detect` or a generic `browser4-cli tool call captcha_detect`, with the tool-name mapping (captcha.detect → captcha_detect) documented.

#### Actual Behavior

The tools are exposed only as MCP tools on the backend. plugin info/list show nothing about the tools a plugin provides. A first-time user following the task's instruction to 'use captcha.detect' has no CLI command to run and no documented alternative; the raw HTTP endpoint and snake_case names had to be reconstructed from the source.

#### Root Cause Analysis

The CLI command registry (cli/browser4-cli/src/commands.rs commands_map) is static and contains no generic MCP-tool passthrough and no plugin-tool commands; REST plugin endpoints cover management only. Custom ToolMount executors are registered dynamically in MCPToolController/AgentToolManager but are not mirrored into CLI help.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (command registry) / cli/browser4-cli/src/help.rs; backend surface browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt (toMcpToolName, ~line 1140)`

#### AI Suggested Improvement

- Add a generic passthrough command, e.g. `browser4-cli tool call <mcp-name> [--json '{…}']`, routed to /mcp/call-tool with the active session's sessionId
- Or add first-class commands for the bundled plugin domains (captcha detect|solve|balance) that map to the MCP names
- Have `plugin info <name>` list the tool names/domains the plugin registers, and include tool names in `plugin list --json`
- Document the captcha.detect → captcha_detect mapping in docs/config.md and SKILL.md

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: captcha plugin ships nowhere by default and is undiscoverable from the CLI

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 plugin list → "No plugins installed." on a fresh dev runtime bundle; the bundle's plugins/ directory is empty. The JAR exists only under browser4-plugins/browser4-captcha/target/. `./b4w.ps1 help` and --help-json never mention CAPTCHA.

#### Expected Behavior

Either the first-party plugins are part of the dev runtime bundle, or the CLI/docs point at them: a line in help/quickstart plus an actionable install hint (source path and restart step).

#### Actual Behavior

Required manual discovery: find the plugin module in the source tree, build/choose the right versioned JAR, run `plugin install`, then `stop` and rely on the next command auto-starting the backend. `plugin install` says 'Restart the application to activate' but does not say how. docs/config.md documents copying the JAR into the runtime bundle's plugins/ dir, not `plugin install`.

#### Root Cause Analysis

The dev bundle assembly (browser4-apps/browser4-bundle) does not include browser4-plugins/*; the plugin is optional by design, but the discoverability path from the CLI is missing (no help entry, no docs cross-reference, no install hint in help plugin install).

#### Code Pointer

`browser4-apps/browser4-bundle (assembly); cli/browser4-cli/src/help.rs (plugin help text); docs/config.md §CAPTCHA`

#### AI Suggested Improvement

- Add a help/quickstart section 'Server plugins' that names the first-party plugins and shows `plugin install browser4-plugins/<name>/target/*.jar` + restart
- Make `plugin install` print the exact restart command (e.g. `browser4-cli stop`) and note that the next command auto-restarts the dev backend
- Consider bundling first-party plugins in the dev bundle so `plugin list` is not empty out of the box

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: captcha.getBalance/captcha.solve do not distinguish 'no solver configured' from real values

**Severity:** Medium
**Category:** UX

#### Reproduction

curl -s -X POST http://localhost:18182/mcp/call-tool -H "Content-Type: application/json" -d '{"tool":"captcha_get_balance","arguments":{"sessionId":"<sid>"}}' → "0.0"; captcha_solve with type RECAPTCHA_V2 + the demo siteKey → {status:FAILED, provider:NONE, error:"All 0 solver(s) failed to solve the CAPTCHA"}, isError=false.

#### Expected Behavior

A response that says no solving provider is configured (and how to configure one), since no API key is present; a failed solve should be surfaced as an error rather than a non-error payload.

#### Actual Behavior

0.0 is returned as a normal double (looks like a genuine empty account); the solve error blames '0 solvers failed', which is confusing — there are zero solvers configured, not failing ones. isError=false on a FAILED solution.

#### Root Cause Analysis

ChainedCaptchaSolver.balance() returns 0.0 when the chain is empty, and CaptchaAutoConfiguration only registers providers when the corresponding captcha.*.api.key property is set (none are). CaptchaToolExecutor.getBalance returns the Double directly with no provider-status metadata, and callFunctionOn returns the solution object even when status=FAILED.

#### Code Pointer

`browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/ChainedCaptchaSolver.kt (balance, ~line 72); tools/CaptchaToolExecutor.kt (getBalance/solve); config/CaptchaAutoConfiguration.kt (provider registration, ~line 135)`

#### AI Suggested Improvement

- Return a structured balance result (e.g. {configured:false, balance:null, provider:NONE}) or an explicit error when the solver chain is empty
- Make captcha.solve fail fast with 'No CAPTCHA solving provider configured — set captcha.capsolver.api.key (or twoCaptcha/antiCaptcha)' when the chain is empty, and mark it isError=true at the MCP layer
- Document in the tool help that balance is averaged across all configured providers

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Navigation challenge-probe false positives push users toward unnecessary headed escalation

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 -s captcha-eval goto "https://en.wikipedia.org/wiki/CAPTCHA" → warns "detected URL matches the block/challenge marker `/captcha`" on a normal Wikipedia article. 2) ./b4w.ps1 -s captcha-eval goto "https://www.google.com/recaptcha/api2/demo" → warns "page text is implausibly empty (64 chars) after a successful navigation" although the page (a CAPTCHA widget demo) is fully functional; the widget and iframes are present.

#### Expected Behavior

No blocked/challenge advisory on these pages; the article URL merely contains the word CAPTCHA, and a page whose visible text is rendered inside an iframe/CAPTCHA widget legitimately has little body text. When the probe does fire, it should not advise a mode switch that costs a session teardown.

#### Actual Behavior

Both navigations print the anti-bot advisory and the recommendation to close, re-open --headed and retry — a costly, unnecessary action. SKILL.md teaches the escalation as the documented response to this warning, so a rule-following agent will perform it.

#### Root Cause Analysis

detect_block_signature (cli/browser4-cli/src/main.rs:23302) matches BLOCKED_URL_SIGNATURES as plain case-insensitive substrings — "/captcha" matches inside /wiki/CAPTCHA — and flags any http(s) page with fewer than IMPLAUSIBLY_EMPTY_BODY_CHARS=80 visible characters as an empty-body challenge, without considering iframe/frame content or DOM size.

#### Code Pointer

`cli/browser4-cli/src/main.rs — BLOCKED_URL_SIGNATURES (line 23225), match_blocked_url (23278), IMPLAUSIBLY_EMPTY_BODY_CHARS (23261), detect_block_signature (23302)`

#### AI Suggested Improvement

- Match URL markers against path segments with boundaries (e.g. (^|/)captcha(/|$) or query keys) instead of raw substrings, and/or down-rank markers embedded in content paths such as /wiki/ or /article/
- For the empty-body signal, also require a low DOM-node count or the absence of iframes/frames before warning, so widget-only pages are exempt
- Keep the warning advisory but only suggest --headed when a body/URL challenge marker fired, not for the weak empty-body signal
- Add regression tests: URL /wiki/CAPTCHA must not match; an iframe-only demo page must not match

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Dashed plugin-* command form is rejected while help advertises it

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 plugin-list → Error: Unsupported command form: plugin-list. Use 'browser4-cli plugin list' instead. (The same CLI accepts `plugin list` and its help.rs maps "plugin-list" → "plugin list"; SKILL.md's command table writes `plugin-*`.)

#### Expected Behavior

Either both forms work, or all documentation consistently shows only the spaced form.

#### Actual Behavior

The dashed form fails with a redirect error (clear, but an avoidable stumble); other command families accept dashed forms (htmlsnapshot-get, snapshot-grep, crawl-status), so the inconsistency is surprising.

#### Root Cause Analysis

handle_plugin_* dispatch is registered only for the spaced subcommand path; help.rs's COMMAND_ARG_ALIASES contains plugin-list/plugin-info entries used for help rendering but the runtime dispatcher rejects them.

#### Code Pointer

`cli/browser4-cli/src/main.rs (plugin command dispatch, ~line 17287) and cli/browser4-cli/src/help.rs (COMMAND_ARG_ALIASES, line 49)`

#### AI Suggested Improvement

- Accept the dashed aliases for plugin-* (rewrite to the spaced form before dispatch), matching other command families
- Or update SKILL.md's command map to spell `plugin list`/`plugin install` instead of `plugin-*`

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

**Completion Status:** Partially Successful — all navigation and detection steps ran; hCaptcha, Turnstile and the Wikipedia negative control behaved correctly, but the task's flagship scenario (Google reCAPTCHA v2 demo) returned a wrong isPresent=false with no error, and the solver-balance step could not report meaningful availability information because no provider is configured.

**Success Rate:** 75% — 6 of 8 task steps fully succeeded (navigate x5, hCaptcha detect, Turnstile detect, negative control, balance call, summary); reCAPTCHA detection failed and siteKey reporting for reCAPTCHA required a manual workaround.

**Issues Found:** 7

**Major Blockers:** No CLI command can invoke plugin agent tools (captcha.detect), so every tool call had to be issued as a raw POST to /mcp/call-tool reconstructed from the source; the plugin was not present in the dev bundle and had to be built/installed/restarted manually; the reCAPTCHA detector itself returns a silent false negative.

**Most Confusing Aspects:** The task says to use 'captcha.detect' but there is no such CLI command anywhere in help; `help captcha` says 'Unknown command'; `plugin-list` is rejected while `plugin list` works; a fresh dev bundle contains no plugins despite the task assuming the captcha plugin is available; eval returns a bare null for valid-looking JS with only a misleading 'element may not exist' hint, which makes the real infrastructure failure invisible.

**Most Valuable Improvements:** Fix the JsUtils IIFE/paren wrapping so plugin detection scripts (and user eval) are not silently nulled — this alone restores reCAPTCHA detection; expose plugin tools through the CLI (generic `tool call` or `captcha detect|solve|balance`); make discovery real: list first-party plugins and their tools in help/plugin info and document the install+restart path; distinguish 'no solver configured' from 0.0/FAILED in captcha tools; tighten the challenge-probe heuristics that false-positive on /wiki/CAPTCHA and iframe-only pages.

**Usability Rating:** 6/10

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

#### Issue 1: captcha.detect returns isPresent=false on Google's live reCAPTCHA v2 demo (false negative)

1) ./b4w.ps1 plugin install browser4-plugins/browser4-captcha/target/browser4-captcha-4.13.27-SNAPSHOT.jar ; ./b4w.ps1 stop ; 2) ./b4w.ps1 -s captcha-eval goto "https://www.google.com/recaptcha/api2/demo" ; 3) curl -s -X POST http://localhost:18182/mcp/call-tool -H "Content-Type: application/json" -d '{"tool":"captcha_detect","arguments":{"sessionId":"<sid>"}}'

#### Issue 2: eval silently returns null for valid JavaScript whose raw parenthesis counts are unbalanced

printf '%s' '(() => "(".length)()' > q1.js && ./b4w.ps1 -s captcha-eval eval --file q1.js  → null. Control: '(() => "ab".length)()' → 2. Raw CDP for the same input: ./b4w.ps1 -s captcha-eval cdp Runtime.evaluate --json '{"expression":"(() => \"(\".length)()","returnByValue":true}' → {"result":{"value":1}}

#### Issue 3: No CLI command can invoke plugin-provided agent tools (captcha.detect etc.)

./b4w.ps1 help captcha → "Unknown command: captcha"; ./b4w.ps1 --help-json contains no captcha entry; ./b4w.ps1 help lists only plugin list|info|install|remove. The tool only responds to a hand-built POST http://localhost:18182/mcp/call-tool {"tool":"captcha_detect","arguments":{"sessionId":"…"}}.

#### Issue 4: captcha plugin ships nowhere by default and is undiscoverable from the CLI

./b4w.ps1 plugin list → "No plugins installed." on a fresh dev runtime bundle; the bundle's plugins/ directory is empty. The JAR exists only under browser4-plugins/browser4-captcha/target/. `./b4w.ps1 help` and --help-json never mention CAPTCHA.

#### Issue 5: captcha.getBalance/captcha.solve do not distinguish 'no solver configured' from real values

curl -s -X POST http://localhost:18182/mcp/call-tool -H "Content-Type: application/json" -d '{"tool":"captcha_get_balance","arguments":{"sessionId":"<sid>"}}' → "0.0"; captcha_solve with type RECAPTCHA_V2 + the demo siteKey → {status:FAILED, provider:NONE, error:"All 0 solver(s) failed to solve the CAPTCHA"}, isError=false.

#### Issue 6: Navigation challenge-probe false positives push users toward unnecessary headed escalation

1) ./b4w.ps1 -s captcha-eval goto "https://en.wikipedia.org/wiki/CAPTCHA" → warns "detected URL matches the block/challenge marker `/captcha`" on a normal Wikipedia article. 2) ./b4w.ps1 -s captcha-eval goto "https://www.google.com/recaptcha/api2/demo" → warns "page text is implausibly empty (64 chars) after a successful navigation" although the page (a CAPTCHA widget demo) is fully functional; the widget and iframes are present.

#### Issue 7: Dashed plugin-* command form is rejected while help advertises it

./b4w.ps1 plugin-list → Error: Unsupported command form: plugin-list. Use 'browser4-cli plugin list' instead. (The same CLI accepts `plugin list` and its help.rs maps "plugin-list" → "plugin list"; SKILL.md's command table writes `plugin-*`.)

