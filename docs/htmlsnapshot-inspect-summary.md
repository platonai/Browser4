# `htmlsnapshot inspect` & `htmlsnapshot summary`

## `htmlsnapshot` — Capture a static DOM snapshot first

Both `inspect` and `summary` operate on the cached HTML snapshot. Capture it with:

```bash
browser4-cli goto "https://books.toscrape.com"
browser4-cli htmlsnapshot
```

The base `htmlsnapshot` command stores the page in Browser4's page storage and returns JSON metadata including:

- `url`, `href`, `title`, `contentType`, `sizeBytes`, `capturedAt`
- `imageCount`, `linkCount`
- `interactiveElements` — top weighted interactive elements in Section 8 format (`#closestId tag#id.class`)
- `linkGroups` — visually detected repeating card/link groups

> Use `htmlsnapshot` first; `inspect` and `summary` read from the stored snapshot.

---

## `htmlsnapshot inspect` — Discover CSS selectors on unknown pages

**Problem it solves:** You land on an unfamiliar page (e.g., a competitor's search results) and need to extract structured data — but you don't know the CSS selectors for the repeating content. Instead of reading raw HTML or guessing class names, `inspect` analyzes the DOM and suggests reliable selectors.

**How it works (fully deterministic, no AI):**

When you provide a CSS `selector` that matches multiple elements (e.g. `.product-card`):
1. Finds all elements matching your `selector` (up to `--max`).
2. For each match, walks descendants up to `--depth` and computes relative CSS selectors (`tag.class#id`, attribute selectors, and PowerCSS `:expr()` visual selectors).
3. Counts how many matches each selector appears in.
4. Filters to selectors appearing in **≥50%** of matches (minimum 2).
5. Ranks suggestions by a quality score combining specificity, text distinctiveness, semantic tag value, and interactive-element weight.
6. Returns sample structures + ranked selector suggestions with coverage percentages and quality tiers.

When you run without a selector (default `:root`), or with any selector that matches **≤1** element, **auto-discovery** kicks in:
1. **Visual geometry first:** clusters elements by bounding-box geometry (width → height → x-position → y-spacing regularity), then walks up the DOM to find the repeating card container. This is language-independent, class-name-independent, and tolerant of varying internal DOM structure.
2. If visual detection finds nothing, a structural-signature fallback groups each parent's direct children by CSS signature (`tag.class1.class2`) and scores them by size, specificity, text diversity, structural richness, image presence, tag diversity, text length, chrome/penalty, and viewport position.
3. If your selector matches exactly 1 element (and is not `:root`), discovery is scoped to that container's descendants before falling back to page-level discovery.
4. The response includes `autoDiscovered: true` and `originalSelector` so you know discovery was used.

If your selector already matches multiple elements but visual detection finds a potentially better pattern, `inspect` also surfaces a `speculativeSuggestion` without overriding your choice.

**Usage:**

```bash
# First capture a snapshot
browser4-cli goto "https://books.toscrape.com"
browser4-cli htmlsnapshot

# Discover selectors for repeating product cards
browser4-cli htmlsnapshot inspect ".product_pod"

# Inspect the whole page with auto-discovery
browser4-cli htmlsnapshot inspect

# Read a complex selector from stdin or a file to avoid shell escaping
browser4-cli htmlsnapshot inspect --stdin < selector.txt
echo '[data-component-type="s-search-result"]' | browser4-cli htmlsnapshot inspect --stdin
browser4-cli htmlsnapshot inspect --selector-base64 W2RhdGEtY29tcG9uZW50LXR5cGU9InMtc2VhcmNoLXJlc3VsdCJd
```

**Parameters:**

| Parameter | Default | Purpose |
|-----------|---------|---------|
| `selector` | `:root` | CSS selector to scope inspection; when it matches multiple elements, it compares child structures across matches. Prefix with `@` to read from file (e.g. `@selector.txt`). |
| `--max N` | 20 | Max matching elements to analyze. |
| `--depth D` | 5 | Max descendant depth for selector suggestions. |
| `--stdin` | false | Read the CSS selector from stdin instead of an inline argument (avoids shell quoting issues on Windows). |
| `--selector-base64` | — | Base64-encoded CSS selector (avoids shell quoting issues on Windows). |

**Output (JSON):**

```json
{
  "matchCount": 20,
  "selector": ".product_pod",
  "analyzed": 20,
  "samples": [
    {
      "ref": "article.product_pod",
      "box": "0 0 200 300",
      "text": "A Light in the Attic",
      "children": [
        { "ref": "img.thumbnail", "box": "10 10 180 120" },
        { "ref": "h3 a", "box": "10 140 180 20", "text": "A Light in the Attic" },
        { "ref": "p.price_color", "box": "10 170 180 20", "text": "£51.77" }
      ]
    }
  ],
  "suggestions": [
    {
      "selector": "h3 a",
      "tag": "a",
      "textPreview": "A Light in the Attic",
      "textSamples": ["A Light in the Attic", "Tipping the Velvet"],
      "matchCount": 20,
      "coverage": "100%",
      "quality": "high"
    }
  ]
}
```

Top-level fields:

| Field | Meaning |
|-------|---------|
| `matchCount` | Total number of elements matching the effective selector in the document. |
| `selector` | Effective selector used (may be auto-discovered). |
| `analyzed` | Number of matches actually analyzed (capped by `--max`). |
| `autoDiscovered` | `true` when the selector was discovered automatically. |
| `originalSelector` | The selector you originally passed (only when auto-discovered). |
| `autoDiscoveredCandidates` | Alternative selectors considered during auto-discovery. |
| `speculativeSuggestion` / `speculativeMatchCount` | A potentially better pattern found when your selector already matched multiple elements. |
| `samples` | First 3 matches in Section 8 format (`#closestId tag#id.class`). |
| `suggestions` | Ranked recurring selectors with coverage and quality tier (`high`/`medium`/`low`). |
| `linkGroups` | Visually detected repeating link/card groups. |

**Quality scoring:** Each suggestion is assigned a quality tier based on a percentile of all ranked candidates. The ranking combines:
- **Specificity** — class/id/attribute/PowerCSS selectors score higher than bare tags.
- **Distinctiveness** — selectors whose text varies across matches score higher.
- **Semantic value** — headings, links, images, form controls score higher.
- **Interactive weight** — candidates matching important interactive elements are boosted.

**Workflow:** `inspect` → `get all` / `query` — take the suggested selectors and use them for batch extraction:

```bash
browser4-cli htmlsnapshot get all text ".product_pod h3 a"          # all titles
browser4-cli htmlsnapshot get all text ".product_pod p.price_color"  # all prices
```

---

## `htmlsnapshot summary` — Compressed page overview (WPSI)

**Problem it solves:** You need a quick, AI-readable overview of a page — headings, forms, tables, key content, stats — without reading the full HTML or writing any selectors. Great as a *discovery step* before committing to specific `get` or `query` calls.

**What it produces:** A Web Page Summary Index (WPSI) — a deterministic YAML file typically **0.1%–1%** of the original HTML size. It includes:

- Page URL, title, and inferred page type
- Landmark structure (header, nav, main, article, aside, footer, section)
- Top 100 scored key content nodes with Section 8 refs, bounding boxes, type labels, scores, duplicate counts, text previews, and CSS selector hints
- Detected repeated lists
- Detected link groups (visually clustered repeating cards)
- Table summaries (rows, columns, headers)
- Statistics: nodes, links, buttons, forms, tables, images, inputs

**Usage:**

```bash
# Capture a snapshot first, then summarize
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot summary
```

Use `--raw` or `--stdout` to print the YAML directly to stdout instead of saving to a file.

**Output** is saved to `.browser4-cli/snapshot/htmlsnapshot-summary-<timestamp>.yml`:

```yaml
page:
  title: "Web scraping - Wikipedia"
  url: "https://en.wikipedia.org/wiki/Web_scraping"
  type: "Article / Content"

structure:
  - box: "0 0 1920 120"
    tag: header
    selector: "#mw-head"
  - box: "0 120 250 800"
    tag: nav
    selector: "#mw-panel"
  - box: "250 120 1670 800"
    tag: main
    selector: "#content"

content:
  - ref: "h1.firstHeading"
    box: "250 120 1670 60"
    type: h1
    score: 100
    text: "Web scraping"
  - ref: "#mw-content-text p"
    box: "250 200 1670 100"
    type: text
    score: 45
    text: "Web scraping is the process of automatically..."
    selector: "#mw-content-text"

lists:
  - parentTag: "ul.nav"
    itemTag: "li"
    count: 12

linkGroups:
  - container: "div.mw-parser-output"
    itemTag: "a"
    itemSelector: "a.external"
    count: 24
    columnCount: 1
    allHaveLinks: true
    anyHaveImages: false
    distinctTextCount: 24
    score: 270.0

tables:
  - box: "250 500 1670 200"
    rows: 5
    cols: 3
    headers:
      - "Method"
      - "Pros"
      - "Cons"

stats:
  nodes: 847
  links: 312
  buttons: 4
  forms: 0
  tables: 3
  images: 12
  inputs: 2
```

**Page type inference:** The summary heuristically classifies the page as one of:

- `Product Detail`
- `Search Results`
- `Article / Content`
- `Login / Auth`
- `Form Page`
- `Media Page`
- `Homepage`
- `News / Content`
- `Blog`
- `Forum`
- `Documentation`
- `General Page`

**Use case:** Run `summary` to answer "what's on this page?" before writing selectors for `get`/`query`. Especially useful on unfamiliar pages or for structural audits across a site.

---

## How they fit together

| Step | Command | What you learn |
|------|---------|----------------|
| 1 | `htmlsnapshot` | Capture the page DOM and get enriched metadata + interactive elements + link groups. |
| 2 | `htmlsnapshot summary` | What's on the page (page type, landmarks, key content, lists, link groups, tables, stats). |
| 3 | `htmlsnapshot inspect` | Auto-discover repeating content patterns and their reliable CSS selectors. |
| 4 | `htmlsnapshot get all` / `query` | Extract the actual data. |

Both `inspect` and `summary` are purely deterministic — no AI involved — and operate against the cached HTML snapshot.
