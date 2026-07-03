---
title: "PowerCSS — Visual Feature Selectors with :expr()"
description: "Reference for PowerCSS :expr() pseudo-selector. Query DOM elements by their computed visual features — size, position, and content density — in CSS selectors and X-SQL queries."
tier: catalog
---

# PowerCSS — Visual Feature Selectors with `:expr()`

> **Used in:** [X-SQL](x-sql.md) — `DOM_FIRST_TEXT(DOM, 'img:expr(width>400)')`, [HTML Snapshot](htmlsnapshot.md), [SKILL.md](../SKILL.md)
>
> **Underlying engine:** [jsoup](https://jsoup.org/) — parses HTML into the same DOM as modern browsers. See [jsoup selector-syntax](https://jsoup.org/cookbook/extracting-data/selector-syntax) and [CSS reference](https://www.w3schools.com/cssref/css_selectors.php).

---

## Why PowerCSS

Modern web pages change their HTML structure frequently, but their **visual layout** stays stable. PowerCSS extends standard CSS selectors with a `:expr()` pseudo-selector that queries elements by their **computed numerical features** — size, position, and content density. This makes selectors resilient to markup changes.

## Numerical Features

Browser4 computes these features for every DOM node:

| Feature | Description |
|---------|-------------|
| `top` | Top Y-coordinate of the element (pixels) |
| `left` | Left X-coordinate of the element (pixels) |
| `width` | Width of the element (pixels) |
| `height` | Height of the element (pixels) |
| `char` | Number of characters inside the node |
| `txt_nd` | Number of descendant text nodes |
| `img` | Number of descendant `<img>` elements |
| `a` | Number of descendant `<a>` elements |
| `sibling` | Number of sibling nodes |
| `child` | Number of child nodes |
| `dep` | Node depth in the document tree |
| `seq` | Node sequence in document order |
| `txt_dns` | Text node density |

These are usable in any CSS selector via `:expr(...)`, in X-SQL `DOM_*` functions, and in `htmlsnapshot get` / `htmlsnapshot query` commands.

---

## `:expr()` Pseudo-Selector

```
element:expr(expression)
```

Filters elements by a mathematical expression over their numerical features.

### Examples

```sql
-- Select images larger than 400×400 in X-SQL
SELECT DOM_FIRST_IMG(DOM, 'img:expr(width > 400 && height > 400)')
FROM DOM_LOAD_AND_SELECT('https://example.com', ':root')

-- Select product images that are large enough to be the main photo
SELECT DOM_FIRST_ATTR(DOM, 'img:expr(width > 400)', 'src') AS main_image
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/dp/B0CXJ1NT4B', ':root')

-- Select the right-column product card (left > 500px from the sidebar)
SELECT DOM_FIRST_TEXT(DOM, 'div:expr(left > 500 && width < 400)') AS sidebar
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div')

-- Select divs that contain exactly one image and are 400-500px square
SELECT DOM_FIRST_SLIM_HTML(DOM, 'div:expr(img == 1 && width > 400 && width < 500 && height > 400 && height < 500)')
FROM DOM_LOAD_AND_SELECT('https://example.com', 'body')

-- Fallback chain: try multiple image selectors, pick the first that works
SELECT DOM_FIRST_ATTR(DOM,
    '#landingImage, #imgTagWrapperId img, #imageBlock img:expr(width>400)',
    'data-old-hires'
) AS product_image
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/dp/B0CXJ1NT4B', ':root')
```

---

## Operators

### Arithmetic

| Operator | Description | Example |
|----------|-------------|---------|
| `+` | Addition (prefix/infix) | `width + height`, `+2` |
| `-` | Subtraction / negation | `width - 100`, `-2` |
| `*` | Multiplication | `width * height` |
| `/` | Division | `width / 2` |
| `^` | Power | `width ^ 2` |
| `%` | Modulo (remainder) | `seq % 2` |

### Boolean / Comparison

| Operator | Description | Example |
|----------|-------------|---------|
| `=`, `==` | Equals | `width == 500` |
| `!=`, `<>` | Not equals | `img != 0` |
| `>` | Greater than | `width > 400` |
| `>=` | Greater or equal | `char >= 100` |
| `<` | Less than | `left < 100` |
| `<=` | Less or equal | `dep <= 3` |
| `!` | Prefix NOT | `!a` (no links) |
| `&&` | AND | `width > 400 && height > 400` |
| `\|\|` | OR | `img > 0 \|\| a > 0` |

---

## Real-World Patterns

### E-commerce product image extraction

Amazon product pages have multiple image elements; the main product photo is consistently large:

```sql
SELECT
    DOM_FIRST_ATTR(DOM, '#imgTagWrapperId img:expr(width>400)', 'src') AS main_image,
    DOM_FIRST_ATTR(DOM, '#altImages img:expr(width<200)', 'src') AS thumbnail,
    DOM_FIRST_TEXT(DOM, '#priceblock_ourprice, .a-price:expr(width>100) .a-offscreen') AS price
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/dp/B0CXJ1NT4B', ':root')
```

### Filtering noisy elements by size

```sql
-- Skip tiny invisible elements; only select meaningful content blocks
SELECT DOM_TEXT(DOM)
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div:expr(width > 200 && height > 50 && char > 100)')
ORDER BY DOM_TEXT_LEN(DOM) DESC
```

### Detecting layout structure

```sql
-- Find the main content column (wide, lots of text, left-aligned near center)
SELECT DOM_CSS_SELECTOR(DOM) AS selector,
       DOM_TAG_NAME(DOM) AS tag,
       DOM_TEXT_LEN(DOM) AS chars
FROM DOM_LOAD_AND_SELECT('https://example.com', 'div:expr(width > 400 && left > 100 && char > 500)')
ORDER BY DOM_WIDTH(DOM) DESC
LIMIT 5
```

---

## CLI Usage

PowerCSS selectors work anywhere CSS selectors are accepted:

```bash
# htmlsnapshot get with :expr()
browser4-cli htmlsnapshot get all attr "img:expr(width>400)" src

# htmlsnapshot inspect with :expr()
browser4-cli htmlsnapshot inspect "div:expr(width>400 && width<500)"

# X-SQL query with :expr() in selectors
browser4-cli htmlsnapshot query . --sql "
SELECT DOM_FIRST_IMG(DOM, 'img:expr(width>400 && height>400)')
FROM DOM_LOAD_AND_SELECT('https://www.amazon.com/dp/B0CXJ1NT4B', ':root')
"
```
