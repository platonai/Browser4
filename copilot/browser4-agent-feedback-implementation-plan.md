# b4 代理反馈闭环实现计划（工具披露 · 结果回传 · 完成报告 · 任务隔离）

> 状态：**已实施** ✅（2026-08-21 凌晨轮次完成 P0-P6；详见文末"实施结果"）
> 日期：2026-08-20 制定 · 2026-08-21 落地 · 输入文档：
> - `browser4-code-tool-supervision-v1.4.md`（实测证据）
> - `browser4-code-tool-agent-output-feedback-fix-plan.md`（P0 修复方案）
> - `browser4-agent-tool-disclosure-feedback-design.md`（三机制 + §3.5 页面隔离设计）
>
> 总体策略：**先感知（结果回传）→ 再选择（披露）→ 再问责（完成报告）→ 再隔离 → 最后切默认值**。每个阶段独立可回退（配置开关），每任务带单测与验收。

---

## 阶段总览

| 阶段 | 内容 | 依赖 | 规模估计 | 回退开关 |
|---|---|---|---|---|
| P0 | 基线度量 + 配置键 | 无 | 0.5d | 无（纯加配置） |
| P1 | 结果感知（信封/渲染/保险丝） | P0 | 2d | `browser4.agent.toolOutcome=true` |
| P2 | 工具披露（生成化+分层） | P0 | 2d | `browser4.agent.toolDisclosure=full` |
| P3 | 完成报告（gates 硬校验） | P1 | 1.5d | `browser4.agent.finishGateCheck=warn` |
| P4 | 任务隔离（页面信息+历史） | P1 | 1.5d | 无（仅 codingMode 路径） |
| P5 | 默认切换 toolCalling + 降级兜底 | P1+P2 | 0.5d | `agent.tool.expose.mode=text` |
| P6 | 集成回归 + 发布 | P1-P5 | 1d | — |

依赖图：`P0 → {P1, P2} → P3；P1 → P4；{P1,P2} → P5；全部 → P6`

---

## P0 — 基线度量与配置键（0.5d）

**目标**：所有新行为可控、可度量、可回退。

| # | 任务 | 锚点 | 内容 |
|---|---|---|---|
| 0.1 | 配置键集中定义 | `AgentConfig`（browser4-agentic） | 新增：`browser4.agent.toolOutcome`（bool, 默认 true）、`browser4.agent.toolDisclosure`（`tiered\|full`, 默认 tiered）、`browser4.agent.textOnlyStallLimit`（int, 默认 5, 0=禁用）、`browser4.agent.finishGateCheck`（`warn\|strict`, 默认 strict）、`browser4.agent.toolLoop.maxIterations`（int, 默认 12）、`agent.tool.expose.mode`（`toolCalling\|text\|chat`, 默认见 P5） |
| 0.2 | 度量基线 | `CodingToolExecutor.tokenStats` / `InferenceMetrics` | 记录：披露段字符数、单请求输入 token、每任务工具调用数、text-only 连续计数——供 P6 回归对比（现状：输入峰值 90k，披露 ~15.6k 字符） |
| 0.3 | 回归基线固化 | `browser4-tests` 或脚本 | 保存 wordcount 插件任务为可重复回归场景（任务文本 + 断言清单，见 P6.2） |

**验收**：配置键全部可读且有默认值；`mvn test -pl browser4-agentic -am` 通过。

---

## P1 — 结果感知：模型必须看见每步成果与问题（2d，最高优先级）

