# Issues: javascript-evaluation

> **Source:** `20260902-202249-javascript-evaluation.full.md` | **Date:** 20260902-202249 | **Mode:** dev

## Scenario Background

### Task

**Task completed successfully.** All 8 steps were executed against the local source build via `./b4w.ps1`, with MockSite serving fixtures on `localhost:18080`. Results:

1. **`goto http://localhost:18080/generated/interactive-1.html`** — navigated (auto-connected to existing DEFAULT session). Page title "Interactive Single Page".
2. **`snapshot -i --stdout`** — returned the interactive AX tree; discovered refs `e2559` (name textbox), `e2560` (color combobox), `e2561`/`e2562` (number spinbuttons), `e2616` (Add button), `e2621` (toggle button).
3. **`eval "document.title"`** → `Interactive Single Page` ✓
4. **`eval --json "({url: document.URL, title: document.title, linkCount: document.links.length})"`** → URL/title correct; `linkCount: 0` — verified genuine (fixture has zero `<a href>` elements). Output envelope double-encodes objects (see Issue 2).
5. **`eval --file .test-sessions/page_info.js`** → `{"images":0,"links":0,"forms":0}` — verified genuine: fixture HTML contains 0 `<form>`/`<a>`/`<img>` (inputs are *not* wrapped in a form). Also confirmed `console.log()` is not captured (docs claim correct — only the return value appears).
6. **`echo '<js>' | eval --stdin`** → 5 heading texts — matches fixture exactly (1×`<h1>` + 4×`<h2>`) and matches `htmlsnapshot get all text "h1,h2"` ✓
7. **`eval --ref`** — both positional ref (`e2559`) and `--ref` flag forms work with arrow functions: `element => element.tagName` → `INPUT`; `element => element.id` (e2559) → `name`; text of `e2616` / CSS `#addButton` → `Add`; `#name` aria-label → `Enter your name`. Wrong syntax (`this.tagName`) returns `null` with a helpful arrow-function hint (though the auto-suggestion is malformed — Issue 4).
8. **Consistency** — cross-verified five ways (eval inline / `--json` / `--file` / `--stdin` / `--ref`) plus `htmlsnapshot` capture and direct fixture-source inspection; all agree on title, URL, 0 links/images/forms, 5 headings, and element identity (`e2559` ↔ `#name`, `e2616` ↔ `#addButton`).

No workarounds were required; no browser automation outside browser4-cli was used. Temporary file `page_info.js` was created under `.test-sessions/` as required.

### Execution Context

**Key Commands:**

```
./b4w.ps1 help                                  # command reference
./b4w.ps1 help eval                             # eval options & examples
./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"
./b4w.ps1 snapshot -i --stdout                  # find refs: e2559/e2560/e2561/e2562/e2616/e2621
./b4w.ps1 eval "document.title"                 # inline → "Interactive Single Page"
./b4w.ps1 eval --json "({url: document.URL, title: document.title, linkCount: document.links.length})"
./b4w.ps1 eval "({url: ..., ...})"              # compare non-json object output
./b4w.ps1 eval --json "document.title"          # compare scalar output shape
./b4w.ps1 eval --file .test-sessions/page_info.js   # images/links/forms counts + console.log probe
echo '<headings js>' | ./b4w.ps1 eval --stdin   # heading text extraction
./b4w.ps1 eval "element => element.tagName" e2559            # positional ref
./b4w.ps1 eval --ref e2559 "element => element.getAttribute('name')"
./b4w.ps1 eval --ref e2616 "element => element.textContent"
./b4w.ps1 eval --ref e2559 "this.tagName"       # wrong-form probe (got helpful hint)
./b4w.ps1 eval "element => element.id" e2559; ... # CSS-selector cross-check (#addButton, #name)
./b4w.ps1 eval "element => element.tagName" e9999            # stale-ref probe
./b4w.ps1 eval --json --ref e9999 "..."         # stale ref in JSON mode (status ok + null)
./b4w.ps1 eval                                  # missing-argument error probe
./b4w.ps1 nonexistent-command-xyz               # exit-code probe
cli/browser4-cli/target/debug/browser4-cli.exe eval   # direct binary exit-code probe (exit 2)
./b4w.ps1 htmlsnapshot; htmlsnapshot get all attr/img/a/h1,h2   # independent ground-truth checks
```

