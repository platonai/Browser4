# Agent Action and Tool Call Architecture

## Overview

This document describes the refactored architecture for agent actions and tool call execution in PulsarRPA. The refactoring consolidates scattered action execution logic into a unified, maintainable structure.

## Architecture

### Core Components

#### 1. ActionExecutionService (New)

**Location:** `ai.platon.pulsar.agentic.ai.support.ActionExecutionService`

The central service for all action and tool call execution. This is the **recommended entry point** for new code.

**Responsibilities:**
- Execute tool calls on WebDriver and Browser instances
- Convert between ToolCall objects and expression strings
- Validate tool calls before execution
- Parse Kotlin function expressions
- Provide script evaluation capabilities

**Key Methods:**
```kotlin
// Execute operations
suspend fun execute(toolCall: ToolCall, driver: WebDriver): Any?
suspend fun execute(toolCall: ToolCall, browser: Browser): Any?
suspend fun execute(expression: String, driver: WebDriver): Any?
suspend fun execute(expression: String, browser: Browser): Any?

// Conversion and parsing
fun convertToExpression(toolCall: ToolCall): String?
fun parseExpression(input: String): ToolCall?

// Validation
fun validate(toolCall: ToolCall): Boolean

// Script evaluation (use cautiously)
fun eval(expression: String, variables: Map<String, Any>): Any?
```

**Example Usage:**
```kotlin
val service = ActionExecutionService()

// Execute a tool call
val toolCall = ToolCall("driver", "click", mutableMapOf("selector" to "#button"))
val result = service.execute(toolCall, driver)

// Execute an expression
val result2 = service.execute("driver.scrollToBottom()", driver)

// Convert and validate
val expression = service.convertToExpression(toolCall)
val isValid = service.validate(toolCall)
```

#### 2. ToolCallExecutor (Deprecated)

**Location:** `ai.platon.pulsar.agentic.ai.support.ToolCallExecutor`

**Status:** Maintained for backward compatibility, delegates to `ActionExecutionService`

This class is deprecated and should not be used in new code. Existing code using `ToolCallExecutor` will continue to work but should be migrated to `ActionExecutionService` over time.

#### 3. Supporting Executors

##### WebDriverToolCallExecutor
Handles execution of driver-domain tool calls. Contains implementation details for all WebDriver commands.

**Location:** `ai.platon.pulsar.agentic.ai.support.WebDriverToolCallExecutor`

##### BrowserToolCallExecutor
Handles execution of browser-domain tool calls (e.g., tab switching).

**Location:** `ai.platon.pulsar.agentic.ai.support.BrowserToolCallExecutor`

##### ActionValidator
Validates tool calls before execution to ensure safety and correctness.

**Location:** `ai.platon.pulsar.agentic.ai.agent.detail.ActionValidator`

## Tool Call Domains

PulsarRPA supports two domains for tool calls:

### 1. Driver Domain
Operations on a specific WebDriver instance (browser tab).

**Supported Actions:**
- Navigation: `navigateTo`, `goBack`, `goForward`
- Interaction: `click`, `fill`, `type`, `press`, `check`, `uncheck`
- Scrolling: `scrollTo`, `scrollToTop`, `scrollToBottom`, `scrollBy`, `scrollToMiddle`
- Selection: `exists`, `isVisible`, `focus`, `selectFirstTextOrNull`, `selectTextAll`
- Advanced: `clickTextMatches`, `clickMatches`, `evaluate`, `captureScreenshot`
- And more... (see `AgentTool.TOOL_CALL_SPECIFICATION`)

### 2. Browser Domain
Operations at the browser level (multiple tabs).

**Supported Actions:**
- `switchTab`: Switch between browser tabs

## Migration Guide

### For New Code

Always use `ActionExecutionService`:

```kotlin
// Good ✓
val service = ActionExecutionService()
val result = service.execute(toolCall, driver)

// Avoid ✗
val executor = ToolCallExecutor()
val result = executor.execute(toolCall, driver)
```

### For Existing Code

The `ToolCallExecutor` continues to work and delegates to `ActionExecutionService`, so existing code will function without changes. However, consider migrating gradually:

**Before:**
```kotlin
class MyAgent {
    private val toolCallExecutor = ToolCallExecutor()
    
    suspend fun performAction(toolCall: ToolCall, driver: WebDriver) {
        return toolCallExecutor.execute(toolCall, driver)
    }
}
```

**After:**
```kotlin
class MyAgent {
    private val actionService = ActionExecutionService()
    
    suspend fun performAction(toolCall: ToolCall, driver: WebDriver) {
        return actionService.execute(toolCall, driver)
    }
}
```

## Benefits of the Refactoring

1. **Single Entry Point**: All action execution goes through one service
2. **Better Maintainability**: Changes to execution logic only need to be made in one place
3. **Improved Testability**: Service can be easily tested in isolation
4. **Clear Separation of Concerns**: Execution, validation, and conversion are clearly separated
5. **Reduced Code Duplication**: Common logic is consolidated
6. **Easier Debugging**: Centralized logging and error handling

## Testing

The `ActionExecutionService` has comprehensive test coverage in:
- `ActionExecutionServiceTest`: Tests for the service API
- Existing `ToolCallExecutor*Test` files: Tests for backward compatibility

Run tests:
```bash
./mvnw -pl pulsar-core/pulsar-agentic test
```

## Related Files

- `pulsar-core/pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/ai/support/`
  - `ActionExecutionService.kt` - Main service
  - `ToolCallExecutor.kt` - Deprecated wrapper
  - `WebDriverToolCallExecutor.kt` - Driver execution implementation
  - `BrowserToolCallExecutor.kt` - Browser execution implementation
  - `AgentTool.kt` - Tool specifications and metadata
  - `SimpleKotlinParser.kt` - Expression parsing

- `pulsar-core/pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/ai/agent/detail/`
  - `ActionValidator.kt` - Validation logic

## Future Enhancements

Potential improvements to consider:

1. **Plugin Architecture**: Allow custom action handlers to be registered
2. **Action History**: Track execution history for debugging
3. **Performance Metrics**: Monitor action execution performance
4. **Async Batch Execution**: Execute multiple actions in parallel
5. **Enhanced Error Recovery**: Automatic retry with backoff for transient failures

## Support

For questions or issues related to the action execution architecture:
- Check existing tests for usage examples
- Review the inline documentation in source files
- See `AgentTool.TOOL_CALL_SPECIFICATION` for supported actions
