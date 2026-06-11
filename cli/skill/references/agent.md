# Agent — Autonomous Browser Task Execution

Submit natural-language tasks and let Browser4's AI agent plan and execute browser actions autonomously. The agent reasons about the page, decides which actions to take, and completes the task asynchronously — no need to know page structure or element refs ahead of time.

## Prerequisites

Agent commands require an LLM API key. Configure one of the following providers via environment variables:

| Provider | Required Variables |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY` |
| Volcengine (ByteDance) | `VOLCENGINE_API_KEY`, `VOLCENGINE_MODEL_NAME`, `VOLCENGINE_BASE_URL` |
| OpenAI-compatible | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |
| Aliyun Qwen (DashScope) | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |

Examples:

```bash
# DeepSeek
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx

# OpenRouter
export OPENROUTER_API_KEY=sk-or-v1-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export OPENROUTER_MODEL_NAME=openai/gpt-5.4
export OPENROUTER_BASE_URL=https://openrouter.ai/api/v1

# Volcengine (ByteDance)
export VOLCENGINE_API_KEY=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
export VOLCENGINE_MODEL_NAME=doubao-seed-2-0-pro-260215
export VOLCENGINE_BASE_URL=https://ark.cn-beijing.volces.com/api/v3

# OpenAI-compatible
export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export OPENAI_MODEL_NAME=gpt-4o
export OPENAI_BASE_URL=https://api.openai.com/v1

# Aliyun Qwen via DashScope
export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export OPENAI_MODEL_NAME=qwen-plus
export OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
```

If no valid LLM key is configured, `agent run` fails fast with a clear error message rather than silently queuing a doomed task.

## Comparison: Standard Commands vs Agent vs Swarm

| Interface | Model | Use when |
|---|---|---|
| Standard commands | Single action per invocation | You know the exact refs/selectors and want precise control |
| Agent CLI | Natural-language task → autonomous execution | You have a goal but don't know the page structure; multi-step exploration |
| Swarm CLI | Parallel contexts + X-SQL queries | High-throughput scraping, structured extraction across many pages |

## agent run

Submit a natural-language task for autonomous execution. The agent opens a browser, navigates, interacts with pages, and completes the task asynchronously. Returns a task ID immediately.

```bash
browser4-cli agent run "Open browser4.io and summarize the hero section"
```

### Arguments

| Argument | Required | Description |
|---|---|---|
| `task` | **Yes** | Natural-language description of what the agent should accomplish. Can be as simple as "navigate to example.com and take a screenshot" or as complex as a multi-step research workflow. |

### Output

On success, prints the task ID and a ready-to-copy follow-up command:

```
Task submitted: agent-task-1
Next: browser4-cli agent status agent-task-1
```

The task runs asynchronously — the CLI returns immediately. Use `agent status` to track progress and `agent result` to retrieve the output once complete.

### Writing Effective Task Descriptions

Describe **what** you want, not **how** to do it. The agent discovers elements, waits for pages to load, and adapts to dynamic content on its own.

| Good | Avoid |
|---|---|
| "Go to amazon.com, search for 'wireless headphones', and extract the top 5 product titles and prices" | "Click e15, then type 'wireless headphones' into e22, then click e30" |
| "Log into example.com with user@example.com / password123, then navigate to the dashboard and summarize the stats" | "Fill e1 with user@example.com, fill e2 with password123" |
| "Open the GitHub trending page and list the top 3 repositories with their star counts" | (let the agent discover the page structure) |

## agent status

Poll the progress of a running agent task.

```bash
browser4-cli agent status agent-task-1
```

### Arguments

| Argument | Required | Description |
|---|---|---|
| `id` | **Yes** | Task ID returned by `agent run`. |

### Output

Returns the current task status as a JSON payload. Typical fields include `id`, `status`, `statusCode`, `processState`, `message`, `agentState`, `agentHistory`, and `commandResult`.

```json
{
  "id": "agent-task-1",
  "status": "RUNNING",
  "statusCode": null,
  "processState": "processing",
  "message": "Navigating to https://example.com..."
}
```

### Status Values

| Status | Meaning |
|---|---|
| `RUNNING` | The agent is actively working on the task. |
| `COMPLETED` | The task finished successfully. Call `agent result` to retrieve the output. |
| `FAILED` | The task encountered an error. Inspect `message` and `statusCode` for details. |
| `EXPECTATION_FAILED` | The task failed a precondition check (e.g., missing LLM configuration, status code 417). |

Wait for `status: "COMPLETED"` (or another terminal status) before calling `agent result`. Polling is manual — call `agent status` repeatedly until the task reaches a terminal state.

## agent result

Fetch the completed output of an agent task.

```bash
browser4-cli agent result agent-task-1
```

### Arguments

| Argument | Required | Description |
|---|---|---|
| `id` | **Yes** | Task ID returned by `agent run`. |

### Output

Returns the completed task output. The format depends on the task description — results may be plain text or structured JSON:

**Plain text result (summarization task):**
```
The hero section of example.com contains a headline "Example Domain",
a subheading "for use in illustrative examples", and a link to more
information at iana.org.
```

**Structured result (extraction task):**
```json
{
  "products": [
    {"title": "Wireless Headphones Pro", "price": "$79.99", "rating": "4.5 stars"},
    {"title": "Budget Wireless Buds", "price": "$29.99", "rating": "4.2 stars"}
  ]
}
```

Always confirm the task is complete via `agent status` before fetching the result. If the task is still running, the result may be incomplete or empty.

## Complete Workflow

```bash
# 1) Submit an autonomous task
browser4-cli agent run "Open browser4.io and summarize the hero section"

