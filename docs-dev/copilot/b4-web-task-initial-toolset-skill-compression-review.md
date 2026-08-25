# b4 网页任务审查：初始工具集披露 · 主 SKILL 处理 · 压缩阶段快照可回溯性

> 日期：2026-08-22 · 性质：代码与设计文档核查（非运行时实测）
> 依据：`RobustBrowserAgent.kt`、`AgentToolCallLoop.kt`、`ToolDisclosure.kt`、`ToolLoopCompressor.kt`、
> `CompactionLedger.kt`、`PageViewDeduper.kt`、`SystemToolExecutor.kt`、`skills/browser4-cli/SKILL.md`、
> `docs-dev/copilot/browser4-agent-tool-disclosure-feedback-design.md`（§8）、
> `docs-dev/copilot/web-page-context-optimization-design.md`、`docs-dev/copilot/compaction-traceability-design.md`

---

## 1. 初始披露的工具集（梳理）

### 1.1 机制

CLI 引擎（`RobustBrowserAgent`）走 **TOOL_CALLING 渐进披露**（设计 §8，2026-08-21 落地）：

- `browser4.agent.toolLoop.initialToolSet`（默认 `core`）：初始请求只带**精选初始集 + 2 个元工具**
  （`system.listTools` / `system.exposeTools`，约 200 token）；模型需要更多工具时先
  `listTools(domain?)` 再 `exposeTools(names[])`，循环内拦截扩容，下一轮请求即生效
  （`AgentToolCallLoop.kt:229-241`、`ToolDisclosure.kt`）。
- 引擎域收敛：`CLI_ENGINE_DOMAINS = {coding, b4, system}`（`RobustBrowserAgent.kt:66`）。

### 1.2 网页任务（默认画像）的初始集构成

| 域 | 初始暴露内容 | 来源 |
|---|---|---|
| `b4`（整域恒在） | `run` / `version` / `help`（3 个） | `CLI_CORE_DOMAINS = {b4, system}`（:69） |
| `system`（整域恒在） | `help` / `skillDoc` / `taskComplete` + 2 元工具 | `CLI_CONTRACT_SYSTEM_METHODS = {taskComplete, skillDoc}` 强制包含（:105，任何 `initialToolSet` 下） |
| `coding`（白名单 10 个） | `read/write/append/replace/listDir/glob/grep/stat/mkdir/delete` | `CLI_BROWSING_CODING_METHODS`（:92-96） |
| 编码任务画像 | coding 核心 20 个（含 shell*/mvnBuild/validate/devTask） | `CLI_CORE_CODING_METHODS`（:77-82） |
| 长尾（mvnBuild、symbols、scaffold、插件工具…） | **初始不披露**，`listTools`/`exposeTools` 按需拉取 | — |

即：网页任务初始请求 ≈ **16-18 个工具规格**（对比全量 80+ 个完整 JSON Schema），
每轮数万 token 的规格开销被压到约 200 token 级别；`initialToolSet` 配置落空时自动回退 `core`
并告警（:181-189）。**SKILL.md 本身不在初始披露内**——只注入 frontmatter 元数据（见 §2）。

**小结：初始披露机制与网页任务画像匹配得当**；`taskComplete`/`skillDoc` 作为契约工具
强制包含、编码长尾按需展开、全量回退开关齐备（`initialToolSet=all` / `toolDisclosureEnabled=false`）。

---

## 2. b4 主 SKILL 的处理方式（梳理 + 评估）

### 2.1 现状（4 个事实）

1. **系统提示词只带元数据**：`cliAgentSystemPrompt()` 注入 `skillDocMetadata("SKILL.md")`
   （仅 YAML frontmatter，`SystemToolExecutor.kt:79-84`），并写明
   "Do not guess CLI syntax — fetch the bundled SKILL.md on demand with system.skillDoc(...)"（:1127-1145）。
2. **按需加载工具就绪**：`system.skillDoc(name)` 从 classpath `/skills/browser4-cli/<name>` 读取，
   上限 120,000 字符（`SystemToolExecutor.kt:63-68,172`）；SKILL.md 全量 50,585 字节 ≈ **14.5K token**。
