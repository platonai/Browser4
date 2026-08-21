# 自动压缩机制设计：AgentToolCallLoop 上下文压缩

> 日期：2026-08-21 · 依据：browser4-linkcheck 监督轮实测（每步输入 token 220K-1.25M，工具循环内 O(N²) 累积重发）
> 目标：在不丢失"最近上下文"的前提下，把单步工具循环的输入 token 从 220K-1.25M 压到 ~60K-120K，且不改变模型行为语义。

## 1. 问题复述

`AgentToolCallLoop.generate()` 每轮把整个会话（system + 初始 user 消息 + 历史 + 之前所有轮次的 AiMessage 与 ToolExecutionResultMessage）完整重发；工具结果每条 ≤5000 字符，循环 N 轮后上下文 ≈ base + N×结果，计费为所有轮次之和 → O(N²)。实测：12 轮 ≈ 220K-250K 输入 token，40 轮 ≈ 1.25M。

## 2. 方案：循环内滚动压缩（rolling compaction）

新增独立可测试类 `ToolLoopCompressor`，只压缩"轮次"消息（AiMessage + 紧随其后的 ToolExecutionResultMessage 组），**永不触碰** system 消息、初始 user 消息（指令/历史/浏览器状态）。

### 2.1 数据结构

`messages: MutableList<ChatMessage>` 中，一轮 = 1 个含 toolExecutionRequests 的 AiMessage + 该轮对应的 0..N 个 ToolExecutionResultMessage（下一个 AiMessage 之前的所有 result 消息）。

### 2.2 算法

1. 从列表尾部向前扫描，按上述规则切分出"轮次"区间（最近的轮次在末尾）。
2. 若估计 token 总数 ≤ 阈值 → 不压缩，返回 false。
3. 保留最近 K 轮原样（AiMessage + 结果全文）；对更早的轮次，把整个区间替换为一条 UserMessage：
   - 每轮一行，格式：`🔁 [compressed round N] domain.method [ok|fail] <summary>`；
   - summary 取该轮第一条 ToolExecutionResultMessage 文本的首行（ToolOutcome 信封的 header 行，天然是一行摘要）；
   - 若该轮无 result 消息，行内容为 `domain.method [call made, no result]`。
4. 在压缩点之后、最近保留轮之前，追加一条 UserMessage 说明：
   `[context compaction] 压缩了 X 轮早期工具调用（保留最近 K 轮），估计节省 Y tokens。不要重复执行已完成的工具，基于保留的最新结果继续。`
5. 返回 true（发生了压缩）。

### 2.3 估计口径

独立本地估算器（不依赖外部 TokenEstimator）：`estimatedTokens = text.length / 3.5` 取整（英文为主代码/工具输出的保守近似），系统提示与工具规格按实际文本计入。后续可替换为 TokenEstimator 或模型真实 tokenizer。

### 2.4 配置（与 toolLoopMaxIterations 同源，读 conf）

新增三个键，均由 `ContextToAction` 从 conf 读取后传入 `AgentToolCallLoop` 构造器：

| 键 | 默认 | 说明 |
|---|---|---|
| `browser4.agent.toolLoop.compressionEnabled` | true | 总开关 |
| `browser4.agent.toolLoop.compressionThresholdTokens` | 40_000 | 估计 token 超过该值触发压缩 |
| `browser4.agent.toolLoop.keepRecentRounds` | 3 | 保留最近多少轮不压缩（1..10） |

## 3. 接线点

`AgentToolCallLoop.generate()` 每轮构造 ChatRequest 之前调用 `compressor.compressIfNeeded(messages)`；压缩后继续本轮请求。溢出路径不变（仍返回 modelError），但压缩后 40 轮内不再触顶 500K 单请求限流。

## 4. 测试（browser4-agentic 单元测试）

`ToolLoopCompressorTest`：
1. 低于阈值不压缩；
2. 超过阈值：早于最近 K 轮的轮次被折叠为一行摘要，最近 K 轮内容原样保留；
3. 压缩后估计 token 低于阈值；
4. 无 result 消息的轮次按 `[call made, no result]` 折叠；
5. 轮次数 ≤ keepRecentRounds 时不压缩；
6. system 与初始 user 消息不被压缩/删除。

## 5. 验证标准

- `mvn -pl browser4-agentic test -Dtest=ToolLoopCompressorTest,...` 全绿；
- 既有 `RequestTokenLimiterTest` 等回归全绿；
- 端到端：重跑一个多工具编码任务，单步输入 token 相比 220K-1.25M 显著下降（抽样 act/response.json 的 inputTokenCount 总和）。

## 6. 不做的事（本轮范围外）

- 不改变循环溢出时"丢弃内部会话"的语义（跨步续跑留待后续）；
- 不压缩外层 AgentHistory（已有 4000 字符预算）；
- 不改变工具结果单条截断（ToolOutcome 1600-3000 字符保持）。

---

## 7. 实现状态（2026-08-21 已落地）

已实现并验证（单元测试 + browser4-agentic 全量测试全绿）：

### 新增文件

- `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/chat/ToolLoopCompressor.kt`
  - 两阶段压缩：`pruneToolResults`（无模型裁剪）+ `compressIfNeeded`（压力触发区段压缩）；
  - `ToolLoopSummarizer` fun interface 解耦 LLM 摘要，便于测试注入；
  - 摘要替换为 `<compacted-summary>` 检查点消息（preamble + 标签），指令与 deepseek-harness 同构；
  - 切点恒为轮次边界（AiMessage + 其后 ToolExecutionResultMessage 组），对应其 tool-pairing balanced cut。
- `browser4-agentic/src/test/kotlin/ai/platon/pulsar/agentic/inference/chat/ToolLoopCompressorTest.kt`（10 用例）

### 修改文件

- `AgentToolCallLoop.kt`：新增 `compressor: ToolLoopCompressor? = null` 构造参数，每轮 ChatRequest 前执行
  prune → compressIfNeeded，压缩发生时打 INFO 日志。
- `ContextToAction.kt`：新增 7 个配置键并构造生产 summarizer（复用对话 toolSpecifications，
  指令作为最终 user 消息，`maxOutputTokens` 限定摘要长度）。

### 配置键与默认值

| 键 | 默认 | 说明 |
|---|---|---|
| `browser4.agent.toolLoop.compressionEnabled` | true | 总开关 |
| `browser4.agent.toolLoop.compressionThresholdTokens` | 60000 | 估计 token 压力阈值 |
| `browser4.agent.toolLoop.retainTokens` | 24000 | 保留尾部 verbatim 预算 |
| `browser4.agent.toolLoop.pruneThresholdChars` | 1500 | 单条结果裁剪阈值（字符） |
| `browser4.agent.toolLoop.pruneHeadChars` | 800 | 保留头部字符 |
| `browser4.agent.toolLoop.pruneTailChars` | 400 | 保留尾部字符 |
| `browser4.agent.toolLoop.summarizationMaxTokens` | 2048 | 摘要输出上限 |

### 与 deepseek-harness 的差异

- 压缩点从"整个会话/步骤间"改为"单步工具循环内"（browser4 的 token 燃烧发生在循环内）；
- 无 session surface/事件日志基础设施，直接操作 LangChain4j `ChatMessage` 列表，轮次切分即配对平衡；
- 裁剪默认值按循环内结果规模缩放（deepseek 8192/4096/1024 → 1500/800/400）；
- 摘要调用为同步 `langChainChat`（复用同一模型与工具规格，保留其 KV-cache 前缀复用意图）。