# Output:
#   Task submitted: agent-task-1
#   Next: browser4-cli agent status agent-task-1

# 2) Poll progress (repeat as needed)
browser4-cli agent status agent-task-1

# Output:
#   {"id":"agent-task-1","status":"RUNNING","processState":"processing","message":"Navigating..."}

# 3) When status is COMPLETED, fetch the result
browser4-cli agent result agent-task-1

# Output:
#   The hero section of example.com contains...
```

## Combining Agent with Standard Commands

Agent tasks operate independently of standard browser sessions, but you can use them together:

```bash
# Use standard commands to set up state, then delegate to the agent
browser4-cli open https://app.example.com
browser4-cli state-load auth.json
browser4-cli agent run "Navigate to the reports dashboard, extract all monthly metrics, and summarize trends"

# Agent tasks work across sessions — no need to manage browser state
browser4-cli agent run "Go to github.com/trending, list the top 5 repos with descriptions"
browser4-cli agent status agent-task-2
browser4-cli agent result agent-task-2
```

## Related Commands

The following synchronous commands also use AI but operate on the **current** browser session (unlike `agent run` which is autonomous and task-ID-based):

### extract

Extract structured data from the current page using AI. Operates synchronously on the active session.

```bash
browser4-cli extract "product name, price, ratings"
browser4-cli extract "all article headlines and authors" --schema='{"fields":[{"name":"title","type":"string"},{"name":"author","type":"string"}]}'
```

| Argument/Option | Required | Description |
|---|---|---|
| `instruction` | **Yes** | What data to extract from the current page. |
| `--schema` | No | JSON schema to constrain the extracted data structure. |

### summarize

Summarize page content using AI. Operates synchronously on the active session.

```bash
browser4-cli summarize "summarize the product reviews"
browser4-cli summarize --selector="#content"
```

| Argument/Option | Required | Description |
|---|---|---|
| `instruction` | No | Summarization instruction. Defaults to a general summary if omitted. |
| `--selector` | No | CSS selector to limit summarization to a specific page region. |

## Error Handling

- `agent run` returns a non-zero exit code when the backend is unreachable or when the LLM/API key is not configured.
- `agent status` prints the status payload as-is. If the task failed, inspect the `status`, `statusCode`, and `message` fields in the returned JSON.
- `agent result` prints the result payload as-is. Always confirm the task is done via `agent status` first — incomplete tasks may return empty or partial results.
- If the backend is stopped or restarted while a task is running, the task may be lost. Poll `agent status` to confirm the task is still alive.
- Task IDs may not persist across backend restarts.

## Notes

- Agent subcommands use the spaced form (`agent run`, not `agent-run`) and are not supported in `batch` mode.
- Agent tasks manage their own browser sessions internally — they do not depend on the current CLI browser session slot.
- `extract` and `summarize` are synchronous commands that block until complete. Use `agent run` for asynchronous, autonomous multi-step tasks.
- LLM/API key configuration is required for all agent commands. See the Browser4 backend documentation for LLM configuration details.
