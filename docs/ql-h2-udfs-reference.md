# H2 Database User-Defined Functions (UDFs) Reference

> Auto-generated from `pulsar-core/pulsar-ql/src/main/kotlin/ai/platon/pulsar/ql/h2/udfs/`
> Generated on: 2026-06-10

## Overview

Pulsar QL extends the H2 database engine with **15 UDF groups** organized by namespace. Functions are registered via the `@UDFGroup` and `@UDFunction` annotations. When a namespace is specified, function aliases are generated as `NAMESPACE_FUNCTION_NAME` (e.g., `DOM_LOAD`, `STR_TRIM`).

Function aliases are derived from camelCase method names by splitting on uppercase letters and joining with underscores. For example, `firstText` becomes `FIRST_TEXT`.

---

## 1. ADMIN — Administrative Functions

**File:** `AdminFunctions.kt`
**Namespace:** `ADMIN`

| Alias | Signature | Description |
|-------|-----------|-------------|
| `ADMIN_ECHO` | `echo(message: String): String` | Returns the input message unchanged. |
| `ADMIN_ECHO` | `echo(message: String, message2: String): String` | Returns two messages concatenated with a comma. |
| `ADMIN_PRINT` | `print(message: String)` | Prints the message to stdout. |
| `ADMIN_SESSION_COUNT` | `sessionCount(): Int` | Returns the current number of active SQL sessions. |
| `ADMIN_CLOSE_SESSION` | `closeSession(): String` | Closes the current H2 session and returns its string representation. |
| `ADMIN_SAVE` | `save(url: String, postfix: String = ".htm"): String` | Loads a page by URL and saves it to the web cache directory. |

---

## 2. ARRAY — Array Functions

**File:** `ArrayFunctions.kt`
**Namespace:** `ARRAY`

| Alias | Signature | Description |
|-------|-----------|-------------|
| `ARRAY_JOIN_TO_STRING` | `joinToString(values: ValueArray, separator: String): String` | Joins array elements into a single string with the given separator. |
| `ARRAY_FIRST_NOT_BLANK` | `firstNotBlank(values: ValueArray): Value?` | Returns the first non-blank value in the array, or null. |
| `ARRAY_FIRST_NOT_EMPTY` | `firstNotEmpty(values: ValueArray): Value?` | Returns the first non-empty value in the array, or null. |

---

## 3. IN_BOX — CSS Box Shorthand Functions

**File:** `BoxFunctions.kt`
**Namespace:** `IN_BOX`

Delegates to `DomInlineSelectFunctions` and `DomSelectFunctions` using CSS box notation (the `box` parameter is converted via `convertBox()`). These are convenience shorthands.

| Alias | Signature | Description |
|-------|-----------|-------------|
| `IN_BOX_ALL` | `all(dom: ValueDom, box: String): ValueArray` | Selects all elements matching the box CSS and returns them as an array. |
| `IN_BOX_ALL` | `all(dom: ValueDom, box: String, offset: Int, limit: Int): ValueArray` | Selects all elements matching the box CSS with offset/limit. |
| `IN_BOX_FIRST` | `first(dom: ValueDom, box: String): ValueDom` | Selects the first element matching the box CSS. |
| `IN_BOX_NTH` | `nth(dom: ValueDom, box: String, n: Int): ValueDom` | Selects the nth element matching the box CSS. |
| `IN_BOX_FIRST_TEXT` | `firstText(dom: ValueDom, box: String): String` | Returns the text of the first element matching the box CSS. |
| `IN_BOX_NTH_TEXT` | `nthText(dom: ValueDom, box: String, n: Int): String` | Returns the text of the nth element matching the box CSS. |
| `IN_BOX_FIRST_IMG` | `firstImg(dom: ValueDom, box: String): String` | Returns the src of the first image in the box. |
| `IN_BOX_NTH_IMG` | `nthImg(dom: ValueDom, box: String, n: Int): String` | Returns the src of the nth image in the box. |
| `IN_BOX_FIRST_HREF` | `firstHref(dom: ValueDom, box: String): String` | Returns the href of the first anchor in the box. |
| `IN_BOX_NTH_HREF` | `nthHref(dom: ValueDom, box: String, n: Int): String` | Returns the href of the nth anchor in the box. |
| `IN_BOX_FIRST_RE1` | `firstRe1(dom: ValueDom, box: String, regex: String): String` | Extracts the first regex group from the first element text in the box. |
| `IN_BOX_FIRST_RE1` | `firstRe1(dom: ValueDom, box: String, regex: String, group: Int): String` | Extracts the nth regex group from the first element text in the box. |
| `IN_BOX_FIRST_RE2` | `firstRe2(dom: ValueDom, box: String, regex: String): ValueArray` | Extracts a key-value pair via regex from the first element text. |
| `IN_BOX_FIRST_RE2` | `firstRe2(dom: ValueDom, box: String, regex: String, keyGroup: Int, valueGroup: Int): ValueArray` | Extracts a key-value pair via regex with custom group indices. |

---

## 4. DOM — Chat Functions (AI Chat)

