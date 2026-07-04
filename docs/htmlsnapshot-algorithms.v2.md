# Link Group Detection Algorithm

**Status**: Implemented — applied to all three `htmlsnapshot` commands (`capture`, `summary`, `inspect`)

---

## Motivation

The existing `htmlsnapshot summary` detects **lists** (Phase 7: tag-name-based sibling grouping) but does not specifically detect **link groups** — coherent collections of item cards where each card contains one or more links. Examples:

| Page type | Container | Items | Card visual signature |
|---|---|---|---|
| E-commerce search results | `<div id="search-results">` | 24× `<div class="product-card">` | Grid: consistent w×h, multiple columns |
| News article list | `<section class="latest-news">` | 10× `<article>` | List: left-aligned, consistent width, stacked |
| Blog index | `<main>` | 8× `<article class="post">` | List: left-aligned, consistent width, stacked |
| Comment thread | `<div class="comments">` | 15× `<div class="comment">` | List: left-aligned, consistent width, indented |
| Directory / sitemap | `<ul class="directory">` | 50× `<li>` | List: left-aligned, consistent width |

The three card types share a common property: **visual repetition**. The human eye spots them instantly — a grid of same-sized boxes, or a vertical stack of left-aligned rectangles. This algorithm replicates that intuition using only bounding-box geometry from `vi` attributes.

---

## Design Principle

> **Cluster by visual geometry first, then walk up the DOM to find the container.**

This is the inverse of the structural-signature approach (which groups by DOM structure, then checks positions). The visual approach is:
- **Language-independent** — works across Chinese, Arabic, English sites
- **Class-name-independent** — doesn't care whether the CSS is `.product-card` or `.s-result-item` or a random hash
- **Structure-tolerant** — cards with different internal DOM trees still group together if they look the same
- **Layout-aware** — naturally distinguishes grid, list, and scattered layouts

The algorithm uses only the `vi="x,y,w,h"` bounding-box attribute already present on every element in a captured snapshot. No new browser-side injection is needed.

---

## Card Types and Their Visual Signatures

