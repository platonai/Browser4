---
title: "Agent — Autonomous Browser Task Execution"
description: "Reference for agent commands (run, status, result), extract, and summarize. Submit natural-language tasks for autonomous browser execution driven by an LLM."
tier: procedure
---

# Agent — Autonomous Browser Task Execution

Submit natural-language tasks and let Browser4's AI agent plan and execute browser actions autonomously. Supports both asynchronous multi-step tasks (`agent run`) and synchronous single-page operations (`extract`, `summarize`).

## Prerequisites

Agent commands require an LLM API key, which must be visible to the **Browser4 backend server** process (the CLI only sends HTTP requests to the backend; the backend calls the LLM). When the CLI auto-starts the backend, set the key in the CLI's own environment before the first launch. Configure one provider via environment variables:

| Provider | Required Variables |
|---|---|
| DeepSeek | `DEEPSEEK_API_KEY` |
| OpenRouter | `OPENROUTER_API_KEY`, `OPENROUTER_MODEL_NAME`, `OPENROUTER_BASE_URL` |
| Volcengine (ByteDance) | `VOLCENGINE_API_KEY`, `VOLCENGINE_MODEL_NAME`, `VOLCENGINE_BASE_URL` |
| OpenAI-compatible | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |
| Aliyun Qwen (DashScope) | `OPENAI_API_KEY`, `OPENAI_MODEL_NAME`, `OPENAI_BASE_URL` |

```bash
# Set in the environment of the process that runs the backend server.
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

> The key must reach the backend server process, not just your CLI shell. Run `browser4-cli doctor` to confirm whether "✓ LLM is configured" on the backend.
>
> `doctor` also prints the configuration file it reads (`Config file: …`), and
> `browser4-cli doctor --fix` writes a commented template there
> (`~/.browser4/config/conf-enabled/application-private.properties`) — uncomment one provider
> block, add the key, then restart the backend. The template never overwrites an existing file.
>
> ⚠️ Configure **exactly one** provider. When several provider keys are present only one is
> used — the first in the built-in detection order, in which `openai` comes *after* the
> dedicated providers such as `deepseek`. `doctor` now prints which key won
> (`Selected key`) and the model the backend will request (`Active model`); if they are not
> yours, remove the other key or add `llm.provider.deny.list=deepseek`. See
> [LLM configuration](../../../docs/config.md#which-provider-is-used).

If no valid LLM key is configured, `agent run` fails fast with a clear error.

## Quick Start

```bash
browser4-cli agent run "Open browser4.io and summarize the hero section"
# agent run prints a UUID task id (e.g. 3b27930e-48bc-4460-aae7-be521f0a9194)
browser4-cli agent status <task-id>   # poll until "status": "done"
browser4-cli agent result <task-id>
```

## When to Use

Use **agent** for natural-language tasks where you describe what you want without specifying click/fill/snapshot steps. Use **extract**/**summarize** for synchronous single-page LLM-powered extraction. For deterministic extraction with CSS selectors, prefer `htmlsnapshot get` or `htmlsnapshot query` (no LLM key needed).

## How It Works

`agent run` submits a natural-language task to the Browser4 backend, which uses an LLM to plan and execute browser actions autonomously. The agent reasons about the page, discovers elements, and adapts — it does not need refs or step-by-step instructions. Tasks run asynchronously; poll with `agent status` and fetch results with `agent result`. `extract` and `summarize` are synchronous variants that operate on the current page.

### Where the agent browses, and where it writes

The agent runs **on the backend**, driving the session's own browser through the
session's bound driver — it is not a second browser you can attach to. Two
consequences matter in practice:

- **Its navigation is not visible in your session's tab list.** The backend saves
  the URL you were on before the task and navigates back to it when the task ends,
  so `tab-list`, `page-info` and `snapshot` keep showing your own page and the
  agent's intermediate pages never appear there. Observe its work through
  `agent status` (step count and message) and through the artifacts it reports.
- **Its deliverables are files, not always fields.** When a task says "extract X",
  the agent usually writes the data into a file under its own per-task **workspace**
  and returns a short summary. `agent result` names that directory (`workspace`) and
  lists the files the agent reported (`filesChanged`), so the deliverable is
  reachable without searching the backend's app-data tree by hand:

```bash
browser4-cli agent result <task-id>
# ...
# Key findings:
#   - Born 31 January 1956 in The Hague
# Files the agent reported changing:
#   guido_van_rossum.md
# Task workspace: C:\Users\me\.browser4\...\agent\2026\10\15\<uuid>\workspace
```

The workspace lives on the machine that runs the backend — when the backend is
remote, copy the artifacts from there (or have the agent return the data inline).
Each task gets its own workspace, so artifacts of two tasks never collide.

## Patterns

### 1. Asynchronous Multi-Step Task

```bash
browser4-cli agent run "Go to amazon.com, search for 'wireless headphones', extract the top 5 product titles and prices"
browser4-cli agent status <task-id>   # poll until "status": "done"
browser4-cli agent result <task-id>
```

**Writing tasks:** Describe **what** you want, not how. Good: "extract the top 5 product titles and prices." Avoid step-by-step ref-based instructions.

### 2. Wait for Completion (Optional)

`agent run --wait` blocks until the task reaches a terminal state, then prints
the final result. The wait window defaults to 600 seconds — long enough for
simple tasks, but browsing agents routinely exceed it. Tune it per invocation
with `--wait-timeout` (seconds) or globally via the
`BROWSER4_CLI_AGENT_WAIT_TIMEOUT_SECS` environment variable:

```bash
# Block up to 30 minutes
browser4-cli agent run "Find a STEM toy for a 10-year-old girl on amazon.com" --wait --wait-timeout=1800
```

When the wait window expires, the CLI exits non-zero but the task keeps running
server-side. The task is still tracked locally, so `agent list` and
`agent status <task-id>` can pick it up later:

```bash
browser4-cli agent list          # the timed-out task stays listed as processing
browser4-cli agent status <task-id>
browser4-cli agent result <task-id>
```

### 3. Poll Status

```bash
browser4-cli agent status <task-id>
```

Returns the **shared task envelope** — the same shape every long-running tool
(`crawl`, `swarm`, `command`) answers with:

```json
{"taskId":"3b27930e-48bc-4460-aae7-be521f0a9194","status":"running","statusCode":202,
 "message":"Agent planning next action","processed":3,"elapsedMs":184512,
 "statusTool":"command_status","resultTool":"command_result","cancelTool":"command_cancel"}
