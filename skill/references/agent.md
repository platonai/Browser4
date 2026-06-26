---
title: "Agent — Autonomous Browser Task Execution"
description: "Reference for agent commands (run, status, result), extract, and summarize. Submit natural-language tasks for autonomous browser execution driven by an LLM."
---

# Agent — Autonomous Browser Task Execution

Submit natural-language tasks and let Browser4's AI agent plan and execute browser actions autonomously. The agent reasons about the page, decides which actions to take, and completes the task asynchronously.

## Prerequisites

Agent commands require an LLM API key. Configure one provider via environment variables:

| Provider | Required Variables |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY`, `OPENROUTER_MODEL_NAME`, `OPENROUTER_BASE_URL` |
| Volcengine (ByteDance) | `VOLCENGINE_API_KEY`, `VOLCENGINE_MODEL_NAME`, `VOLCENGINE_BASE_URL` |
| OpenAI-compatible | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |
| Aliyun Qwen (DashScope) | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |

Example pattern (adapt to your provider):

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

If no valid LLM key is configured, `agent run` fails fast with a clear error.

## agent run

Submit a natural-language task for autonomous execution. Returns a task ID immediately — the task runs asynchronously.

```bash
browser4-cli agent run "Open browser4.io and summarize the hero section"
```

**Writing tasks:** Describe **what** you want, not how. The agent discovers elements, waits for pages, and adapts on its own. Good: "Go to amazon.com, search for 'wireless headphones', extract the top 5 product titles and prices." Avoid step-by-step ref-based instructions.

Output on success:

```
Task submitted: agent-task-1
Next: browser4-cli agent status agent-task-1
```

## agent status

Poll the progress of a running agent task.

```bash
browser4-cli agent status <task-id>
```

Returns the current task status as JSON:

```json
{"id":"agent-task-1","status":"RUNNING","statusCode":null,"processState":"processing","message":"Navigating..."}
```

| Status | Meaning |
|---|---|
| `RUNNING` | Agent is actively working |
| `COMPLETED` | Task finished — call `agent result` |
| `FAILED` | Task errored — inspect `message` and `statusCode` |
| `EXPECTATION_FAILED` | Precondition failed (e.g., missing LLM config, status 417) |

Poll until status reaches a terminal state before calling `agent result`.

## agent result

Fetch the completed output of an agent task.

```bash
browser4-cli agent result <task-id>
```

Output depends on the task — plain text for summarization, structured JSON for extraction:

```json
{"products":[{"title":"Wireless Headphones Pro","price":"$79.99","rating":"4.5 stars"}]}
```

Always confirm completion via `agent status` first — incomplete tasks may return empty or partial results.

## Complete Workflow

```bash
browser4-cli agent run "Open browser4.io and summarize the hero section"
browser4-cli agent status agent-task-1    # poll until COMPLETED
browser4-cli agent result agent-task-1
```

## Combining with Standard Commands

Agent tasks operate independently of standard browser sessions, but you can compose them:

```bash
browser4-cli open https://app.example.com
browser4-cli state-load auth.json
browser4-cli agent run "Navigate to the reports dashboard, extract all monthly metrics, and summarize trends"
```

## Related Commands (synchronous, current session)

### extract

Extract structured data from the current page. Operates synchronously.

```bash
browser4-cli extract "product name, price, ratings"
browser4-cli extract "all article headlines and authors" --schema='{"fields":[{"name":"title","type":"string"},{"name":"author","type":"string"}]}'
```

### summarize

Summarize page content. Operates synchronously.

```bash
browser4-cli summarize "summarize the product reviews"
browser4-cli summarize --selector="#content"
```

## Error Handling

- `agent run` exits non-zero when the backend is unreachable or no LLM key is configured.
- `agent status` / `agent result` print the backend payload as-is. Inspect `status`, `statusCode`, and `message` fields on failure.
- Task IDs may not persist across backend restarts. Poll `agent status` to confirm a task is still alive after a restart.
- `extract` and `summarize` are synchronous and block until complete. Use `agent run` for asynchronous multi-step tasks.
- Agent subcommands use spaced form (`agent run`, not `agent-run`) and are not supported in `batch` mode.
