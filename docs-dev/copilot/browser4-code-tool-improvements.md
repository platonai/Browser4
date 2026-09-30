# browser4 `code` 工具与 b4 代理改进建议（正式文档）

> 版本：1.2 · 日期：2026-08-20 · 作者：监督会话（测试 `./b4w.ps1 code` 工具 + 监督 b4 完成插件开发任务）
> 依据：9 轮 b4 agent 执行记录 + 全链路独立验证，详见 `browser4-programming-support-eval.md`（历史背景）与本会话监督时间线 `supervision-timeline-pagetitle.md`
> 范围：`CodingToolExecutor` / `CodingAgentFileSystem` / `DevTaskPlanner` / `RobustBrowserAgent` / `StatefulAgentRunner` / CLI `code` 命令族

---

## 0. 结论摘要

`./b4w.ps1 code` 工具族（23 个 CLI 子命令 → `coding.*` MCP 工具）在新构建后端上**链路全部可用**：read/write/append/replace/diff/changes/delete/validate/mvn/devtask/workspace 等均工作正常，`code devtask --verify` 端到端验证通过。

测试暴露 **3 个文件系统工具正确性缺陷（P0）**、**4 个代理执行可靠性问题（P1）**、**3 个计划/流程/治理缺口（P1/P2）**。b4 代理在 9 轮迭代后完成插件开发任务（构建/测试/校验全过），但前 5 轮的系统性失败均由下述缺陷直接或间接导致。

**v1.1 修复进度**（2026-08-19 下午，P0 + noop 核心）：

| 项 | 状态 | 说明 |
|---|---|---|
| 1.1 glob 基座算法 | ✅ 已修复 | `indexOfLast` → `indexOfFirst`（第一个通配段为界）；CLI 实测 `glob "browser4-plugins/*/pom.xml"` 返回 11 个 pom，不再抛 `Illegal char <*>` |
| 1.2 `**/*` 根级文件 | ✅ 已修复 | 双 matcher（`**/` 前缀剥离变体）；CLI 实测 `glob ".../browser4-pagetitle/**/*"` 含根级 README.md/pom.xml/build.ps1（12 项） |
| 1.3 listDir 静默截断 | ✅ 已修复 | 深度按请求执行、上限 32、超出时输出 `(depth capped at 32)`；CLI 实测 `--depth 10` 列出 8 层文件、`--depth 100` 带提示 |
| 2.2 noop 误杀（核心） | ✅ 已修复 | ①`isBrowserInteraction(null/blank)` → **false**；②`step()` 仅对**实际发起过浏览器工具调用**的失败计 noop；③中止原因（NOOP_LIMIT/MAX_STEPS）传入 `buildFinalActResult`，任务**标记 failed** 而非 completed；④长工具（fs/cli/coding 域）失败天然不计 noop |
| 1.1-1.3 配套测试 | ✅ 已加 | `CodingAgentFileSystemGlobListTest` 新增 9 个回归用例，全绿 |
| 2.2 配套测试 | ✅ 已加 | `ToolSpecificationTest` null/blank 断言翻转，全绿 |

**v1.2 修复进度**（2026-08-20，四阶段全部完成）：

