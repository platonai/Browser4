# 清理遗留 system prompt 子系统（2026-11-12）

> 起因：工作区里出现一批 `@Deprecated("Not used anymore")`（21 处），其中 11 处在 `MainSystemPrompt.kt`、10 处在 `PromptBuilder.kt`。本记录复核每个符号的消费方，并删除确认无人使用的方法。
> 范围：全部改动留在工作区，未 commit。

## 0. 结论

21 处标记全部复核完毕：**没有任何生产消费方**——它们要么互相调用，要么只被 3 个测试类覆盖。整条"system prompt v1"链随之删除（`MainSystemPrompt.kt` 整文件 + `PromptBuilder` 的 companion object 与 2 个实例方法 + 3 个测试类）。

生产 agent 的 system prompt 与这套代码无关：`RobustBrowserAgent` 用的是 `cliAgentSystemPrompt()`（CLI tool loop），`PromptBuilder` 的生产用途只剩 `buildSummaryPrompt`（`RobustBrowserAgent:2699`）、`initExtractUserInstruction`（`BasicBrowserAgent:155`）与 `InferencePromptBuilder` 的 extract / evaluation 提示词。

## 1. 消费方矩阵（删除前）

| 符号（文件） | 生产消费方 | 仅测试消费方 | 处置 |
|---|---|---|---|
| `CODING_NO_PAGE_FIELDS_NOTE`、`FILE_HANDLING_BROWSER|CODING`、`REASONING_PATTERN_BROWSER|CODING`（`MainSystemPrompt.kt`，private） | 无（只被 `buildMainSystemPromptV1` 拼接） | — | 删 |
| `SKILL_TOOL_TYPE_DEFINITIONS`、`buildSkillSummariesSection()` | 无（只被 `buildToolUseSections` 拼接） | — | 删 |
| `buildToolSpecContent(...)` | 无（只被 `buildToolUseSections` 调用） | — | 删 |
| `buildToolUseSections(...)` | 无（只被 `buildMainSystemPromptV1` 调用） | — | 删 |
| `buildMainSystemPromptV1()`（无参） | 无（只自调用有参重载） | — | 删 |
| `buildMainSystemPromptV1(toolFormat, …)` | 无（唯一调用方 `PromptBuilder.buildOperatorSystemPrompt` 自己也没人用） | `ToolFormatComparisonTest`、`MainSystemPromptCustomSkillInjectionTest` | 删（连同测试） |
| `PromptBuilder.workingLanguage`、`buildResponseSchema()` | 无（只被 `buildMainSystemPromptV1` 使用） | — | 删 |
| `PromptBuilder.buildObserveResultSchema(...)` | 无（只被 `buildResponseSchema` 使用） | — | 删 |
| `PromptBuilder.TOOL_CALL_RULE_CONTENT_V1` | **无任何消费方**（连 V2 都不用 V1） | — | 删 |
| `PromptBuilder.TOOL_CALL_RULE_CONTENT_V2` / `TOOL_CALL_RULE_CONTENT` / `EXTRACTION_TOOL_NOTE_CONTENT` | 无（只被 `buildToolUseSections` 使用） | — | 删 |
| `PromptBuilder.compactPromptForHuman(...)` | 无 | `MainSystemPromptCustomSkillInjectionTest` | 删（连同测试） |
| `PromptBuilder.buildOperatorSystemPrompt(...)` | 无（只被 `buildMultistepMessageListStart` 调用） | 间接 | 删 |
| `PromptBuilder.buildMultistepMessageListStart(...)` | 无 | `PromptBuilderBlankMessageTest` | 删（连同测试） |

> 生产可达性验证：`grep` 全仓库后，`buildMultistepMessageListStart` 只有 `PromptBuilderBlankMessageTest` 一个调用方；`buildOperatorSystemPrompt` 只有它调用；其余符号只在这条死链内部互相引用，或只被上述测试引用。

## 2. 删除清单

