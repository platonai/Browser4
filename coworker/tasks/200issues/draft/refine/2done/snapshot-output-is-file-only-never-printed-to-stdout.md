# `snapshot` output is file-only — content is never printed to stdout

## Summary
The `snapshot` command always writes output to a file and only prints the file path to the terminal. There is no `--stdout` flag, no `snapshot show` command, and no built-in way to view snapshot content inline. Users must manually open YAML files (`head`, `cat`, `grep`) to find element refs, which contradicts the documentation's "Never cat full snapshot files" guidance.

## Steps to Reproduce
1. Run any `browser4-cli snapshot` variant
2. Observe that only a file path is printed
3. There is no flag or command to display the snapshot content in the terminal

## Expected Behavior
Snapshot content should be optionally printable to stdout via a `--stdout` flag, or a `snapshot show` command should display the most recent snapshot inline with sensible defaults (e.g., `-i` mode with reasonable depth).

## Actual Behavior
Users must read the YAML file separately after every snapshot. This creates a repetitive manual step in interactive workflows where users are rapidly discovering and targeting elements. The documentation's advice against cat-ing full snapshot files leaves users without any built-in alternative.

## Suggested Fix
Add a `--stdout` flag to `snapshot` that prints content to stdout. Alternatively, provide a `snapshot show` command that displays the last snapshot with `-i` and reasonable depth defaults.

Labels: enhancement, UX, medium
