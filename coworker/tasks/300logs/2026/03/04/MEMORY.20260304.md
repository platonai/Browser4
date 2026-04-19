# Daily Memory - 2026-03-04

## Task: Remove ToDoManager & Cleanup
- **Goal**: Remove `ToDoManager` class, usages, and configuration from codebase and documentation.
- **Outcome**: Deleted `ToDoManager.kt` and empty `todo` directory. Removed all references, initialization, and `todo*` config flags from `BrowserPerceptiveAgent.kt`. Fixed residual compilation error in `ObserveActBrowserAgent.kt` (removed `updateTodo` call). Updated `docs-dev/agentic/AgentFileSystem-Review-2026-02-09.md`. Validated via `browser4-agentic` compilation.
- **Lessons**: Large refactors require compilation to catch residual usages that static analysis might miss.

## Task: Native MCP & Skills in AgentToolExecutor
- **Goal**: Refactor `AgentToolExecutor` to natively support MCP and Skills, replacing `registerCustomTarget` wiring.
- **Outcome**: Updated `AgentToolExecutor.kt` to include `skills` (`SkillToolExecutor`) and `mcpExecutors` as native properties. Modified `executeToolCall` to handle `skill` and `mcp.*` domains directly. Simplified `BasicBrowserAgent.kt` by removing manual registration code. Verified via build.
- **Lessons**: Native integration simplifies agent initialization and centralizes tool management.

## Task: Fix Coworker Rename Logic
- **Goal**: Fix `1created` task file renaming timeouts and script paths.
- **Outcome**: Corrected paths to `rename.ps1`/`rename.sh` in `coworker.ps1`/`coworker.sh` (pointed to `workers/` subdir). Added retry logic (3 attempts) for renaming operations. Updated `rename.ps1` to use array arguments for `gh copilot` calls to resolve quoting issues with complex prompts.
- **Lessons**: Verify relative paths for helper scripts; use array arguments for PowerShell CLI commands to handle special characters safely.

## Task: Create MCPToolController Integration Tests
- **Goal**: Create comprehensive tests for `MCPToolController` covering all commands in the CLI switch statement.
- **Outcome**: Created `pulsar-rest/src/test/kotlin/ai/platon/pulsar/rest/openapi/controller/MCPToolControllerTest.kt`. Implemented tests for session management (`open_session`, `close_session`, `delete_session_data`, etc.), generic driver commands (`click`, `fill`, `navigate`, etc.), and explicit tool mappings (`page_title`, `switch_tab`, `tab_new`, etc.). Verified 16 tests pass successfully using `mvnw`.
- **Lessons**: When testing Kotlin with Mockito, handle non-nullable parameters carefully by creating custom helpers like `anyToolCall()` or `capture()` that return dummy non-null values, as standard `any()` returns null and causes Kotlin NPEs before Mockito interception. Also, mocking properties of data classes (like `val driver`) works if the class is mockable (open or mockito-inline).

## Task: Fix Coworker Rename Logic (Correction)
- **Goal**: Fix regression in `rename.ps1` where `gh copilot` failed with "too many arguments" due to incorrect array argument handling in previous attempt.
- **Outcome**: Refactored `rename.ps1` to pass the prompt as a single string with explicit quoting and flattened the prompt to a single line. This resolved the argument parsing issue. Also updated `rename.sh` to flatten the prompt for consistency. Verified `rename.ps1` execution with a test file.
- **Lessons**: PowerShell's `Start-Process -ArgumentList` with arrays can behave unexpectedly with complex strings; passing a pre-quoted string is more robust for CLI tools like `gh`.



