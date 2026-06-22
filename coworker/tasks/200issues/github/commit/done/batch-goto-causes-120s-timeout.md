# Batch including `goto` causes 120s HTTP timeout

## Summary
Running `batch` with a `goto` command as one of the steps causes a hard 120-second HTTP timeout. The documentation and README both show `goto` in batch examples, so this is a broken documented workflow that forces users to run navigation as a separate step.

## Steps to Reproduce
Run the following command:
```
browser4-cli batch "goto http://localhost:18080/generated/form-filling.html" "fill #first-name 'Alice'"
```

## Expected Behavior
The browser navigates to the URL and fills the form field in a single batch invocation.

## Actual Behavior
Error: `Error: HTTP request timed out [tool=command_batch, timeout=120s]`

## Workaround
Run `goto` separately before `batch`:
```
browser4-cli goto http://localhost:18080/generated/form-filling.html
browser4-cli batch "fill #first-name 'Alice'"
```

## Suggested Fix
Investigate why `goto` inside batch causes the backend to hang. The batch executor or the navigation command may be blocking the event loop or contending for the same browser context resource.

Labels: bug, high, CLI, reliability, timeout
