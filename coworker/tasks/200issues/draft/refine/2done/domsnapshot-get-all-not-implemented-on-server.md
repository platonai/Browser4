# `domsnapshot-get-all` is not implemented on the server

## Summary
The `domsnapshot-get-all` CLI command, which is the primary non-AI method for bulk content extraction, fails with a server-side error: `Unknown tool: dom_snapshot_scrape_all`. The server endpoint that the CLI attempts to call does not exist in the running server version, making bulk CSS-selector-based extraction completely unavailable.

## Steps to Reproduce
1. Run `browser4-cli domsnapshot-get-all text "h2 span" --limit 10`
2. Observe the server error

## Expected Behavior
The command should execute successfully and return all matching elements as documented.

## Actual Behavior
The server returns `ERROR: Unknown tool: dom_snapshot_scrape_all`. The endpoint is not registered in the currently running server, so the command cannot function under any circumstances.

## Suggested Fix
Implement the `dom_snapshot_scrape_all` server endpoint, or if the endpoint has been renamed or relocated, update the CLI to call the correct endpoint. This is a **critical gap** — it blocks the primary non-AI content extraction path, forcing users into `extract` (requires LLM API key) or `eval` (requires JavaScript expertise).

Labels: bug, reliability, critical
