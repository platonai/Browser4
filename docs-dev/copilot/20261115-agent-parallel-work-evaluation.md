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
>
> **修订 v4（2026-11-15，E-1~E-5 已做完）**：§13.2 的 5 条工程待办全部落地，见 §14。
> 三条要记住的：**① 那 256 ms 的 dev 模式开销就是一段 `connect_timeout(250 ms)` 打在一个关闭的
> loopback 端口上**（这台机器上关闭端口的 `ECONNREFUSED` 要 ~2 s 才回来），修完 `--version` 从
> 278–358 ms 降到 50–78 ms，后端活着时端口决策是 0 ms；**② payload 走 stdin 之后带 payload 的命令
> 重新落进直连子集**，dev 下一个动作的固定开销从 ≈500 ms 变成 ≈50 ms（生产 ≈220 → ≈20 ms）；
> **③ `b4.run` 现在报告 `launch/startup/http/server/net` 五段**，`eval` 那 2–4 s 不再是一个黑箱
> ——真页面上量过：一次 1.5 s 的页内等待是 `server 1514 / total 1548 ms`，即 98% 在页面。
> E-5 顺带把 `ModuleGraph` 分支覆盖从 52% 拉到 87.5%。
>
> **索引**：本文的待办散在各节，**§13「剩余待办索引」是唯一汇总**（需决策 / 工程待办 / 流程卫生 / 已关闭），
> 每条都指向解释它的那一节。看到待办先看 §13。

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

**这条命令本身验证过**（2026-11-15，开关的真实风险是"kapt 被拖进并行"，不是参数拼错）：
`MavenBuildSupport.buildThreads("compile", skipTests=true)` 生成的就是
`mvnw -T 1C -pl <module> -am compile -DskipTests -q`，直接跑——
单模块 reactor（`-pl browser4-coding -am`）10.7 s、**12 模块 reactor**（`-pl browser4-agent-tools -am`，
含 kapt）**48.5 s**，两次都 exit 0。这证明参数位置与 compile-only 的并行路径可用；
但它只是"跑通一次"，不构成 kapt 线程安全的证明，所以默认值仍然是关。

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

**原始样本与跑批脚本已入库**：
[20261115-wait-split-experiments/](20261115-wait-split-experiments/README.md) —— §11 与 §12 的
10 + 3 组 `result.json` / `run.log`、三个任务 prompt、两个 runner 脚本，以及复现命令和那四个坑。
表里的数字都能从这些文件重算。

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
   §12.4 把这个"固定开销"量出来了：**dev ≈0.48 s/动作、生产等价 ≈0.22 s/动作**；
   按 B1 的 11–20 次调用，就是每任务 5–10 s（dev）/ 2.4–4.4 s（生产），占墙钟 12–23% / 5–10%。
   做法与风险见 §12.6。
2. **跨标签页流水线**（§2 E）：因为 W-tool 从 1.2% 变成 51.7%，多标签并行的天花板比 coding 口径下的估计高得多
   ——这也解释了既有的"4 个 1.5 s 慢页 28 s → 4.6 s"（6×）为什么成立。它是**吞吐**轴，不是单任务延迟轴。
3. **页面预取**（浏览器侧的"投机"）：与 C1 同构，但**没有数据支持**，且 §5 已明确在没有更多证据前不做。

### 12.4 每动作的固定开销，实测（2026-11-15）

`b4_run` 在 Windows 上每次调用的路径是：`ProcessBuilder` → **PowerShell** →
`browser4-cli.exe` → HTTP → 后端 → CDP。把这三段的**地板**单独量出来（各 5 次）：

| 段 | 命令 | 均值 | 极值 |
|---|---|---|---|
| PowerShell 外壳 | `pwsh -NoLogo -NoProfile -NonInteractive -Command $true` | **199 ms** | 187–227 |
| PowerShell 5.1（备用外壳） | `powershell -NoLogo -NoProfile -NonInteractive -Command $true` | 178 ms | 163–199 |
| CLI 启动，**在仓库 checkout 里** | `target/debug/browser4-cli.exe --version` | **282 ms** | 268–294 |
| CLI 启动，checkout 外（`D:\tmp`、`C:\Windows`） | 同一个 exe `--version` | **15–22 ms** | 13–33 |
| CLI 启动，显式 `--server` 或 `BROWSER4_CLI_SERVER` | `… --version` | 18–26 ms | 14–64 |
| CLI 启动，`BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1`（关掉 dev 模式） | `… --version` | **24 ms** | 17–46 |
| CLI 经 PowerShell 启动（checkout 里） | `pwsh -c "& browser4-cli.exe --version"` | 511 ms | 490–546 |

