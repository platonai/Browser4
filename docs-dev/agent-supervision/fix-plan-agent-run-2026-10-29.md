# `agent run` 修复方案 v2（2026-10-29 整理）

> 状态：**待实施**（P0 前置项未完成）
> 来源：[`agent-run-supervision-2026-10-28-4.15-feat.md`](agent-run-supervision-2026-10-28-4.15-feat.md)、
> [`fix-plan-agent-run-2026-10-28.md`](fix-plan-agent-run-2026-10-28.md) 的 §1–§13、§14（审查）、§15（复审）。
> 关系：**本文件是实施依据**；10-28 的原计划保留为历史记录。原计划中与当前源码不符的
> 前提已在本文件修正（见附录 A）。
>
> 变更记录：
> - **v2（2026-10-29）**：整合审查/复审；把 F2 拆成“结果分类 / cwd+超时”，F4 拆成
>   “预算可见性 / 失败语义 / 归因”；修正 F3 检测层、F4 失败产物断言、F1 测试断言、
>   F8 `goto --load` 前提；重写 Gate 为默认配置双运行 + 数据语义校验。
>
> 阅读顺序：§0 → §1 → §2.1 总览 → 选工作包 → §3/§4/§5；附录 A–D 供追溯。

## 0. 一页结论

### 0.1 目标

让“亚马逊微波炉 Top20 × 75 字段”这类任务在**默认配置**下稳定完成，并让失败运行
**不再说谎**：

- 产物写得出：20 行 × 75 列，关键列非空且语义正确；
- 失败看得见：工具失败显示 `[fail]`，`agent result` 非零退出，`filesChanged` 只属于本次运行；
- 成本看得见：`--follow` 显示累计 token / 有效预算，80% 有告警；
- 隔离可验证：默认每个任务一个 workspace，旧任务文件不进入本次产物。

### 0.2 基线（2026-10-28 运行 `02807b73`）

| 项 | 值 |
|---|---|
| 结果 | `failed (417)`，`TokenBudgetExceededException` |
| 时长 | 925.4s（15m25s） |
| Token | 5,006,041（输入 4,931,514 + 输出 74,527） |
| LLM 请求 | 224 |
| 工具调用 | 248（`coding_shell` 93、`coding_write` 61、`b4_run` 39、`b4_wait` 10…） |
| 新交付物 | **0**；58 个 `.b4scratch` 脚本 |
| `filesChanged` | 旧 CSV（mtime 22:07）+ 另一个任务的 `msedge-*.md`（21:29），均早于本次运行（22:43） |
| `agent result` | `status=failed` 仍 **exit 0** |
| 单次输入峰值 | 38,472 |

### 0.3 根因（按影响排序）

| ID | 现象 | 已核对位置 | 影响 |
|---|---|---|---|
| R1 | `system.skillDoc("SKILL.md")` 59,786 字符被截成 1,290 | `ToolOutcome.kt:84,107-108` 的 key 是 `domain.method`，预算表只有 method | 参考文档索引不可达，模型按旧习惯操作 |
| R2 | `b4.run` 对 CLI `status:error` 仍 `[ok]`；`b4.wait` 对 `state=FAILED` 仍 `[ok]` | `B4CliToolExecutor.kt:147-207`；`ToolOutcome.kt:105` 只看 `evaluate.success` | 失败被重复 4 次，长任务拆批 |
| R3 | `b4.run` 的相对路径在**旧 agent 的 workspace** 里解析 | `AgentToolManager.kt:345-351` 已传 `defaultWorkingDir = root`；现场 cwd 是另一个 agent 的 `862d28c5` | 模型以为路径错，实际是绑定错 |
| R4 | async `eval` 未加 `--await` 返回 `{}`，不报错 | CLI 只收到文本；`AbstractToolExecutor.kt:118-127` 已丢弃 `cdpSubtype` | 20 页批量一次丢光 |
| R5 | 预算/usage 不可见；无任务级覆盖 | `AgentRunEvent.kt:30-41` 只有单请求 token；`RobustBrowserAgent.kt:990` 创建预算 | 5M 中止前无预警 |
| R6 | 失败 `filesChanged` 混入旧文件；失败仍 exit 0 | `StatefulAgentRunner.kt:756-768`、`CommandStatus.kt:330-337`、`main.rs:15878` | 结果不可信，产物无法归因 |
| R7 | Windows 编码丢数据；`set/if/echo./for` 被拒 | `CliProcessManager.kt:410-421`；`CodingAgentShell.kt:202-214` | CSV/JSON 乱码，模型绕路 |
| R8 | 数据 Gate 只查非空 | 原计划 11.2 校验脚本 | `category` 语义错、bullet 重复、旧榜单仍能“通过” |

### 0.4 保留的决策

1. **默认 per-task workspace**；`--resume` 复用 checkpoint workspace；
   `--shared-workspace` 显式回到旧行为。
2. **任务级预算不进 process-wide registry**；用 per-run override，结束后清理。
3. **暂不无脑放行 `if`/`for`**；先拒绝 + 可操作提示，解析嵌套命令后再考虑。
4. **不引入 Excel 运行时依赖**；xlsx 只作为输入，输出 CSV + `problems` 记录降级。
5. **失败运行保留已写产物**，但必须只归因到本次 task（这是 v2 对原计划的关键修正）。

### 0.5 非目标

- 不把默认预算直接改成“无限大”来绕过问题。
- 不自动 `await` 所有 Promise；只把 Promise 变成明确错误/提示。
- 不为了 Gate 通过而使用 `config set` 或 `--fresh-workspace` 绕过默认路径。
- 不在本阶段改 `goto` 的全局导航默认等待策略（没有真实页面 e2e 前只改文档/推荐用法）。

### 0.6 立即缓解（不改代码，不替代 Gate）

在修复落地前，如果必须先让下一次运行有机会完成：

```powershell
pwsh ./b4w.ps1 config set agent.token.budget.total 20000000
pwsh ./b4w.ps1 agent run "<原任务>" --follow --fresh-workspace
```

这只绕开 5M 上限和脏 workspace，**不能**解决 skillDoc 截断、`b4.run` 假成功、
失败归因错误；它只用于保留产出/收集证据，Gate 仍按 §1.1 的默认配置运行。

## 1. 验收标准（Definition of Done）

### 1.1 两次 Gate 运行

