# `extract` command times out after 30 seconds on real-world pages

**Severity:** Critical  
**Category:** Bug / Reliability

## Summary
The `extract` command — advertised as an AI-oriented data extraction feature — consistently times out on real-world pages like Amazon search results. The 30-second HTTP timeout is insufficient for large pages.

## Steps to Reproduce
1. Navigate to a content-heavy page (e.g., Amazon search results)
2. Run `browser4-cli extract "<instruction for structured data extraction>"`
3. Observe the timeout

## Expected Behavior
The `extract` command completes and returns structured data within a reasonable timeframe.

## Actual Behavior
HTTP request timed out after 30 seconds:
```
Error: HTTP request timed out [tool=agent_extract, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s]
```

## Context
This is a critical blocker — `extract` is one of the primary AI-oriented features of browser4-cli, but it fails on the exact kind of real-world page where users would want to use it. The workaround was to abandon `extract` entirely and use raw JavaScript `eval` instead, which required 7 iterations to find correct CSS selectors.

## Suggested Improvement
- Increase the default timeout for the extract endpoint
- Provide a `--timeout` flag so users can configure it per-command
- Consider streaming or chunked responses for large pages

---

