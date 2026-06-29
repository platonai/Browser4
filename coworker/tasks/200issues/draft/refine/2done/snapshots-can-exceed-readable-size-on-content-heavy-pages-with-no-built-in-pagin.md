# Snapshots can exceed readable size on content-heavy pages, with no built-in pagination or filtering

On content-heavy pages such as Amazon search results, accessibility snapshots can grow too large to read in a single operation — the example snapshot was 480 KB across 512+ lines. There is no built-in pagination, size-limiting, or result-filtering mechanism to keep snapshots manageable.

**Steps to reproduce:**
1. Navigate to a content-heavy page (e.g., Amazon search results for "pens to draw on whiteboards").
2. Take a snapshot with `browser4-cli snapshot`.
3. Attempt to read the resulting YAML file.

**Expected behavior:** The snapshot is readable in a single read call, or there is a built-in mechanism to limit or paginate the output.

**Actual behavior:** The snapshot file exceeds typical read limits, forcing users to read it in multiple chunks and manually search for the content they need.

**Suggested improvements (one or more):**
1. Add a `--limit-products=N` or `--search-results` mode that returns only product-card subtrees.
2. Provide a `snapshot read <file> --offset/--limit` built-in command.
3. Raise the default read limit for snapshot files.

Labels: bug, ux, enhancement

