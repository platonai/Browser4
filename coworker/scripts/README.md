# Scripts

This directory contains all PowerShell scripts that power the AI Coworker system — the task runner, scheduler, queue processors, worker modules, and shared utilities.

## Architecture

```
scripts/
├── config.ps1                          # Configuration loader (dot-source first)
├── config.psd1                         # Configuration data (paths, backend settings)
├── coworker.ps1                        # Main task runner
├── coworker-scheduler.ps1              # Unified scheduler (continuous mode)
├── coworker-scheduler.config.psd1      # Scheduler task definitions
├── process-coworker-queue.ps1          # Standalone queue watcher
├── process-draft-refinement-queue.ps1  # Standalone draft-refinement watcher
├── common/                             # Shared utility modules
│   ├── Util.ps1                        # UTF-8 encoding setup
│   ├── Paths.ps1                       # Path resolution helpers
│   ├── Logging.ps1                     # Structured logging & ANSI sanitization
│   ├── Locks.ps1                       # Script-level file mutex
│   └── Watchers.ps1                    # FileSystemWatcher wrappers & file filters
├── workers/                            # Individual task worker scripts
│   ├── agent.ps1                       # Agent process launcher (claude, kimi, or copilot backend)
│   ├── agent-reliability.ps1           # Agent wrapper with timeout, retry, structured output
│   ├── workflow.ps1                    # Task pipeline utilities (naming, placeholders, etc.)
│   ├── task-logger.ps1                 # Console & file logging for task execution
│   ├── prompt-utils.ps1                # Shared prompt construction utilities
│   ├── git-sync.ps1                    # Commit, pull, and push via agent
│   ├── coworker.ps1 caller
│   ├── coworker-memory-generator.ps1   # Memory summary generation (daily/monthly/yearly/global)
│   ├── coworker-memory-context.ps1     # Memory context initializer for task prompts
│   ├── coworker-daily-memory-generator.ps1  # Daily memory orchestration
│   ├── refine-drafts.ps1               # AI-powered draft refinement
│   ├── refine-github-issues.ps1        # Extract, split, and format GitHub issues from drafts
│   ├── commit-github-issues.ps1        # Create GitHub issues via `gh` CLI
│   ├── fetch-github-issues.ps1         # Fetch repo issues and self-assign unassigned ones
│   ├── triage-github-issues.ps1        # Auto-triage fetched issues for AI execution
│   ├── organize-task-files.ps1         # Reorganize task dirs into YYYY/MMDD subfolders
│   ├── update-readmes.ps1              # Detect stale READMEs and queue update tasks
│   ├── rename.ps1                      # AI-driven task file renaming
│   ├── refine-last-draft.ps1           # Refine the most recent draft
│   ├── browser4-eval-prompt.ps1        # Evaluation prompt helper for Browser4
│   ├── count-total-token-usage.ps1     # Token usage counter (wrapper)
│   ├── count-total-token-usage.py      # Token usage counter (Python core)
│   ├── writer.ps1                      # File writer utility
│   └── *.tests.ps1                     # Inline unit tests for individual workers
└── tests/                              # Pester test suite
    ├── coworker.tests.ps1              # Main coworker.ps1 tests
    ├── coworker-scripts.ps1            # Scripts integration tests
    └── test-utils.psm1                 # Shared test utilities module
```

## Configuration

### `config.psd1` — Configuration Data

The central config file. Contains:

- **`Paths`** — Workspace root, coworker root, tasks root, target repository, and log directory. Supports `~` expansion for cross-user portability.
- **`COPILOT`** / **`CLAUDE`** / **`KIMI`** — Backend selection. Define one (or more) as a PowerShell array `@('executable', 'arg1', 'arg2', ...)`. Priority when several are defined: `CLAUDE` > `KIMI` > `COPILOT` (default: `@('gh', 'copilot')`). `Get-AgentBackend` in `config.ps1` is the single resolver used by all worker scripts.
- **`Scheduler`** — Default working directory for scheduled tasks.

### `config.ps1` — Configuration Loader

Dot-source this file first in every script to get all shared utilities in scope. It:

1. Loads `config.psd1` data
2. Dot-sources `common/Util.ps1`, `common/Paths.ps1`, `common/Watchers.ps1`, `common/Logging.ps1`, `common/Locks.ps1`
3. Prepends known tool directories (scoop shims, npm global, Git) to `PATH`

## Core Scripts

### `coworker.ps1` — Main Task Runner

The primary task execution engine. Processes tasks through the full pipeline:

