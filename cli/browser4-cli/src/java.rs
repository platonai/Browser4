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

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_major_version_modern() {
        assert_eq!(parse_major_version("openjdk version \"17.0.10\" 2024-01-16"), Some(17));
        assert_eq!(parse_major_version("java version \"21.0.1\" 2023-10-17"), Some(21));
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
}
