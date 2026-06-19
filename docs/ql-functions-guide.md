# Pulsar QL Functions Guide — AI Skills Reference

> Detailed reference with practical SQL examples for the most commonly used H2 UDFs.
> Covers: `load_and_select`, `DomFunctions`, `DomSelectFunctions`, `StringFunctions`, `ArrayFunctions`.

---

## Table of Contents

- [1. DOM_LOAD_AND_SELECT — Page Loading with CSS Selection](#1-dom_load_and_select--page-loading-with-css-selection)
- [2. DomFunctions — Core DOM Operations](#2-domfunctions--core-dom-operations)
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
- [3. DomSelectFunctions — CSS Selector-Based Extraction](#3-domselectfunctions--css-selector-based-extraction)
  - [3.1 Element Selection](#31-element-selection)
  - [3.2 Text Extraction](#32-text-extraction)
  - [3.3 HTML Extraction](#33-html-extraction)
  - [3.4 Number Extraction](#34-number-extraction)
  - [3.5 Attribute Extraction](#35-attribute-extraction)
  - [3.6 Image & Link Extraction](#36-image--link-extraction)
  - [3.7 Node Labels](#37-node-labels)
  - [3.8 Regex Extraction with CSS Selectors](#38-regex-extraction-with-css-selectors)
- [4. StringFunctions — String Manipulation](#4-stringfunctions--string-manipulation)
  - [4.1 Case Manipulation](#41-case-manipulation)
  - [4.2 Empty / Blank Checks](#42-empty--blank-checks)
  - [4.3 Trimming & Stripping](#43-trimming--stripping)
  - [4.4 Substring Extraction](#44-substring-extraction)
  - [4.5 Search & Contains](#45-search--contains)
  - [4.6 Splitting & Joining](#46-splitting--joining)
  - [4.7 Replace & Remove](#47-replace--remove)
  - [4.8 Padding](#48-padding)
  - [4.9 Other String Utilities](#49-other-string-utilities)
  - [4.10 Character Classification](#410-character-classification)
  - [4.11 Number Extraction](#411-number-extraction)
- [5. ArrayFunctions — Array Operations](#5-arrayfunctions--array-operations)

---

## 1. DOM_LOAD_AND_SELECT — Page Loading with CSS Selection

**Source:** `DomFunctionTables.kt` | **Namespace:** `DOM`

### DOM_LOAD_AND_SELECT

```
DOM_LOAD_AND_SELECT(url, cssQuery [, offset, limit])
```

Loads a web page and immediately selects elements matching a CSS query. Returns a `ResultSet` of DOM objects — use this as a table source with `SELECT * FROM DOM_LOAD_AND_SELECT(...)`.

**Parameters:**
| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | `String` | required | The URL to load (can include load options via `-i`/`-expires` query args) |
| `cssQuery` | `String` | required | CSS selector to match elements on the page |
| `offset` | `Int` | `1` | 1-based offset into the matched element set |
| `limit` | `Int` | `MAX_VALUE` | Maximum number of elements to return |

**Returns:** `ResultSet` with DOM column — each row is a `ValueDom` that can be passed to other DOM functions.

**Examples:**

```sql
-- Load a page and select all <h1> elements
SELECT * FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1');

-- Combine with DOM functions to extract text from each result
SELECT DOM_TEXT(DOM) AS title
FROM DOM_LOAD_AND_SELECT('https://example.com', 'article h2');

-- Load with expiration control (fetch fresh if older than 1 hour)
SELECT DOM_FIRST_TEXT(DOM, 'title')
FROM DOM_LOAD_AND_SELECT('https://example.com?-expires=1h', 'h1');

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

---

## 2. DomFunctions — Core DOM Operations

**Source:** `DomFunctions.kt` | **Namespace:** `DOM` | **~65 functions**

### 2.1 Page Loading

#### DOM_LOAD

```
DOM_LOAD(configuredUrl)
```

Loads a page from the database cache, or fetches it from the web if absent or expired. Returns a single `ValueDom`.

```sql
-- Basic page load
SELECT DOM_TEXT(DOM_LOAD('https://example.com'));

-- Load and extract the page title
SELECT DOM_DOC_TITLE(DOM_LOAD('https://example.com'));
```

#### DOM_FETCH

```
DOM_FETCH(configuredUrl)
```

Forces an immediate web fetch, bypassing the cache entirely (sets expiry to zero).

```sql
-- Always get the latest version
SELECT DOM_TEXT(DOM_FETCH('https://example.com/live-prices'));
```

---

### 2.2 DOM State Checks

#### DOM_IS_NIL

```
DOM_IS_NIL(dom)
```

Returns `true` if the DOM is nil (empty, invalid, or failed to load).

```sql
-- Filter out failed page loads
SELECT url, DOM_IS_NIL(DOM) AS failed
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

#### DOM_IS_NOT_NIL

```
DOM_IS_NOT_NIL(dom)
```

Returns `true` if the DOM is valid and contains content.

```sql
-- Only process successfully loaded pages
SELECT * FROM pages WHERE DOM_IS_NOT_NIL(dom);
```

---

### 2.3 Element Properties

#### DOM_ATTR

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

#### DOM_LABELS

```
DOM_LABELS(dom)
```

Gets the Pulsar `A_LABELS` attribute — machine-learned node classification labels.

```sql
-- See what Pulsar thinks each element is
SELECT DOM_TAG_NAME(DOM) AS tag, DOM_LABELS(DOM) AS labels
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div,p,ul,li');
```

#### DOM_FEATURE

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

#### DOM_HAS_ATTR

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

#### DOM_STYLE

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

#### DOM_SEQUENCE & DOM_DEPTH

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

#### DOM_CSS_SELECTOR & DOM_CSS_PATH

```
DOM_CSS_SELECTOR(dom)  -- unique CSS selector for this element
DOM_CSS_PATH(dom)      -- alias for cssSelector
```

```sql
-- Get the unique CSS path for every heading
SELECT DOM_TEXT(DOM) AS heading, DOM_CSS_SELECTOR(DOM) AS css_path
FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1,h2,h3');
```

#### DOM_SIBLING_SIZE & DOM_SIBLING_INDEX

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

### 2.4 URL & Location

#### DOM_URI

```
DOM_URI(dom)
```

Returns the page's normalized URI — the permanent internal address used as the database key.

```sql
-- See which actual URL was loaded (after normalization)
SELECT DOM_URI(DOM) AS normalized_url
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

#### DOM_BASE_URI

```
DOM_BASE_URI(dom)
```

Returns the element's base URI (the last working address of the page).

```sql
SELECT DOM_BASE_URI(DOM) AS base_url
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root');
```

#### DOM_ABS_URL

```
DOM_ABS_URL(dom, attributeKey)
```

Resolves a relative URL attribute to an absolute URL.

```sql
-- Resolve relative image paths to absolute URLs
SELECT DOM_ABS_URL(DOM, 'src') AS absolute_image_url
FROM DOM_LOAD_AND_SELECT('https://example.com', 'img');
```

#### DOM_LOCATION

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

### 2.5 DOM Tree Navigation

#### DOM_CHILD_NODE_SIZE & DOM_CHILD_ELEMENT_SIZE

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

#### DOM_PARENT

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

#### DOM_ANCESTOR

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

#### DOM_PARENT_NAME

```
DOM_PARENT_NAME(dom)
```

Returns the unique name of the parent element. Returns `"nil"` if the DOM is nil.

```sql
SELECT DOM_TEXT(DOM) AS text, DOM_PARENT_NAME(DOM) AS container
FROM DOM_LOAD_AND_SELECT('https://example.com', 'span');
```

#### DOM_OWNER_DOCUMENT, DOM_OWNER_BODY, DOM_DOCUMENT_VARIABLES

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

### 2.6 Element Identity

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

### 2.7 Link & Image Properties

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

### 2.8 Title

```sql
-- DOM_TITLE: Get the element's title attribute (tooltip)
SELECT DOM_TITLE(DOM) AS tooltip FROM DOM_LOAD_AND_SELECT('...', 'abbr,img[title]');

-- DOM_DOC_TITLE: Get the document's <title> text
SELECT DOM_DOC_TITLE(DOM) AS page_title FROM DOM_LOAD_AND_SELECT('...', ':root');
```

---

### 2.9 Text Extraction

#### DOM_HAS_TEXT

```
DOM_HAS_TEXT(dom)
```

```sql
-- Skip empty elements
SELECT DOM_TEXT(DOM) AS text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'p')
WHERE DOM_HAS_TEXT(DOM);
```

#### DOM_TEXT

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

#### DOM_TEXT_LEN & DOM_TEXT_LENGTH

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

#### DOM_OWN_TEXT

```
DOM_OWN_TEXT(dom)
```

Returns only the element's direct text, excluding text from child elements.

```sql
-- Get the heading text without nested <span> content
SELECT DOM_OWN_TEXT(DOM) AS heading_text
FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1,h2');
```

#### DOM_OWN_TEXTS

```
DOM_OWN_TEXTS(dom)
```

Returns the own texts of the element and all its descendants as a `ValueArray`.

```sql
-- Get all text fragments from an article as an array
SELECT DOM_OWN_TEXTS(DOM) AS text_fragments
FROM DOM_LOAD_AND_SELECT('https://example.com', 'article');
```

#### DOM_OWN_TEXT_LEN

```
DOM_OWN_TEXT_LEN(dom)
```

```sql
SELECT DOM_OWN_TEXT_LEN(DOM) AS own_text_length
FROM DOM_LOAD_AND_SELECT('...', 'p');
```

#### DOM_WHOLE_TEXT & DOM_WHOLE_TEXT_LEN

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

### 2.10 HTML Serialization

```sql
-- DOM_HTML: Inner HTML (slim copy — whitespace normalized)
SELECT DOM_HTML(DOM) AS inner_html FROM DOM_LOAD_AND_SELECT('...', 'div.content');

-- DOM_OUTER_HTML: Outer HTML including the element itself
SELECT DOM_OUTER_HTML(DOM) AS full_html FROM DOM_LOAD_AND_SELECT('...', 'div.card');

-- DOM_SLIM_HTML: Slimmed-down HTML (formatting removed)
SELECT DOM_SLIM_HTML(DOM) AS clean_html FROM DOM_LOAD_AND_SELECT('...', 'article');

-- DOM_MINIMAL_HTML: Most compact HTML representation
SELECT DOM_MINIMAL_HTML(DOM) AS compact_html FROM DOM_LOAD_AND_SELECT('...', 'section');

-- DOM_DOM: Identity — returns the DOM unchanged (useful in CTEs)
WITH page AS (
    SELECT DOM_LOAD('https://example.com') AS dom
)
SELECT DOM_DOC_TITLE(DOM_DOM(dom)) FROM page;
```

---

### 2.11 Regex Extraction on DOM Text

#### DOM_RE1

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

#### DOM_RE2

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

### 2.12 Computed Features

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

---

## 3. DomSelectFunctions — CSS Selector-Based Extraction

**Source:** `DomSelectFunctions.kt` | **Namespace:** `DOM` | **~50 functions**

All functions follow a consistent `all*` / `first*` / `nth*` pattern. `all*` returns `ValueArray`, `first*` and `nth*` return scalar values.

### 3.1 Element Selection

```sql
-- DOM_SELECT_ALL: Returns all matched elements as a ValueArray of DOMs
SELECT DOM_SELECT_ALL(DOM, 'li') AS list_items FROM DOM_LOAD('...');

-- DOM_SELECT_FIRST: Returns the first match as a DOM
SELECT DOM_SELECT_FIRST(DOM, 'h1') AS heading FROM DOM_LOAD('...');

-- DOM_SELECT_NTH: Returns the nth match (1-based) as a DOM
SELECT DOM_SELECT_NTH(DOM, 'p', 3) AS third_paragraph FROM DOM_LOAD('...');
```

### 3.2 Text Extraction

```sql
-- DOM_ALL_TEXTS: All matched elements' text content as an array
SELECT DOM_ALL_TEXTS(DOM, 'li') AS items FROM DOM_LOAD('...');

-- DOM_FIRST_TEXT: Text of the first match
SELECT DOM_FIRST_TEXT(DOM, 'h1') AS heading FROM DOM_LOAD('...');

-- DOM_NTH_TEXT: Text of the nth match
SELECT DOM_NTH_TEXT(DOM, 'p', 2) AS second_paragraph FROM DOM_LOAD('...');

-- DOM_ALL_OWN_TEXTS: Own text of all matches
SELECT DOM_ALL_OWN_TEXTS(DOM, 'li') AS item_texts FROM DOM_LOAD('...');

-- DOM_FIRST_OWN_TEXT: Own text of first match
SELECT DOM_FIRST_OWN_TEXT(DOM, 'div.heading') AS heading FROM DOM_LOAD('...');

-- DOM_NTH_OWN_TEXT: Own text of nth match
SELECT DOM_NTH_OWN_TEXT(DOM, 'p', 1) AS first_p FROM DOM_LOAD('...');

-- DOM_WHOLE_TEXTS: Whole text of all matches
-- DOM_FIRST_WHOLE_TEXT: Whole text of first match
-- DOM_NTH_WHOLE_TEXT: Whole text of nth match
SELECT
    DOM_FIRST_WHOLE_TEXT(DOM, 'pre') AS code_text,
    DOM_NTH_WHOLE_TEXT(DOM, 'pre', 2) AS second_code
FROM DOM_LOAD('...');
```

**Practical example — extract all list items into a table:**

```sql
SELECT *
FROM EXPLODE(
    DOM_ALL_TEXTS(DOM_LOAD('https://example.com'), 'ul.features li')
);
```

### 3.3 HTML Extraction

```sql
-- Slim HTML variants
SELECT DOM_FIRST_SLIM_HTML(DOM, 'article') AS cleaned FROM DOM_LOAD('...');
SELECT DOM_NTH_SLIM_HTML(DOM, 'div.section', 3) AS third_section FROM DOM_LOAD('...');

-- Minimal HTML variants
SELECT DOM_ALL_MINIMAL_HTMLS(DOM, '.comment') AS comments FROM DOM_LOAD('...');
SELECT DOM_FIRST_MINIMAL_HTML(DOM, 'article') AS article FROM DOM_LOAD('...');
SELECT DOM_NTH_MINIMAL_HTML(DOM, 'div', 2) AS second_div FROM DOM_LOAD('...');
```

### 3.4 Number Extraction

Extracts the first integer or float found in the matched element's text. Falls back to `defaultValue` on parse failure.

```sql
-- DOM_ALL_INTEGERS: Extract integers from all matches
SELECT DOM_ALL_INTEGERS(DOM, '.price') AS prices FROM DOM_LOAD('...');

-- DOM_FIRST_INTEGER: Extract integer from first match
SELECT DOM_FIRST_INTEGER(DOM, '.review-count', 0) AS review_count FROM DOM_LOAD('...');

-- DOM_NTH_INTEGER: Extract integer from nth match
SELECT DOM_NTH_INTEGER(DOM, '.stat', 3, 0) AS third_stat FROM DOM_LOAD('...');

-- DOM_ALL_FLOATS: Extract floats from all matches
SELECT DOM_ALL_FLOATS(DOM, '.rating', 0.0) AS ratings FROM DOM_LOAD('...');

-- DOM_FIRST_FLOAT: Extract float from first match
SELECT DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS price FROM DOM_LOAD('...');

-- DOM_NTH_FLOAT: Extract float from nth match
SELECT DOM_NTH_FLOAT(DOM, '.metric', 2, 0.0) AS second_metric FROM DOM_LOAD('...');
```

**Pattern — extracting structured numeric data:**

```sql
SELECT
    DOM_FIRST_TEXT(DOM, '.name') AS name,
    DOM_FIRST_FLOAT(DOM, '.price', 0.0) AS price,
    DOM_FIRST_INTEGER(DOM, '.stock', 0) AS in_stock
FROM DOM_LOAD_AND_SELECT('https://shop.example.com/products', '.product-card');
```

### 3.5 Attribute Extraction

```sql
-- Single attribute, all/first/nth
SELECT DOM_ALL_ATTRS(DOM, 'a', 'href') AS links FROM DOM_LOAD('...');
SELECT DOM_FIRST_ATTR(DOM, 'meta[name="description"]', 'content') AS description FROM DOM_LOAD('...');
SELECT DOM_NTH_ATTR(DOM, 'img', 3, 'src') AS third_image FROM DOM_LOAD('...');

-- Multiple attributes at once (returns as array for all, list for first/nth)
SELECT DOM_FIRST_MULTI_ATTRS(DOM, 'a', ARRAY['href', 'title', 'class']) AS link_attrs FROM DOM_LOAD('...');
SELECT DOM_NTH_MULTI_ATTRS(DOM, 'img', 2, ARRAY['src', 'alt', 'width', 'height']) AS img_attrs FROM DOM_LOAD('...');
```

**Pattern — extract all links with multiple attributes:**

```sql
SELECT
    DOM_FIRST_ATTR(DOM, ':root', 'href') AS url,
    DOM_FIRST_ATTR(DOM, ':root', 'title') AS tooltip
FROM DOM_LOAD_AND_SELECT('https://example.com', 'a[href]');
```

### 3.6 Image & Link Extraction

Automatically appends `img` / `a` to the CSS query if not present.

```sql
-- DOM_ALL_IMGS: All image absolute src URLs
SELECT DOM_ALL_IMGS(DOM) AS images FROM DOM_LOAD('...');
SELECT DOM_ALL_IMGS(DOM, 'div.gallery') AS gallery_images FROM DOM_LOAD('...');

-- DOM_FIRST_IMG: First image absolute src
SELECT DOM_FIRST_IMG(DOM) AS hero_image FROM DOM_LOAD('...');
SELECT DOM_FIRST_IMG(DOM, 'article') AS article_image FROM DOM_LOAD('...');

-- DOM_NTH_IMG: Nth image absolute src
SELECT DOM_NTH_IMG(DOM, 'div.gallery', 3) AS third_gallery_image FROM DOM_LOAD('...');

-- DOM_ALL_HREFS: All link absolute href URLs
SELECT DOM_ALL_HREFS(DOM) AS links FROM DOM_LOAD('...');
SELECT DOM_ALL_HREFS(DOM, 'nav') AS nav_links FROM DOM_LOAD('...');

-- DOM_FIRST_HREF: First link absolute href
SELECT DOM_FIRST_HREF(DOM, 'article') AS first_article_link FROM DOM_LOAD('...');

-- DOM_NTH_HREF: Nth link absolute href
SELECT DOM_NTH_HREF(DOM, 'nav', 5) AS fifth_nav_link FROM DOM_LOAD('...');
```

### 3.7 Node Labels

```sql
-- DOM_ALL_NODES_LABELS: Pulsar classification labels for all matched elements
SELECT DOM_ALL_NODES_LABELS(DOM, 'div') AS labels FROM DOM_LOAD('...');

-- DOM_FIRST_NODE_LABELS: Label of first match
SELECT DOM_FIRST_NODE_LABELS(DOM, 'div') AS label FROM DOM_LOAD('...');

-- DOM_NTH_NODE_LABELS: Label of nth match
SELECT DOM_NTH_NODE_LABELS(DOM, 'div', 2) AS second_label FROM DOM_LOAD('...');
```

### 3.8 Regex Extraction with CSS Selectors

```sql
-- DOM_ALL_RE1: Extract first regex group from all matched elements
SELECT DOM_ALL_RE1(DOM, '\$([\d.]+)') AS prices FROM DOM_LOAD('...');
SELECT DOM_ALL_RE1(DOM, '.spec', '(\d+x\d+)') AS resolutions FROM DOM_LOAD('...');

-- DOM_FIRST_RE1: Extract first regex group from first match
SELECT DOM_FIRST_RE1(DOM, '(\d{4}-\d{2}-\d{2})') AS date FROM DOM_LOAD('...');
SELECT DOM_FIRST_RE1(DOM, '.meta', 'Published:\s*(.+)') AS pub_date FROM DOM_LOAD('...');
SELECT DOM_FIRST_RE1(DOM, '.phones', '(\d{3}-\d{3}-\d{4})', 1) AS phone FROM DOM_LOAD('...');

-- DOM_ALL_RE2: Extract key-value pairs from all matches (groups 1,2)
SELECT DOM_ALL_RE2(DOM, '(\w+):\s*(.+)') AS fields FROM DOM_LOAD('...');
SELECT DOM_ALL_RE2(DOM, '.specs li', '(\w+):\s*(.+)') AS specs FROM DOM_LOAD('...');

-- DOM_FIRST_RE2: Extract key-value pair from first match
SELECT DOM_FIRST_RE2(DOM, '.product', 'Price:\s*\$(\d+)') AS price FROM DOM_LOAD('...');
SELECT DOM_FIRST_RE2(DOM, '.specs', '(\w+)', 1, 1) AS first_word FROM DOM_LOAD('...');

-- DOM_ALL_RE2 with custom groups
SELECT DOM_ALL_RE2(DOM, 'li', '(\d+)\.\s*(.+)', 1, 2) AS numbered_items FROM DOM_LOAD('...');
```

**Pattern — extract key-value specs table from a product page:**

```sql
SELECT *
FROM EXPLODE(
    DOM_ALL_RE2(
        DOM_LOAD('https://shop.example.com/product/123'),
        '.specs-table tr',
        '(.+?):\s*(.+)'
    )
);
```

---

## 4. StringFunctions — String Manipulation

**Source:** `StringFunctions.kt` | **Namespace:** `STR` | **~90 functions**

All functions are null-safe (delegate to Apache Commons `StringUtils`). A `null` input returns `null` or a sensible default depending on the return type.

### 4.1 Case Manipulation

```sql
-- STR_CAPITALIZE: First character to uppercase
SELECT STR_CAPITALIZE('hello world');                    -- 'Hello world'

-- STR_UNCAPITALIZE: First character to lowercase
SELECT STR_UNCAPITALIZE('Hello World');                  -- 'hello World'

-- STR_SWAP_CASE: Swap uppercase ↔ lowercase
SELECT STR_SWAP_CASE('Hello World');                     -- 'hELLO wORLD'

-- STR_UPPER_CASE: All uppercase
SELECT STR_UPPER_CASE('hello');                          -- 'HELLO'

-- STR_LOWER_CASE: All lowercase
SELECT STR_LOWER_CASE('HELLO');                          -- 'hello'
```

**Real-world usage — normalize scraped text:**

```sql
SELECT
    STR_UPPER_CASE(DOM_FIRST_TEXT(DOM, 'h1')) AS heading,
    STR_LOWER_CASE(DOM_FIRST_TEXT(DOM, '.category')) AS category
FROM DOM_LOAD('https://example.com');
```

### 4.2 Empty / Blank Checks

```sql
-- STR_IS_EMPTY: true if null or ""
SELECT STR_IS_EMPTY(NULL), STR_IS_EMPTY(''), STR_IS_EMPTY('  '), STR_IS_EMPTY('a');
-- Result: true, true, false, false

-- STR_IS_NOT_EMPTY: inverse
SELECT STR_IS_NOT_EMPTY('hello');                        -- true

-- STR_IS_BLANK: true if null, "", or whitespace only
SELECT STR_IS_BLANK('  ');                               -- true
SELECT STR_IS_BLANK('a');                                -- false

-- STR_IS_NOT_BLANK: inverse
SELECT STR_IS_NOT_BLANK('hello');                        -- true

-- STR_IS_ANY_EMPTY: true if any element in the array is empty
SELECT STR_IS_ANY_EMPTY(ARRAY['a', '', 'b']);            -- true

-- STR_IS_NONE_EMPTY: true if no elements are empty
SELECT STR_IS_NONE_EMPTY(ARRAY['a', 'b', 'c']);          -- true

-- STR_IS_ANY_BLANK / STR_IS_NONE_BLANK: same but checks for blank
SELECT STR_IS_NONE_BLANK(ARRAY['a', 'b']);               -- true
```

**Pattern — filter out empty/blank values from scraped data:**

```sql
SELECT DOM_FIRST_TEXT(DOM, '.title') AS title
FROM DOM_LOAD_AND_SELECT('https://example.com', '.item')
WHERE STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, '.title'));
```

### 4.3 Trimming & Stripping

```sql
-- STR_TRIM: Remove leading/trailing control characters (<= 32)
SELECT STR_TRIM('  hello  ');                            -- 'hello'

-- STR_TRIM_TO_NULL: Trim, return null if result is empty
SELECT STR_TRIM_TO_NULL('   ');                          -- NULL

-- STR_TRIM_TO_EMPTY: Trim, return "" if result is empty
SELECT STR_TRIM_TO_EMPTY('   ');                         -- ''

-- STR_STRIP: Remove leading/trailing whitespace
SELECT STR_STRIP('  hello  ');                           -- 'hello'

-- STR_STRIP with custom chars
SELECT STR_STRIP('--hello--', '-');                      -- 'hello'

-- STR_STRIP_TO_NULL / STR_STRIP_TO_EMPTY
SELECT STR_STRIP_TO_NULL('   ');                         -- NULL
SELECT STR_STRIP_TO_EMPTY('   ');                        -- ''

-- STR_STRIP_START / STR_STRIP_END: Strip from one side
SELECT STR_STRIP_START('000123', '0');                   -- '123'
SELECT STR_STRIP_END('123.00', '0');                     -- '123.'

-- STR_STRIP_ALL: Strip all strings in an array
SELECT STR_STRIP_ALL(ARRAY[' a ', ' b ', ' c ']);        -- ['a', 'b', 'c']

-- STR_STRIP_ACCENTS: Remove diacritical marks
SELECT STR_STRIP_ACCENTS('café résumé');                 -- 'cafe resume'
```

**Pattern — clean scraped text before comparison:**

```sql
SELECT STR_STRIP_ACCENTS(STR_TRIM(DOM_FIRST_TEXT(DOM, 'h1'))) AS clean_heading
FROM DOM_LOAD('https://example.com');
```

### 4.4 Substring Extraction

```sql
-- STR_SUBSTRING(str, start): From position (0-based) to end
SELECT STR_SUBSTRING('hello world', 6);                  -- 'world'

-- STR_SUBSTRING(str, start, end): From start to end (exclusive)
SELECT STR_SUBSTRING('hello world', 0, 5);               -- 'hello'

-- STR_LEFT(str, len): Leftmost N characters
SELECT STR_LEFT('hello world', 5);                       -- 'hello'

-- STR_RIGHT(str, len): Rightmost N characters
SELECT STR_RIGHT('hello world', 5);                      -- 'world'

-- STR_MID(str, pos, len): Middle N characters starting at pos
SELECT STR_MID('hello world', 2, 4);                     -- 'llo '

-- STR_SUBSTRING_BEFORE: Everything before first occurrence of separator
SELECT STR_SUBSTRING_BEFORE('a/b/c', '/');               -- 'a'

-- STR_SUBSTRING_AFTER: Everything after first occurrence
SELECT STR_SUBSTRING_AFTER('a/b/c', '/');                -- 'b/c'

-- STR_SUBSTRING_BEFORE_LAST: Everything before last occurrence
SELECT STR_SUBSTRING_BEFORE_LAST('a/b/c', '/');          -- 'a/b'

-- STR_SUBSTRING_AFTER_LAST: Everything after last occurrence
SELECT STR_SUBSTRING_AFTER_LAST('a/b/c', '/');           -- 'c'

-- STR_SUBSTRING_BETWEEN(str, tag): Between identical tags
SELECT STR_SUBSTRING_BETWEEN('<b>hello</b>', '<b>');     -- 'hello'
SELECT STR_SUBSTRING_BETWEEN('<b>hello</b>', '<b>', '</b>'); -- 'hello'

-- STR_SUBSTRINGS_BETWEEN: All occurrences
SELECT STR_SUBSTRINGS_BETWEEN('a[x]b[y]c', '[', ']');    -- ['x', 'y']
```

**Pattern — extract values from delimited scraped text:**

```sql
-- Extract everything after "Price:" label
SELECT STR_TRIM(STR_SUBSTRING_AFTER(DOM_TEXT(DOM), 'Price:')) AS price
FROM DOM_LOAD_AND_SELECT('...', '.price-label');

-- Extract breadcrumb last segment
SELECT STR_SUBSTRING_AFTER_LAST(DOM_TEXT(DOM), ' > ') AS current_page
FROM DOM_LOAD_AND_SELECT('...', '.breadcrumb');
```

### 4.5 Search & Contains

```sql
-- STR_CONTAINS_WHITESPACE
SELECT STR_CONTAINS_WHITESPACE('hello world');           -- true
SELECT STR_CONTAINS_WHITESPACE('hello');                 -- false

-- STR_CONTAINS_ANY(str, searchChars): Contains any of the given chars
SELECT STR_CONTAINS_ANY('hello', 'xyz');                 -- false
SELECT STR_CONTAINS_ANY('hello', 'he');                  -- true

-- STR_CONTAINS_ONLY(str, validChars): Only contains the given chars
SELECT STR_CONTAINS_ONLY('12345', '0123456789');         -- true

-- STR_CONTAINS_NONE(str, invalidChars): Contains none of the given chars
SELECT STR_CONTAINS_NONE('hello', 'xyz');                -- true

-- STR_INDEX_OF_ANY: Index of first occurrence of any search char
SELECT STR_INDEX_OF_ANY('hello', 'ol');                  -- 2 (the 'l')

-- STR_INDEX_OF_ANY_BUT: Index of first char not in the set
SELECT STR_INDEX_OF_ANY_BUT('---abc---', '-');           -- 3 (the 'a')

-- STR_ORDINAL_INDEX_OF: Nth occurrence position
SELECT STR_ORDINAL_INDEX_OF('a.b.c.d', '.', 2);          -- 3

-- STR_LAST_ORDINAL_INDEX_OF: Nth from end
SELECT STR_LAST_ORDINAL_INDEX_OF('a.b.c.d', '.', 2);     -- 3

-- STR_INDEX_OF_DIFFERENCE: Index where two strings diverge
SELECT STR_INDEX_OF_DIFFERENCE('hello', 'helpo');        -- 3
SELECT STR_INDEX_OF_DIFFERENCE(ARRAY['abc', 'abd']);     -- 2

-- STR_COUNT_MATCHES: How many times substring appears
SELECT STR_COUNT_MATCHES('hello hello hello', 'hello');  -- 3

-- STR_GET_COMMON_PREFIX
SELECT STR_GET_COMMON_PREFIX(ARRAY['abcdef', 'abcxyz']); -- 'abc'
```

**Pattern — validate scraped data:**

```sql
SELECT DOM_TEXT(DOM) AS text
FROM DOM_LOAD_AND_SELECT('...', '.price')
WHERE STR_CONTAINS_ANY(DOM_TEXT(DOM), '$€£');

SELECT DOM_TEXT(DOM) AS numeric_value
FROM DOM_LOAD_AND_SELECT('...', '.stat')
WHERE STR_CONTAINS_ONLY(STR_TRIM(DOM_TEXT(DOM)), '0123456789.,');
```

### 4.6 Splitting & Joining

```sql
-- STR_SPLIT: Split by whitespace (default) or separator
SELECT STR_SPLIT('a b c');                               -- ['a', 'b', 'c']
SELECT STR_SPLIT('a,b,c', ',');                          -- ['a', 'b', 'c']
SELECT STR_SPLIT('a,b,c', ',', 2);                       -- ['a', 'b,c'] (max 2 parts)

-- STR_SPLIT_BY_WHOLE_SEPARATOR
SELECT STR_SPLIT_BY_WHOLE_SEPARATOR('a--b--c', '--');    -- ['a', 'b', 'c']

-- STR_SPLIT_PRESERVE_ALL_TOKENS: Keeps empty tokens
SELECT STR_SPLIT_PRESERVE_ALL_TOKENS('a,,c', ',');       -- ['a', '', 'c']
SELECT STR_SPLIT('a,,c', ',');                           -- ['a', 'c'] (empty dropped)

-- STR_SPLIT_BY_CHARACTER_TYPE: Split at case/number boundaries
SELECT STR_SPLIT_BY_CHARACTER_TYPE('helloWorld123');     -- ['hello', 'World', '123']

-- STR_SPLIT_BY_CHARACTER_TYPE_CAMEL_CASE
SELECT STR_SPLIT_BY_CHARACTER_TYPE_CAMEL_CASE('helloWorld'); -- ['hello', 'World']

-- STR_JOIN: Join array elements
SELECT STR_JOIN(ARRAY['a', 'b', 'c']);                   -- 'abc'
SELECT STR_JOIN(ARRAY['a', 'b', 'c'], ', ');             -- 'a, b, c'
```

**Pattern — parse comma-separated tags from scraped data:**

```sql
-- Split tags into individual rows
SELECT *
FROM EXPLODE(STR_SPLIT(DOM_FIRST_TEXT(DOM, '.tags'), ','));
```

### 4.7 Replace & Remove

```sql
-- STR_REPLACE_EACH: Replace multiple search/replacement pairs
SELECT STR_REPLACE_EACH(
    'hello & world',
    ARRAY['&', '<', '>'],
    ARRAY['&amp;', '&lt;', '&gt;']
);                                                       -- 'hello &amp; world'

-- STR_REPLACE_EACH_REPEATEDLY: Same but repeats until stable
SELECT STR_REPLACE_EACH_REPEATEDLY(
    'aabb',
    ARRAY['aa', 'bb'],
    ARRAY['b', 'a']
);                                                       -- continues until no more matches

-- STR_REPLACE_CHARS: Replace characters
SELECT STR_REPLACE_CHARS('hello', 'el', 'ip');           -- 'hippo'

-- STR_OVERLAY: Overlay a string at position
SELECT STR_OVERLAY('hello world', 'there', 6, 11);       -- 'hello there'

-- STR_DELETE_WHITESPACE: Remove all whitespace
SELECT STR_DELETE_WHITESPACE(' h e l l o ');             -- 'hello'

-- STR_CHOMP: Remove trailing \n, \r\n, or \r
SELECT STR_CHOMP('hello\n');                             -- 'hello'

-- STR_CHOP: Remove last character
SELECT STR_CHOP('hello');                                -- 'hell'

-- STR_NORMALIZE_SPACE: Collapse all whitespace to single spaces
SELECT STR_NORMALIZE_SPACE('hello   world\t\ttest');     -- 'hello world test'
```

**Pattern — clean up scraped HTML text:**

```sql
SELECT STR_NORMALIZE_SPACE(STR_TRIM(DOM_TEXT(DOM))) AS clean_text
FROM DOM_LOAD_AND_SELECT('...', 'p');
```

### 4.8 Padding

```sql
-- STR_LEFT_PAD: Pad left to specified length (default: space)
SELECT STR_LEFT_PAD('42', 5);                            -- '   42'
SELECT STR_LEFT_PAD('42', 5, '0');                       -- '00042'

-- STR_RIGHT_PAD: Pad right to specified length
SELECT STR_RIGHT_PAD('ID', 6, '-');                      -- 'ID----'

-- STR_CENTER: Center string to specified length
SELECT STR_CENTER('hi', 6);                              -- '  hi  '
SELECT STR_CENTER('hi', 6, '-');                         -- '--hi--'
```

### 4.9 Other String Utilities

```sql
-- STR_REPEAT: Repeat string N times
SELECT STR_REPEAT('ab', 3);                              -- 'ababab'
SELECT STR_REPEAT('a', ',', 3);                          -- 'a,a,a'

-- STR_REVERSE: Reverse the string
SELECT STR_REVERSE('hello');                             -- 'olleh'

-- STR_REVERSE_DELIMITED: Reverse order of delimited tokens
SELECT STR_REVERSE_DELIMITED('a.b.c', '.');              -- 'c.b.a'

-- STR_DIFFERENCE: Return the differing portion of two strings
SELECT STR_DIFFERENCE('hello world', 'hello there');     -- 'there' (from second string)

-- STR_LENGTH: Null-safe string length (null → 0)
SELECT STR_LENGTH('hello');                              -- 5
SELECT STR_LENGTH(NULL);                                 -- 0

-- STR_ABBREVIATE: Abbreviate with ellipsis
SELECT STR_ABBREVIATE('This is a very long text', 10);   -- 'This is...'
SELECT STR_ABBREVIATE('This is a very long text', 3, 10);-- '...is a...'

-- STR_ABBREVIATE_MIDDLE: Abbreviate keeping start and end
SELECT STR_ABBREVIATE_MIDDLE('hello world test', '...', 12); -- 'hello...test'

-- STR_DEFAULT_STRING: Return "" for null
SELECT STR_DEFAULT_STRING(NULL);                         -- ''

-- STR_DEFAULT_IF_BLANK: Return default if blank
SELECT STR_DEFAULT_IF_BLANK('  ', 'N/A');                -- 'N/A'

-- STR_DEFAULT_IF_EMPTY: Return default if empty
SELECT STR_DEFAULT_IF_EMPTY('', 'unknown');              -- 'unknown'

-- STR_TO_ENCODED_STRING: Bytes to string with charset
SELECT STR_TO_ENCODED_STRING(STRINGTOUTF8('hello'), 'UTF-8'); -- 'hello'
```

**Pattern — safely display scraped text with fallbacks:**

```sql
SELECT
    STR_DEFAULT_IF_BLANK(
        STR_ABBREVIATE(
            STR_NORMALIZE_SPACE(DOM_TEXT(DOM)),
            100
        ),
        '[No description available]'
    ) AS description
FROM DOM_LOAD_AND_SELECT('...', '.description');
```

### 4.10 Character Classification

```sql
-- STR_IS_ALPHA: Letters only
SELECT STR_IS_ALPHA('Hello');                            -- true
SELECT STR_IS_ALPHA('Hello123');                         -- false

-- STR_IS_NUMERIC: Digits only
SELECT STR_IS_NUMERIC('12345');                          -- true
SELECT STR_IS_NUMERIC('12.34');                          -- false

-- STR_IS_WHITESPACE: Whitespace characters only
SELECT STR_IS_WHITESPACE('   ');                         -- true

-- STR_IS_ALPHA_SPACE: Letters and spaces only
SELECT STR_IS_ALPHA_SPACE('Hello World');                -- true

-- STR_IS_ALPHANUMERIC: Letters and digits only
SELECT STR_IS_ALPHANUMERIC('Hello123');                  -- true

-- STR_IS_ALPHANUMERIC_SPACE: Letters, digits, and spaces
SELECT STR_IS_ALPHANUMERIC_SPACE('Hello 123');           -- true

-- STR_IS_ASCII_PRINTABLE
SELECT STR_IS_ASCII_PRINTABLE('Hello!');                  -- true

-- STR_IS_NUMERIC_SPACE: Digits and spaces
SELECT STR_IS_NUMERIC_SPACE('123 456');                  -- true

-- STR_IS_ALL_LOWER_CASE / STR_IS_ALL_UPPER_CASE
SELECT STR_IS_ALL_UPPER_CASE('HELLO');                   -- true
SELECT STR_IS_ALL_LOWER_CASE('hello');                   -- true
```

**Pattern — filter scraped values to valid data:**

```sql
SELECT DOM_TEXT(DOM) AS numeric_data
FROM DOM_LOAD_AND_SELECT('...', '.stat')
WHERE STR_IS_NUMERIC(STR_TRIM(DOM_TEXT(DOM)));
```

### 4.11 Number Extraction

```sql
-- STR_FIRST_INTEGER(str, defaultValue): Extract first integer
SELECT STR_FIRST_INTEGER('Price: $42.99', 0);            -- 42
SELECT STR_FIRST_INTEGER('No numbers here', -1);         -- -1

-- STR_FIRST_FLOAT(str, defaultValue): Extract first float
SELECT STR_FIRST_FLOAT('Weight: 3.5kg', 0.0);            -- 3.5

-- STR_GET_FIRST_FLOAT_NUMBER: Alias with same behavior
SELECT STR_GET_FIRST_FLOAT_NUMBER('$19.99 each', 0.0);   -- 19.99
```

**Pattern — parse prices from scraped text:**

```sql
SELECT
    DOM_FIRST_TEXT(DOM, '.name') AS product,
    STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '.price'), 0.0) AS price
FROM DOM_LOAD_AND_SELECT('https://shop.example.com', '.product');
```

---

## 5. ArrayFunctions — Array Operations

**Source:** `ArrayFunctions.kt` | **Namespace:** `ARRAY` | **3 functions**

### ARRAY_JOIN_TO_STRING

```
ARRAY_JOIN_TO_STRING(values, separator)
```

Joins all elements of a `ValueArray` into a single string with the given separator.

```sql
-- Basic join
SELECT ARRAY_JOIN_TO_STRING(MAKE_ARRAY('a', 'b', 'c'), ', ');
-- Result: 'a, b, c'

-- Join scraped items into a comma-separated list
SELECT ARRAY_JOIN_TO_STRING(
    DOM_ALL_TEXTS(DOM, 'ul.tags li'),
    ' | '
) AS tags
FROM DOM_LOAD('https://example.com');

-- Join with newlines for readable output
SELECT ARRAY_JOIN_TO_STRING(
    DOM_ALL_TEXTS(DOM, 'ol.steps li'),
    '\n'
) AS steps
FROM DOM_LOAD('https://example.com/guide');
```

### ARRAY_FIRST_NOT_BLANK

```
ARRAY_FIRST_NOT_BLANK(values)
```

Returns the first value in the array whose string representation is not blank (not null, not empty, not whitespace-only). Returns `null` if no non-blank value is found.

```sql
-- Find the first meaningful text among candidates
SELECT ARRAY_FIRST_NOT_BLANK(
    MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, '.subtitle'),
        DOM_FIRST_TEXT(DOM, '.alt-title'),
        DOM_FIRST_TEXT(DOM, 'h1')
    )
) AS best_title
FROM DOM_LOAD('https://example.com');

-- Fallback chain for missing data
SELECT ARRAY_FIRST_NOT_BLANK(
    MAKE_ARRAY(
        DOM_FIRST_ATTR(DOM, 'img', 'alt'),
        DOM_FIRST_ATTR(DOM, 'img', 'title'),
        'No description'
    )
) AS image_description
FROM DOM_LOAD('https://example.com');
```

### ARRAY_FIRST_NOT_EMPTY

```
ARRAY_FIRST_NOT_EMPTY(values)
```

Like `firstNotBlank` but only checks for non-empty (whitespace-only strings are still returned). Returns `null` if all values are empty.

```sql
-- Stricter fallback — accepts whitespace as valid
SELECT ARRAY_FIRST_NOT_EMPTY(
    MAKE_ARRAY(
        DOM_FIRST_ATTR(DOM, 'meta[name="author"]', 'content'),
        DOM_FIRST_ATTR(DOM, 'meta[name="publisher"]', 'content')
    )
) AS author
FROM DOM_LOAD('https://example.com/article');

-- First non-empty value wins
SELECT ARRAY_FIRST_NOT_EMPTY(MAKE_ARRAY('', '', 'found me', ''));
-- Result: 'found me'
```

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
    'https://example.com/products?-expires=1h',
    '.product-card',
    1, 20
)
WHERE DOM_IS_NOT_NIL(DOM)
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, '.title'));
```

### Extract metadata from a single page

```sql
WITH page AS (
    SELECT DOM_LOAD('https://example.com/article/123') AS dom
)
SELECT
    DOM_DOC_TITLE(dom) AS page_title,
    DOM_FIRST_TEXT(dom, 'meta[name="description"]') AS meta_description,
    STR_NORMALIZE_SPACE(DOM_FIRST_TEXT(dom, 'article')) AS article_text,
    DOM_TEXT_LEN(dom) AS article_length,
    DOM_FIRST_IMG(dom, 'article img') AS hero_image,
    STR_FIRST_FLOAT(DOM_FIRST_TEXT(dom, '.reading-time'), 0.0) AS reading_minutes
FROM page;

### Clean and validate scraped text

```sql
SELECT
    raw_text,
    STR_NORMALIZE_SPACE(STR_TRIM(raw_text)) AS cleaned,
    STR_DEFAULT_IF_BLANK(
        STR_ABBREVIATE(STR_NORMALIZE_SPACE(STR_TRIM(raw_text)), 200),
        '[empty]'
    ) AS display_text
FROM (
    SELECT DOM_TEXT(DOM) AS raw_text
    FROM DOM_LOAD_AND_SELECT('https://example.com', 'p')
);
```

### Parse structured data with regex

```sql
-- Extract "Label: Value" pairs from a specs table
SELECT *
FROM EXPLODE(
    DOM_ALL_RE2(
        DOM_LOAD('https://example.com/product/42'),
        '.specs-table tr',
        '(.+?):\s*(.+)'
    )
);

-- Extract all prices from a page
SELECT *
FROM EXPLODE(
    DOM_ALL_RE1(
        DOM_LOAD('https://shop.example.com/sale'),
        '.price',
        '\$([\d,]+\.?\d*)'
    )
);
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
FROM DOM_LOAD('https://shop.example.com/product/42');
```

---

## Function Index by SQL Alias

### DOM Namespace

| SQL Alias | Returns | Category |
|-----------|---------|----------|
| `DOM_LOAD_AND_SELECT` | `ResultSet` | Page loading + CSS selection |
| `DOM_LOAD` | `ValueDom` | Page loading |
| `DOM_FETCH` | `ValueDom` | Page loading |
| `DOM_IS_NIL` | `Boolean` | State check |
| `DOM_IS_NOT_NIL` | `Boolean` | State check |
| `DOM_ATTR` | `String` | Element property |
| `DOM_LABELS` | `String` | Element property |
| `DOM_FEATURE` | `Double` | Element property |
| `DOM_HAS_ATTR` | `Boolean` | Element property |
| `DOM_STYLE` | `String` | Element property |
| `DOM_SEQUENCE` | `Int` | Element property |
| `DOM_DEPTH` | `Int` | Element property |
| `DOM_CSS_SELECTOR` | `String` | Element property |
| `DOM_CSS_PATH` | `String` | Element property |
| `DOM_SIBLING_SIZE` | `Int` | Tree navigation |
| `DOM_SIBLING_INDEX` | `Int` | Tree navigation |
| `DOM_ELEMENT_SIBLING_SIZE` | `Int` | Tree navigation |
| `DOM_ELEMENT_SIBLING_INDEX` | `Int` | Tree navigation |
| `DOM_URI` | `String` | URL/Location |
| `DOM_BASE_URI` | `String` | URL/Location |
| `DOM_ABS_URL` | `String` | URL/Location |
| `DOM_LOCATION` | `String` | URL/Location |
| `DOM_CHILD_NODE_SIZE` | `Int` | Tree navigation |
| `DOM_CHILD_ELEMENT_SIZE` | `Int` | Tree navigation |
| `DOM_TAG_NAME` | `String` | Element identity |
| `DOM_HREF` | `String` | Link/Image |
| `DOM_ABS_HREF` | `String` | Link/Image |
| `DOM_SRC` | `String` | Link/Image |
| `DOM_ABS_SRC` | `String` | Link/Image |
| `DOM_TITLE` | `String` | Title |
| `DOM_DOC_TITLE` | `String` | Title |
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
| `DOM_SELECT_ALL` | `ValueArray` | CSS select |
| `DOM_SELECT_FIRST` | `ValueDom` | CSS select |
| `DOM_SELECT_NTH` | `ValueDom` | CSS select |
| `DOM_ALL_TEXTS` | `ValueArray` | CSS select |
| `DOM_FIRST_TEXT` | `String` | CSS select |
| `DOM_NTH_TEXT` | `String` | CSS select |
| `DOM_ALL_OWN_TEXTS` | `ValueArray` | CSS select |
| `DOM_FIRST_OWN_TEXT` | `String` | CSS select |
| `DOM_NTH_OWN_TEXT` | `String` | CSS select |
| `DOM_WHOLE_TEXTS` | `ValueArray` | CSS select |
| `DOM_FIRST_WHOLE_TEXT` | `String` | CSS select |
| `DOM_NTH_WHOLE_TEXT` | `String` | CSS select |
| `DOM_ALL_SLIM_HTMLS` | `ValueArray` | CSS select |
| `DOM_FIRST_SLIM_HTML` | `String` | CSS select |
| `DOM_NTH_SLIM_HTML` | `String` | CSS select |
| `DOM_ALL_MINIMAL_HTMLS` | `ValueArray` | CSS select |
| `DOM_FIRST_MINIMAL_HTML` | `String` | CSS select |
| `DOM_NTH_MINIMAL_HTML` | `String` | CSS select |
| `DOM_ALL_INTEGERS` | `ValueArray` | CSS select |
| `DOM_FIRST_INTEGER` | `Int` | CSS select |
| `DOM_NTH_INTEGER` | `Int` | CSS select |
| `DOM_ALL_FLOATS` | `ValueArray` | CSS select |
| `DOM_FIRST_FLOAT` | `ValueFloat` | CSS select |
| `DOM_NTH_FLOAT` | `ValueFloat` | CSS select |
| `DOM_ALL_ATTRS` | `ValueArray` | CSS select |
| `DOM_FIRST_ATTR` | `String` | CSS select |
| `DOM_NTH_ATTR` | `String` | CSS select |
| `DOM_ALL_MULTI_ATTRS` | `ValueArray` | CSS select |
| `DOM_FIRST_MULTI_ATTRS` | `List` | CSS select |
| `DOM_NTH_MULTI_ATTRS` | `List` | CSS select |
| `DOM_ALL_IMGS` | `ValueArray` | CSS select |
| `DOM_FIRST_IMG` | `String` | CSS select |
| `DOM_NTH_IMG` | `String` | CSS select |
| `DOM_ALL_HREFS` | `ValueArray` | CSS select |
| `DOM_FIRST_HREF` | `String` | CSS select |
| `DOM_NTH_HREF` | `String` | CSS select |
| `DOM_ALL_NODES_LABELS` | `ValueArray` | CSS select |
| `DOM_FIRST_NODE_LABELS` | `String` | CSS select |
| `DOM_NTH_NODE_LABELS` | `String` | CSS select |
| `DOM_ALL_RE1` | `ValueArray` | CSS select + regex |
| `DOM_FIRST_RE1` | `String` | CSS select + regex |
| `DOM_ALL_RE2` | `ValueArray` | CSS select + regex |
| `DOM_FIRST_RE2` | `ValueArray` | CSS select + regex |

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
