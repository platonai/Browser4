# extraction-method-routing

Before running this scenario, ensure MockSite is running on localhost:18080 (`./bin/test.ps1 mock-site`). For the natural-language extraction branch, also configure an LLM API key that enables `extract`.

This scenario covers every branch in **SKILL.md §4a — Choosing an Extraction Method**.

## Acceptance Criteria

1. **AC1 — Interact first, then extract:** After interacting with a MockSite form or widget, use a fresh snapshot/HTML snapshot to extract the resulting confirmation text or updated page state.
2. **AC2 — Static page, one field:** Use `htmlsnapshot get text` to extract a single product field from a MockSite product detail page.
3. **AC3 — Static page, one field, all matches:** Use `htmlsnapshot get all text` to extract every matching product title from a MockSite listing page.
4. **AC4 — Static page, correlated multi-field rows:** Use `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` to extract title, price, and URL from each MockSite product card.
5. **AC5 — Dynamic or complex page logic:** Use `eval --json` to compute structured data from the live DOM instead of relying on CSS-only extraction.
6. **AC6 — Natural-language extraction:** Use `extract` on a product page to ask for structured product data in plain English.
7. **AC7 — High-volume extraction:** Use a seed file plus `crawl --sql` on multiple MockSite product URLs to demonstrate the bulk path for many pages.

## Steps

### 1. Interact first, then extract (AC1)

1. Go to `http://localhost:18080/generated/form-filling.html`.
2. Capture an interactive snapshot (`snapshot -i`) and use refs to fill several fields plus one checkbox or dropdown.
3. Submit the form, wait for the resulting page state, then capture a fresh HTML snapshot.
4. Extract the confirmation banner, summary block, or submitted-value container from the post-submit page with `htmlsnapshot get text ...`.
5. Verify the extracted text reflects the values you entered.

### 2. Static page, one field (AC2)

1. Go to `http://localhost:18080/ec/dp/B0E000001`.
2. Capture an HTML snapshot.
3. Run:

```
htmlsnapshot get text "#productTitle"
```

4. Verify the result is the product title for that page.

### 3. Static page, one field, all matches (AC3)

1. Go to `http://localhost:18080/ec/b?node=1292115012`.
2. Capture an HTML snapshot.
3. Use `htmlsnapshot get all text` with the product-title selector pattern already used elsewhere in the MockSite tasks (for example `[class*="product-title"]`).
4. Verify the command returns all product titles from the Electronics listing page, not just the first one.

### 4. Static page, correlated multi-field rows (AC4)

1. Stay on `http://localhost:18080/ec/b?node=1292115012`.
2. Write an X-SQL query to a file that selects:
   - `DOM_BASE_URI(DOM)` as the source URL
   - `DOM_FIRST_TEXT(...)` for the product title
   - `DOM_FIRST_TEXT(...)` or `DOM_FIRST_FLOAT(...)` for the price
   - `DOM_FIRST_HREF(...)` for the detail-page link
3. Scope the query to repeating product-card containers with `DOM_LOAD_AND_SELECT(@url, 'div[class*="product-card"]', ...)`.
4. Run `htmlsnapshot query --sql @<file>`.
5. Verify each output row keeps the title, price, and URL aligned for the same product card.

### 5. Dynamic or complex page logic (AC5)

1. Go to `http://localhost:18080/generated/interactive-1.html`.
2. Use `eval --json` to return a structured object that combines several live-DOM facts, such as:
   - `document.title`
   - the number of buttons, links, and forms
   - the visible text of each heading
3. Verify the JSON payload contains the requested computed fields and that the values match the page.

### 6. Natural-language extraction (AC6)

1. Go to `http://localhost:18080/ec/dp/B0E000002`.
2. If an LLM key is configured, run `extract` with a prompt such as:
   - "Return the product title, displayed price, rating, and the top three feature bullets as JSON."
3. Verify the extracted structure matches the visible product page.
4. If no LLM key is configured, record this branch as environment-blocked rather than changing the scenario.

### 7. High-volume extraction (AC7)

1. Create a seed file containing at least 4 MockSite product detail URLs from `http://localhost:18080/ec/dp/`.
2. Write an X-SQL query file that extracts the product URL, title, and price from each page.
3. Run:

```
crawl --seed-file <path-to-seed-file> --depth 0 --sql @<query-file> --format table --refresh
```

4. Verify the result aggregates one structured row per seed URL and demonstrates the recommended bulk-extraction path for many pages.
