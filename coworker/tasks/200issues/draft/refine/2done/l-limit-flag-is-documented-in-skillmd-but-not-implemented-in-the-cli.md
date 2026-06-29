# `-l` / `--limit` flag is documented in SKILL.md but not implemented in the CLI

The `snapshot` command's `--limit` (`-l`) flag is prominently documented in SKILL.md as a way to limit snapshot output to N nodes, but it does not exist in the actual CLI.

**Steps to reproduce:**
1. `browser4-cli snapshot -l 200`
2. Observe the error output

**Expected behavior:** The `--limit` flag is recognized and limits snapshot output to the specified number of nodes.

**Actual behavior:** The CLI rejects the flag entirely:
```
too many arguments: expected 0, received 2
```
Running `browser4-cli help snapshot` confirms the flag is absent from the supported options.

**Suggested resolution:** Either implement `--limit` in the CLI, or remove it from SKILL.md. If removing, document the gap and guide users to `-d` (depth) as the alternative for controlling snapshot output size.

Labels: bug, documentation

