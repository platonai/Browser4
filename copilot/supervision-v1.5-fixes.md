# v1.5 缺陷修复轮：静态排查 → 全量修复（2026-08-21）

> 依据：`browser4-code-tool-supervision-v1.5.md`（linkcheck 监督轮发现 2 个 P0 + 1 个 P1 + 5 个 P2）
> 方式：四个独立只读排查（P0.1 链、P0.2 链、DevTaskPlanner 系列、workspace/端口/状态杂项）→ 逐项修复 → 定向回归验证。
> 范围约束：不提交（git add/commit/push 未执行），全部改动留在工作区。

## 修复清单

### P0.1 每任务首步 LLM 必崩（空历史 → 空白 user 消息）

根因链已实证：`DefaultHistoryRenderStrategy.render()` 空历史返回 `""` → `PromptBuilder.buildMultistepMessageListStart()` 无条件 addUser → `SimpleMessage.toChatMessage()` 对 blank 文本调 `TextContent.from` → LangChain4j 1.5.0 抛 `"text cannot be null or blank"` → ContextToAction 包装成 `"Unknown exceptiontext cannot be null or blank"`、state=OTHER、tokenUsage{0,0,0}。

三层防护（均已落地）：
1. **源头占位**：`DefaultHistoryRenderStrategy.render()` 空历史返回 `"No execution history yet."`。
2. **入库过滤**：`AgentMessageList.addUser`/`addSystem` 跳过 blank 内容。
3. **转换兜底**：`AgentMessageList.toChatMessages()` 丢弃 blank 的 user/system/tool 消息（tool 角色为排查报告 S7 补充点）。

测试：`PromptBuilderBlankMessageTest`（黄金回归：全新会话首步消息列表无空 user 且转换不抛）、`ChatMessagesTest`（4 用例，含 S1 summarize 空 textContent 场景）、`DefaultHistoryRenderStrategyTest` 与 `PromptBuilderHistoryStrategyTest` 各新增 1 用例（原断言空串的旧用例改为断言占位串）。附带文案修正：`ContextToAction` 错误包装 "Unknown exception" 与 brief 之间补冒号空格。

### P0.2 工具循环溢出丢弃进度 + text-only 熔断误杀

1. **溢出摘要**：`AgentToolCallLoop` 溢出 modelError 从"仅工具名清单"升级为"工具名 + 最近 6 条结果首行摘要（≤2000 字符）"——真实进度不再随循环消息链一起丢弃。
2. **摘要续传**：`PromptBuilder.buildMultistepMessageListStart` 在"有 toolCallResult 或 有上步 modelError"时都渲染 Previous Step Result；`buildPrevToolCallResultMessage` 容忍 toolCallResult 为 null（仅渲染 modelError 段）。
3. **熔断重置**：`ActionDescription` 新增 `internalToolsExecuted`（默认 false，不进 JSON 白名单）；工具循环通过 `onToolExecuted` 回调上报；`RobustBrowserAgent` 的 text-only 熔断对"本步执行过 ≥1 个内部工具"的步清零计数（计数逻辑抽成纯函数 `nextTextOnlyStallCount`）。
4. **--noop-limit 耦合**：`--noop-limit`（noopLimitOverride）存在时同时抬高 textOnlyStallLimit（否则默认 5 仍会误杀长任务）。
5. **enforce 顺序**：`AgentToolCallLoop.generate()` 中压缩先于 `requestTokenLimiter.enforce()`——限流校验的是压缩后的上下文，不再在压缩器本可修复的列表上直接抛异常。
6. **failOnOverflow 开关**（可选硬失败语义）：新配置键 `browser4.agent.toolLoop.failOnOverflow`（默认 false）+ `ToolLoopOverflowException`。开启后溢出异常**穿透** `ContextToAction.generate` 的泛化 catch（新增专门 rethrow 分支），沿 InferenceEngine.observe → act → RobustBrowserAgent.step 传播到 resolve 级重试/中止（传播链已逐层核实无中间吞异常点）；默认保持"继续走下一步 + 摘要注入 prompt"。附带：泛化 catch 现在同时设置 `agentState.exception`——生成崩溃的步此前在历史里显示 isSuccess=true，现正确标记失败。
7. 跨步续跑（报告建议 4）本轮未做——压缩式续跑（checkpoint + 溢出摘要）已覆盖其主要收益，原始消息链延续留待后续设计。

