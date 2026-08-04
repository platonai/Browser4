# Issues: form-filling

> **Source:** `20260804-185507-form-filling.full.md` | **Date:** 20260804-185507 | **Mode:** dev

## Scenario Background

### Task

The form was filled, submitted, and verified. All values confirmed in the result payload:

| Field | Value |
|-------|-------|
| First Name | Alexandra |
| Last Name | Chen |
| Email | alexandra.chen@example.com |
| Country | us (United States) |
| Experience | advanced |
| Topics | automation, testing, ai |
| Comments | I'd like to automate product search... |
| Validation | **All validation checks passed.** |

### B. Issues Found — 8 total (1 Critical, 1 High, 2 Medium, 4 Low)

```json
{
  "issues": [
    {
      "title": "Compilation error prevents CLI from launching",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "Run `./b4w.ps1 help` from the repo root with current code.",
      "expected": "CLI compiles and runs successfully.",
      "actual": "Rust compilation fails: `usize is not an iterator` at main.rs:11011. `.flat_map()` was used where `.map()` was intended.",
      "rootCause": "The closure passed to `flat_map` returns `usize` (`.len()`) instead of `IntoIterator`. Single-token typo: `flat_map` → `map`.",
      "codePointer": "cli/browser4-cli/src/main.rs:11011 — change `.flat_map(` to `.map(`",
      "suggestion": "- Run `cargo check` as a pre-commit hook or CI check to catch compilation errors before they land on the branch\n- Add a `cargo check` step to the `./b4w.ps1` pre-build phase that fails fast before attempting full compilation\n- Consider a `--check` flag to verify the CLI can compile without executing"
    },
    {
      "title": "`goto` reports success but session does not persist for subsequent commands",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1. Run `./b4w.ps1 goto \"http://localhost:18080/...\"` (no prior session).\n2. Immediately run `./b4w.ps1 snapshot -i` or `./b4w.ps1 list`.",
      "expected": "After `goto` reports navigation success, subsequent commands should find the active session.",
      "actual": "`goto` outputs 'Reusing session DEFAULT' and 'Navigated to...' appearing to succeed. But `snapshot -i` fails with 'No active session' and `list` shows no sessions.",
      "rootCause": "`goto` appears to attempt reusing a session that doesn't exist. The 'Reusing session DEFAULT' message is misleading — it may be trying to reconnect to a dead session tracker rather than creating a new session. `open` works correctly while `goto` does not. Investigation needed in session lifecycle of goto vs open.",
      "codePointer": "cli/browser4-cli/src/ — session management in the `goto` command handler vs `open`",
      "suggestion": "- Fix `goto` to properly create/persist a session when none exists\n- Add a diagnostic message when the session store is empty: 'No active session — creating a new one.'\n- Add an integration test: `goto <url>` followed by `list` should show an active session"
    },
    {
      "title": "`snapshot -i` session error message uses wrong binary name",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run `./b4w.ps1 snapshot -i` when no active session exists.",
      "expected": "Error message suggests the correct command for the user's invocation method.",
      "actual": "Shows 'run `browser4-cli open <url>`' — but the user is running `./b4w.ps1`, not `browser4-cli`.",
      "rootCause": "Error messages hardcode the generic binary name `browser4-cli` instead of using the actual invocation name.",
      "codePointer": "cli/browser4-cli/src/ — error message templates for session-related errors",
      "suggestion": "- Detect the actual binary name used at invocation and substitute it in error messages\n- Or standardize tips to use a placeholder like '{cli} open <url>' that gets replaced at runtime"
    },
    {
      "title": "`get` and `htmlsnapshot get` have different selector mechanisms — naming is confusing",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "1. Run `get text \".result-panel\"` — fails (AXTree, not CSS).\n2. Run `htmlsnapshot get text \"#result-data\"` — succeeds (CSS selectors).",
      "expected": "Both `get` commands use the same selector mechanism, or the distinction is clearly visible in the command name.",
      "actual": "The subcommand `get` appears in two different command families (`get` top-level vs `htmlsnapshot get`) with different semantics — AXTree vs CSS selectors. The shared verb implies similar behavior.",
      "rootCause": "`get` queries the live accessibility tree via CDP; `htmlsnapshot get` queries stored HTML via CSS selectors. Naming collision in the command hierarchy.",
      "codePointer": "cli/browser4-cli/src/ — command dispatch for `get` vs `htmlsnapshot get`",
      "suggestion": "- Rename top-level `get` to `ax-get` or `tree-get` to clarify it operates on the accessibility tree\n- Or rename `htmlsnapshot get` to `htmlsnapshot select` or `htmlsnapshot css`\n- Add a cross-reference in help: 'For CSS selectors, use htmlsnapshot get instead'"
    },
    {
      "title": "`htmlsnapshot` partially misses dynamically-inserted DOM elements after form submission",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "1. Submit a form that updates the page DOM dynamically.\n2. Run `htmlsnapshot` to capture.\n3. Run `htmlsnapshot get text \".result-panel\"` — not found.",
      "expected": "After recapturing with `htmlsnapshot`, all visible DOM content should be queryable.",
      "actual": "`#result-data` and `#validation-summary` worked, but `.result-panel` was not found. Inconsistent capture of dynamic content.",
      "rootCause": "`htmlsnapshot` captures via the scrape API which may re-fetch rather than serialize the live DOM. The SKILL.md §5 warns about this, but the partial capture (some dynamic elements work) is confusing.",
      "codePointer": "",
      "suggestion": "- Document exactly which types of dynamic content `htmlsnapshot` can and cannot capture\n- Add a `--live` flag to serialize the live DOM via CDP instead of re-fetching\n- Surface a warning when `htmlsnapshot get` returns no results after known interactions"
    },
    {
      "title": "Auto-build spinner UI lacks stage-level progress detail",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any `./b4w.ps1` command after source changes.",
      "expected": "Build progress shows compilation stages or file counts.",
      "actual": "Simple spinner with time estimates. Server startup shows '⠋ Starting server... (7s elapsed, ~113s remaining)' but no indication of what stage (JVM, Spring, browser).",
      "rootCause": "The spinner provides time estimates but no insight into what's happening during server startup.",
      "codePointer": "cli/browser4-cli/src/ — server startup spinner/progress display",
      "suggestion": "- Show underlying process output in real-time during builds\n- Add a `--verbose` flag that streams build output\n- Add a stage indicator: 'Compiling Rust...', 'Starting JVM...', 'Waiting for HTTP...'"
    },
    {
      "title": "`snapshot -i` interactive mode value proposition is unclear",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "Task asked to use `snapshot -i` but regular auto-snapshot sufficed for all interactions.",
      "expected": "Documentation explains when `-i` is preferred over default snapshot behavior.",
      "actual": "Could not evaluate interactive mode due to session issue. Regular snapshot provided sufficient information. SKILL.md only warns that `-i` strips generic `<div>` containers — unclear when it's beneficial.",
      "rootCause": "The interactive mode's unique value vs default snapshot isn't documented. The SKILL.md warning about div-stripping may actually discourage its use.",
      "codePointer": "",
      "suggestion": "- Document what `-i` mode does differently with a concrete example\n- Add a help section comparing `-i` vs `-v 0` vs default for different use cases\n- Consider if `-i` should be the default for discoverability"
    },
    {
      "title": "Server cold-start latency (~11s) is acceptable but could be documented",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run `./b4w.ps1 open <url>` as the first command in a session.",
      "expected": "Server starts quickly or provides clear progress indicators.",
      "actual": "11-second cold start with spinner. Subsequent commands were instant. Reasonable for JVM backend but unexpected for first-time users.",
      "rootCause": "Spring Boot JVM application cold start includes JVM init, Spring context loading, and browser driver setup.",
      "codePointer": "",
      "suggestion": "- Consider a `browser4-cli daemon` command to pre-start the server\n- Document the expected first-launch latency in the quick start guide\n- The spinner already helps — just setting expectations would reduce perceived friction"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — the form was filled with all required fields, submitted, and the result was verified. All submitted values were confirmed correct in the response payload.",
    "successRate": "85% — 21 of 25 steps succeeded directly; 3 required workarounds; 1 step (snapshot -i) was skipped due to the session issue.",
    "issuesFound": 8,
    "majorBlockers": "Two blockers: (1) Compilation error in main.rs that prevented ANY CLI command from running — required a code fix before evaluation could proceed. (2) `goto` reported success but didn't persist a session, requiring use of `open` as a workaround. Both are reliability issues in the critical path.",
    "mostConfusingAspects": "The most confusing aspect was the `goto` command reporting success ('Reusing session DEFAULT', 'Navigated to...') but the session not being available for subsequent commands. This is a silent failure that undermines trust in the tool. Second, the distinction between `get` (accessibility tree) and `htmlsnapshot get` (CSS selectors) is easy to miss — they share the same verb but work completely differently.",
    "mostValuableImprovements": "1. Fix the `goto` session persistence bug — it's the first command every user runs and it silently fails. 2. Add a pre-commit/CI `cargo check` to prevent compilation errors from landing on the branch. 3. Rename or visually distinguish `get` vs `htmlsnapshot get` to prevent selector confusion. 4. Use the actual binary name in error messages instead of hardcoding `browser4-cli`.",
    "usabilityRating": 6
  }
}
```

### Summary

**Overall usability rating: 6/10.** The core interaction loop (snapshot → fill/select/check → click → verify) works well once a session is established. However, the out-of-box experience is marred by two critical-path failures: a compilation error that blocks all commands, and a `goto` command that silently fails to persist sessions. The documentation (SKILL.md) is thorough and well-structured. Once past the initial hurdles, the form-filling workflow was smooth and the snapshot-based element referencing was intuitive. The main areas for improvement are reliability hardening (prevent compilation regressions, fix session lifecycle) and command naming clarity (disambiguate `get` from `htmlsnapshot get`).

---

## Issues Found (8 issues)

### Issue 1: Compilation error prevents CLI from launching

**Severity:** Critical
**Category:** Reliability

#### Reproduction

Run `./b4w.ps1 help` from the repo root with current code.

#### Expected Behavior

CLI compiles and runs successfully.

#### Actual Behavior

Rust compilation fails: `usize is not an iterator` at main.rs:11011. `.flat_map()` was used where `.map()` was intended.

#### Root Cause Analysis

The closure passed to `flat_map` returns `usize` (`.len()`) instead of `IntoIterator`. Single-token typo: `flat_map` → `map`.

#### Code Pointer

`cli/browser4-cli/src/main.rs:11011 — change `.flat_map(` to `.map(``

#### AI Suggested Improvement

- Run `cargo check` as a pre-commit hook or CI check to catch compilation errors before they land on the branch
- Add a `cargo check` step to the `./b4w.ps1` pre-build phase that fails fast before attempting full compilation
- Consider a `--check` flag to verify the CLI can compile without executing

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Compilation error (`flat_map` → `map`) blocks all CLI functionality. The fix is a one-character change with zero risk of regression. The pre-commit `cargo check` suggestion is sensible but treat it as a follow-up improvement, not a gate on the fix.

---

### Issue 2: `goto` reports success but session does not persist for subsequent commands

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Run `./b4w.ps1 goto "http://localhost:18080/..."` (no prior session).
2. Immediately run `./b4w.ps1 snapshot -i` or `./b4w.ps1 list`.

#### Expected Behavior

After `goto` reports navigation success, subsequent commands should find the active session.

#### Actual Behavior

`goto` outputs 'Reusing session DEFAULT' and 'Navigated to...' appearing to succeed. But `snapshot -i` fails with 'No active session' and `list` shows no sessions.

#### Root Cause Analysis

`goto` appears to attempt reusing a session that doesn't exist. The 'Reusing session DEFAULT' message is misleading — it may be trying to reconnect to a dead session tracker rather than creating a new session. `open` works correctly while `goto` does not. Investigation needed in session lifecycle of goto vs open.

#### Code Pointer

`cli/browser4-cli/src/ — session management in the `goto` command handler vs `open``

#### AI Suggested Improvement

- Fix `goto` to properly create/persist a session when none exists
- Add a diagnostic message when the session store is empty: 'No active session — creating a new one.'
- Add an integration test: `goto <url>` followed by `list` should show an active session

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] `goto` silently fails to create a session when none exists, breaking the fundamental `goto → interact` workflow. The misleading "Reusing session DEFAULT" message makes the bug harder to diagnose. `open` works correctly, so a likely quick fix is sharing its session-creation path.

---

### Issue 3: `snapshot -i` session error message uses wrong binary name

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `./b4w.ps1 snapshot -i` when no active session exists.

#### Expected Behavior

Error message suggests the correct command for the user's invocation method.

#### Actual Behavior

Shows 'run `browser4-cli open <url>`' — but the user is running `./b4w.ps1`, not `browser4-cli`.

#### Root Cause Analysis

Error messages hardcode the generic binary name `browser4-cli` instead of using the actual invocation name.

#### Code Pointer

`cli/browser4-cli/src/ — error message templates for session-related errors`

#### AI Suggested Improvement

- Detect the actual binary name used at invocation and substitute it in error messages
- Or standardize tips to use a placeholder like '{cli} open <url>' that gets replaced at runtime

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Hardcoding `browser4-cli` in error messages is actively confusing for users invoking via `./b4w.ps1` or `./b4w.sh`. Runtime binary-name detection (`std::env::args()[0]`) is the correct fix and is a one-liner in Rust. Related to Issue 2 since both surface when no session exists, but distinct root cause (session lifecycle vs. message templating).

---

### Issue 4: `get` and `htmlsnapshot get` have different selector mechanisms — naming is confusing

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1. Run `get text ".result-panel"` — fails (AXTree, not CSS).
2. Run `htmlsnapshot get text "#result-data"` — succeeds (CSS selectors).

#### Expected Behavior

Both `get` commands use the same selector mechanism, or the distinction is clearly visible in the command name.

#### Actual Behavior

The subcommand `get` appears in two different command families (`get` top-level vs `htmlsnapshot get`) with different semantics — AXTree vs CSS selectors. The shared verb implies similar behavior.

#### Root Cause Analysis

`get` queries the live accessibility tree via CDP; `htmlsnapshot get` queries stored HTML via CSS selectors. Naming collision in the command hierarchy.

#### Code Pointer

`cli/browser4-cli/src/ — command dispatch for `get` vs `htmlsnapshot get``

#### AI Suggested Improvement

- Rename top-level `get` to `ax-get` or `tree-get` to clarify it operates on the accessibility tree
- Or rename `htmlsnapshot get` to `htmlsnapshot select` or `htmlsnapshot css`
- Add a cross-reference in help: 'For CSS selectors, use htmlsnapshot get instead'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Two subcommands named `get` with different selector engines (AXTree vs. CSS) is a genuine discoverability trap. However, `get` is likely the more frequently-used command and renaming it is a breaking change. The safest first step is adding a cross-reference in help text: when `get` returns no results, suggest `htmlsnapshot get` for CSS selectors. Tying this to Issue 5 makes sense — both are about the `get`/`htmlsnapshot` boundary.

---

### Issue 5: `htmlsnapshot` partially misses dynamically-inserted DOM elements after form submission

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. Submit a form that updates the page DOM dynamically.
2. Run `htmlsnapshot` to capture.
3. Run `htmlsnapshot get text ".result-panel"` — not found.

#### Expected Behavior

After recapturing with `htmlsnapshot`, all visible DOM content should be queryable.

#### Actual Behavior

`#result-data` and `#validation-summary` worked, but `.result-panel` was not found. Inconsistent capture of dynamic content.

