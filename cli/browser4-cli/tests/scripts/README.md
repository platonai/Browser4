# Agent Scenario Tests

Markdown task files and PowerShell runners that evaluate browser4-cli usability
through an LLM agent. Each task defines a real-world scenario and asks the agent
to complete it while simultaneously evaluating the CLI's discoverability,
documentation, and reliability from a first-time user's perspective.

## Quick start (standalone)

```powershell
# From the repo root:
./cli/browser4-cli/tests/scripts/run-task.ps1 -TaskFile tasks/search-summary.md
```

Each task file describes the scenario in plain markdown. `run-task.ps1` reads the
file, combines it with the shared evaluation template (`common.ps1`), and invokes
`claude`.

## Anatomy of a task file

Task files live in `tasks/` and follow this format:

```markdown
# scenario-name

1. Go to https://example.com
2. Search for: something
3. Summarize the results.
```

The first `# Heading` becomes the scenario name; the body is the task prompt.

## Running every task at once

`run-tests.ps1` auto-discovers every `.md` in `tasks/` and runs them
sequentially:

```powershell
# Run everything:
./cli/browser4-cli/tests/scripts/run-tests.ps1

# List discovered tasks:
./cli/browser4-cli/tests/scripts/run-tests.ps1 -List

# Run a subset:
./cli/browser4-cli/tests/scripts/run-tests.ps1 search-summary amazon

# Stop on first failure:
./cli/browser4-cli/tests/scripts/run-tests.ps1 -FailFast
```

New task files placed in `tasks/` are picked up automatically — no
registration step is needed.

## Adding a new task

1. Create a new `.md` file in `tasks/`, e.g. `tasks/my-scenario.md`
2. Add a `# scenario-name` heading and the task instructions
3. Save and run — `run-tests.ps1` discovers it automatically

## Available tasks

| Task file | Scenario |
|-----------|----------|
| `tasks/search-summary.md` | Search Baidu for 武汉龙虾节, summarize findings |
| `tasks/amazon.md` | Search Amazon for whiteboard pens, compare top 4 |
| `tasks/hacker-news.md` | Navigate HN, open and summarize top 3 posts |
| `tasks/form-filling.md` | Fill a local HTML form using batch mode with CSS selectors |

## Script reference

| Script | Purpose |
|--------|---------|
| `common.ps1` | Shared evaluation prompt (`$generalPrompt`) and agent invocation (`Invoke-Agent`) |
| `run-task.ps1` | Single-task runner — reads a `.md` task file and invokes the agent |
| `run-tests.ps1` | Batch runner — discovers and runs all tasks in `tasks/` |
| `common.tests.ps1` | Unit tests for `common.ps1` |

## Production copies

The scripts in `browser4-tests/agent-scenarios/` are thin wrappers that
set `$browser4cliMode = 'production'` and call `run-task.ps1` with the
corresponding task file. Keep the canonical task files and runners in this
directory; update the production wrappers only if they need different behavior.
