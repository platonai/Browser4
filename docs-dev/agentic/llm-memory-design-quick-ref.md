# LLM Memory Design - Quick Reference

> Quick reference guide for the LLM memory design in pulsar-agentic component

## Overview

This document provides a quick reference for the comprehensive LLM memory design. For full details, see:
- [Full Design Document (English)](llm-memory-design.md)
- [完整设计文档 (中文)](llm-memory-design.zh.md)

## Three-Layer Memory Architecture

```
┌─────────────────────────────────────┐
│    Working Memory (Short-term)      │
│  - Current session context          │
│  - Recent actions & observations    │
│  - Volatile, in-memory only         │
└─────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────┐
│    Episodic Memory (Long-term)      │
│  - Task execution records           │
│  - Success/failure outcomes         │
│  - Persistent, file-based storage   │
└─────────────────────────────────────┘
                 ↓
┌─────────────────────────────────────┐
│   Semantic Memory (Knowledge)       │
│  - Extracted patterns & facts       │
│  - Learned strategies               │
│  - Vector-indexed, searchable       │
└─────────────────────────────────────┘
```

## Core Components

### 1. Memory Manager
Central coordinator for all memory operations.

```kotlin
interface MemoryManager {
    fun getWorkingMemory(sessionId: String): WorkingMemory
    suspend fun addToWorkingMemory(sessionId: String, state: AgentState)
    suspend fun consolidateSession(sessionId: String, outcome: TaskOutcome): EpisodicMemory
    suspend fun retrieveRelevant(query: MemoryQuery, limit: Int = 5): List<Memory>
    suspend fun storeKnowledge(knowledge: SemanticMemory): String
    suspend fun searchKnowledge(query: String, category: MemoryCategory? = null): List<SemanticMemory>
    suspend fun buildContext(currentTask: String, sessionId: String, maxTokens: Int = 4000): AgentContext
}
```

### 2. Memory Types

#### Working Memory
```kotlin
data class WorkingMemory(
    val sessionId: String,
    val taskContext: TaskContext,
    val recentStates: LimitedQueue<AgentState>,
    val scratchpad: MutableMap<String, Any>
)
```

#### Episodic Memory
```kotlin
data class EpisodicMemory(
    val id: String,
    val sessionId: String,
    val taskDescription: String,
    val outcome: TaskOutcome,  // SUCCESS, PARTIAL_SUCCESS, FAILURE, ABORTED
    val summary: String,        // LLM-generated
    val keyLearnings: List<String>,
    val states: List<AgentState>
)
```

#### Semantic Memory
```kotlin
data class SemanticMemory(
    val category: MemoryCategory,  // FACT, PATTERN, STRATEGY, PREFERENCE, etc.
    val content: String,
    val embedding: FloatArray?,    // For semantic search
    val confidence: Double,
    val sourceEpisodes: List<String>
)
```

## Storage Backends

### File-Based (Default)
```
data/memory/
├── episodic/
│   ├── session-uuid-1/
│   │   ├── episode-1.json
│   │   └── episode-2.json
│   └── session-uuid-2/
└── semantic/
    ├── FACT/
    ├── PATTERN/
    └── STRATEGY/
```

### SQLite (Optional)
Single-file database with FTS5 for full-text search.

### Vector DB (Future)
Integration with Chroma, Pinecone, or Weaviate for semantic search.

## Retrieval Strategies

### 1. Recency-Based
```kotlin
RecencyQuery(limit = 10, category = MemoryCategory.FACT)
```
Use when recent context is most relevant.

### 2. Similarity-Based
```kotlin
SimilarityQuery(
    queryText = "how to search on Amazon",
    minSimilarity = 0.7,
    limit = 5
)
```
Use when semantic relevance matters most.

### 3. Hybrid (Recommended)
```kotlin
HybridQuery(
    recencyWeight = 0.3,
    similarityWeight = 0.5,
    relevanceWeight = 0.2,
    context = currentTask
)
```
Best overall strategy for most scenarios.

## Integration Points

### 1. PerceptiveAgent
```kotlin
interface PerceptiveAgentWithMemory : PerceptiveAgent {
    val memoryManager: MemoryManager
    
    suspend fun runWithMemory(task: String): AgentHistory
    suspend fun learnFromSession(): List<SemanticMemory>
}
```