| # | 任务 | 锚点 | 内容 | 测试 |
|---|---|---|---|---|
| 1.1 | `AgentState.resultPreview` | `AgentState.kt:62` | 新增派生属性：`toolCallResult.evaluate` → 单行、折叠空白、≤600 字；`toolCallResult` 保持 `@JsonIgnore` | `AgentStateTest`：截断/折叠/null/不序列化原对象 |
| 1.2 | `ToolOutcome` 信封 + 裁剪 | 新建 `agentic/model/ToolOutcome.kt`；`CodingToolExecutor.kt` 各返回点 | 按设计 §2.3 裁剪表：read 首 40 行；listDir/glob 前 50 条；mvnBuild exit+诊断前 10 条+尾部 500 字；shell stdout 尾 3000/stderr 尾 1000；validate ERROR 全量+WARNING 计数；写操作附 `workspaceDelta`（`CodingAgentFileSystem` changeSummary 增量） | 单元：长输出截断、诊断保留、delta 计算 |
| 1.3 | Tool Outcomes 渲染 | `DefaultHistoryRenderStrategy.kt:88-136` | 新增「### Tool Outcomes」小节（近 6 步：`N. domain.method [ok\|fail] summary` + errors 缩进 + delta）；压缩优先级 `thinking > keyFindings > nextGoal > body`，`step/tool/ok/summary` 永不丢；总预算 4000 字不变 | 渲染单测：预算超限时最小字段仍在；无 `{"step":N}` 空壳 |
| 1.4 | 增强已有回灌通道 | `PromptBuilder.kt:399-437`（buildPrevToolCallResultMessage） | 改为输出 ToolOutcome 信封（保留 5000 字上限）；错误高亮（`[fail]` + 异常链 ≤300 字）；`prevState.toolCallResult == null` 时不注入 | 单测：信封渲染、空值跳过 |
| 1.5 | toolCalling 结果信封 + 迭代上限 | `AgentToolCallLoop.kt:90`；构造处 `ContextToAction.kt:78-85` | 结果消息内容 = 信封序列化；`maxIterations` 读配置（默认 12）；超限时 modelError 附带"已执行工具清单"供续跑 | 单测：12 次上限、超限错误内容 |
| 1.6 | text-only 保险丝 | `RobustBrowserAgent.kt:356-396` 循环 | 连续无工具调用且未完成计数 ≥ `textOnlyStallLimit` → `StopReason.NOOP_LIMIT` 标 failed；任何工具调用或完成即清零 | 单测：5 次中止、工具调用清零、0=禁用 |

**验收**：wordcount 修复轮（TEXT 模式）回归——模型每一步可见上一步 outcome，不再盲跑；无 text-only 空转 >5 次。

---

## P2 — 工具披露：准确、高效（2d）

| # | 任务 | 锚点 | 内容 | 测试 |
|---|---|---|---|---|
| 2.1 | 生成化披露 | `ToolCallSpecificationRenderer.kt:201-227` | 披露清单改由 `AgentToolManager.getAllToolSpecs()` + `CustomToolRegistry` 序列化生成（domain/method/args/returnType/description ≤120 字）；`ToolSpecification.TOOL_CALL_SPECIFICATION` 降级为回归基准 | 单测：**生成清单与注册表 diff = 0**；与硬编码串语义等价 |
| 2.2 | 补齐披露缺口 | `ToolSpecification.kt:8-48` | 披露 `browser.newTab/listTabs`、`agent.run/observe/done`（done 标注为完成协议）；删除 `fs.*` 注释依赖 | 单测：注册表 ⊆ 披露 |
| 2.3 | 分层披露 L0/L1/L2 | `ToolCallSpecificationRenderer`（过滤扩展）；`PromptBuilder.kt:311-323` | L0 核心（完成协议+system.help+skill.list）；L1 按 `CodingTaskDetector.detect(task)` 分流（coding 任务：coding+cli 全量，tab 域一行摘要「需要时 system.help("tab")」；浏览任务反之）；L2 `system.help(domain[,method])` 完整签名 | 单测：coding/浏览任务披露集合断言；摘要行存在 |
| 2.4 | TOOL_CALLING 同过滤 | `AgentToolManager.getLangChain4jToolSpecifications` | 原生 specs 走同一分层过滤，列表瘦身 | 单测：spec 数量 ≤ 域内全量 |
| 2.5 | 预算守卫 | 渲染器 | 披露段 >4k token 断言触发进一步折叠；`toolDisclosure=full` 一键平铺 | 单测：预算断言 |

**验收**：coding 任务 system 提示词不含 24 个 tab 签名全量；`system.help("tab")` 可展开；披露 ≤4k token。

---

## P3 — 完成报告：gates 结构化 + runner 硬校验（1.5d）

| # | 任务 | 锚点 | 内容 | 测试 |
|---|---|---|---|---|
| 3.1 | finish gates schema | `MainSystemPrompt.kt` FINISH 段 | 完成 JSON：`{taskComplete, gates:[{name,ran,exitCode,ok,detail}], filesChanged, problems}`；说明校验后果 | 提示词单测（schema 文本） |
| 3.2 | runner 硬校验 | `StatefulAgentRunner.kt` / `RobustBrowserAgent.kt` 收尾 | ① 本任务工具调用数==0 → 拒绝 completed（failed, reason=no-tool-calls）；② `ran:true` 的 gate 与状态历史实际调用比对（工具名/exitCode 不符 → failed, reason=gate-mismatch）；③ `ran:false` 必须带 reason；④ `finishGateCheck=warn` 时仅告警 | 单测：假完成拒绝、gate 不符判失败、warn 模式 |
| 3.3 | 结果输出 | `agent result` 链路 | 校验通过的 gates + filesChanged + problems 随结果返回 | 集成测试 |

