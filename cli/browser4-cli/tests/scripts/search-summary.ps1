#!/usr/bin/env pwsh

$generalPrompt = @"
You are evaluating the usability, discoverability, and reliability of browser4-cli while completing a real-world task.

## Preparation

Before performing any browser interaction:

1. Run `cargo run -- help`.
2. Read `cli/skill/SKILL.md` completely.
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

"

$taskPrompt = @"

1. Open https://www.baidu.com
2. Search for: 武汉龙虾节
3. Read multiple relevant results.
4. Summarize:

   * What the Wuhan Lobster Festival (武汉龙虾节) is
   * Its history and background
   * Major activities
   * Typical schedule and venue
   * Its significance to local tourism, food culture, and economy
"@

$prompt = $generalPrompt + $taskPrompt

copilot --allow-all -p "$prompt" ## --silent
# gh copilot --allow-all -p "$prompt" ## --silent
claude --dangerously-skip-permissions -p $prompt
