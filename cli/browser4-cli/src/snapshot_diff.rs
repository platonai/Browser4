//! Snapshot diff engine — compare two accessibility-tree YAML snapshots and
//! produce a unified diff showing added, removed, and modified elements.
//!
//! Elements are matched by (role, accessible-name, bounding-box proximity)
//! using a greedy bipartite matching algorithm with configurable tolerance.

use std::collections::HashMap;
use std::path::PathBuf;

/// A parsed node from the accessibility-tree YAML.
#[derive(Debug, Clone)]
#[allow(dead_code)]
struct SnapNode {
    /// Indentation depth (0-based)
    depth: usize,
    /// Role: "button", "link", "textbox", "generic", etc.
    role: String,
    /// Accessible name (the quoted text after the role)
    name: String,
    /// Element ref, e.g. "e42"
    ref_id: String,
    /// Bounding box parsed from `[box=x,y,w,h]`
    box_rect: Option<BoxRect>,
    /// URL property value (`/url: ...`)
    url: Option<String>,
    /// Value property value (`/value: ...`) — textbox/input content
    value: Option<String>,
    /// Text content (`"text content"` at end of line or on text child)
    text: Option<String>,
    /// Original line prefix for rendering diff
    raw_line: String,
}

#[derive(Debug, Clone)]
struct BoxRect {
    x: f64,
    y: f64,
    w: f64,
    h: f64,
}

/// A single diff entry.
#[derive(Debug)]
enum DiffEntry {
    Added(SnapNode),
    Removed(SnapNode),
    Modified {
        before: SnapNode,
        after: SnapNode,
        changes: Vec<String>,
    },
}

/// Matching tolerance for box proximity (pixels).
const BOX_TOLERANCE_X: f64 = 15.0;
const BOX_TOLERANCE_Y: f64 = 15.0;
const BOX_TOLERANCE_W: f64 = 25.0;
const BOX_TOLERANCE_H: f64 = 25.0;

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/// Run a diff between two snapshot files and return a formatted string.
pub fn diff_snapshots(before_path: &PathBuf, after_path: &PathBuf) -> String {
    let before_content = match std::fs::read_to_string(before_path) {
        Ok(c) => c,
        Err(e) => return format!("# Cannot read {}: {}", before_path.display(), e),
    };
    let after_content = match std::fs::read_to_string(after_path) {
        Ok(c) => c,
        Err(e) => return format!("# Cannot read {}: {}", after_path.display(), e),
    };

    let before_nodes = parse_snapshot(&before_content);
    let after_nodes = parse_snapshot(&after_content);

    let diffs = compute_diff(&before_nodes, &after_nodes);
    format_diff_output(&diffs, before_path, after_path)
}

/// Find the most recent snapshot file in the snapshot directory that isn't the
/// given one. Returns `None` if no other snapshot exists.
pub fn find_previous_snapshot(exclude: &PathBuf) -> Option<PathBuf> {
    let dir = exclude.parent()?;
    if !dir.is_dir() {
        return None;
    }

    let mut entries: Vec<(PathBuf, std::time::SystemTime)> = std::fs::read_dir(dir)
        .ok()?
        .filter_map(|e| e.ok())
        .filter(|e| {
            e.path().extension().map_or(false, |ext| ext == "yml")
                && e.path() != *exclude
        })
        .filter_map(|e| {
            let modified = e.metadata().ok()?.modified().ok()?;
            Some((e.path(), modified))
        })
        .collect();

    entries.sort_by(|a, b| b.1.cmp(&a.1));
    entries.into_iter().next().map(|(p, _)| p)
}

// ---------------------------------------------------------------------------
// YAML parser
// ---------------------------------------------------------------------------