| 阶段 | 项 | 状态 | 说明 |
|---|---|---|---|
| 1 | prepareStep 死代码回传 | ✅ | 页面状态 noop 计数**回传循环计数器**，冻结页面（连续 3 步无变化且达上限）直接中止并标记 failed；顺手修了 v1.1 遗漏：`lastStopReason` 从未从 `stepResult.stopReason` 赋值（NOOP_LIMIT 中止实际未被标 failed） |
| 1 | `--noop-limit` 任务化 | ✅ | CLI `agent run --noop-limit <n>` → `command_run.noopLimit` → `StatefulAgentRunner` → `RobustBrowserAgent.noopLimitOverride`（@Volatile，随任务重置）；后端日志实测 "noop limit overridden to 10" |
| 1 | image 降级前置（2.3） | ✅ | 按模型名前缀预判 text-only 模型（`deepseek`/`o1`/`o3`，可 `-Dbrowser4.agent.vision.enabled=false` 强制），首步即跳过截图；回归任务日志**零 `image_url` 重试**（原 3 连空转每次 ~3.5s） |
| 1 | FINISH 提示词（2.2 尾） | ✅ | `MainSystemPrompt`"When to Finish"强化：完成后必须立即输出完成 JSON，禁止纯文本收尾 |
| 2 | coding 模式（2.1 方案 A） | ✅ | `CodingTaskDetector`（URL/页面动词优先，保守判定）→ `RobustBrowserAgent.codingMode`：跳过 driver 健康检查、搜索引擎导航、截图；**新会话（无预热）编码任务 20.3 秒 2-3 步完成**（此前需预热 + 有 Bing DOM 30s 超时风险）。方案 B（AiServices 编码 agent）留待后续 |
| 3 | DevTaskPlanner（3.1） | ✅ | ①FILE_PATTERN 按长度降序 + `(?!\w)` 结尾锚定（`browser4-plugin.json` 不再截成 `.js`）；②`browser4-plugins/<新名>` 识别为新插件模块 → 计划插入 scaffoldToDir（含 ModuleMap 同步提示）；③测试类 `-Dtest` 按 camelCase→kebab 名称匹配绑定所属模块（`PagetitleConfigTest`→pagetitle；任务含显式模块信号时尊重显式信号）。新增 5 个回归用例 |
| 3 | ModuleMap 治本（3.2） | ✅ | ①`scaffoldToDir` 自动同步 `ModuleMap.MODULES`（唯一锚点行插入）+ 明确提示补 DEPENDENTS 四边；②`RepoConsistencyCheck` 增加 ModuleMap↔pom **双向核对**（MODULES 缺失 + DEPENDENTS 逐模块集合比对，漂移即 ERROR）；③`code devtask` 计划的 validate 步骤文案同步 |
| 3 | validate 语义检查（3.3） | ✅ | `validatePlugin` 增加桩代码标记扫描（`TODO implement`/`NotImplementedError`/`UnsupportedOperationException`/`FIXME`/`stub` 等 → WARNING）+ JS 空 `data:{}` 返回检测；已实现的 pagetitle 插件不误报（实测 "✓ All checks passed"） |
| 4 | `agent run --wait`（4.1） | ✅ | 选项已声明；**额外修复终态识别缺陷**：后端成功态是 `processState="completed"`，原 CLI 只认 `"done"` → `--wait` 永远轮询到超时。现认 `done|completed` + `statusCode>=400`/`failureReason` 判失败 |
| 4 | changes 快照隔离（4.2） | ✅ | `FileSnapshot` 增加 `trackedAtMillis`，`changeSummary` 默认 24h 窗口过滤共享实例的跨调用方旧噪音（隐藏数量显式报告；`diff`/`revert` 不受影响）。完全按会话隔离需独立实例化，列为残余项 |
| 4 | 自毁护栏（4.3） | ✅ | `MainSystemPrompt`"File Handling"增加：先写新文件并验证存在，再删旧文件，禁止"改旧名+删旧文件"式移动 |
| 4 | b4 端到端回归 | ✅ | 新会话编码任务 ×2 均 2-3 步完成（20.3s），无 noop 中止、无 image_url 重试、无 Bing 导航；`code devtask --verify` 全绿；`browser4-coding` 全量 273 测试全过。完整插件开发任务回归（>30 步场景）留待下次观察 |

**残余项**：noop 上限按任务配置文件化（已可用 `--noop-limit` 绕过）、changes 完全按会话隔离、AiServices 编码 agent（方案 B）、TOOL_CALLING 补全（见 langchain4j 评估文档）。

**修复优先级（原）**：先修 P0 工具缺陷（半天），再修 P1 代理可靠性（1-2 天），最后补流程闭环（1 天）。**P0 三项 + P1 核心 + P1-2 治理已全部完成**，剩余见第 5 节路线图。

---

## 1. P0 — code 工具正确性缺陷（直接导致 b4 死循环/崩溃）

### 1.1 `code glob`：含"通配符后跟非通配段"的模式崩溃 ✅ 已修复