1. **`0draft`** → Reports draft files present
2. **`1ready`** → Renames files (AI-driven kebab-case naming), moves to working
3. **`2working`** → Injects memory context, executes the agent with the task prompt
4. **`3done`** → Moves completed tasks here (or `5approved` if `#auto-approve` tag is present)
5. **`5approved`** → Auto-commits and pushes approved tasks via `git-sync.ps1`
6. **`6git-pushed`** → Reports recently pushed tasks (last 2 days)

Supports passing a specific task file directly:
```powershell
.\coworker\scripts\coworker.ps1 path\to\task.md
```

Uses a script-level mutex to prevent concurrent instances. All agent execution output is logged to `~\.browser4-coworker\tasks\300logs\YYYY\MM\DD\`.

### `coworker-scheduler.ps1` — Unified Scheduler

A single entry point that launches and monitors all recurring coworker jobs. Each job runs in its own PowerShell process with console transcript logging, and the scheduler writes a live status file to `logs/scheduled-tasks.status.json`.

```powershell
.\coworker\scripts\coworker-scheduler.ps1        # Continuous mode
.\coworker\scripts\coworker-scheduler.ps1 -Once  # One-shot: run due tasks and exit
```

Task definitions live in `coworker-scheduler.config.psd1`. Each task supports:
- `Enabled` / `IntervalSeconds` / `WindowStyle`
- `DependsOn` for ordering between tasks
- `PendingPaths` — file/directory inputs to watch; the scheduler skips spawning a worker when no work is present

**Default scheduled tasks** (from `coworker-scheduler.config.psd1`):

| Task | Interval | Worker Script | Trigger |
|------|----------|--------------|---------|
| `coworker` | 15s | `coworker.ps1` | `main/1ready` or `main/5approved` |
| `draft-refinement` | 15s | `workers/refine-drafts.ps1` | `main/0draft/refine/1ready` |
| `commit-github-issues` | _disabled_ | `workers/commit-github-issues.ps1` | `issues/github/commit/ready` |
| `refine-github-issues` | 15s | `workers/refine-github-issues.ps1` | `issues/draft/refine/0ready` |
| `fetch-github-issues` | 10m | `workers/fetch-github-issues.ps1` | _(always runs)_ |
| `organize-task-files` | 5m | `workers/organize-task-files.ps1` | _(always runs)_ |
| `triage-github-issues` | 30m | `workers/triage-github-issues.ps1` | `main/0draft/issues/github` |
| `update-readmes` | 1h | `workers/update-readmes.ps1 -Update -MaxTasks 2` | _(always runs)_ |

## Queue Processors

Standalone scripts that watch for work and spawn the appropriate worker. Useful for one-shot execution outside the scheduler:

```powershell
.\coworker\scripts\process-coworker-queue.ps1              # Watch and spawn coworker.ps1
.\coworker\scripts\process-coworker-queue.ps1 -Once        # Single check, exit after
.\coworker\scripts\process-draft-refinement-queue.ps1 -Once # Single refinement pass
```

For recurring automation, prefer `coworker-scheduler.ps1`.

## Common Modules

All files under `common/` are dot-sourced by `config.ps1` and provide foundational utilities:

### `Util.ps1`
Sets console and file I/O encoding to UTF-8 to prevent mojibake, especially with Chinese text. Also sets `MAVEN_OPTS` and `JAVA_TOOL_OPTIONS` for downstream Java tooling.

### `Paths.ps1`
Path resolution helpers that read from `config.psd1` and resolve relative paths with `~` expansion:
- `Get-WorkspaceRoot` — The control repository root
- `Get-TargetRepositoryRoot` — Where task-mode agents run (falls back to workspace root)
- `Get-LogDirectory` — Log output path (default: `~\.browser4-coworker\tasks\300logs`)
- `Resolve-TasksPath` / `Resolve-CoworkerPath` / `Resolve-WorkspacePath`

### `Logging.ps1`
- `Write-CoworkerLog` — Timestamped, leveled logging (`DEBUG`, `INFO`, `WARN`, `ERROR`) with console color
- `Normalize-CoworkerLogFile` — Strips ANSI escape sequences from log files for clean output
- `Remove-AnsiEscapeSequences` — Low-level ANSI remover

### `Locks.ps1`
Script-level file-based mutex system. Locks are keyed by SHA256 of the resolved script path and stored under `coworker/tasks/.locks/`. Provides:
- `New-CoworkerScriptLock` — Acquire a lock (returns `$null` with `-SkipIfHeld`)
- `Remove-CoworkerScriptLock` — Release a lock
- `Test-CoworkerScriptLockHeld` — Check if a script is already running; cleans up stale locks

### `Watchers.ps1`
File system watcher wrappers and file validation predicates:
- `Test-CoworkerIgnoredFile` — Filters dot-paths and `.gitkeep` placeholders
- `Test-CoworkerPendingFile` — Non-ignored file check
- `Test-CoworkerActionableDraftRefinementFile` — Non-empty, non-ignored file for refinement
- `New-CoworkerFileWatcher` / `Remove-CoworkerFileWatcher` — Create/teardown `FileSystemWatcher` with registered events
- `Ensure-CoworkerDirectory` — Idempotent directory creation

## Worker Scripts

### Agent Infrastructure

| Script | Role |
|--------|------|
| `agent.ps1` | Low-level agent process launcher. Detects backend (`claude`, `kimi`, or `copilot`), builds command lines, resolves `.cmd` wrappers on Windows, handles large-prompt stdin piping, and starts processes with output redirection. |
| `agent-reliability.ps1` | Production wrapper: `Invoke-AgentWithRetry` with per-invocation timeout, exponential backoff retry (10s/30s/90s), and structured output extraction via `<OUTPUT>...</OUTPUT>` delimiters. All worker scripts should use this instead of calling `Invoke-Agent` directly. |
| `workflow.ps1` | Task pipeline utilities: `Ensure-DraftPlaceholders` (1.md–5.md in 0draft), `Resolve-UniquePath`, `Get-TaskBaseName` (AI-driven kebab-case naming), `New-AgentPromptArguments` / `Format-AgentPromptCommand`. |
| `task-logger.ps1` | `Write-ConsoleLine` (color-aware, handles redirected output), `Write-LogMessage` (INFO/WARN/ERROR with timestamps), `Write-LogVerbose` (DEBUG-level, file-only). |
| `prompt-utils.ps1` | Shared prompt construction: `New-AgentSystemPrompt`, `Add-ConstraintsBlock`, `Add-FileWriteInstruction`, `New-MemoryGenerationPrompt`, `New-RefinementPrompt`. Includes memory specification schemas for Daily/Monthly/Yearly/Global levels. |

### Task Execution

| Script | Role |
|--------|------|
| `git-sync.ps1` | Invokes the agent to commit all changes, pull from remote, and push. Used by `coworker.ps1` after moving approved tasks to `6git-pushed`. |
| `rename.ps1` | AI-driven rename: generates a descriptive kebab-case filename for a task file based on its content. Called by `coworker.ps1` before execution. |
| `writer.ps1` | File writer utility invoked by the agent for file output operations. |
| `refine-last-draft.ps1` | Convenience script to refine the most recently modified draft. |

### Memory System

| Script | Role |
|--------|------|
| `coworker-memory-generator.ps1` | Generates memory summaries at Daily, Monthly, Yearly, and Global levels from task logs. Supports `-Force` and `-DryRun`. |
| `coworker-memory-context.ps1` | Wraps the memory generator for task prompt injection. Outputs JSON (`context` + `instructions`) on stdout; all diagnostics go to stderr. |
| `coworker-daily-memory-generator.ps1` | Orchestrates daily memory generation: invokes the memory generator and writes daily summaries. |

### Draft Refinement

| Script | Role |
|--------|------|
| `refine-drafts.ps1` | AI-powered draft refinement. Moves files through `1ready → 2working → 3done` with orphan recovery, retry with sidecar tracking, output validation (conversation prefix detection, length sanity, header preservation), and dead-letter routing after max retries. Supports `-Audience`, `-DomainContext`, `-MaxRetries`, `-TimeoutSeconds`, `-DryRun`. |

### GitHub Issues Pipeline

| Script | Role                                                                                                                                                                                                                                                                                                             |
|--------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `fetch-github-issues.ps1` | Fetches recent open issues from `platonai/Browser4` via `gh issue list`, saves each as markdown in `main/0draft/issues/github/`, self-assigns unassigned issues (capped at 5/run), detects closed/deleted issues to update or remove local copies. Persists `.fetch-state.json`.                                 |
| `triage-github-issues.ps1` | Evaluates fetched issues for AI execution suitability via agent. Assesses relevance (to Browser4) and risk (of AI fix). Approved issues (high relevance + low risk) are moved to `main/1ready`. Persists `.triage-state.json` to avoid re-evaluation. `-MaxPerRun` (default 5), `-TimeoutSeconds` (default 120). |
| `refine-github-issues.ps1` | Extracts individual GitHub issues from natural-language draft files. Uses `<!-- COWORKER_ISSUE_BOUNDARY -->` markers, parses title/body/labels/assignees/repo, and routes output based on `#auto-approve` (to `github/commit/ready` or `draft/refine/2done`).                                                    |
| `commit-github-issues.ps1` | Creates GitHub issues via `gh issue create` from formatted markdown files in `issues/github/commit/ready`. Enforces a daily commit cap (20/day) to avoid GitHub spam detection. Moves successful creations to `done/`, failures to `failed/`.                                                                    |

