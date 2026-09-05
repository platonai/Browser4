# Mock Ecommerce Site Specification (Dynamic Rendering)

## Prerequisite

Read `AGENTS.md` in the project root to guide your actions.

## Purpose
Implement a fully dynamic mock ecommerce website served under the `/ec` path using `MockSiteApplication.kt`.
All pages (home, category/list, product) must be rendered server-side from a **single JSON data file** loaded once at startup.

## High-Level Goals
- 1 home page listing 20 category links.
- 20 category (list) pages, each showing exactly 5 products (total products = 100).
- Product detail pages for every listed product.
- Deterministic, reproducible data generation (seeded) to keep IDs stable across runs.
- Clean semantic HTML with unique IDs and reusable classes to aid automated testing / scraping.
- Proper 400 / 404 handling.

## Routes
| Route | Description | Notes |
|-------|-------------|-------|
| GET `/ec/` | Home page with all categories | 20 links: `/ec/b?node={categoryId}` |
| GET `/ec/b?node={categoryId}` | Category list page | `node` required; 400 if missing, 404 if unknown |
| GET `/ec/dp/{productId}` | Product detail page | 404 if product missing or inconsistent category |
| GET `/ec/static/*` | Optional static assets (images/css) | Can serve from classpath |
| GET `/ec/sitemap.xml` | XML sitemap of all discoverable pages | Absolute URLs for `/ec/`, every `/ec/dp/{productId}`, and every `/ec/b?node={categoryId}` (sorted by id) so crawl/swarm scenarios can find pages without guessing IDs |
| (any other `/ec/*`) | Not found | 404 |

## Data Source
Single JSON file (example path):
```
browser4-tests/pulsar-tests-common/src/main/resources/static/generated/mock-amazon/data/products.json
```
Load once at application start; keep immutable in memory.

### JSON Structure (Schema)
```
{
  "meta": {
    "version": 1,
    "generatedAt": "2025-01-01T00:00:00Z",
    "seed": 12345
  },
  "categories": [
    { "id": "1292115012", "name": "Electronics", "slug": "electronics" },
    { "id": "1292115013", "name": "Home", "slug": "home" }
    // ... total 20
  ],
  "products": [
    {
      "id": "B08PP5MSVB",
      "name": "Wireless Noise-Cancelling Headphones",
      "categoryId": "1292115012",
      "price": 199.99,
      "currency": "USD",
      "image": "/ec/static/img/placeholder.png",
      "rating": 4.4,
      "ratingCount": 312,
      "badges": ["Bestseller"],
      "features": ["Bluetooth 5.2", "30h battery"],
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
- Exactly 5 products per category (100 total).
- Product IDs unique (Amazon-like IDs ok, e.g. `B0...`).
- Prices: positive, formatted with 2 decimals when rendered.
- Deterministic generation: if you implement a generator, seed the RNG (store seed in `meta.seed`).

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
  served as a static test fixture.
- `product/` (`index.html`, `main.js`, `style.css`, `counter.js`) — stock Amazon-mock product
  detail layout; static test fixture only, not wired to any `/ec` controller.

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
Amazon-mock layout. Fixes relative asset paths so CSS/JS load correctly under `/ec/b`,
replaces the product list `<div>` content, and updates the page `<title>`.

Both controllers respond at `/ec/b?node={categoryId}` — Spring's default ambiguity resolution
applies (the more specific `produces` match on `EcCategoryController` takes priority for
`text/html` requests).

> **CRITICAL REQUIREMENT: DO NOT ALTER THE TEMPLATE LAYOUT, EXISTING JAVASCRIPT, OR CSS—ONLY INJECT DYNAMIC PRODUCT DATA INTO PLACEHOLDERS.**

## Rendering Requirements
### Common
- UTF-8 output.
- `<title>` reflects page context: `Category: Electronics` or `Product: Wireless Noise-Cancelling Headphones`.
- Include canonical-like structure for consistent scraping.
- Stable, descriptive IDs (unique per page) and reusable classes for selectors.
- Product images: if the product's `image` field is blank or `placeholder.png`, the renderer falls back to `https://picsum.photos/seed/{hash}/200/140`.

### Suggested ID / Class Conventions
- Home: `#category-list`, items: `li.category-item[data-category-id]`, link id: `cat-link-{categoryId}`.
- Category Page wrapper: `#category-page[data-category-id]`.
- Product grid: `#product-list.product-grid`.
- Product cards: `article.product-card#product-{productId}[data-category-id]`.
- Inside card: `h2.product-title`, price span: `span.product-price#product-price-{id}[data-product-id]`, rating: `span.product-rating#product-rating-{id}[data-rating]`, badge container: `.product-badges`.
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
"Premium" means `price >= $80.00`. "Low stock" means `qty` between 1–20.

The `sectionRoll(product, salt)` function hashes `{productId}:{categoryId}:{salt}`
and takes the result modulo 100, producing a stable integer in [0, 99] per product+section
pair — same product always gets the same sections.

Home page also includes dynamic sections: featured products (top 6 bestsellers),
trending search links (from featured product names), and category spotlights
(first 4 categories shown as buying-guide cards).

## Error Handling
| Scenario | Status | Response |
|----------|--------|----------|
| Missing `node` param on `/ec/b` | 400 | Plain text or simple HTML: "Missing category parameter" |
| Unknown category | 404 | "Category not found" |
| Unknown product | 404 | "Product not found" |
| Product exists but not in data (should not happen) | 404 | Same as unknown |
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
9. Total product count = 100; exactly 5 products per category.
10. GET `/ec/sitemap.xml` returns 200 XML containing `/ec/` plus every product (`/ec/dp/{id}`) and category (`/ec/b?node={id}`) URL exactly once.

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
2. Implement `CatalogLoader` to parse JSON into `Catalog` data classes and build in-memory indexes (`byId`, `byCategory`) plus `allProducts()` lookup.
3. Implement `CatalogService` wrapper with sorted product queries, `allProducts()`, and `getBestsellers(limit)`.
4. Implement `HtmlRenderer` to load templates and perform placeholder replacement (`{{VAR}}` + `<!--BLOCK-->`). The product page includes conditionally rendered sections (visit signal, info pills, buybox meta, specs, FAQ, seller notes, customer comments, recommendations) gated by `sectionProfile()` which uses deterministic `sectionRoll()` for reproducible page variation.
5. Implement `EcommerceController` route handlers for `/ec/`, `/ec/b`, `/ec/dp/{id}`, `/ec/static/**`, and fallback 404. Home page passes bestseller products to the renderer.
6. Optionally implement `EcCategoryController` + `ListPageRenderer` as an alternative category-page rendering path using the stock Amazon-mock list template.
7. Add error responses with `#error-page` and `error-code-{status}` conventions.
8. Verify with test checklist.

## Seeds & Determinism

The data is pre-generated and stored as a static JSON file (`products.json`). The seed
is stored in `meta.seed` for traceability. Product data is loaded once at startup and
held immutable — no runtime generation occurs.

If you need to regenerate the dataset, use a deterministic approach:
```
val rng = Random(seed)
val categoryIds = listOf("1292115012", ... total 20 ...)
// For each category: generate products using seeded RNG
// Product ID: 'B' + category-specific char + 5-digit zero-padded number
```
Store the new seed inside JSON `meta` for traceability.

## Maintenance Notes
- If schema evolves, bump `meta.version` and handle backward compatibility in loader.
- Avoid large images; placeholders or data URIs acceptable.

---
This document supersedes the previous minimal instructions and provides a precise, testable contract for the mock ecommerce site implementation.
