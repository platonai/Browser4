# 2026-03-01 Daily Memory

## Task: Reimplement Mock MCP Server
**Outcome**: Completed.
**Summary**:
- Reimplemented MockMCPServer using the official io.modelcontextprotocol Kotlin SDK (v0.8.1).
- Updated browser4-tests-common dependencies to include SDK and serialization libraries.
- Refactored callTool endpoint to accept Map<String, Any> instead of raw JSON string, allowing Spring to handle deserialization.
- Updated unit tests (TestMCPServerMock, TestMCPServerForPluginMock) to pass Maps and verify SDK behavior.
- Updated E2E tests (MCPToolExecutorE2ETest) to handle ObjectNode to Map conversion correctly for requests, ensuring compatibility with the new server implementation.
- Fixed dependency issues and ensured clean builds.
**Lessons Learned**:
- When using RestTestClient with ObjectNode bodies, Jackson might serialize the object properties instead of the JSON content if not handled carefully. Converting to Map explicitly resolves this.
- surefire.excludedGroups configuration in pom.xml needs to be overridden to run E2E tests locally.
- Use -am (also make dependencies) when installing a module that other modules depend on to ensure the local repository is up-to-date.
