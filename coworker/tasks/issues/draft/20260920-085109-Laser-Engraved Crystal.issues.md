# Issues: Laser-Engraved Crystal

> **Source:** `20260920-085109-Laser-Engraved Crystal.full.md` | **Date:** 20260920-085109 | **Mode:** dev

## Scenario Background

### Task

**Search:** `Laser-Engraved Crystal` on Amazon.com → 2,000+ results, page 1 of 48 items extracted.

**Shortlist (10 best as a gift for a 12-year-old boy):**

| # | ASIN | Product | Price | Rating | Reviews |
|---|------|---------|-------|--------|---------|
| 1 | B0BGH5L5FX | 3D Engraved **Eagle** Crystal Ball Paperweight w/ stand 60mm | $9.99 | 4.8 | 1,101 |
| 2 | B0BGH6CKDM | 3D Laser Engraved **Sea Turtle** Crystal Ball 60mm | $9.99 | 4.7 | 1,113 |
| 3 | B0CY5MB4HL | 3D Laser Etched **Galaxy / Milky Way** Crystal Ball, K9 | $9.99 | 4.6 | 304 |
| 4 | B0BVLT5D2F | 3D Laser Engraved **Constellation** Crystal Ball 60mm | $9.99 | 4.6 | 966 |
| 5 | B0CPYHCQ4S | 3D **Solar System** Crystal Ball 2.4in, USB LED base | $11.99 | 4.4 | 677 |
| 6 | B0GDQ8MLMH | **Crystal Cloud Rain** Cube, 3D laser-engraved paperweight | $19.95 | 4.8 | 15 |
| 7 | B0GYZ1F3HK | 3D Crystal Cube **Butterflies** + colour-changing LED base | $19.99 | 4.6 | 8 |
| 8 | B0FT7GKMRZ | 60mm 3D Laser Engraved **Horse** Crystal Ball | $8.54 | 4.8 | 26 |
| 9 | B0GF689RP8 | 60mm 3D Laser Engraved **Cat** Crystal Ball | $7.21 | 5.0 | 6 |
| 10 | B0C3GG934P | **Personalized** 3D Crystal Photo Cube (custom photo) | $29.99 | 4.5 | 66 |

**Selection filter applied:** ~22 of the 48 results are raw *crystal blanks for laser-engraving machines* (supplies, not gifts — and they need a specialised sub-surface laser, not a hobby diode engraver), and 9 are explicitly gendered keepsakes ("for Women / Mom / Sister / Daughter", "40th Anniversary", "Christus statue", corporate "Employee Recognition" plaque). All were excluded as poor fits for a 12-year-old boy.

## 🏆 Best pick: **B0BGH5L5FX — 3D Engraved Eagle Crystal Ball Paperweight with Stand 60mm — $9.99**

- **Most credible quality signal of the set:** 4.8★ from 1,101 ratings — the highest rating among any listing with a large review base (*Cat* and *Horse* beat it on stars but have 6 and 26 reviews).
- **Age-appropriate, gender-neutral branding:** "Theme: Animals", occasions listed as Birthday/Christmas/Congratulations/New Year. No "for women/wife/mom" marketing — unlike the Solar System, Constellation, Butterflies and Photo Cube listings.
- **Immediately readable subject:** a spread-wing eagle is striking to that age group, and unlike the LED-base items it has no cables or batteries to fail.
- **Low risk / good value:** In Stock, $9.99, metal tripod stand included, #62 in Paperweights; 2.5×2.5×3in, 0.34 kg. Care instructions are just "Wipe Clean" — notably the *Solar System* ball carries a "Keep away from children" caution that is a genuine red flag for a child's gift.
- **Runners-up:** Sea Turtle (B0BGH6CKDM, 4.7★/1,113) if he prefers nature; Galaxy (B0CY5MB4HL, 4.6★/304) if he's into space.

### Execution Context

**Key Commands:**

**Steps:** verified repo root and scratch dir → `help` + full `SKILL.md` + the Amazon scenario reference → `open --headless "…/s?k=laser+engraved+crystal"` (first run auto-started daemon/backend; post-command snapshot warned it timed out after 10s, page was fine) → `page-info` confirmed the search URL → `htmlsnapshot summary` surfaced the page shape → `htmlsnapshot inspect` on `.s-result-item[data-component-type='s-search-result']` → validated selectors with `get all` → **X-SQL `htmlsnapshot query` timed out (120s), twice** → switched to a single `eval --file` pass producing 48 aligned rows → discovered titles were Chinese and prices CNY → root-caused to session cookies → fixed with `cookie-set i18n-prefs USD` + `cookie-set lc-main en_US` → re-extracted clean US data (48 rows, USD, 0 Chinese titles) → fetched 10 detail pages via a bash loop of `goto`+`eval --file` → compared and picked.

