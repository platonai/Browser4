# Building from source fails from repository root — no workspace-level Cargo.toml

Users who clone the repository and attempt to build from source are met with an error because `Cargo.toml` is in a subdirectory (`cli/browser4-cli/`), not the repo root. There are no source-build instructions in the documentation, which assumes npm installation.

**Steps to reproduce:**
1. Clone the repository.
2. Run `cargo run -- help` from the repo root.
3. Observe the error: `could not find Cargo.toml`.

**Expected behavior:** Either a top-level `Cargo.toml` workspace so `cargo run` works from root, or clear documentation about which directory to use and how to build from source.

**Actual behavior:** `Cargo.toml` is in `cli/browser4-cli/`, not the repo root. The SKILL.md assumes npm installation, not building from source, so there are no source-build instructions. Users must discover the correct subdirectory on their own.

**Suggested improvement:** Either add a workspace `Cargo.toml` at the repo root, or add a `CONTRIBUTING.md` or section in SKILL.md with source-build instructions (e.g., `cd cli/browser4-cli && cargo run -- help`).

Labels: enhancement, documentation

