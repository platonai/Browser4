# Issues: Use Case 24: 人民网首页要闻提取（单站点）

> **Source:** `20260710-150318-Use Case 24_ 人民网首页要闻提取（单站点）.full.md` | **Date:** 20260710-150318 | **Mode:** dev

## Scenario Background

### Task

✅ **Task completed successfully.** The `people-headlines.md` file was produced with 15 headlines extracted from the front page of people.com.cn, organized in order of page prominence from the top headline (h1) through main news sections (`#aq_two`, `#rm_topread`).

### Execution Context

**Key Commands:**

| # | Command | Purpose |
|---|---------|---------|
| 1 | `goto "http://people.com.cn/"` | Navigate to site (redirected to https://www.people.com.cn/) |
| 2 | `snapshot -v 0` | Capture accessibility tree with interactive refs |
| 3 | `htmlsnapshot` | Capture static HTML snapshot for extraction |
| 4 | `htmlsnapshot summary` | Get page structure overview and link groups |
| 5 | `htmlsnapshot inspect` | Auto-discover CSS selectors (inspected `li` by default) |
| 6 | `htmlsnapshot get all text "h1"` | Extract h1-level headlines |
| 7 | `htmlsnapshot get all text "h2"` | Extract h2-level headlines |
| 8 | `htmlsnapshot get all text "#aq_two a"` | Extract from major news list section |
| 9 | `htmlsnapshot get all text "#rmw_news a"` | Extract from news section |
| 10 | `htmlsnapshot get all text "#aq_one a"` | Extract from secondary top story |
| 11 | `htmlsnapshot get all text "#rm_topline a"` | Extract from top headline section |
| 12 | `htmlsnapshot get all text ".blist1 a, .blist2 a"` | Extract from general news list |
| 13 | `htmlsnapshot get all text "#rm_topread a"` | Extract from "top read" section |

**Workarounds Applied During Task:**

1. **Session carryover from previous run** — first `goto` reported "Reconnected to existing session on https://www.gov.cn/..." before navigating to people.com.cn. Had to let it complete the navigation to the correct URL.
2. **Manual selector discovery** — `htmlsnapshot inspect` auto-inspected `li` elements by default, which wasn't helpful for headline extraction. Had to use `htmlsnapshot summary` to identify the relevant section IDs (`#aq_two`, `#aq_one`, etc.) and then query them directly.
3. **Multi-selector extraction** — no single selector captured all headlines. Had to run 8 separate `get all text` commands against different selectors, then merge and deduplicate manually.

---

## Issues Found (9 issues)
> **Review complete:** 3 approved, 6 deferred/rejected

### Issue 3: `htmlsnapshot inspect` defaults to inspecting `li` elements, not helpful for headline discovery

**Severity:** Medium
**Category:** UX / Discoverability

#### Overview

**Severity:** Medium
**Category:** UX / Discoverability

#### Reproduction

```bash
htmlsnapshot inspect
# Auto-discovers "li" (302 matches, 20 analyzed)
# Shows nav menu items, not headlines
```

#### Expected Behavior

`htmlsnapshot inspect` without arguments should provide actionable guidance for the user's likely goal, or at minimum suggest what to inspect next. The auto-selected `li` gave navigation menu items ("首页", "党政"), which are not the page's main content.

#### Actual Behavior

The tool auto-discovers `li` elements as the repeating pattern, but on people.com.cn, these are navigation menu items, not headlines. The user must already know to run `htmlsnapshot summary` to discover the relevant sections, then target specific selectors.

#### Root Cause Analysis

`htmlsnapshot inspect` picks the most numerous repeating element (`li`, 302 matches) as the default target. On many Chinese news sites, headlines are in `<a>` tags inside `<ul>` lists with specific IDs, not generic `<li>` elements. The default heuristic favors quantity over content relevance.

#### Code Pointer

`Backend HTML snapshot inspect logic — element selection heuristic.`

#### AI Suggested Improvement

- Show a summary of *all* detected repeating patterns (like `summary` does for link groups) and let the user choose which to inspect
- Prioritize elements with text content over structural elements (headlines have more text than nav items)
- Suggest `htmlsnapshot summary` as a natural first step before `inspect` in the tips
- Add a `--group <name>` flag to target specific link groups from the summary output

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 6: `htmlsnapshot summary` output is verbose and includes internal scoring, not just actionable structure

**Severity:** Low
**Category:** UX

#### Overview

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
htmlsnapshot summary
```

#### Expected Behavior

A clean, structured overview of the page's content zones with CSS selectors ready for use in extraction commands.

#### Actual Behavior

The output includes internal scoring values (`score:115`, `score:105`, `score:60`), a "Score scale" legend, and technical detail ("p~len/4 +id(10) +cls(5)") that is irrelevant for end users. The useful information (section IDs, content types, item counts) is mixed with algorithmic scoring data.

#### Root Cause Analysis

The `summary` command exposes internal ranking/scoring implementation details alongside user-facing structural information. These serve the algorithm but not the user.

#### Code Pointer

``htmlsnapshot summary` output formatting.`

#### AI Suggested Improvement

- Hide scoring details behind a `--verbose` flag; show clean structure by default
- Format link groups as "copy-paste ready" CSS selectors: `htmlsnapshot get all text "#aq_two a"`
- Show a "Suggested commands" section: "To extract headlines, try: ..." based on detected content zones

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 9: `htmlsnapshot summary` shows "Type: Login / Auth" which is incorrect for people.com.cn

**Severity:** Low
**Category:** Reliability

#### Overview

**Severity:** Low
**Category:** Reliability

#### Reproduction

```bash
htmlsnapshot summary
# Shows: "Type: Login / Auth"
```

#### Expected Behavior

A news portal homepage should be classified as something like "News / Content" or "Portal", not "Login / Auth".

#### Actual Behavior

The page type detection classified people.com.cn (a major news portal) as "Login / Auth". This is a false positive — the page has a login button in the nav bar but is primarily a content/news page.

#### Root Cause Analysis

The page type classifier likely detects the presence of a login button/link and classifies the page as auth-related, even when the login element is a minor navigation item rather than the page's primary purpose.

#### Code Pointer

`Backend page type classification logic.`

#### AI Suggested Improvement

- Weight page type classification by the prominence of the detected elements (a small "登录" link in the header should not override the presence of dozens of news headlines)
- Consider multiple signals: content-to-chrome ratio, presence of article/headline patterns, number of links vs. forms

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 1: `$cliInvocation`, `$helpCmd`, and `$skillPath` are undefined template variables

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Add a "Quick Start — Dev Mode" section at the top of `SKILL.md` that explicitly defines: `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` as the dev invocation

---

### Issue 2: Session carryover from previous evaluation causes confusing "Reconnected" message

**Severity:** Medium
**Category:** UX / Reliability

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Show the *target* URL in the "Reconnected" message: "Reconnected to existing session, navigating to https://www.people.com.cn/"

---

### Issue 4: `htmlsnapshot get all text` returns empty strings for elements without text content

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** REJECT

**Summary:** - Filter empty strings from `get all text` results by default

---

### Issue 5: No single command to extract "main headlines" from a news homepage

**Severity:** Medium
**Category:** UX / Discoverability

#### Review Result

**Decision:** DEFER

**Summary:** - Add a `headlines` or `top-stories` command that leverages the summary data to auto-extract the most prominent text content

---

### Issue 7: Positive finding — Chinese text extraction and encoding works flawlessly

**Severity:** N/A (positive observation)
**Category:** N/A

#### Review Result

**Decision:** WONTFIX

**Summary:** - This is a strength worth highlighting in documentation for Chinese-market users

---

### Issue 8: `cargo run` overhead on every command — same issue as previous evaluation

**Severity:** Low (recurring)
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document: `cargo build --manifest-path cli/browser4-cli/Cargo.toml` once, then use `./cli/browser4-cli/target/debug/browser4-cli.exe` directly

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `$cliInvocation`, `$helpCmd`, and `$skillPath` are undefined template variables

Read the evaluation task template. It references `$RepoRootPath`, `$helpCmd`, `$skillPath`, and `$cliInvocation` as if they are defined environment variables or documented constants.

#### Issue 2: Session carryover from previous evaluation causes confusing "Reconnected" message

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://people.com.cn/"
# Output: "Reconnected to existing session on https://www.gov.cn/zhengce/202606/content_7073702.htm"
# Then navigates to people.com.cn
```

#### Issue 3: `htmlsnapshot inspect` defaults to inspecting `li` elements, not helpful for headline discovery

```bash
htmlsnapshot inspect
# Auto-discovers "li" (302 matches, 20 analyzed)
# Shows nav menu items, not headlines
```

#### Issue 4: `htmlsnapshot get all text` returns empty strings for elements without text content

```bash
htmlsnapshot get all text "h1"
# Returns: ["","","习近平同纳米比亚总统恩代特瓦会谈"]
htmlsnapshot get all text "#aq_two a"
# Returns: ["李强主持召开...", "赵乐际分别会见...", ..., "", ""]
```

#### Issue 5: No single command to extract "main headlines" from a news homepage

A user wants to extract the top headlines from a news site. They must: (1) `htmlsnapshot`, (2) `htmlsnapshot summary` to discover sections, (3) identify relevant selectors from the summary, (4) run multiple `get all text` commands per section, (5) manually merge, deduplicate, and order results.

#### Issue 6: `htmlsnapshot summary` output is verbose and includes internal scoring, not just actionable structure

```bash
htmlsnapshot summary
```

#### Issue 7: Positive finding — Chinese text extraction and encoding works flawlessly

All `get all text` commands against the Chinese-language site returned accurate, properly encoded Chinese text with no mojibake, truncation, or encoding issues.

#### Issue 8: `cargo run` overhead on every command — same issue as previous evaluation

Every command invocation prints:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.28s
     Running `cli\browser4-cli\target\debug\browser4-cli.exe ...`
```

#### Issue 9: `htmlsnapshot summary` shows "Type: Login / Auth" which is incorrect for people.com.cn

```bash
htmlsnapshot summary
# Shows: "Type: Login / Auth"
```

#auto-approve
