# crawl-sql-formats

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

This scenario exercises X-SQL extraction during crawl, covering the `--sql @file` and `--sql-stdin` input methods along with CSV and table output formats. All crawl pages are served from `http://localhost:18080/generated/crawl/`.

Each product page contains `<h1 id="productTitle">` for the title and `<p id="product-price" class="price">` for the price — these are the targets for X-SQL extraction.

## Acceptance Criteria

5. **AC5 — SQL from file with CSV output:** Running `crawl --seed-file <path> --sql @extract.sql --format csv -o results.csv` produces a CSV file containing the extracted product title and price for each seed URL.
6. **AC6 — SQL from stdin with table format:** Running `crawl --seed-file <path> --sql-stdin --format table < query.sql` displays a table-formatted extraction result on stdout. The output is identical in content to the equivalent `--sql @file` invocation.

## Steps

### 1. Prepare seed file and SQL query

Create a seed file containing two product detail page URLs that have X-SQL-extractable data (`#productTitle` and `#product-price`):

- `http://localhost:18080/generated/crawl/product/1.html`
- `http://localhost:18080/generated/crawl/product/2.html`

Write an X-SQL extraction query to a file (`extract.sql`) that selects the product title and price from each page. Use X-SQL functions like `dom_first_text(dom, '#productTitle')` and `dom_first_text(dom, '#product-price')` with `@url` as the page URL placeholder.

### 2. SQL from file with CSV output (AC5)

Run a crawl using the seed file and the SQL query file, with CSV output written to a file:

```
crawl --seed-file <path-to-seed-file> --sql @extract.sql --format csv -o results.csv
```

Wait for the crawl to complete. Verify:
- The output or completion message indicates the crawl finished (e.g., "Crawl completed", "Results written to results.csv", or "X-SQL extraction: enabled").
- The file `results.csv` exists and is non-empty.
- Reading the CSV file shows column headers and extracted data for each product (e.g., "Widget Alpha" and its price, "Widget Beta" and its price).
- The CSV has at least 2 data rows (one per seed URL).

### 3. SQL from stdin with table format (AC6)

Run the same crawl but use `--sql-stdin` to pipe the query via stdin instead of `--sql @file`, and output in table format:

```
crawl --seed-file <path-to-seed-file> --sql-stdin --format table < extract.sql
```

Wait for the crawl to complete. Verify:
- The output contains a table-formatted result (grid/table layout with aligned columns).
- The table includes the same product titles and prices extracted in step 2.
- The table has column headers matching the SQL query's selected fields.

### 4. Compare results

As a final validation, re-run the SQL-from-file crawl (step 2) and compare that the extracted data matches what the table-format run produced — the extraction should be identical regardless of how the SQL was provided.
