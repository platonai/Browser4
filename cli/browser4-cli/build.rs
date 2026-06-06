//! Build script that reads the project-wide version from the root VERSION
//! file and exposes it as the `BROWSER4_CLI_VERSION` environment variable so
//! the compiled binary always reports the correct version, even when a
//! developer forgets to run `sync-version.js` before `cargo build`.
//!
//! The script walks up from the crate directory until it finds a `VERSION`
//! file, strips any `-SNAPSHOT` suffix (matching the release-script
//! convention), and falls back to `CARGO_PKG_VERSION` when the file cannot
//! be located.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn find_repo_root() -> Option<PathBuf> {
    // Start from the crate manifest directory (cli/browser4-cli) and walk
    // upward until we find a file named "VERSION".
    let mut dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").ok()?);

    loop {
        let candidate = dir.join("VERSION");
        if candidate.is_file() {
            // Tell Cargo to re-run this script when the found VERSION
            // file changes so the binary stays up to date.
            println!("cargo:rerun-if-changed={}", candidate.display());
            return Some(dir);
        }

        if !dir.pop() {
            return None;
        }
    }
}

fn read_version(repo_root: &Path) -> Option<String> {
    let path = repo_root.join("VERSION");
    let raw = fs::read_to_string(&path).ok()?;
    let version = raw.lines().next()?.trim().to_string();

    if version.is_empty() {
        return None;
    }

    // Strip the Maven-style "-SNAPSHOT" suffix so the CLI reports a clean
    // semver (e.g. "4.11.0" instead of "4.11.0-SNAPSHOT").  This matches
    // the behaviour of bin/release/update-versions.sh.
    let version = version
        .strip_suffix("-SNAPSHOT")
        .unwrap_or(&version)
        .to_string();

    Some(version)
}

fn main() {
    let version = match find_repo_root().and_then(|root| read_version(&root)) {
        Some(v) => v,
        None => {
            // Fall back to Cargo.toml version when VERSION cannot be found
            // (e.g. when the crate is built outside of the monorepo).
            println!(
                "cargo:warning=browser4-cli: VERSION file not found, falling back to CARGO_PKG_VERSION"
            );
            env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.0.0-unknown".to_string())
        }
    };

    println!("cargo:rustc-env=BROWSER4_CLI_VERSION={version}");
}
