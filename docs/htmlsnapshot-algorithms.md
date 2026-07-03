# HtmlSnapshot Algorithms

Comparison of the three `htmlsnapshot` subcommand algorithms: **capture**, **summary**, and **inspect**.

---

## 1. `htmlsnapshot` — Capture

**Purpose**: Take a static HTML snapshot of the current page and store it in Browser4's page storage for later querying.

**Entry point**: `MCPToolController.handleHtmlSnapshotCapture()` (server) → `handle_html_snapshot_capture()` (CLI)

### Algorithm

```
┌──────────────────────────────────────────────────────────────┐
│                     CAPTURE Algorithm                         │
├──────────────────────────────────────────────────────────────┤
│  INPUT: sessionId                                            │
│                                                              │
│  1. LOCK session                                             │
│                                                              │
│  2. CAPTURE current page from driver                         │
│     pulsarSession.capture(driver)                            │
│     → Fetches full DOM HTML from the browser                 │
│     → Injects feature_calculator.js (adds vi=bbox attrs)     │
│     → Stores in page storage (keyed by normalized URL)       │
│                                                              │
│  3. PARSE into FeaturedDocument (Jsoup)                      │
│     pulsarSession.parse(page, noCache=true)                  │
│     → Fresh parse, no cached document                        │
│                                                              │
│  4. EXTRACT metadata                                         │
│     ├─ title:    document.title                              │
│     ├─ url:      page.url (normalized)                       │
│     ├─ href:     page.href (original)                        │
│     ├─ size:     page.contentLength                          │
│     ├─ contentType: page.contentType                         │
│     ├─ capturedAt: page.prevFetchTime                        │
│     ├─ imageCount: document.select("img").size               │
│     └─ linkCount:  document.select("a").size                 │
│                                                              │
│  5. SELECT interactive elements (max 100)                    │
│     Selector: a[href], button, input:not([type=hidden]),     │
│               select, textarea, details, summary,            │
│               [role=button|link|checkbox|radio|tab|...],     │
│               [tabindex]:not([tabindex=-1]),                 │
│               [contenteditable=true], [onclick|onkeydown]    │
│                                                              │
│  6. WEIGHT interactive elements                              │
│     computeInteractiveWeights() — two-tier system:           │
│                                                              │
│     ┌─ Tier 1: Primary controls (buttons, inputs, etc.)      │
│     │  Weight = 1_000_000 + area                             │
│     │  Always ranks above links                              │
│     │                                                        │
│     └─ Tier 2: Links (<a href>)                              │
│        Group by x-coordinate (ε=10px), then area (ε=20%)     │
│        Each group's score = Σ member areas                   │
│        Links inherit their group's score                     │
│     Exclusions: hidden (_h=1), aria-hidden, disabled,        │
│                 type=hidden, zero-area, pointer-events:none  │
│                                                              │
│  7. SERIALIZE interactive elements                           │
│     For each element: tag, class, id, aria-* attrs,          │
│     bounding-box (vi attr), ownText (≤80 chars),             │
│     weight, tier                                             │
│                                                              │
│  8. RETURN JSON to CLI                                       │
│     { url, href, sizeBytes, capturedAt, contentType,         │
│       title, imageCount, linkCount, interactiveElements }    │
│                                                              │
│  CLI: FORMAT and PRINT                                       │
│     Line 1: Snapshot: "<title>" or <url>                     │
│     Line 2: url · size · contentType · captured timestamp    │
│     Line 3: N images · N links · N interactive elements      │
│     Then: grouped interactive elements (Links/Buttons/       │
│           Inputs/Other) with numbered entries                 │
│     Then: Next-step hints (htmlsnapshot get/inspect/query)   │
│                                                              │
│  OUTPUT: JSON metadata + interactive elements → stdout       │
│          Stored HTML snapshot → page storage (server-side)    │
└──────────────────────────────────────────────────────────────┘
```

### Key characteristics
- **Mutates state**: writes snapshot to page storage
- **Fresh capture**: always re-captures from the browser (no cache read)
- **Client-side display**: groups interactive elements into Links/Buttons/Inputs/Other
- **Importance weighting**: Tier 1 (controls) always above Tier 2 (links)
- **Next-step hints**: shows relevant follow-up commands

---

## 2. `htmlsnapshot summary` — Summarize

**Purpose**: Read the stored HTML snapshot and produce a compressed Web Page Summary Index (WPSI) in <1% of original HTML size. Deterministic — no AI model involved.

**Entry point**: `MCPToolController.handleHtmlSnapshotSummary()` (server) → `handle_html_snapshot_summary()` (CLI)

### Algorithm

