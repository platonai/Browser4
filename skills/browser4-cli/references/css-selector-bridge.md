---
title: "CSS Selector Bridge — From Snapshot Refs to HTML Snapshot Queries"
description: "How to bridge between interactive snapshot refs and HTML snapshot CSS selectors. Three-tier approach for extracting CSS selectors without reading the full DOM."
tier: procedure
---

# CSS Selector Bridge — From Snapshot Refs to HTML Snapshot Queries

`browser4-cli` has two separate element-addressing systems. This document explains how to bridge between them — so you can discover element structure with the compact interactive `snapshot`, then extract structured data with `htmlsnapshot` — **without ever reading the full HTML snapshot text**.

## The Two Systems

| System | Command | Addressing | Purpose |
|--------|---------|------------|---------|
| Interactive snapshot | `browser4-cli snapshot` | `@e5`, `@e15` refs | `click`, `type`, `fill` |
| Static HTML snapshot | `browser4-cli htmlsnapshot get` | CSS selectors only | Data extraction (`text`, `html`, `attr`) |

**The gap:** `htmlsnapshot get` rejects element refs (`e5`, `backend:15`). It only accepts CSS selectors. But the interactive snapshot output is compact (~200-400 tokens) while the full HTML snapshot can be hundreds of megabytes. You need a way to get CSS selectors without reading the full snapshot.

## Core Principle

> **Never read the full HTML snapshot text directly.** Always use targeted extraction commands.

| ❌ NEVER | ✅ INSTEAD |
|----------|------------|
| `browser4-cli htmlsnapshot get html ":root"` (entire page HTML) | `browser4-cli htmlsnapshot get text "selector"` (single element) |
| `cat htmlsnapshot-export.html` | `browser4-cli htmlsnapshot get text "selector"` |
| `grep` through an exported snapshot file | `browser4-cli htmlsnapshot query --sql "..."` |
| `browser4-cli htmlsnapshot export` then `cat` | `browser4-cli htmlsnapshot get html "form"` (scoped to one element) |
| Dump entire page HTML into agent context | Use `htmlsnapshot get text` to pull only the fields you need |

## Three-Tier Approach

Choose the tier that fits your situation, from simplest to most robust:

---

### Tier 1: Construct Selector from Snapshot Info

**Best for:** Most cases. The interactive snapshot already shows enough to build a working selector.

The interactive `snapshot` output shows each element's tag, key attributes, and visible text:

```
@e3  [input type="email"] placeholder="Email"
@e4  [input type="password"] placeholder="Password"
@e5  [button type="submit"] "Log In"
@e15 [a href="/products"] "View Products"
@e20 [div class="price"] "$19.99"
```

**Construct a CSS selector directly from what you see:**

| Snapshot shows | CSS selector you write |
|----------------|----------------------|
| `[input type="email"] placeholder="Email"` | `[placeholder="Email"]` or `input[type="email"]` |
| `[button type="submit"] "Log In"` | `button[type="submit"]` |
| `[a href="/products"] "View Products"` | `a[href="/products"]` |
| `[div class="price"] "$19.99"` | `.price` |
| `[h1] "Welcome"` | `h1` (check if unique on page) |

**Then use it with `htmlsnapshot get`:**

```bash
# Extract the current price from a product page
browser4-cli goto "https://shop.example.com/product/42"
browser4-cli snapshot                   # see @e20 [div class="price"] "$19.99"
browser4-cli htmlsnapshot get text ".price"
# → "$19.99"

# Verify a form field exists and get its current value
browser4-cli htmlsnapshot get attr "[name=\"email\"]" value
# → "user@example.com"
```

**When Tier 1 is enough:**
- The element has a distinctive attribute (`id`, `name`, `placeholder`, `data-*`)
- A class name is visible in the snapshot and likely unique to the target
- You only need the first match (no disambiguation needed)

---

### Tier 2: Extract Identifying Attributes from the Ref

**Best for:** When the snapshot doesn't show enough attributes, or you need to confirm an element's `id`/`class`/`data-*` values before building a selector.

Use `browser4-cli get attr <ref> <name>` to pull specific attributes from the interactive element:

```bash
# Discover identifying attributes from a snapshot ref
ELEM_ID=$(browser4-cli get attr e5 id)
ELEM_CLASS=$(browser4-cli get attr e5 class)
ELEM_NAME=$(browser4-cli get attr e5 name)
ELEM_DATA_ID=$(browser4-cli get attr e5 data-testid)

# Now construct a precise selector
browser4-cli htmlsnapshot get text "#${ELEM_ID}"
# or
browser4-cli htmlsnapshot get text ".${ELEM_CLASS}"
# or for React/Vue testing attributes
browser4-cli htmlsnapshot get text "[data-testid=\"${ELEM_DATA_ID}\"]"
```

**Complete example — extract product details:**

```bash
# 1. Open page and get refs
browser4-cli goto "https://shop.example.com/product/42"
browser4-cli snapshot
# @e15 [div] "$29.99"
# @e16 [h1] "Widget Pro"
# @e17 [span] "In Stock"

# 2. Extract class names to build precise selectors
PRICE_CLASS=$(browser4-cli get attr e15 class)    # → "product-price"
TITLE_CLASS=$(browser4-cli get attr e16 class)    # → "product-title"

# 3. Use those selectors with htmlsnapshot for structured extraction
PRICE=$(browser4-cli htmlsnapshot get text ".${PRICE_CLASS}")
TITLE=$(browser4-cli htmlsnapshot get text ".${TITLE_CLASS}")

echo "$TITLE costs $PRICE"
# → "Widget Pro costs $29.99"
```

