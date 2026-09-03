# Issues: javascript-evaluation

> **Source:** `20260814-141423-javascript-evaluation.full.md` | **Date:** 20260814-141423 | **Mode:** dev

## Scenario Background

### Task

The task completed successfully. All 8 steps of the eval-workflow scenario were executed against `http://localhost:18080/generated/interactive-1.html` ("Interactive Single Page") and every evaluation method produced correct, mutually consistent output:

| Step | Method | Result |
|---|---|---|
| 2. Interactive snapshot | `snapshot -i --stdout` | Discovered refs: `e2479` (h1 heading), `e2466` (textbox), `e2467` (combobox), `e2528` (button), etc. |
| 3. Simple expression | `eval "document.title"` | `Interactive Single Page` |
| 4. JSON metadata | `eval --json '({url, title, linkCount})'` | `url=http://localhost:18080/generated/interactive-1.html`, `title=Interactive Single Page`, `linkCount=0` |
| 5. File script | `eval --file .test-sessions/page_info.js` | `{"images":0,"links":0,"anchors":0,"forms":0}` |
| 6. Stdin pipe | `echo '...' \| ./b4w.ps1 eval --stdin` | All 5 headings in document order |
| 7. Element-scoped | `eval "element => element.textContent" --ref e2479` | `Welcome to the Interactive Page`; positional-ref form (`tagName` → `H1`) also works |
| 8. Verification | Cross-checked against `page-info`, snapshot, and raw HTML (curl) | All values consistent: 0 images / 0 links / 0 forms / 5 headings confirmed in raw HTML; title & URL match `page-info` and `goto` output |

The documented `--ref` arrow-function requirement was verified: the non-arrow form returns `null` with a diagnostic hint (though the hint's suggested fix is buggy — see Issue 2). `console.log` output is indeed not captured (as documented); only the returned value is shown.

### Execution Context

**Key Commands:**

`help`, `eval --help`, `goto`, `snapshot -i --stdout`, `eval "document.title"`, `eval --json '({...})'`, `eval --file`, `eval --stdin` (piped), `eval --ref` (arrow + non-arrow + positional-ref variants), `eval --json` scalar tests, `page-info`, plus `curl` against the MockSite fixture for ground-truth verification.

**Major steps:**
1. Verified `pwd` = repo root; ran `./b4w.ps1 help` (fast, comprehensive); read `skills/browser4-cli/SKILL.md` in full; confirmed MockSite responds 200 on :18080.
2. Navigated to the target page (reused an existing DEFAULT session with 2 tabs — reported transparently).
3. Captured `snapshot -i --stdout` — clean, ref-labeled output.
4. Ran each eval variant in order, testing documented behaviors on the side (null-result diagnostics, scalar wrapping, console.log capture).
5. Cross-verified every result against the raw fixture HTML fetched with curl (grep counts) and `page-info`.

**Decisions/workarounds:** None required — every documented workflow worked on the first attempt. Temp files (`page_info.js`, `interactive-1.raw.html`) were kept in `.test-sessions/` as required.

**Investigation performed for findings:** Read `cli/browser4-cli/src/main.rs` around the `browser_evaluate` result-handling block (~lines 5084–5160) and the goto summary printer (~line 1059) to establish root causes; located the "Did you mean" format string at main.rs:5108.

---

## Issues Found (4 issues)

### Issue 1: eval --json envelope stringifies scalars and double-encodes objects, contradicting documented JSON typing

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 eval --json "document.links.length"  →  output.result is the string "0" (not number 0).
./b4w.ps1 eval --json '({url: document.URL, title: document.title, linkCount: 0})'  →  output.result is a JSON-encoded string containing JSON (double encoding).
./b4w.ps1 eval --json "document.querySelector('.nope')"  →  output.result is the string "null" (not JSON null).

#### Expected Behavior

Per `eval --help`: "--json … strings get quoted, numbers/booleans/null pass through" and "Objects and arrays are serialized as JSON". A machine consumer should see "result":0, "result":null, and a nested object — not stringified/unparseable-typed values.

#### Actual Behavior

The JSON envelope's `output.result` field always holds the raw backend result as a plain string: numbers become "0", null becomes "null", and object expressions become a string whose value is itself JSON — requiring a second parse and manual type coercion, and making 0 vs "0" indistinguishable.

#### Root Cause Analysis

In the browser_evaluate result handler, the human-readable --json path parses `result` via serde_json::from_str before printing, but the envelope is built with `json_field("result", json!(&result))` using the unparsed raw string, so typed JSON values never reach the envelope. The help text describes the parsing path's semantics, not the envelope's.

#### Code Pointer

`cli/browser4-cli/src/main.rs — browser_evaluate result-handling block around lines 5147–5160 (`json_field("result", json!(&result))`); help text at cli/browser4-cli/src/help.rs:600`

#### AI Suggested Improvement

- Parse the result with serde_json::from_str before embedding it in the envelope (same logic as the human-readable path) and fall back to a plain string only when parsing fails, so scalars keep their JSON types and objects are nested
- If string-only `result` is intentional API design, update help.rs/SKILL.md to state it explicitly and document the double-parse requirement for object results
- Add an e2e/unit test asserting the envelope type of a number result, a null result, and an object result

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: eval null-diagnostic "Did you mean" tip suggests a broken expression (double `element.` prefix)

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 eval "element.textContent" --ref e2479
Output tip: Did you mean: eval "element => element.element.textContent" --ref …?
That suggested expression also returns null (element.element is undefined), so following the tool's own advice fails again.

#### Expected Behavior

The tip should suggest the correct form: eval "element => element.textContent" --ref e2479

#### Actual Behavior

The suggestion is `element => element.element.textContent` — the user's expression is blindly prefixed with `element.`, even when it already starts with `element.`.

#### Root Cause Analysis

The eprintln format string at main.rs:5108 always prepends `element.` to the user's expression: "Did you mean: eval \"element => element.{}\" --ref …?" with `expression` as the only argument. No check for whether the expression already starts with `element.` (or contains a bare property path).

#### Code Pointer

`cli/browser4-cli/src/main.rs — null-diagnostic eprintln in the browser_evaluate handler, around lines 5100–5112`

#### AI Suggested Improvement

- If the expression already starts with `element.`, suggest `element => {expression}`; otherwise suggest `element => element.{expression}`
- Alternatively, extract the property name after the first `element.` and build the suggestion from the correct template
- Add a unit test covering the exact repro (expression `element.textContent` with --ref) asserting the suggested text contains `element => element.textContent`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Duplicate result printed for null/empty eval results in human-readable mode

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 eval "document.querySelector('.does-not-exist')"
Output contains the result twice:
null
💡 Expression returned null.
...
null

#### Expected Behavior

The result should be printed exactly once.

#### Actual Behavior

`null` (and similarly `""` for empty-string results) is printed twice — once by the null-aware block, once by the general print path.

#### Root Cause Analysis

The null/empty-aware block (main.rs ~5084) prints "null"/"\"\"" via cli_println, and then control falls through to the generic `else { cli_println!("{}", maybe_pretty_print_json(&result)); }` branch which prints the same value again. In --json mode the envelope is not corrupted (cli_println is suppressed), so the defect is cosmetic but confusing in interactive use.

#### Code Pointer

`cli/browser4-cli/src/main.rs — browser_evaluate result handler, null-aware block ~5084 and fall-through print ~5158`

#### AI Suggested Improvement

- Print the result in exactly one place (e.g. skip the generic print when the null/empty block already printed)
- Consider exiting non-zero or emitting a distinct machine-detectable marker when an eval expression evaluates to null, so shell scripts relying on exit codes can detect 'no result' (currently exit code is 0)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: goto prints a hardcoded tip on every navigation despite documented 'tips suppressed by default' behavior

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run ./b4w.ps1 goto <any url> twice. Each time stderr prints:
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
without passing --show-tip / -tip.

#### Expected Behavior

Per the global options help ("-tip, --show-tip  show a relevant tip on stderr after each command") and SKILL.md §Output Modes ("Tips are suppressed by default; use this flag to enable them"), no tip should appear unless --show-tip is passed.

#### Actual Behavior

A hardcoded tip is emitted on every goto regardless of the --show-tip flag, making the documented tip-on/off semantics inconsistent (goto tips always on; other commands' rotating tips off).

#### Root Cause Analysis

The page-summary printer after goto contains an unconditional `if !json_active() { eprintln!("💡 Tip: …") }` that does not consult `show_tip_active()`, so the tip bypasses the documented opt-in mechanism.

#### Code Pointer

`cli/browser4-cli/src/main.rs — page-summary/tip emission around line 1059 (unconditional eprintln in the post-goto summary block)`

#### AI Suggested Improvement

- Gate the tip on show_tip_active() so it honors the documented flag, or
- Keep it as an onboarding hint but show it only once per session (first goto) and update SKILL.md/help to document the exception, or
- Move this specific hint into the rotating tips::show_tip system so it is consistent with all other tips

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 8 task steps completed; every eval method (inline, --json, --file, --stdin, --ref) returned correct results, cross-verified against raw HTML and page-info.

**Success Rate:** 100% — no step required a workaround; all documented eval workflows worked on the first attempt.

**Issues Found:** 4

**Most Confusing Aspects:** 1) The --json envelope's type behavior: numbers come back as strings and objects are double-encoded, contrary to the help text's promise that numbers 'pass through' — a machine consumer must re-parse and coerce. 2) The diagnostic tip for the #1 documented eval --ref mistake (non-arrow expression) suggests a fix that is itself broken (element.element.textContent), which would send a first-time user in circles.

