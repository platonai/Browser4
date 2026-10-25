//! Java discovery — shared by the Browser4 backend runtime launcher
//! (`daemon`) and the webminer (scent-miner) integration.
//!
//! [`find_java17`] is the single search chain used whenever the CLI needs a
//! Java 17+ runtime:
//!
//! 1. `JAVA_HOME` (explicit user preference)
//! 2. The JRE bundled with the active Browser4 runtime
//!    (`browser4-cli install`), which requires no extra setup
//! 3. Common install locations (OpenLogic / Adoptium JDK 17 defaults)
//! 4. `java` on `PATH`
//!
//! Every candidate is verified by actually running `java -version` and
//! parsing the major version (must be >= 17), so an unusable JDK is skipped
//! instead of failing later with a confusing error.
//!
//! [`find_build_jdk`] is a separate chain for the **source build**: the root
//! pom's Maven enforcer requires JDK 25+, so inheriting whatever `java` sits
//! on `PATH` (often an older JRE) fails the bundle rebuild with an enforcer
//! error.  It searches `JAVA_HOME` first, then the platform's JDK install
//! roots (highest version wins), then `java` on `PATH`.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// Minimum JDK major version required to build Browser4 from source.
///
/// Mirrors `<javaVersion>` / the `maven-enforcer-plugin` `RequireJavaVersion`
/// rule in the root `pom.xml`, which rejects older JDKs.
pub const BUILD_JDK_MIN_MAJOR: i32 = 25;

/// Platform java executable name (`java.exe` on Windows, `java` elsewhere).
pub fn executable_name() -> &'static str {
    if cfg!(windows) {
        "java.exe"
    } else {
        "java"
    }
}

/// Parse the major Java version from `java -version` output.
///
/// Handles modern (`17.0.10`) and legacy (`1.8.0_392`) version strings;
/// legacy `1.8` is reported as `8`.  Lines that do not contain a version
/// (e.g. `Picked up JAVA_TOOL_OPTIONS: ...` emitted on stderr before the
/// version line) are skipped.
pub fn parse_major_version(output: &str) -> Option<i32> {
    for line in output.lines() {
        let Some(idx) = line.find("version \"") else {
            continue;
        };
        let rest = &line[idx + "version \"".len()..];
        let ver = rest.split('"').next()?;
        let mut parts = ver.split('.');
        let major = parts.next()?.parse::<i32>().ok()?;
        if major == 1 {
            // Legacy 1.8 → 8
            return parts.next().and_then(|s| s.parse::<i32>().ok());
        }
        return Some(major);
    }
    None
}

/// Run `java -version` for the given executable and parse the major version.
///
/// `java -version` writes its output to stderr, which is captured here.
pub fn major_version(java_exe: &Path) -> Result<i32, String> {
    let output = Command::new(java_exe)
        .arg("-version")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output()
        .map_err(|e| format!("Failed to run {}: {e}", java_exe.display()))?;
    let text = String::from_utf8_lossy(&output.stderr);
    parse_major_version(&text).ok_or_else(|| {
        format!(
            "Cannot parse version output of {}: {}",
            java_exe.display(),
            text.trim()
        )
    })
}

/// True when the executable exists and reports a Java version >= 17.
fn is_java17_or_newer(java_exe: &Path) -> bool {
    java_exe.is_file() && major_version(java_exe).map(|v| v >= 17).unwrap_or(false)
}

/// Path of the JRE bundled inside a runtime install directory
/// (`<install-dir>/runtime/bin/java[.exe]`).
pub fn java_in_install_dir(install_dir: &Path) -> PathBuf {
    install_dir
        .join("runtime")
        .join("bin")
        .join(executable_name())
}

/// The JRE bundled with the active Browser4 runtime
/// (`<runtime-data>/runtime/<tag>/runtime/bin/java[.exe]`), installed by
/// `browser4-cli install`.  `None` when no runtime is installed.
pub fn runtime_bundle_java() -> Option<PathBuf> {
    let tag = crate::daemon::read_current_tag()?;
    let exe = java_in_install_dir(
        &crate::state::resolve_runtime_data_dir()
            .join("runtime")
            .join(tag),
    );
    exe.is_file().then_some(exe)
}

