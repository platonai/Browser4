# Issues: visual-screenshot-controls

> **Source:** `20260728-030624-visual-screenshot-controls.full.md` | **Date:** 20260728-030624 | **Mode:** dev

## Scenario Background

### Task

All 10 steps completed successfully:

| Step | Action | Result |
|------|--------|--------|
| 1 | `resize 1280 900` | ✓ Resized to 1280×900 |
| 2 | `goto "https://en.wikipedia.org/wiki/Large_language_model"` | ✓ Page loaded, title: "Large language model - Wikipedia" |
| 3 | `wait --load networkidle` | ✓ Wait complete |
| 4 | `screenshot` | ✓ Saved to `.browser4-cli/snapshot/` |
| 5 | `scroll down 800` (×4) | ✓ Scrolled to position 3200 |
| 6 | `wait --text "Architecture"` | ✓ Text found |
| 7 | `screenshot --full-page --filename .test-sessions/fullpage-llm-article.png` | ✓ 7.0 MB file |
| 8 | `eval "window.scrollTo(0, 0)"` | ✓ Scrolled to top (via JS workaround) |
| 9 | `wait 2000` → `screenshot --filename .test-sessions/top-of-page.png` | ✓ 482 KB file |
| 10 | `pdf --filename .test-sessions/llm-article.pdf` | ✓ 2.4 MB file |

