---
name: coworker
description: File-queue automation system for running AI agents (Claude or Copilot) against task files. Use when the user wants to create, queue, run, or manage Coworker tasks, or asks about the Coworker pipeline, scheduler, or task automation.
allowed-tools: Bash(coworker:*)
---

# Coworker — File-Queue Task Automation

Coworker is a **filesystem-backed state machine** that runs AI agents (Claude by default, or Copilot) against task files in this repo. Task files move through numbered directories; PowerShell scripts handle orchestration, logging, and git push.

## When to Use

- User wants to **create and queue a task** for the AI agent to execute
- User wants to **run the Coworker worker or scheduler**
- User asks about **task pipelines, draft refinement, or GitHub issue creation**
- User needs to **review results, approve tasks, or trigger git push**
- User asks about **Coworker config, logs, or debugging**

## Core Mental Model

```
task file → 0draft → 1ready → 2working (agent runs) → 3done → 4review → 5approved → 6git-pushed
                                                                                          ↓
                                                                                    git commit/push
#auto-approve: 2working → 5approved (skips 3done/4review)
```

- **Task files** are the queue
- **Numbered directories** are the state
- **PowerShell scripts** are the orchestrators
- **`~\.browser4-coworker\tasks\300logs\`** is the audit trail (logs organized by `YYYY\MM\DD`)
- **`5approved`** is the point of no return (auto git push)

## Quickstart

```powershell
# Create a task file (plain .md or with Title:/Description:/Prompt: headers)
# Place it in coworker/tasks/main/1ready/

# Run one pass
.\coworker\scripts\coworker.ps1

# Or run the scheduler continuously
.\coworker\scripts\coworker-scheduler.ps1
```

The worker picks up the task, renames it to kebab-case, runs the configured AI agent (Claude or Copilot), logs to `~\.browser4-coworker\tasks\300logs\YYYY\MM\DD\`, and moves the task to `3done` (or `5approved` if `#auto-approve` is in the file).

## Key Commands

```powershell
.\coworker\start.ps1                              # Unified launcher: GUI + scheduler
.\coworker\start.ps1 -Once                        # One scheduler pass (GUI included)
.\coworker\start.ps1 -NoGui                       # Scheduler only, no GUI server
.\coworker\scripts\coworker-scheduler.ps1          # Continuous scheduler (all pipelines)
.\coworker\scripts\coworker-scheduler.ps1 -Once    # One scheduler pass
.\coworker\scripts\coworker.ps1                    # Run main worker directly
.\coworker\scripts\coworker.ps1 .\path\to\task.md  # Queue + run a specific task
.\coworker\scripts\process-coworker-queue.ps1 -Once # Queue processor (one-shot)
```

## Task File Format

Tasks are Markdown files. Two formats are supported:

**Structured (recommended):**
```markdown
Title: Fix login button alignment
Description: The login button is misaligned on mobile viewports.
Prompt: Inspect the CSS for the login button and fix the alignment issue.
```

**Plain markdown:** The entire file content becomes the prompt.

Add `#auto-approve` anywhere in the file to skip manual review and go straight to `5approved` → git push.

## Pipeline Directories

| Pipeline | Draft | Ready | Working | Done | Review/Approve | Pushed/Error |
|----------|-------|-------|---------|------|----------------|--------------|
| **Main tasks** | `main/0draft` | `main/1ready` | `main/2working` | `main/3done` | `4review` → `5approved` | `6git-pushed` |
| **Draft refinement** | — | `main/0draft/refine/1ready` | `…/2working` | `…/3done` | — | `…/0error` (dead letter) |
| **GitHub issues (refine)** | — | `200issues/draft/refine/0ready` | `…/1working` | `…/2done` | — | `…/0error` (dead letter) |
| **GitHub issues (commit)** | — | `200issues/github/commit/ready` | — | `…/done` | — | `…/failed` |

Add `#auto-approve` to a task file to skip `3done`/`4review` and go directly to `5approved` → `6git-pushed`.

## Draft Refinement

