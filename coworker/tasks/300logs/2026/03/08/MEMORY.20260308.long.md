## Daily Memory - 2026-03-08

- **Coworker/Copilot standardization:** Unified all `gh copilot` invocation behind shared native helpers (`coworker/scripts/workers/gh-copilot.ps1` / `.sh`). Entry points now source shared config, validate `COPILOT`, build args with native arrays, avoid quoting bugs, and handle PowerShell `GH_DEBUG` correctly. **Learning:** Centralize Copilot invocation in reusable helpers; on Windows, use LF-normalized temp `.sh` copies plus temp working dirs for reliable Bash validation.

- **Draft placeholder hygiene:** PowerShell and Bash runners now keep `coworker/tasks/0draft` populated with `1.md`-`5.md`, repairing placeholders before queue listing and after task completion. **Learning:** Placeholder maintenance belongs in shared runners at both startup and completion so PS/Bash behavior stays aligned.

- **MCP tool-spec tightening:** Tool-spec generation now exposes only explicitly annotated `@MCP` methods. Added `WebDriver.drag(sourceSelector, targetSelector)`, annotated executor-used `WebDriver` and `PerceptiveAgent` methods, and regenerated code-mirror artifacts. **Learning:** The spec pipeline is source-text driven, so annotations and generated artifacts must stay synchronized; explicit exposure prevents deprecated/helper overload leakage.

- **WebDriver CLI/backend alignment:** `browser4-cli` commands were aligned to real MCP/backend tool names and payloads (`navigate`, `go_back`, `press`, `type`, `select_option`, `tab_*`, etc.); unsupported exports were removed; open/snapshot/screenshot now use `open`, `aria_snapshot`, and `screenshot` with base64 file saving. `MCPToolController.kt` now normalizes snake_case and legacy payloads for compatibility. **Learning:** Match the real MCP contract rather than preserving partial Playwright-style aliases; keep compatibility in the controller with small normalization instead of alias drift.

- **Draft refinement pipeline:** Added dedicated flow `0draft/refine/1ready -> 2working -> 3done` with `refine-drafts.ps1/.sh`. Scripts resolve repo root from their own location, move inputs into working, promote only successful rewrites, and leave failures in working. **Learning:** Standalone helpers should resolve repo root from script location, not caller cwd; an explicit working state preserves failed inputs for inspection.

- **Python coworker runner:** Added `coworker/scripts/coworker.py` mirroring the existing lifecycle: repo-root discovery, task-folder lifecycle, placeholder maintenance, config-driven Copilot invocation, naming, memory init, logging, approved-task git sync, and completion moves. **Learning:** A Python port can stay aligned by preserving the same folder contract and helper interfaces; temp repos with fake Copilot commands give safe end-to-end coverage.

- **Browser4 MCP startup verification:** Confirmed Browser4 MCP does **not** start from `browser4\browser4-agents\target\Browser4.jar`; it starts from `browser4-agentic` via Maven exec entry point `Browser4MCPServerRunnerKt`, launches Browser4/Chrome, registers tools, and exits when STDIO closes. **Learning:** Browser4 MCP is a STDIO subprocess, not a long-running HTTP server; docs/tests should clearly distinguish it from the Spring Boot app.

- **Unified scheduling + deprecation cleanup:** Implemented PowerShell-first unified scheduling with `coworker-scheduler.ps1` + config, running coworker, draft refinement, and task-source monitoring as child processes with per-task logs, shared status JSON, config-driven intervals/args, and `DependsOn` ordering; `run_coworker_periodically.ps1` gained `-Once`. Legacy periodic scripts were moved to `coworker/deprecated`, original paths became deprecation-warning shims, and docs/config were retargeted accordingly. **Learning:** A one-shot scheduler still needs dependency awareness so producer work is visible in the same cycle; under `Set-StrictMode`, imported config hashtables should use key checks/default helpers, not direct property access; when deprecating runnable scripts, keep compatibility shims and normalize touched `.sh` files to LF before `bash -n` on Windows.


Total usage est:        1 Premium request
API time spent:         24s
Total session time:     32s
Total code changes:     +0 -0
Breakdown by AI model:
 gpt-5.4                 21.1k in, 1.4k out, 18.9k cached (Est. 1 Premium request)

- **PSD1-backed PowerShell coworker config:** Moved the PowerShell Copilot command definition out of coworker/scripts/config.ps1 into the new coworker/scripts/config.psd1, with config.ps1 now importing the data file and coworker.py updated to prefer the PSD1 while keeping existing PowerShell/Bash fallbacks. **Outcome:** PowerShell helpers still load the configured command correctly, and the Python coworker runner now resolves the same flags from the PSD1 source of truth. **Learning:** When PowerShell config becomes data-driven, cross-runtime consumers must either parse the PSD1 directly or retain a compatible fallback path rather than scraping the wrapper script.
