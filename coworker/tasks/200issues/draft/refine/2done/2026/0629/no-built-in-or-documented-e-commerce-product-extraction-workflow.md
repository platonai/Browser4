# No built-in or documented e-commerce product extraction workflow

**Severity:** Medium  
**Category:** Enhancement / Documentation

## Summary
There is no built-in command or documented workflow for extracting product listings (titles, prices, ratings) from e-commerce search results pages. Users must write raw page-specific JavaScript `eval` with custom CSS selectors, and the `extract` command — which would be the natural tool for this — times out on real pages.

## Steps to Reproduce
1. Navigate to an e-commerce search results page
2. Attempt to extract product data using available commands
3. Find no dedicated command or documented pattern

## Expected Behavior
A documented, reusable workflow for extracting product cards from common e-commerce sites, or a built-in `product-extract`-style command.

## Actual Behavior
Users must write raw JavaScript `eval` with page-specific CSS selectors. The `extract` command exists but timed out on the test page (Amazon). The workflow required 7 iterations of trial and error.

## Suggested Improvement
- Document a reusable pattern for e-commerce extraction in SKILL.md
- Add examples showing how to extract product data from Amazon, eBay, and other common e-commerce sites
- Consider a dedicated `product-extract` command or an e-commerce extraction template

---

