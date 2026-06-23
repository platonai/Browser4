# `eval` command sometimes produces no output on failure

URL: https://github.com/platonai/Browser4/issues/487
State: OPEN
Author: galaxyeye
Created: 06/21/2026 14:40:34
Updated: 06/21/2026 14:40:34

**Severity:** Medium
**Category:** Bug / Reliability

## Summary

When `browser4-cli eval` is used to run a JavaScript expression that fails, returns an object that cannot be serialized, or encounters an error, it sometimes produces no output at all — neither a result, nor an error message, nor a status indicator.

## Steps to Reproduce

1. Run `browser4-cli eval` with a complex expression that returns an object (e.g., `document.querySelectorAll('...')`).
2. Observe that no output is produced.

## Expected Behavior

The command should always produce output — at minimum:
- The serialized result (even if `null` or `undefined`).
- A clear error message if the expression throws.
- An indicator if the result cannot be serialized (e.g., a DOM element reference).

## Actual Behavior

Sometimes the command produces no output at all. This silent failure leaves the user unable to determine whether the expression ran, failed, or simply returned an empty/undefined result.

## Suggested Fix

Ensure `eval` always outputs something. At minimum, convert `null` and `undefined` to their string representations. For DOM elements and non-serializable objects, output a descriptive type indicator rather than nothing. If an error occurs, surface it explicitly.
