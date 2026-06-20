# Agent Tool Call 机制（代码索引）

本目录：`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools`

- 总体机制文档（推荐先读）：`docs/agentic/tool-call-mechanism.md`

## 概览

### 入口

- `AgentToolManager`
  - domain 路由（driver/browser/fs/agent/system/skill + custom domains）
  - 调用执行：`BasicToolCallExecutor.callFunctionOn(...)`
  - post hooks：`switchTab` / `navigate` 等
  - 导航统一等待：`ToolSpecification.MAY_NAVIGATE_ACTIONS`
  - 自定义 target 注册/注销：`registerCustomTarget` / `unregisterCustomTarget`
  - skill 集成：通过 `SkillContext` + `SkillToolExecutor` + `SkillToolTarget` 支持 skill 域

- `AgenticCliRunner`
  - 内部 CLI 命令分发器，解析 browser4-cli 命令字符串并路由到 `AgentToolManager`
  - 支持命令：goto, go-back, go-forward, reload, click, dblclick, type, press, fill, hover, select, check, uncheck, upload, drag, mousemove, mousedown, mouseup, mousewheel, keydown, keyup, snapshot, screenshot, eval, resize, dialog-accept, dialog-dismiss, tab-list, tab-new, tab-close, tab-select, extract, summarize, agent-run, agent-status, agent-result
  - 命令解析、参数规范化、前端 MCP tool name → domain+method 解析

### 工具规范与 prompt 输出

- `ToolSpecification`
  - 内置工具签名字符串：`TOOL_CALL_SPECIFICATION`
  - `SUPPORTED_TOOL_CALLS` / `SUPPORTED_ACTIONS` / `MAY_NAVIGATE_ACTIONS`

- `ToolCallSpecificationRenderer`
  - 把内置 specs（原样）+ 自定义 specs（结构化渲染）合并成 prompt-friendly 文本

- `ToolCallSpecificationProvider`
  - executor 可实现，用于提供自定义工具的 `List<ToolSpec>`

- `ToolSpecGenerator`
  - 从 `ToolExecutor` 生成 `ToolSpec` 列表

### 内置执行器 (`builtin/`)

- `AbstractToolExecutor` — 抽象基类，实现 `ToolExecutor` 接口，提供 `toolSpec` 管理
- `AgentToolExecutor` — agent 域执行器
- `BrowserTabToolExecutor` — tab/driver 域执行器（页面交互、导航等）
- `BrowserToolExecutor` — browser 域执行器（标签页管理）
- `FileSystemToolExecutor` — fs 域执行器
- `ShellToolExecutor` — shell 域执行器
- `SystemToolExecutor` — system 域执行器
- `WebDriverExToolExecutor` — WebDriver 扩展执行器

### 执行器调度

- `BasicToolCallExecutor`
  - 根据 `targetClass` 选择合适的 `ToolExecutor`，并调用其 `callFunctionOn(tc, target)`

### 自定义工具扩展

- `CustomToolRegistry`
  - 注册/注销自定义 domain 的 executor
  - 缓存 prompt 可见的 tool specs（来自 `ToolCallSpecificationProvider` 或手动注入）

> 注意：自定义工具需要同时”注册 executor”（`CustomToolRegistry`）和”绑定 target”（`AgentToolManager.registerCustomTarget`）。

### 示例 (`examples/`)

- `CalculatorToolExecutor` — 示例自定义工具，提供基本的加/减/乘/除功能，作为创建自定义 domain 工具的模板

### 工具类 (`util/`)

- `ActionValidator` — 动作执行前验证：URL 安全、选择器语法、参数合法性、安全策略，含验证缓存
