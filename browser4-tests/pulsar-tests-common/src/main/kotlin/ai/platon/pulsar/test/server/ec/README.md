# Mock Ecommerce Site Specification (Dynamic Rendering)

## Prerequisite

Read `AGENTS.md` in the project root to guide your actions.

## Purpose
Implement a fully dynamic mock ecommerce website served under the `/ec` path using `MockSiteApplication.kt`.
All pages (home, category/list, product) must be rendered server-side from a **single JSON data file** loaded once at startup.

**Version scope:** the only project version this document references is `4.16.1-SNAPSHOT`, the
reactor version declared in the root `pom.xml` and inherited by this module's parent block. The
numeric literals below are fixture data or rendering thresholds, **not** version references:

| Literal | Meaning |
|---------|---------|
| `199.99` | sample product `price` (the wireless headphones) |
| `4.4` | sample product `rating` |
| `5.2` | part of the feature string `Bluetooth 5.2` |
| `80.00` | "premium" rendering threshold used by `sectionProfile()` |

> Automated version-consistency checks flag these four literals as stale versions on every
> audit. They are fixture values, not version references: do **not** rewrite them when the
> reactor version changes. Only the reactor version (`4.16.1-SNAPSHOT`) is version-sensitive.

## Runtime Entry Point and Consumers

- `MockSiteApplication.kt` (parent package `ai.platon.pulsar.test.server`) is the Spring Boot
  entry point. `main()` activates the `test` profile; `config/application-test.properties`
  sets `server.port=0` and `spring.main.allow-bean-definition-overriding=true`, so an ad-hoc
  launch binds to an OS-assigned port and test contexts may override beans.
- `CatalogLoader`, `CatalogService`, `HtmlRenderer`, `EcommerceController`, and
  `EcCategoryController` are ordinary Spring components found by the parent-package scan.
- `MockSiteLauncher` (`pulsar-tests-common`) starts/stops the app programmatically, forces
  `--server.port=<port>` (0 = random) as a command-line argument so it outranks the classpath
  `application*.properties`, and exposes `port()`, `baseUrl()`, and `awaitReady()`.
  `start(enforcePort = true)` (the default) restarts an already-running instance whose bound
  port differs from the requested one, while `restart()` stops and starts again.
  `awaitReady()` polls `mock.site.healthPath` (default `/actuator/health`) and then `/`, and
  returns `false` on timeout.
- `MockSiteStarter` waits for an existing site or auto-starts one for a page URL. It prefers the
  URL's explicit port, then `mock.site.port` / `MOCK_SITE_PORT`, then fallbacks 8182 and 8080,
  and finally an OS-assigned port (0). Readiness probes use `mock.site.healthPath`
  (default `/actuator/health`) with a fallback to `/`.
- `MockSiteController` serves the app's non-EC fixtures: `/` (welcome text), `/hello`,
  `/text`, `/csv`, `/json`, `/robots.txt`, the `assets/test-pages/*` pages
  (`form-page.html`, `error-page.html`, `keyboard-test.html`), plus the legacy
  `/amazon/home.htm` and `/amazon/product.htm` mappings (they read `pages/amazon/*`
  from the classpath). This document covers only `/ec`.
- `HtmlSnapshotMockController` (`/htmlsnapshot-test/*`, e.g. `news`, `seo`, `jobs`,
  `compliance`, `research`, `real-estate`, and `readability-article`) and
  `ConcurrencyProbeController` (`/__probe/*`: `/slow[/{id}]`, `/flaky[/{id}]`,
  `/flaky-hub[/{id}]`, `/hub[/{id}]`, `/stats`, and `POST /reset`) are other test fixtures
  in the same Spring app; they are unrelated to `/ec` and are not covered here.
  The probe pages are deliberately thin: `/slow` holds a request open so a parallel crawl
  shows up in `/stats`, `/flaky` fails its first N requests per id, and `/hub` /
  `/flaky-hub` fan out to those pages so crawl discovery and retry behavior are observable
  from the server side.