```
┌──────────────────────────────────────────────────────────────┐
│                    SUMMARIZE Algorithm                        │
├──────────────────────────────────────────────────────────────┤
│  INPUT: sessionId                                            │
│                                                              │
│  SERVER SIDE:                                                │
│                                                              │
│  1. LOCK session                                             │
│                                                              │
│  2. FETCH page (read-only, fallback to capture)              │
│     page = getOrNull(url) ?? capture(driver)                 │
│     → Prefers stored snapshot, captures only if missing       │
│                                                              │
│  3. PARSE into FeaturedDocument                              │
│                                                              │
│  4. PageSummaryIndexService.generate(document, url, title)   │
│                                                              │
│     ┌── PHASE 1: Clone & Clean ──────────────────────────    │
│     │  Clone DOM, remove <script>, <style>, <meta>,          │
│     │  <link>, <noscript>                                    │
│     │                                                        │
│     ├── PHASE 2: Index Nodes ────────────────────────────    │
│     │  BFS traversal over cleaned DOM                        │
│     │  Collect every element with a `vi` (bbox) attribute    │
│     │  Store: box, tag, depth, text, className, id           │
│     │                                                        │
│     ├── PHASE 3: Score Nodes ────────────────────────────    │
│     │  Deterministic scoring by tag:                         │
│     │    h1=100   h2=50    h3=30    h4=20   h5=10  h6=5     │
│     │    button=50  input=50  select=40  textarea=40         │
│     │    table=60   form=40                                  │
│     │    a(text)=15  a(empty)=0                              │
│     │    img(alt)=20  img(no alt)=5                          │
│     │    header/nav/main/article/aside/footer/section=15     │
│     │    ul/ol(>3 children)=25  dl=20                        │
│     │    p→up to 15 (by text length)                         │
│     │    li/dd/dt=10  label=25  option=5                     │
│     │    strong/em/b/i=10                                    │
│     │    div/span→up to 10 (by text length)                  │
│     │  Bonuses: id=+10, class=+5                             │
│     │                                                        │
│     ├── PHASE 4: Landmark Identification ────────────────    │
│     │  Filter nodes by tag: header, nav, main, article,      │
│     │  aside, footer, section                                │
│     │                                                        │
│     ├── PHASE 5: Key Node Extraction ────────────────────    │
│     │  Top 100 nodes by score (score > 0)                    │
│     │                                                        │
│     ├── PHASE 6: List Detection ─────────────────────────    │
│     │  Find parent elements where ≥3 direct children          │
│     │  share the same tag (div, li, tr, article, etc.)       │
│     │  Top 5 groups by count, with ≤3 samples each           │
│     │                                                        │
│     ├── PHASE 7: Table Summary ──────────────────────────    │
│     │  For each <table>: rows, cols, headers (<th> texts)    │
│     │                                                        │
│     ├── PHASE 8: Statistics ─────────────────────────────    │
│     │  Counts: nodes, links, buttons, forms, tables,         │
│     │  images, inputs                                         │
│     │                                                        │
│     ├── PHASE 9: Page Type Inference ────────────────────    │
│     │  Heuristic keyword + tag detection:                    │
│     │    Price detection: $ ¥ € + digit patterns             │
│     │    Add-to-cart / Buy now / 加入购物车 / 立即购买        │
│     │    Search / Login / Article / Form / Video             │
│     │  → Product Detail | Search Results | Login/Auth |      │
│     │    Form Page | Media Page | Article/Content |           │
│     │    Blog | Forum | Documentation | General Page         │
│     │                                                        │
│     └── PHASE 10: Build YAML ────────────────────────────    │
│        Structured output with sections:                      │
│        page: {title, url, type}                              │
│        structure: [landmarks with box, tag, selector]        │
│        content: [key nodes with box, type, score, text,      │
│                  selector]                                   │
│        lists: [repeated patterns with samples]               │
│        tables: [table dimensions with headers]               │
│        stats: {nodes, links, buttons, forms, tables,         │
│                images, inputs}                               │
│                                                              │
│  5. RETURN YAML to CLI                                       │
│                                                              │
│  CLI SIDE:                                                   │
│                                                              │
│  6. FETCH page_url + page_title (parallel calls)             │
│                                                              │
│  7. SAVE summary to file (browser4-htmlsnapshot-summary.*.yml)│
│                                                              │
│  8. PRINT to stdout (non-raw mode)                           │
│     ### Page                                                 │
│     - Page URL: <url>                                        │
│     - Page Title: <title>                                    │
│     ### Summary                                              │
│     <YAML content>                                           │
│     💾 Saved to <path>                                       │
│                                                              │
│  OUTPUT: YAML summary → stdout + saved to file               │
└──────────────────────────────────────────────────────────────┘
```