| 运行 | 命令形态 | 目的 |
|---|---|---|
| **A. 默认配置、human** | `agent run "<原任务>" --follow` | 证明默认预算、默认 workspace、人类可读反馈 |
| **B. 默认配置、NDJSON** | `agent run "<原任务>" --follow --ndjson` | 机器校验 token/预算/事件/结果 |

前置条件：

- 后端从**本工作树**构建/启动；先记录 jar 的 build stamp 与 `git rev-parse HEAD`（D14）。
  若运行的是其他 worktree 的 dev bundle，Gate 无效，先重启。
- Run A/B **不得**执行 `config set agent.token.budget.total`，**不得**带
  `--fresh-workspace`/`--shared-workspace`。
- 两次运行使用不同 task id；保留 `run-*.log`、`events-*.jsonl`、`result*.json`。

### 1.2 机器校验清单

| 维度 | 断言 | 取证方式 |
|---|---|---|
| 产品行 | 20 行数据（+1 表头），75 列 | CSV 读取 |
| 表头 | 与 xlsx 第 1 行字段名逐一相等 | Python/openpyxl 读取 fixture |
| ASIN | 20 个唯一、格式合法（如 `B0…`） | CSV 校验 |
| 榜单一致性 | ASIN 集合 == **本次运行捕获的** bestseller 列表 | run log / `.b4scratch` 里的列表文件；不要事后重抓 |
| `keywords` | 20/20 非空 | CSV 校验 |
| `category` | 属于字段规格允许的品类 ID 集合，不是品类名 | 规格映射（F8 修复后） |
| `price` | 可解析为货币数值，非空 | 正则/Decimal |
| bullets | `overviewbullets`/`featurebullets` 无重复项 | 拆分/去重比较 |
| 编码 | 无 `鈥`、无 `U+FFFD`、无 `It鈥檚`/`d茅cor` | 全文扫描 |
| 新鲜度 | `task_time` 落在本次运行窗口内 | 时间比较 |
| 归因 | `agent result --json` 的 `filesChanged` ⊆ 本次 task 写入；旧 CSV/`msedge-*.md` 不出现 | `WorkspaceManifest`/文件 mtime |
| workspace | `workspace` 路径含本次 task id；旧任务文件不在其中 | `agent result` + `listDir` |
| 成本 | total ≤ 5M（默认预算内完成）或已有**明确决策**调整默认值；峰值输入 ≤ 30k | `run.usage` 累加 / `cli-usage.jsonl` |
| 可观测 | human 至少一条 `tokens x / y (%)`；80% 只告警一次；abort 有明确行 | `--follow` 日志 |
| 机器可读 | NDJSON `run.usage` 含 `effectiveTokenBudget` + `cumulativeTokens`；`task.failed` 含 `errorCode`/`resumable` | NDJSON 逐行 JSON 校验 |
| 结束 | `status=done`，`system.taskComplete` 被调用，`filesChanged` 非空（本次产物） | `agent result --json` |

### 1.3 失败语义（负向验收）

- `--token-budget 20000` 跑任务：80% 告警、abort、`agent result` **exit ≠ 0**、
  `filesChanged` 为空或只含本次已写文件；`--allow-failed` 时 exit 0。
- `b4.run("eval --file .b4scratch/missing.js")`：结果含 `[fail]` 与
  “相对路径/workingDir”提示，不再 `[ok]`。
- `b4.run("eval --await --file big.js", timeoutSeconds=300)`：20 页 eval 不在 30s 被掐；
  若仍超时，错误里明确“分批或提高 MCP 超时”。
- `coding.shell("if exist ...")`：返回可读 hint（python/.cmd 替代），不执行嵌套命令。
- `eval "(async()=>1)()"` 不带 `--await`：明确错误；带 `--await` 返回 `1`。

### 1.4 明确不算通过

- 用 `config set ... 10000000` 跑通；
- 只用 `--fresh-workspace` 跑通（没有验证默认 per-task workspace）；
- 只跑 `--ndjson`（没有 human usage 验证），或反过来；
- 使用旧 bundle（D14 未确认）；
- 数据只满足“非空”，`category`/bullets/榜单未校验；
- `filesChanged` 含本次未写的旧文件。

## 2. 工作包

### 2.1 总览

| ID | 旧编号 | 优先级 | 范围 | 依赖 | 建议 PR |
|---|---|---|---|---|---|
| P0-1 | F1 | P0 | `ToolOutcome` 预算键 + `skillDoc` 分页/索引 | 无 | PR-1 |
| P0-2 | F2a | P0 | `b4.run/status/wait` 统一结果分类 | 无 | PR-1 |
| P0-3 | F2b | P0 | `b4` cwd/workspace 绑定 + MCP 超时 | D14 取证 | PR-2 |
| P0-4 | F3 | P0 | eval Promise/undefined 后端检测 | 无 | PR-1 |
| P0-5 | F4a | P0 | 有效预算字段 + `--token-budget` + human/NDJSON usage | P0-1 可选 | PR-1 |
| P0-6 | F4b/F6 | P0 | 失败结果语义：`errorCode`/`resumable`/exit code | P0-5 | PR-1 |
| P1-1 | F5 | P1 | 默认 per-task workspace + `--shared-workspace` | 无 | PR-2 |
| P1-2 | F5/F6 | P1 | 失败产物归因（保留本次、排除旧任务） | P1-1/P0-6 | PR-2 |
| P1-3 | F7 | P1 | Windows 编码 + cmd 内建命令 | 无 | PR-3 |
| P2-1 | F8 | P2 | 输入保真 / 字段规格 / listDir / memory / goto 文档 | P1-1 | PR-4 |
| P2-2 | F9 | P2 | 文档、help、tips 同步 | P0–P2-1 | PR-4 |
| GATE | F10 | — | 真实运行 + 负向 + 数据校验 | 全部 | PR-4 后 |

### 2.2 P0-1：`system.skillDoc` 可读 + 预算正确（旧 F1）

**问题**

- `ToolOutcome.from` 用 `"$domain.$method"` 查 `BODY_BUDGETS`（`ToolOutcome.kt:84`），
  但表的键是 method-only（`52-61`），所以 `system.skillDoc` 落到
  `DEFAULT_BODY_LEN = 1_200`（`107-108`）。
- `SystemToolExecutor.skillDoc(name)` 返回全文 59,786 字符（`65-70`），没有分页；
  `ToolLoopCompressor` 的 `protectedToolNames` 保护发生在更晚，救不了截断。

**改动**

