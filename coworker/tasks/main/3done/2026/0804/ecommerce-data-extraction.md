# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260804-180301-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260804-180301 | **Mode:** dev

## Scenario Background

### Task

Successfully completed an end-to-end e-commerce data extraction workflow using browser4-cli:

- **Navigated** to the MockSite e-commerce home page
- **Captured** viewport and interactive snapshots to discover page structure
- **Clicked** through to the "4K OLED TV 55" product detail page
- **Extracted** product title, price ($899.99), description, specs, and image URL using `htmlsnapshot get` and `inspect`
- **Discovered** CSS selectors: `#product-page h1` (title), `.buybox` (price), `a.product-link` (listing titles), `.product-price` (listing prices)
- **Extracted** all 6 Electronics products with prices via `htmlsnapshot get all`
- **Counted** 6 product links via `eval --json`
- **Captured** a screenshot named `electronics-listing-electronics-6-products.png`
- **Verified** content after tab switch + reload
- **Searched** for "OLED", "HDR10+", and "55 inch" using `snapshot grep`
- **Saved** browser state for session persistence
- **X-SQL query** failed twice with a documented backend race condition (417)

---

### Execution Context

| Step | Command | Outcome |
|------|---------|---------|
| 0 | `mkdir -p .test-sessions` | Created temp directory |
| 1 | `./b4w.sh goto "http://localhost:18080/ec/"` | Page loaded, reused existing session |
| 2 | `./b4w.sh snapshot -v 0 --stdout` | 173-line viewport tree with product links and category nav |
| 3 | `./b4w.sh snapshot -i --stdout` | 228-line interactive tree (clickable elements) |
| 4 | `./b4w.sh click e1626` | Navigated to `/ec/dp/B0E000001` |
| 5 | `./b4w.sh htmlsnapshot` | Captured 15KB HTML snapshot |
| 6 | `./b4w.sh htmlsnapshot inspect --max 3 --depth 2` | Found `.recommendation-card` pattern (not main product) |
| 7 | `./b4w.sh htmlsnapshot get all text` + targeted queries | Extracted all text, found selectors via trial |
| 8 | Wrote `.test-sessions/product-extract....

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: X-SQL query fails with 417 'scrape session closed' race condition

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.sh htmlsnapshot query "http://localhost:18080/ec/dp/B0E000001" --sql @.test-sessions/product-extract.sql

#### Expected Behavior

Structured query result with title, price, and image URL columns returned as a result set.

#### Actual Behavior

417 Expectation Failed with message 'The scrape session closed before the query could execute.' Failed on both attempts with identical error.

#### Root Cause Analysis

Backend race condition in the X-SQL scrape session lifecycle. The error message itself acknowledges this is a 'known backend race condition.' The session appears to close before the DOM_LOAD_AND_SELECT query can execute, possibly due to session initialization timing or resource contention.

#### Code Pointer

`browser4-rest module — X-SQL scrape session management, likely in the query execution path that creates and initializes the scrape session`

#### AI Suggested Improvement

- Add retry logic with exponential backoff in the backend scrape session initialization
- Ensure the scrape session is fully initialized before returning control to the query executor
- Consider warming/priming the session before accepting query execution
- Document workaround: suggest using eval with DOM APIs as a fallback for single-page extraction

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Hard failure on both attempts with a self-acknowledged race condition. The error message literally says "known backend race condition" — this must be fixed, not documented around. Retry-with-backoff in the session init path is the right starting point.

---

### Issue 2: Screenshot positional argument treated as element ref, not filename

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.sh screenshot .test-sessions/my-screenshot.png

#### Expected Behavior

Screenshot saved to the specified file path.

#### Actual Behavior

Screenshot saved to default snapshot directory with auto-generated timestamp filename; the positional argument was silently interpreted as an element ref.

#### Root Cause Analysis

The screenshot command's positional argument is `[ref]` (element reference), not a filename. Users coming from Playwright/Puppeteer expect `screenshot <path>` semantics. The `--filename` flag is required for custom output paths, but this is not obvious from the help output format which shows `[ref]` as an optional positional arg.

#### Code Pointer

`cli/browser4-cli/src/commands.rs or the screenshot command definition`

#### AI Suggested Improvement

- Detect when the positional argument looks like a file path (contains / or \ or ends with .png) and warn the user to use --filename instead
- Add a `--output` or `-o` short alias for --filename
- Add an example to --help: `screenshot --filename ./my-shot.png`
- Consider making the first positional argument ambiguous: if it looks like a path, use it as --filename; if it looks like a ref, use it as a ref

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The positional-arg ambiguity is real, but the suggested fix of auto-detecting path-like strings and switching behavior is fragile (what about `#product-page.png` — a ref or a filename?). Better: keep `[ref]` semantics but detect when the arg looks like a file path (ends with `.png`, contains `/` or `\`) and emit a clear error: `"my-screenshot.png" looks like a file path. Use --filename instead.` Add `-o` as a short alias for `--filename`.

