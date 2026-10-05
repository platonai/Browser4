# Issues: plugin-markdown-conversion

> **Source:** `20261004-165857-plugin-markdown-conversion.full.md` | **Date:** 20261004-165857 | **Mode:** dev

## Scenario Background

### Task

All seven task steps were completed successfully against a locally built and installed `browser4-markdown` plugin (backend + CLI 4.13.27 from the repo, session `mdeval`).

**Step 1–2 — Convert `https://en.wikipedia.org/wiki/Web_scraping`:** `markdown.convert` returned `{filePath, title: "Web scraping - Wikipedia", url, charCount: 49931, linkCount: 225, imageCount: 9, durationMs: 116}`. The file exists (50,027 bytes, 200 lines), `charCount > 1000` ✓, `linkCount` and `imageCount` non-zero ✓, and the file contains the page title as an H1 (`# Web scraping - Wikipedia`) ✓. Caveat discovered during verification: `linkCount` is **internal links only** (the page has 324 links total), and `imageCount` counts every `<img>` in the document (including 5 header/footer logos) while **zero** `![...]` image markup appears in the output — all in-article images on this page live inside tables, and the converter's table branch swallows them.

**Step 3 — First 50 lines:** contain YAML front matter, the H1 title, well-formed paragraphs, links as `[text](url)`, and two Markdown tables (the maintenance-message boxes). At least one list also exists in the file (77 list items total, e.g. the reference list at line 160). Two systematic fidelity bugs were visible here: headings are emitted with **doubled levels** (`#### History` for an HTML `h2`, `######` for `h3`), and one reference line leaks a full CSS block from a `<style>` element into the Markdown.

**Step 4 — `markdown.discoverLinks`:** total **324** links = **225 internal** + **99 external**. Internal examples: `Main page` → `/wiki/Main_Page`, `Data scraping` → `/wiki/Data_scraping`, `Data extraction` → `/wiki/Data_extraction`, `World Wide Web`, `Hypertext Transfer Protocol`. External examples: `Donate` → `donate.wikimedia.org`, `العربية` → `ar.wikipedia.org`, `الدارجة` → `ary.wikipedia.org`, `Català` → `ca.wikipedia.org`.

**Step 5 — Convert `https://en.wikipedia.org/wiki/Data_scraping`:** produced a different, valid file `Data-scraping-Wikipedia.md` (23,757 bytes; `charCount` 23,715; `linkCount` 361; `imageCount` 9). This page has a figure image outside tables and the file contains the image — twice (duplicate emission, see Issue 7). Same doubled-heading bug.

**Step 6 — `markdown.fetch https://httpbin.org/html` (no browser):** `{filePath: .../httpbin.org_html.md, url, linkCount: 0, success: true}`. The file contains the full Moby-Dick passage as an H1 plus paragraph — direct HTTP + Jsoup path works with no JavaScript. No front matter because the page has no `<title>`; the documented `title` field is omitted from the response when empty.

**Step 7 — `markdown.crawl` from `https://httpbin.org/` (maxDepth 1, maxPages 3):** summary `pagesCrawled: 2, pagesFailed: 1, totalLinksDiscovered: 2, totalImages: 0`. One page was crawled successfully — `https://httpbin.org/` → `httpbin.org.md` (front matter + H1 + code blocks + list) — satisfying the requirement. `https://httpbin.org/forms/post` failed with `"No content extracted"` (the extractor has no handling for form-only pages). A second crawl run, issued from the page the first run left the browser on, reported `pagesCrawled: 1, pagesFailed: 1` and only the failure — i.e., `pagesCrawled` counts attempts including failures, and `crawl` starts from whatever page is currently loaded.

### Execution Context

**Setup / discovery**
1. `./b4w.ps1 help` and read `skills/browser4-cli/SKILL.md` — the skill has **no mention of `browser4-markdown`** or its tools.
2. `./b4w.ps1 plugin list` — only `browser4-captcha` and `browser4-images` were installed; markdown was missing.
3. Read `browser4-plugins/browser4-markdown/README.md` and the plugin manifest to learn the tools (`markdown.convert/crawl/crawlFrom/fetch/discoverLinks`) and parameters.
4. Found a prebuilt `browser4-markdown-4.13.27-SNAPSHOT.jar` in the local Maven repo (`D:\Users\pereg\.m2\...`, built 2026-10-04 22:55); verified no source file was newer, so it matched the checkout.
5. `./b4w.ps1 plugin install <jar>` → "Restart the application to activate." There is **no `restart` command**; used `./b4w.ps1 stop`, then `./b4w.ps1 plugin list` to...