fn parse_snapshot(content: &str) -> Vec<SnapNode> {
    let mut nodes = Vec::new();
    let mut pending_props: HashMap<usize, (Option<String>, Option<String>)> = HashMap::new();
    // Maps: indent → (url, value)

    let lines: Vec<&str> = content.lines().collect();
    let mut i = 0;

    while i < lines.len() {
        let line = lines[i];
        let trimmed = line.trim();

        // Skip comment lines, empty lines, and document separators
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed == "---" {
            i += 1;
            continue;
        }

        let indent = line.chars().take_while(|c| c.is_whitespace()).count();

        // Property lines — store by their indent so the following nodes can pick them up
        if trimmed.starts_with("- /") {
            if let Some(rest) = trimmed.strip_prefix("- /") {
                if let Some((prop, val)) = rest.split_once(':') {
                    let val = val.trim().trim_matches('"');
                    let entry = pending_props.entry(indent).or_insert((None, None));
                    match prop.trim() {
                        "url" => { entry.0 = Some(val.to_string()); }
                        "value" => { entry.1 = Some(val.to_string()); }
                        _ => {}
                    }
                }
            }
            i += 1;
            continue;
        }

        // Node line: `- role "name" [attrs...]:`
        if !trimmed.starts_with("- ") || trimmed.starts_with("- /") {
            i += 1;
            continue;
        }

        let body = trimmed.strip_prefix("- ").unwrap();
        // Strip trailing ":"
        let body = body.strip_suffix(':').unwrap_or(body);

        // Parse role (first word)
        let parts: Vec<&str> = body.splitn(2, ' ').collect();
        let role = parts[0].to_string();
        let rest = if parts.len() > 1 { parts[1].trim() } else { "" };

        // Parse name (quoted string, if present)
        let (name, rest) = parse_quoted_name(rest);

        // Parse bracketed attributes
        let bracketed = parse_bracketed_attrs(rest);
        let ref_id = bracketed.get("ref").cloned().unwrap_or_default();
        let box_rect = bracketed.get("box").and_then(|s| parse_box(s));

        // Scan ahead for properties that belong to this node's children
        // (at indent + 2). Stop at the next node at the same or lower indent.
        let child_indent = indent + 2;
        let mut url = None;
        let mut value = None;

        // First check if we already accumulated these from earlier
        if let Some((u, v)) = pending_props.remove(&child_indent) {
            url = url.or(u);
            value = value.or(v);
        }

        // Also scan ahead for properties immediately following this node
        let mut j = i + 1;
        while j < lines.len() {
            let next_line = lines[j];
            let next_trimmed = next_line.trim();
            if next_trimmed.is_empty() || next_trimmed.starts_with('#') {
                j += 1;
                continue;
            }
            let next_indent = next_line.chars().take_while(|c| c.is_whitespace()).count();

            // Stop if we hit another node at same or lower indent
            if next_indent <= indent && next_trimmed.starts_with("- ") && !next_trimmed.starts_with("- /") {
                break;
            }

            if next_indent == child_indent && next_trimmed.starts_with("- /") {
                if let Some(rest) = next_trimmed.strip_prefix("- /") {
                    if let Some((prop, val)) = rest.split_once(':') {
                        let val = val.trim().trim_matches('"');
                        match prop.trim() {
                            "url" => { url = Some(val.to_string()); }
                            "value" => { value = Some(val.to_string()); }
                            _ => {}
                        }
                    }
                }
            }
            j += 1;
        }

        let node = SnapNode {
            depth: indent / 2,
            role,
            name,
            ref_id,
            box_rect,
            url,
            value,
            text: None,
            raw_line: line.to_string(),
        };
        nodes.push(node);
        i += 1;
    }

    nodes
}

/// Parse a double-quoted name from the start of a string.
/// Returns (name, remaining_text).
fn parse_quoted_name(s: &str) -> (String, &str) {
    let s = s.trim_start();
    if !s.starts_with('"') {
        return (String::new(), s);
    }

    let mut chars = s[1..].char_indices();
    let mut escaped = false;
    let mut end = 0;

    while let Some((i, c)) = chars.next() {
        if escaped {
            escaped = false;
            continue;
        }
        if c == '\\' {
            escaped = true;
            continue;
        }
        if c == '"' {
            end = i + 2; // +2 for the opening quote and this char
            break;
        }
    }

    if end == 0 {
        return (String::new(), s);
    }

    let name = &s[1..end - 1]; // strip quotes
    // Unescape common escapes
    let name = name.replace("\\\"", "\"").replace("\\\\", "\\");
    let rest = &s[end..];
    (name, rest)
}

