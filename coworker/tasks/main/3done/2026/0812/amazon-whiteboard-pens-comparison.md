# Issues: amazon

> **Source:** `20260812-151402-amazon.full.md` | **Date:** 20260812-151402 | **Mode:** dev

## Scenario Background

### Task

The task was completed successfully. I navigated to Amazon.com, searched for "pens to draw on whiteboards", extracted the first 4 results, and wrote a comparison markdown file to `./target/amazon-whiteboard-pens-comparison.md`.

The four products compared (Amazon rendered in zh-CN localization with HKD pricing due to a pre-existing Hong Kong delivery location):

| # | Product | Price (HKD) | Rating | Reviews |
|---|---------|-------------|--------|---------|
| 1 | maxtek 磁性白板马克笔 (12-pack) | 54.85 | 4.3/5 | ~13,000 |
| 2 | Volcanics 磁性干擦马克笔 (12-pack) | 54.85 | 4.5/5 | ~10,000 |
| 3 | YISAN 干擦马克笔超细笔尖 (12-pack) | 59.95 | 4.7/5 | 873 |
| 4 | EXPO 磁性干擦马克笔 (8-pack) | 66.23 | 4.5/5 | 5,260 |

Full markdown (with summary table, per-product detail, and bottom-line recommendations) is at `./target/amazon-whiteboard-pens-comparison.md`.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — read the full command reference (rebuilt the Rust CLI from source first).
2. `./b4w.ps1 open --headless "https://www.amazon.com/"` — opened Amazon (reconnected to a pre-existing session).
3. `./b4w.ps1 snapshot -i` — captured interactive elements.
4. `./b4w.ps1 snapshot grep -i "textbox|search"` — located the search box.
5. `./b4w.ps1 fill e336 "pens to draw on whiteboards" --submit` — filled and submitted the search.
6. `./b4w.ps1 wait --load networkidle` — waited for results to settle.
7. `./b4w.ps1 page-info` — confirmed the search results page was active.
8. `./b4w.ps1 htmlsnapshot` — captured the page's HTML for inspection.
9. `./b4w.ps1 eval --help` — learned the eval interface.
10. `./b4w.ps1 eval --file .test-sessions/extract-*.js` — iterative live-DOM extraction (3 refinements + 1 debug pass).

**Major steps & decisions:**
- Followed the documented core loop: open → snapshot → find ref → fill → re-snapshot/extract.
- Started with `open --headless` per SKILL.md guidance (guarantees headless vs. `goto` which inherits backend default).
- Used `snapshot grep` to find the search box ref (`e336`) rather than dumping the full 134 KB tree.
- Chose `eval --file` for extraction because it avoids Windows shell-quoting issues (documented recommendation) and reads the live DOM (avoids stale `htmlsnapshot` data after JS updates).

**Workarounds required:**
- **Empty page title after submit** — the auto-snapshot fired before navigation finished; added `wait --load networkidle`.
- **Broken initial selectors** — Amazon's current markup has the title `h2` *wrapped by* an `<a>` (not containing one), and the review count lives in `a[href*="#customerReviews"]`. I inspected the first card's DOM via a debug eval to discover the correct selectors.
- **HKD/Chinese localization** — inherited from a pre-existing session's Hong Kong location; noted in the output rather than attempting to change the delivery address.

# C & D. Issues and Assessment (JSON)

