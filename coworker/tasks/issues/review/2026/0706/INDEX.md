# Issue Index

> **32** files · **169** issues

---

## 📝 htmlsnapshot inspect discovery

> **All 9 task steps completed successfully:**

**8 issues**

1. **Template variables in task specification are undefined**
   🟡 Medium · Documentation

2. **`htmlsnapshot query` with URL submits asynchronously without clear indication**
   🔴 High · UX / Documentation

3. **`htmlsnapshot inspect` auto-discovery ignores explicit single-element container selectors**
   🟡 Medium · Product / UX

4. **`htmlsnapshot grep --selector` uses querySelector (first-match) semantics without documentation**
   🟡 Medium · Documentation / UX

5. **`inspect` does not directly surface all useful child selectors**
   🟢 Low · Discoverability

6. **`htmlsnapshot summary` content section dominated by repetitive buttons**
   🟢 Low · UX

7. **Leftover swarm tasks accumulate across sessions without cleanup**
   🟢 Low · Reliability

8. **Two distinct snapshot systems cause confusion for new users**
   🟡 Medium · UX / Discoverability

---

## 📝 advanced mouse interaction

> **All 13 task steps completed successfully:**

**9 issues**

1. **Template variables in task specification are undefined**
   🟡 Medium · Documentation

2. **`drag` command fails with snapshot element refs**
   🔴 High · Product

3. **`dblclick` fails on non-focusable elements**
   🔴 High · Product

4. **`drag` and `dblclick` use `ref` terminology but don't accept snapshot refs**
   🟡 Medium · Documentation / UX

5. **Dialog-triggering clicks hang and go to background, requiring separate `dialog-accept`**
   🟡 Medium · UX / Reliability

6. **Tooltip hover verification is ambiguous from snapshot output**
   🟢 Low · UX

7. **`cargo run --` from repo root fails (must cd into cli/browser4-cli first)**
   🟡 Medium · Discoverability

8. **`generate-locator` produces simple ID selectors, not "resilient" selectors**
   🟢 Low · Product

9. **`snapshot -i` (interactive) doesn't show how many viewports were skipped**
   🟢 Low · UX

---

## 📝 agent extraction

*Evaluation report — no structured issues*

---

## 📝 Calabi Yau

**9 issues**

1. **Click on off-screen elements fails silently with misleading message**
   🔴 High · UX

2. **`htmlsnapshot inspect` auto-discover fails on e-commerce product grids**
   🔴 High · Reliability

3. **Amazon geo-redirects silently change domain without warning**
   🟡 Medium · Reliability

4. **Development invocation requires cd into subdirectory each time**
   🟡 Medium · UX

5. **Product detail pages sometimes load with empty title and no content**
   🟡 Medium · Reliability

6. **Prices shown in SGD (Singapore Dollars) without clear indication of currency locale**
   🟢 Low · UX

7. **Help output too verbose — 150+ lines for `--help`**
   🟢 Low · Discoverability

8. **SKILL.md core loop uses `snapshot -v 0` but e-commerce pages need `htmlsnapshot`**
   🟢 Low · Documentation

9. **`snapshot grep` produces noisy output with full YAML paths**
   🟢 Low · UX

---

## 📝 Laser Engraved Crystal

> ### Best Pick: **Enjinkail DNA Crystal Ball with LED Base**

**7 issues**

1. **`cargo run` overhead adds noise to every command**
   🟡 Medium · UX

2. **First X-SQL selector attempt silently returned no title/link data**
   🟡 Medium · Reliability

3. **`htmlsnapshot query` output redirection difficult with `cargo run`**
   🟢 Low · UX

4. **Product links extracted as relative paths requiring manual URL construction**
   🟢 Low · UX

5. **SKILL.md reference files not present in repository**
   🟡 Medium · Documentation

6. **`htmlsnapshot inspect` shell quoting fails with attribute selectors on Windows**
   🟡 Medium · Reliability

7. **Amazon locale auto-detection shows prices in non-USD currency**
   🟢 Low · Task Execution

---

## 📝 amazon

> The comparison markdown file has been written to:

**7 issues**

1. **Auto-discover (htmlsnapshot inspect) defaults to generic selectors, not product cards**
   🟡 Medium · UX