### Key characteristics
- **Reads from storage**: uses stored snapshot when available, captures as fallback
- **Fully deterministic**: zero randomness, no AI model
- **Size compression**: typically 0.1%–1% of original HTML
- **Structure-aware**: preserves landmarks, lists, tables, key content nodes
- **CSS selector hints**: every node carries `#id` or `.class` for bridging to `htmlsnapshot get`

---

## 3. `htmlsnapshot inspect` — Inspect

**Purpose**: Read the stored HTML snapshot and discover CSS selectors for recurring patterns (product cards, prices, titles, etc.)

**Entry point**: `MCPToolController.handleHtmlSnapshotInspect()` (server) → `handle_html_snapshot_inspect()` (CLI)

### Algorithm

```
┌──────────────────────────────────────────────────────────────┐
│                     INSPECT Algorithm                         │
├──────────────────────────────────────────────────────────────┤
│  INPUT: sessionId, selector=":root", max=10, depth=5         │
│                                                              │
│  SERVER SIDE:                                                │
│                                                              │
│  1. LOCK session                                             │
│                                                              │
│  2. FETCH page (read-only, fallback to capture)              │
│     page = getOrNull(url) ?? capture(driver)                 │
│     → Prefers stored snapshot, captures only if missing       │
│                                                              │
│  3. PARSE into FeaturedDocument                              │
│                                                              │
│  4. AUTO-DISCOVER repeating selector (if needed)             │
│     autoDiscoverRepeatingSelector(document)                  │
│     Trigger: initial selector (e.g. :root) matches ≤1        │
│              element → can't do cross-match comparison        │
│                                                              │
│     ┌── Walk every parent element in DOM                     │
│     │                                                        │
│     ├── Group DIRECT children by CSS signature:              │
│     │  "tag.class1.class2" (with class) or bare "tag"        │
│     │                                                        │
│     ├── Score each group by:                                 │
│     │  · size:      number of children in group              │
│     │  · specificity: ×1.5 if has classes                    │
│     │  · variance:   ×1.3 if ≥2 distinct text values         │
│     │  · richness:   ×1.2 if avg ≥3 descendants              │
│     │  · structural: ×0.5 if bare div/span                   │
│     │                                                        │
│     └── Return highest-scoring group's selector              │
│        (e.g. ".product-card", "li")                          │
│        null if no suitable repeating pattern found            │
│                                                              │
│  5. SELECT matches (up to maxMatches)                        │
│     matches = document.select(effectiveSelector)             │
│                                                              │
│  6. BUILD sample structures (first 3 matches)                │
│     For each match: tag, class, id, ownText                  │
│                     + direct children (tag, class, id, text)  │
│                                                              │
│  7. PRE-COMPUTE element weights                              │
│     computeInteractiveWeights() across all interactive        │
│     elements → used to boost candidates targeting             │
│     high-importance elements                                 │
│                                                              │
│  8. DISCOVER selector candidates                             │
│     For each match, walk descendants up to maxDepth:         │
│                                                              │
│     Candidate types generated per descendant:                │
│     ┌─ CLASS:  tag.class → best specificity                  │
│     ├─ ID:     tag#id    → unique identifier                 │
│     ├─ BARE:   bare tag  → always included (fallback)        │
│     ├─ ATTR:   [data-testid="..."] [aria-label="..."]        │
│     │          [role="..."] [itemprop="..."]                  │
│     │          [data-*="..."] (generic data attrs)            │
│     └─ POWER:  tag:expr(width>N) tag:expr(img>0)             │
│                tag:expr(a>0) tag:expr(width>N && img>0)      │
│                Derived from vi (bbox) attributes              │
│                                                              │
│  9. COUNT candidates across matches                          │
│     candidateStats: Map<SelectorCandidate, CandidateStats>   │
│     Stats: count, textValues[byMatchIndex], maxWeight        │
│                                                              │
│  10. FILTER to recurring candidates                          │
│      threshold = max(2, matches.size * 0.5)                  │
│      Keep only candidates appearing in ≥50% of matches        │
│                                                              │
│  11. QUALITY SCORE each candidate                             │
│      qualityScore(candidate, stats):                         │
│        base = stats.count  (appearance frequency)            │
│        + specificity × base:                                 │
│            id=0.7  class=0.4  power=0.35  attr=0.2           │
│            bare_div/span=-0.3  bare_other=-0.1               │
│        + distinctBoost × base: +0.3 if ≥2 unique text values │
│        + semanticBoost × base: +0.2 if semantic tag          │
│          (h1-h6, a, img, button, input, select, ...)          │
│        + weightBoost × base: maxWeight/1M × 0.4              │
│                                                              │
│  12. RANK top 40, assign quality tiers                       │
│      p75 = 75th percentile score                             │
│      high   ≥ p75                                            │
│      medium ≥ p75/2                                          │
│      low    < p75/2                                          │
│                                                              │
│  13. RETURN JSON to CLI                                      │
│      { matchCount, selector, analyzed, autoDiscovered?,      │
│        originalSelector?, samples[...], suggestions[...] }   │
│                                                              │
│  CLI SIDE:                                                   │
│                                                              │
│  14. FORMAT and PRINT                                        │
│      Header: ### Inspect: "<selector>" (N matches, M analyzed)│
│      Auto-discovery notice (if applicable)                   │
│      Sample structures (3 samples with children)             │
│      Selector suggestions grouped by specificity:            │
│        Class/ID selectors (high specificity)                 │
│        Attribute selectors (data-testid, aria-label, ...)    │
│        PowerCSS :expr() selectors (visual features)          │
│        Structural (bare tags, low specificity)               │
│      Dynamic next-step tips (actionable selectors)           │
│                                                              │
│  OUTPUT: Selector suggestions + sample structures → stdout   │
│          Stored snapshot is NOT modified                     │
└──────────────────────────────────────────────────────────────┘
```

