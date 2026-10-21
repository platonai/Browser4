# 网页任务上下文优化设计：页面内容去重 / 差异视图 / 会话级折叠

> 日期：2026-08-21 · 前置依赖：[tool-loop-compression-design.md](tool-loop-compression-design.md)（已落地）
> 问题：网页任务中循环反复查看页面内容（`ariaSnapshot` / `snapshot` / `dump` / `htmlsnapshot` / `extract`），
> 大页面每次快照数 KB-数十 KB，多轮累积 + 每轮整体重发 → prompt 膨胀、token 浪费。
> 目标：同一页面内容**最多完整进入会话一次**；再次查看时只给"未变化"引用或**差异**；不破坏 KV-cache 前缀复用。

## 1. 问题量化

现状（浏览器智能体典型 observe/act 步）：

- 每步 1 次 `ariaSnapshot`（30-60KB ≈ 8.5-17K token）常被反复调用：动作前后、异常重试、多工具链中都可能再查；
- `AgentToolCallLoop.generate()` 每轮**整体重发**全部历史（O(N²) 计费），页面内容随轮次重复计费；
- 单条结果已有两道截断（`ToolExecutionCoordinator` 5000 字符、`ToolLoopCompressor` 1500 字符 head/tail 裁剪），
  但截断**丢中间信息**，且不解决"同一份内容出现多次"的浪费；
- `ToolLoopCompressor` 只在估计 token > 60K 时把**旧轮次整段**折叠为摘要——对网页任务，被折叠的往往是"页面状态"
  这一最关键的上下文，且折叠后若模型再要查看页面，又会产生一份新快照。

实测参考（browser4-linkcheck 监督轮）：单步输入 token 220K-1.25M，其中页面快照重复内容占大头。

## 2. 设计原则

1. **追加时去重，历史只写不改**：重复判定在 `messages.add()` 之前完成；一旦消息发出，其内容不再被改写，
   保持 provider KV-cache 前缀复用（与现有压缩设计一致）。
2. **首份完整、后续引用**：同一内容的首次出现保留全文；后续重复只放一条**自足引用**（含摘要），模型无需"回忆"原文也能推理。
3. **引用必须自足**：引用带 digest（标题/首行/指纹），即使原文所在轮次后续被压缩掉，引用仍可独立理解。
4. **最新页面状态始终 verbatim 保留**：压缩可以折叠旧轮次，但绝不能折叠"最近一次完整页面视图"。
5. **可配置、可观测、可回退**：所有行为走配置开关；统计每次去重/差异节省的 token；`refresh=true` 参数强制重取。

## 3. 方案总览（四层）

```
┌─ Layer 4  源端瘦身（可选）：快照默认 compact 输出，全量按需
├─ Layer 3  页面状态缓存与差异视图（PageViewDeduper）★核心
│            - 指纹：同一 URL 内容未变 → 引用（不重发全文）
│            - 内容变化但重叠大 → 差异（diff vs 上次视图）
│            - 变化大 / 显式要求 → 全文
├─ Layer 2  会话级重复折叠（通用，不限页面工具）
│            - 任意工具结果与历史重复 → 引用
└─ Layer 1  循环内预算与 web-aware 压缩（增强 ToolLoopCompressor）
             - 最新页面视图 verbatim 保留；旧视图进检查点"页面时间线"
             - 工具结果每请求 token 预算，超限先裁最旧
```

## 4. 核心：PageViewDeduper（Layer 3）

### 4.1 指纹

对视图工具的文本结果计算规范化指纹（SHA-256）：

- `normalize()`：折叠空白、去掉 `[box=x,y,w,h]` 坐标、去掉时间戳/动态计数行（可配置正则）；
- 指纹 = `sha256(normalize(text))`，连同 (toolName, 规范化后的关键参数如 viewports/url) 一起构成缓存键；
- 循环实例内维护 `url/fingerprint → 消息序号` 映射；`ToolLoopCompressor` 折叠轮次时同步更新映射。

### 4.2 三种返回形态（按优先级）

1. **未变化**（同一 URL，指纹与上次视图相同，且在 TTL 内）：
   返回自足引用（约 200-400 字符），例如：

   ```
   [page snapshot] url=https://... title=... 
   [unchanged since tool result #k, fp=abc123...] 内容与上次视图一致。
   如需强制重取请调用 <tool>(refresh=true)。
   ```

2. **变化但重叠大**（diff 行数 ≤ 总行数 60%，且 diff 文本 ≤ `pageViewDiffMaxChars`）：
   返回差异视图：规范化文本的 unified diff（- 旧 / + 新），头部附 url/title/指纹 + 摘要；模型看到的是
   "自上次视图以来发生了什么"，通常只有几行（新 toast、列表项变化），token 节省 80-95%，且**注意力更集中**。