1. `ToolOutcome.kt`：
   - 用 `BODY_BUDGETS[resolvedMethod]` 查表；
   - 新增 `"skillDoc" to 8_000`；
   - 截断 marker 带续读提示：`…[truncated N chars; call system.skillDoc(name, offset=K)]…`。
2. `SystemToolExecutor.kt`：
   - `skillDoc(name: String, offset: Int = 0, limit: Int = 8_000): String`；
   - 按 **Unicode code point** 切页；返回头 `offset/nextOffset/totalLength`；
   - `limit` 上限 16_000；`MAX_SKILL_DOC_CHARS` 仍作总上限；
   - `SKILL.md` 无 `offset` 时默认返回“章节标题 + 参考链接索引”（≤2 KB），
     含每个 heading/link 的 offset；
   - tool spec 增加 `offset`/`limit` 参数与描述。
3. `CliAgentPrompt.kt` / `RobustBrowserAgent.kt` 的 system prompt：
   - 改为“先读 reference map，再按需加载 topic doc”，给
     `system.skillDoc("bulk-collection.md")` 示例；不要无条件拉 59 KB。

**测试**

- `ToolOutcomeTest`：`coding.shell` body > 1,200；`system.skillDoc` body ≤ 8,000；
  marker 含 `offset=`。
- `SystemToolExecutorTest`：`SKILL.md` 默认索引含 `bulk-collection.md`/
  `agent-workspace.md`/`htmlsnapshot.md`；`offset=8000` 与第一页不重叠，
  `nextOffset` 正确；未知文档返回可用列表；`limit=16_000` 的 **executor 原始结果**
  可达 16,000（注意：经 `ToolOutcome` 后仍 ≤8,000，两者分开断言）。

**验收**

模型可见结果包含参考链接索引且 ≤8 KB；按 offset 能读到 `## 7 Reference Map` 正文；
不再出现 `[truncated 58125 chars]` 作为唯一内容。

### 2.3 P0-2：`b4.run/status/wait` 统一结果分类（旧 F2a）

**问题**

- CLI 输出 `{"status":"error", ...}` 时，`b4.run` 仍是 `[ok]`；非零 exit code 只以
  `[exit code: N]` 文本出现，未映射为失败（`CliModels.kt:64-74`）。
- `b4.wait` 对 `state=FAILED` 只拼文本（`B4CliToolExecutor.kt:179-207`），
  `ToolOutcome.ok` 仍为 `true`。本次证据 seq 150/166/464。
- `b4.status` 同样只拼文本。

**改动**

1. 抽 `classifyCliResult(stdout, exitCode, jobState)`，`run/status/wait` 共用：
   - `status:error` → 失败；
   - `exitCode != 0` → 失败；
   - `state in {FAILED, CANCELLED}` → 失败；
   - `state=RUNNING` + job handle → 正常“仍在运行”，不是失败。
2. 失败通过**不可重试的专用异常**或 `ToolOutcome` 失败路径返回，使
   `evaluate.success=false` / `ok=false`；错误文本保留 CLI 原始 `error.message`
   与提示（相对路径、分批、crawl）。
3. `b4.run` 的 tool description 明确：CLI 退出码、JSON `status:error`、
   job 不会把失败伪装成成功。

**测试**

- `B4CliToolExecutorTest`：stdout `{"status":"error"}` → `ok=false`，文本含
  `Eval file ... not found`；非零 exit → `ok=false`。
- `B4CliToolExecutorJobTest`：长命令升级 job 后 `wait`/`status` 的 FAILED →
  `ok=false`；RUNNING 仍正常。
- e2e：`b4.run("eval --file .b4scratch/missing.js")` 在 `--follow` 中显示 `[fail]`。

**验收**

本次运行的 5 次 `status:error`（含 30s 超时）全部以 `[fail]` 出现；模型不再重复
同一个相对路径错误 4 次。

### 2.4 P0-3：`b4` cwd/workspace 绑定 + MCP 超时（旧 F2b）

**前提修正**

原计划写“`b4.run` 的 `workingDir` 默认 null”。当前 HEAD **不是**这样：
`AgentToolManager.buildB4CliExecutor(root)` 已传 `defaultWorkingDir = root`
（`AgentToolManager.kt:345-351`），`root` 来自 `BasicBrowserAgent.workspaceRoot`
（`BasicBrowserAgent.kt:51,66-68`）。真正的问题是**现场 cwd 与 coding 工具的
workspace 不一致**：

- `coding.workspaceRoot` → `.../e1676135-.../workspace`（trace seq 23）；
- `b4.run` 解析 `.b4scratch/ev.js` 时尝试
  `.../862d28c5-.../workspace/.b4scratch/ev.js`（另一个更早 agent 的 workspace）。

**改动**

1. **先取证**：按 D14 记录后端 bundle 来源；在 HEAD 上复现。
2. 定位绑定关系。候选原因（按优先级）：
   - `AgentToolManager._concreteExecutors` 是 `by lazy`，首次初始化捕获
     `b4CliExecutor`；`rebindWorkspace` 只替换属性（`359-365`），未刷新已初始化的
     映射；
   - 旧 agent/tool manager 被复用，`workspaceRoot` 与 `defaultWorkingDir` 生命周期
     不一致；
   - 现场 bundle 不是 HEAD。
3. 修复后加不变量：`b4.run` 的 cwd == 当前 `coding.workspaceRoot`，
   rebind 前后一致；在 trace 中记录实际 cwd。
4. **超时**：`timeoutSeconds > 30` 时，在 CLI 命令前注入全局
   `--timeout <s>`（若 `args` 已含 `--timeout` 则不注入）。注意外层
   `CliProcessManager` 的超时要留出 grace（例如 `timeoutSeconds + 60`），否则
   CLI 的 300s HTTP 超时会被子进程 300s 杀死，形成竞态。
5. `b4.run` tool description 写明 cwd、`--timeout` 与 MCP 30s 的区别、相对路径建议。

**测试**

- 回归：同一 agent 在 rebind 前后、连续两个任务中，`b4.run("eval --file .b4scratch/x.js")`
  的相对路径必须落在 `coding.workspaceRoot`。
- `b4.run("version")` 的 cwd 记录正确。
- 单测：`timeoutSeconds=120` → argv 含 `--timeout 120`；已显式 `--timeout` 不重复；
  外层超时有 grace。
- 负向：>30s 的 eval 不被 30s HTTP 默认值掐断；如仍超时，错误明确。

