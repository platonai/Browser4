---
title: "Experience Memory — Architecture & Implementation Guide"
description: "End-to-end guide for the Progressive Experience Memory learning system: knowledge store layout, MCP tool usage, retrieval chain, confidence model, and implementation patterns."
tier: procedure
---

# Progressive Experience Memory (PEM) — Implementation Guide

## Quick Start

The experience tools (`experience_query`, `experience_save`, `experience_list`) are **MCP tools** called by the agent during `browser4-cli agent run` — they are not standalone CLI subcommands.

```bash
# 1. Run the agent with a task — it calls experience_query before starting
#    and experience_save after completion, automatically persisting knowledge
browser4-cli agent run "Go to https://amazon.com/dp/B0CXJ1NT4B and extract product details"

# 2. Inspect stored knowledge entries
browser4-cli agent run "List experience knowledge entries for amazon"
```

## When to Use

Use `experience_query` **before every task** — it's a no-op on cold start (returns P5 with no penalty). Use `experience_save` **after every task** (success or failure) — knowledge compounds with each visit.

When NOT to use:
- Throwaway test sessions (skip the `experience_save` call or omit it entirely)
- Tasks on sensitive/authenticated pages where selectors embed user data (the redaction layer in Phase 2+ will handle this automatically)

## How It Works

The PEM system has **three MCP tools** that read/write a **file-backed YAML knowledge store**. On task completion, the agent calls `experience_save` which:

1. Persists the raw execution trace to `.traces/<domain>/<timestamp>-<task_type>.yaml`
2. Extracts the domain from the URL
3. Normalizes the URL (strips query params, `www.`, fragments)
4. Derives a URL pattern (replaces high-cardinality path segments with `*`)
5. Creates or updates a `KnowledgeEntry` in `sites/<domain>.yaml`
6. Updates the in-memory index (`knowledge/.index.yaml`)
7. Returns a save summary with confidence scores

On the next visit to the same domain, `experience_query`:
1. Normalizes the input URL
2. Extracts the domain and looks up the index
3. Finds the most specific matching URL pattern
4. Loads the `KnowledgeEntry` from `sites/<domain>.yaml`
5. Returns stored selectors, steps, extraction patterns, and blocker awareness

## Architecture

### Four-Layer Knowledge Model

```
L1: Site Profile         — domain, site_types, auth_pattern, load_strategy
L2: Page Schema          — url_pattern, selectors_ranked, wpsi_landmarks
L3: Task Playbook        — steps, extraction_fields, success_criteria, fallbacks
L4: Abstract Patterns    — cross-site generalizations (Phase 4+)
```

### Implementation Modules

All PEM code lives in `browser4-agentic` under package `ai.platon.pulsar.agentic.tools.experience`:

| File | Purpose |
|------|---------|
| `ExperienceModels.kt` | Data classes: ExecutionTrace, SiteProfile, PageSchema, TaskPlaybook, KnowledgeEntry, ConfidenceScore, TaskType |
| `KnowledgeStore.kt` | YAML file I/O with atomic rename, index management, domain-indexed storage |
| `UrlNormalizer.kt` | URL normalization, pattern matching, specificity scoring |
| `ExperienceToolExecutor.kt` | MCP tool executor (domain="experience") extending AbstractToolExecutor |
| `config/ExperienceAutoConfiguration.kt` | Spring Boot auto-configuration registering executor via CustomToolRegistry |

### Registration

The executor is registered at startup via Spring auto-configuration (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`). It implements `ToolMount` so the `PluginManager` automatically wires it into the MCP dispatch chain. No changes to `AgentToolManager` or `MCPToolController` are needed.

### Concurrency Model

- **Readers never lock** — atomic rename guarantees consistent view
- **Writers to the same domain serialize** via per-domain `Mutex`
- **Writers to different domains run concurrently**

## Confidence Model

```
confidence = α × success_ratio + (1 - α) × recency_factor

Where:
  success_ratio = (success_count + 1) / (success_count + failure_count + 2)  [Laplace-smoothed]
  recency_factor = 0.5 ^ (days_since_last_verification / 60)  [60-day half-life]
  α = 0.7

Special cases:
  first verified save → fixed 0.50
  cap → 0.95, floor → 0.05
```

### Retrieval Tiers by Confidence

| Tier | Range | Behavior |
|------|-------|----------|
| P1 | ≥ 0.85 | Direct replay — stored steps used without verification |
| P2 | 0.60–0.84 | Verify-before-replay — each selector validated via `htmlsnapshot get` |
| P3 | 0.40–0.59 | Hint mode — playbook suggests, full discovery runs |
| P4 | < 0.40 | Advisory — knowledge surfaced as suggestion only |
| P5 | No data | Cold start — `htmlsnapshot inspect` auto-discovery |

## Task Types

| Type | Default Criteria |
|------|-----------------|
| `extract_product_list` | `field_not_null: title` AND `row_count_gt: 0` |
| `extract_product_detail` | `field_not_null: title` |
| `extract_article` | `field_not_null: title` AND `field_not_null: body` |
| `search` | `row_count_gt: 0` |
| `add_to_cart` | `selector_visible` for cart confirmation |
| `fill_form` | `url_pattern: changed` |
| `login` | `url_pattern: changed` |
| `checkout` | `url_pattern` matches `/order/confirmation` |
| `extract_table` | `row_count_gt: 0` AND `field_not_null: col_0` |
| `navigate` | `url_pattern` matches target |
| `download_file` | Non-zero file size |
| `monitor_change` | Changed value from baseline |

## URL Normalization

URLs are normalized before storage and matching:

```
Input:  https://www.amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1?keywords=laptop&qid=1234567
Output: amazon.com/dp/B0CXJ1NT4B
```

Rules:
1. Strip `www.` prefix
2. Strip trailing slash
3. Strip fragment (`#section`)
4. Strip query params except semantically significant ones (`?q=`, `?id=`, `?page=`, `?k=`)
5. Replace high-cardinality path segments with `*` in URL patterns

## Storage Layout

```
{knowledge_dir}/
├── sites/
│   ├── amazon.com.yaml
│   ├── ebay.com.yaml
│   └── github.com.yaml
├── .index.yaml
├── .traces/
│   └── amazon.com/
│       ├── 2026-0717-142530-extract_product_list.yaml
│       └── 2026-0717-143045-search.yaml
└── .archive/
```

## Future Phases

| Phase | Status | Key Features |
|-------|--------|-------------|
| Phase 1 | ✓ Implemented | Trace & Replay, URL pattern matching, knowledge store |
| Phase 2 | Planned | `--with-experience` flags for inspect/summary, fast-path skip, stability scoring |
| Phase 3 | Planned | PowerCSS `:expr()` analysis, X-SQL extraction query capture, P2 retrieval |
| Phase 4 | Planned | Cross-site generalization, WPSI signature matching, geometric fingerprints |
| Phase 5 | Planned | Continuous learning, time-decay staleness, periodic probes, automated rollback |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `browser4.experience.enabled` | `true` | Enable/disable the PEM system |
| `knowledge.dir` | `$AGENT_BASE_DIR/knowledge` | Knowledge store root directory |

## Reference

- [Design proposal (v2)](../coworker/plan/feature/evolve/synthesis-proposed-solution.md) — Full 2300-line technical specification
- [Skill: browser4-experience](../skills/browser4-experience/SKILL.md) — Agent-facing usage guide
- [AGENTS.md](../AGENTS.md) — Code conventions and development patterns
