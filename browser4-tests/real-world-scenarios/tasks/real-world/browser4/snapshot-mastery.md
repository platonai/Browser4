# snapshot-mastery

1. Go to `https://en.wikipedia.org/wiki/Christopher_Alexander`.
2. Capture a full-page snapshot (`-v 0`) to see the entire accessibility tree.
3. Capture an interactive-only snapshot (`-i`) to see only the interactive elements.
4. Capture a scoped snapshot limited to the main content area. Consult the documentation for how to scope snapshots with CSS selectors.
5. Capture a snapshot with a limited depth (e.g., `-d 3`) to see a condensed view.
6. Capture a snapshot that includes URLs for link elements.
7. Click on a link in the article to navigate to a related page.
8. Capture a snapshot with auto-diff enabled to see what changed compared to before the click.
9. Use snapshot grep to search the auto-diff output for changed elements:
   - First, use case-insensitive search (`-i`) for a keyword from the new page.
   - Then, use context lines (`-C 3`) around a match to see surrounding elements.
   - Try inverted matching (`-v`) to exclude lines containing a common word.
   - Count matches with `-c`.
   - Search for a fixed string (not regex) with `-F`.
   - Match whole words only with `-w`.
   - If the grep supports it, filter by a CSS selector to narrow results.
10. Capture a snapshot directed to stdout (`--stdout`) and review the output format.
