# Agent 自开发效率 — 开发计划（2026-11-13）

> 依据：上一任务对最近 4 个会话任务、911 次工具调用的复盘（数据源 `app-data/memory/events` 的
> `tool_executed` 事件），以及 `agent run --coding` 的自开发链路（**per-task git worktree** →
> `coding.*` 工具 → Maven 构建 → 确定性校验）。
>
> 采集时点：数据来自 `e1e1b5c0` 前后，当时 coding 任务还跑在镜像工作区；自 2026-11-13
> [worktree 隔离计划](20261113-self-dev-worktree-isolation-plan.md) 的 S0–S3 落地后，默认执行根是
> `<repo>/.browser4/worktrees/<taskId>`（`CodingWorkspace.isolationMode` 默认 `worktree`），
> 因此**每个任务的 `target/` 是冷的**——构建缓存只能救同一任务内的重复构建（`isolation.reuse=session` 时
> 同一会话内可跨任务命中，见 P2-1）。
>
> 代码基线：`4.16.x`。本文所有改动点都带当前行号锚点；行号随代码漂移，以符号名为准。
> 相关文档：[Agent 权限能力模型 — 开发计划 v2](agent-permission-capability-model-dev-plan-v2.md)、
> [tool loop 压缩设计](tool-loop-compression-design.md)。
>
> **修订 v2（2026-11-13）**：按 [审查报告](20261113-agent-dev-efficiency-dev-plan-review.md) 的可采纳清单
> 修订 §0/§1/§2/§3/§4/§5/§6/§7/§8 与附录。核心变化：先归因再优化（新增 P0-0）、DoD 拆两档、
> P0-3 暂缓并补齐前置修复、P0-2 改成先测后选型、门禁命令改写为可执行形态。
>
> **修订 v3（2026-11-13 深夜，数据回填）**：P0-0 实测 4 个真实任务后，**P0-3 与 P1-3 ①② 被数据否决**、
> P1-4 重定义为"压低每轮请求成本"、P0-2 改用实测 reactor 规模（2 / 10 模块）、DoD 第 2 条删除并新增
> "model 桶下降 ≥20%"；P0-1 与 P0-0 已在真实任务中端到端验证。见 §0.1、§5、§9、附录 B。
>
> **修订 v4（2026-11-14，进度回填）**：P0-1 / P0-0 / 实验 1（输出预算提示词）/ N3（度量进结果）/
> N1（`coding.readFiles`）均已落地并各自提交；**N1 的 A/B 没有墙钟收益**（如实记录，工具保留、提示词行回滚）。
> 当前进度与待办见 **§12**，逐项数据见附录 B。6 个提交：`41993ec592`、`570c884f54`、`df378a5bed`、
> `a72cfd399b`、`e5df9643eb`、`fea39337c6`。

> **一句话进度（2026-11-14）**：数据采集与最大的两块浪费（零编辑重建、输出 token）已解决；
> 剩下 N4（缓存 verdict 的模块覆盖，0.5 d）、N7（worktree 未提交文件可见性，1 d）、N6（runner 消失，
> 0.5–1 d）、N5（指纹收敛，需先有命中率数据，1 d）；N2 受 base 库阻塞。

## 0. 前置门禁：先归因，再优化（P0-0）

> **状态（2026-11-13）：测量已实施，并已跑 4 个真实 coding 任务产出数据** → 结论见 §0.1 与附录 B。
> 两个门禁分支都已触发：**「记账」占比 0.2% < 20% → P1-3 ①② 降级；「工具」占比 1.2% < 10% → P0-3 不做。**

### 0.1 实测结论（4 个真实任务，四桶）

| 任务 | 轮数 | 调用 | build | tool | callback | model | 未覆盖 | 墙钟 |
|---|---|---|---|---|---|---|---|---|
| 编译+校验（读→build→verify） | 5 | 6 | 14.6 s | 0.11 s | 0.02 s | 8.6 s | 6.6 s | 29.9 s |
| 编辑被拒 + 两次 build | 22 | 22 | 14.5 s | 0.12 s | 0.01 s | 50.5 s | 6.9 s | 72.0 s |
| 只读巡检（78 次调用） | 18 | 78 | 0 | 1.50 s | 0.29 s | 64.4 s | 7.8 s | 76.4 s |
| 只读巡检（91 次调用，含末轮） | 14 | 91 | 0 | 1.52 s | 0.28 s | 92.5 s | 7.9 s | 102.2 s |
| **合计** | 59 | 197 | **29.2 s (10%)** | **3.2 s (1.2%)** | **0.6 s (0.2%)** | **216 s (77%)** | 26.4 s (9%) | 280.5 s |

三条结论，直接改写后续优先级：

1. **每调用记账不是问题**：197 次调用合计 0.6 s（≈ 3 ms/次）。P1-3 ①② 的理论收益上限是 0.6 s，
   原先"间隔桶 50%"的说法在拆桶后不成立——那 50% 里绝大部分是**模型往返**。
2. **同轮工具执行也不是问题**：3.2 s（1.2%）。P0-3（只读并发）最多省 3.2 s，**数据否决**。
3. **模型往返是最大桶（77%）**，而且它随"每轮塞进去的结果量"上升：第 3 个任务 18 轮 / 64 s，
   第 4 个任务 14 轮 / 92 s（轮数更少但每轮结果更多，总模型时间反而更长）。
   所以"压轮数"不是无条件收益，真正要压的是**每轮请求的成本**（上下文体积、结果裁剪、缓存命中）。

> 历史任务（`e1e1b5c0` 等 6 个，无 `round_timing`）用时间戳重建只能区分 build 46% / tool 4% /
> 轮内 29% / 轮间 21%；其中"轮内"部分在拆桶后证明主要是模型延迟（聚类无法看到真正的轮边界），
> 因此 v1 三桶口径不再作为决策依据（附录 A 已改）。

### 0.2 成本公式（59 轮实测拟合，R² = 94.7%）

把 4 个真实任务的 63 次请求逐轮配对（`round_timing.modelMs` × `cli-usage.jsonl` 的 token 用量），
最小二乘拟合得到：

```
modelMs ≈ 600 + 4.05 × outTokens        (n = 59, R² = 94.7%)
```

再加上"未缓存的输入"一项没有解释力（系数 −0.039 ≈ 0）：**输入几乎不要时间**。原因是 prompt cache
命中率 **92.6%**（1,326,158 输入 token 中 1,227,904 命中）。于是 model 桶只有两个价格：

| 价格 | 数值 | 直接含义 |
|---|---|---|
| 每次请求的固定延迟 | **≈ 0.6 s**（63 次 → 约 38 s） | 合并轮次直接省它 |
| 每个输出 token | **≈ 4.05 ms**（46,935 → 约 190 s） | 输出才是主成本；每 1k 输出 ≈ 4 s |
| 未缓存输入 token | ≈ 0 | **结果裁剪/上下文压缩不会按输入折算收益**，与 P1-4 原设相反 |

**输出 token 里"可见"的只有约四成**。把 T4 的 14 条 AI 消息按 prompt dump 拆开：可见文本 5,067 字符 +
工具参数 16,418 字符 ≈ 8.6k token，而 `usage.outputTokens` 是 21,491——差额是模型侧的隐藏推理
（最典型的一轮：3,948 输出 token，可见输出只有约 100 字符）。仓库内的 `BrowserChatModel` 来自外部件，
没有透传 `reasoning_effort`/thinking 开关的缝，所以**这条最大的杠杆当前不可控**（见附录 B.6）。

给下一步的三条定价结论：

1. 读密集型任务要快 20%，得砍掉约 20% 的输出 token（≈ 每轮少写 300 token）或减少 20% 的请求（每任务
   少 2–3 轮）——两者都由模型行为支配，不由代码结构支配。
2. 任何"减少上下文/裁剪结果"的改动必须用 A/B 验证它是否真的降低了隐藏推理；按输入 token 折算收益是错的。
3. base 库一旦能透传 `reasoning_effort`，A/B 才值得投入——隐藏推理约占 model 桶六成。

### 0.3 实验 #1：输出预算提示词（A/B，已保留）

假设（来自 §0.2）：model 桶 ≈ 0.6–0.9 s/请求 + ~3.3–4 ms/输出 token，所以"让模型少写"应当按 token 折现。
做法：在 coding 模式的 `### Context hygiene` 首条加一条**输出预算**规则（工具轮不写散文、最终报告不
复述文件内容），`CliAgentPromptTest` 增一条契约断言；同一任务、同一模型、无会话，**2 次基线 / 2 次处理组**。

| 臂 | 任务 | 轮 | 调用 | outTok | model | 墙钟 |
|---|---|---|---|---|---|---|
| 基线 | `684c2986` | 22 | 119 | 19,071 | 87.3 s | 98.6 s |
| 基线 | `6136cc73` | 21 | 126 | 24,024 | 101.6 s | 112.1 s |
| **基线均值** | | 21.5 | 122.5 | **21,548** | **94.5 s** | **105.4 s** |
| 处理 | `d0c04585` | 21 | 107 | 15,925 | 72.0 s | 77.2 s |
| 处理 | `eb6f815d` | 17 | 104 | 17,441 | 67.6 s | 78.1 s |
| **处理均值** | | 19.0 | 105.5 | **16,683** | **69.8 s** | **77.7 s** |
| **变化** | | −12% | −14% | **−22.6%** | **−26.1%** | **−26.3%** |

判定（按 §5 P1-4 的验收）：model 桶 −26% ≥ 20% 且输出 token 未上升 → **通过，规则保留**。
额外证据：处理组最慢一次（78.1 s）仍快于基线最快一次（98.6 s），两臂内部离散度（基线 87–102 s）小于臂间差；
两次处理组的报告完整（75 个文件全部枚举并给出表格），没有出现"为省 token 而漏答"。

**这是本计划唯一被 A/B 证实有效的优化**：同一任务墙钟 105.4 s → 77.7 s。剩余杠杆是请求数（每轮约
0.9 s）与隐藏推理（§0.2，需 P1-5）。