```

| Field | Meaning |
|---|---|
| `taskId` | The task id (also printed by `agent run`) |
| `status` | `queued` \| `running` \| `done` \| `failed` \| `cancelled` — the **only** field to branch on |
| `statusCode` | The backend's own code (200 done, 404 no such task, 417 failed) |
| `message` | Human-readable progress while the task is healthy |
| `processed` | Agent progress: the live tool-call count while a CLI tool-loop run is in flight, or the recorded step count once steps were written |
| `elapsedMs` | How long the task has been running, so a long `running` is distinguishable from a just-started one |
| `error` | Present only when `status` is `failed` |
| `statusTool` / `resultTool` / `cancelTool` | The MCP tools a generic client should call next |

**Terminal check:** poll until `status` is `done`, `failed` or `cancelled`.
`queued`/`running` mean "keep polling". Do not wait for the pre-4.14
`processState`/`isDone` fields — the envelope above is the contract, and a task
that is `done` never changes again.

An unknown id answers `{"status":"failed","statusCode":404,...}` with the reason
in `error`, which `agent status`/`agent result` report as
`No agent task found with id '…'` and exit non-zero.

`agent list` renders the same knowledge as local labels — `queued`, `processing`,
`completed`, `failed (<code>)`, `cancelled` — refreshed from the backend on every
call, so its STATUS never contradicts `agent status`.

### 4. Fetch Results

```bash
browser4-cli agent result <task-id>
browser4-cli agent result <task-id> --json   # envelope + payload as one JSON object
```

Always confirm completion via `agent status` first — incomplete tasks may return empty or partial results.

The payload is the agent's **completion report**, not just a summary:

```json
{"taskId":"…","status":"done","processed":7,
 "result":{"summary":"…","keyFindings":["…"],"filesChanged":["report.md"],
           "problems":[],"workspace":"…/agent/2026/10/15/<uuid>/workspace"}}
