# MCPToolController 优化建议

## 一、代码结构分析

### 1.1 整体架构

`MCPToolController` 是一个 Spring REST 控制器，负责：
- 对外暴露 MCP (Model Context Protocol) 工具调用接口
- 管理浏览器会话的生命周期
- 执行批处理命令

### 1.2 核心组件

| 组件 | 职责 | 状态 |
|------|------|------|
| **DTO 类** | 定义请求/响应结构 | 良好 |
| **会话管理** | open/close/list sessions | 良好 |
| **批处理执行** | executeBatchStep 处理多种操作类型 | ✅ 已优化 |
| **工具名称映射** | FRONTEND_TOOL_NAME_ALIASES | 良好 |
| **参数规范化** | normalizeFrontendToolCall/normalizeToolArguments | ✅ 已优化 |
| **批量命令限制** | 限制 batch 模式仅支持 DOM 操作 | ✅ 已实现 |

---

## 二、问题识别

### 2.1 批处理执行逻辑分散

**问题**：`executeBatchStep` 方法（约 100 行）包含大量重复的会话检查逻辑：

```kotlin
// 每个分支都重复相同的会话检查模式
"tool" -> {
    val sessionId = currentSessionId
        ?: throw IllegalArgumentException("""No active session...""")
    // ...
}
"snapshot" -> {
    val sessionId = currentSessionId
        ?: throw IllegalArgumentException("""No active session...""")
    // ...
}
```

**影响**：代码冗余，维护成本高，易出错。

### 2.2 工具调用路径不一致

**问题**：`executeAgentToolText` 方法在第 585-595 行和第 597-615 行有两个重载版本，存在逻辑重复。

**影响**：相同的工具执行逻辑分散在两处，难以维护。

### 2.3 魔法字符串硬编码

**问题**：大量字符串字面量直接硬编码在代码中：
- 错误消息模板（如 `"No active session. Run \"browser4-cli open\" first."`）
- 工具名称（如 `"browser_evaluate"`, `"page_url"`）
- JSON 键名（如 `"preFocusSelector"`, `"preMousePosition"`）

**影响**：难以统一管理，容易拼写错误。

### 2.4 参数规范化逻辑复杂

**问题**：`normalizeToolArguments` 方法（约 90 行）包含大量条件分支，处理各种参数映射。

**影响**：可读性差，新参数映射需要修改该方法。

### 2.5 缺少请求验证

**问题**：对输入参数缺少结构化验证，依赖运行时异常处理。

**影响**：错误信息不够友好，调试困难。

---

## 三、优化建议

### 3.1 重构批处理执行逻辑

**方案**：提取公共会话检查逻辑，使用策略模式或函数式编程简化分支处理。

```kotlin
// 优化后的 executeBatchStep 结构
private suspend fun executeBatchStep(
    index: Int,
    step: Map<String, Any?>,
    currentSessionId: String?,
    agent: BasicBrowserAgent
): BatchExecutionResult {
    val op = step["op"]?.toString() ?: throw IllegalArgumentException("Missing 'op' in batch step")
    
    // 使用 when 表达式的函数式风格
    return when (op) {
        "open" -> handleBatchOpen(index, step)
        "close" -> handleBatchClose(index, currentSessionId)
        "tool" -> handleBatchTool(index, step, currentSessionId, agent)
        "snapshot" -> handleBatchSnapshot(index, step, currentSessionId, agent)
        "screenshot" -> handleBatchScreenshot(index, step, currentSessionId, agent)
        else -> throw IllegalArgumentException("Unsupported batch step op: $op")
    }
}

// 提取公共检查方法
private fun requireSessionId(sessionId: String?): String {
    return sessionId ?: throw IllegalArgumentException("""No active session. Run "browser4-cli open" first.""")
}
```

**收益**：
- 减少代码重复约 30%
- 提高可读性和可测试性
- 便于单独测试每个操作类型

### 3.2 合并工具执行逻辑

**方案**：移除 `executeAgentToolText` 的重复重载，统一调用路径。

**收益**：
- 消除代码重复
- 统一错误处理逻辑

### 3.3 提取常量定义

**方案**：创建常量类管理魔法字符串。

```kotlin
object MCPConstants {
    const val ERROR_NO_ACTIVE_SESSION = """No active session. Run "browser4-cli open" first."""
    const val KEY_OP = "op"
    const val KEY_TOOL = "tool"
    const val KEY_ARGUMENTS = "arguments"
    const val KEY_SELECTOR = "selector"
    const val KEY_REF = "ref"
    const val KEY_SESSION_ID = "sessionId"
    // ... 其他常量
}
```

**收益**：
- 集中管理字符串常量
- 编译时检查拼写错误
- 便于国际化支持

### 3.4 参数规范化策略模式

**方案**：使用策略模式重构 `normalizeToolArguments`。

```kotlin
interface ArgumentNormalizer {
    fun normalize(toolName: String, args: MutableMap<String, Any?>): MutableMap<String, Any?>
}

class DefaultArgumentNormalizer : ArgumentNormalizer {
    override fun normalize(toolName: String, args: MutableMap<String, Any?>): MutableMap<String, Any?> {
        // 默认转换逻辑
        args.replaceAll { (k, v) -> snakeToCamel(k) to v }
        args.remove("sessionId")
        return args
    }
}

class SelectOptionArgumentNormalizer : ArgumentNormalizer {
    override fun normalize(toolName: String, args: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val legacyValue = args.remove("value")
        if (!args.containsKey("values") && legacyValue != null) {
            args["values"] = listOf(legacyValue.toString())
        }
        return args
    }
}
```