**File:** `ChatFunctions.kt`
**Namespace:** `DOM`

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_CHAT` | `chat(userMessage: String, systemMessage: String): String` | Chat with the AI model using a user message and a system message. Returns the model's response content. |

---

## 5. System Helper Table Functions (No Namespace)

**File:** `CommonFunctionTables.kt`
**Namespace:** *(none — aliases are unprefixed)*

| Alias | Signature | Description |
|-------|-----------|-------------|
| `LOAD_OPTIONS` | `loadOptions(): ResultSet` | Returns a ResultSet of all load options (columns: OPTION, TYPE, DEFAULT, DESCRIPTION). Used to configure URL fetching behavior. |
| `XSQL_HELP` | `xsqlHelp(): ResultSet` | Returns a ResultSet of all registered X-SQL functions (columns: NAMESPACE, XSQL FUNCTION, NATIVE FUNCTION, DESCRIPTION). |
| `GAUGES` | `gauges(): ResultSet` | Returns a ResultSet of system gauge metrics (columns: NAME, VALUE). |
| `METERS` | `meters(): ResultSet` | Returns a ResultSet of system meter metrics (columns: NAME, COUNT, M1_RATE, M5_RATE, M15_RATE, MEAN_RATE, RATE_UNIT). |
| `MAP` | `map(vararg kvs: Value): ResultSet` | Creates a key-value ResultSet from alternating key-value pairs (k1, v1, k2, v2…). |
| `EXPLODE` | `explode(): ResultSet` | Creates an empty ResultSet. |
| `EXPLODE` | `explode(values: ValueArray, col: String = "COL"): ResultSet` | Creates a single-column ResultSet from an array of values. |
| `POSEXPLODE` | `posexplode(): ResultSet` | Creates an empty ResultSet (with position column). |
| `POSEXPLODE` | `posexplode(values: ValueArray, col: String = "COL"): ResultSet` | Creates a ResultSet with position (1-based) and value columns from an array. |
| `TRANSPOSE` | `transpose(rs: ResultSet): ResultSet` | Transposes a SimpleResultSet (rows ↔ columns). **Note: marked as TODO — not correctly implemented.** |

---

## 6. Common Utility Functions (No Namespace)

**File:** `CommonFunctions.kt`
**Namespace:** *(none)*

| Alias | Signature | Description |
|-------|-----------|-------------|
| `IS_NUMERIC` | `isNumeric(str: String): Boolean` | Tests if the given string is a number. |
| `GET_TOP_PRIVATE_DOMAIN` | `getTopPrivateDomain(url: String): String` | Extracts the top private domain (e.g., `example.com`) from a URL. |
| `RE1` | `re1(text: String, regex: String): String` | Extracts the first group from a regex match on the given text. |
| `RE1` | `re1(text: String, regex: String, group: Int): String` | Extracts the nth group from a regex match on the given text. |
| `RE2` | `re2(text: String, regex: String): ValueArray` | Extracts a key-value pair (groups 1 and 2) from a regex match. |
| `RE2` | `re2(text: String, regex: String, keyGroup: Int, valueGroup: Int): ValueArray` | Extracts a key-value pair from a regex match using custom group indices. |
| `MAKE_ARRAY` | `makeArray(vararg values: Value): ValueArray` | Creates a ValueArray from vararg values. |
| `MAKE_ARRAY_N` | `makeArrayN(value: Value, n: Int): ValueArray` | Creates a ValueArray by repeating a value n times. |
| `TO_JSON` | `toJson(rs: ResultSet): String` | Converts the first two columns of a ResultSet into a JSON object. |
| `MAKE_VALUE_STRING_JSON` | `makeValueStringJSON(): ValueStringJSON` | Creates an empty JSON ValueStringJSON (`{}`). **[Beta]** |
| `MAKE_VALUE_STRING_JSON` | `makeValueStringJSON(jsonText: String, javaClassName: String): ValueStringJSON` | Creates a ValueStringJSON from JSON text and a Java class name. **[Beta]** |
| `INT_ARRAY_MIN` | `intArrayMin(values: ValueArray): Value` | Finds the minimum integer value in an array (ignores non-integers). |
| `INT_ARRAY_MAX` | `intArrayMax(values: ValueArray): Value` | Finds the maximum integer value in an array (ignores non-integers). |
| `FLOAT_ARRAY_MIN` | `floatArrayMin(values: ValueArray): Value` | Finds the minimum float value in an array (ignores non-floats). |
| `FLOAT_ARRAY_MAX` | `floatArrayMax(values: ValueArray): Value` | Finds the maximum float value in an array (ignores non-floats). |
| `GET_STRING` | `getString(value: Value): String` | Returns the string representation of a Value. |
| `IS_EMPTY` | `isEmpty(array: ValueArray): Boolean` | Checks if a ValueArray is empty. |
| `IS_NOT_EMPTY` | `isNotEmpty(array: ValueArray): Boolean` | Checks if a ValueArray is not empty. |
| `FORMAT_TIMESTAMP` | `formatTimestamp(timestamp: String, fmt: String = "yyyy-MM-dd HH:mm:ss"): String` | Formats a timestamp string (long millis) using the given date format pattern. |

---

## 7. TIME — Date/Time Functions

**File:** `DateTimeFunctions.kt`
**Namespace:** `TIME`

| Alias | Signature | Description |
|-------|-----------|-------------|
| `TIME_FIRST_MYSQL_DATE_TIME` | `firstMysqlDateTime(text: String?, pattern: String = "yyyy-MM-dd HH:mm:ss"): String` | Parses a string as a date-time and formats it with the given pattern. Falls back to EPOCH if parsing fails. |
| `TIME_FIRST_DATE_TIME` | `firstDateTime(text: String?, pattern: String = "yyyy-MM-dd HH:mm:ss"): String` | Same as `firstMysqlDateTime` — attempts best-effort ISO instant parsing. |

---

## 8. DOM — Table-Returning Functions

**File:** `DomFunctionTables.kt`
**Namespace:** `DOM`

These functions return `ResultSet` objects and are typically invoked via `SELECT * FROM DOM_LOAD_ALL(...)`.

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_LOAD_ALL` | `loadAll(urls: ValueArray): ResultSet` | Loads all pages specified by the given URL array. Returns a DOM ResultSet. Has shortcut (ignorable namespace). |
| `DOM_LOAD_AND_SELECT` | `loadAndSelect(url: String, cssQuery: String, offset: Int = 1, limit: Int = MAX_VALUE): ResultSet` | Loads a page and selects elements matching the CSS query. Returns a DOM ResultSet. Has shortcut. |
| `DOM_SELECT` | `select(dom: ValueDom, cssQuery: String, offset: Int = 1, limit: Int = MAX_VALUE): ResultSet` | Selects elements from a DOM by CSS query and returns them as a ResultSet. |
| `DOM_LOAD_AND_GET_LINKS` | `loadAndGetLinks(portalUrl: Value, restrictCss: String = ":root", offset: Int = 1, limit: Int = MAX_VALUE): ResultSet` | Loads a portal page and extracts all links from matched elements. Has shortcut. |
| `DOM_LINKS` | `links(dom: ValueDom, cssQuery: String = ":root", offset: Int = 1, limit: Int = MAX_VALUE): ResultSet` | Extracts all links from matched elements within a DOM. |
| `DOM_LOAD_AND_GET_ANCHORS` | `loadAndGetAnchors(portalUrl: String, restrictCss: String = ":root", offset: Int = 1, limit: Int = MAX_VALUE): ResultSet` | Loads a page and finds all anchor elements. Has shortcut. |
| `DOM_LOAD_OUT_PAGES` | `loadOutPages(portalUrl: String, restrictCss: String = ":root", offset: Int = 1, limit: Int = MAX_VALUE, normalize: Boolean = true): ResultSet` | Loads a portal page, follows all outbound links, and returns the linked pages as DOMs. Has shortcut. |
| `DOM_LOAD_OUT_PAGES_IGNORE_URL_QUERY` | `loadOutPagesIgnoreUrlQuery(portalUrl: String, restrictCss: String = ":root", offset: Int = 1, limit: Int = MAX_VALUE, normalize: Boolean = true): ResultSet` | Same as `loadOutPages` but ignores URL query parameters when normalizing. Has shortcut. |
| `DOM_LOAD_OUT_PAGES_AND_SELECT` | `loadOutPagesAndSelect(portal: String, restrictCss: String = ":root", offset: Int = 1, limit: Int = MAX_VALUE, targetCss: String = ":root", normalize: Boolean = true, ignoreQuery: Boolean = false): ResultSet` | Loads outbound pages and selects elements from each using a target CSS query. Has shortcut. |
| `DOM_LOAD_OUT_PAGES_AND_SELECT_FIRST` | `loadOutPagesAndSelectFirst(portalUrl: String, restrictCss: String = ":root", offset: Int = 1, limit: Int = MAX_VALUE, targetCss: String = ":root", normalize: Boolean = true, ignoreQuery: Boolean = false): ResultSet` | Loads outbound pages and selects the **first** matching element from each. Has shortcut. |
| `DOM_LOAD_AND_GET_FEATURES` | `loadAndGetFeatures(portalUrl: String, cssQuery: String = "DIV,P,UL,OL,LI,DL,DT,DD,TABLE,TR,TD,H1,H2,H3", offset: Int = 1, limit: Int = 100): ResultSet` | Loads a page and returns element features (DOM metrics) for matched elements. Has shortcut. |
| `DOM_FEATURES` | `features(dom: ValueDom, cssSelector: String = "DIV,P,UL,OL,LI,DL,DT,DD,TABLE,TR,TD", offset: Int = 1, limit: Int = 100): ResultSet` | Returns element features for matched elements within a DOM. Columns include DOM reference and all registered feature names. |
| `DOM_LOAD_AND_GET_ELEMENTS_WITH_MOST_SIBLING` | `loadAndGetElementsWithMostSibling(portalUrl: String, restrictCss: String = "DIV,P,UL,OL,LI,DL,DT,DD,TABLE,TR,TD", offset: Int = 1, limit: Int = MAX_VALUE): ResultSet` | Loads a page and returns elements sorted by sibling count (descending). |
| `DOM_GET_ELEMENTS_WITH_MOST_SIBLING` | `getElementsWithMostSibling(dom: ValueDom, restrictCss: String = "DIV,P,UL,OL,LI,DL,DT,DD,TABLE,TR,TD", offset: Int = 1, limit: Int = MAX_VALUE): ResultSet` | Returns elements sorted by sibling count (descending) within a DOM. Has shortcut. |