**Decisions & workarounds:**
1. **URL injection** for the search (documented — Amazon intercepts form submits), `amazon.com/s?k=…`.
2. **Abandoned the documented X-SQL path** after it timed out even on `example.com`; used `eval --file` with a JS extractor returning `JSON.stringify`, which yielded correctly *aligned* rows (title/price/rating/link per product) that `get all` cannot guarantee.
3. **Manually forced the US storefront** via cookies; `&language=en_US` in the URL had no effect.
4. **Fetched detail pages one ASIN per invocation** rather than in a `batch`, because a 2-product batch blew the 120s HTTP timeout. Each page still took ~97s to load.
5. Persisted every intermediate artefact under `.test-sessions/20260920T0355347004286Z/` (`q1.sql`, `extract_search.js`, `detail.js`, `search_results*.json`, `d_<ASIN>.json`, `fetch_details.sh`).

---

## Issues Found (10 issues)

### Issue 1: htmlsnapshot query (X-SQL) always times out — even on a trivial static page

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 -s gift-eval goto "https://example.com"
2) ./b4w.ps1 -s gift-eval htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(dom, 'h1') AS t FROM DOM_LOAD_AND_SELECT(@url, 'body')"
Also reproduced on the Amazon search page with the exact query from skills/browser4-cli/references/htmlsnapshot-scenarios-amazon.md §15d.

#### Expected Behavior

The query returns a resultSet with the page's h1 text (example.com) or the 48 product rows (Amazon).

#### Actual Behavior

Every query fails after 120s with statusCode 408: {"message":"X-SQL query timed out after 120s. The page may be too large or the session may be unresponsive.","pageContentBytes":0,"resultSet":null}. Wall-clock per attempt is 4m25s (the CLI retries), so this also burns 4+ minutes of user time per try. Identical failure on a tiny static page proves it is not page size or Amazon.

#### Root Cause Analysis

The server-side page loader that backs DOM_LOAD_AND_SELECT is returning pageContentBytes:0 and never completing. Because the failure is independent of page complexity, the backend's independent scrape/webdb load path is broken in this environment (not the SQL engine itself). Needs backend investigation — check the browser4-rest X-SQL executor and its page-fetch/webdb component, and why a 0-byte load neither errors nor falls back to the live tab DOM. Note the whole documented extraction decision tree (§4b) routes structured/multi-field extraction to this command.

#### Code Pointer

`browser4-rest — the htmlsnapshot/query tool handler behind MCPToolController, and the DOM_LOAD_AND_SELECT page-loader it delegates to; CLI side cli/browser4-cli/src/ (htmlsnapshot query passthrough)`

#### AI Suggested Improvement

- Reproduce on a trivial page first (example.com) to confirm it is page-independent, then fix the backend page-load path that produces pageContentBytes:0
- Make the live-tab-DOM fallback actually engage when the independent load yields 0 bytes, instead of waiting out the 120s deadline
- Return a distinct error status when the loader cannot fetch at all, rather than a generic 'page may be too large'
- Add an automated smoke test running one X-SQL query against a local fixture page so this cannot silently regress

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Failed X-SQL query exits 0 and prints irrelevant shell-quoting advice

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s gift-eval htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(dom, 'h1') AS t FROM DOM_LOAD_AND_SELECT(@url, 'body')"; echo "exit=$?"

#### Expected Behavior

Non-zero exit code on a 408 timeout so scripts and CI can detect the failure; and if guidance is printed it should address the actual error.

#### Actual Behavior

exit=0 despite a 408 Request Timeout and resultSet:null. The CLI additionally prints a tip about shell quoting ('Use --sql @file.sql to avoid shell quoting issues… double quotes are interpreted as SQL identifiers') — advice unrelated to a server timeout, which sent me chasing a quoting problem that did not exist.

#### Root Cause Analysis

The CLI maps a server error envelope for this command to a success exit status, and the post-command tip is selected statically rather than from the failure class. The X-SQL docs state exit is non-zero on a server error envelope (417/5xx) — a 408 appears to fall outside that check.

#### Code Pointer

