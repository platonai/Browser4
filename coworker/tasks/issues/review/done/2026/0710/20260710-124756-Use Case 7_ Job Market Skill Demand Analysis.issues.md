# Issues: Use Case 7: Job Market Skill Demand Analysis

> **Source:** `20260710-124756-Use Case 7_ Job Market Skill Demand Analysis.full.md` | **Date:** 20260710-124756 | **Mode:** dev

## Scenario Background

### Task

The `skill-demand-analysis.md` has been generated at `D:/workspace/Browser4/Browser4-4.11/skill-demand-analysis.md`. It analyzes 10 job postings for Senior Backend Engineer and related roles, normalizes skill names, calculates frequency distribution, and provides a demand summary.

**Key finding:** wellfound.com (the originally specified site) is protected by DataDome CAPTCHA and was completely inaccessible through browser4-cli. The analysis was completed using remoteok.com as an alternative data source.

---

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `cargo --version && java -version` | Verify prerequisites |
| 2 | `cargo run -- ... -- --help` | Learn available commands |
| 3 | `goto https://wellfound.com/jobs` | Initial navigation (blocked) |
| 4 | `snapshot -v 0` ×3 | Page inspection (CAPTCHA) |
| 5 | `screenshot` | Visual confirmation of CAPTCHA |
| 6 | `reload` + `snapshot` | Recovery attempt (failed) |
| 7 | `htmlsnapshot` ×5 | Capture static HTML for extraction |
| 8 | `close` + `open --headed --profile-mode temporary` | Fresh session attempt |
| 9 | `attach --cdp chrome` | Attach to existing Chrome |
| 10 | `goto https://remoteok.com/` | Switch to alternative site |
| 11 | `snapshot grep` ×3 | Find search elements |
| 12 | `fill e90 "Senior Backend Engineer"` | Form interaction (worked) |
| 13 | `fill ... --submit` | Triggered backend error |
| 14 | `press Enter e90` | Submit search (didn't work) |
| 15 | `eval` ×10 | JavaScript execution, debugging, popup dismissal |
| 16 | `eval --file` ×4 | Run JS from file (quote escaping) |
| 17 | `htmlsnapshot query --sql @query` ×4 | X-SQL structured extraction |
| 18 | `htmlsnapshot get text` ×5 | CSS selector extraction |
| 19 | `htmlsnapshot inspect` ×2 | Pattern discovery |
| 20 | `scroll down` ×4 | Lazy-load content |
| 21 | `goto <job-posting-url>` ×5 | Visit individual job postings |
| 22 | `htmlsnapshot get all text` | Bulk data extraction |

**Workarounds Applied During Task:**

- **wellfound.com CAPTCHA** → Switched to remoteok.com
- **`fill --submit` error** → Used `fill` without `--submit` then `press Enter`
- **Client-side JS filtering** → Used `eval` for direct DOM manipulation
- **Shell quoting on Windows** → Used `--file` option for `eval`
- **File path resolution** → Used absolute paths (`D:/workspace/...`)
- **Truncated job descriptions** → Used multiple extraction selectors
- **Premium popup** → Dismissed with `eval`
- **Ref staleness** → Re-captured snapshot after every navigation

---

---

## Issues Found (11 issues)

### Issue 1: Environment Variable Configuration Not Documented

**Severity:** Medium
**Category:** Documentation

#### Reproduction

The task instructions reference `$RepoRootPath`, `$cliInvocation`, `$helpCmd`, and `$skillPath` environment variables. These were all empty at runtime.

#### Expected Behavior

The variables should be pre-configured or the task should specify exact values.

#### Actual Behavior

All four variables were empty strings, requiring manual discovery of:
- Repo root: `D:/workspace/Browser4/Browser4-4.11` (from task prose)
- CLI invocation: `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` (from development.md)
- Help command: `--help` (from CLI convention)
- Skill path: `skills/browser4-cli/SKILL.md` (from directory exploration)

#### Root Cause Analysis

The task template uses variable substitution placeholders that are expected to be filled by the evaluation harness, but the harness didn't populate them.

#### Code Pointer

`N/A (harness issue)`

#### AI Suggested Improvement

- Document the variable substitution mechanism in the task template
- Provide fallback values inline in the task description
- Add a validation step that checks variables are populated before task execution

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Bot Protection Blocks Real-World Sites

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://wellfound.com/jobs"
snapshot -v 0
```
Returns: `generic "DataDome CAPTCHA" [ref=e2]` with only 1209 bytes of HTML.

#### Expected Behavior

The page should load with actual job content, or the CLI should detect the CAPTCHA and report a clear error.

#### Actual Behavior

The `goto` command returns success, the snapshot shows only the CAPTCHA element, and `eval` confirms `bodyLen: 1209` with empty text. No indication that the page is blocked. The same happens with `--headed`, `--profile-mode temporary`, and `attach --cdp chrome`.

#### Root Cause Analysis

wellfound.com uses DataDome, which detects CDP-controlled browsers via browser fingerprinting signals (navigator.webdriver, CDP runtime flags, etc.). The CLI has no built-in anti-detection mechanisms (stealth mode, fingerprint randomization, proxy rotation).

#### Code Pointer

``cli/browser4-cli/src/` — session/browser launch logic. Could add anti-detection flags or detection of CAPTCHA pages.`

#### AI Suggested Improvement

- Add a `--stealth` flag that enables anti-bot-detection measures (disable webdriver flag, randomize viewport, patch navigator properties)
- After `goto`, detect common CAPTCHA/bot-detection services (DataDome, Cloudflare, hCaptcha, reCAPTCHA) and surface a clear warning
- Document known limitations with bot-protected sites in the README
- Consider adding a `--proxy` option for rotating proxy services

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `fill --submit` Causes Backend Error with Leaked Internal Message

**Severity:** High
**Category:** Reliability

#### Reproduction

```
fill e90 "Senior Backend Engineer" --submit
```

#### Expected Behavior

Fills the text and presses Enter to submit.

#### Actual Behavior

```
ERROR: browser_type failed: Extraneous parameter 'submit' for fill. Allowed=[selector, text]
help: This method emulates inserting text that doesn't come from a key press.
```
The error leaks a Kotlin code snippet: `driver.fill("input[name='q']", "Hello, World!")`.

#### Root Cause Analysis

The `--submit` flag is documented in the CLI help (`fill <ref> <text>` with `--submit` to press Enter after) but the backend rejects the `submit` parameter. This is a version mismatch between the CLI binary and the backend JAR.

#### Code Pointer

``browser4-core/` or `browser4-rest/` — the backend endpoint handling the `fill` command doesn't accept the `submit` parameter.`

#### AI Suggested Improvement

- Align backend and CLI parameter schemas — either add `submit` support to the backend or remove it from the CLI help
- Strip Kotlin/internal error messages from user-facing output
- Add an integration test that validates every CLI flag against the backend schema

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
add submit support

---

### Issue 4: `type` Command Does Not Trigger JavaScript Input Events

**Severity:** High
**Category:** Reliability

#### Reproduction

```
fill e90 "Senior Backend Engineer"
press Enter e90
```
Then check `document.querySelector('input.search-filter-input').value` — value is set but the site's JavaScript filter doesn't activate.

#### Expected Behavior

Typing text into an input should trigger the standard `input` and `change` events that client-side JavaScript frameworks (React, Vue, etc.) listen for.

#### Actual Behavior

The `fill` command sets `element.value` directly without dispatching `input` or `change` events. The `type` command simulates keystrokes but also doesn't reliably trigger framework-level reactivity. Workaround required using `eval` with `nativeInputValueSetter` + `dispatchEvent(new Event('input'))`.

#### Root Cause Analysis

Both `fill` and `type` use CDP methods that manipulate the DOM/value directly. CDP's `Input.dispatchKeyEvent` may not trigger the synthetic event system that modern JS frameworks depend on.

#### Code Pointer

``cli/browser4-cli/src/` — the `fill` and `type` command implementations, and the backend CDP integration layer.`

#### AI Suggested Improvement

- After setting `element.value` via CDP, dispatch `input`, `change`, and `keyup` events programmatically
- Add a `--react` flag that uses React-friendly value setting (native setter + event dispatch)
- Document the limitation and workaround in the SKILL.md and README

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Shell Quote Escaping on Windows is Extremely Difficult

**Severity:** High
**Category:** UX

#### Reproduction

Try passing a moderately complex JavaScript expression to `eval`:
```
eval "JSON.stringify({searchVal: document.querySelector('input[placeholder*=\"Search\"]')?.value})"
```

#### Expected Behavior

The expression should be passed to the browser correctly.

#### Actual Behavior

The multiple layers of quoting (Bash → cargo → CLI → JS) make correct escaping nearly impossible. Single quotes, double quotes, backticks, and dollar signs all interact in unpredictable ways. Multiple attempts produced `null` results or syntax errors before the correct escaping was found.

#### Root Cause Analysis

The CLI runs inside a Bash shell on Windows (Git Bash), which applies its own quoting rules before passing arguments to `cargo run`, which passes them to the CLI binary, which passes them to the browser's JS engine. Four layers of quote interpretation.

#### Code Pointer

`N/A — architectural limitation. Mitigated by the existing `--file`, `--stdin`, `--base64` options.`

#### AI Suggested Improvement

- Promote `--file` as the **primary recommended method** for `eval` in the documentation, not an alternative
- Add a `heredoc`/`--stdin` example in the quick-start guide for `eval`
- Consider adding an interactive eval mode (`eval --interactive`) that reads from a temporary file
- Add a Windows-specific section in the troubleshooting guide

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Promote --file as the primary recommended method for eval in the documentation.

---

### Issue 6: `eval --file` Path Resolution Depends on `cargo run` Working Directory

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval --file "search.js"
```
When `search.js` is at the repo root.

#### Expected Behavior

File path resolves relative to the current working directory (repo root).

#### Actual Behavior

Error: `Failed to read eval file '../../search_remoteok.js': 系统找不到指定的文件。` The working directory during `cargo run` is `cli/browser4-cli/`, not the repo root. Requires `../../search.js` or absolute path.

#### Root Cause Analysis

Cargo sets the working directory to the crate directory (`cli/browser4-cli/`) when running the binary. The development.md mentions this but it's easy to miss.

#### Code Pointer

`N/A — Cargo behavior.`

#### AI Suggested Improvement

- Add a prominent note in the SKILL.md quick-start: "When using `--file`, use absolute paths or paths relative to `cli/browser4-cli/`"
- Consider having the CLI binary resolve relative paths from the original invocation directory rather than the cargo working directory
- Add a `--cwd <path>` option to explicitly set the working directory for file resolution

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: HTML Snapshot Does Not Capture Dynamically Loaded Content

**Severity:** Medium
**Category:** Reliability

#### Reproduction

On remoteok.com, scroll down to trigger lazy loading of more jobs, then run `htmlsnapshot`. The snapshot size remains 1116 KB with the same link count before and after scrolling.

#### Expected Behavior

Scrolling should trigger AJAX loading of additional job listings, which should appear in the DOM and be captured by the HTML snapshot.

#### Actual Behavior

The snapshot size and content don't change after scrolling. Either the lazy loading didn't trigger or the snapshot captures a static copy that doesn't reflect DOM mutations from AJAX calls.

#### Root Cause Analysis

Unclear — could be that the scroll didn't reach the trigger point, or that `htmlsnapshot` captures from a stored/page-cache version rather than the live DOM. Further investigation needed.

#### Code Pointer

``browser4-core/` — the HTML snapshot capture logic.`

#### AI Suggested Improvement

- Verify that `htmlsnapshot` captures the live DOM, not a cached version
- Add a `--wait-for <selector>` option to `htmlsnapshot` that waits for specific content to appear before capturing
- Document the relationship between scroll position and snapshot content

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: `snapshot grep` Regex Alternation Syntax Inconsistency

**Severity:** Low
**Category:** UX

#### Reproduction

```
snapshot grep -i "search\|find\|input\|textbox"
```

#### Expected Behavior

Standard grep alternation syntax (`\|`) should work.

#### Actual Behavior

A warning is emitted: "Converted grep-style alternation `\\|` to `|` in pattern. Rust regex uses bare `|` for alternation (like ERE/egrep)."

#### Root Cause Analysis

The underlying regex engine is Rust's `regex` crate (ERE syntax), not GNU grep (BRE syntax). The CLI does auto-convert but the warning adds noise.

#### Code Pointer

``cli/browser4-cli/src/` — snapshot grep implementation.`

#### AI Suggested Improvement

- Accept both `\|` and `|` without warning (silently convert)
- Use `-E` flag semantics by default (extended regex) since that's what most users expect
- Add an examples section for common grep patterns in the help

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: X-SQL Function Names Not Discoverable from CLI

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

A new user wants to write an X-SQL query. They run `htmlsnapshot query --help` which shows `--sql` as required but doesn't list available functions (DOM_FIRST_TEXT, DOM_LOAD_AND_SELECT, etc.).

#### Expected Behavior

The help should either list available functions or point to the reference documentation.

#### Actual Behavior

The help shows only the SQL pattern template without listing functions. Users must discover the `skills/browser4-cli/references/x-sql*.md` files separately.

#### Root Cause Analysis

The X-SQL function catalog is maintained in separate reference files (`x-sql-dom-functions.md`, etc.) that are not linked from the `--help` output.

#### Code Pointer

``cli/browser4-cli/src/` — the `htmlsnapshot query` help text.`

#### AI Suggested Improvement

- Add `See skills/browser4-cli/references/x-sql.md for the complete function reference` to the `htmlsnapshot query --help` output
- Consider a `htmlsnapshot query --functions` flag that lists available X-SQL functions
- Add 2-3 copy-pasteable query templates in the help output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
Add See skills/browser4-cli/references/x-sql.md for the complete function reference to the htmlsnapshot query --help output

---

### Issue 10: `--help` Output is an Undifferentiated Wall of Text

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help`. The output is ~200 lines of densely packed text with no visual hierarchy beyond section headers.

#### Expected Behavior

A scannable help output with clear grouping, ideally with `--help <category>` to filter by domain (navigation, interaction, extraction, etc.).

#### Actual Behavior

All 60+ commands are listed in one monolithic output. Finding a specific command requires reading the entire output. No `--help navigation` or `--help extract` filtering.

#### Root Cause Analysis

The help is structured as a flat list with `---` separators. No category-based filtering is implemented.

#### Code Pointer

``cli/browser4-cli/src/` — help rendering logic.`

#### AI Suggested Improvement

- Add category-based help filtering: `--help nav`, `--help extract`, `--help session`
- Use a table format for command summaries (command | description)
- Add a "Common workflows" section at the top showing the 5 most common command sequences
- Group related commands visually with indentation or color (when supported)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: Session Carries State Across Unrelated Tasks (Stale Tabs)

**Severity:** Low
**Category:** UX

#### Reproduction

Running `goto https://wellfound.com/jobs` for the first time produced: `Reconnected to existing session on https://www.amazon.com/dp/B0C3HCD34R`.

#### Expected Behavior

A fresh task should start with a clean or predictable browser state.

#### Actual Behavior

The CLI reconnected to a pre-existing browser session showing an Amazon product page from a previous session. This is intended behavior (session persistence) but can be surprising for new users.

#### Root Cause Analysis

The default session persists across CLI invocations. The `goto` command reconnects to the last active session.

#### Code Pointer

`N/A — by design.`

#### AI Suggested Improvement

- Show a notice on first `goto` in a session: "Reconnected to existing session. Use `close` first for a clean start."
- Consider a `--fresh` flag on `goto` to start a new session
- Add session age to the `goto` output: "Reconnected to session from 2 days ago"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Environment Variable Configuration Not Documented

The task instructions reference `$RepoRootPath`, `$cliInvocation`, `$helpCmd`, and `$skillPath` environment variables. These were all empty at runtime.

#### Issue 2: Bot Protection Blocks Real-World Sites

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://wellfound.com/jobs"
snapshot -v 0
```
Returns: `generic "DataDome CAPTCHA" [ref=e2]` with only 1209 bytes of HTML.

#### Issue 3: `fill --submit` Causes Backend Error with Leaked Internal Message

```
fill e90 "Senior Backend Engineer" --submit
```

#### Issue 4: `type` Command Does Not Trigger JavaScript Input Events

```
fill e90 "Senior Backend Engineer"
press Enter e90
```
Then check `document.querySelector('input.search-filter-input').value` — value is set but the site's JavaScript filter doesn't activate.

#### Issue 5: Shell Quote Escaping on Windows is Extremely Difficult

Try passing a moderately complex JavaScript expression to `eval`:
```
eval "JSON.stringify({searchVal: document.querySelector('input[placeholder*=\"Search\"]')?.value})"
```

#### Issue 6: `eval --file` Path Resolution Depends on `cargo run` Working Directory

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval --file "search.js"
```
When `search.js` is at the repo root.

#### Issue 7: HTML Snapshot Does Not Capture Dynamically Loaded Content

On remoteok.com, scroll down to trigger lazy loading of more jobs, then run `htmlsnapshot`. The snapshot size remains 1116 KB with the same link count before and after scrolling.

#### Issue 8: `snapshot grep` Regex Alternation Syntax Inconsistency

```
snapshot grep -i "search\|find\|input\|textbox"
```

#### Issue 9: X-SQL Function Names Not Discoverable from CLI

A new user wants to write an X-SQL query. They run `htmlsnapshot query --help` which shows `--sql` as required but doesn't list available functions (DOM_FIRST_TEXT, DOM_LOAD_AND_SELECT, etc.).

#### Issue 10: `--help` Output is an Undifferentiated Wall of Text

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help`. The output is ~200 lines of densely packed text with no visual hierarchy beyond section headers.

#### Issue 11: Session Carries State Across Unrelated Tasks (Stale Tabs)

Running `goto https://wellfound.com/jobs` for the first time produced: `Reconnected to existing session on https://www.amazon.com/dp/B0C3HCD34R`.

