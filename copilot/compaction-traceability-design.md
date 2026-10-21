# 压缩可回溯性与事务可靠性优化设计（对齐 deepseek-harness）

> 日期：2026-08-22 · 前置依赖：[tool-loop-compression-design.md](tool-loop-compression-design.md)（已落地）、
> [web-page-context-optimization-design.md](web-page-context-optimization-design.md)（已落地）
> 参照实现：`D:\workspace\ds-harness\deepseek-harness`（packages/compaction/*、packages/llm/token-meter、packages/core/session）
> 问题：现有压缩实现了"语义自足"的回溯（引用带 digest + Page State 时间线），但缺少 deepseek-harness 的
> **结构性回溯**（压缩账本 + 稳定引用解析）与**事务可靠性**（稳定性校验、shrink 校验、溢出恢复、审计记账）。
> 目标：在不推翻现有设计（KV-cache 前缀不变性、引用/diff、保留最新视图）的前提下，补齐上述能力。

## 1. 现状缺口（对照 deepseek-harness 逐条审计）

| # | 缺口 | 现有实现（Browser4） | deepseek-harness 的解法 |
|---|---|---|---|
| G1 | **引用序号压缩后失效** | `[duplicate of result #k]` / `[page diff vs result #k]` 中的 `#k` 是压缩前位置序号；压缩改写列表后 `#k` 不可解析，只能靠 digest 语义自足（`PageViewDeduper.kt:190,204`） | append-only 事件日志 + `sourceEventSeqs` 引用链：替换事件显式记录被遮蔽的每个 seq，**结构上**可回溯 |
| G2 | **压缩无审计账本** | `compressIfNeeded` 直接 `subList.clear()+add`（`ToolLoopCompressor.kt:211-212`），只打一条 INFO 日志，压缩掉了什么、省了多少 token 不可审计 | `compaction/start` → `compaction/summary`（含 `shadowedSeqs/shadowedTokenCount/usage`）→ `compaction/end` 事件；失败也留痕 |
| G3 | **无稳定性校验** | 摘要 await 期间若 messages 变化，提交时可能替换错范围 | 提交前校验 surface 未变（`SurfaceChangedError`），异步摘要前后两次 `isDeepStrictEqual` |
| G4 | **无 shrink 校验** | 摘要只要非空就提交，可能比被遮蔽内容还大 | `framedSummaryTokenCount >= shadowedTokenCount` → 事务失败（`region.ts:374`） |
| G5 | **无上下文溢出恢复** | `RequestTokenLimiter` 只在请求前拦截抛异常；provider 确认的 context-overflow 没有恢复路径 | `agent/request-error` + `CONTEXT_WINDOW_EXCEEDED_CODE` → 强制压缩（retainTokens=0）→ `{kind:'retry'}`，上限 `maxOverflowRetries` |
| G6 | **保留策略误匹配 diff 形态** | `retainLatestPageView` 按工具名匹配任意视图结果（`ToolLoopCompressor.kt:186-189`），最近一次视图是 diff 时保留的是 diff 轮次而非完整视图轮次 | 无直接对应（harness 无 dedup）；其原则是"retainTokens 尾部 verbatim + balanced cut" |
| G7 | **无工具对平衡显式校验** | 轮次切分隐含 balanced，但 `retainLatestPageView` 把 `keepFrom` 拉回视图轮时没有重新校验切割点 | `toolPairingBalancedBefore/After` 显式平衡 + 缓存按 `replaceGeneration` 失效 |
| G8 | **预算裁剪无对账** | `pruneToolResults` / `enforceResultTokenBudget` 裁剪后只记日志 | 每次裁剪前写 `compaction/prune` **shadow-price 事件**（被裁节点的 token 定价），纯消费者可扣减对账 |

## 2. 设计原则（继承 + 新增）

1. **继承**：追加时去重、历史只写不改（KV-cache 前缀复用）；引用自足（digest）；最新页面状态 verbatim 保留；可配置可观测可回退。
2. **新增 · 引用用稳定 ID 而非位置序号**：引用/差异消息携带 `callId`（`ToolExecutionResultMessage.id()`，天然稳定），
   位置 `#k` 仅作展示辅助；压缩后引用仍可解析到"该 callId 的消息已被压缩进 checkpoint X"。
3. **新增 · 压缩账本（CompactionLedger）**：循环内维护"发生了什么"的不可变记录，等价于 harness 的
   `compaction/start|summary|end` 事件流；提供 `resolve()` 把任意历史引用/序号映射到当前状态。
4. **新增 · 压缩事务**：准备（测量+选区快照）→ 摘要 → **稳定性校验 + shrink 校验** → 提交（原子替换 + 记账）；失败留痕不半提交。
5. **新增 · 溢出恢复**：provider 确认的 context-window-exceeded → 强制压缩 → 重试，带上限。
6. **新增 · shadow-price 记账**：每次 prune/压缩记录被遮蔽 token 数，与测量同口径，可对账审计。

## 3. 方案总览（五块）

```
┌─ A. CompactionLedger（新增）        压缩审计账本 + 稳定引用解析（G1, G2）
├─ B. 压缩事务化（改 ToolLoopCompressor） 稳定性校验 + shrink 校验 + balanced 重校验（G3, G4, G7）
├─ C. 溢出恢复（改 AgentToolCallLoop）    context-overflow → 强制压缩 → 重试（G5）
├─ D. 保留策略修正（改 ToolLoopCompressor）最新完整视图优先保留（G6）
└─ E. 裁剪对账（改 ToolLoopCompressor）   shadow-price 记录 + 替换合法性断言（G8）
```

## 4. A. CompactionLedger —— 压缩账本与引用解析（核心新增）

`browser4-agentic/.../chat/CompactionLedger.kt`，纯逻辑、无 IO、可单测。每实例一份（随 `AgentToolCallLoop` 生命周期）。

### 4.1 记录类型（追加式时间线）

```kotlin
sealed class LedgerEntry {
    /** 一次工具结果以原始形态进入会话。 */
    data class ResultRegistered(val callId: String, val messageIndex: Int) : LedgerEntry()
    /** 一次裁剪：同 callId 的后续消息替换了早期消息（G8）。 */
    data class Pruned(val callId: String, val shadowedIndex: Int, val replacementIndex: Int,
                      val shadowedTokens: Long) : LedgerEntry()
    /** 一次引用/差异折叠：同 callId 的原始消息以紧凑形态进入会话。 */
    data class Folded(val callId: String, val originalIndex: Int, val compactIndex: Int) : LedgerEntry()
    /** 一次区段压缩：一段消息区间被一个 checkpoint 消息替换。 */
    data class Compacted(val compactionId: String, val reason: CompactionReason,
                         val shadowedRange: IntRange, val replacementIndex: Int,
                         val shadowedTokens: Long, val replacementTokens: Long,
                         val failure: String? = null) : LedgerEntry()
}
```

### 4.2 解析面（回溯的核心）

```kotlin
sealed class Resolution {
    data class Live(val messageIndex: Int) : Resolution()          // 原文还在会话里
    data class PrunedAway(val replacementIndex: Int) : Resolution() // 被裁剪，指向替换消息
    data class CompactedAway(val compactionId: String) : Resolution() // 被压缩进 checkpoint
    data class Unknown(val reason: String) : Resolution()
}
fun resolve(callId: String): Resolution
fun resolveIndex(originalIndex: Int): Resolution   // 供历史 #k 引用兜底解析
```

- `callId → 当前消息` 用最新一条 `ResultRegistered/Pruned/Folded` 条目判定（时间线单调，天然"最新优先"）；
- `Compacted` 条目记录 `shadowedRange`（压缩前的消息序号区间），`resolveIndex(k)` 命中区间 → `CompactedAway`；
- 与 deepseek-harness `sourceEventSeqs` 对应：每个 `Compacted` 携带被遮蔽的完整消息序号集（由 range 表达）。

### 4.3 与 PageViewDeduper 的协作

- `PageViewDeduper` 产生引用/差异时把 `callId` 写入消息文本（见 §4.4），并向 ledger 写 `Folded`；
- 压缩提交时 ledger 先记 `Compacted`，**再** `pageViewDeduper.reset()` —— 顺序保证旧引用可解析到压缩事件；
- `AgentToolCallLoop` 溢出摘要（`overflowProgressSummary`）改用 ledger 的 `resolve` 生成，避免引用到被压缩的旧消息。

### 4.4 引用格式升级（G1 的直接修复）

`PageViewDeduper.referenceMessage/diffMessage` 文本改为：

```
[duplicate of result #k (call=callId, fp=...) ] 内容与之前完全一致，未重复发送全文。摘要: ...
[page diff vs result #k (call=callId, fp=...) ] 自上次视图以来的变化（- 旧 / + 新）：...
```

- `callId` 取自 `result.id()`（`ToolExecutionResultMessage` 的稳定 ID），**不随压缩失效**；
- `#k` 保留为人类可读的位置提示；模型推理仍靠 digest 自足（不变）；
- 新格式与旧格式（无 call）在 `isCompactForm` 判定上兼容（标记子串不变）。

## 5. B. 压缩事务化（ToolLoopCompressor 改造）

`compressIfNeeded` 从"直接替换"改为三阶段事务（保持现有签名兼容，失败返回 false 并记账）：

### 5.1 准备（prepare）

1. `estimateTotal` 超阈值 → 走 `findRounds` + 尾部保留预算（现有逻辑）；
2. `retainLatestPageView` 修正为**最新完整视图**优先（§7 细节）；
3. **balanced 重校验**：`keepFrom` 最终确定后，断言 `rounds[keepFrom].start` 是轮次边界（现有结构保证），
   且 `retainLatestPageView` 拉回后仍满足轮次边界 —— 不满足则放弃拉回（G7）；
4. 快照被遮蔽区间 `shadowedSeqs`（消息对象引用数组），供提交前校验。

### 5.2 摘要与校验（validate）

1. 现有 summarizer 调用不变；
2. **shrink 校验（G4）**：`estimateTokens(framed) < shadowedTokens` 才可提交；
   失败 → ledger 记 `Compacted(failure=...)` → 返回 false（保留原始历史，下次压力再试）；
3. **结构校验（新增，对应 harness 的固定小节纪律）**：解析 `<compacted-summary>` 内是否包含全部必需小节
   （含 `## Page State`）；缺失 → 重试一次摘要（`summarizationRetries`，默认 1）→ 仍缺失则放弃本次压缩并记账；
4. **稳定性校验（G3）**：提交前校验 `messages` 中 `shadowedSeqs` 各位置仍是快照中的同一对象；
   任何变化（摘要期间后续轮次追加导致位置偏移）→ 放弃并记账，不半提交。

### 5.3 提交（commit）

1. 原子替换：`subList.clear() + add(framed)`（现有实现）；
2. 记账：`ledger.append(Compacted(compactionId=randomUUID(), ...))`，token 用与测量同口径的 `estimateTokens`；
3. `pageViewDeduper.reset()`（现有）——顺序见 §4.3；
4. 审计日志升级为 harness 风格：`compaction (${reason}): shadowed ${range} (~${shadowedTokens} tokens) -> ${replacementTokens} tokens, id=${compactionId}`。

## 6. C. 上下文溢出恢复（AgentToolCallLoop 改造，G5）

### 6.1 触发

`model.langChainChat(request, "cta")` 抛出 provider 确认的 context-window-exceeded（`code` 含 `CONTEXT_WINDOW_EXCEEDED` 或等价标记）时：

1. 若 `compressor == null` 或无 `maxOverflowRetries` 配置 → 维持现状抛出；
2. 否则进入恢复：`compressor.compactForOverflow(messages)` —— 强制路径：**先 `pruneToolResults`**，再
   `compressIfNeeded` 且 `retainTokens = 0`（压缩到只剩最近一个平衡轮次），仅一次；
3. 恢复成功（发生了真实替换）→ **重试当前轮请求**（continue 循环，`retryOverflow` 计数 +1）；
4. 恢复失败或重试超限 → 抛原始错误（模型错误语义不变）。

### 6.2 计数与重置

- `overflowRetries: Int` 随循环实例维护；每次**成功收到模型响应**（无论是否 tool call）清零；
- 与 deepseek-harness 一致：`maxOverflowRetries` 默认 1；被裁剪的恢复也算恢复进展（`prune` 落地即重试）。

### 6.3 与 RequestTokenLimiter 的关系

- 前置拦截（`requestTokenLimiter.enforce`）保留 —— 它防的是"已知会超限"的提前失败；
- 溢出恢复是**后置兜底** —— 处理估算低估 / provider 实际拒绝；
- `enforce` 在压缩后运行（现有顺序），溢出恢复在请求异常路径运行，两者互补。

## 7. D. 保留策略修正（G6）

`retainLatestPageView` 的查找逻辑从"任意视图工具结果"改为：

```kotlin
// 从尾部向前找：优先最新 FULL 视图轮次（非紧凑形态）；退化为最新视图轮次（现状行为）
val latestViewRound = rounds.indexOfLast { round ->
    messages.subList(round.start, round.end + 1).any {
        it is ToolExecutionResultMessage
            && PageViewDeduper.matchesViewTool(it.toolName(), viewToolNames)
            && !PageViewDeduper.isCompactForm(it.text())   // 新增：diff/引用不算完整视图
    }
} // 找不到 → 用现有（任意视图结果）逻辑兜底
```

- 修复点：页面变化后最近一次视图是 diff 时，保留的不再是 diff 轮次而是更早的完整快照轮次；
- diff 轮次本身很小，被压缩的损失可接受；但完整快照是模型工作上下文，必须 verbatim。

## 8. E. 裁剪对账（G8）

`pruneToolResults` / `enforceResultTokenBudget` 每次实际替换时：

1. 向 ledger 写 `Pruned(callId, shadowedIndex, replacementIndex, shadowedTokens)`；
2. 断言替换消息保留 `id()` 与 `toolName()`（与 harness `assertToolResultRewrite` 的"只允许改 content"对应）；
3. 新增 `estimatePruneSavings(): Long` 只读接口，供日志/审计汇总本轮累计节省（与 harness shadow-price 对账同构）。

## 9. 配置（ContextToAction / RobustBrowserAgent 读取，全部默认开启）

| 键 | 默认 | 说明 | 对应缺口 |
|---|---|---|---|
| `browser4.agent.toolLoop.compactionLedgerEnabled` | true | 压缩账本与引用解析 | G1, G2 |
| `browser4.agent.toolLoop.requireShrink` | true | 摘要必须比被遮蔽内容小才提交 | G4 |
| `browser4.agent.toolLoop.summarizationRetries` | 1 | 摘要结构校验失败后的重试次数 | — |
| `browser4.agent.toolLoop.maxOverflowRetries` | 1 | 上下文溢出恢复重试上限（0=关） | G5 |
| `browser4.agent.toolLoop.auditCompaction` | true | 压缩审计日志（结构化，含 compactionId/范围/token 账） | G2 |

> 设计沿用：`retainLatestPageView` / `viewToolNames` / `maxToolResultTokens` 等既有键不变；`compactionLedgerEnabled=false` 时
> 所有记账与解析退化为现状（引用回退到纯 digest 自足）。

## 10. 与 deepseek-harness 的映射总结

| deepseek-harness 机制 | Browser4 优化落点 | 裁剪理由（若照搬则过度） |
|---|---|---|
| append-only 事件日志 + surface 派生 | 保留 `messages` 列表为主状态；`CompactionLedger` 只记"压缩/裁剪事件"（消息级 replace 的增量） | 无会话持久化基础设施，全量事件溯源超出循环内优化范围 |
| `sourceEventSeqs` 引用链 | 引用消息携带稳定 `callId` + ledger `resolve()` | 消息对象即"事件"，callId 即稳定标识 |
| `compaction/start\|summary\|end` 事件流 | `LedgerEntry.Compacted`（含失败留痕）+ 升级审计日志 | 单线程串行循环无并发锁需求；事务语义用内存账本表达 |
| `SurfaceChangedError` 稳定性校验 | 提交前消息对象引用一致性校验 | — |
| shrink 强制校验 | `requireShrink` 提交门禁 | — |
| 溢出恢复 + `maxOverflowRetries` | `AgentToolCallLoop` 请求异常路径 | — |
| shadow-price（`compaction/prune`） | ledger `Pruned` 条目 + `estimatePruneSavings()` | — |
| tool-pairing balanced cut | 轮次边界（已有）+ `retainLatestPageView` 拉回后重校验 | — |
| KV-cache 前缀复用摘要调用 | 已实现（summarizer 复用 system/tools/前缀，指令为末条 user 消息） | — |
| 固定小节摘要指令 + 合并旧 checkpoint | 已实现（含 `## Page State`）；新增结构校验 | — |

## 11. 实现计划

1. **新增** `CompactionLedger.kt`：条目类型、`resolve(callId)` / `resolveIndex(k)`、`estimatePruneSavings()` —— 纯逻辑可单测；
2. **修改** `PageViewDeduper.kt`：引用/差异格式加 `call=`；`decorate` 回调 ledger（构造参数可选）；
3. **修改** `ToolLoopCompressor.kt`：事务化（prepare/validate/commit）、`compactForOverflow`、结构校验、
   `retainLatestPageView` 修正、prune 记账与断言；构造参数加 `ledger`（可选）、`requireShrink`、`summarizationRetries`；
4. **修改** `AgentToolCallLoop.kt`：ledger 实例化与接线、溢出恢复路径（含 `overflowRetries` 计数与重置）、
   `overflowProgressSummary` 改用 `resolve`；
5. **修改** `ContextToAction.kt` / `RobustBrowserAgent.kt`：5 个新配置键 + 构造；
6. **测试**：
   - `CompactionLedgerTest`（新）：callId 解析最新优先、压缩后 `resolveIndex` 命中区间、失败留痕、prune 对账；
   - `ToolLoopCompressorTest` 增补：shrink 不满足不提交、结构校验失败重试后放弃、稳定性变化放弃、
     `retainLatestPageView` 在"最新为 diff"时保留完整视图轮、prune 记账与只改 content 断言；
   - `AgentToolCallLoopTest` 增补：溢出恢复（prune+压缩+重试）、重试上限、成功响应重置计数、引用含 callId；
   - 回归：既有 8+15+12 用例与 KV 前缀不变性（已发出消息不被改写）全部保持绿。

## 12. 验证标准

- `mvn -pl browser4-agentic test` 全量回归全绿；
- 手工/监督轮验证：发生压缩时审计日志含 `compactionId / shadowed range / token 账`；引用消息含 `call=`；
  溢出场景（人为把 `thresholdTokens` 调低）触发恢复重试且最终成功；
- 重跑多视图网页任务：单步输入 token 与现状持平或更低（不劣化），压缩后引用仍可经 ledger 解析。

## 13. 不做的事（本轮范围外）

- 不引入持久化事件日志/会话 surface（`messages` 仍是唯一会话状态；账本仅内存，随循环结束丢弃）；
- 不做跨循环（步间）的引用解析 —— 循环外 `AgentHistory` 预算已有，跨步压缩另立任务；
- 不做 `compaction/start` 式持久化锁（循环内串行，无并发写者）；
- 不改单条结果 5000 字符信封截断、不改 `RequestTokenLimiter` 前置语义、不改溢出时"丢弃内部会话"的既有行为。

---

## 14. 实现状态（2026-08-22 已落地）

### 新增文件

- `browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/chat/CompactionLedger.kt`
  - 追加式时间线：`ResultRegistered` / `Folded` / `Pruned` / `Compacted`（含失败留痕）；
  - `resolve(callId)` 最新条目优先；`resolveIndex(k)` 命中成功压缩区间 → `CompactedAway(compactionId)`；
  - `estimatePruneSavings()` 裁剪对账；`enabled=false` 全透明（退化为 digest 自足）。
- `browser4-agentic/src/test/kotlin/ai/platon/pulsar/agentic/inference/chat/CompactionLedgerTest.kt`（9 用例）

### 修改文件

- `PageViewDeduper.kt`：引用/差异消息携带稳定 `call=`（`result.id()`）；`decorate` 向共享 ledger 记 `Folded`/`ResultRegistered`。
- `ToolLoopCompressor.kt`：
  - 构造新增 `ledger` / `requireShrink` / `summarizationRetries` / `audit`（`summarizer` 保持末位，尾随 lambda 兼容）；
  - `compressIfNeeded` 事务化：快照 shadowed → 摘要重试（blank/shrink/结构校验）→ 稳定性校验 → 原子提交 + 记账；
  - `compactForOverflow`（prune + retainTokens=0 强制压缩，`context-overflow` 记账）；
  - `retainLatestPageView` 优先"最新完整视图"轮（`!isCompactForm`），diff/引用轮不视为完整视图；
  - prune/budget 裁剪记 `Pruned` 影子价格；`REQUIRED_SUMMARY_SECTIONS` 结构校验（含 `## Page State`）。
- `AgentToolCallLoop.kt`：`maxOverflowRetries`（成功响应重置）+ `compactionLedger` 共享接线；请求异常路径识别上下文溢出 → 压缩重试；`overflowProgressSummary` 对紧凑形态经 ledger 解析原文首行。
- `ContextToAction.kt` / `RobustBrowserAgent.kt`：5 个新配置键，三条路径共享同一 ledger 实例。

### 配置键与默认值（全部默认开启）

| 键 | 默认 | 说明 |
|---|---|---|
| `browser4.agent.toolLoop.compactionLedgerEnabled` | true | 压缩账本与引用解析 |
| `browser4.agent.toolLoop.requireShrink` | true | 摘要必须比被遮蔽内容小才提交 |
| `browser4.agent.toolLoop.summarizationRetries` | 1 | 摘要失败后的重试次数（0..5） |
| `browser4.agent.toolLoop.maxOverflowRetries` | 1 | 上下文溢出恢复重试上限（0=关） |
| `browser4.agent.toolLoop.auditCompaction` | true | 结构化压缩审计日志 |

### 测试与验证

- `CompactionLedgerTest`（9）：live 解析、折叠解析、prune 解析与对账、最新优先、压缩区间命中、失败留痕不遮蔽、禁用透明、累计节省。
- `ToolLoopCompressorTest`（23）：新增 shrink 拒绝、结构校验重试后放弃/重试成功、稳定性放弃、完整视图优先于 diff、prune 影子价格记账、`compactForOverflow` 强制压缩与 prune-only 恢复。
- `PageViewDeduperTest`（15）：新增引用携带 `call=`、共享 ledger 记账、`resolve` 折叠 callId。
- `AgentToolCallLoopTest`（13）：新增溢出恢复（prune+重试成功）、重试上限传播原始错误、`maxOverflowRetries=0` 关闭恢复。
- `mvn -pl browser4-agentic test` 全量回归全绿。
