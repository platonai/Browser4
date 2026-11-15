# Agent 等待期间可并行工作 — 评估（2026-11-15）

> 性质：**只评估，不改代码**。目的是回答一个问题：agent 卡在工具执行上时，还有哪些工作可以并行做，
> 哪些做了是真的省墙钟、哪些只是看起来忙。
>
> 数据来源（全部是本文档之前已采集的真实数据，未新增实验）：
> - [agent 自开发效率开发计划](20261113-agent-dev-efficiency-dev-plan.md) §0.1 四桶实测（4 个真实 coding 任务、
>   59 轮、197 次调用）、§0.2 成本公式（R²=94.7%）、§1 历史基线（2863 s）；
> - 代码：`AgentToolCallLoop.kt`（循环与回调）、`ToolExecutionCoordinator.kt`（工具执行）、
>   `BatchExecutor.kt`（既有并行路径）、`BuildCache.kt` / `MavenBuildSupport.kt`（构建与指纹）、
>   `CliJobRegistry.kt`（长命令转后台作业）、`RunTimings.kt`（四桶累计器）；
> - [多标签并行采集](crawl-multitab-parallel-4.13.x.md) 的对比数据（4 个 1.5 s 慢页：28 s → 4.6 s）。
>
> 采集时点：四桶数据来自 `4.16.x`、P0-1（构建短路）落地之后，因此构建桶已经不含"零编辑重复构建"。
>
> **修订 v1（2026-11-15，B2 已实施）**：本文 §6 的第 1 步（拆未归因 9%）已落地为
> `RunPhaseClock` / `RunPhaseTimings`（`browser4-agentic`），由 `StatefulAgentRunner` 打 5 个边界标记；
> 结果 JSON 在 `timings` 下新增 `phases{wallMs,setupMs,isolationMs,runMs,mergeMs,finalizeMs}` 与
> `runOverheadMs`，见 §7。**其余结论未被这次改动影响**——尤其 B1（浏览器任务四桶）仍没测，
> 所以 P0-3 的否决只对 coding 任务成立。
>
> **修订 v2（2026-11-15，C1 的 A/B 已跑）**：真实后端 + 真实模型 + 真实 Maven 的 5 基线 / 5 处理已跑完，
> 见 §11。结论：**机制成立但默认保持关闭**——投机构建 5/5 触发、5/5 被按需构建加入
> （`reuseRatio = 1.00`，零取消、零失败、Maven 启动次数不增），构建等待降 18.5%；
> 但省下的绝对量被"一轮模型往返"封顶（实测 0.96 s / 2.5 s 的构建），在 ~22 s 的小任务上是 4.3%，
> 按 §10 的口径换到真实工作负载只剩 1.4–2.9%，压在 ≥2% 的验收线上。
> A/B 还顺手抓到本次改动里的一个真 bug（`phases` 报成纳秒，§11.4），已修复并单独提交。
>
> **修订 v3（2026-11-15，B1 已测）**：3 个真实浏览器任务的四桶已拿到，见 §12：**W-tool 51.7% / W-model 48%**，
> 与 coding 的 1.2% / 77% 完全相反。所以本文 §0 那句话要分任务类型读；§1/§5/§6 里"工具慢不是方向"
> 的范围被收窄为 coding。**P0-3（同页内并发）仍然否决，但理由换了**——不是"时间不够"，而是同标签页动作
> 按语义必须串行、延迟是反爬要求（§2 D1/D2）。收益因此转移到两条新方向上：每个动作的 `b4_run` 固定开销
> 与跨标签页流水线（§12.3）。

## 0. 结论先行

一句话：**对 coding 任务，"等工具"本身没有多少时间可抢，可抢的是"等模型"那一段；对浏览器任务这句话是反的
——"等工具"占了约一半墙钟（§12 实测 51.7%），而它不能被"同页并发"抢回来，只能被"别的标签页"抢回来。**

前提要说清：下面的四桶表来自 4 个 coding 任务。B1 补测的 3 个浏览器任务里 W-tool 是 51.7%、W-model 48%
（§12），所以**"工具慢不是方向"只对 coding 成立**；浏览器侧的方向是每动作固定开销与跨标签页流水线，不是
单页内并发（理由见 §12.3，不是"时间不够"而是 D1）。

| 等待窗口 | 实测（4 个 coding 任务） | 可并行候选 | 收益天花板 | 结论 |
|---|---|---|---|---|
| 等模型往返 | **216 s / 77%** | 投机编译、健康检查、记账外移 | ≤ build 桶 29.2 s ≈ **10%** | **唯一值得做**（§3） |
| 等构建 | 29.2 s / 10% | 在构建旁边并发跑只读检查 | verify 内只读检查实测 103–132 ms → ≈ 0 | 并发不做；改做"构建本身变快"（§2 C2） |
| 等普通工具 | 3.2 s / 1.2%（coding）<br>**51.7%（浏览器，§12）** | 同轮只读并发 | coding ≤ 1.1%，2 核上更少；浏览器那是 50% 但同页必须串行 | coding 维持否决（P0-3）；浏览器改走跨标签页（§2 E）与每动作开销 |
| 记账/回调 | 0.6 s / 0.2% | 异步化 | ≤ 0.6 s | 不做（P1-3①②） |
| 未归因 | 26.4 s / 9% | — | 未知 | 已测（§7/§11）：`phases` 能解释 |

三条判断：

1. **in-loop 并发（同轮多工具）天花板 1.1%**，且要先付 6 个正确性前置修复（§4）。花一周换 1 秒，不划算——
   P0-3 的否决在当前数据下仍然成立。
2. **真正有量的是"等模型时顺手把构建做了"**：模型桶 216 s 是网络等待、CPU 空闲；构建桶 29.2 s 完全在模型桶之外，
   两者现在严格串行。把后者的 30–60% 藏进前者，等于白拿 3–6% 墙钟。
