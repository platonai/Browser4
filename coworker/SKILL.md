# Coworker

This repository contains a **file-queue automation system** called **Coworker**. The active implementation is the PowerShell worker in `coworker/scripts/coworker.ps1`: it watches task files, renames them, runs GitHub Copilot against the repository, logs the run, and routes the task through review/approval/push folders. (`coworker/README.md:1-40`, `coworker/scripts/coworker.ps1:50-80`, `coworker/scripts/coworker.ps1:413-717`)

## What Coworker is for

- Use Coworker when you want an agentic workflow driven by **task files in the repo**, not by chat alone.
- A task can be a plain Markdown file or a structured file with `Title:`, `Description:`, and `Prompt:`. If the structured header is missing, the full file becomes the prompt. (`coworker/scripts/coworker.ps1:11-20`, `coworker/scripts/coworker.ps1:536-541`)
- The worker runs GitHub Copilot CLI with broad repo access to execute the task against the current repository. The helper command is configurable in `coworker/scripts/config.psd1`. (`coworker/scripts/workers/gh-copilot.ps1:37-65`, `coworker/scripts/config.psd1:1-12`)
- Important: the **current/live workflow is file-based**. The folder-based “story.md / analysis / plan / design / impl.patch / e2e” pipeline described in `coworker/docs/architect/orchestrator.md` and `coworker/scripts/architect/orchestrator.ps1` exists as design/legacy material, but it is **not** the main coworker entrypoint used by `coworker.ps1`. (`coworker/docs/architect/orchestrator.md:1-38`, `coworker/scripts/architect/orchestrator.ps1:17-29`, `coworker/scripts/coworker.ps1:57-68`)

## Main workflows and scripts

### 1. Main task execution

- **Primary entrypoint:** `./coworker/scripts/coworker.ps1`
- It ensures the task directories exist, optionally accepts a task file path, moves that file into `coworker/tasks/1ready`, generates a descriptive kebab-case filename, moves it to `2working`, runs Copilot, writes logs, then moves the task to `3_1complete` or `5approved`. (`coworker/scripts/coworker.ps1:22-35`, `coworker/scripts/coworker.ps1:82-95`, `coworker/scripts/coworker.ps1:417-500`, `coworker/scripts/coworker.ps1:551-713`)
- The prompt given to Copilot explicitly says: **finish the task described in the file, but do not move that task file yourself**. The script handles routing after execution. (`coworker/scripts/coworker.ps1:531-545`)

### 2. Queue processor / watchdog

- **Recommended wrapper for one-shot or recurring checks:** `./coworker/scripts/process-coworker-queue.ps1`
- It checks for pending files in `1ready` or `5approved`, avoids duplicate coworker runners, can optionally run task-source monitoring first, and has loop-detection logic that can kill a stuck coworker process and move the task from `2working` to `3_5aborted`. (`coworker/scripts/process-coworker-queue.ps1:28-37`, `coworker/scripts/process-coworker-queue.ps1:55-69`, `coworker/scripts/process-coworker-queue.ps1:71-174`, `coworker/scripts/process-coworker-queue.ps1:176-250`)

### 3. Unified scheduler

- **Preferred automation entrypoint:** `./coworker/scripts/coworker-scheduler.ps1`
- Scheduler definitions live in `coworker/scripts/coworker-scheduler.config.psd1`.
- Current defaults:
  - `coworker` every 15s, dependent on task-source processing, only when `1ready` or `5approved` has files.
  - `draft-refinement` every 15s, only when `0draft/refine/1ready` has files.
  - `commit-github-issues` every 15s, only when `200issues/github/commit/ready` has files.
  - `refine-github-issues` every 15s, only when `200issues/draft/refine/0ready` has files.
  - `process-task-source` exists but is **disabled by default**. (`coworker/scripts/coworker-scheduler.config.psd1:1-62`)
- The scheduler launches child PowerShell processes, keeps live worker output in each child terminal, records a console transcript log under `coworker/tasks/300logs/`, and writes a JSON status snapshot. (`coworker/scripts/coworker-scheduler.ps1`)

