# `snapshot` output does not display element references inline

## Summary

The `snapshot` command writes the accessibility tree to a YAML file on disk and only outputs a file path in the terminal. Users must manually open the YAML file in a separate editor or viewer to find element references (refs) and plan their next command. This breaks the interactive workflow and forces a context switch between the CLI and a file viewer.

## Steps to Reproduce

1. Run `browser4-cli goto https://news.ycombinator.com/news`
2. Run `browser4-cli snapshot`

## Expected Behavior

The snapshot output should display the accessibility tree (or at least the most relevant elements with their refs) directly in the terminal, so the user can immediately see available element refs and understand the page structure.

## Actual Behavior

The terminal output shows only a file path, such as `[Snapshot](snapshot-20260619-123456.yml)`. The user must separately locate and open this file to find any element references.

## Impact

- Adds friction to every interaction that requires an element ref
- Forces users to leave the CLI workflow to inspect a file
- Particularly inefficient for multi-step workflows (e.g., Hacker News evaluation required multiple snapshots to find story links across top stories)
- New users may not know to look for or open the YAML file at all

## Suggested Improvements

- Add an `--inline` flag to print the snapshot tree to stdout
- Show key elements (with refs) inline by default, with a pointer to the full file for detailed inspection
- Display at minimum the top N interactive elements directly in the terminal output

Labels: UX, enhancement, discoverability
