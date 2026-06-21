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

## API

### Stage Groups

The pipeline is organized into stage groups shown as tabs:

| Group | Stages |
|-------|--------|
| `main` | 0draft → 1ready → 2working → 3done → 4review → 5approved → 6git-pushed |
| `refine` | Draft refinement sub-pipeline (0draft/refine/*) |
| `sources` | Input feeders: `0draft/issues/github` (fetched GitHub issues) |
| `issues` | GitHub issues pipeline (200issues/*) |

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/stats` | Per-stage task counts |
| `GET` | `/api/tasks?stage=<id>` | List tasks in a stage |
| `GET` | `/api/task?path=<rel>` | Read task content |
| `POST` | `/api/task?path=<rel>` | Create / update task |
| `DELETE` | `/api/task?path=<rel>` | Delete task |
| `POST` | `/api/move` | Move task between stages |

## Files

```
.
├── package.json      # express + cors only
├── server.js         # All routes in one file (~250 LOC)
├── frontend/
│   └── index.html    # Single-page application
└── README.md
```

## Security

- Path traversal prevention (`..`, null bytes, absolute paths rejected)
- Localhost-only binding by default
- Filename validation (no hidden files or special characters)
- 1 MiB JSON body limit on POST
