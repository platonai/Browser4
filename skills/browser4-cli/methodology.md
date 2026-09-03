---
title: "SKILL Document Methodology"
description: "Use when creating, editing, or reviewing any document under skills/: read this first to learn the governing principles, document tiers and templates, frontmatter schema, and conformance rules."
tier: decision
version: 2.2
x-exempt: P4
---

# SKILL Document Methodology

## Scope and Authority

- Governs **every** file under the `skills/` tree: all `SKILL.md` manifests and all files in their `references/` directories.
- This file is a **governance document**: it is exempt from the section templates in Principle 4 (it defines them), but it must obey its own frontmatter schema, style conventions, and conformance checklist.
- This file is the sole authority for its rules. A document that conflicts with it is non-conformant; a document that extends it must say so explicitly (see [Exemptions](#exemptions)).

## Six Principles

Every principle ends with a **test**. A rule without a test is a suggestion; tests are what make conformance checkable.

### Principle 1: SKILL.md Is a Decision Guide, Not a Reference

SKILL.md teaches the agent **how to choose** between approaches. It does not exhaustively document flags or functions.

- **Target length:** 200-250 lines; hard cap **300**. Length is counted in physical lines (blank lines, code blocks, and table rows all count — they consume the same context budget).
- **Keep:** core interaction loop, key concepts, command map, decision trees, critical warnings, quick patterns, reference map
- **Move out:** complete flag lists, exhaustive function catalogs, detailed procedural walkthroughs, scenario recipes

**Test P1 (machine):** SKILL.md ≤ 300 physical lines; no section whose body is a flag table of 20+ rows.

**Overflow procedure:** move the offending content to a `references/` file, add a one-line summary plus link in SKILL.md, and re-run the test. If the core loop itself cannot fit in 250 lines, the skill boundary is too large — split the skill rather than stretching the cap.

### Principle 2: One Fact, One Place

Every fact lives in exactly one file. SKILL.md may **reference** a fact via a link but never **duplicate** it. The canonical copy lives in the most specific file that needs it; all other occurrences are links.

**Snippet exemption (bounded):** a reference file may include a *minimal executable snippet* of a fact from another file, subject to all of:

1. The snippet is executable code or a command — never a normative statement (rule, warning, definition, semantics).
2. The snippet is ≤ 12 lines.
3. The snippet carries a one-line pointer to the canonical location.

Anything larger, or any normative statement, is a duplicate and must be replaced by a link.

**Test P2 (machine):** delete the canonical copy, then grep the tree: no other file may still contain the fact's normative wording. (grep + spot-check per fact)

### Principle 3: Three-Tier Document Model

Every document is classified into exactly one tier, signaled by `tier:` in its frontmatter.

| Tier | Purpose | Target Length | Example |
|------|---------|---------------|---------|
| **decision** | Comparison tables, decision trees, trade-off analysis. Answers "which approach should I use?" | 100-300 lines (SKILL.md: 200-250) | SKILL.md, scenario comparison docs |
| **procedure** | End-to-end workflows. Answers "I want to do X, show me the steps." | 100-500 lines | Operation walkthroughs, how-to guides |
| **catalog** | Exhaustive reference listings. Answers "what are all the options for Y?" Only consulted on demand. | Any length | API references, flag listings, function catalogs |

**Critical rule:** a decision document never contains a complete flag listing; a procedure document never contains an exhaustive function catalog; a catalog never contains a decision tree.

**Tier selection procedure** (apply in order):

1. Is the content primarily *comparison/choice*? → `decision`
2. Is it primarily *step-by-step execution*? → `procedure`
3. Is it primarily *exhaustive enumeration*? → `catalog`
4. Content mixes two tiers? → **split into separate files**, one per tier, linked: decision → procedure → catalog (one direction only). Never label a mixed document with one tier and hide the rest.

**Test P3 (machine):** frontmatter `tier` present and one of the three values; decision files contain no 20+ row flag tables; catalogs contain no `## Decision Tree` section; procedures contain no function catalog sections.

### Principle 4: Consistent Skeleton Per Tier

Every document of the same tier follows the identical section template for that tier. Sections marked **(required)** must exist in order; additional sections are allowed only if they are declared in a single `## Additional Sections` note at the end of the template section list.

**Decision template:**

```text
# Title
## Quick Comparison        — (required) table: approach vs approach on key dimensions
## Decision Tree           — (required) ASCII tree or numbered flow
## When to Use Each        — (required) 1 paragraph per approach
## Quick Patterns          — (required) minimal copy-paste for the top 3 paths
## Reference Map           — (required) "I want to do X" → reference file
```

**SKILL.md variant (the only allowed SKILL.md skeleton):**

```text
# Title
## 1. Core Loop            — (required) the interaction loop, taught before anything else
## 2. Key Concepts         — (required) refs, snapshots, sessions — only what the loop needs
## 3. Command Map          — (required) command → one-line purpose
## 4. Decision Trees       — (required) how to choose between approaches
## 5. Critical Warnings    — (required) the central home for broad warnings (see P5)
## 6. Quick Patterns       — (required) top 3 copy-paste paths
## 7. Reference Map        — (required) task-organized links (see P6)
```

This variant *is* the decision template adapted for SKILL.md: sections 4-7 map to the decision template's Decision Tree / When to Use Each / Quick Patterns / Reference Map; sections 1-3 (Core Loop, Key Concepts, Command Map) and 5 (Critical Warnings) are SKILL.md-specific. A SKILL.md whose `tier` is `procedure` (a fixed-workflow skill) uses the procedure template instead.

**Procedure template:**

```text
# Title
## Quick Start             — (required) copy-paste command block (the 80% case)
## When to Use             — (required) vs alternatives (link to decision doc, don't repeat)
## How It Works            — (required) 1 paragraph, no code
## Patterns                — (required) numbered recipes: Problem → Solution → Example
## Flags / Options         — (required) compact table (≤ 20 rows; larger → catalog)
## Errors & Recovery       — (required) symptom → cause → fix table
```

**Catalog template:**

```text
# Title
## Overview                — (required) 1 paragraph: what this covers, when to read it
## Quick Index             — (required) table: function/option name | returns/type | one-line description
## Reference               — (required) grouped by category: signature + description + example per entry
```

The index section may use an equivalent heading that serves the same role — `Quick Reference`, `Table of Contents`, `Commands`, or `Function Index` are all accepted; the table must still map name → type → one-line description. The reference section's heading name is free (e.g. `2.1 Page Loading`); what matters is that the body is grouped by category with a signature, description, and example per entry.

**Procedure section-name aliases:** `Quick start` (case-insensitive), `Flags / Options`, and `Error handling` are accepted equivalents of `Quick Start`, `Flags`, and `Errors & Recovery` respectively.

**Test P4 (machine):** required sections present, in template order, for the file's tier.

### Principle 5: Warning Centralization

- **Broad warnings** (apply to the whole skill or most of it) live **only** in the skill's own SKILL.md, in `## 5. Critical Warnings`. Reference files point back with a single-line pointer — never the warning text:

```text
> **Note:** Warning: refs are single-use — see [SKILL.md §5](../SKILL.md#5-critical-warnings)
```

- **File-local warnings** (specific to one command or function) may stay in their file, in the standard format below.

**Warning taxonomy (sole authority):**

| Prefix | Meaning | Example |
|--------|---------|---------|
| `> **Warning:**` | Will cause silent failure or wrong results if ignored | "Refs are single-use — re-snapshot after every interaction" |
| `> **Note:**` | Important context, but won't break things if missed | "Output is paginated at 2K lines by default" |
| `> **Tip:**` | Non-obvious optimization or workflow improvement | "Use --auto-diff to verify interactions" |

**Callout formatting:** no emoji and no colored text anywhere in a callout line (prefix and body) — rendering is inconsistent across terminals. "Emoji" means pictographic characters (supplementary-plane pictographs, dingbats, symbols blocks, and variation selectors); arrows such as `→` are not emoji and are allowed.

**Test P5 (machine):** every warning text that appears in SKILL.md §5 appears nowhere else except as a one-line pointer of the form shown above; callout lines contain no emoji.

### Principle 6: Max Two Hops to Answer

An agent should answer **80% of questions** using SKILL.md plus **at most one reference file**. Because "80%" is not directly testable, it is enforced through these checkable mechanisms:

1. **Decision documents embed the top 3 patterns** — the common case never needs a hop to a procedure doc. (Test: Quick Patterns section non-empty with code blocks.)
2. **Every SKILL.md link target is self-contained** — its topic's common uses are completable from that file alone (snippets allowed per P2). (Test: human spot-check, one target per review.)
3. **The Reference Map is task-organized** — entries read "I want to do X" → file, never a bare document name. (Test: machine-checkable entry format.)
4. **Index documents never sit on the critical path.** An index (a file whose job is to list other files, e.g. a master function index) must be `tier: catalog` with `x-role: index`, and SKILL.md must not link to it — SKILL.md links its leaf documents directly, so the path SKILL.md → index → leaf never occurs. (Test: machine check, no index links in SKILL.md.)

**Test P6:** all four checks above pass.

## Document Frontmatter

Every file must have YAML frontmatter. `title`, `description`, and `tier` are required in all documents; other fields depend on the document's role.

```yaml
---
title: "<Document Title>"
description: "<One sentence, starting with 'Use when' or 'Read this when'. Answers: when should I read this?>"
tier: decision | procedure | catalog
---
```

- `title` — matches the document's `#` heading exactly
- `description` — one sentence, agent-oriented, beginning with "Use when…" or "Read this when…"
- `tier` — exactly one of `decision`, `procedure`, `catalog`

**SKILL.md manifests** add:

```yaml
name: <kebab-case, must match the directory name>
tags: [ ... ]            # optional
allowed-tools: [ ... ]   # optional, space or comma separated
```

**Distilled copies** (a compressed duplicate of another document, e.g. one embedded in an engine prompt) are the one deliberate exception to P2. They must declare their nature and never drift from their source:

```yaml
x-role: distilled
source: <relative path of the canonical document>
```

A distilled copy may compress its source but must not add, remove, or reword any fact, and must state that details live in the source.

**Index documents** declare:

```yaml
x-role: index
```

### Exemptions

Any document that must deviate from this methodology declares it in frontmatter and justifies it in the `description`:

```yaml
x-exempt: P5        # comma-separated principle or section ids
```

An exemption must name the rule it breaks, and is itself reviewed under Conformance.

## Style Conventions

| Rule | Detail | Authority |
|------|--------|-----------|
| Callout taxonomy | `> **Warning:**` / `> **Note:**` / `> **Tip:**` with the meanings and format rules defined there | [Principle 5](#principle-5-warning-centralization) — not repeated here |
| Aligned table pipes | Every table uses aligned `|` separators; applies to prose tables, not example tables inside code blocks | this file |
| Consistent code block tags | `bash`, `sql`, `yaml`, `json`, `text` — never bare blocks | this file |
| Verified cross-references | Every `[text](file.md)` link must resolve to an existing file; relative paths correct for the link's location. Links inside code blocks are examples and are not checked | this file |
| No dead links | Remove references to files that no longer exist | this file |

## Conformance & Maintenance

### Machine-checkable (run as a lint script or CI step; also part of every review)

- M1. Frontmatter exists; `title`, `description`, `tier` present and valid (P3, Frontmatter)
- M2. `title` equals the `#` heading (Frontmatter)
- M3. `name` in SKILL.md manifests matches the directory name (Frontmatter)
- M4. Required template sections present in order (P4)
- M5. Tier-content rules: no 20+ row flag tables in decision files, no `## Decision Tree` in catalogs, no function catalogs in procedures (P3)
- M6. SKILL.md ≤ 300 lines; 100 ≤ decision ≤ 300; procedure ≤ 500 (P1, P3)
- M7. All relative links resolve; no dead links; no index links in SKILL.md (Style, P6)
- M8. Callout lines contain no emoji; warning text unique outside SKILL.md §5 (P5)

### Human review (part of every document review)

- H1. Spot-check P6: one SKILL.md link target is self-contained for its common use
- H2. Spot-check P2: one fact's deletion test passes
- H3. Description is agent-oriented and begins with "Use when" / "Read this when"
- H4. Exemptions (if any) are declared with justification; distilled copies are current with their source
- H5. No dead content: every referenced command, flag, and behavior exists in the current CLI

### Revision procedure

- This document is versioned (`version` in frontmatter). Any change to a rule bumps the version.
- A rule change requires: updating this file, updating the corresponding test, and fixing all non-conformant documents found by re-running the checklist — in the same change.
- When the checklist or templates change, update this file's own frontmatter and re-run M1-M8 against it (this file must pass everything except P4, from which it is exempt by [Scope and Authority](#scope-and-authority)).
