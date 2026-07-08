# Issues: session-management

> **Source:** `20260706-211118-session-management.full.md` | **Date:** 20260706-211118 | **Mode:** dev

## Scenario Background

### Task

All 8 steps completed successfully:

| # | Step | Result |
|---|------|--------|
| 1 | Open "research" session → Wikipedia | ✅ Session opened, landed on `Headless_browser` (Wikipedia redirected `Browser_automation`) |
| 2 | Open "news" session → Hacker News | ✅ Session opened, landed on `news.ycombinator.com` |
| 3 | Snapshot "news" session | ✅ Confirmed URL `https://news.ycombinator.com/`, title "Hacker News", 57 KB / 924 nodes |
| 4 | Snapshot "research" session | ✅ Confirmed URL `https://en.wikipedia.org/wiki/Headless_browser`, 31 KB / 472 nodes |
| 5 | List all sessions | ✅ Showed "research", "news", and "(default)" — all "Active / Reuse" |
| 6 | Close "news" session | ✅ "Session closed." |
| 7 | List again | ✅ Only "research" and "(default)" remained |
| 8 | Close all remaining | ✅ "Closed 3 session(s)" (see Issue 2); final list is empty |

### Execution Context

**Key Commands:**

1. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- help`
2. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"`
3. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- -s news goto "https://news.ycombinator.com"`
4. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- -s news snapshot -v 0`
5. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- -s research snapshot -v 0`
6. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- list`
7. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- -s news close`
8. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- list`
9. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- close-all`
10. `cd "D:/workspace/Browser4/Browser4-4.11" && cd cli/browser4-cli && cargo run -- list`

**Important decisions:**
- Used the dev-mode `cargo run` invocation as prescribed; never used a global `browser4-cli` binary.
- Read `SKILL.md` and `help` output before any browser commands.
- Used `-s <name>` prefix to target named sessions throughout.
- Used `close-all` at the end rather than closing sessions individually.

**Workarounds:**
- None needed — all commands worked on the first attempt.

---

---

## Issues Found (7 issues)
> **Review complete:** 4 approved, 3 deferred/rejected

### Issue 1: Default session auto-created even when all commands use `-s`

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"
cargo run -- -s news goto "https://news.ycombinator.com"
cargo run -- list
```

#### Expected Behavior

Only the two explicitly named sessions ("research", "news") should appear in the session list.

#### Actual Behavior

A third "(default)" session with ID "DEFAULT" appears alongside the named sessions, even though no command was ever issued without a `-s` flag.

#### Root Cause Analysis

The daemon likely auto-creates a default session on startup or on the first `goto` call, regardless of whether `-s` was provided. This creates unnecessary session clutter.

#### Code Pointer

`Likely in the daemon/session initialization code — possibly in the backend service's session management layer.`

#### AI Suggested Improvement

- Only create the "(default)" session lazily when a command is actually issued without `-s`, not eagerly at daemon startup or on the first `goto` call.
- Hide the "(default)" session from `list` output when it has never been used (no navigation history).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 2: `close-all` reports inaccurate session count

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```
cargo run -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"
cargo run -- -s news goto "https://news.ycombinator.com"
cargo run -- -s news close
cargo run -- list             # shows 2 entries: "research" and "(default)"
cargo run -- close-all        # reports "Closed 3 session(s)"
```

#### Expected Behavior

`close-all` should report "Closed 2 session(s)" when `list` shows only 2 active sessions.

#### Actual Behavior

`close-all` reported "Closed 3 session(s)" when only 2 sessions were visible in the `list` output. This is a misreporting of either the count or an internal session that is hidden from the user.

#### Root Cause Analysis

