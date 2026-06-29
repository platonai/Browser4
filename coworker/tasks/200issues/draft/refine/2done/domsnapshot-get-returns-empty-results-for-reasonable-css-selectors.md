# `domsnapshot get` returns empty results for reasonable CSS selectors

The `domsnapshot get` command returns an empty string for CSS selectors that should match elements on the page, making structured data extraction unreliable.

**Steps to reproduce:**
1. Navigate to a search results page with product listings (e.g., Amazon).
2. Run `browser4-cli domsnapshot`.
3. Run `browser4-cli domsnapshot get text "h2 a"`.

**Expected behavior:** A list of product titles, since the page contains multiple `<h2><a>` elements in the search results.

**Actual behavior:** `""` — an empty string is returned.

**Suggested improvement:** Either fix CSS selector matching to work as documented for common patterns, or clearly document which selectors are supported with working examples for common extraction patterns (product titles, prices, ratings). The reference file `references/domsnapshot.md` should provide immediately actionable guidance from the main documentation.

Labels: bug, documentation, reliability

