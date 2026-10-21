# Snapshots Are Too Large for Practical Element Discovery on Content-Rich Pages

Snapshot output on real-world pages is prohibitively large, making it difficult to locate element references (`ref`) needed for interaction commands. This undermines the usability of the ref-based interaction model.

**Steps to Reproduce:**
1. Navigate to any content-rich page (e.g., Amazon search results).
2. Run `browser4-cli snapshot`.
3. Read the resulting YAML file to find element references.

**Expected Behavior:** A concise, scannable element tree focused on interactive elements, allowing quick discovery of references.

**Actual Behavior:**
- The snapshot for Amazon search results was **1227 lines**.
- Even with the `-i` (interactive only) flag, the output remained large due to navigation menus, carousels, and footer elements.
- Finding the 4 product result references required manually scrolling through hundreds of lines.
- With `-i` and `-c` (compact) together, output is still unwieldy.

**Suggested Improvements:**
- Add a `--depth` option that defaults to excluding deeply nested or hidden content.
- Add a `--filter="role:link,role:button"` flag to filter the tree by ARIA role.
- Provide a `browser4-cli find "<text>"` command for direct reference discovery (see related issue).
- Auto-group carousel and sidebar content under collapsible headings in snapshot output.

**Acceptance Criteria:**
- Snapshot output for an Amazon search results page can be filtered to show only the primary result elements within a single screen of text.
- Interactive-only (`-i`) snapshots exclude navigation chrome, footers, and carousel internals by default or via a simple flag.

Labels: enhancement, ux

