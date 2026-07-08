# Issues: comprehensive-ecommerce-workflow

> **Source:** `20260706-194023-comprehensive-ecommerce-workflow.full.md` | **Date:** 20260706-194023 | **Mode:** dev

## Scenario Background

### Task

**Extracted Product Data Summary:**

### Product Detail: 4K OLED TV 55
| Field | Value |
|-------|-------|
| **Title** | 4K OLED TV 55 |
| **Price** | $899.99 |
| **Rating** | 4.6 / 5 (521 ratings) |
| **Category** | Electronics |
| **Status** | Bestseller |
| **Features** | 55 inch, HDR10+ |
| **Specs** | OLED panel, 3840×2160 resolution |
| **Image** | `/ec/static/img/placeholder.png` |

### Electronics Listing (6 products)
| # | Product | Price |
|---|---------|-------|
| 1 | 4K OLED TV 55 | $899.99 |
| 2 | Wireless Noise-Cancelling Headphones | $199.99 |
| 3 | Portable Bluetooth Speaker | $49.99 |
| 4 | Smartphone 128GB | $599.00 |
| 5 | USB-C Hub 7-in-1 | $29.95 |
| 6 | Wireless Mouse | $24.99 |

### Deliverables Produced
- Screenshot: `electronics-listing-6-products.png` (131 KB)
- Session state: `session-product-detail.json`
- X-SQL query: `query_product.sql`
- LLM-extracted data: `extract-*.txt`

---

### Execution Context

| Step | Command | Result | Notes |
|------|---------|--------|-------|
| 0 | Help + SKILL.md read | ✓ | `--help` output comprehensive; SKILL.md well-structured |
| 1 | `goto http://localhost:18080/ec/` | ✓ | Session auto-opened |
| 2 | `snapshot -v 0` | ✓ | 75 nodes, 4 KB |
| 3 | `snapshot -i` | ✓ | Same view; `-i` vs `-v 0` gave identical structure for this page |
| 4 | `click e38` (Electronics link) | Workaround | Home page had NO product links — only category nav; clicked "Electronics" instead |
| 4b | `click e340` (product link) | ✗→Workaround | URL contained literal `"` quotes → 404; used `goto` directly to clean URL |
| 5 | `htmlsnapshot` | ✓ | 4 KB captured |
| 6 | `htmlsnapshot inspect --max 3 --depth 2` | Partial | Found `#product-specs tr` pattern but not title/price selectors |...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: MockSite product links contain literal escaped quotes in URLs

**Severity:** High
**Category:** Product

#### Reproduction

```
goto http://localhost:18080/ec/
click e38  (Electronics category)
click e340 (first product link)
```
Or inspect `a[href]` values in the listing page HTML.

#### Expected Behavior

Product links should have clean URLs like `/ec/dp/B0E000001`.

#### Actual Behavior

Product hrefs contain literal `\"` characters: `\"/ec/dp/B0E000001\"`. When clicked, the browser navigates to `/%22/ec/dp/B0E000001/%22` (URL-encoded quotes), resulting in a 404 error page.

#### Root Cause Analysis

The MockSite seed data or HTML template incorrectly escapes quotation marks in `href` attributes. The annotated HTML stores URLs with literal backslash-quote sequences that are interpreted as part of the URL path rather than as HTML attribute delimiters.

#### Code Pointer

`The fix likely belongs in the MockSite HTML template or seed data generation, not in browser4-cli itself. However, browser4-cli could add URL sanitization when reading href attributes.`

#### AI Suggested Improvement

- Fix MockSite templates to generate clean `href="/ec/dp/B0E000001"` without escaped quotes
- Browser4-cli could strip leading/trailing quote characters from extracted URLs as a defensive measure
- Add URL validation in the `click` command to warn when a href value looks malformed

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `htmlsnapshot` fails on listing page after `tab-new` + `tab-select`

**Severity:** High
**Category:** Reliability

#### Reproduction

```
goto http://localhost:18080/ec/dp/B0E000001    # product page
tab-new http://localhost:18080/ec/b?node=1292115012   # new tab
tab-select 0                                    # switch to listing
htmlsnapshot                                    # FAILS
```

#### Expected Behavior

