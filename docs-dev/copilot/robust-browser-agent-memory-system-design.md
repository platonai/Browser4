# RobustBrowserAgent 通用智能体记忆系统设计

> 状态：实现中（M1 骨架 ✅ 已落地、M2 SQLite 检索 ✅ 已落地、M3 PEM 融合 ✅ 已落地，2026-08-24） · 范围：`browser4-agentic`（`RobustBrowserAgent` 及其执行引擎）
> 实现位置：`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/memory/`（组件清单见 §4，接线点见 §14）
> 参考：**deepseek-harness 记忆系统**（`D:\workspace\ds-harness\deepseek-harness`）——
> `packages/session/session-persistence-*`（持久化）、`packages/session-query/session-query*`（统一查询服务）、
> `.agents/notes/archived/{feature/2026-07-10-session-query-service,architecture/2026-07-23-unified-session-query-service}.md`、
> `.agents/notes/implemented/feature/2026-07-31-third-party-memory-mcp-examples.md`（外部记忆 MCP 桥接）。
> 本设计**先定义通用智能体记忆系统**（与领域知识无关的骨架），**再**在 §8 讨论与 PEM 经验系统的融合。
>
> 一句话：**把"记忆"定义成三层——事件日志（事实）→ 提炼知识（语义/程序）→ 外部记忆（可选），
> 以"统一查询服务 + 显式工具面 + 自动写路径"为骨架**，对齐 DSH 的会话查询服务拓扑。

## 1. deepseek-harness 记忆系统调研结论（参考基线）

### 1.1 DSH 的做法（四块）

| 块 | 实现 | 要点 |
|---|---|---|
| **会话持久化** | `session-persistence-jsonl` / `session-persistence-sqlite` | 每个会话的事件日志 append-only 落盘；检查点可落后于活跃日志，持久化不是权威源 |
| **统一查询服务** | `ctx.sessionQuery`（`session-query` 接口包 + `session-query-sqlite` 唯一后端） | 一个服务一个上下文键；精确操作（list/filter/read/trace）共享 live-preferred 语料库实现；仅 `searchSessions()`/`searchEvents()` 两个抽象方法由 SQLite 实现（FTS 派生索引） |
| **外部记忆 MCP** | `examples/mcp-memory/*.cordis.yml`（Memorix / MCP Reference Memory / Engram） | 通用 MCP 客户端桥接，默认关闭；DSH 只负责启动进程/桥接工具，账户/存储/embedding 归提供方；模型指导一句话："当用户要求记住时调用记忆写工具；当历史可能相关时搜索记忆" |
| **边界纪律** | 服务是"受信任基础设施，而非授权层"；**不添加隐式模型工具**；未来工具显式加作用域 | 有界读取（`readWindowMax` 默认 50、snippet 上限、分页上限）；返回数据过 structured-clone 边界；FTS 派生索引可丢弃、永不当权威源；索引对齐是带静止性保证的串行状态机 |

### 1.2 对 Browser4 的五条借鉴

1. **记忆 = 持久化事件日志 + 查询服务**，而不是"KV 缓存"。事实先落盘，检索后置。
2. **统一服务、单一入口**：精确读取与全文检索是同一逻辑语料库上的两个视图，不应拆成两套 API。
3. **live-preferred 解析**：内存活跃态优先，持久化回退；活跃读取绝不依赖持久化健康状态。
4. **派生索引可丢弃**：FTS 索引由日志对齐生成，损毁/过期只降级检索，不破坏事实。
5. **工具显式、有作用域**：记忆能力暴露给模型必须是显式工具 + 明确 scope，不做隐式注入的魔法（注入区只是"目录"）。

## 2. 现状盘点与缺口

### 2.1 Browser4 现有"记忆相关"设施（as-is）

| 设施 | 形式 | 机器可查？ | 缺什么 |
|---|---|---|---|
| `AgentHistory`/`stateHistory` | 内存 `AgentState` 列表（会话内） | 部分（JSON 可序列化） | 无统一持久化 schema；observe-act 引擎用、CLI 引擎不用 |
| `TranscriptPersister` | 人类可读文本日志 | **否** | 无结构、无检索价值 |
| `CliLoopTracer` | 三类 JSONL（cli-events / cli-tool-trace / page-timeline） | 是（但分散） | 只有 CLI 引擎有；无统一查询；无回读 |
| `CheckpointManager` | 检查点 | 部分 | 面向恢复，不面向检索 |
| PEM `KnowledgeStore`（experience_*） | YAML（traces/stats/facts/patterns） | 是（专用检索） | 领域特定（选择器/反模式），无通用事实检索；读写靠模型自觉 |
| Skill / SKILL.md | bundle 资源 | 是 | 静态，无动态沉淀 |

### 2.2 核心缺口（通用记忆系统要补的）

- **G1 · 无统一事件日志**：三种痕迹（stateHistory / transcript / cli JSONL）并存且 schema 各异，没有一个"完整发生了什么"的机器可读事实源。
- **G2 · 无查询服务**：历史不可搜索——模型不能问"上次跑 amazon 提取任务时用了什么步骤"，人也不能。
- **G3 · 无模型可见记忆工具**：`experience_*` 只覆盖领域知识，不覆盖会话事实。
- **G4 · 写路径靠自觉**：跨会话沉淀依赖模型记得调工具（PEM 的 Phase 1 局限）。
- **G5 · 无分层**：事实（L0）与提炼知识（L1）混为一谈，语义记忆无从生长。

## 3. 通用记忆分层模型（与领域无关）

```
┌─────────────────────────────────────────────────────────────┐
│  L0 事件日志（原始记忆 · 情景/事实）                          │
│  append-only MemoryEvent 流，机器可读、可检索、可回溯        │
│  生产者：两个执行引擎的观察点（统一写路径）                   │
├─────────────────────────────────────────────────────────────┤
│  L1 提炼记忆（语义/程序）                                    │
│  由 L0 异步提炼：任务摘要、模式、反模式、置信度               │
│  抽象接口 MemoryKnowledgeProvider（检索 + 沉淀）             │
│  首个实现 = PEM KnowledgeStore（§8 融合）                    │
├─────────────────────────────────────────────────────────────┤
│  L2 外部记忆（可选）                                         │
│  第三方记忆 MCP 服务器（DSH 式桥接，默认关闭）               │
└─────────────────────────────────────────────────────────────┘
         ▲                        ▲
         │ 统一查询门面             │ 统一写路径
   MemoryQueryService        AgentMemorySink
   （精确 + FTS + 跨层融合）      （事件 + 提炼 + 外部）
```

