# Commands Unexpectedly Execute as Background Tasks Instead of Synchronously

When running interactive commands such as `fill` and `click`, they spawn as background tasks rather than executing synchronously and returning output directly. This behavior contradicts the workflow shown in SKILL.md, where inline output patterns are documented (e.g., `### Page` / `### Snapshot` blocks appearing immediately after command execution).

**Steps to Reproduce:**
1. Open a session and navigate to any page with `browser4-cli goto <url>`
2. Run `browser4-cli fill <ref> "<text>"`
3. Observe the output message

**Expected:** The command executes synchronously, returning a result (e.g., page URL, snapshot reference) directly in the terminal, matching the documentation.

**Actual:** The command reports `Command running in background with ID: ...` and the user must poll `TaskOutput` to retrieve results. Every interaction becomes a two-step process: run command → poll for result.

**Impact:** This is the single biggest UX problem encountered during real-world usage. It doubles the number of steps for every interaction, breaks the mental model presented in the documentation, and would confuse a first-time user who wouldn't know to use `TaskOutput` to retrieve results. The task flow is dramatically slower and more cumbersome than expected.

**Suggested Fix:** Investigate why the harness runs these commands as background tasks. Either fix the harness to execute them synchronously, or update SKILL.md with explicit documentation of the background-task behavior and instructions on how to retrieve output via `TaskOutput`.

Labels: bug, UX

