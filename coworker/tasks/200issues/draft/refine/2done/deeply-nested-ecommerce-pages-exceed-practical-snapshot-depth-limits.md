# E-commerce pages with deeply nested DOM exceed practical snapshot depth limits

## Summary
Amazon and similar e-commerce pages have deeply nested DOM structures where product cards and listing elements reside at depth >8. The `snapshot` command's depth parameter makes interactive snapshots impractical for these pages: product data is invisible at any reasonable depth, and deep snapshots are too large to navigate.

## Steps to Reproduce
1. Navigate to an Amazon search results page
2. Run `browser4-cli snapshot -i -d 8`
3. Observe that product cards are not visible in the snapshot
4. Increase depth further — snapshot becomes impractically large and unreadable

## Expected Behavior
The documentation should guide users toward the right tool for content-heavy e-commerce pages. `extract` (AI-powered) and `domsnapshot` are more effective than `snapshot -i` for extracting structured data from deeply nested commercial pages.

## Actual Behavior
Users naturally reach for `snapshot -i` to discover interactive elements on search result pages, but the depth required to render product cards makes the output unusable. There is no guidance steering users toward `extract` or `domsnapshot` for this use case.

## Suggested Fix
Add an "E-commerce & Content-Heavy Pages" section to SKILL.md (or the snapshot command help) recommending:
- Use `extract` as the primary tool for search result pages and product listings
- Use `domsnapshot` for CSS-selector-based extraction when `extract` is unavailable or undesired
- Reserve `snapshot -i` for pages with shallow DOM (forms, landing pages, settings)

Labels: documentation, enhancement, discoverability, low
