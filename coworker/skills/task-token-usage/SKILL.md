---
name: task-token-usage
title: "Task Token Usage — List tasks with their token consumption"
description: "Analyzes Claude Code session traces (JSONL) and draft task files to report token usage per task. Use when asked about token usage, session costs, or task efficiency metrics."
allowed-tools: Bash(python3:*)
---

# Task Token Usage

Analyzes Claude Code conversation traces to report token consumption per task.

## When to Use

- "list tasks with token usage"
- "show me token usage for each task"
- "how many tokens did task X use?"
- "what tasks ran in the last N hours?"
- "compare token usage across tasks"

## How It Works

1. Scans `~/.claude/projects/<project>/` for JSONL session traces
2. Extracts `input_tokens`, `output_tokens`, `cache_read_input_tokens` from each assistant message
3. Optionally matches sessions to draft task files in `coworker/tasks/issues/draft/`
4. Prints a summary table grouped by task

## Usage

Run the bundled script:

```bash
python3 coworker/skills/task-token-usage/scripts/list-tasks.py [OPTIONS]
```

### Options

| Flag | Description |
|------|-------------|
| `--recent N` | Only show sessions from last N hours (default: 24) |
| `-d, --detail` | Per-session breakdown instead of grouped summary |
| `-a, --all` | Show all sessions (no time filter) |

### Examples

```bash
# Summary of last 12 hours
python3 coworker/skills/task-token-usage/scripts/list-tasks.py --recent 12

# Detailed per-session view for all time
python3 coworker/skills/task-token-usage/scripts/list-tasks.py --all --detail

# Default: last 24 hours, grouped summary
python3 coworker/skills/task-token-usage/scripts/list-tasks.py
```

## Output Columns (Summary Mode)

| Column | Description |
|--------|-------------|
| Task | Task name (matched from draft files or first step description) |
| Runs | Number of evaluation sessions for this task |
| In Tok | Total input tokens across all runs |
| Out Tok | Total output tokens across all runs |
| Cache | Total cache-read + cache-creation tokens |
| TOTAL | Sum of all tokens |
| Avg/run | Average total tokens per run |

## Output Columns (Detail Mode)

| Column | Description |
|--------|-------------|
| Time | When the session ended |
| Task | Matched task name |
| In | Input tokens |
| Out | Output tokens |
| CacheR | Cache-read tokens |
| TOTAL | Total tokens |
| Model | AI model used |

## Token Pricing (Approximate)

The script does not compute cost, but you can estimate:

| Model | Input (per 1M) | Output (per 1M) | Cached (per 1M) |
|-------|----------------|-----------------|-----------------|
| claude-sonnet-4.6 | $3.00 | $15.00 | $0.30 |
| claude-opus-4 | $15.00 | $75.00 | $1.50 |
| claude-haiku-4.5 | $1.00 | $5.00 | $0.10 |

Cache tokens account for ~96% of total usage in typical evaluation sessions.
