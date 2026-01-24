# State Management Architecture

**Document Version**: 1.0  
**Date**: 2026-01-24  
**Component**: BrowserPerceptiveAgent State Management

---

## Overview

The BrowserPerceptiveAgent uses a multi-layered state management system to track execution progress, maintain history, and enable debugging. This document explains the architecture, relationships between components, and lifecycle management.

## Core Components

### 1. AgentStateManager

**Purpose**: Central coordinator for all state management operations

**Responsibilities**:
- Creating and managing execution contexts
- Recording state history
- Maintaining debug traces
- Memory management and cleanup

**Key Fields**:
```kotlin
private lateinit var _baseContext: ExecutionContext      // Initial context (step 0)
private var _activeContext: ExecutionContext?            // Current context (step N)
private val contexts: MutableList<ExecutionContext>      // All contexts in session
private val _stateHistory: AgentHistory                  // Successful actions only
private val _processTrace: MutableList<ProcessTrace>     // All events (debug)
```

### 2. ExecutionContext

**Purpose**: Execution state for a single agent step

**Key Fields**:
```kotlin
val step: Int                          // Step number (0 = base, 1+ = actual steps)
val instruction: String                // User's instruction
val agentState: AgentState            // Current browser state snapshot
val stateHistory: AgentHistory        // Shared reference to history
val sessionId: String                 // Session identifier
val config: AgentConfig               // Configuration
```

**Lifecycle**:
1. Created via `AgentStateManager.buildExecutionContext()`
2. Activated via `setActiveContext()` → appended to contexts list
3. Used during step execution (observe, act, state updates)
4. Trimmed from contexts list when exceeding 100 entries

### 3. AgentState

**Purpose**: Immutable snapshot of browser and execution state at a point in time

**Key Fields**:
```kotlin
val step: Int                         // Step number
val instruction: String               // User instruction
val browserUseState: BrowserUseState  // Browser DOM state
val prevState: AgentState?            // Previous state (linked list)
val actionDescription: ActionDescription?  // Action taken
val toolCallResult: ToolCallResult?   // Result of tool execution
val isComplete: Boolean?              // Whether task is complete
```

**Usage**:
- Created for each step via `getAgentState()`
- Added to `AgentHistory.states` after successful execution
- Forms a linked list via `prevState` pointer
- Immutable after creation (copy-on-write pattern)

### 4. AgentHistory

**Purpose**: Accumulates successfully executed actions across all steps

**Key Fields**:
```kotlin
val states: MutableList<AgentState>   // List of successful states
```

**Characteristics**:
- **Singleton per session**: All contexts share the same instance
- **Success-only**: Only records actions that completed successfully
- **Bounded growth**: Trimmed to `maxHistorySize` when exceeding `maxHistorySize * 2`
- **Accessed by**: All ExecutionContext instances (shared reference)

### 5. ProcessTrace

**Purpose**: Debug trace of all events (including failures)

**Key Fields**:
```kotlin
val step: Int                         // Step number
val event: String?                    // Event name (e.g., "observe", "act-1")
val method: String?                   // Tool method called
val agentState: String?              // Serialized state
val isComplete: Boolean              // Whether step completed
```

**Characteristics**:
- **Comprehensive**: Records all events (success + failure)
- **Debugging**: Written to disk via `writeProcessTrace()`
- **Bounded**: Trimmed to 100 entries when exceeding 200

---

## State Relationships

### Diagram: Component Relationships

