# No `--stdout` flag on any data-output commands

## Summary
None of the data-producing commands (`snapshot`, `extract`, `get`, `domsnapshot get`, `domsnapshot export`) support printing results to stdout. Every command that produces output requires the user to open and read a file separately, creating constant friction during interactive use and preventing standard Unix-style piping.

## Steps to Reproduce
1. Check `browser4-cli snapshot --help` — no `--stdout` flag
2. Check `browser4-cli extract --help` — no `--stdout` flag
3. Check any other data-output command — none support `--stdout` or `--output -`

## Expected Behavior
Following the common CLI convention, data commands should support `--stdout` or `--output -` for piping and inline viewing. This is a standard pattern in tools like `curl`, `git`, and most Unix utilities.

## Actual Behavior
Every data extraction command requires an extra step to read the output file. This is especially painful during interactive exploration where users iterate rapidly on selectors and prompts.

## Suggested Fix
Add a consistent `--stdout` flag (or `-o -` convention) across all data-output commands: `snapshot`, `extract`, `domsnapshot get`, `domsnapshot export`, and any others that produce output files.

Labels: enhancement, UX, discoverability, medium
