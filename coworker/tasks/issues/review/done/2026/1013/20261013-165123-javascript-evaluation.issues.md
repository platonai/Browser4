# Issues: javascript-evaluation

> **Source:** `20261013-165123-javascript-evaluation.issues.json` | **Date:** 20261013-165123 | **Mode:** dev

## Scenario Background

### Task

All seven task steps completed successfully and every result was cross-verified against the raw fixture HTML and independent extraction paths.

| Step | Command | Result |
|---|---|---|
| 0. MockSite | `curl http://localhost:18080/generated/interactive-1.html` | HTTP 200 (already running; no start needed) |
| 1. Navigate | `./b4w.ps1 -s eval-task goto "http://localhost:18080/generated/interactive-1.html"` | Dev backend auto-started (10.8 s), session `eval-task` created, page loaded ("Interactive Single Page") |
| 2. Interactive snapshot | `./b4w.ps1 -s eval-task snapshot -i` | 9 refs discovered: `e3` name textbox, `e4` color select, `e5`/`e6` numbers, `e45` Add, `e53` Toggle, `e60`/`e63`/`e66` footer links |
| 3. Title eval | `eval "document.title"` | `Interactive Single Page` ✓ |
| 4. JSON metadata | `eval --json "{url: document.URL, title: document.title, links: document.querySelectorAll('a').length}"` | `{"status":"ok",...,"result":{"url":"…interactive-1.html","title":"Interactive Single Page","links":3}}` ✓ |
| 5. `--file` | `eval --file <scratch>/page_info.js` | First (console.log-only) run printed `""` + caveat; after adding `return` → `{"images":2,"links":3,"forms":1}` ✓ |
| 6. `--stdin` | heredoc → `eval --stdin` | `["Welcome to the Interactive Page","📋 User Information","📊 Preferences","🧮 Quick Calculator","🎯 Dynamic Toggle"]` ✓ (emoji preserved) |
| 7. `--ref` | `eval "element => ({tag: element.tagName, id: element.id, placeholder: element.placeholder, ariaLabel: element.getAttribute('aria-label')})" --ref e3` | `{"tag":"INPUT","id":"name","placeholder":"Type here...","ariaLabel":"Enter your name"}` ✓ |

**Verification (step 8):** the fixture ground truth is 3 links, 2 images, 1 form, 5 headings (1×h1 + 4×h2), title "Interactive Single Page". Every eval method agreed with it, and independent non-eval paths agreed too: `page-info` (title/URL), `htmlsnapshot get all text "h2"` → the same 4 `h2` headings, `htmlsnapshot get all attr "a" href` → 3, `attr "form" data-testid` → 1, `attr "img" alt` → 2, and `document.links/images/forms.length` collections matched the `querySelectorAll` counts exactly. `eval --json "document.title"` returned a quoted string per the documented envelope.

### Execution Context

**Key Commands:**

**Major steps / decisions:**
- Verified MockSite with `curl` before invoking the CLI (documented pre-condition), then used an isolated named session `-s eval-task` — a documented, global-flag-before-command workflow — to avoid clashing with the stale `form-fill-eval` session found in `list`. That worked through `b4w.ps1` unchanged.
- The first command auto-started the dev backend (10.8 s) and auto-created the session; no manual setup was needed (matches docs).
- Ref discovery used the saved snapshot YAML (1 KB) rather than `--stdout`, per the "don't cat/dump snapshots" guidance.
- Shell quoting: heredoc via `--stdin` and an absolute Windows path via `--file` both passed the Git-Bash→pwsh boundary cleanly — the documented Windows quoting mitigations work as advertised.
- Negative tests were run deliberately to check documented caveats: non-arrow expression with `--ref` (returned `null` + an actionable "did you mean `element => element.textContent`" hint — matches docs) and a never-valid ref `e999` (exit 1, ~3 s, actionable message — but tagged `[TIMEOUT]`, see Issue 1).

**Workarounds required:** one — the natural "compute and console.log" `page_info.js` produced `""` (console.log is not captured, as documented). Per the documents, the script was updated to also `return` the object; the results then printed correctly. The CLI's *first* hint said the right thing; its follow-up diagnosis did not (Issue 2).

**Artifacts** (all under `.test-sessions/20261013T0154334271610Z/eval-task/`): `fixture-interactive-1.html` (ground truth), `page_info.js` (final script), `badref.out`, `badref-json.out` (error evidence). The session was closed at the end (`Session closed. Browser terminated.`); the shared backend was left running.