- Integration tests use the site through these helpers:
  - `MockEcServerConfiguration` (`browser4-rest-tests`) — `@Lazy(false)` test configuration
    that boots `MockSiteApplication` on port 0 in a daemon thread, waits up to 60 s for the
    bound port, and publishes it as the `mock.server.port` system property (a `-1` sentinel
    marks a failed start). It reuses an externally started server only when a 1.2 s `/ec/`
    probe returns 2xx/3xx, and clears only the property it set itself on shutdown, so an
    external `-Dmock.server.port` survives.
  - `MockServerPorts` (`pulsar-tests-common`) — `port()` / `baseUrl()` read that property.
    `port()` fails with an actionable error (`set -Dmock.server.port=<port>`) when it is unset
    rather than silently falling back to a default port.
  - `MockEcServerTestBase` (`browser4-rest-tests`) — exposes `mockProductListUrl` /
    `mockProductDetailUrl` and verifies `/ec/` before each test; tagged `IntegrationTest`.
  - `TestUrls` (`pulsar-tests-common`) — `MOCK_PRODUCT_LIST_URL` /
    `MOCK_PRODUCT_DETAIL_URL` are shared, port-aware URL helpers. The same object also
    exposes the non-EC fixture URLs used by the REST tests: `MOCK_HTML_SNAPSHOT_BASE`
    (plus `MOCK_NEWS_URL`, `MOCK_SEO_URL`, `MOCK_JOBS_URL`, `MOCK_COMPLIANCE_URL`,
    `MOCK_RESEARCH_URL`, `MOCK_REAL_ESTATE_URL`), `MOCK_FORM_PAGE_URL`, and
    `MOCK_CRAWL_BASE` (`MOCK_CRAWL_HUB_URL`, `MOCK_CRAWL_PRODUCT_DETAIL_URL`,
    `MOCK_CRAWL_DUP_HUB_URL`).
- The catalog is static test data. No generator script and no regeneration endpoint are
  checked in.

## High-Level Goals
- 1 home page listing 20 category links.
- 20 category (list) pages: 5 products per category, except Electronics (`1292115012`) which has 6 (total products = 101).
- Product detail pages for every listed product.
- Deterministic, reproducible dataset (seeded) to keep IDs stable across runs.
- Clean semantic HTML with unique IDs and reusable classes to aid automated testing / scraping.
- Proper 400 / 404 handling.

