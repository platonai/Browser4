# Issues: Use Case 11: Hiring Market Cross-Platform Comparison

> **Source:** `20260710-134412-Use Case 11_ Hiring Market Cross-Platform Comparison.full.md` | **Date:** 20260710-134412 | **Mode:** dev

## Scenario Background

### Task

The comparison report has been written to `qa-automation-hiring-comparison.md`. Key findings:

- **Wellfound** has dramatically more QA automation engineer listings (~83 US) than **RemoteOK** (~2)
- **RemoteOK** hides most salary data behind a premium paywall; **Wellfound** is more transparent and includes equity
- Core skill requirements (Selenium, Playwright, Cypress, Python/JS/Java) are consistent across both platforms
- Wellfound listings show broader skill expectations (cloud, observability, contract testing, AI-assisted QA)
- Wellfound was **inaccessible via browser4-cli** due to DataDome CAPTCHA — data was gathered via web search as a workaround

### Execution Context

**Key Commands:**

| # | Command | Purpose | Result |
|---|---------|---------|--------|
| 1 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- help` | Learn available commands | Success — comprehensive help output |
| 2 | `goto "https://remoteok.com/jobs"` | Navigate to RemoteOK | Success — page loaded (reused existing session) |
| 3 | `snapshot -v 0` | Capture accessibility tree | Success — 139 nodes, viewport 0 |
| 4 | `snapshot grep -i "search"` | Find search textbox | Success — located ref=e57 |
| 5 | `fill e57 "QA automation engineer"` | Fill search box | Success |
| 6 | `press Enter` | Submit search | **Failed** — RemoteOK uses JS filtering, form submit didn't trigger |
| 7 | `goto "https://remoteok.com/remote-jobs?search=qa+automation+engineer"` | Direct URL search (workaround) | Success |
| 8 | `snapshot grep -i "qa"` | Verify search results | Success — found 2 QA Engineer listings |
| 9 | `htmlsnapshot` + `htmlsnapshot get all text "h2"/"h3"` | Extract job titles and tags | Success |
| 10 | `goto` job detail page + `htmlsnapshot get text` | Extract full job description | Success — full description with skills |
| 11 | `goto "https://wellfound.com/jobs"` | Navigate to Wellfound | **Failed** — DataDome CAPTCHA |
| 12 | Multiple retries: `reload`, fresh sessions (`-s`), homepage → jobs | Attempt to bypass CAPTCHA | All failed |
| 13 | `screenshot` | Verify CAPTCHA visually | Confirmed DataDome block |
| 14 | Web search (fallback) | Gather Wellfound data | Success — 83 US listings found |
| 15 | Write `qa-automation-hiring-comparison.md` | Compile report | Success |

**Key workarounds:**
- Direct URL navigation for RemoteOK search (form submit via `press Enter` didn't work with JS-based search)
- Web search to supplement Wellfound data (CAPTCHA blocked all automated access)

---

## Issues Found (8 issues)
> **Review complete:** 1 approved, 7 deferred/rejected

### Issue 5: `snapshot grep` alternation syntax differs from standard grep

**Severity:** Low
**Category:** UX / Discoverability

#### Overview

**Severity:** Low
**Category:** UX / Discoverability

#### Reproduction

```
browser4-cli snapshot grep -i "qa\|salary"
```

#### Expected Behavior

Pipe-separated alternation should work as in standard grep.

#### Actual Behavior

The CLI prints a note: "Converted grep-style alternation `\\|` to `|` in pattern. Rust regex uses bare `|` for alternation (like ERE/egrep)." While the command still succeeds, the message is confusing and the behavior differs from both GNU grep (which uses `\|` by default) and egrep (which uses `|`).

#### Root Cause Analysis

The CLI uses Rust's regex engine which uses bare `|` for alternation. The CLI auto-converts `\|` to `|` as a compatibility shim, but the help text doesn't document this difference.

#### Code Pointer

``cli/browser4-cli/src/` — the `snapshot grep` command's regex processing`

