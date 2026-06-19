# `type` command times out but may partially execute

## Summary

The `type` command consistently times out after 30 seconds on text input operations, but evidence shows the action may have partially or fully executed despite the timeout error. This creates an unreliable state where the user cannot determine whether the command actually worked.

## Steps to reproduce

1. Run `browser4-cli type "pens to draw on whiteboards" e37` on an Amazon search box.
2. Wait for the command to complete.

## Expected behavior

The command either succeeds with the text typed into the field, or fails cleanly with no side effects on browser state.

## Actual behavior

The command times out after 30 seconds with the error `HTTP request timed out`. However, subsequent commands indicate the text was actually typed into the field (potentially twice), demonstrating that the action executed despite the timeout error. This leaves the user in an unreliable state, unsure whether the operation was performed.

## Suggested resolution

- Increase the default timeout for `type` and `fill` commands to accommodate slower operations.
- Distinguish between "operation timed out but may have succeeded" and "operation definitely failed" in error messaging.
- Add a mechanism to verify whether text was actually typed after a timeout, such as checking the field content via `snapshot` or a dedicated `type --verify` flag.
- Consider implementing a retry or health-check mechanism after timeout to report actual state.

Labels: bug, reliability
