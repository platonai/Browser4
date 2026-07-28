# Task Instructions Incorrectly Assume Cargo/Rust Toolchain

The generic task evaluation template instructs users to run `cargo run -- help`, which fails because browser4-cli is an npm-based tool, not a Rust/Cargo project. While this is a task-template issue rather than a browser4-cli bug, it highlights a discoverability gap for new contributors.

**Steps to Reproduce:**
1. Follow task preamble instructions and run `cargo run -- help` in the project directory

**Expected:** A help output, or a clear message indicating the correct way to run the tool.

**Actual:** `error: could not find Cargo.toml in <project-dir> or any parent directory`

**Context:** The install and usage instructions in SKILL.md are correct and clear (`npm install`, `browser4-cli --help`). The disconnect is between the generic task template and this specific project.

**Suggested Fix:** Update the task evaluation template to be project-aware, or add a brief "Getting Started" note in the project root (or README) that clearly states the tool is npm-based and how to install and run it. This would help new contributors bypass the incorrect Cargo assumption.

Labels: documentation