/// Locate a Java 17+ executable (absolute path when known, `java` on PATH).
///
/// Search order: `JAVA_HOME` → Browser4 runtime bundle JRE → common install
/// locations → `java` on PATH.
pub fn find_java17() -> Result<PathBuf, String> {
    let name = executable_name();

    // 1. JAVA_HOME
    if let Ok(java_home) = std::env::var("JAVA_HOME") {
        let java_home = java_home.trim().to_string();
        if !java_home.is_empty() {
            let exe = Path::new(&java_home).join("bin").join(name);
            if is_java17_or_newer(&exe) {
                return Ok(exe);
            }
        }
    }

    // 2. Browser4 runtime bundle JRE (installed by `browser4-cli install`) —
    // a known-good JDK that requires no extra setup.
    if let Some(exe) = runtime_bundle_java() {
        if is_java17_or_newer(&exe) {
            return Ok(exe);
        }
    }

    // 3. Common install locations
    let candidates: &[&str] = if cfg!(windows) {
        &[
            r"D:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot",
            r"C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot",
            r"D:\Program Files\Java\jdk-17",
            r"C:\Program Files\Java\jdk-17",
            r"D:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot",
            r"C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot",
        ]
    } else {
        &[
            "/usr/lib/jvm/java-17-openjdk",
            "/usr/lib/jvm/java-17-openjdk-amd64",
            "/usr/lib/jvm/jdk-17",
            "/usr/local/lib/jvm/jdk-17",
        ]
    };
    for home in candidates {
        let exe = Path::new(home).join("bin").join(name);
        if is_java17_or_newer(&exe) {
            return Ok(exe);
        }
    }

    // 4. `java` on PATH
    if let Ok(major) = major_version(Path::new(name)) {
        if major >= 17 {
            return Ok(PathBuf::from(name));
        }
    }

    Err("No Java 17+ installation found.\nSet JAVA_HOME to a Java 17+ JDK, install the Browser4 runtime (`browser4-cli install`), or add a JDK 17+ to PATH (https://adoptium.net/).".to_string())
}

/// Platform javac executable name (`javac.exe` on Windows, `javac` elsewhere).
fn javac_name() -> &'static str {
    if cfg!(windows) {
        "javac.exe"
    } else {
        "javac"
    }
}

/// Version of the JDK at `home`, or `None` when the directory is not a JDK.
///
/// A usable build JDK must ship `javac`; a plain JRE is rejected even when its
/// `java -version` is new enough, because Maven's compiler plugin would fail.
fn jdk_version_at(home: &Path) -> Option<i32> {
    let bin = home.join("bin");
    if !bin.join(javac_name()).is_file() {
        return None;
    }
    let java = bin.join(executable_name());
    if !java.is_file() {
        return None;
    }
    major_version(&java).ok()
}

/// Normalise a directory that may be a macOS JDK bundle into its JDK home.
fn jdk_home_candidate(dir: &Path) -> PathBuf {
    if cfg!(target_os = "macos") {
        let bundled = dir.join("Contents").join("Home");
        if bundled.is_dir() {
            return bundled;
        }
    }
    dir.to_path_buf()
}

/// Directories whose immediate children may be JDK homes, per platform.
fn jdk_search_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();
    if cfg!(windows) {
        for var in ["ProgramFiles", "ProgramFiles(x86)"] {
            if let Some(base) = env::var_os(var) {
                for sub in [
                    "Java",
                    "Eclipse Adoptium",
                    "Microsoft",
                    "Zulu",
                    "Corretto",
                    "OpenLogic",
                    "BellSoft",
                ] {
                    roots.push(PathBuf::from(&base).join(sub));
                }
            }
        }
        if let Some(base) = env::var_os("SystemDrive") {
            for sub in ["Java", r"Program Files\Java"] {
                roots.push(PathBuf::from(&base).join(sub));
            }
        }
    } else if cfg!(target_os = "macos") {
        roots.push(PathBuf::from("/Library/Java/JavaVirtualMachines"));
    } else {
        for root in ["/usr/lib/jvm", "/usr/java", "/opt/java", "/opt/jdk"] {
            roots.push(PathBuf::from(root));
        }
    }
    if let Some(home) = env::var_os("HOME").or_else(|| env::var_os("USERPROFILE")) {
        let home = PathBuf::from(home);
        roots.push(home.join(".sdkman").join("candidates").join("java"));
        roots.push(home.join(".jdks"));
        roots.push(home.join(".asdf").join("installs").join("java"));
    }
    roots
}

/// Highest-versioned valid JDK among `candidates` (only version >= `min_major`).
fn best_jdk_among(
    candidates: impl IntoIterator<Item = PathBuf>,
    min_major: i32,
) -> Option<PathBuf> {
    let mut best: Option<(PathBuf, i32)> = None;
    for candidate in candidates {
        let home = jdk_home_candidate(&candidate);
        let Some(version) = jdk_version_at(&home) else {
            continue;
        };
        if version < min_major {
            continue;
        }
        if best.as_ref().map(|(_, v)| version > *v).unwrap_or(true) {
            best = Some((home, version));
        }
    }
    best.map(|(home, _)| home)
}

