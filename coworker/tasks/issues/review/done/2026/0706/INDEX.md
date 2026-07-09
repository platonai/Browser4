# Issue Index

> **18** files · **134** issues · **126** reviewed

---

## 📄 htmlsnapshot-inspect-discovery

> Source: `20260706-165414-htmlsnapshot-inspect-discovery.full.md` · Date: `20260706-165414` · Mode: `dev`

**8 issues** (5 reviewed)

1. **Template variables in task specification are undefined**
   🟡 Medium · Documentation · ❌ WONTFIX

2. **`htmlsnapshot query` with URL submits asynchronously without clear indication**
   🔴 High · UX / Documentation · 🔧 ACCEPT with improvements

3. **`htmlsnapshot inspect` auto-discovery ignores explicit single-element container selectors**
   🟡 Medium · Product / UX · ✅ ACCEPT

4. **`htmlsnapshot grep --selector` uses querySelector (first-match) semantics without documentation**
   🟡 Medium · Documentation / UX · 🔧 ACCEPT with improvements

5. **`inspect` does not directly surface all useful child selectors**
   🟢 Low · Discoverability · ❌ WONTFIX

6. **`htmlsnapshot summary` content section dominated by repetitive buttons**
   🟢 Low · UX

7. **Leftover swarm tasks accumulate across sessions without cleanup**
   🟢 Low · Reliability

8. **Two distinct snapshot systems cause confusion for new users**
   🟡 Medium · UX / Discoverability

---

## 📄 advanced-mouse-interaction

> Source: `20260706-184550-advanced-mouse-interaction.full.md` · Date: `20260706-184550` · Mode: `dev`

**9 issues** (8 reviewed)

1. **Template variables in task specification are undefined**
   🟡 Medium · Documentation · ❌ WONTFIX

2. **`drag` command fails with snapshot element refs**
   🔴 High · Product · ✅ ACCEPT

3. **`dblclick` fails on non-focusable elements**
   🔴 High · Product · 🔧 ACCEPT with improvements

4. **`drag` and `dblclick` use `ref` terminology but don't accept snapshot refs**
   🟡 Medium · Documentation / UX · 🔧 ACCEPT with improvements

5. **Dialog-triggering clicks hang and go to background, requiring separate `dialog-accept`**
   🟡 Medium · UX / Reliability · ❌ WONTFIX

6. **Tooltip hover verification is ambiguous from snapshot output**
   🟢 Low · UX

7. **`cargo run --` from repo root fails (must cd into cli/browser4-cli first)**
   🟡 Medium · Discoverability · ❌ WONTFIX

8. **`generate-locator` produces simple ID selectors, not "resilient" selectors**
   🟢 Low · Product · ✅ ACCEPT

9. **`snapshot -i` (interactive) doesn't show how many viewports were skipped**
   🟢 Low · UX · ✅ ACCEPT

---

## 📄 Calabi-Yau

> Source: `20260706-190640-Calabi-Yau.full.md` · Date: `20260706-190640` · Mode: `dev`

**9 issues** (7 reviewed)

1. **Click on off-screen elements fails silently with misleading message**
   🔴 High · UX · ✅ ACCEPT

2. **`htmlsnapshot inspect` auto-discover fails on e-commerce product grids**
   🔴 High · Reliability · ⏸ DEFER

3. **Amazon geo-redirects silently change domain without warning**
   🟡 Medium · Reliability · ❌ WONTFIX

4. **Development invocation requires cd into subdirectory each time**
   🟡 Medium · UX · ⏸ DEFER

5. **Product detail pages sometimes load with empty title and no content**
   🟡 Medium · Reliability · ❌ WONTFIX

6. **Prices shown in SGD (Singapore Dollars) without clear indication of currency locale**
   🟢 Low · UX

7. **Help output too verbose — 150+ lines for `--help`**
   🟢 Low · Discoverability

8. **SKILL.md core loop uses `snapshot -v 0` but e-commerce pages need `htmlsnapshot`**
   🟢 Low · Documentation · ⏸ DEFER

9. **`snapshot grep` produces noisy output with full YAML paths**
   🟢 Low · UX · ⏸ DEFER

---

## 📄 Laser-Engraved Crystal

> Source: `20260706-191631-Laser-Engraved Crystal.full.md` · Date: `20260706-191631` · Mode: `dev`