- **复现**：`code glob "browser4-plugins/*/pom.xml"` → `ERROR: coding_glob failed: Illegal char <*> at index 17`
- **位置**：`browser4-coding/.../CodingAgentFileSystem.kt`（原 664-674 行）
- **根因**：`allParts.indexOfLast { !it.contains("*") && !it.contains("?") }` 取的是**最后一个**非通配段（如 `pom.xml`），`take(lastNonWildcard+1)` 把整串（含通配符）拼进 `canonicalRoot.resolve(...)` → Windows 的 `Path.resolve(String)` 对 `*` 抛 `InvalidPathException`。正确语义应为"**第一个通配段之前的目录**"做基座。
- **影响**：b4 第 2 轮因此反复重试，配合 1.2/1.3 形成"文件清单不确定"死循环（52 步零写入）。
- **修复（已实施）**：
  ```kotlin
  // 基座 = 第一个通配段之前的所有段；globPart = 从第一个通配段起
  val firstWildcard = allParts.indexOfFirst { it.contains("*") || it.contains("?") }
  baseDir  = if (firstWildcard > 0) canonicalRoot.resolve(allParts.take(firstWildcard).joinToString("/"))
             else canonicalRoot
  globPart = if (firstWildcard >= 0) allParts.drop(firstWildcard).joinToString("/")
             else allParts.joinToString("/")
  ```
- **测试**：`CodingAgentFileSystemGlobListTest.globWildcardInMiddle` / `globDeepWildcardBase` / `globFilenameOnly`；CLI 实测通过（见 §0 表）。

### 1.2 `code glob "dir/**/*"` 漏掉根级文件 ✅ 已修复

- **复现**：`code glob "browser4-plugins/browser4-pagetitle/**/*"` 只返回 `src/...` 下 6 个文件，**根级 pom.xml/README.md/build.ps1 不出现**
- **位置**：`CodingAgentFileSystem.kt` 的 `glob()` 匹配段（原 683-701 行）
- **根因**：JDK `FileSystems.getDefault().getPathMatcher("glob:**/*")` 的语义要求**至少一个目录层级**（实测 `glob:**/*` 匹配 `sub/pom.xml` 但**不匹配** `pom.xml`）——`**` 的"零目录"语义在 Java glob 里不成立。
- **影响**：b4 第 2/4/5 轮反复 glob 同一目录怀疑"文件丢了"；第 4 轮模型自己察觉"glob is unreliable for root files"仍无法解决。第 1 轮 `cli.run(powershell ...)` 误用（browser4-cli 无该子命令，只输出帮助）即源于此路径不可信。
- **修复（已实施）**：当 `globPart` 以 `**/` 开头时，额外构建一个去掉 `**/` 前缀的 matcher（只匹配单层路径），与主 matcher 一起判定：
  ```kotlin
  val rootLevelMatcher = if (globPart.startsWith("**/")) {
      FileSystems.getDefault().getPathMatcher("glob:${globPart.removePrefix("**/")}")
  } else null
  // visitFile: pathMatcher.matches(rel) || rootLevelMatcher.matches(rel) || pathMatcher.matches(file.fileName)
  ```
- **测试**：`globDoubleStarMatchesRootFiles` / `globDoubleStarKtRootFiles` / `globDirDoubleStarRootFiles`；CLI 实测通过。

### 1.3 `code list` 深度静默截断到 5 层 ✅ 已修复

- **复现**：`code list browser4-plugins/browser4-pagetitle --depth 10` 只显示 14 项，`src/main/kotlin/ai/platon/pulsar/pagetitle/` 下所有 `.kt` 文件不可见，且**无任何截断提示**
- **位置**：`CodingAgentFileSystem.kt` `listDir()` — 原 `Files.walk(resolved, maxDepth.coerceIn(1, 5))`
- **影响**：b4 第 2 轮要 10/20 层深度，实际只给 5 层；目录深于 5 层的仓库（本项目标配）中，文件树永远"看起来不完整"→ 代理反复换工具/换深度确认 → 探索成本爆炸。
- **修复（已实施）**：请求深度如实执行，上限提到 32（`MAX_LIST_DIR_DEPTH`），超出上限时输出显式提示 `(depth capped at 32)`，不再静默：
  ```kotlin
  val effectiveDepth = maxDepth.coerceAtLeast(1).coerceAtMost(MAX_LIST_DIR_DEPTH)
  val depthCapped = maxDepth > MAX_LIST_DIR_DEPTH
  // 输出头部: "Contents of $path (N entries)( depth capped at 32):"
  ```
- **测试**：`listDirDeepDepth` / `listDirShallowDepth` / `listDirDepthCappedWithNotice`；CLI 实测通过。

> **P0 总结论**：1.1+1.2+1.3 三者叠加 = 代理永远无法获得"正确且完整的文件树"，这是前 5 轮失败的**第一根因**。三项已全部修复并验证，b4 的探索效率应显著回升。附录 A 第 4 条"禁缺陷工具（不要用 listDir/glob）"**可以移除**，恢复为正常使用。

---

