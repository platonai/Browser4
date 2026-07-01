---
title: "SKILL Document Refactoring — Methodology & Plan"
description: "Methodology, principles, document-tier model, templates, and phased refactoring plan for all SKILL documents in the skill/ directory."
tier: decision
---

# SKILL Document Refactoring — Methodology

## 1. Current State

**25 files**, ~7,000 lines across two layers:

```
skill/
├── SKILL.md              (~480 lines)  Main entry: concepts, commands, decision tables, X-SQL intro
└── references/
    ├── agent.md           (~130 lines)  Agent/extract/summarize + LLM provider config
    ├── attach.md          (~107 lines)  Connect to running Chrome/Edge via CDP
    ├── crawl.md           (~302 lines)  Recursive crawling with X-SQL extraction
    ├── css-selector-bridge.md (~250 lines)  Bridging snapshot refs to CSS selectors
    ├── domsnapshot.md     (~312 lines)  Static DOM extraction, inspection, X-SQL querying
    ├── domsnapshot-scenarios.md           (~163 lines)  Scenario index hub + patterns/tips
    ├── domsnapshot-scenarios-advanced.md  (~300 lines)  Agent discovery, summary, inspect
    ├── domsnapshot-scenarios-amazon.md    (~600 lines)  Amazon-specific workflows
    ├── domsnapshot-scenarios-audit.md     (~400 lines)  SEO, compliance, CI, incident response
    ├── domsnapshot-scenarios-extraction.md (~300 lines)  E-commerce, news, jobs, academic, real estate
    ├── error-handling.md  ( 22 lines)  Command exit codes + recovery patterns
    ├── load-options-guide.md       (~267 lines)  Full LoadOptions parameter reference
    ├── load-options-quick-ref.md   (~202 lines)  Compact quick reference (50% overlap with guide)
    ├── loop.md            (~309 lines)  Repeated task execution with persistence/resume
    ├── polite-scraping.md ( 29 lines)  Rate limiting and CAPTCHA avoidance
    ├── power-dom.md       (~162 lines)  PowerCSS :expr() visual-feature selectors
    ├── storage-state.md   (~230 lines)  Cookies, localStorage, sessionStorage, state save/load
    ├── swarm.md           (~137 lines)  Parallel scraping across multiple browser contexts
    ├── x-sql.md           (~351 lines)  X-SQL master function index + quick-reference patterns
    ├── x-sql-array-functions.md      (~100 lines)  ARRAY namespace (3 functions)
    ├── x-sql-dom-functions.md        (~500 lines)  Core DOM operations (~65 functions)
    ├── x-sql-dom-load-select.md      ( 69 lines)  DOM_LOAD_AND_SELECT table-source function
    ├── x-sql-dom-select-functions.md (~200 lines)  CSS selector-based extraction (~50 functions)
    └── x-sql-string-functions.md     (~430 lines)  String manipulation (~90 functions)
```

### Problems Identified

#### P1. Content Duplication

The same facts appear in 3+ places:

| Fact | Appears in |
|------|-----------|
| "Refs are single-use" | SKILL.md, domsnapshot.md, css-selector-bridge.md |
| "Use @file.sql to avoid shell quoting" | SKILL.md, crawl.md, x-sql.md, domsnapshot.md, scenarios.md |
| "CSS selectors WILL break over time" | All 5 scenario files |
| LoadOptions parameter tables | load-options-guide.md, load-options-quick-ref.md (50% overlap), crawl.md |

#### P2. Frontmatter Inconsistency

Only 7 of 25 files have YAML frontmatter (--- block with title:/description:). The remaining 18 start with bare # headings. There is no tier or category metadata on any file.

#### P3. Thin Files

Two files are too small to justify standalone existence:
- `error-handling.md` — 22 lines
- `polite-scraping.md` — 29 lines

#### P4. Structure Anarchy Across Command References

Each command reference arranges its sections differently:

| File | Section Order |
|------|--------------|
| `crawl.md` | Quick start → Modes → Flags → Output formats → Common patterns → Error handling |
| `loop.md` | Quick start → Modes → Flags → Persistence → Output → How it works → Timeout → Error handling |
| `swarm.md` | Architecture → create → submit → query → status & result → Complete Workflow → Error Handling |
| `attach.md` | Quick Syntax → by channel → by CDP URL → remote server → named sessions → workflows → error handling |
| `agent.md` | Prerequisites → agent run → status → result → complete workflow → combining → related commands → error handling |

No two follow the same skeleton.

#### P5. Audience Confusion

These documents are consumed by AI agents (Claude), but many are written in a human-facing CLI-man-page style. The document types are mixed:
- **Procedural** (scenario files — step-by-step recipes)
- **Exhaustive catalog** (x-sql-*.md — function-by-function listings)
- **Hybrid** (SKILL.md — tries to be both quick-start and comprehensive reference)

#### P6. Deep Navigation

