---
name: x-sql
description: Queries the web with SQL — loads pages, selects DOM elements with CSS selectors, and extracts structured data (text, HTML, attributes, links, images, numbers) into tables and charts. Use when the user asks to scrape, extract, crawl, or monitor web content, or when they ask to convert a web page into structured data.
metadata:
  displayName: X-SQL Web Query
  version: "1.0.0"
  author: Browser4
  tags: "scraping, extraction, web, sql, dom, crawling, data-mining"
  dependencies: ""
---

# X-SQL Skill

## Description

X-SQL is a SQL dialect for querying the web. It extends H2 SQL with User-Defined Functions (UDFs) that load web pages, traverse the DOM, select elements with CSS selectors, and extract structured data — all within a single declarative SQL statement.

Use this skill whenever you need to turn a web page into structured data: product listings, news headlines, search results, pricing tables, article metadata, or any other content that can be targeted with CSS selectors.

## When to Use This Skill

Activate this skill when the user asks to:

- **Scrape or extract** data from a web page ("extract all products from...", "get the headlines from...")
- **Crawl** linked pages ("follow all links on the blog index and extract article titles")
- **Monitor** content changes ("check the price of...", "track when... changes")
- **Convert** a web page into a table, CSV, or structured JSON
- **Query** the web with SQL-like syntax ("show me all images larger than 400px on...")
- **Analyze** page structure ("find the most common CSS classes on...", "list all links on...")

## Core Mental Model

Think of X-SQL as a pipeline where each SQL clause maps to a step in the scraping process:

```
FROM    →  Load the page and select row-level elements
SELECT  →  Extract fields from each row element using DOM functions
WHERE   →  Filter rows by extracted values
JOIN    →  Combine data from nested elements or linked pages
```

Every X-SQL query starts with a **table function** in the `FROM` clause that loads a web page and produces rows. Each row contains a `DOM` column (the selected element) and optionally a `DOC` column (the full document). You then apply **DOM functions** in the `SELECT` clause to extract specific values from each row.

## Key Rules for Writing X-SQL

### Rule 1: Every query starts with a table function in FROM

The entry point is always a table function. Use `load_and_select` for most cases:

```sql
SELECT ... FROM load_and_select('<url>', '<cssSelector>' [, offset [, limit]]);
```

- `offset` is 1-based (the first element is 1, not 0)
- `limit` controls how many rows to return

### Rule 2: The DOM column is your row-level element

`load_and_select` returns two columns: `DOM` (each matched element) and `DOC` (the full document). Almost all DOM functions take `dom` as their first argument.

```sql
SELECT
  dom_first_text(dom, 'h2') AS title,       -- from the matched element
  dom_first_text(dom, '.price') AS price     -- from the matched element
FROM load_and_select('https://example.com/products', '.product-item');
```

### Rule 3: Use dom_first_* for scalar values, dom_all_* for arrays

DOM selection functions follow a consistent naming pattern:

| Prefix | Returns | Use case |
|---|---|---|
| `dom_first_*` | Single value | One field per row |
| `dom_all_*`  | Array of values | Multiple values per row (use with `explode`) |
| `dom_nth_*`  | Single value from the nth match | When you need a specific sibling |

### Rule 4: Function names are case-insensitive and ignore underscores

All of these are identical: `dom_first_text`, `DOM_FIRST_TEXT`, `domFirstText`, `dOm___FiRsT___tExT`.

The canonical form (used in this spec) is lowercase with underscores.

### Rule 5: Namespaces can be omitted for shortcut functions

Functions declared with `hasShortcut = true` can be called without their namespace prefix. For example, `load_and_select` belongs to the `DOM` namespace but the `dom_` prefix is optional.

### Rule 6: URLs can carry load options

Append load options to the URL to control caching and fetch behavior:

```
'https://example.com/page   -i 1d -njr 3'
```

Common options: `-i <duration>` (cache duration), `-njr` (no JavaScript rendering).

## Core Table Functions

Choose the right table function for your task:

### load_and_select — Single page, element rows

The workhorse. Load one page, select matching elements as rows.

```sql
SELECT dom_first_text(dom, 'h2 a') AS title
FROM load_and_select('https://news.ycombinator.com', 'tr.athing', 1, 30);
```

### load_all — Multiple known URLs

Load several pages in parallel. The `urls` parameter is an array.

```sql
SELECT dom_base_uri(dom) AS url, dom_first_text(dom, 'title') AS title
FROM load_all(ARRAY('https://example.com/1', 'https://example.com/2'));
```

### load_out_pages — Portal → linked pages → rows

