# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20261015-121022-htmlsnapshot-inspect-discovery.issues.json` | **Date:** 20261015-121022 | **Mode:** dev

## Scenario Background

### Task

**Objective:** Use browser4-cli's `htmlsnapshot` family to reverse-engineer the structure of `http://books.toscrape.com/` (redirects to HTTPS), extract all book titles, then extract titles + prices with X-SQL.

**Outcome: Successful.** All 9 task steps completed on the first attempt. 20 books were found on the page, and all 20 titles + prices were extracted with zero missing/empty fields.

**Answer to "what repeats on this page?" (step 3):** the tool auto-discovered `article.product_pod` — 20 product cards in a 4-column grid (`ol.row`), plus secondary repeating groups: 51 sidebar `li` items (category list) and pagination. Suggested selectors for card children: `p.price_color`, `h3 a` (full title lives in the `a[title]` attribute — the visible text is clipped by the site itself), `p.instock.availability`, `button.btn.btn-primary`.

**Extracted data (X-SQL, 20 correlated rows, all fields populated):**

| # | Title | Price | Availability |
|---|-------|-------|--------------|
| 1 | A Light in the Attic | £51.77 | In stock |
| 2 | Tipping the Velvet | £53.74 | In stock |
| 3 | Soumission | £50.10 | In stock |
| 4 | Sharp Objects | £47.82 | In stock |
| 5 | Sapiens: A Brief History of Humankind | £54.23 | In stock |
| 6 | The Requiem Red | £22.65 | In stock |
| 7 | The Dirty Little Secrets of Getting Your Dream Job | £33.34 | In stock |
| 8 | The Coming Woman: A Novel Based on the Life of the Infamous Feminist, Victoria Woodhull | £17.93 | In stock |
| 9 | The Boys in the Boat: Nine Americans and Their Epic Quest for Gold at the 1936 Berlin Olympics | £22.60 | In stock |
| 10 | The Black Maria | £52.15 | In stock |
| 11 | Starving Hearts (Triangular Trade Trilogy, #1) | £13.99 | In stock |
| 12 | Shakespeare's Sonnets | £20.66 | In stock |
| 13 | Set Me Free | £17.46 | In stock |
| 14 | Scott Pilgrim's Precious Little Life (Scott Pilgrim #1) | £52.29 | In stock |
| 15 | Rip it Up and Start Again | £35.02 | In stock |
| 16 | Our Band Could Be Your Life: Scenes from the American Indie Underground, 1981-1991 | £57.25 | In stock |
| 17 | Olio | £23.88 | In stock |
| 18 | Mesaerion: The Best Science Fiction Stories 1800-1849 | £37.59 | In stock |
| 19 | Libertarianism for Beginners | £51.33 | In stock |
| 20 | It's Only the Himalayas | £45.17 | In stock |

**Artifacts** (all under `.test-sessions/20261015T0901431699046Z/`): `01-htmlsnapshot-capture.txt`, `02-inspect-autodiscover.txt`, `03-inspect-product_pod.txt`, `04-summary.txt`, `05-titles-h3a-title.txt`, `06-titles.json`, `07-grep-count.txt`, `08-books-query.sql`, `08-query-result.txt`, `08-query-result.json`, `08-books-titles-prices.csv`, `09-inspect-sidebar.txt`, `10-summary-suggested-selector.txt`, `11-depth1.txt`/`11-depth5.txt`, `12-cf-depth1.txt`/`12-cf-depth6.txt`, `page-export.html`, `make_csv.py`.

---

### Execution Context

**Preparation** — verified cwd `D:/workspace/Browser4/Browser4-4.14`, created the run scratch dir, ran `./b4w.ps1 help` (rich, well-organized, `★` marking high-frequency commands) and read `skills/browser4-cli/SKILL.md` plus `help htmlsnapshot`, `help htmlsnapshot inspect`, `help htmlsnapshot query`. Dev mode started the daemon/backend automatically; no manual setup was needed.

**Commands run (in order):**
1. `./b4w.ps1 -s books-eval open --headless "http://books.toscrape.com/"` → opened, redirected to `https://books.toscrape.com/`, "All products | Books to Scrape - Sandbox".
2. `./b4w.ps1 -s books-eval htmlsnapshot` → capture metadata (62 KB, 20 images, 94 links, 100 interactive elements).
3. `./b4w.ps1 -s books-eval htmlsnapshot inspect` → auto-discovery: `.product_pod` ×20; suggested `...

(truncated — see 20261015-121022-htmlsnapshot-inspect-discovery.issues.json for the complete execution trace)

---

## Issues Found (6 issues)

### Issue 1: htmlsnapshot inspect silently analyzes a different element set and states that the user's non-matching selector repeats

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s books-eval htmlsnapshot inspect ".no-such-thing"   (also reproduce with a selector matching exactly one element, e.g. ".sidebar")

#### Expected Behavior

The command should say that the selector matched 0 elements (or that it is a singleton container) and clearly mark the pattern it actually analyzed, e.g. 'no elements matched "..."; auto-discovered repeating pattern "article.product_pod" instead'. The header and every sentence should name the SAME selector.

#### Actual Behavior

Header prints '### Inspect: ".product_pod" (20 matches, 20 analyzed)' while the body prints 'Auto-discovered repeating pattern from ".no-such-thing" — The tool found that ".no-such-thing" repeats as a sibling group (e.g. a product grid, search result list).' Exit code 0, no warning. A user/agent reading the prose concludes that a selector which matches nothing is a repeating group. The single-match variant (".sidebar") claims '.sidebar repeats as a sibling group' although it matched exactly one element (the real repeating unit was '.sidebar li', shown only in the header).

#### Root Cause Analysis

Backend inspectDocument() enters auto-discovery whenever the user's selector matches <=1 element (initialMatchCount <= 1). It replaces the effective selector (page-level structural/visual discovery when 0 matches; container-scoped discovery — effectiveSelector = "$selector ${discovered.selector}" — when exactly 1), and reports autoDiscovered=true with originalSelector=<user input>. The Rust renderer handle_html_snapshot_inspect() then prints prose that asserts the ORIGINAL selector repeats, using originalSelector in sentences written for the discovered selector. The CLI's explicit '- No elements matched' branch only runs when the backend returns matchCount == 0, which never happens because discovery substitutes a non-empty selector.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_inspect (auto-discovered block near the '🔍 Auto-discovered repeating pattern' println), with the substitution performed in browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:inspectDocument (initialMatchCount <= 1 branch)`

#### AI Suggested Improvement

- When the user-supplied selector matched 0 elements, print that fact FIRST and explicitly ('Your selector ".x" matched 0 elements; showing the auto-discovered pattern below instead') and never assert the original selector repeats.
- Pass the initial match count through to the renderer (e.g. a new initialMatchCount/originalMatchCount field in the JSON) and branch the wording on it: 0 matches -> 'matched nothing, fallback used'; 1 match -> 'matched one container, analysed its repeating children'.
- Add an integration test asserting that inspect with a non-matching selector does not print the phrase 'repeats as a sibling group' about the user's selector.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: htmlsnapshot summary's 'Suggested Commands' emits an invalid CSS selector (space-joined classes) that matches 0 elements

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s books-eval htmlsnapshot summary   then run the printed command: ./b4w.ps1 -s books-eval htmlsnapshot get all text ".col-sm-8 col-md-9 a" --limit 20

#### Expected Behavior

Commands advertised as 'Copy-paste ready' should run and match elements.

#### Actual Behavior

The third suggested command is htmlsnapshot get all text ".col-sm-8 col-md-9 a" --limit 20 and returns '[]' with 'No elements matched'. '.col-sm-8 col-md-9' is parsed as a descendant 'col-md-9' element inside .col-sm-8. The corrected selector '.col-sm-8 a' returns 42 elements. The same defect appears in the summary's own structure labels ('div.col-sm-8 col-md-9', 'div.container-fluid page').

#### Root Cause Analysis

buildSelectorHint() in the backend WPSI summarizer returns '.' + the element's FULL class attribute value, so a multi-class element yields '.cls1 cls2'. That string is serialized as the link group's 'selector:' field and re-used verbatim by the CLI to build the copy-paste command (format!("htmlsnapshot get all text \"{} a\" --limit 20", sel)).

#### Code Pointer

`browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/workflow/parse/html/PageSummaryIndexService.kt:buildSelectorHint (line ~423) — and the command template at cli/browser4-cli/src/main.rs (Suggested Commands, `for sel in linkgroup_selectors.iter().take(3)`).`

#### AI Suggested Improvement

- In buildSelectorHint, split className on whitespace and join with '.', i.e. '.col-sm-8.col-md-9' (or use only the first/most distinctive class when truncating).
- Guard the emitters: any selector string containing a space that is not a valid descendant combination should be dropped or emitted as an attribute selector '[class="col-sm-8 col-md-9"]'.
- Add a summary-level test that every 'Suggested Commands' line round-trips through htmlsnapshot get all with a non-empty match count on the fixture page.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Malformed CSS selectors are reported as 'No elements matched' instead of a syntax error (exit 0)

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s books-eval htmlsnapshot get all text "article[["   (same for "div:not(", "p > >", ">>>")

#### Expected Behavior

A parse error should be distinguishable from a valid selector that found nothing (e.g. an error naming the selector as invalid CSS), so a typo can be diagnosed.

#### Actual Behavior

Every malformed selector returns '[]' plus 'No elements matched ... The read captured the page as the tab shows it now, so the element is simply not there — check the selector, the current URL, and that the page has finished loading.' with status 'ok', match_count 0 and exit code 0. The message asserts the selector is fine and the PAGE is at fault, which sends the user hunting for a missing element instead of a typo.

#### Root Cause Analysis

The all-elements path evaluates the selector with jsoup document.select(selector) inside extractSnapshotFields(); a CSS parse failure surfaces as an empty element list identical to a genuine zero-match. (Whether jsoup parses these leniently or a SelectorParseException is swallowed upstream was not conclusively established here — jsoup was not available locally — but the observable contract is that no error ever reaches the client.) Additionally, zero-result exit codes are inconsistent across the family: htmlsnapshot grep exits 1 on zero matches while htmlsnapshot get all exits 0.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:extractSnapshotFields (document.select(selector))`

#### AI Suggested Improvement

- Wrap document.select() and map Selector.SelectorParseException to a distinct error result (e.g. status/invalidSelector + 400) instead of an empty list; verify with a unit test calling inspect/extract with 'article[['.
- Have the CLI render 'invalid CSS selector' when that field is present, rather than the current 'the element is simply not there' text.
- Align the zero-result contract: document it, and consider exiting non-zero (or a --fail-on-empty flag) for get/get all to match snapshot grep's exit 1.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: htmlsnapshot summary prints the same suggested command twice

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s books-eval htmlsnapshot summary  — the 'Suggested Commands' block lists 'htmlsnapshot get all text ".row a" --limit 20' twice

#### Expected Behavior

Each suggested command appears once (or duplicates are collapsed with a note).

#### Actual Behavior

The listing repeats the identical line. It happens because two link groups share the container selector '.row' (ol.row > article and ol.row > li) and the CLI iterates the collected selectors without de-duplication.

#### Root Cause Analysis

linkgroup_selectors collects one entry per link group (from the 'selector:' lines of the summary YAML) and the Suggested Commands loop does iter().take(3) with no dedup; two groups collapsing to the same selector therefore print twice and consume two of the three suggestion slots.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle summary rendering — 'for sel in linkgroup_selectors.iter().take(3)' block (around the '### Suggested Commands' outline.push_str calls)`

#### AI Suggested Improvement

- De-duplicate linkgroup_selectors (e.g. sort + dedup, or a HashSet) before emitting suggestions.
- Prefer the itemSelector of a group when it is more specific than the container selector (e.g. '.product_pod' instead of '.row') so the suggested command targets the cards rather than the row wrapper.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Serialized/exported HTML contains injected annotations (vi boxes, link rel="normalizedURI") that the export and grep help do not mention

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s books-eval htmlsnapshot export --file page.html  then inspect page.html; also see the output of ./b4w.ps1 -s books-eval htmlsnapshot grep "product_pod" --selector "ol.row"

#### Expected Behavior

Help for export/grep should state that the artifact is an annotated serialization (or the docs' 'raw HTML text' phrasing should be corrected).

#### Actual Behavior

The exported file carries vi="x y w h" on every element (516 occurrences) and two <link rel="normalizedURI" href="…"> plus one <link rel="href"> injected into <head>. The live DOM contains none of these (verified with eval: 0 occurrences). grep's --selector help says it searches 'raw HTML text', which can therefore match tool-generated attributes rather than the site's markup, and a downstream consumer of the export receives three synthetic link elements.

#### Root Cause Analysis

The driver injects visual-information (vi) attributes and the normalizedURI link while serializing HTML (documented in references/htmlsnapshot.md's release-notes section, and hinted at by export's --clean 'non-standard attributes'), but the per-command help text shown by `help htmlsnapshot`, `help htmlsnapshot export` and `help htmlsnapshot grep` does not surface this.

#### Code Pointer

`cli/browser4-cli/src/main.rs / cli/browser4-cli/src/help.rs (htmlsnapshot help text for export and grep)`

#### AI Suggested Improvement

- Add one sentence to the export/grep help: 'The serialized HTML is annotated during capture: every element carries a vi="x y w h" visual box and <head> gains a <link rel="normalizedURI"> — use --clean to strip non-standard attributes.'
- In grep help, replace 'raw HTML text' with 'captured HTML text (the annotated serialization, not the site's source)'. 

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Summary page-type classification labels the product listing page 'Article / Content'

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s books-eval htmlsnapshot summary

#### Expected Behavior

A 20-item product grid page should be classified as a listing/product page, not an article.

#### Actual Behavior

'Type: Article / Content' is printed. The useful signal is in the other sections (Link Groups found the 20×4 grid with 'grid(4 cols)'), so a reader who trusts the Type line is misled, and an agent that branches on it (article vs listing extraction) will take the wrong path.

#### Root Cause Analysis

The page-type heuristic appears to key on heading/content-node density and has no product-grid/list-page rule (or the rule ranks below the article rule for this page).

#### Code Pointer

`browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/workflow/parse/html/PageSummaryIndexService.kt (type labels & selector hints section, ~line 392, and the link-group scoring that already recognises grid layouts)`

#### AI Suggested Improvement

- Promote a 'Listing / Product grid' type when a high-scoring link group has many siblings with columnCount > 1 and repeated item structure (the data used for the 'grid(4 cols)' annotation is already computed).
- Or print the type with a confidence marker and always show the link-group summary alongside it.

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

**Completion Status:** Successful — every step of the task completed on the first attempt: navigation, capture, bare and scoped inspect, summary, title extraction with the authoritative count (20 elements matched / match_count 20), selector-scoped grep, an X-SQL query returning 20 correlated title+price rows, and a sidebar inspection. No step had to be abandoned.

**Success Rate:** 100% of task steps; two tool outputs (a summary-suggested selector and the inspect narration) were wrong and had to be corrected by hand before they could be trusted, but they did not block progress.

**Issues Found:** 6

**Most Confusing Aspects:** 1) htmlsnapshot inspect's narration contradicts its own header when the selector matches 0 or 1 elements — the prose insists the user's selector 'repeats as a sibling group' while the header shows a different, auto-discovered selector. 2) The summary advertises 'Copy-paste ready' commands, one of which is an invalid selector that silently returns nothing. 3) Malformed CSS selectors and valid-but-absent selectors produce identical, reassuring 'the element is simply not there' output, so a typo looks like a page problem. 4) Deciding when grep -c is meaningful — it counts lines (grep -c "a" = 200 within a subtree of 20 cards), so it cannot validate a selector.

