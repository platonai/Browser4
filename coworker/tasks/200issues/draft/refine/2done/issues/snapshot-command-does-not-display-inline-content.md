# `snapshot` command does not display inline content

## Summary

Running `browser4-cli snapshot` only outputs a file path rather than displaying the snapshot content inline. Users must manually open the generated YAML file to find element references (refs), creating a context-switching burden that breaks interactive workflow flow.

## Steps to reproduce

1. Run `browser4-cli snapshot`.

## Expected behavior

The snapshot content is displayed inline in the terminal, or at minimum a clear indication of how to view the contents (e.g., "run `browser4-cli snapshot --view`").

## Actual behavior

Only a file path is shown, e.g., `[Snapshot](snapshot-2026-...yml)`. The user must separately open the YAML file to find element refs, then return to the CLI.

## Suggested resolution

- Add an `--inline` flag to print the snapshot accessibility tree directly to stdout.
- Alternatively, make inline display the default for interactive use and provide `--file` or `--no-inline` to suppress it.
- At minimum, display the top N elements in the terminal to give the user immediate usable information.

Labels: UX, enhancement
