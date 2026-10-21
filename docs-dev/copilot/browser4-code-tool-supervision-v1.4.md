# `code` 工具与 b4 代理改进建议 v1.4（browser4-wordcount 监督轮）

> 版本：1.4 · 日期：2026-08-20 · 作者：监督会话（browser4-wordcount 插件任务端到端回归）
> 前情：v1.3 修复已落地（HEAD `376071f460`，19:10）。本轮先撞上 6 小时陈旧的运行 bundle（13:23 构建），随后经用户确认执行了「停后端 → 重建 bundle（含 agentic 补重建）→ 重启」并用 HEAD 完成全链路验证。
> 结论先行：**插件任务最终 100% 完成**（scaffold → 实现 → 编译 → 5/5 测试 → 双重校验 → 打包 → REST 部署 → 重启加载 → 工具直连调用返回正确结果）。但过程中新发现 **2 个 P0 代理缺陷（TEXT 模式结果不回灌、跨任务历史污染导致假完成）**，以及若干 P1/P2。

---

## 任务与最终产物

- 任务：创建 `browser4-plugins/browser4-wordcount` 插件，实现 LLM 工具 `wordcount.getWordCount(text: String) → WordCountResult(words/chars/charsNoSpaces/lines)`，纯文本统计，`wordcount.enabled` 配置项，单元测试，四大验收门禁。
- b4 完成物：脚手架（10 文件 + 聚合 pom 注册 + ModuleMap MODULES）→ 7 个实现文件（Config/Service/ToolExecutor/AutoConfiguration/manifest/Test/README）→ ModuleMap DEPENDENTS 4 键补齐 → 全部验收通过 → JAR(19.7KB) → REST 部署 → 重启后 `loaded=True` → `wordcount.getWordCount` 直连返回 `WordCountResult(words=5, chars=28, charsNoSpaces=24, lines=1)` ✅
- git 净变更仅 3 处：`ModuleMap.kt`（+8/-3）、`browser4-plugins/pom.xml`（+1 行）、新插件目录。治理文件零触碰 ✅

---

## P0 — 代理执行回路缺陷（本轮新发现，HEAD 上复现）

### 0.1 TEXT 模式（默认）下工具结果完全不回灌模型 → 空转循环/挂起

- **证据**：`ToolExposeMode` 默认 **TEXT**（`ToolExposeMode.kt` 注释自认 "today's behaviour and the default"；`agent.tool.expose.mode` 未配置）。TEXT 路径 `generateResponseRawLegacy` 无 `AgentToolCallLoop`；`AgentState.toolCallResult` 标注 `@JsonIgnore`（`AgentState.kt:62`），序列化进提示词的 history 里每步只有 `description: "✅ tool.done | ... | null/null"`。旧后端实测：21 步 / 10 分钟 / 仅 6 次工具调用（devTask、scaffoldToDir、listDir×3、workspaceRoot），模型反复声明 "I don't have its output"，零实现写入。HEAD 上第三轮复现同款症状（模型看不到 mvnBuild 结果，只能盲跑）。
- **影响**：默认配置下多步编码任务不可行；`--verify` 类"看结果再行动"的模式失效；模型被迫靠猜（猜错即幻觉 API，见 2.4）。
- **修复建议**（按性价比排序）：
  1. **默认切到 `toolCalling`**：`ToolExposeMode.from` 默认返回 `TOOL_CALLING`（deepseek 系列支持 OpenAI 风格函数调用，本轮实测可用）。或至少在 `application.properties` 落 `agent.tool.expose.mode=toolCalling`。
  2. TEXT 模式兜底：工具执行后把结果（截断到 N 字符）追加为 user 消息，或在 history 渲染中带 `resultPreview`。
  3. 纯文本空转保护：`d284c60353` 把 text-only 响应移出了 noop 计数，导致空转永远不中止——建议单独设 text-only 连续计数器（如 5 次即 abort）。

### 0.2 跨任务历史污染 → 假完成（编造叙述，零工具调用）

