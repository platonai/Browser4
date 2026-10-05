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

## Quick Start

Every Amazon scenario runs the same discovery-first loop:

```bash
browser4-cli goto "https://www.amazon.com"                  # or /s?k=<query>, or /dp/<ASIN>
browser4-cli htmlsnapshot                                   # capture the page
browser4-cli htmlsnapshot summary                           # WPSI: structure overview
browser4-cli htmlsnapshot inspect [selector]                # discover selectors + coverage
browser4-cli htmlsnapshot get all text "<css>" --limit 5    # validate a selector
browser4-cli htmlsnapshot query --sql "<sql>"               # extract correlated fields
```

## When to Use

Use these recipes for Amazon product research, price/availability tracking, or any extraction against Amazon's catalog. The parent [scenario index](htmlsnapshot-scenarios.md) compares all scenario families: for other e-commerce sites use [htmlsnapshot-scenarios-extraction.md](htmlsnapshot-scenarios-extraction.md), and for presence-only or regression checks use [htmlsnapshot-scenarios-audit.md](htmlsnapshot-scenarios-audit.md).

## How It Works

`summary` compresses a page into a Web Page Summary Index (WPSI) — headings, form/table/list counts and the most text-dense content blocks, usually a small fraction of the original HTML (the ratio varies with page structure; dense listing pages compress far less). `inspect` then samples the elements matching a candidate selector and ranks their child selectors by how many of those elements each one recurs in, so you commit to a selector only after seeing its coverage. Extraction follows one of two shapes everywhere: `get` / `get all` for individual fields, and `query` with `DOM_LOAD_AND_SELECT` for per-row correlated fields. Because Amazon lazy-loads prices and images with JavaScript and A/B-tests its layouts, the loop is re-run per locale and per product category instead of trusting a stored selector list.

## Patterns

| # | Scenario | Primary Commands | Key Pattern |
|---|----------|------------------|-------------|
| 14 | Amazon Home Page Discovery | `summary`, `inspect` | Structure overview before interaction |
| 15 | Amazon Search Results Extraction | `summary`, `inspect`, `get all`, `query` | Discovery → validate → extract |
| 16 | Amazon Product Detail Extraction | `summary`, `inspect`, `get`, `grep`, `export` | Full product page data collection |

Each numbered recipe below is self-contained. Documented selectors are a starting point, not a contract — re-run `inspect` on your locale and product category before reusing them (verified 2026-07-10, Amazon.com, US locale).

---

## Flags

| Flag | Used in these recipes for |
|------|---------------------------|
| `inspect <selector>` | 14b, 16b — drill into one container (`#nav-xshop`, `#centerCol`, `#feature-bullets`) |
| `get all … --limit <n>` | 15c — validate a discovered selector on the first N matches |
| `query --sql "<sql>"` | 15d, 16d — extract per-row correlated fields with `DOM_LOAD_AND_SELECT` |
| `query … --sql @file.sql` | 15e — keep scheduled queries out of the shell's quoting rules |
| `grep -l -F "<text>"` | 16e — binary presence check for a stock badge |
| `grep -i "<regex>"` | 16e — case-insensitive promotion/badge search |
| `grep -c` | 16e — count matches (verify the image gallery loaded) |
| `grep --selector <css>` | 16e — scope the search to a page region, avoiding footer/sidebar false matches |
| `export --file <name>` | 15e, 16f — archive raw HTML for offline analysis and diffing |
| `-i <duration>` | 15e — cache validity inside the load-options string (`-i 1h` reuses the cached page for an hour) |
| `-njr <n>` | 15e, 16c — immediate-retry count for a single fetch (`-njr 3`), the documented workaround when lazy-loaded prices or images come back empty |

