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
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus, Stdio};
use std::time::{Duration, Instant};

use reqwest::Client;
use serde::{Deserialize, Serialize};

use crate::managed_processes::{register_managed_server_process, ManagedServerProcess};
use crate::state::{read_state, resolve_default_state_dir};

const EXISTING_SERVER_READY_TIMEOUT: Duration = Duration::from_secs(120);
const JAR_SERVER_READY_TIMEOUT: Duration = Duration::from_secs(120);
const SERVER_READY_INITIAL_QUIET_WAIT: Duration = Duration::from_secs(5);
const CLI_TEMP_DIR_COMPONENTS: [&str; 2] = ["tmp", "cli"];
const CLI_LIB_DIR_COMPONENT: &str = "lib";
const BROWSER4_LIB_DIR_NAME: &str = "lib";
const BROWSER4_RUNTIME_DIR_NAME: &str = "runtime";
const BROWSER4_MAIN_CLASS: &str = "ai.platon.pulsar.apps.Browser4BundleApplicationKt";
const BROWSER4_INSTALL_METADATA_FILE_NAME: &str = "browser4-installation.json";
const BROWSER4_RELEASES_BASE_URL: &str = "https://github.com/platonai/Browser4/releases";
const BROWSER4_RELEASES_BASE_URL_ENV: &str = "BROWSER4_RELEASES_BASE_URL";
const ROOT_SEARCH_START_DIR_ENV: &str = "BROWSER4_CLI_INVOKE_DIR";

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

fn browser4_install_dir() -> PathBuf {
    resolve_default_state_dir().join(CLI_LIB_DIR_COMPONENT)
}

fn browser4_java_executable_name() -> &'static str {
    if cfg!(windows) { "java.exe" } else { "java" }
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

fn install_dir_contains_runtime(install_dir: &Path) -> bool {
    let lib_dir = install_dir.join(BROWSER4_LIB_DIR_NAME);
    let has_lib = lib_dir.is_dir()
        && std::fs::read_dir(&lib_dir)
            .map(|mut entries| entries.any(|entry| {
                entry
                    .ok()
                    .map(|e| {
                        e.path()
                            .extension()
                            .map(|ext| ext == "jar")
                            .unwrap_or(false)
                    })
                    .unwrap_or(false)
            }))
            .unwrap_or(false);
    has_lib && java_path_in_install_dir(install_dir).is_file()
}

