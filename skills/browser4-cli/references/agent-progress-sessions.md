---
title: "Agent Progress and Interactive Sessions"
description: "Reference for the live surfaces of agent run: the --follow step stream and its --ndjson events, result rendering, the completion line, the agent run -i session with its context, steering and slash commands, backend requirements, and step-stream failure handling."
tier: catalog
---

# Agent Progress and Interactive Sessions

## Overview

`agent run` has three levels of visibility, and `agent run -i` adds a session on top of them.
All of them submit the SAME async task; they differ only in what the CLI does while it runs.
Read this when you need the exact shape of a live run — the step list, the `--ndjson` events,
the `/stats` numbers, the completion line — or when a step stream misbehaves.

| Mode | What you see |
|---|---|
| `agent run "<task>"` (default) | The task id, immediately. Poll later. |
| `agent run "<task>" --wait` | A progress line every few seconds, then the result. |
| `agent run "<task>" --follow` | Every step: tool calls, the model's own text, slow/failed tools, then the result. |
| `agent run -i` | An interactive session: tasks one after another, steering, `/status`, `/cancel`, … |
| `agent supervise <task-id>` | One panel judging the run: counters vs file changes, gate/verify verdicts, repair rounds, pending approvals. Works on a live or finished task, and after a restart. |

## Quick Index

