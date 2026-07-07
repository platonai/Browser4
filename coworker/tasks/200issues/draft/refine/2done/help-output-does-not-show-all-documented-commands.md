# Help output does not consistently list all documented commands

## Summary
The `browser4-cli help` output does not list all available commands. The AI-powered `extract` command appears briefly under an "Agent:" section but `summarize` is not listed at all in the CLI help, despite being documented in SKILL.md. This inconsistency makes commands undiscoverable for users who rely on the built-in help system.

## Steps to Reproduce
1. Run `cargo run -- help` (or `browser4-cli help`)
2. Look for `extract` and `summarize` commands
3. Compare the listed commands with those documented in SKILL.md

## Expected Behavior
All available commands should appear in `help` output with consistent formatting and categorization. Every command documented in SKILL.md should also be listed in the CLI help.

## Actual Behavior
`extract` appears under an "Agent:" section heading but `summarize` (also an AI-powered command) is not listed at all in the help output. Users who only consult `help` will not discover the `summarize` command.

## Suggested Fix
Ensure every implemented command is registered in the help system and appears in `help` output. Commands of the same category (e.g., AI-powered) should appear together consistently. Run an automated check that every command in SKILL.md also appears in `help` output.

Labels: bug, discoverability, medium
