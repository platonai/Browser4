#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Shared browser4-cli usability evaluation prompt for coworker worker scripts.

.DESCRIPTION
    Provides the standard evaluation prompt extracted from
    browser4-tests/real-world-scenarios/scripts/common.ps1 so that any coworker task can
    invoke a browser4-cli usability evaluation without depending on the test
    scripts directory.

    The prompt adapts to the environment automatically:

      - Dev (default):  `cargo run -- help`  + local `cli/skill/SKILL.md`.
      - Production:      `browser4-cli help` + `https://browser4.ioSKILL.md`.

    Set $browser4cliMode = 'production' BEFORE calling New-Browser4EvalPrompt
    to switch to production mode.

.EXAMPLE
    . "$PSScriptRoot/browser4-eval-prompt.ps1"

    $evalPrompt = New-Browser4EvalPrompt
    $fullPrompt = $evalPrompt + $taskPrompt

.EXAMPLE
    # Production mode
    $browser4cliMode = 'production'
    . "$PSScriptRoot/browser4-eval-prompt.ps1"
    $prompt = New-Browser4EvalPrompt
#>

# ── Backend detection (consistent with prompt-utils.ps1) ───────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$configPath = Join-Path (Split-Path -Parent $workerDir) 'config.ps1'
if (Test-Path $configPath) { . $configPath }

function Get-AgentBackend {
    if ($CLAUDE) { return 'claude' }
    return 'copilot'
}

# ── Shared evaluation prompt ────────────────────────────────────────────────

function New-Browser4EvalPrompt {
    <#
    .SYNOPSIS
        Returns the standard browser4-cli usability evaluation prompt.

    .DESCRIPTION
        Builds the evaluation prompt that instructs an agent to evaluate the
        usability, discoverability, and reliability of browser4-cli while
        completing a real-world task. The prompt includes:

          - Tool usage rules (use only browser4-cli, no external tools)
          - Evaluation categories (installation, discoverability, docs, CLI,
            task execution, reliability, UX)
          - Investigation guidelines
          - Deliverables format (Task Result, Execution Trace, Issues Found,
            Overall Assessment)

        The caller should prepend this to their task-specific prompt.

        The returned string ends with "# Task" followed by a blank line so the
        caller can append task-specific instructions directly.

    .PARAMETER Mode
        'dev' (default) uses `cargo run -- help` and local `cli/skill/SKILL.md`.
        'production' uses `browser4-cli help` and `https://browser4.ioSKILL.md`.

        When omitted, the caller may also set `$script:browser4cliMode` before
        dot-sourcing this module.

    .EXAMPLE
        $prompt = New-Browser4EvalPrompt
        $fullPrompt = $prompt + "Navigate to example.com and log in."

    .EXAMPLE
        $prompt = New-Browser4EvalPrompt -Mode 'production'
    #>
    param(
        [ValidateSet('dev', 'production')]
        [string]$Mode = ''
    )

    # Resolve mode: explicit parameter takes precedence, then script-level
    # variable (may be set by caller before dot-sourcing), then default.
    $effectiveMode = $Mode
    if (-not $effectiveMode) {
        if ($script:browser4cliMode -eq 'production') {
            $effectiveMode = 'production'
        } else {
            $effectiveMode = 'dev'
        }
    }

    if ($effectiveMode -eq 'production') {
        $helpCmd   = '`browser4-cli help`'
        $skillPath = 'https://browser4.ioSKILL.md'
    } else {
        $helpCmd   = '`cargo run -- help`'
        $skillPath = '`cli/skill/SKILL.md`'
    }

    return @"
You are evaluating the usability, discoverability, and reliability of browser4-cli while completing a real-world task.

## Preparation

Before performing any browser interaction:

1. Run $helpCmd.
2. Read $skillPath completely.
3. Learn the available commands, workflows, and conventions directly from the documentation.
4. Do not assume any prior knowledge of browser4-cli.

## Tool Usage Rules

* Use browser4-cli for ALL browser interactions.
* Do NOT use Playwright, Puppeteer, Selenium, CDP libraries, external browser APIs, or any other browser automation tool.
* If a browser action is required, first identify the documented browser4-cli command that should perform it.
* Prefer documented workflows over assumptions.
* If documentation is ambiguous, incomplete, inaccurate, outdated, or difficult to discover, record it as an issue.

## Evaluation Objective

Your goal is not only to complete the task, but also to evaluate the usability of browser4-cli from the perspective of a first-time user.

Actively look for issues involving:

### Installation & Setup

* Missing prerequisites
* Environment assumptions
* Setup complexity
* Platform-specific issues

### Discoverability

* Help output quality
* Command discoverability
* Missing examples
* Missing documentation

### Documentation

* Incomplete instructions
* Incorrect instructions
* Ambiguous wording
* Undocumented behavior
* Inconsistent terminology

### CLI Experience

* Command naming consistency
* Parameter naming consistency
* Workflow clarity
* Session management
* Browser lifecycle management
* State management

### Task Execution

* Navigation workflow
* Search workflow
* Content extraction workflow
* Form interaction workflow
* Waiting/synchronization behavior
* Error recovery

### Reliability

* Unexpected failures
* Flaky behavior
* Misleading outputs
* Poor error messages
* Silent failures

### User Experience

* Learnability
* Efficiency
* Cognitive load
* Friction points
* Missing shortcuts
* Missing quality-of-life features

## Investigation Guidelines

Whenever you encounter a problem:

1. Attempt to understand the root cause.
2. Determine whether it is:

   * Product issue
   * Documentation issue
   * UX issue
   * Reliability issue
   * Discoverability issue
3. Continue the task whenever reasonably possible.
4. Record all findings, even if a workaround exists.

## Deliverables

### A. Task Result

Provide the requested task outcome.

### B. Execution Trace

Summarize:

* Commands used
* Major steps performed
* Important decisions made
* Workarounds required

### C. Issues Found

For every issue discovered, provide:

#### Title

#### Severity

* Critical
* High
* Medium
* Low

#### Category

* Product
* Documentation
* UX
* Reliability
* Discoverability

#### Reproduction Steps

#### Expected Behavior

#### Actual Behavior

#### Suggested Improvement

### D. Overall Assessment

Include:

* Task completion status
* Estimated task success rate
* Number of issues found
* Major blockers
* Most confusing aspects
* Most valuable improvements
* Overall usability rating (1–10)

## Important

* Think like a new user who has never used browser4-cli before.
* Do not assume undocumented functionality exists.
* Prefer evidence gathered from actual usage over assumptions.
* Record both major and minor usability issues.
* The task is considered successful only if both the task itself and the usability evaluation are completed.

# Task

"@
}