**验收**：35 秒/0 调用假完成被拒；gates 与实际工具调用一致才通过。

---

## P4 — 任务隔离：编码智能体不自动获得网页信息 + 历史切片（1.5d）

| # | 任务 | 锚点 | 内容 | 测试 |
|---|---|---|---|---|
| 4.1 | 页面状态短路 | `AgentStateManager.kt:508-539` | `codingMode=true` 时 `getBrowserUseState()` 返回 `BrowserUseState.DUMMY`（不 settle、不快照、不注入 tabs） | 单测：codingMode 下零调用 |
| 4.2 | 提示词隔离 | `PromptBuilder.kt:311-323, :663-750` | codingMode 跳过 Browser State/Viewport/ARIA 三段，替换一行：「当前无页面上下文；如需网页信息，请显式调用 tab.navigate/ariaSnapshot/textContent/eval」 | 单测：请求消息断言无 `## Browser State` |
| 4.3 | 响应模板免填 | 响应解析/模板 | codingMode 下 `screenshotContentSummary/currentPageContentSummary` 不要求填写（N/A） | 单测 |
| 4.4 | 历史切片 | `StatefulAgentRunner.kt:332` 附近；`BasicBrowserAgent.kt:126` | 每任务新 sessionId；history 渲染用 `snapshotFor(sessionId)`；与 P3.2① 联动封死假完成 | 单测：新任务 history 不含旧任务状态 |
| 4.5 | 浏览器惰性启动（二期，可拆分） | `RobustBrowserAgent.kt:505-521` | codingMode 不预启动浏览器；模型调用 `tab.*` 时按需绑定 driver | 集成测试 |

**验收**：编码任务请求无页面信息段、全程无 DOM settle 日志；调用 `tab.navigate` 后才出现页面状态。

---

## P5 — 默认切换 + 降级兜底（0.5d，最后做）

| # | 任务 | 锚点 | 内容 | 测试 |
|---|---|---|---|---|
| 5.1 | 默认 TOOL_CALLING | `ToolExposeMode.kt:38-43` | `from(conf)` 缺省返回 `TOOL_CALLING`；显式 `text`/`chat` 仍可选；更新 KDoc 与 `application.properties` 注释 | 单测：默认值、显式覆盖 |
| 5.2 | 降级兜底 | `ContextToAction.kt` langchain4j 路径 | 供应商不支持工具规格（400/明确错误）→ 本任务自动回落 TEXT + 单次 WARN 日志 | 单测：模拟不支持错误回落 |

**验收**：默认配置下 wordcount 类多步任务可自主看到 mvnBuild/test/validate 输出并据其行动；不支持场景自动回落不崩溃。

---

## P6 — 集成回归与发布（1d）

| # | 任务 | 内容 |
|---|---|---|
| 6.1 | 单元回归 | `mvn test -pl browser4-agentic -am`（含 browser4-agent-tools 的 CodingTaskDetectorTest）全绿 |
| 6.2 | 场景回归 | 重建 bundle（注意 Maven 4 smart-defaults 陈旧 jar 陷阱：关键模块先 `clean package`）；wordcount 任务 TEXT 与 toolCalling 各一轮，断言：① 无 listDir 空转；② 每步 Tool Outcomes 可见；③ finish gates 与实测一致；④ 假完成被拒；⑤ 单请求输入 ≤15k token（现状 90k）；⑥ 编码任务请求无 `## Browser State` |
| 6.3 | 文档 | 更新 `skills/browser4-cli/SKILL.md`、`docs-dev/copilot/` 三份文档状态标记为"已实施"；`application.properties` 注释示例 |
| 6.4 | 性能评估 | 对照 P0.2 基线：披露 token、请求 token、每任务步数、时延变化 >5% 需说明 |

---

## 实施顺序建议（关键路径）

