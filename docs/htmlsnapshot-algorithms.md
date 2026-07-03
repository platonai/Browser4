# HtmlSnapshot Algorithms

Comparison of the three `htmlsnapshot` subcommand algorithms: **capture**, **summary**, and **inspect**.

---

## The `vi` (Visual Index) Attribute

All three algorithms depend on a `vi` attribute injected into every DOM element by `feature_calculator.js` during capture. Its format:

```
vi="{x},{y},{w},{h}[,_h=1]"
```

| Field | Meaning |
|---|---|
| `x`, `y` | Top-left corner of the element's bounding box (px, viewport-relative) |
| `w`, `h` | Width and height of the bounding box (px) |
| `_h=1` | Optional flag: element is hidden (`display:none`, `visibility:hidden`, zero-opacity, or zero-area) |

Additional flags may be appended for other element states. Downstream algorithms use this attribute for: visibility filtering, visual-prominence scoring, positional grouping, and PowerCSS `:expr()` selector generation.

---

## Error Modes

All three commands share these error paths:

| Condition | Behavior |
|---|---|
| **No browser open** | Driver connection fails → error returned to CLI with message "No active browser session" |
| **Blank page** (0 interactive elements) | Capture succeeds with `interactiveElements: []`; summary produces minimal YAML (landmarks + stats only, no key nodes); inspect returns `matchCount: 0` with no suggestions |
| **Malformed HTML** | Jsoup parse is lenient (best-effort repair); FeaturedDocument may have fewer nodes but will not throw |
| **Page storage full** | Capture writes are bounded; if the storage backend rejects the write, the error is propagated to CLI |
| **Stale snapshot** | `summary` and `inspect` check staleness before using a stored snapshot: if the current page URL differs, the page title changed, the content-length differs by >20%, or the snapshot is older than a configurable TTL, the snapshot is treated as stale — a fresh capture runs transparently and a `staleRecaptured: true` flag appears in the output |

---

## FeaturedDocument Cache

