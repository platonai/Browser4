# `domsnapshot get` returns empty strings silently when no element matches

## Summary
When `domsnapshot get` is called with a CSS selector that doesn't match any element on the page, it returns an empty string (`""`) with no error message, warning, or non-zero exit code. The user cannot distinguish between "element found but has no text content" and "selector didn't match anything."

## Steps to Reproduce
1. Navigate to a page
2. Run `browser4-cli domsnapshot get text "div[data-component-type='s-search-result'] h2 a span"`
3. If the selector doesn't match, the output is `""`
4. There is no indication whether the tool executed successfully or the selector was wrong

## Expected Behavior
If no element matches the selector, the tool should return a clear diagnostic message like "No element matches selector" and exit with a non-zero code. If an element is found but has no text content, that should be communicated distinctly.

## Actual Behavior
Empty string is returned with exit code 0 in both cases (no match found, and element found with no text). The user is left uncertain whether to debug the selector, check the page structure, or try a different approach entirely.

## Suggested Fix
1. Print a warning to stderr when no element matches the selector
2. Return a non-zero exit code for "no match found"
3. Distinguish "no match" from "element has empty text content" in the output
4. Consider printing the total number of matches found (even if only returning the first)

Labels: bug, UX, reliability, medium
