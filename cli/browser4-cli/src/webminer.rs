//! webminer (WebMiner) — first-class CLI support for the web-miner SKILL.
//!
//! webminer is an external Java tool ([platonai/web-miner]) that groups
//! downloaded HTML pages into clusters and produces interactive HTML views
//! plus Excel spreadsheets.  This module implements the webminer launcher in
//! native Rust (replacing the PowerShell launcher that previously shipped
//! with the skill), so the CLI works on every platform without PowerShell:
//!
//! - **Management** — `install` / `update` / `version` / `uninstall` download
//!   and verify `scent-miner.jar` from GitHub Releases with an Aliyun OSS
//!   mirror fallback, exactly like the launcher script.
//! - **Pipeline** — `all <html-dir>`, `views <result-dir>`, and any other JAR
//!   command are forwarded verbatim to `java -jar scent-miner.jar` with the
//!   JVM module-opens flags the stack needs.
//!
//! Install layout (kept identical to the launcher): `~/.scent/webminer/`
//! containing `lib/scent-miner.jar`, `version.txt` and `checksum.sha256`.
//!
//! [platonai/web-miner]: https://github.com/platonai/web-miner

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

use serde_json::Value;
use sha2::{Digest, Sha256};

const REPO_OWNER: &str = "platonai";
const REPO_NAME: &str = "web-miner";
const GITHUB_API_LATEST: &str = "https://api.github.com/repos/platonai/web-miner/releases/latest";
const OSS_BASE_URL: &str = "https://web-miner.oss-cn-beijing.aliyuncs.com";
const OSS_LATEST_JSON: &str = "https://web-miner.oss-cn-beijing.aliyuncs.com/releases/latest-release.json";
const OSS_LATEST_DOWNLOAD: &str = "https://web-miner.oss-cn-beijing.aliyuncs.com/releases/latest/download";

/// `-Dapp.name` value used when launching the JAR.  The views task-output
/// directory is `%TEMP%/<app>-pereg/ml/tasks/...`; the default is `webminer`,
/// so runs started through browser4-cli use the webminer task-output root
/// (a direct `java -jar` run uses `pulsar`).
const APP_NAME: &str = "webminer";

/// JVM `--add-opens` flags required by the webminer stack at runtime.
const MODULE_OPENS: &[&str] = &[
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
    "--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED",
];

/// A release of `scent-miner.jar` resolved from GitHub or the OSS mirror.
#[derive(Debug, Clone)]
pub struct ReleaseInfo {
    pub tag_name: String,
    pub published_at: Option<String>,
    pub jar_url: String,
    pub jar_size: Option<u64>,
    /// Hex SHA-256 when the release metadata provides one.
    pub jar_checksum: Option<String>,
}

/// Outcome of an install/update operation.
#[derive(Debug, Clone)]
pub struct InstallOutcome {
    pub tag: String,
    pub install_dir: PathBuf,
    pub reused_existing: bool,
    pub source_url: String,
}

/// Current local installation state (for `version` / bare `webminer`).
#[derive(Debug, Clone)]
pub struct VersionStatus {
    pub installed: Option<String>,
    pub install_dir: PathBuf,
    pub jar_bytes: Option<u64>,
    pub latest: Option<ReleaseInfo>,
    pub latest_error: Option<String>,
}

// ---------------------------------------------------------------------------
// Paths and install state
// ---------------------------------------------------------------------------

fn require_home() -> Result<PathBuf, String> {
    dirs::home_dir().ok_or_else(|| {
        "Cannot determine home directory (neither USERPROFILE nor HOME is set).".to_string()
    })
}

/// `~/.scent/webminer` — where releases are installed.
pub fn install_root() -> Result<PathBuf, String> {
    Ok(require_home()?.join(".scent").join("webminer"))
}

pub fn install_jar_path() -> Result<PathBuf, String> {
    Ok(install_root()?.join("lib").join("scent-miner.jar"))
}

