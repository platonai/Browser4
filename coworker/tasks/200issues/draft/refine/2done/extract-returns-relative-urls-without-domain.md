# `extract` returns relative URLs without the base domain

## Summary
When `extract` is used on a page like Amazon, the `product_link` (or similar URL fields) returns relative paths such as `/sspa/click?...` instead of absolute URLs like `https://www.amazon.com/sspa/click?...`. Users must manually reconstruct the full URL by prepending the domain.

## Steps to Reproduce
1. Navigate to an Amazon search results page
2. Run `browser4-cli extract "get the first 4 search results with product links"`
3. Inspect the output — `product_link` contains a relative path

## Expected Behavior
The `extract` command should resolve relative `href` values against the current page's base URL before returning them. Product links should be fully qualified, usable URLs.

## Actual Behavior
Relative URLs are returned as-is (e.g., `/sspa/click?...`). The user must manually prepend `https://www.amazon.com` to make the links usable. This is error-prone and adds unnecessary friction.

## Suggested Fix
Resolve all `href` and `src` attribute values against the page's base URL in the `extract` output. If the raw value must be preserved, add a separate `resolved_url` field.

Labels: bug, product, low