```
┌─────────────────────────────────────────────────────────┐
│              AgentStateManager (Singleton)              │
├─────────────────────────────────────────────────────────┤
│ _baseContext: ExecutionContext (step=0, immutable)     │
│ _activeContext: ExecutionContext? (step=N, mutable)    │
│ contexts: List<ExecutionContext> (all contexts)        │
│ _stateHistory: AgentHistory (shared singleton)         │
│ _processTrace: List<ProcessTrace> (debug traces)       │
└─────────────────┬───────────────────────────────────────┘
                  │
                  │ creates & manages
                  ▼
┌─────────────────────────────────────────────────────────┐
│         ExecutionContext (Per-Step Instance)            │
├─────────────────────────────────────────────────────────┤
│ step: Int                                               │
│ agentState: AgentState ──────────────────┐             │
│ stateHistory: AgentHistory (shared) ─────┼────┐        │
└───────────────────────────────────────────┼────┼────────┘
                                            │    │
                                            │    │ shared
                                            │    │ reference
                                            │    ▼
                                            │  ┌─────────────────┐
                                            │  │  AgentHistory   │
                                            │  │   (Singleton)   │
                                            │  ├─────────────────┤
                                            │  │ states: List<   │
                                            │  │   AgentState>   │
                                            │  └─────────────────┘
                                            │
                                            │ current state
                                            ▼
┌─────────────────────────────────────────────────────────┐
│         AgentState (Immutable Per-Step Record)          │
├─────────────────────────────────────────────────────────┤
│ step: Int                                               │
│ browserUseState: BrowserUseState                        │
│ prevState: AgentState? (linked list) ───┐              │
│ actionDescription: ActionDescription?    │              │
│ toolCallResult: ToolCallResult?          │              │
└──────────────────────────────────────────┼──────────────┘
                                            │
                                            │ linked list
                                            ▼
                                   ┌─────────────────┐
                                   │   AgentState    │
                                   │   (previous)    │
                                   └─────────────────┘
```

### Key Relationships

1. **AgentStateManager → ExecutionContext**: One-to-many (creates multiple contexts)
2. **ExecutionContext → AgentState**: One-to-one (each context has current state)
3. **ExecutionContext → AgentHistory**: Many-to-one (all contexts share same history)
4. **AgentState → AgentState**: Linked list via `prevState` (forms execution chain)
5. **AgentHistory → AgentState**: One-to-many (accumulates successful states)

### No Circular References

- ✅ All references are one-way or use linked lists
- ✅ Shared `AgentHistory` reference is intentional (singleton pattern)
- ✅ No strong circular references that could cause memory leaks
- ✅ Cleanup logic properly handles cascading deletions

---

## State Lifecycle

### Phase 1: Initialization

```kotlin
// In BrowserPerceptiveAgent.resolveInCoroutine()
val baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init")
stateManager.setActiveContext(baseContext)
```

**What happens**:
1. `buildBaseExecutionContext()` creates context with step=0
2. Context added to `contexts` list
3. `_baseContext` field set (immutable reference)
4. `_activeContext` set to baseContext
5. Initial trace added to `_processTrace`

### Phase 2: Step Execution Loop

```kotlin
// In BrowserPerceptiveAgent.doResolveProblem()
for (step in 1..maxSteps) {
    val activeContext = prepareStep(action, previousContext, step, event)
    // observe, act, update state
    stateManager.addToHistory(activeContext.agentState)
    stateManager.addTrace(activeContext.agentState, ...)
}
```

**What happens per step**:
1. `prepareStep()` calls `buildExecutionContext(step=N, baseContext=previous)`
2. New context created with reference to previous context's state
3. `setActiveContext()` updates `_activeContext` and appends to `contexts`
4. Step executes (observe → act → update state)
5. `addToHistory()` adds state to `_stateHistory.states`
6. `addTrace()` adds trace to `_processTrace`

### Phase 3: Memory Cleanup

```kotlin
// Automatic cleanup in AgentStateManager.clearUpHistory()
if (contexts.size > 100) {
    // Keep most recent 50 contexts
    contexts = contexts.takeLast(50)
}
if (_stateHistory.states.size > maxHistorySize * 2) {
    // Keep most recent maxHistorySize states
    _stateHistory.states = _stateHistory.states.takeLast(maxHistorySize)
}
if (_processTrace.size > 200) {
    // Keep most recent 100 traces
    _processTrace = _processTrace.takeLast(100)
}
```

**Triggers**:
- Every `memoryCleanupIntervalSteps` (default: 50 steps)
- Manual call via `clearUpHistory(toRemove: Int)`
- Automatically adjusts `_activeContext` if it was removed

### Phase 4: Session Completion

```kotlin
// In BrowserPerceptiveAgent.resolveInCoroutine() finally block
stateManager.writeProcessTrace()
// History NOT cleared (intentional for cross-task traceability)
```

**What happens**:
1. Process trace written to disk for debugging
2. `_stateHistory` kept for next task (per design decision)
3. Contexts list remains (trimmed if exceeding limits)
4. Agent can be used for additional tasks without losing context