2. **Documentation's recommended CSS selectors fail on non-English Amazon locale**
   🟡 Medium · Documentation

3. **Snapshot is YAML-only; no option for JSON machine-readable output**
   🟢 Low · Product

4. **Relative SQL file path resolution from CLI directory is confusing**
   🟢 Low · UX

5. **No built-in command to extract review/rating counts from search results**
   🟢 Low · Product

6. **`htmlsnapshot` output is very verbose — hard to find key info in interactive elements list**
   🟢 Low · UX

7. **`snapshot -v 0` truncation loses product data — products only appear in later viewports**
   🟡 Medium · Discoverability

---

## 📝 attach remote debug

> **Outcome:** ✅ **Task completed successfully** with workarounds.

**7 issues**

1. **`--cdp <channel-name>` panics with tokio runtime error**
   🔴 Critical · Reliability

2. **`tab-new` does not auto-switch to the newly created tab**
   🟡 Medium · UX

3. **`tab-select` output shows stale page info from previous tab**
   🟢 Low · UX

4. **Supported channel names not listed in `attach --help`**
   🟡 Medium · Discoverability

5. **`attach --help` examples don't include `--endpoint` usage**
   🟢 Low · Documentation

6. **`close` on attached sessions provides no warning that the browser stays open**
   🟢 Low · UX

7. **`tab-list` output format is JSON only — no human-readable table**
   🟢 Low · UX

---

## 📝 comprehensive ecommerce workflow

> **Extracted Product Data Summary:**

**7 issues**

1. **MockSite product links contain literal escaped quotes in URLs**
   🔴 High · Product

2. **`htmlsnapshot` fails on listing page after `tab-new` + `tab-select`**
   🔴 High · Reliability

3. **`tab-new` does not auto-switch to the new tab**
   🟡 Medium · UX

4. **`htmlsnapshot inspect --max 3 --depth 2` returns insufficient selectors for product detail page**
   🟡 Medium · Discoverability

5. **MockSite home page has no individual product links**
   🟢 Low · Product

6. **`screenshot` positional argument conflicts with filename intent**
   🟢 Low · UX / Discoverability

7. **`snapshot -v 0` and `snapshot -i` produce identical output for small pages**
   🟢 Low · UX

---

## 📝 crawl advanced extraction

*Evaluation report — no structured issues*

---

## 📝 crawl link discovery

*Evaluation report — no structured issues*

---

## 📝 html snapshot extraction

*Evaluation report — no structured issues*

---

## 📝 form filling

> **Form successfully filled and submitted.** All values were captured and confirmed:

**6 issues**

1. **Interactive snapshot (`-i`) does not display element refs inline**
   🟡 Medium · UX

2. **`select` command confirmation message shows empty string**
   🟢 Low · UX

3. **`get text` requires prior htmlsnapshot capture — confusing error for new users**
   🟡 Medium · Discoverability

4. **`cargo run` invocation requires manual `cd` — no repo-root shortcut**
   🟢 Low · UX

5. **Snapshot YAML is verbose — hard to scan for interactive elements**
   🟢 Low · UX

6. **SKILL.md documentation is comprehensive but difficult to navigate for first-time users**
   🟡 Medium · Documentation

---

## 📝 hacker news

> Successfully navigated to Hacker News, identified the top 3 stories, opened each one, and summarized their content:

**8 issues**

1. **Click action times out with no navigation and misleading error guidance**
   🔴 High · Reliability

2. **goto command auto-captures snapshot but does not display element refs**
   🟡 Medium · UX

3. **Snapshot output is file-path-only; requires separate file read to see content**
   🟡 Medium · UX

4. **snap shot grep uses Rust regex syntax, not grep-style alternation**
   🟢 Low · Discoverability

5. **Accessibility tree snapshots are verbose; htmlsnapshot needed for readable text extraction**
   🟡 Medium · UX / Discoverability

6. **No `--timeout` flag for individual commands; timeout is hardcoded**
   🟡 Medium · Reliability / UX

7. **Refs are ephemeral but no tooling helps manage ref lifecycle across navigations**
   🟢 Low · UX

8. **Windows-specific: `cargo run` compilation overhead adds ~0.12s to every command**
   🟢 Low · UX

---

## 📝 htmlsnapshot inspect discovery

