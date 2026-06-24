# DOM Snapshot — Real-World Scenarios

Practical, end-to-end recipes using the `domsnapshot` family of commands. Each scenario is self-contained: you can adapt the CSS selectors and X-SQL queries to your own target pages.

## Scenario Index

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 1 | E-Commerce Product Monitoring | `get`, `query` | Retail |
| 2 | News Headline Aggregator | `domsnapshot`, `get`, `export` | Media |
| 3 | SEO Health Audit | `query` (X-SQL) | Marketing |
| 4 | Competitive Price Tracker | `query` (X-SQL + load options) | Business |
| 5 | Job Board Scraper | `get`, `query` | HR / Recruiting |
| 6 | Compliance Verification | `get`, `export` | Legal / Governance |
| 7 | Academic Literature Metadata Extraction | `query` (X-SQL) | Research |
| 8 | Real Estate Listing Monitor | `get`, `query` | Property |
| 9 | CI/E2E Visual Regression Snapshot | `domsnapshot`, `export` | Engineering |
| 10 | Agent-Assisted Form Discovery | `get` + Agent CLI | AI / Automation |

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

### 5c. Save and process

```bash
browser4-cli goto "https://www.linkedin.com/jobs/search?keywords=senior%20frontend"
browser4-cli domsnapshot
browser4-cli domsnapshot export --file=jobs.html
# Then feed the exported HTML to your preferred parser, or grep for quick checks:
grep -oP 'Senior.*Engineer' jobs.html | sort | uniq -c
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

### 6c. Archive for audit trail

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

## Patterns & Tips

### Combining commands

Most real workflows chain `goto` → `domsnapshot` → `get`/`query`/`export`. The standalone `domsnapshot` command captures and caches the DOM snapshot; subsequent `get`, `query`, and `export` calls reuse the cached snapshot:

```bash
browser4-cli goto "$URL"
browser4-cli domsnapshot                      # capture + cache
browser4-cli domsnapshot get text "$SELECTOR"  # reads from cache
browser4-cli domsnapshot get html "$SELECTOR"  # reads from cache
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

### Choosing `get` vs `query`

| Use `get` when… | Use `query` when… |
|-----------------|-------------------|
| You need one value from one element | You need multiple fields from repeating elements |
| The selector is simple and stable | You need filtering (`WHERE`), `expr()`, or aggregation |
| You're in a shell script doing quick checks | You want structured tabular output |
| You want raw text/HTML for piping | You're extracting across multiple pages in one command |

> **Important:** `domsnapshot get` returns **only the first match** (it uses `document.selectFirstOrNull()` internally). For extracting data from multiple elements (e.g., all products on a listing page), use `domsnapshot query` with X-SQL's `load_and_select`.

### Command form notes

- The CLI uses the **spaced form**: `browser4-cli domsnapshot get text "h1"`, not the hyphenated `domsnapshot-get`.
- `browser4-cli domsnapshot` (with no subcommand) captures a fresh DOM snapshot and caches it in the backend. Subsequent `get`, `query`, and `export` calls reuse this cached snapshot — they do **not** re-capture the page. The cache is invalidated by the next `domsnapshot` capture or a page navigation.

---

## Tested & Verified

All scenarios using `get` and `export` commands have been tested against live websites:

| Scenario | Test Site | Result |
|----------|-----------|--------|
| 1a. Product extraction | books.toscrape.com | ✅ Title, price, availability, image URL |
| 3. SEO metadata | en.wikipedia.org | ✅ Title, H1, meta description, canonical URL |
| 5. Listing extraction | books.toscrape.com | ✅ Product title and price (single-element) |
| 6. Compliance verification | en.wikipedia.org | ✅ Footer link extraction, element presence check |
| 9. Export & archival | Multiple sites | ✅ Pretty-formatted HTML with metadata |
| 10. Form discovery | httpbin.org/forms/post | ✅ Full form HTML with all input fields |

**X-SQL `query` note:** The X-SQL query path (`domsnapshot query`) has a known Jackson serialization issue with `java.time.Instant` fields in `ScrapeResponse`. A fix has been applied in `MCPToolController.kt` (using the Spring-configured `ObjectMapper` with `JavaTimeModule` instead of `jacksonObjectMapper()`). This requires a server rebuild to take effect.

---

## See Also

- [DOM Snapshot Reference](domsnapshot.md) — full command reference and X-SQL documentation
- [X-SQL Reference](x-sql.md) — available DOM UDFs and SQL syntax
- [SKILL.md](../SKILL.md) — Browser4 CLI automation skill overview
