# Browser4 MCP Server - Usage Examples

This document provides practical examples of using the Browser4 MCP Server.

## Prerequisites

1. Browser4 server running on http://localhost:8182
2. HTTP client (curl, httpie, or Postman)
3. (Optional) MCP-compatible AI assistant

## Basic Examples

### Example 1: Check Server Info

```bash
curl -X GET http://localhost:8182/api/mcp/info | jq
```

**Expected Output:**
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

### Example 2: List Available Tools

```bash
curl -X POST http://localhost:8182/api/mcp/list_tools | jq
```

**Expected Output:**
```json
{
  "tools": [
    {
      "name": "load_page",
      "description": "Load a web page and return its content. Supports advanced options like wait conditions, scrolling, and custom headers.",
      "inputSchema": {
        "type": "object",
        "properties": {
          "url": {
            "type": "string",
            "description": "The URL of the page to load"
          },
          "options": {
            "type": "string",
            "description": "Optional load options (e.g., '-expires 1d -refresh')"
          }
        },
        "required": ["url"]
      }
    },
    ...
  ]
}
```

### Example 3: Load a Simple Page

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "load_page",
    "arguments": {
      "url": "https://example.com"
    }
  }' | jq
```

### Example 4: Load Page with Options

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "load_page",
    "arguments": {
      "url": "https://example.com",
      "options": "-expires 1h -refresh"
    }
  }' | jq
```

## Advanced Examples

### Example 5: Extract Text from Element

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "extract_text",
    "arguments": {
      "url": "https://example.com",
      "selector": "h1"
    }
  }' | jq
```

### Example 6: Get Page Metadata

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "get_page_info",
    "arguments": {
      "url": "https://github.com"
    }
  }' | jq
```

### Example 7: Scrape Data with X-SQL

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "scrape_data",
    "arguments": {
      "sql": "SELECT dom_first_text(dom, \"h1\") as title FROM load_and_select(\"https://example.com\", \"body\")"
    }
  }' | jq
```

## Real-World Use Cases

### Use Case 1: News Aggregation

Extract headlines from Hacker News:

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "scrape_data",
    "arguments": {
      "sql": "SELECT dom_all_text(dom, \".titleline>a\") as headlines FROM load_and_select(\"https://news.ycombinator.com\", \".athing\")"
    }
  }' | jq
```

### Use Case 2: Product Information

Get product details from an e-commerce site:

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "extract_text",
    "arguments": {
      "url": "https://www.amazon.com/dp/B08N5WRWNW",
      "selector": "#productTitle"
    }
  }' | jq
```

### Use Case 3: Documentation Scraping

Extract documentation text:

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "extract_text",
    "arguments": {
      "url": "https://docs.python.org/3/",
      "selector": ".body"
    }
  }' | jq
```

## Integration with AI Assistants

### Claude Desktop Configuration

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%/Claude/claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "browser4": {
      "command": "node",
      "args": [
        "/path/to/browser4-mcp-proxy.js",
        "http://localhost:8182"
      ]
    }
  }
}
```

Where `browser4-mcp-proxy.js` is a simple Node.js script that bridges MCP STDIO to HTTP:

```javascript
#!/usr/bin/env node
const http = require('http');
const readline = require('readline');

const baseUrl = process.argv[2] || 'http://localhost:8182';

const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: false
});

rl.on('line', async (line) => {
  try {
    const request = JSON.parse(line);
    const path = `/api/mcp/${request.method}`;
    
    const options = {
      hostname: 'localhost',
      port: 8182,
      path: path,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      }
    };

    const req = http.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => data += chunk);
      res.on('end', () => {
        console.log(data);
      });
    });

    req.on('error', (error) => {
      console.error(JSON.stringify({
        error: error.message
      }));
    });

    req.write(JSON.stringify(request.params));
    req.end();
  } catch (error) {
    console.error(JSON.stringify({
      error: error.message
    }));
  }
});
```

## Python Client Example

```python
import requests
import json

