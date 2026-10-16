# Issues: plugin-captcha-detection

> **Source:** `20261015-123037-plugin-captcha-detection.issues.json` | **Date:** 20261015-123037 | **Mode:** dev

## Scenario Background

### Task

All requested detection steps completed successfully after one setup step: the **browser4-captcha plugin was not installed** in this checkout (only `browser4-swarm` was), so I installed it per `help`/`docs/config.md`, restarted the backend for it to load, and then ran the task.

| # | Page | isPresent | captchaType | siteKey | confidence | selector |
|---|---|---|---|---|---|---|
| 1 | `https://www.google.com/recaptcha/api2/demo` | `true` | **RECAPTCHA_V2** | `6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-` | **0.95** | `.g-recaptcha` |
| 2 | `https://accounts.hcaptcha.com/demo` | `true` | **HCAPTCHA** | `a5f74b19-9e45-40e0-b45d-47ff91b7a6c2` | **0.95** | `.h-captcha` |
| 3 | `https://demo.turnstile.workers.dev` | `true` | **TURNSTILE** | `1x00000000000000000000AA` | **0.95** | `.cf-turnstile` |
| 4 | `https://en.wikipedia.org/wiki/CAPTCHA` | **`false`** | `UNKNOWN` | `null` | **0.0** | `null` |
| 5 | reCAPTCHA demo (revisit) | `true` | RECAPTCHA_V2 | same key | 0.95 | `.g-recaptcha` |

**Solver availability (step 6):** No solving service is configured —
`captcha_get_balance` → `{"configured":false,"message":"No CAPTCHA solving provider configured — set captcha.capsolver.api.key (or captcha.twocaptcha.api.key / captcha.anticaptcha.api.key)"}`. `captcha_solve` correspondingly fails fast (exit 1) with the same guidance, so solving was correctly skipped.

**Detection summary (step 7):**
- **All three provider types detected**, each with `confidence=0.95`; the negative page reported `0.0`/`not present` — no false positives (also verified on `example.com` and `about:blank`).
- **Highest/lowest confidence:** no spread among widget detectors (all 0.95); only the image detector differs (0.7) and it never fired. The one variation is `metadata`: reCAPTCHA returns `widgetCount=1`, hCaptcha/Turnstile return `{}`.
- **Speed:** wall-clock **1.6–1.8 s per `captcha_detect` call**, stable across repeats (5 runs on reCAPTCHA: 1587/1759/1799/1692/1735 ms). The backend logs show the detector itself takes **3–35 ms**; the rest is CLI + HTTP overhead. One outlier: the first detect right after loading the hCaptcha demo took 2542 ms server-side, consistent with the page's JS main thread still being busy — later calls on the same page were 5–24 ms.
- **Extra robustness checks I ran:** a widget injected *after* page load via `eval` was detected with its key; two concurrent sessions (`cap`/`cap2`) each reported their own page's CAPTCHA (session isolation correct).

### Execution Context

**Key Commands:**

**Major steps:** (1) verified cwd = repo root, ran `help`, read `SKILL.md` in full; (2) found the captcha plugin missing, read `docs/config.md §CAPTCHA` and the plugin's Kotlin sources to learn the tool surface; (3) installed `browser4-captcha-4.14.1-SNAPSHOT.jar`, `stop`, auto-restart (6.5 s, "CAPTCHA detection chain initialized with 4 detectors"); (4) ran the 5 detection scenarios in session `cap`, timing each; (5) ran `captcha_get_balance` / `captcha_solve`; (6) probed discoverability surfaces (`plugin`, `plugin info`, `--help plugin-captcha`, `tool call --help`); (7) decomposed latency via backend logs and `pwsh` startup measurements; (8) proved session isolation and dynamic-widget detection.

**Key decisions:** used `-s cap` (named session) for isolation per SKILL's concurrency guidance; installed the exact `4.14.1-SNAPSHOT` JAR rather than the documented `*.jar` wildcard because that wildcard matched 6 JARs (see Issue 1); read the plugin source rather than guessing tool names, because the CLI never lists them.

