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

### Filtering by target

`browser4-cli` has 3 test targets, defined in `Cargo.toml`:

| Command | Target |
|------|-------------|
| `cargo test` | All 3 targets |
| `cargo test --lib` | Only `#[cfg(test)] mod tests` in `src/lib.rs` |
| `cargo test --bin browser4-cli` | Only `#[cfg(test)]` blocks in `src/main.rs` and other `src/*.rs` files |
| `cargo test --test e2e` | Only `tests/e2e.rs` (uses a custom harness) |

`--lib` and `--bin` can be combined:
```bash
# Run all unit tests (lib + bin), skip e2e
cargo test --lib --bin browser4-cli

# Run e2e tests separately (slower, requires a running backend)
cargo test --test e2e -- --nocapture
```

> [!TIP]
> `cargo test` runs all 3 targets by default.  Use `--lib --bin browser4-cli`
> during development to stay in the fast feedback loop.  Reach for
> `--test e2e` only when you specifically need integration coverage.

### Filtering by test name / module

```bash
# Exact match on function name
cargo test test_mousewheel_params_preserve_decimal_numbers

# Substring match — any test whose name contains "mousewheel"
cargo test mousewheel

# Filter by module path prefix
cargo test daemon::tests

# Module + function
cargo test daemon::tests::test_find_browser4_root

# Multiple patterns (OR semantics)
cargo test mousewheel keypress
```

### Filtering by test attribute

```bash
# Run only tests marked with #[ignore]
cargo test -- --ignored

# Run both normal and ignored tests
cargo test -- --include-ignored
```

### Controlling output

```bash
# Show println! output (captured by default)
cargo test -- --nocapture

# Show full output even for passing tests
cargo test -- --show-output

# Limit concurrency
cargo test -- --test-threads=1
```

### e2e-specific

e2e uses a custom harness (`harness = false`). Arguments after `--` are forwarded to
`tests/e2e.rs`'s `main()`:

```bash
# Basics
cargo test --test e2e -- --nocapture

# Run a specific scenario (exact match)
cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_form_submission

# Wildcard — match multiple scenarios
cargo test --test e2e -- --nocapture --scenario=*open*
cargo test --test e2e -- --nocapture --scenario=test_e2e_swarm_*

# Re-run scenarios that failed last time
cargo test --test e2e -- --nocapture --failed

# Enable batch / swarm scenarios (skipped by default)
cargo test --test e2e -- --nocapture --enable-batch-scenario
cargo test --test e2e -- --nocapture --enable-swarm-scenario

# Combine flags
cargo test --test e2e -- --nocapture --failed --enable-batch-scenario

# List all available scenarios without running them
cargo test --test e2e -- --list
```

## License

Apache-2.0
