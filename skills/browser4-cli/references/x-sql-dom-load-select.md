---
title: "X-SQL: DOM_LOAD_AND_SELECT — Page Loading with CSS Selection"
description: "Reference for DOM_LOAD_AND_SELECT. The required table-source function for all X-SQL page-data queries — loads a web page and selects elements matching a CSS query."
tier: catalog
---

# X-SQL: DOM_LOAD_AND_SELECT — Page Loading with CSS Selection

> **Parent:** [x-sql.md](x-sql.md) — full function index and quick-reference patterns
>
> **Related:** [DomFunctions](x-sql-dom-functions.md) | [DomSelectFunctions](x-sql-dom-select-functions.md) | [StringFunctions](x-sql-string-functions.md) | [ArrayFunctions](x-sql-array-functions.md)

**Source:** `DomFunctionTables.kt` | **Namespace:** `DOM`

---

## DOM_LOAD_AND_SELECT

```
DOM_LOAD_AND_SELECT(url, cssQuery [, offset, limit])
```

Loads a web page and immediately selects elements matching a CSS query. Returns a `ResultSet` of DOM objects — use this as a table source with `SELECT * FROM DOM_LOAD_AND_SELECT(...)`.

**Parameters:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | required | The URL to load. Append space-separated load options (e.g. `https://example.com -expires 1h`) to control caching, rendering, etc. |
| `cssQuery` | `String` | required | CSS selector to match elements on the page |
| `offset` | `Int` | `1` | 1-based offset into the matched element set |
| `limit` | `Int` | `MAX_VALUE` | Maximum number of elements to return |

**Returns:** `ResultSet` with DOM column — each row is a `ValueDom` that can be passed to other DOM functions.

> **URL parameter:** When used through `htmlsnapshot query`, use the **unquoted** `@url` placeholder to reference the target page URL. The `SQLTemplate` engine replaces `@url` with the properly escaped URL before execution.
> - ✅ `DOM_LOAD_AND_SELECT(@url, 'h1')` — `@url` is replaced with the actual page URL
> - ❌ `DOM_LOAD_AND_SELECT('.', 'h1')` — the literal `'.'` string is not a valid URL and will cause a 500 error
>
> The `@url` placeholder must appear **unquoted**. Do not wrap it in quotes: `'@url'` will be treated as a literal string, not a placeholder.

**Examples:**

```sql
-- Load a page and select all <h1> elements
SELECT * FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1');

-- Combine with DOM functions to extract text from each result
SELECT DOM_TEXT(DOM) AS title
FROM DOM_LOAD_AND_SELECT('https://example.com', 'article h2');

-- Load with expiration control (fetch fresh if older than 1 hour)
SELECT DOM_FIRST_TEXT(DOM, 'title')
FROM DOM_LOAD_AND_SELECT('https://example.com -expires 1h', 'h1');

-- Select only the first 5 product cards
SELECT DOM_TEXT(DOM) AS product_name
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/products', '.product-card', 1, 5);
```

**Pattern: Scrape a list page with multiple fields per item:**

```sql
SELECT
    DOM_FIRST_TEXT(DOM, '.title') AS title,
    DOM_FIRST_TEXT(DOM, '.price') AS price,
    DOM_FIRST_HREF(DOM, 'a') AS link,
    DOM_FIRST_IMG(DOM, 'img') AS image
FROM DOM_LOAD_AND_SELECT('https://example.com/list', '.item');
```
