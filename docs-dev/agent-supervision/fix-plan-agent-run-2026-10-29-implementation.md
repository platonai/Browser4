# `agent run` 修复方案 v2 — 实施记录（2026-10-29）

> 依据：[`fix-plan-agent-run-2026-10-29.md`](fix-plan-agent-run-2026-10-29.md)（P0-1…P2-2）。
> 本文只记录**已落地的代码/测试/文档**与**剩余 Gate 项**；方案本身的验收标准仍以原文件为准。
> 分支：`4.15.x`，基线 commit `33be64efb58e4626b026788a8ad2435f70ae2a82`。

## 1. 工作包落地情况

| ID | 内容 | 主要改动 | 测试 |
|---|---|---|---|
| P0-1 | `system.skillDoc` 可读 + 预算 | `ToolOutcome` 预算改为按 **method** 查表（原 `domain.method` 使整张表失效），新增 `skillDoc=8_000`，截断 marker 带 `offset=` 续读提示；`SystemToolExecutor.skillDoc(name, offset, limit)` 按 **code point** 分页，`SKILL.md` 无 `offset` 时返回 ≤2 KB 的 reference map（章节 + 参考文档 + offset），`limit` 上限 16_000；prompt 改为“先读 reference map，再按需加载 topic doc” | `ToolOutcomeTest`（预算键、8 KB、续读 offset）、`SystemToolExecutorTest`（索引、分页不重叠、limit 上限、越界文档） |
| P0-2 | `b4.run/status/wait` 统一结果分类 | 新增 `cliFailureReason`/`classifyCliResult`：非零退出码、JSON `status:error`、超时、abort、基础设施失败均判失败；`wait`/`status` 的 FAILED/CANCELLED 输出 `b4.<method> [fail] …`；失败文本保留 CLI 原始输出 + 可执行 hint（超时→分批/提高 timeoutSeconds，相对路径→working dir）；`callFunctionOn` 覆盖把失败文本变成 `evaluate.exception`（`B4CliFailureException` 继承 `IllegalStateException`，不重试），因此 `ToolOutcome` 显示 `[fail]`、`--follow` 也会打印 | `B4CliToolExecutorTest`（status:error、exit code、timeout hint、逐项 status:error 不误判、job 状态）、`B4CliToolExecutorJobTest` |
| P0-3 | `b4` cwd/workspace 绑定 + MCP 超时 | **根因**：`B4CliToolExecutor` 在构造时捕获 `defaultWorkingDir`，而 `AgentToolManager.rebindWorkspace` 只替换属性、不刷新已 `lazy` 初始化的执行器表 → 相对路径落到上一个任务的 workspace。现在**删除该构造参数**，cwd 从派发时的 `CodingAgentShell.workspaceRoot` 解析（`CodingAgentShell.workspaceRoot` 新增公开只读属性），`AgentToolManager` 不再重建 b4 执行器（也不再遗弃其后台 job）；`--timeout` 注入：`CliProcessManager.resolve` 对真实 `browser4-cli` 且 timeout>30s 且参数无 `--timeout` 时注入全局 `--timeout <s>`，外层进程超时再加 60s grace（`CliProcessConfig.cliTimeoutGraceSeconds`） | `CliProcessManagerTest.timeoutInjection`、`B4CliToolExecutorTest`（rebind 前后 cwd 不变式） |
| P0-4 | eval Promise/undefined 反馈 | `AbstractToolExecutor`（仍能看 `cdpType/cdpSubtype` 的最后一层）新增 `jsEvaluationIssue`：未 `--await` 的 Promise → **失败**（“re-run with --await”）；`undefined` → 成功但输出 `null  (undefined: …)` 提示（`--json` 仍为 `null`，CLI 识别前缀保持机器输出）；`ToolResultTextRenderer` 路径未改 | `JsEvaluationFeedbackTest`、`executeRaw`/`execute` 既有测试 |
| P0-5 | 有效预算 + `--token-budget` + `--follow` usage | `AgentRunEvent` 增加 `budgetTokens`/`cumulativeTokens`；`AgentTokenBudget` 增加 per-task override（`taskTotalTokens`，优先于 registry/config，`forRun()`），`RobustBrowserAgent.tokenBudgetOverride` + `lastRunTokenBudget`，runner `finally` 清理；`CommandToolExecutor`/`UserCommandExecutor`/`StatefulAgentRunner` 贯通 `tokenBudget`；CLI `--token-budget`（非法值报错而非忽略）；`--follow` 每 10 次请求或 15s 打 `tokens used / budget (%)`，80% 只警告一次，100% 打 abort 行；NDJSON `run.usage` 带 `effectiveTokenBudget`/`cumulativeTokens` | `AgentTokenBudgetTest`（override 优先级、0=unlimited）、`commands.rs` token-budget 测试、`main.rs` `build_agent_run_extra` 测试 |
| P0-6 | 失败结果语义 | `AgentTaskStatus.errorCode`/`resumable`/`effectiveTokenBudget`（`errorCodeOf` 从原因推导 `TOKEN_BUDGET`/`NOOP_LIMIT`/`CANCELLED`/`TOOL_ERROR`/`INFRASTRUCTURE`），`CommandStatus`/`CommandResult`/`TaskEnvelopes` 同步；`task.failed` 事件带 `errorCode`/`resumable`；`agent result` 对 failed/cancelled **exit≠0**（`--allow-failed` 逃生口），人类输出加 `✖ failed (<code>) · workspace · files · budget · resume:` 判决行；失败 summary 追加“本次写了哪些文件 + resume 提示” | `AgentTaskStatusTest`、`CommandStatusPageStatusTest`、Rust `agent-result`/tips 测试 |
| P1-1 | 默认 per-task workspace | `taskWorkspaceFor()`：`--resume` → checkpoint；`--shared-workspace` → 旧共享目录；否则 `<baseDir>/workspace.<taskId>`。`--fresh-workspace` 变为兼容开关（不再建 `workspace.fresh.*`）。`command.run(sharedWorkspace)` + CLI `--shared-workspace` 贯通 | `AgentResumeTest.perTaskWorkspaceIsTheDefault`、`StatefulAgentRunnerTest`（既有 27 项全过） |
| P1-2 | 失败产物归因 | `deliverableFilesOf(workspace, startedAt, mutations)`：优先 mutation log（`CodingAgentFileSystem`，已排除 scratch），否则 `mtime >= startedAt`，并排除 `.b4scratch/`、`.b4inputs/`、dot-entries；runner 的 catch/finally 传 `status.startedTime` + `mutationsSnapshot()` → 旧任务文件不再进入 `filesChanged` | `AgentResumeTest`（旧文件排除、mutation 优先、边界）、`CommandStatusPageStatusTest`（失败 summary/files） |
| P1-3 | Windows 编码 + cmd 内建 | `CliProcessManager.buildShellInvocation`：`pwsh` 与 `powershell` 均注入 `try { [Console]::OutputEncoding=UTF8; $OutputEncoding=UTF8; chcp 65001 } catch { }`；`CodingAgentShell`：`echo.` 归一化为 `echo`（校验与执行同源），`set`（无参或 `VAR=value`）放行，`if`/`for` 明确拒绝并给 `.cmd` / `python -c` 替代提示 | `CodingAgentShellWindowsBuiltinTest`（3）、`CodingAgentShellTest`/PathGuard/Boundary（55） |
| P2-1 | 输入保真/字段规格/listDir/memory/goto | `agent-run` 拒绝多余 positional（`unexpected positional arguments: [...]`）；字段规格 fixture 校验测试（75 列、无 U+FFFD、中文行仍在）；`coding.listDir` 默认 50 条 + 总数 + 扩展名汇总 + `progress.txt` 缺失提示，新增 `limit`/`summary`；`memory.search` 结果附 `taskId`/`createdAt`/`fromCurrentTask` 与 provenance 说明，prompt 明示“历史运行产物”；`goto` 无 `--load`，文档改为 `crawl`/`eval --await`/`wait --load` 推荐用法 | `AsinFieldSpecFixtureTest`、`CodingToolExecutorTest`（listDir 新参数） |
| P2-2 | 文档/help/tips | `agent.md`、`agent-workspace.md`、`config.md`、`SKILL.md`、`quickstart.md`、`eval.md`、`bulk-collection.md`、`docs/eval-command-output.md`、`help.rs`、`tips.rs`、`cli/browser4-cli/README.md` | — |

