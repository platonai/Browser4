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

- `ToolMount`
  - 插件挂载点接口，供外部模块注册自定义 `ToolExecutor`
  - 实现 `getToolExecutors()` 返回 executor 列表，由 `PluginManager` 自动发现并注册到 `CustomToolRegistry`

### 工具规范与 prompt 输出 (`specs/`)

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

### 经验学习 (`experience/`)

渐进式经验记忆系统（PEM v2），从任务执行痕迹中学习，为后续任务提供知识加速。

- `ExperienceToolExecutor` — `experience` 域 MCP 工具执行器，提供 4 个工具：
  - `experience_save` — **Fast Learning**：保存执行痕迹 + 更新统计（~数毫秒）
  - `experience_query` — **Intent-Based 查询**：6 级回退链解析知识（domain,intent → domain,url → family,intent → category,intent → universal,intent → cold start）
  - `experience_list` — 按 domain/intent/status 列出已存储知识
  - `experience_deep_learn` — **Deep Learning**：运行分析工具、构建事实、晋升验证状态

- `ExperienceModels` — 数据模型：`TaskType`（12 种任务类型）、`SuccessCriteria`（默认成功条件）、`SelectorEntry`（带稳定性评分的 CSS 选择器）、`ActionStep`（单个操作步骤）、`ExecutionTrace`（完整执行痕迹）、查询/保存/Deep Learn/列表结果类型

- `ExperienceStats` — 聚合统计（从 `TraceRecord` 派生，持续更新）。按 `(domain, intent)` 键控，含置信度（按需计算，从不存储）

- `IntentModels` — 意图与故障分类：
  - `Intent` — 12 种用户意图（BUY, SEARCH, BOOK, LOGIN, CHECKOUT, EXTRACT, COMPARE, DOWNLOAD, READ, FILL_FORM, MONITOR, OTHER），每种带规范操作序列
  - `FailureCategory` — 12 类结构化故障（SELECTOR_DRIFT, VISUAL_DRIFT, NETWORK, AUTH_REQUIRED, OVERLAY_BLOCKED, TIMING, ANTI_BOT, LAZY_LOADING, AB_EXPERIMENT, UNEXPECTED_REDIRECT 等），每类含可恢复性标志和建议恢复动作
  - `VerificationStatus` — 知识验证流水线：HYPOTHESIS → CANDIDATE → VERIFIED（CONTESTED 触发重新验证）
  - `PromotionLevel` — 模式抽象层级：SITE → FAMILY → CATEGORY → UNIVERSAL

- `KnowledgeFacts` — 经验证不可变的 `(domain, intent)` 知识。含站点事实、页面事实、已验证选择器、交互提示、已知障碍、反模式

- `KnowledgeStore` — 文件持久化 YAML 知识库，原子写入（`.tmp` → `fsync` → rename）。三层分离布局：`traces/`（30 天 TTL）、`experience/`（可变统计）、`facts/`（仅已验证，不可变）

- `TraceRecord` — 原始执行记录（写入后不可变，30 天 TTL）。含操作步骤、最终页面状态、持续时间、错误信息

- `UrlNormalizer` — URL 规范化与模式匹配。两层策略：全局规则（去除无关查询参数、尾部斜杠、www. 前缀、片段）+ 模式匹配（正则通配符 + 特异性评分）

### 自定义工具扩展

- `CustomToolRegistry`
  - 注册/注销自定义 domain 的 executor
  - 缓存 prompt 可见的 tool specs（来自 `ToolCallSpecificationProvider` 或手动注入）

> 注意：自定义工具需要同时"注册 executor"（`CustomToolRegistry`）和"绑定 target"（`AgentToolManager.registerCustomTarget`）。

### 示例 (`examples/`)

- `CalculatorToolExecutor` — 示例自定义工具，提供基本的加/减/乘/除功能，作为创建自定义 domain 工具的模板

### 工具类 (`util/`)

- `ActionValidator` — 动作执行前验证：URL 安全、选择器语法、参数合法性、安全策略，含验证缓存
