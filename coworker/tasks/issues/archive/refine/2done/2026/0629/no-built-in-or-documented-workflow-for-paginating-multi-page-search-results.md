# No Built-in or Documented Workflow for Paginating Multi-Page Search Results

When search results span multiple pages, there is no documented recommended workflow for paginating through results — users must figure it out by manually scanning snapshots for "next page" links.

**Steps to Reproduce:**
1. Perform a search that returns multiple pages of results
2. Consult SKILL.md and `--help` output for guidance on navigating to subsequent pages

**Expected:** Clear documentation describing the recommended approach for paginating through multi-page results (e.g., identifying and clicking "next page" links, scrolling to trigger infinite scroll, or a built-in pagination command).

**Actual:** No documentation or built-in command addresses pagination. The user must manually scan snapshots for pagination links and issue separate click/navigate commands.

**Workaround:** Manually find and click pagination links in each snapshot, or scroll and re-snapshot.

**Suggested Fix:** Document the recommended pagination workflow in SKILL.md. Consider whether a convenience command or flag (e.g., `browser4-cli snapshot --next-page`) would be valuable for this common task.

Labels: documentation, enhancement

