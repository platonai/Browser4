# `eval` command produces no output on success

## Summary
The `eval` command executes JavaScript expressions in the browser page context but produces no visible output when the expression succeeds. The return value is silently discarded, making the command appear to do nothing. Only errors produce output, which is confusing and gives the impression the command is broken.

## Steps to Reproduce
Run the following commands:
```
browser4-cli eval "JSON.stringify(window.__browser4State)"
browser4-cli eval "document.title"
```

## Expected Behavior
The return value of the expression should be printed to stdout. For `document.title`, the page title should be printed.

## Actual Behavior
Both commands produce no visible output at all (silent success). The user has no way to tell whether the expression ran, what the result was, or whether it returned `undefined` vs. actually executing.

## Suggested Fix
`eval` should always print its return value when the expression evaluates successfully. Silent success is confusing and makes the command effectively unusable for interactive debugging or scripting workflows.

Labels: bug, UX, CLI, medium
