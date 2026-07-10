# Silent failures in `eval` — no output and no error message

**Severity:** High  
**Category:** Bug / UX

## Summary
Certain `eval` commands with multi-statement JavaScript (e.g., using `var` declarations with `for` loops) produce no output at all — not even an error code or stack trace — making debugging extremely difficult.

## Steps to Reproduce
1. Run `browser4-cli eval` with multi-statement JavaScript (e.g., variable declarations combined with loops)
2. Observe that some commands return nothing

## Expected Behavior
Every `eval` should return one of:
- The JavaScript evaluation result (even if `undefined`)
- A clear error message with the JavaScript error and stack trace

## Actual Behavior
Several eval commands returned absolutely nothing — no result, no error code, no indication of failure. This forced blind trial and error during data extraction, requiring 7 iterations to find working selectors.

## Context
This is a high-severity UX issue. When a power-user feature like `eval` fails silently, users have no way to determine whether their JavaScript is incorrect, the page structure doesn't match their selectors, or there's a tool-level failure.

## Suggested Improvement
- Always print JavaScript evaluation results, including `undefined`
- Surface JavaScript runtime errors to the user with full stack traces
- Add a `--debug` flag for `eval` that prints the full eval context and any intermediate state

---

