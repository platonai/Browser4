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

> The key must reach the backend server process, not just your CLI shell: `browser4-cli doctor` confirms "✓ LLM is configured", and `doctor --fix` writes a commented template (`~/.browser4/config/conf-enabled/application-private.properties`) without touching an existing file. Configure **exactly one** provider — leftover keys compete in Browser4's provider order (`deepseek` wins over `openai`, which wins over `groq`); `doctor` names the winner (`Selected key`) and the model (`Active model`), and
> `llm.provider.deny.list=deepseek` excludes one. See [LLM configuration](../../../docs/config.md#which-provider-is-used).

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

The agent runs **on the backend**, driving the session's own browser through its bound driver — never a second browser you can attach to. `agent run` targets the CLI's session: `-s <name>` when given, else the unnamed slot (including a browser attached via `attach --extension` / `attach --cdp`), or the sole recorded `swarm create` session; `--coding` tasks run with no browser at all. The browser launches **lazily** — submitting opens nothing, the first page-needing tool call starts it — so a
task driving an attached browser through `b4.run("goto …")` opens **no additional browser**. An attached session whose browser is not connected never falls back to a fresh Chrome; the page tool fails with a re-attach instruction.

Two consequences matter in practice:

- **The submission echoes the binding, and so does the backend log.** The CLI prints `Task submitted: <id> (session=<sid>, <connection>)` or `(no session — a session-less task)`; `--json` carries `session_id`/`session_connection`, `--ndjson` puts `sessionId`/`connection` on the `task.submitted` line. The authoritative log lines are `tool.call start … tool=command_run session=<browser session>` and `Submitting agent task <task-id> (session=<browser session>)` — both must carry the session id the
  CLI reported. Lines naming the agent's own session use `agentSession=` — a second, unrelated UUID (the agentic session), never the browser session.
- **Its navigation is not visible in your session's tab list.** The backend saves the URL you were on before the task and navigates back to it when the task ends, so `tab-list`, `page-info` and `snapshot` keep showing your own page and the agent's intermediate pages never appear there. Observe its work through `agent status` (step count and message) and through the artifacts it reports.
- **Its deliverables are files, not always fields.** When a task says "extract X", the agent usually writes the data into a file under its own per-task **workspace** and returns a short summary. `agent result` names that directory (`workspace`) and lists the files the agent reported (`filesChanged`), so the deliverable is reachable without searching the backend's app-data tree by hand:

```bash
browser4-cli agent result <task-id>
# Summary
#   Extract the subject's biography and save it as markdown.
# Key findings (1)
#   • Born 31 January 1956 in The Hague
# Files written (1) · workspace: C:\Users\me\.browser4\...\agent\2026\10\15\<uuid>\workspace
#   guido_van_rossum.md
```

`--json` prints the same data as one machine envelope instead.

The workspace lives on the backend machine (copy the artifacts when it is remote). Each task gets its **own** directory (`<agent data dir>/workspace.<task-id>`), so another run's file never shows in this run's `filesChanged`; `--resume <task-id>` keeps the interrupted task's workspace, `--shared-workspace` restores the old shared `<agent data dir>/workspace`, and `--fresh-workspace` is the default behaviour already. Input copying, the read rules and the deliverable format are in
[agent-workspace.md](agent-workspace.md).

**Coding tasks are different in one visible way.** `--coding` runs in an isolated **git
worktree** of the checkout (`<repo>/.browser4/worktrees/<task-id>`) and merges the
change into the main repository as a patch only when `coding.verify` passes. So
`agent result` reports `workspace` = the **main repository** (where the change landed),
`isolatedWorkspace` = the worktree, and `merge` = `applied` | `refused` | `pending`;
`refused` means nothing was applied and the run's files exist only in the worktree.
`--shared-workspace` is refused for `--coding` (a shared directory is not a worktree), and
`b4w recover` can revert one task's patch without touching anything else.

### Cost control (`--token-budget`, `--follow` usage lines)

A run is capped at `agent.token.budget.total` (**5M cache-miss tokens** by default) and aborts with `TOKEN_BUDGET` before it can burn unbounded provider credits. The cap counts **cache-miss prompt tokens + output tokens**: prompt-cache reads cost ~10% of the input price and are *not* charged, so a long loop that re-sends a mostly cached prefix no longer aborts a run that actually costs little. Uncached prompt (Anthropic's cache *writes* included) and output stay charged; a provider that reports no cache details charges its whole prompt — an unknown cache is never treated as a hit. Two different knobs:

| Knob | Scope | Use it when |
|---|---|---|
| `agent run … --token-budget <n>` | **this task only** (a per-run override; cleared when the task ends) | one long task legitimately needs more (or less) than the default |
| `config set agent.token.budget.total <n>` | process-wide: every later task on this backend | the operator wants a new default |

`--token-budget 0` means unlimited (accounting only). `--follow` reports the spend:

```text
[04:31] budget 412.0k / 5.00M (8%) · cache 331.8k (81%) · 37 requests
[12:02] budget 4.02M / 5.00M (80%) · cache 3.62M (90%) · 118 requests ⚠ 80% of the cache-miss budget used
[15:24] ✖ cache-miss token budget exhausted: 5.01M / 5.00M — the run is being aborted
```

The first number is the **charged** amount (cache-miss prompt + output), not the raw token total — that is why the line says `budget`. The progress line is throttled (every 10 requests or 15s); the 80% warning appears once and the abort line names both numbers. The `cache` part is the run's prompt-cache hit ratio (cache-read tokens ÷ prompt tokens) and appears only when the provider reports cache details — a provider that reports none stays silent rather than claiming `0%`. `--ndjson` carries the same information as `effectiveTokenBudget` + `cumulativeTokens` on every `run.usage` line, plus `cachedInputTokens` / `cacheWriteInputTokens` when the provider disclosed them. `cumulativeTokens` is the **charged** amount (cache-miss prompt + output, what the budget enforces); `inputTokens` on the same line stays the raw prompt count, so the two differ exactly by the cache reads. `agent result --json` reports the budget as `result.effectiveTokenBudget`.

**A budget abort is resumable** — the run's workspace, files and progress are already on disk. In `-i` the failure is followed by a numbered choice that resumes the task with a **doubled** cap; typing `1` runs the command on the right, the resume re-uses the interrupted task's workspace and instruction, and the new task shows as `▶ resume <old> → task <new>`:

```text
✖ failed · 3b27930e · 12.1s — token_budget: Token budget exceeded (cache-miss accounting)…
likely next steps (type a number, or your own task):
  1) continue with a 10.00M cache-miss budget
  2) find out why it failed
  3) review the 1 changed file(s)
```

Scripted runs use the one-liner the failure line prints — `browser4-cli agent run --resume <task-id> --token-budget 10000000` — and `/resume [--budget <n>] [task-id]` continues any resumable failure (`NOOP_LIMIT`, a cancellation), not just budget aborts.

**After every task the session predicts what you want next** and prints 2–3 numbered *tasks*, not internal commands — type the number to run one, or type your own task. The offers come from what the run actually produced, most-predictive first: the model's own `nextSuggestions` from its completion report (`system.taskComplete` — it just did the work and knows what is left); an open `problems` entry (`fix what it left open: …`); and deterministic intents derived from the payload — a coding run that changed files offers `review the N changed file(s)`, `run the <module> tests`, `commit the change`, a browsing run offers `dig into: <finding>` and `save the result to a file`, a failure offers `find out why it failed` and (when it cannot be resumed) `try the same task again`, and a resumable one offers `continue with a … budget` first.

The block never spends a slot on `/stats`, `/result` or `/new`: those are in `/help`, and a menu slot spent on the CLI's own bookkeeping is a slot not spent on the work. Piped stdin (no TTY) gets no menu — a numbered choice a script cannot answer would only be noise — and the `resume:` hint line keeps carrying the exact command.

### Permissions (`--access`)

A task's coding tools run under one access **preset** (`full` / `write` / `read-build` / `read`), and a refused step can be approved out of band through `coding.requestAccess` plus the operator endpoints. The preset table, the `PERMISSION_DENIED | code=...` catalogue, the approval/rail rules, the unattended default and the task-scoped reset are in [agent-access.md](agent-access.md).

**Reads are wider than writes.** Browser4's own data root (`~/.browser4`: logs, task data, per-task workspaces, its `tmp/`), the source checkout, and any folder added with `-Dbrowser4.agent.readPaths=…` are readable from every task — read-only, whatever the preset. `coding.permissions` lists them; see [agent-access.md](agent-access.md#readable-roots--browser4s-own-folders).

**Two layers, one boundary.** The preset and the string checks (`pathGuard`, `workspaceBoundary`, `allowExternalAccess`) decide what the model may *spell* — they are fast-fail and advisory, not enforcement. The boundary is the **execution sandbox**: `coding.shell` and `coding.runCode` run inside it whenever `browser4.agent.sandbox` selects a backend other than `off`. A fresh Windows installation defaults to `windows-acl`; existing installations and Linux/macOS stay `off` until switched. When the
sandbox is active, an OS denial reaches the model as `PERMISSION_DENIED | code=SANDBOX_DENIED | tool=… | needed=…` (raw evidence stays in the log), and an unavailable sandbox — missing account or credential, failed ACLs, failed write probe, unknown id — **fails the task**; there is no unsandboxed fallback. Switch values, defaults, the ACL matrix, the account property and the `off` rollback are in [config.md](../../../docs/config.md).

### Extra folders (`--add-dir`)

A task's boundary is its own workspace (plus its mirrored module roots and the readable roots). A folder that lives somewhere else is granted per task — repeatable, must already exist, task-scoped, `--access` still bounds it and the OS sandbox agrees; `-i` adds `/add-dir`. See [agent-workspace.md](agent-workspace.md#extra-folders-granted-with---add-dir).

## Patterns

### 1. Asynchronous Multi-Step Task

```bash
browser4-cli agent run "Go to amazon.com, search for 'wireless headphones', extract the top 5 product titles and prices"
browser4-cli agent status <task-id>   # poll until "status": "done"
browser4-cli agent result <task-id>
```

**Writing tasks:** Describe **what** you want, not how. Good: "extract the top 5 product titles and prices." Avoid step-by-step ref-based instructions.

### 2. Wait for Completion (Optional)

`agent run --wait` blocks until the task reaches a terminal state, then prints the result. The window defaults to 600s — browsing agents routinely exceed it — and is tuned per invocation with `--wait-timeout` (seconds) or globally with `BROWSER4_CLI_AGENT_WAIT_TIMEOUT_SECS`. When it expires the CLI exits non-zero but the task keeps running server-side and stays tracked locally, so pick it up later:

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

Returns the **shared task envelope** — the same shape every long-running tool (`crawl`, `swarm`, `command`) answers with:

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

**Terminal check:** poll until `status` is `done`, `failed` or `cancelled`. `queued`/`running` mean "keep polling". Do not wait for the pre-4.14 `processState`/`isDone` fields — the envelope above is the contract, and a task that is `done` never changes again.

An unknown id answers `{"status":"failed","statusCode":404,...}` with the reason in `error`, which `agent status`/`agent result` report as `No agent task found with id '…'` and exit non-zero.

`agent list` renders the same knowledge as local labels — `queued`, `processing`, `completed`, `failed (<code>)`, `cancelled` — refreshed from the backend on every call, so its STATUS never contradicts `agent status`.

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

`result.summary` is what the agent reported; `keyFindings` and `filesChanged` carry the deliverable itself, and `workspace` is the directory its tools wrote into. Human output renders all three as sections (see [Result rendering](agent-progress-sessions.md#result-rendering)) — a task whose answer is a file is usable without filesystem archaeology; `--json` keeps the envelope above for machines.

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

**Output destination:** `extract` saves its result to a **timestamped file** (`.browser4-cli/snapshot/extract-*.txt` under your working directory) and prints only a link — it does **not** echo the payload to stdout by default. Add `--stdout` (or `--raw`) to print the extracted content directly, or `--filename <path>` to choose the saved path. When `--schema` is given, the requested fields arrive as plain **top-level JSON** — both in the saved file and on stdout — so
`extract "..." --schema @schema.json --stdout` can be piped straight into JSON consumers.

### 7. Synchronous Summarization (summarize)

```bash
browser4-cli summarize "summarize the product reviews"
browser4-cli summarize --selector "#content"
```

### 8. Resume an Interrupted Task

A task that died with the backend (crash, restart, `stop`) leaves a checkpoint: its instruction, its workspace and the files already written. Resume it instead of paying for the same work again:

```bash
browser4-cli agent run --resume <task-id>              # continue the checkpointed task
browser4-cli agent run --resume <task-id> "also capture the price"   # add an instruction
```

The new run **reuses the interrupted task's workspace** (so the rows/files it wrote are there), inherits its `--coding`/`--access` flags, and its prompt lists those files with a "do not start over" instruction. The id must be known to the *same backend* — after a restart the interrupted row is restored as failed, which is exactly the checkpoint resume reads. An unknown id fails the new task with a clear message instead of silently starting from scratch.

## Live Progress and the Interactive Session

`agent run` (the task id, poll later), `--wait` (a progress line, then the result),
`--follow` (every step as it happens) and `agent run -i` (an interactive session) all submit
the same async task. The step and `--ndjson` shapes, result rendering, the completion line,
the session's context, steering and slash commands (including `/stats`), backend
requirements and step-stream failure handling are in
[agent-progress-sessions.md](agent-progress-sessions.md).

## Flags

| Flag | Applies to | Description |
|------|-----------|-------------|
| `--wait` | `agent run` | Block until the task reaches a terminal state, then print the result |
| `--wait-timeout <secs>` | `agent run` | Wait window in seconds (default 600 for `--wait`; no cap for `--follow`) or a cap for `--follow` |
| `--follow` | `agent run` | Stream the task's steps while waiting, then print the result. Ctrl+C cancels the task |
| `--ndjson` | `agent run --follow` | Emit one JSON object per line (submission, every step, the final result) instead of human lines |
| `-i`, `--interactive` | `agent run` | Open an interactive session ([details](agent-progress-sessions.md)); an optional task runs immediately |
| `--coding` | `agent run` | Explicitly mark the task as a coding task (plain file/build work; no browser page). Omitted (the default) the task is a browser task — task type is never auto-detected |
| `--access <preset>` | `agent run` | Access preset for this task's coding tools: `full` (default), `write`, `read-build`, `read`. Omitted, the backend's `browser4.agent.access` applies. Scoped to the task and reset when it ends — see [Permissions](#permissions---access) |
| `--unrestricted` | `agent run` | Run this task with the current OS user's own permissions: `full` preset, external access, network and destructive commands, no protected rails, no OS sandbox, and the shell's command allow-list off too (programs it never knew, a PowerShell cmdlet included, run as written — shapes the shell cannot decompose are handed to the platform shell in every mode). The OS credential stores (`~/.ssh`, `~/.aws`, …) and the commands that would kill or rebuild the backend stay refused. Opt-in per task, audited, inherited by `--resume` and not removable on it — see [agent-access.md](agent-access.md#unrestricted-tasks--agent-run---unrestricted) |
| `--json` | `agent status`, `agent result`, `agent list` | Emit the envelope as JSON instead of the human rendering |
| `--all` | `agent list --clear` | Also untrack tasks that are not finished (they keep running server-side) |
| `--schema <json>` | `extract` | Enforce a JSON output schema for the extraction |
| `--selector <css>` | `summarize` | Scope summarization to elements matching a CSS selector |

### Coding tasks (--coding)

Browser is the product's primary mode, so an agent task is a **browser task by default** — the CLI does not guess whether the task text "looks like" coding. Pass `--coding` only for pure file/build work (read/write files, run Maven, scaffold a module) needing no page:

```bash
browser4-cli agent run "Read pom.xml, fix the failing module build" --coding
```

Coding runs skip the driver health check, search-engine navigation and screenshots, and start with the coding tool core; any task containing a URL or page interaction stays a browser task regardless.

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