3. **变化大 / 显式 `full=true`**：返回完整快照（仍走现有 5000 字符信封截断）。

### 4.3 接线点

- `AgentToolCallLoop.generate()`：`coordinator.execute()` 之后、`messages.add(resultMessage)` 之前插入
  `pageViewDeduper.decorate(resultMessage, messages)`；
- **不改** `ToolExecutionCoordinator`（执行仍发生，只改进入会话的形态；浏览器往返成本另议）；
- 快速路径（可选二期）：指纹命中且未变化时跳过执行、直接合成引用消息，省浏览器往返——默认关闭，避免动态页面误判。

### 4.4 动态页面护栏

- TTL（默认 30s）：TTL 内命中"未变化"才走引用，超时重新执行；
- `refresh=true` 参数：任何视图工具显式强制重取（模型被引用误导时自救）；
- 规范化规则可配置，广告/倒计时等高频变化区域可整体剔除，避免指纹抖动。

## 5. 会话级重复折叠（Layer 2）

- 通用化：**任意** `ToolExecutionResultMessage`（非视图工具也一样）在追加时计算规范化哈希，
  若与会话中已有消息重复（如两次 `eval` 返回相同 JSON），替换为引用 `[duplicate of result #k, fp=...]`；
- 与 Layer 3 共用同一哈希映射与引用格式；视图工具由 PageViewDeduper 优先处理。

## 6. web-aware 压缩增强（Layer 1）

在 `ToolLoopCompressor` 上做三处增强（保持既有行为兼容，新键默认开启）：

1. **最新页面视图 verbatim 保留**：`compressIfNeeded` 计算保留尾部时，若最近 K 轮中不含完整页面视图，
   则把最近一次完整页面视图所在轮次纳入保留区（宁可多保留一轮）；
2. **检查点含"页面状态时间线"**：`COMPACTION_INSTRUCTION` 增加 `## Page State` 小节：
   每行 `url | title | fp | 变化要点`（视图 → 差异摘要），保证压缩后模型仍掌握页面演化脉络；
3. **工具结果每请求预算**：新增 `maxToolResultTokens`（默认 20K）——超限时先裁剪**最旧**结果
   （已有 head/tail 机制），最新页面视图永不裁。

## 7. 配置（ContextToAction 读取，与 toolLoop.* 同源）

| 键 | 默认 | 说明 |
|---|---|---|
| `browser4.agent.toolLoop.pageViewDedupEnabled` | true | Layer 3 总开关 |
| `browser4.agent.toolLoop.pageViewDiffEnabled` | true | 差异视图开关 |
| `browser4.agent.toolLoop.pageViewDiffMaxChars` | 3000 | 差异文本上限，超限回退全文 |
| `browser4.agent.toolLoop.pageViewDigestChars` | 300 | 引用/摘要 digest 长度 |
| `browser4.agent.toolLoop.viewToolNames` | ariaSnapshot,snapshot,dump,htmlsnapshot,extract | 视为页面视图的工具名 |
| `browser4.agent.toolLoop.duplicateFoldEnabled` | true | Layer 2 通用重复折叠 |
| `browser4.agent.toolLoop.maxToolResultTokens` | 20000 | 每请求工具结果 token 预算 |
| `browser4.agent.toolLoop.retainLatestPageView` | true | 压缩保留最新完整页面视图 |

> `pageViewDedupTtlMillis`（跳过执行的快速路径 TTL）留待二期，见 §10。

## 8. 实现计划

1. **新增** `browser4-agentic/.../inference/chat/PageViewDeduper.kt`：
   `fingerprint(text)`、`normalize()`、`decorate(resultMessage, messages): ChatMessage`、diff 生成与回退判定——纯逻辑、无 IO，可单测；
2. **修改** `AgentToolCallLoop.kt`：构造参数 `pageViewDeduper: PageViewDeduper? = null`，追加结果消息前调用；
3. **修改** `ToolLoopCompressor.kt`：`retainLatestPageView` 保留策略 + `COMPACTION_INSTRUCTION` 增加 Page State 小节；
4. **修改** `ContextToAction.kt`：新增 9 个配置键，构造 deduper 与增强 compressor；
5. **测试**：`PageViewDeduperTest.kt`（指纹稳定/变化、未变化引用、diff 回退、TTL、refresh 强制、KV 前缀不变性：
   已发出消息不被改写）+ `ToolLoopCompressorTest` 增补（最新视图保留、Page State 时间线）；
6. **验证**：browser4-agentic 单测全绿；重跑多视图网页任务，对比 act/response.json 的 inputTokenCount 总和。

## 9. 预期收益与风险

- **收益**：典型网页步（同一页 4-5 次视图、单页 30-50KB）页面内容 token 从 ~5×8.5K=42K/轮 降到
  首视图 8.5K + 3 次差异 ~1-2K ≈ 12-14K；叠加现有压缩后单步输入稳定在 ~60-80K（此前 220K-1.25M）；
  差异视图还提升模型对"页面发生了什么变化"的注意力，减少误判重试。