`cli/browser4-cli/src/ — the `htmlsnapshot query` subcommand's exit-status mapping and tip selection`

#### AI Suggested Improvement

- Treat any non-2xx statusCode (including 408) as a failing exit code
- Select tips from the actual error class, and suppress tips entirely on failure
- Surface the server's `message` field prominently and stop printing the server payload only as raw JSON

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Browser locale/geo is not controllable — Amazon.com served as a Chinese storefront with CNY prices and machine-translated titles

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s gift-eval open --headless "https://www.amazon.com/s?k=laser+engraved+crystal"
./b4w.ps1 -s gift-eval eval "JSON.stringify({lang:navigator.language, tz:Intl.DateTimeFormat().resolvedOptions().timeZone})"
./b4w.ps1 -s gift-eval htmlsnapshot get all text "span.a-offscreen" --limit 5

#### Expected Behavior

A US-storefront search results page with English titles and USD prices — or a documented way to request one.

#### Actual Behavior

browser reports lang=zh-CN, tz=Asia/Shanghai; Amazon sets i18n-prefs=CNY and lc-main=zh_CN, so the page renders in Chinese with prices like "CNY 361.45" and 39 of 49 product titles machine-translated (e.g. "6 件装 K9 水晶坯料,1.97 x 1.97 x 3.15 英寸适用于 2D/3D 激光雕刻"). Adding &language=en_US to the URL had no effect. Only manually overwriting both cookies fixed it: `cookie-set i18n-prefs USD` + `cookie-set lc-main en_US`. No CLI flag (locale, accept-language, country, currency) is documented for controlling this.

#### Root Cause Analysis

The managed Chrome profile inherits the host OS locale/timezone, and nothing in the session-creation path normalises Accept-Language or exposes a locale override. Extraction pipelines silently inherit whatever locale the host machine has, so results are neither reproducible across machines nor comparable between runs.

#### Code Pointer

`cli/browser4-cli/src/ — session/open option handling (no locale/accept-language option); browser4-core PulsarWebDriver session creation where the browser profile is launched`

#### AI Suggested Improvement

- Add a documented --locale / --accept-language (and optionally --country / --currency) option to open/goto, applied at session creation
- Default the managed AI-agent profile to en-US so automation output is deterministic regardless of host locale
- Document the cookie workaround (i18n-prefs, lc-main) for sites that override Accept-Language server-side
- Warn on stderr when the resolved page locale differs from the requested one

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Documented Amazon selectors are stale — 'h2 a.a-link-normal' matches nothing

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s gift-eval htmlsnapshot get all text "h2 a.a-link-normal" --limit 5

#### Expected Behavior

Per skills/browser4-cli/references/htmlsnapshot-scenarios-amazon.md (Scenarios 15c/15d, 'Last verified 2026-07-10'), a JSON array of ~48 product titles.

#### Actual Behavior

[] with 'No elements matched'. The AX tree shows Amazon now nests the heading INSIDE the anchor (link "…" > heading [level=2]), so the documented h2-ancestor selector cannot match. Working selectors are 'h2.a-size-base-plus' and 'a.a-link-normal.s-line-clamp-3'.

#### Root Cause Analysis

Amazon changed its result-card markup after the scenario doc's verification date (a → h2 inversion). The doc's own warning ('always run inspect first') is correct but the copy-paste selectors in §15c/15d/16c are now wrong, and §15d's X-SQL query is doubly unusable because X-SQL itself times out.

#### Code Pointer

`skills/browser4-cli/references/htmlsnapshot-scenarios-amazon.md (Scenarios 15c, 15d, 16c)`

#### AI Suggested Improvement

- Update the §15c/15d selectors to 'h2.a-size-base-plus' and 'a.a-link-normal.s-line-clamp-3'
- Make the scenario's last-verified date and stale-selector risk more prominent, with a one-line 'verify first' command at the top of each scenario
- Add a cheap self-check command (e.g. htmlsnapshot grep) users can run to confirm a documented selector still matches before relying on it

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: goto to an Amazon product page blocks ~97 seconds with no progress feedback

**Severity:** Medium
**Category:** UX

#### Reproduction

time ./b4w.ps1 -s gift-eval goto "https://www.amazon.com/dp/B0BGH5L5FX"

#### Expected Behavior

Navigation returns promptly once the document is interactive, or prints progress/elapsed feedback while it waits.

#### Actual Behavior

