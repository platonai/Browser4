# Coworker Task Drafting Area

Draft your tasks here. This area is for tasks under preparation and not yet ready for execution.

1. run `coworker-scheduler.ps1` to start recurring automation
2. draft tasks in `0draft` (or anywhere)
3. copy ready tasks to `1created` for execution
4. once executed, you can find results in `3_1complete` and detailed logs in `300logs`
5. review results if needed
6. move task file from `3_1complete` to `5approved` to trigger git pushing

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

## Examples

- [Simple Example - refine-coworker-readme.md.md](../6git-pushed/2026/0228/refine-coworker-readme.md.md)
- [Advanced Example - implement-daily-memory-batching.md](../6git-pushed/2026/0228/implement-daily-memory-batching.md)

## References

- [coworker.ps1](../../scripts/coworker.ps1)
- [coworker.sh](../../scripts/coworker.sh)
- [coworker-scheduler.ps1](../../scripts/coworker-scheduler.ps1)
- [coworker-scheduler.config.psd1](../../scripts/coworker-scheduler.config.psd1)
