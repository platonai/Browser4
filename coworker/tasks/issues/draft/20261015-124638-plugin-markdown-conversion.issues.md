# Issues: plugin-markdown-conversion

> **Source:** `20261015-124638-plugin-markdown-conversion.issues.json` | **Date:** 20261015-124638 | **Mode:** dev

## Scenario Background

### Task

All 7 steps completed successfully — but only after a setup step nobody documents in the agent-facing skill: **the browser4-markdown plugin is not installed by default**.

| Step | Result |
|---|---|
| 1. Navigate to Wikipedia *Web scraping* | ✅ headless session `md-test`, page title "Web scraping - Wikipedia" |
| 2. `markdown.convert` | ✅ `Web-scraping-Wikipedia.md`, `charCount` 48344, `linkCount` 225, `imageCount` 3, H1 = page title |
| 3. First 50 lines | ✅ H1 (line 6), paragraphs, `[text](url)` links, 2 tables; whole file: 19 headings, 38 bullets, 39 numbered items, 3 tables, 336 markdown links |
| 4. `markdown.discoverLinks` | ✅ total 324 = 225 internal + 99 external (verified independently: `links[]`=324, `internalUrls`=225) |
| 5. Convert *Data scraping* | ✅ different file (`cmp` ≠), 21573 chars, 360 links, 2 images, valid H1 + front matter |
| 6. `markdown.fetch` httpbin.org/html | ✅ `success:true`, `# Herman Melville - Moby-Dick` + full paragraph extracted, no browser used |
| 7. `markdown.crawl` httpbin.org (depth 1, max 3) | ✅ 1 page crawled successfully (`httpbin.org.md` written); summary counters however are wrong (see Issue 3) |

Raw evidence dumped to `.test-sessions/20261015T0901431699046Z/99-evidence.md`.

### Execution Context

**Setup (not in the task, but required):** `help` → `plugin list` (only captcha/images/swarm) → located `browser4-plugins/browser4-markdown/target/browser4-markdown-4.14.1-SNAPSHOT.jar` → `plugin install <jar>` → `stop` (restart needed to load) → next command auto-started the backend in 6.8 s → `plugin list` now shows 4 plugins. Tool names were confirmed via `curl http://localhost:8282/mcp/tools` (`markdown_convert`, `markdown_crawl`, `markdown_crawl_from`, `markdown_fetch`, `markdown_discover_links`, `markdown_read`).