**新增 AgentToolCallLoopTest 时揪出的两个真实生产 bug（一并修复）**：
- 溢出 modelError 的字符串拼接运算符优先级错误——`if (executed.isNotBlank()) A else "" + digest` 被解析为 `if (...) A else ("" + digest)`，executed 非空（溢出时必非空）时摘要被静默丢弃。已加括号。
- `buildPrevToolCallResultMessage` 读取的是**当前步** state 的 `actionDescription`（构建 prompt 时恒为 null），导致 "Previous model error" 段一直是死代码、溢出摘要从未真正流入下一步。改为读 `prevState`。

测试：`AgentToolCallLoopTest`（4 用例：溢出名称+摘要格式、摘要封顶、prune 先于 enforce、回调计数）、`TextToActionParsingTest.overflowModelErrorWithEmptyContentKeepsError`、`PromptBuilderHistoryStrategyTest.prevToolCallResultRendersModelErrorWithoutToolResult`、`RobustBrowserAgentTest` 熔断计数 3 用例。

### P1.1 DevTaskPlanner 模块绑定回归

- `DevTaskPlanner.buildSteps`：mvnBuild/test 目标候选改为 `newPluginModules + modules`（同深度时新插件模块赢 tie-break）。
- 执行期同样修复：`CodingToolExecutor` devTask verify/runTests 的目标候选集加入 `plan.newPluginModules` 且优先。
- 测试：`DevTaskPlannerTest.dependentsKeysWithNewPluginTargetNewModule`（规划期）、`CodingToolExecutorTest.testDevTaskVerifyTargetsNewPluginModule`（执行期，空工作区 → 静态 ModuleMap 回退）。

### P1.2 计划 read/impact 路径缺模块前缀

`DevTaskPlanner.resolvePlannedPath`：带斜杠路径若首段非已知模块且非仓库顶级目录（cli/docs/skills/...），存在 newPluginModules 时补 `browser4-plugins/<name>/` 前缀；read 与 impact 共用。
测试：`DevTaskPlannerTest.slashedPathInNewPluginGetsModulePrefix` + `rootedPathsAreNotReprefixed`（已知模块路径与 cli 顶级路径不重复补）。

### P1.4 code 工具工作区漂移

