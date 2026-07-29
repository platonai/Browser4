# Issues: session-management

> **Source:** `20260709-224834-session-management.full.md` | **Date:** 20260709-224834 | **Mode:** dev

## Scenario Background

### Task

✅ **All 8 task steps completed successfully.**

| Step | Action | Result |
|------|--------|--------|
| 1 | Open session "research" → Wikipedia | Session created, navigated to Wikipedia (redirected to Headless browser) |
| 2 | Open session "news" → Hacker News | Session created, navigated to news.ycombinator.com |
| 3 | Snapshot in "news" session | Confirmed: Hacker News, 107 KB snapshot |
| 4 | Snapshot in "research" session | Confirmed: Headless browser - Wikipedia, 88 KB snapshot |
| 5 | List all sessions | Both "news" and "research" shown as Active/Reuse |
| 6 | Close "news" session | Closed successfully |
| 7 | List sessions again | Only "research" remains |
| 8 | Close all remaining | 1 session closed, list confirms empty |

---

### Execution Context

**Key Commands:**

```
1. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
2. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"
3. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s news goto "https://news.ycombinator.com"
4. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s news snapshot -i
5. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s research snapshot -i
6. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list
7. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s news close
8. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list
9. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- close-all
10. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list
```

**Major steps performed:**
1. Read SKILL.md, AGENTS.md, README.md, and cli/README.md to understand the command set
2. Ran `--help` to see the full command list
3. Used `goto` with `-s <name>` to create named sessions (auto-opens when no session exists)
4. Used `snapshot -i` to verify page content in each session
5. Used `list` to see all sessions
6. Used `close -s <name>` to close a specific session
7. Used `close-all` to clean up remaining sessions

**Important decisions made:**
- Used `goto` instead of `open` → `goto` since `goto` auto-opens sessions and is the documented recommended workflow
- Used `snapshot -i` (interactive elements only) for cleaner verification output
- Used `close-all` for step 8 instead of `close -s research` since the task said "Close all remaining sessions"

**Workarounds required:** None. All commands worked as documented.

---

---

## Issues Found (6 issues)

### Issue 1: Template variables not substituted in task instructions

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read the task instructions at the top of this evaluation. The variables `$RepoRootPath`, `$helpCmd`, `$cliInvocation`, and `$skillPath` appear as literal template variables rather than their resolved values.

#### Expected Behavior

Template variables should be substituted with concrete values before the instructions are given to an evaluator (e.g., `$cliInvocation` → `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`).

#### Actual Behavior

Variables appear as raw `$variableName` tokens requiring the evaluator to infer the correct values from the project documentation.

#### Root Cause Analysis

The evaluation framework uses a templating system that didn't apply variable substitution before emitting the task prompt. The substitution definitions likely exist in a harness configuration but weren't applied.

#### AI Suggested Improvement

- Add a pre-processing step in the evaluation harness to substitute template variables with values defined in the evaluation configuration
- Alternatively, provide a legend at the top of the task that maps each variable to its concrete value

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] This is a bug in the evaluation harness's template-substitution pipeline, not in the browser4-cli product itself. The product team likely doesn't own the evaluation framework. Forward to the eval-infra owners.

---

### Issue 2: `open --help` does not mention `-s` global option for named sessions

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- open --help`. The output describes session reuse behavior but never mentions the `-s <name>` global flag that enables named sessions.

#### Expected Behavior

Since named sessions are a core feature, `open --help` should mention `-s <name>` in its examples or notes section (e.g., `browser4-cli -s mysession open https://example.com`).

#### Actual Behavior

The help output shows only `browser4-cli open https://browser4.io` and `browser4-cli open --headed https://browser4.io` examples. Named session usage is documented only in the top-level `--help` and `cli/README.md`.

#### Root Cause Analysis

The `-s` flag is a global option rendered by the top-level help generator, but per-command help doesn't cross-reference global options that are essential to that command's operation.

#### Code Pointer

``cli/browser4-cli/src/help.rs` or `cli/browser4-cli/src/commands.rs` — the `CommandDef` for `open` should include a note or example showing `-s` usage.`

#### AI Suggested Improvement

- Add a note to `open --help`: "Combine with `-s <name>` to manage multiple independent sessions"
- Add an example: `browser4-cli -s mysession open https://example.com`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] `-s` is a foundational global flag that directly affects how `open` works — a user reading `open --help` should learn about named sessions there, not just at the top level. Low implementation cost: add a note and one example line to the `CommandDef`.

---

### Issue 3: `close --help` is too minimal

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- close --help`. Output is a single line: "Close the browser" with no arguments, options, notes, or examples.

#### Expected Behavior

The help should explain:
- That `-s <name>` targets a specific named session
- What happens when no `-s` is provided (closes the default session)
- An example closing a named session

#### Actual Behavior

A single description line with no additional context.

#### Root Cause Analysis

The `CommandDef` for `close` in `commands.rs` lacks notes and examples. This is particularly impactful because `close` and `close-all` have different semantics (one closes a single session, the other closes all), and a new user needs help to distinguish them.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — the `CommandDef` for `close` needs notes and examples added.`

#### AI Suggested Improvement