**7 issues** (7 reviewed)

1. **`cargo run` overhead adds noise to every command**
   🟡 Medium · UX · ❌ WONTFIX

2. **First X-SQL selector attempt silently returned no title/link data**
   🟡 Medium · Reliability · 🔧 ACCEPT with improvements

3. **`htmlsnapshot query` output redirection difficult with `cargo run`**
   🟢 Low · UX · ✅ ACCEPT

4. **Product links extracted as relative paths requiring manual URL construction**
   🟢 Low · UX · 🔧 ACCEPT with improvements

5. **SKILL.md reference files not present in repository**
   🟡 Medium · Documentation · ❌ WONTFIX

6. **`htmlsnapshot inspect` shell quoting fails with attribute selectors on Windows**
   🟡 Medium · Reliability · 🔧 ACCEPT with improvements

7. **Amazon locale auto-detection shows prices in non-USD currency**
   🟢 Low · Task Execution · ❌ WONTFIX

---

## 📄 amazon

> Source: `20260706-192330-amazon.full.md` · Date: `20260706-192330` · Mode: `dev`

**7 issues** (7 reviewed)

1. **Auto-discover (htmlsnapshot inspect) defaults to generic selectors, not product cards**
   🟡 Medium · UX · ⏸ DEFER

2. **Documentation's recommended CSS selectors fail on non-English Amazon locale**
   🟡 Medium · Documentation · ❌ WONTFIX

3. **Snapshot is YAML-only; no option for JSON machine-readable output**
   🟢 Low · Product · ❌ WONTFIX

4. **Relative SQL file path resolution from CLI directory is confusing**
   🟢 Low · UX · 🔧 ACCEPT with improvements

5. **No built-in command to extract review/rating counts from search results**
   🟢 Low · Product · ❌ WONTFIX

6. **`htmlsnapshot` output is very verbose — hard to find key info in interactive elements list**
   🟢 Low · UX · ⏸ DEFER

7. **`snapshot -v 0` truncation loses product data — products only appear in later viewports**
   🟡 Medium · Discoverability · ❌ WONTFIX

---

## 📄 attach-remote-debug

> Source: `20260706-193037-attach-remote-debug.full.md` · Date: `20260706-193037` · Mode: `dev`

**7 issues** (7 reviewed)

1. **`--cdp <channel-name>` panics with tokio runtime error**
   🔴 Critical · Reliability · ✅ ACCEPT

2. **`tab-new` does not auto-switch to the newly created tab**
   🟡 Medium · UX · ✅ ACCEPT

3. **`tab-select` output shows stale page info from previous tab**
   🟢 Low · UX · ✅ ACCEPT

4. **Supported channel names not listed in `attach --help`**
   🟡 Medium · Discoverability · ✅ ACCEPT

5. **`attach --help` examples don't include `--endpoint` usage**
   🟢 Low · Documentation · ✅ ACCEPT

6. **`close` on attached sessions provides no warning that the browser stays open**
   🟢 Low · UX · ✅ ACCEPT

7. **`tab-list` output format is JSON only — no human-readable table**
   🟢 Low · UX · ✅ ACCEPT

---

## 📄 comprehensive-ecommerce-workflow

> Source: `20260706-194023-comprehensive-ecommerce-workflow.full.md` · Date: `20260706-194023` · Mode: `dev`

**7 issues** (7 reviewed)

1. **MockSite product links contain literal escaped quotes in URLs**
   🔴 High · Product · ✅ ACCEPT

2. **`htmlsnapshot` fails on listing page after `tab-new` + `tab-select`**
   🔴 High · Reliability · ⏸ DEFER

3. **`tab-new` does not auto-switch to the new tab**
   🟡 Medium · UX · ⏸ DEFER

4. **`htmlsnapshot inspect --max 3 --depth 2` returns insufficient selectors for product detail page**
   🟡 Medium · Discoverability · ✅ ACCEPT

5. **MockSite home page has no individual product links**
   🟢 Low · Product · ✅ ACCEPT

6. **`screenshot` positional argument conflicts with filename intent**
   🟢 Low · UX / Discoverability · ✅ ACCEPT

7. **`snapshot -v 0` and `snapshot -i` produce identical output for small pages**
   🟢 Low · UX · ✅ ACCEPT