fn materialize_installed_runtime(
    metadata: InstalledBrowser4RuntimeMetadata,
    reused_existing: bool,
) -> InstalledBrowser4Runtime {
    let install_dir = browser4_install_dir();
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

fn browser4_releases_base_url() -> String {
    env::var(BROWSER4_RELEASES_BASE_URL_ENV)
        .ok()
        .map(|value| value.trim().trim_end_matches('/').to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| BROWSER4_RELEASES_BASE_URL.to_string())
}

fn browser4_release_download_url(tag: Option<&str>, asset_name: &str) -> String {
    let base = browser4_releases_base_url();
    match normalize_release_tag(tag) {
        Some(tag) => format!("{base}/download/{tag}/{asset_name}"),
        None => format!("{base}/latest/download/{asset_name}"),
    }
}

fn parse_release_tag_from_url(url: &str) -> Option<String> {
    let parsed = reqwest::Url::parse(url).ok()?;
    let segments = parsed.path_segments()?.collect::<Vec<_>>();
    let download_index = segments.iter().position(|segment| *segment == "download")?;
    segments.get(download_index + 1).map(|segment| (*segment).to_string())
}

fn create_runtime_install_temp_dir() -> Result<PathBuf, String> {
    let path = browser4_cli_temp_root_dir()
        .join("install")
        .join(format!(
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

    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(1800))
        .build()
        .map_err(|e| e.to_string())?;

    let mut response = client
        .get(url)
        .send()
        .map_err(|e| format!("Download failed: {e}"))?;

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
    let bytes_written = io::copy(&mut response, &mut file).map_err(|e| e.to_string())?;
    file.flush().map_err(|e| e.to_string())?;

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
    archive.unpack(destination_dir).map_err(|e| e.to_string())
}

fn is_runtime_bundle_root(path: &Path) -> bool {
    install_dir_contains_runtime(path)
}

fn resolve_runtime_bundle_root(extracted_dir: &Path) -> Result<PathBuf, String> {
    if is_runtime_bundle_root(extracted_dir) {
        return Ok(extracted_dir.to_path_buf());
    }

    for entry in fs::read_dir(extracted_dir).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();
        if path.is_dir() && is_runtime_bundle_root(&path) {
            return Ok(path);
        }
    }

    Err(format!(
        "Downloaded Browser4 runtime bundle did not contain lib/ and runtime/ directories under {}.",
        extracted_dir.display()
    ))
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
    let path = browser4_install_metadata_path();
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
    let install_dir = browser4_install_dir();
    let target_runtime = install_dir.join(BROWSER4_RUNTIME_DIR_NAME);
    let target_lib = install_dir.join(BROWSER4_LIB_DIR_NAME);
    let target_bin = install_dir.join("bin");
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

    fs::create_dir_all(&install_dir).map_err(|e| e.to_string())?;
    remove_path_if_exists(&target_runtime)?;
    remove_path_if_exists(&target_lib)?;
    remove_path_if_exists(&target_bin)?;
    copy_dir_recursive(&source_runtime, &target_runtime)?;
    copy_dir_recursive(&source_lib, &target_lib)?;
    if source_bin.is_dir() {
        copy_dir_recursive(&source_bin, &target_bin)?;
    }
    write_installed_browser4_runtime_metadata(&metadata)?;

    Ok(materialize_installed_runtime(metadata, false))
}

pub async fn install_browser4_runtime(
    tag: Option<&str>,
    force: bool,
) -> Result<InstalledBrowser4Runtime, String> {
    let platform = detect_current_runtime_bundle_platform()?;
    let requested_tag = normalize_release_tag(tag);
    if !force {
        if let Some(requested_tag) = requested_tag.as_deref() {
            if let Some(existing_metadata) = read_installed_browser4_runtime_metadata() {
                if existing_metadata.tag == requested_tag
                    && install_dir_contains_runtime(&browser4_install_dir())
                {
                    return Ok(materialize_installed_runtime(existing_metadata, true));
                }
            }
        }
    }

    let asset_name = platform.asset_name();
    let download_url = browser4_release_download_url(tag, &asset_name);
    let temp_dir = create_runtime_install_temp_dir()?;
    let archive_path = temp_dir.join(&asset_name);
    let extraction_dir = temp_dir.join("extract");

    let install_result = async {
        eprintln!("Downloading Browser4 runtime bundle from {}...", download_url);
        let downloaded = download_file(&download_url, &archive_path).await?;
        eprintln!(
            "Downloaded {} bytes for Browser4 runtime bundle.",
            downloaded.bytes_written
        );
        extract_runtime_bundle_archive(&archive_path, &extraction_dir, platform.archive_kind())?;
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
    .await;

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
        &[]
    };

    for path in candidates {
        if std::path::Path::new(path).exists() {
            return Some(std::path::PathBuf::from(path));
        }
    }

    // Linux / fallback: check PATH
    for name in &["google-chrome", "google-chrome-stable", "chromium-browser", "chromium"] {
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
        return Ok(());
    }

    if cfg!(target_os = "macos") {
        eprintln!("   Install Chrome manually:");
        eprintln!("     brew install --cask google-chrome");
        eprintln!("   Or download from: https://www.google.com/chrome/");
        return Ok(());
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
         exit (Test-Path '{}')",
        temp_installer.display(),
        temp_installer.display(),
        temp_installer.display(),
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    );

    let status = std::process::Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            &ps_script,
        ])
        .status()
        .map_err(|e| format!("Failed to run PowerShell installer: {e}"))?;

    if !status.success() {
        let _ = fs::remove_file(&temp_installer);
        return Err("Google Chrome installation via PowerShell failed.".to_string());
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
    let status = std::process::Command::new("sudo")
        .args(["dpkg", "-i"])
        .arg(&tmp_deb)
        .status()
        .map_err(|e| format!("Failed to run dpkg: {e}"))?;

    if !status.success() {
        // Fix broken dependencies
        eprintln!("   Fixing dependencies ...");
        let _ = std::process::Command::new("sudo")
            .args(["apt-get", "install", "-f", "-y"])
            .status();
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
    let status = std::process::Command::new("sudo")
        .args(["dnf", "install", "-y"])
        .arg(&tmp_rpm)
        .status()
        .map_err(|e| format!("Failed to run dnf: {e}"))?;

    if !status.success() {
        // Try yum as fallback
        let _ = std::process::Command::new("sudo")
            .args(["yum", "install", "-y"])
            .arg(&tmp_rpm)
            .status();
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
        format!(
            "{}\\*",
            runtime.lib_dir.display()
        )
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
            bundle_module_dir.join("target").join("Browser4Bundle.jar").display()
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
                        "package",
                        "-Passet-bundle",
                        "-pl",
                        "browser4-apps/browser4-bundle",
                        "-am",
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
                    status.code().map_or_else(|| "signal".to_string(), |c| c.to_string())
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
            eprintln!(
                "Runtime bundle build script failed: {error}; falling back to download."
            );
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
async fn run_bundle_build_script(
    shell: &str,
    script: &Path,
    working_dir: &Path,
) -> Result<(), String> {
    let script_path = script.to_path_buf();
    let working_dir = working_dir.to_path_buf();
    let shell_owned = shell.to_string();
    let shell_for_error = shell_owned.clone();

    let output = tokio::task::spawn_blocking(move || {
        std::process::Command::new(&shell_owned)
            .args([
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                &script_path.to_string_lossy(),
            ])
            .current_dir(&working_dir)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::piped())
            .output()
    })
    .await
    .map_err(|e| format!("build script task panicked: {e}"))?;

    match output {
        Ok(output) if output.status.success() => Ok(()),
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            Err(format!(
                "build script exited with {}: {}",
                output
                    .status
                    .code()
                    .map_or_else(|| "signal".to_string(), |c| c.to_string()),
                stderr.trim()
            ))
        }
        Err(error) => Err(format!("failed to spawn build script ({shell_for_error}): {error}")),
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

    // Check for a local project build (or auto-build it).
    let project_root = find_browser4_root();
    if let Some(root) = &project_root {
        match try_build_local_runtime_bundle(root).await {
            Ok(Some(runtime)) => return Ok(runtime),
            Ok(None) => {
                eprintln!(
                    "Local Browser4 bundle is not available; falling back to download."
                );
            }
            Err(error) => {
                eprintln!(
                    "Local Browser4 bundle check failed: {error}; falling back to download."
                );
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
    use tempfile::TempDir;

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
        create_dir_all(root.join("sdks").join("browser4-cli")).unwrap();
        write(root.join("ROOT.md"), "# Browser4\n").unwrap();
        write(root.join("VERSION"), "0.1.0\n").unwrap();
        write(root.join("pom.xml"), "<project />").unwrap();
        write(
            root.join("sdks").join("browser4-cli").join("Cargo.toml"),
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
        let nested = root.join("sdks").join("browser4-cli");

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
        let nested = root.join("sdks").join("browser4-cli").join("src");
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
    fn test_browser4_release_download_url_defaults_to_latest() {
        let previous = env::var(BROWSER4_RELEASES_BASE_URL_ENV).ok();
        unsafe {
            env::remove_var(BROWSER4_RELEASES_BASE_URL_ENV);
        }
        let url = browser4_release_download_url(None, "browser4-runtime-windows-x64.zip");
        match previous {
            Some(value) => unsafe { env::set_var(BROWSER4_RELEASES_BASE_URL_ENV, value) },
            None => unsafe { env::remove_var(BROWSER4_RELEASES_BASE_URL_ENV) },
        }
        assert_eq!(
            url,
            "https://github.com/platonai/Browser4/releases/latest/download/browser4-runtime-windows-x64.zip"
        );
    }

    #[test]
    fn test_browser4_release_download_url_normalizes_explicit_tags() {
        let previous = env::var(BROWSER4_RELEASES_BASE_URL_ENV).ok();
        unsafe {
            env::remove_var(BROWSER4_RELEASES_BASE_URL_ENV);
        }
        let url_without_v =
            browser4_release_download_url(Some("4.9.3"), "browser4-runtime-linux-x64.tar.gz");
        let url_with_v =
            browser4_release_download_url(Some("v4.9.3"), "browser4-runtime-linux-x64.tar.gz");
        match previous {
            Some(value) => unsafe { env::set_var(BROWSER4_RELEASES_BASE_URL_ENV, value) },
            None => unsafe { env::remove_var(BROWSER4_RELEASES_BASE_URL_ENV) },
        }
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
    fn test_materialize_installed_runtime_uses_default_install_layout() {
        let tmp = test_temp_dir();
        unsafe {
            env::set_var("BROWSER4_CLI_STATE_DIR", tmp.path().as_os_str());
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
            env::remove_var("BROWSER4_CLI_STATE_DIR");
        }

        // In the new model, jar_path = lib_dir (the lib/ directory)
        assert!(runtime.lib_dir.ends_with("lib"));
        assert!(runtime
            .java_path
            .ends_with(Path::new("lib").join("runtime").join("bin").join(browser4_java_executable_name())));
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
        let jar_path = server_startup_log_path(
            Some(tmp.path()),
            &sample_launch_spec(),
            9292,
        );

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
        let log = create_server_startup_log_in(
            Some(tmp.path()),
            &sample_launch_spec(),
            8123,
        )
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
        let log = create_server_startup_log_in(
            Some(tmp.path()),
            &sample_launch_spec(),
            8182,
        )
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
        create_dir_all(root.join("sdks").join("browser4-cli")).unwrap();
        write(root.join("pom.xml"), "<project />").unwrap();
        write(
            root.join("browser4-apps")
                .join("browser4-standalone")
                .join("pom.xml"),
            "<project />",
        )
        .unwrap();
        write(
            root.join("sdks").join("browser4-cli").join("Cargo.toml"),
            "[package]\nname = \"browser4-cli\"\n",
        )
        .unwrap();

        assert!(!is_browser4_root(&root));
    }

    fn create_browser4_root_in(parent: &Path) -> PathBuf {
        let root = parent.join("Browser4");
        create_dir_all(&root).unwrap();
        create_dir_all(root.join("browser4-apps").join("browser4-standalone")).unwrap();
        create_dir_all(root.join("sdks").join("browser4-cli")).unwrap();
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
            root.join("sdks").join("browser4-cli").join("Cargo.toml"),
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
        assert_eq!(normalize_release_tag(Some("4.9.3")).as_deref(), Some("v4.9.3"));
        assert_eq!(normalize_release_tag(Some("4.10.0")).as_deref(), Some("v4.10.0"));
    }

    #[test]
    fn test_normalize_release_tag_keeps_existing_v_prefix() {
        assert_eq!(normalize_release_tag(Some("v4.9.3")).as_deref(), Some("v4.9.3"));
        assert_eq!(normalize_release_tag(Some("v4.10.0")).as_deref(), Some("v4.10.0"));
    }

    #[test]
    fn test_normalize_release_tag_trims_whitespace() {
        assert_eq!(normalize_release_tag(Some("  v4.9.3  ")).as_deref(), Some("v4.9.3"));
        assert_eq!(normalize_release_tag(Some("  4.10.0\t")).as_deref(), Some("v4.10.0"));
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
        assert_eq!(parse_release_tag_from_url("https://example.com/other/path"), None);
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
        let tmp = test_temp_dir();
        let metadata_path = tmp.path().join("lib").join("browser4-installation.json");
        fs::create_dir_all(metadata_path.parent().unwrap()).unwrap();
        let metadata = InstalledBrowser4RuntimeMetadata {
            tag: "v4.9.3".to_string(),
            asset_name: "browser4-runtime-linux-x64.tar.gz".to_string(),
            download_url: "https://example.com/releases/download/v4.9.3/bundle.tar.gz"
                .to_string(),
            installed_at: "2026-06-01T00:00:00Z".to_string(),
        };
        let json = serde_json::to_string_pretty(&metadata).unwrap();
        fs::write(&metadata_path, json).unwrap();

        let previous = env::var("BROWSER4_CLI_STATE_DIR").ok();
        unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", tmp.path().as_os_str()); }
        let read = read_installed_browser4_runtime_metadata();
        // restore
        match previous {
            Some(v) => unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", v) },
            None => unsafe { env::remove_var("BROWSER4_CLI_STATE_DIR") },
        }
        assert!(read.is_some());
        let read = read.unwrap();
        assert_eq!(read.tag, "v4.9.3");
        assert_eq!(read.asset_name, "browser4-runtime-linux-x64.tar.gz");
        assert_eq!(read.installed_at, "2026-06-01T00:00:00Z");
    }

    #[test]
    fn test_metadata_missing_file_returns_none() {
        let tmp = test_temp_dir();
        let previous = env::var("BROWSER4_CLI_STATE_DIR").ok();
        unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", tmp.path().as_os_str()); }
        let read = read_installed_browser4_runtime_metadata();
        match previous {
            Some(v) => unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", v) },
            None => unsafe { env::remove_var("BROWSER4_CLI_STATE_DIR") },
        }
        assert!(read.is_none());
    }

    #[test]
    fn test_metadata_corrupted_json_returns_none() {
        let tmp = test_temp_dir();
        let metadata_path = tmp.path().join("lib").join("browser4-installation.json");
        fs::create_dir_all(metadata_path.parent().unwrap()).unwrap();
        fs::write(&metadata_path, "not valid json {{{").unwrap();

        let previous = env::var("BROWSER4_CLI_STATE_DIR").ok();
        unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", tmp.path().as_os_str()); }
        let read = read_installed_browser4_runtime_metadata();
        match previous {
            Some(v) => unsafe { env::set_var("BROWSER4_CLI_STATE_DIR", v) },
            None => unsafe { env::remove_var("BROWSER4_CLI_STATE_DIR") },
        }
        assert!(read.is_none());
    }

}