## 2. P1 — b4 代理执行可靠性

### 2.1 无"编码模式"：浏览器观察环节拖死/杀死纯编码任务

- **证据**：第 3 轮 `🛑 doResolve.cancelled sid=... steps=12 reason=Timed out waiting for 30000 ms`（`DOM.getDocument` 30s 超时 → 整个 run 取消，零产出）。
- **位置**：`StatefulAgentRunner.executeAgentCommand`（`ensureSessionDriverHealthy()` 强制创建浏览器）+ `RobustBrowserAgent.observe/doResolve`（每步页面 DOM 快照）。
- **影响**：纯编码任务（无需页面）仍每步做页面观察：浪费 token、易超时、并把"browser on Bing"等无关内容塞进上下文。
- **修复建议**：
  1. 增加 **coding 专用执行路径**：当任务不含 URL/页面意图（或显式标记）时，跳过 `ensureSessionDriverHealthy` 与 observe 阶段，仅保留 think→tool→act 循环；
  2. 或提供配置项（如 `browser4.agent.codingMode=true`）由服务端决定；
  3. 短期缓解（已在第 4 轮验证有效）：`-s <name> open about:blank` 预热会话可消除 DOM 超时。

### 2.2 `noop.stop(limit=5)` 误杀未完成任务 ✅ 核心已修复

- **证据**：第 1 轮 16 步、第 5 轮 61 步、第 7 轮 61 步均以 `⛔ noop.stop step=N limit=5` 中止；其中第 7 轮**刚修好编译错误、正在重跑 mvn 时**被 5 连 noop 杀死；第 3 轮中止时模型明确输出 `taskComplete=false`。
- **位置**：`RobustBrowserAgent.kt` `step()`（原 386-399）/ `handleConsecutiveNoOps`（527-558）；`ToolSpecification.isBrowserInteraction`（89-94）。
- **根因**：`isBrowserInteraction(null)` 因"安全默认"返回 **true** → LLM 的**纯文本响应**（无工具调用）被当成浏览器交互失败计入 noop → 连续 5 次文本响应即中止；且中止后任务状态标记为 `completed`，监督方无法从状态机区分成功/失败。
- **修复（已实施）**：
  1. `ToolSpecification.isBrowserInteraction(null/blank)` → **false**（"无工具调用 ≠ 浏览器交互"），四个调用点（noop 计数、页面状态检查、截图判定、提示词构建）语义一致化；
  2. `RobustBrowserAgent.step()` 仅当**实际发起过工具调用**（`lastToolCall != null`）且域为浏览器交互时才计 noop——纯文本/思考响应、fs/cli/coding 域失败（含长工具超时）一律不计；
  3. `StepProcessingResult` 携带 `stopReason`（`COMPLETED`/`NOOP_LIMIT`/`MAX_STEPS`），`buildFinalActResult` 对非 COMPLETED 中止**标记 failed**（`IllegalStateException("Agent loop stopped abnormally: ...")`），不再伪装成功；
  4. 截图判定保留首步安全（`step <= 1` 显式截图），文本/非浏览器步骤不再浪费截图 token。
- **遗留（未做）**：noop 上限按任务配置（`consecutiveNoOpLimit` 本身已是配置项，可后续暴露到任务参数）；提示词 FINISH 标记；**新发现**：`prepareStep()` 中页面状态 `unchangedCount >= 3` 的 noop 累加是**死代码**——局部变量递增后未回传循环计数器，页面状态型 noop 检测实际从未生效（本次未改，避免行为突变，列为后续项）。
- **测试**：`ToolSpecificationTest`（null/blank 断言翻转 + 大小写/自定义域回归）；`browser4-agentic` 模块编译通过。

### 2.3 DeepSeek（text-only 模型）的截图降级没有接住

- **证据**：第 3 轮日志 `Failed to send chat message for 3 times: ... unknown variant 'image_url', expected 'text'`；`ContextToAction.isImageNotSupportedError` 本可匹配该错误，但重试发生在 `CachedBrowserChatModel` 层，先空转 3 次。
- **位置**：`ContextToAction.kt:156-310`（vision 探测与降级）、`CachedBrowserChatModel` 重试逻辑。
- **修复建议**：把 image-not-supported 探测提前到 chat 模型层（首见 image 即试发一次并捕获 `image_url` 类错误 → 标记 `modelSupportsVision=false` 并降级重发），或让 `CachedBrowserChatModel` 复用同一判定函数后再重试。

