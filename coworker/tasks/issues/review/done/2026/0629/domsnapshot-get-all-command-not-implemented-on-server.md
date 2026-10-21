# `domsnapshot-get-all` command not implemented on server

**Severity:** Critical  
**Category:** Bug / Reliability

## Summary
The `domsnapshot-get-all` CLI command sends a request for the `dom_snapshot_scrape_all` server tool, but the server endpoint does not exist. The command always fails with `ERROR: Unknown tool: dom_snapshot_scrape_all`. This is the primary non-AI content extraction method, and its absence forces users to rely on the LLM-powered `extract` command.

## Steps to Reproduce
1. Navigate to any page with repeating elements: `browser4-cli goto "https://www.amazon.com/s?k=pens"`
2. Run: `browser4-cli domsnapshot-get-all text "h2 span" --limit 10`
3. Observe: `ERROR: Unknown tool: dom_snapshot_scrape_all`

## Expected Behavior
The command should extract all matching elements from the DOM snapshot and return structured results.

## Actual Behavior
Server returns an unknown-tool error. The feature appears to be documented and wired up on the CLI side but never implemented on the server.

## Context
Discovered during an Amazon product search evaluation. After `domsnapshot get` (single-element) proved unreliable with CSS selectors, `domsnapshot-get-all` was the next logical approach for extracting multiple product titles/prices. Its absence meant falling back entirely to the AI `extract` command, which requires LLM API key configuration that may not be set up for all users.

## Suggested Improvement
Implement the `dom_snapshot_scrape_all` server endpoint. If the tool has been renamed, update the CLI to call the correct endpoint. This is the critical-path fix for non-AI content extraction.

---