| Question | Read |
|---|---|
| What does one `--follow` step line look like, and what is truncated or redacted? | [`--follow`: watch the steps](#--follow-watch-the-steps) |
| How do I consume the same stream from a script? | [`--ndjson`](#--follow-watch-the-steps) |
| How is a finished task's payload printed? | [Result rendering](#result-rendering) |
| What do the numbers on the final line mean? | [The completion line](#the-completion-line) |
| How do I steer, `/status`, `/stats`, `/goal` or `/resume` inside a session? | [`agent run -i`: the interactive session](#agent-run--i-the-interactive-session) |
| What was this run asked to do, and what does it still owe? | [`/goal`: what the run is for](#goal-what-the-run-is-for) |
| Which backend endpoints does the live view need? | [Backend requirements and compatibility](#backend-requirements-and-compatibility) |
| The live view is stuck or a step is unreadable — what now? | [When the step stream fails](#when-the-step-stream-fails) |

## Reference

### `--follow`: watch the steps

```bash
browser4-cli agent run "Collect the top 5 products and their prices" --follow
```

```
Task submitted: 3b27930e-48bc-4460-aae7-be521f0a9194
[00:00] ▶ Collect the top 5 products and their prices
[00:03] #1 b4.run(args="goto https://example.com/products")
[00:04] #2 browser_snapshot
[00:09]   ↳ #1 b4.run ok in 6.2s
[00:12]   ⋯ The grid shows prices in a second column; I will extract both.
[00:12]   » Intent: query the price column — to build the CSV with both fields
[00:15] #3 htmlsnapshot_query(sql="...")
✔ Agent completed in 41.3s · 3b27930e-48bc-4460-aae7-be521f0a9194 · 3 round(s) · 39 tool(s) · 54.4k tokens

Summary
  Top 5 products extracted, with both the name and the price column.

Files written (1) · workspace: ~/.browser4/agent/workspace.3b27…
  products.csv
```

- One line per tool **call**; a completion line only for a **slow** (>2 s) or **failed** tool, so a 100-tool run stays readable. Failures always print the first ~240 characters of the tool output.
- The result line repeats the call's index (`↳ #2 browser_click failed after 120ms: …`), so two calls of the same tool are tellable apart. The backend also sends the exact `callId` on both steps (`--ndjson`), so a programmatic follower pairs them without the name heuristic.
- **The engine's own verdicts are visible**: `⏱ gate rejected: 2 violation(s) — …` when a completion report fails the finish gate, `🔁 repair round 1/3 — compile browser4-browser failed (exit 1)` when a failing `coding.verify` is fed back, and `✔ finish report amended from the mutation trace: +3 file(s)` when the report's file list is completed from the trace. A passing gate stays silent — the completion line already reports success.
- **A run parked on a permission is labelled, not mistaken for a slow tool**: `⏸ waiting for approval: fs.write /etc/hosts (req-1) — /approve req-1 in `agent run -i`, or POST …` when `coding.requestAccess` queues a request, and `✔ access approved: req-1` / `✖ access denied: req-1 — <reason>` once someone answers.
- Arguments are shown as `key="value"` for the keys a reader can act on (`selector`, `ref`, `url`, `path`, `file`, `args`, `text`, `query`, `sql`, `command`) — at most three, then `+N` for the rest. A shape with none of those keys falls back to the compact JSON, so nothing is hidden.
- Prose lines (the model's own text, steering) are marked `⋯` / `↪` and wrap to `COLUMNS` (clamped 40–400, default 100), the continuation indented under the marker instead of running back to column 0. Structured lines (calls and results) deliberately stay one line — they are what people grep and copy.
- **The purpose of the next step is its own line**: `» Intent: <what> — <why>` is the text the model writes together with a tool call (that text used to be dropped, so the view showed only WHICH tool ran). `/intent off` hides it and also stops the backend asking for it — the line costs tokens, so hiding it must not keep paying for it; `/tools off` hides the tool rows instead. Both switches are live for a running task and remembered for the next ones.
- **Long silences are not a hang.** After 30 s without a step the live view prints `[00:39]   ⋯ still running · 5 step(s) · 30s since the last step`, then at most one more every 30 s. A single slow tool (three minutes inside one `b4.run`) used to print nothing at all in between. Terminal only — a piped log, `--quiet`, `--json` and `--ndjson` carry steps and nothing else. `-i` behaves the same.
- **A gap in the step stream is reported, never silent.** Steps live in a bounded per-task buffer (1000); if the follower falls behind it, the backend says `resynced=true` and the live view prints `⚠ fell behind the retained step window — N step(s) dropped, resumed at seq M` (in `--ndjson` the same fact arrives as `{"type":"events.resynced","firstRetainedSeq":…,"dropped":N,"after":…}`). Before this, the page was simply empty and a lost stretch looked like a stalled agent.
- **The steps survive a backend restart.** Each task's steps are written to `<data>/agent/events/<taskId>.jsonl` (write-behind; the same bounded window, rotated at ~4 MB, aged out with the task TTL), and the endpoint restores them on demand, keeping their original `seq` — so a follower that reconnects after a restart continues from its cursor instead of finding an empty process next to a terminal status row. A restored window starts `dropped` at 0: what was evicted before the restart is gone from the shard too, and `firstRetainedSeq` says where the remaining window begins.
- **Prefer push over polling when you are not the CLI.** The same steps are served as Server-Sent Events at `GET /api/commands/{id}/events/stream`: every frame carries `id: <seq>` (so a reconnect sends `Last-Event-ID` and resumes exactly there; `?after=` wins when both are given), the stream completes right after the terminal step, a silent run still gets a comment keep-alive every 15 s, and `?maxSeconds=<n>` bounds it. The CLI's own follower polls `/events` (400 ms) — that stays true; this endpoint is for dashboards and other agents.
- `--follow` has **no default timeout** (unlike `--wait`); pass `--wait-timeout <secs>` to bound it. On timeout the CLI exits non-zero and the task keeps running server-side.
- **Ctrl+C cancels the task** (the same thing `agent cancel <task-id>` does) and exits — it no longer leaves an orphan running in the background.

> **A run cannot stop its own backend.** The agent drives the CLI through `b4.run`, which refuses `stop`, `close all`, `kill all`, `install`, `uninstall`, `upgrade`, `delete data` and `doctor --fix` with `PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED`: those commands end or rewrite the process the task is running in. Before this guard existed, a task that could not launch a browser ran `b4.run("stop")` as a "fix", killed its own backend, and was reported as `server restarted`. When the browser is
> broken, read `b4.run("doctor log …")` / take stock of the real error and report the blocker through `system.taskComplete(problems=[...])`.

`--ndjson` turns the same stream into machine output — one JSON object per stdout line:

```bash
browser4-cli agent run "Collect the top 5 products" --follow --ndjson | jq -c 'select(.type=="tool.call")'
```

```json
{"type":"task.submitted","taskId":"…","task":"Collect the top 5 products"}
{"taskId":"…","seq":3,"type":"tool.call","epochMillis":1761…,"tool":"b4.run","arguments":"{\"args\":\"goto …\"}","turn":1,"callId":"call_a1"}
{"taskId":"…","seq":4,"type":"tool.result","epochMillis":1761…,"tool":"b4.run","ok":true,"durationMs":6200,"text":"…","turn":1,"callId":"call_a1"}
{"taskId":"…","seq":5,"type":"run.usage","epochMillis":1761…,"turn":1,"inputTokens":4821,"outputTokens":212,"totalTokens":5033,"cachedInputTokens":3900,"cacheWriteInputTokens":0}
{"taskId":"…","seq":6,"type":"round.start","epochMillis":1761…,"turn":2,"mutations":3,"readOnlyRounds":0,"schemaVersion":1}
{"taskId":"…","seq":7,"type":"gate.evaluated","epochMillis":1761…,"schemaVersion":1,"detail":{"passed":false,"strict":true,"violations":["filesChanged lists 1 file(s) neither git nor this run's file trace shows: ev.js"],"unlistedFiles":["ev.js"]}}
{"taskId":"…","type":"task.result","status":"done","elapsedMs":41300,"result":{"summary":"…"}}
```

Beyond the step types above, `--ndjson` forwards the **decision events** and their bounded `detail` payload unchanged, so a script can judge a run the way the engine does:

| type | when | `detail` |
|---|---|---|
| `verify.verdict` | a `coding.verify` result was parsed | `passed`, `module`, `failureReason`, `checks[{name,target,ran,ok,required,exitCode}]` |
| `gate.evaluated` | every finish-gate evaluation, pass included | `passed`, `strict`, `violations[]`, `unlistedFiles[]`, `warnings[]`, `requiredGates[]` |
| `repair.round` | a failing verdict was fed back | `round`, `maxRounds`, `reason` |
| `repair.exhausted` | the repair budget ran out (before `task.failed`) | `roundsUsed`, `maxRounds`, `passed`, `failureReason` |
| `finish.repair` / `finish.amend` | the report's file list is repaired / completed from the trace | `round`, `missing[]` / `added[]`, `source`, `filesChanged` |
| `nudge` | the engine injected a correction | `kind=coding` (`readOnlyRounds`, `mutations`) or `kind=budget` (`percent`, `consumed`, `limit`) |
| `isolation.merge` | the worktree merge was applied or refused | `decision`, `changedFiles`, `reason` |
| `access.requested` / `access.resolved` | a `coding.requestAccess` is waiting / was answered | `requestId`, `effect`, `target`, `reason`, `requestedBy` / `decision`, `expiresAt` |
| `steer` / `follow_up` | a typed line was delivered | `inputIds` — the `inputId` the input call answered with |

`round.start` also carries the progress gauges `mutations` (cumulative file mutations, a **gauge**, not a delta) and `readOnlyRounds` (consecutive rounds that changed no file). "Hundreds of tool calls, `mutations: 0`" is the earliest reliable sign a run will not converge. Every step now also carries `schemaVersion`, and the `detail` payload is bounded (strings ~240 chars, ≤12 keys, ≤20 items, ≤2 levels, ≤4 KB) with `detail.truncated=true` whenever it had to be cut.

`run.usage` is one line per model request, carrying the provider's token counts (`inputTokens`/`outputTokens`/`totalTokens`, omitted when the provider reports none) — the same numbers `/stats` sums — plus, when the provider discloses it, the prompt-cache breakdown: `cachedInputTokens` are the prompt tokens served from its cache (cache *read*; `null`/absent when it reported no cache details — unknown, not `0`), `cacheWriteInputTokens` the tokens written into that cache (Anthropic-style APIs; `0`/absent elsewhere).

`inputTokens` is **cache-inclusive across protocols**: OpenAI-compatible APIs (DeepSeek, OpenRouter, …) already count cache hits inside `prompt_tokens`, while Anthropic reports them separately and Browser4 folds them in. `cachedInputTokens / inputTokens` is therefore a comparable hit ratio, and `totalTokens` is `inputTokens` + output.

The visible detail is deliberately bounded: tool arguments and results are truncated to ~240 characters, and values whose key names a credential (`password`, `token`, `apiKey`, …) are redacted to `***`, as is typed text whose selector names a credential field (`input[type=password]`).

### Result rendering

When a task finishes, the CLI renders the `command_result` payload as sections instead of printing the envelope as one JSON line. One renderer serves every human path — `agent result <id>`, `agent run --wait`, `agent run --follow` and `agent run -i`:

```
✔ Agent completed in 18.4s · 5b0c3f2a · 3 round(s) · 39 tool(s) · 54.4k tokens

Summary
  The pricing page lists four plans; enterprise pricing is quote-only, so the
  three published tiers were collected.

Key findings (2)
  • Starter is $19/mo, billed yearly
  • Team is $49/mo per seat, 10-seat minimum

Files written (1) · workspace: ~/.browser4/agent/workspace.5b0c3f2a
  pricing.csv

Problems (1)
  ⚠ Enterprise pricing is not published anywhere on the page

Data (3 rows × 3 columns)
  plan     | price | seats
  ---------+-------+------
  Starter  | 19    | 1
  Team     | 49    | 10
  Business | 89    | 25
```

- A section appears only when the payload carries it, and every list states its total (`Key findings (7)`) — a capped list (10 findings, 10 files, 5 problems, 5 links, 20 rows) ends with `… and N more`. `/result --full` lifts both the fold and those per-section caps, so "show me everything" really means everything.
- `fields` and `xsqlResultSet` become aligned tables; a string payload is printed as text, without JSON quotes.
- The `Timings` section answers "where did the wall clock go" from the task's own result: one line for what the tool loop measured (`3 rounds · model 4.8s · tools 171ms · callbacks 54ms · tokens 12.8k in / 12.0k cached in / 555 out`), then — when the run recorded them — a second line for the phases around the loop (`setup 3.2s (isolation 2.8s) · merge 120ms · finalize 45ms`), where `isolation` is the worktree share of `setup` and the merge/finalize parts only appear when they took time. Zero parts are omitted throughout, so a read-only run claims no build time and a task that never ran claims no round. The phases get their own line deliberately: appended to the bucket line they pushed the token summary past the line width and it was clipped away. `--json` carries the whole object, including `runOverheadMs` (the part of the run that happened outside the tool loop), which the human line omits.
- The envelope's transport fields (`taskId`, `status`, `statusTool`, `resultTool`, `cancelTool`, `processed`, `statusCode`) are never printed.
- A payload field this renderer does not know yet stays visible under `Other (N field(s))`, so a backend addition is never silently dropped.
- The model's own `nextSuggestions` render as a trailing `Suggested next steps` section in one-shot runs (`agent result`, `--wait`, `--follow`). Inside `-i` they are the numbered menu instead, so they are not printed twice.
- Lines wrap to `COLUMNS` (default 100); a payload longer than 40 lines is folded with `… N more line(s) — /result --full shows everything`. The hint names the command that exists where you are: `/result --full` (or the global `--pretty`) inside `-i`, `agent result <id> --pretty` everywhere else. `agent result` with the global `--json` flag still emits the machine envelope.
- Machines are unaffected: `--ndjson` is byte-identical, `-i` ignores `--ndjson` (it says so on stderr), and the envelope is available through `agent result <id> --json`, `/result --json` (pretty-printed, still valid JSON), or the `--json` envelope's `raw` field.

### The completion line

`agent run -i`, `--follow` and `--wait` all end a task with the same line — verdict word, elapsed time, task id, and the run's cost:

```
✔ Agent completed in 41.3s · 3b27930e-48bc-4460-aae7-be521f0a9194 · 3 round(s) · 39 tool(s) · 54.4k tokens
✖ failed · 3b27930e-48bc-4460-aae7-be521f0a9194 · 12.1s — the page was blocked by a captcha
⏹ cancelled · 3b27930e-48bc-4460-aae7-be521f0a9194 · 41.3s · 39 tool(s)
```

- Each cost part appears only when it is known: a coding task that never called the model prints no `0 round(s)`, and a provider that sends no usage block prints no token count (unknown is not zero).
- The counters come from the same `GET /api/commands/{id}/stats` that `/stats` reads, with a **2 s budget**. A slow or missing endpoint costs the line nothing — the part is omitted rather than delaying the end of the run — and `--json` / `-q` never ask at all (they do not print the line).
- Elapsed time is compact and scales: `18.4s`, `3m12s`, `1h04m`.

### `agent run -i`: the interactive session

```bash
browser4-cli agent run -i
```

```
agent> Summarize the pricing page
[00:02] #1 b4.run(args="goto https://example.com/pricing")
...
✔ Agent completed in 18.4s · 5b0c… · 3 round(s) · 39 tool(s) · 54.4k tokens

Summary
  The pricing page lists four plans; enterprise pricing is quote-only.

Key findings (2)
  • Starter is $19/mo, billed yearly
  • Team is $49/mo per seat, 10-seat minimum

Files written (1) · workspace: ~/.browser4/agent/workspace.5b0c…
  pricing.csv

agent> now compare it with the enterprise page
```

The payload is rendered as sections (see [Result rendering](#result-rendering)), and `/result` re-prints it at any time without re-running the task: `--json` prints the `command_result` envelope as pretty-printed JSON (still valid JSON for a script), `--full` keeps the section layout but lifts the fold and the per-section caps.

#### Session context: the next task inherits the whole conversation

Every task of a session is a new agent run, so the backend appends a `## Session context` block to the next instruction — but it is not a summary: it is the **actual conversation of the earlier tasks**, with what the user asked, the model's own messages, every tool call and its result, and the steering lines typed into them, each task closed with its outcome, files and the workspace they live in. The next run therefore continues the session instead of being told about it. `/new` starts a new session (and a new context); two `agent run -i` invocations against the same session share one. Backing up is cheaper than starting over: `-i`'s `/rewind <task-id>` (one-shot: `agent run --rewind <task-id> "<task>"`) continues the session's conversation from the point where that task **began**, and the backend summarizes the branch being abandoned first — what it tried, what it learned, what not to repeat — so going back to re-plan does not lose the knowledge. A rewind applies to exactly one task; `/rewind off` cancels it. It needs the live backend process (the conversation is in memory) and cannot be combined with `--resume`, which starts a new run from another task's workspace instead.

The transcript is compacted **by the model** when it grows: once it crosses `browser4.agent.session.contextTokens` (default 24000 tokens, estimated at 4 characters per token) the backend sends it with a dedicated **compression instruction** that asks for a **factual record** — the instructions as they were worded, decisions, paths, URLs, selectors, ids, formats, error strings, numbers, and how each task ended — and for no goals, priorities, recommendations or conclusions the transcript does not contain. The block and the compression instruction state facts only: they do not tell the model which instruction is the current goal, which was abandoned, or how to treat the history. The result (about `browser4.agent.session.memoryTokens`, default 2000) replaces the folded turns and is carried into every later compression, so nothing is lost across folds; the newest task's turns are never folded. If the compression call fails the transcript is sent uncompressed and the failure is logged. `browser4.agent.session.contextEnabled=false` (or `contextTokens=0`) turns the block off; a one-shot `agent run` is unaffected either way (a session's first task has nothing to remember). To add your own framing or guidance, set `browser4.agent.systemPrompt.file` / `.extra` (appended last) or `.overrideFile` / `.override` (replaces the role preamble; the tool/permission/completion contract stays).

While the backend process lives this is not a rendering at all: the next task continues the session's **real message list** (the earlier assistant messages and their tool calls *with the results*), so the model never has to re-run a tool whose output it already has; the tool loop's own compressor keeps that list bounded under the same window-aware pressure as a single task. After a backend restart the compacted transcript described above is what continues the session.

Three details matter in practice:

- **The context survives a backend restart.** The conversation is persisted per session (`agent-session-context.jsonl`, 12 h TTL by default — `browser4.agent.session.contextTtlMinutes`), so a backend that restarts between two tasks of your `-i` does not silently reset the session's memory. The anonymous `DEFAULT` session is never persisted: an `-i` started without `-s` uses its own `interactive-<hash>` id (visible in the banner, reusable as `-s`), so two interactive sessions never inherit each other's context or queue behind each other.
- **The files of an earlier task carry their directory** (`workspace: …` in that task's closing line). Each task runs in its own workspace, so a bare relative name would point at an empty directory; a workspace that no longer exists (a merged coding worktree) is not advertised.
- **A line that arrives too late is not lost silently.** If the task ends between your steering message and the next model round, the live view prints `⚠ steering not delivered — the task ended first: …`, and the line is appended to the transcript as an undelivered user instruction for the next task instead of dying with the run.
What the session's own chrome says:

- **The banner lists only what you changed.** `Browser4 agent · session=abc` plus `· coding=on`, `· access=read-build`, `· budget=5.00M`, `· quiet` when they apply — then `  type a task · /help for commands · Ctrl+C cancels the running task`. A default session prints no knob at all, so the line that does appear is the one that matters. There is no `engine=` field: the CLI tool loop is the only engine.
- **The banner also names this session's web console** — `  web console: http://127.0.0.1:18182/agent.html?session=abc  ·  same session`. That single static page is served by the backend and drives the same REST endpoints: submit a task, watch the steps live (SSE, with cursor polling as the fallback), steer, stop, and read the result. It is deliberately narrower than the terminal — `/goal`, `/approve`, `/rewind`, `--resume` and permission approvals stay in `agent run -i`.
- **The console's sidebar lists every session this backend remembers, grouped by workspace** (`GET api/agent/sessions`). A session belongs to the directory its newest `agent run --workspace` task named; sessions whose tasks never named one are grouped under **临时工作区** (each task wrote into its own throw-away directory). The whole panel and each workspace fold independently (remembered in the browser), and clicking a session switches the page to it — URL, stored session and composer follow — and restores its context (`GET api/agent/sessions/{id}/context`): the conversation turns the next task would inherit plus the tasks it ran, with a button to follow the newest one while it is still running. Switching is refused while a task runs, because a run belongs to the session that started it. The page is a left-right shell: the session list is a fixed left column; the right column opens with the composer **centered** under a short headline and **docks it to the bottom** as soon as a task runs (or a session is opened), so the steps get the space.
- **The console's composer can pick the workspace** for its next tasks: the dropdown beside the input lists the workspaces the session index knows and is remembered in the browser; choosing one submits `POST /api/commands/plain?…&workspace=<dir>`, which the backend validates before the task starts (a missing/invalid directory is a `400`, not a task that fails later). Opening a session adopts that session's workspace, so "continue this conversation" also means "write where it wrote".
- **Each task starts on a blank line**, headed `▶ task 5b0c3f2a — Summarize the pricing page` (the task text is collapsed to one line and capped at 60 characters).
- **`/status` is one line**: `task 5b0c3f2a · running · 12 step(s) — collecting product details` (no message → no tail, never a trailing space).
- **A failure names its cause and the way out**: `✖ failed · 5b0c3f2a · 3.2s — page_blocked: the page was blocked` followed, when the backend says the run can continue, by `    resume: agent run --resume 5b0c3f2a · /status for the envelope`.
- **Quitting does not silently orphan a run.** `/quit` (or stdin EOF) while a task is still running prints `⚠ task 5b0c3f2a keeps running — agent status 5b0c3f2a · agent cancel 5b0c3f2a`.
- **ANSI follows `NO_COLOR`**: any non-empty value disables colour even on a terminal (`--quiet` and `--json` already do).

`-i --json` keeps the scalars `task_id` / `raw` / `stats` describing the **last** task (unchanged, so existing readers keep working) and adds `tasks[]`, one entry per task of the session in order:

```json
{"task_id":"…","raw":{…},"stats":{…},"tasks":[{"taskId":"5b0c3f2a","status":"done","isDone":true,"elapsedMs":18400,"steps":39},{"taskId":"7f21ab9c","status":"cancelled","isDone":true,"elapsedMs":2100,"steps":4}]}
```

A line typed while a task is running is **steering**, not a new task: it is sent to the running task and injected into the agent as a user message at its next model round-trip. `/followup <text>` is the other delivery point: it is queued and delivered when the agent would otherwise stop, as a real user message in the SAME conversation — the task keeps running with it (same task id, same workspace), so it is the right way to add "one more thing" without interrupting the step in flight. The live view labels it `↪ follow-up delivered`; a follow-up that arrives after the task really ended is reported (`⚠ follow-up not delivered`) and kept for the next task. A backend hiccup does not end the session either — the live view keeps following a task that is still running (see [When the step stream fails](#when-the-step-stream-fails)).

> **"Queued" is verifiable.** The REST/MCP answer to an input carries `inputId`, and the delivered `steer` / `follow_up` step repeats it in `detail.inputIds` (`--ndjson` forwards it). So a script that submits steering can prove the step it observes is its own message, instead of inferring it from the text — and an `inputId` that never shows up in a delivered step means the line arrived after the last model round (it is then reported as `steering.undelivered`).

Steering is chosen whenever this session's task is still running — the CLI asks the backend rather than trusting its own bookkeeping, so a line can never be queued as a second task behind a live one (`↪ steering queued` confirms it). If the session had stopped following the task, accepting the line re-attaches the live view (`… following task <id> again`). To start a *different* task, `/cancel` the running one first; after `/new` the previous task belongs to the old agent context, so a later line is a new task. Steering is best-effort by design: it lands at the next model round (immediately for browser tasks that call a tool per round; possibly after a long tool round for coding tasks). If the task finished while you were typing, the CLI says so and keeps the session open.

Session commands:

| Command | Effect |
|---|---|
| `/help` | List the commands |
| `/status` | Status of the running (or last) task |
| `/stats [task-id]` | Run statistics: LLM rounds and requests, token usage (prompt-cache reads/writes and hit ratio included when the provider reports them), tool calls, and — for a coding task — `files  N changed` next to the tool count (`⚠ … not converging` when tools ran without a single file change) |
| `/goal [--json] [--task <id>]` | The task's goal — the instruction the engine pins into every model request — with the plan the run committed to and the steps it still owes (`--json`: the same facts as one object) |
| `/goal <text>` | Replace the running task's goal (the operator's only channel; the model cannot reach it) |
| `/result [--json] [--full]` | Print the last finished task's result as sections (`--json`: the envelope as pretty JSON; `--full`: no fold, no per-section caps) |
| `/resume [--budget <n>] [task-id]` | Continue an interrupted/stalled task with its workspace and files. After a budget abort pass a larger `--budget` (the numbered continue choice does this with a doubled cap) |
| `/cancel` | Cancel the running task (same as Ctrl+C) |
| `/new` | Start a new session — the next task gets a fresh agent context |
| `1` / `2` / `3` | Run one of the predicted next steps while the numbered block is on screen (any other input, a task or a `/command`, supersedes it) |
| `/session` | Print the current session id |
| `/coding [on\|off]` | Treat following tasks as coding tasks |
| `/quiet [on\|off]` | Silence the session's human lines — the same effect as the global `-q`, for the rest of this session (bare `/quiet` flips it) |
| `/verbose [on\|off]` | Also print round markers (`· round 2`) and fast successful tools (`↳ #1 b4.run ok in 40ms`) — the troubleshooting view, off by default |
| `/intent [on\|off]` | Show the purpose the model states for each next step (`» Intent: open the product page — to read its price`). Off also stops the backend asking for it, so the line costs no tokens |
| `/tools [on\|off]` | Show the tool call/result lines (`#1 b4.run`, `↳ #1 b4.run ok in 40ms`); the steps stay recorded, so turning it back on shows the next ones |
| `/noop-limit <n>` | Consecutive no-op abort threshold (blank resets) |
| `/events [n]` | Raw JSON of the last n steps (default 20) |
| `/quit` | Leave the session |

#### `/stats`: what the run cost

```
agent> /stats
5b0c3f2a · running · 3m12s
  llm     3 round(s) · 11 request(s)
  tokens  in 48,231 · out 6,120 · total 54,351
  cache   38,585 read · 120 written (80.0% hit)
  tools   39 call(s) · 2 failed (b4.run 12, browser_snapshot 8)
  steps   132 · 1 steering message(s)
```

- **llm**: outer agent `round`s (each may issue several model requests) and the model `request`s — the honest "how many LLM calls" count.
- **tokens**: what the provider reported, summed per request. `not reported by the provider` means the calls happened but no usage block arrived (a local model, a gateway that drops it) — it is not a measured zero. `in` counts the whole prompt the model processed, prompt-cache reads included (see **cache** below), so it stays comparable across providers.
- **cache**: prompt tokens the provider served from its prompt cache (`read`), how many the run wrote into that cache (`written`, Anthropic-style APIs), and the hit ratio (`read ÷ in`). The line appears **only** when at least one request reported cache details — an unreported cache is unknown, not `0%`. Reads bill at roughly a tenth of the base input price and writes above it, so a high hit rate is the cheapest way to run a long loop.
- **tools**: tool calls, how many failed, and the most-called tools.
- **steps**: every step the run emitted; `dropped from the buffer` means the bounded step log evicted older steps (the counters still include them).

The numbers come from `GET /api/commands/{id}/stats`, which counts every step as it is appended — exact even for steps the step log no longer retains. `/stats <task-id>` reports another task of this session; against a backend without that endpoint the report is labelled `counted by this session` and can only undercount. A backend that predates the prompt-cache counters simply omits the **cache** line (its `cachedInputTokens` / `cacheWriteInputTokens` / `cacheReported` fields are missing).

The completion line already carries a compact version of these numbers (`· 3 round(s) · 39 tool(s) · 54.4k tokens · cache 80%`), so `/stats` is for the breakdown — rounds vs individual requests, input/output tokens, cache reads/writes, failures and the per-tool counts.

#### `/goal`: what the run is for

```
agent> /goal
task 5b0c3f2a · goal r2 · replaced by the operator · round 7
  only the three cheapest offers
  reason: operator: /goal
  plan 2/4 done, 1 dropped, 1 pending
    ✔ 1. open the offers page
    ✔ 2. read the prices
    ✖ 3. export csv — no csv writer
    ▢ 4. answer with the list
  done when:
    - the three prices are in the answer
  ⚠ 1 step(s) pending — the engine rejects a completion that leaves them open
  /goal <text> replaces it (the only way to move a running task's goal)
```

The goal is **engine state, not conversation**: it is re-rendered into every model request, so a run that is compacted, repaired or hours old still knows what it was asked to do. The plan is what the run itself recorded with `goal.plan`; a `✖` step counts as closed only when it carries the reason it was dropped. `/goal` reads `GET /api/commands/{id}/goal` (the same object every status envelope carries as `goal`), `/goal <text>` posts to it and is applied at the next round boundary — the change is announced to the model as a user turn and recorded as a `goal.changed` step, while the plan survives. A run cannot move its own goal — no tool reaches the channel — and an unattended `agent run` has nobody typing at it; an operator who holds the task id can still replace the goal explicitly over the API, and a finished or unknown task is refused with `accepted=false` and the reason. Without `--task` the command reports the task this session is following (or last saw). Design: `docs-dev/copilot/agent-goal-mode.md`; the same facts over MCP: `command.goal`.

An optional task runs immediately: `agent run -i "Summarize this page"`. With piped stdin the banner and prompt are omitted, so a harness can drive the session: `printf 'task one\n/quit\n' | browser4-cli agent run -i`.

### Backend requirements and compatibility

Live steps use `GET /api/commands/{id}/events` (a per-task in-memory step log polled with a sequence cursor) and steering uses `POST /api/commands/{id}/input` — both on a current backend. Against an **older backend** the CLI degrades instead of failing: `--follow` prints one notice and falls back to status polling, and `-i` reports that steering is unavailable and ignores the typed line (the task keeps running). The `queued`/`running`/`done`/`failed`/`cancelled` vocabulary and the result payload
are identical in every mode.

### When the step stream fails

The live view is a convenience over a task that keeps running server-side, so the CLI never lets a *display* problem end it:

- **A page the CLI cannot parse** (a proxy error page, a truncated response, a preview that is not strict JSON) is not retried unchanged — that could never succeed. The follower narrows the page until the offending step stands alone, steps over it, and prints `… skipped 1 unreadable step (up to seq N)`: one preview line is lost, not the stream.
- **`-i` keeps following a running task.** If the follower exhausts its window (e.g. the backend restarted), the session warns and keeps following while the backend still reports the task as `running` — the steps resume when it answers. It gives up only when the task finished or the id is unknown; `/status` and `/result` keep answering from what the session already saw.
- **`--follow` gives up after ~5 min** without a usable response, exits non-zero and names the task id. On the way out it probes the backend once (`GET /actuator/health`, 2 s timeout) so the message says **which** failure this is instead of the old one-size hint:
  - the server still answers → `(the backend still answers /actuator/health, so the server is up and the task or this endpoint is the problem — 'agent status <id>' reports it)`;
  - nothing serves the request (connection refused **or** the probe timing out — a healthy backend answers it in milliseconds) → `(the backend is not serving any more: it exited, is restarting, or is wedged, and a task cannot outlive it — run 'browser4-cli status' and read the backend log before retrying)`;
  - the probe itself failed ambiguously → the older, careful `(the task itself may still be running …)`.

  The distinction matters: a task cannot outlive its backend, so "may still be running" sent an operator looking for something that no longer exists after a backend crash. Any HTTP answer counts as alive, including a 503 from a starting Spring context — it still proves a process owns the port.

