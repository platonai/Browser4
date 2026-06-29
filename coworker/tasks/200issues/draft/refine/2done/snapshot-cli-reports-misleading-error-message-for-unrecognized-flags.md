# Snapshot CLI reports misleading error message for unrecognized flags

When the `snapshot` command receives flags it does not recognize, the error message is misleading and makes debugging harder than necessary.

**Steps to reproduce:**
1. `browser4-cli snapshot -i -l 50`
2. Observe the error output

**Expected behavior:** A clear error message such as `unknown flag: -l` or `unrecognized option: -l`, which immediately tells the user which flag is the problem.

**Actual behavior:** The CLI outputs:
```
too many arguments: expected 0, received 2
```
This error counts positional arguments rather than identifying the unrecognized flag, making it appear as though `snapshot` accepts no flags at all. A user unfamiliar with the CLI is likely to conclude that `snapshot` is broken rather than realizing they used an unsupported flag (or a flag that should exist per the documentation, as in Issue 2).

**Suggested resolution:** Improve the argument parser to distinguish unrecognized flags from excess positional arguments, and emit a specific error message naming the unrecognized flag.

Labels: bug, ux

