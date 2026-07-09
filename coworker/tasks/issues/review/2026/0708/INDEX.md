# Issue Index

> **25** files · **99** issues

---

## 📄 advanced-mouse-interaction

> Source: `20260708-160052-advanced-mouse-interaction.full.md` · Date: `20260708-160052` · Mode: `dev`

**12 issues** (0 reviewed)

1. **MockSite startup via `test.ps1` fails without prior `mvnw install`**
   🔴 High · Documentation

2. **Dialog-triggering clicks timeout (120s) with poor UX**
   🔴 High · Product

3. **`batch` command cannot handle dialog-triggering click + dialog-dismiss**
   🔴 High · Product

4. **Viewport state metadata always reports `processingViewport: 0`**
   🟡 Medium · Product

5. **`drag` command produces no confirmation message**
   🟢 Low · UX

6. **Interactive snapshot (`-i`) truncates preview without clear indication**
   🟡 Medium · UX

7. **Tooltip hover content not visible in accessibility tree snapshots**
   🟡 Medium · Product

8. **`test.ps1` argument passing fails with JVM system properties on Linux**
   🟡 Medium · Reliability

9. **`generate-locator` not mentioned in SKILL.md**
   🟡 Medium · Documentation

10. **`batch` command syntax confusing — `batch --help` interpreted as batch subcommand**
   🟢 Low · UX

11. **`scroll` command output format inconsistent with other commands**
   🟢 Low · UX

12. **First-time startup is slow due to auto-building the runtime bundle**
   🟢 Low · Discoverability

---

## 📄 agent-extraction

> Source: `20260708-161425-agent-extraction.full.md` · Date: `20260708-161425` · Mode: `dev`

*No issues extracted*

---

## 📄 Calabi-Yau

> Source: `20260708-162517-Calabi-Yau.full.md` · Date: `20260708-162517` · Mode: `dev`

**7 issues** (0 reviewed)

1. **Documented Amazon selectors do not match live Amazon search results**
   🔴 High · Documentation

2. **Backend HTTP timeouts on large Amazon product detail pages**
   🔴 High · Reliability

3. **`snapshot -v` flag reports wrong `processingViewport` in output**
   🟡 Medium · Product

4. **`htmlsnapshot inspect` auto-discovery fails to find product cards on search results**
   🟡 Medium · Reliability

5. **Session state silently resets to `about:blank` between navigations**
   🟡 Medium · Reliability

6. **`htmlsnapshot grep` produces confusing regex alternation conversion note**
   🟢 Low · UX

7. **No `--timeout` flag to control per-command HTTP timeout**
   🟢 Low · Discoverability / UX

---

## 📄 Laser-Engraved Crystal

> Source: `20260708-163719-Laser-Engraved Crystal.full.md` · Date: `20260708-163719` · Mode: `dev`

*No issues extracted*

---

## 📄 amazon

> Source: `20260708-164441-amazon.full.md` · Date: `20260708-164441` · Mode: `dev`

**8 issues** (0 reviewed)

1. **Verbose development invocation command**
   🟡 Medium · UX

2. **Silent session reuse on `goto` obscures page state**
   🟡 Medium · UX / Reliability

3. **`snapshot -v 0` preview truncation hides interactive elements**
   🟡 Medium · Discoverability

4. **`htmlsnapshot inspect` without arguments picks wrong element type**
   🟡 Medium · Discoverability / Reliability

5. **`get all` arrays produce unaligned multi-field data**
   🔴 High · Reliability

6. **Cargo build status lines pollute command output**
   🟢 Low · UX

7. **No built-in sponsored/organic result distinction**
   🟢 Low · Product

8. **X-SQL learning curve — no interactive query builder**
   🟡 Medium · Discoverability / UX

---

## 📄 attach-remote-debug

> Source: `20260708-164841-attach-remote-debug.full.md` · Date: `20260708-164841` · Mode: `dev`

**5 issues** (0 reviewed)

1. **Named session flag (-s) silently ignored during attach**
   🟡 Medium · Product

2. **`attach --help` does not document `--cdp channel` values**
   🟡 Medium · Discoverability

3. **`close` command naming is misleading for attached sessions**
   🟢 Low · UX

4. **Snapshot default output is a file path, not inline content**
   🟢 Low · UX

5. **`list` and `tab-list` are separate concepts that overlap confusingly**
   🟢 Low · UX / Discoverability

---

## 📄 comprehensive-ecommerce-workflow

> Source: `20260708-165610-comprehensive-ecommerce-workflow.full.md` · Date: `20260708-165610` · Mode: `dev`

**9 issues** (0 reviewed)

1. **Home page has no product links — task instructions assume products on home page**
   🟡 Medium · Documentation

2. **MockSite generates malformed HTML with literal double-quote characters in attributes**
   🔴 High · Product

3. **htmlsnapshot capture fails with `ReferenceError: __pulsar_utils__ is not defined` when page loaded via tab-new**
   🟡 Medium · Reliability

