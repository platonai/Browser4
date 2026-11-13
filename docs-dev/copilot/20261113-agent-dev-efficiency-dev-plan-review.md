# 审查：《Agent 自开发效率 — 开发计划（2026-11-13）》

> 被审文档：[20261113-agent-dev-efficiency-dev-plan.md](20261113-agent-dev-efficiency-dev-plan.md)（未跟踪，`git status` 为 `??`）
> 审查基线：当前工作树 `Browser4-4.16-feat`，所有代码锚点均在本树中逐条核对过。
> 审查口径：数字是否可复算、锚点是否成立、"已有能力"是否真的可复用、机制能否兑现 DoD、门禁命令能否跑通。
>
> **实施状态（2026-11-13）**：§9 的 8 项最小补丁已全部应用到计划正文（计划记为"修订 v2"），§8 的事实性
> 锚点与小修（`%`口径、`BatchToolExecutor.runStep`、四桶口径、测试矩阵）一并向文档修正。唯一偏离：
> §7 决策 4 保留了 `-Dbrowser4.agent.tool.parallelism` 作为逃生/灰度开关（回滚需要），默认值仍取
> `BatchExecutor.MAX_CONCURRENCY`。
>
> **P0 落地情况（2026-11-13）**：P0-1（构建短路）与 P0-0（轮次归因）已实现、通过门禁、并在 4 个**真实
> coding 任务**里产出数据（计划 §0.1 / 附录 B）：model 77% / build 10% / tool 1.2% / callback 0.2%。
> 结论：P0-2、P0-3、P1-3①② 均被数据否决或降级，下一步只剩"压低每轮模型成本"这一条主线。
> 我对 P1-3/P0-3 的原始排序已被实测修正，见下方"数据回填"。

## 0. 结论

**方向正确，优先级排序需要改；P0-1 可以立刻开工，P0-3 / P0-2 应先暂缓。**

| 项 | 判定 | 依据 |
|---|---|---|
| P0-1 编辑纪元 + 构建短路 | ✅ 可开工（附 5 处必改） | 缓存点、指纹来源、compile 复用路径都成立；但"风险最低"这个判断是错的（见 §3.1） |
| P0-3 同轮只读并发 | ⚠️ 暂缓 | 按计划自己的数据，它的收益上限是**总墙钟的 3.6%**（见 §2.1），且实现前提（阻塞式 coordinator）没被解决 |
| P0-2 多模块编译合并 | ⚠️ 重新定义 | "`mvnBuild` 收到多请求时改走 `buildMany`"在单次工具调用内部无法实现（见 §3.3） |
| P1-1 只读结果复用 | 🔁 缩小范围 | 缓存**已经存在**（`ToolResultCache`），不是"把语义上移"（见 §3.4） |
| P1-2 verify 分层 | 🔁 名不副实 | 默认检查项**已经是** compile + repo-consistency，分层是空转；真实收益只有并发 + 复用 P0-1 缓存 |
| P1-3 降低单调用开销 | ⭐ 应升到 P0 | 这是唯一能兑现 §2 "降 ≥40%" 的桶，但机制归因未验证（见 §2.2） |
| §5 门禁命令 | ❌ 三条跑不通 | `-am` + `-Dtest` 缺 `-Dsurefire.failIfNoSpecifiedTests=false`；`coding.verify` 缺 `module`（见 §4） |
| §1 基线链路描述 | ❌ 陈旧 | 同日的 [worktree 隔离计划](20261113-self-dev-worktree-isolation-plan.md) 的 S0–S3 **已实施**，coding 任务默认跑在 per-task worktree 里，不是"工作区镜像" |

