# Documentation inconsistency: `domsnapshot get all` vs `domsnapshot-get-all`

## Summary
SKILL.md documents the bulk extraction command as `browser4-cli domsnapshot get all <field> [selector]` (space-separated subcommand with `all` as a positional argument), but the actual CLI implements it as `domsnapshot-get-all` (a kebab-case top-level command). Attempting the documented form fails with `Error: Unknown field 'all'`.

## Steps to Reproduce
1. Read SKILL.md line 228: `browser4-cli domsnapshot get all <field> [selector]`
2. Run that exact command
3. Observe the error

## Expected Behavior
The documentation should match the actual CLI interface. If the command is `domsnapshot-get-all`, SKILL.md should use that name consistently.

## Actual Behavior
The documented form `domsnapshot get all` fails with `Error: Unknown field 'all'`. The user must guess or discover the correct kebab-case form `domsnapshot-get-all`.

## Suggested Fix
1. Update SKILL.md to use `domsnapshot-get-all` consistently
2. Consider supporting `domsnapshot get all` as an alias for discoverability, since users naturally expect subcommand namespacing to be consistent

Labels: bug, documentation, high