```

`result.summary` is what the agent reported; `keyFindings` and `filesChanged`
carry the deliverable itself, and `workspace` is the directory its tools wrote
into. Human output prints the findings, the changed files and the workspace path
below the raw JSON, so a task whose answer is a file is usable without
filesystem archaeology.

### 5. Composing with Standard Commands

```bash
browser4-cli open https://app.example.com
browser4-cli state-load auth.json
browser4-cli agent run "Navigate to the reports dashboard, extract all monthly metrics, and summarize trends"
```

### 6. Synchronous Extraction (extract)

```bash
browser4-cli extract "product name, price, ratings"
browser4-cli extract "all article headlines and authors" --schema '{"fields":[{"name":"title","type":"string"},{"name":"author","type":"string"}]}'
```

**Output destination:** `extract` saves its result to a **timestamped file**
(`.browser4-cli/snapshot/extract-*.txt` under your working directory) and
prints only a link — it does **not** echo the payload to stdout by default.
Add `--stdout` (or `--raw`) to print the extracted content directly, or
`--filename <path>` to choose the saved path. When `--schema` is given, the
requested fields arrive as plain **top-level JSON** — both in the saved file
and on stdout — so `extract "..." --schema @schema.json --stdout` can be
piped straight into JSON consumers.

### 7. Synchronous Summarization (summarize)

```bash
browser4-cli summarize "summarize the product reviews"
browser4-cli summarize --selector "#content"
```

## Flags

| Flag | Applies to | Description |
|------|-----------|-------------|
| `--wait` | `agent run` | Block until the task reaches a terminal state, then print the result |
| `--wait-timeout <secs>` | `agent run` | Wait window in seconds (default 600; env `BROWSER4_CLI_AGENT_WAIT_TIMEOUT_SECS`) |
| `--coding` | `agent run` | Explicitly mark the task as a coding task (plain file/build work; no browser page). Omitted (the default) the task is a browser task — task type is never auto-detected |
| `--json` | `agent status`, `agent result`, `agent list` | Emit the envelope as JSON instead of the human rendering |
| `--all` | `agent list --clear` | Also untrack tasks that are not finished (they keep running server-side) |
| `--schema <json>` | `extract` | Enforce a JSON output schema for the extraction |
| `--selector <css>` | `summarize` | Scope summarization to elements matching a CSS selector |

### Coding tasks (--coding)

Browser is the product's primary mode, so an agent task is a **browser task by
default** — the CLI does not guess whether the task text "looks like" coding.
Pass `--coding` only when the task is pure file/build work (read/write files,
run Maven, scaffold a module) and needs no page:

```bash
browser4-cli agent run "Read pom.xml, fix the failing module build" --coding
```

Coding runs skip the driver health check, search-engine navigation and
screenshots, and the agent starts with the coding tool core; any task
containing a URL or page interaction stays a browser task regardless.

## Errors & Recovery

| Symptom | Recovery |
|----------|---------|
| `agent run` exits non-zero | Check backend is reachable and LLM key is configured |
| `No agent task found with id '…'` | The backend has no record of that id (cleared, expired, or the backend restarted) — `agent list` shows what is still tracked |
| Task still `running` after minutes | Poll `agent status` — browsing agents routinely take minutes; `processed` shows whether it is still stepping |
| `agent result` says the result carries no data | The agent may have written its deliverable to a file: the same output names the `workspace` it wrote into |
| `agent list` shows a different status than `agent status` | `agent list` refreshes from the backend on every call; if the backend is unreachable it prints a note and shows cached labels |
| Task lost after backend restart | Task ids may not persist; re-submit the task |
| `extract`/`summarize` blocking too long | Use `agent run` for async execution instead |
| Agent subcommands in batch mode | Not supported — use standalone commands |