Load a portal page, extract all outgoing links, then load each linked page as a row. Ideal for crawling blog indexes, product listings with detail pages, or search result pages.

```sql
SELECT dom_base_uri(dom) AS url, dom_first_text(dom, 'h1') AS title
FROM load_out_pages('https://example.com/blog', 'main', 1, 20);
```

### load_out_pages_and_select — Portal → linked pages → selected elements

Like `load_out_pages` but selects elements from each linked page.

```sql
SELECT dom_base_uri(dom) AS url, dom_first_text(dom, 'p') AS paragraph
FROM load_out_pages_and_select('https://example.com/articles', 'main', 1, 10, 'p.content');
```

### load_and_get_links — Just extract links

```sql
SELECT * FROM load_and_get_links('https://example.com', 'nav', 1, 100);
```

### select — Two-level iteration (parent → children)

Used as a joined table to iterate over child elements:

```sql
SELECT
  dom_first_text(cat.dom, 'h3') AS category,
  dom_first_text(item.dom, '.name') AS product,
  dom_first_float(item.dom, '.price', 0.0) AS price
FROM load_and_select('https://shop.example.com', '.category') cat
JOIN select(cat.dom, '.product-item') item;
```

## Core DOM Functions

### Content Extraction (most commonly used)

| Function | Returns | Use for |
|---|---|---|
| `dom_first_text(dom, css)` | String | Visible text of the first matched element |
| `dom_all_texts(dom, css)` | Array | Visible text of all matched elements |
| `dom_first_own_text(dom, css)` | String | Text of the element itself, excluding children |
| `dom_first_slim_html(dom, css)` | String | Cleaned HTML of the first match |
| `dom_first_whole_text(dom, css)` | String | Text including hidden nodes |
| `dom_first_integer(dom, css [, default])` | Int | Parse first integer from matched text |
| `dom_first_float(dom, css [, default])` | Float | Parse first float from matched text |

### Attribute & Media Extraction

| Function | Returns | Use for |
|---|---|---|
| `dom_first_attr(dom, css, attr)` | String | Any attribute value |
| `dom_first_href(dom [, css])` | String | First `<a>` href (absolute URL) |
| `dom_all_hrefs(dom [, css])` | Array | All `<a>` hrefs |
| `dom_first_img(dom [, css])` | String | First `<img>` src (absolute URL) |
| `dom_all_imgs(dom [, css])` | Array | All `<img>` srcs |

### Element Properties

| Function | Returns | Use for |
|---|---|---|
| `dom_base_uri(dom)` | String | The page URL |
| `dom_tag_name(dom)` | String | HTML tag name |
| `dom_attr(dom, name)` | String | Named attribute of the row element |
| `dom_id(dom)` | String | Element's `id` |
| `dom_class_name(dom)` | String | Element's `class` |
| `dom_has_class(dom, name)` | Boolean | Check if element has a CSS class |
| `dom_text(dom)` | String | Full text of the row element |
| `dom_outer_html(dom)` | String | Outer HTML of the row element |
| `dom_slim_html(dom)` | String | Slimmed HTML |

### Position & Size

| Function | Returns | Use for |
|---|---|---|
| `dom_width(dom)` | Double | Element width in pixels |
| `dom_height(dom)` | Double | Element height in pixels |
| `dom_area(dom)` | Double | width × height |
| `dom_top(dom)` | Double | Y-coordinate |
| `dom_left(dom)` | Double | X-coordinate |

### Regex Extraction

| Function | Returns | Use for |
|---|---|---|
| `dom_re1(dom, regex)` | String | First capture group |
| `dom_first_re1(dom, css, regex)` | String | First capture group after CSS selection |
| `dom_first_re1(dom, css, regex, group)` | String | Nth capture group after CSS selection |
| `dom_re2(dom, regex)` | Array | Two capture groups as (key, value) |

## Core String Functions (STR namespace)

Use these to clean and transform extracted text. All are null-safe (null input → null output).

| Function | Use for |
|---|---|
| `str_substring_after(text, sep)` | Extract text after a delimiter |
| `str_substring_before(text, sep)` | Extract text before a delimiter |
| `str_substring_between(text, open, close)` | Extract text between delimiters |
| `str_trim(text)` | Remove leading/trailing whitespace |
| `str_normalize_space(text)` | Collapse whitespace |
| `str_first_integer(text, default)` | Parse first integer |
| `str_first_float(text, default)` | Parse first float |
| `str_replace_chars(text, search, replacement)` | Replace characters |
| `str_delete_whitespace(text)` | Remove all whitespace |
| `str_is_blank(text)` / `str_is_not_blank(text)` | Check for content |
| `str_lower_case(text)` / `str_upper_case(text)` | Change case |
| `str_split(text, sep)` | Split into array |
| `str_length(text)` | String length |