4. **screenshot command has no filename parameter — argument is misinterpreted as element ref**
   🟢 Low · UX

5. **DOM_FIRST_IMG returns empty string for small/placeholder images**
   🟡 Medium · Product

6. **extract command works without LLM API key — undocumented behavior**
   🟢 Low · Documentation

7. **Output files (screenshots, state-save, snapshots) scattered across repo root — no output directory concept**
   🟢 Low · UX

8. **Shell escaping required for eval JavaScript with double quotes on Linux**
   🟢 Low · UX

9. **snapshot grep pattern note about `\|` conversion is confusing for new users**
   🟢 Low · Discoverability

---

## 📄 crawl-advanced-extraction

> Source: `20260708-173325-crawl-advanced-extraction.full.md` · Date: `20260708-173325` · Mode: `dev`

*No issues extracted*

---

## 📄 crawl-link-discovery

> Source: `20260708-182349-crawl-link-discovery.full.md` · Date: `20260708-182349` · Mode: `dev`

**10 issues** (0 reviewed)

1. **CSS selector quoting breaks JCommander args parser**
   🔴 Critical · Product

2. **`--refresh` flag causes `Protocol not found` (ProtoNotFound 1600) for HTTPS URLs**
   🔴 Critical · Reliability

3. **`--format csv` and `--format json` have no effect without `--sql`**
   🔴 High · Documentation / UX

4. **CLI sends null `args` field when no link-discovery flags are set, causing 400 error**
   🔴 High · Product

5. **Crawl tasks persist in queue across backend restarts with no way to cancel**
   🔴 High · Reliability / UX

6. **Background crawl tasks remain "pending" forever; status never updates**
   🟡 Medium · Reliability / UX

7. **Crawl always takes 90+ seconds "waiting to start" before processing**
   🟡 Medium · UX / Reliability

8. **Crawl reports "completed. N pages found" even when all pages failed to load**
   🟡 Medium · Reliability / UX

9. **`--json` global flag does not produce machine-parseable JSON for crawl output**
   🟢 Low · Product

10. **Help output for crawl `--format` does not mention it only works with `--sql`**
   🟢 Low · Documentation / Discoverability

---

## 📄 html-snapshot-extraction

> Source: `20260708-182856-html-snapshot-extraction.full.md` · Date: `20260708-182856` · Mode: `dev`

**7 issues** (0 reviewed)

1. **`htmlsnapshot capture` HTTP timeout on first attempt**
   🔴 High · Reliability

2. **`get text` and `get all text` return site-truncated text without indicating truncation**
   🟡 Medium · UX

3. **`--offset` in `get all` is zero-indexed but not documented as such**
   🟢 Low · Documentation

4. **`grep -c` output is ambiguous — count of what?**
   🟢 Low · UX

5. **`--help` output is overwhelming — hundreds of lines with no TOC or filtering**
   🟡 Medium · Discoverability

6. **Dev mode invocation is verbose and error-prone**
   🟢 Low · UX (Development)

7. **`htmlsnapshot summary` output is dense and difficult to parse for new users**
   🟢 Low · UX

---

## 📄 form-filling

> Source: `20260708-183254-form-filling.full.md` · Date: `20260708-183254` · Mode: `dev`

*No issues extracted*

---

## 📄 hacker-news

> Source: `20260708-184022-hacker-news.full.md` · Date: `20260708-184022` · Mode: `dev`

**6 issues** (0 reviewed)

1. **Major sites (mistral.ai, openai.com) refuse CDP browser connections**
   🔴 High · Reliability

2. **`htmlsnapshot get attr` returns wrong href for HN story links**
   🟡 Medium · UX / Documentation

3. **No automatic re-snapshot after navigation — silent ref staleness risk**
   🟡 Medium · UX / Reliability

4. **`snapshot` vs `htmlsnapshot` — confusing two-system design for new users**
   🟡 Medium · Discoverability / Documentation

5. **Cargo rebuild overhead on every command (~0.5s each)**
   🟢 Low · UX

6. **Tips suggest `--stdout` but it's easy to overlook**
   🟢 Low · Discoverability

---

## 📄 htmlsnapshot-inspect-discovery

> Source: `20260708-184600-htmlsnapshot-inspect-discovery.full.md` · Date: `20260708-184600` · Mode: `dev`

*No issues extracted*

---

## 📄 javascript-evaluation

> Source: `20260708-184937-javascript-evaluation.full.md` · Date: `20260708-184937` · Mode: `dev`

**6 issues** (0 reviewed)

1. **`--stdout` flag not obvious for first-time snapshot users**
   🟡 Medium · Discoverability

2. **First `goto` showed "Reconnected to existing session" for a stale session**
   🟢 Low · UX / Reliability

3. **`console.log` output from `eval --file` is silently discarded**
   🟡 Medium · Documentation / UX

4. **Shell quoting of inline JS is error-prone**
   🟡 Medium · UX

