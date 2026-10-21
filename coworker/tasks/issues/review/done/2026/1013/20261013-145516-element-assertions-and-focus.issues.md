# Issues: element-assertions-and-focus

> **Source:** `20261013-145516-element-assertions-and-focus.issues.json` | **Date:** 20261013-145516 | **Mode:** dev

## Scenario Background

### Task

The scenario ran end-to-end against the real local backend (dev mode, server `http://127.0.0.1:8283`, health UP) with MockSite already serving on port 18080. A fresh headless session (`-s state-eval`) was opened on the interactive fixture and all ten steps were executed.

**Acceptance criteria outcome:**

| AC | Result | Notes |
|---|---|---|
| AC1 `is visible` | ⚠️ Partially | `true` / `false` / no-match→`false` all correct, **but every `false` exits 0** — a false assertion is silently readable as success; no exit-code contract is documented in `--help`, `--help-json`, SKILL.md or README (contrast: `snapshot grep` documents exit 1 on zero matches). |
| AC2 `is enabled` | ⚠️ Partially | `true`/`false` correct, but a missing/invalid selector returns `null`, not `false` like its two siblings. |
| AC3 check/uncheck | ✅ | Round trip verified against both `is checked` and `window.__browser4State.checkbox`. |
| AC4 focus | ✅ | `document.activeElement.id` = `fill-target`. |
| AC5 scrollintoview | ✅ | scrollY 0→1335; box y 2159→824 (inside 1080px viewport). |
| AC6 highlight | ✅ | Inline `outline: rgb(255, 87, 34) solid 3px` on target only (sibling untouched). |
| AC7 pushstate | ✅ | pathname changed, `readyState: complete`, DOM intact (checkbox + outline survived), `page-info` shows new URL. |
| AC8 explicit failures | ❌ Partially | Missing arguments all fail correctly (exit 1 + usage). Bad selectors: `check`/`uncheck`/`click` print `✓` and exit 0 (silent pass), `focus` exits 0 silently; `scrollintoview`/`highlight`/`select` exit 1 naming the selector but with misleading labels/dumps. |

The functional core of this scenario works well, but the scripting contract (exit codes, silent no-ops) has real defects — the two answers the scenario explicitly asks for are both negative: exit codes are **not** documented anywhere a script author would look, and a false assertion **is** silently readable as success.

### Execution Context

**Key Commands:**

**Major steps / decisions:**
1. MockSite was already up (HTTP 200 on the fixture) — no `./bin/test.ps1 mock-site` needed.
2. Read `SKILL.md` and `quick-patterns.md` up front; these are excellent, but neither documents exit codes or output contracts for the assertion/action commands.
3. Used a dedicated named session (`-s state-eval`) per the concurrency guidance; verified every assertion against the page's own `window.__browser4State` via `eval` rather than trusting CLI output alone.
4. Verified the bogus `check`/`uncheck`/`click`/`focus` calls were true no-ops by re-reading page state afterwards.
5. Root-caused every non-obvious behavior in source (see per-issue Code Pointers).
6. Cleanup: closed `state-eval` and a throwaway `typo-test` session; both reported "Session closed. Browser terminated."

**Workarounds required:**
- `pushstate "/b4/frame-other.html"` failed in Git Bash (MSYS path mangling) with an exemplary diagnostic; retried successfully with `MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 …`.
- `get box`'s Java `RectD` wrapper had to be manually parsed to check viewport intersection.
- Exit-code-based verification was impossible for `is …` assertions; verification was done by reading the printed text and cross-checking with `eval`/`--json`.

All scratch files (fixture copy, evidence log) are in `.test-sessions/20261013T0154334271610Z/`.

---

## Issues Found (12 issues)

### Issue 1: check/uncheck/click/focus report success on selectors that match nothing

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s state-eval check "#no-such-element-abc" (repeat with uncheck/click/focus, and with a garbage selector `check "???"`) on http://localhost:18080/b4/mcp-tool-controller-interactive-fixture.html

#### Expected Behavior

Non-zero exit and an error message naming the selector (AC8: 'no silent pass'); the ✓ confirmation must not print when no element was acted on.

#### Actual Behavior

`check` printed '✓ Checked #no-such-element-abc' and exited 0; `uncheck`/`click` printed '✓ Unchecked/Clicked …' exit 0; `focus` exited 0 with no message at all. They are true no-ops: document.activeElement stayed 'fill-target' and window.__browser4State.checkbox kept its prior value. Even `check "???"` printed '✓ Checked ???' exit 0.

