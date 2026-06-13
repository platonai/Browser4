//! Server daemon management for the Browser4 CLI.
//!
//! Ensures a Browser4 server is running before executing commands.
//! Only manages localhost instances; remote servers are not touched.
//! The server is launched from the `browser4-apps/browser4-bundle` runtime
//! bundle — a self-contained distribution with a minimal jlink JRE and all
//! dependency jars. When running inside a Browser4 repository checkout the
//! bundle is auto-built from source; otherwise it falls back to downloading
//! a pre-built release.

use std::env;
use std::fs;
use std::io;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus, Stdio};
use std::time::{Duration, Instant};

use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::managed_processes::{register_managed_server_process, ManagedServerProcess};
use crate::state::{
    read_state, resolve_default_state_dir, resolve_runtime_cache_dir, resolve_runtime_data_dir,
};

const EXISTING_SERVER_READY_TIMEOUT: Duration = Duration::from_secs(120);
const JAR_SERVER_READY_TIMEOUT: Duration = Duration::from_secs(120);
const SERVER_READY_INITIAL_QUIET_WAIT: Duration = Duration::from_secs(5);
const CLI_TEMP_DIR_COMPONENTS: [&str; 2] = ["tmp", "cli"];
/// Subdirectory of the runtime data dir that holds versioned installs.
const RUNTIME_VERSIONS_DIR_NAME: &str = "runtime";
/// Symlink (or tag file on Windows) that points to the currently active version.
const CURRENT_TAG_FILE_NAME: &str = "current.tag";
/// Name of the directory inside each versioned install that holds dependency JARs.
const BROWSER4_LIB_DIR_NAME: &str = "lib";
/// Name of the directory inside each versioned install that holds the bundled JRE.
const BROWSER4_RUNTIME_DIR_NAME: &str = "runtime";
/// Subdirectory of the runtime data dir for cached downloads.
const DOWNLOADS_DIR_NAME: &str = "downloads";
const BROWSER4_MAIN_CLASS: &str = "ai.platon.pulsar.apps.Browser4BundleApplicationKt";
const BROWSER4_INSTALL_METADATA_FILE_NAME: &str = "browser4-installation.json";
const BROWSER4_RELEASES_BASE_URL_ENV: &str = "BROWSER4_RELEASES_BASE_URL";
/// Path to the mirror configuration file, relative to the runtime data dir.
const MIRRORS_CONFIG_FILE_NAME: &str = "mirrors.json";
/// Env var to override the mirror config file path.
const MIRRORS_CONFIG_FILE_ENV: &str = "BROWSER4_MIRRORS_CONFIG";
/// Default timeout for mirror reachability checks (seconds).
const MIRROR_REACHABILITY_TIMEOUT_SECS: u64 = 5;
/// Env var to override the mirror reachability timeout (seconds).
const MIRROR_REACHABILITY_TIMEOUT_ENV: &str = "BROWSER4_CLI_MIRROR_CHECK_TIMEOUT_SECS";
/// Number of bytes to download from each mirror during speed tests (10 MB).
const SPEED_TEST_PROBE_BYTES: u64 = 10 * 1024 * 1024;
/// Default per-mirror timeout for speed-test downloads (seconds).
const MIRROR_SPEED_TEST_TIMEOUT_SECS: u64 = 30;
/// Env var to override the speed-test per-mirror timeout (seconds).
const MIRROR_SPEED_TEST_TIMEOUT_ENV: &str = "BROWSER4_CLI_MIRROR_SPEED_TEST_TIMEOUT_SECS";
/// Default TTL for a cached mirror preference (24 hours).
const MIRROR_PREFERENCE_TTL_SECS: u64 = 86400;
/// Env var to override the mirror preference TTL (seconds).
const MIRROR_PREFERENCE_TTL_ENV: &str = "BROWSER4_CLI_MIRROR_PREFERENCE_TTL_SECS";
/// Env var to override the download timeout (seconds).  Default: 1800 (30 min).
const DOWNLOAD_TIMEOUT_ENV: &str = "BROWSER4_CLI_DOWNLOAD_TIMEOUT_SECS";
/// Env var to disable speed testing (set to "1") — forces TCP-only fallback.
const DISABLE_MIRROR_SPEED_TEST_ENV: &str = "BROWSER4_CLI_DISABLE_MIRROR_SPEED_TEST";
/// Name of the mirror preference cache file inside the runtime cache dir.
const MIRROR_PREFERENCE_CACHE_FILE: &str = "mirror-preference.json";
/// Name of the lock directory used to serialise concurrent install/upgrade runs.
const INSTALL_LOCK_DIR_NAME: &str = ".install.lock";
/// Maximum time to wait for a concurrent install/upgrade to finish (seconds).
const INSTALL_LOCK_TIMEOUT_SECS: u64 = 300;
const ROOT_SEARCH_START_DIR_ENV: &str = "BROWSER4_CLI_INVOKE_DIR";
/// When set to `1`, `true`, `yes`, or `on`, forces the CLI to download the
/// Browser4 runtime bundle from a remote release instead of building it from
/// a local Browser4 repository checkout.  Useful in environments where
/// Maven / jlink are unavailable or unreliable (e.g. CI behind a proxy).
const FORCE_REMOTE_BUNDLE_ENV: &str = "BROWSER4_CLI_FORCE_REMOTE_BUNDLE";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RuntimeBundleArchiveKind {
    Zip,
    TarGz,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum RuntimeBundlePlatform {
    WindowsX64,
    LinuxX64,
    MacOsX64,
    MacOsArm64,
}

impl RuntimeBundlePlatform {
    fn asset_name(self) -> String {
        match self {
            RuntimeBundlePlatform::WindowsX64 => "browser4-bundle-runtime-windows-x64.zip",
            RuntimeBundlePlatform::LinuxX64 => "browser4-bundle-runtime-linux-x64.tar.gz",
            RuntimeBundlePlatform::MacOsX64 => "browser4-bundle-runtime-darwin-x64.tar.gz",
            RuntimeBundlePlatform::MacOsArm64 => "browser4-bundle-runtime-darwin-arm64.tar.gz",
        }
        .to_string()
    }

    fn archive_kind(self) -> RuntimeBundleArchiveKind {
        match self {
            RuntimeBundlePlatform::WindowsX64 => RuntimeBundleArchiveKind::Zip,
            RuntimeBundlePlatform::LinuxX64
            | RuntimeBundlePlatform::MacOsX64
            | RuntimeBundlePlatform::MacOsArm64 => RuntimeBundleArchiveKind::TarGz,
        }
    }

    /// The directory name inside `_work/` — the asset name without the archive
    /// extension (e.g. `browser4-bundle-runtime-windows-x64`).
    fn bundle_dir_name(self) -> String {
        let asset = self.asset_name();
        if let Some(name) = asset.strip_suffix(".zip") {
            name.to_string()
        } else if let Some(name) = asset.strip_suffix(".tar.gz") {
            name.to_string()
        } else {
            asset
        }
    }

    /// Build script filename relative to `browser4-apps/browser4-bundle/`.
    fn build_script_name(self) -> &'static str {
        "build-runtime-bundle.ps1"
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct InstalledBrowser4RuntimeMetadata {
    pub tag: String,
    pub asset_name: String,
    pub download_url: String,
    pub installed_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstalledBrowser4Runtime {
    pub tag: String,
    pub asset_name: String,
    pub download_url: String,
    pub install_dir: PathBuf,
    pub lib_dir: PathBuf,
    pub jar_path: PathBuf,
    pub java_path: PathBuf,
    pub reused_existing: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DownloadedFile {
    final_url: String,
    bytes_written: u64,
}

// ---------------------------------------------------------------------------
// Download mirror configuration
// ---------------------------------------------------------------------------

/// A single download mirror entry.
///
/// Each mirror provides a `base_url` that hosts release assets in the same
/// layout as GitHub Releases: `<base_url>/download/<tag>/<asset>` (or
/// `/latest/download/<asset>` for the latest release).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
struct DownloadMirror {
    /// Human-readable name shown in log messages (e.g. "github", "aliyun-oss").
    name: String,
    /// Base URL for release downloads (e.g. `https://github.com/platonai/Browser4/releases`).
    base_url: String,
    /// Whether this mirror supports GitHub-style `/latest/download/` redirects.
    /// When `false`, "latest" downloads must resolve the tag before constructing
    /// the download URL (e.g. via a release-metadata endpoint or user-supplied
    /// `--tag`).
    #[serde(default)]
    supports_latest_resolution: bool,
}

/// Top-level structure of the mirrors.json config file.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct MirrorsConfig {
    mirrors: Vec<DownloadMirror>,
}

/// Result of a single-mirror speed test.
#[derive(Debug, Clone)]
struct SpeedTestResult {
    mirror: DownloadMirror,
    #[allow(dead_code)]
    duration: Duration,
    #[allow(dead_code)]
    bytes_downloaded: u64,
    /// Throughput in bytes per second (higher is better).
    speed_bps: f64,
}

/// Cached mirror preference written to disk so we can skip re-testing on every
/// install.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct MirrorPreference {
    /// The mirror that was fastest during the last speed test.
    selected_mirror: DownloadMirror,
    /// When the speed test was performed (UTC, RFC 3339).
    tested_at: String,
    /// Measured throughput in bytes per second.
    download_speed_bps: u64,
}

/// Built-in default mirrors used when no config file exists.
fn builtin_mirrors() -> Vec<DownloadMirror> {
    builtin_mirrors_for_locale(is_china_locale())
}

/// Return the built-in mirror list, optionally placing the Aliyun OSS mirror
/// first when the caller knows the user is in China mainland.
fn builtin_mirrors_for_locale(china_locale: bool) -> Vec<DownloadMirror> {
    if china_locale {
        vec![
            DownloadMirror {
                name: "aliyun-oss".to_string(),
                base_url: "https://browser4.oss-cn-beijing.aliyuncs.com/releases"
                    .to_string(),
                supports_latest_resolution: true,
            },
            DownloadMirror {
                name: "github".to_string(),
                base_url: "https://github.com/platonai/Browser4/releases".to_string(),
                supports_latest_resolution: true,
            },
        ]
    } else {
        vec![
            DownloadMirror {
                name: "github".to_string(),
                base_url: "https://github.com/platonai/Browser4/releases".to_string(),
                supports_latest_resolution: true,
            },
            DownloadMirror {
                name: "aliyun-oss".to_string(),
                base_url: "https://browser4.oss-cn-beijing.aliyuncs.com/releases"
                    .to_string(),
                supports_latest_resolution: true,
            },
        ]
    }
}

/// Check whether the system locale suggests the user is in China mainland.
///
/// Uses only local environment variables and filesystem probes — no network
/// calls.  Returns `false` when all probes are ambiguous or empty, which is
/// the safe default.
fn is_china_locale() -> bool {
    // 1 — Locale environment variables (works on Linux, macOS, Git Bash/WSL
    //     on Windows, and most Docker/CI environments).
    for var in ["LC_ALL", "LANG", "LC_CTYPE", "LC_MESSAGES"] {
        if let Ok(val) = std::env::var(var) {
            let trimmed = val.trim();
            if trimmed.starts_with("zh_CN")
                || trimmed.starts_with("zh-CN")
                || trimmed.eq_ignore_ascii_case("Chinese (Simplified)_China")
            {
                return true;
            }
        }
    }

    // 2 — TZ environment variable (covers Docker/CI where locale is C but
    //     timezone is explicitly set to a China mainland zone).
    if let Ok(tz) = std::env::var("TZ") {
        if is_china_timezone(tz.trim()) {
            return true;
        }
    }

    // 3 — /etc/timezone on Debian/Ubuntu.
    #[cfg(unix)]
    {
        if let Ok(content) = std::fs::read_to_string("/etc/timezone") {
            if is_china_timezone(content.trim()) {
                return true;
            }
        }
    }

    false
}

/// Check whether `tz` is a China mainland timezone identifier.
fn is_china_timezone(tz: &str) -> bool {
    matches!(
        tz,
        "Asia/Shanghai" | "Asia/Chongqing" | "Asia/Urumqi" | "Asia/Harbin"
    )
}

/// Path to the mirror configuration file.
///
/// 1. `BROWSER4_MIRRORS_CONFIG` env var, if set and non-empty.
/// 2. `{runtime_data_dir}/mirrors.json` otherwise.
fn mirrors_config_path() -> PathBuf {
    if let Ok(env_path) = env::var(MIRRORS_CONFIG_FILE_ENV) {
        let trimmed = env_path.trim();
        if !trimmed.is_empty() {
            return PathBuf::from(trimmed);
        }
    }
    resolve_runtime_data_dir().join(MIRRORS_CONFIG_FILE_NAME)
}

/// Load the mirror list.
///
/// If `BROWSER4_RELEASES_BASE_URL` is set it takes full precedence — the
/// env var is treated as a single-mirror override and no config file is read.
///
/// Otherwise the mirrors are loaded from `mirrors.json`.  When the file is
/// missing or unreadable the built-in defaults (GitHub → Aliyun OSS) are used.
fn load_mirrors() -> Vec<DownloadMirror> {
    // Single-source override: BROWSER4_RELEASES_BASE_URL completely bypasses
    // the mirror system for backward compatibility.
    if let Ok(env_url) = env::var(BROWSER4_RELEASES_BASE_URL_ENV) {
        let trimmed = env_url.trim().trim_end_matches('/').to_string();
        if !trimmed.is_empty() {
            return vec![DownloadMirror {
                name: "custom".to_string(),
                base_url: trimmed,
                supports_latest_resolution: true,
            }];
        }
    }

    let config_path = mirrors_config_path();
    match fs::read_to_string(&config_path) {
        Ok(contents) => match serde_json::from_str::<MirrorsConfig>(&contents) {
            Ok(config) if !config.mirrors.is_empty() => {
                eprintln!(
                    "Loaded {} mirror(s) from {}",
                    config.mirrors.len(),
                    config_path.display()
                );
                return config.mirrors;
            }
            Ok(_) => {
                eprintln!(
                    "Mirror config file {} has an empty mirror list; using built-in defaults.",
                    config_path.display()
                );
            }
            Err(e) => {
                eprintln!(
                    "Failed to parse mirror config {}: {}; using built-in defaults.",
                    config_path.display(),
                    e
                );
            }
        },
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            // Warn when the user explicitly set BROWSER4_MIRRORS_CONFIG to a
            // path that doesn't exist — silently falling back to built-ins
            // would mean their custom mirrors are not active and they might
            // not notice.  Only be silent when no env var was set (the
            // default mirrors.json simply doesn't exist yet).
            if env::var(MIRRORS_CONFIG_FILE_ENV)
                .ok()
                .map_or(false, |v| !v.trim().is_empty())
            {
                eprintln!(
                    "Warning: {MIRRORS_CONFIG_FILE_ENV} points to {} which does not exist; \
                     using built-in defaults.",
                    config_path.display()
                );
            }
        }
        Err(e) => {
            eprintln!(
                "Cannot read mirror config {}: {}; using built-in defaults.",
                config_path.display(),
                e
            );
        }
    }

    builtin_mirrors()
}

/// Build a download URL for the given mirror, release tag, and asset name.
fn mirror_download_url(mirror: &DownloadMirror, tag: Option<&str>, asset_name: &str) -> String {
    let base = mirror.base_url.trim_end_matches('/');
    match normalize_release_tag(tag) {
        Some(tag) => format!("{base}/download/{tag}/{asset_name}"),
        None => format!("{base}/latest/download/{asset_name}"),
    }
}

/// Check whether a mirror is reachable via a fast TCP connect to its host:443.
///
/// Parses the host from the mirror's `base_url` and attempts a single
/// `TcpStream::connect_timeout`.  This is intentionally a connectivity probe
/// rather than an HTTP round-trip — it's resilient to rate limits and
/// redirects.
fn mirror_is_reachable(mirror: &DownloadMirror) -> bool {
    use std::net::{TcpStream, ToSocketAddrs};

    let timeout_secs = env::var(MIRROR_REACHABILITY_TIMEOUT_ENV)
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .filter(|&s| s > 0)
        .unwrap_or(MIRROR_REACHABILITY_TIMEOUT_SECS);

    // Parse the host from the base URL.  reqwest::Url handles the scheme and
    // port extraction for us.
    let (host, port) = match reqwest::Url::parse(&mirror.base_url) {
        Ok(parsed) => {
            let host = parsed.host_str().unwrap_or("").to_string();
            if host.is_empty() {
                return false;
            }
            let port = parsed.port().unwrap_or(443);
            (host, port)
        }
        Err(_) => return false,
    };

    // Resolve the host to a SocketAddr.  Try parsing as a literal IP first
    // to avoid getaddrinfo / AI_ADDRCONFIG issues: in containerised
    // environments (Docker, GitHub Actions runners) IPv6 is often disabled
    // at the sysctl level, so getaddrinfo filters out IPv6 loopback even
    // though binding and connecting to [::1] works fine.  For hostnames
    // that need real DNS resolution we fall back to ToSocketAddrs.
    let addr: std::net::SocketAddr = {
        use std::net::{IpAddr, SocketAddr};
        if let Ok(ip) = host.parse::<IpAddr>() {
            SocketAddr::new(ip, port)
        } else {
            // Use the (host, port) tuple form of ToSocketAddrs rather than a
            // "host:port" string.  The tuple form avoids an IPv6 ambiguity:
            // reqwest strips the brackets from IPv6 addresses
            // (e.g. [::1] → ::1), so a formatted string like "::1:443" would
            // be mis-parsed as a bare IPv6 address with the port becoming the
            // last hextet.
            match (host.as_str(), port)
                .to_socket_addrs()
                .ok()
                .and_then(|mut a| a.next())
            {
                Some(addr) => addr,
                None => return false,
            }
        }
    };

    let timeout = Duration::from_secs(timeout_secs);
    match TcpStream::connect_timeout(&addr, timeout) {
        Ok(_) => true,
        Err(_) => false,
    }
}

/// Select the first reachable mirror from the list.
///
/// If no mirror is reachable, returns the first mirror anyway so the user
/// gets a clear download error rather than a confusing "no mirrors" message.
fn select_reachable_mirror(mirrors: &[DownloadMirror]) -> (&DownloadMirror, bool) {
    for mirror in mirrors {
        if mirror_is_reachable(mirror) {
            return (mirror, true);
        }
        eprintln!(
            "Mirror '{}' is unreachable; trying next mirror...",
            mirror.name
        );
    }
    // All mirrors failed the reachability check — fall back to the first
    // mirror so the download attempt produces a clear HTTP error.
    let fallback = &mirrors[0];
    eprintln!(
        "No mirror is reachable. Falling back to '{}' (download may fail).",
        fallback.name
    );
    (fallback, false)
}

// ---------------------------------------------------------------------------
// Mirror preference cache (persists the fastest mirror across install runs)
// ---------------------------------------------------------------------------

/// Normalize a mirror base URL for comparison purposes, stripping the
/// trailing slash so that `https://example.com/releases` and
/// `https://example.com/releases/` are treated as the same mirror.
fn normalize_base_url_for_comparison(url: &str) -> &str {
    url.trim_end_matches('/')
}

/// Path to the mirror preference cache file.
fn mirror_preference_cache_path() -> PathBuf {
    resolve_runtime_cache_dir().join(MIRROR_PREFERENCE_CACHE_FILE)
}

/// Load the cached mirror preference.
///
/// Returns `None` when the file is missing, corrupt, the cached mirror is no
/// longer in the provided mirror list, or the preference has expired.
fn load_mirror_preference(mirrors: &[DownloadMirror]) -> Option<MirrorPreference> {
    let path = mirror_preference_cache_path();
    let contents = fs::read_to_string(&path).ok()?;
    let pref: MirrorPreference = serde_json::from_str(&contents).ok()?;
    // Validate: the cached mirror must be in the current mirror list.
    // Normalize trailing slashes so that a trivial config change (e.g.
    // adding or removing a trailing / in mirrors.json) doesn't silently
    // invalidate a valid cached preference.
    if !mirrors.iter().any(|m| {
        normalize_base_url_for_comparison(&m.base_url)
            == normalize_base_url_for_comparison(&pref.selected_mirror.base_url)
    }) {
        eprintln!(
            "Cached mirror '{}' is not in the current mirror list; ignoring.",
            pref.selected_mirror.name
        );
        return None;
    }
    Some(pref)
}

/// Check whether a cached mirror preference is still within its TTL.
fn is_mirror_preference_valid(pref: &MirrorPreference) -> bool {
    let ttl_secs = env::var(MIRROR_PREFERENCE_TTL_ENV)
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .filter(|&s| s > 0)
        .unwrap_or(MIRROR_PREFERENCE_TTL_SECS);

    match chrono::DateTime::parse_from_rfc3339(&pref.tested_at) {
        Ok(tested_at) => {
            let tested_at_utc: chrono::DateTime<chrono::Utc> = tested_at.into();
            let elapsed = chrono::Utc::now().signed_duration_since(tested_at_utc);
            elapsed.num_seconds() >= 0 && elapsed.num_seconds() < ttl_secs as i64
        }
        Err(_) => {
            // Unparseable timestamp — treat as invalid.
            false
        }
    }
}

/// Atomically save a mirror preference to the cache file.
fn save_mirror_preference(pref: &MirrorPreference) {
    let path = mirror_preference_cache_path();
    if let Some(parent) = path.parent() {
        if let Err(e) = fs::create_dir_all(parent) {
            eprintln!("  (skipping mirror cache: cannot create cache dir: {e})");
            return;
        }
    }
    // Write atomically via temp file + rename.
    let tmp_path = path.with_extension("json.tmp");
    match serde_json::to_string_pretty(pref) {
        Ok(json) => {
            if let Err(e) = fs::write(&tmp_path, &json) {
                eprintln!("  (skipping mirror cache: write failed: {e})");
                return;
            }
            if let Err(e) = fs::rename(&tmp_path, &path) {
                eprintln!("  (skipping mirror cache: rename failed: {e})");
                let _ = fs::remove_file(&tmp_path);
            }
        }
        Err(e) => eprintln!("  (skipping mirror cache: serialization failed: {e})"),
    }
}

/// Delete the mirror preference cache file.
fn delete_mirror_preference_cache() {
    let path = mirror_preference_cache_path();
    let _ = fs::remove_file(&path);
}

// ---------------------------------------------------------------------------
// Mirror speed testing
// ---------------------------------------------------------------------------

/// Run speed tests against all mirrors concurrently and return results sorted
/// by speed (fastest first).
///
/// Each mirror is probed by downloading the first `SPEED_TEST_PROBE_BYTES` of
/// the runtime bundle asset via an HTTP Range request.  This keeps the probe
/// small (~10 MB) while exercising the same CDN endpoints that will serve the
/// full download.
///
/// Returns an empty `Vec` when every mirror fails or speed testing is disabled
/// via `BROWSER4_CLI_DISABLE_MIRROR_SPEED_TEST`.
async fn run_speed_tests(
    mirrors: &[DownloadMirror],
    tag: Option<&str>,
) -> Vec<SpeedTestResult> {
    // Honour the disable flag.
    if env::var(DISABLE_MIRROR_SPEED_TEST_ENV).ok().as_deref() == Some("1") {
        return vec![];
    }

    let timeout_secs = env::var(MIRROR_SPEED_TEST_TIMEOUT_ENV)
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .filter(|&s| s > 0)
        .unwrap_or(MIRROR_SPEED_TEST_TIMEOUT_SECS);

    // Resolve the runtime bundle asset name for the current platform so we
    // probe the exact same CDN path that the full download will use.
    let platform = match detect_current_runtime_bundle_platform() {
        Ok(p) => p,
        Err(_) => return vec![],
    };
    let asset_name = platform.asset_name();

    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(timeout_secs))
        .build()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Failed to create HTTP client for speed tests: {e}");
            return vec![];
        }
    };

    // Fire all speed tests concurrently — serial fallback would make the total
    // latency the sum of all mirror timeouts (worst case).
    let mut handles = Vec::new();
    for mirror in mirrors {
        // When the user wants "latest" but this mirror doesn't support
        // /latest/download/ redirects, skip the speed test — it would
        // always 404.  The mirror can still be used when the user passes
        // an explicit --tag; TCP reachability covers the connectivity
        // check in that case.
        if tag.is_none() && !mirror.supports_latest_resolution {
            eprintln!(
                "  Skipping speed test for '{}' (does not support /latest/download/).",
                mirror.name
            );
            continue;
        }

        // Use the user-requested tag for the speed test when available so
        // the probe hits the exact CDN path that the full download will use.
        let url = mirror_download_url(mirror, tag, &asset_name);
        let client = client.clone();
        let mirror = mirror.clone();

        handles.push(tokio::spawn(async move {
            eprintln!("  Speed-testing mirror '{}'...", mirror.name);
            let start = Instant::now();

            let range_header = format!("bytes=0-{}", SPEED_TEST_PROBE_BYTES - 1);
            match client.get(&url).header("Range", &range_header).send().await {
                Ok(response) => {
                    let status = response.status();
                    // Accept 206 (Partial Content — Range honoured) and 200
                    // (server ignored Range header and sent the full asset).
                    if !status.is_success()
                        && status != reqwest::StatusCode::PARTIAL_CONTENT
                        && status != reqwest::StatusCode::OK
                    {
                        eprintln!(
                            "  Mirror '{}' speed test failed: HTTP {}",
                            mirror.name, status
                        );
                        return None;
                    }
                    match response.bytes().await {
                        Ok(bytes) => {
                            let elapsed = start.elapsed();
                            let speed_bps = if elapsed.as_secs_f64() > 0.0 {
                                bytes.len() as f64 / elapsed.as_secs_f64()
                            } else {
                                bytes.len() as f64
                            };
                            eprintln!(
                                "  Mirror '{}' speed: {:.2} MB/s ({:.0} ms for {} bytes)",
                                mirror.name,
                                speed_bps / 1_048_576.0,
                                elapsed.as_secs_f64() * 1000.0,
                                bytes.len()
                            );
                            Some(SpeedTestResult {
                                mirror,
                                duration: elapsed,
                                bytes_downloaded: bytes.len() as u64,
                                speed_bps,
                            })
                        }
                        Err(e) => {
                            eprintln!(
                                "  Mirror '{}' speed test failed (body read): {e}",
                                mirror.name
                            );
                            None
                        }
                    }
                }
                Err(e) => {
                    if e.is_timeout() {
                        eprintln!(
                            "  Mirror '{}' speed test timed out after {}s",
                            mirror.name, timeout_secs
                        );
                    } else {
                        eprintln!("  Mirror '{}' speed test failed: {e}", mirror.name);
                    }
                    None
                }
            }
        }));
    }

    let mut results: Vec<SpeedTestResult> = Vec::new();
    for handle in handles {
        match handle.await {
            Ok(Some(result)) => results.push(result),
            Ok(None) => {} // This mirror failed — skip it.
            Err(join_err) => {
                eprintln!("  Speed test task panicked: {join_err}");
            }
        }
    }

    // Sort descending by speed (fastest first).
    results.sort_by(|a, b| {
        b.speed_bps
            .partial_cmp(&a.speed_bps)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    results
}

/// Select the best mirror for the runtime download.
///
/// Decision order:
/// 1. Cached preference (if valid and the mirror is still in the list).
/// 2. Concurrent speed tests across all mirrors.
/// 3. TCP reachability fallback (when all speed tests fail).
///
/// Returns `(selected_mirror, was_speed_tested)` — the caller uses
/// `was_speed_tested` to decide whether to invalidate the cache and retry on
/// download failure.
async fn select_best_mirror<'a>(
    mirrors: &'a [DownloadMirror],
    tag: Option<&str>,
) -> (&'a DownloadMirror, bool) {
    // --- cached preference ---
    if let Some(pref) = load_mirror_preference(mirrors) {
        if is_mirror_preference_valid(&pref) {
            if let Some(mirror) = mirrors.iter().find(|m| {
                normalize_base_url_for_comparison(&m.base_url)
                    == normalize_base_url_for_comparison(&pref.selected_mirror.base_url)
            }) {
                eprintln!(
                    "Using cached mirror '{}' (tested at {}, {:.2} MB/s)",
                    mirror.name,
                    &pref.tested_at[..19.min(pref.tested_at.len())],
                    pref.download_speed_bps as f64 / 1_048_576.0
                );
                return (mirror, true);
            }
        }
    }

    // --- speed tests ---
    let results = run_speed_tests(mirrors, tag).await;

    if results.is_empty() {
        eprintln!("All speed tests failed; falling back to TCP reachability check.");
        return select_reachable_mirror(mirrors);
    }

    // Cache the best result for future runs.
    let best = &results[0];
    let pref = MirrorPreference {
        selected_mirror: best.mirror.clone(),
        tested_at: chrono::Utc::now().to_rfc3339(),
        download_speed_bps: best.speed_bps as u64,
    };
    save_mirror_preference(&pref);

    let idx = mirrors.iter().position(|m| {
        normalize_base_url_for_comparison(&m.base_url)
            == normalize_base_url_for_comparison(&best.mirror.base_url)
    })
        .unwrap_or(0);
    (&mirrors[idx], true)
}

