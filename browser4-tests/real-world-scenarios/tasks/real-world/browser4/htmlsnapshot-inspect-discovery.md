# htmlsnapshot-inspect-discovery

1. Go to `http://books.toscrape.com/`.
2. Capture an HTML snapshot of the page.
3. Run htmlsnapshot inspect without a selector to see the top-level landmarks and structure of the page.
4. Run htmlsnapshot inspect with a CSS selector targeting the product listing area, using `--max 5` to limit to 5 examples and `--depth 3` to show nested structure.
5. Generate a page summary to get a compressed overview alongside the inspection results.
6. Use the selectors discovered by inspect to run an htmlsnapshot get all query — extract all book titles using one of the suggested selectors.
7. Use htmlsnapshot grep with `--selector` to validate that a proposed selector matches the expected number of elements on the page.
8. Based on the discovered structure, write and run an htmlsnapshot query using X-SQL to extract both book titles and prices.
9. Optionally, explore another section of the page (e.g., the sidebar category list) by running inspect with a different container selector.