```json
{
  "issues": [
    {
      "title": "open --headless silently reconnects to a stale session and ignores the flag, leaking dirty state into the task",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run `./b4w.ps1 open --headless \"https://www.amazon.com/\"` after a previous session (or another tool's session) has been left running in the DEFAULT slot. Output begins with 'Using existing session DEFAULT (current page: https://mp.weixin.qq.com/...)'. The browser reuses the old session instead of starting a fresh headless one.",
      "expected": "A fresh headless session opened at the requested URL, with the --headless flag honored, and no unrelated tabs/state from prior runs.",
      "actual": "The CLI reconnected to a pre-existing DEFAULT session containing 3 unrelated WeChat tabs, silently ignored --headless, and inherited that session's cookies/location (Amazon rendered in Chinese with HKD prices and a Hong Kong delivery address). page-info showed 4 tabs (3 stale + Amazon).",
      "rootCause": "Session resolution treats an already-active DEFAULT session as authoritative and reconnects regardless of the requested URL/display mode. The SKILL.md documents that --headless/--headed are ignored on reconnect, but there is no warning surfaced at runtime that the flag was ignored, and no visible indication that unrelated tabs/state were inherited. Needs investigation into the open/goto reconnect path to add a clear notice or a --fresh/--reopen option.",
      "codePointer": "cli/browser4-cli/src/main.rs (open/goto session-reconnect resolution; exact function to be confirmed)",
      "suggestion": "- When --headless/--headed is supplied but ignored because the session already exists, print a stderr warning ('display mode flag ignored: reconnecting to existing session')\n- Add a `--fresh` (or `--reopen`) flag to force a new session/window instead of reconnecting, so users can escape inherited state\n- Surface inherited-tab count (e.g. 'reconnecting to session with 3 existing tabs') in the open output so first-time users understand the context"
    },
    {
      "title": "Auto-snapshot after a submit-triggered navigation captures a mid-load state (empty page title)",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "`./b4w.ps1 fill e336 \"pens to draw on whiteboards\" --submit` on Amazon. The returned Page block shows the new search URL but `Page Title:` is empty.",
      "expected": "The post-action snapshot reflects the fully-loaded results page (title populated, results present) or clearly indicates the page is still loading.",
      "actual": "The automatic snapshot was captured during navigation; the title was empty and the snapshot file was of a transitional state. A first-time user could misread this as a failed search. A separate `wait --load networkidle` was required to get a stable page.",
      "rootCause": "The fill --submit path (press Enter) triggers a navigation, but the automatic post-action snapshot does not wait for the navigation/load to complete before capturing. Investigation needed into whether fill/click auto-snapshot can await a load event when the action causes a URL change.",
      "codePointer": "",
      "suggestion": "- When an interaction causes a URL change, wait for the new page's load (or at least its title) before capturing the automatic snapshot\n- Otherwise, print a transient notice in the Page block like '(page still loading)' when the title is empty, so the empty title is not misread as failure"
    },
    {
      "title": "Task instructions propagate the $(./b4w.ps1) anti-pattern that the tool's own docs call out as broken",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "The evaluation prompt's Preparation section says 'All browser4-cli commands use $(./b4w.ps1)' and step 1 says 'Run $(./b4w.ps1 help)', while the Command Invocation section and SKILL.md both say to use `./b4w.ps1 <command>` and explicitly warn that `$(./b4w.ps1) <command>` is bash command substitution that does NOT invoke the CLI.",
      "expected": "Consistent invocation guidance across the task prompt and the tool documentation.",
      "actual": "Two mutually contradictory invocation forms appear in the same prompt; a first-time user following the Preparation section literally would run command substitution and get confused (or worse, if using bash, test the wrong thing).",
      "rootCause": "Template/driving-prompt copy still contains the legacy $(...) notation while the in-repo docs (SKILL.md, CLAUDE.md) were updated to warn against it. This is a documentation consistency bug in the evaluation harness, not the CLI binary.",
      "codePointer": "",
      "suggestion": "- Normalize the driving prompt to use `./b4w.ps1 <command>` everywhere and remove the $(...) examples\n- Keep the existing SKILL.md/CLAUDE.md warning as the single source of truth for invocation"
    },
    {
      "title": "State-directory fallback warning mixes English and Chinese and repeats on some (not all) invocations",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run commands until the CLI state dir warning appears, e.g. `./b4w.ps1 fill e336 \"...\" --submit` produced: `browser4-cli: warning: cannot write CLI state to C:\\Users\\pereg\\.browser4 (拒绝访问。 (os error 5))`.",
      "expected": "A consistent, clearly-phrased warning in a single language, shown once (or not repeated) rather than intermittently.",
      "actual": "The warning mixes an English prefix with a Chinese OS error string ('拒绝访问' = access denied), and appears on some invocations but not others, which is noisy and confusing for a first-time user.",
      "rootCause": "The CLI surfaces the raw OS error string from the home-directory write attempt; on this machine the OS is localized to Chinese, so the OS error text is Chinese while the CLI prefix is English. The intermittent appearance suggests the state write is only attempted on certain code paths (or the warning is only emitted on first failure per invocation).",
      "codePointer": "",
      "suggestion": "- Localize the entire warning (or map the OS error to a stable English message) so the output is single-language\n- Emit the fallback warning at most once per session (dedupe) to reduce noise"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 4 task steps (open Amazon, search, extract first 4 results, write markdown to ./target/) completed; deliverable written to ./target/amazon-whiteboard-pens-comparison.md",
    "successRate": "100%",
    "issuesFound": 4,
    "majorBlockers": "None. The only significant surprise was the pre-existing session being reused (with --headless silently ignored and Hong Kong localization leaking in), which was worked around without blocking the task.",
    "mostConfusingAspects": "The silent reuse of a dirty pre-existing session (unrelated tabs, Chinese UI, HKD prices) when running `open --headless`; and the empty page title in the auto-snapshot right after submitting the search, which briefly looked like the search had failed.",
    "mostValuableImprovements": "Surface a warning when --headless/--headed is ignored on session reconnect and add a --fresh/--reopen escape hatch; make the post-action auto-snapshot wait for navigation to settle; dedupe and localize the state-dir warning.",
    "usabilityRating": 7
  }
}
```

---

**Summary of the evaluation:** browser4-cli was genuinely usable end-to-end for this real-world task. The onboarding materials (`help` output and `SKILL.md`) are thorough and well-organized, the auto-start backend worked on the first command, `snapshot grep` made element discovery easy, and `eval --file` proved to be a clean, Windows-safe extraction path. The main friction points were lifecycle-related: a stale session was silently reused (ignoring `--headless` and leaking prior state), the auto-snapshot after submit caught a mid-navigation frame, and a couple of minor documentation/messaging inconsistencies. None of these blocked completion, but they're the first things a new user would stumble on.

---

## Issues Found (4 issues)

### Issue 1: open --headless silently reconnects to a stale session and ignores the flag, leaking dirty state into the task

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `./b4w.ps1 open --headless "https://www.amazon.com/"` after a previous session (or another tool's session) has been left running in the DEFAULT slot. Output begins with 'Using existing session DEFAULT (current page: https://mp.weixin.qq.com/...)'. The browser reuses the old session instead of starting a fresh headless one.

#### Expected Behavior

A fresh headless session opened at the requested URL, with the --headless flag honored, and no unrelated tabs/state from prior runs.

#### Actual Behavior

The CLI reconnected to a pre-existing DEFAULT session containing 3 unrelated WeChat tabs, silently ignored --headless, and inherited that session's cookies/location (Amazon rendered in Chinese with HKD prices and a Hong Kong delivery address). page-info showed 4 tabs (3 stale + Amazon).

#### Root Cause Analysis

Session resolution treats an already-active DEFAULT session as authoritative and reconnects regardless of the requested URL/display mode. The SKILL.md documents that --headless/--headed are ignored on reconnect, but there is no warning surfaced at runtime that the flag was ignored, and no visible indication that unrelated tabs/state were inherited. Needs investigation into the open/goto reconnect path to add a clear notice or a --fresh/--reopen option.

#### Code Pointer

`cli/browser4-cli/src/main.rs (open/goto session-reconnect resolution; exact function to be confirmed)`

#### AI Suggested Improvement

- When --headless/--headed is supplied but ignored because the session already exists, print a stderr warning ('display mode flag ignored: reconnecting to existing session')
- Add a `--fresh` (or `--reopen`) flag to force a new session/window instead of reconnecting, so users can escape inherited state
- Surface inherited-tab count (e.g. 'reconnecting to session with 3 existing tabs') in the open output so first-time users understand the context

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Auto-snapshot after a submit-triggered navigation captures a mid-load state (empty page title)

**Severity:** Low
**Category:** Reliability

#### Reproduction

`./b4w.ps1 fill e336 "pens to draw on whiteboards" --submit` on Amazon. The returned Page block shows the new search URL but `Page Title:` is empty.

#### Expected Behavior

The post-action snapshot reflects the fully-loaded results page (title populated, results present) or clearly indicates the page is still loading.

#### Actual Behavior

The automatic snapshot was captured during navigation; the title was empty and the snapshot file was of a transitional state. A first-time user could misread this as a failed search. A separate `wait --load networkidle` was required to get a stable page.

#### Root Cause Analysis

The fill --submit path (press Enter) triggers a navigation, but the automatic post-action snapshot does not wait for the navigation/load to complete before capturing. Investigation needed into whether fill/click auto-snapshot can await a load event when the action causes a URL change.

#### AI Suggested Improvement

- When an interaction causes a URL change, wait for the new page's load (or at least its title) before capturing the automatic snapshot
- Otherwise, print a transient notice in the Page block like '(page still loading)' when the title is empty, so the empty title is not misread as failure

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Task instructions propagate the $(./b4w.ps1) anti-pattern that the tool's own docs call out as broken

**Severity:** Low
**Category:** Documentation

#### Reproduction

The evaluation prompt's Preparation section says 'All browser4-cli commands use $(./b4w.ps1)' and step 1 says 'Run $(./b4w.ps1 help)', while the Command Invocation section and SKILL.md both say to use `./b4w.ps1 <command>` and explicitly warn that `$(./b4w.ps1) <command>` is bash command substitution that does NOT invoke the CLI.

#### Expected Behavior

Consistent invocation guidance across the task prompt and the tool documentation.

#### Actual Behavior

Two mutually contradictory invocation forms appear in the same prompt; a first-time user following the Preparation section literally would run command substitution and get confused (or worse, if using bash, test the wrong thing).

#### Root Cause Analysis

Template/driving-prompt copy still contains the legacy $(...) notation while the in-repo docs (SKILL.md, CLAUDE.md) were updated to warn against it. This is a documentation consistency bug in the evaluation harness, not the CLI binary.

#### AI Suggested Improvement

- Normalize the driving prompt to use `./b4w.ps1 <command>` everywhere and remove the $(...) examples
- Keep the existing SKILL.md/CLAUDE.md warning as the single source of truth for invocation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: State-directory fallback warning mixes English and Chinese and repeats on some (not all) invocations

**Severity:** Low
**Category:** UX

#### Reproduction

Run commands until the CLI state dir warning appears, e.g. `./b4w.ps1 fill e336 "..." --submit` produced: `browser4-cli: warning: cannot write CLI state to C:\Users\pereg\.browser4 (拒绝访问。 (os error 5))`.

#### Expected Behavior

A consistent, clearly-phrased warning in a single language, shown once (or not repeated) rather than intermittently.

#### Actual Behavior

The warning mixes an English prefix with a Chinese OS error string ('拒绝访问' = access denied), and appears on some invocations but not others, which is noisy and confusing for a first-time user.

#### Root Cause Analysis

The CLI surfaces the raw OS error string from the home-directory write attempt; on this machine the OS is localized to Chinese, so the OS error text is Chinese while the CLI prefix is English. The intermittent appearance suggests the state write is only attempted on certain code paths (or the warning is only emitted on first failure per invocation).

#### AI Suggested Improvement

- Localize the entire warning (or map the OS error to a stable English message) so the output is single-language
- Emit the fallback warning at most once per session (dedupe) to reduce noise

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Verified in `cli/browser4-cli/src/state.rs:372-381` — the warning formats the raw `std::io::Error` (`{}` = `e`), which on a Chinese-localized Windows OS yields the mixed-language message ("拒绝访问。 (os error 5)"); since the branch already matches `PermissionDenied`, replacing the raw error with a stable English phrase is a trivial, correct fix. The intermittency is explained by code-path dependence (only state-writing commands hit `write_state`) plus the existing per-process `FALLBACK_WARNING_PRINTED` AtomicBool (state.rs:368-371) — so the suggested "dedupe per session" needs refinement: per-invocation dedupe already exists, and cross-invocation suppression would require a persisted sentinel (e.g., a marker file in the fallback dir), which is optional polish on top of the core message fix.

---

## Overall Assessment

**Completion Status:** Successful — all 4 task steps (open Amazon, search, extract first 4 results, write markdown to ./target/) completed; deliverable written to ./target/amazon-whiteboard-pens-comparison.md

**Success Rate:** 100%

**Issues Found:** 4

**Major Blockers:** None. The only significant surprise was the pre-existing session being reused (with --headless silently ignored and Hong Kong localization leaking in), which was worked around without blocking the task.

**Most Confusing Aspects:** The silent reuse of a dirty pre-existing session (unrelated tabs, Chinese UI, HKD prices) when running `open --headless`; and the empty page title in the auto-snapshot right after submitting the search, which briefly looked like the search had failed.

**Most Valuable Improvements:** Surface a warning when --headless/--headed is ignored on session reconnect and add a --fresh/--reopen escape hatch; make the post-action auto-snapshot wait for navigation to settle; dedupe and localize the state-dir warning.

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

#### Issue 1: open --headless silently reconnects to a stale session and ignores the flag, leaking dirty state into the task

Run `./b4w.ps1 open --headless "https://www.amazon.com/"` after a previous session (or another tool's session) has been left running in the DEFAULT slot. Output begins with 'Using existing session DEFAULT (current page: https://mp.weixin.qq.com/...)'. The browser reuses the old session instead of starting a fresh headless one.

#### Issue 2: Auto-snapshot after a submit-triggered navigation captures a mid-load state (empty page title)

`./b4w.ps1 fill e336 "pens to draw on whiteboards" --submit` on Amazon. The returned Page block shows the new search URL but `Page Title:` is empty.

#### Issue 3: Task instructions propagate the $(./b4w.ps1) anti-pattern that the tool's own docs call out as broken

The evaluation prompt's Preparation section says 'All browser4-cli commands use $(./b4w.ps1)' and step 1 says 'Run $(./b4w.ps1 help)', while the Command Invocation section and SKILL.md both say to use `./b4w.ps1 <command>` and explicitly warn that `$(./b4w.ps1) <command>` is bash command substitution that does NOT invoke the CLI.

#### Issue 4: State-directory fallback warning mixes English and Chinese and repeats on some (not all) invocations

Run commands until the CLI state dir warning appears, e.g. `./b4w.ps1 fill e336 "..." --submit` produced: `browser4-cli: warning: cannot write CLI state to C:\Users\pereg\.browser4 (拒绝访问。 (os error 5))`.