---

### Issue 3: htmlsnapshot get text returns first DOM match, not the most relevant one

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.sh htmlsnapshot get text "h1" on a page with multiple h1 elements

#### Expected Behavior

Returns the most prominent/visible h1 text (the product title '4K OLED TV 55') or warns about ambiguity.

#### Actual Behavior

Returns 'Mock Ecommerce' (the site header h1, which appears first in DOM order but is the site branding, not the page's main heading). A new user would be confused by the mismatch.

#### Root Cause Analysis

`get text` uses querySelector (first match in DOM order), not a visible/prominence heuristic. The site header h1 appears before the product title h1 in DOM order. The SKILL.md does explain this distinction but the error is silent — no warning that there are multiple matches.

#### Code Pointer

`browser4-core module — htmlsnapshot get text implementation`

#### AI Suggested Improvement

- When there are multiple matches, emit a warning on stderr: 'Found N matches for "h1"; returning first. Use get all text for all matches.'
- Add a `--prominence` flag that ranks by visibility/position/size rather than DOM order
- Document this behavior prominently in the htmlsnapshot get help text
- Consider returning the largest/most-visible match by default when there are multiple

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The `querySelector` first-match behavior is standard and correct. Don't change it. The real fix is the warning: when `querySelectorAll` returns N > 1, emit to stderr: `Found 2 matches for "h1"; returning first. Use "get all text h1" for all matches.` This matches the principle from Issues 4/6 — the information exists in SKILL.md but isn't delivered at the point of confusion.

---

### Issue 4: Task invocation syntax $(./b4w.ps1) incompatible with bash

**Severity:** Medium
**Category:** Documentation

#### Reproduction

In Git Bash: $(./b4w.ps1) goto "http://example.com"

#### Expected Behavior

Either the task template syntax should work in bash, or the documentation should clearly state the correct invocation.

#### Actual Behavior

SKILL.md explicitly states: 'The $(./b4w.ps1) <command> syntax shown in some task instructions does not work in bash — $(…) is command substitution, not invocation.' This creates a direct conflict between the task instructions and the tool's own documentation.

#### Root Cause Analysis

The task template uses PowerShell syntax $(./b4w.ps1) as a macro-like notation, but bash interprets $(...) as command substitution which executes the command and substitutes its output. The SKILL.md addresses this, but the task instructions still mandate the broken syntax.

#### AI Suggested Improvement

- Update task templates to use a shell-agnostic notation like `browser4-cli` or `./b4w.sh`
- Add a wrapper/alias so $(./b4w.ps1) works as command substitution in bash (e.g., by making the script output nothing extra)
- Document the bash invocation clearly at the top of all task templates

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Direct conflict between task templates and SKILL.md. The SKILL.md already documents the correct behavior, but task templates still mandate broken syntax. Fix the task templates to use `./b4w.sh` or a shell-agnostic notation. This is the same root cause as Issues 3 and 6 — docs exist but aren't propagated to the artifact the user actually reads.

---

### Issue 5: htmlsnapshot inspect only finds repeating patterns, not singletons like product title on detail pages

**Severity:** Medium
**Category:** Product

#### Reproduction

On a product detail page (single product), run: ./b4w.sh htmlsnapshot inspect --max 3 --depth 2

#### Expected Behavior

Inspect should help discover selectors for the main product fields (title, price, description, image) on the detail page.

#### Actual Behavior

Inspect only found the repeating '.recommendation-card' pattern. The main #product-page article with title, price, description was not surfaced because it only appears once. The user must manually trial-and-error with get text/get all to find selectors.

#### Root Cause Analysis

`htmlsnapshot inspect` is designed to find repeating patterns (grids, lists, cards) — it looks for sibling groups that repeat. A product detail page has a single main content area, so there's no repetition to detect. The inspect feature doesn't have a mode for analyzing a single container's children.

#### Code Pointer

`browser4-core module — htmlsnapshot inspect implementation`

#### AI Suggested Improvement

- Add a 'single element' analysis mode that breaks down a unique container's children into labeled fields
- When inspect finds no repeating patterns, fall back to showing the structure of the main content area with suggested selectors
- Add htmlsnapshot inspect '--single' or '--container <selector>' mode for detail pages
- Document that inspect is for listing/search pages and suggest summary/get for detail pages

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Inspect is correctly designed for repeating patterns — this is a feature gap, not a bug. Rather than a full `--single` mode, the simpler fix is: when inspect finds zero repeating patterns, fall back to printing the top-level container structure with suggested selectors (essentially a lightweight summary). This reuses existing code paths and avoids a new flag. The SKILL.md note about using `summary`/`get` for detail pages should also appear in `htmlsnapshot inspect --help`.

---

### Issue 6: Interactive snapshot (-i) strips generic divs, hiding e-commerce product cards

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.sh snapshot -i on the e-commerce home page

#### Expected Behavior

All clickable elements including product cards should be visible in interactive mode.

#### Actual Behavior

The SKILL.md warns: 'Interactive mode (snapshot -i) strips generic <div> containers. Many e-commerce product cards use generic divs, not semantic elements.' The home page's product grid area was not fully represented, though links within it still appeared.

#### Root Cause Analysis

Interactive mode filters to only interactive/semantic elements, stripping generic containers like <div> and <span>. This is by design for reducing noise, but modern e-commerce sites heavily use generic divs for product cards.

#### AI Suggested Improvement

- Consider a --keep-containers flag that retains generic divs with interactive children
- Add a heuristic: if a generic div contains links/buttons/images, treat it as a 'card' container and include it
- Update the warning text to be more prominent in snapshot -i output
- Suggest `snapshot -v 0` as the preferred first command for e-commerce in the docs

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The behavior is by-design and documented, but the UX consequence is real enough to warrant improvement. The heuristic suggestion (keep generic divs that contain interactive children) is low-risk and would catch most e-commerce product cards. The SKILL.md warning should also appear in `snapshot -i` output as a stderr note when generic containers were stripped.

---

### Issue 7: No LLM configuration leads to silently unavailable AI features

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.sh extract "get product data as JSON"

#### Expected Behavior

Clear guidance on how to configure an LLM key, or a helpful error message pointing to setup docs.

#### Actual Behavior

The `doctor` command shows 'LLM is not configured.' The extract command itself would likely fail with a less helpful error. There's no `--help` for extract that mentions the LLM requirement upfront.

#### Root Cause Analysis

LLM-dependent commands (extract, summarize, agent, chat) require OPENROUTER_API_KEY or equivalent environment variable. This dependency is not visible in the command help output — only discoverable via `doctor` or by trying the command and getting an error.

#### AI Suggested Improvement

- Add LLM requirement to --help for extract/summarize/agent/chat commands
- Provide a setup command: `browser4-cli config set llm.key <key>` or `browser4-cli setup-llm`
- Show a first-run banner with setup instructions when LLM is unconfigured
- Add a `--dry-run` or capability check to extract that tells the user if it would work

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] LLM-dependent commands should declare their dependency. The simplest fix: add `Requires: OPENROUTER_API_KEY (or equivalent)` to the `--help` output of `extract`, `summarize`, `agent`, and `chat`. Also surface the `doctor` output at the end of `--help` for these commands if the key is missing. This follows the same pattern as Issues 3/4/6 — surface existing information at the point of need.

