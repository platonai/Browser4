# b4 代理三机制设计：工具披露 · 结果回传 · 完成报告

> 状态：设计稿（待评审实施） · 日期：2026-08-20 · 轮次：v1.4 监督轮聚焦设计
> 关联：`browser4-code-tool-agent-output-feedback-fix-plan.md`（P0 修复方案）、`browser4-code-tool-supervision-v1.4.md`（实测证据）
> 目标：模型**准确、高效**地拿到合适工具（披露），工具结果**被模型感知**（回传），上一步编程的**成果与问题**结构化呈现（完成报告）。三者共享一条主线：**受预算约束的结构化反馈闭环**。

---

## 0. 现状度量（本设计的事实基础，全部实测）

| 维度 | 实测 |
|---|---|
| 披露 | TEXT 模式 system 提示词 15,595 字符；84 个工具平铺（tab 24 / browser 2 / agent 2 / system 2 / cli 3 / coding 51）+ 技能段；纯浏览任务同样背着 51 个 coding 签名 |
| 披露漂移 | 清单是硬编码 `TOOL_CALL_SPECIFICATION` 第二事实源：`browser.newTab/listTabs`、`agent.run/observe` 存在但未披露；`fs.*` 靠注释人工剔除 |
| 回传（TEXT） | 无通道：`AgentState.toolCallResult` 被 `@JsonIgnore`；history 每步只有 `tool.done | null/null` |
| 回传（TOOL_CALLING） | 有原生 append，但无裁剪：mvnBuild 输出回灌致 **90k token/请求**；`AgentToolCallLoop.maxIterations=5` 导致链条中断重来 |
| 历史渲染 | `DefaultHistoryRenderStrategy`（预算 4000 字）压缩时整步塌缩成空 `{"step":N}`，连工具名都丢 |
| 完成报告 | 无结构化 step outcome；finish 仅靠提示词约束（v1.4），实测仍发生 35 秒/0 工具调用假完成 |

---

## 1. 机制一：工具披露（准确 + 高效）

### 1.1 原则
- **单一事实源**：披露清单由执行器注册表生成，不再手写第二份。
- **任务自适应**：按任务类型分层披露，无关域折叠成一行摘要，需要时按需展开。
- **预算受控**：披露段 token 上限，超限进一步折叠。

### 1.2 设计

**A. 生成化披露（消灭漂移）**

- 数据源：`AgentToolManager.getAllToolSpecs()`（7 个内置执行器 + `CustomToolRegistry` 插件工具）。
- 渲染：`ToolCallSpecificationRenderer` 改为序列化 `ToolSpec`（domain/method/arguments/returnType/description）生成 Kotlin 风格签名；`ToolSpecification.TOOL_CALL_SPECIFICATION` 降级为**回归基准**：单测断言"生成结果 ⊇ 硬编码串语义"，稳定后删除硬编码串。
- 补齐缺口：`browser.newTab/listTabs`、`agent.run/observe/done` 进入注册表后自动披露（done 标注为完成协议而非工具）。
- 完成协议显式化：系统提示词给出明确的"任务完成必须调用 `done` / 输出完成 JSON"约定，避免模型用 text-only 表示完成。

**B. 分层披露（任务自适应）**

```
L0 核心（任何任务）：完成协议 + system.help(domain[,method]) + skill.list —— ~50 token
L1 任务域（二选一，由 CodingTaskDetector.detect(task) 决定）：
  · coding 任务 → coding.*(51) + cli.*(3) 全量
                 + 一行摘要：「页面工具可用：tab.navigate/click/fill/type/ariaSnapshot 等 24 个；
                   需要签名时调用 system.help("tab")」
  · 浏览任务 → tab.* + browser.* + agent.* 全量
                 + 一行摘要：「编码工具可用：coding.read/write/shell/mvnBuild/devTask 等 51 个；
                   需要签名时调用 system.help("coding")」
L2 按需（模型主动调用）：system.help(domain[,method]) 返回完整签名；
  coding.classInfo（后端类路径 API）/ coding.ktSymbols / coding.languages 作为代码知识通道
```

