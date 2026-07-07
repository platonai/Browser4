# Misleading error message for unrecognized snapshot flags

## Summary
When the `snapshot` command receives an unrecognized flag (such as `-l`), the CLI reports an argument-count error rather than identifying the actual problem: that the flag is unknown. This misleads users into thinking `snapshot` accepts no flags at all.

## Steps to Reproduce
1. Run `browser4-cli snapshot -i -l 50`
2. Observe the error message

## Expected Behavior
The CLI should print something like `error: unrecognized flag: -l` or `unknown option: --limit`, clearly identifying which argument is unrecognized.

## Actual Behavior
The error reads: `too many arguments: expected 0, received 2`. This suggests the command doesn't accept any flags, when in fact `-i` is valid — only `-l` is unrecognized. The user cannot distinguish between a valid flag used incorrectly and an entirely unsupported flag.

## Suggested Fix
Improve the argument parser to detect unrecognized flags and report them by name. If the parser cannot distinguish flags from positional arguments, add explicit flag validation before falling back to a generic argument-count error.

Labels: bug, UX, medium
