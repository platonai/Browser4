# crawl-advanced-extraction

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

1. Create a seed file with several MockSite e-commerce product URLs. Use real product IDs from the Electronics category:
   - `http://localhost:18080/ec/dp/B0E000001` (4K OLED TV)
   - `http://localhost:18080/ec/dp/B0E000002` (Wireless Headphones)
   - `http://localhost:18080/ec/dp/B0E000003` (Bluetooth Speaker)
2. Write an X-SQL query to a file that extracts the product title and price from each page.
3. Run a crawl using the seed file with depth 0 (fetch only, no link following), applying the X-SQL query via `--sql @file`, with these options:
   - `--refresh` to force fresh fetches
   - `--parse` to enable parsing
   - `--expires 1h` to set cache duration
   - `--store-content` to persist the fetched HTML
   - `--priority` set to a high value (low number) for urgent processing
   - `--page-load-timeout 30s` for a reasonable timeout
4. Run the same crawl but with `--background` for asynchronous execution. Note the task ID.
5. While the background crawl runs, list crawl tasks to see its status.
6. Run another crawl with `--ignore-url-query` to strip query parameters from URLs.
7. Run a crawl with `--no-norm` to disable URL normalization and observe the difference.
8. Run a crawl with `--readonly` to ensure no destructive operations occur.
9. Check the crawl list to see all completed, running, and queued tasks. Wait for background tasks to complete.
