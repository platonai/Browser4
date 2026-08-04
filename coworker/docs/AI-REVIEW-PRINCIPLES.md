# AI Review Decision-Making Principles

How the AI agent (claude / kimi / copilot) evaluates `.issues.md` files and
chooses one of six review decisions. This document is the canonical reference
for the decision algorithm; `REVIEW-GUIDE.md` supplies worked examples and
the downstream pipeline breakdown.

## Core Principle: Judge for the AI Agent, not the Human

browser4-cli is built for **AI agents** to use programmatically. Every issue
must be evaluated through this single lens:

> *"Does this block or mislead an AI agent?"*

If the answer is no, the issue is probably **REJECT** or **WONTFIX** — even
if a human user would find it annoying. Conversely, an issue invisible to a
human (e.g., malformed JSON in an MCP response) can be **ACCEPT** if it
breaks an agent's parsing logic.

---

## Decision Algorithm

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

## The Six Decisions

| # | Decision | When to Use | Signal Phrase |
|---|---|---|---|
| 1 | **ACCEPT** | Clear bug, blocks AI agents, fix is well-scoped | "This is a real bug that breaks agent workflows" |
| 2 | **ACCEPT with improvements** | Valid issue but suggested fix needs refinement | "The issue is real, but the fix should..." |
| 3 | **DEFER** | Real problem but large scope, architectural, or low priority | "Acknowledged, but needs more design / lower priority" |
| 4 | **WONTFIX** | External/upstream, platform-specific, intentional design | "Can't control this / intentional trade-off" |
| 5 | **REJECT** | Not a real problem, intentional behavior, human-only concern | "Not actually a problem for AI agents" |
| 6 | **DUPLICATE** | Same root cause as another issue | "Same cause as Issue N — ..." |

### Decision Details

**ACCEPT (1)** — The issue is a genuine defect that blocks or degrades
AI-agent workflows. The suggested fix is correct and well-scoped. No
refinement needed.

**ACCEPT with improvements (2)** — The issue is genuine, but the suggested
fix is incomplete, targets the wrong layer, or misses edge cases. The Notes
field must describe *what* to change (not just "fix is wrong").

**DEFER (3)** — The problem is real but fixing it now is impractical:
architectural scope, needs cross-cutting design, or lower priority than
other work. Notes must include *why* it's deferred and ideally a trigger
condition for revisiting.

**WONTFIX (4)** — The problem exists but is outside our control: third-party
behavior, platform quirks, upstream dependencies, or an intentional design
trade-off. Notes must cite the external constraint.

**REJECT (5)** — The issue is not a real problem for AI agents. Either the
behavior is intentional, the reporter misunderstood the tool, or the concern
only matters for human users reading terminal output. This is the
highest-stakes decision — over-rejecting hides real bugs; under-rejecting
floods the pipeline with noise.

**DUPLICATE (6)** — The issue shares a root cause with another issue in the
same batch. Notes must reference the specific issue number. Duplicates
should NOT be REJECTed — the observation may be valid, just not novel.

---

## Severity Weighting

Severity acts as a prior on the decision, not a rule:

| Severity | Default Lean | Exception |
|---|---|---|
| **Critical** | ACCEPT | Only REJECT if provably false (crash that can't reproduce) |
| **High** | ACCEPT if fixable | DEFER if architectural scope |
| **Medium** | ACCEPT if clear fix | DEFER/WONTFIX if edge case with workaround |
| **Low** | ACCEPT quick wins | WONTFIX for cosmetic or dev-mode-only friction |

## Category Weighting

Category shapes *what evidence matters* for the decision:

| Category | Key Question | Tilt |
|---|---|---|
| **Reliability** | Can it be reproduced? | ACCEPT if reproducible |
| **Product** | Does it block an agent's goal? | ACCEPT if yes; WONTFIX if feature-request |
| **UX** | Does it break *agent* parsing/automation? | Filter harder — most UX issues are human-only |
| **Documentation** | Does it *mislead* an agent? | ACCEPT if misleading; WONTFIX if merely unclear to humans |
| **Discoverability** | Can an agent not find a needed command? | ACCEPT if blocking; DEFER if adding complexity |

---

## Batch vs. Single-Issue Review

### Batch review (`Invoke-AiReviewBatch`)
- All issues in the file are sent in **one prompt**
- Sections are truncated to 500 chars each for context-window efficiency
- Sibling context is implicit (all issues visible together)
- The AI must detect duplicates across the batch
- Cross-issue patterns (same root cause, severity consistency) are expected
- Response format: `ISSUE N:\nDECISION: <decision>\nNOTES: <rationale>`

### Single-issue review (`Invoke-AiReviewSingle`)
- One issue per prompt
- Full section text (no truncation)
- Sibling issues summarized as a compact list (title + current decision)
- Used in interactive mode when user presses `a` on a single issue
- Response format: `DECISION: <decision>\nNOTES: <rationale>`

---

## The Notes Field

The Notes field is the **key feedback channel** — it distinguishes a
reasoned decision from an opaque one. Every non-ACCEPT decision requires a
rationale:

| Decision | Notes Template |
|---|---|
| ACCEPT | (optional) "Confirmed because..." |
| ACCEPT with improvements | "Fix direction is correct, but also handle..." |
| DEFER | "Postponed because... Will revisit when..." |
| WONTFIX | "Cannot fix because... Upstream/external/design trade-off" |
| REJECT | "This is intentional because..." or "AI agents handle this by..." |
| DUPLICATE | "Same root cause as Issue N — ..." |

When the AI provides a suggested decision, it is recorded as
`[AI suggested: ACCEPT]` prefix in Notes. Human reviewers can see the
AI's reasoning even if they override the decision.

---

## Response Parsing & Normalization

The AI's raw text response is parsed with regex for structured fields
(`DECISION:`, `NOTES:`) and then normalized through fuzzy matching:

1. **Exact match** against the six canonical decision strings
2. **Fuzzy match** on lowercase substrings:
   - `"accept with"` / `"improve"` → ACCEPT with improvements
   - `"accept"` → ACCEPT
   - `"defer"` → DEFER
   - `"wontfix"` / `"won't fix"` → WONTFIX
   - `"reject"` → REJECT
   - `"duplicate"` → DUPLICATE
3. **Fallback**: If no decision is parseable, the issue remains unreviewed
   (or defaults to DEFER in inline mode when the AI call itself fails)

---

## Downstream Pipeline Impact

The binary split that governs everything:

```
ACCEPT / ACCEPT with improvements
  → Full detail preserved in summary
  → File moves to tasks/main/1ready/
  → Coworker executes the fixes

DEFER / WONTFIX / REJECT / DUPLICATE
  → Condensed to one-line abstract
  → Archived for reference only
  → No execution triggered
```

**Getting ACCEPT vs. not-ACCEPT right is the single most important call.**
The sub-decisions within "not-ACCEPT" are preserved as labels but don't
change how the issue flows through the pipeline.

---

## Pre-Review Sanity Checks

Before submitting a review decision, verify:

- [ ] The issue is judged through the **AI agent as user** lens
- [ ] Non-ACCEPT decisions have a rationale in Notes
- [ ] DUPLICATE decisions reference the specific issue number
- [ ] ACCEPT with improvements describes *what* to change (not just "fix is wrong")
- [ ] WONTFIX decisions cite an external constraint or intentional trade-off
- [ ] Severity and category labels are still accurate after reading the full issue body
- [ ] The decision is consistent with how sibling issues were judged (no contradicting rationales)
