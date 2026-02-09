# pulsar-agentic 组件 LLM 记忆功能设计

> **状态**: 设计文档 (仅设计，不编程)  
> **版本**: 1.0  
> **最后更新**: 2026-02-09  
> **作者**: Browser4 开发团队

## 目录
- [概述](#概述)
- [背景和动机](#背景和动机)
- [当前架构分析](#当前架构分析)
- [设计目标](#设计目标)
- [记忆架构](#记忆架构)
- [记忆类型](#记忆类型)
- [存储层设计](#存储层设计)
- [记忆检索与上下文集成](#记忆检索与上下文集成)
- [API 设计](#api-设计)
- [记忆管理与生命周期](#记忆管理与生命周期)
- [与现有组件的集成](#与现有组件的集成)
- [实现阶段](#实现阶段)
- [技术考虑](#技术考虑)
- [未来增强](#未来增强)

---

## 概述

本文档详细设计了为 Browser4 的 `pulsar-agentic` 组件添加 **LLM 记忆功能**的完整方案。该设计使 AI Agent 能够通过多层记忆系统在多次交互、会话和任务中保持上下文感知能力。

**核心特性：**
- **多层记忆系统**：短期（工作）记忆、长期（情节）记忆和语义记忆
- **自动记忆整合**：从工作记忆到长期存储的自动转换
- **向量语义搜索**：支持智能记忆检索
- **会话感知记忆**：在会话内部和跨会话维护上下文
- **Token 高效**：通过智能摘要最小化上下文窗口使用
- **可插拔存储后端**：支持多种持久化机制

---

## 背景和动机

### 当前状态

Browser4 的 `pulsar-agentic` 模块目前通过以下方式维护有限的记忆：

1. **AgentHistory**：在单个会话内跟踪执行状态和工具调用结果
2. **ProcessTrace**：记录详细的事件跟踪用于调试
3. **AgentStateManager**：管理当前执行上下文

**局限性：**
- 记忆仅限于会话范围，会话结束后丢失
- 缺乏对过去交互的语义理解
- 无法从以前的任务中学习
- 不能利用过去的经验做出更好的决策
- 当上下文增长时 Token 效率低下

### 动机

基于 LLM 的 Agent 从记忆能力中获益显著：

- **上下文连续性**：在多个会话中保持感知能力
- **从经验中学习**：基于过去的成功/失败改进性能
- **个性化**：记住用户偏好和模式
- **效率**：避免重新学习常见模式
- **复杂任务处理**：跨多个会话分解复杂任务

---

## 当前架构分析

### 现有记忆相关组件

#### 1. AgentHistory（Agent 历史）
```kotlin
data class AgentHistory(
    val states: MutableList<AgentState> = mutableListOf(),
)
```
- **用途**：跟踪当前会话中的 Agent 状态序列
- **范围**：单会话，仅内存
- **局限**：会话关闭时丢失

#### 2. AgentState（Agent 状态）
```kotlin
data class AgentState(
    var step: Int,
    var instruction: String,
    var browserUseState: BrowserUseState,
    var description: String? = null,
    // ... AI 生成的字段
)
```
- **用途**：特定步骤的 Agent 状态快照
- **内容**：包括观察、动作和结果
- **局限**：无跨会话持久化

#### 3. AgentStateManager（Agent 状态管理器）
```kotlin
class AgentStateManager(
    val agent: BasicBrowserAgent,
    val pageStateTracker: PageStateTracker,
)
```
- **用途**：管理执行上下文和状态历史
- **特性**：历史大小限制、清理机制
- **局限**：无语义索引或长期存储

#### 4. InferenceEngine（推理引擎）
- 将推理输入/输出记录到文件
- 无结构化检索机制
- 仅限于调试目的

### 集成点

记忆系统将与以下组件集成：

1. **PerceptiveAgent**：主 Agent 接口
2. **InferenceEngine**：用于观察和动作上下文
3. **AgenticSession**：用于会话生命周期管理
4. **AgentEventBus**：用于记忆相关事件

---

## 设计目标

### 功能目标

1. **多会话记忆**：跨 Agent 会话持久化和检索记忆
2. **语义理解**：启用语义搜索和相关记忆的检索
3. **自动整合**：智能地总结和整合记忆
4. **上下文感知检索**：获取与当前任务最相关的记忆
5. **增量学习**：基于过去的经验改进 Agent 行为

### 非功能目标

1. **性能**：记忆操作不应显著影响 Agent 响应性
2. **可扩展性**：支持数千条记忆而不降级
3. **Token 效率**：通过智能摘要最小化 Token 使用
4. **可扩展性**：易于添加新的记忆类型和存储后端
5. **隐私**：支持记忆隔离和清理

---

## 记忆架构

### 三层记忆系统

```
┌─────────────────────────────────────────────────────────────┐
│                        Agent 接口                            │
│                    (PerceptiveAgent)                         │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                      记忆管理器                               │
│  - 记忆协调                                                   │
│  - 上下文集成                                                 │
│  - 整合编排                                                   │
└─────────┬──────────────────┬──────────────────┬─────────────┘
          │                  │                  │
┌─────────▼─────────┐ ┌─────▼─────────┐ ┌─────▼──────────┐
│    工作记忆       │ │   情节记忆     │ │   语义记忆      │
│   (短期记忆)      │ │  (长期记忆)    │ │   (知识库)      │
│                   │ │                │ │                │
│ - 当前任务        │ │ - 任务情节     │ │ - 学习到的事实  │
│ - 活动上下文      │ │ - 经验         │ │ - 模式         │
│ - 最近动作        │ │ - 结果         │ │ - 技能         │
└─────────┬─────────┘ └────────┬───────┘ └────────┬───────┘
          │                    │                   │
          └────────────────────┼───────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│                      存储层                                  │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   内存存储   │  │  文件存储    │  │   向量数据库  │     │
│  │   (临时)     │  │(JSON/SQLite) │  │  (嵌入向量)   │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

### 组件概览

#### 1. 记忆管理器（Memory Manager）
- 所有记忆操作的中央协调器
- 处理记忆生命周期和整合
- 将记忆集成到 Agent 上下文
- 管理记忆检索策略

#### 2. 工作记忆（Working Memory）
- 当前会话的活动上下文
- 最近的观察和动作
- 临时任务特定信息
- 高访问频率，易失性

#### 3. 情节记忆（Episodic Memory）
- 完成任务和经验的记录
- 成功/失败结果及上下文
- 按时间排序的经验
- 跨会话持久化

#### 4. 语义记忆（Semantic Memory）
- 提取的知识和模式
- 从情节中归纳的学习
- 领域特定的事实
- 按语义相似度索引

#### 5. 存储层（Storage Layer）
- 可插拔后端架构
- 支持多种持久化机制
- 处理序列化和索引

---

## 记忆类型

### 1. 工作记忆（短期）

**目的**：维护当前任务/会话的上下文

**特征：**
- **容量**：有限（类似当前 AgentHistory）
- **持续时间**：单会话
- **访问模式**：顺序且频繁
- **内容类型**：最近的 Agent 状态、观察、动作

**数据结构：**
```kotlin
data class WorkingMemory(
    val sessionId: String,
    val taskContext: TaskContext,
    val recentStates: LimitedQueue<AgentState>,  // 最后 N 个状态
    val scratchpad: MutableMap<String, Any>,     // 临时变量
    val timestamp: Instant = Instant.now()
)

data class TaskContext(
    val goal: String,                    // 任务目标
    val domain: String?,                 // 领域
    val subTasks: List<String>,          // 子任务
    val currentStep: Int,                // 当前步骤
    val metadata: Map<String, Any>       // 元数据
)
```

**操作：**
- `add(state: AgentState)`：添加新状态到工作记忆
- `getCurrent(): TaskContext`：获取当前任务上下文
- `clear()`：清除工作记忆
- `consolidate(): EpisodicMemory`：转换为情节记忆

### 2. 情节记忆（长期）

**目的**：存储经验和任务执行

**特征：**
- **容量**：大（可配置，例如 10,000 个情节）
- **持续时间**：跨会话持久化
- **访问模式**：按相似度或最近性检索
- **内容类型**：完整的任务情节及结果

**数据结构：**
```kotlin
data class EpisodicMemory(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val taskDescription: String,         // 任务描述
    val taskGoal: String,                // 任务目标
    val startTime: Instant,              // 开始时间
    val endTime: Instant,                // 结束时间
    val states: List<AgentState>,        // 状态列表
    val outcome: TaskOutcome,            // 任务结果
    val summary: String,                 // LLM 生成的摘要
    val keyLearnings: List<String>,      // 关键学习
    val tags: Set<String>,               // 标签
    val domain: String?,                 // 领域
    val metadata: Map<String, Any>       // 元数据
)

enum class TaskOutcome {
    SUCCESS,          // 成功
    PARTIAL_SUCCESS,  // 部分成功
    FAILURE,          // 失败
    ABORTED           // 中止
}
```

**操作：**
- `store(episode: EpisodicMemory)`：持久化情节
- `retrieve(query: MemoryQuery): List<EpisodicMemory>`：检索相关情节
- `getSimilar(episode: EpisodicMemory, limit: Int)`：查找相似情节
- `getRecent(limit: Int)`：获取最近的情节

### 3. 语义记忆（知识）

**目的**：存储提取的知识和学习到的模式

**特征：**
- **容量**：大（可配置）
- **持续时间**：长期持久化
- **访问模式**：语义相似度搜索
- **内容类型**：事实、模式、策略

**数据结构：**
```kotlin
data class SemanticMemory(
    val id: String = UUID.randomUUID().toString(),
    val category: MemoryCategory,        // 记忆分类
    val content: String,                 // 内容
    val embedding: FloatArray?,          // 向量嵌入（用于语义搜索）
    val confidence: Double,              // 置信度
    val sourceEpisodes: List<String>,    // 源情节引用
    val timesUsed: Int = 0,             // 使用次数
    val lastUsed: Instant? = null,      // 最后使用时间
    val created: Instant = Instant.now(), // 创建时间
    val tags: Set<String>,               // 标签
    val metadata: Map<String, Any>       // 元数据
)

enum class MemoryCategory {
    FACT,              // 事实（如："Amazon 搜索按钮在 #twotabsearchtextbox"）
    PATTERN,           // 行为模式（如："登录通常需要先输入邮箱再输入密码"）
    STRATEGY,          // 问题解决策略（如："使用 CSS 选择器优先于 XPath"）
    PREFERENCE,        // 用户偏好（如："用户偏好详细输出"）
    ERROR_RESOLUTION,  // 常见错误的解决方法
    SKILL              // 学习到的技能（如："从 Amazon 提取产品信息"）
}
```

**操作：**
- `store(memory: SemanticMemory)`：存储语义知识
- `search(query: String, category: MemoryCategory?, limit: Int)`：语义搜索
- `update(id: String, updates: Map<String, Any>)`：更新现有记忆
- `incrementUsage(id: String)`：跟踪记忆使用
- `prune(criteria: PruningCriteria)`：删除低价值记忆

---

## 存储层设计

### 存储抽象

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

### 存储后端选项

#### 1. 内存存储（默认）
```kotlin
class InMemoryStorage : MemoryStorage {
    private val memories = ConcurrentHashMap<String, Memory>()
    
    // 快速访问，无持久化
    // 适合开发和测试
}
```

**优点：**
- 快速访问
- 无外部依赖
- 简单实现

**缺点：**
- 重启后丢失
- 受 RAM 限制
- 无向量搜索

#### 2. 文件存储
```kotlin
class FileBasedStorage(
    private val baseDir: Path
) : MemoryStorage {
    // 以 JSON 文件形式存储在目录结构中
    // baseDir/episodic/{sessionId}/{episodeId}.json
    // baseDir/semantic/{category}/{memoryId}.json
}
```

**优点：**
- 简单持久化
- 易于检查和调试
- 无数据库依赖
- 与现有日志基础设施协作

**缺点：**
- 比内存慢
- 查询能力有限
- 无原生向量搜索

#### 3. SQLite 存储
```kotlin
class SQLiteStorage(
    private val dbPath: Path
) : MemoryStorage {
    // 关系型存储，支持 FTS5 全文搜索
    // 可以将嵌入向量存储为 BLOB
}
```

**优点：**
- 良好的查询性能
- ACID 合规
- 全文搜索支持
- 单文件数据库

**缺点：**
- 需要 SQLite 依赖
- 向量搜索有限
- 模式管理

#### 4. 向量数据库集成（未来）
```kotlin
class VectorDBStorage(
    private val config: VectorDBConfig
) : MemoryStorage {
    // 集成 Chroma、Pinecone、Weaviate 等
    // 原生支持语义相似度搜索
}
```

**优点：**
- 优秀的语义搜索
- 可扩展至数百万向量
- 专为嵌入向量设计

**缺点：**
- 外部依赖
- 增加复杂性
- 潜在成本（云服务）

### 存储配置

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
    IN_MEMORY,     // 内存
    FILE_BASED,    // 文件
    SQLITE,        // SQLite
    VECTOR_DB      // 向量数据库
}
```

---

## 记忆检索与上下文集成

### 检索策略

#### 1. 基于最近性的检索
优先检索最近的记忆。

```kotlin
data class RecencyQuery(
    val limit: Int = 10,
    val category: MemoryCategory? = null,
    val tags: Set<String> = emptySet()
) : MemoryQuery
```

**使用场景**：当最近的上下文最相关时

#### 2. 基于相似度的检索
检索与当前上下文相似的记忆。

```kotlin
data class SimilarityQuery(
    val queryText: String,
    val queryEmbedding: FloatArray? = null,
    val limit: Int = 5,
    val minSimilarity: Double = 0.7,
    val category: MemoryCategory? = null
) : MemoryQuery
```

**使用场景**：当语义相关性最重要时

#### 3. 基于标签的检索
检索具有特定标签的记忆。

```kotlin
data class TagQuery(
    val tags: Set<String>,
    val matchAll: Boolean = false,  // AND vs OR
    val limit: Int = 10
) : MemoryQuery
```

**使用场景**：在特定领域工作时

#### 4. 混合检索
结合多种策略并赋予权重。

```kotlin
data class HybridQuery(
    val recencyWeight: Double = 0.3,
    val similarityWeight: Double = 0.5,
    val relevanceWeight: Double = 0.2,
    val context: String,
    val limit: Int = 5
) : MemoryQuery
```

**使用场景**：大多数场景的最佳整体策略

### 上下文集成

#### 使用记忆构建 Agent 上下文

```kotlin
class MemoryContextBuilder {
    suspend fun buildContext(
        currentTask: String,
        workingMemory: WorkingMemory,
        maxTokens: Int = 4000
    ): AgentContext {
        // 1. 检索相关记忆
        val relevant = retrieveRelevantMemories(currentTask, maxTokens / 2)
        
        // 2. 格式化为 LLM 上下文
        val contextText = formatMemoriesForLLM(relevant)
        
        // 3. 与工作记忆结合
        return AgentContext(
            task = currentTask,
            workingMemory = workingMemory,
            relevantEpisodes = relevant.episodic,
            relevantKnowledge = relevant.semantic,
            formattedContext = contextText
        )
    }
}
```

#### LLM 上下文格式

```markdown
# Agent 上下文

## 当前任务
{task_description}

## 工作记忆
- 当前步骤: {step}
- 最近动作: {recent_actions}
- 活动变量: {scratchpad}

## 相关过去经验
### 情节 1: {episode_summary}
- 结果: {outcome}
- 关键学习: {learning}
- 相关动作: {actions}

### 情节 2: ...

## 相关知识
- {fact_1}
- {pattern_1}
- {strategy_1}
```

---

## API 设计

### 记忆管理器 API

```kotlin
interface MemoryManager {
    /**
     * 获取会话的当前工作记忆
     */
    fun getWorkingMemory(sessionId: String): WorkingMemory
    
    /**
     * 添加状态到工作记忆
     */
    suspend fun addToWorkingMemory(sessionId: String, state: AgentState)
    
    /**
     * 将工作记忆整合为情节记忆
     */
    suspend fun consolidateSession(
        sessionId: String,
        outcome: TaskOutcome,
        summary: String? = null
    ): EpisodicMemory
    
    /**
     * 存储情节记忆
     */
    suspend fun storeEpisode(episode: EpisodicMemory): String
    
    /**
     * 检索与当前上下文相关的记忆
     */
    suspend fun retrieveRelevant(
        query: MemoryQuery,
        limit: Int = 5
    ): List<Memory>
    
    /**
     * 存储语义知识
     */
    suspend fun storeKnowledge(knowledge: SemanticMemory): String
    
    /**
     * 搜索语义记忆
     */
    suspend fun searchKnowledge(
        query: String,
        category: MemoryCategory? = null,
        limit: Int = 5
    ): List<SemanticMemory>
    
    /**
     * 从情节中提取并存储知识
     */
    suspend fun extractKnowledge(episodes: List<EpisodicMemory>): List<SemanticMemory>
    
    /**
     * 使用记忆构建 Agent 上下文
     */
    suspend fun buildContext(
        currentTask: String,
        sessionId: String,
        maxTokens: Int = 4000
    ): AgentContext
    
    /**
     * 清除会话的所有记忆
     */
    suspend fun clearSession(sessionId: String)
    
    /**
     * 清除所有记忆（谨慎使用）
     */
    suspend fun clearAll()
}
```

---

## 记忆管理与生命周期

### 记忆生命周期

```
┌───────────────┐
│   任务开始    │
└───────┬───────┘
        │
        ▼
┌───────────────────────┐
│  创建工作记忆         │
│ - 初始化上下文        │
│ - 检索相关的          │
│   过去记忆            │
└───────┬───────────────┘
        │
        ▼
┌───────────────────────┐
│   任务执行            │
│ - 添加状态到          │
│   工作记忆            │
│ - 按需访问记忆        │
└───────┬───────────────┘
        │
        ▼
┌───────────────────────┐
│   任务完成            │
│ - 将工作记忆          │
│   整合为情节          │
│ - 提取知识            │
└───────┬───────────────┘
        │
        ▼
┌───────────────────────┐
│  存储记忆             │
│ - 保存情节            │
│ - 更新语义记忆        │
└───────────────────────┘
```

### 整合过程

```kotlin
class MemoryConsolidator(
    private val llmClient: LLMClient
) {
    /**
     * 将工作记忆整合为情节记忆
     */
    suspend fun consolidate(
        workingMemory: WorkingMemory,
        outcome: TaskOutcome
    ): EpisodicMemory {
        // 1. 使用 LLM 生成摘要
        val summary = generateSummary(workingMemory)
        
        // 2. 提取关键学习
        val learnings = extractLearnings(workingMemory, outcome)
        
        // 3. 生成标签
        val tags = generateTags(workingMemory, summary)
        
        // 4. 创建情节记忆
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
     * 从情节中提取语义知识
     */
    suspend fun extractKnowledge(
        episodes: List<EpisodicMemory>
    ): List<SemanticMemory> {
        val knowledge = mutableListOf<SemanticMemory>()
        
        // 1. 识别常见模式
        val patterns = identifyPatterns(episodes)
        knowledge.addAll(patterns)
        
        // 2. 提取事实
        val facts = extractFacts(episodes)
        knowledge.addAll(facts)
        
        // 3. 提炼策略
        val strategies = distillStrategies(episodes)
        knowledge.addAll(strategies)
        
        // 4. 如果启用，生成嵌入向量
        if (config.enableEmbeddings) {
            knowledge.forEach { it.embedding = generateEmbedding(it.content) }
        }
        
        return knowledge
    }
}
```

---

## 与现有组件的集成

### 1. PerceptiveAgent 集成

```kotlin
abstract class AbstractPerceptiveAgent : PerceptiveAgent {
    // 添加记忆管理器
    protected val memoryManager: MemoryManager by lazy {
        createMemoryManager()
    }
    
    // 重写 run 方法以包含记忆上下文
    override suspend fun run(action: ActionOptions): AgentHistory {
        // 使用记忆构建上下文
        val context = memoryManager.buildContext(
            action.action,
            session.id
        )
        
        // 使用增强上下文执行
        val result = executeWithContext(action, context)
        
        // 整合记忆
        consolidateMemories(result)
        
        return result
    }
}
```

### 2. InferenceEngine 集成

```kotlin
class InferenceEngine {
    // 将记忆上下文添加到提示词
    suspend fun observe(
        params: ObserveParams,
        context: ExecutionContext
    ): ActionDescription {
        // 1. 获取记忆上下文
        val memoryContext = agent.memoryManager.buildContext(
            currentTask = context.instruction,
            sessionId = context.sessionId
        )
        
        // 2. 使用记忆构建消息
        val messages = InferencePromptBuilder.buildObserveMessages(
            params,
            memoryContext = memoryContext.formattedContext
        )
        
        // ... 其余推理过程
    }
}
```

### 3. AgentEventBus 集成

```kotlin
object MemoryEvents {
    const val MEMORY_STORED = "memory.stored"
    const val MEMORY_RETRIEVED = "memory.retrieved"
    const val MEMORY_CONSOLIDATED = "memory.consolidated"
    const val KNOWLEDGE_EXTRACTED = "memory.knowledge_extracted"
}

// 为记忆操作发出事件
AgentEventBus.emitMemoryEvent(
    eventType = MemoryEvents.MEMORY_STORED,
    memoryId = episode.id,
    memoryType = "episodic",
    metadata = mapOf("sessionId" to sessionId)
)
```

### 4. 配置集成

```kotlin
// 添加到 application.properties
class AgenticConfig {
    // 记忆配置
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

## 实现阶段

### 第一阶段：基础（2-3 周）
**目标**：基本的记忆存储和检索

**交付物：**
- [ ] 记忆数据模型（WorkingMemory、EpisodicMemory、SemanticMemory）
- [ ] MemoryStorage 接口
- [ ] 基于文件的存储实现
- [ ] 基本 MemoryManager 实现
- [ ] 单元测试

### 第二阶段：工作记忆集成（1-2 周）
**目标**：将工作记忆与现有 AgentHistory 集成

**交付物：**
- [ ] WorkingMemory 实现
- [ ] 与 AgentStateManager 集成
- [ ] 会话生命周期管理
- [ ] 集成测试

### 第三阶段：情节记忆（2-3 周）
**目标**：完整的情节记忆与整合

**交付物：**
- [ ] 记忆整合逻辑
- [ ] 基于 LLM 的摘要
- [ ] 标签生成
- [ ] 检索策略（最近性、相似度）
- [ ] 与 PerceptiveAgent 集成

### 第四阶段：语义记忆（2-3 周）
**目标**：知识提取和语义搜索

**交付物：**
- [ ] 知识提取算法
- [ ] 模式识别
- [ ] 语义记忆存储
- [ ] 基本相似度搜索（基于文本）
- [ ] 记忆清理

### 第五阶段：上下文集成（1-2 周）
**目标**：无缝集成到 Agent 工作流

**交付物：**
- [ ] 上下文构建器
- [ ] 提示词增强
- [ ] Token 预算管理
- [ ] 性能优化

### 第六阶段：向量搜索（可选，2-3 周）
**目标**：使用嵌入向量的高级语义搜索

**交付物：**
- [ ] 嵌入向量生成
- [ ] 向量存储集成
- [ ] 语义相似度搜索
- [ ] 性能基准测试

### 第七阶段：高级特性（可选，3-4 周）
**目标**：生产就绪的增强

**交付物：**
- [ ] 记忆压缩
- [ ] 分布式存储支持
- [ ] 记忆导出/导入
- [ ] 分析和洞察
- [ ] 记忆管理的管理 UI

---

## 技术考虑

### 性能

#### 检索延迟
- **目标**：记忆检索 <100ms
- **策略**：
  - 频繁访问记忆的内存缓存
  - 索引存储以实现快速查询
  - 异步/后台加载

#### Token 效率
- **挑战**：记忆可能消耗大量上下文 Token
- **解决方案**：
  - 层次化摘要（详细 → 浓缩 → 一行）
  - 保留关键点的智能截断
  - Token 预算跟踪和强制执行

#### 存储可扩展性
- **挑战**：记忆无限增长
- **解决方案**：
  - 自动清理低价值记忆
  - 可配置的保留策略
  - 归档到冷存储

### 隐私和安全

#### 记忆隔离
- Agent 特定的记忆空间
- 用户特定的记忆隔离
- 团队/组织记忆共享

#### 敏感数据
- PII 检测和编辑
- 可配置的记忆保留
- 导出和删除能力

#### 访问控制
- 基于角色的记忆访问
- 记忆操作的审计日志

### 可靠性

#### 数据一致性
- 关键操作的 ACID 属性
- 事务性记忆更新
- 备份和恢复机制

#### 错误处理
- 存储不可用时的优雅降级
- 仅回退到工作记忆
- 带退避的重试机制

---

## 未来增强

### 高级特性

1. **记忆共享与协作**
   - 共享团队记忆
   - 常见任务的记忆模板
   - 社区记忆池

2. **元学习**
   - 从记忆访问模式学习
   - 自适应检索策略
   - 自我改进的整合

3. **多模态记忆**
   - 存储截图/图像
   - Agent 动作的视频录制
   - 音频笔记

4. **记忆可视化**
   - Agent 历史的时间线视图
   - 记忆图可视化
   - 交互式记忆浏览器

5. **联邦记忆**
   - 分布式记忆存储
   - 跨 Agent 记忆共享
   - 隐私保护的记忆同步

### 研究方向

1. **最佳整合时机**
   - 何时整合工作记忆？
   - 如何平衡最近性与重要性？

2. **记忆压缩技术**
   - 无损摘要
   - 层次化记忆结构
   - 差分记忆编码

3. **记忆引导规划**
   - 使用过去经验进行更好的规划
   - 从相似情节进行类比推理
   - 从成功模式中提取元策略

---

## 结论

本设计为 `pulsar-agentic` 组件添加 LLM 记忆能力提供了全面的基础。三层架构（工作、情节、语义）模仿人类记忆系统，使 Agent 能够：

- 跨会话维护上下文
- 从过去经验中学习
- 高效检索相关知识
- 在 Token 预算内运行
- 扩展至数千次交互

分阶段的实现方法允许增量开发和验证，每个阶段都提供实际价值。

---

## 参考

- **AgentHistory**：`pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/model/Models.kt`
- **AgentStateManager**：`pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/detail/AgentStateManager.kt`
- **PerceptiveAgent**：`pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/PerceptiveAgent.kt`
- **InferenceEngine**：`pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/InferenceEngine.kt`

---

## 附录：使用示例

### 示例 1：带记忆的 Agent

```kotlin
// 创建带记忆的 Agent
val agent = AgenticContexts.getOrCreateAgent()

// 第一次会话：学习
val result1 = agent.runWithMemory("""
    访问 amazon.com 并搜索"机械键盘"。
    记住搜索过程以供将来参考。
""")

// 第二次会话：使用记忆
val result2 = agent.runWithMemory("""
    在 Amazon 上搜索"游戏鼠标"。
    (Agent 将记住之前的搜索过程)
""")

// 第三次会话：使用记忆的复杂任务
val result3 = agent.runWithMemory("""
    比较 Amazon 上前 3 款机械键盘的价格。
    使用你学到的关于导航 Amazon 的知识。
""")
```

### 示例 2：显式记忆管理

```kotlin
// 存储自定义知识
agent.memoryManager.storeKnowledge(
    SemanticMemory(
        category = MemoryCategory.FACT,
        content = "Amazon 搜索按钮 CSS 选择器: #nav-search-submit-button",
        confidence = 1.0,
        sourceEpisodes = listOf(currentSessionId),
        tags = setOf("amazon", "search", "selector")
    )
)

// 搜索相关知识
val relevantKnowledge = agent.memoryManager.searchKnowledge(
    query = "如何在 Amazon 上搜索",
    category = MemoryCategory.FACT,
    limit = 5
)

// 显式整合会话
val episode = agent.memoryManager.consolidateSession(
    sessionId = session.id,
    outcome = TaskOutcome.SUCCESS,
    summary = "成功导航并提取数据"
)
```

### 示例 3：记忆引导的任务执行

```kotlin
// Agent 使用记忆改进任务执行
val agent = AgenticContexts.getOrCreateAgent()

// 隐式使用记忆的任务
agent.runWithMemory("""
    预订下周从纽约到旧金山的机票。
    
    (Agent 将：
     1. 检索关于机票预订网站的记忆
     2. 记住成功的导航模式
     3. 应用学到的表单填写策略
     4. 存储关于此预订网站的新知识)
""")
```

---

**设计文档结束**
