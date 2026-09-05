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
use std::sync::atomic::{AtomicU16, Ordering};
use std::time::{Duration, Instant};

use reqwest::Client;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::managed_processes::{
    recorded_server_version, register_managed_server_process,
    shutdown_managed_server_processes_on_port, ManagedServerProcess,
};
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
/// Subdirectory of the CLI state dir that holds the trained JVM AOT cache.
///
/// The cache lives under the **state** dir (`~/.browser4`, preserved across
/// uninstall/reinstall) rather than the runtime data dir so a reinstall of the
/// same version never pays the one-time training cost again.  The
/// invalidation key (version tag + jar list + JVM flags) still guards against
/// stale caches, and the JVM silently falls back when the cache mismatches.
const AOT_CACHE_DIR_NAME: &str = "aot-cache";
/// The trained AOT cache artifact (JEP 483 class loading & linking + JEP 515
/// method profiles).
const AOT_CACHE_FILE_NAME: &str = "app.aot";
/// Sidecar that records the invalidation key for `app.aot`, so a stale cache
/// (version bump, jar change, JVM flag change) triggers a fresh training run.
const AOT_CACHE_KEY_FILE_NAME: &str = "app.aot.key";
/// Minimum JDK major version for the AOT cache (JEP 483 shipped in JDK 24;
/// the one-step `-XX:AOTCacheOutput` workflow and method profiles need JDK 25).
const AOT_CACHE_MIN_JDK: u32 = 24;
/// Marker file recording that a background training run is in flight.  Its
/// content is the invalidation key the training run was started with, which
/// also doubles as the completion record: when `app.aot` exists and the
/// marker carries the current key, the background run has finished and the
/// key sidecar can be promoted without another CLI process having been
/// around when the training JVM exited.
const AOT_TRAINING_MARKER_FILE_NAME: &str = "training.lock";
/// Log file for the background training run's stdout/stderr.
const AOT_TRAINING_LOG_FILE_NAME: &str = "training.log";
/// Fixed-name Java `@argfile` for the training run, kept inside the cache dir
/// so the JVM cannot race its deletion; any leftover is removed on the next
/// cache check.
const AOT_TRAINING_ARGFILE_FILE_NAME: &str = "training-args.txt";
/// PID of the background training JVM, written right after a successful
/// spawn.  Lets the next launch detect a **dead** training run (crash, kill,
/// OOM) immediately and retrain instead of waiting for the stale-marker
/// window — the failure-recovery fast path.
const AOT_TRAINING_PID_FILE_NAME: &str = "training.pid";
/// A training marker older than this is considered stale (the training run
/// crashed, was killed, or otherwise failed to finish) and is reclaimed by
/// the next launch.  This is the failure-recovery fallback window (the PID
/// check above covers the common crash case immediately); a failed training
/// run self-heals — the next launch simply trains again.  Concurrent
/// retraining of the same key is harmless (same input → same output, last
/// writer wins), so the window only needs to exceed a realistic training
/// run, not bound it tightly.
const AOT_TRAINING_MARKER_STALE_AFTER: Duration = Duration::from_secs(5 * 60);
/// JVM flag that switches the backend into AOT-training mode: the Spring
/// context skips the heavyweight eager beans (the MCP HTTP session — and with
/// it the browser launch) so the training run records class loading with the
/// minimal startup surface.  Must match `browser4.aot.training` in
/// `McpHttpServerConfiguration` (browser4-rest).
const AOT_TRAINING_JVM_FLAG: &str = "-Dbrowser4.aot.training=true";
/// Env var override for the browser binary path.  When set to an existing
/// executable file it is used regardless of other browser search heuristics.
/// The value is also forwarded to the server via `-Dchrome.path`.
const BROWSER4_BROWSER_PATH_ENV: &str = "BROWSER4_BROWSER_PATH";
/// Env var with space-separated JVM options injected into the server launch
/// command (e.g. `-Dapp.name=test -Xmx512m`).  Only consulted when the CLI
/// itself starts the backend; ignored for externally-managed servers.
const BROWSER4_SERVER_OPTS_ENV: &str = "BROWSER4_SERVER_OPTS";
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
/// When set to `1`, `true`, `yes`, or `on`, forces the CLI to rebuild the
/// Browser4 runtime bundle from source even if the build artifacts already
/// exist.  Useful in development when the source code has changed but cached
/// artifacts appear up-to-date.
const FORCE_REBUILD_BUNDLE_ENV: &str = "BROWSER4_CLI_FORCE_REBUILD_BUNDLE";
/// When set to `1`, `true`, `yes`, or `on`, disables the plugin warm restart:
/// the CLI will no longer restart a running local server when the contents of
/// its `plugins/` directory change since the server was started.  Plugins then
/// only take effect after a manual restart, as before.
const DISABLE_PLUGIN_WARM_RESTART_ENV: &str = "BROWSER4_CLI_DISABLE_PLUGIN_WARM_RESTART";
/// When set to `1`, `true`, `yes`, or `on`, disables the JVM AOT cache
/// (JEP 483/515) training step entirely and skips attaching any trained cache
/// when launching the server.  Useful in CI / test harnesses that must not
/// spawn background training JVMs or attach caches; the server then starts
/// without AOT acceleration.
const DISABLE_AOT_CACHE_ENV: &str = "BROWSER4_CLI_DISABLE_AOT_CACHE";
/// Name of the plugins fingerprint store inside the CLI state dir.  Records,
/// per port, the plugins directory the server was launched with and a
/// fingerprint of its JAR set at launch time.
const PLUGINS_FINGERPRINT_FILE_NAME: &str = "server-plugins-fingerprint.json";
/// Graceful-stop timeout for the plugin warm restart (ms).
const PLUGIN_WARM_RESTART_STOP_TIMEOUT_MS: u64 = 15_000;
/// Poll interval while waiting for the old server to exit before a warm
/// restart (ms).
const PLUGIN_WARM_RESTART_STOP_POLL_MS: u64 = 250;

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
// Release metadata — used to resolve the latest version tag from mirrors
// that publish a `latest-release.json` file (Aliyun OSS, custom mirrors).
// ---------------------------------------------------------------------------

/// Minimal representation of the `latest-release.json` file published by
/// release workflows.  Contains only public, non-sensitive fields.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct LatestReleaseInfo {
    tag: String,
    #[serde(default)]
    version: Option<String>,
    #[serde(default)]
    published_at: Option<String>,
    #[serde(default)]
    release_url: Option<String>,
    #[serde(default)]
    assets: Vec<ReleaseAssetInfo>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct ReleaseAssetInfo {
    name: String,
    #[serde(default)]
    size: Option<u64>,
}

// ---------------------------------------------------------------------------
// Download mirror configuration
// ---------------------------------------------------------------------------

/// A single download mirror entry.
///
/// Each mirror provides a `base_url` that hosts release assets.  Tagged
/// downloads always use `<base_url>/download/<tag>/<asset>`.  For the
/// latest release the URL is `<base_url>/<latest_path>/<asset>`, where
/// `latest_path` defaults to `latest/download` (GitHub Releases layout).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
struct DownloadMirror {
    /// Human-readable name shown in log messages (e.g. "github", "aliyun-oss").
    name: String,
    /// Base URL for release downloads (e.g. `https://github.com/platonai/Browser4/releases`).
    base_url: String,
    /// Whether this mirror supports resolving the latest release without an
    /// explicit tag.  When `false`, "latest" downloads must resolve the tag
    /// before constructing the download URL (e.g. via a release-metadata
    /// endpoint or user-supplied `--tag`).
    #[serde(default)]
    supports_latest_resolution: bool,
    /// Path segment inserted between `base_url` and `asset_name` for latest
    /// downloads.  Defaults to `latest/download` (GitHub Releases layout).
    /// Mirrors that use a different layout (e.g. Aliyun OSS uses
    /// `download/latest`) can override this.
    #[serde(default)]
    latest_path: Option<String>,
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
    let mut mirrors = vec![
        DownloadMirror {
            name: "github".to_string(),
            base_url: "https://github.com/platonai/Browser4/releases".to_string(),
            supports_latest_resolution: true,
            latest_path: None,
        },
        DownloadMirror {
            name: "aliyun-oss".to_string(),
            base_url: "https://browser4.oss-cn-beijing.aliyuncs.com/releases".to_string(),
            supports_latest_resolution: true,
            latest_path: Some("download/latest".to_string()),
        },
    ];
    if china_locale {
        mirrors.swap(0, 1);
    }
    mirrors
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

