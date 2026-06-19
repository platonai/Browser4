# Agent Scenario Tests

PowerShell scripts that run browser4-cli usability evaluations through an LLM
agent. Each script defines a real-world task and asks the agent to complete it
while simultaneously evaluating the CLI's discoverability, documentation, and
reliability from a first-time user's perspective.

## Quick start (standalone)

```powershell
# From the repo root:
./cli/browser4-cli/tests/scripts/search-summary.ps1
```

Each script builds a prompt by combining the shared evaluation template
(`common.ps1`) with a task-specific prompt, then invokes `claude`.

## Anatomy of a scenario script

```powershell
#!/usr/bin/env pwsh
. "$PSScriptRoot/common.ps1"           # 1. dot-source shared helpers

$taskPrompt = @"                        # 2. define the task
1. Go to https://example.com
2. Search for: something
3. Summarize the results.
"@

$prompt = $generalPrompt + $taskPrompt  # 3. concatenate
Invoke-Agent -Prompt $prompt            # 4. invoke the agent
```

`common.ps1` provides two things:

| Symbol | Purpose |
|--------|---------|
| `$generalPrompt` | The shared usability-evaluation template (prepended to every task) |
| `Invoke-Agent` | Centralized agent invocation (`claude --dangerously-skip-permissions`) |

## Running every scenario at once

`test-runner.ps1` auto-discovers every `.ps1` in this directory (excluding
`common.ps1` and itself) and runs them sequentially:

```powershell
# Run everything:
./cli/browser4-cli/tests/scripts/test-runner.ps1

# List discovered scripts:
./cli/browser4-cli/tests/scripts/test-runner.ps1 -List

# Run a subset:
./cli/browser4-cli/tests/scripts/test-runner.ps1 search-summary.ps1 amazon.ps1

# Stop on first failure:
./cli/browser4-cli/tests/scripts/test-runner.ps1 -FailFast

# Launch each scenario in its own window:
./cli/browser4-cli/tests/scripts/test-runner.ps1 -NewWindow
```

New scripts placed in this directory are picked up automatically — no
registration step is needed.

## Adding a new scenario

1. Copy an existing script, e.g. `cp hacker-news.ps1 my-scenario.ps1`
2. Replace the `$taskPrompt` content with your task instructions
3. Save and run — `test-runner.ps1` discovers it automatically

## Available scenarios

| Script | Task |
|--------|------|
| `search-summary.ps1` | Search Baidu for 武汉龙虾节, summarize findings |
| `amazon.ps1` | Search Amazon for whiteboard pens, compare top 4 |
| `hacker-news.ps1` | Navigate HN, open and summarize top 3 posts |
| `form-filling.ps1` | Fill a local HTML form using batch mode with CSS selectors |

## Production copies

The scripts in `browser4-tests/agent-scenarios/` are thin wrappers that
dot-source the corresponding scripts here. Keep the canonical versions in this
directory; update the production wrappers only if they need different behavior.
