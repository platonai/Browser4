---
title: "HTML Snapshot Scenarios — Data Extraction"
description: "End-to-end recipes for extracting structured data from e-commerce, news, job boards, academic literature, and real estate pages using htmlsnapshot get, query, and export."
tier: procedure
---

# HTML Snapshot Scenarios — Data Extraction

Practical recipes for extracting structured data from listing and detail pages using `htmlsnapshot get`, `htmlsnapshot query`, and `htmlsnapshot export`.

> **Note:** CSS selectors are tied to live websites and may break over time. See [SKILL.md §5](../SKILL.md#5-critical-warnings).

> **Parent document:** [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips, and command reference.

## Scenarios

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 1 | E-Commerce Product Monitoring | `get`, `query` | Retail |
| 2 | News Headline Aggregator | `htmlsnapshot`, `get`, `export` | Media |
| 5 | Job Board Scraper | `get`, `query`, `grep` | HR / Recruiting |
| 7 | Academic Literature Metadata Extraction | `query` (X-SQL) | Research |
| 8 | Real Estate Listing Monitor | `get`, `query` | Property |

---

## 1. E-Commerce Product Monitoring

**Problem:** Your team needs to track product availability, pricing, and review counts across a competitor's catalog — daily. Manually visiting pages is not scalable.

**Why HTML Snapshot:** CSS selectors give you precise extraction of structured fields (title, price, rating, stock status). X-SQL handles multi-product listing pages in a single query.

### 1a. Extract a single product's details

```bash
# Navigate to the product page and capture a HTML snapshot
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"
browser4-cli htmlsnapshot

# Pull individual fields from the cached snapshot
browser4-cli htmlsnapshot get text "#productTitle"
browser4-cli htmlsnapshot get text ".a-price .a-offscreen"
browser4-cli htmlsnapshot get text "#acrCustomerReviewText"
browser4-cli htmlsnapshot get attr "#landingImage" src
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
# Step 1: Navigate, capture a HTML snapshot, and verify the page loaded correctly.
# (This lets you validate selectors with `get` / `inspect` before running the X-SQL query.)
browser4-cli goto "https://www.amazon.com/s?k=mechanical+keyboard"
browser4-cli htmlsnapshot

# Step 2: Run the X-SQL query with its own URL + load options.
# `DOM_LOAD_AND_SELECT(@url, ...)` makes its own HTTP request (server-side);
# -i 1h caches that request for 1 hour — critical for avoiding quota exhaustion.
browser4-cli htmlsnapshot query "https://www.amazon.com/s?k=mechanical+keyboard -i 1h" --sql "
  SELECT
    DOM_FIRST_TEXT(dom, 'h2 .a-link-normal') AS title,
    DOM_FIRST_TEXT(dom, '.a-price .a-offscreen') AS price,
    DOM_FIRST_TEXT(dom, '.a-icon-alt') AS rating,
    DOM_FIRST_ATTR(dom, 'img.s-image:expr(width >= 200 && height >= 200)', 'src') AS image_url
  FROM DOM_LOAD_AND_SELECT(@url, '.s-result-item[data-component-type=s-search-result]')
  WHERE DOM_FIRST_TEXT(dom, 'h2 .a-link-normal') IS NOT NULL
"
```

**Why this works:** The initial `goto` + `htmlsnapshot` capture lets you verify the page structure and test selectors before committing to the full X-SQL query — especially important on rate-limited sites where every `load_and_select` call counts against your quota. The `-i 1h` load option caches the server response for 1 hour — essential when iterating on a query or running it repeatedly, as Amazon aggressively rate-limits rapid requests.

> **Warning:** Quota warning: Amazon and similar sites detect rapid repeated requests and will return CAPTCHAs or 503 errors. Always use `-i 1h` (or longer) when iterating on a query. If you see empty results or bot-detection pages, wait 5–10 minutes before retrying. See the [Amazon Search Results Extraction](htmlsnapshot-scenarios-amazon.md#15-amazon-search-results-extraction) for a full discovery-to-extraction workflow that minimizes quota burn by validating selectors on the cached snapshot before running X-SQL queries.

### 1c. Export for offline analysis

```bash
browser4-cli goto "https://www.amazon.com/dp/B08PP5MSVB"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot export --file product-page-$(date +%Y%m%d).html
# Later: grep, parse, or diff against yesterday's export
diff product-page-20260621.html product-page-20260622.html
```

---

## 2. News Headline Aggregator

**Problem:** You need the top 10 headlines, bylines, and timestamps from a news homepage every morning, formatted as structured data.

**Why HTML Snapshot:** X-SQL can extract multiple articles from a listing page in one shot. The `export` command preserves the raw HTML for archival or later re-parsing with different selectors.

### 2a. One-shot extraction with X-SQL

```bash
browser4-cli goto "https://news.ycombinator.com"

browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, '.titleline > a') AS headline,
    DOM_BASE_URI(dom) AS article_url,
    DOM_FIRST_TEXT(dom, '.score') AS points,
    DOM_FIRST_TEXT(dom, '.hnuser') AS author
  FROM DOM_LOAD_AND_SELECT(@url, 'tr.athing')
  WHERE DOM_FIRST_TEXT(dom, '.titleline > a') IS NOT NULL
"
```

### 2b. Quick extraction with `get` for a single section

```bash
browser4-cli goto "https://www.bbc.com/news"

# `get text` returns the first match of the CSS selector
browser4-cli htmlsnapshot get text ".gs-c-promo-heading__title"
# For multiple headlines, use `query` with X-SQL (see Scenario 1b pattern)
```

### 2c. Archive for trend analysis

```bash
browser4-cli goto "https://news.ycombinator.com"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot export --file "headlines-$(date +%Y%m%d-%H%M).html"
# Build a corpus over weeks; run NLP pipelines on the exported HTML
```

---

## 5. Job Board Scraper

**Problem:** A recruiter wants to extract all "Senior Frontend Engineer" postings from a job board into a structured CSV — job title, company, location, salary range (if listed), and posting date.

**Why HTML Snapshot:** Listings are repeated structures on a single page — perfect for X-SQL's `load_and_select`.

### 5a. Full extraction with X-SQL

```bash
browser4-cli goto "https://www.linkedin.com/jobs/search?keywords=senior%20frontend"

browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, '.job-card-list__title') AS title,
    DOM_FIRST_TEXT(dom, '.job-card-container__company-name') AS company,
    DOM_FIRST_TEXT(dom, '.job-card-container__metadata-item') AS location,
    DOM_FIRST_TEXT(dom, '.job-search-card__salary-info') AS salary
  FROM DOM_LOAD_AND_SELECT(@url, '.job-card-container')
  WHERE DOM_FIRST_TEXT(dom, '.job-card-list__title') IS NOT NULL
"
```

### 5b. Quick text extraction for a single field

```bash
browser4-cli htmlsnapshot get text ".jobs-search-results__list-item"
```

### 5c. Quick filtering with grep

Skip the export — search the snapshot directly with `grep` for instant filtering:

```bash
browser4-cli goto "https://www.linkedin.com/jobs/search?keywords=senior%20frontend"
browser4-cli htmlsnapshot

# Find all "Senior" level positions
browser4-cli htmlsnapshot grep -i 'senior'

# Count remote vs on-site
browser4-cli htmlsnapshot grep -c -i 'remote'
browser4-cli htmlsnapshot grep -c -i 'on.site'

# Scope search to job cards only for cleaner results
browser4-cli htmlsnapshot grep --selector ".job-card-container" -i 'react'
```

### 5d. Save and process

```bash
browser4-cli goto "https://www.linkedin.com/jobs/search?keywords=senior%20frontend"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot export --file jobs.html
# For deeper processing, feed the exported HTML to your preferred parser
```

---

## 7. Academic Literature Metadata Extraction

**Problem:** A researcher needs to extract paper titles, authors, publication dates, and abstract snippets from 50 search-result pages on Google Scholar or PubMed — for a systematic literature review.

**Why HTML Snapshot:** X-SQL can extract from each result card on a search page. No need for a Python/Scrapy stack.

### 7a. PubMed search extraction

```bash
browser4-cli goto "https://pubmed.ncbi.nlm.nih.gov/?term=machine+learning+drug+discovery&sort=date"

browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, '.docsum-title') AS title,
    DOM_FIRST_TEXT(dom, '.full-author-list') AS authors,
    DOM_FIRST_TEXT(dom, '.docsum-journal-citation') AS citation,
    DOM_FIRST_ATTR(dom, '.docsum-title', 'href') AS link
  FROM DOM_LOAD_AND_SELECT(@url, '.docsum-content')
  WHERE DOM_FIRST_TEXT(dom, '.docsum-title') IS NOT NULL
"
```

### 7b. Extract abstract from individual paper page

```bash
browser4-cli goto "https://pubmed.ncbi.nlm.nih.gov/12345678/"
browser4-cli htmlsnapshot

# Extract fields from the cached snapshot
browser4-cli htmlsnapshot get text ".abstract-content"
browser4-cli htmlsnapshot get text "#full-view-heading"
```

### 7c. Bulk processing loop

```bash
#!/bin/bash
# Extract metadata from a list of PubMed IDs
for pmid in 12345678 23456789 34567890; do
  browser4-cli goto "https://pubmed.ncbi.nlm.nih.gov/$pmid/"
  browser4-cli htmlsnapshot
  echo "=== PMID: $pmid ==="
  browser4-cli htmlsnapshot get text ".heading-title"
  browser4-cli htmlsnapshot get text ".abstract-content"
  echo ""
done
```

---

## 8. Real Estate Listing Monitor

**Problem:** A home buyer wants to track new listings in a neighborhood — address, price, bedrooms, square footage, and listing URL — and be notified when a property matching their criteria appears.

**Why HTML Snapshot:** X-SQL extracts a structured table of listings from a single search page. CSS selectors target specific data fields.

### 8a. Extract all listings from a search

```bash
browser4-cli goto "https://www.zillow.com/homes/san-francisco_rb/"

browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, '[data-test=property-card-address]') AS address,
    DOM_FIRST_TEXT(dom, '[data-test=property-card-price]') AS price,
    DOM_FIRST_TEXT(dom, '.beds-container') AS beds,
    DOM_FIRST_TEXT(dom, '.baths-container') AS baths,
    DOM_FIRST_TEXT(dom, '.sqft-container') AS sqft
  FROM DOM_LOAD_AND_SELECT(@url, '[data-test=property-card]')
"
```

### 8b. Quick check: did anything change?

```bash
browser4-cli goto "https://www.zillow.com/homes/san-francisco_rb/"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "[data-test='property-card-address']" > todays-listings.txt
diff yesterdays-listings.txt todays-listings.txt
```

### 8c. Filter with X-SQL WHERE clause

Use a `WHERE` clause to filter results based on extracted field values — more reliable than trying to embed filtering logic in the CSS selector:

```bash
browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, '[data-test=property-card-address]') AS address,
    DOM_FIRST_TEXT(dom, '[data-test=property-card-price]') AS price,
    DOM_FIRST_TEXT(dom, '.beds-container') AS beds
  FROM DOM_LOAD_AND_SELECT(@url, '[data-test=property-card]')
  WHERE DOM_FIRST_TEXT(dom, '[data-test=property-card-address]') IS NOT NULL
    AND CAST(DOM_FIRST_TEXT(dom, '.beds-container') AS INT) >= 3
"
```

> **Note:** PowerCSS `:expr()` selectors (e.g., `div:expr(width>400)`) query by visual features like size and position — they cannot call X-SQL DOM functions like `DOM_FIRST_TEXT()`. Use a `WHERE` clause to filter by extracted text values, as shown above.

---

## See Also

- [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips
- [htmlsnapshot-scenarios-amazon.md](htmlsnapshot-scenarios-amazon.md) — Amazon discovery-to-extraction workflows (scenarios 14–16)
- [htmlsnapshot-scenarios-audit.md](htmlsnapshot-scenarios-audit.md) — SEO, compliance, CI, pricing, incident response (scenarios 3, 4, 6, 9, 12)
- [htmlsnapshot-scenarios-advanced.md](htmlsnapshot-scenarios-advanced.md) — summary, inspect, and agent form discovery (scenarios 10, 11, 13)
- [htmlsnapshot.md](htmlsnapshot.md) — full command reference
- [SKILL.md](../SKILL.md) — Browser4 CLI automation skill overview