---

## 9. DOM — Core DOM Functions

**File:** `DomFunctions.kt`
**Namespace:** `DOM`

The largest function group — provides element property access, navigation, text extraction, and computed feature values.

### Page Loading

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_LOAD` | `load(configuredUrl: String): ValueDom` | Loads a page from the database (or fetches from the web if absent/expired) and returns it as a DOM. |
| `DOM_FETCH` | `fetch(configuredUrl: String): ValueDom` | Forces an immediate fetch of the page from the web (sets expiry to zero). |

### DOM State Checks

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_IS_NIL` | `isNil(dom: ValueDom): Boolean` | Checks if the DOM is nil (empty/invalid). |
| `DOM_IS_NOT_NIL` | `isNotNil(dom: ValueDom): Boolean` | Checks if the DOM is not nil. |

### Element Properties

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ATTR` | `attr(dom: ValueDom, attrName: String): String` | Gets the value of the given attribute on the element. |
| `DOM_LABELS` | `labels(dom: ValueDom): String` | Gets the `A_LABELS` attribute value. |
| `DOM_FEATURE` | `feature(dom: ValueDom, featureName: String): Double` | Gets the computed feature value by name. |
| `DOM_HAS_ATTR` | `hasAttr(dom: ValueDom, attrName: String): Boolean` | Checks if the element has the given attribute. |
| `DOM_STYLE` | `style(dom: ValueDom, styleName: String): String` | Gets the computed CSS style value. |
| `DOM_SEQUENCE` | `sequence(dom: ValueDom): Int` | Gets the element's sequence number. |
| `DOM_DEPTH` | `depth(dom: ValueDom): Int` | Gets the element's depth in the DOM tree. |
| `DOM_CSS_SELECTOR` | `cssSelector(dom: ValueDom): String` | Gets the CSS selector path for the element. |
| `DOM_CSS_PATH` | `cssPath(dom: ValueDom): String` | Alias for `cssSelector` — returns the CSS selector path. |

### URL / Location

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_URI` | `uri(dom: ValueDom): String` | Returns the page's normalized URI (the permanent internal address / database key). |
| `DOM_BASE_URI` | `baseUri(dom: ValueDom): String` | Returns the element's base URI. |
| `DOM_ABS_URL` | `absUrl(dom: ValueDom, attributeKey: String): String` | Resolves a relative URL attribute to an absolute URL. |
| `DOM_LOCATION` | `location(dom: ValueDom): String` | Returns the page's location (the last working address, may differ from URI). |

### DOM Tree Navigation

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_CHILD_NODE_SIZE` | `childNodeSize(dom: ValueDom): Int` | Returns the number of child nodes (including text nodes). |
| `DOM_CHILD_ELEMENT_SIZE` | `childElementSize(dom: ValueDom): Int` | Returns the number of child elements (Element nodes only). |
| `DOM_SIBLING_SIZE` | `siblingSize(dom: ValueDom): Int` | Returns the number of sibling nodes. |
| `DOM_SIBLING_INDEX` | `siblingIndex(dom: ValueDom): Int` | Returns the element's index among siblings. |
| `DOM_ELEMENT_SIBLING_SIZE` | `elementSiblingSize(dom: ValueDom): Int` | Returns the number of sibling elements. |
| `DOM_ELEMENT_SIBLING_INDEX` | `elementSiblingIndex(dom: ValueDom): Int` | Returns the element's index among element siblings. |
| `DOM_PARENT` | `parent(dom: ValueDom): ValueDom` | Returns the parent element as a DOM. |
| `DOM_ANCESTOR` | `ancestor(dom: ValueDom, n: Int): ValueDom` | Returns the nth ancestor element. |
| `DOM_PARENT_NAME` | `parentName(dom: ValueDom): String` | Returns the unique name of the parent element. |
| `DOM_OWNER_DOCUMENT` | `ownerDocument(dom: ValueDom): ValueDom` | Returns the owner document of the element. |
| `DOM_OWNER_BODY` | `ownerBody(dom: ValueDom): ValueDom` | Returns the owner body element. |
| `DOM_DOCUMENT_VARIABLES` | `documentVariables(dom: ValueDom): ValueDom` | Returns the Pulsar meta-information meta element from the document head. |

### Element Identity

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_TAG_NAME` | `tagName(dom: ValueDom): String` | Returns the element's tag name (e.g., `DIV`, `A`). |
| `DOM_ID` | `id(dom: ValueDom): String` | Returns the element's `id` attribute. |
| `DOM_CLASS_NAME` | `className(dom: ValueDom): String` | Returns the element's `class` attribute. |
| `DOM_CLASS_NAMES` | `classNames(dom: ValueDom): Set<String>` | Returns the element's class names as a set. |
| `DOM_HAS_CLASS` | `hasClass(dom: ValueDom, className: String): Boolean` | Checks if the element has a specific class. |
| `DOM_UNIQUE_NAME` | `uniqueName(dom: ValueDom): String` | Returns the element's unique name identifier. |
| `DOM_VALUE` | `value(dom: ValueDom): String` | Returns the element's form value (`val()`). |
| `DOM_DATA` | `data(dom: ValueDom): String` | Returns the element's combined data attributes. |

