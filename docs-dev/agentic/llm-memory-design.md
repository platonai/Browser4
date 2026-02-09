# LLM Memory Design for pulsar-agentic

> **Status**: Design Document (仅设计，不编程)  
> **Version**: 1.0  
> **Last Updated**: 2026-02-09  
> **Author**: Browser4 Development Team

## Table of Contents
- [Executive Summary](#executive-summary)
- [Background and Motivation](#background-and-motivation)
- [Current Architecture Analysis](#current-architecture-analysis)
- [Design Goals](#design-goals)
- [Memory Architecture](#memory-architecture)
- [Memory Types](#memory-types)
- [Storage Layer Design](#storage-layer-design)
- [Memory Retrieval and Context Integration](#memory-retrieval-and-context-integration)
- [API Design](#api-design)
- [Memory Management and Lifecycle](#memory-management-and-lifecycle)
- [Integration with Existing Components](#integration-with-existing-components)
- [Implementation Phases](#implementation-phases)
- [Technical Considerations](#technical-considerations)
- [Future Enhancements](#future-enhancements)

---

## Executive Summary

This document presents a comprehensive design for adding **LLM memory functionality** to the `pulsar-agentic` component in Browser4. The design enables AI agents to maintain contextual awareness across multiple interactions, sessions, and tasks through a multi-layered memory system.

**Key Features:**
- **Multi-layered memory**: Short-term (working), long-term (episodic), and semantic memory
- **Automatic memory consolidation**: From working memory to long-term storage
- **Vector-based semantic search**: For intelligent memory retrieval
- **Session-aware memory**: Maintains context within and across sessions
- **Token-efficient**: Smart summarization to minimize context window usage
- **Pluggable storage backends**: Support for multiple persistence mechanisms

---

## Background and Motivation

### Current State

Browser4's `pulsar-agentic` module currently maintains limited memory through:
1. **AgentHistory**: Tracks execution states and tool call results within a single session
2. **ProcessTrace**: Records detailed event traces for debugging
3. **AgentStateManager**: Manages current execution context

**Limitations:**
- Memory is session-scoped and lost after session ends
- No semantic understanding of past interactions
- Limited ability to learn from previous tasks
- Cannot leverage past experiences for better decision-making
- Token inefficiency when contexts grow large

### Motivation

LLM-based agents benefit significantly from memory capabilities:
- **Contextual Continuity**: Maintain awareness across multiple sessions
- **Learning from Experience**: Improve performance based on past successes/failures
- **Personalization**: Remember user preferences and patterns
- **Efficiency**: Avoid re-learning common patterns
- **Complex Task Handling**: Break down complex tasks across multiple sessions

---

## Current Architecture Analysis

### Existing Memory-Related Components

#### 1. AgentHistory
```kotlin
data class AgentHistory(
    val states: MutableList<AgentState> = mutableListOf(),
)
```
- **Purpose**: Tracks sequence of agent states in current session
- **Scope**: Single session, in-memory only
- **Limitation**: Lost when session closes

#### 2. AgentState
```kotlin
data class AgentState(
    var step: Int,
    var instruction: String,
    var browserUseState: BrowserUseState,
    var description: String? = null,
    // ... AI-generated fields
)
```
- **Purpose**: Snapshot of agent state at a specific step
- **Content**: Includes observations, actions, and results
- **Limitation**: No cross-session persistence

#### 3. AgentStateManager
```kotlin
class AgentStateManager(
    val agent: BasicBrowserAgent,
    val pageStateTracker: PageStateTracker,
)
```
- **Purpose**: Manages execution contexts and state history
- **Features**: History size limits, cleanup mechanisms
- **Limitation**: No semantic indexing or long-term storage

#### 4. InferenceEngine
- Logs inference inputs/outputs to files
- No structured retrieval mechanism
- Limited to debugging purposes

### Integration Points

The memory system will integrate with:
1. **PerceptiveAgent**: Main agent interface
2. **InferenceEngine**: For observation and action context
3. **AgenticSession**: For session lifecycle management
4. **AgentEventBus**: For memory-related events

---

## Design Goals

### Functional Goals
1. **Multi-Session Memory**: Persist and retrieve memories across agent sessions
2. **Semantic Understanding**: Enable semantic search and retrieval of relevant memories
3. **Automatic Consolidation**: Intelligently summarize and consolidate memories
4. **Context-Aware Retrieval**: Fetch most relevant memories for current task
5. **Incremental Learning**: Improve agent behavior based on past experiences

### Non-Functional Goals
1. **Performance**: Memory operations should not significantly impact agent responsiveness
2. **Scalability**: Support thousands of memories without degradation
3. **Token Efficiency**: Minimize token usage through smart summarization
4. **Extensibility**: Easy to add new memory types and storage backends
5. **Privacy**: Support memory isolation and cleanup

---

## Memory Architecture

### Three-Layer Memory System

```
┌─────────────────────────────────────────────────────────────┐
│                        Agent Interface                       │
│                    (PerceptiveAgent)                         │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                      Memory Manager                          │
│  - Memory coordination                                       │
│  - Context integration                                       │
│  - Consolidation orchestration                               │
└─────────┬──────────────────┬──────────────────┬─────────────┘
          │                  │                  │
┌─────────▼─────────┐ ┌─────▼─────────┐ ┌─────▼──────────┐
│  Working Memory   │ │ Episodic Memory│ │ Semantic Memory│
│  (Short-term)     │ │  (Long-term)   │ │  (Knowledge)   │
│                   │ │                │ │                │
│ - Current task    │ │ - Task episodes│ │ - Learned facts│
│ - Active context  │ │ - Experiences  │ │ - Patterns     │
│ - Recent actions  │ │ - Outcomes     │ │ - Skills       │
└─────────┬─────────┘ └────────┬───────┘ └────────┬───────┘
          │                    │                   │
          └────────────────────┼───────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                      Storage Layer                           │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   In-Memory  │  │  File-Based  │  │   Vector DB  │     │
│  │   (Transient)│  │  (JSON/SQLite)│  │  (Embeddings)│     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

### Components Overview

#### 1. Memory Manager
- Central coordinator for all memory operations
- Handles memory lifecycle and consolidation
- Integrates memories into agent context
- Manages memory retrieval strategies

#### 2. Working Memory
- Current session's active context
- Recent observations and actions
- Temporary task-specific information
- High access frequency, volatile

#### 3. Episodic Memory
- Records of completed tasks and experiences
- Success/failure outcomes with context
- Time-ordered experiences
- Persistent across sessions

#### 4. Semantic Memory
- Extracted knowledge and patterns
- Generalized learnings from episodes
- Domain-specific facts
- Indexed by semantic similarity

#### 5. Storage Layer
- Pluggable backend architecture
- Support for multiple persistence mechanisms
- Handles serialization and indexing

---

## Memory Types

### 1. Working Memory (Short-term)

**Purpose**: Maintain context for the current task/session

**Characteristics:**
- **Capacity**: Limited (similar to current AgentHistory)
- **Duration**: Single session
- **Access Pattern**: Sequential and frequent
- **Content Type**: Recent agent states, observations, actions

**Data Structure:**
```kotlin
data class WorkingMemory(
    val sessionId: String,
    val taskContext: TaskContext,
    val recentStates: LimitedQueue<AgentState>,  // Last N states
    val scratchpad: MutableMap<String, Any>,     // Temporary variables
    val timestamp: Instant = Instant.now()
)

data class TaskContext(
    val goal: String,
    val domain: String?,
    val subTasks: List<String>,
    val currentStep: Int,
    val metadata: Map<String, Any>
)
```

**Operations:**
- `add(state: AgentState)`: Add new state to working memory
- `getCurrent(): TaskContext`: Get current task context
- `clear()`: Clear working memory
- `consolidate(): EpisodicMemory`: Convert to episodic memory

### 2. Episodic Memory (Long-term)

**Purpose**: Store experiences and task executions

**Characteristics:**
- **Capacity**: Large (configurable, e.g., 10,000 episodes)
- **Duration**: Persistent across sessions
- **Access Pattern**: Retrieval by similarity or recency
- **Content Type**: Complete task episodes with outcomes

**Data Structure:**
```kotlin
data class EpisodicMemory(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val taskDescription: String,
    val taskGoal: String,
    val startTime: Instant,
    val endTime: Instant,
    val states: List<AgentState>,
    val outcome: TaskOutcome,
    val summary: String,          // LLM-generated summary
    val keyLearnings: List<String>,
    val tags: Set<String>,
    val domain: String?,
    val metadata: Map<String, Any>
)

enum class TaskOutcome {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILURE,
    ABORTED
}
```

**Operations:**
- `store(episode: EpisodicMemory)`: Persist an episode
- `retrieve(query: MemoryQuery): List<EpisodicMemory>`: Retrieve relevant episodes
- `getSimilar(episode: EpisodicMemory, limit: Int): List<EpisodicMemory>`: Find similar episodes
- `getRecent(limit: Int): List<EpisodicMemory>`: Get most recent episodes

### 3. Semantic Memory (Knowledge)

**Purpose**: Store extracted knowledge and learned patterns

**Characteristics:**
- **Capacity**: Large (configurable)
- **Duration**: Long-term persistent
- **Access Pattern**: Semantic similarity search
- **Content Type**: Facts, patterns, strategies

**Data Structure:**
```kotlin
data class SemanticMemory(
    val id: String = UUID.randomUUID().toString(),
    val category: MemoryCategory,
    val content: String,
    val embedding: FloatArray?,    // Vector embedding for semantic search
    val confidence: Double,
    val sourceEpisodes: List<String>,  // References to source episodes
    val timesUsed: Int = 0,
    val lastUsed: Instant? = null,
    val created: Instant = Instant.now(),
    val tags: Set<String>,
    val metadata: Map<String, Any>
)

enum class MemoryCategory {
    FACT,              // Domain facts (e.g., "Amazon search is at #twotabsearchtextbox")
    PATTERN,           // Behavioral patterns (e.g., "Login usually requires email then password")
    STRATEGY,          // Problem-solving strategies (e.g., "Use CSS selector before XPath")
    PREFERENCE,        // User preferences (e.g., "User prefers detailed output")
    ERROR_RESOLUTION,  // How to resolve common errors
    SKILL              // Learned skills (e.g., "Extract product info from Amazon")
}
```

**Operations:**
- `store(memory: SemanticMemory)`: Store semantic knowledge
- `search(query: String, category: MemoryCategory?, limit: Int): List<SemanticMemory>`: Semantic search
- `update(id: String, updates: Map<String, Any>)`: Update existing memory
- `incrementUsage(id: String)`: Track memory usage
- `prune(criteria: PruningCriteria)`: Remove low-value memories

---

## Storage Layer Design

### Storage Abstraction

```kotlin
interface MemoryStorage {
    suspend fun save(memory: Memory): String
    suspend fun load(id: String): Memory?
    suspend fun query(query: MemoryQuery): List<Memory>
    suspend fun delete(id: String): Boolean
    suspend fun update(id: String, updates: Map<String, Any>): Boolean
    suspend fun count(): Long
}

sealed class Memory {
    abstract val id: String
    abstract val timestamp: Instant
    
    data class Episodic(
        override val id: String,
        override val timestamp: Instant,
        val episode: EpisodicMemory
    ) : Memory()
    
    data class Semantic(
        override val id: String,
        override val timestamp: Instant,
        val knowledge: SemanticMemory
    ) : Memory()
}
```

### Storage Backend Options

#### 1. In-Memory Storage (Default)
```kotlin
class InMemoryStorage : MemoryStorage {
    private val memories = ConcurrentHashMap<String, Memory>()
    
    // Fast access, no persistence
    // Suitable for development and testing
}
```

**Pros:**
- Fast access
- No external dependencies
- Simple implementation

**Cons:**
- Lost on restart
- Limited by RAM
- No vector search

#### 2. File-Based Storage
```kotlin
class FileBasedStorage(
    private val baseDir: Path
) : MemoryStorage {
    // Store as JSON files in directory structure
    // baseDir/episodic/{sessionId}/{episodeId}.json
    // baseDir/semantic/{category}/{memoryId}.json
}
```

**Pros:**
- Simple persistence
- Easy to inspect and debug
- No database dependency
- Works with existing log infrastructure

**Cons:**
- Slower than in-memory
- Limited query capabilities
- No native vector search

#### 3. SQLite Storage
```kotlin
class SQLiteStorage(
    private val dbPath: Path
) : MemoryStorage {
    // Relational storage with FTS5 for text search
    // Can store embeddings as BLOBs
}
```

**Pros:**
- Good query performance
- ACID compliance
- Full-text search support
- Single-file database

**Cons:**
- Requires SQLite dependency
- Limited vector search
- Schema management

#### 4. Vector Database Integration (Future)
```kotlin
class VectorDBStorage(
    private val config: VectorDBConfig
) : MemoryStorage {
    // Integration with Chroma, Pinecone, Weaviate, etc.
    // Native support for semantic similarity search
}
```

**Pros:**
- Excellent semantic search
- Scalable to millions of vectors
- Purpose-built for embeddings

**Cons:**
- External dependency
- Added complexity
- Potential cost (cloud services)

### Storage Configuration

```kotlin
data class MemoryStorageConfig(
    val backend: StorageBackend = StorageBackend.FILE_BASED,
    val baseDir: Path = AppPaths.detectAuxiliaryLogDir().resolve("memory"),
    val maxEpisodicMemories: Int = 10_000,
    val maxSemanticMemories: Int = 50_000,
    val enableEmbeddings: Boolean = false,
    val embeddingModel: String? = null,
    val vectorDBConfig: VectorDBConfig? = null
)

enum class StorageBackend {
    IN_MEMORY,
    FILE_BASED,
    SQLITE,
    VECTOR_DB
}
```

---

## Memory Retrieval and Context Integration

### Retrieval Strategies

#### 1. Recency-Based Retrieval
Retrieve most recent memories first.

```kotlin
data class RecencyQuery(
    val limit: Int = 10,
    val category: MemoryCategory? = null,
    val tags: Set<String> = emptySet()
) : MemoryQuery
```

**Use Case**: When recent context is most relevant

#### 2. Similarity-Based Retrieval
Retrieve memories similar to current context.

```kotlin
data class SimilarityQuery(
    val queryText: String,
    val queryEmbedding: FloatArray? = null,
    val limit: Int = 5,
    val minSimilarity: Double = 0.7,
    val category: MemoryCategory? = null
) : MemoryQuery
```

**Use Case**: When semantic relevance matters most

#### 3. Tag-Based Retrieval
Retrieve memories with specific tags.

```kotlin
data class TagQuery(
    val tags: Set<String>,
    val matchAll: Boolean = false,  // AND vs OR
    val limit: Int = 10
) : MemoryQuery
```

**Use Case**: When working in specific domains

#### 4. Hybrid Retrieval
Combine multiple strategies with weights.

```kotlin
data class HybridQuery(
    val recencyWeight: Double = 0.3,
    val similarityWeight: Double = 0.5,
    val relevanceWeight: Double = 0.2,
    val context: String,
    val limit: Int = 5
) : MemoryQuery
```

**Use Case**: Best overall strategy for most scenarios

### Context Integration

#### Building Agent Context with Memories

```kotlin
class MemoryContextBuilder {
    suspend fun buildContext(
        currentTask: String,
        workingMemory: WorkingMemory,
        maxTokens: Int = 4000
    ): AgentContext {
        // 1. Retrieve relevant memories
        val relevant = retrieveRelevantMemories(currentTask, maxTokens / 2)
        
        // 2. Format for LLM context
        val contextText = formatMemoriesForLLM(relevant)
        
        // 3. Combine with working memory
        return AgentContext(
            task = currentTask,
            workingMemory = workingMemory,
            relevantEpisodes = relevant.episodic,
            relevantKnowledge = relevant.semantic,
            formattedContext = contextText
        )
    }
    
    private suspend fun retrieveRelevantMemories(
        task: String,
        maxTokens: Int
    ): RetrievedMemories {
        // Use hybrid retrieval
        val query = HybridQuery(
            context = task,
            limit = 10
        )
        
        val memories = memoryManager.retrieve(query)
        
        // Rank and filter by relevance
        val ranked = rankByRelevance(memories, task)
        
        // Limit by token budget
        return selectByTokenBudget(ranked, maxTokens)
    }
}
```

#### LLM Context Format

```markdown
# Agent Context

## Current Task
{task_description}

## Working Memory
- Current Step: {step}
- Recent Actions: {recent_actions}
- Active Variables: {scratchpad}

## Relevant Past Experiences
### Episode 1: {episode_summary}
- Outcome: {outcome}
- Key Learning: {learning}
- Relevant Actions: {actions}

### Episode 2: ...

## Relevant Knowledge
- {fact_1}
- {pattern_1}
- {strategy_1}
```

---

## API Design

### Memory Manager API

```kotlin
interface MemoryManager {
    /**
     * Get current working memory for the session
     */
    fun getWorkingMemory(sessionId: String): WorkingMemory
    
    /**
     * Add state to working memory
     */
    suspend fun addToWorkingMemory(sessionId: String, state: AgentState)
    
    /**
     * Consolidate working memory into episodic memory
     */
    suspend fun consolidateSession(
        sessionId: String,
        outcome: TaskOutcome,
        summary: String? = null
    ): EpisodicMemory
    
    /**
     * Store episodic memory
     */
    suspend fun storeEpisode(episode: EpisodicMemory): String
    
    /**
     * Retrieve relevant memories for current context
     */
    suspend fun retrieveRelevant(
        query: MemoryQuery,
        limit: Int = 5
    ): List<Memory>
    
    /**
     * Store semantic knowledge
     */
    suspend fun storeKnowledge(knowledge: SemanticMemory): String
    
    /**
     * Search semantic memory
     */
    suspend fun searchKnowledge(
        query: String,
        category: MemoryCategory? = null,
        limit: Int = 5
    ): List<SemanticMemory>
    
    /**
     * Extract and store knowledge from episodes
     */
    suspend fun extractKnowledge(episodes: List<EpisodicMemory>): List<SemanticMemory>
    
    /**
     * Build agent context with memories
     */
    suspend fun buildContext(
        currentTask: String,
        sessionId: String,
        maxTokens: Int = 4000
    ): AgentContext
    
    /**
     * Clear all memories for a session
     */
    suspend fun clearSession(sessionId: String)
    
    /**
     * Clear all memories (use with caution)
     */
    suspend fun clearAll()
}
```

### Agent Integration API

```kotlin
// Extension to PerceptiveAgent interface
interface PerceptiveAgentWithMemory : PerceptiveAgent {
    /**
     * Memory manager for this agent
     */
    val memoryManager: MemoryManager
    
    /**
     * Run task with memory context
     */
    suspend fun runWithMemory(task: String): AgentHistory {
        // 1. Retrieve relevant memories
        val context = memoryManager.buildContext(task, session.id)
        
        // 2. Execute task with context
        val result = run(ActionOptions(
            action = task,
            variables = mapOf("memory_context" to context.formattedContext)
        ))
        
        // 3. Consolidate and store
        val outcome = determineOutcome(result)
        memoryManager.consolidateSession(session.id, outcome)
        
        return result
    }
    
    /**
     * Learn from current session
     */
    suspend fun learnFromSession(): List<SemanticMemory> {
        val workingMemory = memoryManager.getWorkingMemory(session.id)
        val episodes = memoryManager.extractKnowledge(
            listOf(memoryManager.consolidateSession(session.id, TaskOutcome.SUCCESS))
        )
        return episodes
    }
}
```

---

## Memory Management and Lifecycle

### Memory Lifecycle

```
┌───────────────┐
│  Task Start   │
└───────┬───────┘
        │
        ▼
┌───────────────────────┐
│ Create Working Memory │
│ - Initialize context  │
│ - Retrieve relevant   │
│   past memories       │
└───────┬───────────────┘
        │
        ▼
┌───────────────────────┐
│   Task Execution      │
│ - Add states to       │
│   working memory      │
│ - Access memories     │
│   as needed           │
└───────┬───────────────┘
        │
        ▼
┌───────────────────────┐
│   Task Complete       │
│ - Consolidate working │
│   memory to episodic  │
│ - Extract knowledge   │
└───────┬───────────────┘
        │
        ▼
┌───────────────────────┐
│  Store Memories       │
│ - Save episodic       │
│ - Update semantic     │
└───────────────────────┘
```

### Consolidation Process

```kotlin
class MemoryConsolidator(
    private val llmClient: LLMClient
) {
    /**
     * Consolidate working memory into episodic memory
     */
    suspend fun consolidate(
        workingMemory: WorkingMemory,
        outcome: TaskOutcome
    ): EpisodicMemory {
        // 1. Generate summary using LLM
        val summary = generateSummary(workingMemory)
        
        // 2. Extract key learnings
        val learnings = extractLearnings(workingMemory, outcome)
        
        // 3. Generate tags
        val tags = generateTags(workingMemory, summary)
        
        // 4. Create episodic memory
        return EpisodicMemory(
            sessionId = workingMemory.sessionId,
            taskDescription = workingMemory.taskContext.goal,
            taskGoal = workingMemory.taskContext.goal,
            startTime = workingMemory.timestamp,
            endTime = Instant.now(),
            states = workingMemory.recentStates.toList(),
            outcome = outcome,
            summary = summary,
            keyLearnings = learnings,
            tags = tags,
            domain = workingMemory.taskContext.domain,
            metadata = buildMetadata(workingMemory)
        )
    }
    
    /**
     * Extract semantic knowledge from episodes
     */
    suspend fun extractKnowledge(
        episodes: List<EpisodicMemory>
    ): List<SemanticMemory> {
        val knowledge = mutableListOf<SemanticMemory>()
        
        // 1. Identify common patterns
        val patterns = identifyPatterns(episodes)
        knowledge.addAll(patterns)
        
        // 2. Extract facts
        val facts = extractFacts(episodes)
        knowledge.addAll(facts)
        
        // 3. Distill strategies
        val strategies = distillStrategies(episodes)
        knowledge.addAll(strategies)
        
        // 4. Generate embeddings if enabled
        if (config.enableEmbeddings) {
            knowledge.forEach { it.embedding = generateEmbedding(it.content) }
        }
        
        return knowledge
    }
}
```

### Memory Pruning

```kotlin
class MemoryPruner {
    /**
     * Prune low-value memories to prevent unbounded growth
     */
    suspend fun prune(criteria: PruningCriteria) {
        when (criteria) {
            is AgeBased -> pruneByAge(criteria.maxAge)
            is UsageBased -> pruneByUsage(criteria.minUsageCount, criteria.maxAge)
            is SizeBased -> pruneToSize(criteria.maxMemories)
            is QualityBased -> pruneByQuality(criteria.minConfidence)
            is Hybrid -> pruneHybrid(criteria)
        }
    }
    
    private suspend fun pruneByUsage(minUsage: Int, maxAge: Duration) {
        // Remove memories that haven't been used recently
        // and don't meet minimum usage threshold
        val toRemove = storage.query(MemoryQuery.All)
            .filter { 
                it.timesUsed < minUsage && 
                Duration.between(it.lastUsed ?: it.created, Instant.now()) > maxAge 
            }
        
        toRemove.forEach { storage.delete(it.id) }
    }
}
```

---

## Integration with Existing Components

### 1. PerceptiveAgent Integration

```kotlin
abstract class AbstractPerceptiveAgent : PerceptiveAgent {
    // Add memory manager
    protected val memoryManager: MemoryManager by lazy {
        createMemoryManager()
    }
    
    protected open fun createMemoryManager(): MemoryManager {
        return DefaultMemoryManager(
            storage = createMemoryStorage(),
            config = loadMemoryConfig()
        )
    }
    
    // Override run to include memory context
    override suspend fun run(action: ActionOptions): AgentHistory {
        // Build context with memories
        val context = memoryManager.buildContext(
            action.action,
            session.id
        )
        
        // Execute with enhanced context
        val result = executeWithContext(action, context)
        
        // Consolidate memories
        consolidateMemories(result)
        
        return result
    }
}
```

### 2. InferenceEngine Integration

```kotlin
class InferenceEngine {
    // Add memory context to prompts
    suspend fun observe(
        params: ObserveParams,
        context: ExecutionContext
    ): ActionDescription {
        // 1. Get memory context
        val memoryContext = agent.memoryManager.buildContext(
            currentTask = context.instruction,
            sessionId = context.sessionId
        )
        
        // 2. Build messages with memory
        val messages = InferencePromptBuilder.buildObserveMessages(
            params,
            memoryContext = memoryContext.formattedContext
        )
        
        // ... rest of inference
    }
}
```

### 3. AgentEventBus Integration

```kotlin
object MemoryEvents {
    const val MEMORY_STORED = "memory.stored"
    const val MEMORY_RETRIEVED = "memory.retrieved"
    const val MEMORY_CONSOLIDATED = "memory.consolidated"
    const val KNOWLEDGE_EXTRACTED = "memory.knowledge_extracted"
}

// Emit events for memory operations
AgentEventBus.emitMemoryEvent(
    eventType = MemoryEvents.MEMORY_STORED,
    memoryId = episode.id,
    memoryType = "episodic",
    metadata = mapOf("sessionId" to sessionId)
)
```

### 4. Configuration Integration

```kotlin
// Add to application.properties
class AgenticConfig {
    // Memory configuration
    var memory.enabled: Boolean = true
    var memory.storage.backend: String = "file_based"
    var memory.storage.baseDir: String = "data/memory"
    var memory.episodic.maxSize: Int = 10_000
    var memory.semantic.maxSize: Int = 50_000
    var memory.consolidation.enabled: Boolean = true
    var memory.embeddings.enabled: Boolean = false
    var memory.embeddings.model: String? = null
}
```

---

## Implementation Phases

### Phase 1: Foundation (2-3 weeks)
**Goal**: Basic memory storage and retrieval

**Deliverables:**
- [ ] Memory data models (WorkingMemory, EpisodicMemory, SemanticMemory)
- [ ] MemoryStorage interface
- [ ] File-based storage implementation
- [ ] Basic MemoryManager implementation
- [ ] Unit tests

### Phase 2: Working Memory Integration (1-2 weeks)
**Goal**: Integrate working memory with existing AgentHistory

**Deliverables:**
- [ ] WorkingMemory implementation
- [ ] Integration with AgentStateManager
- [ ] Session lifecycle management
- [ ] Integration tests

### Phase 3: Episodic Memory (2-3 weeks)
**Goal**: Full episodic memory with consolidation

**Deliverables:**
- [ ] Memory consolidation logic
- [ ] LLM-based summarization
- [ ] Tag generation
- [ ] Retrieval strategies (recency, similarity)
- [ ] Integration with PerceptiveAgent

### Phase 4: Semantic Memory (2-3 weeks)
**Goal**: Knowledge extraction and semantic search

**Deliverables:**
- [ ] Knowledge extraction algorithms
- [ ] Pattern identification
- [ ] Semantic memory storage
- [ ] Basic similarity search (text-based)
- [ ] Memory pruning

### Phase 5: Context Integration (1-2 weeks)
**Goal**: Seamless integration into agent workflows

**Deliverables:**
- [ ] Context builder
- [ ] Prompt enhancement
- [ ] Token budget management
- [ ] Performance optimization

### Phase 6: Vector Search (Optional, 2-3 weeks)
**Goal**: Advanced semantic search with embeddings

**Deliverables:**
- [ ] Embedding generation
- [ ] Vector storage integration
- [ ] Semantic similarity search
- [ ] Performance benchmarks

### Phase 7: Advanced Features (Optional, 3-4 weeks)
**Goal**: Production-ready enhancements

**Deliverables:**
- [ ] Memory compression
- [ ] Distributed storage support
- [ ] Memory export/import
- [ ] Analytics and insights
- [ ] Admin UI for memory management

---

## Technical Considerations

### Performance

#### Retrieval Latency
- **Target**: <100ms for memory retrieval
- **Strategy**: 
  - In-memory caching of frequently accessed memories
  - Indexed storage for fast queries
  - Async/background loading

#### Token Efficiency
- **Challenge**: Memories can consume significant context tokens
- **Solutions**:
  - Hierarchical summarization (detailed → condensed → one-liner)
  - Smart truncation with preserved key points
  - Token budget tracking and enforcement

#### Storage Scalability
- **Challenge**: Memories grow unbounded
- **Solutions**:
  - Automatic pruning of low-value memories
  - Configurable retention policies
  - Archival to cold storage

### Privacy and Security

#### Memory Isolation
- Agent-specific memory spaces
- User-specific memory isolation
- Team/organizational memory sharing

#### Sensitive Data
- PII detection and redaction
- Configurable memory retention
- Export and deletion capabilities

#### Access Control
- Role-based access to memories
- Audit logging for memory operations

### Reliability

#### Data Consistency
- ACID properties for critical operations
- Transactional memory updates
- Backup and recovery mechanisms

#### Error Handling
- Graceful degradation when storage unavailable
- Fallback to working memory only
- Retry mechanisms with backoff

---

## Future Enhancements

### Advanced Features

1. **Memory Sharing and Collaboration**
   - Shared team memories
   - Memory templates for common tasks
   - Community memory pools

2. **Meta-Learning**
   - Learn from memory access patterns
   - Adaptive retrieval strategies
   - Self-improving consolidation

3. **Multi-Modal Memories**
   - Store screenshots/images
   - Video recordings of agent actions
   - Audio notes

4. **Memory Visualization**
   - Timeline view of agent history
   - Memory graph visualization
   - Interactive memory browser

5. **Federated Memory**
   - Distributed memory storage
   - Cross-agent memory sharing
   - Privacy-preserving memory sync

### Research Directions

1. **Optimal Consolidation Timing**
   - When to consolidate working memory?
   - How to balance recency vs. importance?

2. **Memory Compression Techniques**
   - Lossless summarization
   - Hierarchical memory structures
   - Differential memory encoding

3. **Memory-Guided Planning**
   - Use past experiences for better planning
   - Analogical reasoning from similar episodes
   - Meta-strategies from successful patterns

---

## Conclusion

This design provides a comprehensive foundation for adding LLM memory capabilities to the `pulsar-agentic` component. The three-layer architecture (working, episodic, semantic) mirrors human memory systems and enables agents to:

- Maintain context across sessions
- Learn from past experiences
- Retrieve relevant knowledge efficiently
- Operate within token budgets
- Scale to thousands of interactions

The phased implementation approach allows for incremental development and validation, with each phase delivering tangible value.

---

## References

- **AgentHistory**: `pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/model/Models.kt`
- **AgentStateManager**: `pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/detail/AgentStateManager.kt`
- **PerceptiveAgent**: `pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/PerceptiveAgent.kt`
- **InferenceEngine**: `pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/InferenceEngine.kt`

---

## Appendix: Example Usage

### Example 1: Agent with Memory

```kotlin
// Create agent with memory
val agent = AgenticContexts.getOrCreateAgent()

// First session: Learning
val result1 = agent.runWithMemory("""
    Go to amazon.com and search for 'mechanical keyboard'.
    Remember the search process for future reference.
""")

// Second session: Using memory
val result2 = agent.runWithMemory("""
    Search for 'gaming mouse' on Amazon.
    (Agent will remember the search process from earlier)
""")

// Third session: Complex task with memory
val result3 = agent.runWithMemory("""
    Compare prices of the top 3 mechanical keyboards on Amazon.
    Use what you learned about navigating Amazon.
""")
```

### Example 2: Explicit Memory Management

```kotlin
// Store custom knowledge
agent.memoryManager.storeKnowledge(
    SemanticMemory(
        category = MemoryCategory.FACT,
        content = "Amazon search button CSS selector: #nav-search-submit-button",
        confidence = 1.0,
        sourceEpisodes = listOf(currentSessionId),
        tags = setOf("amazon", "search", "selector")
    )
)

// Search for relevant knowledge
val relevantKnowledge = agent.memoryManager.searchKnowledge(
    query = "how to search on Amazon",
    category = MemoryCategory.FACT,
    limit = 5
)

// Consolidate session explicitly
val episode = agent.memoryManager.consolidateSession(
    sessionId = session.id,
    outcome = TaskOutcome.SUCCESS,
    summary = "Successfully navigated and extracted data"
)
```

### Example 3: Memory-Guided Task Execution

```kotlin
// Agent uses memories to improve task execution
val agent = AgenticContexts.getOrCreateAgent()

// Task with implicit memory usage
agent.runWithMemory("""
    Book a flight from NYC to SFO for next week.
    
    (Agent will:
     1. Retrieve memories about flight booking websites
     2. Remember successful navigation patterns
     3. Apply learned strategies for form filling
     4. Store new knowledge about this booking site)
""")
```

---

**End of Design Document**
