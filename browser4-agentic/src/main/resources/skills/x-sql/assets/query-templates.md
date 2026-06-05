# X-SQL Query Templates

Ready-to-use X-SQL query templates for common web scraping scenarios. Replace placeholders (marked with `$`) with actual values.

---

## E-commerce

### Product Listing Grid

```sql
SELECT
  dom_first_text(dom, '$titleSelector') AS title,
  dom_first_float(dom, '$priceSelector', 0.0) AS price,
  dom_first_img(dom, '$imageSelector') AS image,
  dom_first_href(dom, '$linkSelector') AS link,
  dom_first_text(dom, '$ratingSelector') AS rating
FROM load_and_select('$url', '$rowSelector', 1, $limit);
```

**Common selectors by site pattern:**

| Site Pattern | rowSelector | titleSelector | priceSelector | imageSelector | linkSelector |
|---|---|---|---|---|---|
| Generic e-commerce | `.product`, `[data-product]` | `h2`, `.name`, `.title` | `.price`, `[data-price]` | `img:first-child` | `a:first-child` |
| Shopify stores | `.product-item` | `.product-item__title` | `.price` | `.product-item__image img` | `a.product-item__link` |
| WooCommerce | `li.product` | `h2.woocommerce-loop-product__title` | `span.price` | `img.attachment-woocommerce_thumbnail` | `a.woocommerce-LoopProduct-link` |
| Magento | `.product-item` | `.product-item-name` | `.price` | `.product-image-photo` | `a.product-item-link` |

### Product Detail Page

```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '$titleSelector') AS title,
  dom_first_float(dom, '$priceSelector', 0.0) AS price,
  dom_first_text(dom, '$descriptionSelector') AS description,
  dom_all_imgs(dom, '$gallerySelector') AS gallery,
  dom_first_text(dom, '$skuSelector') AS sku,
  dom_first_text(dom, '$availabilitySelector') AS availability
FROM load_and_select('$url', '$containerSelector');

-- For multiple products: crawl from a listing to detail pages
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '$titleSelector') AS title,
  dom_first_float(dom, '$priceSelector', 0.0) AS price,
  dom_first_text(dom, '$descriptionSelector') AS description,
  dom_first_integer(dom, '$reviewCountSelector', 0) AS review_count,
  dom_first_float(dom, '$avgRatingSelector', 0.0) AS avg_rating
FROM load_out_pages('$listingUrl', '$linkAreaSelector', 1, $limit);
```

### Price Monitoring

```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '$titleSelector') AS title,
  dom_first_float(dom, '$priceSelector', 0.0) AS current_price,
  dom_first_text(dom, '$availabilitySelector') AS availability
FROM load_and_select('$url -i 0', '$rowSelector')
WHERE str_is_not_blank(dom_first_text(dom, '$titleSelector'));
```

---

## News & Content

### Article Headlines & Links

```sql
SELECT
  dom_first_text(dom, '$headlineSelector') AS headline,
  dom_first_href(dom, '$linkSelector') AS url,
  dom_first_text(dom, '$dateSelector') AS published_date,
  dom_first_text(dom, '$summarySelector') AS summary,
  dom_first_img(dom, '$imageSelector') AS thumbnail
FROM load_and_select('$newsUrl', '$articleSelector', 1, $limit)
WHERE str_is_not_blank(dom_first_text(dom, '$headlineSelector'));
```

**Common selectors by site pattern:**

| Site Pattern | articleSelector | headlineSelector | linkSelector | dateSelector |
|---|---|---|---|---|
| Hacker News | `tr.athing` | `td.title a` | `td.title a` | `tr + tr td:contains( points) ~ td` |
| Reddit | `article` | `h3` | `a[data-click-id=body]` | `[data-testid=post_timestamp]` |
| Google News | `article` | `h3 a`, `h4 a` | `h3 a, h4 a` | `time` |
| Blog index | `article`, `.post`, `.entry` | `h2 a`, `.entry-title a` | `h2 a, .entry-title a` | `time, .date, .published` |
| Medium | `article` | `h2` | `a[rel=noopener]` | `time` |

### Article Body Extraction

```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '$titleSelector') AS title,
  dom_first_text(dom, '$authorSelector') AS author,
  dom_first_text(dom, '$dateSelector') AS published,
  dom_first_text(dom, '$bodySelector') AS body,
  dom_all_texts(dom, '$tagSelector') AS tags
FROM load_and_select('$articleUrl', 'body');
```

### RSS-Like Feed Extraction

```sql
SELECT
  dom_base_uri(dom) AS feed_url,
  dom_first_text(dom, 'title') AS feed_title,
  dom_all_texts(dom, 'item title, entry title') AS item_titles,
  dom_all_hrefs(dom, 'item link, entry link[href]') AS item_links,
  dom_all_texts(dom, 'item pubDate, entry updated, entry published') AS item_dates
FROM load_and_select('$feedUrl', 'rss channel, feed');
```

---

## Search Results

### Web Search Results

