# `click` command times out on certain elements (e.g., Amazon search box)

**Severity:** High | **Category:** Reliability

Clicking on standard `<input type="search">` elements can result in a hard HTTP timeout after 30 seconds, making click-based interaction workflows unusable on some real-world sites.

### Steps to Reproduce

1. Navigate to `https://www.amazon.com/`
2. Take a snapshot to find the search box ref (e39)
3. Run `browser4-cli click e39`
4. Wait 30 seconds

### Expected Behavior

Click succeeds within a reasonable time (<5 seconds).

### Actual Behavior

```
Error: HTTP request timed out [tool=browser_click, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s, sessionId=DEFAULT]
```

### Suggested Improvements

1. Investigate why clicks on standard `<input type="search">` elements timeout.
2. Implement retry with fallback (e.g., try JS click if CDP click fails).
3. Reduce default timeout with a clearer error message that distinguishes between a hung command and a genuinely slow page.