### 2.4 上下文膨胀 → 行为退化（重读/乱调用/文本化）

- **证据**：第 4/5 轮代理反复重读已读过的同一批文件（`PagetitleToolExecutor.kt` 等 3-4 次）；第 4 轮 66 步开始调用无关的 `skill.debug.scraping.run`；长任务后期 text-only 响应比例上升（noop 频发）。
- **根因**：每步请求携带全量历史（第 24 步时请求已 13K 字符、实际 payload 更大），模型注意力稀释；且现有上下文截断未对工具结果做语义压缩。
- **修复建议**：
  1. 对**大工具结果**（read 全文、mvn 输出）做滚动摘要或只保留最近 N 步原文；
  2. 系统提示增加约束："不要重复读取已读过的文件；内容在你的记忆里"；
  3. 任务编排层：把大任务拆成小任务（本次第 8/9 轮小任务配方即验证有效，见附录 A）。

---

## 3. P1/P2 — 计划、校验与流程闭环

### 3.1 `DevTaskPlanner` 计划质量问题

- **证据**（`code devtask "<插件任务>"` 实际输出）：
  - 任务文本中的 `browser4-plugin.json` 被 `FILE_PATTERN` 提取为 `browser4-plugin.js`；
  - 测试类 `PageTitleConfigTest` 被编排到 **browser4-plugins/browser4-seo** 模块执行（新模块无法推断，退化为参考模块）；
  - 未识别"创建新插件模块"类任务（无任何模块信号 → 计划塌缩为 validate+commit 两步）。
- **位置**：`DevTaskPlanner.kt:37`（FILE_PATTERN 扩展名交替顺序 `js` 在 `json` 前，未锚定结尾）、`:76-98`（模块推断）、`:162-170`（测试步骤）。
- **修复建议**：
  1. FILE_PATTERN 扩展名按长度降序排列或加 `(?!\w)` 结尾锚定；
  2. 对 `browser4-plugins/<new-name>` 形态的路径识别为"新插件模块"信号，计划中给出 scaffold → 实现 → ModuleMap 同步 → 构建 → 校验的完整步骤；
  3. 测试步骤的 `-Dtest` 应绑定到**任务文件所属模块**而非任意推断模块。

### 3.2 ModuleMap 同步缺口（治理流程）🩹 已治标同步，自动同步待做

- **证据**：`scaffoldToDir` 自动把 `<module>browser4-pagetitle</module>` 写入 `browser4-plugins/pom.xml`（`CodingToolExecutor.kt:1281-1299`），但 `ModuleMap.MODULES` 无任何自动同步；`code validate repo-consistency` **不检查 ModuleMap**（只查 VERSION/pom/BOM/sdkVersion），本次任务全部 validate 通过，但 `ModuleMapDriftE2ETest` 会失败。
- **2026-08-19 治标（已做）**：`ModuleMap.kt` 手动同步——`MODULES` 补 `browser4-plugins/browser4-pagetitle`；`DEPENDENTS` 在 agentic/pdk/protocol/skeleton 四个列表补 pagetitle（pagetitle 的 pom 依赖它们：skeleton/protocol/agentic + parent pdk）；pagetitle 本身是叶子（无依赖方），不加 key。`ModuleMapDriftE2ETest` 已恢复全绿（259 用例全过）。**治本（未做）**：见下。
- **修复建议**（三选一，推荐 1+2）：
  1. `scaffoldToDir` 注册模块时**同时**用 `coding.replace` 语义更新 `ModuleMap.MODULES`（或返回提示让代理执行）；
  2. `RepoConsistencyCheck` 增加 ModuleMap↔pom 双向核对（复用 `ModuleGraph`），漂移即报 ERROR；
  3. `devTask` 计划中，凡涉及 `browser4-plugins/` 新模块的任务强制插入 ModuleMap 同步步骤。

### 3.3 validate 通过 ≠ 功能完整（语义校验缺失）

- **证据**：`getPageInfo.js` 直到第 9 轮仍是 scaffold 桩（只返回 `{url, data:{}}`），期间 `code validate plugin` 全部通过（校验器只查文件存在性/结构，不查内容语义）；是人工监督审查发现功能缺失。
- **修复建议**：
  1. `validatePlugin` 对 scaffold 生成的资源做"未实现标记"检查（如 JS 含 `TODO`/`// TODO: implement` 时给 WARNING）；
  2. 对工具方法有明确语义声明的（如 `toolMethod=getPageInfo` 且返回字段已定义），增加**字段存在性检查**（JS 返回对象键与工具描述比对）——轻量正则即可；
  3. 中期：插件 validate 增加可选"冒烟执行"（`tab.eval` 跑 JS 验证可解析）。