## Routes
| Route | Description | Notes |
|-------|-------------|-------|
| GET `/ec/` | Home page with all categories | 20 links: `/ec/b?node={categoryId}`; also reachable as `/ec` |
| GET `/ec/b?node={categoryId}` | Category list page | `node` required; 400 if missing (or blank on the text/HTML handler), 404 if unknown |
| GET `/ec/dp/{productId}` | Product detail page | 404 if product missing or inconsistent category |
| GET `/ec/static/**` | Static assets | Served from classpath `static/ec/static/`; currently only `img/placeholder.png`. `.png` paths get `image/png`, every other path is returned as `application/octet-stream`, so this route is not used for CSS/JS (the list layout's assets load from `/generated/mock-amazon/...`). A missing asset returns 404 with the `#error-page` body |
| GET `/ec/sitemap.xml` | XML sitemap of all discoverable pages | Absolute URLs for `/ec/`, every `/ec/dp/{productId}`, and every `/ec/b?node={categoryId}` (products and categories each sorted by id) so crawl/swarm scenarios can find pages without guessing IDs. URLs are built from the request's scheme/host/port, so they match the calling client's base URL |
| (any other `/ec/*`) | Not found | 404 |

**Handler selection for `/ec/b`:** `EcCategoryController` declares `produces = text/html`,
so ordinary browser requests (and `text/html` test clients) are served by the stock
list-layout renderer. `EcommerceController.category()` also maps `/ec/b` for requests whose
`Accept` header does not select `text/html`. The two handlers differ on a blank `node`:
`EcCategoryController` returns 400 for `null`/blank, while `EcommerceController` returns 404
for an empty id.

## Data Source
Single JSON file:
```
browser4-tests/pulsar-tests-common/src/main/resources/static/generated/mock-amazon/data/products.json
```
Load once at application start; keep immutable in memory. At runtime `CatalogLoader` reads the
same file from the classpath (`/static/generated/mock-amazon/data/products.json`), so the
resources directory remains the single source of truth. If that resource is missing,
`CatalogLoader` fails fast during Spring context startup (an `IllegalStateException` naming the
path) rather than serving an empty catalog. Jackson is configured with
`FAIL_ON_UNKNOWN_PROPERTIES=false`, so extra JSON fields are ignored as the schema evolves;
a missing required field (for example a product without `price`) still fails deserialization
and therefore context startup.

### Startup and request-time failure modes

| Failure | Surfaces at | Behavior |
|---------|-------------|----------|
| `products.json` missing from the classpath | Spring context startup | `CatalogLoader` throws `IllegalStateException` naming the resource path |
| Malformed JSON or a missing required field (for example `price`) | Spring context startup | Jackson deserialization fails and aborts context startup |
| `ec-home.html`, `ec-category.html`, or `ec-product.html` missing | Spring context startup | `HtmlRenderer` bean creation fails with `Template not found: <path>` |
| `list/index.html` missing | First `text/html` request to `/ec/b` | `ListPageRenderer` loads lazily; the request fails with `List template not found at <path>` |
| Unknown `/ec/static/**` asset | Request time | 404 with the shared `#error-page` body |

These paths fail fast (or, for the list template, fail on first use) rather than serving an
empty or partially loaded catalog.

### JSON Structure (Schema)
```
{
  "meta": {
    "version": 1,
    "generatedAt": "2025-01-01T00:00:00Z",
    "seed": 123456789
  },
  "categories": [
    { "id": "1292115012", "name": "Electronics", "slug": "electronics" },
    { "id": "1292115013", "name": "Home", "slug": "home" }
    // ... total 20
  ],
  "products": [
    {
      "id": "B0E000002",
      "name": "Wireless Noise-Cancelling Headphones",
      "categoryId": "1292115012",
      "price": 199.99,                 // sample price, not a project version
      "currency": "USD",
      "image": "/ec/static/img/placeholder.png",
      "rating": 4.4,                   // sample rating, not a project version
      "ratingCount": 312,
      "badges": ["Bestseller"],
      "features": ["Bluetooth 5.2", "30h battery"],  // feature strings, not versions
      "description": "High fidelity wireless headphones.",
      "specs": {"weight": "240g", "color": "Black"},
      "inventory": {"inStock": true, "qty": 42},
      "createdAt": "2025-01-01T00:00:00Z",
      "updatedAt": "2025-01-01T00:00:00Z"
    }
    // ... more
  ]
}
```

### Data Rules
- Exactly 20 distinct categories.
- Each product belongs to exactly one `categoryId` present in categories.
- 101 products total: 5 per category, except Electronics (`1292115012`) which has 6.
- Product IDs unique. Single-letter category codes use `B0` + letter + 6 digits
  (e.g. `B0E000001`), while longer category codes use a 3–4 character code plus a
  3–4 digit sequence (e.g. `B0PET0001`; the Grocery IDs such as `B0GROC001` use three
  digits).
- Prices: positive, formatted with 2 decimals when rendered.
- Deterministic generation: if you implement a generator, seed the RNG (store seed in `meta.seed`).
  The shipped dataset uses seed `123456789`.

### Catalog Snapshot

| Metric | Value |
|--------|-------|
| Categories | 20 |
| Products | 101 (6 in Electronics, 5 in each of the other 19 categories) |
| Products carrying a `Bestseller` badge | 5 |
| `meta.version` | 1 |
| `meta.seed` | `123456789` |

## Implementation Map

### EC file layout

```
browser4-tests/pulsar-tests-common/
├── src/main/kotlin/ai/platon/pulsar/test/server/
│   ├── MockSiteApplication.kt      # Spring Boot entry point
│   └── ec/
│       ├── CatalogModels.kt
│       ├── CatalogLoader.kt
│       ├── CatalogService.kt
│       ├── HtmlRenderer.kt
│       ├── EcommerceController.kt
│       ├── EcControllers.kt
│       └── README.md
├── src/main/resources/config/application-test.properties   # server.port=0, allow-bean-definition-overriding=true
├── src/main/resources/static/generated/mock-amazon/
│   ├── data/products.json
│   ├── ec-home.html
│   ├── ec-category.html
│   ├── ec-product.html
│   ├── list/          # index.html, main.js, style.css, category.js
│   ├── product/       # index.html, main.js, style.css, counter.js
│   └── prompt.md
├── src/main/resources/static/ec/static/img/placeholder.png
└── src/test/kotlin/ai/platon/pulsar/test/
    ├── self/                                  # non-EC fixture-integrity checks
    │   ├── AccessibilityAndDragDropTest.kt
    │   └── InteractivePagesTest.kt
    └── server/
        ├── ec/EcommerceControllerTest.kt
        └── MockSiteLauncherPortTest.kt
```

Only the files needed to understand `/ec` are shown; the other parent-package fixtures are listed below.

Consumer-side test helpers live in `browser4-tests/browser4-rest-tests`:
`MockEcServerConfiguration` and `MockEcServerTestBase` (see Runtime Entry Point and Consumers).

| File | Responsibility |
|------|----------------|
| `../MockSiteApplication.kt` | Spring Boot entry point (`@SpringBootApplication`); activates the `test` profile and scans `ai.platon.pulsar.test.server`, including this package. |
| `CatalogModels.kt` | `Catalog`, `Category`, `Product`, and `CatalogMeta` data classes matching the JSON schema. |
| `CatalogLoader.kt` | Loads `products.json` from the classpath once and builds `categoriesById`, `productsById`, and `productsByCategory` indexes. |
| `CatalogService.kt` | Thin service over the loader: `allCategories()`, `getCategory()`, `getProduct()`, sorted per-category queries, `allProducts()`, and `getBestsellers(limit)` (case-insensitive `Bestseller` badge filter). |
| `HtmlRenderer.kt` | Loads the three `ec-*.html` templates, escapes values, and renders placeholders plus conditional product sections. |
| `EcommerceController.kt` | Primary `/ec` controller: home, category, product, sitemap, static assets, and fallback 404. |
| `EcControllers.kt` | Alternative `EcCategoryController` for `/ec/b` plus `ListPageRenderer`, which renders the stock Amazon-mock list page. |
| `browser4-tests/pulsar-tests-common/src/test/kotlin/ai/platon/pulsar/test/server/ec/EcommerceControllerTest.kt` | Boots the Spring app on a random port; covers home, category/product pages, section variation, and 400/404 handling. |

The Spring app also hosts fixtures outside `/ec`; these files live in the parent package and are
not part of the EC contract:

| File | Responsibility |
|------|----------------|
| `../MockSiteController.kt` | Non-EC demo endpoints listed under Runtime Entry Point. |
| `../HtmlSnapshotMockController.kt` | `/htmlsnapshot-test/*` HTML pages for the htmlsnapshot scenarios, including the `readability-article` fixture used by readability/markdown extraction tests. |
| `../ConcurrencyProbeController.kt` | `/__probe/*` slow, flaky, flaky-hub, and hub pages plus `/stats` and `POST /reset` counters used by crawl/parallelism tests. |
| `../MockSiteLauncher.kt` | Programmatic start/stop of `MockSiteApplication` (`port()`, `baseUrl()`, `awaitReady()`). |
| `../MockSiteStarter.kt` | Availability probe plus auto-start with port fallbacks. |
| `../MockServerPorts.kt` | Resolves `mock.server.port` for consumer tests. |

The crawl fixture site under `src/main/resources/static/generated/crawl/` is served by the same
Spring app at `/generated/crawl/**`: `index.html`, `product/1.html`–`11.html`,
`category/{electronics,accessories,clearance}.html`, `depth3/8.html`–`12.html`, and
`dup/hub.html`. It backs `TestUrls.MOCK_CRAWL_*` and is unrelated to `/ec`.

## Page Templates

Two rendering paths coexist:

### Primary: `EcommerceController` + `HtmlRenderer`

Templates under `browser4-tests/pulsar-tests-common/src/main/resources/static/generated/mock-amazon/`:
- Home: `ec-home.html`
- Category: `ec-category.html`
- Product: `ec-product.html`

Sibling content in the same directory:
- `data/products.json` — the single catalog dataset (see Data Source).
- `list/` (`index.html`, `main.js`, `style.css`, `category.js`) — stock Amazon-mock category
  layout; feeds the alternative `EcCategoryController` + `ListPageRenderer` path and is also
  served as a static test fixture at `/generated/mock-amazon/list/...`. `index.html` references
  only `style.css` and `main.js`; `category.js` is shipped reference material and is not loaded.
- `product/` (`index.html`, `main.js`, `style.css`, `counter.js`) — stock Amazon-mock product
  detail layout; static test fixture only (`/generated/mock-amazon/product/...`), not wired to
  any `/ec` controller. As with the list layout, `counter.js` is not referenced by `index.html`.
- `prompt.md` — the generation prompt used to produce the stock list/product layouts; reference
  material only, not referenced by any `/ec` controller (it is still reachable as a static file
  under `/generated/mock-amazon/`).

Placeholders use `{{VARIABLE}}` syntax for scalar values and `<!--BLOCK_NAME-->` HTML comments
for repeated/multi-line content injection.

**Home page placeholders:** `<!--CATEGORY_LINKS-->`, `<!--FEATURED_PRODUCTS-->`,
`<!--TRENDING_SEARCHES-->`, `<!--CATEGORY_SPOTLIGHTS-->`, `{{TITLE}}`

**Category page placeholders:** `{{CATEGORY_ID}}`, `{{CATEGORY_NAME}}`, `{{RESULT_COUNT}}`,
`{{CATEGORY_SUMMARY}}`, `<!--FILTER_CHIPS-->`, `<!--RELATED_CATEGORY_LINKS-->`,
`<!--SPONSORED_PRODUCTS-->`, `<!--PRODUCT_LIST-->`, `{{TITLE}}`

**Product page placeholders:** `{{PRODUCT_ID}}`, `{{PRODUCT_NAME}}`, `{{PRODUCT_PRICE}}`,
`{{PRODUCT_RATING}}`, `{{PRODUCT_RATING_COUNT}}`, `{{PRODUCT_IMAGE}}`,
`{{PRODUCT_CATEGORY_ID}}`, `{{PRODUCT_CATEGORY_NAME}}`, `{{PRODUCT_DESCRIPTION}}`,
`{{PRODUCT_STOCK_STATUS}}`, `<!--VISIT_SIGNAL-->`, `<!--INFO_PILLS-->`,
`<!--DETAILS_SECTION-->`, `<!--SPECS_SECTION-->`, `<!--BUYBOX_META-->`, `<!--BADGES-->`,
`<!--SECONDARY_GRID-->`, `<!--RECOMMENDATIONS_SECTION-->`, `<!--COMMENTS_SECTION-->`,
`{{TITLE}}`

### Alternative: `EcCategoryController` + `ListPageRenderer`

Renders category pages from `list/index.html` using direct string replacement on the stock
Amazon-mock layout. Rewrites `style.css` / `main.js` references to
`/generated/mock-amazon/list/...` so assets load correctly under `/ec/b`, replaces the
`#product-list` content (marking it `data-server-rendered="true"`; the stock `product-list`
class is kept, while the primary renderer uses `product-grid`), and updates the page
`<title>`.

This path reads `CatalogLoader` directly instead of going through `CatalogService`, so cards
are rendered in JSON order rather than by an explicit id sort. The shipped `products.json`
is already id-ordered within each category, so both paths currently agree; a regenerated
dataset that is not id-ordered would make the two renderers list products in different
orders (`CatalogService.getProductsByCategory` sorts by id).

The injected cards deliberately mirror the primary renderer's structure: `article.product-card`,
`h2.product-title`, `span.product-price`, and an image carrying both `product-image` and the
legacy `product-img` class. This keeps tag-qualified X-SQL selectors working regardless of
which handler serves `/ec/b`.

The stock list layout has no `#category-page` wrapper or `data-category-id` on `<main>`, so
`#category-page[data-category-id]` is primary-renderer-only. On the list path, scope by the
cards' `data-category-id` or by the `node` query parameter instead.

Both controllers respond at `/ec/b?node={categoryId}` — Spring's default ambiguity resolution
applies (the more specific `produces` match on `EcCategoryController` takes priority for
`text/html` requests).

> **CRITICAL REQUIREMENT: DO NOT ALTER THE TEMPLATE LAYOUT, EXISTING JAVASCRIPT, OR CSS—ONLY INJECT DYNAMIC PRODUCT DATA INTO PLACEHOLDERS.**

## Rendering Requirements
### Common
- UTF-8 output.
- `<title>` reflects page context: `Mock EC Home`, `Category: Electronics`, or
  `Product: Wireless Noise-Cancelling Headphones`.
- Include canonical-like structure for consistent scraping.
- Stable, descriptive IDs (unique per page) and reusable classes for selectors.
- Product images: if the product's `image` field is blank or `placeholder.png`, the renderer falls back to `https://picsum.photos/seed/{hash}/200/140`.

### Suggested ID / Class Conventions
- Home: `#category-list`, items: `li.category-item[data-category-id]`, link id: `cat-link-{categoryId}`.
- Category Page wrapper: `#category-page[data-category-id]` (primary renderer; the stock list
  layout has no such wrapper — see the list-path note under Page Templates).
- Product grid: `#product-list.product-grid` (primary renderer); the list path keeps the stock
  `#product-list.product-list` container class.
- Product cards: `article.product-card#product-{productId}[data-category-id][data-product-id]` (both renderers).
- Inside card: `h2.product-title`; price: `span.product-price#product-price-{id}[data-product-id]`;
  rating: `#product-rating-{id}[data-rating]` (`span` on the primary renderer, `div` on the list
  renderer); badge container: `.product-badges`.
- Primary card extras: `.product-meta`, `.product-copy`, `.feature-pills` / `.feature-pill`,
  `.product-ops`, `.stock-note`, `.secondary-link`; the list renderer keeps the legacy
  `img.product-img` class alongside `.product-image`.
- Product Detail root: `#product-page[data-product-id][data-category-id]`.
- Product image: `#product-image.product-image`.
- Detail fields: `#productTitle` (h1), `#product-price`, `#product-rating`, `#product-rating-count`, `#product-category-link`, features list `#product-features`, specs table `#product-specs`.
- Use `alt` attributes for images: `alt="{name}"`.

### Accessibility / Semantics
- Use `<nav>` for category navigation on home.
- Use `<section>` / `<article>` for product listings.
- Provide `<ul>` for feature lists; `<table>` only for tabular specs.

### Product Page Enrichment (Conditional Sections)

Product detail pages include multiple optional sections that appear based on product
attributes and a deterministic `sectionRoll()` hash, ensuring reproducible variation
across products for realistic scraping/test scenarios:

| Section | Rendered when | Template placeholder |
|---------|---------------|---------------------|
| Visit signal | Popular product or roll < 42 | `<!--VISIT_SIGNAL-->` |
| Info pills | Premium (≥$80) or roll < 74 | `<!--INFO_PILLS-->` |
| Feature section | Has features AND (price ≥$20 or roll < 30) | `<!--DETAILS_SECTION-->` |
| Specs table | Has specs AND (premium or roll < 58) | `<!--SPECS_SECTION-->` |
| Buybox meta | Low stock, premium, or roll < 86 | `<!--BUYBOX_META-->` |
| FAQ section | Has specs AND (popular or roll < 44) | `<!--SECONDARY_GRID-->` |
| Seller notes | Low stock, premium, or roll < 78 | `<!--SECONDARY_GRID-->` |
| Recommendations | Has related products AND (popular or roll < 68) | `<!--RECOMMENDATIONS_SECTION-->` |
| Customer comments | Popular, premium, or roll < 57 | `<!--COMMENTS_SECTION-->` |

A product is "popular" when `ratingCount >= 150` or it has a "Bestseller" badge.
"Premium" means `price >= $80.00` (a rendering threshold, not a version). "Low stock" means
`qty` between 1–20.

The `sectionRoll(product, salt)` function hashes `{productId}:{categoryId}:{salt}`
and takes the result modulo 100, producing a stable integer in [0, 99] per product+section
pair — same product always gets the same sections.

**Stock messages** come from `stockMessage()`: `inStock = false` → "Currently unavailable";
`qty < 15` → "Only N left in stock"; `15 <= qty < 60` → "In stock (N available)";
`qty >= 60` → "Ready to ship"; missing `qty` → "In stock". `inStock` accepts a boolean or a
string (a string counts as true only when it equals `true`, case-insensitively); when absent
it falls back to `qty > 0`, and defaults to `true` when `qty` is also absent. `qty` may be a
JSON number or a numeric string — anything else is treated as missing.

### Home and Category Page Sections

Home page also includes dynamic sections: featured products (up to 6 products carrying the
`Bestseller` badge — 5 in the current dataset), trending search links (from featured product
names), and category spotlights (first 4 categories shown as buying-guide cards).

Category pages add filter chips (`Top rated`, `Ready to ship`, `From $min`, `Under $max`),
up to six sibling-category links, and up to three sponsored recommendations drawn from
`getBestsellers(12)` while excluding the current category. Sponsored and related cards use
`article.recommendation-card`; product pages render up to four related products.

## Error Handling
| Scenario | Status | Response |
|----------|--------|----------|
| Missing `node` on `/ec/b` (both handlers), or blank on the text/HTML handler | 400 | Simple HTML: "Missing category parameter" |
| Blank `node=` on the non-HTML handler | 404 | "Category not found" |
| Unknown category | 404 | "Category not found" |
| Unknown product | 404 | "Product not found" |
| Product's category missing from the catalog (should not happen) | 404 | "Product not found" |
| Any other `/ec/*` | 404 | Standard not found |

Keep error pages lightweight, also with a unique id: `#error-page` and a class `error-code-404` etc.

## Validation / Test Checklist
Automated or manual tests should assert:
1. GET `/ec/` returns 200 and contains 20 links with `cat-link-` IDs.
2. Each category link resolves (200) and only shows products whose cards have `data-category-id` matching the `node` param.
3. Each product card link resolves (200) and product detail page contains matching `#product-page[data-product-id]`.
4. Invalid category (`/ec/b?node=NOPE`) returns 404.
5. Missing node (`/ec/b`) returns 400.
6. Invalid product (`/ec/dp/DOESNOTEXIST`) returns 404.
7. All prices show two decimals (regex: `\$\d+\.\d{2}`).
8. No duplicate IDs in any page (spot check by parsing DOM or regex + set logic).
9. Total product count = 101 across 20 categories: Electronics has 6 products and every other category has 5.
10. GET `/ec/sitemap.xml` returns 200 XML containing `/ec/` plus every product (`/ec/dp/{id}`) and category (`/ec/b?node={id}`) URL exactly once; products and categories are sorted by id. The current catalog produces 122 `<loc>` entries (1 home + 101 products + 20 categories).
11. GET `/ec` (no trailing slash) returns the same home page (200).
12. `/ec/b?node=1292115012` cards use the tag-qualified selectors `article.product-card`, `h2.product-title`, and `span.product-price` on both rendering paths; the container is `#product-list.product-grid` (primary) or `#product-list.product-list` (list path).
13. `/ec/static/img/placeholder.png` returns 200; an unknown asset returns 404 with `#error-page`.

## Automated Tests

### Controller test

`browser4-tests/pulsar-tests-common/src/test/kotlin/ai/platon/pulsar/test/server/ec/EcommerceControllerTest.kt`
is tagged `TestInfraCheck` and boots `MockSiteApplication` on a random port with
`@SpringBootTest`. `TestInfraCheck` is excluded by the PR and CI gates, so this suite runs in
the nightly gate or when invoked directly:

```
mvn test -pl :pulsar-tests-common -am -Dtest=EcommerceControllerTest -Dsurefire.failIfNoSpecifiedTests=false
```

| Test | Asserts |
|------|---------|
| `home` | `/ec/` is 200 HTML with `#category-list` and a known `cat-link-1292115012` |
| `homeWithoutTrailingSlash` | `/ec` returns the same home page |
| `categoryPage` | `/ec/b?node=1292115012` is 200, cards carry `data-category-id`, and `article.product-card`, `h2.product-title`, `span.product-price`, and `product-image` are present |
| `productPage` | `/ec/dp/B0E000001` is 200 and contains `data-product-id` and `product-price` |
| `productPagesVaryOptionalSections` | sampled product pages differ in comments, FAQ, and recommendations |
| `missingCategoryParam` | `/ec/b` is 400 with `Error 400` |
| `invalidCategory` | `/ec/b?node=DOES_NOT_EXIST` is 404 with `Error 404` |
| `invalidProduct` | `/ec/dp/DOES_NOT_EXIST` is 404 with `Error 404` |

The suite does **not** assert the 20-link count, per-category counts, two-decimal prices,
duplicate IDs, the sitemap, static assets, or both category renderers. Those remain manual
checklist items: 1–3, 7–10, 12, and 13. Ordinary `/ec/b` requests exercise
`EcCategoryController` + `ListPageRenderer`; the primary renderer's category output is not
compared against it.

### Consumer integration tests

`MockEcServerTestBase` (tagged `IntegrationTest`) starts the site through
`MockEcServerConfiguration` and verifies `/ec/` before each test. `ScrapeServiceTests` uses
`/ec/dp/B0E000001`, not the 101-product list page, because the list page takes about 32 s per
fetch on a loaded machine while the detail page takes about 3 s.

### Launcher and starter tests

`browser4-tests/pulsar-tests-common/src/test/kotlin/ai/platon/pulsar/test/server/MockSiteLauncherPortTest.kt`
is tagged `TestInfraCheck` and pins the port contract described under Runtime Entry Point:
`MockSiteLauncher.start(port = ...)` binds the requested port, and `MockSiteStarter.start(url)`
extracts the port from a page URL and starts the site there. Both tests pick a free port with
`ServerSocket(0)` and stop the launcher afterwards:

```
mvn test -pl :pulsar-tests-common -am -Dtest=MockSiteLauncherPortTest -Dsurefire.failIfNoSpecifiedTests=false
```

### Sibling fixture-integrity tests

Two `TestInfraCheck` suites in `src/test/kotlin/ai/platon/pulsar/test/self/` guard the
non-EC static fixtures under `src/main/resources/static/generated/`. They are pure resource
assertions — neither starts `MockSiteApplication` — and are not part of the `/ec` contract:

- `InteractivePagesTest` loads `interactive-elements-index.json` and checks that every indexed
  `id` / `data-testid` exists in its page, that ids and test ids are unique per file, and that
  each indexed page's `<header>` / `<section>` elements with explicit ids are present in the
  index. It also pins the stable selectors and `window.__browser4State` hook on
  `form-filling.html`.
- `AccessibilityAndDragDropTest` checks the `interactive-1.html`–`interactive-4.html` and
  `interactive-screens.html` fixtures for `aria-label` on `data-action` buttons,
  `aria-expanded` / `aria-controls` on the toggle buttons, `role="region"` on the hidden
  message, and contiguous `data-order` values in the drag-and-drop list.

The fixtures themselves are catalogued in
`src/main/resources/static/generated/README.md`, with per-page interaction maps in
`src/main/resources/static/generated/INTERACTIVE-README.md`.

### Build and coverage

`pulsar-tests-common` is a shared test-support library: its `main` classes are exercised from
other modules' test JVMs, so this module's own `jacoco.exec` records ~0 coverage by
construction. `browser4-tests/pulsar-tests-common/pom.xml` therefore sets `<skip>true</skip>`
on the JaCoCo `check` execution; the agent and report still run, so the numbers are produced
and uploaded. The coverage floor applies to the real code modules, not to this bundle —
failing it here is expensive because every module that depends on this one is skipped for the
rest of the reactor. The module also packages a `test-jar` (the `maven-jar-plugin` `test-jar`
goal) so consumer modules can reuse its test-support classes.

## Optional Enhancements (Do NOT block MVP)
- Query pagination: `/ec/b?node=1292115012&page=2` (deterministic sort by product ID).
- Simple search: `/ec/search?q=headphones`.
- Badge filtering or price range.
- Regeneration endpoint (dev only) to rebuild JSON with same seed or new seed.

## Logging
- On startup: log categories count, product count, seed.
- On 404/400: concise log line with path + reason.

## Done Definition
- All required routes implemented.
- Data served purely from JSON (no hardcoded product logic except generation step if included).
- Acceptance checklist passes.
- Deterministic repeatable product set.
- Semantic, test-friendly HTML.

## Quick Implementation Steps
1. Create (or generate) the JSON data file with categories & products.
2. Implement `CatalogLoader` to parse JSON into `Catalog` data classes and build in-memory indexes (`categoriesById`, `productsById`, `productsByCategory`) plus `allProducts()` lookup.
3. Implement `CatalogService` wrapper with `allCategories()`, sorted product queries, `allProducts()`, and `getBestsellers(limit)`.
4. Implement `HtmlRenderer` to load templates and perform placeholder replacement (`{{VAR}}` + `<!--BLOCK-->`). The product page includes conditionally rendered sections (visit signal, info pills, buybox meta, specs, FAQ, seller notes, customer comments, recommendations) gated by `sectionProfile()` which uses deterministic `sectionRoll()` for reproducible page variation.
5. Implement `EcommerceController` route handlers for `/ec/`, `/ec/b`, `/ec/dp/{id}`, `/ec/static/**`, `/ec/sitemap.xml`, and fallback 404. Home page passes bestseller products to the renderer.
6. Optionally implement `EcCategoryController` + `ListPageRenderer` as an alternative category-page rendering path using the stock Amazon-mock list template.
7. Add error responses with `#error-page` and `error-code-{status}` conventions.
8. Verify with test checklist.

## Seeds & Determinism

The data is pre-generated and stored as a static JSON file (`products.json`). The seed
is stored in `meta.seed` for traceability. Product data is loaded once at startup and
held immutable — no runtime generation occurs. No generator script is checked in; the
sketch below is a recipe for a one-off regeneration, not an executable file.

If you need to regenerate the dataset, use a deterministic approach:
```
val rng = Random(123456789L)
val categoryIds = listOf("1292115012", ... total 20 ...)
// For each category: generate 5 products (6 for Electronics) using the seeded RNG
// Product ID: "B0" + category code + zero-padded sequence, e.g. B0E000001 / B0GROC001
```
Store the new seed inside JSON `meta` for traceability.

## Maintenance Notes
- Project version references come from the root `pom.xml` (currently `4.16.1-SNAPSHOT`, also
  declared in this module's parent block); the numeric literals in the catalog example
  (`199.99`, `4.4`, `5.2`, `80.00`) are product data or rendering thresholds, not version
  references — see the table under "Version scope". Update the version line there when the
  reactor version changes.
- If schema evolves, bump `meta.version` and handle backward compatibility in loader. The loader
  already ignores unknown JSON fields (`FAIL_ON_UNKNOWN_PROPERTIES=false`); removing or renaming
  a required field still needs a matching `CatalogModels` change.
- Avoid large images; placeholders or data URIs acceptable.
- Keep the two category renderers selector-compatible: `ListPageRenderer`'s cards must keep
  `article.product-card`, `h2.product-title`, `span.product-price`, and an image carrying
  `product-image` (plus the legacy `product-img`) so X-SQL selectors work on both paths.
- If the catalog size or per-category distribution changes, update the counts in this README,
  `EcommerceControllerTest`, and consumer tests such as `ScrapeServiceTests` (which notes the
  101-product list page).

---
This document supersedes the previous minimal instructions and provides a precise, testable contract for the mock ecommerce site implementation.

*Last reviewed: 2026-11-03 against Browser4 `4.16.1-SNAPSHOT`; catalog `meta.version=1`, seed
`123456789`, 20 categories, 101 products (6 in Electronics), 5 `Bestseller` badges. EC file
layout, route table, `/ec` template placeholder lists, static assets, the test-profile
properties, and the launcher/starter behavior and test coverage were re-checked against the
sources on the same date; the parent-package `/htmlsnapshot-test/*`, `/__probe/*`, and
`/generated/crawl/**` fixture routes, the `TestUrls` helpers, the module POM's JaCoCo
`check` exemption and `test-jar` packaging, and the `self/` fixture-integrity suites and the
`static/generated/` fixture documentation were re-checked too. `MockEcServerConfiguration`'s
lazy initialization, 60 s startup wait, `/ec/` reuse probe, and owned-property cleanup, plus
the catalog/template fail-fast paths, were re-checked as well. The reactor version was
re-confirmed against the root `pom.xml` and this module's parent block; the four fixture
literals called out under "Version scope" are not version references.*