### 0.4 实验 #2：同轮批量提示词（A/B，**已否决并回滚**）

假设（来自 §0.2）：每次请求 ≈0.9 s 固定延迟，所以"把独立调用并进同一轮"应当省时间。
做法：coding 模式再加一条"独立调用并进一轮"的规则，同一任务 2 次基线（§0.3 的处理组）/ 2 次处理。

| 臂 | 任务 | 轮 | 调用 | outTok | model | 墙钟 |
|---|---|---|---|---|---|---|
| 基线（§0.3 处理组） | `d0c04585` | 21 | 107 | 15,925 | 72.0 s | 77.2 s |
| 基线（§0.3 处理组） | `eb6f815d` | 17 | 104 | 17,441 | 67.6 s | 78.1 s |
| **基线均值** | | 19.0 | 105.5 | **16,683** | **69.8 s** | **77.7 s** |
| 处理 | `911f595f` | 22 | 127 | 18,959 | 81.3 s | 89.4 s |
| 处理 | `fe861ecf` | 22 | 113 | 17,533 | 78.8 s | 89.7 s |
| **处理均值** | | 22.0 | 120.0 | **18,246** | **80.1 s** | **89.6 s** |
| **变化** | | **+16%** | +14% | +9% | **+15%** | **+15%** |

判定（§5 的验收：轮数下降且墙钟必须下降）：**失败**。轮数不但没降（22 vs 17–21），墙钟还涨了 15%，
输出 token 也涨了 9%——规则让模型"先规划再动手"，把省下的请求延迟又花回了推理上。规则已回滚，
`CliAgentPromptTest` 反向钉住（"Batch independent calls" 不得再出现）。

**这条负结果本身就是结论**：请求数由模型的任务规划决定，提示词层面的"多调用"指令不会减少轮次。
若还要压请求数，只能动模型行为以外的结构（例如把"列目录 + 读一批文件"做成一次复合工具调用，
让一轮里的一次调用覆盖 N 个文件），而不是让模型自己多调用。

> 本次 A/B 期间还遇到一次运行环境故障：处理组第 2 次（任务 `9c97e8c0`）跑到 74 次调用时后端进程消失，
> 任务被判 "ended before it reported completion"，该样本作废并重跑（`fe861ecf`）。后端日志没有崩溃行，
> 疑似被外部杀掉/OOM；这属于运行环境问题，与提示词无关，已记入 §10.4。

§1 的「间隔 1429 s」是本文最大的桶，但它的现有口径把三类时间混成一份，无法判断该由哪个优化项去拿：

| 混在间隔桶里的东西 | 现有打点 | 对应的优化项 |
|---|---|---|
| 每轮一次模型推理延迟 | 无（`onModelResponse` 只有回调，无耗时） | 减少轮数（P1-4）/ 更紧的提示词 |
| 每调用的记账开销（`onToolRequest` → `pageViewDeduper.decorate` → `onToolDecorated` → `onToolResult`，含 3 次 jsonl 追加与 memory 追加） | 无 | P1-3 ①② |
| 同轮内真实排队/往返 | 无 | P0-3 |

证据：`durationMs` 只量了 `coordinator.execute`（`AgentToolCallLoop.kt:315`/`:332`），所以附录 A 的三桶口径
必然把上面三类都塞进「间隔」。§1 举的例子（一轮 17 个调用 17.2 s、工具时间 0.45 s）说明这一轮 **97% 的
时间不在工具执行里**，但**无法**从现有数据判断它属于模型推理还是记账开销——两者的优化动作完全不同。

P0-0 只交付测量，不交付优化：

1. 在 `AgentToolCallLoop` 的 5 个回调（`onToolRequest` / `pageViewDeduper.decorate` / `onToolDecorated` /
   `onToolResult` / `onToolExecuted`）各埋一个 `nanoTime` 桶；
2. `onModelResponse` 记每轮模型耗时；
3. 新增一条 `round_timing` memory 事件 `{turn, modelMs, callbacksMs, toolsMs, calls}`（走同一套
   `AgentMemorySink`，不引入外部日志）；
4. 跑真实 coding 任务，四桶占比写进附录 B（归因测量）。**已跑 4 个（29.9 s / 72.0 s / 76.4 s / 102.2 s），
   结果见 §0.1 与附录 B.1。**

**门禁**（已触发，2026-11-13）：附录 B 有 4 个任务的四桶占比。「记账」占比 0.2% < 20% →
**P1-3 ①② 降级**，资源转"压低每轮请求成本"；「工具执行」占比 1.2% < 10% → **P0-3 不做**。

## 1. 现状基线（实测，不是估算）

| 观察项 | 数值 | 含义 |
|---|---|---|
| 4 个任务总墙钟 | 2863 s | 约 48 min |
| 构建耗时 | 1332 s（46%），18 次 `coding.mvnBuild` / `coding.verify` 调用 | 单次均值 ≈ 74 s |
| 其中「零编辑重复构建」 | 10 次 ≈ 756 s（占 `e1e1b5c0` 任务墙钟 34%，占 4 任务总墙钟 26.4%） | 上一次构建之后**没有任何写操作** |
| 非构建工具执行 | 103 s（4%） | 真正的工具工作只占 4% |
| 调用间固定开销 | 1429 s（50%），≈ 1.57 s/次 × 911 | 口径未拆：模型推理 + 逐调用记账 + 往返（见 §0） |
| 同一轮 17 个工具调用 | 17.2 s，其中工具时间 0.45 s | 该轮 97% 的时间不在工具执行里 |
| 同轮并发的收益上限 | ≤ 103 s（总墙钟 3.6%） | 构建调用是屏障，撬不动那 1332 s |

结论：**墙钟的大头不是"干活"**。优先级按**可兑现的收益**排：

去重构建（26%）→ 降低单调用/记账开销 → 减少轮数 → 消除串行（同轮工具执行仅 4%）→ 缩短单次构建。

中间两项（记账开销 vs 轮数）的先后由 P0-0 的归因数据定案；P0-3 排在第 4 位，且它的收益上限只有 3.6%。

## 2. 总体目标与完成定义（DoD）

在**不削弱验证强度**的前提下，把一个典型自开发任务（读 → 改 → 编译 → 测试 → 校验）的端到端墙钟降下来，
并让"时间花在哪"始终可度量。分两档验收——P0 集合的算术上限够不到完整目标，不拆档就会永远"未达标"：

| 档 | 范围 | 墙钟目标 | 可达性 |
|---|---|---|---|
| **P0 里程碑** | P0-1（零编辑构建归零） | 构建占比 ≥46% 的典型自开发任务：2863 s → ≤ 2110 s，即 **降 ≥ 25%** | 算术可保证（756 s / 2863 s = 26.4%）；已由真实任务验证缓存命中 |
| **完整目标** | P0 里程碑 + 压低每轮模型成本（P1-4 重定义） | **降 ≥ 40%** | 取决于模型往返能否压缩：实测模型占 77%，但"少轮多塞"会让每轮更贵（§0.1），需要单独一轮测量 |

完成定义（逐条可验证）：

1. **零编辑构建 = 0 次**：同一 workspace root 下，自上次成功构建后没有写操作时，第二次 `coding.mvnBuild`
   必须返回 `cached`（毫秒级），且结果里带指纹与时间；`goals=test` 永不短路。**已由真实任务验证（附录 B.2）**。
2. **可分桶度量**：每个任务能给出 **四桶**（build / tool / callback / model）时长，来自 `tool_executed` +
   `round_timing`（含末轮），不依赖外部日志。**已交付，四个真实任务已产出数据（附录 B.1）**。
3. **验证强度不降**：短路只允许出现在 `compile` 类检查上，`test` / `trapCheck` 永不缓存；`coding.verify`
   的 verdict 必须写明 `cached@<fingerprint>`、生成时间与**覆盖的模块列表**（合并闸门要用来判断"这次缓存的
   编译是否覆盖了本次改动"）。**已由真实 verdict 验证（附录 B.2）**。
4. ~~同轮只读工具并发~~：**删除**。实测工具桶 1.2%（3.2 s / 197 次调用），并发收益上限就是它。
5. **同轮多模块编译合并**：同一轮对 ≥2 个模块的编译变成一条 `mvn -pl a,b,c …`，且实测不比逐模块慢
   （单模块/`-am` 爆炸时自动退回）。
6. **压低每轮模型成本**（新增，替代原第 2 条）：在 read-heavy 任务上，同口径 bootstrap 下
   **model 桶墙钟下降 ≥ 20%**，且轮数不得以"每轮更贵"为代价（反例见附录 B.3）。
7. **回归**：`browser4-coding` 与 `browser4-agentic` 全量测试通过（既有失败
   `CliAgentPromptTest.hygieneFollowsMode` 除外）。

## 3. 已完成基线（不再重复实施）