> All 9 steps completed successfully:

**7 issues**

1. **Text extraction silently truncates long content**
   🔴 High · Product

2. **`htmlsnapshot inspect` on a single container element silently redirects to auto-discovered pattern**
   🟡 Medium · UX

3. **`htmlsnapshot grep -c --selector` semantics are confusing**
   🟡 Medium · UX

4. **Suggested selectors from inspect don't include the most obvious ones for link text**
   🟢 Low · UX

5. **WPSI summary content section dominated by buttons — misses key content**
   🟢 Low · Product

6. **Windows shell escaping friction for X-SQL queries**
   🟡 Medium · Documentation / UX

7. **No discoverable way to see `htmlsnapshot inspect` sub-options from CLI help**
   🟢 Low · Discoverability

---

## 📝 javascript evaluation

> All 8 task steps completed successfully:

**7 issues**

1. **`console` command fails with "Unknown tool" error**
   🟡 Medium · Reliability

2. **Snapshot defaults to file output requiring an extra step to view refs**
   🟡 Medium · UX

3. **Shell quoting of JavaScript expressions is painful on Windows**
   🟡 Medium · UX

4. **`--ref` flag discoverability gap in top-level help**
   🟢 Low · Discoverability

5. **`cargo run --` invocation overhead is high for interactive use**
   🟢 Low · UX

6. **Snapshot viewport concept not obvious to first-time users**
   🟢 Low · Discoverability

7. **No `--json` output from snapshot commands**
   🟢 Low · UX

---

## 📝 loop monitoring

> All 12 steps completed successfully:

**7 issues**

1. **`--name` after `--` silently treated as subcommand args instead of loop-level flag**
   🟡 Medium · UX / Discoverability

2. **`--resume` is a state-only operation — doesn't launch process execution**
   🟡 Medium · UX / Documentation

3. **Completed loops auto-clean state with no history or audit trail**
   🟢 Low · UX / Discoverability

4. **Shell output encoding issue on Windows with non-ASCII characters**
   🟢 Low · Reliability / UX

5. **`--list` output doesn't show interval, count, or timeout at a glance**
   🟢 Low · UX

6. **Argument order sensitivity in general — no validation or helpful error for misplaced flags**
   🟡 Medium · UX / Reliability

7. **Documentation doesn't cover `--manifest-path` invocation for source builds — users must infer the pattern**
   🟢 Low · Documentation

---

## 📝 navigation basics

*Evaluation report — no structured issues*

---

## 📝 search summary

**6 issues**

1. **`summarize` command returns empty/useless output on complex pages**
   🔴 High · Reliability

2. **Snapshot output size overwhelming — viewport pagination not obvious to new users**
   🟡 Medium · UX / Discoverability

3. **`htmlsnapshot get text` with compound CSS selector returns only one match**
   🟢 Low · Product

4. **First-run `cargo run` startup delay with no progress indication**
   🟡 Medium · UX

5. **SKILL.md references `htmlsnapshot capture` as separate command, but `htmlsnapshot` alone does the same thing**
   🟢 Low · Documentation

6. **No built-in search engine integration for common search tasks**
   🟡 Medium · Discoverability / Product

---

## 📝 session management

> All 8 steps completed successfully:

**7 issues**

1. **Default session auto-created even when all commands use `-s`**
   🟢 Low · UX

2. **`close-all` reports inaccurate session count**
   🟡 Medium · Reliability

3. **"Next open" column in `list` output is undocumented**
   🟢 Low · Discoverability

4. **No `info` or `detail` subcommand to inspect session state beyond the list table**
   🟡 Medium · UX

5. **SKILL.md installation section mentions Node.js prerequisite but dev mode uses `cargo run`**
   🟢 Low · Documentation

6. **`snapshot -v 0` output refers to a YAML file but provides no inline content preview**
   🟢 Low · UX

7. **Session list column "Session ID" duplicates "Name" for named sessions**
   🟢 Low · UX

---

## 📝 snapshot mastery

*Evaluation report — no structured issues*

---

## 📝 storage state management

*Evaluation report — no structured issues*

---

## 📝 swarm parallel scraping

*Evaluation report — no structured issues*

---

## 📝 tab management

*Evaluation report — no structured issues*

---

