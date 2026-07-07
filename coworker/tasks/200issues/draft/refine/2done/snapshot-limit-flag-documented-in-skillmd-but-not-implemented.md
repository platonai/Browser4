# `snapshot --limit` flag documented in SKILL.md but not implemented in the CLI

## Summary
The `browser4-cli` SKILL.md documentation references a `-l`/`--limit` flag for the `snapshot` command to constrain output to N nodes, but this flag is not recognized by the actual CLI binary. Attempting to use it produces a misleading argument-count error.

## Steps to Reproduce
1. Read SKILL.md and note the documented `--limit` flag for `snapshot`
2. Run `browser4-cli snapshot -l 200`
3. Observe the error

## Expected Behavior
Either the `--limit` flag should work as documented, limiting snapshot output to the specified number of nodes, or the documentation should not reference it.

## Actual Behavior
The CLI rejects the flag with: `too many arguments: expected 0, received 2`. The flag does not appear in `browser4-cli help` output, confirming it was never implemented.

## Suggested Fix
Either implement the `--limit` flag on the snapshot command, or remove it from SKILL.md. If removed, consider documenting `-d` (depth) as the recommended alternative for controlling snapshot size, and note the gap for users who want to limit by node count.

Labels: bug, documentation, medium
