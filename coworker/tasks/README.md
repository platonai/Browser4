# Coworker Task State Machine

This document describes every task state, pipeline, and transition in the Coworker system.

---

## Pipeline 1: Main Task Execution

The primary pipeline for agentic task execution via `coworker/scripts/coworker.ps1`.

```
┌──────────────┐
│    0draft    │  ← Manual: author task files here
│   (Draft)    │
└──────┬───────┘
       │  User copies file to 1ready (or passes via -TaskFile)
       ▼
┌──────────────┐
│    1ready    │  ← Queue: ready for execution
│   (Queue)    │
└──────┬───────┘
       │  coworker.ps1 picks it up, renames to kebab-case
       ▼
┌──────────────┐
│   2working   │  ← Agent is actively executing the task
│  (Working)   │
└──────┬───────┘
       │  Agent exits
       ▼
┌──────────────┐
│    3done     │  ← Completed tasks (date-stamped: YYYY/MMDD/<file>)
│  (Complete)  │
└──────┬───────┘
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
│  (Approved)  │     (date-stamped: YYYY/MMDD/<file>)
└──────┬───────┘
       │  coworker.ps1 moves to date-stamped folder, invokes git-sync.ps1
       ▼
┌──────────────┐
│ 6git-pushed  │  ← Successfully committed and pushed
│   (Pushed)   │     (date-stamped: YYYY/MMDD/<file>)
└──────────────┘
```

### Fast path with `#auto-approve`

If a task file contains `#auto-approve`, it skips `3done`:

```
2working ──► 5approved (date-stamped) ──► 6git-pushed
```

### Timeout handling

`coworker.ps1` enforces a configurable timeout (`$agentRunTimeoutSeconds`, default 6000s).
If the agent process exceeds it, the process is killed and the task is moved to `3done`
with the timeout noted in the log. There is no separate "aborted" directory — all
completions (success, timeout, error) land in `3done`.

---

## Pipeline 2: Draft Refinement

Refines raw draft files using the AI agent via `coworker/scripts/workers/refine-drafts.ps1`,
invoked by the queue watcher `coworker/scripts/process-draft-refinement-queue.ps1`.

```
┌──────────────────────────┐
│  0draft/refine/0draft    │  ← Source drafts (manual)
│        (Source)          │
└──────────┬───────────────┘
           │  process-draft-refinement-queue.ps1 detects file
           ▼
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

┌──────────────────────────┐
│  0draft/refine/0error    │  ← Dead letter (after max retries)
│     (Dead Letter)        │
└──────────────────────────┘
```

Orphaned files in `2working` (older than 30 min) are recovered: returned to `1ready` for
retry, or moved to `0error` if max retries are exhausted.

---

## Pipeline 3: GitHub Issues

Extracts, refines, and creates GitHub issues from natural-language drafts.

### Stage A — Extraction & Refinement (`coworker/scripts/workers/refine-github-issues.ps1`)

```
┌──────────────────────────────────┐
│  issues/draft/refine/0ready   │  ← Draft files describing issues
│            (Ready)               │
└────────────┬─────────────────────┘
             │  refine-github-issues.ps1 picks it up
             ▼
┌──────────────────────────────────┐
│  issues/draft/refine/         │  ← Agent extracts individual issues
│       1working                   │
│       (In Process)               │
└──────┬───────────────┬───────────┘
       │               │
       │  Success      │  Failure (≤ max retries → returns to 0ready)
       ▼               ▼
   ┌─────── #auto-approve? ───────┐
   │                              │
   ▼ No                           ▼ Yes
┌──────────────┐          ┌──────────────────┐
│   2done      │          │ github/commit/ready │  ← commit-github-issues.ps1
│ (Manual      │          │ (Auto-committed)    │     picks up from here
│  review)     │          └─────────────────────┘
└──────────────┘
       │
       │  (after manual review: move to github/commit/ready)
       ▼
┌─────────────────────────┐
│  github/commit/ready    │
└─────────────────────────┘

┌──────────────────────────────────┐
│  issues/draft/refine/0error   │  ← Dead Letter (after max retries)
└──────────────────────────────────┘
```

### Stage B — GitHub Creation (`coworker/scripts/workers/commit-github-issues.ps1`)

```
┌───────────────────────────────────────┐
│  issues/github/commit/ready        │  ← Formatted issue .md files
│            (Ready)                    │     (written by refine-github-issues)
└────────────┬──────────────────────────┘
             │  commit-github-issues.ps1: gh issue create
             ▼
     ┌────────────────────┐
     │  github/commit/done│  ← Successfully created on GitHub
     │    (Done)          │
     └────────────────────┘

     ┌──────────────────────┐
     │ github/commit/failed │  ← gh CLI returned non-zero (manual inspection needed)
     │   (Failed)           │
     └──────────────────────┘
```

**Daily commit guard:** `commit-github-issues.ps1` caps issue creation at **20 per UTC day**
to avoid tripping GitHub spam detection. Overflow stays in `open` for the next run.
Daily state is tracked in `issues/github/commit/.daily-commit-state.json`.

