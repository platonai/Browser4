# `domsnapshot get` returns empty strings silently with no diagnostics

**Severity:** Medium  
**Category:** UX / Reliability

## Summary
When `domsnapshot get` fails to match any element for a CSS selector, it returns an empty string (`""`) with no error message, warning, or non-zero exit code. The user cannot distinguish between "no element matched the selector" and "element matched but has no text content."

## Steps to Reproduce
1. Navigate to a page with structured content: `browser4-cli goto "https://www.amazon.com/s?k=pens"`
2. Run: `browser4-cli domsnapshot get text "div[data-component-type='s-search-result'] h2 span"`
3. Observe: Output is `""` with no diagnostic information

## Expected Behavior
If no element matches, the tool should return a clear message like "No element matches selector" or return a non-zero exit code. If an element matches but has no text, it should indicate that distinction.

## Actual Behavior
Returns an empty string with exit code 0 (success). The user is left uncertain whether the tool worked, the selector was wrong, or the page structure changed. Multiple retries with different selectors are needed, each providing no feedback.

## Context
Discovered during an Amazon product search evaluation. Several CSS selector attempts returned `""` with no indication of what went wrong. The user eventually abandoned `domsnapshot get` entirely and used the AI `extract` command instead. The silent failure mode makes selector debugging nearly impossible.

## Suggested Improvement
1. Distinguish between "no element matched" (stderr warning) and "element has no text" (empty result with info message)
2. Return non-zero exit code when no element matches
3. Consider adding a `--verbose` flag that shows which selectors were attempted and why they failed

---

