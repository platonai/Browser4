## Daily Memory - 2026-03-09

- **PulsarWebDriver click tests refreshed:** Replaced stale disabled `PulsarWebDriverClickTests` tied to an obsolete dynamic page with active E2E coverage on `generated/interactive-screens.html`, updating selectors to current IDs like `#addButton` and `#toggleMessageButton`. Coverage now includes count clicks, modifier clicks, missing-element no-ops, disabled-button behavior, navigation, and sequential interactions. **Learning:** When generated mock pages drift, update tests to current HTML instead of preserving dead selectors. **Validation:** `.\mvnw.cmd -f browser4-tests\pom.xml -pl pulsar-it-tests -am -DrunE2ETests=true -Dtest=PulsarWebDriverClickTests -D"surefire.failIfNoSpecifiedTests=false" test` → 13 passed.

- **Coworker periodic scripts renamed/structured:** Renamed periodic entrypoints to queue-oriented names: `process-coworker-queue.ps1` and `process-draft-refinement-queue.ps1`; moved scheduler-backed implementations into `coworker/scripts/deprecated/`; retained old `run_*_periodically.ps1` files as compatibility shims; updated scheduler/docs. **Learning:** Once `coworker-scheduler.ps1` owns recurrence, helper names should describe queue processing, while shims preserve automation compatibility. **Validation:** AST parsing and `coworker-scheduler.config.psd1` path checks confirmed scripts still resolve cleanly.

- **ARIA snapshot rendering aligned with Playwright:** Added Playwright-style ARIA snapshot rendering via `DOMState.render()` and `PageHandler.ariaSnapshot()`. Renderer now emits roles, names, state attrs, refs, pointer hints, inline text, and `/url` from DOM/AX data; `href` was added to default included attributes so link URLs survive DOM-state construction. **Learning:** For Playwright-compatible accessibility YAML, render from the richer micro/unfiltered tree, not generic object YAML, so refs, pointer hints, and semantics are reconstructed deterministically. **Validation:** `.\mvnw.cmd -pl pulsar-core\pulsar-browser -am -Dtest=DOMStateBuilderTest -D"surefire.failIfNoSpecifiedTests=false" test` → 11 passed, 1 skipped.

- **Scheduler empty-queue skip:** Updated `coworker/scripts/coworker-scheduler.ps1` to support `PendingPaths`, letting the scheduler check queues before spawning child PowerShell processes. Default `coworker` and `draft-refinement` tasks now use queue paths, removing empty runs and extra PowerShell popups. **Learning:** Queue-awareness belongs in the unified scheduler because suppressing child launch is what removes empty-run UX noise. **Validation:** Temporary `-Once` config confirmed empty queues yield `WaitingForWork` and `RunCount=0`, while non-empty queues still launch workers.

- **MicroToNanoTreeHelper review findings:** Identified two correctness issues in `MicroDOMTreeNodeHelper.kt`: `toNanoTreeInRangeRecursive()` can prune visible leaf nodes or in-range descendants too early, and `toInteractiveDOMTreeNodeList()` mixes ordering keys (`nodeId` vs `paintOrder/backendNodeId`) when computing `textBefore`, potentially attaching text to the wrong interactive node. **Learning:** In DOM summarizers, recurse before pruning generated subtrees, and use one monotonic traversal order for “text between nodes” logic.

- **PromptBuilder translation cleanup:** Translated remaining Chinese prompt text in `PromptBuilder.kt` to concise English, aliased duplicate EN rules to one canonical set, and normalized `language` output to `Chinese`/`English`. **Learning:** A single English source reduces CN/EN drift and makes prompt maintenance safer. **Validation:** Non-ASCII scan found no remaining prompt text; `.\mvnw.cmd -pl browser4-agentic -am -DskipTests compile` succeeded.

- **Scheduled-task status file relocated:** Moved default scheduler status output from `coworker\tasks\300logs\scheduler\scheduled-tasks.status.json` to ignored repo-root `logs\scheduled-tasks.status.json`; updated defaults/config/docs and removed the tracked artifact. **Learning:** Runtime status artifacts belong in ignored operational log locations, not versioned task-history trees; a disabled-task smoke config is sufficient to validate path resolution safely. **Validation:** Running `./coworker/scripts/coworker-scheduler.ps1 -ConfigPath <temp-config> -Once` created `logs\scheduled-tasks.status.json` before cleanup.


Total usage est:        1 Premium request
API time spent:         15s
Total session time:     24s
Total code changes:     +0 -0
Breakdown by AI model:
 gpt-5.4                 21.6k in, 1.1k out, 18.9k cached (Est. 1 Premium request)

- **MainSystemPrompt refined:** Translated all remaining Chinese content in `MainSystemPrompt.kt` to English and tightened the wording for system instructions, tool usage, completion criteria, reasoning steps, and skill metadata descriptions. **Learning:** Keeping the shared system prompt in one concise English source reduces bilingual drift and makes downstream prompt maintenance easier. **Validation:** Non-ASCII scan of `MainSystemPrompt.kt` returned no matches, and `.\mvnw.cmd -pl browser4-agentic -am -DskipTests compile` completed successfully.