Full flag reference: [htmlsnapshot.md](htmlsnapshot.md).

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| Search box `press Enter` does nothing | Amazon intercepts form submission with custom JavaScript | Navigate by URL injection (`amazon.com/s?k=<query>`) or `click` the Go button |
| Documented selectors match nothing | Locale differences (`a-link-normal` naming varies, `data-component-type` may differ on .co.uk / .de) | Re-run `inspect` on your locale's page |
| Price is only dollars or only cents | Amazon splits prices into `.a-price-whole` and `.a-price-fraction` | Read the combined screen-reader text from `.a-offscreen`; if empty, concatenate both parts in X-SQL |
| Prices or images come back empty | The region lazy-loads them with JavaScript, so a fetch can return before the content appears | Re-capture with the `-njr 3` load option (up to 3 immediate retries within one fetch) |
| Sponsored or unrelated rows pollute results | Sponsored products use a different DOM structure, and non-product elements match the field selectors | Filter in the X-SQL `WHERE` clause after extraction, or inspect the sponsored sections separately |
| Title and price arrays don't line up | Each `get all` scans the whole document independently, so field arrays have different lengths | Extract per row with `DOM_LOAD_AND_SELECT(@url, '.s-result-item…')` scoped to the card |
| Tech spec table not found | `#productDetails_techSpec_section_1` varies by product category | Fall back to `table.a-keyvalue.prodDetTable` |
| `#centerCol` selectors vanish on some visits | Amazon A/B-tests product page layouts (`#leftCol` on some categories or logged-in users) | Re-run `summary` + `inspect` instead of trusting stored selectors |
| Availability check misses the status | `#availability` text varies ("In Stock", "Only 3 left in stock", "Currently unavailable") | `grep` for the alternatives instead of matching one exact string |

Practical recipes for Amazon discovery, search-results extraction, and product detail extraction using `htmlsnapshot summary`, `inspect`, `get`, `query`, `grep`, and `export`.

## 14. Amazon Home Page Discovery

**Problem:** You land on `amazon.com` and need to quickly understand the page structure — where is the search box? What navigation categories exist? What content blocks (recommendations, deals, featured products) are present?

**Why HTML Snapshot:** `summary` generates a compressed WPSI that distills the page to its structural essence — headings, forms, lists, tables, and key content blocks — without drowning you in HTML. `inspect` then reveals the CSS selectors for the interactive and repeated elements you care about. Together they eliminate manual exploration on a page with 2000+ text nodes and dozens of sections.

### 14a. Get a bird's-eye view with summary

```bash
browser4-cli goto "https://www.amazon.com"    # navigate to Amazon's home page
browser4-cli htmlsnapshot                     # capture the page
browser4-cli htmlsnapshot summary             # generate the WPSI summary
```

**Output (abridged — actual output is YAML):**
```yaml
url: https://www.amazon.com
title: "Amazon.com. Spend less. Smile more."
headings:
  - level: h1
    text: "Amazon"
  - level: h2
    text: "Today's Deals"
  - level: h2
    text: "Top categories"
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
browser4-cli htmlsnapshot inspect              # auto-discover the most prominent repeating patterns
browser4-cli htmlsnapshot inspect "#nav-xshop" # narrow down to the navigation structure
```

**Output (example):**
```text
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

```bash
# Approach 1: from the WPSI summary keyContent, read the search form's HTML
browser4-cli htmlsnapshot get html "#nav-search-bar-form"

# Approach 2: find it by role in the interactive snapshot
browser4-cli snapshot | grep -i search
```

**Why `summary` + `inspect` before extraction:** On a page as large as Amazon's home page (2000+ text nodes, 18 tables), manually reading HTML or guessing selectors is impractical. `summary` condenses the page to its skeleton — you see that there are 2 forms and the search bar lives inside `#nav-search-bar-form`. `inspect` then reveals the exact selectors for the search input (`input#twotabsearchtextbox`) and the Go button (`input.nav-input[type="submit"]`). Once you know these selectors, you can either fill the form or — more reliably — navigate directly to search results using URL injection (see Scenario 15).

