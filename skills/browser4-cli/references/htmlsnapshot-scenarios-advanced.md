---
title: "HTML Snapshot Scenarios — Advanced Discovery & Automation"
description: "Recipes for agent-assisted form discovery, page structure analysis with summary/WPSI, and selector discovery with inspect. Covers discovery-first patterns for unfamiliar pages."
tier: procedure
---

# HTML Snapshot Scenarios — Advanced Discovery & Automation

Practical recipes for discovering page structure, finding CSS selectors on unfamiliar pages, and using HTML snapshots in agent-assisted form-filling workflows.

> **Note:** CSS selectors are tied to live websites and may break over time. See [SKILL.md §5](../SKILL.md#5-critical-warnings). These examples demonstrate discovery workflows — the selectors shown are outputs of `inspect` and `summary`, not inputs you hard-code.

> **Parent document:** [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips, and command reference.

## Scenarios

| # | Scenario | Primary Commands | Domain |
|---|----------|------------------|--------|
| 10 | Agent-Assisted Form Discovery | `get` + Agent CLI | AI / Automation |
| 11 | Page Structure Analysis | `summary` | Research / Auditing |
| 13 | Selector Discovery for Unknown Pages | `inspect` | Research / Scraping |

---

## 10. Agent-Assisted Form Discovery

**Problem:** An AI agent needs to understand a complex multi-step form (tax filing, insurance application, loan origination) — what fields exist, what's required, what options are in each `<select>`. The agent uses `htmlsnapshot get` to discover the form structure before filling it.

**Why HTML Snapshot:** Unlike the accessibility-tree `snapshot` (which shows roles and names), `htmlsnapshot get` extracts raw DOM attributes — `required`, `pattern`, `minlength`, `placeholder`, `<option>` values — that are essential for correct form filling.

### 10a. Discover all form fields and their attributes

```bash
browser4-cli goto "https://example.com/insurance/application"
browser4-cli htmlsnapshot

# List all input names and types (reads from the cached snapshot)
browser4-cli htmlsnapshot get attr "form input[name]" name
browser4-cli htmlsnapshot get attr "form input[name]" type
browser4-cli htmlsnapshot get attr "form input[name]" required

# Extract all select options
browser4-cli htmlsnapshot get text "form select[name='state'] option"
# Returns: AL, AK, AZ, AR, CA, CO, CT, ...

# Check validation constraints
browser4-cli htmlsnapshot get attr "form input[name='zip']" pattern
browser4-cli htmlsnapshot get attr "form input[name='zip']" maxlength
```

### 10b. Get full form HTML for LLM analysis

```bash
browser4-cli htmlsnapshot get html "form#application"
# Returns the entire form's inner HTML — feed this to an LLM for semantic understanding
```

### 10c. Agent orchestration pattern

```bash
# Step 1: Capture a HTML snapshot and discover the form structure
browser4-cli htmlsnapshot
FORM_HTML=$(browser4-cli htmlsnapshot get html "form")

# Step 2: Ask the LLM to analyze the form HTML and identify:
#   - All input names, types, and validation constraints
#   - Which fields are required
#   - Select options available
# (the agent framework handles this; htmlsnapshot supplies the raw DOM data)

# Step 3: Capture an interactive snapshot to get element refs
browser4-cli snapshot -v 0

# Step 4: Bridge DOM selectors to snapshot refs using generate-locator
# or by extracting id/class attributes:
browser4-cli get attr "#full-name" id

# Step 5: Agent fills the form using snapshot refs with fill/click/select
browser4-cli fill e12 "John Doe"
browser4-cli fill e15 "john@example.com"
browser4-cli select e18 "CA"
```

> **Important:** `fill` and `select` require snapshot element refs (e.g., `e12`), not CSS selectors. Use the interactive `snapshot` command to get refs, then bridge from DOM selectors using `get attr <selector> id` or `generate-locator <ref>`. The `htmlsnapshot get` commands in steps 10a–10b are for **understanding** the form structure; **interacting** with it requires the accessibility-tree snapshot and its ref-based commands.

---

## 11. Page Structure Analysis with Summary (WPSI)

**Problem:** An auditor or researcher needs a quick, AI-readable overview of a page's structure — headings, forms, tables, key content blocks, and statistics — without reading the full HTML or writing selectors.

**Why HTML Snapshot:** `summary` generates a Web Page Summary Index (WPSI) — a deterministic compressed page summary (typically <1% of original HTML) in YAML format. It's designed for LLM consumption and quick human review.

### 11a. Generate a page summary

```bash
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot summary
```

**Output (example — actual output is YAML):**
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

### 11b. Compare page structures across a site

Use `summary` to detect structural drift across pages — missing headings, extra forms, changed layouts:

```bash
#!/bin/bash
# audit-structure.sh — compare page summaries across a site
PAGES=("/about" "/products" "/pricing" "/contact")

for path in "${PAGES[@]}"; do
  browser4-cli goto "https://example.com$path"
  browser4-cli htmlsnapshot
  echo "=== $path ==="
  browser4-cli htmlsnapshot summary
  echo ""
done
# Pipe summaries to an LLM: "Compare these page summaries and flag structural inconsistencies"
```

### 11c. Pre-screen before deep extraction

```bash
# 1. Get a summary to understand the page structure
browser4-cli goto "https://example.com/products"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot summary
# → reveals: tables=1, lists=5, headings at h2, no forms

# 2. Now write targeted X-SQL knowing the structure
browser4-cli htmlsnapshot query --sql "
  SELECT DOM_FIRST_TEXT(dom, 'h2') AS category, DOM_FIRST_TEXT(dom, 'li') AS item
  FROM DOM_LOAD_AND_SELECT(@url, 'ul.product-list')
"
```

**Why `summary` here:** It answers "what's on this page?" without you writing a single selector. Use it as a discovery step before committing to specific `get` or `query` calls. Especially useful for unfamiliar pages.

---

## 13. Selector Discovery for Unknown Pages with Inspect

**Problem:** You land on an unfamiliar page (e.g., a competitor's e-commerce search results, a job board, or a news aggregator) and need to extract structured data — but you don't know the CSS selectors for product titles, prices, ratings, or other recurring fields. Guessing selectors or manually reading HTML is slow and error-prone.

**Why HTML Snapshot:** `inspect` analyzes the DOM structure and suggests CSS selectors for recurring patterns across matching elements. Run without arguments to trigger **auto-discovery** — it finds the page's most prominent repeating content pattern automatically. It's deterministic, requires no AI, and works on any page where content repeats in a consistent structure.

### 13a. Auto-discover selectors on an unfamiliar page

```bash
# Navigate to the page and capture a snapshot
browser4-cli goto "https://books.toscrape.com"
browser4-cli htmlsnapshot

# Run inspect without arguments — auto-discovery finds .product_pod automatically
browser4-cli htmlsnapshot inspect
```

**Output (example):**
```
### Inspect: ".product_pod" (20 matches, 10 analyzed)
  🔍 Auto-discovered repeating pattern from ":root"

  Sample structure (3 of 20):
  -- Element 1: article.product_pod
      img.thumbnail
      h3            ""
       a            "A Light in the Attic"
      div.product_price
       p.price_color  "£51.77"
      p.instock.availability  "In stock"
  ...

  Suggested selectors (recurring across matches):
   10/10 (100%)  h3 a                                         → "A Light in the..."
   10/10 (100%)  img.thumbnail                                → ""
   10/10 (100%)  p.price_color                                → "£51.77"
    8/10 ( 80%)  p.instock.availability                       → "In stock"
```

You can also provide an explicit container selector if you already know it:
```bash
browser4-cli htmlsnapshot inspect ".product_pod"
```

**Output (example):**
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
  ...

  Suggested selectors (recurring across matches):
   10/10 (100%)  h3 a                                         → "A Light in the..."
   10/10 (100%)  img.thumbnail                                → ""
   10/10 (100%)  p.price_color                                → "£51.77"
    8/10 ( 80%)  p.instock.availability                       → "In stock"
```

### 13b. Use discovered selectors for extraction

Take the selectors from `inspect` and feed them directly into `htmlsnapshot get all` or `htmlsnapshot query`:

```bash
# Extract all product titles using the suggested selector
browser4-cli htmlsnapshot get all text ".product_pod h3 a"

# Extract all prices
browser4-cli htmlsnapshot get all text ".product_pod p.price_color"

# Or run a structured X-SQL query with the discovered selectors
browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, 'h3 a') AS title,
    DOM_FIRST_TEXT(dom, 'p.price_color') AS price,
    DOM_FIRST_TEXT(dom, 'p.instock.availability') AS availability
  FROM DOM_LOAD_AND_SELECT(@url, '.product_pod')
"
```

### 13c. Narrow scope for complex pages

On larger pages, start broad then narrow down:

```bash
# Step 1: Auto-discover the main repeating containers
browser4-cli htmlsnapshot inspect

# Step 2: Drill into a specific container for finer-grained selectors
browser4-cli htmlsnapshot inspect ".s-result-item"

# Step 3: For deeply nested structures, increase depth
browser4-cli htmlsnapshot inspect ".job-card" --depth 6 --max 20
```

### 13d. Workflow: discover → extract → validate

```bash
#!/bin/bash
# discover-and-extract.sh — from zero to structured data on an unknown page

URL="$1"
browser4-cli goto "$URL"
browser4-cli htmlsnapshot

# 1. Auto-discover repeating containers
echo "=== Scanning for repeating containers ==="
browser4-cli htmlsnapshot inspect

# 2. Pick the most promising container (e.g., largest match count)
#    and inspect it in detail
CONTAINER=".product_pod"  # adjust based on step 1 output
echo "=== Inspecting $CONTAINER ==="
browser4-cli htmlsnapshot inspect "$CONTAINER"

# 3. Validate the suggested selectors by extracting a few values
echo "=== Validating: title ==="
browser4-cli htmlsnapshot get all text "$CONTAINER h3 a" --limit 5

echo "=== Validating: price ==="
browser4-cli htmlsnapshot get all text "$CONTAINER p.price_color" --limit 5

# 4. Once validated, run full extraction with htmlsnapshot query
browser4-cli htmlsnapshot query --sql "
  SELECT
    DOM_FIRST_TEXT(dom, 'h3 a') AS title,
    DOM_FIRST_TEXT(dom, 'p.price_color') AS price
  FROM DOM_LOAD_AND_SELECT(@url, '$CONTAINER')
"
```

**Why `inspect` here:** It eliminates the guesswork of selector discovery. Instead of reading raw HTML or guessing class names, you get a ranked list of selectors with coverage percentages. The suggested selectors are based on structural recurrence — if a selector appears in 10/10 cards, it's reliable for extraction.

---

## See Also

- [htmlsnapshot-scenarios.md](htmlsnapshot-scenarios.md) — full scenario index, patterns & tips
- [htmlsnapshot-scenarios-extraction.md](htmlsnapshot-scenarios-extraction.md) — e-commerce, news, jobs, academic, real estate extraction
- [htmlsnapshot-scenarios-amazon.md](htmlsnapshot-scenarios-amazon.md) — Amazon discovery-to-extraction workflows
- [htmlsnapshot-scenarios-audit.md](htmlsnapshot-scenarios-audit.md) — SEO, compliance, CI, pricing, incident response
- [htmlsnapshot.md](htmlsnapshot.md) — full command reference
- [css-selector-bridge.md](css-selector-bridge.md) — bridging snapshot refs to HTML snapshot CSS selectors
- [SKILL.md](../SKILL.md) — Browser4 CLI automation skill overview