/// Path of the installed JAR, or `None` when webminer is not installed.
pub fn installed_jar_path() -> Option<PathBuf> {
    install_jar_path().ok().filter(|p| p.is_file())
}

fn read_version_file(path: &Path) -> Option<String> {
    std::fs::read_to_string(path)
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

/// The version tag recorded at install time (e.g. `v0.0.7`).
pub fn read_installed_version() -> Result<Option<String>, String> {
    Ok(read_version_file(&install_root()?.join("version.txt")))
}

// ---------------------------------------------------------------------------
// Launching the JAR
// ---------------------------------------------------------------------------

/// Launch `java -jar <jar> <forward...>` with the required JVM flags and
/// inherited stdio, returning the child process exit code.
pub fn run_jar(jar: &Path, java_exe: &Path, forward: &[String]) -> Result<i32, String> {
    let mut cmd = Command::new(java_exe);
    // `-Dapp.name` selects the views task-output root (`%TEMP%/<app>-pereg/...`);
    // honour APP_NAME (the value the launcher used).
    let app_name = std::env::var("APP_NAME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| APP_NAME.to_string());
    cmd.arg(format!("-Dapp.name={app_name}"));
    for flag in MODULE_OPENS {
        cmd.arg(flag);
    }
    cmd.arg("-jar").arg(jar);
    for arg in forward {
        cmd.arg(arg);
    }
    // Expose JAVA_HOME to the child when the java executable lives under
    // `<home>/bin/`, so the JAR can locate the JDK.
    if let Some(bin) = java_exe.parent() {
        if bin.file_name().map(|n| n == "bin").unwrap_or(false) {
            if let Some(home) = bin.parent() {
                cmd.env("JAVA_HOME", home);
            }
        }
    }
    cmd.stdin(Stdio::inherit())
        .stdout(Stdio::inherit())
        .stderr(Stdio::inherit());
    let status = cmd
        .status()
        .map_err(|e| format!("Failed to launch webminer: {e}"))?;
    Ok(status.code().unwrap_or(1))
}

/// Run a webminer pipeline command (`all <dir>`, `views <result-dir>`, or
/// any other JAR command) with the installed JAR and a detected Java 17+.
pub fn run_pipeline(forward: &[String]) -> Result<i32, String> {
    let jar = installed_jar_path().ok_or_else(|| {
        "Cannot find scent-miner.jar.\n\nRun `browser4-cli webminer install` to download the latest release, or place scent-miner.jar at ~/.scent/webminer/lib/scent-miner.jar."
            .to_string()
    })?;
    let java = crate::java::find_java17()?;
    run_jar(&jar, &java, forward)
}

// ---------------------------------------------------------------------------
// Release metadata (GitHub → OSS mirror)
// ---------------------------------------------------------------------------

fn user_agent() -> String {
    format!("browser4-cli/{}", env!("BROWSER4_CLI_VERSION"))
}

fn blocking_client(timeout_secs: u64) -> Result<reqwest::blocking::Client, String> {
    let mut builder = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(timeout_secs))
        .user_agent(user_agent());
    if let Some(proxy) = crate::daemon::resolve_download_proxy() {
        builder = builder.proxy(proxy);
    }
    builder.build().map_err(|e| e.to_string())
}

async fn async_client(timeout_secs: u64) -> Result<reqwest::Client, String> {
    let mut builder = reqwest::Client::builder()
        .timeout(Duration::from_secs(timeout_secs))
        .user_agent(user_agent());
    if let Some(proxy) = crate::daemon::resolve_download_proxy() {
        builder = builder.proxy(proxy);
    }
    builder.build().map_err(|e| e.to_string())
}

fn find_jar_asset<'a>(assets: &'a [Value]) -> Option<&'a Value> {
    assets
        .iter()
        .find(|a| a.get("name").and_then(|n| n.as_str()) == Some("scent-miner.jar"))
}