real 1m37s for the goto alone (a full 10-product review therefore takes ~17 minutes). eval on the already-loaded page takes 4.8s. No output at all until 'Page loaded.' appears, so the user cannot tell a slow load from a hang.

#### Root Cause Analysis

goto waits for a full page load and Amazon's product pages carry very heavy third-party/ad/script payloads; from this host the round trip is also long. There is no progress indicator on a blocking navigation and no documented load-strategy option to wait for something cheaper (the SKILL mentions -njr load options for capture, not for goto).

#### Code Pointer

`cli/browser4-cli/src/ — the goto/navigate subcommand's wait strategy and output loop`

#### AI Suggested Improvement

- Print a progress line (or elapsed time) while the load is pending so a slow page is distinguishable from a hang
- Expose a documented load strategy (e.g. --load domcontentloaded / networkidle) so bulk detail-page work is not forced to wait for full load
- Document the realistic per-page cost for heavy sites so users size their loops correctly

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: A failed navigation yields valid-looking all-null JSON from eval instead of an error (silent failure)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run a goto that fails at the network level, then eval a DOM extractor on the result. Observed as: ./b4w.ps1 -s gift-eval goto "https://www.amazon.com/dp/B0C3GG934P" (transient failure) followed by ./b4w.ps1 -s gift-eval eval --file detail.js

#### Expected Behavior

Either the goto reports the navigation failure, or the subsequent eval errors clearly that the page is not a real document.

#### Actual Behavior

eval returned a syntactically valid JSON object with every field null and url "chrome-error://chromewebdata/". Nothing on stdout/stderr flagged a failure, so a script consuming the JSON sees 'success' with empty data. Re-running the goto succeeded and returned real data, confirming it was a transient network failure, not a bad ASIN.

#### Root Cause Analysis

The extractor ran against Chrome's error document, which is a legitimate but empty DOM, so the eval succeeds technically while being semantically useless. There is no guard for non-http(s) / error-page documents, and the goto failure was not surfaced to the caller. Note the htmlsnapshot docs already describe falling back when there is 'no usable live document (about:blank, non-http(s))' — that idea is not applied to eval.

#### Code Pointer

`cli/browser4-cli/src/ — eval command result handling; browser4-core PulsarWebDriver navigation/error-page detection and goto error reporting`

#### AI Suggested Improvement

- Detect chrome-error:// / about:blank / non-http(s) documents and fail the command with an explicit 'navigation failed' error
- Have goto surface a non-zero exit and a clear message when the document ends up as an error page
- Include a 'url' field sanity check in eval's contract so callers can assert the page they expected

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: htmlsnapshot inspect/summary suggest generic layout selectors instead of the semantic content selectors users need

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 -s gift-eval htmlsnapshot inspect ".s-result-item[data-component-type='s-search-result']"
./b4w.ps1 -s gift-eval htmlsnapshot summary

#### Expected Behavior

Inspect ranks the selectors that actually carry the data — product title, price, rating, review count, link — as it does in the documented Amazon scenario output.

#### Actual Behavior

The top 'Suggested selectors' are structural/geometric: div.sg-col-4-of-24.sg-col-4-of-12, div.sg-col-inner, div:expr(width>200 && height>500), span:expr(img>0) — none of which yields a title or a price. It also pushes a misleading diversion ('Visual geometry detection found a potentially better repeating pattern: "div" — 2918 occurrences'). summary's 'Suggested Commands' likewise point at whole-page anchors ('.s-main-slot … a') rather than the per-product h2/price/rating nodes. I only obtained usable selectors by inspecting a deeper container (div[data-cy='asin-faceout-container']).

#### Root Cause Analysis

The selector scorer appears to weight recurrence and bounding-box consistency over semantic value (text/attribute yield), so ubiquitous wrapper divs outscore h2.a-size-base-plus and span.a-offscreen. The 'better repeating pattern' hint keys on raw geometry, which on a card grid always points at 'div'.

#### Code Pointer

`browser4-core / browser4-rest — the htmlsnapshot inspect + summary selector-scoring implementation`

#### AI Suggested Improvement

- Rank candidate selectors by extracted content (has non-empty text or a meaningful attribute) as well as by recurrence, and demote bare-tag/geometry selectors
- Prefer selectors anchored on semantic tags that carry text (h1-h3, a, span with text) when scoring
- Suppress the 'better repeating pattern: div' hint when the match is a bare tag with thousands of hits, or require a minimum specificity
- Surface a 'deep container' suggestion so users drill one level further automatically

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Global -s flag after the subcommand produces a confusing parser error

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 open --headless "https://www.amazon.com/s?k=laser+engraved+crystal" -s gift-eval