## 📝 visual screenshot controls

> All 10 steps completed successfully:

**7 issues**

1. **`resize` command does not confirm the new viewport dimensions**
   🟡 Medium · UX

2. **`scroll` and `resize` commands silently produce full snapshot files as side effects**
   🟡 Medium · Product

3. **Time-based `wait` has no completion confirmation (inconsistent with other wait modes)**
   🟢 Low · UX

4. **Stale browser session from previous use — first command operates on an unexpected page**
   🟡 Medium · Reliability

5. **No way to specify output directory for screenshots and PDFs — all outputs land in `.browser4-cli/snapshot/`**
   🟢 Low · UX

6. **Development-mode invocation is verbose and error-prone**
   🟡 Medium · Discoverability

7. **Help text uses inconsistent terminology between "screenshot" command and "save as" category**
   🟢 Low · Documentation

---

## 📝 x sql extraction functions

> **Task Status:** ✅ **Completed** (with caveats)

**8 issues**

1. **HTML snapshot stores escaped-quote-encoded class names that break CSS selectors**
   🔴 High · Product (Reliability / Data Quality)

2. **`htmlsnapshot inspect` auto-discovered selectors have quoting issues, returning 0 matches**
   🟡 Medium · Reliability (Discoverability)

3. **`LLM_EXTRACT` returns opaque 417 "Expectation Failed" when no LLM API key is configured**
   🟡 Medium · UX (Error Messaging)

4. **Extracted URLs contain malformed paths due to escaped quote artifacts**
   🟡 Medium · Product (Data Quality)

5. **`htmlsnapshot query` output is JSON-only — no human-readable table format available**
   🟢 Low · UX

6. **Working directory drifts into `cli/browser4-cli` when using `cargo run`**
   🟢 Low · UX / Discoverability

7. **`DOM_FIRST_FLOAT` drops trailing zeros from price values**
   🟢 Low · Product (Minor Data Quality)

8. **`cargo run -- --help` outputs a wall of text with mixed organization**
   🟢 Low · Discoverability

---

## 📝 x sql query methods

