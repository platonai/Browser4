## Task: add-webdriver-input-api (2026-03-07)

### Summary
Added Playwright-style low-level WebDriver input APIs for keyboard and mouse interactions, and aligned Browser4 CLI/MCP routing with them.

### Changes
- Added `keyDown`, `keyUp`, `mouseMove`, `mouseDown`, `mouseUp`, and `mouseWheel` to the core `WebDriver` contract and implemented them in CDP and Playwright drivers.
- Updated REST/OpenAPI and `WebDriverToolExecutor` to route input actions through the new driver methods instead of raw DOM-event JavaScript shims.
- Extended the Kotlin SDK with the new input helpers and added MCP alias coverage so browser4-cli input commands (`keydown`, `keyup`, `mousemove`, `mousedown`, `mouseup`, `mousewheel`) resolve correctly.
- Refreshed docs/examples and updated code-mirror tool spec resources to keep generated metadata aligned with the runtime API.

### Validation
- `npx jest --runInBand tests/commands.test.ts tests/program.test.ts` ✅
- `..\..\mvnw.cmd -DskipTests compile` from `sdks\browser4-kotlin` ✅
- `mvnw.cmd -pl browser4-rest -am -Dtest=MCPToolControllerTest -D"surefire.failIfNoSpecifiedTests=false" test` was still running through reactor compilation at handoff time; no failure observed in streamed output.

### Lessons Learned
- Keeping MCP/controller aliases in sync with CLI tool names avoids split-brain behavior when one side speaks Playwright-style names and the server expects raw driver method names.
- When WebDriver APIs change, code-mirror/spec resources and user-facing docs must be updated together or generated tool metadata quickly drifts from runtime behavior.
