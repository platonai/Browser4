# 智能体记忆系统

## 概述

BrowserPerceptiveAgent 现在包含一个记忆系统，使其能够跨交互维护上下文。此功能使智能体能够记住先前步骤中的重要信息，并使用这些信息在未来的操作中做出更好的决策。

## 架构

记忆系统由三个主要组件组成：

### 1. ShortTermMemory（短期记忆）
- **目的**：使用滑动窗口方法存储最近的记忆
- **容量**：可配置（默认：50 条记忆）
- **保留策略**：达到容量时自动删除最旧的记忆
- **使用场景**：维护最近操作和观察的上下文

### 2. LongTermMemory（长期记忆）
- **目的**：存储重要事实和信息
- **过滤机制**：仅存储重要度高于阈值的记忆（默认：0.7）
- **容量**：可配置（默认：200 条记忆）
- **保留策略**：达到容量时删除最不重要的记忆
- **使用场景**：为长期运行的任务保留关键信息

### 3. CompositeMemory（组合记忆）
- **目的**：结合短期和长期记忆
- **去重功能**：自动处理两个存储中的重复记忆
- **检索机制**：按相关性和重要性合并和排序记忆
- **使用场景**：提供统一的记忆操作接口

## 配置

可以通过 `AgentConfig` 配置记忆设置：

```kotlin
val config = AgentConfig(
    enableMemory = true,                    // 启用/禁用记忆系统
    shortTermMemorySize = 50,               // 最大短期记忆数量
    longTermMemorySize = 200,               // 最大长期记忆数量
    longTermMemoryThreshold = 0.7,          // 长期存储的最低重要度
    memoryRetrievalLimit = 10               // 提示中包含的最大记忆数量
)
```

## 工作原理

### 1. 记忆收集

智能体在执行过程中自动提取和存储记忆：

- **从 LLM 响应中**：LLM 可以在其响应中提供包含重要观察的"memory"字段
- **从评估中**：先前操作的评估被存储为记忆
- **从目标中**：下一步目标陈述以高重要度存储

### 2. 记忆检索

在生成操作时，智能体：
1. 根据当前指令检索相关记忆
2. 格式化以包含在 LLM 提示中
3. 提供上下文帮助 LLM 做出明智的决策

### 3. 记忆持久化

记忆通过检查点系统自动保存和恢复：
- 记忆包含在检查点快照中
- 恢复会话时，记忆会自动加载
- 支持在完整上下文下恢复任务

## 使用示例

### 基本使用

```kotlin
val agent = BrowserPerceptiveAgent(driver, session, config = AgentConfig(
    enableMemory = true,
    shortTermMemorySize = 50,
    longTermMemorySize = 200
))

// 记忆会自动收集和使用
agent.resolve("搜索产品并比较价格")
```

### 手动记忆管理

```kotlin
// 手动添加记忆
agent.memory.add(
    content = "用户偏好 100 元以下的产品",
    importance = 0.9,
    metadata = mapOf("category" to "preference")
)

// 检索相关记忆
val memories = agent.memory.retrieve(query = "价格偏好", limit = 5)

// 获取用于提示的记忆上下文
val context = agent.memory.getMemoryContext(query = "购物", limit = 10)

// 清除所有记忆
agent.memory.clear()
```

### 检查点集成

```kotlin
// 启用检查点以持久化记忆
val config = AgentConfig(
    enableMemory = true,
    enableCheckpointing = true,
    checkpointIntervalSteps = 10
)

val agent = BrowserPerceptiveAgent(driver, session, config = config)

// 记忆会自动保存在检查点中
agent.resolve("复杂的多步骤任务")

// 从检查点恢复（包括记忆状态）
val checkpoint = agent.restoreFromCheckpoint(sessionId)
```

## 记忆重要性评分

记忆根据上下文分配重要性分数（0.0 到 1.0）：

- **1.0**：任务完成事件
- **0.8**：下一步目标陈述
- **0.7**：成功操作的记忆
- **0.6**：操作评估
- **0.3**：失败操作的记忆

只有重要度高于 `longTermMemoryThreshold`（默认 0.7）的记忆才会存储在长期记忆中。

## LLM 集成

记忆字段包含在发送给 LLM 的观察模式中：

```json
{
  "elements": [
    {
      "locator": "0,4",
      "description": "操作描述",
      "domain": "driver",
      "method": "click",
      "memory": "描述此步骤和整体进度的 1-3 个具体句子",
      "evaluationPreviousGoal": "对前一个操作的分析",
      "nextGoal": "下一步目标的明确陈述"
    }
  ]
}
```

LLM 可以在 `memory` 字段中填充应该记住的重要观察。

## 优势

1. **上下文保留**：在多个步骤中维护重要信息
2. **改进决策**：LLM 可以访问相关的过去信息
3. **任务连续性**：可以在完整上下文下恢复长期运行的任务
4. **减少重复**：避免重新学习已经发现的信息
5. **更好的规划**：可以跟踪进度并做出明智的决策

## 最佳实践

1. **适当配置**：根据任务复杂度调整记忆大小
2. **明智使用重要度**：为关键信息分配更高的重要度
3. **启用检查点**：结合检查点实现稳健的恢复
4. **监控记忆使用**：在开始不相关任务时清除记忆
5. **提供上下文**：检索记忆时包含相关查询

## 性能考虑

- 记忆检索速度快（O(n)，其中 n = 记忆数量）
- 短期记忆使用双端队列实现高效的最旧优先删除
- 长期记忆使用哈希映射实现快速查找和去重
- 记忆快照包含在检查点中（配置时考虑大小）

## 未来增强

记忆系统的潜在改进：

- 基于向量的语义搜索以实现更准确的检索
- 基于任务结果的自动重要性评分
- 记忆整合和总结
- 外部记忆存储（数据库、文件系统）
- 记忆可视化和调试工具