#### AI Suggested Improvement

- Document the regex flavor in `snapshot grep --help` output: "Uses Rust regex syntax (bare `|` for alternation, not `\|`)"
- Add a `-E` flag alias for extended regex (documentation consistency)
- Suppress the conversion notice in non-verbose mode — it adds noise for a successful conversion

#### Human Review

- [ ] **ACCEPT**
- [x] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 1: DataDome CAPTCHA blocks Wellfound entirely

**Severity:** Critical
**Category:** Reliability

#### Review Result

**Decision:** DEFER

**Summary:** - Add anti-detection/stealth flags to the Chromium launch configuration (e.g., `--disable-blink-features=AutomationControlled`, hide `navigator.webdriver`)

---

### Issue 2: `fill` + `press Enter` fails on JavaScript-driven search forms

**Severity:** High
**Category:** Product

#### Review Result

**Decision:** DEFER

**Summary:** - After `fill`, automatically fire `input` and `change` DOM events (not just set the `value` property) to trigger JS frameworks (React, Vue, Angular)

---

### Issue 3: Pre-existing session reused silently on `goto`

**Severity:** Medium
**Category:** UX / Discoverability

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Print a more prominent warning when reconnecting to a session from a different domain than the requested URL

---

### Issue 4: `snapshot` output is truncated by default, requiring extra commands

**Severity:** Medium
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Increase the default preview from 10 lines to 30-50 lines

---

### Issue 6: No CAPTCHA / bot-detection handling in documentation

**Severity:** High
**Category:** Documentation

#### Review Result

**Decision:** DEFER

**Summary:** - Add a "Known Limitations" or "Troubleshooting" section to SKILL.md listing: sites protected by DataDome, Cloudflare Bot Management, Akamai Bot Manager, etc.

---

### Issue 7: Shell working directory resets after every Bash command on Windows

**Severity:** Low
**Category:** Reliability / UX

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Document the `--manifest-path` pattern more prominently in development.md as the recommended way to invoke from any directory

---

### Issue 8: `cargo run` first-time compilation delay

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document `cargo run --quiet --` as the recommended default in development.md examples

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: DataDome CAPTCHA blocks Wellfound entirely

```
browser4-cli goto "https://wellfound.com/jobs"
browser4-cli goto "https://wellfound.com/jobs?search=qa+automation"
browser4-cli goto "https://wellfound.com/role/l/qa-automation-engineer/united-states"
```

#### Issue 2: `fill` + `press Enter` fails on JavaScript-driven search forms

```
browser4-cli goto "https://remoteok.com/jobs"
browser4-cli fill <search-ref> "QA automation engineer"
browser4-cli press Enter
# Observe: page navigates to an unexpected URL, search not executed
```

#### Issue 3: Pre-existing session reused silently on `goto`

```
browser4-cli goto "https://remoteok.com/jobs"
# Output: "Reconnected to existing session on https://github.com/h4ckf0r0day/obscura/commits/main"
```

#### Issue 4: `snapshot` output is truncated by default, requiring extra commands

```
browser4-cli snapshot -v 0
# Output: "... (use --stdout or open the file for full content) ---"
# Shows only first 10 lines
```

#### Issue 5: `snapshot grep` alternation syntax differs from standard grep

```
browser4-cli snapshot grep -i "qa\|salary"
```

#### Issue 6: No CAPTCHA / bot-detection handling in documentation

Search the SKILL.md, development.md, load-options-guide.md, or any reference file for "captcha", "bot detection", "DataDome", "Cloudflare", "anti-bot", or "blocked".

#### Issue 7: Shell working directory resets after every Bash command on Windows

Run any sequence of browser4-cli commands via Bash tool. After each command, observe: `Shell cwd was reset to C:\Users\pereg`.

#### Issue 8: `cargo run` first-time compilation delay

Run any `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>` for the first time.

#auto-approve