- 实现锚点：`PromptBuilder`（注入任务分类结果）→ `ToolCallSpecificationRenderer.collectAllToolSpecs(includeCustomDomains, domainFilter)` 扩展为**通用域过滤 + 排序**（现仅有 `customDomainFilter`）；`RobustBrowserAgent.codingMode`（已存在）作为过滤开关。
- TOOL_CALLING 模式同样受益：`getLangChain4jToolSpecifications()` 走同一过滤，原生 spec 列表瘦身。
- 混合任务（编码+验证页面）风险：L1 摘要行 + L2 按需展开兜底；`browser4.agent.toolDisclosure=full|tiered` 可配置。

**C. 披露质量**

- 每条 `description ≤120 字`（TOOL_CALLING 模式下 description 即披露）；TEXT 签名中参数默认值必须与 `ToolSpec.Arg` 一致（生成保证）。
- 排序：域内按声明序，跨域按任务相关性（L1 域优先）。

**D. 度量目标**

- TEXT 模式工具段 ≤4,000 token；TOOL_CALLING 原生 specs ≤ 域内全量。
- 新增单测：披露清单与注册表 diff = 0；coding/浏览两种任务各自的披露集合断言。

---

## 2. 机制二：工具执行结果传递（可感知）

### 2.1 原则
结果在**源头自汇报**：统一信封、结构化、预算内裁剪；全量证据留在持久化日志。

### 2.2 ResultEnvelope（统一信封）

```kotlin
/** 每个工具执行后的标准化结果信封（模型视角的最小完备信息） */
data class ToolOutcome(
    val domain: String, val method: String,
    val ok: Boolean,              // 执行成功与否（exit 0 / 无异常）
    val summary: String,          // ≤120 字：一句话成果或失败原因
    val body: String? = null,     // 关键证据（按工具规则裁剪）
    val errors: List<String> = emptyList(),  // 每条 ≤200 字
    val workspaceDelta: String? = null,      // 写操作附带：files +n/-m, lines +x/-y
)
```

### 2.3 各工具裁剪规则（数值全部来自实测膨胀点）

| 工具 | ok 判定 | body 裁剪 |
|---|---|---|
| `coding.read` | 读到 | 首 40 行 + `…共 N 行`（大文件跳过中段） |
| `coding.listDir/glob` | 成功 | 前 50 条目 + 计数 |
| `coding.write/append/replace/delete/mkdir/copy/move` | 成功 | 字节数 + `workspaceDelta`（来自 `CodingAgentFileSystem` 的 changeSummary 增量：本次调用前后 diff） |
| `coding.mvnBuild` | exit 0 | exit code + **诊断前 10 条（每条 ≤200 字）** + 日志尾部 500 字 |
| `coding.shell` | exit 0 | exit code + stdout 尾部 3000 字 + stderr 尾部 1000 字 |
| `coding.validate` | 0 ERROR | ERROR 全量 + WARNING 计数 |
| `coding.devTask/impact/help/workspaceRoot/…`（短文类） | 成功 | 原样（本身短） |
| 任何异常 | false | 异常链 ≤300 字 |

### 2.4 回传通道（双模式）

- **TOOL_CALLING**（默认，见修复方案 A）：`AgentToolCallLoop` 的 `ToolExecutionResultMessage` 内容 = `ToolOutcome` 序列化——治理 90k token 爆炸；`maxIterations` 5→12（可配置）。
- **TEXT**（显式回退）：`DefaultHistoryRenderStrategy` 新增「### Tool Outcomes」小节，渲染近 6 步：
  ```
  ### Tool Outcomes
  3. coding.write [ok]  写入 WordcountService.kt (1,204 B) | Δ files +0, lines +47/-44
  4. coding.mvnBuild [fail] exit 1 — 24 diagnostics
     - error WordcountToolExecutor.kt:5:25 Unresolved reference 'pdk'
     - error WordcountAutoConfiguration.kt:4:25 Unresolved reference 'pdk'
  ```
- **预算治理**：单步 outcome ≤600 字；总历史预算维持 4000 字；压缩优先级 `thinking > keyFindings > nextGoal > body`，但 `step/tool/ok/summary` 永不丢弃（修掉 `{"step":N}` 空壳渲染）。
- 结果全量始终落盘（`stateHistoryPath` 已提示，渲染器保留指引行）。

### 2.5 保险丝（上轮方案 C，随本机制一并落地）
连续 text-only 响应 ≥5 次（配置 `browser4.agent.textOnlyStallLimit`，默认 5，0=禁用）→ `StopReason.NOOP_LIMIT` 标 failed。

