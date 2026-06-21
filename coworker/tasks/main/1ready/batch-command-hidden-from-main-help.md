# `batch` command hidden from main help output

## Summary
The `batch` command — along with several other advanced commands (`extract`, `summarize`, `agent`, `swarm`, `domsnapshot`, `console`, `pdf`) — does not appear in the output of `browser4-cli help`. It is only discoverable if the user already knows to run `browser4-cli help batch`. This is a significant discoverability problem since `batch` is a critical feature for automation workflows.

## Steps to Reproduce
1. Run `browser4-cli help` and scan the output.
2. Look for any mention of `batch`.

## Expected Behavior
`batch` (and other advanced commands) should appear in the main help listing, or at minimum a note should say "see `help <command>` for batch mode and other advanced commands".

## Actual Behavior
The global help lists 40+ commands but omits `batch`, `extract`, `summarize`, `agent`, `swarm`, `domsnapshot`, `console`, and `pdf`. Users who have not read external documentation or source code have no way to discover `batch` exists.

## Suggested Fix
Add a one-line entry for `batch` (and the other omitted commands) in the global help output, with a pointer to `help <command>` for full details.

Labels: enhancement, CLI, discoverability, documentation