5. **`snapshot -i` mode strips generic containers — documented but easy to miss**
   🟢 Low · Documentation

6. **`--ref` supports CSS selectors but help output emphasizes snapshot refs**
   🟢 Low · Discoverability

---

## 📄 loop-monitoring

> Source: `20260708-185531-loop-monitoring.full.md` · Date: `20260708-185531` · Mode: `dev`

**9 issues** (0 reviewed)

1. **Plain-text loop mode fails without a working LLM backend**
   🔴 High · Reliability

2. **Loop state persists across sessions; stale loops from prior sessions cause confusion**
   🟡 Medium · UX

3. **Error handling in loop completion message is misleading**
   🟡 Medium · UX

4. **`--list` and `--history` are separate commands but users expect unified view**
   🟢 Low · Discoverability

5. **Pause command has inherent race condition with fast-executing loops**
   🟢 Low · Reliability

6. **`--name` validation silently allows reusing names of completed loops**
   🟢 Low · UX

7. **`--interval` short flag `-i` conflicts with other CLI conventions**
   🟢 Low · Discoverability

8. **No `--json` output example in loop reference docs**
   🟢 Low · Documentation

9. **`--resume` spawns background process — control returns immediately with no `--wait` option**
   🟢 Low · UX

---

## 📄 navigation-basics

> Source: `20260708-190113-navigation-basics.full.md` · Date: `20260708-190113` · Mode: `dev`

**6 issues** (0 reviewed)

1. **Viewport snapshots produce truncated accessibility trees after scrolling**
   🔴 High · Reliability

2. **Snapshot grep refs are undocumented as usable targets for click/fill/etc.**
   🟡 Medium · Documentation

3. **Long page navigation workflow is poorly documented**
   🟡 Medium · Discoverability

4. **First-run build time has no progress feedback**
   🟢 Low · UX

5. **`snapshot grep` regex support has inconsistent behavior**
   🟢 Low · UX

6. **No quick inline snapshot view for simple pages**
   🟢 Low · UX

---

## 📄 search-summary

> Source: `20260708-190909-search-summary.full.md` · Date: `20260708-190909` · Mode: `dev`

*No issues extracted*

---

## 📄 session-management

> Source: `20260708-191248-session-management.full.md` · Date: `20260708-191248` · Mode: `dev`

*No issues extracted*

---

## 📄 snapshot-mastery

> Source: `20260708-192408-snapshot-mastery.full.md` · Date: `20260708-192408` · Mode: `dev`

*No issues extracted*

---

## 📄 storage-state-management

> Source: `20260708-192816-storage-state-management.full.md` · Date: `20260708-192816` · Mode: `dev`

**8 issues** (0 reviewed)

1. **Typo "entrie(s)" in clear command output**
   🟢 Low · Product

2. **`state-save` and `state-load` file paths resolved relative to binary directory, not user CWD**
   🟡 Medium · UX

3. **`cookie-get` returns full JSON object, no way to get value-only output**
   🟢 Low · Discoverability

4. **`state-save` does not save sessionStorage**
   🟢 Low · Documentation

5. **No way to run from source documented for developers**
   🟡 Medium · Discoverability

6. **`cookie-set` confirmation message does not echo set attributes**
   🟢 Low · UX

7. **`--json` global flag behavior not obvious for storage commands**
   🟢 Low · Discoverability

8. **Help text and README disagree on `wait` command syntax**
   🟢 Low · Documentation

---

## 📄 swarm-parallel-scraping

> Source: `20260708-194047-swarm-parallel-scraping.full.md` · Date: `20260708-194047` · Mode: `dev`

*No issues extracted*

---

## 📄 tab-management

> Source: `20260708-195042-tab-management.full.md` · Date: `20260708-195042` · Mode: `dev`

*No issues extracted*

---

## 📄 visual-screenshot-controls

> Source: `20260708-195458-visual-screenshot-controls.full.md` · Date: `20260708-195458` · Mode: `dev`

*No issues extracted*

---

## 📄 x-sql-extraction-functions

> Source: `20260708-200137-x-sql-extraction-functions.full.md` · Date: `20260708-200137` · Mode: `dev`

*No issues extracted*

---

## 📄 x-sql-query-methods

> Source: `20260708-200550-x-sql-query-methods.full.md` · Date: `20260708-200550` · Mode: `dev`

**6 issues** (0 reviewed)

1. **"Finding browser4 root" debug message leaks into user output**
   🟢 Low · Reliability

2. **`--result-only` flag not discoverable from global help**
   🟡 Medium · Discoverability

3. **DOM_FIRST_FLOAT results serialized as JSON strings, not numbers**
   🟡 Medium · Product

4. **Inconsistent function name casing in documentation**
   🟢 Low · Documentation

5. **SKILL.md examples always include URL for `htmlsnapshot query`, obscuring that it's optional**
   🟢 Low · Documentation

6. **Titles truncated with ellipsis in X-SQL results**
   🟡 Medium · Product

---
