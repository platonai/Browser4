# SKILL.md Audit Methodology

A systematic process for reviewing SKILL.md files to ensure they are optimized for AI agent consumption. Apply this checklist to every SKILL.md in the repository.

## Audit Principles

SKILL.md files are read by AI agents, not humans. An agent lands on the file, scans for the information it needs, and acts. The file must answer four questions immediately:

1. **What does this skill do?** (frontmatter `description`)
2. **How do I use it?** (Quick Start / core loop)
3. **What are the critical rules I must not break?** (Golden Rules)
4. **What do I do when something fails?** (Error Recovery)

Everything else — installation, advanced features, reference tables — is secondary. Put it later.

## Audit Checklist

### 1. Frontmatter (P0 — file is broken without this)

| Check | Requirement |
|-------|-------------|
| `name` | Present, kebab-case, unique across all skills |
| `description` | Present, 1–3 sentences. Must include BOTH what the skill does AND concrete triggers ("Use when the user needs to X, Y, or Z"). No triggers → agent won't know when to invoke |
| `allowed-tools` | Correctly scoped. Prefer narrow tool patterns (`Bash(browser4-cli:*)`) over broad ones. Missing tools → runtime errors |

**Anti-pattern:**
```yaml
description: Browser automation CLI for AI agents.
```
**Correct:**
```yaml
description: Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages.
```

### 2. Information Hierarchy (P0 — agent misses critical info)

| Check | Requirement |
|-------|-------------|
| Quick Start section | Present within the first ~50 lines. Shows the 3–7 step core interaction loop |
| Golden Rules | Critical invariants called out with a red marker (`🔴`) or bold warning box. One rule per callout |
| Installation | At the bottom of the file (appendix), not the first section. Agents don't install tools |
| Reference material | Below operational content. Command references, function lists, advanced features come after Quick Start, Concepts, and Error Recovery |

**Test:** Can an agent read the first 80 lines and successfully complete a basic task? If not, restructure.

### 3. Golden Rules & Invariants (P0 — agent corrupts state without these)

Every SKILL.md has at least one non-obvious invariant that, if violated, breaks everything. Identify it and make it unmissable.

| Check | Requirement |
|-------|-------------|
| Critical invariants identified | What rule, if broken, corrupts state or produces wrong results? |
| Prominence | `🔴` marker, bold text, or blockquote with `> **` at the top of the file |
| Explanation | WHY the rule exists, not just what it is. Agents follow rules more reliably when they understand the reason |
| Scope | Clear boundaries — when does the rule apply and when doesn't it? |

**Example:** "Refs are single-use" in `skill/SKILL.md` — explains that refs are CDP backend node IDs, ephemeral, invalid after any page-modifying command. Without this, an agent would store refs across interactions and every subsequent command would fail.

### 4. Decision Guidance (P1 — agent picks wrong tool)

When the skill offers multiple ways to accomplish similar tasks, the file must guide the choice.

| Check | Requirement |
|-------|-------------|
| Method comparison table | Decision table or flow chart: "Use X when…, use Y when…" |
| Anti-guidance | Explicit "Avoid X for Y" statements. Knowing when NOT to use a tool is as important as knowing when to use it |
| Fallback chains | If method A fails, what should the agent try next? Documented escape hatches |

### 5. Error Recovery (P1 — agent gets stuck)

| Check | Requirement |
|-------|-------------|
| Exit codes documented | Which commands exit non-zero and under what conditions? |
| Recovery table | Symptom → likely cause → recovery action. At least 5 entries covering the most common failures |
| Staleness handling | What happens after long idle? After network interruption? After browser crash? |
| Rate-limiting guidance | What happens when the target site rate-limits? How to detect and recover? |

**Format:**
```markdown
| Symptom | Likely cause | Recovery |
|---------|-------------|----------|
| `snapshot` exits non-zero | Page not loaded | `wait --load=networkidle` then retry |
| `domsnapshot get` returns `[]` | DOM serialization mismatch | Fall back to `eval` or X-SQL |
```

### 6. Example Quality (P1 — agent copies broken examples)

| Check | Requirement |
|-------|-------------|
| Copy-pasteable | Every example is a complete, valid command. No pseudocode, no `...` that hides required args |
| Realistic | Examples use real URLs, real selectors, real values. Not `example.com/foo` |
| Platform-aware | If platform differences matter (Windows quoting vs Linux), show both or show the safe cross-platform approach |
| Output shown | Expected output is shown or described so the agent knows what success looks like |

### 7. Common Pitfalls (P2 — prevents repeated mistakes)