3. **跨任务并行是唯一有 10% 以上实测证据的轴**（采集 4 页 28 s → 4.6 s，6×），但它提升吞吐、不降低单任务延迟，
   且受 driver 池与 worktree 冷 `target/` 限制（§2 E）。

## 1. 口径：把"等待工具执行"拆成三个窗口

现有四桶（`RunTimings`）已经能把墙钟切开，但"等待"不是一个桶，而是三个互相排斥的窗口：

| 窗口 | 触发点 | 现有打点 | agent 在干什么 |
|---|---|---|---|
| W-model | `model.langChainChat(...)`（`AgentToolCallLoop:305`） | `RoundTiming.modelMs` | 等网络，CPU 空闲 |
| W-build | `coding.mvnBuild` / `coding.verify` 工具体 | `RoundTiming.toolsMs` 中被拆出为 `buildMs` | 等 Maven 子进程，CPU 部分占用 |
| W-tool | 其它工具体（`coordinator.execute`） | `toolsMs` | 等浏览器/CDP/IO |
| W-callback | 五个回调 + 结果消息装配 | `RoundTiming.callbacksMs` | 纯本地计算 |

关键事实：**W-model 与 W-build 在同一轮里不可能同时发生**（工具在模型返回后被串行调用），这正是可重叠的空间。
在 coding 任务里 W-tool + W-callback 只有 1.4%，被 77% 的 W-model 完全盖住——**盯着"工具慢"做优化，
方向就是错的**。这句只对 coding 成立：浏览器任务的实测是 W-tool 51.7%（§12），方向变成"每个动作的固定开销
与跨标签页流水线"。

两个缺口（本文建议优先补）：

- ~~**浏览器任务没有四桶数据**~~ → ✅ 已测（§12）：3 个真实浏览器任务 W-tool **51.7%**、W-model 48%，
  翻转条件（≥25%）成立。但结论不是"该做 P0-3"，而是"该做每动作开销与跨标签页"——同页动作按语义串行。
- ~~**9% 的未归因没人拆**~~ → ✅ 已测（§7 + §11）：`phases` 把 setup/isolation/run/merge/finalize 全归了，
  `runOverheadMs` 在真实任务上占 4.4%。

## 2. 候选清单

### A. 安全可做（不依赖在途结果、不碰共享可变状态）

| 编号 | 工作 | 判据 | 天花板 | 结论 |
|---|---|---|---|---|
| A1 | **长等待期的看门狗/心跳** | 后端进程存活、CDP 连接存活、子进程存活 | 不省墙钟，省"静默失败"的时间 | ✅ **已实施**（§9）：放弃轮询时探测 `/actuator/health` 并把原因说清楚 |
| A2 | 记账外移（trace jsonl、memory 事件、metrics） | 已有 `onToolResult` 等 5 个回调 | 0.2% | 不做 |
| A3 | 日志落盘、指标导出、缓存回收 | — | ≈ 0 | 挪到任务边界，不在轮内 |

A1 的价值有明确证据：`9c97e8c0` 跑到 74 次调用时后端进程消失，任务被判 "ended before it reported completion"，
日志里连一行崩溃都没有，样本作废、整轮重跑。若在 W-model 超过 N 秒时对后端做一次 `health` 探测，
这个 100 s 级的静默损失可以变成秒级、可归因的错误。**这是"并行做工作"里唯一同时改善可靠性的一条，
且不与任何状态冲突**（只读探测）。

### B. 需要先测量（数据不足，不能下结论）

| 编号 | 要测什么 | 怎么测 | 判据 |
|---|---|---|---|
| B1 | **浏览器任务的四桶** | N3 已把 `timings{build,tool,callback,model}` 写进 `agent result`，跑 3 个真实浏览器任务读结果即可 | ✅ **已测**（§12）：W-tool **51.7%**，翻转条件（≥25%）成立 |
| B2 | 未归因 9% 的去向 | ✅ **已实施**：`RunPhaseClock` 打点（§7），读 `agent result` 的 `timings.phases` 即可 | 任一项 > 3% 再谈优化 |

B1 还有一层：**浏览器任务的 W-tool 里，等待是"故意的"**（人化输入延迟 `randomDelayMillis("type")` 90–240 ms、
导航 settle `NAVIGATION_DOM_SETTLE_DELAY_MS`）。这些延迟是反爬要求，不能压缩，只能**用别的活填满**。
这决定了浏览器侧的正确方向是"多标签页流水线"而不是"单页内并发"。

### C. 值得做（有数据支持）

| 编号 | 工作 | 收益 | 依据 |
|---|---|---|---|
| **C1** | **投机编译**：编辑成功后、模型下一轮推理期间，后台先跑一次 `compile` | 实测：build 桶 −18.5%（≈0.96 s/任务），即**一轮模型往返**，不是 3–6% | 模型桶 216 s 是网络等待；build 桶 29.2 s 与它严格串行；`BuildCache` 已提供失效原语。**已落地（§8）+ A/B 已跑（§11），默认关闭** |
| C2 | 构建自身并行：多模块合并（P0-2）、`-T 1C`、`mvnd` | 直接削 build 桶，历史口径下是最大单块 | `-T` 已落地（默认关，§10 实测冷编译 −24%）；多模块融合 §10 实测否决；`mvnd` 未装 |

C1 之所以"有数据支持"而不是"投机"：`BuildCache` 的内容指纹（module + goals + skipTests + `-am` 链 pom 摘要 +
maven/JDK）让**猜错的代价接近零**——猜对了，模型随后要求的构建命中缓存（毫秒级返回 `cached`）；
猜错了，下一次写操作使指纹失配、条目自然失效。**唯一真实成本是 CPU 与内存**。