- **证据**：`command_run` 各任务共用 DEFAULT_SESSION 的 `stateHistory`。第四轮修正任务实测：**35 秒、1 步、0 次工具调用**，模型却输出 taskComplete:true 并"复述"了上一轮的全部步骤（连上一轮 D 失败的理由"manifest 缺 sdkVersion"都原样照抄），磁盘上文件一字未动。源码已有 `snapshotFor(_lastRunSessionId)`（`BasicBrowserAgent.kt:126`）但 command 路径未生效；旧后端 status 中 `agentHistory.states` 混有 3 个不同 sessionId 的任务状态。
- **影响**：同一会话连续下发任务时，后一个任务极易假完成；v1.3 报告的"假完成变种"在 HEAD 上仍存在（本次是历史污染触发的新变种）。
- **修复建议**：① 任务级 history 切片：`command_run`/`StatefulAgentRunner` 每次任务用新 sessionId 并渲染 `snapshotFor(当前sessionId)`；② FINISH 校验：完成声明时比对"本任务实际执行的工具调用数"，0 调用即拒绝 completed；③ 新任务开局的 history 清空或仅保留跨任务摘要。

---

## P1 — 开发流与工具链

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| 1.1 | `code devtask --verify` 编译目标与计划不一致 | 计划第 4 步目标为 `browser4-plugins/browser4-wordcount`（modules+newPluginModules 取最深），verify 分支只用 `plan.modules` → 实际编译了聚合器 `browser4-plugins`（4 秒假阳性） | verify 与计划共用同一模块集合；对尚未存在的模块给出"跳过编译，先 scaffold"的明确提示 |
| 1.2 | `code devtask` 计划 impact 步骤用裸文件名 | 任务文本含裸 `pom.xml` 时，计划第 3 步为 `coding.impact(path="pom.xml")`（应为新模块目录） | impact 目标优先取最深模块路径，裸文件名仅作信号不作路径 |
| 1.3 | `scaffoldToDir` 只同步 MODULES，DEPENDENTS 反向边留给 agent | 新后端 `validate repo-consistency` 立刻报 4 个 DEPENDENTS 漂移；agent 回路坏掉时无人补 | scaffoldToDir 根据插件 pom 依赖机械补齐 DEPENDENTS（skeleton/protocol/agentic/pdk），无需 agent 参与 |
| 1.4 | Maven 4 smart defaults 静默跳过 jar 重打包 → bundle 重建产出陈旧 jar | `mvn install` 后 m2 的 agentic jar 时间戳/内容仍为 13:23（`Nothing to compile` 但源码已改）；`clean package` 才产出新 jar（`requestTimeoutMs` 字符串从缺到有） | bundle 构建脚本对关键模块（agentic/coding/rest）强制 `clean package`，或关闭 smart-defaults 缓存；重建后做内容级校验（含 v1.4 新符号） |
| 1.5 | toolCalling 模式下单轮 5 次工具上限 + 历史渲染预算塌缩 → 重复编译循环 | 修正轮实测：每轮 90k 输入 token（Maven 输出回灌把上下文撑爆）、history 渲染成 `{"step":1}...{"step":4}` 空对象、连续 4 次重跑 mvnBuild（编译其实早已成功） | ① maxIterations 提升或按任务类型自适应；② history 预算保留"步骤→工具名→结果前 200 字"而非整步丢弃；③ mvnBuild/shell 结果截断（如仅保留 exit code + 诊断摘要） |

---