### 4. Draft refinement pipeline

- Draft refinement is separate from main task execution.
- Queue:
  - `coworker/tasks/0draft/refine/1ready`
  - `coworker/tasks/0draft/refine/2working`
  - `coworker/tasks/0draft/refine/3done`
- Main scripts:
  - `coworker/scripts/workers/refine-drafts.ps1`
  - `coworker/scripts/process-draft-refinement-queue.ps1`
- `refine-drafts.ps1` moves files from ready -> working, asks Copilot to return only the refined document, then writes the refined content and moves the file to done. (`coworker/README.md:128-152`, `coworker/scripts/workers/refine-drafts.ps1:31-39`, `coworker/scripts/workers/refine-drafts.ps1:89-151`, `coworker/scripts/process-draft-refinement-queue.ps1:28-71`)

### 5. GitHub Issues pipeline

- Two-stage pipeline for extracting, refining, and creating GitHub issues from natural-language drafts:
  1. **Refine** (`coworker/scripts/workers/refine-github-issues.ps1`): Scans `200issues/draft/refine/0ready`, invokes the agent to extract individual issues from each draft, formats each as structured markdown, and writes them to `200issues/github/commit/ready`.
  2. **Commit** (`coworker/scripts/workers/commit-github-issues.ps1`): Scans `200issues/github/commit/ready` and creates each file as a GitHub issue via `gh issue create`.
- Directories: `200issues/draft/refine/0ready` → `1working` → `2done` (or `0error` on failure); staged files land in `200issues/github/commit/ready`.
- Supports optional `Labels:`, `Assignees:`, and `Repo:` metadata fields in each issue block. (`coworker/scripts/workers/refine-github-issues.ps1:1-29`, `coworker/scripts/workers/commit-github-issues.ps1:1-19`)
- Both tasks are **enabled by default** in the scheduler config. (`coworker/scripts/coworker-scheduler.config.psd1:35-60`)

### 6. Task-source ingestion

- `coworker/scripts/process-task-source.ps1` can create new task files in `coworker/tasks/1ready` from:
  - GitHub issues assigned to a configured user
  - a polled URL containing a keyword
- Defaults are repo `platonai/Browser4`, assignee `galaxyeye`, and keyword `@galaxyeye`. (`coworker/scripts/process-task-source.ps1:18-25`, `coworker/scripts/process-task-source.ps1:40-58`, `coworker/scripts/process-task-source.ps1:61-106`, `coworker/scripts/process-task-source.ps1:109-151`)
- This source monitor is useful, but remember it is **disabled in the default scheduler config**. (`coworker/scripts/coworker-scheduler.config.psd1:62-67`)

### 7. Git sync / pushing approved work

- When Coworker sees files in `5approved`, it first moves them into a date-based folder under `6git-pushed`, then invokes `coworker/scripts/workers/git-sync.ps1`. (`coworker/scripts/coworker.ps1:365-399`)
- `git-sync.ps1` does not contain custom git logic; it asks GitHub Copilot to **commit all changes in the repo, pull, push, and auto-resolve conflicts**. Treat this as powerful and potentially risky. (`coworker/scripts/workers/git-sync.ps1:16-29`)

### 8. Memory helpers

- Before executing a task, `coworker.ps1` calls `coworker/scripts/workers/coworker-memory-generator.ps1 -Type init` and appends returned memory context/instructions to the task prompt. (`coworker/scripts/coworker.ps1:508-545`)
- Daily/monthly/yearly/global memory summaries are generated from `coworker/tasks/300logs`. (`coworker/scripts/workers/coworker-memory-generator.ps1:15-22`, `coworker/scripts/workers/coworker-memory-generator.ps1:103-172`, `coworker/scripts/workers/coworker-memory-generator.ps1:174-347`, `coworker/scripts/workers/coworker-daily-memory-generator.ps1:49-56`, `coworker/scripts/workers/coworker-daily-memory-generator.ps1:169-233`)

## How tasks move through directories