3. **参考文档 27 个文件共 288 KB**，全部按需可读（`AVAILABLE_DOCS`，:175-185）。
4. **压缩器不识别 skillDoc**：`viewToolNames` 只有 `ariaSnapshot,textContent,snapshot,dump,htmlsnapshot,extract`
   （`RobustBrowserAgent.kt:1086-1089`），skillDoc 结果不享受任何保留/豁免。

### 2.2 发现的问题（P1，需修复）

**`system.skillDoc` 返回的完整文档会在下一轮请求前被 `pruneToolResults` 静默裁掉**：

- 循环每轮迭代**先压缩后发请求**（`AgentToolCallLoop.kt:152-163`）；
- `pruneToolResults` 对**任何**超 `pruneThresholdChars`（默认 **1,500** 字符）的工具结果做
  头 800 + 尾 400 字符裁剪，且**只跳过紧凑引用形态、无工具名豁免**（`ToolLoopCompressor.kt:128-144`）；
- 于是 `skillDoc("SKILL.md")` 的 50.6K 字符结果，在模型下一次看到它之前已被裁成 **1,200 字符**
  （frontmatter + 安装段），**完整 SKILL.md 实际从未进入模型上下文**；所有 >1,500 字符的
  参考文档（`snapshot.md`、`htmlsnapshot.md`、`x-sql.md`…）同理。模型只能在
  `cli-tool-trace.jsonl`（磁盘）里找到全文，上下文里永远只有头和尾。

这使"按需加载 SKILL"契约在执行层失效：模型被要求"查 SKILL 再动手"，但查到的是残片。
（注：`compressIfNeeded` 在 >60K token 时整体折叠旧轮次，但折叠的是**已裁剪的残片**，不改变结论。）

### 2.3 评估结论

- **披露方式（元数据常驻 + 完整文档按需加载）方向正确**——14.5K token 常驻相当于
  压缩阈值（60K）的 ~24%，且对非浏览器任务毫无价值；作为 system 消息还会被压缩器
  "永不移除"规则锁定为永久开销（`ToolLoopCompressor.kt:28`），按需加载是可回收的。
- **执行层有缺陷（P1）**，修复建议（按成本排序）：
  1. `ToolLoopCompressor` 增加受保护工具集（如 `system_skillDoc`），prune / budget 裁剪跳过，
     仍允许 `compressIfNeeded` 折叠（折叠后模型可重新拉取，重拉结果同样受保护）；
  2. 或在 `cliToolLoopCompressor()` 中把 skillDoc 结果阈值单独放大（
     `pruneThresholdChars` 对 `system_skillDoc` 不生效）；
  3. 配套：`COMPACTION_INSTRUCTION` 增加一句"若 SKILL 文档已被压缩，重新调用
     `system.skillDoc("SKILL.md")`"。

### 2.4 评估：主 SKILL 是否需要常驻上下文？

**不需要常驻完整版，但建议常驻一个蒸馏迷你版**：

| 方案 | token/请求 | 说明 |
|---|---|---|
| 现状（仅 frontmatter，~0.1K） | ~0.1K | 依赖模型自觉调用 skillDoc，且 P1 使其失效 |
| 完整 SKILL.md 常驻 | ~14.5K | 24% 阈值预算、非浏览器任务浪费、压缩不可回收（system 消息）——**不推荐** |
| **迷你版常驻 + 完整版按需**（推荐） | ~3-4K | 蒸馏 §1 核心循环 + Copy-Paste 模板 + §4a 决策树 + §5 关键警告；任何压缩之后模型仍有最小可用的 CLI 操作知识；细节仍走 skillDoc（修复 P1 后） |
| 引擎级"首轮强制注入" | 首轮 +14.5K | 镜像 `taskComplete` 契约：拦截首个 `b4.run` 前自动注入 SKILL.md 一次；首轮贵，但保证契约成立 |

---

## 3. 压缩阶段网页快照的可回溯性（评估）

### 3.1 现状盘点

**已落盘（压缩前的原始证据）：**