#### Root Cause Analysis

The tool executor dispatches straight to the driver (driver.check/uncheck/click/focus); the driver returns without error when the selector cannot be resolved (Browser4WebDriver.click resolves a hover point, gets null, and falls back to the upstream super.click which no-ops). The tool call therefore reports OK, and the CLI prints its unconditional success confirmation in main.rs (~8311-8351) without verifying the action landed. The executor already has the right helper — elementNotFoundMessage() at BrowserTabToolExecutor.kt:774, used by `select` — but click/check/uncheck/focus never call it.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt ("click" ~1545, "focus" ~1304, "check" ~1854, "uncheck" ~1864); cli/browser4-cli/src/main.rs:8311 (success-confirmation block)`

#### AI Suggested Improvement

- Before dispatching click/check/uncheck/focus, resolve the target (driver.exists(selector)) and throw elementNotFoundMessage(selector) — the existing message already names the selector and gives ref-staleness guidance
- Alternatively make movePointerToClickTarget / focus fail loudly instead of returning null/silently
- Only print the '✓ Checked/Clicked …' line after the backend confirms the element was resolved (e.g. return the resolved ref from the tool)
- Add e2e coverage: every action command on a non-matching selector must exit non-zero

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Assertion commands exit 0 when the result is false/null — no scriptable failure signal, and no documentation

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s state-eval is visible "#hidden-target"; echo $?   (also `is visible "#no-such-element-xyz"`, `is enabled "#disabled-target"`, `is checked "#check-target"`)

#### Expected Behavior

A documented way for scripts to distinguish a false assertion from success (AC1: 'a false assertion must not be silently readable as success in a script').

#### Actual Behavior

All three commands print the value and exit 0 in every case. --json returns {"status":"ok","command":"is-visible","output":{"result":"false"}} — still exit 0. No opt-in flag exists (--fail-on-false is silently ignored, see the unknown-flag issue). The exit-code contract is documented nowhere: `--help`, `--help-json`, SKILL.md, README.md and quick-patterns.md all omit it. Contrast: `snapshot grep` documents 'Zero matches = exit 1', and the project's own audit scenarios build exit codes by hand via `| grep -q` pipelines.

#### Root Cause Analysis

The is-* commands are thin wrappers around MCP tool calls (CommandDef entries with no options, commands.rs ~2190-2231); the printed value is only text and process exit is driven solely by transport/tool-call errors, never by the boolean result. No assertion flag was added to the CommandDefs.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (is-visible/is-enabled/is-checked CommandDefs); result handling in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Add an opt-in `--fail-on-false` (or `--assert`) flag that exits 1 when the assertion is false/null, keeping the default for backward compatibility
- Document the exit-code contract on each command's help page and in SKILL.md / README ('prints true/false; exits 0 unless --fail-on-false is given')
- At minimum, document the `--json` + output.result parsing route for scripts
- Fix `is enabled` to return false (see separate issue) so the flag has a uniform contract

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: is enabled returns null instead of false for a missing or invalid selector

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s state-eval is enabled "#missing-xyz"   -> null ; ./b4w.ps1 -s state-eval is enabled "["   -> null

#### Expected Behavior

false, consistent with `is visible` and `is checked` (both print false for the same selectors), and consistent with the 'no match = false' rule in AC1.

#### Actual Behavior

Prints null, exit 0. In --json it becomes the string "result":"null", so even machine consumers get a different type than the sibling commands.

#### Root Cause Analysis

The "isEnabled" branch has no dedicated driver method: it calls driver.evaluateValue(selector, "function() { return !(this.disabled || this.readOnly); }") (BrowserTabToolExecutor.kt:1295-1301), which returns null when the element cannot be resolved. isVisible/isChecked go through driver methods that return false.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:1295-1301 ("isEnabled" branch)`

#### AI Suggested Improvement

- Coalesce the evaluateValue result to false when no element matches (or resolve existence first)
- Add a unit test pinning the false-for-missing-selector behaviour for all three is-* commands
- Document the return value of each assertion in its help page

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: highlight/scrollintoview missing-element errors are labeled [TIMEOUT] and dump internal Java signatures

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s state-eval highlight "#no-such-element-abc"   (and scrollintoview with the same selector; fails in ~3.8s)

#### Expected Behavior

A clear failure that names the user's command and the selector, e.g. 'highlight failed: no element matches "#no-such-element-abc"'.

#### Actual Behavior