---

## Issues Found (5 issues)

### Issue 1: Unresolvable eval ref fails with error code [TIMEOUT] because classification scans the appended help text containing "waitTimeout"

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s eval-task goto "http://localhost:18080/generated/interactive-1.html"
./b4w.ps1 -s eval-task eval "element => element.textContent" --ref e999     # any never-valid ref
./b4w.ps1 -s eval-task eval --json "element => element.textContent" --ref e999

#### Expected Behavior

A stale/unknown ref is a permanent, non-retryable failure, so the error should carry a not-found/invalid code (or at least not TIMEOUT), keeping the actionable message ("re-run snapshot") as the payload.

#### Actual Behavior

Exit code 1 in ~3 s with: "[TIMEOUT] browser_evaluate failed: Element not found for ref backend:999. Refs expire after page changes — re-run `snapshot` to get fresh refs. help: Evaluate JavaScript in the page, ..." — TIMEOUT is declared retryable=true (HTTP 504, hint "Retry with a longer timeout"), so machine consumers are told to retry a ref that can never resolve.

#### Root Cause Analysis

Verified chain: (1) BrowserTabToolExecutor throws IllegalArgumentException(elementNotFoundMessage(selector)) when a ref cannot resolve (BrowserTabToolExecutor.kt:2286). (2) isUsageError() returns true for ANY IllegalArgumentException (MCPToolController.kt:2027), so buildErrorMessage() appends the tool description + full signature + arguments help (MCPToolController.kt:1961-1963). (3) The single-argument errorResponse() used on this path (MCPToolController.kt:1685 for the MCP dispatch, also 1250) classifies the COMPOSED message via ToolErrorMapper.classifyMessage (MCPToolController.kt:1908-1915). The help text contains the evaluate tool's parameter name waitTimeout ("waitTimeout: How long to wait ..."), whose lowercase form contains the substring "timeout", matching the pattern `"timed out" in text || "timeout" in text` (ToolErrorCode.kt:158) -> ToolErrorCode.TIMEOUT. The classification never looks at the throwable on this path (unlike line 1484, which passes exception.cause), and the raw not-found text itself contains no timeout wording.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:1685 (errorResponse(buildErrorMessage(...))) and browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/ToolErrorCode.kt:158 (ToolErrorMapper.classifyMessage)`

#### AI Suggested Improvement

- Classify from the throwable before help text is appended: make the call sites at MCPToolController.kt:1685/1250 pass the exception like line 1484 does (`errorResponse(buildErrorMessage(toolName, exception), exception.cause)`), or move classification ahead of the help append.
- Make the timeout pattern token-aware (e.g. regex `\btimeout\b` or match "timed out after") so parameter names like `waitTimeout` can never trigger TIMEOUT.
- Give ref-not-found a dedicated non-retryable code (or map it to INVALID_ARGUMENT/CDP_ERROR) instead of letting prose decide; retryable=true for a stale ref is semantically wrong.
- Add a regression test asserting the errorCode for a stale-ref message is not TIMEOUT.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Empty eval result from a console.log-only script triggers a wrong "page context is stale — run goto first" diagnosis and echoes the whole script

**Severity:** Medium
**Category:** UX

#### Reproduction

Write page_info.js that computes and console.logs (no return), then:
./b4w.ps1 -s eval-task eval --file "<path>/page_info.js"

#### Expected Behavior

The output states the real cause (console.log output is not captured; the script returned no value) and suggests ending with a return value. No page-state blame, no full-script echo.

#### Actual Behavior

Prints `""` then: "💡 Expression returned empty/undefined. This could mean: - The page context is stale (try: goto <url>) - The JS expression returned undefined or "" ..." followed by "`<the ENTIRE file contents>` returned empty — the page may have changed since your last navigation. Run `goto` first to restore the page context." The page was fine; the script simply had no return value. Following the advice would needlessly re-navigate (destroying any page state).

#### Root Cause Analysis

In cli/browser4-cli/src/main.rs:8202-8222 the is_empty_result branch prints a generic cause list that names page staleness first, and when the expression text contains "document.title", "document.body" or "document.querySelector" it prints the stronger "page may have changed ... run goto" message. The script contained document.querySelectorAll(...), which CONTAINS the substring "document.querySelector", so the most misleading hint fires precisely for common extraction snippets. The branch also interpolates the full expression, which for --file/--stdin is the whole script. The CLI already printed the correct diagnosis one line earlier ("console.log() output is not captured"), so the confounding advice is purely additive noise.