(truncated — see full.md for complete trace)

---

## Issues Found (12 issues)

### Issue 1: Unknown plugin method silently executes the default tool

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s mdeval plugin-markdown convertx
(any misspelled/unknown method, e.g. `plugin-markdown status`)

#### Expected Behavior

An error listing the available methods, e.g. "Unknown method 'convertx'. Available: convert, crawl, crawlFrom, fetch, discoverLinks" (non-zero exit).

#### Actual Behavior

No error: the CLI silently falls back to the alphabetically first matching tool and runs markdown.convert for real. `plugin-markdown convertx` produced {"filePath":"...\httpbin.org_1.md","title":"httpbin.org",...}. A typo therefore performs a real, side-effecting conversion (file write) with no indication the wrong tool ran.

#### Root Cause Analysis

resolve_plugin_method() in cli/browser4-cli/src/main.rs looks up `{domain}_{camel_to_snake(candidate)}` in the server tool list; when the candidate is not found it executes the unconditional fallback `matching[0].to_string()` (alphabetically first tool) instead of returning a usage error.

#### Code Pointer

`cli/browser4-cli/src/main.rs:resolve_plugin_method() (fallback at line ~17517)`

#### AI Suggested Improvement

- Replace the `matching[0]` fallback with a usage error that lists the available methods for the domain.
- Only use the default-tool fallback when there is exactly one matching tool or when no positional method was supplied at all (documented behavior `plugin-<name>` invokes the default tool).
- Include the resolved tool name in the success output (e.g. `Running markdown.convert ...`) so a fallback is at least visible.
- Add a CLI test asserting that an unknown method exits non-zero and does not call any tool.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Markdown headings are emitted with doubled levels (h2 -> ####, h3 -> ######)

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Web_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
Open the generated Web-scraping-Wikipedia.md and inspect heading lines.

#### Expected Behavior

HTML `<h2>History</h2>` converts to `## History`, `<h3>` to `###`, i.e. a contiguous hierarchy under the `#` page title.

#### Actual Behavior

`# Web scraping - Wikipedia` is followed by `#### History` (h2), `###### Human copy-and-paste` (h3), etc. Levels 2 and 3 never appear; every heading is 2 levels deeper than the source. Reproduced on both Wikipedia pages and httpbin.org (h2 -> `####`, h4 -> `########`). Document outlines/TOC generators built from these files get the wrong hierarchy.

#### Root Cause Analysis

In MARKDOWN_EXTRACTION_SCRIPT, the heading branch computes `var level = parseInt(tag.charAt(1))` and then pushes `'##'.repeat(level)`, which doubles the level (h2 -> 4 hashes). The intended expression is `'#'.repeat(level)`.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/MarkdownConverter.kt:309 (MARKDOWN_EXTRACTION_SCRIPT heading branch)`

#### AI Suggested Improvement

- Change `'##'.repeat(level)` to `'#'.repeat(level)`.
- Add a unit test asserting h2 -> `## `, h3 -> `### `, h4 -> `#### ` against a small HTML fixture (the existing MarkdownConverterTest should be extended).
- Consider re-verifying that the H1 (page title) still does not duplicate an in-page h1.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Images inside tables are silently dropped from the Markdown output

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Web_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
python -c "import io;s=io.open('<output>.md',encoding='utf-8').read();print(s.count('!['))"  -> 0

#### Expected Behavior

Images referenced on the page are rendered as `![alt](src)` (or at minimum reported consistently with the imageCount metric).

#### Actual Behavior

Zero `![...]` in the file even though every in-article image on this page is inside a table: the maintenance-box tables show `| [](https://en.wikipedia.org/wiki/File:Question_book-new.svg) | ... |` — an empty-text link to the image file page, alt text lost. The `IMG` walker branch never runs for table descendants.

#### Root Cause Analysis

Two compounding causes in MARKDOWN_EXTRACTION_SCRIPT: (1) the TABLE branch consumes the table and then sibling-skips past all descendants (so nested `img` nodes are never visited by the TreeWalker's IMG branch), and (2) the table cell renderer uses inlineFormatting(), which handles STRONG/B/EM/I/CODE/A/BR but has no IMG case — an `<img>` wrapped in `<a class="mw-file-description">` therefore degrades to `[empty-text](href)`. Verified by instrumenting the exact probe: IMG_SEEN=0 on this page.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/MarkdownConverter.kt:348 (TABLE branch) and :243 (inlineFormatting)`

#### AI Suggested Improvement

- Add an IMG case to inlineFormatting(): `result += '![' + (alt||'image') + '](' + src + ')'`.
- In the table cell renderer, fall back to the image `src`/`alt` when a cell contains an img (so image tables keep content).
- Alternatively, don't skip table descendants in the walker; mark the table as emitted and let IMG nodes be walked, guarding against re-emitting table text.
- Add regression fixtures: an image in a table cell, and an `<a><img></a>` wrapper.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: imageCount counts every <img> in the document, so it does not describe the converted output

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Web_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
# result: imageCount=9, generated .md contains 0 images

#### Expected Behavior

A metric that reflects images actually converted into the Markdown (or at least images in the content area), so users can trust it as a verification signal.

#### Actual Behavior

imageCount=9 is `document.querySelectorAll('img').length`, which includes 3 header logos, 2 footer logos and other chrome. Combined with the table-image drop, the report claims 9 images while the file has 0 — the metric actively misleads verification (the task instructs users to check imageCount as a success criterion).

#### Root Cause Analysis

convert() computes imageCount independently via querySelectorAll('img') instead of counting `![](...)` emissions from the extraction script (or counting only images inside the extracted content).

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/MarkdownConverter.kt:128-133`

#### AI Suggested Improvement

- Have the extraction script return converted image count (e.g. return JSON {markdown, imageCount}) and use that value.
- Exclude non-content `<img>` (header/footer/nav) by the same exclusion rules used for extraction.
- Document the metric's semantics in the tool description/README.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Plugin not installed by default; no documented dev-mode build/install path and no `restart` command despite the activation instruction

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1) ./b4w.ps1 plugin list  -> markdown plugin absent
2) ./b4w.ps1 plugin install <jar> -> "Restart the application to activate."
3) ./b4w.ps1 help restart -> Unknown command: restart

#### Expected Behavior

Either the dev bundle ships browser4-markdown, or the plugin README/SKILL explains how to build and install it in a source checkout and how to restart the backend to activate it.

#### Actual Behavior

The plugin had to be discovered by browsing browser4-plugins/; the JAR had to be located in the local Maven repository (or built with Maven) and installed manually. The install message says "Restart the application" but no restart command exists; the working sequence (stop, then any command auto-starts) is undocumented. The main SKILL.md never mentions the markdown plugin or its tools.

#### Root Cause Analysis

Docs gap: browser4-plugins/browser4-markdown/README.md only shows Maven coordinates (Installation section), and skills/browser4-cli/SKILL.md lists no markdown tools. The activation message in plugin-install assumes an external restart mechanism.

#### Code Pointer

`browser4-plugins/browser4-markdown/README.md (Installation) and skills/browser4-cli/SKILL.md`

#### AI Suggested Improvement

- Add a "Development install" section to the plugin README: `mvn -pl browser4-plugins/browser4-markdown package`, `./b4w.ps1 plugin install browser4-plugins/browser4-markdown/target/browser4-markdown-<version>.jar`, `./b4w.ps1 stop` (next command auto-starts).
- Either add a `restart` command or change the install message to name the actual steps ("stop the server; it restarts automatically on the next command").
- Consider shipping browser4-markdown in the dev runtime bundle alongside captcha/images, or list bundled-vs-optional plugins in `plugin list`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Bare `plugin` command is documented in help but rejected by the CLI

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 plugin  ->  Error: Unsupported command form: plugin. Use 'browser4-cli plugin <subcommand>' instead.

#### Expected Behavior

Per `browser4-cli help` (Plugin tools section): "plugin — list all available plugin tool domains". Bare `plugin` should print the available plugin tool domains.

#### Actual Behavior

The command is rejected with a usage error before reaching the dynamic-plugin handler. This is the one discoverable way to enumerate plugin tools, and it does not work — users cannot list `plugin-markdown` methods from the CLI at all.

#### Root Cause Analysis

handle_command() rejects bare `plugin` via preferred_prefixed_group_form("plugin") == Some("plugin <subcommand>") before the plugin branch (let is_bare_plugin = command == "plugin"; handle_dynamic_plugin_command(domain="") ...) can run — that handler is dead code for this input.

#### Code Pointer

`cli/browser4-cli/src/main.rs:20287 (preferred_prefixed_group_form) vs :21697 (dead is_bare_plugin branch); help text in cli/browser4-cli/src/help.rs`

#### AI Suggested Improvement

- Exempt bare `plugin` from preferred_prefixed_group_form (or run the `plugin` group-form check only when a subcommand is present) so the documented listing works.
- Alternatively, change the help text to document what actually works today.
- Add a CLI test: bare `plugin` exits 0 and prints plugin domains.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Figure-wrapped images are emitted twice in the Markdown

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Data_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
# lines 29 and 32 of Data-scraping-Wikipedia.md contain the same image URL twice

#### Expected Behavior

One `![alt](src)` per image.

#### Actual Behavior

The same image appears twice: `![A screen fragment and a screen-scraping interface ...](...jpg)` and `![image](...same jpg)`. Instrumented probe on the live page: one IMG node (IMG_SEEN=1), emitted by both the FIGURE branch and the IMG branch (FIG_HIT=1, IMG_HIT=1, total `![` = 2).

#### Root Cause Analysis

The FIGURE branch emits the nested image and then calls `node = walker.nextNode(); continue;` — nextNode() descends into the figure's children, so the same `<img>` is visited again and emitted by the generic IMG branch (with alt defaulted to 'image').

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/MarkdownConverter.kt:442-459 (FIGURE) and :332 (IMG)`

#### AI Suggested Improvement

- After emitting a FIGURE, skip its subtree (e.g. remember the figure node and skip descendants, or mark the image element as consumed in a WeakSet checked by the IMG branch).
- Prefer the caption-derived alt text over the fallback 'image' in the IMG branch so duplicates are at least identical.
- Add a fixture test with `<figure><img><figcaption>` asserting exactly one image emission.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: CSS text from <style> elements leaks into Markdown when a parent is inline-formatted

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Web_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
# line 160 of Web-scraping-Wikipedia.md is a reference list item containing a full CSS block (.mw-parser-output cite.citation{...})

#### Expected Behavior

Excluded elements (style/script) never contribute text to the output, regardless of which code path renders their ancestor.

#### Actual Behavior

The generated file contains ~2.8 KB of raw CSS inline in a numbered reference entry, garbling the Markdown. The same page's other references are fine; only entries whose container has an embedded <style> leak.

#### Root Cause Analysis

isExcluded() prunes STYLE nodes only in the TreeWalker filter. List items are rendered via inlineFormatting(lis[k]), which recurses through all child elements and only special-cases STRONG/B/EM/I/CODE/A/BR — a STYLE (or SCRIPT) descendant falls to the generic branch and its text node is appended.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/MarkdownConverter.kt:243 (inlineFormatting) / :203 (isExcluded)`

#### AI Suggested Improvement

- Make inlineFormatting skip nodes matching the exclusion selectors (call isExcluded(child) per child, or handle STYLE/SCRIPT/NOSCRIPT explicitly by emitting nothing).
- Apply the same guard in the getText()/heading and blockquote paths.
- Add a fixture with `<li>text<style>css</style></li>` asserting the CSS is absent.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: CrawlSummary.pagesCrawled counts failed pages, making the summary ambiguous

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s mdeval goto "https://httpbin.org/"
./b4w.ps1 -s mdeval plugin-markdown crawl --maxDepth 1 --maxPages 3
# -> pagesCrawled=2, pagesFailed=1, pageResults has 1 success + 1 failure

#### Expected Behavior

Either pagesCrawled counts successful pages only (so successes = pagesCrawled - pagesFailed is unambiguous), or the field/docs make it explicit that it counts attempts.

#### Actual Behavior

pagesCrawled = results.size (includes failures). "pagesCrawled: 2, pagesFailed: 1" is readable as "2 successes and 1 failure" (3 attempts) when in fact only 1 page succeeded. The README documents only the field name, not its semantics.

#### Root Cause Analysis

SiteCrawler builds CrawlSummary with pagesCrawled = results.size, where `results` contains both successful and failed page results; pagesFailed is counted separately from the same list.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/SiteCrawler.kt:281-283`

#### AI Suggested Improvement

- Rename/split the metrics: pagesAttempted, pagesSucceeded, pagesFailed; or set pagesCrawled = results.count { it.success }.
- Document each field's meaning in the tool description and README.
- Update the tool-spec text shown by `plugin-markdown` help output accordingly.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: markdown.convert linkCount is internal-links-only and undocumented

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s mdeval plugin-markdown convert            # linkCount=225
./b4w.ps1 -s mdeval plugin-markdown discoverLinks        # totalLinks=324 (225 internal + 99 external)
# generated .md contains 333 "](http" link occurrences

#### Expected Behavior

The response field and README make the counting rules explicit (total links found? internal only? links written to the file?).

#### Actual Behavior

linkCount=225 silently equals the internal-link count; the page has 324 links and the generated Markdown contains 333 link occurrences. During verification this looks like inconsistent reporting until the source is read. The README's convert table just says `linkCount`.

#### Root Cause Analysis

MarkdownToolExecutor maps `"linkCount" to result.internalLinkCount` (MarkdownResult.internalLinkCount = distinct internal URLs), while discoverLinks reports total/internal/external separately and the markdown file counts every rendered anchor.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/tools/MarkdownToolExecutor.kt:193-240 (convert branch)`

#### AI Suggested Improvement

- Rename the field to internalLinkCount, or report `linkCount` (total) plus `internalLinkCount`/`externalLinkCount` consistently across convert and discoverLinks.
- Update the README's convert return description to define each field.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: markdown.fetch response omits the documented `title` field when empty

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s mdeval plugin-markdown fetch --url "https://httpbin.org/html"
# -> {"filePath":"...","url":"...","linkCount":0,"success":true}  (no "title", no "error")

#### Expected Behavior

README documents the fetch return as {filePath, title, url, linkCount, success, error}; consumers can rely on the keys being present.

#### Actual Behavior

`title` and `error` are missing from the response when empty (httpbin.org/html has no <title>), so scripts keying on those fields must handle absence. Same empty-field omission likely applies to convert's error field.

#### Root Cause Analysis

The tool map includes `"title" to result.title` (empty string here) but the MCP/serialization layer appears to drop empty strings before returning the payload (or the map is filtered); needs confirmation in the MCP result serializer.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/tools/MarkdownToolExecutor.kt:316-330 (fetch branch); see MCP result serialization for empty-value stripping`

#### AI Suggested Improvement

- Keep contract keys present with empty-string values (or document the omission).
- Derive a fallback title for fetch (hostname or first <h1>) so title is rarely empty.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: Form-only pages yield empty Markdown and are counted as crawl failures

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s mdeval goto "https://httpbin.org/"
./b4w.ps1 -s mdeval plugin-markdown crawl --maxDepth 1 --maxPages 3
# -> https://httpbin.org/forms/post: {"success":false,"error":"No content extracted"}

#### Expected Behavior

Either a reasonable Markdown capture of the page (title/headings/form field labels as text), or a failure message that explains why (e.g. "no prose content extractable — page is form-only").

#### Actual Behavior

The page fails with the generic "No content extracted"; the page does have an <h1>/<title> and structured form labels, all ignored because input/select/button/form are in the exclusion list and the probe only handles headings, paragraphs, images, tables, lists, code and blockquotes.

#### Root Cause Analysis

MARKDOWN_EXTRACTION_SCRIPT has no fallback for pages without matching content elements; SiteCrawler treats a blank result as a hard failure with a generic message.

#### Code Pointer

`browser4-plugins/browser4-markdown/src/main/kotlin/ai/platon/pulsar/markdown/service/MarkdownConverter.kt:MARKDOWN_EXTRACTION_SCRIPT; SiteCrawler.kt:194`

#### AI Suggested Improvement

- Add a fallback that emits the page title plus text content of body-formatted elements (or a plain text dump) when no structured elements match.
- Include the URL/title in the "No content extracted" error and distinguish "empty page" from "no extractable prose".

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

**Completion Status:** Successful — all 7 steps completed and their checks passed (file exists, charCount 49931 > 1000, linkCount 225 and imageCount 9 non-zero, H1 present, distinct second file, fetch success, crawl with 1+ successful page). Fidelity caveats found in the generated Markdown (doubled heading levels, dropped/duplicated images, one CSS leak) and several CLI/plugin UX issues.

**Success Rate:** 90% — every requested command executed and produced a verifiable result; the deducted share reflects output-fidelity defects and the manual plugin build/install/restart work the task did not anticipate.

**Major Blockers:** The browser4-markdown plugin was NOT installed in the dev runtime bundle; the JAR had to be located (or built) and installed with `plugin install`, then the backend manually stopped and restarted (there is no `restart` command, and the main SKILL.md never mentions this plugin).

**Most Confusing Aspects:** 1) No working way to list plugin tools (`plugin` is documented but rejected; unknown methods silently run the default tool, so typos are invisible). 2) Response-field semantics: linkCount is internal-only, imageCount counts page chrome rather than converted images, pagesCrawled counts failures. 3) crawl starts from and leaves the browser on whatever page is current, so a re-run behaves completely differently. 4) `/url`-less documentation: tool docs live only in the plugin README (Maven-coordinate install), not in the CLI help.

