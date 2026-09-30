# LangChain4j 可处理环节评估

> 版本：1.1（复查修正版） · 日期：2026-08-19 · 配套文档：`browser4-code-tool-improvements.md`（缺陷清单与路线图）
> 目的：评估 `browser4-code-tool-improvements.md` 中列出的问题，哪些环节适合用 LangChain4j 处理、哪些应保持自研/纯逻辑，并给出优先级。
> 术语：下文"langchain4j"均指项目已引入的 **LangChain4j**（Kotlin/Java 生态），仓库内已有接线（见 §1）。

---

## 1. 现状：仓库已有 langchain4j 接线（评估前提）

| 组件 | 位置 | 作用 |
|---|---|---|
| `ToolSpecificationConverter` | `browser4-agentic/.../tools/langchain4j/` | Browser4 `ToolSpec` → langchain4j `ToolSpecification`（JSON Schema） |
| `ToolExecutionCoordinator` / `ToolCallConverter` / `LangChain4jToolAdapter` | 同上 | langchain4j `ToolExecutionRequest` → Browser4 `ToolCall` 执行回路由 |
| `AgentToolCallLoop` | `.../inference/chat/AgentToolCallLoop.kt` | 基于 langchain4j 原生 function-calling 协议的多轮工具循环 |
| `ChatMessages` | `.../inference/ChatMessages.kt` | 消息 ↔ langchain4j `ChatMessage` 层级映射 |
| `RequestTokenLimiter` | `.../inference/RequestTokenLimiter.kt` | 基于 langchain4j 消息的 token 预算守卫/截断 |
| `ContextToAction.generateResponseRawWithLangChain4j` | `.../inference/action/ContextToAction.kt` | langchain4j 请求通道（含 vision `ImageContent`） |
| `ToolExposeMode` | `.../inference/ToolExposeMode.kt` | TEXT（默认）/ CHAT / TOOL_CALLING 三档，配置键 `agent.tool.expose.mode` |

**关键事实（2026-08-19 复查修正）**：生产默认 `TEXT`——工具以 Kotlin 签名文本写进 system prompt，模型返回自研 JSON（`{"elements":[...]}`），再手工解析。langchain4j 原生工具调用路径（TOOL_CALLING）**已接线但未启用**。

> ⚠️ **复查修正（重要）**：初版评估曾建议"切 TOOL_CALLING 即可解决 noop 误杀"，**该结论不成立**，证据：
> 1. **noop 根因在外环、与模式无关**：`RobustBrowserAgent.step()`（`RobustBrowserAgent.kt:386-399`）对 `!actResult.isSuccess` 计 noop，判定用 `ToolSpecification.isBrowserInteraction(lastDomain)`；该函数对 **null 域返回 true**（`ToolSpecification.kt:89-93`，"safety default"）。LLM 返回纯文本（无 JSON）→ `BasicBrowserAgent.act` 报 "No tool call to act"（`BasicBrowserAgent.kt:207`）→ 失败且 `lastToolCall=null` → 计 noop。实测第 7 轮 step2/step7 的 noop 正是该路径（无 tool.exec、`toolCall=null`）。**TEXT 与 TOOL_CALLING 模式都走这个外环**。
> 2. **TOOL_CALLING 是半接线路径**：`AgentToolCallLoop` 原生执行工具后，最终文本仍按旧 JSON `{"elements":...}` 解析（`AgentToolCallLoop.kt:45-47` "downstream parsing is unchanged"）→ 纯文本收尾仍进 noop；且若模型既发原生工具调用又输出 elements JSON，外环会**二次执行**工具（如 scaffold 两遍）。
> 3. **`ToolExposeMode.includeToolListInPrompt` 从未接入**：`PromptBuilder.buildOperatorSystemPrompt()`（`PromptBuilder.kt:305-309`）恒为 `includeToolList=true`，提示词输出格式约定不随模式变化。
> 4. **TOOL_CALLING 无测试覆盖**：全仓库无引用 `TOOL_CALLING`/`AgentToolCallLoop` 的测试文件。
>
> 结论：**noop 误杀的修复是确定性逻辑修复（外环语义），不属于 langchain4j 范畴**；TOOL_CALLING 模式要成为候选，必须先修外环 noop 语义 + 接上 `includeToolListInPrompt` + 解决二次执行 + 补测试，作为"第 2 周"任务而非"立即切换"。

---

## 2. 建议用 langchain4j 处理的环节（按优先级）

### 2.1 【前置、非 langchain4j】修复外环 noop 语义（对应改进文档 2.2）

- **问题**：LLM 纯文本响应（无 JSON 工具调用）→ `act` 失败 "No tool call to act" → `isBrowserInteraction(null)=true` → 计 noop → 5 连中止。编码任务中这属于**正常中间态**，却被判为卡死。
- **修复（确定性逻辑，`RobustBrowserAgent.step` + `ToolSpecification.isBrowserInteraction`）**：
  1. `isBrowserInteraction(null)` 改为 false，或仅在 `lastToolCall != null` 时才做浏览器判定；
  2. 无工具调用的失败 act 不计 noop（"模型在思考"不是"卡死"）；只有"发起了浏览器动作且失败/页面无变化"才累计；
  3. noop 中止前检查最近一次模型评估：`taskComplete=false` 时应置任务状态 `failed`（带 failureReason），而非 `completed`；
  4. 长工具（mvn 等）执行期间暂停 noop 计数。