C1 的设计要点见 §3。C2 的坑不在设计而在测量：`-am` 可能把"2 × 30 s"变成"1 × 120 s"，必须先测 reactor 模块数。

### D. 结构上不可并行（做了就是 bug）

| 编号 | 不可并行的事 | 原因 |
|---|---|---|
| D1 | 同一标签页上的浏览器动作（click → snapshot → fill） | DOM 是单一共享可变资源，顺序本身就是语义；`frontDriver`/`bringToFront` 是全局"当前页"状态 |
| D2 | 同一轮里的写/编辑工具 | `edit → build`、`write → read` 的顺序是任务语义；`CodingAgentFileSystem` 的 mutation 日志按序记录 |
| D3 | 多模型 / 多 agent 并行推理 | 计划 §9 已列不做：打乱压缩、记账、finish gate |
| D4 | 回调与账本的并发写 | `RunTimingsAccumulator` 注释明写"Deliberately not thread-safe"；`roundToolRecords`（`RobustBrowserAgent:1164`）是 `mutableListOf` |
| D5 | 同一 workspace 的两个 Maven 构建 | `BuildCache` 只有 `lookup/put/invalidate`、**没有 in-flight 锁**；两个 mvn 抢同一个 `target/` 会互相踩 |

D5 是 C1 的硬约束：投机构建必须与按需构建**单飞（single-flight）**——按需请求来了要么 join 在途构建，
要么先取消再重跑，绝不并行起第二个 mvn。

### E. 另一条轴：跨任务并行（吞吐，不是延迟）

| 证据 | 数值 | 含义 |
|---|---|---|
| 4 个 1.5 s 慢页，`parallelTabs=1` | ~28 s，服务端峰值并发 1 | 串行 |
| 4 个 1.5 s 慢页，`parallelTabs=4` | **~4.6 s**，峰值并发 4 | 6× |

约束（都是已知的、不是猜测）：驱动池是硬上限（`browser.context.number` × `max active tabs`）；
coding 任务在 worktree 隔离下 `target/` 是冷的，N 个并行任务 = N 份冷构建；`maxConcurrentPerSession` 默认 2
（`CliProcessManager`），2 核 CI 上并发只会更差。

结论：这条轴适合"批量任务"（采集、巡检、多仓库同题），**不适合**"把一个任务做得更快"。要动它，先回答
"每任务墙钟不变、总吞吐 N×" 这个目标是否就是产品想要的。

## 3. 投机编译（C1）— 若实施，最小形态

**触发**：一个成功的写类工具返回后（`onToolResult` 里 `gate == ToolGate.MUTATE`），且下一个动作是模型推理
（即本轮工具已执行完、即将回到 W-model）——此时工作区已包含这次编辑。

**干什么**：`mvn -o -q -pl <module> -am compile`（与 `VerificationRunner.compileCheck` 同一条 goals 形状，
保证能命中同一个缓存键）。

**单飞**：以规范化 workspace root 为键，`ConcurrentHashMap<Path, Deferred<BuildResult>>`。
按需构建到达时，若在途则 `await` 它（并比对指纹），否则正常起。

**失效**：任何写操作 → `BuildCache.invalidate(root)` 并取消在途投机构建（`BuildFingerprint` 不匹配即丢弃）。
这条已有的 `beginTask` 失效逻辑可以直接复用。

**度量**（不开新口径）：在 `RunTimings` 增 `builtAheadMs` 与 `speculative{started,reused,wasted}` 三个计数，
进结果 JSON。核心指标是 **`reused / started`**；低于 50% 就该关掉这个特性。

**验收**：同一任务（读 → 改 → 编译 → 校验）、同一模型，2 基线 / 2 处理：处理组 `mvn` 真实启动次数不增、
墙钟下降，且 `verify` 的 compile 检查出现 `cached@<fp>`。**墙钟下降 < 2% 就回滚**——这条和 P0-3 一样，
宁可判死也不要留一个没人算得清收益的特性。

> **2026-11-15 修正（C2 实测之后）**：一次真实编译 31–84 s（§10），比模型一轮（0.6 s + ~4 ms/输出 token）
> **长得多**，所以投机构建通常跑不完就被按需调用 join——它省下的是"编辑完成 → 模型提出构建"的间隔（通常一轮），
> 不是整个构建时长。上面的 3–6% 因此偏乐观：验收按"每任务省 1–2 轮模型往返"来衡量。

**开关**：`-Dbrowser4.agent.build.speculative=false`（默认开或关由 POC 数据定；§11 的 A/B 数据出来之后
定为**默认关**——机制可信，但收益被一轮模型往返封顶）。

**风险**：① 2 核 CI 上后台 Maven 抢占 CPU，可能拖慢紧随其后的工具调用——缓解：只在 W-model 期间运行，
一旦有真实工具调用就 join 或取消；② Maven 内存翻倍；③ 与 D5 冲突，必须单飞。

## 4. 任何 in-loop 并发都要先付的账

这 6 条不是建议，是**前置条件**；缺一条，并发就会把错误变成静默错误：

1. `ToolExecutionCoordinator.execute` 内部是 `runBlocking(Dispatchers.IO)`（`:36-41`）——塞进 `async` 会占死线程池，
   2 核 CI 上"并发 4"只有 2。先抽 `suspend fun executeSuspend`。
2. `catch (e: Exception)`（`:54`）会连 `CancellationException` 一起吞——用户取消变成某条工具的 `[fail]`。先重抛。
3. 回调必须**按请求顺序串行**触发：`RunTimingsAccumulator` 非线程安全、`roundToolRecords` 是 `mutableListOf`、
   `RoundTimed` 事件按序落库。
4. 结果消息的追加顺序必须 = 请求顺序（provider 要求 `AiMessage.toolExecutionRequests` 与
   `ToolExecutionResultMessage` 一一配对）。
