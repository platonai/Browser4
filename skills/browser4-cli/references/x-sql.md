---
title: "X-SQL Reference: DOM & String Functions"
description: "Master index for the X-SQL function reference. Links to DOM_LOAD_AND_SELECT, DomFunctions, DomSelectFunctions, StringFunctions, and ArrayFunctions documentation."
tier: catalog
---

# X-SQL Reference: DOM & String Functions

This directory contains the X-SQL function reference, split by function group. Use the links below to read only the section you need.

**SQL constraint:** All queries extracting page data MUST use this pattern:

```sql
SELECT <expressions>
FROM DOM_LOAD_AND_SELECT(url, cssQuery [, offset, limit])
[WHERE <conditions>]
[ORDER BY <expression> [ASC|DESC]]
[LIMIT <n>]
```

No other SQL syntax is supported — no CTEs (`WITH`), no subqueries in `FROM`, no `EXPLODE`, no joins. The only valid table source is `DOM_LOAD_AND_SELECT`.

**URL parameter:** When used through `htmlsnapshot query` or `swarm query`, use the **unquoted** `@url` placeholder to reference the target page URL. Do NOT use `'.'` as a literal URL — it is not valid and will cause a 500 error. The `@url` placeholder is replaced with the actual page URL by `SQLTemplate.createSQL()`.

X-SQL uses the **H2 database** SQL dialect.

---

## Files

| File | Content | Lines |
|------|---------|-------|
| [x-sql-dom-load-select.md](x-sql-dom-load-select.md) | `DOM_LOAD_AND_SELECT` — Page loading with CSS selection | ~55 |
| [x-sql-dom-functions.md](x-sql-dom-functions.md) | `DomFunctions` — Core DOM operations (~65 functions) | ~500 |
| [x-sql-dom-select-functions.md](x-sql-dom-select-functions.md) | `DomSelectFunctions` — CSS selector-based extraction (~50 functions) | ~200 |
| [x-sql-string-functions.md](x-sql-string-functions.md) | `StringFunctions` — String manipulation (~90 functions) | ~430 |
| [x-sql-array-functions.md](x-sql-array-functions.md) | `ArrayFunctions` — Array operations (3 functions) | ~100 |

---

## Quick Reference: Common Patterns

### Scrape a list page (products, articles, etc.)

```sql
SELECT
    DOM_FIRST_TEXT(DOM, '.title') AS title,
    DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS price,
    DOM_FIRST_HREF(DOM, 'a.title-link') AS link,
    DOM_FIRST_IMG(DOM, 'img.thumbnail') AS image,
    STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(DOM, '.description'), 'N/A') AS description
FROM DOM_LOAD_AND_SELECT(
    'https://example.com/products -expires 1h',
    '.product-card',
    1, 20
)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, '.title'));
```

### Extract page metadata

```sql
SELECT
    DOM_DOC_TITLE(DOM) AS page_title,
    DOM_FIRST_TEXT(DOM, 'meta[name="description"]') AS meta_description,
    DOM_FIRST_IMG(DOM, 'article img') AS hero_image,
    STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '.reading-time'), 0.0) AS reading_minutes
FROM DOM_LOAD_AND_SELECT('https://example.com/article/123', ':root');
```

### Clean and transform scraped text

```sql
SELECT
    DOM_TEXT(DOM) AS raw_text,
    STR_NORMALIZE_SPACE(STR_TRIM(DOM_TEXT(DOM))) AS cleaned,
    STR_DEFAULT_IF_BLANK(
        STR_ABBREVIATE(STR_NORMALIZE_SPACE(STR_TRIM(DOM_TEXT(DOM))), 200),
        '[empty]'
    ) AS display_text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p');
```

### Extract key-value specs with regex

```sql
-- Extract "Label: Value" pairs from a specs table
SELECT DOM_ALL_RE2(DOM, '.specs-table tr', '(.+?):\s*(.+)') AS specs
FROM DOM_LOAD_AND_SELECT('https://example.com/product/42', '.specs-table');

-- Extract all prices from a page
SELECT DOM_ALL_RE1(DOM, '.price', '\$([\d,]+\.?\d*)') AS prices
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/sale', 'body');
```

### DOM tree analysis

