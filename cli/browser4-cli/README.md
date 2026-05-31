# Browser4 CLI (development)

Rust CLI binary for controlling a [Browser4](https://github.com/platonai/Browser4) server.
See [`cli/README.md`](../README.md) for the user-facing documentation.

## Build

```bash
cargo build --release
# Binary: target/release/browser4-cli
cargo install --path .
```

## Architecture

| Module | Purpose |
|---|---|
| `src/main.rs` | Entry point, command dispatch, session lifecycle |
| `src/commands.rs` | Command definitions (name, args, options, MCP tool mapping) |
| `src/args.rs` | CLI argument parsing |
| `src/http.rs` | HTTP client for `/mcp/call-tool` |
| `src/state.rs` | Persistent CLI state (`~/.browser4/cli-state.json`) |
| `src/daemon.rs` | Local Browser4 server auto-start (Maven / jar / download) |
| `src/managed_processes.rs` | Server process registry and force-cleanup |
| `src/snapshot.rs` | Snapshot & screenshot file helpers |
| `src/help.rs` | Help text generation |

## Testing

```bash
# All tests
cargo test

# End-to-end tests with output
cargo test --test e2e -- --nocapture

# Specific scenario
cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_form_submission

# Pattern match
cargo test --test e2e -- --nocapture --scenario=test_e2e_swarm_*

# Rerun failures
cargo test --test e2e -- --nocapture --failed

# Include batch scenarios (skipped by default)
cargo test --test e2e -- --nocapture --enable-batch-scenario
```

## License

Apache-2.0
