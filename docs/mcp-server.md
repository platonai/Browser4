# Browser4 MCP Server

## Overview

Browser4 MCP Server exposes Browser4's powerful browser automation and data extraction capabilities through the Model Context Protocol (MCP), allowing AI assistants like Claude Desktop, Cursor, and other MCP clients to interact with web pages programmatically.

## Features

The MCP server provides four main tools:

### 1. load_page
Loads a web page and returns its content with metadata.

**Parameters:**
- `url` (required): The URL of the page to load
- `options` (optional): Load options (e.g., `-expires 1d -refresh`)

**Returns:** Page information including URL, title, status, content length, and text preview

**Example:**
```json
{
  "name": "load_page",
  "arguments": {
    "url": "https://example.com",
    "options": "-expires 1d"
  }
}
```

### 2. scrape_data
Extracts structured data from web pages using X-SQL queries.

**Parameters:**
- `sql` (required): The X-SQL query to execute

**Returns:** Extracted data in structured format

**Example:**
```json
{
  "name": "scrape_data",
  "arguments": {
    "sql": "SELECT dom_first_text(dom, '#title') as title FROM load_and_select('https://example.com', 'body')"
  }
}
```

### 3. extract_text
Extracts clean text content from a web page.

**Parameters:**
- `url` (required): The URL of the page
- `selector` (optional): CSS selector to extract text from specific elements

**Returns:** Extracted text content

**Example:**
```json
{
  "name": "extract_text",
  "arguments": {
    "url": "https://example.com",
    "selector": "#content"
  }
}
```

### 4. get_page_info
Gets metadata and information about a web page.

**Parameters:**
- `url` (required): The URL of the page

**Returns:** Page metadata including title, content type, status, encoding, and timing information

**Example:**
```json
{
  "name": "get_page_info",
  "arguments": {
    "url": "https://example.com"
  }
}
```

## Endpoints

The MCP server exposes three REST endpoints:

### GET /api/mcp/info
Returns server information including name, version, and capabilities.

**Response:**
```json
{
  "name": "browser4-mcp-server",
  "version": "1.0.0",
  "description": "Browser4 MCP Server - AI-powered browser automation and data extraction",
  "capabilities": {
    "tools": {}
  }
}
```

### POST /api/mcp/list_tools
Lists all available tools with their schemas.

**Response:**
```json
{
  "tools": [
    {
      "name": "load_page",
      "description": "Load a web page and return its content...",
      "inputSchema": {
        "type": "object",
        "properties": {...},
        "required": ["url"]
      }
    },
    ...
  ]
}
```

### POST /api/mcp/call_tool
Executes a specific tool with the provided arguments.

**Request:**
```json
{
  "name": "load_page",
  "arguments": {
    "url": "https://example.com"
  }
}
```

**Response:**
```json
{
  "content": [
    {
      "type": "text",
      "text": "Page loaded successfully:\nURL: https://example.com\n..."
    }
  ]
}
```

**Error Response:**
```json
{
  "isError": true,
  "content": [
    {
      "type": "text",
      "text": "Error: url is required"
    }
  ]
}
```

## Configuration

The MCP server can be configured through `application-rest.properties`:

```properties
# Enable/disable the MCP server endpoints
mcp.server.enabled=true

# MCP server name displayed to clients
mcp.server.name=browser4-mcp-server

# MCP server version
mcp.server.version=1.0.0

# MCP server description
mcp.server.description=Browser4 MCP Server - AI-powered browser automation and data extraction
```

## Usage with MCP Clients

### Claude Desktop

Add the following to your Claude Desktop configuration:

```json
{
  "mcpServers": {
    "browser4": {
      "command": "curl",
      "args": [
        "-X", "POST",
        "-H", "Content-Type: application/json",
        "-d", "@-",
        "http://localhost:8182/api/mcp/call_tool"
      ]
    }
  }
}
```

### HTTP Client

You can test the MCP server using any HTTP client:

```bash
# List available tools
curl -X POST http://localhost:8182/api/mcp/list_tools

# Call a tool
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "load_page",
    "arguments": {
      "url": "https://example.com"
    }
  }'
```

## Architecture

The MCP server implementation consists of:

- **MCPService**: Core service that manages tool definitions and executes tools
- **MCPController**: REST controller that exposes MCP protocol endpoints
- **AgenticSession**: Browser4 session for executing browser operations
- **ScrapeService**: Service for data extraction using X-SQL

The service integrates with Browser4's existing infrastructure:
- Uses Browser4's page loading and parsing capabilities
- Leverages X-SQL for structured data extraction
- Supports all Browser4 load options and configurations

## Error Handling

The MCP server provides comprehensive error handling:

- Invalid tool names return clear error messages
- Missing required parameters are caught and reported
- Tool execution errors are captured and returned with context
- All errors follow the MCP protocol format

## Performance Considerations

- The MCP server runs on the same port as the Browser4 REST API (default: 8182)
- Tool execution is synchronous but uses efficient coroutine-based implementation
- Page loading respects Browser4's caching and resource management
- Multiple tool calls can be made concurrently from different clients

## Future Enhancements

Planned improvements for the MCP server:

1. **Additional Tools**:
   - Browser automation tools (click, scroll, type)
   - Screenshot capture
   - Navigation history
   - Cookie management

2. **Advanced Features**:
   - Streaming responses for long-running operations
   - Batch tool execution
   - Tool call caching
   - Rate limiting and authentication

3. **Integration**:
   - Native MCP transport support (STDIO, WebSocket)
   - Integration with Browser4 agent capabilities
   - Tool composition and chaining

## Examples

### Example 1: Load and Extract Data

```bash
# First, load the page
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "load_page",
    "arguments": {
      "url": "https://news.ycombinator.com"
    }
  }'

# Then extract structured data
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "scrape_data",
    "arguments": {
      "sql": "SELECT dom_first_text(dom, \".storylink\") as title FROM load_and_select(\"https://news.ycombinator.com\", \".storylink\")"
    }
  }'
```

### Example 2: Extract Text from Specific Element

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "extract_text",
    "arguments": {
      "url": "https://example.com",
      "selector": "#main-content"
    }
  }'
```

### Example 3: Get Page Metadata

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "get_page_info",
    "arguments": {
      "url": "https://example.com"
    }
  }'
```

## Troubleshooting

### Server Not Responding

1. Check if Browser4 is running: `curl http://localhost:8182/api/system/health`
2. Verify MCP server is enabled in configuration
3. Check logs for any error messages

### Tool Execution Fails

1. Verify the tool name is correct (use `/api/mcp/list_tools` to see available tools)
2. Check that all required parameters are provided
3. Ensure the URL is accessible and valid
4. Review the error message in the response

### Performance Issues

1. Check Browser4 resource usage
2. Verify page loading options are optimal
3. Consider using caching options (`-expires`)
4. Monitor network connectivity

## Related Documentation

- [Browser4 REST API Documentation](../rest-api-examples.md)
- [X-SQL Query Language](../x-sql.md)
- [Model Context Protocol Specification](https://modelcontextprotocol.io)
- [Browser4 Configuration Guide](../config.md)

## License

Apache License 2.0 - See LICENSE file for details.
