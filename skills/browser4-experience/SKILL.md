---
name: browser4-experience
title: "Progressive Experience Memory (PEM)"
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

## 2. Key Concepts

- **Knowledge store** — a local directory tree of memory artifacts (tasks, traces, index); the store layout is described in section 8.
- **experience_save** — persist a completed task's trace (selectors, steps, blockers) into the store.
- **experience_query** — retrieve relevant past traces before starting a new task; returns a replay tier P1-P5.
- **experience_list** — inspect what is stored, filtered by domain or intent.
- **Replay tiers** — P1 (replay directly) → P5 (cold start), the confidence ladder used by the Decision Tree in section 4.

## 3. Command Map

### experience_save

Persists a task execution trace to the knowledge store.

| Argument | Required | Description |
|----------|----------|-------------|
| `url` | Yes | The URL the task operated on |
| `trace` | Yes | JSON-encoded ExecutionTrace (steps, selectors, extraction results) |
| `outcome` | No | `"success"` (default) or `"failure"` |
| `task_type` | No | Canonical task type (e.g., `extract_product_list`, `publish_post`) |
| `intent` | No | Free-text description of what the task was trying to do |
| `facts` | No | Retrospective knowledge patch (inline JSON, or `@file.json` through the CLI): `selectors` / `interaction_hints` / `known_blockers` / `anti_patterns` (camelCase and snake_case keys both accepted), merged into the `(domain, intent)` facts entry — the writer path for lessons learned. Refused when the entry is VERIFIED (immutable); the response then reports `facts_rejected` |

**Success path:** Knowledge promoted with initial confidence 0.50. Subsequent verified successes raise confidence.
**Failure path:** Negative evidence recorded (failure category classified from the trace). Failed selectors are **not** automatically turned into anti-patterns — record lessons explicitly with `facts` (e.g. `anti_patterns`) via `experience_save --facts` (or the `facts` argument), or let `experience_deep_learn` promote knowledge later.
**Response:** the save result includes `facts_merged`, `facts_status`, `facts_rejected`, and `facts_message` when `facts` was supplied.

### experience_query

Queries stored knowledge before starting a task.

| Argument | Required | Description |
|----------|----------|-------------|
| `url` | Yes | The target URL |
| `intent` | No | Free-text intent description |

**Returns:** JSON with `tier`, `confidence`, `primary_selectors`, `extraction_query`, `known_blockers`, `warnings`, `steps`.

### experience_list

Lists stored knowledge entries (diagnostic/debug tool).

| Argument | Required | Description |
|----------|----------|-------------|
| `filter` | No | Filter by domain (partial match) |
| `intent_filter` | No | Filter by intent (partial match) |
| `page` | No | Page number (default 1) |
| `page_size` | No | Results per page (default 20, max 100) |

## 4. Decision Trees

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

## 5. Retrieval Tiers

| Tier | Confidence | Behavior |
|------|-----------|----------|
| **P1** Direct replay | ≥ 0.85 | Steps executed without verification. Selectors used as-is. |
| **P2** Verify-before-replay | 0.60–0.84 | Each selector validated via `htmlsnapshot get` before use. |
| **P3** Hint mode | 0.40–0.59 | Playbook provides suggestions but full discovery runs. |
| **P4** Advisory | < 0.40 | Knowledge surfaced as suggestion only. Full discovery required. |
| **P5** Cold start | No data | No prior knowledge. Full exploration. |

## 6. Critical Warnings

> **Note:** The automatic engine hook is **live** (since 2026-08-24): `RobustBrowserAgent` auto-deposits completed/failed tasks into the knowledge store (`MemoryConsolidator` → PEM fusion) and auto-injects recalled knowledge into the run-start `## Memory` section. Calling `experience_save` yourself is still supported for richer traces and diagnostics, but forgetting it no longer loses knowledge.

### experience_save

Persists a task execution trace to the knowledge store.

| Argument | Required | Description |
|----------|----------|-------------|
| `url` | Yes | The URL the task operated on |
| `trace` | Yes | JSON-encoded ExecutionTrace (steps, selectors, extraction results) |
| `outcome` | No | `"success"` (default) or `"failure"` |
| `task_type` | No | Canonical task type (e.g., `extract_product_list`, `publish_post`) |
| `intent` | No | Free-text description of what the task was trying to do |
| `facts` | No | Retrospective knowledge patch (inline JSON, or `@file.json` through the CLI): `selectors` / `interaction_hints` / `known_blockers` / `anti_patterns` (camelCase and snake_case keys both accepted), merged into the `(domain, intent)` facts entry — the writer path for lessons learned. Refused when the entry is VERIFIED (immutable); the response then reports `facts_rejected` |

