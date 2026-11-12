# 下线 `session.act()`：实施记录（2026-11-12）

> 依据：`20261112-generic-agentic-session-act-review.md`（路线 A）
> 基线：`da2f3fec43`（`refactor(agent): remove the observe-act engine, keep only the CLI tool loop`）
> 范围约束：不提交（`git add` / `commit` / `push` 均未执行），全部改动留在工作区。

## 0. 结论

`act()` 整条链路删除，仓库里只剩 CLI tool loop 一个 agent 引擎：

- 删除 `AgenticSession.act`、三份实现、`SessionActExecutor`、`TextToAction.generateActions`、`SINGLE_WEB_DRIVER_ACTION_GENERATION_PROMPT`；
- 删除 REST 端点 `/api/act`（`ActController` + `ActService`）与 CLI `act` 命令（含 help、`Category::Act`、`execute_act_command`）；
- 两个回调入口（`LoadService` / `ScrapeService` 的 `onPageReadyActions` / `onBrowserLaunchedActions`）改走 `session.companionAgent.run(...)`；
- 示例 `TalkToLivePage.kt` 迁移到 `companionAgent.run`；三份 README 的 `act` 行改成"已移除命令 + 替代"；
- CLI 侧保留一条迁移提示：`browser4-cli act "..."` 现在直接报
  `The 'act' command was removed. Use the CLI tool loop instead: browser4-cli agent run "<desc>" --wait`。

## 1. 迁移表（破坏性公共 API 变更）

| 已删除 | 替代 |
|---|---|
| `AgenticSession.act(action: String): List<ToolCallResult>` | `AgenticSession.companionAgent.run(task)`（`command.run` / `browser4-cli agent run`） |
| `ai.platon.pulsar.agentic.inference.SessionActExecutor`（internal） | 无（随 `act` 删除） |
| `TextToAction.generateActions(action, driver, screenshotB64)` | 无（模型响应统一由 `modelResponseToActionDescription` 解析、由 tool loop 执行） |
| `TextToAction.chatModel` / `chatModelConf`（仅供 `generateActions`） | 无（`ContextToAction.chatModel` 仍是唯一 chat model 入口） |
| `PromptBuilder.SINGLE_WEB_DRIVER_ACTION_GENERATION_PROMPT` | 无 |
| `POST /api/act` + `ActRequest` / `ActResponse` + `ActController` / `ActService` | `POST /mcp/call` 的 `command.run` |
| CLI `act <description>`（`Category::Act`） | `browser4-cli agent run "<task>" --wait` |

**行为变更**：`scrape` / `load` 的 `onPageReadyActions` / `onBrowserLaunchedActions` 仍然是"每条形如自然语言的动作"，但每条动作现在是一次 **tool loop run**（多步、可观测、失败可见），不再是"一次 LLM 翻译 + 直接派发工具调用"。

## 2. 改动清单（逐文件）

```
 README.md                                                   |   8 +-
 README.zh.md                                                |   8 +-
 cli/README.md                                               |   8 +-
 browser4-agentic/.../agentic/AgenticSession.kt              |  45 +-
 browser4-agentic/.../inference/SessionActExecutor.kt        |  56 ---（删除）
 browser4-agentic/.../inference/PromptBuilder.kt             |  36 --（提示词常量）
 browser4-agentic/.../inference/action/TextToAction.kt       | 120 +--（generateActions / chatModel / 死代码）
 browser4-agent-tools/.../crawl/service/ScrapeService.kt     |  14 +-
 browser4-rest/.../api/controller/ActController.kt           |  44 ---（删除）
 browser4-rest/.../api/service/ActService.kt                 |  48 ---（删除）
 browser4-rest/.../api/service/LoadService.kt                |  16 +-
 examples/.../examples/llm/TalkToLivePage.kt                 |  70 +-
 examples/.../examples/fuse/FusedActs.kt                     |   2 +-
 cli/browser4-cli/src/commands.rs                            |  26 --（Category::Act + CommandDef + 2 处测试）
 cli/browser4-cli/src/help.rs                                |  34 --（类别项 + act help 块 + 用例）
 cli/browser4-cli/src/http.rs                                |  69 --（execute_act_command + 2 常量）
 cli/browser4-cli/src/main.rs                                |  30 +-（早处理块 → 迁移提示、handle_act 删除）
 docs-dev/copilot/firecrawl-compatible-formats-design.md     |   2 +-
 docs-dev/copilot/real-world-scenario-cli-coverage-plan.md   |   2 +-
```

