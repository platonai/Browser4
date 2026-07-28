---
title: "Development"
description: "Prerequisites and setup for building the browser4-cli from source. Read this when you need to compile or develop the CLI itself."
tier: procedure
---

# Development

## Prerequisites for Development

- **[Rust](https://rustup.rs/)** — via `rustup`; needed to compile the CLI binary.
- **Java 17+** — required by the Browser4 backend server.
- **[Git](https://git-scm.com/)** — for cloning the repository.

Verify your setup with:

```bash
cargo --version && java -version
```

When running from source (not a globally installed binary), use `cargo run` from the CLI directory:

```bash
cd cli/browser4-cli
cargo build                     # build the binary
cargo run -- <command>          # run a command (the -- separates cargo args from CLI args)
cargo run -- goto "https://example.com"
cargo run -- snapshot -v 0
```

**Note:** All examples in this document use `browser4-cli` as the command. If running from source, substitute `cargo run --` (with the leading `cd cli/browser4-cli &&` if not already in that directory).

**From repo root (no `cd` required):**

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>
```

This pattern works from any directory — no need to `cd` first.

## Output Redirection in Dev Mode

The working directory during `cargo run` is `cli/browser4-cli/`, so relative file paths must account for this. Use `--quiet` to suppress cargo build output:

```bash
# From repo root: redirect query results to a file
cd cli/browser4-cli && cargo run --quiet -- htmlsnapshot query --sql @../../query.sql --result-only > ../../results.json

# From cli/browser4-cli/: same pattern with shorter relative paths
cargo run --quiet -- htmlsnapshot query --sql @query.sql --result-only > results.json
```

> **Tip:** `--quiet` passes through to cargo and suppresses the "Finished" / "Running" build-status lines that would otherwise pollute the output file. Without `--quiet`, those lines appear on stderr but `2>&1` captures them along with the data — use `--quiet` instead of `2>&1` for clean output.