- **工作记忆**（会话内，上下文窗口）不是本分层的一部分，而是 L0/L1 的**渲染面**：对话内消息压缩（`ToolLoopCompressor`）已有，本设计增加 `system.note` 便签（§7.3）作为显式工作记忆写板。
- 分层职责：**L0 记录事实，L1 沉淀规律，L2 扩展边界**。上层检索不到时回退下层，全空才冷启动。

## 4. 总体架构

```
RobustBrowserAgent（两个引擎共用）
  │
  ├─ 观察点 ──▶ AgentMemorySink（统一写路径）
  │                │  MemoryEvent 规范化（schema v1）
  │                │  └─▶ AgentEventLog（append-only JSONL，L0 事实）
  │                │  └─▶ MemoryConsolidator（异步）──▶ L1 KnowledgeProvider（PEM）
  │                │  └─▶ 外部 MemoryBridge（可选，M4）
  │
  ├─ run 开始 ──▶ MemoryRecallService
  │                  │  MemoryQueryService.search(task)  → L0 命中
  │                  │  L1 Provider.query(...)           → 知识命中（§8）
  │                  ▼
  │              "## Memory" 注入区（≤ recallMaxChars，静态）
  │
  ├─ 每轮 generate ──▶ system.note 便签（尾消息替换注入）
  │
  └─ 模型可见工具面（显式、有作用域）
       memory.search / memory.read / memory.note / memory.forget
```

新增组件（全部在 `browser4-agentic/.../agentic/memory/`，纯逻辑、可单测）：

| 组件 | 职责 | 对齐 DSH |
|---|---|---|
| `AgentEventLog` | L0 事实存储：append-only JSONL（per agent，按 session 分片），原子追加 + 尾部截断恢复 | session-persistence-jsonl |
| `MemoryEvent`（sealed） | 统一事件 schema：TaskStarted/ToolExecuted/PageViewed/TextEmitted/Completed/Failed/NoteWritten | SessionEvent |
| `MemoryQueryService` | 统一查询门面：精确（listTasks/filterEvents/readEvent/traceTask）+ 全文（searchEvents/searchTasks）+ L1/L2 融合；live-preferred 解析 | SessionQueryService |
| `MemoryQueryIndex`（抽象）→ `MemoryQuerySqlite`（实现） | FTS 派生索引：串行对齐状态机、可丢弃、有界读取 | SessionQuerySqlite |
| `MemoryRecallService` | 注入区渲染：L0 命中 + L1 知识 + 用户偏好，预算裁剪 | 模型指导 + 注入 |
| `AgentMemorySink` | 统一写路径：观察点 → 事件 → 日志 → 异步提炼 → 外部 | 持久化 + 提炼 |
| `MemoryConsolidator` | L0 → L1 提炼调度（采样、限流、幂等） | —（PEM 融合 §8） |
| `MemoryToolExecutor`（domain=`memory`） | 模型可见工具：search/read/note/forget，显式 scope | 显式工具纪律 |
| `TaskScratchpad` | 工作记忆写板（KV + LRU + 尾消息渲染） | — |

> **关键决策 1**：引擎直接持有 `MemoryQueryService`/`AgentMemorySink`（Kotlin 直连），`memory.*` 工具面走 MCP 分发（`ToolMount` 注册）——**库给引擎、工具给模型**，同一实现两个入口，对齐 DSH"服务是受信基础设施，工具显式加作用域"。
> **关键决策 2**：FTS 具体后端**单独引入 SQLite**（`org.xerial:sqlite-jdbc`），与业务库（H2/WebDB）完全隔离：
> 索引是"可丢弃的派生物"，独立数据库文件 + 独立原生库，删库即重建，不碰任何业务数据；
> 同时与 DSH 参考实现（`session-query-sqlite`）保持同一后端，便于对照验证。接口抽象保持提供方无关（DSH 的 Service/Sqlite 分离模式）。

## 5. 事件模型与写路径（L0）

### 5.1 MemoryEvent schema（v1，JSON）

```kotlin
sealed class MemoryEvent {
    val seq: Long; val ts: Instant; val sessionId: String; val agentUuid: String
    data class TaskStarted(instruction: String, engine: String, urlCandidate: String?) : MemoryEvent()
    data class ToolExecuted(tool: String, argsBrief: String /*已脱敏*/, ok: Boolean,
                            resultBrief: String /*≤200 字符*/, durationMs: Long, callId: String) : MemoryEvent()
    data class PageViewed(url: String, title: String, viewType: String /*full|reference|diff*/, fingerprint: String) : MemoryEvent()
    data class TextEmitted(kind: String /*reasoning|report|nudge*/, textBrief: String /*≤300*/) : MemoryEvent()
    data class NoteWritten(key: String, valueBrief: String) : MemoryEvent()
    data class Completed(summary: String, keyFindings: List<String>?, filesChanged: List<String>?,
                        problems: List<String>?, outcome: String, durationMs: Long) : MemoryEvent()
    data class Failed(errorBrief: String, failureCategory: String?, step: Int) : MemoryEvent()
}
```

### 5.2 生产者映射（两个引擎同一 schema）

| 观察点 | observe-act 引擎（弃用，兼容） | CLI 引擎（默认） |
|---|---|---|
| 任务开始 | `run()` 入口 | `doRunCliAgentLoop` 入口（已有 `cliLoopTracer.logEvent("run.start")`） |
| 工具执行 | `AgentStateManager` 每步 state | `CliLoopTracer.logTool`（**已有** `cli-tool-trace.jsonl`，仅需按新 schema 规范化/缓冲） |
| 页面视图 | `BrowserUseState` 更新 | `CliLoopTracer.logPageView`（**已有** `page-timeline.jsonl`） |
| 完成 | `onTaskCompletion` | `completeCliRun`（已有 `run.complete` 事件） |
| 失败/中止 | 异常路径 | `run.cancelled`/异常路径 |

