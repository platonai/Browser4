# Session state is intermittently lost between CLI invocations

## Summary
The `browser4-cli` session state is unreliably persisted across separate CLI process invocations, even though the browser server continues running. A session that `browser4-cli list` reports as "Active" will intermittently fail with "Session required" on the next command, breaking step-by-step interactive workflows.

## Steps to Reproduce
1. `browser4-cli open --headed` — session created successfully
2. `browser4-cli goto "https://www.amazon.com/"` — navigation works
3. `browser4-cli snapshot` — fails with "Session required"
4. `browser4-cli list` — shows session "Active"
5. `browser4-cli goto "..." && browser4-cli snapshot` — chained commands work
6. Run next standalone command — may fail again

## Expected Behavior
Session state should be reliably persisted across CLI process invocations as long as the server is running. Each CLI invocation should find and use the active session without intermittent failures.

## Actual Behavior
Session state is intermittently lost between process invocations. Chaining commands in a single shell invocation (`goto && snapshot`) works around the issue, but standalone commands fail unpredictably. The `browser4-cli list` output contradicts the error, showing "Active" when the session is apparently unavailable.

## Suggested Fix
Investigate the session identification mechanism. Likely causes include a race condition in session file locking, an environment variable or cookie not being reliably carried between subprocess calls, or a timing issue in the server-side session lookup. This is the single biggest friction point for interactive usage.

Labels: bug, reliability, high
