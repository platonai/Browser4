//! Server daemon management for the Browser4 CLI.
//!
//! Ensures a Browser4 server is running before executing commands.
//! Only manages localhost instances; remote servers are not touched.
//! When a local Browser4 checkout is available, startup prefers
//! `mvn spring-boot:run` from the `browser4-apps/browser4-agents` module so the CLI
//! uses the matching server version from source. If no checkout can be found,
//! it falls back to the packaged Browser4 jar flow used by the standalone
//! installer.

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
const JAR_SERVER_READY_TIMEOUT: Duration = Duration::from_secs(60);
const MAVEN_SERVER_READY_TIMEOUT: Duration = Duration::from_secs(300);
const SERVER_READY_INITIAL_QUIET_WAIT: Duration = Duration::from_secs(5);
const CLI_TEMP_DIR_COMPONENTS: [&str; 2] = ["tmp", "cli"];
const CLI_LIB_DIR_COMPONENT: &str = "lib";
const BROWSER4_JAR_FILE_NAME: &str = "Browser4.jar";
const BROWSER4_JRE_DIR_NAME: &str = "jre";
const BROWSER4_INSTALL_METADATA_FILE_NAME: &str = "browser4-installation.json";
const BROWSER4_RELEASES_BASE_URL: &str = "https://github.com/platonai/Browser4/releases";
const BROWSER4_RELEASES_BASE_URL_ENV: &str = "BROWSER4_RELEASES_BASE_URL";
const BROWSER4_JAVA_PATH_ENV: &str = "BROWSER4_JAVA_PATH";
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
            RuntimeBundlePlatform::WindowsX64 => "browser4-runtime-windows-x64.zip",
            RuntimeBundlePlatform::LinuxX64 => "browser4-runtime-linux-x64.tar.gz",
            RuntimeBundlePlatform::MacOsX64 => "browser4-runtime-darwin-x64.tar.gz",
            RuntimeBundlePlatform::MacOsArm64 => "browser4-runtime-darwin-arm64.tar.gz",
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
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct InstalledBrowser4RuntimeMetadata {
    tag: String,
    asset_name: String,
    download_url: String,
    installed_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstalledBrowser4Runtime {
    pub tag: String,
    pub asset_name: String,
    pub download_url: String,
    pub install_dir: PathBuf,
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
pub async fn ensure_server_running(base_url: &str, use_maven_startup: bool) -> Result<(), String> {
    // Skip remote servers
    if !base_url.contains("localhost") && !base_url.contains("127.0.0.1") {
        return Ok(());
    }

    let port = extract_port(base_url);
    if !is_local_port_open(base_url) {
        eprintln!("Browser4 server not running. Starting...");
        let launch_spec = resolve_server_launch_spec(port, use_maven_startup).await?;
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
        ServerState::Unreachable(_) => {}
    }

    eprintln!("Browser4 server not running. Starting...");

    let launch_spec = resolve_server_launch_spec(port, use_maven_startup).await?;
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
    Maven,
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

fn default_browser4_jre_dir() -> PathBuf {
    browser4_install_dir().join(BROWSER4_JRE_DIR_NAME)
}

fn browser4_java_executable_name() -> &'static str {
    if cfg!(windows) { "java.exe" } else { "java" }
}

fn default_browser4_java_path() -> PathBuf {
    default_browser4_jre_dir()
        .join("bin")
        .join(browser4_java_executable_name())
}

fn browser4_install_metadata_path() -> PathBuf {
    browser4_install_dir().join(BROWSER4_INSTALL_METADATA_FILE_NAME)
}

fn java_path_in_install_dir(install_dir: &Path) -> PathBuf {
    install_dir
        .join(BROWSER4_JRE_DIR_NAME)
        .join("bin")
        .join(browser4_java_executable_name())
}

fn browser4_java_override_path() -> Option<PathBuf> {
    let override_path = env::var(BROWSER4_JAVA_PATH_ENV).ok()?;
    let trimmed = override_path.trim();
    if trimmed.is_empty() {
        return None;
    }
    Some(PathBuf::from(trimmed))
}

fn installed_browser4_java_path() -> Option<PathBuf> {
    let path = default_browser4_java_path();
    path.is_file().then_some(path)
}

fn resolve_browser4_java_command() -> PathBuf {
    browser4_java_override_path()
        .or_else(installed_browser4_java_path)
        .unwrap_or_else(|| PathBuf::from("java"))
}

fn read_installed_browser4_runtime_metadata() -> Option<InstalledBrowser4RuntimeMetadata> {
    let path = browser4_install_metadata_path();
    let contents = fs::read_to_string(path).ok()?;
    serde_json::from_str(&contents).ok()
}

fn install_dir_contains_runtime(install_dir: &Path) -> bool {
    install_dir.join(BROWSER4_JAR_FILE_NAME).is_file() && java_path_in_install_dir(install_dir).is_file()
}

fn materialize_installed_runtime(
    metadata: InstalledBrowser4RuntimeMetadata,
    reused_existing: bool,
) -> InstalledBrowser4Runtime {
    let install_dir = browser4_install_dir();
    InstalledBrowser4Runtime {
        tag: metadata.tag,
        asset_name: metadata.asset_name,
        download_url: metadata.download_url,
        jar_path: install_dir.join(BROWSER4_JAR_FILE_NAME),
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
        return Err(format!(
            "Download failed with status: {}",
            response.status()
        ));
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
    path.join(BROWSER4_JAR_FILE_NAME).is_file() && java_path_in_install_dir(path).is_file()
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
        "Downloaded Browser4 runtime bundle did not contain {} and a bundled JRE under {}.",
        BROWSER4_JAR_FILE_NAME,
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
    let target_jar = install_dir.join(BROWSER4_JAR_FILE_NAME);
    let target_jre = install_dir.join(BROWSER4_JRE_DIR_NAME);
    let source_jar = extracted_root.join(BROWSER4_JAR_FILE_NAME);
    let source_jre = extracted_root.join(BROWSER4_JRE_DIR_NAME);

    if !source_jar.is_file() {
        return Err(format!(
            "Downloaded Browser4 runtime bundle is missing {}",
            source_jar.display()
        ));
    }
    if !source_jre.is_dir() {
        return Err(format!(
            "Downloaded Browser4 runtime bundle is missing bundled JRE directory {}",
            source_jre.display()
        ));
    }

    fs::create_dir_all(&install_dir).map_err(|e| e.to_string())?;
    remove_path_if_exists(&target_jre)?;
    remove_path_if_exists(&target_jar)?;
    copy_dir_recursive(&source_jre, &target_jre)?;
    fs::copy(&source_jar, &target_jar).map_err(|e| e.to_string())?;
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

async fn resolve_server_launch_spec(
    port: u16,
    use_maven_startup: bool,
) -> Result<ServerLaunchSpec, String> {
    if let Some(repo_root) = find_browser4_root_for_enabled_maven_launch(use_maven_startup) {
        match build_maven_launch_spec(&repo_root, port) {
            Ok(spec) => return Ok(spec),
            Err(error) => {
                eprintln!(
                    "Falling back to Browser4.jar startup because Maven launch spec failed: {}",
                    error
                );
            }
        }
    }

    let jar_path = find_or_download_jar().await?;
    Ok(build_jar_launch_spec(&jar_path, port))
}

fn build_maven_launch_spec(repo_root: &Path, port: u16) -> Result<ServerLaunchSpec, String> {
    if !repo_root.is_dir() {
        return Err(format!(
            "Browser4 root does not exist: {}",
            repo_root.display()
        ));
    }

    let module_dir = repo_root.join("browser4-app").join("browser4-agents");
    if !module_dir.join("pom.xml").is_file() {
        return Err(format!(
            "Browser4 agents module not found under {}",
            module_dir.display()
        ));
    }

    let wrapper_name = if cfg!(windows) { "mvnw.cmd" } else { "mvnw" };
    let wrapper_path = repo_root.join(wrapper_name);
    let program = if wrapper_path.exists() {
        wrapper_path
    } else if cfg!(windows) {
        PathBuf::from("mvn.cmd")
    } else {
        PathBuf::from("mvn")
    };

    Ok(ServerLaunchSpec {
        kind: ServerLaunchKind::Maven,
        program: program.clone(),
        args: vec![format!("-Dspring-boot.run.arguments=--server.port={port}")],
        working_dir: repo_root.to_path_buf(),
        registry_target: program,
        description: format!(
            "Starting server via Maven spring-boot:run from {} on port {}...",
            module_dir.display(),
            port
        ),
    })
}

fn build_jar_launch_spec(jar_path: &Path, port: u16) -> ServerLaunchSpec {
    let program = resolve_browser4_java_command();
    let program_display = program.display().to_string();
    ServerLaunchSpec {
        kind: ServerLaunchKind::Jar,
        program,
        args: vec![
            "-jar".to_string(),
            java_jar_argument_path(jar_path),
            format!("--server.port={port}"),
        ],
        working_dir: jar_path
            .parent()
            .map(Path::to_path_buf)
            .unwrap_or_else(default_server_working_dir),
        registry_target: jar_path.to_path_buf(),
        description: format!(
            "Starting server from Browser4.jar at {} using {} on port {}...",
            jar_path.display(),
            program_display,
            port
        ),
    }
}

fn java_jar_argument_path(jar_path: &Path) -> String {
    #[cfg(windows)]
    {
        normalize_windows_verbatim_path_for_java(jar_path)
    }

    #[cfg(not(windows))]
    {
        jar_path.to_string_lossy().to_string()
    }
}

#[cfg(windows)]
fn normalize_windows_verbatim_path_for_java(path: &Path) -> String {
    let raw = path.to_string_lossy();
    if let Some(without_prefix) = raw.strip_prefix(r"\\?\UNC\") {
        return format!(r"\\{without_prefix}");
    }
    if let Some(without_prefix) = raw.strip_prefix(r"\\?\") {
        return without_prefix.to_string();
    }
    raw.to_string()
}

fn default_server_working_dir() -> PathBuf {
    env::current_dir().unwrap_or_else(|_| resolve_default_state_dir())
}

fn command_for_launch_spec(
    launch_spec: &ServerLaunchSpec,
) -> Result<PreparedLaunchCommand, String> {
    #[cfg(windows)]
    if launch_spec.kind == ServerLaunchKind::Maven && is_windows_batch_program(&launch_spec.program)
    {
        let invocation = build_powershell_maven_invocation(&launch_spec.program, &launch_spec.args);
        let mut command = Command::new("powershell.exe");
        command
            .args(["-NoProfile", "-NonInteractive", "-Command", &invocation])
            .current_dir(&launch_spec.working_dir)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        return Ok(PreparedLaunchCommand {
            command,
            cleanup_dir: None,
        });
    }

    #[cfg(not(windows))]
    let (program, cleanup_dir) = if launch_spec.kind == ServerLaunchKind::Maven
        && launch_spec
            .program
            .file_name()
            .and_then(|name| name.to_str())
            == Some("mvnw")
        && launch_spec.program.is_file()
    {
        prepare_unix_maven_wrapper_launcher(launch_spec)?
    } else {
        (launch_spec.program.clone(), None)
    };

    #[cfg(windows)]
    let (program, cleanup_dir) = (launch_spec.program.clone(), None);

    let mut command = Command::new(&program);
    command
        .args(&launch_spec.args)
        .current_dir(&launch_spec.working_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    Ok(PreparedLaunchCommand {
        command,
        cleanup_dir,
    })
}

fn launch_ready_timeout(launch_spec: &ServerLaunchSpec) -> Duration {
    if launch_spec.kind == ServerLaunchKind::Maven {
        MAVEN_SERVER_READY_TIMEOUT
    } else {
        JAR_SERVER_READY_TIMEOUT
    }
}

#[cfg(windows)]
fn is_windows_batch_program(program: &Path) -> bool {
    matches!(
        program.extension().and_then(|extension| extension.to_str()),
        Some("cmd" | "bat")
    )
}

#[cfg(windows)]
fn escape_powershell_single_quoted(value: &str) -> String {
    value.replace('\'', "''")
}

#[cfg(windows)]
fn build_powershell_batch_invocation(program: &Path, args: &[String]) -> String {
    std::iter::once(program.to_string_lossy().to_string())
        .chain(args.iter().cloned())
        .map(|value| format!("'{}'", escape_powershell_single_quoted(&value)))
        .collect::<Vec<_>>()
        .join(" ")
        .pipe(|command| format!("& {command}"))
}

#[cfg(windows)]
fn build_powershell_maven_invocation(program: &Path, runtime_args: &[String]) -> String {
    let install_args = vec![
        "-pl".to_string(),
        "browser4-apps/browser4-agents".to_string(),
        "-am".to_string(),
        "-DskipTests".to_string(),
        "install".to_string(),
        "-q".to_string(),
    ];
    let mut run_args = vec![
        "-pl".to_string(),
        "browser4-apps/browser4-agents".to_string(),
        "spring-boot:run".to_string(),
    ];
    run_args.extend_from_slice(runtime_args);

    let install_command = build_powershell_batch_invocation(program, &install_args);
    let run_command = build_powershell_batch_invocation(program, &run_args);

    format!("{install_command}; if ($LASTEXITCODE -ne 0) {{ exit $LASTEXITCODE }}; {run_command}")
}

#[cfg(not(windows))]
fn prepare_unix_maven_wrapper_launcher(
    launch_spec: &ServerLaunchSpec,
) -> Result<(PathBuf, Option<PathBuf>), String> {
    use std::os::unix::fs::{symlink, PermissionsExt};
    use std::time::{SystemTime, UNIX_EPOCH};

    let Some(repo_root) = launch_spec.program.parent() else {
        return Ok((launch_spec.program.clone(), None));
    };
    let wrapper_support_dir = repo_root.join(".mvn");
    if !wrapper_support_dir.is_dir() {
        return Ok((launch_spec.program.clone(), None));
    }

    let launcher_base_dir = browser4_cli_temp_root_dir().join("launchers");
    fs::create_dir_all(&launcher_base_dir).map_err(|e| {
        format!(
            "Failed to create Browser4 launcher directory {}: {e}",
            launcher_base_dir.display()
        )
    })?;

    let unique_suffix = format!(
        "{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|e| format!("Failed to compute Browser4 launcher timestamp: {e}"))?
            .as_nanos()
    );
    let launcher_dir = launcher_base_dir.join(format!("maven-wrapper-{unique_suffix}"));
    fs::create_dir(&launcher_dir).map_err(|e| {
        format!(
            "Failed to create Browser4 Maven wrapper directory {}: {e}",
            launcher_dir.display()
        )
    })?;

    let launcher_wrapper_path = launcher_dir.join("mvnw");
    let launcher_support_dir = launcher_dir.join(".mvn");
    let wrapper_contents = fs::read(&launch_spec.program).map_err(|e| {
        format!(
            "Failed to read Maven wrapper {}: {e}",
            launch_spec.program.display()
        )
    })?;
    let normalized_wrapper_contents = wrapper_contents
        .into_iter()
        .filter(|byte| *byte != b'\r')
        .collect::<Vec<_>>();
    fs::write(&launcher_wrapper_path, normalized_wrapper_contents).map_err(|e| {
        format!(
            "Failed to write normalized Maven wrapper {}: {e}",
            launcher_wrapper_path.display()
        )
    })?;
    fs::set_permissions(&launcher_wrapper_path, fs::Permissions::from_mode(0o755)).map_err(
        |e| {
            format!(
                "Failed to set executable permissions on normalized Maven wrapper {}: {e}",
                launcher_wrapper_path.display()
            )
        },
    )?;
    symlink(&wrapper_support_dir, &launcher_support_dir).map_err(|e| {
        format!(
            "Failed to link Maven wrapper support directory {} -> {}: {e}",
            launcher_support_dir.display(),
            wrapper_support_dir.display()
        )
    })?;

    // Use a single reactor spring-boot:run so Maven resolves sibling modules
    // from the current checkout instead of falling back to stale installed jars.
    let mvnw_abs = launcher_wrapper_path
        .to_string_lossy()
        .replace('\'', "'\\''");
    let launcher_script_content = [
        "#!/bin/sh",
        "set -e",
        &format!("'{mvnw_abs}' -pl browser4-apps/browser4-agents -am -DskipTests install -q"),
        &format!("'{mvnw_abs}' -pl browser4-apps/browser4-agents spring-boot:run \"$@\""),
        "",
    ]
    .join("\n");
    let launcher_script_path = launcher_dir.join("browser4-start.sh");
    fs::write(&launcher_script_path, launcher_script_content.as_bytes()).map_err(|e| {
        format!(
            "Failed to write Browser4 launcher script {}: {e}",
            launcher_script_path.display()
        )
    })?;
    fs::set_permissions(&launcher_script_path, fs::Permissions::from_mode(0o755)).map_err(|e| {
        format!(
            "Failed to set executable permissions on Browser4 launcher script {}: {e}",
            launcher_script_path.display()
        )
    })?;

    Ok((launcher_script_path, Some(launcher_dir)))
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

fn find_browser4_root_for_maven_launch() -> Option<PathBuf> {
    let current_dir = env::current_dir().ok()?;
    find_browser4_root_from(&current_dir, false)
}

fn find_browser4_root_for_enabled_maven_launch(use_maven_startup: bool) -> Option<PathBuf> {
    if !use_maven_startup {
        return None;
    }

    find_browser4_root_for_maven_launch()
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

async fn find_or_download_jar() -> Result<PathBuf, String> {
    if let Some(configured_path) = browser4_jar_override_path() {
        return Ok(configured_path);
    }

    let project_root = find_browser4_root();
    let candidates = browser4_jar_candidates(project_root.as_deref());

    eprintln!("Project root: {:?}", project_root);

    for candidate in candidates {
        eprintln!("Checking for Browser4.jar at {}...", candidate.display());

        if candidate.exists() {
            return Ok(candidate);
        }
    }

    match install_browser4_runtime(None, false).await {
        Ok(runtime) => {
            eprintln!(
                "Installed self-contained Browser4 runtime {} at {}.",
                runtime.tag,
                runtime.install_dir.display()
            );
            return Ok(runtime.jar_path);
        }
        Err(error) => {
            eprintln!(
                "Failed to install bundled Browser4 runtime automatically: {}",
                error
            );
            eprintln!("Falling back to Browser4.jar-only download.");
        }
    }

    let download_path = default_browser4_jar_path();
    download_jar(&download_path).await?;
    Ok(download_path)
}

fn browser4_jar_override_path() -> Option<PathBuf> {
    let override_path = env::var("BROWSER4_JAR_PATH").ok()?;
    let trimmed = override_path.trim();
    if trimmed.is_empty() {
        return None;
    }

    let path = PathBuf::from(trimmed);
    path.exists().then_some(path)
}

fn browser4_jar_candidates(project_root: Option<&Path>) -> Vec<PathBuf> {
    let mut candidates = Vec::new();

    if let Some(root) = project_root {
        candidates.push(
            root.join("browser4-app")
                .join("browser4-agents")
                .join("target")
                .join(BROWSER4_JAR_FILE_NAME),
        );
        candidates.push(root.join("target").join(BROWSER4_JAR_FILE_NAME));
    }

    candidates.push(default_browser4_jar_path());
    candidates
}

fn default_browser4_jar_path() -> PathBuf {
    resolve_default_state_dir()
        .join(CLI_LIB_DIR_COMPONENT)
        .join(BROWSER4_JAR_FILE_NAME)
}

async fn download_jar(target_path: &Path) -> Result<(), String> {
    if let Some(dir) = target_path.parent() {
        fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }

    let url = "https://github.com/platonai/Browser4/releases/latest/download/Browser4.jar";
    eprintln!("Downloading Browser4.jar from {}...", url);
    let downloaded = download_file(url, target_path).await?;

    eprintln!(
        "Download complete ({} bytes, final URL: {}).",
        downloaded.bytes_written,
        downloaded.final_url
    );
    Ok(())
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
        ServerLaunchKind::Maven => "maven",
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

    fn sample_launch_spec(kind: ServerLaunchKind) -> ServerLaunchSpec {
        ServerLaunchSpec {
            kind,
            program: PathBuf::from("program"),
            args: vec!["arg1".to_string(), "arg2".to_string()],
            working_dir: PathBuf::from("."),
            registry_target: PathBuf::from("registry-target"),
            description: String::from("desc"),
        }
    }

    fn create_browser4_root(tmp: &TempDir) -> PathBuf {
        let root = tmp.path().join("Browser4");
        create_dir_all(root.join("browser4-app").join("browser4-agents")).unwrap();
        create_dir_all(root.join("sdks").join("browser4-cli")).unwrap();
        write(root.join("ROOT.md"), "# Browser4\n").unwrap();
        write(root.join("VERSION"), "0.1.0\n").unwrap();
        write(root.join("pom.xml"), "<project />").unwrap();
        write(
            root.join("browser4-app")
                .join("browser4-agents")
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
    fn test_find_browser4_root_for_enabled_maven_launch_requires_opt_in() {
        assert_eq!(find_browser4_root_for_enabled_maven_launch(false), None);
        assert_eq!(
            find_browser4_root_for_enabled_maven_launch(true),
            find_browser4_root_for_maven_launch()
        );
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
    fn test_launch_ready_timeout_prefers_maven_processes() {
        let mut maven_spec = sample_launch_spec(ServerLaunchKind::Maven);
        maven_spec.program = PathBuf::from(if cfg!(windows) { "mvnw.cmd" } else { "mvnw" });
        let mut jar_spec = sample_launch_spec(ServerLaunchKind::Jar);
        jar_spec.program = PathBuf::from("java");

        assert_eq!(
            launch_ready_timeout(&maven_spec),
            MAVEN_SERVER_READY_TIMEOUT
        );
        assert_eq!(launch_ready_timeout(&jar_spec), JAR_SERVER_READY_TIMEOUT);
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
    fn test_default_browser4_jar_path_uses_state_dir_override() {
        let tmp = test_temp_dir();
        let expected = tmp
            .path()
            .canonicalize()
            .unwrap()
            .join("lib")
            .join("Browser4.jar");

        unsafe {
            env::set_var("BROWSER4_CLI_STATE_DIR", tmp.path().as_os_str());
        }
        let actual = default_browser4_jar_path();
        unsafe {
            env::remove_var("BROWSER4_CLI_STATE_DIR");
        }

        assert_eq!(actual, expected);
    }

    #[test]
    fn test_default_browser4_java_path_uses_state_dir_override() {
        let tmp = test_temp_dir();
        let expected = tmp
            .path()
            .canonicalize()
            .unwrap()
            .join("lib")
            .join("jre")
            .join("bin")
            .join(browser4_java_executable_name());

        unsafe {
            env::set_var("BROWSER4_CLI_STATE_DIR", tmp.path().as_os_str());
        }
        let actual = default_browser4_java_path();
        unsafe {
            env::remove_var("BROWSER4_CLI_STATE_DIR");
        }

        assert_eq!(actual, expected);
    }

    #[test]
    fn test_browser4_jar_override_path_ignores_blank_values() {
        unsafe {
            env::set_var("BROWSER4_JAR_PATH", "   ");
        }
        let actual = browser4_jar_override_path();
        unsafe {
            env::remove_var("BROWSER4_JAR_PATH");
        }

        assert_eq!(actual, None);
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
        let bundle_root = extracted.join("browser4-runtime-windows-x64");
        fs::create_dir_all(bundle_root.join("jre").join("bin")).unwrap();
        write(bundle_root.join(BROWSER4_JAR_FILE_NAME), "jar").unwrap();
        write(
            bundle_root
                .join("jre")
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

        assert!(runtime.jar_path.ends_with(Path::new("lib").join("Browser4.jar")));
        assert!(runtime
            .java_path
            .ends_with(Path::new("lib").join("jre").join("bin").join(browser4_java_executable_name())));
        assert!(runtime.reused_existing);
    }

    #[test]
    fn test_resolve_browser4_java_command_prefers_installed_runtime_java() {
        let tmp = test_temp_dir();

        unsafe {
            env::set_var("BROWSER4_CLI_STATE_DIR", tmp.path().as_os_str());
        }
        let installed_java = default_browser4_java_path();
        create_dir_all(installed_java.parent().unwrap()).unwrap();
        write(&installed_java, "java").unwrap();
        let actual = resolve_browser4_java_command();
        unsafe {
            env::remove_var("BROWSER4_CLI_STATE_DIR");
        }

        assert_eq!(actual, installed_java.canonicalize().unwrap());
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
        let maven_path = server_startup_log_path(
            Some(tmp.path()),
            &sample_launch_spec(ServerLaunchKind::Maven),
            8182,
        );
        let jar_path = server_startup_log_path(
            Some(tmp.path()),
            &sample_launch_spec(ServerLaunchKind::Jar),
            9292,
        );

        let maven_name = maven_path.file_name().unwrap().to_string_lossy();
        let jar_name = jar_path.file_name().unwrap().to_string_lossy();

        assert!(maven_path.starts_with(tmp.path()));
        assert!(jar_path.starts_with(tmp.path()));
        assert!(maven_name.starts_with("browser4-server-maven-port8182-"));
        assert!(maven_name.ends_with(".log"));
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
            &sample_launch_spec(ServerLaunchKind::Maven),
            8123,
        )
        .expect("startup log creation should succeed");

        drop(log.stdout);
        drop(log.stderr);

        let contents = fs::read_to_string(&log.path).expect("startup log should be readable");
        assert!(contents.contains("Launching Browser4 Maven on port 8123"));
        assert!(contents.contains("program: program"));
        assert!(contents.contains("args: arg1 arg2"));
    }

    #[test]
    fn test_append_startup_log_message_writes_status_line() {
        let tmp = test_temp_dir();
        let log = create_server_startup_log_in(
            Some(tmp.path()),
            &sample_launch_spec(ServerLaunchKind::Jar),
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
        create_dir_all(root.join("browser4-app").join("browser4-agents")).unwrap();
        create_dir_all(root.join("sdks").join("browser4-cli")).unwrap();
        write(root.join("pom.xml"), "<project />").unwrap();
        write(
            root.join("browser4-app")
                .join("browser4-agents")
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
        create_dir_all(root.join("browser4-app").join("browser4-agents")).unwrap();
        create_dir_all(root.join("sdks").join("browser4-cli")).unwrap();
        write(root.join("ROOT.md"), "# Browser4\n").unwrap();
        write(root.join("VERSION"), "0.1.0\n").unwrap();
        write(root.join("pom.xml"), "<project />").unwrap();
        write(
            root.join("browser4-app")
                .join("browser4-agents")
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

    #[cfg(windows)]
    #[test]
    fn test_build_maven_launch_spec_prefers_windows_wrapper() {
        let tmp = test_temp_dir();
        let root = create_browser4_root(&tmp);
        let wrapper = root.join("mvnw.cmd");
        write(&wrapper, "@echo off\r\n").unwrap();
        let port_arg = "-Dspring-boot.run.arguments=--server.port=8199".to_string();

        let spec = build_maven_launch_spec(&root, 8199).unwrap();

        assert_eq!(spec.kind, ServerLaunchKind::Maven);
        assert_eq!(spec.program, wrapper);
        assert_eq!(spec.working_dir, root);
        assert_eq!(spec.args, vec![port_arg.clone()]);

        let invocation = build_powershell_maven_invocation(&spec.program, &spec.args);
        let escaped_program = escape_powershell_single_quoted(&spec.program.to_string_lossy());
        let escaped_port_arg = escape_powershell_single_quoted(&port_arg);

        assert!(
            invocation.contains(&format!(
                "& '{escaped_program}' '-pl' 'browser4-apps/browser4-agents' '-am' '-DskipTests' 'install' '-q'"
            )),
            "expected Maven preinstall phase in invocation: {invocation}"
        );
        assert!(
            invocation.contains("if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }"),
            "expected explicit Maven failure guard in invocation: {invocation}"
        );
        assert!(
            invocation.contains(&format!(
                "& '{escaped_program}' '-pl' 'browser4-apps/browser4-agents' 'spring-boot:run' '{escaped_port_arg}'"
            )),
            "expected module-scoped spring-boot:run phase in invocation: {invocation}"
        );
    }

    #[cfg(windows)]
    #[test]
    fn test_build_powershell_batch_invocation_escapes_dynamic_windows_paths() {
        let tmp = test_temp_dir();
        let wrapper_dir = tmp.path().join("team's tools");
        create_dir_all(&wrapper_dir).unwrap();
        let wrapper = wrapper_dir.join("mvnw.cmd");
        write(&wrapper, "@echo off\r\n").unwrap();
        let args = ["-Dflag=team's-value".to_string()];

        let invocation = build_powershell_batch_invocation(&wrapper, &args);

        assert_eq!(
            invocation,
            format!(
                "& '{}' '{}'",
                wrapper.to_string_lossy().replace('\'', "''"),
                args[0].replace('\'', "''")
            )
        );
    }

    #[cfg(windows)]
    #[test]
    fn test_normalize_windows_verbatim_path_for_java_drive_path() {
        let path = PathBuf::from(r"\\?\C:\temp\Browser4.jar");
        assert_eq!(
            normalize_windows_verbatim_path_for_java(&path),
            r"C:\temp\Browser4.jar"
        );
    }

    #[cfg(windows)]
    #[test]
    fn test_normalize_windows_verbatim_path_for_java_unc_path() {
        let path = PathBuf::from(r"\\?\UNC\server\share\Browser4.jar");
        assert_eq!(
            normalize_windows_verbatim_path_for_java(&path),
            r"\\server\share\Browser4.jar"
        );
    }

    #[cfg(not(windows))]
    #[test]
    fn test_build_maven_launch_spec_prefers_unix_wrapper() {
        let tmp = test_temp_dir();
        let root = create_browser4_root(&tmp);
        let wrapper = root.join("mvnw");
        write(&wrapper, "#!/usr/bin/env sh\n").unwrap();

        let spec = build_maven_launch_spec(&root, 8199).unwrap();

        assert_eq!(spec.kind, ServerLaunchKind::Maven);
        assert_eq!(spec.program, wrapper);
        assert_eq!(spec.working_dir, root);
        assert_eq!(
            spec.args,
            vec!["-Dspring-boot.run.arguments=--server.port=8199"]
        );
    }

    #[cfg(not(windows))]
    #[test]
    fn test_command_for_launch_spec_normalizes_unix_maven_wrapper() {
        let tmp = test_temp_dir();
        let root = create_browser4_root(&tmp);
        create_dir_all(root.join(".mvn").join("wrapper")).unwrap();
        let wrapper = root.join("mvnw");
        write(&wrapper, "#!/bin/sh\r\nset -eu\r\n").unwrap();
        write(
            root.join(".mvn").join("wrapper").join("maven-wrapper.properties"),
            "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.7/apache-maven-3.9.7-bin.zip\n",
        )
        .unwrap();

        let spec = build_maven_launch_spec(&root, 8199).unwrap();
        let prepared = command_for_launch_spec(&spec).unwrap();
        let cleanup_dir = prepared
            .cleanup_dir
            .clone()
            .expect("Unix wrapper launch should stage a normalized temp wrapper");
        let prepared_program = PathBuf::from(Path::new(prepared.command.get_program()));

        // The launcher script (not mvnw) is returned as the program
        assert_ne!(prepared_program, wrapper);
        assert_eq!(
            prepared_program.file_name().and_then(|name| name.to_str()),
            Some("browser4-start.sh")
        );
        assert_eq!(
            prepared.command.get_current_dir(),
            Some(spec.working_dir.as_path())
        );
        assert_eq!(
            prepared
                .command
                .get_args()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect::<Vec<_>>(),
            spec.args
        );

        // The normalized mvnw and .mvn symlink are staged in the same cleanup dir
        let staged_mvnw = cleanup_dir.join("mvnw");
        assert!(staged_mvnw.exists(), "normalized mvnw should be staged");
        assert_eq!(
            fs::read_to_string(&staged_mvnw).unwrap(),
            "#!/bin/sh\nset -eu\n"
        );
        assert!(cleanup_dir.join(".mvn").exists());

        // The launcher script should reference mvnw and both Maven phases
        let script_content = fs::read_to_string(&prepared_program).unwrap();
        assert!(
            script_content.contains("-am -DskipTests install"),
            "launcher script should contain install phase"
        );
        assert!(
            script_content.contains("spring-boot:run"),
            "launcher script should contain spring-boot:run"
        );

        cleanup_prepared_launch_dir(Some(cleanup_dir));
    }
}

trait Pipe: Sized {
    fn pipe<T, F: FnOnce(Self) -> T>(self, function: F) -> T {
        function(self)
    }
}

impl<T> Pipe for T {}
