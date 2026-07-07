---

## Evaluation Summary

**Task:** Navigate Wikipedia, capture 6 snapshot variants, click a link with auto-diff, exercise all snapshot grep modes, and output to stdout — **all 10 steps completed.**

**Key finding:** The `-s` short flag is fatally ambiguous. When a user runs `snapshot -s "#content"`, the global arg parser silently steals `-s` as `--session`, producing a baffling "No active session" error right after a successful `goto`. The `--selector` long form works correctly. This alone would block a new user and is the highest-priority fix.

**8 issues documented** across 7 categories, with root causes, code pointers, and concrete fix suggestions — all ready for human review with the checkbox workflow.