## 2. 验证命令与结果

```powershell
# 编译门（含测试编译）
.\mvnw.cmd -q -o -D"skipTests" -pl browser4-agentic,browser4-agent-tools,browser4-coding,browser4-rest -am        # BUILD SUCCESS

# 目标单测
.\mvnw.cmd -o -pl browser4-agentic "-Dtest=ToolOutcomeTest,SystemToolExecutorTest,B4CliToolExecutorTest,B4CliToolExecutorJobTest,JsEvaluationFeedbackTest,AgentTokenBudgetTest,CliProcessManagerTest,AsinFieldSpecFixtureTest"   # 72 passed
.\mvnw.cmd -o -pl browser4-agent-tools "-Dtest=AgentResumeTest,StatefulAgentRunnerTest,AgentTaskStatusTest"          # 63 passed
.\mvnw.cmd -o -pl browser4-coding "-Dtest=CodingAgentShell*,CodingAgentShellWindowsBuiltinTest"                      # 53 passed
.\mvnw.cmd -o -pl browser4-rest "-Dtest=CommandStatusPageStatusTest,CommandToolExecutorTest"                          # 30 passed

# CLI 单测
cargo test --manifest-path cli/browser4-cli/Cargo.toml --bin browser4-cli                                            # 1967 passed

# 四个模块的 fast/unit 套件（PR 门禁标签排除）
.\mvnw.cmd -o -pl browser4-agentic,browser4-agent-tools,browser4-coding,browser4-rest `
  "-DexcludedGroups=ManualOnly,RequiresAI,E2E,E2ETest,Slow,Heavy,HeavyTest,Integration,IntegrationTest,RequiresServer,RequiresBrowser,RequiresDocker,TestInfraCheck" test
