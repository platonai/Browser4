---
name: coworker-skills
title: "Coworker Skills — Decision-tree router for the Coworker skill family"
description: "Routes to the correct Coworker skill (run-tests, maintenance, organize-task-files, task-token-usage, coworker). Use when unsure which coworker skill to invoke, or when you need an overview of available task-pipeline and quality tools."
---

# Coworker Skills — Decision Tree

> **Project-level only.** These skills reference `./bin/`, `./coworker/scripts/`,
> and other Browser4 repo paths. They only work when the current working directory
> is the Browser4 repository root. Do not install them as user-level (global) skills.

Which skill should you invoke? Start at the top and follow the branch that
matches what you want to do.

```
                          ┌─────────────────────────────────┐
                          │ What are you trying to do?      │
                          └─────────────────────────────────┘
                              │
              ┌───────────────┼──────────────────────────────┐
              ▼               ▼                              ▼
     "Run / verify       "Manage the                  "Analyze or
      code quality"       task pipeline"               investigate
              │               │                         past runs"
              │               │                              │
   ┌──────────┴──────────┐    │              ┌───────────────┴───────────────┐
   ▼                     ▼    ▼              ▼                               ▼
┌──────────┐       ┌───────────┐    ┌──────────────────┐      ┌──────────────────────┐
│ "Run     │       │ "Audit    │    │ "List/move/find  │      │ "How many tokens did  │
│ tests"   │       │ quality"  │    │ task files"      │      │ this task consume?"   │
└──────────┘       └───────────┘    └──────────────────┘      └──────────────────────┘
    │                   │                 │                           │
    ▼                   ▼                 ▼                           ▼
┌──────────┐       ┌──────────┐    ┌────────────────┐        ┌──────────────────┐
│run-tests │       │maintenance│    │organize-task-  │        │task-token-usage  │
│          │       │          │    │files           │        │                  │
└──────────┘       └──────────┘    └────────────────┘        └──────────────────┘
```

## Skill summaries

> Click the skill name to jump to its full documentation.

| Skill | Invoke when… |
|---|---|
| **[run-tests](run-tests/SKILL.md)** | You need to run, verify, or check any Browser4 test suite — unit, integration, E2E, CLI, PowerShell, real-world scenarios, or production acceptance. |
| **[maintenance](maintenance/SKILL.md)** | You need to run CI checks, audit documentation links, validate SKILL frontmatter, detect dead code, check coverage, scan for dependency vulns, clean build artifacts, or add a new maintenance check. |
| **[organize-task-files](organize-task-files/SKILL.md)** | You need to list, pair, deduplicate, or move `.full.md` / `.issues.md` files between Coworker pipeline stages (0draft → 6git-pushed). |
| **[task-token-usage](task-token-usage/SKILL.md)** | You need to see token consumption per task — session counts, input/output/cache tokens, or compare costs across task runs. |
| **[coworker](coworker/SKILL.md)** | You need to understand the Coworker file-queue system itself — its scripts, the task state machine, pipeline directories, scheduler, or worker lifecycle. This is the **reference** skill; the others are the **action** skills. |

## Common choreographies

Some real-world tasks need more than one skill. These are the typical chains:

### "A task ran and something went wrong"

```
task-token-usage  →  check if it actually ran and how much it cost
   │
   ▼
organize-task-files  →  find where the task file ended up in the pipeline
   │
   ▼
coworker/tasks/300logs  →  read the execution log for error details
```

### "Run tests and make sure everything is clean before pushing"

```
run-tests  →  execute the relevant test suites
   │
   ▼ (all green)
maintenance  →  run CI-level checks (compilation, dead code, docs, SKILLs)
```

### "Audit the task pipeline and clean up stale files"

```
organize-task-files summary  →  overview of all pipeline stages
organize-task-files dupes    →  find duplicate task names
organize-task-files empty    →  find .issues.md files with zero issues
organize-task-files move     →  move empty/stale files out of the pipeline
```

### "Investigate token spend across all recent evaluations"

```
task-token-usage --recent 24  →  summary of last 24 hours
   │
   ▼ (spot a task with high spend)
task-token-usage --recent 24 --detail  →  per-session breakdown for that task
```