---

## 4. P2 — 小问题

| # | 问题 | 位置 | 建议 |
|---|---|---|---|
| 4.1 | `agent run --wait` 不可达：`handle_agent_run` 读 `tool_params["wait"]`，但 `agent-run` 的 `CommandDef.options` 为空，CLI 报 `Missing required argument` | `main.rs:9589+` / `commands.rs:3007-3019` | 给 `agent-run` 声明 `--wait` 选项并传入 tool_params |
| 4.2 | `code changes` 快照含无关操作（`server-hold5.log` 等删除记录，非本会话产生） | `CodingAgentFileSystem` change 追踪 | 追踪按会话/调用方隔离，或过滤非 workspace 根下的杂音 |
| 4.3 | 代理自毁序列："写新内容到旧文件名" + "删除旧文件" = 数据丢失（第 5 轮），且恢复时不使用 `coding.revert`/`changeSummary` 审计 | agent 行为 | 提示词增加"先建新文件并验证存在，再删旧文件"护栏；监督工具侧可在 delete 前检测同名写历史并提示 |

---

## 5. 修复路线图

| 阶段 | 内容 | 工作量 | 状态 |
|---|---|---|---|
| **P0 冲刺** | 修 1.1 glob 基座算法、1.2 根级文件匹配、1.3 listDir 截断提示；各配单元测试 | 0.5 天 | ✅ 已完成（2026-08-19） |
| **P1-1 代理可靠性（核心）** | 2.2 noop 语义修复 + 页面状态计数回传 + `--noop-limit` + FINISH 提示词 + 2.3 image 降级前置 + 2.1 coding 模式（方案 A） | 1 天 | ✅ 已完成（2026-08-20）；剩方案 B（AiServices 编码 agent）为可选后续 |
| **P1-2 计划与治理** | 3.1 DevTaskPlanner 修复、3.2 ModuleMap 自动同步+一致性校验、3.3 validate 语义检查 | 1 天 | ✅ 已完成（2026-08-20） |
| **P2 打磨** | 4.1 `--wait`（含终态识别修复）、4.2 changes 24h 窗口过滤、4.3 自毁护栏提示词 | 0.5 天 | ✅ 已完成（2026-08-20） |

**回归测试基线**：`browser4-coding` 全量测试（273 例，含 `CodingAgentFileSystemGlobListTest` 9 例、`DevTaskPlannerTest` 14 例）、`ToolSpecificationTest`、`CodingTaskDetectorTest`、`StatefulAgentRunnerTest`、`ModuleMapDriftE2ETest`；端到端：新会话编码任务 2 次 2-3 步通过、`code devtask --verify` 全绿、glob/list/validate CLI 实测通过。

**残余项（低优先级）**：changes 完全按会话隔离（现为 24h 窗口）、AiServices 编码 agent（方案 B）、TOOL_CALLING 补全、完整插件开发任务（>30 步）的 b4 回归观察。

---

## 附录 A：对 b4 有效的任务配方（本次会话验证）

以下干预手段显著提升 b4 完成率（第 8/9 轮一次通过，7-20 步完成）：

1. **任务切小**：单轮只做一件事（修 1 行 / 写 1 个文件 / 跑 1 个校验）；
2. **API 签名直接内置**：`handlers.onDocumentSteady.addLast { page, driver -> ... }` 写进任务文本，省去代理 grep/read 研读（第 7 轮写入提前到 10 步）；
3. **禁破坏性操作**：明确"禁止删除/移动/复制文件"，避免写旧删旧自毁；
4. **禁缺陷工具**：明确"不要使用 listDir/glob"（P0 修复后**已移除该禁令**，glob/list 现已可靠）；
5. **禁参考文件读取**：限定"只读自己的 scaffold 文件"，防上下文膨胀；
6. **质量门槛显式化**：mvn 命令、validate 命令直接给全参数。

---

*本文档配套监督记录：`docs-dev/copilot/supervision-timeline-pagetitle.md`（9 轮执行时间线、缺陷证据、最终验证结果）与 `docs-dev/copilot/plugin-dev-task-pagetitle.md`（任务定义）。*