**Most Valuable Improvements:** 1) Never assert that the user's selector repeats when the tool substituted another element set — report the 0-match/1-match case explicitly (Issue 1). 2) Fix buildSelectorHint so multi-class elements produce '.a.b' selectors and make every suggested command round-trip through an execution test (Issue 2). 3) Surface CSS parse errors distinctly from empty result sets, and align zero-result exit codes across the read commands (Issue 3). 4) De-duplicate suggested commands and prefer item selectors (Issue 4).

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

#### Issue 1: htmlsnapshot inspect silently analyzes a different element set and states that the user's non-matching selector repeats

./b4w.ps1 -s books-eval htmlsnapshot inspect ".no-such-thing"   (also reproduce with a selector matching exactly one element, e.g. ".sidebar")

#### Issue 2: htmlsnapshot summary's 'Suggested Commands' emits an invalid CSS selector (space-joined classes) that matches 0 elements

./b4w.ps1 -s books-eval htmlsnapshot summary   then run the printed command: ./b4w.ps1 -s books-eval htmlsnapshot get all text ".col-sm-8 col-md-9 a" --limit 20

#### Issue 3: Malformed CSS selectors are reported as 'No elements matched' instead of a syntax error (exit 0)

./b4w.ps1 -s books-eval htmlsnapshot get all text "article[["   (same for "div:not(", "p > >", ">>>")

#### Issue 4: htmlsnapshot summary prints the same suggested command twice

./b4w.ps1 -s books-eval htmlsnapshot summary  — the 'Suggested Commands' block lists 'htmlsnapshot get all text ".row a" --limit 20' twice

#### Issue 5: Serialized/exported HTML contains injected annotations (vi boxes, link rel="normalizedURI") that the export and grep help do not mention

./b4w.ps1 -s books-eval htmlsnapshot export --file page.html  then inspect page.html; also see the output of ./b4w.ps1 -s books-eval htmlsnapshot grep "product_pod" --selector "ol.row"

#### Issue 6: Summary page-type classification labels the product listing page 'Article / Content'

./b4w.ps1 -s books-eval htmlsnapshot summary