Prints '[TIMEOUT] browser_evaluate failed: No element matches selector "#no-such-element-abc". help: Evaluate JavaScript in the page, or against the element matched by selector.' followed by the full Java signature of tab.evaluateValue(...) and its argument docs. Exit code is correct (1) and the selector is named, but the code suggests a slow operation rather than a bad selector, the leaked tool name is the internal `browser_evaluate` (not the user's `highlight`), and the signature dump reads like a debug stack.

#### Root Cause Analysis

highlight/scrollintoview are eval-based shortcuts (commands.rs:2283-2290) whose element wait throws a TimeoutException when the selector never appears; ToolErrorMapper.classify checks exception types before message patterns, so TimeoutException wins over the 'No element matches selector' text (ToolErrorCode.kt:105-111). MCPToolController.buildErrorMessage then appends the static tool spec because the failure passes isUsageError (MCPToolController.kt:1961) — even though its own comment says the spec should be reserved for usage errors.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/ToolErrorCode.kt:105-111; browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:1942-1966; cli/browser4-cli/src/commands.rs:2283`

#### AI Suggested Improvement

- Classify 'No element matches selector' as TARGET_UNAVAILABLE/NOT_FOUND with precedence over the timeout type mapping, or convert the never-appeared-selector wait timeout into the no-match error at the source
- Suppress the tool-spec/signature dump on runtime errors (as the code comment already intends)
- Use the user-facing command name (highlight/scrollintoview) instead of the internal tool name in the message

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: pushstate silently fails (exit 0) on a URL the browser refuses, and gives no warning when the pushed URL is not served

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s state-eval pushstate "https://example.com/other"  -> prints 'null' + generic hint, exit 0, location.href unchanged. Also: ./b4w.ps1 -s state-eval pushstate "/b4/definitely-missing-xyz.html" (404 on the server) -> URL printed, exit 0, no warning.

#### Expected Behavior

A cross-origin pushState throws SecurityError in the page, so the command should fail with a clear message and non-zero exit; the help should state that only the client-side URL changes (content stays), and ideally an advisory should appear when the pushed path returns 404.

#### Actual Behavior

The command printed `null` followed by a generic '💡 Expression returned null. The queried element or property may not exist on this page. Try verifying: eval "document.querySelector(...)"' hint and exited 0, while location.href was provably unchanged. The 404 push reported success with no warning at all.

#### Root Cause Analysis

pushstate is implemented as browser_evaluate of `() => { history.pushState({}, '', "<url>"); return location.href; }` (commands.rs:2268). The SecurityError aborts before the return, the expression result is null, and the CLI's null-result path treats a null result as a successful call and emits the element/property-oriented hint; it never re-checks location.href.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2263-2270 (pushstate tool_params_fn); CLI null-result hint path in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Have the CLI verify location.href after the push and fail non-zero with a targeted message when it did not change ('cross-origin URLs cannot be pushed; use goto for navigation')
- Suppress the generic element/property null hint for pushstate, or replace it with pushstate-specific guidance
- State explicitly in the help that the page content does not change and that a pushed URL is never fetched from the server

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: get box output contradicts the documented {x, y, width, height} shape

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s state-eval get box "#scroll-target"   and   ./b4w.ps1 -s state-eval get box "#scroll-target" --json

#### Expected Behavior

`--help get` says: 'box — returns the bounding box {x, y, width, height} of the first matching element.' A JSON object with those four numeric keys.

#### Actual Behavior

{"type":"ai.platon.pulsar.common.math.geometric.RectD","description":"RectD(x=24.0, y=2159.0, width=1857.0, height=24.0)"} — a Java toString wrapper. Even --json nests it as an opaque string in output.result, so scripts must string-parse the description. The coordinate space (viewport-relative; y changed 2159→824 when scrollY changed 0→1335) is also undocumented.

#### Root Cause Analysis

The "boundingBox" branch returns the RectD object itself (BrowserTabToolExecutor.kt:2342-2349) and the MCP response serializer renders unsupported types as {type, description} rather than decomposing the fields.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:2342-2349; help text in cli/browser4-cli/src/commands.rs (get command notes)`

#### AI Suggested Improvement

- Return a plain map {x, y, width, height} (numbers) instead of the RectD object, so both text and --json output match the documented contract
- Document that coordinates are viewport-relative in the get help page
- Add a test asserting the JSON shape of `get box`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Unknown/typo'd options and extra positionals are silently ignored

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s state-eval is visible "#hidden-target" --bogus-xyz  (also --fail-on-false, and an extra positional); ./b4w.ps1 -s state-eval check "#check-target" --no-snapshott (typo); ./b4w.ps1 -s state-eval open --headlesss <url>

#### Expected Behavior

An unknown option should be rejected (or at least warned about) naming the offending flag; an extra positional argument should be an error.

#### Actual Behavior

All silently accepted, exit 0. The typo'd --no-snapshot still performed the snapshot (visible in the output), and --headlesss silently fell back to the default display mode. This also masks the absence of a --fail-on-false flag (it looks accepted but does nothing). Contrast: the CLI deliberately rejects stray global flags and a trailing `-s` per the documented session rules, so rejection is not uniformly absent.

#### Root Cause Analysis

Argument parsing for commands with no declared options drops unrecognized --flags and leftover positionals instead of raising 'unknown option'. CommandDef option lists exist (e.g. check declares --no-snapshot) but unknown names are not validated against them.

#### Code Pointer

`cli/browser4-cli/src/main.rs (command argument parsing / option matching against CommandDef.options)`

#### AI Suggested Improvement

- Reject unknown options with exit 2 and a did-you-mean suggestion (closest option name)
- Reject extra positional arguments with 'unexpected argument' + usage (the wording already exists for stray global flags)
- Warn when a known option is applied to a command that does not use it

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: --help <command> --examples never loads tool-level examples and misdiagnoses it as 'is the backend running?'

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 --help click --examples  and  ./b4w.ps1 --help check --examples  (backend healthy; `status` shows Server health: UP)

#### Expected Behavior

Runnable examples, or an accurate reason when none exist.

#### Actual Behavior

'Examples for browser4-cli click (built into the CLI): …' followed by 'Tool-level examples are unavailable: is the backend running?' — and for check/focus/scrollintoview/highlight/pushstate/is visible/is enabled the entire response is 'No examples available for 'X' — is the backend running?'. Every command help page advertises this route as 'Runnable examples: browser4-cli X --help --examples'. curl http://127.0.0.1:8283/mcp/tools/specs returns 200 with {"tools":[]} on this backend.

#### Root Cause Analysis

build_tool_examples fetches GET /mcp/tools/specs and maps an empty spec list to 'is the backend running?' (main.rs:21327-21334). That endpoint only lists plugin-declared specs with a non-blank cliName (MCPToolController.kt:640-647), so it is empty on a stock build without plugins — while the backend ships examples for built-in tab tools (TabToolExamples.kt, including isVisible/isChecked/isEnabled). The CLI therefore reports a healthy backend as down.

#### Code Pointer

`cli/browser4-cli/src/main.rs:21327-21334 (build_tool_examples); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:640-647 (listCliToolSpecs)`

#### AI Suggested Improvement

- Include built-in tool specs (or at least their examples) in /mcp/tools/specs, or expose them via a second endpoint the CLI consults
- Distinguish 'spec list empty' from 'fetch failed' — never claim the backend is down when the HTTP call succeeded
- Add CLI-bundled examples for the core commands (check, uncheck, focus, scrollintoview, highlight, pushstate, is visible/enabled/checked)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: list URL column shows '-' for live sessions with loaded pages while backend_reachable is true

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 list ; ./b4w.ps1 list --verbose ; ./b4w.ps1 list --json — run while session state-eval has the fixture page loaded (page-info in the same session reports the URL).

#### Expected Behavior

The URL column shows each session's current page URL, as SKILL.md documents.

#### Actual Behavior

Every session shows '-' in the URL column (including the live one), while the same JSON payload reports "backend_reachable":true. SKILL.md defines '-' as 'the backend is unreachable or the saved session is stale' — both false here, so the column is actively misleading when choosing which session to attach to.

#### Root Cause Analysis

Unknown — the per-session URL lookup is not returning a page URL for live Browser4 sessions in this dev-mode build. Needs investigation: check the backend sessions endpoint used to populate the column and whether the CLI maps sessionId to the active tab URL (per-session page lookup may not be wired for the 'Browser4' connection type).

#### Code Pointer

`cli/browser4-cli/src/main.rs (list rendering / URL column population); backend sessions endpoint feeding it`

#### AI Suggested Improvement

- Populate the URL from the backend's per-session active tab when available
- If the lookup is unavailable, use a distinct marker (e.g. '?') and print a hint, instead of reusing the documented 'backend unreachable/stale' value
- Update SKILL.md if '-' can also mean 'URL unknown'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: highlight outlines accumulate and cannot be cleared

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s state-eval highlight "#click-target" ; ./b4w.ps1 -s state-eval highlight "#dblclick-target" ; then eval both elements' style.outline

#### Expected Behavior

Either only one element is highlighted at a time, or there is a documented way to remove the marker (e.g. `unhighlight`).

#### Actual Behavior

Both elements now carry 'rgb(255, 87, 34) solid 3px' as an inline style, persisting indefinitely. No unhighlight/clear command exists and the help does not mention that the marker survives (screenshots and visual checks after a highlight will include the orange outline).

#### Root Cause Analysis

The highlight expression sets element.style.outline directly (commands.rs:2287) with no bookkeeping of the previous target and no cleanup path or removal command.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2283-2290 (highlight expression)`

#### AI Suggested Improvement

- Clear the previous highlight (or all highlights) on each highlight call, or add `unhighlight [selector]` / `--clear`
- Document in the help that the outline is an inline style mutation that persists until removed
- Consider using an overlay element rather than mutating the page's own style

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: --help-json rejects the spaced command name and returns error payloads with exit 0

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 --help-json "is visible"  ->  {"error":"Unknown command: is visible"}, EXIT=0 ; compare ./b4w.ps1 --help "is visible" (works)

#### Expected Behavior

Same name forms accepted as text help (the displayed name 'is visible', the internal 'is-visible'), and an error payload should exit non-zero for scripts.

#### Actual Behavior

The JSON mode resolves only the internal hyphenated key; the error object is emitted with exit 0, and the JSON payload contains no output/exit-code contract for the command even when it resolves.

#### Root Cause Analysis

--help-json does not run the classify_help_target resolution that the text --help path uses, and its error branch prints JSON without setting a failing exit status.

#### Code Pointer

`cli/browser4-cli/src/main.rs (help-json handler; classify_help_target at ~21280)`

#### AI Suggested Improvement

- Route --help-json through the same classify_help_target resolution as --help
- Exit non-zero when the JSON payload is an error object
- Include the command's output contract (prints true/false) and exit codes in the JSON metadata

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: Git Bash: ./b4w.ps1 rejects arguments starting with '/' (e.g. pushstate URLs) due to MSYS path conversion

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 -s state-eval pushstate "/b4/frame-other.html"  (Git Bash on Windows)

#### Expected Behavior

The URL is pushed onto the history stack.

#### Actual Behavior

Exit 1 with: "Error: argument 'C:/Program Files/Git/b4/frame-other.html' (probably typed as '/b4/frame-other.html') was rewritten by Git Bash's MSYS path conversion before PowerShell started. ... Run this command via ./b4w.sh instead (it disables the conversion). Or export MSYS2_ARG_CONV_EXCL='*' and re-run". Retrying with MSYS2_ARG_CONV_EXCL='*' succeeded. This affects any slash-leading argument (absolute paths, pushstate URLs) and is the first thing a Git-Bash user hits on this command family.

#### Root Cause Analysis

MSYS2 converts slash-leading arguments into Windows paths before pwsh sees them; b4w.ps1 detects the rewrite and refuses (the safe behavior) but the required b4w.sh / MSYS2_ARG_CONV_EXCL workaround is documented only in CLAUDE.md and the error text, not in the CLI's own help for bash users.

#### Code Pointer

`b4w.ps1 (wrapper guard); b4w.sh (bash wrapper)`

#### AI Suggested Improvement

- Mention the b4w.sh / MSYS2_ARG_CONV_EXCL workaround in the CLI help (or a shell-quoting reference section) for bash users on Windows
- Consider having b4w.ps1 auto-retry by delegating to b4w.sh when it detects the rewrite
- Keep the current fail-loudly diagnostic — it is clear and actionable

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

**Completion Status:** Successful / Partially Successful — all 10 task steps executed end-to-end against the real backend and fixture; 6 of 8 acceptance criteria passed fully. AC1's scriptability clause failed (all assertions exit 0) and AC8 failed for check/uncheck/click/focus (silent success on non-matching selectors).

**Success Rate:** 85% — every command executed; 2 of 8 ACs have gaps (exit-code contract; explicit rejection of bad selectors), the rest verified against the page's own state.

**Issues Found:** 12

**Major Blockers:** None blocked the scenario. MockSite was already running and the local backend auto-started on port 8283. The only outright command failure was the first pushstate, due to Git Bash MSYS path mangling — retried successfully with MSYS2_ARG_CONV_EXCL='*'.

**Most Confusing Aspects:** For a first-time user: (1) `is visible/enabled/checked` print true/false but always exit 0, with no exit-code contract anywhere a script author would look — and the sibling `is enabled` prints null where the others print false; (2) `check`/`uncheck`/`click` print '✓ Checked/Clicked' for selectors that match nothing, and `focus` does nothing with no message at all; (3) missing-element failures from highlight/scrollintoview surface as '[TIMEOUT] browser_evaluate failed' with a Java signature dump, which misattributes the cause and the command name; (4) `--help <cmd> --examples` claims the backend is down while it is demonstrably up; (5) `list` shows '-' for live sessions, the documented marker for a dead backend.

**Most Valuable Improvements:** 1) Make silent failures loud: resolve the target for click/check/uncheck/focus and fail non-zero with the existing 'No element matches selector "<sel>"' message (BrowserTabToolExecutor already has the helper). 2) Add --fail-on-false (or document an exit-code contract) to the is-* assertions and document the output of each command. 3) Fix error classification/message hygiene so a missing element is not labeled TIMEOUT and never dumps internal Java signatures or tool names. 4) Return plain JSON {x,y,width,height} from `get box` and align the help text. 5) Never print a ✓ confirmation without verifying the action actually landed.

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

#### Issue 1: check/uncheck/click/focus report success on selectors that match nothing

./b4w.ps1 -s state-eval check "#no-such-element-abc" (repeat with uncheck/click/focus, and with a garbage selector `check "???"`) on http://localhost:18080/b4/mcp-tool-controller-interactive-fixture.html

#### Issue 2: Assertion commands exit 0 when the result is false/null — no scriptable failure signal, and no documentation

./b4w.ps1 -s state-eval is visible "#hidden-target"; echo $?   (also `is visible "#no-such-element-xyz"`, `is enabled "#disabled-target"`, `is checked "#check-target"`)

#### Issue 3: is enabled returns null instead of false for a missing or invalid selector

./b4w.ps1 -s state-eval is enabled "#missing-xyz"   -> null ; ./b4w.ps1 -s state-eval is enabled "["   -> null

#### Issue 4: highlight/scrollintoview missing-element errors are labeled [TIMEOUT] and dump internal Java signatures

./b4w.ps1 -s state-eval highlight "#no-such-element-abc"   (and scrollintoview with the same selector; fails in ~3.8s)

#### Issue 5: pushstate silently fails (exit 0) on a URL the browser refuses, and gives no warning when the pushed URL is not served

./b4w.ps1 -s state-eval pushstate "https://example.com/other"  -> prints 'null' + generic hint, exit 0, location.href unchanged. Also: ./b4w.ps1 -s state-eval pushstate "/b4/definitely-missing-xyz.html" (404 on the server) -> URL printed, exit 0, no warning.

#### Issue 6: get box output contradicts the documented {x, y, width, height} shape

./b4w.ps1 -s state-eval get box "#scroll-target"   and   ./b4w.ps1 -s state-eval get box "#scroll-target" --json

#### Issue 7: Unknown/typo'd options and extra positionals are silently ignored

./b4w.ps1 -s state-eval is visible "#hidden-target" --bogus-xyz  (also --fail-on-false, and an extra positional); ./b4w.ps1 -s state-eval check "#check-target" --no-snapshott (typo); ./b4w.ps1 -s state-eval open --headlesss <url>

#### Issue 8: --help <command> --examples never loads tool-level examples and misdiagnoses it as 'is the backend running?'

./b4w.ps1 --help click --examples  and  ./b4w.ps1 --help check --examples  (backend healthy; `status` shows Server health: UP)

#### Issue 9: list URL column shows '-' for live sessions with loaded pages while backend_reachable is true

./b4w.ps1 list ; ./b4w.ps1 list --verbose ; ./b4w.ps1 list --json — run while session state-eval has the fixture page loaded (page-info in the same session reports the URL).

#### Issue 10: highlight outlines accumulate and cannot be cleared

./b4w.ps1 -s state-eval highlight "#click-target" ; ./b4w.ps1 -s state-eval highlight "#dblclick-target" ; then eval both elements' style.outline

#### Issue 11: --help-json rejects the spaced command name and returns error payloads with exit 0

./b4w.ps1 --help-json "is visible"  ->  {"error":"Unknown command: is visible"}, EXIT=0 ; compare ./b4w.ps1 --help "is visible" (works)

#### Issue 12: Git Bash: ./b4w.ps1 rejects arguments starting with '/' (e.g. pushstate URLs) due to MSYS path conversion

./b4w.ps1 -s state-eval pushstate "/b4/frame-other.html"  (Git Bash on Windows)