```sql
-- Find the content-heavy containers on a page
SELECT
    DOM_CSS_SELECTOR(DOM) AS selector,
    DOM_TAG_NAME(DOM) AS tag,
    DOM_TEXT_LEN(DOM) AS text_chars,
    DOM_A(DOM) AS links,
    DOM_IMG(DOM) AS images,
    DOM_DEP(DOM) AS depth
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div,section,article,main')
WHERE DOM_TEXT_LEN(DOM) > 200
ORDER BY DOM_TEXT_LEN(DOM) DESC;
```

### Array-based fallback chains

```sql
-- Try multiple selectors and use the first one that returns content
SELECT ARRAY_FIRST_NOT_BLANK(
    MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, 'h1.product-title'),
        DOM_FIRST_TEXT(DOM, '.product-name'),
        DOM_FIRST_TEXT(DOM, 'title'),
        'Unknown Product'
    )
) AS product_name
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/product/42', 'body');
```

---

## Function Input/Output Types & Composability

X-SQL functions fall into **two type categories** based on how they interact with DOM elements:

| Type | Signature | Examples | Can compose with |
|------|-----------|----------|-----------------|
| **ValueDom functions** | Take `(DOM [, selector])` where `DOM` is a `ValueDom` node | `DOM_TEXT(DOM)`, `DOM_ABS_SRC(DOM)`, `DOM_HREF(DOM)`, `DOM_DOC_TITLE(DOM)` | Other ValueDom functions: `DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img'))` ✅ |
| **Scalar functions** | Take `(DOM, selector)` and return `String`/`Int`/`Float` | `DOM_FIRST_TEXT(DOM, 'h1')`, `DOM_FIRST_ATTR(DOM, 'img', 'src')`, `DOM_FIRST_IMG(DOM, 'img')` | String functions: `STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(...), 'N/A')` ✅ |

**Critical rule:** You cannot pass a `String` (from a scalar function) where a `ValueDom` is expected, and vice versa.

### Common Composition Mistakes

```sql
-- ❌ WRONG: DOM_FIRST_IMG returns a String (the src attribute), not a ValueDom.
--    DOM_ABS_SRC expects a ValueDom, so it receives a String and fails with a
--    misleading 417 "scrape session closed" error.
SELECT DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img')) AS image_url
FROM DOM_LOAD_AND_SELECT(@url, '#product');

-- ✅ CORRECT: Use DOM_FIRST_ATTR to get the src attribute directly.
--    No DOM_ABS_SRC needed — just pass the attribute name.
SELECT DOM_FIRST_ATTR(DOM, 'img', 'src') AS image_url
FROM DOM_LOAD_AND_SELECT(@url, '#product');

-- ✅ ALSO CORRECT: Select the DOM element first, then get the absolute src.
SELECT DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img')) AS image_url
FROM DOM_LOAD_AND_SELECT(@url, '#product');
```

### Visual Composition Graph

```
ValueDom functions (input: DOM element, output: varies)
┌─────────────────────────────────────────────┐
│ DOM_LOAD()     → ValueDom                   │
│ DOM_SELECT_FIRST(DOM, sel) → ValueDom       │
│ DOM_PARENT(DOM) → ValueDom                  │
│ DOM_ANCESTOR(DOM, tag) → ValueDom           │
│                                             │
│ DOM_TEXT(DOM) → String                      │
│ DOM_ABS_SRC(DOM) → String      ⚠ expects    │
│ DOM_ABS_HREF(DOM) → String      ValueDom!   │
│ DOM_HREF(DOM) → String                      │
│ DOM_SRC(DOM) → String                       │
│ DOM_DOC_TITLE(DOM) → String                 │
│ DOM_BASE_URI(DOM) → String                  │
└─────────────────────────────────────────────┘
        ▲
        │ CAN compose: DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img'))
        │
        │ CANNOT compose: DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img'))
        │                 ↑ returns String, not ValueDom
        ▼
Scalar functions (input: DOM + selector string, output: scalar)
┌─────────────────────────────────────────────┐
│ DOM_FIRST_TEXT(DOM, sel) → String           │
│ DOM_FIRST_ATTR(DOM, sel, attr) → String     │
│ DOM_FIRST_IMG(DOM, sel) → String (src attr) │
│ DOM_FIRST_HREF(DOM, sel) → String           │
│ DOM_ALL_TEXTS(DOM, sel) → ValueArray        │
│ DOM_ALL_ATTRS(DOM, sel, attr) → ValueArray  │
└─────────────────────────────────────────────┘
```

