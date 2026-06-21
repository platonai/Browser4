# `go-back` navigates to an unexpected page

**Severity:** Medium  
**Category:** Bug / UX

## Summary

The `browser4-cli go-back` command does not reliably return to the immediate previous page in the browser history stack. It sometimes skips one or more entries, landing the user on a page visited earlier than expected.

## Steps to Reproduce

1. Navigate from page A → page B → page C.
2. Run `go-back` from page C.

## Expected Behavior

The browser returns to page B (the immediate predecessor in the history stack).

## Actual Behavior

The browser returns to page A, skipping page B entirely.

## Impact

Multi-product navigation workflows (e.g., visiting several product detail pages in sequence and returning to search results) cannot rely on browser history. Users are forced to maintain explicit URLs and use `goto` for every navigation step.

## Workaround

Use explicit `goto <URL>` commands instead of relying on `go-back`.