    // 3 — /etc/timezone on Debian/Ubuntu (only when TZ is not set:
    //     an explicit TZ takes precedence over the system default).
    #[cfg(unix)]
    {
        if std::env::var("TZ").is_err() {
            if let Ok(content) = std::fs::read_to_string("/etc/timezone") {
                if is_china_timezone(content.trim()) {
                    return true;
                }
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
                latest_path: None,
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
        None => {
            let latest = mirror.latest_path.as_deref().unwrap_or("latest/download");
            format!("{base}/{latest}/{asset_name}")
        }
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
async fn run_speed_tests(mirrors: &[DownloadMirror], tag: Option<&str>) -> Vec<SpeedTestResult> {
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

    let idx = mirrors
        .iter()
        .position(|m| {
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

/// The runtime version tag the CLI would launch for a fresh localhost server:
/// `"local"` when a repository checkout with a bundle module is detected (the
/// source-built bundle is preferred there), otherwise the installed runtime's
/// `current.tag` (e.g. `"v4.13.5"`).  Returns `None` when no version can be
/// determined (no checkout, no installed runtime) — callers then skip the
/// version check and reuse whatever server is running.
fn expected_runtime_tag() -> Option<String> {
    if !should_force_remote_bundle() {
        if let Some(root) = find_browser4_root() {
            if root.join("browser4-apps").join("browser4-bundle").is_dir() {
                return Some("local".to_string());
            }
        }
    }
    read_current_tag()
}

/// Canonicalize a runtime version string for comparison:
/// - `"v4.13.5"` → `"4.13.5"` (release tags)
/// - `"local"`, `"4.13.6-SNAPSHOT"`, `"4.13.7-rc.1"`, `""` → `"local"` (source-built bundle)
fn canonical_runtime_version(tag: &str) -> String {
    let trimmed = tag.trim().to_ascii_lowercase();
    let without_v = trimmed.strip_prefix('v').unwrap_or(&trimmed).to_string();
    if without_v.is_empty()
        || without_v == "local"
        || without_v.contains("-snapshot")
        || without_v.contains("-dev")
        || without_v.contains("-rc")
    {
        "local".to_string()
    } else {
        without_v
    }
}

/// `true` when the running server's version differs from the version this CLI
/// would launch, after canonicalization (release tags compare exactly; any
/// source-built marker — "local", "-SNAPSHOT" — compares equal to "local").
fn server_version_mismatch(actual: &str, expected: &str) -> bool {
    canonical_runtime_version(actual) != canonical_runtime_version(expected)
}

/// Probe the version reported by a running server via `GET /api/system/build`.
/// Returns `None` when the endpoint is missing or unparseable — older or
/// non-Browser4 servers — so the caller can fall back to reusing the server.
async fn probe_server_version(client: &Client, base_url: &str) -> Option<String> {
    let url = format!("{}/api/system/build", base_url.trim_end_matches('/'));
    let response = client.get(&url).send().await.ok()?;
    if !response.status().is_success() {
        return None;
    }
    let body: serde_json::Value = response.json().await.ok()?;
    body.get("version").and_then(|v| v.as_str()).map(String::from)
}

/// Version of the server currently running on [port]: the version recorded in
/// the managed process registry when the CLI launched it, falling back to an
/// HTTP probe for servers the CLI did not launch (or launched before the
/// version field existed).
async fn running_server_version(client: &Client, base_url: &str, port: u16) -> Option<String> {
    if let Some(recorded) = recorded_server_version(port, None) {
        return Some(recorded);
    }
    probe_server_version(client, base_url).await
}

/// Decide what to do with an existing, ready server: check for plugin
/// fingerprint changes, then (when enforcement is active) check that the
/// running backend matches the version this CLI would launch and restart it
/// on the same port when it does not.
async fn on_existing_server_ready(
    client: &Client,
    base_url: &str,
    port: u16,
    enforce_version: bool,
) -> Result<(), String> {
    if plugins_changed_since_server_start(port) {
        return restart_server_for_plugin_change(base_url, port).await;
    }

    if enforce_version {
        if let Some(expected) = expected_runtime_tag() {
            if let Some(actual) = running_server_version(client, base_url, port).await {
                if server_version_mismatch(&actual, &expected) {
                    return restart_server_for_version_change(base_url, port, &actual, &expected)
                        .await;
                }
            }
        }
    }

    Ok(())
}

/// Ensure a Browser4 server is running at [base_url], starting one when the
/// port is free.  When [enforce_version] is set (the URL was not explicitly
/// chosen by the user), a running server whose version differs from the
/// version this CLI would launch is restarted on the same port so the CLI
/// never talks to a stale backend by accident.
pub async fn ensure_server_running(base_url: &str, enforce_version: bool) -> Result<(), String> {
    // Skip remote servers
    if !base_url.contains("localhost") && !base_url.contains("127.0.0.1") {
        return Ok(());
    }

    let port = extract_port(base_url);
    if !is_local_port_open(base_url) {
        print_server_starting_message();
        let launch_spec = resolve_server_launch_spec(port).await?;
        return start_server(&launch_spec, base_url, port).await;
    }

    let client = Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        // Disable the proxy for the readiness probe (see the comment in
        // `start_server`): reqwest honours HTTP(S)_PROXY but not NO_PROXY, so
        // probing localhost would otherwise go through the proxy and stall.
        .no_proxy()
        .build()
        .map_err(|e| e.to_string())?;

    match probe_server_state(&client, base_url).await {
        ServerState::Ready => {
            return on_existing_server_ready(&client, base_url, port, enforce_version).await;
        }
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
                ServerState::Ready => {
                    return on_existing_server_ready(&client, base_url, port, enforce_version).await;
                }
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

    print_server_starting_message();

    let launch_spec = resolve_server_launch_spec(port).await?;

    start_server(&launch_spec, base_url, port).await
}

fn extract_port(base_url: &str) -> u16 {
    if let Ok(url) = reqwest::Url::parse(base_url) {
        url.port().unwrap_or(8182)
    } else {
        8182
    }
}

fn print_server_starting_message() {
    eprintln!("Starting Browser4 server (first launch ~10s for JVM + Spring Boot; subsequent starts faster)...");
}

// ---- Plugins warm restart ----
//
// Runtime plugins live in `<server working dir>/plugins/*.jar` and are wired
// into the Spring context during startup (PluginClasspathEnhancer +
// AutoConfiguration.imports), so installing or removing a plugin JAR at
// runtime only takes effect after a restart.  To make that transparent, the
// CLI fingerprints the plugins directory at launch time and, on later
// commands, restarts the local server when the fingerprint has changed.  With
// the trained AOT cache (JEP 483) a restart completes in seconds, so this
// "warm restart" is cheap enough to happen automatically.

/// A recorded plugins fingerprint for one server port.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct PluginsFingerprintEntry {
    pub port: u16,
    /// Absolute path of the plugins directory the server was launched with
    /// (relative to the server working directory).  Recorded so later CLI
    /// invocations recompute the fingerprint from exactly the same directory,
    /// regardless of the CLI's own cwd.
    #[serde(rename = "pluginsDir")]
    pub plugins_dir: String,
    /// Fingerprint of the plugin JAR set at server launch time.
    pub fingerprint: String,
    #[serde(rename = "recordedAt")]
    pub recorded_at: String,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct PluginsFingerprintStore {
    entries: Vec<PluginsFingerprintEntry>,
}

fn plugins_fingerprint_file() -> PathBuf {
    resolve_default_state_dir().join(PLUGINS_FINGERPRINT_FILE_NAME)
}

fn load_plugins_fingerprint_store(path: &Path) -> PluginsFingerprintStore {
    fs::read_to_string(path)
        .ok()
        .and_then(|raw| serde_json::from_str::<PluginsFingerprintStore>(&raw).ok())
        .unwrap_or_default()
}

fn save_plugins_fingerprint_store(store: &PluginsFingerprintStore, path: &Path) {
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    if let Ok(json) = serde_json::to_string_pretty(store) {
        let _ = fs::write(path, json);
    }
}

/// Record the plugins fingerprint for the server just launched on [port].
///
/// Called from `start_server` after the server reports ready.  The entry is
/// keyed by port; an existing entry for the same port is replaced.
fn record_plugins_fingerprint(port: u16, plugins_dir: &Path) {
    let path = plugins_fingerprint_file();
    let mut store = load_plugins_fingerprint_store(&path);
    store.entries.retain(|e| e.port != port);
    store.entries.push(PluginsFingerprintEntry {
        port,
        plugins_dir: plugins_dir.to_string_lossy().to_string(),
        fingerprint: compute_plugins_fingerprint(plugins_dir),
        recorded_at: chrono::Utc::now().to_rfc3339(),
    });
    save_plugins_fingerprint_store(&store, &path);
}

/// Remove the recorded plugins fingerprint for [port] (e.g. when the port is
/// now owned by a server this CLI did not launch).
fn clear_plugins_fingerprint(port: u16) {
    let path = plugins_fingerprint_file();
    let mut store = load_plugins_fingerprint_store(&path);
    let before = store.entries.len();
    store.entries.retain(|e| e.port != port);
    if store.entries.len() != before {
        save_plugins_fingerprint_store(&store, &path);
    }
}

/// Compute a stable fingerprint over the plugin JARs in [plugins_dir].
///
/// Each JAR contributes its file name, size, and modification time; the list
/// is order-independent (sorted by file name).  A missing directory and an
/// empty directory produce the same fingerprint — neither contributes any
/// JAR.  Metadata-based fingerprinting keeps this cheap enough to run before
/// every command; content changes normally alter size or mtime too.
fn compute_plugins_fingerprint(plugins_dir: &Path) -> String {
    let mut jar_keys: Vec<String> = Vec::new();

    if let Ok(entries) = fs::read_dir(plugins_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("jar") {
                continue;
            }
            if !path.is_file() {
                continue;
            }
            let name = entry.file_name().to_string_lossy().to_string();
            let (size, mtime) = match fs::metadata(&path) {
                Ok(meta) => (
                    meta.len(),
                    meta.modified()
                        .ok()
                        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                        .map(|d| d.as_nanos())
                        .unwrap_or(0),
                ),
                Err(_) => (0, 0),
            };
            jar_keys.push(format!("{name}|{size}|{mtime}"));
        }
    }

    jar_keys.sort();

    let mut digest = Sha256::new();
    digest.update(jar_keys.len().to_le_bytes());
    for key in &jar_keys {
        digest.update(key.as_bytes());
        digest.update([0u8]);
    }

    digest
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn plugin_warm_restart_disabled() -> bool {
    matches!(
        env::var(DISABLE_PLUGIN_WARM_RESTART_ENV)
            .unwrap_or_default()
            .to_lowercase()
            .as_str(),
        "1" | "true" | "yes" | "on"
    )
}

/// Whether JVM AOT cache training/attachment is explicitly disabled via
/// `BROWSER4_CLI_DISABLE_AOT_CACHE`.  Mirrors [`plugin_warm_restart_disabled`].
fn aot_cache_disabled() -> bool {
    matches!(
        env::var(DISABLE_AOT_CACHE_ENV)
            .unwrap_or_default()
            .to_lowercase()
            .as_str(),
        "1" | "true" | "yes" | "on"
    )
}

/// Whether the plugins directory of the server on [port] has changed since
/// that server was launched by this CLI.
///
/// Returns false when there is no recorded entry (e.g. the server was started
/// externally, or by an older CLI) — servers this CLI did not launch are
/// never restarted.
fn plugins_changed_since_server_start(port: u16) -> bool {
    if plugin_warm_restart_disabled() {
        return false;
    }

    let entry = match load_plugins_fingerprint_store(&plugins_fingerprint_file())
        .entries
        .into_iter()
        .find(|e| e.port == port)
    {
        Some(entry) => entry,
        None => return false,
    };

    let current = compute_plugins_fingerprint(Path::new(&entry.plugins_dir));
    current != entry.fingerprint
}

/// Restart the local server on [port] so newly installed/removed plugin JARs
/// take effect, then wait for the fresh server to become ready.
///
/// Only processes registered by this CLI are stopped (see
/// `shutdown_managed_server_processes_on_port`); if the port is still open
/// afterwards it is now owned by some other server, and the restart degrades
/// to a no-op rather than launching onto an occupied port.
async fn restart_server_for_plugin_change(base_url: &str, port: u16) -> Result<(), String> {
    restart_server_on_port(
        base_url,
        port,
        "Plugins changed since the server started - restarting Browser4 server to activate them (warm restart)...",
    )
    .await
}

/// Restart the local server on [port] because the running backend's version
/// no longer matches the version this CLI would launch (e.g. the installed
/// release is running while the CLI now prefers the source-built local
/// bundle).  Same stop-and-relaunch semantics as the plugin warm restart.
async fn restart_server_for_version_change(
    base_url: &str,
    port: u16,
    actual: &str,
    expected: &str,
) -> Result<(), String> {
    restart_server_on_port(
        base_url,
        port,
        &format!(
            "Server on port {} is running version {}, but this CLI expects {} — \
             restarting it to match (the current browser session will be stopped).",
            port, actual, expected
        ),
    )
    .await
}

/// Shared stop-and-relaunch used by the plugin warm restart and the
/// version-drift restart: stop the managed server on [port], verify the port
/// is free, then launch a fresh server on the same port.
async fn restart_server_on_port(base_url: &str, port: u16, reason: &str) -> Result<(), String> {
    eprintln!("{reason}");

    let shutdown = shutdown_managed_server_processes_on_port(
        false,
        None,
        port,
        PLUGIN_WARM_RESTART_STOP_TIMEOUT_MS,
        PLUGIN_WARM_RESTART_STOP_POLL_MS,
    );
    if !shutdown.remaining_pids.is_empty() {
        return Err(format!(
            "Browser4 server on port {port} could not be stopped for the restart \
             (pids still running: {:?}). Stop it manually and re-run the command.",
            shutdown.remaining_pids
        ));
    }

    // The managed processes are gone; if the port is still open another
    // server (started outside this CLI) now owns it.  Leave it alone.
    if is_local_port_open(base_url) {
        eprintln!(
            "Port {port} is still served by a server not started by this CLI; skipping the restart."
        );
        clear_plugins_fingerprint(port);
        return Ok(());
    }

    print_server_starting_message();
    let launch_spec = resolve_server_launch_spec(port).await?;
    start_server(&launch_spec, base_url, port).await
}

pub fn is_local_port_open(base_url: &str) -> bool {
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
    /// Runtime version tag of the launched backend ("local" for a source-built
    /// bundle, "v4.13.5" for an installed release).  Recorded in the managed
    /// process registry so later invocations can detect version drift between
    /// the running server and the version this CLI would launch.
    version: Option<String>,
}

struct PreparedLaunchCommand {
    command: Command,
    cleanup_dir: Option<PathBuf>,
    /// Path to the Java `@argfile` the command was switched to when the
    /// fully-enumerated classpath would exceed the Windows command-line
    /// limit.  Kept for startup-log diagnostics.
    argfile: Option<PathBuf>,
}

fn detect_current_runtime_bundle_platform() -> Result<RuntimeBundlePlatform, String> {
    match (env::consts::OS, env::consts::ARCH) {
        ("windows", "x86_64") => Ok(RuntimeBundlePlatform::WindowsX64),
        ("linux", "x86_64") => Ok(RuntimeBundlePlatform::LinuxX64),
        ("macos", "x86_64") => Ok(RuntimeBundlePlatform::MacOsX64),
        ("macos", "aarch64") => Ok(RuntimeBundlePlatform::MacOsArm64),
        (os, arch) => Err(format!(
            "browser4-cli install does not yet publish a bundled Browser4 runtime for {os}/{arch}. Please install Java 25+ and use the Browser4.jar release asset instead."
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
///
/// When `current.tag` is missing, empty, or points to a non-existent version
/// directory, this function attempts auto-repair by scanning for versioned
/// install directories and rewriting the marker.
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
        // current.tag exists but is empty — treat as missing.
        return find_newest_versioned_install();
    }
    // Verify the versioned directory actually exists and is complete.
    let dir = versioned_install_dir(&tag);
    if !install_dir_contains_runtime(&dir) {
        // current.tag points to a non-existent or corrupt directory.
        // Try to find another valid install and repair the marker.
        return find_newest_versioned_install();
    }
    Some(tag)
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
///
/// `read_current_tag()` already performs auto-repair when `current.tag` is
/// missing, empty, or points to a corrupt/non-existent directory.  The
/// secondary check here handles the race where the directory disappears
/// between the call to `read_current_tag()` and this function.
fn resolve_current_install_dir() -> Option<PathBuf> {
    let tag = read_current_tag()?;
    let dir = versioned_install_dir(&tag);
    if install_dir_contains_runtime(&dir) {
        Some(dir)
    } else {
        // Race or corruption: the tag was valid a moment ago but the directory
        // is now broken.  Try one more repair pass.
        let _ = fs::remove_file(current_tag_file_path());
        let repaired_tag = find_newest_versioned_install()?;
        let repaired_dir = versioned_install_dir(&repaired_tag);
        if install_dir_contains_runtime(&repaired_dir) {
            Some(repaired_dir)
        } else {
            None
        }
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

/// Platform java executable name (`java.exe` on Windows, `java` elsewhere).
/// Thin wrapper over the shared [`crate::java::executable_name`].
fn browser4_java_executable_name() -> &'static str {
    crate::java::executable_name()
}

fn browser4_install_metadata_path() -> PathBuf {
    browser4_install_dir().join(BROWSER4_INSTALL_METADATA_FILE_NAME)
}

/// Path of the JRE inside a runtime install directory.  Thin wrapper over
/// the shared [`crate::java::java_in_install_dir`].
fn java_path_in_install_dir(install_dir: &Path) -> PathBuf {
    crate::java::java_in_install_dir(install_dir)
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

/// Check whether `BROWSER4_CLI_FORCE_REBUILD_BUNDLE` is set.
///
/// When this flag is active the CLI forces a local Maven/jlink rebuild of
/// the Browser4 runtime bundle, even if the build artifacts already exist.
/// This is useful when the source changed but the timestamps/cache make
/// the existing artifacts appear up-to-date.
fn should_force_rebuild_bundle() -> bool {
    match env::var(FORCE_REBUILD_BUNDLE_ENV).ok().as_deref() {
        Some("1" | "true" | "TRUE" | "yes" | "YES" | "on" | "ON") => true,
        _ => false,
    }
}

/// Return `true` when `s` looks like a version tag: starts with `v` followed
/// by a dotted numeric version (e.g. `v4.11.2`, `v4.11.9-rc.1`).
fn is_valid_version_tag(s: &str) -> bool {
    let s = s.trim();
    let rest = match s.strip_prefix('v') {
        Some(r) => r,
        None => return false,
    };
    if rest.is_empty() || rest.starts_with('.') || rest.ends_with('.') {
        return false;
    }
    let mut has_digit = false;
    let mut in_prerelease = false;
    for ch in rest.chars() {
        if ch.is_ascii_digit() {
            has_digit = true;
        } else if ch == '.' {
            // dots are fine (both in semver and pre-release)
        } else if ch == '-' {
            in_prerelease = true;
        } else if in_prerelease && (ch.is_ascii_lowercase() || ch == '_') {
            // pre-release labels like -rc.1, -alpha.2, -beta.3, -dry_run.1
        } else {
            return false;
        }
    }
    has_digit
}

fn parse_release_tag_from_url(url: &str) -> Option<String> {
    let parsed = reqwest::Url::parse(url).ok()?;
    let segments = parsed.path_segments()?.collect::<Vec<_>>();
    let download_index = segments.iter().position(|segment| *segment == "download")?;
    let candidate = segments.get(download_index + 1)?.to_string();
    if is_valid_version_tag(&candidate) {
        Some(candidate)
    } else {
        None
    }
}

/// Attempt to extract the GitHub owner and repo from a mirror's `base_url`.
///
/// Returns `Some((owner, repo))` when the mirror is a GitHub Releases URL
/// (e.g. `https://github.com/platonai/Browser4/releases`), or `None` for
/// non-GitHub mirrors.
fn parse_github_owner_repo(mirror: &DownloadMirror) -> Option<(String, String)> {
    let parsed = reqwest::Url::parse(&mirror.base_url).ok()?;
    let host = parsed.host_str()?;
    if host != "github.com" {
        return None;
    }
    let segments: Vec<&str> = parsed.path_segments()?.collect();
    // Expected: ["{owner}", "{repo}", "releases"]
    if segments.len() >= 2 {
        Some((segments[0].to_string(), segments[1].to_string()))
    } else {
        None
    }
}

/// Query the GitHub REST API for the latest release tag.
///
/// Calls `GET /repos/{owner}/{repo}/releases/latest` and extracts `tag_name`
/// from the JSON response.  Returns `None` on any error (non-fatal — callers
/// fall through to the next mirror).
async fn resolve_latest_tag_from_github_api(owner: &str, repo: &str) -> Option<String> {
    let url = format!("https://api.github.com/repos/{owner}/{repo}/releases/latest");
    let client = reqwest::Client::builder()
        .user_agent("browser4-cli")
        .build()
        .ok()?;
    let response = client
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .send()
        .await
        .ok()?;
    if !response.status().is_success() {
        return None;
    }
    let body = response.text().await.ok()?;
    let json: serde_json::Value = serde_json::from_str(&body).ok()?;
    json.get("tag_name")?.as_str().map(String::from)
}

/// Fetch `latest-release.json` from a mirror and return the parsed metadata.
///
/// The metadata file is published at `{base_url}/latest-release.json` and
/// contains the latest release tag and other public, non-sensitive fields.
async fn resolve_latest_tag_from_oss_metadata(
    mirror: &DownloadMirror,
) -> Option<LatestReleaseInfo> {
    let base = mirror.base_url.trim_end_matches('/');
    let metadata_url = format!("{base}/latest-release.json");
    let response = reqwest::get(&metadata_url).await.ok()?;
    if !response.status().is_success() {
        return None;
    }
    response.json::<LatestReleaseInfo>().await.ok()
}

/// Resolve the latest release tag from the configured mirrors.
///
/// Tries each mirror that has `supports_latest_resolution` set:
/// - GitHub mirrors: uses the REST API (`/repos/{owner}/{repo}/releases/latest`)
/// - Other mirrors: fetches `{base_url}/latest-release.json`
///
/// Returns the first successfully resolved `(tag, full_metadata)` pair, or
/// `None` when all mirrors fail to report their latest version.
async fn resolve_latest_tag(mirrors: &[DownloadMirror]) -> Option<(String, LatestReleaseInfo)> {
    for mirror in mirrors {
        if !mirror.supports_latest_resolution {
            continue;
        }
        // GitHub mirrors — use the REST API (fast, no download overhead).
        if let Some((owner, repo)) = parse_github_owner_repo(mirror) {
            if let Some(tag) = resolve_latest_tag_from_github_api(&owner, &repo).await {
                let info = LatestReleaseInfo {
                    tag: tag.clone(),
                    version: Some(tag.trim_start_matches('v').to_string()),
                    published_at: None,
                    release_url: Some(format!(
                        "https://github.com/{owner}/{repo}/releases/tag/{tag}"
                    )),
                    assets: Vec::new(),
                };
                return Some((tag, info));
            }
            continue;
        }
        // Non-GitHub mirrors — fetch `latest-release.json`.
        if let Some(info) = resolve_latest_tag_from_oss_metadata(mirror).await {
            let tag = info.tag.clone();
            return Some((tag, info));
        }
    }
    None
}

/// Whether `tag` is a release-candidate tag such as `v4.14.0-rc.1`.
///
/// Only tags whose pre-release label starts with `rc` qualify — CI tags
/// (`v4.14.0-ci.3`) and other pre-release labels (`alpha`, `beta`, `dry_run`)
/// are deliberately excluded so the upgrade hint only points at real release
/// candidates.
fn is_rc_tag(tag: &str) -> bool {
    let normalized = tag.trim_start_matches('v');
    normalized
        .split_once('-')
        .map_or(false, |(_, label)| {
            label.to_ascii_lowercase().starts_with("rc")
        })
}

/// Compare two tags by their numeric core — the part before any `-`
/// pre-release suffix.
///
/// This makes `v4.14.0-rc.1` compare equal to `v4.14.0` (a release candidate
/// of the *current* stable version is not newer than it) and still greater
/// than `v4.13.11`.  Plain [`compare_semver_tags`] would rank
/// `v4.14.0-rc.1` above `v4.14.0` because the `rc.1` suffix leaks into the
/// numeric parts, which produces a misleading "newer RC" hint.
fn compare_base_versions(a: &str, b: &str) -> std::cmp::Ordering {
    let base_a = a.split('-').next().unwrap_or(a);
    let base_b = b.split('-').next().unwrap_or(b);
    compare_semver_tags(base_a, base_b)
}

/// Pick the newest tag in `tags` that is strictly newer than `stable_tag`.
///
/// "Newer" is judged on the numeric core ([`compare_base_versions`]) so a
/// release candidate of the same version as the stable release never
/// qualifies.  Ties between candidates of the same core version are broken
/// by full tag comparison (so `v4.14.0-rc.2` wins over `v4.14.0-rc.1`).
/// Returns `None` when no tag qualifies.
fn pick_newest_tag_newer_than(tags: &[String], stable_tag: &str) -> Option<String> {
    tags.iter()
        .filter(|tag| compare_base_versions(tag, stable_tag) == std::cmp::Ordering::Greater)
        .max_by(|a, b| compare_semver_tags(a, b))
        .cloned()
}

/// Query the GitHub REST API for the newest release-candidate tag that is
/// newer than `stable_tag`.
///
/// `GET /repos/{owner}/{repo}/releases?per_page=100` returns up to 100
/// releases including prereleases (unlike `/releases/latest`, which only
/// ever returns the newest stable release).  Only pre-release tags with an
/// `rc` label are considered.  Best-effort — returns `None` on any error so
/// the install/upgrade flow never fails over this.
async fn resolve_newer_rc_tag_from_github_api(
    owner: &str,
    repo: &str,
    stable_tag: &str,
) -> Option<String> {
    let url = format!("https://api.github.com/repos/{owner}/{repo}/releases?per_page=100");
    let client = reqwest::Client::builder()
        .user_agent("browser4-cli")
        .build()
        .ok()?;
    let response = client
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .send()
        .await
        .ok()?;
    if !response.status().is_success() {
        return None;
    }
    let body = response.text().await.ok()?;
    let releases: serde_json::Value = serde_json::from_str(&body).ok()?;
    let rc_tags: Vec<String> = releases
        .as_array()?
        .iter()
        .filter(|release| {
            release
                .get("prerelease")
                .and_then(|p| p.as_bool())
                .unwrap_or(false)
        })
        .filter_map(|release| release.get("tag_name").and_then(|t| t.as_str()))
        .filter(|tag| is_valid_version_tag(tag) && is_rc_tag(tag))
        .map(String::from)
        .collect();
    pick_newest_tag_newer_than(&rc_tags, stable_tag)
}

/// Fetch `latest-rc.json` from a mirror and return the release-candidate tag.
///
/// Mirrors that publish release metadata (e.g. Aliyun OSS) host this file
/// next to `latest-release.json`; it is only published when a pre-release
/// (RC) release exists.
async fn resolve_latest_rc_tag_from_oss_metadata(mirror: &DownloadMirror) -> Option<String> {
    let base = mirror.base_url.trim_end_matches('/');
    let metadata_url = format!("{base}/latest-rc.json");
    let response = reqwest::get(&metadata_url).await.ok()?;
    if !response.status().is_success() {
        return None;
    }
    let info: LatestReleaseInfo = response.json().await.ok()?;
    Some(info.tag)
}

/// Resolve the newest release-candidate tag that is newer than `stable_tag`.
///
/// Best-effort and non-fatal — returns `None` when no mirror can report a
/// newer RC (e.g. mirrors that only publish stable metadata).  Tries each
/// mirror in order:
/// - GitHub mirrors: the REST releases list (includes prereleases).
/// - Other mirrors: `{base_url}/latest-rc.json`.
async fn resolve_newer_rc_tag(mirrors: &[DownloadMirror], stable_tag: &str) -> Option<String> {
    for mirror in mirrors {
        if !mirror.supports_latest_resolution {
            continue;
        }
        if let Some((owner, repo)) = parse_github_owner_repo(mirror) {
            if let Some(tag) =
                resolve_newer_rc_tag_from_github_api(&owner, &repo, stable_tag).await
            {
                return Some(tag);
            }
            continue;
        }
        if let Some(tag) = resolve_latest_rc_tag_from_oss_metadata(mirror).await {
            if compare_base_versions(&tag, stable_tag) == std::cmp::Ordering::Greater {
                return Some(tag);
            }
        }
    }
    None
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
/// 1. `BROWSER4_CLI_PROXY` env var — set by `--proxy <url>` on the CLI,
///    giving the user explicit control over the download proxy.
/// 2. Standard env vars (`https_proxy`, `HTTPS_PROXY`, `http_proxy`, …) —
///    the portable path, used on Unix, in CI, and in Docker.
/// 3. On Windows: the system-wide proxy configured via Internet Options,
///    which is stored in the registry and surfaced by `netsh winhttp`.
///
/// Returns `None` when no proxy is configured, so the download uses a
/// direct connection.
pub(crate) fn resolve_download_proxy() -> Option<reqwest::Proxy> {
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
        reqwest::blocking::Client::builder().timeout(Duration::from_secs(download_timeout_secs));

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

            // Copy with frequent progress reporting so long-running downloads
            // don't appear hung.
            let total_size = response.content_length();
            let mut downloaded: u64 = 0;
            let mut last_report = std::time::Instant::now();
            let report_interval = std::time::Duration::from_secs(5);
            let download_start = std::time::Instant::now();
            let mut buf = [0u8; 8192];

            // Print the total size up front so the user knows what to expect.
            if let Some(total) = total_size {
                eprintln!("  Downloading {:.1} MB...", total as f64 / 1_048_576.0);
            } else {
                eprintln!("  Downloading (size unknown)...");
            }

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
                        let elapsed = download_start.elapsed().as_secs_f64();
                        let speed_bps = if elapsed > 0.0 {
                            downloaded as f64 / elapsed
                        } else {
                            0.0
                        };
                        eprintln!(
                            "  Downloaded {:.1} / {:.1} MB ({:.0}%) — {:.1} MB/s",
                            downloaded as f64 / 1_048_576.0,
                            total as f64 / 1_048_576.0,
                            pct,
                            speed_bps / 1_048_576.0,
                        );
                    } else {
                        eprintln!("  Downloaded {:.1} MB", downloaded as f64 / 1_048_576.0);
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

    // Runtime bundle is typically ~130 MB.
    eprintln!(
        "  Downloading via PowerShell (~{:.0} MB, no progress bar — please wait)...",
        130.0,
    );

    let ps_script = format!(
        // Use Continue (not SilentlyContinue) so PowerShell shows progress
        // when the calling terminal supports it.
        "$ProgressPreference = 'Continue'; \
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
            Err(e) => Err(format!("Failed to run 'df' for disk space check: {e}")),
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
    release_info: Option<&LatestReleaseInfo>,
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

    // Save a copy of the release metadata alongside the installation metadata
    // so the versioned install directory is self-describing.
    if let Some(info) = release_info {
        let release_meta_path = staging_dir.join("latest-release.json");
        if let Ok(contents) = serde_json::to_string_pretty(info) {
            let _ = fs::write(&release_meta_path, contents);
        }
    }

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
        // Also save release metadata in the fallback path.
        if let Some(info) = release_info {
            let release_meta_path =
                versioned_install_dir(&metadata.tag).join("latest-release.json");
            if let Ok(contents) = serde_json::to_string_pretty(info) {
                let _ = fs::write(&release_meta_path, contents);
            }
        }
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

/// Check whether a process with the given PID is still running.
fn is_pid_alive(pid: u32) -> bool {
    #[cfg(unix)]
    {
        // Signal 0 performs error checking but sends no signal.
        // Returns 0 if the process exists, -1 with ESRCH if it doesn't.
        extern "C" {
            fn kill(pid: i32, sig: i32) -> i32;
        }
        unsafe { kill(pid as i32, 0) == 0 }
    }
    #[cfg(windows)]
    {
        const PROCESS_QUERY_LIMITED_INFORMATION: u32 = 0x1000;
        extern "system" {
            fn OpenProcess(dwDesiredAccess: u32, bInheritHandle: i32, dwProcessId: u32) -> isize;
            fn CloseHandle(hObject: isize) -> i32;
        }
        unsafe {
            let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if handle == 0 {
                return false;
            }
            CloseHandle(handle);
            true
        }
    }
}

/// Read the PID recorded in the lock directory, if any.
fn read_lock_pid(lock_dir: &Path) -> Option<u32> {
    let pid_file = lock_dir.join("pid");
    match fs::read_to_string(&pid_file) {
        Ok(contents) => contents.trim().parse::<u32>().ok(),
        Err(_) => None,
    }
}

impl RuntimeInstallLock {
    /// Attempt to acquire the install lock, blocking up to `timeout` seconds.
    fn acquire(timeout: Duration) -> Result<Self, String> {
        let lock_dir = runtime_versions_dir().join(INSTALL_LOCK_DIR_NAME);
        // Ensure the parent directory exists before we attempt the atomic
        // mkdir — create_dir only creates the leaf and fails if intermediate
        // directories are missing (e.g. on a fresh install where the runtime
        // versions directory hasn't been created yet).
        fs::create_dir_all(runtime_versions_dir())
            .map_err(|e| format!("Failed to create runtime versions directory: {e}"))?;
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
                    // Check whether the lock holder is still alive.
                    let lock_stale = read_lock_pid(&lock_dir)
                        .map(|pid| !is_pid_alive(pid))
                        .unwrap_or(false);
                    if lock_stale {
                        // The process that created this lock is gone — clean it up.
                        let _ = fs::remove_dir_all(&lock_dir);
                        // Retry the acquisition immediately (the next loop iteration
                        // will attempt create_dir again).
                        continue;
                    }
                    if Instant::now() >= deadline {
                        // The lock holder is still alive after our full timeout.
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
                        deadline
                            .saturating_duration_since(Instant::now())
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
    let mut requested_tag = normalize_release_tag(tag);
    let mut release_info: Option<LatestReleaseInfo> = None;

    // When the caller asked for "latest" (no explicit --tag), try to resolve
    // the concrete version tag from the mirror's metadata API.  This avoids
    // relying on URL redirect parsing (which breaks with CDN-backed mirrors
    // like GitHub) and enables the download cache and early-exit fast-path.
    if requested_tag.is_none() {
        let mirrors = load_mirrors();
        if let Some((resolved, info)) = resolve_latest_tag(&mirrors).await {
            eprintln!(
                "Resolved latest release: {} (from mirror metadata)",
                resolved
            );
            // Best-effort: surface a newer release candidate (e.g. v4.14.0-rc.1)
            // so users on the stable track can opt into the RC explicitly.
            // Skipped when the currently installed tag already IS that RC
            // (otherwise the hint would be confusing on the RC track).
            if let Some(rc_tag) = resolve_newer_rc_tag(&mirrors, &resolved).await {
                let already_on_rc = read_current_tag().as_deref() == Some(rc_tag.as_str());
                if !already_on_rc {
                    eprintln!(
                        "💡 A newer release candidate is available: {rc_tag}\n   \
                         To try it: browser4-cli upgrade --tag {rc_tag}"
                    );
                }
            }
            requested_tag = Some(resolved);
            release_info = Some(info);
        } else {
            eprintln!(
                "Note: could not resolve the latest release tag from mirror metadata. \
                 Falling back to /latest/download/ URL redirect."
            );
        }
    }

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
    let _install_lock =
        RuntimeInstallLock::acquire(Duration::from_secs(INSTALL_LOCK_TIMEOUT_SECS))?;

    // Check available disk space before downloading.  We need at least
    // ~2 GB free (200 MB download + 200 MB extraction + headroom).
    // This is a best-effort check — the actual free space may change
    // between the check and the download.
    {
        let check_path = runtime_versions_dir();
        if let Ok(available) = fs_disk_space_available(&check_path) {
            const MIN_FREE_BYTES: u64 = 2 * 1024 * 1024 * 1024; // 2 GB
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
                bytes_written: fs::metadata(&archive_path).map(|m| m.len()).unwrap_or(0),
            };
            extract_runtime_bundle_archive(
                &archive_path,
                &extraction_dir,
                platform.archive_kind(),
            )?;
            let extracted_root = resolve_runtime_bundle_root(&extraction_dir)?;
            let resolved_tag = requested_tag
                .as_deref()
                .map(String::from)
                .unwrap_or_else(|| {
                    let fallback =
                        format!("unknown-{}", chrono::Utc::now().format("%Y%m%dT%H%M%S"));
                    eprintln!(
                        "⚠  Could not determine the release version tag (cache-hit path). \
                         Using fallback identifier: {fallback}"
                    );
                    fallback
                });
            let metadata = InstalledBrowser4RuntimeMetadata {
                tag: resolved_tag,
                asset_name: asset_name.clone(),
                download_url: downloaded.final_url,
                installed_at: chrono::Utc::now().to_rfc3339(),
            };
            commit_installed_browser4_runtime(&extracted_root, metadata, None)
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
                        eprintln!("Download from '{}' failed: {e}", mirror.name);
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
                        let cached_checksum_path = cached_checksum_path(normalized, &asset_name);
                        if cached_checksum_path.exists() {
                            if let Ok(expected) = fs::read_to_string(&cached_checksum_path) {
                                let expected = expected.trim();
                                if !expected.is_empty() && fresh_checksum != expected {
                                    eprintln!(
                                        "⚠  Checksum mismatch with previously cached archive! \
                                         The new download may be corrupt. \
                                         Evicting stale cache entry."
                                    );
                                    let _ = fs::remove_file(&cached_download_path(
                                        normalized,
                                        &asset_name,
                                    ));
                                    let _ = fs::remove_file(&cached_checksum_path);
                                }
                            }
                        }
                    }
                    Err(e) => {
                        eprintln!("⚠  Could not compute checksum of downloaded archive: {e}");
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
                fs::metadata(&archive_path).map(|m| m.len()).unwrap_or(0)
            );
            extract_runtime_bundle_archive(
                &archive_path,
                &extraction_dir,
                platform.archive_kind(),
            )?;
            eprintln!("Extraction complete.");

            let extracted_root = resolve_runtime_bundle_root(&extraction_dir)?;
            let resolved_tag = parse_release_tag_from_url(&downloaded.final_url)
                .or(requested_tag.clone())
                .unwrap_or_else(|| {
                    let fallback =
                        format!("unknown-{}", chrono::Utc::now().format("%Y%m%dT%H%M%S"));
                    eprintln!(
                        "⚠  Could not determine the release version tag from the download URL. \
                         Using fallback identifier: {fallback}"
                    );
                    fallback
                });
            let metadata = InstalledBrowser4RuntimeMetadata {
                tag: resolved_tag,
                asset_name: asset_name.clone(),
                download_url: downloaded.final_url,
                installed_at: chrono::Utc::now().to_rfc3339(),
            };
            commit_installed_browser4_runtime(&extracted_root, metadata, release_info.as_ref())
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
// ---------------------------------------------------------------------------
// Playwright browser discovery
// ---------------------------------------------------------------------------
//
// Playwright installs browsers under a platform-specific cache directory:
//   Windows: %USERPROFILE%\AppData\Local\ms-playwright\
//   macOS:   ~/Library/Caches/ms-playwright/
//   Linux:   ~/.cache/ms-playwright/
//
// Each browser lives in a versioned subdirectory:
//   chromium-1114/chrome-win/chrome.exe       (Windows)
//   chromium-1114/chrome-mac/Chromium.app/…   (macOS)
//   chromium-1114/chrome-linux/chrome         (Linux)
//
// The `PLAYWRIGHT_BROWSERS_PATH` env var overrides the default root.
// ---------------------------------------------------------------------------

/// Return the list of root directories where Playwright may have installed
/// browsers.  The `PLAYWRIGHT_BROWSERS_PATH` env var takes precedence;
/// otherwise the platform default is returned.
fn playwright_install_roots() -> Vec<PathBuf> {
    // 1. Explicit env var override — can contain multiple paths separated by
    //    the platform path separator (":" on Unix, ";" on Windows).
    if let Ok(raw) = env::var("PLAYWRIGHT_BROWSERS_PATH") {
        let trimmed = raw.trim().to_string();
        if !trimmed.is_empty() {
            return env::split_paths(&trimmed).collect();
        }
    }

    // 2. Platform default.
    let home = match dirs::home_dir() {
        Some(h) => h,
        None => return vec![],
    };

    if cfg!(target_os = "windows") {
        vec![home.join("AppData").join("Local").join("ms-playwright")]
    } else if cfg!(target_os = "macos") {
        vec![home.join("Library").join("Caches").join("ms-playwright")]
    } else {
        // Linux: both XDG and legacy locations.
        vec![
            home.join(".cache").join("ms-playwright"),
            home.join("ms-playwright"),
        ]
    }
}

/// Platform-specific relative path from a Playwright browser version directory
/// to the Chromium / Chrome binary.
fn playwright_chromium_relative_exe_path() -> &'static [&'static str] {
    if cfg!(target_os = "windows") {
        // Playwright on 64-bit Windows uses "chrome-win64"; the 32-bit
        // "chrome-win" is also possible.  Prefer the full browser, then
        // the headless shell variant.
        &[
            "chrome-win64\\chrome.exe",
            "chrome-win\\chrome.exe",
            "chrome-headless-shell-win64\\chrome-headless-shell.exe",
            "chrome-headless-shell-win\\chrome-headless-shell.exe",
        ]
    } else if cfg!(target_os = "macos") {
        &["chrome-mac/Chromium.app/Contents/MacOS/Chromium"]
    } else {
        // Linux
        &[
            "chrome-linux/chrome",
            "chrome-headless-shell-linux/chrome-headless-shell",
        ]
    }
}

/// Platform-specific relative path from a Playwright browser version directory
/// to the Microsoft Edge binary.
fn playwright_edge_relative_exe_path() -> &'static [&'static str] {
    if cfg!(target_os = "windows") {
        &["chrome-win64\\msedge.exe", "chrome-win\\msedge.exe"]
    } else if cfg!(target_os = "macos") {
        &["chrome-mac/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"]
    } else {
        &["chrome-linux/msedge"]
    }
}

/// Search Playwright install directories for a browser binary matching
/// `browser_prefixes` (e.g. `["chromium", "chrome"]`) and the given
/// platform-specific relative exe paths.
fn find_browser_in_playwright(
    browser_prefixes: &[&str],
    relative_exe_paths: &[&str],
) -> Option<PathBuf> {
    for root in playwright_install_roots() {
        let entries = match fs::read_dir(&root) {
            Ok(e) => e,
            Err(_) => continue,
        };

        let mut version_dirs: Vec<PathBuf> = entries
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().map(|t| t.is_dir()).unwrap_or(false))
            .map(|e| e.path())
            .collect();

        // Sort descending so newer versions (higher build numbers) are tried
        // first.  Build numbers are monotonic integers.
        version_dirs.sort_by(|a, b| {
            let a_name = a.file_name().and_then(|n| n.to_str()).unwrap_or("");
            let b_name = b.file_name().and_then(|n| n.to_str()).unwrap_or("");
            // Reverse: higher build number → tried first.
            b_name.cmp(a_name)
        });

        for version_dir in &version_dirs {
            let dir_name = version_dir
                .file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("");

            // Check whether this directory matches one of the requested browser
            // prefixes (e.g. "chromium-1114" matches prefix "chromium").
            let matches_prefix = browser_prefixes
                .iter()
                .any(|prefix| dir_name == *prefix || dir_name.starts_with(&format!("{prefix}-")));
            if !matches_prefix {
                continue;
            }

            for rel in relative_exe_paths {
                let candidate = version_dir.join(rel);
                if candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
    }

    None
}

/// Locate a Chromium / Chrome executable installed by Playwright.
fn find_chrome_in_playwright() -> Option<PathBuf> {
    find_browser_in_playwright(
        &["chromium", "chrome"],
        playwright_chromium_relative_exe_path(),
    )
}

/// Locate a Microsoft Edge executable installed by Playwright.
fn find_edge_in_playwright() -> Option<PathBuf> {
    find_browser_in_playwright(&["msedge", "edge"], playwright_edge_relative_exe_path())
}

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
            // On Unix, verify the file is actually executable — not just a
            // broken symlink, data file, or script without +x.  The Java side
            // does its own isExecutable() check, but filtering here avoids
            // passing a useless path via -Dchrome.path that would cause the
            // JVM to throw a RuntimeException before even trying to launch.
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                if let Ok(meta) = std::fs::metadata(path) {
                    if meta.permissions().mode() & 0o111 != 0 {
                        return Some(std::path::PathBuf::from(path));
                    }
                }
                // Not executable — keep searching.
                continue;
            }
            #[cfg(not(unix))]
            {
                return Some(std::path::PathBuf::from(path));
            }
        }
    }
    // Playwright-installed Chromium / Chrome (cross-platform)
    if let Some(path) = find_chrome_in_playwright() {
        return Some(path);
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

/// Try to locate an installed Microsoft Edge executable.
/// Searched after Chrome/Chromium — Edge is a fallback.
pub fn find_edge_executable() -> Option<PathBuf> {
    let candidates: &[&str] = if cfg!(target_os = "windows") {
        &[
            r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
            r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        ]
    } else if cfg!(target_os = "macos") {
        &["/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"]
    } else {
        // Linux
        &[
            "/opt/microsoft/msedge/msedge",
            "/usr/bin/microsoft-edge",
            "/usr/bin/microsoft-edge-stable",
        ]
    };

    for path in candidates {
        if Path::new(path).exists() {
            return Some(PathBuf::from(path));
        }
    }

    // Playwright-installed Microsoft Edge (cross-platform)
    if let Some(path) = find_edge_in_playwright() {
        return Some(path);
    }

    // Linux / fallback: check PATH
    for name in &["microsoft-edge", "microsoft-edge-stable", "msedge"] {
        if let Some(path) = find_in_path(name) {
            return Some(path);
        }
    }

    None
}

/// Locate a usable browser binary by checking, in order:
///   1. The `BROWSER4_BROWSER_PATH` env var
///   2. `find_chrome_executable()` (Chrome / Chromium)
///   3. `find_edge_executable()` (Microsoft Edge fallback)
///
/// Returns `None` when every strategy fails.
pub fn find_browser_executable() -> Option<PathBuf> {
    // 1. Env var override (highest priority)
    if let Ok(path) = env::var(BROWSER4_BROWSER_PATH_ENV) {
        let trimmed = path.trim().to_string();
        if !trimmed.is_empty() {
            let p = PathBuf::from(&trimmed);
            if p.exists() {
                eprintln!(
                    "Using browser from {}: {}",
                    BROWSER4_BROWSER_PATH_ENV, trimmed
                );
                return Some(p);
            }
            eprintln!(
                "Warning: {} is set to {:?} but that path does not exist; falling back.",
                BROWSER4_BROWSER_PATH_ENV, trimmed
            );
        }
    }

    // 2. Chrome / Chromium (existing logic)
    if let Some(chrome) = find_chrome_executable() {
        return Some(chrome);
    }

    // 3. Microsoft Edge (fallback)
    find_edge_executable()
}

// ---------------------------------------------------------------------------
// Browser channel resolution for `attach --cdp <channel>`
// ---------------------------------------------------------------------------

/// Known browser channels for the `attach --cdp <channel>` command.
#[derive(Debug, Clone, PartialEq)]
pub enum BrowserChannel {
    Chrome,
    ChromeBeta,
    ChromeDev,
    ChromeCanary,
    MsEdge,
    MsEdgeBeta,
    MsEdgeDev,
    MsEdgeCanary,
}

impl BrowserChannel {
    /// Parse a channel name string (case-insensitive).
    pub fn from_str(s: &str) -> Option<BrowserChannel> {
        match s.to_ascii_lowercase().as_str() {
            "chrome" | "google-chrome" | "google chrome" => Some(BrowserChannel::Chrome),
            "chrome-beta" | "google-chrome-beta" => Some(BrowserChannel::ChromeBeta),
            "chrome-dev" | "google-chrome-dev" => Some(BrowserChannel::ChromeDev),
            "chrome-canary" | "google-chrome-canary" => Some(BrowserChannel::ChromeCanary),
            "msedge" | "edge" | "microsoft-edge" | "microsoft edge" => Some(BrowserChannel::MsEdge),
            "msedge-beta" | "edge-beta" | "microsoft-edge-beta" => Some(BrowserChannel::MsEdgeBeta),
            "msedge-dev" | "edge-dev" | "microsoft-edge-dev" => Some(BrowserChannel::MsEdgeDev),
            "msedge-canary" | "edge-canary" | "microsoft-edge-canary" => {
                Some(BrowserChannel::MsEdgeCanary)
            }
            _ => None,
        }
    }

    /// Default remote debugging port for this channel.
    pub fn default_debug_port(&self) -> u16 {
        match self {
            BrowserChannel::Chrome => 9222,
            BrowserChannel::ChromeBeta => 9223,
            BrowserChannel::ChromeDev => 9224,
            BrowserChannel::ChromeCanary => 9225,
            BrowserChannel::MsEdge => 9222,
            BrowserChannel::MsEdgeBeta => 9223,
            BrowserChannel::MsEdgeDev => 9224,
            BrowserChannel::MsEdgeCanary => 9225,
        }
    }

    /// Executable name used to filter running processes (without extension).
    pub fn executable_name(&self) -> &'static str {
        match self {
            BrowserChannel::Chrome
            | BrowserChannel::ChromeBeta
            | BrowserChannel::ChromeDev
            | BrowserChannel::ChromeCanary => "chrome",
            BrowserChannel::MsEdge
            | BrowserChannel::MsEdgeBeta
            | BrowserChannel::MsEdgeDev
            | BrowserChannel::MsEdgeCanary => "msedge",
        }
    }
}

/// Resolve a channel name to its browser executable name.
///
/// For known channels, returns the executable name (e.g. "chrome", "msedge").
/// Unknown channels are returned as-is, allowing users to specify raw executable
/// names.
impl std::fmt::Display for BrowserChannel {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            BrowserChannel::Chrome => "chrome",
            BrowserChannel::ChromeBeta => "chrome-beta",
            BrowserChannel::ChromeDev => "chrome-dev",
            BrowserChannel::ChromeCanary => "chrome-canary",
            BrowserChannel::MsEdge => "msedge",
            BrowserChannel::MsEdgeBeta => "msedge-beta",
            BrowserChannel::MsEdgeDev => "msedge-dev",
            BrowserChannel::MsEdgeCanary => "msedge-canary",
        };
        write!(f, "{}", s)
    }
}

/// Resolve a channel name to a CDP HTTP endpoint URL.
///
/// Uses a three-tier strategy:
/// 1. Scan running processes for `--remote-debugging-port=N`
/// 2. Probe the channel's default port
/// 3. Scan ports 9222–9333 for a responding CDP endpoint
pub fn resolve_channel_to_endpoint(channel: &str) -> Result<String, String> {
    let ch = BrowserChannel::from_str(channel).ok_or_else(|| {
        format!(
            "Unknown browser channel: {channel}. \
             Supported channels: chrome, chrome-beta, chrome-dev, chrome-canary, \
             msedge, msedge-beta, msedge-dev, msedge-canary"
        )
    })?;

    let executable_name = ch.executable_name();

    // Tier 1: find --remote-debugging-port in running process command lines
    if let Some(port) = find_debug_port_in_running_processes(executable_name) {
        let endpoint = format!("http://localhost:{port}");
        if probe_cdp_port(port) {
            return Ok(endpoint);
        }
    }

    // Tier 2: try the channel's default port
    let default_port = ch.default_debug_port();
    if probe_cdp_port(default_port) {
        return Ok(format!("http://localhost:{default_port}"));
    }

    // Tier 3: scan a range of ports concurrently — probe all ports in the
    // range at once since localhost connection-refused is near-instant and
    // sequential scanning would waste time on timeouts for non-CDP open ports.
    let found = AtomicU16::new(0);
    let found_ref = &found;
    std::thread::scope(|s| {
        for port in 9222..=9333 {
            s.spawn(move || {
                if found_ref.load(Ordering::Relaxed) == 0 && probe_cdp_port(port) {
                    found_ref.store(port, Ordering::Relaxed);
                }
            });
        }
    });
    let found_port = found.load(Ordering::Relaxed);
    if found_port != 0 {
        return Ok(format!("http://localhost:{found_port}"));
    }

    Err(format!(
        "Could not find a running {executable_name} browser with remote debugging enabled.\n\
         To attach to a {channel} browser:\n\
         1. Open {executable_name} and go to chrome://inspect/#remote-debugging\n\
         2. Check 'Allow remote debugging for this browser instance'\n\
         3. Or start it with: {executable_name} --remote-debugging-port={default_port}\n\
         Then run: browser4-cli attach --cdp http://localhost:{default_port}"
    ))
}

/// Search for a running process matching `executable_name` and extract
/// its `--remote-debugging-port` value from the command line.
fn find_debug_port_in_running_processes(executable_name: &str) -> Option<u16> {
    let output = if cfg!(target_os = "windows") {
        // Windows: try PowerShell (Get-CimInstance) first, fall back to
        // deprecated wmic for older Windows versions.
        windows_process_list(executable_name)?
    } else {
        // macOS / Linux: use ps with `-o args=` for the full command line
        // (BSD `command=` may truncate long lines; `args=` is the portable
        // column for the complete argument vector).
        std::process::Command::new("ps")
            .args(["-e", "-o", "args="])
            .output()
            .ok()?
    };

    let stdout = String::from_utf8_lossy(&output.stdout);
    let mut requested_port: Option<u16> = None;
    for line in stdout.lines() {
        let lower = line.to_ascii_lowercase();
        // Only consider lines that actually reference the executable
        if !lower.contains(executable_name) {
            continue;
        }
        // Parse --remote-debugging-port=N
        for part in line.split_whitespace() {
            if let Some(port_str) = part.strip_prefix("--remote-debugging-port=") {
                if let Ok(port) = port_str.parse::<u16>() {
                    requested_port = Some(port);
                }
            }
        }
    }

    if let Some(port) = requested_port {
        if port != 0 {
            return Some(port);
        }
        // --remote-debugging-port=0 means Chrome picked a free port at random
        // (Browser4-launched browsers use this). The requested value 0 is not
        // a usable endpoint, so fall through to listening-port discovery below.
    }

    // The command line either had no --remote-debugging-port, or used `=0`
    // (random port). On Windows, find every port the executable is currently
    // LISTENING on and return the first one that answers a CDP health check.
    // This is what makes Browser4-launched browsers (random debug port)
    // discoverable via `attach --cdp chrome`.
    #[cfg(target_os = "windows")]
    {
        if let Some(pid) = resolve_executable_pid(executable_name) {
            let ps_cmd = format!(
                "Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {{ $_.OwningProcess -eq {pid} }} | Select-Object -ExpandProperty LocalPort"
            );
            if let Ok(out) = std::process::Command::new("powershell")
                .args(["-NoProfile", "-Command", &ps_cmd])
                .output()
            {
                if out.status.success() {
                    for line in String::from_utf8_lossy(&out.stdout).lines() {
                        if let Ok(port) = line.trim().parse::<u16>() {
                            if probe_cdp_port(port) {
                                return Some(port);
                            }
                        }
                    }
                }
            }
        }
    }

    // Fall back to the requested non-zero port even if the listening-port
    // discovery above found nothing (e.g. non-Windows or probe failures).
    requested_port.filter(|p| *p != 0)
}

/// Resolve the PID of a process whose name matches `executable_name` AND whose
/// command line requests remote debugging. Filtering on `--remote-debugging-port`
/// matters: without it, the first matching process is usually the user's own
/// everyday browser (no debug port), which has no listening ports to discover.
#[cfg(target_os = "windows")]
fn resolve_executable_pid(executable_name: &str) -> Option<String> {
    let ps_cmd = format!(
        "Get-CimInstance Win32_Process | Where-Object {{ $_.Name -like '*{0}*' -and $_.CommandLine -match '--remote-debugging-port' }} | Select-Object -First 1 -ExpandProperty ProcessId",
        executable_name
    );
    let out = std::process::Command::new("powershell")
        .args(["-NoProfile", "-Command", &ps_cmd])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let pid = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if pid.is_empty() || !pid.chars().all(|c| c.is_ascii_digit()) {
        return None;
    }
    Some(pid)
}

// Stub for non-Windows platforms — never called; satisfies the compiler.
#[cfg(not(target_os = "windows"))]
fn resolve_executable_pid(_executable_name: &str) -> Option<String> {
    None
}

/// On Windows, enumerate running processes via PowerShell (preferred) or
/// the deprecated `wmic` as a fallback for older systems.
#[cfg(target_os = "windows")]
fn windows_process_list(executable_name: &str) -> Option<std::process::Output> {
    // PowerShell — Get-CimInstance is the modern replacement for wmic.
    let ps_cmd = format!(
        "Get-CimInstance Win32_Process | Where-Object {{ $_.Name -like '*{}*' }} | Select-Object -ExpandProperty CommandLine",
        executable_name
    );
    if let Ok(out) = std::process::Command::new("powershell")
        .args(["-NoProfile", "-Command", &ps_cmd])
        .output()
    {
        if out.status.success() && !out.stdout.is_empty() {
            return Some(out);
        }
    }

    // Fallback: wmic (deprecated since Windows 10 21H1, but still present
    // on most systems).
    std::process::Command::new("wmic")
        .args([
            "process",
            "where",
            &format!("name like '%{executable_name}%'"),
            "get",
            "commandline",
        ])
        .output()
        .ok()
}

// Stub for non-Windows platforms — never called; satisfies the compiler.
#[cfg(not(target_os = "windows"))]
fn windows_process_list(_executable_name: &str) -> Option<std::process::Output> {
    None
}

/// Probe whether a CDP endpoint is listening on `localhost:<port>`.
///
/// Sends a quick GET to `/json/version` — the standard Chrome DevTools
/// Protocol health-check endpoint.
fn probe_cdp_port(port: u16) -> bool {
    let url = format!("http://localhost:{port}/json/version");
    match reqwest::blocking::Client::builder()
        .timeout(std::time::Duration::from_secs(2))
        .build()
        .and_then(|client| client.get(&url).send())
    {
        Ok(resp) => resp.status().is_success(),        Err(_) => false,
    }
}

/// Window-state snapshot of Browser4-managed Chrome processes.
///
/// Unlike a generic "any chrome has a window" check, this only looks at
/// chrome processes that Browser4 actually launched: command lines carrying
/// `--remote-debugging-port` AND the `PULSAR_CHROME` user-data-dir marker.
/// The user's own everyday Chrome/Edge is never counted, so a missing headed
/// window is not masked by unrelated browser windows.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Browser4WindowState {
    /// At least one Browser4-managed chrome process is alive.
    pub found_browser: bool,
    /// At least one Browser4-managed chrome process runs WITHOUT `--headless`.
    /// A headed session whose launch regressed to headless (the
    /// createBoundDriver display-mode bug) shows up as found_browser=true,
    /// headed_browser=false.
    pub headed_browser: bool,
    /// A headed Browser4 chrome process owns a visible main window.
    pub headed_window_visible: bool,
}

/// Detect the window state of Browser4-managed Chrome processes.
///
/// Returns `(found_browser, headed_browser, headed_window_visible)` semantics
/// via [Browser4WindowState]:
/// - `found_browser=false` — nothing to diagnose; callers stay silent.
/// - `found_browser=true, headed_browser=false` — the browser runs headless
///   even though the session asked for headed mode (launch-mode regression).
/// - `headed_browser=true, headed_window_visible=false` — the classic silent
///   no-window failure: process alive, navigation works, window never appeared.
pub fn browser4_window_state() -> Browser4WindowState {
    #[cfg(target_os = "windows")]
    {
        // Enumerate Browser4-managed chrome processes only (debug port +
        // PULSAR_CHROME profile marker), and for each report:
        //   PID|headless(0|1)|hasVisibleMainWindow(0|1)
        let ps = r#"Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" |
            Where-Object { $_.CommandLine -notmatch '--type=' -and $_.CommandLine -match '--remote-debugging-port' -and $_.CommandLine -match 'PULSAR_CHROME' } |
            ForEach-Object {
                $p = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
                $headless = if ($_.CommandLine -match '--headless') { '1' } else { '0' }
                $hwnd = if ($p -and $p.MainWindowHandle -ne 0) { '1' } else { '0' }
                "$($_.ProcessId)|$headless|$hwnd"
            }"#;
        let out = std::process::Command::new("powershell")
            .args(["-NoProfile", "-Command", ps])
            .output();

        let mut state = Browser4WindowState {
            found_browser: false,
            headed_browser: false,
            headed_window_visible: false,
        };
        if let Ok(o) = out {
            if o.status.success() {
                for line in String::from_utf8_lossy(&o.stdout).lines() {
                    let mut parts = line.trim().split('|');
                    let (Some(_pid), Some(headless), Some(hwnd)) =
                        (parts.next(), parts.next(), parts.next())
                    else {
                        continue;
                    };
                    state.found_browser = true;
                    if headless != "1" {
                        state.headed_browser = true;
                        if hwnd == "1" {
                            state.headed_window_visible = true;
                        }
                    }
                }
            }
        }
        state
    }
    #[cfg(not(target_os = "windows"))]
    {
        // No portable window-visibility check on Unix; report "unknown" in a
        // way that never triggers the headed-window warning.
        Browser4WindowState {
            found_browser: false,
            headed_browser: false,
            headed_window_visible: true,
        }
    }
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
fn run_sudo_command(program: &str, args: &[&str], description: &str) -> Result<(), String> {
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
            status
                .code()
                .map_or_else(|| "signal".to_string(), |c| c.to_string())
        ));
    }
    Ok(())
}

/// Check whether a supported browser (Chrome, Chromium, or Edge) is available.
/// If not, attempt to install Google Chrome automatically (Debian/Ubuntu only).
/// Other platforms receive a warning with manual instructions.
pub fn ensure_chrome_available() -> Result<(), String> {
    if find_browser_executable().is_some() {
        eprintln!("✅ A supported browser (Chrome / Chromium / Edge) is available.");
        return Ok(());
    }

    eprintln!("⚠  No supported browser found (Chrome, Chromium, or Edge).");

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
        eprintln!("   Install Chrome: https://www.google.com/chrome/");
        eprintln!("   Or Edge:        https://www.microsoft.com/edge");
        return Err("Cannot auto-install Chrome on this Linux distribution. \
             Install it manually from https://www.google.com/chrome/"
            .to_string());
    }

    if cfg!(target_os = "macos") {
        eprintln!("   Install a browser manually:");
        eprintln!("     brew install --cask google-chrome");
        eprintln!("     brew install --cask microsoft-edge");
        eprintln!("   Or download from: https://www.google.com/chrome/");
        return Err(
            "No supported browser found. Install Chrome: brew install --cask google-chrome, \
             or Edge: brew install --cask microsoft-edge"
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
    ensure_aot_cache_trained(&runtime);
    Ok(build_jar_launch_spec(&runtime, port))
}

/// Returns `true` when `token` is a JVM-level flag that must appear before
/// the main class on the `java` command line.
fn is_jvm_option(token: &str) -> bool {
    token.starts_with("-D") || token.starts_with("-X") || token.starts_with("--add-")
}

/// Collect the core JVM options and program arguments shared by both the
/// training run and the production run.  Keeping this set identical between
/// the two is what makes the AOT cache valid (the JVM requires the same
/// classpath and JVM flag set when the cache is produced and consumed).
fn collect_jvm_opts_and_program_args() -> (Vec<String>, Vec<String>) {
    let mut jvm_opts: Vec<String> = Vec::new();
    let mut program_args: Vec<String> = Vec::new();

    // Inject the detected browser path as -Dchrome.path so the server's
    // Browsers.searchChromeBinary() picks it up via System.getProperty as
    // its first-priority check.  Placed before BROWSER4_SERVER_OPTS so a
    // user-supplied -Dchrome.path=... in that env var overrides (Java uses
    // the last -D value for a given key).
    if let Some(browser_path) = find_browser_executable() {
        jvm_opts.push(format!("-Dchrome.path={}", browser_path.to_string_lossy()));
    }

    // Pin the backend's coding workspace to the repository root the CLI was
    // invoked from. The server is launched with working_dir = install_dir
    // (the bundled runtime), so a user.dir-based fallback in CodingWorkspace
    // can drift onto a sibling worktree and make every code tool read/write
    // the wrong checkout. `-Dbrowser4.agent.workspace` wins over that
    // fallback. Placed before BROWSER4_SERVER_OPTS so a user-supplied
    // -Dbrowser4.agent.workspace=... in that env var overrides (Java uses the
    // last -D value for a given key).
    if let Some(root) = find_browser4_root() {
        // Forward slashes (JVM-accepted on Windows) and no verbatim \\?\ prefix —
        // the argfile path would otherwise escape/mangle the embedded backslashes.
        jvm_opts.push(format!(
            "-Dbrowser4.agent.workspace={}",
            normalize_jvm_windows_path_text(&root.to_string_lossy())
        ));
    }

    // The Pulsar SDK's AppContext.APP_DATA_DIR defaults to $HOME/.pulsar.
    // Since this is the Browser4 application, override to $HOME/.browser4
    // so that LocalResourceProperties.load() finds config files in
    // ~/.browser4/config/conf-enabled/ (e.g. application-private.properties
    // with LLM API keys).
    jvm_opts.push("-Dapp.name=browser4".to_string());

    // Limit JIT compilation to C1 (client) tier for faster startup.
    // Placed before BROWSER4_SERVER_OPTS so users can override with
    // -XX:TieredStopAtLevel=4 in the env var for peak throughput.
    jvm_opts.push("-XX:TieredStopAtLevel=1".to_string());

    // Extra options from BROWSER4_SERVER_OPTS (space-separated).  Tokens
    // starting with -D, -X, -XX:, or --add- are JVM flags and go before -cp;
    // everything else is a program argument.
    if let Ok(raw) = std::env::var(BROWSER4_SERVER_OPTS_ENV) {
        for token in raw
            .split_whitespace()
            .map(str::trim)
            .filter(|t| !t.is_empty())
        {
            if is_jvm_option(token) {
                jvm_opts.push(token.to_string());
            } else {
                program_args.push(token.to_string());
            }
        }
    }

    (jvm_opts, program_args)
}

/// Enumerate the dependency JARs under `lib_dir` and join them, sorted, into
/// a classpath.  CDS and the AOT cache both require a stable, ordered JAR
/// list; the previous `lib/*` directory wildcard does not guarantee one.
///
/// On Windows each entry is stripped of the verbatim `\\?\` prefix inherited
/// from the canonicalized runtime data dir and normalized to forward slashes,
/// so the classpath is valid both as a plain command-line argument and inside
/// a Java `@argfile` (where a leading-only strip would leave `//?/` on every
/// entry after the first).
fn enumerate_classpath(lib_dir: &Path) -> Result<String, String> {
    let mut jars: Vec<String> = Vec::new();
    let entries = fs::read_dir(lib_dir)
        .map_err(|e| format!("cannot read lib dir {}: {e}", lib_dir.display()))?;
    for entry in entries {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) == Some("jar") {
            let raw = path.to_string_lossy().to_string();
            let normalized = if cfg!(windows) {
                normalize_jvm_windows_path_text(&raw)
            } else {
                raw
            };
            jars.push(normalized);
        }
    }
    if jars.is_empty() {
        return Err(format!("no jar files found in {}", lib_dir.display()));
    }
    jars.sort();
    Ok(jars.join(if cfg!(windows) { ";" } else { ":" }))
}

/// Detect the major JDK version of the bundled runtime by parsing
/// `java -version` (which prints to stderr, e.g. `openjdk version "25.0.4"`).
fn detect_java_major_version(java_path: &Path) -> Result<u32, String> {
    let output = Command::new(java_path)
        .arg("-version")
        .output()
        .map_err(|e| format!("cannot run {} -version: {e}", java_path.display()))?;
    let mut text = String::from_utf8_lossy(&output.stderr).to_string();
    if text.trim().is_empty() {
        text = String::from_utf8_lossy(&output.stdout).to_string();
    }
    let version = text
        .lines()
        .find_map(|line| line.split('"').nth(1))
        .ok_or_else(|| format!("cannot parse java version from: {}", text.trim()))?;
    let major = match version.strip_prefix("1.") {
        // Legacy `1.8.0_xxx` scheme → major 8.
        Some(rest) => rest.split('.').next().unwrap_or("0"),
        None => version.split('.').next().unwrap_or("0"),
    };
    major
        .parse::<u32>()
        .map_err(|_| format!("cannot parse java major version from: {version}"))
}

fn aot_cache_dir() -> PathBuf {
    // State dir (preserved across uninstall/reinstall), not runtime data dir,
    // so reinstalling the same version never retrains.
    resolve_default_state_dir().join(AOT_CACHE_DIR_NAME)
}

fn aot_cache_file() -> PathBuf {
    aot_cache_dir().join(AOT_CACHE_FILE_NAME)
}

fn aot_cache_key_file() -> PathBuf {
    aot_cache_dir().join(AOT_CACHE_KEY_FILE_NAME)
}

fn aot_training_marker_file() -> PathBuf {
    aot_cache_dir().join(AOT_TRAINING_MARKER_FILE_NAME)
}

fn aot_training_log_file() -> PathBuf {
    aot_cache_dir().join(AOT_TRAINING_LOG_FILE_NAME)
}

fn aot_training_argfile() -> PathBuf {
    aot_cache_dir().join(AOT_TRAINING_ARGFILE_FILE_NAME)
}

fn aot_training_pid_file() -> PathBuf {
    aot_cache_dir().join(AOT_TRAINING_PID_FILE_NAME)
}

/// Read the PID of the background training JVM, if one was recorded.
fn read_aot_training_pid() -> Option<u32> {
    fs::read_to_string(aot_training_pid_file())
        .ok()?
        .trim()
        .parse::<u32>()
        .ok()
}

/// Record the PID of the background training JVM.
fn write_aot_training_pid(pid: u32) {
    // The cache dir must exist for the write to succeed.
    let _ = fs::create_dir_all(aot_cache_dir());
    let _ = fs::write(aot_training_pid_file(), pid.to_string().as_bytes());
}

/// Whether a process with `pid` is still alive.
///
/// On failure to determine (tool missing, parse error) returns `true` —
/// conservative: the caller then falls back to the stale-marker window
/// instead of risking a concurrent training run.
fn aot_training_process_alive(pid: u32) -> bool {
    #[cfg(windows)]
    {
        // `tasklist /FI "PID eq <pid>" /NH` prints one row per match; with no
        // match it prints an INFO line.  Match on the PID column so the check
        // is locale-independent (tasklist output is localized).
        let out = Command::new("tasklist")
            .args(["/FI", &format!("PID eq {pid}"), "/NH"])
            .output();
        match out {
            Ok(o) => String::from_utf8_lossy(&o.stdout).contains(&format!(" {pid} ")),
            Err(_) => true,
        }
    }
    #[cfg(not(windows))]
    {
        // POSIX: `kill -0` probes existence without signalling.
        let status = Command::new("sh")
            .args(["-c", &format!("kill -0 {pid} 2>/dev/null")])
            .status();
        status.map(|s| s.success()).unwrap_or(true)
    }
}

/// Reclaim the training marker when the recorded training JVM is dead — the
/// training run crashed, was killed, or otherwise failed without completing.
///
/// Returns `true` when the marker was reclaimed (the caller should retrain
/// immediately); `false` when the training JVM is still alive or no PID was
/// recorded (the caller should wait — either for the run or the stale-marker
/// window).
fn reclaim_dead_aot_training() -> bool {
    let pid = match read_aot_training_pid() {
        Some(pid) => pid,
        None => return false,
    };
    if aot_training_process_alive(pid) {
        return false;
    }
    eprintln!(
        "AOT cache: previous training run (pid {pid}) is no longer running — retraining now"
    );
    release_aot_training_marker();
    let _ = fs::remove_file(aot_training_pid_file());
    let _ = fs::remove_file(aot_training_argfile());
    true
}

/// Atomically acquire the background-training marker.
///
/// Uses `create_new` so concurrent CLI invocations cannot both start a
/// training run.  A marker older than [`AOT_TRAINING_MARKER_STALE_AFTER`] is
/// treated as stale (the training JVM crashed or was killed) and reclaimed.
/// Returns `true` when the caller now owns the training run.
fn acquire_aot_training_marker() -> bool {
    // The marker file's parent (the cache dir) must exist for `create_new`
    // to succeed; on a fresh install it does not yet.
    let _ = fs::create_dir_all(aot_cache_dir());
    let marker = aot_training_marker_file();
    let write_marker = || -> bool {
        match fs::OpenOptions::new().write(true).create_new(true).open(&marker) {
            Ok(mut f) => {
                let _ = writeln!(f, "{}", std::process::id());
                true
            }
            Err(_) => false,
        }
    };
    if write_marker() {
        return true;
    }
    // Already exists — reclaim only when stale.
    let stale = fs::metadata(&marker)
        .and_then(|m| m.modified())
        .ok()
        .map(|modified| modified.elapsed().unwrap_or_default() > AOT_TRAINING_MARKER_STALE_AFTER)
        .unwrap_or(false);
    if stale {
        let _ = fs::remove_file(&marker);
        write_marker()
    } else {
        false
    }
}

fn release_aot_training_marker() {
    let _ = fs::remove_file(aot_training_marker_file());
}

/// Write the invalidation key into the training marker.
///
/// The content must compare **exactly** equal to the key (no trailing
/// newline): `ensure_aot_cache_trained` promotes the produced cache only
/// when the marker content matches the current key.
fn write_aot_training_marker_key(key: &str) {
    // `fs::write` truncates an existing marker file (created by the acquire
    // step) and writes the key verbatim.
    let _ = fs::write(aot_training_marker_file(), key.as_bytes());
}

/// Read the key recorded in the training marker, trimmed of any whitespace.
fn read_aot_training_marker_key() -> String {
    fs::read_to_string(aot_training_marker_file())
        .unwrap_or_default()
        .trim()
        .to_string()
}

/// Compute the AOT cache invalidation key: version tag + sorted jar list
/// (name + size) + the core JVM flag set.  This is a fast "should we
/// retrain?" heuristic — the JVM additionally validates the actual classpath
/// when loading the cache and silently falls back on any mismatch, so a
/// false-negative here is harmless.
fn aot_cache_invalidation_key(
    runtime: &InstalledBrowser4Runtime,
    jvm_opts: &[String],
) -> Result<String, String> {
    let mut hasher = Sha256::new();
    hasher.update(runtime.tag.as_bytes());
    hasher.update(b"\0");

    let mut entries: Vec<(String, u64)> = Vec::new();
    let dir = fs::read_dir(&runtime.lib_dir).map_err(|e| e.to_string())?;
    for entry in dir.flatten() {
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) == Some("jar") {
            if let Ok(meta) = entry.metadata() {
                entries.push((entry.file_name().to_string_lossy().to_string(), meta.len()));
            }
        }
    }
    entries.sort();
    for (name, size) in entries {
        hasher.update(name.as_bytes());
        hasher.update(b"\x1f");
        hasher.update(size.to_string().as_bytes());
        hasher.update(b"\0");
    }

    hasher.update(b"jvm-opts\0");
    for opt in jvm_opts {
        hasher.update(opt.as_bytes());
        hasher.update(b"\0");
    }
    Ok(format!("{:x}", hasher.finalize()))
}

/// Train the JVM AOT cache (JEP 483 class loading & linking, plus JEP 515
/// method profiles on JDK 25) on first launch, and reuse it on subsequent
/// launches.
///
/// Training runs **in the background**: the server the user is waiting for
/// starts immediately without AOT acceleration (the pre-4.14 behavior), and
/// the training JVM writes `app.aot` while the server is already up.  Every
/// later launch attaches the trained cache (see `build_jar_launch_spec`) so
/// restarts complete in seconds.
///
/// Called from three places: every server launch (`resolve_server_launch_spec`),
/// and right after `browser4-cli install` / `browser4-cli upgrade` commit a
/// runtime, so a first `open` shortly after an install already hits the cache.
///
/// This is deliberately non-fatal and **self-healing**: on any failure (JDK <
/// 24, unparseable version, missing jars, training crash) we log a warning and
/// proceed with a normal JVM start.  A failed background training run leaves a
/// marker that is reclaimed after [`AOT_TRAINING_MARKER_STALE_AFTER`], so the
/// next launch simply trains again — the failure never blocks the server and
/// never disables AOT permanently.  The runtime plugin loader
/// (`PluginClasspathEnhancer`, a URLClassLoader for `plugins/*.jar`) is
/// unaffected — the AOT cache only pre-loads classes seen on the main
/// classpath, so plugin classes simply load normally and are not cached.
pub(crate) fn ensure_aot_cache_trained(runtime: &InstalledBrowser4Runtime) {
    // Explicit opt-out (e.g. CI / e2e harness): start without AOT acceleration
    // instead of paying the one-time training cost on a fresh runtime bundle.
    if aot_cache_disabled() {
        return;
    }

    // Respect an explicit user-provided AOT configuration.
    if let Ok(raw) = std::env::var(BROWSER4_SERVER_OPTS_ENV) {
        if raw.contains("-XX:AOTCache") {
            return;
        }
    }

    let major = match detect_java_major_version(&runtime.java_path) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("AOT cache: {e}; skipping");
            return;
        }
    };
    if major < AOT_CACHE_MIN_JDK {
        return; // JEP 483 requires JDK 24+; older JVMs fall back to normal start.
    }

    let (jvm_opts, program_args) = collect_jvm_opts_and_program_args();
    let classpath = match enumerate_classpath(&runtime.lib_dir) {
        Ok(cp) => cp,
        Err(e) => {
            eprintln!("AOT cache: {e}; skipping");
            return;
        }
    };
    let key = match aot_cache_invalidation_key(runtime, &jvm_opts) {
        Ok(k) => k,
        Err(e) => {
            eprintln!("AOT cache: {e}; skipping");
            return;
        }
    };

    let cache_file = aot_cache_file();
    let key_file = aot_cache_key_file();
    let cached_key = fs::read_to_string(&key_file).unwrap_or_default();
    if cache_file.is_file() && cached_key == key {
        // Cache already trained and still valid — also clean up any leftover
        // marker/pid/argfile from the background run that produced it.
        release_aot_training_marker();
        let _ = fs::remove_file(aot_training_pid_file());
        let _ = fs::remove_file(aot_training_argfile());
        return;
    }

    // A background training run finished since this process last looked: the
    // training JVM produced `app.aot` but no CLI process was around to write
    // the key sidecar.  The marker carries the key it was started with, so a
    // matching marker + existing cache is a valid completion record.
    let marker_key = read_aot_training_marker_key();
    if cache_file.is_file() && marker_key == key {
        let _ = fs::write(&key_file, &key);
        release_aot_training_marker();
        let _ = fs::remove_file(aot_training_argfile());
        let _ = fs::remove_file(aot_training_pid_file());
        return;
    }

    // Stale cache for this runtime (wrong key or no key sidecar): drop it so
    // the server never attaches an invalid cache, then (re)train in the
    // background.  Deleting before spawning means a freshly completed run can
    // never be clobbered by a stale one.
    let _ = fs::remove_file(&cache_file);
    let _ = fs::remove_file(&key_file);

    if let Err(e) = fs::create_dir_all(aot_cache_dir()) {
        eprintln!("AOT cache: cannot create cache dir: {e}; skipping");
        return;
    }

    if !acquire_aot_training_marker() {
        // A training run is marked as in flight.  If its JVM is already dead
        // (crash, kill, OOM — the common failure mode), reclaim the marker
        // and retrain immediately instead of waiting for the stale-marker
        // window: the failure-recovery fast path.
        if !reclaim_dead_aot_training() {
            eprintln!(
                "JVM AOT cache training already in progress in the background — \
                the server starts without AOT acceleration until it completes"
            );
            return;
        }
        if !acquire_aot_training_marker() {
            eprintln!(
                "JVM AOT cache training already in progress in the background — \
                the server starts without AOT acceleration until it completes"
            );
            return;
        }
    }

    spawn_aot_training(&runtime, &classpath, &jvm_opts, &program_args, &cache_file, &key);
}

/// Spawn the detached JVM that trains the AOT cache, without waiting for it.
///
/// The training run reuses the same JVM flags and classpath as production
/// (required for cache validity), but binds a random port, runs in AOT
/// training mode (skipping the MCP session / browser launch via
/// [`AOT_TRAINING_JVM_FLAG`]) and exits after Spring context refresh
/// (`spring.context.exit=onRefresh`).  A 1G heap keeps the single-step
/// `-XX:AOTCacheOutput` peak (2× heap) bounded; heap size is not part of the
/// AOT cache validity check.
///
/// The child is deliberately detached: it outlives this CLI invocation and
/// writes its output to [`aot_training_log_file`].  On Windows it runs at
/// below-normal priority so it never competes with the server the user is
/// waiting on.
fn spawn_aot_training(
    runtime: &InstalledBrowser4Runtime,
    classpath: &str,
    jvm_opts: &[String],
    program_args: &[String],
    cache_file: &Path,
    key: &str,
) {
    eprintln!("Training JVM AOT cache in the background (one-time, only on first launch) ...");
    // The marker doubles as the completion record: it must carry the key this
    // run was started with so a later launch can promote the produced cache.
    write_aot_training_marker_key(key);

    let mut training_args: Vec<String> =
        Vec::with_capacity(jvm_opts.len() + program_args.len() + 7);
    training_args.extend(jvm_opts.iter().cloned());
    // Training-mode flag must sit among the JVM flags (before `-cp`).
    training_args.push(AOT_TRAINING_JVM_FLAG.to_string());
    training_args.push("-Xmx1G".to_string());
    training_args.push(format!("-XX:AOTCacheOutput={}", cache_file.display()));
    training_args.push("-Dspring.context.exit=onRefresh".to_string());
    training_args.push("-cp".to_string());
    training_args.push(classpath.to_string());
    training_args.push(BROWSER4_MAIN_CLASS.to_string());
    training_args.extend(program_args.iter().cloned());
    training_args.push("--server.port=0".to_string());

    let mut cmd = Command::new(&runtime.java_path);
    if should_use_java_argfile(&runtime.java_path, &training_args) {
        // Fixed-name argfile inside the cache dir: the JVM reads it at
        // startup, and any leftover is removed on the next cache check —
        // deleting it right after spawn() would race the JVM's read.
        let argfile = aot_training_argfile();
        let _ = fs::remove_file(&argfile);
        let contents = training_args
            .iter()
            .map(|a| java_argfile_token(a))
            .collect::<Vec<_>>()
            .join("\n");
        match fs::write(&argfile, contents) {
            Ok(()) => {
                cmd.arg(format!("@{}", argfile.display()));
            }
            Err(e) => {
                eprintln!("AOT cache: cannot write training argfile: {e}; using plain arguments");
                cmd.args(&training_args);
            }
        }
    } else {
        cmd.args(&training_args);
    }
    cmd.current_dir(&runtime.install_dir).stdin(Stdio::null());

    // Stream training output to a log file so a failed run is diagnosable.
    let log_file = aot_training_log_file();
    match fs::File::create(&log_file) {
        Ok(log) => match log.try_clone() {
            Ok(stdout) => {
                cmd.stdout(stdout);
                cmd.stderr(log);
            }
            Err(_) => {
                cmd.stdout(Stdio::null());
                cmd.stderr(Stdio::null());
            }
        },
        Err(e) => {
            eprintln!(
                "AOT cache: cannot open training log {}: {e}",
                log_file.display()
            );
            cmd.stdout(Stdio::null());
            cmd.stderr(Stdio::null());
        }
    }

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // CREATE_BELOW_NORMAL_PRIORITY_CLASS — the training JVM boots the
        // whole Spring context and must not compete with the server launch
        // the user is waiting on.
        const CREATE_BELOW_NORMAL_PRIORITY_CLASS: u32 = 0x0000_4000;
        cmd.creation_flags(CREATE_BELOW_NORMAL_PRIORITY_CLASS);
    }

    match cmd.spawn() {
        Ok(child) => {
            // Record the training JVM's PID so a later launch can detect a
            // dead (failed) training run and retrain immediately.
            write_aot_training_pid(child.id());
            // Deliberately detached: the training run outlives this CLI
            // invocation.  Dropping the handle does not kill the process.
            drop(child);
            eprintln!(
                "AOT cache training started in the background (log: {}) — \
                the server starts without AOT acceleration until training completes",
                log_file.display()
            );
        }
        Err(e) => {
            release_aot_training_marker();
            let _ = fs::remove_file(aot_training_pid_file());
            let _ = fs::remove_file(aot_training_argfile());
            eprintln!("AOT cache: background training could not start: {e}");
        }
    }
}

fn build_jar_launch_spec(runtime: &InstalledBrowser4Runtime, port: u16) -> ServerLaunchSpec {
    let program = runtime.java_path.clone();
    let program_display = program.display().to_string();

    // Enumerate the classpath explicitly; fall back to the directory wildcard
    // if enumeration fails so startup is never blocked by this change.
    let classpath_arg = enumerate_classpath(&runtime.lib_dir).unwrap_or_else(|_| {
        if cfg!(windows) {
            format!("{}\\*", runtime.lib_dir.display())
        } else {
            format!("{}/*", runtime.lib_dir.display())
        }
    });

    let (mut jvm_opts, program_args) = collect_jvm_opts_and_program_args();

    // Attach the trained AOT cache when present.  The JVM silently falls back
    // to a normal start if the cache is stale or incompatible.
    let cache_file = aot_cache_file();
    if !aot_cache_disabled() && cache_file.is_file() {
        jvm_opts.push(format!("-XX:AOTCache={}", cache_file.display()));
    }

    let mut args: Vec<String> = Vec::with_capacity(4 + jvm_opts.len() + program_args.len());
    args.extend(jvm_opts);
    args.push("-cp".to_string());
    args.push(classpath_arg);
    args.push(BROWSER4_MAIN_CLASS.to_string());
    args.extend(program_args);
    args.push(format!("--server.port={port}"));

    ServerLaunchSpec {
        kind: ServerLaunchKind::Jar,
        program,
        args,
        working_dir: runtime.install_dir.clone(),
        registry_target: runtime.lib_dir.clone(),
        description: format!(
            "Starting server from Browser4 runtime at {} using {} on port {}...",
            runtime.install_dir.display(),
            program_display,
            port
        ),
        version: Some(runtime.tag.clone()),
    }
}

/// Windows `CreateProcess` caps the entire command line (program + arguments)
/// at 32,767 characters.  The explicitly-enumerated classpath (required for a
/// stable CDS / AOT-cache order) spans ~230 JARs and blows past that cap on
/// long install paths, so switch to a Java `@argfile` (JDK 9+ launcher
/// feature) well before the limit.  See `write_java_argfile`.
///
/// The threshold sits safely below the 32,767-char cap: per-argument quoting
/// and encoding overhead vary, so an argfile is used early rather than at the
/// exact limit.
const WINDOWS_ARGFILE_THRESHOLD: usize = 30_000;

/// Rough length of the single-string command line Windows would build for
/// `program args...` (each argument separated by one space, plus two quote
/// characters as pessimistic quoting overhead).
fn estimated_windows_command_line_len(program: &Path, args: &[String]) -> usize {
    program.to_string_lossy().len() + args.iter().map(|a| a.len() + 3).sum::<usize>()
}

/// Normalize Windows path text embedded in JVM arguments: drop verbatim
/// `\\?\` prefixes **anywhere** they appear and convert backslashes to
/// forward slashes, which the JVM accepts on Windows.
///
/// A single argument can embed several verbatim prefixes: the `-cp`
/// classpath is one `;`-joined token whose *every* entry carries its own
/// `\\?\` (the runtime data dir is canonicalized — see
/// `resolve_runtime_data_dir`), and `-XX:AOTCache=\\?\C:\...` embeds the
/// prefix after `=`.  Stripping only a leading prefix leaves `//?/` once
/// backslashes are converted, which the JVM cannot open — that corrupted
/// both the AOT training run and the main server launch on Windows.
fn normalize_jvm_windows_path_text(text: &str) -> String {
    text.replace(r"\\?\UNC\", "//")
        .replace(r"\\?\", "")
        .replace('\\', "/")
}

/// Normalize one JVM argument for embedding in a Java `@argfile`.
///
/// The java launcher parses `@argfile` tokens itself and interprets
/// backslash escape sequences (`\t`, `\n`, `\r`, ...) inside tokens, which
/// would corrupt Windows paths (`C:\tools\...` → tab/newline).  All
/// backslashes are therefore normalized to forward slashes, and tokens
/// containing whitespace are double-quoted.
fn java_argfile_token(token: &str) -> String {
    let normalized = normalize_jvm_windows_path_text(token);
    if normalized.chars().any(char::is_whitespace) {
        format!("\"{normalized}\"")
    } else {
        normalized
    }
}

/// Write `args` into a fresh Java `@argfile` and return the file path.
///
/// The file lives in its own uniquely-named directory so callers can pass that
/// directory as `cleanup_dir` and remove everything with one
/// `fs::remove_dir_all`.
fn write_java_argfile(args: &[String]) -> Result<PathBuf, String> {
    let unique = format!(
        "browser4-argfile-{}-{}",
        std::process::id(),
        chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()
    );
    let dir = std::env::temp_dir().join(unique);
    fs::create_dir_all(&dir).map_err(|e| format!("cannot create argfile dir: {e}"))?;
    let file = dir.join("java-args.txt");
    let content = args
        .iter()
        .map(|a| java_argfile_token(a))
        .collect::<Vec<_>>()
        .join("\n");
    fs::write(&file, content).map_err(|e| format!("cannot write argfile: {e}"))?;
    Ok(file)
}

/// Decide whether the java command line should be moved into an `@argfile`.
/// Only relevant on Windows; other platforms have multi-megabyte exec limits.
fn should_use_java_argfile(program: &Path, args: &[String]) -> bool {
    cfg!(windows) && estimated_windows_command_line_len(program, args) > WINDOWS_ARGFILE_THRESHOLD
}

fn command_for_launch_spec(
    launch_spec: &ServerLaunchSpec,
) -> Result<PreparedLaunchCommand, String> {
    let mut command = Command::new(&launch_spec.program);
    let mut cleanup_dir = None;
    let mut argfile = None;
    if should_use_java_argfile(&launch_spec.program, &launch_spec.args) {
        // The fully-enumerated classpath exceeds the Windows command-line
        // limit; pass it through a Java @argfile instead.  Falls back to the
        // plain argument list when the argfile cannot be written (the spawn
        // will then surface the underlying OS error).
        match write_java_argfile(&launch_spec.args) {
            Ok(file) => {
                cleanup_dir = file.parent().map(Path::to_path_buf);
                command.arg(format!("@{}", file.display()));
                argfile = Some(file);
            }
            Err(e) => {
                eprintln!("Failed to write java argfile: {e}; using plain arguments");
                command.args(&launch_spec.args);
            }
        }
    } else {
        command.args(&launch_spec.args);
    }
    command
        .current_dir(&launch_spec.working_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    Ok(PreparedLaunchCommand {
        command,
        cleanup_dir,
        argfile,
    })
}

fn launch_ready_timeout(_launch_spec: &ServerLaunchSpec) -> Duration {
    JAR_SERVER_READY_TIMEOUT
}

pub(crate) fn find_browser4_root() -> Option<PathBuf> {
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
/// a `runtime/bin/java` launcher produced by jlink.  The runtime image must
/// also contain `runtime/lib/jvm.cfg` — a partially-deleted runtime (e.g. a
/// rebuild interrupted while the previous bundle was locked) can leave
/// `java.exe` behind while removing `jvm.cfg`, making the launcher report
/// "could not open jvm.cfg".  Such a broken bundle is NOT reused; the caller
/// falls through to a rebuild.
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

    // jvm.cfg sits at <runtime>/lib/jvm.cfg next to the bin/ launcher.
    let jvm_cfg = java_path
        .parent()
        .and_then(|bin_dir| bin_dir.parent())
        .map(|runtime_dir| runtime_dir.join("lib").join("jvm.cfg"));

    let runtime_healthy = match &jvm_cfg {
        Some(cfg) => cfg.is_file(),
        None => false,
    };

    if has_jars && runtime_healthy && java_path.is_file() {
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

/// Why an existing local runtime bundle does not match the checked-out sources.
#[derive(Debug, PartialEq, Eq)]
enum LocalBundleStaleness {
    /// The checked-out project version (root pom.xml) differs from the
    /// version embedded in the bundled jar names.
    VersionMismatch { checked_out: String, bundled: String },
    /// Versions match, but some source file under the checked-out modules is
    /// newer than the newest bundled project jar — sources were edited after
    /// the bundle was assembled.
    SourcesNewerThanBundle,
}

/// Read the checked-out project version from the root `pom.xml` of a Browser4
/// repository checkout.  Returns the `<version>` that belongs to the root
/// artifact itself (`<artifactId>browser4</artifactId>`), NOT the parent POM
/// version declared above it.
fn checked_out_project_version(root: &Path) -> Option<String> {
    let pom = std::fs::read_to_string(root.join("pom.xml")).ok()?;
    let marker = "<artifactId>browser4</artifactId>";
    let marker_end = pom.find(marker)? + marker.len();
    let rest = &pom[marker_end..];
    let head = if rest.len() > 512 { &rest[..512] } else { rest };
    let version_open = "<version>";
    let start = head.find(version_open)? + version_open.len();
    let tail = &head[start..];
    let end = tail.find("</version>")?;
    let version = tail[..end].trim();
    if version.is_empty() { None } else { Some(version.to_string()) }
}

/// Compare two project version strings such as `4.13.13-SNAPSHOT` numerically,
/// segment by segment (`4.13.9-SNAPSHOT` < `4.13.10-SNAPSHOT`).  Trailing
/// qualifiers after `-` are ignored.  Returns true when `a` is a later version.
fn project_version_newer(a: &str, b: &str) -> bool {
    fn leading_number(s: &str) -> u64 {
        s.split('-')
            .next()
            .unwrap_or("")
            .chars()
            .take_while(|c| c.is_ascii_digit())
            .collect::<String>()
            .parse()
            .unwrap_or(0)
    }
    let av: Vec<u64> = a.split('.').map(leading_number).collect();
    let bv: Vec<u64> = b.split('.').map(leading_number).collect();
    for i in 0..av.len().max(bv.len()) {
        let x = av.get(i).copied().unwrap_or(0);
        let y = bv.get(i).copied().unwrap_or(0);
        if x != y {
            return x > y;
        }
    }
    false
}

/// Extract the project version embedded in the assembled bundle's jar names
/// (e.g. `browser4-rest-4.13.13-SNAPSHOT.jar`) together with the newest such
/// jar's modified time.  Returns `None` when the bundle contains no project
/// jars (not a locally built bundle).
fn bundled_project_version(lib_dir: &Path) -> Option<(String, std::time::SystemTime)> {
    let mut newest: Option<(String, std::time::SystemTime)> = None;
    let entries = std::fs::read_dir(lib_dir).ok()?;
    for entry in entries.flatten() {
        let path = entry.path();
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
        // browser4-rest is the backend module every locally built bundle
        // contains; its version suffix marks the build's project version.
        let version = name
            .strip_prefix("browser4-rest-")
            .and_then(|rest| rest.strip_suffix(".jar"));
        let Some(version) = version else { continue };
        if version.is_empty() || !version.contains('.') {
            continue;
        }
        let Ok(metadata) = path.metadata() else { continue };
        let Ok(mtime) = metadata.modified() else { continue };
        // Newest jar wins; when a stale jar was copied at the same clock tick
        // (filesystem timestamp granularity) and mtimes tie, prefer the later
        // version so a leftover older build is never mistaken for the bundle's
        // real version.
        let replace = match &newest {
            None => true,
            Some((v, t)) => {
                mtime > *t || (mtime == *t && project_version_newer(version, v))
            }
        };
        if replace {
            newest = Some((version.to_string(), mtime));
        }
    }
    newest
}

/// Newest modification time of any Kotlin/Java source (or pom.xml) under the
/// modules whose code ships in the runtime bundle, when newer than `baseline`.
/// Returns `true` when at least one source file is newer than the bundle jars.
fn local_sources_newer_than(root: &Path, baseline: std::time::SystemTime) -> bool {
    let mut source_roots: Vec<PathBuf> = vec![
        root.join("browser4-rest").join("src"),
        root.join("browser4-agentic").join("src"),
        root.join("browser4-common").join("src"),
        root.join("browser4-apps").join("browser4-bundle").join("pom.xml"),
    ];
    // browser4-core ships multiple modules (skeleton, browser, parse, ...).
    let core_src = root.join("browser4-core");
    if let Ok(entries) = std::fs::read_dir(&core_src) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() && path.join("src").join("main").is_dir() {
                source_roots.push(path.join("src"));
            }
        }
    }

    fn walk_newer(dir: &Path, baseline: std::time::SystemTime, found: &mut bool) {
        if *found {
            return;
        }
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                walk_newer(&path, baseline, found);
            } else {
                let is_source = matches!(
                    path.extension().and_then(|e| e.to_str()),
                    Some("kt" | "java")
                );
                if is_source {
                    if let Ok(metadata) = path.metadata() {
                        if let Ok(mtime) = metadata.modified() {
                            if mtime > baseline {
                                *found = true;
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    let mut found = false;
    for root_dir in &source_roots {
        walk_newer(root_dir, baseline, &mut found);
        if found {
            break;
        }
    }
    found
}

/// Detect whether an existing local runtime bundle at `lib_dir` was built from
/// sources that differ from the current checkout at `root`.
fn detect_local_bundle_staleness(root: &Path, lib_dir: &Path) -> Option<LocalBundleStaleness> {
    let checked_out = checked_out_project_version(root)?;
    let (bundled, newest_jar_mtime) = bundled_project_version(lib_dir)?;

    if bundled != checked_out {
        return Some(LocalBundleStaleness::VersionMismatch {
            checked_out,
            bundled,
        });
    }
    if local_sources_newer_than(root, newest_jar_mtime) {
        return Some(LocalBundleStaleness::SourcesNewerThanBundle);
    }
    None
}

/// Print a loud, actionable warning when the existing local runtime bundle was
/// built from older sources than the current checkout.
fn warn_bundle_staleness(root: &Path, staleness: &LocalBundleStaleness) {
    let reason = match staleness {
        LocalBundleStaleness::VersionMismatch {
            checked_out,
            bundled,
        } => format!(
            "the existing local Browser4 runtime bundle was built from \
             {bundled} sources, but the checked-out sources are {checked_out}"
        ),
        LocalBundleStaleness::SourcesNewerThanBundle => {
            "the checked-out sources are newer than the existing local \
             Browser4 runtime bundle"
                .to_string()
        }
    };
    // Name the rebuild command for the platform we are running on:
    // powershell.exe on Windows (also runnable from cmd/Git Bash), pwsh elsewhere.
    let rebuild_command = if cfg!(windows) {
        format!(
            "powershell -ExecutionPolicy Bypass -File {}\\browser4-apps\\browser4-bundle\\build-runtime-bundle.ps1",
            root.display()
        )
    } else {
        format!(
            "pwsh -File {}/browser4-apps/browser4-bundle/build-runtime-bundle.ps1",
            root.display()
        )
    };
    eprintln!(
        "\n\u{26A0}  {reason}.\n\
         \u{26A0}  Serving the OLD backend build — behaviour may not match the checked-out code.\n\
         \u{26A0}  To rebuild from source, run:\n\
         \u{26A0}    {rebuild_command}\n\
         \u{26A0}  (or set BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 and re-run this command)"
    );
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
/// # Local runtime bundle lifecycle (dev mode)
///
/// - **Where the bundle lives:** `browser4-apps/browser4-bundle/target/runtime-bundle/`.
///   The assembled runtime sits under `_work/<platform>/<platform>/` — `runtime/` is
///   the jlink JRE image, `lib/` holds the backend jars — and the distributable
///   archive (`browser4-bundle-runtime-<platform>.zip` / `.tar.gz`) is written next
///   to `_work/`.
/// - **When it is rebuilt:** dev-mode auto-start from a checkout reuses an existing
///   bundle WITHOUT rebuilding (fast path below) unless the bundle is missing or
///   broken, or `BROWSER4_CLI_FORCE_REBUILD_BUNDLE` is set.  If the staleness check
///   finds the on-disk bundle older than the checked-out sources it is still reused
///   (the rebuild may be a deliberate, slow operation) but a loud warning names the
///   rebuild command.  A full rebuild (Maven `install` + the platform build script)
///   only runs when no valid bundle exists.
/// - **How to force a rebuild:** set `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1` and re-run
///   the command, or run `build-runtime-bundle.ps1` manually (optionally after
///   deleting `browser4-apps/browser4-bundle/target/runtime-bundle/_work`).
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

    // Fast path: runtime bundle already assembled — nothing to do
    // (unless --force-rebuild-bundle is active).  The bundle is reused
    // UNCONDITIONALLY on the happy path, so when it looks stale relative to
    // the checked-out sources we still reuse it (the rebuild may be
    // deliberate, e.g. a slow machine) but warn loudly and name the rebuild
    // command — a silently stale backend costs hours of misdiagnosis.
    if !should_force_rebuild_bundle() {
        if let Some(runtime) = existing_runtime_bundle(&lib_dir, &java_path, &work_dir) {
            if let Some(staleness) = detect_local_bundle_staleness(root, &lib_dir) {
                warn_bundle_staleness(root, &staleness);
            }
            eprintln!(
                "Using existing local Browser4 runtime bundle at {}.",
                work_dir.display()
            );
            return Ok(Some(runtime));
        }
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
    // when only the runtime assembly step needs to be re-run.  When
    // --force-rebuild-bundle is active, always rebuild regardless.
    if !should_force_rebuild_bundle() && maven_jar_exists(&bundle_module_dir) {
        eprintln!(
            "Using existing Browser4 bundle JAR at {}; skipping Maven package.",
            bundle_module_dir
                .join("target")
                .join("Browser4Bundle.jar")
                .display()
        );
    } else {
        if should_force_rebuild_bundle() {
            eprintln!(
                "[daemon] --force-rebuild-bundle is active; \
                 forcing Maven rebuild of Browser4Bundle.jar ..."
            );
        } else {
            eprintln!(
                "Building local Browser4 runtime bundle from {} ...",
                bundle_module_dir.display()
            );
        }
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
    // The ErrorEncoding property only exists on PowerShell 7+ — on Windows
    // PowerShell 5.1 (powershell.exe) it raises a PropertyAssignmentException,
    // so the assignment is guarded with try/catch.
    let script_path_escaped = script_path.to_string_lossy().replace('\'', "''");
    let command = format!(
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; \
         try {{ [Console]::ErrorEncoding = [System.Text.Encoding]::UTF8 }} catch {{}}; \
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
    let force_remote = should_force_remote_bundle();

    // When running from a Browser4 repository checkout, prefer the local
    // project build over any globally installed runtime.  The local build
    // reflects the current source tree, which is what developers expect
    // when they run `cargo run -- <command>`.
    //
    // BROWSER4_CLI_FORCE_REMOTE_BUNDLE skips the local build entirely —
    // useful in CI / corporate environments where Maven or jlink are
    // unreliable (e.g. behind a proxy that only allows HTTPS to GitHub).
    if !force_remote {
        let project_root = find_browser4_root();
        if let Some(root) = &project_root {
            match try_build_local_runtime_bundle(root).await {
                Ok(Some(runtime)) => return Ok(runtime),
                Ok(None) => {
                    return Err(format!(
                        "Local Browser4 runtime bundle is not available.\n\
                         When running from a repository checkout, the local runtime bundle must \
                         be built from source so that the server matches the CLI at HEAD.\n\
                         \n\
                         Build it manually:\n  \
                         cd browser4-apps/browser4-bundle\n  \
                         powershell -File build-runtime-bundle.ps1\n\
                         \n\
                         Or switch to the globally installed runtime (production mode):\n  \
                         set BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1\n  \
                         browser4-cli <command>"
                    ));
                }
                Err(error) => {
                    return Err(format!(
                        "Local Browser4 runtime bundle build failed: {error}\n\
                         \n\
                         When running from a repository checkout, the local runtime bundle must \
                         be built from source so that the server matches the CLI at HEAD.\n\
                         Fix the build issue above, or switch to the globally installed runtime \
                         (production mode):\n  \
                         set BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1"
                    ));
                }
            }
        }
    }

    // Not running from a repository checkout — use a globally installed runtime
    // (from a prior `install` command) or download one.
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

    // Download and install the runtime bundle.
    // This is a one-time setup — subsequent launches use the cached runtime
    // and start in under 5 seconds.
    eprintln!(
        "Browser4 runtime is not installed yet — downloading now (one-time setup, ~130 MB)..."
    );
    eprintln!("Tip: run 'browser4-cli install' beforehand to avoid this download on first use.");
    install_browser4_runtime(None, false).await
}

async fn start_server(
    launch_spec: &ServerLaunchSpec,
    base_url: &str,
    port: u16,
) -> Result<(), String> {
    let server_start = Instant::now();
    let startup_log = create_server_startup_log(launch_spec, port).map_err(|error| {
        eprintln!("Failed to initialize Browser4 startup log: {error}");
        error
    })?;
    // Suppress the startup log path during normal operation — it's useful
    // for debugging but clutters the output for first-time users. The path
    // is always shown on failure (via format_server_startup_failure_message).

    let PreparedLaunchCommand {
        mut command,
        mut cleanup_dir,
        argfile,
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
    if let Some(argfile) = &argfile {
        let contents = fs::read_to_string(argfile).unwrap_or_else(|e| format!("<unreadable: {e}>"));
        append_startup_log_message(
            &startup_log.path,
            format!(
                "Launch argfile {} contents (classpath moved out of the command line):\n{}",
                argfile.display(),
                contents
            ),
        );
    }

    command
        .stdin(Stdio::null())
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
        // Disable the proxy for the readiness probe.  reqwest reads the
        // HTTP_PROXY / HTTPS_PROXY environment variables but does NOT honour
        // NO_PROXY, so probing `localhost` goes through the configured proxy
        // (e.g. http://127.0.0.1:10808).  During backend startup the proxy
        // can stall on the just-bound port, hanging every probe for the full
        // 5s timeout and adding ~10s to a cold `open`.  `no_proxy()` makes the
        // probe connect to localhost directly.
        .no_proxy()
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
            version: launch_spec.version.clone(),
        },
        None,
    );

    // Record the plugins fingerprint so later commands can detect plugin
    // changes and trigger a warm restart.  The plugins directory is resolved
    // relative to the server's working directory (the server's PluginService
    // defaults to `plugins/` under its cwd).
    record_plugins_fingerprint(port, &launch_working_dir.join("plugins"));

    // Detach: we drop the Child handle here. The spawned process continues
    // running independently because we set all stdio to null and call drop().
    drop(child);

    append_startup_log_message(
        &startup_log.path,
        format!("Browser4 reported ready at {base_url}; managed pid {managed_pid}"),
    );
    let elapsed = server_start.elapsed().as_secs_f64();
    // Clear the spinner line and print a clean ready message.
    // Log path is only shown on failure (via format_server_startup_failure_message).
    eprint!("\r\x1b[K");
    eprintln!("Server ready in {:.1}s", elapsed);
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
    if let Some(dir) = log_dir {
        return dir.to_path_buf();
    }
    if let Ok(dir) = std::env::var("BROWSER4_SERVER_LOG_DIR") {
        let trimmed = dir.trim();
        if !trimmed.is_empty() {
            let p = PathBuf::from(trimmed);
            // Create the directory early so callers don't have to.
            let _ = fs::create_dir_all(&p);
            return p;
        }
    }
    default_cli_temp_dir()
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
    // Build the parent->children map in a single WMI round-trip, then walk the
    // tree in memory.  The previous implementation queried `Get-CimInstance
    // -Filter "ParentProcessId = ..."` once per recursion level, which under a
    // launcher that fans out into many descendants (JVM + Chrome + helpers)
    // cost ~10s.  One unfiltered query plus an in-memory walk is ~1s.
    let ps_command = format!(
        r#"
$byParent = @{{}}
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    ForEach-Object {{ $byParent[[string]$_.ParentProcessId] = @($byParent[[string]$_.ParentProcessId]) + $_.ProcessId }}
$visited = New-Object 'System.Collections.Generic.HashSet[string]'
function Get-DescendantIds([UInt32] $ProcessId) {{
    $key = [string]$ProcessId
    if ($visited.Contains($key)) {{ return }}
    [void]$visited.Add($key)
    foreach ($childId in $byParent[$key]) {{
        $childId
        Get-DescendantIds $childId
    }}
}}
$ids = @(Get-DescendantIds {launcher_pid})
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
        .or_else(|| crate::config::read_config().server.clone())
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

    // Explicit short per-probe timeouts: the readiness loop must stay
    // responsive and finish within its overall budget even when the global
    // client timeout is large (--timeout 600) or a probe stalls.
    const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

    let health_response = match client.get(&health_url).timeout(PROBE_TIMEOUT).send().await {
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

    let tools_response = match client.get(&tools_url).timeout(PROBE_TIMEOUT).send().await {
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

    // Spinner frames for cold-start progress indication.
    const SPINNER: &[char] = &['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
    let is_cold_start = startup_log_path.is_some();

    if !initial_quiet_wait.is_zero() {
        if is_cold_start {
            let mut frame = 0usize;
            let start_sleep = Instant::now();
            while start_sleep.elapsed() < initial_quiet_wait {
                let elapsed = Instant::now().duration_since(start).as_secs();
                // Show the initial startup phase — at this point the JVM and
                // Spring Boot are loading; the TCP port isn't open yet.
                // \r returns to column 0; \x1b[K clears to end of line so that
                // the previous (longer) frame doesn't leave trailing characters.
                eprint!(
                    "\r\x1b[K  {} Starting server... ({}s) — JVM loading, waiting for TCP port...",
                    SPINNER[frame % SPINNER.len()],
                    elapsed
                );
                frame += 1;
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        } else {
            tokio::time::sleep(initial_quiet_wait).await;
        }
        if start.elapsed() >= timeout {
            last_error = format!(
                "readiness checks were deferred during the initial {}s startup grace period",
                initial_quiet_wait.as_secs()
            );
        }
    }

    let mut spinner_frame = 0usize;
    // Cold-start logs reveal fatal backend errors (denied writes, port
    // conflicts, missing deps) seconds before the readiness timeout elapses.
    // Poll the log periodically so the CLI fails fast with a real diagnosis
    // instead of spinning for the full `timeout`.
    let mut last_fatal_log_check_at = Instant::now() - Duration::from_secs(2);
    while start.elapsed() <= timeout {
        if is_cold_start && last_fatal_log_check_at.elapsed() >= Duration::from_secs(2) {
            last_fatal_log_check_at = Instant::now();
            if let Some(log_path) = startup_log_path {
                let tail = read_startup_log_tail(log_path);
                if log_mentions_fatal_startup_error(&tail) {
                    return Err(format_server_startup_failure_message(
                        base_url,
                        Some("the Browser4 backend reported a fatal error during startup."),
                        &format!("Last readiness probe result: {last_error}"),
                        None,
                        startup_log_path,
                    ));
                }
            }
        }

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

        if is_cold_start {
            let elapsed = start.elapsed().as_secs();
            let remaining = timeout.as_secs().saturating_sub(elapsed);
            // Include the stage-level progress so users can see what phase
            // the server is in (JVM, Spring Boot, MCP tools, browser init).
            // \r returns to column 0; \x1b[K clears to end of line.
            eprint!(
                "\r\x1b[K  {} Starting server... ({}s elapsed, ~{}s remaining) — {}",
                SPINNER[spinner_frame % SPINNER.len()],
                elapsed,
                remaining,
                progress_status
            );
            spinner_frame += 1;
        } else if last_progress_log_at.elapsed() >= Duration::from_secs(10) {
            let elapsed = start.elapsed().as_secs();
            let remaining = timeout.as_secs().saturating_sub(elapsed);
            eprintln!(
                "Waiting for Browser4 server at {} ({}s elapsed, ~{}s remaining): {}",
                base_url, elapsed, remaining, progress_status
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
            message if message.is_empty() => {
                "JVM is starting, waiting for Spring Boot...".to_string()
            }
            message => format!("Spring Boot is UP, waiting for MCP tools ({message})"),
        },
        ServerState::Unreachable(_) => {
            "TCP port not open yet, JVM may still be loading...".to_string()
        }
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

    let log_tail = startup_log_path.map(read_startup_log_tail);
    let permission_denied = log_tail
        .as_deref()
        .is_some_and(log_mentions_permission_denied);

    let mut suggestions = vec!["inspect the Browser4 startup log for the underlying server error."];
    if timeout.is_some() {
        suggestions.push("retry the command after Browser4 finishes starting.");
    } else {
        suggestions.push("confirm Java/Browser4 dependencies are available, then retry.");
    }
    suggestions.push("common causes: insufficient JVM heap memory, missing native dependencies, port conflicts, an unwritable logs/runtime directory, or a corrupt Browser4 runtime installation.");
    if permission_denied {
        suggestions.push("the startup log shows a denied write to the backend's logs/ directory — set BROWSER4_RUNTIME_DIR (and BROWSER4_CLI_STATE_DIR) to a writable directory, then retry.");
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

    // Lossy read: backend logs can contain non-UTF-8 bytes (e.g. GBK-encoded
    // OS error messages like `拒绝访问` on Windows). `read_to_string` fails on
    // those, which hid the tail entirely; lossy decoding keeps it visible.
    let contents = match fs::read(path) {
        Ok(bytes) => String::from_utf8_lossy(&bytes).into_owned(),
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

/// True when the startup log tail shows the backend could not create its
/// `logs/*.log` files — the signature of a denied write in sandboxes that only
/// allow writes to the workspace. Matching `FileNotFoundException` against a
/// `logs` path (rather than any exception) keeps this specific and avoids
/// flagging unrelated missing-file errors. Both tokens are ASCII in the JVM's
/// log even when the OS message that follows is GBK-encoded (`拒绝访问`).
fn log_mentions_permission_denied(log: &str) -> bool {
    let haystack = log.to_lowercase();
    haystack.contains("filenotfoundexception") && haystack.contains("logs")
}

/// True when the startup log tail shows a fatal error that no amount of
/// waiting will resolve, so the readiness loop should fail fast instead of
/// spinning until its timeout.  Each signature is a specific, common backend
/// failure:
///
/// - a denied write to `logs/*.log` (`log_mentions_permission_denied`);
/// - `APPLICATION FAILED TO START` — Spring Boot's banner for any fatal
///   context-load failure (bad config, unresolvable bean, missing dep);
/// - "Web server failed to start" with `Port .* was already in use` — the
///   classic port-conflict signature Spring Boot logs at startup.
fn log_mentions_fatal_startup_error(log: &str) -> bool {
    let haystack = log.to_lowercase();
    log_mentions_permission_denied(log)
        || haystack.contains("application failed to start")
        || (haystack.contains("web server failed to start")
            && haystack.contains("already in use"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::{create_dir_all, write};
    use std::sync::{Mutex, MutexGuard};
    use tempfile::TempDir;

    /// Global lock to serialize tests that manipulate `BROWSER4_RUNTIME_DIR`
    /// and `BROWSER4_CLI_STATE_DIR` environment variables.  Without this,
    /// parallel test execution causes cross-test contamination.
    static ENV_MUTEX: Mutex<()> = Mutex::new(());

    /// Acquire the global env mutex, tolerating a poisoned lock from a
    /// previous test panic.  This prevents a single assertion failure from
    /// cascading into 18 `PoisonError` failures across other tests.
    fn lock_env_mutex() -> MutexGuard<'static, ()> {
        ENV_MUTEX.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Drop-based guard that isolates `BROWSER4_RUNTIME_DIR` and
    /// `BROWSER4_CLI_STATE_DIR` for a single test.  On drop (including
    /// panic unwinding) the original env-var values are restored, so a
    /// panicked test can never leak its temp-directory paths into
    /// subsequent tests.
    struct TestEnvGuard {
        prev_runtime: Option<String>,
        prev_state: Option<String>,
    }

    impl TestEnvGuard {
        fn lock(tmp: &Path) -> Self {
            let prev_runtime = env::var("BROWSER4_RUNTIME_DIR").ok();
            let prev_state = env::var("BROWSER4_CLI_STATE_DIR").ok();
            // Pre-create the directories so that `resolve_runtime_data_dir`
            // and `resolve_default_state_dir` can canonicalize the paths
            // consistently across calls (canonicalize fails on nonexistent
            // paths and falls back to the raw string, which may differ in
            // format from the canonicalised form on Windows).
            let runtime_dir = tmp.join("runtime-data");
            let state_dir = tmp.join("state");
            let _ = fs::create_dir_all(&runtime_dir);
            let _ = fs::create_dir_all(&state_dir);
            unsafe {
                env::set_var("BROWSER4_RUNTIME_DIR", runtime_dir.as_os_str());
                env::set_var("BROWSER4_CLI_STATE_DIR", state_dir.as_os_str());
            }
            Self {
                prev_runtime,
                prev_state,
            }
        }
    }

    impl Drop for TestEnvGuard {
        fn drop(&mut self) {
            match &self.prev_runtime {
                Some(v) => unsafe { env::set_var("BROWSER4_RUNTIME_DIR", v) },
                None => unsafe { env::remove_var("BROWSER4_RUNTIME_DIR") },
            }
            match &self.prev_state {
                Some(v) => unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", v) },
                None => unsafe { env::remove_var("BROWSER4_CLI_STATE_DIR") },
            }
        }
    }

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
            version: Some("local".to_string()),
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
        let _env_guard = lock_env_mutex(); // serialize ROOT_SEARCH_START_DIR_ENV manipulation
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
    fn test_collect_jvm_opts_injects_agent_workspace_from_repo_root() {
        let _env_guard = lock_env_mutex(); // serialize BROWSER4_SERVER_OPTS / ROOT_SEARCH_START_DIR_ENV manipulation
        let tmp = test_temp_dir();
        let root = create_browser4_root(&tmp);

        // Isolate the env vars this function reads so parallel tests cannot
        // pollute the result.
        let prev_server_opts = env::var(BROWSER4_SERVER_OPTS_ENV).ok();
        let prev_search_start = env::var(ROOT_SEARCH_START_DIR_ENV).ok();
        unsafe {
            env::set_var(ROOT_SEARCH_START_DIR_ENV, root.join("cli").join("browser4-cli").as_os_str());
            env::remove_var(BROWSER4_SERVER_OPTS_ENV);
        }
        let (jvm_opts, _) = collect_jvm_opts_and_program_args();
        unsafe {
            match prev_server_opts {
                Some(v) => env::set_var(BROWSER4_SERVER_OPTS_ENV, v),
                None => env::remove_var(BROWSER4_SERVER_OPTS_ENV),
            }
            match prev_search_start {
                Some(v) => env::set_var(ROOT_SEARCH_START_DIR_ENV, v),
                None => env::remove_var(ROOT_SEARCH_START_DIR_ENV),
            }
        }

        // P1.4: the server must be pinned to the repo root the CLI was invoked
        // from, so its coding workspace cannot drift onto a sibling worktree.
        let expected = format!(
            "-Dbrowser4.agent.workspace={}",
            normalize_jvm_windows_path_text(&root.to_string_lossy())
        );
        assert!(
            jvm_opts.iter().any(|o| *o == expected),
            "expected {expected} in jvm opts: {jvm_opts:?}"
        );
    }

    #[test]
    fn test_user_server_opts_override_injected_agent_workspace() {
        let _env_guard = lock_env_mutex(); // serialize BROWSER4_SERVER_OPTS / ROOT_SEARCH_START_DIR_ENV manipulation
        let tmp = test_temp_dir();
        let root = create_browser4_root(&tmp);

        let prev_server_opts = env::var(BROWSER4_SERVER_OPTS_ENV).ok();
        let prev_search_start = env::var(ROOT_SEARCH_START_DIR_ENV).ok();
        unsafe {
            env::set_var(ROOT_SEARCH_START_DIR_ENV, root.join("cli").join("browser4-cli").as_os_str());
            env::set_var(BROWSER4_SERVER_OPTS_ENV, "-Dbrowser4.agent.workspace=/custom/workspace");
        }
        let (jvm_opts, _) = collect_jvm_opts_and_program_args();
        unsafe {
            match prev_server_opts {
                Some(v) => env::set_var(BROWSER4_SERVER_OPTS_ENV, v),
                None => env::remove_var(BROWSER4_SERVER_OPTS_ENV),
            }
            match prev_search_start {
                Some(v) => env::set_var(ROOT_SEARCH_START_DIR_ENV, v),
                None => env::remove_var(ROOT_SEARCH_START_DIR_ENV),
            }
        }

        // The injected value comes first, the user's value last — the JVM uses
        // the last -D for a given key, so the user override wins.
        let injected = jvm_opts
            .iter()
            .position(|o| o == "-Dbrowser4.agent.workspace=/custom/workspace")
            .expect("user -Dbrowser4.agent.workspace must be present");
        let auto = jvm_opts
            .iter()
            .position(|o| o.starts_with("-Dbrowser4.agent.workspace="))
            .expect("injected -Dbrowser4.agent.workspace must be present");
        assert!(auto < injected, "injected value must precede the user override: {jvm_opts:?}");
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
    fn test_canonical_runtime_version_normalizes_tags() {
        assert_eq!(canonical_runtime_version("v4.13.5"), "4.13.5");
        assert_eq!(canonical_runtime_version("4.13.5"), "4.13.5");
        assert_eq!(canonical_runtime_version("local"), "local");
        assert_eq!(canonical_runtime_version("LOCAL"), "local");
        assert_eq!(canonical_runtime_version("4.13.6-SNAPSHOT"), "local");
        assert_eq!(canonical_runtime_version("4.13.7-rc.1"), "local");
        assert_eq!(canonical_runtime_version("4.12.0-rc.3-SNAPSHOT"), "local");
        assert_eq!(canonical_runtime_version(""), "local");
        assert_eq!(canonical_runtime_version("  v4.13.5  "), "4.13.5");
    }

    #[test]
    fn test_server_version_mismatch_detects_stale_backend() {
        // The trap that started this: CLI prefers the source build while an
        // installed release is still running.
        assert!(server_version_mismatch("v4.13.5", "local"));
        // Release vs release: exact version matters.
        assert!(server_version_mismatch("v4.13.5", "v4.13.6"));
        assert!(!server_version_mismatch("v4.13.5", "4.13.5"));
        // Any source-built marker (SNAPSHOT, rc, "local") compares equal to "local".
        assert!(!server_version_mismatch("4.13.6-SNAPSHOT", "local"));
        assert!(!server_version_mismatch("local", "4.14.0-SNAPSHOT"));
        assert!(!server_version_mismatch("local", "4.13.7-rc.1"));
        assert!(!server_version_mismatch("4.13.7-rc.1", "local"));
        // Same version is never a mismatch.
        assert!(!server_version_mismatch("v4.13.5", "v4.13.5"));
    }

    #[test]
    fn test_expected_runtime_tag_prefers_local_bundle_in_checkout() {
        // Mirrors expected_runtime_tag(): inside a checkout with a bundle
        // module the expected tag is "local", otherwise the installed tag.
        if std::env::var("BROWSER4_CLI_FORCE_REMOTE_BUNDLE").as_deref() == Ok("1") {
            assert_eq!(expected_runtime_tag(), read_current_tag());
            return;
        }
        let root = find_browser4_root();
        if let Some(root) = &root {
            if root.join("browser4-apps").join("browser4-bundle").is_dir() {
                assert_eq!(expected_runtime_tag().as_deref(), Some("local"));
                return;
            }
        }
        assert_eq!(expected_runtime_tag(), read_current_tag());
    }

    #[test]
    fn test_launch_ready_timeout_uses_jar_timeout() {
        let spec = sample_launch_spec();
        assert_eq!(launch_ready_timeout(&spec), JAR_SERVER_READY_TIMEOUT);
    }

    #[test]
    fn test_enumerate_classpath_sorts_and_ignores_non_jars() {
        let tmp = test_temp_dir();
        let lib = tmp.path().join("lib");
        create_dir_all(&lib).unwrap();
        write(lib.join("z.jar"), "z").unwrap();
        write(lib.join("a.jar"), "a").unwrap();
        write(lib.join("m.jar"), "m").unwrap();
        write(lib.join("readme.txt"), "not a jar").unwrap();

        let cp = enumerate_classpath(&lib).unwrap();
        let sep = if cfg!(windows) { ";" } else { ":" };
        let names: Vec<&str> = cp
            .split(sep)
            .map(|p| Path::new(p).file_name().unwrap().to_str().unwrap())
            .collect();
        assert_eq!(names, vec!["a.jar", "m.jar", "z.jar"]);
    }

    #[test]
    fn test_enumerate_classpath_empty_dir_errors() {
        let tmp = test_temp_dir();
        let lib = tmp.path().join("lib");
        create_dir_all(&lib).unwrap();
        assert!(enumerate_classpath(&lib).is_err());
    }

    #[test]
    fn test_aot_cache_invalidation_key_stable_and_sensitive() {
        let tmp = test_temp_dir();
        let lib = tmp.path().join("lib");
        create_dir_all(&lib).unwrap();
        write(lib.join("a.jar"), "content-a").unwrap();
        write(lib.join("b.jar"), "content-b").unwrap();

        let runtime = InstalledBrowser4Runtime {
            tag: "v4.13.4".to_string(),
            asset_name: "asset".to_string(),
            download_url: "url".to_string(),
            install_dir: tmp.path().to_path_buf(),
            lib_dir: lib.clone(),
            jar_path: lib.join("a.jar"),
            java_path: tmp.path().join("runtime/bin/java"),
            reused_existing: false,
        };
        let opts = vec!["-XX:TieredStopAtLevel=1".to_string()];

        let k1 = aot_cache_invalidation_key(&runtime, &opts).unwrap();
        let k2 = aot_cache_invalidation_key(&runtime, &opts).unwrap();
        assert_eq!(k1, k2, "same inputs must produce the same key");

        write(lib.join("c.jar"), "content-c").unwrap();
        let k3 = aot_cache_invalidation_key(&runtime, &opts).unwrap();
        assert_ne!(k1, k3, "adding a jar must change the key");

        let mut runtime2 = runtime.clone();
        runtime2.tag = "v4.14.0".to_string();
        let k4 = aot_cache_invalidation_key(&runtime2, &opts).unwrap();
        assert_ne!(k1, k4, "a version bump must change the key");
    }

    #[test]
    fn test_aot_cache_disabled_flag() {
        let _guard = lock_env_mutex();
        let saved = env::var(DISABLE_AOT_CACHE_ENV).ok();
        for value in ["1", "true", "yes", "on", "TRUE", "On"] {
            unsafe { env::set_var(DISABLE_AOT_CACHE_ENV, value) };
            assert!(aot_cache_disabled(), "flag value {value:?} should disable AOT cache");
        }
        for value in ["", "0", "false", "off", "no"] {
            unsafe { env::set_var(DISABLE_AOT_CACHE_ENV, value) };
            assert!(!aot_cache_disabled(), "flag value {value:?} should not disable AOT cache");
        }
        match saved {
            Some(v) => unsafe { env::set_var(DISABLE_AOT_CACHE_ENV, v) },
            None => unsafe { env::remove_var(DISABLE_AOT_CACHE_ENV) },
        }
    }

    #[test]
    fn test_aot_cache_dir_lives_under_state_dir() {
        let _guard = lock_env_mutex();
        let tmp = test_temp_dir();
        let env_guard = TestEnvGuard::lock(tmp.path());

        // The cache must live under the CLI state dir (preserved across
        // uninstall/reinstall), NOT under the runtime data dir.  Compare by
        // components — on Windows the canonicalized state dir carries a
        // `\\?\` verbatim prefix that would break a raw string comparison.
        let cache_dir = aot_cache_dir();
        assert_eq!(
            cache_dir.file_name().and_then(|n| n.to_str()),
            Some("aot-cache")
        );
        assert_eq!(
            cache_dir
                .parent()
                .and_then(|p| p.file_name())
                .and_then(|n| n.to_str()),
            Some("state"),
            "aot cache must live under the CLI state dir"
        );

        drop(env_guard);
    }

    #[test]
    fn test_aot_training_marker_blocks_fresh_and_reclaims_stale() {
        let _guard = lock_env_mutex();
        let tmp = test_temp_dir();
        let env_guard = TestEnvGuard::lock(tmp.path());

        // First acquisition wins.
        assert!(acquire_aot_training_marker());
        // A fresh marker blocks a concurrent training run.
        assert!(!acquire_aot_training_marker());

        // Age the marker past the staleness threshold; it is then reclaimed.
        // Open with write access: on Windows a read-only handle lacks the
        // FILE_WRITE_ATTRIBUTES right that `set_modified` needs.
        let marker = aot_training_marker_file();
        let file = fs::OpenOptions::new().write(true).open(&marker).unwrap();
        let old = std::time::SystemTime::now()
            - (AOT_TRAINING_MARKER_STALE_AFTER + Duration::from_secs(60));
        file.set_modified(old).unwrap();
        drop(file);
        assert!(
            acquire_aot_training_marker(),
            "stale marker should be reclaimed"
        );

        release_aot_training_marker();
        assert!(!marker.exists(), "marker should be removed by release");

        drop(env_guard);
    }

    #[test]
    fn test_aot_training_marker_key_roundtrip_exact() {
        let _guard = lock_env_mutex();
        let tmp = test_temp_dir();
        let env_guard = TestEnvGuard::lock(tmp.path());

        // The marker content must round-trip the invalidation key exactly —
        // a trailing newline would make the equality check in
        // `ensure_aot_cache_trained` fail and the freshly trained cache would
        // be deleted as "stale" on the very next launch.
        assert!(acquire_aot_training_marker());
        let key = "fcc04fd6ad65f6f5452bcbc6271177cc462c4ee6c31887e12d553e448e2c7e26";
        write_aot_training_marker_key(key);
        assert_eq!(read_aot_training_marker_key(), key, "marker key must round-trip exactly");
        let raw = fs::read_to_string(aot_training_marker_file()).unwrap();
        assert!(!raw.ends_with('\n'), "marker content must not carry a trailing newline");

        drop(env_guard);
    }

    #[test]
    fn test_aot_training_process_alive_detects_dead_process() {
        // Our own PID is alive.
        assert!(aot_training_process_alive(std::process::id()));
        // A PID that cannot exist is dead.
        assert!(!aot_training_process_alive(u32::MAX));
        assert!(!aot_training_process_alive(4_000_000_000 - 1));
    }

    #[test]
    fn test_aot_training_pid_file_roundtrip() {
        let _guard = lock_env_mutex();
        let tmp = test_temp_dir();
        let env_guard = TestEnvGuard::lock(tmp.path());

        assert_eq!(read_aot_training_pid(), None, "no pid file yet");
        write_aot_training_pid(12345);
        assert_eq!(read_aot_training_pid(), Some(12345));

        drop(env_guard);
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
    fn test_mirror_download_url_oss_latest_path() {
        let mirror = DownloadMirror {
            name: "aliyun-oss".to_string(),
            base_url: "https://browser4.oss-cn-beijing.aliyuncs.com/releases".to_string(),
            supports_latest_resolution: true,
            latest_path: Some("download/latest".to_string()),
        };
        // Without tag → uses mirror's latest_path
        let url = mirror_download_url(&mirror, None, "browser4-cli-win32-x64.exe");
        assert_eq!(
            url,
            "https://browser4.oss-cn-beijing.aliyuncs.com/releases/download/latest/browser4-cli-win32-x64.exe"
        );
        // With tag → still uses standard /download/<tag>/ pattern
        let url = mirror_download_url(&mirror, Some("v4.11.13"), "browser4-cli-win32-x64.exe");
        assert_eq!(
            url,
            "https://browser4.oss-cn-beijing.aliyuncs.com/releases/download/v4.11.13/browser4-cli-win32-x64.exe"
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

    // --- Helpers for locale-env tests ---

    /// Snapshot of the five environment variables that `is_china_locale()` reads.
    type LocaleEnv = (
        Option<String>, // LC_ALL
        Option<String>, // LANG
        Option<String>, // LC_CTYPE
        Option<String>, // LC_MESSAGES
        Option<String>, // TZ
    );

    fn isolate_locale_env() -> LocaleEnv {
        let saved = (
            env::var("LC_ALL").ok(),
            env::var("LANG").ok(),
            env::var("LC_CTYPE").ok(),
            env::var("LC_MESSAGES").ok(),
            env::var("TZ").ok(),
        );
        unsafe {
            env::remove_var("LC_ALL");
            env::remove_var("LANG");
            env::remove_var("LC_CTYPE");
            env::remove_var("LC_MESSAGES");
            env::set_var("TZ", "UTC");
        }
        saved
    }

    fn restore_locale_env(saved: LocaleEnv) {
        let (lc_all, lang, lc_ctype, lc_messages, tz) = saved;
        for (var, val) in &[
            ("LC_ALL", lc_all),
            ("LANG", lang),
            ("LC_CTYPE", lc_ctype),
            ("LC_MESSAGES", lc_messages),
            ("TZ", tz),
        ] {
            match val {
                Some(v) => unsafe { env::set_var(var, v) },
                None => unsafe { env::remove_var(var) },
            }
        }
    }

    // --- Locale detection tests ---

    #[test]
    fn test_is_china_locale_zh_cn_lang() {
        let _guard = lock_env_mutex();
        let saved = isolate_locale_env();
        unsafe { env::set_var("LANG", "zh_CN.UTF-8") };
        let result = is_china_locale();
        restore_locale_env(saved);
        assert!(result);
    }

    #[test]
    fn test_is_china_locale_zh_cn_dash() {
        let _guard = lock_env_mutex();
        let saved = isolate_locale_env();
        unsafe { env::set_var("LANG", "zh-CN.UTF-8") };
        let result = is_china_locale();
        restore_locale_env(saved);
        assert!(result);
    }

    #[test]
    fn test_is_china_locale_lc_all_overrides_lang() {
        let _guard = lock_env_mutex();
        let saved = isolate_locale_env();
        unsafe { env::set_var("LC_ALL", "zh_CN.UTF-8") };
        unsafe { env::set_var("LANG", "en_US.UTF-8") };
        let result = is_china_locale();
        restore_locale_env(saved);
        assert!(result);
    }

    #[test]
    fn test_is_china_locale_asia_shanghai_tz() {
        let _guard = lock_env_mutex();
        let saved = isolate_locale_env();
        unsafe { env::set_var("TZ", "Asia/Shanghai") };
        let result = is_china_locale();
        restore_locale_env(saved);
        assert!(result);
    }

    #[test]
    fn test_is_china_locale_false_for_en_us() {
        let _guard = lock_env_mutex();
        let saved = isolate_locale_env();
        unsafe { env::set_var("LANG", "en_US.UTF-8") };
        let result = is_china_locale();
        restore_locale_env(saved);
        assert!(!result);
    }

    #[test]
    fn test_is_china_locale_false_for_empty() {
        let _guard = lock_env_mutex();
        let saved = isolate_locale_env();
        let result = is_china_locale();
        restore_locale_env(saved);
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
        // Order depends on locale detection; verify the set is correct.
        let names: Vec<&str> = mirrors.iter().map(|m| m.name.as_str()).collect();
        assert!(names.contains(&"github"));
        assert!(names.contains(&"aliyun-oss"));
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
        let _guard = lock_env_mutex();
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

        assert_eq!(
            progress,
            "TCP port not open yet, JVM may still be loading..."
        );
    }

    #[test]
    fn test_format_server_wait_progress_starting_includes_status() {
        let progress =
            format_server_wait_progress(&ServerState::Starting("{\"status\":\"STARTING\"}".into()));

        assert_eq!(
            progress,
            "Spring Boot is UP, waiting for MCP tools ({\"status\":\"STARTING\"})"
        );
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
    fn test_format_server_startup_failure_message_suggests_writable_dir_on_permission_error() {
        let tmp = test_temp_dir();
        let log_path = tmp.path().join("startup.log");
        // GBK-encoded `拒绝访问` after the ASCII exception/class name, matching
        // what a zh-CN Windows JVM writes when it cannot create logs/*.log.
        write(
            &log_path,
            b"java.io.FileNotFoundException: logs\\pulsar.log (\xBE\xDC\xBE\xF8\xB7\xC3\xCE\xCA)\n",
        )
        .unwrap();

        let message = format_server_startup_failure_message(
            "http://127.0.0.1:8182",
            Some("Browser4 did not become MCP-ready before the startup timeout elapsed."),
            "Last readiness probe result: connection refused",
            Some(Duration::from_secs(60)),
            Some(&log_path),
        );

        assert!(message.contains("BROWSER4_RUNTIME_DIR"));
        assert!(message.contains("unwritable logs/runtime directory"));
    }

    #[test]
    fn test_log_mentions_fatal_startup_error_detects_permission_denied() {
        // `拒绝访问` is GBK-encoded non-UTF-8; read as a lossy String the same
        // way read_startup_log_tail does, then the ASCII tokens must match.
        let bytes = b"java.io.FileNotFoundException: logs\\pulsar.dc.log (\xBE\xDC\xBE\xF8\xB7\xC3\xCE\xCA)\n\tat ch.qos.logback.core.FileAppender.openFile";
        let log = String::from_utf8_lossy(bytes);
        assert!(log_mentions_fatal_startup_error(&log));
    }

    #[test]
    fn test_log_mentions_fatal_startup_error_detects_spring_boot_failure() {
        let log = "***************************\nAPPLICATION FAILED TO START\n***************************\nDescription:\n\nWeb server failed to start. Port 8182 was already in use.";
        assert!(log_mentions_fatal_startup_error(log));
    }

    #[test]
    fn test_log_mentions_fatal_startup_error_ignores_transient_progress() {
        let log = "2026-08-15 03:22:51 [main] INFO  o.s.b.StartupInfoLogger - Starting Application v4.13.4-SNAPSHOT using Java 17";
        assert!(!log_mentions_fatal_startup_error(log));
    }

    #[test]
    fn test_log_mentions_fatal_startup_error_ignores_port_conflict_without_web_server() {
        // "already in use" alone must not trip the detector — only the
        // Spring Boot "Web server failed to start" pair is a real port conflict.
        let log = "some other component reports: address already in use";
        assert!(!log_mentions_fatal_startup_error(log));
    }

    #[test]
    fn test_format_server_startup_failure_message_no_writable_hint_without_permission_error() {
        let tmp = test_temp_dir();
        let log_path = tmp.path().join("startup.log");
        write(&log_path, "some unrelated startup failure\n").unwrap();

        let message = format_server_startup_failure_message(
            "http://127.0.0.1:8182",
            Some("Browser4 did not become MCP-ready before the startup timeout elapsed."),
            "Last readiness probe result: connection refused",
            Some(Duration::from_secs(60)),
            Some(&log_path),
        );

        assert!(!message.contains("BROWSER4_RUNTIME_DIR"));
    }

    #[test]
    fn test_read_startup_log_tail_decodes_non_utf8() {
        let tmp = test_temp_dir();
        let log_path = tmp.path().join("startup.log");
        write(
            &log_path,
            b"java.io.FileNotFoundException: logs\\pulsar.log (\xBE\xDC\xBE\xF8)\n",
        )
        .unwrap();

        let tail = read_startup_log_tail(&log_path);

        assert!(!tail.contains("failed to read startup log"));
        assert!(tail.contains("FileNotFoundException"));
        assert!(tail.contains("logs"));
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
        // "download" is the asset filename, not a version tag.  The validator
        // rejects non-version-tag strings, returning None so callers fall
        // through to metadata-based resolution.
        let tag = parse_release_tag_from_url(
            "https://github.com/platonai/Browser4/releases/latest/download/browser4-runtime.zip",
        );
        assert_eq!(tag, None);
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
    // is_valid_version_tag
    // -------------------------------------------------------------------

    #[test]
    fn test_is_valid_version_tag_accepts_stable_releases() {
        assert!(is_valid_version_tag("v4.11.2"));
        assert!(is_valid_version_tag("v4.11.9"));
        assert!(is_valid_version_tag("v0.1.0"));
        assert!(is_valid_version_tag("v100.200.300"));
    }

    #[test]
    fn test_is_valid_version_tag_accepts_prerelease() {
        assert!(is_valid_version_tag("v4.11.9-rc.1"));
        assert!(is_valid_version_tag("v4.11.9-alpha.2"));
        assert!(is_valid_version_tag("v4.11.9-beta.3"));
    }

    #[test]
    fn test_is_valid_version_tag_rejects_non_version_strings() {
        assert!(!is_valid_version_tag("latest"));
        assert!(!is_valid_version_tag("browser4-runtime.zip"));
        assert!(!is_valid_version_tag("browser4-linux-x64.tar.gz"));
        assert!(!is_valid_version_tag(""));
        assert!(!is_valid_version_tag("v"));
        assert!(!is_valid_version_tag("v."));
        assert!(!is_valid_version_tag(".v4.11"));
        assert!(!is_valid_version_tag("4.11.2")); // missing 'v' prefix
    }

    // -------------------------------------------------------------------
    // parse_github_owner_repo
    // -------------------------------------------------------------------

    #[test]
    fn test_parse_github_owner_repo_extracts_owner_and_repo() {
        let mirror = DownloadMirror {
            name: "github".to_string(),
            base_url: "https://github.com/platonai/Browser4/releases".to_string(),
            supports_latest_resolution: true,
            latest_path: None,
        };
        let (owner, repo) = parse_github_owner_repo(&mirror).unwrap();
        assert_eq!(owner, "platonai");
        assert_eq!(repo, "Browser4");
    }

    #[test]
    fn test_parse_github_owner_repo_rejects_non_github_mirrors() {
        let mirror = DownloadMirror {
            name: "aliyun-oss".to_string(),
            base_url: "https://browser4.oss-cn-beijing.aliyuncs.com/releases".to_string(),
            supports_latest_resolution: true,
            latest_path: None,
        };
        assert!(parse_github_owner_repo(&mirror).is_none());
    }

    #[test]
    fn test_parse_github_owner_repo_rejects_non_github_host() {
        let mirror = DownloadMirror {
            name: "custom".to_string(),
            base_url: "https://gitlab.example.com/owner/repo/releases".to_string(),
            supports_latest_resolution: true,
            latest_path: None,
        };
        assert!(parse_github_owner_repo(&mirror).is_none());
    }

    // -------------------------------------------------------------------
    // is_rc_tag
    // -------------------------------------------------------------------

    #[test]
    fn test_is_rc_tag_accepts_rc_labels() {
        assert!(is_rc_tag("v4.14.0-rc.1"));
        assert!(is_rc_tag("v4.14.0-rc"));
        assert!(is_rc_tag("v4.14.0-RC.2"));
        assert!(is_rc_tag("v4.14.0-rc.10"));
    }

    #[test]
    fn test_is_rc_tag_rejects_non_rc_labels() {
        assert!(!is_rc_tag("v4.14.0"));
        assert!(!is_rc_tag("v4.14.0-ci.3"));
        assert!(!is_rc_tag("v4.14.0-alpha.1"));
        assert!(!is_rc_tag("v4.14.0-beta.2"));
        assert!(!is_rc_tag("v4.14.0-dry_run.1"));
        assert!(!is_rc_tag("not-a-tag"));
        assert!(!is_rc_tag(""));
    }

    // -------------------------------------------------------------------
    // compare_base_versions / pick_newest_tag_newer_than
    // -------------------------------------------------------------------

    #[test]
    fn test_compare_base_versions_ignores_prerelease_suffix() {
        use std::cmp::Ordering;
        assert_eq!(
            compare_base_versions("v4.14.0-rc.1", "v4.13.11"),
            Ordering::Greater
        );
        assert_eq!(compare_base_versions("v4.14.0-rc.1", "v4.14.0"), Ordering::Equal);
        assert_eq!(compare_base_versions("v4.13.11-rc.9", "v4.13.11"), Ordering::Equal);
        assert_eq!(
            compare_base_versions("v4.13.11-rc.1", "v4.14.0-rc.2"),
            Ordering::Less
        );
    }

    #[test]
    fn test_pick_newest_tag_newer_than_filters_and_orders() {
        let tags = vec![
            "v4.13.11-rc.5".to_string(),
            "v4.14.0-rc.1".to_string(),
            "v4.14.0-rc.2".to_string(),
            "v4.12.0-rc.3".to_string(),
        ];
        assert_eq!(
            pick_newest_tag_newer_than(&tags, "v4.13.11").as_deref(),
            Some("v4.14.0-rc.2")
        );
    }

    #[test]
    fn test_pick_newest_tag_newer_than_ignores_same_base_version() {
        // A release candidate of the *current* stable version is not newer.
        let tags = vec!["v4.13.11-rc.1".to_string(), "v4.13.11-rc.2".to_string()];
        assert_eq!(pick_newest_tag_newer_than(&tags, "v4.13.11"), None);
    }

    #[test]
    fn test_pick_newest_tag_newer_than_none_when_empty_or_older() {
        assert_eq!(pick_newest_tag_newer_than(&[], "v4.13.11"), None);
        let tags = vec!["v4.12.0-rc.1".to_string()];
        assert_eq!(pick_newest_tag_newer_than(&tags, "v4.13.11"), None);
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
        set_test_file_executable(&runtime_bin.join(browser4_java_executable_name()));
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
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let tag = "v4.9.3";
        let _env = TestEnvGuard::lock(&tmp.path());
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
    }

    #[test]
    fn test_metadata_missing_file_returns_none() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());
        // No runtime installed at all → metadata is None.
        let read = read_installed_browser4_runtime_metadata();
        assert!(read.is_none());
    }

    #[test]
    fn test_metadata_corrupted_json_returns_none() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let tag = "v4.9.3";
        let _env = TestEnvGuard::lock(&tmp.path());
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
    }

    // -------------------------------------------------------------------
    // resolve_base_url config fallback tests
    // -------------------------------------------------------------------

    #[test]
    fn test_resolve_base_url_falls_back_to_config_server() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        // No persisted state, but config has `server` set.
        fs::write(
            tmp.path().join("state").join("config.json"),
            r#"{"server":"http://config:9000"}"#,
        )
        .unwrap();

        assert_eq!(resolve_base_url(None, None), "http://config:9000");
    }

    #[test]
    fn test_resolve_base_url_config_server_beats_persisted_state() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        fs::write(
            tmp.path().join("state").join("config.json"),
            r#"{"server":"http://config:9000"}"#,
        )
        .unwrap();
        fs::write(
            tmp.path().join("state").join("cli-state.json"),
            r#"{"baseUrl":"http://state:9002"}"#,
        )
        .unwrap();

        assert_eq!(resolve_base_url(None, None), "http://config:9000");
    }

    #[test]
    fn test_resolve_base_url_override_beats_config_server() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        fs::write(
            tmp.path().join("state").join("config.json"),
            r#"{"server":"http://config:9000"}"#,
        )
        .unwrap();

        assert_eq!(
            resolve_base_url(Some("http://flag:9001"), None),
            "http://flag:9001"
        );
    }

    #[test]
    fn test_resolve_base_url_uses_state_when_no_config() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        fs::write(
            tmp.path().join("state").join("cli-state.json"),
            r#"{"baseUrl":"http://state:9002"}"#,
        )
        .unwrap();

        assert_eq!(resolve_base_url(None, None), "http://state:9002");
    }

    // -------------------------------------------------------------------
    // Helpers for new-layout tests
    // -------------------------------------------------------------------

    /// On Unix, mark a file as executable so that `install_dir_contains_runtime`
    /// doesn't reject it for missing the execute bit (it checks `mode & 0o111`).
    /// On other platforms this is a no-op.
    fn set_test_file_executable(path: &Path) {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            if let Ok(meta) = fs::metadata(path) {
                let mut perms = meta.permissions();
                perms.set_mode(0o755);
                let _ = fs::set_permissions(path, perms);
            }
        }
        #[cfg(not(unix))]
        {
            let _ = path;
        }
    }

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
        let java = rt_bin.join(browser4_java_executable_name());
        fs::write(&java, "fake-java").unwrap();
        set_test_file_executable(&java);
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

    // -------------------------------------------------------------------
    // New layout: versioned install dirs, current.tag, migration
    // -------------------------------------------------------------------

    #[test]
    fn test_current_tag_read_write_round_trip() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        // No tag yet → None (and no legacy install to migrate).
        assert!(read_current_tag().is_none());

        // Write a tag with a valid versioned install → should read back.
        setup_valid_versioned_runtime(&tmp.path().join("runtime-data"), "v4.11.0");
        assert_eq!(read_current_tag().as_deref(), Some("v4.11.0"));

        // Overwrite with a different tag.
        setup_valid_versioned_runtime(&tmp.path().join("runtime-data"), "v4.12.0");
        assert_eq!(read_current_tag().as_deref(), Some("v4.12.0"));
    }

    #[test]
    fn test_versioned_install_dir_path() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        let dir = versioned_install_dir("v4.10.0");
        assert!(dir.ends_with(Path::new("runtime").join("v4.10.0")));

        // Write a valid runtime and verify browser4_install_dir resolves.
        setup_valid_versioned_runtime(&tmp.path().join("runtime-data"), "v4.10.0");
        let resolved = resolve_current_install_dir().unwrap();
        // Both paths should point to the same versioned install (canonicalization
        // may add \\?\ prefix on Windows — compare file names instead).
        assert!(resolved.ends_with(Path::new("runtime").join("v4.10.0")));
        assert!(resolved.join("lib").join("browser4.jar").exists());
    }

    #[test]
    fn test_java_argfile_token_normalizes_windows_paths() {
        // Verbatim prefix is stripped, backslashes become forward slashes so
        // the java launcher's escape processing (`\t`, `\n`, ...) cannot
        // corrupt paths inside the argfile.
        assert_eq!(
            java_argfile_token(r"\\?\C:\Users\john\app\lib\a.jar"),
            "C:/Users/john/app/lib/a.jar"
        );
        assert_eq!(
            java_argfile_token(r"C:\Program Files\Browser4\lib\a.jar"),
            "\"C:/Program Files/Browser4/lib/a.jar\"" // whitespace → quoted
        );
        // The `-cp` classpath is ONE `;`-joined token whose every entry may
        // carry its own verbatim prefix (the runtime data dir is
        // canonicalized) — all of them must be stripped, not just a leading
        // one, otherwise the entries turn into unopenable `//?/` paths.
        assert_eq!(
            java_argfile_token(
                r"\\?\C:\app\lib\Bundle.jar;\\?\C:\app\lib\EvalEx-2.0.jar;\\?\C:\app\lib\kotlin.jar"
            ),
            "C:/app/lib/Bundle.jar;C:/app/lib/EvalEx-2.0.jar;C:/app/lib/kotlin.jar"
        );
        // AOT cache options embed the verbatim prefix after `=`.
        assert_eq!(
            java_argfile_token(r"-XX:AOTCacheOutput=\\?\C:\Users\runtime\aot\app.aot"),
            "-XX:AOTCacheOutput=C:/Users/runtime/aot/app.aot"
        );
        assert_eq!(
            java_argfile_token(r"-XX:AOTCache=\\?\C:\Users\runtime\aot\app.aot"),
            "-XX:AOTCache=C:/Users/runtime/aot/app.aot"
        );
        // UNC paths canonicalize to `\\?\UNC\server\share\...`.
        assert_eq!(
            java_argfile_token(r"\\?\UNC\server\share\app\lib\a.jar"),
            "//server/share/app/lib/a.jar"
        );
        // Non-path arguments pass through untouched.
        assert_eq!(
            java_argfile_token("--server.port=8182"),
            "--server.port=8182"
        );
        assert_eq!(
            java_argfile_token("-XX:TieredStopAtLevel=1"),
            "-XX:TieredStopAtLevel=1"
        );
    }

    #[test]
    fn test_estimated_windows_command_line_len() {
        let program = Path::new(r"C:\jdk\bin\java.exe");
        let args = vec!["-cp".to_string(), "a".repeat(100)];
        // program (19) + space overhead: "-cp" (3+3) + 100+3
        assert_eq!(
            estimated_windows_command_line_len(program, &args),
            19 + 6 + 103
        );
    }

    #[test]
    fn test_write_java_argfile_round_trip() {
        let file = write_java_argfile(&[
            "-cp".to_string(),
            r"C:\Users\john doe\lib\a.jar".to_string(),
            "ai.platon.pulsar.apps.Browser4BundleApplicationKt".to_string(),
        ])
        .unwrap();
        assert!(file.is_file());
        let contents = fs::read_to_string(&file).unwrap();
        let lines: Vec<&str> = contents.lines().collect();
        assert_eq!(lines[0], "-cp");
        assert_eq!(lines[1], "\"C:/Users/john doe/lib/a.jar\"");
        assert_eq!(
            lines[2],
            "ai.platon.pulsar.apps.Browser4BundleApplicationKt"
        );
        let dir = file.parent().unwrap();
        fs::remove_dir_all(dir).unwrap();
        assert!(!file.exists());
    }

    #[test]
    fn test_legacy_migration_detects_old_layout() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

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
        set_test_file_executable(&legacy_runtime_bin.join(browser4_java_executable_name()));
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
    }

    #[test]
    fn test_find_newest_versioned_install() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        // Set up multiple versioned installs without current.tag.
        for tag in &["v4.8.0", "v4.9.0", "v4.10.0"] {
            let dir = versioned_install_dir(tag);
            let lib = dir.join("lib");
            let rt_bin = dir.join("runtime").join("bin");
            fs::create_dir_all(&lib).unwrap();
            fs::create_dir_all(&rt_bin).unwrap();
            fs::write(lib.join("x.jar"), "jar").unwrap();
            fs::write(rt_bin.join(browser4_java_executable_name()), "java").unwrap();
            set_test_file_executable(&rt_bin.join(browser4_java_executable_name()));
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
    }

    // -------------------------------------------------------------------
    // Mirror preference cache tests
    // -------------------------------------------------------------------

    #[test]
    fn test_mirror_preference_round_trip() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

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
    }

    #[test]
    fn test_mirror_preference_expired() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());
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
        match prev_ttl {
            Some(v) => unsafe { env::set_var(MIRROR_PREFERENCE_TTL_ENV, v) },
            None => unsafe { env::remove_var(MIRROR_PREFERENCE_TTL_ENV) },
        }
    }

    #[test]
    fn test_mirror_preference_valid_under_ttl() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());
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
        match prev_ttl {
            Some(v) => unsafe { env::set_var(MIRROR_PREFERENCE_TTL_ENV, v) },
            None => unsafe { env::remove_var(MIRROR_PREFERENCE_TTL_ENV) },
        }
    }

    #[test]
    fn test_mirror_preference_ttl_env_override() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());
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

        match prev_ttl {
            Some(v) => unsafe { env::set_var(MIRROR_PREFERENCE_TTL_ENV, v) },
            None => unsafe { env::remove_var(MIRROR_PREFERENCE_TTL_ENV) },
        }
    }

    #[test]
    fn test_mirror_preference_invalid_when_mirror_missing() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

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
    }

    #[test]
    fn test_mirror_preference_corrupted() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        let path = mirror_preference_cache_path();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, "not valid json {{{").unwrap();

        let mirrors = builtin_mirrors();
        let loaded = load_mirror_preference(&mirrors);
        assert!(loaded.is_none(), "corrupted cache file should return None");

        delete_mirror_preference_cache();
    }

    #[test]
    fn test_delete_mirror_preference_cache_removes_file() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let _env = TestEnvGuard::lock(&tmp.path());

        let path = mirror_preference_cache_path();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, r#"{"selected_mirror":{"name":"x","base_url":"https://x.com"},"tested_at":"2026-01-01T00:00:00+00:00","download_speed_bps":1}"#).unwrap();
        assert!(path.exists());

        delete_mirror_preference_cache();
        assert!(!path.exists(), "cache file should be deleted");
    }

    // ------------------------------------------------------------------
    // Playwright browser search tests
    // ------------------------------------------------------------------

    /// Create a mock Playwright browser directory with a fake executable.
    /// Returns the expected binary path.
    fn create_mock_playwright_chromium(playwright_root: &Path) -> PathBuf {
        let version_dir = playwright_root.join("chromium-1150");
        let exe_path = if cfg!(target_os = "windows") {
            version_dir.join("chrome-win64").join("chrome.exe")
        } else if cfg!(target_os = "macos") {
            let mac_bundle = version_dir
                .join("chrome-mac")
                .join("Chromium.app")
                .join("Contents")
                .join("MacOS");
            create_dir_all(&mac_bundle).unwrap();
            mac_bundle.join("Chromium")
        } else {
            version_dir.join("chrome-linux").join("chrome")
        };
        if let Some(parent) = exe_path.parent() {
            create_dir_all(parent).unwrap();
        }
        write(&exe_path, "fake-binary").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = fs::metadata(&exe_path).unwrap().permissions();
            perms.set_mode(0o755);
            fs::set_permissions(&exe_path, perms).unwrap();
        }
        exe_path
    }

    /// Create a mock Playwright Edge directory with a fake executable.
    fn create_mock_playwright_edge(playwright_root: &Path) -> PathBuf {
        let version_dir = playwright_root.join("msedge-1150");
        let exe_path = if cfg!(target_os = "windows") {
            version_dir.join("chrome-win64").join("msedge.exe")
        } else if cfg!(target_os = "macos") {
            let mac_bundle = version_dir
                .join("chrome-mac")
                .join("Microsoft Edge.app")
                .join("Contents")
                .join("MacOS");
            create_dir_all(&mac_bundle).unwrap();
            mac_bundle.join("Microsoft Edge")
        } else {
            version_dir.join("chrome-linux").join("msedge")
        };
        if let Some(parent) = exe_path.parent() {
            create_dir_all(parent).unwrap();
        }
        write(&exe_path, "fake-binary").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = fs::metadata(&exe_path).unwrap().permissions();
            perms.set_mode(0o755);
            fs::set_permissions(&exe_path, perms).unwrap();
        }
        exe_path
    }

    /// Guard that sets `PLAYWRIGHT_BROWSERS_PATH` and restores on drop.
    struct PlaywrightPathGuard {
        prev: Option<String>,
    }

    impl PlaywrightPathGuard {
        fn lock(path: &Path) -> Self {
            let prev = env::var("PLAYWRIGHT_BROWSERS_PATH").ok();
            unsafe {
                env::set_var("PLAYWRIGHT_BROWSERS_PATH", path.as_os_str());
            }
            Self { prev }
        }
    }

    impl Drop for PlaywrightPathGuard {
        fn drop(&mut self) {
            match &self.prev {
                Some(v) => unsafe { env::set_var("PLAYWRIGHT_BROWSERS_PATH", v) },
                None => unsafe { env::remove_var("PLAYWRIGHT_BROWSERS_PATH") },
            }
        }
    }

    #[test]
    fn test_find_chrome_in_playwright_discovers_chromium_binary() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let playwright_root = tmp.path().join("ms-playwright");
        let expected = create_mock_playwright_chromium(&playwright_root);
        let _guard = PlaywrightPathGuard::lock(&playwright_root);

        let found = find_chrome_in_playwright();
        assert_eq!(found, Some(expected), "should discover Playwright Chromium");
    }

    #[test]
    fn test_find_edge_in_playwright_discovers_edge_binary() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let playwright_root = tmp.path().join("ms-playwright");
        let expected = create_mock_playwright_edge(&playwright_root);
        let _guard = PlaywrightPathGuard::lock(&playwright_root);

        let found = find_edge_in_playwright();
        assert_eq!(found, Some(expected), "should discover Playwright Edge");
    }

    #[test]
    fn test_find_chrome_in_playwright_returns_none_when_empty() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let playwright_root = tmp.path().join("ms-playwright-empty");
        create_dir_all(&playwright_root).unwrap();
        let _guard = PlaywrightPathGuard::lock(&playwright_root);

        let found = find_chrome_in_playwright();
        assert_eq!(found, None, "empty dir should return None");
    }

    #[test]
    fn test_find_chrome_in_playwright_prefers_newer_version() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let playwright_root = tmp.path().join("ms-playwright");

        // Create an older version first (lower build number).
        let older_dir = playwright_root.join("chromium-1000");
        let older_exe = if cfg!(target_os = "windows") {
            older_dir.join("chrome-win64").join("chrome.exe")
        } else if cfg!(target_os = "macos") {
            let mac_bundle = older_dir
                .join("chrome-mac")
                .join("Chromium.app")
                .join("Contents")
                .join("MacOS");
            create_dir_all(&mac_bundle).unwrap();
            mac_bundle.join("Chromium")
        } else {
            older_dir.join("chrome-linux").join("chrome")
        };
        if let Some(parent) = older_exe.parent() {
            create_dir_all(parent).unwrap();
        }
        write(&older_exe, "older").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = fs::metadata(&older_exe).unwrap().permissions();
            perms.set_mode(0o755);
            fs::set_permissions(&older_exe, perms).unwrap();
        }

        // Create a newer version (higher build number).
        let expected = create_mock_playwright_chromium(&playwright_root); // chromium-1150

        let _guard = PlaywrightPathGuard::lock(&playwright_root);

        let found = find_chrome_in_playwright();
        assert_eq!(
            found,
            Some(expected),
            "should prefer the newer version directory (1150 > 1000)"
        );
    }

    #[test]
    fn test_find_browser_in_playwright_respects_custom_env_var() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let custom_dir = tmp.path().join("custom-playwright-cache");
        let expected = create_mock_playwright_chromium(&custom_dir);
        let _guard = PlaywrightPathGuard::lock(&custom_dir);

        let found = find_chrome_in_playwright();
        assert_eq!(
            found,
            Some(expected),
            "should respect PLAYWRIGHT_BROWSERS_PATH env var"
        );
    }

    #[test]
    fn test_playwright_install_roots_uses_env_var_over_default() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let custom = tmp.path().join("pw-custom");
        let _guard = PlaywrightPathGuard::lock(&custom);

        let roots = playwright_install_roots();
        assert!(
            roots.contains(&custom),
            "roots should contain the custom path from PLAYWRIGHT_BROWSERS_PATH"
        );
    }

    #[test]
    fn test_find_chrome_executable_includes_playwright() {
        let _lock = lock_env_mutex();
        let tmp = test_temp_dir();
        let playwright_root = tmp.path().join("ms-playwright");
        let playwright_exe = create_mock_playwright_chromium(&playwright_root);
        let _guard = PlaywrightPathGuard::lock(&playwright_root);

        // find_chrome_executable() should discover a browser.  If a system
        // Chrome is already installed it may be found first (before the
        // Playwright fallback), which is fine — the point is that the
        // function succeeds.
        let found = find_chrome_executable();
        assert!(
            found.is_some(),
            "find_chrome_executable should find a browser (system or Playwright)"
        );

        // When no system Chrome exists, the Playwright binary should be found.
        // Verify the Playwright binary is discoverable on its own.
        let playwright_found = find_chrome_in_playwright();
        assert_eq!(
            playwright_found,
            Some(playwright_exe),
            "Playwright binary should be independently discoverable"
        );
    }
    // -----------------------------------------------------------------------
    // BrowserChannel tests
    // -----------------------------------------------------------------------

    #[test]
    fn browser_channel_from_str_all_variants() {
        // Chrome variants
        assert_eq!(
            BrowserChannel::from_str("chrome"),
            Some(BrowserChannel::Chrome)
        );
        assert_eq!(
            BrowserChannel::from_str("Chrome"),
            Some(BrowserChannel::Chrome)
        );
        assert_eq!(
            BrowserChannel::from_str("CHROME"),
            Some(BrowserChannel::Chrome)
        );
        assert_eq!(
            BrowserChannel::from_str("google-chrome"),
            Some(BrowserChannel::Chrome)
        );
        assert_eq!(
            BrowserChannel::from_str("google chrome"),
            Some(BrowserChannel::Chrome)
        );

        assert_eq!(
            BrowserChannel::from_str("chrome-beta"),
            Some(BrowserChannel::ChromeBeta)
        );
        assert_eq!(
            BrowserChannel::from_str("chrome-dev"),
            Some(BrowserChannel::ChromeDev)
        );
        assert_eq!(
            BrowserChannel::from_str("chrome-canary"),
            Some(BrowserChannel::ChromeCanary)
        );

        // Edge variants
        assert_eq!(
            BrowserChannel::from_str("msedge"),
            Some(BrowserChannel::MsEdge)
        );
        assert_eq!(
            BrowserChannel::from_str("edge"),
            Some(BrowserChannel::MsEdge)
        );
        assert_eq!(
            BrowserChannel::from_str("microsoft-edge"),
            Some(BrowserChannel::MsEdge)
        );
        assert_eq!(
            BrowserChannel::from_str("MsEdge"),
            Some(BrowserChannel::MsEdge)
        );

        assert_eq!(
            BrowserChannel::from_str("msedge-beta"),
            Some(BrowserChannel::MsEdgeBeta)
        );
        assert_eq!(
            BrowserChannel::from_str("msedge-dev"),
            Some(BrowserChannel::MsEdgeDev)
        );
        assert_eq!(
            BrowserChannel::from_str("msedge-canary"),
            Some(BrowserChannel::MsEdgeCanary)
        );
    }

    #[test]
    fn browser_channel_from_str_unknown() {
        assert_eq!(BrowserChannel::from_str("firefox"), None);
        assert_eq!(BrowserChannel::from_str("safari"), None);
        assert_eq!(BrowserChannel::from_str(""), None);
    }

    #[test]
    fn browser_channel_default_debug_port() {
        assert_eq!(BrowserChannel::Chrome.default_debug_port(), 9222);
        assert_eq!(BrowserChannel::ChromeBeta.default_debug_port(), 9223);
        assert_eq!(BrowserChannel::ChromeDev.default_debug_port(), 9224);
        assert_eq!(BrowserChannel::ChromeCanary.default_debug_port(), 9225);
        assert_eq!(BrowserChannel::MsEdge.default_debug_port(), 9222);
        assert_eq!(BrowserChannel::MsEdgeBeta.default_debug_port(), 9223);
        assert_eq!(BrowserChannel::MsEdgeDev.default_debug_port(), 9224);
        assert_eq!(BrowserChannel::MsEdgeCanary.default_debug_port(), 9225);
    }

    #[test]
    fn browser_channel_executable_name() {
        assert_eq!(BrowserChannel::Chrome.executable_name(), "chrome");
        assert_eq!(BrowserChannel::ChromeBeta.executable_name(), "chrome");
        assert_eq!(BrowserChannel::ChromeDev.executable_name(), "chrome");
        assert_eq!(BrowserChannel::ChromeCanary.executable_name(), "chrome");
        assert_eq!(BrowserChannel::MsEdge.executable_name(), "msedge");
        assert_eq!(BrowserChannel::MsEdgeBeta.executable_name(), "msedge");
        assert_eq!(BrowserChannel::MsEdgeDev.executable_name(), "msedge");
        assert_eq!(BrowserChannel::MsEdgeCanary.executable_name(), "msedge");
    }

    #[test]
    fn browser_channel_display() {
        assert_eq!(format!("{}", BrowserChannel::Chrome), "chrome");
        assert_eq!(format!("{}", BrowserChannel::ChromeCanary), "chrome-canary");
        assert_eq!(format!("{}", BrowserChannel::MsEdge), "msedge");
        assert_eq!(format!("{}", BrowserChannel::MsEdgeDev), "msedge-dev");
    }

    /// Integration test: probes the actual Chrome instance on port 9222.
    /// Requires Chrome running with --remote-debugging-port=9222.
    /// Skipped by default; run with `cargo test -- --ignored`.
    #[test]
    #[ignore = "requires Chrome with --remote-debugging-port=9222"]
    fn cdp_probe_live_chrome_port_9222() {
        assert!(
            probe_cdp_port(9222),
            "Chrome CDP endpoint not responding on port 9222. \
             Start Chrome with: chrome --remote-debugging-port=9222"
        );
    }

    /// Integration test: verifies that a port where nothing listens returns false.
    #[test]
    fn cdp_probe_unused_port_returns_false() {
        // Port 19999 is very unlikely to have anything listening
        assert!(!probe_cdp_port(19999));
    }

    // -------------------------------------------------------------------
    // BrowserChannel::from_str tests
    // -------------------------------------------------------------------

    #[test]
    fn channel_from_str_chrome_variants() {
        assert_eq!(
            BrowserChannel::from_str("chrome"),
            Some(BrowserChannel::Chrome)
        );
        assert_eq!(
            BrowserChannel::from_str("Chrome"),
            Some(BrowserChannel::Chrome)
        );
        assert_eq!(
            BrowserChannel::from_str("CHROME"),
            Some(BrowserChannel::Chrome)
        );
        assert_eq!(
            BrowserChannel::from_str("google-chrome"),
            Some(BrowserChannel::Chrome)
        );
        assert_eq!(
            BrowserChannel::from_str("google chrome"),
            Some(BrowserChannel::Chrome)
        );
    }

    #[test]
    fn channel_from_str_chrome_beta_variants() {
        assert_eq!(
            BrowserChannel::from_str("chrome-beta"),
            Some(BrowserChannel::ChromeBeta)
        );
        assert_eq!(
            BrowserChannel::from_str("google-chrome-beta"),
            Some(BrowserChannel::ChromeBeta)
        );
    }

    #[test]
    fn channel_from_str_chrome_dev_variants() {
        assert_eq!(
            BrowserChannel::from_str("chrome-dev"),
            Some(BrowserChannel::ChromeDev)
        );
        assert_eq!(
            BrowserChannel::from_str("google-chrome-dev"),
            Some(BrowserChannel::ChromeDev)
        );
    }

    #[test]
    fn channel_from_str_chrome_canary_variants() {
        assert_eq!(
            BrowserChannel::from_str("chrome-canary"),
            Some(BrowserChannel::ChromeCanary)
        );
        assert_eq!(
            BrowserChannel::from_str("google-chrome-canary"),
            Some(BrowserChannel::ChromeCanary)
        );
    }

    #[test]
    fn channel_from_str_edge_variants() {
        assert_eq!(
            BrowserChannel::from_str("msedge"),
            Some(BrowserChannel::MsEdge)
        );
        assert_eq!(
            BrowserChannel::from_str("edge"),
            Some(BrowserChannel::MsEdge)
        );
        assert_eq!(
            BrowserChannel::from_str("EDGE"),
            Some(BrowserChannel::MsEdge)
        );
        assert_eq!(
            BrowserChannel::from_str("microsoft-edge"),
            Some(BrowserChannel::MsEdge)
        );
        assert_eq!(
            BrowserChannel::from_str("microsoft edge"),
            Some(BrowserChannel::MsEdge)
        );
    }

    #[test]
    fn channel_from_str_edge_beta_variants() {
        assert_eq!(
            BrowserChannel::from_str("msedge-beta"),
            Some(BrowserChannel::MsEdgeBeta)
        );
        assert_eq!(
            BrowserChannel::from_str("edge-beta"),
            Some(BrowserChannel::MsEdgeBeta)
        );
        assert_eq!(
            BrowserChannel::from_str("microsoft-edge-beta"),
            Some(BrowserChannel::MsEdgeBeta)
        );
    }

    #[test]
    fn channel_from_str_edge_dev_variants() {
        assert_eq!(
            BrowserChannel::from_str("msedge-dev"),
            Some(BrowserChannel::MsEdgeDev)
        );
        assert_eq!(
            BrowserChannel::from_str("edge-dev"),
            Some(BrowserChannel::MsEdgeDev)
        );
        assert_eq!(
            BrowserChannel::from_str("microsoft-edge-dev"),
            Some(BrowserChannel::MsEdgeDev)
        );
    }

    #[test]
    fn channel_from_str_edge_canary_variants() {
        assert_eq!(
            BrowserChannel::from_str("msedge-canary"),
            Some(BrowserChannel::MsEdgeCanary)
        );
        assert_eq!(
            BrowserChannel::from_str("edge-canary"),
            Some(BrowserChannel::MsEdgeCanary)
        );
        assert_eq!(
            BrowserChannel::from_str("microsoft-edge-canary"),
            Some(BrowserChannel::MsEdgeCanary)
        );
    }

    #[test]
    fn channel_from_str_unknown_returns_none() {
        assert_eq!(BrowserChannel::from_str("firefox"), None);
        assert_eq!(BrowserChannel::from_str("safari"), None);
        assert_eq!(BrowserChannel::from_str(""), None);
        assert_eq!(BrowserChannel::from_str("chromium"), None);
    }

    #[test]
    fn channel_default_ports() {
        assert_eq!(BrowserChannel::Chrome.default_debug_port(), 9222);
        assert_eq!(BrowserChannel::ChromeBeta.default_debug_port(), 9223);
        assert_eq!(BrowserChannel::ChromeDev.default_debug_port(), 9224);
        assert_eq!(BrowserChannel::ChromeCanary.default_debug_port(), 9225);
        assert_eq!(BrowserChannel::MsEdge.default_debug_port(), 9222);
        assert_eq!(BrowserChannel::MsEdgeBeta.default_debug_port(), 9223);
        assert_eq!(BrowserChannel::MsEdgeDev.default_debug_port(), 9224);
        assert_eq!(BrowserChannel::MsEdgeCanary.default_debug_port(), 9225);
    }

    #[test]
    fn channel_executable_names() {
        assert_eq!(BrowserChannel::Chrome.executable_name(), "chrome");
        assert_eq!(BrowserChannel::ChromeCanary.executable_name(), "chrome");
        assert_eq!(BrowserChannel::MsEdge.executable_name(), "msedge");
        assert_eq!(BrowserChannel::MsEdgeDev.executable_name(), "msedge");
    }

    #[test]
    fn channel_display_format() {
        assert_eq!(BrowserChannel::Chrome.to_string(), "chrome");
        assert_eq!(BrowserChannel::ChromeBeta.to_string(), "chrome-beta");
        assert_eq!(BrowserChannel::MsEdge.to_string(), "msedge");
        assert_eq!(BrowserChannel::MsEdgeCanary.to_string(), "msedge-canary");
    }

    // -------------------------------------------------------------------
    // Plugins warm restart
    // -------------------------------------------------------------------

    /// Env guard that isolates the state dir and the plugin warm-restart env
    /// var for a single test, restoring the previous values on drop.
    struct WarmRestartEnvGuard {
        prev_disable: Option<String>,
        prev_state: Option<String>,
    }

    impl WarmRestartEnvGuard {
        fn lock(state_dir: &Path) -> Self {
            let prev_disable = env::var(DISABLE_PLUGIN_WARM_RESTART_ENV).ok();
            let prev_state = env::var("BROWSER4_CLI_STATE_DIR").ok();
            let _ = fs::create_dir_all(state_dir);
            unsafe {
                env::set_var("BROWSER4_CLI_STATE_DIR", state_dir.as_os_str());
                env::remove_var(DISABLE_PLUGIN_WARM_RESTART_ENV);
            }
            Self {
                prev_disable,
                prev_state,
            }
        }
    }

    impl Drop for WarmRestartEnvGuard {
        fn drop(&mut self) {
            match &self.prev_disable {
                Some(v) => unsafe { env::set_var(DISABLE_PLUGIN_WARM_RESTART_ENV, v) },
                None => unsafe { env::remove_var(DISABLE_PLUGIN_WARM_RESTART_ENV) },
            }
            match &self.prev_state {
                Some(v) => unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", v) },
                None => unsafe { env::remove_var("BROWSER4_CLI_STATE_DIR") },
            }
        }
    }

    fn write_test_jar(dir: &Path, name: &str, content: &[u8]) {
        create_dir_all(dir).unwrap();
        write(dir.join(name), content).unwrap();
    }

    #[test]
    fn plugins_fingerprint_stable_for_unchanged_jars() {
        let tmp = test_temp_dir();
        let plugins = tmp.path().join("plugins");
        write_test_jar(&plugins, "b-plugin.jar", b"bbb");
        write_test_jar(&plugins, "a-plugin.jar", b"aaa");

        // Order of directory iteration must not matter (sorted by file name).
        let first = compute_plugins_fingerprint(&plugins);
        let second = compute_plugins_fingerprint(&plugins);
        assert_eq!(first, second);
        assert_eq!(first.len(), 64, "sha256 hex digest expected");
    }

    #[test]
    fn plugins_fingerprint_missing_dir_equals_empty_dir() {
        let tmp = test_temp_dir();
        let empty = tmp.path().join("plugins");
        create_dir_all(&empty).unwrap();
        let missing = tmp.path().join("does-not-exist");

        assert_eq!(
            compute_plugins_fingerprint(&empty),
            compute_plugins_fingerprint(&missing)
        );
    }

    #[test]
    fn plugins_fingerprint_changes_on_jar_added_removed_modified() {
        let tmp = test_temp_dir();
        let plugins = tmp.path().join("plugins");
        write_test_jar(&plugins, "a-plugin.jar", b"aaa");

        let base = compute_plugins_fingerprint(&plugins);

        // Added
        write_test_jar(&plugins, "b-plugin.jar", b"bbb");
        assert_ne!(compute_plugins_fingerprint(&plugins), base);

        // Removed (back to base set)
        let _ = fs::remove_file(plugins.join("b-plugin.jar"));
        assert_eq!(compute_plugins_fingerprint(&plugins), base);

        // Modified (different size; mtime granularity is not relied upon)
        write_test_jar(&plugins, "a-plugin.jar", b"aaaaaa");
        assert_ne!(compute_plugins_fingerprint(&plugins), base);

        // Non-jar files are ignored: adding notes.txt must not change the
        // fingerprint, so removing it again restores the previous value.
        let before_txt = compute_plugins_fingerprint(&plugins);
        write_test_jar(&plugins, "notes.txt", b"ignored");
        assert_eq!(compute_plugins_fingerprint(&plugins), before_txt);
        let _ = fs::remove_file(plugins.join("notes.txt"));
        assert_eq!(compute_plugins_fingerprint(&plugins), before_txt);
    }

    #[test]
    fn plugins_change_detection_lifecycle() {
        let tmp = test_temp_dir();
        let _guard = lock_env_mutex();
        let _env = WarmRestartEnvGuard::lock(&tmp.path().join("state"));

        let plugins = tmp.path().join("server").join("plugins");
        write_test_jar(&plugins, "a-plugin.jar", b"aaa");

        // No recorded entry yet: never restart (server may be external).
        assert!(!plugins_changed_since_server_start(8182));

        record_plugins_fingerprint(8182, &plugins);
        assert!(!plugins_changed_since_server_start(8182));

        // Other ports are unaffected.
        assert!(!plugins_changed_since_server_start(8183));

        // Installing a new plugin triggers a restart.
        write_test_jar(&plugins, "b-plugin.jar", b"bbb");
        assert!(plugins_changed_since_server_start(8182));

        // Opt-out env var suppresses the restart.
        unsafe { env::set_var(DISABLE_PLUGIN_WARM_RESTART_ENV, "1") };
        assert!(!plugins_changed_since_server_start(8182));
        unsafe { env::remove_var(DISABLE_PLUGIN_WARM_RESTART_ENV) };

        // Clearing the entry disables detection again (external server).
        clear_plugins_fingerprint(8182);
        assert!(!plugins_changed_since_server_start(8182));
    }

    #[test]
    fn plugins_fingerprint_store_round_trip_and_port_replace() {
        let tmp = test_temp_dir();
        let _guard = lock_env_mutex();
        let _env = WarmRestartEnvGuard::lock(&tmp.path().join("state"));

        let plugins = tmp.path().join("plugins");
        write_test_jar(&plugins, "a-plugin.jar", b"aaa");

        record_plugins_fingerprint(8182, &plugins);
        record_plugins_fingerprint(8183, &plugins);

        let store = load_plugins_fingerprint_store(&plugins_fingerprint_file());
        assert_eq!(store.entries.len(), 2);
        assert!(store.entries.iter().all(|e| !e.fingerprint.is_empty()));

        // Re-recording the same port replaces, not duplicates.
        record_plugins_fingerprint(8182, &plugins);
        let store = load_plugins_fingerprint_store(&plugins_fingerprint_file());
        assert_eq!(store.entries.len(), 2);
        assert_eq!(store.entries.iter().filter(|e| e.port == 8182).count(), 1);

        clear_plugins_fingerprint(8183);
        let store = load_plugins_fingerprint_store(&plugins_fingerprint_file());
        assert_eq!(store.entries.len(), 1);
        assert_eq!(store.entries[0].port, 8182);
    }

    // -------------------------------------------------------------------
    // Runtime-bundle staleness detection (Issue: dev-mode auto-start can
    // silently reuse a stale bundle that does not match checked-out sources)
    // -------------------------------------------------------------------

    fn write_root_pom_with_version(root: &Path, version: &str) {
        write(
            root.join("pom.xml"),
            format!(
                "<?xml version=\"1.0\"?>\n<project>\n  <parent>\n    \
                 <groupId>ai.platon</groupId>\n    <artifactId>pulsar-parent</artifactId>\n    \
                 <version>4.5.0</version>\n  </parent>\n  <groupId>ai.platon.pulsar</groupId>\n  \
                 <artifactId>browser4</artifactId>\n  <version>{version}</version>\n</project>\n"
            ),
        )
        .unwrap();
    }

    fn write_bundle_lib_jar(lib_dir: &Path, name: &str) {
        create_dir_all(lib_dir).unwrap();
        write(lib_dir.join(name), "jar-content").unwrap();
    }


    #[test]
    fn checked_out_project_version_reads_root_artifact_not_parent() {
        let tmp = test_temp_dir();
        write_root_pom_with_version(tmp.path(), "4.13.13-SNAPSHOT");
        assert_eq!(
            checked_out_project_version(tmp.path()).as_deref(),
            Some("4.13.13-SNAPSHOT")
        );
    }

    #[test]
    fn checked_out_project_version_returns_none_for_unrelated_pom() {
        let tmp = test_temp_dir();
        write(
            tmp.path().join("pom.xml"),
            "<project><artifactId>other</artifactId></project>",
        )
        .unwrap();
        assert_eq!(checked_out_project_version(tmp.path()), None);
    }

    #[test]
    fn bundled_project_version_extracts_version_from_jar_names() {
        let tmp = test_temp_dir();
        let lib_dir = tmp.path().join("lib");
        write_bundle_lib_jar(&lib_dir, "browser4-rest-4.13.11-SNAPSHOT.jar");
        write_bundle_lib_jar(&lib_dir, "browser4-rest-4.13.13-SNAPSHOT.jar");
        write_bundle_lib_jar(&lib_dir, "caffeine-3.2.3.jar");
        let (version, _) = bundled_project_version(&lib_dir).expect("version expected");
        // The newest jar (written last) wins.
        assert_eq!(version, "4.13.13-SNAPSHOT");
    }

    #[test]
    fn bundled_project_version_returns_none_without_project_jars() {
        let tmp = test_temp_dir();
        let lib_dir = tmp.path().join("lib");
        write_bundle_lib_jar(&lib_dir, "caffeine-3.2.3.jar");
        assert_eq!(bundled_project_version(&lib_dir), None);
    }

    #[test]
    fn project_version_newer_compares_segments_numerically() {
        assert!(project_version_newer("4.13.13-SNAPSHOT", "4.13.11-SNAPSHOT"));
        assert!(project_version_newer("4.13.10-SNAPSHOT", "4.13.9-SNAPSHOT"));
        assert!(!project_version_newer("4.13.9-SNAPSHOT", "4.13.10-SNAPSHOT"));
        assert!(project_version_newer("5.0.0-SNAPSHOT", "4.99.99-SNAPSHOT"));
        // Trailing qualifier after `-` is ignored; equal prefixes are not newer.
        assert_eq!(
            project_version_newer("4.13.13-SNAPSHOT", "4.13.13-SNAPSHOT"),
            false
        );
        assert_eq!(project_version_newer("4.13.13", "4.13.13-SNAPSHOT"), false);
        assert_eq!(project_version_newer("4.13.13-SNAPSHOT", "4.13.13"), false);
    }

    #[test]
    fn detect_staleness_flags_version_mismatch() {
        let tmp = test_temp_dir();
        write_root_pom_with_version(tmp.path(), "4.13.13-SNAPSHOT");
        let lib_dir = tmp.path().join("lib");
        write_bundle_lib_jar(&lib_dir, "browser4-rest-4.13.11-SNAPSHOT.jar");

        let staleness = detect_local_bundle_staleness(tmp.path(), &lib_dir);
        assert_eq!(
            staleness,
            Some(LocalBundleStaleness::VersionMismatch {
                checked_out: "4.13.13-SNAPSHOT".to_string(),
                bundled: "4.13.11-SNAPSHOT".to_string(),
            })
        );
    }

    #[test]
    fn detect_staleness_flags_newer_sources() {
        let tmp = test_temp_dir();
        write_root_pom_with_version(tmp.path(), "4.13.13-SNAPSHOT");
        let lib_dir = tmp.path().join("lib");
        write_bundle_lib_jar(&lib_dir, "browser4-rest-4.13.13-SNAPSHOT.jar");

        // No newer sources yet — bundle is fresh.
        assert_eq!(detect_local_bundle_staleness(tmp.path(), &lib_dir), None);

        // Touch a Kotlin source under a bundled module after the jar time.
        let src = tmp.path().join("browser4-rest").join("src").join("main");
        create_dir_all(&src).unwrap();
        let source_file = src.join("Fresh.kt");
        write(&source_file, "package fresh\n").unwrap();
        std::thread::sleep(std::time::Duration::from_millis(20));
        assert_eq!(
            detect_local_bundle_staleness(tmp.path(), &lib_dir),
            Some(LocalBundleStaleness::SourcesNewerThanBundle)
        );
    }

    #[test]
    fn existing_runtime_bundle_rejects_broken_jvm_cfg_runtime() {
        let tmp = test_temp_dir();
        let work_dir = tmp.path().join("bundle");
        let lib_dir = work_dir.join("lib");
        let bin_dir = work_dir.join("runtime").join("bin");
        create_dir_all(&lib_dir).unwrap();
        create_dir_all(&bin_dir).unwrap();
        write_bundle_lib_jar(&lib_dir, "browser4-rest-4.13.13-SNAPSHOT.jar");
        let java_path = bin_dir.join(browser4_java_executable_name());
        write(&java_path, "java").unwrap();

        // Missing lib/jvm.cfg → broken (partially deleted) runtime → None,
        // so the caller rebuilds instead of serving a bundle whose java.exe
        // reports "could not open jvm.cfg".
        assert_eq!(
            existing_runtime_bundle(&lib_dir, &java_path, &work_dir),
            None
        );

        // Adding jvm.cfg makes the bundle valid again.
        create_dir_all(work_dir.join("runtime").join("lib")).unwrap();
        write(work_dir.join("runtime").join("lib").join("jvm.cfg"), "-server").unwrap();
        assert!(existing_runtime_bundle(&lib_dir, &java_path, &work_dir).is_some());
    }

}
