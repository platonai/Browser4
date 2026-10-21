# `cargo run -- help` Fails from Repository Root — No Workspace-Level Cargo.toml

Running `cargo run -- help` from the repository root fails because there is no workspace-level `Cargo.toml`. The actual `Cargo.toml` is nested inside `cli/browser4-cli/`. A new developer cloning the repository and following standard Rust project conventions will hit this error immediately.

**Steps to Reproduce:**
1. Clone the repository and navigate to the repository root.
2. Run `cargo run -- help`.

**Expected Behavior:** The command either runs successfully (via a workspace `Cargo.toml`) or produces a clear error message directing the developer to the correct subdirectory.

**Actual Behavior:** `error: could not find Cargo.toml` — with no indication of where the correct `Cargo.toml` is located. The developer must discover the nested path `cli/browser4-cli/` on their own.

**Suggested Improvement:** Either:
- Add a workspace-level `Cargo.toml` at the repository root that includes `cli/browser4-cli/` as a member.
- Document the correct invocation path (`cd cli/browser4-cli && cargo run -- help`) in a `CONTRIBUTING.md` or the project README.

**Acceptance Criteria:**
- A developer following standard Rust conventions can build and run the CLI from the repository root, OR the repository root README provides clear build instructions with the correct subdirectory path.

Labels: bug, documentation

