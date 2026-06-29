# Element ref validity across navigations is undocumented

The documentation does not clarify whether element refs from a snapshot remain valid after navigating away from and back to a page. Users are left uncertain whether they need to re-snapshot after every navigation, adding friction to common workflows.

**Steps to reproduce:**
1. Take a snapshot on a page, noting element refs.
2. Navigate away (e.g., click a link) and then navigate back to the original page (e.g., `go-back`).
3. Attempt to use the original refs.

**Expected behavior:** Documentation clearly states whether refs persist across navigations (`go-back`, `goto`).

**Actual behavior:** In practice, refs from an initial snapshot still worked after `go-back` on Hacker News. However, this behavior is not documented, leaving users uncertain whether they need to re-snapshot after every navigation.

**Suggested improvement:** Document the ref lifecycle: when refs become stale, when they persist, and best practices (e.g., "re-snapshot after any navigation that may change the DOM"). If refs are guaranteed to remain valid after `go-back` to the same URL, state this explicitly.

Labels: documentation

