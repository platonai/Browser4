# Review: agent-skill-auto-summary

## Scope

Independent read-only review of the "auto-summarize successful agent tasks into a reusable
SKILL.md draft" feature, checked against `.trae/specs/agent-skill-auto-summary/spec.md`
(AC-1..AC-12, FR-1..FR-12, NFR-1..NFR-6).

### Changed files

**Modified:**
- `application.properties` — documented defaults for the 8 `browser4.agent.skill.summary.*` props
- `browser4-agent-tools/pom.xml` — explicit `browser4-coding` dependency
- `browser4-agent-tools/.../agent/AgentTaskEvents.kt` — `TYPE_SKILL_SUMMARIZE` constant
- `browser4-agent-tools/.../agent/StatefulAgentRunner.kt` — success-hook trigger (+ test)
- `browser4-agentic/src/main/resources/skills/README.md` — new REST endpoints + mechanism note
- `browser4-boot/.../skill/SkillService.kt` — `DraftSummary` / `listDrafts` / `promoteDraft` (+ test)
- `browser4-rest/.../controller/SkillController.kt` — `GET /drafts`, `POST /drafts/{id}/promote` (+ test)
- `docs/config.md` — new "Auto-generated skill summaries" section

**Added:**
- `.trae/specs/agent-skill-auto-summary/` — spec.md, tasks.md
- `browser4-agent-tools/.../agent/skillsummary/` — implementation + tests (10 source files, 9 test files)

## Reviewer

Independent read-only review delegated to a separate agent (no code writes).

## Acceptance Criteria checklist

| AC | Description | Status |
|----|-------------|--------|
| AC-1 | Config props + master switch, default off | met |
| AC-2 | Success-only hook, never on failure | met |
| AC-3 | Threshold filter (rounds/toolCalls/mutation) | met |
| AC-4 | Dedup fingerprint index + similarity | met |
| AC-5 | LLM generates SKILL.md draft, validated, sanitized | met |
| AC-6 | Persist to `<app data>/agent/skills-drafts`, never repo skills/ | met |
| AC-7 | Register strategy draft/auto/off, default draft | met |
| AC-8 | Append mode for repeated similar tasks | met |
| AC-9 | Event emission `skill.summarize` for observability | met |
| AC-10 | REST `GET /api/skills/drafts` | met |
| AC-11 | REST `POST /api/skills/drafts/{id}/promote` | met |
| AC-12 | Docs registered | met |

## NFR checklist

| NFR | Description | Status |
|-----|-------------|--------|
| NFR-1 | Best-effort; failures swallowed, never affect task success/verdict | met |
| NFR-2 | No sensitive-info leakage (SecretRedactor) | met |
| NFR-3 | No writes to protected repo files | met |
| NFR-4 | Default off / conservative (draft, never auto-activate) | met |
| NFR-5 | Reuse existing mechanisms (LLM, SkillRegistry, event store, validator) | met |
| NFR-6 | Does not break finish-gate semantics | met |

## Findings

### Finding 1 (BLOCKING) — fingerprint index never written in production

- **Observed:** `summarizePipeline` did not call `index.record(...)` (GENERATE branch) or `index.bump(...)` (APPEND branch), so `index.json` stayed empty in production → the decider always saw no match → always GENERATE, never APPEND / cooldown. Tests masked the gap by pre-filling the index manually.
- **Remediation:** Extracted `val index = indexFactory(config)`; GENERATE branch now calls `index.record(...)`, APPEND branch calls `index.bump(...)`, both wrapped in `runCatching { ... }.onFailure { logger.warn(...) }` so an index write failure can never affect task success (NFR-1). Added regression test `consecutiveSimilarRunsGenerateThenAppend` (same prompt twice with `cooldownHours=0L` → first run emits `generate` and persists `index.json`, second run emits `append` and not `generate`).
- **Status:** Fixed & verified (skillsummary 56 tests green).

### Finding 2 (MAJOR, FR-7) — provenance markers missing from SKILL.md frontmatter

- **Observed:** `generated: true` appeared only in the LLM instruction, never force-injected into the persisted draft; `source-task-id` and `generated-at` were absent entirely.
- **Remediation:** Added `ensureProvenance(content, context)` which injects `generated: true`, `source-task-id: <taskId>`, and `generated-at: <epochMillis>` into the YAML frontmatter only when absent (no overwrite of existing values), wired into `generateInternal` via `finalContent`. Added test `injectsProvenanceMarkers`.
- **Status:** Fixed & verified.

### Finding 3 (MINOR, AC-2) — no runner-level negative test for failure / cancel

- **Observed:** The hook correctly fired only on the success path, but no test pinned that a failed (or cancelled) task does NOT emit a `skill.summarize` event — a future refactor could silently regress AC-2.
- **Remediation:** Added `failedTaskDoesNotTriggerAutoSkillSummary` in `StatefulAgentRunnerTest`: `agent.run` throws `IllegalStateException`, the test asserts the task failed, waits 2s for any stray async emission, and asserts no `skill.summarize` event landed.
- **Status:** Fixed & verified (runner 46 tests green).

### Finding 4 (MINOR, NFR-2) — input-side free-form text sent to the LLM without re-redaction

- **Observed:** `renderContext` sent raw `prompt` / `result` / `verifyVerdict` to the LLM provider; only the output side (persisted `context.json`) was redacted.
- **Remediation:** `renderContext` now passes `prompt`, `result`, and `verifyVerdict` through the injected `redactor` before the text reaches the LLM (defense-in-depth; NFR-2).
- **Status:** Fixed & verified (no regression, 56 tests green).

### Re-review

A second read-only pass by the same independent reviewer confirmed all four fixes are present in the current source exactly as specified (file:line evidence recorded in the review thread). No new findings.

## Verdict

PASS
