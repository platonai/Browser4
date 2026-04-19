# browser4-cli Agent Commands

## Overview

The **Agent** commands in `browser4-cli` provide AI-powered browser automation
capabilities. These commands leverage Browser4's `BasicBrowserAgent` to perform
intelligent data extraction, page summarization, and autonomous multi-step tasks.

Agent commands require a running Browser4 server with an LLM provider configured
(see [Configuration](#configuration)).

---

## Commands

### `extract` — Extract structured data

Extract structured data from the current page using AI.

```
browser4-cli extract <instruction> [--schema=<json>]
```

**Arguments:**

| Argument      | Required | Description                                              |
|---------------|----------|----------------------------------------------------------|
| `instruction` | Yes      | What data to extract, e.g. `'product name, price, ratings'` |

**Options:**

| Option     | Description                                              |
|------------|----------------------------------------------------------|
| `--schema` | JSON schema to constrain the extracted data structure    |

**Examples:**

```bash
# Simple extraction
browser4-cli extract "product name, price, and ratings"

# Extraction with schema constraint
browser4-cli extract "product details" --schema='{"fields":[{"name":"title","type":"string","description":"Product title"},{"name":"price","type":"number","description":"Price in USD"}]}'
```

**How it works:**

The `extract` command maps to the `agent_extract` MCP tool, which calls
`BasicBrowserAgent.extract()`. The agent builds a rich prompt with the current
DOM snapshot and optional JSON schema, then uses a two-stage LLM call (extract +
metadata) to produce structured results.

---

### `summarize` — Summarize page content

Summarize the content of the current page (or a selected section) using AI.

```
browser4-cli summarize [instruction] [--selector=<css>]
```

**Arguments:**

| Argument      | Required | Description                                              |
|---------------|----------|----------------------------------------------------------|
| `instruction` | No       | Summarization instruction, e.g. `'summarize the product reviews'` |

**Options:**

| Option       | Description                                             |
|--------------|---------------------------------------------------------|
| `--selector` | CSS selector to limit the scope of summarization        |

**Examples:**

```bash
# Summarize the entire page
browser4-cli summarize

# Summarize with a specific instruction
browser4-cli summarize "give a brief overview of this product"

# Summarize a specific section
browser4-cli summarize --selector="#reviews"

# Combine instruction and selector
browser4-cli summarize "list the pros and cons" --selector="#customer-reviews"
```

**How it works:**

The `summarize` command maps to the `agent_summarize` MCP tool, which calls
`BasicBrowserAgent.summarize()`. The agent extracts the text content of the page
(or scoped to the provided CSS selector) and sends it to the LLM with the
summarization instruction.

---

### `agent-run` — Run an autonomous agent task

Submit a natural language task for the agent to execute autonomously. This is a
long-running operation that returns immediately with a task ID.

```
browser4-cli agent-run <task>
```

**Arguments:**

| Argument | Required | Description                                          |
|----------|----------|------------------------------------------------------|
| `task`   | Yes      | Natural language task for the agent to execute       |

**Examples:**

```bash
# Submit an agent task
browser4-cli agent-run "go to amazon.com, search for '4k monitors', and extract the top 5 results"

# The command returns a task ID
# Task submitted: abc-123-def
# Use 'browser4-cli agent-status abc-123-def' to check progress.
```

**How it works:**

The `agent-run` command submits the task to the Browser4 server via
`POST /api/commands/plain?async=true`. The server delegates the task to
`BasicBrowserAgent.run()`, which performs observe→act cycles until the task is
complete or the maximum number of steps is reached.

---

### `agent-status` — Check task status

Check the status of a running agent task.

```
browser4-cli agent-status <id>
```

**Arguments:**

| Argument | Required | Description                              |
|----------|----------|------------------------------------------|
| `id`     | Yes      | Task ID returned by `agent-run`          |

**Examples:**

```bash
browser4-cli agent-status abc-123-def
```

**Response:**

Returns a JSON object with the current status:

```json
{
  "id": "abc-123-def",
  "status": "running",
  "progress": "Step 3/10: Navigating to search results..."
}
```

---

### `agent-result` — Get task result

Get the final result of a completed agent task.

```
browser4-cli agent-result <id>
```

**Arguments:**

| Argument | Required | Description                              |
|----------|----------|------------------------------------------|
| `id`     | Yes      | Task ID returned by `agent-run`          |

**Examples:**

```bash
browser4-cli agent-result abc-123-def
```

---

## Workflow Example

A typical agent workflow:

```bash
# 1. Open a browser session
browser4-cli open https://www.amazon.com/dp/B08PP5MSVB

# 2. Extract structured data from the page
browser4-cli extract "product name, price, ratings, and review count"

# 3. Summarize the product description
browser4-cli summarize "give a brief overview of this product"

# 4. Run a complex multi-step task
browser4-cli agent-run "search for similar products under $500 and compare specifications"

# 5. Check the task status
browser4-cli agent-status <task-id>

# 6. Get the final result
browser4-cli agent-result <task-id>

# 7. Close the session
browser4-cli close
```

---

## Configuration

Agent commands require the Browser4 server to have an LLM provider configured.
Set the following in your `application.properties` or `application-private.properties`:

```properties
# Example: OpenRouter provider
openrouter.api.key=sk-your-api-key-here
openrouter.model.name=bytedance-seed/seed-1.6

# Example: OpenAI-compatible provider
# openai.api.key=sk-your-api-key-here
# openai.model.name=gpt-4o
# openai.base.url=https://api.openai.com/v1

# Example: DeepSeek provider
# deepseek.api.key=sk-your-api-key-here
```

---

## REST API Mapping

| CLI Command      | REST Endpoint                               | Method |
|------------------|---------------------------------------------|--------|
| `extract`        | `/mcp/call-tool` (tool: `agent_extract`)    | POST   |
| `summarize`      | `/mcp/call-tool` (tool: `agent_summarize`)  | POST   |
| `agent-run`      | `/api/commands/plain?async=true`            | POST   |
| `agent-status`   | `/api/commands/{id}/status`                 | GET    |
| `agent-result`   | `/api/commands/{id}/result`                 | GET    |

---

## See Also

- [browser4-cli-collective.md](browser4-cli-collective.md) — Collective session & parallel scraping commands
- [rest-api-examples.md](rest-api-examples.md) — REST API usage examples
- [BasicBrowserAgent.kt](../browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/BasicBrowserAgent.kt) — Agent implementation