> **Note:** Amazon's home page is notoriously heavy (often >2 MB of HTML, 2000+ DOM nodes). The WPSI summary collapses boilerplate-heavy pages like this to a small fraction of the HTML, making them practical for LLM consumption; the exact ratio depends on page structure, and dense listing pages compress far less.

---

## 15. Amazon Search Results Extraction

**Problem:** You've searched Amazon for a product category and need to extract titles, prices, ratings, and image URLs from the search results page. The DOM is complex and you don't know the selectors ahead of time. You need a repeatable discovery-to-extraction workflow.

**Why HTML Snapshot:** `summary` confirms you are on a search-results page and reveals the result count. `inspect` discovers the repeating card structure and suggests selectors with coverage percentages — no manual HTML reading needed. `get all` validates the suggested selectors on real data. `query` then extracts structured data in a single X-SQL pass.

### 15a. Navigate and confirm page type with summary

URL injection bypasses Amazon's problematic search form — the `press Enter` approach fails because Amazon intercepts form submission with custom JavaScript:

```bash
browser4-cli goto "https://www.amazon.com/s?k=wireless+mouse"
browser4-cli htmlsnapshot                     # capture the HTML snapshot
browser4-cli htmlsnapshot summary             # confirm it's a search-results page
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
browser4-cli htmlsnapshot inspect              # auto-discovery finds .s-result-item automatically
browser4-cli htmlsnapshot inspect ".s-result-item[data-component-type='s-search-result']"
```

**Output (example):**
```text
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
    8/10 ( 80%)  span.a-size-base                                → "2,345"
```

### 15c. Validate the discovered selectors with get all

Validate before committing to a full X-SQL query — `get all` returns a JSON array of every match, unlike `get`, which returns only the first:

```bash
# Spot-check each field on the first few matches
browser4-cli htmlsnapshot get all text "h2 a.a-link-normal" --limit 5
# → ["Logitech M720 Triathlon", "Logitech MX Master 3S", "Razer Basilisk X HyperSpeed", ...]

browser4-cli htmlsnapshot get all text "span.a-offscreen" --limit 5
# → ["$34.99", "$99.99", "$59.99", ...]

browser4-cli htmlsnapshot get all text "span.a-icon-alt" --limit 5
# → ["4.6 out of 5 stars", "4.7 out of 5 stars", "4.5 out of 5 stars", ...]

browser4-cli htmlsnapshot get all attr "img.s-image" src --limit 3
# → ["https://m.media-amazon.com/images/I/61kU1j...", ...]

# Once validated, extract every field in bulk (drop --limit)
browser4-cli htmlsnapshot get all text "h2 a.a-link-normal"
browser4-cli htmlsnapshot get all text "span.a-offscreen"
browser4-cli htmlsnapshot get all text "span.a-icon-alt"
browser4-cli htmlsnapshot get all attr "img.s-image" src
```

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

# For scheduled monitoring, save the 15d query to search-results.sql and run it with
# load options: -i 1h sets an hourly cache, -njr 3 retries a fetch whose lazy-loaded
# pricing came back empty. --sql @file.sql also keeps nested quotes out of the shell
# (see shell-quoting.md)
browser4-cli htmlsnapshot query "
  https://www.amazon.com/s?k=wireless+mouse -i 1h -njr 3