/// Parse the GitHub Releases API JSON for a `scent-miner.jar` asset.
pub fn parse_github_release(v: &Value) -> Option<ReleaseInfo> {
    let tag_name = v.get("tag_name")?.as_str()?.to_string();
    let asset = find_jar_asset(v.get("assets")?.as_array()?)?;
    let jar_url = asset.get("browser_download_url")?.as_str()?.to_string();
    let jar_checksum = asset
        .get("digest")
        .and_then(|d| d.as_str())
        .map(|d| d.strip_prefix("sha256:").unwrap_or(d).to_string())
        .filter(|s| !s.is_empty());
    Some(ReleaseInfo {
        tag_name,
        published_at: v
            .get("published_at")
            .and_then(|p| p.as_str())
            .map(str::to_string),
        jar_url,
        jar_size: asset.get("size").and_then(|s| s.as_u64()),
        jar_checksum,
    })
}

/// Parse the OSS mirror `latest-release.json` for a `scent-miner.jar` asset.
pub fn parse_oss_release(v: &Value) -> Option<ReleaseInfo> {
    let tag_name = v.get("tag")?.as_str()?.to_string();
    let asset = find_jar_asset(v.get("assets")?.as_array()?)?;
    let jar_checksum = asset
        .get("sha256")
        .and_then(|s| s.as_str())
        .map(str::to_string)
        .filter(|s| !s.is_empty());
    let jar_url = format!("{OSS_BASE_URL}/releases/download/{tag_name}/scent-miner.jar");
    Some(ReleaseInfo {
        tag_name,
        published_at: v
            .get("published_at")
            .and_then(|p| p.as_str())
            .map(str::to_string),
        jar_url,
        jar_size: asset.get("size").and_then(|s| s.as_u64()),
        jar_checksum,
    })
}

/// When a download proxy is configured (CLI config or env vars), return a
/// hint appended to network errors: an unreachable proxy (e.g. a local SOCKS5
/// tool that is not running) surfaces as "cannot reach" failures, and the
/// user needs a pointer to the proxy configuration.
fn proxy_hint() -> String {
    let proxy = std::env::var("BROWSER4_CLI_PROXY")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .or_else(|| {
            std::env::var("https_proxy")
                .or_else(|_| std::env::var("HTTPS_PROXY"))
                .ok()
                .filter(|v| !v.trim().is_empty())
        });
    match proxy {
        Some(p) => format!(
            "\n\nNote: a download proxy is configured ({p}). If the proxy is not running, \
             disable it with `browser4-cli config set proxy`, or override it per command \
             with `--proxy <url>`."
        ),
        None => String::new(),
    }
}

/// Fetch the latest release metadata, trying GitHub first and falling back
/// to the OSS mirror.  `Ok(None)` means the metadata was reachable but no
/// `scent-miner.jar` asset exists; `Err` means both sources failed.
pub async fn fetch_latest_release() -> Result<Option<ReleaseInfo>, String> {
    let client = async_client(30).await?;
    match client.get(GITHUB_API_LATEST).send().await {
        Ok(resp) if resp.status().is_success() => {
            if let Ok(v) = resp.json::<Value>().await {
                if let Some(info) = parse_github_release(&v) {
                    return Ok(Some(info));
                }
                eprintln!("[webminer] Latest GitHub release does not contain scent-miner.jar; trying OSS mirror ...");
            }
        }
        Ok(resp) => {
            if resp.status().as_u16() == 403 {
                eprintln!("[webminer] GitHub API rate limit exceeded; trying OSS mirror ...");
            } else {
                eprintln!(
                    "[webminer] GitHub API returned HTTP {}; trying OSS mirror ...",
                    resp.status()
                );
            }
        }
        Err(e) => {
            eprintln!("[webminer] Cannot reach GitHub API: {e}");
            eprintln!("[webminer] Trying OSS mirror ...");
        }
    }

    let client = async_client(30).await?;
    let resp = client
        .get(OSS_LATEST_JSON)
        .send()
        .await
        .map_err(|e| format!("Cannot reach GitHub or OSS mirror: {e}{}", proxy_hint()))?;
    if !resp.status().is_success() {
        return Err(format!("OSS mirror returned HTTP {}", resp.status()));
    }
    let v: Value = resp.json().await.map_err(|e| e.to_string())?;
    match parse_oss_release(&v) {
        Some(info) => Ok(Some(info)),
        None => Ok(None),
    }
}

