# `domsnapshot get` returns only the first matching element, with no way to retrieve all matches

The `domsnapshot get` command only returns the first DOM element matching the given CSS selector, with no flag or option to retrieve all matches. This forces users to resort to JavaScript `eval` for any bulk extraction task, which is a significant usability gap.

**Steps to reproduce:**
1. Navigate to a page with multiple similar elements (e.g., Amazon search results):
   `browser4-cli goto "https://www.amazon.com/s?k=pens+to+draw+on+whiteboards"`
2. Attempt to extract all product titles:
   `browser4-cli domsnapshot get text "[data-component-type=s-search-result] h2 span"`
3. Observe that only the **first** product title is returned.

**Expected behavior:** Either:
- Return all matching elements (analogous to `document.querySelectorAll`), or
- Provide an `--all` flag to opt into returning all matches as a JSON array.

At minimum, the single-match behavior should be clearly documented so users know they need `eval` or `extract` for multi-element queries.

**Actual behavior:** Only the first match is returned, with no warning that other matching elements were discarded. The user has no way to know whether there was only one match or whether additional results were silently dropped.

**Suggested resolution:**
1. Add an `--all` flag to `domsnapshot get` that returns all matches as a JSON array.
2. If `--all` is not feasible in the short term, document the single-match limitation prominently in CLI help text and SKILL.md, and suggest `eval` and `extract` as alternatives for bulk extraction.

Labels: enhancement, ux, documentation