**Core loop:** `./b4w.ps1 -s md-test goto <url>` → `./b4w.ps1 -s md-test tool call markdown_convert --json '{"outputPath": ".../.test-sessions/20261015T0901431699046Z/md-…"}'` (repeated per page), `tool call markdown_discover_links`, `tool call markdown_fetch --json '{...}'...

(truncated — see 20261015-124638-plugin-markdown-conversion.issues.json for the complete execution trace)

---

## Issues Found (9 issues)

### Issue 1: browser4-markdown plugin is not installed by default and the agent skill never mentions it

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 plugin list   # -> 3 plugins: captcha, images, swarm (no markdown)
./b4w.ps1 help | grep -i markdown   # -> only a 'scrape --formats ... markdown' mention
# SKILL.md has no occurrence of 'markdown.convert|markdown.crawl|markdown_fetch|plugin install'

#### Expected Behavior

The markdown plugin (or its install step) should be discoverable from `help` and from skills/browser4-cli/SKILL.md, since SKILL.md is the document a first-time agent is told to read completely. Either it is bundled by default in dev mode, or the skill names the plugin, the JAR location and the install+restart procedure.

#### Actual Behavior

The tools do not exist until the user finds `browser4-plugins/browser4-markdown/target/browser4-markdown-4.14.1-SNAPSHOT.jar` on their own, runs `plugin install`, and then `stop` to restart the backend. The only pointer is the tail of the general help ('── Server plugins ──'), which SKILL.md does not link to; `help tool` prints a single line and lists no plugin tools.

#### Root Cause Analysis

First-party plugins ship as optional JARs under browser4-plugins/*/target and are not part of the runtime bundle, so a fresh checkout has none of the markdown tools; the discovery chain for them (plugin list / plugin / GET /mcp/tools) exists but is documented only in the general help, not in the SKILL.md that agents actually read. SKILL.md's §7 Reference Map has no entry for plugins or markdown tools.

#### Code Pointer

`skills/browser4-cli/SKILL.md (§3 Command Map / §8 Installation) and cli/browser4-cli help text`

#### AI Suggested Improvement

- Add a short 'Optional first-party plugins' subsection to SKILL.md that shows `plugin list`, the `browser4-plugins/<name>/target/*.jar` path, `plugin install`, and the mandatory `stop` restart, using browser4-markdown as the example.
- Make `help tool` list the currently available plugin tools (it is the command users reach for when they want to call `markdown.convert`).
- Consider bundling the first-party plugins in the dev-mode runtime bundle so a checkout's tools match its source tree.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: markdown.convert's linkCount is the page's internal-link count, not the number of links in the generated file

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s <s> goto https://en.wikipedia.org/wiki/Web_scraping
./b4w.ps1 -s <s> tool call markdown_convert --json '{"outputPath":"<dir>"}'
# -> "linkCount":225 ; grep -c '](http' <dir>/Web-scraping-Wikipedia.md -> 336 markdown links (67 external)
goto https://en.wikipedia.org/wiki/Data_scraping ; convert -> "linkCount":360 while the file holds 163 links
discoverLinks on each page -> internalLinks 225 and 360 (exact match with linkCount)

#### Expected Behavior

A field named linkCount in a conversion result should describe the produced document (as imageCount does), or be named/documented as internalLinkCount with the same semantics as markdown.discoverLinks.internalLinks.

#### Actual Behavior

linkCount equals the page-wide count of same-host links (fragment-stripped, deduplicated) and has no relation to the file: 225 vs 336 links actually present, and the shorter Data_scraping page reports a *larger* number (360) than the longer Web_scraping page. The README documents the return map `{filePath, title, url, charCount, linkCount, imageCount, durationMs}` without defining linkCount.

#### Root Cause Analysis

MarkdownConverter.convert() sets `internalLinkCount = internalUrls.size` where internalUrls come from LINK_DISCOVERY_SCRIPT filtered by isInternal and deduplicated on the fragment-stripped URL, while imageCount comes from countMarkdownImages(rawMarkdown) — two different sources with confusingly parallel names in one result object.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/MarkdownConverter.kt (convert(), return MarkdownResult) and MarkdownToolExecutor.kt:56 (ToolSpec "convert")`

#### AI Suggested Improvement

- Rename the field to internalLinkCount (matching discoverLinks) or add markdownLinkCount counted from the produced markdown.
- Document each returned field in the README's return list, including that discoverLinks' counts are page-wide while convert's counts are internal-only.
- Emit both numbers so a caller can tell 'links on the page' from 'links in the file'.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: markdown.crawl summary is self-contradictory: pagesCrawled includes failed pages, so the counts exceed the pages attempted

**Severity:** Medium
**Category:** Product

#### Reproduction

goto https://httpbin.org/ then:
./b4w.ps1 -s <s> tool call markdown_crawl --json '{"maxDepth":1,"maxPages":3,"outputPath":"<dir>"}'
-> {"pagesCrawled":2,"pagesFailed":1,...,"pageResults":[<root, success:true>,<forms/post, success:false>]}

#### Expected Behavior

pagesCrawled should count pages that succeeded (1 here) and pagesAttempted/pagesFailed the rest, so pagesCrawled + pagesFailed == pagesAttempted == pageResults.length. The task's acceptance check ('at least 1 page crawled successfully') should be readable straight off the summary.

#### Actual Behavior

pagesCrawled(2) + pagesFailed(1) = 3 for 2 pages attempted, with pageResults.length = 2. pagesCrawled counts every attempted page, including the one reported as failed; only 1 .md file was written to disk.

#### Root Cause Analysis

SiteCrawler.crawl() returns `pagesCrawled = results.size` (the full attempt list) while `pagesFailed = failed` counts failures from that same list — the two fields overlap instead of partitioning.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/SiteCrawler.kt:281-287 (CrawlSummary construction; log line at :278)`

#### AI Suggested Improvement

- Set pagesCrawled = results.count { it.success } (or add pagesAttempted) so the counters partition.
- Log the same numbers that are returned, and include the skipped/queued-duplicate count so an early stop (e.g. only 2 links discovered) is explainable.
- Add charCount to CrawlPageResult so a caller can tell a stub page from a real one.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: markdown.crawl hijacks the session's active tab and leaves it on the last crawled page

**Severity:** Medium
**Category:** UX

#### Reproduction

goto https://httpbin.org/
./b4w.ps1 -s <s> tool call markdown_crawl --json '{"maxDepth":1,"maxPages":3}'
./b4w.ps1 -s <s> page-info
-> URL: https://httpbin.org/forms/post   (the user's page is gone)

#### Expected Behavior

A crawl whose contract is 'convert pages to files' should either restore the original page, run in its own tab/context, or warn in the result that the session's active tab was navigated. The CLI docs should state the side effect (the SKILL.md 'Critical Warnings' section covers refs and wait semantics but not this).

#### Actual Behavior

The crawl drives the shared session tab page by page and stops on the last visited URL; nothing in stdout, stderr, or the JSON result mentions that the page under the user was navigated away. Any refs or in-flight work on the previous page are silently invalidated.

#### Root Cause Analysis

SiteCrawler navigates the WebDriver receiver it is given (the session's active tab) for every URL and never records/restores the entry URL. The README documents this as 'Crawl the current site starting from the current page' but not as 'and leave the browser there'.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/SiteCrawler.kt (crawl loop navigation) and MarkdownToolExecutor.kt (:88 ToolSpec "crawl")`

#### AI Suggested Improvement

- Capture the entry URL before crawling and navigate back when the crawl finishes (or open a dedicated tab for the crawl and close it).
- If restoring is not wanted, return entryUrl/finalUrl in CrawlSummary and print a one-line stderr note that the active tab moved.
- Document the side effect in the plugin README's markdown.crawl section and in the tool's help text.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Fragment-only anchors are not filtered by link discovery and collapse into the page's own URL

**Severity:** Medium
**Category:** Product

#### Reproduction

goto https://httpbin.org/   (Swagger/Flasgger index: 11 anchors of the form '#/HTTP Methods')
./b4w.ps1 -s <s> eval "document.querySelectorAll('a[href]').length"         -> 16
./b4w.ps1 -s <s> tool call markdown_discover_links                          -> totalLinks 5, internalLinks 2
internalUrls -> ["https://httpbin.org/", "https://httpbin.org/forms/post"]

#### Expected Behavior

In-page fragment links (#section, #/route) are navigation within the current document, not links to other pages. They should either be excluded from the link model or reported as a separate fragment/anchor count, and the current page's own URL should not be listed as a discovered internal link (it inflates totalLinksDiscovered and is re-offered to the crawler).

#### Actual Behavior

Only the literal href '#' is skipped. All other fragment hrefs resolve, then have their fragment stripped for deduplication ('resolvedUrl.split("#")[0]'), so 11 distinct fragment anchors collapse onto https://httpbin.org/ and the page itself appears as its own first internal link. Reported totalLinks is 5 where the DOM has 16 anchors; the crawl's totalLinksDiscovered (2) is dominated by the self-link.

#### Root Cause Analysis

MarkdownConverter.LINK_DISCOVERY_SCRIPT filters `href === '#'` but not `href.startsWith('#')`, and deduplicates on the fragment-stripped URL, which is exactly the URL of the current page for every in-page anchor. The same fragment hrefs then render as plain text in the markdown output (e.g. '#### HTTP MethodsTesting different HTTP verbs'), silently losing the anchors.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/MarkdownConverter.kt (LINK_DISCOVERY_SCRIPT, the `href === '#'` guard and `cleanUrl` dedup)`

#### AI Suggested Improvement

- Skip fragment-only hrefs (`href.startsWith('#')`) or count them separately as anchors, keeping them out of totalLinks/internalLinks and out of `internalUrls`.
- Never return the current page URL in internalUrls (skip when cleanUrl === page URL without fragment).
- Keep fragments when deduplicating distinct targets, or expose `anchorCount` so a caller can reconcile reported links with the DOM.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: tool call exits 0 when the tool itself reports success:false

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s <s> tool call markdown_fetch --json '{"url":"https://httpbin.org/status/404"}'
echo $?   -> 0 ; stderr empty ; stdout {"url":"...","linkCount":0,"success":false,"error":"HTTP 404: "}

#### Expected Behavior

A tool-reported failure should surface as a non-zero exit (or at minimum a stderr warning naming the failure), so shell scripts and agents can branch on it without parsing stdout. The docs for `tool call` should state the contract either way.

#### Actual Behavior

Exit status is 0 and stderr is empty; the only failure signal is the `success:false`/`error` fields inside the JSON. A caller that checks $? — the natural habit after the CLI's own `wait`/assertion guidance about exit codes — treats a failed fetch as a success. The error text is also truncated ('HTTP 404: ' with an empty reason phrase).

#### Root Cause Analysis

The generic MCP passthrough prints the tool result verbatim and never maps a result-level failure flag onto the process exit code. Plugin tools such as markdown.fetch/crawl report failures inside their payload (success:false / pagesFailed) rather than as transport errors, so the CLI has nothing to raise.

#### Code Pointer

`cli/browser4-cli/src/ (tool call / MCP passthrough command; plugin call path) — plugin side: browser4-plugins/browser4-markdown/.../MarkdownToolExecutor.kt:364 (fetch branch)`

#### AI Suggested Improvement

- Add an opt-in flag (e.g. `tool call --fail-on-error`) that exits non-zero when the result carries success:false or a non-empty error, and document it in `--help tool`.
- Print a one-line stderr note ('markdown.fetch failed: HTTP 404') even when exiting 0.
- Include the HTTP status/reason in the error string (currently 'HTTP 404: ' — the reason phrase is empty).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: `plugin <domain> --help` prints a contradictory error and dumps the whole command reference

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 plugin markdown --help
-> "Plugin tool domain 'markdown' is not a static CLI command."
   "Run `browser4-cli plugin` to list available plugin domains and tools,"
   "Unknown command: plugin-markdown"
   + the entire ~350-line general help
(but `./b4w.ps1 plugin markdown convert --outputPath <dir>` works fine)

#### Expected Behavior

`plugin markdown --help` should list the domain's methods with their arguments (the same information README summarizes), or a single concise 'unknown subcommand' error — not a 350-line help dump. The `plugin` listing should show the real tool names.

#### Actual Behavior

Three different messages are emitted (domain-not-a-static-command, a hint, and 'Unknown command: plugin-markdown'), followed by the full general help, which buries the actual information. Additionally `./b4w.ps1 plugin` prints `plugin markdown`, `plugin markdown_crawl`, `plugin markdown_discover` — the last is a truncated non-existent name (the tool is markdown_discover_links) and markdown_crawl is shown as a domain rather than a method of `markdown`, so the listing cannot be used to find methods.

#### Root Cause Analysis

The CLI resolves `plugin <name>` into a synthetic `plugin-<name>` command for --help purposes and falls back to the top-level help when that fails, while the domain list is derived by splitting tool names at an underscore that also occurs inside method names (markdown_discover_links -> markdown_discover).

#### Code Pointer

`cli/browser4-cli/src/ (plugin command dispatch, plugin domain/tool listing and its --help fallback)`

#### AI Suggested Improvement

- Make `plugin <domain> --help` print the domain's methods, their argument names/defaults and the `plugin <domain> <method> --flag` form, using the ToolSpec data already present server-side.
- Drop the 'Unknown command: plugin-<name>' line and the full help dump for that specific case.
- Derive the listing from the domain map instead of string-splitting tool names, so it shows `plugin markdown convert|crawl|crawlFrom|fetch|discoverLinks|read`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: markdown.fetch omits title and skips YAML front matter for pages without a <title>, and uses a different filename scheme than convert

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s <s> tool call markdown_fetch --json '{"url":"https://httpbin.org/html","outputPath":"<dir>"}'
-> {"filePath":"<dir>\\httpbin.org_html.md","url":"https://httpbin.org/html","linkCount":0,"success":true}
head -3 <dir>/httpbin.org_html.md -> starts at '# Herman Melville - Moby-Dick' (no front matter)

#### Expected Behavior

The README documents the fetch return as `{filePath, title, url, linkCount, success, error}`; a caller should get a title (the document has a clear H1) and the same front-matter/naming conventions that markdown.convert produces, so the two paths' outputs are interchangeable.

#### Actual Behavior

No `title` key appears in the response at all (null is dropped by serialization), and the file carries no YAML front matter although markdown.output.include-front-matter defaults to true. The file is named from the URL (httpbin.org_html.md) whereas convert names files from the title (Web-scraping-Wikipedia.md).

#### Root Cause Analysis

The HTTP path (SiteCrawler.htmlToMarkdown) derives the title from Jsoup's doc.title() only; httpbin.org/html has no <title> element, so both the front matter block and the reported title are skipped, while the body <h1> still becomes the markdown H1. Filename generation falls back to url-minus-scheme in that case.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/SiteCrawler.kt:381-400 (htmlToMarkdown front matter) and :302-360 (fetchAndConvert title/uniquePath); MarkdownToolExecutor.kt:364 (fetch return map)`

#### AI Suggested Improvement

- Fall back to the first <h1> (then og:title) when <title> is absent, so title and front matter are still produced.
- Keep the key present in the JSON with an empty string instead of relying on null-omission, matching the README's documented return shape.
- Align filename generation across convert/fetch (title-first, URL as fallback) so the two output trees look the same.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: plugin list reports the manifest version (4.12.0-rc.1) for a 4.14.1-SNAPSHOT JAR, obscuring which plugin build is loaded

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 plugin list
-> browser4-markdown-4.14.1-SNAPSHOT.jar  v4.12.0-rc.1  sdk 4.14.1-SNAPSHOT loaded
cat browser4-plugins/browser4-markdown/src/main/resources/META-INF/browser4-plugin.json -> "version": "4.12.0-rc.1"

#### Expected Behavior

The version column should identify the build actually loaded (the JAR version), or the manifest version should be maintained; at minimum the display should make clear which one it is.

#### Actual Behavior

Two version strings disagree (JAR 4.14.1-SNAPSHOT vs manifest 4.12.0-rc.1) and the stale manifest value is the one shown; the same pattern appears for browser4-images. A user verifying 'did my rebuilt plugin load?' cannot tell from this output.

#### Root Cause Analysis

browser4-plugin.json carries a hand-maintained version field that is not synchronized with the Maven project version at build time, and `plugin list` renders the manifest value rather than the JAR file name/version.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/resources/META-INF/browser4-plugin.json (version field; build-time filtering) and the `plugin list` renderer in cli/browser4-cli/src/`

#### AI Suggested Improvement

- Filter the manifest version from ${project.version} at build time (the sdkVersion field already uses that mechanism).
- Show both in `plugin list` (JAR version first, manifest version as a secondary column) so a stale manifest is visible instead of misleading.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 7 requested steps completed and verified. Conversion quality on Wikipedia is high (front matter, H1, tables, 336 preserved links) and both browser and HTTP paths worked on the first attempt; the only prerequisite was installing the plugin, which the agent-facing skill never mentions.

**Success Rate:** 95% — every task step produced a valid result; the deduction is for the undocumented install/restart prerequisite and the crawl summary needing manual interpretation (pagesCrawled/pagesFailed/pageResults disagree).

**Issues Found:** 9

**Major Blockers:** None. The only real obstacle was that the browser4-markdown plugin is not installed by default and SKILL.md (the document a first-time user is told to read completely) never mentions plugins or the markdown tools — recovery required searching browser4-plugins/*/target for the JAR, then `plugin install` plus `stop` to reload the backend.