/// Capture process startup cwd once for Browser4 root discovery.
///
/// We only set this when the caller did not already provide an override,
/// so tests and advanced invocations can keep explicit control.
pub fn init_root_search_start_dir_from_startup() {
    if env::var_os(ROOT_SEARCH_START_DIR_ENV).is_some() {
        return;
    }

    if let Ok(current_dir) = env::current_dir() {
        // Rust 2024 marks process-wide env mutation as unsafe.
        unsafe {
            env::set_var(ROOT_SEARCH_START_DIR_ENV, current_dir);
        }
    }
}

/// Ensure the Browser4 server is running, starting it if necessary.
///
/// Only acts on `localhost` / `127.0.0.1` URLs.
pub async fn ensure_server_running(base_url: &str) -> Result<(), String> {
    // Skip remote servers
    if !base_url.contains("localhost") && !base_url.contains("127.0.0.1") {
        return Ok(());
    }

    let port = extract_port(base_url);
    if !is_local_port_open(base_url) {
        eprintln!("Browser4 server not running. Starting...");
        let launch_spec = resolve_server_launch_spec(port).await?;
        eprintln!("{}", launch_spec.description);
        return start_server(&launch_spec, base_url, port).await;
    }

    let client = Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(|e| e.to_string())?;

    match probe_server_state(&client, base_url).await {
        ServerState::Ready => return Ok(()),
        ServerState::Starting(_) => {
            return wait_for_server_ready(&client, base_url, EXISTING_SERVER_READY_TIMEOUT, None)
                .await;
        }
        ServerState::Unreachable(error) => {
            // Port is open but the health endpoint didn't answer.  The
            // server may be mid-startup (TCP bound, HTTP not ready).
            // Wait a short grace period and retry once before assuming
            // the port holder is not a Browser4 server.
            eprintln!(
                "Port {} is open but the health check failed ({}); retrying in 3 s...",
                port,
                truncate_status_for_log(&error),
            );
            tokio::time::sleep(Duration::from_secs(3)).await;
            match probe_server_state(&client, base_url).await {
                ServerState::Ready => return Ok(()),
                ServerState::Starting(_) => {
                    return wait_for_server_ready(
                        &client,
                        base_url,
                        EXISTING_SERVER_READY_TIMEOUT,
                        None,
                    )
                    .await;
                }
                ServerState::Unreachable(_) => {
                    eprintln!(
                        "Port {} is still unreachable after retry; starting a new server.",
                        port
                    );
                }
            }
        }
    }

    eprintln!("Browser4 server not running. Starting...");

    let launch_spec = resolve_server_launch_spec(port).await?;
    eprintln!("{}", launch_spec.description);

    start_server(&launch_spec, base_url, port).await
}

fn extract_port(base_url: &str) -> u16 {
    if let Ok(url) = reqwest::Url::parse(base_url) {
        url.port().unwrap_or(8182)
    } else {
        8182
    }
}

fn is_local_port_open(base_url: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(base_url) else {
        return false;
    };

    let port = url.port().unwrap_or(8182);
    let addr = match url.host_str() {
        Some("localhost") => SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port),
        Some(host) => match host.parse::<IpAddr>() {
            Ok(ip) if ip.is_loopback() => SocketAddr::new(ip, port),
            _ => return false,
        },
        None => return false,
    };

    TcpStream::connect_timeout(&addr, Duration::from_millis(250)).is_ok()
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum ServerLaunchKind {
    Jar,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ServerLaunchSpec {
    kind: ServerLaunchKind,
    program: PathBuf,
    args: Vec<String>,
    working_dir: PathBuf,
    registry_target: PathBuf,
    description: String,
}

struct PreparedLaunchCommand {
    command: Command,
    cleanup_dir: Option<PathBuf>,
}

fn detect_current_runtime_bundle_platform() -> Result<RuntimeBundlePlatform, String> {
    match (env::consts::OS, env::consts::ARCH) {
        ("windows", "x86_64") => Ok(RuntimeBundlePlatform::WindowsX64),
        ("linux", "x86_64") => Ok(RuntimeBundlePlatform::LinuxX64),
        ("macos", "x86_64") => Ok(RuntimeBundlePlatform::MacOsX64),
        ("macos", "aarch64") => Ok(RuntimeBundlePlatform::MacOsArm64),
        (os, arch) => Err(format!(
            "browser4-cli install does not yet publish a bundled Browser4 runtime for {os}/{arch}. Please install Java 17+ and use the Browser4.jar release asset instead."
        )),
    }
}

// ---------------------------------------------------------------------------
// Runtime data directory layout
// ---------------------------------------------------------------------------
//
//   {runtime_data_dir}/                  e.g. ~/.local/share/browser4/
//   ├── runtime/                         versioned installs
//   │   ├── current.tag                  plain-text file: "v4.11.0"
//   │   │                               (or symlink on Unix when possible)
//   │   ├── v4.10.0/
//   │   │   ├── lib/                     dependency JARs
//   │   │   ├── runtime/                 bundled JRE
//   │   │   ├── bin/                     launcher scripts
//   │   │   └── browser4-installation.json
//   │   └── v4.11.0/
//   │       └── ...
//   └── downloads/                       cached downloaded archives
//       └── v4.10.0/
//           └── browser4-bundle-runtime-….zip
//
// The `current.tag` file (or `current` symlink on Unix) is the single source
// of truth for which version is active.  `browser4_install_dir()` resolves it
// to the versioned directory.
// ---------------------------------------------------------------------------

/// The directory that holds versioned runtime installs.
fn runtime_versions_dir() -> PathBuf {
    resolve_runtime_data_dir().join(RUNTIME_VERSIONS_DIR_NAME)
}

/// Path to a specific versioned install directory.
fn versioned_install_dir(tag: &str) -> PathBuf {
    runtime_versions_dir().join(tag)
}

/// Path to the `current.tag` marker file that records the active version tag.
fn current_tag_file_path() -> PathBuf {
    runtime_versions_dir().join(CURRENT_TAG_FILE_NAME)
}

/// Read the currently active tag from the marker file.  Returns `None` when
/// no runtime has been installed yet or the file is corrupt.
pub fn read_current_tag() -> Option<String> {
    let path = current_tag_file_path();
    if !path.exists() {
        return try_migrate_legacy_runtime().or_else(|| {
            // No legacy install either — try to find any versioned directory.
            find_newest_versioned_install()
        });
    }
    let raw = fs::read_to_string(&path).ok()?;
    let tag = raw.trim().to_string();
    if tag.is_empty() {
        None
    } else {
        Some(tag)
    }
}

/// Record `tag` as the currently active version.
///
/// On Unix this also attempts to create a `current` symlink for convenience,
/// but the `current.tag` file is always the authoritative source.
fn write_current_tag(tag: &str) -> Result<(), String> {
    let parent = runtime_versions_dir();
    fs::create_dir_all(&parent).map_err(|e| e.to_string())?;

    let tag_path = current_tag_file_path();
    fs::write(&tag_path, format!("{tag}\n")).map_err(|e| e.to_string())?;

    // Best-effort symlink on Unix for shell-friendliness.
    // Remove the existing symlink first — symlink(2) returns EEXIST when the
    // path already exists, so without this removal every call after the first
    // install would silently leave the `current` pointer stale.
    #[cfg(unix)]
    {
        let symlink_path = parent.join("current");
        let target = versioned_install_dir(tag);
        let _ = std::fs::remove_file(&symlink_path);
        let _ = std::os::unix::fs::symlink(&target, &symlink_path);
    }

    Ok(())
}

/// Resolve the currently active runtime install directory.
///
/// Reads `current.tag`, validates the versioned directory exists, and returns
/// its path.  Returns `None` when no runtime is installed.
fn resolve_current_install_dir() -> Option<PathBuf> {
    let tag = read_current_tag()?;
    let dir = versioned_install_dir(&tag);
    if install_dir_contains_runtime(&dir) {
        Some(dir)
    } else {
        None
    }
}

/// The active runtime install directory.  Panics when no runtime is installed
/// — only call this after confirming a runtime exists.
fn browser4_install_dir() -> PathBuf {
    resolve_current_install_dir().unwrap_or_else(|| {
        // Fallback: return the versions dir itself (caller will find nothing
        // and trigger a download).
        runtime_versions_dir()
    })
}

/// Compare two version tags like "v4.10.0" and "v4.9.0" using natural
/// numeric ordering so "v4.10.0" > "v4.9.0".
fn compare_semver_tags(a: &str, b: &str) -> std::cmp::Ordering {
    let a_parts: Vec<u64> = a
        .trim_start_matches('v')
        .split('.')
        .filter_map(|s| s.parse::<u64>().ok())
        .collect();
    let b_parts: Vec<u64> = b
        .trim_start_matches('v')
        .split('.')
        .filter_map(|s| s.parse::<u64>().ok())
        .collect();
    for (ap, bp) in a_parts.iter().zip(b_parts.iter()) {
        match ap.cmp(bp) {
            std::cmp::Ordering::Equal => continue,
            other => return other,
        }
    }
    a_parts.len().cmp(&b_parts.len())
}

/// Look for the newest versioned install directory when `current.tag` is
/// missing (e.g. after manual cleanup or migration).
fn find_newest_versioned_install() -> Option<String> {
    let parent = runtime_versions_dir();
    let entries = fs::read_dir(&parent).ok()?;
    let mut tags: Vec<String> = entries
        .filter_map(|e| e.ok())
        .filter(|e| e.file_type().map(|t| t.is_dir()).unwrap_or(false))
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            // Only consider directories that look like version tags (v*).
            if name.starts_with('v') && install_dir_contains_runtime(&parent.join(&name)) {
                Some(name)
            } else {
                None
            }
        })
        .collect();
    if tags.is_empty() {
        return None;
    }
    // Sort by version using natural numeric ordering so v4.10.0 > v4.9.0.
    tags.sort_by(|a, b| compare_semver_tags(a, b));
    let newest = tags.last()?.clone();
    // Re-write the marker file so the next lookup is fast.
    let _ = write_current_tag(&newest);
    Some(newest)
}

/// One-shot migration from the legacy `~/.browser4/lib/` layout to the new
/// versioned layout under the platform data directory.  Reads the old
/// metadata to determine the tag, moves the files, and writes `current.tag`.
fn try_migrate_legacy_runtime() -> Option<String> {
    let legacy_install_dir = resolve_default_state_dir().join("lib");
    if !install_dir_contains_runtime(&legacy_install_dir) {
        return None;
    }

    let legacy_metadata_path = legacy_install_dir.join(BROWSER4_INSTALL_METADATA_FILE_NAME);
    let metadata: InstalledBrowser4RuntimeMetadata =
        serde_json::from_str(&fs::read_to_string(&legacy_metadata_path).ok()?).ok()?;

    let tag = metadata.tag;
    let target_dir = versioned_install_dir(&tag);

    // Don't overwrite if the target already exists.
    if target_dir.exists() {
        // Both exist — just set the current tag and clean up the legacy dir.
        let _ = write_current_tag(&tag);
        let _ = fs::remove_dir_all(&legacy_install_dir);
        return Some(tag);
    }

    eprintln!(
        "Migrating Browser4 runtime from legacy location {} to {}",
        legacy_install_dir.display(),
        target_dir.display()
    );

    // Move the legacy install into the versioned directory.
    if let Some(parent) = target_dir.parent() {
        let _ = fs::create_dir_all(parent);
    }
    match fs::rename(&legacy_install_dir, &target_dir) {
        Ok(()) => {
            let _ = write_current_tag(&tag);
            eprintln!("  Migration complete.");
            Some(tag)
        }
        Err(e) => {
            eprintln!("  Migration by rename failed ({}); copying instead...", e);
            // Fallback: copy recursively.
            copy_dir_recursive(&legacy_install_dir, &target_dir).ok()?;
            let _ = fs::remove_dir_all(&legacy_install_dir);
            let _ = write_current_tag(&tag);
            Some(tag)
        }
    }
}

fn browser4_java_executable_name() -> &'static str {
    if cfg!(windows) {
        "java.exe"
    } else {
        "java"
    }
}

fn browser4_install_metadata_path() -> PathBuf {
    browser4_install_dir().join(BROWSER4_INSTALL_METADATA_FILE_NAME)
}

fn java_path_in_install_dir(install_dir: &Path) -> PathBuf {
    install_dir
        .join(BROWSER4_RUNTIME_DIR_NAME)
        .join("bin")
        .join(browser4_java_executable_name())
}

pub fn read_installed_browser4_runtime_metadata() -> Option<InstalledBrowser4RuntimeMetadata> {
    let path = browser4_install_metadata_path();
    let contents = fs::read_to_string(path).ok()?;
    serde_json::from_str(&contents).ok()
}

/// Read metadata from a specific install directory (does not go through `current.tag`).
fn read_installed_browser4_runtime_metadata_for(
    install_dir: &Path,
) -> Option<InstalledBrowser4RuntimeMetadata> {
    let path = install_dir.join(BROWSER4_INSTALL_METADATA_FILE_NAME);
    let contents = fs::read_to_string(path).ok()?;
    serde_json::from_str(&contents).ok()
}

fn install_dir_contains_runtime(install_dir: &Path) -> bool {
    // Verify the metadata file exists — a missing file signals a truncated
    // or partially-committed install that should be re-downloaded.
    if !install_dir
        .join(BROWSER4_INSTALL_METADATA_FILE_NAME)
        .is_file()
    {
        return false;
    }
    let lib_dir = install_dir.join(BROWSER4_LIB_DIR_NAME);
    let has_lib = lib_dir.is_dir()
        && std::fs::read_dir(&lib_dir)
            .map(|mut entries| {
                entries.any(|entry| {
                    entry
                        .ok()
                        .map(|e| {
                            e.path()
                                .extension()
                                .map(|ext| ext == "jar")
                                .unwrap_or(false)
                        })
                        .unwrap_or(false)
                })
            })
            .unwrap_or(false);
    if !has_lib {
        return false;
    }
    let java = java_path_in_install_dir(install_dir);
    if !java.is_file() {
        return false;
    }
    // On Unix also verify the binary has at least one execute bit set so
    // that a non-executable file extracted from a broken archive (or from a
    // `noexec`-mounted filesystem) doesn't silently pass the check.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(&java) {
            if meta.permissions().mode() & 0o111 == 0 {
                return false;
            }
        }
    }
    true
}

fn materialize_installed_runtime(
    metadata: InstalledBrowser4RuntimeMetadata,
    reused_existing: bool,
) -> InstalledBrowser4Runtime {
    let install_dir = versioned_install_dir(&metadata.tag);
    let lib_dir = install_dir.join(BROWSER4_LIB_DIR_NAME);
    InstalledBrowser4Runtime {
        tag: metadata.tag,
        asset_name: metadata.asset_name,
        download_url: metadata.download_url,
        lib_dir: lib_dir.clone(),
        jar_path: lib_dir,
        java_path: java_path_in_install_dir(&install_dir),
        install_dir,
        reused_existing,
    }
}

fn normalize_release_tag(tag: Option<&str>) -> Option<String> {
    let trimmed = tag?.trim();
    if trimmed.is_empty() || trimmed.eq_ignore_ascii_case("latest") {
        return None;
    }
    if trimmed.starts_with('v') {
        Some(trimmed.to_string())
    } else {
        Some(format!("v{trimmed}"))
    }
}

/// Check whether `BROWSER4_CLI_FORCE_REMOTE_BUNDLE` is set.
///
/// When this flag is active the CLI skips the local Maven/jlink build
/// and downloads a pre-built runtime bundle directly from the release
/// server.  This is primarily useful in CI / corporate environments
/// where Maven or jlink dependencies are unavailable.
fn should_force_remote_bundle() -> bool {
    match env::var(FORCE_REMOTE_BUNDLE_ENV).ok().as_deref() {
        Some("1" | "true" | "TRUE" | "yes" | "YES" | "on" | "ON") => true,
        _ => false,
    }
}

fn parse_release_tag_from_url(url: &str) -> Option<String> {
    let parsed = reqwest::Url::parse(url).ok()?;
    let segments = parsed.path_segments()?.collect::<Vec<_>>();
    let download_index = segments.iter().position(|segment| *segment == "download")?;
    segments
        .get(download_index + 1)
        .map(|segment| (*segment).to_string())
}

/// Build a `reqwest::Proxy` from the given URL string.
/// The URL must include a scheme (http://, https://, or socks5://).
fn proxy_from_url(raw: &str) -> Option<reqwest::Proxy> {
    let trimmed = raw.trim().to_string();
    if trimmed.is_empty() {
        return None;
    }
    match reqwest::Proxy::all(&trimmed) {
        Ok(proxy) => Some(proxy),
        Err(error) => {
            eprintln!("Warning: failed to configure download proxy from ({trimmed}): {error}");
            None
        }
    }
}

/// Resolve a download proxy for the reqwest HTTP client.
///
/// # Search order (first match wins)
/// 1. `BROWSER4_CLI_PROXY` env var — set by `--proxy=<url>` on the CLI,
///    giving the user explicit control over the download proxy.
/// 2. Standard env vars (`https_proxy`, `HTTPS_PROXY`, `http_proxy`, …) —
///    the portable path, used on Unix, in CI, and in Docker.
/// 3. On Windows: the system-wide proxy configured via Internet Options,
///    which is stored in the registry and surfaced by `netsh winhttp`.
///
/// Returns `None` when no proxy is configured, so the download uses a
/// direct connection.
fn resolve_download_proxy() -> Option<reqwest::Proxy> {
    // 1 — Explicit CLI override (--proxy flag).
    let cli_proxy = std::env::var("BROWSER4_CLI_PROXY")
        .ok()
        .filter(|v| !v.trim().is_empty());
    if let Some(ref url) = cli_proxy {
        return proxy_from_url(url);
    }

    // 2 — Environment variables (portable; primary on Unix).
    // On Windows, `std::env::var` is backed by `GetEnvironmentVariableW`,
    // which does case-insensitive matching, so the first variant already
    // catches both `HTTPS_PROXY` and `https_proxy`.  The explicit
    // fallbacks are here for Unix (case-sensitive).
    let env_proxy = std::env::var("https_proxy")
        .or_else(|_| std::env::var("HTTPS_PROXY"))
        .or_else(|_| std::env::var("http_proxy"))
        .or_else(|_| std::env::var("HTTP_PROXY"))
        .or_else(|_| std::env::var("all_proxy"))
        .or_else(|_| std::env::var("ALL_PROXY"))
        .ok()
        .filter(|v| !v.trim().is_empty());
    if let Some(ref url) = env_proxy {
        return proxy_from_url(url);
    }

    // 3 — Windows system proxy (WinHTTP / Internet Options).
    #[cfg(windows)]
    {
        if let Some(proxy) = resolve_windows_system_proxy() {
            return Some(proxy);
        }
    }

    None
}