fn oss_sha256_url(tag: &str) -> String {
    if tag == "latest" {
        format!("{OSS_LATEST_DOWNLOAD}/scent-miner.jar.sha256")
    } else {
        format!("{OSS_BASE_URL}/releases/download/{tag}/scent-miner.jar.sha256")
    }
}

/// Fetch the hex SHA-256 from the OSS `.sha256` sidecar (used when release
/// metadata carries no checksum).  `Ok(None)` when the sidecar is absent.
async fn fetch_oss_sha256(tag: &str) -> Result<Option<String>, String> {
    let client = async_client(30).await?;
    let resp = client
        .get(oss_sha256_url(tag))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Ok(None);
    }
    let text = resp.text().await.map_err(|e| e.to_string())?;
    let hash = text.trim();
    if hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit()) {
        Ok(Some(hash.to_string()))
    } else {
        Ok(None)
    }
}

/// Normalise a user-supplied version to a release tag (`0.0.7` → `v0.0.7`).
pub fn normalize_tag(version: &str) -> String {
    let version = version.trim();
    if version.starts_with('v') {
        version.to_string()
    } else {
        format!("v{version}")
    }
}

/// Fetch a specific release tag from GitHub, falling back to the OSS mirror.
pub async fn fetch_release_for_version(version: &str) -> Result<ReleaseInfo, String> {
    let tag = normalize_tag(version);
    let url = format!("https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/tags/{tag}");
    let client = async_client(30).await?;
    let github_ok = match client.get(&url).send().await {
        Ok(resp) if resp.status().is_success() => {
            if let Ok(v) = resp.json::<Value>().await {
                if let Some(info) = parse_github_release(&v) {
                    return Ok(info);
                }
                return Err(format!("Release '{tag}' does not contain scent-miner.jar"));
            }
            false
        }
        Ok(resp) if resp.status().as_u16() == 404 => false,
        Ok(resp) => {
            eprintln!(
                "[webminer] GitHub API returned HTTP {}; trying OSS mirror ...",
                resp.status()
            );
            false
        }
        Err(e) => {
            eprintln!("[webminer] Cannot reach GitHub API: {e}");
            eprintln!("[webminer] Trying OSS mirror ...");
            false
        }
    };
    if !github_ok {
        eprintln!("[webminer] Release '{tag}' not found on GitHub; trying OSS mirror ...");
    }
    let checksum = match fetch_oss_sha256(&tag).await {
        Ok(Some(h)) => Some(h),
        Ok(None) => {
            eprintln!("[webminer] Cannot fetch checksum from mirror; skipping verification.");
            None
        }
        Err(e) => {
            eprintln!("[webminer] Cannot fetch checksum from mirror: {e}");
            None
        }
    };
    let jar_url = format!("{OSS_BASE_URL}/releases/download/{tag}/scent-miner.jar");
    Ok(ReleaseInfo {
        tag_name: tag,
        published_at: None,
        jar_url,
        jar_size: None,
        jar_checksum: checksum,
    })
}

// ---------------------------------------------------------------------------
// Download + checksum
// ---------------------------------------------------------------------------

fn sha256_hex(path: &Path) -> Result<String, String> {
    let mut file =
        std::fs::File::open(path).map_err(|e| format!("Cannot read {}: {e}", path.display()))?;
    let mut hasher = Sha256::new();
    std::io::copy(&mut file, &mut hasher).map_err(|e| e.to_string())?;
    Ok(format!("{:x}", hasher.finalize()))
}

