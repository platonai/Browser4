# RobustBrowserAgent#run v2 设计（CLI 工具循环引擎）

> 状态：规划稿 v0.2（评审修订版，未实现） · 日期：2026-08-22 · 范围：`ai.platon.pulsar.agentic.agents.RobustBrowserAgent#run`
> 评审：见《robust-browser-agent-run-v2-review.md》——按评审结论裁剪进程防线、统一输出缓冲、
> 补充两段式取消与版本对齐、修正动机表述。
> 原则：v1（observe→act 引擎）保留为显式可选（`--engine=observe-act` / `-Dbrowser4.agent.runEngine=observe-act`），
> 通过 `AgentConfig.runEngine` 切换；**默认引擎已是 CLI_TOOL_LOOP（2026-08-23 起）**。
> 设计参考：`D:\workspace\ds-harness\deepseek-harness\docs-dev\process-management-analysis.md`（2026-08-22），
> 进程管理部分对齐其三层能力缝（tool → service → subprocess）与进程树管控细节。
> 补充参考：`D:\codebase\codex\docs-dev\process-management.md`（Codex 进程管理分析），
> 引入会话化执行（yield 窗口 → job/session 句柄）、PDEATHSIG / Job Object 树级包装、
> 环境白名单净化、HeadTailBuffer 输出缓冲。

## 1. 背景与现状

### 1.1 v1 执行链路

```
run(task)
  → run(ActionOptions)
    → resolveProblemInCoroutine      # 整体超时 + 追踪
      → resolveProblemWithRetry      # 重试 / 熔断 / 预算错误
        → doRunAgentLoop             # while step < maxSteps
            → prepareStep            # context、页面未变检测、非 coding 自动导航搜索引擎
              → step → act → doObserveAct
                → doObserveActObserve:
                    updateBrowserUseState (ARIA 快照)
                    + 高亮 + 截图（vision 模型）
                    → inference.observe（LLM：ARIA+截图+提示词 → JSON ActionDescription）
                    → toObserveResults（候选）
                → 逐候选 act(observe) 执行工具
            → no-op / text-only stall 熔断
            → 最终总结 + finish gate 校验
```

### 1.2 v1 的已知瓶颈（实测证据）

- **每步上下文爆炸**：ARIA 树 + 截图 + 工具列表逐轮重发。实测工具选择消息 68,799 字符，超过 PulsarRPA `ChatModelSettings` 默认 64,000 字符上限被截断（该默认值与模型无关，已在 `forceLlmMaxInputTokenLength` 中提升到 900,000）。
- **格式错配崩溃**：模型按 `## Tool List` 的 Kotlin 示例返回 `tab.type(text=..., selector=...)`，`TextToAction.generateActions` 用 Jackson 强按 JSON 解析 → `JsonParseException`，异常被吞，动作静默丢弃，任务以残缺结果"成功"收尾（维基多步任务实测）。
- **双套浏览器通路**：进程内 `tab.*`/`browser.*` 工具与 `cli.*` 子进程并存，语义与维护成本双份。
- **上下文卫生不可控**：快照大小由实现决定，SKILL.md 推荐的 `-v 0 --stdout`、定点提取等节流手段无法介入。

## 2. 改造目标与原则

### 2.0 真实动机（评审修订）

本设计的核心价值不是修 v1 的 bug：64k 截断是 PulsarRPA 配置默认值（已由
`forceLlmMaxInputTokenLength` 提升），JSON 解析崩溃一行容错即可解。
真正的产品方向是**工具面标准化**：同一份 SKILL.md + browser4-cli 接口，
既能驱动后端内 agent，也能移植到外部 agent 环境（Codex 等），agent 只学一套
浏览器自动化接口。v1 bug 的消除是附带收益。另注意：进程内 tab/browser 工具面
仍存在于后端，v2 只是不在提示词中披露，并非消除。

1. **避免 v1 的"提示词 + snapshot"方案**：不再每步注入 ARIA 树、截图和 JSON 动作解析。
2. **发 coding 工具集 + 主 SKILL.md 给 AI**，让模型通过原生 function calling 自主决定调用哪个工具。
3. **浏览器操作一律走 browser4-cli 子进程**（`cli.run`），`browser4-agentic` 提供准确的子进程调用、监控与管理。
4. **SKILL.md 所需工具在新电脑上永远可用**：bundle 自带 CLI 与技能资源，兜底自动安装。
5. **v1 不动**：默认引擎保持 observe→act；新引擎通过配置/入口切换。

