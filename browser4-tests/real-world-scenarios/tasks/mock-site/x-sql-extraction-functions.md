# x-sql-extraction-functions

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

1. Go to `http://localhost:18080/ec/b?node=1292115012` (the MockSite e-commerce Electronics category with 6 products).
2. Capture an HTML snapshot of the page.
3. Use htmlsnapshot inspect with `--max 3 --depth 3` to discover the CSS selectors for product containers, titles, prices, images, and links.
4. Write an X-SQL query that extracts multiple fields from each product using DOM functions:
   - `DOM_FIRST_TEXT` for the product title
   - `DOM_FIRST_FLOAT` for the price (parsed as a number)
   - `DOM_FIRST_HREF` for the product detail link
   - `DOM_FIRST_IMG` for the product image URL
   - `DOM_FIRST_ATTR` for any data attributes on the product card
5. Enhance the query with STR functions to clean the extracted data:
   - Use `STR_TRIM` to remove whitespace from titles
   - Use `STR_UPPER_CASE` to normalize a field
   - Use `STR_DEFAULT_IF_BLANK` to provide fallback values for missing fields
   - Use `STR_FIRST_FLOAT` to extract numeric values from text
   - Use `STR_ABBREVIATE` to truncate long text fields for display
6. Use `ARRAY_FIRST_NOT_BLANK` with multiple fallback selectors to handle variations in the page structure.
7. If the page supports it, use PowerCSS `:expr()` to filter elements by visual properties (e.g., only elements wider than a threshold).
8. Add `WHERE` clauses to filter the results, `ORDER BY` to sort by price or title, and `LIMIT` to restrict the output.
9. Run the final query and review the extracted data.
