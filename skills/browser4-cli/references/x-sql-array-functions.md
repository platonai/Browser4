---
title: "X-SQL: ArrayFunctions — Array Operations"
description: "Reference for ARRAY functions (ARRAY_JOIN_TO_STRING, ARRAY_FIRST_NOT_BLANK, ARRAY_FIRST_NOT_EMPTY). Fallback chains and array joining for X-SQL queries."
tier: catalog
---

# X-SQL: ArrayFunctions — Array Operations

## Overview

> **Parent:** [x-sql.md](x-sql.md) — full function index and quick-reference patterns
>
> **Related:** [DomFunctions](x-sql-dom-functions.md) | [DomSelectFunctions](x-sql-dom-select-functions.md) | [StringFunctions](x-sql-string-functions.md)

**Source:** `ArrayFunctions.kt` | **Namespace:** `ARRAY` | **3 functions**

> **SQL constraint:** All queries must use `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)`. No CTEs, subqueries, `EXPLODE`, or other table sources are supported.

> **`MAKE_ARRAY` is not an `ARRAY_*` function:** it is the constructor that every
> fallback-chain example below depends on (`ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(...))`).
> It is documented in [x-sql.md → Array Constructors](#array-constructors-make_array);
> without it there is no way to build the candidate list those functions consume.

## Quick Index

| Function | Returns | Description |
|----------|---------|-------------|
| [ARRAY_JOIN_TO_STRING](#array_join_to_string) | string | Join all array elements with a separator |
| [ARRAY_FIRST_NOT_BLANK](#array_first_not_blank) | element | First element that is not blank (non-whitespace) |
| [ARRAY_FIRST_NOT_EMPTY](#array_first_not_empty) | element | First element that is not empty |
| [MAKE_ARRAY](#make_array) | array | Constructor for fallback chains — see [x-sql.md](x-sql.md) |

---

## MAKE_ARRAY

```
MAKE_ARRAY(value1, value2, ...)
```

Builds a `ValueArray` from its arguments. Every `ARRAY_FIRST_NOT_BLANK` /
`ARRAY_FIRST_NOT_EMPTY` fallback chain starts with `MAKE_ARRAY(...)`; it is the
documented way to construct the candidate list those functions scan. `null`
arguments are kept in the array and simply skipped by the `ARRAY_FIRST_*`
functions (a `DOM_FIRST_TEXT` that matches nothing evaluates to blank, so the
chain falls through to the next candidate).

```sql
-- Build an ordered fallback chain: subtitle → alt-title → h1
SELECT ARRAY_FIRST_NOT_BLANK(
    MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, '.subtitle'),
        DOM_FIRST_TEXT(DOM, '.alt-title'),
        DOM_FIRST_TEXT(DOM, 'h1')
    )
) AS best_title
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

Full signature and notes: [x-sql.md → Array Constructors (MAKE_ARRAY)](x-sql.md#array-constructors-make_array).

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
