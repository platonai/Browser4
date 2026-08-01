Title: fix `b4w coworker commit`, message generation
Description: Task drafted via coworker CLI.
Prompt: PS D:\workspace\Browser4\Browser4-4.12> ./b4w.ps1 coworker commit
Target repo: D:\workspace\Browser4\Browser4-4.12\coworker\
Generating commit message via AI agent...
Commit message:
────────────────
Here's the conventional commit message for these staged changes:

```
fix(coworker): prevent empty drafts and use named args in b4w delegation

In b4w.ps1, pass coworker args as explicit -Command and -Remaining
named parameters instead of positional splatting, preventing PowerShell
from misbinding the first arg to other parameters.

In coworker.ps1's Invoke-Draft, remove the unconditional
Ensure-DraftPlaceholders call and add an early return when both title
and content are empty (unless -Edit is set), so empty drafts are no
longer created.

Co-Authored-By: Builtin Coworker
────────────────
Committed successfully.
[4.12.x a11af61d0] Here's the conventional commit message for these staged changes:
 2 files changed, 13 insertions(+), 3 deletions(-)

=====
the above is a bad message.
issues: 

1. the introduce message "Here's the conventional ..." is not needed
