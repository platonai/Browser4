# `--help` Output Does Not Clearly Distinguish `open` vs `goto` Commands

The `--help` output for `open` and `goto` does not make the distinction between these commands obvious to a first-time user. The guidance to "prefer `goto`" exists only in SKILL.md, not in the CLI help text.

**Steps to Reproduce:**
1. Run `browser4-cli --help`
2. Read the descriptions for `open` and `goto`

**Expected:** Clear, actionable guidance on when to use each command. A first-time user should immediately understand that `goto` is the preferred command for most use cases.

**Actual:**
- `open [url]` — "Open a browser session or refresh the saved one if it is no longer active"
- `goto <url>` — "Navigate to a URL, auto-opening or refreshing the session when needed"

The distinction (that `goto` auto-manages sessions while `open` requires manual management) is implicit but not obvious. The "prefer goto" guidance from SKILL.md is absent from `--help`.

**Suggested Fix:** Add a note to the `--help` output for `open`, such as: "Usually you want `goto` instead — it auto-manages sessions." This small change would significantly improve discoverability for new users.

Labels: enhancement, documentation

