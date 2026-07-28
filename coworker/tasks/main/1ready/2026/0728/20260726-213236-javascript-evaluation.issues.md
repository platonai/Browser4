# Issues: javascript-evaluation

> **Source:** `20260726-213236-javascript-evaluation.full.md` | **Date:** 20260726-213236 | **Mode:** dev

## Scenario Background

### Task

All 7 eval methods completed successfully with consistent results:

| Method | Expression | Result |
|--------|-----------|--------|
| `eval "document.title"` | `document.title` | `Interactive Single Page` |
| `eval --json` | `{url: document.URL, title: document.title, linkCount: document.links.length}` | Structured JSON with url, title, linkCount=0 |
| `eval --file` | `page_info.js` computing images/links/forms | `{"images":0,"links":0,"forms":0}` |
| `eval --stdin` | Pipe headings query | All 5 headings returned as array |
| `eval --ref --stdin` | `function() { return this.tagName; }` → target e900 | `INPUT` |
| `eval --ref --file` | Function file → target e900 | `{"tag":"INPUT","type":"text","placeholder":"Type here...","name":"","id":"name"}` |
| `eval --await --stdin` | Promise returning `{ready, time}` | Resolved and returned correctly |

**Cross-method consistency verified:** `linkCount:0` from `--json` matches `links:0` from `--file`. Headings from `--stdin` match those visible in the interactive snapshot. Element properties from `--ref` match the textbox in the snapshot (`INPUT`, `type=text`, placeholder `Type here...`).

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"` — navigation
2. `./b4w.ps1 snapshot --interactive --stdout` — interactive snapshot (had to use long flag, `-i` intercepted by PowerShell)
3. `./b4w.ps1 eval "document.title"` — simple eval
4. `./b4w.ps1 eval --json "{url: document.URL, ...}"` — structured JSON
5. `./b4w.ps1 eval --file /tmp/page_info.js` — file-based eval
6. `echo '...' | ./b4w.ps1 eval --stdin` — stdin eval
7. Explored `--ref` semantics: tried `eval "this.tagName" --ref e900` → `null`; tried CSS selector → `null`; discovered function-declaration requirement → `function() { return this.tagName; }` → `INPUT`
8. `./b4w.ps1 eval --file /tmp/eval_ref_func.js e900` — file + positional ref with function declaration
9. `echo '...' | ./b4w.ps1 eval --stdin --await` — async eval

**Key decisions:**
- Used `--interactive` long flag instead of `-i` due to PowerShell parameter binding
- Used `--viewport 0` long flag instead of `-v 0` for same reason
- Discovered empirically that `--ref` requires a function declaration, not a simple expression

**Workarounds required:**
- PowerShell: use long-form flags (`--interactive`, `--viewport`) instead of short forms (`-i`, `-v`)
- `--ref`: wrap expressions in `function() { return ...; }` instead of using bare expressions

---

---

## Issues Found (6 issues)

### Issue 1: `eval --ref` silently returns `null` for simple expressions — requires undiscoverable function declaration

**Severity:** High
**Category:** UX

#### Reproduction

```bash
./b4w.ps1 eval "this.tagName" --ref e900
echo 'this.tagName' | ./b4w.ps1 eval --ref e900 --stdin
```

#### Expected Behavior

`INPUT` (the tag name of the targeted element). User passes a JavaScript expression, `--ref` scopes `this` to the element, the expression evaluates and returns the property.

#### Actual Behavior

`null` is returned with no error or explanation. The user has no way to know what went wrong.

#### Root Cause Analysis

In `PulsarWebDriver.kt:407-413`, `normalizeElementFunctionDeclaration()` unconditionally wraps every expression with `.call(this, this)`:
```kotlin
return """
    function() {
        return ($callable).call(this, this);
    }
```
For `this.tagName` (a string), this produces `("INPUT").call(this, this)` which throws because `String` has no `.call()` method. The error is silently caught and `null` is returned.

The correct usage — `function() { return this.tagName; }` — works because the resulting `(function() { ... }).call(this, this)` is valid. But this is never documented.

#### Code Pointer

``browser4-core/browser4-browser/src/main/kotlin/ai/platon/browser4/chrome/PulsarWebDriver.kt:normalizeElementFunctionDeclaration()``

#### AI Suggested Improvement

- Detect whether the expression is callable (`typeof expr === 'function'`) and only wrap with `.call()` if it is. For non-callable expressions, evaluate directly with the element as `this`.
- Alternatively, wrap expressions in a function body: `function() { return (EXPR); }` instead of calling `.call()`, which works for both callable and non-callable expressions.
- If keeping the current behavior, surface a clear error message: "When using --ref, the expression must be a function declaration. Example: function() { return this.tagName; }"
- Add this detail to the `eval --help` output and to `SKILL.md`.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Genuine bug — `normalizeElementFunctionDeclaration()` unconditionally wraps with `.call(this, this)`, which throws for non-callable expressions like `this.tagName` (a string has no `.call()`). The silent `null` return makes this actively misleading. Fix should detect callable vs. non-callable and evaluate non-callables directly with the element as `this`, or wrap in a function body instead of calling `.call()`.