/// Parse all `[key=value]` bracketed attributes from a string.
fn parse_bracketed_attrs(s: &str) -> HashMap<String, String> {
    let mut attrs = HashMap::new();
    let s = s.trim();

    let mut i = 0;
    let bytes = s.as_bytes();
    while i < bytes.len() {
        if bytes[i] == b'[' {
            let start = i + 1;
            if let Some(end) = s[start..].find(']') {
                let inner = &s[start..start + end];
                if let Some((key, val)) = inner.split_once('=') {
                    attrs.insert(key.trim().to_string(), val.trim().to_string());
                } else {
                    // Boolean flag like [disabled] — key only
                    attrs.insert(inner.trim().to_string(), String::new());
                }
                i = start + end + 1;
            } else {
                break;
            }
        } else {
            i += 1;
        }
    }
    attrs
}

/// Parse a box string "x,y,w,h" into a BoxRect.
fn parse_box(s: &str) -> Option<BoxRect> {
    let parts: Vec<&str> = s.split(',').collect();
    if parts.len() != 4 {
        return None;
    }
    Some(BoxRect {
        x: parts[0].trim().parse().unwrap_or(0.0),
        y: parts[1].trim().parse().unwrap_or(0.0),
        w: parts[2].trim().parse().unwrap_or(0.0),
        h: parts[3].trim().parse().unwrap_or(0.0),
    })
}

// ---------------------------------------------------------------------------
// Diff engine
// ---------------------------------------------------------------------------

fn compute_diff(before: &[SnapNode], after: &[SnapNode]) -> Vec<DiffEntry> {
    let mut entries = Vec::new();
    let mut after_matched: Vec<bool> = vec![false; after.len()];

    for b_node in before {
        let mut best_match: Option<(usize, f64)> = None;

        for (j, a_node) in after.iter().enumerate() {
            if after_matched[j] {
                continue;
            }
            let score = match_score(b_node, a_node);
            if score > 0.5 {
                // Threshold for considering it a match
                match &mut best_match {
                    None => best_match = Some((j, score)),
                    Some((_, best_score)) if score > *best_score => {
                        best_match = Some((j, score));
                    }
                    _ => {}
                }
            }
        }

        match best_match {
            Some((j, _score)) => {
                after_matched[j] = true;
                let changes = compute_changes(b_node, &after[j]);
                if changes.is_empty() {
                    // No changes — don't emit anything for identical elements
                } else {
                    entries.push(DiffEntry::Modified {
                        before: b_node.clone(),
                        after: after[j].clone(),
                        changes,
                    });
                }
            }
            None => {
                entries.push(DiffEntry::Removed(b_node.clone()));
            }
        }
    }

    // Everything in `after` that wasn't matched is newly added
    for (j, a_node) in after.iter().enumerate() {
        if !after_matched[j] {
            entries.push(DiffEntry::Added(a_node.clone()));
        }
    }

    entries
}

/// Score how well two nodes match (0.0 = no match, 1.0 = perfect match).
fn match_score(a: &SnapNode, b: &SnapNode) -> f64 {
    // Role must match exactly
    if a.role != b.role {
        return 0.0;
    }

    let mut score = 0.5; // base score for matching role

    // Name match (fuzzy)
    if !a.name.is_empty() && !b.name.is_empty() {
        if a.name == b.name {
            score += 0.3;
        } else if a.name.contains(&b.name) || b.name.contains(&a.name) {
            score += 0.15;
        }
    } else if a.name.is_empty() && b.name.is_empty() {
        score += 0.1; // both anonymous
    }

    // Box match
    if let (Some(ba), Some(bb)) = (&a.box_rect, &b.box_rect) {
        let dx = (ba.x - bb.x).abs();
        let dy = (ba.y - bb.y).abs();
        let dw = (ba.w - bb.w).abs();
        let dh = (ba.h - bb.h).abs();

        if dx <= BOX_TOLERANCE_X && dy <= BOX_TOLERANCE_Y {
            score += 0.1;
            if dw <= BOX_TOLERANCE_W && dh <= BOX_TOLERANCE_H {
                score += 0.1;
            }
        }
    }

    score
}

