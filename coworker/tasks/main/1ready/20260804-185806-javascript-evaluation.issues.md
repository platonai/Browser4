# Issues: javascript-evaluation

> **Source:** `20260804-185806-javascript-evaluation.full.md` | **Date:** 20260804-185806 | **Mode:** dev

## Scenario Background

### Task

All 8 evaluation steps completed successfully. The browser4-cli `eval` command supports four invocation modes (inline, `--json`, `--file`, `--stdin`) plus element-scoped evaluation via `--ref`. A key discoverability issue was found: `--ref` requires an arrow function (`element => ...`) as the expression, which is not documented in the help output or SKILL.md.

### Execution Context

1. **`goto`** — Navigated to `http://localhost:18080/generated/interactive-1.html`, auto-reused DEFAULT session. Page loaded with title "Interactive Single Page".
2. **`snapshot -v 0 --stdout`** — Captured accessibility tree. Discovered refs: `e2656` (h1 heading), `e2642` (textbox), `e2643` (combobox), `e2644`/`e2645` (spinbuttons), `e2700` (button).
3. **`eval "document.title"`** — Returned `Interactive Single Page`. Simple inline expression worked.
4. **`eval --json "JSON.stringify({...})"`** — Returned structured JSON envelope with `url`, `title`, `linkCount: 0`.
5. **`eval --file .test-sessions/page_info.js`** — File-based JS executed, returned `{"images":0,"links":0,"forms":0}`.
6. **`eval --stdin`** (piped) — Extracted all heading text: `Welcome to the Interactive Page | 📋 User Info...

(truncated — see full.md for complete trace)

---

## Issues Found (4 issues)

### Issue 1: eval --ref requires arrow function syntax (element => ...) but this is undocumented

**Severity:** High
**Category:** Documentation

#### Reproduction

Run `browser4-cli eval "document.title" --ref e5` or `browser4-cli eval "element.tagName" --ref e5`

#### Expected Behavior

The expression should be evaluated with `element` bound to the target DOM node, or the documentation should clearly state the required function signature.

#### Actual Behavior

When expression is not an arrow function, the result is `null` with a generic error tip. Only arrow functions like `element => element.textContent` work. This is discoverable only by reading the Rust test code in `commands.rs`.

#### Root Cause Analysis

The backend `browser_evaluate` tool expects the expression to be a function that receives the element as a parameter when a ref is provided. The CLI passes the expression and ref as separate params and the backend wraps them. Neither the CLI help text nor SKILL.md documents that the expression must be an arrow function `element => ...` — the test cases at commands.rs:4351-4356 show the expected pattern but users never see this.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:1431 — the `ref` option definition's description says 'CSS selector or snapshot ref to scope evaluation' but doesn't mention the arrow function requirement. Also cli/browser4-cli/src/help.rs for the help text generation.`

#### AI Suggested Improvement

- Update the --ref option description to include: 'Expression must be an arrow function (element => ...) when using --ref'
- Add an example to eval --help: browser4-cli eval "element => element.textContent" --ref e5
- Add to SKILL.md §3 Command Map > eval entry: mention the arrow function requirement
- Consider auto-detecting non-arrow-function expressions with --ref and showing a clear error: 'When using --ref, the expression must be an arrow function like element => element.textContent, not element.textContent'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed: `normalizeEvaluateValueArgs` (BrowserTabToolExecutor.kt:232-243) moves `expression` to `functionDeclaration` when both selector and expression are present, which means the expression MUST be an arrow function `element => ...` — the backend passes the element as the argument. Neither the help text (commands.rs:1431) nor the tips mention this requirement. Additionally, the existing tip at tips.rs:139 (`eval "this.textContent" e5`) is actively wrong and would fail.

---

### Issue 2: Misleading null output when eval --ref gets wrong expression form

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `browser4-cli eval "element.tagName" --ref e2656`

#### Expected Behavior

A clear error message indicating the expression form is incorrect, e.g. 'When using --ref, wrap your expression as an arrow function: element => element.tagName'

#### Actual Behavior

Output is `null` with tip: 'Expression returned null. The queried element or property may not exist on this page.' This misleads users into thinking the element ref is invalid or the page state is wrong, rather than that the expression syntax is incorrect.

#### Root Cause Analysis

When the expression is not a function, the backend likely tries to call it as a function with the element argument, gets undefined, and returns null. The CLI's generic null-handling tip does not account for the --ref case where the most likely cause is a missing arrow function wrapper.

#### Code Pointer

`cli/browser4-cli/src/main.rs or the output formatting logic that prints the null tip. The backend browser_evaluate handler in browser4-rest or browser4-agentic.`

#### AI Suggested Improvement

- When --ref is present and the result is null, add a specific hint: 'Did you use an arrow function? With --ref, write: element => element.property'
- Alternatively, the backend could detect that the expression is not a function and auto-wrap it, making `eval "element.tagName" --ref e5` just work

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The null-handling logic at main.rs:5161-5168 emits a generic "element or property may not exist" message regardless of whether --ref was used. When --ref is present and the expression is a bare property access like `"element.tagName"` rather than an arrow function, the expression evaluates to a function string rather than the intended value, producing null. A --ref-specific hint ("Did you mean `element => element.tagName`?") would save significant debugging time. This is defense-in-depth for Issue 1.