```sql
SELECT
  dom_first_text(dom, '$titleSelector') AS title,
  dom_first_href(dom, '$linkSelector') AS url,
  dom_first_text(dom, '$snippetSelector') AS snippet
FROM load_and_select('$searchUrl', '$resultSelector', 1, $limit);
```

**Common selectors by search engine:**

| Engine | resultSelector | titleSelector | linkSelector | snippetSelector |
|---|---|---|---|---|
| Google | `.g` | `h3` | `a[href^=http]` | `.VwiC3b, span.st` |
| Bing | `li.b_algo` | `h2 a` | `h2 a` | `.b_caption p` |
| DuckDuckGo | `article` | `h2 a` | `h2 a` | `[data-testid=result-snippet]` |

### Site Search

```sql
SELECT
  dom_first_text(dom, 'h2 a') AS title,
  dom_first_href(dom, 'h2 a') AS url,
  dom_first_text(dom, '.search-snippet, .excerpt, p') AS excerpt
FROM load_and_select('$siteUrl/search?q=$query', '$resultSelector', 1, $limit);
```

---

## Directories & Listings

### Business Directory

```sql
SELECT
  dom_first_text(dom, '$nameSelector') AS business_name,
  dom_first_text(dom, '$addressSelector') AS address,
  dom_first_text(dom, '$phoneSelector') AS phone,
  dom_first_href(dom, '$websiteSelector') AS website,
  dom_first_text(dom, '$categorySelector') AS category,
  dom_first_float(dom, '$ratingSelector', 0.0) AS rating
FROM load_and_select('$directoryUrl', '$listingSelector', 1, $limit);
```

### Job Listings

```sql
SELECT
  dom_first_text(dom, '$titleSelector') AS job_title,
  dom_first_text(dom, '$companySelector') AS company,
  dom_first_text(dom, '$locationSelector') AS location,
  dom_first_text(dom, '$salarySelector') AS salary,
  dom_first_href(dom, '$linkSelector') AS link,
  dom_first_text(dom, '$dateSelector') AS posted_date
FROM load_and_select('$jobsUrl', '$jobSelector', 1, $limit);
```

### Real Estate Listings

```sql
SELECT
  dom_first_text(dom, '$addressSelector') AS address,
  dom_first_float(dom, '$priceSelector', 0.0) AS price,
  dom_first_integer(dom, '$bedsSelector', 0) AS beds,
  dom_first_integer(dom, '$bathsSelector', 0) AS baths,
  dom_first_integer(dom, '$sqftSelector', 0) AS sqft,
  dom_first_img(dom, '$imageSelector') AS image
FROM load_and_select('$listingsUrl', '$listingSelector', 1, $limit);
```

---

## Social Media & Community

### Forum Thread List

```sql
SELECT
  dom_first_text(dom, '$titleSelector') AS title,
  dom_first_href(dom, '$linkSelector') AS link,
  dom_first_text(dom, '$authorSelector') AS author,
  dom_first_integer(dom, '$repliesSelector', 0) AS replies,
  dom_first_integer(dom, '$viewsSelector', 0) AS views,
  dom_first_text(dom, '$lastPostSelector') AS last_post
FROM load_and_select('$forumUrl', '$threadSelector', 1, $limit);
```

### Comments / Reviews

```sql
SELECT
  dom_first_text(dom, '$authorSelector') AS author,
  dom_first_text(dom, '$dateSelector') AS date,
  dom_first_float(dom, '$ratingSelector', 0.0) AS rating,
  dom_first_text(dom, '$bodySelector') AS content
FROM load_and_select('$url', '$reviewSelector', 1, $limit);
```

---

## Technical & SEO

### Page Metadata

```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_attr(dom, 'meta[name=description]', 'content') AS meta_description,
  dom_first_attr(dom, 'meta[name=keywords]', 'content') AS meta_keywords,
  dom_first_attr(dom, 'meta[property="og:title"]', 'content') AS og_title,
  dom_first_attr(dom, 'meta[property="og:description"]', 'content') AS og_description,
  dom_first_attr(dom, 'meta[property="og:image"]', 'content') AS og_image,
  dom_first_attr(dom, 'link[rel=canonical]', 'href') AS canonical_url,
  dom_first_text(dom, 'title') AS page_title,
  dom_first_text(dom, 'h1') AS h1
FROM load_and_select('$url', 'head');
```

### Link Analysis (Internal/External)

```sql
SELECT
  dom_all_hrefs(dom, '$linkAreaSelector') AS all_links
FROM load_and_select('$url', 'body');

-- Flatten with explode to analyze
SELECT
  col AS link,
  get_top_private_domain(col) AS domain,
  CASE WHEN col LIKE '$domainPrefix%' THEN 'internal' ELSE 'external' END AS link_type
FROM load_and_select('$url', 'body') t
JOIN explode(dom_all_hrefs(dom, 'a')) links;
```

### Image Inventory

```sql
SELECT
  col AS image_src,
  str_substring_after_last(col, '.') AS format
FROM load_and_select('$url', 'body') t
JOIN explode(dom_all_imgs(dom, 'img')) imgs
WHERE str_is_not_blank(col);
```

