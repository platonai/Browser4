# `summarize` and `extract` commands are unreliable on complex pages

## Summary

The `summarize` command consistently times out on content-heavy pages like Baidu search results, making one of the most valuable AI-powered features unreliable in practice.

## Steps to Reproduce

1. Navigate to a content-heavy page like Baidu search results
2. Run `browser4-cli summarize "..."`

## Expected Behavior

AI-powered summary returned within a reasonable time.

## Actual Behavior

The command times out after 30 seconds. The `summarize` command appears to send the full page content to an AI backend that cannot complete processing on rich, complex pages within the default timeout.

## Additional Context

These agent commands are among the most valuable for task completion but also the least reliable. The same issue likely affects the `extract` command.

## Suggested Improvement

- Stream partial results as they become available
- Implement a longer default timeout with progress indication
- Chunk large pages and process them incrementally
- Add a configurable timeout parameter

Labels: bug, high, reliability