| 能力 | 位置 | 现状 |
|---|---|---|
| 每调用耗时打点（含 `durationMs` / `callId`） | `AgentMemorySink.toolExecuted`（`AgentMemorySink.kt:41`） | 已有；缺回调/模型两桶与四桶报表（P0-0） |
| 批量只读并发 + 步骤缓存 | `BatchExecutor.run` / `runParallel:181-184`（`readOnly` 门禁、`concurrency`、`MAX_CONCURRENCY=4`）、`BatchToolExecutor.runStep:130-165`（步骤缓存，用 `ToolResultCache.shared`）、`BatchToolExecutor.kt:114`（`isReadOnly` 接线）、`ToolMetrics.recordBatch:224` | 已有，但**只对模型显式发出的 `system.batch` 生效**；同轮原生多调用串行，且完全不经过该缓存（`AgentToolCallLoop` 直接调 `coordinator.execute`） |
| 方法级副作用分类 | `AgentAccessPolicy.gateOf(domain, method)`（`:226`，**公开入口**）→ `ToolGate.READ/MUTATE/BUILD/EXEC`；矩阵在 `READ_METHODS:492` / `BUILD_METHODS:514` / `MUTATE_METHODS:517` / `EXEC_METHODS:525` | **半可用**：`READ_METHODS` 里 `requestAccess`(:507)、`protect`(:510) 是按"访问控制可放行"归的，实际会写状态——并发分类必须把它们当屏障。`CodingToolExecutor.WRITE_METHODS:2285` 只服务 `coding.explain` 的试跑诊断，`Effect.parse`(:1007) 解析的是模型传入的 `effect` 参数，两者都**不是**工具副作用分类器 |
| 文件变更日志 | `CodingAgentFileSystem.mutationsSnapshot()`（:302）、`mutationCount`（:296）、`mutationLog`（:295） | 已有，但**只能当缓存指纹的快速否定**：`clearMutations()` 会归零（:305-308，`beginTask:324-330` 每任务都调），且 `coding.shell` 里的 `sed`/`git apply`/格式化器完全绕过 `recordMutation`(:370-393) |
| 只读方法前缀表 | `ToolRateLimitPolicy.READ_ONLY_METHOD_PREFIXES`（`ToolRateLimitPolicy.kt:35`） | 已有，但它是 **`private`** 且语义是"限流上便宜"，不是"无副作用"——不要作为并发分类器 |
| Maven 构建 / 诊断解析 | `MavenBuildSupport.build`（:286）、`test`（:323）、`parseDiagnostics`（:145）、`BuildResult`（:32）、选择器回退（:360-381） | 单模块、无缓存、无合并；`buildMany` 必须兼容上述两条既有行为 |
| 确定性校验 | `VerificationRunner.verify`（`VerificationReport.kt:244` / `:270`） | 检查项串行（`:294`），无短路；`VerificationCheck`（`:16-33`）已带 `cached`/`fingerprint`（P0-1） |
| **构建短路（P0-1，本轮实施）** | `BuildCache.kt`（`BuildFingerprint` / `BuildCache` / `SourceDigest`）、`MavenBuildSupport.build` | 已有：内容指纹 + 进程级缓存；只缓存成功且 `goals ⊆ compile` 的构建；`beginTask` 失效；`-Dbrowser4.agent.buildCache.enabled=false` 逃生 |
| **轮次归因（P0-0，本轮实施）** | `AgentToolCallLoop.RoundTiming` + `onRoundTiming`、`RoundTimed`（memory 事件 `round_timing`） | 已有：每轮 model / tools / callbacks 三桶（含末轮）；四个真实任务已产出数据（附录 B.1） |
| `--unrestricted` 放开语法闸门 | `CodingAgentShell.validateCommandRaw` + `isSyntaxViolation` | 上一轮已交付，不再改 |

## 4. 剩余工作分解

### P0 — 立刻可做（拿掉最大的两块浪费）

| ID | 工作项 | 改动/动作 | 验收 |
|---|---|---|---|
| P0-1 | 编辑纪元 + 构建短路（**已实施 2026-11-13**，收益确定、后果最重） | 新增 `BuildFingerprint`（module、goals、skipTests、**内容摘要**、上游 `-am` 链的 `pom.xml` 摘要、maven/JDK 版本）；`MavenBuildSupport.build` 增加**进程级** `BuildCache`（按规范化 workspace root 分桶、容量有界、`beginTask` 时清该 root），命中直接返回 `BuildResult(cached=true, fingerprint=…)`，`output` 写 `cached (no source change since <ts>)`；`VerificationRunner.verify` 的 `compile` 检查复用同一缓存（`compileCheck` 本就调 `maven.build`，`:350`）。**四条硬约束**：① 只缓存 `success && !timedOut`，失败/超时永不命中；② 只在 `goals ⊆ compile` 且 `skipTests=true` 时短路；③ 指纹主体只能是内容摘要，`fs.mutationCount` 只能作快速否定（会归零、且绕过 `coding.shell` 写）；④ workspace 内**任何来源**的写（含 `coding.shell` 的 `sed`/`git apply`）都使缓存失效；`-Dbrowser4.agent.buildCache.enabled=false` 逃生 | 新增 `BuildShortCircuitTest`：① 无编辑二次 build 为 `cached=true` 且**不启动 mvn**（fake shell 计数为 1）；② `write` 之后重新构建；③ `pom.xml` 变更重新构建；④ 跨 workspace root 不串味；⑤ `--no-cache` 逃生开关生效；⑥ **`coding.shell` 写文件后必须重新构建**（唯一能防假绿的用例）；⑦ 失败/超时的构建不进入缓存 —— **12 个用例全绿**，见 §10 |
| P0-2 | 同轮多模块编译合并（**未开始**，先测再选型） | 第一步测量：`mvn -pl a,b -am compile` 在本仓库会拉进多少个 reactor 模块（`-am` 可能把"2 × 30 s"变成"1 × 120 s"）。第二步二选一：**(A) loop 级融合** —— 同一轮的多次 `coding.mvnBuild` 合成一条命令（需解决 2 个 `ToolExecutionRequest` → 1 个结果消息的映射）；**(B) 契约变更** —— `mvnBuild` 加 `modules: List<String>`（工具面与提示词同步改，属 P1-4 范畴）。`MavenBuildSupport` 侧新增 `buildMany(shell, modules, goals, …)` + `MergedBuildResult(modules, perModule: List<BuildResult>)`（不给 `BuildResult` 加可空 `modules`，否则 `format()`/`buildResultJson`/verdict 证据三处都出现"哪个 module"的歧义），诊断按模块归并。必须兼容既有两条不变量：命令形状（`mavenTestCommand` 的 goal-first 注释，`:507-519`）与 `runMavenWithSelectorFallback`（`:360-381`）的选择器回退——**部分模块选择器失败时整条重试还是拆开，待定** | 新增 `MergedBuildTest`（命令行组装、诊断归并、per-module exit 摘要、部分选择器失败的重试语义）；附录 B 记一次真实双模块墙钟对比 + `-am` reactor 模块数；模块数超阈值时退回逐模块 |
| P0-3 | 同轮只读工具并发（**数据否决，不做**） | **收益上限实测 3.2 s / 197 次调用（1.2%）**，见 §0.1 与附录 B.1——低于 §0 的 10% 门槛，整项不做。以下内容仅作为"如果将来某类任务工具桶变大"的预案保留：前置修复（① `ToolExecutionCoordinator` 增加 `suspend fun executeSuspend`——现在 `execute` 内部 `runBlocking(Dispatchers.IO)`（`:36-41`），塞进 `async` 会占死 `Dispatchers.Default`（2 核 CI 上"并发 4"只有 2）；② 修 `catch (e: Exception)` 连 `CancellationException` 一起吞（`:54-56`），否则用户取消会变成某条工具的 `[fail]`）。然后 `AgentToolCallLoop.kt:313` 改"分段并发"：③ 分类器用 `AgentAccessPolicy.gateOf`（`requestAccess`/`protect` 强制当屏障；`null` 一律串行）；④ 连续只读段 `supervisorScope { requests.map { async { executeSuspend(it) } }.awaitAll() }`，调度用 `Dispatchers.IO.limitedParallelism(n)`，`n` 默认取 `BatchExecutor.MAX_CONCURRENCY`，`-Dbrowser4.agent.tool.parallelism` 只作逃生/灰度开关；⑤ 写/构建是屏障，之前并发段先收敛；⑥ `onToolRequest`/`onToolResult`/`onToolExecuted` 按**请求顺序**串行触发，因此调用方的 `roundToolRecords`（`RobustBrowserAgent.kt:1151` 的 `mutableListOf`）无需改并发容器——但要有测试钉住 | 若将来重启：`ToolCallParallelismTest`：① 4 个只读并发（fake 注入延迟，断言**轮墙钟** < 串行基线 60%）；② `只读 → 写 → 只读` 顺序正确（写为屏障）；③ 结果消息顺序 = 请求顺序；④ 回调抛异常不影响同批其它结果（`supervisorScope` + 逐条 `runCatching`）；⑤ 并发下 `roundToolRecords` 不丢记录（[2026-10-22 评审并发正确性第 16 条](20261022-agentic-executor-review.md)）；⑥ 取消传播不被吞掉 |

说明：P0-1 可独立开工；P0-3 的前置修复（`executeSuspend` + 取消语义）与 P0-2 的测量可与 P0-1 并行，但
P0-3 本体等 P0-0 的结论；三者都要先补各自的单测，再跑模块全量测试。

### P1 — 结构性收益

