# No built-in multi-product or batch data extraction workflow

**Severity:** Medium  
**Category:** Enhancement / UX

## Summary

The tool lacks a built-in pattern for visiting multiple URLs and extracting the same fields from each. Tasks like comparison shopping (open N product pages, extract title/rating/price/reviews from each) require manual repetition of `goto` and `eval` for every page.

## Steps to Reproduce

1. Attempt to extract structured data (e.g., title, rating, price) from 5 or more product pages.
2. Look for a `batch`, `foreach`, or workflow command to automate the repetition.

## Expected Behavior

A batch or workflow command that accepts a list of URLs and a set of extraction instructions, then returns structured results for each URL.

## Actual Behavior

Users must manually repeat `goto <url>`, `eval <selector>`, and other commands for each individual product, managing state and results themselves.

## Suggested Fix

- Document the existing `batch` command (if available) with a complete multi-page extraction example.
- Provide a `foreach` or `map` pattern that iterates over URLs and collects results.
- Consider a dedicated comparison-shopping or multi-product research workflow.

