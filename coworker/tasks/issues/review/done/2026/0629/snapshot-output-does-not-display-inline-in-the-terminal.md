# Snapshot output does not display inline in the terminal

**Severity:** Medium  
**Category:** Enhancement / UX

## Summary
When running `browser4-cli snapshot -i -c`, only a file path is printed to stdout. The compact interactive snapshot tree is not displayed inline, forcing users to open and read a separate YAML file for every interaction.

## Steps to Reproduce
1. Navigate to any page
2. Run `browser4-cli snapshot -i -c`
3. Observe that only a file path is printed

## Expected Behavior
The compact snapshot tree should be displayed inline in the terminal. At minimum, the first N lines should be visible directly.

## Actual Behavior
Only the file path is shown. Users must `Read` the YAML file separately to find element refs for subsequent commands.

## Context
This slows workflows considerably. During testing, every interaction required switching context to read the snapshot file. The file path is not even clickable in all terminals, adding further friction.

## Suggested Improvement
- Print the interactive snapshot tree directly to stdout when using `-i -c` flags
- Alternatively, add a `--stdout` flag
- At minimum, display the first N lines (e.g., 50) with a note that the full output is saved to the file

---