| ID | 工作项 | 改动/动作 | 验收 |
|---|---|---|---|
| P1-1 | 只读调用结果复用 | 缓存**已经存在**：`ToolResultCache.shared`（`ToolResultCache.kt:215`），`BatchToolExecutor.runStep`（`:130-165`）已接。缺的是 in-loop 接线（`AgentToolCallLoop` 直接调 `coordinator.execute`，绕过传输层缓存）与**失效语义**：TTL+session 是为浏览页面设计的，对 `coding.read` 是错的——必须改成"coding 写纪元使缓存失效"；并发/缓存判据要与 `BatchToolExecutor.isReadOnly`（`:197-201`）共用一份，避免 `system.batch` 与原生多调用给出不同答案 | 同轮重复读命中缓存，`tool_executed` 结果标 `cached`；写工具使缓存失效（含 `coding.shell` 写） |
| P1-2 | `coding.verify` 并发 + 缓存复用 | 注意默认检查集**已经**是 compile + repo-consistency（`VerificationReport.kt:285-289`），"默认走 fast"是空转；真实增量只有两条：① `compile` 与 `repo-consistency` 并发（互不依赖，后者纯读）；② compile 复用 P0-1 的缓存。另需给 `VerificationCheck` 加 `cached` 字段并同步三处消费方：`toJsonMap`/`fromJson`、`RobustBrowserAgent.recordVerificationVerdict`（`:2656-2663`）与 `actualVerdictJson` 比对（`:2413`）、worktree 合并闸门 `IsolationGate` | 新增 `VerifyLayeringTest`：`compile` 与 `repo-consistency` 并发（fake 计时）、cached 字段进 JSON 且 survive `fromJson`、finish gate 接受带 `cached@<fp>` 的 verdict |
| P1-3 | 降低每次调用开销（**数据否决 ①②**） | 实测回调桶 0.2%（0.6 s / 197 次调用）——① 记账合批、② 结果引用压缩的收益上限就是这 0.6 s。保留 ③（`system.listTools` 结果按轮复用）作为 P1-4 的一部分，因为它的价值是"少一次请求"而不是"少一次记账" | 同口径重测：回调桶本身不设目标；③ 计入 P1-4 的 model 桶 |
| P1-4 | **成本模型 + 两个受控实验**（实验 1 已通过，实验 2 待做） | 成本公式已给出（§0.2）：请求 ≈0.6–0.9 s/次、输出 ≈3.3–4 ms/token、输入≈0。**实验 1（已完成，§0.3）**：coding 提示词加输出预算规则 → 同一任务 outTok −22.6%、model −26.1%、墙钟 −26.3%，规则保留。**实验 2（待做）**：减少请求数（每合并一轮省约 0.9 s，但合并会改变隐藏推理量，必须 A/B）。多文件工具（`readFiles`/`writeBatch`）**不再是本项内容**——它减的是输入，而输入已免费 | 实验 1：✅ model −26%（≥20%）且输出 token 未上升；实验 2：同口径 A/B，轮数下降且**墙钟必须下降**，否则判失败 |
| P1-5 | provider 参数透传（**前置在 base 库，当前不可做**） | 隐藏推理占 model 桶约六成，只有 `reasoning_effort`/thinking 开关能压它。本仓库的 `BrowserChatModel` 来自外部件，无透传缝；`/v1/models` 只列出 `deepseek-flash`、`deepseek-v4-pro`（配置里的 `deepseek-v4-flash` 不在目录内，见附录 B.6） | 需 base 库提供自定义参数；落地后用同一任务 A/B 出"reasoning on/off"的 model 桶对比 |

### P2 — 中期（要外部前提）

| ID | 工作项 | 改动/动作 | 验收 |
|---|---|---|---|
| P2-1 | 增量编译 | 用 `ModuleMap` 只编译受影响模块；构建命令加 `-o`（离线，前提是本地仓库已就绪）。**注意**：per-task worktree 的 `target/` 是冷的，增量编译救不了首次构建；`browser4.agent.isolation.reuse=session`（`SessionWorktree`）开启后同一会话跨任务共享 `target/`，收益模型会再变，评估须分两种形态 | 单模块改动只编译该模块及其依赖；两种 isolation 形态下的墙钟对比 |
| P2-2 | Maven 守护进程 / 并行 | 评估 `mvnd` 或 `mvn -T 1C`（需先确认插件线程安全）；worktree 冷构建会放大 mvnd 收益，评估须用 worktree 形态 | 冷构建对比数据 + 回滚开关 |
| P2-3 | 度量看板 | 任务报告与 `coding.verify` verdict 里带 `timings{build,tool,callback,model}` 四桶 | 一个真实任务的报告可直接读出四桶占比 |

## 5. 顺序与门禁

| 顺序 | 阶段 | 前置 | 门禁 | 估时 |
|---|---|---|---|---|
| 1 | P0-0 归因测量（**已实施，数据已产出**） | 无 | `RoundTimingTest` + 四桶数据（附录 B） | 0.5 d |
| 2 | P0-1 构建短路（**已实施**） | 无 | `mvn test -pl browser4-coding -am -Dtest=MavenBuildSupportTest,BuildShortCircuitTest -Dsurefire.failIfNoSpecifiedTests=false` + `browser4-coding` 全量；真实任务已见到 `cached@sha256:…` | 0.5–1 d |
| 3 | **P1-4 实验 1：输出预算提示词**（✅ 已通过并保留） | P0-0 数据 | 2+2 次同任务 A/B：outTok −22.6%、model −26.1%、墙钟 105.4→77.7 s（§0.3） | 0.5 d |
| 4 | P1-4 实验 2：减少请求数（❌ **A/B 否决并回滚**） | P0-0 数据 | 同口径 A/B：轮数 **+16%**、墙钟 **+15%**（§0.4）；提示词层面的批量指令不减少轮次 | 0.5 d |
| 4 | P1-1 只读结果复用（写纪元失效） | P0-3 的分类器不做了，改为复用 `gateOf` + `ToolResultCache` | 同轮重复读命中、写使失效 | 0.5–1 d |
| 5 | P1-2 verify 并发/缓存复用 | P0-1 | `VerifyLayeringTest` + `repo-consistency` | 0.5 d |
| 6 | P0-2 合并构建 | 附录 B 的 reactor 规模（已实测：2 模块 / 10 模块） | `MergedBuildTest`；只在"同依赖子树"合并 | 0.5 d |
| 7 | ~~P0-3 只读并发~~ | — | **数据否决：工具桶 1.2%，上限 3.2 s/197 调用** | 不做 |
| 8 | ~~P1-3 ①② 记账合批~~ | — | **数据否决：回调桶 0.2%，上限 0.6 s** | 不做 |
| 9 | P2-* | P0/P1 落地后按收益排序 | 冷/热构建对比数据（分 isolation 形态） | 各 1–2 d |

每个阶段都必须过 `coding.verify(module="<被改模块>", checks="compile,repo-consistency")`——`module` 必填，
否则 compile 记 `ran=false`、报告永远不过（`VerificationReport.kt:78`、`:322-330`）；涉及 shell/驱动改动再加
`trapCheck`。

新测试必须带 `@Tag`（纯单测 `Fast`；真的起 mvn / 起进程的 `Slow` 或 `Heavy`，否则会进 PR 门禁），并发测试
另加 `@Timeout`（先例 [mcp-interface-hardening-plan.md:558](mcp-interface-hardening-plan.md)："并发测试宁可
失败也不能拖垮流水线"）。

## 6. 测试矩阵（新增部分）

| 测试类 | 覆盖点 | 所在模块 |
|---|---|---|
| `BuildShortCircuitTest` | 缓存命中/失效、指纹含内容摘要、跨 root 隔离、逃逸开关、**失败/超时不缓存**、**`coding.shell` 写后必重编** | `browser4-coding`（`MavenBuildSupport` 层）＋ `browser4-agentic`（`coding.mvnBuild` 端到端） |
| `MergedBuildTest` | `buildMany` 命令组装、诊断归并、per-module exit 摘要、部分选择器失败的重试语义、超阈值退回 | `browser4-coding` |
| `ToolCallParallelismTest` | 只读并发（轮墙钟）、写为屏障、消息顺序、回调异常隔离、`roundToolRecords` 不丢记录、取消传播 | `browser4-agentic` |
| `VerifyLayeringTest` | compile/repo-consistency 并发、`cached` 字段进 JSON、finish gate 接受 `cached@<fp>` | `browser4-coding` |
| `RoundTimingTest` | `round_timing` 事件字段齐全、四桶之和 ≤ 轮墙钟 | `browser4-agentic` |
| `CodingBatchToolsTest` | `readFiles` / `writeBatch` 的语义与顺序 | `browser4-agentic` |
| 回归 | `browser4-coding` 全量、`browser4-agentic` 全量、`CliAgentPromptTest` | 两个模块 |

## 7. 已关闭 / 待关闭的决策

| # | 决策 | 结论或倾向 |
|---|---|---|
| 1 | 并发分类器来源 | **已定**：`AgentAccessPolicy.gateOf`（公开、含 BUILD/EXEC/READ/MUTATE）为准；`requestAccess`/`protect` 强制当屏障；`gateOf == null`（浏览器域等）默认串行。前缀表 `ToolRateLimitPolicy.READ_ONLY_METHOD_PREFIXES` 是 `private` 且语义是限流，不用；`CodingToolExecutor.WRITE_METHODS` 不是分类器 |
| 2 | `cached` verdict 是否算"已通过" | **算**，但必须带 ①指纹 ②生成时间 ③**覆盖的模块列表**（worktree 合并闸门用它判断"缓存的编译是否覆盖本次改动"）；`test`/`trapCheck` 永不缓存；`cached` 必须计入报告 |
| 3 | 合并构建的模块上限 | 先用实测 reactor 规模定 N，而不是拍 3；优先评估 `-pl a,b`（不带 `-am`） |
| 4 | 默认并发度 | **不做（数据否决）**：工具桶 1.2%，`ToolCallParallelismTest` 不必写。`BatchExecutor.MAX_CONCURRENCY` 保持只服务 `system.batch` |
| 5 | 是否引入 `mvnd` | 先做 P2-1 再评估；评估必须在 worktree 冷 `target/` 形态下测 |
| 6 | P0-2 的触发形态 | 待附录 B：loop 级融合 vs 工具契约 `modules: List<String>` |

## 8. 风险与回滚

| 风险 | 触发条件 | 缓解 |
|---|---|---|
| **缓存掩盖真实失败（唯一的假绿路径）** | 指纹过粗（只用 mtime/大小），或写路径绕过指纹（`coding.shell` 的 `sed`/`git apply`、格式化器） | 指纹用**内容摘要**；失败/超时不缓存；`--no-cache` 开关；`test`/`trapCheck` 不缓存；专项用例 ⑥（shell 写后必重编） |
| 缓存跨任务串味 | `mutationCount` 被 `beginTask` 归零后与旧条目撞号 | 指纹只用内容摘要；缓存按规范化 root 分桶；`beginTask` 清该 root |
| 并发破坏浏览器/会话状态 | 错误地把有副作用工具判为只读 | 白名单式判定（`gateOf`，默认串行）；`requestAccess`/`protect` 例外；写/构建为屏障 |
| 并发下调用方集合被并发写 | `roundToolRecords`（`RobustBrowserAgent.kt:1151` 的 `mutableListOf`，`onToolResult` 里 `+=`） | 回调按请求顺序串行触发 + 专项测试（[2026-10-22 评审第 16 条](20261022-agentic-executor-review.md)） |
| 取消被当成工具失败 | `ToolExecutionCoordinator` 的 `catch (e: Exception)` 吞 `CancellationException`（`:54-56`） | 前置修复：`CancellationException` 重新抛出，先修再并发 |
| 并发度在少核 CI 上不达标 | `execute` 的 `runBlocking` 占 `Dispatchers.Default` 线程 | 先加 `executeSuspend`，再用 `Dispatchers.IO.limitedParallelism(n)` 调度 |
| 构建与工具并发抢 CPU | 同一轮同时跑构建与只读工具 | 构建视为屏障（与写工具同级） |
| 合并构建失败定位变难 / 变慢 | 多模块一条命令，诊断混杂；`-am` 拉进大量上游 | `parseDiagnostics` 按模块归并、保留 per-module exit 摘要；先用附录 B 的 reactor 规模数据决定是否带 `-am` |

