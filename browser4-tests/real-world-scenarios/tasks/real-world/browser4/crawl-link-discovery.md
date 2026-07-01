# crawl-link-discovery

1. Crawl `http://books.toscrape.com/` with a depth of 1 to discover links from the main page.
2. When crawling, use `--out-link-selector` to extract only links matching the CSS selector for book-related pages (e.g., links containing "catalogue").
3. Apply an `--out-link-pattern` regex to further filter the extracted links (e.g., only URLs matching `.*catalogue.*`).
4. Limit the number of extracted links per page with `--top-links 10`.
5. Run the crawl and output the results in table format.
6. Re-run the same crawl but output as CSV format, saving to a file with `--output`.
7. Re-run the same crawl but output as JSON format.
8. Create a seed file with 2-3 known book detail page URLs, then use `crawl --seed-file` to crawl just those URLs.
9. List any active or completed crawl tasks to verify the crawl history.
