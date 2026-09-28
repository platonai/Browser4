//! Browser4 CLI library — shared modules used by both the CLI binary and integration tests.

pub mod commands;
pub mod config;
pub mod daemon;
pub mod java;
pub mod managed_processes;
pub mod skills;
pub mod state;

// Test-only: `daemon.rs` is compiled into both this library and the CLI binary,
// and resolves the shared env lock through `crate::test_env`.
#[cfg(test)]
pub(crate) mod test_env;
