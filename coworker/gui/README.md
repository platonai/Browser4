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
| `--open-browser` | — | Open the browser on startup (flag, no value) |

## Pages

| Route | File | Description |
|-------|------|-------------|
| `GET /` | `frontend/index.html` | Main Coworker Task Manager — pipeline dashboard with stage tabs and file tree |
| `GET /issues/review` | `frontend/issue-review.html` | Issue Review SPA — review `.issues.md` files from the `200issues/review` queue |

## API

### Stage Groups

The pipeline is organized into stage groups shown as tabs:

| Group | Stages |
|-------|--------|
| `main` | 0draft → 1ready → 2working → 3done → 4review → 5approved → 6git-pushed |
| `refine` | Draft refinement sub-pipeline (0draft/refine/*) |
| `sources` | Input feeders: `0draft/issues/github` (fetched GitHub issues) |
| `issues` | GitHub issues pipeline (200issues/*) |
| `review` | Issue review queue (`200issues/review`) |

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

### Auto-Approve

When the "Auto-approve" checkbox is checked in the Issue Review SPA, the mark-done endpoint appends a `#auto-approve` tag to the end of the summary file written to `1ready`. The coworker worker (`coworker.ps1`) detects this tag and routes the task directly to `5approved` instead of `3done`, bypassing manual review and triggering the automated git push.

## Files

```
.
├── package.json       # express + cors only
├── server.js          # All routes (~680 LOC)
├── frontend/
│   ├── index.html     # Task Manager SPA
│   ├── issue-review.html  # Issue Review SPA
│   └── issue-model.js     # Shared issue model library
└── README.md
```

## Security

- Path traversal prevention (`..`, null bytes, absolute paths rejected)
- Localhost-only binding by default
- Filename validation (no hidden files or special characters)
- 1 MiB JSON body limit on POST
