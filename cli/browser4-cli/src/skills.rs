//! Bundled skill files management.
//!
//! Skill files are embedded into the binary at compile time via `build.rs`.
//! Functions in this module let you list, retrieve, and unpack those files.

use std::collections::BTreeSet;
use std::path::PathBuf;

/// One embedded skill file bundled at compile time.
pub struct SkillFile {
    pub skill_name: &'static str,
    pub rel_path: &'static str,
    pub content: &'static str,
}

// The generated file is produced by build.rs and lives in OUT_DIR.
include!(concat!(env!("OUT_DIR"), "/skills_data.rs"));

/// List the names of all bundled skills (deduplicated).
pub fn list_skill_names() -> Vec<String> {
    let mut names = BTreeSet::new();
    for f in SKILL_FILES {
        names.insert(f.skill_name.to_string());
    }
    names.into_iter().collect()
}

/// Return the bundled files belonging to `skill_name`.
pub fn skill_files_for(skill_name: &str) -> Vec<&SkillFile> {
    SKILL_FILES
        .iter()
        .filter(|f| f.skill_name == skill_name)
        .collect()
}

/// Return all bundled files in (skill_name, rel_path, content) order.
#[allow(dead_code)]
pub fn all_skill_files() -> &'static [SkillFile] {
    SKILL_FILES
}

/// Get the full content of a skill.
///
/// When `full` is true, includes all bundled files for the skill (references
/// and extra docs), concatenated with headers.  When `full` is false, returns
/// only the SKILL.md content.
pub fn get_skill(name: &str, full: bool) -> Option<String> {
    let files = skill_files_for(name);
    if files.is_empty() {
        return None;
    }
    if !full {
        // Return only SKILL.md content.
        files
            .iter()
            .find(|f| f.rel_path == format!("{}/SKILL.md", name))
            .or_else(|| files.first())
            .map(|f| f.content.to_string())
    } else {
        // Concatenate all files with markers.
        let mut out = String::new();
        for f in &files {
            if !out.is_empty() {
                out.push_str("\n\n");
            }
            out.push_str(&format!("--- {} ---\n", f.rel_path));
            out.push_str(f.content);
        }
        Some(out)
    }
}

/// Get all skills concatenated together, each prefixed with a header.
pub fn get_all_skills() -> String {
    let names = list_skill_names();
    if names.is_empty() {
        return "No skills bundled.\n".to_string();
    }
    let mut out = String::new();
    for name in &names {
        if !out.is_empty() {
            out.push_str("\n\n");
        }
        out.push_str(&format!("========== {} ==========\n", name));
        if let Some(content) = get_skill(name, true) {
            out.push_str(&content);
        }
    }
    out
}

/// Resolve the skills directory path.
///
/// Honours `BROWSER4_SKILLS_DIR` as an override.  When set and non-empty,
/// that path is used directly.  Otherwise returns the default skills
/// directory under the runtime data directory.
///
/// Note: `browser4-cli install` unpacks skills into the versioned runtime
/// installation directory, which may differ from this default.  Callers that
/// know the current version tag should use `versioned_install_dir(tag)/skills`
/// instead.
pub fn get_skills_dir() -> PathBuf {
    if let Ok(override_dir) = std::env::var("BROWSER4_SKILLS_DIR") {
        let trimmed = override_dir.trim().to_string();
        if !trimmed.is_empty() {
            // Reject values that look like CLI flags.
            if trimmed.starts_with('-') {
                eprintln!(
                    "browser4-cli: ignoring BROWSER4_SKILLS_DIR=\"{}\" — \
                    directory names that start with '-' are not allowed. \
                    Using default skills directory.",
                    trimmed
                );
            } else {
                return PathBuf::from(&trimmed)
                    .canonicalize()
                    .unwrap_or_else(|_| PathBuf::from(trimmed));
            }
        }
    }
    crate::state::resolve_runtime_data_dir().join("skills")
}

/// Unpack all bundled skill files to `dest_dir`.
///
/// Creates the directory structure and writes each file.  Returns the number
/// of files written.
pub fn unpack_skills_to(dest_dir: &std::path::Path) -> Result<usize, String> {
    let mut count = 0;
    for f in SKILL_FILES {
        let file_path = dest_dir.join(&f.rel_path);
        if let Some(parent) = file_path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| {
                format!("Failed to create directory {}: {e}", parent.display())
            })?;
        }
        std::fs::write(&file_path, f.content).map_err(|e| {
            format!("Failed to write {}: {e}", file_path.display())
        })?;
        count += 1;
    }
    Ok(count)
}
