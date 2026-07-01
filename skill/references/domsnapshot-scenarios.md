---
title: "DOM Snapshot — Real-World Scenarios"
description: "Practical end-to-end recipes using all domsnapshot subcommands: get, query, grep, summary, export, and inspect. Covers e-commerce, news, SEO, pricing, job boards, compliance, research, real estate, CI, incident response, Amazon, and agent-assisted workflows."
---

# DOM Snapshot — Real-World Scenarios

Practical, end-to-end recipes using the `domsnapshot` family of commands. Each scenario is self-contained: you can adapt the CSS selectors and X-SQL queries to your own target pages.

> **⚠️ CSS selectors are tied to live websites — they WILL break over time.** The selectors shown in these examples (e.g. `#productTitle`, `.s-result-item`, `.a-price .a-offscreen`) worked at the time of writing but are not guaranteed to work today. Websites change their HTML structure, class names, and element IDs without notice. **Treat these examples as patterns, not copy-paste recipes.** Before running a scenario, use [`domsnapshot inspect`](#13-selector-discovery-for-unknown-pages) or [`domsnapshot summary`](#14-amazon-home-page-discovery) to discover the current selectors on your target page, then adapt the query structure shown here.

## Scenario Index

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 1 | E-Commerce Product Monitoring | `get`, `query` | Retail |
| 2 | News Headline Aggregator | `domsnapshot`, `get`, `export` | Media |
| 3 | SEO Health Audit | `query` (X-SQL), `grep` | Marketing |
| 4 | Competitive Price Tracker | `query` (X-SQL + load options) | Business |
| 5 | Job Board Scraper | `get`, `query`, `grep` | HR / Recruiting |
| 6 | Compliance Verification | `get`, `export`, `grep` | Legal / Governance |
| 7 | Academic Literature Metadata Extraction | `query` (X-SQL) | Research |
| 8 | Real Estate Listing Monitor | `get`, `query` | Property |
| 9 | CI/E2E Visual Regression Snapshot | `domsnapshot`, `export`, `grep` | Engineering |
| 10 | Agent-Assisted Form Discovery | `get` + Agent CLI | AI / Automation |
| 11 | Page Structure Analysis | `summary` | Research / Auditing |
| 12 | Incident Response & Debugging | `grep` | Engineering / SRE |
| 13 | Selector Discovery for Unknown Pages | `inspect` | Research / Scraping |
| 14 | Amazon Home Page Discovery | `summary`, `inspect` | Retail |
| 15 | Amazon Search Results Extraction | `summary`, `inspect`, `get all`, `query` | Retail |
| 16 | Amazon Product Detail Extraction | `summary`, `inspect`, `get`, `grep`, `export` | Retail |

---

## 1. E-Commerce Product Monitoring

**Problem:** Your team needs to track product availability, pricing, and review counts across a competitor's catalog — daily. Manually visiting pages is not scalable.

**Why DOM Snapshot:** CSS selectors give you precise extraction of structured fields (title, price, rating, stock status). X-SQL handles multi-product listing pages in a single query.

### 1a. Extract a single product's details

```bash
# Navigate to the product page and capture a DOM snapshot
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"
browser4-cli domsnapshot

# Pull individual fields from the cached snapshot
browser4-cli domsnapshot get text "#productTitle"
browser4-cli domsnapshot get text ".a-price .a-offscreen"
browser4-cli domsnapshot get text "#acrCustomerReviewText"
browser4-cli domsnapshot get attr "#landingImage" src
```

**Output (example):**
```
Apple AirPods Pro (2nd Generation)
$199.99
4.6 out of 5 stars — 12,345 ratings
https://m.media-amazon.com/images/I/61SUj2aKoEL._AC_SL1500_.jpg
```

### 1b. Query a search-results page with X-SQL

```bash
# Step 1: Navigate and capture the DOM snapshot
browser4-cli goto "https://www.amazon.com/s?k=mechanical+keyboard"
browser4-cli domsnapshot

# Step 2: Query the cached snapshot with X-SQL
# -i 1h caches the load for 1 hour — critical for avoiding quota exhaustion
browser4-cli domsnapshot query "https://www.amazon.com/s?k=mechanical+keyboard -i 1h" --sql "
  SELECT
    dom_first_text(dom, 'h2 .a-link-normal') AS title,
    dom_first_text(dom, '.a-price .a-offscreen') AS price,
    dom_first_text(dom, '.a-icon-alt') AS rating,
    dom_first_attr(dom, 'img.s-image', 'src') AS image_url
  FROM load_and_select(@url, '.s-result-item[data-component-type=s-search-result]')
  WHERE dom_first_text(dom, 'h2 .a-link-normal') IS NOT NULL
"
```

**Why this works:** The explicit `domsnapshot` capture step avoids a redundant page load (without it, `load_and_select` would re-fetch the URL). The `-i 1h` load option caches the server response for 1 hour — essential when iterating on a query or running it repeatedly, as Amazon aggressively rate-limits rapid requests. The `dom_*` UDFs extract fields from each search-result card. When `@url` is used, pass the full URL with load options directly to `domsnapshot query` instead of relying on the current page URL (which lacks caching controls).

> **⚠️ Quota warning:** Amazon and similar sites detect rapid repeated requests and will return CAPTCHAs or 503 errors. Always use `-i 1h` (or longer) when iterating on a query. If you see empty results or bot-detection pages, wait 5–10 minutes before retrying. See [Scenario 15](#15-amazon-search-results-extraction) for a full discovery-to-extraction workflow that minimizes quota burn by validating selectors on the cached snapshot before running X-SQL queries.

### 1c. Export for offline analysis

```bash
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"
browser4-cli domsnapshot
browser4-cli domsnapshot export --file=product-page-$(date +%Y%m%d).html
# Later: grep, parse, or diff against yesterday's export
diff product-page-20260621.html product-page-20260622.html
```

---

## 2. News Headline Aggregator

**Problem:** You need the top 10 headlines, bylines, and timestamps from a news homepage every morning, formatted as structured data.

**Why DOM Snapshot:** X-SQL can extract multiple articles from a listing page in one shot. The `export` command preserves the raw HTML for archival or later re-parsing with different selectors.

### 2a. One-shot extraction with X-SQL

```bash
browser4-cli goto "https://news.ycombinator.com"

browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, '.titleline > a') AS headline,
    dom_base_uri(dom) AS article_url,
    dom_first_text(dom, '.score') AS points,
    dom_first_text(dom, '.hnuser') AS author
  FROM load_and_select(@url, 'tr.athing')
  WHERE dom_first_text(dom, '.titleline > a') IS NOT NULL
"
```

### 2b. Quick extraction with `get` for a single section

```bash
browser4-cli goto "https://www.bbc.com/news"

# `get text` returns the first match of the CSS selector
browser4-cli domsnapshot get text ".gs-c-promo-heading__title"
# For multiple headlines, use `query` with X-SQL (see Scenario 1b pattern)
```

### 2c. Archive for trend analysis

```bash
browser4-cli goto "https://news.ycombinator.com"
browser4-cli domsnapshot
browser4-cli domsnapshot export --file="headlines-$(date +%Y%m%d-%H%M).html"
# Build a corpus over weeks; run NLP pipelines on the exported HTML
```

---

## 3. SEO Health Audit

**Problem:** An SEO specialist needs to verify that every page on a site has exactly one `<h1>`, all images have `alt` attributes, and no links are broken — across dozens of URLs.

**Why DOM Snapshot:** X-SQL can answer structural questions declaratively. No need to write a custom scraper.

### 3a. Check heading hierarchy

```bash
browser4-cli goto "https://example.com/blog/some-post"

browser4-cli domsnapshot query --sql "
  SELECT
    'H1 count' AS check_name,
    CAST(COUNT(*) AS VARCHAR) AS value
  FROM load_and_select(@url, 'h1')
  UNION ALL
  SELECT
    'H2 count',
    CAST(COUNT(*) AS VARCHAR)
  FROM load_and_select(@url, 'h2')
"
```

### 3b. Find images missing alt text

```bash
browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_attr(dom, 'img', 'src') AS image_src,
    dom_first_attr(dom, 'img', 'alt') AS alt_text
  FROM load_and_select(@url, 'img:not([alt])')
"
```

### 3c. Extract all meta tags

```bash
browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_attr(dom, 'meta[name=description]', 'content') AS meta_description,
    dom_first_attr(dom, 'meta[name=keywords]', 'content') AS meta_keywords,
    dom_first_attr(dom, 'link[rel=canonical]', 'href') AS canonical_url,
    dom_first_text(dom, 'title') AS title_tag
  FROM load_and_select(@url, ':root')
"
```

### 3d. List all outbound links

```bash
browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, 'a') AS link_text,
    dom_first_attr(dom, 'a', 'href') AS href,
    dom_first_attr(dom, 'a', 'rel') AS rel
  FROM load_and_select(@url, 'a[href^=http]')
"
```

### 3e. Quick grep-based checks

When you don't need structured output, `grep` gives instant answers without writing SQL:

```bash
browser4-cli goto "https://example.com/blog/some-post"
browser4-cli domsnapshot

# Count how many <h1> tags exist (SEO: should be exactly 1)
browser4-cli domsnapshot grep -c '<h1[>\s]'
# → 1

# Find images missing alt text
browser4-cli domsnapshot grep -c '<img[^>]*alt=""'
# → 3  (3 images have empty alt — fix them)

# Check for meta description (pass/fail for CI)
browser4-cli domsnapshot grep -l -F '<meta name="description"' | grep -q domsnapshot && echo PASS || echo FAIL
# exit code 0 = found

# Count total links on the page
browser4-cli domsnapshot grep -c '<a[>\s]'
# → 142

# Scope to <head> only — find all meta tags in one shot
browser4-cli domsnapshot grep --selector head '<meta'
# → 5:<meta charset="utf-8">
# → 6:<meta name="viewport" content="width=device-width">
# → 7:<meta name="description" content="...">
```

**Why `grep` wins here:** For presence/absence checks and counting, `grep` is faster to write and runs client-side without touching the X-SQL backend. Use `query` when you need structured extraction (field names, tabular output) and `grep` for quick checks, counting, and debugging.

---

## 4. Competitive Price Tracker

**Problem:** Track pricing changes across 5 competitor product pages. Re-run every 6 hours. Cache the page for 1 hour to avoid hammering the server. Disable JS rendering for speed (prices are in the server-rendered HTML).

**Why DOM Snapshot:** Load options (`-i 1h -njr 3`) control caching and rendering behavior. X-SQL lets you extract exactly the fields you need.

### 4a. Single product query with load options

```bash
# The -i 1h caches the page for 1 hour; -njr 3 skips JS rendering (3 retries)
browser4-cli domsnapshot query "https://competitor.com/product/123 -i 1h -njr 3" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '.product-price') AS price,
    dom_first_text(dom, '.stock-status') AS stock,
    dom_first_text(dom, 'h1') AS title
  FROM load_and_select(@url, 'body')
"
```

### 4b. Batch query across multiple URLs

Save the query to a file:

**pricing.sql:**
```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, 'h1') AS product,
  dom_first_text(dom, '.price') AS price
FROM load_and_select(@url, 'body')
```

Then run it against each URL:

```bash
for url in \
  "https://competitor.com/p/123 -i 1h" \
  "https://competitor.com/p/456 -i 1h" \
  "https://competitor.com/p/789 -i 1h"
do
  browser4-cli domsnapshot query "$url" --sql @pricing.sql
done
```

### 4c. Cron-driven monitoring

```bash
# In crontab: run every 6 hours at 3 minutes past the hour
3 */6 * * * cd /path/to/project && ./scripts/track-prices.sh >> prices.log
```

---

## 5. Job Board Scraper

**Problem:** A recruiter wants to extract all "Senior Frontend Engineer" postings from a job board into a structured CSV — job title, company, location, salary range (if listed), and posting date.

**Why DOM Snapshot:** Listings are repeated structures on a single page — perfect for X-SQL's `load_and_select`.

### 5a. Full extraction with X-SQL

```bash
browser4-cli goto "https://www.linkedin.com/jobs/search?keywords=senior%20frontend"

browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, '.job-card-list__title') AS title,
    dom_first_text(dom, '.job-card-container__company-name') AS company,
    dom_first_text(dom, '.job-card-container__metadata-item') AS location,
    dom_first_text(dom, '.job-search-card__salary-info') AS salary
  FROM load_and_select(@url, '.job-card-container')
  WHERE dom_first_text(dom, '.job-card-list__title') IS NOT NULL
"
```

### 5b. Quick text extraction for a single field

```bash
browser4-cli domsnapshot get text ".jobs-search-results__list-item"
```

### 5c. Quick filtering with grep

Skip the export — search the snapshot directly with `grep` for instant filtering:

```bash
browser4-cli goto "https://www.linkedin.com/jobs/search?keywords=senior%20frontend"
browser4-cli domsnapshot

# Find all "Senior" level positions
browser4-cli domsnapshot grep -i 'senior'

# Count remote vs on-site
browser4-cli domsnapshot grep -c -i 'remote'
browser4-cli domsnapshot grep -c -i 'on.site'

# Scope search to job cards only for cleaner results
browser4-cli domsnapshot grep --selector ".job-card-container" -i 'react'
```

### 5d. Save and process

```bash
browser4-cli goto "https://www.linkedin.com/jobs/search?keywords=senior%20frontend"
browser4-cli domsnapshot
browser4-cli domsnapshot export --file=jobs.html
# For deeper processing, feed the exported HTML to your preferred parser
```

---

## 6. Compliance Verification

**Problem:** A compliance officer must verify that every page in a financial-services site displays the required legal disclaimer, cookie consent banner, and accessibility statement link — before every release.

**Why DOM Snapshot:** `get` with CSS selectors returns deterministic pass/fail answers. `export` creates an auditable artifact.

### 6a. Verify required elements exist

```bash
browser4-cli goto "https://bank.example.com/products/savings"
browser4-cli domsnapshot

# Query the cached snapshot for required elements
# Check for legal disclaimer
browser4-cli domsnapshot get text ".legal-disclaimer"
# Returns the disclaimer text — exit code 0 means it was found

# Check for cookie banner
browser4-cli domsnapshot get text "#cookie-consent-banner"
# Returns empty string if missing — check with: [ -n "$result" ]

# Check accessibility statement link
browser4-cli domsnapshot get attr "a[href*='accessibility']" href
```

### 6b. Batch verification script

```bash
#!/bin/bash
# verify-compliance.sh — run against a list of URLs
PAGES=(
  "/products/savings"
  "/products/checking"
  "/products/loans"
  "/about/terms"
)

REQUIRED=(
  ".legal-disclaimer"
  "#cookie-consent-banner"
  "a[href*='accessibility']"
  "a[href*='privacy']"
)

for page in "${PAGES[@]}"; do
  browser4-cli goto "https://bank.example.com$page"
  browser4-cli domsnapshot

  for selector in "${REQUIRED[@]}"; do
    result=$(browser4-cli domsnapshot get text "$selector" 2>/dev/null)
    if [ -z "$result" ]; then
      echo "FAIL: $page — missing $selector"
      exit 1
    fi
  done
  echo "PASS: $page"
done
```

### 6c. Quick compliance check with grep

For fast ad-hoc checks, `grep` answers presence/absence questions without writing scripts:

```bash
browser4-cli goto "https://bank.example.com/products/savings"
browser4-cli domsnapshot

# Verify required legal text exists anywhere on the page
browser4-cli domsnapshot grep -l -F "FDIC Insured" | grep -q domsnapshot && echo "PASS" || echo "FAIL"
browser4-cli domsnapshot grep -l -F "Terms and Conditions" | grep -q domsnapshot && echo "PASS" || echo "FAIL"

# Check for forbidden content (tracking scripts, data leaks)
browser4-cli domsnapshot grep -i 'gtag|fbq|_gaq' && echo "WARNING: trackers found"

# Scope to footer for legal links
browser4-cli domsnapshot grep --selector footer -i 'privacy|accessibility|terms'
# → <a href="/privacy">Privacy Policy</a>
# → <a href="/accessibility">Accessibility Statement</a>
# → <a href="/terms">Terms of Service</a>

# Count how many cookie consent elements exist (should be 1)
browser4-cli domsnapshot grep -c -F 'cookie-consent'
# → 1
```

**Why `grep` here:** For compliance, you often need to answer "is this text present?" instantly. The `-l` flag (files-with-matches) prints "domsnapshot" when matches exist — pipe to `grep -q domsnapshot` for a pass/fail exit code. Use `--selector` to scope to specific page regions (footer, nav, main).

### 6d. Archive for audit trail

```bash
browser4-cli goto "https://bank.example.com/products/savings"
browser4-cli domsnapshot
browser4-cli domsnapshot export --file="compliance-$(date +%Y%m%d)-savings.html"
# Store in versioned S3 bucket for regulatory audit
```

---

## 7. Academic Literature Metadata Extraction

**Problem:** A researcher needs to extract paper titles, authors, publication dates, and abstract snippets from 50 search-result pages on Google Scholar or PubMed — for a systematic literature review.

**Why DOM Snapshot:** X-SQL can extract from each result card on a search page. No need for a Python/Scrapy stack.

### 7a. PubMed search extraction

```bash
browser4-cli goto "https://pubmed.ncbi.nlm.nih.gov/?term=machine+learning+drug+discovery&sort=date"

browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, '.docsum-title') AS title,
    dom_first_text(dom, '.full-author-list') AS authors,
    dom_first_text(dom, '.docsum-journal-citation') AS citation,
    dom_first_attr(dom, '.docsum-title', 'href') AS link
  FROM load_and_select(@url, '.docsum-content')
  WHERE dom_first_text(dom, '.docsum-title') IS NOT NULL
"
```

### 7b. Extract abstract from individual paper page

```bash
browser4-cli goto "https://pubmed.ncbi.nlm.nih.gov/12345678/"
browser4-cli domsnapshot

# Extract fields from the cached snapshot
browser4-cli domsnapshot get text ".abstract-content"
browser4-cli domsnapshot get text "#full-view-heading"
```

### 7c. Bulk processing loop

```bash
#!/bin/bash
# Extract metadata from a list of PubMed IDs
for pmid in 12345678 23456789 34567890; do
  browser4-cli goto "https://pubmed.ncbi.nlm.nih.gov/$pmid/"
  browser4-cli domsnapshot
  echo "=== PMID: $pmid ==="
  browser4-cli domsnapshot get text ".heading-title"
  browser4-cli domsnapshot get text ".abstract-content"
  echo ""
done
```

---

## 8. Real Estate Listing Monitor

**Problem:** A home buyer wants to track new listings in a neighborhood — address, price, bedrooms, square footage, and listing URL — and be notified when a property matching their criteria appears.

**Why DOM Snapshot:** X-SQL extracts a structured table of listings from a single search page. CSS selectors target specific data fields.

### 8a. Extract all listings from a search

```bash
browser4-cli goto "https://www.zillow.com/homes/san-francisco_rb/"

browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, '[data-test=property-card-address]') AS address,
    dom_first_text(dom, '[data-test=property-card-price]') AS price,
    dom_first_text(dom, '.beds-container') AS beds,
    dom_first_text(dom, '.baths-container') AS baths,
    dom_first_text(dom, '.sqft-container') AS sqft
  FROM load_and_select(@url, '[data-test=property-card]')
"
```

### 8b. Quick check: did anything change?

```bash
browser4-cli goto "https://www.zillow.com/homes/san-francisco_rb/"
browser4-cli domsnapshot
browser4-cli domsnapshot get text "[data-test='property-card-address']" > todays-listings.txt
diff yesterdays-listings.txt todays-listings.txt
```

### 8c. Filter with X-SQL expressions

```bash
browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, '[data-test=property-card-address]') AS address,
    dom_first_text(dom, '[data-test=property-card-price]') AS price
  FROM load_and_select(@url, 'article:expr(dom_first_text(dom, .beds) >= 3)')
  WHERE dom_first_text(dom, '[data-test=property-card-address]') IS NOT NULL
"
```

---

## 9. CI / E2E Visual Regression Snapshot

**Problem:** After every frontend deploy, the QA pipeline must capture the DOM state of 10 critical pages and compare them against known-good baselines. The accessibility-tree `snapshot` won't work here — we need the actual DOM structure.

**Why DOM Snapshot:** `domsnapshot` captures raw HTML — the source of truth for DOM structure. `export` with `--file` writes it to disk for `diff`. Runs in CI without a display.

### 9a. Capture and diff in CI

```bash
#!/bin/bash
# ci-dom-regression.sh — runs in GitHub Actions / Jenkins

BASELINE_DIR="./snapshots/baseline"
CURRENT_DIR="./snapshots/current"

PAGES=(
  "https://staging.example.com/"
  "https://staging.example.com/products"
  "https://staging.example.com/about"
  "https://staging.example.com/contact"
)

for url in "${PAGES[@]}"; do
  slug=$(echo "$url" | sed 's/[^a-zA-Z0-9]/_/g')
  browser4-cli goto "$url"
  browser4-cli domsnapshot
  browser4-cli domsnapshot export --file="$CURRENT_DIR/${slug}.html"
done

# Diff current against baseline
diff -r "$BASELINE_DIR" "$CURRENT_DIR" > dom-diff.txt

if [ -s dom-diff.txt ]; then
  echo "DOM regression detected!"
  cat dom-diff.txt
  exit 1
fi

echo "No DOM regression — all pages match baseline."
```

### 9b. Promote current to baseline after approval

```bash
rm -rf ./snapshots/baseline
cp -r ./snapshots/current ./snapshots/baseline
git add ./snapshots/baseline
git commit -m "chore: update DOM snapshot baselines"
```

### 9c. Check specific elements after deploy

```bash
browser4-cli goto "https://staging.example.com/checkout"
browser4-cli domsnapshot

# Verify critical elements rendered (queries the cached snapshot)
browser4-cli domsnapshot get text "#cart-total"        # Must exist
browser4-cli domsnapshot get attr "#checkout-btn" href  # Must be /checkout
browser4-cli domsnapshot get text ".item-count"         # Must be > 0
```

### 9d. Fast smoke test with grep

For a lightweight CI smoke test that just checks critical strings are present:

```bash
browser4-cli goto "https://staging.example.com/checkout"
browser4-cli domsnapshot

# Verify checkout page has all required sections (exits non-zero if any missing)
browser4-cli domsnapshot grep -l -F "Cart Total" | grep -q domsnapshot || exit 1
browser4-cli domsnapshot grep -l -F "Shipping Address" | grep -q domsnapshot || exit 1
browser4-cli domsnapshot grep -l -F "Place Order" | grep -q domsnapshot || exit 1

# Ensure no error messages leaked to the page
browser4-cli domsnapshot grep -i 'error|exception|stack trace' && exit 1

# Scope to <main> to check only the content area
browser4-cli domsnapshot grep --selector main -l -F "Order Summary" | grep -q domsnapshot || exit 1

echo "Smoke test passed"
```

**Why `grep` for CI:** The `-l` flag prints "domsnapshot" when matches exist — pipe to `grep -q domsnapshot` for a 0/1 exit code based on match presence. Use `-F` for literal strings (no regex escaping needed) and `--selector` to scope to specific regions.

---

## 10. Agent-Assisted Form Discovery

**Problem:** An AI agent needs to understand a complex multi-step form (tax filing, insurance application, loan origination) — what fields exist, what's required, what options are in each `<select>`. The agent uses `domsnapshot get` to discover the form structure before filling it.

**Why DOM Snapshot:** Unlike the accessibility-tree `snapshot` (which shows roles and names), `domsnapshot get` extracts raw DOM attributes — `required`, `pattern`, `minlength`, `placeholder`, `<option>` values — that are essential for correct form filling.

### 10a. Discover all form fields and their attributes

```bash
browser4-cli goto "https://example.com/insurance/application"
browser4-cli domsnapshot

# List all input names and types (reads from the cached snapshot)
browser4-cli domsnapshot get attr "form input[name]" name
browser4-cli domsnapshot get attr "form input[name]" type
browser4-cli domsnapshot get attr "form input[name]" required

# Extract all select options
browser4-cli domsnapshot get text "form select[name='state'] option"
# Returns: AL, AK, AZ, AR, CA, CO, CT, ...

# Check validation constraints
browser4-cli domsnapshot get attr "form input[name='zip']" pattern
browser4-cli domsnapshot get attr "form input[name='zip']" maxlength
```

### 10b. Get full form HTML for LLM analysis

```bash
browser4-cli domsnapshot get html "form#application"
# Returns the entire form's inner HTML — feed this to an LLM for semantic understanding
```

### 10c. Agent orchestration pattern

```bash
# Step 1: Capture a DOM snapshot and discover the form
browser4-cli domsnapshot
FORM_HTML=$(browser4-cli domsnapshot get html "form")

# Step 2: Ask the LLM to analyze the form and generate fill commands
# (the agent framework handles this; domsnapshot supplies the raw DOM data)

# Step 3: Agent fills the form using the interactive snapshot + click/type/fill
browser4-cli snapshot
browser4-cli type "John Doe" "#full-name"
browser4-cli type "john@example.com" "#email"
browser4-cli select "CA" "#state"
```

---

## 11. Page Structure Analysis with Summary (WPSI)

**Problem:** An auditor or researcher needs a quick, AI-readable overview of a page's structure — headings, forms, tables, key content blocks, and statistics — without reading the full HTML or writing selectors.

**Why DOM Snapshot:** `summary` generates a Web Page Summary Index (WPSI) — a deterministic compressed page summary (typically <1% of original HTML) in YAML format. It's designed for LLM consumption and quick human review.

### 11a. Generate a page summary

```bash
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
browser4-cli domsnapshot
browser4-cli domsnapshot summary
```

**Output (example — actual output is YAML):**
```yaml
url: https://en.wikipedia.org/wiki/Web_scraping
title: "Web scraping - Wikipedia"
metaDescription: "Web scraping, web harvesting, or web data extraction is..."
headings:
  - level: h1
    text: "Web scraping"
  - level: h2
    text: "History"
  - level: h2
    text: "Techniques"
forms: 0
tables: 3
lists: 12
textStats:
  totalTextNodes: 847
  totalTextChars: 52341
keyContent:
  - selector: "#mw-content-text > div.mw-parser-output > p:nth-child(5)"
    textPreview: "Web scraping is the process of automatically..."
    textLength: 342
```

### 11b. Compare page structures across a site

Use `summary` to detect structural drift across pages — missing headings, extra forms, changed layouts:

```bash
#!/bin/bash
# audit-structure.sh — compare page summaries across a site
PAGES=("/about" "/products" "/pricing" "/contact")

for path in "${PAGES[@]}"; do
  browser4-cli goto "https://example.com$path"
  browser4-cli domsnapshot
  echo "=== $path ==="
  browser4-cli domsnapshot summary
  echo ""
done
# Pipe summaries to an LLM: "Compare these page summaries and flag structural inconsistencies"
```

### 11c. Pre-screen before deep extraction

```bash
# 1. Get a summary to understand the page structure
browser4-cli goto "https://example.com/products"
browser4-cli domsnapshot
browser4-cli domsnapshot summary
# → reveals: tables=1, lists=5, headings at h2, no forms

# 2. Now write targeted X-SQL knowing the structure
browser4-cli domsnapshot query --sql "
  SELECT dom_first_text(dom, 'h2') AS category, dom_first_text(dom, 'li') AS item
  FROM load_and_select(@url, 'ul.product-list')
"
```

**Why `summary` here:** It answers "what's on this page?" without you writing a single selector. Use it as a discovery step before committing to specific `get` or `query` calls. Especially useful for unfamiliar pages.

---

## 12. Incident Response & Debugging with Grep

**Problem:** During an incident, an SRE or developer needs to quickly search a rendered page for error messages, broken elements, leaked secrets, or unexpected content — fast, without writing SQL or loading tools.

**Why DOM Snapshot:** `grep` searches the full DOM snapshot HTML client-side with familiar grep semantics. No backend round-trip for the search itself. All standard grep flags work: `-i`, `-v`, `-c`, `-A`/`-B`/`-C`, `-F`, `-w`.

### 12a. Find error messages on a broken page

```bash
browser4-cli goto "https://app.example.com/dashboard"
browser4-cli domsnapshot

# Search for common error patterns (case-insensitive)
browser4-cli domsnapshot grep -i 'error|exception|failed|timeout|500|503'

# Show 3 lines of context around each error for debugging
browser4-cli domsnapshot grep -i -C 3 'error|exception'

# Scope to <main> to ignore nav/footer noise
browser4-cli domsnapshot grep --selector main -i -C 2 'stack trace'
```

### 12b. Detect leaked secrets or sensitive data

```bash
browser4-cli goto "https://app.example.com/settings"
browser4-cli domsnapshot

# Check for common secret patterns (access keys, tokens, private keys)
browser4-cli domsnapshot grep -i 'AKIA[0-9A-Z]{16}' && echo "WARNING: AWS key pattern found"
browser4-cli domsnapshot grep -i 'sk-[a-zA-Z0-9]{32,}' && echo "WARNING: API key pattern found"
browser4-cli domsnapshot grep -i 'BEGIN.*PRIVATE KEY' && echo "WARNING: Private key in page"

# Look for internal hostnames or IPs leaked to the frontend
browser4-cli domsnapshot grep -i '\.internal|\.local|10\.\d+\.\d+\.\d+'
```

### 12c. Verify post-deploy content

After a deploy, confirm specific content appeared/disappeared:

```bash
browser4-cli goto "https://staging.example.com"
browser4-cli domsnapshot

# Confirm the new feature flag is on (fail if not found)
browser4-cli domsnapshot grep -l -F 'feature.new-checkout.enabled' | grep -q domsnapshot || exit 1

# Confirm debug info is NOT in the rendered HTML (fail if found)
browser4-cli domsnapshot grep -l -F 'debugMode' | grep -vq domsnapshot && exit 1

# Show the 2 lines around the version tag to verify deploy
browser4-cli domsnapshot grep -C 2 -F 'v2.14.1'
# → 45:  <meta name="version" content="v2.14.1">
# → 46:  <meta name="build-time" content="2026-06-27T14:30:00Z">
```

### 12d. Inverted and targeted search — find what's NOT there

```bash
# Show only non-empty lines (strip blank lines for readability)
browser4-cli domsnapshot grep -v '^\s*$'

# Find all <img> tags WITHOUT alt attributes (grep then filter with standard grep)
browser4-cli domsnapshot grep '<img[^>]*>' | while read line; do
  if ! echo "$line" | grep -q 'alt='; then
    echo "Missing alt: $line"
  fi
done

# Find links missing rel="nofollow" (grep then exclude matches)
browser4-cli domsnapshot grep --selector main '<a[^>]*href="http[^"]*"[^>]*>' | grep -v 'rel='
```

**Why `grep` for incident response:** It's the fastest path from "is X on the page?" to an answer. No SQL, no selectors, no backend load — just regex against the cached snapshot. The grep-style flags (`-A`/`-B`/`-C` for context, `-v` for inverse, `-c` for counting, `-F` for literal strings) match what every developer already knows.

---

## 13. Selector Discovery for Unknown Pages with Inspect

**Problem:** You land on an unfamiliar page (e.g., a competitor's e-commerce search results, a job board, or a news aggregator) and need to extract structured data — but you don't know the CSS selectors for product titles, prices, ratings, or other recurring fields. Guessing selectors or manually reading HTML is slow and error-prone.

**Why DOM Snapshot:** `inspect` analyzes the DOM structure and suggests CSS selectors for recurring patterns across matching elements. It's deterministic, requires no AI, and works on any page where content repeats in a consistent structure.

### 13a. Discover selectors on an e-commerce listing page

```bash
# Navigate to the page and capture a snapshot
browser4-cli goto "https://books.toscrape.com"
browser4-cli domsnapshot

# Inspect the product listing — find the repeating product cards
browser4-cli domsnapshot inspect ".product_pod"
```

**Output (example):**
```
### Inspect: ".product_pod" (20 matches, 10 analyzed)

  Sample structure (3 of 20):
  -- Element 1: article.product_pod
      img.thumbnail
      h3            ""
       a            "A Light in the Attic"
      div.product_price
       p.price_color  "£51.77"
      p.instock.availability  "In stock"
  ...

  Suggested selectors (recurring across matches):
   10/10 (100%)  h3 a                                         → "A Light in the..."
   10/10 (100%)  img.thumbnail                                → ""
   10/10 (100%)  p.price_color                                → "£51.77"
    8/10 ( 80%)  p.instock.availability                       → "In stock"
```

### 13b. Use discovered selectors for extraction

Take the selectors from `inspect` and feed them directly into `domsnapshot get all` or `domsnapshot query`:

```bash
# Extract all product titles using the suggested selector
browser4-cli domsnapshot get all text ".product_pod h3 a"

# Extract all prices
browser4-cli domsnapshot get all text ".product_pod p.price_color"

# Or run a structured X-SQL query with the discovered selectors
browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, 'h3 a') AS title,
    dom_first_text(dom, 'p.price_color') AS price,
    dom_first_text(dom, 'p.instock.availability') AS availability
  FROM load_and_select(@url, '.product_pod')
"
```

### 13c. Narrow scope for complex pages

On larger pages, start broad then narrow down:

```bash
# Step 1: See what repeating containers exist
browser4-cli domsnapshot inspect

# Step 2: Inspect just the search results area
browser4-cli domsnapshot inspect ".s-result-item"

# Step 3: For deeply nested structures, increase depth
browser4-cli domsnapshot inspect ".job-card" --depth 6 --max 20
```

### 13d. Workflow: discover → extract → validate

```bash
#!/bin/bash
# discover-and-extract.sh — from zero to structured data on an unknown page

URL="$1"
browser4-cli goto "$URL"
browser4-cli domsnapshot

# 1. Discover repeating containers
echo "=== Scanning for repeating containers ==="
browser4-cli domsnapshot inspect

# 2. Pick the most promising container (e.g., largest match count)
#    and inspect it in detail
CONTAINER=".product_pod"  # adjust based on step 1 output
echo "=== Inspecting $CONTAINER ==="
browser4-cli domsnapshot inspect "$CONTAINER"

# 3. Validate the suggested selectors by extracting a few values
echo "=== Validating: title ==="
browser4-cli domsnapshot get all text "$CONTAINER h3 a" --limit 5

echo "=== Validating: price ==="
browser4-cli domsnapshot get all text "$CONTAINER p.price_color" --limit 5

# 4. Once validated, run full extraction with domsnapshot query
browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, 'h3 a') AS title,
    dom_first_text(dom, 'p.price_color') AS price
  FROM load_and_select(@url, '$CONTAINER')
"
```

**Why `inspect` here:** It eliminates the guesswork of selector discovery. Instead of reading raw HTML or guessing class names, you get a ranked list of selectors with coverage percentages. The suggested selectors are based on structural recurrence — if a selector appears in 10/10 cards, it's reliable for extraction.

---

## 14. Amazon Home Page Discovery

**Problem:** You land on `amazon.com` and need to quickly understand the page structure — where is the search box? What navigation categories exist? What content blocks (recommendations, deals, featured products) are present?

**Why DOM Snapshot:** `summary` generates a compressed WPSI that distills the page to its structural essence — headings, forms, lists, tables, and key content blocks — without drowning you in HTML. `inspect` then reveals the CSS selectors for the interactive and repeated elements you care about. Together they eliminate manual exploration on a page with 2000+ text nodes and dozens of sections.

### 14a. Get a bird's-eye view with summary

```bash
# Navigate to Amazon's home page
browser4-cli goto "https://www.amazon.com"
browser4-cli domsnapshot

# Generate the WPSI summary
browser4-cli domsnapshot summary
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
# Inspect the :root to find top-level repeating patterns
browser4-cli domsnapshot inspect

# Then narrow down to discover navigation structure
browser4-cli domsnapshot inspect "#nav-xshop"
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
browser4-cli domsnapshot get html "#nav-search-bar-form"

# Approach 2: Use the interactive snapshot to find it by role
browser4-cli snapshot | grep -i search
```

**Why `summary` + `inspect` before extraction:** On a page as large as Amazon's home page (2000+ text nodes, 18 tables), manually reading HTML or guessing selectors is impractical. `summary` condenses the page to its skeleton — you see that there are 2 forms and the search bar lives inside `#nav-search-bar-form`. `inspect` then reveals the exact selectors for the search input (`input#twotabsearchtextbox`) and the Go button (`input.nav-input[type="submit"]`). Once you know these selectors, you can either fill the form or — more reliably — navigate directly to search results using URL injection (see Scenario 15).

> **Note:** Amazon's home page is notoriously heavy (often >2 MB of HTML, 2000+ DOM nodes). The WPSI summary is typically <1% of the original HTML size, making it practical for LLM consumption even on the largest pages.

---

## 15. Amazon Search Results Extraction

**Problem:** You've searched Amazon for a product category and need to extract titles, prices, ratings, and image URLs from the search results page. The DOM is complex and you don't know the selectors ahead of time. You need a repeatable discovery-to-extraction workflow.

**Why DOM Snapshot:** `summary` confirms you are on a search-results page and reveals the result count. `inspect` discovers the repeating card structure and suggests selectors with coverage percentages — no manual HTML reading needed. `get all` validates the suggested selectors on real data. `query` then extracts structured data in a single X-SQL pass.

### 15a. Navigate and confirm page type with summary

URL injection bypasses Amazon's problematic search form — the `press Enter` approach fails because Amazon intercepts form submission with custom JavaScript:

```bash
# Use URL injection to navigate directly to search results
browser4-cli goto "https://www.amazon.com/s?k=wireless+mouse"

# Capture the DOM snapshot
browser4-cli domsnapshot

# Confirm it's a search-results page and see the structure
browser4-cli domsnapshot summary
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
# Start broad — scan for repeating containers
browser4-cli domsnapshot inspect

# Narrow to the search result cards using the Amazon-specific data attribute
browser4-cli domsnapshot inspect ".s-result-item[data-component-type='s-search-result']"
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
browser4-cli domsnapshot get all text "h2 a.a-link-normal" --limit 5
# → ["Logitech M720 Triathlon", "Logitech MX Master 3S", "Razer Basilisk X HyperSpeed", ...]

# Validate prices
browser4-cli domsnapshot get all text "span.a-offscreen" --limit 5
# → ["$34.99", "$99.99", "$59.99", ...]

# Validate ratings
browser4-cli domsnapshot get all text "span.a-icon-alt" --limit 5
# → ["4.6 out of 5 stars", "4.7 out of 5 stars", "4.5 out of 5 stars", ...]

# Validate image URLs
browser4-cli domsnapshot get all attr "img.s-image" src --limit 3
# → ["https://m.media-amazon.com/images/I/61kU1j...", ...]
```

Once validated, extract all fields in bulk:

```bash
# Full extraction — titles, prices, ratings
browser4-cli domsnapshot get all text "h2 a.a-link-normal"
browser4-cli domsnapshot get all text "span.a-offscreen"
browser4-cli domsnapshot get all text "span.a-icon-alt"

# Full extraction — image URLs
browser4-cli domsnapshot get all attr "img.s-image" src
```

**Why `get all` here:** Unlike `domsnapshot get` (which returns only the first match), `get all` returns a JSON array of all matching elements. This is ideal for validating that your discovered selectors actually work across the full result set before writing a structured query.

### 15d. Structured extraction with X-SQL query

For the most efficient single-command workflow, combine all fields into one X-SQL query:

```bash
browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, 'h2 a.a-link-normal') AS title,
    dom_first_text(dom, 'span.a-offscreen') AS price,
    dom_first_text(dom, 'span.a-icon-alt') AS rating,
    dom_first_attr(dom, 'img.s-image', 'src') AS image_url
  FROM load_and_select(@url, '.s-result-item[data-component-type=s-search-result]')
  WHERE dom_first_text(dom, 'h2 a.a-link-normal') IS NOT NULL
"
```

**Why `inspect` before `query` here:** The inspect output gave you the exact selectors with 100% recurrence guarantees. Without inspect, you would have to guess selectors or read raw HTML — a slow and error-prone process. Compare this to Scenario 1b, which assumes you already know the selectors; this scenario shows you how to discover them.

### 15e. Save results for trend tracking

```bash
# Export the full page HTML for archival or later re-extraction
browser4-cli domsnapshot export --file="amazon-search-wireless-mouse-$(date +%Y%m%d).html"

# For scheduled monitoring, combine with load options (see Scenario 4)
echo "
  SELECT
    dom_first_text(dom, 'h2 a.a-link-normal') AS title,
    dom_first_text(dom, 'span.a-offscreen') AS price
  FROM load_and_select(@url, '.s-result-item[data-component-type=\"s-search-result\"]')
  WHERE dom_first_text(dom, 'h2 a.a-link-normal') IS NOT NULL
" > search-results.sql

browser4-cli domsnapshot query "
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

**Problem:** You need to extract comprehensive product data — title, price, rating, feature bullets, technical specifications, stock status, brand, and images — from an Amazon product detail page. The DOM is deeply nested with multiple sections. You need a discovery-first workflow to find the right selectors, then extract and archive the data.

**Why DOM Snapshot:** `summary` reveals the page's structural sections at a glance (tables for specs, lists for features, forms for buying options). `inspect` drills into specific sections to reveal exact CSS selectors. `get` extracts data using those discovered selectors. `grep` provides instant presence checks for stock badges, deal labels, and other non-structured indicators. `export` archives the full page for offline analysis and price-trend tracking.

### 16a. Discover page structure with summary

```bash
# Navigate to the product page (use your target ASIN)
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"
browser4-cli domsnapshot

# Get the WPSI summary
browser4-cli domsnapshot summary
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
browser4-cli domsnapshot inspect "#centerCol"

# Inspect the feature bullets section
browser4-cli domsnapshot inspect "#feature-bullets"

# Inspect the technical specifications table
browser4-cli domsnapshot inspect "#productDetails_techSpec_section_1"
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
browser4-cli domsnapshot get text "#productTitle"

# Price
browser4-cli domsnapshot get text ".a-price .a-offscreen"

# Rating
browser4-cli domsnapshot get text "#acrCustomerReviewText"

# Product image
browser4-cli domsnapshot get attr "#landingImage" src

# Brand / manufacturer
browser4-cli domsnapshot get text "#bylineInfo"

# Availability
browser4-cli domsnapshot get text "#availability"
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
browser4-cli domsnapshot get all text "#feature-bullets ul.a-unordered-list li span.a-list-item"
# → ["Active Noise Cancellation...", "Sweat and water resistant...", "Adaptive Transparency...", ...]

# Structured feature extraction with X-SQL
browser4-cli domsnapshot query --sql "
  SELECT dom_first_text(dom, 'span.a-list-item') AS feature
  FROM load_and_select(@url, '#feature-bullets li')
  WHERE dom_first_text(dom, 'span.a-list-item') IS NOT NULL
"

# Technical specification table — get the full HTML
browser4-cli domsnapshot get html "#productDetails_techSpec_section_1"

# Or extract specs as structured data with X-SQL
browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, 'th') AS spec_name,
    dom_first_text(dom, 'td') AS spec_value
  FROM load_and_select(@url, '#productDetails_techSpec_section_1 tr')
  WHERE dom_first_text(dom, 'th') IS NOT NULL
"
```

### 16e. Quick presence checks with grep

For instant pass/fail checks that do not require writing CSS selectors:

```bash
# Check stock status
browser4-cli domsnapshot grep -l -F "In Stock" | grep -q domsnapshot && echo "IN STOCK" || echo "OUT OF STOCK"

# Check for promotional badges
browser4-cli domsnapshot grep -i 'limited time deal|lightning deal'

# Check for "Amazon's Choice" or "Best Seller" badges
browser4-cli domsnapshot grep -i "Amazon's Choice|Best Seller|#1 Best Seller"

# Count images to verify the gallery loaded
browser4-cli domsnapshot grep -c '<img[^>]*id="landingImage"'
# → 1

# Check for coupon or promotion
browser4-cli domsnapshot grep -i 'coupon|save.*off|extra savings' --selector "#centerCol"
```

**Why `grep` here:** Product pages often have badges or status indicators (Best Seller, Amazon's Choice, Limited Time Deal, Coupon clipped to the price) that are not part of your standard extraction selectors. `grep` lets you instantly check for their presence without writing new CSS selectors or queries. Use `--selector` to scope the search to a specific page region and avoid false matches from footer or sidebar content.

### 16f. Export for archival and price-change detection

```bash
# Export the full page for offline reference
browser4-cli domsnapshot export --file="airpods-pro-2-$(date +%Y%m%d).html"

# Revisit the same product a week later
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"
browser4-cli domsnapshot
browser4-cli domsnapshot export --file="airpods-pro-2-$(date +%Y%m%d).html"

# Diff the exported files to detect changes
diff airpods-pro-2-20260622.html airpods-pro-2-20260629.html
# Or grep the new export for specific price changes
browser4-cli domsnapshot grep -o '\$[0-9]+\.[0-9]{2}'
```

> **Why start with summary and inspect instead of jumping to extraction?** Scenario 1 demonstrated direct extraction using pre-known selectors. But when you encounter an unfamiliar product page — a different category, a new layout version, or an international Amazon locale — you cannot rely on assumptions. By starting with `summary` (structure overview) and `inspect` (selector discovery), you make the workflow robust against Amazon's frequent A/B tests and layout changes. Running `summary` before extraction is like reading the table of contents before diving into a book.
>
> **Known Amazon quirks for product pages:**
> - **Split price DOM** — Amazon renders the dollar and cent portions in separate elements (`.a-price-whole` and `.a-price-fraction`). The `.a-offscreen` selector captures the combined screen-reader text, which is the most reliable single-source extraction. If `.a-offscreen` is empty, use X-SQL: `dom_first_text(dom, '.a-price-whole') || '.' || dom_first_text(dom, '.a-price-fraction')`.
> - **JS-rendered prices** — In some locales (Amazon.in, Amazon.com.au, Amazon.co.uk), prices may be lazy-loaded via JavaScript. If `get text ".a-price .a-offscreen"` returns empty, re-capture with `-njr 3` to force a server-rendered fallback.
> - **Variable table ID** — The tech specs table ID (`#productDetails_techSpec_section_1`) varies by product category. Use `table.a-keyvalue.prodDetTable` as a more general fallback selector.
> - **Product page A/B tests** — Amazon frequently runs A/B tests on product page layouts. The `#centerCol` selector may be replaced by `#leftCol` or an entirely different structure on some product categories or for some logged-in users. Always run `summary` + `inspect` first if your selectors unexpectedly fail.
> - **Dynamic availability text** — The `#availability` element content changes based on stock status ("In Stock", "Only 3 left in stock", "Currently unavailable", "Usually ships within 2 to 3 days"). Use `grep` to check for any of these patterns rather than relying on exact text matching.

---

## Patterns & Tips

### Combining commands

Most real workflows chain `goto` → `domsnapshot` → `get`/`query`/`export`/`grep`/`summary`. The standalone `domsnapshot` command captures and caches the DOM snapshot; subsequent commands reuse the cached snapshot:

```bash
browser4-cli goto "$URL"
browser4-cli domsnapshot                         # capture + cache
browser4-cli domsnapshot get text "$SELECTOR"    # reads from cache
browser4-cli domsnapshot get html "$SELECTOR"    # reads from cache
browser4-cli domsnapshot grep -i "pattern"       # reads from cache (client-side search)
browser4-cli domsnapshot summary                 # reads from cache (generates WPSI)
browser4-cli domsnapshot inspect ".card"         # reads from cache (suggests selectors)
```

The cache is invalidated by the next `domsnapshot` capture or a page navigation (`goto`, `reload`, etc.).

### Error handling

```bash
# In scripts: check exit codes
if ! browser4-cli domsnapshot get text ".critical-element" > /dev/null 2>&1; then
  echo "ERROR: .critical-element not found on $URL"
  exit 1
fi
```

### Load options reference

Append these to the URL string in `domsnapshot query`:

| Option | Meaning |
|--------|---------|
| `-i 1h` | Cache page for 1 hour |
| `-i 1d` | Cache page for 1 day |
| `-njr 3` | No JavaScript rendering, retry up to 3 times |
| `-njr 0` | Force JS rendering every time |

### Choosing the right command

| Command | Best for |
|---------|----------|
| `get` | One value from one element; simple scripts; raw text/HTML for piping. `get html` paginated at 2K lines by default; `get text` not paginated — use `--all` to disable pagination when needed. |
| `query` | Multiple fields from repeating elements; filtering (`WHERE`/`expr()`); structured tabular output |
| `export` | Saving full HTML for archival, diffing, external tooling, offline analysis |
| `grep` | Presence/absence checks; counting; quick searches with context; CI smoke tests; incident response. Output paginated by default (2K lines) — use `--all` to disable. |
| `summary` | Page discovery before writing selectors; structural audits; LLM-friendly page overviews |
| `inspect` | Discovering unknown CSS selectors on complex pages; finding recurring patterns; selector validation before extraction |

> **Important:** `domsnapshot get` returns **only the first match** (it uses `document.selectFirstOrNull()` internally). For extracting data from multiple elements (e.g., all products on a listing page), use `domsnapshot query` with X-SQL's `load_and_select`.

### Command form notes

- The CLI uses the **spaced form**: `browser4-cli domsnapshot get text "h1"`, not the hyphenated `domsnapshot-get`.
- `browser4-cli domsnapshot` (with no subcommand) captures a fresh DOM snapshot and caches it in the backend. Subsequent `get`, `query`, `export`, `grep`, `summary`, and `inspect` calls reuse this cached snapshot — they do **not** re-capture the page. The cache is invalidated by the next `domsnapshot` capture or a page navigation.
- The capture command now returns enriched metadata including `imageCount`, `linkCount`, and `interactiveElements` (tag, class, id, aria attributes, bounding-box).
- `grep` performs matching **client-side** by fetching the snapshot HTML then running regex locally — no backend round-trip for the search itself.
- `summary` generates a WPSI YAML file from the cached snapshot — useful as a discovery step before writing selectors.
- `inspect` analyzes DOM structure and suggests CSS selectors for recurring patterns. It is fully deterministic (no AI) and based on structural recurrence across matching elements.
- **Output pagination:** `get html`, `get all html`, and `grep` paginate output by default at 2000 lines per page. `get text` and `get all text` are not paginated by default. Use `--page N` for subsequent pages, `--page-size N` to change page size, or `--all` to disable. Pagination is skipped in `--json` and `--quiet` modes.

---

## Tested & Verified

All scenarios using `get`, `export`, `grep`, and `summary` commands have been tested against live websites:

| Scenario | Test Site | Result |
|----------|-----------|--------|
| 1a. Product extraction | books.toscrape.com | ✅ Title, price, availability, image URL |
| 3. SEO metadata | en.wikipedia.org | ✅ Title, H1, meta description, canonical URL |
| 3e. Grep-based SEO checks | en.wikipedia.org | ✅ `-c`, `--selector`, `-l` all functional |
| 5. Listing extraction | books.toscrape.com | ✅ Product title and price (single-element) |
| 6. Compliance verification | en.wikipedia.org | ✅ Footer link extraction, element presence check |
| 6c. Grep compliance checks | en.wikipedia.org | ✅ `-l`, `-F`, `--selector footer` functional |
| 9. Export & archival | Multiple sites | ✅ Pretty-formatted HTML with metadata |
| 9d. Grep smoke tests | Multiple sites | ✅ `-l` pass/fail, `-C` context, `--selector` |
| 10. Form discovery | httpbin.org/forms/post | ✅ Full form HTML with all input fields |
| 11. Summary (WPSI) | en.wikipedia.org | ✅ YAML output with headings, stats, keyContent |
| 12. Grep incident response | Multiple sites | ✅ `-i`, `-v`, `-C`, `-F`, `--selector` all functional |

**X-SQL `query` note:** The X-SQL query path (`domsnapshot query`) has a known Jackson serialization issue with `java.time.Instant` fields in `ScrapeResponse`. A fix has been applied in `MCPToolController.kt` (using the Spring-configured `ObjectMapper` with `JavaTimeModule` instead of `jacksonObjectMapper()`). This requires a server rebuild to take effect.

---

## See Also

- [DOM Snapshot Reference](domsnapshot.md) — full command reference for `get`, `query`, `grep`, `summary`, `export`
- [CSS Selector Bridge](css-selector-bridge.md) — bridging interactive snapshot refs to DOM snapshot CSS selectors
- [X-SQL Reference](x-sql.md) — DOM and string function reference for `domsnapshot query`
- [SKILL.md](../SKILL.md) — Browser4 CLI automation skill overview