### Type 1: Product-style (Grid)

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  [image]     │  │  [image]     │  │  [image]     │
│  Title text  │  │  Title text  │  │  Title text  │
│  $Price      │  │  $Price      │  │  $Price      │
└──────────────┘  └──────────────┘  └──────────────┘
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  [image]     │  │  [image]     │  │  [image]     │
│  Title text  │  │  Title text  │  │  Title text  │
│  $Price      │  │  $Price      │  │  $Price      │
└──────────────┘  └──────────────┘  └──────────────┘
```

| Visual property | Heuristic |
|---|---|
| Width consistency | All cards share near-identical width (±10% tolerance) |
| Height consistency | All cards share near-identical height (±15% tolerance) |
| X-alignment | 2–5 distinct x-positions (one per column) |
| Y-spacing | Regular vertical gaps between rows |
| Box size | Typically 180–400px wide, 250–500px tall |

### Type 2: News-style (Vertical List)

```
┌──────────────────────────────────────┐
│  [image]  Title text here            │
│           Abstract text preview...   │
├──────────────────────────────────────┤
│  [image]  Another article title      │
│           More preview text here...  │
├──────────────────────────────────────┤
│  [image]  Third article headline     │
│           Excerpt of the content...  │
└──────────────────────────────────────┘
```

| Visual property | Heuristic |
|---|---|
| X-alignment | All cards share the same x-position (left-aligned, ±2% viewport width) |
| Width consistency | Similar width (±15% tolerance) |
| Height | May vary (text length differs), tolerance relaxed to ±30% |
| Y-spacing | Regular vertical gaps, often small (borders/dividers) |
| Box size | Typically spans content area width, 80–250px tall each |

### Type 3: Comment-style (Indented List)

```
┌────────────────────────────────┐
│  Avatar  Username  Timestamp   │
│  Comment body text here...     │
│  Reply  Share  Report          │
├────────────────────────────────┤
│  Avatar  Username  Timestamp   │
│  Another comment text...       │
│  Reply  Share  Report          │
└────────────────────────────────┘
```

Visually identical to news-style (single x-position, consistent width, stacked vertically). Distinguished from news only by typically narrower width and smaller height. The same clustering logic handles both.

---

## Algorithm

```
┌──────────────────────────────────────────────────────────────┐
│                 LINK GROUP DETECTION                          │
├──────────────────────────────────────────────────────────────┤
│  INPUT: FeaturedDocument (cleaned, with vi attributes)       │
│                                                              │
│  ┌── PHASE 0: Extract Viewport Dimensions ───────────────    │
│  │                                                           │
│  │  The viewport size is the coordinate system for all       │
│  │  bounding boxes. It is injected into the page during      │
│  │  capture as a hidden <input> element:                     │
│  │                                                           │
│  │  <input type="hidden" id="PulsarMetaInformation"          │
│  │         domain="www.amazon.com"                           │
│  │         view-port="1920x1080"                             │
│  │         date-time="2026/7/3 01:56:24"                     │
│  │         timestamp="1783014984670">                        │
│  │                                                           │
│  │  Extract:                                                 │
│  │    meta = document.select("#PulsarMetaInformation")       │
│  │    viewport = meta.attr("view-port")  →  "1920x1080"     │
│  │    viewportW, viewportH = parse(viewport)                 │
│  │                                                           │
│  │  Fallback: use <html>'s vi attr (w, h), or default to    │
│  │  1920×1080. All tolerance calculations that use viewport  │
│  │  percentage (ε_x, max card width) reference these values. │
│  │                                                           │
│  ┌── PHASE 1: Collect Card Candidates ───────────────────    │
│  │                                                           │
│  │  Walk all elements with a vi="x,y,w,h" attribute.         │
│  │  For each element:                                        │
│  │                                                           │
│  │  1. SKIP if hidden (_h=1 flag in vi attr)                 │
│  │                                                           │
│  │  2. SKIP if no <a href> descendant (not a link card)      │
│  │                                                           │
│  │  3. SKIP if too small: w < 80px OR h < 30px              │
│  │     (nav links, icons, buttons, single-word tags)         │
│  │                                                           │
│  │  4. SKIP if too large: w > 95% viewport width            │
│  │     AND h > 80% viewport height                            │
│  │     (full-page sections, hero banners, iframes)           │
│  │                                                           │
│  │  5. RECORD: CardCandidate(element, x, y, w, h, area)     │
│  │                                                           │
│  │  Output: flat list of card candidates                     │
│  │                                                           │
│  ├── PHASE 2: Cluster by Visual Similarity ──────────────    │
│  │                                                           │
│  │  Multi-pass clustering. Each pass groups candidates       │
│  │  that share a visual dimension.                           │
│  │                                                           │
│  │  ┌─ Pass 2a: Width clustering ───────────────────────    │
│  │  │                                                       │
│  │  │  Sort candidates by width.                            │
│  │  │                                                       │
│  │  │  Two candidates A and B are in the same width-group   │
│  │  │  if:                                                  │
│  │  │    |w_A - w_B| / max(w_A, w_B) ≤ 10%                 │
│  │  │                                                       │
│  │  │  This catches pixel-identical product cards (0%       │
│  │  │  diff) and CSS-flexible article rows (up to 10%).     │
│  │  │                                                       │
│  │  │  Each width-group proceeds independently to Pass 2b. │
│  │  │                                                       │
│  │  └────────────────────────────────────────────────────   │
│  │                                                           │
│  │  ┌─ Pass 2b: Height clustering ──────────────────────    │
│  │  │                                                       │
│  │  │  Within each width-group, cluster by height:          │
│  │  │    |h_A - h_B| / max(h_A, h_B) ≤ 15%                 │
│  │  │                                                       │
│  │  │  Looser than width because cards vary more in         │
│  │  │  height (longer titles, variable excerpts).           │
│  │  │                                                       │
│  │  └────────────────────────────────────────────────────   │
│  │                                                           │
│  │  ┌─ Pass 2c: X-alignment clustering ─────────────────    │
│  │  │                                                       │
│  │  │  Within each (width, height) group, cluster by        │
│  │  │  x-position:                                          │
│  │  │    |x_A - x_B| ≤ ε_x                                  │
│  │  │                                                       │
│  │  │  where ε_x = 2% of viewport width (resolution-        │
│  │  │  independent, same as existing link grouping).        │
│  │  │                                                       │
│  │  │  Each x-cluster represents either:                    │
│  │  │    · A column in a product grid (multiple clusters)   │
│  │  │    · A news/article list (single cluster)             │
│  │  │                                                       │
│  │  └────────────────────────────────────────────────────   │
│  │                                                           │
│  ├── PHASE 3: Y-Spacing Regularity Check ────────────────    │
│  │                                                           │
│  │  Within each x-cluster, sort by y-position. Compute       │
│  │  gaps between consecutive items:                          │
│  │                                                           │
│  │    gaps = [y₂ - (y₁+h₁), y₃ - (y₂+h₂), ...]             │
│  │                                                           │
│  │  A group has "regular" y-spacing if:                      │
│  │    stddev(gaps) / mean(gaps) ≤ 0.5                       │
│  │                                                           │
│  │  AND at least one of:                                     │
│  │    · mean gap < 2 × median card height                    │
│  │      (items are close together — list/grid, not           │
│  │       scattered across unrelated page sections)           │
│  │    · OR all gaps are within [0, 100px]                    │
│  │      (tightly packed with dividers, common in lists)      │
│  │                                                           │
│  │  Groups that fail this check:                             │
│  │    · Scattered elements that happen to share dimensions   │
│  │      (e.g., same-width sidebars on different pages)       │
│  │    · Elements separated by large irregular gaps           │
│  │    · Single isolated elements                             │
│  │                                                           │
│  ├── PHASE 4: Merge Columns into Grids ──────────────────    │
│  │                                                           │
│  │  After Phase 3, we have x-clusters that each represent    │
│  │  a column (or a single-column list). Merge x-clusters     │
│  │  that belong to the same grid:                            │
│  │                                                           │
│  │  Two x-clusters A and B are columns of the same grid      │
│  │  if ALL of:                                               │
│  │    · Same width-group and height-group                    │
│  │    · Different x-positions (otherwise they'd be the       │
│  │      same column)                                         │
│  │    · Their y-ranges overlap (>50% of items share y-rows   │
│  │      with items in the other column)                      │
│  │    · Similar y-spacing pattern (mean gap within 2×)       │
│  │                                                           │
│  │  Merged grid: items = union of all columns' items.        │
│  │  Unmerged single-column clusters → news/article list.     │
│  │                                                           │
│  │  MINIMUM GROUP SIZE: ≥3 items total (after merging).      │
│  │                                                           │
│  ├── PHASE 5: Navigation Menu Suppression ───────────────    │
│  │                                                           │
│  │  Before finding containers, suppress groups that are      │
│  │  navigation elements rather than content link groups.     │
│  │                                                           │
│  │  Visual nav signals (checked on the group's items):       │
│  │                                                           │
│  │  ┌─ STRONG (reject group):                                │
│  │  │  · Average item height < 36px (single line of text)   │
│  │  │    AND no items contain <img>                          │
│  │  │  · Average item width < 120px AND no images            │
│  │  │    (sidebar nav links, tag clouds)                     │
│  │  │                                                        │
│  │  └─ MODERATE (apply ×0.3 score penalty):                  │
│  │     · Container element is <nav> or role=navigation       │
│  │     · Average link text < 20 chars AND no images          │
│  │       AND items are single-column                        │
│  │                                                           │
│  ├── PHASE 6: Find Nearest Container ────────────────────    │
│  │                                                           │
│  │  For each surviving visual group:                         │
│  │                                                           │
│  │  1. COLLECT all item elements in the group                │
│  │                                                           │
│  │  2. WALK UP from each item to build parent chains         │
│  │     parentChain(el) = [el, el.parent, el.parent.parent,  │
│  │                        ..., <html>]                       │
│  │                                                           │
│  │  3. FIND the LCA (lowest common ancestor) of all items:   │
│  │     LCA = the deepest element that appears in every       │
│  │     item's parent chain                                   │
│  │                                                           │
│  │  4. TIGHTEN the container if LCA is too generic:          │
│  │                                                           │
│  │     If LCA is <html>, <body>, or a <div>/<main> that     │
│  │     contains >80% of ALL card candidates on the page      │
│  │     (i.e., it's the page wrapper, not a specific list):   │
│  │                                                           │
│  │     Walk DOWN from LCA:                                   │
│  │       · Among LCA's children, find the one whose subtree  │
│  │         contains the most items from this group           │
│  │       · If that child contains ≥80% of the group's items, │
│  │         make it the new LCA and repeat                    │
│  │       · Stop when no single child dominates               │
│  │                                                           │
│  │     This tightens:                                        │
│  │       <body> → <main> → <div.search-results>              │
│  │                                                           │
│  │  5. The final LCA is the container for this link group.   │
│  │                                                           │
│  │  6. Compute the item selector:                            │
│  │     · Build the CSS signature (tag + up to 2 classes)     │
│  │       for the item elements                               │
│  │     · If ≥80% of items share the same signature, use it   │
│  │     · Otherwise, use the bare tag (fallback)              │
│  │                                                           │
│  ├── PHASE 7: Resolve Overlapping Groups ────────────────    │
│  │                                                           │
│  │  Multiple groups may share items (nested containers).     │
│  │  Resolution: prefer the DEEPER (tighter) container.       │
│  │                                                           │
│  │  1. SORT groups by container DOM depth descending         │
│  │     (deepest container first)                             │
│  │                                                           │
│  │  2. GREEDY assignment:                                    │
│  │     For each group (deepest first):                       │
│  │       uncovered = items not already claimed               │
│  │       if |uncovered| ≥ 3 AND |uncovered| ≥ 50% total:    │
│  │         keep group, mark uncovered items as claimed       │
│  │       else:                                               │
│  │         discard (a deeper group already covers these)     │
│  │                                                           │
│  │  3. KEEP top 5 groups by score (after dedup)              │
│  │                                                           │
│  │  Scoring formula (applied before dedup):                  │
│  │    score = itemCount × 10                                 │
│  │          + itemCount × 5  if all items have links         │
│  │          + itemCount × 3  if any item has an image        │
│  │          + distinctTextCount × 2                          │
│  │          + min(avgDescendants, 6) × 2                     │
│  │          × navPenalty (1.0 or 0.3)                        │
│  │                                                           │
│  └── PHASE 8: Extract Link Data ─────────────────────────    │
│                                                              │
│     For each surviving group, extract up to 3 sample items.  │
│     For each sample:                                         │
│       · Bounding box (vi attr)                               │
│       · All <a href> descendants: text, href, box            │
│       · Whether it contains an <img>                         │
│       · Total descendant count                               │
│       · Total text length                                    │
│                                                              │
│     Compute aggregate stats for the group:                   │
│       · allHaveLinks, anyHaveImages, distinctTextCount       │
│       · avgDescendants, avgCardWidth, avgCardHeight          │
│       · columnCount (1 = list, ≥2 = grid)                    │
│                                                              │
│  RETURN: List<SummaryLinkGroup> (top 5, sorted by score)     │
└──────────────────────────────────────────────────────────────┘
```

---

## Worked Examples

### Example 1: E-commerce product grid (Amazon search results)

```
Input: 48 product cards in a 4-column grid
Card dimensions: 240×420px each
Viewport: 1920×1080

Phase 1: 48 candidates collected (all 240×420, contain links+images)
Phase 2a: All 48 share width 240 (±0%) → single width-group
Phase 2b: All 48 share height 420 (±0%) → single height-group
Phase 2c: 4 x-clusters found:
  Column 1: x≈20   (12 items, y-positions 100, 530, 960, ...)
  Column 2: x≈270  (12 items, y-positions 100, 530, 960, ...)
  Column 3: x≈520  (12 items, y-positions 100, 530, 960, ...)
  Column 4: x≈770  (12 items, y-positions 100, 530, 960, ...)
Phase 3: Each column: gaps = [430, 430, 430, ...]
  stddev=0, mean=430 → ratio=0 ✓ (perfectly regular)
Phase 4: All 4 columns share (w=240, h=420), y-ranges fully overlap
  → merged into 1 grid with 48 items
Phase 5: Avg height 420px, images present → no nav penalty
Phase 6: LCA = <div id="search-results"> — correct container!
Phase 7: Score = 480 + 240 + 144 + 100 + 12 = 976
```

### Example 2: News article list (BBC/CNN-style)

```
Input: 10 article cards, single column
Card dimensions: 680×120px (typical), some 680×140px
Viewport: 1920×1080, content area x≈200

Phase 1: 10 candidates collected
Phase 2a: All share width 680 (±0%) → single width-group
Phase 2b: 8 cards h=120, 2 cards h=140
  |140-120|/140 = 14.3% ≤ 15% → same height-group ✓
Phase 2c: All share x≈200 (±2% viewport = 38px) → single x-cluster
Phase 3: gaps = [10, 10, 12, 10, 10, 8, 10, 10, 10]
  stddev≈1.1, mean≈10 → ratio≈0.11 ✓ (very regular)
Phase 4: Single cluster → news-style list with 10 items
Phase 5: Images present, avg height 124px → no nav penalty
Phase 6: LCA = <section class="latest-news"> — correct!
Phase 7: Score = 100 + 50 + 30 + 20 + 8 = 208
```

### Example 3: Navigation menu (correctly suppressed)

```
Input: 8 nav items in a <nav> sidebar
Item dimensions: 180×32px each, no images
Viewport: 1920×1080, nav at x≈0

Phase 1: 8 candidates collected
Phase 2a: Width 180 → one width-group
Phase 2b: Height 32 → one height-group
Phase 2c: All x≈0 → single x-cluster
Phase 3: gaps = [0, 0, 0, 0, 0, 0, 0] → regular ✓
Phase 4: Single cluster, 8 items
Phase 5: Avg height 32px < 36px AND no images → STRONG nav signal
  → GROUP REJECTED ✗
```

### Example 4: Documentation sidebar with longer link text

```
Input: 6 sidebar items with descriptions
Item dimensions: 220×60px each, no images
Viewport: 1920×1080, sidebar at x≈0

Phase 1: 6 candidates (w=220, h=60, has links, no images)
Phase 2: Single (w, h, x) cluster
Phase 3: gaps regular → passes
Phase 4: Single cluster, 6 items
Phase 5: Avg height 60px ≥ 36 → NOT strong signal
  No images + avg link text 35 chars > 20 → NOT moderate signal
  → passes nav suppression
Phase 6: LCA = <nav class="docs-sidebar">
  → MODERATE signal: container is <nav>
  → score × 0.3 penalty applied
Phase 7: Score = (60 + 30 + 0 + 12 + 6) × 0.3 = 32.4
  Above minimum (20) → kept, but low-ranked
```

---

## Data Structures

### `CardCandidate` (internal, Phase 1)

| Field | Type | Description |
|---|---|---|
| `element` | `Element` | Jsoup element reference |
| `x, y, w, h` | `Double` | Bounding box from `vi` attr |
| `area` | `Double` | `w × h` |
| `hasLinks` | `Boolean` | Contains `<a href>` descendant |
| `hasImages` | `Boolean` | Contains `<img>` descendant |
| `linkCount` | `Int` | Number of `<a href>` descendants |
| `textLength` | `Int` | Total text content length |

### `VisualCluster` (internal, Phases 2–4)

| Field | Type | Description |
|---|---|---|
| `items` | `List<CardCandidate>` | Members of this cluster |
| `widthGroup` | `Double` | Mean width of members |
| `heightGroup` | `Double` | Mean height of members |
| `xPositions` | `List<Double>` | Distinct x-positions (1 = list, ≥2 = grid) |
| `yRegularity` | `Double` | stddev(gaps) / mean(gaps) — lower = more regular |
| `mergedFrom` | `Int` | Number of x-clusters merged (1 = list, ≥2 = grid) |

### `LinkInfo`

| Field | Type | Description |
|---|---|---|
| `text` | `String` | Link text, capped at 100 chars |
| `href` | `String` | The href attribute, capped at 200 chars |
| `box` | `String` | Bounding box from `vi` attr |

### `LinkGroupItem`

| Field | Type | Description |
|---|---|---|
| `box` | `String` | Bounding box of this sample item |
| `links` | `List<LinkInfo>` | Links found inside this item |
| `hasImage` | `Boolean` | Whether this item contains an `<img>` |
| `descendantCount` | `Int` | Total number of descendant elements |
| `textLength` | `Int` | Total text content length |

### `SummaryLinkGroup`

| Field | Type | Description |
|---|---|---|
| `containerTag` | `String` | Tag name of the container element |
| `containerId` | `String` | `id` attribute (may be empty) |
| `containerClass` | `String` | `class` attribute (may be empty) |
| `containerSelector` | `String` | CSS selector (`#id` > `.class` > bare tag) |
| `itemTag` | `String` | Tag name of the repeated items |
| `itemSelector` | `String` | CSS selector for items |
| `count` | `Int` | Number of items |
| `columnCount` | `Int` | 1 = list, ≥2 = grid |
| `allHaveLinks` | `Boolean` | Every item has ≥1 link |
| `anyHaveImages` | `Boolean` | Any item has an image |
| `avgCardWidth` | `Double` | Mean item width (px) |
| `avgCardHeight` | `Double` | Mean item height (px) |
| `distinctTextCount` | `Int` | Unique non-blank text values |
| `avgDescendants` | `Double` | Mean descendant count per item |
| `samples` | `List<LinkGroupItem>` | Up to 3 sample items |
| `viewportWidth` | `Double` | Viewport width in px (from `<html>` `vi` attr) |
| `viewportHeight` | `Double` | Viewport height in px (from `<html>` `vi` attr) |
| `score` | `Double` | Computed score |

The viewport dimensions are extracted once from the root `<html>` element's `vi` attribute (e.g., `vi="0,0,1920,1080"` → viewport 1920×1080). All bounding boxes in the output are relative to this viewport. Reporting the viewport lets consumers interpret box coordinates correctly — e.g., a card at `x=800` in a 1920px viewport is centered, while the same coordinate in a 375px mobile viewport would be off-screen.

---

## Output Format (YAML)

```yaml
linkGroups:
  - container: "div#search-results"
    selector: "#search-results"
    itemTag: "div"
    itemSelector: ".product-card"
    count: 24
    columnCount: 3
    viewportWidth: 1920.0
    viewportHeight: 1080.0
    allHaveLinks: true
    anyHaveImages: true
    avgCardWidth: 220.0
    avgCardHeight: 380.0
    distinctTextCount: 22
    samples:
      - box: "20 100 220 380"
        links:
          - text: "Sony WH-1000XM5 Wireless Headphones"
            href: "https://example.com/product/B0XYZ123"
            box: "30 110 200 20"
          - text: "$349.99"
            href: "https://example.com/product/B0XYZ123"
            box: "30 320 80 20"
        hasImage: true
      - box: "260 100 220 380"
        links:
          - text: "Bose QuietComfort 45"
            href: "https://example.com/product/B0ABC456"
            box: "270 110 200 20"
          - text: "$299.99"
            href: "https://example.com/product/B0ABC456"
            box: "270 320 80 20"
        hasImage: true
      - box: "500 100 220 380"
        links:
          - text: "Apple AirPods Max"
            href: "https://example.com/product/B0DEF789"
            box: "510 110 200 20"
          - text: "$549.99"
            href: "https://example.com/product/B0DEF789"
            box: "510 320 80 20"
        hasImage: true
  - container: "section.latest-news"
    selector: ".latest-news"
    itemTag: "article"
    itemSelector: "article"
    count: 10
    columnCount: 1
    viewportWidth: 1920.0
    viewportHeight: 1080.0
    allHaveLinks: true
    anyHaveImages: true
    avgCardWidth: 680.0
    avgCardHeight: 124.0
    distinctTextCount: 10
    samples:
      - box: "200 200 680 120"
        links:
          - text: "Breaking News: Major Discovery Announced"
            href: "https://example.com/news/discovery"
            box: "210 210 500 24"
        hasImage: true
```

If no link groups are found, the `linkGroups:` section is omitted (same convention as `lists:` and `tables:`).

---

## Tolerances Summary

| Parameter | Value | Rationale |
|---|---|---|
| Min card width | 80px | Smaller = nav icon, button, tag |
| Min card height | 30px | Smaller = single line of text/link |
| Max card width | 95% viewport | Larger = section/hero, not a card |
| Width tolerance (ε_w) | 10% | Cards in a grid are pixel-identical or near-identical |
| Height tolerance (ε_h) | 15% | Cards vary more in height (text length) |
| X-alignment tolerance (ε_x) | 2% viewport width | Resolution-independent, same as existing link grouping |
| Y-spacing regularity | stddev/mean ≤ 0.5 | Allows some irregularity (variable-height cards) |
| Y-spacing proximity | mean gap < 2× card height | Items must be near each other, not scattered |
| Min group size | 3 | Fewer items = not a meaningful group |
| Column merge: y-overlap | >50% items share rows | Columns must be part of the same grid |
| Strong nav: max height | 36px (no images) | Catches horizontal/sidebar nav links |
| Strong nav: max width | 120px (no images) | Catches narrow sidebar navs |
| Moderate nav | `<nav>` container or short link text + no images | Penalty instead of rejection |

---

## Integration

### Call sites

```
capture:  handleHtmlSnapshotCapture()  → detectLinkGroups(cleaned)  → included as JSON linkGroups
summary:  PageSummaryIndexService.generate() → Phase 6b → detectLinkGroups(cleaned)  → included as YAML linkGroups
inspect:  inspectDocument()            → detectLinkGroups(document) → included as JSON linkGroups
```

### Method signature

```kotlin
internal fun detectLinkGroups(document: FeaturedDocument): List<SummaryLinkGroup>
```

`internal` visibility allows direct unit testing.

### Helper methods (all private)

| Method | Purpose |
|---|---|
| `collectCardCandidates(document): List<CardCandidate>` | Phase 1: walk DOM, filter by size + link presence |
| `clusterByWidth(candidates, ε_w): List<List<CardCandidate>>` | Phase 2a |
| `clusterByHeight(candidates, ε_h): List<List<CardCandidate>>` | Phase 2b |
| `clusterByX(candidates, ε_x): List<List<CardCandidate>>` | Phase 2c |
| `checkYRegularity(cluster): Boolean` | Phase 3: gap analysis |
| `mergeColumns(clusters): List<VisualCluster>` | Phase 4: grid merging |
| `applyNavSuppression(cluster): Double` | Phase 5: returns penalty multiplier |
| `findContainer(items): Element` | Phase 6: LCA + tightening |
| `resolveOverlaps(groups): List<SummaryLinkGroup>` | Phase 7: depth-based dedup |
| `extractLinkData(items, maxSamples): List<LinkGroupItem>` | Phase 8 |

---

## Edge Cases

| Scenario | Expected behavior |
|---|---|
| Page with no repeating visual pattern | `detectLinkGroups()` returns empty list |
| Exactly 2 visually similar cards | Below min group size (3) → not detected |
| Product grid with 4 columns, last row has only 2 items | All items grouped (48 items in 4 columns, last row partial — handled naturally) |
| Variable-height cards in a news list | Height tolerance (15%) accommodates; if variance too high, items split into sub-groups |
| Cards with identical boxes but different content | Structural distinct-text check validates diversity |
| Navigation menu (horizontal bar) | Small height (<36px) + no images → rejected |
| Sidebar nav with icons | Icons are `<img>` or SVG → if images present, height check is skipped → may pass if height ≥ 36px; container-is-nav penalty applies |
| Hero banner with link (large, full-width) | Filtered by max width (95% viewport) |
| Single-column grid (1 column, multiple rows) | Treated as news-style list (columnCount=1) — correct |
| Nested groups (categories within a page) | Phase 7 prefers deeper containers |
| Shadow DOM / iframe content | Elements not in the main document — not handled by this algorithm |
| Cards with no `vi` attributes | Ignored (algorithm requires bounding boxes) |

---

## Comparison: Visual vs. Structural Approach

| Dimension | Visual (this algorithm) | Structural (signature-based) |
|---|---|---|
| Primary grouping | Bounding-box geometry | DOM tag + class signature |
| Language dependence | None | None (both are language-independent) |
| Class-name dependence | None — works on obfuscated class names | Requires consistent class names within a group |
| Internal structure variation | Tolerant — cards can have different internal DOM | Intolerant — different child counts → different signatures |
| Nav suppression | Size-based (height < 36px, width < 120px) | Text-based (link text length, image presence) |
| Strengths | Handles messy/auto-generated HTML; works across frameworks | Precise when DOM is clean and consistent |
| Weaknesses | Can group visually similar but semantically different elements; requires elements to have `vi` attrs | Fails on sites with inconsistent class naming; requires structural consistency |