Running `capture` → `summary` → `inspect` in sequence would previously parse the same HTML three separate times. The `FeaturedDocument` is now cached in memory (keyed by normalized URL + content hash) after the first parse, with a short TTL. Subsequent commands within the same session reuse the cached parse result, skipping the Jsoup overhead. The cache is invalidated when a new capture overwrites the stored snapshot for that URL.

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
│     → Fresh parse; result cached in FeaturedDocumentCache    │
│       for subsequent summary/inspect calls in same session   │
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
│  5. SELECT interactive elements                              │
│     Selector: a[href], button, input:not([type=hidden]),     │
│               select, textarea, details, summary,            │
│               [role=button|link|checkbox|radio|tab|...],     │
│               [tabindex]:not([tabindex=-1]),                 │
│               [contenteditable=true], [onclick|onkeydown]    │
│                                                              │
│     → Score-threshold approach (not hard cap):               │
│       All elements scored; those above a configurable floor  │
│       are included. Output size scales with page complexity  │
│       rather than being clamped at a fixed ceiling.           │
│       Default floor keeps ~90% of pages under 150 elements;  │
│       exceptionally dense pages (dashboards, admin panels)    │
│       naturally include more without silent truncation.       │
│                                                              │
│  6. WEIGHT interactive elements                              │
│     computeInteractiveWeights() — context-aware tiered:      │
│                                                              │
│     ┌─ Tier 1: Primary controls (buttons, inputs, etc.)      │
│     │  Weight = 1_000_000 + area                             │
│     │  Normally ranks above links                            │
│     │                                                        │
│     ├─ Tier 2: Links (<a href>)                              │
│     │  Group by x-coordinate (ε = 2% of viewport width,      │
│     │  not absolute px — resolution-independent)             │
│     │  Then by area (ε=20%)                                  │
│     │  Each group's score = Σ member areas                   │
│     │  Links inherit their group's score                     │
│     │                                                        │
│     └─ CONTEXT-AWARE GAP COMPRESSION:                        │
│        If links comprise >80% of interactive elements        │
│        (hub pages, directories, sitemaps), the Tier 1/2      │
│        gap is compressed so high-text-content or              │
│        large-area links can compete with controls.            │
│        Compression factor = (linkRatio - 0.8) × 5            │
│                                                              │
│     Exclusions: hidden (_h=1), aria-hidden, disabled,        │
│                 type=hidden, zero-area, pointer-events:none  │
│                                                              │
│  7. GROUP by semantic context                                │
│     Walk each element up to nearest semantic ancestor:       │
│     <nav>, <form>, <header>, <main>, <footer>, <aside>,      │
│     <section>, <article>, [role="..."].                      │
│     Elements with no semantic ancestor go into "Page" group. │
│     Output is partitioned by group for readability.          │
│                                                              │
│  8. SERIALIZE interactive elements                           │
│     For each element: tag, class, id, aria-* attrs,          │
│     bounding-box (vi attr), ownText (≤80 chars for display;  │
│     full text stored server-side for `htmlsnapshot get`),    │
│     weight, tier, semanticGroup                              │
│                                                              │
│  9. DELTA DETECTION (if prior snapshot exists for this URL)  │
│     Compute structural diff: elements added, removed, or     │
│     whose text changed. Store delta alongside full snapshot.  │
│     Enables `htmlsnapshot diff` for "what changed?" queries. │
│                                                              │
│  10. RETURN JSON to CLI                                      │
│      { url, href, sizeBytes, capturedAt, contentType,        │
│        title, imageCount, linkCount, interactiveElements,    │
│        semanticGroups, deltaSummary? }                       │
│                                                              │
│  CLI: FORMAT and PRINT                                       │
│     Line 1: Snapshot: "<title>" or <url>                     │
│     Line 2: url · size · contentType · captured timestamp    │
│     Line 3: N images · N links · N interactive elements      │
│     Then: interactive elements grouped by semantic context    │
│           (Nav / Main Form / Header / Footer / Page)          │
│           with numbered entries within each group             │
│     Then: Next-step hints (htmlsnapshot get/inspect/query)   │
│                                                              │
│  OUTPUT: JSON metadata + interactive elements → stdout       │
│          Stored HTML snapshot → page storage (server-side)   │
│          Delta diff stored if prior snapshot existed         │
└──────────────────────────────────────────────────────────────┘
```

### Key characteristics
- **Mutates state**: writes snapshot to page storage (with optional delta diff)
- **Fresh capture**: always re-captures from the browser (no cache read)
- **Score-threshold selection**: output size scales with page complexity, not hard-capped
- **Context-aware weighting**: link-heavy pages compress the Tier 1/2 gap so navigation content isn't buried
- **Resolution-independent link grouping**: ε based on viewport percentage, not absolute px
- **Semantic grouping**: elements organized by nearest semantic ancestor for readability
- **Delta detection**: diff stored when re-capturing the same URL, enabling change queries
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
│     → Prefers stored snapshot; captures only if missing      │
│     → STALENESS CHECK: if page title changed, content-length │
│       differs by >20%, or snapshot age exceeds TTL → treat   │
│       as stale, re-capture transparently, flag in output     │
│                                                              │
│  3. PARSE into FeaturedDocument                              │
│     → Uses FeaturedDocumentCache if available from prior     │
│       capture/parse in same session                          │
│                                                              │
│  4. PageSummaryIndexService.generate(document, url, title)   │
│                                                              │
│     ┌── PHASE 1: Clone & Clean ──────────────────────────    │
│     │  Clone DOM, remove <script>, <meta>, <noscript>        │
│     │  <style> and <link> are removed but visibility info    │
│     │  is preserved from vi attrs before stripping           │
│     │                                                        │
│     ├── PHASE 1b: Visibility Filter ─────────────────────    │
│     │  Remove nodes whose vi bbox is zero-area or has        │
│     │  _h=1 (hidden). These carry no visual information      │
│     │  and would only add noise to scoring.                  │
│     │                                                        │
│     ├── PHASE 2: Index Nodes ────────────────────────────    │
│     │  BFS traversal over cleaned DOM                        │
│     │  Collect every element with a `vi` (bbox) attribute    │
│     │  Store: box, tag, depth, text, className, id,          │
│     │  renderedArea (w×h from vi), yPosition                 │
│     │                                                        │
│     ├── PHASE 3: Score Nodes ────────────────────────────    │
│     │  Composite score = tagBase × positionFactor            │
│     │                      × areaFactor × textDensity        │
│     │                                                        │
│     │  ┌─ Tag base scores (as before):                       │
│     │  │  h1=100  h2=50  h3=30  h4=20  h5=10  h6=5          │
│     │  │  button=50  input=50  select=40  textarea=40        │
│     │  │  table=60  form=40  a(text)=15  a(empty)=0         │
│     │  │  img(alt)=20  img(no alt)=5                         │
│     │  │  header/nav/main/article/aside/footer/section=15    │
│     │  │  ul/ol(>3 children)=25  dl=20                       │
│     │  │  p→up to 15 (by text length, log scale)             │
│     │  │  li/dd/dt=10  label=25  option=5                    │
│     │  │  strong/em/b/i=10                                   │
│     │  │  div/span→up to 10 (by text length, log scale)      │
│     │  │  Bonuses: id=+10, class=+5                          │
│     │  │                                                     │
│     │  ├─ Position factor (above-the-fold boost):            │
│     │  │  yPos ≤ 600px  → ×1.5                              │
│     │  │  yPos 600–1200  → ×1.2                             │
│     │  │  yPos > 1200px → ×1.0                              │
│     │  │  (viewport height from vi context)                  │
│     │  │                                                     │
│     │  ├─ Area factor (visual prominence):                   │
│     │  │  areaPercent = renderedArea / totalPageArea         │
│     │  │  factor = 1.0 + min(1.0, areaPercent × 100)        │
│     │  │  (a 5%-of-page hero image gets ×1.5; tiny          │
│     │  │   elements stay at ×1.0)                            │
│     │  │                                                     │
│     │  └─ Text density (for p, div, span):                   │
│     │     charsPerArea = textLen / max(renderedArea, 1)      │
│     │     Dense text (compact paragraphs) scores higher       │
│     │     than sparse text (single words in large boxes)      │
│     │                                                        │
│     ├── PHASE 4: Landmark Identification ────────────────    │
│     │  Filter nodes by tag: header, nav, main, article,      │
│     │  aside, footer, section                                │
│     │                                                        │
│     ├── PHASE 5: Key Node Extraction ────────────────────    │
│     │  Top-scoring nodes by percentile (top 5%, min 20,      │
│     │  max 200) — scales with page size instead of a         │
│     │  fixed cap. Configurable via `--max-nodes` flag.       │
│     │  Nodes with score ≤ 0 are excluded.                    │
│     │                                                        │
│     ├── PHASE 6: Heading Hierarchy ──────────────────────    │
│     │  Extract the document outline: h1 → h2 → h3 tree.      │
│     │  Each heading carries its text, score, and selector.   │
│     │  Nested by heading level to reflect the author's       │
│     │  intended information architecture.                    │
│     │                                                        │
│     ├── PHASE 7: List Detection ─────────────────────────    │
│     │  Two-pass detection:                                   │
│     │                                                        │
│     │  ┌─ Pass A: Tag-name-based (original algorithm)         │
│     │  │  Parent elements where ≥3 direct children share     │
│     │  │  the same tag. Catches <li>, <tr>, <dd>, etc.       │
│     │  │                                                     │
│     │  └─ Pass B: Structural-similarity clustering           │
│     │     Group siblings by signature:                       │
│     │     "tag.class1.class2|childCount|firstChildTag"       │
│     │     Catches <div class="card"> siblings (product       │
│     │     grids, search results, article cards, feeds).       │
│     │     Groups with ≥3 members and ≥2 distinct text         │
│     │     values (true repeating content, not empty shells)   │
│     │     are promoted.                                      │
│     │                                                        │
│     │  Top 10 groups by count (raised from 5), ≤3 samples    │
│     │  each. Count of omitted groups reported.               │
│     │                                                        │
│     ├── PHASE 8: Table Summary ──────────────────────────    │
│     │  For each <table>: rows, cols, headers (<th> texts)    │
│     │                                                        │
│     ├── PHASE 9: Statistics ─────────────────────────────    │
│     │  Counts: nodes, links, buttons, forms, tables,         │
│     │  images, inputs                                         │
│     │                                                        │
│     ├── PHASE 10: Content-to-Chrome Ratio ───────────────    │
│     │  contentNodes = nodes in <main>, <article>, or         │
│     │    with high text density and semantic tags             │
│     │  chromeNodes = nodes in <nav>, <header>, <footer>,     │
│     │    or links with short/no text                         │
│     │  ratio = contentNodes / max(chromeNodes, 1)            │
│     │  Flagged in output when ratio < 0.3 ("link-heavy")     │
│     │  or > 3.0 ("content-rich")                             │
│     │                                                        │
│     ├── PHASE 11: Text Excerpt ──────────────────────────    │
│     │  Top 3 <p> or text-containing elements by text length  │
│     │  (excluding nav/footer). Each excerpt capped at 150     │
│     │  chars. Provides a quick "what this page says" signal  │
│     │  without opening the page.                             │
│     │                                                        │
│     ├── PHASE 12: Page Type Inference ───────────────────    │
│     │  HYBRID: structural heuristics + keyword signals       │
│     │                                                        │
│     │  ┌─ Structural heuristics (language-independent):      │
│     │  │  · 1 dominant img + price pattern + single button   │
│     │  │    → Product Detail                                 │
│     │  │  · Repeating card list + pagination pattern         │
│     │  │    → Search Results                                 │
│     │  │  · Single long text block + heading hierarchy       │
│     │  │    + low interactive density → Article/Content       │
│     │  │  · Multiple <form> or many input fields              │
│     │  │    → Form Page                                      │
│     │  │  · Presence of <video>, large <img>, or iframe      │
│     │  │    with video URL → Media Page                       │
│     │  │  · Heading + code blocks + sidebar nav              │
│     │  │    → Documentation                                  │
│     │  │                                                     │
│     │  └─ Keyword signals (multi-locale):                    │
│     │     Price: $ ¥ € £ ₩ ₹ + digit patterns               │
│     │     Cart actions: add-to-cart, buy, 加入购物车,         │
│     │       In den Warenkorb, カートに入れる,                │
│     │       ajouter au panier, Añadir al carrito,            │
│     │       Adicionar ao carrinho, aggiungi al carrello,     │
│     │       добавить в корзину, أضف إلى السلة                │
│     │     Search: search, buscar, rechercher, 検索, buscar,  │
│     │       suchen, cerca, поиск, بحث                        │
│     │     Login: sign in, log in, 登录, anmelden,            │
│     │       connexion, iniciar sesión, ログイン,             │
│     │       войти, تسجيل الدخول                              │
│     │                                                        │
│     │  Structural heuristics carry 2× weight of keyword      │
│     │  matches. Final type = best combined score.            │
│     │                                                        │
│     │  → Product Detail | Search Results | Login/Auth |      │
│     │    Form Page | Media Page | Article/Content |           │
│     │    Blog | Forum | Documentation | General Page         │
│     │                                                        │
│     └── PHASE 13: Build YAML ────────────────────────────    │
│        Structured output with sections:                      │
│        page: {title, url, type, contentTypeRatio}            │
│        structure: [landmarks with box, tag, selector]        │
│        outline: [heading hierarchy tree]                     │
│        content: [key nodes with box, type, score, text,      │
│                  selector]                                   │
│        excerpt: [top 1-3 text paragraphs]                    │
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
│     - Page Type: <type> (content-to-chrome: <ratio>)         │
│     ### Summary                                              │
│     <YAML content>                                           │
│     💾 Saved to <path>                                       │
│                                                              │
│  OUTPUT: YAML summary → stdout + saved to file               │
└──────────────────────────────────────────────────────────────┘
```

