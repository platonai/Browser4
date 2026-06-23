# `extract` command is not discoverable from the main help output

**Severity:** Low  
**Category:** Documentation / Discoverability

## Summary

The `extract` agent command exists and is functional (it can be invoked and appears in help text under certain conditions), but it is not listed in the main `browser4-cli help` output. This makes it difficult for users to discover that the feature exists.

## Steps to Reproduce

1. Run `browser4-cli help`.
2. Look for `extract` in the command listing.

## Expected Behavior

All available commands, including `extract`, are listed in the main help output.

## Actual Behavior

`extract` does not appear in the main `browser4-cli help` listing. It has been observed to appear in help output triggered incidentally (e.g., after a malformed `scroll` command), but this is not a reliable discovery path. The `SKILL.md` documentation references `browser4-cli help extract`, but users would not think to look there if they don't know the command exists.

## Suggested Fix

Add `extract` (and any other undocumented subcommands) to the main help output so users can discover it naturally.

