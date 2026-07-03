# `htmlsnapshot inspect` & `htmlsnapshot summary`

## `htmlsnapshot inspect` — Discover CSS selectors on unknown pages

**Problem it solves:** You land on an unfamiliar page (e.g., a competitor's search results) and need to extract structured data — but you don't know the CSS selectors for the repeating content. Instead of reading raw HTML or guessing class names, `inspect` analyzes the DOM and suggests reliable selectors.

**How it works (fully deterministic, no AI):**

When you provide a CSS `selector` that matches multiple elements (e.g. `.product-card`):
1. Finds all elements matching your `selector`
2. For each match, walks descendants up to `--depth` and computes relative CSS selectors (`tag.class#id`)
3. Counts how many matches each selector appears in
4. Filters to selectors appearing in **≥50%** of matches (minimum 2)
5. Returns sample structures + ranked selector suggestions with coverage percentages

When you run without a selector (default `:root`), or with any selector that matches only 1 element, **auto-discovery** kicks in:
1. Walks the DOM to find groups of sibling elements with the same CSS signature
2. Scores each group by size, specificity (class-based > bare tags), content variance, and structural richness
3. Picks the best repeating pattern and uses it as the effective selector
4. Runs the normal cross-match comparison pipeline against the discovered pattern
5. The response includes `autoDiscovered: true` and `originalSelector` so you know discovery was used

This means `htmlsnapshot inspect` with no arguments now produces useful suggestions out of the box — no need to already know a container selector.

**Usage:**

```bash
# First capture a snapshot
browser4-cli goto "https://books.toscrape.com"
browser4-cli htmlsnapshot

# Discover selectors for repeating product cards
browser4-cli htmlsnapshot inspect ".product_pod"
```

**Output preview:**
```
### Inspect: ".product_pod" (20 matches, 10 analyzed)

  Sample structure (3 of 20):
  -- Element 1: article.product_pod
      img.thumbnail
      h3            ""
       a            "A Light in the Attic"
      div.product_price
       p.price_color  "£51.77"
      p.instock.availability  "In stock"

  Suggested selectors (recurring across matches):
   10/10 (100%)  h3 a                                         → "A Light in the..."
   10/10 (100%)  img.thumbnail                                → ""
   10/10 (100%)  p.price_color                                → "£51.77"
    8/10 ( 80%)  p.instock.availability                       → "In stock"
```

**Parameters:**

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `selector` | `:root` | CSS selector to scope inspection; when it matches multiple elements, it compares child structures across matches |
| `--max N` | 20 | Max matching elements to analyze |
| `--depth D` | 5 | Max descendant depth for selector suggestions |

**Workflow:** `inspect` → `get all` / `query` — take the suggested selectors and use them for batch extraction:

```bash
browser4-cli htmlsnapshot get all text ".product_pod h3 a"          # all titles
browser4-cli htmlsnapshot get all text ".product_pod p.price_color"  # all prices
```

---

## `htmlsnapshot summary` — Compressed page overview (WPSI)

**Problem it solves:** You need a quick, AI-readable overview of a page — headings, forms, tables, key content, stats — without reading the full HTML or writing any selectors. Great as a *discovery step* before committing to specific `get` or `query` calls.

**What it produces:** A Web Page Summary Index (WPSI) — a deterministic YAML file typically **<1% of the original HTML size**. It includes:

- Page URL and title
- Heading hierarchy (h1–h6)
- Counts of forms, tables, and lists
- Text statistics (node count, character count)
- Key content nodes with CSS selector hints and text previews

**Usage:**

```bash
# Capture a snapshot first, then summarize
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot summary
```

**Output** is saved to `.browser4-cli/snapshot/htmlsnapshot-summary-<timestamp>.yml`:

```yaml
url: https://en.wikipedia.org/wiki/Web_scraping
title: "Web scraping - Wikipedia"
metaDescription: "Web scraping, web harvesting, or web data extraction is..."
headings:
  - level: h1
    text: "Web scraping"
  - level: h2
    text: "History"
  - level: h2
    text: "Techniques"
forms: 0
tables: 3
lists: 12
textStats:
  totalTextNodes: 847
  totalTextChars: 52341
keyContent:
  - selector: "#mw-content-text > div.mw-parser-output > p:nth-child(5)"
    textPreview: "Web scraping is the process of automatically..."
    textLength: 342
```

**Use case:** Run `summary` to answer "what's on this page?" before writing selectors for `get`/`query`. Especially useful on unfamiliar pages or for structural audits across a site.

---

## How they fit together

| Step | Command | What you learn |
|------|---------|----------------|
| 1 | `htmlsnapshot` | Capture the page DOM |
| 2 | `htmlsnapshot summary` | What's on the page (headings, tables, forms, key content) |
| 3 | `htmlsnapshot inspect` | Auto-discover repeating content patterns and their reliable CSS selectors |
| 4 | `htmlsnapshot get all` / `query` | Extract the actual data |

Both commands are purely deterministic — no AI involved — and operate against the cached HTML snapshot.
