# AI Coworker

The AI Coworker is an agent that assists you with various tasks in a target repository.
It processes task files that you create, executes them, and can commit changes back to your repository.

## How to Use

1. run `coworker-scheduler.ps1` to start recurring automation
2. draft tasks in `main/0draft` (or anywhere)
3. copy ready tasks to `main/1ready` for execution
4. once executed, you can find results in `main/3done` and detailed logs in `~\.browser4-coworker\tasks\300logs`
5. review results if needed
6. move task file from `main/3done` to `main/5approved` to trigger git pushing

## How It Works

Task files flow through a pipeline of numbered folders inside `coworker/tasks/`. See `coworker/tasks/README.md` for the full state-machine documentation with all pipelines, transitions, and directory maps.

### Main task pipeline

| Stage | Folder | Description |
|-------|--------|-------------|
| Draft | `main/0draft` | Create and draft your task files here |
| Queue | `main/1ready` | Move tasks here when ready for execution |
| Work | `main/2working` | Agent is actively executing the task |
| Complete | `main/3done` | Execution finished — review the changes (date-stamped: `YYYY/MMDD/<file>`) |
| Review | `main/4review` | Optional manual review stage |
| Approved | `main/5approved` | Approved tasks awaiting commit/push (date-stamped: `YYYY/MMDD/<file>`) |
| Pushed | `main/6git-pushed` | Successfully committed and pushed (date-stamped: `YYYY/MMDD/<file>`) |

Tasks in stages `3done`, `5approved`, and `6git-pushed` are organized by date via `organize-task-files.ps1`.

### GitHub issues pipeline

| Stage | Folder | Description |
|-------|--------|-------------|
| Draft | `200issues/draft/refine/0ready` | Draft issue descriptions to be extracted and refined |
| In Process | `200issues/draft/refine/1working` | Agent is extracting and refining issues |
| Done | `200issues/draft/refine/2done` | Extraction complete, issues staged |
| Error | `200issues/draft/refine/0error` | Extraction failed after max retries |
| Open | `200issues/github/commit/ready` | Refined issue files ready for creation via `gh` CLI |

### Draft refinement pipeline (sub-pipeline of `main/0draft`)

| Stage | Folder | Description |
|-------|--------|-------------|
| Ready | `main/0draft/refine/1ready` | Drafts waiting to be refined |
| Working | `main/0draft/refine/2working` | Drafts currently being refined |
| Done | `main/0draft/refine/3done` | Refined drafts ready for review |
| Error | `main/0draft/refine/0error` | Refinement failed after max retries |

## Quick Start

1. **Draft** — Create your task file in `coworker/tasks/main/0draft/`.
2. **Queue** — Move it to `coworker/tasks/main/1ready/` when ready.
3. **Execute** — Run the scheduler or the worker script directly:
   ```powershell
   .\coworker\scripts\coworker-scheduler.ps1
   # or single-shot:
   .\coworker\scripts\coworker.ps1
   ```
4. **Review** — Task moves to `main/3done` after execution. Review the changes.
5. **Approve** — Move the task to `main/5approved` to have it automatically committed and pushed by the periodic runner.

## Task Manager GUI

A web-based GUI for managing the task pipeline is available at `coworker/gui/`. It provides a visual interface for browsing, creating, moving, and deleting tasks across pipeline stages — no command-line required.

```bash
cd coworker/gui
npm install
npm start -- --tasks-root ../tasks/
```

Then open **http://127.0.0.1:8090**. The GUI exposes a REST API (`/api/stats`, `/api/tasks`, `/api/move`) and binds to localhost only by default. See `coworker/gui/README.md` for the full API reference and CLI options.

## Prerequisites

GitHub CLI (`gh`) must be installed and authenticated.

See https://github.com/cli/cli#installation for installation instructions.

## Configuration

Coworker keeps its control data in the current repository, but task execution can target a different repository. Configure both in `coworker/scripts/config.psd1`:

- `Paths.WorkspaceRoot` keeps `coworker/tasks`, logs, and memory rooted in this repository.
- `Paths.TargetRepositoryRoot` sets the repository where task-mode `gh copilot` runs. When omitted, task execution falls back to `WorkspaceRoot` for backward compatibility.

## Tags

You can use tags in task files to provide additional context or control behavior.