## 3. 目标流程

外层骨架（run 契约、resolve/retry/trace、熔断、超时、finish gate、最终总结、关闭/取消）全部保留，
仅替换内环 `doObserveAct` 为**原生工具循环**：

```
doRunAgentLoop（每轮 = 一次 AgentToolCallLoop.generate()）
  ├─ system: 专用系统提示词
  │     = 角色规则（用 browser4-cli 驱动浏览器，遵循 SKILL.md）
  │     + coding/cli 工具签名（langchain4j 原生 tool specs）
  │     + 主 SKILL.md 全文
  │     + 上下文卫生规则（优先 snapshot -v 0 --stdout / htmlsnapshot get 定点提取）
  ├─ user:   任务
  └─ 循环: 模型自主调 cli.run("browser4-cli ...") / coding.* / system.skillDoc
          → 工具结果回填 → 继续
          → 直到模型调用 system.taskComplete(...)
```

### 3.1 完成协议（替代 JSON 探测）

新增原生工具 `system.taskComplete(summary, keyFindings, filesChanged, problems)`。
模型完成任务时调用它；循环从工具参数构造
`ActionDescription(isDecidedComplete = true)`，**原样复用 `onTaskCompletion` 的
finish-gate 校验**（零工具执行拒绝、gate 交叉核对）与最终总结生成。
不做任何字符串/JSON 内容探测，从协议层面消灭 v1 的解析脆弱性。

### 3.2 熔断与控制复用

- 每轮 `generate()` 执行过 ≥1 个工具调用即视为有效工作；纯文本轮次计入 stall 熔断
  （复用 `nextTextOnlyStallCount` 语义）。
- `maxSteps` 语义映射为 `maxTurns`；`resolveTimeoutMs`、重试、熔断、`AgentTokenBudget`、
  `RequestTokenLimiter`、`ToolLoopCompressor` 全部沿用。
- 页面未变检测从"ARIA 状态对比"改为"CLI 输出指纹对比"（可选，例如同一 `snapshot` 输出连续重复）。

## 4. 组件设计

### 4.1 `CliProcessManager`（核心新组件，browser4-agentic）

替换 `CliToolExecutor.callFunctionOn` 中 `shell.execute("$cliPath $args")` 的拼串调用。
进程管理拆成三层能力缝，每层职责单一（对齐 deepseek-harness）：

```
cli.run(args=..., timeoutSeconds?, workingDir?)
  → CliToolExecutor                      # 工具消费层：校验参数、组装请求 DTO
      → CliProcessManager.resolve(CliRunRequest) → CliRunSpec   # 服务定义层：显式默认值 + 封顶
      → CliProcessManager.run(spec) / start(spec)               # 只接受已解析 spec
          → CliSubprocessRuntime.spawn(CliSpawnSpec)            # 子进程层：真正落地 spawn + 进程树管控
```

**request/spec 分离（显式 > 隐式）**
- `resolve(request)` 显式填充默认值并封顶：`timeoutMs` 默认 120s、封顶 600s；`outputBufferBytes` 默认 1MiB；
  `graceMs` 3s；并发上限；队列上限。默认值集中在 owning implementation 的 `resolve()`，
  可经配置覆盖，不在各层藏 `?? default`。
- 加载/首次使用时 fail-loud 校验（CLI 缺失、上限非法等），失败给出安装指引而非静默降级。

**deadline 融合与归因**
- 把「用户取消（agent close / 任务 cancel）」与「超时」熔成一个 deadline（AbortSignal 语义）；
  事后只把执行器自己的定时器归为超时，外层取消一律算 aborted——**timedOut 与 aborted 互斥**。
- 归因规则：谁拥有 deadline 谁负责归因（`CliProcessManager` 层）；子进程层只认信号、不猜原因。

