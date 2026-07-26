---
title: "Experience System — Progressive Experience Memory (PEM)"
description: "Complete reference for Browser4's self-learning knowledge system: three-tier storage, four MCP tools, confidence model, intent classification, failure taxonomy, and retrieval fallback chain."
tier: reference
---

# Progressive Experience Memory (PEM)

The Experience system makes Browser4 **progressively smarter**: each completed task deposits reusable knowledge so future tasks — identical, similar, or on similar sites — complete faster with fewer steps.

## Overview

PEM is a persistent, file-backed knowledge layer implemented in Kotlin (`browser4-agentic`). It is exposed as an MCP tool domain (`"experience"`) with four tools, registered into the dispatch chain via Spring Boot auto-configuration in `browser4-rest`.

```
                  ┌──────────────────────────┐
                  │   MCP Tool Controller    │
                  │   (browser4-rest)         │
                  └──────────┬───────────────┘
                             │ dispatch
                  ┌──────────▼───────────────┐
                  │  ExperienceToolExecutor  │
                  │  domain = "experience"    │
                  │                          │
                  │  save / query / list     │
                  │  deep_learn              │
                  └──────────┬───────────────┘
                             │
                  ┌──────────▼───────────────┐
                  │     KnowledgeStore       │
                  │  (file-backed YAML)      │
                  │                          │
                  │  traces/   experience/   │
                  │  facts/    patterns/     │
                  └──────────────────────────┘
```

## Three-Tier Knowledge Model

Knowledge flows through three progressively refined layers:

```
TraceRecord              ExperienceStats           KnowledgeFacts
(raw, 30d TTL,           (mutable, aggregated,     (verified, immutable,
 never replayed)          confidence source)        used for replay)
───────────────────────▶ ───────────────────────▶ ───────────────────
 saveTrace()              updateStats()              saveFacts()
                                                     promoteToVerified()
```

| Layer | Directory | Mutability | Purpose |
|-------|-----------|------------|---------|
| **Traces** | `traces/<domain>/` | Immutable, 30d TTL | Raw execution records — exactly what happened |
| **Stats** | `experience/<domain>/` | Mutable, continuously updated | Aggregated success/failure counts; confidence derived here |
| **Facts** | `facts/<domain>/` | Immutable once VERIFIED | Authoritative selectors, blockers, interaction hints for replay |

A fourth layer — **Patterns** (`patterns/families/`, `patterns/categories/`, `patterns/universal/`) — stores cross-site generalizations promoted from multiple domains.

## Four MCP Tools

### experience_save — Fast Learning

Records a task trace and updates statistics. Runs in ~tens of milliseconds. No analysis tools execute.

| Argument | Required | Type | Description |
|----------|----------|------|-------------|
| `url` | Yes | String | The URL the task operated on |
| `trace` | Yes | String (JSON) | JSON-encoded `ExecutionTrace` (steps, selectors, extraction results) |
| `outcome` | No | String | `"success"` (default) or `"failure"` |
| `intent` | No | String | Free-text description of what the task was trying to do |
| `task_type` | No | String | One of the 12 canonical task types |

**What it does:**

1. Parses the trace JSON into an `ExecutionTrace`
2. Normalizes the URL, extracts domain and URL pattern
3. Classifies intent via `Intent.classify()` (keyword scoring)
4. For failures, classifies the error via `FailureCategory.classify()`
5. Writes a `TraceRecord` to `traces/<domain>/<timestamp>-<intent>.yaml`
6. Updates `ExperienceStats` via `withSuccess()` or `withFailure()`

**Returns:** `ExperienceSaveResult` — `{saved, domain, intent, confidence, retrieval_tier, failure_category, message}`

### experience_query — Intent-Based Retrieval

Queries stored knowledge before a task starts. Runs in ~single-digit milliseconds.

| Argument | Required | Type | Description |
|----------|----------|------|-------------|
| `url` | Yes | String | The target URL |
| `intent` | No | String | Free-text intent description for classification |

**6-level resolution fallback:**