### Main task lifecycle

1. **Draft** in `coworker/tasks/0draft` (manual authoring area). (`coworker/README.md:16-28`, `coworker/tasks/0draft/README.md:1-37`)
2. **Queue** by moving/copying the task file to `coworker/tasks/1ready`. (`coworker/README.md:30-40`)
3. **Rename + start work**: Coworker generates a descriptive kebab-case name and moves the file to `coworker/tasks/2working`. (`coworker/scripts/coworker.ps1:417-500`, `coworker/scripts/workers/rename.ps1:30-60`, `coworker/scripts/workers/rename.ps1:153-178`)
4. **Execute**: Copilot works against the repo; logs are written under `coworker/tasks/300logs/YYYY/MM/DD`. (`coworker/scripts/coworker.ps1:548-552`, `coworker/scripts/coworker.ps1:575-687`)
5. **Finish**:
   - normal tasks -> `coworker/tasks/3_1complete/YYYY/MMDD/...`
   - tasks containing `#auto-approve` -> `coworker/tasks/5approved/YYYY/MMDD/...` (`coworker/scripts/coworker.ps1:696-713`, `coworker/README.md:48-55`)
6. **Approval / push**:
   - human review can happen in `3_1complete` and optionally `4review`
   - moving a reviewed task to `5approved` causes the next run to move it into `6git-pushed/YYYY/MMDD/...` and invoke git sync. (`coworker/README.md:7-12`, `coworker/scripts/coworker.ps1:348-399`)
7. **Failure path**: stuck/aborted tasks can end up in `coworker/tasks/3_5aborted`. (`coworker/scripts/process-coworker-queue.ps1:133-174`)

### Separate draft-refinement lifecycle

- `0draft/refine/1ready` -> `0draft/refine/2working` -> `0draft/refine/3done`. (`coworker/README.md:128-152`, `coworker/scripts/workers/refine-drafts.ps1:31-39`, `coworker/scripts/workers/refine-drafts.ps1:129-151`)

### Separate GitHub issues lifecycle

- **Refine**: `200issues/draft/refine/0ready` -> `1working` -> `2done` (or `0error` on failure).
- **Commit**: `200issues/github/commit/ready` -> created via `gh issue create`.
- `#auto-approve` in a draft's last 5 lines also routes the original draft to `github/commit/ready` as an issue. (`coworker/scripts/workers/refine-github-issues.ps1:31-36`, `coworker/scripts/workers/refine-github-issues.ps1:447-465`, `coworker/scripts/workers/commit-github-issues.ps1:1-19`)

## Key PowerShell commands / entrypoints

From repository root in PowerShell:

```powershell
# Run the unified scheduler continuously
.\coworker\scripts\coworker-scheduler.ps1

# Run one scheduler pass
.\coworker\scripts\coworker-scheduler.ps1 -Once

# Run one coworker queue check
.\coworker\scripts\process-coworker-queue.ps1 -Once

# Run coworker directly now
.\coworker\scripts\coworker.ps1

# Queue a specific task file directly
.\coworker\scripts\coworker.ps1 .\path\to\task.md

# Process draft refinement once
.\coworker\scripts\process-draft-refinement-queue.ps1 -Once

# Refine all ready drafts
.\coworker\scripts\workers\refine-drafts.ps1 -Path .\coworker\tasks\0draft\refine\1ready

# Extract and refine GitHub issues from drafts
.\coworker\scripts\workers\refine-github-issues.ps1

# Create GitHub issues from staged files
.\coworker\scripts\workers\commit-github-issues.ps1

# Poll external task sources once
.\coworker\scripts\process-task-source.ps1 -Once

# Run git sync manually
.\coworker\scripts\workers\git-sync.ps1
```

References: `coworker/README.md:34-40`, `coworker/README.md:85-90`, `coworker/README.md:121-152`, `coworker/scripts/coworker.ps1:22-35`, `coworker/scripts/process-task-source.ps1:3-16`.

## Important config files

