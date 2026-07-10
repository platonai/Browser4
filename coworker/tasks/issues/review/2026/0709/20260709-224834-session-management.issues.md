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

## Issues Found (6 issues)
> **Review complete:** 0 approved, 6 deferred/rejected

### Issue 1: Template variables not substituted in task instructions

**Severity:** Low
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a pre-processing step in the evaluation harness to substitute template variables with values defined in the evaluation configuration

---

### Issue 2: `open --help` does not mention `-s` global option for named sessions

**Severity:** Medium
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a note to `open --help`: "Combine with `-s <name>` to manage multiple independent sessions"

---

### Issue 3: `close --help` is too minimal

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add notes explaining the difference between `close`, `close-all`, and `kill-all`

---

### Issue 4: `goto` does not indicate when a URL redirect occurs

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - When the final URL differs from the requested URL, add a line: `Redirected to: <final URL>` or `(redirected from <original URL>)`

---

### Issue 5: `list` command does not show page URL or title for each session

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add `URL` and `Title` columns to the `list` output when sessions are active

---

### Issue 6: Snapshot defaults to file output rather than stdout

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - When `-i` (interactive) is combined with default viewport, show the full output inline (interactive snapshots are typically small)

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