**准确调用**
- **M1 定案：放弃 tokenizer，走 shell 单 argv**（评审风险项已闭环）：
  v2 工具集已含 `coding.shell`（任意命令执行），`cli.run` 防 shell 注入无安全增益；
  tokenizer 反而要承受 X-SQL 引号解析风险。故命令字符串作为**单个 argv 元素**交给平台 shell：
  - Windows：`pwsh -NoLogo -NoProfile -NonInteractive -Command <UTF-8 前导 + "& '<binary>' <args>">`
    （pwsh 缺失回退 powershell 5.1，UTF-8 前导防 OEM 代码页乱码）；
  - POSIX：`/bin/sh -c "<binary> <args>"`；
  - 唯一执行器层转义：**二进制路径引号**（单引号包裹，路径含单引号翻倍），其余参数零转义——
    语义与用户在终端输入完全一致（对齐 deepseek-harness / Codex）。
- stdout/stderr 分开捕获、UTF-8 解码；结果按 `ToolOutcome` 上限截断。

**输出采集（评审统一：有界头尾缓冲）**
- 原始捕获：HeadTailBuffer 头尾都保留，上限 `outputBufferBytes`（默认 1MiB）；
- 返回模型：按 `returnMaxTokens`（默认 10K token）截断，追加 `[truncated: kept head X + tail Y]` 标记；
- 移除 spill 文件机制：browser4-cli 输出是短命文本，无持久化需求；实测出现超限必需场景再引入。

**进程树终止（两级升级 + 承诺到底）**
- POSIX：detached 进程组（spawn 后自成组长），SIGTERM → 等 `graceMs` → SIGKILL，信令面向 `-pgid`；
- Windows：`taskkill /PID <pid> /T /F` 走整树；
- **M1 实证（2026-08-22）**：Windows 上经 `start`/ShellExecute 启动的进程**不在
  taskkill /T 的树内**（测试中 `cmd /c "start /b ping…"` 的孙进程在树杀后存活）——
  Job Object 在 Windows 上不是"更稳"而是**必需**（kill-on-close 覆盖所有子进程，
  无论父链）；JVM 内接入 Job Object 需要 JNI/第三方库，列为 M1 之后的专项。
- **grace 定时器不清除**：leader 已结算仍照发 SIGKILL，SIGTERM 被 trap 的孙进程逃不掉；
- **父进程死亡防护（Codex 补充）**：
  - Linux：`prctl(PR_SET_PDEATHSIG, SIGTERM)` + fork/exec 竞态重校验（重新 `getppid()`）——
    后端 JVM 被 kill -9 也不留孤儿 shell；
  - Windows：**Job Object**（kill-on-close 句柄，`TerminateJobObject` 一键杀全树）替代/叠加
    `taskkill /T`（taskkill 依赖树仍可寻址，Job Object 更稳）；
  - 所有 kill 都面向整棵进程树（进程组 / Job Object），从不只杀直接子进程。

**spawn 树级包装（Codex 补充）**
- POSIX：`setsid()` 新会话（不继承控制终端）+ `setpgid` 自成组长 + PDEATHSIG；
- stdin 用 `Stdio.null()`（防止命令探测到 stdin 管道而挂起等待输入），stdout/stderr `piped()` 捕获；
  交互/长驻场景才保持 stdin 打开。

**孤儿/僵尸防线（评审裁剪：12 → 6）**
1. POSIX detached 进程组，信令面向 `-pgid`，组消失回退直接 child；
2. Windows Job Object kill-on-close（`TerminateJobObject`）+ `taskkill /T` 兜底；
3. SIGTERM → grace → SIGKILL 承诺到底（grace 定时器不清除，trap 的孙进程逃不掉）；
4. Linux PDEATHSIG（`prctl` + `getppid` 竞态校验）——父进程死亡自动 SIGTERM；
5. 组合拆卸：terminate + join 全部活树，失败同步强杀并抛 AggregateError；
6. agent close / 后端退出钩子 + `CliJobRegistry` kill+await，fail-loud 告警孤儿。

评审移除（browser4-cli 是短命 HTTP 客户端，为长期交互 shell 设计的机制不适用）：
僵尸组 /proc 扫描、spill 文件、PID 复用永久边界、PTY 关闭阶梯、管道排水限时；
实测出现对应事故再恢复。