**Success path:** Knowledge promoted with initial confidence 0.50. Subsequent verified successes raise confidence.
**Failure path:** Negative evidence recorded (failure category classified from the trace). Failed selectors are **not** automatically turned into anti-patterns — record lessons explicitly with `facts` (e.g. `anti_patterns`) via `experience_save --facts` (or the `facts` argument), or let `experience_deep_learn` promote knowledge later.
**Response:** the save result includes `facts_merged`, `facts_status`, `facts_rejected`, and `facts_message` when `facts` was supplied.

**Recording a lesson with `facts`:** a lesson (a selector that broke, a blocker, an anti-pattern) can be recorded immediately after the task — no need to wait for `deep_learn`:

```text
# MCP tool form
experience_save(url="<target-url>", trace="<execution trace JSON>", outcome="success",
                intent="extract product details", task_type="extract_product_list",
                facts='{"interaction_hints":["open the price popover before reading"],
                        "anti_patterns":["clicking the thumbnail before the modal loads"]}')

# CLI equivalent — trace is inline JSON; --facts accepts inline JSON or @file.json
browser4-cli experience save "https://example.com/products" '<trace-json>' --facts @lessons.json
```

### experience_query

Queries stored knowledge before starting a task.

| Argument | Required | Description |
|----------|----------|-------------|
| `url` | Yes | The target URL |
| `intent` | No | Free-text intent description |

**Returns:** JSON with `tier`, `confidence`, `primary_selectors`, `extraction_query`, `known_blockers`, `warnings`, `steps`.

### experience_list

Lists stored knowledge entries (diagnostic/debug tool).

| Argument | Required | Description |
|----------|----------|-------------|
| `filter` | No | Filter by domain (partial match) |
| `intent_filter` | No | Filter by intent (partial match) |
| `page` | No | Page number (default 1) |
| `page_size` | No | Results per page (default 20, max 100) |

> **Warning:** `experience_query` before `open_session` is supported — it operates on the file system, not the browser. Use it to plan your task before launching Chrome.

> **Warning:** Knowledge stored for one URL pattern (e.g., `/dp/*`) is not automatically available for a different pattern (e.g., `/s?k=*`). The query matches by URL pattern specificity.

> **Note:** The knowledge store is file-backed YAML under `knowledge/`, resolved **relative to the backend process working directory** (there is no `knowledge.dir` config option to relocate it). The store is safe to version with git. Raw traces (under `knowledge/traces/<domain>/`) are ephemeral (30-day TTL) and not versioned; facts entries (`knowledge/facts/<domain>/<intent>.yaml`) are immutable once VERIFIED.

## 7. Quick Patterns

### Before a task — query prior knowledge

The store is **file-level YAML per (domain, intent)** — no per-site blob files:

```
knowledge/                          ← root: relative to the backend process CWD
├── traces/<domain>/                ← TraceRecords (immutable, 30-day TTL)
├── experience/<domain>/            ← ExperienceStats (mutable; confidence source)
└── facts/<domain>/                 ← KnowledgeFacts — one <intent>.yaml per (domain, intent)
                                      (VERIFIED entries are immutable; merge is refused)
```

```text
experience_query(url="<target-url>", intent="extract product details")
```

### After a task — save what you learned

```text
# MCP tool form — trace (JSON) is required; facts patches knowledge onto the (domain, intent) entry
experience_save(url="<target-url>", trace="<execution trace JSON>", outcome="success",
                intent="extract product details", task_type="extract_product_list",
                facts='{"interaction_hints":["open the price popover before reading"],
                        "anti_patterns":["clicking the thumbnail before the modal loads"]}')

# CLI equivalent — trace is inline JSON; --facts accepts inline JSON or @file.json
browser4-cli experience save "https://example.com/products" '<trace-json>' --facts @lessons.json
```

A lesson (selector that broke, a blocker, an anti-pattern) can be recorded immediately after the task with `--facts` — no need to wait for `deep_learn`.

### Inspect the store

```text
experience_list(filter="amazon")
```

## 8. Knowledge Store Layout

The store is **file-level YAML per (domain, intent)** — no per-site blob files:

```
knowledge/                          ← root: relative to the backend process CWD
├── traces/<domain>/                ← TraceRecords (immutable, 30-day TTL)
├── experience/<domain>/            ← ExperienceStats (mutable; confidence source)
└── facts/<domain>/                 ← KnowledgeFacts — one <intent>.yaml per (domain, intent)
                                      (VERIFIED entries are immutable; merge is refused)
```

## 8. Reference Map

- [Design document](../../docs/experience-memory.md) — Full architecture and implementation guide
- [CLAUDE.md](../../CLAUDE.md) — Project context and conventions



