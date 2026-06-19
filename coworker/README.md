# AI Coworker

The AI Coworker is an agent that assists you with various tasks in a target repository.
It processes task files that you create, executes them, and can commit changes back to your repository.

## How to Use

1. run `coworker-scheduler.ps1` to start recurring automation
2. draft tasks in `0draft` (or anywhere)
3. copy ready tasks to `1ready` for execution
4. once executed, you can find results in `3_1complete` and detailed logs in `coworker/tasks/300logs`
5. review results if needed
6. move task file from `3_1complete` to `5approved` to trigger git pushing

## How It Works

Task files flow through a pipeline of numbered folders inside `coworker/tasks/`:

### Main task pipeline

| Stage | Folder | Description |
|-------|--------|-------------|
| Draft | `0draft` | Create and draft your task files here |
| Queue | `1ready` | Move tasks here when ready for execution |
| Plan | `200plan` | Agent planning phase (managed automatically) |
| Work | `2working` | Agent is actively executing the task |
| Complete | `3_1complete` | Execution finished — review the changes |
| Review | `4review` | Optional manual review stage |
| Approved | `5approved` | Approved tasks awaiting commit/push |
| Pushed | `6git-pushed` | Successfully committed and pushed |
| Archive | `700archive` | Archived completed tasks |

### GitHub issues pipeline

| Stage | Folder | Description |
|-------|--------|-------------|
| Draft | `200issues/draft/refine/0ready` | Draft issue descriptions to be extracted and refined |
| In Process | `200issues/draft/refine/1working` | Agent is extracting and refining issues |
| Done | `200issues/draft/refine/2done` | Extraction complete, issues staged |
| Error | `200issues/draft/refine/0error` | Extraction failed after max retries |
| Open | `200issues/github/open` | Refined issue files ready for creation via `gh` CLI |

### Draft refinement pipeline (sub-pipeline of `0draft`)

| Stage | Folder | Description |
|-------|--------|-------------|
| Ready | `0draft/refine/1ready` | Drafts waiting to be refined |
| Working | `0draft/refine/2working` | Drafts currently being refined |
| Done | `0draft/refine/3done` | Refined drafts ready for review |

## Quick Start

1. **Draft** — Create your task file in `coworker/tasks/0draft/`.
2. **Queue** — Move it to `coworker/tasks/1ready/` when ready.
3. **Execute** — Run the scheduler or the worker script directly:
   ```powershell
   .\coworker\scripts\coworker-scheduler.ps1
   # or single-shot:
   .\coworker\scripts\coworker.ps1
   ```
4. **Review** — Task moves to `3_1complete` after execution. Review the changes.
5. **Approve** — Move the task to `5approved` to have it automatically committed and pushed by the periodic runner.

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

- `#auto-approve` — Automatically move the task to `5approved` after completion instead of `3_1complete`. Useful for trusted, low-risk tasks that can be committed without manual review.

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
| `coworker` | `coworker.ps1` | `1ready` or `5approved` |
| `draft-refinement` | `workers/refine-drafts.ps1` | `0draft/refine/1ready` |
| `commit-github-issues` | `workers/commit-github-issues.ps1` | `200issues/github/open` |
| `refine-github-issues` | `workers/refine-github-issues.ps1` | `200issues/draft/refine/0ready` |
| `process-task-source` | `process-task-source.ps1` | _(disabled by default)_ |

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

Draft refinement uses a dedicated pipeline under `coworker/tasks/0draft/refine/`:

- `1ready` — drafts waiting to be refined
- `2working` — drafts currently being refined
- `3done` — refined drafts ready for review

You can refine a single file or every file in a folder. When a folder is provided, files are processed one by one.

```powershell
.\coworker\scripts\workers\refine-drafts.ps1 -Path .\coworker\tasks\0draft\refine\1ready
```

## GitHub Issues Pipeline

Coworker can extract, refine, and create GitHub issues from natural-language draft files. This is a two-stage pipeline:

1. **Refine** (`refine-github-issues.ps1`): Scans `200issues/draft/refine/0ready` for draft files describing one or more issues, invokes the agent to extract individual issues, formats each as a structured markdown file, and writes them to `200issues/github/open`.

2. **Commit** (`commit-github-issues.ps1`): Scans `200issues/github/open` for formatted issue files and creates them on GitHub via `gh issue create`.

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



