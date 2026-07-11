# Browser4-CLI Usability Evaluation: Multi-Session Management

**Date:** 2026-07-09
**Evaluator:** Claude (AI Agent)
**Task:** Multi-session navigation, snapshot verification, and session lifecycle management

---

## A. Task Result

**Task completed successfully.** All 8 steps executed without errors:

1. Opened named session "research" → navigated to Wikipedia (followed redirect to `Headless_browser`)
2. Opened named session "news" → navigated to Hacker News
3. Snapshot in "news" session confirmed Hacker News page
4. Snapshot in "research" session confirmed Wikipedia article
5. Listed all sessions — both "research" and "news" shown as Active
6. Closed "news" session — confirmed with "Session closed. Browser terminated."
7. Listed sessions again — only "research" remained
8. Closed all sessions — "Closed 1 session(s)", verified empty list

---

## B. Execution Trace

### Commands Used

```
# Step 0: Setup
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- open --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- close --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- close-all --help

# Step 1: Open research session
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"

# Step 2: Open news session
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s news goto "https://news.ycombinator.com"

# Step 3: Snapshot in news session
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s news snapshot -v 0

# Step 4: Snapshot in research session
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s research snapshot -v 0

# Step 5: List all sessions
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list

# Step 6: Close news session
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- -s news close

# Step 7: List sessions again
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list

# Step 8: Close all
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- close-all
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list  # verify empty
```

### Major Steps

1. **Discovery phase**: Read `AGENTS.md`, `SKILL.md`, and `development.md` to understand CLI invocation pattern. Learned that `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <cmd>` is the correct invocation from repo root.

2. **Help exploration**: Ran `help` and individual `--help` for `goto`, `open`, `list`, `close`, `close-all` to understand session management semantics.

3. **Session creation**: Used `-s <name> goto <url>` for both sessions. The `goto` command auto-opens sessions, making explicit `open` unnecessary.

4. **Verification**: Used `snapshot -v 0` to capture accessibility trees and verify correct pages.

5. **Session lifecycle**: Used `list` → `-s news close` → `list` → `close-all` → `list` to verify proper cleanup.

### Important Decisions

- Chose `goto` over `open` because `goto` auto-opens/reconnects sessions and SKILL.md recommends it as the primary navigation command.
- Used `-v 0` with snapshot to get the viewport-paginated accessibility tree (as documented in SKILL.md).
- Used `list` without `--all` since working within a single workspace.

### Workarounds Required

None. All commands worked as documented on first attempt.

---

## C. Issues Found

### Issue 1: `close --help` output is too minimal and lacks session context

**Severity:** Medium

**Category:** Documentation

**Reproduction:** Run `browser4-cli close --help`

**Expected:** The help should explain that `close` closes the current or named session's browser window, mention the `-s` flag for targeting specific sessions, and distinguish it from `close-all`.

**Actual:** Output is a single line: "Close the browser". No mention of sessions, the `-s` flag, or relationship to `close-all`.

**Root Cause:** The `CommandDef` for `close` in `cli/browser4-cli/src/commands.rs` has a minimal description field. The help text is auto-generated from the command definition and was not expanded to cover session-specific behavior.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — the `close` CommandDef definition.

**AI Suggested Improvement:**
- Expand the `close` command description to mention named sessions via `-s` and distinguish from `close-all`
- Add examples showing `browser4-cli close` (close default session) and `browser4-cli -s mysession close` (close named session)
- Add a `Notes:` section explaining the difference between `close`, `close-all`, `kill-all`, and `stop`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Difference between `goto` and `open` is unclear from help text

**Severity:** Medium

**Category:** Discoverability

**Reproduction:** Compare `browser4-cli goto --help` and `browser4-cli open --help`

**Expected:** Clear guidance on when to use each command. For example: "Use `goto` for everyday navigation (it auto-manages sessions). Use `open` when you need to configure browser options like headed/headless mode or profile settings."

**Actual:** Both commands describe similar behavior (open/reconnect session, navigate to URL). `open` has additional `--headed`/`--headless`/`--profile` options but the core descriptions overlap significantly. A new user cannot easily determine which command is the "right" one for simple navigation.

**Root Cause:** The command descriptions in `commands.rs` are independently written without cross-referencing each other. The conceptual distinction (goto = everyday nav, open = session configuration) is not surfaced in the help text.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — the `goto` and `open` CommandDef definitions.

**AI Suggested Improvement:**
- Add a "When to use" note to each command's help: `goto` for routine navigation, `open` for browser configuration
- Cross-reference between the two: add "See also: `goto`" to `open --help` and vice versa
- Consider a decision tree in SKILL.md section 3 (Command Map) clarifying the distinction

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `list` command does not show current page URL or title per session

**Severity:** Low

**Category:** UX

**Reproduction:** Run `browser4-cli list` with multiple active sessions.

**Expected:** The list should show at least the current page URL or title for each active session, helping users identify which session is which without running additional snapshot commands.

**Actual:** Shows only Name, Session ID, Status, and Next open behavior. No indication of what page each session is currently displaying.

**Root Cause:** The `list` command backend only returns session metadata (ID, status, stale/reconnect info) but does not include the current page URL stored in the session state.

**Code Pointer:** The backend endpoint or MCP tool that serves `list` data, and `cli/browser4-cli/src/commands.rs` for the `list` command rendering.

**AI Suggested Improvement:**
- Add a "Page" column showing the current URL (truncated if long) for each active session
- Alternatively, add a `--verbose` / `-v` flag to `list` that shows additional details including URL, title, tab count
- Store the last-known URL per session slot so it can be displayed even when the backend session is stale

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Copy-paste template in SKILL.md does not show `-s` flag usage