## P2 — 小问题

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 2.1 | scaffold 默认包名 `ai.platon.pulsar.my.*`（plan 的 scaffoldToDir 未传 basePackage） | `DevTaskPlanner` scaffold 步骤 / `ArtifactScaffolds` 默认值 | 从插件名推断 basePackage（`ai.platon.pulsar.wordcount`） |
| 2.2 | `code mvn` 编译失败时 CLI exit 0（诊断只进 stdout） | CLI code-mvn 路径 | 编译失败返回非零码（或 `--fail-on-error` 标志），便于脚本/监督方判断 |
| 2.3 | b4w.ps1 后端陈旧警告只提示不阻止，且手动重建后 hash 缓存不更新导致持续误报 | b4w.ps1 后端 hash 检测 | ① 提供严格模式（`BROWSER4_STRICT=1` 时陈旧即拒绝执行）；② 启动时按内容校验 hash 而非仅查缓存文件 |
| 2.4 | 模型幻觉 `ai.platon.pulsar.pdk.*` API（ToolExecutor/AutoConfiguration 两个文件不可编译） | 第三轮产物，24 条编译诊断 | 把真实插件 API 样板（`agentic.tools.builtin.AbstractToolExecutor`+`ToolSpec`、`agentic.tools.ToolMount` 接口）注入 coding 系统提示词或 devtask 计划的 scaffold 步骤 |
| 2.5 | 后端工作目录随启动方式漂移：从仓库根启动 `start-runtime.ps1` 后 `plugins/`、`logs/` 落在仓库根，与 bundle 内目录不一致（REST 安装路径也因此不同） | 本轮实测 | start-runtime.ps1 固定 `Set-Location` 到 bundle 根再启动，或显式 `-D` 指定 plugins/logs 目录 |
| 2.6 | coding 任务每步仍付 5s DOM settle 超时（about:blank 页面等待） | 旧后端日志 `PageStateTracker - DOM settle timeout after 5000ms` 每步必现 | CodingTaskDetector 命中后跳过页面状态跟踪，不再等待 DOM 稳定 |
| 2.7 | deepseek 长输出（一次生成多文件内容）挂起 | 旧后端挂 5min 无响应；新后端靠 v1.4 的 5min 请求超时重试救回 | 提示词引导"一次只写一个文件"；输出长度阈值预警 |
| 2.8 | agent 手补 ModuleMap DEPENDENTS 时缩进漂移（line 82 等） | ModuleMap.kt 现状 | 与 scaffoldToDir 的 pom 缩进修复同思路：按相邻行缩进对齐（或见 1.3 由工具代劳） |
| 2.9 | `agent status` 返回的 `agentHistory` 为全量 JSON（含跨任务状态），CLI 整包打印，且 `agentState` 在新任务首步会显示旧任务指令 | CLI agent-status / 后端状态序列化 | 状态端点只返回当前任务切片 + 精简字段；CLI 加 `--json` 时才输出全量 |

---

## 回归基线（本轮验证通过，HEAD）

- `code workspace/list/glob/read/scaffold(print)/validate plugin/repo-consistency/mvn/shell/delete/replace` 全链路 ✅（结构化编译诊断、磁盘快照一致性校验尤其好用）
- `code devtask` 计划：0.8s 出 6 步计划，新插件识别、模块推断、无 commit 步骤 ✅；`--verify` 4s（热构建）✅
- `agent run/cancel/status/result` 全链路 ✅；cancel 秒级生效（statusCode 417）
- v1.4 修复实测有效：5min LLM 请求超时（挂起被重试救回）、repo-consistency 磁盘快照（立刻抓出 DEPENDENTS 漂移）、点号工具分发（`wordcount.getWordCount` 直连成功）、validate plugin 残影警告、agentic 补重建后 bundle 与 HEAD 一致 ✅
- 插件验收：编译 ✅、5/5 测试 ✅、validate plugin 全过 ✅、repo-consistency 全过 ✅、打包 ✅、REST 部署 ✅、重启加载 `loaded=True` ✅、工具直连返回正确 ✅

---

## 环境备忘（本轮结束后）

- 后端现运行于 **toolCalling 模式**（`JAVA_TOOL_OPTIONS=-Dagent.tool.expose.mode=toolCalling`，由监督会话的启动进程持有）。如要恢复默认 TEXT 模式：停后端后不带该环境变量重启。建议把 `agent.tool.expose.mode=toolCalling` 落进 `application.properties`（见 0.1 建议 1）。
- 新插件已通过 REST 安装到 `D:\workspace\Browser4\Browser4-4.14\plugins\`（当前后端工作目录为仓库根；见 2.5）。
- `b4w.ps1` 仍会提示后端陈旧（手动重建未更新其 hash 缓存），属误报，下次 `-Rebuild` 后消失。