#### Root Cause Analysis

`htmlsnapshot` captures via the scrape API which may re-fetch rather than serialize the live DOM. The SKILL.md §5 warns about this, but the partial capture (some dynamic elements work) is confusing.

#### AI Suggested Improvement

- Document exactly which types of dynamic content `htmlsnapshot` can and cannot capture
- Add a `--live` flag to serialize the live DOM via CDP instead of re-fetching
- Surface a warning when `htmlsnapshot get` returns no results after known interactions

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The partial capture (some dynamic elements work, others don't) is worse than total failure because it's non-deterministic. Since SKILL.md §5 already warns about this, the priority is surfacing a runtime warning when `htmlsnapshot get` returns no results, and documenting the re-fetch-vs-live-DOM distinction explicitly. The `--live` flag is a good long-term feature request but should not block the documentation fix. Cross-reference with Issue 4 — both benefit from clearer `get`/`htmlsnapshot` semantics.

---

### Issue 6: Auto-build spinner UI lacks stage-level progress detail

**Severity:** Low
**Category:** UX

#### Reproduction

Run any `./b4w.ps1` command after source changes.

#### Expected Behavior

Build progress shows compilation stages or file counts.

#### Actual Behavior

Simple spinner with time estimates. Server startup shows '⠋ Starting server... (7s elapsed, ~113s remaining)' but no indication of what stage (JVM, Spring, browser).

#### Root Cause Analysis

The spinner provides time estimates but no insight into what's happening during server startup.

#### Code Pointer

`cli/browser4-cli/src/ — server startup spinner/progress display`

#### AI Suggested Improvement

- Show underlying process output in real-time during builds
- Add a `--verbose` flag that streams build output
- Add a stage indicator: 'Compiling Rust...', 'Starting JVM...', 'Waiting for HTTP...'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The spinner's time estimates are helpful but opaque — users can't tell if the build is stuck or progressing. A stage indicator ("Compiling Rust...", "Starting JVM...", "Waiting for HTTP...") is low-effort and high-impact. The `--verbose` flag for streaming output is a natural companion. Related to Issue 8 — both address the startup/setup experience.

---

### Issue 7: `snapshot -i` interactive mode value proposition is unclear

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Task asked to use `snapshot -i` but regular auto-snapshot sufficed for all interactions.

#### Expected Behavior

Documentation explains when `-i` is preferred over default snapshot behavior.

#### Actual Behavior

Could not evaluate interactive mode due to session issue. Regular snapshot provided sufficient information. SKILL.md only warns that `-i` strips generic `<div>` containers — unclear when it's beneficial.

#### Root Cause Analysis

The interactive mode's unique value vs default snapshot isn't documented. The SKILL.md warning about div-stripping may actually discourage its use.

#### AI Suggested Improvement

- Document what `-i` mode does differently with a concrete example
- Add a help section comparing `-i` vs `-v 0` vs default for different use cases
- Consider if `-i` should be the default for discoverability

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The value of `-i` mode is genuinely unclear from current docs. The SKILL.md warning about div-stripping may actively discourage its use. Since the issue author couldn't evaluate it due to Issue 2, the immediate action is documentation: explain what `-i` does differently, show a concrete before/after example, and recommend when to use it vs. default. No code changes needed unless the mode is provably useless.

---

### Issue 8: Server cold-start latency (~11s) is acceptable but could be documented

**Severity:** Low
**Category:** UX

#### Reproduction

Run `./b4w.ps1 open <url>` as the first command in a session.

#### Expected Behavior

Server starts quickly or provides clear progress indicators.

#### Actual Behavior

11-second cold start with spinner. Subsequent commands were instant. Reasonable for JVM backend but unexpected for first-time users.

#### Root Cause Analysis

Spring Boot JVM application cold start includes JVM init, Spring context loading, and browser driver setup.

#### AI Suggested Improvement

- Consider a `browser4-cli daemon` command to pre-start the server
- Document the expected first-launch latency in the quick start guide
- The spinner already helps — just setting expectations would reduce perceived friction

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] 11s cold start for a Spring Boot app is expected and reasonable. The spinner (noted in Issue 6) already mitigates this. The fix is setting expectations: document the latency in the quick-start guide and consider a "first run may take ~10s" note on the initial spinner line. The `daemon` command suggestion is heavyweight for this benefit and should be deferred. Closely related to Issue 6 — consider addressing both under a single "startup UX" work item.

