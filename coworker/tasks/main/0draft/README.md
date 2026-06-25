# Coworker Task Drafting Area

Draft your tasks here. This area is for tasks under preparation and not yet ready for execution.

1. run `coworker-scheduler.ps1` to start recurring automation
2. draft tasks in `0draft` (or anywhere)
3. copy ready tasks to `1ready` for execution
4. once executed, you can find results in `main/3done` and detailed logs in `coworker/tasks/300logs`
5. review results if needed
6. move task file from `main/3done` to `main/5approved` to trigger git pushing

Any prompt is OK to describe a task, but the following template is recommended for clarity:

```markdown
# Task Title

## Problem
Clearly describe the issue or task. Be specific and concise.

## Solution
Outline your proposed approach, including steps, methods, or techniques.

## References
List relevant resources, documentation, or helpful links.
```

## References

- [coworker.ps1](../../../scripts/coworker.ps1)
- [coworker-scheduler.ps1](../../../scripts/coworker-scheduler.ps1)
- [coworker-scheduler.config.psd1](../../../scripts/coworker-scheduler.config.psd1)
- [refine-github-issues.ps1](../../../scripts/workers/refine-github-issues.ps1)
- [commit-github-issues.ps1](../../../scripts/workers/commit-github-issues.ps1)
