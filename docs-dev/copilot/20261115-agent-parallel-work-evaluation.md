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

## 0. 结论先行

一句话：**"等工具"这件事本身没有多少时间可抢；可抢的是"等模型"那一段——而模型等待期里唯一被数据支持、
且已经有失效原语的候选是投机编译。**

| 等待窗口 | 实测（4 个 coding 任务） | 可并行候选 | 收益天花板 | 结论 |
|---|---|---|---|---|
| 等模型往返 | **216 s / 77%** | 投机编译、健康检查、记账外移 | ≤ build 桶 29.2 s ≈ **10%** | **唯一值得做**（§3） |
| 等构建 | 29.2 s / 10% | 在构建旁边并发跑只读检查 | verify 内只读检查实测 103–132 ms → ≈ 0 | 并发不做；改做"构建本身变快"（§2 C2） |
| 等普通工具 | 3.2 s / 1.2% | 同轮只读并发 | ≤ 3.2 s = **1.1%**，2 核上更少 | 维持否决（P0-3） |
| 记账/回调 | 0.6 s / 0.2% | 异步化 | ≤ 0.6 s | 不做（P1-3①②） |
| 未归因 | 26.4 s / 9% | — | 未知 | **先测再谈**（最便宜的一步） |

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
而 W-tool + W-callback 只有 1.4%，被 77% 的 W-model 完全盖住——**盯着"工具慢"做优化，方向就是错的**。

两个缺口（本文建议优先补）：

- **浏览器任务没有四桶数据**。上面 77/10/1.2% 全部来自 coding 任务，而 coding 任务几乎不点页面。
  浏览器任务里 `type()` 是 90–240 ms/字符、导航有 settle 延迟，W-tool 很可能远超 25%——
  **一旦 W-tool ≥ 25%，P0-3 的否决前提就不成立**，需要按"标签页级流水线"重估（§2 B1）。
- **9% 的未归因**（26.4 s / 280.5 s）没人拆。它可能是进程启动、worktree 同步、报告渲染、`agent result` 落盘。
  在花任何工程时间之前先花 0.5 d 把它拆开，符合本项目"先归因再优化"的既有做法。

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
| B1 | **浏览器任务的四桶** | N3 已把 `timings{build,tool,callback,model}` 写进 `agent result`，跑 3 个真实浏览器任务读结果即可 | W-tool ≥ 25% → 重估 P0-3；< 10% → 彻底关闭这个话题 |
| B2 | 未归因 9% 的去向 | ✅ **已实施**：`RunPhaseClock` 打点（§7），读 `agent result` 的 `timings.phases` 即可 | 任一项 > 3% 再谈优化 |

B1 还有一层：**浏览器任务的 W-tool 里，等待是"故意的"**（人化输入延迟 `randomDelayMillis("type")` 90–240 ms、
导航 settle `NAVIGATION_DOM_SETTLE_DELAY_MS`）。这些延迟是反爬要求，不能压缩，只能**用别的活填满**。
这决定了浏览器侧的正确方向是"多标签页流水线"而不是"单页内并发"。

### C. 值得做（有数据支持）

| 编号 | 工作 | 收益 | 依据 |
|---|---|---|---|
| **C1** | **投机编译**：编辑成功后、模型下一轮推理期间，后台先跑一次 `compile` | 预期 build 桶的 30–60% → **3–6% 墙钟** | 模型桶 216 s 是网络等待；build 桶 29.2 s 与它严格串行；`BuildCache` 已提供失效原语。**骨架已落地（§8），默认关闭，等 A/B** |
| C2 | 构建自身并行：多模块合并（P0-2）、`-T 1C`、`mvnd` | 直接削 build 桶，历史口径下是最大单块 | 计划 §P2-2 已列，未开始；worktree 冷构建会放大收益 |

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

**开关**：`-Dbrowser4.agent.build.speculative=false`（默认开或关由 POC 数据定）。

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

- 不做同轮只读并发（P0-3）：天花板 1.1%，前置修复 6 条，2 核 CI 上收益趋零。
- 不做 `coding.verify` 内检查并行（P1-2）：`repo-consistency` 实测 103–132 ms。
- 不做回调异步化（P1-3①②）：0.6 s / 197 次 = 3 ms/次。
- 不做多模型/多 agent 并行推理（§2 D3）。
- 不为了"看起来并行"而并发化任何会写状态的东西——本项目已经因为"看起来并发、实际排在一个 tab 上"踩过坑
  （多标签采集的 2 ms 内双报）。

## 6. 建议顺序（成本 / 收益）

| 序 | 动作 | 成本 | 预期 |
|---|---|---|---|
| 1 | 拆未归因 9%（B2） | ✅ **已实施**（§7） | 拿到 `timings.phases` 后再决定后面值不值得做 |
| 2 | 浏览器任务四桶（B1） | 0.5 d，读 `agent result` 即可 | 决定 P0-3 结论是否翻转 |
| 3 | 长等待看门狗（A1） | ✅ **已实施**（§9） | 消除 100 s 级静默失败 |
| 4 | 投机编译 POC + A/B（C1） | 骨架 ✅ **已实施**（§8）；A/B 待真实任务 | 3–6% 墙钟，且 `BuildCache` 已铺好路 |
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

**还没做**：在真实任务上跑一遍读出数字。这一步需要真实模型 + worktree，本机的
`agent run --coding` 会做完整校验，不是纯测量。

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

- `reuseRatio = reused / speculativeBuilds`：判死线。低于 0.5 说明猜的构建大多没人要。
- `speculativeMs`：猜的 CPU 账单。它**不是**省下的时间——省下的是 `reused` 次构建本会花的墙钟。
- 计数是进程级，结果里是**任务区间增量**（`Stats.deltaSince`，并向下钳零）。不做增量，
  同一 JVM 里第二个任务会继承第一个任务的数字，A/B 两个臂就分不开。

**还没做**

- **A/B**：需要真实任务对比 `reuseRatio` 与墙钟；按 §3 的验收，墙钟降幅 < 2% 就回滚。
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