1. **(domain, intent)** — exact match on domain + classified intent
2. **(domain, url_pattern)** — match by URL pattern within same domain
3. **(site_family, intent)** — cross-site family pattern (e.g., amazon-like sites)
4. **(site_category, intent)** — cross-category pattern (e.g., all marketplaces)
5. **(site_universal, intent)** — universal pattern (e.g., all ecommerce)
6. **Cold start (P5)** — no knowledge; full discovery required

**Returns:** `ExperienceQueryResult` — `{tier, confidence, domain, intent, url_pattern, primary_selectors, known_blockers, warnings, status}`

### experience_list — Diagnostic Browser

Lists stored knowledge entries with pagination and filtering.

| Argument | Required | Type | Default | Description |
|----------|----------|------|---------|-------------|
| `filter` | No | String | — | Filter by domain (partial case-insensitive match) |
| `intent_filter` | No | String | — | Filter by intent (partial case-insensitive match) |
| `page` | No | Int | 1 | Page number |
| `page_size` | No | Int | 20 | Results per page (max 100) |

**Returns:** `ExperienceListResult` — `{total, page, page_size, total_pages, entries[]}`

### experience_deep_learn — Deep Learning

Runs analysis tools to build or update `KnowledgeFacts`. Explicit call — runs in ~seconds.

| Argument | Required | Type | Default | Description |
|----------|----------|------|---------|-------------|
| `url` | Yes | String | — | The target URL |
| `intent` | Yes | String | — | Free-text intent description |
| `force` | No | Boolean | false | Bypass sampling; run even if confidence ≥ 0.90 |

**What it does:**

1. Classifies intent, loads current stats and facts
2. **Sampling check:** skips if confidence ≥ 0.90 and `force=false`
3. Loads the most recent successful trace for the domain
4. Creates `KnowledgeFacts` as `HYPOTHESIS` (first run) or updates existing
5. Calls `promoteToVerified()` — promotes if thresholds are met
6. Saves facts to `facts/<domain>/<intent>.yaml`

**Returns:** `DeepLearnResult` — `{completed, domain, intent, status_before, status_after, promoted, new_confidence, selectors_found, message}`

## Confidence Model

Confidence is **computed on-the-fly** from `ExperienceStats` — it is never stored.

```
confidence = α × success_ratio + (1 − α) × recency_factor

Where:
  success_ratio  = (successes + 1) / (successes + failures + 2)    [Laplace smoothing]
  recency_factor = 0.5 ^ (days_since_last_update / 60)            [60-day half-life]
  α              = 0.7

Constants:
  Initial (first save)  → 0.50
  Cap                    → 0.95
  Floor                  → 0.05
```

### Retrieval Tiers

| Tier | Confidence | Behavior |
|------|-----------|----------|
| **P1** Direct replay | ≥ 0.85 | Stored steps used without verification. Selectors used as-is. |
| **P2** Verify-before-replay | 0.60–0.84 | Each selector validated before use. |
| **P3** Hint mode | 0.40–0.59 | Knowledge suggests candidates; full discovery runs. |
| **P4** Advisory | < 0.40 | Knowledge surfaced as suggestion only. |
| **P5** Cold start | No data | No prior knowledge. Full exploration required. |

Tiers can be **degraded** by failure categories. If any recorded failure has `degradeRetrieval = true` (e.g., `ANTI_BOT`), the tier drops one level: P1 → P2, P2 → P3.

## Verification Pipeline

Knowledge progresses through four verification states:

```
HYPOTHESIS  ──▶  CANDIDATE  ──▶  VERIFIED (locked, immutable)
                                       │
                                  CONTESTED (disconfirmations > confirmations)
```

| Status | Trigger | Confidence Required | Replay Behavior |
|--------|---------|---------------------|-----------------|
| **HYPOTHESIS** | Initial `deep_learn` pass | Any | Not used for replay |
| **CANDIDATE** | `promoteToVerified()` | ≥ 0.60, ≥ 2 successes | Verify-before-replay |
| **VERIFIED** | `promoteToVerified()` | ≥ 0.85, ≥ 5 successes | Direct replay; selectors LOCKED |
| **CONTESTED** | Disconfirmations exceed confirmations | — | Under review |