/// Compute what changed between two matched nodes.
fn compute_changes(before: &SnapNode, after: &SnapNode) -> Vec<String> {
    let mut changes = Vec::new();

    // Value changes (most important for fill/type verification)
    match (&before.value, &after.value) {
        (None, Some(v)) => changes.push(format!("/value: \"\" → \"{}\"", v)),
        (Some(v), None) => changes.push(format!("/value: \"{}\" → \"\"", v)),
        (Some(b), Some(a)) if b != a => changes.push(format!("/value: \"{}\" → \"{}\"", b, a)),
        _ => {}
    }

    // URL changes (navigation verification)
    match (&before.url, &after.url) {
        (None, Some(u)) => changes.push(format!("/url: (none) → \"{}\"", u)),
        (Some(u), None) => changes.push(format!("/url: \"{}\" → (none)", u)),
        (Some(b), Some(a)) if b != a => changes.push(format!("/url: \"{}\" → \"{}\"", b, a)),
        _ => {}
    }

    // Text content changes
    match (&before.text, &after.text) {
        (None, Some(t)) => changes.push(format!("text: (none) → \"{}\"", t)),
        (Some(t), None) => changes.push(format!("text: \"{}\" → (none)", t)),
        (Some(b), Some(a)) if b != a => changes.push(format!("text: \"{}\" → \"{}\"", b, a)),
        _ => {}
    }

    changes
}

// ---------------------------------------------------------------------------
// Output formatting
// ---------------------------------------------------------------------------

fn format_diff_output(diffs: &[DiffEntry], before: &PathBuf, after: &PathBuf) -> String {
    let mut lines = Vec::new();

    // Header
    lines.push(format!(
        "# Snapshot Diff: {} → {}",
        before.file_name().unwrap_or_default().to_string_lossy(),
        after.file_name().unwrap_or_default().to_string_lossy(),
    ));

    if diffs.is_empty() {
        lines.push("# No changes detected.".to_string());
        return lines.join("\n");
    }

    // Count summary
    let added = diffs.iter().filter(|d| matches!(d, DiffEntry::Added(_))).count();
    let removed = diffs.iter().filter(|d| matches!(d, DiffEntry::Removed(_))).count();
    let modified = diffs.iter().filter(|d| matches!(d, DiffEntry::Modified { .. })).count();

    lines.push(format!(
        "# {} added, {} removed, {} modified",
        added, removed, modified
    ));
    lines.push(String::new());

    // Group: removed first, then modified, then added
    let groups: [(&str, fn(&&DiffEntry) -> bool); 3] = [
        ("Removed", |d| matches!(d, DiffEntry::Removed(_))),
        ("Modified", |d| matches!(d, DiffEntry::Modified { .. })),
        ("Added", |d| matches!(d, DiffEntry::Added(_))),
    ];

    for (label, filter) in &groups {
        let matches: Vec<_> = diffs.iter().filter(filter).collect();
        if matches.is_empty() {
            continue;
        }

        lines.push(format!("## {}", label));
        lines.push(String::new());

        for entry in matches {
            match entry {
                DiffEntry::Added(node) => {
                    lines.push(format!("+ {} {} \"{}\"",
                        node.role,
                        short_id(&node.ref_id),
                        truncate_str(&node.name, 60),
                    ));
                    if let Some(v) = &node.value {
                        if !v.is_empty() {
                            lines.push(format!("  + /value: \"{}\"", truncate_str(v, 80)));
                        }
                    }
                }
                DiffEntry::Removed(node) => {
                    lines.push(format!("- {} {} \"{}\"",
                        node.role,
                        short_id(&node.ref_id),
                        truncate_str(&node.name, 60),
                    ));
                }
                DiffEntry::Modified { before: _b, after: _a, changes } => {
                    let node = &_b;
                    let node_a = &_a;
                    let id = if node.ref_id == node_a.ref_id {
                        short_id(&node.ref_id)
                    } else {
                        format!("{}→{}", short_id(&node.ref_id), short_id(&node_a.ref_id))
                    };
                    lines.push(format!("~ {} {} \"{}\"",
                        node.role,
                        id,
                        truncate_str(&node.name, 60),
                    ));
                    for c in changes {
                        lines.push(format!("  {}", c));
                    }
                }
            }
        }
        lines.push(String::new());
    }

    lines.join("\n")
}

fn short_id(ref_id: &str) -> String {
    if ref_id.is_empty() {
        return "?".to_string();
    }
    if ref_id.len() <= 6 {
        ref_id.to_string()
    } else {
        ref_id[..6].to_string()
    }
}