> **数据回填（2026-11-13 深夜，4 个真实任务，见计划 §0.1 / 附录 B）**：上面的判断有两处被实测推翻，
> 以实测为准：
>
> | 我在审查里说 | 实测 | 修正 |
> |---|---|---|
> | 间隔桶大概率是模型延迟（§2.2），所以 P1-3 要谨慎 | model **77%**、callback **0.2%** | 模型延迟这一半成立；但"每调用记账"确实是噪声（0.2%），P1-3 ①② 应**删除**而不是升到 P0 |
> | P0-3 上限 3.6%，暂缓（§2.1） | tool **1.2%**（3.2 s / 197 调用） | 方向对、量级更小 → **整项删除** |
> | 建议顺序：归因 → P0-1 → 记账合批 → 工具面收敛 → P0-3（§6） | "少轮多塞"实测更慢（18 轮/64 s vs 14 轮/92 s） | 第 3、5 步删除；第 4 步重定义为"压低每轮请求成本"，且**不接受以轮数下降作为收益证明** |
>
> 我当时的排序建议建立在"间隔桶里记账占大头"的猜测上；§0 的归因测量正是为避免这种猜测而设，
> 它起作用了——结论是**只留 P0-1，其余优化先在每轮模型成本上做实验**。
>
> **下一步实测（成本公式）**：model 时间不是"一团黑"，而是 `600 ms/请求 + 4.05 ms/输出 token`
> （59 轮拟合，R² = 94.7%；未缓存输入项系数 ≈ 0，因为 prompt cache 命中 92.6%）。因此我在 §2.2 说
> "gap 混了模型延迟"是对的，但 P1-4 的方向也要改：**减输入（结果裁剪/多文件工具）没有延迟收益**，
> 能动的只有输出 token（可见部分约占四成）与请求数（0.6 s/次）；隐藏推理约占 model 桶六成，
> 需 provider 参数透传才能动（P1-5，前置在 base 库）。
>
> **A/B #1 已验证（计划 §0.3 / 附录 B.7）**：coding 提示词加"输出预算"规则后，同一任务 2+2 次对比
> outTok −22.6%、model −26.1%、**墙钟 105.4 → 77.7 s**，报告质量未降。这是本计划唯一被 A/B 证实
> 有效的优化；"减输入"那条按公式可以判死，不必再试。

一句话：这份计划把 50% 的"间隔桶"用一个未定义的口径去解释，然后据此把资源投给了只占 4% 的桶。

## 1. 锚点与"已有能力"核对

### 1.1 成立的锚点（逐条读过）

| 计划中的说法 | 实际 | 结论 |
|---|---|---|
| `AgentToolCallLoop.kt:313` `for (request in toolRequests)` | 一致，`AgentToolCallLoop.kt:313` | ✅ |
| `:204 executedTools` 是 `mutableListOf` | 一致 | ✅（但这不是并发风险点，见 §3.2） |
| `AgentMemorySink.kt:41` `toolExecuted` 带 `durationMs`/`callId` | 一致（`:41-47`） | ✅ |
| `BatchToolExecutor.kt:114` `isReadOnly` 接线 | 一致 | ✅ |
| `ToolMetrics.recordBatch`（`:224`） | 一致 | ✅ |
| `MutationSnapshot()` `:302`、`mutationCount` `:296`、`mutationLog` `:295` | 一致 | ✅ |
| `MavenBuildSupport`：`BuildResult:32` / `parseDiagnostics:145` / `build:286` | 一致 | ✅ |
| `VerificationRunner` `:244` / `verify:270`，检查项串行、无短路 | 一致（`:294` 的 `for`） | ✅ |
| `ToolRateLimitPolicy.kt:35` `READ_ONLY_METHOD_PREFIXES` | 行号对，但它是 **`private val`**（`:35`），外部拿不到 | ⚠️ 半对 |
| `AgentAccessPolicy` 的 `BUILD_METHODS`/`MUTATE_METHODS` | 行号对（`:514`/`:517`），但同样是 companion 里的 **private** | ⚠️ 半对 |

### 1.2 不成立的"已有能力"

**① `CodingToolExecutor.kt:2302` / `:1007` 不是副作用分类器。**

- `:2285` `WRITE_METHODS` 的注释自己写着 *"used to pick the FS_WRITE effect for `coding.explain`"*，唯一消费点是 `explainBoundary`（`:2299-2302`），即 `coding.explain` 的试跑诊断。它只覆盖 11 个文件写方法，不含 `shell`、`mvnBuild`、`verify`、`scaffold*`、`devTask`。
- `:1007` 的 `Effect.parse` 在 `requestAccess` 分支里，解析的是**模型传进来的 `effect` 参数**，与"这个工具调用有什么副作用"无关。
- 结论：§3 表格里"已有方法级副作用分类，可直接作为并发分类器的来源"这句，把两个**诊断/参数解析**路径当成了分类器。真正的分类器是 `AgentAccessPolicy` 的 `coding` 矩阵 + 公开入口 `gateOf(method)`（`:226`）。

