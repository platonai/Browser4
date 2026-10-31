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

> The key must reach the backend server process, not just your CLI shell: `browser4-cli doctor`
> confirms "✓ LLM is configured", and `doctor --fix` writes a commented template
> (`~/.browser4/config/conf-enabled/application-private.properties`) without touching an existing
> file. Configure **exactly one** provider — leftover keys compete in Browser4's provider order
> (`deepseek` wins over `openai`, which wins over `groq`); `doctor` names the winner
> (`Selected key`) and the model (`Active model`), and `llm.provider.deny.list=deepseek` excludes
> one. See [LLM configuration](../../../docs/config.md#which-provider-is-used).

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

The agent runs **on the backend**, driving the current session's own browser through
its bound driver — never a second browser you can attach to. `agent run` targets the
CLI's session: `-s <name>` when given, else the unnamed slot (including a browser
attached via `attach --extension` / `attach --cdp`), or the sole recorded
`swarm create` session; `--coding` tasks run with no browser at all.

The browser is launched **lazily**: submitting a task opens nothing, and it starts on
the first tool call that needs a page — so a task driving an attached browser through
`b4.run("goto …")` opens **no additional browser**. An attached session whose browser
is not connected never falls back to a fresh Chrome; the page tool fails with a
re-attach instruction.

Two consequences matter in practice:

- **Its navigation is not visible in your session's tab list.** The backend restores
  the URL you were on when the task ends, so `tab-list`, `page-info` and `snapshot`
  keep showing your own page and the agent's pages never appear there. Observe the
  work through `agent status` (step count and message) and its artifacts.
- **Its deliverables are files, not always fields.** "Extract X" usually writes the
  data into the task's own **workspace**; `agent result` names it (`workspace`) and
  lists the files the agent reported (`filesChanged`):

```bash
browser4-cli agent result <task-id>
# ...
# Key findings:
#   - Born 31 January 1956 in The Hague
# Files the agent reported changing:
#   guido_van_rossum.md
# Task workspace: C:\Users\me\.browser4\...\agent\2026\10\15\<uuid>\workspace
```

The workspace lives on the machine that runs the backend (copy the artifacts from
there when it is remote). Each task gets its **own** directory
(`<agent data dir>/workspace.<task-id>`), so another run's file is never in this run's
`filesChanged`; `--resume <task-id>` keeps the interrupted task's workspace (the point
of resuming), `--shared-workspace` restores the old shared
`<agent data dir>/workspace`, and `--fresh-workspace` is the default behaviour
already. Input copying, the read rules and the deliverable format are in
[agent-workspace.md](agent-workspace.md).

### Cost control (`--token-budget`, `--follow` usage lines)

A run is capped at `agent.token.budget.total` (5M tokens by default) and aborts with
`TOKEN_BUDGET` before it can burn unbounded provider credits. Two different knobs:

| Knob | Scope | Use it when |
|---|---|---|
| `agent run … --token-budget <n>` | **this task only** (a per-run override; cleared when the task ends) | one long task legitimately needs more (or less) than the default |
| `config set agent.token.budget.total <n>` | process-wide: every later task on this backend | the operator wants a new default |

`--token-budget 0` means unlimited (accounting only). `--follow` reports the spend:

```text
[04:31] tokens 412.0k / 5.00M (8%) · 37 requests
[12:02] tokens 4.02M / 5.00M (80%) · 118 requests ⚠ 80% of the token budget used
[15:24] ✖ token budget exhausted: 5.01M / 5.00M — the run is being aborted
```

The progress line is throttled (every 10 requests or 15s); the 80% warning appears
once and the abort line names both numbers. `--ndjson` carries the same information as
`effectiveTokenBudget` + `cumulativeTokens` on every `run.usage` line, and
`agent result --json` reports the budget as `result.effectiveTokenBudget`.

### Permissions (`--access`)