### 2.1 `browser4-agentic`

| 位置 | 处置 |
|---|---|
| `AgenticSession.kt` | 接口删 `act`（保留 `companionAgent`）；三个实现的 `executor` + `act` override 删除；类/接口 KDoc 记录下线原因与替代 |
| `inference/SessionActExecutor.kt` | 删除（唯一使用者是 `act`） |
| `inference/action/TextToAction.kt` | 删 `generateActions`、`chatModel`/`chatModelConf`（仅被 `generateActions` 使用）、`companion.baseDir` + `init { Files.createDirectories }`（无使用者的类初始化副作用）、随之无用的 import；保留 `modelResponseToActionDescription`（`ContextToAction` 仍在用）与 `TextToActionParsingTest` |
| `inference/PromptBuilder.kt` | 删 `SINGLE_WEB_DRIVER_ACTION_GENERATION_PROMPT`（唯一使用者是 `generateActions`）；当时保留的 `buildObserveResultSchema` / `OBSERVE_RESPONSE_ELEMENT_SCHEMA` 已在同日 `20261112-legacy-system-prompt-removal.md` 一并删除（整条 system prompt v1 链无生产消费方） |

### 2.2 `browser4-rest` / `browser4-agent-tools`

| 位置 | 处置 |
|---|---|
| `ActController.kt` / `ActService.kt` | 删除（`/api/act` 不再存在） |
| `LoadService.kt` | 3 处 `session.act(it)` → `session.companionAgent.run(it)`；lambda 参数改 `_ , _`；类 KDoc 说明引擎切换 |
| `ScrapeService.kt` | 同上 2 处；`loadDocument` 加 KDoc 说明 |

> 为什么不是删字段：`PromptRequest.actions` / `CommandRequest.onPageReadyActions` / `onBrowserLaunchedActions` 是公开 JSON 契约，`Prompts.kt` 的示例提示词、`ConversationServiceTest` / `CommandRunnerTest` / `PromptsTest` 都依赖它们存在。保持字段、替换引擎是兼容性代价最小的做法。

### 2.3 Rust CLI

| 位置 | 处置 |
|---|---|
| `commands.rs` | 删 `Category::Act`、`Category::Act => "act"`、`act` 的 `CommandDef`、`test_commands_map_contains_expected` 里的 `"act"`、`test_act_command_params` |
| `help.rs` | 删类别标题 `("act", "Act")`、`generate_command_help` 的 act 块、`test_generate_command_help_act` |
| `http.rs` | 删 `execute_act_command` 与 `ACT_REQUEST_TIMEOUT_SECS` / `ACT_REQUEST_TIMEOUT_ENV` |
| `main.rs` | 删 `handle_act` 与 `no_snapshot_commands` 里的 `"act"`；`is_not_page_dependent_for_async` 用例去掉 act 断言；**新增迁移提示**（见 §3） |

### 2.4 文档

| 位置 | 处置 |
|---|---|
| `README.md` / `README.zh.md` / `cli/README.md` | "Advanced and currently hidden commands"（只有 `act` 一行）→ "Removed commands / 已移除命令"，给出替代命令与原因 |
| `docs-dev/copilot/firecrawl-compatible-formats-design.md` | 已占用 REST 前缀里去掉 `api/act`，并注明 2026-11-12 释放 |
| `docs-dev/copilot/real-world-scenario-cli-coverage-plan.md` | "Act（实验）" 那条测试计划标记作废 |

`skills/browser4-cli/SKILL.md` 与 `skills/**/references/*.md` 经确认**没有** `act` 命令条目（只有 "act on a page" 这类普通用语），无需改动。

## 3. 关键决策

