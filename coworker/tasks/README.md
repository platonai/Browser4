# Coworker Task State Machine

This document describes every task state, pipeline, and transition in the Coworker system.

---

## Pipeline 1: Main Task Execution

The primary pipeline for agentic task execution via `coworker.ps1`.

```
┌──────────────┐
│    0draft    │  ← Manual: author task files here
│   (Draft)    │
└──────┬───────┘
       │  User copies file
       ▼
┌──────────────┐
│   1ready   │  ← Queue: ready for execution
│   (Queue)    │
└──────┬───────┘
       │  coworker.ps1 picks it up, renames to kebab-case
       ▼
┌──────────────┐
│   200plan    │  ← Agent planning phase (managed automatically)
│   (Plan)     │
└──────┬───────┘
       │
       ▼
┌──────────────┐
│   2working   │  ← Agent is actively executing the task
│  (Working)   │
└──┬──────┬────┘
   │      │
   │      └────────────────────────────────┐
   │  Normal completion                    │  Timeout / stuck / error
   ▼                                       ▼
┌──────────────┐                    ┌──────────────┐
│ 3_1complete  │                    │  3_5aborted  │
│  (Complete)  │                    │  (Aborted)   │
└──────┬───────┘                    └──────────────┘
       │
       │  User moves file (after review)
       ▼
┌──────────────┐
│   4review    │  ← Optional manual review stage
│   (Review)   │
└──────┬───────┘
       │  User approves
       ▼
┌──────────────┐
│  5approved   │  ← Approved tasks awaiting git commit/push
│  (Approved)  │
└──────┬───────┘
       │  coworker.ps1 moves to date-stamped folder, invokes git-sync.ps1
       ▼
┌──────────────┐
│ 6git-pushed  │  ← Successfully committed and pushed
│   (Pushed)   │     (structured as YYYY/MMDD/<file>)
└──────┬───────┘
       │
       ▼
┌──────────────┐
│  700archive  │  ← Archived completed tasks
│  (Archive)   │
└──────────────┘
```

### Fast path with `#auto-approve`

If a task file contains `#auto-approve`, it skips `3_1complete`:

```
2working ──► 5approved (date-stamped) ──► 6git-pushed ──► 700archive
```

### Failure path

If a task is stuck in `2working` beyond a timeout (detected by `process-coworker-queue.ps1`):

```
2working ──► 3_5aborted
```

---

## Pipeline 2: Draft Refinement

Refines raw draft files using the AI agent via `workers/refine-drafts.ps1`.

```
┌──────────────────────────┐
│  0draft/refine/1ready    │  ← Drafts waiting to be refined
│        (Ready)           │
└──────────┬───────────────┘
           │  refine-drafts.ps1 picks it up
           ▼
┌──────────────────────────┐
│  0draft/refine/2working  │  ← Agent is refining the draft
│       (Working)          │
└──────────┬───────────────┘
           │  Refinement complete
           ▼
┌──────────────────────────┐
│  0draft/refine/3done     │  ← Refined drafts ready for review
│        (Done)            │
└──────────────────────────┘
```

---

## Pipeline 3: GitHub Issues

Extracts, refines, and creates GitHub issues from natural-language drafts.

### Stage A — Extraction & Refinement (`workers/refine-github-issues.ps1`)

```
┌──────────────────────────────────┐
│  200issues/draft/refine/0ready   │  ← Draft files describing issues
│            (Ready)               │
└────────────┬─────────────────────┘
             │  refine-github-issues.ps1 picks it up
             ▼
┌──────────────────────────────────┐
│  200issues/draft/refine/         │  ← Agent extracts individual issues,
│       1working                │     formats each, writes to github/open
│       (In Process)               │
└──────┬───────────────┬───────────┘
       │               │
       │  Success      │  Failure (≤ max retries → returns to 0ready)
       ▼               ▼
┌──────────────┐  ┌──────────────────────────────────┐
│   2done      │  │  200issues/draft/refine/0error   │
│   (Done)     │  │  (Dead Letter — after max retries)│
└──────────────┘  └──────────────────────────────────┘
```

### Stage B — GitHub Creation (`workers/commit-github-issues.ps1`)

```
┌──────────────────────────────────┐
│  200issues/github/open           │  ← Formatted issue .md files
│            (Open)                │     (written by refine-github-issues)
└────────────┬─────────────────────┘
             │  commit-github-issues.ps1: gh issue create
             ▼
     ┌───────────────┐
     │  github/done  │  ← Successfully created on GitHub
     │    (Done)     │
     └───────────────┘
     
     ┌───────────────┐
     │ github/failed │  ← gh CLI returned non-zero (manual inspection needed)
     │   (Failed)    │
     └───────────────┘
```

### `#auto-approve` in GitHub issues

When `#auto-approve` appears in the last 5 lines of a draft in `1working`, the **original draft file** is also moved to `200issues/github/open` as a separate issue (e.g., parent/epic issue), alongside the individual extracted issues.

---

## Pipeline 4: Task-Source Ingestion

External sources → new task files in `1ready`.

```
┌─────────────────────────┐
│  GitHub Issues          │  ← Issues assigned to configured user
│  (external)             │
└───────────┬─────────────┘
            │  process-task-source.ps1 polls, creates .md files
            ▼
┌─────────────────────────┐
│      1ready           │  ← New task file for each external item
└─────────────────────────┘

┌─────────────────────────┐
│  Polled URL             │  ← URL content containing keyword
│  (external)             │
└───────────┬─────────────┘
            │  process-task-source.ps1 polls, creates .md files
            ▼
┌─────────────────────────┐
│      1ready           │
└─────────────────────────┘
```