### Promotion thresholds (from `KnowledgeStore.promoteToVerified()`)

- `confidence ≥ 0.85 AND successes ≥ 5` → **VERIFIED**
- `confidence ≥ 0.60 AND successes ≥ 2` → **CANDIDATE**
- Otherwise → stays at current status

## Intent Classification

Twelve intents, each with canonical action sequences used for keyword matching:

| Intent | Canonical Actions | Trigger Keywords |
|--------|-------------------|------------------|
| `BUY` | search → select → add_to_cart → checkout | buy, purchase, order, add to cart, cheapest |
| `SEARCH` | navigate → type → submit → extract | search, find, lookup, query |
| `BOOK` | search → select → fill_form → confirm | book, reserve, appointment, ticket, flight, hotel |
| `EXTRACT` | navigate → extract | extract, scrape, get data, fetch, collect |
| `COMPARE` | search → extract → compare | compare, vs, versus, difference between |
| `DOWNLOAD` | navigate → click → wait | download, save file, export |
| `READ` | navigate → scroll → extract | read, article, news, blog, post |
| `LOGIN` | navigate → fill → submit | login, sign in, authenticate |
| `CHECKOUT` | review → fill → confirm | checkout, place order, confirm purchase |
| `FILL_FORM` | navigate → fill → submit | fill, form, register, sign up, subscribe |
| `MONITOR` | navigate → check → compare | monitor, watch, track, alert, notify |
| `OTHER` | — | Fallback when no keywords match |

Classification uses **keyword scoring**: canonical action words in the text score +2, display-name match scores +3, intent-specific keywords score +4. Highest-scoring intent wins.

## Failure Taxonomy

Twelve failure categories, each with recoverability and recovery hints:

| Category | Recoverable | Degrades Tier | Detection Keywords |
|----------|-------------|---------------|--------------------|
| `SELECTOR_DRIFT` | Yes | No | selector, element not found, no such element |
| `VISUAL_DRIFT` | Yes | No | not visible, not clickable, hidden, obscured |
| `NETWORK` | Yes | No | timeout, network, connection, econnrefused |
| `AUTH_REQUIRED` | No | No | login, sign in, 401, 403, unauthorized |
| `PERMISSION_DENIED` | No | No | permission denied, not allowed, admin only |
| `OVERLAY_BLOCKED` | Yes | No | overlay, modal, popup, cookie, consent |
| `TIMING` | Yes | No | wait, loading, not ready, pending |
| **`ANTI_BOT`** | **No** | **Yes** | captcha, recaptcha, bot detection, unusual traffic |
| `LAZY_LOADING` | Yes | No | lazy, data-src, placeholder, skeleton |
| `AB_EXPERIMENT` | Yes | No | a/b, variant, experiment, split test |
| `UNEXPECTED_REDIRECT` | Yes | No | redirect, moved, url changed |
| `UNKNOWN` | No | No | Fallback |

Only `ANTI_BOT` degrades the retrieval tier. Failures are classified by keyword matching against the error message and the last attempted selector context.

## URL Normalization

```text
Input:  https://www.amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1?keywords=laptop&qid=1234567#reviews
Output: amazon.com/dp/B0CXJ1NT4B
```

### Rules (in order)

1. Strip `www.` prefix from host
2. Strip trailing slash from path
3. Strip fragment (`#section`)
4. Strip query parameters **except** semantically significant ones: `q`, `id`, `page`, `k`
5. URL patterns replace high-cardinality path segments with `*` (segments with digits, length > 4)

### Pattern Matching

Patterns like `/dp/*` match concrete URLs like `/dp/B0CXJ1NT4B`. When multiple patterns match, the one with the highest **specificity** (count of literal, non-wildcard segments) wins.

## Storage Layout

```
{knowledge_dir}/
├── traces/
│   └── <domain>/
│       └── 2026-07-26-143025-<intent>.yaml    ← TraceRecord (immutable, 30d TTL)
├── experience/
│   └── <domain>/
│       └── <intent>.yaml                       ← ExperienceStats (mutable)
├── facts/
│   └── <domain>/
│       └── <intent>.yaml                       ← KnowledgeFacts (verified, immutable)
├── patterns/
│   ├── families/<name>.yaml                    ← L4: site-family patterns
│   ├── categories/<name>.yaml                  ← L4: site-category patterns
│   └── universal/<name>.yaml                   ← L4: universal patterns
├── .index.yaml                                 ← in-memory index (regenerated lazily)
└── .archive/                                   ← evicted artifacts
```

