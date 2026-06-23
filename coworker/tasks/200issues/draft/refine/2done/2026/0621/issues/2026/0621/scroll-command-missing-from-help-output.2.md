# `scroll` command missing from `help` output

Running `browser4-cli help` does not list the `scroll` command anywhere in its output. The only scrolling-related entry under the Mouse section is `mousewheel`. This means users who read `help` (the primary discoverability mechanism) would never learn about `scroll`, even if it were implemented.

## Steps to reproduce

1. Run `browser4-cli help`
2. Look for any mention of `scroll`

## Expected behavior

The `scroll` command appears under the Mouse section (or its own section) with a brief description.

## Actual behavior

No `scroll` command appears. Only `mousewheel` is listed under Mouse.

## Additional context

- Even if `scroll` were implemented, users would not discover it through the built-in help system.
- This also means that `help scroll` returns nothing, since the command is absent from both the help listing and the per-command help registry.