---

## Overall Assessment

**Completion Status:** Successful — the form was filled with all required fields, submitted, and the result was verified. All submitted values were confirmed correct in the response payload.

**Success Rate:** 85% — 21 of 25 steps succeeded directly; 3 required workarounds; 1 step (snapshot -i) was skipped due to the session issue.

**Issues Found:** 8

**Major Blockers:** Two blockers: (1) Compilation error in main.rs that prevented ANY CLI command from running — required a code fix before evaluation could proceed. (2) `goto` reported success but didn't persist a session, requiring use of `open` as a workaround. Both are reliability issues in the critical path.

**Most Confusing Aspects:** The most confusing aspect was the `goto` command reporting success ('Reusing session DEFAULT', 'Navigated to...') but the session not being available for subsequent commands. This is a silent failure that undermines trust in the tool. Second, the distinction between `get` (accessibility tree) and `htmlsnapshot get` (CSS selectors) is easy to miss — they share the same verb but work completely differently.

**Most Valuable Improvements:** 1. Fix the `goto` session persistence bug — it's the first command every user runs and it silently fails. 2. Add a pre-commit/CI `cargo check` to prevent compilation errors from landing on the branch. 3. Rename or visually distinguish `get` vs `htmlsnapshot get` to prevent selector confusion. 4. Use the actual binary name in error messages instead of hardcoding `browser4-cli`.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Compilation error prevents CLI from launching

