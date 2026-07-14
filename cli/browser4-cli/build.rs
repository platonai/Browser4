//! Build script that reads the unified project version from the repo-root
//! `VERSION` file and exposes it as the `BROWSER4_CLI_VERSION` environment
//! variable so the compiled binary always reports the correct version.
//!
//! The script reads the version from `VERSION` at the repo root (at
//! `../../VERSION` relative to the Cargo manifest directory), strips any
//! `-SNAPSHOT` suffix, and falls back to `CARGO_PKG_VERSION` when the file
//! cannot be located.
//!
//! The repo-root `VERSION` file is the single source of truth for all
//! modules — CLI, backend Maven project, and npm package all share the
//! same version number.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn find_version_cli() -> Option<PathBuf> {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").ok()?);
    // The crate is at cli/browser4-cli, so the repo-root VERSION is ../../VERSION
    let candidate = manifest_dir.parent()?.parent()?.join("VERSION");
    if candidate.is_file() {
        // Tell Cargo to re-run this script when VERSION changes so the
        // binary stays up to date.
        println!("cargo:rerun-if-changed={}", candidate.display());
        Some(candidate)
    } else {
        None
    }
}

fn read_version(path: &std::path::Path) -> Option<String> {
    let raw = fs::read_to_string(path).ok()?;
    // VERSION is a plain-text file that contains the version on the
    // first non-empty line (e.g. "4.12.0-rc.1-SNAPSHOT").
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

// ---------------------------------------------------------------------------
// Skills data bundling
// ---------------------------------------------------------------------------

/// Find the `skills/` directory relative to the repo root.
/// The crate is at `cli/browser4-cli/`, so skills is at `../../skills/`.
fn find_skills_dir() -> Option<PathBuf> {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").ok()?);
    let candidate = manifest_dir
        .parent()?   // cli/
        .parent()?   // repo root
        .join("skills");
    if candidate.is_dir() {
        println!("cargo:rerun-if-changed={}", candidate.join("browser4-cli").display());
        Some(candidate)
    } else {
        None
    }
}

/// Recursively collect (skill_name, rel_path, content) for every file under
/// `skills_dir`.  `skill_name` is the top-level subdirectory name.
fn collect_skill_files(skills_dir: &Path) -> Vec<(String, String, String)> {
    let mut files = Vec::new();
    if !skills_dir.is_dir() {
        return files;
    }
    let entries = match fs::read_dir(skills_dir) {
        Ok(e) => e,
        Err(_) => return files,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let skill_name = match path.file_name().and_then(|n| n.to_str()) {
            Some(n) => n.to_string(),
            None => continue,
        };
        collect_files_recursive(skills_dir, &path, &skill_name, &mut files);
    }
    // Sort by skill name then relative path for deterministic output.
    files.sort_by(|a, b| a.0.cmp(&b.0).then_with(|| a.1.cmp(&b.1)));
    files
}

fn collect_files_recursive(skills_dir: &Path, dir: &Path, skill_name: &str, out: &mut Vec<(String, String, String)>) {
    let entries = match fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return,
    };
    let mut children: Vec<PathBuf> = entries
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .collect();
    children.sort();
    for path in children {
        if path.is_dir() {
            collect_files_recursive(skills_dir, &path, skill_name, out);
        } else if path.is_file() {
            // Compute path relative to the skills/ directory.
            let rel = match path.strip_prefix(skills_dir) {
                Ok(r) => r.to_string_lossy().replace('\\', "/"),
                Err(_) => continue,
            };
            match fs::read_to_string(&path) {
                Ok(content) => {
                    out.push((skill_name.to_string(), rel, content));
                }
                Err(e) => {
                    println!(
                        "cargo:warning=browser4-cli: could not read skill file {}: {e}",
                        path.display()
                    );
                }
            }
        }
    }
}

/// Escape a string for inclusion as a Rust string literal.
/// Handles backslash, double-quote, newline, carriage return, tab, and
/// control characters.
fn escape_for_rust_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for ch in s.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => {
                out.push_str(&format!("\\u{{{:x}}}", c as u32));
            }
            c => out.push(c),
        }
    }
    out
}

fn generate_skills_data(skills_dir: &Path, out_dir: &Path) {
    let files = collect_skill_files(skills_dir);
    if files.is_empty() {
        println!(
            "cargo:warning=browser4-cli: no skill files found under {}",
            skills_dir.display()
        );
    }

    let out_path = out_dir.join("skills_data.rs");
    let mut code = String::new();

    code.push_str("// Auto-generated by build.rs — DO NOT EDIT\n\n");

    if files.is_empty() {
        code.push_str("pub static SKILL_FILES: &[SkillFile] = &[];\n");
    } else {
        code.push_str("pub static SKILL_FILES: &[SkillFile] = &[\n");
        for (skill_name, rel_path, content) in &files {
            let escaped = escape_for_rust_string(content);
            code.push_str("    SkillFile {\n");
            code.push_str(&format!(
                "        skill_name: \"{}\",\n", skill_name
            ));
            code.push_str(&format!(
                "        rel_path: \"{}\",\n", rel_path
            ));
            code.push_str(&format!(
                "        content: \"{}\",\n", escaped
            ));
            code.push_str("    },\n");
        }
        code.push_str("];\n");
    }

    match fs::write(&out_path, &code) {
        Ok(_) => {
            println!(
                "cargo:warning=browser4-cli: embedded {} skill files from {}",
                files.len(),
                skills_dir.display()
            );
        }
        Err(e) => {
            println!(
                "cargo:warning=browser4-cli: failed to write skills_data.rs: {e}"
            );
            // Write a fallback empty file so compilation doesn't fail.
            let fallback = "pub static SKILL_FILES: &[SkillFile] = &[];\n";
            let _ = fs::write(&out_path, fallback);
        }
    }
}

fn main() {
    let version = match find_version_cli().and_then(|path| read_version(&path)) {
        Some(v) => v,
        None => {
            // Fall back to Cargo.toml version when the repo-root VERSION
            // cannot be found (e.g. when the crate is built outside of the monorepo).
            println!(
                "cargo:warning=browser4-cli: VERSION not found, falling back to CARGO_PKG_VERSION"
            );
            env::var("CARGO_PKG_VERSION").unwrap_or_else(|_| "0.0.0-unknown".to_string())
        }
    };

    println!("cargo:rustc-env=BROWSER4_CLI_VERSION={version}");

    // Bundle skill files into the binary.
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap_or_else(|_| ".".to_string()));
    if let Some(skills_dir) = find_skills_dir() {
        generate_skills_data(&skills_dir, &out_dir);
    } else {
        println!(
            "cargo:warning=browser4-cli: skills directory not found — binary will have no bundled skills"
        );
        // Write an empty fallback so compilation succeeds.
        let out_path = out_dir.join("skills_data.rs");
        let _ = fs::write(
            &out_path,
            "pub static SKILL_FILES: &[SkillFile] = &[];\n",
        );
    }
}