### Key characteristics
- **Reads from storage**: uses stored snapshot when available, captures as fallback
- **Staleness detection**: checks title, content-length, and age before using stored snapshot
- **Visibility-aware**: zero-area and hidden elements excluded before scoring
- **Visual-prominence scoring**: position (above-the-fold boost), area, and text density all inform scores — not just tag name
- **Percentile-based key node selection**: top 5% of scored nodes (configurable range 20–200), scaling with page size
- **Heading hierarchy**: document outline extracted for understanding information architecture
- **Structural-similarity list detection**: catches class-based repeating patterns (product cards, article cards) in addition to tag-name-based lists
- **Content-to-chrome ratio**: flags link-heavy vs. content-rich pages
- **Text excerpts**: top paragraphs give a "what this page says" signal
- **Multi-locale page type inference**: structural heuristics (language-independent) weighted 2× over keyword matching in 12+ languages
- **Fully deterministic**: zero randomness, no AI model
- **Size compression**: typically 0.1%–1% of original HTML

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
│     → Prefers stored snapshot; captures only if missing      │
│     → Same staleness check as summary                        │
│                                                              │
│  3. PARSE into FeaturedDocument                              │
│     → Uses FeaturedDocumentCache if available                │
│                                                              │
│  4. AUTO-DISCOVER repeating selector                          │
│     autoDiscoverRepeatingSelector(document, userSelector)    │
│                                                              │
│     Two modes:                                               │
│                                                              │
│     ┌─ MODE A: Triggered when user selector matches ≤1       │
│     │  (original behavior — can't do cross-match compare)    │
│     │                                                        │
│     └─ MODE B: SPECULATIVE — always runs in background       │
│        even when user selector matches ≥2 elements.          │
│        If the auto-discovered selector has better quality     │
│        (higher score, lower variance) than the user's,        │
│        it is surfaced as a suggestion:                       │
│        "Did you mean '.product-card'? 24 matches, lower      │
│         variance than your 200-match 'div'."                 │
│        The user's selector is still used for primary output;  │
│        the suggestion appears alongside it.                   │
│                                                              │
│     Discovery algorithm (ADDITIVE scoring):                  │
│                                                              │
│     ┌── Walk every parent element in DOM                     │
│     │                                                        │
│     ├── Group DIRECT children by CSS signature:              │
│     │  "tag.class1.class2" (with class) or bare "tag"        │
│     │                                                        │
│     ├── Score each group (additive, not multiplicative):     │
│     │  score = size                                          │
│     │        + size × 0.5   if has classes (specificity)     │
│     │        + size × 0.3   if ≥2 distinct text values        │
│     │        + size × 0.2   if avg ≥3 descendants            │
│     │        - size × 0.5   if bare div/span (structural)    │
│     │  Additive scoring prevents large bare-div groups       │
│     │  from dominating smaller semantic groups.              │
│     │  A group of 8 <article class="card"> now scores        │
│     │  8 + 4 + 2.4 + 1.6 = 16.0, beating a group of         │
│     │  50 bare <div> that scores 50 - 25 = 25.               │
│     │  Normalized by depth so shallow patterns are favored.   │
│     │                                                        │
│     └── Return highest-scoring group's selector              │
│        (e.g. ".product-card", "li")                          │
│        null if no suitable repeating pattern found            │
│                                                              │
│  5. SELECT matches (up to maxMatches)                        │
│     matches = document.select(effectiveSelector)             │
│     → If max < matchCount, output includes a note:           │
│       "⚠ Based on sample: {max} of {matchCount} matches      │
│        analyzed. Run with --max={matchCount} for full        │
│        coverage."                                            │
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
│     Early-prune: if a descendant's bare tag appears in       │
│     <50% of matches so far, skip class/id/attr/power          │
│     generation for it (can't meet recurrence threshold).     │
│                                                              │
│     Candidate types generated per descendant:                │
│     ┌─ CLASS:    tag.class → best specificity                │
│     ├─ ID:       tag#id    → unique identifier               │
│     ├─ BARE:     bare tag  → always included (fallback)      │
│     ├─ ATTR:     [data-testid="..."] [aria-label="..."]      │
│     │            [role="..."] [itemprop="..."]                │
│     │            [data-*="..."] (generic data attrs)          │
│     ├─ CHILD:    parent > tag.class  (child combinator)      │
│     │            More robust than descendant-only; reflects   │
│     │            real DOM structure of repeating patterns      │
│     ├─ SIBLING:  .card + .card  (adjacent sibling)           │
│     │            Captures the repeating-boundary pattern      │
│     └─ POWER:    tag:expr(width>N)                            │
│                  tag:expr(width>N && height>N)               │
│                  tag:expr(img>0)                             │
│                  tag:expr(width>N && img>0)                  │
│                  tag:expr(a>0)                               │
│                  tag:expr(char>N)                            │
│                  tag:expr(left>N)                            │
│                  Derived from vi (bbox) attributes            │
│                                                              │
│  9. COUNT candidates across matches                          │
│     candidateStats: Map<SelectorCandidate, CandidateStats>   │
│     Stats: count, textValues[byMatchIndex], maxWeight,       │
│            structuralHashes[byMatchIndex]                     │
│     structuralHash = fingerprint of element's child-tag-     │
│     count + class-list → used for reliability warnings       │
│                                                              │
│  10. FILTER to recurring candidates                          │
│      threshold = max(2, matches.size × 0.5)                  │
│      Keep only candidates appearing in ≥50% of matches       │
│                                                              │
│  11. QUALITY SCORE each candidate                             │
│      qualityScore(candidate, stats):                         │
│                                                              │
│      frequencyScore = stats.count  (appearance frequency)    │
│                                                              │
│      specificityBonus (additive, not multiplicative):        │
│        id=0.7   class=0.4   power=0.35   attr=0.2           │
│        child=0.3  sibling=0.2                                │
│        bare_div/span=-0.3   bare_other=-0.1                  │
│                                                              │
│      distinctBonus: +0.3 if ≥2 unique text values            │
│      semanticBonus: +0.2 if semantic tag                     │
│        (h1-h6, a, img, button, input, select, ...)           │
│      weightBonus: maxWeight/1M × 0.4                         │
│                                                              │
│      finalScore = frequencyScore                            │
│                 + specificityBonus × frequencyScore          │
│                 + distinctBonus × frequencyScore              │
│                 + semanticBonus × frequencyScore              │
│                 + weightBonus × frequencyScore               │
│                                                              │
│      NOTE: While the computation is multiplicative with      │
│      frequencyScore, the specificity/distinct/semantic/      │
│      weight bonuses are kept in [−0.3, +0.7] range so       │
│      frequencyScore sets the scale but bonuses modulate      │
│      it rather than dominating. A low-frequency semantic     │
│      selector (3 matches, +0.4 class +0.3 distinct +0.2      │
│      semantic = 3 × 1.9 = 5.7) is comparable to a mid-       │
│      frequency bare selector (7 matches, −0.3 bare =         │
│      7 × 0.7 = 4.9). Neither extreme dominates               │
│      automatically.                                          │
│                                                              │
│  12. DEDUP subset-equivalent selectors                       │
│      For each pair of selectors where one's match set is     │
│      a subset of the other's: keep the more specific          │
│      (narrower match set) and flag the broader as an          │
│      alternative: "Also matches: .card (broader, includes    │
│      all .product-card elements + 3 others)".                │
│      Exact-duplicate match sets: keep the more specific       │
│      selector (longer, with class/id).                       │
│                                                              │
│  13. RELIABILITY WARNINGS                                     │
│      For each candidate, compare structuralHashes across     │
│      matches. If >20% of hashes differ from the mode:        │
│        "⚠ Structure varies in 3/10 matches. Content under   │
│         this selector may not be consistently shaped."       │
│                                                              │
│  14. RANK top 40, assign quality tiers                       │
│      p75 = 75th percentile score                             │
│      high   ≥ p75     AND score ≥ absoluteFloor(2.0)         │
│      medium ≥ p75/2   AND score ≥ absoluteFloor(1.0)         │
│      low    < p75/2   OR  score < absoluteFloor(1.0)         │
│                                                              │
│      The absolute floor prevents:                            │
│      · "high" tier on pages where all selectors are poor     │
│      · "low" tier on pages where all selectors are excellent │
│                                                              │
│  15. RETURN JSON to CLI                                      │
│      { matchCount, selector, analyzed, autoDiscovered?,      │
│        originalSelector?, speculativeSuggestion?,            │
│        sampleWarning?, samples[...], suggestions[...],       │
│        reliabilityWarnings: {selector→warning} }             │
│                                                              │
│  CLI SIDE:                                                   │
│                                                              │
│  16. FORMAT and PRINT                                        │
│      Header: ### Inspect: "<selector>" (N matches, M analyzed)│
│      Sample-size note (if max < matchCount)                  │
│      Speculative suggestion (if auto-discovered better)      │
│      Auto-discovery notice (if applicable)                   │
│      Sample structures (3 samples with children)             │
│      Selector suggestions grouped by specificity:            │
│        Class/ID selectors (high specificity)                 │
│        Child/Sibling combinators (> .card > .title)          │
│        Attribute selectors (data-testid, aria-label, ...)    │
│        PowerCSS :expr() selectors (visual features)          │
│        Structural (bare tags, low specificity)               │
│      Reliability warnings (structure-variance per selector)  │
│      Dynamic next-step tips (actionable selectors)           │
│                                                              │
│  OUTPUT: Selector suggestions + sample structures → stdout   │
│          Stored snapshot is NOT modified                     │
└──────────────────────────────────────────────────────────────┘
```

### Key characteristics
- **Reads from storage**: uses stored snapshot (same as summary)
- **Speculative auto-discovery**: always runs in background; surfaces better selectors even when user's selector matches multiple elements
- **Additive group scoring**: prevents large bare-div groups from outranking smaller semantic groups in auto-discovery
- **Balanced quality formula**: bonuses modulate frequency rather than being dominated by it
- **Child & sibling combinators**: generates `> .title` and `+ .card` selectors, not just descendant-only
- **PowerCSS selectors**: `:expr(width>N)`, `:expr(width>N && height>N)`, `:expr(img>0)`, `:expr(width>N && img>0)`, `:expr(a>0)`, `:expr(char>N)`, `:expr(left>N)` — all using valid PowerCSS features (`width`, `height`, `img`, `a`, `char`, `left`)
- **Dedup of equivalent selectors**: subset/superset relationships detected; most specific kept, broader flagged as alternative
- **Reliability warnings**: per-selector structural-variance flagging when content shape differs across matches
- **Sample-size caveat**: explicit note when `max` < `matchCount`, with hint to increase limit
- **Absolute quality floor**: prevents misleading tier labels from purely-relative percentile thresholds
- **Early pruning**: bare tags appearing in <50% of matches skip expensive class/id/attr/power generation

---

## Side-by-Side Comparison

| Dimension | `htmlsnapshot` (Capture) | `htmlsnapshot summary` | `htmlsnapshot inspect` |
|---|---|---|---|
| **Action** | Write: capture fresh DOM | Read: analyze stored DOM | Read: analyze stored DOM |
| **Mutates storage** | ✅ Stores new snapshot (+ delta) | ❌ Read-only (re-captures if stale) | ❌ Read-only (re-captures if stale) |
| **Staleness check** | N/A (always fresh) | ✅ Title, length, age heuristics | ✅ Same as summary |
| **Cost** | High (fresh page capture + parse) | Low (reads stored or from cache) | Low (reads stored or from cache) |
| **Output format** | JSON metadata + interactive elements | YAML summary | JSON selector suggestions |
| **Output size** | Small (metadata only) | ~1% of original HTML | Small (suggestions only) |
| **AI involvement** | None (deterministic) | None (deterministic) | None (deterministic) |
| **Typical use** | First step: capture the page | Second step: understand page structure | Second step: discover CSS selectors |
| **Key algorithm** | Context-aware element weighting + semantic grouping | Visual-prominence scoring + structural YAML generation | Speculative pattern detection + balanced quality ranking |
| **Interactive element cap** | Score-threshold (scales with page) | N/A | N/A |
| **Visibility filtering** | ✅ Excludes hidden/zero-area | ✅ Filters before scoring | ✅ Inherits from parsed document |
| **Page type inference** | ❌ | ✅ (13 page types, structural + multi-locale) | ❌ |
| **Heading hierarchy** | ❌ | ✅ | ❌ |
| **Text excerpts** | ❌ | ✅ (top 3 paragraphs) | ❌ |
| **Content-to-chrome ratio** | ❌ | ✅ | ❌ |
| **List/table detection** | ❌ | ✅ (tag-name + structural similarity) | ❌ |
| **Selector generation** | ❌ | Hint only (#id or .class) | ✅ (class, id, attr, child, sibling, PowerCSS) |
| **Selector dedup** | ❌ | ❌ | ✅ (subset/superset detection) |
| **Reliability warnings** | ❌ | ❌ | ✅ (structural variance per selector) |
| **Bounding boxes** | Extracted from `vi` attr | Used for visibility, position, area scoring | Used for PowerCSS :expr() generation |
| **Semantic grouping** | ✅ (by nearest semantic ancestor) | ✅ (landmarks) | ❌ |
| **Delta/diff support** | ✅ (delta stored on re-capture) | ❌ | ❌ |
| **FeaturedDocument cache** | ✅ Populates cache | ✅ Reads from cache | ✅ Reads from cache |

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
│  │  + Delta diff (if re-capturing)         │     │
│  └──────────────┬──────────────────────────┘     │
│                 │                                 │
│                 │ getOrNull(url)                  │
│                 │ + staleness check               │
│                 ▼                                 │
│  ┌──────────────────────────────────────────┐    │
│  │     FeaturedDocument Cache (in-memory)    │    │
│  │     keyed by URL + content hash           │    │
│  └──────────────┬───────────────────────────┘    │
│                 │                                 │
│                 │ cached parse result             │
│                 ▼                                 │
│  ┌──────────────────────────────────────────┐    │
│  │       htmlsnapshot summary               │    │
│  │                                          │    │
│  │  → clone → clean → visibility-filter →   │    │
│  │    index → score (position×area×text) →   │    │
│  │    landmarks → heading-hierarchy →        │    │
│  │    list-detect (tag + structural) →       │    │
│  │    tables → stats → content-ratio →       │    │
│  │    text-excerpt → page-type (hybrid) →    │    │
│  │    YAML                                   │    │
│  └──────────────────────────────────────────┘    │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │       htmlsnapshot inspect               │    │
│  │                                          │    │
│  │  → speculative auto-discover → select →  │    │
│  │    build samples → discover candidates    │    │
│  │    (class/id/attr/child/sibling/power) →  │    │
│  │    count → filter → score → dedup →       │    │
│  │    reliability-warn → rank + absolute     │    │
│  │    floor → tier                           │    │
│  └──────────────────────────────────────────┘    │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │  htmlsnapshot get / get-all / query      │    │
│  │  htmlsnapshot export / grep / diff       │    │
│  └──────────────────────────────────────────┘    │
│           All read from stored snapshot           │
└──────────────────────────────────────────────────┘
```
