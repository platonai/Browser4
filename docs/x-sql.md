# X-SQL

English | [简体中文](/docs/zh/x-sql.md)

## Introduction

X-SQL is a SQL dialect for querying the web. It lets you load web pages, parse their DOM, extract structured data,
and convert results into tables and charts — all from a single SQL statement.

X-SQL is built on the [H2 database engine](https://www.h2database.com/), which means it inherits H2's SQL dialect and can be used with any
H2-compatible client or driver.

**Capabilities at a glance:**

| Capability | Description |
|---|---|
| Web page loading | Fetch pages from the internet or read from the local cache |
| DOM selection | Query elements with CSS selectors, just like `document.querySelectorAll` |
| Content extraction | Extract text, HTML, attributes, images, links, and numeric data |
| String processing | 80+ null-safe string functions backed by Apache Commons Lang |
| Regex extraction | Extract capture groups from element text |
| LLM integration | Chat with an LLM or extract structured fields from page content |
| Visual boxing | Select elements by their visual bounding box |

Here is a typical X-SQL query:

```sql
select
  dom_base_uri(dom) as url,
  dom_first_text(dom, '#productTitle') as title,
  str_substring_after(dom_first_href(dom, '#wayfinding-breadcrumbs_container ul li:last-child a'), '&node=') as category,
  dom_first_slim_html(dom, '#bylineInfo') as brand,
  dom_all_slim_htmls(dom, '#imageBlock img') as gallery,
  dom_first_slim_html(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width > 400)') as img,
  dom_first_text(dom, '#price tr td:contains(List Price) ~ td') as listprice,
  dom_first_text(dom, '#price tr td:matches(^Price) ~ td') as price,
  str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
from load_and_select('https://www.amazon.com/dp/B08PP5MSVB -i 1d -njr 3', 'body');
```

---

## How X-SQL Works

Browser4 extends H2 by registering **User-Defined Functions (UDFs)** written in Kotlin. Each X-SQL function wraps a
piece of the web-crawling and DOM-parsing pipeline: loading a page, selecting elements, reading attributes, extracting
text, and so on.

### Namespaces

Every X-SQL function belongs to a namespace that identifies its domain:

| Namespace | Purpose | Example |
|---|---|---|
| `DOM` | DOM traversal, attributes, content, and table functions | `dom_base_uri(dom)` |
| `STR` | String manipulation (Apache Commons Lang) | `str_substring_after(text, sep)` |
| `ARRAY` | Array utilities | `array_join_to_string(arr, ',')` |
| `IN_BOX` | Visual bounding-box based selection | `in_box_first_text(dom, box)` |
| `LLM` | Large language model chat and extraction | `llm_chat(prompt)` |
| `TIME` | Date-time parsing and formatting | `time_first_date_time(text)` |
| `META` | Page metadata retrieval | `meta_load(url)` |
| `ADMIN` | Session management and debugging | `admin_echo(msg)` |

Functions declared **without a namespace** (e.g., `loadOptions`, `explode`, `makeArray`) are common utilities available globally.

### Shortcuts

When a function is declared with `hasShortcut = true`, the **namespace prefix is optional**. For example, `load_and_select`
belongs to the `DOM` namespace, but since it has a shortcut, you can write:

```sql
-- All equivalent:
load_and_select(url, 'body');
DOM_LOAD_AND_SELECT(url, 'body');
dom_load_and_select(url, 'body');
```

### Case and Underscore Insensitivity

X-SQL function names are **case-insensitive**, and all **underscores (`_`) are ignored**. The following are all identical:

```sql
DOM_LOAD_AND_SELECT(url, 'body');
dom_loadAndSelect(url, 'body');
Dom_Load_And_Select(url, 'body');
dom_load_and_select(url, 'body');
dOm_____lo_AdaNd_S___elEct_____(url, 'body');
```

This also means `loadAndSelect`, `load_and_select`, and `LOAD_AND_SELECT` resolve to the same function. The canonical
form used in this document is lowercase with underscores (`load_and_select`), which is the recommended style.

### Load Options

Many functions that accept a `url` parameter support **inline load options** appended to the URL. These options control
caching, fetch behavior, and more:

```
https://www.example.com/page   -i 1d -njr 3
```

Use the `loadOptions()` table function to see all available options:

```sql
select * from loadOptions();
```

---

## Table Functions

Table functions return a `ResultSet` and are used in the `FROM` clause. They are the entry point for most X-SQL
queries — they load pages and produce rows that downstream functions can consume.

### load_and_select

```
load_and_select(url, cssSelector [, offset [, limit]])
```

Loads a web page and selects elements matching `cssSelector`. Returns a `ResultSet` with two columns:

| Column | Type | Description |
|---|---|---|
| `DOM` | `ValueDom` | The selected element |
| `DOC` | `ValueDom` | The full document (shared across all rows) |

`offset` is 1-based; `limit` controls how many elements to return.

```sql
select dom_base_uri(dom), dom_first_text(dom, 'h1')
from load_and_select('https://example.com/products', '.product-item', 1, 20);
```

### load_all

```
load_all(urls)
```

Loads multiple pages in parallel. `urls` can be a single URL string or an array of URLs.

```sql
select dom_base_uri(dom), dom_first_text(dom, 'title')
from load_all(array('https://example.com/1', 'https://example.com/2'));
```

### select

```
select(dom, cssQuery [, offset [, limit]])
```

Selects child elements from an existing `ValueDom`, producing a new `ResultSet`. Useful for two-level iteration.

```sql
select dom_first_text(dom, 'span.name') as name
from select(someDom, 'ul li.item', 1, 10);
```

### load_and_get_links

```
load_and_get_links(portalUrl [, restrictCss [, offset [, limit]]])
```

Loads a page and extracts all outgoing links (absolute `href` values from `<a>` elements) within the matched elements.

```sql
select * from load_and_get_links('https://example.com/blog', 'main', 1, 50);
```

### links

```
links(dom [, cssQuery [, offset [, limit]]])
```

Extracts links from an existing DOM, same semantics as `load_and_get_links` but operating on a `ValueDom`.

### load_and_get_anchors

```
load_and_get_anchors(portalUrl [, restrictCss [, offset [, limit]]])
```

Loads a page and returns geo-anchors (URL, text, path, left, top, width, height) for all matching anchor elements.

### load_out_pages

```
load_out_pages(portalUrl [, restrictCss [, offset [, limit [, normalize]]]])
```

Loads a portal page, extracts all outgoing links, and then loads each linked page. Returns a `ResultSet` of `DOM` columns — one row per out-linked page.

```sql
select dom_base_uri(dom), dom_first_text(dom, 'h1')
from load_out_pages('https://example.com/blog/index', 'main', 1, 10);
```

### load_out_pages_ignore_url_query

```
load_out_pages_ignore_url_query(portalUrl [, restrictCss [, offset [, limit [, normalize]]]])
```

Same as `load_out_pages` but strips URL query strings from the target URLs before loading. Useful for de-duplicating pages that differ only by tracking parameters.

### load_out_pages_and_select

```
load_out_pages_and_select(portal, restrictCss, offset, limit, targetCss, normalize, ignoreQuery)
```

Loads out-linked pages and selects elements from each, returning a single flat `ResultSet`.

### load_out_pages_and_select_first

```
load_out_pages_and_select_first(portalUrl [, restrictCss [, offset [, limit [, targetCss [, normalize [, ignoreQuery]]]]]])
```

Like `load_out_pages_and_select`, but selects only the first matching element from each out-linked page.

### load_and_get_features

```
load_and_get_features(portalUrl [, cssQuery [, offset [, limit]]])
```

Loads a page and returns a `ResultSet` with the `DOM` column plus computed feature columns (CH, TN, IMG, A, SIB, C, DEP, SEQ, TOP, LEFT, WIDTH, HEIGHT, etc.) for each matched element.

```sql
select * from load_and_get_features('https://example.com', 'div.item', 1, 10);
```

### features

```
features(dom [, cssSelector [, offset [, limit]]])
```

Same as `load_and_get_features`, but operates on an existing `ValueDom`.

### load_and_get_elements_with_most_sibling

```
load_and_get_elements_with_most_sibling(portalUrl [, restrictCss [, offset [, limit]]])
```

Loads a page and returns elements sorted by sibling count (descending). Useful for finding list/item containers.

### get_elements_with_most_sibling

```
get_elements_with_most_sibling(dom [, restrictCss [, offset [, limit]]])
```

Same operation on an existing `ValueDom`.

---

## DOM Functions

DOM functions operate on a `ValueDom` — a wrapper around a [Jsoup](https://jsoup.org/) `Element`. They extract properties, attributes, content, and positional data.

All DOM functions belong to the **`DOM`** namespace.

### Page Loading

| Function | Returns | Description |
|---|---|---|
| `dom_load(configuredUrl)` | `ValueDom` | Loads a page from DB or web, returns the document |
| `dom_fetch(configuredUrl)` | `ValueDom` | Fetches a page immediately, bypassing cache |

```sql
select dom_first_text(dom_load('https://example.com'), 'title');
```

### Identity & URI

| Function | Returns | Description |
|---|---|---|
| `dom_base_uri(dom)` | `String` | The base URI of the document |
| `dom_uri(dom)` | `String` | The normalized URI (the DB key) |
| `dom_location(dom)` | `String` | The last known working URL (may differ from `uri`) |
| `dom_abs_url(dom, attributeKey)` | `String` | Resolves a relative attribute value to an absolute URL |

### Attributes & Metadata

| Function | Returns | Description |
|---|---|---|
| `dom_attr(dom, attrName)` | `String` | The value of the named attribute |
| `dom_labels(dom)` | `String` | The element's internal label |
| `dom_feature(dom, featureName)` | `String` | A named feature value |
| `dom_has_attr(dom, attrName)` | `Boolean` | Whether the element has the given attribute |
| `dom_style(dom, styleName)` | `String` | A computed CSS style value |
| `dom_has_class(dom, className)` | `Boolean` | Whether the element has the given CSS class |

```sql
select dom_attr(dom, 'href'), dom_has_class(dom, 'active')
from load_and_select('https://example.com', 'a');
```

### Element Identity

| Function | Returns | Description |
|---|---|---|
| `dom_tag_name(dom)` | `String` | The HTML tag name |
| `dom_id(dom)` | `String` | The `id` attribute |
| `dom_class_name(dom)` | `String` | The `class` attribute |
| `dom_class_names(dom)` | `Set` | The set of CSS class names |
| `dom_unique_name(dom)` | `String` | A unique identifier for the element |
| `dom_css_selector(dom)` | `String` | A CSS selector that uniquely identifies this element |
| `dom_css_path(dom)` | `String` | Alias for `css_selector` |

### Content Extraction

| Function | Returns | Description |
|---|---|---|
| `dom_text(dom [, truncate])` | `String` | The full visible text of the element and its descendants |
| `dom_text_len(dom)` | `Int` | Length of the text |
| `dom_text_length(dom)` | `Int` | Alias for `text_len` |
| `dom_own_text(dom)` | `String` | The visible text of this element only (excludes children) |
| `dom_own_texts(dom)` | `Array` | Own texts as an array |
| `dom_own_text_len(dom)` | `Int` | Length of own text |
| `dom_whole_text(dom)` | `String` | The whole text including non-visible nodes |
| `dom_whole_text_len(dom)` | `Int` | Length of whole text |
| `dom_has_text(dom)` | `Boolean` | Whether the element has any text |

### HTML Output

| Function | Returns | Description |
|---|---|---|
| `dom_html(dom)` | `String` | Inner HTML (slim copy) |
| `dom_outer_html(dom)` | `String` | Outer HTML (slim copy) |
| `dom_slim_html(dom)` | `String` | A slimmed-down HTML representation |
| `dom_minimal_html(dom)` | `String` | A minimal HTML representation |

### Links & Media

| Function | Returns | Description |
|---|---|---|
| `dom_href(dom)` | `String` | The `href` attribute |
| `dom_abs_href(dom)` | `String` | The absolute URL of the `href` attribute |
| `dom_src(dom)` | `String` | The `src` attribute |
| `dom_abs_src(dom)` | `String` | The absolute URL of the `src` attribute |
| `dom_title(dom)` | `String` | The `title` attribute |
| `dom_doc_title(dom)` | `String` | The document's `<title>` |
| `dom_links(dom)` | `Array` | All `<a>` elements as an array of `ValueDom` |
| `dom_data(dom)` | `String` | The combined `data-*` attributes |
| `dom_value(dom)` | `String` | The form value |

### Tree Traversal

| Function | Returns | Description |
|---|---|---|
| `dom_dom(dom)` | `ValueDom` | Identity (returns the DOM itself) |
| `dom_parent(dom)` | `ValueDom` | The parent element |
| `dom_ancestor(dom, n)` | `ValueDom` | The nth ancestor |
| `dom_parent_name(dom)` | `String` | The unique name of the parent |
| `dom_owner_document(dom)` | `ValueDom` | The owning document |
| `dom_owner_body(dom)` | `ValueDom` | The owning `<body>` |
| `dom_document_variables(dom)` | `ValueDom` | Pulsar meta-information element |

### Position & Size

| Function | Returns | Description |
|---|---|---|
| `dom_top(dom)` | `Double` | Y-coordinate of the top edge |
| `dom_left(dom)` | `Double` | X-coordinate of the left edge |
| `dom_width(dom)` | `Double` | Element width in pixels (min 1) |
| `dom_height(dom)` | `Double` | Element height in pixels (min 1) |
| `dom_area(dom)` | `Double` | `width × height` |
| `dom_aspect_ratio(dom)` | `Double` | `width / height` |

### Index & Depth

| Function | Returns | Description |
|---|---|---|
| `dom_sequence(dom)` | `Int` | Element's sequence number |
| `dom_depth(dom)` | `Int` | Depth in the DOM tree |
| `dom_sibling_size(dom)` | `Int` | Number of sibling nodes |
| `dom_sibling_index(dom)` | `Int` | Index among sibling nodes |
| `dom_element_sibling_size(dom)` | `Int` | Number of element siblings |
| `dom_element_sibling_index(dom)` | `Int` | Index among element siblings |
| `dom_child_node_size(dom)` | `Int` | Number of child nodes |
| `dom_child_element_size(dom)` | `Int` | Number of child elements |

### Feature Shortcuts

These are convenient wrappers around `dom_feature` for commonly used features:

| Function | Feature | Description |
|---|---|---|
| `dom_ch(dom)` | CH | Character count feature |
| `dom_tn(dom)` | TN | Tag name feature |
| `dom_img(dom)` | IMG | Image count feature |
| `dom_a(dom)` | A | Anchor count feature |
| `dom_sib(dom)` | SIB | Sibling count feature |
| `dom_c(dom)` | C | Child count feature |
| `dom_dep(dom)` | DEP | Depth feature |
| `dom_seq(dom)` | SEQ | Sequence feature |

### Regex on Element Text

| Function | Returns | Description |
|---|---|---|
| `dom_re1(dom, regex)` | `String` | First regex capture group from the element's text |
| `dom_re1(dom, regex, group)` | `String` | Nth regex capture group from the element's text |
| `dom_re2(dom, regex)` | `Array` | Two capture groups (key, value) from the element's text |
| `dom_re2(dom, regex, keyGroup, valueGroup)` | `Array` | Specified capture groups as (key, value) |

### Nil Checks

| Function | Returns | Description |
|---|---|---|
| `dom_is_nil(dom)` | `Boolean` | Whether the DOM is NIL (missing/empty) |
| `dom_is_not_nil(dom)` | `Boolean` | Whether the DOM is valid |

---

## DOM Selection Functions

DOM selection functions take a `ValueDom` and a `cssSelector`, then select child elements and extract a specific value from each. They follow a consistent naming pattern:

| Prefix | Scope | Returns |
|---|---|---|
| `all_*` | All matching elements | `ValueArray` |
| `first_*` | First match only | Scalar |
| `nth_*` | Nth match | Scalar |

All selection functions belong to the **`DOM`** namespace.

### Element Selection

| Function | Returns |
|---|---|
| `dom_select_all(dom, cssQuery)` | `ValueArray` of `ValueDom` |
| `dom_select_first(dom, cssQuery)` | `ValueDom` |
| `dom_select_nth(dom, cssQuery, n)` | `ValueDom` |

### Text Extraction

| Function | Returns |
|---|---|
| `dom_all_texts(dom, cssQuery)` | `ValueArray` |
| `dom_first_text(dom, cssQuery)` | `String` |
| `dom_nth_text(dom, cssQuery, n)` | `String` |

### Own Text Extraction

| Function | Returns |
|---|---|
| `dom_all_own_texts(dom, cssQuery)` | `ValueArray` |
| `dom_first_own_text(dom, cssQuery)` | `String` |
| `dom_nth_own_text(dom, cssQuery, n)` | `String` |

### Whole Text Extraction

| Function | Returns |
|---|---|
| `dom_whole_texts(dom, cssQuery)` | `ValueArray` |
| `dom_first_whole_text(dom, cssQuery)` | `String` |
| `dom_nth_whole_text(dom, cssQuery, n)` | `String` |

### HTML Extraction

| Function | Returns |
|---|---|
| `dom_all_slim_htmls(dom, cssQuery)` | `ValueArray` |
| `dom_first_slim_html(dom, cssQuery)` | `String` |
| `dom_nth_slim_html(dom, cssQuery, n)` | `String` |
| `dom_all_minimal_htmls(dom, cssQuery)` | `ValueArray` |
| `dom_first_minimal_html(dom, cssQuery)` | `String` |
| `dom_nth_minimal_html(dom, cssQuery, n)` | `String` |

### Numeric Extraction

| Function | Returns |
|---|---|
| `dom_all_integers(dom, cssQuery [, defaultValue])` | `ValueArray` |
| `dom_first_integer(dom, cssQuery [, defaultValue])` | `Int` |
| `dom_nth_integer(dom, cssQuery, n [, defaultValue])` | `Int` |
| `dom_all_floats(dom, cssQuery [, defaultValue])` | `ValueArray` |
| `dom_first_float(dom, cssQuery [, defaultValue])` | `ValueFloat` |
| `dom_nth_float(dom, cssQuery, n [, defaultValue])` | `ValueFloat` |

```sql
select
  dom_first_float(dom, 'span.price', 0.0) as price,
  dom_first_integer(dom, 'span.reviews', 0) as review_count
from load_and_select('https://example.com/products', '.product');
```

### Attribute Extraction

| Function | Returns |
|---|---|
| `dom_all_attrs(dom [, cssQuery], attrName)` | `ValueArray` |
| `dom_first_attr(dom [, cssQuery], attrName)` | `String` |
| `dom_nth_attr(dom, cssQuery, n, attrName)` | `String` |

```sql
select dom_first_attr(dom, 'a.title-link', 'href') as link
from load_and_select('https://example.com', 'body');
```

### Multi-Attribute Extraction

Returns multiple attributes per element.

| Function | Returns |
|---|---|
| `dom_all_multi_attrs(dom [, cssQuery], attrNames)` | `ValueArray` |
| `dom_first_multi_attrs(dom [, cssQuery], attrNames)` | `List<String>` |
| `dom_nth_multi_attrs(dom, cssQuery, n, attrNames)` | `List<String>` |

```sql
select dom_first_multi_attrs(dom, 'a', array('href', 'title', 'class'))
from load_and_select('https://example.com', 'body');
```

### Image & Link Extraction

These auto-append `img` / `a` to the CSS query if not already present.

| Function | Returns |
|---|---|
| `dom_all_imgs(dom [, cssQuery])` | `ValueArray` (absolute src) |
| `dom_first_img(dom [, cssQuery])` | `String` |
| `dom_nth_img(dom, cssQuery, n)` | `String` |
| `dom_all_hrefs(dom [, cssQuery])` | `ValueArray` (absolute href) |
| `dom_first_href(dom [, cssQuery])` | `String` |
| `dom_nth_href(dom, cssQuery, n)` | `String` |

### Node Labels

| Function | Returns |
|---|---|
| `dom_all_nodes_labels(dom [, cssQuery])` | `ValueArray` |
| `dom_first_node_labels(dom [, cssQuery])` | `String` |
| `dom_nth_node_labels(dom, cssQuery, n)` | `String` |

### Regex Selection

These apply a regex to the text of selected elements.

| Function | Returns |
|---|---|
| `dom_all_re1(dom, regex)` | `ValueArray` |
| `dom_all_re1(dom, cssQuery, regex)` | `ValueArray` |
| `dom_first_re1(dom, regex)` | `String` |
| `dom_first_re1(dom, cssQuery, regex)` | `String` |
| `dom_first_re1(dom, cssQuery, regex, group)` | `String` |
| `dom_all_re2(dom, regex)` | `ValueArray` |
| `dom_all_re2(dom, cssQuery, regex)` | `ValueArray` |
| `dom_all_re2(dom, cssQuery, regex, keyGroup, valueGroup)` | `ValueArray` |
| `dom_first_re2(dom, cssQuery, regex)` | `ValueArray` |
| `dom_first_re2(dom, cssQuery, regex, keyGroup, valueGroup)` | `ValueArray` |

### Inline Selection (Flattening)

`inlineSelect` returns selected elements as a flat array of `ValueDom`, suitable for passing to `explode`:

| Function | Returns |
|---|---|
| `dom_inline_select(dom, cssQuery)` | `ValueArray` |
| `dom_inline_select(dom, cssQuery, offset, limit)` | `ValueArray` |
| `dom_inline_select_text(dom, cssQuery [, offset, limit])` | `ValueArray` |

---

## String Functions

The **`STR`** namespace provides 80+ null-safe string functions. Most are auto-generated from [Apache Commons Lang `StringUtils`](https://commons.apache.org/proper/commons-lang/apidocs/org/apache/commons/lang3/StringUtils.html) and follow its exact behavior: `null` inputs return `null` (or `false`/`0` for boolean/int returns).

See the source: [StringFunctions.kt](https://github.com/apache/pulsar/blob/master/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/StringFunctions.kt).

### Checks

| Function | Returns |
|---|---|
| `str_is_empty(str)` | `Boolean` |
| `str_is_not_empty(str)` | `Boolean` |
| `str_is_blank(str)` | `Boolean` |
| `str_is_not_blank(str)` | `Boolean` |
| `str_is_any_empty(strs)` | `Boolean` |
| `str_is_none_empty(strs)` | `Boolean` |
| `str_is_any_blank(strs)` | `Boolean` |
| `str_is_none_blank(strs)` | `Boolean` |
| `str_is_alpha(str)` | `Boolean` |
| `str_is_alphanumeric(str)` | `Boolean` |
| `str_is_alpha_space(str)` | `Boolean` |
| `str_is_alphanumeric_space(str)` | `Boolean` |
| `str_is_numeric(str)` | `Boolean` |
| `str_is_numeric_space(str)` | `Boolean` |
| `str_is_whitespace(str)` | `Boolean` |
| `str_contains_whitespace(str)` | `Boolean` |
| `str_is_ascii_printable(str)` | `Boolean` |
| `str_is_all_lower_case(str)` | `Boolean` |
| `str_is_all_upper_case(str)` | `Boolean` |

### Trimming & Stripping

| Function | Returns |
|---|---|
| `str_trim(str)` | `String?` |
| `str_trim_to_null(str)` | `String?` |
| `str_trim_to_empty(str)` | `String?` |
| `str_strip(str)` | `String?` |
| `str_strip(str, stripChars)` | `String?` |
| `str_strip_to_null(str)` | `String?` |
| `str_strip_to_empty(str)` | `String?` |
| `str_strip_start(str, stripChars)` | `String?` |
| `str_strip_end(str, stripChars)` | `String?` |
| `str_strip_all(strs)` | `Array` |
| `str_strip_all(strs, stripChars)` | `Array` |
| `str_strip_accents(str)` | `String?` |
| `str_normalize_space(str)` | `String?` |

### Substring Extraction

| Function | Returns |
|---|---|
| `str_substring(str, start)` | `String?` |
| `str_substring(str, start, end)` | `String?` |
| `str_left(str, len)` | `String?` |
| `str_right(str, len)` | `String?` |
| `str_mid(str, pos, len)` | `String?` |
| `str_substring_before(str, separator)` | `String?` |
| `str_substring_after(str, separator)` | `String?` |
| `str_substring_before_last(str, separator)` | `String?` |
| `str_substring_after_last(str, separator)` | `String?` |
| `str_substring_between(str, tag)` | `String?` |
| `str_substring_between(str, open, close)` | `String?` |
| `str_substrings_between(str, open, close)` | `Array` |

### Search & Contains

| Function | Returns |
|---|---|
| `str_contains_any(str, searchChars)` | `Boolean` |
| `str_contains_only(str, validChars)` | `Boolean` |
| `str_contains_none(str, invalidChars)` | `Boolean` |
| `str_index_of_any(str, searchChars)` | `Int` |
| `str_index_of_any_but(str, searchChars)` | `Int` |
| `str_ordinal_index_of(str, searchStr, ordinal)` | `Int` |
| `str_last_ordinal_index_of(str, searchStr, ordinal)` | `Int` |
| `str_count_matches(str, sub)` | `Int` |

### Splitting & Joining

| Function | Returns |
|---|---|
| `str_split(str)` | `Array` |
| `str_split(str, separator)` | `Array` |
| `str_split(str, separator, max)` | `Array` |
| `str_split_by_whole_separator(str, separator)` | `Array` |
| `str_split_by_whole_separator(str, separator, max)` | `Array` |
| `str_split_preserve_all_tokens(str)` | `Array` |
| `str_split_preserve_all_tokens(str, separator)` | `Array` |
| `str_split_preserve_all_tokens(str, separator, max)` | `Array` |
| `str_split_by_character_type(str)` | `Array` |
| `str_split_by_character_type_camel_case(str)` | `Array` |
| `str_join(array)` | `String?` |
| `str_join(array, separator)` | `String?` |

### Case Conversion

| Function | Returns |
|---|---|
| `str_upper_case(str)` | `String?` |
| `str_lower_case(str)` | `String?` |
| `str_capitalize(str)` | `String?` |
| `str_uncapitalize(str)` | `String?` |
| `str_swap_case(str)` | `String?` |

### Replace & Remove

| Function | Returns |
|---|---|
| `str_replace_chars(str, searchChars, replacement)` | `String?` |
| `str_replace_each(str, searchList, replacementList)` | `String?` |
| `str_replace_each_repeatedly(str, searchList, replacementList)` | `String?` |
| `str_delete_whitespace(str)` | `String?` |
| `str_remove(str, remove)` | — |
| `str_overlay(str, overlay, start, end)` | `String?` |

### Padding & Centering

| Function | Returns |
|---|---|
| `str_left_pad(str, size)` | `String?` |
| `str_left_pad(str, size, padStr)` | `String?` |
| `str_right_pad(str, size)` | `String?` |
| `str_right_pad(str, size, padStr)` | `String?` |
| `str_center(str, size)` | `String?` |
| `str_center(str, size, padStr)` | `String?` |
| `str_repeat(str, repeat)` | `String?` |
| `str_repeat(str, separator, repeat)` | `String?` |

### Other

| Function | Returns |
|---|---|
| `str_length(str)` | `Int` |
| `str_reverse(str)` | `String?` |
| `str_reverse_delimited(str, delimiter)` | `String?` |
| `str_abbreviate(str, maxWidth)` | `String?` |
| `str_abbreviate(str, offset, maxWidth)` | `String?` |
| `str_abbreviate_middle(str, middle, maxWidth)` | `String?` |
| `str_chomp(str)` | `String?` |
| `str_chop(str)` | `String?` |
| `str_difference(str1, str2)` | `String?` |
| `str_index_of_difference(str1, str2)` | `Int` |
| `str_index_of_difference(strs)` | `Int` |
| `str_get_common_prefix(strs)` | `String?` |
| `str_default_string(str)` | `String?` |
| `str_default_if_blank(str, defaultStr)` | `String?` |
| `str_default_if_empty(str, defaultStr)` | `String?` |
| `str_levenshtein_distance(str1, str2)` | — |

### Number Parsing

| Function | Returns |
|---|---|
| `str_first_integer(str, defaultValue)` | `Int` |
| `str_first_float(str, defaultValue)` | `Float` |
| `str_get_first_float_number(str, defaultValue)` | `Float` |

---

## Array Functions

The **`ARRAY`** namespace provides utilities for working with value arrays.

| Function | Returns | Description |
|---|---|---|
| `array_join_to_string(values, separator)` | `String` | Joins array elements with a separator |
| `array_first_not_blank(values)` | `Value` | First non-blank value in the array |
| `array_first_not_empty(values)` | `Value` | First non-empty value in the array |

---

## Box Functions

The **`IN_BOX`** namespace selects elements by their **visual bounding box** position, translating `box` coordinates into CSS selectors internally.

| Function | Returns |
|---|---|
| `in_box_all(dom, box)` | `ValueArray` |
| `in_box_all(dom, box, offset, limit)` | `ValueArray` |
| `in_box_first(dom, box)` | `ValueDom` |
| `in_box_nth(dom, box, n)` | `ValueDom` |
| `in_box_first_text(dom, box)` | `String` |
| `in_box_nth_text(dom, box, n)` | `String` |
| `in_box_first_img(dom, box)` | `String` |
| `in_box_nth_img(dom, box, n)` | `String` |
| `in_box_first_href(dom, box)` | `String` |
| `in_box_nth_href(dom, box, n)` | `String` |
| `in_box_first_re1(dom, box, regex)` | `String` |
| `in_box_first_re1(dom, box, regex, group)` | `String` |
| `in_box_first_re2(dom, box, regex)` | `ValueArray` |
| `in_box_first_re2(dom, box, regex, keyGroup, valueGroup)` | `ValueArray` |

---

## LLM Functions

The **`LLM`** namespace enables interaction with a large language model directly from SQL.

| Function | Returns | Description |
|---|---|---|
| `llm_model_name()` | `String` | Returns the configured LLM model name |
| `llm_chat(prompt)` | `String` | Sends a prompt to the LLM and returns the response |
| `llm_chat(dom, prompt)` | `String` | Sends a DOM element's content plus a prompt to the LLM |
| `llm_extract(dom, dataExtractionRules)` | `ValueStringJSON` | Extracts structured fields from DOM content using the LLM |

```sql
select llm_chat(dom, 'Summarize this product description in one sentence.')
from load_and_select('https://example.com/product/123', 'body');
```

### Structured Extraction

`llm_extract` takes a DOM and a set of extraction rules (field descriptions), then returns JSON:

```sql
select llm_extract(dom,
  'name: the product name, ' ||
  'price: the product price as a number, ' ||
  'rating: the average rating out of 5'
) as product_info
from load_and_select('https://example.com/product/123', 'body');
```

---

## Chat Functions

The `chat` function in the **`DOM`** namespace provides a lower-level chat interface with separate user and system messages:

| Function | Returns | Description |
|---|---|---|
| `dom_chat(userMessage, systemMessage)` | `String` | Chat with the AI model using system+user messages |

```sql
select dom_chat('What is the capital of France?', 'You are a helpful geography assistant.');
```

---

## DateTime Functions

The **`TIME`** namespace provides date-time parsing and formatting.

| Function | Returns | Description |
|---|---|---|
| `time_first_mysql_date_time(text [, pattern])` | `String` | Parses the first date-time from text, formats as MySQL datetime |
| `time_first_date_time(text [, pattern])` | `String` | Parses the first date-time from text, formats with the given pattern |

```sql
select time_first_mysql_date_time('Published on 2024-03-15 at 10:30 AM')
-- Returns: "2024-03-15 10:30:00"
```

---

## Common Functions

These functions have **no namespace** and are available globally.

### Regex on Strings

| Function | Returns |
|---|---|
| `re1(text, regex)` | `String` |
| `re1(text, regex, group)` | `String` |
| `re2(text, regex)` | `ValueArray` |
| `re2(text, regex, keyGroup, valueGroup)` | `ValueArray` |

### Validation & URL

| Function | Returns |
|---|---|
| `is_numeric(str)` | `Boolean` |
| `get_top_private_domain(url)` | `String` |

### Array Construction

| Function | Returns |
|---|---|
| `make_array(values...)` | `ValueArray` |
| `make_array_n(value, n)` | `ValueArray` |

### Array Aggregation

| Function | Returns |
|---|---|
| `int_array_min(values)` | `Value` |
| `int_array_max(values)` | `Value` |
| `float_array_min(values)` | `Value` |
| `float_array_max(values)` | `Value` |

### JSON & Type Conversion

| Function | Returns |
|---|---|
| `get_string(value)` | `String` |
| `is_empty(array)` | `Boolean` |
| `is_not_empty(array)` | `Boolean` |
| `to_json(rs)` | `String` |
| `make_value_string_json()` | `ValueStringJSON` |
| `make_value_string_json(jsonText, javaClassName)` | `ValueStringJSON` |
| `format_timestamp(timestamp [, fmt])` | `String` |

---

## Common Table Functions

These table functions have **no namespace** and are available globally in the `FROM` clause.

### loadOptions

```
loadOptions()
```

Returns a `ResultSet` of all available load options with their types, defaults, and descriptions:

```sql
select * from loadOptions();
```

Columns: `OPTION`, `TYPE`, `DEFAULT`, `DESCRIPTION`

### xsqlHelp

```
xsqlHelp()
```

Returns a `ResultSet` listing every registered X-SQL function:

```sql
select * from xsqlHelp();
```

Columns: `NAMESPACE`, `XSQL FUNCTION`, `NATIVE FUNCTION`, `DESCRIPTION`

### explode

```
explode(values [, col])
```

Explodes a `ValueArray` into a `ResultSet`, one row per element:

```sql
select * from explode(dom_all_texts(dom, 'ul li'));
```

### posexplode

```
posexplode(values [, col])
```

Like `explode` but includes the 1-based position of each element:

```sql
select * from posexplode(dom_all_texts(dom, 'ul li'));
```

Columns: `POS`, `COL`

### map

```
map(key1, value1 [, key2, value2 ...])
```

Creates a two-column `ResultSet` from alternating key-value pairs:

```sql
select * from map('name', 'Alice', 'age', '30');
```

Columns: `KEY`, `VALUE`

### gauges / meters

```
gauges()
meters()
```

Return system monitoring metrics:

```sql
select * from gauges();
select * from meters();
```

---

## Metadata Functions

The **`META`** namespace provides access to stored page metadata.

### Scalar

| Function | Returns | Description |
|---|---|---|
| `meta_get(url)` | `String` | Retrieves a formatted page from the database by URL |

### Table Functions

| Function | Returns |
|---|---|
| `meta_load(configuredUrl)` | `ResultSet(KEY, VALUE)` |
| `meta_fetch(configuredUrl)` | `ResultSet(KEY, VALUE)` |

```sql
select * from meta_load('https://example.com');
```

---

## Admin Functions

The **`ADMIN`** namespace provides debugging and session management utilities.

| Function | Returns | Description |
|---|---|---|
| `admin_echo(msg)` | `String` | Echoes the message back |
| `admin_echo(msg1, msg2)` | `String` | Echoes both messages joined |
| `admin_print(msg)` | — | Prints to stdout |
| `admin_session_count()` | `Int` | Returns the number of active sessions |
| `admin_close_session()` | `String` | Closes the current session |
| `admin_save(url [, postfix])` | `String` | Saves a page to disk |

---

## Complete Examples

### E-commerce product listing

```sql
select
  dom_base_uri(dom) as url,
  dom_first_text(dom, '#productTitle') as title,
  str_substring_after(dom_first_href(dom, '#wayfinding-breadcrumbs_container ul li:last-child a'), '&node=') as category,
  dom_first_slim_html(dom, '#bylineInfo') as brand,
  dom_all_slim_htmls(dom, '#imageBlock img') as gallery,
  dom_first_slim_html(dom, '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width > 400)') as img,
  dom_first_text(dom, '#price tr td:contains(List Price) ~ td') as listprice,
  dom_first_text(dom, '#price tr td:matches(^Price) ~ td') as price,
  str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
from load_and_select('https://www.amazon.com/dp/B08PP5MSVB   -i 1d -njr 3', 'body');
```

### News headline scraper

```sql
select
  dom_first_text(dom, 'h2 a') as headline,
  dom_first_href(dom, 'h2 a') as link,
  dom_first_text(dom, 'time') as published_date
from load_and_select('https://news.ycombinator.com', 'tr.athing', 1, 30);
```

### Two-level iteration (categories → products)

```sql
select
  dom_first_text(cat.dom, 'h3') as category,
  dom_first_text(prod.dom, '.name') as product_name,
  dom_first_float(prod.dom, '.price', 0.0) as price
from load_and_select('https://example.com/shop', '.category') cat
join select(cat.dom, '.product-item') prod;
```

### LLM-powered extraction

```sql
select
  dom_base_uri(dom) as url,
  llm_extract(dom, 'title: the article title, author: the author name, ' ||
                    'date: the publication date, summary: a one-paragraph summary') as extracted
from load_and_select('https://example.com/article/42', 'body');
```

### Using explode to flatten arrays

```sql
select col as image_url
from load_and_select('https://example.com/product/123', 'body') t
join explode(dom_all_imgs(dom, '#gallery')) img;
```

---

## Source Code

All X-SQL functions are defined in the [pulsar-ql](https://github.com/apache/pulsar/tree/master/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs) module:

| File | Namespace | Contents |
|---|---|---|
| `DomFunctions.kt` | `DOM` | DOM properties, attributes, content, traversal |
| `DomSelectFunctions.kt` | `DOM` | CSS-based element selection (all/first/nth variants) |
| `DomInlineSelectFunctions.kt` | `DOM` | Array-returning inline selection |
| `DomFunctionTables.kt` | `DOM` | Table functions (load_and_select, load_out_pages, etc.) |
| `StringFunctions.kt` | `STR` | 80+ Apache Commons Lang string operations |
| `ArrayFunctions.kt` | `ARRAY` | Array utilities |
| `BoxFunctions.kt` | `IN_BOX` | Visual bounding-box selection |
| `LLMFunctions.kt` | `LLM` | LLM chat and structured extraction |
| `ChatFunctions.kt` | `DOM` | Lower-level chat interface |
| `DateTimeFunctions.kt` | `TIME` | Date-time parsing |
| `CommonFunctions.kt` | _(none)_ | Regex, array ops, JSON conversion |
| `CommonFunctionTables.kt` | _(none)_ | `loadOptions`, `explode`, `posexplode`, `map` |
| `MetadataFunctions.kt` | `META` | Page metadata retrieval |
| `MetadataFunctionTables.kt` | `META` | Page metadata as ResultSet |
| `AdminFunctions.kt` | `ADMIN` | Session management, debugging |
