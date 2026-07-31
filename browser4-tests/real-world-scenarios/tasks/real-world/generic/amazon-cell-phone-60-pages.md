# amazon-cell-phone-60-pages

1. Go to https://www.amazon.com/
2. Search for: cell phone
3. Starting from page 1, visit all search result pages up to page 60.
   - On each page, capture the product titles, prices, ratings, and URLs.
   - Use pagination (the "Next" button or page number links) to advance.
4. For each page visited, save the extracted data immediately so no progress is lost.
5. Export all collected data to a directory under `./target/amazon-cell-phones/`:
   - One file per page (e.g. `page-001.md`, `page-002.md`, …).
   - A summary file `index.md` listing total products found per page and overall totals.
6. After exporting, verify:
   - All 60 pages were visited.
   - The export directory exists and contains 61 files (60 page files + index.md).
   - The summary index contains accurate totals.
