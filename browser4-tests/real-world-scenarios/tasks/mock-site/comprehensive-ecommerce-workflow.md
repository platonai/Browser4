# comprehensive-ecommerce-workflow

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`).

1. Go to `http://localhost:18080/ec/` (the MockSite e-commerce home page).
2. Capture a full-page snapshot (`-v 0`) to see all visible elements on the home page.
3. Capture an interactive-only snapshot (`-i`) to see clickable elements (product links, search, navigation).
4. Click on the first product link to navigate to a product detail page.
5. On the product detail page, capture a DOM snapshot for detailed extraction.
6. Use domsnapshot inspect with `--max 3 --depth 2` to discover the CSS selectors for the product title, price, description, and image.
7. Use domsnapshot get all to extract all text content from the product description area.
8. Write an X-SQL query to a file that extracts the product title, price, and image URL. Run it with `--sql @file`.
9. Use domsnapshot grep to search the page HTML for price-related patterns.
10. If an LLM API key is configured, use extract to pull structured product data (title, price, description, features) as JSON.
11. Open a new tab and navigate to `http://localhost:18080/ec/b?node=1292115012` (the Electronics product listing page with 6 products).
12. On the listing page, capture a DOM snapshot and run domsnapshot get all to extract all product titles and prices.
13. Use eval with `--json` to run JavaScript that counts the total number of product links on the listing page.
14. Take a screenshot of the product listing page with a descriptive filename.
15. Switch back to the product detail tab and reload the page. Take a snapshot to verify the content is still correct.
16. Use snapshot grep on the detail page to find specific product attribute text.
17. Save the complete browser state (cookies, localStorage) to a file to preserve the session.
18. Summarize the extracted product data in a brief report.