- **迁移策略**：不推翻 `CliLoopTracer`/`TranscriptPersister`，`AgentEventLog` 作为**新的事实源**；CLI 引擎把 `logTool/logPageView/logEvent` 的载荷同步转发给 `AgentMemorySink`（同一数据双写：tracer 给调试、event log 给记忆），观察期后再考虑合并。`TranscriptPersister` 保持人类可读日志定位，不参与检索。
- **原子性**：单行 JSON append + `fsync` 批量；写失败仅告警（记忆不能阻塞任务主路径）。
- **TTL/容量**：原始事件默认保留 30 天（可配置），滚动清理归档到 `.archive/`（对齐 PEM 的归档语义）。

## 6. 查询服务设计（核心）

### 6.1 接口（对齐 DSH：精确 + 搜索统一在一个服务）

```kotlin
interface MemoryQueryService {
    // ── 精确操作（live-preferred，共享语料库实现）──
    suspend fun listTasks(agentUuid: String?, limit: Int): List<TaskRecord>
    suspend fun filterTasks(filters: TaskFilters): List<TaskRecord>          // 按 outcome/engine/url/时间
    suspend fun listEvents(taskId: String, cursor: Long?, limit: Int): List<MemoryEvent>
    suspend fun readEvent(taskId: String, seq: Long, before: Int = 0, after: Int = 0): EventWindow  // 有界
    suspend fun traceTask(taskId: String): TaskTrace                          // 事件血缘链

    // ── 全文搜索（具体后端实现：SQLite FTS5 派生索引）──
    suspend fun searchEvents(query: String, filters: SearchFilters, cursor: Cursor?): SearchPage
    suspend fun searchTasks(query: String, filters: SearchFilters, cursor: Cursor?): SearchPage

    // ── 跨层融合（§8）──
    suspend fun recall(task: String, scope: MemoryScope): RecallResult       // L0 + L1 + L2 合并
}
```

### 6.2 live-preferred 解析（对齐 DSH SessionCorpus）

- 查询时先读内存态（`AgentHistory`/`CliLoopTracer` 缓冲/`AgentMemorySink` 内存缓冲），再回退持久化事件日志；活跃任务永不因持久化故障不可读。
- 返回数据一律过**不可变拷贝边界**（Kotlin `data class` 拷贝/深拷贝），调用方不可能污染权威状态。

### 6.3 FTS 派生索引（MemoryQuerySqlite）

- 依赖：**新增 `org.xerial:sqlite-jdbc`**（版本进 `browser4-dependencies` BOM 统一管理，遵循仓库"版本跟随父 BOM、不随意改动"约定）；jar 自带各平台原生库（Windows/Linux/macOS），runtime bundle 无需额外打包步骤。
- 结构：独立 SQLite 文件库（`memory-index.sqlite`），`EVENTS(task_id, seq, ts, tool, text, outcome)` 表 + **FTS5 虚拟表**（`CREATE VIRTUAL TABLE events_fts USING fts5(text, ...)`）；`text` 为事件的检索投影（tool + argsBrief + resultBrief + summary 拼接），`content_hash` 列用于增量对齐。
- **对齐状态机**：串行消费 `AgentEventLog` 的追加流（记录已索引水位 `watermark_seq`）；索引损毁 → 全量重建；**索引永远可丢弃，日志是权威**。
- **有界读取**：`readWindowMax`（默认 50 前后事件）、snippet 上限（默认 200 字符）、分页上限（默认 50/页）——全部进配置。
- 同步 SQL 不可抢占，状态机在每次等待前后检查取消（对齐 DSH 的静止性保证）。

### 6.4 作用域（Security Boundary）

- `MemoryScope(agentUuid?, userId?, workspaceId?)`：查询/工具默认限定当前 agent + 当前用户；跨作用域检索必须显式声明（`memory.search(..., scope="global")` 需配置放行）。
- 服务是受信基础设施：**不在引擎内做隐式注入全文内容**，模型只能通过显式工具取全文（注入区只有摘要目录）。

## 7. 模型可见面

### 7.1 工具面（domain=`memory`，MCP 注册，对齐"显式工具"纪律）

| 工具 | 参数 | 返回 | 用途 |
|---|---|---|---|
| `memory.search` | `query`, `scope?`, `limit?` | 命中列表（taskId、ts、snippet、tier L0/L1/L2） | 任务中主动检索历史 |
| `memory.read` | `taskId`, `seq?`, `before?`, `after?` | 事件窗口（有界） | 取全文 |
| `memory.note` | `key`, `value` | 确认 | 工作记忆写板（§7.3） |
| `memory.forget` | `taskId?`, `scope?` | 确认 | 显式遗忘（隐私/纠错） |

### 7.2 注入区（run 开始，`MemoryRecallService`）

```
## Memory（自动召回；仅供参考，使用前验证）

[L0 事实] 上次任务（2026-08-23 14:02, amazon.com, EXTRACT, 成功）: 用 htmlsnapshot get 提取 #productTitle
[L1 知识] 置信度 0.87（P2）主选择器 #productTitle / 已知障碍 ANTI_BOT → 加随机延迟
[用户] 输出用中文（§8 后才有）
（无历史 → 一行 "冷启动，无相关记忆"）
```

- 预算 `recallMaxChars`（默认 2K 字符），运行期静态（不破坏 KV-cache 前缀复用）。
- **注入区是目录不是全文**：模型要细节就调 `memory.read`——对齐 DSH"不隐式注入、工具显式取"。

### 7.3 工作记忆写板（TaskScratchpad）