- **风险与对策**：
  - 模型"不记得"被引用的原文 → 引用自足 digest + Page State 时间线兜底；
  - 动态页面指纹抖动 → 规范化剔除高频变化区、TTL、`refresh=true` 逃生门；
  - 大改版页面 diff 无意义 → 变化占比 >60% 回退全文；
  - 压缩折叠了被引用的原文轮次 → 引用自带 digest，且最新视图永不折叠。

## 10. 不做的事（本轮范围外）

- 不改单条结果 5000 字符信封截断（Layer 4 源端瘦身另立任务）；
- 不做跨步（step 间）页面缓存——外层 AgentHistory 已有预算，先解决循环内大头；
- 不做截图去重（图像 token 另议）；
- `pageViewDedupTtlMillis`（跳过执行的快速路径护栏）留待二期：当前 TTL 仅影响"是否执行浏览器往返"，本轮只做结果形态优化，执行仍发生。

---

## 11. 实现状态（2026-08-21 已落地）

### 新增文件

- `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/chat/PageViewDeduper.kt`
  - 规范化指纹（SHA-256 16 位 hex，剥离 `[box=...]` 等易变区域、折叠空白）；
  - `decorate()`：追加时折叠——完全相同 → 自足引用（digest + fp + refresh 提示）；视图工具新内容 → 前缀/后缀行差异（变化占比 >60% 或超出 `diffMaxChars` 回退全文）；非视图工具仅折叠精确重复（`duplicateFoldEnabled`）；
  - 引用/差异消息保持 `ToolExecutionResultMessage` 角色，轮次结构协议合法；
  - `reset()` 供压缩后重建索引。
- `browser4-agentic/src/test/kotlin/ai/platon/pulsar/agentic/inference/chat/PageViewDeduperTest.kt`（12 用例）

### 修改文件

- `AgentToolCallLoop.kt`：新增 `pageViewDeduper` 构造参数；`coordinator.execute` 后、`messages.add` 前调用 `decorate`（raw 结果仍进 `onToolResult` 追踪）；压缩发生时 `reset()`。
- `ToolLoopCompressor.kt`：
  - 新增 `retainLatestPageView`（最新完整页面视图所在轮次永不压缩）、`viewToolNames`、`maxResultTokens`（累计结果预算，先裁最旧、保护最新）三个参数，`summarizer` 移至末位保持尾随 lambda 兼容；
  - `pruneToolResults`/`enforceResultTokenBudget` 跳过已压缩的引用/差异形态；
  - `COMPACTION_INSTRUCTION` 新增 `## Page State` 小节。
- `ContextToAction.kt`：新增 8 个配置键，构造 deduper 与增强 compressor。
- `RobustBrowserAgent.kt`：CLI-agent 路径同样接线 deduper 与三个新参数（`cliPageViewDeduper()` + `cliViewToolNames()`）。

### 配置键与默认值（全部默认开启）

| 键 | 默认 | 说明 |
|---|---|---|
| `browser4.agent.toolLoop.pageViewDedupEnabled` | true | 页面去重总开关 |
| `browser4.agent.toolLoop.pageViewDiffEnabled` | true | 差异视图开关 |
| `browser4.agent.toolLoop.pageViewDiffMaxChars` | 3000 | 差异文本上限，超限回退全文 |
| `browser4.agent.toolLoop.pageViewDigestChars` | 300 | 引用 digest 长度 |
| `browser4.agent.toolLoop.duplicateFoldEnabled` | true | 非视图工具精确重复折叠 |
| `browser4.agent.toolLoop.viewToolNames` | ariaSnapshot,textContent,snapshot,dump,htmlsnapshot,extract | 视图工具名（支持 `domain.` 前缀匹配） |
| `browser4.agent.toolLoop.retainLatestPageView` | true | 压缩保留最新完整页面视图 |
| `browser4.agent.toolLoop.maxToolResultTokens` | 20000 | 每请求工具结果 token 预算（0=关） |

### 测试与验证

- `PageViewDeduperTest`（12）：首份全文/后续引用、box 坐标剥离、小变化出 diff、大变化回退、diff 超限回退、非视图工具折叠开关、域前缀匹配、reset、禁用透传、空白不敏感、指纹区分。
- `ToolLoopCompressorTest`（15）：新增预算裁剪（先旧后新、保护最新）、紧凑形态跳过裁剪、页面视图保留（开/关对比）、Page State 指令。
- `AgentToolCallLoopTest`（8）：新增 deduper 接线（第二份相同页面折叠为引用，raw 结果仍进追踪）。
- `mvn -pl browser4-agentic test` 全量回归全绿。