### Link & Image Properties

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_HREF` | `href(dom: ValueDom): String` | Returns the element's `href` attribute. |
| `DOM_ABS_HREF` | `absHref(dom: ValueDom): String` | Returns the absolute URL of the element's `href` attribute. |
| `DOM_SRC` | `src(dom: ValueDom): String` | Returns the element's `src` attribute. |
| `DOM_ABS_SRC` | `absSrc(dom: ValueDom): String` | Returns the absolute URL of the element's `src` attribute. |
| `DOM_LINKS` | `links(dom: ValueDom): ValueArray` | Returns all `<a>` elements within the DOM as a ValueArray. |

### Title

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_TITLE` | `title(dom: ValueDom): String` | Gets the element's `title` attribute. |
| `DOM_DOC_TITLE` | `docTitle(dom: ValueDom): String` | Gets the document's `<title>` text. If called on a non-document element, it navigates to the owner document first. |

### Text Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_HAS_TEXT` | `hasText(dom: ValueDom): Boolean` | Checks if the element has any text content. |
| `DOM_TEXT` | `text(dom: ValueDom, truncate: Int = MAX_VALUE): String` | Returns the element's full text content, optionally truncated. |
| `DOM_TEXT_LEN` | `textLen(dom: ValueDom): Int` | Returns the length of the element's text. |
| `DOM_TEXT_LENGTH` | `textLength(dom: ValueDom): Int` | Returns the length of the element's text (alias). |
| `DOM_OWN_TEXT` | `ownText(dom: ValueDom): String` | Returns the element's own text (excluding child element text). |
| `DOM_OWN_TEXTS` | `ownTexts(dom: ValueDom): ValueArray` | Returns own texts of the element and its children as an array. |
| `DOM_OWN_TEXT_LEN` | `ownTextLen(dom: ValueDom): Int` | Returns the length of the element's own text. |
| `DOM_WHOLE_TEXT` | `wholeText(dom: ValueDom): String` | Returns the whole text of the element (including child text nodes). |
| `DOM_WHOLE_TEXT_LEN` | `wholeTextLen(dom: ValueDom): Int` | Returns the length of the element's whole text. |

### HTML Serialization

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_DOM` | `dom(dom: ValueDom): ValueDom` | Identity function — returns the DOM as-is. |
| `DOM_HTML` | `html(dom: ValueDom): String` | Returns the element's inner HTML (slim copy). |
| `DOM_OUTER_HTML` | `outerHtml(dom: ValueDom): String` | Returns the element's outer HTML (slim copy). |
| `DOM_SLIM_HTML` | `slimHtml(dom: ValueDom): String` | Returns a slimmed-down version of the element's HTML. |
| `DOM_MINIMAL_HTML` | `minimalHtml(dom: ValueDom): String` | Returns a minimal version of the element's HTML. |

### Regex Extraction on DOM Text

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_RE1` | `re1(dom: ValueDom, regex: String): String` | Extracts the first regex group from the element's text. |
| `DOM_RE1` | `re1(dom: ValueDom, regex: String, group: Int): String` | Extracts the nth regex group from the element's text. |
| `DOM_RE2` | `re2(dom: ValueDom, regex: String): ValueArray` | Extracts a key-value pair (groups 1,2) from the element's text. |
| `DOM_RE2` | `re2(dom: ValueDom, regex: String, keyGroup: Int, valueGroup: Int): ValueArray` | Extracts a key-value pair with custom group indices from the element's text. |

### Computed Features (Shorthand Abbreviations)

These functions return `Double` values for common DOM features. They are convenient abbreviations of `feature(dom, FEATURE_KEY)`.

| Alias | Full Feature | Description |
|-------|-------------|-------------|
| `DOM_CH` | CH | Character count (text length). |
| `DOM_TN` | TN | Text node count. |
| `DOM_IMG` | IMG | Image count. |
| `DOM_A` | A | Anchor (link) count. |
| `DOM_SIB` | SIB | Sibling count. |
| `DOM_C` | C | Child count. |
| `DOM_DEP` | DEP | Depth in the DOM tree. |
| `DOM_SEQ` | SEQ | Sequence number. |
| `DOM_TOP` | TOP | Y-coordinate of the element's bounding box. |
| `DOM_LEFT` | LEFT | X-coordinate of the element's bounding box. |
| `DOM_WIDTH` | WIDTH | Width of the element's bounding box (minimum 1.0). |
| `DOM_HEIGHT` | HEIGHT | Height of the element's bounding box (minimum 1.0). |
| `DOM_AREA` | — | `width × height` — the element's bounding box area. |
| `DOM_ASPECT_RATIO` | — | `width / height` — the element's aspect ratio. |

---

## 10. DOM — Inline Select Functions

**File:** `DomInlineSelectFunctions.kt`
**Namespace:** `DOM`

These functions select elements and return arrays (ValueArray), suitable for inline use in SQL expressions (as opposed to table-returning functions).

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_INLINE_SELECT` | `inlineSelect(dom: ValueDom, cssQuery: String): ValueArray` | Selects all elements matching the CSS query and returns them as an array of DOMs. |
| `DOM_INLINE_SELECT` | `inlineSelect(dom: ValueDom, cssQuery: String, offset: Int, limit: Int): ValueArray` | Same as above with offset/limit. |
| `DOM_INLINE_SELECT_TEXT` | `inlineSelectText(dom: ValueDom, cssQuery: String): ValueArray` | Selects all elements and returns their text content as an array (uses default offset=1, limit=40). |
| `DOM_INLINE_SELECT_TEXT` | `inlineSelectText(dom: ValueDom, cssQuery: String, offset: Int, limit: Int): ValueArray` | Same as above with explicit offset/limit. |

---

## 11. DOM — Select Functions

**File:** `DomSelectFunctions.kt`
**Namespace:** `DOM`

A large collection of CSS-selector-based data extraction functions. Each conceptual group has `all*`, `first*`, and `nth*` variants.

### Element Selection

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_SELECT_ALL` | `selectAll(dom: ValueDom, cssQuery: String): ValueArray` | Selects all matching elements and returns them as a ValueArray of DOMs. |
| `DOM_SELECT_FIRST` | `selectFirst(dom: ValueDom, cssQuery: String): ValueDom` | Selects the first matching element and returns it as a DOM. |
| `DOM_SELECT_NTH` | `selectNth(dom: ValueDom, cssQuery: String, n: Int): ValueDom` | Selects the nth matching element (1-based) and returns it as a DOM. |