**验收**

相对 `--file` 不再需要模型自己发现并重写；20 页 batch 不会被 30s 切成 5 批，或错误里
明确“请分批/提高 MCP 超时”。

### 2.5 P0-4：eval 的 Promise/undefined 反馈放到后端（旧 F3）

**问题**

- CLI 只收到文本；`cdpSubtype` 在 `AbstractToolExecutor.kt:118-127` 已被折叠，
  `main.rs:9615-9622` 只是 `eval_result_to_typed_json`。
- `ToolResultTextRenderer.kt:34-40` 把 JS `undefined` 渲染为字面量 `"null"`
  （不是空字符串）；Promise 对象则可能序列化为 `{}`。
- 因此“Rust 单测 mock CDP result”测的是不存在的接口。

**改动**

1. 在仍能看到 `JsEvaluation.cdpType/cdpSubtype` 的层检测：
   - `cdpSubtype == "promise"` 且未 `--await` → 失败/明确错误
     `result is a Promise; re-run with --await`；
   - `cdpType == "undefined"` → 成功结果带 warning（或明确失败，按工具语义定），
     不再只给 `null`；
   - `--await` 行为不变。
2. 推荐位置：`AbstractToolExecutor.callFunctionOn(Tc, ...)` 或 driver 的 eval 包装层；
   若要在 CLI 展示结构化 warning，则扩展 MCP 响应的 `resultType` 元数据，由 CLI 消费。
3. `help.rs`/`references/eval.md`/`commands.rs`：把 `--await` 放到 eval 第一条注意事项。

**测试**

- 后端单测：`JsEvaluation(value=null, cdpType="undefined")` → 明确提示；
  `cdpSubtype="promise"` → 失败；正常值不变。
- CLI e2e：`eval "(async()=>1)()"` 无 `--await` → 失败文本；带 `--await` → `1`；
  `eval "(()=>{})()"` → 明确 undefined 提示。
- 不再写“CLI mock CDP subtype”的 Rust 单测。

**验收**

模型不需要先 `b4_help("eval")` 才知道 `--await`；本次 trace 中的 `{}`（至少
seq 243/414/483 三处）至少一处变成可操作错误，且真实对象 `{}` 不误报。

### 2.6 P0-5：有效预算 + `--token-budget` + `--follow` usage（旧 F4a）

**问题**

- `AgentRunEvent.kt:30-41` 只有单请求 token；CLI `TaskEvent` 无法算百分比。
- `AgentTokenBudget.effectiveMaxTotalTokens` 读 process-wide `RuntimeConfigRegistry`
  （`TokenBudget.kt:50-51`），不能用于任务级覆盖。
- `AgentTokenBudget` 在 `RobustBrowserAgent.kt:990` 创建，`buildCliToolLoop`
  （`1029-1035`）只是接收；原计划“在 buildCliToolLoop 内构造”需修正。

**改动**

1. `AgentRunEvent` 增加 `budgetTokens`/`cumulativeTokens`（或 `effectiveTokenBudget`）；
   `emitUsageEvent`（`RobustBrowserAgent.kt:1510-1523`）传入本任务 `AgentTokenBudget`。
2. `CommandStatus`/`CommandResult` 也带 `effectiveTokenBudget`（或 status payload 字段）。
3. CLI：
   - `agent-run` 增加 `--token-budget <n>`；`build_agent_run_extra` 加 `tokenBudget`；
   - `CommandToolExecutor` 的 allowed 参数加 `tokenBudget` → `UserCommandExecutor` →
     `StatefulAgentRunner`；
   - `RobustBrowserAgent` 增加 `@Volatile var tokenBudgetOverride: Long?`，
     `990` 行优先用它构造 `AgentTokenBudget`；`finally` 清除；
   - **不要**把任务级值写进 `RuntimeConfigRegistry`。
4. `--follow` human renderer：累计并在每 10 次请求或 15s 打一行
   `tokens 1.24M / 5.0M (25%) · 37 requests`；80% 只打一次 `⚠`；100% 打 abort 行。
5. NDJSON：`run.usage` 带 `effectiveTokenBudget`/`cumulativeTokens`；
   `agent_interactive.rs` 的 `TaskEvent`/`RunStats` 解析；不混 prose。

**测试**

- 三种来源各一条：默认 5M、全局 `config set 9000000`、任务级 `--token-budget 2000000`；
- 连续两个任务：第二个回到默认（override 已清理）；
- `--token-budget 0` = unlimited（与 `AgentTokenBudget` 的 0/负值语义一致）且不误 abort；
- human 行出现、80% 只一次；NDJSON 字段可解析。

**验收**

下一次真实运行到 80% 时，监督者能在 `--follow` 里看到；abort 行含已用/预算。

### 2.7 P0-6：失败结果语义（旧 F4b/F6）

**问题**

- `agent result`（`main.rs:15878`）解析 payload 后总是 `Ok(())`，失败/取消也 exit 0。
- `CommandResult` 没有 `errorCode`/`resumable`；人类输出仍打印“Files the agent reported changing”，
  可能是旧文件。

**改动**

1. `CommandResult` 增加 `errorCode: String?`（`TOKEN_BUDGET`/`NOOP_LIMIT`/`CANCELLED`/
   `TOOL_ERROR`）与 `resumable: Boolean?`。
2. `CommandStatus.toCommandStatus` 按失败原因填；`statusCode=417` 与 `errorCode` 一致。
3. `handle_agent_result`：解析 envelope 的 `status`；`failed`/`cancelled` → `Err`（exit 1）；
   增加 `--allow-failed` 逃生口；`agent status` 不变。
4. 人类输出：
   `✖ failed (budget) · workspace: … · files: (none) · resume: agent run --resume <id>`；
   不再只打印旧 `filesChanged`。
5. budget abort 的 `summary` 保留“已写文件 + 可 resume”，不要只放异常文本。

**测试**

- CLI 单测：`status=failed` → 非零；`--allow-failed` → 0；`status=done` → 0。
- `CommandResult`/`CommandStatus` 序列化含 `errorCode`/`resumable`。
- 负向：预算 abort → 非零、无旧文件、含 resume 提示。

### 2.8 P1-1：默认 per-task workspace（旧 F5 前半）

**问题**

- `StatefulAgentRunner` 只在 `--fresh-workspace` 时设置 `workspaceDirOverride`
  （`672-687`）；默认 `baseDir/workspace` 被同一 session 的所有任务复用。