### 2. InferenceEngine
Automatically includes memory context in LLM prompts:
```kotlin
suspend fun observe(params: ObserveParams, context: ExecutionContext): ActionDescription {
    val memoryContext = memoryManager.buildContext(context.instruction, context.sessionId)
    val messages = buildObserveMessages(params, memoryContext)
    // ... inference with enhanced context
}
```

### 3. AgentEventBus
Emits events for memory operations:
```kotlin
object MemoryEvents {
    const val MEMORY_STORED = "memory.stored"
    const val MEMORY_RETRIEVED = "memory.retrieved"
    const val MEMORY_CONSOLIDATED = "memory.consolidated"
    const val KNOWLEDGE_EXTRACTED = "memory.knowledge_extracted"
}
```

## Memory Lifecycle

```
Task Start
    ↓
Create Working Memory + Retrieve Relevant Past Memories
    ↓
Task Execution (add states to working memory)
    ↓
Task Complete
    ↓
Consolidate Working Memory → Episodic Memory
    ↓
Extract Knowledge → Semantic Memory
    ↓
Store to Persistent Storage
```

## Usage Examples

### Basic Usage
```kotlin
val agent = AgenticContexts.getOrCreateAgent()

// Agent automatically uses memory
val result = agent.runWithMemory("""
    Go to amazon.com and search for 'mechanical keyboard'.
    Remember the search process for future reference.
""")
```

### Explicit Memory Management
```kotlin
// Store custom knowledge
agent.memoryManager.storeKnowledge(
    SemanticMemory(
        category = MemoryCategory.FACT,
        content = "Amazon search button: #nav-search-submit-button",
        confidence = 1.0,
        tags = setOf("amazon", "search")
    )
)

// Search knowledge
val knowledge = agent.memoryManager.searchKnowledge(
    query = "Amazon search process",
    category = MemoryCategory.PATTERN
)

// Manual consolidation
val episode = agent.memoryManager.consolidateSession(
    sessionId = session.id,
    outcome = TaskOutcome.SUCCESS
)
```

## Configuration

```properties
# Memory configuration (application.properties)
memory.enabled=true
memory.storage.backend=file_based
memory.storage.baseDir=data/memory
memory.episodic.maxSize=10000
memory.semantic.maxSize=50000
memory.consolidation.enabled=true
memory.embeddings.enabled=false
memory.embeddings.model=openai-text-embedding-3-small
```

## Implementation Phases

| Phase | Goal | Duration | Key Deliverables |
|-------|------|----------|------------------|
| 1 | Foundation | 2-3 weeks | Data models, Storage interface, File storage |
| 2 | Working Memory | 1-2 weeks | Integration with AgentStateManager |
| 3 | Episodic Memory | 2-3 weeks | Consolidation, Retrieval strategies |
| 4 | Semantic Memory | 2-3 weeks | Knowledge extraction, Pattern recognition |
| 5 | Context Integration | 1-2 weeks | Prompt enhancement, Token management |
| 6 | Vector Search | 2-3 weeks | Embeddings, Semantic similarity (optional) |
| 7 | Advanced Features | 3-4 weeks | Compression, Analytics, Admin UI (optional) |

## Performance Targets

- **Retrieval Latency**: <100ms
- **Token Efficiency**: Hierarchical summarization to fit context windows
- **Storage Scalability**: Support 10K+ episodes with automatic pruning
- **Memory Consolidation**: Async, non-blocking

## Key Benefits

1. **Cross-Session Context**: Agents remember across restarts
2. **Continuous Learning**: Improve from past experiences
3. **Token Efficiency**: Smart summarization reduces context size
4. **Semantic Search**: Find relevant memories intelligently
5. **Personalization**: Remember user preferences and patterns

## Next Steps

1. Review full design documents
2. Discuss architecture with team
3. Begin Phase 1 implementation
4. Set up testing infrastructure
5. Plan integration strategy

---

For complete details, algorithms, and code examples, see the full design documents:
- [llm-memory-design.md](llm-memory-design.md) (English)
- [llm-memory-design.zh.md](llm-memory-design.zh.md) (中文)