### Heading Structure

```sql
SELECT
  dom_all_texts(dom, 'h1') AS h1s,
  dom_all_texts(dom, 'h2') AS h2s,
  dom_all_texts(dom, 'h3') AS h3s
FROM load_and_select('$url', 'body');
```

---

## Structured Data (Schema.org / JSON-LD)

### Extract JSON-LD Blocks

```sql
SELECT
  dom_all_texts(dom, 'script[type="application/ld+json"]') AS json_ld_blocks
FROM load_and_select('$url', 'head');
```

### Extract Microdata

```sql
SELECT
  dom_first_text(dom, '[itemprop=name]') AS name,
  dom_first_text(dom, '[itemprop=price]') AS price,
  dom_first_attr(dom, '[itemprop=image]', 'src') AS image,
  dom_first_text(dom, '[itemprop=ratingValue]') AS rating
FROM load_and_select('$url', '[itemscope]');
```

---

## LLM-Powered Extraction

### When CSS Selectors Aren't Enough

Use LLM extraction for pages with inconsistent markup or when you need semantic understanding:

```sql
SELECT
  dom_base_uri(dom) AS url,
  llm_extract(dom,
    '$field1: $description1, ' ||
    '$field2: $description2, ' ||
    '$field3: $description3'
  ) AS extracted_data
FROM load_and_select('$url', 'body');
```

### Summarize Article Content

```sql
SELECT
  dom_base_uri(dom) AS url,
  llm_chat(dom, 'Summarize the main content of this page in 3 bullet points.') AS summary
FROM load_and_select('$url', 'body');
```

### Categorize Content

```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, 'h1') AS title,
  llm_chat(dom, 'Categorize this page into one of: product, article, documentation, landing page, or other. Reply with just the category name.') AS category
FROM load_and_select('$url', 'body');
```

---

## Monitoring & Change Detection

### Content Change Check

```sql
-- Force fresh fetch (-i 0) to bypass cache
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '$keyElementSelector') AS current_value
FROM load_and_select('$url -i 0', '$containerSelector');
```

### Availability Monitor

```sql
SELECT
  dom_base_uri(dom) AS url,
  dom_first_text(dom, '$statusSelector') AS availability_status,
  CASE
    WHEN str_contains_any(str_lower_case(dom_first_text(dom, '$statusSelector')), 'in stock available add')
    THEN 'AVAILABLE'
    ELSE 'UNAVAILABLE'
  END AS status
FROM load_and_select('$url -i 0', '$containerSelector');
```

---

## Multi-Level Crawling

### Two-Level: Category → Products

```sql
SELECT
  dom_first_text(cat.dom, '$categoryTitleSelector') AS category,
  dom_first_text(prod.dom, '$productTitleSelector') AS product,
  dom_first_float(prod.dom, '$priceSelector', 0.0) AS price,
  dom_first_img(prod.dom, '$imageSelector') AS image
FROM load_and_select('$catalogUrl', '$categoryRowSelector', 1, $categoryLimit) cat
JOIN select(cat.dom, '$productRowSelector') prod;
```

### Three-Level: Index → Category → Products

```sql
SELECT
  dom_first_text(idx.dom, 'h2') AS section,
  dom_first_text(cat.dom, 'h3') AS category,
  dom_first_text(prod.dom, '.name') AS product,
  dom_first_float(prod.dom, '.price', 0.0) AS price
FROM load_and_select('$siteUrl', '$sectionSelector', 1, $sectionLimit) idx
JOIN select(idx.dom, '$categorySelector') cat
JOIN select(cat.dom, '$productSelector') prod;
```

---

## Utility Queries

### Discover Page Structure

```sql
-- What elements exist? (tag names and counts)
SELECT
  dom_tag_name(dom) AS tag,
  dom_class_name(dom) AS class_name,
  dom_text_len(dom) AS text_length
FROM load_and_select('$url', '*', 1, 100);
```

### Find Elements by Text Content

```sql
SELECT
  dom_css_selector(dom) AS css_path,
  dom_tag_name(dom) AS tag,
  dom_text(dom) AS text
FROM load_and_select('$url', '*:contains($searchText)', 1, 50);
```

### View Load Options

```sql
SELECT * FROM loadOptions();
```

### View All Available Functions

```sql
SELECT * FROM xsqlHelp();
SELECT * FROM xsqlHelp() WHERE NAMESPACE = 'DOM';
```

---

## Tips for Customizing Templates

1. **Start with a small `limit`** (5–10) to validate your selectors before scaling up
2. **Use the browser's DevTools** to test CSS selectors: `document.querySelectorAll('$selector')`
3. **Prefer semantic attributes** (`[data-testid]`, `[aria-label]`) over layout classes that may change
4. **Add `-i 1d`** to the URL for repeated queries to use cached pages
5. **Use `str_first_float`** rather than casting — it handles currency symbols and formatting
6. **Set defaults** on all numeric extraction functions to avoid NULL propagation
7. **Filter with WHERE** after extraction, not with complex CSS selectors