| Check | Requirement |
|-------|-------------|
| Anti-pattern list | 5–10 common mistakes with brief explanations |
| Distinct from error recovery | Pitfalls are mistakes in approach (using `-i` on e-commerce, cat'ing snapshot files). Error recovery is runtime failure response |
| Actionable | Each pitfall includes the fix, not just the warning |

### 8. Conciseness (P2 — reduces context consumption)

| Check | Requirement |
|-------|-------------|
| No duplicate information | Same fact stated once. Cross-reference with links, don't repeat |
| No narrative fluff | "Browser4-cli is a powerful tool that enables..." → cut. State what it does, not that it's powerful |
| Tables over prose for comparisons | Decision tables, flag tables, option tables. Dense information in scannable format |
| Tip blocks are single-topic | One callout = one idea. Don't stack 6 tips in one blockquote |
| File length | Under 1000 lines. If longer, move deep-reference content to separate files under `references/` and link from SKILL.md |

### 9. Reference Completeness (P2 — agent can't find details)

| Check | Requirement |
|-------|-------------|
| Every command documented | All subcommands have syntax, options table, and at least one example |
| Link quality | Reference links point to files that exist. No 404s |
| External references | All linked `references/*.md` files exist in the same directory as SKILL.md |

### 10. Consistency (P2 — agent confused by contradictions)

| Check | Requirement |
|-------|-------------|
| Terminology | Same word for same concept throughout. Don't say "element reference" in one section and "ref" in another without establishing equivalence |
| Tone | Imperative, direct. "Use X" not "You can use X" or "One might use X" |
| Formatting | Consistent code fence languages (bash vs sh). Consistent table formatting. Consistent callout style |

## Audit Process

### Phase 1: Quick Scan (5 minutes)

1. Read the frontmatter. Does `description` include concrete triggers?
2. Read the first 80 lines. Can you execute a basic task?
3. Find the Golden Rule. Is it prominent enough?
4. Find the Error Recovery section. Does it have a recovery table?

If any of these fail, the file needs restructuring before deeper review.

### Phase 2: Structural Review (15 minutes)

1. Map the section hierarchy. Is the most operationally critical content at the top?
2. Check for missing sections: Quick Start, Golden Rules, Decision Guidance, Error Recovery, Common Pitfalls
3. Verify Installation is at the bottom (or absent)
4. Check that every command has: syntax, options, example
5. Verify all reference links resolve

### Phase 3: Content Review (20 minutes)

1. Execute every example command mentally. Does it parse? Are args complete?
2. Read every tip/warning/callout. Is each single-topic and actionable?
3. Check the decision guidance. Does the agent know when to use X vs Y?
4. Read the error recovery table. Are the top 5 failure modes covered?
5. Read the pitfalls. Do they match actual agent mistakes seen in practice?

### Phase 4: Agent Simulation (10 minutes)

Pick 3 representative tasks and simulate how an agent would read the file:

1. **Simple task**: e.g., "navigate to a page and get the title"
   - Does the agent find the Quick Start pattern?
   
2. **Complex task**: e.g., "extract product data from search results with filtering"
   - Does the agent find the decision guidance and choose the right tool?
   
3. **Failure task**: e.g., "domsnapshot get returns empty, what now?"
   - Does the agent find the recovery table and apply the fallback?

## Severity Levels

| Level | Criteria | Action |
|-------|----------|--------|
| **P0** | Agent cannot complete basic tasks, or corrupts state due to missing critical rule | Fix before any agent uses the skill |
| **P1** | Agent picks wrong tool, gets stuck on failure, or copies broken examples | Fix in next iteration |
| **P2** | File is verbose, inconsistent, or missing reference details | Fix when touching the file |

## Post-Audit Output

After auditing, produce:

1. **Severity summary**: count of P0/P1/P2 findings
2. **Restructure plan**: if information hierarchy is wrong, specify the new section order
3. **Content gaps**: list of missing sections or missing information within sections
4. **Example fixes**: specific examples that need correction

## Example: Audit of `skill/SKILL.md` (2026-07-01)

### Findings addressed

| # | Severity | Finding | Fix |
|---|----------|---------|-----|
| 1 | P0 | Installation was first section; no Quick Start | Added Quick Start with core loop + golden rule; moved Installation to appendix |
| 2 | P0 | Golden Rule ("refs are single-use") buried under Concepts | Promoted to top with 🔴 marker in Quick Start section |
| 3 | P1 | No extraction method decision guide | Added "Choosing an Extraction Method" comparison table |
| 4 | P1 | Error handling had no recovery patterns | Added 9-row symptom→cause→recovery table |
| 5 | P2 | Giant tip block with 6 stacked ideas | Split into separate, single-topic callouts |
| 6 | P2 | No common pitfalls documented | Added 8-item pitfalls checklist |

### Structure after audit

```
Frontmatter
Quick Start (core loop, golden rule, extraction methods, template)
Concepts (snapshots, ref lifecycle, sessions)
Commands (navigation, interaction, snapshots, grep, get, eval, storage)
DOM Snapshot
X-SQL
AI-Powered Extraction
Browser Sessions
Swarm CLI / Crawl CLI / Loop CLI
Paginating Through Results
Polite Scraping
Error Handling & Recovery (with recovery table)
Common Pitfalls
Installation (appendix)
References
```