---

## Context Types and Their Purposes

### Base Context (`_baseContext`)

**Characteristics**:
- Step number: Always 0
- Created: Once per resolve() session
- Purpose: Metadata and configuration holder
- Never added to history (step=0 is filtered out)
- Provides session ID for all subsequent contexts

**When Used**:
```kotlin
// Initial session setup
val baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init")
// Recovery from fatal errors
val baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init-recovery")
```

### Active Context (`_activeContext`)

**Characteristics**:
- Step number: 1 to maxSteps
- Created: For each execution step
- Purpose: Current step's execution state
- Always equals `contexts.last()`
- Updated via `setActiveContext()`

**Invariant Check**:
```kotlin
// AgentStateManager.getActiveContext()
require(context == contexts.last()) { 
    "Active context should be the last context in the list"
}
```

### Contexts List (`contexts`)

**Characteristics**:
- Contains: All contexts created (base + active steps)
- Growth: Unbounded initially, trimmed to 100 max
- Purpose: Full session history for debugging
- Cleanup: Keeps most recent 50 when trimming

**Access Pattern**:
```kotlin
contexts.last()          // Current active context
contexts.first()         // Base context (step=0)
contexts[step]           // Context at specific step (if available)
```

---

## Memory Management Strategy

### Problem: Unbounded Growth

Without cleanup, long-running sessions would accumulate:
- Thousands of contexts (each with full browser state)
- Unlimited state history
- Unlimited process traces
- Result: Out of memory errors

### Solution: Periodic Trimming

```kotlin
// AgentStateManager.clearUpHistory()
synchronized(this) {
    // Keep newest 50 contexts out of 100
    if (contexts.size > 100) {
        contexts = contexts.takeLast(50)
        // Update _activeContext if it was removed
        if (_activeContext !in contexts) {
            _activeContext = contexts.lastOrNull()
        }
    }
    
    // Keep newest maxHistorySize states out of maxHistorySize * 2
    if (_stateHistory.states.size > config.maxHistorySize * 2) {
        _stateHistory.states = _stateHistory.states.takeLast(config.maxHistorySize)
    }
    
    // Keep newest 100 traces out of 200
    if (_processTrace.size > 200) {
        _processTrace = _processTrace.takeLast(100)
    }
}
```

### Trade-offs

**Pros**:
- ✅ Prevents memory exhaustion
- ✅ Keeps recent history for context-aware decisions
- ✅ Maintains debugging capability

**Cons**:
- ⚠️ Loses old context (acceptable for long sessions)
- ⚠️ Process trace truncated (full trace written to disk)
- ⚠️ History trimming may lose early task context

---

## State vs Trace vs Context: When to Use

### Use AgentHistory (stateHistory)

**When**: Recording successfully executed actions for LLM context

```kotlin
// After successful tool execution
stateManager.addToHistory(context.agentState)
```

**Characteristics**:
- Success-only (failures not recorded)
- Used by LLM for next action generation
- Bounded by `maxHistorySize`
- Serialized in prompts

### Use ProcessTrace (_processTrace)

**When**: Debugging, monitoring, and audit trails

```kotlin
// Record any event (success or failure)
stateManager.addTrace(
    state = context.agentState,
    event = "resolveStart",
    message = "🚀 resolve START"
)
```

**Characteristics**:
- Records all events (success + failure)
- Includes metadata (timestamps, exceptions)
- Written to disk for post-mortem analysis
- Not used by LLM

### Use ExecutionContext (activeContext)

**When**: Accessing current step's execution state

```kotlin
// Get current context
val context = stateManager.getActiveContext()
val currentStep = context.step
val currentState = context.agentState
```

**Characteristics**:
- Current step only
- Contains configuration and metadata
- Provides access to both state and history
- Immutable after step completes

---

## Common Patterns

### Pattern 1: Start New Session

```kotlin
// Create base context
val baseContext = stateManager.buildBaseExecutionContext(action, "resolve-init")
stateManager.setActiveContext(baseContext)
```

### Pattern 2: Execute Step