`htmlsnapshot` should work after switching tabs, same as it does after a direct `goto`.

#### Actual Behavior

Fails with `ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined`. Retrying after `wait --load networkidle` and `reload` does not fix it. Only a fresh `goto` to the URL resolves the issue.

#### Root Cause Analysis

When switching tabs via `tab-select`, the Browser4 injection script (`__pulsar_utils__`) is not re-injected into the page context. The script is only injected on initial page load via `goto`/`open`. After `tab-select`, the page's JS context doesn't have the injection, so `htmlsnapshot`'s `getAnnotatedHTML()` call fails.

#### Code Pointer

`The tab-switching logic in the CLI or server needs to re-inject the Browser4 utility scripts after switching to a tab that was not opened through the current session's navigation flow.`

#### AI Suggested Improvement

- On `tab-select`, detect whether `__pulsar_utils__` is available in the page context; if not, re-inject the Browser4 scripts
- Add a `--reload` flag to `tab-select` to force page reload after switching (ensuring scripts load)
- Document clearly that `htmlsnapshot` requires a `goto` (not `tab-select`) for pages in tabs not opened by the current session
- Add a pre-check in `htmlsnapshot` that tests `__pulsar_utils__` availability and provides a helpful error message suggesting `goto` or `reload`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `tab-new` does not auto-switch to the new tab

**Severity:** Medium
**Category:** UX

#### Reproduction

```
goto http://localhost:18080/ec/dp/B0E000001
tab-new http://localhost:18080/ec/b?node=1292115012
snapshot    # still shows product page, not listing page
```

#### Expected Behavior

After `tab-new`, the browser should switch to the newly created tab (consistent with how browsers work when you open a new tab).

#### Actual Behavior

`tab-new` creates the tab but the active tab remains unchanged. User must run `tab-list` to find the index, then `tab-select <index>` to switch.

#### Root Cause Analysis

`tab-new` creates a tab via CDP but doesn't call `Page.activate()` or equivalent to bring it to the foreground. This is likely an intentional design choice, but it's counterintuitive for users.

#### Code Pointer

``cli/browser4-cli/src/` — the `tab-new` command handler needs an auto-activate step.`

#### AI Suggested Improvement

- Add `--no-switch` flag to `tab-new` to preserve current behavior, but default to auto-switching
- Print the tab index in `tab-new` output (currently only shows guid and url), so the user knows which index to use with `tab-select`
- Add the tab index to the `tab-list` JSON output more prominently

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `htmlsnapshot inspect --max 3 --depth 2` returns insufficient selectors for product detail page

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

```
goto http://localhost:18080/ec/dp/B0E000001
htmlsnapshot
htmlsnapshot inspect --max 3 --depth 2
```

#### Expected Behavior

Should discover selectors for product title, price, description, and image — the key elements on a product page.

#### Actual Behavior

Only discovered `tr`, `th`, `td` within `#product-specs` (the specs table). Did not surface `h1` (title), `#product-price`, `li` (features), or `img` (image). Had to manually probe selectors to find them.

#### Root Cause Analysis

`inspect` works by finding "repeating patterns" (sibling groups). On a product detail page with mostly singular elements (one title, one price, one image), there are few repeating patterns to discover. The specs table has repeating `tr` rows, so that's what `inspect` latched onto. The tool is optimized for listing/search result pages, not detail pages.

#### Code Pointer

`The `inspect` algorithm likely needs a fallback mode for detail pages that surfaces high-scoring singular elements from the summary index.`

#### AI Suggested Improvement

- When `inspect` finds no/few repeating patterns, fall back to showing the top-N highest-scoring elements from `htmlsnapshot summary` with their CSS selectors
- Add a dedicated `htmlsnapshot selectors` command that emits all unique CSS selector paths from the summary index
- Document that `inspect` is designed for listing pages and suggest `summary` + manual probing for detail pages

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: MockSite home page has no individual product links

**Severity:** Low
**Category:** Product

#### Reproduction

```
goto http://localhost:18080/ec/
snapshot -v 0
```

#### Expected Behavior

The e-commerce home page should feature product links (e.g., "Featured Products", "Best Sellers", "Deals") that allow direct navigation to product detail pages.