**Key decisions:** read `SKILL.md` + `help eval` before acting; used the docs' recommended `--file`/`--stdin` for multi-line JS; treated refs as single-use and re-snapshotted where needed; cross-checked all-zero counts against the fixture HTML source (`browser4-tests/.../static/generated/interactive-1.html`) before concluding they were genuine, since an "interactive" page returning 0 forms/links/images initially looked like a bug.

**Notable observations:** daemon/backend auto-start worked with zero setup friction; a DEFAULT browser session from a prior run was reused (expected). Exit codes of errors are swallowed by the `b4w.ps1` wrapper (Issue 1); `--json` envelopes double-encode objects (Issue 2); stale refs return `null`/`status: ok` rather than an error (Issue 3); the arrow-function "Did you mean" suggestion is invalid JS (Issue 4).

---

## Issues Found (5 issues)

### Issue 1: b4w.ps1 wrapper always exits 0 — CLI failure exit codes are swallowed

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run a failing command through the dev wrapper: `./b4w.ps1 nonexistent-command-xyz; echo exit=$?` prints exit=0, and `./b4w.ps1 eval; echo exit=$?` prints the error 'A JavaScript expression is required...' yet still exits 0. The real binary exits 2 for the same invocations: `cli/browser4-cli/target/debug/browser4-cli.exe eval; echo exit=$?` prints exit=2.

#### Expected Behavior

The wrapper should propagate the browser4-cli exit code (nonzero on usage/tool errors) so scripts, CI, set -e, && chaining, and agent tooling can detect failures. SKILL.md itself documents `&&`-chained patterns (e.g. `click "#alertBtn" && browser4-cli dialog-accept`) that depend on this.

#### Actual Behavior

The wrapper exits 0 for every command, including usage errors (unknown command, missing eval expression) and tool failures. Failure detection via exit code silently breaks — every error looks like success.

#### Root Cause Analysis

b4w.ps1 is executed by Git Bash through its `#!/usr/bin/env pwsh` shebang. The script invokes the CLI (or `cargo run`) but ends with `Set-Location $OriginalCwd` and never forwards `$LASTEXITCODE` via `exit $LASTEXITCODE`, so the pwsh process exits 0 regardless of the CLI's status. Verified empirically: the direct .exe returns exit=2 for the same usage errors.

#### Code Pointer

