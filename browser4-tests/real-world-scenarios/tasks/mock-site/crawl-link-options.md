# crawl-link-options

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario exercises the `crawl` command's link discovery options: depth control, CSS selectors, URL patterns, and seed files. All crawl pages are served from `http://localhost:18080/generated/crawl/`.

## Acceptance Criteria

1. **AC1 — Basic crawl (depth 0):** Running `crawl <url> --depth 0 --refresh` fetches exactly one page with no link discovery.
2. **AC2 — Link selector + pattern:** Running `crawl <url> -d 2 -ol "a.product" -olp "/product/"` follows only product-class links whose URLs match `/product/`. Category links (class `category-link`) are not followed.
3. **AC3 — Deep crawl (depth 3):** Running `crawl <url> --depth 3 --refresh` traverses 4 levels (0–3) and reaches terminal depth-3 pages containing "Deep Widget".
4. **AC4 — Seed file crawl:** Running `crawl --seed-file <path> --depth 0 --refresh` fetches only the URLs listed in the file, with no additional link discovery.

## Steps

### 1. Basic crawl — depth 0 (AC1)

Run a crawl against the crawl test hub page with depth 0 (fetch only, no link following):

```
crawl http://localhost:18080/generated/crawl/index.html --depth 0 --refresh
```

Wait for the crawl to complete. Verify:
- The output contains "Crawl completed" or similar completion message.
- The results include "Crawl Test Hub" (the page title).
- The results show exactly 1 page found (or a small number if using background mode).
- No "Widget Alpha" product detail page content appears (depth 0 means no link following).

### 2. Crawl with out-link selector and pattern — depth 2 (AC2)

Run a crawl with link discovery filtering. The hub page has `<a class="product" href="product/...">` links and `<a class="category-link" href="category/...">` links:

```
crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"
```

Wait for the crawl to complete. Verify:
- The output contains a completion message.
- Pages with "/product/" in their URL path were discovered and fetched (e.g., Widget Alpha, Widget Beta, Widget Gamma).
- "Electronics Category" content may be absent or present depending on whether product pages link back to it — but pages NOT matching `-ol "a.product"` (the category page linked with `class="category-link"`) should not be followed from the hub.
- Results include depth-2 pages reachable through product links (e.g., Widget Delta, Widget Zeta).
- The total crawled page count is greater than 1, confirming link discovery occurred.

### 3. Deep crawl — depth 3 with refresh (AC3)

Run a crawl with depth 3 to verify multi-level traversal reaches terminal depth-3 pages:

```
crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh
```

Wait for the crawl to complete. Verify:
- The output contains a completion message.
- The results include at least one "Deep Widget" page (Theda or Iota at depth 3).
- The results include intermediate pages from depth 1 and 2.
- The total crawled page count suggests multi-level traversal (several pages from different depths).

### 4. Seed file crawl — depth 0 (AC4)

Create a seed file containing two specific product URLs (no hub page — seed file only, no direct URL argument):

- `http://localhost:18080/generated/crawl/product/1.html`
- `http://localhost:18080/generated/crawl/product/3.html`

Run the crawl:

```
crawl --seed-file <path-to-seed-file> --depth 0 --refresh
```

Wait for the crawl to complete. Verify:
- The output shows "URLs: 2" (confirming both seed URLs were resolved).
- The results include content from both Widget Alpha and Widget Gamma.
- No "Widget Beta" content appears (it was not in the seed file).
- No "Crawl Test Hub" content appears (the hub was not in the seed file).