```kotlin
// Create new context for step N
val previousContext = stateManager.getActiveContext()
val newContext = stateManager.buildExecutionContext(
    instruction = action.action,
    step = previousContext.step + 1,
    event = "act-${previousContext.step + 1}",
    baseContext = previousContext
)
stateManager.setActiveContext(newContext)

// Execute step
val result = executeSingleStep(newContext)

// Record results
stateManager.addToHistory(newContext.agentState)
stateManager.addTrace(newContext.agentState, emptyMap(), "stepComplete")
```

### Pattern 3: Access History

```kotlin
// Get full history
val history = stateManager.stateHistory

// Get recent states
val recentStates = history.states.takeLast(10)

// Get specific state
val lastState = history.states.lastOrNull()
```

### Pattern 4: Cleanup

```kotlin
// Manual cleanup
stateManager.clearUpHistory(toRemove = 10)

// Clear all history
stateManager.clearHistory()

// Write traces to disk
stateManager.writeProcessTrace()
```

---

## Best Practices

### DO ✅

1. **Use getOrCreateActiveContext()** when you need automatic context creation
2. **Use getActiveContext()** when context should already exist
3. **Call addToHistory()** after every successful action
4. **Call addTrace()** for important events (success or failure)
5. **Document state transitions** in your code
6. **Test context lifecycle** in unit tests

### DON'T ❌

1. **Don't hold long-term references** to ExecutionContext (use session ID)
2. **Don't modify AgentState** after adding to history (copy-on-write)
3. **Don't bypass setActiveContext()** (breaks invariants)
4. **Don't assume contexts list is complete** (may be trimmed)
5. **Don't forget to call writeProcessTrace()** before session end
6. **Don't clear history mid-session** (use separate sessions instead)

---

## Testing State Management

### Unit Test: Context Creation

```kotlin
@Test
fun `should create base context with step 0`() {
    val context = stateManager.buildBaseExecutionContext(action, "test")
    assertEquals(0, context.step)
    assertEquals("test", context.event)
    assertNotNull(context.sessionId)
}
```

### Unit Test: Context Activation

```kotlin
@Test
fun `should maintain activeContext invariant`() {
    val context1 = stateManager.buildExecutionContext("test", 1, "step1")
    stateManager.setActiveContext(context1)
    
    val active = stateManager.getActiveContext()
    assertEquals(context1, active)
    assertEquals(context1, contexts.last())
}
```

### Unit Test: Memory Cleanup

```kotlin
@Test
fun `should trim contexts when exceeding limit`() {
    // Create 110 contexts
    repeat(110) { i ->
        val ctx = stateManager.buildExecutionContext("test", i, "step$i")
        stateManager.setActiveContext(ctx)
    }
    
    // Trigger cleanup
    stateManager.clearUpHistory(0)
    
    // Should be trimmed to 50
    assertTrue(contexts.size <= 50)
}
```

---

## Troubleshooting

### Issue: "Actor not initialized" Error

**Cause**: Calling `getActiveContext()` before creating any context

**Solution**: Use `getOrCreateActiveContext()` instead, or ensure context is created first

### Issue: Context Not in List

**Cause**: Context was trimmed during cleanup

**Solution**: Store session ID, not context reference; recreate context if needed

### Issue: Memory Growth

**Cause**: Cleanup not triggered, or limits set too high

**Solution**: Check `memoryCleanupIntervalSteps` and `maxHistorySize` config

### Issue: Lost History

**Cause**: History trimming removed needed states

**Solution**: Increase `maxHistorySize` or use separate sessions for different tasks

---

## Future Improvements

### Considered but Not Implemented

1. **Immutable Data Structures**: Would prevent mutation bugs but require significant refactoring
2. **State Machine Pattern**: Explicit state transitions, but current ad-hoc approach is sufficient
3. **Persistent State Store**: Save state to disk for resumption after crash
4. **Bidirectional Context Links**: Parent/child pointers, but adds complexity

### Why Not Implemented

- Current approach is working well
- Complexity doesn't justify benefits
- Memory management is adequate
- No user requests for these features

---

## References

- `AgentStateManager.kt`: State management implementation
- `SupportTypes.kt`: ExecutionContext, ProcessTrace definitions
- `Models.kt`: AgentState, AgentHistory definitions
- `BrowserPerceptiveAgent.kt`: Usage examples

---

**Document Maintenance**: Update this document when state management changes significantly.