**② 可复用的公开入口被漏掉了。** `AgentAccessPolicy.gateOf(method)` 返回 `ToolGate.READ/MUTATE/BUILD/EXEC`，正好是 P0-3 需要的四分类，且是**唯一的公开入口**；同时 `ToolGate.BUILD` 天然实现"构建是屏障"（§8 风险表里又重复设计了一次）。

**③ `gateOf` 不是"无副作用"分类器。** `READ_METHODS`（`:492`）里有三条 READ 是**访问控制语义**下的放行，而非无副作用：

```kotlin
// AgentAccessPolicy.kt:504-510
"explain",        // 只读试跑，确实无副作用
"requestAccess",  // 会创建 pending 请求 —— 写状态
"protect",        // 无 path 只列保护项；带 path 会改保护项 —— 写状态
```

若按"`gateOf == READ` ⇒ 可并发"直接落地，`coding.protect(path=…)` 与 `coding.requestAccess(…)` 会进并发段。计划 §7 决策 1 的倾向（"以 Effect/AgentAccessPolicy 为准"）会静默继承这三个例外。**分类必须是 `(domain, method, args)`，或对这两条一律当屏障。**

### 1.3 缓存其实已经有了，位置也写错了

- 步骤缓存不在 `BatchExecutor`，而在 `BatchToolExecutor.runStep`（`:130`；计划 §P1-1 引用的 "`BatchExecutor.runStep`" 不存在这个方法）。它读写的实例是 `ToolResultCache`（`BatchToolExecutor.kt:143-147` 走 `cache.get(spec, sessionId, args)`），共享单例在 `ToolResultCache.kt:215`。
- 所以 P1-1 的真实工作量不是"把语义上移"，而是：给 in-loop 调用接上 `ToolResultCache`（`coordinator.execute` 直接走 `AgentToolManager.execute`，绕过了 transport 层的缓存），并把失效条件从"TTL + session"换成"coding mutating 纪元"。
- 注意 `ToolResultCache` 的 key 含 `specVersion`（工具契约哈希）和 `sessionId`，TTL 型失效对 `coding.read` 是**错误语义**（写完再读必须看到新内容）。P1-1 必须显式声明这一点，否则就是把一个只为浏览页面设计的缓存接到文件编辑上。

## 2. 数字：账对得上，归因对不上

### 2.1 复算

| 项 | 计划值 | 复算 |
|---|---|---|
| 构建占比 | 1332/2863 = 46% | ✅ |
| 非构建工具 | 103/2863 = 4% | ✅ |
| 间隔桶 | 1429 s，1.57 s × 911 = 1430 s | ✅ |
| 三桶合计 | 1332+103+1429 = 2864 | ✅（±1 舍入） |
| 单次构建均值 | 1332/18 = 74 s | ✅ |
| 零编辑构建 | 10 次 ≈ 756 s | 756/2863 = **26.4%**，不是 40% |

**关键推论（计划没算）**：P0-3 只作用于"非构建工具"这 103 s，即使并发度无穷大，**收益上限 103 s = 3.6% 总墙钟**；而构建调用是屏障，撬不动那 1332 s。因此 P0 三项全做完的可行上限 ≈ 26% + ~2% ≈ **28%**，达不到 §2 的 "≥40%"。要过 40%，必须动间隔桶（50%），也就是 P1-3 / P1-4——它们现在排在 P1。

### 2.2 间隔桶的口径把三类时间混在一起

附录 A 的定义：`间隔 = 相邻事件 createdAt 差 − 前一个 durationMs 之和`。而 `durationMs` 只量了 `coordinator.execute`（`AgentToolCallLoop.kt:315/332`），所以这个桶同时装进了：

1. **模型推理延迟**（每轮一次，按调用数摊薄）；
2. **每调用回调开销**（`onToolRequest` → `pageViewDeduper.decorate` → `onToolDecorated` → `onToolResult`，其中含 3 次 jsonl 追加 + memory 追加，`AgentEventLog.append` 每次 `Files.writeString` 在全局锁内）；
3. **真实往返**（同一轮内的排队）。

