# `domsnapshot get all` documented in SKILL.md but CLI uses `domsnapshot-get-all`

**Severity:** High  
**Category:** Documentation

## Summary
SKILL.md documents the bulk-extraction command as `browser4-cli domsnapshot get all <field> [selector]` (space-separated subcommand with "all" as a positional argument), but the actual CLI implements it as `domsnapshot-get-all` (kebab-case subcommand). Running the documented form produces `Error: Unknown field 'all'`.

## Steps to Reproduce
1. Read SKILL.md line 228 or search for `domsnapshot get all`
2. Run: `browser4-cli domsnapshot get all text "h2 span"`
3. Observe: `Error: Unknown field 'all'`

## Expected Behavior
Documentation should match the actual CLI interface. Either the CLI should accept `domsnapshot get all` as an alias, or the documentation should use `domsnapshot-get-all`.

## Actual Behavior
The documented syntax silently fails. The user must guess the correct command name or find it through trial and error.

## Context
Discovered during an Amazon product search evaluation. The `domsnapshot` subcommand namespace has two different naming conventions: `get` takes positional args, but `get-all` is a separate kebab-case command. This inconsistency is confusing.

## Suggested Improvement
1. Update SKILL.md to use `domsnapshot-get-all` consistently
2. Consider supporting `domsnapshot get all` as an alias for better discoverability
3. Audit the entire `domsnapshot` subcommand namespace for similar naming inconsistencies

---