回滚：P0-1 / P0-3 / P0-2 各自独立开关（开关名是提议，随实现定稿：
`-Dbrowser4.agent.buildCache.enabled=false`、`-Dbrowser4.agent.tool.parallelism=1`（默认取
`BatchExecutor.MAX_CONCURRENCY`，仅作逃生用）、`-Dbrowser4.agent.build.merge=off`），任一开关关闭即回到当前行为。

## 9. 明确不做

- 不做多模型 / 多 agent 并行推理（收益不明，成本高，且会打乱现有的压缩与记账逻辑）。
- 不改 MCP 协议：服务端仍按单请求处理；并发只发生在 agent 循环内部。
- 不做任何"跳过测试也返回 passed"的捷径；不为省时间弱化 `trapCheck` / `repo-consistency`。
- 不动 `--unrestricted` 的语法闸门语义（上一轮刚收敛完成）。
- **不做同轮只读并发（P0-3）**：实测工具桶 1.2%，上限 3.2 s / 197 次调用（附录 B）。
- **不做记账合批（P1-3 ①②）**：实测回调桶 0.2%，上限 0.6 s（附录 B）。
- **不以"轮数下降"作为收益证明**：第 4 个真实任务轮数更少（14 vs 18）但墙钟更长（102 s vs 76 s），
  压低模型时间必须靠每轮请求变便宜，而不是合并调用。
- **不再在提示词里要求模型"多调用/批量调用"**：A/B 已否决（§0.4 实验 2：轮数 +16%、墙钟 +15%）。
  请求数要降只能靠复合工具（一次调用覆盖 N 个文件），不靠指令。

## 10. 实施记录（2026-11-13）

### 10.1 P0-1 构建短路 — 已落地

| 落点 | 内容 |
|---|---|
| `browser4-coding/.../BuildCache.kt`（新） | `BuildFingerprint`（module / goals / skipTests / `sourceDigest` / launcher / toolchain，`id` = 指纹前 16 位）；`BuildCache`（进程级、按规范化 root 分桶、`maxEntries=64`、只存成功、digest 不匹配即丢弃；`shared` 单例；`-Dbrowser4.agent.buildCache.enabled`）；`SourceDigest`（`*.kt/*.kts/*.java/*.xml` + `.mvn/wrapper/maven-wrapper.properties`，跳过 `target/build/out/bin/node_modules/.git/.gradle/.idea/.vscode/.mvn/.browser4/.b4scratch/.b4inputs/.claude/.worktrees`，跟随链接 + 已访问目录去重防环，>20000 文件则不缓存） |
| `MavenBuildSupport` | `BuildResult` 增 `cached/fingerprint/builtAt` + `stamp()`；构造参数 `cache` 与测试缝 `commandRunner`（证明"命中不启动 mvn"的唯一办法）；`build()` 先算指纹再查缓存，成功后写入；`format()` 对缓存命中输出 `✓ Build succeeded (cached, fingerprint …, no source change since …)`（非命中路径文本逐字不变）；`isCacheableBuild`（`skipTests && goals ⊆ compile`） |
| `VerificationRunner.compileCheck` | 命中时 `evidence = cached@<fp> (no source change since …)`，并填 `VerificationCheck.cached/fingerprint`（JSON 与 round-trip 同步） |
| `CodingToolExecutor.buildResultJson` | `cached` / `fingerprint` / `builtAt` 三个字段，机器消费方不必解析散文 |
| `CodingAgentFileSystem.beginTask` | 任务边界调用 `BuildCache.shared.invalidate(workspaceRoot)`（内容指纹已保证不会误命中，这步只是不让长命 JVM 攒死桶） |

**指纹成本（实测，本仓库）**：1345 个相关文件 / 11.6 MB；枚举 ~1.65 s（冷、PowerShell 逐文件）、摘要 ~0.25 s。
即每次**可缓存**构建额外 0.3–2 s，相对单次 74 s 构建可忽略；不可缓存的调用（`test`/`verify` 非 compile 目标）完全不算指纹。

**已知限制（写进 §8 之外，实现层面必须知道）**：

1. 缓存断言的是"这些源码能编译"，**不是** `target/classes` 仍然存在。手工删过 `target/` 后命中缓存，不会重新产出产物。
2. 指纹覆盖整个 workspace root（保守），任一模块的源码变化都会使该 root 全部条目失配——正确性优先，代价是多一次重编。
3. 指纹不覆盖 `src/**/resources` 与 `-P` profile，只覆盖"编译输入"；这与缓存只服务 `compile` 目标一致。

### 10.2 P0-0 轮次归因 — 已落地并已产出真实数据

| 落点 | 内容 |
|---|---|
| `AgentToolCallLoop.RoundTiming` + `onRoundTiming` | 每轮返回前上报 `{round, calls, modelMs, toolsMs, callbacksMs}`；`toolsMs` = 工具体，`callbacksMs` = 5 个观察点（`onToolRequest` / `decorate`+`onToolDecorated` / `onToolResult` / `onToolExecuted`）的计时之和；观察点抛异常只记日志，绝不影响任务 |
| 末轮收束（首轮实跑后补） | 纯文本收束轮也上报（`calls = 0, toolsMs = 0`）：首个真实任务 `905cbeeb` 显示它在 18 轮里独占约 28 s，不测就会系统性低估模型桶 |
| `MemoryEvent.RoundTimed`（`round_timing`） | 新事件类型；`DefaultMemoryQueryService` / `SqliteMemoryQueryIndex` 的 `eventText` 补分支；`AgentMemorySink.roundTimed(...)` |
| `RobustBrowserAgent.buildCliToolLoop` | `onRoundTiming` 同时写 `cliLoopTracer.logEvent("round_timing", …)` 与 memory 事件 |
| `docs-dev/copilot/scripts/analyze-agent-timing.ps1`（新） | 四桶复算脚本（`-Last N` / `-TaskId <id>`），同时做零编辑重建检测 |

**数据结论**：见 §0.1 与附录 B —— 模型 77%、build 10%、工具 1.2%、回调 0.2%。
P0-3 与 P1-3 ①② 因此被数据否决（§5、§9 已改）。成本公式与 token 归因见 §0.2 / 附录 B.5。

| 追加落点（P1-4 第一步） | 内容 |
|---|---|
| `RoundTiming.outTokens` / `cachedInTokens`（+`RoundTimed`、sink、tracer） | 每轮同时上报 token：model 桶与输出 token 的关系（§0.2 的 R²=94.7%）从"看得出来"变成"可复算" |
| `RoundTimingTest` | 新增 token 用例（provider 报用量时取真实值、不报时为 0），事件往返含 token 字段；共 6 例全绿 |
| `scripts/analyze-agent-timing.ps1` | 增 `outTok`/`cachedIn` 列 + `modelMs = a + b·outTokens` 自动拟合（≥5 轮带 token 时生效） |

### 10.3 门禁证据

| 命令 | 结果 |
|---|---|
| `mvn -q -DskipTests -pl browser4-coding,browser4-agentic -am compile` | exit 0 |
| `mvn -pl browser4-coding,browser4-agentic -am -Dtest=BuildShortCircuitTest,MavenBuildSupportTest,VerificationReportTest,RoundTimingTest,AgentToolCallLoopTest -Dsurefire.failIfNoSpecifiedTests=false test` | `browser4-coding` 37 tests（`BuildShortCircuitTest` 12 + `MavenBuildSupportTest` 16 + `VerificationReportTest` 9）全绿；`browser4-agentic` `AgentToolCallLoopTest` 28 + `RoundTimingTest` 5 全绿 |
| 同一命令重跑（`RoundTimingTest` 修正后） | `Tests run: 59, Failures: 0, Errors: 0` — BUILD SUCCESS |
| 收尾重跑（局部变量改名后）`mvn -pl browser4-coding -am -Dtest=BuildShortCircuitTest,MavenBuildSupportTest test` | `Tests run: 28, Failures: 0` — BUILD SUCCESS（reactor 仅 2 模块，见附录 B） |
| 末轮计时改动后 `mvn -pl browser4-agentic -Dtest=RoundTimingTest,AgentToolCallLoopTest test` | `Tests run: 33, Failures: 0` — BUILD SUCCESS |
| 真实任务 · P0-1 端到端 | `agent run --coding`：`mvnBuild` 真跑 Maven（exit 0）→ `verify` 的 compile 返回 `cached@sha256:248d2b574`；第二次 `mvnBuild` 输出 `✓ Build succeeded (cached, …)`（附录 B.2） |
| 真实任务 · P0-0 端到端 | 4 个任务、59 轮、197 次调用，`round_timing` 全部落库；四桶 = model 77% / build 10% / tool 1.2% / callback 0.2% |

### 10.4 本轮发现的运行环境事实

- **未提交的新文件在隔离 worktree 里不可见**：真实任务 `e76c517c` 尝试用 `coding.replace` 修改本轮新增的
  `BuildCache.kt` 被拒（`PATH_DENIED / PATH_OUTSIDE_WORKSPACE`）——worktree 只检出 HEAD。
  读路径更宽（能看到主检出），写路径只限 worktree。建议记入
  [worktree 隔离计划](20261113-self-dev-worktree-isolation-plan.md)，本计划不受影响。