# BUILD SUCCESS（agentic 1305 / agent-tools 231 / coding 479 / rest 771 个报告用例，0 failure / 0 error）

# MCP 工具文档（command.run 新增 sharedWorkspace/tokenBudget 参数）
.\mvnw.cmd -o -pl browser4-rest -DregenerateToolDocs=true test -Dtest=ToolDocGeneratorTest
# Regenerated 146 tool docs -> docs/mcp-tools.md

# CLI 全目标类型检查（含 e2e 场景文件）
cargo check --manifest-path cli/browser4-cli/Cargo.toml --all-targets
```

> 说明：`RobustBrowserAgentTest.cliEngineBrowsingProfileHidesCodingLongTail` 在 HEAD
> 上已过期（`CLI_BROWSING_CODING_METHODS` 早先为 xlsx 解析加入了 `shell`），本次按
> 现状修正断言：`coding_shell` 暴露，`shellOutput`/`mvnBuild`/`ktSymbols` 仍隐藏。

## 3. Gate 执行结果（2026-10-29 02:00–03:00）

**判定：不通过**（数据/预算维度）；行为维度大部分通过。完整证据、对比表与复现命令见
[`gate-report-agent-run-2026-10-29.md`](gate-report-agent-run-2026-10-29.md)；原始日志在
`logs/supervise-20261029/`（git-ignored），D14 取证见其中 `d14-provenance.md`。

### 3.1 取证与运行

| 项 | 结果 |
|---|---|
| D14 | HEAD `8605bccfa3`（+ 修复主体 `b30f39c101`），本树重建 bundle `builtAtUtc 2026-10-28T18:15:35Z`，`lib/pulsar-browser-4.11.27.jar` |
| Run A（human `--follow`，默认配置） | task `807f0d5f`：779 s / 223 请求 / 5.01M tokens / exit 1 / `errorCode=TOKEN_BUDGET` / `resumable=true`；**无 CSV 交付物**，`filesChanged: (none this run)` |
| Run B（NDJSON） | **未重复整任务**（已确认默认 5M 内不可能完成）；NDJSON 契约用两次短运行验证：成功运行 28 行、失败运行 9 行，`run.usage` 的 `effectiveTokenBudget`/`cumulativeTokens` 与 `task.failed` 的 `errorCode`/`resumable` 齐全 |
| 数据断言 | Run A 无产物 → 无法评估；基线 CSV 校验 5 PASS / 5 FAIL（keywords 20/20 全空、category 写成品类名、bullets 重复、乱码 `鈥`×12/`d茅cor`×3） |
| 负向 | 相对路径 `[fail]`+hint ✅、`if exist` 拦截 ✅、`--token-budget` abort + exit 码 + `--allow-failed` ✅；>30 s eval ❌（驱动层 `PT30S`）；无 `--await` Promise ❌（CLI 返回 `{}`，`b4.run` 路径被引号问题挡住） |
| 可观测 | `--follow` 44 条 `tokens x / y (%)`；80% 只告警一次；abort 有明确 `✖ failed … Token budget exceeded …` + `Tip:` |
| 与基线对比 | 时长 −16%（928→779 s），请求数持平，失败可见性/隔离/提示显著改善，交付物仍未达成（见 gate-report §6） |

### 3.2 本轮 Gate 发现并修复

| 编号 | 问题 | 修复 | 验证 |
|---|---|---|---|
| F11 | Chrome 156 在 `/json/version` 新增 `File-Path-Style`，base 4.11.25 的 `ChromeVersion` 用普通 `ObjectMapper` 反序列化 → 任何导航都死在 `createDevTools`（基线 `02807b73` 同因） | `browser4-base.version` 4.11.25 → 4.11.27（Central 2026-10-28，含 `@JsonIgnoreProperties(ignoreUnknown = true)`） | 导航冒烟 `Page Title: Example Domain`；`ChromeVersionCompatibilityTest`（2 项） |
| F15a | NDJSON 终态 `task.failed` 行只有 status/message，缺 `errorCode`/`resumable`（流式那条有；机器消费者读最后一行） | 新增 `terminal_failure_event()`；`parse_status_progress` 解析两字段 | `neg7` 校验 3/3 PASS；CLI 单测 1970 passed |
| F16 | `daemon::tests::test_expected_runtime_tag_prefers_local_bundle_in_checkout` 偶发顺序依赖失败（`Some("v4.14.0")` vs `Some("local")`），单跑/复跑通过 | 未修，先记录 | 全量复跑 1970 passed |
| F13 | `b4.run` 的参数被 PowerShell 二次解析：Java 的 argv 引号被 `-Command` 的解析器剥掉，`eval "(async()=>1)()"`、`eval --json "(…)()"` 到 CLI 前已变成多个 token → `ParserError`（Gate 里最大的重试/预算浪费源） | 见 §3.5：`-EncodedCommand` + `[ScriptBlock]::Create`，命令文本 base64 传递并通过 `escapeForPowerShellScript` 抑制 `$`/反引号展开 | `quotedArgumentSurvivesTheShell`、`encodedScriptKeepsTheCommandVerbatim` 等 5 个单测；`f13-smoke2.log` 里 `eval --json "JSON.stringify(…)"` 返回 `[ok]` + JSON |
| F13a | `eval --js "…"`（`--js` 是 `--stdin` 的布尔简写）在 `b4.run` 下读 stdin，而父进程从不关闭该管道 → 命令挂到 300 s 超时（11:43 冒烟现场） | 见 §3.5：`b4.run` 的 stdin 重定向到 `NUL` / `/dev/null`（对齐 pi 的 `stdio: ["ignore", …]`），读 stdin 的命令立即 EOF | `stdinIsClosedForArgvCommands`；`f13-smoke2.log` 第 4 条 1 s 内返回 `Stdin was empty`（exit 2） |

### 3.3 未修阻断项（下一轮按序处理）

1. **F14 驱动层 `Runtime.evaluate` 30 s 上限**：MCP/CLI 的 `--timeout 300` 覆盖不到，
   >30 s 的页面内等待不可行；错误文本只到 `[INTERNAL] browser_evaluate … (PT30S)`，未提示替代方案。
2. **F15 P0-4 的 Promise 反馈不可达**：CLI `eval "(async()=>1)()"` 退出码 0 且返回 `{}`（静默），
   F13 修好后已能稳定复现（`f13-smoke2.log` 第 3 条）；目前只有 agent 工具层单测能观察到
   “re-run with --await”。
3. **F12 `coding.shell` 可杀后端**：Gate 第一次 Run A 就是被模型 `taskkill /PID …` 杀掉后端而中断；
   `33be64` 只封了 `b4.run` 的生命周期命令。
4. **数据语义缺口**：keywords 全空 / category 用品类名 / bullets 重复 / 乱码 —— P2-1 的字段规格与
   输入保真没有覆盖到这些采集语义。
5. **默认 5M 结构性不足**：基线（5,006,041）与本次（5,012,835）都在 5M 处 abort。按方案 §1.4
   不得用 10M 掩盖；F13/F13a 修完后需用同一任务复测，再决定是否走“有证据地调整默认值”。

### 3.4 第一次 Run A 尝试（2026-10-29 02:06，F11 现场）

task `dab8f49b`：02:06:51–02:08:53，37 请求 / 614k tokens / exit 1。当时新行为已正常工作：
per-task workspace（`workspace.dab8f49b-…`）、输入物化（`.b4inputs/asin_field_description.xlsx`）、
`--follow` 的 `tokens 109.3k / 5.00M (2%)`、`b4.wait` 的 `[fail]` 分类；失败根因是 F11，
随后模型自行 `taskkill` 掉后端（F12）。证据：`a1-run-human.log` / `a1-run-human-trace.txt`。

### 3.5 F13 修复（2026-10-29 11:20–12:00）

**根因**（`CliProcessManager.buildShellInvocation`）：命令被拼成
`pwsh -Command "<preamble> & '<cli.exe>' <args>"`，Java 的 `ProcessBuilder` 按 CRT 规则给
argv 加引号（内部 `"` → `\"`），PowerShell 的 `-Command` 解析器再把它们吃掉。探针
（`logs/supervise-20261029/probe-argv/Probe.java`）对比：