计划 §1 把三者并列写成 "模型下发 + 逐调用记账 + 往返"，然后 P1-3 只打第 2 项，却给出 "1.57 → <0.8 s/次" 的目标——**隐含假设第 2 项占一半以上，但没有任何数据支持**。

更要紧的是计划自己给的例子反证了这个假设的方向：同一轮 17 个调用 17.2 s，工具时间 0.45 s ⇒ 这一轮 97% 的时间不在工具执行里。如果这 16.75 s 是回调开销，则 P0-3（并发执行工具）最多省 0.45 s，同时回调还得按序串行，**收益几乎为零**；如果这 16.75 s 是模型延迟，则 P1-3①② 也打不到它，真正的杠杆是**减少轮数**（P1-4）与**缩短提示词**。

**结论：在任何编码之前，先做一次归因测量（0.5 d）。** 具体建议：在 `AgentToolCallLoop` 的 5 个回调处埋 5 个 `System.nanoTime()` 桶，并在 `onModelResponse` 里记录每轮模型耗时，额外写一条 `round_timing` memory 事件（`{turn, modelMs, callbacksMs, toolsMs}`）。跑 2 个真实 coding 任务即可定案，成本远低于 P0-3 的 1 d。

### 2.3 数据不可复核

`app-data/memory/events` 不在本树（工作区根下没有 `app-data`），仓库里也没有任何含 "911 次调用" 的复盘产物——`docs-dev/` 下除本计划外没有该数据集。整个优先级建立在唯一一份不可复核的数字上。建议把复盘表（4 个任务 × 三桶 × 18 次构建清单）作为附录 B 落进文档，或把复算脚本放进 `docs-dev/copilot/scripts/`（计划 §附录 A 只"建议"，没落地）。

## 3. 逐项机制问题

### 3.1 P0-1（构建短路）

**成立的部分**：`compileCheck` 真的走 `maven.build`（`VerificationReport.kt:350`），所以"verify 的 compile 复用同一缓存"成立；`CodingToolExecutor` 里 `mavenBuild` 与 `VerificationRunner(mavenBuild)` 共享同一实例（`:130` / `:137`），所以同一任务内两条路径天然同缓存。

**必改 5 处**：

1. **"风险最低"改掉。** 这是全计划唯一会产出**假绿**的项。指纹一旦漏掉某条写路径，`coding.verify` 会在毫秒内对一次真实损坏的树返回 PASSED，而且会被 worktree 合并闸门（[隔离计划](20261113-self-dev-worktree-isolation-plan.md) D3：verify 通过才合并）当作放行依据。风险等级应为"机械简单、后果最重"。
2. **`mutationCount` 不能当指纹主体。**
   - 它被 `clearMutations()` 归零（`CodingAgentFileSystem.kt:305-308`），`beginTask` 每次任务都调（`:324-330`）；缓存若按 root 存活，跨任务就会撞号。
   - 它只统计经过 `recordMutation` 的写（`:370-393`），`recordMutation` 还会**主动跳过非产物路径**（`:388`）；更重要的是 `coding.shell` 里的 `sed`/`git apply`/格式化器/外部编辑器**完全不经过它**。
   - 因此指纹必须**只**由内容摘要构成（模块下源文件 + `pom.xml` + 上游 `-am` 链的 `pom.xml`），`mutationCount` 最多当"快速否定"（变了就一定不命中，没变也不能证明没变）。
3. **缓存策略要写死三条**：只缓存 `success && !timedOut`；缓存的失败**永不命中**（避免把一次 OOM/文件锁当成结论）；只在 `goals ⊆ compile` 且 `skipTests=true` 时短路。后者直接修掉 **DoD-1 与 DoD-5 的矛盾**：§2 第 1 条说"第二次 `coding.mvnBuild` 必须返回 cached"，第 5 条说"短路只允许出现在 compile 类检查上"——`coding.mvnBuild(goals="test")` 两句话打架。
4. **缓存必须进程级、按规范化 root 分桶，并显式处理任务边界。** 现行结构下 `MCPToolController.kt:458-463` 的 standalone executor 与 agent 的 manager 是两个 `MavenBuildSupport` 实例，实例级缓存跨路径不命中；而 `beginTask` 是天然的失效点，应在那里清掉该 root 的条目。
5. **`--no-cache` 开关名对齐既有约定**：仓库已有 `-Dmcp.cache.enabled`（`ToolResultCache.kt:218`），建议用 `-Dbrowser4.agent.buildCache.enabled` 而不是 `off`，读法与既有代码一致。

