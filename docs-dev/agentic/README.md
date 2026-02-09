# Agentic Documentation

This directory contains documentation for the Browser4 agentic component (`pulsar-agentic`).

## Table of Contents

- [Custom Agent Tools](custom-agent-tools.md) - Guide for creating custom agent tools
- [LLM Memory Design](llm-memory-design.md) - Comprehensive design for LLM memory functionality
- [Agent Event Bus Integration](agentEventBus-integration-plan.md) - Event bus integration plan
- [Skills](skills/) - Agent skills documentation
- [Use Cases](use-cases.md) - Common use cases and examples

## LLM Memory Design

> **Status**: Design Document (仅设计，不编程)  
> **Date**: 2026-02-09

A comprehensive design for adding memory capabilities to the pulsar-agentic component. The design enables AI agents to maintain contextual awareness across multiple interactions, sessions, and tasks through a multi-layered memory system.

- **English**: [llm-memory-design.md](llm-memory-design.md)
- **中文**: [llm-memory-design.zh.md](llm-memory-design.zh.md)

**Key Features:**
- Three-layer memory system (working, episodic, semantic)
- Automatic memory consolidation
- Vector-based semantic search
- Token-efficient context integration
- Pluggable storage backends

---

# Custom Agent Tools - Quick Start

This guide provides a quick overview of how to create and use custom agent tools to extend the agent's capabilities.

## What are Custom Agent Tools?

Custom agent tools allow you to add domain-specific functionality to the Browser4 agent system beyond the built-in tools (driver, browser, fs, agent, system). You can create tools for:

- Database operations
- API integrations
- Custom business logic
- External service interactions
- Data processing
- Any other domain-specific operations

## Quick Example

Here's a complete example of creating a custom calculator tool:

### 1. Create Your Target Class

```kotlin
class Calculator {
    fun add(a: Double, b: Double): Double = a + b
    fun subtract(a: Double, b: Double): Double = a - b
    fun multiply(a: Double, b: Double): Double = a * b
    fun divide(a: Double, b: Double): Double {
        require(b != 0.0) { "Division by zero is not allowed" }
        return a / b
    }
}
```

### 2. Create Your Tool Executor

```kotlin
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import kotlin.reflect.KClass

class CalculatorToolExecutor : AbstractToolExecutor() {
    override val domain = "calc"  // The domain prefix for tool calls
    override val targetClass: KClass<*> = Calculator::class

    override suspend fun execute(
        objectName: String,
        functionName: String,
        args: Map<String, Any?>,
        target: Any
    ): Any? {
        require(target is Calculator) { "Target must be a Calculator" }
        val calc = target

        return when (functionName) {
            "add" -> {
                validateArgs(args, allowed = setOf("a", "b"), required = setOf("a", "b"), functionName)
                calc.add(
                    paramDouble(args, "a", functionName)!!,
                    paramDouble(args, "b", functionName)!!
                )
            }
            "subtract" -> {
                validateArgs(args, allowed = setOf("a", "b"), required = setOf("a", "b"), functionName)
                calc.subtract(
                    paramDouble(args, "a", functionName)!!,
                    paramDouble(args, "b", functionName)!!
                )
            }
            // Add more operations...
            else -> throw IllegalArgumentException("Unsupported method: $functionName")
        }
    }
}
```

### 3. Register Your Tool

```kotlin
import ai.platon.pulsar.agentic.tools.CustomToolRegistry

// Create instances
val calculator = Calculator()
val calculatorExecutor = CalculatorToolExecutor()

// Register with the global registry
CustomToolRegistry.instance.register(calculatorExecutor)

// Register the target with your AgentToolManager
agentToolManager.registerCustomTarget("calc", calculator)
```

### 4. Use Your Tool

Once registered, the agent can use your custom tool:

```kotlin
// The agent can now execute:
// calc.add(a: 5.0, b: 3.0) -> returns 8.0
// calc.multiply(a: 7.0, b: 6.0) -> returns 42.0
// calc.divide(a: 20.0, b: 4.0) -> returns 5.0
```

## Key Features

- **Easy Registration**: Register custom tools at runtime with a simple API
- **Type-Safe**: Strongly typed parameters with validation helpers
- **Thread-Safe**: Safe to use in concurrent environments
- **Error Handling**: Built-in error handling and validation
- **Extensible**: Follow the same patterns as built-in tools

## Helper Methods

The `AbstractToolExecutor` base class provides helper methods for parameter extraction:

- `validateArgs()` - Validate required and allowed parameters
- `paramString()` - Extract string parameters
- `paramInt()` - Extract integer parameters
- `paramLong()` - Extract long parameters
- `paramDouble()` - Extract double parameters
- `paramBool()` - Extract boolean parameters
- `paramStringList()` - Extract list of strings

## Example Usage in Code

```kotlin
// Database tool example
class DatabaseToolExecutor : AbstractToolExecutor() {
    override val domain = "db"
    override val targetClass: KClass<*> = Database::class

    override suspend fun execute(
        objectName: String,
        functionName: String,
        args: Map<String, Any?>,
        target: Any
    ): Any? {
        require(target is Database) { "Target must be a Database" }
        val db = target

        return when (functionName) {
            "query" -> {
                validateArgs(args, allowed = setOf("sql"), required = setOf("sql"), functionName)
                db.query(paramString(args, "sql", functionName)!!)
            }
            "execute" -> {
                validateArgs(args, allowed = setOf("sql"), required = setOf("sql"), functionName)
                db.execute(paramString(args, "sql", functionName)!!)
            }
            else -> throw IllegalArgumentException("Unsupported method: $functionName")
        }
    }
}

// Register and use
val db = Database()
CustomToolRegistry.instance.register(DatabaseToolExecutor())
agentToolManager.registerCustomTarget("db", db)

// Agent can now use:
// db.query(sql: "SELECT * FROM users")
// db.execute(sql: "INSERT INTO users VALUES (...)")
```

## Documentation

For complete documentation including:
- Detailed API reference
- Best practices
- Advanced usage
- Troubleshooting guide

See: [docs/agentic/custom-agent-tools.md](docs/agentic/custom-agent-tools.md)

## Example Implementation

A working example is provided in the codebase:
- `CalculatorToolExecutor.kt` - Example custom tool executor
- `CalculatorToolExecutorTest.kt` - Comprehensive test suite

## Testing

Always write tests for your custom tools:

```kotlin
@Test
fun `test calculator add operation`() = runBlocking {
    val executor = CalculatorToolExecutor()
    val calculator = Calculator()
    val toolCall = ToolCall("calc", "add", mutableMapOf("a" to "5.0", "b" to "3.0"))

    val result = executor.callFunctionOn(toolCall, calculator)

    assertEquals(8.0, result.value as Double, 0.001)
}
```

## Summary

Custom agent tools provide a powerful way to extend the agent system. The process is simple:

1. Create your target class with business logic
2. Create a tool executor extending `AbstractToolExecutor`
3. Register both the executor and target object
4. Use the tool in your agent workflows

For questions or issues, refer to the full documentation or examine the example implementation.
