# Issues: javascript-evaluation

> **Source:** `20260708-184937-javascript-evaluation.full.md` | **Date:** 20260708-184937 | **Mode:** dev

## Scenario Background

### Task

All 8 steps completed successfully:

| Step | Method | Result |
|------|--------|--------|
| 1 | `goto` | Navigated to `interactive-1.html` (title: "Interactive Single Page") |
| 2 | `snapshot -i` | Discovered element refs: `e30` (h1), `e36` (textbox), `e44` (combobox), `e67`/`e73` (spinbuttons), `e78` (button), `e83` (toggle) |
| 3 | `eval "document.title"` | `Interactive Single Page` ✅ |
| 4 | `eval --json` | `{"linkCount":0,"title":"Interactive Single Page","url":"http://localhost:18080/generated/interactive-1.html"}` ✅ |
| 5 | `eval --file` | `{"images":0,"links":0,"forms":0}` ✅ |
| 6 | `eval --stdin` | `["Welcome to the Interactive Page","📋 User Information","📊 Preferences","🧮 Quick Calculator","🎯 Dynamic Toggle"]` ✅ |
| 7 | `eval --ref e30` | `Welcome to the Interactive Page` ✅ |
| 8 | Cross-verification | Title matches across steps 3, 4, 7. Heading "📋 User Information" matches steps 6, --ref e33. Link count 0 consistent across steps 4, 5. |

### Execution Context

**Key Commands:**

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/generated/interactive-1.html"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -i
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -i --stdout
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "document.title"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval --json "(function() { ... })()"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval --file /tmp/page_info.js
echo '...' | cargo run ... -- eval --stdin
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "element => element.textContent" --ref e30
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "element => element.placeholder" --ref e36
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "element => element.tagName" --ref e78
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "element => element.getAttribute('aria-label')" e83
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval --file /tmp/elem_fn.js --ref e33
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "element => element.textContent" --ref "h1"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- console
```

**Key decisions:**
- Used `--stdout` on second snapshot call after discovering the first only showed a 10-line preview
- Wrapped the JSON eval in an IIFE `(function() { ... })()` to return a structured object
- Tested both `--ref` flag and positional ref syntax
- Verified CSS selector support works with `--ref` (not just snapshot refs)

**Workarounds:**
- Shell quoting: used single-quote wrapping with `'\''` escaping for nested single quotes within bash single-quoted strings; the `--file` and `--stdin` options avoided this entirely for multi-line scripts

---

## Issues Found (6 issues)
> **Review complete:** 0 approved, 6 deferred/rejected

### Issue 1: `--stdout` flag not obvious for first-time snapshot users

**Severity:** Medium
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** ---

---

### Issue 2: First `goto` showed "Reconnected to existing session" for a stale session

**Severity:** Low
**Category:** UX / Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** ---

---

### Issue 3: `console.log` output from `eval --file` is silently discarded

**Severity:** Medium
**Category:** Documentation / UX

#### Review Result

**Decision:** WONTFIX

**Summary:** ---

---

### Issue 4: Shell quoting of inline JS is error-prone

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** ---

---

### Issue 5: `snapshot -i` mode strips generic containers — documented but easy to miss

**Severity:** Low
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** ---

---

### Issue 6: `--ref` supports CSS selectors but help output emphasizes snapshot refs

**Severity:** Low
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** ---

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `--stdout` flag not obvious for first-time snapshot users

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -i
```
Without `--stdout`, only the first 10 lines of the snapshot are shown, followed by `... (use --stdout or open the file for full content)`.

#### Issue 2: First `goto` showed "Reconnected to existing session" for a stale session

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://localhost:18080/generated/interactive-1.html"
```
Output:
```
Reconnected to existing session on https://books.toscrape.com/
### Page
- Page URL: http://localhost:18080/generated/interactive-1.html
- Page Title: Interactive Single Page
```

#### Issue 3: `console.log` output from `eval --file` is silently discarded

1. Create `/tmp/page_info.js` containing JS that calls `console.log(...)` and returns a value.
2. Run `eval --file /tmp/page_info.js`
3. Run `console` to view console messages.
4. `console` returns `[]`.

#### Issue 4: Shell quoting of inline JS is error-prone

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval --json "(function() { return { url: document.URL, title: document.title, linkCount: document.querySelectorAll('a').length }; })()"
```
Requires `'\''` escaping for nested single quotes within bash single-quoted strings.

#### Issue 5: `snapshot -i` mode strips generic containers — documented but easy to miss

Run `snapshot -i` on an e-commerce page where product cards are `<div>` elements.

#### Issue 6: `--ref` supports CSS selectors but help output emphasizes snapshot refs

Read `eval --help` and note the examples:
```
browser4-cli eval "element => element.textContent" "#click-target"
browser4-cli eval "element => element.textContent" e5
```