5. 分类器只认 `AgentAccessPolicy.gateOf(domain, method)`，`null` 一律串行；`requestAccess` / `protect` 虽然归在
   `READ_METHODS`，实际会写状态，**必须当屏障**。
6. 浏览器域：`driver` 是会话绑定的那一个；并发操作不同标签页必须显式给 `tabId`，不能依赖"当前标签页"。

## 5. 不做什么

- 不做同轮只读并发（P0-3）：coding 任务天花板 1.1%，前置修复 6 条，2 核 CI 上收益趋零。
  浏览器任务里 W-tool 确实占 51.7%（§12），**但仍不该做**——同一标签页的动作按语义必须串行（§2 D1），
  可省的是"每个动作一次 `b4_run` 进程 + 往返"这类固定开销（§12.3），那是批量化的活，不是并发的活。
- 不做 `coding.verify` 内检查并行（P1-2）：`repo-consistency` 实测 103–132 ms。
- 不做回调异步化（P1-3①②）：0.6 s / 197 次 = 3 ms/次。
- 不做多模型/多 agent 并行推理（§2 D3）。
- 不为了"看起来并行"而并发化任何会写状态的东西——本项目已经因为"看起来并发、实际排在一个 tab 上"踩过坑
  （多标签采集的 2 ms 内双报）。

## 6. 建议顺序（成本 / 收益）

| 序 | 动作 | 成本 | 预期 |
|---|---|---|---|
| 1 | 拆未归因 9%（B2） | ✅ **已实施**（§7） | 拿到 `timings.phases` 后再决定后面值不值得做 |
| 2 | 浏览器任务四桶（B1） | ✅ **已测**（§12） | W-tool 51.7%：P0-3 仍否决（同页串行），方向改为每动作开销 + 跨标签页 |
| 3 | 长等待看门狗（A1） | ✅ **已实施**（§9） | 消除 100 s 级静默失败 |
| 4 | 投机编译 POC + A/B（C1） | 骨架 ✅ **已实施**（§8）；A/B ✅ **已跑**（§11） | 机制 5/5 命中、`reuseRatio = 1.00`；省下 ≈1 轮模型往返，默认保持关 |
| 5 | `-T 1C` / 多模块合并测量（C2 / P0-2） | ✅ **已测量**（§10）：`-T` 对 compile-only 可选放开；**多模块融合否决** | 直接削 build 桶 |
| — | 其它（A2/A3/D/E） | — | 不做或另立目标 |

## 7. 实施记录：B2（阶段计时，2026-11-15）

**改了什么**

| 文件 | 内容 |
|---|---|
| `browser4-agentic/.../agents/RunPhaseTimings.kt`（新） | `RunPhase` 枚举 + `RunPhaseTimings`（`wallMs/setupMs/isolationMs/runMs/mergeMs/finalizeMs` + `toMap()`）+ `RunPhaseClock`（边界标记，可注入时钟） |
| `browser4-agent-tools/.../StatefulAgentRunner.kt` | 5 个 `mark`：`ISOLATION`（worktree 创建）→ `SETUP`（rebind/policy）→ `RUN`（`agent.run`）→ `MERGE`（`finishIsolation`）→ `FINALIZE`（`finally` 到结果装配）；`status.timings` 增 `phases` 与 `runOverheadMs` |
| `browser4-agentic/src/test/.../RunPhaseClockTest.kt`（新） | 5 例：分区不变式、重开阶段累加、快照幂等、时钟倒走钳零、map 形状 |
| `cli/browser4-cli/src/agent_result.rs` | `Timings` 区第二行渲染 `setup (isolation) · merge · finalize`；`runOverheadMs` 仅留在 `--json` |

**怎么读**

- `timings.phases.wallMs` = 从任务进入 runner 到结果装配；`setup + run + merge + finalize` 与它**必须相等**
  （`RunPhaseClockTest` 钉住了这条分区不变式）。
- `isolationMs` 是 `setupMs` 的**内部拆分**（worktree 时间同时计入 setup，否则 setup 会静默漏掉最慢的那段）。
- `timings.runOverheadMs` = `runMs - 四桶合计`：`agent.run()` 里**工具循环之外**的时间（prompt 构建、
  memory 召回、状态写）。这是"把活藏进模型窗口"唯一需要先知道的数。
- 快照点**之后**的尾巴（`onStatusChanged`、`recordLifecycle`、`conversation.record`）故意不认领，
  用 `status.elapsedMs - phases.wallMs` 算——两个数都在手边，比在时钟里编一个恒为 0 的
  `unaccountedMs` 诚实。第一版实现里确实有那个字段，发现它恒为 0 后删掉了。
- 人读的位置是 `agent result` 的 `Timings` 区：第一行四桶，第二行阶段。阶段**必须独占一行**——
  实测把它拼在桶后面会把 tokens 挤出行宽被裁掉，渲染测试当场抓到了这一点。

**为什么不是"再埋一个 memory 事件"**：P0-0 的 `round_timing` 是事件，因为它要按轮查询；
阶段是每任务一份的常量，跟着结果走（N3 的做法）比再开一张事件表更省，也避免日志与结果打架。

**真实任务上的读数**：✅ 已拿到，见 §11（5 基线 / 5 处理）。同一任务 `wall = 30,689 ms`，
`setup 725 / isolation 459 / run 29,056 / merge 776 / finalize 132`，`runOverheadMs = 1,347`
——即 `agent.run()` 里工具循环之外的部分占墙钟 4.4%，分区不变式在真实任务上成立。

## 8. 实施记录：C1 骨架（2026-11-15）

**改了什么**

