# No `--json` output for snapshots returned to stdout

## Summary

The `browser4-cli snapshot --json` command does not return snapshot data to stdout as JSON. Instead, snapshots are always written to YAML files on disk, forcing users to read files manually to access snapshot data programmatically.

## Steps to Reproduce

1. Run `browser4-cli snapshot --json`

## Expected Behavior

Snapshot data returned as JSON to stdout for direct consumption (e.g., piping to `jq` or other tools).

## Actual Behavior

The `--json` flag exists as a global option, but snapshots are always written to YAML files on disk. The command output references a file path. There is no way to get snapshot data directly in stdout without reading the file.

## Suggested Improvement

Allow `browser4-cli snapshot --json` to output the accessibility tree as JSON directly to stdout. Alternatively, document clearly that snapshots are file-only and must be read from disk.

Labels: enhancement, low, UX