**Most Valuable Improvements:** 1) Embed parsed (typed) JSON values in the eval --json envelope instead of raw strings. 2) Fix the 'Did you mean' suggestion generator to not double-prefix `element.`. 3) Make the goto tip respect --show-tip (or document the exception).

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

#### Issue 1: eval --json envelope stringifies scalars and double-encodes objects, contradicting documented JSON typing

./b4w.ps1 eval --json "document.links.length"  →  output.result is the string "0" (not number 0).
./b4w.ps1 eval --json '({url: document.URL, title: document.title, linkCount: 0})'  →  output.result is a JSON-encoded string containing JSON (double encoding).
./b4w.ps1 eval --json "document.querySelector('.nope')"  →  output.result is the string "null" (not JSON null).

#### Issue 2: eval null-diagnostic "Did you mean" tip suggests a broken expression (double `element.` prefix)

./b4w.ps1 eval "element.textContent" --ref e2479
Output tip: Did you mean: eval "element => element.element.textContent" --ref …?
That suggested expression also returns null (element.element is undefined), so following the tool's own advice fails again.

#### Issue 3: Duplicate result printed for null/empty eval results in human-readable mode

./b4w.ps1 eval "document.querySelector('.does-not-exist')"
Output contains the result twice:
null
💡 Expression returned null.
...
null

#### Issue 4: goto prints a hardcoded tip on every navigation despite documented 'tips suppressed by default' behavior

Run ./b4w.ps1 goto <any url> twice. Each time stderr prints:
💡 Tip: Try `htmlsnapshot get text "h1"` to extract the page heading, or `htmlsnapshot inspect` to discover CSS selectors
without passing --show-tip / -tip.