| 通道 | 内容 | 位置 |
|---|---|---|
| `cli-tool-trace.jsonl` | 每个工具调用：工具名、参数、**完整原始结果文本**（压缩/去重前的全文）、耗时 | `AgentPaths.resolveTraceRunDir(...)`（`RobustBrowserAgent.kt:981-997`） |
| `cli-prompt/<ts>.<seq>.request.json` | 每轮**精确请求**（消息 + 工具规格 + 估算 token） | 同 runDir（:920-956） |
| `cli-usage.jsonl` / `cli-events.jsonl` | 真实 token 用量 / 运行事件 | 同 runDir |
| CLI 侧 | `snapshot` YAML、htmlsnapshot HTML、webdb 缓存 | `~/.browser4` / 工作区状态目录 |

**未落盘 / 缺索引：**

1. **压缩检查点（`<compacted-summary>`）仅内存**——压缩提交后 `framed` 只替换消息列表，
   不写盘；`CompactionLedger` 明确设计为内存态"随循环结束丢弃"
   （`compaction-traceability-design.md:234`；`CompactionLedger.kt:26`）。循环结束后，
   "压缩掉了哪些轮次、Page State 时间线是什么"不可复盘。
2. **无结构化"页面访问时间线/链接索引"**——`## Page State` 小节要求每页
   url/title/指纹/变化要点（`ToolLoopCompressor.kt:415-417`），但它是 **LLM 生成的自由文本**
   （由压缩器结构校验只保证小节标题存在，不保证 URL 完整）；没有
   `{url, title, fingerprint, viewType, callId, seq, round}` 形式的机器可读索引，
   AI 无法程序化地从"某链接"回溯到"某轮原始快照"。
3. **磁盘原始结果 ↔ 压缩检查点之间无对账键**——ledger 的 `resolve(callId)` 只在内存；
   trace 文件里没有 compactionId/callId 的反向索引。

### 3.2 结论：需要导出（低成本，值得做）

理由：

1. **压缩是常态而非例外**：网页任务单步输入实测 220K-1.25M token（`web-page-context-optimization-design.md:19`），
   60K 阈值下几乎必然触发压缩；被折叠的恰恰是"页面状态"这一最关键上下文。
2. **"链接"是压缩后唯一可靠锚点**：checkpoint 中 URL 若被 LLM 转述丢失/截断，
   模型无法重新访问、复盘者无法定位原始证据；trace 文件可人肉回溯，但无索引时
   AI 无法自动回溯（无法 `grep url` 到对应快照轮次）。
3. **成本极低且不破坏既有设计**：只追加写盘、不改消息列表（KV-cache 前缀不变性不受影响）。

建议（三项，均为追加式 JSONL，写进现有 runDir）：

1. **`page-timeline.jsonl`**：每次页面视图（完整/引用/差异）追加一行
   `{callId, toolSeq, url, title, fingerprint, viewType, round}`——
   由 `PageViewDeduper`/loop 侧在 `onToolResult` 旁写入（`onToolResult` 已能拿到
   原始结果与请求参数，URL 可从结果文本/参数中提取）；这就是"记录链接"的最小实现。
2. **`cli-compactions.jsonl`**：压缩提交成功时追加
   `{compactionId, reason, shadowedRange, shadowedTokens, replacementTokens, pageState 摘要行}`——
   与 `CompactionLedger.recordCompacted` 同点写入，一行代码级成本，闭环审计。
3. **`COMPACTION_INSTRUCTION` 强化**："Page State 每行必须给出完整绝对 URL（禁止截断/省略）"，
   结构校验可加 URL 存在性检查（可选、warn 级）。

不做也成立的场景：单次短任务（全程 <60K token、不触发压缩）时，trace 文件已足够；
导出是"压缩发生时"的保险，不是所有任务的必需品。

---

## 4. 总结

| # | 结论 | 优先级 |
|---|---|---|
| 1 | 初始披露机制（core 初始集 + 元工具 + 契约工具）与网页任务画像匹配，**梳理通过** | — |
| 2 | 主 SKILL **不需要**完整常驻（14.5K token，压缩不可回收）；元数据 + 按需加载方向正确 | — |
| 3 | **P1**：`pruneToolResults` 会把 skillDoc 结果裁成 1,200 字符，模型从未看到完整 SKILL.md —— 需给 skillDoc 加裁剪豁免 | 高 |
| 4 | 建议常驻"蒸馏迷你版 SKILL"（~3-4K token），保证压缩后仍有最小 CLI 操作知识 | 中 |
| 5 | 压缩阶段**需要**导出链接索引：`page-timeline.jsonl` + `cli-compactions.jsonl` + Page State URL 强化；原始全文已落盘，缺的是机器可读的结构化索引与检查点持久化 | 中 |