**Most Confusing Aspects:** 1) Discovering the plugin tools at all: no `markdown` command in help, `help tool` is one line, `plugin` prints truncated domain names (`markdown_discover` is not a real tool), and `plugin markdown --help` answers with an error plus the entire 350-line help. 2) Trusting the numbers: convert's linkCount (225) contradicted both the file's link count (336) and discoverLinks' totalLinks (324), and the crawl summary's pagesCrawled + pagesFailed exceeded the number of pages actually attempted. 3) Not knowing that markdown.crawl navigates (and abandons) the session's tab — discovered only by running page-info afterwards.

**Most Valuable Improvements:** Document and (ideally) preinstall the first-party plugins so `markdown.*` tools are reachable from SKILL.md; make result counters self-consistent and self-describing (internalLinkCount vs markdown link count, pagesAttempted/Succeeded/Failed, charCount per crawled page); filter fragment-only anchors and never emit the current page URL as one of its own internal links; and give `tool call` a documented failure exit path so agents can rely on exit codes instead of parsing payloads.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: browser4-markdown plugin is not installed by default and the agent skill never mentions it

./b4w.ps1 plugin list   # -> 3 plugins: captcha, images, swarm (no markdown)
./b4w.ps1 help | grep -i markdown   # -> only a 'scrape --formats ... markdown' mention
# SKILL.md has no occurrence of 'markdown.convert|markdown.crawl|markdown_fetch|plugin install'

