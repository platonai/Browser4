# Issues: Use Case 21: 知乎话题热问题摘要（单站点）

> **Source:** `20260710-144022-Use Case 21_ 知乎话题热问题摘要（单站点）.full.md` | **Date:** 20260710-144022 | **Mode:** dev

## Scenario Background

### Task

✅ **Task completed successfully.** The `zhihu-hot-questions.md` file was produced with 10 hot questions extracted from Zhihu's "热点问题 · 小时榜" page, including titles and URLs in a formatted markdown table.

**Content of `zhihu-hot-questions.md`:**

```markdown
# 知乎热榜问题 Top 10

> 数据来源：知乎 [热点问题 · 小时榜](https://www.zhihu.com/knowledge-plan/hot-question/hot/0/hour)
> 抓取时间：2026-07-10

| 序号 | 问题 | 链接 |
|------|------|------|
| 1 | 为什么说闲鱼是国内暗网？ | [链接](https://www.zhihu.com/question/2042557235679720474) |
| 2 | 长征十号乙运载火箭成功实现一子级可控回收，这一技术有多难？... | [链接](https://www.zhihu.com/question/2058516301257994660) |
| 3 | 2026年7月9日，福建晋江辉腾鞋厂楼顶上多人绝望等待死亡... | [链接](https://www.zhihu.com/question/2058900143710246282) |
| 4 | 巴威减弱由强台风级改为台风级，可能不会擦过台湾岛... | [链接](https://www.zhihu.com/question/2058543803955241209) |
| 5 | 长十乙的「网系回收」和星舰的「筷子夹火箭」... | [链接](https://www.zhihu.com/question/2058873625373930494) |
| 6 | 福建晋江一鞋厂发生火灾已致 28 人死亡... | [链接](https://www.zhihu.com/question/2058603024998560374) |
| 7 | 如何看待媒体新闻标题《"俄罗斯失败论"抬头，安的什么心？》？ | [链接](https://www.zhihu.com/question/2058843503598294668) |
| 8 | 如何看待潇湘晨报发文「上海青浦孩子被两只狗撕咬」... | [链接](https://www.zhihu.com/question/2058519764566380553) |
| 9 | 为什么金属的密度比水大多了，但却感觉水最重？ | [链接](https://www.zhihu.com/question/615476997) |
| 10 | 长沙公职人员彭某占车位事件影响那么恶劣... | [链接](https://www.zhihu.com/question/2058783264488289171) |
```

### Execution Context

**Key Commands:**

| # | Command | Purpose | Success |
|---|---------|---------|---------|
| 1 | `cargo --version && java -version` | Verify prerequisites | ✅ |
| 2 | `--help` | Learn available commands | ✅ |
| 3 | `goto "https://www.zhihu.com/topic/19552134/hot"` | Navigate to task URL | ❌ 404 |
| 4 | `snapshot -v 0` | Inspect the 404 page | ✅ (confirmed 404) |
| 5 | `goto "https://www.zhihu.com"` | Try Zhihu homepage | 🔀 Redirected to signin |
| 6 | `snapshot -v 0 --stdout` | Inspect signin page | ✅ (no dismissable dialog) |
| 7 | `goto "https://www.zhihu.com/explore"` | Try explore page (workaround) | ✅ Loaded |
| 8 | `snapshot -v 0 --stdout` | Inspect explore page | ✅ Found hot questions section |
| 9 | `goto "https://www.zhihu.com/knowledge-plan/hot-question/hot/0/hour"` | Navigate to full hot questions | ✅ |
| 10 | `snapshot -v 0 --stdout` | Capture hot questions page | ✅ |
| 11 | `eval --json "Array.from(document.querySelectorAll(...))"` | Try JS extraction | ❌ Empty result |
| 12 | `htmlsnapshot` | Capture HTML snapshot for CSS extraction | ✅ |
| 13 | `htmlsnapshot get all text "a.css-2ietpx"` | Extract all question titles | ✅ 16 titles |
| 14 | `htmlsnapshot get all attr "a.css-2ietpx" href` | Extract all question URLs | ✅ 16 URLs |
| 15 | `snapshot -v 1 --stdout` | Verify viewport pagination bug | ❌ Still viewport 0 |

**Workarounds Applied During Task:**

1. **URL discovery** — Original URL returned 404; found alternative via navigation menu links
2. **Login wall bypass** — Zhihu's redirect-to-signin can't be "dismissed"; used unauthenticated pages instead
3. **CSS class discovery** — Used `htmlsnapshot` metadata table (Interactive Elements) to find `a.css-2ietpx` as the question link selector

---