### Text Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_TEXTS` | `allTexts(dom: ValueDom, cssQuery: String): ValueArray` | Returns the text of all matched elements as an array. |
| `DOM_FIRST_TEXT` | `firstText(dom: ValueDom, cssQuery: String): String` | Returns the text of the first matched element. |
| `DOM_NTH_TEXT` | `nthText(dom: ValueDom, cssQuery: String, n: Int): String` | Returns the text of the nth matched element. |

### Own Text Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_OWN_TEXTS` | `allOwnTexts(dom: ValueDom, cssQuery: String): ValueArray` | Returns the own text of all matched elements. |
| `DOM_FIRST_OWN_TEXT` | `firstOwnText(dom: ValueDom, cssQuery: String): String` | Returns the own text of the first matched element. |
| `DOM_NTH_OWN_TEXT` | `nthOwnText(dom: ValueDom, cssQuery: String, n: Int): String` | Returns the own text of the nth matched element. |

### Whole Text Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_WHOLE_TEXTS` | `wholeTexts(dom: ValueDom, cssQuery: String): ValueArray` | Returns the whole text of all matched elements. |
| `DOM_FIRST_WHOLE_TEXT` | `firstWholeText(dom: ValueDom, cssQuery: String): String` | Returns the whole text of the first matched element. |
| `DOM_NTH_WHOLE_TEXT` | `nthWholeText(dom: ValueDom, cssQuery: String, n: Int): String` | Returns the whole text of the nth matched element. |

### HTML Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_SLIM_HTMLS` | `allSlimHtmls(dom: ValueDom, cssQuery: String): ValueArray` | Returns the slim HTML of all matched elements. |
| `DOM_FIRST_SLIM_HTML` | `firstSlimHtml(dom: ValueDom, cssQuery: String): String` | Returns the slim HTML of the first matched element. |
| `DOM_NTH_SLIM_HTML` | `nthSlimHtml(dom: ValueDom, cssQuery: String, n: Int): String` | Returns the slim HTML of the nth matched element. |
| `DOM_ALL_MINIMAL_HTMLS` | `allMinimalHtmls(dom: ValueDom, cssQuery: String): ValueArray` | Returns the minimal HTML of all matched elements. |
| `DOM_FIRST_MINIMAL_HTML` | `firstMinimalHtml(dom: ValueDom, cssQuery: String): String` | Returns the minimal HTML of the first matched element. |
| `DOM_NTH_MINIMAL_HTML` | `nthMinimalHtml(dom: ValueDom, cssQuery: String, n: Int): String` | Returns the minimal HTML of the nth matched element. |

### Number Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_INTEGERS` | `allIntegers(dom: ValueDom, cssQuery: String, defaultValue: Int = 0): ValueArray` | Extracts integers from the text of all matched elements. Falls back to defaultValue on parse failure. |
| `DOM_FIRST_INTEGER` | `firstInteger(dom: ValueDom, cssQuery: String, defaultValue: Int = 0): Int` | Extracts the first integer from the first matched element's text. |
| `DOM_NTH_INTEGER` | `nthInteger(dom: ValueDom, cssQuery: String, n: Int, defaultValue: Int = 0): Int` | Extracts the first integer from the nth matched element's text. |
| `DOM_ALL_FLOATS` | `allFloats(dom: ValueDom, cssQuery: String, defaultValue: Float = 0.0): ValueArray` | Extracts floats from the text of all matched elements. |
| `DOM_FIRST_FLOAT` | `firstFloat(dom: ValueDom, cssQuery: String, defaultValue: Float = 0.0): ValueFloat` | Extracts the first float from the first matched element's text. |
| `DOM_NTH_FLOAT` | `nthFloat(dom: ValueDom, cssQuery: String, n: Int, defaultValue: Float = 0.0): ValueFloat` | Extracts the first float from the nth matched element's text. |

### Attribute Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_ATTRS` | `allAttrs(dom: ValueDom, cssQuery: String = ":root", attrName: String): ValueArray` | Returns the named attribute value from all matched elements. |
| `DOM_FIRST_ATTR` | `firstAttr(dom: ValueDom, cssQuery: String = ":root", attrName: String): String` | Returns the named attribute from the first matched element. |
| `DOM_NTH_ATTR` | `nthAttr(dom: ValueDom, cssQuery: String, n: Int, attrName: String): String` | Returns the named attribute from the nth matched element. |
| `DOM_ALL_MULTI_ATTRS` | `allMultiAttrs(dom: ValueDom, cssQuery: String = ":root", attrNames: Array<String>): ValueArray` | Returns multiple attribute values from all matched elements. |
| `DOM_FIRST_MULTI_ATTRS` | `firstMultiAttrs(dom: ValueDom, cssQuery: String = ":root", attrNames: Array<String>): List<String>` | Returns multiple attribute values from the first matched element. |
| `DOM_NTH_MULTI_ATTRS` | `nthMultiAttrs(dom: ValueDom, cssQuery: String, n: Int, attrNames: Array<String>): List<String>` | Returns multiple attribute values from the nth matched element. |

### Image Source Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_IMGS` | `allImgs(dom: ValueDom, cssQuery: String = ":root"): ValueArray` | Returns the `abs:src` of all `<img>` elements (auto-appends `img` to the CSS query). |
| `DOM_FIRST_IMG` | `firstImg(dom: ValueDom, cssQuery: String = ":root"): String` | Returns the `abs:src` of the first `<img>` element. |
| `DOM_NTH_IMG` | `nthImg(dom: ValueDom, cssQuery: String, n: Int): String` | Returns the `abs:src` of the nth `<img>` element. |

### Link (Href) Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_HREFS` | `allHrefs(dom: ValueDom, cssQuery: String = ":root"): ValueArray` | Returns the `abs:href` of all `<a>` elements (auto-appends `a` to the CSS query). |
| `DOM_FIRST_HREF` | `firstHref(dom: ValueDom, cssQuery: String = ":root"): String` | Returns the `abs:href` of the first `<a>` element. |
| `DOM_NTH_HREF` | `nthHref(dom: ValueDom, cssQuery: String, n: Int): String` | Returns the `abs:href` of the nth `<a>` element. |

