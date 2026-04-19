# Agent Tool Call 机制（代码索引）

本目录：`pulsar-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools`

- 总体机制文档（推荐先读）：`docs/agentic/tool-call-mechanism.md`

## 概览

### 入口

- `AgentToolManager`
  - domain 路由（driver/browser/fs/agent/system + custom domains）
  - 调用执行：`BasicToolCallExecutor.callFunctionOn(...)`
  - post hooks：`switchTab` / `navigate` 等
  - 导航统一等待：`ToolSpecification.MAY_NAVIGATE_ACTIONS`

### 工具规范与 prompt 输出

- `ToolSpecification`
  - 内置工具签名字符串：`TOOL_CALL_SPECIFICATION`
  - `SUPPORTED_TOOL_CALLS` / `SUPPORTED_ACTIONS` / `MAY_NAVIGATE_ACTIONS`

- `ToolCallSpecificationRenderer`
  - 把内置 specs（原样）+ 自定义 specs（结构化渲染）合并成 prompt-friendly 文本

- `ToolCallSpecificationProvider`
  - executor 可实现，用于提供自定义工具的 `List<ToolSpec>`

### 执行器

- `BasicToolCallExecutor`
  - 根据 `targetClass` 选择合适的 `ToolExecutor`，并调用其 `callFunctionOn(tc, target)`

- `executors/*`
  - 内置域的 `ToolExecutor` 实现：driver/browser/fs/agent/system ...

### 自定义工具扩展

- `CustomToolRegistry`
  - 注册/注销自定义 domain 的 executor
  - 缓存 prompt 可见的 tool specs（来自 `ToolCallSpecificationProvider` 或手动注入）

> 注意：自定义工具需要同时“注册 executor”（`CustomToolRegistry`）和“绑定 target”（`AgentToolManager.registerCustomTarget`）。
