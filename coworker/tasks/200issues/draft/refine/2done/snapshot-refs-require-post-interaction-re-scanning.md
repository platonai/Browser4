# Snapshot refs require defensive re-scanning after every page interaction

## Summary
After any page interaction (`fill`, `click`, `press Enter`, etc.), element references from a previous snapshot may become stale. The documentation correctly warns about this, but the CLI provides no tooling to help users verify ref validity. Users must defensively re-grep the snapshot YAML file after every interaction, even when refs haven't actually changed.

## Steps to Reproduce
1. Take a snapshot and note a button ref (e.g., `e345`)
2. Run `browser4-cli fill <input-ref> "some text"`
3. The button ref `e345` might still be valid, but the user has no way to confirm this without re-reading the snapshot file
4. Running `browser4-cli click e345` without verification risks clicking the wrong element

## Expected Behavior
The CLI could help users manage ref lifecycle by:
- Tracking whether refs are still valid after interactions
- Providing a `--verify-ref` flag on `click`/`fill` that checks validity before acting
- Warning if a ref appears to be stale

## Actual Behavior
Users must manually re-inspect the snapshot after every interaction to confirm refs haven't changed. This is tedious and error-prone, even when refs are stable (as they often are for simple interactions).

## Suggested Fix
1. Add a `--verify-ref` flag to interaction commands (`click`, `fill`) that checks ref validity and warns if the ref is stale
2. Consider auto-detecting stale refs and surfacing a warning
3. Add a `snapshot diff` or `snapshot refs` command that shows which refs changed since the last snapshot

Labels: enhancement, UX, low
