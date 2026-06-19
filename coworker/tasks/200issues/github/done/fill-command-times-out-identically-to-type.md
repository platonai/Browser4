# `fill` command times out identically to `type`

## Summary

The `fill` command suffers from the same 30-second timeout issue as the `type` command. Both text-input commands share the same timeout ceiling, even though they use different underlying MCP tools (`browser_type` vs `browser_press_sequentially`).

## Steps to reproduce

1. Run `browser4-cli fill e37 "pens to draw on whiteboards"` on an Amazon search box.

## Expected behavior

The command either succeeds with the field filled, or fails cleanly with no side effects.

## Actual behavior

The command times out after 30 seconds with the same timeout error as `type`. This makes both text-input methods unreliable for interactive use.

## Suggested resolution

- Increase the default timeout for both `type` and `fill` commands.
- Improve error reporting to distinguish timeout modes.
- Investigate why both MCP tools (`browser_type` and `browser_press_sequentially`) hit the same timeout ceiling despite potentially different execution paths.

Labels: bug, reliability