- `system.note(key, value)` 写、每轮 generate 前以**尾消息替换**方式注入（所有更早消息前缀不变，KV 复用不受影响，压缩器不碰尾消息）。
- 预算 `scratchpadMaxChars`（默认 1.5K），LRU 裁剪；`NoteWritten` 事件进 L0（跨会话可检索"我当时记了什么"）。

### 7.4 Prompt 指导（对齐 DSH 一句话）

> 当用户要求你记住某事时，调用 `memory.note`。当历史信息可能相关时，调用 `memory.search` 并在回答中使用相关结果。

## 8. PEM 经验系统的融合（先通用、后融合）

### 8.1 定位

**PEM = 通用记忆系统 L1 层的首个领域实现**。通用骨架不依赖 PEM；PEM 通过 `MemoryKnowledgeProvider` 接口挂进来，领域特定知识（选择器、反模式、置信度、URL 模式）全部留在 PEM 内部，通用层只见"检索 + 沉淀"两个方法。

```kotlin
interface MemoryKnowledgeProvider {
    suspend fun query(task: String, url: String?, scope: MemoryScope): KnowledgeHits   // 检索
    suspend fun deposit(trace: TaskTrace, outcome: String, scope: MemoryScope)         // 沉淀（异步）
}
// 实现：PemKnowledgeProvider —— 内部直连 KnowledgeStore（Intent/FailureCategory/UrlNormalizer 复用），
// 不经 MCP 分发层（引擎调库，模型才走工具）。
```

### 8.2 读融合（RecallResult 合并）

- `MemoryQueryService.recall()` = L0 事件命中（searchEvents 取 top-K）+ L1 `Provider.query`（PEM 六层回退原样生效）+ L2（可选）。
- 注入区按 tier 分节渲染（§7.2 示例）；tier 标注让模型知道"事实"与"知识"的置信度差别。
- **互不遮蔽**：L1 命中不抑制 L0 命中（PEM 只覆盖它擅长的领域，事实检索永远可用）。

### 8.3 写融合（自动沉淀，治 PEM 的"Phase 1 靠自觉"）

- 任务结束（`completeCliRun`/`onTaskCompletion`/失败路径）→ `AgentMemorySink` 写 `Completed/Failed` 事件（L0）→ `MemoryConsolidator` 异步把该任务的 `TaskTrace` 交给 `PemKnowledgeProvider.deposit`：
  - 成功 → `saveTrace + updateStats`（选择器统计、置信度）；
  - 失败 → `saveTrace(outcome=failure)` + `FailureCategory.classify`；
  - 采样提升 → 沿用 PEM 的 `promoteToVerified` 门槛（confidence ≥ 0.85 且 successes ≥ 5 → VERIFIED），限流（每 (domain,intent) 60 分钟一次，confidence ≥ 0.90 跳过）。
- **幂等**：按 sessionId 去重（同任务只沉淀一次）；CancellationException 不沉淀（用户取消 ≠ 失败样本）。
- 沉淀失败仅日志——L0 事件已落盘，L1 可稍后重试重建（L0 → L1 单向可重放，这是分层的最大红利）。

### 8.4 不改 PEM 本体

- `KnowledgeStore`/`Intent`/`FailureCategory`/`UrlNormalizer`/`ExperienceStats` 零改动；
- `experience_*` MCP 工具保留（模型深查/补录/诊断），但不再是写路径的依赖；
- `docs/experience-memory.md` 增加一节"作为通用记忆系统 L1 层"的关系说明；`browser4-experience` SKILL 更新"引擎已自动沉淀"。

### 8.5 融合收益

| 能力 | 之前（PEM 独立） | 之后（融合） |
|---|---|---|
| 沉淀 | 模型自觉调 `experience_save` | 引擎自动（L0 事件 → 异步提炼） |
| 召回 | 模型自觉调 `experience_query` | run 开始自动注入 + 模型可深查 |
| 事实检索 | 无 | `memory.search` 全文检索会话历史 |
| 可重放 | 知识丢失即永久丢失 | L0 日志在，L1 可重建 |
| 用户偏好 | 无 | AgentProfile（M3，经统一 recall 注入） |

## 9. 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `browser4.agent.memory.enabled` | `true` | 总开关；关闭 = 现状行为 |
| `browser4.agent.memory.log.dir` | `<APP_DATA>/memory/` | L0 事件日志根目录 |
| `browser4.agent.memory.log.ttlDays` | `30` | 原始事件保留期（归档到 .archive） |
| `browser4.agent.memory.index.backend` | `sqlite` | FTS 后端（`none` 禁用检索只留日志） |
| `browser4.agent.memory.index.path` | `<APP_DATA>/memory/memory-index.sqlite` | 派生索引（可删，重建） |
| `browser4.agent.memory.recallMaxChars` | `2000` | 注入区上限 |
| `browser4.agent.memory.readWindowMax` | `50` | 事件窗口前后限 |
| `browser4.agent.memory.snippetChars` | `200` | 检索片段上限 |
| `browser4.agent.memory.scratchpadMaxChars` | `1500` | 便签上限 |
| `browser4.agent.memory.autoRecall` | `true` | run 开始自动召回 |
| `browser4.agent.memory.consolidation.enabled` | `true` | L0→L1 提炼（PEM 融合） |
| `browser4.agent.memory.consolidation.minIntervalMinutes` | `60` | 每 (domain,intent) 提炼间隔 |
| `browser4.agent.memory.external.enabled` | `false` | L2 外部记忆 MCP 桥接（M4） |
| `browser4.agent.memory.external.transport` | `stdio` | 桥接传输（`http` 预留） |
| `browser4.agent.memory.external.command` | 无 | stdio 命令（第三方记忆服务器，如 `npx -y ...`） |
| `browser4.agent.memory.external.timeoutMs` | `30000` | 连接 + 工具发现握手上限 |
| `browser4.agent.memory.external.toolPrefix` | `mem` | 发现工具注册的域前缀 |
| `browser4.agent.memory.external.toolAllowlist` | 空 | 工具白名单（空 = 全部暴露） |
| `browser4.agent.memory.external.enabled` | `false` | L2 外部记忆 MCP 桥接（M4） |

## 10. 安全与隐私

