# Coworker Task Manager — Node.js Edition

A zero-compilation web GUI for the coworker task pipeline. Same API, same frontend as
the Rust version, but runs on Node.js with only `express` and `cors` as dependencies.

## Quick Start

```bash
cd coworker/gui
npm install
npm start -- --tasks-root ../tasks/
# Or: node server.js --tasks-root ../tasks/
```

Then open **http://127.0.0.1:8090**.

## CLI Options

| Option | Default | Description |
|--------|---------|-------------|
| `--host` | `127.0.0.1` | Host to bind |
| `--port` | `8090` | Port to listen on |
| `--tasks-root` | `./coworker/tasks/` | Path to the tasks root directory |
| `--logs-root` | *(auto)* | Path to the agent logs root; defaults to `<repo>/logs/agent` (dev symlink) when present, else `~/.browser4/logs/agent` |
| `--open-browser` | — | Open the browser on startup (flag, no value) |

## Pages

| Route | File | Description |
|-------|------|-------------|
| `GET /` | `frontend/index.html` | Main Coworker Task Manager — pipeline dashboard with stage tabs and file tree |
| `GET /issues/review` | `frontend/issue-review.html` | Issue Review SPA — review `.issues.md` files from the `issues/review` queue |
| `GET /logs` | `frontend/watch-logs.html` | Log Dashboard — real-time log viewer for all Browser4 log sources |
| `GET /logs/reader` | `frontend/log-reader.html` | Log Reader — parse log lines into structured entries (level/thread/logger/message) with level & text filters, stack-trace folding, stats and export |
| `GET /agent/logs` | `frontend/agent-logs.html` | Agent Logs viewer — browse `logs/agent`: run traces (events, tool trace, token usage, task trajectories) and LLM chat sessions (sys prompt + request/response conversation) |

## API

### Stage Groups

The pipeline is organized into stage groups shown as tabs:

| Group | Stages |
|-------|--------|
| `main` | 0draft → 1ready → 2working → 3done → 4review → 5approved → 6git-pushed |
| `refine` | Draft refinement sub-pipeline (0draft/refine/*) |
| `sources` | Input feeders: `0draft/issues/github` (fetched GitHub issues) |
| `issues` | GitHub issues pipeline (issues/*) |
| `review` | Issue review queue (`issues/review`) |

### Endpoints

**Task CRUD:**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/stats` | Per-stage task counts |
| `GET` | `/api/tasks?stage=<id>` | List tasks in a stage |
| `GET` | `/api/task?path=<rel>` | Read task content |
| `POST` | `/api/task?path=<rel>` | Create / update task |
| `DELETE` | `/api/task?path=<rel>` | Delete task |
| `POST` | `/api/move` | Move task between stages (with transition validation) |

**Issue Review:**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/issue-review/ai-suggest` | AI-assisted review suggestion (invokes Claude CLI) |
| `POST` | `/api/issue-review/mark-done` | Finalize review: creates summary in `1ready`, archives original to `review/done`. Accepts optional `auto_approve` boolean — when true, appends `#auto-approve` tag to the summary so the coworker pipeline auto-moves it to `5approved` and triggers push. |
| `POST` | `/api/issue-review/discard` | Discard a valueless issue file: moves it to `review/done/discard/` |

**Log Reader:**

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/logs/parse` | Parse a tail window of a log source into structured entries. Body: `{ source, windowLines, levels?, query?, maxEntries? }`. Returns `{ entries, stats, totalParsed, files, truncated }` — each entry has `ts`, `level`, `thread`, `logger`, `message`, `text`, `continuation[]`, `raw`, `label`, `file`, `lineNo`. Multi-line stack traces are folded into their owning entry (`continuation`). |

**Agent Logs:**

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/agent/overview` | Everything in one call: `{ root, source, exists, stats: { runs, chats, byStatus }, runs, chats }`. Each run carries status (`complete`/`running`/`overflow`/`error`), cli-events preview, token-usage totals, tool-call counts and task-dir listings; chats pair `.chat.sys.log` with `.chat.user.log`. |
| `GET` | `/api/agent/runs` | Run list only (lighter than overview). |
| `GET` | `/api/agent/chats` | Chat session list only. |
| `GET` | `/api/agent/file?path=<rel>` | Load one file under the logs root. Parsing is content-driven: `.jsonl` → structured objects (broken lines reported, capped), `.chat.user.log` → request/response blocks, `.json` → parsed JSON, everything else → plain text lines. `jsonlLimit`/`chatLimit` query params cap item counts. Path traversal is rejected (403). |

### Auto-Approve

When the "Auto-approve" checkbox is checked in the Issue Review SPA, the mark-done endpoint appends a `#auto-approve` tag to the end of the summary file written to `1ready`. The coworker worker (`coworker.ps1`) detects this tag and routes the task directly to `5approved` instead of `3done`, bypassing manual review and triggering the automated git push.

## Files

```
.
├── package.json       # express + cors only
├── server.js          # All routes
├── log-parser.js      # Log line parser (shared by /api/logs/parse)
├── agent-log-parser.js# Agent logs parser & enumerator (runs, chats, jsonl, chat blocks)
├── frontend/
│   ├── index.html          # Task Manager SPA
│   ├── issue-review.html   # Issue Review SPA
│   ├── watch-logs.html     # Log Dashboard SPA
│   ├── log-reader.html     # Log Reader SPA (parsed log view)
│   ├── agent-logs.html     # Agent Logs viewer SPA (runs + trajectories + LLM chats)
│   └── issue-model.js      # Shared issue model library
└── README.md
```

### Agent logs layout

`logs/agent` is the durable home of agent run traces and LLM chat sessions
(see `AgentPaths.kt` in `browser4-agentic`). In development it is symlinked to
the project root as `./logs/agent`; in production it lives under
`~/.browser4/logs/agent`. The viewer auto-detects both and `--logs-root`
overrides. Run directories are `<time>/<uuid>/` with `cli-events.jsonl`,
`cli-usage.jsonl`, `cli-tool-trace.jsonl`, `cli-prompt/*.request.json` and
`task-*/` trajectory files; chat sessions live under `chat/<MMDD>/`.

## Security

- Path traversal prevention (`..`, null bytes, absolute paths rejected)
- Localhost-only binding by default
- Filename validation (no hidden files or special characters)
- 1 MiB JSON body limit on POST