---

## 5. 实现状态（2026-08-22 已落地）

### P1：skillDoc 裁剪豁免（高）

- `ToolLoopCompressor.kt`：新增 `protectedToolNames` 构造参数（默认空集）；
  `pruneToolResults` / `enforceResultTokenBudget` 对受保护工具名**跳过裁剪**（仍可被整段压缩，压缩后重拉）；
- 接线：`ContextToAction.kt` 与 `RobustBrowserAgent.cliToolLoopCompressor()` 新增配置键
  `browser4.agent.toolLoop.protectedToolNames`（默认 `system_skillDoc`）；
- 配套：`COMPACTION_INSTRUCTION` 新增规则——Page State 必须给**完整绝对 URL（禁止截断）**；
  若 SKILL 文档已被压缩，在 Critical Context 标注需经 `system.skillDoc` 重载。

### 迷你版常驻（中）

- 新增 `skills/browser4-cli/references/quickstart.md`（5.7KB ≈ 1.6K token）：核心循环、
  Copy-Paste 模板、关键命令表、snapshot vs htmlsnapshot 决策、refs 生命周期、关键警告、
  提取决策树、上下文纪律；经 pom 资源打包自动进入 classpath `skills/browser4-cli/**`；
- `SystemToolExecutor`：新增 `skillDocStrict(name)`（缺失返回 null，供提示词构建方决定回退），
  `AVAILABLE_DOCS` 加入 `quickstart.md`；`readBundledDoc` 复用 `sanitizeDocName`；
- `RobustBrowserAgent.cliAgentSystemPrompt()`：系统提示词由"仅 frontmatter 元数据"升级为
  **常驻迷你版 quickstart 全文**（缺失时回退元数据），并保留"完整 SKILL.md 按需加载"指引；
- `SKILL.md` §7 Reference Map 新增 quickstart.md 入口。

### 链接索引导出（中）

- `PageViewDeduper.kt`：抽出静态 `normalizeOf` / `fingerprintOf`（与实例指纹同口径），
  供 trace 写入方复用；
- `AgentToolCallLoop`：新增 `onToolDecorated(request, raw, decorated)` 回调（装饰后、追加前触发）；
- `CliLoopTracer`（`RobustBrowserAgent.kt`）：
  - `logPageView` → **`page-timeline.jsonl`**：每行 `{timestamp, seq, tool, callId, viewType
    (full/reference/diff), url, title, fingerprint, textChars, arguments}`；
    URL/title 为尽力提取（先命令行参数、后结果文本）；仅记录视图工具；
  - `logLedgerEntry` → **`cli-compactions.jsonl`**：ledger 每条持久化条目
    （registered/folded/pruned/compacted 含 token 账与失败留痕）；
- `CompactionLedger`：新增 `onEntry` 观察者（enabled 时每条落账后触发），CLI 引擎接线到 tracer。

### 测试

- `ToolLoopCompressorTest` +2：受保护结果绕过 prune / 绕过结果预算（保持全文）；
- `CompactionLedgerTest` +2：onEntry 逐条触发（类型断言）/ disabled 不触发；
- `AgentToolCallLoopTest` +1：onToolDecorated 收到 raw 与装饰形态（首视图 full、重复折叠 reference）；
- `SystemToolExecutorTest` +2：quickstart.md 可读且 <20K 字符 / skillDocStrict 缺失返回 null。

### 回退

- 豁免：`browser4.agent.toolLoop.protectedToolNames=`（空）恢复旧行为；
- 迷你常驻：删除/改名 `quickstart.md` 后自动回退 frontmatter 元数据（无硬编码）；
- 时间线/压缩日志：`logInferenceToFile=false` 或写盘异常时静默降级（与既有 trace 同策略）。