**补一条计划没写的测试**：`coding.shell("sed -i …")` 之后必须重新构建（这是唯一能证明"内容摘要是真的在算"的用例，也是唯一能防住假绿的用例）。

### 3.2 P0-3（只读并发）

1. **`coordinator.execute` 是阻塞的，不能直接放进 `async`。** `ToolExecutionCoordinator.execute` 不是 `suspend`，内部 `runBlocking(Dispatchers.IO)`（`:36-41`）。在 `coroutineScope { requests.map { async { coordinator.execute(it) } } }` 里，每个 `async` 体**既不挂起也不让出**，会在 `Dispatchers.Default`（`RobustBrowserAgent.kt:733` 的 `agentScope`）上占满 N 个线程；CI 2 核机器上"并发度 4"实际只有 2，DoD-2 的 "≤ 串行和 60%" 会在 4 个调用上直接失败。正确形状：加一个 `suspend fun executeSuspend` 直接调 `toolManager.execute`，然后 `withContext(Dispatchers.IO.limitedParallelism(n))`——与 `BatchExecutor.runParallel`（`:181-184`）完全一致，并复用 `MAX_CONCURRENCY = 4`（`:221`），不要新造 `-Dbrowser4.agent.tool.parallelism`。
2. **"单个工具抛异常不影响同批其它结果"这条验收是空转。** `execute` 已经 `catch (e: Exception)` 把一切（**包括 `CancellationException`**）吞成 `"[fail] …"`（`:54-56`）。所以 ④ 现在天然成立；而真正该修的是这个 catch：并发之后，一次用户取消会被报到某一条工具结果里，接着循环继续跑。**这条应在 P0-3 之前修，否则并发会把"取消"变成"部分失败"**。
3. **并发的真实风险点不是 `executedTools`。** `executedTools`（`:204`）只在串行收尾阶段写，回调按序就无需改。真正的共享可变集合在**调用方**：`RobustBrowserAgent.kt:1151` 的 `roundToolRecords = mutableListOf()`，`onToolResult` 里 `toolRecords += …`（`:1564`）。这一点在 [2026-10-22 执行器评审（并发正确性第 16 条）](20261022-agentic-executor-review.md) 里已经被点名过（"并行工具执行时 `onToolResult` 并发触发会 CME/丢记录"）。计划的 §8 风险表只写了 `executedTools`，漏了这条已被记录过的坑。
4. **`coroutineScope` 用错**：任一同批任务抛错会取消兄弟任务。既然工具异常已被 coordinator 吞掉，剩下的抛出点都是回调（jsonl/memory 写）——这些**不该**因为一条日志写失败而取消其它工具结果。用 `supervisorScope` + 逐条 `runCatching`。
5. **DoD-2 度量的是错的量**（见 §2.1）。建议改成"该轮墙钟下降 ≥ X%"（用 §2.2 新增的 `round_timing`），而不是"4 个只读调用的和"。

### 3.3 P0-2（多模块编译合并）