- Add notes explaining the difference between `close`, `close-all`, and `kill-all`
- Add examples: `browser4-cli -s mysession close` and `browser4-cli close`
- Mention that `close` terminates the browser process for the current/default session

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] `close` is destructive and has sibling commands (`close-all`, `kill-all`) with different semantics — the help should explain the distinction, session targeting via `-s`, and default-session behavior. Same fix pattern as Issue 2; these two should be addressed together for consistency.

---

### Issue 4: `goto` does not indicate when a URL redirect occurs

**Severity:** Low
**Category:** UX

#### Reproduction

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"`. The output shows `Page URL: https://en.wikipedia.org/wiki/Headless_browser` with no indication that a redirect from `Browser_automation` → `Headless_browser` took place.

#### Expected Behavior

The output should note that a redirect occurred, or at least show the originally requested URL alongside the final URL. This helps users understand why they landed on a different page.

#### Actual Behavior

Only the final URL is shown. The user could mistakenly think they navigated to the wrong page.

#### Root Cause Analysis

The page metadata display in the `goto` command handler shows the final `document.location.href` without comparing it to the requested URL.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — the `goto` command handler where page metadata is printed.`

#### AI Suggested Improvement

- When the final URL differs from the requested URL, add a line: `Redirected to: <final URL>` or `(redirected from <original URL>)`
- Consider showing both URLs: `Requested: ... → Final: ...`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Silent redirects undermine user trust ("did I navigate to the wrong page?"). Showing the final URL alongside the requested URL when they differ is a one-line comparison in the `goto` handler. Low effort, meaningful clarity.

---

### Issue 5: `list` command does not show page URL or title for each session

**Severity:** Medium
**Category:** UX

#### Reproduction

Run `list` with multiple active sessions. The output shows Name, Session ID, Status, and Next open — but no page URL or title.

#### Expected Behavior

In a multi-session workflow, users need to distinguish sessions by more than just name. Showing the current page URL and/or title would help users identify which session is which without having to snapshot each one.

#### Actual Behavior

Only session metadata (name, ID, status) is shown. To find out what page a session is on, the user must run a separate `snapshot` command.

#### Root Cause Analysis

The `list` command handler queries session metadata from the backend but doesn't include page-level information in the response or display.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — the `list` command handler; `browser4-rest` — the session listing endpoint may need to include current URL/title.`

#### AI Suggested Improvement

- Add `URL` and `Title` columns to the `list` output when sessions are active
- Consider a `list --verbose` flag that shows full page details
- The `--json` output already includes `sessions` array — adding URL/title fields there would be backward-compatible

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] In multi-session workflows, `list` is the dashboard — without URL/title, users must `snapshot` each session just to identify which is which. This likely requires both CLI display changes and backend endpoint additions, so it's higher effort than Issues 2–4, but the UX payoff is real.

---

### Issue 6: Snapshot defaults to file output rather than stdout

**Severity:** Low
**Category:** UX

#### Reproduction

Run `snapshot -i`. The output shows a preview of 10 lines and a file path, with tips about using `--stdout` to print inline.

#### Expected Behavior

For a CLI tool, the default behavior of printing to stdout (or at least printing the full snapshot inline) would be more intuitive for first-time users, especially when using `-i` (interactive-only) which produces compact output.

#### Actual Behavior

The snapshot is saved to a timestamped YAML file and only a 10-line preview is shown. The user must discover `--stdout` to get inline output.

#### Root Cause Analysis

This is likely a deliberate design choice for large snapshots (200KB+), but the threshold for file-vs-inline could be adaptive — small snapshots from `-i` mode are often small enough to display inline.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` — the snapshot rendering logic.`

#### AI Suggested Improvement

- When `-i` (interactive) is combined with default viewport, show the full output inline (interactive snapshots are typically small)
- Add a clear hint after the preview: "Run with `--stdout` to see full output in terminal"
- Consider a configurable size threshold: auto-display inline when snapshot is under N KB

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] The file-default behavior is a deliberate design choice to avoid terminal flooding from large snapshots (200KB+), and `--stdout` already exists as the opt-in escape hatch. The adaptive-threshold idea (inline when small) is reasonable but adds complexity without a clear user-demand signal. Revisit if users consistently complain.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Template variables not substituted in task instructions

Read the task instructions at the top of this evaluation. The variables `$RepoRootPath`, `$helpCmd`, `$cliInvocation`, and `$skillPath` appear as literal template variables rather than their resolved values.

#### Issue 2: `open --help` does not mention `-s` global option for named sessions

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- open --help`. The output describes session reuse behavior but never mentions the `-s <name>` global flag that enables named sessions.

#### Issue 3: `close --help` is too minimal

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- close --help`. Output is a single line: "Close the browser" with no arguments, options, notes, or examples.

#### Issue 4: `goto` does not indicate when a URL redirect occurs

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"`. The output shows `Page URL: https://en.wikipedia.org/wiki/Headless_browser` with no indication that a redirect from `Browser_automation` → `Headless_browser` took place.

#### Issue 5: `list` command does not show page URL or title for each session

Run `list` with multiple active sessions. The output shows Name, Session ID, Status, and Next open — but no page URL or title.

#### Issue 6: Snapshot defaults to file output rather than stdout

Run `snapshot -i`. The output shows a preview of 10 lines and a file path, with tips about using `--stdout` to print inline.