- **`--access read-build` 足以跑 build+verify**：`259a2651` 在只读构建档下完成编译与校验，证明
  "只读任务也能自证编译"这条链路可用。
- **后端进程可能中途消失**（2026-11-14 00:30，任务 `9c97e8c0`）：跑到 74 次调用时后端没了，任务被判
  "ended before it reported completion — no live runner owns it"。`pulsar.log` 里没有崩溃行，下一次读状态时
  CLI 又拉起了新后端（新 pid）。本次按样本作废重跑处理，但这是一个**未定因的健壮性问题**：
  任务状态机没有"runner 消失"的自愈路径，只把它报成失败。建议单独开一单查（OOM / 外部 kill / 端口冲突三者之一）。

> 注：真实的 `coding.mvnBuild` / `coding.verify` 端到端已在 `259a2651` / `e76c517c` 两个任务里跑过真 mvn
> （附录 B.2）；本节的门禁表只覆盖单测与包级回归。

### 10.5 P1-4 实验 1 — 已落地并 A/B 通过

| 落点 | 内容 |
|---|---|
| `CliAgentPrompt.hygiene(coding)` | `### Context hygiene` 首条新增**输出预算**规则：输出 token 是贵的那一半（每 1k ≈ 4 s）、工具轮不写散文、最终报告不复述文件内容；浏览模式不受影响 |
| `CliAgentPromptTest.outputTokenBudgetIsStated` | 钉住规则文本与"只出现在 coding 模式"；`CliAgentPromptTest` 13 例 + `RoundTimingTest` 6 例全绿 |
| 度量 | 同任务 2+2 A/B：outTok −22.6%、model −26.1%、墙钟 105.4 → 77.7 s（附录 B.7） |
| 证据留痕 | `round_timing` 的 token 字段 + 脚本自动拟合 `modelMs ≈ 942 + 3.30·outTokens`（R² 90.9%） |

### 10.6 N3 度量随任务结果交付 — 已落地并端到端验证

| 落点 | 内容 |
|---|---|
| `RunTimings` / `RunTimingsAccumulator`（agentic，新） | 四桶累计器：`onTool` 把 build 工具（`coding_mvnBuild`/`coding.verify` 两种写法）从工具桶拆出，`onRound` 记 model/callback/rounds/tokens；负值钳零；`toMap()` 产出结果 JSON 的形状 |
| `RobustBrowserAgent` | `lastRunTimings`（每次 run 重置）+ 在 `onToolResult`/`onRoundTiming` 两个已有点位累计；`RoundTiming`/`RoundTimed` 增 `inTokens`，托管到事件与 tracer |
| `AgentTaskStatus` → `CommandStatus` → `CommandResult` | 新增 `timings: Map<String, Any?>?`，在 `detachedSnapshot`、`toCommandStatus`、`toResult` 三处透传；`StatefulAgentRunner` 从 agent 取值 |
| `agent result` 渲染（Rust） | 新增 `Timings` 段（单行：`4 rounds · model 4.8s · tools 59ms · callbacks 11ms · tokens 25.2k in / 18.3k cached in / 554 out`），`timings` 加入 `RESULT_KEYS`（不再落进 unknown 块） |

**端到端验证**（真实任务 `348ca7a6`，8.1 s）：`agent result` 的人读输出与后端 `CommandResult.timings` 如下，
并与事件日志复算（`analyze-agent-timing.ps1`）**逐项一致**：

| 指标 | 任务结果 `timings` | 事件日志复算 |
|---|---|---|
| rounds | 4 | 4 |
| buildMs | 0 | 0（buildN=0） |
| toolMs | 59 | 59 |
| callbackMs | 11 | 11 |
| modelMs | 4,779 | 4,779 |
| tokens | in 25,226 / cachedIn 18,304 / out 554 | cachedIn 18,304 / out 554 |

测试：`RunTimingsTest` 7 例、`AgentTaskStatusTest` 25（含 timings 快照）、`CommandStatusPageStatusTest` 11（含 timings 透传）、
CLI `agent_result` 28 例（含 Timings 段渲染与"无度量不渲染"）。


## 11. 后续开发计划（2026-11-14）

### 11.1 已冻结的约束（后续任何优化先对照这张表）

| 事实（实测） | 数值 | 对计划的约束 |
|---|---|---|
| 成本公式 | `modelMs ≈ 600 + 4.05×outTokens`（R² 94.7%，n=59） | 输出 token 是贵的那一半；每 1k ≈ 4 s |
| 每次请求固定延迟 | 0.6–0.9 s（63 次 → 38 s） | 减请求有效，但**只能靠结构**（实验 2 证明提示词不行） |
| 输入 | ≈ 0（prompt cache 命中 92.6%） | **不再为"减输入"立项**：结果裁剪、多文件合并读都不是延迟优化 |
| 四桶 | model 77% / build 10% / tool 1.2% / callback 0.2%（build-heavy 任务 build 46%） | 已否决 P0-3、P1-3①②；P0-1 是唯一确定的大块 |
| 已验证有效 | 输出预算提示词：墙钟 −26%（105.4 → 77.7 s） | 保持；新增提示词规则必须 A/B |
| 不可控 | 隐藏推理 ≈ model 桶六成 | 只有 provider 参数能压 → 列为 N2（前置 base 库） |

### 11.2 计划项（按可兑现收益排序）

| ID | 项 | 为什么（数据支撑） | 动作 | 验收 | 估时 |
|---|---|---|---|---|---|
| **N1** | 复合工具 `coding.readFiles`（一次调用读 N 个文件）（✅ **已实施**，A/B 无收益，见 B.9） | 请求≈0.6–0.9 s/次；实验 2 证明提示词压不动轮数；结构化的批量读至少能消除"逐文件读撑大上下文"的路径 | `readFiles(paths, startLine, maxLinesPerFile, maxTotalChars)`：4.6 KB 硬预算、逐文件截断、跳过清单；注册进 `READ_METHODS`/`TOOL_EFFECTS`/`CLI_CORE_CODING_METHODS` | ✅ 9 例单测 + 25 例矩阵/副作用守卫；❌ A/B 未显示墙钟收益（两臂都采纳，轮数 10–22 波动），**提示词行已回滚** | 1 d |
| **N2** | provider 参数透传（=`P1-5`） | 隐藏推理约占 model 桶六成（最典型一轮 3,948 输出 token、可见输出约 100 字符） | base 库给 `BrowserChatModel` 加自定义参数缝；本仓库加 `browser4.agent.llm.extraParams`（默认空） | `reasoning_effort=low/off` 的 A/B：model 桶与墙钟对比 | 本仓库 0.5 d（前置 base 库） |
| **N3** | 把度量搬进任务结果（✅ **已实施**，见 §10.6） | `agent result` 原先只有 `elapsedMs`/`effectiveTokenBudget`，看不到轮数、token、四桶；每次分析都要跑脚本 | 任务结果 JSON 增 `rounds`/`tokens{in,cached,out}`/`timings{build,tool,callback,model}` | ✅ 一次真实任务的结果里直接读出四桶，且与事件日志复算逐项一致 | 0.5 d |
| **N4** | `cached` verdict 带上"覆盖的模块列表" | worktree 合并闸门要判断"这次缓存的编译是否覆盖本次改动"；当前 verdict 只有 `cached`/`fingerprint` | `VerificationCheck.modules` + `IsolationGate` 消费 + 用例 | 合并闸门用例：缓存命中但未覆盖改动 → 拒绝合并 | 0.5 d |
| **N5** | P0-1 指纹收敛（可选） | 现按整个 workspace 摘要，换模块必失配；命中率未测 | 先统计真实任务的缓存命中/失配原因，再决定是否按 module+上游 pom 收敛 | 命中率提升 vs 误失效（要有数据） | 1 d |
| **N6** | 故障：runner 消失 | 任务 `9c97e8c0` 后端中途消失、判失败、日志无崩溃行（§10.4） | 单独 issue：存活检测 / 更明确的错误 / 排查 OOM·端口·外部 kill | 可复现或排除；任务不再"无声失败" | 0.5–1 d |
| **N7** | worktree 可见性 | 未提交文件"读得到、写不了"（B.3），真实任务 `e76c517c` 被 `PATH_DENIED` | `WorkspaceIsolation` 把未跟踪文件也带入 worktree（`stash -u` 或复制） | 用例 + 真实任务能编辑未提交文件 | 1 d |

### 11.3 降级 / 不做（已有数据判定）

- **P1-1 只读结果复用**：工具桶 1.2%，收益上限就是它 → 降到"顺手做"，不单独排期。
- **P1-2 verify 并发**：`repo-consistency` 实测 103–132 ms，与 compile 并发无收益 → 只保留 N4（正确性）。
- **`coding.writeBatch`**：与 N1 同一前提（"批量调用能省轮"），而 N1 的 A/B 已表明轮数由模型规划决定（同指令 10–22 轮）；写入还受"先读后改"的依赖限制。不立项，除非将来出现真实的批量写场景数据。
- **P0-2 合并构建**：合并只省一次 Maven 启动（全 reactor `validate` ≈ 2 s），却可能把 2 个模块变 10 个上游 → 在真实任务出现"同一轮多次多模块构建"之前不立项。

### 11.4 执行顺序与复测方式

顺序：**N3 → N1 → N4 →（N2 等 base 库）→ N5 → N6 / N7**。N3 先做的理由：它把 N1/N2 的验收从"跑脚本"变成"读结果"。

每项门禁：最小作用域单测 + 一次真实任务。真实任务现在可以直接等结果（**`agent run <task> --coding --wait --wait-timeout 900`**，
`--wait` 会阻塞到完成；本轮早期用的是手写轮询，属于多余步骤）：

```bash
pwsh ./b4w.ps1 agent run "<task>" --coding --wait --wait-timeout 900
pwsh docs-dev/copilot/scripts/analyze-agent-timing.ps1 -Last 3      # 四桶 + token + 成本模型拟合
```

