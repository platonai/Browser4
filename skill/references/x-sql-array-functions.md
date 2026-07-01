---
title: "X-SQL: ArrayFunctions — Array Operations"
description: "Reference for ARRAY functions (ARRAY_JOIN_TO_STRING, ARRAY_FIRST_NOT_BLANK, ARRAY_FIRST_NOT_EMPTY). Fallback chains and array joining for X-SQL queries."
tier: catalog
---

# X-SQL: ArrayFunctions — Array Operations

> **Parent:** [x-sql.md](x-sql.md) — full function index and quick-reference patterns
>
> **Related:** [DomFunctions](x-sql-dom-functions.md) | [DomSelectFunctions](x-sql-dom-select-functions.md) | [StringFunctions](x-sql-string-functions.md)

**Source:** `ArrayFunctions.kt` | **Namespace:** `ARRAY` | **3 functions**

> **SQL constraint:** All queries must use `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)`. No CTEs, subqueries, `EXPLODE`, or other table sources are supported.

---

## ARRAY_JOIN_TO_STRING

```
ARRAY_JOIN_TO_STRING(values, separator)
```

Joins all elements of a `ValueArray` into a single string with the given separator.

```sql
-- Basic join (standalone expression)
SELECT ARRAY_JOIN_TO_STRING(MAKE_ARRAY('a', 'b', 'c'), ', ');
-- Result: 'a, b, c'

-- Join scraped items into a comma-separated list
SELECT ARRAY_JOIN_TO_STRING(
    DOM_ALL_TEXTS(DOM, 'ul.tags li'),
    ' | '
) AS tags
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- Join with newlines for readable output
SELECT ARRAY_JOIN_TO_STRING(
    DOM_ALL_TEXTS(DOM, 'ol.steps li'),
    '\n'
) AS steps
FROM DOM_LOAD_AND_SELECT('https://example.com/guide', 'body');
```

## ARRAY_FIRST_NOT_BLANK

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
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- Fallback chain for missing data
SELECT ARRAY_FIRST_NOT_BLANK(
    MAKE_ARRAY(
        DOM_FIRST_ATTR(DOM, 'img', 'alt'),
        DOM_FIRST_ATTR(DOM, 'img', 'title'),
        'No description'
    )
) AS image_description
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');

-- Simple standalone example (no page load needed)
SELECT ARRAY_FIRST_NOT_BLANK(
    MAKE_ARRAY(NULL, '', '  ', 'hello', 'world')
);
-- Result: 'hello'
```

## ARRAY_FIRST_NOT_EMPTY

```
ARRAY_FIRST_NOT_EMPTY(values)
```

Like `firstNotBlank` but only checks for non-empty (whitespace-only strings are still returned). Returns `null` if all values are empty.

```sql
-- Fallback chain for metadata
SELECT ARRAY_FIRST_NOT_EMPTY(
    MAKE_ARRAY(
        DOM_FIRST_ATTR(DOM, 'meta[name="author"]', 'content'),
        DOM_FIRST_ATTR(DOM, 'meta[name="publisher"]', 'content')
    )
) AS author
FROM DOM_LOAD_AND_SELECT('https://example.com/article', 'head');

-- First non-empty value wins
SELECT ARRAY_FIRST_NOT_EMPTY(MAKE_ARRAY('', '', 'found me', ''));
-- Result: 'found me'
```