两个要点：

1. **固定开销分两段，生产/开发不一样**：
   - **PowerShell 199 ms / 动作**：发生在**后端**的 `CliProcessManager` 里，生产同样付。
   - **CLI 那 282 ms 是 dev 模式独有的**：同一个 exe 在 checkout 外只要 15–22 ms。
     进 dev 模式的判定一触发（在 checkout 里、且没显式 `--server`），每次调用多付 ≈256 ms；
     `BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1` 把它压回 24 ms。
     另一个 checkout（`Browser4-4.16-feat`，23 个 worktree）实测 **1,007 ms**，说明这笔开销随 checkout 状态变化。
   - 所以**生产等价**的每动作固定开销是 ≈0.22 s（199 + ≈20），dev 下才是 ≈0.48 s。
   - **这两段后来都降了**（§14.1 / §14.3）：dev 的 CLI 启动 278–358 → 50–78 ms，带 payload 的命令
     也不再起外壳。dev 下每个动作的固定开销从 ≈0.48 s 变成 ≈0.05 s，生产等价 ≈0.02 s。
2. **B1 的 51.7% 因此被 dev 开销抬高了，但结论不变**：按 15/20/11 次调用折算，
   dev 每任务多付 3.6/5.2/2.9 s。扣掉后 tool 桶 22.7→19.1、52.0→46.8、20.9→18.0，
   **生产等价 W-tool ≈ 48%（49.2 / 48.4 / 47.0）**，仍是"工具与模型各占一半"。

**这条被证伪了两次，记下来**：我先假设"dev 用的是 debug 二进制所以慢"——换成 bundle 的 release 版
（293 ms vs 282 ms）几乎没变；再假设"生产同样付"——checkout 外一测只有 15–22 ms。
两次都是"在同一位置量另一个变量"才纠正的，不是靠推理。

### 12.5 还没测的

- 3 个任务都在同一台机器、同一天、`headless`；GUI 模式与人化输入的比例会不同（`type` 的字符延迟在大段输入里占比更高）。
- ~~**调用内部**的三段（CLI 启动 / HTTP 往返 / 页面动作）还没有埋点~~ → ✅ 已埋（§14.2）：
  `[timing] launch/startup/http/server/net`，一次 `status` 的实测是
  `startup=56ms http=5ms`（热）/ `startup=545ms http=34ms`（冷）。
- ~~**dev 模式那 256 ms 到底花在哪没定位**~~ → ✅ 已定位并修复（§14.1）：一段
  `connect_timeout(250 ms)` 打在一个关闭的 loopback 端口上；`--version` 从 278–358 ms 降到 50–78 ms。
  下面这段是**当时的排查记录**，留着当"排除项清单"：
  已排除：state dir（换新的仍慢）、`.browser4` 下的 6 个 worktree
  （挪走仍慢）、AOT 缓存开关、promo/自动升级、`BROWSER4_CLI_ALLOW_STALE_BUNDLE`、`BROWSER4_RUNTIME_DIR`、
  root-search 起始目录。已确认：只在 checkout 里发生、`--server`/`FORCE_REMOTE_BUNDLE` 能绕过。
  事后看，`--server`/`FORCE_REMOTE_BUNDLE` 之所以能绕过，是因为它们**跳过了 dev-mode 的端口决策**
  ——排除项一路正确，只是没有一项指向"那一步里有个 TCP 探测"。

### 12.6 由此产生的三个候选（按"收益 / 风险"排序）

1. **去掉 Windows 上的 PowerShell 中间层**（每动作 −199 ms，且这是**生产**也付的一段）。
   命令本身是 `browser4-cli.exe <argv>`，只有当参数里含 shell 元字符时才需要外壳。风险点很具体：
   `CliProcessManager` 的历史注释记录了 2026-10-29 因为 argv 引号被 PowerShell 二次解析而改用
   `-EncodedCommand` 的一次修复，动它必须有等价的转义测试。
2. **一个 `b4_run` 跑多条命令**（CLI 已有 `batch`）：直接把 11–20 次调用压成 3–5 次，
   固定开销按调用数线性下降。不需要动 shell 层。**✅ 已落地为 `b4.batch`（§14.4）**。
3. **定位 dev 模式那 256 ms**（`--version` 在 checkout 里 282 ms、在外面 22 ms）：它不影响终端用户，
   但**开发者敲的每一条命令**都要付，且另一个 checkout 是 1 s。12.5 列了已排除的假设。
   **✅ 已定位并修复（§14.1）**：是一段 `connect_timeout(250 ms)` 打在一个关闭的 loopback 端口上，
   不是 state/更新/缓存。