- 文档 `agent.md:106-108`、`agent-workspace.md:39-48` 已承诺“每个任务独立”。

**改动**

1. `StatefulAgentRunner.executeSerialized`：
   - `--resume` → checkpoint workspace（现状）；
   - 否则默认 `baseDir.resolve("workspace.${status.id}")`（或 `workspace/<taskId>`），
     设置 `workspaceDirOverride` + `rebindWorkspace`；
   - `status.workspace` 在 `executeAgentCommand` 读取 `agent.workspaceRoot`（`884`），
     override 必须先设置；
   - 新增 `--shared-workspace` 回到 `baseDir/workspace`；
     `--fresh-workspace` 保留为兼容开关（等同默认 per-task，不再需要
     `workspace.fresh.*` 旧路径）；`--resume` 仍按 checkpoint 的 workspace
     （包括历史 `workspace.fresh.*`）解析。
2. `finally` 重置 override 并 `rebindWorkspace(default)`（`799-807`）。
3. `materializeTaskInputs`（`RobustBrowserAgent.kt:958`）在 rebind 之后执行，
   保证 `.b4inputs` 落在本次 workspace；当前顺序已正确，补测试即可。
4. 文档：`agent.md`/`agent-workspace.md` 改“每个任务一个 workspace；`--resume` 复用；
   `--shared-workspace` 显式共享”。

**测试**

- 同一 session 连续两个任务 → workspace 不同；B 看不到 A 的文件；
- 每个任务都有自己的 `.b4inputs`；外部输入复制到本任务；
- `--resume` 复用 checkpoint workspace；
- `--shared-workspace` 保持旧路径。

**验收**

workspace 名含 task id；`coding_listDir` 不再看到 125 个历史脚本；旧 CSV 不在新 workspace。

### 2.9 P1-2：失败产物归因（旧 F5 后半 / F6）

**问题**

- `deliverableFilesOf(workspace)`（`AgentResume.kt:102-116`）扫描顶层全部普通文件，
  于是旧 CSV + 别的任务报告被算成本次 `filesChanged`。
- 但当前 HEAD（`33be64`）**有意保留 abort 产物**：`StatefulAgentRunner.kt:756-768`
  设置 `deliverables`，`CommandStatus.kt:330-337` 在 `filesChanged` 为空时回退。
  因此“失败 → `filesChanged` 为空”是**错误目标**，会回归“完整 CSV 被报成裸失败”的修复。

**改动**

1. `deliverableFilesOf(workspace, startedAt, mutations)`：
   - 优先用 `WorkspaceManifestStore` 的 `currentTask`/`lastTask` 的
     `TaskSnapshot.filesChanged`（交付物集合）；需要逐条明细时用
     `CodingAgentFileSystem.mutationsSnapshot()`；
   - 退回 `mtime >= startedAt`；
   - 排除 `.b4scratch/`、`.b4inputs/`、`.browser4*`、dotfiles；
   - 保留本次已写出的部分交付物。
2. `StatefulAgentRunner` 的 catch/cancel 传 `status.startedTime` 与本次
   `mutationsSnapshot()`；`CommandStatus` 的 fallback 使用同一过滤结果。
3. 文档：失败运行也会列“本次已写产物”；旧任务文件永不进入。

**测试**

- `AgentResumeTest`：workspace 放新/旧文件 → 只返回新的；`.b4scratch` 排除。
- `StatefulAgentRunnerTest`：预算 abort + 已写 CSV → `filesChanged` 含 CSV，
  不含旧 `msedge-*.md`；无写入时为空。
- `CommandStatus`：失败 + 旧文件 → 只含本次。

**验收**

旧 CSV/msedge 报告不再出现；部分完成的 CSV 被保留并标注为本次产物。

### 2.10 P1-3：Windows 编码 + cmd 内建命令（旧 F7）

**问题**

- `CliProcessManager.kt:410-421` 只给 Windows PowerShell 5.1 注入
  `[Console]::OutputEncoding`，`pwsh` 分支 preamble 为空；`b4.run` 的 `> file`
  经子 pwsh 用 cp936 解码 UTF-8，出现 `It鈥檚`/`12.8鈥?`。
- `CodingAgentShell.kt:202-214` 的 `SAFE_COMMANDS` 不含 `set`；`echo.` 不是 `echo`；
  `if`/`for` 被当作未知命令拒绝，模型没有干净替代。

**改动**

1. `CliProcessManager.buildShellInvocation`：`pwsh` 与 `powershell` 都注入
   `[Console]::OutputEncoding=[Text.Encoding]::UTF8;`（必要时 `$OutputEncoding`/
   `chcp 65001`），并加 `try { … } catch { }` 兜底；注释说明 `b4.run` 的 `> file` 走这层。
2. `CodingAgentShell`：
   - `echo.` → 归一化为 `echo`；
   - `set`（无参数或 `set VAR=value`）加入 Windows 内建；
   - `if`/`for` **不简单放行**：返回 hint（“use python -c … or a .cmd file”），
     因为 `SAFE_COMMANDS` 只检查首 token，嵌套命令会绕过 workspace 边界。
3. 可选：CLI `eval --out <file>`/`htmlsnapshot export` 替代 `> file`，从根上绕过编码。

**测试**

- Windows：输出 `It’s`/`décor`/中文的命令，落盘/返回文本无 `鈥`/U+FFFD。
- `set PYTHONIOENCODING=utf-8` 通过；`echo.` 通过；`if exist` 返回 hint；
  `if exist C:\Windows\...` 不绕过边界。
- CI：Windows job 覆盖（至少 unit/集成层）。

### 2.11 P2-1：输入保真 / 字段规格 / listDir / memory / goto 文档（旧 F8）

1. **额外 positional**
   - `commands.rs` 的 `CommandDef` 增加 `reject_extra_positionals`（默认 false）；
     `agent-run` 设 true；`main.rs` 的 `build_command_args` 超出时报
     `unexpected positional arguments: ["。"]`。
   - 测试：`agent run "task" extra` → 错误；`loop` 等仍允许拼接。
2. **字段规格 xlsx**
   - 修复 `asin_field_description.xlsx` 第 2 行中文（当前 `sharedStrings.xml` 是 U+FFFD）；
     无法恢复时写英文 description（75 行）。
   - fixture 测试：`sharedStrings` 不含 U+FFFD；表头 75 个名字与代码常量一致。
   - 可选：提供 CSV 版本，避免二进制读取。
