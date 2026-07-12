# Issues: hacker-news

> **Source:** `20260709-221858-hacker-news.full.md` | **Date:** 20260709-221858 | **Mode:** dev

### Execution Context

**Key Commands:**

| Step | Command |
|------|---------|
| 1 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- --help` |
| 2 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "https://news.ycombinator.com/news"` |
| 3 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- snapshot -v 0 --stdout` |
| 4 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "https://openai.com/index/gpt-5-6/"` |
| 5 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- htmlsnapshot` |
| 6 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- htmlsnapshot get text "title"` + `get attr "meta[name='description']" content` + `get all text "h2, h3"` |
| 7 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "https://github.com/JustVugg/colibri"` |
| 8 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- htmlsnapshot && htmlsnapshot get all text "article p, .markdown-body p"` |
| 9 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "https://www.patrick-breyer.de/en/eu-parliament-greenlights-chat-control-1-0-breyer-our-children-lose-out/"` |
| 10 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- htmlsnapshot && htmlsnapshot get all text ".entry-content p"` |

**Major steps:** Navigate to HN → capture accessibility snapshot → identify story links and URLs → navigate to each story → capture HTML snapshot → extract text content via CSS selectors.

**Important decisions:** I chose `goto` directly to the story URLs (from the snapshot) rather than clicking refs, since URLs were available in the snapshot and clicking requires worrying about ref lifecycle. I used `htmlsnapshot` for content extraction (static HTML) rather than `snapshot` (accessibility tree), since the task required reading page text, not interacting with elements.

**Workarounds required:** None — the documented workflow worked as expected.

---

## Issues Found (6 issues)
> **Review complete:** 2 approved, 4 deferred/rejected

### Issue 3: snapshot --stdout is missing from the help output and core loop documentation

**Severity:** Low
**Category:** Discoverability

#### Overview

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `browser4-cli snapshot --help` and look for `--stdout`. Then check the SKILL.md core loop section.

#### Expected Behavior

`--stdout` should be listed in the snapshot help and mentioned in the SKILL.md core loop / copy-paste template as a useful option.

#### Actual Behavior

The `--stdout` flag is mentioned in the tip from snapshot output ("💡 Tip: Use `--stdout` to print element refs inline instead of opening the snapshot file") and in a warning about snapshot file size ("Don't cat snapshot files... Use viewport pagination... or `snapshot --stdout --page 1` instead"), but is absent from the `snapshot --help` output and the SKILL.md's core loop template. A new user might never discover it.

#### Root Cause Analysis

The flag exists and works, but it's not documented in the primary help or the skill documentation's copy-paste templates.

#### Code Pointer

``cli/browser4-cli/src/` — the snapshot command's help text generator.`

#### AI Suggested Improvement

- Add `--stdout` to the `snapshot --help` output listing
- Include `snapshot -v 0 --stdout` in the SKILL.md copy-paste template as an alternative for inline viewing
- The tip that prints after snapshot output is good, so this is a documentation-only fix

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 4: htmlsnapshot requires separate capture step — easy to forget

**Severity:** Low
**Category:** UX

#### Overview

**Severity:** Low
**Category:** UX

#### Reproduction

Navigate to a page with `goto`, then immediately run `htmlsnapshot get text "h1"` without first running `htmlsnapshot` (capture).

#### Expected Behavior

Either a helpful error message that tells the user exactly what to do, or implicit capture when running `get` without a prior capture.

#### Actual Behavior

The `htmlsnapshot` command output includes a tip suggesting to run `htmlsnapshot get text "h1"`, but this only works after `htmlsnapshot` has been run first. If a user follows the tip directly after `goto`, they may encounter an error. The dependency on a prior capture step adds friction.

#### Root Cause Analysis

The capture-then-query model separates state capture from state reading. This design is deliberate (it enables repeated queries against a frozen snapshot), but the error message when the snapshot is missing could be more actionable.

#### Code Pointer

``cli/browser4-cli/src/` — the htmlsnapshot get command handler, where it checks for an existing snapshot.`

#### AI Suggested Improvement

- When `htmlsnapshot get` is run without a prior capture, auto-trigger a capture first (with a note) instead of erroring
- If auto-capture is not desired, improve the error message: "No HTML snapshot found. Run `browser4-cli htmlsnapshot` first to capture the page, then try again."
- Add a `htmlsnapshot get --capture` flag that does both in one step

#### Human Review

- [ ] **ACCEPT**
- [x] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 1: Two snapshot commands with overlapping names confuse new users

**Severity:** Medium
**Category:** UX / Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a parenthetical tag in the help output for each command family: `snapshot` could show "(accessibility tree — for interaction refs)" and `htmlsnapshot` could show "(static HTML — for content e...

---

### Issue 2: No one-command "summarize this page" for quick reading

**Severity:** Medium
**Category:** UX / Documentation

#### Review Result

**Decision:** DEFER

**Summary:** - Add a `htmlsnapshot readability` subcommand that uses a readability algorithm (like Mozilla's Readability) to extract the main article content in one step

---

### Issue 5: Verbose cargo invocation makes commands hard to read and type

**Severity:** Medium
**Category:** UX / Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `justfile` or `Makefile` target (e.g., `make cli -- goto "url"`) for development convenience

---

### Issue 6: Snapshot output format is highly verbose for simple tasks

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a `snapshot links` subcommand that extracts only link elements with their refs, URLs, and text, filtering out layout noise

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Two snapshot commands with overlapping names confuse new users

Run `browser4-cli --help` and observe two separate command families: `snapshot` and `htmlsnapshot`, each with their own subcommands (`get`, `grep`, `query`, etc.).

#### Issue 2: No one-command "summarize this page" for quick reading

Navigate to any article page and try to get a summary of its content. The only built-in option is `htmlsnapshot get text "<selector>"`, which requires knowing the page structure. The `extract` and `summarize` commands require an LLM API key.

#### Issue 3: snapshot --stdout is missing from the help output and core loop documentation

Run `browser4-cli snapshot --help` and look for `--stdout`. Then check the SKILL.md core loop section.

#### Issue 4: htmlsnapshot requires separate capture step — easy to forget

Navigate to a page with `goto`, then immediately run `htmlsnapshot get text "h1"` without first running `htmlsnapshot` (capture).

#### Issue 5: Verbose cargo invocation makes commands hard to read and type

The `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet --` prefix is 72 characters long. Every command requires this prefix when running from source.

#### Issue 6: Snapshot output format is highly verbose for simple tasks

Run `snapshot -v 0 --stdout` on news.ycombinator.com and observe 300+ lines of YAML, most of which is LayoutTable/LayoutTableCell nesting.

#auto-approve