" --sql @search-results.sql
```

---

## 16. Amazon Product Detail Page Extraction

**Problem:** You need to extract comprehensive product data — title, price, rating, feature bullets, technical specifications, stock status, brand, and images — from an Amazon product detail page. The DOM is deeply nested with multiple sections. You need a discovery-first workflow to find the right selectors, then extract and archive the data.

**Why HTML Snapshot:** `summary` reveals the page's structural sections at a glance (tables for specs, lists for features, forms for buying options). `inspect` drills into specific sections to reveal exact CSS selectors. `get` extracts data using those discovered selectors. `grep` provides instant presence checks for stock badges, deal labels, and other non-structured indicators. `export` archives the full page for offline analysis and price-trend tracking.

### 16a. Discover page structure with summary

```bash
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"   # use your target ASIN
browser4-cli htmlsnapshot                                   # capture the page
browser4-cli htmlsnapshot summary                           # get the WPSI summary
```

**Output (example):**
```yaml
url: https://www.amazon.com/dp/B08PP5MSVB
title: "Apple AirPods Pro (2nd Generation) Wireless Earbuds, Up to 2X More Active Noise Cancelling..."
headings:
  - level: h1
    text: "Apple AirPods Pro (2nd Generation)"
  - level: h2
    text: "About this item"
  - level: h2
    text: "Product information"
  - level: h2
    text: "Customer reviews"
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
browser4-cli htmlsnapshot inspect "#centerCol"                        # title, price, rating, brand
browser4-cli htmlsnapshot inspect "#feature-bullets"                  # feature bullets
browser4-cli htmlsnapshot inspect "#productDetails_techSpec_section_1"  # specs table
```

**Output (example):**
```text
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
browser4-cli htmlsnapshot get text "#productTitle"           # title
browser4-cli htmlsnapshot get text ".a-price .a-offscreen"   # price
browser4-cli htmlsnapshot get text "#acrCustomerReviewText"  # rating
browser4-cli htmlsnapshot get attr "#landingImage" src       # product image
browser4-cli htmlsnapshot get text "#bylineInfo"             # brand / manufacturer
browser4-cli htmlsnapshot get text "#availability"           # availability
```

**Output (example):**
```text
Apple AirPods Pro (2nd Generation)
$199.99
4.6 out of 5 stars — 12,345 ratings
https://m.media-amazon.com/images/I/61SUj2aKoEL._AC_SL1500_.jpg
Apple
In Stock
```

If the price comes back empty, re-capture with `-njr 3` (see [Errors & Recovery](#errors--recovery)).

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

# Technical specification table — full HTML, or structured rows with X-SQL
browser4-cli htmlsnapshot get html "#productDetails_techSpec_section_1"

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
# Check stock status as a pass/fail: -l prints only whether matches exist, -F matches literally
browser4-cli htmlsnapshot grep -l -F "In Stock" | grep -q htmlsnapshot && echo "IN STOCK" || echo "OUT OF STOCK"

# Check for promotional badges, "Amazon's Choice", or "Best Seller"
browser4-cli htmlsnapshot grep -i 'limited time deal|lightning deal'
browser4-cli htmlsnapshot grep -i "Amazon's Choice|Best Seller|#1 Best Seller"

# Count images to verify the gallery loaded
browser4-cli htmlsnapshot grep -c '<img[^>]*id="landingImage"'
# → 1

# Check for a coupon or promotion, scoped to the main content column
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
browser4-cli htmlsnapshot grep '[$][0-9]+\.[0-9]{2}'   # literal $ written [$] — survives shell quoting
```

**Why start with `summary` and `inspect` instead of jumping to extraction?** Scenario 1 demonstrated direct extraction using pre-known selectors. But when you encounter an unfamiliar product page — a different category, a new layout version, or an international Amazon locale — you cannot rely on assumptions. Starting with `summary` (structure overview) and `inspect` (selector discovery) makes the workflow robust against Amazon's frequent A/B tests and layout changes.

---

## See Also

- [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips
- [htmlsnapshot-scenarios-extraction.md](htmlsnapshot-scenarios-extraction.md) — e-commerce, news, jobs, academic, real estate extraction
- [htmlsnapshot-scenarios-audit.md](htmlsnapshot-scenarios-audit.md) — SEO, compliance, CI, pricing, incident response
- [htmlsnapshot-scenarios-advanced.md](htmlsnapshot-scenarios-advanced.md) — summary, inspect, and agent form discovery
- [htmlsnapshot.md](htmlsnapshot.md) — full command reference
