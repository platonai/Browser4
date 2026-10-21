# 实现任务：AgentToolCallLoop 自动压缩机制（由 b4 代理执行）

依据设计文档：`docs-dev/copilot/tool-loop-compression-design.md`（先 coding.read 全文）。
目标模块：`browser4-agentic`。范围约束：只允许修改以下文件，禁止 git add/commit/push：
- `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/chat/AgentToolCallLoop.kt`
- `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/chat/ToolLoopCompressor.kt`（新增）
- `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/action/ContextToAction.kt`（只加 3 个配置读取与构造器传参）
- `browser4-agentic/src/test/kotlin/ai/platon/pulsar/agentic/inference/chat/ToolLoopCompressorTest.kt`（新增）

## 要求

1. 新增 `ToolLoopCompressor` 类（放 `ai.platon.pulsar.agentic.inference.chat` 包）：
   - 构造参数：`enabled: Boolean`、`thresholdTokens: Int`、`keepRecentRounds: Int`；
   - `fun compressIfNeeded(messages: MutableList<dev.langchain4j.data.message.ChatMessage>): Boolean`；
   - 轮次切分：1 个含 toolExecutionRequests 的 AiMessage + 其后到下一个 AiMessage 之前的所有 ToolExecutionResultMessage；
   - 本地估算：`text.length / 3.5` 取整，所有消息文本计入（SystemMessage/AiMessage/UserMessage/ToolExecutionResultMessage）；
   - 超过阈值且轮次数 > keepRecentRounds 时：保留最近 keepRecentRounds 轮原样，更早轮次整体替换为一条 UserMessage，
     每轮一行 `🔁 [compressed round N] domain.method [ok|fail] <该轮第一条 result 首行>`；
     无 result 的轮次用 `[call made, no result]`；随后追加
     `[context compaction] 压缩了 X 轮早期工具调用（保留最近 K 轮），估计节省 Y tokens。不要重复执行已完成的工具，基于保留的最新结果继续。`；
   - system 与初始 user 消息永不删除/修改；低于阈值或轮次数不足时不压缩返回 false。
2. `AgentToolCallLoop`：新增构造参数 `compressor: ToolLoopCompressor`（默认 enabled 全开），
   在每轮构造 ChatRequest 之前调用 `compressor.compressIfNeeded(messages)`；压缩前后各打一条 DEBUG 日志。
3. `ContextToAction`：按 `toolLoopMaxIterations` 同款方式从 conf 读取三个配置
   （`browser4.agent.toolLoop.compressionEnabled` 默认 true、`browser4.agent.toolLoop.compressionThresholdTokens` 默认 40000、
   `browser4.agent.toolLoop.keepRecentRounds` 默认 3），传入 AgentToolCallLoop 构造器。
4. `ToolLoopCompressorTest`（JUnit5 + kotlin-test-junit5，方法 camelCase + @DisplayName）：
   构造合成消息列表覆盖设计文档 §4 的 6 个用例（不依赖 LLM/后端）。
5. 验证：`mvn -pl browser4-agentic -am test -Dtest=ToolLoopCompressorTest -Dsurefire.failIfNoSpecifiedTests=false` 全绿，
   并运行 `mvn -pl browser4-agentic -am test -Dtest=RequestTokenLimiterTest -Dsurefire.failIfNoSpecifiedTests=false` 确认回归通过。

完成后输出完成 JSON，gates 锚定实测：compile、ToolLoopCompressorTest 用例数、RequestTokenLimiterTest 用例数。