#### Issue 2: markdown.convert's linkCount is the page's internal-link count, not the number of links in the generated file

./b4w.ps1 -s <s> goto https://en.wikipedia.org/wiki/Web_scraping
./b4w.ps1 -s <s> tool call markdown_convert --json '{"outputPath":"<dir>"}'
# -> "linkCount":225 ; grep -c '](http' <dir>/Web-scraping-Wikipedia.md -> 336 markdown links (67 external)
goto https://en.wikipedia.org/wiki/Data_scraping ; convert -> "linkCount":360 while the file holds 163 links
discoverLinks on each page -> internalLinks 225 and 360 (exact match with linkCount)

#### Issue 3: markdown.crawl summary is self-contradictory: pagesCrawled includes failed pages, so the counts exceed the pages attempted

goto https://httpbin.org/ then:
./b4w.ps1 -s <s> tool call markdown_crawl --json '{"maxDepth":1,"maxPages":3,"outputPath":"<dir>"}'
-> {"pagesCrawled":2,"pagesFailed":1,...,"pageResults":[<root, success:true>,<forms/post, success:false>]}

#### Issue 4: markdown.crawl hijacks the session's active tab and leaves it on the last crawled page

goto https://httpbin.org/
./b4w.ps1 -s <s> tool call markdown_crawl --json '{"maxDepth":1,"maxPages":3}'
./b4w.ps1 -s <s> page-info
-> URL: https://httpbin.org/forms/post   (the user's page is gone)

