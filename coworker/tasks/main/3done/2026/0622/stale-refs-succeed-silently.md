# Stale element refs after page reload succeed silently instead of erroring

**Severity:** Medium | **Category:** UX / Discoverability

When a page is reloaded, all element refs are reassigned. Commands that use old refs succeed silently rather than failing with a clear "stale ref" error, leading to actions targeting wrong or non-existent elements with no indication anything went wrong.

### Steps to Reproduce

1. Take a snapshot — note ref e39 for the search box
2. Run `browser4-cli reload`
3. Run `browser4-cli fill e39 "text"` — command reports success
4. The ref e39 no longer points to the search box (new refs assigned, e.g., e5461)

### Expected Behavior

Either the command fails with a "stale ref" error, or the snapshot output includes ref validation information so users know when refs are no longer valid.

### Actual Behavior

`fill e39 "text"` succeeded silently, suggesting the ref was still valid. But the search box had been re-assigned to a different ref, so the fill likely targeted a different (or non-existent) element.

### Suggested Improvements

1. Validate refs before executing commands and error clearly if stale.
2. Include ref stability metadata in snapshot output (e.g., "Refs valid until page navigation or reload").
3. Add a `--validate` flag to check ref freshness before acting.

