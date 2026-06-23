# Price extraction from Amazon product pages is inconsistent

**Severity:** Medium  
**Category:** Enhancement / UX

## Summary

Extracting product prices from Amazon product pages requires multiple attempts and different DOM selectors per product. There is no consistent price element or selector that works across all Amazon product pages.

## Steps to Reproduce

1. Navigate to different Amazon product detail pages (e.g., Apple AirPods Pro 3 vs. Anker Soundcore P30i).
2. Run `eval "document.querySelector('.a-price .a-offscreen')"` on each page.

## Expected Behavior

A consistent DOM structure or selector reliably returns the product price.

## Actual Behavior

Different products use different DOM structures for price display. Examples encountered:
- `.a-price .a-offscreen`
- `#corePrice_desktop`
- Some product pages had no standard price element at all.

## Suggested Fix

- Consider adding a dedicated `get price` command that attempts multiple known price selectors in sequence.
- Document the most common Amazon price selectors and fallback strategies in usage examples.
- Surface a clear error or "not found" message when no known price element is detected.