| 文件 | 内容 |
|---|---|
| `browser4-coding/.../SpeculativeBuild.kt`（新） | 单飞协调器：同指纹**加入**、异指纹**等待**、只有投机航班可被**取消**；`Stats{builds, speculativeBuilds, reused, cancelled, failed, reuseRatio}`；`speculative` 侧开关 `-Dbrowser4.agent.build.speculative`（默认 **false**） |
| `browser4-coding/.../MavenBuildSupport.kt` | `build()` 走协调器（`test` 类不可缓存构建用一次性 id，只做串行化、不加入）；新增 `scheduleSpeculativeBuild()` / `cancelSpeculativeBuild()` / `speculativeStats()`；`lastBuildRequest` 提到 companion，成为**进程级**"每个 workspace 上次构建的模块与 goals" |
| `browser4-coding/.../CodingAgentFileSystem.kt` | 每次交付性写入时取消在途投机构建（与 `BuildCache.invalidate` 同一漏斗）；新增 `mutationEpoch` 供触发点做廉价守卫 |
| `browser4-agentic/.../RobustBrowserAgent.kt` | `onBeforeGenerate`（每轮模型请求前）触发投机编译，只在 coding 模式，用写纪元守卫避免每轮哈希源码树 |
| `browser4-agent-tools/.../StatefulAgentRunner.kt` | 结果里新增 `timings.speculative{builds,speculativeBuilds,reused,cancelled,failed,speculativeMs,reuseRatio}`，取**任务区间增量**（`deltaSince`），不是进程累计 |
| 测试 | `SpeculativeBuildTest`（9 例）+ `SpeculativeBuildWiringTest`（4 例） |

**三条语义（都能测）**

1. **同一 workspace 永远只有一次构建**：同指纹加入（一次 Maven、一份答案），异指纹排队等待。
   这条**与开关无关**——它防的是 `target/` 互踩（风险 D5），不是省时间。
2. 只有**投机**航班可被写操作取消；调用方自己的构建不会被另一次调用取消（那会把真实请求杀掉）。
3. 被取消的航班**不会被加入**：它的结果描述的是已经不存在的源码树，调用方必须重建。

**为什么触发点在 `onBeforeGenerate` 而不是写入后**：写入后立刻触发，会把同一轮里连续的编辑
各编译一遍；放在模型请求前，工作区已经稳定，正是 W-model 窗口（§0 的 77%）。

**守卫**：`onBeforeGenerate` 每轮都调用，而指纹要哈希整棵源码树（`SourceDigest`），
所以用 `mutationEpoch` 挡住"这轮没写"的情况——否则触发点本身会比它想省的构建更贵。

**一个被测试逼出来的设计修正**：最初的 `Stats.started` 把按需构建也算进去，
`reused/started` 于是把"没人请求的投机"和"真实请求"混成一个分母，决策指标失真。
现在拆成 `builds`（全部）与 `speculativeBuilds`（只算投机），`reuseRatio` 以前者为分子、
后者为分母。测试先红后绿的就是这一条。

**怎么读 `timings.speculative`**

- `flightsUsed` / `flightsUnclaimed`：**先看这两个**。前者是"有人在等"的投机构建（真正重叠了模型等待），
  后者是"跑完了还没人来要"的——它**不是浪费**：请求随后到达时会走 `BuildCache`（`cached@<fp>`），
  所以 `reused` 不会增。两者相加 = 投机航班数（另有 `cancelled` / `failed`）。
- `reuseRatio = reused / speculativeBuilds`：**按请求**算的加入率，可以大于 1（一次航班被多个调用者加入）。
  单看它会把"跑在请求前面"的那一半读成"没人要"，所以它不再单独当判死线用（§11.4b）。
- `speculativeMs`：猜的 CPU 账单。它**不是**省下的时间——省下的是 `reused` 次构建本会花的墙钟。
- 计数是进程级，结果里是**任务区间增量**（`Stats.deltaSince`，并向下钳零）。不做增量，
  同一 JVM 里第二个任务会继承第一个任务的数字，A/B 两个臂就分不开。

**还没做**

- ~~**A/B**~~：✅ 已跑，见 §11（机制 5/5 命中；省下 ≈1 轮模型往返；默认保持关）。
- **模块猜测**：现在只复用"上次 `coding.mvnBuild` 的模块"；从改动文件路径推断模块没做。
  这不是偷懒：猜错模块是纯浪费，而"上次构建的模块"是同一个人大概率会再要的那一个。
- 浏览器侧的对应物（下一轮页面预取）没做，也不建议在没有 B1 数据前做。

## 9. 实施记录：A1 看门狗（2026-11-15）

**问题**：跟随器（`--follow` / `-i`）在连续 300 s 拿不到可用响应后放弃，原本的文案一律是
"the task itself may still be running — 'agent status <id>' reports it"。后端进程已经死掉时这句是**错的**：
任务不可能活得比后端长，用户被指去查一个查不到的东西。`9c97e8c0` 那次就是这样丢掉一个样本的。

**改了什么**（`cli/browser4-cli/src/agent_interactive.rs`）

| 内容 | 说明 |
|---|---|
| `BackendLiveness{Up, Down, Unknown}` + `probe_backend_liveness` | 只在**窗口到期那一刻**探一次 `/actuator/health`（2 s 超时），不是每次失败都探 |
| `give_up_message(task, secs, error, liveness)` | 三档归因：服务器还在（问题在任务/端点）／不服务了（进程退出、重启或卡死）／证据不足（保留旧措辞） |
| `tolerate_transient` / `recover_unparseable_page` 改成 `async` | 探测需要网络；四个调用点补齐 `client`/`base_url` |

**两个判断口径**

