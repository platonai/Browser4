//! Build script that reads the CLI version from `cli/VERSION-CLI` and
//! exposes it as the `BROWSER4_CLI_VERSION` environment variable so the
//! compiled binary always reports the correct version.
//!
//! The script reads the version from `cli/VERSION-CLI` (at `../VERSION-CLI`
//! relative to the Cargo manifest directory), strips any `-SNAPSHOT` suffix,
//! and falls back to `CARGO_PKG_VERSION` when the file cannot be located.
//!
//! `cli/VERSION-CLI` is the canonical single source of truth for the CLI
//! version.  The backend Maven project uses a separate `VERSION` file at the
//! repo root, so the two can be published independently.

use std::env;
use std::fs;
use std::path::PathBuf;

fn find_version_cli() -> Option<PathBuf> {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").ok()?);
    // The crate is at cli/browser4-cli, so cli/VERSION-CLI is ../VERSION-CLI
    let candidate = manifest_dir.parent()?.join("VERSION-CLI");
    if candidate.is_file() {
        // Tell Cargo to re-run this script when VERSION-CLI changes so the
        // binary stays up to date.
        println!("cargo:rerun-if-changed={}", candidate.display());
        Some(candidate)
    } else {
        None
    }
}

fn read_version(path: &std::path::Path) -> Option<String> {
    let raw = fs::read_to_string(path).ok()?;
    // VERSION-CLI is a plain-text file that contains the version on the
    // first non-empty line (e.g. "0.1.15").
    for line in raw.lines() {
        let version = line.trim().to_string();
        if version.is_empty() {
            continue;
        }
        // Strip the Maven-style "-SNAPSHOT" suffix so the CLI reports a
        // clean semver (e.g. "0.1.15" instead of "0.1.15-SNAPSHOT").
        let version = version
            .strip_suffix("-SNAPSHOT")
            .unwrap_or(&version)
            .to_string();
        return Some(version);
    }
    None
}

fn main() {
    let version = match find_version_cli().and_then(|path| read_version(&path)) {
        Some(v) => v,
        None => {
            // Fall back to Cargo.toml version when VERSION-CLI cannot be
            // found (e.g. when the crate is built outside of the monorepo).
            println!(
                "cargo:warning=browser4-cli: VERSION-CLI not found, falling back to CARGO_PKG_VERSION"
            );
            env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.0.0-unknown".to_string())
        }
    };

    println!("cargo:rustc-env=BROWSER4_CLI_VERSION={version}");
}