**环境卫生**
- **白名单净化（Codex 补充，比"擦洗"更强）**：`env_clear()` 后只注入策略允许的变量——
  `PATH`、`SystemDrive`、`SystemRoot`、`ComSpec`、`PROCESSOR_ARCHITECTURE`（Windows 关键变量）、
  `TMP/TEMP`、`HOME`、`BROWSER4_CLI_SERVER`（M0 确认的官方注入 env，强制连回同一 backend）、
  `BROWSER4_CLI_DISABLE_PLUGIN_WARM_RESTART=1`（禁用插件指纹重启）、工作区变量、
  `NO_COLOR/TERM=dumb/PAGER=cat/GIT_PAGER=cat`；
  后端进程环境里的 `DEEPSEEK_API_KEY` 等密钥从根本上不进入子进程；
- Windows 大小写不敏感键覆盖。
- **M1 实证（2026-08-22）**：白名单漏掉 `SystemDrive` 时，Windows 内部组件（字体/DirectWrite
  缓存等）会把 `%SystemDrive%` 当字面相对路径，在工作目录下创建
  `%SystemDrive%\ProgramData\...` 垃圾目录——Windows 关键变量必须保留。

**取消语义（评审补充：进程级 ≠ 操作级）**
- 进程级取消：kill CLI 进程树——只停止"轮询/等待"，不停止后端上的实际任务；
- 操作级取消（M0 审计结论）：
  - crawl：`POST /api/crawl/{id}/cancel` 存在，CLI 侧有 `cancel_crawl` helper → 直接复用；
  - agent/command 任务：`POST /api/commands/{id}/cancel` 存在，CLI 已有 `agent cancel <id>` → 复用；
  - swarm：**无取消端点**（SwarmController 仅 submit/query/count/status/result）→
    记录为已知限制：swarm 任务只能 kill CLI 轮询进程 + 任务级 TTL 兜底，不能真正停止后端任务。

**前置守卫（M0 审计结论：禁止 CLI 自启/重启后端）**
- CLI 的 `daemon.rs` 在本地端口关闭时会**自建后端**（从源码构建或下载 bundle，可达分钟级），
  就绪后还可能因插件指纹/版本不匹配**重启**后端；但服务器若由外部启动（无指纹记录）则永不重启；
- `BROWSER4_CLI_SERVER=<url>` env 是官方 server URL 注入机制（`parse_global_flags`），
  设置后 `enforce_version=false`，版本重启被禁用；`BROWSER4_CLI_DISABLE_PLUGIN_WARM_RESTART=1`
  进一步禁用插件指纹重启；
- **CliProcessManager 调用前先健康检查后端**（`/actuator/health`）：后端不可达时直接报
  infra 错误（retryable），**绝不放行 CLI 自启服务器**——进程树边界因此保持干净。

### 4.1.1 会话化执行与输出缓冲（对齐 Codex Unified Exec）

`cli.run` 不再"跑完即弃"，采用 yield 窗口双结局模型：

- **窗口内退出** → 返回带 `exit_code` 的结果（无 job 句柄）；
- **仍在运行** → 按命令族分流（评审修订）：crawl / swarm / loop 等已知长命令族，
  若 CLI 本身支持 task-id/异步语义则优先走**原生异步协议**（提交 → 轮询，与后端任务句柄关联）；
  其余进入 `CliJobRegistry` 兜底。后台退出 watcher 即使模型不再轮询，也把终止事件写入 agent history。

参数与机制：
- `yieldTimeMs` 夹在 `[250ms, 30s]`，Windows 初始下限调高（进程启动 + HTTP 首字节慢）；
- **早退宽限 150ms**：启动即崩的进程立即判失败，不进入 job 模式、不占注册表槽位；
- 注册表条目持**会话 Weak 引用**（会话销毁不反向保活），进程结束时 `releaseProcessId`；
- 输出用 **HeadTailBuffer**：头尾都保留（命令回显在头部、错误在尾部），上限 1 MiB，
  broadcast channel 流式分发给订阅者；返回模型的文本再按 `maxOutputTokens`（默认 10K）截断；
- 共享 `CancellationToken`：进程退出时取消输出任务。

**错误语义与渲染标记**
- `run()` 仅在基础设施故障时 reject（二进制缺失、spawn 失败等）；非零退出、超时杀、中止杀一律 resolve 成结果。
- 结果渲染追加机器可读标记：`[timed out after Xms]` / `[killed by signal: X]` / `[exit code: N]`，
  agent 与监控可从标记回解析。