1. **事件脱敏在写路径**：`argsBrief/resultBrief/valueBrief` 构造时对 `password/token/secret/cookie/authorization/api_key` 打码（`Sanitizer`，单测覆盖）；与 `CliProcessManager` env 白名单互为纵深。
2. **作用域即边界**：查询/工具默认当前 agent + 用户；`scope=global` 需配置放行；`memory.forget` 提供显式遗忘。
3. **索引可弃**：FTS 索引文件删除即"失忆检索"，日志不受影响——隐私合规场景可只删索引。
4. **不隐式注入**：模型只能通过显式工具读全文；注入区只有摘要。
5. **取消不写失败样本**；`enabled=false` 完全恢复现状。

## 11. 验收标准

### 单测

| 测试 | 断言 |
|---|---|
| `AgentEventLogTest` | 追加原子性；尾部截断恢复（模拟崩溃）；TTL 归档 |
| `MemoryQueryServiceTest` | live-preferred（内存有→不落盘）；精确 read 有界；filter/trace 正确性 |
| `MemoryQuerySqliteTest` | 对齐状态机水位推进；FTS5 检索正确性；索引文件删除后重建；snippet/分页上限；取消传播 |
| `MemoryToolExecutorTest` | search/read/note/forget 参数校验、scope 默认与放行、脱敏 |
| `MemoryRecallServiceTest` | 注入区渲染/裁剪/冷启动占位；tier 标注 |
| `PemKnowledgeProviderTest`（融合） | deposit 幂等（sessionId）；failure 分类；promote 门槛复用；confidence≥0.90 跳过 |

### e2e（CLI 引擎）

- **两轮任务对比**：第一轮 amazon 提取任务完成 → 第二轮同任务：system prompt 含 `## Memory`（L0 命中或 L1 知识），**步骤数 ≤ 第一轮**；
- **跨任务检索**：新任务中模型调 `memory.search("amazon")` 返回上一轮事件命中，并能 `memory.read` 取到细节；
- **便签**：模型写 `system.note` 后，后续轮次注入区/尾消息可见，压缩后仍在；
- **失败沉淀**：构造选择器失败 → L0 `Failed` 事件落盘 + L1 failure trace 可见；
- **回归**：`memory.enabled=false` 行为与现状一致；既有 experience/PEM 测试全绿。

## 12. 里程碑与风险

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M1（骨架）** | `MemoryEvent` schema + `AgentEventLog` + `AgentMemorySink`（CLI 引擎观察点挂载）；`MemoryQueryService` 精确操作 + live-preferred；`memory.search/read/note` 工具 + 注入区（仅 L0） | §11 单测 + e2e 两轮对比（L0 生效） |
| **M2（检索）** | `MemoryQuerySqlite`（sqlite-jdbc + FTS5）+ 对齐状态机 + 有界读取 + `memory.forget` | 索引单测；跨任务检索 e2e |
| **M3（PEM 融合）** | `MemoryKnowledgeProvider` 接口 + `PemKnowledgeProvider`；`MemoryConsolidator` 自动沉淀；注入区 L0+L1 融合渲染；AgentProfile（用户偏好，轻量） | §8 验收；融合注入区 e2e |
| **M4（已完成 2026-08-26）** | L2 外部记忆 MCP 桥接：`MemoryExternalBridge`（MCP 客户端，stdio 子进程传输 + 可注入传输工厂；`kotlin-sdk-client-jvm` 升为编译期依赖）+ `MemoryExternalToolExecutor`（动态规格 + 参数校验）+ `MemoryExternalConfiguration`（Spring ToolMount，MCP 面）+ 引擎侧注册（发现握手后有界等待 → CustomToolRegistry + 每 agent 目标）；默认关闭。测试：进程内 pipe fixture MCP 服务器，走完整协议（initialize → tools/list → tools/call）。observe-act 引擎事件映射未做（该引擎已弃用） | 桥接单测全绿；全套记忆测试不回归 |

### 风险

| 风险 | 缓解 |
|---|---|
| 事件日志增长（30 天 × 多任务） | TTL + 滚动归档；索引只建可检索投影，原始 JSON 压缩存储 |
| 双写开销（tracer + event log） | 观察期后评估合并；写入异步、失败仅告警 |
| SQLite 原生库/新依赖引入 | 版本进 `browser4-dependencies` BOM 统一管理；sqlite-jdbc 自带三平台原生库；索引可弃重建；`index.backend=none` 降级为纯日志 |
| 注入区/工具占用上下文 | 硬上限 + 空区不注入 + 显式工具按需取 |
| PEM 融合复杂度 | 接口薄（2 方法）+ PEM 本体零改动 + L0→L1 单向可重放 |
| 索引对齐与取消 | 串行状态机 + 静止性保证（对齐 DSH） |

## 14. 实现状态与文件清单（2026-08-24 落地）

**M1（骨架）+ M2（SQLite 检索）+ M3（PEM 融合）已实现**，`browser4-agentic` 编译通过，
新增 9 个测试类 25 个用例全绿，相关既有测试（RobustBrowserAgentTest / AgentToolManagerTest /
ToolSpecificationTest / ExperienceToolExecutorTest / CompactionLedgerTest 等）无回归，
`browser4-rest` 编译通过。