Answering a task like "extract Amazon prices" requires traversing 4 files:
```
SKILL.md → domsnapshot-scenarios.md (hub) → domsnapshot-scenarios-amazon.md → x-sql-dom-load-select.md
```

#### P7. Scattered Warnings

Critical rules are repeated ad-hoc across files with inconsistent formatting:
- `> **RED THE GOLDEN RULE:**` (SKILL.md)
- `> Warning: CSS selectors are tied to live websites` (scenario files)
- `> **Note:**` vs `> **Important:**` vs `> **RED**` — no consistent taxonomy

---

## 2. Methodology — Six Principles

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
| **decision** | Comparison tables, decision trees, trade-off analysis. Answers "which approach should I use?" | 100-300 lines | SKILL.md, domsnapshot-scenarios.md |
| **procedure** | End-to-end workflows. Answers "I want to do X, show me the steps." | 100-500 lines | Scenario files, attach.md, crawl.md, loop.md, swarm.md, agent.md |
| **catalog** | Exhaustive reference listings. Answers "what are all the options for Y?" Only consulted on demand. | Any length | x-sql-*.md, load-options-guide.md, domsnapshot.md, power-dom.md |

**Critical rule:** A decision document never contains a complete flag listing. A procedure document never contains an exhaustive function catalog. A catalog never contains a decision tree.

### Principle 4: Consistent Skeleton Per Tier

Every document of the same tier follows an identical section template:

**Decision template:**
```
# Title
## Quick Comparison        — table: approach vs approach on key dimensions
## Decision Tree           — ASCII tree or numbered flow
## When to Use Each        — 1 paragraph per approach
## Quick Patterns          — minimal copy-paste for the top 3 paths
```

**Procedure template:**
```
# Title
## Quick Start             — copy-paste command block (the 80% case)
## When to Use             — vs alternatives (link to decision doc, don't repeat)
## How It Works            — 1 paragraph, no code
## Patterns                — numbered recipes: Problem → Solution → Example
## Flags / Options         — compact table
## Errors & Recovery       — symptom → cause → fix table
```

**Catalog template:**
```
# Title
## Overview                — 1 paragraph: what this covers, when to read it
## Quick Index             — table: function/option name | returns/type | one-line description
## Reference               — grouped by category: signature + description + example per entry
```

### Principle 5: Warning Centralization

Critical warnings that apply broadly live in SKILL.md section 5 (Critical Warnings). Reference files link back to that section rather than repeating the warning text.

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

Current state (4-hop example): SKILL.md → scenario hub → amazon scenarios → x-sql-dom-load-select

Target state (2 hops): SKILL.md → domsnapshot-scenarios-amazon.md (which embeds the necessary X-SQL pattern inline)

**Mechanisms:**
- Decision documents include the top 3 copy-paste patterns directly — no hop to a procedure doc for the common case
- Each reference file is self-contained for its topic (includes the minimal X-SQL snippet needed, rather than saying "see x-sql.md")
- The SKILL.md reference map is organized by **user task** ("I want to extract data"), not by document name

---

## 3. Refactoring Plan

### Phase 1: Merge & Prune

| Action | Detail |
|--------|--------|
| **Merge** `load-options-quick-ref.md` → `load-options-guide.md` | Quick-ref table becomes the top section ("Quick Reference") of the full guide. One file, two levels of depth. Delete the standalone quick-ref file. |
| **Absorb** `error-handling.md` → SKILL.md | The 22-line recovery table moves into SKILL.md as a compact "Common Recoveries" section. Delete the standalone file. |
| **Absorb** `polite-scraping.md` → `crawl.md` | The 29 lines of rate-limiting guidance move into the crawl.md procedure document (crawl has built-in rate limiting) with a one-line reference from SKILL.md. Delete the standalone file. |
| **Rename** `css-selector-bridge.md` | Keep as a procedure document. Evaluate whether the current name is clear or should be renamed. |

**Files after Phase 1:** 25 → 22

### Phase 2: Restructure SKILL.md

Target: ~230 lines. Structure:

```
---
title: "Browser4 CLI — AI Agent Skill"
description: "Automates browser interactions for web testing, form filling, screenshots, and data extraction."
tier: decision
---

§1  Core Loop             (~20 lines)  — The interaction pattern every session follows. Copy-paste template.
§2  Key Concepts          (~25 lines)  — Ref lifecycle, sessions, snapshots, viewports.
§3  Command Map           (~30 lines)  — Table: command family | what it does | when to use | full reference link.
§4  Decision Trees        (~60 lines)  — The heart of the refactor:
     4a  Extraction method: snapshot vs domsnapshot vs eval vs X-SQL vs extract/summarize
     4b  Bulk/scale: crawl vs swarm vs loop
     4c  Query granularity: get vs get all vs query
§5  Critical Warnings     (~25 lines)  — Centralized, once only:
     - Refs are single-use (re-snapshot after every interaction)
     - CSS selectors break — always discover, don't hard-code
     - Shell quoting on Windows — prefer @file, --stdin, or --base64 for SQL/JS
     - Don't cat snapshot files — use viewport pagination or snapshot grep
     - Pagination defaults: get html paginated, get text not paginated
§6  Quick Patterns        (~30 lines)  — 3 copy-paste templates:
     - Interactive form fill
     - Static data extraction (domsnapshot get)
     - Bulk extraction (X-SQL query)
§7  Reference Map         (~30 lines)  — Organized by task:
     "Extract data"          → domsnapshot.md, x-sql.md, domsnapshot-scenarios.md
     "Run at scale"          → crawl.md, swarm.md, loop.md
     "Manage browser state"  → storage-state.md, attach.md
     "AI-powered extraction" → agent.md
     "Resilient selectors"   → power-dom.md, css-selector-bridge.md
     "Configure fetching"    → load-options-guide.md
```