/// Highest-versioned valid JDK >= `min_major` among the platform install roots.
fn best_installed_jdk(min_major: i32) -> Option<PathBuf> {
    let mut candidates = Vec::new();
    for root in jdk_search_roots() {
        let Ok(entries) = fs::read_dir(&root) else {
            continue;
        };
        for entry in entries.flatten() {
            if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                candidates.push(entry.path());
            }
        }
    }
    best_jdk_among(candidates, min_major)
}

/// Resolve a `java` on `PATH` back to its JDK home (following symlinks).
fn jdk_home_on_path(min_major: i32) -> Option<PathBuf> {
    let path = env::var_os("PATH")?;
    for dir in env::split_paths(&path) {
        let exe = dir.join(executable_name());
        if !exe.is_file() {
            continue;
        }
        if major_version(&exe).map(|v| v < min_major).unwrap_or(true) {
            continue;
        }
        // `/usr/bin/java` and friends are shims/symlinks; the JDK home is the
        // parent of the directory holding the real executable.
        let resolved = fs::canonicalize(&exe).unwrap_or(exe);
        if let Some(home) = resolved.parent().and_then(Path::parent) {
            if jdk_version_at(home)
                .map(|v| v >= min_major)
                .unwrap_or(false)
            {
                return Some(home.to_path_buf());
            }
        }
    }
    None
}

/// Locate a JDK home (with `javac`) of at least `min_major`.
///
/// Search order: `JAVA_HOME` (when new enough) → common install roots (highest
/// version wins) → `java` on `PATH`.
pub fn find_jdk_home(min_major: i32) -> Result<PathBuf, String> {
    // 1. JAVA_HOME — the explicit user preference, when it satisfies the build.
    if let Ok(configured) = env::var("JAVA_HOME") {
        let configured = configured.trim();
        if !configured.is_empty() {
            let home = PathBuf::from(configured);
            if jdk_version_at(&home)
                .map(|v| v >= min_major)
                .unwrap_or(false)
            {
                return Ok(home);
            }
        }
    }

    // 2. Installed JDKs (highest version wins).
    if let Some(home) = best_installed_jdk(min_major) {
        return Ok(home);
    }

    // 3. `java` on PATH.
    if let Some(home) = jdk_home_on_path(min_major) {
        return Ok(home);
    }

    Err(format!(
        "No JDK {min_major}+ found to build Browser4 from source \
         (the root pom's Maven enforcer requires JDK {min_major}+).\n\
         Set JAVA_HOME to a JDK {min_major}+ home or install one, for example:\n\
         \x20 export JAVA_HOME=/usr/lib/jvm/java-{min_major}-openjdk-amd64   # Linux\n\
         \x20 $env:JAVA_HOME = \"C:\\Program Files\\Java\\jdk-{min_major}\"   # Windows\n\
         Searched JAVA_HOME, the platform JDK install roots and `java` on PATH."
    ))
}

/// Locate a JDK home that satisfies the source build (`BUILD_JDK_MIN_MAJOR`).
///
/// Warns when `JAVA_HOME` points at a JDK older than required, since the build
/// deliberately uses a different (newer) JDK from the install roots.
pub fn find_build_jdk() -> Result<PathBuf, String> {
    let home = find_jdk_home(BUILD_JDK_MIN_MAJOR)?;
    if let Ok(configured) = env::var("JAVA_HOME") {
        let configured = configured.trim();
        if !configured.is_empty() && !same_path(Path::new(configured), &home) {
            let found = jdk_version_at(Path::new(configured))
                .map(|v| format!("JDK {v}"))
                .unwrap_or_else(|| "not a JDK".to_string());
            eprintln!(
                "JAVA_HOME is {configured} ({found}); the source build needs JDK \
                 {BUILD_JDK_MIN_MAJOR}+, using {} instead.",
                home.display()
            );
        }
    }
    Ok(home)
}

fn same_path(left: &Path, right: &Path) -> bool {
    match (fs::canonicalize(left), fs::canonicalize(right)) {
        (Ok(left), Ok(right)) => left == right,
        _ => left == right,
    }
}