- **成本**：低（纯代码 + 单测）；**这是修复 noop 的唯一正确路径，langchain4j 无法替代**。
- **✅ 已实施（2026-08-19）**：①`isBrowserInteraction(null/blank)` → false（`ToolSpecification.kt:89-95`）；②`RobustBrowserAgent.step()` 仅当 `lastToolCall != null` 且为浏览器域时才计 noop（纯文本/长工具/非浏览器失败不计）；③`StepProcessingResult.stopReason`（COMPLETED/NOOP_LIMIT/MAX_STEPS）+ `buildFinalActResult` 对非正常中止标记 `failed`；④截图判定保留首步（`step <= 1`）。配套 `ToolSpecificationTest` 翻转 null/blank 断言；`agent run` 冒烟任务 2 步完成、状态 OK、日志零 noop。遗留：noop 上限任务化、FINISH 提示词、页面状态 noop 计数回传（`prepareStep` 死代码）。

### 2.2 【第 1 周】FallbackExecutor 处理 image → text 降级（对应 2.3）

- **问题**：DeepSeek 拒绝 `image_url`（`unknown variant 'image_url'`），当前在 `CachedBrowserChatModel` 空转重试 3 次；`ContextToAction.isImageNotSupportedError` 的降级逻辑位置太晚。
- **langchain4j 能力**：`RetryingExecutor` / `FallbackExecutor`（langchain4j-core）——把"带图请求失败 → 判定为 image-not-supported → 去图重发"封装为 fallback 链，错误判定收敛到一处。
- **成本**：低；替换现有手写重试即可。

### 2.3 【第 1 周】TokenWindowChatMemory / 结果摘要处理上下文膨胀（对应 2.4）

- **问题**：长任务每步携带全量历史，模型反复重读文件、乱调 skill、text-only 响应增多。
- **langchain4j 能力**：`TokenWindowChatMemory`（按 token 预算保留最近窗口）、`TextSplitter`（工具结果分块）、配合 `RequestTokenLimiter` 现有守卫；进阶用 `AiServices` 的 memory 集成。
- **落地方式**：把大工具结果（read 全文、mvn 输出）在入史前做滚动摘要/截断（`TextSplitter` 或 LLM 摘要），窗口按 token 收缩。
- **成本**：低-中；注意摘要本身耗 token，先做"截断+窗口"，再评估 LLM 摘要。

### 2.4 【第 2 周】AiServices 构造编码专用 agent（对应 2.1 无编码模式）

- **问题**：`agent run` 走浏览器导向的 PerceptiveAgent，纯编码任务仍做页面观察，DOM 超时直接取消 run。
- **langchain4j 能力**：`AiServices.builder(CodingAgent::class.java).chatLanguageModel(...).tools(编码工具).chatMemory(...)` 即可得到一个**纯工具型 agent**——无浏览器、无 observe 阶段；coding 工具（fs/shell/mvn/validate）可作为 `@Tool` 方法暴露，或复用现有 `ToolExecutionCoordinator` 回路由。
- **落地方式**：`StatefulAgentRunner` 增加 coding 分支：任务判定为编码类（无 URL/页面意图）→ 走 AiServices 循环，状态/历史仍复用 `AgentTaskStatus` 持久化。
- **成本**：中-高（新 agent 壳 + 提示词 + 测试）；收益最大（彻底消除浏览器观察拖累 + noop 问题一并消失）。

### 2.5 【第 2 周起】RAG 注入仓库 API 知识（对应探索期 grep/read 循环）

- **问题**：b4 花 10-30 步 grep/read 找 `BrowseEventMount` 签名、`ToolMount` 接口等；上下文越大越容易循环。
- **langchain4j 能力**：`EmbeddingModel` + `EmbeddingStore` + `ContentRetriever`，把仓库关键 API 文件（skeleton 的 MountPoints/PageEvents/EventHandlers、agentic 的 AbstractToolExecutor/ToolMount 等）建索引，agent 启动时自动检索相关片段注入上下文。
- **注意**：DeepSeek API **无 embedding 端点**，需要第二 provider（OpenAI/本地 embedding 模型如 bge-m3）或离线索引；也可先做轻量替代（把 API 手册预置进 system prompt / 工具描述增强）。
- **成本**：中；有 provider 前置依赖。**建议先做 2.4，RAG 作为后续增强**——编码 agent 的上下文本身更可控后，探索需求会大幅下降。

### 2.6 【持续】结构化输出用于计划与校验（对应 3.1 DevTaskPlanner、3.3 validate 语义、4.3 自毁序列）