#### Code Pointer

`cli/browser4-cli/src/main.rs:8202 (is_empty_result branch; substring trigger at 8213, script echo at 8218-8221)`

#### AI Suggested Improvement

- When a console.log/no-return script is detected (the existing console.log reminder fired), suppress the stale-page cause list and print only "add `return`" guidance.
- Tighten the trigger so `document.querySelectorAll` no longer matches the `document.querySelector` substring (match `document.querySelector(` or use word boundaries).
- Do not interpolate the full expression for --file/--stdin input; truncate to the first line or N characters.
- Order candidate causes by likelihood for eval: "script produced no value" first, "page changed" last (or behind --verbose).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: eval of JS undefined prints "" — indistinguishable from an empty string, contradicting the documented null contract

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s eval-task eval "undefined"
./b4w.ps1 -s eval-task eval "''"
./b4w.ps1 -s eval-task eval "null"
./b4w.ps1 -s eval-task eval --json "undefined"

#### Expected Behavior

Per `help eval` and skills/browser4-cli/references/eval.md: "Return values are always printed: `null` for JS null/undefined, `""` for empty string". Undefined should render as null (or at minimum be distinguishable from an empty string).

#### Actual Behavior

`undefined` -> `""` (JSON: ""); `''` -> `""` (identical output, same hint); only `null` -> `null`. A script/agent cannot tell "evaluated to undefined" from "evaluated to empty string", and the documented null mapping does not hold for undefined.

#### Root Cause Analysis

The CLI's own contract expects undefined to arrive as the literal string "null" (comment at cli/browser4-cli/src/main.rs:8103-8105), but the backend transports an undefined JsEvaluation as an empty string (its comment at main.rs:8112 even says "an empty result for undefined" — the two comments contradict each other). Since the null-aware branch keys on `result == "null"` (main.rs:8107), undefined falls into the empty-string branch (main.rs:8137-8138). The rendering originates in the backend transport layer, ToolResultTextRenderer.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/ToolResultTextRenderer.kt (JsEvaluation/undefined rendering) and cli/browser4-cli/src/main.rs:8103-8141 (null/empty handling)`

#### AI Suggested Improvement

- Render JS undefined as the literal "null" in ToolResultTextRenderer so it reaches the CLI's null branch (matching the documented contract).
- If undefined is intentionally mapped to "", update `help eval` and eval.md to state '"" for undefined and empty string' and note they are indistinguishable in plain output.
- Add CLI tests asserting distinct output for `undefined`, `null`, and `''`, in both plain and --json modes.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: htmlsnapshot get all uses a different, nagging message template when exactly one element matches

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s eval-task htmlsnapshot get all text "h1"      # 1 match
./b4w.ps1 -s eval-task htmlsnapshot get all attr "a" href   # 3 matches

#### Expected Behavior

Uniform count feedback, e.g. "1 element matched.", with no unsolicited advice when the selector intentionally matches a unique element.

#### Actual Behavior

1 match prints: "1 result found for "h1". If you expected more, the selector may be too narrow - try `htmlsnapshot inspect "h1"` to discover alternatives." whereas n>1 prints "3 elements matched." Terminology is inconsistent ("result found" vs "elements matched"), and the narrow-selector warning is noise for h1/form/select/#id-style selectors.

#### Root Cause Analysis

cli/browser4-cli/src/main.rs:9376-9384 gives the Some(1) case its own template with the inspect hint while Some(n) uses "{} elements matched." The adjacent comment (9360-9364) says a single result is "a perfectly valid outcome ... never imply the page changed", yet the singular arm still editorializes.

#### Code Pointer

`cli/browser4-cli/src/main.rs:9377 (Some(1) arm of the get-all count message)`

#### AI Suggested Improvement

- Use the same "N element(s) matched." message for every count, including 1.
- If the inspect hint has value, move it behind a tip/verbose flag instead of printing it on every single-match result.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Ref-not-found runtime errors are treated as usage errors and get a ~30-line tool help dump appended

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s eval-task eval "element => element.textContent" --ref e999

#### Expected Behavior

