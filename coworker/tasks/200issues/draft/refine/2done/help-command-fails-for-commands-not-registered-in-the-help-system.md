# `help <command>` fails for commands not registered in the help system

The CLI has a `help` command that lists available commands, but `help <command>` does not work for commands that don't appear in the main help output — such as `scroll`. This means users cannot get per-command documentation for features they discover through SKILL.md or other channels.

## Steps to reproduce

1. Run `browser4-cli help scroll`

## Expected behavior

Documentation for the `scroll` command is displayed (usage, arguments, examples).

## Actual behavior

Presumed to fail or return an error, since `scroll` does not appear in the main `help` listing and may not have a registered help entry.

## Additional context

- Every command that is documented externally (SKILL.md) should have a corresponding `help <command>` entry in the CLI.
- This is a discoverability and documentation integrity issue — users should be able to trust that `help <anything-in-docs>` returns real information.