## LLM Functions

When CSS selectors alone aren't enough, use the LLM to extract structured data from page content.

| Function | Use for |
|---|---|
| `llm_chat(prompt)` | Ask the LLM a question |
| `llm_chat(dom, prompt)` | Ask the LLM about a DOM element's content |
| `llm_extract(dom, rules)` | Extract structured fields as JSON |

```sql
SELECT llm_extract(dom,
  'name: the product name, ' ||
  'price: the price as a number, ' ||
  'rating: the average rating out of 5'
) AS product_info
FROM load_and_select('https://example.com/product/123', 'body');
```

## Array Helpers

| Function | Use for |
|---|---|
| `explode(array [, col])` | Turn an array into rows (use in FROM) |
| `posexplode(array [, col])` | Like explode but includes 1-based position |
| `array_join_to_string(array, sep)` | Join array elements into a string |
| `array_first_not_blank(array)` | First non-blank value |

## System Helpers

| Function | Use for |
|---|---|
| `loadOptions()` | List all URL load options |
| `xsqlHelp()` | List all registered X-SQL functions |
| `map(k1, v1, k2, v2, ...)` | Create a key-value ResultSet |

## Query Patterns

### Pattern 1: Simple extraction (single page, one row per item)

The most common pattern. Each matched element becomes a row.

```sql
SELECT
  dom_first_text(dom, '.title') AS title,
  dom_first_href(dom, '.title a') AS link,
  dom_first_float(dom, '.price', 0.0) AS price,
  dom_first_img(dom, 'img.thumb') AS thumbnail
FROM load_and_select('https://example.com/products', '.product-card', 1, 50);
```

### Pattern 2: Flatten an array into rows with explode

When a single page element contains a list, use `dom_all_*` + `explode`.

```sql
SELECT col AS image_url
FROM load_and_select('https://example.com/product/123', 'body') t
JOIN explode(dom_all_imgs(dom, '#gallery img')) img;
```

### Pattern 3: Two-level nested extraction (categories → products)

```sql
SELECT
  dom_first_text(cat.dom, 'h3.category-name') AS category,
  dom_first_text(item.dom, '.product-name') AS product,
  dom_first_float(item.dom, '.product-price', 0.0) AS price
FROM load_and_select('https://shop.example.com', '.category-section') cat
JOIN select(cat.dom, '.product-item') item;
```

### Pattern 4: Crawl and extract from linked pages

```sql
SELECT
  dom_base_uri(dom) AS article_url,
  dom_first_text(dom, 'h1') AS title,
  dom_first_text(dom, 'article p:first-child') AS lede,
  dom_first_text(dom, 'time.publish-date') AS published
FROM load_out_pages('https://example.com/blog/index', 'main', 1, 10);
```

### Pattern 5: Regex extraction from element text

```sql
SELECT
  dom_first_text(dom, '.specs') AS raw_specs,
  dom_first_re1(dom, '.specs', '(\d+)\s*GB') AS memory_gb,
  dom_first_re1(dom, '.specs', '(\d+)\s*MP', 1) AS camera_mp
FROM load_and_select('https://example.com/phones', '.phone-specs');
```

### Pattern 6: Filter rows with string checks

```sql
SELECT dom_first_text(dom, 'h2') AS headline
FROM load_and_select('https://news.example.com', 'article')
WHERE str_is_not_blank(dom_first_text(dom, 'h2'))
  AND str_contains_any(str_lower_case(dom_first_text(dom, 'h2')), 'ai ml artificial');
```

### Pattern 7: LLM-powered extraction for unstructured content

```sql
SELECT
  dom_base_uri(dom) AS url,
  llm_extract(dom,
    'title: the article headline, ' ||
    'author: the author name, ' ||
    'summary: a 2-sentence summary, ' ||
    'sentiment: positive, negative, or neutral'
  ) AS extracted
FROM load_and_select('https://example.com/news/article-42', 'body');
```

## CSS Selector Tips

X-SQL uses Jsoup's CSS selector engine. Here are the most useful selectors:

| Selector | Meaning | Example |
|---|---|---|
| `tag` | Element by tag | `h1`, `article` |
| `.class` | Element by class | `.product`, `.price` |
| `#id` | Element by ID | `#main-content` |
| `[attr]` | Has attribute | `[data-price]` |
| `[attr=value]` | Attribute equals | `[data-category=electronics]` |
| `[attr^=value]` | Attribute starts with | `[href^=/products/]` |
| `[attr$=value]` | Attribute ends with | `[src$=.jpg]` |
| `[attr*=value]` | Attribute contains | `[class*=product]` |
| `parent > child` | Direct child | `ul > li` |
| `ancestor descendant` | Any descendant | `article p` |
| `prev + next` | Adjacent sibling | `h2 + p` |
| `prev ~ siblings` | All following siblings | `h2 ~ p` |
| `:contains(text)` | Contains text | `a:contains(Read more)` |
| `:matches(regex)` | Text matches regex | `td:matches(^\d+\.\d+$)`, `span:matches(Price)` |
| `:has(selector)` | Contains matching child | `div:has(.price)` |
| `:not(selector)` | Negation | `li:not(.sold-out)` |
| `:nth-child(n)` | Nth child | `tr:nth-child(1)` |
| `:first-child` / `:last-child` | First/last child | `li:first-child` |
| `:expr(js-expression)` | **X-SQL extension** — evaluate a JS expression | `img:expr(width > 400)` |

### Selector Strategy

1. **Be specific but not brittle** — `.product-title` is better than `div:nth-child(3) > span:nth-child(1)`
2. **Use semantic selectors first** — `[data-testid]`, `[itemprop]`, `[property]` are more stable than layout classes
3. **Use `:expr()` for computed properties** — `img:expr(width > 400)` filters by actual rendered size, not just markup
4. **Use `:matches()` for text patterns** — `td:matches(^\$\d+\.\d{2})` matches currency-formatted cells
5. **Combine with `:has()` for context** — `div:has(.price)` selects containers that contain a price

## Step-by-Step Approach for Any Scraping Task

When given a scraping task, follow this process:

### Step 1: Understand the target

Ask yourself:
- What is the page URL?
- What constitutes a "row" (product, article, search result)?
- What fields need to be extracted per row?

### Step 2: Identify the row selector

Choose the CSS selector that matches each item. Look for repeating container elements:
- Product list: `.product-card`, `[data-product]`, `li.product`
- News feed: `article`, `tr.athing`, `.post`
- Search results: `.g`, `.result`, `[data-result]`

### Step 3: Identify field selectors within each row

For each field, determine the CSS selector relative to the row:
- Title: `h2`, `.title`, `a:first-child`
- Price: `.price`, `[data-price]`, `span:contains($)`
- Link: `a` (use `dom_first_href`)
- Image: `img` (use `dom_first_img`)
- Date: `time`, `.date`, `[datetime]`

### Step 4: Choose the right table function

- **Single page, list of items** → `load_and_select(url, rowSelector)`
- **Multiple known URLs** → `load_all(ARRAY(url1, url2, ...))`
- **Crawl from a portal/index page** → `load_out_pages(portalUrl, linkAreaCss)`
- **Two-level nesting** → `load_and_select` + `JOIN select()`

### Step 5: Compose the SELECT clause

Map each desired field to a DOM function:
- Text → `dom_first_text(dom, css)`
- Number → `dom_first_float(dom, css, default)` or `dom_first_integer(dom, css, default)`
- Link → `dom_first_href(dom, css)`
- Image → `dom_first_img(dom, css)`
- Attribute → `dom_first_attr(dom, css, attrName)`
- HTML → `dom_first_slim_html(dom, css)`

### Step 6: Add cleanup with STR functions

Post-process extracted values:
- `str_substring_after(price, '$')` to strip currency symbols
- `str_trim(title)` to remove extra whitespace
- `str_first_float(text, 0.0)` to parse numbers from free text

### Step 7: Add WHERE clause (optional)

Filter rows by business rules:
- `WHERE str_is_not_blank(title)`
- `WHERE price > 0`
- `WHERE str_contains_any(str_lower_case(title), 'keyword')`

## Parameters

| Parameter | Type | Required | Default | Description |
|---|---|---|---|---|
| query | String | Yes | - | The X-SQL query to execute |
| url | String | No | - | Convenience: URL to scrape (used to construct a simple query) |
| selector | String | No | - | Convenience: CSS row selector (used with `url` to construct a query) |
| fields | Map<String, String> | No | - | Convenience: Field name → CSS selector mapping for quick extraction |

## Return Value

Returns a `SkillResult` with the following structure:

```json
{
  "query": "the executed X-SQL query",
  "columns": ["col1", "col2", "..."],
  "rows": [
    {"col1": "value1", "col2": "value2"},
    {"col1": "value3", "col2": "value4"}
  ],
  "rowCount": 42,
  "truncated": false
}
```