4. **查 CLI 启动本身**（checkout 外 15–22 ms，已经不是问题）——这条从候选里划掉：
   它只在 dev 模式下才贵，发布版没有 290 ms 的问题。

### 12.7 候选 1 已落地（2026-11-15）

`CliProcessManager` 现在对**无引号、无元字符、纯 ASCII** 的命令行直接 exec
（`ProcessBuilder(binary, argv)`），不再起外壳；其余命令（`eval --json '{…}'`、管道、重定向、
`C:\path`、非 ASCII 查询）一字不改地走原路径。子集判定是 `directArgvOrNull`，故意"保守而不聪明"：

- 只接受可打印 ASCII（PowerShell 前导存在的理由就是钉 UTF-8 控制台 I/O，不能顺手扩大影响面）；
- 双/单引号、反引号、反斜杠、`$`、`|`、`&`、`;`、`<`、`>`、`(){}[]`、`*`、`?`、`!`、`^`、`#`、`~` 一律拒绝；
- 至少一个 token。

这条命令行的读法唯一（按空白切分），而外壳对它的读法也相同，所以是**语义等价**的加速；
顺带的好处是退出码变成子进程自己的，不再经 `exit $LASTEXITCODE`。

代价与收益：每个"简单"动作省掉外壳启动——**dev 下 511 → 282 ms，生产等价 219 → 20 ms**。
B1 的 45 次 `b4_run` 里有多少落在子集会随任务而变（`open`/`goto`/`click`/`tab list`/`snapshot -v 0`
都是），`eval --json "…"` 那种不会。

**落地后真跑了一次**（同一句 prompt、同一个页面，样本见
[20261115-wait-split-experiments/b1/task4-after-shell-bypass-*](20261115-wait-split-experiments/b1/)）：
bundle 确实按新源码重建了（日志里有 `Rebuilding from source`），结果 `tool=16.2 s / 40.8%`、
墙钟 40.9 s。但**这个对比不能用来证明省了多少**：模型这次只用了 8 次 `b4_run`（上次 14 次），
计划不同；按调用平均反而更高（1.62 → 2.02 s），因为剩下的调用更重。

把两次真实调用的**命令行分类**才是有用的数：落在"直连子集"里的只有 T1 4/14、T4 3/8
（约 30–40%）——其余绝大多数是 `eval --json "…"`，带引号，必须走外壳。
所以这条改动的实际收益是**每任务 ~0.6–0.8 s**（3–4 次 × 199 ms），不是"每个动作 −200 ms"。
要吃到剩下那 60–70%，得让带 payload 的命令不必经过命令行（`--stdin` / `--file`），
而 `b4_run` 目前把 stdin 关掉了（历史上为了不让读 stdin 的命令挂死）——那是另一件事，没有做。

验证：`CliProcessManagerTest` 14/14（新增 3 例：简单命令行解析到二进制本身、10 种 shell 语法仍走外壳、
子集拒绝项逐条）、`B4CliToolExecutorTest` 13/13、`browser4-agentic` **1775 例全绿**；
按 PR 门禁的排除项（`-Dsurefire.excludedGroups=ManualOnly,RequiresAI,E2E,…,Slow,Heavy,…`）
跑 `browser4-coding,browser4-agentic,browser4-agent-tools` 三模块：**BUILD SUCCESS**，
本次新增的测试类都在里面执行了。
**没有**做的：跨标签页/批量（候选 2）、dev 模式 256 ms 的定位（候选 3）、带 payload 命令的 stdin 化。
——三条后来都做了：批量与 stdin 见 §14.3/§14.4，那 256 ms 见 §14.1（一段 `connect_timeout` 打在一个
关闭的 loopback 端口上）。

## 13. 剩余待办索引（2026-11-15 收尾时）

本文的待办散在各个小节里，这一节是唯一的索引。**状态**一栏指"下一步是谁的决定/动作"。

### 13.1 需要决策（阻塞其它选择）

| # | 事项 | 状态 | 依据在哪 |
|---|---|---|---|
| D-1 | **C1 投机编译的默认开关策略**：永久默认关，还是按核数/在线 `reuseRatio` 自动开 | 待决策 | §11.3（判定：留代码默认关）、§3 的"开关"段、§12.5 的读数口径 |
| D-2 | **跨任务并行（吞吐轴）是否作为产品目标** | 待决策 | §2 E、§12.3 第 2 条（B1 把它从 1.2% 的天花板抬到 ~48%） |

### 13.2 工程待办（E-1~E-5，全部已实施，见 §14）

