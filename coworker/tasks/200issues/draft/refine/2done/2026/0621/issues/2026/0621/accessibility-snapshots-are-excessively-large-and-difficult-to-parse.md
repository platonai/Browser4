# Accessibility snapshots are excessively large and difficult to parse

**Severity:** Low  
**Category:** Enhancement / UX

## Summary

Taking a snapshot of a typical e-commerce page (e.g., Amazon search results) produces 1,653+ lines of YAML output. This verbosity makes it difficult for users to manually locate specific elements, even with `grep` assistance.

## Steps to Reproduce

1. Navigate to `https://www.amazon.com/s?k=noise+cancelling+headphones`.
2. Take a snapshot of the page.
3. Attempt to manually find a specific filter or product link.

## Actual Behavior

The snapshot output is extremely large (thousands of lines) and contains many elements irrelevant to the user's current task. Manual parsing is slow and error-prone.

## Suggested Fix

- Add `--filter` or `--selector` options to the snapshot command to restrict output to elements matching a CSS selector, role, or text pattern.
- Add a `--max-depth` option to limit how deep the accessibility tree is traversed.
- Provide a `--summary` mode that shows only high-level page structure (headings, landmarks, interactive elements).