#### Issue 5: Fragment-only anchors are not filtered by link discovery and collapse into the page's own URL

goto https://httpbin.org/   (Swagger/Flasgger index: 11 anchors of the form '#/HTTP Methods')
./b4w.ps1 -s <s> eval "document.querySelectorAll('a[href]').length"         -> 16
./b4w.ps1 -s <s> tool call markdown_discover_links                          -> totalLinks 5, internalLinks 2
internalUrls -> ["https://httpbin.org/", "https://httpbin.org/forms/post"]

#### Issue 6: tool call exits 0 when the tool itself reports success:false

./b4w.ps1 -s <s> tool call markdown_fetch --json '{"url":"https://httpbin.org/status/404"}'
echo $?   -> 0 ; stderr empty ; stdout {"url":"...","linkCount":0,"success":false,"error":"HTTP 404: "}

#### Issue 7: `plugin <domain> --help` prints a contradictory error and dumps the whole command reference

./b4w.ps1 plugin markdown --help
-> "Plugin tool domain 'markdown' is not a static CLI command."
   "Run `browser4-cli plugin` to list available plugin domains and tools,"
   "Unknown command: plugin-markdown"
   + the entire ~350-line general help
(but `./b4w.ps1 plugin markdown convert --outputPath <dir>` works fine)

#### Issue 8: markdown.fetch omits title and skips YAML front matter for pages without a <title>, and uses a different filename scheme than convert

./b4w.ps1 -s <s> tool call markdown_fetch --json '{"url":"https://httpbin.org/html","outputPath":"<dir>"}'
-> {"filePath":"<dir>\\httpbin.org_html.md","url":"https://httpbin.org/html","linkCount":0,"success":true}
head -3 <dir>/httpbin.org_html.md -> starts at '# Herman Melville - Moby-Dick' (no front matter)

#### Issue 9: plugin list reports the manifest version (4.12.0-rc.1) for a 4.14.1-SNAPSHOT JAR, obscuring which plugin build is loaded

./b4w.ps1 plugin list
-> browser4-markdown-4.14.1-SNAPSHOT.jar  v4.12.0-rc.1  sdk 4.14.1-SNAPSHOT loaded
cat browser4-plugins/browser4-markdown/src/main/resources/META-INF/browser4-plugin.json -> "version": "4.12.0-rc.1"