---

## 3. 机制三：代码完成情况报告（成果 + 问题）

### 3.1 Step Outcome（每步自动，机制二的直出物）
模型每轮必见上一步的 `ToolOutcome`（tool/ok/summary/errors/workspaceDelta）——"上一步编程的成果和问题"以最小 token 呈现；写操作自带 workspaceDelta 即"编程成果"的量化。

### 3.2 Task Finish 报告（结构化 + runner 校验）

模型完成时输出（替代自由文本 summary）：

```json
{
  "taskComplete": true,
  "gates": [
    {"name": "compile",            "ran": true,  "exitCode": 0, "ok": true},
    {"name": "tests",              "ran": true,  "exitCode": 0, "ok": true,
     "detail": "Tests run: 5, Failures: 0, Errors: 0"},
    {"name": "validate-plugin",    "ran": true,  "ok": true},
    {"name": "repo-consistency",   "ran": false, "ok": null, "reason": "not run"}
  ],
  "filesChanged": ["browser4-plugins/browser4-wordcount/src/.../WordcountService.kt"],
  "problems": []
}
```

**Runner 侧硬校验**（`StatefulAgentRunner` / `RobustBrowserAgent` 收尾处）：
1. 本任务工具调用数 == 0 → 拒绝 completed，标 failed（reason=`no-tool-calls`）——直接封死"35 秒假完成"；
2. 每个 `ran:true` 的 gate 与状态历史中的实际工具调用比对（工具名、exit code 不符 → failed，reason=`gate-mismatch`）；
3. `ran:false` 的 gate 必须带 reason，否则视为无效报告；
4. 校验通过的 gates 随 `agent result` 输出，监督者可复核。

### 3.3 提示词配合
`MainSystemPrompt` FINISH 段引用上述 schema（已有"锚定实测门禁"约束，升级为结构化 schema + 校验后果说明："0 次工具调用或 gate 与实测不符将被判为失败"）。

---

## 3.5 补充机制：编码任务的页面信息隔离（不自动获得网页信息）

> 原则：编程智能体不自动获得网页信息，除非它**显式调用工具**（tab.navigate/ariaSnapshot/textContent/eval 等）。本轮实测发现该原则尚未落实。

**现状事实（HEAD 实测）**：

- `PromptBuilder.buildMultistepAgentMessageListAll`（PromptBuilder.kt:311-323）每步**无条件**调用 `buildBrowserStateMessageForBrowserInteraction`（:320），向编码任务的提示词注入 `## Browser State`（url/tabs/scroll/viewport）、`## Viewport State`、`## ARIA Snapshot` 三段——抓包实证：纯编码任务（wordcount 修复轮）的请求里含 `{"url":"about:blank","tabs":[...],"viewport":{...}}`。
- `AgentStateManager.getBrowserUseState`（AgentStateManager.kt:508-539）每步**无条件**执行 `waitForDOMSettle()`（旧后端实测每步 5s 超时日志）+ `driver.browserUseState(...)` 全量快照（AX 树/样式/DOM rects/滚动分析/可见性，30s 超时保护）——编码任务纯浪费。
- `codingMode` 全仓库仅 2 处守卫：截图跳过（BasicBrowserAgent.kt:675）、搜索引擎导航跳过（RobustBrowserAgent.kt:510）。
- 浏览器会话仍启动（about:blank 页 + tabs 注入）；模型响应模板仍要求填写 `screenshotContentSummary/currentPageContentSummary`。

**设计**：

1. **短路**：`codingMode=true` 时 `getBrowserUseState()` 直接返回 `BrowserUseState.DUMMY`（不 settle、不快照、不注入 tabs）；`buildBrowserStateMessageForBrowserInteraction` 替换为一句话：`当前无页面上下文；如需网页信息，请显式调用 tab.navigate / tab.ariaSnapshot / tab.textContent / tab.eval`。
2. **唯一入口**：页面信息的获取通道只剩 `tab.*` 工具——模型调用后才驱动页面（惰性）。
3. **响应模板**：codingMode 下 `screenshotContentSummary/currentPageContentSummary` 不要求填写（或统一 "N/A"）。
4. **二期（可选）**：codingMode 任务不预启动浏览器；模型调用 `tab.*` 时按需绑定 driver。
5. **契约**：写入系统提示词（codingMode 分支）+ 披露层联动（L1 coding 摘要行已含 tab 提示，语义一致）。