- **任何 HTTP 应答都算 `Up`**：503（Spring 正在启动）也证明端口有进程，而我们要区分的正是"任务是不是变成了孤儿"。
- **超时算 `Down`，不算"未知"**：健康端点在正常后端上是毫秒级，5 分钟不通之后再给 2 s 还超时，就是不在服务。
  这条是被测试逼出来的：Windows 会给"刚刚释放的临时端口"分配保留区间，SYN 被静默丢弃 → 连接表现为**超时**而不是拒绝，
  所以不能靠 `is_connect()` 判断死活。

**测试**（4 例）：文案三档、探测区分活/死端口、503 也算活、跟随器在窗口到期时给出可归因错误
（含反向断言：后端死了就**不得**出现 "may still be running"）。

**没做**：进程级校验（CLI 自己拉起的管理端口，可以查 PID 是否还活着，比探测更直接）；CDP 连接存活探测。

## 10. C2 实测：单次 Maven 调用的真实成本（2026-11-15）

环境：Windows、20 核、JDK 25（GraalVM）、Maven wrapper（Maven 4）、`-o` 离线、`-DskipTests`。
数字全部是 `-pl … -am compile` 的墙钟。

| 场景 | 墙钟 | 备注 |
|---|---|---|
| `-pl browser4-coding -am compile`（已最新，reactor 2） | 10.5 s | |
| `-pl browser4-agentic -am compile`（已最新，reactor 10） | 36.4 / 34.8 / 39.1 s | 稳态 ≈ 35 s |
| 同上 `-T 1C` | 32.4 s | 稳态 −11%，与噪声同量级 |
| `-pl browser4-rest -am compile`（已最新） | 59.2 s | |
| `-pl browser4-agent-tools -am compile`（已最新） | 71.2 s | |
| **冷构建** `clean compile`（10 模块） | **110.7 s** | |
| **冷构建 + `-T 1C`** | **83.9 s** | **−24%** |
| 合并选择 `-pl browser4-agentic,browser4-coding`（稳态 ×2） | 31.6 / 31.4 s | 与单模块同 reactor，无收益也无损失 |
| **换选择集/换 flag 后的第一次调用** | 74.4 / 78.6 / 106.8 s | **状态被打断 → 重编** |
| `mvnd` | 未安装 | |

**结论一：固定成本是主项，且与"改了多少行"无关。**
一次 `coding.mvnBuild` 即便没有任何改动要编译，也要付 10.5–71.2 s（≈3–4 s/模块 + JVM/插件启动）。
这解释了 build 桶为什么由"选了哪个模块"决定，而不是由代码改动量决定。

**结论二（修正 §3 的预期）：投机编译的窗口比一次构建短，C1 的收益是"一轮"不是"一个 build"。**
§0.2 实测模型一轮 ≈ 0.6 s + 4 ms/输出 token；一次真实编译是 31–84 s。
所以投机构建通常**跑不完**就被按需调用 join 掉——它省下的是"编辑完成 → 模型提出构建"之间的间隔
（通常一轮，几秒），**不是整个构建时长**。§3 里"build 桶的 30–60% → 3–6% 墙钟"因此偏乐观：
现实收益是每次构建省 1 轮模型往返，两次构建量级 = 数秒。
C1 仍值得留（它把重复构建和等待变成一次），但**验收线要按"每任务省 1–2 轮"定，而不是按 build 桶降幅**。

**结论三：改变构建形状很贵，所以 P0-2 的"同轮多模块融合"不该做。**
把 `-pl` 列表从一个形状换到另一个（哪怕只是显式加入一个本来就在依赖里的模块），紧接着的第一次构建
会触发重编：74–107 s vs 稳态 32–35 s。融合调用每次都可能换形状，付出的重编成本大于省下的 JVM 启动；
而"合并"本身没有收益（同样 10 个模块，31.5 s vs 35 s，差异在噪声内）。

**落地的改动**：`-T` 只对 **compile-only** 打开，且默认关——
`mavenBuildCommand(..., threads)` + `-Dbrowser4.agent.build.threads=1C`（`MavenBuildSupport.buildThreads`）。
`test`/`verify` 一律单线程：仓库自己把 `-T 1C` 注释在 `.mvn/maven.config` 里，原因就是 kapt 与
remote-resources 不是线程安全的；并行 `mvn test` 省几秒却可能换来一个假红。

## 11. C1 的 A/B 实测（2026-11-15）

**口径**：真实后端（`b4w.ps1` 从源码构建的 bundle）+ 真实模型（DeepSeek）+ 真实 Maven。
每个样本是同一句 prompt、同一个起点的**一次完整 coding 任务**，5 基线（`speculative=false`）/
5 处理（`speculative=true`），交错执行（S3→B3→S4→B4→S5→B5）。

被试工作区是一个 2 模块的玩具 Maven 仓库（`ab-parent` → `lib` + `app`，Java 17），放在
`D:\tmp\b4-ab`，与 Browser4 自己的 checkout 无关；任务 = 先 build 一次、改 `app`、再 build 一次、
最后 `coding.verify`。每次跑前 `git reset --hard` + `git clean -fdx` + worktree prune，
保证两次任务看到同一个起点（构建都是**冷 worktree**，与真实 coding 任务一致）。

**改了什么在被测这一侧**：只有 `-Dbrowser4.agent.build.speculative`；`-T` 未开
（`-Dbrowser4.agent.build.threads` 缺省 `1`），`finishGateCheck=warn` 两臂相同。

### 11.1 逐次结果