**Advantages over Tier 1:**
- `id` and `class` values may not be shown in the compact snapshot output
- `get attr` gives you the exact attribute value, not just what the snapshot chose to display
- Works for `data-*` attributes that interactive snapshot typically omits

---

### Tier 3: Generate Unique Selector via generate-locator

**Best for:** Complex pages where the element has no `id`, no unique class, and you need a guaranteed-unique, fully-qualified CSS selector.

Use the built-in `generate-locator` command:

```bash
# Generate a unique CSS selector for element e5
SELECTOR=$(browser4-cli generate-locator e5)

# SELECTOR is now something like:
# "#main-content > div.product-card:nth-child(3) > div.price-container > span.price"

# Use it with htmlsnapshot
browser4-cli htmlsnapshot get text "$SELECTOR"
browser4-cli htmlsnapshot get html "$SELECTOR"
```

**How it works:**

1. The backend resolves the ref (`e5`, `backend:15`) or CSS selector to a DOM element
2. Checks if the element has an `id` → returns `#the-id` (shortest, fastest, most unique)
3. Walks up from the element, building a selector path segment by segment
4. For each ancestor, uses `tag#id`, `tag.class1.class2`, or `tag:nth-child(n)` as needed
5. Stops when it reaches an element with an `id` or the document root
6. Returns a single CSS selector string

`generate-locator` also accepts CSS selectors — pass one to get a fully-qualified path to that element:

```bash
# Resolve a CSS selector to a unique path
browser4-cli generate-locator ".price"
# → "body > div.search-results > div.product-card:nth-child(1) > span.price"
```

---

## End-to-End Workflow

Here is the complete pattern: snapshot to discover → bridge to selector → extract with htmlsnapshot:

```bash
# 1. Navigate and get a compact snapshot (NO full DOM read)
browser4-cli goto "https://shop.example.com/search?q=laptop"
browser4-cli snapshot
# Output (compact, ~300 tokens):
# @e10 [div class="search-results"]
#   @e11 [div class="product-card"]
#     @e12 [h2] "ThinkPad X1"
#     @e13 [span class="price"] "$1,299"
#     @e14 [a href="/p/123"] "Details"
#   @e15 [div class="product-card"]
#     @e16 [h2] "MacBook Pro"
#     @e17 [span class="price"] "$2,499"
#     @e18 [a href="/p/456"] "Details"

# 2. Option A (Tier 1): Use visible class names directly
browser4-cli htmlsnapshot get text ".price"
# → "$1,299"  (first match only — see note below)

# 2. Option B (Tier 2): Extract class from a specific ref for precision
CARD_CLASS=$(browser4-cli get attr e11 class)
# To get ALL prices, use X-SQL (since get returns only the first match):
browser4-cli htmlsnapshot query --sql "
  SELECT DOM_FIRST_TEXT(dom, '.price') AS price,
         DOM_FIRST_TEXT(dom, 'h2') AS title
  FROM DOM_LOAD_AND_SELECT(@url, '.${CARD_CLASS}')
"
# → [{"price": "$1,299", "title": "ThinkPad X1"},
#    {"price": "$2,499", "title": "MacBook Pro"}]

# 3. Option C (Tier 3): Get a guaranteed-unique selector for a specific element
SELECTOR=$(browser4-cli generate-locator e13)
browser4-cli htmlsnapshot get text "$SELECTOR"
# → "$1,299"
```

## Choosing get vs query

| Use `htmlsnapshot get` when… | Use `htmlsnapshot query` when… |
|-----------------------------|-------------------------------|
| You need one value from one element | You need multiple fields from repeating elements |
| The selector is simple and stable | You need filtering (`WHERE`), `expr()`, or aggregation |
| You're in a shell script doing quick checks | You want structured tabular output |
| You want raw text/HTML for piping | You want all matches, not just the first |

> **Important:** `htmlsnapshot get` returns **only the first match**. For extracting data from multiple elements (e.g., all prices on a listing page), use `htmlsnapshot query` with X-SQL's `load_and_select`.

## Anti-Patterns to Avoid

```bash
# ❌ BAD: Exporting full page HTML then reading it
browser4-cli htmlsnapshot export --file page.html
cat page.html  # hundreds of MB!

# ❌ BAD: Getting full page HTML just to find one value
browser4-cli htmlsnapshot get html ":root"  # entire page innerHTML

# ✅ GOOD: Get only what you need
browser4-cli htmlsnapshot get text ".price"

# ✅ GOOD: Scoped HTML for a specific region
browser4-cli htmlsnapshot get html "form#checkout"

# ✅ GOOD: Structured extraction with X-SQL
browser4-cli htmlsnapshot query --sql "
  SELECT DOM_FIRST_TEXT(dom, '.price') AS price
  FROM DOM_LOAD_AND_SELECT(@url, '.product-card')
"
```

## Related References

- [HTML Snapshot Reference](htmlsnapshot.md) — full command reference for `htmlsnapshot get`, `query`, `export`
- [X-SQL Reference](x-sql.md) — DOM and string function reference for `htmlsnapshot query`
- [X-SQL DOM Select Functions](x-sql-dom-select-functions.md) — CSS selector-based extraction functions
- [HTML Snapshot Scenarios](htmlsnapshot-scenarios.md) — real-world recipes
- [SKILL.md](../SKILL.md) — Browser4 CLI automation skill overview