| # | 事项 | 状态 | 依据在哪 |
|---|---|---|---|
| E-1 | **带 payload 的命令不经过命令行**（`--stdin` / `--file`），吃掉直连子集剩下的 60–70% | ✅ **已实施**（§14.3）：`b4.run(args=…, stdin=…)`，payload 走子进程 stdin | §12.7 末段、§12.6 候选 2 |
| E-2 | **一个 `b4_run` 跑多条命令**（CLI 已有 `batch`）：压调用数，但牺牲中间观测 | ✅ **已实施**（§14.4）：`b4.batch(commands=…)` = 一个进程；每步输出保留，丢的是步间判断 | §12.6 候选 2 |
| E-3 | **dev 模式 256 ms 定位**（`--version` 在 checkout 里 282 ms、外面 22 ms；另一个 checkout 1 s） | ✅ **已定位并修复**（§14.1）：一段 `connect_timeout(250 ms)` 打在一个关闭的 loopback 端口上；`--version` 50–78 ms | §12.4、§12.5 |
| E-4 | **`b4_run` 内部三段埋点**（CLI 启动 / HTTP / 页面），解释 `eval` 那 2–4 s | ✅ **已实施**（§14.2）：`[timing] launch/startup/http/server/net` + 四个指标 | §12.5 |
| E-5 | **`ModuleGraph` 分支覆盖 52%**（模块整体指令 78.5% / 行 82.1%，门禁已过） | ✅ **已补测**（§14.5）：分支 87.5% / 指令 95.8% / 行 100% | 本条为收尾时实测，未写进正文 |

### 13.3 流程/卫生（有观察，未改）

| # | 事项 | 状态 | 依据在哪 |
|---|---|---|---|
| H-1 | **`4.16.x` 的推送只触发 PowerShell Tests**：PR 门禁最近一次是 10-31、CI 最近一次是 9-28，本系列新增的 Maven 测试目前没有门禁在执行（本地已按门禁配置跑绿） | 需决定是否开 PR 触发门禁 | 本条为收尾时用 `gh run list` 实测 |
| H-2 | **方法级 `@Tag("Slow")` 不起过滤作用**（`-Dsurefire.excludedGroups=Slow` 仍跑 14/14；类级 `@Tag("Fast")` 有效，0/0）。`browser4-agentic` 里 3 个类受影响 | 只记录，影响是秒级 | 本条为收尾时实测 |
| H-3 | **本机临时目录**：`D:\tmp\ab-results`、`D:\tmp\b1-results`、两个 runner 已入库，可删；`D:\tmp\b4-ab` 是 A/B 夹具，仓库有副本 | 可删 | [20261115-wait-split-experiments/README.md](20261115-wait-split-experiments/README.md) |
| H-4 | **本仓库 6 个残留 worktree**（`git worktree list`） | 可清 | — |

### 13.4 已关闭（不要再讨论）

P0-3 同轮只读并发（coding 口径，§5）、P1-2/P1-3 回调与检查并行（§5）、A2 记账外移（§2 A）、
D1–D5 结构性不可并行（§2 D）、P0-2 多模块融合（§10 实测否决）、
"用 release 二进制换收益"（§12.4 实测证伪）、"CLI 启动本身要 290 ms"（§12.5，checkout 外 15–22 ms）。

## 14. 实施记录：E-1~E-5（2026-11-15 收尾）

> §13.2 的 5 条工程待办一次做完。它们其实是一条线上的东西：**E-3 是每个动作固定开销的底座，
> E-1/E-2 是它的两个杠杆，E-4 是把这笔钱量出来的埋点，E-5 是顺手做的小活**。
> 原始输出（改前/改后、端口探测、PowerShell 地板）见
> [e3-dev-startup/](20261115-wait-split-experiments/e3-dev-startup/README.md)，脚本可重跑。
>
> 环境：本 checkout、`target/debug/browser4-cli.exe`、Windows 20 核。§14.1–§14.4 里有两次测量：
> 一次没有后端（量 CLI 自己的固定开销），一次用 `b4w.ps1 -Rebuild` 把 bundle 按本 checkout 的源码
> 重建后跑真页面（量 `server` 段与 `batch`），两边都注明是哪一次。

### 14.1 E-3：那 256 ms 是"给一个没人监听的端口做 TCP 探测"

**它到底是什么。** 加一层 `BROWSER4_CLI_TRACE_STARTUP=1` 的阶段打点后，答案一行就出来了：