| 文件（`browser4-agentic/.../agentic/memory/`） | 对应设计 |
|---|---|
| `MemoryConfig.kt` | §9 配置项（系统属性读取） |
| `MemoryEvent.kt` | §5.1 事件 schema（Jackson 多态 JSON） |
| `Sanitizer.kt` | §10 写路径脱敏 |
| `MemoryKeywords.kt` | 停用词 + FTS 表达式（AND→OR 语义修正，实现期新增） |
| `EventBuffer.kt` | §6.2 live 缓冲（有界、removeTask） |
| `AgentEventLog.kt` | §5 L0 append-only JSONL（尾行恢复、TTL 归档、deleteTask） |
| `AgentMemorySink.kt` | §5.2 统一写路径（JVM 全局 seq，多 agent 共享后端不错序） |
| `MemoryQueryService.kt` / `DefaultMemoryQueryService.kt` | §6 查询门面（live-preferred、精确 + 检索、兜底扫描） |
| `MemoryQueryIndex.kt` / `SqliteMemoryQueryIndex.kt` | §6.3 FTS5 派生索引（per-agent 水位、`IS COALESCE` 过滤、可重建） |
| `TaskScratchpad.kt` | §7.3 工作记忆写板（LRU 预算） |
| `MemoryRecallService.kt` | §5.3 注入区渲染（L0 + L1 融合） |
| `MemoryKnowledgeProvider.kt` / `PemKnowledgeProvider.kt` | §8.1/8.3 L1 接口 + PEM 实现（deposit 幂等、promote 采样） |
| `MemoryConsolidator.kt` | §8.3 后台合并（有界队列、幂等） |
| `AgentMemory.kt` | §4 门面（scope/rootDir/knowledgeDir 可注入，close 释放） |
| `MemoryToolTarget.kt` / `MemoryToolExecutor.kt` | §7.1 `memory.search/read/note/forget` 工具面 |
| `browser4-rest/.../config/MemoryToolMountConfiguration.kt` | §4 工具注册（ToolMount → CustomToolRegistry） |

**RobustBrowserAgent 接线**（`agents/RobustBrowserAgent.kt`）：`agentMemory` 惰性门面 +
`memory` 域进 CLI 引擎域集合（`CLI_ENGINE_DOMAINS`/`CLI_CORE_DOMAINS`）+
`doRunCliAgentLoop` 观察点（taskStarted / onToolResult→ToolExecuted /
onToolDecorated→PageViewed / 停止与异常→Failed）+ `completeCliRun`→Completed +
合并调度 + 每轮便签尾消息注入 + system prompt 记忆指导行。

**实现期修正（相对设计稿）**：
1. FTS5 表增加 `seq UNINDEXED` 列——`upsert` 按全局唯一 seq 精确删除，避免"按 task 删除清空同任务其它事件"；
2. 过滤条件用 `col IS COALESCE(?, col)`（SQLite `IS` 语义），NULL 列在无过滤查询时不再被 `= NULL` 误杀；
3. 关键词统一走 `MemoryKeywords`（停用词 + OR 语义），修复 "extract the amazon price" 这类含停用词查询的假阴性；
4. `AgentMemorySink.seq` 改 JVM 全局递增——多 agent 共享后端时 FTS 增量对齐按水位不错序。

**第二轮修正（2026-08-26，继续实现）**：
5. **时序 bug**：`doRunCliAgentLoop` 原在 `buildCliToolLoop` 之后才触碰 `agentMemory` 惰性值——
   memory.* 工具规格注册进 CustomToolRegistry 发生在工具集快照之后，首轮工具注册表缺失 memory 域。
   已把 `agentMemory.currentTaskId = taskId` 移到 `buildCliToolLoop` 之前（首轮即注册）。
6. **新增测试**：`AgentMemoryTwoRunIntegrationTest`（引擎级两轮闭环：run1 事件落盘 + 合并器自动沉淀 →
   run2 召回区同时含 `[L0]` 事实与 `[L1]` 知识 + 置信度；失败任务沉淀 failure trace；便签经
   `memory.note` 写入后可检索）+ `MemoryToolMountConfigurationTest`（browser4-rest Spring 配置，
   `app.data.dir`/`knowledge.dir` 指向临时目录，不污染用户数据目录）。
7. `MemoryToolExecutor` 增加 `close()`（关闭 MCP 级共享后端，测试与生命周期释放 SQLite 锁）；
   `MemoryToolMountConfiguration` 尊重 `knowledge.dir` 系统属性。

**第三轮（M4 外部记忆，2026-08-26）**：
8. `MemoryExternalBridge`：MCP 客户端桥接（stdio 子进程 / 可注入传输工厂），连接 → `tools/list`
   发现 → `tools/call` 路由；`MemoryExternalToolExecutor` 动态暴露规格并校验参数；引擎侧
   （`RobustBrowserAgent.agentMemory`）等待发现握手（有界）后注册工具；Spring 侧
   `MemoryExternalConfiguration`（ToolMount）供 MCP 面使用；`kotlin-sdk-client-jvm` 升编译期依赖。
   测试用进程内 pipe fixture 服务器走完整 MCP 协议（对齐 Browser4MCPServerE2ETest 模式）。
9. 已知取舍：桥接为每 agent 一份连接（引擎侧）+ Spring 共享一份（MCP 面）；多 agent 并发场景
   未来可收敛为共享实例。外部服务器进程的生命周期由桥接负责（kill-on-close），账户/存储归提供方。

**第四轮（AgentProfile + 语义修正，2026-08-26）**：
10. **AgentProfile（设计 §9 补全）**：`profiles/<principal>.yaml` 轻量 YAML KV——
    显式偏好（语言切换，如"以后用中文输出"→ `language=zh`，保守无推断）+ 访问域名计数
    （`domain_count:<domain>`）；召回注入区附加"用户偏好"行；引擎钩子：任务开始记域名、
    完成总结提取显式偏好。8 个单测。
11. **searchEvents 语义修正（测试真实执行后暴露）**：事件级返回改为**每任务 rank 最优 1 条**
    （FTS 内存 `distinctBy` + naive 同语义）；outcome 过滤改**任务级**（失败任务的
    TaskStarted/ToolExecuted 事件本身无 outcome，事件级过滤会误杀）——FTS 用子查询、
    naive 用任务 outcome 映射。
12. **测试执行盲区修复**：`-Dtest=Memory*Test` 前缀模式不匹配 `Agent*`/`Default*`/`Sqlite*`/
    `PemKnowledge*` 开头的类——此前部分"全绿"是假象。统一改用 `-Dtest=*Memory*Test,AgentProfileTest,SanitizerTest`
    并逐个补齐验证；`testIncrementalSync` 修正为不假设 JVM 全局 seq 绝对值（只断言相对增量）。
