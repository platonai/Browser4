# Browser4 MCP Server Design

## Overview

Browser4 exposes its browser-automation and AI-agent capabilities as an **MCP (Model Context Protocol) server**,
so external AI clients — Claude Desktop, Cursor, Windsurf, and any MCP-compatible tool — can control a
real Chrome browser without writing any automation code themselves.

---

## Architecture

```
┌──────────────────────────────┐         MCP / JSON-RPC
│  AI Client                   │ ◄──────────────────────── ┐
│  (Claude Desktop, Cursor …)  │                            │
└──────────────────────────────┘                            │
                                            ┌───────────────────────────────┐
                                            │  Browser4MCPServer            │
                                            │  (kotlin-sdk-server-jvm 0.8.1)│
                                            │                               │
                                            │  STDIO transport (default)    │
                                            │  SSE / WebSocket (optional)   │
                                            └────────────┬──────────────────┘
                                                         │  WebDriver
                                            ┌────────────▼──────────────────┐
                                            │  AgenticSession / WebDriver   │
                                            │  (real Chrome via CDP)        │
                                            └───────────────────────────────┘
```

The implementation lives in:

| File | Purpose |
|------|---------|
| `browser4-agentic/.../mcp/server/Browser4MCPServer.kt` | MCP Server — registers all tools |
| `browser4-agentic/.../mcp/server/Browser4MCPServerRunner.kt` | STDIO entry-point (`main`) |

---

## Tool Catalogue

### Category 1 — Navigation (5 tools)

| Tool | Rationale |
|------|-----------|
| `navigate_to` | Entry point of every browsing task. Without navigation the browser stays on the blank page. |
| `go_back` | Required for multi-step workflows where the agent needs to return to a previous state (e.g. pick a different search result). |
| `go_forward` | Complement to `go_back`; allows re-doing navigation. |
| `reload` | Necessary when pages stall or content is dynamic and must be refreshed. |
| `current_url` | Lets the LLM verify where it is before taking an action. Prevents loops caused by unexpected redirects. |

### Category 2 — Element Interaction (8 tools)

| Tool | Rationale |
|------|-----------|
| `click` | The most common browser action. Required for buttons, links, menus, tabs. |
| `type` | Appends text; needed for incremental typing (e.g. autocomplete). |
| `fill` | Clears then types; the standard form-filling pattern, avoiding stale input. |
| `hover` | Reveals dropdown menus, tooltips, and hover-activated UI components. Without it, many page elements are inaccessible. |
| `scroll_to` | Brings off-screen elements into the viewport so they can be interacted with. Many elements are not clickable until scrolled into view. |
| `check` / `uncheck` | Handles boolean toggles. Checkbox state cannot be set with `click` reliably (toggling requires knowing current state). |
| `press` | Sends keyboard events (Enter, Tab, Escape, arrow keys). Required for search-bar submission and keyboard-driven UIs. |

### Category 3 — Page Content (5 tools)

| Tool | Rationale |
|------|-----------|
| `get_text` | Reads visible text from a specific element — the primary way to extract structured data. |
| `get_html` | Returns the HTML subtree for richer inspection when the LLM needs to understand element hierarchy. |
| `get_attribute` | Reads `href`, `src`, `value`, `data-*`, and other metadata attributes that are not visible as text. |
| `page_source` | Returns the complete page HTML — fallback when selectors are unknown and the LLM needs to reason about structure. |
| `screenshot` | Provides a visual snapshot for multimodal models. Especially useful when the page layout matters for decisions, or when JS-rendered content is invisible in the HTML. |

### Category 4 — Waiting & Synchronisation (2 tools)

| Tool | Rationale |
|------|-----------|
| `wait_for_selector` | Dynamic pages (SPAs, AJAX, lazy-load) add elements after the initial paint. Without explicit waits, subsequent interactions target elements that do not yet exist. |
| `wait_for_navigation` | After clicking links or submitting forms, the browser is loading a new page. Acting before navigation completes leads to stale-element errors. |

### Category 5 — JavaScript Evaluation (1 tool)

| Tool | Rationale |
|------|-----------|
| `evaluate` | An intentional escape hatch. CSS selectors cannot reach shadow DOM, canvas elements, or internal browser state. JS evaluation provides full access. Annotated as potentially destructive so the client can apply additional scrutiny. |

---

## What is **not** exposed and why

| Capability | Reason not exposed |
|------------|-------------------|
| `run_agent` (autonomous LLM loop) | Recursive: the MCP client is already an LLM. Exposing a second LLM loop creates unpredictable nesting of model calls and costs. |
| `X-SQL` / scrape | Domain-specific to Pulsar's extraction engine. Exposing it requires the caller to know the X-SQL dialect, raising the barrier to entry unnecessarily. |
| `loadResource` / `loadJsoupResource` | Low-level HTTP utilities that bypass the real browser. MCP clients should use `navigate_to` instead to get authentic browser behaviour. |
| Cookie management (`getCookies`, `deleteCookies`) | Security-sensitive. Exposing raw cookie access to an MCP client could allow session hijacking if the client is compromised. |
| `evaluateDetail` / `evaluateValueDetail` | Internals of the CDP response. `evaluate` covers all practical use cases; the detail variants add complexity without benefit. |
| Tab management (`switchTab`, `bringToFront`) | Advanced lifecycle management. The single-session model of the MCP server makes multi-tab coordination the server's concern, not the client's. |
| Shell execution | Security risk; not related to browser automation. |

---

## Transport

### Default: STDIO

STDIO is the standard transport used by Claude Desktop, Cursor, Windsurf, and the MCP Inspector.
The server reads newline-delimited JSON-RPC from `stdin` and writes responses to `stdout`.

**Claude Desktop config** (`~/.config/Claude/claude_desktop_config.json`):
```json
{
  "mcpServers": {
    "browser4": {
      "command": "java",
      "args": [
        "-cp", "/path/to/browser4-all.jar",
        "ai.platon.pulsar.agentic.mcp.server.Browser4MCPServerRunnerKt"
      ]
    }
  }
}
```

### Optional: SSE (HTTP Streaming)

For remote or shared deployments, the server can be fronted by a Ktor HTTP server
using the `mcp {}` extension from `kotlin-sdk-server-jvm`.
The `Browser4MCPServer.server` field exposes the `Server` instance,
making it straightforward to attach any transport.

---

## Session & Concurrency Model

The current implementation uses a **single shared `WebDriver`** for all tool calls.
This mirrors how a human uses a browser: one active page at a time.

Concurrency implications:
- Tool calls from a single MCP client are serialised by the `Server` implementation.
- Running two MCP clients against the same server at the same time is unsupported and will cause race conditions.
- For multi-client scenarios, run one server process per client.

---

## Security Considerations

1. **`evaluate` tool** — arbitrary JavaScript execution.
   - Clients should present users with a confirmation prompt before running LLM-generated JavaScript against sensitive pages.
   - The tool can read cookies, exfiltrate data via `fetch()`, manipulate form values, and execute XSS payloads against the active session.
   - In production deployments, consider audit logging every `evaluate` call (expression + origin) and rate-limiting its invocation frequency.
   - If untrusted callers can reach the MCP server, consider restricting or disabling this tool entirely via a server configuration flag.
2. **No authentication** — the STDIO transport inherits process-level security. For network transports, add TLS and token-based auth at the reverse-proxy layer.
3. **Local browser data** — the browser instance may be logged into websites. Exposing the MCP server over a network transport reveals the user's authenticated sessions to any caller.
