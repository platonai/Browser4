# `open` and `goto` command overlap creates user confusion

**Severity:** Low  
**Category:** Documentation / UX

## Summary
The CLI exposes both `open [url]` and `goto <url>` commands without clear guidance in the `--help` output about when to use each. While SKILL.md advises "Prefer goto over manual session management," this guidance is absent from the CLI help, leaving new users uncertain.

## Steps to Reproduce
1. Run `browser4-cli --help`
2. See both `open [url]` and `goto <url>` listed
3. Be uncertain which command to use for everyday navigation

## Expected Behavior
Clear, immediately visible guidance on the difference between `open` and `goto` and when to use each.

## Actual Behavior
SKILL.md provides the guidance ("Prefer goto over manual session management"), but the `--help` output does not reflect this. Users who only read `--help` (the first thing most users do) miss the recommendation.

## Suggested Improvement
Add a note in the `--help` output for both commands. For example:
- `goto` help: "Navigate to a URL (auto-manages browser sessions). Recommended for everyday navigation."
- `open` help: "Open a URL in a fresh browser session. For most navigation, prefer `goto` which auto-manages sessions."