### Node Labels

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_NODES_LABELS` | `allNodesLabels(dom: ValueDom, cssQuery: String = ":root"): ValueArray` | Returns the `A_LABELS` attribute from all matched elements. |
| `DOM_FIRST_NODE_LABELS` | `firstNodeLabels(dom: ValueDom, cssQuery: String = ":root"): String` | Returns the `A_LABELS` attribute from the first matched element. |
| `DOM_NTH_NODE_LABELS` | `nthNodeLabels(dom: ValueDom, cssQuery: String, n: Int): String` | Returns the `A_LABELS` attribute from the nth matched element. |

### Regex Extraction with CSS Selectors

| Alias | Signature | Description |
|-------|-----------|-------------|
| `DOM_ALL_RE1` | `allRe1(dom: ValueDom, regex: String): ValueArray` | Extracts the first regex group from all elements (scoped to `:root`). |
| `DOM_ALL_RE1` | `allRe1(dom: ValueDom, cssQuery: String, regex: String): ValueArray` | Extracts the first regex group from all matched elements. |
| `DOM_FIRST_RE1` | `firstRe1(dom: ValueDom, regex: String): String` | Extracts the first regex group from the root element's text. |
| `DOM_FIRST_RE1` | `firstRe1(dom: ValueDom, cssQuery: String, regex: String): String` | Extracts the first regex group from the first matched element's text. |
| `DOM_FIRST_RE1` | `firstRe1(dom: ValueDom, cssQuery: String, regex: String, group: Int): String` | Extracts the nth regex group from the first matched element's text. |
| `DOM_ALL_RE2` | `allRe2(dom: ValueDom, regex: String): ValueArray` | Extracts key-value pairs via regex from all elements (scoped to `:root`). |
| `DOM_ALL_RE2` | `allRe2(dom: ValueDom, cssQuery: String, regex: String): ValueArray` | Extracts key-value pairs via regex from all matched elements. |
| `DOM_FIRST_RE2` | `firstRe2(dom: ValueDom, cssQuery: String, regex: String): ValueArray` | Extracts a key-value pair from the first matched element's text. |
| `DOM_FIRST_RE2` | `firstRe2(dom: ValueDom, cssQuery: String, regex: String, keyGroup: Int, valueGroup: Int): ValueArray` | Extracts a key-value pair from the first matched element's text with custom group indices. |
| `DOM_ALL_RE2` | `allRe2(dom: ValueDom, cssQuery: String, regex: String, keyGroup: Int, valueGroup: Int): ValueArray` | Extracts key-value pairs from all matched elements with custom group indices. |

---

## 12. LLM — Large Language Model Functions

**File:** `LLMFunctions.kt`
**Namespace:** `LLM`

These functions integrate with LLM (AI) models for chat and structured data extraction.

| Alias | Signature | Description |
|-------|-----------|-------------|
| `LLM_MODEL_NAME` | `modelName(): String` | Returns the configured LLM model name. |
| `LLM_CHAT` | `chat(prompt: String): String` | Sends a prompt to the LLM and returns the response. Handles interruption gracefully (returns empty string). |
| `LLM_CHAT` | `chat(dom: ValueDom, prompt: String): String` | Sends a DOM element and prompt to the LLM and returns the response. |
| `LLM_EXTRACT` | `extract(dom: ValueDom, dataExtractionRules: String): ValueStringJSON` | Extracts structured fields from DOM content using the LLM. The `dataExtractionRules` describe the fields to extract as a JSON object. Handles interruption and JSON parsing failures gracefully. |

### LLM Extract Usage Example

The `extract` function uses the following prompt template internally:

```json
{
  "field1": "value1",
  "field2": "value2"
}
```

`dataExtractionRules` describes what fields to extract. If a field cannot be found, its value is set to `null`.

---

## 13. META — Metadata Table Functions

**File:** `MetadataFunctionTables.kt`
**Namespace:** `META`

Return page metadata as `ResultSet` key-value pairs.

| Alias | Signature | Description |
|-------|-----------|-------------|
| `META_LOAD` | `load(configuredUrl: String): ResultSet` | Loads a page from the database and returns its metadata fields as key-value pairs. |
| `META_FETCH` | `fetch(configuredUrl: String): ResultSet` | Fetches a page from the web (force, sets expiry to zero) and returns its metadata fields as key-value pairs. |

---

## 14. META — Metadata Scalar Functions

**File:** `MetadataFunctions.kt`
**Namespace:** `META`

| Alias | Signature | Description |
|-------|-----------|-------------|
| `META_GET` | `get(url: String): String` | Loads a page from the database and returns it as a formatted string (via `WebPageFormatter`). |

---

## 15. STR — String Functions

**File:** `StringFunctions.kt`
**Namespace:** `STR`

A comprehensive string manipulation library that delegates to Apache Commons `StringUtils` and `Strings`. All functions are null-safe.

### Case Manipulation

| Alias | Signature |
|-------|-----------|
| `STR_CAPITALIZE` | `capitalize(str: String?): String?` |
| `STR_UNCAPITALIZE` | `uncapitalize(str: String?): String?` |
| `STR_SWAP_CASE` | `swapCase(str: String?): String?` |
| `STR_UPPER_CASE` | `upperCase(str: String?): String?` |
| `STR_UPPER_CASE` | `upperCase(str: String?, locale: Locale): String?` |
| `STR_LOWER_CASE` | `lowerCase(str: String?): String?` |
| `STR_LOWER_CASE` | `lowerCase(str: String?, locale: Locale): String?` |

### Empty / Blank Checks

| Alias | Signature | Returns |
|-------|-----------|---------|
| `STR_IS_EMPTY` | `isEmpty(str: String?): Boolean` | `true` if null or empty |
| `STR_IS_NOT_EMPTY` | `isNotEmpty(str: String?): Boolean` | `true` if not null and not empty |
| `STR_IS_BLANK` | `isBlank(str: String?): Boolean` | `true` if null, empty, or whitespace only |
| `STR_IS_NOT_BLANK` | `isNotBlank(str: String?): Boolean` | inverse of `isBlank` |
| `STR_IS_ANY_EMPTY` | `isAnyEmpty(str: Array<String>): Boolean` | `true` if any element is empty |
| `STR_IS_NONE_EMPTY` | `isNoneEmpty(str: Array<String>): Boolean` | `true` if no element is empty |
| `STR_IS_ANY_BLANK` | `isAnyBlank(str: Array<String>): Boolean` | `true` if any element is blank |
| `STR_IS_NONE_BLANK` | `isNoneBlank(str: Array<String>): Boolean` | `true` if no element is blank |

### Trimming / Stripping

| Alias | Signature |
|-------|-----------|
| `STR_TRIM` | `trim(str: String?): String?` |
| `STR_TRIM_TO_NULL` | `trimToNull(str: String?): String?` |
| `STR_TRIM_TO_EMPTY` | `trimToEmpty(str: String?): String?` |
| `STR_STRIP` | `strip(str: String?): String?` |
| `STR_STRIP` | `strip(str: String?, stripChars: String): String?` |
| `STR_STRIP_TO_NULL` | `stripToNull(str: String?): String?` |
| `STR_STRIP_TO_EMPTY` | `stripToEmpty(str: String?): String?` |
| `STR_STRIP_START` | `stripStart(str: String?, stripChars: String): String?` |
| `STR_STRIP_END` | `stripEnd(str: String?, stripChars: String): String?` |
| `STR_STRIP_ALL` | `stripAll(str: Array<String>): Array<String>` |
| `STR_STRIP_ALL` | `stripAll(str: Array<String>, stripChars: String): Array<String>` |
| `STR_STRIP_ACCENTS` | `stripAccents(str: String?): String?` |

### Substring Extraction

| Alias | Signature |
|-------|-----------|
| `STR_SUBSTRING` | `substring(str: String?, start: Int): String?` |
| `STR_SUBSTRING` | `substring(str: String?, start: Int, end: Int): String?` |
| `STR_LEFT` | `left(str: String?, len: Int): String?` |
| `STR_RIGHT` | `right(str: String?, len: Int): String?` |
| `STR_MID` | `mid(str: String?, pos: Int, len: Int): String?` |
| `STR_SUBSTRING_BEFORE` | `substringBefore(str: String?, separator: String): String?` |
| `STR_SUBSTRING_AFTER` | `substringAfter(str: String?, separator: String): String?` |
| `STR_SUBSTRING_BEFORE_LAST` | `substringBeforeLast(str: String?, separator: String): String?` |
| `STR_SUBSTRING_AFTER_LAST` | `substringAfterLast(str: String?, separator: String): String?` |
| `STR_SUBSTRING_BETWEEN` | `substringBetween(str: String?, tag: String): String?` |
| `STR_SUBSTRING_BETWEEN` | `substringBetween(str: String?, open: String, close: String): String?` |
| `STR_SUBSTRINGS_BETWEEN` | `substringsBetween(str: String?, open: String, close: String): Array<String>` |

### Search / Contains

| Alias | Signature |
|-------|-----------|
| `STR_CONTAINS22` | `contains22(str: String?, searchChar: Int): Boolean` |
| `STR_CONTAINS_WHITESPACE` | `containsWhitespace(str: String?): Boolean` |
| `STR_CONTAINS_ANY` | `containsAny(str: String?, searchChars: String): Boolean` |
| `STR_CONTAINS_ONLY` | `containsOnly(str: String?, validChars: String): Boolean` |
| `STR_CONTAINS_NONE` | `containsNone(str: String?, invalidChars: String): Boolean` |
| `STR_INDEX_OF_ANY` | `indexOfAny(str: String?, searchChars: String): Int` |
| `STR_INDEX_OF_ANY_BUT` | `indexOfAnyBut(str: String?, searchChars: String): Int` |
| `STR_ORDINAL_INDEX_OF` | `ordinalIndexOf(str: String?, searchStr: String, ordinal: Int): Int` |
| `STR_LAST_ORDINAL_INDEX_OF` | `lastOrdinalIndexOf(str: String?, searchStr: String, ordinal: Int): Int` |
| `STR_INDEX_OF_DIFFERENCE` | `indexOfDifference(strs: Array<String>): Int` |
| `STR_INDEX_OF_DIFFERENCE` | `indexOfDifference(str: String?, other: String): Int` |
| `STR_COUNT_MATCHES` | `countMatches(str: String?, sub: String): Int` |
| `STR_GET_COMMON_PREFIX` | `getCommonPrefix(strs: Array<String>): String?` |

### Splitting / Joining

| Alias | Signature |
|-------|-----------|
| `STR_SPLIT` | `split(str: String?): Array<String>` |
| `STR_SPLIT` | `split(str: String?, separatorChars: String): Array<String>` |
| `STR_SPLIT` | `split(str: String?, separatorChars: String, max: Int): Array<String>` |
| `STR_SPLIT22` | `split22(str: String?, separatorChar: Char): Array<String>` |
| `STR_SPLIT_BY_WHOLE_SEPARATOR` | `splitByWholeSeparator(str: String?, separator: String): Array<String>` |
| `STR_SPLIT_BY_WHOLE_SEPARATOR` | `splitByWholeSeparator(str: String?, separator: String, max: Int): Array<String>` |
| `STR_SPLIT_BY_WHOLE_SEPARATOR_PRESERVE_ALL_TOKENS` | `splitByWholeSeparatorPreserveAllTokens(str: String?, separator: String): Array<String>` |
| `STR_SPLIT_BY_WHOLE_SEPARATOR_PRESERVE_ALL_TOKENS` | `splitByWholeSeparatorPreserveAllTokens(str: String?, separator: String, max: Int): Array<String>` |
| `STR_SPLIT_PRESERVE_ALL_TOKENS` | `splitPreserveAllTokens(str: String?): Array<String>` |
| `STR_SPLIT_PRESERVE_ALL_TOKENS` | `splitPreserveAllTokens(str: String?, separatorChars: String): Array<String>` |
| `STR_SPLIT_PRESERVE_ALL_TOKENS2` | `splitPreserveAllTokens2(str: String?, separatorChar: Char): Array<String>` |
| `STR_SPLIT_PRESERVE_ALL_TOKENS` | `splitPreserveAllTokens(str: String?, separatorChars: String, max: Int): Array<String>` |
| `STR_SPLIT_BY_CHARACTER_TYPE` | `splitByCharacterType(str: String?): Array<String>` |
| `STR_SPLIT_BY_CHARACTER_TYPE_CAMEL_CASE` | `splitByCharacterTypeCamelCase(str: String?): Array<String>` |
| `STR_JOIN` | `join(array: Array<String>): String?` |
| `STR_JOIN` | `join(array: Array<String>, separator: String): String?` |

### Replace / Remove

| Alias | Signature |
|-------|-----------|
| `STR_REPLACE_EACH` | `replaceEach(str: String?, searchList: Array<String>, replacementList: Array<String>): String?` |
| `STR_REPLACE_EACH_REPEATEDLY` | `replaceEachRepeatedly(str: String?, searchList: Array<String>, replacementList: Array<String>): String?` |
| `STR_REPLACE_CHARS2` | `replaceChars2(str: String?, searchChar: Char, replaceChar: Char): String?` |
| `STR_REPLACE_CHARS` | `replaceChars(str: String?, searchChars: String, replaceChars: String): String?` |
| `STR_OVERLAY` | `overlay(str: String?, overlay: String, start: Int, end: Int): String?` |
| `STR_DELETE_WHITESPACE` | `deleteWhitespace(str: String?): String?` |
| `STR_CHOMP` | `chomp(str: String?): String?` |
| `STR_CHOP` | `chop(str: String?): String?` |
| `STR_NORMALIZE_SPACE` | `normalizeSpace(str: String?): String?` |

### Padding

| Alias | Signature |
|-------|-----------|
| `STR_LEFT_PAD` | `leftPad(str: String?, size: Int): String?` |
| `STR_LEFT_PAD` | `leftPad(str: String?, size: Int, padStr: String): String?` |
| `STR_LEFT_PAD2` | `leftPad2(str: String?, size: Int, padChar: Char): String?` |
| `STR_RIGHT_PAD` | `rightPad(str: String?, size: Int): String?` |
| `STR_RIGHT_PAD` | `rightPad(str: String?, size: Int, padStr: String): String?` |
| `STR_RIGHT_PAD2` | `rightPad2(str: String?, size: Int, padChar: Char): String?` |
| `STR_CENTER` | `center(str: String?, size: Int): String?` |
| `STR_CENTER` | `center(str: String?, size: Int, padStr: String): String?` |
| `STR_CENTER2` | `center2(str: String?, size: Int, padChar: Char): String?` |

### Other String Utilities

| Alias | Signature | Description |
|-------|-----------|-------------|
| `STR_REPEAT` | `repeat(str: String?, repeat: Int): String?` | Repeats the string n times. |
| `STR_REPEAT` | `repeat(str: String?, separator: String, repeat: Int): String?` | Repeats the string n times with a separator. |
| `STR_REVERSE` | `reverse(str: String?): String?` | Reverses the string. |
| `STR_REVERSE_DELIMITED` | `reverseDelimited(str: String?, delimiter: Char): String?` | Reverses a delimited string. |
| `STR_DIFFERENCE` | `difference(str: String?, other: String): String?` | Returns the difference between two strings. |
| `STR_LENGTH` | `length(str: String?): Int` | Returns the string length (null-safe, returns 0 for null). |
| `STR_ABBREVIATE` | `abbreviate(str: String?, maxWidth: Int): String?` | Abbreviates using ellipsis. |
| `STR_ABBREVIATE` | `abbreviate(str: String?, offset: Int, maxWidth: Int): String?` | Abbreviates with offset. |
| `STR_ABBREVIATE_MIDDLE` | `abbreviateMiddle(str: String?, middle: String, maxWidth: Int): String?` | Abbreviates with a middle marker. |
| `STR_DEFAULT_STRING` | `defaultString(str: String?): String?` | Returns the string or an empty string if null. |
| `STR_DEFAULT_IF_BLANK` | `defaultIfBlank(str: String?, defaultStr: String): String?` | Returns default if blank. |
| `STR_DEFAULT_IF_EMPTY` | `defaultIfEmpty(str: String?, defaultStr: String): String?` | Returns default if empty. |
| `STR_TO_ENCODED_STRING` | `toEncodedString(bytes: ByteArray, charset: Charset): String?` | Converts bytes to a string using the given charset. |

### Character Classification

| Alias | Signature |
|-------|-----------|
| `STR_IS_ALPHA` | `isAlpha(str: String?): Boolean` |
| `STR_IS_NUMERIC` | `isNumeric(str: String?): Boolean` |
| `STR_IS_WHITESPACE` | `isWhitespace(str: String?): Boolean` |
| `STR_IS_ALPHA_SPACE` | `isAlphaSpace(str: String?): Boolean` |
| `STR_IS_ALPHANUMERIC` | `isAlphanumeric(str: String?): Boolean` |
| `STR_IS_ALPHANUMERIC_SPACE` | `isAlphanumericSpace(str: String?): Boolean` |
| `STR_IS_ASCII_PRINTABLE` | `isAsciiPrintable(str: String?): Boolean` |
| `STR_IS_NUMERIC_SPACE` | `isNumericSpace(str: String?): Boolean` |
| `STR_IS_ALL_LOWER_CASE` | `isAllLowerCase(str: String?): Boolean` |
| `STR_IS_ALL_UPPER_CASE` | `isAllUpperCase(str: String?): Boolean` |

### Number Extraction

| Alias | Signature | Description |
|-------|-----------|-------------|
| `STR_FIRST_INTEGER` | `firstInteger(str: String?, defaultValue: Int): Int` | Extracts the first integer from the string. |
| `STR_FIRST_FLOAT` | `firstFloat(str: String?, defaultValue: Float): Float` | Extracts the first float from the string. |
| `STR_GET_FIRST_FLOAT_NUMBER` | `getFirstFloatNumber(str: String?, defaultValue: Float): Float` | Extracts the first float number from the string. |

---

## Function Alias Naming Convention

Function aliases are auto-generated from camelCase Kotlin method names:

1. Split on uppercase letters: `firstText` → `["first", "Text"]`
2. Join with `_`: `first_Text`
3. Uppercase: `FIRST_TEXT`
4. If a namespace exists, prefix it: `DOM_FIRST_TEXT`

Arbitrary underscores in alias names are ignored when resolving, so `SOME_FUN_()`, `_____SOME_FUN_()`, and `SOME______FUN()` all resolve to the same function.

## Usage Patterns

### Table-Returning Functions (ResultSet)

Use with SQL `SELECT` or `CALL`:

```sql
-- Show help
SELECT * FROM XSQL_HELP();