## Usage Examples

### Execute a raw X-SQL query

```kotlin
val result = registry.execute(
    skillId = "x-sql",
    context = context,
    params = mapOf(
        "query" to """
            SELECT
              dom_first_text(dom, 'h2 a') AS title,
              dom_first_href(dom, 'h2 a') AS link,
              dom_first_text(dom, '.score') AS score
            FROM load_and_select('https://news.ycombinator.com', 'tr.athing', 1, 30)
        """.trimIndent()
    )
)
```

### Quick extraction with field mapping

```kotlin
val result = registry.execute(
    skillId = "x-sql",
    context = context,
    params = mapOf(
        "url" to "https://books.toscrape.com",
        "selector" to "article.product_pod",
        "fields" to mapOf(
            "title" to "h3 a",
            "price" to ".price_color",
            "image" to "img",
            "link" to "h3 a"
        )
    )
)
```

### Crawl and extract from linked pages

```kotlin
val result = registry.execute(
    skillId = "x-sql",
    context = context,
    params = mapOf(
        "query" to """
            SELECT
              dom_base_uri(dom) AS article_url,
              dom_first_text(dom, 'h1') AS title,
              dom_first_text(dom, 'article .content') AS body
            FROM load_out_pages('https://example.com/blog', 'main a.post-link', 1, 10)
        """.trimIndent()
    )
)
```

### LLM-based extraction

```kotlin
val result = registry.execute(
    skillId = "x-sql",
    context = context,
    params = mapOf(
        "query" to """
            SELECT llm_extract(dom,
              'name: product name, ' ||
              'price: price as number, ' ||
              'features: key features as a list'
            ) AS product_data
            FROM load_and_select('https://example.com/product/42', 'body')
        """.trimIndent()
    )
)
```

## Error Handling

The skill returns a failure result in the following cases:

| Error | Cause |
|---|---|
| Missing required parameter `query` | Neither `query` nor (`url` + `selector`) provided |
| Invalid URL | URL format is malformed or unsupported |
| CSS selector matches nothing | The row selector returned zero elements (page structure may have changed) |
| Page load failure | Network error, timeout, or the target server rejected the request |
| SQL syntax error | Malformed X-SQL query |
| Namespace or function not found | Referenced a non-existent function |

## Lifecycle Hooks

### onBeforeExecute
Validates that at least `query` or (`url` + `selector`) is present. If `url` + `selector` + `fields` are provided, auto-generates the X-SQL query.

### onAfterExecute
Records execution metrics (query duration, row count) and caches page content if caching is enabled.

### validate
Checks that the X-SQL engine is available and the H2 database is initialized.

## Implementation Notes

- X-SQL is built on the H2 database engine; queries execute within an H2 SQL session
- Page loading is handled by PulsarSession, which manages caching, fetch policies, and rate limiting
- DOM parsing uses Jsoup under the hood
- The `:expr()` pseudo-class evaluates JavaScript expressions on each element's computed properties (size, position, styles)
- All DOM functions are null-safe: if a selector matches nothing, they return empty strings, 0, or NIL rather than throwing
- Parallel page loads (`load_all`, `load_out_pages`) respect the session's concurrency limits
- LLM functions require an LLM backend to be configured in the session

## Best Practices

1. **Be specific with CSS selectors** — `.product-title` is more maintainable than `div > span:nth-child(3)`
2. **Limit rows during development** — start with `limit=5` to test your selector logic before scaling up
3. **Use cache options for repeated queries** — append `-i 1d` to the URL to cache pages for a day
4. **Prefer `dom_first_*` over `dom_all_*` + `explode`** — unless you genuinely need array flattening
5. **Extract once, clean with STR functions** — use `str_trim`, `str_substring_after`, etc. rather than complex regex in selectors
6. **Use `:matches()` for content-based filtering** — `td:matches(^Price)` is clearer than extracting then filtering with WHERE
7. **Handle missing data with defaults** — `dom_first_float(dom, '.price', 0.0)` returns 0.0 rather than NULL
8. **Prefer LLM extraction only when CSS selectors aren't practical** — CSS is faster, cheaper, and deterministic

## See Also

- [X-SQL Documentation](/docs/x-sql.md) — Complete function reference
- [Web Scraping Skill](../web-scraping/SKILL.md) — Browser-automation based scraping
- [Form Filling Skill](../form-filling/SKILL.md) — Form automation
- [Data Validation Skill](../data-validation/SKILL.md) — Data quality checks
