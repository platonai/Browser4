# Snapshot refs not shown inline in CLI output, requiring manual file reads

**Severity:** Low | **Category:** UX / Efficiency

After every interactive command (`goto`, `click`, `fill`), the CLI output shows `[Snapshot](path-to-file)` but does not surface any element refs inline. Users must open and read the snapshot YAML file separately to discover refs, adding a manual step between every interaction.

### Steps to Reproduce

1. Run any interactive command (`goto`, `click`, `fill`)
2. Observe that output shows `[Snapshot](path-to-file)` but not the refs

### Expected Behavior

Key interactive refs (search boxes, buttons, links) shown inline in CLI output for quick chaining, with the full snapshot file available for detailed inspection.

### Actual Behavior

Must read the snapshot YAML file separately to discover element refs. This adds a manual step between every interaction.

### Suggested Improvements

1. Add `--show-refs` flag to display top-level interactive elements inline.
2. Auto-detect and display common patterns: "Search box: e39, Search button: e340".
3. Consider a compact inline snapshot mode with just interactive elements.

