---
title: "X-SQL: StringFunctions — String Manipulation"
description: "Reference for ~90 STR functions covering case manipulation, empty/blank checks, trimming/stripping, substrings, search/contains, splitting/joining, replace/remove, padding, classification, and number extraction."
tier: catalog
---

# X-SQL: StringFunctions — String Manipulation

> **Parent:** [x-sql.md](x-sql.md) — full function index and quick-reference patterns
>
> **Related:** [DomFunctions](x-sql-dom-functions.md) | [DomSelectFunctions](x-sql-dom-select-functions.md) | [ArrayFunctions](x-sql-array-functions.md)

**Source:** `StringFunctions.kt` | **Namespace:** `STR` | **~90 functions**

All functions are null-safe (delegate to Apache Commons `StringUtils`). A `null` input returns `null` or a sensible default depending on the return type.

> **SQL constraint:** All page-data queries must use `SELECT ... FROM DOM_LOAD_AND_SELECT(url, cssQuery)`. No CTEs, subqueries, `EXPLODE`, or other table sources are supported. Standalone `SELECT STR_*(...)` expressions (no FROM) are valid for string-only operations.

---

## Table of Contents

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

---

## 4.1 Case Manipulation

```sql
-- STR_CAPITALIZE: First character to uppercase
SELECT STR_CAPITALIZE('hello world');                    -- 'hello world'

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
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

## 4.2 Empty / Blank Checks

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

## 4.3 Trimming & Stripping

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
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body');
```

## 4.4 Substring Extraction

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

## 4.5 Search & Contains

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

## 4.6 Splitting & Joining

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
-- Split tags into an array result
SELECT STR_SPLIT(DOM_FIRST_TEXT(DOM, '.tags'), ',') AS tags
FROM DOM_LOAD_AND_SELECT('https://example.com', '.product');
```

## 4.7 Replace & Remove

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

## 4.8 Padding

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

## 4.9 Other String Utilities

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

## 4.10 Character Classification

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

## 4.11 Number Extraction

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
