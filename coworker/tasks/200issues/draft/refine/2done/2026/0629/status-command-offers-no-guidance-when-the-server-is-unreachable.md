# `status` Command Offers No Guidance When the Server Is Unreachable

When the Browser4 server is not running, the `browser4-cli status` command reports the server as unreachable but provides no actionable next steps. A new user is left without direction on how to proceed.

**Steps to Reproduce:**
1. Perform a fresh install of Browser4 CLI.
2. Run `browser4-cli status`.

**Expected Behavior:** A message such as "Server not running. Use `browser4-cli goto <url>` or `browser4-cli open` to start it."

**Actual Behavior:** `Server health: UNREACHABLE (no response from http://localhost:8182)` — with no guidance on what to do next. The user must discover through trial and error that `goto` or `open` will auto-start the server.

**Suggested Improvement:** Add actionable guidance to the `status` output when the server is unreachable, directing the user to commands that will start the server automatically.

**Acceptance Criteria:**
- `browser4-cli status` output includes a clear next step when the server is unreachable (e.g., "Run `browser4-cli goto <url>` to start the server and navigate").
- The message is concise and does not obscure the status information.

Labels: enhancement, ux