13. **回归确认**：browser4-rest 关键套件（MCPToolControllerTest 71 用例等 10 个类）全绿；
    memory 套件 70 用例全绿。

**第五轮（文档同步 + 交互验证 + 配置可覆盖，2026-08-26）**：
14. **文档同步**：`skills/browser4-cli/SKILL.md` 新增 "Agent Memory (progressive)" 小节
    （`## Memory` 注入区 / `memory.note` 工作记忆 / `memory.search|read|forget` / 自动沉淀）；
    `cli/browser4-cli/src/tips.rs` 新增 TIPS_AGENT 记忆提示（Rust 测试通过）。
15. **便签 × 压缩器交互验证**（设计 §12 风险表闭环）：`ScratchpadCompressionInteractionTest`——
    压力压缩保留尾消息逐字（断言 checkpoint 吸收旧轮次而便签仍在末尾）；溢出压缩可能吸收
    尾消息，但引擎"每轮重新注入"语义保证恢复（replace-tail 永不依赖历史）。
16. **MemoryConfig 运行时可覆盖**：`object` 属性从"类加载固化"改为**惰性 getter**
    （每次读取实时求值系统属性）——修复测试无法注入配置的问题，同时支持运行时热切换；
    `SystemPropertyGuard` 测试助手（快照/恢复）+ `MemoryConfigIsolationTest`（3 用例）。

**第六轮（规模优化 + http 传输，2026-08-26）**：
17. **readSince 规模优化**：`AgentEventLog.readSince` 增加文件级快速跳过——只读每个事件文件
    尾部 4KB 解析最后一行 `"seq"`，文件最大 seq ≤ 水位则整文件跳过（append-only 文件 seq
    递增）；无法解析的尾部返回 MAX_VALUE 不跳过（交给 readFile 的崩溃恢复）。索引对齐在
    watermark 最新时从 O(全部事件) 降到 O(文件数 × 尾读)。同时索引同步优先用 `EventBuffer`
    增量（零文件 I/O），冷启动才回退日志扫描。
18. **M4 http 传输**：`MemoryExternalBridge` 支持 `transport=http`（`browser4.agent.memory.external.url`
    指定 MCP SSE 端点）——`HttpMemoryTransportFactory`（Ktor CIO + SSE 插件 +
    `mcpSseTransport` 扩展）；`ktor-client-cio-jvm` 升编译期依赖（SSE 插件类经
    `ktor-client-core` 传递可用）。测试：进程内 `McpHttpServer` fixture + 完整协议
    （initialize → tools/list → tools/call），发现工具名带服务端域前缀（Browser4MCPServer
    命名规则）。

**第七轮（引擎侧 TTL 归档 + 验收手册，2026-08-26）**：
19. **引擎侧归档缺口修复**：`AgentMemory` 构造时执行 `eventLog.archiveExpired()`（此前只有
    Spring 共享后端在启动时归档，每 agent 的引擎侧记忆从不清理——事件文件会无限增长）；
    `AgentMemoryArchiveTest` 验证过期文件被归档且查询不可见、新文件保留。
20. **布局 bug 修复**：`AgentMemory` 曾把 `rootDir.resolve("events")` 传给 `AgentEventLog`，
    而后者内部又 `resolve("events")`——实际落盘变成 `<root>/events/events/` 双重嵌套
    （单测因读写自洽而未暴露）。已改为传 `rootDir`，布局回归设计
    （`<root>/events/<agent>/<task>.jsonl`，归档 `<root>/.archive/...`）。
21. **§16 真实环境验收手册**：见下节（供有 LLM 密钥 + 运行中后端的 CI/用户执行）。

**第八轮（knowledge.dir 默认值 + 引擎接线测试，2026-08-26）**：
22. **knowledge.dir 默认值修复**：`AgentMemory` 的 PEM 知识目录解析改为"显式参数 → `knowledge.dir`
    系统属性 → PEM 默认（相对 cwd）"三级回退——引擎侧记忆不再无视 `knowledge.dir` 配置；
    Spring 共享后端配置同步简化（靠 AgentMemory 默认逻辑）。真实部署建议设置 `knowledge.dir`。
23. **引擎接线测试**：`RobustBrowserAgentMemoryWiringTest`——触碰 `agentMemory` 后断言
    CustomToolRegistry 注册 memory 域（stateless executor）+ per-agent dispatch target 绑定 +
    backend 可用；未触碰时零副作用（惰性验证）。`RobustBrowserAgent` 新增
    `agentMemoryRootDirOverride`（测试隔离 + 多租户可重定位记忆目录）。

**第九轮（外部工具参数语义修正，2026-08-26）**：
24. **可选参数 bug 修复**：`MemoryExternalToolExecutor` 原用 `validateArgs` 默认语义
    （required=allowed）→ 外部工具的可选参数被强制必填；且 `toToolSpec` 忽略
    inputSchema 的 `required` 列表。修复：`toToolSpec` 按 provider 的 required 列表设置
    `defaultValue`（null=必填、`""`=可选占位，对齐 ToolSpec 既有语义）；executor 校验的
    required 集由 `defaultValue == null` 推导。新增带可选参数的 fixture 工具（`memory_get`）
    与测试（只传必填成功 / 可选参数生效 / 缺必填失败）。

**第十轮（真实环境 e2e，2026-08-26）**：
25. **真实环境执行**（deepseek-v4-flash + Chrome + 运行中后端，§16 手册）：两轮同任务对比
    工具执行 **121 → 32（-73%）**，第二轮 system prompt 出现 `## Memory` 注入区（L0 命中 +
    用户偏好 + 目标 URL）；`memory_search`/`memory_read` 工具真实调用成功并命中历史任务；
    PEM 自动沉淀（`knowledge/traces/example.com/...yaml`）、事件日志（92KB JSONL）、
    SQLite 索引、用户偏好文件全部落盘。