**Workaround required:** Step 8 (scroll to top) had no direct command — `scroll` only supports relative offsets. I used `eval "window.scrollTo(0, 0)"` as a JavaScript escape hatch.

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 resize 1280 900` — viewport sizing
2. `./b4w.ps1 goto "https://en.wikipedia.org/wiki/Large_language_model"` — navigation
3. `./b4w.ps1 wait --load networkidle` — wait for idle
4. `./b4w.ps1 screenshot` — default viewport screenshot
5. `./b4w.ps1 scroll down 800` (×4) — incremental scrolling
6. `./b4w.ps1 wait --text "Architecture" --timeout 15000` — wait for section heading
7. `./b4w.ps1 screenshot --full-page --filename .test-sessions/fullpage-llm-article.png` — full-page capture
8. `./b4w.ps1 eval "window.scrollTo(0, 0)"` — return to top (JS workaround)
9. `./b4w.ps1 wait 2000` → `./b4w.ps1 screenshot --filename .test-sessions/top-of-page.png` — settled top-of-page shot
10. `./b4w.ps1 pdf --filename .test-sessions/llm-article.pdf` — PDF export

**Key decisions:**
- Used `eval` to scroll to absolute position since `scroll` is relative-only
- Waited for "Architecture" section heading as the visibility anchor (a known Wikipedia article section)
- Stored all output files under `.test-sessions/` as instructed
- Used 800px scroll increments (~viewport height minus chrome) to avoid skipping content

**Workarounds:**
- Scroll-to-top: used `eval "window.scrollTo(0, 0)"` — no built-in `scroll top` or absolute positioning

---

---

## Issues Found (8 issues)

### Issue 1: No absolute scroll positioning — 'scroll to top/bottom' missing

**Severity:** Medium
**Category:** Product

#### Reproduction

Try to scroll back to the top of a page after scrolling down. `scroll up 99999` works but is imprecise; `scroll top` is not a command.

#### Expected Behavior

A built-in way to scroll to absolute positions (top, bottom, or a specific pixel offset from top) without needing JavaScript eval.

#### Actual Behavior

The `scroll` command only supports relative offsets (`scroll up/down/left/right <pixels>`). Scrolling to top requires `eval "window.scrollTo(0, 0)"` — a JavaScript escape hatch that a non-technical user wouldn't know.

#### Root Cause Analysis

The `scroll` command was designed for incremental scrolling only. No `scroll-top`, `scroll-bottom`, or absolute position target was implemented.

#### Code Pointer

`cli/browser4-cli/src/commands/scroll.rs or where scroll command is defined`

#### AI Suggested Improvement

- Add `scroll top` and `scroll bottom` subcommands for absolute positioning
- Alternatively, accept `scroll to <pixels>` for absolute scroll-to-position
- Document the eval workaround in the scroll help text until the feature is added

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Scroll output lacks page height context

**Severity:** Low
**Category:** UX

#### Reproduction

Run `scroll down 800`. Output: `Scrolled down 800px (position: 800.0)`.

#### Expected Behavior

Output should include total page height so the user knows progress: e.g., `Scrolled down 800px (position: 800 / 8500, 9%)`.

#### Actual Behavior

Only shows the new scroll position without total page height. User cannot gauge how far through the page they are.

#### Root Cause Analysis

The scroll command output formatter doesn't query `document.body.scrollHeight` or equivalent to compute total page height.

#### AI Suggested Improvement

- Include total page height in scroll output (e.g., `position: 800/7200px (11%)`)
- This small change dramatically improves the UX of long-page scrolling

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Session reuse message could confuse first-time users

**Severity:** Low
**Category:** UX

#### Reproduction

Run `goto` when a DEFAULT session already exists. Output begins with `Using existing session DEFAULT (current page: ...)`.

#### Expected Behavior

Either a clean new session or a clear prompt/option to choose between reusing and refreshing.

#### Actual Behavior

The session was transparently reused. While convenient, a first-time user might not understand what 'DEFAULT session' means, why there's already a page loaded, or whether this affects the task.

#### Root Cause Analysis

Auto-session-reuse is the default behavior of `goto`. The message `Using existing session DEFAULT` is informational but assumes familiarity with the session model.

#### AI Suggested Improvement

- Add a brief explanation on first run: 'Sessions persist between commands. Use `close` to end a session.'
- Consider `goto --fresh` flag to force a new window
- Add session state indicator (clean vs. reused) to the output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: No `--stdout` or pipe support for screenshot command

**Severity:** Low
**Category:** Product

#### Reproduction

There is no `--stdout` flag on the `screenshot` command. Screenshots always write to a file.

#### Expected Behavior

A `--stdout` flag or pipe support to stream screenshot data, useful for chaining with image processing tools.

#### Actual Behavior

Screenshots must always go to a file. For quick inspection, the user must open the file separately.

#### Root Cause Analysis

The screenshot command was designed as a file-output-only operation. Streaming binary data to stdout may have been deprioritized.

#### AI Suggested Improvement

- Add `--stdout` flag to emit PNG data to stdout (similar to `snapshot --stdout`)
- This enables `browser4-cli screenshot --stdout | feh -` and similar pipelines

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Snapshot auto-generated on `goto` but path format is noisy

**Severity:** Low
**Category:** UX

#### Reproduction

Run `goto <url>`. Output includes `[Snapshot](/home/vincent/workspace/Browser4-4.12/.browser4-cli/snapshot/snapshot-2026-07-28T03-03-45-172Z.yml)`.

#### Expected Behavior

The snapshot path should use a relative or shortened format in the terminal output for readability, especially since the full absolute path is long.

#### Actual Behavior

The full absolute filesystem path is printed, which is noisy in terminal output (116 characters for the path alone).

#### Root Cause Analysis

The output formatter uses the absolute path. No relative-path or tilde-shortening is applied.

#### AI Suggested Improvement

- Use `~` shorthand for paths under the home directory
- Or show paths relative to the repo root when inside it
- Keep the full path accessible via a `--verbose` flag

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `screenshot --filename` help doesn't explain where 'snapshot directory' is

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `screenshot --help`. It says 'Bare filenames are saved to the snapshot directory' but doesn't say what or where that directory is.

#### Expected Behavior

The help should state the snapshot directory's default path (e.g., `~/.browser4-cli/snapshot/`) or how to discover it.

#### Actual Behavior

A new user must guess or learn from other output messages that the snapshot directory is `.browser4-cli/snapshot/` under the working directory.

#### Root Cause Analysis

The help text assumes the user already knows the snapshot directory location from prior context.

#### AI Suggested Improvement

- Add the default snapshot directory path to the `--filename` help text
- Or add a `browser4-cli config` command that shows paths

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: No `snapshot -i` option flow guidance in help text

**Severity:** Low
**Category:** Discoverability

#### Reproduction

A new user reading the help output sees `snapshot` with flags like `-v N`, `-i`, `--auto-diff` but no explanation of when to use which mode.

#### Expected Behavior

Brief guidance on when to use interactive vs. viewport-based snapshots.

#### Actual Behavior

The help output lists flags tersely: `-v N` for viewport chunks, `-i` for interactive, `--auto-diff` for diffs. A new user must consult SKILL.md for context.

#### Root Cause Analysis

The CLI help is intentionally brief, with detailed guidance in SKILL.md. However, new users may not know SKILL.md exists.

#### AI Suggested Improvement

- Add a reference to SKILL.md in the `snapshot --help` output
- Or add a one-line description of when to use each snapshot mode

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: `wait` numeric target vs. text target ambiguity could cause confusion

**Severity:** Low
**Category:** Discoverability

#### Reproduction

`wait 2000` waits 2 seconds (time). `wait e1` waits for element (selector). `wait Architecture` — would this wait for text 'Architecture' or try to match an element?

#### Expected Behavior

Clear documentation of positional argument disambiguation rules.

#### Actual Behavior

The docs say 'interpreted as milliseconds when numeric, otherwise as a CSS selector or element ref'. It's unclear what happens with a non-numeric string that's also a CSS selector.

#### Root Cause Analysis

The positional argument has dual semantics (time vs. selector). Non-numeric strings that aren't valid CSS selectors may fail confusingly.

#### AI Suggested Improvement

- Add an explicit example showing the `wait --text` form is preferred for text matching
- Consider warning when a non-numeric string isn't a valid CSS selector or ref

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 10 task steps completed. One step (scroll-to-top) required a JavaScript eval workaround since no absolute scroll command exists.

**Success Rate:** 100% — every step produced the expected output. The one workaround (eval for scroll-to-top) was functional and produced the correct result.

**Issues Found:** 8

**Major Blockers:** No major blockers. The task was completable without interruption. The only friction point was the absence of an absolute scroll command, which was trivially worked around with eval.

**Most Confusing Aspects:** 1) Understanding that the DEFAULT session persists and is reused automatically — a first-time user might not expect state from prior commands to carry over. 2) The snapshot directory location is not documented in the help text for commands that accept --filename. 3) The scroll command's relative-only design requires learning the eval escape hatch for absolute positioning.

**Most Valuable Improvements:** 1) Add `scroll top` / `scroll bottom` commands for absolute positioning. 2) Show total page height in scroll output. 3) Add --stdout support to screenshot for pipeline workflows. 4) Improve --filename help to state the default snapshot directory path.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: No absolute scroll positioning — 'scroll to top/bottom' missing

Try to scroll back to the top of a page after scrolling down. `scroll up 99999` works but is imprecise; `scroll top` is not a command.

#### Issue 2: Scroll output lacks page height context

Run `scroll down 800`. Output: `Scrolled down 800px (position: 800.0)`.

#### Issue 3: Session reuse message could confuse first-time users

Run `goto` when a DEFAULT session already exists. Output begins with `Using existing session DEFAULT (current page: ...)`.

#### Issue 4: No `--stdout` or pipe support for screenshot command

There is no `--stdout` flag on the `screenshot` command. Screenshots always write to a file.

#### Issue 5: Snapshot auto-generated on `goto` but path format is noisy

Run `goto <url>`. Output includes `[Snapshot](/home/vincent/workspace/Browser4-4.12/.browser4-cli/snapshot/snapshot-2026-07-28T03-03-45-172Z.yml)`.

#### Issue 6: `screenshot --filename` help doesn't explain where 'snapshot directory' is

Run `screenshot --help`. It says 'Bare filenames are saved to the snapshot directory' but doesn't say what or where that directory is.

#### Issue 7: No `snapshot -i` option flow guidance in help text

A new user reading the help output sees `snapshot` with flags like `-v N`, `-i`, `--auto-diff` but no explanation of when to use which mode.

#### Issue 8: `wait` numeric target vs. text target ambiguity could cause confusion

`wait 2000` waits 2 seconds (time). `wait e1` waits for element (selector). `wait Architecture` — would this wait for text 'Architecture' or try to match an element?