> **Task:** Navigate to `http://books.toscrape.com/`, discover CSS selectors with `htmlsnapshot inspect`, and extract book titles + prices using X-SQL via all four input methods (`--sql`, `--sql @file`,

**7 issues**

1. **`--sql-base64` standalone (Mode 1) fails — validation rejects it before base64 decode**
   🔴 High · Product

2. **`--sql-base64` boolean flag mode (Mode 2) not captured in tool_params**
   🔴 High · Product

3. **`htmlsnapshot query --help` and `help htmlsnapshot query` omit usage examples**
   🟡 Medium · Documentation

4. **Inline `--sql` shell quoting on Windows is fragile**
   🟡 Medium · UX

5. **`htmlsnapshot inspect` suggests `DOM_FIRST_TEXT` for title but `title` attribute is more complete**
   🟢 Low · Documentation

6. **`goto` silently upgrades HTTP to HTTPS without notification**
   🟢 Low · UX

7. **`$cliInvocation` pattern is verbose and not discoverable**
   🟢 Low · Discoverability

---

## 📝 swarm evaluation

> **All 10 steps completed successfully.**

**10 issues**

1. **`swarm list` does not show tasks submitted via `swarm query`**
   🔴 High · Product

2. **Task IDs are non-sequential UUIDs, making them hard to reference**
   🟡 Medium · UX

3. **No `swarm close` command — session closed with generic `close`**
   🟢 Low · Discoverability

4. **`swarm status` and `swarm result` return identical JSON for completed jobs**
   🟢 Low · Product

5. **File path resolution is confusing with `cargo run` working directory**
   🟡 Medium · UX

6. **`swarm list` shows stale tasks from previous sessions**
   🟡 Medium · Reliability

7. **Extracted image URLs are relative, not absolute**
   🟢 Low · Product

8. **No `--wait` flag for swarm job submission to block until completion**
   🟡 Medium · UX

9. **Documentation example task IDs don't match reality**
   🟢 Low · Documentation

10. **`swarm query` help output is minimal — key options not shown**
   🟢 Low · Discoverability

---

## 📝 20260707 ai agent workflow evaluation

> **All 9 task steps completed successfully:**

**9 issues**

1. **`summarize` command not listed in `--help` output**
   🔴 High · Discoverability

2. **`agent run`, `agent status`, `agent result` invisible in CLI help**
   🔴 High · Discoverability

3. **No `--schema-file` option for `extract` command**
   🟡 Medium · UX / Documentation

4. **Two conflicting schema formats documented for `extract`**
   🟡 Medium · Documentation / UX

5. **`--filename` flag ignores relative path components**
   🟢 Low · Reliability

6. **`--selector` for section-scoped summarization only captures the target element, not its content section**
   🟡 Medium · UX / Product

7. **`agent list` shows stale/inconsistent task data after task completion**
   🟡 Medium · Reliability

8. **Extract/agent output wraps data in non-standard JSON envelope requiring double-parsing**
   🟢 Low · UX

9. **`--schema` doesn't support `@file` syntax (inconsistency with `--sql @file`)**
   🟢 Low · UX / Consistency

---

## 📄 AI Agent Workflow Evaluation

> Source: `` · Date: `` · Mode: ``

**9 issues** (0 reviewed)

1. **`summarize` command not listed in `--help` output**
   🔴 High · Discoverability

2. **`agent run`, `agent status`, `agent result` invisible in CLI help**
   🔴 High · Discoverability

3. **No `--schema-file` option for `extract` command**
   🟡 Medium · UX / Documentation

4. **Two conflicting schema formats documented for `extract`**
   🟡 Medium · Documentation / UX

5. **`--filename` flag ignores relative path components**
   🟢 Low · Reliability

6. **`--selector` for section-scoped summarization only captures the target element, not its content section**
   🟡 Medium · UX / Product

7. **`agent list` shows stale/inconsistent task data after task completion**
   🟡 Medium · Reliability

8. **Extract/agent output wraps data in non-standard JSON envelope requiring double-parsing**
   🟢 Low · UX

9. **`--schema` doesn't support `@file` syntax (inconsistency with `--sql @file`)**
   🟢 Low · UX / Consistency

---

## 📌 Browser4-CLI Crawl Evaluation Report

🟢 Low · Reliability

---

## 📝 20260707 htmlsnapshot evaluation

> **All 10 task steps completed successfully:**

**8 issues**

1. **Text extraction truncates content with ellipsis**
   🔴 High · Product

2. **Shell working-directory drift breaks subsequent relative-path commands**
   🟡 Medium · UX

3. **WPSI summary content section omits key page content (book titles)**
   🟡 Medium · Product

4. **`htmlsnapshot grep -c` counts lines, not word occurrences — documentation is correct but name is ambiguous**
   🟢 Low · Documentation / UX

5. **Reference documentation paths in SKILL.md don't match actual filesystem layout**
   🟢 Low · Documentation

6. **`goto` auto-captures a snapshot but `htmlsnapshot` must still be run separately — unclear workflow**
   🟢 Low · UX / Discoverability

7. **Help output doesn't show `htmlsnapshot get all` usage with `--offset` and `--limit`**
   🟢 Low · Discoverability

8. **No JSON output option tested — `--json` behavior with `get all` not verified**
   🟢 Low · Discoverability (noted for completeness)

---

## 📄 20260707 htmlsnapshot evaluation

> Source: `` · Date: `` · Mode: ``

**8 issues** (0 reviewed)

1. **Text extraction truncates content with ellipsis**
   🔴 High · Product

2. **Shell working-directory drift breaks subsequent relative-path commands**
   🟡 Medium · UX

3. **WPSI summary content section omits key page content (book titles)**
   🟡 Medium · Product

4. **`htmlsnapshot grep -c` counts lines, not word occurrences — documentation is correct but name is ambiguous**
   🟢 Low · Documentation / UX

5. **Reference documentation paths in SKILL.md don't match actual filesystem layout**
   🟢 Low · Documentation

6. **`goto` auto-captures a snapshot but `htmlsnapshot` must still be run separately — unclear workflow**
   🟢 Low · UX / Discoverability

7. **Help output doesn't show `htmlsnapshot get all` usage with `--offset` and `--limit`**
   🟢 Low · Discoverability

8. **No JSON output option tested — `--json` behavior with `get all` not verified**
   🟢 Low · Discoverability (noted for completeness)

---