| 位置 | 处置 |
|---|---|
| `browser4-agentic/.../agentic/prompts/MainSystemPrompt.kt` | **删除整个文件**（298 行、11 个被标记成员）；`agentic.prompts` 包随之清空 |
| `browser4-agentic/.../inference/PromptBuilder.kt` | 删除 `companion object`（8 个成员，含 `TOOL_CALL_RULE_CONTENT_V1/V2`、`EXTRACTION_TOOL_NOTE_CONTENT`、`compactPromptForHuman` 等）与 `buildOperatorSystemPrompt` / `buildMultistepMessageListStart`；追加轮再删三个孤儿方法（见 §3）。最终 555 → 256 行，无用的 import（`OBSERVE_RESPONSE_ELEMENT_SCHEMA`、`buildMainSystemPromptV1`、`ToolSpecFormat`、`KStrings`、`ExecutionContext`、`ToolOutcome`、`Strings`）全部清掉 |
| `browser4-agentic/src/test/.../prompts/ToolFormatComparisonTest.kt` | 删除（只测 `buildMainSystemPromptV1` 的两种工具格式） |
| `browser4-agentic/src/test/.../prompts/MainSystemPromptCustomSkillInjectionTest.kt` | 删除（只测 `buildMainSystemPromptV1` + `compactPromptForHuman`） |
| `browser4-agentic/src/test/.../inference/PromptBuilderBlankMessageTest.kt` | 删除（只测 `buildMultistepMessageListStart`） |

## 3. 保留与后续（追加轮已处理）

- **保留**：`PromptBuilder.buildAgentStateHistoryMessage(...)` —— 生产 `buildSummaryPrompt` 仍在使用（`PromptBuilderHistoryStrategyTest` 的前两个用例继续有效）。它此前被误标 `@Deprecated("Not used anymore")`，已移除该注解。
- **连带孤儿（追加轮已删）**：删除 `buildMultistepMessageListStart` 后，以下三个 public 方法在主源码里已无调用方，经确认后一并删除：
  - `buildUserRequestMessage(...)`
  - `buildBrowserVisionInfo()`
  - `buildPrevToolCallResultMessage(...)`（其唯一的覆盖用例 `prevToolCallResultRendersModelErrorWithoutToolResult` 随方法一起删除）

  `PromptBuilder.kt` 最终 **555 → 256 行**；同时删掉随之无用的 `ExecutionContext` / `ToolOutcome` / `Strings` import。
- **P0.1 空白消息回归测试随代码一起删除**：那个崩溃（空历史 → 空 user message → LangChain4j `text cannot be null or blank`）发生在已下线的 observe-act 路径；当前 tool loop 用 `cliAgentSystemPrompt()` + `UserMessage.from(...)` 组装消息。若担心同类问题，应针对 live 路径补一条等价 guard，而不是保留一个已经没有调用方的构造器测试。

## 4. 验证

| 检查 | 结果 |
|---|---|
| `mvn -o -pl browser4-agentic -DskipTests compile` | BUILD SUCCESS |
| `mvn -o -pl browser4-agentic -DskipTests test-compile` | BUILD SUCCESS（告警里不再出现 `buildMainSystemPromptV1 is deprecated`） |
| `mvn -o -pl browser4-agentic test -Dtest=PromptBuilderHistoryStrategyTest,TextToActionParsingTest,AgentStateManagerTest` | **15 tests / 0 failures** |
| 追加轮：`mvn -o -pl browser4-agentic test -Dtest=PromptBuilderHistoryStrategyTest,TextToActionParsingTest` | **6 tests / 0 failures**（HistoryStrategy 剩 2 个用例） |
| 全仓库 `grep`（.kt/.md/.json/.html） | 除 `docs-dev` 历史设计文档外无残留引用；无代码引用 |
| `@Deprecated("Not used anymore")` 剩余数量 | **0** |

## 5. 发布说明

删除遗留内部 API（无生产调用方）：

- `ai.platon.pulsar.agentic.prompts.MainSystemPrompt`（整文件：`buildMainSystemPromptV1`、`buildToolUseSections`、`buildToolSpecContent`、`buildSkillSummariesSection`、`SKILL_TOOL_TYPE_DEFINITIONS` 及私有提示词常量）
- `PromptBuilder.Companion.workingLanguage` / `buildResponseSchema` / `buildObserveResultSchema` / `TOOL_CALL_RULE_CONTENT[_V1|_V2]` / `EXTRACTION_TOOL_NOTE_CONTENT` / `compactPromptForHuman`
- `PromptBuilder.buildOperatorSystemPrompt` / `PromptBuilder.buildMultistepMessageListStart`
- `PromptBuilder.buildUserRequestMessage` / `buildBrowserVisionInfo` / `buildPrevToolCallResultMessage`（追加轮，删除后无调用方）

生产 agent 的 system prompt 不受影响（`cliAgentSystemPrompt()`）。