1. **CLI 保留一条专用迁移提示，而不是让它变成 "Unknown command"**。`act` 的描述是变长 positional，原来的早处理块就是为了在通用参数解析之前把剩余 positional 拼起来；删命令时把这段逻辑改成报错，用户直接看到替代命令（`agent run --wait`），而不是"未知命令 + 建议 attach/agent"。
   - 位置：`main.rs` 的 `cmd_map.get(command)` → `None` 分支最前面。
   - 退出码：`ExitCode::Usage`（2），与其它用法错误一致。
2. **回调改用 `companionAgent.run` 而不是删除字段**（见 §2.2 注）。
3. **保留 `modelResponseToActionDescription` 与它的单测**。它是仓库里唯一带围栏修复/`taskComplete` 分支/`ref`→`selector` 归一化的解析实现，`ContextToAction` 仍在使用；删除它会把 `TextToActionParsingTest` 一起带走，属于另一轮清理。
4. **不重新生成 code-mirror 快照**：`code-mirror/agent-tool-call-specs.json` 与 `PerceptiveAgent.kt.txt` 只镜像 `PerceptiveAgent` 的 tool specs（上一轮已从 5 条降到 3 条），`AgenticSession` 不在镜像内；`ToolSpecification.TOOL_CALL_SPECIFICATION` 没有 act 条目，因此没有漂移。

## 4. 验证

| 命令 | 结果 |
|---|---|
| `.\mvnw.cmd -o -q -pl browser4-agentic -DskipTests compile` | BUILD SUCCESS |
| `.\mvnw.cmd -o -q -pl browser4-rest,browser4-agent-tools -am -DskipTests compile` | BUILD SUCCESS |
| `.\mvnw.cmd -o -pl browser4-agentic,browser4-rest,browser4-agent-tools -am -DskipTests test-compile` | BUILD SUCCESS（仅剩无关告警：`KnowledgeFacts` 注解目标、测试里的 `!!`/cast/`instanceof` 提示） |
| `.\mvnw.cmd -o -pl browser4-agentic test -Dtest='TextToActionParsingTest,PromptBuilderBlankMessageTest,ToolCallSpecificationRendererTest,AgentStateManagerTest'` | **35 tests, 0 failures**（`TextToActionParsingTest` 4、`AgentStateManagerTest` 8、`PromptBuilderBlankMessageTest` 1、`ToolCallSpecificationRendererTest` 22） |
| `.\mvnw.cmd -o -Pall-modules -pl examples/browser4-examples -DskipTests compile` | BUILD SUCCESS（迁移后的 `TalkToLivePage.kt` 通过编译） |
| `cd cli/browser4-cli && cargo build --bin browser4-cli` | Finished（exit 0） |
| `cd cli/browser4-cli && cargo test --bin browser4-cli` | **2191 passed, 0 failed, 4 ignored**（8.14s） |
| `cargo run -- act "click the login button"` | exit **2**，输出：`The 'act' command was removed. Use the CLI tool loop instead: / browser4-cli agent run "click the login button" --wait`（未启动后端，命令校验先于服务启动） |

`TextToActionParsingTest` 仍会打印一条既有告警 `'modelResponseToActionDescription' is deprecated`——该注解上一轮就有，本轮未改语义。

### 4.1 端到端验证（重建源码包 + 真浏览器，2026-11-12 22:10–22:20）

环境：`cargo run -- open https://example.com --headless` 触发 `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1`，从本工作区源码构建 `4.16.7-SNAPSHOT` bundle，server ready 9.2s，监听 `127.0.0.1:8282`；LLM 已配置（`DEEPSEEK_API_KEY`，model `deepseek-v4-flash`）。

