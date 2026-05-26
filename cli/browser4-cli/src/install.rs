//! Install command: download Browser4.jar and a bundled JRE from GitHub releases.
//!
//! Usage:
//!   browser4-cli install                    # install latest release
//!   browser4-cli install --version=4.9.0    # install specific version

use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

use reqwest::Client;
use serde_json::Value;

use crate::state::resolve_default_state_dir;

const GITHUB_RELEASES_BASE: &str = "https://github.com/platonai/Browser4/releases";
const JRE_DIR_NAME: &str = "jre";
const BROWSER4_JAR_FILE_NAME: &str = "Browser4.jar";
const DOWNLOAD_TIMEOUT_JAR_SECS: u64 = 300;
const DOWNLOAD_TIMEOUT_JRE_SECS: u64 = 600;

// ---------------------------------------------------------------------------
// Public helpers
// ---------------------------------------------------------------------------

/// Returns the lib directory where `browser4-cli install` places files.
pub fn default_lib_dir() -> PathBuf {
    resolve_default_state_dir().join("lib")
}

/// Returns the path to the `java` binary in the bundled JRE that lives inside
/// `lib_dir`, or `None` if it does not exist.
pub fn bundled_jre_java_binary(lib_dir: &Path) -> Option<PathBuf> {
    let java = jre_java_binary_path(lib_dir);
    java.exists().then_some(java)
}

/// Constructs the expected path to the `java` binary inside `lib_dir/jre/`.
/// Does not check whether the file actually exists.
pub fn jre_java_binary_path(lib_dir: &Path) -> PathBuf {
    let jre_dir = lib_dir.join(JRE_DIR_NAME);
    if cfg!(windows) {
        jre_dir.join("bin").join("java.exe")
    } else {
        jre_dir.join("bin").join("java")
    }
}

// ---------------------------------------------------------------------------
// Command handler
// ---------------------------------------------------------------------------

pub async fn handle_install(tool_params: &Value) -> Result<(), String> {
    let lib_dir = default_lib_dir();

    let version = tool_params
        .get("version")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(|v| v.trim_start_matches('v').to_string());

    // 1. Download Browser4.jar
    let jar_path = lib_dir.join(BROWSER4_JAR_FILE_NAME);
    download_jar_to(&jar_path, version.as_deref()).await?;

    // 2. Download and extract bundled JRE (platform-specific)
    match jre_asset_name() {
        None => {
            eprintln!(
                "ℹ️  No bundled JRE available for this platform ({}/{}).",
                std::env::consts::OS,
                std::env::consts::ARCH
            );
            eprintln!(
                "   Make sure Java 17+ is on your PATH before running 'browser4-cli open'."
            );
        }
        Some(asset_name) => {
            let jre_dir = lib_dir.join(JRE_DIR_NAME);
            download_and_extract_jre(&lib_dir, &jre_dir, asset_name, version.as_deref()).await?;
        }
    }

    // 3. Report success
    println!("✅ Browser4 installed successfully.");
    println!("   JAR:  {}", jar_path.display());
    let java_bin = jre_java_binary_path(&lib_dir);
    if java_bin.exists() {
        println!("   JRE:  {}", lib_dir.join(JRE_DIR_NAME).display());
    }
    println!();
    println!("Run 'browser4-cli open' to start Browser4.");
    Ok(())
}

// ---------------------------------------------------------------------------
// Platform detection
// ---------------------------------------------------------------------------

/// Returns the release-asset name for the bundled JRE on the current platform,
/// or `None` if no pre-built JRE is available.
fn jre_asset_name() -> Option<&'static str> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("linux", "x86_64") => Some("browser4-jre-linux-x64.tar.gz"),
        ("linux", "aarch64") => Some("browser4-jre-linux-arm64.tar.gz"),
        ("windows", "x86_64") => Some("browser4-jre-win32-x64.zip"),
        ("macos", "x86_64") => Some("browser4-jre-darwin-x64.tar.gz"),
        ("macos", "aarch64") => Some("browser4-jre-darwin-arm64.tar.gz"),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Download helpers
// ---------------------------------------------------------------------------

async fn download_jar_to(target_path: &Path, version: Option<&str>) -> Result<(), String> {
    if let Some(dir) = target_path.parent() {
        fs::create_dir_all(dir)
            .map_err(|e| format!("Failed to create install directory: {e}"))?;
    }

    let url = match version {
        Some(v) => format!("{}/download/v{}/Browser4.jar", GITHUB_RELEASES_BASE, v),
        None => format!("{}/latest/download/Browser4.jar", GITHUB_RELEASES_BASE),
    };

    eprintln!("Downloading Browser4.jar from {}...", url);

    let bytes = http_get_bytes(&url, DOWNLOAD_TIMEOUT_JAR_SECS).await?;
    fs::write(target_path, &bytes)
        .map_err(|e| format!("Failed to write Browser4.jar: {e}"))?;

    eprintln!("Downloaded Browser4.jar ({} bytes).", bytes.len());
    Ok(())
}

