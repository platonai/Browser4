# Inconsistent CSS selector support documentation across commands

## Summary
The help text for different commands describes their target argument format inconsistently. Only `type` documents CSS selector support, while other commands that actually accept CSS selectors (`fill`, `click`, `check`, `select`) use different wording that implies they only accept snapshot element references. This misleads users about which arguments are valid.

## Steps to Reproduce
1. Run `browser4-cli help` and examine the argument descriptions.
2. Compare the descriptions of `fill`, `type`, `click`, `check`, and `select`.

## Expected Behavior
Consistent description across commands — either all commands mention CSS selectors or all mention snapshot refs, with clear documentation of which argument format is accepted where.

## Actual Behavior
- `fill` says: "ref: Exact target element reference from the page snapshot" — implies only snapshot refs work, yet CSS selectors do work in batch mode.
- `type` says: "ref: Optional CSS selector or element reference to type into" — correctly documents both formats.
- Other commands like `click`, `check`, and `select` have similar inconsistencies.

## Suggested Fix
Standardize the argument descriptions. Either use "CSS selector or element reference" for all ref-accepting commands, or clearly document the expected format for each command individually. The documentation should also clarify whether CSS selector support differs between direct and batch mode.

Labels: bug, documentation, UX, medium