A task's coding tools run under one access **preset** (`full` / `write` / `read-build` /
`read`), and a refused step can be approved out of band through `coding.requestAccess`
plus the operator endpoints. The preset table, the `PERMISSION_DENIED | code=...`
catalogue, the approval/rail rules, the unattended default and the task-scoped reset
are in [agent-access.md](agent-access.md).

**Two layers, one boundary.** The preset and the string checks (`pathGuard`,
`workspaceBoundary`, `allowExternalAccess`) decide what the model may *spell* — they are
fast-fail and advisory, not enforcement. The boundary is the **execution sandbox**:
`coding.shell` and `coding.runCode` run inside it whenever `browser4.agent.sandbox` selects a
backend other than `off`.
A fresh Windows installation defaults to `windows-acl`; existing installations and Linux/macOS
stay `off` until switched. When the sandbox is active, an OS denial reaches the model as
`PERMISSION_DENIED | code=SANDBOX_DENIED | tool=… | needed=…` (raw evidence stays in the log),
and an unavailable sandbox — missing account or credential, failed ACLs, failed write probe,
unknown id — **fails the task**; there is no unsandboxed fallback. Switch values, defaults,
the ACL matrix, the account property and the `off` rollback are in
[config.md](../../../docs/config.md).

## Patterns

### 1. Asynchronous Multi-Step Task

```bash
browser4-cli agent run "Go to amazon.com, search for 'wireless headphones', extract the top 5 product titles and prices"
browser4-cli agent status <task-id>   # poll until "status": "done"
browser4-cli agent result <task-id>
```

**Writing tasks:** Describe **what** you want, not how. Good: "extract the top 5 product titles and prices." Avoid step-by-step ref-based instructions.

### 2. Wait for Completion (Optional)

`agent run --wait` blocks until the task reaches a terminal state, then prints the
result. The window defaults to 600s — browsing agents routinely exceed it — and is
tuned per invocation with `--wait-timeout` (seconds) or globally with
`BROWSER4_CLI_AGENT_WAIT_TIMEOUT_SECS`. When it expires the CLI exits non-zero but the
task keeps running server-side and stays tracked locally, so pick it up later:

```bash
browser4-cli agent run "Find a STEM toy for a 10-year-old girl on amazon.com" --wait --wait-timeout=1800
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

`result.summary` is what the agent reported; `keyFindings` and `filesChanged` carry the
deliverable itself, and `workspace` is the directory its tools wrote into. Human output
prints all three below the raw JSON, so a task whose answer is a file is usable without
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

### 8. Resume an Interrupted Task

A task that died with the backend (crash, restart, `stop`) leaves a checkpoint: its
instruction, its workspace and the files already written. Resume it instead of paying for
the same work again:

```bash
browser4-cli agent run --resume <task-id>              # continue the checkpointed task
browser4-cli agent run --resume <task-id> "also capture the price"   # add an instruction
```

The new run **reuses the interrupted task's workspace** (so the rows/files it wrote are
there), inherits its `--coding`/`--access` flags, and its prompt lists those files with a
"do not start over" instruction. The id must be known to the *same backend* — after a
restart the interrupted row is restored as failed, which is exactly the checkpoint resume
reads. An unknown id fails the new task with a clear message instead of silently starting
from scratch.

## Live Progress and the Interactive Session

`agent run` has three levels of visibility. All three submit the SAME async task;
they differ only in what the CLI does while it runs.

| Mode | What you see |
|---|---|
| `agent run "<task>"` (default) | The task id, immediately. Poll later. |
| `agent run "<task>" --wait` | A progress line every few seconds, then the result. |
| `agent run "<task>" --follow` | Every step: tool calls, the model's own text, slow/failed tools, then the result. |
| `agent run -i` | An interactive session: tasks one after another, steering, `/status`, `/cancel`, … |

### `--follow`: watch the steps

```bash
browser4-cli agent run "Collect the top 5 products and their prices" --follow
```

```
Task submitted: 3b27930e-48bc-4460-aae7-be521f0a9194
[00:00] ▶ Collect the top 5 products and their prices
[00:03] #1 b4.run({"args":"goto https://example.com/products"})
[00:04] #2 browser_snapshot
[00:09]   ↳ b4.run ok in 6.2s
[00:12]   💭 The grid shows prices in a second column; I will extract both.
[00:15] #3 htmlsnapshot_query({"sql":"..."})
✔ Agent completed in 41.3s:
{"summary":"Top 5 products extracted", ...}
```

- One line per tool **call**; a completion line only for a **slow** (>2 s) or
  **failed** tool, so a 100-tool run stays readable. Failures always print the
  first ~240 characters of the tool output.
- `--follow` has **no default timeout** (unlike `--wait`); pass
  `--wait-timeout <secs>` to bound it. On timeout the CLI exits non-zero and the
  task keeps running server-side.
- **Ctrl+C cancels the task** (the same thing `agent cancel <task-id>` does) and
  exits — it no longer leaves an orphan running in the background.

> **A run cannot stop its own backend.** The agent drives the CLI through
> `b4.run`, which refuses `stop`, `close-all`, `kill-all`, `install`,
> `uninstall`, `upgrade`, `delete-data` and `doctor --fix` with
> `PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED`: those commands end or rewrite
> the process the task is running in. Before this guard existed, a task that
> could not launch a browser ran `b4.run("stop")` as a "fix", killed its own
> backend, and was reported as `server restarted`. When the browser is broken,
> read `b4.run("doctor log …")` / take stock of the real error and report the
> blocker through `system.taskComplete(problems=[...])`.

`--ndjson` turns the same stream into machine output — one JSON object per stdout line:

```bash
browser4-cli agent run "Collect the top 5 products" --follow --ndjson | jq -c 'select(.type=="tool.call")'
```

```json
{"type":"task.submitted","taskId":"…","task":"Collect the top 5 products"}
{"taskId":"…","seq":3,"type":"tool.call","epochMillis":1761…,"tool":"b4.run","arguments":"{\"args\":\"goto …\"}","turn":1}
{"taskId":"…","seq":4,"type":"tool.result","epochMillis":1761…,"tool":"b4.run","ok":true,"durationMs":6200,"text":"…","turn":1}
{"taskId":"…","seq":5,"type":"run.usage","epochMillis":1761…,"turn":1,"inputTokens":4821,"outputTokens":212,"totalTokens":5033}
{"taskId":"…","type":"task.result","status":"done","elapsedMs":41300,"result":{"summary":"…"}}
```

`run.usage` is one line per model request, carrying the provider's token counts
(`inputTokens`/`outputTokens`/`totalTokens`, omitted when the provider reports none) —
the same numbers `/stats` sums.

The visible detail is deliberately bounded: tool arguments and results are truncated to
~240 characters, and values whose key names a credential (`password`, `token`,
`apiKey`, …) are redacted to `***`, as is typed text whose selector names a credential
field (`input[type=password]`).

### `agent run -i`: the interactive session

```bash
browser4-cli agent run -i
```

```
agent> Summarize the pricing page
[00:02] #1 b4.run({"args":"goto https://example.com/pricing"})
...
✔ done in 18.4s · 5b0c… 
{"summary":"…"}