Supported tags:

- `#auto-approve` — Automatically move the task to `main/5approved` after completion instead of `main/3done`. Useful for trusted, low-risk tasks that can be committed without manual review.

## Mentions

> **Experimental**

Mention `@coworker` in a task file to notify the agent to process the task.

## Syncing with Git

After tasks are approved, push changes to your repository using the git-sync scripts.

```powershell
.\coworker\scripts\workers\git-sync.ps1
```

## Unified Scheduler (PowerShell)

Use the unified scheduler when you want a single trigger to manage all recurring coworker jobs. The scheduler launches each configured task in its own PowerShell process, keeps the live output in that worker terminal, records a console transcript log, continuously writes task status to `logs/scheduled-tasks.status.json`, and uses filesystem events to react to queue changes without polling task folders.

Task definitions live in `coworker/scripts/coworker-scheduler.config.psd1`. Each entry can be enabled or disabled independently and sets its own `IntervalSeconds`, script path, arguments, optional `DependsOn` task ordering, and optional `PendingPaths` input queues. When `PendingPaths` is configured, the scheduler watches those files/folders and skips spawning a worker until work is actually present.

```powershell
.\coworker\scripts\coworker-scheduler.ps1        # Continuous mode
.\coworker\scripts\coworker-scheduler.ps1 -Once  # One-shot: run all due tasks and exit
```

Default scheduled tasks:

| Task | Worker Script | Trigger Path |
|------|--------------|--------------|
| `coworker` | `coworker.ps1` | `main/1ready` or `main/5approved` |
| `draft-refinement` | `workers/refine-drafts.ps1` | `main/0draft/refine/1ready` |
| `commit-github-issues` | `workers/commit-github-issues.ps1` | `200issues/github/commit/ready` |
| `refine-github-issues` | `workers/refine-github-issues.ps1` | `200issues/draft/refine/0ready` |
| `fetch-github-issues` | `workers/fetch-github-issues.ps1` | _(always runs, every 10 min)_ |
| `triage-github-issues` | `workers/triage-github-issues.ps1` | `main/0draft/issues/github` (every 30 min) |
| `organize-task-files` | `workers/organize-task-files.ps1` | _(always runs, every 5 min)_ |
| `update-readmes` | `workers/update-readmes.ps1` | _(always runs, every 1h)_ |

## Queue Processors

For direct one-shot execution outside the scheduler:

```powershell
.\coworker\scripts\process-coworker-queue.ps1
.\coworker\scripts\process-coworker-queue.ps1 -Once
.\coworker\scripts\process-draft-refinement-queue.ps1 -Once
.\coworker\scripts\process-task-source.ps1 -Once
```

For recurring automation, prefer `coworker-scheduler.ps1`.

## Draft Refinement

Draft refinement uses a dedicated pipeline under `coworker/tasks/main/0draft/refine/`:

- `1ready` — drafts waiting to be refined
- `2working` — drafts currently being refined
- `3done` — refined drafts ready for review

You can refine a single file or every file in a folder. When a folder is provided, files are processed one by one.

```powershell
.\coworker\scripts\workers\refine-drafts.ps1 -Path .\coworker\tasks\main\0draft\refine\1ready
```

## GitHub Issues Pipeline

Coworker can extract, refine, and create GitHub issues from natural-language draft files. This is a two-stage pipeline:

1. **Refine** (`refine-github-issues.ps1`): Scans `200issues/draft/refine/0ready` for draft files describing one or more issues, invokes the agent to extract individual issues, formats each as a structured markdown file, and writes them to `200issues/github/commit/ready`.

2. **Commit** (`commit-github-issues.ps1`): Scans `200issues/github/commit/ready` for formatted issue files and creates them on GitHub via `gh issue create`.

Issue file format:
```markdown
# Issue Title

Issue body content.

Labels: bug, enhancement
Assignees: username
Repo: owner/repo
```

- `Labels`, `Assignees`, and `Repo` fields are optional.
- Use `#auto-approve` in the last 5 lines of a draft to also publish the original draft as an issue (e.g., as a parent/epic).

```powershell
# Refine issue drafts
.\coworker\scripts\workers\refine-github-issues.ps1

# Create issues on GitHub
.\coworker\scripts\workers\commit-github-issues.ps1
```

