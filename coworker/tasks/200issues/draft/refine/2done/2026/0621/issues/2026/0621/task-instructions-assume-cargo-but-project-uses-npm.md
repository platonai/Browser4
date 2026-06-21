# Task instructions assume Cargo, but project uses npm

## Summary

The evaluation task instructions for Browser4-CLI assume the project uses Cargo/Rust to build and run, but the actual project uses npm. Running `cargo run -- help` in the `cli/` directory fails because there is no `Cargo.toml` at that path.

## Steps to reproduce

1. Navigate to the `cli/` directory of the project.
2. Run `cargo run -- help`.

## Expected behavior

The command succeeds and displays help output for the CLI tool.

## Actual behavior

The command fails with `could not find Cargo.toml`. The actual binary is installed via npm and invoked as `browser4-cli`. The `Cargo.toml` is located at `cli/browser4-cli/`, not at `cli/`.

## Suggested resolution

- The evaluation task instructions should detect the project type before attempting to run it.
- Alternatively, the project root README should be clearer that `cli/` is a packaging directory, not the Rust source root, and direct users to the correct build approach.

Labels: documentation