1. **触发点在单次工具调用内部看不到兄弟请求。** `coding.mvnBuild` 的参数是单个 `module`（`CodingToolExecutor.kt:1247-1261`），"收到多模块/多请求时改走 `buildMany`"里的"多请求"必须由**循环层**把同一轮的多次 `coding.mvnBuild` 合成一次——那是一个和 P0-3 完全不同的机制（工具级融合），并且要解决 **2 个 `ToolExecutionRequest` → 1 个结果** 的映射（两个 tool_call id 都要有结果消息，只能复制同一段文本）。另一个可行解是改工具契约（`modules: List<String>`）+ 改提示词，那属于 P1-4 的范畴。计划把这两条塞在同一个单元格里，实际上还没选型。
2. **`-am` 可能让合并变慢。** `mvn -pl a,b -am compile` 会连带编译两个模块的全部上游。Browser4 是 20+ 模块的 reactor，双模块合并很可能把"2 × 30 s"变成"1 × 120 s"。计划 §7 决策 3 只用"模块上限 3"兜底，但真正该先做的是**测量 `-am` 的 reactor 规模**（`-pl a,b -am` 的模块数），再决定是否用 `-pl a,b`（无 `-am`）+ 已安装产物。
3. **既有两条不变量不能破**：
   - 命令形状是**被测试锁定的**：`mavenTestCommand` 把 goal 放在最前（`MavenBuildSupport.kt:507-519` 的注释写明"every log, doc and test assumes it"），`mavenBuildCommand` 把 goal 放在 `-am` 之后；`buildMany` 必须给出与之一致的形状并补 `MavenBuildSupportTest.commandShapes` 断言。
   - `runMavenWithSelectorFallback`（`:360-381`）在"路径选择器不匹配"时会**再跑一次**（`:378-380`）。多模块版本要同时处理"部分模块选择器失败"（重试整条命令？还是拆开？），否则合并后的重试语义会变成 2 倍工作量。计划没提这条既有逻辑。
4. **`BuildResult` 是单模块形态**（`:32-45`），计划把"P0-1 的 `BuildResult` 形态定稿"列为前置是对的；但要明确新增的是 `MergedBuildResult(modules, perModule: List<BuildResult>)`，而不是给 `BuildResult` 加可空 `modules` 字段（那会让 `format()`、`buildResultJson`、verdict 证据三处都出现"到底哪个 module"的歧义）。

### 3.4 P1-1 / P1-2

- **P1-1**：见 §1.3。核心工作是"in-loop 接缓存 + 失效语义改成 coding 写纪元"，不是"上移语义"。另注意 `ToolCachePolicy.ttlMs(spec) != null` 是 `BatchToolExecutor.isReadOnly` 的现实判据（`:199-200`），in-loop 复用必须走同一套判据，否则 `system.batch` 与原生多调用会对"什么能并发/能缓存"给出不同答案——这是两个事实来源的经典坑。
- **P1-2**：默认检查集**已经**是 compile + repo-consistency（`VerificationReport.kt:285-289`），"默认走 fast"是空转。真实增量只有两条：(a) `compile` 与 `repo-consistency` 并发（收益：repo-consistency 是纯读，几秒到几十秒）；(b) compile 复用 P0-1 缓存（那本来就是 P0-1 的效果）。另外 DoD-5 要求 verdict 里写 `cached@<fp>`，而 `VerificationCheck`（`:16-33`）没有 `cached` 字段，`toJsonMap`/`fromJson`/`format()` 都要改，且消费方至少三处：`RobustBrowserAgent.recordVerificationVerdict`（`:2656-2663`）、`actualVerdictJson` 比对（`:2413`）、worktree 合并闸门 `IsolationGate`。计划 §1-§8 没有提到任何一个消费方。

### 3.5 P1-3

见 §2.2。若归因测量证明回调开销确实占大头，则 ①②是正解；若证明是模型延迟，则唯一有效的是"减少轮数"——即 P1-4（`readFiles`/`writeBatch`）+ 更大 `maxOutputTokens`/更紧的提示词，此时 P1-3 的 ③（`listTools` 按轮复用）也才有意义（每轮少一次往返）。

## 4. §5 门禁命令：三条跑不通

| 计划写法（§5） | 问题 | 修法 |
|---|---|---|
| `mvn -pl browser4-agentic test -Dtest=BuildShortCircuitTest` | 无 `-am` 时上游模块不参与，本地常常编译不过；加 `-am` 后 surefire 在无匹配测试的模块上直接 fail（仓库自己的 `mavenTestCommand` 就是为这个加了 `-Dsurefire.failIfNoSpecifiedTests=false`，见 `MavenBuildSupport.kt:517-519`） | `mvn test -pl <module> -am -Dtest=… -Dsurefire.failIfNoSpecifiedTests=false` |
| 同上，但 §6 把 `BuildShortCircuitTest` 放在 `browser4-coding` 或 `browser4-agentic`（二选一未定） | 命令与模块自相矛盾 | 先定模块；建议 `MavenBuildSupport` 层用例放 `browser4-coding`（纯单测、无需 shell），`coding.mvnBuild` 端到端放 `browser4-agentic` |
| `coding.verify(checks="compile,repo-consistency")` | 缺 `module`：`compileCheck` 在 module 为空时记 `ran=false`，`passed` 要求必需检查都 `ran`（`VerificationReport.kt:78`、`:322-330`）——这条门禁永远不过 | `coding.verify(module="<模块>", checks="compile,repo-consistency")` |
| 未提 | 新测试没有任何 `@Tag` | 按 [TESTING.md](../../docs/TESTING.md)：纯单测 `Fast`；任何真的起 mvn / 起进程的用例必须 `Slow`/`Heavy`（否则进 PR 门禁）。并发测试应有 `@Timeout`（先例：[mcp-interface-hardening-plan.md:558](mcp-interface-hardening-plan.md)："并发测试宁可失败也不能拖垮流水线"） |

