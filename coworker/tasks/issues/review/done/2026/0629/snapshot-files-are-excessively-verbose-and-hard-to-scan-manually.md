# Snapshot Files Are Excessively Verbose and Hard to Scan Manually

Even with `-i` (interactive only) and `-c` (compact) flags, snapshots of real-world pages produce deeply nested, verbose output dominated by `generic [ref=eXXXXX]` elements with no accessible name. Finding actual interactive elements among the clutter requires tedious manual scanning.

**Steps to Reproduce:**
1. Navigate to a content-rich page (e.g., Baidu search results)
2. Run `browser4-cli snapshot -i -c`
3. Read the resulting file

**Expected:** A concise, scannable representation of the page's interactive elements — ideally a flat list of links, buttons, and text content.

**Actual:** A 380+ line YAML file with deeply nested generic elements, many without accessible names or semantic labeling. Finding search result links among the clutter requires carefully scanning every line.

**Context:** SKILL.md advises "Never cat full snapshot files. Always use targeted domsnapshot get or domsnapshot query." However, for initial page exploration to discover refs, reading the snapshot is practically necessary. The `-i -c` flags help but don't solve the fundamental verbosity issue.

**Suggested Enhancement:** Add a `--text` or `--links-only` flag that outputs a flat, human-readable list of links and text content rather than a full accessibility tree. This would make initial page exploration and ref discovery much faster.

Labels: enhancement, UX

