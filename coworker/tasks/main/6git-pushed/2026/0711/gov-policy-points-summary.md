# Issues: Use Case 23: 政府网站政策要点摘要（单站点）

> **Source:** `20260710-145929-Use Case 23_ 政府网站政策要点摘要（单站点）.full.md` | **Date:** 20260710-145929 | **Mode:** dev

## Scenario Background

### Task

The output file is **`gov-policy-summary.md`** at `D:/workspace/Browser4/Browser4-4.11/gov-policy-summary.md`.

**Summary of content:**

| Field | Value |
|-------|-------|
| **Title** | 国务院常务会议解读 \| 国务院常务会议部署人工智能发展有关工作 |
| **Source** | 新华社 (Xinhua News Agency) |
| **Date** | 2026-06-29 22:59 |
| **Reporter** | 魏玉坤 |
| **URL** | https://www.gov.cn/zhengce/202606/content_7073702.htm |

The article reports on a June 29, 2026 State Council executive meeting that reviewed AI development and made targeted deployments across five areas: AI trend awareness & governance, key technology breakthroughs & computing infrastructure, "AI+" initiative implementation, AI safety & ethics regulation, and international AI governance cooperation.

### Execution Context

**Key Commands:**

1. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` — Initial help discovery
2. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.gov.cn/"` — Navigate to homepage
3. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0` — Capture accessibility tree
4. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep -i "search|搜索|textbox"` — Find search box elements
5. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- fill e342 "人工智能"` — Attempted fill (FAILED — element not focusable)
6. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- type "人工智能" e340` — Type into alternate search box (WORKED)
7. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- press Enter` — Submit search (triggered form GET, not JS handler)
8. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- tab-list` — Check for new tabs
9. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot --stdout` — Full snapshot to find search button
10. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://sousuo.www.gov.cn/sousuo/search.shtml?...searchWord=..."` — **Workaround**: Direct navigation to search results URL
11. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep -i "人工智能"` — Find articles in results
12. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot` — Capture HTML for extraction
13. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all attr "a.title.log-anchor" href` — Extract article URLs
14. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "a.title.log-anchor"` — Extract article titles
15. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.gov.cn/zhengce/202606/content_7073702.htm"` — Open selected article
16. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot` — Capture article HTML
17. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "h1"` — Extract title
18. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text ".pages-date"` — Extract date+source
19. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "#UCAP-CONTENT"` — Extract article body

**Major decisions:**
- Skipped clicking the search button in favor of direct URL navigation to search results (workaround for search form complexity)
- Used `htmlsnapshot get text` with CSS selectors for extraction rather than `snapshot` accessibility tree refs
- Manually composed the 5-point summary after reading extracted content (no LLM key configured)

**Workarounds required:**
1. Template variables (`$cliInvocation`, `$helpCmd`, etc.) had to be resolved by searching previous evaluation files
2. `fill` on `e342` failed — switched to `type` on `e340`
3. `press Enter` didn't trigger the JS search handler — navigated directly to the known search results URL instead
4. CSS selectors (`.pages-date`, `#UCAP-CONTENT`) had to be inferred from page structure; no built-in discovery helped locate them

---

## Issues Found (7 issues)
> **Review complete:** 0 approved, 7 deferred/rejected

### Issue 1: Template variables (`$cliInvocation`, `$helpCmd`, `$skillPath`, `$RepoRootPath`) are undefined

**Severity:** High
**Category:** Documentation

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Define these variables in a single `.env.eval` file in the repo root, or set them as environment variables before running evaluations

---

### Issue 2: `fill` fails on search textbox with "Element is not focusable" while `type` succeeds

**Severity:** Medium
**Category:** Reliability

#### Review Result

**Decision:** DUPLICATE

**Summary:** - When `fill` fails with "not focusable", the error message should suggest trying `type` as an alternative

---

### Issue 3: `press Enter` on search textbox triggers form GET instead of JS search handler

**Severity:** Medium
**Category:** Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - After `press Enter` on a textbox inside a form, detect whether the page navigated to the form's action URL vs. a different URL — if the form action was used but there's a search button present, s...

---

### Issue 4: Snapshot output defaults to file; `--stdout` requires an extra flag

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** REJECT

**Summary:** - Consider adding a CLI config option for default snapshot output mode (file vs. stdout)

---

### Issue 5: Shell CWD resets after each command, requiring repeated `cd` or verbose `--manifest-path`

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** DUPLICATE

**Summary:** - The evaluation framework should set CWD to `$RepoRootPath` at session start to eliminate the need for repeated `cd`

---

### Issue 6: CSS selectors for page content require guesswork without `htmlsnapshot inspect`

**Severity:** Medium
**Category:** Discoverability

#### Review Result

**Decision:** REJECT

**Summary:** - Add an "article/news extraction" quick pattern to SKILL.md §6 showing typical selectors: `h1` for title, `.pages-date` or `time` for date, `[class*=source]` for source, `article` or `#UCAP-CONTEN...

---

### Issue 7: No built-in summarization without LLM API key configuration

**Severity:** Medium
**Category:** Product

#### Review Result

**Decision:** REJECT

**Summary:** - When `summarize` or `extract` is invoked without a configured LLM key, the error message should clearly state which environment variables need to be set, with a copy-paste-ready example

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Template variables (`$cliInvocation`, `$helpCmd`, `$skillPath`, `$RepoRootPath`) are undefined

Read the evaluation task template. It references `$RepoRootPath`, `$helpCmd`, `$skillPath`, and `$cliInvocation` as if they are defined environment variables or documented constants. They are not set anywhere.

#### Issue 2: `fill` fails on search textbox with "Element is not focusable" while `type` succeeds

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.gov.cn/"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- fill e342 "人工智能"
```
The snapshot shows two textbox elements near the search form: `e340` ("text headSearchword") and `e342` ("text headSearchword 请输入关键字……"). `fill e342` fails; `type e340` succeeds.

#### Issue 3: `press Enter` on search textbox triggers form GET instead of JS search handler

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.gov.cn/"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- type "人工智能" e340
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- press Enter
```

#### Issue 4: Snapshot output defaults to file; `--stdout` requires an extra flag

Run `snapshot -v 0` — the output is a file path, not inline content. The preview shows only the first 10 lines.

#### Issue 5: Shell CWD resets after each command, requiring repeated `cd` or verbose `--manifest-path`

Run any `cargo run --manifest-path ...` command. The shell CWD resets to `C:\Users\pereg` after each invocation.

#### Issue 6: CSS selectors for page content require guesswork without `htmlsnapshot inspect`

After navigating to an article page, the user needs to extract title, date, source, and body content. The correct selectors (`h1`, `.pages-date`, `#UCAP-CONTENT`) are not obvious from the accessibility tree snapshot.

#### Issue 7: No built-in summarization without LLM API key configuration

The task requires summarizing an article into 5 key points. The `summarize` command exists but requires an LLM API key. Without one configured, `summarize` would fail.

#auto-approve
