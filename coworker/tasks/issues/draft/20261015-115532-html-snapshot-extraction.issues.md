# Issues: html-snapshot-extraction

> **Source:** `20261015-115532-html-snapshot-extraction.issues.json` | **Date:** 20261015-115532 | **Mode:** dev

## Scenario Background

### Task

**Task: extract book data from books.toscrape.com using `htmlsnapshot`** — completed successfully. All 10 steps executed.

| # | Step | Result |
|---|------|--------|
| 1 | Navigate to `http://books.toscrape.com/` | ✅ Redirected to `https://books.toscrape.com/` (HTTP→HTTPS); title "All products \| Books to Scrape - Sandbox" |
| 2 | Capture fresh HTML snapshot | ✅ 62 KB · text/html · 20 images · 94 links · 100 interactive elements (20 book cards, 80 links) |
| 3 | Text of first book title | ✅ `A Light in the ...` — **the page's own markup truncates it**; full title from the `title` attribute: **"A Light in the Attic"** |
| 4 | HTML of first product container | ✅ Inner HTML of `article.product_pod`: `div.image_container` → `p.star-rating.Three` → `h3 > a[title]` → `div.product_price` (£51.77, In stock, Add to basket) |
| 5 | `href` of first book link | ✅ Raw: `catalogue/a-light-in-the-attic_1000/index.html` · Absolute: `https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html` |
| 6 | Text of every book title | ✅ 20 elements returned; page markup truncates 8 of them (e.g. `Sapiens: A Brief History ...`). Full values via `get all attr "h3 a" title` (e.g. `Sapiens: A Brief History of Humankind`) |
| 7 | Titles 6–10 via `--offset`/`--limit` | ✅ `--offset 5 --limit 5` → The Requiem Red, The Dirty Little Secrets ..., The Coming Woman: A ..., The Boys in the ..., The Black Maria |
| 8 | Export snapshot to HTML file | ✅ `.test-sessions/20261015T0901431699046Z/books-page.html` (42,957 bytes, ends with `</html>`, book titles present for offline grep) |
| 9 | Page summary | ✅ 76-line WPSI outline: page type, 4 link groups, 23 landmarks, 20 content nodes, 3 lists, stats (516 nodes / 94 links / 20 buttons / 21 forms / 20 images); full YAML saved to `.browser4-cli/snapshot/htmlsnapshot-summary-2026-10-15T11-53-12-001Z.yml` (57,751 bytes) |
| 10 | Grep "price" + count | ✅ `htmlsnapshot grep -c "price"` → **40**. Independently cross-checked against the exported HTML: 40 occurrences (41 case-insensitive — the extra is the page banner "**Prices** and ratings here were randomly assigned…") |

### Execution Context

**Key Commands:**

```
1.  ./b4w.ps1 help                                  # + --help htmlsnapshot / "htmlsnapshot get" / "htmlsnapshot get all"
2.  ./b4w.ps1 goto "http://books.toscrape.com/"     # reused existing DEFAULT session, printed redirect notice + snapshot link
3.  ./b4w.ps1 htmlsnapshot                          # capture + metadata (step 2)
4.  ./b4w.ps1 htmlsnapshot get text "h3 a"          # first title (step 3)
5.  ./b4w.ps1 htmlsnapshot get attr "h3 a" title    # full (untruncated) first title
6.  ./b4w.ps1 htmlsnapshot get html "article.product_pod"        # container HTML (step 4)
7.  ./b4w.ps1 htmlsnapshot get attr "h3 a" href [--absolute]     # first link (step 5)
8.  ./b4w.ps1 htmlsnapshot get all text "h3 a"                   # all titles (step 6)
9.  ./b4w.ps1 htmlsnapshot get all text "h3 a" --offset 5 --limit 5 [# --json]   # titles 6–10 (step 7)
10. ./b4w.ps1 htmlsnapshot export --file "<scratch>/books-page.html"             # export (step 8)
11. ./b4w.ps1 htmlsnapshot summary > "<scratch>/summary-full.txt"                # summary (step 9)
12. ./b4w.ps1 htmlsnapshot get all attr "h3 a" title              # all 20 full titles (title-attr workaround)
13. ./b4w.ps1 htmlsnapshot grep -c "price"                        # count (step 10) → 40
14. ./b4w.ps1 htmlsnapshot grep "price" [--all]                   # match listing
15. Offline cross-checks: grep -o "price" <exported file> | wc -l → 40
```

