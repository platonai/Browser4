# No `--stdout` flag available on any data-output commands

**Severity:** Medium  
**Category:** UX / Discoverability

## Summary
None of the data-output commands (`snapshot`, `extract`, `domsnapshot get`, `get`, etc.) support printing results to stdout. Every data extraction requires an extra step to read the output file. This is a cross-cutting UX pattern that creates constant friction.

## Steps to Reproduce
1. Check help for `snapshot`, `extract`, `get`, `domsnapshot get`
2. Look for `--stdout`, `--output -`, or equivalent flag
3. Observe: No such flag exists on any command

## Expected Behavior
Common CLI pattern: data-output commands should support `--stdout` (or `--output -`) for piping and inline viewing. At minimum, the most commonly used commands should offer this.

## Actual Behavior
Every data extraction command requires finding and reading the output file separately. This pattern repeats across the entire CLI surface.

## Context
Discovered during an Amazon product search evaluation, but observed across multiple usability evaluations. The file-only output pattern was the most frequently mentioned UX friction. Commands affected include at least: `snapshot`, `extract`, `domsnapshot get`, `get`, and likely others.

## Suggested Improvement
1. Add `--stdout` flag to all data-output commands (`snapshot`, `extract`, `domsnapshot get`, `get`, etc.)
2. Consider making `--stdout` the default for interactive use, with `--output <file>` for explicit file output
3. At minimum, print a preview/summary inline and write full content to file

---