---

### Issue 2: PowerShell short flags intercepted, documented `--` workaround fails

**Severity:** High
**Category:** Documentation + Reliability

#### Reproduction

```bash
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 -- snapshot -i --stdout
./b4w.ps1 snapshot -v 0 --stdout
```

#### Expected Behavior

Per SKILL.md, `./b4w.ps1 -- snapshot -i` should pass `-i` through to the Rust CLI. Short flags should work.

#### Actual Behavior

- `./b4w.ps1 snapshot -i` → error: "Parameter cannot be processed because the parameter name 'i' is ambiguous"
- `./b4w.ps1 -- snapshot -i` → error: "Parameter cannot be processed because the parameter name '' is ambiguous"
- `./b4w.ps1 snapshot -v 0` → interprets `-v` as `-Verbose` and `0` as a positional command argument, producing error help output

Users must use long-form flags (`--interactive`, `--viewport`), which the documentation mentions as an alternative but deemphasizes.

#### Root Cause Analysis

PowerShell's parameter binder matches short flags to common parameters before the `--` separator is processed. The `--` separator in PowerShell passes remaining args as positional, but `-i` alone isn't valid as a positional argument either. The docs reference `b4w.bat`/`b4w.sh` as alternatives but these aren't available from the repo's `./b4w.ps1`.

#### Code Pointer

``b4w.ps1` (PowerShell wrapper in repo root)`

#### AI Suggested Improvement

- Fix the `b4w.ps1` wrapper to use `--%` (PowerShell stop-parsing symbol) or `@args` splatting to pass through all arguments unmodified to the Rust CLI.
- Example fix: Change `b4w.ps1` to invoke `cargo run ... --% @args` so PowerShell doesn't interpret CLI flags.
- Until fixed, prominently document in `SKILL.md` that **all short flags must be replaced with long-form equivalents** when using `b4w.ps1`.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Real platform reliability issue — PowerShell's parameter binder intercepts short flags (`-i`, `-v`) before `--` can protect them, and the documented `--` workaround itself fails. The `b4w.ps1` wrapper should use `--%` (stop-parsing symbol) or splatting to pass all arguments through unmodified. Until fixed, the only reliable workaround is long-form flags, which should be prominently documented.

---

### Issue 3: `eval --help` output missing `--ref` context variable documentation

**Severity:** Medium
**Category:** Documentation

#### Reproduction

```bash
./b4w.ps1 eval --help
```

#### Expected Behavior

The help should explain:
- What variable name (`this`, `element`, `el`, `$0`) refers to the targeted element
- That `--ref` requires a function declaration (if Issue 1 is not fixed)
- An example showing correct `--ref` usage with a function

#### Actual Behavior

The help output says only "CSS selector or snapshot ref to scope evaluation". There is no explanation of the JS context model. The sole example with ref — `browser4-cli eval --file script.js e5` — doesn't show script.js contents or indicate that a function declaration is required.

#### Root Cause Analysis

The command definition in `commands.rs:1344` provides a minimal description for the `--ref` option. The examples section at the bottom doesn't include a `--ref` example despite `--ref` being a documented feature.

#### Code Pointer

``cli/browser4-cli/src/commands.rs:1344` (OptionDef description), `cli/browser4-cli/src/help.rs` (eval help text)`

#### AI Suggested Improvement