- 错误分类与处置（评审补充）：
  | 类别 | 例 | 处置 |
  |---|---|---|
  | retryable | 超时、后端瞬时 5xx、spawn 抖动 | `retryStrategy` 重试（≤maxRetries） |
  | non-retryable | CLI 语法/参数错误（exit code 明确） | 结果回填模型，由模型修正后重发 |
  | fatal | CLI 缺失且安装失败、token 预算超限 | 中止任务并报告 |

**后台任务注册表 `CliJobRegistry`（由 yield 窗口驱动）**
- 前台命令超过 yield 窗口自动转为 job：`start()` 注册（pid、命令、归属会话/任务、起止时间），
  `list` / `kill` / `await` / `status(id)`；输出增量读取按流偏移不重复；
- 早退宽限 150ms 内的瞬时失败直接结算，不占槽位；teardown kill+await，拆卸抛错强制 fail 记录并告警孤儿。

**效率、并发与可观测**
- 子进程只是 HTTP 客户端，单次开销 ~百毫秒级；按会话并发上限（默认 2）+ 全局上限；
  队列有界（默认每会话 8），满则拒绝并在结果中提示模型稍后重试。
- `--headless` 默认注入（对齐 SKILL.md 的 AI 默认）；`agent run` 嵌套递归守卫保留并扩展
  （`cli.run` 拒绝 `agent run`/`agent-run`/`act` 家族）。
- 进程事件（start/exit/error/timeout）进 `AgentEventBus` + agent history，最终报告可引用实际执行过的命令。
- 指标（评审补充）：每任务 CLI 调用计数、spawn 率、平均/最大延迟、输出截断率、job 转换数，
  进 `InferenceMetrics`/`AgentMetrics`。

### 4.1.2 新增配置项（默认值集中在 `CliProcessManager.resolve`）

| 键 | 默认 | 说明 |
|---|---|---|
| `browser4.agent.cli.timeoutMs` | 120s | 单命令超时默认 |
| `browser4.agent.cli.maxTimeoutMs` | 600s | 超时封顶 |
| `browser4.agent.cli.graceMs` | 3000ms | SIGTERM → SIGKILL 宽限 |
| `browser4.agent.cli.maxConcurrent` | 2 | 每会话并发 CLI 子进程 |
| `browser4.agent.cli.yieldTimeMs` | 250ms（Windows 初始 10s） | 前台命令 yield 窗口 |
| `browser4.agent.cli.outputBufferBytes` | 1MiB | HeadTailBuffer 上限 |
| `browser4.agent.cli.returnMaxTokens` | 10K | 返回模型文本截断 |
| `browser4.agent.cli.queueCap` | 8 | 每会话排队上限（满则拒绝） |

### 4.2 `CliBinaryResolver`（新电脑可用性）

解析顺序：
1. 显式配置路径；
2. runtime bundle 自带 CLI 二进制（三平台打包）；
3. PATH 中的 `browser4-cli`；
4. 源码树 dev wrapper（`./b4w.ps1` / `./b4w.sh`，cwd 在 Browser4 源码树内时）；
5. 兜底自动安装：按平台拉固定版本到 `AgentPaths` 本地目录，并注入子进程 PATH。

首次调用前 `browser4-cli --version` 健康探测，结果缓存到会话状态；失败时向任务报告
"CLI 不可用 + 安装指引"，而不是让子进程静默失败。

- **版本对齐（评审补充）**：resolver 固定 CLI 版本 = 后端版本（同一 bundle 发布）；
  发现 PATH 上的版本漂移（如 4.13.7 对 4.14 后端）时优先 bundle 自带二进制并告警；
  可选运行 `browser4-cli doctor` 做兼容探测。
- **自动安装来源（评审补充）**：固定分发源（npm 包或 GitHub release 固定 tag）+ SHA-256 校验和，
  安装到 `AgentPaths` 本地目录并注入子进程 PATH。

### 4.3 技能资源打包

`skills/browser4-cli/**`（主 SKILL.md 608 行约 50KB + 28 个参考文档）随 runtime bundle 打进
classpath；`system.skillDoc(name)` 从该资源读取，**不依赖源码树**。
主 SKILL.md 全文进 system prompt（约 1.5–2 万 token，900k 上限下可接受）——
每任务一次性基线；同一会话多任务共享 system prompt 时摊销（后续任务不再重复计费）；
M4 实测摊销效果。参考文档按需经 `system.skillDoc` 拉取，不预载。

