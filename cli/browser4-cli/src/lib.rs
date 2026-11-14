//! Browser4 CLI library — shared modules used by both the CLI binary and integration tests.

pub mod commands;
pub mod config;
pub mod daemon;
pub mod java;
pub mod managed_processes;
pub mod skills;
pub mod state;
pub mod update_check;

// Test-only: `commands.rs` is compiled into both this library and the CLI binary.
// Its `#[cfg(test)]` module exercises argument parsing through `crate::args`, and
// `args.rs` in turn resolves help text through `crate::help`; the binary declares
// both, so the library must too or `cargo test --lib` cannot compile the crate.
// Gated on `cfg(test)` so a plain library build keeps today's (smaller) module set.
#[cfg(test)]
pub mod args;
#[cfg(test)]
pub mod help;

// Test-only: `daemon.rs` is compiled into both this library and the CLI binary,
// and resolves the shared env lock through `crate::test_env`.
#[cfg(test)]
pub(crate) mod test_env;
