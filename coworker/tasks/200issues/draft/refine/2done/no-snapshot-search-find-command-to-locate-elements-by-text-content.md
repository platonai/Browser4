# No snapshot search / `find` command to locate elements by text content

There is no way to search within the current accessibility snapshot or find elements by their text content. On content-rich pages with large snapshots, users must manually scan the entire YAML file to locate the elements they want to interact with.

**Steps to reproduce:**
1. Take a large snapshot (e.g., Hacker News with 30 stories, 990 lines of YAML).
2. Try to find a specific element by its text content (e.g., a story titled "Zig").

**Expected behavior:** A way to search within the current snapshot or find elements by text (e.g., `find "Zig"` → returns matching refs like `e135`).

**Actual behavior:** No search command exists. Users must read the entire snapshot YAML file and manually scan for the text they want. For a 30-item HN page this is manageable; for larger pages it becomes cumbersome and error-prone.

**Suggested improvement:** Add a `find <text>` or `search <text>` command that searches the most recent snapshot and returns matching element refs. This would significantly improve the workflow for locating elements on content-rich pages.

Labels: enhancement, ux