---

## Function Index by SQL Alias

**Where to find detailed docs:** Functions in the "Element property", "Tree navigation", "Text", "Link/Image", "Regex", "HTML", "Feature", and "State check" categories are documented in [x-sql-dom-functions.md](x-sql-dom-functions.md). Functions in the "CSS select", "Attribute extraction", "Visual", and "DOM manipulation" categories are documented in [x-sql-dom-select-functions.md](x-sql-dom-select-functions.md). "Page loading" functions are in [x-sql-dom-load-select.md](x-sql-dom-load-select.md). String functions are in [x-sql-string-functions.md](x-sql-string-functions.md). Array functions are in [x-sql-array-functions.md](x-sql-array-functions.md).

### DOM Namespace

> **Legend:** `DOM` = `ValueDom` node (from `FROM DOM_LOAD_AND_SELECT`). `DOM, sel` = ValueDom + CSS selector string. `DOM, sel, attr` = ValueDom + selector + attribute name.

| SQL Alias | Input | Returns | Category |
|-----------|-------|---------|----------|
| `DOM_LOAD_AND_SELECT` | `(url, sel)` | `ResultSet` | Page loading + CSS selection |
| `DOM_LOAD` | `(url)` | `ValueDom` | Page loading |
| `DOM_FETCH` | `(url)` | `ValueDom` | Page loading |
| `DOM_IS_NIL` | `(DOM)` | `Boolean` | State check |
| `DOM_IS_NOT_NIL` | `(DOM)` | `Boolean` | State check |
| `DOM_ATTR` | `(DOM, attr)` | `String` | Element property → DomFunctions |
| `DOM_LABELS` | `(DOM)` | `String` | Element property |
| `DOM_FEATURE` | `(DOM, feat)` | `Double` | Element property |
| `DOM_HAS_ATTR` | `(DOM, attr)` | `Boolean` | Element property |
| `DOM_STYLE` | `(DOM, prop)` | `String` | Element property |
| `DOM_SEQUENCE` | `(DOM)` | `Int` | Element property |
| `DOM_DEPTH` | `(DOM)` | `Int` | Element property |
| `DOM_CSS_SELECTOR` | `(DOM)` | `String` | Element property |
| `DOM_CSS_PATH` | `(DOM)` | `String` | Element property |
| `DOM_SIBLING_SIZE` | `(DOM)` | `Int` | Tree navigation |
| `DOM_SIBLING_INDEX` | `(DOM)` | `Int` | Tree navigation |
| `DOM_ELEMENT_SIBLING_SIZE` | `(DOM)` | `Int` | Tree navigation |
| `DOM_ELEMENT_SIBLING_INDEX` | `(DOM)` | `Int` | Tree navigation |
| `DOM_URI` | `(DOM)` | `String` | URL/Location |
| `DOM_BASE_URI` | `(DOM)` | `String` | URL/Location |
| `DOM_ABS_URL` | `(DOM, url)` | `String` | URL/Location |
| `DOM_LOCATION` | `(DOM)` | `String` | URL/Location |
| `DOM_CHILD_NODE_SIZE` | `(DOM)` | `Int` | Tree navigation |
| `DOM_CHILD_ELEMENT_SIZE` | `(DOM)` | `Int` | Tree navigation |
| `DOM_TAG_NAME` | `(DOM)` | `String` | Element identity |
| `DOM_HREF` | `(DOM)` | `String` | Link/Image |
| `DOM_ABS_HREF` | `(DOM)` | `String` | Link/Image |
| `DOM_SRC` | `(DOM)` | `String` | Link/Image |
| `DOM_ABS_SRC` | `(DOM)` ⚠ | `String` | Link/Image |
| `DOM_TITLE` | `(DOM)` | `String` | Title |
| `DOM_DOC_TITLE` | `(DOM)` | `String` | Title |
| `DOM_HAS_TEXT` | `Boolean` | Text |
| `DOM_TEXT` | `String` | Text |
| `DOM_TEXT_LEN` | `Int` | Text |
| `DOM_TEXT_LENGTH` | `Int` | Text |
| `DOM_OWN_TEXT` | `String` | Text |
| `DOM_OWN_TEXTS` | `ValueArray` | Text |
| `DOM_OWN_TEXT_LEN` | `Int` | Text |
| `DOM_WHOLE_TEXT` | `String` | Text |
| `DOM_WHOLE_TEXT_LEN` | `Int` | Text |
| `DOM_RE1` | `String` | Regex |
| `DOM_RE2` | `ValueArray` | Regex |
| `DOM_DATA` | `String` | Element identity |
| `DOM_ID` | `String` | Element identity |
| `DOM_CLASS_NAME` | `String` | Element identity |
| `DOM_CLASS_NAMES` | `Set` | Element identity |
| `DOM_HAS_CLASS` | `Boolean` | Element identity |
| `DOM_VALUE` | `String` | Element identity |
| `DOM_OWNER_DOCUMENT` | `ValueDom` | Tree navigation |
| `DOM_OWNER_BODY` | `ValueDom` | Tree navigation |
| `DOM_DOCUMENT_VARIABLES` | `ValueDom` | Tree navigation |
| `DOM_PARENT` | `ValueDom` | Tree navigation |
| `DOM_ANCESTOR` | `ValueDom` | Tree navigation |
| `DOM_PARENT_NAME` | `String` | Tree navigation |
| `DOM_DOM` | `ValueDom` | HTML |
| `DOM_HTML` | `String` | HTML |
| `DOM_OUTER_HTML` | `String` | HTML |
| `DOM_SLIM_HTML` | `String` | HTML |
| `DOM_MINIMAL_HTML` | `String` | HTML |
| `DOM_UNIQUE_NAME` | `String` | Element identity |
| `DOM_LINKS` | `ValueArray` | Link/Image |
| `DOM_CH` | `Double` | Feature |
| `DOM_TN` | `Double` | Feature |
| `DOM_IMG` | `Double` | Feature |
| `DOM_A` | `Double` | Feature |
| `DOM_SIB` | `Double` | Feature |
| `DOM_C` | `Double` | Feature |
| `DOM_DEP` | `Double` | Feature |
| `DOM_SEQ` | `Double` | Feature |
| `DOM_TOP` | `Double` | Feature |
| `DOM_LEFT` | `Double` | Feature |
| `DOM_WIDTH` | `Double` | Feature |
| `DOM_HEIGHT` | `Double` | Feature |
| `DOM_AREA` | `Double` | Feature |
| `DOM_ASPECT_RATIO` | `Double` | Feature |
| `DOM_SELECT_ALL` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_SELECT_FIRST` | `(DOM, sel)` | `ValueDom` | CSS select |
| `DOM_SELECT_NTH` | `(DOM, sel, n)` | `ValueDom` | CSS select |
| `DOM_ALL_TEXTS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_TEXT` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_TEXT` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_ALL_OWN_TEXTS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_OWN_TEXT` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_OWN_TEXT` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_WHOLE_TEXTS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_WHOLE_TEXT` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_WHOLE_TEXT` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_ALL_SLIM_HTMLS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_SLIM_HTML` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_SLIM_HTML` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_ALL_MINIMAL_HTMLS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_MINIMAL_HTML` | `(DOM, sel)` | `String` | CSS select |
| `DOM_NTH_MINIMAL_HTML` | `(DOM, sel, n)` | `String` | CSS select |
| `DOM_ALL_INTEGERS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_INTEGER` | `(DOM, sel)` | `Int` | CSS select |
| `DOM_NTH_INTEGER` | `(DOM, sel, n)` | `Int` | CSS select |
| `DOM_ALL_FLOATS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_FLOAT` | `(DOM, sel)` | `ValueFloat` | CSS select |
| `DOM_NTH_FLOAT` | `(DOM, sel, n)` | `ValueFloat` | CSS select |
| `DOM_ALL_ATTRS` | `(DOM, sel, attr)` | `ValueArray` | CSS select |
| `DOM_FIRST_ATTR` | `(DOM, sel, attr)` | `String` | CSS select |
| `DOM_NTH_ATTR` | `(DOM, sel, attr, n)` | `String` | CSS select |
| `DOM_ALL_MULTI_ATTRS` | `(DOM, sel, attrs)` | `ValueArray` | CSS select |
| `DOM_FIRST_MULTI_ATTRS` | `(DOM, sel, attrs)` | `List` | CSS select |
| `DOM_NTH_MULTI_ATTRS` | `(DOM, sel, attrs, n)` | `List` | CSS select |
| `DOM_ALL_IMGS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_IMG` | `(DOM, sel)` ⚠ | `String` | CSS select |
| `DOM_NTH_IMG` | `(DOM, sel, n)` ⚠ | `String` | CSS select |
| `DOM_ALL_HREFS` | `(DOM, sel)` | `ValueArray` | CSS select |
| `DOM_FIRST_HREF` | `(DOM, sel)` ⚠ | `String` | CSS select |
| `DOM_NTH_HREF` | `(DOM, sel, n)` ⚠ | `String` | CSS select |
| `DOM_ALL_NODES_LABELS` | `ValueArray` | CSS select |
| `DOM_FIRST_NODE_LABELS` | `String` | CSS select |
| `DOM_NTH_NODE_LABELS` | `String` | CSS select |
| `DOM_ALL_RE1` | `ValueArray` | CSS select + regex |
| `DOM_FIRST_RE1` | `String` | CSS select + regex |
| `DOM_ALL_RE2` | `ValueArray` | CSS select + regex |
| `DOM_FIRST_RE2` | `ValueArray` | CSS select + regex |

> ⚠ **Warning:** Functions marked with ⚠ return a **scalar** (`String`), not a `ValueDom`. They select an element AND extract a property in one step. Their results **cannot** be passed to `ValueDom` functions like `DOM_ABS_SRC`, `DOM_ABS_HREF`, `DOM_TEXT`, etc. Use the `DOM_SELECT_*` + property-function pattern instead for composable DOM access (see [§Composability](#function-inputoutput-types--composability) above).

> **Note on `DOM_FIRST_HREF`:** For href extraction, `DOM_FIRST_HREF(DOM, sel)` can return an empty string for a class-only selector (e.g. `.product-link`) while the tag-qualified form (`a.product-link`) works. Prefer `DOM_FIRST_ATTR(DOM, sel, 'href')` — it accepts any selector and returns the href consistently (relative; use `DOM_ABS_HREF` or `abs:href` for the absolute URL).

### STR Namespace

| SQL Alias | Returns | Category |
|-----------|---------|----------|
| `STR_CAPITALIZE` | `String?` | Case |
| `STR_UNCAPITALIZE` | `String?` | Case |
| `STR_SWAP_CASE` | `String?` | Case |
| `STR_UPPER_CASE` | `String?` | Case |
| `STR_LOWER_CASE` | `String?` | Case |
| `STR_IS_EMPTY` | `Boolean` | Check |
| `STR_IS_NOT_EMPTY` | `Boolean` | Check |
| `STR_IS_BLANK` | `Boolean` | Check |
| `STR_IS_NOT_BLANK` | `Boolean` | Check |
| `STR_IS_ANY_EMPTY` | `Boolean` | Check |
| `STR_IS_NONE_EMPTY` | `Boolean` | Check |
| `STR_IS_ANY_BLANK` | `Boolean` | Check |
| `STR_IS_NONE_BLANK` | `Boolean` | Check |
| `STR_TRIM` | `String?` | Trim/Strip |
| `STR_TRIM_TO_NULL` | `String?` | Trim/Strip |
| `STR_TRIM_TO_EMPTY` | `String?` | Trim/Strip |
| `STR_STRIP` | `String?` | Trim/Strip |
| `STR_STRIP_TO_NULL` | `String?` | Trim/Strip |
| `STR_STRIP_TO_EMPTY` | `String?` | Trim/Strip |
| `STR_STRIP_START` | `String?` | Trim/Strip |
| `STR_STRIP_END` | `String?` | Trim/Strip |
| `STR_STRIP_ALL` | `Array` | Trim/Strip |
| `STR_STRIP_ACCENTS` | `String?` | Trim/Strip |
| `STR_SUBSTRING` | `String?` | Substring |
| `STR_LEFT` | `String?` | Substring |
| `STR_RIGHT` | `String?` | Substring |
| `STR_MID` | `String?` | Substring |
| `STR_SUBSTRING_BEFORE` | `String?` | Substring |
| `STR_SUBSTRING_AFTER` | `String?` | Substring |
| `STR_SUBSTRING_BEFORE_LAST` | `String?` | Substring |
| `STR_SUBSTRING_AFTER_LAST` | `String?` | Substring |
| `STR_SUBSTRING_BETWEEN` | `String?` | Substring |
| `STR_SUBSTRINGS_BETWEEN` | `Array` | Substring |
| `STR_CONTAINS_WHITESPACE` | `Boolean` | Search |
| `STR_CONTAINS_ANY` | `Boolean` | Search |
| `STR_CONTAINS_ONLY` | `Boolean` | Search |
| `STR_CONTAINS_NONE` | `Boolean` | Search |
| `STR_INDEX_OF_ANY` | `Int` | Search |
| `STR_INDEX_OF_ANY_BUT` | `Int` | Search |
| `STR_ORDINAL_INDEX_OF` | `Int` | Search |
| `STR_LAST_ORDINAL_INDEX_OF` | `Int` | Search |
| `STR_INDEX_OF_DIFFERENCE` | `Int` | Search |
| `STR_COUNT_MATCHES` | `Int` | Search |
| `STR_GET_COMMON_PREFIX` | `String?` | Search |
| `STR_SPLIT` | `Array` | Split/Join |
| `STR_SPLIT_BY_WHOLE_SEPARATOR` | `Array` | Split/Join |
| `STR_SPLIT_PRESERVE_ALL_TOKENS` | `Array` | Split/Join |
| `STR_SPLIT_BY_CHARACTER_TYPE` | `Array` | Split/Join |
| `STR_SPLIT_BY_CHARACTER_TYPE_CAMEL_CASE` | `Array` | Split/Join |
| `STR_JOIN` | `String?` | Split/Join |
| `STR_REPLACE_EACH` | `String?` | Replace |
| `STR_REPLACE_EACH_REPEATEDLY` | `String?` | Replace |
| `STR_REPLACE_CHARS` | `String?` | Replace |
| `STR_OVERLAY` | `String?` | Replace |
| `STR_DELETE_WHITESPACE` | `String?` | Replace |
| `STR_CHOMP` | `String?` | Replace |
| `STR_CHOP` | `String?` | Replace |
| `STR_NORMALIZE_SPACE` | `String?` | Replace |
| `STR_LEFT_PAD` | `String?` | Padding |
| `STR_RIGHT_PAD` | `String?` | Padding |
| `STR_CENTER` | `String?` | Padding |
| `STR_REPEAT` | `String?` | Utility |
| `STR_REVERSE` | `String?` | Utility |
| `STR_REVERSE_DELIMITED` | `String?` | Utility |
| `STR_DIFFERENCE` | `String?` | Utility |
| `STR_LENGTH` | `Int` | Utility |
| `STR_ABBREVIATE` | `String?` | Utility |
| `STR_ABBREVIATE_MIDDLE` | `String?` | Utility |
| `STR_DEFAULT_STRING` | `String?` | Utility |
| `STR_DEFAULT_IF_BLANK` | `String?` | Utility |
| `STR_DEFAULT_IF_EMPTY` | `String?` | Utility |
| `STR_TO_ENCODED_STRING` | `String?` | Utility |
| `STR_IS_ALPHA` | `Boolean` | Classification |
| `STR_IS_NUMERIC` | `Boolean` | Classification |
| `STR_IS_WHITESPACE` | `Boolean` | Classification |
| `STR_IS_ALPHA_SPACE` | `Boolean` | Classification |
| `STR_IS_ALPHANUMERIC` | `Boolean` | Classification |
| `STR_IS_ALPHANUMERIC_SPACE` | `Boolean` | Classification |
| `STR_IS_ASCII_PRINTABLE` | `Boolean` | Classification |
| `STR_IS_NUMERIC_SPACE` | `Boolean` | Classification |
| `STR_IS_ALL_LOWER_CASE` | `Boolean` | Classification |
| `STR_IS_ALL_UPPER_CASE` | `Boolean` | Classification |
| `STR_FIRST_INTEGER` | `Int` | Number extraction |
| `STR_FIRST_FLOAT` | `Float` | Number extraction |
| `STR_GET_FIRST_FLOAT_NUMBER` | `Float` | Number extraction |

### ARRAY Namespace

| SQL Alias | Returns | Description |
|-----------|---------|-------------|
| `ARRAY_JOIN_TO_STRING` | `String` | Join array elements with separator |
| `ARRAY_FIRST_NOT_BLANK` | `Value?` | First non-blank value |
| `ARRAY_FIRST_NOT_EMPTY` | `Value?` | First non-empty value |