**Most Valuable Improvements:** 1) Fix the doubled heading levels (`'#'.repeat(level)`) — it corrupts every generated document's structure. 2) Emit images nested in tables/figures exactly once and make imageCount reflect converted images. 3) Make unknown plugin methods a hard error listing available methods. 4) Fix bare `plugin` (or the help text) so plugin tools can be discovered from the CLI. 5) Document the dev-mode build → plugin install → stop/auto-restart flow and consider shipping the markdown plugin in the dev bundle.

**Usability Rating:** 6/10

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

#### Issue 1: Unknown plugin method silently executes the default tool

./b4w.ps1 -s mdeval plugin-markdown convertx
(any misspelled/unknown method, e.g. `plugin-markdown status`)

#### Issue 2: Markdown headings are emitted with doubled levels (h2 -> ####, h3 -> ######)

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Web_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
Open the generated Web-scraping-Wikipedia.md and inspect heading lines.

#### Issue 3: Images inside tables are silently dropped from the Markdown output

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Web_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
python -c "import io;s=io.open('<output>.md',encoding='utf-8').read();print(s.count('!['))"  -> 0

#### Issue 4: imageCount counts every <img> in the document, so it does not describe the converted output

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Web_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
# result: imageCount=9, generated .md contains 0 images

#### Issue 5: Plugin not installed by default; no documented dev-mode build/install path and no `restart` command despite the activation instruction