`daemon.rs collect_jvm_opts_and_program_args()`：找到仓库根时注入 `-Dbrowser4.agent.workspace=<root>`（在 BROWSER4_SERVER_OPTS 之前，用户可覆盖；路径经 `normalize_jvm_windows_path_text` 转正斜杠、去 `\\?\` 前缀以兼容 argfile），后端 `CodingWorkspace` 已有该键优先读取。覆盖 b4w.ps1/b4w.sh/全局 CLI 全部启动路径；8182 已运行实例需重启生效。
测试：`daemon.rs test_collect_jvm_opts_injects_agent_workspace_from_repo_root`（注入断言）+ `test_user_server_opts_override_injected_agent_workspace`（用户覆盖排在注入之后）；相关 env 操作测试均加 `lock_env_mutex()` 消除并行竞态（实测修复了一次偶发失败）。

### P2.1 聚合 pom 缩进逐级漂移

根因双确认：① 取"最后一个 module 行"缩进（改为第一个）；② 替换 `</modules>` 时闭合标签行自带的 4 空格残留在新行前 → 每轮 +4。现替换整个 `    </modules>` 尾部，新行严格等于首行缩进（8 空格）。
`browser4-plugins/pom.xml` 现存漂移已手工归一（wordcount/linkcheck → 8 空格）。
测试：`CodingToolExecutorTest.testScaffoldToDirUsesFirstModuleIndent`。

### P2.2 ModuleMap 行尾空格/超 120 列

`RepoConsistencyCheck.check` 新增可选参数 `moduleMapSource`：逐行 WARNING（尾随空格、>120 列），不 fail 门禁；`repoConsistencyReport` 已接入磁盘源文本。`ModuleMap.kt` 两条 129 列 DEPENDENTS 行已拆行。
测试：`RepoConsistencyCheckTest.moduleMapFormatFlagsTrailingWhitespaceAndLongLines` + 干净文本反例。

### P2.3 MCP-over-HTTP 端口冲突

`McpHttpServer.start()`：启动前探测端口占用（`ServerSocket` 试探），被占则改用临时端口并暴露 `actualPort`。放弃"catch BindException"路线——CIO 引擎 wait=false 异步绑定，BindException 不会同步抛给调用方（实测确认）。`McpHttpServerConfiguration` 的 KDoc 已修正：mcp.http.* 为 JVM 系统属性而非 application.properties。
测试：`McpHttpServerE2ETest.startFallsBackWhenConfiguredPortBusy`。

### P2.4 -am 构建触发 ModuleMapDriftE2ETest 失败

`syncModuleMapForNewPlugin`：scaffold 时除 MODULES 外自动向四个 DEPENDENTS 键（agentic/protocol/skeleton/pdk）插入 `        "browser4-plugins/<name>",`（8 空格、单行 ≤120 列）——关闭"注册到补 DEPENDENTS 之间"的漂移窗口。不再提示 agent 手工补。
测试：`CodingToolExecutorTest.testScaffoldToDirCompletesDependentsEdges`（写回含 5 处 entry：1 MODULES + 4 DEPENDENTS）。

### P2.5 agent list 全部 queued（状态串链）

后端：
- `CommandToolExecutor "status"`：未知 id 返回 `CommandStatus.notFound(id)` 而非序列化字面 "null"。
- `UserCommandExecutor.getStatus`：owner 未知时先探测全部现有 runner/visitor（各自构造时已 restoreFromDisk），仍无则构造 runner 触发 JSONL 恢复——终态不再丢失。
- `StatefulAgentRunner.restoreFromDisk`：终态优先——同 id 已恢复终态时，后续非终态行（JSONL 乱序）不再覆盖。
CLI：
- `refreshed_agent_status()`（纯函数）："null"/空/404/无状态字段的 `{}` 不覆盖缓存；缓存为终态时禁止降级为 queued/processing。
- `sync_agent_status_to_local` 复用同一守卫（原实现只挡 "null"，404 与 `{}` 仍会污染）。
测试：`main.rs refreshed_agent_status_*` 6 用例；`StatefulAgentRunnerTest.restoreFromDiskKeepsTerminalStatus`。

## 验证记录（全部实测）

| 门禁 | 结果 |
|---|---|
| 全量 Maven 编译（skipTests） | exit 0 |
| browser4-coding 定向（DevTaskPlanner 20、RepoConsistencyCheck 18、ModuleMapDriftE2E 1、RepoConsistencyE2E 1、ModuleMap 4） | 全绿 |
| browser4-agentic 定向（P0.1/P0.2 相关 7 类 + CodingToolExecutorTest 72 + McpHttpServerE2ETest 11） | 全绿 |
| browser4-rest 定向（MCPToolControllerTest 等 4 类） | 全绿 |
| cargo test --bin browser4-cli | 1115 passed / 0 failed |

## 遗留（本轮范围外）

- P0.2-4 跨步续跑工具循环（原始消息链延续）——设计文档已列为后续项。
- P2.4 方案 B（ModuleMapDriftE2ETest 加 @Tag("E2E")）未做：会从 CI 移除保护且仓库无 Maven E2E lane，不采纳。
- 8183 监督后端仍在运行（带调参）；8182 后端为旧 bundle，P1.4 注入需重启/重建生效。