## Issues Found (7 issues)
> **Review complete:** 4 approved, 3 deferred/rejected

### Issue 1: Snapshot viewport pagination (`-v N`) stays at viewport 0

**Severity:** High
**Category:** Reliability

#### Overview

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
goto "https://www.zhihu.com/knowledge-plan/hot-question/hot/0/hour"
snapshot -v 0 --stdout | head -5    # shows processingViewport: 0, viewportsTotal: 2
snapshot -v 1 --stdout | head -5    # STILL shows processingViewport: 0
```

#### Expected Behavior

`snapshot -v 1` should scroll the page to viewport 1 (986px down) and capture the next chunk of the page, showing `processingViewport: 1` and different content.

#### Actual Behavior

`snapshot -v 1` shows the exact same `processingViewport: 0` and identical content to `snapshot -v 0`. The page is never scrolled.

#### Root Cause Analysis

The viewport pagination flag is either not being passed to the backend correctly, or the backend is not executing the scroll action before capturing the accessibility tree. The output header `processingViewport` always reports `0` regardless of the `-v` argument.

#### Code Pointer

`CLI snapshot command argument handling in `cli/browser4-cli/src/`; backend snapshot viewport scrolling logic.`

#### AI Suggested Improvement

- Fix the `-v` flag to actually trigger page scrolling before snapshot capture
- Add a visual indicator in `--stdout` output showing which viewport chunk is being displayed
- Add a test that verifies `snapshot -v 0` and `snapshot -v 1` produce different content on a page that exceeds one viewport

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 2: `eval` with `document.querySelectorAll` returns empty while `htmlsnapshot` CSS selector works

**Severity:** Medium
**Category:** Reliability

#### Overview

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```bash
goto "https://www.zhihu.com/knowledge-plan/hot-question/hot/0/hour"
eval --json "Array.from(document.querySelectorAll('a[href*=\"question\"]')).slice(0,10).map(a => a.textContent)"
# → []
htmlsnapshot
htmlsnapshot get all text "a.css-2ietpx"
# → ["为什么说闲鱼是国内暗网？", "长征十号乙运载火箭...", ...]  (16 results)
```

#### Expected Behavior

`document.querySelectorAll` in the page context should find the same question link elements that `htmlsnapshot`'s CSS selector engine finds.

#### Actual Behavior

The JS `eval` returned an empty array `[]` even though the elements existed in the DOM. Several different selector variations were tried (`[href^="/question/"]`, `[href*="question"]`) — all returned empty.

#### Root Cause Analysis

Uncertain. Possible causes: (a) the `eval` command may not have proper access to the full DOM after a page load, (b) the elements may be loaded asynchronously and not yet in the DOM when `eval` runs, (c) there may be a scope or frame issue. The `htmlsnapshot` tool successfully finds them because it operates on the stored HTML snapshot. Further investigation needed.

#### Code Pointer

`Backend `eval` command implementation; DOM access scope handling.`

#### AI Suggested Improvement

- Investigate why `querySelectorAll` doesn't find elements that exist in the stored HTML snapshot
- Document any known limitations of `eval` vs `htmlsnapshot` for element discovery
- Consider adding a `--wait-selector` flag to `eval` that waits for an element to appear before executing JS
- Add a debug mode that shows the current DOM state accessible to `eval`

#### Human Review

- [ ] **ACCEPT**
- [x] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 4: `htmlsnapshot` metadata table (`a.css-2ietpx`) uses auto-generated CSS module class names

**Severity:** Low
**Category:** UX / Documentation

#### Overview

**Severity:** Low
**Category:** UX / Documentation

#### Reproduction

```bash
htmlsnapshot
# Interactive Elements table shows: "a.css-2ietpx" — this is a CSS module hash
```

#### Expected Behavior

Documentation should warn that auto-generated class names (from CSS Modules, styled-components, etc.) are ephemeral and may change on page reload or site update.

#### Actual Behavior

The class name `css-2ietpx` worked perfectly for this extraction, but there's no warning that this selector may break tomorrow if Zhihu redeploys. The `htmlsnapshot inspect` command is mentioned in docs as a selector discovery tool but wasn't obviously the next step.

#### Root Cause Analysis

CSS Modules generate unique hashed class names at build time. The `htmlsnapshot` metadata table faithfully reports these class names, which is correct behavior, but users may not realize they're fragile.

#### Code Pointer

`Documentation in `skills/browser4-cli/SKILL.md` and `skills/browser4-cli/references/htmlsnapshot.md`.`

#### AI Suggested Improvement

- Add a note in the `htmlsnapshot` output warning that CSS-module class names (those matching `css-[a-z0-9]+`) are auto-generated and may change
- Recommend `htmlsnapshot inspect` or `htmlsnapshot summary` for discovering more resilient selectors (e.g., structural selectors, aria labels)
- Add a resilience score or indicator to the Interactive Elements table (e.g., 🟢 semantic, 🟡 class-based, 🔴 auto-generated)

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 5: Documentation about `snapshot -v` viewport pagination is unclear

**Severity:** Medium
**Category:** Documentation

#### Overview

**Severity:** Medium
**Category:** Documentation

#### Reproduction

```bash
snapshot --help
# Output mentions "-v N" and "viewport pagination" but doesn't explain what viewports are
# or how they relate to scrolling
```

#### Expected Behavior

The help text should explain that `-v N` scrolls the page to chunk N (each chunk = viewport height ≈ 986px), allowing users to read long pages in segments like a human would scroll.

#### Actual Behavior

The help says "Use -v N for viewport pagination" and the snapshot output says "This page has N viewports (page chunks split by viewport height)" but the concept of "viewport" as a scroll chunk is never defined. Users must infer the meaning from context.

#### Root Cause Analysis

The term "viewport" is used as a technical term without a user-facing definition. In browser automation, "viewport" typically means the visible area, not a scroll chunk.

#### Code Pointer

`CLI help text in `cli/browser4-cli/src/`; snapshot output formatting.`

#### AI Suggested Improvement

- Add a one-line definition in `--help`: "Use -v N to scroll to and capture page chunk N (each chunk = one screen height)"
- Rename "viewport" to "scroll page" or "scroll chunk" in user-facing output for clarity
- Add an example: `snapshot -v 1-3` → "capture the 2nd through 4th screen-heights of the page"
- Link from `--help` to a dedicated section in documentation

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 3: `goto` silently reconnects to pre-existing session from prior runs

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Make the "Reconnected" message more prominent (e.g., colored warning on stderr)

---

### Issue 6: `htmlsnapshot get all` data includes UI label prefixes in extracted text

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Consider adding a `--text-content` vs `--inner-text` flag to control text extraction behavior

---

### Issue 7: Template variables (`$RepoRootPath`, `$cliInvocation`, `$helpCmd`, `$skillPath`) still undefined

**Severity:** Medium
**Category:** Documentation

#### Review Result

**Decision:** DUPLICATE

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Snapshot viewport pagination (`-v N`) stays at viewport 0

```bash
goto "https://www.zhihu.com/knowledge-plan/hot-question/hot/0/hour"
snapshot -v 0 --stdout | head -5    # shows processingViewport: 0, viewportsTotal: 2
snapshot -v 1 --stdout | head -5    # STILL shows processingViewport: 0
```

#### Issue 2: `eval` with `document.querySelectorAll` returns empty while `htmlsnapshot` CSS selector works

```bash
goto "https://www.zhihu.com/knowledge-plan/hot-question/hot/0/hour"
eval --json "Array.from(document.querySelectorAll('a[href*=\"question\"]')).slice(0,10).map(a => a.textContent)"
# → []
htmlsnapshot
htmlsnapshot get all text "a.css-2ietpx"
# → ["为什么说闲鱼是国内暗网？", "长征十号乙运载火箭...", ...]  (16 results)
```

#### Issue 3: `goto` silently reconnects to pre-existing session from prior runs

```bash
# A session was left open from a previous evaluation
goto "https://www.zhihu.com/topic/19552134/hot"
# Output: "Reconnected to existing session on https://baike.baidu.com/item/..."
```

#### Issue 4: `htmlsnapshot` metadata table (`a.css-2ietpx`) uses auto-generated CSS module class names

```bash
htmlsnapshot
# Interactive Elements table shows: "a.css-2ietpx" — this is a CSS module hash
```

#### Issue 5: Documentation about `snapshot -v` viewport pagination is unclear

```bash
snapshot --help
# Output mentions "-v N" and "viewport pagination" but doesn't explain what viewports are
# or how they relate to scrolling
```

#### Issue 6: `htmlsnapshot get all` data includes UI label prefixes in extracted text

```bash
htmlsnapshot get all text "a.css-2ietpx"
# Returns: ["新题 2026年7月9日，福建晋江...", "新题 长十乙的...", ...]
# The "新题" prefix is a UI label embedded in the link text, not part of the question title
```

#### Issue 7: Template variables (`$RepoRootPath`, `$cliInvocation`, `$helpCmd`, `$skillPath`) still undefined

(No reproduction steps recorded — see full.md for surrounding context)

#auto-approve