| 阶段（`--version`，checkout 里） | 改前 | 改后 |
|---|---|---|
| hooks / runtime / flags / invocation | 0 / 0 / 20 / 20 ms | 0 / 0 / 5 / 5 ms |
| `state` / `persisted-state` | 21 / 21 ms | 6 / 6 ms |
| `dev-mode`（`find_browser4_root`） | 21 ms | 6 ms |
| **`port-8283-registry`**（本 workspace 的托管进程注册表） | 未做 | 1 ms（累计 7 ms） |
| **`port-8283-connect`**（`TcpStream::connect_timeout`） | **277 ms** | **49 ms** |
| 合计 | 278 ms | **50 ms** |

改前的 256 ms 全部花在**一个** `connect_timeout(127.0.0.1:8283, 250 ms)` 上：8283 是本
checkout 记录下的 dev 端口，当时没人监听。这台机器上**连一个关闭的 loopback 端口不会立刻
RST**，SYN 被丢掉，`ECONNREFUSED` 要 ~2 s 才回来（实测 8283/8284/18182/9999 都是
2,023–2,084 ms；而 8282 上有另一个 checkout 的后端在监听，2 ms 就连上了）。所以那个 250 ms
的预算**每次都被整份花掉**——预算本身是上限，不是"多久算不健康"的阈值。

**修正过程中被打脸一次，记下来。** 第一版"优化"是把托管进程注册表放在探测前面（"自己的后端
还活着就不用探"），结果 `dev-port` 变成 **1,185 ms**：`managed_port_has_live_server` 里的
`is_process_running` 在 Windows 上是**起一个 `tasklist`**，每调一次 ~1 s。所谓"便宜的注册表检查"
本身就是那笔钱。改成 `OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)` + `CloseHandle`
（微秒级，`ERROR_ACCESS_DENIED` 也算活着）之后降到 1 ms。

**改了 3 处**（`cli/browser4-cli/src/daemon.rs`、`managed_processes.rs`）：

| 改动 | 理由 |
|---|---|
| `dev_port_is_usable`：注册表**优先**，命中即"这个端口是我的" | 自己的后端在跑时（agent 任务的常态）完全不探测，只剩一次小 JSON 读 |
| `DEV_PORT_PROBE_TIMEOUT` 250 → **30 ms** | 真正的监听者在内核里完成握手（亚毫秒），"预算内没答案"本来就已经等于"不是我的"；预算只决定**空闲端口**的探测花多久 |
| `is_process_running`：`tasklist` → `OpenProcess` | 见上，1 s → 1 ms |

**结果**：checkout 里的 `--version` 从 278–358 ms 降到 **50–78 ms**（trace 的 `total` 是 50 ms；
checkout 外 14–25 ms，差的那 30 ms 就是"这台机器上探一个空闲端口"的地板）。另一个 checkout 的
1 s 是同一段代码在更多端口被占时的表现，这次没有单独复测。

**自己的后端活着时是 0 ms（后补测的）。** 上面的 30 ms 是"没有后端在跑"那一侧。后端起来之后
`BROWSER4_CLI_TRACE_STARTUP=1` 只剩注册表那一条，**连 `port-8283-connect` 都不出现**——即
`dev_port_is_usable` 用注册表命中直接回答，一次探测都没做：

```
[startup] dev-mode=6ms
[startup] port-8283-registry=6ms
[startup] dev-port=6ms
```

而 agent 任务的常态正是"本 checkout 的后端活着"，所以 `b4.run` 为端口决策付的是 **0 ms**
（那两个 `6ms` 是进程到此为止的累计，不是这一段自己的耗时）。

### 14.2 E-4：`b4.run` 内部三段的埋点

**想量什么。** `eval` 那 2–4 s 到底是 CLI 启动、网络、还是页面。现在一次调用被切成 5 段：

| 段 | 含义 | 谁测 |
|---|---|---|
| `launch` | 进程创建之前的花费：外壳解释器 + spawn | `CliProcessManager`（= 子进程耗时 − CLI 自报 `total`） |
| `startup` | 进程启动 → 第一个**工具**请求：参数解析、config/state、dev 端口决策、就绪探测 | CLI 自己 |
| `http` | 工具调用 HTTP 往返总计（send → body 读完） | CLI 自己 |
| `server` | `http` 里后端自报的处理时间（页面动作） | 后端 → `X-Browser4-Duration-Ms` 响应头 |
| `net` | `http − server`：派发、socket、序列化 | 两者之差 |

**实现**

