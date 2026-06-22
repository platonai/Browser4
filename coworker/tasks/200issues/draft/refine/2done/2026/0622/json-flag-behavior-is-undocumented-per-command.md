# `--json` flag behavior is undocumented per command

**Severity:** Low | **Category:** Discoverability

The `--json` flag is listed in global help options but its interaction with specific commands is not documented. Users cannot determine which commands support `--json` output or what the JSON format looks like without trial and error.

### Steps to Reproduce

1. Notice `--json` in global options
2. Try to use it with `snapshot` or `get text` — uncertain if supported

### Expected Behavior

`--json` flag produces machine-parseable output for snapshots and get commands, with documented format.

### Actual Behavior

The `--json` flag is listed in help but its interaction with specific commands is not documented. Users may avoid using it because they aren't sure which commands support it.

### Suggested Improvements

1. Document which commands support `--json`.
2. Add examples of `snapshot --json` and `get text e5 --json` in SKILL.md.
3. Show a sample JSON output format.