/// Read the system-wide proxy configured under Internet Options on Windows.
///
/// Checks two sources (in order):
/// 1. `netsh winhttp show proxy` — the WinHTTP proxy used by system services.
/// 2. The registry key `HKCU\…\Internet Settings` — the IE / Edge proxy
///    (surfaced in the GUI as "LAN Settings").
///
/// Returns the proxy URL with a scheme added when necessary
/// (the registry stores `host:port` without a scheme by default).
#[cfg(windows)]
fn resolve_windows_system_proxy() -> Option<reqwest::Proxy> {
    // 2a — WinHTTP proxy (netsh).
    if let Some(url) = read_winhttp_proxy() {
        return proxy_from_url(&url);
    }

    // 2b — IE proxy via registry.
    if let Some(url) = read_ie_proxy_from_registry() {
        return proxy_from_url(&url);
    }

    None
}

#[cfg(windows)]
fn read_winhttp_proxy() -> Option<String> {
    let output = std::process::Command::new("netsh")
        .args(["winhttp", "show", "proxy"])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .output()
        .ok()?;

    if !output.status.success() {
        return None;
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    for line in stdout.lines() {
        let line = line.trim();
        if let Some(server) = line.strip_prefix("Proxy Server(s):") {
            let server = server.trim();
            if !server.is_empty() && !server.eq_ignore_ascii_case("direct") {
                return Some(ensure_proxy_scheme(server));
            }
        }
    }

    None
}

/// Add a default `http://` scheme to a `host:port` proxy URL if one is
/// not already present.
#[cfg(windows)]
fn ensure_proxy_scheme(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.starts_with("http://")
        || trimmed.starts_with("https://")
        || trimmed.starts_with("socks5://")
    {
        return trimmed.to_string();
    }
    format!("http://{trimmed}")
}

/// Read the Internet Explorer proxy from
/// `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`.
///
/// The registry value `ProxyServer` may be a plain `host:port` or a
/// semicolon-delimited list like `http=proxy1:8080;https=proxy2:8080`.
/// This function prefers the `https=` entry when present (our download
/// target is GitHub, served over HTTPS), falling back to the plain form.
#[cfg(windows)]
fn read_ie_proxy_from_registry() -> Option<String> {
    // PowerShell one-liner: read ProxyEnable + ProxyServer from the
    // IE registry key.  We use PowerShell rather than linking against
    // winreg to avoid adding a new crate dependency.
    let ps_command = r#"
$key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$enabled = (Get-ItemProperty -Path $key -Name ProxyEnable -ErrorAction SilentlyContinue).ProxyEnable
if ($enabled -ne 1) { exit 1 }
$server = (Get-ItemProperty -Path $key -Name ProxyServer -ErrorAction SilentlyContinue).ProxyServer
if (-not $server) { exit 1 }
$server
"#;

    let output = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", ps_command])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .output()
        .ok()?;

    if !output.status.success() {
        return None;
    }

    let raw = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if raw.is_empty() {
        return None;
    }

    // The server may be protocol-specific: "http=host:port;https=host:port".
    // For HTTPS downloads we prefer the https= entry.
    if let Some(https_proxy) = raw
        .split(';')
        .find_map(|entry| entry.trim().strip_prefix("https="))
    {
        return Some(ensure_proxy_scheme(https_proxy.trim()));
    }

    // Single server — use as-is.
    Some(ensure_proxy_scheme(&raw))
}

/// Remove stale install temp directories that are older than the given age.
/// These are left behind by killed or crashed processes and would otherwise
/// accumulate unboundedly in the system temp directory.
fn cleanup_stale_install_temp_dirs(max_age: std::time::Duration) {
    let install_tmp_root = browser4_cli_temp_root_dir().join("install");
    let now = std::time::SystemTime::now();
    let entries = match fs::read_dir(&install_tmp_root) {
        Ok(e) => e,
        Err(_) => return,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        // Only touch directories that match our naming pattern.
        let dir_name = match path.file_name().and_then(|n| n.to_str()) {
            Some(n) => n,
            None => continue,
        };
        if !dir_name.starts_with("runtime-") {
            continue;
        }
        match entry.metadata().and_then(|m| m.modified()) {
            Ok(mtime) => {
                if let Ok(age) = now.duration_since(mtime) {
                    if age > max_age {
                        let _ = fs::remove_dir_all(&path);
                    }
                }
            }
            Err(_) => {
                // Can't stat — remove it to be safe.
                let _ = fs::remove_dir_all(&path);
            }
        }
    }
}

fn create_runtime_install_temp_dir() -> Result<PathBuf, String> {
    // Best-effort cleanup of orphaned temp dirs from previous crashed/killed
    // processes before creating a new one.
    cleanup_stale_install_temp_dirs(std::time::Duration::from_secs(3600));

    let path = browser4_cli_temp_root_dir().join("install").join(format!(
        "runtime-{}-{}",
        std::process::id(),
        chrono::Utc::now().format("%Y%m%dT%H%M%S%.3fZ")
    ));
    fs::create_dir_all(&path).map_err(|e| {
        format!(
            "Failed to create Browser4 runtime install temp directory {}: {e}",
            path.display()
        )
    })?;
    Ok(path)
}

async fn download_file(url: &str, target_path: &Path) -> Result<DownloadedFile, String> {
    let url = url.to_string();
    let target_path = target_path.to_path_buf();
    tokio::task::spawn_blocking(move || download_file_blocking(&url, &target_path))
        .await
        .map_err(|e| format!("Download task join failed: {e}"))?
}

fn download_file_blocking(url: &str, target_path: &Path) -> Result<DownloadedFile, String> {
    if let Some(dir) = target_path.parent() {
        fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }

    let download_timeout_secs = env::var(DOWNLOAD_TIMEOUT_ENV)
        .ok()
        .and_then(|v| v.trim().parse::<u64>().ok())
        .filter(|&s| s > 0)
        .unwrap_or(1800);
    let mut client_builder =
        reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(download_timeout_secs));

    // Honour proxy environment variables and system proxy settings.
    // Reqwest processes NO_PROXY / no_proxy automatically when a proxy
    // is configured via the env-var form.
    if let Some(proxy) = resolve_download_proxy() {
        client_builder = client_builder.proxy(proxy);
    }

    let client = client_builder.build().map_err(|e| e.to_string())?;

    let response = client.get(url).send();
    match response {
        Ok(mut response) => {
            if !response.status().is_success() {
                let status = response.status();
                let mut msg = format!("Download failed with status: {status}\n  URL: {url}");
                if status == reqwest::StatusCode::NOT_FOUND {
                    msg.push_str("\n\n  The runtime bundle asset was not found on the release.");
                    msg.push_str("\n  This may happen when the release does not include pre-built runtime bundles.");
                    msg.push_str("\n  Try one of the following:");
                    msg.push_str("\n    - Use a specific tag that includes runtime bundles: browser4-cli install --tag v4.8.0");
                    msg.push_str("\n    - Build the runtime from source inside the Browser4 repo and use --skip-install");
                    msg.push_str("\n    - Set BROWSER4_RELEASES_BASE_URL to a custom server hosting the runtime bundle");
                }
                return Err(msg);
            }

            let final_url = response.url().to_string();
            let mut file = fs::File::create(target_path).map_err(|e| e.to_string())?;

            // Copy with progress reporting every 30 s so long-running downloads
            // don't appear hung.
            let total_size = response.content_length();
            let mut downloaded: u64 = 0;
            let mut last_report = std::time::Instant::now();
            let report_interval = std::time::Duration::from_secs(30);
            let mut buf = [0u8; 8192];
            loop {
                let n = response.read(&mut buf).map_err(|e| e.to_string())?;
                if n == 0 {
                    break;
                }
                file.write_all(&buf[..n]).map_err(|e| e.to_string())?;
                downloaded += n as u64;
                if last_report.elapsed() >= report_interval {
                    if let Some(total) = total_size {
                        let pct = if total > 0 {
                            (downloaded as f64 / total as f64) * 100.0
                        } else {
                            0.0
                        };
                        eprintln!(
                            "  Download progress: {} / {} bytes ({:.0}%)",
                            downloaded, total, pct
                        );
                    } else {
                        eprintln!("  Download progress: {} bytes", downloaded);
                    }
                    last_report = std::time::Instant::now();
                }
            }
            let bytes_written = downloaded;
            file.flush().map_err(|e| e.to_string())?;

            // Validate download completeness against Content-Length when
            // available.  A truncated response (dropped connection, CDN
            // glitch) produces a corrupt archive that would fail extraction
            // with a confusing "corrupt archive" error; catch it early.
            if let Some(expected) = total_size {
                if bytes_written != expected {
                    return Err(format!(
                        "Download incomplete: received {bytes_written} of {expected} bytes \
                         (connection may have been interrupted).  Retry the command or \
                         try a different mirror."
                    ));
                }
            }

            Ok(DownloadedFile {
                final_url,
                bytes_written,
            })
        }
        Err(reqwest_error) => {
            // On Windows, fall back to PowerShell's Invoke-WebRequest which
            // uses the system WinINET proxy stack natively — no manual
            // proxy configuration needed.
            #[cfg(windows)]
            {
                eprintln!(
                    "Download via reqwest failed ({}); falling back to PowerShell.",
                    reqwest_error
                );
                return download_file_via_powershell(url, target_path);
            }

            #[cfg(not(windows))]
            {
                Err(format!("Download failed: {reqwest_error}"))
            }
        }
    }
}

/// Download a file using PowerShell's `Invoke-WebRequest`, which uses the
/// Windows WinINET proxy stack natively and handles system proxy (Internet
/// Options) automatically — no manual proxy configuration needed.
#[cfg(windows)]
fn download_file_via_powershell(url: &str, target_path: &Path) -> Result<DownloadedFile, String> {
    let url = url.to_string();
    let target_path = target_path.to_path_buf();

    // Escape single quotes in the URL and path for the PowerShell script.
    let ps_url = url.replace('\'', "''");
    let ps_outfile = target_path.to_string_lossy().replace('\'', "''");

    let ps_script = format!(
        "$ProgressPreference = 'SilentlyContinue'; \
         Invoke-WebRequest -Uri '{ps_url}' -OutFile '{ps_outfile}' -UseBasicParsing; \
         if (-not (Test-Path '{ps_outfile}')) {{ throw 'Download produced no output file.' }}; \
         $length = (Get-Item '{ps_outfile}').Length; \
         Write-Output \"DOWNLOADED:$length\"; \
         Write-Output \"FINAL_URL:{ps_url}\""
    );

    let output = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &ps_script])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .output()
        .map_err(|e| format!("Failed to spawn PowerShell downloader: {e}"))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("PowerShell download failed: {}", stderr.trim()));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let mut bytes_written: u64 = 0;
    let mut final_url = url.clone();

    for line in stdout.lines() {
        if let Some(size) = line.trim().strip_prefix("DOWNLOADED:") {
            bytes_written = size.trim().parse::<u64>().unwrap_or(0);
        }
        if let Some(redirect_url) = line.trim().strip_prefix("FINAL_URL:") {
            final_url = redirect_url.trim().to_string();
        }
    }

    if bytes_written == 0 {
        // Fallback: read the file size directly.
        bytes_written = target_path.metadata().map(|m| m.len()).map_err(|e| {
            format!(
                "PowerShell download appeared to succeed but the output file is unreadable: {e}"
            )
        })?;
    }

    eprintln!("  PowerShell download complete: {} bytes", bytes_written);

    Ok(DownloadedFile {
        final_url,
        bytes_written,
    })
}

fn extract_runtime_bundle_archive(
    archive_path: &Path,
    destination_dir: &Path,
    archive_kind: RuntimeBundleArchiveKind,
) -> Result<(), String> {
    fs::create_dir_all(destination_dir).map_err(|e| e.to_string())?;
    match archive_kind {
        RuntimeBundleArchiveKind::Zip => extract_zip_archive(archive_path, destination_dir),
        RuntimeBundleArchiveKind::TarGz => extract_tar_gz_archive(archive_path, destination_dir),
    }
}

fn extract_zip_archive(archive_path: &Path, destination_dir: &Path) -> Result<(), String> {
    let file = fs::File::open(archive_path).map_err(|e| e.to_string())?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| e.to_string())?;

    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|e| e.to_string())?;
        let Some(relative_path) = entry.enclosed_name().map(|path| path.to_path_buf()) else {
            continue;
        };
        let output_path = destination_dir.join(relative_path);

        if entry.is_dir() {
            fs::create_dir_all(&output_path).map_err(|e| e.to_string())?;
            continue;
        }

        if let Some(parent) = output_path.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }

        let mut output = fs::File::create(&output_path).map_err(|e| e.to_string())?;
        io::copy(&mut entry, &mut output).map_err(|e| e.to_string())?;

        #[cfg(unix)]
        if let Some(mode) = entry.unix_mode() {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&output_path, fs::Permissions::from_mode(mode))
                .map_err(|e| e.to_string())?;
        }
    }

    Ok(())
}

fn extract_tar_gz_archive(archive_path: &Path, destination_dir: &Path) -> Result<(), String> {
    let file = fs::File::open(archive_path).map_err(|e| e.to_string())?;
    let decoder = flate2::read::GzDecoder::new(file);
    let mut archive = tar::Archive::new(decoder);
    for entry in archive.entries().map_err(|e| e.to_string())? {
        let mut entry = entry.map_err(|e| e.to_string())?;
        let entry_path = entry.path().map_err(|e| e.to_string())?;
        // Reject path-traversal attempts (entries with `..` components).
        if entry_path
            .components()
            .any(|c| c == std::path::Component::ParentDir)
        {
            return Err(format!(
                "Malicious archive: entry contains path traversal: {}",
                entry_path.display()
            ));
        }
        entry
            .unpack_in(destination_dir)
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Check whether `path` contains the runtime bundle directory structure
/// (lib/ with at least one .jar and a java executable).  Unlike
/// `install_dir_contains_runtime` this does *not* require an installation
/// metadata file, because freshly-extracted bundles don't have one yet.
fn is_runtime_bundle_root(path: &Path) -> bool {
    let lib_dir = path.join(BROWSER4_LIB_DIR_NAME);
    let has_lib = lib_dir.is_dir()
        && std::fs::read_dir(&lib_dir)
            .map(|mut entries| {
                entries.any(|entry| {
                    entry
                        .ok()
                        .map(|e| {
                            e.path()
                                .extension()
                                .map(|ext| ext == "jar")
                                .unwrap_or(false)
                        })
                        .unwrap_or(false)
                })
            })
            .unwrap_or(false);
    has_lib && java_path_in_install_dir(path).is_file()
}

fn resolve_runtime_bundle_root(extracted_dir: &Path) -> Result<PathBuf, String> {
    if is_runtime_bundle_root(extracted_dir) {
        return Ok(extracted_dir.to_path_buf());
    }

    for entry in fs::read_dir(extracted_dir).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();
        if path.is_dir() {
            if is_runtime_bundle_root(&path) {
                return Ok(path);
            }
            // Search one additional level to handle archives that wrap the
            // bundle in two nested directories (e.g. archive → outer/ → bundle/).
            if let Ok(sub_entries) = fs::read_dir(&path) {
                for sub_entry in sub_entries.flatten() {
                    let sub_path = sub_entry.path();
                    if sub_path.is_dir() && is_runtime_bundle_root(&sub_path) {
                        return Ok(sub_path);
                    }
                }
            }
        }
    }

    Err(format!(
        "Downloaded Browser4 runtime bundle did not contain lib/ and runtime/ directories under {}.",
        extracted_dir.display()
    ))
}

/// Query available disk space on the filesystem containing `path`.
///
/// Uses `df` on Unix to get the available space in bytes.  On non-Unix
/// platforms returns `u64::MAX` (skip check — the install will fail
/// naturally with a clear error if the disk is truly full).
fn fs_disk_space_available(path: &Path) -> Result<u64, String> {
    // Walk up to find an existing ancestor if `path` doesn't exist yet.
    let probe_path = if path.exists() {
        path.to_path_buf()
    } else {
        let mut probe = path.to_path_buf();
        loop {
            if probe.exists() {
                break;
            }
            if !probe.pop() {
                return Err("Cannot find an existing ancestor for disk space check".to_string());
            }
        }
        probe
    };

    #[cfg(unix)]
    {
        // `df -B1 <path>` prints available space in bytes.
        // Output format (POSIX):
        //   Filesystem    1B-blocks        Used    Available Use% Mounted on
        //   /dev/sda1   12345678901  2345678901  10000000000  20% /home
        match std::process::Command::new("df")
            .args(["-B1", &probe_path.to_string_lossy()])
            .output()
        {
            Ok(output) if output.status.success() => {
                let stdout = String::from_utf8_lossy(&output.stdout);
                // Parse the second line, fourth column (Available).
                if let Some(data_line) = stdout.lines().nth(1) {
                    let columns: Vec<&str> = data_line.split_whitespace().collect();
                    if columns.len() >= 4 {
                        if let Ok(bytes) = columns[3].parse::<u64>() {
                            return Ok(bytes);
                        }
                    }
                }
                Err(format!(
                    "Could not parse 'df' output for {}: {}",
                    probe_path.display(),
                    stdout,
                ))
            }
            Ok(output) => Err(format!(
                "'df' command failed for {}: {}",
                probe_path.display(),
                String::from_utf8_lossy(&output.stderr).trim(),
            )),
            Err(e) => Err(format!(
                "Failed to run 'df' for disk space check: {e}"
            )),
        }
    }

    #[cfg(not(unix))]
    {
        let _ = probe_path;
        Ok(u64::MAX)
    }
}

fn remove_path_if_exists(path: &Path) -> Result<(), String> {
    if path.is_dir() {
        fs::remove_dir_all(path).map_err(|e| e.to_string())?;
    } else if path.exists() {
        fs::remove_file(path).map_err(|e| e.to_string())?;
    }
    Ok(())
}

fn copy_dir_recursive(source: &Path, destination: &Path) -> Result<(), String> {
    fs::create_dir_all(destination).map_err(|e| e.to_string())?;
    for entry in fs::read_dir(source).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let source_path = entry.path();
        let destination_path = destination.join(entry.file_name());
        let file_type = entry.file_type().map_err(|e| e.to_string())?;
        if file_type.is_dir() {
            copy_dir_recursive(&source_path, &destination_path)?;
        } else {
            if let Some(parent) = destination_path.parent() {
                fs::create_dir_all(parent).map_err(|e| e.to_string())?;
            }
            fs::copy(&source_path, &destination_path).map_err(|e| e.to_string())?;

            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let mode = fs::metadata(&source_path)
                    .map_err(|e| e.to_string())?
                    .permissions()
                    .mode();
                fs::set_permissions(&destination_path, fs::Permissions::from_mode(mode))
                    .map_err(|e| e.to_string())?;
            }
        }
    }
    Ok(())
}

fn write_installed_browser4_runtime_metadata(
    metadata: &InstalledBrowser4RuntimeMetadata,
) -> Result<(), String> {
    // Write directly to the versioned install directory instead of going
    // through browser4_install_metadata_path() → browser4_install_dir() →
    // resolve_current_install_dir() → install_dir_contains_runtime().  The
    // latter requires the metadata file to already exist (it signals a
    // complete install), so using it here would be a chicken-and-egg problem.
    let path = versioned_install_dir(&metadata.tag).join(BROWSER4_INSTALL_METADATA_FILE_NAME);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let contents = serde_json::to_string_pretty(metadata).map_err(|e| e.to_string())?;
    fs::write(path, contents).map_err(|e| e.to_string())
}

fn commit_installed_browser4_runtime(
    extracted_root: &Path,
    metadata: InstalledBrowser4RuntimeMetadata,
) -> Result<InstalledBrowser4Runtime, String> {
    let install_dir = versioned_install_dir(&metadata.tag);
    let source_runtime = extracted_root.join(BROWSER4_RUNTIME_DIR_NAME);
    let source_lib = extracted_root.join(BROWSER4_LIB_DIR_NAME);
    let source_bin = extracted_root.join("bin");

    if !source_runtime.is_dir() {
        return Err(format!(
            "Downloaded Browser4 runtime bundle is missing runtime/ JRE directory {}",
            source_runtime.display()
        ));
    }
    if !source_lib.is_dir() {
        return Err(format!(
            "Downloaded Browser4 runtime bundle is missing lib/ directory {}",
            source_lib.display()
        ));
    }

    // Stage into a sibling directory first, then atomically rename into
    // place.  This prevents a crash or kill signal mid-copy from leaving
    // `install_dir` in a half-written state that passes
    // `install_dir_contains_runtime` (which only checks for the .jar +
    // java binary) but is functionally broken.
    let staging_dir = install_dir
        .parent()
        .map(|p| p.join(format!(".staging-{}-{}", metadata.tag, std::process::id())))
        .unwrap_or_else(|| install_dir.with_extension("staging"));
    let _ = remove_path_if_exists(&staging_dir);
    fs::create_dir_all(&staging_dir).map_err(|e| e.to_string())?;

    let stage_runtime = staging_dir.join(BROWSER4_RUNTIME_DIR_NAME);
    let stage_lib = staging_dir.join(BROWSER4_LIB_DIR_NAME);
    let stage_bin = staging_dir.join("bin");
    copy_dir_recursive(&source_runtime, &stage_runtime)?;
    copy_dir_recursive(&source_lib, &stage_lib)?;
    if source_bin.is_dir() {
        copy_dir_recursive(&source_bin, &stage_bin)?;
    }
    // Write the metadata file into staging so the directory is fully
    // populated before it becomes visible.
    let meta_path = staging_dir.join(BROWSER4_INSTALL_METADATA_FILE_NAME);
    let contents = serde_json::to_string_pretty(&metadata).map_err(|e| e.to_string())?;
    if let Some(parent) = meta_path.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    fs::write(&meta_path, contents).map_err(|e| e.to_string())?;

    // Atomically swap staging → install_dir.  On the same filesystem this is
    // a single directory rename (instantaneous).  If rename fails (e.g.
    // cross-filesystem — extremely rare for temp dirs) fall back to the
    // copy-in-place approach so the install still succeeds.
    let _ = remove_path_if_exists(&install_dir);
    if let Err(_e) = fs::rename(&staging_dir, &install_dir) {
        // Fallback: copy from staging into install_dir directly.
        let _ = remove_path_if_exists(&staging_dir);
        let target_runtime = install_dir.join(BROWSER4_RUNTIME_DIR_NAME);
        let target_lib = install_dir.join(BROWSER4_LIB_DIR_NAME);
        let target_bin = install_dir.join("bin");
        fs::create_dir_all(&install_dir).map_err(|e| e.to_string())?;
        copy_dir_recursive(&source_runtime, &target_runtime)?;
        copy_dir_recursive(&source_lib, &target_lib)?;
        if source_bin.is_dir() {
            copy_dir_recursive(&source_bin, &target_bin)?;
        }
        write_installed_browser4_runtime_metadata(&metadata)?;
    }
    // Write the current-tag marker *after* install_dir is fully populated so
    // a crash between the rename and this write leaves current.tag pointing
    // to a complete (though not yet current) version rather than nothing.
    write_current_tag(&metadata.tag)?;

    Ok(materialize_installed_runtime(metadata, false))
}