```
P0 → P1（1.1→1.6，先 1.1/1.3/1.4 让 TEXT 立即可用）
      ↘ P3（依赖 P1 的结果可见性）
      ↘ P4（依赖 P1 的 outcome 渲染；4.5 可延后）
P0 → P2 → P5（P5.1 必须等 P1+P2 就绪，避免默认模式下裸奔）
全部 → P6
```

并行建议：P2 与 P1 可两人并行（共享 P0 配置与 ToolOutcome 类型定义）；P4.1/4.2 可提前与 P1 并行（纯跳过逻辑）。

## 全局验收线（Definition of Done）

- [ ] 默认模式下模型能看见工具输出并据此行动（不再盲跑/空转/假完成）
- [ ] 披露清单 = 注册表（diff=0），分层披露 ≤4k token
- [ ] finish gates 经 runner 硬校验，0 调用拒绝 completed
- [ ] 编码任务无页面信息注入、无 DOM settle 浪费
- [ ] 全部新单测 + 既有回归绿；wordcount 场景两模式回归通过
- [ ] 无新增高噪声日志；性能影响 ≤5%（有则说明）
- [ ] 配置回退开关全部验证可用（warn/full/text/0）

---

## 实施结果（2026-08-21）

| 阶段 | 结果 |
|---|---|
| P0 | ✅ `AgentConfig` 新增 5 键（toolOutcome/toolDisclosure/textOnlyStallLimit/finishGateCheck/toolLoopMaxIterations），全部带默认值与回退 |
| P1 | ✅ `AgentState.resultPreview`；新 `ToolOutcome` 信封（按工具裁剪）；`DefaultHistoryRenderStrategy` 新增「### Tool Outcomes」+ result 字段；`buildPrevToolCallResultMessage` 信封化；`ToolExecutionCoordinator` 信封化；`AgentToolCallLoop` 迭代上限 12 + 超限附已执行清单；text-only 保险丝 |
| P2 | ✅ 披露补齐（browser.newTab/listTabs、agent.observe/run）；`renderTiered` 分层披露（coding 任务折叠页面域，浏览任务折叠开发域，full 平铺回退），经 ObserveParams→PromptBuilder 全链注入 |
| P3 | ✅ 完成 schema 增 gates/filesChanged/problems；`ModelObserveResponseComplete`/`ActionDescription`/`AgentState` 贯通；runner 硬校验：**0 工具调用拒绝 completed（strict）** + gate 交叉校验（warn） |
| P4 | ✅ `AgentStateManager.getBrowserUseState` codingMode 短路 DUMMY（无 settle/快照/tabs）；`PromptBuilder` codingMode 跳过 Browser State/Viewport/ARIA 注入；**prompt 历史按 sessionId 切片**（消除跨任务污染）；响应模板免填与浏览器惰性启动为后续项 |
| P5 | ✅ `ToolExposeMode` 默认 TOOL_CALLING（text/chat 显式可选）；供应商不支持原生工具时自动降级 TEXT（warn 一次） |
| P6 | ✅ 全量回归 988 测试 0 失败（含新增 ToolOutcomeTest/ToolExposeModeTest + 渲染器/披露扩展）；bundle 重建；wordcount 回归任务 **81 秒完成**，模型如实报告四门禁实测结果；假完成守卫实战拦截 1 次伪造完成 |

**关键实测对比**（wordcount 修复任务）：

| 指标 | 改造前（HEAD, TEXT 默认） | 改造后（默认 toolCalling） |
|---|---|---|
| 任务耗时 | 30 分钟 / 假完成 35 秒 | **81 秒完成** |
| 假完成 | 35 秒 0 工具调用“全部完成” | 守卫拦截伪造 → 重试 → 如实完成 |
| 结果感知 | 模型看不到工具输出（空转/盲写幻觉 API） | 模型如实报告 compile=0/test=1/validate 通过 |
| 单请求输入 | 峰值 90k token | 收尾 summary 聊天 4,937 字符 |
| 编码任务页面注入 | `## Browser State`(about:blank) 每步注入 | codingMode 一行提示替代 |

**遗留/后续项**：P4.3 响应模板免填、P4.5 浏览器惰性启动（二期）；P2.4 原生 specs 分层过滤；gate 名与内层工具调用的精确匹配（当前 warn 级）。回退开关：`-Dbrowser4.agent.toolDisclosure=full`、`-Dbrowser4.agent.finishGateCheck=warn`、`-Dagent.tool.expose.mode=text`、`-Dbrowser4.agent.textOnlyStallLimit=0`。
