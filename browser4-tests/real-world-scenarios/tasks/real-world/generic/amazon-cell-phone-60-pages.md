# Amazon Cell Phone — 60-Page Collection

Goal: collect all 60 pages of Amazon cell phone search results as fast as possible, then export the full HTML contents.

## Phase 1 — Fast parallel collection

1. Navigate to https://www.amazon.com/ and search for **cell phones**.
2. Open the first page of search results.
3. Collect all product detail-page URLs from the 60 search-result pages. Open multiple result pages in parallel using the pagination URL pattern (e.g., `&page=N`) to skip clicking "Next" one-by-one.
4. From each result page, extract the links to individual product detail pages.

## Phase 2 — Fast parallel detail-page collection

5. Open all collected product detail pages in parallel across multiple concurrent tabs.
6. For each product detail page, save the full HTML.

## Phase 3 — Export

7. Export all saved HTML documents into a single archive or directory for downstream processing.