agent> now compare it with the enterprise page
```

A line typed while a task is running is **steering**, not a new task: it is sent
to the running task and injected into the agent as a user message at its next
model round-trip. A backend hiccup does not end the session either: the live view
keeps following a task that is still running (see
[When the step stream fails](#when-the-step-stream-fails)).

Steering is chosen whenever this session's task is still running — the CLI asks
the backend rather than trusting its own bookkeeping, so a line can never be
queued as a second task behind a live one. If the session had stopped following
the task, accepting the line re-attaches the live view (`… following task <id>
again`). To start a *different* task, `/cancel` the running one first; after
`/new` the previous task belongs to the old agent context, so a later line is a
new task.

```
agent> Summarize the pricing page
[00:02] #1 b4.run(...)
> also include the enterprise tier
↪ steering queued — the agent sees it at its next model round
```

Steering is best-effort by design: it lands at the next model round (immediately
for browser tasks that call a tool per round; possibly after a long tool round
for coding tasks). If the task finished while you were typing, the CLI says so
and keeps the session open.

Session commands:

| Command | Effect |
|---|---|
| `/help` | List the commands |
| `/status` | Status of the running (or last) task |
| `/stats [task-id]` | Run statistics: LLM rounds and requests, token usage, tool calls |
| `/result` | Print the last finished task's result |
| `/cancel` | Cancel the running task (same as Ctrl+C) |
| `/new` | Start a new session — the next task gets a fresh agent context |
| `/session` | Print the current session id |
| `/coding [on\|off]` | Treat following tasks as coding tasks |
| `/noop-limit <n>` | Consecutive no-op abort threshold (blank resets) |
| `/events [n]` | Raw JSON of the last n steps (default 20) |
| `/quit` | Leave the session |

#### `/stats`: what the run cost

```
agent> /stats
5b0c3f2a · running · 3m12s
  llm     3 round(s) · 11 request(s)
  tokens  in 48,231 · out 6,120 · total 54,351
  tools   39 call(s) · 2 failed (b4.run 12, browser_snapshot 8)
  steps   132 · 1 steering message(s)
