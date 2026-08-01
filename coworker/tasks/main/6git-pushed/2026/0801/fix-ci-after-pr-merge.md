Title: Fix CI failures after PR merge into 4.12.x
Description: CI pipeline failed after merging 2 PR(s) into 4.12.x. Merged: #523. Resolved: #525.
Prompt: |
  The following PRs were just merged into $BaseBranch:
  - Direct merges: 523
  - Conflict-resolved merges: 525

  CI pipeline failed with exit code 1. Investigate the CI failures
  and fix them. Read the CI output above, identify the root cause(s), and
  apply fixes.

  CI workflow: ci.yml (ref: 4.12.x)

  #auto-approve