## Concurrency Model

- **Readers never lock** — atomic rename (write to `.tmp` → `fsync` → rename) guarantees a consistent view
- **Writers to the same domain serialize** via per-domain `Mutex`
- **Writers to different domains run concurrently**

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `browser4.experience.enabled` | `true` | Enable/disable the entire PEM system |
| `knowledge.dir` | `knowledge/` (relative) | Knowledge store root directory |

The `ExperienceToolMountConfiguration` in `browser4-rest` registers the executor via Spring Boot auto-configuration (conditional on `browser4.experience.enabled=true`). The executor implements `ToolMount`, so `PluginManager` automatically wires it into both the MCP dispatcher and the LLM agent tool system.

## Source Files

All in `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/experience/`:

| File | Key Types | Purpose |
|------|-----------|---------|
| `ExperienceToolExecutor.kt` | `ExperienceToolExecutor` | MCP tool executor — dispatches `save`, `query`, `list`, `deep_learn` |
| `ExperienceModels.kt` | `ExecutionTrace`, `ExperienceQueryResult`, `ExperienceSaveResult`, `DeepLearnResult`, `ExperienceListResult`, `ActionStep`, `SelectorEntry`, `TaskType`, `SuccessCriteria` | All data classes for tool I/O |
| `ExperienceStats.kt` | `ExperienceStats`, `SelectorStats` | Aggregated statistics with confidence and tier computation |
| `KnowledgeStore.kt` | `KnowledgeStore` | File-backed YAML store with atomic writes, query resolution, and promotion |
| `KnowledgeFacts.kt` | `KnowledgeFacts`, `SiteFacts`, `PageFacts`, `VerifiedSelector`, `BlockerInfo`, `PromotionEvent`, `PatternPromotion` | Verified immutable knowledge layer |
| `IntentModels.kt` | `Intent`, `FailureCategory`, `VerificationStatus`, `PromotionLevel` | Classification enums with keyword-based matching |
| `TraceRecord.kt` | `TraceRecord`, `PageState` | Raw immutable execution record |
| `UrlNormalizer.kt` | `UrlNormalizer` | URL normalization, pattern matching, specificity scoring |

### Spring Configuration

| File | Purpose |
|------|---------|
| `browser4-rest/.../config/ExperienceToolMountConfiguration.kt` | Registers `KnowledgeStore` and `ExperienceToolExecutor` as Spring beans, implements `ToolMount` |

## Core Loop

```
Before task ──▶ experience_query ──▶ Get stored selectors, steps, blockers
    │                                      │
    ▼                                      ▼
Execute task                        P1: Replay directly
    │                               P2: Verify then replay
    ▼                               P3: Hint mode (verify all)
After task  ──▶ experience_save ──▶ P4: Advisory only
    │                               P5: Cold start (no knowledge)
    ▼
(periodic) ──▶ experience_deep_learn ──▶ Build facts, promote status
```

## Promotion Hierarchy (Cross-Site)

Patterns ascend a 4-level hierarchy as they're confirmed across more sites:

| Level | Min Sites | Example |
|-------|-----------|---------|
| **SITE** | 1 | Selectors for `amazon.com/dp/*` |
| **FAMILY** | 2 | Shared across amazon-like sites (amazon, ebay, walmart) |
| **CATEGORY** | 3 | Shared across all marketplaces (amazon, ebay, etsy) |
| **UNIVERSAL** | 4 | Shared across all ecommerce sites |

A `PatternPromotion` can advance when `confirmed_sites ≥ minSitesRequired` and the confirmation ratio ≥ 75%.

## Related Documents

- [Skill: browser4-experience](../skills/browser4-experience/SKILL.md) — Agent-facing usage guide
- [CLAUDE.md](../CLAUDE.md) — Project context and conventions