Drafts flow through `0draft/refine/1ready` → `2working` → `3done`. Failed refinements (after max retries) are moved to `0error` (dead letter). Orphaned files in `2working` older than 30 min are recovered automatically.

```powershell
.\coworker\scripts\workers\refine-drafts.ps1 -Path .\coworker\tasks\main\0draft\refine\1ready
```

## GitHub Issues Pipeline

Two-stage: refine drafts into structured issues, then create them on GitHub. Successfully created issues land in `200issues/github/commit/done`; failures go to `…/commit/failed`. A daily commit guard caps creation at 20 issues per UTC day.

```powershell
.\coworker\scripts\workers\refine-github-issues.ps1   # Extract issues from drafts
.\coworker\scripts\workers\commit-github-issues.ps1   # Create on GitHub via gh CLI
```

Issue files support optional `Labels:`, `Assignees:`, and `Repo:` metadata fields.

## Additional Scheduled Tasks

These tasks run on intervals via the scheduler (configured in `coworker-scheduler.config.psd1`):

| Task | Script | Interval | Description |
|------|--------|----------|-------------|
| `fetch-github-issues` | `workers/fetch-github-issues.ps1` | 10 min | Pull open issues, self-assign unassigned, save as `.md` in `0draft/issues/github/` |
| `triage-github-issues` | `workers/triage-github-issues.ps1` | 30 min | Scan fetched issues for low-risk/high-relevance ones, auto-queue for execution |
| `organize-task-files` | `workers/organize-task-files.ps1` | 5 min | Reorganize flat task directories into `YYYY/MMDD` subdirectories |
| `update-readmes` | `workers/update-readmes.ps1` | 1 hour | Scan all `README.md` files for staleness and queue stale ones for AI update |

## Additional Workers

```powershell
.\coworker\scripts\workers\fetch-github-issues.ps1       # Fetch + self-assign GitHub issues
.\coworker\scripts\workers\triage-github-issues.ps1      # Triage fetched issues → queue
.\coworker\scripts\workers\organize-task-files.ps1        # Date-organize task directories
.\coworker\scripts\workers\git-sync.ps1                   # Commit + push all changes
.\coworker\scripts\workers\coworker-memory-context.ps1    # Generate memory context for tasks
.\coworker\scripts\workers\count-total-token-usage.ps1    # Count token usage across logs
```

## Configuration

- **`coworker/scripts/config.psd1`** — Agent backend (Claude by default; Copilot is commented out), workspace/target roots, log directory
- **`coworker/scripts/config.ps1`** — Loader that dot-sources shared modules (Paths, Logging, Locks, Watchers), sets up PATH shims
- **`coworker/scripts/coworker-scheduler.config.psd1`** — Scheduled tasks (8 tasks: coworker, draft-refinement, commit-github-issues, refine-github-issues, fetch-github-issues, organize-task-files, triage-github-issues, update-readmes), intervals, enabled/disabled state

## Safety Rules

1. **Never move files out of `2working`** while a run is active — the script handles state transitions
2. **Review before `5approved`** — approval (via `coworker.ps1` or the scheduler) triggers `git-sync.ps1`, which commits and pushes all repo changes
3. **Use `#auto-approve` sparingly** — it bypasses human review
4. **Check `~\.browser4-coworker\tasks\300logs\` first when debugging** — task and agent logs are per-day under `YYYY\MM\DD`
5. **`git` must be installed** for auto-commit/push; **`gh` must be installed and authenticated** for GitHub issue creation (and Copilot backend if used)
6. **Multiple Coworker instances are prevented** by per-script mutex locks — only one `coworker.ps1` or `git-sync.ps1` runs at a time

## GUI

A web-based task manager is available:

```bash
cd coworker/gui
npm install
npm start -- --tasks-root ../tasks/
# Open http://127.0.0.1:8090
```

## For Full Details

See `coworker/README.md` for complete pipeline documentation, all scheduler tasks, task-source ingestion, memory helpers, and the full state-machine specification.
