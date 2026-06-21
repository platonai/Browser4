# `summarize` command times out with opaque error

The `summarize` command — one of the most valuable AI-agent features — consistently times out with an unhelpful error message. The user receives no indication of whether the backend AI service is unavailable, misconfigured, or simply overloaded.

## Steps to reproduce

```
browser4-cli summarize "武汉龙虾节的历史背景"
```

## Expected behavior

An AI-powered summary of the current page content is returned.

## Actual behavior

```
Error: HTTP request timed out [tool=agent_summarize, endpoint=http://localhost:8182/mcp/call-tool, timeout=30s]
```

## Additional context

- The 30-second timeout is insufficient for AI-powered summarization on long or complex pages.
- The error message does not distinguish between "service unreachable," "service is slow," and "configuration issue" — leaving the user with no actionable next step.
- Consider increasing the default timeout for AI-powered commands and/or improving the error message with diagnostic information (e.g., whether the MCP endpoint responded at all, whether the tool was found, etc.).

