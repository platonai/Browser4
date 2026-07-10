# Issue Review Guide

How to review browser4-cli issues — for humans and AI reviewers.

## Core Principle: Judge for the AI Agent, not the Human

browser4-cli is built for **AI agents** to use programmatically. A UX problem visible to a human reading terminal output may be irrelevant to an AI parsing structured data. Always ask:

> *"Does this block or mislead an AI agent?"*

If the answer is no, the issue is probably a **REJECT** or **WONTFIX**.

---

## Decision Tree

```
Issue reported
│
├─ Is the issue REAL — can it actually be reproduced?
│  ├─ NO  → 🚫 REJECT
│  │        "Behavior is intentional", "not actually a problem",
│  │        "already works correctly", "misunderstands the tool"
│  │
│  └─ YES ── Is this a REAL problem for an AI AGENT user?
│       ├─ NO  → 🚫 REJECT (or ⏸ DEFER if minor)
│       │        e.g., "output is verbose for humans" — AI can grep it
│       │        e.g., "no inline refs in snapshot -i" — AI reads YAML fine
│       │
│       └─ YES ── Is the fix ALREADY designed or partially applied?
│            ├─ YES → 🔧 ACCEPT with improvements
│            │        "Issue valid, fix needs refinement"
│            │        Describe what to change in Notes
│            │
│            └─ NO ── Same root cause as another issue in this batch?
│                 ├─ YES → 📋 DUPLICATE
│                 │        Reference which issue number in Notes
│                 │
│                 └─ NO ── Can we actually fix this?
│                      ├─ NO (external) → ❌ WONTFIX
│                      │   Third-party sites, platform quirks,
│                      │   upstream behavior, intentional design trade-offs
│                      │
│                      ├─ NO (too big right now) → ⏸ DEFER
│                      │   Architectural changes, needs more design,
│                      │   low priority relative to other work
│                      │
│                      └─ YES → ✅ ACCEPT
│                          Clear bug, broken behavior, missing feature
│                          that blocks AI agent workflows
```

---

## Decisions at a Glance

| Decision | When to Use | Signal Phrase |
|----------|------------|---------------|
| **✅ ACCEPT** | Clear bug, blocks AI agents, fix is well-scoped | "This is a real bug that breaks agent workflows" |
| **🔧 ACCEPT with improvements** | Valid but suggested fix needs refinement | "The issue is real, but the fix should..." |
| **⏸ DEFER** | Real problem but large scope, architectural, or low priority | "Acknowledged, but needs more design / lower priority" |
| **❌ WONTFIX** | External/upstream, platform-specific, intentional design | "Can't control this / intentional trade-off" |
| **🚫 REJECT** | Not a real problem, intentional behavior, human-only concern | "Not actually a problem for AI agents" |
| **📋 DUPLICATE** | Same root cause as another issue | "Same cause as Issue N — ..." |

---

## Real Examples from Past Reviews

### ACCEPT

> **"`get all` arrays produce unaligned multi-field data"**
> Severity: High · Category: Reliability
>
> The arrays from `get all text` for different fields have different lengths
> and no index alignment, making cross-field correlation impossible for agents.

→ Clear bug that blocks data extraction workflows. Fix is well-scoped.

---

### ACCEPT with improvements

> **"Relative SQL file path resolution from CLI directory is confusing"**
> Severity: Low · Category: UX
>
> **Notes:** Should resolve `@file` paths relative to the original invocation directory.
> **Fix applied (2026-07-07):** Changed `resolve_sql_file()` to try the Browser4
> repo root FIRST, then fall back to CWD.

→ Issue was valid, and a fix was designed and applied during review. Notes
  describe what was done differently from the AI's original suggestion.

---

### DEFER

> **"`htmlsnapshot inspect` auto-discover fails on e-commerce product grids"**
> Severity: High · Category: Reliability
>
> The auto-discovery algorithm picks the first repeating element from `:root`,
> which on Amazon means navigation divs, not product cards.

→ Real problem, but fixing it requires refactoring the inspect algorithm's
  container-priority heuristic — a significant architectural change. Defer
  until the inspect subsystem gets dedicated attention.

