# Issues: Use Case 20: 百度百科公司信息对比（单站点）

> **Source:** `20260710-143429-Use Case 20_ 百度百科公司信息对比（单站点）.full.md` | **Date:** 20260710-143429 | **Mode:** dev

## Scenario Background

### Task

✅ **Task completed.** `company-compare.md` was produced with a structured comparison table covering all required fields (company name, founding time, HQ location, company description) plus additional metadata, a key differences summary, and screenshots of both Baike pages.

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `--help` | Learn available commands |
| 2 | `goto "https://baike.baidu.com/"` | Navigate to Baidu Baike |
| 3 | `snapshot -v 0` | Capture page structure |
| 4 | `snapshot grep -i "搜索"` | Locate search button |
| 5 | `snapshot grep -i "textbox"` | Locate search textbox (`e45`) |
| 6 | `fill e45 "华为"` | Fill search query |
| 7 | `press Enter e45` | Submit search → navigated to Huawei Baike page |
| 8 | `htmlsnapshot` | Capture HTML snapshot |
| 9 | `screenshot --filename huawei-baike.png` | Screenshot of Huawei page |
| 10 | `htmlsnapshot get all text ".basicInfo-item" --all` | ❌ No elements matched |
| 11 | `htmlsnapshot summary` | ❌ HTTP timeout (60s) |
| 12 | `eval` with complex JS | ❌ "Ping timeout" |
| 13 | `status` | Check backend → UP |
| 14 | `goto` (re-navigate) | ❌ "Ping timeout" |
| 15 | `close` | Close hung session |
| 16 | `goto` (re-navigate to Huawei page) | Fresh session → worked |
| 17 | `eval --file` — extract dt/dd pairs | ✅ Got all structured fields |
| 18 | `eval --file` — extract paragraph text | ✅ Got company description |
| 19 | `goto "https://baike.baidu.com/"` | Return to homepage |
| 20 | `snapshot grep -i "textbox"` | Find search box (`e37586`) |
| 21 | `fill e37586 "小米"` | Fill search query |
| 22 | `press Enter e37586` | Submit → navigated to Xiaomi page |
| 23 | `screenshot --filename xiaomi-baike.png` | Screenshot of Xiaomi page |
| 24 | `eval --file` — extract dt/dd pairs | ✅ Got all structured fields |
| 25 | `eval --file` — extract paragraph text | ✅ Got company description |
| 26 | Write `company-compare.md` | Compile comparison report |

**Workarounds Applied During Task:**

1. **Session restart to recover from backend timeout** — all operations on the first session timed out after `htmlsnapshot summary` hung. `close` then fresh `goto` resolved it.
2. **`eval --file` for all content extraction** — `htmlsnapshot get` couldn't match any selectors; had to write JS to temp files for extraction.
3. **Fallback to generic DOM traversal** — Baidu Baike's React-based class names didn't match documented patterns.

---

## Issues Found (8 issues)
> **Review complete:** 0 approved, 8 deferred/rejected

### Issue 1: Template variables `$cliInvocation`, `$helpCmd`, `$skillPath` are undefined (PERSISTING)

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Add a `.env.example` or setup script that defines these variables

---

### Issue 2: Backend times out after `htmlsnapshot summary` on large pages, requiring session restart

**Severity:** High
**Category:** Reliability

#### Review Result

**Decision:** DEFER

**Summary:** - Add a hard timeout on backend summary generation that kills the operation and releases resources

---

### Issue 3: `eval` and `htmlsnapshot get` fail with generic "Ping timeout" — no diagnostic info

**Severity:** Medium
**Category:** Reliability / UX

#### Review Result

**Decision:** DEFER

**Summary:** - Classify timeout errors by phase: "page not ready", "JS execution timeout", "backend unresponsive", "session hung"

---

### Issue 4: `htmlsnapshot get` on React-based sites returns empty matches — no guidance to fall back

**Severity:** Medium
**Category:** UX / Discoverability

#### Review Result

**Decision:** DEFER

**Summary:** - Add to the "No elements matched" error: "For dynamic/JS-rendered pages, try `eval` with JavaScript to query the live DOM"

---

### Issue 5: `htmlsnapshot summary` is not resilient to large pages (3.3MB HTML)

**Severity:** Medium
**Category:** Reliability

#### Review Result

**Decision:** DEFER

**Summary:** - Add a node budget (e.g., cap at 50K DOM nodes) with graceful degradation

---

### Issue 6: `eval` on Windows requires `--file` workaround for any non-trivial JS

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Detect common quoting errors (unbalanced quotes, escaped characters) and warn before sending to backend

---

### Issue 7: `cargo run` compilation output adds noise to every invocation

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document the two-step build pattern prominently: `cargo build` once, then invoke the binary directly for all subsequent commands

---

### Issue 8: Session close loses all page state — no `snapshot save` / `snapshot restore`

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** REJECT

**Summary:** - Add `session-save <name>` and `session-restore <name>` for full session checkpointing

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Template variables `$cliInvocation`, `$helpCmd`, `$skillPath` are undefined (PERSISTING)

Read the evaluation task template. It references `$RepoRootPath`, `$helpCmd`, `$skillPath`, and `$cliInvocation` as if they are defined environment variables or documented constants.

#### Issue 2: Backend times out after `htmlsnapshot summary` on large pages, requiring session restart

```bash
goto "https://baike.baidu.com/item/华为技术有限公司/6455903"
htmlsnapshot                          # Works (returns metadata)
htmlsnapshot summary                  # Times out after 60s
```
After the summary timeout, ALL subsequent commands (eval, htmlsnapshot get, goto) fail with "Ping timeout" or "HTTP request timed out".

#### Issue 3: `eval` and `htmlsnapshot get` fail with generic "Ping timeout" — no diagnostic info

```bash
eval "document.title" --json
# → ERROR: browser_evaluate failed: Ping timeout
htmlsnapshot get all text ".basicInfo-item.name" --all
# → HTTP request timed out [timeout=30s]
```

#### Issue 4: `htmlsnapshot get` on React-based sites returns empty matches — no guidance to fall back

```bash
htmlsnapshot get all text ".basicInfo-item" --all
# → [] No elements matched ".basicInfo-item".
# → Try `htmlsnapshot inspect ".basicInfo-item"` to discover valid selectors
```

#### Issue 5: `htmlsnapshot summary` is not resilient to large pages (3.3MB HTML)

```bash
goto "https://baike.baidu.com/item/华为技术有限公司/6455903"
htmlsnapshot               # Reports 3348 KB
htmlsnapshot summary        # Times out after 60s
```

#### Issue 6: `eval` on Windows requires `--file` workaround for any non-trivial JS

```bash
eval "JSON.stringify({title: document.title})" --json
# → ERROR: browser_evaluate failed: Ping timeout
# (Actually caused by shell quoting issues that break the JS before sending)
```

#### Issue 7: `cargo run` compilation output adds noise to every invocation

Every `cargo run --manifest-path ... -- <cmd>` prints:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.58s
     Running `cli\browser4-cli\target\debug\browser4-cli.exe ...`
```

#### Issue 8: Session close loses all page state — no `snapshot save` / `snapshot restore`

When the session hung (Issue 2), the only recovery was `close` → `goto`. This required re-searching for the Huawei page, re-navigating to the Baike entry, and re-extracting all data. The first extraction's data was only available because it had been captured to a temp file manually.

#auto-approve