## 5. 跨计划不一致（最重要的一条）

[同日的 worktree 隔离计划](20261113-self-dev-worktree-isolation-plan.md) 状态是 **S0–S3 已实施**，且本树代码已兑现：

- `CodingWorkspace.isolationMode` 默认 `"worktree"`（`CodingWorkspace.kt:105`、`:108`）；
- `StatefulAgentRunner` 在任务开始 `WorkspaceIsolation.begin`（`:727`），`provisionCodingWorkspaceMirror` 在 worktree 下直接返回（`AgentToolManager.kt:246`）。

于是本计划有三处失真：

1. **§1 头部把链路写成"工作区镜像 → `coding.*` → Maven 构建"**：默认路径已是 per-task git worktree（`.browser4/worktrees/<taskId>`），镜像只是 `isolation=off` 的回退。
2. **"按 workspace root 分桶"的缓存跨任务永不命中**（每个任务一个 root）。这对 P0-1 影响可控（它要的就是"同任务内零编辑不重跑"），但必须写清，否则读者会以为冷构建也能被救。
3. **P2-1 增量编译的前提被削弱**：新 worktree 的 `target/` 是空的，"只编译受影响模块"救不了首次构建；`-o`（离线）也依赖本地仓库齐全。另外 `browser4.agent.isolation.reuse=session`（`SessionWorktree`）一旦开启，同一会话跨任务共享 `target/`，缓存与增量编译的收益模型会**再变一次**——计划应把这两个开关列为前置条件，而不是 P2 的附带假设。
4. **验证强度与合并闸门耦合**：隔离计划的 D3 是"verify 通过才合并"。因此"cached verdict 算不算已通过"（本计划 §7 决策 2）不是内部设计选择，而是**合并闸门的放行条件**。`cached` 必须连同"覆盖了哪些模块"一起落进 verdict，否则闸门无法判断"这次缓存的编译是否覆盖了本次改动"。

另外，本计划 §3 表格把 `MavenBuildSupport` 描述为"单模块、无缓存、无合并"，但漏了它已有的 `test()`（`:323-346`）和选择器回退（`:360-381`）——这两处都是 P0-2 必须兼容的既有行为。

## 6. 建议修订后的顺序

| 新顺序 | 工作项 | 理由 | 估时 |
|---|---|---|---|
| 0 | **归因测量**（5 个回调桶 + `round_timing` 事件） | 没有它，P0-3 与 P1-3 的取舍都是猜的 | 0.5 d |
| 1 | **P0-1 构建短路**（按 §3.1 的 5 处修订） | 26% 是唯一确定的大块收益；且必须最早做，因为其它项都要复用它的 `BuildResult`/指纹 | 0.5–1 d |
| 2 | **P1-3① 记账合批**（用第 0 步的数据决定做哪几条） | 间隔桶 50%，是 ≥40% 目标的唯一来源 | 1 d |
| 3 | **P1-4 工具面收敛**（`readFiles`/`writeBatch`） | 真正减少轮数，直接压间隔桶；同时给 P0-2 提供契约变更的先例 | 1 d |
| 4 | **P0-3 只读并发**（先修 `runBlocking` 桥 + `CancellationException`，再并发） | 上限 3.6%，只有在归因数据证明"同轮工具时间占比 > 10%"时才值得做 | 1 d |
| 5 | **P0-2 合并构建**（先测 `-am` reactor 规模） | 可能变慢，需要先有数据 | 0.5 d |
| 6 | P1-1 / P1-2 | 都要接 P0-1 的指纹与缓存 | 各 0.5 d |

