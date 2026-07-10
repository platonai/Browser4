---
name: organize-task-files
title: "Organize Task Files — Manage files across Coworker task pipelines"
description: "Lists, pairs, deduplicates, and moves task files across the Coworker task state machine (0draft → 6git-pushed). Use when asked to organize, clean up, or audit task files."
allowed-tools: Bash(python3:*)
---

# Organize Task Files

Manages `.full.md` (evaluation outputs) and `.issues.md` (extracted issues) files across the Coworker task state machine pipelines.

## When to Use

- "organize task files"
- "show me what tasks are in each pipeline stage"
- "find unpaired .full.md and .issues.md files"
- "find duplicate task files"
- "find empty issues files (zero issues found)"
- "move task files from X to Y"
- "clean up the issues draft directory"

## How It Works

1. Scans `coworker/tasks/` (issues, main) for `.md` task files
2. Parses filenames with pattern `YYYYMMDD-HHMMSS-taskname.{full,issues}.md` or plain `taskname.md`
3. Groups by task name, detects pairs/dupes/orphans, classifies by pipeline stage

## Usage

Run the bundled script:

```bash
python3 coworker/skills/organize-task-files/scripts/organize.py COMMAND [OPTIONS]
```

### Commands

| Command | Description |
|---------|-------------|
| `summary` | Overview of all pipelines: file counts per stage, type breakdown |
| `list` | List all task files with full paths, grouped by pipeline stage |
| `pairs` | Match .full.md ↔ .issues.md pairs; flag orphans |
| `dupes` | Find duplicate task names across directories and stages |
| `empty` | Find .issues.md files with "0 issues found" |
| `move` | Move matching files between pipeline stages (dry-run by default) |

### Options

| Flag | Description |
|------|-------------|
| `--dir DIR` | Only scan a specific directory (default: all task dirs) |
| `--dry-run` | Show what would happen without making changes (default for `move`) |
| `--force` | Actually perform moves (use with `move`) |
| `--json` | Output as JSON for scripting |

### Examples

```bash
# Overview of all pipelines
python3 coworker/skills/organize-task-files/scripts/organize.py summary

# Find unpaired files in issues draft
python3 coworker/skills/organize-task-files/scripts/organize.py pairs --dir issues/draft

# List all duplicate task names
python3 coworker/skills/organize-task-files/scripts/organize.py dupes

# Find empty .issues.md files (zero issues)
python3 coworker/skills/organize-task-files/scripts/organize.py empty

# Preview moving empty issues files to a discard directory
python3 coworker/skills/organize-task-files/scripts/organize.py move --dir issues/draft --filter empty --target coworker/tasks/issues/review/done/discard

# Actually perform the move
python3 coworker/skills/organize-task-files/scripts/organize.py move --dir issues/draft --filter empty --target coworker/tasks/issues/review/done/discard --force
```

## Pipeline Stages (for reference)

```
0draft → 1ready → 2working → 3done → 4review → 5approved → 6git-pushed
```

Plus parallel pipelines:
- `issues/draft/refine/` (0ready → 1working → 2done → 0error)
- `issues/github/commit/` (ready → done → failed)
- `issues/review/` and `issues/review/done/`
- `issues/local/done/` and `issues/local/wontfix/`
