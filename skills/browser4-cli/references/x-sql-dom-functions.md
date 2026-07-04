---
title: "X-SQL: DomFunctions — Core DOM Operations"
description: "Reference for ~65 DOM functions: page loading, state checks, element properties, URL/location, tree navigation, element identity, link/image props, title, text, HTML serialization, regex, and computed features."
tier: catalog
---

# X-SQL: DomFunctions — Core DOM Operations

> **Parent:** [x-sql.md](x-sql.md) — full function index and quick-reference patterns
>
> **Related:** [DOM_LOAD_AND_SELECT](x-sql-dom-load-select.md) | [DomSelectFunctions](x-sql-dom-select-functions.md) | [StringFunctions](x-sql-string-functions.md)

**Source:** `DomFunctions.kt` | **Namespace:** `DOM` | **~65 functions**

> **SQL constraint:** All page-data queries must use `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)`. No CTEs, subqueries, `EXPLODE`, or `FROM DOM_LOAD(...)` are supported. `DOM_LOAD` and `DOM_FETCH` are expression functions (usable in SELECT, not FROM).

---

## Table of Contents

- [2.1 Page Loading](#21-page-loading)
- [2.2 DOM State Checks](#22-dom-state-checks)
- [2.3 Element Properties](#23-element-properties)
- [2.4 URL & Location](#24-url--location)
- [2.5 DOM Tree Navigation](#25-dom-tree-navigation)
- [2.6 Element Identity](#26-element-identity)
- [2.7 Link & Image Properties](#27-link--image-properties)
- [2.8 Title](#28-title)
- [2.9 Text Extraction](#29-text-extraction)
- [2.10 HTML Serialization](#210-html-serialization)
- [2.11 Regex Extraction on DOM Text](#211-regex-extraction-on-dom-text)
- [2.12 Computed Features](#212-computed-features)

---

## 2.1 Page Loading

### DOM_LOAD

```
DOM_LOAD(configuredUrl)
```

Loads a page from the database cache, or fetches it from the web if absent or expired. Returns a single `ValueDom` — usable as an expression, not a table source.

```sql
-- Expression usage: get page title
SELECT DOM_DOC_TITLE(DOM_LOAD('https://example.com'));

-- Equivalent via DOM_LOAD_AND_SELECT (preferred table-source pattern)
SELECT DOM_DOC_TITLE(DOM)
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

### DOM_FETCH

```
DOM_FETCH(configuredUrl)
```

Forces an immediate web fetch, bypassing the cache entirely (sets expiry to zero). Returns a single `ValueDom` — usable as an expression, not a table source.

```sql
-- Expression usage: fetch latest prices
SELECT DOM_TEXT(DOM_FETCH('https://example.com/live-prices'));

-- Equivalent via DOM_LOAD_AND_SELECT with refresh option
SELECT DOM_TEXT(DOM)
FROM DOM_LOAD_AND_SELECT('https://example.com/live-prices -expires 0', ':root');
```

---

## 2.2 DOM State Checks

### DOM_IS_NIL

```
DOM_IS_NIL(dom)
```

Returns `true` if the DOM is nil (empty, invalid, or failed to load).

```sql
-- Filter out failed page loads
SELECT url, DOM_IS_NIL(DOM) AS failed
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

### DOM_IS_NOT_NIL

```
DOM_IS_NOT_NIL(dom)
```

Returns `true` if the DOM is valid and contains content.

```sql
-- Only process successfully loaded pages
SELECT DOM_TEXT(DOM) AS text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p')
WHERE DOM_IS_NOT_NIL(DOM);
```

---

## 2.3 Element Properties

### DOM_ATTR

```
DOM_ATTR(dom, attrName)
```

Gets the value of any HTML attribute on the element.

```sql
-- Get the 'data-id' attribute from each product card
SELECT DOM_ATTR(DOM, 'data-id') AS product_id
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', '.product');

-- Get href from all links
SELECT DOM_ATTR(DOM, 'href') AS link_url
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a');
```

### DOM_LABELS

```
DOM_LABELS(dom)
```

Gets the Pulsar `A_LABELS` attribute — machine-learned node classification labels.

```sql
-- See what Pulsar thinks each element is
SELECT DOM_TAG_NAME(DOM) AS tag, DOM_LABELS(DOM) AS labels
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div,p,ul,li');
```

### DOM_FEATURE

```
DOM_FEATURE(dom, featureName)
```

Gets any computed feature value by name. Returns `Double`.

```sql
-- Get a specific feature by name
SELECT DOM_FEATURE(DOM, 'CH') AS char_count
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p');

-- Get the sibling count feature
SELECT DOM_FEATURE(DOM, 'SIB') AS siblings
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div');
```

### DOM_HAS_ATTR

```
DOM_HAS_ATTR(dom, attrName)
```

Checks whether the element has a specific HTML attribute.

```sql
-- Find all elements that have a 'data-price' attribute
SELECT DOM_TAG_NAME(DOM) AS tag, DOM_ATTR(DOM, 'data-price') AS price
FROM DOM_LOAD_AND_SELECT('https://example.com', '*')
WHERE DOM_HAS_ATTR(DOM, 'data-price');
```

### DOM_STYLE

```
DOM_STYLE(dom, styleName)
```

Gets the computed CSS style value for the element.

```sql
-- Get the display and color styles
SELECT
    DOM_STYLE(DOM, 'display') AS display,
    DOM_STYLE(DOM, 'color') AS color
FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1');
```

### DOM_SEQUENCE & DOM_DEPTH

```
DOM_SEQUENCE(dom)  -- sequence number in document order
DOM_DEPTH(dom)     -- depth in the DOM tree
```

```sql
-- Find deeply nested elements
SELECT DOM_CSS_SELECTOR(DOM) AS path, DOM_DEPTH(DOM) AS depth
FROM DOM_LOAD_AND_SELECT('https://example.com', '*')
WHERE DOM_DEPTH(DOM) > 10
ORDER BY DOM_DEPTH(DOM) DESC
LIMIT 10;
```

### DOM_CSS_SELECTOR & DOM_CSS_PATH

```
DOM_CSS_SELECTOR(dom)  -- unique CSS selector for this element
DOM_CSS_PATH(dom)      -- alias for cssSelector
```

```sql
-- Get the unique CSS path for every heading
SELECT DOM_TEXT(DOM) AS heading, DOM_CSS_SELECTOR(DOM) AS css_path
FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1,h2,h3');
```

### DOM_SIBLING_SIZE & DOM_SIBLING_INDEX

```
DOM_SIBLING_SIZE(dom)          -- count of all sibling nodes (including text nodes)
DOM_SIBLING_INDEX(dom)         -- index among all sibling nodes
DOM_ELEMENT_SIBLING_SIZE(dom)  -- count of sibling elements only
DOM_ELEMENT_SIBLING_INDEX(dom) -- index among sibling elements
```

```sql
-- Find the first and last child elements of each container
SELECT
    DOM_TAG_NAME(DOM) AS container,
    DOM_CHILD_ELEMENT_SIZE(DOM) AS children_count
FROM DOM_LOAD_AND_SELECT('https://example.com', 'ul,ol,div.menu')
WHERE DOM_CHILD_ELEMENT_SIZE(DOM) > 0;
```

---

## 2.4 URL & Location

### DOM_URI

```
DOM_URI(dom)
```

Returns the page's normalized URI — the permanent internal address used as the database key.

```sql
-- See which actual URL was loaded (after normalization)
SELECT DOM_URI(DOM) AS normalized_url
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

### DOM_BASE_URI

```
DOM_BASE_URI(dom)
```

Returns the element's base URI (the last working address of the page).

```sql
SELECT DOM_BASE_URI(DOM) AS base_url
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

### DOM_ABS_URL

```
DOM_ABS_URL(dom, attributeKey)
```

Resolves a relative URL attribute to an absolute URL.

```sql
-- Resolve relative image paths to absolute URLs
SELECT DOM_ABS_URL(DOM, 'src') AS absolute_image_url
FROM DOM_LOAD_AND_SELECT('https://example.com', 'img');
```

### DOM_LOCATION

```
DOM_LOCATION(dom)
```

Returns the page's location — the last working address. May differ from `uri` if redirects occurred.

```sql
-- Detect if a redirect happened
SELECT
    DOM_URI(DOM) AS original,
    DOM_LOCATION(DOM) AS final_location
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root')
WHERE DOM_URI(DOM) != DOM_LOCATION(DOM);
```

---

## 2.5 DOM Tree Navigation

### DOM_CHILD_NODE_SIZE & DOM_CHILD_ELEMENT_SIZE

```
DOM_CHILD_NODE_SIZE(dom)     -- includes text nodes
DOM_CHILD_ELEMENT_SIZE(dom)  -- element nodes only
```

```sql
-- Find containers with many direct child elements
SELECT
    DOM_TAG_NAME(DOM) AS tag,
    DOM_CHILD_ELEMENT_SIZE(DOM) AS child_count
FROM DOM_LOAD_AND_SELECT('https://example.com', '*')
WHERE DOM_CHILD_ELEMENT_SIZE(DOM) > 20
ORDER BY DOM_CHILD_ELEMENT_SIZE(DOM) DESC;
```

### DOM_PARENT

```
DOM_PARENT(dom)
```

Returns the parent element as a new DOM.

```sql
-- Get the parent of each <a> tag
SELECT
    DOM_TEXT(DOM) AS link_text,
    DOM_TAG_NAME(DOM_PARENT(DOM)) AS parent_tag,
    DOM_CLASS_NAME(DOM_PARENT(DOM)) AS parent_class
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a');
```

### DOM_ANCESTOR

```
DOM_ANCESTOR(dom, n)
```

Returns the nth ancestor. `n=1` = parent, `n=2` = grandparent, etc.

```sql
-- Walk up to the 3rd ancestor
SELECT
    DOM_TAG_NAME(DOM) AS self,
    DOM_TAG_NAME(DOM_ANCESTOR(DOM, 3)) AS great_grandparent
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a.nav-link');
```

### DOM_PARENT_NAME

```
DOM_PARENT_NAME(dom)
```

Returns the unique name of the parent element. Returns `"nil"` if the DOM is nil.

```sql
SELECT DOM_TEXT(DOM) AS text, DOM_PARENT_NAME(DOM) AS container
FROM DOM_LOAD_AND_SELECT('https://example.com', 'span');
```

### DOM_OWNER_DOCUMENT, DOM_OWNER_BODY, DOM_DOCUMENT_VARIABLES

```
DOM_OWNER_DOCUMENT(dom)     -- the full document containing this element
DOM_OWNER_BODY(dom)         -- the <body> containing this element
DOM_DOCUMENT_VARIABLES(dom) -- the Pulsar meta-information element from <head>
```

```sql
-- Get document metadata from any element
SELECT DOM_DOC_TITLE(DOM_OWNER_DOCUMENT(DOM)) AS page_title
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p');

-- Access Pulsar meta information
SELECT DOM_TEXT(DOM_DOCUMENT_VARIABLES(DOM)) AS pulsar_meta
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

---

## 2.6 Element Identity

```sql
-- DOM_TAG_NAME: Get the HTML tag name
SELECT DOM_TAG_NAME(DOM) AS tag FROM DOM_LOAD_AND_SELECT('...', '*') LIMIT 10;

-- DOM_ID: Get the element's id attribute
SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT('...', '*')
WHERE DOM_ID(DOM) IS NOT NULL;

-- DOM_CLASS_NAME: Get the element's class attribute (full string)
SELECT DOM_CLASS_NAME(DOM) AS classes FROM DOM_LOAD_AND_SELECT('...', 'div');

-- DOM_CLASS_NAMES: Get class names as a set
SELECT DOM_CLASS_NAMES(DOM) AS class_set FROM DOM_LOAD_AND_SELECT('...', 'div.active');

-- DOM_HAS_CLASS: Check for a specific class
SELECT DOM_TEXT(DOM) AS text FROM DOM_LOAD_AND_SELECT('...', 'div')
WHERE DOM_HAS_CLASS(DOM, 'featured');

-- DOM_UNIQUE_NAME: Get the element's unique name identifier
SELECT DOM_UNIQUE_NAME(DOM) AS name FROM DOM_LOAD_AND_SELECT('...', '*') LIMIT 10;

-- DOM_VALUE: Get form field value
SELECT DOM_VALUE(DOM) AS input_value FROM DOM_LOAD_AND_SELECT('...', 'input,select,textarea');

-- DOM_DATA: Get combined data-* attributes
SELECT DOM_DATA(DOM) AS dataset FROM DOM_LOAD_AND_SELECT('...', '[data-price]');
```

---

## 2.7 Link & Image Properties

```sql
-- DOM_HREF: Get raw href attribute
SELECT DOM_HREF(DOM) AS raw_link FROM DOM_LOAD_AND_SELECT('...', 'a');

-- DOM_ABS_HREF: Get resolved absolute href URL
SELECT DOM_ABS_HREF(DOM) AS absolute_link FROM DOM_LOAD_AND_SELECT('...', 'a');

-- DOM_SRC: Get raw src attribute
SELECT DOM_SRC(DOM) AS raw_src FROM DOM_LOAD_AND_SELECT('...', 'img');

-- DOM_ABS_SRC: Get resolved absolute src URL
SELECT DOM_ABS_SRC(DOM) AS absolute_src FROM DOM_LOAD_AND_SELECT('...', 'img');
```

**Practical pattern — extract all links with text:**

```sql
SELECT
    DOM_TEXT(DOM) AS link_text,
    DOM_ABS_HREF(DOM) AS url
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a')
WHERE DOM_HAS_TEXT(DOM);
```

---

## 2.8 Title

```sql
-- DOM_TITLE: Get the element's title attribute (tooltip)
SELECT DOM_TITLE(DOM) AS tooltip FROM DOM_LOAD_AND_SELECT('...', 'abbr,img[title]');

-- DOM_DOC_TITLE: Get the document's <title> text
SELECT DOM_DOC_TITLE(DOM) AS page_title FROM DOM_LOAD_AND_SELECT('...', ':root');
```

---

## 2.9 Text Extraction

### DOM_HAS_TEXT

```
DOM_HAS_TEXT(dom)
```

```sql
-- Skip empty elements
SELECT DOM_TEXT(DOM) AS text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p')
WHERE DOM_HAS_TEXT(DOM);
```

### DOM_TEXT

```
DOM_TEXT(dom [, truncate])
```

Returns the element's full inner text. Optionally truncate to N characters.

```sql
-- Full text
SELECT DOM_TEXT(DOM) AS full_text FROM DOM_LOAD_AND_SELECT('...', 'article');

-- Truncated to 200 chars (for previews)
SELECT DOM_TEXT(DOM, 200) AS preview FROM DOM_LOAD_AND_SELECT('...', 'p');
```

### DOM_TEXT_LEN & DOM_TEXT_LENGTH

```
DOM_TEXT_LEN(dom)     -- text character count
DOM_TEXT_LENGTH(dom)  -- alias
```

```sql
-- Find the longest paragraphs
SELECT DOM_TEXT(DOM) AS text, DOM_TEXT_LEN(DOM) AS length
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p')
ORDER BY DOM_TEXT_LEN(DOM) DESC
LIMIT 5;
```

### DOM_OWN_TEXT

```
DOM_OWN_TEXT(dom)
```

Returns only the element's direct text, excluding text from child elements.

```sql
-- Get the heading text without nested <span> content
SELECT DOM_OWN_TEXT(DOM) AS heading_text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1,h2');
```

### DOM_OWN_TEXTS

```
DOM_OWN_TEXTS(dom)
```

Returns the own texts of the element and all its descendants as a `ValueArray`.

```sql
-- Get all text fragments from an article as an array
SELECT DOM_OWN_TEXTS(DOM) AS text_fragments
FROM DOM_LOAD_AND_SELECT('https://example.com', 'article');
```

### DOM_OWN_TEXT_LEN

```
DOM_OWN_TEXT_LEN(dom)
```

```sql
SELECT DOM_OWN_TEXT_LEN(DOM) AS own_text_length
FROM DOM_LOAD_AND_SELECT('...', 'p');
```

### DOM_WHOLE_TEXT & DOM_WHOLE_TEXT_LEN

```
DOM_WHOLE_TEXT(dom)     -- text including child text nodes
DOM_WHOLE_TEXT_LEN(dom)
```

```sql
-- Whole text is useful when you want text node content preserved
SELECT DOM_WHOLE_TEXT(DOM) AS whole_text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'pre,code');
```

---

## 2.10 HTML Serialization

```sql
-- DOM_HTML: Inner HTML (slim copy — whitespace normalized)
SELECT DOM_HTML(DOM) AS inner_html FROM DOM_LOAD_AND_SELECT('...', 'div.content');

-- DOM_OUTER_HTML: Outer HTML including the element itself
SELECT DOM_OUTER_HTML(DOM) AS full_html FROM DOM_LOAD_AND_SELECT('...', 'div.card');

-- DOM_SLIM_HTML: Slimmed-down HTML (formatting removed)
SELECT DOM_SLIM_HTML(DOM) AS clean_html FROM DOM_LOAD_AND_SELECT('...', 'article');

-- DOM_MINIMAL_HTML: Most compact HTML representation
SELECT DOM_MINIMAL_HTML(DOM) AS compact_html FROM DOM_LOAD_AND_SELECT('...', 'section');

-- DOM_DOM: Identity — returns the DOM unchanged
SELECT DOM_DOC_TITLE(DOM_DOM(DOM)) AS title
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

---

## 2.11 Regex Extraction on DOM Text

### DOM_RE1

```
DOM_RE1(dom, regex [, group])
```

Extracts a regex group from the element's text. Default is group 1.

```sql
-- Extract price numbers from text like "Price: $29.99"
SELECT DOM_RE1(DOM, '\$([\d.]+)') AS price
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', '.price');

-- Extract the 2nd regex group
SELECT DOM_RE1(DOM, '(\d+) reviews.*(\d+) stars', 2) AS star_count
FROM DOM_LOAD_AND_SELECT('...', '.rating');
```

### DOM_RE2

```
DOM_RE2(dom, regex [, keyGroup, valueGroup])
```

Extracts key-value pairs from text. Returns `ValueArray` with `[key, value]`.

```sql
-- Extract "Color: Red" style text as key-value pairs
SELECT DOM_RE2(DOM, '(\w+):\s*(.+)') AS kv_pair
FROM DOM_LOAD_AND_SELECT('https://example.com', '.specs li');

-- Use custom group indices (group 2 as key, group 3 as value)
SELECT DOM_RE2(DOM, '(SKU:)\s*([A-Z0-9]+)', 2, 2) AS sku
FROM DOM_LOAD_AND_SELECT('...', '.product-code');
```

---

## 2.12 Computed Features

These are shorthand abbreviations for common DOM features. All return `Double`.

```sql
-- DOM_CH: Character count (text length)
SELECT DOM_TEXT(DOM) AS text, DOM_CH(DOM) AS chars
FROM DOM_LOAD_AND_SELECT('...', 'p')
ORDER BY DOM_CH(DOM) DESC LIMIT 5;

-- DOM_TN: Text node count
-- DOM_IMG: Image count
-- DOM_A: Anchor (link) count
SELECT DOM_TAG_NAME(DOM) AS tag, DOM_IMG(DOM) AS images, DOM_A(DOM) AS links
FROM DOM_LOAD_AND_SELECT('...', 'div');

-- DOM_SIB: Sibling count
-- DOM_C: Child count
-- DOM_DEP: Depth in tree
-- DOM_SEQ: Sequence number
SELECT
    DOM_DEP(DOM) AS tree_depth,
    DOM_SIB(DOM) AS siblings,
    DOM_C(DOM) AS children
FROM DOM_LOAD_AND_SELECT('...', 'div');

-- DOM_TOP, DOM_LEFT: Bounding box position
-- DOM_WIDTH, DOM_HEIGHT: Bounding box dimensions (minimum 1.0)
SELECT
    DOM_TOP(DOM) AS y,
    DOM_LEFT(DOM) AS x,
    DOM_WIDTH(DOM) AS w,
    DOM_HEIGHT(DOM) AS h
FROM DOM_LOAD_AND_SELECT('...', 'img');

-- DOM_AREA: width × height
SELECT DOM_AREA(DOM) AS pixel_area
FROM DOM_LOAD_AND_SELECT('...', 'img')
ORDER BY DOM_AREA(DOM) DESC;

-- DOM_ASPECT_RATIO: width / height
SELECT DOM_ASPECT_RATIO(DOM) AS ratio
FROM DOM_LOAD_AND_SELECT('...', 'img')
WHERE DOM_ASPECT_RATIO(DOM) > 1.5;  -- landscape images
```

**Practical pattern — find the largest visible images:**

```sql
SELECT
    DOM_ABS_SRC(DOM) AS image_url,
    DOM_WIDTH(DOM) AS width,
    DOM_HEIGHT(DOM) AS height,
    DOM_AREA(DOM) AS area
FROM DOM_LOAD_AND_SELECT('https://example.com', 'img')
WHERE DOM_WIDTH(DOM) > 100 AND DOM_HEIGHT(DOM) > 100
ORDER BY DOM_AREA(DOM) DESC
LIMIT 10;
```