### Maintenance

| Script | Role |
|--------|------|
| `organize-task-files.ps1` | Recursively scans task directories. When a directory has >10 files, moves them into `YYYY/MMDD/` subdirectories based on each file's last git commit timestamp (falling back to file creation time). Skips directories already in date-pattern format. Idempotent. |
| `update-readmes.ps1` | Multi-signal README staleness detector. Scores every tracked `README.md` (0–100) across four dimensions: git change recency (40%), content quality (30%), version consistency (20%), directory coverage (10%). With `-Update`, creates coworker task files in `1ready` for stale READMEs. |

### Utilities

| Script | Role |
|--------|------|
| `browser4-eval-prompt.ps1` | Browser4 evaluation prompt helper. |
| `count-total-token-usage.ps1` | PowerShell wrapper for token counting. |
| `count-total-token-usage.py` | Python script that parses agent log files and computes total token usage across runs. |

## Tests

The `tests/` directory contains Pester tests:

```powershell
Invoke-Pester .\coworker\scripts\tests\
```

| File | Description |
|------|-------------|
| `coworker.tests.ps1` | Comprehensive Pester tests for `coworker.ps1` — covers task processing, file renaming, pipeline transitions, memory integration, and error handling. |
| `coworker-scripts.ps1` | Integration tests for worker scripts and common modules — covers mutex acquisition, path resolution, file filtering, and logging utilities. |
| `test-utils.psm1` | Shared test utility module — mock setup, helper functions, and common test fixtures used by both test files. |