/// Download `url` to `target`, honouring proxy settings, returning the
/// number of bytes written.
///
/// The blocking reqwest client cannot be created/dropped inside an async
/// runtime, so the work is moved onto a blocking thread via
/// `tokio::task::spawn_blocking` (same pattern as `daemon::download_file`).
pub async fn download_file(url: &str, target: &Path) -> Result<u64, String> {
    let url = url.to_string();
    let target = target.to_path_buf();
    tokio::task::spawn_blocking(move || download_file_blocking(&url, &target))
        .await
        .map_err(|e| format!("Download task join failed: {e}"))?
}

fn download_file_blocking(url: &str, target: &Path) -> Result<u64, String> {
    if let Some(dir) = target.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let client = blocking_client(1800)?;
    let mut resp = client
        .get(url)
        .send()
        .map_err(|e| format!("Download failed: {e}\n  URL: {url}"))?;
    if !resp.status().is_success() {
        return Err(format!(
            "Download failed with status: {}\n  URL: {url}",
            resp.status()
        ));
    }
    let mut file = std::fs::File::create(target).map_err(|e| e.to_string())?;
    let mut total: u64 = 0;
    let mut buf = [0u8; 8192];
    loop {
        let n = resp.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
        total += n as u64;
    }
    file.flush().map_err(|e| e.to_string())?;
    Ok(total)
}

/// Download from a list of URLs, trying each in turn.  Returns the URL that
/// succeeded.
async fn download_with_fallback(urls: &[String], target: &Path) -> Result<String, String> {
    let mut last_error = String::new();
    for url in urls {
        match download_file(url, target).await {
            Ok(bytes) => {
                eprintln!(
                    "[webminer] Downloaded {:.1} MB from {url}",
                    bytes as f64 / 1_048_576.0
                );
                return Ok(url.clone());
            }
            Err(e) => {
                last_error = format!("{e}");
                eprintln!("[webminer] Download from {url} failed: {e}");
            }
        }
    }
    Err(format!("All download sources failed. Last error: {last_error}{}", proxy_hint()))
}

// ---------------------------------------------------------------------------
// Management commands
// ---------------------------------------------------------------------------

async fn install_release(release: &ReleaseInfo, force: bool) -> Result<InstallOutcome, String> {
    let root = install_root()?;
    let lib = root.join("lib");
    let jar = lib.join("scent-miner.jar");
    let version_file = root.join("version.txt");
    let checksum_file = root.join("checksum.sha256");

    let installed = read_version_file(&version_file);
    if installed.as_deref() == Some(release.tag_name.as_str()) && !force {
        eprintln!("[webminer] {} is already installed.", release.tag_name);
        return Ok(InstallOutcome {
            tag: release.tag_name.clone(),
            install_dir: root,
            reused_existing: true,
            source_url: release.jar_url.clone(),
        });
    }
    match &installed {
        Some(prev) if prev != &release.tag_name => {
            eprintln!(
                "[webminer] Upgrading from {prev} to {} ...",
                release.tag_name
            );
        }
        Some(_) => eprintln!("[webminer] Reinstalling {} ...", release.tag_name),
        None => eprintln!("[webminer] Installing {} ...", release.tag_name),
    }

    // Download with GitHub → OSS mirror fallback.
    let mut urls = vec![release.jar_url.clone()];
    if !release.jar_url.contains(OSS_BASE_URL) {
        let oss_url = if release.tag_name == "latest" {
            format!("{OSS_LATEST_DOWNLOAD}/scent-miner.jar")
        } else {
            format!(
                "{OSS_BASE_URL}/releases/download/{}/scent-miner.jar",
                release.tag_name
            )
        };
        urls.push(oss_url);
    }

    let temp_jar = std::env::temp_dir().join("scent-miner-download.jar");
    let _ = std::fs::remove_file(&temp_jar);
    let source_url = download_with_fallback(&urls, &temp_jar).await?;

    // Verify the SHA-256 when available; fetch the OSS sidecar as fallback.
    let mut expected = release.jar_checksum.clone();
    if expected.is_none() {
        expected = fetch_oss_sha256(&release.tag_name)
            .await
            .ok()
            .flatten();
    }
    match expected {
        Some(hash) => {
            let actual = sha256_hex(&temp_jar)?;
            if !actual.eq_ignore_ascii_case(&hash) {
                let _ = std::fs::remove_file(&temp_jar);
                return Err(format!(
                    "Checksum mismatch!\n  Expected sha256: {hash}\n  Actual   sha256: {actual}"
                ));
            }
            eprintln!("[webminer] SHA-256 verified.");
        }
        None => {
            eprintln!("[webminer] No checksum available; skipping verification.");
        }
    }

    // Move into place (fall back to copy+delete when rename crosses devices).
    std::fs::create_dir_all(&lib).map_err(|e| e.to_string())?;
    let moved = std::fs::rename(&temp_jar, &jar)
        .map_err(|e| e.to_string())
        .or_else(|_| {
            std::fs::copy(&temp_jar, &jar)
                .map(|_| ())
                .map_err(|e| e.to_string())
        });
    let _ = std::fs::remove_file(&temp_jar);
    moved?;

    // Record version + checksum.
    std::fs::write(&version_file, release.tag_name.as_bytes()).map_err(|e| e.to_string())?;
    if let Ok(actual) = sha256_hex(&jar) {
        let _ = std::fs::write(&checksum_file, actual.as_bytes());
    }

    eprintln!("[webminer] Installed {} to {}", release.tag_name, root.display());
    Ok(InstallOutcome {
        tag: release.tag_name.clone(),
        install_dir: root,
        reused_existing: false,
        source_url,
    })
}

