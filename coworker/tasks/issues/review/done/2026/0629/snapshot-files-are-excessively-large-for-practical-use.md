# Snapshot files are excessively large for practical use

**Severity:** Medium  
**Category:** Enhancement / UX

## Summary
Snapshot output on e-commerce search results pages produces excessively large files (1,349 lines for a single search results page), making it difficult to locate relevant interactive elements even with `-i -c` (interactive + compact) flags.

## Steps to Reproduce
1. Navigate to an e-commerce search results page (e.g., Amazon)
2. Run `browser4-cli snapshot -i -c`
3. Observe the snapshot file size

## Expected Behavior
A manageable snapshot focused on key interactive elements, with options to filter by section or limit output size.

## Actual Behavior
1,349 lines for a single page. Even with compact and interactive modes, the output is overwhelming and hard to navigate.

## Suggested Improvement
- Add a `--max-lines` flag to limit output lines
- Add section-based filtering (e.g., show only elements under a specific heading or landmark region)
- Consider a `--filter` flag accepting a CSS selector or section name to scope the snapshot

---