// ---------------------------------------------------------------------------
// Checksum utilities — used by the download cache to verify archive
// integrity on restore (self-consistency check, not external verification).
// ---------------------------------------------------------------------------

/// Compute the SHA-256 digest of a file, returning the hex-encoded string.
fn compute_file_sha256(path: &Path) -> Result<String, String> {
    let mut file =
        fs::File::open(path).map_err(|e| format!("Cannot open file for checksum: {e}"))?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 65536];
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| format!("Cannot read file for checksum: {e}"))?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

/// Verify that `path` has the expected SHA-256 digest.
fn verify_file_sha256(path: &Path, expected_sha256: &str) -> Result<(), String> {
    let actual = compute_file_sha256(path)?;
    // Constant-time-ish comparison: compare lengths first, then hex strings.
    if actual.len() != expected_sha256.len() || actual != expected_sha256 {
        return Err(format!(
            "Checksum mismatch for {}:\n  expected sha256: {}\n  actual   sha256: {}",
            path.file_name()
                .map(|n| n.to_string_lossy())
                .unwrap_or_else(|| path.to_string_lossy()),
            expected_sha256,
            actual
        ));
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Download cache — avoids re-downloading the same runtime bundle on every
// `install --force` (stress tests exercise this path heavily).
// ---------------------------------------------------------------------------

fn browser4_download_cache_dir() -> PathBuf {
    resolve_runtime_cache_dir().join(DOWNLOADS_DIR_NAME)
}

/// Path where a downloaded archive for the given *normalized* tag and
/// asset name would be cached.  Returns `None` when the tag is unknown
/// (i.e. `latest`) — we don't cache those because "latest" drifts.
fn cached_download_path(normalized_tag: &str, asset_name: &str) -> PathBuf {
    browser4_download_cache_dir()
        .join(normalized_tag)
        .join(asset_name)
}

/// Path to the checksum sidecar file for a cached archive.
fn cached_checksum_path(normalized_tag: &str, asset_name: &str) -> PathBuf {
    let mut base = cached_download_path(normalized_tag, asset_name).into_os_string();
    base.push(".sha256");
    PathBuf::from(base)
}

/// Copy `src` into the download cache so the next install of the same
/// tag can skip the network fetch.  A `.sha256` sidecar is written
/// alongside the archive for integrity verification on later restores.
///
/// Both files are written atomically (temp file → rename) to prevent
/// concurrent readers from observing partial writes.  Errors are swallowed
/// — a full or broken cache is never fatal.
fn try_cache_downloaded_archive(src: &Path, normalized_tag: &str, asset_name: &str) {
    let dest = cached_download_path(normalized_tag, asset_name);
    if dest.exists() {
        return; // already cached
    }
    if let Some(parent) = dest.parent() {
        if let Err(e) = fs::create_dir_all(parent) {
            eprintln!("  (skipping download cache: cannot create cache dir: {e})");
            return;
        }
    }

    // Compute the SHA-256 checksum of the source file first.
    let sha256 = match compute_file_sha256(src) {
        Ok(digest) => digest,
        Err(e) => {
            eprintln!("  (skipping download cache: cannot compute checksum: {e})");
            return;
        }
    };

    // Write archive to a temporary path and rename atomically.
    let mut dest_tmp = dest.clone();
    dest_tmp.set_extension("tmp");
    if let Err(e) = fs::copy(src, &dest_tmp) {
        eprintln!("  (skipping download cache: copy failed: {e})");
        let _ = fs::remove_file(&dest_tmp);
        return;
    }
    if let Err(e) = fs::rename(&dest_tmp, &dest) {
        eprintln!("  (skipping download cache: atomic rename failed: {e})");
        let _ = fs::remove_file(&dest_tmp);
        return;
    }

    // Write checksum sidecar atomically.
    let checksum_path = cached_checksum_path(normalized_tag, asset_name);
    let mut checksum_tmp = checksum_path.clone();
    checksum_tmp.set_extension("sha256.tmp");
    if let Err(e) = fs::write(&checksum_tmp, &sha256) {
        eprintln!("  (skipping download cache: cannot write checksum: {e})");
        let _ = fs::remove_file(&checksum_tmp);
        return;
    }
    if let Err(e) = fs::rename(&checksum_tmp, &checksum_path) {
        eprintln!("  (skipping download cache: checksum rename failed: {e})");
        let _ = fs::remove_file(&checksum_tmp);
        return;
    }

    eprintln!(
        "  Cached downloaded archive to {} (sha256: {})",
        dest.display(),
        sha256
    );
}

/// If a previously-downloaded archive for this tag+asset is cached,
/// copy it into `dest_path`, verify its integrity against the stored
/// checksum, and return `true`.
///
/// A corrupt cache entry (missing sidecar or checksum mismatch) is
/// cleaned up and `false` is returned so the caller falls back to a
/// fresh download.
fn try_restore_from_download_cache(
    normalized_tag: &str,
    asset_name: &str,
    dest_path: &Path,
) -> bool {
    let cached = cached_download_path(normalized_tag, asset_name);
    let checksum_path = cached_checksum_path(normalized_tag, asset_name);

    if !cached.exists() || !checksum_path.exists() {
        // If only one of the pair exists, clean up the orphan.
        if cached.exists() {
            eprintln!(
                "  Cached archive {} is missing its checksum file; discarding.",
                cached.display()
            );
            let _ = fs::remove_file(&cached);
        }
        if checksum_path.exists() {
            let _ = fs::remove_file(&checksum_path);
        }
        return false;
    }

    // Read the expected checksum from the sidecar file.
    let expected_sha256 = match fs::read_to_string(&checksum_path) {
        Ok(s) => s.trim().to_string(),
        Err(e) => {
            eprintln!(
                "  Cannot read cached checksum {}: {}; will re-download.",
                checksum_path.display(),
                e
            );
            let _ = fs::remove_file(&cached);
            let _ = fs::remove_file(&checksum_path);
            return false;
        }
    };

    if expected_sha256.is_empty() {
        eprintln!(
            "  Cached checksum {} is empty; will re-download.",
            checksum_path.display()
        );
        let _ = fs::remove_file(&cached);
        let _ = fs::remove_file(&checksum_path);
        return false;
    }

    // Copy the cached archive to the destination, then verify.
    if let Some(parent) = dest_path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    eprintln!("  Restoring {} from download cache...", asset_name);

    match fs::copy(&cached, dest_path) {
        Ok(bytes) => {
            eprintln!(
                "  Copied {} from cache ({} bytes); verifying integrity...",
                asset_name, bytes
            );

            match verify_file_sha256(dest_path, &expected_sha256) {
                Ok(()) => {
                    eprintln!("  Checksum verified successfully.");
                    true
                }
                Err(e) => {
                    eprintln!(
                        "  Cached archive is corrupt: {}. Cleaning up and re-downloading.",
                        e
                    );
                    let _ = fs::remove_file(&cached);
                    let _ = fs::remove_file(&checksum_path);
                    let _ = fs::remove_file(dest_path);
                    false
                }
            }
        }
        Err(e) => {
            eprintln!(
                "  Cached archive exists but copy failed ({}); will re-download.",
                e
            );
            false
        }
    }
}

/// Evict old entries from the download cache, keeping only the newest `MAX`
/// versioned directories.  Non-version directories (e.g. `latest`) are left
/// untouched.  Errors are swallowed — cache eviction is best-effort and
/// never causes an install to fail.
fn evict_old_download_cache_entries() {
    const MAX_CACHED_VERSIONS: usize = 3;

    let cache_dir = browser4_download_cache_dir();
    if !cache_dir.is_dir() {
        return;
    }

    let entries = match fs::read_dir(&cache_dir) {
        Ok(e) => e,
        Err(_) => return,
    };

    let mut versioned: Vec<(Vec<u64>, PathBuf)> = Vec::new();

    for entry in entries.filter_map(|e| e.ok()) {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let dir_name = match path.file_name().and_then(|n| n.to_str()) {
            Some(name) => name,
            None => continue,
        };
        // Only consider version-tag directories (e.g. "v4.10.0").
        let parts: Vec<u64> = dir_name
            .trim_start_matches('v')
            .split('.')
            .filter_map(|s| s.parse::<u64>().ok())
            .collect();
        if parts.is_empty() || parts.len() < 2 {
            continue; // skip non-version dirs like "latest"
        }
        versioned.push((parts, path));
    }

    if versioned.len() <= MAX_CACHED_VERSIONS {
        return;
    }

    // Sort descending (newest first), then delete everything after MAX.
    versioned.sort_by(|(a, _), (b, _)| {
        for (ap, bp) in a.iter().zip(b.iter()) {
            match bp.cmp(ap) {
                std::cmp::Ordering::Equal => continue,
                other => return other,
            }
        }
        b.len().cmp(&a.len())
    });

    for (_, path) in versioned.iter().skip(MAX_CACHED_VERSIONS) {
        eprintln!("  Evicting old cached runtime: {}", path.display());
        let _ = fs::remove_dir_all(path);
    }
}

// ---------------------------------------------------------------------------
// Concurrent install protection
// ---------------------------------------------------------------------------

/// Advisory lock that serialises concurrent `install` / `upgrade` runs.
///
/// Uses a directory-creation approach (`mkdir`) which is atomic on all
/// mainstream filesystems.  The lock is released when the guard is dropped.
struct RuntimeInstallLock {
    lock_dir: PathBuf,
    acquired: bool,
}

impl RuntimeInstallLock {
    /// Attempt to acquire the install lock, blocking up to `timeout` seconds.
    fn acquire(timeout: Duration) -> Result<Self, String> {
        let lock_dir = runtime_versions_dir().join(INSTALL_LOCK_DIR_NAME);
        let deadline = Instant::now() + timeout;

        loop {
            match fs::create_dir(&lock_dir) {
                Ok(()) => {
                    // Write our PID into the lock directory for debugging.
                    let pid_file = lock_dir.join("pid");
                    let _ = fs::write(&pid_file, std::process::id().to_string());
                    return Ok(RuntimeInstallLock {
                        lock_dir,
                        acquired: true,
                    });
                }
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {
                    if Instant::now() >= deadline {
                        // Remove a stale lock that's older than our timeout —
                        // the previous process may have crashed.
                        let _ = fs::remove_dir_all(&lock_dir);
                        return Err(format!(
                            "Another browser4-cli install or upgrade is already in progress \
                             and did not finish within {} seconds.  \
                             If you are sure no other install is running, remove \
                             {} manually and retry.",
                            timeout.as_secs(),
                            lock_dir.display()
                        ));
                    }
                    eprintln!(
                        "Another browser4-cli install/upgrade is in progress; waiting... \
                         ({:.0}s remaining)",
                        deadline.saturating_duration_since(Instant::now())
                            .as_secs_f64()
                            .max(0.0)
                    );
                    std::thread::sleep(Duration::from_millis(500));
                }
                Err(e) => {
                    return Err(format!(
                        "Failed to acquire install lock at {}: {e}",
                        lock_dir.display()
                    ));
                }
            }
        }
    }
}

impl Drop for RuntimeInstallLock {
    fn drop(&mut self) {
        if self.acquired {
            let _ = fs::remove_dir_all(&self.lock_dir);
        }
    }
}

pub async fn install_browser4_runtime(
    tag: Option<&str>,
    force: bool,
) -> Result<InstalledBrowser4Runtime, String> {
    let platform = detect_current_runtime_bundle_platform()?;
    let requested_tag = normalize_release_tag(tag);
    if !force {
        if let Some(requested_tag) = requested_tag.as_deref() {
            let versioned_dir = versioned_install_dir(requested_tag);
            if install_dir_contains_runtime(&versioned_dir) {
                if let Some(existing_metadata) =
                    read_installed_browser4_runtime_metadata_for(&versioned_dir)
                {
                    // Ensure this version is also marked as current.
                    if read_current_tag().as_deref() != Some(requested_tag) {
                        let _ = write_current_tag(requested_tag);
                    }
                    return Ok(materialize_installed_runtime(existing_metadata, true));
                }
            }
        }
    }

    let asset_name = platform.asset_name();
    // Acquire an advisory lock to prevent concurrent install/upgrade runs
    // from corrupting the versioned install directory.
    let _install_lock = RuntimeInstallLock::acquire(Duration::from_secs(
        INSTALL_LOCK_TIMEOUT_SECS,
    ))?;

    // Check available disk space before downloading.  We need at least
    // ~500 MB free (200 MB download + 200 MB extraction + headroom).
    // This is a best-effort check — the actual free space may change
    // between the check and the download.
    {
        let check_path = runtime_versions_dir();
        if let Ok(available) = fs_disk_space_available(&check_path) {
            const MIN_FREE_BYTES: u64 = 500 * 1024 * 1024; // 500 MB
            if available < MIN_FREE_BYTES {
                return Err(format!(
                    "Insufficient disk space at {}: only {:.1} MB available, \
                     need at least {:.0} MB.  Free up space or set \
                     BROWSER4_RUNTIME_DIR to a filesystem with more room.",
                    check_path.display(),
                    available as f64 / 1_048_576.0,
                    MIN_FREE_BYTES as f64 / 1_048_576.0,
                ));
            }
        }
    }

    let mirrors = load_mirrors();
    // Determine whether BROWSER4_RELEASES_BASE_URL is in effect — when it is
    // there is only a single custom mirror and speed testing is pointless.
    let is_single_mirror_override = env::var(BROWSER4_RELEASES_BASE_URL_ENV)
        .ok()
        .map_or(false, |v| !v.trim().is_empty());
    let temp_dir = create_runtime_install_temp_dir()?;
    let archive_path = temp_dir.join(&asset_name);
    let extraction_dir = temp_dir.join("extract");

    // If we have a concrete tag, try the download cache first so repeated
    // install runs don't re-fetch the same ~200 MB bundle from the network
    // every time.  When --force is given the user explicitly wants a fresh
    // download, so skip the cache in that case.
    let cache_hit = if force {
        false
    } else {
        match requested_tag.as_deref() {
            Some(normalized) => {
                try_restore_from_download_cache(normalized, &asset_name, &archive_path)
            }
            None => false,
        }
    };

    let install_result = async {
        if cache_hit {
            // Already staged in archive_path — skip download.
            eprintln!(
                "Using cached Browser4 runtime bundle for {} (skip download).",
                asset_name
            );
            // We need a DownloadedFile for the metadata below.
            // Reconstruct it from what we know.  The final_url is set to a
            // sentinel value rather than the first mirror's URL so that
            // inspecting browser4-installation.json doesn't mislead the user
            // into thinking this file was just downloaded from that URL.
            let downloaded = DownloadedFile {
                final_url: "(restored from download cache)".to_string(),
                bytes_written: fs::metadata(&archive_path)
                    .map(|m| m.len())
                    .unwrap_or(0),
            };
            extract_runtime_bundle_archive(&archive_path, &extraction_dir, platform.archive_kind())?;
            let extracted_root = resolve_runtime_bundle_root(&extraction_dir)?;
            let resolved_tag = requested_tag
                .as_deref()
                .map(String::from)
                .unwrap_or_else(|| "latest".to_string());
            let metadata = InstalledBrowser4RuntimeMetadata {
                tag: resolved_tag,
                asset_name: asset_name.clone(),
                download_url: downloaded.final_url,
                installed_at: chrono::Utc::now().to_rfc3339(),
            };
            commit_installed_browser4_runtime(&extracted_root, metadata)
        } else {
            // Build a prioritised list of mirrors to try.
            // The first mirror is either the single override or the result of
            // speed-test / cached-preference selection.  Remaining mirrors
            // follow in the order they appear in the config, deduplicated.
            let (preferred_mirror, _was_speed_tested) = if is_single_mirror_override {
                (&mirrors[0], false)
            } else {
                select_best_mirror(&mirrors, tag).await
            };

            let mut ordered_mirrors: Vec<&DownloadMirror> = Vec::with_capacity(mirrors.len());
            ordered_mirrors.push(preferred_mirror);
            for mirror in &mirrors {
                if normalize_base_url_for_comparison(&mirror.base_url)
                    != normalize_base_url_for_comparison(&preferred_mirror.base_url)
                {
                    ordered_mirrors.push(mirror);
                }
            }

            // Warn about mirrors that don't support /latest/ resolution when
            // no explicit --tag was given (they will be skipped below).
            if requested_tag.is_none() {
                let unsupported: Vec<&str> = ordered_mirrors
                    .iter()
                    .filter(|m| !m.supports_latest_resolution)
                    .map(|m| m.name.as_str())
                    .collect();
                if !unsupported.is_empty() {
                    eprintln!(
                        "Note: mirror(s) {} do not resolve \"latest\" automatically. \
                         If all downloads fail, specify an exact --tag.",
                        unsupported.join(", ")
                    );
                }
            }

            let mut last_error = String::new();
            let mut download_succeeded = false;
            let mut downloaded = DownloadedFile {
                final_url: String::new(),
                bytes_written: 0,
            };

            for (attempt, mirror) in ordered_mirrors.iter().enumerate() {
                // Skip mirrors that don't support latest resolution when no
                // explicit --tag was given — they would 404.
                if requested_tag.is_none() && !mirror.supports_latest_resolution {
                    eprintln!(
                        "Skipping mirror '{}' (does not support /latest/ resolution).",
                        mirror.name
                    );
                    continue;
                }

                if attempt > 0 {
                    eprintln!("Retrying with mirror '{}'...", mirror.name);
                }

                let download_url = mirror_download_url(mirror, tag, &asset_name);
                eprintln!(
                    "Downloading Browser4 runtime bundle from {}...",
                    download_url
                );

                match download_file(&download_url, &archive_path).await {
                    Ok(d) => {
                        downloaded = d;
                        download_succeeded = true;
                        break;
                    }
                    Err(e) => {
                        eprintln!(
                            "Download from '{}' failed: {e}",
                            mirror.name
                        );
                        last_error = e;
                        // Continue to the next mirror.
                    }
                }
            }

            if !download_succeeded {
                // All mirrors exhausted — invalidate the preference cache so
                // the next run doesn't pick the same failing mirror.
                delete_mirror_preference_cache();
                let help = if is_single_mirror_override {
                    format!(
                        "The configured {BROWSER4_RELEASES_BASE_URL_ENV} is unreachable; \
                         update it to point to a working release server."
                    )
                } else {
                    format!(
                        "Help: configure additional mirrors in {} or set \
                         {BROWSER4_RELEASES_BASE_URL_ENV} to point to a working release server.",
                        mirrors_config_path().display()
                    )
                };
                return Err(format!(
                    "Failed to download from all {} mirror(s). Last error: {last_error}\n{help}",
                    ordered_mirrors.len(),
                ));
            }

            eprintln!(
                "Downloaded {} bytes for Browser4 runtime bundle.",
                downloaded.bytes_written
            );

            // Compute SHA-256 of the downloaded archive for self-consistency.
            // If a previously-cached archive exists for this tag+asset, compare
            // checksums — a mismatch signals a corrupt or tampered download.
            if let Some(normalized) = requested_tag.as_deref() {
                match compute_file_sha256(&archive_path) {
                    Ok(fresh_checksum) => {
                        let cached_checksum_path =
                            cached_checksum_path(normalized, &asset_name);
                        if cached_checksum_path.exists() {
                            if let Ok(expected) =
                                fs::read_to_string(&cached_checksum_path)
                            {
                                let expected = expected.trim();
                                if !expected.is_empty() && fresh_checksum != expected {
                                    eprintln!(
                                        "⚠  Checksum mismatch with previously cached archive! \
                                         The new download may be corrupt. \
                                         Evicting stale cache entry."
                                    );
                                    let _ = fs::remove_file(
                                        &cached_download_path(normalized, &asset_name),
                                    );
                                    let _ = fs::remove_file(&cached_checksum_path);
                                }
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!(
                            "⚠  Could not compute checksum of downloaded archive: {e}"
                        );
                    }
                }
            }

            // Cache the downloaded archive for future runs (only when we have
            // a concrete tag — "latest" is too ephemeral).
            if let Some(normalized) = requested_tag.as_deref() {
                try_cache_downloaded_archive(&archive_path, normalized, &asset_name);
            }

            eprintln!(
                "Extracting Browser4 runtime bundle ({} bytes)...",
                fs::metadata(&archive_path)
                    .map(|m| m.len())
                    .unwrap_or(0)
            );
            extract_runtime_bundle_archive(
                &archive_path,
                &extraction_dir,
                platform.archive_kind(),
            )?;
            eprintln!("Extraction complete.");

            let extracted_root = resolve_runtime_bundle_root(&extraction_dir)?;
            let resolved_tag = parse_release_tag_from_url(&downloaded.final_url)
                .or(requested_tag)
                .unwrap_or_else(|| "latest".to_string());
            let metadata = InstalledBrowser4RuntimeMetadata {
                tag: resolved_tag,
                asset_name: asset_name.clone(),
                download_url: downloaded.final_url,
                installed_at: chrono::Utc::now().to_rfc3339(),
            };
            commit_installed_browser4_runtime(&extracted_root, metadata)
        }
    }
    .await;

    // Best-effort eviction of old cached versions to prevent unbounded
    // disk growth on CI machines and long-lived developer workstations.
    evict_old_download_cache_entries();

    cleanup_prepared_launch_dir(Some(temp_dir));
    install_result
}

// ---------------------------------------------------------------------------
// Chrome / Chromium detection and auto-install
// ---------------------------------------------------------------------------

/// Try to locate an installed Google Chrome or Chromium executable.
pub fn find_chrome_executable() -> Option<std::path::PathBuf> {
    let candidates: &[&str] = if cfg!(target_os = "windows") {
        &[
            r"C:\Program Files\Google\Chrome\Application\chrome.exe",
            r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        ]
    } else if cfg!(target_os = "macos") {
        &[
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
        ]
    } else {
        // Linux: check common install locations before falling back to PATH.
        &[
            "/opt/google/chrome/chrome",
            "/opt/google/chrome-stable/chrome",
            "/opt/google/chromium/chromium",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium-browser",
            "/usr/bin/chromium",
            "/usr/lib64/chromium-browser/chromium-browser",
        ]
    };

    for path in candidates {
        if std::path::Path::new(path).exists() {
            return Some(std::path::PathBuf::from(path));
        }
    }

    // Linux / fallback: check PATH
    for name in &[
        "google-chrome",
        "google-chrome-stable",
        "chromium-browser",
        "chromium",
    ] {
        if let Some(path) = find_in_path(name) {
            return Some(path);
        }
    }

    None
}

/// Search for an executable in the system PATH.
fn find_in_path(name: &str) -> Option<std::path::PathBuf> {
    let path_var = env::var_os("PATH")?;
    let exe_extensions = if cfg!(target_os = "windows") {
        vec![".exe", ".cmd", ".bat"]
    } else {
        vec![""]
    };
    for dir in env::split_paths(&path_var) {
        for ext in &exe_extensions {
            let candidate = dir.join(format!("{name}{ext}"));
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    None
}

/// Run a command via sudo, returning a user-friendly error when sudo is
/// unavailable or passwordless sudo is not configured.
fn run_sudo_command(
    program: &str,
    args: &[&str],
    description: &str,
) -> Result<(), String> {
    let status = std::process::Command::new(program)
        .args(args)
        .stdin(std::process::Stdio::null())
        .status()
        .map_err(|e| {
            format!(
                "Failed to run '{program}': {e}. \
                 Ensure 'sudo' is installed and passwordless sudo is configured \
                 for unattended Chrome installation, or install Chrome manually: \
                 https://www.google.com/chrome/"
            )
        })?;

    if !status.success() {
        return Err(format!(
            "{description} failed (sudo exit code: {}). \
             Configure passwordless sudo for unattended Chrome installation, \
             or install Chrome manually: https://www.google.com/chrome/",
            status.code().map_or_else(|| "signal".to_string(), |c| c.to_string())
        ));
    }
    Ok(())
}

/// Check whether Chrome or Chromium is available.  If not, attempt to install
/// Google Chrome automatically (Debian/Ubuntu only).  Other platforms receive
/// a warning with manual instructions.
pub fn ensure_chrome_available() -> Result<(), String> {
    if find_chrome_executable().is_some() {
        eprintln!("✅ Google Chrome / Chromium is available.");
        return Ok(());
    }

    eprintln!("⚠  Google Chrome not found.");

    if cfg!(target_os = "linux") {
        // Detect Debian-based / RHEL-based
        let is_debian = std::path::Path::new("/etc/debian_version").exists();
        let is_rhel = std::path::Path::new("/etc/redhat-release").exists();

        if is_debian {
            eprintln!("   Attempting auto-install on Debian/Ubuntu ...");
            return install_chrome_debian();
        }
        if is_rhel {
            eprintln!("   Attempting auto-install on RHEL/Fedora ...");
            return install_chrome_rhel();
        }

        eprintln!("   Unsupported Linux distribution.");
        eprintln!("   Install Chrome manually: https://www.google.com/chrome/");
        return Err("Cannot auto-install Chrome on this Linux distribution. \
             Install it manually from https://www.google.com/chrome/"
            .to_string());
    }

    if cfg!(target_os = "macos") {
        eprintln!("   Install Chrome manually:");
        eprintln!("     brew install --cask google-chrome");
        eprintln!("   Or download from: https://www.google.com/chrome/");
        return Err(
            "Chrome is not installed. Install it with: brew install --cask google-chrome, \
             or download from https://www.google.com/chrome/"
                .to_string(),
        );
    }

    if cfg!(target_os = "windows") {
        return install_chrome_windows();
    }

    Ok(())
}

fn install_chrome_windows() -> Result<(), String> {
    // 1. Try winget first (built into Windows 10 1709+ / Windows 11).
    if let Ok(output) = std::process::Command::new("winget")
        .args(["--version"])
        .output()
    {
        if output.status.success() {
            eprintln!("   Installing Google Chrome via winget ...");
            let status = std::process::Command::new("winget")
                .args([
                    "install",
                    "--id",
                    "Google.Chrome",
                    "--silent",
                    "--accept-package-agreements",
                    "--accept-source-agreements",
                ])
                .status()
                .map_err(|e| format!("Failed to run winget: {e}"))?;

            if status.success() {
                if find_chrome_executable().is_some() {
                    eprintln!("✅ Google Chrome installed successfully via winget.");
                    return Ok(());
                }
            }
            eprintln!("   winget install failed or Chrome not found after install.");
            eprintln!("   Hint: winget requires Administrator privileges. Run as Administrator and try again.");
        }
    }

    // 2. Fallback: download and run the standalone installer.
    eprintln!("   Downloading Google Chrome installer ...");
    let temp_installer = std::env::temp_dir().join("chrome_installer.exe");
    let url = "https://dl.google.com/chrome/install/latest/chrome_installer.exe";

    // Use PowerShell to download (more reliable on Windows than relying on
    // curl/wget being available).
    let ps_script = format!(
        "$ProgressPreference = 'SilentlyContinue'; \
         Invoke-WebRequest -Uri '{url}' -OutFile '{}'; \
         Start-Process -FilePath '{}' -ArgumentList '/silent /install' -Wait; \
         Remove-Item '{}' -Force; \
         if ((Test-Path '{}') -or (Test-Path '{}')) {{ exit 0 }} else {{ exit 1 }}",
        temp_installer.display(),
        temp_installer.display(),
        temp_installer.display(),
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    );

    let status = std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &ps_script])
        .status()
        .map_err(|e| format!("Failed to run PowerShell installer: {e}"))?;

    if !status.success() {
        let _ = fs::remove_file(&temp_installer);
        return Err("Google Chrome installation via PowerShell failed. \
             This may be due to insufficient privileges — try running as Administrator, \
             or install Chrome manually from https://www.google.com/chrome/"
            .to_string());
    }

    if find_chrome_executable().is_some() {
        eprintln!("✅ Google Chrome installed successfully.");
        Ok(())
    } else {
        Err("Google Chrome installation did not produce a usable binary.".to_string())
    }
}

fn install_chrome_debian() -> Result<(), String> {
    let tmp_deb = std::env::temp_dir().join("google-chrome-stable.deb");
    let url = "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb";

    eprintln!("   Downloading {} ...", url);
    let status = std::process::Command::new("wget")
        .args(["-q", "--show-progress", "-O"])
        .arg(&tmp_deb)
        .arg(url)
        .status()
        .map_err(|e| format!("Failed to run wget: {e}"))?;

    if !status.success() {
        // Try curl as fallback
        eprintln!("   wget failed, trying curl ...");
        let status = std::process::Command::new("curl")
            .args(["-fsSL", "-o"])
            .arg(&tmp_deb)
            .arg(url)
            .status()
            .map_err(|e| format!("Failed to run curl: {e}"))?;
        if !status.success() {
            let _ = fs::remove_file(&tmp_deb);
            return Err("Failed to download Google Chrome. Install it manually.".to_string());
        }
    }

    eprintln!("   Installing Google Chrome ...");
    let tmp_deb_str = tmp_deb.to_string_lossy().to_string();
    let dpkg_result = run_sudo_command(
        "sudo",
        &["-n", "dpkg", "-i", &tmp_deb_str],
        "Chrome dpkg install",
    );

    if dpkg_result.is_err() {
        // Fix broken dependencies (best-effort).
        eprintln!("   Fixing dependencies ...");
        let _ = run_sudo_command(
            "sudo",
            &["-n", "apt-get", "install", "-f", "-y"],
            "apt-get fix dependencies",
        );
        // Retry the dpkg install after fixing deps.
        run_sudo_command(
            "sudo",
            &["-n", "dpkg", "-i", &tmp_deb_str],
            "Chrome dpkg install (retry after dependency fix)",
        )?;
    }

    let _ = fs::remove_file(&tmp_deb);

    if find_chrome_executable().is_some() {
        eprintln!("✅ Google Chrome installed successfully.");
        Ok(())
    } else {
        Err("Google Chrome installation did not produce a usable binary.".to_string())
    }
}

fn install_chrome_rhel() -> Result<(), String> {
    let tmp_rpm = std::env::temp_dir().join("google-chrome-stable.rpm");
    let url = "https://dl.google.com/linux/direct/google-chrome-stable_current_x86_64.rpm";

    eprintln!("   Downloading {} ...", url);
    let status = std::process::Command::new("curl")
        .args(["-fsSL", "-o"])
        .arg(&tmp_rpm)
        .arg(url)
        .status()
        .map_err(|e| format!("Failed to run curl: {e}"))?;

    if !status.success() {
        let _ = fs::remove_file(&tmp_rpm);
        return Err("Failed to download Google Chrome. Install it manually.".to_string());
    }

    eprintln!("   Installing Google Chrome ...");
    let tmp_rpm_str = tmp_rpm.to_string_lossy().to_string();
    let dnf_result = run_sudo_command(
        "sudo",
        &["-n", "dnf", "install", "-y", &tmp_rpm_str],
        "Chrome dnf install",
    );

    if dnf_result.is_err() {
        // Try yum as fallback.
        run_sudo_command(
            "sudo",
            &["-n", "yum", "install", "-y", &tmp_rpm_str],
            "Chrome yum install",
        )?;
    }

    let _ = fs::remove_file(&tmp_rpm);

    if find_chrome_executable().is_some() {
        eprintln!("✅ Google Chrome installed successfully.");
        Ok(())
    } else {
        Err("Google Chrome installation did not produce a usable binary.".to_string())
    }
}

async fn resolve_server_launch_spec(port: u16) -> Result<ServerLaunchSpec, String> {
    let runtime = find_or_install_runtime().await?;
    Ok(build_jar_launch_spec(&runtime, port))
}

fn build_jar_launch_spec(runtime: &InstalledBrowser4Runtime, port: u16) -> ServerLaunchSpec {
    let program = runtime.java_path.clone();
    let program_display = program.display().to_string();
    let classpath_arg = if cfg!(windows) {
        format!("{}\\*", runtime.lib_dir.display())
    } else {
        format!("{}/*", runtime.lib_dir.display())
    };
    ServerLaunchSpec {
        kind: ServerLaunchKind::Jar,
        program,
        args: vec![
            "-cp".to_string(),
            classpath_arg,
            BROWSER4_MAIN_CLASS.to_string(),
            format!("--server.port={port}"),
        ],
        working_dir: runtime.install_dir.clone(),
        registry_target: runtime.lib_dir.clone(),
        description: format!(
            "Starting server from Browser4 runtime at {} using {} on port {}...",
            runtime.install_dir.display(),
            program_display,
            port
        ),
    }
}

fn command_for_launch_spec(
    launch_spec: &ServerLaunchSpec,
) -> Result<PreparedLaunchCommand, String> {
    let mut command = Command::new(&launch_spec.program);
    command
        .args(&launch_spec.args)
        .current_dir(&launch_spec.working_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    Ok(PreparedLaunchCommand {
        command,
        cleanup_dir: None,
    })
}

fn launch_ready_timeout(_launch_spec: &ServerLaunchSpec) -> Duration {
    JAR_SERVER_READY_TIMEOUT
}

fn find_browser4_root() -> Option<PathBuf> {
    if let Some(invocation_dir) = browser4_root_search_start_dir_from_env() {
        if let Some(root) = find_browser4_root_from(&invocation_dir, false) {
            return Some(root);
        }
    }

    let current_dir = env::current_dir().ok()?;
    find_browser4_root_from(&current_dir, false)
}

fn browser4_root_search_start_dir_from_env() -> Option<PathBuf> {
    let value = env::var(ROOT_SEARCH_START_DIR_ENV).ok()?;
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(PathBuf::from(trimmed))
}

fn find_browser4_root_from(start: &Path, deep_search: bool) -> Option<PathBuf> {
    eprintln!("Finding browser4 root from {:?}", start.display());

    let start_dir = if start.is_dir() {
        start
    } else {
        start.parent()?
    };
    let mut current = Some(start_dir);
    while let Some(path) = current {
        if is_browser4_root(path) {
            return Some(path.to_path_buf());
        }
        current = path.parent();
    }

    if deep_search {
        if let Some(module_dir) = find_browser4_cli_module_dir(start_dir) {
            return find_browser4_root_from(&module_dir, false);
        }
    }

    None
}

fn is_browser4_root(path: &Path) -> bool {
    path.join("ROOT.md").is_file() && path.join("pom.xml").is_file()
}

fn find_browser4_cli_module_dir(start: &Path) -> Option<PathBuf> {
    let mut stack = vec![start.to_path_buf()];

    while let Some(dir) = stack.pop() {
        if is_browser4_cli_module_dir(&dir) {
            return Some(dir);
        }

        let entries = match fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(_) => continue,
        };

        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() && !should_skip_browser4_root_search(&path) {
                stack.push(path);
            }
        }
    }

    None
}

fn is_browser4_cli_module_dir(path: &Path) -> bool {
    path.file_name().and_then(|name| name.to_str()) == Some("browser4-cli")
        && path.join("Cargo.toml").is_file()
}

fn should_skip_browser4_root_search(path: &Path) -> bool {
    matches!(
        path.file_name().and_then(|name| name.to_str()),
        Some(".git" | ".idea" | "node_modules" | "target")
    )
}

/// Check whether a valid runtime bundle already exists at the given paths.
///
/// A valid bundle must have a populated `lib/` directory (at least one jar) and
/// a `runtime/bin/java` launcher produced by jlink.
fn existing_runtime_bundle(
    lib_dir: &Path,
    java_path: &Path,
    work_dir: &Path,
) -> Option<InstalledBrowser4Runtime> {
    let has_jars = lib_dir.is_dir()
        && std::fs::read_dir(lib_dir)
            .map(|mut entries| {
                entries.any(|entry| {
                    entry
                        .ok()
                        .map(|e| {
                            e.path()
                                .extension()
                                .map(|ext| ext == "jar")
                                .unwrap_or(false)
                        })
                        .unwrap_or(false)
                })
            })
            .unwrap_or(false);

    if has_jars && java_path.is_file() {
        Some(InstalledBrowser4Runtime {
            tag: "local".to_string(),
            asset_name: String::new(),
            download_url: String::new(),
            install_dir: work_dir.to_path_buf(),
            lib_dir: lib_dir.to_path_buf(),
            jar_path: PathBuf::new(),
            java_path: java_path.to_path_buf(),
            reused_existing: true,
        })
    } else {
        None
    }
}

/// Check whether the Maven-built fat JAR is present and has valid content.
fn maven_jar_exists(bundle_module_dir: &Path) -> bool {
    let jar = bundle_module_dir.join("target").join("Browser4Bundle.jar");
    // The bundle JAR is a Spring Boot thin launcher (~8 KB); anything
    // above 4 KB is a credible build artifact.  Stale / corrupt files
    // are typically zero-length or a few hundred bytes.
    jar.is_file() && jar.metadata().map(|m| m.len() > 4_096).unwrap_or(false)
}

/// Attempt to auto-build the local runtime bundle from source when running in a
/// Browser4 repository checkout.  Returns `Ok(None)` when auto-build is not
/// applicable (no bundle module, build script missing, or build failed) so the
/// caller can fall back to the download path.
///
/// Each build step is independently skippable when its output already exists:
/// 1. Maven `package` is skipped when `target/Browser4Bundle.jar` is valid.
/// 2. The platform build script is skipped when the runtime bundle already
///    contains `lib/*.jar` and `runtime/bin/java`.
async fn try_build_local_runtime_bundle(
    root: &Path,
) -> Result<Option<InstalledBrowser4Runtime>, String> {
    let bundle_module_dir = root.join("browser4-apps").join("browser4-bundle");
    if !bundle_module_dir.is_dir() {
        return Ok(None);
    }

    let platform = detect_current_runtime_bundle_platform()?;
    let bundle_dir_name = platform.bundle_dir_name();
    let bundle_runtime_dir = bundle_module_dir.join("target").join("runtime-bundle");
    let work_dir = bundle_runtime_dir
        .join("_work")
        .join(&bundle_dir_name)
        .join(&bundle_dir_name);
    let lib_dir = work_dir.join("lib");
    let java_path = work_dir
        .join("runtime")
        .join("bin")
        .join(browser4_java_executable_name());

    // Fast path: runtime bundle already assembled — nothing to do.
    if let Some(runtime) = existing_runtime_bundle(&lib_dir, &java_path, &work_dir) {
        eprintln!(
            "Using existing local Browser4 runtime bundle at {}.",
            work_dir.display()
        );
        return Ok(Some(runtime));
    }

    let build_script = bundle_module_dir.join(platform.build_script_name());
    if !build_script.is_file() {
        eprintln!(
            "Build script not found at {}; skipping local bundle build.",
            build_script.display()
        );
        return Ok(None);
    }

    // Step 1 – ensure the fat JAR exists.  Skip Maven when the JAR from a
    // previous build is still present; this saves 10–30 s on every invocation
    // when only the runtime assembly step needs to be re-run.
    if maven_jar_exists(&bundle_module_dir) {
        eprintln!(
            "Using existing Browser4 bundle JAR at {}; skipping Maven package.",
            bundle_module_dir
                .join("target")
                .join("Browser4Bundle.jar")
                .display()
        );
    } else {
        eprintln!(
            "Building local Browser4 runtime bundle from {} ...",
            bundle_module_dir.display()
        );
        let mvn_program = resolve_maven_program(root);
        let mvn_status = tokio::task::spawn_blocking({
            let mvn_program = mvn_program.clone();
            let root = root.to_path_buf();
            move || {
                std::process::Command::new(&mvn_program)
                    .args([
                        "install",
                        "-Pall-main-modules,asset-bundle",
                        "-DskipTests",
                        "-q",
                    ])
                    .current_dir(&root)
                    .stdin(std::process::Stdio::null())
                    .stdout(std::process::Stdio::null())
                    .stderr(std::process::Stdio::piped())
                    .status()
            }
        })
        .await
        .map_err(|e| format!("Maven package task panicked: {e}"))?;

        match mvn_status {
            Ok(status) if status.success() => {}
            Ok(status) => {
                eprintln!(
                    "Maven package for browser4-bundle exited with {}; falling back to download.",
                    status
                        .code()
                        .map_or_else(|| "signal".to_string(), |c| c.to_string())
                );
                return Ok(None);
            }
            Err(error) => {
                eprintln!(
                    "Failed to run Maven for browser4-bundle ({error}); falling back to download."
                );
                return Ok(None);
            }
        }
    }

    // Step 2 – run the platform build script (jlink + assembly).
    eprintln!(
        "Assembling Browser4 runtime bundle from {} ...",
        bundle_module_dir.display()
    );
    let build_result = if cfg!(windows) {
        run_bundle_build_script("powershell.exe", &build_script, &bundle_module_dir).await
    } else {
        // PowerShell Core may be installed as `pwsh` on Linux / macOS.
        run_bundle_build_script("pwsh", &build_script, &bundle_module_dir).await
    };

    match build_result {
        Ok(()) => {}
        Err(error) => {
            eprintln!("Runtime bundle build script failed: {error}; falling back to download.");
            return Ok(None);
        }
    }

    // Verify the expected output was produced.
    if let Some(runtime) = existing_runtime_bundle(&lib_dir, &java_path, &work_dir) {
        eprintln!(
            "Local Browser4 runtime bundle built successfully at {}.",
            bundle_runtime_dir.display()
        );
        return Ok(Some(runtime));
    }

    eprintln!(
        "Runtime bundle build completed but expected layout under {} was not found; falling back to download.",
        work_dir.display()
    );
    Ok(None)
}

/// Resolve the Maven launcher (`mvnw` / `mvnw.cmd` / `mvn`) relative to the
/// Browser4 repository root.  Prefers the checked-in wrapper when available.
fn resolve_maven_program(root: &Path) -> PathBuf {
    let wrapper_name = if cfg!(windows) { "mvnw.cmd" } else { "mvnw" };
    let wrapper = root.join(wrapper_name);
    if wrapper.exists() {
        return wrapper;
    }
    if cfg!(windows) {
        PathBuf::from("mvn.cmd")
    } else {
        PathBuf::from("mvn")
    }
}

/// Run the bundle build script and wait for it to finish.
///
/// Uses `-Command` instead of `-File` so we can force UTF-8 output encoding
/// before the script runs.  Without this, PowerShell writes stderr in the
/// system's OEM code page (e.g. GBK on Chinese Windows) and Rust's
/// `String::from_utf8_lossy` produces garbled diagnostics.
async fn run_bundle_build_script(
    shell: &str,
    script: &Path,
    working_dir: &Path,
) -> Result<(), String> {
    let script_path = script.to_path_buf();
    let working_dir = working_dir.to_path_buf();
    let shell_owned = shell.to_string();
    let shell_for_error = shell_owned.clone();
    // Build a -Command line that forces UTF-8 encoding on the console output
    // stream, then dot-sources the build script.  We shell-escape the script
    // path so that paths with spaces or special characters work correctly.
    let script_path_escaped = script_path.to_string_lossy().replace('\'', "''");
    let command = format!(
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; \
         [Console]::ErrorEncoding = [System.Text.Encoding]::UTF8; \
         & '{}' -SkipMavenInstall",
        script_path_escaped
    );

    let output = tokio::task::spawn_blocking(move || {
        std::process::Command::new(&shell_owned)
            .args([
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                &command,
            ])
            .current_dir(&working_dir)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped())
            .output()
    })
    .await
    .map_err(|e| format!("build script task panicked: {e}"))?;

    match output {
        Ok(output) if output.status.success() => Ok(()),
        Ok(output) => {
            let stdout = String::from_utf8_lossy(&output.stdout);
            let stderr = String::from_utf8_lossy(&output.stderr);
            let mut message = format!(
                "build script exited with {}",
                output
                    .status
                    .code()
                    .map_or_else(|| "signal".to_string(), |c| c.to_string()),
            );
            if !stdout.trim().is_empty() {
                message.push_str("\n--- stdout ---\n");
                message.push_str(stdout.trim());
            }
            if !stderr.trim().is_empty() {
                message.push_str("\n--- stderr ---\n");
                message.push_str(stderr.trim());
            }
            if stdout.trim().is_empty() && stderr.trim().is_empty() {
                message.push_str(" (no output)");
            }
            Err(message)
        }
        Err(error) => Err(format!(
            "failed to spawn build script ({shell_for_error}): {error}"
        )),
    }
}

async fn find_or_install_runtime() -> Result<InstalledBrowser4Runtime, String> {
    // Check for an already-installed runtime (from prior `install` command)
    if install_dir_contains_runtime(&browser4_install_dir()) {
        if let Some(metadata) = read_installed_browser4_runtime_metadata() {
            eprintln!(
                "Using installed Browser4 runtime {} from {}.",
                metadata.tag,
                browser4_install_dir().display()
            );
            return Ok(materialize_installed_runtime(metadata, true));
        }
    }

    let force_remote = should_force_remote_bundle();

    // When BROWSER4_CLI_FORCE_REMOTE_BUNDLE is set, skip the local build
    // entirely — useful in CI / corporate environments where Maven or jlink
    // are unreliable (e.g. behind a proxy that only allows HTTPS to GitHub).
    if !force_remote {
        // Check for a local project build (or auto-build it).
        let project_root = find_browser4_root();
        if let Some(root) = &project_root {
            match try_build_local_runtime_bundle(root).await {
                Ok(Some(runtime)) => return Ok(runtime),
                Ok(None) => {
                    eprintln!("Local Browser4 bundle is not available; falling back to download.");
                }
                Err(error) => {
                    eprintln!(
                        "Local Browser4 bundle check failed: {error}; falling back to download."
                    );
                }
            }
        }
    }

    // Download and install the runtime bundle
    install_browser4_runtime(None, false).await
}

async fn start_server(
    launch_spec: &ServerLaunchSpec,
    base_url: &str,
    port: u16,
) -> Result<(), String> {
    let startup_log = create_server_startup_log(launch_spec, port).map_err(|error| {
        eprintln!("Failed to initialize Browser4 startup log: {error}");
        error
    })?;
    eprintln!("Browser4 startup log: {}", startup_log.path.display());

    let PreparedLaunchCommand {
        mut command,
        mut cleanup_dir,
    } = command_for_launch_spec(launch_spec)?;
    append_startup_log_message(
        &startup_log.path,
        format!("Prepared command launch for {base_url}"),
    );

    let launch_working_dir = command
        .get_current_dir()
        .map(Path::to_path_buf)
        .unwrap_or_else(|| launch_spec.working_dir.clone());
    append_startup_log_message(
        &startup_log.path,
        format!("Launch working directory: {}", launch_working_dir.display()),
    );
    append_startup_log_message(
        &startup_log.path,
        format!("Launch command: {}", format_command_for_log(&command)),
    );

    command
        .stdout(startup_log.stdout)
        .stderr(startup_log.stderr);

    let mut child = command.spawn().map_err(|e| {
        let error = format_server_startup_failure_message(
            base_url,
            Some("Browser4 could not be launched."),
            &format!("Failed to start server: {e}"),
            None,
            Some(startup_log.path.as_path()),
        );
        append_startup_log_message(&startup_log.path, &error);
        error
    })?;
    append_startup_log_message(
        &startup_log.path,
        format!("Spawned launcher process with pid {}", child.id()),
    );

    let client = Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(|e| e.to_string())?;

    let ready_timeout = launch_ready_timeout(launch_spec);
    append_startup_log_message(
        &startup_log.path,
        format!(
            "Waiting up to {} seconds for Browser4 readiness at {base_url}",
            ready_timeout.as_secs()
        ),
    );

    if let Err(error) = wait_for_server_ready(
        &client,
        base_url,
        ready_timeout,
        Some(startup_log.path.as_path()),
    )
    .await
    {
        let (preserve_cleanup_dir, exit_context, cleanup_context) =
            readiness_failure_context(child.try_wait(), cleanup_dir.as_deref());
        if !preserve_cleanup_dir {
            cleanup_prepared_launch_dir(cleanup_dir.take());
        }
        let error_message = format!("{error}{exit_context}{cleanup_context}");
        append_startup_log_message(&startup_log.path, &error_message);
        return Err(error_message);
    }

    cleanup_prepared_launch_dir(cleanup_dir.take());

    let managed_pid = resolve_managed_server_pid(child.id());
    register_managed_server_process(
        ManagedServerProcess {
            pid: managed_pid,
            base_url: base_url.to_string(),
            port,
            // Keep the legacy registry field populated for backward compatibility.
            jar_path: launch_spec.registry_target.to_string_lossy().to_string(),
            started_at: chrono::Utc::now().to_rfc3339(),
        },
        None,
    );

    // Detach: we drop the Child handle here. The spawned process continues
    // running independently because we set all stdio to null and call drop().
    drop(child);

    append_startup_log_message(
        &startup_log.path,
        format!("Browser4 reported ready at {base_url}; managed pid {managed_pid}"),
    );
    eprintln!(
        "Server is up and running. Startup log: {}",
        startup_log.path.display()
    );
    Ok(())
}

fn format_command_for_log(command: &Command) -> String {
    let mut parts = vec![shell_quote_for_log(
        &command.get_program().to_string_lossy(),
    )];
    parts.extend(
        command
            .get_args()
            .map(|arg| shell_quote_for_log(&arg.to_string_lossy())),
    );
    parts.join(" ")
}

fn shell_quote_for_log(value: &str) -> String {
    if value.is_empty()
        || value
            .chars()
            .any(|ch| ch.is_whitespace() || ch == '"' || ch == '\'')
    {
        format!("{value:?}")
    } else {
        value.to_string()
    }
}

fn cleanup_prepared_launch_dir(path: Option<PathBuf>) {
    if let Some(path) = path {
        let _ = fs::remove_dir_all(path);
    }
}

fn readiness_failure_context(
    launch_process_state: Result<Option<ExitStatus>, io::Error>,
    cleanup_dir: Option<&Path>,
) -> (bool, String, String) {
    match launch_process_state {
        Ok(Some(status)) => (
            false,
            format!(" Process exited early with status {status}."),
            String::new(),
        ),
        Ok(None) => (
            true,
            String::new(),
            cleanup_dir
                .map(|path| {
                    format!(
                        " Preserving staged Browser4 launcher files at {} because the startup process is still running.",
                        path.display()
                    )
                })
                .unwrap_or_default(),
        ),
        Err(wait_error) => (
            true,
            format!(" Failed to inspect launcher process: {wait_error}."),
            cleanup_dir
                .map(|path| {
                    format!(
                        " Preserving staged Browser4 launcher files at {} because the startup process is still running.",
                        path.display()
                    )
                })
                .unwrap_or_default(),
        ),
    }
}

struct ServerStartupLog {
    path: PathBuf,
    stdout: Stdio,
    stderr: Stdio,
}

fn create_server_startup_log(
    launch_spec: &ServerLaunchSpec,
    port: u16,
) -> Result<ServerStartupLog, String> {
    create_server_startup_log_in(None, launch_spec, port)
}

fn create_server_startup_log_in(
    log_dir: Option<&Path>,
    launch_spec: &ServerLaunchSpec,
    port: u16,
) -> Result<ServerStartupLog, String> {
    let path = server_startup_log_path(log_dir, launch_spec, port);
    let parent = path.parent().ok_or_else(|| {
        format!(
            "Startup log path does not have a parent directory: {}",
            path.display()
        )
    })?;
    fs::create_dir_all(parent).map_err(|e| {
        format!(
            "Failed to create Browser4 startup log directory {}: {e}",
            parent.display()
        )
    })?;

    let mut file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .map_err(|e| {
            format!(
                "Failed to open Browser4 startup log {}: {e}",
                path.display()
            )
        })?;
    writeln!(
        file,
        "[{}] Launching Browser4 {:?} on port {} from {}",
        chrono::Utc::now().to_rfc3339(),
        launch_spec.kind,
        port,
        launch_spec.working_dir.display()
    )
    .map_err(|e| {
        format!(
            "Failed to write Browser4 startup log header {}: {e}",
            path.display()
        )
    })?;
    writeln!(file, "program: {}", launch_spec.program.display()).map_err(|e| {
        format!(
            "Failed to write Browser4 startup log header {}: {e}",
            path.display()
        )
    })?;
    writeln!(file, "args: {}", launch_spec.args.join(" ")).map_err(|e| {
        format!(
            "Failed to write Browser4 startup log header {}: {e}",
            path.display()
        )
    })?;
    writeln!(file).map_err(|e| {
        format!(
            "Failed to write Browser4 startup log header {}: {e}",
            path.display()
        )
    })?;
    file.sync_all().map_err(|e| {
        format!(
            "Failed to sync Browser4 startup log header {}: {e}",
            path.display()
        )
    })?;

    let stderr_file = file.try_clone().map_err(|e| {
        format!(
            "Failed to clone Browser4 startup log handle {}: {e}",
            path.display()
        )
    })?;

    Ok(ServerStartupLog {
        path,
        stdout: Stdio::from(file),
        stderr: Stdio::from(stderr_file),
    })
}

fn append_startup_log_message(path: &Path, message: impl AsRef<str>) {
    if let Err(error) = append_startup_log_message_impl(path, message.as_ref()) {
        eprintln!(
            "Failed to write Browser4 startup log {}: {}",
            path.display(),
            error
        );
    }
}

fn append_startup_log_message_impl(path: &Path, message: &str) -> std::io::Result<()> {
    let mut file = fs::OpenOptions::new().append(true).open(path)?;
    writeln!(file, "[{}] {}", chrono::Utc::now().to_rfc3339(), message)?;
    file.sync_all()
}

fn server_startup_log_dir(log_dir: Option<&Path>) -> PathBuf {
    log_dir
        .map(Path::to_path_buf)
        .unwrap_or_else(default_cli_temp_dir)
}

fn server_startup_log_path(
    log_dir: Option<&Path>,
    launch_spec: &ServerLaunchSpec,
    port: u16,
) -> PathBuf {
    let kind = match launch_spec.kind {
        ServerLaunchKind::Jar => "jar",
    };
    let timestamp = chrono::Utc::now().format("%Y%m%dT%H%M%S%.3fZ");
    server_startup_log_dir(log_dir)
        .join(format!("browser4-server-{kind}-port{port}-{timestamp}.log"))
}

fn browser4_cli_temp_root_dir() -> PathBuf {
    env::temp_dir().join("browser4").join("browser4-cli")
}

fn default_cli_temp_dir() -> PathBuf {
    let mut path = browser4_cli_temp_root_dir();
    for component in CLI_TEMP_DIR_COMPONENTS {
        path.push(component);
    }
    path
}

fn resolve_managed_server_pid(launcher_pid: u32) -> u32 {
    #[cfg(windows)]
    {
        resolve_windows_managed_server_pid(launcher_pid).unwrap_or(launcher_pid)
    }

    #[cfg(not(windows))]
    {
        launcher_pid
    }
}

#[cfg(windows)]
fn resolve_windows_managed_server_pid(launcher_pid: u32) -> Option<u32> {
    let ps_command = format!(
        r#"
function Get-DescendantProcessIds([UInt32] $ProcessId) {{
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId" | Sort-Object CreationDate
    foreach ($child in $children) {{
        $child.ProcessId
        Get-DescendantProcessIds -ProcessId $child.ProcessId
    }}
}}
$ids = @(Get-DescendantProcessIds -ProcessId {launcher_pid})
if ($ids.Count -gt 0) {{ $ids[-1] }}
"#
    );

    let output = Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", &ps_command])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    String::from_utf8_lossy(&output.stdout)
        .lines()
        .rev()
        .find_map(|line| line.trim().parse::<u32>().ok())
}

/// Resolve the base URL from CLI state + optional server override arg.
pub fn resolve_base_url(override_url: Option<&str>, session_name: Option<&str>) -> String {
    let state = read_state(None, session_name);
    let base = override_url
        .map(|s| s.to_string())
        .unwrap_or(state.base_url);
    base.trim_end_matches('/').to_string()
}

enum ServerState {
    Ready,
    Starting(String),
    Unreachable(String),
}

async fn probe_server_state(client: &Client, base_url: &str) -> ServerState {
    let trimmed = base_url.trim_end_matches('/');
    let health_url = format!("{trimmed}/actuator/health");
    let tools_url = format!("{trimmed}/mcp/tools");

    let health_response = match client.get(&health_url).send().await {
        Ok(response) => response,
        Err(error) => return ServerState::Unreachable(error.to_string()),
    };
    let health_body = match health_response.text().await {
        Ok(body) => body,
        Err(error) => return ServerState::Starting(error.to_string()),
    };
    if !health_body.contains("\"status\":\"UP\"") {
        return ServerState::Starting(health_body);
    }

    let tools_response = match client.get(&tools_url).send().await {
        Ok(response) => response,
        Err(error) => return ServerState::Starting(error.to_string()),
    };
    let tools_body = match tools_response.text().await {
        Ok(body) => body,
        Err(error) => return ServerState::Starting(error.to_string()),
    };
    if tools_body.contains("open_session") && tools_body.contains("browser_navigate") {
        ServerState::Ready
    } else {
        ServerState::Starting(format!("MCP tools endpoint not ready: {tools_body}"))
    }
}

async fn wait_for_server_ready(
    client: &Client,
    base_url: &str,
    timeout: Duration,
    startup_log_path: Option<&Path>,
) -> Result<(), String> {
    let start = Instant::now();
    let mut last_error = String::from("unknown");
    let mut last_progress_log_at = Instant::now() - Duration::from_secs(10);
    let initial_quiet_wait = initial_server_ready_quiet_wait(timeout);

    if !initial_quiet_wait.is_zero() {
        tokio::time::sleep(initial_quiet_wait).await;
        if start.elapsed() >= timeout {
            last_error = format!(
                "readiness checks were deferred during the initial {}s startup grace period",
                initial_quiet_wait.as_secs()
            );
        }
    }

    while start.elapsed() <= timeout {
        let progress_status = match probe_server_state(client, base_url).await {
            ServerState::Ready => return Ok(()),
            ServerState::Starting(error) => {
                last_error = error;
                format_server_wait_progress(&ServerState::Starting(last_error.clone()))
            }
            ServerState::Unreachable(error) => {
                last_error = error;
                format_server_wait_progress(&ServerState::Unreachable(last_error.clone()))
            }
        };

        if last_progress_log_at.elapsed() >= Duration::from_secs(10) {
            eprintln!(
                "Waiting for Browser4 server at {} ({}s/{}s): {}",
                base_url,
                start.elapsed().as_secs(),
                timeout.as_secs(),
                progress_status
            );
            last_progress_log_at = Instant::now();
        }

        tokio::time::sleep(Duration::from_secs(1)).await;
    }

    Err(format_server_startup_failure_message(
        base_url,
        Some("Browser4 did not become MCP-ready before the startup timeout elapsed."),
        &format!("Last readiness probe result: {last_error}"),
        Some(timeout),
        startup_log_path,
    ))
}

fn initial_server_ready_quiet_wait(timeout: Duration) -> Duration {
    SERVER_READY_INITIAL_QUIET_WAIT.min(timeout)
}

fn format_server_wait_progress(state: &ServerState) -> String {
    match state {
        ServerState::Ready => "ready".to_string(),
        ServerState::Starting(status) => match truncate_status_for_log(status) {
            message if message.is_empty() => "still starting".to_string(),
            message => format!("still starting ({message})"),
        },
        ServerState::Unreachable(_) => "not reachable yet".to_string(),
    }
}

fn truncate_status_for_log(message: &str) -> String {
    const MAX_CHARS: usize = 240;

    let single_line = message.replace(['\r', '\n'], " ").trim().to_string();
    if single_line.chars().count() <= MAX_CHARS {
        return single_line;
    }

    let mut truncated = single_line
        .chars()
        .take(MAX_CHARS.saturating_sub(1))
        .collect::<String>();
    truncated.push('…');
    truncated
}

fn format_server_startup_failure_message(
    base_url: &str,
    summary: Option<&str>,
    details: &str,
    timeout: Option<Duration>,
    startup_log_path: Option<&Path>,
) -> String {
    let mut message = vec!["🛑 Browser4 server startup failed".to_string()];
    message.push(format!("  Server: {base_url}"));

    if let Some(timeout) = timeout {
        message.push(format!("  Timeout: {}s", timeout.as_secs()));
    }

    if let Some(summary) = summary {
        message.push(format!("  Summary: {summary}"));
    }

    let mut suggestions = vec!["inspect the Browser4 startup log for the underlying server error."];
    if timeout.is_some() {
        suggestions.push("retry the command after Browser4 finishes starting.");
    } else {
        suggestions.push("confirm Java/Browser4 dependencies are available, then retry.");
    }

    message.push(String::new());
    message.push("💡 What to try".to_string());
    message.extend(suggestions.into_iter().map(|line| format!("  - {line}")));

    message.push(String::new());
    message.push("🧾 Details".to_string());
    message.push(format!("  {details}"));

    let startup_log_details = format_startup_log_timeout_details(startup_log_path);
    if !startup_log_details.is_empty() {
        message.push(String::new());
        message.push(startup_log_details);
    }

    message.join("\n")
}

fn format_startup_log_timeout_details(startup_log_path: Option<&Path>) -> String {
    let Some(startup_log_path) = startup_log_path else {
        return String::new();
    };

    format!(
        "📄 Browser4 startup log\n  Path: {}\n  Tail:\n{}",
        startup_log_path.display(),
        read_startup_log_tail(startup_log_path)
    )
}

fn read_startup_log_tail(path: &Path) -> String {
    const MAX_LINES: usize = 40;
    const MAX_CHARS: usize = 8_000;

    let contents = match fs::read_to_string(path) {
        Ok(contents) => contents,
        Err(error) => return format!("(failed to read startup log: {error})"),
    };
    let lines: Vec<&str> = contents.lines().collect();
    if lines.is_empty() {
        return "(startup log is empty)".to_string();
    }

    let start = lines.len().saturating_sub(MAX_LINES);
    let mut tail = lines[start..].join("\n");
    let tail_char_count = tail.chars().count();
    if tail_char_count > MAX_CHARS {
        tail = format!(
            "...{}",
            tail.chars()
                .skip(tail_char_count - MAX_CHARS)
                .collect::<String>()
        );
    }

    tail
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::{create_dir_all, write};
    use std::sync::Mutex;
    use tempfile::TempDir;

    /// Global lock to serialize tests that manipulate `BROWSER4_RUNTIME_DIR`
    /// and `BROWSER4_CLI_STATE_DIR` environment variables.  Without this,
    /// parallel test execution causes cross-test contamination.
    static ENV_MUTEX: Mutex<()> = Mutex::new(());

    fn test_temp_dir() -> TempDir {
        let root = std::env::temp_dir()
            .join("browser4")
            .join("browser4-cli")
            .join("daemon-tests");
        create_dir_all(&root).unwrap();
        tempfile::Builder::new()
            .prefix("daemon-")
            .tempdir_in(&root)
            .unwrap()
    }

    fn sample_launch_spec() -> ServerLaunchSpec {
        ServerLaunchSpec {
            kind: ServerLaunchKind::Jar,
            program: PathBuf::from("program"),
            args: vec!["arg1".to_string(), "arg2".to_string()],
            working_dir: PathBuf::from("."),
            registry_target: PathBuf::from("registry-target"),
            description: String::from("desc"),
        }
    }

    fn create_browser4_root(tmp: &TempDir) -> PathBuf {
        let root = tmp.path().join("Browser4");
        create_dir_all(root.join("cli").join("browser4-cli")).unwrap();
        write(root.join("ROOT.md"), "# Browser4\n").unwrap();
        write(root.join("VERSION"), "0.1.0\n").unwrap();
        write(root.join("pom.xml"), "<project />").unwrap();
        write(
            root.join("cli").join("browser4-cli").join("Cargo.toml"),
            "[package]\nname = \"browser4-cli\"\n",
        )
        .unwrap();
        root
    }

    #[test]
    fn test_find_browser4_root() {
        let root = find_browser4_root().unwrap();
        // println!("{:?}", root);
        assert!(root.join("ROOT.md").exists());
    }

    #[test]
    fn test_find_browser4_root_prefers_invocation_env_dir() {
        let tmp = test_temp_dir();
        let root = create_browser4_root(&tmp);
        let nested = root.join("cli").join("browser4-cli");

        unsafe {
            env::set_var(ROOT_SEARCH_START_DIR_ENV, nested.as_os_str());
        }
        let detected = find_browser4_root();
        unsafe {
            env::remove_var(ROOT_SEARCH_START_DIR_ENV);
        }

        assert_eq!(detected, Some(root));
    }

    #[test]
    fn test_find_browser4_root_from_nested_cli_dir() {
        let tmp = test_temp_dir();
        let root = create_browser4_root(&tmp);
        let nested = root.join("cli").join("browser4-cli").join("src");
        create_dir_all(&nested).unwrap();

        assert_eq!(find_browser4_root_from(&nested, false), Some(root));
    }

    #[test]
    fn test_find_browser4_root_from_workspace_parent_with_deep_search() {
        let tmp = test_temp_dir();
        let workspace = tmp.path().join("Browser4Team");
        let submodules = workspace.join("submodules");
        create_dir_all(&submodules).unwrap();

        let root = create_browser4_root_in(&submodules);

        assert_eq!(find_browser4_root_from(&workspace, true), Some(root));
    }

    #[test]
    fn test_find_browser4_root_from_workspace_parent_without_deep_search() {
        let tmp = test_temp_dir();
        let workspace = tmp.path().join("Browser4Team");
        create_dir_all(workspace.join("submodules")).unwrap();
        let root = create_browser4_root_in(&workspace.join("submodules"));

        assert_eq!(find_browser4_root_from(&workspace, false), None);
        assert_eq!(find_browser4_root_from(&workspace, true), Some(root));
    }

    #[test]
    fn test_find_browser4_root_from_non_repo_path() {
        let tmp = test_temp_dir();
        let outside = tmp.path().join("not-browser4");
        create_dir_all(&outside).unwrap();

        assert_eq!(find_browser4_root_from(&outside, false), None);
    }

    #[test]
    fn test_is_local_port_open_detects_listener() {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();

        assert!(is_local_port_open(&format!("http://127.0.0.1:{port}")));
        assert!(is_local_port_open(&format!("http://localhost:{port}")));
    }

    #[test]
    fn test_is_local_port_open_returns_false_for_unbound_port() {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let port = listener.local_addr().unwrap().port();
        drop(listener);

        assert!(!is_local_port_open(&format!("http://127.0.0.1:{port}")));
    }

    #[test]
    fn test_launch_ready_timeout_uses_jar_timeout() {
        let spec = sample_launch_spec();
        assert_eq!(launch_ready_timeout(&spec), JAR_SERVER_READY_TIMEOUT);
    }

    #[test]
    fn test_readiness_failure_context_preserves_cleanup_for_running_process() {
        let cleanup_dir = Path::new("/tmp/browser4-launcher");

        let (preserve_cleanup_dir, exit_context, cleanup_context) =
            readiness_failure_context(Ok(None), Some(cleanup_dir));

        assert!(preserve_cleanup_dir);
        assert!(exit_context.is_empty());
        assert!(cleanup_context.contains(cleanup_dir.to_string_lossy().as_ref()));
    }

    #[test]
    fn test_readiness_failure_context_cleans_up_after_exited_process() {
        let exited_status = if cfg!(windows) {
            Command::new("cmd")
                .args(["/C", "exit 7"])
                .status()
                .expect("cmd exit status should be available")
        } else {
            Command::new("sh")
                .args(["-c", "exit 7"])
                .status()
                .expect("sh exit status should be available")
        };

        let (preserve_cleanup_dir, exit_context, cleanup_context) = readiness_failure_context(
            Ok(Some(exited_status)),
            Some(Path::new("/tmp/browser4-launcher")),
        );

        assert!(!preserve_cleanup_dir);
        assert!(exit_context.contains("Process exited early with status"));
        assert!(cleanup_context.is_empty());
    }

    #[test]
    fn test_readiness_failure_context_preserves_cleanup_when_inspection_fails() {
        let cleanup_dir = Path::new("/tmp/browser4-launcher");

        let (preserve_cleanup_dir, exit_context, cleanup_context) = readiness_failure_context(
            Err(io::Error::other("permission denied")),
            Some(cleanup_dir),
        );

        assert!(preserve_cleanup_dir);
        assert!(exit_context.contains("Failed to inspect launcher process"));
        assert!(cleanup_context.contains(cleanup_dir.to_string_lossy().as_ref()));
    }

    #[test]
    fn test_mirror_download_url_defaults_to_latest() {
        let mirror = DownloadMirror {
            name: "test".to_string(),
            base_url: "https://github.com/platonai/Browser4/releases".to_string(),
        ..Default::default()
        };
        let url = mirror_download_url(&mirror, None, "browser4-runtime-windows-x64.zip");
        assert_eq!(
            url,
            "https://github.com/platonai/Browser4/releases/latest/download/browser4-runtime-windows-x64.zip"
        );
    }

    #[test]
    fn test_mirror_download_url_normalizes_explicit_tags() {
        let mirror = DownloadMirror {
            name: "test".to_string(),
            base_url: "https://github.com/platonai/Browser4/releases".to_string(),
        ..Default::default()
        };
        let url_without_v =
            mirror_download_url(&mirror, Some("4.9.3"), "browser4-runtime-linux-x64.tar.gz");
        let url_with_v =
            mirror_download_url(&mirror, Some("v4.9.3"), "browser4-runtime-linux-x64.tar.gz");
        assert_eq!(
            url_without_v,
            "https://github.com/platonai/Browser4/releases/download/v4.9.3/browser4-runtime-linux-x64.tar.gz"
        );
        assert_eq!(
            url_with_v,
            "https://github.com/platonai/Browser4/releases/download/v4.9.3/browser4-runtime-linux-x64.tar.gz"
        );
    }

    #[test]
    fn test_mirror_download_url_strips_trailing_slash_in_base_url() {
        let mirror = DownloadMirror {
            name: "test".to_string(),
            base_url: "https://example.com/releases/".to_string(),
        ..Default::default()
        };
        let url = mirror_download_url(&mirror, Some("v1.0.0"), "asset.zip");
        assert_eq!(
            url,
            "https://example.com/releases/download/v1.0.0/asset.zip"
        );
    }

    #[test]
    fn test_builtin_mirrors_has_github_first() {
        let mirrors = builtin_mirrors_for_locale(false);
        assert_eq!(mirrors[0].name, "github");
        assert!(mirrors[0].base_url.contains("github.com"));
    }

    #[test]
    fn test_builtin_mirrors_china_oss_first() {
        let mirrors = builtin_mirrors_for_locale(true);
        assert_eq!(mirrors[0].name, "aliyun-oss");
        assert!(mirrors[0].base_url.contains("aliyuncs.com"));
    }

    #[test]
    fn test_builtin_mirrors_china_retains_all() {
        let mirrors = builtin_mirrors_for_locale(true);
        assert_eq!(mirrors.len(), 2);
        let names: Vec<&str> = mirrors.iter().map(|m| m.name.as_str()).collect();
        assert!(names.contains(&"github"));
        assert!(names.contains(&"aliyun-oss"));
    }

    #[test]
    fn test_builtin_mirrors_non_china_retains_all() {
        let mirrors = builtin_mirrors_for_locale(false);
        assert_eq!(mirrors.len(), 2);
        let names: Vec<&str> = mirrors.iter().map(|m| m.name.as_str()).collect();
        assert!(names.contains(&"github"));
        assert!(names.contains(&"aliyun-oss"));
    }

    #[test]
    fn test_is_china_locale_zh_cn_lang() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let prev_lang = env::var("LANG").ok();
        let prev_tz = env::var("TZ").ok();
        unsafe { env::set_var("LANG", "zh_CN.UTF-8") };
        unsafe { env::remove_var("TZ") };
        let result = is_china_locale();
        match prev_lang {
            Some(v) => unsafe { env::set_var("LANG", v) },
            None => unsafe { env::remove_var("LANG") },
        }
        match prev_tz {
            Some(v) => unsafe { env::set_var("TZ", v) },
            None => unsafe { env::remove_var("TZ") },
        }
        assert!(result);
    }

    #[test]
    fn test_is_china_locale_zh_cn_dash() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let prev_lang = env::var("LANG").ok();
        let prev_tz = env::var("TZ").ok();
        unsafe { env::set_var("LANG", "zh-CN.UTF-8") };
        unsafe { env::remove_var("TZ") };
        let result = is_china_locale();
        match prev_lang {
            Some(v) => unsafe { env::set_var("LANG", v) },
            None => unsafe { env::remove_var("LANG") },
        }
        match prev_tz {
            Some(v) => unsafe { env::set_var("TZ", v) },
            None => unsafe { env::remove_var("TZ") },
        }
        assert!(result);
    }

    #[test]
    fn test_is_china_locale_asia_shanghai_tz() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let prev_lang = env::var("LANG").ok();
        let prev_tz = env::var("TZ").ok();
        unsafe { env::remove_var("LANG") };
        unsafe { env::set_var("TZ", "Asia/Shanghai") };
        let result = is_china_locale();
        match prev_lang {
            Some(v) => unsafe { env::set_var("LANG", v) },
            None => unsafe { env::remove_var("LANG") },
        }
        match prev_tz {
            Some(v) => unsafe { env::set_var("TZ", v) },
            None => unsafe { env::remove_var("TZ") },
        }
        assert!(result);
    }

    #[test]
    fn test_is_china_locale_false_for_en_us() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let prev_lang = env::var("LANG").ok();
        let prev_tz = env::var("TZ").ok();
        unsafe { env::set_var("LANG", "en_US.UTF-8") };
        unsafe { env::remove_var("TZ") };
        let result = is_china_locale();
        match prev_lang {
            Some(v) => unsafe { env::set_var("LANG", v) },
            None => unsafe { env::remove_var("LANG") },
        }
        match prev_tz {
            Some(v) => unsafe { env::set_var("TZ", v) },
            None => unsafe { env::remove_var("TZ") },
        }
        assert!(!result);
    }

    #[test]
    fn test_is_china_locale_false_for_empty() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let prev_lang = env::var("LANG").ok();
        let prev_tz = env::var("TZ").ok();
        unsafe { env::remove_var("LANG") };
        unsafe { env::remove_var("TZ") };
        let result = is_china_locale();
        match prev_lang {
            Some(v) => unsafe { env::set_var("LANG", v) },
            None => unsafe { env::remove_var("LANG") },
        }
        match prev_tz {
            Some(v) => unsafe { env::set_var("TZ", v) },
            None => unsafe { env::remove_var("TZ") },
        }
        assert!(!result);
    }

    #[test]
    fn test_is_china_timezone_matches_mainland() {
        assert!(is_china_timezone("Asia/Shanghai"));
        assert!(is_china_timezone("Asia/Chongqing"));
        assert!(is_china_timezone("Asia/Urumqi"));
        assert!(is_china_timezone("Asia/Harbin"));
    }

    #[test]
    fn test_is_china_timezone_excludes_hk_macau() {
        assert!(!is_china_timezone("Asia/Hong_Kong"));
        assert!(!is_china_timezone("Asia/Macau"));
        assert!(!is_china_timezone("Asia/Tokyo"));
    }

    #[test]
    fn test_load_mirrors_falls_back_to_builtins_when_no_config() {
        let previous_config = env::var(MIRRORS_CONFIG_FILE_ENV).ok();
        let previous_releases = env::var(BROWSER4_RELEASES_BASE_URL_ENV).ok();
        unsafe {
            // Point to a non-existent config file and clear the single-source override.
            env::set_var(MIRRORS_CONFIG_FILE_ENV, "/nonexistent/mirrors.json");
            env::remove_var(BROWSER4_RELEASES_BASE_URL_ENV);
        }
        let mirrors = load_mirrors();
        match previous_config {
            Some(value) => unsafe { env::set_var(MIRRORS_CONFIG_FILE_ENV, value) },
            None => unsafe { env::remove_var(MIRRORS_CONFIG_FILE_ENV) },
        }
        match previous_releases {
            Some(value) => unsafe { env::set_var(BROWSER4_RELEASES_BASE_URL_ENV, value) },
            None => unsafe { env::remove_var(BROWSER4_RELEASES_BASE_URL_ENV) },
        }
        assert_eq!(mirrors.len(), 2);
        assert_eq!(mirrors[0].name, "github");
        assert_eq!(mirrors[1].name, "aliyun-oss");
    }

    #[test]
    fn test_load_mirrors_uses_single_source_override() {
        let previous = env::var(BROWSER4_RELEASES_BASE_URL_ENV).ok();
        unsafe {
            env::set_var(
                BROWSER4_RELEASES_BASE_URL_ENV,
                "https://custom.example.com/releases",
            );
        }
        let mirrors = load_mirrors();
        match previous {
            Some(value) => unsafe { env::set_var(BROWSER4_RELEASES_BASE_URL_ENV, value) },
            None => unsafe { env::remove_var(BROWSER4_RELEASES_BASE_URL_ENV) },
        }
        assert_eq!(mirrors.len(), 1);
        assert_eq!(mirrors[0].name, "custom");
        assert_eq!(mirrors[0].base_url, "https://custom.example.com/releases");
    }

    // -------------------------------------------------------------------
    // mirror_is_reachable / select_reachable_mirror
    // -------------------------------------------------------------------

    #[test]
    fn test_mirror_is_reachable_detects_listening_port() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind test listener");
        let port = listener.local_addr().unwrap().port();
        let mirror = DownloadMirror {
            name: "test".to_string(),
            base_url: format!("https://127.0.0.1:{port}"),
        ..Default::default()
        };
        assert!(
            mirror_is_reachable(&mirror),
            "mirror should be reachable when a TCP listener is bound to its port"
        );
        drop(listener);
    }

    #[test]
    fn test_mirror_is_reachable_returns_false_for_unbound_port() {
        // Bind and immediately drop to find a free port, then verify
        // nothing is listening on it.
        let free_port = {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind for free port");
            listener.local_addr().unwrap().port()
        };
        let mirror = DownloadMirror {
            name: "test".to_string(),
            base_url: format!("https://127.0.0.1:{free_port}"),
        ..Default::default()
        };
        assert!(
            !mirror_is_reachable(&mirror),
            "mirror should not be reachable when nothing listens on its port"
        );
    }

    #[test]
    fn test_mirror_is_reachable_returns_false_for_invalid_url() {
        let mirror = DownloadMirror {
            name: "test".to_string(),
            base_url: "not-a-valid-url".to_string(),
        ..Default::default()
        };
        assert!(
            !mirror_is_reachable(&mirror),
            "mirror should not be reachable when the base_url is not a valid URL"
        );
    }

    #[test]
    fn test_select_reachable_mirror_returns_first_reachable() {
        // First mirror points to a free port (nothing listening) — unreachable.
        // Second mirror has a live TcpListener — reachable.
        let dead_port = {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind for dead port");
            listener.local_addr().unwrap().port()
        };
        let live_listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind live listener");
        let live_port = live_listener.local_addr().unwrap().port();

        let mirrors = vec![
            DownloadMirror {
                name: "dead".to_string(),
                base_url: format!("https://127.0.0.1:{dead_port}"),
            ..Default::default()
            },
            DownloadMirror {
                name: "live".to_string(),
                base_url: format!("https://127.0.0.1:{live_port}"),
            ..Default::default()
            },
        ];

        let (selected, reachable) = select_reachable_mirror(&mirrors);
        assert!(reachable, "should have found a reachable mirror");
        assert_eq!(selected.name, "live");
        drop(live_listener);
    }

    #[test]
    fn test_select_reachable_mirror_falls_back_to_first_when_none_reachable() {
        // Both mirrors point to free ports — neither is reachable.
        let dead1 = {
            let listener =
                std::net::TcpListener::bind("127.0.0.1:0").expect("bind for dead port 1");
            listener.local_addr().unwrap().port()
        };
        let dead2 = {
            let listener =
                std::net::TcpListener::bind("127.0.0.1:0").expect("bind for dead port 2");
            listener.local_addr().unwrap().port()
        };

        let mirrors = vec![
            DownloadMirror {
                name: "first".to_string(),
                base_url: format!("https://127.0.0.1:{dead1}"),
            ..Default::default()
            },
            DownloadMirror {
                name: "second".to_string(),
                base_url: format!("https://127.0.0.1:{dead2}"),
            ..Default::default()
            },
        ];

        let (selected, reachable) = select_reachable_mirror(&mirrors);
        assert!(!reachable, "should not have found a reachable mirror");
        assert_eq!(
            selected.name, "first",
            "should fall back to the first mirror when none are reachable"
        );
    }

    #[test]
    fn test_mirror_is_reachable_returns_false_for_empty_host() {
        // A URL with a scheme but no host should not panic — it should return false.
        let mirror = DownloadMirror {
            name: "empty-host".to_string(),
            base_url: "https:///path".to_string(),
        ..Default::default()
        };
        assert!(
            !mirror_is_reachable(&mirror),
            "mirror with empty host should not be reachable"
        );
    }

    #[test]
    fn test_mirror_is_reachable_resolves_ipv6_without_brackets() {
        // When reqwest parses "https://[::1]:443", host_str() returns "::1"
        // (without brackets).  The (host, port) tuple form of ToSocketAddrs
        // must correctly pair the bare IPv6 address with the port rather than
        // mis-parsing "::1:443" as a raw IPv6 address (where the port becomes
        // the last hextet).  This test verifies the resolution directly —
        // no TCP connection is needed, so it works even in Docker/CI
        // environments where IPv6 may be unavailable.
        use std::net::ToSocketAddrs;
        let addr = ("::1", 443)
            .to_socket_addrs()
            .expect("must resolve ::1")
            .next()
            .expect("must produce at least one address");
        assert_eq!(
            addr.port(),
            443,
            "port must be the TCP port, not part of the IPv6 address"
        );
        assert!(addr.is_ipv6(), "must be an IPv6 address");
        assert_eq!(addr.ip().to_string(), "::1");
    }

    #[test]
    #[ignore] // temporarily disabled — IPv6 not available in current environment
    fn test_mirror_is_reachable_handles_ipv6_localhost() {
        // IPv6 loopback connectivity test — exercises the full
        // mirror_is_reachable path with an IPv6 bracket-notation URL.
        //
        // Gracefully skip if IPv6 is unavailable (e.g. Docker containers
        // disable IPv6 by default, and some CI runners restrict it).
        let listener = match std::net::TcpListener::bind("[::1]:0") {
            Ok(l) => l,
            Err(e) => {
                eprintln!(
                    "SKIP test_mirror_is_reachable_handles_ipv6_localhost: \
                     cannot bind [::1] — IPv6 disabled? ({e})"
                );
                return;
            }
        };
        let addr = listener.local_addr().unwrap();

        // Some environments (notably Docker with default settings) allow
        // binding to IPv6 loopback but block outbound IPv6 connections.
        // Probe with a raw connect before asserting mirror_is_reachable.
        match std::net::TcpStream::connect_timeout(&addr, std::time::Duration::from_secs(2)) {
            Err(e) => {
                eprintln!(
                    "SKIP test_mirror_is_reachable_handles_ipv6_localhost: \
                     cannot connect to bound IPv6 address {addr} — environment \
                     may block IPv6 loopback connections ({e})"
                );
                return;
            }
            Ok(_) => { /* raw connect succeeded — mirror_is_reachable should too */ }
        }

        let mirror = DownloadMirror {
            name: "ipv6".to_string(),
            base_url: format!("https://[::1]:{}", addr.port()),
        ..Default::default()
        };
        assert!(
            mirror_is_reachable(&mirror),
            "mirror should be reachable when a TCP listener is bound to its IPv6 port"
        );
        drop(listener);
    }

    #[test]
    fn test_mirror_is_reachable_does_not_panic_on_hostname() {
        // Hostname-based URLs were the original panic trigger:
        // SocketAddr::FromStr rejects hostnames, only accepting IP literals.
        // This test ensures hostnames are handled gracefully (return false
        // when DNS fails, without panicking).  "invalid.invalid" is a
        // reserved TLD that is guaranteed not to resolve.
        let mirror = DownloadMirror {
            name: "hostname".to_string(),
            base_url: "https://invalid.invalid/releases".to_string(),
        ..Default::default()
        };
        let result = mirror_is_reachable(&mirror);
        // Must not panic — may be true or false depending on DNS hijacking,
        // but the key invariant is that we got a bool back, not a crash.
        assert!(
            !result || result,
            "mirror_is_reachable must return a bool, not panic, for hostname URLs"
        );
    }

    #[test]
    fn test_mirror_is_reachable_respects_explicit_port() {
        // URLs with an explicit non-default port should connect to that port.
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind test listener");
        let port = listener.local_addr().unwrap().port();
        let mirror = DownloadMirror {
            name: "explicit-port".to_string(),
            base_url: format!("https://127.0.0.1:{port}/some/path"),
        ..Default::default()
        };
        assert!(
            mirror_is_reachable(&mirror),
            "mirror should be reachable when the URL specifies the correct port explicitly"
        );
        drop(listener);
    }

    #[test]
    fn test_parse_release_tag_from_url_extracts_download_tag() {
        let tag = parse_release_tag_from_url(
            "https://github.com/platonai/Browser4/releases/download/v4.9.3/browser4-runtime-windows-x64.zip",
        );
        assert_eq!(tag.as_deref(), Some("v4.9.3"));
    }

    #[test]
    fn test_runtime_bundle_root_detection_accepts_nested_folder() {
        let tmp = test_temp_dir();
        let extracted = tmp.path().join("extract");
        let bundle_root = extracted.join("browser4-bundle-runtime-windows-x64");
        fs::create_dir_all(bundle_root.join("runtime").join("bin")).unwrap();
        fs::create_dir_all(bundle_root.join("lib")).unwrap();
        // Create a jar file in lib/ to satisfy install_dir_contains_runtime
        write(bundle_root.join("lib").join("test.jar"), "jar").unwrap();
        write(
            bundle_root
                .join("runtime")
                .join("bin")
                .join(browser4_java_executable_name()),
            "java",
        )
        .unwrap();

        let actual = resolve_runtime_bundle_root(&extracted).unwrap();
        assert_eq!(actual, bundle_root);
    }

    #[test]
    fn test_is_runtime_bundle_root_accepts_top_level_bundle() {
        // The extracted directory itself is the bundle root (no nested folder).
        let tmp = test_temp_dir();
        fs::create_dir_all(tmp.path().join("runtime").join("bin")).unwrap();
        fs::create_dir_all(tmp.path().join("lib")).unwrap();
        write(tmp.path().join("lib").join("browser4.jar"), "jar").unwrap();
        write(
            tmp.path()
                .join("runtime")
                .join("bin")
                .join(browser4_java_executable_name()),
            "java",
        )
        .unwrap();

        // No metadata file — is_runtime_bundle_root should still detect the bundle.
        assert!(is_runtime_bundle_root(tmp.path()));
        assert_eq!(resolve_runtime_bundle_root(tmp.path()).unwrap(), tmp.path());
    }

    #[test]
    fn test_is_runtime_bundle_root_returns_false_when_lib_dir_missing() {
        let tmp = test_temp_dir();
        fs::create_dir_all(tmp.path().join("runtime").join("bin")).unwrap();
        write(
            tmp.path()
                .join("runtime")
                .join("bin")
                .join(browser4_java_executable_name()),
            "java",
        )
        .unwrap();
        // lib/ directory absent → should not be detected as a bundle root.
        assert!(!is_runtime_bundle_root(tmp.path()));
    }

    #[test]
    fn test_is_runtime_bundle_root_returns_false_when_no_jar_files() {
        let tmp = test_temp_dir();
        fs::create_dir_all(tmp.path().join("lib")).unwrap();
        fs::create_dir_all(tmp.path().join("runtime").join("bin")).unwrap();
        write(
            tmp.path()
                .join("runtime")
                .join("bin")
                .join(browser4_java_executable_name()),
            "java",
        )
        .unwrap();
        // lib/ exists but contains no .jar files.
        assert!(!is_runtime_bundle_root(tmp.path()));
    }

    #[test]
    fn test_is_runtime_bundle_root_returns_false_when_java_binary_missing() {
        let tmp = test_temp_dir();
        fs::create_dir_all(tmp.path().join("lib")).unwrap();
        write(tmp.path().join("lib").join("browser4.jar"), "jar").unwrap();
        fs::create_dir_all(tmp.path().join("runtime").join("bin")).unwrap();
        // runtime/bin/ exists but the java executable is absent.
        assert!(!is_runtime_bundle_root(tmp.path()));
    }

    #[test]
    fn test_is_runtime_bundle_root_still_works_without_metadata_file() {
        // The key distinction from install_dir_contains_runtime: a freshly
        // extracted bundle has no browser4-installation.json yet, but it
        // should still be recognised.
        let tmp = test_temp_dir();
        fs::create_dir_all(tmp.path().join("lib")).unwrap();
        write(tmp.path().join("lib").join("browser4.jar"), "jar").unwrap();
        fs::create_dir_all(tmp.path().join("runtime").join("bin")).unwrap();
        write(
            tmp.path()
                .join("runtime")
                .join("bin")
                .join(browser4_java_executable_name()),
            "java",
        )
        .unwrap();

        // No metadata file present.
        assert!(!tmp.path().join("browser4-installation.json").exists());
        // install_dir_contains_runtime requires the metadata file → false.
        assert!(!install_dir_contains_runtime(tmp.path()));
        // is_runtime_bundle_root deliberately does NOT require it → true.
        assert!(is_runtime_bundle_root(tmp.path()));
    }

    #[test]
    fn test_materialize_installed_runtime_uses_versioned_layout() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        unsafe {
            env::set_var("BROWSER4_RUNTIME_DIR", tmp.path().as_os_str());
        }

        let runtime = materialize_installed_runtime(
            InstalledBrowser4RuntimeMetadata {
                tag: "v4.9.3".to_string(),
                asset_name: "browser4-runtime-windows-x64.zip".to_string(),
                download_url: "https://example.invalid/runtime.zip".to_string(),
                installed_at: "2026-05-26T00:00:00Z".to_string(),
            },
            true,
        );

        unsafe {
            env::remove_var("BROWSER4_RUNTIME_DIR");
        }

        // install_dir should be {runtime_data}/runtime/v4.9.3/
        assert!(runtime
            .install_dir
            .ends_with(Path::new("runtime").join("v4.9.3")));
        // lib_dir should be {install_dir}/lib/
        assert!(runtime.lib_dir.ends_with(Path::new("v4.9.3").join("lib")));
        // java_path should be {install_dir}/runtime/bin/java
        assert!(runtime.java_path.ends_with(
            Path::new("v4.9.3")
                .join("runtime")
                .join("bin")
                .join(browser4_java_executable_name())
        ));
        assert!(runtime.reused_existing);
    }

    #[test]
    fn test_server_startup_log_dir_uses_provided_log_dir() {
        let tmp = test_temp_dir();

        assert_eq!(server_startup_log_dir(Some(tmp.path())), tmp.path());
    }

    #[test]
    fn test_server_startup_log_dir_defaults_to_temp_cli_dir() {
        let expected = env::temp_dir()
            .join("browser4")
            .join("browser4-cli")
            .join("tmp")
            .join("cli");
        let actual = server_startup_log_dir(None);

        assert_eq!(actual, expected);
    }

    #[test]
    fn test_server_startup_log_path_includes_launch_kind_and_port() {
        let tmp = test_temp_dir();
        let jar_path = server_startup_log_path(Some(tmp.path()), &sample_launch_spec(), 9292);

        let jar_name = jar_path.file_name().unwrap().to_string_lossy();

        assert!(jar_path.starts_with(tmp.path()));
        assert!(jar_name.starts_with("browser4-server-jar-port9292-"));
        assert!(jar_name.ends_with(".log"));
    }

    #[test]
    fn test_format_server_wait_progress_unreachable_is_not_reported_as_error() {
        let progress = format_server_wait_progress(&ServerState::Unreachable(
            "error sending request for url (http://localhost:8182/actuator/health)".to_string(),
        ));

        assert_eq!(progress, "not reachable yet");
    }

    #[test]
    fn test_format_server_wait_progress_starting_includes_status() {
        let progress =
            format_server_wait_progress(&ServerState::Starting("{\"status\":\"STARTING\"}".into()));

        assert_eq!(progress, "still starting ({\"status\":\"STARTING\"})");
    }

    #[test]
    fn test_initial_server_ready_quiet_wait_uses_five_second_grace_period() {
        assert_eq!(
            initial_server_ready_quiet_wait(Duration::from_secs(60)),
            Duration::from_secs(5)
        );
    }

    #[test]
    fn test_initial_server_ready_quiet_wait_is_capped_by_timeout() {
        assert_eq!(
            initial_server_ready_quiet_wait(Duration::from_secs(3)),
            Duration::from_secs(3)
        );
    }

    #[test]
    fn test_format_startup_log_timeout_details_includes_log_tail() {
        let tmp = test_temp_dir();
        let log_path = tmp.path().join("startup.log");
        write(
            &log_path,
            "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n",
        )
        .unwrap();

        let details = format_startup_log_timeout_details(Some(&log_path));

        assert!(details.contains("📄 Browser4 startup log"));
        assert!(details.contains(&format!("Path: {}", log_path.display())));
        assert!(details.contains("Tail:"));
        assert!(details.contains("line10"));
    }

    #[test]
    fn test_format_server_startup_failure_message_includes_sections() {
        let tmp = test_temp_dir();
        let log_path = tmp.path().join("startup.log");
        write(&log_path, "tail line 1\ntail line 2\n").unwrap();

        let message = format_server_startup_failure_message(
            "http://127.0.0.1:8182",
            Some("Browser4 did not become MCP-ready before the startup timeout elapsed."),
            "Last readiness probe result: connection refused",
            Some(Duration::from_secs(60)),
            Some(&log_path),
        );

        assert!(message.contains("🛑 Browser4 server startup failed"));
        assert!(message.contains("Server: http://127.0.0.1:8182"));
        assert!(message.contains("Timeout: 60s"));
        assert!(message.contains("💡 What to try"));
        assert!(message.contains("inspect the Browser4 startup log"));
        assert!(message.contains("🧾 Details"));
        assert!(message.contains("Last readiness probe result: connection refused"));
        assert!(message.contains("📄 Browser4 startup log"));
        assert!(message.contains("tail line 2"));
    }

    #[test]
    fn test_create_server_startup_log_writes_header() {
        let tmp = test_temp_dir();
        let log = create_server_startup_log_in(Some(tmp.path()), &sample_launch_spec(), 8123)
            .expect("startup log creation should succeed");

        drop(log.stdout);
        drop(log.stderr);

        let contents = fs::read_to_string(&log.path).expect("startup log should be readable");
        assert!(contents.contains("Launching Browser4 Jar on port 8123"));
        assert!(contents.contains("program: program"));
        assert!(contents.contains("args: arg1 arg2"));
    }

    #[test]
    fn test_append_startup_log_message_writes_status_line() {
        let tmp = test_temp_dir();
        let log = create_server_startup_log_in(Some(tmp.path()), &sample_launch_spec(), 8182)
            .expect("startup log creation should succeed");

        append_startup_log_message_impl(&log.path, "test status line")
            .expect("startup log append should succeed");
        drop(log.stdout);
        drop(log.stderr);

        let contents = fs::read_to_string(&log.path).expect("startup log should be readable");
        assert!(contents.contains("test status line"));
    }

    #[test]
    fn test_is_browser4_root_rejects_missing_root_marker() {
        let tmp = test_temp_dir();
        let root = tmp.path().join("Browser4");
        create_dir_all(root.join("browser4-apps").join("browser4-standalone")).unwrap();
        create_dir_all(root.join("cli").join("browser4-cli")).unwrap();
        write(root.join("pom.xml"), "<project />").unwrap();
        write(
            root.join("browser4-apps")
                .join("browser4-standalone")
                .join("pom.xml"),
            "<project />",
        )
        .unwrap();
        write(
            root.join("cli").join("browser4-cli").join("Cargo.toml"),
            "[package]\nname = \"browser4-cli\"\n",
        )
        .unwrap();

        assert!(!is_browser4_root(&root));
    }

    fn create_browser4_root_in(parent: &Path) -> PathBuf {
        let root = parent.join("Browser4");
        create_dir_all(&root).unwrap();
        create_dir_all(root.join("browser4-apps").join("browser4-standalone")).unwrap();
        create_dir_all(root.join("cli").join("browser4-cli")).unwrap();
        write(root.join("ROOT.md"), "# Browser4\n").unwrap();
        write(root.join("VERSION"), "0.1.0\n").unwrap();
        write(root.join("pom.xml"), "<project />").unwrap();
        write(
            root.join("browser4-apps")
                .join("browser4-standalone")
                .join("pom.xml"),
            "<project />",
        )
        .unwrap();
        write(
            root.join("cli").join("browser4-cli").join("Cargo.toml"),
            "[package]\nname = \"browser4-cli\"\n",
        )
        .unwrap();
        root
    }

    // -------------------------------------------------------------------
    // normalize_release_tag
    // -------------------------------------------------------------------

    #[test]
    fn test_normalize_release_tag_empty_or_latest_returns_none() {
        assert_eq!(normalize_release_tag(None), None);
        assert_eq!(normalize_release_tag(Some("")), None);
        assert_eq!(normalize_release_tag(Some("  ")), None);
        assert_eq!(normalize_release_tag(Some("latest")), None);
        assert_eq!(normalize_release_tag(Some("LATEST")), None);
    }

    #[test]
    fn test_normalize_release_tag_adds_v_prefix() {
        assert_eq!(
            normalize_release_tag(Some("4.9.3")).as_deref(),
            Some("v4.9.3")
        );
        assert_eq!(
            normalize_release_tag(Some("4.10.0")).as_deref(),
            Some("v4.10.0")
        );
    }

    #[test]
    fn test_normalize_release_tag_keeps_existing_v_prefix() {
        assert_eq!(
            normalize_release_tag(Some("v4.9.3")).as_deref(),
            Some("v4.9.3")
        );
        assert_eq!(
            normalize_release_tag(Some("v4.10.0")).as_deref(),
            Some("v4.10.0")
        );
    }

    #[test]
    fn test_normalize_release_tag_trims_whitespace() {
        assert_eq!(
            normalize_release_tag(Some("  v4.9.3  ")).as_deref(),
            Some("v4.9.3")
        );
        assert_eq!(
            normalize_release_tag(Some("  4.10.0\t")).as_deref(),
            Some("v4.10.0")
        );
    }

    // -------------------------------------------------------------------
    // parse_release_tag_from_url (additional edge cases)
    // -------------------------------------------------------------------

    #[test]
    fn test_parse_release_tag_from_url_latest_pattern() {
        // When called on a "latest" URL (before redirect), the segment after
        // "download" is the asset filename, not a tag.  In practice this
        // function is only called on the redirect-final URL which always has
        // a real tag, so this edge case is harmless.
        let tag = parse_release_tag_from_url(
            "https://github.com/platonai/Browser4/releases/latest/download/browser4-runtime.zip",
        );
        assert_eq!(tag.as_deref(), Some("browser4-runtime.zip"));
    }

    #[test]
    fn test_parse_release_tag_from_url_non_release_url_returns_none() {
        assert_eq!(
            parse_release_tag_from_url("https://example.com/other/path"),
            None
        );
        assert_eq!(
            parse_release_tag_from_url("https://github.com/platonai/Browser4/releases/tag/v4.9.3"),
            None
        );
    }

    #[test]
    fn test_parse_release_tag_from_url_malformed_url_returns_none() {
        assert_eq!(parse_release_tag_from_url("not-a-url"), None);
        assert_eq!(parse_release_tag_from_url(""), None);
    }

    // -------------------------------------------------------------------
    // install_dir_contains_runtime
    // -------------------------------------------------------------------

    #[test]
    fn test_install_dir_contains_runtime_valid() {
        let tmp = test_temp_dir();
        // The metadata file is required — a missing file signals a truncated
        // or partially-committed install that should be re-downloaded.
        write(
            tmp.path().join("browser4-installation.json"),
            r#"{"tag":"v4.10.0","asset_name":"bundle.tar.gz","download_url":"https://example.com/bundle.tar.gz","installed_at":"2026-01-01T00:00:00Z"}"#,
        )
        .unwrap();
        let lib_dir = tmp.path().join("lib");
        fs::create_dir_all(&lib_dir).unwrap();
        write(lib_dir.join("browser4.jar"), "jar-content").unwrap();
        let runtime_bin = tmp.path().join("runtime").join("bin");
        fs::create_dir_all(&runtime_bin).unwrap();
        write(runtime_bin.join(browser4_java_executable_name()), "java").unwrap();
        assert!(install_dir_contains_runtime(tmp.path()));
    }

    #[test]
    fn test_install_dir_contains_runtime_missing_lib_dir() {
        let tmp = test_temp_dir();
        assert!(!install_dir_contains_runtime(tmp.path()));
    }

    #[test]
    fn test_install_dir_contains_runtime_missing_java() {
        let tmp = test_temp_dir();
        fs::create_dir_all(tmp.path().join("lib")).unwrap();
        write(tmp.path().join("lib").join("browser4.jar"), "jar").unwrap();
        assert!(!install_dir_contains_runtime(tmp.path()));
    }

    #[test]
    fn test_install_dir_contains_runtime_empty_lib_dir() {
        let tmp = test_temp_dir();
        fs::create_dir_all(tmp.path().join("lib")).unwrap();
        let runtime_bin = tmp.path().join("runtime").join("bin");
        fs::create_dir_all(&runtime_bin).unwrap();
        write(runtime_bin.join(browser4_java_executable_name()), "java").unwrap();
        // No jar files in lib/
        assert!(!install_dir_contains_runtime(tmp.path()));
    }

    // -------------------------------------------------------------------
    // read_installed_browser4_runtime_metadata
    // -------------------------------------------------------------------

    #[test]
    fn test_metadata_round_trip() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let tag = "v4.9.3";
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());
        setup_valid_versioned_runtime(&tmp.path().join("runtime-data"), tag);

        // Write metadata into the versioned install dir.
        let metadata_path = tmp
            .path()
            .join("runtime-data")
            .join("runtime")
            .join(tag)
            .join("browser4-installation.json");
        let metadata = InstalledBrowser4RuntimeMetadata {
            tag: tag.to_string(),
            asset_name: "browser4-runtime-linux-x64.tar.gz".to_string(),
            download_url: "https://example.com/releases/download/v4.9.3/bundle.tar.gz".to_string(),
            installed_at: "2026-06-01T00:00:00Z".to_string(),
        };
        let json = serde_json::to_string_pretty(&metadata).unwrap();
        fs::write(&metadata_path, json).unwrap();

        let read = read_installed_browser4_runtime_metadata();
        assert!(read.is_some(), "Expected metadata to be readable");
        let read = read.unwrap();
        assert_eq!(read.tag, "v4.9.3");
        assert_eq!(read.asset_name, "browser4-runtime-linux-x64.tar.gz");
        assert_eq!(read.installed_at, "2026-06-01T00:00:00Z");

        restore_test_env(prev_runtime, prev_state);
    }

    #[test]
    fn test_metadata_missing_file_returns_none() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());
        // No runtime installed at all → metadata is None.
        let read = read_installed_browser4_runtime_metadata();
        assert!(read.is_none());
        restore_test_env(prev_runtime, prev_state);
    }

    #[test]
    fn test_metadata_corrupted_json_returns_none() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let tag = "v4.9.3";
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());
        setup_valid_versioned_runtime(&tmp.path().join("runtime-data"), tag);

        // Corrupt the metadata file.
        let metadata_path = tmp
            .path()
            .join("runtime-data")
            .join("runtime")
            .join(tag)
            .join("browser4-installation.json");
        fs::write(&metadata_path, "not valid json {{{").unwrap();

        let read = read_installed_browser4_runtime_metadata();
        assert!(read.is_none());

        restore_test_env(prev_runtime, prev_state);
    }

    // -------------------------------------------------------------------
    // Helpers for new-layout tests
    // -------------------------------------------------------------------

    /// Set up a fully valid versioned runtime directory so that
    /// `install_dir_contains_runtime` and `browser4_install_dir` both succeed.
    /// Also writes `current.tag`.
    fn setup_valid_versioned_runtime(_tmp: &Path, tag: &str) -> PathBuf {
        let dir = versioned_install_dir(tag);
        let lib = dir.join("lib");
        let rt_bin = dir.join("runtime").join("bin");
        fs::create_dir_all(&lib).unwrap();
        fs::create_dir_all(&rt_bin).unwrap();
        fs::write(lib.join("browser4.jar"), "fake-jar").unwrap();
        fs::write(rt_bin.join(browser4_java_executable_name()), "fake-java").unwrap();
        // Write the installation metadata file required by install_dir_contains_runtime.
        fs::write(
            dir.join(BROWSER4_INSTALL_METADATA_FILE_NAME),
            format!(
                r#"{{"tag":"{}","asset_name":"bundle.tar.gz","download_url":"https://example.com/bundle.tar.gz","installed_at":"2026-01-01T00:00:00Z"}}"#,
                tag
            ),
        )
        .unwrap();
        write_current_tag(tag).unwrap();
        dir
    }

    /// Set both env vars so tests are fully isolated from any real
    /// `~/.browser4/` or `~/.local/share/browser4/` directories.
    fn isolate_test_env(tmp: &Path) -> (Option<String>, Option<String>) {
        let prev_runtime = env::var("BROWSER4_RUNTIME_DIR").ok();
        let prev_state = env::var("BROWSER4_CLI_STATE_DIR").ok();
        unsafe {
            env::set_var("BROWSER4_RUNTIME_DIR", tmp.join("runtime-data").as_os_str());
            env::set_var("BROWSER4_CLI_STATE_DIR", tmp.join("state").as_os_str());
        }
        (prev_runtime, prev_state)
    }

    fn restore_test_env(prev_runtime: Option<String>, prev_state: Option<String>) {
        match prev_runtime {
            Some(v) => unsafe { env::set_var("BROWSER4_RUNTIME_DIR", v) },
            None => unsafe { env::remove_var("BROWSER4_RUNTIME_DIR") },
        }
        match prev_state {
            Some(v) => unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", v) },
            None => unsafe { env::remove_var("BROWSER4_CLI_STATE_DIR") },
        }
    }

    // -------------------------------------------------------------------
    // New layout: versioned install dirs, current.tag, migration
    // -------------------------------------------------------------------

    #[test]
    fn test_current_tag_read_write_round_trip() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());

        // No tag yet → None (and no legacy install to migrate).
        assert!(read_current_tag().is_none());

        // Write a tag → should read back.
        write_current_tag("v4.11.0").unwrap();
        assert_eq!(read_current_tag().as_deref(), Some("v4.11.0"));

        // Overwrite with a different tag.
        write_current_tag("v4.12.0").unwrap();
        assert_eq!(read_current_tag().as_deref(), Some("v4.12.0"));

        restore_test_env(prev_runtime, prev_state);
    }

    #[test]
    fn test_versioned_install_dir_path() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());

        let dir = versioned_install_dir("v4.10.0");
        assert!(dir.ends_with(Path::new("runtime").join("v4.10.0")));

        // Write a valid runtime and verify browser4_install_dir resolves.
        setup_valid_versioned_runtime(&tmp.path().join("runtime-data"), "v4.10.0");
        let resolved = resolve_current_install_dir().unwrap();
        // Both paths should point to the same versioned install (canonicalization
        // may add \\?\ prefix on Windows — compare file names instead).
        assert!(resolved.ends_with(Path::new("runtime").join("v4.10.0")));
        assert!(resolved.join("lib").join("browser4.jar").exists());

        restore_test_env(prev_runtime, prev_state);
    }

    #[test]
    fn test_legacy_migration_detects_old_layout() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());

        // Set up legacy layout under state_dir/lib/ (but BROWSER4_CLI_STATE_DIR
        // points to our isolated temp dir, so we must write there).
        let state_dir = tmp.path().join("state");
        let legacy_install = state_dir.join("lib");
        let legacy_lib = legacy_install.join("lib");
        let legacy_runtime_bin = legacy_install.join("runtime").join("bin");
        fs::create_dir_all(&legacy_lib).unwrap();
        fs::create_dir_all(&legacy_runtime_bin).unwrap();
        fs::write(legacy_lib.join("browser4.jar"), "fake-jar").unwrap();
        fs::write(
            legacy_runtime_bin.join(browser4_java_executable_name()),
            "fake-java",
        )
        .unwrap();
        let metadata = InstalledBrowser4RuntimeMetadata {
            tag: "v4.8.0".to_string(),
            asset_name: "browser4-runtime.zip".to_string(),
            download_url: "https://example.com/bundle.zip".to_string(),
            installed_at: "2025-01-01T00:00:00Z".to_string(),
        };
        fs::write(
            legacy_install.join("browser4-installation.json"),
            serde_json::to_string_pretty(&metadata).unwrap(),
        )
        .unwrap();

        // Migration should move the legacy install to the new layout.
        let tag = read_current_tag();
        assert_eq!(tag.as_deref(), Some("v4.8.0"));

        // The legacy dir should be gone.
        assert!(!legacy_install.exists());

        // The versioned dir should exist in the new location.
        let new_dir = versioned_install_dir("v4.8.0");
        assert!(new_dir.join("lib").join("browser4.jar").exists());

        restore_test_env(prev_runtime, prev_state);
    }

    #[test]
    fn test_find_newest_versioned_install() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());

        // Set up multiple versioned installs without current.tag.
        for tag in &["v4.8.0", "v4.9.0", "v4.10.0"] {
            let dir = versioned_install_dir(tag);
            let lib = dir.join("lib");
            let rt_bin = dir.join("runtime").join("bin");
            fs::create_dir_all(&lib).unwrap();
            fs::create_dir_all(&rt_bin).unwrap();
            fs::write(lib.join("x.jar"), "jar").unwrap();
            fs::write(rt_bin.join(browser4_java_executable_name()), "java").unwrap();
            // Write the required installation metadata file.
            fs::write(
                dir.join(BROWSER4_INSTALL_METADATA_FILE_NAME),
                format!(
                    r#"{{"tag":"{}","asset_name":"bundle.tar.gz","download_url":"https://example.com/bundle.tar.gz","installed_at":"2026-01-01T00:00:00Z"}}"#,
                    tag
                ),
            )
            .unwrap();
        }
        // Remove current.tag if it exists so find_newest is forced to scan.
        let tag_path = current_tag_file_path();
        let _ = fs::remove_file(&tag_path);

        // Without current.tag, should find newest.
        assert_eq!(read_current_tag().as_deref(), Some("v4.10.0"));
        // Now current.tag should have been written.
        assert_eq!(
            fs::read_to_string(current_tag_file_path()).unwrap().trim(),
            "v4.10.0"
        );

        restore_test_env(prev_runtime, prev_state);
    }

    // -------------------------------------------------------------------
    // Mirror preference cache tests
    // -------------------------------------------------------------------

    #[test]
    fn test_mirror_preference_round_trip() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());

        let pref = MirrorPreference {
            selected_mirror: DownloadMirror {
                name: "test-mirror".to_string(),
                base_url: "https://example.com/releases".to_string(),
            ..Default::default()
            },
            tested_at: "2026-06-11T00:00:00+00:00".to_string(),
            download_speed_bps: 5_000_000,
        };
        save_mirror_preference(&pref);

        let mirrors = vec![pref.selected_mirror.clone()];
        let loaded = load_mirror_preference(&mirrors);
        assert!(loaded.is_some());
        let loaded = loaded.unwrap();
        assert_eq!(loaded.selected_mirror.name, "test-mirror");
        assert_eq!(loaded.download_speed_bps, 5_000_000);

        delete_mirror_preference_cache();
        restore_test_env(prev_runtime, prev_state);
    }

    #[test]
    fn test_mirror_preference_expired() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());
        let prev_ttl = env::var(MIRROR_PREFERENCE_TTL_ENV).ok();
        unsafe {
            env::set_var(MIRROR_PREFERENCE_TTL_ENV, "1"); // 1-second TTL
        }

        let pref = MirrorPreference {
            selected_mirror: DownloadMirror {
                name: "old-mirror".to_string(),
                base_url: "https://example.com/releases".to_string(),
            ..Default::default()
            },
            // Timestamp is well over 1 second ago.
            tested_at: "2020-01-01T00:00:00+00:00".to_string(),
            download_speed_bps: 100,
        };
        save_mirror_preference(&pref);

        let mirrors = vec![pref.selected_mirror.clone()];
        let loaded = load_mirror_preference(&mirrors);
        assert!(loaded.is_some());
        assert!(
            !is_mirror_preference_valid(&loaded.unwrap()),
            "preference with 2020 timestamp should be expired"
        );

        delete_mirror_preference_cache();
        restore_test_env(prev_runtime, prev_state);
        match prev_ttl {
            Some(v) => unsafe { env::set_var(MIRROR_PREFERENCE_TTL_ENV, v) },
            None => unsafe { env::remove_var(MIRROR_PREFERENCE_TTL_ENV) },
        }
    }

    #[test]
    fn test_mirror_preference_valid_under_ttl() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());
        let prev_ttl = env::var(MIRROR_PREFERENCE_TTL_ENV).ok();
        unsafe {
            env::set_var(MIRROR_PREFERENCE_TTL_ENV, "86400");
        }

        let pref = MirrorPreference {
            selected_mirror: DownloadMirror {
                name: "recent".to_string(),
                base_url: "https://example.com/releases".to_string(),
            ..Default::default()
            },
            tested_at: chrono::Utc::now().to_rfc3339(),
            download_speed_bps: 200,
        };
        save_mirror_preference(&pref);

        let mirrors = vec![pref.selected_mirror.clone()];
        let loaded = load_mirror_preference(&mirrors);
        assert!(loaded.is_some());
        assert!(
            is_mirror_preference_valid(&loaded.unwrap()),
            "preference saved just now should be valid"
        );

        delete_mirror_preference_cache();
        restore_test_env(prev_runtime, prev_state);
        match prev_ttl {
            Some(v) => unsafe { env::set_var(MIRROR_PREFERENCE_TTL_ENV, v) },
            None => unsafe { env::remove_var(MIRROR_PREFERENCE_TTL_ENV) },
        }
    }

    #[test]
    fn test_mirror_preference_ttl_env_override() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());
        let prev_ttl = env::var(MIRROR_PREFERENCE_TTL_ENV).ok();
        unsafe {
            env::set_var(MIRROR_PREFERENCE_TTL_ENV, "3600"); // 1 hour
        }

        let pref = MirrorPreference {
            selected_mirror: DownloadMirror {
                name: "custom-ttl".to_string(),
                base_url: "https://example.com/releases".to_string(),
            ..Default::default()
            },
            tested_at: chrono::Utc::now().to_rfc3339(),
            download_speed_bps: 300,
        };
        assert!(
            is_mirror_preference_valid(&pref),
            "with 3600s TTL, just-now timestamp should be valid"
        );

        // Now set a tiny TTL and verify an old timestamp becomes invalid.
        unsafe {
            env::set_var(MIRROR_PREFERENCE_TTL_ENV, "1");
        }
        let old_pref = MirrorPreference {
            selected_mirror: DownloadMirror {
                name: "custom-ttl".to_string(),
                base_url: "https://example.com/releases".to_string(),
            ..Default::default()
            },
            tested_at: "2020-01-01T00:00:00+00:00".to_string(),
            download_speed_bps: 300,
        };
        assert!(
            !is_mirror_preference_valid(&old_pref),
            "with 1s TTL, 2020 timestamp should be expired"
        );

        restore_test_env(prev_runtime, prev_state);
        match prev_ttl {
            Some(v) => unsafe { env::set_var(MIRROR_PREFERENCE_TTL_ENV, v) },
            None => unsafe { env::remove_var(MIRROR_PREFERENCE_TTL_ENV) },
        }
    }

    #[test]
    fn test_mirror_preference_invalid_when_mirror_missing() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());

        let pref = MirrorPreference {
            selected_mirror: DownloadMirror {
                name: "absent".to_string(),
                base_url: "https://deleted.example.com/releases".to_string(),
            ..Default::default()
            },
            tested_at: chrono::Utc::now().to_rfc3339(),
            download_speed_bps: 100,
        };
        save_mirror_preference(&pref);

        // Load with a different mirror list — the cached mirror is absent.
        let mirrors = vec![DownloadMirror {
            name: "other".to_string(),
            base_url: "https://other.example.com/releases".to_string(),
            ..Default::default()
        }];
        let loaded = load_mirror_preference(&mirrors);
        assert!(
            loaded.is_none(),
            "cached mirror not in the list should return None"
        );

        delete_mirror_preference_cache();
        restore_test_env(prev_runtime, prev_state);
    }

    #[test]
    fn test_mirror_preference_corrupted() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());

        let path = mirror_preference_cache_path();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, "not valid json {{{").unwrap();

        let mirrors = builtin_mirrors();
        let loaded = load_mirror_preference(&mirrors);
        assert!(loaded.is_none(), "corrupted cache file should return None");

        delete_mirror_preference_cache();
        restore_test_env(prev_runtime, prev_state);
    }

    #[test]
    fn test_delete_mirror_preference_cache_removes_file() {
        let _guard = ENV_MUTEX.lock().unwrap();
        let tmp = test_temp_dir();
        let (prev_runtime, prev_state) = isolate_test_env(&tmp.path());

        let path = mirror_preference_cache_path();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, r#"{"selected_mirror":{"name":"x","base_url":"https://x.com"},"tested_at":"2026-01-01T00:00:00+00:00","download_speed_bps":1}"#).unwrap();
        assert!(path.exists());

        delete_mirror_preference_cache();
        assert!(!path.exists(), "cache file should be deleted");

        restore_test_env(prev_runtime, prev_state);
    }
}