| 样本 | 状态 | 墙钟 | 轮数 | model | build | 构建数 | 投机数 | 加入数 | specMs |
|---|---|---|---|---|---|---|---|---|---|
| B1 | done | 30,676 | 13 | 17,560 | 9,841 | 3 | 0 | 0 | 0 |
| B2 | done | 18,079 | 8 | 10,421 | 4,808 | 2 | 0 | 0 | 0 |
| B3 | done | 17,885 | 8 | 9,889 | 5,225 | 2 | 0 | 0 | 0 |
| B4 | done | 24,540 | 10 | 15,734 | 5,710 | 2 | 0 | 0 | 0 |
| B5 | done | 21,884 | 11 | 13,508 | 5,145 | 2 | 0 | 0 | 0 |
| S1 | done | 29,602 | 12 | 19,005 | 4,611 | 2 | 1 | 1 | 2,436 |
| S2 | done | 19,465 | 12 | 12,215 | 3,801 | 2 | 1 | 1 | 2,542 |
| S3 | done | 32,334 | 14 | 21,452 | 7,184 | 3 | 1 | 1 | 2,399 |
| S4 | done | 15,288 | 6 | 7,933 | 4,631 | 2 | 1 | 1 | 2,546 |
| S5 | done | 19,649 | 11 | 12,510 | 3,988 | 2 | 1 | 1 | 2,585 |

（毫秒；10 个样本全部 `merge: applied`，没有失败或取消。）

### 11.2 三条结论

**一、机制成立，而且没有浪费。** 处理臂 5/5 触发了投机构建，5/5 被随后的按需构建**加入**
（`reused = 1`，`reuseRatio = 1.00`），`cancelled = 0`、`failed = 0`，Maven 启动次数不增
（两臂 `builds` 均值都是 2.2）。也就是说：猜的构建一定有真实请求来加入，写操作取消那条路径在
这批样本里根本没被用到（它仍然有单测）。

**二、省下的量被"一轮模型往返"封顶，不是被构建时长封顶。** 只看构建数相同的样本
（各 4 个两次构建的样本）：build 桶均值 **5,222 ms → 4,258 ms（−18.5%，≈ −0.96 s/任务）**。
被加入的那次构建等了 `specMs − 重叠` ≈ 1.6 s 而不是 2.5 s，重叠部分 ≈ 0.96 s ≈ 一轮模型往返
（model 桶均值 1,329 ms/轮）。这正是 §10 修正过的口径：**投机构建省的是"编辑完成 → 模型提出构建"
的间隔，不是整个构建**。

**三、总墙钟在这个玩具任务上测不出来。** 原始均值 B 22,613 ms vs S 23,268 ms（+2.9%，处理臂反而
更慢），但轮数是模型自己选的（B 10.0 轮 / S 11.0 轮），而轮数就是墙钟的主项；按轮归一化
**2,261 ms → 2,115 ms/轮（−6.5%）**。n=5、轮数 6–14 的方差下，这个数字不足以判断 ≥2% 的验收线，
但方向上与结论二一致。按 §10 的真实工作负载（~70 s 任务、31–84 s 构建、~0.6–2 s 一轮）换算，
省下的 1 轮 = **1.4–2.9% 墙钟**，正好压在验收线上。

### 11.3 判定：留代码，默认关

- **留**：`reuseRatio = 1.00` 说明"猜了没人要"的情况没有发生（判死线是 < 0.5），单飞也不依赖开关、
  防的是 `target/` 互踩。
- **默认关**：收益是每任务 ~1 轮模型往返，在 2 核 CI 上换来的可能只是 Maven 抢 CPU；真要开，
  应该按机器规格（≥8 核）或按 `reuseRatio` 的在线观测开，而不是默认全开。
- **对 §3 验收线的修正**：验收不该看"build 桶降幅"，而应看"每任务是否省下 ~1 轮"。这条 A/B 已经
  把这个数测出来了（≈0.96 s），不用再重测。

### 11.4 A/B 顺手抓到的两个问题

**（a）`phases` 单位错了 1e6——已修复并单独提交。** 第一次真跑的 `phases` 报
`wallMs = 33672370000`（33.7 s 的任务报出 389 天），因为 `RunPhaseClock` 的默认时钟是
`System::nanoTime`（纳秒）而字段全叫 `*Ms`。分区不变式仍然成立，所以 5 个单测全绿——它们注入的
假时钟喂的是毫秒，**默认时钟的单位是唯一没人看的地方**。修复：默认时钟改成
`System.nanoTime() / 1_000_000`，并加一个用真实时钟的回归测试（先用旧默认值验证它确实变红：
`wallMs must be milliseconds, was 62905400`）。修完后同一任务报 `wall = 30,689 ms`、
`setup 725 / isolation 459 / run 29,056 / merge 776 / finalize 132`，分区仍然成立。

**（b）`reuseRatio` 看不到"缓存命中"这种胜利——已修复。** 若投机构建在模型提出请求之前就跑完了，
按需构建会走 `BuildCache` 短路（毫秒级返回 `cached`），此时 `reused` 不增——因为 `reused` 只统计
**加入在途航班**。玩具仓库构建只有 ~2.5 s、模型一轮 ~1 s，本来就有一半概率落在这一侧；真实工作负载
（构建 31–84 s）基本不会。

修复：结果里多了两个**按航班**计的字段——`flightsUsed`（有人在等）和 `flightsUnclaimed`
（跑完了还没人来要，后续请求由缓存应答），两者相加 = 投机航班数。`reuseRatio` 保留原语义
（按请求），但不再单独当判死线；读报告先看这两个计数（§8）。

### 11.5 复现方式

```powershell
# 每臂一个后端 JVM（开关是 system property，不能热改）
$env:BROWSER4_SERVER_OPTS = "-Dbrowser4.agent.workspace=D:\tmp\b4-ab " +
    "-Dbrowser4.agent.finishGateCheck=warn -Dbrowser4.agent.build.speculative=<true|false>"
pwsh ./b4w.ps1 agent run "<同一句 prompt>" --coding --follow
pwsh ./b4w.ps1 agent result <taskId> --json | jq '.output.raw | fromjson | .result.timings'
```

