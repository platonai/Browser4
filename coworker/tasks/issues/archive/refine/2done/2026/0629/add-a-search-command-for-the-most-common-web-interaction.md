# Add a `search` Command for the Most Common Web Interaction

There is no `search` command in the CLI, and the multi-step workflow required to perform a simple web search is not documented. Searching is the most common web interaction, yet it requires users to discover a four-step process through trial and error.

**Steps to Reproduce:**
1. As a new user, attempt to search on a website.
2. Look for a `search` command in `browser4-cli help`.

**Expected Behavior:** A dedicated command such as `browser4-cli search "<query>"` exists, or a prominent search workflow is documented.

**Actual Behavior:** No `search` command exists. The required workflow — `snapshot` → find searchbox ref in YAML → `fill <ref>` → `press Enter` — must be discovered through trial and error. A new user has no obvious path to performing this fundamental task.

**Suggested Improvements:**
- Add a `browser4-cli search "<query>"` command that locates the page's search input by heuristic (matching `role=searchbox`, common element names, or placeholder text) and submits the query automatically.
- At minimum, document the search workflow pattern prominently in `SKILL.md` with a step-by-step example.

**Acceptance Criteria:**
- A user can execute a search on amazon.com with a single command.
- The search workflow is documented with a clear example in the primary user-facing documentation.

Labels: enhancement, ux