---

### Issue 8: Session reuse creates unexpected duplicate tabs

**Severity:** Low
**Category:** UX

#### Reproduction

1. Have an existing session at a product page. 2. Run `goto http://localhost:18080/ec/`. 3. Run `tab-list`.

#### Expected Behavior

The existing tab navigates to the new URL, or at most one new tab is created.

#### Actual Behavior

Three tabs existed: two identical product detail tabs (from initial session + a previous goto) and one listing tab. The session reuse behavior created tab clutter that was confusing.

#### Root Cause Analysis

The default session already had a tab at the product detail page. The initial `goto` to the home page navigated within the same tab. But a previous goto from before this session also left a duplicate. Tab management across session reuse is not transparent to the user.

#### AI Suggested Improvement

- `goto` on an existing session should navigate in-place by default (current behavior), but document this
- Add `gotonew` or `goto --new-tab` for explicit new-tab navigation
- Show a brief session summary at startup: 'Reusing session DEFAULT (1 tab, current: http://...)'
- List tabs in `list` output with clearer ownership/creation source

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The session-summary-at-startup suggestion ("Reusing session DEFAULT (1 tab, current: http://...)") is a one-line addition that would prevent most of this confusion. The `goto --new-tab` flag is a natural extension. Tab clutter from previous sessions is a real friction point for CLI users who expect stateless commands.

