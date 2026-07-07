---
title: "X-SQL: DomSelectFunctions — CSS Selector-Based Extraction"
description: "Reference for ~50 DOM select functions following the all*/first*/nth* pattern. Covers text, HTML, number, attribute, image, link, node label, and regex extraction with CSS selectors."
tier: catalog
---

# X-SQL: DomSelectFunctions — CSS Selector-Based Extraction

> **Parent:** [x-sql.md](x-sql.md) — full function index and quick-reference patterns
>
> **Related:** [DOM_LOAD_AND_SELECT](x-sql-dom-load-select.md) | [DomFunctions](x-sql-dom-functions.md) | [StringFunctions](x-sql-string-functions.md)

**Source:** `DomSelectFunctions.kt` | **Namespace:** `DOM` | **~50 functions**

All functions follow a consistent `all*` / `first*` / `nth*` pattern. `all*` returns `ValueArray`, `first*` and `nth*` return scalar values.

> **SQL constraint:** All queries must use `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)`. No CTEs, subqueries, `EXPLODE`, or other table sources are supported.

---

## Table of Contents

- [3.1 Element Selection](#31-element-selection)
- [3.2 Text Extraction](#32-text-extraction)
- [3.3 HTML Extraction](#33-html-extraction)
- [3.4 Number Extraction](#34-number-extraction)
- [3.5 Attribute Extraction](#35-attribute-extraction)
- [3.6 Image & Link Extraction](#36-image--link-extraction)
- [3.7 Node Labels](#37-node-labels)
- [3.8 Regex Extraction with CSS Selectors](#38-regex-extraction-with-css-selectors)

---

## 3.1 Element Selection

```sql
-- DOM_SELECT_ALL: Returns all matched elements as a ValueArray of DOMs
SELECT DOM_SELECT_ALL(DOM, 'li') AS list_items
FROM DOM_LOAD_AND_SELECT('https://example.com', 'ul');

-- DOM_SELECT_FIRST: Returns the first match as a DOM
SELECT DOM_SELECT_FIRST(DOM, 'h1') AS heading
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_SELECT_NTH: Returns the nth match (1-based) as a DOM
SELECT DOM_SELECT_NTH(DOM, 'p', 3) AS third_paragraph
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

## 3.2 Text Extraction

```sql
-- DOM_ALL_TEXTS: All matched elements' text content as an array
SELECT DOM_ALL_TEXTS(DOM, 'li') AS items
FROM DOM_LOAD_AND_SELECT('https://example.com', 'ul');

-- DOM_FIRST_TEXT: Text of the first match
SELECT DOM_FIRST_TEXT(DOM, 'h1') AS heading
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_NTH_TEXT: Text of the nth match
SELECT DOM_NTH_TEXT(DOM, 'p', 2) AS second_paragraph
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_ALL_OWN_TEXTS: Own text of all matches
SELECT DOM_ALL_OWN_TEXTS(DOM, 'li') AS item_texts
FROM DOM_LOAD_AND_SELECT('https://example.com', 'ul.features');

-- DOM_FIRST_OWN_TEXT: Own text of first match
SELECT DOM_FIRST_OWN_TEXT(DOM, 'div.heading') AS heading
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_NTH_OWN_TEXT: Own text of nth match
SELECT DOM_NTH_OWN_TEXT(DOM, 'p', 1) AS first_p
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_WHOLE_TEXTS, DOM_FIRST_WHOLE_TEXT, DOM_NTH_WHOLE_TEXT
SELECT
    DOM_FIRST_WHOLE_TEXT(DOM, 'pre') AS code_text,
    DOM_NTH_WHOLE_TEXT(DOM, 'pre', 2) AS second_code
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

## 3.3 HTML Extraction

```sql
-- Slim HTML variants
SELECT DOM_FIRST_SLIM_HTML(DOM, 'article') AS cleaned
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_NTH_SLIM_HTML(DOM, 'div.section', 3) AS third_section
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- Minimal HTML variants
SELECT DOM_ALL_MINIMAL_HTMLS(DOM, '.comment') AS comments
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_FIRST_MINIMAL_HTML(DOM, 'article') AS article
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_NTH_MINIMAL_HTML(DOM, 'div', 2) AS second_div
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

## 3.4 Number Extraction

Extracts the first integer or float found in the matched element's text. Falls back to `defaultValue` on parse failure.

```sql
-- DOM_ALL_INTEGERS: Extract integers from all matches
SELECT DOM_ALL_INTEGERS(DOM, '.price') AS prices
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', 'body');

-- DOM_FIRST_INTEGER: Extract integer from first match
SELECT DOM_FIRST_INTEGER(DOM, '.review-count', 0) AS review_count
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_NTH_INTEGER: Extract integer from nth match
SELECT DOM_NTH_INTEGER(DOM, '.stat', 3, 0) AS third_stat
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_ALL_FLOATS: Extract floats from all matches
SELECT DOM_ALL_FLOATS(DOM, '.rating', 0.0) AS ratings
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_FIRST_FLOAT: Extract float from first match
SELECT DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS price
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', '.product-card');

-- DOM_NTH_FLOAT: Extract float from nth match
SELECT DOM_NTH_FLOAT(DOM, '.metric', 2, 0.0) AS second_metric
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

**Pattern — extracting structured numeric data:**

```sql
SELECT
    DOM_FIRST_TEXT(DOM, '.name') AS name,
    DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS price,
    DOM_FIRST_INTEGER(DOM, '.stock', 0) AS in_stock
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/products', '.product-card');
```

## 3.5 Attribute Extraction

```sql
-- Single attribute, all/first/nth
SELECT DOM_ALL_ATTRS(DOM, 'a', 'href') AS links
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_FIRST_ATTR(DOM, 'meta[name="description"]', 'content') AS description
FROM DOM_LOAD_AND_SELECT('https://example.com', 'head');

SELECT DOM_NTH_ATTR(DOM, 'img', 3, 'src') AS third_image
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- Multiple attributes at once
SELECT DOM_FIRST_MULTI_ATTRS(DOM, 'a', ARRAY['href', 'title', 'class']) AS link_attrs
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_NTH_MULTI_ATTRS(DOM, 'img', 2, ARRAY['src', 'alt', 'width', 'height']) AS img_attrs
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

> **Tip — Resolve relative URLs to absolute:** Use the `abs:` prefix on any URL-bearing attribute to resolve it against the document's base URI:
> ```sql
> -- Relative href → absolute URL
> SELECT DOM_FIRST_ATTR(DOM, 'a', 'abs:href') AS absolute_link
> FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
> 
> -- Relative src → absolute URL
> SELECT DOM_FIRST_ATTR(DOM, 'img', 'abs:src') AS absolute_src
> FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
> ```
> Without `abs:`, the raw attribute value is returned (e.g., `/-/zh/dp/B0FCLQZDHV/ref=sr_1_31?...`). With `abs:`, it is resolved to a full URL (e.g., `https://www.amazon.com/dp/B0FCLQZDHV`).

```sql
/**
 * Pattern — extract all links with multiple attributes:
 */

```sql
SELECT
    DOM_FIRST_ATTR(DOM, ':root', 'href') AS url,
    DOM_FIRST_ATTR(DOM, ':root', 'title') AS tooltip
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a[href]');
```

## 3.6 Image & Link Extraction

Automatically appends `img` / `a` to the CSS query if not present.

```sql
-- DOM_ALL_IMGS: All image absolute src URLs
SELECT DOM_ALL_IMGS(DOM) AS images
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_ALL_IMGS(DOM, 'div.gallery') AS gallery_images
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_FIRST_IMG: First image absolute src
SELECT DOM_FIRST_IMG(DOM) AS hero_image
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_FIRST_IMG(DOM, 'article') AS article_image
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_NTH_IMG: Nth image absolute src
SELECT DOM_NTH_IMG(DOM, 'div.gallery', 3) AS third_gallery_image
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_ALL_HREFS: All link absolute href URLs
SELECT DOM_ALL_HREFS(DOM) AS links
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_ALL_HREFS(DOM, 'nav') AS nav_links
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_FIRST_HREF: First link absolute href
SELECT DOM_FIRST_HREF(DOM, 'article') AS first_article_link
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_NTH_HREF: Nth link absolute href
SELECT DOM_NTH_HREF(DOM, 'nav', 5) AS fifth_nav_link
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

## 3.7 Node Labels

```sql
-- DOM_ALL_NODES_LABELS: Pulsar classification labels for all matched elements
SELECT DOM_ALL_NODES_LABELS(DOM, 'div') AS labels
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_FIRST_NODE_LABELS: Label of first match
SELECT DOM_FIRST_NODE_LABELS(DOM, 'div') AS label
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_NTH_NODE_LABELS: Label of nth match
SELECT DOM_NTH_NODE_LABELS(DOM, 'div', 2) AS second_label
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

## 3.8 Regex Extraction with CSS Selectors

```sql
-- DOM_ALL_RE1: Extract first regex group from all matched elements
SELECT DOM_ALL_RE1(DOM, '\$([\d.]+)') AS prices
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', '.price');

SELECT DOM_ALL_RE1(DOM, '.spec', '(\d+x\d+)') AS resolutions
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_FIRST_RE1: Extract first regex group from first match
SELECT DOM_FIRST_RE1(DOM, '(\d{4}-\d{2}-\d{2})') AS date
FROM DOM_LOAD_AND_SELECT('https://example.com', '.meta');

SELECT DOM_FIRST_RE1(DOM, '.meta', 'Published:\s*(.+)') AS pub_date
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

SELECT DOM_FIRST_RE1(DOM, '.phones', '(\d{3}-\d{3}-\d{4})', 1) AS phone
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_ALL_RE2: Extract key-value pairs from all matches (groups 1,2)
SELECT DOM_ALL_RE2(DOM, '(\w+):\s*(.+)') AS fields
FROM DOM_LOAD_AND_SELECT('https://example.com', '.specs li');

SELECT DOM_ALL_RE2(DOM, '.specs li', '(\w+):\s*(.+)') AS specs
FROM DOM_LOAD_AND_SELECT('https://example.com/product/123', '.specs-table tr');

-- DOM_FIRST_RE2: Extract key-value pair from first match
SELECT DOM_FIRST_RE2(DOM, '.product', 'Price:\s*\$(\d+)') AS price
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', 'body');

SELECT DOM_FIRST_RE2(DOM, '.specs', '(\w+)', 1, 1) AS first_word
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- DOM_ALL_RE2 with custom groups
SELECT DOM_ALL_RE2(DOM, 'li', '(\d+)\.\s*(.+)', 1, 2) AS numbered_items
FROM DOM_LOAD_AND_SELECT('https://example.com', 'ol');
```