§2 的 DoD 建议拆成两档，否则永远"未达标"：

- **P0 里程碑**：零编辑构建 = 0；总墙钟降 **≥ 25%**；
- **完整目标**：降 **≥ 40%**，并附三桶（建议四桶：build / tool / callback / model）报表。

## 7. 待关闭决策的修订意见

| 计划 §7 决策 | 修订意见 |
|---|---|
| 1. 分类器来源 | 用 `AgentAccessPolicy.gateOf`（公开、含 BUILD/EXEC/READ/MUTATE），**但** `requestAccess`/`protect` 必须强制当屏障；前缀表 `ToolRateLimitPolicy.READ_ONLY_METHOD_PREFIXES` 是 `private` 且语义是限流，不要用 |
| 2. cached verdict 是否算通过 | 算，但必须带 ①指纹 ②生成时间 ③**覆盖的模块列表**（合并闸门要用）；`test`/`trapCheck` 永不缓存（保持） |
| 3. 合并模块上限 | 先用实测 reactor 规模定 N，而不是拍 3；优先考虑 `-pl a,b`（不带 `-am`） |
| 4. 默认并发度 | 复用 `BatchExecutor.MAX_CONCURRENCY = 4`，用 `Dispatchers.IO.limitedParallelism(4)`；不要新增 `-Dbrowser4.agent.tool.parallelism`（除非确认需要运行时调） |
| 5. `mvnd` | 保持"先 P2-1 再评估"；注意 worktree 冷 `target/` 会放大 mvnd 的收益，评估时要用 worktree 形态测 |

## 8. 文档层面的小修（不必单开一节，但与事实不符）

1. L17 "占 `e1e1b5c0` 的 34%" —— 单位不明（是那个任务的墙钟占比？）。写成 `756 s / <task 墙钟> = 34%`。
2. L48 "`BatchExecutor.kt:40-44`（`readOnly` 门禁、`concurrency`）" —— 类声明在 `:42-45`；`concurrency` 是 `run(...)` 的参数。写出准确符号：`BatchExecutor.run` / `runParallel:181`。
3. L72 "见 `BatchExecutor.runStep` 的注释" —— 应为 `BatchToolExecutor.runStep`（`:120-165`），缓存实现是 `ToolResultCache`。
4. L147 零编辑判定用写工具清单，漏了 `coding.shell`（可写文件）与 `scaffold*` 之外的 `devTask`/`revert` 变体；建议改为"**任何** 非 `gateOf == READ` 的调用都打断零编辑窗口"。
5. §6 测试矩阵缺 4 个用例：失败/超时不缓存、`coding.shell` 写后必重编、`cached` verdict 过 finish gate、并发下的 `roundToolRecords` 不丢记录。

## 9. 可直接采纳的最小补丁清单

1. §1 头部链路改为"per-task git worktree（`isolation=worktree` 默认）→ `coding.*` → Maven 构建 → 确定性校验"，并注明数据采集于 `e1e1b5c0`（worktree 之前）。
2. §2 DoD 拆两档（≥25% / ≥40%），DoD-2 改为"轮墙钟"口径。
3. §3 第 49 行整行重写：分类器 = `AgentAccessPolicy.gateOf`，并标注 `requestAccess`/`protect` 例外。
4. §4 P0-1 增加"只缓存 success、只缓存 compile 类 goals、指纹只由内容摘要构成、shell 写必须使缓存失效"四条。
5. §4 P0-3 增加"先加 `executeSuspend`、修 `CancellationException` 吞掉、用 `supervisorScope`、复用 `MAX_CONCURRENCY`、按 `Dispatchers.IO.limitedParallelism` 调度"五条，并把验收改成轮墙钟。
6. §4 P0-2 改成"先测 `-am` reactor 规模，再决定 loop 级融合还是工具契约 `modules: List<String>`"，并把 `runMavenWithSelectorFallback` 的多模块语义列为待定问题。
7. §5 三条门禁命令按 §4 修正。
8. 新增 §0 前置："归因测量"作为 P0 的第 0 步。
