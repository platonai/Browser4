# `extract` returns relative URLs without resolving against page base URL

**Severity:** Low  
**Category:** Bug / Product

## Summary
When `extract` returns `product_link` (or other link fields) from page content, the URLs are relative paths (e.g., `/sspa/click?...`) rather than absolute URLs (e.g., `https://www.amazon.com/sspa/click?...`). The user must manually reconstruct the full URL by prepending the domain.

## Steps to Reproduce
1. Navigate to an Amazon search results page
2. Run: `browser4-cli extract "get first 4 search results with product name, price, rating, and product link"`
3. Observe: `product_link` field contains relative paths like `/sspa/click?...`

## Expected Behavior
Extracted links should be absolute URLs, resolved against the current page's base URL. At minimum, relative URLs should be documented so users know to resolve them.

## Actual Behavior
Relative URLs are returned, requiring manual domain reconstruction by the user. This is error-prone and breaks downstream automation that expects valid absolute URLs.

## Context
Discovered during an Amazon product search evaluation. The comparison file required manual URL reconstruction for all product links. For e-commerce and data extraction workflows, absolute URLs are the expected output format.

## Suggested Improvement
Resolve all `href` values against the page base URL before returning them in `extract` results. If the base URL cannot be determined, include it as a separate field in the output so users can resolve links programmatically.

---