```

- **llm**: outer agent `round`s (each may issue several model requests) and the
  model `request`s themselves — the honest "how many LLM calls" count.
- **tokens**: what the provider reported, summed per request. `not reported by
  the provider` means the calls happened but the provider sent no usage block
  (a local model, a gateway that drops it) — it is not a measured zero.
- **tools**: tool calls, how many failed, and the most-called tools.
- **steps**: every step the run emitted; `dropped from the buffer` appears when
  the backend's bounded step log evicted older steps (the counters still include
  them).

The numbers come from `GET /api/commands/{id}/stats`, which counts every step as it is
appended — exact even for steps the step log no longer retains. `/stats <task-id>`
reports another task of this session; against a backend without that endpoint the report
is labelled `counted by this session` and can only undercount.

An optional task runs immediately: `agent run -i "Summarize this page"`.
With piped stdin the banner and prompt are omitted, so a harness can drive the
session: `printf 'task one\n/quit\n' | browser4-cli agent run -i`.

### Backend requirements and compatibility

Live steps use `GET /api/commands/{id}/events` (a per-task in-memory step log polled
with a sequence cursor) and steering uses `POST /api/commands/{id}/input` — both on a
current backend. Against an **older backend** the CLI degrades instead of failing:
`--follow` prints one notice and falls back to status polling, and `-i` reports that
steering is unavailable and ignores the typed line (the task keeps running). The
`queued`/`running`/`done`/`failed`/`cancelled` vocabulary and the result payload are
identical in every mode.

### When the step stream fails

The live view is a convenience over a task that keeps running server-side, so the CLI
never lets a *display* problem end it:

- **A page the CLI cannot parse** (a proxy error page, a truncated response, a preview
  that is not strict JSON) is not retried unchanged — that could never succeed. The
  follower narrows the page until the offending step stands alone, steps over it, and
  prints `… skipped 1 unreadable step (up to seq N)`: one preview line is lost, not the
  stream.
- **`-i` keeps following a running task.** If the follower exhausts its window (e.g.
  the backend restarted), the session warns and keeps following while the backend
  still reports the task as `running` — the steps resume when it answers. It gives up
  only when the task finished or the id is unknown; `/status` and `/result` keep
  answering from what the session already saw.
- **`--follow` gives up after ~5 min** without a usable response, exits non-zero and
  names the task id; `agent status <id>` reports the task's real state.

## Flags

| Flag | Applies to | Description |
|------|-----------|-------------|
| `--wait` | `agent run` | Block until the task reaches a terminal state, then print the result |
| `--wait-timeout <secs>` | `agent run` | Wait window in seconds (default 600 for `--wait`; no cap for `--follow`) or a cap for `--follow` |
| `--follow` | `agent run` | Stream the task's steps while waiting, then print the result. Ctrl+C cancels the task |
| `--ndjson` | `agent run --follow` | Emit one JSON object per line (submission, every step, the final result) instead of human lines |
| `-i`, `--interactive` | `agent run` | Open an interactive session (see above); an optional task runs immediately |
| `--coding` | `agent run` | Explicitly mark the task as a coding task (plain file/build work; no browser page). Omitted (the default) the task is a browser task — task type is never auto-detected |
| `--access <preset>` | `agent run` | Access preset for this task's coding tools: `full` (default), `write`, `read-build`, `read`. Omitted, the backend's `browser4.agent.access` applies. Scoped to the task and reset when it ends — see [Permissions](#permissions---access) |
| `--json` | `agent status`, `agent result`, `agent list` | Emit the envelope as JSON instead of the human rendering |
| `--all` | `agent list --clear` | Also untrack tasks that are not finished (they keep running server-side) |
| `--schema <json>` | `extract` | Enforce a JSON output schema for the extraction |
| `--selector <css>` | `summarize` | Scope summarization to elements matching a CSS selector |

### Coding tasks (--coding)

Browser is the product's primary mode, so an agent task is a **browser task by default**
— the CLI does not guess whether the task text "looks like" coding. Pass `--coding` only
for pure file/build work (read/write files, run Maven, scaffold a module) needing no page:

```bash
browser4-cli agent run "Read pom.xml, fix the failing module build" --coding
```

Coding runs skip the driver health check, search-engine navigation and screenshots, and
start with the coding tool core; any task containing a URL or page interaction stays a
browser task regardless.

## Errors & Recovery

| Symptom | Recovery |
|----------|---------|
| `agent run` exits non-zero | Check backend is reachable and LLM key is configured |
| `agent result` exits non-zero with `✖ failed (<code>)` | The task really failed. The printed payload carries `errorCode` (`TOKEN_BUDGET`, `NOOP_LIMIT`, `CANCELLED`, `TOOL_ERROR`, `INFRASTRUCTURE`), the workspace, the files it wrote and — when the work can continue — `resume: agent run --resume <id>`. Pass `--allow-failed` to exit 0 while still reading the failed payload |
| `✖ failed (TOKEN_BUDGET)` | Raise the cap for one task (`--token-budget <n>`), the default for the backend (`config set agent.token.budget.total <n>`), or resume with a narrower scope (`--resume <id>`); `errors`/`summary` name the consumed budget |
| `agent run` reports `unexpected positional arguments: [...]` | The task is one argument: quote it (`agent run "the whole task"`). Extra positional words are rejected instead of being appended to the instruction |
| `No agent task found with id '…'` | The backend has no record of that id (cleared, expired, or the backend restarted) — `agent list` shows what is still tracked |
| Task still `running` after minutes | Poll `agent status` — browsing agents routinely take minutes; `processed` shows whether it is still stepping |
| `agent result` says the result carries no data | The agent may have written its deliverable to a file: the same output names the `workspace` it wrote into |
| `⚠ not delivered: …` after typing into `-i` | The task finished (or stopped accepting input) while the line was typed — the text is NOT turned into a task; type it again to start one, or `/cancel` first when you want a different task |
| `agent list` shows a different status than `agent status` | `agent list` refreshes from the backend on every call; if the backend is unreachable it prints a note and shows cached labels |
| Task lost after backend restart | Task ids may not persist; re-submit the task |
| Steps fail with `PERMISSION_DENIED \| code=…` | The task's access preset refuses that call — re-submit with a wider `--access`, have an operator approve the needed effect (`coding.requestAccess` + the REST endpoints), or narrow the task to the routes the preset allows (`coding.permissions` lists them) |
| `extract`/`summarize` blocking too long | Use `agent run` for async execution instead |
| Agent subcommands in batch mode | Not supported — use standalone commands |