---

## Scheduler Task State Machine

Each task managed by `coworker-scheduler.ps1` transitions through these states:

```
                    ┌──────────┐
                    │ Disabled │  ← Enabled = $false in config
                    └──────────┘

                    ┌──────────┐
          ┌────────►│   Idle   │◄─────────┐
          │         └────┬─────┘          │
          │              │                │
          │              │ Interval       │ ExitCode = 0
          │              │ elapsed +      │ (process completes)
          │              │ pending files  │
          │              │ present        │
          │              ▼                │
          │         ┌──────────┐          │
          │         │ Running  │──────────┤
          │         └────┬─────┘          │
          │              │                │
          │              │ Process exits  │
          │              │ ExitCode ≠ 0   │
          │              ▼                │
          │         ┌──────────┐          │
          │         │  Failed  │──────────┘
          │         └──────────┘   (on next interval,
          │                          resets to Idle)
          │
          │  ┌────────────────┐
          └──│ WaitingForWork │  ← PendingPaths configured but
             └────────────────┘    no files present; skips this tick
                (on next interval, rechecks paths)
```

### State transitions detail

| From | To | Trigger |
|------|----|---------|
| _(init)_ | `Idle` | Task first loaded with `Enabled = $true` |
| _(init)_ | `Disabled` | Task first loaded with `Enabled = $false` |
| `Idle` | `Running` | `CanStart`: interval elapsed, dependencies satisfied, pending files present |
| `Idle` | `WaitingForWork` | `CanStart` but no pending files in `PendingPaths` |
| `WaitingForWork` | `Running` | Next tick: interval elapsed, pending files now present |
| `Running` | `Idle` | Process exits with code 0 |
| `Running` | `Failed` | Process exits with code ≠ 0 |
| `Failed` | `Idle` | Next interval elapsed → scheduler retries |
| `Idle` / `Failed` | `Disabled` | Config reload with `Enabled = $false` |

### Scheduler tick flow (each pass)

```
Tick ──► Refresh all processes (detect exits) ──► Update statuses
                                                       │
                                                       ▼
                    For each task (sorted by Name):
                         │
                         ├─ Disabled? ──► skip
                         ├─ Already running? ──► skip
                         ├─ CanStart?
                         │    ├─ Interval elapsed?
                         │    ├─ Dependencies satisfied?
                         │    └─ (Once mode: not already run?)
                         │         │
                         │    ┌────┘
                         │    ▼
                         ├─ HasPendingInputs? ── NO ──► WaitingForWork
                         │         │
                         │    YES  │
                         │    ▼   │
                         └─ Start-ScheduledTaskRun
                              │
                              ├─ Write stdout/err to 300logs/YYYY/MM/DD/
                              ├─ Register Process.Exited event
                              └─ Status → Running
```

---

## Complete Directory Map

```
coworker/tasks/
│
├── 0draft/                          # Manual drafting area
│   ├── *.md                         # Task drafts (any name)
│   ├── issues/                      # Draft issues (manual)
│   ├── bugs/                        # Draft bugs (manual)
│   ├── plan/                        # Draft plans (manual)
│   └── refine/                      # Draft refinement sub-pipeline
│       ├── 0draft/                  # Source drafts
│       ├── 1ready/                  # Queued for refinement
│       ├── 2working/                # Agent refining
│       └── 3done/                   # Refinement complete
│
├── 1ready/                        # Tasks queued for execution
│
├── 200plan/                         # Agent planning phase
│
├── 2working/                        # Agent executing (coworker.ps1)
│
├── 3_1complete/                     # Completed tasks
│   └── YYYY/MMDD/<file>             #   date-stamped
│
├── 4review/                         # Optional manual review (user-managed)
│
├── 5approved/                       # Approved for git push
│
├── 6git-pushed/                     # Successfully pushed
│   └── YYYY/MMDD/<file>             #   date-stamped
│
├── 700archive/                      # Archived completed tasks
│
├── 200issues/                       # GitHub issues pipeline
│   ├── draft/refine/
│   │   ├── 0ready/                  # Issue drafts to refine
│   │   ├── 1working/             # Agent extracting issues
│   │   ├── 2done/                   # Extraction complete
│   │   └── 0error/                  # Failed (dead letter)
│   └── github/
│       ├── open/                    # Formatted issues ready for gh CLI
│       ├── done/                    # Successfully created on GitHub
│       └── failed/                  # gh CLI returned error
│
├── 300logs/                         # All execution logs
│   └── YYYY/MM/DD/                  #   organized by date
│       └── HHmmss-<taskname>.stdout.log
│
└── 100templates/                    # Prompt templates (orchestrator pipeline)
```

---

## Summary: Entry Points to Pipeline Mapping

| Script | Pipeline | Input Directory | Output Directory |
|--------|----------|----------------|------------------|
| `coworker.ps1` | Main | `1ready` | `3_1complete` or `5approved` |
| `refine-drafts.ps1` | Draft Refinement | `0draft/refine/1ready` | `0draft/refine/3done` |
| `refine-github-issues.ps1` | Issues: Refine | `200issues/draft/refine/0ready` | `200issues/github/open` |
| `commit-github-issues.ps1` | Issues: Commit | `200issues/github/open` | `200issues/github/done` |
| `git-sync.ps1` | Git Push | `5approved` | `6git-pushed` |
| `process-task-source.ps1` | Ingestion | GitHub/URL (external) | `1ready` |
| `coworker-scheduler.ps1` | All (orchestrator) | _(all above)_ | _(all above)_ |
