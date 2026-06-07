//! Build script that reads the CLI version from `cli/package.json` and
//! exposes it as the `BROWSER4_CLI_VERSION` environment variable so the
//! compiled binary always reports the correct version, even when a developer
//! forgets to run `sync-version.js` before `cargo build`.
//!
//! The script reads the version from `cli/package.json` (at `../package.json`
//! relative to the Cargo manifest directory), strips any `-SNAPSHOT` suffix,
//! and falls back to `CARGO_PKG_VERSION` when the file cannot be located.
//!
//! Using `cli/package.json` as the version source (instead of the repo-root
//! `VERSION` file) allows the backend Maven project and the CLI to be
//! published separately with different versions.

use std::env;
use std::fs;
use std::path::PathBuf;

fn find_cli_package_json() -> Option<PathBuf> {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").ok()?);
    // The crate is at cli/browser4-cli, so cli/package.json is ../package.json
    let candidate = manifest_dir.parent()?.join("package.json");
    if candidate.is_file() {
        // Tell Cargo to re-run this script when the package.json version
        // changes so the binary stays up to date.
        println!("cargo:rerun-if-changed={}", candidate.display());
        Some(candidate)
    } else {
        None
    }
}

fn read_version(path: &std::path::Path) -> Option<String> {
    let raw = fs::read_to_string(path).ok()?;
    // Find "version": "X.Y.Z" in package.json (simple line-based parsing
    // to avoid pulling in a JSON dependency).
    for line in raw.lines() {
        let trimmed = line.trim();
        if let Some(after_colon) = trimmed
            .strip_prefix("\"version\"")
            .and_then(|rest| rest.trim_start().strip_prefix(':'))
        {
            let version = after_colon
                .trim()
                .trim_matches('"')
                .trim_end_matches(',')
                .trim_matches('"')
                .to_string();

            if version.is_empty() {
                return None;
            }

            // Strip the Maven-style "-SNAPSHOT" suffix so the CLI reports a
            // clean semver (e.g. "4.11.0" instead of "4.11.0-SNAPSHOT").
            let version = version
                .strip_suffix("-SNAPSHOT")
                .unwrap_or(&version)
                .to_string();

            return Some(version);
        }
    }
    None
}

fn main() {
    let version = match find_cli_package_json().and_then(|path| read_version(&path)) {
        Some(v) => v,
        None => {
            // Fall back to Cargo.toml version when cli/package.json cannot be
            // found (e.g. when the crate is built outside of the monorepo).
            println!(
                "cargo:warning=browser4-cli: cli/package.json not found, falling back to CARGO_PKG_VERSION"
            );
            env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.0.0-unknown".to_string())
        }
    };

    println!("cargo:rustc-env=BROWSER4_CLI_VERSION={version}");
}
