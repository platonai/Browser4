# Installation verification is unclear for first-time users

## Summary

SKILL.md documents installation methods (`npm install -g browser4-cli`, PowerShell script, bash script) but does not provide a clear verification step to confirm the installation succeeded. The "Quick start" section assumes `browser4-cli install` was already run separately. A user who runs `browser4-cli open` without first running `browser4-cli install` may encounter unclear errors if the Java backend is not available.

## Steps to Reproduce

A first-time user reading SKILL.md and following the installation instructions.

## Expected Behavior

Clear indication of how to verify installation succeeded, including checking that the CLI is available and the backend is healthy.

## Actual Behavior

SKILL.md documents installation methods but does not include a verification step. The "Quick start" section jumps straight to using commands without confirming the installation is complete and the backend is running.

## Suggested Improvement

Add a "Verify installation" section covering:
- `browser4-cli --version` should return a version number
- `browser4-cli status` should show backend health
- Document that `browser4-cli install` is needed before `open`

Labels: enhancement, documentation, medium
