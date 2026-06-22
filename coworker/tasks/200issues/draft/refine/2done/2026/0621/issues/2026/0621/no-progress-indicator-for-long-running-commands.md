# No progress indicator for long-running commands

## Summary

Commands like `extract`, `type`, and `fill` can take multiple seconds to complete, but the terminal hangs silently until either the command completes or the timeout fires. There is no visual feedback to indicate that the tool is still working.

## Steps to reproduce

1. Run `browser4-cli extract`, `type`, or `fill` — any command that may take more than 1-2 seconds to execute.
2. Wait during execution.

## Expected behavior

A spinner, elapsed-time counter, or other progress indication is shown while the command executes, giving the user confidence the tool is still working.

## Actual behavior

The terminal hangs silently until the command completes or times out. Users cannot tell whether the command is progressing or stuck.

## Suggested resolution

- Add a spinner or progress indicator for commands that take more than 1-2 seconds.
- Show elapsed time periodically to reassure the user.
- Consider different progress display modes (e.g., simple spinner vs. detailed "step N of M" output) depending on verbosity settings.

Labels: UX, enhancement