The actionable failure leads ("Element not found for ref backend:999. Refs expire after page changes — re-run `snapshot` to get fresh refs.") followed by at most a short hint; a "wrong syntax" help block is irrelevant because the invocation was valid.

#### Actual Behavior

The first line continues with "help: Evaluate JavaScript in the page, ...", then the full signature (expression/selector/functionDeclaration/awaitPromise/waitSelector/waitTimeout), the Arguments list, and Examples — roughly 30 lines that bury the actionable message and imply the caller misused the tool. (Examined with `head -15`: all 15 lines were help text.)

#### Root Cause Analysis

elementNotFoundMessage failures are thrown as IllegalArgumentException (BrowserTabToolExecutor.kt:2286, same pattern at 799); isUsageError() at MCPToolController.kt:2026-2036 returns true for any IllegalArgumentException, so buildErrorMessage() appends exception.help (the static tool description/signature, MCPToolController.kt:1961-1963). A lookup miss on valid syntax is thereby mislabeled a usage error.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:2026 (isUsageError) and browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:774-781 (elementNotFoundMessage)`

#### AI Suggested Improvement

- Throw a dedicated coded failure (e.g. a CodedFailure with a non-retryable not-found code) instead of IllegalArgumentException, so isUsageError() no longer classifies runtime lookups as usage mistakes.
- Only append the signature/examples for genuine argument-shape errors (missing/unknown/mistyped arguments), not for ref/selector misses.
- If help must stay on errors, cap it to the one-line description (or gate the full dump behind --help <tool>).

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

**Completion Status:** Successful — all 7 task steps completed and cross-verified against the raw fixture HTML plus independent htmlsnapshot/page-info reads. The only adaptation was the documented console.log caveat when running eval --file (the script was updated to also return the value).

**Success Rate:** 95% — every task step produced correct output; one retry was needed for the --file script because console.log is not captured (documented, but the CLI's follow-up hint misattributes the cause).

**Issues Found:** 5

**Most Confusing Aspects:** For a first-time user: (1) the natural 'compute and console.log' script — the very first thing one writes for eval --file — yields "" plus a wrong 'page may have changed, run goto first' diagnosis that could send you to re-navigate for no reason; (2) a stale ref fails with the error code [TIMEOUT] (retryable, HTTP 504) instead of a not-found classification, and dumps the tool help as if the call syntax were wrong; (3) eval of JS undefined prints "" — indistinguishable from an empty string — while both `help eval` and eval.md promise null.

**Most Valuable Improvements:** Classify tool failures from the throwable (or at least before help text is appended) and make the timeout pattern substring-safe; tailor the empty-eval hint to the detected console.log/no-return case instead of blaming page state and echoing the whole script; render JS undefined as null (or document the actual mapping and the undefined-vs-empty ambiguity); unify the htmlsnapshot get-all count message so a single match is not scolded; keep the tool signature dump out of runtime lookup errors.

**Usability Rating:** 8/10

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

#### Issue 1: Unresolvable eval ref fails with error code [TIMEOUT] because classification scans the appended help text containing "waitTimeout"

./b4w.ps1 -s eval-task goto "http://localhost:18080/generated/interactive-1.html"
./b4w.ps1 -s eval-task eval "element => element.textContent" --ref e999     # any never-valid ref
./b4w.ps1 -s eval-task eval --json "element => element.textContent" --ref e999

#### Issue 2: Empty eval result from a console.log-only script triggers a wrong "page context is stale — run goto first" diagnosis and echoes the whole script

Write page_info.js that computes and console.logs (no return), then:
./b4w.ps1 -s eval-task eval --file "<path>/page_info.js"

#### Issue 3: eval of JS undefined prints "" — indistinguishable from an empty string, contradicting the documented null contract

./b4w.ps1 -s eval-task eval "undefined"
./b4w.ps1 -s eval-task eval "''"
./b4w.ps1 -s eval-task eval "null"
./b4w.ps1 -s eval-task eval --json "undefined"

#### Issue 4: htmlsnapshot get all uses a different, nagging message template when exactly one element matches

./b4w.ps1 -s eval-task htmlsnapshot get all text "h1"      # 1 match
./b4w.ps1 -s eval-task htmlsnapshot get all attr "a" href   # 3 matches

#### Issue 5: Ref-not-found runtime errors are treated as usage errors and get a ~30-line tool help dump appended

./b4w.ps1 -s eval-task eval "element => element.textContent" --ref e999

