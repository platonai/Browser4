---
title: "DOM Snapshot — Real-World Scenarios"
description: "Practical end-to-end recipes using all domsnapshot subcommands: get, query, grep, summary, export, and inspect. Covers e-commerce, news, SEO, pricing, job boards, compliance, research, real estate, CI, incident response, and agent-assisted workflows."
---

# DOM Snapshot — Real-World Scenarios

Practical, end-to-end recipes using the `domsnapshot` family of commands. Each scenario is self-contained: you can adapt the CSS selectors and X-SQL queries to your own target pages.

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
browser4-cli goto "https://www.amazon.com/s?k=mechanical+keyboard"

browser4-cli domsnapshot query --sql "
  SELECT
    dom_first_text(dom, 'h2 .a-link-normal') AS title,
    dom_first_text(dom, '.a-price .a-offscreen') AS price,
    dom_first_text(dom, '.a-icon-alt') AS rating,
    dom_first_attr(dom, 'img.s-image', 'src') AS image_url
  FROM load_and_select(@url, '.s-result-item[data-component-type=\"s-search-result\"]')
  WHERE dom_first_text(dom, 'h2 .a-link-normal') IS NOT NULL
"
```

**Why this works:** `load_and_select` iterates over each search-result card; the `dom_*` UDFs extract fields from each card. The `@url` placeholder is replaced with the current page URL.

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
    dom_first_attr(dom, 'meta[name=\"description\"]', 'content') AS meta_description,
    dom_first_attr(dom, 'meta[name=\"keywords\"]', 'content') AS meta_keywords,
    dom_first_attr(dom, 'link[rel=\"canonical\"]', 'href') AS canonical_url,
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
  FROM load_and_select(@url, 'a[href^=\"http\"]')
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
    dom_first_text(dom, '[data-test=\"property-card-address\"]') AS address,
    dom_first_text(dom, '[data-test=\"property-card-price\"]') AS price,
    dom_first_text(dom, '.beds-container') AS beds,
    dom_first_text(dom, '.baths-container') AS baths,
    dom_first_text(dom, '.sqft-container') AS sqft
  FROM load_and_select(@url, '[data-test=\"property-card\"]')
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
    dom_first_text(dom, '[data-test=\"property-card-address\"]') AS address,
    dom_first_text(dom, '[data-test=\"property-card-price\"]') AS price
  FROM load_and_select(@url, 'article:expr(dom_first_text(dom, \".beds\") >= 3)')
  WHERE dom_first_text(dom, '[data-test=\"property-card-address\"]') IS NOT NULL
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
| `get` | One value from one element; simple scripts; raw text/HTML for piping |
| `query` | Multiple fields from repeating elements; filtering (`WHERE`/`expr()`); structured tabular output |
| `export` | Saving full HTML for archival, diffing, external tooling, offline analysis |
| `grep` | Presence/absence checks; counting; quick searches with context; CI smoke tests; incident response |
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