/// Install the latest release, or a specific version tag when given.
pub async fn install(version: Option<&str>, force: bool) -> Result<InstallOutcome, String> {
    let release = match version {
        Some(v) => fetch_release_for_version(v).await?,
        None => match fetch_latest_release().await? {
            Some(r) => r,
            None => {
                return Err("Cannot find the latest release (no scent-miner.jar asset). Check your internet connection.".to_string());
            }
        },
    };
    install_release(&release, force).await
}

/// Update to the latest release when a newer one exists.
pub async fn update() -> Result<InstallOutcome, String> {
    let installed = read_installed_version()?;
    let Some(installed) = installed else {
        return Err("No webminer installation found. Run `browser4-cli webminer install` first.".to_string());
    };
    let latest = fetch_latest_release()
        .await?
        .ok_or_else(|| "Cannot check for updates: the latest release has no scent-miner.jar asset.".to_string())?;
    if installed == latest.tag_name {
        eprintln!("[webminer] Already up to date ({installed}).");
        return Ok(InstallOutcome {
            tag: latest.tag_name.clone(),
            install_dir: install_root()?,
            reused_existing: true,
            source_url: latest.jar_url.clone(),
        });
    }
    eprintln!("[webminer] Update available: {installed} → {}", latest.tag_name);
    install_release(&latest, true).await
}

/// Collect the current installation state plus the latest release info
/// (best-effort for the network part).
pub async fn version_status() -> VersionStatus {
    let install_dir = install_root().unwrap_or_else(|_| PathBuf::from(".scent/webminer"));
    let installed = read_version_file(&install_dir.join("version.txt"));
    let jar_bytes = installed_jar_path()
        .and_then(|p| std::fs::metadata(p).ok())
        .map(|m| m.len());
    let (latest, latest_error) = match fetch_latest_release().await {
        Ok(Some(info)) => (Some(info), None),
        Ok(None) => (
            None,
            Some("latest release has no scent-miner.jar asset".to_string()),
        ),
        Err(e) => (None, Some(e)),
    };
    VersionStatus {
        installed,
        install_dir,
        jar_bytes,
        latest,
        latest_error,
    }
}

