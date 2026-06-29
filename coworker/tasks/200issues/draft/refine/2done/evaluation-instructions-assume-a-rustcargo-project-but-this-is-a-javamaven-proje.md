# Evaluation instructions assume a Rust/Cargo project, but this is a Java/Maven project

The evaluation prompt and documentation instruct users to run `cargo run -- help`, but the project is built with Java and Maven — there is no `Cargo.toml` at the repository root.

**Steps to reproduce:**
1. Follow the evaluation prompt instructions.
2. Run `cargo run -- help`.

**Expected behavior:** The command prints help output from `browser4-cli`.

**Actual behavior:** `error: could not find Cargo.toml`. The project is Java/Maven; `cargo` is not applicable.

**Suggested improvement:** The evaluation prompt should detect the project type or provide the correct invocation (`browser4-cli --help`). Documentation and onboarding materials should reference the project's actual build toolchain.

Labels: documentation

