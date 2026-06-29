# CLI session state is lost between process invocations

When using the browser4 CLI interactively across multiple shell invocations, the browser session state is intermittently lost. This was the single biggest friction point in evaluating the CLI for a realistic Amazon product comparison task — it affected nearly every interactive step and forced chained-command workarounds.

**Steps to reproduce:**
1. `browser4-cli open --headed` — session is created successfully
2. `browser4-cli goto "https://amazon.com"` — navigation works
3. `browser4-cli snapshot` — fails with `Session required`
4. `browser4-cli list` — shows session as `Active`
5. Run `goto && snapshot` chained in a single shell invocation — both succeed
6. Next standalone `browser4-cli` call — may fail again with `Session required`

**Expected behavior:** Session persists across CLI process invocations since the server process is long-running and `browser4-cli list` confirms the session is active.

**Actual behavior:** Session state is intermittently lost between process invocations. Subsequent standalone commands fail with `Session required` even though `browser4-cli list` still reports the session as `Active`. The only reliable workaround is chaining commands in a single shell invocation (e.g., `goto "..." && snapshot`).

**Suggested investigation:** Possible race condition in session identification, or the session cookie / environment variable / token is not being properly carried between independent CLI processes. The session lookup mechanism may rely on transient state that is not consistently re-established on each invocation.

Labels: bug, reliability