-- Load options reference
SELECT * FROM LOAD_OPTIONS();

-- Explode an array into rows
SELECT * FROM EXPLODE(MAKE_ARRAY('a', 'b', 'c'));

-- Load and extract
SELECT * FROM DOM_LOAD_AND_SELECT('https://example.com', 'h1');
```

### Scalar Functions

Use inline in SQL expressions:

```sql
-- Load a page and extract text
SELECT DOM_FIRST_TEXT(DOM_LOAD('https://example.com'), 'h1');

-- String manipulation
SELECT STR_UPPER_CASE(STR_TRIM('  hello  '));

-- Regex extraction
SELECT STR_RE1('price: $99.99', '(\d+\.\d+)');
```

### LLM Integration

```sql
-- Chat with the model
SELECT LLM_CHAT('What is the capital of France?');

-- Extract structured data from a page
SELECT LLM_EXTRACT(
    DOM_LOAD('https://example.com/product'),
    '{"name": "product name", "price": "product price", "description": "product description"}'
);
```

---

## Summary by Namespace

| Namespace | File(s) | Function Count | Purpose |
|-----------|---------|---------------|---------|
| `ADMIN` | `AdminFunctions.kt` | 6 | Session management, echo, save |
| `ARRAY` | `ArrayFunctions.kt` | 3 | Array manipulation |
| `IN_BOX` | `BoxFunctions.kt` | 14 | CSS box selector shorthand |
| `DOM` | `ChatFunctions.kt` | 1 | AI chat |
| *(none)* | `CommonFunctionTables.kt` | 10 | System helpers, explode/map, metrics |
| *(none)* | `CommonFunctions.kt` | 18 | Regex, arrays, JSON, timestamps |
| `TIME` | `DateTimeFunctions.kt` | 2 | Date/time parsing |
| `DOM` | `DomFunctionTables.kt` | 15 | Page loading, link extraction, features |
| `DOM` | `DomFunctions.kt` | ~65 | Element properties, text, navigation, features |
| `DOM` | `DomInlineSelectFunctions.kt` | 4 | CSS select returning arrays |
| `DOM` | `DomSelectFunctions.kt` | ~50 | CSS select with all/first/nth variants |
| `LLM` | `LLMFunctions.kt` | 4 | LLM chat and data extraction |
| `META` | `MetadataFunctionTables.kt` | 2 | Page metadata as key-value ResultSets |
| `META` | `MetadataFunctions.kt` | 1 | Formatted page metadata |
| `STR` | `StringFunctions.kt` | ~90 | String manipulation (Commons Lang) |

**Total: approximately 285 user-defined functions across 15 groups.**