---

## 📄 form-filling

> Source: `20260706-203229-form-filling.full.md` · Date: `20260706-203229` · Mode: `dev`

**6 issues** (5 reviewed)

1. **Interactive snapshot (`-i`) does not display element refs inline**
   🟡 Medium · UX · 🚫 REJECT

2. **`select` command confirmation message shows empty string**
   🟢 Low · UX · ✅ ACCEPT

3. **`get text` requires prior htmlsnapshot capture — confusing error for new users**
   🟡 Medium · Discoverability · ✅ ACCEPT

4. **`cargo run` invocation requires manual `cd` — no repo-root shortcut**
   🟢 Low · UX · ❌ WONTFIX

5. **Snapshot YAML is verbose — hard to scan for interactive elements**
   🟢 Low · UX · ⏸ DEFER

6. **SKILL.md documentation is comprehensive but difficult to navigate for first-time users**
   🟡 Medium · Documentation

---

## 📄 hacker-news

> Source: `20260706-204047-hacker-news.full.md` · Date: `20260706-204047` · Mode: `dev`

**8 issues** (8 reviewed)

1. **Click action times out with no navigation and misleading error guidance**
   🔴 High · Reliability · ⏸ DEFER

2. **goto command auto-captures snapshot but does not display element refs**
   🟡 Medium · UX · 🔧 ACCEPT with improvements

3. **Snapshot output is file-path-only; requires separate file read to see content**
   🟡 Medium · UX · ✅ ACCEPT

4. **snap shot grep uses Rust regex syntax, not grep-style alternation**
   🟢 Low · Discoverability · ✅ ACCEPT

5. **Accessibility tree snapshots are verbose; htmlsnapshot needed for readable text extraction**
   🟡 Medium · UX / Discoverability · ⏸ DEFER

6. **No `--timeout` flag for individual commands; timeout is hardcoded**
   🟡 Medium · Reliability / UX · ⏸ DEFER

7. **Refs are ephemeral but no tooling helps manage ref lifecycle across navigations**
   🟢 Low · UX · ⏸ DEFER

8. **Windows-specific: `cargo run` compilation overhead adds ~0.12s to every command**
   🟢 Low · UX · ❌ WONTFIX

---

## 📄 htmlsnapshot-inspect-discovery

> Source: `20260706-204519-htmlsnapshot-inspect-discovery.full.md` · Date: `20260706-204519` · Mode: `dev`

**7 issues** (7 reviewed)

1. **Text extraction silently truncates long content**
   🔴 High · Product · 🚫 REJECT

2. **`htmlsnapshot inspect` on a single container element silently redirects to auto-discovered pattern**
   🟡 Medium · UX · ⏸ DEFER

3. **`htmlsnapshot grep -c --selector` semantics are confusing**
   🟡 Medium · UX · 🔧 ACCEPT with improvements

4. **Suggested selectors from inspect don't include the most obvious ones for link text**
   🟢 Low · UX · ⏸ DEFER

5. **WPSI summary content section dominated by buttons — misses key content**
   🟢 Low · Product · ✅ ACCEPT

6. **Windows shell escaping friction for X-SQL queries**
   🟡 Medium · Documentation / UX · 🚫 REJECT

7. **No discoverable way to see `htmlsnapshot inspect` sub-options from CLI help**
   🟢 Low · Discoverability · ✅ ACCEPT

---

## 📄 javascript-evaluation

> Source: `20260706-204914-javascript-evaluation.full.md` · Date: `20260706-204914` · Mode: `dev`

**7 issues** (7 reviewed)

1. **`console` command fails with "Unknown tool" error**
   🟡 Medium · Reliability · ✅ ACCEPT

2. **Snapshot defaults to file output requiring an extra step to view refs**
   🟡 Medium · UX · 🚫 REJECT

3. **Shell quoting of JavaScript expressions is painful on Windows**
   🟡 Medium · UX · 🚫 REJECT

4. **`--ref` flag discoverability gap in top-level help**
   🟢 Low · Discoverability · ✅ ACCEPT

5. **`cargo run --` invocation overhead is high for interactive use**
   🟢 Low · UX · 🚫 REJECT

6. **Snapshot viewport concept not obvious to first-time users**
   🟢 Low · Discoverability · ✅ ACCEPT