---

### Issue 3: eval --help usage line shows [ref] as positional arg but examples only show --ref flag

**Severity:** Low
**Category:** UX

#### Reproduction

Run `browser4-cli eval --help` and compare the usage line with the examples section.

#### Expected Behavior

The usage line should match the documented examples, or examples should show both forms.

#### Actual Behavior

Usage line says `eval [expression] [ref]` suggesting ref can be positional, but all examples use `--ref` as a named option. The inconsistency is confusing for a first-time user trying to understand which form to use.

#### Root Cause Analysis

The command definition allows both positional and named ref, but examples only show the named form. The positional form in the usage line might not actually work or might be undocumented behavior.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:1427-1431 — the arg definition and option definition for ref.`

#### AI Suggested Improvement

- Add a positional ref example: browser4-cli eval "element => element.textContent" e5
- Or remove [ref] from the usage line and keep only --ref if positional is deprecated
- Ensure both forms actually work and are tested

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Both `ArgDef { name: "ref" }` (positional, line 1428) and `OptionDef { name: "ref" }` (named, line 1431) are defined and work. The usage line shows `[ref]` as positional but no examples demonstrate positional form — all test cases (commands.rs:4353-4399) and internal callers (main.rs:2220, 8379) use `--ref` named form. If positional is supported, at least one example should show it; otherwise remove `[ref]` from the usage line and make it option-only for consistency.

---

### Issue 4: No eval tips shown by --show-tip despite being a core command

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `browser4-cli eval "document.title" --show-tip` and observe tips output

#### Expected Behavior

Tips about eval modes (--file, --stdin, --json, --ref with arrow function) should appear, especially for new users.

#### Actual Behavior

No eval-specific tips were observed during testing. The only tips shown were for htmlsnapshot, unrelated to the eval workflow.

#### Root Cause Analysis

The tips rotation may not include eval-specific tips, or the tips shown are not context-sensitive to the command being run. The tip system could benefit from command-context-aware tip selection.

#### Code Pointer

`cli/browser4-cli/src/tips.rs`

#### AI Suggested Improvement

- Add eval-specific tips covering: --file for Windows quoting, --stdin for piping, --json for structured output, arrow function requirement with --ref
- Consider showing context-sensitive tips based on the command just executed

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The premise is partially inaccurate — TIPS_EVAL (tips.rs:124-146) exists with 7 entries and `show_tip("eval")` dispatches correctly (tips.rs:377). However, the ref-related tip (line 139: `eval "this.textContent" e5`) is factually wrong — `"this.textContent"` is not an arrow function and would produce exactly the null/misleading-output problem from Issues 1-2. The real gap is: (1) the existing ref tip needs correction to show `element => element.textContent`, (2) no tip currently mentions the arrow function requirement, and (3) the misleading tip could actively teach users the wrong pattern. Cross-issue pattern: Issues 1, 2, and 4 all converge on the same root cause — the arrow-function-with-ref contract is undocumented everywhere and the one tip that mentions ref is actively incorrect.

---

## Overall Assessment

**Completion Status:** Successful — all 8 evaluation steps completed. The task objective was fully met, with all four eval invocation modes (inline, --json, --file, --stdin) and --ref scoped evaluation verified working correctly. Results were cross-validated for consistency.

**Success Rate:** 100% — all steps worked, though step 7 required source-code investigation to discover the undocumented arrow function syntax requirement for --ref.

**Issues Found:** 4

**Major Blockers:** The --ref arrow function requirement is a documentation gap that would block a real first-time user from using element-scoped eval. A user would try eval 'element.tagName' --ref e5, get null, and conclude the feature is broken — when it actually works with the correct (undocumented) syntax.

**Most Confusing Aspects:** 1) The --ref arrow function requirement is completely undocumented — discovered only by reading Rust test source code. 2) The misleading 'element may not exist' error when the real problem is expression syntax. 3) The usage line showing [ref] as positional vs examples using --ref creates ambiguity about which form to use.

**Most Valuable Improvements:** 1) Document the arrow function requirement in eval --help and SKILL.md. 2) Improve the null-result error message when --ref is used. 3) Add eval-specific tips to the tip rotation. 4) Consider auto-wrapping non-function expressions with --ref so 'element.tagName' just works.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: eval --ref requires arrow function syntax (element => ...) but this is undocumented

Run `browser4-cli eval "document.title" --ref e5` or `browser4-cli eval "element.tagName" --ref e5`

#### Issue 2: Misleading null output when eval --ref gets wrong expression form

Run `browser4-cli eval "element.tagName" --ref e2656`

#### Issue 3: eval --help usage line shows [ref] as positional arg but examples only show --ref flag

Run `browser4-cli eval --help` and compare the usage line with the examples section.

#### Issue 4: No eval tips shown by --show-tip despite being a core command

Run `browser4-cli eval "document.title" --show-tip` and observe tips output

