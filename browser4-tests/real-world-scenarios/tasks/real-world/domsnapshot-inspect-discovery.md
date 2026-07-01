# domsnapshot-inspect-discovery

1. Go to `http://books.toscrape.com/`.
2. Capture a DOM snapshot of the page.
3. Run domsnapshot inspect without a selector to see the top-level landmarks and structure of the page.
4. Run domsnapshot inspect with a CSS selector targeting the product listing area, using `--max 5` to limit to 5 examples and `--depth 3` to show nested structure.
5. Generate a page summary to get a compressed overview alongside the inspection results.
6. Use the selectors discovered by inspect to run a domsnapshot get all query — extract all book titles using one of the suggested selectors.
7. Use domsnapshot grep with `--selector` to validate that a proposed selector matches the expected number of elements on the page.
8. Based on the discovered structure, write and run a domsnapshot query using X-SQL to extract both book titles and prices.
9. Optionally, explore another section of the page (e.g., the sidebar category list) by running inspect with a different container selector.