7. **No `--json` output from snapshot commands**
   🟢 Low · UX · ✅ ACCEPT

---

## 📄 loop-monitoring

> Source: `20260706-205549-loop-monitoring.full.md` · Date: `20260706-205549` · Mode: `dev`

**7 issues** (7 reviewed)

1. **`--name` after `--` silently treated as subcommand args instead of loop-level flag**
   🟡 Medium · UX / Discoverability · ✅ ACCEPT

2. **`--resume` is a state-only operation — doesn't launch process execution**
   🟡 Medium · UX / Documentation · 🔧 ACCEPT with improvements

3. **Completed loops auto-clean state with no history or audit trail**
   🟢 Low · UX / Discoverability · ✅ ACCEPT

4. **Shell output encoding issue on Windows with non-ASCII characters**
   🟢 Low · Reliability / UX · ✅ ACCEPT

5. **`--list` output doesn't show interval, count, or timeout at a glance**
   🟢 Low · UX · ✅ ACCEPT

6. **Argument order sensitivity in general — no validation or helpful error for misplaced flags**
   🟡 Medium · UX / Reliability · ✅ ACCEPT

7. **Documentation doesn't cover `--manifest-path` invocation for source builds — users must infer the pattern**
   🟢 Low · Documentation · ✅ ACCEPT

---

## 📄 search-summary

> Source: `20260706-210815-search-summary.full.md` · Date: `20260706-210815` · Mode: `dev`

**6 issues** (6 reviewed)

1. **`summarize` command returns empty/useless output on complex pages**
   🔴 High · Reliability · ⏸ DEFER

2. **Snapshot output size overwhelming — viewport pagination not obvious to new users**
   🟡 Medium · UX / Discoverability · ⏸ DEFER

3. **`htmlsnapshot get text` with compound CSS selector returns only one match**
   🟢 Low · Product · ✅ ACCEPT

4. **First-run `cargo run` startup delay with no progress indication**
   🟡 Medium · UX · ✅ ACCEPT

5. **SKILL.md references `htmlsnapshot capture` as separate command, but `htmlsnapshot` alone does the same thing**
   🟢 Low · Documentation · ✅ ACCEPT

6. **No built-in search engine integration for common search tasks**
   🟡 Medium · Discoverability / Product · ⏸ DEFER

---

## 📄 session-management

> Source: `20260706-211118-session-management.full.md` · Date: `20260706-211118` · Mode: `dev`

**7 issues** (7 reviewed)

1. **Default session auto-created even when all commands use `-s`**
   🟢 Low · UX · ✅ ACCEPT

2. **`close-all` reports inaccurate session count**
   🟡 Medium · Reliability · ✅ ACCEPT

3. **"Next open" column in `list` output is undocumented**
   🟢 Low · Discoverability · ✅ ACCEPT

4. **No `info` or `detail` subcommand to inspect session state beyond the list table**
   🟡 Medium · UX · ⏸ DEFER

5. **SKILL.md installation section mentions Node.js prerequisite but dev mode uses `cargo run`**
   🟢 Low · Documentation · ✅ ACCEPT

6. **`snapshot -v 0` output refers to a YAML file but provides no inline content preview**
   🟢 Low · UX · ⏸ DEFER

7. **Session list column "Session ID" duplicates "Name" for named sessions**
   🟢 Low · UX · ⏸ DEFER

---

## 📄 visual-screenshot-controls

> Source: `20260706-215254-visual-screenshot-controls.full.md` · Date: `20260706-215254` · Mode: `dev`

**7 issues** (7 reviewed)

1. **`resize` command does not confirm the new viewport dimensions**
   🟡 Medium · UX · ✅ ACCEPT

2. **`scroll` and `resize` commands silently produce full snapshot files as side effects**
   🟡 Medium · Product · ✅ ACCEPT

3. **Time-based `wait` has no completion confirmation (inconsistent with other wait modes)**
   🟢 Low · UX · ✅ ACCEPT

4. **Stale browser session from previous use — first command operates on an unexpected page**
   🟡 Medium · Reliability · 🔧 ACCEPT with improvements

5. **No way to specify output directory for screenshots and PDFs — all outputs land in `.browser4-cli/snapshot/`**
   🟢 Low · UX · ✅ ACCEPT