26. **e2e 暴露并修复的三个真实缺陷**：
    a. **工具规格注册缺口**：`MemoryToolExecutor` 未实现 `ToolCallSpecificationProvider`——
      Spring 侧 `MemoryToolMountConfiguration` 抢先注册（无规格），引擎侧"已注册"守卫跳过 →
      memory.* 工具对模型不可见（`exposeTools` 报 unknown）。修复：executor 实现 provider。
    b. **注入区自引用**：`taskStarted` 事件先写入、recall 后执行 → 注入区命中当前任务自身的
      刚写入事件。修复：`recall(excludeTaskId)` 排除当前任务。
    c. **提示词工具名写法**：`memory.note`（点号）≠ 注册工具名 `memory_note`（下划线）→
      模型 exposeTools("memory.search") 失败。修复：系统提示词改下划线形式。
27. **e2e 观察**：b4.run 子进程在本环境报 "The module 'bin' could not be loaded"（CLI
    PowerShell 模块解析问题，与记忆系统无关；模型绕行 coding_shell 仍完成任务）；
    记忆按 agent uuid 隔离（后端重启 → 新 agent → 干净记忆）符合作用域设计。

**第十一轮（验收补全 + 收尾，2026-08-26）**：
28. **§16-D 失败沉淀结论**：失败路径（stop/异常 → Failed 事件 + 合并器沉淀）代码与成功路径
    对称，单测充分覆盖（PemKnowledgeProviderTest 失败分类、TwoRun 失败用例）；真实环境
    下**引擎级失败不可自然构造**（模型总能完成任务或调 taskComplete——stall/不可能域名
    任务均被模型"成功处理"），故失败沉淀以单测为准，真实环境仅验证了成功路径。
29. **§16-E 关闭开关回归通过**：`BROWSER4_SERVER_OPTS=-Dbrowser4.agent.memory.enabled=false`
    重启后端后：任务正常完成（Example Domain），memory 事件**零新增**、system prompt
    **无 `## Memory` 注入区**、工具规格 **0 个 memory 工具**——行为与实现前一致。
    （注：`BROWSER4_AGENT_MEMORY_ENABLED` 环境变量无效——`MemoryConfig` 读系统属性，
    需经 `BROWSER4_SERVER_OPTS` 注入 `-D` 参数。）
30. **后续工作已提 issue**：#580（L1 注入区观察 + 短任务 cli-prompt 缺失）、#581（桥接
    共享实例收敛）、#582（跨站点提升 E2E 验证）、#583（tracer 双写合并评估）、#584
    （规模/运维：月分片、AgentMetrics、管理端点）。

## 16. 真实环境验收手册（e2e，需 LLM 密钥 + 运行中后端）

引擎级闭环已由 `AgentMemoryTwoRunIntegrationTest` 覆盖（无 LLM）；以下验收在**真实环境**
（`DEEPSEEK_API_KEY` 或 `OPENROUTER_API_KEY` + 后端 `browser4` 启动 + Chrome）执行，
对应设计 §11 的 e2e 验收标准。

### 前置

```bash
export DEEPSEEK_API_KEY=...          # 或 OPENROUTER_API_KEY
./b4w.ps1 open --headless            # 确保后端就绪（或 browser4-cli 任意命令预热）
```

### A. 两轮任务对比（核心验收：步骤数下降）

```bash
# 第 1 轮（冷启动，无记忆）
browser4-cli agent run "打开 https://<fixture>/dp/1 提取标题与价格" --wait --wait-timeout=900
# 第 2 轮（同任务，应命中记忆）
browser4-cli agent run "打开 https://<fixture>/dp/1 提取标题与价格" --wait --wait-timeout=900
```

**预期**：
- 第 2 轮后端日志（`~/.browser4/logs/agent/<time>/<uuid>/cli-prompt/*.request.json`）
  的 system 消息含 `## Memory` 注入区，且 tier ≥ P2（0.60–0.84）或含 `[L0]` 命中；
- 第 2 轮工具执行数（`cli-tool-trace.jsonl` 行数）**≤ 第 1 轮**（选择器复用生效的量化信号）；
- 完成后 `~/.browser4/memory/events/<agent>/` 出现两个任务事件文件，
  `knowledge/traces/<domain>/` 出现 PEM trace。

### B. 跨任务检索（模型主动调 memory.search）

```bash
browser4-cli agent run "上次提取 amazon 价格用的什么方法？请用 memory.search 查一下再回答" --wait
```

**预期**：回答引用上一任务的工具/步骤（如 htmlsnapshot get），说明 `memory.search`/`memory.read`
在工具循环中可用。

### C. 便签跨轮保持（压缩后仍在）

给模型一个长任务并提示"把结论写入 memory.note"（或观察 system prompt 指导行），
检查每轮请求 JSON 的**最后一条 user 消息**含 `## Task Scratchpad`，且长任务压缩发生后仍在。

### D. 失败沉淀

构造一个必然失败的选择器任务（如提取不存在的元素），完成后检查：
`knowledge/experience/<domain>/<intent>.yaml` 的 `failures` +1，`traces/` 出现 outcome=failure
的 trace，`memory.search "失败|selector"` 能命中。

### E. 关闭开关回归

`-Dbrowser4.agent.memory.enabled=false` 重启后端后重复 A：无 `## Memory` 注入、
`~/.browser4/memory/` 不新增事件——行为与实现前一致。

## 13. 与既有文档的关系

| 文档 | 关系 |
|---|---|
| [robust-browser-agent-run-v2-design.md](robust-browser-agent-run-v2-design.md) | 宿主引擎；挂载点：`doRunCliAgentLoop`/`completeCliRun`/`onTaskCompletion`/`CliLoopTracer` |
| [compaction-traceability-design.md](compaction-traceability-design.md) | 工作记忆压缩；便签"尾消息替换"遵守其前缀不变原则 |
| [docs/experience-memory.md](../../docs/experience-memory.md) | PEM 本体，融合后定位为 L1 层实现（§8.4 补充关系说明） |
| [skills/browser4-experience/SKILL.md](../../skills/browser4-experience/SKILL.md) | 更新为"引擎已自动沉淀，模型无需自调 experience_save" |
| deepseek-harness 对应物 | §1.1 对齐表：session-persistence / SessionQueryService / MCP 记忆桥接 / 边界纪律 |