---

### WONTFIX

> **"Documentation's recommended CSS selectors fail on non-English Amazon locale"**
> Severity: Medium · Category: Documentation
>
> Amazon serves different HTML structures depending on detected locale.
> The `h2 a.a-link-normal` selector works on amazon.com/en but not on
> amazon.com/sg (Chinese UI).

→ Real observation, but the tool cannot control Amazon's per-locale DOM.
  WONTFIX — instead, documentation should teach users to run `htmlsnapshot
  inspect` to discover locale-specific selectors at runtime.

---

### REJECT

> **"Interactive snapshot (`-i`) does not display element refs inline"**
> Severity: Medium · Category: UX
>
> **Notes:** No matter — AI agents can understand it easily.

→ The reviewer judged this through the AI-as-user lens. The YAML output
  already contains refs; AI agents parse structured data, not inline hints.
  Human readability is not the design target.

---

### DUPLICATE

> **"`tab-new` does not auto-switch to the new tab"**
> → DUPLICATE of Issue 3 in the same file. Both stem from the same root
>   cause: stale CDP session state after tab creation.

---

## Severity Guidelines

| Severity | Meaning | Typical Action |
|----------|---------|---------------|
| **Critical** | Data loss, crash, security issue | Almost always ACCEPT — fix immediately |
| **High** | Blocks key workflows, no workaround | ACCEPT if fixable; DEFER if architectural |
| **Medium** | Degrades experience, workaround exists | ACCEPT if clear fix; DEFER/WONTFIX if edge case |
| **Low** | Minor friction, cosmetic, nice-to-have | ACCEPT quick wins; WONTFIX dev-mode friction |

## Category Guidelines

| Category | What It Covers | Review Lens |
|----------|---------------|-------------|
| **Reliability** | Crashes, timeouts, flaky behavior, silent failures | ACCEPT if reproducible |
| **Product** | Missing features, incorrect behavior, data quality | ACCEPT if blocks agent goals; WONTFIX if feature request |
| **UX** | Command ergonomics, output clarity, workflow friction | Filter hard: only ACCEPT if AI agents suffer |
| **Documentation** | Wrong, missing, or misleading docs | ACCEPT if misleads agents; WONTFIX if human-readability only |
| **Discoverability** | Hard-to-find commands, confusing help, hidden features | ACCEPT if blocking; DEFER if adding complexity |

---

## The Notes Field

Write a brief rationale for every non-ACCEPT decision. Past human reviewers rarely wrote notes — but for AI-assisted review, notes are the key feedback channel that distinguishes a good decision from an opaque one.

| Decision | Notes Template |
|----------|---------------|
| REJECT | "This is intentional because..." or "AI agents handle this by..." |
| DEFER | "Postponed because... Will revisit when..." |
| WONTFIX | "Cannot fix because... Upstream/external/design trade-off" |
| DUPLICATE | "Same root cause as Issue N — ..." |
| ACCEPT with improvements | "Fix direction is correct, but also handle..." |

---

## Downstream Pipeline Impact

The `buildSummaryContent` function in `server.js` uses a binary split:

| Decisions | Pipeline Treatment |
|-----------|-------------------|
| **ACCEPT / ACCEPT with improvements** | Full detail preserved → goes to `main/1ready` for execution |
| **DEFER / WONTFIX / REJECT / DUPLICATE** | Condensed to one-line abstract → archived for reference |

The critical call is **ACCEPT vs. not-ACCEPT**. Getting that right matters most.
The sub-decisions (DEFER vs. WONTFIX vs. REJECT) are preserved as labels but
don't change how the issue flows through the system.

---

## Review Checklist

Before finalizing a review, verify:

- [ ] Every issue has a decision (no unreviewed issues)
- [ ] Non-ACCEPT decisions have a rationale in Notes
- [ ] DUPLICATE decisions reference the specific issue number
- [ ] The issue is judged through the **AI agent as user** lens
- [ ] ACCEPT with improvements describes what to change (not just "fix is wrong")
- [ ] WONTFIX decisions cite an external constraint or intentional trade-off
- [ ] Severity and category labels are still accurate after reading the full issue