class Browser4MCPClient:
    def __init__(self, base_url="http://localhost:8182"):
        self.base_url = base_url
        self.mcp_url = f"{base_url}/api/mcp"
    
    def list_tools(self):
        """List all available tools."""
        response = requests.post(f"{self.mcp_url}/list_tools")
        return response.json()
    
    def call_tool(self, tool_name, arguments):
        """Call a specific tool with arguments."""
        payload = {
            "name": tool_name,
            "arguments": arguments
        }
        response = requests.post(
            f"{self.mcp_url}/call_tool",
            json=payload
        )
        return response.json()
    
    def load_page(self, url, options=None):
        """Load a page and return its content."""
        args = {"url": url}
        if options:
            args["options"] = options
        return self.call_tool("load_page", args)
    
    def extract_text(self, url, selector=None):
        """Extract text from a page."""
        args = {"url": url}
        if selector:
            args["selector"] = selector
        return self.call_tool("extract_text", args)
    
    def get_page_info(self, url):
        """Get page metadata."""
        return self.call_tool("get_page_info", {"url": url})
    
    def scrape_data(self, sql):
        """Scrape data using X-SQL."""
        return self.call_tool("scrape_data", {"sql": sql})

# Usage
client = Browser4MCPClient()

# List tools
tools = client.list_tools()
print(f"Available tools: {len(tools['tools'])}")

# Load a page
result = client.load_page("https://example.com")
print(result["content"][0]["text"])

# Extract text
text = client.extract_text("https://example.com", selector="h1")
print(text["content"][0]["text"])

# Get page info
info = client.get_page_info("https://github.com")
print(info["content"][0]["text"])
```

## Error Handling Examples

### Example: Missing Required Parameter

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "load_page",
    "arguments": {}
  }' | jq
```

**Response:**
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

### Example: Unknown Tool

```bash
curl -X POST http://localhost:8182/api/mcp/call_tool \
  -H "Content-Type: application/json" \
  -d '{
    "name": "unknown_tool",
    "arguments": {}
  }' | jq
```

**Response:**
```json
{
  "isError": true,
  "content": [
    {
      "type": "text",
      "text": "Error: Unknown tool: unknown_tool"
    }
  ]
}
```

## Testing with HTTPie

HTTPie provides a more user-friendly interface:

```bash
# List tools
http POST http://localhost:8182/api/mcp/list_tools

# Load page
http POST http://localhost:8182/api/mcp/call_tool \
  name=load_page \
  arguments:='{"url": "https://example.com"}'

# Extract text
http POST http://localhost:8182/api/mcp/call_tool \
  name=extract_text \
  arguments:='{"url": "https://example.com", "selector": "h1"}'
```

## Postman Collection

Import this JSON to create a Postman collection:

```json
{
  "info": {
    "name": "Browser4 MCP Server",
    "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  },
  "item": [
    {
      "name": "Get Server Info",
      "request": {
        "method": "GET",
        "url": "http://localhost:8182/api/mcp/info"
      }
    },
    {
      "name": "List Tools",
      "request": {
        "method": "POST",
        "url": "http://localhost:8182/api/mcp/list_tools"
      }
    },
    {
      "name": "Load Page",
      "request": {
        "method": "POST",
        "header": [
          {
            "key": "Content-Type",
            "value": "application/json"
          }
        ],
        "body": {
          "mode": "raw",
          "raw": "{\n  \"name\": \"load_page\",\n  \"arguments\": {\n    \"url\": \"https://example.com\"\n  }\n}"
        },
        "url": "http://localhost:8182/api/mcp/call_tool"
      }
    }
  ]
}
```

## Next Steps

- Explore the [MCP Server Documentation](mcp-server.md)
- Learn about [X-SQL Query Language](x-sql.md)
- Check out [REST API Examples](rest-api-examples.md)
- Review [Configuration Guide](config.md)
