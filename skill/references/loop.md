---
title: "Loop Command Reference"
description: "Reference for the loop command. Execute a task repeatedly on a configurable interval with persistence and resume support."
tier: procedure
---

# Loop Command Reference

Execute a task repeatedly on a configurable interval. Supports plain text commands
(auto-detected as X-SQL by the server), shell commands, and nested `browser4-cli`
subcommands. Progress is persisted to disk so loops survive process restarts.

## Quick start

```bash
# Plain text command every hour (default interval)
browser4-cli loop "load https://example.com and extract the page title"

# Shell command every 60 seconds, 10 iterations
browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60 -n 10

# Run eval every 5 minutes
browser4-cli loop -- eval "document.title" -i 300
```

## When to Use

Use **loop** for repeated task execution at fixed intervals — monitoring, health checks, scheduled data collection. Prefer **crawl** for one-time bulk multi-page extraction. Prefer **swarm** for parallel high-throughput scraping. Use **cron** (system scheduler) for tasks that don't need browser automation.

## How It Works

Loop executes a task on a fixed interval, persists progress to `~/.browser4/loop-state.json` after each iteration, and resumes from the last completed iteration on restart. Interval is measured from start-to-start of iterations. Ctrl+C during execution saves progress before exit.

## Modes

### Plain text (default)

The task string is submitted to the Browser4 server via the `command_run` MCP tool.
The server auto-detects the command type:

- **Natural language**: e.g., `"load https://example.com and extract the page title"`
- **X-SQL**: e.g., `"select dom.title from load_and_select('https://example.com')"`

```bash
browser4-cli loop "select dom.title, dom.url from load_and_select('https://news.ycombinator.com')" --count 5
```

### Shell (`--shell`)

The task is executed via the OS shell. Uses `cmd /C` on Windows, `sh -c` on Unix.

```bash
browser4-cli loop --shell "curl -s https://api.example.com/health | jq .status" -i 60
```

Quote the entire shell command to avoid tokenization issues with pipes, redirects, and spaces.

### Subcommand (`--`)

Everything after `--` is passed as arguments to a nested `browser4-cli` process.
Uses the current binary path so the same version is always invoked.

```bash
browser4-cli loop -- eval "document.title" -i 300
browser4-cli loop -- snapshot -i 600
browser4-cli loop -- screenshot --full-page -i 1800
```

## Flags

| Flag | Short | Type | Default | Description |
|---|---|---|---|---|
| `--interval` | `-i` | u64 | `3600` (1 hour) | Seconds between iterations |
| `--count` | `-n` | u64 | infinite | Maximum number of iterations |
| `--timeout` | `-t` | u64 | `604800` (1 week) | Maximum total duration in seconds |
| `--name` | | string | `"default"` | Loop name for persistence. Named loops are stored in `~/.browser4/loops/<name>.json`. Only letters, digits, dots, hyphens, and underscores are allowed. |
| `--shell` | | bool | — | Execute task as a shell command |
| `--pause` | | bool | — | Pause a running loop (control op), or start a new loop in paused state (when combined with a task) |
| `--resume` | | bool | — | Resume a paused loop (control op, no task allowed) |
| `--stop` | | bool | — | Stop a running/paused loop and clear persisted state |
| `--status` | | bool | — | Show loop state and progress |
| `--list` | | bool | — | List all persisted loops |
| `--pause-all` | | bool | — | Pause all running loops at once |
| `--resume-all` | | bool | — | Resume all paused loops at once |
| `--stop-all` | | bool | — | Stop and clear all persisted loops at once |

### Named loops

Use `--name` to run multiple independent loops concurrently. Each named loop
has its own state file under `~/.browser4/loops/<name>.json`. The default
loop (no `--name`) uses `~/.browser4/loop-state.json`.

```bash
# Start a named health-check loop
browser4-cli loop --name health --shell "curl -s https://api.example.com/health" -i 300

# Start a second loop independently
browser4-cli loop --name monitor -- eval "document.title" -i 600

# List all loops
browser4-cli loop --list

# Pause/resume/stop a specific loop
browser4-cli loop --pause --name health
browser4-cli loop --resume --name health
browser4-cli loop --stop --name health
```

### Start paused

Combine `--pause` with a task to create a loop that is persisted but does not
start executing immediately. Use `--resume` (control op) followed by the same
command to begin execution.

```bash
# Create a loop in paused state
browser4-cli loop --pause --shell "echo hi" -i 60 --name demo

# Output:
# Loop: "echo hi" — every 60s
#   Mode: shell command
# ⏸  Created as paused. Use `browser4-cli loop --resume --name demo` to start.

# Resume the paused loop
browser4-cli loop --resume --name demo

# Run the same command to actually start executing
browser4-cli loop --shell "echo hi" -i 60 --name demo
# Resuming loop: "echo hi" from iteration 1
#   Loop was paused — resuming now.
```

### Normal completion

When a loop finishes normally (count reached or timeout expired), the
persisted state is automatically cleared.  Use `--stop` to clear state
before normal completion if needed.

```bash
browser4-cli loop --shell "echo hi" --count 2
# ... runs 2 iterations ...
# ✓  Loop finished — 2 iteration(s) completed.
# (state file is automatically removed)
```

