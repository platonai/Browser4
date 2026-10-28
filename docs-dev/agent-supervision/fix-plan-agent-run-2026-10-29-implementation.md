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

## 3. 尚未执行（需要真实环境）

以下是方案 §5 的 Gate，需要真实 LLM key + 浏览器 + 默认配置双运行，**本次未执行**：

1. Run A（human `--follow`）/ Run B（`--follow --ndjson`），默认预算、默认 workspace；
2. §1.2 的 20×75 CSV 数据断言（表头/ASIN/榜单一致性/keywords/category/price/bullets/编码/新鲜度）；
3. §1.3 负向：`--token-budget 20000` abort + exit code、`b4.run("eval --file .b4scratch/missing.js")` 的 `[fail]`、>30s eval、`coding.shell("if exist …")`；
4. 与基线 `02807b73` 的对比表（token/时长/请求数/工具数/新产物）。

Gate 前置检查（D14）：确认后端由**本工作树**构建（jar build stamp / `git rev-parse HEAD` 与本文件一致）。

## 4. 行为变更速查

| 变更 | 影响 |
|---|---|
| 每个任务默认独立 workspace（`workspace.<taskId>`） | 依赖旧共享目录的脚本需加 `--shared-workspace`；`--resume` 行为不变 |
| `agent result` 对 failed/cancelled 非零退出 | 流水线可区分成功/失败；需要旧行为时用 `--allow-failed` |
| `b4.run` 失败不再伪装成功 | 模型会看到 `[fail]` + 原因，不再重复同一失败命令 |
| 未 `--await` 的 Promise 报错 | 异步 eval 必须显式 `--await` |
| `eval` 的 `undefined` 输出提示行 | `--json` 仍为 `null`；文本消费者仍能看到 `null` |
| `skillDoc("SKILL.md")` 默认返回索引 | 读取正文用 `offset=0` 或 topic 文档 |
| `coding.listDir` 默认最多 50 条 | 需要全量时 `limit=0` |
| `agent run` 多余 positional 报错 | 任务文本必须作为一个参数传入 |