### 4.4 提示词（新 `CliAgentPromptBuilder`）

- 工具集：**coding.\*（51）+ cli.\*（3）+ system.skillDoc + system.taskComplete**；
  **不暴露 tab/browser 进程内工具**（浏览器一律走 CLI）。
- 上下文卫生规则：优先 `snapshot -v 0 --stdout`；用 `htmlsnapshot get` 定点提取；
  避免整页 dump；`crawl`/`swarm` 等长任务给出预期时长。
- 参考文档索引：system prompt 附一行"可用参考文档列表 + 读取方式"，具体内容按需读取。

## 5. 保留 / 移除 / 新增

| 保留（复用） | 移除（v2 不再走） | 新增 |
|---|---|---|
| run 骨架、history/trace、重试、熔断、超时 | `doObserveActObserve` 的 ARIA+截图+JSON 解析 | `doRunCliAgentLoop` |
| no-op/stall 熔断、finish gate、最终总结 | 每步 `updateBrowserUseState`/高亮/截图 | `CliProcessManager` + `CliBinaryResolver` |
| `AgentToolCallLoop` / `ToolLoopCompressor` / token 预算 | 非 coding 模式自动导航搜索引擎 | `system.taskComplete` / `system.skillDoc` |
| `AgentToolManager` 的 coding/cli executor | 进程内 tab/browser 工具披露 | `AgentConfig.runEngine` 开关 |

## 6. 兼容与迁移

- `AgentConfig` 增加 `runEngine: RunEngine`（枚举），**默认 `CLI_TOOL_LOOP`**；
  v1 通过显式 `observe-act` 选择，原有路径保持可用。
- `StatefulAgentRunner` 按任务选项或 `-Dbrowser4.agent.runEngine=cli` 切换；
  CLI 侧 `agent run` 新增 `--engine` 选项（**默认 `cli`**）。
- coding 模式判定照旧；v2 天然无进程内驱动绑定（等价于"全程 coding 模式 + CLI 管浏览器"）。
- `system.taskComplete` 复用 `ActionDescription`/`onTaskCompletion` 数据模型，状态持久化与
  SSE 事件通道不变。

## 7. 验收标准

- 维基多步任务（输入 Albert Einstein → 提交 → 跳转 → 返回文章标题与首段）在 cli 引擎下
  **真实完成交互**，结果引用 Einstein 文章而非主页。
- `CliProcessManager` 单测（评审裁剪后）：
  - request/spec 分离：`resolve()` 显式默认值与封顶（120s/600s、1MiB、grace 3s）；
  - deadline 融合：超时与取消互斥（timedOut 与 aborted 不同时为真），取消优先归因；
  - 进程树终止：SIGTERM → grace → SIGKILL 承诺到底（trap SIGTERM 的孙进程仍被 SIGKILL）；
  - **argv fixture**：SKILL.md + 参考文档全部命令示例 tokenize → 还原 → 不丢引号
    （含 X-SQL `WHERE x='y'`）；
  - 环境白名单：子进程 env 仅含白名单变量（PATH/SystemRoot/TMP/HOME/BROWSER4_SERVER 等），
    不含 `DEEPSEEK_API_KEY`；
  - 并发上限 + 有界队列、取消传播、teardown 兜底杀 + 孤儿告警。
- 会话化：长命令按命令族分流（原生异步协议优先，`CliJobRegistry` 兜底）、早退宽限 150ms、
  后台 watcher 写入终止事件；
- 两段式取消：kill CLI 进程后，后端长任务（crawl/swarm）也确认停止（操作级取消）；
- 树级防护：后端 JVM 被 kill -9（POSIX PDEATHSIG / Windows Job Object kill-on-close）后无孤儿进程；
- 版本对齐：bundle 自带 CLI 与后端同版本，`doctor` 兼容探测无告警。
- 默认引擎（observe→act）的既有测试全绿。
- 全新环境（无 CLI、无源码树）下 cli 引擎可完成一次真实浏览器任务（自动安装/打包路径生效）。
- 长命令（crawl/swarm）取消后无孤儿进程：注册表 kill+await 后无残留进程树。

## 8. 风险与里程碑