**Content moves out of SKILL.md:**
- Full command flag listings → remain in their reference files (already mostly the case)
- X-SQL function counts and namespaces → x-sql.md only
- Installation instructions → keep a minimal section at the bottom of SKILL.md
- Extraction method comparison table → keep but shrink to decision-tree form

### Phase 3: Standardize Reference Documents

Apply the three templates to all files:

**Decision tier** (no structural change needed):
- `SKILL.md` — already restructured in Phase 2
- `domsnapshot-scenarios.md` — remove "Patterns & Tips" (moves to SKILL.md §6 + domsnapshot.md), keep as a pure index

**Procedure tier** — restructure to the procedure template:
- `attach.md`, `crawl.md`, `loop.md`, `swarm.md`, `agent.md`, `css-selector-bridge.md`
- All 5 scenario files: `domsnapshot-scenarios{-advanced,-amazon,-audit,-extraction}.md`

**Catalog tier** — restructure to the catalog template:
- `domsnapshot.md`, `load-options-guide.md`, `power-dom.md`, `storage-state.md`
- All 5 x-sql files: `x-sql.md`, `x-sql-{dom-functions,dom-load-select,dom-select-functions,string-functions,array-functions}.md`

### Phase 4: Normalize Frontmatter

Every file gets consistent YAML frontmatter:

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

### Phase 5: Scenario File Cleanup

1. Standardize all 5 scenario files to the **procedure template**
2. Remove the duplicated CSS-selector-break warning from each file body — replaced by a one-line link to SKILL.md §5
3. Renumber scenarios 1-16 sequentially (currently: 1-2, 5, 7-8, 10-13, 14-16 — non-sequential gaps cause confusion)
4. Move "Patterns & Tips" section from `domsnapshot-scenarios.md` to:
   - SKILL.md §6 (the top 3 patterns)
   - `domsnapshot.md` (the remaining command-specific tips)
5. Ensure each scenario file is self-contained: include the minimal X-SQL snippet in the scenario rather than saying "see x-sql-dom-load-select.md"

### Phase 6: Polish

Apply consistently across all files:

| Rule | Detail |
|------|--------|
| Callout taxonomy | `> **Warning:**` for critical, `> **Note:**` for informational, `> **Tip:**` for optimizations |
| No emoji in callouts | Replace emoji with text prefixes — renders consistently across terminals |
| Aligned table pipes | Every table uses aligned `|` separators |
| Consistent code block tags | `bash`, `sql`, `yaml`, `json`, `text` — never bare blocks |
| Verified cross-references | Every `[text](file.md)` link resolves to an existing file |
| No dead links | Remove references to files that no longer exist |

### Phase 7 (Optional): X-SQL Function Reorganization

The X-SQL catalog files could be further split by functional category within each namespace. This is **lower priority** — agents only read these on demand, and the current grouping (core DOM, select functions, string functions, array functions) is already reasonable.

---

## 4. Target Outcome

| Metric | Before | After |
|--------|--------|-------|
| Files | 25 | **21** |
| Total lines | ~7,000 | **~5,500** |
| SKILL.md lines | ~480 | **~230** |
| Max hops to answer | 4 | **2** |
| Frontmatter coverage | 7/25 (28%) | **21/21 (100%)** |
| Duplicated facts | ~15 instances | **0** |
| Thin files (<50 lines) | 2 | **0** |
| Structure templates | 0 formalized | **3** (decision / procedure / catalog) |

---

## 5. Implementation Order

1. **Phase 1** (Merge & Prune) — mechanical, no content authoring. Quick win.
2. **Phase 4** (Frontmatter) — add frontmatter to all surviving files. Prerequisite for later phases.
3. **Phase 2** (SKILL.md restructure) — the core content refactor. Do first so references can link to it.
4. **Phase 3** (Standardize references) — apply templates to all 20 remaining reference files.
5. **Phase 5** (Scenario cleanup) — numbering, dedup, self-containment.
6. **Phase 6** (Polish) — formatting, links, consistency pass.
7. **Phase 7** (X-SQL reorg) — only if needed after Phase 3-6.