`b4w.ps1 — final lines after the `Invoke-Expression "& `"$Exe`" $SafeArgsStr"` / `cargo run` invocation (replace trailing `Set-Location $OriginalCwd` block with `Set-Location $OriginalCwd; exit $LASTEXITCODE` or equivalent).`

#### AI Suggested Improvement

- Append `exit $LASTEXITCODE` after restoring the working directory so pwsh forwards the native command's status (also ensure early-return branches like the build/startup spinner paths propagate failures).
- Add a small CI/dev check that runs `./b4w.ps1 eval` (no args) and asserts a nonzero exit code.
- Document in SKILL.md/CLAUDE.md that scripted automation should use the wrapper's propagated exit codes, and keep the `&&` chaining examples correct once propagation exists.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed at b4w.ps1:823-839 — the main CLI path (the default case, covering all `eval`/`snapshot` etc. invocations) ends with `Set-Location $OriginalCwd` and no `exit $LASTEXITCODE`, unlike the `coworker`/`sc`/`test`/`build` branches which already propagate. This blocks every exit-code-dependent pattern in SKILL.md and the e2e expectations in Issues 2-3, so fix it first (also verify b4w.sh propagates identically for shell parity).

---

### Issue 2: eval --json envelope stores object results as double-encoded strings; scalars lose type fidelity

**Severity:** Medium
**Category:** Product

#### Reproduction

`./b4w.ps1 eval --json "({url: document.URL, title: document.title, linkCount: document.links.length})"` returns an envelope whose `output.result` is the escaped JSON string `"{\"url\":\"http://localhost:18080/generated/interactive-1.html\",...}"` — a second JSON.parse is required to get the object. `./b4w.ps1 eval --json "document.title"` returns `output.result` = `"Interactive Single Page"` (plain string). Without --json, the same object prints as clean top-level JSON: `{"url":...}`.

#### Expected Behavior

In machine-readable `--json` mode, `output.result` should hold a typed JSON value: an object expression yields a JSON object, a number yields a JSON number, null yields null — so a consumer can parse the envelope once and use the value directly.

#### Actual Behavior

`output.result` is always a JSON *string* (from `json!(&result)` where result is the raw backend string). Object/array results are therefore double-encoded (escaped JSON text inside the string), while numbers/booleans/null arrive as indistinguishable strings ("2", "true", "null"). A consumer cannot uniformly parse the envelope without knowing the expression's return type in advance; uniform `JSON.parse` silently yields the wrong shape for objects.

#### Root Cause Analysis

In handle_tool_command_with_options (main.rs:5304-5330), the `--json` branch correctly parses the backend result into a typed `serde_json::Value` (`json_val`) but only uses it for the stdout print; the envelope field is populated separately at main.rs:5324 with `json_field("result", json!(&result))` using the raw string. The parsed value is discarded for envelope purposes.

#### Code Pointer

`cli/browser4-cli/src/main.rs:5324 (handle_tool_command_with_options, eval --json branch) — emit `json_field("result", json_val.clone())` (or the parsed value) instead of `json!(&result)` when eval_json is active.`

#### AI Suggested Improvement

- When `eval_json` is set, populate the envelope's `result` field from the parsed `json_val` (objects as objects, numbers as numbers, strings as strings) instead of the raw string.
- Add a unit test asserting the envelope type fidelity for: object expression, number expression, boolean/null expression, and plain-string expression.
- If a legacy consumer depends on string-encoded results, consider adding an explicit `resultRaw` string field and documenting the envelope schema rather than keeping ambiguous double-encoding.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed at main.rs:5304-5324 — the parsed `json_val` is used only for stdout while the envelope field is populated from the raw string, so `--json` machine output is double-encoded/typeless. Note the envelope `result` is a raw-string convention across all tools (cf. main.rs:5336), so either document the semantics change or add a typed sibling field; coordinate the schema with Issue 3's proposed envelope additions and update envelope-asserting tests in one pass.

---

### Issue 3: Stale/nonexistent eval ref silently returns null with status ok — indistinguishable from a legitimate JS null

**Severity:** Medium
**Category:** Product

#### Reproduction

After `snapshot -i`, run `./b4w.ps1 eval "element => element.tagName" e9999` (e9999 does not exist) → prints `null` plus a generic 'Expression returned null' hint; `./b4w.ps1 eval --json --ref e9999 "element => element.tagName"` → `{"status":"ok","command":"eval","output":{"result":"null","ref":"e9999"}}`. The same null shape is returned for a legitimate case: `./b4w.ps1 eval --ref e2559 "element => element.getAttribute('name')"` → `null` (the input genuinely has no `name` attribute).

#### Expected Behavior

When the ref/selector cannot be resolved to a DOM element, the command should report an explicit, distinguishable error (e.g. 'element for ref e9999 not found', nonzero exit, and a `status` other than ok in --json mode) rather than a successful null result.

#### Actual Behavior

A failed element lookup is reported identically to an expression that legitimately evaluates to null: human mode prints the same hint (which even suggests verifying a CSS selector via document.querySelector, misleading for ref targets), and JSON mode reports status ok with result null. Machine consumers and first-time users cannot tell a stale ref from a real null; given refs are documented as ephemeral (SKILL.md warns they go stale after any interaction), this is a common failure mode that should be loudly surfaced.

#### Root Cause Analysis

The backend resolves the ref → element and, when resolution fails, evaluates/handles it as null instead of raising (driver element resolution returns null, and the eval executor passes it through). The CLI's null handling at main.rs:5240-5278 deliberately treats the literal string "null" as data and only appends a heuristic hint in human mode; in JSON mode nothing distinguishes the failure. Investigation needed: confirm the exact driver method (likely evaluateValueDetail with selector path in BrowserTabToolExecutor, which calls driver.evaluateValueDetail(selector, functionDeclaration)) and where a missing backend node id could raise instead.

#### Code Pointer

`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:1538-1577 ("eval"/"evaluateValue" branches) and cli/browser4-cli/src/main.rs:5240-5278 (null handling).`

#### AI Suggested Improvement

- In the backend, when a ref (backend node id) or CSS selector resolves to no element, throw an explicit error (e.g. 'Element not found for ref e9999') that surfaces as a failed tool call with nonzero exit.
- If that is not feasible (element may legitimately be absent), add a `refResolved: false` field to the JSON result and a distinct human-mode message ('ref e9999 not found — re-run snapshot to refresh refs') instead of the generic property-may-not-exist hint.
- For JSON mode specifically, never report status ok when the requested ref could not be resolved.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Confirmed on the CLI side (main.rs:5240-5278): ref-resolution failure and a legitimate JS null arrive as the identical `"null"` string, and JSON mode adds no discriminator; the fix must be backend-side in BrowserTabToolExecutor so resolution failure is distinguishable (explicit error preferred, matching $eval conventions; `refResolved: false` acceptable fallback). This shares the exact hint block with Issue 4 — implement both together, and note that asserting the expected nonzero exit through the wrapper requires Issue 1 to land first.

---

### Issue 4: eval --ref wrong-form hint suggests invalid JS: 'element => element.this.tagName'

**Severity:** Low
**Category:** UX

#### Reproduction

Run `./b4w.ps1 eval --ref e2559 "this.tagName"` → the CLI prints: '💡 Expression returned null... Did you mean: eval "element => element.this.tagName" --ref …?'

#### Expected Behavior

The suggested replacement should be valid JS that will actually work, e.g. `eval "element => element.tagName" --ref e2559`.

#### Actual Behavior

The auto-suggestion blindly prefixes the user's raw expression: `element => element.this.tagName`. Following it produces a TypeError (`element.this` is undefined), so the user's second attempt fails again with the same confusing null result.

#### Root Cause Analysis

main.rs:5267 builds the suggestion with string interpolation `element => element.{}` using the original expression, without stripping a leading `this.` / `this` token (and without limiting suggestions to simple property-access shapes).

#### Code Pointer

`cli/browser4-cli/src/main.rs:5262-5270 (eval null-diagnostic hint block).`

#### AI Suggested Improvement

- When the expression starts with `this.`, strip the `this.` prefix before building the suggestion (also handle a bare `this`).
- Only offer the 'Did you mean' rewrite for simple property-access patterns; otherwise just show the generic arrow-function guidance with a working example.
- Add a unit test asserting the suggested expression is syntactically valid (e.g. contains no `this.` after `element =>`).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed at main.rs:5262-5270 — the suggestion blindly interpolates the raw expression (`element.this.tagName`), which is a guaranteed TypeError; strip a leading `this.`/`this` or restrict the rewrite to simple property shapes. Trivial fix with an obvious unit test; land it in the same change as Issue 3 since they edit the same lines.

---

### Issue 5: Eval scenario fixture 'interactive-1.html' contains no form, links, or images — verification counts are all zero and give weak signal

**Severity:** Low
**Category:** Product

#### Reproduction

Scenario steps 4-6 against http://localhost:18080/generated/interactive-1.html: `eval --json` reports linkCount 0, `eval --file page_info.js` reports {"images":0,"links":0,"forms":0}. Verified against fixture source browser4-tests/pulsar-tests-common/src/main/resources/static/generated/interactive-1.html: it contains 0 `<form>`, 0 `<a href>`, 0 `<img>` elements — the textbox/select/spinbutton/buttons are not wrapped in any `<form>`.

#### Expected Behavior

An 'interactive' fixture used to verify eval counting should contain a nonzero, easily cross-checkable number of forms, links, and images (e.g. a real <form> wrapping the name input, a couple of anchor links, one or two images) so correct implementations produce distinguishable non-zero results.

#### Actual Behavior

Every count method returns 0 and 0==0 across methods — internally consistent, so the step-8 consistency verification passes, but the checks carry no positive signal: a buggy implementation that always returns zero (or fails to count) would pass undetected. Additionally, a first-time user sees '0 forms' on a page full of form controls and must do extra work to determine the result is genuine rather than a broken eval.

#### Root Cause Analysis

Fixture authoring gap: interactive-1.html models controls as standalone elements outside any <form> and omits links/images entirely. This is not a CLI bug (eval results are correct), but it weakens the scenario's verification power and the eval help/UX cannot compensate.

#### Code Pointer

`browser4-tests/pulsar-tests-common/src/main/resources/static/generated/interactive-1.html (fixture markup; wrap controls in a <form>, add 2-3 <a href> and 1-2 <img>).`

#### AI Suggested Improvement

- Wrap the 'User Information' inputs in a real `<form>` and add a few links/images to the fixture so eval counts are nonzero and cross-checkable against the source.
- If counts must remain zero in some scenarios, the scenario text should call that out so testers do not mistake zero for an eval failure.
- Consider a dedicated counting fixture (e.g. 3 forms / 5 links / 2 images) used by the eval-modes scenario for stronger step-8 consistency verification.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified the fixture genuinely has zero forms/links/images, so the eval scenario's count checks carry no positive signal. Prefer additive changes (extra links/images + a real `<form>` wrapper is harmless to the id-based selectors other scenarios use) or a dedicated counting fixture, but first confirm no e2e scenario asserts on the fixture's current structure or element ordering before mutating this shared resource.

---

## Overall Assessment

**Completion Status:** Successful — all 8 scenario steps completed; every eval invocation mode (inline, --json, --file, --stdin, --ref) worked as documented, and results were verified consistent with each other, with htmlsnapshot extraction, and with the fixture source HTML. Findings are usability/reliability refinements, not blockers.

**Success Rate:** 100%

**Issues Found:** 5

**Most Confusing Aspects:** For a first-time user: (1) eval --json output shapes differ by result type — objects come back double-encoded inside the envelope, scalars as plain strings, so machine parsing is ambiguous; (2) a stale ref returns 'null' with status ok — indistinguishable from a legitimate JS null, which is especially confusing because SKILL.md stresses refs are ephemeral; (3) an 'interactive' page whose form-looking controls live outside any <form> returns 0 forms/links/images, initially looking like an eval bug; (4) all failures through ./b4w.ps1 exit 0, so scripts cannot tell success from error; (5) the wrong-arrow-function hint suggests 'element => element.this.tagName', which is invalid JS. The doc warnings (console.log not captured, arrow functions required with --ref) were accurate and helpful, and the built-in hints otherwise guided recovery well.

**Most Valuable Improvements:** (1) Fix --json envelope type fidelity (emit parsed values, not raw strings) so eval results are uniformly machine-parseable; (2) propagate exit codes through b4w.ps1 with 'exit $LASTEXITCODE' so failures are detectable in scripts/agents; (3) surface ref-not-found as an explicit error (or at least distinct JSON field/message) instead of a silent null; (4) fix the invalid 'element => element.this...' auto-suggestion; (5) enrich the eval-scenario fixture with real forms/links/images so counting checks produce nonzero, distinguishable results.

**Usability Rating:** 7/10

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

#### Issue 1: b4w.ps1 wrapper always exits 0 — CLI failure exit codes are swallowed

Run a failing command through the dev wrapper: `./b4w.ps1 nonexistent-command-xyz; echo exit=$?` prints exit=0, and `./b4w.ps1 eval; echo exit=$?` prints the error 'A JavaScript expression is required...' yet still exits 0. The real binary exits 2 for the same invocations: `cli/browser4-cli/target/debug/browser4-cli.exe eval; echo exit=$?` prints exit=2.

#### Issue 2: eval --json envelope stores object results as double-encoded strings; scalars lose type fidelity

`./b4w.ps1 eval --json "({url: document.URL, title: document.title, linkCount: document.links.length})"` returns an envelope whose `output.result` is the escaped JSON string `"{\"url\":\"http://localhost:18080/generated/interactive-1.html\",...}"` — a second JSON.parse is required to get the object. `./b4w.ps1 eval --json "document.title"` returns `output.result` = `"Interactive Single Page"` (plain string). Without --json, the same object prints as clean top-level JSON: `{"url":...}`.

#### Issue 3: Stale/nonexistent eval ref silently returns null with status ok — indistinguishable from a legitimate JS null

After `snapshot -i`, run `./b4w.ps1 eval "element => element.tagName" e9999` (e9999 does not exist) → prints `null` plus a generic 'Expression returned null' hint; `./b4w.ps1 eval --json --ref e9999 "element => element.tagName"` → `{"status":"ok","command":"eval","output":{"result":"null","ref":"e9999"}}`. The same null shape is returned for a legitimate case: `./b4w.ps1 eval --ref e2559 "element => element.getAttribute('name')"` → `null` (the input genuinely has no `name` attribute).

#### Issue 4: eval --ref wrong-form hint suggests invalid JS: 'element => element.this.tagName'

Run `./b4w.ps1 eval --ref e2559 "this.tagName"` → the CLI prints: '💡 Expression returned null... Did you mean: eval "element => element.this.tagName" --ref …?'

#### Issue 5: Eval scenario fixture 'interactive-1.html' contains no form, links, or images — verification counts are all zero and give weak signal

Scenario steps 4-6 against http://localhost:18080/generated/interactive-1.html: `eval --json` reports linkCount 0, `eval --file page_info.js` reports {"images":0,"links":0,"forms":0}. Verified against fixture source browser4-tests/pulsar-tests-common/src/main/resources/static/generated/interactive-1.html: it contains 0 `<form>`, 0 `<a href>`, 0 `<img>` elements — the textbox/select/spinbutton/buttons are not wrapped in any `<form>`.

