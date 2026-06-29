---
name: coworker
description: File-queue automation system for running GitHub Copilot against task files. Use when the user wants to create, queue, run, or manage Coworker tasks, or asks about the Coworker pipeline, scheduler, or task automation.
allowed-tools: Bash(coworker:*)
---

# Coworker — File-Queue Task Automation

Coworker is a **filesystem-backed state machine** that runs GitHub Copilot against task files in this repo. Task files move through numbered directories; PowerShell scripts handle orchestration, logging, and git push.

## When to Use

- User wants to **create and queue a task** for Copilot to execute
- User wants to **run the Coworker worker or scheduler**
- User asks about **task pipelines, draft refinement, or GitHub issue creation**
- User needs to **review results, approve tasks, or trigger git push**
- User asks about **Coworker config, logs, or debugging**

## Core Mental Model

```
task file → 1ready → 2working (Copilot runs) → 3done → 5approved → 6git-pushed
                                                              ↓
                                                        git commit/push
```

- **Task files** are the queue
- **Numbered directories** are the state
- **PowerShell scripts** are the orchestrators
- **`300logs/`** is the audit trail
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

The worker picks up the task, renames it to kebab-case, runs Copilot, logs to `300logs/YYYY/MM/DD/`, and moves the task to `3done` (or `5approved` if `#auto-approve` is in the file).

## Key Commands

```powershell
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

| Pipeline | Ready | Working | Done | Notes |
|----------|-------|---------|------|-------|
| **Main tasks** | `main/1ready` | `main/2working` | `main/3done` | `5approved` → `6git-pushed` |
| **Draft refinement** | `main/0draft/refine/1ready` | `…/2working` | `…/3done` | See `refine-drafts.ps1` |
| **GitHub issues (refine)** | `200issues/draft/refine/0ready` | `…/1working` | `…/2done` | Extracts issues from drafts |
| **GitHub issues (commit)** | `200issues/github/commit/ready` | — | — | Creates via `gh issue create` |

## Draft Refinement

```powershell
.\coworker\scripts\workers\refine-drafts.ps1 -Path .\coworker\tasks\main\0draft\refine\1ready
```

## GitHub Issues Pipeline

Two-stage: refine drafts into structured issues, then create them on GitHub.

```powershell
.\coworker\scripts\workers\refine-github-issues.ps1   # Extract issues from drafts
.\coworker\scripts\workers\commit-github-issues.ps1   # Create on GitHub via gh CLI
```

Issue files support optional `Labels:`, `Assignees:`, and `Repo:` metadata fields.

## Configuration

- **`coworker/scripts/config.psd1`** — Copilot command (`gh copilot --model gpt-5.4 ...`), workspace/target roots
- **`coworker/scripts/coworker-scheduler.config.psd1`** — scheduler tasks, intervals, enabled/disabled state

## Safety Rules

1. **Never move files out of `2working`** while a run is active — the script handles state transitions
2. **Review before `5approved`** — approval triggers automated git commit/push over the whole repo
3. **Use `#auto-approve` sparingly** — it bypasses human review
4. **Check `300logs/` first when debugging** — task and Copilot logs are per-day under `YYYY/MM/DD`
5. **`gh` must be installed and authenticated** for git push and GitHub issue creation

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