/// Remove the installed release.  Returns the install root (which may not
/// have existed).
pub fn uninstall() -> Result<PathBuf, String> {
    let root = install_root()?;
    if root.is_dir() {
        std::fs::remove_dir_all(&root)
            .map_err(|e| format!("Failed to remove {}: {e}", root.display()))?;
    }
    Ok(root)
}

// ---------------------------------------------------------------------------
// run-example: sample dataset + full pipeline
// ---------------------------------------------------------------------------

/// Locate 7-Zip on PATH or in common install locations.
fn find_seven_zip() -> Option<PathBuf> {
    let names: &[&str] = if cfg!(windows) {
        &["7z.exe"]
    } else {
        &["7z", "7zz"]
    };
    let path_var = std::env::var("PATH").unwrap_or_default();
    let sep = if cfg!(windows) { ';' } else { ':' };
    for dir in path_var.split(sep) {
        if dir.is_empty() {
            continue;
        }
        for name in names {
            let candidate = Path::new(dir).join(name);
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    let common: &[&str] = if cfg!(windows) {
        &[
            r"C:\Program Files\7-Zip\7z.exe",
            r"D:\Program Files\7-Zip\7z.exe",
        ]
    } else {
        &["/usr/bin/7z", "/usr/bin/7zz", "/usr/local/bin/7z", "/usr/local/bin/7zz"]
    };
    common
        .iter()
        .map(PathBuf::from)
        .find(|p| p.is_file())
}

fn for_each_html_file(dir: &Path, f: &mut dyn FnMut(&Path)) {
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                for_each_html_file(&path, f);
            } else if path
                .extension()
                .map(|e| e == "html" || e == "htm")
                .unwrap_or(false)
            {
                f(&path);
            }
        }
    }
}

fn count_html_files(dir: &Path) -> usize {
    let mut count = 0;
    for_each_html_file(dir, &mut |_| count += 1);
    count
}