3. **`coding.listDir`**
   - `ToolSpec`（`CodingToolExecutor.kt:397-401`）增加 `summary`/`limit`；
     默认 top 50 + 总数 + 扩展名汇总；`maxDepth` 保留。
   - 测试：125 个条目 → 输出含 `125 entries` 且正文不超预算。
4. **memory 来源**
   - `memory_search` 结果附 `taskId`/`createdAt`/`workspace`；
     prompt 明示“历史运行的产物，不是本次结果”。
   - “今天”类任务要求重新拉 bestseller 列表并比对 ASIN 集合。
5. **`goto`/批量（修正原计划）**
   - `goto` **没有** `--load` 选项（`commands.rs:1346-1360`）；`--load` 是 `wait`
     的选项（`1875-1896`），`navigate` 只接受 `url/rawUrl/pageUrl`
     （`BrowserTabToolExecutor.kt:373-381`）。
   - 因此本阶段**只在文档中**推荐批量采集用 `crawl`/`eval --await`，或显式
     `wait --load domcontentloaded`；若要让 `goto` 支持等待策略，作为独立变更
     并配真实页面 e2e，不在本计划静默改全局导航语义。
6. **`progress.txt` resident 契约（S11）**
   - 把“每采一项 append `progress.txt`”写进 resident prompt（不能只放在被截断的
     `bulk-collection.md`），并在 `coding.listDir` 汇总中提示该文件缺失。

### 2.12 P2-2：文档、help、tips（旧 F9）

按仓库 Documentation Update Rule 更新：

- `skills/browser4-cli/references/agent.md`：workspace 语义、`--token-budget`、
  `--shared-workspace`、失败 `agent result` 退出码、`--follow` usage 行、`--allow-failed`。
- `skills/browser4-cli/references/agent-workspace.md`：默认 per-task、`--resume`、
  `--shared-workspace`、失败运行保留本次产物。
- `skills/browser4-cli/references/config.md`：任务级预算 vs 全局 `config set`。
- `skills/browser4-cli/SKILL.md`：xlsx 输入/CSV 输出、批量采集、`b4.run` 的 cwd/超时/`--await`。
- `cli/browser4-cli/src/help.rs` / `tips.rs`：新 flag 与失败提示。
- `cli/browser4-cli/README.md`：如有 flag 列表。

### 2.13 GATE：真实运行 + 负向（旧 F10）

见 §5；验收断言见 §1.2/§1.3。

## 3. 实施顺序与 PR 切分

| 阶段 | 内容 | 说明 |
|---|---|---|
| 0. 取证 | 记录 HEAD/bundle；复现 `b4` cwd 不一致；确认 D14 | **先做**，否则 P0-3 可能是无效修改 |
| 1. 反馈真相 | P0-1、P0-2、P0-4、P0-5、P0-6 | 可并行；PR-1 |
| 2. 成本/隔离 | P0-3、P1-1、P1-2 | PR-2；依赖阶段 1 的告警/结果语义 |
| 3. Windows | P1-3 | PR-3；独立 |
| 4. 数据/文档 | P2-1、P2-2 | PR-4；依赖 P1-1 的 workspace 语义 |
| 5. Gate | §5 | 全部合入后；先 Run A 再 Run B |

依赖要点：

- P0-6 依赖 P0-5 的 `effectiveTokenBudget`/失败原因；同一 PR 内完成。
- P1-2 依赖 P1-1 的 task workspace 与 P0-6 的 `errorCode`。
- P2-1 的 `category` Gate 依赖 F8 的 xlsx 修复；否则只能做“非空 + 允许集合”的降级校验。
- Gate 必须在 P1-1 之后，且 Run A **不带** `--fresh-workspace`。

## 4. 测试计划

### 4.1 模块/单测

| 范围 | 测试 | 命令 |
|---|---|---|
| agentic 预算/错误/workspace | `ToolOutcomeTest`、`SystemToolExecutorTest`、`B4CliToolExecutorTest`、`B4CliToolExecutorJobTest` | `.\mvnw.cmd -q -pl browser4-agentic -am -Dtest=ToolOutcomeTest,SystemToolExecutorTest,B4CliToolExecutorTest,B4CliToolExecutorJobTest` |
| agent-tools workspace/归因 | `AgentResumeTest`、`StatefulAgentRunnerTest` | `.\mvnw.cmd -q -pl browser4-agent-tools -am -Dtest=AgentResumeTest,StatefulAgentRunnerTest` |
| coding shell | `CodingAgentShellTest`（及 PathGuard/Boundary） | `.\mvnw.cmd -q -pl browser4-coding -am -Dtest=CodingAgentShellTest,CodingAgentShellPathGuardTest,CodingAgentShellWorkspaceBoundaryTest` |
| rest 结果语义 | `CommandStatus*Test`、`MCPToolControllerTest` | `.\mvnw.cmd -q -pl browser4-rest -am -Dtest=CommandStatus*Test,MCPToolControllerTest` |
| CLI 单测 | `cargo test --bin browser4-cli` | `cd cli/browser4-cli; cargo test --bin browser4-cli` |

编译门：

```powershell
.\mvnw.cmd -q -D"skipTests" -pl browser4-agentic,browser4-agent-tools,browser4-coding,browser4-rest -am
```

### 4.2 集成/e2e

```powershell
# 真实后端或 mock server
cd cli/browser4-cli
cargo test --test e2e -- --nocapture --scenario=*agent*
# 新增场景按实际命名加入上面的 glob（例如把 b4 结果语义测试放进 agent 组）
```

需要覆盖：

- `--follow` 的 human 行与 `[fail]`；
- `b4.wait` job 升级失败；
- `--token-budget` 的 80%/abort/exit；
- workspace 不同任务隔离；
- `--resume`；
- Windows 编码（CI Windows job）。

### 4.3 真实任务

见 §5；产物放 `logs/supervise-20261029/`。

### 4.4 负向

见 §1.3；至少包含预算、`b4.run` 相对路径、30s 长 eval、`if exist`。

## 5. Gate 执行手册

### 5.1 取证

1. `git rev-parse HEAD`、`git status --porcelain`；
2. 记录后端 jar/bundle 的 build stamp（D14）；若是旧 worktree bundle，先用本树重启；
3. 记录 `b4w.ps1`/CLI 版本与 `browser4-cli --version`。