1) ./b4w.ps1 plugin list  -> markdown plugin absent
2) ./b4w.ps1 plugin install <jar> -> "Restart the application to activate."
3) ./b4w.ps1 help restart -> Unknown command: restart

#### Issue 6: Bare `plugin` command is documented in help but rejected by the CLI

./b4w.ps1 plugin  ->  Error: Unsupported command form: plugin. Use 'browser4-cli plugin <subcommand>' instead.

#### Issue 7: Figure-wrapped images are emitted twice in the Markdown

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Data_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
# lines 29 and 32 of Data-scraping-Wikipedia.md contain the same image URL twice

#### Issue 8: CSS text from <style> elements leaks into Markdown when a parent is inline-formatted

./b4w.ps1 -s mdeval goto "https://en.wikipedia.org/wiki/Web_scraping"
./b4w.ps1 -s mdeval plugin-markdown convert
# line 160 of Web-scraping-Wikipedia.md is a reference list item containing a full CSS block (.mw-parser-output cite.citation{...})

#### Issue 9: CrawlSummary.pagesCrawled counts failed pages, making the summary ambiguous

./b4w.ps1 -s mdeval goto "https://httpbin.org/"
./b4w.ps1 -s mdeval plugin-markdown crawl --maxDepth 1 --maxPages 3
# -> pagesCrawled=2, pagesFailed=1, pageResults has 1 success + 1 failure

#### Issue 10: markdown.convert linkCount is internal-links-only and undocumented

./b4w.ps1 -s mdeval plugin-markdown convert            # linkCount=225
./b4w.ps1 -s mdeval plugin-markdown discoverLinks        # totalLinks=324 (225 internal + 99 external)
# generated .md contains 333 "](http" link occurrences

#### Issue 11: markdown.fetch response omits the documented `title` field when empty

./b4w.ps1 -s mdeval plugin-markdown fetch --url "https://httpbin.org/html"
# -> {"filePath":"...","url":"...","linkCount":0,"success":true}  (no "title", no "error")

#### Issue 12: Form-only pages yield empty Markdown and are counted as crawl failures

./b4w.ps1 -s mdeval goto "https://httpbin.org/"
./b4w.ps1 -s mdeval plugin-markdown crawl --maxDepth 1 --maxPages 3
# -> https://httpbin.org/forms/post: {"success":false,"error":"No content extracted"}