**Severity:** Low

**Category:** Documentation

**Reproduction:** Read the "Copy-Paste Template" section in `skills/browser4-cli/SKILL.md`.

**Expected:** The template should show how to use named sessions since session management is a core feature. Multi-session workflows are common (e.g., comparing two sites, parallel research).

**Actual:** The template only shows `browser4-cli goto "https://example.com"` without the `-s <name>` flag. The `-s` flag is mentioned in §2 (Key Concepts > Sessions) but not demonstrated in the quick-start template.

**Root Cause:** The template prioritizes the simplest single-session case. Named sessions are documented separately but not woven into the primary copy-paste workflow.

**Code Pointer:** `skills/browser4-cli/SKILL.md` — the "Copy-Paste Template" section.

**AI Suggested Improvement:**
- Add a second copy-paste template block specifically for multi-session workflows
- Add a comment in the main template pointing to the `-s` flag: `# Use -s <name> for named sessions`
- Include a brief multi-session example in §2 (Key Concepts > Sessions)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Cargo build output pollutes command output in dev mode

**Severity:** Low

**Category:** UX

**Reproduction:** Run any `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>` from the repo root.

**Expected:** Clean command output without build-system noise, or a documented flag to suppress it.

**Actual:** Every invocation prints:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.48s
     Running `cli/browser4-cli/target/debug/browser4-cli ...`
```
before the actual command output. This adds 2 lines of noise per command.

**Root Cause:** Cargo always prints build status to stderr. The `--quiet` flag suppresses this but the development.md reference documents it primarily for output redirection scenarios, not as a general usability recommendation.

**Code Pointer:** `skills/browser4-cli/references/development.md` — the "Output Redirection in Dev Mode" section could be expanded.

**AI Suggested Improvement:**
- Add a prominent note in the development guide: "For cleaner output during interactive use, add `--quiet`: `cargo run --quiet --manifest-path ... -- <cmd>`"
- Consider recommending a shell alias in the development setup instructions: `alias b4='cargo run --quiet --manifest-path cli/browser4-cli/Cargo.toml --'`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: No explicit "active session" concept or switch command

**Severity:** Low

**Category:** UX

**Reproduction:** Open two named sessions. Try to interact with the second session without knowing about `-s`.

**Expected:** Either: (a) a `session switch <name>` command that sets the default session, or (b) clear documentation that every command requires `-s <name>` to target a non-default session.

**Actual:** The `-s` flag is shown in the global usage line but there's no explicit "how to work with multiple sessions" guide. The SKILL.md says "you rarely need to manage sessions manually" which implies single-session usage is the norm. Multi-session workflows require prefixing every command with `-s <name>`, which is verbose but functional.

**Root Cause:** The design philosophy favors explicit session targeting via `-s` on every command rather than mutable global state (an "active" session). This is arguably the correct design (explicit > implicit), but the multi-session workflow isn't documented as a first-class pattern.

**Code Pointer:** `skills/browser4-cli/SKILL.md` — could add a "Multi-Session Workflows" section.

**AI Suggested Improvement:**
- Add a "Multi-Session Workflows" subsection to SKILL.md §2 (Key Concepts > Sessions) with concrete examples
- Document the explicit `-s` pattern as intentional design (avoiding hidden mutable state)
- Consider a `session switch <name>` convenience command that sets the default for subsequent commands (while keeping `-s` as an override)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
**Fully completed.** All 8 steps executed successfully on first attempt with no errors or retries.

### Estimated Task Success Rate
**100%** — Every command worked as documented. No failures, no unexpected behavior.

### Number of Issues Found
**6 issues** (0 Critical, 0 High, 2 Medium, 4 Low)

### Major Blockers
None. The task was completed without any blockers.

### Most Confusing Aspects
1. **goto vs open distinction** — Both commands describe similar behavior. As a new user, I had to read both `--help` outputs carefully and cross-reference the SKILL.md to understand that `goto` is the everyday navigation command while `open` is for session configuration with browser options.

2. **Session targeting pattern** — The `-s <name>` flag prefix pattern is consistent but not prominently featured in quick-start documentation. It's listed in the global options of `help` and shown in one `goto` example, but the copy-paste template omits it entirely.

### Most Valuable Improvements
1. **Expand `close --help`** to cover session-specific behavior and relationship to other session commands (highest impact for least effort).
2. **Clarify `goto` vs `open`** in help text — add "When to use" guidance and cross-references.
3. **Add current URL to `list` output** — would dramatically improve multi-session situational awareness.
4. **Add multi-session examples to SKILL.md** — validate multi-session as a first-class workflow.

### Overall Usability Rating: **8/10**

**Strengths:**
- Session management via `-s <name>` is consistent and composable across all commands
- `goto` auto-creates sessions, eliminating boilerplate open/close steps
- `list` output is clean, well-formatted, and shows session status + next-open behavior
- Command feedback is clear and actionable ("Session opened: research", "Session closed. Browser terminated.", "Closed 1 session(s)")
- All commands worked correctly on first attempt — high reliability
- Snapshot provides rich accessibility tree data with element refs
- `close-all` provides a safe bulk cleanup without killing the backend

**Areas for improvement:**
- Help text for some commands is too minimal (`close` in particular)
- No current URL in `list` output — forces extra snapshot commands for context
- Multi-session workflows are functional but not documented as a first-class pattern
- Build output noise in dev mode (minor, cargo-level issue)
- `goto` vs `open` distinction unclear from help alone