| 形态 | 引号 | 子进程退出码 | 解析错误可读性 |
|---|---|---|---|
| `-Command "<text>"`（旧） | **丢** | 7 ✓ | 纯文本 |
| `-File <ps1>` | 保留 | ✓ | 纯文本，但语法错误时 **exit 0**（静默成功风险） |
| `-Command -`（stdin） | 保留 | ✓ | 纯文本，但 **非 ASCII 被按 cp936 解码**（中文丢字） |
| `-EncodedCommand <base64>`（新） | **保留** | ✓（脚本内 `exit $LASTEXITCODE`） | CLIXML ← 由 `[ScriptBlock]::Create` 预解析规避 |

**做法**：命令文本先按 UTF-8 base64 编码，再放进一段只含 ASCII 的脚本，整段以 UTF-16LE
base64 交给 `pwsh -OutputFormat Text -EncodedCommand`；脚本在运行前用
`[ScriptBlock]::Create` 解析，语法错误 → `ParserError: …` + exit 2（避免 CLIXML 和
“语法错误 exit 0”），最后 `exit $LASTEXITCODE` 保留子进程退出码。
`escapeForPowerShellScript` 把 `$` 转义为 `` `$ ``、反引号翻倍，保证命令文本**字面**传给
CLI（与 POSIX 分支对 `$`/反引号的处理一致；旧形态是靠“引号被吃掉”偶然避开展开的）。
命令行超过 30k 字符时退回旧的内联形态（Windows 命令行上限 32,767）。

**stdin**：对齐 pi 的 `spawn(..., { stdio: ["ignore", …] })` —— `b4.run` 没有 stdin 可喂，
`ProcessBuilder` 默认的管道会让 `eval --js`（= `--stdin`）读到 300 s 超时；现在重定向到
`NUL`/`/dev/null`，读 stdin 的命令立即拿到 EOF 并以明确错误退出。

**验证**：`CliProcessManagerTest` 16 项全过（含 `quotedArgumentSurvivesTheShell`、
`stdinIsClosedForArgvCommands`、编码/转义/超长回退单测）；`B4CliToolExecutorTest` 12 项过；
真实 agent 冒烟（`f13-smoke2.log`，task `11263f25`）：
`eval --json "JSON.stringify({…})"` → `[ok]` + JSON、`eval --await "(async()=>1)()"` → `1`、
`eval --js "…"` → 1 s 内 `Stdin was empty`（exit 2，不再挂住）。

## 4. 行为变更速查

| 变更 | 影响 |
|---|---|
| 每个任务默认独立 workspace（`workspace.<taskId>`） | 依赖旧共享目录的脚本需加 `--shared-workspace`；`--resume` 行为不变 |
| `agent result` 对 failed/cancelled 非零退出 | 流水线可区分成功/失败；需要旧行为时用 `--allow-failed` |
| `b4.run` 失败不再伪装成功 | 模型会看到 `[fail]` + 原因，不再重复同一失败命令 |
| 未 `--await` 的 Promise 报错（仅 agent 工具层；CLI `eval` 仍返回 `{}`，见 Gate F15） | 异步 eval 必须显式 `--await`；CLI 路径的提示尚未覆盖 |
| `eval` 的 `undefined` 输出提示行 | `--json` 仍为 `null`；文本消费者仍能看到 `null` |
| `skillDoc("SKILL.md")` 默认返回索引 | 读取正文用 `offset=0` 或 topic 文档 |
| `coding.listDir` 默认最多 50 条 | 需要全量时 `limit=0` |
| `agent run` 多余 positional 报错 | 任务文本必须作为一个参数传入 |