**收益**：
- 每个工具类型的参数转换逻辑独立
- 符合开闭原则
- 易于扩展新工具类型

### 3.5 添加请求验证

**方案**：使用 Jakarta Validation API 或自定义验证器。

```kotlin
data class MCPToolCallRequest(
    @NotBlank(message = "Tool name is required")
    @JsonProperty("tool") val tool: String,
    
    @Valid
    @JsonProperty("arguments") val arguments: Map<String, Any?>? = null
)
```

**收益**：
- 提前捕获无效输入
- 提供清晰的错误信息
- 减少运行时异常处理逻辑

---

## 四、实施步骤

### 阶段 1：基础重构（低风险）

| 步骤 | 任务 | 预计时间 |
|------|------|----------|
| 1 | 提取常量类 `MCPConstants` | 1 小时 |
| 2 | 提取公共会话检查方法 `requireSessionId` | 30 分钟 |
| 3 | 替换代码中的魔法字符串 | 1 小时 |

### 阶段 2：核心重构（中风险）

| 步骤 | 任务 | 预计时间 |
|------|------|----------|
| 4 | 重构 `executeBatchStep` 为函数式风格 | 2 小时 |
| 5 | 合并 `executeAgentToolText` 重载方法 | 1 小时 |

### 阶段 3：架构优化（较高风险）

| 步骤 | 任务 | 预计时间 |
|------|------|----------|
| 6 | 实现参数规范化策略模式 | 3 小时 |
| 7 | 添加请求验证框架 | 2 小时 |

### 阶段 4：测试与验证

| 步骤 | 任务 | 预计时间 |
|------|------|----------|
| 8 | 更新单元测试 | 2 小时 |
| 9 | 运行集成测试 | 1 小时 |

---

## 五、风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 重构引入回归错误 | 中 | 完善单元测试，逐步重构 |
| API 兼容性问题 | 低 | 保持接口签名不变 |
| 性能影响 | 低 | 策略模式使用惰性加载 |

---

## 六、预期收益

| 指标 | 改进前 | 改进后 |
|------|--------|--------|
| 代码重复率 | ~30% | <10% |
| 圈复杂度 | 高 | 中 |
| 可测试性 | 一般 | 良好 |
| 可扩展性 | 差 | 良好 |

---

## 七、已完成的优化

### 7.1 批处理命令限制

**背景**：batch 模式设计的首要目标是批量表单填写，不应支持会话管理操作。

**修改内容**：

1. **后端 (MCPToolController.kt)**：
   - 在 `executeBatchStep` 中添加对 `open`/`close` 操作的验证
   - 如果检测到非 DOM 操作，返回错误：`"Batch command only supports DOM operations. Op '%s' is not allowed."`
   - 移除了 `handleBatchOpen` 和 `handleBatchClose` 方法

2. **CLI (commands.rs)**：
   - 为 `CommandDef` 添加 `batch_supported` 字段
   - 仅以下类别的命令支持 batch 模式：
     - **Core**: snapshot, eval, select, upload, check, uncheck, dialog-accept, dialog-dismiss, resize
     - **Navigation**: goto, go-back, go-forward, reload
     - **Keyboard**: press, type, keydown, keyup, fill
     - **Mouse**: mousemove, mousedown, mouseup, mousewheel, click, dblclick, drag, hover
     - **Export**: screenshot, pdf
     - **Tabs**: tab-list, tab-new, tab-close, tab-select

3. **CLI (main.rs)**：
   - 在 `compile_batch_request` 中添加对 `batch_supported` 的检查
   - 如果命令不支持 batch 模式，返回错误：`"Command 'xxx' is not supported in batch mode. Batch mode only supports DOM operations."`

**预期收益**：
- 防止用户在 batch 模式中使用不适合的命令
- 确保 batch 命令专注于 DOM 操作（表单填写等）
- 提供清晰的错误提示

### 7.2 实施状态

| 阶段 | 任务 | 状态 |
|------|------|------|
| 阶段 1 | 提取常量类 `MCPConstants` | ✅ 已完成 |
| 阶段 1 | 提取公共会话检查方法 | ✅ 已完成 |
| 阶段 1 | 替换代码中的魔法字符串 | ✅ 已完成 |
| 阶段 2 | 重构 `executeBatchStep` 为函数式风格 | ✅ 已完成 |
| 阶段 2 | 合并 `executeAgentToolText` 重载方法 | ✅ 已完成 |
| 阶段 3 | 实现参数规范化策略模式 | ✅ 已完成 |
| 阶段 4 | 更新单元测试 | ✅ 已完成 |
| 阶段 4 | 运行集成测试 | ✅ 已完成 |
| 新增 | 限制 batch 模式仅支持 DOM 操作 | ✅ 已完成 |

## 八、总结

`MCPToolController` 整体架构设计合理，但存在以下主要改进空间：

1. **代码重复**：批处理执行逻辑中大量重复的会话检查代码
2. **可维护性**：魔法字符串和复杂条件分支影响可读性
3. **可扩展性**：参数规范化逻辑难以扩展

**已完成的优化**：

1. ✅ 创建了 `MCPConstants` 常量类，集中管理魔法字符串
2. ✅ 提取了 `requireSessionId` 公共检查方法
3. ✅ 重构了 `executeBatchStep` 为函数式风格，拆分多个独立方法
4. ✅ 合并了 `executeAgentToolText` 重载方法
5. ✅ 实现了参数规范化策略模式，创建了 `ArgumentNormalizers.kt`
6. ✅ **新增**：限制 batch 模式仅支持 DOM 操作，提高了命令执行的安全性和专注度

所有代码修改已通过测试验证，并提交到远程仓库。