/// Point a child process at `jdk_home`: set `JAVA_HOME` and put its `bin`
/// directory first on `PATH`, so the Maven wrapper and any tool it spawns use
/// the same JDK.
pub fn set_java_home(command: &mut Command, jdk_home: &Path) {
    command.env("JAVA_HOME", jdk_home);
    let mut entries = vec![jdk_home.join("bin")];
    if let Some(path) = env::var_os("PATH") {
        entries.extend(env::split_paths(&path));
    }
    if let Ok(joined) = env::join_paths(entries) {
        command.env("PATH", joined);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_major_version_modern() {
        assert_eq!(
            parse_major_version("openjdk version \"17.0.10\" 2024-01-16"),
            Some(17)
        );
        assert_eq!(
            parse_major_version("java version \"21.0.1\" 2023-10-17"),
            Some(21)
        );
    }

    #[test]
    fn parse_major_version_skips_preamble_lines() {
        // `java -version` on stderr may be preceded by lines such as
        // "Picked up JAVA_TOOL_OPTIONS: ..." — those must be skipped, not
        // treated as a parse failure.
        let output = "Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8\n\
                      openjdk version \"17.0.14\" 2025-01-21\n\
                      OpenJDK Runtime Environment OpenLogic-OpenJDK (build 17.0.14+7-adhoc..jdk17u)\n\
                      OpenJDK 64-Bit Server VM OpenLogic-OpenJDK (build 17.0.14+7-adhoc..jdk17u, mixed mode, sharing)";
        assert_eq!(parse_major_version(output), Some(17));
    }

    #[test]
    fn parse_major_version_legacy() {
        assert_eq!(parse_major_version("java version \"1.8.0_392\""), Some(8));
    }

    #[test]
    fn parse_major_version_garbage() {
        assert_eq!(parse_major_version("not a version"), None);
        assert_eq!(parse_major_version(""), None);
    }

    #[test]
    fn executable_name_is_platform_aware() {
        let name = executable_name();
        assert!(name == "java.exe" || name == "java");
    }

    #[test]
    fn java_in_install_dir_layout() {
        let path = java_in_install_dir(Path::new(r"C:\browser4\runtime\v4.13.7"));
        let comps: Vec<_> = path.components().collect();
        assert_eq!(comps[comps.len() - 3].as_os_str(), "runtime");
        assert_eq!(comps[comps.len() - 2].as_os_str(), "bin");
        assert_eq!(comps[comps.len() - 1].as_os_str(), executable_name());
    }

    #[test]
    fn set_java_home_exports_home_and_prepends_bin_to_path() {
        let mut command = Command::new(executable_name());
        set_java_home(&mut command, Path::new("/opt/jdk-25"));

        let envs: Vec<(String, Option<String>)> = command
            .get_envs()
            .map(|(key, value)| {
                (
                    key.to_string_lossy().into_owned(),
                    value.map(|v| v.to_string_lossy().into_owned()),
                )
            })
            .collect();

        let java_home = envs
            .iter()
            .find(|(key, _)| key == "JAVA_HOME")
            .and_then(|(_, value)| value.as_deref());
        assert_eq!(java_home, Some("/opt/jdk-25"));

        let path = envs
            .iter()
            .find(|(key, _)| key == "PATH")
            .and_then(|(_, value)| value.as_deref())
            .expect("PATH should be exported");
        let first = env::split_paths(path).next().expect("PATH entry");
        assert_eq!(first, Path::new("/opt/jdk-25").join("bin"));
    }

    #[test]
    fn jdk_version_at_rejects_a_jre_without_javac() {
        let tmp = tempfile::tempdir().unwrap();
        let bin = tmp.path().join("bin");
        fs::create_dir_all(&bin).unwrap();
        fs::write(bin.join(executable_name()), "").unwrap();

        assert_eq!(jdk_version_at(tmp.path()), None);
    }

    #[test]
    fn jdk_home_candidate_unwraps_a_macos_bundle_only() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path().join("jdk-25");
        let expected = if cfg!(target_os = "macos") {
            let home = dir.join("Contents").join("Home");
            fs::create_dir_all(&home).unwrap();
            home
        } else {
            dir.clone()
        };
        assert_eq!(jdk_home_candidate(&dir), expected);
    }

    #[cfg(unix)]
    fn write_fake_jdk(root: &Path, version: &str) {
        use std::os::unix::fs::PermissionsExt;

        let bin = root.join("bin");
        fs::create_dir_all(&bin).unwrap();
        let java = bin.join("java");
        fs::write(
            &java,
            format!("#!/bin/sh\necho 'openjdk version \"{version}\" 2026-01-01' >&2\n"),
        )
        .unwrap();
        let mut permissions = fs::metadata(&java).unwrap().permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&java, permissions).unwrap();
        fs::write(bin.join("javac"), "").unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn best_jdk_among_picks_the_newest_jdk_that_meets_the_floor() {
        let tmp = tempfile::tempdir().unwrap();
        let jdk17 = tmp.path().join("jdk-17");
        let jdk21 = tmp.path().join("jdk-21");
        let jdk25 = tmp.path().join("jdk-25");
        write_fake_jdk(&jdk17, "17.0.10");
        write_fake_jdk(&jdk21, "21.0.1");
        write_fake_jdk(&jdk25, "25.0.1");

        let candidates = vec![jdk17.clone(), jdk21.clone(), jdk25.clone()];
        assert_eq!(best_jdk_among(candidates.clone(), 25), Some(jdk25.clone()));
        assert_eq!(best_jdk_among(candidates.clone(), 21), Some(jdk25));
        assert_eq!(best_jdk_among(vec![jdk17], 25), None);
    }
}