#### Actual Behavior

The home page only contains 20 category navigation links (Electronics, Home, Garden, etc.) and a footer. No individual product links exist. Users must navigate through a category first to reach products.

#### Root Cause Analysis

The MockSite home page template only includes category navigation. The SKILL.md's e-commerce scenario examples assume a richer home page with product cards.

#### Code Pointer

`MockSite HTML template for the `/ec/` route.`

#### AI Suggested Improvement

- Add a "Featured Products" or "Best Sellers" section to the MockSite home page with direct product links
- This would make the MockSite a better test bed for e-commerce scraping scenarios

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `screenshot` positional argument conflicts with filename intent

**Severity:** Low
**Category:** UX / Discoverability

#### Reproduction

```
screenshot electronics-listing.png
```

#### Expected Behavior

`screenshot` with a string argument should save with that filename.

#### Actual Behavior

The string is interpreted as an element `[ref]`. The correct way to set a filename is `screenshot --filename electronics-listing.png`. This was only discovered by running `screenshot --help`.

#### Root Cause Analysis

The positional `[ref]` argument and `--filename` flag serve different purposes, but a new user naturally types `screenshot <filename>`. The help output shows `screenshot [ref]` which doesn't hint at `--filename`.

#### AI Suggested Improvement

- Add a tip to the `screenshot` output mentioning `--filename` for custom names
- Consider making the first positional argument work as a filename when it doesn't match a snapshot ref pattern (e.g., contains `.png`)
- Add `--filename` / `-o` examples to the `--help` output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `snapshot -v 0` and `snapshot -i` produce identical output for small pages

**Severity:** Low
**Category:** UX

#### Reproduction

```
goto http://localhost:18080/ec/
snapshot -v 0
snapshot -i
```
Compare the two snapshot files — they have identical node counts (75) and structure.

#### Expected Behavior

`snapshot -i` (interactive) should produce a visibly different, more compact view focused on actionable elements.

#### Actual Behavior

For a small page with only links as interactive elements, both modes produce nearly identical output. The semantic difference between `-v 0` (full accessibility tree) and `-i` (interactive elements only) is not visible in the output — both show the full tree.

#### Root Cause Analysis

The MockSite home page has no non-interactive content between interactive elements (no large text blocks, no decorative images). With only links, there's nothing to filter out. The `-i` flag works correctly but the test page doesn't demonstrate the difference.

#### AI Suggested Improvement

- Add a line to `-i` output explaining what was filtered out (e.g., "Filtered 0 non-interactive nodes; 75 remain")
- The SKILL.md could mention this edge case: small/all-interactive pages won't show a difference

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: MockSite product links contain literal escaped quotes in URLs

```
goto http://localhost:18080/ec/
click e38  (Electronics category)
click e340 (first product link)
```
Or inspect `a[href]` values in the listing page HTML.

#### Issue 2: `htmlsnapshot` fails on listing page after `tab-new` + `tab-select`

```
goto http://localhost:18080/ec/dp/B0E000001    # product page
tab-new http://localhost:18080/ec/b?node=1292115012   # new tab
tab-select 0                                    # switch to listing
htmlsnapshot                                    # FAILS
```

#### Issue 3: `tab-new` does not auto-switch to the new tab

```
goto http://localhost:18080/ec/dp/B0E000001
tab-new http://localhost:18080/ec/b?node=1292115012
snapshot    # still shows product page, not listing page
```

#### Issue 4: `htmlsnapshot inspect --max 3 --depth 2` returns insufficient selectors for product detail page

```
goto http://localhost:18080/ec/dp/B0E000001
htmlsnapshot
htmlsnapshot inspect --max 3 --depth 2
```

#### Issue 5: MockSite home page has no individual product links

```
goto http://localhost:18080/ec/
snapshot -v 0
```

#### Issue 6: `screenshot` positional argument conflicts with filename intent

```
screenshot electronics-listing.png
```

#### Issue 7: `snapshot -v 0` and `snapshot -i` produce identical output for small pages

```
goto http://localhost:18080/ec/
snapshot -v 0
snapshot -i
```
Compare the two snapshot files — they have identical node counts (75) and structure.

