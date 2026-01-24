# Structured Logging for Pulsar-Agentic Module

## Overview

This document describes the structured logging system implemented for the `pulsar-agentic` module, which provides consistent, parseable logs for agent operations.

## Purpose

The `AgentLogger` class provides structured logging to replace the `stateManager.addTrace()` mechanism with proper SLF4J-based logging that follows Browser4's log format conventions.

## Log Format

All agent logs follow this structured format:

```
HH:mm:ss.SSS [thread] LEVEL LoggerName - EventType | field=value field2=value2 | message
```

### Example Logs

```
15:03:01.234 [agent-1] INFO AgentLogger - toolExecOk | sid=abc12345 step=5 tool=click | ✅ Tool executed successfully
15:03:02.456 [agent-1] WARN AgentLogger - actTimeout | step=3 timeout=600000ms | ⏳ Action timed out: navigate to homepage
15:03:03.789 [agent-1] INFO AgentLogger - complete | sid=abc12345 step=8 complete=true | ✅ Task finished
```

## AgentLogger API

### Location

- **Package**: `ai.platon.pulsar.agentic.logging`
- **File**: `pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/logging/AgentLogger.kt`

### Creating an Instance

```kotlin
// For a specific class
val agentLogger = AgentLogger.forClass(MyAgent::class.java)

// For a specific target object
val agentLogger = AgentLogger.forTarget(this)
```

### Event Types and Methods

#### 1. Action Lifecycle

**Action Start**
```kotlin
agentLogger.logActionStart(sessionId, step, action)
```
Example output: `actionStart | sid=abc12345 step=1 | action=Click login button`

**Action Timeout**
```kotlin
agentLogger.logActionTimeout(state, timeoutMs, instruction)
```
Example output: `actTimeout | step=3 timeout=60000ms | ⏳ Action timed out: navigate...`

**Action Success**
```kotlin
agentLogger.logActionSuccess(state, candidateIndex, candidateTotal)
```
Example output: `actSuccess | step=5 candidate=1/3 | ✅ Action executed successfully`

**Action All Failed**
```kotlin
agentLogger.logActionAllFailed(state, candidatesCount, lastError)
```
Example output: `actAllFailed | step=7 candidates=3 | ❌ All candidates failed. Last: timeout`

#### 2. Tool Execution

**Tool Execution Success**
```kotlin
agentLogger.logToolExecOk(sessionId, step, method, description)
```
Example output: `toolExecOk | sid=abc12345 step=5 tool=click | ✅ Click executed successfully`

**Tool Execution Failure**
```kotlin
agentLogger.logToolExecFail(sessionId, step, method, error)
```
Example output: `toolExecFail | sid=abc12345 step=5 tool=click | ❌ Element not found`

#### 3. Observation

**No Observation Result**
```kotlin
agentLogger.logObserveNoAction(state)
```
Example output: `observeNoAction | step=3 | ⚠️ No observe result generated`

#### 4. Validation

**Validation Failed**
```kotlin
agentLogger.logValidationFailed(sessionId, step, reason)
```
Example output: `validationFailed | sid=abc12345 step=4 | ⚠️ Tool call validation failed`

#### 5. Task Completion

**Task Complete**
```kotlin
agentLogger.logComplete(sessionId, step, isComplete)
```
Example output: `complete | sid=abc12345 step=10 complete=true | ✅ Task finished`

**No-Operation (NoOp)**
```kotlin
agentLogger.logNoOp(state, consecutiveNoOps, maxAllowed)
```
Example output: `noop | step=8 consecutive=3 max=5 | ⚠️ No-operation detected`

#### 6. Multi-Step Resolution

**Resolve Start**
```kotlin
agentLogger.logResolveStart(sessionId, instruction, maxSteps)
```
Example output: `resolveStart | sid=abc12345 maxSteps=100 | instruction=Complete checkout process`

**Resolve Done**
```kotlin
agentLogger.logResolveDone(sessionId, step, success, durationMs, result)
```
Example output: `resolveDone | sid=abc12345 step=12 success=true duration=45000ms | ✅ Task completed`

**Resolve Timeout**
```kotlin
agentLogger.logResolveTimeout(sessionId, timeoutMs, instruction)
```
Example output: `resolveTimeout | sid=abc12345 timeout=86400000ms | ⏳ Resolve timed out: process order`