### 11.5 每项的最小验收口径（避免再次"跑通了但没省时间"）

1. 任何优化都必须给出**同任务、同模型、≥2 次/臂**的 A/B，报告 outTok、rounds、model ms、墙钟四项。
2. 验收以**墙钟**为准；轮数/token 只作为解释项（实验 2 就是轮数解释不了的例子）。
3. 报告里必须写明样本量与离散度；n=1 的"改进"不作数。

### B.9 A/B #3：`coding.readFiles`（工具保留，提示词行回滚）

同一中性指令（不点名任何工具）、同模型、无会话：`browser4-coding/src/test/kotlin` 下全部 .kt 读前 30 行并列表。

| 臂 | 任务 | 轮 | model | 墙钟 | 用了 readFiles |
|---|---|---|---|---|---|
| 处理组（提示词点名 readFiles） | `5ac9a55e` | 17 | 68 s | 74 s | 是 |
| 处理组 | `90eb492a` | — | — | 64 s | 是 |
| 对照组（提示词不点名，工具仍在） | `cf81b12d` | 10 | 53.6 s | **58.5 s** | 是（靠工具描述） |
| 对照组 | `13f57b0a` | 15 | 61 s | 66 s | 是 |
| 参考：更早一次（工具未被采纳） | `4a843524` | 22 | 100 s | 106 s | 否 |

结论（如实记录）：

1. **没有可归因于 N1 的墙钟收益**：两臂都采纳了工具，墙钟 58.5–74 s，对照组反而略快；同指令下轮数在 **10–22** 之间波动，
   模型方差远大于臂间差，n=2 无法分辨。
2. **采纳由工具描述驱动，不由提示词驱动**：对照组（提示词从不提 readFiles）两跑都用了它——`ToolSpec.description`
   里"ANY batch of 2+ files"才是有效信号。因此**提示词行已回滚**（`CliAgentPromptTest` 反向钉住），工具与其描述保留。
3. 工具价值不在"减轮"，而在**单次调用覆盖 N 个文件且回复有界**（4.6 KB 硬预算 + 跳过清单），
   这消除了"逐文件读 → 上下文被 60 个文件结果撑大"的路径；是否减少轮数由模型规划决定。
4. 推论：**不要在同一个前提上继续做 `coding.writeBatch`**（§11.3 已记）。写入的轮次受"先看后改"的依赖限制，不是调用粒度限制。

## 12. 进度与待办（2026-11-14）

### 12.1 已交付（按提交顺序）

| 提交 | 内容 | 状态 | 证据 |
|---|---|---|---|
| `41993ec592` | **P0-1 构建短路**：`BuildFingerprint`/`BuildCache`/`SourceDigest`、`MavenBuildSupport.build` 命中即返回、`cached` 进 `BuildResult`/`VerificationCheck`/`mvnBuild(json)` | ✅ 已完成 | 真实任务 `mvnBuild` 真跑后 `verify` 返回 `cached@sha256:248d2b574`（附录 B.2）；`BuildShortCircuitTest` 12 例 |
| `570c884f54` | **P0-0 轮次归因**：`RoundTiming`（model/tools/callbacks + 末轮）、`RoundTimed` 事件、sink/查询/索引/追踪接线 | ✅ 已完成 | 4 个真实任务四桶：model 77% / build 10% / tool 1.2% / callback 0.2%（§0.1）；`RoundTimingTest` 6 例 |
| `df378a5bed` | **实验 1 输出预算提示词**：工具轮不写散文、报告不复述文件内容 | ✅ 已保留（A/B 通过） | 2+2 次：outTok −22.6%、model −26.1%、墙钟 105.4 → 77.7 s（§0.3 / B.7）；`CliAgentPromptTest` 13 例 |
| `a72cfd399b` | 计划 v3、审查报告、四桶复算脚本 | ✅ 已完成 | 本文 + [审查报告](20261113-agent-dev-efficiency-dev-plan-review.md) + `scripts/analyze-agent-timing.ps1` |
| `e5df9643eb` | **N3 度量进结果**：`RunTimings` 累计器、`timings` 透传链、`agent result` 的 `Timings` 段 | ✅ 已完成 | 真实任务结果与事件日志**逐项一致**（§10.6）；`RunTimingsTest` 7 例 + 状态/结果透传 36 例 + CLI 28 例 |
| `fea39337c6` | **N1 `coding.readFiles`**：一次读 N 文件、4.6 KB 硬预算、跳过清单、内联失败 | ⚠️ 已交付但 **A/B 无收益** | 9 例单测；四跑 58.5–74 s、轮数 10–22 无臂间差；**采纳靠工具描述，提示词行已回滚**（B.9） |

配套的判死记录（都有数据）：P0-3 只读并发（工具桶 1.2%）、P1-3 ①② 记账合批（回调桶 0.2%）、
实验 2 批量提示词（+15% 墙钟，已回滚）、`coding.writeBatch`、P0-2 合并构建（见 §11.3）。

### 12.2 待办

| ID | 任务 | 前置 / 阻塞 | 验收 | 估时 |
|---|---|---|---|---|
| **N4** | `cached` verdict 带**覆盖模块列表** | 无（可直接开工） | 合并闸门用例：缓存命中但未覆盖本次改动 → 拒绝合并 | 0.5 d |
| **N7** | worktree 纳入**未提交文件** | 无 | 用例 + 真实任务能编辑未跟踪文件（现在 `e76c517c` 被 `PATH_DENIED`） | 1 d |
| **N6** | **runner 消失**的故障处理 | 无（需先定位 OOM/端口/外部 kill） | 任务不再"无声失败"，或可复现并给出根因 | 0.5–1 d |
| **N5** | P0-1 指纹收敛（可选） | 需先有真实任务的命中/失配数据 | 命中率提升且不引入误失效 | 1 d |
| **N2** | provider 参数透传（`reasoning_effort`） | **被 base 库阻塞**（`BrowserChatModel` 无透传缝） | `reasoning_effort=low/off` 的 A/B：model 桶与墙钟对比 | 本仓库 0.5 d + 前置 |

建议顺序：**N4 → N7 → N6 → N5**；N2 等前置解除后再排。

### 12.3 DoD 达成对照（§2）

| DoD | 状态 |
|---|---|
| 1. 零编辑构建 = 0 次（第二次 build 返回 `cached`） | ✅ 真实任务验证 |
| 2. ~~同轮只读并发~~ | 已删除（工具桶 1.2%，数据否决） |
| 3. 同轮多模块编译合并 | ❌ 未做（P0-2 降级，见 §11.3） |
| 4. 可分桶度量（四桶，不依赖外部日志） | ✅ N3 交付，`agent result` 直读 |
| 5. 验证强度不降 + `cached@<fp>` + 生成时间 | ✅ 已验证；**"覆盖模块列表"= N4 待做** |
| 6. model 桶下降 ≥20%（新增） | ✅ 实验 1：−26.1%（读密集型任务） |
| 7. 回归（`browser4-coding` / `browser4-agentic`） | ✅ 相关类全绿（见 §10.3 / §10.6）；**未跑全量**（仓库策略：最小作用域） |

### 12.4 已知缺口（写在纸面上，避免误读）

1. **没有跑全量测试套件**：所有门禁都是"最小作用域"（相关测试类），符合仓库测试策略，但跨模块回归风险
   由 CI 承担。
2. **N1 没有收益数字**：工具保留是因为它消除"逐文件读撑大上下文"的路径且回复有界，不是因为省时间。
3. **合并闸门仍只看 `cached` 标志**：N4 未做之前，闸门无法判断缓存的编译是否覆盖本次改动。
4. **缓存断言的是"源码能编译"**，不是 `target/classes` 存在（§10.1 已记）。
5. **隐藏推理仍不可控**：model 桶约六成是模型侧不可见的输出 token，只有 N2 能压。

### 12.5 复测方法（下次接着做之前先跑一遍）

```bash
# 真实任务（本轮才发现 --wait 可以不用手写轮询）
pwsh ./b4w.ps1 agent run "<task>" --coding --access read --wait --wait-timeout 600
# 四桶 + token + 成本模型拟合（也可以直接读 agent result 的 timings 段）
pwsh docs-dev/copilot/scripts/analyze-agent-timing.ps1 -Last 5
# 相关单测
.\mvnw.cmd -pl browser4-coding,browser4-agentic -am -D"test=BuildShortCircuitTest,RoundTimingTest,RunTimingsTest,CodingReadFilesToolTest" -D"surefire.failIfNoSpecifiedTests=false" test
```

## 附录 A：数据来源与复算方法

- 数据源：`app-data/memory/events` 中的 `tool_executed` 事件，字段 `seq` / `tool` / `ok` /
  `durationMs` / `callId` / `createdAt`。
- **工具名是 camelCase，不是点号**：事件里的 `tool` 形如 `coding_mvnBuild` / `coding_verify`
  （MCP 命名），不是 `coding.mvnBuild`。按点号过滤会得到 0 次构建——这是 v1 复算时踩到的坑，
  脚本已同时接受两种写法。
- **四桶口径（v2）**：**构建** = `tool ∈ {coding_mvnBuild, coding_verify}` 的 `durationMs` 之和；
  **工具** = 其余调用的 `durationMs` 之和；**回调** = `round_timing.callbacksMs` 之和；
  **模型** = `round_timing.modelMs` 之和（含末轮纯文本收束轮，`calls = 0`）。旧的「间隔」口径
  （相邻 `createdAt` 差 − 前一个 `durationMs`）只用于与 v1 基线对比：它把"模型延迟"和"轮内间隙"
  混在一起，聚类也无法看到真实轮边界，因此不再作为决策依据（对照见 §0.1 末尾）。
- 零编辑构建判定（v2）：两次构建事件之间不存在**任何非只读**调用——判据统一为
  `AgentAccessPolicy.gateOf(domain, method) != ToolGate.READ || method ∈ {requestAccess, protect}` 命中即打断，
  不再维护一份"写工具清单"（清单会漏掉 `coding.shell` 写文件）。脚本侧用 camelCase 前缀匹配同一集合。
