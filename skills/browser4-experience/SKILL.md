---
name: browser4-experience
title: "Progressive Experience Memory — Learning from Past Tasks"
description: "Use experience_save to persist task traces, experience_query to recall them on revisit, and experience_list to inspect stored knowledge. Reuses selectors, extraction patterns, and blocker awareness across sessions."
allowed-tools: Bash(browser4-cli:*)
tier: decision
---

# Progressive Experience Memory (PEM)

The PEM system makes Browser4 **progressively smarter**: each successfully completed task deposits reusable knowledge so that future tasks — identical, similar, or on similar sites — complete faster with fewer steps.

## 1. Core Loop

```
Before task ──▶ experience_query ──▶ Get stored selectors, steps, blockers
    │                                      │
    ▼                                      ▼
Execute task                        P1: Replay directly
    │                               P2: Verify then replay
    ▼                               P3: Hint mode (verify all)
After task  ──▶ experience_save ──▶ P4: Advisory only
                                      P5: Cold start (no knowledge)
```

### Copy-Paste Template

The experience tools are **MCP tools** called by the agent during `browser4-cli agent run`. The agent should call them as part of its tool set:

```bash
# Before starting a task — the agent calls experience_query to check prior knowledge
# After completing a task — the agent calls experience_save to persist what it learned
browser4-cli agent run "Go to https://amazon.com/dp/test and extract product details"

# To inspect stored knowledge, the agent calls experience_list
browser4-cli agent run "List experience knowledge entries for amazon"
```

## 2. Decision Tree

```
Starting a new task?
├─ experience_query returns P1 (confidence ≥ 0.85)?
│  → Replay stored steps directly. Selectors verified by existence check only.
├─ experience_query returns P2 (confidence 0.60–0.84)?
│  → Use stored selectors as primary candidates. Verify each before use.
├─ experience_query returns P3 (confidence 0.40–0.59)?
│  → Use stored knowledge as hints. Run full discovery for any failed selector.
├─ experience_query returns P4/P5 (confidence < 0.40, or cold start)?
│  → Full discovery mode. Run htmlsnapshot inspect, discover selectors fresh.
│  → Call experience_save after success to bootstrap knowledge.
└─ Always call experience_save after task completion (success or failure).
   → Success path: stores selectors, steps, extraction patterns.
   → Failure path: records negative evidence (what broke, why).
```

## 3. Retrieval Tiers

| Tier | Confidence | Behavior |
|------|-----------|----------|
| **P1** Direct replay | ≥ 0.85 | Steps executed without verification. Selectors used as-is. |
| **P2** Verify-before-replay | 0.60–0.84 | Each selector validated via `htmlsnapshot get` before use. |
| **P3** Hint mode | 0.40–0.59 | Playbook provides suggestions but full discovery runs. |
| **P4** Advisory | < 0.40 | Knowledge surfaced as suggestion only. Full discovery required. |
| **P5** Cold start | No data | No prior knowledge. Full exploration. |

## 4. Tool Reference

### experience_save

Persists a task execution trace to the knowledge store.

| Argument | Required | Description |
|----------|----------|-------------|
| `url` | Yes | The URL the task operated on |
| `trace` | Yes | JSON-encoded ExecutionTrace (steps, selectors, extraction results) |
| `outcome` | No | `"success"` (default) or `"failure"` |
| `task_type` | No | One of the 12 canonical task types (e.g., `extract_product_list`) |
| `intent` | No | Free-text description of what the task was trying to do |

**Success path:** Knowledge promoted with initial confidence 0.50. Subsequent verified successes raise confidence.
**Failure path:** Negative evidence recorded. Failed selectors added to anti-patterns. Blocker awareness updated.

### experience_query

Queries stored knowledge before starting a task.

| Argument | Required | Description |
|----------|----------|-------------|
| `url` | Yes | The target URL |
| `intent` | No | Free-text intent description |
| `task_type` | No | Filter to a specific task type |

**Returns:** JSON with `tier`, `confidence`, `primary_selectors`, `extraction_query`, `known_blockers`, `warnings`, `steps`.

### experience_list

Lists stored knowledge entries (diagnostic/debug tool).

| Argument | Required | Description |
|----------|----------|-------------|
| `filter` | No | Filter by domain (partial match) |
| `task_type` | No | Filter by task type |
| `page` | No | Page number (default 1) |
| `page_size` | No | Results per page (default 20, max 100) |

## 5. Critical Warnings

> **Warning:** Phase 1 (MVP) requires the agent to explicitly call `experience_save` after task completion. The automatic engine hook (`onTaskComplete`) is Phase 2+. Forgetting to save means knowledge is lost.

> **Warning:** `experience_query` before `open_session` is supported — it operates on the file system, not the browser. Use it to plan your task before launching Chrome.

> **Warning:** Knowledge stored for one URL pattern (e.g., `/dp/*`) is not automatically available for a different pattern (e.g., `/s?k=*`). The query matches by URL pattern specificity.

> **Note:** The knowledge store is file-backed YAML under `knowledge/` in the agent data directory. It is safe to version with git. Traces (under `knowledge/.traces/`) are ephemeral (30-day TTL) and not versioned.

## 6. Knowledge Store Layout

```
knowledge/
├── sites/<domain>.yaml       — One file per domain (L1–L3 knowledge)
├── .index.yaml               — In-memory index, materialized on write
├── .traces/<domain>/          — Raw execution traces (30-day TTL)
├── .archive/                  — Evicted artifacts (recoverable)
└── .wal/<domain>.log          — Write-ahead log (Phase 5+)
```

## 7. Reference Map

- [Design document](../../docs/experience-memory.md) — Full architecture and implementation guide
- [Proposal (v2)](../../coworker/plan/feature/evolve/synthesis-proposed-solution.md) — 2300-line technical design
- [CLAUDE.md](../../CLAUDE.md) — Project context and conventions