## Persistence and resume

### State file

Loop progress is persisted to `~/.browser4/loop-state.json` (default loop) or
`~/.browser4/loops/<name>.json` (named loops) after each iteration:

```json
{
  "taskTokens": ["echo", "hello"],
  "mode": "shell",
  "intervalSecs": 3600,
  "count": 10,
  "timeoutSecs": 604800,
  "iterationsCompleted": 3,
  "startedAt": "2026-06-27T10:00:00+00:00",
  "updatedAt": "2026-06-27T13:00:05+00:00",
  "status": "running"
}
```

### Resume after interruption

If the process is interrupted (Ctrl+C, system shutdown, terminal closed):

1. Progress is saved automatically before exit.
2. Run the **same command** again to resume from the next iteration.
3. The original `startedAt` timestamp is preserved for timeout calculation.

```bash
# First run — interrupted after 3 iterations
browser4-cli loop --shell "echo hello" -n 10 -i 5
# ... Ctrl+C after 3 iterations ...

# Resume — starts at iteration 4
browser4-cli loop --shell "echo hello" -n 10 -i 5
# Resuming loop: "echo hello" from iteration 4
```

### Stop a loop

Use `--stop` to clear the persisted state and prevent auto-resume:

```bash
browser4-cli loop --stop
# Loop stopped. 3 iteration(s) were completed. State cleared.
```

### Inspect loop state

Use `--status` to view the current loop without executing:

```bash
browser4-cli loop --status
# ▶ Loop state: running
#   Task: echo hello
#   Mode: shell
#   Interval: 3600s
#   Count: 10
#   Timeout: 604800s
#   Iterations completed: 3
#   Started at: 2026-06-27T10:00:00+00:00
#   Updated at: 2026-06-27T13:00:05+00:00
```

## Output

### Human-readable output

Each iteration prints a header with the iteration number and UTC timestamp:

```
Loop: "echo hello" — every 3600s, up to 10 iterations or 604800s
  Mode: shell command

--- Iteration 1 [2026-06-27T10:00:00.000Z] ---
hello

--- Iteration 2 [2026-06-27T11:00:01.002Z] ---
hello

========================================
Loop finished. 2 iteration(s) completed.
```

### JSON output (`--json`)

When the global `--json` flag is used before the command:

```bash
browser4-cli --json loop --shell "echo hello" --count 2
```

```json
{
  "command": "loop",
  "status": "ok",
  "output": {
    "iterations": [
      {"iteration": 1, "timestamp": "2026-06-27T10:00:00.000Z", "ok": true, "output": "hello"},
      {"iteration": 2, "timestamp": "2026-06-27T11:00:01.002Z", "ok": true, "output": "hello"}
    ],
    "total_iterations": 2
  }
}
```

Failed iterations record the error and continue:

```json
{"iteration": 3, "timestamp": "...", "ok": false, "error": "Shell command exited with exit code: 1: ..."}
```

## How it works

### Execution loop

1. Parse arguments and determine mode (plain/shell/subcommand).
2. Check for existing persisted state (resume if matching task found).
3. Persist initial state.
4. For each iteration:
   - Check `--stop` signal in the persisted state file.
   - Check `--count` limit.
   - Check `--timeout` limit (capped from original `startedAt`).
   - Execute the task in the determined mode.
   - Persist updated progress.
   - Sleep for `interval - execution_time` (capped by remaining timeout budget).
   - Break on Ctrl+C (progress saved before exit).
5. On normal completion: clear persisted state, print summary.

### Interval semantics

The interval is measured from the **start** of one iteration to the start of the next.
If an iteration takes longer than the interval, the next iteration starts immediately
(no additional delay). This maintains a consistent pacing cadence.

### Timeout semantics

The timeout is checked at the **top** of each iteration. A long-running iteration may
exceed the configured timeout — the loop will not abort mid-execution. The sleep
between iterations is capped so the loop wakes up in time to honour the timeout.

### Ctrl+C handling

Ctrl+C is handled at every phase of the loop:

- **During execution**: A `tokio::select!` races the task against Ctrl+C. If
  Ctrl+C arrives mid-execution, progress is persisted from the last
  *completed* iteration so the loop can be resumed.
- **During sleep**: A polling loop checks for Ctrl+C every 2 seconds. Progress
  is persisted and the loop exits cleanly.
- **While paused**: Ctrl+C exits without changing state (the loop remains paused).

In all cases the state file is updated so running the same command again
resumes from the last completed iteration.

## Timeout

Set via `--timeout` / `-t`. Default: 604800 seconds (1 week).

```bash
browser4-cli loop --shell "echo hi" -t 3600  # Stop after 1 hour
```

## Error handling

- Errors during an iteration are logged to stderr and the loop **continues**.
- The JSON output records both successful and failed iterations.
- `--shell` and `--` are mutually exclusive — passing both produces a usage error.
- An empty task produces a usage error.
- Non-numeric values for `--interval`, `--count`, or `--timeout` produce a usage error.
- If a different task is persisted when starting a new loop, a warning is printed and a fresh loop starts.