### 风险
- deepseek-v4-flash 经 langchain4j 的原生 function calling 兼容性——第一个里程碑先验证。
- CLI 子进程每调用 ~百毫秒级开销——并发上限 + 批处理（`browser4-cli batch`）缓解。
- 长命令（crawl/swarm）需显式时长上限与可取消性。
- 模型可能滥用 `cli.run`（引号、危险参数）——argv 安全解析 + 固定二进制 + 参数白名单。

### 里程碑
0. **M0（前置审计，已完成 2026-08-22）**：
   - `daemon.rs` 不 fork 持久 CLI daemon，但会自建/重启后端服务器（外部启动的服务器永不重启）——
     结论：前置健康检查 + `BROWSER4_CLI_SERVER` + 禁用插件热重启，禁止 CLI 管理服务器；
   - server URL 注入：官方 env `BROWSER4_CLI_SERVER`（或 `--server`），设置后版本强制重启自动关闭；
   - 取消端点：crawl `POST /api/crawl/{id}/cancel`、agent/command `POST /api/commands/{id}/cancel`
     存在；swarm 无取消端点（记录为限制）。
   三项结论已写入 §4.1（前置守卫 / 取消语义 / 环境卫生）。
1. **M1（验证 + 核心实现，已完成 2026-08-22）**：验证原生 function calling（deepseek-v4-flash +
   langchain4j）——**通过**（返回规范 `ToolExecutionRequest`）；SKILL.md token 成本实测 ≈ 17.5k；
   CLI 单次开销实测 min 38–323ms / avg 201–631ms。`CliProcessManager` + `CliBinaryResolver` +
   `CliJobRegistry` 核心已实现，8 个单测全绿（resolve 封顶、超时/取消互斥、env 白名单、
   树杀、后端前置守卫、并发上限、job 生命周期）。
   M1 覆盖：resolve/封顶、deadline 归因、进程树两级终止、argv fixture、环境白名单、
   并发上限 + 有界队列、取消传播、teardown 兜底、版本兼容探测、CLI 单次开销实测、
   SKILL.md token 成本实测。
2. **M2（已完成 2026-08-23）**：`skills/browser4-cli/**`（28 个文件）经
   `${maven.multiModuleProjectDirectory}` 资源映射打进 agentic jar（classpath `skills/browser4-cli/**`）；
   `system.skillDoc(name)` 实现（classpath 读取 + 越界防护 + 文档清单 + 120k 字符上限），3 个测试全绿；
   `build-runtime-bundle.ps1` 新增 `Install-Browser4Cli`（预编译二进制优先，缺则 cargo build --release，
   再缺则告警跳过，由 resolver 回退 PATH/自动安装）。
3. **M3（已完成 2026-08-23）**：`AgentConfig.runEngine`（`-Dbrowser4.agent.runEngine=cli` 切换，
   默认 OBSERVE_ACT 不动）；`system.taskComplete` 完成协议 + `TaskCompletion` JSON 解析；
   `AgentToolCallLoop.onToolRequest` 回调捕获 taskComplete 参数；`RobustBrowserAgent.doRunCliAgentLoop`
   原生工具循环（coding/cli/system 工具集 + 主 SKILL.md system prompt + 上下文卫生规则），
   完成走 `onTaskCompletion`/finish gate，无完成标记按异常终止上报。M3 为单轮
   `generate()` 版本（内部最多 toolLoopMaxIterations 轮）；跨轮历史压缩列为 M4 细化。
4. **M4（e2e 已通过 2026-08-23）**：`CliToolExecutor` 接入 `CliProcessManager`（M1→M3 最后拼图）；
   `runEngineOverride` 每任务开关 + REST `engine` 参数打通（`CommandToolExecutor` → `UserCommandExecutor`
   → `StatefulAgentRunner`）；`doRunCliAgentLoop` 修复为**多轮 generate + 纯文本续推 + stall 熔断 +
   文本最终回答回退**（模型常以文本作答而非调 taskComplete；有真实工具执行时接受文本为最终报告，
   finish-gate 零工具守卫兜底）；完成状态写入 `stateHistory`（否则 StatefulAgentRunner 报
   "no results"）。e2e：维基多步任务经 `-Dbrowser4.agent.runEngine=cli` 真实完成
   输入→提交→跳转→提取，返回 Einstein 文章标题与首段（status 200）。
待办（后续）：全新环境/长命令取消验证；多任务 SKILL.md 摊销验证。