#### 7. Agent Lifecycle

**User Close**
```kotlin
agentLogger.logUserClose(state)
```
Example output: `userClose | step=5 | 🛑 Agent closed by user`

**Final Summary**
```kotlin
agentLogger.logFinalSummary(sessionId, step)
```
Example output: `final | sid=abc12345 step=10 | Generating final summary`

#### 8. Generic Events

For custom events not covered by specific methods:

```kotlin
agentLogger.logEvent(event, fields, message)
```

Example:
```kotlin
agentLogger.logEvent(
    "customEvent",
    mapOf("user" to "john", "action" to "retry"),
    "Custom processing completed"
)
```

Output: `customEvent | user=john action=retry | Custom processing completed`

## Integration with BasicBrowserAgent

The `BasicBrowserAgent` class includes an `agentLogger` instance:

```kotlin
open class BasicBrowserAgent(
    override val session: AgenticSession,
    val config: AgentConfig
) : PerceptiveAgent {
    protected val agentLogger = AgentLogger.forClass(BasicBrowserAgent::class.java)
    
    // ... rest of implementation
}
```

## Integration with BrowserPerceptiveAgent

The `BrowserPerceptiveAgent` inherits `agentLogger` from `BasicBrowserAgent` and uses it throughout:

```kotlin
open class BrowserPerceptiveAgent(
    session: AgenticSession,
    val maxSteps: Int = 100,
    config: AgentConfig = AgentConfig(maxSteps = maxSteps)
) : BasicBrowserAgent(session, config) {
    // agentLogger is inherited from BasicBrowserAgent
}
```

## Backward Compatibility

The implementation maintains backward compatibility by:

1. **Keeping `addTrace()` calls**: All original `stateManager.addTrace()` calls are preserved alongside new `agentLogger` calls
2. **Dual logging**: Events are logged to both the new structured logs (SLF4J) and the legacy ProcessTrace system
3. **No breaking changes**: Existing code continues to work without modification

Example of dual logging:
```kotlin
agentLogger.logToolExecOk(context.sid, context.step, method, description)
stateManager.addTrace(
    context.agentState,
    items = mapOf("tool" to method),
    event = "toolExecOk",
    message = description
)
```

## Benefits

1. **Structured Format**: Consistent key-value pairs for easy parsing and analysis
2. **Standard Framework**: Uses SLF4J/Logback (Browser4's standard logging framework)
3. **Log Routing**: Leverages existing Logback configuration for file routing
4. **Visual Markers**: Unicode symbols (✅, ❌, ⚠️, ⏳, 🛑) for quick visual scanning
5. **Parseable**: Easy to extract fields for monitoring and alerting
6. **Compact**: Session IDs truncated to 8 chars, strings limited to prevent log spam

## Migration Guide

### For New Code

Use `agentLogger` methods directly:

```kotlin
agentLogger.logToolExecOk(sessionId, step, "click", "Button clicked")
```

### For Existing Code

Add `agentLogger` calls before existing `addTrace` calls:

```kotlin
// New structured logging
agentLogger.logComplete(sid, step, true)

// Keep existing trace for backward compatibility
stateManager.addTrace(context.agentState, event = "complete", message = "#${step} complete")
```

### Future Migration

Once the transition period is complete, `addTrace` calls can be gradually removed, leaving only structured logging.

## Log Analysis Examples

### Finding Failed Actions

```bash
grep "actAllFailed" logs/pulsar.log
```

### Monitoring Timeouts

```bash
grep "Timeout" logs/pulsar.log | grep -E "step=[0-9]+"
```

### Tracking Session Progress

```bash
grep "sid=abc12345" logs/pulsar.log | grep -E "(actionStart|complete)"
```

### Performance Analysis

```bash
grep "resolveDone" logs/pulsar.log | grep -oP "duration=\K[0-9]+"
```

## Related Documentation

- [Browser4 Log Format](../../docs/log-format.md) - Overall log format conventions
- [AgentStateManager](../../pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/detail/AgentStateManager.kt) - Legacy trace system
- [ProcessTrace Model](../../pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/model/Models.kt) - ProcessTrace data structure

## Version

- **Created**: 2026-01-24
- **Version**: 1.0
- **Module**: pulsar-agentic 4.5.0-SNAPSHOT
