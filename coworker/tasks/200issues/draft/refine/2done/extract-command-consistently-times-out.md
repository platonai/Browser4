# `extract` command consistently times out at 30 seconds

## Summary

The `extract` command, which uses an AI agent to extract structured data from a page, consistently times out with the 30-second default timeout. This is the most severe reliability issue in the tool — the AI-based extraction feature never worked during testing.

## Steps to reproduce

1. Navigate to a page with structured data (e.g., Amazon search results).
2. Run `browser4-cli extract "extract the name, price, rating..."` on the search results page.

## Expected behavior

The AI agent extracts structured data from the page and returns it in a usable format.

## Actual behavior

The command fails with: `HTTP request timed out [tool=agent_extract, timeout=30s]`. The 30-second hard timeout is too short for AI-based extraction, which requires LLM inference time on top of page processing.

## Suggested resolution

- Significantly increase the agent tool timeout (to at least 120 seconds) to accommodate LLM inference latency.
- Provide a `--timeout` flag for long-running operations so users can configure it per-command.
- Document the expected latency for `extract` operations so users know what to expect.
- Consider adding a streaming/progressive output mode that returns results as the AI agent produces them.

Labels: bug, reliability