/// Download the sample dataset (cached at `~/.scent/test-data/amazon.com/`),
/// extract it with 7-Zip, then run the full pipeline on it.  Extra args are
/// forwarded to the JAR (e.g. `--k 8`).
pub async fn run_example(extra: &[String]) -> Result<i32, String> {
    let home = require_home()?;
    let extract_dir = home.join(".scent").join("test-data");
    let data_dir = extract_dir.join("amazon.com");

    if data_dir.is_dir() && count_html_files(&data_dir) > 0 {
        eprintln!("[webminer] Test dataset already present at: {}", data_dir.display());
    } else {
        let archive_url = format!("{OSS_BASE_URL}/test/amazon.com.7z");
        let archive_path = std::env::temp_dir().join("amazon.com.7z");
        if !archive_path.is_file()
            || std::fs::metadata(&archive_path)
                .map(|m| m.len() == 0)
                .unwrap_or(true)
        {
            eprintln!("[webminer] Downloading test dataset ...");
            eprintln!("[webminer] From: {archive_url}");
            download_file(&archive_url, &archive_path)
                .await
                .map_err(|e| format!("Failed to download the test dataset: {e}"))?;
        } else {
            eprintln!("[webminer] Archive already cached at: {}", archive_path.display());
        }

        let seven_zip = find_seven_zip().ok_or_else(|| {
            "7-Zip not found. Install from https://www.7-zip.org/ or ensure 7z is on PATH.".to_string()
        })?;
        eprintln!("[webminer] Extracting to {} ...", extract_dir.display());
        let _ = std::fs::remove_dir_all(&extract_dir);
        std::fs::create_dir_all(&extract_dir).map_err(|e| e.to_string())?;

        let status = Command::new(&seven_zip)
            .arg("x")
            .arg(&archive_path)
            .arg(format!("-o{}", extract_dir.display()))
            .arg("-y")
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .status()
            .map_err(|e| format!("Failed to run 7-Zip: {e}"))?;
        if !status.success() {
            return Err(format!(
                "7-Zip extraction failed (exit code: {})",
                status.code().unwrap_or(1)
            ));
        }
        let count = count_html_files(&data_dir);
        eprintln!("[webminer] Extracted {count} HTML files to {}", data_dir.display());
    }

    let mut forward = vec!["all".to_string(), data_dir.display().to_string()];
    forward.extend(extra.iter().cloned());
    run_pipeline(&forward)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn normalize_tag_adds_v_prefix() {
        assert_eq!(normalize_tag("0.0.7"), "v0.0.7");
        assert_eq!(normalize_tag("v0.0.7"), "v0.0.7");
        assert_eq!(normalize_tag(" 1.2.3 "), "v1.2.3");
    }

    #[test]
    fn github_release_parsing() {
        let v = json!({
            "tag_name": "v0.0.7",
            "published_at": "2025-01-01T00:00:00Z",
            "assets": [
                { "name": "README.md", "size": 10 },
                {
                    "name": "scent-miner.jar",
                    "size": 10485760,
                    "digest": "sha256:abc123",
                    "browser_download_url": "https://github.com/platonai/web-miner/releases/download/v0.0.7/scent-miner.jar"
                }
            ]
        });
        let info = parse_github_release(&v).expect("release should parse");
        assert_eq!(info.tag_name, "v0.0.7");
        assert_eq!(info.jar_url, "https://github.com/platonai/web-miner/releases/download/v0.0.7/scent-miner.jar");
        assert_eq!(info.jar_checksum.as_deref(), Some("abc123"));
        assert_eq!(info.jar_size, Some(10485760));
    }

    #[test]
    fn github_release_missing_jar_asset() {
        let v = json!({ "tag_name": "v0.0.7", "assets": [ { "name": "other.jar" } ] });
        assert!(parse_github_release(&v).is_none());
    }

    #[test]
    fn oss_release_parsing() {
        let v = json!({
            "tag": "v0.0.7",
            "published_at": "2025-01-01T00:00:00Z",
            "assets": [
                {
                    "name": "scent-miner.jar",
                    "size": 2097152,
                    "sha256": "def456"
                }
            ]
        });
        let info = parse_oss_release(&v).expect("release should parse");
        assert_eq!(info.tag_name, "v0.0.7");
        assert!(info.jar_url.contains("oss-cn-beijing"));
        assert!(info.jar_url.ends_with("/releases/download/v0.0.7/scent-miner.jar"));
        assert_eq!(info.jar_checksum.as_deref(), Some("def456"));
        assert_eq!(info.jar_size, Some(2097152));
    }

    #[test]
    fn sha256_hex_known_value() {
        let dir = std::env::temp_dir().join("browser4-cli-webminer-tests");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("checksum-input.txt");
        std::fs::write(&path, b"hello").unwrap();
        // sha256("hello") — well-known value
        assert_eq!(
            sha256_hex(&path).unwrap(),
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        );
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn run_jar_builds_expected_argv() {
        // Spot-check the JVM flag set mirrors the PowerShell launcher.
        assert!(MODULE_OPENS
            .iter()
            .any(|f| *f == "--add-opens=java.base/java.lang=ALL-UNNAMED"));
        assert!(MODULE_OPENS
            .iter()
            .any(|f| *f == "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"));
        assert_eq!(APP_NAME, "webminer");
    }

    #[test]
    fn install_root_layout_matches_launcher() {
        // Verify the relative layout of the install:
        // $HOME/.scent/webminer/lib/scent-miner.jar
        let root = install_root().expect("home dir should resolve");
        let comps: Vec<_> = root.components().collect();
        assert!(comps.len() >= 3);
        assert_eq!(comps[comps.len() - 2].as_os_str(), ".scent");
        assert_eq!(comps[comps.len() - 1].as_os_str(), "webminer");

        let jar = install_jar_path().expect("jar path should resolve");
        let jar_comps: Vec<_> = jar.components().collect();
        assert_eq!(jar_comps[jar_comps.len() - 2].as_os_str(), "lib");
        assert_eq!(jar_comps[jar_comps.len() - 1].as_os_str(), "scent-miner.jar");
    }
}