Likely an internal/background session (perhaps the daemon's own management session or a previously closed-but-tracked session) is counted by `close-all` but not displayed by `list`. Alternatively, the counter might be naively incrementing without deduplication.

#### Code Pointer

`The counting logic in the `close-all` handler — likely in `cli/browser4-cli/src/` or the corresponding backend endpoint.`

#### AI Suggested Improvement

- Ensure `close-all` counts only the same sessions that `list` displays, so the numbers are consistent.
- If internal sessions exist that users should know about, display them in `list` with a distinct marker (e.g., "(internal)" status).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 3: "Next open" column in `list` output is undocumented

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```
cargo run -- list
```
Observe the "Next open" column showing "Reuse" for all sessions.

#### Expected Behavior

The SKILL.md or `help list` output should explain what "Next open" means, what values it can take, and how to change it.

#### Actual Behavior

The "Next open" column appears in `list` output with the value "Reuse" but neither the SKILL.md sessions section (§2) nor the `help` output explains this concept. A user encountering this cannot know whether "Reuse" means the session will be reused, a tab will be reused, or something else.

#### Root Cause Analysis

Missing documentation — the column was added to the CLI output without corresponding updates to SKILL.md or help text.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — §2 Sessions section, and the help text in the CLI source.`

#### AI Suggested Improvement

- Add a sentence to SKILL.md §2 explaining: "Next open controls what happens when `goto` targets a session name that already exists: `Reuse` reconnects to the existing browser window, `New` opens a fresh window."
- Add a `--help` entry for `list` that describes each column, or include the description in the main `help` output under "Browser sessions."

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 5: SKILL.md installation section mentions Node.js prerequisite but dev mode uses `cargo run`

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read SKILL.md §Installation. It says "Requires Node.js" and shows `npm install -g browser4-cli`. Switch to the Development section which says `cargo run`. The prerequisite for dev mode (Rust toolchain, Java for the backend) is not mentioned.

#### Expected Behavior

The Development section should explicitly list prerequisites for running from source: Rust toolchain (`cargo`), Java runtime for the backend, and that `cargo build`/`cargo run` will compile the binary.

#### Actual Behavior

The Development section assumes the reader already has these tools. A new contributor cloning the repo for the first time would not know they need Rust and Java.

#### Root Cause Analysis

The Development section was written for existing contributors, not for new users following the evaluation workflow.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — Development section (lines 241-253).`

#### AI Suggested Improvement

- Add a "Prerequisites for Development" bullet list at the top of the Development section: Rust (via `rustup`), Java 17+, and Git.
- Include a quick verification command: `cargo --version && java -version`.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

---

### Issue 4: No `info` or `detail` subcommand to inspect session state beyond the list table

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** DEFER

**Summary:** - Add a `session` or `info` subcommand that prints current URL, title, tab count, and uptime without triggering a new snapshot.

---

### Issue 6: `snapshot -v 0` output refers to a YAML file but provides no inline content preview

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** DEFER

**Summary:** - Show a brief inline preview (first 5-10 lines) of the snapshot in the default output, even when writing to a file.

---

### Issue 7: Session list column "Session ID" duplicates "Name" for named sessions

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** DEFER

**Summary:** - Either merge "Name" and "Session ID" into a single column, or display a distinct internal ID (e.g., a short UUID prefix) in the Session ID column.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Default session auto-created even when all commands use `-s`

```
cargo run -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"
cargo run -- -s news goto "https://news.ycombinator.com"
cargo run -- list
```

#### Issue 2: `close-all` reports inaccurate session count

```
cargo run -- -s research goto "https://en.wikipedia.org/wiki/Browser_automation"
cargo run -- -s news goto "https://news.ycombinator.com"
cargo run -- -s news close
cargo run -- list             # shows 2 entries: "research" and "(default)"
cargo run -- close-all        # reports "Closed 3 session(s)"
```

#### Issue 3: "Next open" column in `list` output is undocumented

```
cargo run -- list
```
Observe the "Next open" column showing "Reuse" for all sessions.

#### Issue 4: No `info` or `detail` subcommand to inspect session state beyond the list table

After opening multiple named sessions, run `cargo run -- help` and search for a way to inspect details about a specific session.

#### Issue 5: SKILL.md installation section mentions Node.js prerequisite but dev mode uses `cargo run`

Read SKILL.md §Installation. It says "Requires Node.js" and shows `npm install -g browser4-cli`. Switch to the Development section which says `cargo run`. The prerequisite for dev mode (Rust toolchain, Java for the backend) is not mentioned.

#### Issue 6: `snapshot -v 0` output refers to a YAML file but provides no inline content preview

```
cargo run -- -s research snapshot -v 0
```

#### Issue 7: Session list column "Session ID" duplicates "Name" for named sessions

```
cargo run -- list
```
Output:
```
Name                 | Session ID                               | Status   | Next open
---------------------+------------------------------------------+----------+----------
research             | research                                 | Active   | Reuse
(default)            | DEFAULT                                  | Active   | Reuse
```
