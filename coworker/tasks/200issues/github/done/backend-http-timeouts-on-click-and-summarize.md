# Backend HTTP timeouts on `click`, `summarize`, and other commands

## Summary

Several core commands (`click`, `summarize`) consistently time out with HTTP request errors, making basic browser automation unreliable. This is a high-severity reliability issue that blocks task completion.

## Steps to Reproduce

1. Open a page and wait briefly
2. Run `browser4-cli click <ref>` or `browser4-cli summarize "..."`

## Expected Behavior

The command executes within a reasonable time and returns the expected result.

## Actual Behavior

The following error is returned: `HTTP request timed out [tool=browser_click, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s]`. This occurred on 3 separate commands during a single evaluation session: `click e311`, `summarize`, and `click e22527`.

## Additional Context

The 30-second default timeout may be too short for operations on complex, JavaScript-heavy pages (like Baidu with its dynamic content). The error message also does not guide users toward troubleshooting steps.

## Suggested Improvement

- Consider increasing the default timeout for click and agent operations
- Implement automatic retry with exponential backoff
- Improve the error message to suggest checking backend health (e.g., `browser4-cli status`)
- Consider making the timeout configurable

Labels: bug, high, reliability