### `#auto-approve` in GitHub issues

When `#auto-approve` appears in the last 5 lines of a draft being processed:

- Extracted issues AND the original draft are written directly to `issues/github/commit/ready`,
  where `commit-github-issues.ps1` will pick them up automatically.
- **Without** `#auto-approve`, everything goes to `issues/draft/refine/2done` for
  manual review. After review, approved issues should be moved to `github/commit/ready`.

---

## Pipeline 4: Task-Source Ingestion

External sources → new task files.

```
┌─────────────────────────┐
│  GitHub Issues          │  ← Issues from configured repo (platonai/Browser4)
│  (external)             │
└───────────┬─────────────┘
            │  fetch-github-issues.ps1 polls, creates .md files
            ▼
┌─────────────────────────┐
│  0draft/issues/github   │  ← Task file for each external issue
└─────────────────────────┘
```

`fetch-github-issues.ps1` pulls open issues, self-assigns unassigned ones, and saves each
as a markdown file in `0draft/issues/github/`. These drafts then flow into Pipeline 1
(via `1ready`) or Pipeline 3 (via `issues/draft/refine/0ready`).

---

## Scheduler Task State Machine

Each task managed by `coworker/scripts/coworker-scheduler.ps1` transitions through these states:

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
├── .locks/                           # Runtime lock files (mutexes)
│
├── 0draft/                           # Manual drafting area
│   ├── *.md                          # Task drafts (any name)
│   ├── issues/                       # Draft issues (manual)
│   ├── issues/github/                # GitHub issues fetched by fetch-github-issues.ps1
│   ├── bugs/                         # Draft bugs (manual)
│   ├── plan/                         # Draft plans (manual)
│   └── refine/                       # Draft refinement sub-pipeline
│       ├── 0draft/                   # Source drafts (manual)
│       ├── 1ready/                   # Queued for refinement
│       ├── 2working/                 # Agent refining
│       ├── 3done/                    # Refinement complete
│       └── 0error/                   # Dead letter (refinement failed after max retries)
│
├── 1ready/                           # Tasks queued for execution (coworker.ps1)
│
├── 2working/                         # Agent executing (coworker.ps1)
│
├── 3done/                            # Completed tasks
│   └── YYYY/MMDD/<file>              #   date-stamped
│
├── 4review/                          # Optional manual review (user-managed)
│
├── 5approved/                        # Approved for git push
│   └── YYYY/MMDD/<file>              #   date-stamped
│
├── 6git-pushed/                      # Successfully pushed
│   └── YYYY/MMDD/<file>              #   date-stamped
│
└── issues/                        # GitHub issues pipeline
    ├── draft/refine/
    │   ├── 0ready/                   # Issue drafts to refine
    │   ├── 1working/                 # Agent extracting issues
    │   ├── 2done/                    # Extraction complete
    │   └── 0error/                   # Failed (dead letter)
    └── github/
        └── commit/                   # GitHub issue commit pipeline
            ├── .daily-commit-state.json  # Daily commit guard state (max 20/day)
            ├── ready/                # Formatted issues ready for gh CLI
            ├── draft/                # Original drafts awaiting manual approval
            ├── done/                 # Successfully created on GitHub
            └── failed/               # gh CLI returned error
```

**Log directory** (configured in `config.psd1`):
```
~\.browser4-coworker\tasks\300logs\   # All execution logs
    └── YYYY/MM/DD/                   #   organized by date
        └── HHmmss-<taskname>.log
```

---

## Summary: Entry Points to Pipeline Mapping

| Script | Pipeline | Input Directory | Output Directory |
|--------|----------|----------------|------------------|
| `coworker.ps1` | Main | `1ready` | `3done` or `5approved` |
| `process-coworker-queue.ps1` | Main (watcher) | `1ready`, `5approved` | _(triggers `coworker.ps1`)_ |
| `process-draft-refinement-queue.ps1` | Draft Refinement (watcher) | `0draft/refine/1ready` | _(triggers `refine-drafts.ps1`)_ |
| `refine-drafts.ps1` | Draft Refinement | `0draft/refine/1ready` | `0draft/refine/3done` |
| `refine-github-issues.ps1` | Issues: Refine | `issues/draft/refine/0ready` | `issues/draft/refine/2done` or `issues/github/commit/ready` (see `#auto-approve`) |
| `commit-github-issues.ps1` | Issues: Commit | `issues/github/commit/ready` | `issues/github/commit/done` |
| `git-sync.ps1` | Git Push | `5approved` | `6git-pushed` |
| `fetch-github-issues.ps1` | Ingestion | GitHub (external) | `0draft/issues/github` |
| `coworker-scheduler.ps1` | All (orchestrator) | _(all above)_ | _(all above)_ |

All scripts live under `coworker/scripts/`. Queue watchers and the scheduler are at
`coworker/scripts/` root; worker scripts are at `coworker/scripts/workers/`.