**Key decisions / workarounds:**
- **Selector discovery:** I did not guess selectors. `htmlsnapshot summary` surfaced `article .product_pod` and the docs' `inspect` example gave the `h3 a` title-link pattern; `get`/`get all` then worked first try.
- **Truncated titles (documented pitfall):** `text` returned `A Light in the ...`. The docs twice state `text`/`textcontent` cannot recover page-truncated text and point to the carrying attribute — `get attr "h3 a" title` returned the full values for step 3 and again for all 20 titles.
- **Relative hrefs (documented pitfall):** `attr` returned the raw relative href; `--absolute` resolved it against the page URL.
- **Stream discipline verified:** `grep`'s freshness header goes to stderr (piped stdout was pure match lines); `-c` printed exactly `40`.
- **Pagination semantics:** `--offset` is 0-based and skips before slicing; the human count line reports the *returned* slice (see Issue 1).
- One operator error — `htmlsnapshot summary --all` — was rejected with a precise, actionable message listing the supported options; no issue.

**Evidence files (this run's scratch dir):** `books-page.html`, `summary-full.txt`.

---



**Overall usability (1–10): 7** — The documentation is unusually thorough (the SKILL.md, `--help <command>` pages, and the truncated-text/relative-href warnings all matched observed behavior exactly, and `skills get` keeps docs version-matched), error messages are precise and actionable, and the htmlsnapshot workflow required no guesswork. The deduction is for output-quality defects that actively mislead: the sliced match count contradicting the documented verification recipe, and the summary's "copy-paste ready" commands that don't work.

---

## Issues Found (6 issues)

### Issue 1: get all count line and match_count report the paginated slice, not the total match count

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot get all text "h3 a" --offset 5 --limit 5  →  "5 elements matched."; same command with --json → "match_count":5. ./b4w.ps1 htmlsnapshot get all text "h3 a" --offset 5  →  "15 elements matched.". The selector actually matches 20 elements (verified by the command without flags and by htmlsnapshot inspect).

#### Expected Behavior

The count should be the total number of elements the selector matches (20), or be explicitly labelled as the number of returned rows (e.g. "5 of 20 elements"). The documentation tells users this count is the reliable way to verify a selector's match count.

#### Actual Behavior

Both the human "N elements matched." line and the --json match_count reflect the length of the already-sliced result array, so paginating silently produces a wrong answer to "does my selector match N elements?". The in-code comment claims it is "the reliable 'does the selector match N elements?' answer".

#### Root Cause Analysis

The CLI derives the count from the returned JSON array's length after the server applied --offset/--limit, instead of using a total-match value. The backend response carries no total, so the printed number is bounded by --limit/--offset.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_get() — array_count computed at ~line 10382-10394 and match_count at ~line 10448-10451`

#### AI Suggested Improvement

- Report the true match total: have the backend return a totalMatches value in the get all response and print/emit that instead of the sliced array length
- When a total is unavailable, label the number as the returned count ("5 elements returned") so it is not mistaken for the selector's match count
- Cover the --offset/--limit case in the existing unit tests for the count line and the --json envelope
- Align skills/browser4-cli/references/htmlsnapshot.md, which currently instructs users to use this count to verify a selector's element count

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Summary "Suggested Commands" are invalid or misleading, and are duplicated

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot summary → read the ### Suggested Commands block, then run the listed commands:
  1) ./b4w.ps1 htmlsnapshot get all text ".row a" --limit 20  → 93 elements (sidebar category links, empty strings, 'next'), not the 20 book titles; --limit 20 truncates the output and hides the mismatch. This line is printed twice.
  2) ./b4w.ps1 htmlsnapshot get all text ".col-sm-8 col-md-9 a" → "No elements matched" (the selector is a descendant selector, not the compound div.col-sm-8.col-md-9).

#### Expected Behavior

The block is labelled "Copy-paste ready — use these to extract data", so each command should run successfully and return the page's repeated items (the 20 book titles/links).

#### Actual Behavior

Two of the three suggested commands fail their stated purpose: one returns 93 unrelated anchors, one returns nothing, and one is printed twice.

#### Root Cause Analysis