| 文件 | 内容 |
|---|---|
| `cli/browser4-cli/src/timing.rs`（新） | `Timing` 收集器（`startup/http/server/calls/launch` + 一次性 footer）+ `[startup]` 阶段 trace；**默认关闭**，`BROWSER4_CLI_TIMING=1` 打开 footer，`BROWSER4_CLI_TRACE_STARTUP=1` 打开逐阶段行。收集器是值对象，测试自建实例，不碰全局 |
| `cli/browser4-cli/src/main.rs` | `timing::init()` 作为进程第一句；退出前 `emit_footer()`；阶段标记 `hooks/runtime/flags/invocation` |
| `cli/browser4-cli/src/daemon.rs` | 端口决策的阶段标记（`state/persisted-state/dev-mode/dev-port/port-<p>-registry/port-<p>-connect`） |
| `cli/browser4-cli/src/http.rs` | `call_tool_with_timeout` 里记 `note_tool_request()`（钉住 startup）+ `record_http(往返, 响应头)` |
| `MCPToolController.kt` | 每次 `call-tool` 回 `X-Browser4-Duration-Ms`（响应头，不改 JSON 契约） |
| `CliProcessManager.kt` | 子进程环境固定带 `BROWSER4_CLI_TIMING=1`（每次一行、~90 字节）；从 stderr 里**摘掉** footer 与 trace 行，解析成 `CliResult.timing`，并补上 `launchMs`；指标 `browser4.cli.{launch,startup,http,server}` |
| `B4CliToolExecutor.classifyCliResult` | `-Dbrowser4.agent.b4.timing=true` 时把 `[timing] …` 追加到结果末尾，默认不显示 |

**读到的数**。先是对一个**真实后端**跑 `status`（一次 `list_sessions` 往返）：

```
冷：[timing] startup=545ms http=34ms server=0ms net=34ms calls=1 total=581ms
热：[timing] startup=56ms  http=5ms  server=0ms net=5ms  calls=1 total=62ms
```

`server=0ms` 是因为那台后端还是旧代码（没有 `X-Browser4-Duration-Ms`）——没有头时整个往返
都记进 `net`，这正是设计里的降级：老后端/非 Browser4 服务不会让这条线变成假的。

**后端也按新源码重建之后**（`b4w.ps1 -Rebuild`，真页面 `example.com`），`server` 段就活了，
而且回答的正是 §12.5 留下的那个问题：

```
eval --json "1+1"                               : startup=24ms http=15ms  server=7ms   net=8ms calls=1 total=41ms
page-side 1.0 s wait + document.title (--await) : startup=22ms http=1016ms server=1009ms net=7ms calls=1 total=1039ms
page-side 1.5 s wait + document.title (--await) : startup=23ms http=1523ms server=1514ms net=9ms calls=1 total=1548ms
```

**慢 `eval` 的 2–4 s 是页面（98%），不是 CLI 启动、也不是网络。** 同一批采样里还有一条
`startup=719354ms`——那是 `b4w.ps1 -Rebuild` 的 12 分钟 Maven 重建 + 12 s 后端启动，
按定义就该算进 startup，不加滤镜。

**为什么要 `launch` 这一段。** CLI 的时钟在进程内部开始，**它看不见谁把它拉起来的**：同一条
`status` 直接跑 `total=62ms`，套一层 `pwsh -EncodedCommand` 也是 `total=62ms`，而墙钟多了
~220 ms。父子之差只有调用方能算，所以由 `CliProcessManager` 记。

**测试**：`CliTimingTest` 11 例（footer 解析、未知字段、`server` 钳到 `http`、stderr 剥离、
trace 行剥离、未知 footer 形状、`launch` 渲染与回读）；`CliProcessManagerTest` 新增
"footer 被解析且不进 stderr""stdin payload 原样送达"；`MCPToolControllerTest` 新增
"`call-tool` 一定带 `X-Browser4-Duration-Ms`"；Rust 侧 `timing::tests` 8 例。

### 14.3 E-1：payload 走 stdin，不再经过命令行

**接口**：`b4.run(args="eval --stdin --json", stdin="document.title")`。payload 落在
`CliRunRequest.stdin` → 子进程 stdin（写完后关闭，命令看到 EOF），`args` 里只留命令本身。
`batch --json`、`htmlsnapshot inspect --stdin`、`fs write --stdin` 同理。

**为什么这是"吃掉 60–70%"的那一半**：§12.7 的直连子集只接受"无引号、无元字符"的命令行，
而 `eval --json "…"` 因为带引号必须走外壳。payload 挪到 stdin 之后，命令行长这样：

| 命令 | 改前 | 改后 |
|---|---|---|
| 取标题 | `eval --json "document.title"`（外壳） | `eval --stdin --json` + stdin（**直连**） |
| 多条命令 | 每条一个进程 | `batch --json` + stdin（**直连**） |
| 写文件 | `fs write --content "…"`（外壳） | `fs write --stdin` + stdin（**直连**） |