**Workarounds required:** (a) the documented `plugin install …browser4-captcha-*.jar` command **fails** — had to type the full JAR filename; (b) tool names had to be learned from docs/source, not from CLI output (the CLI's listing shows only pseudo-domains); (c) `captcha_detect` output had to be parsed out of a Java `toString()` string.

**Evidence** saved under `.test-sessions/20261015T0901431699046Z/captcha/` (`results.md`, `solve-no-provider.out/.err`).

---

## Issues Found (10 issues)

### Issue 1: captcha_detect result is a Java toString() string, not structured JSON — no machine-readable output mode

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s cap tool call captcha_detect
./b4w.ps1 -s cap --json --pretty tool call captcha_detect

#### Expected Behavior

A structured payload whose fields are directly addressable, e.g. {"isPresent":true,"captchaType":"RECAPTCHA_V2","siteKey":"6Le-...","confidence":0.95,"selector":".g-recaptcha"} — the pattern the same plugin already uses for captcha_get_balance (which returns clean JSON).

#### Actual Behavior

{"type":"ai.platon.pulsar.captcha.CaptchaDetectionResult","description":"CaptchaDetectionResult(isPresent=true, captchaType=RECAPTCHA_V2, siteKey=6Le-wvkSAAAAAPBMRTvw0Q4Muexq9bi0DJwx_mJ-, action=null, selector=.g-recaptcha, confidence=0.95, metadata={widgetCount=1})"}. Even with the global `--json --pretty` the value stays a string under output.result.description; every consumer must regex-scrape Kotlin data-class syntax, and `metadata` is itself an unquoted map literal.

#### Root Cause Analysis

The tool returns the CaptchaDetectionResult data class; the MCP/agentic result serializer has no JSON mapping for it, so it falls back to toString() wrapped in a type+description envelope. Structured results only survive when the tool returns a plain Map/JSON value (as getBalance does), so the defect is per-return-type, not a protocol limitation.

#### Code Pointer

`browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/CaptchaDetectionResult.kt (serialization) and browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/tools/CaptchaToolExecutor.kt:callFunctionOn() ("detect" branch)`

#### AI Suggested Improvement

- Return a Map<String, Any?> from the `detect` branch (same style as getBalance) so the payload is real JSON
- Or register an explicit Jackson serializer for CaptchaDetectionResult in the plugin and honour it in the tool-result layer
- If the toString envelope is intentional, document it and the exact field order in the tool help so parsers are not guesswork

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Documented `plugin install` wildcard command fails — CLI joins shell-expanded paths into one bogus filename

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 plugin install browser4-plugins/browser4-captcha/target/browser4-captcha-*.jar   (exactly as written in docs/config.md §CAPTCHA and the `help` Server-plugins footer)
./b4w.ps1 plugin install "a.jar" "b.jar"   (minimal form)

#### Expected Behavior

Installs the intended JAR (or, given 6 versioned JARs, rejects the ambiguity with a message naming the candidates / pointing at a `--latest` / version flag).

#### Actual Behavior

Error: Plugin file not found: browser4-plugins/browser4-captcha/target/browser4-captcha-4.14.0-rc.10.jar browser4-plugins/.../browser4-captcha-4.14.0-rc.5.jar ... browser4-captcha-4.14.1-SNAPSHOT.jar  (exit 1). The CLI reports a single "not found" path built by joining all positional arguments with spaces; there is no hint that 6 files matched or which one is correct.

#### Root Cause Analysis

handle_plugin_install reads one positional `file` string; the arg parser folds all extra positionals into that same string (space-joined), so a glob expanding to N files becomes one nonexistent path. The error message echoes the joined string, hiding the real cause. Compounding it, browser4-plugins/browser4-captcha/target/ contains 6 JARs (rc.5 … rc.10, 4.14.1-SNAPSHOT) and neither the docs nor the CLI explain which matches the checkout.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_plugin_install() (~line 21839, "Plugin file not found") plus the positional-argument collection feeding it`

#### AI Suggested Improvement

- Reject more than one positional for `plugin install` with an explicit message ("expected exactly one JAR, got 6 — pick one")
- Or resolve globs inside the CLI and offer `--latest` / `--version <x>` selection, preferring the JAR matching the checked-out version
- Update docs/config.md and the `help` footer to name the version-matched artifact (e.g. `browser4-captcha-<version>.jar`) instead of `*.jar`
- Print the available candidates in the error path so a first-time user can choose without `ls`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `plugin` tool listing shows invented pseudo-domains and never the real tool names

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 plugin

#### Expected Behavior

A listing a first-time user can act on, e.g. domains with their methods: `captcha` → detect, solve, solve_image, get_balance.

#### Actual Behavior

Lists `plugin captcha`, `plugin captcha_get`, `plugin captcha_solve`. The real tools are captcha_detect, captcha_get_balance, captcha_solve, captcha_solve_image: `captcha_detect` never appears, and `captcha_get`/`captcha_solve` look like domains but are artefacts. The only place real method names surface is an error: `plugin captcha bogusMethod` → "Available methods: detect, get_balance, solve, solve_image".

#### Root Cause Analysis

available_plugin_domains() strips only the last underscore-separated segment (rsplitn(2,'_')) to derive a domain, so captcha_get_balance → domain captcha_get, captcha_solve_image → captcha_solve. The registration path (CustomToolRegistry / ToolSpec.domain) knows the true domain but is not consulted for the listing.

#### Code Pointer

`cli/browser4-cli/src/main.rs:available_plugin_domains() (~line 22736) and resolve_plugin_method() (~line 21964)`

#### AI Suggested Improvement

- Build the listing from the backend's tool-spec metadata (ToolSpec.domain/method) instead of name heuristics
- Print each domain with its methods (and one-line descriptions) so `captcha_detect` is discoverable without invoking a bogus method
- Keep `plugin <domain> <method>` working for multi-word tool names such as captcha_get_balance / captcha_solve_image

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `plugin info <domain>` fails with a bare 404 and an empty response body

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 plugin info captcha      # name exactly as printed by `plugin` / `plugin list`
./b4w.ps1 plugin info browser4-captcha   # works

#### Expected Behavior

Either the plugin's details, or an actionable error ("Unknown plugin 'captcha'. Installed plugins: browser4-captcha, browser4-swarm — run `plugin list`").

#### Actual Behavior

Error: HTTP request failed with status 404 Not Found and an empty response body. No indication that the domain/plugin distinction is the problem, and no list of valid names.

#### Root Cause Analysis

The backend's plugin-info lookup is keyed on manifest name / JAR filename only; the CLI passes the user's argument through verbatim and surfaces the empty 404 body. The names the CLI itself advertises elsewhere (`plugin captcha`) are therefore not accepted by its own `info` subcommand.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_plugin_info() (error surfacing) and the backend PluginService info endpoint`

#### AI Suggested Improvement

- On 404, retry with a substring/domain match against installed plugin names and say which name was used
- Always return a non-empty error body/help text listing valid manifest names
- Accept the tool-domain alias (`captcha`) as an input form, since that is the vocabulary `plugin` and `tool call` teach

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: captcha_get_balance omits the documented `balance` and `providers` keys

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s cap tool call captcha_get_balance

#### Expected Behavior

Per the tool's own help text and docs/config.md §CAPTCHA: a map with configured, balance (null), providers ([]), message.

#### Actual Behavior

{"configured":false,"message":"No CAPTCHA solving provider configured — ..."} — `balance` and `providers` are absent, so a client that switches on those keys cannot distinguish "unconfigured" from "field renamed".

#### Root Cause Analysis

The executor builds the documented 4-key map, but null/empty-collection values are dropped by the serialization layer before the CLI prints them; the documented contract was written against the pre-serialization map.

#### Code Pointer

`browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/tools/CaptchaToolExecutor.kt:callFunctionOn() ("getBalance" branch) vs the agentic result serializer`

#### AI Suggested Improvement

- Emit null/[] explicitly (serializer inclusion setting) so the documented schema holds
- Or update the ToolSpec help and docs/config.md to the actually observed shape
- Add a contract test asserting the four keys for both configured and unconfigured states

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: `--help <plugin-domain>` prints "Unknown command" and then dumps the full ~200-line command reference

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 --help plugin-captcha
./b4w.ps1 tool call --help --examples

#### Expected Behavior

A short, focused note for a plugin domain (what it is, how to list its methods/tools) and exit; discoverable examples for the passthrough command that the docs advertise (`tool call captcha_detect`).

#### Actual Behavior

Prints a helpful 3-line explanation, then "Unknown command: plugin-captcha" and the entire general help; the useful text is buried above a wall of output. Separately, `tool call --help --examples` answers "No examples available for 'tool call' — the command maps to no single tool", so the CLI's most important escape hatch for plugins ships without examples.

#### Root Cause Analysis

Unknown-command handling falls through to printing the global reference, and the examples facility only works for commands bound to a single backend tool, not for the generic passthrough.

#### Code Pointer

`cli/browser4-cli/src/main.rs:help dispatch / unknown-command fallback; tool-call examples path`

#### AI Suggested Improvement

- Route `--help plugin-<domain>` to the plugin-domain help (list its methods + a runnable example) instead of the unknown-command path
- When falling back to full help, keep it behind a flag (e.g. `help` alone) and end the short message with a next-step command
- Add static examples for `tool call` (captcha_detect / captcha_get_balance) that do not require a single backing tool

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: CLI help says to list tools with `plugin list`, but `plugin list` lists plugins only

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 tool call --help   vs   ./b4w.ps1 plugin list

#### Expected Behavior

Accurate pointer to a command that actually enumerates tools.

#### Actual Behavior

`tool call --help` states "list available tools with `plugin list` or GET /mcp/tools"; `plugin list` prints two installed-plugin rows and no tool names. GET /mcp/tools returns names only (no descriptions).

#### Root Cause Analysis

The help string was written against an earlier output shape; the listing command and the tool-listing surface diverged.

#### Code Pointer

`cli/browser4-cli/src/main.rs (tool call help text) and the `plugin list` handler`

#### AI Suggested Improvement

- Correct the help text to `plugin` (domains) and `GET /mcp/tools` (tool names)
- Have `plugin <domain>` print its methods so a single command answers "what can this plugin do?"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Inconsistent detection metadata: widgetCount reported only by the reCAPTCHA detector

**Severity:** Low
**Category:** Product

#### Reproduction

Compare `tool call captcha_detect` outputs on the three demo pages.

#### Expected Behavior

Consistent result shape across detectors, so consumers can rely on metadata keys (or on metadata being uniformly empty).

#### Actual Behavior

RECAPTCHA_V2 → metadata={widgetCount=1}; HCAPTCHA and TURNSTILE → metadata={}; `action` is always null; image detections would add imageSelector/inputSelector keys.

#### Root Cause Analysis

RecaptchaDetector passes metadata from its JS probe into found(...); HCaptchaDetector/TurnstileDetector call found(...) without metadata even though their scripts also count widgets.

#### Code Pointer

`browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/detection/HCaptchaDetector.kt, TurnstileDetector.kt (found(...) calls)`

#### AI Suggested Improvement

- Populate widgetCount (and any probe-provided fields) uniformly in every detector
- Document the metadata contract in the ToolSpec help so consumers know which keys are optional

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: Per-call latency is dominated by CLI overhead, and detection latency has no documented wait/timeout semantics

**Severity:** Low
**Category:** UX

#### Reproduction

Time `./b4w.ps1 -s cap tool call captcha_detect` (≈1.7 s) and compare with the backend log line `tool=captcha_detect ... durationMs=3..35`. First detect right after `goto https://accounts.hcaptcha.com/demo` logged durationMs=2542.

#### Expected Behavior

Either latency guidance in the docs, or lower per-call overhead — detection itself is effectively instant.

#### Actual Behavior

Every detect costs ~1.6–1.8 s wall clock despite 3–35 ms server-side work; `./b4w.ps1 --version` alone costs ~1.2 s (pwsh ~0.3 s + wrapper), so on Windows a 4-page scan spends ~7 s in process/round-trip overhead. The 2.5 s first-detection on the hCaptcha demo shows results also depend on page JS state, and nothing documents whether/when `captcha.detect` waits for lazily rendered widgets (a call made too early could report isPresent=false with no retry hint).

#### Root Cause Analysis

Two layers: (a) the dev wrapper re-resolves the checkout and spawns PowerShell per command; (b) each tool call performs session resolution round-trips before hitting a ~15 ms backend endpoint. The detectors are single JS evals with no wait loop, so a not-yet-rendered widget is a silent false negative.

#### Code Pointer

`b4w.ps1 (dev wrapper bootstrap), cli/browser4-cli/src/main.rs with_session()/call_tool_with_timeout_override(), browser4-plugins/browser4-captcha/src/main/kotlin/ai/platon/pulsar/captcha/detection/ChainedCaptchaDetector.kt`

#### AI Suggested Improvement

- Document expected detect latency and recommend `batch`/repeated calls where several tool calls are needed in one step
- Document (or add) an optional wait/retry window for lazily rendered widgets, and surface it in the detection result (e.g. `waited: false`)
- Note in docs that per-command CLI overhead on Windows dev wrappers is ~1.2 s and unrelated to plugin performance

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: SKILL.md contradicts observed `stop` behaviour for session records

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 list      # shows an Active session
./b4w.ps1 stop

#### Expected Behavior

Per skills/browser4-cli/SKILL.md §Sessions: "stop ... removes every sessions/*.json record, so the next `list` prints 'No active browser sessions.'"

#### Actual Behavior

stop prints "Marked 1 session record(s) stale — they stay in `list` and are revived by `open`/`goto` after the backend restarts", and the following `list` does show the row as `Stale / Refresh`.

#### Root Cause Analysis

The stop implementation was changed to mark records stale (matching the Stale/Refresh UX described elsewhere in the same document) but the SKILL.md paragraph was not updated; the two halves of the doc now disagree.

#### Code Pointer

`skills/browser4-cli/SKILL.md §Sessions ("`stop` deletes this checkout's saved session records")`

#### AI Suggested Improvement

- Update SKILL.md to the mark-stale behaviour and drop the "No active browser sessions" claim
- Add a regression note that `list --all`/Stale rows persist after stop

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

**Completion Status:** Successful — all 7 task steps completed: captcha plugin installed and loaded, live reCAPTCHA v2 / hCaptcha / Turnstile widgets detected with correct type+siteKey, negative page (Wikipedia) correctly reported isPresent=false, and solver availability (unconfigured) reported via captcha_get_balance.

**Success Rate:** 95% — every requested measurement was obtained; only the optional solving step was unavailable by configuration (no provider key), which is an expected, clearly reported state rather than a failure.

**Issues Found:** 10

**Major Blockers:** None fatal. The only real blocker was that the browser4-captcha plugin is not installed by default and the documented install command (wildcard `*.jar`) fails outright, so installation required reading docs/config.md plus the plugin source to pick the right of 6 JARs. Secondary friction: tool names are invisible in CLI listings and the detect result is an unparseable Kotlin toString.

**Most Confusing Aspects:** For a first-time user: (1) the capability under test is not present until a plugin install + backend restart, and the documented install command fails; (2) `plugin` output advertises domains (`captcha_get`, `captcha_solve`) that do not exist as tools while hiding `captcha_detect`; (3) `plugin info captcha` 404s with an empty body even though `plugin` prints `plugin captcha`; (4) `tool call` help points at `plugin list`, which lists plugins, not tools; (5) the detection result looks like JSON but its fields live inside a Java toString string, so even `--json` yields nothing directly addressable.

**Most Valuable Improvements:** Return captcha_detect results as real JSON (a Map, exactly like getBalance already does) — one change removes the need for bespoke parsing in every agent. Then fix plugin discovery end to end: derive the `plugin` listing from tool-spec metadata (real domain + methods), accept the domain name in `plugin info`, and make the documented install command work (single-file validation with an actionable error, or glob/`--latest` support) plus docs naming the version-matched JAR. Lower-effort wins: show the documented `balance`/`providers` keys, unify detector metadata, and document detect latency/wait semantics.

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

#### Issue 1: captcha_detect result is a Java toString() string, not structured JSON — no machine-readable output mode

./b4w.ps1 -s cap tool call captcha_detect
./b4w.ps1 -s cap --json --pretty tool call captcha_detect

#### Issue 2: Documented `plugin install` wildcard command fails — CLI joins shell-expanded paths into one bogus filename

./b4w.ps1 plugin install browser4-plugins/browser4-captcha/target/browser4-captcha-*.jar   (exactly as written in docs/config.md §CAPTCHA and the `help` Server-plugins footer)
./b4w.ps1 plugin install "a.jar" "b.jar"   (minimal form)

#### Issue 3: `plugin` tool listing shows invented pseudo-domains and never the real tool names

./b4w.ps1 plugin

#### Issue 4: `plugin info <domain>` fails with a bare 404 and an empty response body

./b4w.ps1 plugin info captcha      # name exactly as printed by `plugin` / `plugin list`
./b4w.ps1 plugin info browser4-captcha   # works

#### Issue 5: captcha_get_balance omits the documented `balance` and `providers` keys

./b4w.ps1 -s cap tool call captcha_get_balance

#### Issue 6: `--help <plugin-domain>` prints "Unknown command" and then dumps the full ~200-line command reference

./b4w.ps1 --help plugin-captcha
./b4w.ps1 tool call --help --examples

#### Issue 7: CLI help says to list tools with `plugin list`, but `plugin list` lists plugins only

./b4w.ps1 tool call --help   vs   ./b4w.ps1 plugin list

#### Issue 8: Inconsistent detection metadata: widgetCount reported only by the reCAPTCHA detector

Compare `tool call captcha_detect` outputs on the three demo pages.

#### Issue 9: Per-call latency is dominated by CLI overhead, and detection latency has no documented wait/timeout semantics

Time `./b4w.ps1 -s cap tool call captcha_detect` (≈1.7 s) and compare with the backend log line `tool=captcha_detect ... durationMs=3..35`. First detect right after `goto https://accounts.hcaptcha.com/demo` logged durationMs=2542.

#### Issue 10: SKILL.md contradicts observed `stop` behaviour for session records

./b4w.ps1 list      # shows an Active session
./b4w.ps1 stop