#### Expected Behavior

Either accept -s in trailing position, or explain that global options must precede the command (as git does).

#### Actual Behavior

Error: error: unexpected positional arguments (this command accepts 1): ["-s"] — it reports -s as a positional argument and never says where it should go, nor that the session flag exists.

#### Root Cause Analysis

Option parsing is strict about positional order and the error path classifies the unknown flag as a positional. The usage line does document [-s <session>] before the command, so this is purely an error-message quality problem — the very first command a new user is likely to mistype.

#### Code Pointer

`cli/browser4-cli/src/ — argument parsing / error reporting`

#### AI Suggested Improvement

- Detect known global flags appearing after the subcommand and emit 'global options such as -s must come before the command, e.g. browser4-cli -s NAME <command>'
- Include a usage example in the error text for unrecognised dash-arguments
- Consider accepting trailing global flags for convenience

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: batch hits the 120s HTTP timeout with no hint that --timeout exists

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s gift-eval batch --json < batch2.json   (a 4-command batch: goto + eval for two Amazon product pages)

#### Expected Behavior

Either the batch completes, or the error points at the documented remedy (--timeout).

#### Actual Behavior

Error: HTTP request timed out [tool=command_batch, endpoint=http://localhost:18182/mcp/call-tool, timeout=120s, sessionId=…]: error sending request for url (…). The message is otherwise good and specific, but never mentions that --timeout <seconds> exists, so the natural next move (chaining many slow page loads — the documented reason to use batch) is a dead end for a new user.

#### Root Cause Analysis

The batch tool performs all sub-commands inside one HTTP request against a fixed default timeout, and the error template does not cross-reference the global --timeout option. Batch is explicitly recommended for 'fewer round-trips', which is exactly when per-request time grows.

#### Code Pointer

`cli/browser4-cli/src/ — batch subcommand error rendering; global --timeout plumbing`

#### AI Suggested Improvement

- Append a hint to timeout errors: 'Retry with --timeout <seconds> (current: 120)'
- Document a recommended batch size / per-command budget in `help batch`
- Consider streaming per-command results so a partially completed batch is not lost on timeout

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: First command emits a post-command snapshot timeout warning on a page that is actually fine

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s gift-eval open --headless "https://www.amazon.com/s?k=laser+engraved+crystal"

#### Expected Behavior

Either no warning, or a warning that distinguishes 'still loading' from 'failed'.

#### Actual Behavior

Warning: post-command snapshot timed out after 10s (page may still be initializing). — yet the next command (page-info) immediately reported the correct title and URL, so nothing was actually wrong.

#### Root Cause Analysis

The automatic post-command accessibility snapshot has a fixed 10s budget that is unrealistic for a heavy page on an already-cold first launch (daemon + backend + browser all starting at once). It is emitted before the caller can know the page is healthy, which trains users to ignore warnings.

#### Code Pointer

`cli/browser4-cli/src/ — automatic post-command snapshot scheduling and its timeout`

#### AI Suggested Improvement

- Raise or make configurable the auto-snapshot timeout, and skip the automatic snapshot on the initial open of a cold session
- Word the warning so it does not imply failure ('snapshot skipped: page still loading'), or suppress it when a later probe succeeds
- Document that --no-snapshot avoids the round-trip entirely

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 48 page-1 results were extracted, a 10-item shortlist built and filtered for a 12-year-old boy, all 10 detail pages reviewed, and a best option selected. The tool's headline structured-extraction feature (X-SQL htmlsnapshot query) was unusable, so the task was completed via the documented eval/get-all alternatives.

**Success Rate:** 75% — the task outcome was achieved, but the primary documented extraction path (X-SQL) failed 100% of the time, one required a manual locale workaround, one navigation failed transiently and silently, and per-page load time was ~97s.

**Issues Found:** 10

**Major Blockers:** X-SQL (htmlsnapshot query) timed out on every attempt — including on example.com — and returned exit code 0, so the documented multi-field/correlated extraction workflow and the Amazon scenario doc's §15d query were both dead ends. Needed a hand-written eval --file JS extractor instead. Second blocker: the browser session inherited host locale zh-CN/Asia-Shanghai, so Amazon.com served a Chinese storefront with CNY prices and 39/49 machine-translated titles; only manually overwriting the i18n-prefs and lc-main cookies produced usable US data.

**Most Confusing Aspects:** The failure messages actively mislead: a 408 server timeout is followed by advice about shell quoting and SQL single-vs-double quotes, which sent me debugging a quoting problem that did not exist. The bare 'null' printed by --format table on that same failure gave no clue at all, and exit code 0 meant nothing was detectably wrong to a script. Separately, htmlsnapshot inspect's top suggestions on a product grid are layout divs and PowerCSS geometry filters (div:expr(width>200 && height>500)) plus a '2938 occurrences of div' diversion — as a first-time user I had the documented selectors fail and the discovery tool point somewhere useless at the same time. A failed navigation silently producing a valid all-null JSON object is the kind of thing that quietly corrupts a whole run.

**Most Valuable Improvements:** Fix X-SQL, or make the live-tab-DOM fallback engage instead of waiting out a 120s deadline; make failures non-zero-exit with error-class-appropriate messages and no unrelated tips. Add a documented --locale/--accept-language (and country/currency) option defaulting to en-US so extraction is reproducible regardless of host locale, and document the cookie workaround for server-side locale overrides. Have inspect rank selectors by extracted content rather than recurrence so it surfaces h2/price/rating instead of wrapper divs. Add progress output and a cheaper load strategy to goto so heavy pages are not a 97-second silent block, and surface navigation failures rather than returning empty data.

**Usability Rating:** 4/10

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

#### Issue 1: htmlsnapshot query (X-SQL) always times out — even on a trivial static page

1) ./b4w.ps1 -s gift-eval goto "https://example.com"
2) ./b4w.ps1 -s gift-eval htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(dom, 'h1') AS t FROM DOM_LOAD_AND_SELECT(@url, 'body')"
Also reproduced on the Amazon search page with the exact query from skills/browser4-cli/references/htmlsnapshot-scenarios-amazon.md §15d.