### Key characteristics
- **Reads from storage**: uses stored snapshot (same as summary)
- **Auto-discovery**: when `:root` yields 1 match, automatically searches for repeating patterns
- **PowerCSS selectors**: generates `:expr()` selectors from bounding-box data
- **Quality tiers**: high / medium / low based on percentile ranking
- **Interactive weight boost**: candidates matching high-importance elements get scored higher

---

## Side-by-Side Comparison

| Dimension | `htmlsnapshot` (Capture) | `htmlsnapshot summary` | `htmlsnapshot inspect` |
|---|---|---|---|
| **Action** | Write: capture fresh DOM | Read: analyze stored DOM | Read: analyze stored DOM |
| **Mutates storage** | ✅ Stores new snapshot | ❌ Read-only (captures as fallback) | ❌ Read-only (captures as fallback) |
| **Cost** | High (fresh page capture + parse) | Low (reads stored, if available) | Low (reads stored, if available) |
| **Output format** | JSON metadata + interactive elements | YAML summary | JSON selector suggestions |
| **Output size** | Small (metadata only) | ~1% of original HTML | Small (suggestions only) |
| **AI involvement** | None (deterministic) | None (deterministic) | None (deterministic) |
| **Typical use** | First step: capture the page | Second step: understand page structure | Second step: discover CSS selectors |
| **Key algorithm** | Interactive element weighting | Node scoring + YAML generation | Recurring pattern detection + quality ranking |
| **Page type inference** | ❌ | ✅ (13 page types) | ❌ |
| **List/table detection** | ❌ | ✅ | ❌ |
| **Selector generation** | ❌ | Hint only (#id or .class) | ✅ (class, id, attr, PowerCSS) |
| **Bounding boxes** | Extracted from `vi` attr | Used for node identification | Used for PowerCSS :expr() generation |

---

## Data Flow

```
                 ┌──────────────┐
                 │   Browser    │
                 │   (driver)   │
                 └──────┬───────┘
                        │ DOM HTML
                        ▼
┌──────────────────────────────────────────────────┐
│              htmlsnapshot (capture)               │
│                                                  │
│  driver.currentUrl() → capture() → parse()       │
│  ┌─────────────────────────────────────────┐     │
│  │  Page Storage (keyed by normalized URL)  │◄────┤ stores here
│  └──────────────┬──────────────────────────┘     │
│                 │                                 │
│                 │ getOrNull(url)                  │
│                 ▼                                 │
│  ┌──────────────────────────────────────────┐    │
│  │       htmlsnapshot summary               │    │
│  │                                          │    │
│  │  → clone → clean → index → score →       │    │
│  │    detect → summarize → YAML             │    │
│  └──────────────────────────────────────────┘    │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │       htmlsnapshot inspect               │    │
│  │                                          │    │
│  │  → auto-discover (if needed) → select →  │    │
│  │    build samples → discover candidates →  │    │
│  │    filter → score → rank                 │    │
│  └──────────────────────────────────────────┘    │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │  htmlsnapshot get / get-all / query      │    │
│  │  htmlsnapshot export / grep              │    │
│  └──────────────────────────────────────────┘    │
│           All read from stored snapshot           │
└──────────────────────────────────────────────────┘
```