- 复算脚本：`docs-dev/copilot/scripts/analyze-agent-timing.ps1`（参数 `-Last N` / `-TaskId <id>`），
  不进产物目录。

## 附录 B：归因测量（P0-0 产出）

复算脚本：[`scripts/analyze-agent-timing.ps1`](scripts/analyze-agent-timing.ps1)
（`pwsh docs-dev/copilot/scripts/analyze-agent-timing.ps1 -Last 5`，或 `-TaskId <id>`）。

### B.1 四个真实 coding 任务（2026-11-13，`agent run --coding`，DeepSeek）

| 任务（memory id） | 内容 | 轮数 | 调用 | build | tool | callback | model | 未覆盖 | 墙钟 |
|---|---|---|---|---|---|---|---|---|---|
| `259a2651` | 读 → `mvnBuild` → `verify(compile,repo-consistency)` | 5 | 6 | 14.6 s | 0.11 s | 0.02 s | 8.6 s | 6.6 s | 29.9 s |
| `e76c517c` | 编辑被拒（见 B.3）+ 两次 build + verify | 22 | 22 | 14.5 s | 0.12 s | 0.01 s | 50.5 s | 6.9 s | 72.0 s |
| `905cbeeb` | 只读巡检：59 个 .kt 逐个 `readLines` | 18 | 78 | 0 | 1.50 s | 0.29 s | 64.4 s | 7.8 s | 76.4 s |
| `d6e6973f` | 同上（末轮已纳入计时） | 14 | 91 | 0 | 1.52 s | 0.28 s | 92.5 s | 7.9 s | 102.2 s |
| **合计** | | 59 | 197 | **29.2 s (10%)** | **3.2 s (1.2%)** | **0.6 s (0.2%)** | **216 s (77%)** | 26.4 s (9%) | 280.5 s |

"未覆盖" = 墙钟里落在工具循环之外的部分：worktree 创建、workspace 镜像、finish gate、进程启停。

### B.2 P0-1 的真实命中证据

`259a2651`：第一步 `coding.mvnBuild(module='browser4-coding', goals='compile')` 真的跑了 Maven（exit 0），
紧接着 `coding.verify(checks='compile,repo-consistency')` 的 compile 检查返回
`cached@sha256:248d2b574 (no source change since 2026-11-13T15:34:37.235Z)`，同一 VERDICT_JSON 里带
`"cached":true,"fingerprint":"sha256:248d2b574"` —— DoD-1 与 DoD-5 在真实任务里同时成立。

`e76c517c`：第二次 `coding.mvnBuild` 输出
`✓ Build succeeded (cached, fingerprint sha256:248d2b574, no source change since …)`，
`zero-edit rebuilds: 2 (cached answers visible: 2)`。

### B.3 两个实测限制（环境相关，已写入 §10）

1. **worktree 看不到未提交文件**：`e76c517c` 想改 `BuildCache.kt`（本轮新增、未提交）被拒
   `PATH_DENIED / PATH_OUTSIDE_WORKSPACE` —— 隔离 worktree 只检出 HEAD，未提交文件不在其中。
   对本计划无影响（`coding.shell` 写盘使其失效的用例已由 `BuildShortCircuitTest` ⑥ 覆盖），
   但**未提交的新文件无法被 agent 编辑**，值得在 worktree 计划里记一笔。
2. **末轮必须单独计时**：`905cbeeb` 与 `d6e6973f` 是同一类任务，轮数更少（14 vs 18）但模型时间更长
   （92.5 s vs 64.4 s）——第 4 个任务把每次 `readLines` 塞得更多，每轮请求更贵。这正是 §0.1 第 3 条结论的来源，
   也是"压轮数 ≠ 省时间"的反例。

### B.4 P0-2 选型数据（已实测）

| 命令 | reactor 模块数 | 说明 |
|---|---|---|
| `mvn -pl browser4-coding -am validate` | **2**（Browser4 父 + Coding） | `browser4-coding` 是叶子模块，没有 `browser4-*` 依赖 |
| `mvn -pl browser4-coding,browser4-agentic -am validate` | **10**（父、common、tests-common、resources、browser、skeleton、core、protocol、coding、agentic） | 把一个叶子模块和一个重模块合并，会额外拉进 8 个上游模块 |

**结论（P0-2）**：合并的收益不是"少编译"，而是"少一次 Maven 启动 + 依赖解析"（全 reactor `validate` 约 2 s）。
只有**同一依赖子树内**的模块才值得合并；叶子模块与重模块合并不划算。

### B.5 成本模型拟合与探针任务（2026-11-13）

复算方式：把 4 个真实任务的 `round_timing.modelMs` 与同序 `cli-usage.jsonl` 的请求逐轮配对（59 轮配对成功，
4 次收尾轮因 token 缺失被排除），对 `modelMs = a + b·outTokens` 做最小二乘：

| 模型 | 结果 | 说明 |
|---|---|---|
| `a + b·outTokens` | **a = 600 ms, b = 4.05 ms/token, R² = 94.7%** | 采用 |
| 再加未缓存输入项 | 未缓存输入系数 **−0.039 ≈ 0** | 输入（在 92.6% 缓存命中下）不贡献延迟，故弃用该项 |

探针任务 `a4aacc52`（最小只读任务，用于验证 token 字段真的落库）：3 轮、3 次调用、
`outTokens = 555`、`cachedIn = 12,800`、model 4,839 ms、tool 0.17 s、callback 0.05 s——四桶与 token 字段
在真实运行中全部可见（`scripts/analyze-agent-timing.ps1` 已带 `outTok`/`cachedIn` 列与自动拟合）。

### B.6 为什么"换模型 / 关推理"这条杠杆现在做不了

- 配置源：`browser4-core/browser4-resources/src/main/resources/config/llm/providers.json` 的 deepseek 段
  `defaultModel = deepseek-v4-flash`，`apiProtocol = OPENAI`。
- 端点目录：`GET https://api.deepseek.com/v1/models` 只返回 **`deepseek-flash`、`deepseek-v4-pro`**——
  配置里的默认名不在目录中（请求仍被接受），因此"换个非推理模型名"在本环境不是可靠实验。
- 代码缝：`BrowserChatModel` 来自外部依赖（`ai.platon.pulsar.external`），本仓库没有设置
  `reasoning_effort`/`thinking` 等 provider 参数的入口；`AgentToolCallLoop` 只设置 `maxOutputTokens`。
- 结论：隐藏推理（约占 model 桶六成）在当前仓库内不可控，列为 P1-5，前置是 base 库的 provider 参数透传。

### B.7 A/B #1：输出预算提示词（2026-11-13 深夜）

同一指令、同一模型、无会话、`--coding --access read`，各跑 2 次（任务：`browser4-coding/src/test/kotlin`
下所有 .kt 列成"文件名 → 首个声明"表）：

| 臂 | 任务 | 轮 | 调用 | outTok | model | 墙钟 |
|---|---|---|---|---|---|---|
| 基线 | `684c2986` | 22 | 119 | 19,071 | 87,303 ms | 98.6 s |
| 基线 | `6136cc73` | 21 | 126 | 24,024 | 101,604 ms | 112.1 s |
| **均值** | | 21.5 | 122.5 | **21,548** | **94,454 ms** | **105.4 s** |
| 处理 | `d0c04585` | 21 | 107 | 15,925 | 71,960 ms | 77.2 s |
| 处理 | `eb6f815d` | 17 | 104 | 17,441 | 67,617 ms | 78.1 s |
| **均值** | | 19.0 | 105.5 | **16,683** | **69,789 ms** | **77.7 s** |

变化：outTok **−22.6%**、model **−26.1%**、墙钟 **−26.3%**、轮数 −12%、调用 −14%。
81 轮合并拟合：`modelMs ≈ 942 + 3.30 × outTokens`（R² = 90.9%），与 §0.2 的公式一致。

注意事项（写下来免得下次误读）：

- n=2/臂，不是统计检验；可用信号是**臂间差大于臂内离散**（基线 87–102 s，处理 68–72 s）。
- 两臂之间做过一次 bundle 重建（提示词变更），构建/启动成本没有计入任务墙钟。
- 任务存在模型自选策略的空间（基线两次枚举方式不同：19k vs 24k 输出），所以**这个 −26% 只对"该任务形态"
  成立**：读多、结论用表格。写代码/构建任务的主导成本是 build 与工具，提示词规则不会改变它们。
- 部署形态：规则已进 `CliAgentPrompt.hygiene(coding)`，`CliAgentPromptTest.outputTokenBudgetIsStated` 钉住，
  浏览模式不受影响。
### B.8 A/B #2：同轮批量提示词（否决）

| 臂 | 任务 | 轮 | 调用 | outTok | model | 墙钟 |
|---|---|---|---|---|---|---|
| 基线 | `d0c04585` | 21 | 107 | 15,925 | 71,960 ms | 77.2 s |
| 基线 | `eb6f815d` | 17 | 104 | 17,441 | 67,617 ms | 78.1 s |
| **均值** | | 19.0 | 105.5 | **16,683** | **69,789 ms** | **77.7 s** |
| 批处理 | `911f595f` | 22 | 127 | 18,959 | 81,339 ms | 89.4 s |
| 批处理 | `fe861ecf` | 22 | 113 | 17,533 | 78,802 ms | 89.7 s |
| **均值** | | 22.0 | 120.0 | **18,246** | **80,071 ms** | **89.6 s** |

结论：轮数未降（+16%）、输出 +9%、墙钟 +15% → 规则回滚。剔除的故障样本：`9c97e8c0`
（后端进程中途消失，74 次调用后任务被判 "ended before it reported completion"）。

教训：一次请求 ≈0.9 s 的延迟是**模型规划行为的函数**，不是提示词能直接压的量。真要减请求，得让
"列目录 + 读 N 个文件"这类动作在**一次工具调用**里完成（复合工具），或改写工具面，而不是要求模型"多调用"。