fn truncate_str(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        format!("{}…", &s[..max - 1])
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_basic_snapshot() {
        let yaml = r#"
# Viewport State
# - processingViewport: 0
- generic [ref=e1] [box=0,0,1920,1080]:
  - button "Search" [ref=e2] [box=100,80,120,40]
  - textbox "Query" [ref=e3] [box=240,80,400,40]:
    - /value: "hello"
  - link "Home" [ref=e4] [box=10,10,80,20]:
    - /url: https://example.com
"#;
        let nodes = parse_snapshot(yaml);
        assert_eq!(nodes.len(), 4, "Should parse 4 nodes");

        let btn = nodes.iter().find(|n| n.role == "button").unwrap();
        assert_eq!(btn.name, "Search");
        assert_eq!(btn.ref_id, "e2");

        let tb = nodes.iter().find(|n| n.role == "textbox").unwrap();
        assert_eq!(tb.value.as_deref(), Some("hello"));

        let link = nodes.iter().find(|n| n.role == "link").unwrap();
        assert_eq!(link.url.as_deref(), Some("https://example.com"));
    }

    #[test]
    fn test_parse_box() {
        assert!(parse_box("100,200,300,400").is_some());
        let b = parse_box("100,200,300,400").unwrap();
        assert_eq!(b.x, 100.0);
        assert_eq!(b.y, 200.0);
        assert_eq!(b.w, 300.0);
        assert_eq!(b.h, 400.0);
        assert!(parse_box("invalid").is_none());
    }

    #[test]
    fn test_parse_quoted_name() {
        assert_eq!(parse_quoted_name(r#""Search""#).0, "Search");
        assert_eq!(parse_quoted_name(r#""Hello World" [ref=e5]"#).0, "Hello World");
        assert_eq!(parse_quoted_name(r#"no-quotes"#).0, "");
    }

    #[test]
    fn test_diff_added_removed() {
        // Different roles and boxes far apart → no match
        let before = vec![
            SnapNode {
                depth: 0, role: "button".into(), name: "Submit".into(),
                ref_id: "e1".into(), box_rect: Some(BoxRect { x: 0.0, y: 0.0, w: 100.0, h: 40.0 }),
                url: None, value: None, text: None, raw_line: String::new(),
            },
        ];
        let after = vec![
            SnapNode {
                depth: 0, role: "link".into(), name: "New Link".into(),
                ref_id: "e99".into(), box_rect: Some(BoxRect { x: 500.0, y: 500.0, w: 80.0, h: 20.0 }),
                url: None, value: None, text: None, raw_line: String::new(),
            },
        ];

        let diffs = compute_diff(&before, &after);
        // Different role → no match → old removed, new added
        assert_eq!(diffs.len(), 2);
        assert!(matches!(diffs[0], DiffEntry::Removed(_)));
        assert!(matches!(diffs[1], DiffEntry::Added(_)));
    }

    #[test]
    fn test_diff_value_changed() {
        let before = vec![
            SnapNode {
                depth: 0, role: "textbox".into(), name: "Query".into(),
                ref_id: "e3".into(), box_rect: Some(BoxRect { x: 100.0, y: 80.0, w: 400.0, h: 40.0 }),
                url: None, value: Some("".into()), text: None, raw_line: String::new(),
            },
        ];
        let after = vec![
            SnapNode {
                depth: 0, role: "textbox".into(), name: "Query".into(),
                ref_id: "e99".into(), box_rect: Some(BoxRect { x: 100.0, y: 80.0, w: 400.0, h: 40.0 }),
                url: None, value: Some("Laser-Engraved Crystal".into()), text: None, raw_line: String::new(),
            },
        ];

        let diffs = compute_diff(&before, &after);
        assert_eq!(diffs.len(), 1);
        match &diffs[0] {
            DiffEntry::Modified { changes, .. } => {
                assert!(changes.iter().any(|c| c.contains("value")), "Should detect value change");
            }
            _ => panic!("Expected Modified entry"),
        }
    }

    #[test]
    fn test_diff_identical_no_output() {
        let node = SnapNode {
            depth: 0, role: "button".into(), name: "Submit".into(),
            ref_id: "e1".into(), box_rect: Some(BoxRect { x: 0.0, y: 0.0, w: 100.0, h: 40.0 }),
            url: None, value: None, text: None, raw_line: String::new(),
        };
        let before = vec![node.clone()];
        let after = vec![node.clone()];
        let diffs = compute_diff(&before, &after);
        assert!(diffs.is_empty(), "Identical elements should produce no diff");
    }
}