Individual workers may also have inline `.tests.ps1` files (e.g., `coworker-daily-memory-generator.tests.ps1`).

## Backend Selection

The agent backend is configured in `config.psd1`. When several keys are defined, priority is `CLAUDE` > `KIMI` > `COPILOT` (default: `@('gh', 'copilot')`).

```powershell
# config.psd1 — Claude backend example
CLAUDE = @(
    'claude'
    '--dangerously-skip-permissions'
    '--verbose'
)

# config.psd1 — Kimi backend example. kimi -p runs non-interactively with
# auto permission, so no permission flags are needed; do NOT add
# --yolo/--auto (they conflict with -p).
KIMI = @(
    'kimi'
)
```

When the Claude or Kimi backend is active, copilot-specific flags (`--allow-all-tools`, `--allow-all-paths`) are automatically filtered from the argument list.

## Running Tasks Directly

### Single task execution

```powershell
# Pass a specific task file to the runner
.\coworker\scripts\coworker.ps1 .\coworker\tasks\main\0draft\my-task.md
```

### Backend invocation

```powershell
# Invoke the agent directly (for testing/debugging)
. .\coworker\scripts\workers\agent.ps1
Invoke-Agent -Prompt "Summarize the README" -CaptureOutput

# With retry safety
. .\coworker\scripts\workers\agent-reliability.ps1
Invoke-AgentWithRetry -Prompt "Summarize the README" -CaptureOutput -TimeoutSeconds 60
```

## Design Principles

1. **Script-level mutexes** — Every script that should not run concurrently uses `New-CoworkerScriptLock -SkipIfHeld` with file-based locks under `tasks/.locks/`. Stale locks (dead PIDs) are automatically cleaned.

2. **Backend agnosticism** — Worker scripts use `Invoke-AgentWithRetry` which delegates to `agent.ps1`. The backend (claude, kimi, or copilot) is resolved from config at invocation time.

3. **Orphan recovery** — Long-running workers (`refine-drafts.ps1`, `refine-github-issues.ps1`) detect files stuck in the working directory (older than 30 minutes) and either return them to ready for retry or dead-letter them after max retries.

4. **Rate limiting** — `commit-github-issues.ps1` caps at 20 creations/UTC day. `fetch-github-issues.ps1` caps self-assignment at 5/run. Both persist state to disk for continuity across restarts.

5. **Structured output** — Agent invocations via `agent-reliability.ps1` wrap prompts with `<OUTPUT>...</OUTPUT>` delimiter instructions so output can be reliably extracted from conversational framing.

6. **UTF-8 everywhere** — `Util.ps1` forces UTF-8 encoding for console I/O and file operations to ensure cross-platform correctness.