### 5.2 默认 human 运行

```powershell
# 不执行 config set；不带 --fresh-workspace/--shared-workspace
pwsh ./b4w.ps1 agent run "<原任务>" --follow `
  | Tee-Object logs/supervise-20261029/run-human.log
pwsh ./b4w.ps1 agent result <task-id> --json > logs/supervise-20261029/result-human.json
```

检查：

- `--follow` 至少一条 `tokens … / …`，80% 有 `⚠`；
- `status=done`，`filesChanged` 只含本次；
- 失败则 `agent result` 非零，且 `errorCode`/resume 可见。

### 5.3 NDJSON 运行

```powershell
pwsh ./b4w.ps1 agent run "<原任务>" --follow --ndjson `
  | Tee-Object logs/supervise-20261029/run.ndjson
pwsh ./b4w.ps1 agent result <task-id> --json > logs/supervise-20261029/result.json
```

校验：

- 每行都是 JSON；
- `run.usage` 含 `effectiveTokenBudget`/`cumulativeTokens`；
- `task.failed`（如有）含 `errorCode`/`resumable`；
- 不出现人类 prose 行。

### 5.4 数据校验

用 Python/openpyxl（仅验证脚本，不是运行时依赖）：

```python
# 伪代码要点
rows = list(csv.DictReader(open(csv_path, encoding="utf-8-sig")))
assert len(rows) == 20
assert len(rows[0]) == 75
assert header == xlsx_header
assert len({r["asin"] for r in rows}) == 20
assert set(r["asin"] for r in rows) == captured_bestseller_asins
assert all(r["keywords"].strip() for r in rows)
assert all(r["category"] in allowed_category_ids for r in rows)
assert all(parse_price(r["price"]) for r in rows)
assert no_duplicate_bullets(rows)
assert no_garble(open(csv_path, encoding="utf-8").read())
assert run_start <= parse_time(rows[0]["task_time"]) <= run_end
```

`captured_bestseller_asins` 必须来自**本次运行**（run log/`.b4scratch` 列表），
不允许 Gate 事后联网重抓。

### 5.5 负向

```powershell
pwsh ./b4w.ps1 agent run "<短任务>" --token-budget 20000 --follow
# 期望：80% 告警、abort、agent result 非零、filesChanged 为空或仅本次

pwsh ./b4w.ps1 agent result <id> --allow-failed
# 期望：exit 0

# 通过 agent 工具调用（或测试）：
b4.run("eval --file .b4scratch/missing.js")
# 期望：[fail] + 相对路径提示

b4.run("eval --await --file big.js", timeoutSeconds=300)
# 期望：>30s 不被掐；或错误明确要求分批/提高 MCP 超时

coding.shell("if exist C:\\Windows\\...")
# 期望：hint，不执行嵌套命令
```

### 5.6 通过判定

必须同时满足：

- §1.2 全部机器断言；
- §1.3 全部负向；
- Run A 与 Run B 都基于本工作树 bundle；
- 与基线对比表（token/时长/请求数/工具数/新产物）签字。

## 6. 风险与回滚

| 风险 | 缓解 |
|---|---|
| `skillDoc` 8 KB 仍不够，模型反复分页 | 默认返回索引 + 按需 topic；监控 `skillDoc` 调用次数；完整文档仍可分页 |
| P0-3 改错绑定层，引入跨任务污染 | 先复现 + cwd 不变量测试；失败则回退到“显式 workingDir + 文档”的保守方案 |
| eval Promise 直接报错影响合法异步用法 | 只对未 `--await` 的 Promise 报错；`--await` 不变；提供 `--await` 提示 |
| per-task workspace 改变用户预期 | 保留 `--shared-workspace`；`--resume` 复用；文档迁移说明 |
| 失败 `filesChanged` 过滤误删本次产物 | 保留本次 mutations/mtime 的文件；只排除旧任务/scratch；测试部分 CSV |
| Windows 编码修复在无控制台场景抛异常 | try/catch；设置 `$OutputEncoding`/chcp；Windows CI 覆盖 |
| `if/for` 放行造成边界绕过 | 先拒绝 + hint；解析嵌套命令后再考虑 |
| 数据 Gate 过严受榜单波动影响 | 使用本次捕获的 ASIN 列表；失败时输出差异报告，不直接判任务错 |
| 默认 5M 结构性不足 | 独立决策：要么优化到 <5M，要么有证据地调整默认值；Gate 不使用 10M 掩盖 |

## 7. 待决策（Open Decisions）

| 项 | 选项 | 截止阶段 | 默认建议 |
|---|---|---|---|
| D7 40 调用/轮上限 | 保持 / 提高 / 只改摘要 | Gate 前 | 先用现有压缩+摘要；若仍溢出再评估提高 |
| S11 `progress.txt` | resident prompt / 只文档 | P2-1 | 写进 resident prompt，并让 listDir 汇总提示 |
| D14 bundle 来源 | 本树构建 / 记录外部 bundle | 阶段 0 | 一律本树构建并记录 |
| 默认 5M 预算 | 保持 / 调整默认 | Gate 前 | 先按 §1.2 目标测；不够再走独立决策 |
| xlsx 中文说明 | 恢复中文 / 英文降级 | P2-1 | 先英文 + fixture 校验，中文可后补 |
| `goto` 等待策略 | 只文档 / 新增选项 | P2-1 | 本阶段只文档，真实页面 e2e 后再改 |
| `if/for` | 拒绝+提示 / 解析嵌套 | P2-1 | 保持拒绝+提示 |

## 8. 交付物

- [ ] PR-1（P0 反馈真相）、PR-2（成本/隔离）、PR-3（Windows）、PR-4（数据/文档）
- [ ] 每个 PR 含单测/e2e 与 CHANGELOG 条目
- [ ] 文档更新（agent.md / agent-workspace.md / config.md / SKILL.md / help/tips）
- [ ] Gate 证据：`run-human.log`、`run.ndjson`、`result*.json`、20×75 CSV、校验脚本输出
- [ ] 修复前后对比：token / 时长 / LLM 请求 / 工具调用 / 新交付物 / 失败归因
- [ ] 负向证据：预算 abort、`b4.run` 相对路径、30s 长 eval、`if exist`

## 附录 A：对原计划/审查的修正

