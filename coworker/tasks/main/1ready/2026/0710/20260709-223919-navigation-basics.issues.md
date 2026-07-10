# Issues: navigation-basics

> **Source:** `20260709-223919-navigation-basics.full.md` | **Date:** 20260709-223919 | **Mode:** dev

## Scenario Background

### Task

✅ **All 8 task steps completed successfully:**

1. ✅ Navigated to `https://en.wikipedia.org/wiki/Web_scraping`
2. ✅ Took a snapshot showing accessible elements (42 KB, 576 nodes)
3. ✅ Identified and clicked the "Data scraping" link in the "See also" section
4. ✅ Used `go-back` to return to Web scraping, then `go-forward` to Data scraping
5. ✅ Reloaded the Data scraping page with `reload`
6. ✅ Checked server status: version `v4.11.15`, server `UP` on port 8182
7. ✅ Listed sessions: 1 active default session, then confirmed empty after close
8. ✅ Closed the session cleanly

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| 1 | `cargo run -- help` | Retrieved full command listing |
| 2 | Read `skills/browser4-cli/SKILL.md` | Learned command patterns, lifecycle, warning |
| 3 | `cargo run -- goto "https://en.wikipedia.org/wiki/Web_scraping"` | Navigated to the article (reused existing session) |
| 4 | `cargo run -- snapshot -v 0` | 576 nodes, 42 KB snapshot captured |
| 5 | `cargo run -- snapshot grep -C 30 "See also"` | Found TOC entry for "See also" section |
| 6 | `cargo run -- scroll down 8000` | Scrolled to bottom of page |
| 7 | `cargo run -- snapshot --stdout --all \| grep "heading.*See also"` | Found all "See also" links with refs (viewport limitations forced alternative approach) |
| 8 | `cargo run -- scroll up 1000` | Adjusted scroll position ...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)
> **Review complete:** 0 approved, 6 deferred/rejected

### Issue 1: Viewport snapshots return near-empty trees for scrolled-to positions

**Severity:** High
**Category:** Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - After a `scroll` command completes, automatically request a full accessibility tree snapshot that includes newly visible elements

---

### Issue 2: Build output noise on every command invocation in dev mode

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document in `skills/browser4-cli/SKILL.md` that dev mode includes build output

---

### Issue 3: `snapshot` viewport command output is confusingly verbose vs. `--stdout`

**Severity:** Medium
**Category:** UX / Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Make `--stdout` the default behavior and add `--save` for file-saving use cases

---

### Issue 4: No CSS selector support for `click` command — requires snapshot refs or JavaScript

**Severity:** Medium
**Category:** Product / UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Extend `click` (and other interaction commands) to accept CSS selectors, resolving to the first matching element

---

### Issue 5: `snapshot grep` output formatting includes `:` prefix on matching lines

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Use standard grep markers: `:` for matching lines, `-` for context lines, instead of mixing with YAML syntax

---

### Issue 6: `list` command shows empty table after `close` without clear confirmation

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add an empty-state message: "No active browser sessions."

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Viewport snapshots return near-empty trees for scrolled-to positions

```
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
browser4-cli scroll down 8000
browser4-cli snapshot -v 0
```

#### Issue 2: Build output noise on every command invocation in dev mode

Any `cargo run -- <command>` invocation in dev mode.

#### Issue 3: `snapshot` viewport command output is confusingly verbose vs. `--stdout`

```
browser4-cli snapshot -v 0
```

#### Issue 4: No CSS selector support for `click` command — requires snapshot refs or JavaScript

```
browser4-cli click "a[href*='Data_scraping']"
```

#### Issue 5: `snapshot grep` output formatting includes `:` prefix on matching lines

```
browser4-cli snapshot grep -C 30 "See also"
```

#### Issue 6: `list` command shows empty table after `close` without clear confirmation

```
browser4-cli close
browser4-cli list
```