**验收**：编码任务请求 JSON 断言无 `## Browser State` 段；任务全程无 DOM settle 日志；模型调用 `tab.navigate` 后提示词才出现页面信息。

---

## 4. 落地锚点（文件:行 → 改动）

| # | 机制 | 锚点 | 改动 |
|---|---|---|---|
| 1 | 披露 | `specs/ToolSpecification.kt:8`（硬编码串） | 降级为回归基准；新增生成化渲染 |
| 2 | 披露 | `specs/ToolCallSpecificationRenderer.kt:201`（collectAllToolSpecs） | 通用域过滤 + 分层排序 |
| 3 | 披露 | `AgentToolManager.kt:133-139`（注册点） | 全执行器注册表作为披露数据源 |
| 4 | 披露 | `PromptBuilder.kt:317-347` | 注入任务分类 → 选择披露层 |
| 5 | 回传 | `CodingToolExecutor.kt` 各返回点 | ToolOutcome 信封 + 2.3 裁剪表 |
| 6 | 回传 | `coding/CodingAgentFileSystem.kt` | 写操作附带 workspaceDelta |
| 7 | 回传 | `inference/chat/AgentToolCallLoop.kt:90` | 结果消息用信封；maxIterations 可配置 |
| 8 | 回传 | `inference/history/DefaultHistoryRenderStrategy.kt:124-136` | Tool Outcomes 小节 + 压缩优先级 |
| 9 | 回传 | `model/AgentState.kt:62` | resultPreview 派生属性（修复方案 B） |
| 10 | 完成报告 | `RobustBrowserAgent.kt:356-396` | text-only 保险丝；收尾 gate 校验 |
| 11 | 完成报告 | `tools/advanced/agent/StatefulAgentRunner.kt` | finish schema 解析 + 硬校验 |
| 12 | 完成报告 | `prompts/MainSystemPrompt.kt`（FINISH 段） | schema 与校验后果说明 |

| 13 | 隔离 | `inference/PromptBuilder.kt:311-323, :663-750` | codingMode 下跳过 Browser State/Viewport/ARIA 注入，替换为一行提示 |
| 14 | 隔离 | `inference/AgentStateManager.kt:508-539` | codingMode 短路返回 DUMMY（不 settle、不快照） |
| 15 | 隔离 | `agents/RobustBrowserAgent.kt:505-521` | 浏览器惰性启动（二期）；先落实 13/14 |

## 5. 预算账（效率验收线）

- 披露：TEXT 工具段 ≤4,000 token（现状 ≈5,000+，且随域增长）
- 回传：单步 outcome ≤600 字；总历史 ≤4,000 字不变
- 单请求输入：TEXT/toolCalling 均 ≤15,000 token（现状峰值 90,000）
- 单任务工具调用：空转 0 容忍（保险丝 5 次 text-only 即停）

## 6. 测试与验收

| 层 | 用例 |
|---|---|
| 单元 | 披露=注册表 diff 0；coding/浏览任务分层断言；信封裁剪（mvnBuild 长输出、read 40 行）；ToolOutcomes 渲染预算不超且 step/tool/ok/summary 不丢；text-only 5 次中止；0 工具调用拒绝 completed；gate 与实测不符判失败 |
| 集成 | wordcount 类任务 TEXT 与 toolCalling 各一轮：无 listDir 空转、每步 outcome 可见、finish gates 与实测一致、输入 token ≤15k |
| 回归 | `mvn test -pl browser4-agentic -am`；v1.4 插件端到端基线（scaffold→编译→测试→校验→部署） |

## 7. 风险与回退

- 分层披露误伤混合任务 → L1 摘要行 + `system.help` 按需展开 + `browser4.agent.toolDisclosure=full` 一键回退平铺。
- 信封裁剪丢长输出 → 全量在 `stateHistoryPath` 持久化日志（渲染器已有指引行）。
- 生成化披露与硬编码串并存期 → 双轨 diff 单测守护，稳定后删硬编码串。
- gate 硬校验误伤诚实报告（如工具输出了非标准 exit code）→ 校验先告警再判失败，日志留证据，`browser4.agent.finishGateCheck=warn|strict` 可调。

