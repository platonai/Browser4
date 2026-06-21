# Snapshot refs become stale after any page interaction

## Summary

Element references (refs) obtained from a snapshot are regenerated on every interaction, meaning any command that touches the page invalidates previously captured refs. This creates a workflow where users must take a new snapshot before every single interaction, contrary to the intuitive expectation set by the documentation.

## Steps to Reproduce

1. Take a snapshot, note a ref (e.g., e311 for a search button)
2. Use `fill` on a textbox
3. Try to `click` the previously noted ref

## Expected Behavior

The ref should remain valid if the element still exists on the page and has not been removed or re-rendered.

## Actual Behavior

Refs are regenerated on every interaction (the accessibility tree is re-enumerated), making previously captured refs invalid. The user must take a new snapshot after every action to get current refs.

## Suggested Improvement

This is a fundamental design choice, but the documentation should emphasize this limitation more strongly. The workflow description "snapshot -> click e15" implies refs persist, but they do not survive intermediate commands. Add a prominent note like: "Refs are only valid for the next immediate command -- you must snapshot again before any subsequent interaction." A longer-term solution would be to implement stable element identifiers that persist across interactions.

Labels: enhancement, medium, UX
