# ISSUE-137: agent.observe(instruction: String) 工具集成方案计划

## 1. 背景与目标
- 目标：将 agent.observe(instruction: String): List<ObserveResult> 工具集成到 ToolCallExecutor，使其可通过字符串表达式和 ToolCall 对象调用。
- 支持两种调用方式：字符串指令和 ObserveOptions。

## 2. 架构设计
- 扩展 ToolCallExecutor，支持 agent 域工具调用。
- 新增 execute(toolCall: ToolCall, agent: PerceptiveAgent): Any? 方法。
- 参数映射：ToolCall.args 映射到 ObserveOptions。
- 递归防护：Schema 限制 method 只允许 driver/browser 域。

## 3. 详细实现步骤
1. ToolCallExecutor 新增 agent 域分发逻辑，支持 observe、act、extract。
2. 参数映射规则：instruction、modelName、returnAction、domSettleTimeoutMs、iframes、frameId。
3. 更新 TOOL_CALL_LIST，补充 agent.observe 签名。
4. 扩展 toolCallToExpression，支持 agent.observe 表达式生成。
5. 集成到 BrowserPerceptiveAgent 工具调用链路。
6. Schema 防止递归调用 agent.observe。

## 4. 测试策略
- ToolCallExecutorTest：新增 agent.observe 单元测试。
- PulsarPerceptiveAgentTest：集成测试，验证端到端调用。

## 5. 日志与文档
- 结构化日志记录工具调用链路。
- KDoc 注释，补充 rest-api-examples.md 示例。

## 6. 风险与缓解
- 递归调用风险：Schema 限制，调用深度计数。
- 参数解析失败：默认值/警告日志。

## 7. 验收标准
- 构建通过，测试覆盖，KDoc 完整，无日志风暴，向后兼容。

## 8. 估时与优先级
- 总估时约 7h，优先级：ToolCallExecutor > 测试 > 文档。

---
方案遵循最小改动、类型安全、测试驱动、日志可观测原则，符合 README-AI.md 要求。