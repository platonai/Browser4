## Daily Memory - 2026-03-08

- **Coworker/Copilot standardization:** Unified all `gh copilot` calls behind shared native helpers in `coworker/scripts/workers/gh-copilot.ps1` and `.sh`. Entry points now source shared config, build args with native arrays, avoid quoting bugs, and handle PowerShell `GH_DEBUG` correctly. **Learning:** Centralize Copilot invocation; on Windows, validate Bash via LF-normalized temp `.sh` files and temp working dirs.

- **Coworker lifecycle consistency:** PowerShell and Bash runners now keep `coworker/tasks/0draft` populated with `1.md`-`5.md`, repairing placeholders before queue listing and after completion. A new Python runner, `coworker/scripts/coworker.py`, mirrors the same lifecycle: repo-root discovery, task-folder flow, placeholder maintenance, config-driven Copilot invocation, naming, memory init, logging, approved-task git sync, and completion moves. **Learning:** Cross-runtime ports stay aligned by preserving the same folder contract and helper interfaces.

- **PSD1-backed config:** PowerShell Copilot command configuration moved from `coworker/scripts/config.ps1` into `coworker/scripts/config.psd1`; `config.ps1` now imports the data file, and `coworker.py` prefers parsing PSD1 with existing fallbacks retained. **Learning:** When PowerShell config becomes data-driven, cross-runtime consumers should parse the data source directly or keep a compatible fallback.

- **Draft refinement pipeline:** Added `0draft/refine/1ready -> 2working -> 3done` with `refine-drafts.ps1/.sh`. Scripts resolve repo root from their own location, move inputs into working, promote only successful rewrites, and leave failures in working. **Learning:** Resolve repo root from script location, not caller cwd; explicit working states preserve failed inputs for inspection.

- **Unified scheduling + deprecation cleanup:** Implemented PowerShell-first scheduling via `coworker-scheduler.ps1` plus config, running coworker, draft refinement, and task-source monitoring as child processes with per-task logs, shared status JSON, config-driven intervals/args, and dependency ordering; `run_coworker_periodically.ps1` gained `-Once`. Legacy periodic scripts moved to `coworker/deprecated`, with original paths retained as warning shims. **Learning:** One-shot schedulers still need dependency awareness; under `Set-StrictMode`, prefer key checks/default helpers over direct hashtable property access; preserve compatibility shims during deprecation.

- **MCP tool-spec tightening:** Tool-spec generation now exposes only explicitly annotated `@MCP` methods. Added `WebDriver.drag(sourceSelector, targetSelector)`, annotated executor-used `WebDriver` and `PerceptiveAgent` methods, and regenerated code-mirror artifacts. **Learning:** The spec pipeline is source-text driven, so annotations and generated artifacts must stay synchronized; explicit exposure prevents deprecated/helper overload leakage.

- **CLI/backend contract alignment:** `browser4-cli` now matches real MCP/backend tool names and payloads (`navigate`, `go_back`, `press`, `type`, `select_option`, `tab_*`, etc.); unsupported exports were removed; open/snapshot/screenshot now use `open`, `aria_snapshot`, and `screenshot` with base64 file saving. `MCPToolController.kt` normalizes snake_case and legacy payloads for compatibility. **Learning:** Follow the actual MCP contract, and keep backward compatibility in the controller via lightweight normalization instead of alias drift.

- **Browser4 MCP startup verification:** Confirmed Browser4 MCP starts from `browser4-agentic` via Maven exec entry point `Browser4MCPServerRunnerKt`, launches Browser4/Chrome, registers tools, and exits when STDIO closes; it does **not** start from `browser4\browser4-agents\target\Browser4.jar`. **Learning:** Browser4 MCP is a STDIO subprocess, not a long-running HTTP server; docs/tests should distinguish it clearly from the Spring Boot app.


Total usage est:        1 Premium request
API time spent:         15s
Total session time:     23s
Total code changes:     +0 -0
Breakdown by AI model:
 gpt-5.4                 21.0k in, 936 out, 18.9k cached (Est. 1 Premium request)

- **Deprecated scheduler directory correction:** Corrected the legacy scheduler relocation so the runnable implementations now live under coworker/scripts/deprecated instead of coworker/deprecated, and retargeted the scheduler config, shim wrappers, and English/Chinese coworker docs to the new paths. Validated the updated PowerShell scripts/config with parser + Import-PowerShellDataFile checks, confirmed every configured scheduler target exists, and ran ash -n on the shim and deprecated shell scripts. **Learning:** Keep deprecated runnable scripts beside their coworker/scripts wrappers to avoid path drift; when relocating script directories, update config, wrappers, and user-facing docs as one change set.
- **Memory addendum:** Shell-script validation for the scheduler directory correction was run with `bash -n` against both the compatibility shims and the moved deprecated scripts.