| 检查 | 结果 |
|---|---|
| `browser4-cli status` | `CLI version: 4.16.7`，`Bundled backend version: 4.16.7-SNAPSHOT`（本地 dev bundle，非 `v4.16.6` 安装包） |
| `browser4-cli act "click the login button"` | **exit 2** + `The 'act' command was removed … browser4-cli agent run "click the login button" --wait` |
| `browser4-cli help` 中 `act` 条目数 | **0** |
| `POST /api/act` | **404** |
| `browser4-cli tool list --filter act` | 313 个工具中匹配 4 个，全部无关（`media_extract_audio`/`agent_extract`/`coding_impact`/`skill_activate`），无 `act` 工具 |
| `eval` / `click` / `goto` / `snapshot --stdout` / `batch` | 全部 exit 0（click 后 URL 变为 `iana.org/help/example-domains`，与页面一致） |
| `agent run "click the first link"`（替代引擎） | task done，23.5s，点击 e43 并导航，summary 与页面一致 |
| `agent run "open https://example.com and click the \"Learn more\" link, then report the page title" --wait` | completed 12.2s，5 steps / 6 tools / 85.5k tokens，title = "Example Domains" |
| Rust e2e 子集 `--group open --group scrape --group status --group list` | 选中 27，`--level=BASIC` 排除 6，**21 passed / 0 failed**，75.7s |

两条**观察到的既有缺陷**（与本次改动无关；**已于同日修复**，见 `20261112-rest-json-binding-and-loadservice-cleanup.md`）：

1. `POST /api/conversations/about`（`PromptRequest` 唯一的 HTTP 入口）立即 500：`tools.jackson.databind.exc.InvalidDefinitionException: Cannot construct instance of PromptRequest (no Creators…)`——Jackson 3 转换器没有注册 Kotlin module（根因：Jackson 3 由 `spring-boot-starter-websocket` 带进 classpath，而 poms 里的 starter-jackson 排除没有覆盖这条路径，且缺 `tools.jackson.module:jackson-module-kotlin`）。修复后该入口 200，`actions` 回调可端到端执行。
2. `LoadService.loadDocument(CommandRequest, eventHandlers)` 无生产调用方（仅测试）；`CommandRequest` 只是 `PageVisitRequest` 的 typealias，真正的实现在 `ScrapeService.loadDocument`（`StatefulPageVisitor.kt:184`）。该重复重载已删除；`onPageReadyActions` 现在只经 `POST /api/commands/json` → `StatefulPageVisitor` → `ScrapeService` 生效。

> 工作区里另有一处**不属于本轮**的改动：`browser4-agentic/.../prompts/MainSystemPrompt.kt` 增加了若干 `@Deprecated("Not used anymore")`（21:51 修改，非本次编辑）。**已另行处理**：复核确认整条 system prompt v1 链无生产消费方，连同测试整体删除（`20261112-legacy-system-prompt-removal.md`）。

## 5. 未纳入本轮（留给下一次清理）

审查文档里这些项不属于"下线 act"，本轮刻意不动：

1. `ContextToAction.generate(messages, context)` 没有生产调用方（`RobustBrowserAgent` 只用它的 `chatModel` / `requestTokenLimiter`，`InferenceEngine` 只用 `generateResponseRaw`）。因此 `modelResponseToActionDescription` 目前实质是"仅测试可达"——删它要连 `TextToAction` 与解析测试一起处理。
2. `TestChatModelFactory` 仍在生产路径上（`ContextToAction.chatModel` 先查测试工厂，只看进程环境变量）。
3. `ToolCallResult.isSuccess` 语义取反（`Models.kt`，目前无生产调用方）。
4. `SessionActExecutor` 删除后，`LoadService` / `ScrapeService` 里的页面动作是**每条一次 tool loop**，成本高于原来的"一次翻译"；如果这些回调在实践中变成热点，应改成一次 run 处理整个 action 列表（而不是 `forEach` 逐条 run）。

## 6. 发布说明

破坏性变更（4.16）：`AgenticSession.act(...)`、`TextToAction.generateActions(...)`、`POST /api/act`、CLI `act` 命令全部移除。迁移：

```
browser4-cli act "click the login button"
  →  browser4-cli agent run "click the login button" --wait

POST /api/act {"description": "..."}
  →  POST /mcp/call  {tool: "command_run", arguments: {command: "..."}}

session.act("...")                    (Kotlin API)
  →  session.companionAgent.run("...") (返回 AgentHistory)
```

`scrape` / `load` 请求里的 `onPageReadyActions` / `onBrowserLaunchedActions` 字段不变，语义从"一次性翻译"变为"每条动作一次 tool loop run"。
