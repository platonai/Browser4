---
title: "SKILL Document Methodology"
description: "Governing principles, three-tier document model, section templates, and style conventions for all SKILL documents under the skills/ directory."
tier: decision
---

# SKILL Document Methodology

These principles and conventions govern all documents under the `skills/` directory. Every document must conform.

## Six Principles

### Principle 1: SKILL.md Is a Decision Guide, Not a Reference

SKILL.md should be compact (~200-250 lines). Its job is to teach the agent **how to choose** between approaches — not to exhaustively document every flag or function.

- **Keep:** Core interaction loop, key concepts, command map, decision trees, critical warnings, quick patterns, reference map
- **Move out:** Complete flag lists, exhaustive function catalogs, detailed procedural walkthroughs, scenario recipes

### Principle 2: One Fact, One Place

Every fact lives in exactly one file. SKILL.md may **reference** a fact via a link but never **duplicate** it. When a fact appears in multiple files, the canonical copy lives in the most specific reference file; all other occurrences become links.

**Enforcement test:** If you delete a fact from its canonical file, no other file should still contain it.

### Principle 3: Three-Tier Document Model

Every document is classified into one of three tiers, signaled by `tier:` in its frontmatter:

| Tier | Purpose | Target Length | Example |
|------|---------|---------------|---------|
| **decision** | Comparison tables, decision trees, trade-off analysis. Answers "which approach should I use?" | 100-300 lines (SKILL.md: ~200-250) | SKILL.md, scenario comparison docs |
| **procedure** | End-to-end workflows. Answers "I want to do X, show me the steps." | 100-500 lines | Operation walkthroughs, how-to guides |
| **catalog** | Exhaustive reference listings. Answers "what are all the options for Y?" Only consulted on demand. | Any length | API references, flag listings, function catalogs |

**Critical rule:** A decision document never contains a complete flag listing. A procedure document never contains an exhaustive function catalog. A catalog never contains a decision tree.

### Principle 4: Consistent Skeleton Per Tier

Every document of the same tier follows an identical section template:

**Decision template:**
```text
# Title
## Quick Comparison        — table: approach vs approach on key dimensions
## Decision Tree           — ASCII tree or numbered flow
## When to Use Each        — 1 paragraph per approach
## Quick Patterns          — minimal copy-paste for the top 3 paths
## Reference Map           — "I want to do X" → reference file
```

**Procedure template:**
```text
# Title
## Quick Start             — copy-paste command block (the 80% case)
## When to Use             — vs alternatives (link to decision doc, don't repeat)
## How It Works            — 1 paragraph, no code
## Patterns                — numbered recipes: Problem → Solution → Example
## Flags / Options         — compact table
## Errors & Recovery       — symptom → cause → fix table
```

**Catalog template:**
```text
# Title
## Overview                — 1 paragraph: what this covers, when to read it
## Quick Index             — table: function/option name | returns/type | one-line description
## Reference               — grouped by category: signature + description + example per entry
```

### Principle 5: Warning Centralization

Critical warnings that apply broadly live in the skill's own SKILL.md. Reference files link back to that section rather than repeating the warning text.

**File-local warnings** (specific to one command or function) may stay in their file but follow a consistent format.

**Warning taxonomy:**

| Prefix | Meaning | Example |
|--------|---------|---------|
| `> **Warning:**` | Will cause silent failure or wrong results if ignored | "Refs are single-use — re-snapshot after every interaction" |
| `> **Note:**` | Important context, but won't break things if missed | "Output is paginated at 2K lines by default" |
| `> **Tip:**` | Non-obvious optimization or workflow improvement | "Use --auto-diff to verify interactions" |

No emoji in callout prefixes. No colored text — these render inconsistently across terminals.

### Principle 6: Max Two Hops to Answer

An agent should be able to answer **80% of questions** with SKILL.md + **1 reference file**.

**Mechanisms:**
- Decision documents include the top 3 copy-paste patterns directly — no hop to a procedure doc for the common case
- Each reference file is self-contained for its topic (includes the minimal snippet needed, rather than saying "see the full reference")
- The SKILL.md reference map is organized by **user task** ("I want to do X"), not by document name

## Document Frontmatter

Every file must have YAML frontmatter with these fields:

```yaml
---
title: "<Document Title>"
description: "<One line — what the agent learns from reading this file>"
tier: decision | procedure | catalog
---
```

- `title` — matches the document's `#` heading
- `description` — one sentence, written for an AI agent. Answers: "When should I read this?"
- `tier` — one of `decision`, `procedure`, `catalog`

## Style Conventions

| Rule | Detail |
|------|--------|
| Callout taxonomy | `> **Warning:**` for critical, `> **Note:**` for informational, `> **Tip:**` for optimizations |
| No emoji in callouts | Replace emoji with text prefixes — renders consistently across terminals |
| Aligned table pipes | Every table uses aligned `|` separators |
| Consistent code block tags | `bash`, `sql`, `yaml`, `json`, `text` — never bare blocks |
| Verified cross-references | Every `[text](file.md)` link must resolve to an existing file |
| No dead links | Remove references to files that no longer exist |
