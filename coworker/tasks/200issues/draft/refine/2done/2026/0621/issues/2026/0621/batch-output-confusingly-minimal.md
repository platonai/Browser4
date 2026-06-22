# Batch output is confusingly minimal

## Summary
When running a batch command, the output only shows the return value of the last command (e.g., `[us]` for a `select` command), with no indication of which commands ran, whether they succeeded or failed, or what the bracket-prefix format means. This makes it difficult to verify that all steps in the batch completed successfully.

## Steps to Reproduce
Run a batch with multiple commands:
```
browser4-cli batch "fill #first-name 'Alice'" "select #country us" "click #submit-btn"
```
Observe the output. It will show only `[us]` — the return value of the `select` command.

## Expected Behavior
Clear indication of which commands ran and their individual results. At minimum, each command should print its name and success/failure status.

## Actual Behavior
- Only the last command's return value is shown (`[us]`).
- Results of `fill` and `click` are invisible.
- The `[value]` bracket format is unexplained and may be confused with an array literal.
- There is no success/failure summary.

## Suggested Fix
Batch mode should either:
- Print per-command status (command name + success/failure) by default, or
- Add a `--verbose` flag to enable detailed output.
- Document the bracket format in `help batch` regardless.

Labels: enhancement, UX, CLI, medium