#### Issue 2: Failed X-SQL query exits 0 and prints irrelevant shell-quoting advice

./b4w.ps1 -s gift-eval htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(dom, 'h1') AS t FROM DOM_LOAD_AND_SELECT(@url, 'body')"; echo "exit=$?"

#### Issue 3: Browser locale/geo is not controllable — Amazon.com served as a Chinese storefront with CNY prices and machine-translated titles

./b4w.ps1 -s gift-eval open --headless "https://www.amazon.com/s?k=laser+engraved+crystal"
./b4w.ps1 -s gift-eval eval "JSON.stringify({lang:navigator.language, tz:Intl.DateTimeFormat().resolvedOptions().timeZone})"
./b4w.ps1 -s gift-eval htmlsnapshot get all text "span.a-offscreen" --limit 5

#### Issue 4: Documented Amazon selectors are stale — 'h2 a.a-link-normal' matches nothing

./b4w.ps1 -s gift-eval htmlsnapshot get all text "h2 a.a-link-normal" --limit 5

#### Issue 5: goto to an Amazon product page blocks ~97 seconds with no progress feedback

time ./b4w.ps1 -s gift-eval goto "https://www.amazon.com/dp/B0BGH5L5FX"

#### Issue 6: A failed navigation yields valid-looking all-null JSON from eval instead of an error (silent failure)

Run a goto that fails at the network level, then eval a DOM extractor on the result. Observed as: ./b4w.ps1 -s gift-eval goto "https://www.amazon.com/dp/B0C3GG934P" (transient failure) followed by ./b4w.ps1 -s gift-eval eval --file detail.js

#### Issue 7: htmlsnapshot inspect/summary suggest generic layout selectors instead of the semantic content selectors users need

./b4w.ps1 -s gift-eval htmlsnapshot inspect ".s-result-item[data-component-type='s-search-result']"
./b4w.ps1 -s gift-eval htmlsnapshot summary

#### Issue 8: Global -s flag after the subcommand produces a confusing parser error

./b4w.ps1 open --headless "https://www.amazon.com/s?k=laser+engraved+crystal" -s gift-eval

#### Issue 9: batch hits the 120s HTTP timeout with no hint that --timeout exists

./b4w.ps1 -s gift-eval batch --json < batch2.json   (a 4-command batch: goto + eval for two Amazon product pages)

#### Issue 10: First command emits a post-command snapshot timeout warning on a page that is actually fine

./b4w.ps1 -s gift-eval open --headless "https://www.amazon.com/s?k=laser+engraved+crystal"