| 旧表述 | v2 修正 | 依据 |
|---|---|---|
| F2：`b4.run` 的 `workingDir` 默认 null | HEAD 已传 `defaultWorkingDir = root`；现场是 b4 cwd 绑定了旧 agent workspace | `AgentToolManager.kt:345-351`；trace `862d28c5` vs `coding.workspaceRoot` `e1676135` |
| F3：在 CLI 检查 `subtype == "promise"` | CLI 收不到 subtype；在 `AbstractToolExecutor`/driver 层检测，或扩展 MCP 元数据 | `AbstractToolExecutor.kt:118-127`；`MCPToolController.kt:1989` |
| F3：undefined 变成空字符串 | JS undefined 渲染为 `"null"`；空字符串是 Kotlin Unit/无 className | `ToolResultTextRenderer.kt:34-40` |
| F4：失败 `filesChanged` 为空 | 保留本次已写产物，只排除旧任务/scratch；`filesChanged` 为“本次产物”而非“空” | `StatefulAgentRunner.kt:756-768`；`CommandStatus.kt:330-337`；`33be64` |
| F1：`ToolOutcome.body > 8,000` | 分成 executor 原始 16k 与 `ToolOutcome` ≤8k 两个断言 | `ToolOutcome.kt:162-171` |
| F8：`goto` 默认 `--load` | `goto` 没有 `--load`；`--load` 属于 `wait`；本阶段只改文档/推荐 `crawl` | `commands.rs:1346-1360,1875-1896` |
| 11.2：`config set 10000000` 作为 Gate 前置 | Gate 默认预算运行；10M 只作缓解/负向 | §0.1 目标；§1.4 |
| 11.2：`--ndjson` 后检查 human usage 行 | human 与 NDJSON 分两次运行 | `agent_interactive.rs:1064-1074` |
| 14.6：F4 的“为空”断言移到 F5 | 不是“为空”，而是“只含本次”；F4/F5 同一 PR 或明确依赖 | §2.9 |
| 14.9：F5 必须保证 rebind 先于输入物化 | 现有 runner 顺序已正确；改为回归测试 | `StatefulAgentRunner.kt:672-682,906`；`RobustBrowserAgent.kt:958` |

## 附录 B：审查意见闭环

| 审查项 | 结论 | 本文件处理 |
|---|---|---|
| §14.1 Gate 绕开默认配置 | 成立（验收阻断） | §1.1/§1.4/§5.2；10M 仅缓解 |
| §14.2 F3 放错层 | 成立，证据修正 | P0-4；undefined→`null`，行号改 1989 |
| §14.3 缺有效预算字段 | 成立 | P0-5；`effectiveTokenBudget` + per-run override |
| §14.4 必须覆盖 wait/status | 成立，升为 F2 验收阻断 | P0-2；seq 150/166/464 |
| §14.5 F1 断言矛盾 | 成立 | P0-1 测试分层 |
| §14.6 F4 依赖 F5 | 成立，且漏 keep-artifacts 冲突 | P1-2；改为“只含本次” |
| §14.7 数据 Gate 太弱 | 成立 | §1.2 数据清单 |
| §14.8 NDJSON/human 冲突 | 成立 | §1.1/§5.2/§5.3 分两次 |
| §14.9 D7/S11/F5 输入/goto | 部分成立 | §7、P1-1、P2-1；goto 无 `--load` |
| §15.11.1 F2 前提不成立 | 成立 | P0-3 先取证 |
| §15.11.2 Gate 仍用 `--fresh-workspace` | 成立 | §1.1/§5.2 无 flag 运行 |
| §15.11.3 F3 `{}` 需重新取证 | 成立 | P0-4 按真实类型分类 |

## 附录 C：旧编号对照

| 旧编号 | v2 工作包 | 状态 |
|---|---|---|
| F1 | P0-1 | 保留，修正测试分层 |
| F2 | P0-2 + P0-3 | 拆分结果分类 / cwd+超时 |
| F3 | P0-4 | 改到后端 |
| F4 | P0-5 + P0-6 + P1-2 | 拆分预算可见性 / 结果语义 / 归因 |
| F5 | P1-1 + P1-2 | 拆分 workspace / 产物归因 |
| F6 | P0-6 + P1-2 | 并入结果语义 |
| F7 | P1-3 | 保留 |
| F8 | P2-1 | 保留，goto 改文档 |
| F9 | P2-2 | 保留 |
| F10 | GATE | 重写，默认配置 + 双运行 + 数据语义 |

## 附录 D：证据索引

- 监督记录：`docs-dev/agent-supervision/agent-run-supervision-2026-10-28-4.15-feat.md`
- 原始计划/审查/复审：`docs-dev/agent-supervision/fix-plan-agent-run-2026-10-28.md`（§14/§15）
- 运行日志：`logs/supervise-20261028/run-exact.log`
- 事件流：`logs/supervise-20261028/events-02807b73-*.jsonl`
- 工具 trace（本机）：
  `C:\Users\pereg\.browser4\workspaces\Browser4-4.15-feat-2ec92531\app-data\logs\agent\20261028.211312\e1676135-f357-4afb-8bd7-3a44a1d02f93\cli-tool-trace.jsonl`
- 基线 workspace（本机）：
  `C:\Users\pereg\.browser4\workspaces\Browser4-4.15-feat-2ec92531\app-data\agent\2026\10\28\e1676135-f357-4afb-8bd7-3a44a1d02f93\workspace`
- 旧的错误 workspace（用于对比）：
  `C:\Users\pereg\.browser4\workspaces\Browser4-4.15-feat-2ec92531\app-data\agent\2026\10\28\862d28c5-9697-44b5-836d-e9b125656b4b\workspace`
- 关键代码：
  - `ToolOutcome.kt:52-61,84,107-108,162-171`
  - `SystemToolExecutor.kt:29-37,65-70`
  - `B4CliToolExecutor.kt:147-207`
  - `AgentToolManager.kt:345-351,359-365`
  - `BasicBrowserAgent.kt:41,51,66-68`
  - `MCPToolController.kt:1989`
  - `AbstractToolExecutor.kt:118-127`
  - `ToolResultTextRenderer.kt:34-40`
  - `RobustBrowserAgent.kt:990,1029-1035,1510-1523`
  - `AgentRunEvent.kt:30-41`
  - `CommandStatus.kt:330-337`
  - `StatefulAgentRunner.kt:672-687,756-768,799-807,884`
  - `AgentResume.kt:102-116`
  - `CliProcessManager.kt:410-421`
  - `CodingAgentShell.kt:202-214`
