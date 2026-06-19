# `cargo run -- help` fails, creating a misleading first impression

## Summary

The evaluation instructions in SKILL.md tell new users to run `cargo run -- help` as the first step, but this command fails because the `cli/` directory does not contain a `Cargo.toml`. This creates confusion and a poor first impression for users trying to get started.

## Steps to Reproduce

1. Enter the `cli/` directory
2. Run `cargo run -- help`

## Expected Behavior

The command should either work (display help text) or the instructions should point to the correct command. The SKILL.md instructions say: "Before performing any browser interaction: 1. Run `cargo run -- help`." This implies it should work from the `cli/` directory.

## Actual Behavior

Fails with: `error: could not find Cargo.toml`. The project uses npm/pnpm for distribution, not Cargo from the `cli/` directory. The `Cargo.toml` exists at a higher level or only for native build steps.

## Suggested Improvement

Update the evaluation instructions or add a note in SKILL.md that `browser4-cli help` (global CLI) is the correct first command, not `cargo run -- help`. Alternatively, document that `cargo build` is only for native binary compilation via `npm run build:native`.

Labels: bug, documentation, discoverability