---

## Overall Assessment

**Completion Status:** Partially Successful — 16 of 18 steps completed successfully. X-SQL query failed (reliability bug). LLM extract skipped (no API key configured). All core extraction, navigation, screenshot, state-save, and verification steps succeeded.

**Success Rate:** 89% — 16/18 steps succeeded, 1 failed (X-SQL), 1 skipped (extract/LLM)

**Issues Found:** 8

**Major Blockers:** X-SQL backend race condition (417) prevented structured query execution on the product detail page. This is a documented known issue.

**Most Confusing Aspects:** 1. The $(./b4w.ps1) syntax mandated by task instructions doesn't work in bash per SKILL.md. 2. htmlsnapshot get text returns first DOM match silently — 'h1' returned site branding not product title. 3. Screenshot positional arg is silently treated as element ref, not filename. 4. htmlsnapshot inspect only finds repeating patterns, not detail-page singletons.

**Most Valuable Improvements:** 1. Fix the X-SQL scrape session race condition (blocks a core extraction workflow). 2. Add ambiguity warnings when get text has multiple matches. 3. Make screenshot accept filename as positional arg or warn on path-like values. 4. Add singleton analysis mode to htmlsnapshot inspect for detail pages.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: X-SQL query fails with 417 'scrape session closed' race condition

./b4w.sh htmlsnapshot query "http://localhost:18080/ec/dp/B0E000001" --sql @.test-sessions/product-extract.sql

#### Issue 2: Screenshot positional argument treated as element ref, not filename

./b4w.sh screenshot .test-sessions/my-screenshot.png

#### Issue 3: htmlsnapshot get text returns first DOM match, not the most relevant one

./b4w.sh htmlsnapshot get text "h1" on a page with multiple h1 elements

#### Issue 4: Task invocation syntax $(./b4w.ps1) incompatible with bash

In Git Bash: $(./b4w.ps1) goto "http://example.com"

#### Issue 5: htmlsnapshot inspect only finds repeating patterns, not singletons like product title on detail pages

On a product detail page (single product), run: ./b4w.sh htmlsnapshot inspect --max 3 --depth 2

#### Issue 6: Interactive snapshot (-i) strips generic divs, hiding e-commerce product cards

./b4w.sh snapshot -i on the e-commerce home page

#### Issue 7: No LLM configuration leads to silently unavailable AI features

./b4w.sh extract "get product data as JSON"

#### Issue 8: Session reuse creates unexpected duplicate tabs

1. Have an existing session at a product page. 2. Run `goto http://localhost:18080/ec/`. 3. Run `tab-list`.