Two defects compound. (1) Backend: PageSummaryIndexService.buildSelectorHint() returns "." + the raw class attribute, so multi-class elements become ".class1 class2" (a descendant selector) instead of ".class1.class2"; the correct compound formatter already exists in the same file (formatClassList). The summary YAML therefore contains selectors like ".header container-fluid" and ".col-sm-8 col-md-9". (2) CLI: format_summary_outline() takes each link-group container selector and blindly appends " a" with --limit 20, without deduplicating (two groups collapse to the same ".row" selector) or validating that the result matches anything or is precise (the intended item-level selector is the link group's own item tag/link, not any descendant anchor).

#### Code Pointer

`cli/browser4-cli/src/main.rs:format_summary_outline() (suggestion block ~line 11562-11580); browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/workflow/parse/html/PageSummaryIndexService.kt:buildSelectorHint() ~line 424 (and containerSelector at ~line 718)`

#### AI Suggested Improvement

- Fix buildSelectorHint() to emit compound class selectors (reuse formatClassList) so generated selectors actually match
- Derive the suggested extraction selector from the link group's item structure (itemTag + the anchor that carries the item text/title) instead of appending a bare " a"
- Deduplicate identical suggestions before printing
- Validate a suggestion before printing it (or mark it unverified) — e.g. skip selectors whose match count differs wildly from the group's item count
- Add a fixture-page test asserting that every printed suggestion matches (and that its match count is plausible)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Summary header repeats "### Page" and misclassifies a product listing as "Article / Content"

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot summary on https://books.toscrape.com/ (a 20-item product grid) → output begins:
  ### Page
  - Page URL: …
  - Page Title: …
  ### Page
  - Type: Article / Content

#### Expected Behavior

A single Page section combining URL/title/type, and a page-type label that reflects a catalog/listing page.

#### Actual Behavior

The "### Page" heading prints twice (once for URL/Title, once for Type), and a product listing with 20 repeated product cards and no article prose is labelled "Article / Content" — a misleading first line of the "compressed overview" the command exists to provide.

#### Root Cause Analysis

The CLI prints its own Page block (URL/Title) before rendering the YAML's Page section instead of merging them; the page-type classifier in the WPSI algorithm appears to lack a listing/catalog category, so a grid of product cards falls through to the article/content bucket.

#### Code Pointer

`cli/browser4-cli/src/main.rs:format_summary_outline() (heading emission ~line 11203+); browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/workflow/parse/html/WpsiPageSummaryAlgorithm.kt`

#### AI Suggested Improvement

- Merge the URL/Title lines into the summary's own Page section and print one heading
- Add a listing/catalog page-type classification (many sibling item containers with repeated link groups) and prefer it over Article/Content when no article-like text density exists
- Add a regression test using a listing fixture (e.g. the books fixture page) asserting the detected page type

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: htmlsnapshot get cannot return an element's outer HTML

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot get html "article.product_pod" → returns only the container's inner HTML; the wrapper `<article class="product_pod" …>` tag itself (with its classes) is absent from the output. The field list is limited to text | textcontent | html | attr.

#### Expected Behavior

A way to retrieve the first product container's outer markup (including the <article> element itself) when a task asks for "the HTML of the container".

#### Actual Behavior

`html` is inner HTML only; obtaining the outer markup requires a different command family as a workaround: ./b4w.ps1 eval "document.querySelector('article.product_pod').outerHTML".

#### Root Cause Analysis

No outerHTML field (or --outer flag) exists in the htmlsnapshot get field set; validate_html_snapshot_field() rejects anything outside the four documented fields, and no alternative is suggested in the error or help text.

#### Code Pointer

`cli/browser4-cli/src/main.rs:validate_html_snapshot_field() (line 10241) and handle_html_snapshot_get()`

#### AI Suggested Improvement

- Add an `outerhtml` field (or `--outer` modifier) that serializes the matched element itself
- Until then, mention the `eval "el.outerHTML"` workaround in the `html` field's help/doc text so users don't have to search for it

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: get all prints its result as one long single-line JSON array in human mode

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot get all text ".row a" (or any selector matching many elements) → the entire result is one unbroken line, e.g. ["Books to Scrape","Travel",…,"next"] followed by "93 elements matched." — about 1.5 screens of horizontally-scrolled text with empty strings interleaved.

#### Expected Behavior

Human-facing output that is scannable — one item per line (with an index), the JSON array form reserved for --json/pipe mode.

#### Actual Behavior

Human mode prints a raw single-line JSON array; long results wrap unpredictably in the terminal and are hard to read or diff, which pushes users to add --json and post-process immediately.

#### Root Cause Analysis

The get all human path prints the backend's array verbatim; there is no line-per-item rendering mode for the default (non-json) output.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_get() (text printing ~line 10382 and the --json match_count path ~line 10448)`

#### AI Suggested Improvement

- In default mode, render one result per line with a 1-based index, and keep the raw JSON array for --json mode
- Offer a --format lines|json switch consistent with htmlsnapshot query --format
- Keep the existing "N elements matched." trailer after the list

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: grep -c counts matching lines and there is no way to count true occurrences (-o equivalent)

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot grep -c "price" → 40 (matching lines). Against minified single-line HTML the same pattern would report 1 regardless of how many times "price" occurs. No -o/--only-matching flag exists to count actual occurrences.

#### Expected Behavior

A documented way to obtain the number of occurrences of a pattern in the snapshot, since the natural task "count how often X appears" is not always equal to the number of matching lines.

#### Actual Behavior

Only the line count is available. On this page the two coincide only because the exported HTML is pretty-printed one occurrence per line (verified: 40 lines = 40 case-sensitive occurrences in the exported file). The documentation warns that -c counts lines, but offers no occurrence-counting alternative.

#### Root Cause Analysis

The grep implementation (run_grep_on_source) is line-oriented and exposes no only-matching mode; matches are reported per line, so multiple occurrences on one line collapse into one count.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_grep() (line 12662) / run_grep_on_source() (line 12898)`

#### AI Suggested Improvement

- Add -o/--only-matching (print each match) so `… grep -o price | wc -l` counts occurrences, matching GNU grep muscle memory
- Or add --count-matches that reports total occurrences alongside the existing -c line count
- Document the distinction explicitly in the -c help text (the reference doc already explains the line-vs-element caveat)

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

**Completion Status:** Successful — all 10 task steps completed and independently verified (counts cross-checked offline against the exported HTML). The htmlsnapshot family did everything the task required on the first or second try, with no failed commands, retries, or backend problems.

**Success Rate:** 100% of task steps; 3 of the 10 steps needed a documented follow-up call (title attribute for untruncated values, --absolute for the href, title attribute again for all 20 titles) because the page's own markup truncates the display text.

**Issues Found:** 6

**Major Blockers:** None. The daemon/backend were already running; goto reused the existing DEFAULT session (2 tabs), the HTTP→HTTPS redirect was reported inline, and every htmlsnapshot command returned data. First-run latency docs (Maven build + Spring Boot start) did not apply this session.

**Most Confusing Aspects:** 1) htmlsnapshot get text returned "A Light in the ..." — a first-time user cannot tell page-inherent truncation from a tool bug without reading the reference docs, which mention it only in passing. 2) The summary's "Copy-paste ready" suggested commands produced 93 mixed links / 0 matches, which reads as a user error until you inspect the generated selectors. 3) The "N elements matched." line silently changes meaning to "N returned" once --offset/--limit are used, exactly when a user is paginating and most likely to trust it. 4) Line-vs-occurrence counting with grep -c is a subtle trap for a "count occurrences" task.

**Most Valuable Improvements:** Fix the summary's suggested-command generation (correct compound-class selectors, derive the item selector instead of appending " a", dedupe, validate) since it is advertised as copy-paste ready; make the element count total-aware under pagination; and add an occurrence-counting mode to grep plus an outerhtml field to get. Cheapest wins first: the two count/suggestion fixes are small, local, and remove misleading output rather than adding features.

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

#### Issue 1: get all count line and match_count report the paginated slice, not the total match count

./b4w.ps1 htmlsnapshot get all text "h3 a" --offset 5 --limit 5  →  "5 elements matched."; same command with --json → "match_count":5. ./b4w.ps1 htmlsnapshot get all text "h3 a" --offset 5  →  "15 elements matched.". The selector actually matches 20 elements (verified by the command without flags and by htmlsnapshot inspect).

#### Issue 2: Summary "Suggested Commands" are invalid or misleading, and are duplicated

./b4w.ps1 htmlsnapshot summary → read the ### Suggested Commands block, then run the listed commands:
  1) ./b4w.ps1 htmlsnapshot get all text ".row a" --limit 20  → 93 elements (sidebar category links, empty strings, 'next'), not the 20 book titles; --limit 20 truncates the output and hides the mismatch. This line is printed twice.
  2) ./b4w.ps1 htmlsnapshot get all text ".col-sm-8 col-md-9 a" → "No elements matched" (the selector is a descendant selector, not the compound div.col-sm-8.col-md-9).

#### Issue 3: Summary header repeats "### Page" and misclassifies a product listing as "Article / Content"

./b4w.ps1 htmlsnapshot summary on https://books.toscrape.com/ (a 20-item product grid) → output begins:
  ### Page
  - Page URL: …
  - Page Title: …
  ### Page
  - Type: Article / Content

#### Issue 4: htmlsnapshot get cannot return an element's outer HTML

./b4w.ps1 htmlsnapshot get html "article.product_pod" → returns only the container's inner HTML; the wrapper `<article class="product_pod" …>` tag itself (with its classes) is absent from the output. The field list is limited to text | textcontent | html | attr.

#### Issue 5: get all prints its result as one long single-line JSON array in human mode

./b4w.ps1 htmlsnapshot get all text ".row a" (or any selector matching many elements) → the entire result is one unbroken line, e.g. ["Books to Scrape","Travel",…,"next"] followed by "93 elements matched." — about 1.5 screens of horizontally-scrolled text with empty strings interleaved.

#### Issue 6: grep -c counts matching lines and there is no way to count true occurrences (-o equivalent)

./b4w.ps1 htmlsnapshot grep -c "price" → 40 (matching lines). Against minified single-line HTML the same pattern would report 1 regardless of how many times "price" occurs. No -o/--only-matching flag exists to count actual occurrences.

