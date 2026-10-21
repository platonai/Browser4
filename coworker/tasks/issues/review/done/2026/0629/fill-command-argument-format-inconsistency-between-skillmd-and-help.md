# `fill` command argument format inconsistency between SKILL.md and `--help`

**Severity:** Low  
**Category:** Documentation

## Summary
The argument format for the `fill` command is documented inconsistently: SKILL.md uses `fill <ref> "<value>"` (with quotes around the value), while the `--help` output uses `fill <ref> <text>` (without quotes).

## Steps to Reproduce
1. Read `browser4-cli fill --help` — see `<ref> <text>` format
2. Read SKILL.md — see `<ref> "<value>"` format with quotes
3. Notice the inconsistency

## Expected Behavior
Consistent argument formatting across all documentation.

## Actual Behavior
SKILL.md wraps the value argument in quotes; `--help` does not. In practice, quotes are only needed for shell escaping of text with spaces or special characters, not as part of the command syntax itself.

## Suggested Improvement
Standardize argument formatting. Since quotes are only needed for shell escaping (not part of the command syntax), remove them from SKILL.md for consistency with `--help` output, or add a note explaining when quotes are necessary.

---