- Add `--ref` usage example to the eval help examples section showing both function declaration and (if fixed) simple expression forms.
- Document the context variable explicitly: `When --ref is used, this inside the expression refers to the targeted element.`
- If keeping the current function-declaration requirement, state it explicitly: `When using --ref, the JavaScript must be a callable function.`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear documentation gap — the `--ref` option description says only "CSS selector or snapshot ref to scope evaluation" with no explanation of the JS context model (`this` binding) or the function-declaration requirement (if Issue 1's fix retains it). A `--ref` example in the examples section is also missing. Low-effort high-impact fix.

---

### Issue 4: `console.log` output from `eval --file` not visible to user

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
# page_info.js contains console.log(JSON.stringify(result, null, 2));
./b4w.ps1 eval --file /tmp/page_info.js
```

#### Expected Behavior

The `console.log` output should either appear on stderr/stdout alongside the return value, or the documentation should clearly state that `console.log` output is not captured.

#### Actual Behavior

Only the return value appears. The `console.log` call in the script produces no visible output. This wastes time when users add `console.log` for debugging.

#### Root Cause Analysis

`evaluateValue`/`evaluateValueDetail` captures the return value of the evaluated expression but does not surface `console.log` messages. The `console` command exists as a separate tool, but users may not realize `eval` doesn't capture console output.

#### Code Pointer

``browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/BrowserTabToolExecutor.kt:1225-1264` (eval handler)`

#### AI Suggested Improvement

- Document in `eval --help` that `console.log` output is not captured; `eval` only returns the expression's return value.
- Use `console` command separately to view logged messages.
- Consider adding a `--console` flag to `eval` that captures and returns console output alongside the result.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid UX gap — users naturally add `console.log` for debugging and are confused when output vanishes. Minimum fix: document in `eval --help` that `console.log` is not captured and point users to the `console` command. The `--console` flag suggestion is a reasonable enhancement but can be deferred. Related to Issue 3 (both are eval documentation gaps) but distinct in scope.

---

### Issue 5: `snapshot grep` available but no `htmlsnapshot grep` visible in grep output example

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```bash
./b4w.ps1 help | grep -i "grep\|search"
```

#### Expected Behavior

Both `snapshot grep` and `htmlsnapshot grep` should be discoverable. The SKILL.md decision tree recommends `htmlsnapshot grep` for searching snapshot HTML.

#### Actual Behavior

`htmlsnapshot grep` is listed deep in the HTML Snapshot section of help but isn't shown alongside `snapshot grep` in the quick patterns. The SKILL.md mentions `htmlsnapshot grep` but it's buried.

#### Root Cause Analysis

The "Quick Patterns" section in SKILL.md shows `snapshot grep` but not `htmlsnapshot grep`. The difference between searching the accessibility tree snapshot vs. searching the HTML snapshot is not explained.

#### Code Pointer

``skills/browser4-cli/SKILL.md` (Quick Patterns section)`

#### AI Suggested Improvement

- Add an `htmlsnapshot grep` example to the Quick Patterns section.
- Distinguish when to use `snapshot grep` (search accessibility tree) vs. `htmlsnapshot grep` (search raw HTML).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid discoverability issue — `htmlsnapshot grep` exists but isn't surfaced alongside `snapshot grep` in Quick Patterns, and the distinction between searching the accessibility tree vs. raw HTML isn't explained. Trivial to fix with one additional example and a one-line explanation. Low priority but zero cost.

---

### Issue 6: Ref lifecycle ambiguity — `eval` effect on ref validity not documented

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `eval` multiple times with the same ref from a snapshot. Experience uncertainty about whether `eval` invalidates refs.

#### Expected Behavior

The SKILL.md's "Ref Lifecycle" section should list `eval` in either the "Safe" or "Unsafe" category.

#### Actual Behavior

`eval` is not mentioned in the ref lifecycle documentation. In practice, `eval` does not invalidate refs (confirmed by repeated use), but users must discover this empirically.

#### Root Cause Analysis

The ref lifecycle documentation in SKILL.md lists only interaction commands (`fill`, `type`, `press`, `check`, `uncheck`, `select`, `click`, `goto`, `reload`, tab switches) but omits `eval`.

#### Code Pointer

``skills/browser4-cli/SKILL.md` (Ref Lifecycle section, line 60-68)`

#### AI Suggested Improvement

- Add `eval` (and other read-only commands like `htmlsnapshot get`, `htmlsnapshot query`, `get`) to the "Safe" category: "Refs survive — `eval` is read-only and does not modify the DOM."
- Clarify explicitly: "All commands in the 'Extract' and 'Capture' families are ref-safe."

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Straightforward documentation omission — the Ref Lifecycle section lists interaction commands that invalidate refs but omits read-only commands like `eval`, `htmlsnapshot get`, `htmlsnapshot query`, and `get`. Adding a "Safe" category with these commands would resolve the ambiguity. Related to Issues 3 and 4 (all eval documentation gaps) but addresses a different document (SKILL.md vs. CLI --help).

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `eval --ref` silently returns `null` for simple expressions — requires undiscoverable function declaration

```bash
./b4w.ps1 eval "this.tagName" --ref e900
echo 'this.tagName' | ./b4w.ps1 eval --ref e900 --stdin
```

#### Issue 2: PowerShell short flags intercepted, documented `--` workaround fails

```bash
./b4w.ps1 snapshot -i --stdout
./b4w.ps1 -- snapshot -i --stdout
./b4w.ps1 snapshot -v 0 --stdout
```

#### Issue 3: `eval --help` output missing `--ref` context variable documentation

```bash
./b4w.ps1 eval --help
```

#### Issue 4: `console.log` output from `eval --file` not visible to user

```bash
# page_info.js contains console.log(JSON.stringify(result, null, 2));
./b4w.ps1 eval --file /tmp/page_info.js
```

#### Issue 5: `snapshot grep` available but no `htmlsnapshot grep` visible in grep output example

```bash
./b4w.ps1 help | grep -i "grep\|search"
```

#### Issue 6: Ref lifecycle ambiguity — `eval` effect on ref validity not documented

Run `eval` multiple times with the same ref from a snapshot. Experience uncertainty about whether `eval` invalidates refs.