async fn download_and_extract_jre(
    lib_dir: &Path,
    jre_dir: &Path,
    asset_name: &str,
    version: Option<&str>,
) -> Result<(), String> {
    let url = match version {
        Some(v) => format!("{}/download/v{}/{}", GITHUB_RELEASES_BASE, v, asset_name),
        None => format!("{}/latest/download/{}", GITHUB_RELEASES_BASE, asset_name),
    };

    eprintln!("Downloading bundled JRE from {}...", url);

    let bytes = http_get_bytes(&url, DOWNLOAD_TIMEOUT_JRE_SECS).await?;
    eprintln!("Downloaded JRE archive ({} bytes). Extracting...", bytes.len());

    // Write archive to a temp file next to the future jre dir
    let archive_path = lib_dir.join(asset_name);
    fs::write(&archive_path, &bytes)
        .map_err(|e| format!("Failed to write JRE archive: {e}"))?;

    // Remove existing JRE directory to avoid stale files
    if jre_dir.exists() {
        fs::remove_dir_all(jre_dir).map_err(|e| format!("Failed to remove old JRE: {e}"))?;
    }

    // Extract
    let extract_result = if asset_name.ends_with(".zip") {
        extract_zip_archive(&archive_path, lib_dir)
    } else {
        extract_tar_gz_archive(&archive_path, lib_dir)
    };

    // Remove temp archive regardless of extraction outcome
    let _ = fs::remove_file(&archive_path);

    extract_result?;

    // Rename the extracted top-level directory to "jre" if it has a different name
    normalize_jre_directory(lib_dir, jre_dir)?;

    eprintln!("JRE extracted to {}.", jre_dir.display());
    Ok(())
}

async fn http_get_bytes(url: &str, timeout_secs: u64) -> Result<Vec<u8>, String> {
    let client = Client::builder()
        .timeout(std::time::Duration::from_secs(timeout_secs))
        .build()
        .map_err(|e| e.to_string())?;

    let response = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("Download failed: {e}"))?;

    if !response.status().is_success() {
        return Err(format!(
            "Download failed with HTTP {}: {}",
            response.status(),
            url
        ));
    }

    response
        .bytes()
        .await
        .map(|b| b.to_vec())
        .map_err(|e| format!("Failed to read response body: {e}"))
}

// ---------------------------------------------------------------------------
// Archive extraction
// ---------------------------------------------------------------------------

#[cfg(not(windows))]
fn extract_tar_gz_archive(archive_path: &Path, dest_dir: &Path) -> Result<(), String> {
    let status = Command::new("tar")
        .args([
            "xzf",
            &archive_path.to_string_lossy(),
            "-C",
            &dest_dir.to_string_lossy(),
        ])
        .status()
        .map_err(|e| format!("Failed to launch tar: {e}"))?;
    if !status.success() {
        return Err(format!("tar extraction failed (status: {status})"));
    }
    Ok(())
}

#[cfg(windows)]
fn extract_tar_gz_archive(_archive_path: &Path, _dest_dir: &Path) -> Result<(), String> {
    Err("tar.gz extraction is not supported on Windows; a .zip archive should have been used.".to_string())
}

#[cfg(windows)]
fn extract_zip_archive(archive_path: &Path, dest_dir: &Path) -> Result<(), String> {
    let script = format!(
        "Expand-Archive -LiteralPath '{}' -DestinationPath '{}' -Force",
        archive_path.to_string_lossy().replace('\'', "''"),
        dest_dir.to_string_lossy().replace('\'', "''"),
    );
    let status = Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", &script])
        .status()
        .map_err(|e| format!("Failed to launch PowerShell for extraction: {e}"))?;
    if !status.success() {
        return Err(format!("Zip extraction failed (status: {status})"));
    }
    Ok(())
}

#[cfg(not(windows))]
fn extract_zip_archive(archive_path: &Path, dest_dir: &Path) -> Result<(), String> {
    let status = Command::new("unzip")
        .args([
            "-q",
            &archive_path.to_string_lossy(),
            "-d",
            &dest_dir.to_string_lossy(),
        ])
        .status()
        .map_err(|e| format!("Failed to launch unzip: {e}"))?;
    if !status.success() {
        return Err(format!("Zip extraction failed (status: {status})"));
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// JRE directory normalisation
// ---------------------------------------------------------------------------

/// After extraction, locate the top-level directory that looks like a JRE
/// root (contains `bin/java` or `bin/java.exe`) and rename it to `jre_dir`.
///
/// jlink output directories are typically named after the configured image,
/// so the archive may extract to e.g. `lib/browser4-jre` rather than `lib/jre`.
fn normalize_jre_directory(lib_dir: &Path, jre_dir: &Path) -> Result<(), String> {
    if jre_dir.is_dir() {
        return Ok(()); // already in the right place
    }

    let entries: Vec<PathBuf> = fs::read_dir(lib_dir)
        .map_err(|e| format!("Failed to read lib dir: {e}"))?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir() && p != jre_dir)
        .collect();

    // Prefer a directory whose bin/java[.exe] exists
    let jre_root = entries
        .iter()
        .find(|p| {
            p.join("bin").join("java").exists() || p.join("bin").join("java.exe").exists()
        })
        .or_else(|| {
            // Fall back to any single extracted directory
            if entries.len() == 1 {
                entries.first()
            } else {
                None
            }
        });

    if let Some(src) = jre_root {
        fs::rename(src, jre_dir)
            .map_err(|e| format!("Failed to rename JRE directory to 'jre': {e}"))?;
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jre_asset_name_returns_static_str_or_none() {
        // Just ensure the function is callable without panicking.
        let _name = jre_asset_name();
    }

    #[test]
    fn jre_java_binary_path_has_correct_extension() {
        let lib_dir = PathBuf::from("/fake/lib");
        let java = jre_java_binary_path(&lib_dir);
        if cfg!(windows) {
            assert!(
                java.to_string_lossy().ends_with("java.exe"),
                "expected java.exe, got {}",
                java.display()
            );
        } else {
            assert!(
                java.to_string_lossy().ends_with("java"),
                "expected java, got {}",
                java.display()
            );
        }
    }

    #[test]
    fn bundled_jre_java_binary_returns_none_for_missing_dir() {
        let tmp = tempfile::tempdir().unwrap();
        assert!(bundled_jre_java_binary(tmp.path()).is_none());
    }
}