每动作省下的是外壳那一段（本机 `pwsh -EncodedCommand $true` 实测 210–228 ms，与 §12.4 的
187–227 ms 一致），叠加 E-3 的 256 ms 之后，dev 下的固定开销：

```
改前  220（外壳）+ 278（dev 启动）≈ 500 ms/动作
改后    0（直连）  +  50（启动）  ≈  50 ms/动作
生产等价：199 + 20 ≈ 220 ms/动作  →  0 + 20 ≈ 20 ms/动作
```

**守卫没有被 payload 绕开。** 这是这次最容易做错的地方：`b4.run` 的三道守卫
（生命周期命令、外部路径、收窄的 access preset）都只看 `args`，而 payload 是**看不见**的。
`b4.run(args="batch --json", stdin='["goto x","stop"]')` 因此必须与 `b4.batch` 一样逐条检查
——现在如此（`stdinBatchRefusal` + `batchStepRefusal`），拒绝文案点名第几步。非 batch 的
payload（JS、CSS、文件内容）**不做文本扫描**：它可能是任何东西，而按关键字拒一次正常的
`eval` 比漏掉一次更糟；那类 payload 能做的事在 E-1 之前就能做。

**测试**：`B4CliBatchTest` 12 例（见 §14.4）、`CliProcessManagerTest`
"stdin payload 原样送达（引号/反斜杠不丢）"。

### 14.4 E-2：一个 `b4.run` 跑多条命令 → `b4.batch`

**接口**：`b4.batch(commands="goto https://x\nsnapshot -v 0\nclick e12", bail=false)`。
换行分隔就够了——模型写的是短序列，不是序列化格式；行首的 `- ` / `* ` / `1. ` 会被剥掉
（模型爱把"每行一条"写成项目符号）。

**落法**：`args` 恒为 `batch --json`（直连子集），命令列表作为 JSON 数组走 stdin。所以
**N 条命令 = 1 个进程 + 1 次 MCP 往返**，且每一步都不需要引号。按改后的固定开销估算
（只数省掉的**固定**部分，页面动作两种方式都要付）：4 条一批省 3 ×（进程 ~50 ms + 往返 ~10 ms）
≈ 0.18 s/批（dev），生产等价 ≈ 0.09 s。

**真后端上量了一次**（3 条 `eval`，`example.com`，dev，后端与页面都热）：

```
batch --json <<< ["eval document.title","eval document.URL","eval 1+1"]
    : startup=31ms http=42ms server=20ms net=22ms calls=3 total=79ms   （一个进程，三次后端调用）
同样三条，分三次 CLI 调用
    : 墙钟 166 ms（≈55 ms/次）
```

三条省 ≈87 ms，和上面的估算同向；省的是固定开销，`server` 那 20 ms 两种方式都要付。

**代价（就是 §13.2 说的那个决定）**：**每一步的输出都在**（CLI 逐条打印，失败行是
`Batch command N failed (<command>): <reason>`，退出码 5 表示至少一步失败），**丢的是
"步与步之间的判断"**——后一条命令的参数如果要依赖前一条的返回值，就不能批。所以规则很简单：
**参数已定的序列用 `b4.batch`，需要看结果再决定的用 `b4.run`**。

**守卫**：逐条跑与 `b4.run` 相同的三道检查，任何一条被拒则**一条都不执行**，文案点名第几步
（`PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED | tool=b4.batch | detail=command 2 (stop) was
refused: …`）。用 `PermissionAudit` 的信封而不是自造前缀，是因为 `ToolOutcome` 只把**行首**的
`PERMISSION_DENIED | code=` 当拒绝——被拒的批必须显示成 `[fail]`，否则它看起来就像跑过了。
批里的步骤不能用 `--stdin`（stdin 被命令列表占了），payload 要落成文件（`eval --file x.js`）或内联。

**测试**：`B4CliBatchTest` 12 例——换行/项目符号切分、JSON 列表编码、两种 entry 形状、
一条命令列表 = 一个进程（断言 `args == "batch --json"` 且 stdin 是那三条）、`bail` 转发、
空列表拒绝、生命周期步骤被拒（点名步号）、`b4.run` 的 stdin 版本同样被拒、非 batch payload
不被当命令扫、payload 原样进 `CliRunRequest`、无 payload 时保持 null、`[timing]` 仅按开关显示。

### 14.5 E-5：`ModuleGraph` 分支覆盖 52% → 87.5%

`browser4-coding` 单跑 `ModuleGraphTest` + JaCoCo（`-Pquality-gate -Djacoco.check.skip=true`）：

| 计数器 | 改前 | 改后 |
|---|---|---|
| BRANCH | 52% | **42/48 = 87.5%** |
| INSTRUCTION | 78.5% | **713/744 = 95.8%** |
| LINE | 82.1% | **100%（77/77）** |

