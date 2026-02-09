# MCP End-to-End Tests

## Overview

This directory contains end-to-end tests for the MCP (Model Context Protocol) functionality in Browser4.

## Test Structure

### MCPTestApplication.kt
Spring Boot test application that automatically starts `TestMCPServer` as a bean, making it available for testing MCP functionality in a realistic environment.

### MCPToolExecutorE2ETest.kt
Comprehensive end-to-end tests for `MCPToolExecutor` that validate:

1. **Server Connectivity**: Tests HTTP accessibility and server information endpoints
2. **Tool Discovery**: Validates tool listing and schema compliance with MCP protocol
3. **Tool Execution**: Tests echo, add, and multiply tools with various inputs
4. **Error Handling**: Validates error scenarios like invalid tools and missing arguments
5. **Sequential Operations**: Tests multiple tool calls and server state management
6. **Cross-Network Communication**: Tests HTTP-based communication across network boundaries

## Running the Tests

The tests in the `pulsar-tests` module are skipped by default because they are time-consuming. To run the MCP E2E tests:

### Run all MCP tests
```bash
./mvnw -pl pulsar-tests test -Dtest="MCPToolExecutorE2ETest"
```

### Run a specific test method
```bash
./mvnw -pl pulsar-tests test -Dtest="MCPToolExecutorE2ETest#test server is accessible via HTTP"
```

### Run all tests with MCP tag
```bash
./mvnw -pl pulsar-tests test -Dgroups="mcp"
```

### Run all E2E tests (including MCP)
```bash
./mvnw -pl pulsar-tests test -Dgroups="E2ETest"
```

## Test Coverage

The MCP E2E tests cover:

- ✅ Automatic TestMCPServer startup in Spring Boot context
- ✅ Server info endpoint validation
- ✅ Tool listing and schema validation (3 default tools: echo, add, multiply)
- ✅ Echo tool execution with various messages
- ✅ Add tool execution with integers and decimals
- ✅ Multiply tool execution
- ✅ Non-existent tool error handling
- ✅ Missing required argument error handling
- ✅ Multiple sequential tool calls
- ✅ Mixed successful and failed calls
- ✅ Cross-network HTTP communication

## Architecture

```
MCPTestApplication (Spring Boot)
    |
    └─> TestMCPServer (HTTP/REST endpoints)
            |
            └─> MCPToolExecutor (connects via HTTP)
                    |
                    └─> MCPClientManager
```

The tests use HTTP REST endpoints for communication, simulating real-world cross-network scenarios. The `TestMCPServer` provides a minimal but complete MCP implementation suitable for testing without external dependencies.

## Dependencies

The MCP tests require:
- `pulsar-agentic` (for MCPToolExecutor, MCPClientManager, MCPConfig)
- `pulsar-tests-common` (for TestMCPServer)
- Spring Boot Test infrastructure
- JUnit 5

## Future Enhancements

Potential areas for expansion:
1. Add tests for SSE (Server-Sent Events) transport
2. Add tests for WebSocket transport
3. Add tests for STDIO transport with process management
4. Add concurrent tool execution tests
5. Add performance/load testing scenarios
6. Add tests for custom MCP tools beyond the default set