**一个坑**：后端 java 进程会继承调用者的 stdout 句柄，脚本连续跑多次会在第一次的管道上死等；
跑批脚本每次结束时停掉后端（下一次重新起 JVM，顺带完成臂切换）。

## 12. B1 实测：浏览器任务的四桶（2026-11-15）

**口径**：真实后端（从源码构建的 bundle）+ DeepSeek + headless Chrome，3 个形态不同的任务
（① 单页取文本 ② 多步导航 + 站内跳转 ③ 表单填写 + 提交）。每个任务一次 `agent run`，
读 `agent result --json` 的 `timings`。数字都是毫秒。

| 任务 | 形态 | 轮数 | 墙钟 | model | tool | callback | **W-tool 占比** |
|---|---|---|---|---|---|---|---|
| T1 | example.com，取标题与首段 | 12 | 43,313 | 19,716 | 22,707 | 91 | **53.4%** |
| T2 | browser4.io，摘要 + 找文档 + 跳转 | 24 | 103,778 | 49,761 | 51,994 | 196 | **51.0%** |
| T3 | selenium 表单，填写 + 下拉 + 勾选 + 提交 | 14 | 42,576 | 20,193 | 20,852 | 103 | **50.7%** |

**均值：W-tool 51.7% / W-model 48.0% / callback 0.2% / build 0**（coding 任务是 1.2% / 77%）。
§2 B1 的翻转条件（W-tool ≥ 25%）成立，而且形态差异几乎没有影响——三个任务的占比落在 50.7–53.4%。

### 12.1 工具时间是什么构成的

从运行日志看，三个任务共 51 次工具调用，其中 **45 次是 `b4_run`**（T1 14/15、T2 20/23、T3 11/13）
——也就是说 agent 每做一个页面动作，就起一次 CLI 子进程。日志里带耗时的调用：

| 动作 | 单次耗时 | 说明 |
|---|---|---|
| `b4_run open --headless <url>` | **10.1 s** | 冷启动 Chrome + 导航 + settle |
| `b4_run goto <url>` | **10.0 s** | 导航 + settle（不含浏览器启动） |
| `b4_run click e33` | 2.6 s | 定位 + 点击 + 确认 |
| `b4_run eval --json …` | 2–4 s | 每条都是一次子进程 + MCP 往返 |
| `b4_run eval --json "Promise.all([...])"` | 9.6 s | 页内 fetch 多个候选 URL |

T2 有 5 次带耗时的 `b4_run` 合计 34.7 s（占该任务 tool 桶 52 s 的 67%），再加 `b4_wait` 7.3 s。
所以 **W-tool 的主体不是"页面本身慢"，而是"每个动作的固定开销 + 导航 settle"**。

### 12.2 这推翻了什么

- **推翻"工具慢不是方向"**（本文 §1 的原话）：那只对 coding 成立。浏览器任务里工具时间就是主项之一。
- **不推翻 P0-3 的否决**，但理由从"时间太少（1.1%）"换成"**这些动作按语义必须串行**"——
  同标签页 DOM 是单一共享可变资源（§2 D1），`fill → click → snapshot` 的顺序本身就是任务语义；
  导航 settle、`randomDelayMillis("type")` 90–240 ms/字符是**反爬要求**，不能压缩。
  把两个同页动作塞进 `async` 只会让它们排队，还多付 §4 里那 6 条前置修复。

### 12.3 收益去了哪里（三条新方向，按性价比排序）

1. **每动作固定开销**（最直接、无需新并发）：45/51 次调用是 `b4_run`，每次都要起进程 + 建连 + 一次 MCP 往返。
   可以在一个 `b4_run` 里批量执行多条命令（CLI 已有 `batch`，服务端也有 batch 端点），
   或者让 agent 直接用服务端浏览器工具而不是套一层 CLI。**这条不需要任何并发改造**，也不碰 D1/D2。
2. **跨标签页流水线**（§2 E）：因为 W-tool 从 1.2% 变成 51.7%，多标签并行的天花板比 coding 口径下的估计高得多
   ——这也解释了既有的"4 个 1.5 s 慢页 28 s → 4.6 s"（6×）为什么成立。它是**吞吐**轴，不是单任务延迟轴。
3. **页面预取**（浏览器侧的"投机"）：与 C1 同构，但**没有数据支持**，且 §5 已明确在没有更多证据前不做。

### 12.4 还没测的

- 3 个任务都在同一台机器、同一天、`headless`；GUI 模式与人化输入的比例会不同（`type` 的字符延迟在大段输入里占比更高）。
- 没有单独的"页面内动作耗时"埋点：`toolMs` 是黑盒，12.1 的构成是从日志里逐条读的，不是新埋点。
  要精确回答"其中多少是子进程/往返、多少是页面"，需要在 `b4_run` 里记 client/server 两段耗时。

## 附录：本文引用的数据

| 指标 | 数值 | 出处 |
|---|---|---|
| 4 任务墙钟 / 轮数 / 调用 | 280.5 s / 59 / 197 | 计划 §0.1 |
| model / build / tool / callback / 未覆盖 | 216 s (77%) / 29.2 s (10%) / 3.2 s (1.2%) / 0.6 s (0.2%) / 26.4 s (9%) | 计划 §0.1 |
| 单次请求固定延迟 | ≈ 0.6 s | 计划 §0.2（R²=94.7%） |
| 每个输出 token | ≈ 4.05 ms | 同上 |
| prompt cache 命中率 | 92.6% | 同上 |
| 历史基线（P0-1 之前）构建占比 / 零编辑重建 | 1332 s (46%) / 756 s (26.4%) | 计划 §1 |
| 同轮 17 调用 | 17.2 s 中工具时间 0.45 s（97% 不在工具里） | 计划 §0/§1 |
| 多标签并行采集 | 28 s → 4.6 s（4 慢页） | crawl-multitab-parallel |
