---
title: "HTML Snapshot Scenarios — Amazon Discovery & Extraction"
description: "End-to-end Amazon workflows: home page discovery with summary + inspect, search results extraction, and product detail page extraction. Covers discovery-first patterns that work across Amazon locales and layout changes."
tier: procedure
---

# HTML Snapshot Scenarios — Amazon Discovery & Extraction

These three scenarios form a complete Amazon extraction workflow: discover the home page structure → extract search results → extract product details. Each scenario is self-contained and emphasizes a **discovery-first** approach — use `summary` and `inspect` to find selectors before committing to extraction queries.

> **Note:** CSS selectors are tied to live websites and may break over time. See [SKILL.md §5](../SKILL.md#5-critical-warnings). Always run `summary` + `inspect` first when targeting a new locale or product category.
>
> **Last verified:** 2026-07-10 (Amazon.com, US locale). Selectors may differ by locale, device, or after Amazon layout updates.

> **Parent document:** [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips, and command reference.

## Scenarios

| # | Scenario | Primary Commands | Key Pattern |
|---|----------|------------------|-------------|
| 14 | Amazon Home Page Discovery | `summary`, `inspect` | Structure overview before interaction |
| 15 | Amazon Search Results Extraction | `summary`, `inspect`, `get all`, `query` | Discovery → validate → extract |
| 16 | Amazon Product Detail Extraction | `summary`, `inspect`, `get`, `grep`, `export` | Full product page data collection |

---

## 14. Amazon Home Page Discovery

> ⚠️ **Always run `htmlsnapshot inspect` first** to verify selectors on your locale. Amazon's DOM structure changes frequently and varies by region. Selectors shown below were verified on 2026-07-10 (Amazon.com, US locale).

**Problem:** You land on `amazon.com` and need to quickly understand the page structure — where is the search box? What navigation categories exist? What content blocks (recommendations, deals, featured products) are present?

**Why HTML Snapshot:** `summary` generates a compressed WPSI that distills the page to its structural essence — headings, forms, lists, tables, and key content blocks — without drowning you in HTML. `inspect` then reveals the CSS selectors for the interactive and repeated elements you care about. Together they eliminate manual exploration on a page with 2000+ text nodes and dozens of sections.

### 14a. Get a bird's-eye view with summary

```bash
# Navigate to Amazon's home page
browser4-cli goto "https://www.amazon.com"
browser4-cli htmlsnapshot

# Generate the WPSI summary
browser4-cli htmlsnapshot summary
```

**Output (abridged — actual output is YAML):**
```yaml
url: https://www.amazon.com
title: "Amazon.com. Spend less. Smile more."
metaDescription: "Free shipping on millions of items. Get the best of Shopping and Entertainment with Prime..."
headings:
  - level: h1
    text: "Amazon"
  - level: h2
    text: "Today's Deals"
  - level: h2
    text: "Recommendations"
  - level: h2
    text: "Top categories"
  - level: h2
    text: "New & interesting finds"
forms: 2
tables: 18
lists: 24
textStats:
  totalTextNodes: 2143
  totalTextChars: 142890
keyContent:
  - selector: "#nav-search-bar-form"
    textPreview: "Search Amazon"
    textLength: 24
  - selector: "#nav-xshop"
    textPreview: "Today's Deals  Customer Service  Gift Cards  Sell"
    textLength: 98
  - selector: "#gw-card-container"
    textPreview: "Shop by Category  Electronics  Home  Kitchen  Books"
    textLength: 412
```

**Why `summary` here:** The summary instantly reveals that Amazon's home page has 2 forms (the search box and probably a sign-in), 18 tables (product grids and comparison sections), and 24 lists (navigation and recommendations). The `headings` section shows the page's content sections at a glance. The `keyContent` blocks identify the most text-dense regions — the search form (`#nav-search-bar-form`), the navigation bar (`#nav-xshop`), and the main content grid (`#gw-card-container`). Without `summary`, you would need to scroll through thousands of lines of HTML or visually scan a heavily cluttered page.

### 14b. Discover structural patterns with inspect

```bash
# Auto-discover the page's most prominent repeating patterns
browser4-cli htmlsnapshot inspect

# Then narrow down to discover navigation structure
browser4-cli htmlsnapshot inspect "#nav-xshop"
```

**Output (example):**
```
### Inspect: ":root" (78 matching containers, 10 analyzed)

  Sample structure (3 of 10):
  -- Container 1: div#nav-belt
      div#nav-logo
       a#nav-logo-sprites  ""
      div#nav-search-bar-form
       div.nav-search-field
        input#twotabsearchtextbox  ""
       div.nav-search-submit
        input.nav-input[type="submit"]  "Go"
      div#nav-tools
       span#nav-link-accountList  "Hello, sign in"
  -- Container 2: div#nav-main
      div#nav-xshop
       a.nav-a             "Today's Deals"
       a.nav-a             "Customer Service"
       a.nav-a             "Gift Cards"
       a.nav-a             "Sell"

  Suggested selectors (recurring across containers):
   10/10 (100%)  h2                                              → "Today's Deals"
    8/10 ( 80%)  h2 + div a[aria-label]                         → "Deal of the Day"
    6/10 ( 60%)  div[data-component-type="s-desktop-slot"]      → (slot-based content)

### Inspect: "#nav-xshop" (15 matching elements, 10 analyzed)

  Suggested selectors (recurring across matches):
   10/10 (100%)  a.nav-a                                         → "Today's Deals"
   10/10 (100%)  a[data-nav-tab]                                 → "Customer Service"
    8/10 ( 80%)  span.nav-icon-text                              → "Gift Cards"
```

### 14c. Locate the search box — two approaches

The search box is the most critical interactive element on the home page. Here are two ways to find it:

```bash
# Approach 1: From the WPSI summary keyContent, we see the search form
browser4-cli htmlsnapshot get html "#nav-search-bar-form"

# Approach 2: Use the interactive snapshot to find it by role
browser4-cli snapshot | grep -i search
```

**Why `summary` + `inspect` before extraction:** On a page as large as Amazon's home page (2000+ text nodes, 18 tables), manually reading HTML or guessing selectors is impractical. `summary` condenses the page to its skeleton — you see that there are 2 forms and the search bar lives inside `#nav-search-bar-form`. `inspect` then reveals the exact selectors for the search input (`input#twotabsearchtextbox`) and the Go button (`input.nav-input[type="submit"]`). Once you know these selectors, you can either fill the form or — more reliably — navigate directly to search results using URL injection (see Scenario 15).

> **Note:** Amazon's home page is notoriously heavy (often >2 MB of HTML, 2000+ DOM nodes). The WPSI summary is typically <1% of the original HTML size, making it practical for LLM consumption even on the largest pages.

---

## 15. Amazon Search Results Extraction

> ⚠️ **Always run `htmlsnapshot inspect` first** to verify selectors on your locale. Amazon's DOM structure changes frequently and varies by region. Selectors shown below were verified on 2026-07-10 (Amazon.com, US locale).

**Problem:** You've searched Amazon for a product category and need to extract titles, prices, ratings, and image URLs from the search results page. The DOM is complex and you don't know the selectors ahead of time. You need a repeatable discovery-to-extraction workflow.

**Why HTML Snapshot:** `summary` confirms you are on a search-results page and reveals the result count. `inspect` discovers the repeating card structure and suggests selectors with coverage percentages — no manual HTML reading needed. `get all` validates the suggested selectors on real data. `query` then extracts structured data in a single X-SQL pass.

### Step 0: Auto-discover selectors (always do this first)

Before using any documented selectors, verify the current page structure:

```bash
browser4-cli htmlsnapshot inspect
```

This discovers the repeating card CSS classes, element hierarchy, and suggested selectors with coverage percentages — adapting to whatever Amazon's current DOM looks like for your locale and product category. Documented selectors like `.s-result-item[data-component-type='s-search-result']` are a starting point; actual selectors may differ.

### 15a. Navigate and confirm page type with summary

URL injection bypasses Amazon's problematic search form — the `press Enter` approach fails because Amazon intercepts form submission with custom JavaScript:

```bash
# Use URL injection to navigate directly to search results
browser4-cli goto "https://www.amazon.com/s?k=wireless+mouse"

# Capture the HTML snapshot
browser4-cli htmlsnapshot

# Confirm it's a search-results page and see the structure
browser4-cli htmlsnapshot summary
```

**Output (example):**
```yaml
url: https://www.amazon.com/s?k=wireless+mouse
title: "Amazon.com: wireless mouse"
headings:
  - level: h1
    text: "Results"
  - level: h2
    text: "Sponsored"
  - level: h2
    text: "Related searches"
forms: 4
tables: 1
lists: 48
keyContent:
  - selector: ".s-main-slot"
    textPreview: "Results  Price and other details may vary based on product..."
    textLength: 560
```

The summary confirms: this is a search-results page (h1 "Results"), there are 48 list items (roughly the number of products), and the main content lives in `.s-main-slot`.

### 15b. Discover selectors with inspect

```bash
# Auto-discover repeating containers (finds .s-result-item automatically)
browser4-cli htmlsnapshot inspect

# Narrow to the search result cards using the Amazon-specific data attribute
browser4-cli htmlsnapshot inspect ".s-result-item[data-component-type='s-search-result']"
```

**Output (example):**
```
### Inspect: ".s-result-item[data-component-type='s-search-result']" (48 matches, 10 analyzed)

  Sample structure (3 of 48):
  -- Element 1: div.s-result-item[data-component-type="s-search-result"]
      div.s-card-container
       div.a-section
        h2.a-size-mini.a-spacing-none
         a.a-link-normal.s-underline-text    "Logitech M720 Triathlon"
        div.a-row
         a.a-link-normal.s-no-hover
          i.a-icon-star-small
           span.a-icon-alt                  "4.6 out of 5 stars"
          span.a-size-base                  "2,345"
        div.a-row.a-spacing-micro
         a.a-link-normal
          span.a-price
           span.a-offscreen                 "$34.99"
        div.s-image
         img.s-image                        "https://m.media-amazon.com/images/I/..."

  Suggested selectors (recurring across matches):
   10/10 (100%)  h2 a.a-link-normal                              → "Logitech M720..."
   10/10 (100%)  span.a-icon-alt                                 → "4.6 out of 5 stars"
   10/10 (100%)  span.a-offscreen                                → "$34.99"
   10/10 (100%)  img.s-image                                     → (src attr)
    9/10 ( 90%)  a.a-link-normal.s-underline-text                → (same as h2 a)
    8/10 ( 80%)  span.a-size-base                                → "2,345"
```

### 15c. Validate and extract with get all

Take the suggested selectors and validate them before committing to a full X-SQL query:

```bash
# Validate titles
browser4-cli htmlsnapshot get all text "h2 a.a-link-normal" --limit 5
# → ["Logitech M720 Triathlon", "Logitech MX Master 3S", "Razer Basilisk X HyperSpeed", ...]

# Validate prices
browser4-cli htmlsnapshot get all text "span.a-offscreen" --limit 5
# → ["$34.99", "$99.99", "$59.99", ...]

# Validate ratings
browser4-cli htmlsnapshot get all text "span.a-icon-alt" --limit 5
# → ["4.6 out of 5 stars", "4.7 out of 5 stars", "4.5 out of 5 stars", ...]

# Validate image URLs
browser4-cli htmlsnapshot get all attr "img.s-image" src --limit 3
# → ["https://m.media-amazon.com/images/I/61kU1j...", ...]
```

Once validated, extract all fields in bulk:

```bash
# Full extraction — titles, prices, ratings
browser4-cli htmlsnapshot get all text "h2 a.a-link-normal"
browser4-cli htmlsnapshot get all text "span.a-offscreen"
browser4-cli htmlsnapshot get all text "span.a-icon-alt"

# Full extraction — image URLs
browser4-cli htmlsnapshot get all attr "img.s-image" src
```

**Why `get all` here:** Unlike `htmlsnapshot get` (which returns only the first match), `get all` returns a JSON array of all matching elements. This is ideal for validating that your discovered selectors actually work across the full result set before writing a structured query.

> **Note:** Why not just use `get all` for everything? Each `get all` call scans the entire document independently. If you run `get all text "h2 a"` (69 titles) and `get all text ".a-offscreen"` (91 prices), the two arrays have different lengths and can't be aligned — some products lack prices, some prices belong to non-product elements. Step 15d solves this with `DOM_LOAD_AND_SELECT` scoped to `.s-result-item`, so each row's fields stay together.

### 15d. Structured extraction with X-SQL query

For the most efficient single-command workflow, combine all fields into one X-SQL query:

```bash
browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, 'h2 a.a-link-normal') AS title,
    DOM_FIRST_TEXT(dom, 'span.a-offscreen') AS price,
    DOM_FIRST_TEXT(dom, 'span.a-icon-alt') AS rating,
    DOM_FIRST_ATTR(dom, 'img.s-image', 'src') AS image_url
  FROM DOM_LOAD_AND_SELECT(@url, '.s-result-item[data-component-type=s-search-result]')
  WHERE DOM_FIRST_TEXT(dom, 'h2 a.a-link-normal') IS NOT NULL
"
```

**Why `inspect` before `query` here:** The inspect output gave you the exact selectors with 100% recurrence guarantees. Without inspect, you would have to guess selectors or read raw HTML — a slow and error-prone process.

### 15e. Save results for trend tracking

```bash
# Export the full page HTML for archival or later re-extraction
browser4-cli htmlsnapshot export --file "amazon-search-wireless-mouse-$(date +%Y%m%d).html"

# For scheduled monitoring, combine with load options (see audit scenarios)
echo "
  SELECT
    DOM_FIRST_TEXT(dom, 'h2 a.a-link-normal') AS title,
    DOM_FIRST_TEXT(dom, 'span.a-offscreen') AS price
  FROM DOM_LOAD_AND_SELECT(@url, '.s-result-item[data-component-type=\"s-search-result\"]')
  WHERE DOM_FIRST_TEXT(dom, 'h2 a.a-link-normal') IS NOT NULL
" > search-results.sql

browser4-cli htmlsnapshot query "
  https://www.amazon.com/s?k=wireless+mouse -i 1h -njr 3
" --sql @search-results.sql
```

> **Known Amazon quirks for search results:**
> - **URL injection is required** — `press Enter` on the search box fails on Amazon due to their custom JavaScript handlers. Always navigate to `amazon.com/s?k=<query>` or use `click` on the Go button.
> - **Locale variations** — Amazon's CSS classes differ by locale (e.g., `a-link-normal` naming varies, `data-component-type` attributes may differ on Amazon.co.uk, Amazon.de, etc.). If selectors fail, re-run `inspect` on your locale's page.
> - **Split price DOM** — Amazon renders prices as `.a-price-whole` (dollars) and `.a-price-fraction` (cents) in separate elements. The `.a-offscreen` selector used here captures the combined screen-reader text and is more reliable for extraction.
> - **Sponsored products** — Search results may include sponsored products with different DOM structure. Use the X-SQL `WHERE` clause to filter after extraction, or inspect the sponsored sections separately.
> - **Client-side rendering** — Some Amazon regions use JavaScript to lazy-load prices and images. Add `-njr 3` to load options if fields appear empty after capture.

---

## 16. Amazon Product Detail Page Extraction

> ⚠️ **Always run `htmlsnapshot inspect` first** to verify selectors on your locale. Amazon's DOM structure changes frequently and varies by region. Selectors shown below were verified on 2026-07-10 (Amazon.com, US locale).

**Problem:** You need to extract comprehensive product data — title, price, rating, feature bullets, technical specifications, stock status, brand, and images — from an Amazon product detail page. The DOM is deeply nested with multiple sections. You need a discovery-first workflow to find the right selectors, then extract and archive the data.

**Why HTML Snapshot:** `summary` reveals the page's structural sections at a glance (tables for specs, lists for features, forms for buying options). `inspect` drills into specific sections to reveal exact CSS selectors. `get` extracts data using those discovered selectors. `grep` provides instant presence checks for stock badges, deal labels, and other non-structured indicators. `export` archives the full page for offline analysis and price-trend tracking.

### Step 0: Auto-discover selectors (always do this first)

Before using any documented selectors, verify the current page structure:

```bash
browser4-cli htmlsnapshot inspect
```

Amazon product page layouts vary by category, locale, and over time. Run `htmlsnapshot inspect` first to discover current selectors for title, price, rating, feature bullets, and specs on your specific product page.

### 16a. Discover page structure with summary

```bash
# Navigate to the product page (use your target ASIN)
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"
browser4-cli htmlsnapshot

# Get the WPSI summary
browser4-cli htmlsnapshot summary
```

**Output (example):**
```yaml
url: https://www.amazon.com/dp/B08PP5MSVB
title: "Apple AirPods Pro (2nd Generation) Wireless Earbuds, Up to 2X More Active Noise Cancelling..."
metaDescription: "Amazon.com: Apple AirPods Pro (2nd Generation) ..."
headings:
  - level: h1
    text: "Apple AirPods Pro (2nd Generation)"
  - level: h2
    text: "About this item"
  - level: h2
    text: "Product information"
  - level: h2
    text: "Customer reviews"
  - level: h2
    text: "Compare with similar items"
forms: 1
tables: 2
lists: 6
keyContent:
  - selector: "#productTitle"
    textPreview: "Apple AirPods Pro (2nd Generation)"
    textLength: 42
  - selector: "#feature-bullets"
    textPreview: "Active Noise Cancellation, Adaptive Transparency..."
    textLength: 890
  - selector: "#productDetails_techSpec_section_1"
    textPreview: "Brand  Apple  Manufacturer  Apple  Model Name  AirPods Pro  ..."
    textLength: 1240
```

The summary reveals: the product title lives in `#productTitle`, there are 2 tables (technical specs + pricing), 6 lists (feature bullets, related products), and the feature bullets section is the largest text block. This tells you exactly where to look before writing any selectors.

### 16b. Inspect key sections

```bash
# Inspect the main content column — title, price, rating, brand
browser4-cli htmlsnapshot inspect "#centerCol"

# Inspect the feature bullets section
browser4-cli htmlsnapshot inspect "#feature-bullets"

# Inspect the technical specifications table
browser4-cli htmlsnapshot inspect "#productDetails_techSpec_section_1"
```

**Output (example):**
```
### Inspect: "#centerCol" (1 element)

  -- Element: div#centerCol
       h1#title.a-size-large    "Apple AirPods Pro (2nd Generation)"
        span#productTitle       "Apple AirPods Pro (2nd Generation)"
       div#averageCustomerReviews
        i.a-icon-star
         span.a-icon-alt        "4.6 out of 5 stars"
        span#acrCustomerReviewText  "12,345"
       div#corePriceDisplay_desktop_feature_div
        span.a-price
         span.a-offscreen       "$199.99"
       div#availability
        span.a-declarative      "In Stock"
       div#bylineInfo
        a#bylineInfo            "Apple"

### Inspect: "#feature-bullets" (8 list items)

  Suggested selectors:
    8/8 (100%)  ul.a-unordered-list li span.a-list-item   → "Active Noise Cancellation..."
    8/8 (100%)  ul.a-unordered-list li                    → "Sweat and water resistant..."
```

### 16c. Extract all fields with get

Now that `inspect` has revealed the CSS selectors, extract each field:

```bash
# Title
browser4-cli htmlsnapshot get text "#productTitle"

# Price
browser4-cli htmlsnapshot get text ".a-price .a-offscreen"

# Rating
browser4-cli htmlsnapshot get text "#acrCustomerReviewText"

# Product image
browser4-cli htmlsnapshot get attr "#landingImage" src

# Brand / manufacturer
browser4-cli htmlsnapshot get text "#bylineInfo"

# Availability
browser4-cli htmlsnapshot get text "#availability"
```

**Output (example):**
```
Apple AirPods Pro (2nd Generation)
$199.99
4.6 out of 5 stars — 12,345 ratings
https://m.media-amazon.com/images/I/61SUj2aKoEL._AC_SL1500_.jpg
Apple
In Stock
```

### 16d. Extract feature bullets and technical specs

```bash
# All feature bullets (using the suggested selector from inspect)
browser4-cli htmlsnapshot get all text "#feature-bullets ul.a-unordered-list li span.a-list-item"
# → ["Active Noise Cancellation...", "Sweat and water resistant...", "Adaptive Transparency...", ...]

# Structured feature extraction with X-SQL
browser4-cli htmlsnapshot query --sql "
  SELECT DOM_FIRST_TEXT(dom, 'span.a-list-item') AS feature
  FROM DOM_LOAD_AND_SELECT(@url, '#feature-bullets li')
  WHERE DOM_FIRST_TEXT(dom, 'span.a-list-item') IS NOT NULL
"

# Technical specification table — get the full HTML
browser4-cli htmlsnapshot get html "#productDetails_techSpec_section_1"

# Or extract specs as structured data with X-SQL
browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, 'th') AS spec_name,
    DOM_FIRST_TEXT(dom, 'td') AS spec_value
  FROM DOM_LOAD_AND_SELECT(@url, '#productDetails_techSpec_section_1 tr')
  WHERE DOM_FIRST_TEXT(dom, 'th') IS NOT NULL
"
```

### 16e. Quick presence checks with grep

For instant pass/fail checks that do not require writing CSS selectors:

```bash
# Check stock status
browser4-cli htmlsnapshot grep -l -F "In Stock" | grep -q htmlsnapshot && echo "IN STOCK" || echo "OUT OF STOCK"

# Check for promotional badges
browser4-cli htmlsnapshot grep -i 'limited time deal|lightning deal'

# Check for "Amazon's Choice" or "Best Seller" badges
browser4-cli htmlsnapshot grep -i "Amazon's Choice|Best Seller|#1 Best Seller"

# Count images to verify the gallery loaded
browser4-cli htmlsnapshot grep -c '<img[^>]*id="landingImage"'
# → 1

# Check for coupon or promotion
browser4-cli htmlsnapshot grep -i 'coupon|save.*off|extra savings' --selector "#centerCol"
```

**Why `grep` here:** Product pages often have badges or status indicators (Best Seller, Amazon's Choice, Limited Time Deal, Coupon clipped to the price) that are not part of your standard extraction selectors. `grep` lets you instantly check for their presence without writing new CSS selectors or queries. Use `--selector` to scope the search to a specific page region and avoid false matches from footer or sidebar content.

### 16f. Export for archival and price-change detection

```bash
# Export the full page for offline reference
browser4-cli htmlsnapshot export --file "airpods-pro-2-$(date +%Y%m%d).html"

# Revisit the same product a week later
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot export --file "airpods-pro-2-$(date +%Y%m%d).html"

# Diff the exported files to detect changes
diff airpods-pro-2-20260622.html airpods-pro-2-20260629.html
# Or grep the new export for specific price changes
browser4-cli htmlsnapshot grep -o '\$[0-9]+\.[0-9]{2}'
```

> **Why start with summary and inspect instead of jumping to extraction?** Scenario 1 demonstrated direct extraction using pre-known selectors. But when you encounter an unfamiliar product page — a different category, a new layout version, or an international Amazon locale — you cannot rely on assumptions. By starting with `summary` (structure overview) and `inspect` (selector discovery), you make the workflow robust against Amazon's frequent A/B tests and layout changes. Running `summary` before extraction is like reading the table of contents before diving into a book.
>
> **Known Amazon quirks for product pages:**
> - **Split price DOM** — Amazon renders the dollar and cent portions in separate elements (`.a-price-whole` and `.a-price-fraction`). The `.a-offscreen` selector captures the combined screen-reader text, which is the most reliable single-source extraction. If `.a-offscreen` is empty, use X-SQL: `DOM_FIRST_TEXT(dom, '.a-price-whole') || '.' || DOM_FIRST_TEXT(dom, '.a-price-fraction')`.
> - **JS-rendered prices** — In some locales (Amazon.in, Amazon.com.au, Amazon.co.uk), prices may be lazy-loaded via JavaScript. If `get text ".a-price .a-offscreen"` returns empty, re-capture with `-njr 3` to force a server-rendered fallback.
> - **Variable table ID** — The tech specs table ID (`#productDetails_techSpec_section_1`) varies by product category. Use `table.a-keyvalue.prodDetTable` as a more general fallback selector.
> - **Product page A/B tests** — Amazon frequently runs A/B tests on product page layouts. The `#centerCol` selector may be replaced by `#leftCol` or an entirely different structure on some product categories or for some logged-in users. Always run `summary` + `inspect` first if your selectors unexpectedly fail.
> - **Dynamic availability text** — The `#availability` element content changes based on stock status ("In Stock", "Only 3 left in stock", "Currently unavailable", "Usually ships within 2 to 3 days"). Use `grep` to check for any of these patterns rather than relying on exact text matching.

---

## See Also

- [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips
- [htmlsnapshot-scenarios-extraction.md](htmlsnapshot-scenarios-extraction.md) — e-commerce, news, jobs, academic, real estate extraction
- [htmlsnapshot-scenarios-audit.md](htmlsnapshot-scenarios-audit.md) — SEO, compliance, CI, pricing, incident response
- [htmlsnapshot-scenarios-advanced.md](htmlsnapshot-scenarios-advanced.md) — summary, inspect, and agent form discovery
- [htmlsnapshot.md](htmlsnapshot.md) — full command reference
