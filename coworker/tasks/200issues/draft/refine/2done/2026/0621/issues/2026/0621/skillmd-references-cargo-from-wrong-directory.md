# SKILL.md references Cargo paths not usable from `cli/` directory

## Summary

SKILL.md (the task runner skill documentation) includes references to `Cargo.toml` at the relative path `browser4-cli/Cargo.toml`, which is correct for building from source. However, the quick-start section assumes `browser4-cli` is already installed via npm, and using `cargo run` from the `cli/` directory fails because the `Cargo.toml` is nested inside `cli/browser4-cli/`.

## Steps to reproduce

1. Follow SKILL.md instructions from the `cli/` directory.
2. Attempt any `cargo` command referencing the project.

## Expected behavior

Clear distinction between "using the installed npm tool" and "building from source" — users should know which directory to be in and which command to run for each workflow.

## Actual behavior

The README has both sections clearly, and SKILL.md focuses primarily on usage. The issue is relatively minor since the Cargo.toml path is correct when resolved from the right directory, but the task instructions' `cargo run -- help` assumption is the more significant problem.

## Suggested resolution

- Ensure SKILL.md explicitly states the working directory context for build-from-source instructions (e.g., "from `cli/browser4-cli/`").
- Add a note distinguishing the installed-tool workflow (`browser4-cli`) from the build-from-source workflow (`cd cli/browser4-cli && cargo run --`).
- Consider making the quick-start guide consistently recommend the npm-installed approach.

Labels: documentation