6. **Development-mode invocation is verbose and error-prone**
   🟡 Medium · Discoverability · 🚫 REJECT

7. **Help text uses inconsistent terminology between "screenshot" command and "save as" category**
   🟢 Low · Documentation · ✅ ACCEPT

---

## 📄 x-sql-extraction-functions

> Source: `20260706-215916-x-sql-extraction-functions.full.md` · Date: `20260706-215916` · Mode: `dev`

**8 issues** (8 reviewed)

1. **HTML snapshot stores escaped-quote-encoded class names that break CSS selectors**
   🔴 High · Product (Reliability / Data Quality) · 📋 DUPLICATE

2. **`htmlsnapshot inspect` auto-discovered selectors have quoting issues, returning 0 matches**
   🟡 Medium · Reliability (Discoverability) · 📋 DUPLICATE

3. **`LLM_EXTRACT` returns opaque 417 "Expectation Failed" when no LLM API key is configured**
   🟡 Medium · UX (Error Messaging) · 🔧 ACCEPT with improvements

4. **Extracted URLs contain malformed paths due to escaped quote artifacts**
   🟡 Medium · Product (Data Quality) · 📋 DUPLICATE

5. **`htmlsnapshot query` output is JSON-only — no human-readable table format available**
   🟢 Low · UX · ✅ ACCEPT

6. **Working directory drifts into `cli/browser4-cli` when using `cargo run`**
   🟢 Low · UX / Discoverability · ⏸ DEFER

7. **`DOM_FIRST_FLOAT` drops trailing zeros from price values**
   🟢 Low · Product (Minor Data Quality) · ⏸ DEFER

8. **`cargo run -- --help` outputs a wall of text with mixed organization**
   🟢 Low · Discoverability · ✅ ACCEPT

---

## 📄 x-sql-query-methods

> Source: `20260706-220651-x-sql-query-methods.full.md` · Date: `20260706-220651` · Mode: `dev`

**7 issues** (7 reviewed)

1. **`--sql-base64` standalone (Mode 1) fails — validation rejects it before base64 decode**
   🔴 High · Product · ✅ ACCEPT

2. **`--sql-base64` boolean flag mode (Mode 2) not captured in tool_params**
   🔴 High · Product · ✅ ACCEPT

3. **`htmlsnapshot query --help` and `help htmlsnapshot query` omit usage examples**
   🟡 Medium · Documentation · ✅ ACCEPT

4. **Inline `--sql` shell quoting on Windows is fragile**
   🟡 Medium · UX · ✅ ACCEPT

5. **`htmlsnapshot inspect` suggests `DOM_FIRST_TEXT` for title but `title` attribute is more complete**
   🟢 Low · Documentation · ⏸ DEFER

6. **`goto` silently upgrades HTTP to HTTPS without notification**
   🟢 Low · UX · ❌ WONTFIX

7. **`$cliInvocation` pattern is verbose and not discoverable**
   🟢 Low · Discoverability · ✅ ACCEPT

---

## 📄 swarm-evaluation

> Source: `20260707-103000-swarm-evaluation.full.md` · Date: `20260707-103000` · Mode: `dev`

**10 issues** (9 reviewed)

1. **`swarm list` does not show tasks submitted via `swarm query`**
   🔴 High · Product · ✅ ACCEPT

2. **Task IDs are non-sequential UUIDs, making them hard to reference**
   🟡 Medium · UX · ✅ ACCEPT

3. **No `swarm close` command — session closed with generic `close`**
   🟢 Low · Discoverability · ✅ ACCEPT

4. **`swarm status` and `swarm result` return identical JSON for completed jobs**
   🟢 Low · Product · ✅ ACCEPT

5. **File path resolution is confusing with `cargo run` working directory**
   🟡 Medium · UX

6. **`swarm list` shows stale tasks from previous sessions**
   🟡 Medium · Reliability · ✅ ACCEPT

7. **Extracted image URLs are relative, not absolute**
   🟢 Low · Product · ❌ WONTFIX

8. **No `--wait` flag for swarm job submission to block until completion**
   🟡 Medium · UX · ✅ ACCEPT

9. **Documentation example task IDs don't match reality**
   🟢 Low · Documentation · ✅ ACCEPT

10. **`swarm query` help output is minimal — key options not shown**
   🟢 Low · Discoverability · ✅ ACCEPT

---
