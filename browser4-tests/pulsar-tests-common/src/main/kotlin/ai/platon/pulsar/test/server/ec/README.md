# Mock Ecommerce Site Specification (Dynamic Rendering)

## Prerequisite

Read `README-AI.md` in the project root to guide your actions.

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
| (any other `/ec/*`) | Not found | 404 |

## Data Source
Single JSON file (example path):
```
/browser4-tests-common/src/main/resources/static/generated/mock-amazon/data/products.json
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

The primary `EcommerceController` + `HtmlRenderer` uses these templates under
`/browser4-tests-common/src/main/resources/static/generated/mock-amazon/`:
- Home: `ec-home.html`
- Category: `ec-category.html`
- Product: `ec-product.html`

Placeholders use `{{VARIABLE}}` syntax for scalar values and `<!--BLOCK_NAME-->` HTML comments
for repeated/multi-line content injection.

An alternative renderer (`ListPageRenderer`) also renders category pages from
`list/index.html` using direct string replacement on the stock Amazon-mock layout.

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
2. Implement `CatalogLoader` to parse JSON into `Catalog` data classes and build in-memory indexes (`byId`, `byCategory`).
3. Implement `CatalogService` wrapper with sorted product queries.
4. Implement `HtmlRenderer` to load templates and perform placeholder replacement (`{{VAR}}` + `<!--BLOCK-->`).
5. Implement `EcommerceController` route handlers for `/ec/`, `/ec/b`, `/ec/dp/{id}`, `/ec/static/**`, and fallback 404.
6. Add error responses with `#error-page` and `error-code-{status}` conventions.
7. Verify with test checklist.

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