Run `./b4w.ps1 help` from the repo root with current code.

#### Issue 2: `goto` reports success but session does not persist for subsequent commands

1. Run `./b4w.ps1 goto "http://localhost:18080/..."` (no prior session).
2. Immediately run `./b4w.ps1 snapshot -i` or `./b4w.ps1 list`.

#### Issue 3: `snapshot -i` session error message uses wrong binary name

Run `./b4w.ps1 snapshot -i` when no active session exists.

#### Issue 4: `get` and `htmlsnapshot get` have different selector mechanisms — naming is confusing

1. Run `get text ".result-panel"` — fails (AXTree, not CSS).
2. Run `htmlsnapshot get text "#result-data"` — succeeds (CSS selectors).

#### Issue 5: `htmlsnapshot` partially misses dynamically-inserted DOM elements after form submission

1. Submit a form that updates the page DOM dynamically.
2. Run `htmlsnapshot` to capture.
3. Run `htmlsnapshot get text ".result-panel"` — not found.

#### Issue 6: Auto-build spinner UI lacks stage-level progress detail

Run any `./b4w.ps1` command after source changes.

#### Issue 7: `snapshot -i` interactive mode value proposition is unclear

Task asked to use `snapshot -i` but regular auto-snapshot sufficed for all interactions.

#### Issue 8: Server cold-start latency (~11s) is acceptable but could be documented

Run `./b4w.ps1 open <url>` as the first command in a session.

