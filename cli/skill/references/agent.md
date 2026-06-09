# Agent — Autonomous Browser Task Execution

Submit natural-language tasks to Browser4's backend AI agent and let it plan and execute browser actions autonomously. The agent reasons about the page, decides which actions to take, and completes the task asynchronously — no need to know page structure or element refs ahead of time.

## Architecture

The agent lifecycle follows a submit → poll → fetch pattern, all driven through the MCP `command_run` / `command_status` / `command_result` tools on the Browser4 backend:

```
agent run <task>      →  submits a natural-language task, returns immediately with a task ID
agent status <id>     →  polls the task's progress (JSON payload with status, state, messages)
agent result <id>     →  fetches the completed task's output (plain text or structured JSON)
```

All agent subcommands use the spaced form (`agent <subcommand>`, not `agent-<subcommand>`). They are task-ID based and do not depend on the current saved CLI browser session slot. Agent subcommands are advanced commands and are not supported in `batch` mode.

## Comparison: Standard Commands vs Agent vs Swarm

| Interface | Model | Use when |
|---|---|---|
| Standard commands | Single action per invocation | You know the exact refs/selectors and want precise control |
| Agent CLI | Natural-language task → autonomous execution | You have a goal but don't know the page structure; multi-step exploration |
| Swarm CLI | Parallel contexts + X-SQL queries | High-throughput scraping, structured extraction across many pages |

## agent run

Submit a natural-language task for autonomous execution. The backend agent will open a browser, navigate, interact with pages, and complete the task asynchronously. Returns a task ID immediately.

```bash
browser4-cli agent run "Open example.com and summarize the hero section"
```

### Positional Arguments

| Argument | Required | Description |
|---|---|---|
| `task` | **Yes** | Natural-language description of what the agent should accomplish. Can be as simple as "navigate to example.com and take a screenshot" or as complex as a multi-step research workflow. |

### Behavior

- Submits the task to the backend via `command_run` and prints a task ID immediately.
- The task runs asynchronously on the backend — the CLI does not block waiting for completion.
- On success, prints `Task submitted: <task-id>` plus a ready-to-copy follow-up command:
  ```
  Task submitted: agent-task-1
  Next: browser4-cli agent status agent-task-1
  ```
- Performs a short status probe after submission. If the backend indicates a missing LLM/API key configuration, the command fails fast with a clear error message rather than silently queuing a doomed task.

### Error Handling

If the backend is not configured with a valid LLM key, `agent run` exits with a non-zero code and prints:
```
Agent task requires an LLM key and cannot execute.
The LLM is not configured, see docs/config/llm/llm-config.md
```

This fast-fail check happens during the post-submission probe, so the task ID is still generated but the CLI surfaces the configuration error immediately.

### Task Descriptions — Best Practices

Write tasks that give the agent clear goals without prescribing exact steps:

| Good | Avoid |
|---|---|
| "Go to amazon.com, search for 'wireless headphones', and extract the top 5 product titles and prices" | "Click e15, then type 'wireless headphones' into e22, then click e30" |
| "Log into example.com with user@example.com / password123, then navigate to the dashboard and summarize the stats" | "Fill e1 with user@example.com, fill e2 with password123" |
| "Open the GitHub trending page and list the top 3 repositories with their star counts" | (let the agent discover the page structure) |

The agent works best when you describe **what** you want, not **how** to do it. It discovers elements, waits for pages to load, and adapts to dynamic content on its own.

## agent status

Poll the progress of a running agent task.

```bash
browser4-cli agent status agent-task-1
```

### Positional Arguments

| Argument | Required | Description |
|---|---|---|
| `id` | **Yes** | Task ID returned by `agent run`. |

### Behavior

- Reads task status from `command_status` on the backend and prints the returned JSON payload as-is.
- The payload typically includes fields like `id`, `status`, `statusCode`, `processState`, `message`, `agentState`, `agentHistory`, and `commandResult`.
- Status is poll-based — there is no streaming or push notification. You must call `agent status` repeatedly until the task completes.

### Interpreting Status

```json
{
  "id": "agent-task-1",
  "status": "RUNNING",
  "statusCode": null,
  "processState": "processing",
  "message": "Navigating to https://example.com..."
}
```

Common `status` values:

| Status | Meaning |
|---|---|
| `RUNNING` | The agent is actively working on the task. |
| `COMPLETED` | The task finished successfully. Call `agent result` to retrieve the output. |
| `FAILED` | The task encountered an error. Inspect `message` and `statusCode` for details. |
| `EXPECTATION_FAILED` | The task failed a precondition check (e.g., missing LLM configuration, status code 417). |

Wait for `status: "COMPLETED"` (or a terminal error status) before calling `agent result`.

## agent result

Fetch the completed output of an agent task.

```bash
browser4-cli agent result agent-task-1
```

### Positional Arguments

| Argument | Required | Description |
|---|---|---|
| `id` | **Yes** | Task ID returned by `agent run`. |

### Behavior

- Reads the completed task result from `command_result` on the backend and prints the returned payload as-is.
- Depending on the task, the result may be plain text (a summary, extracted data) or structured JSON (extracted records, structured output).
- There is no fixed schema — the agent determines the output format based on the task description.

### Example Results

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

## Complete Workflow

```bash
# 1) Submit an autonomous task
browser4-cli agent run "Open example.com and summarize the hero section"

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

The agent operates independently of standard browser commands, but you can use them together:

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

The Agent category also includes two synchronous extraction/summarization commands that operate on the **current** browser session (unlike `agent run` which is autonomous and task-ID-based):

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
| `--selector` | No | CSS selector to limit the scope of summarization to a specific page region. |

## Error Handling

- `agent run` returns a non-zero exit code when the backend is unreachable or when the post-submission probe detects a missing LLM/API key configuration.
- `agent status` prints the backend status payload as-is. If the task failed server-side, inspect the `status`, `statusCode`, and `message` fields in the returned JSON.
- `agent result` prints the backend result payload as-is. If the task has not yet completed, the result may be incomplete or empty — always confirm the task is done via `agent status` first.
- If the backend is stopped or restarted while a task is running, the task may be lost. Poll `agent status` to confirm the task is still alive.
- Task IDs are ephemeral — they are managed by the backend and may not persist across backend restarts.

## Notes

- Agent subcommands (`agent run`, `agent status`, `agent result`) are advanced commands and are not supported in `batch` mode.
- The flat forms `agent-run`, `agent-status`, `agent-result` are rejected with a message directing users to the spaced form.
- Agent tasks do not depend on the current CLI browser session slot — they manage their own browser sessions internally.
- The backend agent reasons about pages using accessibility-tree snapshots, not screenshots. It discovers elements dynamically rather than requiring pre-known refs.
- `extract` and `summarize` are synchronous commands that operate on the current session — they block until complete. Use `agent run` for asynchronous, autonomous multi-step tasks.
- LLM/API key configuration is required for all agent commands. See the Browser4 backend documentation for LLM configuration.