- **问题**：DevTaskPlanner 正则启发式（`.json` 截成 `.js`、新模块无法推断）；validate 只查结构不查语义（JS 桩代码一路绿灯）；代理"先写后删"自毁。
- **langchain4j 能力**：`StrictJsonSchemaResponseFormat` / `BeanOutputParser` 结构化输出：
  - DevTaskPlanner：LLM 把任务文本抽取为 `DevPlan`（modules/files/testClasses/steps，schema 校验），替代/兜底正则（hybrid：正则命中率高时零成本，低置信时走 LLM）；
  - validate 语义层：LLM-as-judge 检查 JS 返回字段与工具描述一致（可选 profile，默认关）；
  - 文件操作计划：LLM 生成结构化 op 序列后，用确定性规则校验"先建后删"顺序，拦截 4.3 类自毁。
- **成本**：中；每处都增加 LLM 延迟与 token 消耗——**必须做成 opt-in/hybrid**，基础校验保持零成本静态检查。

---

## 3. 不建议用 langchain4j 的环节（保持自研/纯逻辑）

| 环节 | 原因 |
|---|---|
| **P0 文件工具**：glob 基座算法、`**/*` 根级匹配、listDir 截断（改进文档 §1.1-1.3） | 纯文件系统逻辑，与 LLM 无关；确定性 bug 用确定性修复 + 单测 |
| **ModuleMap 同步与校验**（§3.2） | 确定性图一致性检查；扩展 `RepoConsistencyCheck` 即可，无需 LLM |
| **CLI `--wait`、changeSummary 杂音**（§4.1-4.2） | CLI/追踪层逻辑 |
| **validate 结构检查**（pom/manifest/imports/ToolMount 存在性） | 静态校验零成本，保持现状；语义层才考虑 LLM（§2.6） |

---

## 4. 优先级与路线建议

| 顺序 | 动作 | 对应问题 | 成本 | 预期收益 |
|---|---|---|---|---|
| 1 | **外环 noop 语义修复**（`isBrowserInteraction(null)`、无工具失败不计 noop、完成态标记 failed、长工具暂停计数）——纯代码，**非 langchain4j** | 2.2 noop | 低 | 直接消除 noop 误杀（本轮 9 次执行中 4 次的死因） |
| 2 | P0 三处文件工具修复（**纯代码，与 langchain4j 无关**） | §1.1-1.3 | 低 | 消除探索死循环根因 |
| 3 | `FallbackExecutor` 统一 image 降级 | 2.3 | 低 | 消除 3 次空转重试 |
| 4 | Token 窗口 + 工具结果截断/摘要 | 2.4 | 低-中 | 长任务稳定性 |
| 5 | `AiServices` 编码 agent（coding 分支，**替换外环**） | 2.1 | 中-高 | noop 机制随外环一起消失；彻底摆脱浏览器观察拖累 |
| 6 | TOOL_CALLING 模式补全（仅当需要统一双路径时）：接 `includeToolListInPrompt`、解决原生调用与外环 elements 二次执行、补测试、A/B 回归 | 2.2（备选） | 中 | 收敛到单一路径；**不是 noop 的修复手段** |
| 7 | 结构化输出：devTask 抽取 / 文件操作计划校验 | 3.1/4.3 | 中 | 计划质量与防自毁 |
| 8 | RAG API 知识注入 | 探索循环 | 中（需 embedding provider） | 探索期减负；#5 落地后需求下降 |

**总体判断（复查修正）**：本次测试暴露的 P1 问题中，**noop 误杀（最大死因）的修复是外环确定性逻辑，不依赖 langchain4j**；langchain4j 的价值集中在：image 降级（FallbackExecutor）、上下文管理（TokenWindowChatMemory）、编码专用 agent（AiServices，从结构上消除 noop 与浏览器观察）、结构化输出（devTask/计划校验）、RAG（API 知识注入）。**建议路径**：先做 #1+#2（纯代码，2 项合计约 1 天，解决 9 轮执行中 7 轮的失败根因），再做 #3-#5 的 langchain4j 项；TOOL_CALLING 路径目前是半接线状态，**不要作为修复手段**，仅在需要统一协议时按 #6 补全。

---

## 5. 风险与注意事项

1. **TOOL_CALLING 是半接线路径（复查确认）**：`AgentToolCallLoop` 无测试、`includeToolListInPrompt` 未接入提示词、原生工具调用与外环 elements 存在二次执行风险、最终文本仍按旧 JSON 解析——**不要把它当作 noop 的修复手段**；若要用，先按 §4 #6 补全并 A/B 回归。
2. **noop 修复必须落在外环**：`RobustBrowserAgent.step` 与 `ToolSpecification.isBrowserInteraction`（null 域安全默认）是唯一正确修改点；改完用本任务回归（9 轮中 4 轮死因即此）。
3. **成本上涨**：结构化输出/摘要/RAG 均增加 token 消耗；默认路径必须保持零额外 LLM 调用（静态检查优先，LLM 层 opt-in）。
4. **embedding provider 缺失**：DeepSeek 无 embedding 端点，RAG 需第二 provider 或本地模型；没有之前不要阻塞其他项。
5. **模型差异**：即便补全 TOOL_CALLING，DeepSeek 的原生工具调用质量（参数格式、多轮稳定性）仍需实测；备选方案始终是"TEXT + 外环 noop 修复"。