---

## 8. TOOL_CALLING 模式落地：初始工具集优化 + 循环内渐进披露（2026-08-21 已实现）

> 问题：原生工具调用模式下，`AgentToolCallLoop` 每轮把**全部**工具规格（coding ×51、b4、shell、fs、插件等 ≈80+ 个完整 JSON Schema）随请求发给模型——每轮数万 token，且与任务无关的工具稀释了模型决策。

### 8.1 机制

```
初始集（curated core）──▶ 每轮请求只带 初始集 + 2 个元工具（~200 token）
                                │
   模型需要更多工具：system.listTools(domain?)  ──▶ 列出未暴露工具（名 + 一行描述）
                     system.exposeTools(names[]) ──▶ 循环内拦截并扩容 exposed 集合
                                │
                   下一轮请求自动携带扩容后的工具规格
```

- **初始集**：`browser4.agent.toolLoop.initialToolSet` = `core`（默认：tab/browser/agent/system 整域）| `all`（旧行为全量）| 显式模式列表（`domain.*`、`domain.method`、裸 `method`；`core`/`all` 可作 token 与模式混用，如 `core,coding.ktSymbols`；模式全部落空时自动回退 `core` 并告警）；
- CLI 引擎（`RobustBrowserAgent`）初始集按任务画像自适应：b4/system 整域恒在；
  - 网页任务（默认）→ coding 只暴露最常用文件工具 10 个（read/write/append/replace/listDir/glob/grep/stat/mkdir/delete），shell*/mvnBuild/validate/devTask 等编码工具不进入初始集；
  - 编码任务（`CodingTaskDetector` 判定）→ coding 暴露核心 20 个（read/write/replace/mvnBuild/shell/validate/devTask/...）；
  - 长尾（symbols/kt*/scaffold*/impact/...）两种画像下都按需暴露（`system.listTools`/`system.exposeTools`）；
  - `system.taskComplete` 与 `system.skillDoc` 为契约工具，无论 `initialToolSet` 为何值都强制包含（system 域规格取自 `SystemToolExecutor`，硬编码 `TOOL_CALL_SPECIFICATION` 只有 `system.help`）；
- **元工具**由 `AgentToolCallLoop` 拦截合成，不经过协调器：`exposeTools` 就地扩容 `exposedToolSpecs`，下一轮请求即生效；裸方法名仅在跨域唯一时解析（歧义跳过并报告）；结果消息自解释（已启用/跳过清单）；
- 与既有机制正交：`PageViewDeduper`（结果折叠）、`ToolLoopCompressor`（压缩）、`RequestTokenLimiter`（限额）不受影响。

### 8.2 实现与测试

- 新增 `inference/chat/ToolDisclosure.kt`（`ToolDisclosureTools`：元工具规格、`selectInitialSpecs`、`listToolsResult`/`exposeToolsResult` 合成、参数解析——纯逻辑）；
- 修改 `AgentToolCallLoop`：`allToolSpecifications` + `disclosureListingLimit` 构造参数；请求携带 `exposedToolSpecs + metaSpecs`；执行循环拦截两个元工具；元工具不计入 overflow digest；
- 修改 `ContextToAction` / `RobustBrowserAgent`：配置键 + 初始集计算（ToolSpec 层选择 → 转换器统一命名）；
- 配置键：`browser4.agent.toolLoop.initialToolSet`（默认 core）、`toolDisclosureEnabled`（默认 true）、`toolDisclosureListingLimit`（默认 200）；
- 测试：`ToolDisclosureTest`（9 用例）+ `AgentToolCallLoopTest` 增补 2 例（初始请求只带核心集+元工具；exposeTools 后下一请求携带扩容规格）；browser4-agentic 全量 758 测试全绿。

### 8.3 关键注意点

- 工具名是转换器的**消毒形式**（`system_listTools`、`coding_read`，`.` → `_`），所有匹配按该形式；
- 初始集选择在 `ToolSpec` 层做（domain/method 完整），再转原生规格；
- 默认值变更会影响既有任务（coding 长尾不再自动可见）——回退：`initialToolSet=all` 或 `toolDisclosureEnabled=false`。
