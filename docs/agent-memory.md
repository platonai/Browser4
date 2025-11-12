# Agent Memory System

## Overview

The BrowserPerceptiveAgent now includes a memory system that allows it to maintain context across interactions. This feature enables the agent to remember important information from previous steps and use it to make better decisions in future actions.

## Architecture

The memory system consists of three main components:

### 1. ShortTermMemory
- **Purpose**: Stores recent memories using a sliding window approach
- **Capacity**: Configurable (default: 50 memories)
- **Retention**: Automatically removes oldest memories when capacity is reached
- **Use Case**: Maintaining context of recent actions and observations

### 2. LongTermMemory
- **Purpose**: Stores important facts and information
- **Filtering**: Only stores memories above an importance threshold (default: 0.7)
- **Capacity**: Configurable (default: 200 memories)
- **Retention**: Removes least important memories when capacity is reached
- **Use Case**: Preserving critical information for long-running tasks

### 3. CompositeMemory
- **Purpose**: Combines short-term and long-term memory
- **Deduplication**: Automatically handles duplicate memories across both stores
- **Retrieval**: Merges and ranks memories by relevance and importance
- **Use Case**: Provides unified interface for memory operations

## Configuration

Memory settings can be configured through `AgentConfig`:

```kotlin
val config = AgentConfig(
    enableMemory = true,                    // Enable/disable memory system
    shortTermMemorySize = 50,               // Max short-term memories
    longTermMemorySize = 200,               // Max long-term memories
    longTermMemoryThreshold = 0.7,          // Min importance for long-term storage
    memoryRetrievalLimit = 10               // Max memories to include in prompts
)
```

## How It Works

### 1. Memory Collection

The agent automatically extracts and stores memories during execution:

- **From LLM Responses**: The LLM can provide a "memory" field in its responses containing important observations
- **From Evaluations**: Previous action evaluations are stored as memories
- **From Goals**: Next goal statements are stored with high importance

### 2. Memory Retrieval

When generating actions, the agent:
1. Retrieves relevant memories based on the current instruction
2. Formats them for inclusion in the LLM prompt
3. Provides context to help the LLM make informed decisions

### 3. Memory Persistence

Memories are automatically saved and restored through the checkpoint system:
- Memories are included in checkpoint snapshots
- When restoring a session, memories are automatically loaded
- Enables resuming tasks with full context preservation

## Usage Example

### Basic Usage

```kotlin
val agent = BrowserPerceptiveAgent(driver, session, config = AgentConfig(
    enableMemory = true,
    shortTermMemorySize = 50,
    longTermMemorySize = 200
))

// Memories are automatically collected and used
agent.resolve("Search for products and compare prices")
```

### Manual Memory Management

```kotlin
// Add a memory manually
agent.memory.add(
    content = "User prefers products under $100",
    importance = 0.9,
    metadata = mapOf("category" to "preference")
)

// Retrieve relevant memories
val memories = agent.memory.retrieve(query = "price preference", limit = 5)

// Get memory context for prompts
val context = agent.memory.getMemoryContext(query = "shopping", limit = 10)

// Clear all memories
agent.memory.clear()
```

### Checkpoint Integration

```kotlin
// Enable checkpointing to persist memories
val config = AgentConfig(
    enableMemory = true,
    enableCheckpointing = true,
    checkpointIntervalSteps = 10
)

val agent = BrowserPerceptiveAgent(driver, session, config = config)

// Memories are automatically saved in checkpoints
agent.resolve("Complex multi-step task")

// Restore from checkpoint (includes memory state)
val checkpoint = agent.restoreFromCheckpoint(sessionId)
```

## Memory Importance Scoring

Memories are assigned importance scores (0.0 to 1.0) based on context:

- **1.0**: Task completion events
- **0.8**: Next goal statements
- **0.7**: Successful action memories
- **0.6**: Action evaluations
- **0.3**: Failed action memories

Only memories above the `longTermMemoryThreshold` (default 0.7) are stored in long-term memory.

## LLM Integration

The memory field is included in the observation schema sent to the LLM:

```json
{
  "elements": [
    {
      "locator": "0,4",
      "description": "Description of action",
      "domain": "driver",
      "method": "click",
      "memory": "1-3 specific sentences describing this step and overall progress",
      "evaluationPreviousGoal": "Analysis of previous action",
      "nextGoal": "Clear statement of next goal"
    }
  ]
}
```

The LLM can populate the `memory` field with important observations that should be remembered.

## Benefits

1. **Context Preservation**: Maintains important information across multiple steps
2. **Improved Decisions**: LLM has access to relevant past information
3. **Task Continuity**: Can resume long-running tasks with full context
4. **Reduced Repetition**: Avoids re-learning information already discovered
5. **Better Planning**: Can track progress and make informed decisions

## Best Practices

1. **Configure Appropriately**: Adjust memory sizes based on task complexity
2. **Use Importance Wisely**: Assign higher importance to critical information
3. **Enable Checkpointing**: Combine with checkpoints for robust recovery
4. **Monitor Memory Usage**: Clear memories when starting unrelated tasks
5. **Provide Context**: Include relevant queries when retrieving memories

## Performance Considerations

- Memory retrieval is fast (O(n) with n = number of memories)
- Short-term memory uses a deque for efficient oldest-first removal
- Long-term memory uses a hash map for fast lookups and deduplication
- Memory snapshots are included in checkpoints (consider size when configuring)

## Future Enhancements

Potential improvements to the memory system:

- Vector-based semantic search for more accurate retrieval
- Automatic importance scoring based on task outcomes
- Memory consolidation and summarization
- External memory storage (database, file system)
- Memory visualization and debugging tools