- `coworker/scripts/config.psd1` — defines the Copilot command. Current default is `gh copilot --model gpt-5.4 --no-ask-user --log-level info --allow-all`. (`coworker/scripts/config.psd1:1-12`)
- `coworker/scripts/config.ps1` — loads `config.psd1` and exposes `$COPILOT`. (`coworker/scripts/config.ps1:1-12`)
- `coworker/scripts/coworker-scheduler.config.psd1` — scheduler tasks, intervals, pending paths, and status/log paths. (`coworker/scripts/coworker-scheduler.config.psd1:1-41`)
- `coworker/tasks/100templates/*.prompt.md` — prompt templates for the older orchestrator pipeline, not the normal file-runner path. (`coworker/docs/architect/orchestrator.md:23-38`, `coworker/scripts/architect/orchestrator.ps1:17-23`, `coworker/tasks/100templates/analysis.prompt.md:1-8`, `coworker/tasks/100templates/implementation.prompt.md:1-15`)
- `coworker/README.md` and `coworker/tasks/0draft/README.md` — the clearest human-facing usage docs. (`coworker/README.md:1-153`, `coworker/tasks/0draft/README.md:1-37`)

## Conventions and safety notes

- **Prefer the active file workflow.** For normal use, create a Markdown task file and queue it; do not start with the older `story.md` orchestrator structure unless you are intentionally working on that subsystem. (`coworker/scripts/coworker.ps1:57-68`, `coworker/docs/architect/orchestrator.md:9-30`)
- **Numeric filenames are OK.** Coworker auto-creates `0draft/1.md`..`5.md` placeholders and can rename generic/numeric task files to descriptive kebab-case names. (`coworker/scripts/coworker.ps1:172-185`, `coworker/scripts/coworker.ps1:340`, `coworker/scripts/coworker.ps1:417-489`)
- **Do not manually move files out of `2working` while a run is active.** The worker prompt and post-processing assume the script, not the agent, handles state transitions. (`coworker/scripts/coworker.ps1:531-545`, `coworker/scripts/coworker.ps1:693-713`)
- **Use `#auto-approve` sparingly.** It bypasses manual review and sends the task straight to the approval/push path. (`coworker/README.md:48-55`, `coworker/scripts/coworker.ps1:696-713`)
- **Review before `5approved`.** Approval eventually triggers a Copilot-driven git commit/pull/push over the whole repo. (`coworker/scripts/coworker.ps1:365-399`, `coworker/scripts/workers/git-sync.ps1:16-29`)
- **Expect broad tool access.** The configured Copilot command already includes `--allow-all`, and many calls also add `--allow-all-tools` / `--allow-all-paths`. Coworker is meant to act on the repository, not just read it. (`coworker/scripts/config.psd1:1-12`, `coworker/scripts/coworker.ps1:575-585`, `coworker/scripts/workers/refine-drafts.ps1:108-118`)
- **Check logs first when debugging.** Task logs and Copilot logs are written per day under `coworker/tasks/300logs/YYYY/MM/DD`. Scheduler child-process logs go under that same directory using `HHmmss-<taskname>.stdout.log` filenames. (`coworker/scripts/coworker.ps1:103-106`, `coworker/scripts/coworker.ps1:548-552`, `coworker/scripts/coworker-scheduler.config.psd1:7-8`, `coworker/scripts/coworker-scheduler.ps1:163-169`)
- **GitHub CLI auth is required.** The docs explicitly require `gh` to be installed and authenticated. (`coworker/README.md:42-46`)

## Recommended mental model for agents

Treat Coworker as a **filesystem-backed state machine** around GitHub Copilot:

- task files are the queue
- numbered directories are the state
- PowerShell scripts are the orchestrators
- `300logs` is the audit trail
- `5approved` is the point of no return for automated git operations

If you need to use Coworker safely, the normal path is:

1. draft a Markdown task
2. queue it in `coworker/tasks/1ready`
3. run `process-coworker-queue.ps1 -Once` or the scheduler
4. inspect `3_1complete` and `300logs`
5. only then move the task to `5approved` if you want automated commit/push