新增 8 例，补的都是**不该走**的分支：父 pom 里没有自己的 artifactId（不是模块，不能顶着父名
注册）、`<parent>` 里没有 artifactId（不产生父边）、`dependencyManagement`/`build` 里的
`<dependency>` 不是编译边（版本钉版、插件 classpath）、同名 artifactId 取先写的那个、
依赖环终止、`cachedGraph` 命中 / pom 变化后重建 / `clearCache`、`scanPoms` 只认模块 pom、
`format` 的两个判词（`(none)` 与 ⚠ 漂移）。剩下的 6 个分支是 I/O 故障路径
（`visitFileFailed`、pom 读失败），要 OS 级故障注入才碰得到，没有为它造 ACL。

### 14.6 验证

| 范围 | 命令 | 结果 |
|---|---|---|
| Rust CLI 全量单测 | `cargo test --manifest-path cli/browser4-cli/Cargo.toml --bin browser4-cli` | **2229 passed** / 0 failed / 4 ignored |
| `browser4-agentic` 快测试（PR 门禁口径） | `mvn -o -pl browser4-agentic test -Dsurefire.excludedGroups=ManualOnly,RequiresAI,E2E,…,TestInfraCheck` | **1798 passed** / 0 failed，BUILD SUCCESS |
| CLI 侧新增（agentic） | `mvn -o -pl browser4-agentic test -Dtest=CliTimingTest,B4CliBatchTest,B4CliToolExecutorTest,CliProcessManagerTest` | **50 例全绿**（11 + 12 + 13 + 14） |
| 后端埋点（rest） | `mvn -o -pl browser4-rest test -Dtest=MCPToolControllerTest#callToolStampsTheHandlerDuration` | **1/1 绿** |
| 模块图（coding） | `mvn -o -pl browser4-coding test -Pquality-gate -Dtest=ModuleGraphTest -Djacoco.check.skip=true` | **17/17 绿**，覆盖见 §14.5 |

**真后端跑过一遍**（不是单测）：`b4w.ps1 -Rebuild` 用**本 checkout 的源码**重建 bundle 并起后端，
然后直接调 CLI，读 `[timing]`——`server` 段在真实页面上出现（`server=1009ms` / `http=1016ms`），
`batch --json` 一个进程跑完三条命令，本 workspace 的后端活着时端口决策一次探测都不做。
原始输出见 [e3-dev-startup/real-backend.txt](20261115-wait-split-experiments/e3-dev-startup/real-backend.txt)。
这一步同时验证了 `X-Browser4-Duration-Ms` 两端接得上（后端新代码 + CLI 新代码），以及
`batch --json` 在真后端上确实逐条执行、逐条打印。

**没有做的**：另一个 checkout 那 1 s 的单独复测；通过 `agent run` 端到端跑一次
`b4.run(stdin=…)`/`b4.batch`（那需要真实模型，工具层由 `B4CliBatchTest` 与上面的 CLI 级验证覆盖，
中间只剩一层 `CliRunRequest` 透传）；`--file` 作为 payload 通道的工具封装（`stdin` 已覆盖，
`--file` 一直可用）。

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
| dev 模式 CLI 启动（`--version`） | 278–358 ms → **50–78 ms** | §14.1、[e3-dev-startup/](20261115-wait-split-experiments/e3-dev-startup/after.txt) |
| 关闭的 loopback 端口探测 | ~2 s 才 `ECONNREFUSED`（探针预算 250 → 30 ms） | §14.1 |
| 每动作固定开销（dev / 生产等价） | ≈500 / ≈220 ms → **≈50 / ≈20 ms** | §14.3 |
| PowerShell 外壳（`-EncodedCommand $true`） | 210–228 ms | §14.3、§12.4 |
| `b4.batch` 一批 4 条的固定开销节省 | ≈0.18 s（dev）/ ≈0.09 s（生产） | §14.4 |
| 真页面 `eval` 的拆分（1.5 s 页内等待） | startup 23 / http 1523 / **server 1514** / net 9 ms | §14.2、[real-backend.txt](20261115-wait-split-experiments/e3-dev-startup/real-backend.txt) |
| 真后端 `batch` 3 条 vs 3 次调用 | 79 ms（1 进程）vs 166 ms | §14.4 |
| 后端活着时的 dev 端口决策 | 0 ms（注册表命中，无 TCP 探测） | §14.1 |
| `ModuleGraph` 分支 / 指令 / 行覆盖 | 52% / 78.5% / 82.1% → **87.5% / 95.8% / 100%** | §14.5 |
