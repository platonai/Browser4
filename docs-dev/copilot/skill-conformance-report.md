---
title: "Skill Conformance Report — 2026-08-25"
description: "Results of running bin/skill-doc-lint.ps1 (methodology.md M1-M8) across the skills/ tree after the v2.2 conformance sweep. Read when tracking remaining skill-document remediation work."
tier: procedure
---

# Skill Conformance Report — 2026-08-25

## Summary

The `skills/` tree was swept against `skills/browser4-cli/methodology.md` (v2.2) using `bin/skill-doc-lint.ps1`. **All 44 documents now pass every check (M1-M8) — lint exit code 0, 220/220 links resolve.**

## What was fixed in this sweep

### Links (M7) — 8 broken links repaired
- `crawl.md`, `skills.md`, `browser4-plugin/SKILL.md` — wrong relative depth for `docs/`, `docs-dev/`, `AGENTS.md`, `browser4-pdk/`, `browser4-plugins/` targets
- `browser4-experience/SKILL.md` — removed dead link to `coworker/plan/.../synthesis-proposed-solution.md` (no such file)

### Frontmatter (M1-M3) — 7 files repaired
- `quickstart.md` — added frontmatter + declared `x-role: distilled` / `source: ../SKILL.md` (the sanctioned P2 exception)
- `browser4-coding`, `browser4-dev`, `browser4-fix-bug` — added `title` + `tier: procedure`
- `browser4-experience`, `browser4-seo`, `browser4-plugin` — `title` now matches H1
- `browser4-web-miner` — `name` now matches the directory (`browser4-web-miner`)
- `methodology.md` — declared `x-exempt: P4` per its own exemption rule

### Warning centralization (M8) — duplicates replaced with pointers
- `snapshot.md` — "don't cat snapshot files" and "refs are single-use" now one-line pointers to SKILL.md §5 (file-local warnings kept)

### Templates (M4/M5) — all reference docs brought onto their tier skeleton
- 10 catalog docs gained `## Overview`; `power-dom.md` and `storage-state.md` gained `## Quick Index` tables; `x-sql-array-functions.md` and `x-sql-dom-load-select.md` gained index tables
- `config.md` re-tiered `procedure` → `catalog` (it is a config-key enumeration)
- 10 procedure docs gained `Quick Start`; 5 gained `Flags`; 7 gained `When to Use` / `How It Works`; 6 gained `Patterns`; 6 gained `Errors & Recovery`; section order normalized (including `crawl.md` section move)
- `htmlsnapshot-scenarios*.md` — "Scenarios" renamed to "Patterns" (recipes are the patterns), added Quick Start/When to Use/How It Works/Flags/Errors & Recovery; index doc gained the full decision template (Quick Comparison / Decision Tree / When to Use Each / Quick Patterns / Reference Map)
- `browser4-experience/SKILL.md` — gained `Key Concepts` + `Quick Patterns`, renumbered 1-9, "Tool Reference" → "Command Map"
- All 6 non-CLI SKILL.md manifests gained the procedure skeleton (Quick Start … Errors & Recovery)
- **`load-options-guide.md`** — split per P3: the catalog now points to the new decision doc **`load-options-decision.md`** (decision tier, full template), removing the forbidden `## Decision Tree` from a catalog (M5)

### Methodology itself (v2.0 → v2.2)
- Unified SKILL.md skeleton (7-section variant), bounded P2 snippet exemption, index/distilled/manifest roles, per-section heading aliases (Quick Reference / Table of Contents / Commands / Error handling …), M1-M8 conformance checklist, exemption + revision procedures

## Remaining violations

**None — 44/44 files pass.** The three former M6 line-count violations were resolved by content splits:

| File | Before | After | Split |
|------|--------|-------|-------|
| `skills/browser4-cli/SKILL.md` | 789 | **300** | §4 decision trees → `references/decision-trees.md` (decision, 173); §6 quick patterns → `references/quick-patterns.md` (procedure, 219); tab details → `references/tab-management.md` (procedure, 100); §2/§5/§7 compressed |
| `skills/browser4-plugin/SKILL.md` | 661 | **143** | → `references/workflow.md` (procedure, 421), `references/file-reference.md` (catalog, 45), `references/plugin-loading.md` (catalog, 48) |
| `references/htmlsnapshot-scenarios-amazon.md` | 577 | **488** | Compressed 6 `inspect`/`summary` output transcripts (no facts lost) |

Also in this sweep: `references/quickstart.md` translated from Chinese to English (still passes the engine-embedding test assertions: contains "Core Loop" and "snapshot vs htmlsnapshot", 6.3K chars < 20K budget).

## Tooling

- `bin/skill-doc-lint.ps1` implements M1-M8; exit code 0 = clean, 1 = violations.
- **Wired into CI (2026-08-25):** a `Skill document conformance (methodology M1-M8)` step runs `./bin/skill-doc-lint.ps1` in:
  - `.github/workflows/pr.yml` — PR quality gate (after PowerShell script validation)
  - `.github/workflows/ci.yml` — release-tag pipeline (after PowerShell script validation)
  - `.github/workflows/ps1-tests.yml` — nightly scheduled check

## Re-run

```powershell
./bin/skill-doc-lint.ps1            # whole tree
./bin/skill-doc-lint.ps1 -Path skills/browser4-cli   # one skill
./bin/skill-doc-lint.ps1 -PassThru  # machine-readable JSON
```
