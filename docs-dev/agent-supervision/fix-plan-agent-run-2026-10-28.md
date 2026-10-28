# `agent run` 修复计划（4.15-feat，2026-10-28）

> 整理版（实施依据）：[`fix-plan-agent-run-2026-10-29.md`](fix-plan-agent-run-2026-10-29.md)。
> 本文件保留原始计划、§14 审查与 §15 复审，供追溯。

> 依据：[`agent-run-supervision-2026-10-28-4.15-feat.md`](agent-run-supervision-2026-10-28-4.15-feat.md)（S1–S13 / D1–D14 复核）。
> 目标：让"亚马逊微波炉 Top20 × 75 字段"这类任务在**默认配置**下稳定成功，并让失败运行**不再说谎**
> （错误可见、产物归因正确、预算可见）。
> 原则：先修"反馈不可信"（S1/S4/S5），再修"成本/隔离"（S2/S3），最后数据质量与文档。每项都带测试与验收标准。

---

## 0. 验收总目标（Definition of Done）

一次真实运行（原任务，`--fresh-workspace`）满足：

| 维度 | 通过标准 |
|---|---|
| 结果 | `status=done`，`system.taskComplete` 被调用，`filesChanged` 只含本次写的文件 |
| 数据 | 20 行 × 75 列；`keywords`/`category`/`price` 等关键列非空；无 `鈥`/`U+FFFD` 乱码 |
| 成本 | 总 token < 8M（修复后应显著低于当前 5M 就 abort 的水平）；单次输入 < 40k |
| 时间 | < 20 分钟（当前 15m25s 仍未落盘） |
| 可观测 | `--follow` 每 N 轮显示累计 token / 预算；80% 有告警；失败时打印真实原因与已写文件 |
| 失败语义 | 预算/权限/工具失败时 `agent result` 非零退出；`filesChanged` 不包含历史任务文件 |

**明确不做**：不引入 Excel 依赖、不内置 xlsx 写入（既定决策）；不把 `if`/`for` 无脑放行；
不为了"跑通"只把默认预算调到无限大（那只是把问题推后）。

---

## 1. 修复项总览与排期

| 批次 | 项 | 对应问题 | 预估 | 依赖 |
|---|---|---|---|---|
| **P0-1** | F1 `ToolOutcome` 预算键修正 + `skillDoc` 分页 | S1 | 0.5d | 无 |
| **P0-2** | F2 `b4.run` 错误/路径/超时契约 | S4, S13 | 0.5–1d | 无 |
| **P0-3** | F3 `eval` async/undefined 反馈 | S5 | 0.5d | 无 |
| **P0-4** | F4 `--token-budget` + `--follow` usage + 失败结果语义 | S2 | 1d | F1 之后效果最好 |
| **P1-1** | F5 默认按 task 分配 workspace + 交付物按本次 mutation 归属 | S3 | 1d | F4（可选） |
| **P1-2** | F6 失败运行：准确 `filesChanged` / `errorCode` / `agent result` 退出码 | S2/S3 | 0.5d | F5 |
| **P1-3** | F7 Windows 编码 + cmd 内建命令 | S6/S7 | 0.5–1d | 无 |
| **P2-1** | F8 输入保真 / 字段规格 / 额外 positional / listDir / memory 来源 | S8/S9/S11/S12 | 0.5–1d | 无 |
| **P2-2** | F9 文档与 help/tips 同步 | 全部 | 0.5d | 各项落地后 |
| **Gate** | F10 真实运行验证 + 负向测试 | — | 0.5d | F1–F9 |

建议拆 3 个 PR：`P0`（F1–F4）→ `P1`（F5–F7）→ `P2 + Gate`（F8–F10）。
F1/F2/F3 可并行；F5 改动面最大，单独 PR。

**立即缓解（不改代码，先让下一次运行可完成）**：

```powershell
pwsh ./b4w.ps1 config set agent.token.budget.total 20000000
pwsh ./b4w.ps1 agent run "<原任务>" --follow --fresh-workspace
```

这只能绕开 5M 上限和脏 workspace，**不能**解决"模型读不到 SKILL.md"和"b4.run 假成功"，只作为验证基线。

---

## 2. F1（P0）`ToolOutcome` 预算键修正 + `skillDoc` 分页

### 问题
- `ToolOutcome.from` 用 `key = "$domain.$method"` 查 `BODY_BUDGETS`，但 map 的键是 **method-only**
  （`shell`/`read`/`mvnBuild`…），所以 `coding.shell` 永远拿 `DEFAULT_BODY_LEN = 1_200`；
  `system.skillDoc` 也拿 1,200，59,786 字符的 `SKILL.md` 被截成 1,290 字符（`…[truncated 58,125 chars]…`）。
- `ToolLoopCompressor` 已把 `system_skillDoc` 放进 `protectedToolNames`（`RobustBrowserAgent.kt:1933-1935`），
  但截断发生在更早的 `ToolOutcome`，保护失效。

### 改动
1. `browser4-agentic/.../model/ToolOutcome.kt`
   - 查表改为 `BODY_BUDGETS[resolvedMethod]`（或用 `domain.method` 作为键并同步 map）。
   - 新增 `"skillDoc" to 8_000`（先给一页大小；完整文档走分页）。
   - `boundedBody` 的截断 marker 增加**续读提示**：`…[truncated N chars; call system.skillDoc(name, offset=K)]…`。
2. `browser4-agentic/.../tools/builtin/SystemToolExecutor.kt`
   - `skillDoc(name: String, offset: Int = 0, limit: Int = 8_000)`：按 UTF-8 字符切页，返回
     `offset`/`nextOffset`/`totalLength` 头；`limit` 上限 16_000；`MAX_SKILL_DOC_CHARS` 保留为总上限。
   - tool spec 增加 `offset`/`limit` 参数与描述（"read the next page"）。
   - 无 `offset` 时，对 `SKILL.md` 默认返回 **章节标题 + 链接索引**（从 `^#{1,3} ` 与 markdown 链接
     生成，含每个 heading/link 的 offset，≤2KB），而不是全文；`bulk-collection.md` 等链接因此可见。
     其余文档返回第一页。若实现成本高，退而求其次：默认返回第一页 8KB，并在尾部列出所有
     `references/*.md` 链接；但**必须**让参考索引可见。
3. `browser4-agentic/.../agents/CliAgentPrompt.kt`
   - 把"load the complete SKILL.md"改成"read the reference map, then load only the topic doc you need"，
     并给出 `system.skillDoc("bulk-collection.md")` 示例（本次任务真正需要的）。
4. `browser4-agentic/.../agents/RobustBrowserAgent.kt`（system prompt 段）
   - 同步措辞；避免再让模型无条件拉 59KB。

### 测试
- 新增 `ToolOutcomeTest`：
  - `coding.shell` 的结果 body > 1,200（证明 3,000 生效）；
  - `system.skillDoc` 的 body > 8,000（用 `limit=16_000` 或 `bulk-collection.md`，证明新预算生效）；
  - `skillDoc("SKILL.md")` 默认结果 ≤8,000 且含 `bulk-collection.md`/`agent-workspace.md` 链接；
  - 截断 marker 含 `offset=` 提示。
- 新增 `SystemToolExecutorTest`：
  - `skillDoc("SKILL.md")` 含 `bulk-collection.md`、`agent-workspace.md`、`htmlsnapshot.md`；
  - `skillDoc("SKILL.md", offset=8000)` 与第一页不重叠、`nextOffset` 正确；
  - 未知文档仍返回可用列表。
- 回归：`cliToolLoopCompressor` 的 protected 测试仍通过（文档不被 head/tail 剪）。

### 验收
`system.skillDoc("SKILL.md")` 的模型可见结果包含 `bulk-collection.md` / `agent-workspace.md` 的链接
（索引或正文均可），长度 ≤8KB；按 offset 可继续读到 `## 7 Reference Map` 正文；
不再出现 `[truncated 58125 chars]` 作为唯一可见内容。

---

## 3. F2（P0）`b4.run` 的错误、路径、超时契约

### 问题
- CLI 返回 `{"status":"error"}`（eval 文件不存在、`browser_evaluate` 30s 超时）时，`b4.run` 仍是 `[ok]`；
  本次 5 次，其中 4 次是相对路径 `--file` 找不到，模型重复 4 次。
- `b4.run` 的 `workingDir` 默认 null → 相对路径按 CLI cwd 解析，不是 agent workspace。
- `b4.run timeOutSeconds=300` 只约束 CLI 子进程；CLI 的 MCP HTTP 默认 30s（`cli/.../http.rs:8`），
  `browser_evaluate` 先超时。

### 改动
1. `browser4-agentic/.../tools/builtin/B4CliToolExecutor.kt`
   - 解析 CLI JSON 输出：`"status":"error"` → 以 `ToolOutcome` 的失败路径返回（`ok=false`），
     错误文本保留 `error.message` 与建议（如"相对路径请传 workingDir 或绝对路径"）。
   - 非零 exit code 一律映射为失败（不要只看 `evaluate.success`）。
   - `workingDir` 默认使用 `defaultWorkingDir`（构造时传 `agentWorkspaceDir`）；在 tool description 写明。
   - `timeoutSeconds > 30` 时，在 CLI 命令行前注入全局 `--timeout <s>`（避免重复：若 `args` 已含
     `--timeout` 则不注入），或用 CLI 的 `BROWSER4_CLI_TIMEOUT`（若存在）作为等价通道。
2. `browser4-agentic/.../model/ToolOutcome.kt`
   - `b4.run` 的 `status:error` 文本进入 `errors`/`ok=false`（与 F1 一起改）。
3. `skills/browser4-cli/references/agent.md` / `quickstart.md`
   - 写明 `b4.run` 的 cwd/绝对路径、`--timeout` 与 MCP 30s 的区别、`eval --await`。
4. 可选：CLI `eval` 增加 `--out <file>`，由 Rust 写字节，避免 `> file` 经 PowerShell 编码（见 F7）。

### 测试
- 新增 `B4CliToolExecutorTest`：
  - stdout 含 `{"status":"error"}` → `ToolOutcome.ok == false`，文本含 `Eval file ... not found`；
  - `workingDir` 默认值生效（相对路径文件在 workspace 下可找到）；
  - `timeoutSeconds=120` 时 CLI argv 含 `--timeout 120`，已显式 `--timeout` 时不重复。
- 集成：`b4.run("eval --file .b4scratch/missing.js")` 在 `--follow` 中打印 `↳ b4_run failed`。
- 负向：`b4.run("eval --await --file big.js", timeoutSeconds=300)` 跑一个 >30s 的 eval，不应在 30s 被掐。

### 验收
本次运行的 5 次 `status:error` 全部以 `[fail]` 出现；相对路径不再需要模型自己发现并重写 4 次；
30s 超时不再把 20 页 batch 切成 5 批（或至少错误里明确"请分批/提高 MCP 超时"）。

---

## 4. F3（P0）`eval` 的 async/undefined 反馈

### 问题
- `eval "(async () => {...})()"` 未加 `--await` 返回 `{}`（Promise 未等待）；
- `eval "(() => { window.x = ... })()"` 没有 `return` 也返回 `{}`；
- 两者都记成 `[ok]`，本次各出现 1 次，浪费了 batch 构建与一次 84s 的 `goto` + `b4_wait`。

### 改动
- `cli/browser4-cli/src/main.rs`（eval 命令）：
  - CDP 结果 `subtype == "promise"` 且未 `--await` → 返回 `status:error`，
    `message="result is a Promise; re-run with --await"`；
  - `type == "undefined"` 且表达式非赋值/非语句 → 警告 `expression returned undefined; add an explicit return`
    （作为成功结果的 `warning` 字段，或直接 `status:error`；建议前者，避免误伤合法副作用）。
  - `--await` 的行为不变。
- `help.rs`/`references/eval.md`：把 `--await` 放到 eval 的第一条注意事项（在 `--file` 之前）。
- `commands.rs` 的 eval `CommandDef` 描述同步。

### 测试
- Rust 单测：mock CDP result（promise/undefined/value）→ 三种输出；
- e2e：`eval "(async()=>1)()"` 不带 `--await` → 非零/失败文本；带 `--await` → `1`；
- `eval "(()=>{})()"` → 明确的 undefined 提示。

### 验收
本次运行中 2 次 `{}` 至少有一次变成可操作错误；模型不需要先 `b4_help("eval")` 才发现 `--await`。

---

## 5. F4（P0）`--token-budget` + `--follow` usage + 失败语义

### 问题
- 5M 默认预算对 20×75 结构性不足；两次同任务都在 ~5M abort。
- `--follow` 不显示累计 token；80% 只写后端日志；失败时 `agent result` exit 0 且打印过期 filesChanged。
- 预算 knob 只有 `config set`（`config.md:24,67`），没有任务级覆盖。

### 改动
1. **任务级预算**
   - `cli/browser4-cli/src/commands.rs`：`agent-run` 增加 `--token-budget <n>`；
     `tool_params_fn` 转发 `tokenBudget`。
   - `main.rs` `handle_agent_run`：放进 `build_agent_run_extra`。
   - `browser4-rest/.../CommandToolExecutor.kt`：allowed 参数加 `tokenBudget`，传给 `UserCommandExecutor`
     → `StatefulAgentRunner`。
   - **不要**用 `RuntimeConfigRegistry` 存任务级值：它是 process-wide 的，会影响并发任务。
     改为 per-run 字段：`StatefulAgentRunner.executeSerialized` 把 `tokenBudget` 写进
     `RobustBrowserAgent` 的 `tokenBudgetOverride`（`@Volatile`，`finally` 清除），
     `buildCliToolLoop` 用它构造本任务的 `AgentTokenBudget`；全局 `config set` 仍走 registry。
   - 任务结束（成功/失败/取消）清除 override；两个连续任务验证第二个回到默认。
2. **`--follow` 可见性**
   - `cli/browser4-cli/src/agent_interactive.rs`：`TaskEvent` 已解析 `run.usage`；在 human renderer 里
     累计并在每 10 次请求或 15s 时打印一行：
     `tokens 1.24M / 5.0M (25%) · 37 requests`；80% 打一次 `⚠`；100% 打印 abort 行。
   - `--ndjson` 不变（已有 `run.usage`）。
3. **失败结果**
   - `CommandStatus.kt:330-337`：失败时不要回退到"全 workspace 扫描"的 `deliverables`；改为
     `currentTask.mutations`/本次 mtime 过滤（见 F5），并设置 `errorCode=TOKEN_BUDGET|...`。
   - `agent result`（`main.rs:15878`）：解析 envelope 的 `status`，`failed`/`cancelled` → 返回 `Err`（exit 1）；
     增加 `--allow-failed` 逃生口；`agent status` 不变。
   - 预算 abort 的 `summary` 保留"已写文件 + 可 resume"的信息，不要把异常文本当唯一内容。

### 测试
- CLI 单测：`--token-budget` 参数映射；`agent result` 对 `status=failed` 退出非零；`--allow-failed` 退出 0。
- 后端：task-scoped budget override 生效且结束后清除（两个连续任务，第二个回到默认）。
- `agent_interactive`：`run.usage` 事件在 human 模式产生一行；80% 只打一次。
- 负向：`--token-budget 20000` 跑一个任务，看到 80% 告警、abort、exit 非零、filesChanged 为空。

### 验收
下一次真实运行进行到 80% 时，监督者能在 `--follow` 里看到；abort 时 `agent result` 非零且
`filesChanged` 为空（或只含本次实际写入）。

---

## 6. F5（P1）默认按 task 分配 workspace + 交付物按本次 mutation 归属

### 问题
- `StatefulAgentRunner` 只在 `--fresh-workspace` 时设置 `workspaceDirOverride`
  （`StatefulAgentRunner.kt:672-686`）；默认 `baseDir/workspace` 被同一 session 的所有任务复用
  （`RobustBrowserAgent.kt:390-395`）。
- 失败时 `deliverableFilesOf(status.workspace)` 扫描 workspace **顶层全部普通文件**
  （`AgentResume.kt:102-116`），于是旧 CSV + 别的任务的 msedge 报告被算成 `filesChanged`。
- 文档承诺"每个任务独立 workspace"（`agent.md:104-108`、`agent-workspace.md:41-45`）。

### 改动
1. **默认 per-task workspace**
   - `StatefulAgentRunner.executeSerialized`：
     - `--resume` → 用 checkpoint workspace（现状）。
     - 否则默认 `baseDir.resolve("workspace.<taskId>")`（或 `workspace/<taskId>`），
       `workspaceDirOverride = ...` + `rebindWorkspace(...)`。`status.workspace` 在
       `executeAgentCommand` 内读取 `agent.workspaceRoot`（`StatefulAgentRunner.kt:884`），
       只要 override 在进入 `executeAgentCommand` 之前设置，它就是正确的；无需改读取顺序。
     - 新增 `--shared-workspace`（或 `--workspace shared`）保留旧行为；`--fresh-workspace` 成为
       默认行为的别名/兼容开关。
   - `finally` 里照常清理 override。
2. **交付物归因**
   - `AgentResume.deliverableFilesOf(workspace, startedAt, mutations)`：只返回
     `mtime >= startedAt` 且不在 `.b4scratch/.b4inputs/.browser4*` 下的文件；
     若 `WorkspaceStore` 的 current/last task mutations 可用，优先用 mutation 列表。
   - `StatefulAgentRunner` 的 catch/cancel 两处传入 `status.startedAt`（或 task manifest 的 mutations）。
   - `CommandStatus.kt:334` 的 fallback 使用同一过滤结果。
3. **文档**
   - `agent.md`/`agent-workspace.md` 改"每个任务一个 workspace；`--resume` 复用；
     `--shared-workspace` 显式共享"；`agent result` 的 workspace 说明同步。

### 测试
- 新集成测试（或 mock runner）：同 session 连续两个任务 → `workspace` 不同；B 看不到 A 的文件；
  A 的失败 `filesChanged` 为空；`--resume` 复用 checkpoint workspace。
- `AgentResumeTest`：workspace 里放新/旧文件 → 只返回新的；`.b4scratch` 被排除。
- `CommandStatusTest`：failure + 旧 files → `result.filesChanged` 为空或只含新文件。

### 验收
再跑一次相同任务：workspace 名含 task id；旧 CSV/msedge 报告不再出现；
`coding_listDir` 不再看到 125 个历史脚本（新 workspace 只有本次的）。

---

## 7. F6（P1）失败运行的结果语义

### 改动
- `agent result` 非零（并入 F4）。
- `CommandResult` 增加 `errorCode`（`TOKEN_BUDGET`/`NOOP_LIMIT`/`CANCELLED`/`TOOL_ERROR`）与
  `resumable: true|false`；`agent result` 人类输出给出：
  `✖ failed (budget) · workspace: … · files: (none) · resume: agent run --resume <id>`。
- `agent status` 的 `message` 已经带原因；确保 `statusCode=417` + `errorCode` 一致。

### 测试
- `CommandStatus`/`CommandResult` 序列化测试；`agent result --json` 含 `errorCode`/`resumable`。
- 负向：预算 abort → 人类输出含 budget/resume，不再打印"Files the agent reported changing" 的旧文件。

---

## 8. F7（P1）Windows 编码 + cmd 内建命令

### 8.1 编码
- `browser4-agentic/.../cli/CliProcessManager.kt:414-420`：
  `preamble` 目前只给 Windows PowerShell 5.1；`pwsh` 分支为空。改为两者都注入
  `[Console]::OutputEncoding=[Text.Encoding]::UTF8;`（或 `chcp 65001` 等价），并加注释说明
  `b4.run` 的 `> file` 会走这层解码。
  - 注意：无控制台/重定向场景下 `[Console]::OutputEncoding` 可能抛异常，需 `try { … } catch { }`
    兜底，并在 Windows CI 上覆盖；必要时同时设置 `$OutputEncoding` 与 `chcp 65001`。
- 可选：CLI `eval --out <file>`/`htmlsnapshot export` 已存在 `export`；在 `b4.run` 描述里推荐
  `--out`/`htmlsnapshot export` 而不是 `> file`，从根上绕过 shell 编码。
- 测试：在 Windows 上跑一个输出 `It’s`/`décor`/中文的 CLI 命令，断言落盘/返回文本无 `鈥`/U+FFFD。

### 8.2 命令白名单
- `browser4-coding/.../CodingAgentShell.kt:202-214`：
  - `echo.` → 归一化为 `echo`（去掉尾部标点）；
  - `set`（无参数或 `set VAR=value`）加入 Windows 内建；
  - `if`/`for` **不要简单加入**：它们会执行嵌套命令，现有 `SAFE_COMMANDS` 只检查首 token，
    会造成 workspace 边界绕过。二选一：
    - (a) 实现最小 cmd 解析：拆出 `if`/`for` 里的命令 token，逐个走同一套 path guard/白名单；
    - (b) 明确拒绝并返回 `hint`："if/for not supported; use python -c ... or a .cmd file"，
      同时把 `set`/`echo.` 放行。
  推荐先 (b)（0.5d），后续再做 (a)。
- 测试：`set PYTHONIOENCODING=utf-8` 通过；`echo.` 通过；`if exist` 返回可读 hint；
  `if exist C:\Windows\...` 不会绕过边界。

---

## 9. F8（P2）输入保真 / 字段规格 / 交互细节

1. **额外 positional**
   - `cli/browser4-cli/src/commands.rs` 的 `CommandDef` 增加 `reject_extra_positionals: bool`（默认 false）；
     `agent-run` 设 true；`main.rs` 的 `build_command_args` 调用传入该 flag，超出时报
     `unexpected positional arguments: ["。"]`。
   - 测试：`agent run "task" extra` → 错误；`loop` 等仍然允许拼接。
2. **字段规格 xlsx**
   - 修复 `asin_field_description.xlsx` 第 2 行中文说明（当前 `sharedStrings.xml` 里是 U+FFFD）；
     若原始中文不可恢复，至少写英文 description（75 行）。
   - 增加 fixture 校验测试：`sharedStrings` 不含 U+FFFD；表头 75 个名字与代码常量一致。
   - 可选：随任务提供一份 CSV 版本，避免 xlsx 二进制读取（与既定决策一致）。
3. **`coding.listDir`**
   - 增加 `summary=true`/`limit`：默认返回 top 50 + 总数 + 扩展名汇总；`maxDepth` 保留。
   - 测试：125 个条目 → 输出含 `125 entries` 且正文不超预算。
4. **memory 来源**
   - `memory_search` 结果附 `taskId`/`createdAt`/`workspace`；prompt 明示"历史运行的产物，不是本次结果"。
   - "今天"类任务要求重新拉 bestseller 列表并比对 ASIN 集合。
5. **goto/批量**
   - `goto` 默认 `--load domcontentloaded`（或文档明确 `--load`），并在 `b4.run` 描述中给
     "批量页面用 `crawl`/`eval --await`，不要逐页 goto"。
   - 把 `bulk-collection.md` 的 quick start（~2KB）放进 resident quickstart（它不会被 1.2KB 截断——
     注意 quickstart 本身约 10KB，先确认它在 system prompt 中完整）。

---

## 10. F9（P2）文档与 help/tips 同步

按仓库 Documentation Update Rule 更新：

- `skills/browser4-cli/references/agent.md`：workspace 语义、`--token-budget`、`--shared-workspace`、
  失败 `agent result` 退出码、`--follow` usage 行。
- `skills/browser4-cli/references/agent-workspace.md`：per-task 默认、`--resume`、`--shared-workspace`。
- `skills/browser4-cli/references/config.md`：任务级预算与全局 `config set` 的区别。
- `skills/browser4-cli/SKILL.md`：xlsx 输入/CSV 输出、批量采集、`b4.run` 的 cwd/超时/`--await`。
- `cli/browser4-cli/src/help.rs` / `tips.rs`：新 flag 与失败提示。
- `cli/browser4-cli/README.md`（如有 flag 列表）。

---

## 11. F10 验证计划

### 11.1 自动化
```powershell
# Kotlin：工具预算/错误映射/workspace/命令白名单
.\mvnw.cmd -q -D"skipTests" -pl browser4-agentic,browser4-agent-tools,browser4-coding,browser4-rest -am
.\mvnw.cmd -q -pl browser4-agentic -am -Dtest=ToolOutcomeTest,SystemToolExecutorTest,B4CliToolExecutorTest
.\mvnw.cmd -q -pl browser4-coding -am -Dtest=CodingAgentShellTest
.\mvnw.cmd -q -pl browser4-rest -am -Dtest=CommandStatusTest

# Rust：flag/退出码/usage/async eval
cd cli/browser4-cli; cargo test --bin browser4-cli

# CLI e2e（agent follow / result / workspace）
cargo test --test e2e -- --nocapture --scenario=*agent*
```

### 11.2 真实任务
```powershell
pwsh ./b4w.ps1 config set agent.token.budget.total 10000000
pwsh ./b4w.ps1 agent run "<原任务>" --follow --fresh-workspace --ndjson `
  | Tee-Object logs/supervise-verify/run.ndjson
pwsh ./b4w.ps1 agent result <id> --json > logs/supervise-verify/result.json
```
校验脚本（python/openpyxl）：
- 20 行、75 列、表头 == 第 1 行字段名；
- 无 `鈥`/`U+FFFD`；`keywords`/`category`/`price` 非空；
- `taskComplete` 的 `filesChanged` 只含本次 workspace 的文件；`problems` 记录 xlsx→CSV；
- `--follow` 输出含至少一条 `tokens … / …` 与 80% 告警（若触达）。

### 11.3 负向
- `--token-budget 20000`：确认 80% 告警、abort、`agent result` 非零、`filesChanged` 为空。
- `b4.run("eval --file .b4scratch/missing.js")`：确认 `[fail]` 与 hint。
- `coding.shell("if exist ...")`：确认拒绝文本给出 python 替代。

---

## 12. 风险与回滚

| 风险 | 缓解 |
|---|---|
| skillDoc 一次性 59KB 进上下文（~15k tokens） | 默认只返回目录/首页 8KB；完整文档分页；compressor 仍保护 |
| per-task workspace 改变用户预期/续跑语义 | 保留 `--shared-workspace`；`--resume` 显式复用；文档迁移说明 |
| `eval` 自动 await 改变同步语义 | 只对 Promise 返回**错误**，不自动 await；`--await` 仍显式 |
| `if`/`for` 放行造成边界绕过 | 先拒绝 + hint；解析嵌套命令后再考虑放行 |
| 提高预算掩盖 token 浪费 | F1/F2/F3/F5 先降低无效轮次；预算只作为兜底，不作为"修复" |
| xlsx 中文原文不可恢复 | 至少英文 description + 表头校验；CSV 作为输入形态 |

---

## 13. 交付物清单

- [ ] 3 个 PR（P0 / P1 / P2+Gate），每个含单测与 changelog
- [ ] 修复后的真实运行证据：`run.ndjson`、`result.json`、20×75 CSV、校验脚本输出
- [ ] 文档更新（agent.md / agent-workspace.md / config.md / SKILL.md / help/tips）
- [ ] 监督报告附录：修复前后 token/时长/工具调用对比（当前基线：15m25s、5,006,041 tokens、248 tools、0 新交付物）

---

## 14. 审查意见（2026-10-28）

> 结论：整体修复方向正确，但当前计划存在 3 个实现层阻断问题和 5 个验收/依赖矛盾。
> 在下列问题解决前，不建议直接按 P0/P1/P2 拆分实施。以下审查仅涉及文档与代码，未修改实现、未运行测试。

### 14.1 [阻断] Gate 绕开默认配置，成本目标也比当前基线宽松

- 总目标要求默认配置下成功，但 11.2 先执行 `config set agent.token.budget.total 10000000`。
  即使任务消耗 6–8M tokens 并通过，也不能证明默认 5M 配置可用。
- 当前基线是在 5M 中止，“修复后总 token `<8M` 且显著降低”自相矛盾。
- 当前单次输入峰值已是 38,472，验收要求 `<40k` 没有约束力。

**修改要求：**Gate 必须包含一次不修改全局预算的原始命令；总 token 改为 `<5M` 或明确的相对基线下降指标；
单次输入以 38,472 为基线设置实际下降目标。提高预算只允许作为单独的缓解/负向测试，不得替代默认配置 Gate。

### 14.2 [阻断] F3 的 Promise/undefined 检测放错了实现层

F3 计划在 `cli/browser4-cli/src/main.rs` 检查 CDP `subtype == "promise"` 与 `type == "undefined"`，
但当前链路在到达 CLI 前已经丢失这些元数据：

- `MCPToolController.kt:1827-1829` 只把 evaluation value 渲染为文本；
- `main.rs:9615-9622` 只收到文本并尝试解析 JSON，undefined 已变为空字符串；
- `AbstractToolExecutor.kt:118-127` 已将 CDP 结果压缩为 value/className 等字段。

因此“Rust 单测 mock CDP result”测试的是当前不存在的接口。

**修改要求：**在 driver/`BrowserTabToolExecutor` 仍能访问 `JsEvaluation.cdpType/cdpSubtype` 时检测并返回结构化错误；
或者先扩展 MCP 响应元数据，再由 CLI 消费。undefined 警告也应基于真实类型信息，而不是不可靠的表达式分类。

### 14.3 [阻断] F4 缺少有效预算字段，无法计算百分比与 80% 告警

当前 `AgentRunEvent.Usage`（`AgentRunEvent.kt:24-39`）与 CLI `TaskEvent` 只有本次请求的
`inputTokens/outputTokens/totalTokens`，没有有效预算总额。CLI 因而无法可靠区分默认预算、全局 `config set`
和本次 `--token-budget` 覆盖后的预算，不能仅按现计划渲染 `tokens x / y (%)`。

**修改要求：**在任务提交响应、status 或 usage 事件中增加 `effectiveTokenBudget`，并覆盖三种预算来源的测试。
另外，`AgentTokenBudget` 当前在 `RobustBrowserAgent.kt:990` 创建后传入 loop，并非在 `buildCliToolLoop` 内创建；
F4 的实施位置需同步修正。

### 14.4 [重要] F2 必须覆盖 `b4.wait/status`，不能只处理 `b4.run`

超过 10 秒的命令会升级为 job，30 秒 MCP 超时正属于该路径。当前 `B4CliToolExecutor.kt:179-203`
的 `status`/`wait` 仍直接拼接结果文本。如果只解析 `b4.run` 的直接 stdout，长 eval 的 `status:error`
仍可能通过 `b4.wait` 显示为 `[ok]`。

**修改要求：**抽出统一结果分类器，同时覆盖 `run/status/wait`、非零退出码与 JSON `status:error`；
增加“立即失败”和“job 升级后失败”两组测试。

### 14.5 [重要] F1 的预算设计与测试断言互相矛盾

计划将 `skillDoc` 的 `BODY_BUDGETS` 固定为 8,000，却要求 `limit=16_000` 时
`system.skillDoc` 的 `ToolOutcome.body > 8,000`。固定预算下该断言不可能成立。

**修改要求：**分别验证：

1. executor 原始分页结果可以达到 16,000；
2. 经 `ToolOutcome` 后仍不超过 8,000；
3. 默认索引包含参考链接，`nextOffset` 能继续读取正文。

### 14.6 [重要] F4 的 P0 验收依赖尚未实施的 F5

F4 已要求预算失败后 `filesChanged` 为空，并修改失败 fallback；但真正按 `startedAt/mutations`
过滤旧文件的工作排在 F5。独立的 P0 PR 因而不可能满足自己的负向测试。

**修改要求：**把失败产物归因及对应断言统一移到 F5/F6；或者将 F4/F5 放入同一 PR，并明确依赖关系。

### 14.7 [重要] 数据 Gate 仍会放过已发现的语义错误

当前 Gate 只检查 20×75、非空和乱码，但监督报告已发现 `category` 非空却语义错误、bullet 重复、
以及沿用旧 bestseller 集合的风险。仅检查非空无法证明任务正确完成。

**修改要求：**补充以下机器校验：

- 20 个唯一 ASIN；
- 与本次重新获取的 bestseller ASIN 集合一致；
- `category` 符合字段规格，而不仅是非空；
- `overviewbullets/featurebullets` 无重复项。

### 14.8 [重要] F10 的 NDJSON 与 human usage 验收互相冲突

真实验证使用 `--ndjson`，后面却检查 `tokens … / …` 人类输出行；F4 又明确“`--ndjson` 不变”。
现有 `agent_interactive.rs:1064-1074` 也是 NDJSON 与 human renderer 二选一。

**修改要求：**拆成两次验证，或在 NDJSON 中增加可机器校验的累计 usage/budget 字段；
不得要求 prose 与 NDJSON 混流。

### 14.9 其他待补项

- **D7/S11 需明确处置：**说明 40 调用溢出是修复、接受还是延期，并补上 `progress.txt` resident 契约的决定，
  不要静默遗漏。
- **F5 增加输入回归测试：**连续两个 per-task workspace 任务都应得到自己的 `.b4inputs`。
  当前输入复制使用 `agentWorkspaceDir`（`RobustBrowserAgent.kt:1238-1254`），必须保证 rebind 先于输入物化。
- **谨慎修改 `goto` 默认 load 行为：**这是全局导航语义变化；若没有真实页面 e2e，优先只在批量采集文档中
  显式推荐 `--load domcontentloaded`。

### 14.10 审查后实施门槛

- [ ] 修正 14.1–14.3 三个阻断项，再开始对应实现。
- [ ] 将 14.4–14.8 转化为具体测试与 Gate 断言。
- [ ] 对 D7/S11 记录明确决定，并补齐 per-task 输入物化测试。
- [ ] 最终保留一次默认预算、无 `--ndjson` 的 human 验证，以及一次独立 NDJSON 机器校验。

计划中“默认 per-task workspace”“任务级预算不进入 process-wide registry”“暂不放行 `if/for`”
这些决策予以保留。

---

## 15. 复审：对第 14 节审查意见的再审核（2026-10-28）

> 复审基准：当前 HEAD `33be64efb5`（2026-10-28 21:19）的源码，以及本次运行的原始证据
> （`logs/supervise-20261028/run-exact.log`、`events-02807b73-*.jsonl`、
> `cli-tool-trace.jsonl`）。本次复审只做静态核对与日志比对，未修改实现、未运行测试。
>
> 总评：§14 的方向和大部分判断是对的，“先修正再拆分实施”的结论成立。但 §14 有
> **2 处事实错误**（14.2 的 undefined 行为、14.9 的 `goto --load`）、**2 处定位/严重度需要修正**
> （14.2 的行号、14.4 应从“重要”升为 F2 的验收阻断）、**1 处语义漏判**（14.6 与刚落地的
> “保留 abort 产物”冲突），以及 **3 个未覆盖的关键问题**。其中 F2 的
> `workingDir` 前提在 HEAD 上并不成立，F4/F5 的“失败 `filesChanged` 为空”与刚刚落地的
> “保留 abort 产物”语义直接冲突——这两项都会改变修复方案，不能只按 §14 的措辞执行。

### 15.1 逐条判定

| §14 条目 | 原判 | 复审 | 关键依据 / 修正 |
|---|---|---|---|
| 14.1 | 阻断 | **成立，但属“Gate/验收阻断”，不阻断 F1–F9 实现** | 11.2 仍 `config set ... 10000000`；DoD 要求默认配置；`<8M` 弱于 5M 基线 |
| 14.2 | 阻断 | **结论成立，证据与定位需修正** | CLI eval 的文本渲染在 `MCPToolController.kt:1989`，不是 1827；JS undefined 渲染为 `"null"`，不是 `""` |
| 14.3 | 阻断 | **成立** | `AgentRunEvent` 无预算字段；`AgentTokenBudget` 在 `RobustBrowserAgent.kt:990` 创建 |
| 14.4 | 重要 | **成立，且应升级为 F2 的验收阻断** | `b4.wait` 返回 `state=FAILED` 时 `ToolOutcome.ok` 仍为 `true`；本次证据 seq 150/166/464 |
| 14.5 | 重要 | **成立** | 8,000 的 `boundedBody` 预算下，`body > 8,000` 不可能成立 |
| 14.6 | 重要 | **成立，但漏了更关键的语义冲突** | 33be64 故意保留 abort 产物；F4 的“为空”断言会回归 CSV 丢失修复，见 §15.7 |
| 14.7 | 重要 | **成立，补两点** | xlsx 第 2 行 U+FFFD 未修前无法校验 `category`；`keywords` 0/20 已是 DoD 违反 |
| 14.8 | 重要 | **成立** | `emit` 是 NDJSON/human 二选一；11.2 用 `--ndjson` 却检查 human 行 |
| 14.9 | 其他 | **部分成立，需修正** | D7/S11 成立；F5 的输入物化顺序在现有 runner 中已正确；`goto` 没有 `--load` 选项 |
| 14.10 | 门槛 | **需改写** | 见 §15.12；14.1 不是实现阻断，14.4 才是 F2 的验收阻断 |

### 15.2 §14.1（Gate 绕开默认配置）——成立，但定性要降一级

- 事实成立：11.2 第一条就是 `pwsh ./b4w.ps1 config set agent.token.budget.total 10000000`，
  而 §0 要求“默认配置下成功”。这次 Gate 即使通过，也证明不了默认 5M 可用。
- “`<8M` 与当前 5M 基线自相矛盾”严格说不是矛盾，`<8M` 只是上限；真正的问题是它
  **弱到没有约束力**，并且被 11.2 的 10M 覆盖。建议改成两条明确指标：
  1. 一次**默认预算、无任何调参**的运行：记录 total tokens、是否自然结束、是否 abort；
  2. 若一次默认运行仍结构性超出 5M，必须把“调整默认预算”作为独立决策记录
     （含证据、影响面、回滚），而不是在 Gate 脚本里临时 `config set`。
- 单次输入峰值 38,472 的基线有效。`<40k` 等于没设目标，建议改为“峰值 ≤ 30k”或
  “相对 38,472 下降 ≥ 20%”，并在 `--follow`/NDJSON 中输出该值。
- 术语修正：14.1 列的是 **验收阻断**。F1–F8 的实现可以先做；但在 Gate 措辞修正前
  不能宣布“默认配置已修复”。

### 15.3 §14.2（F3 放错实现层）——结论成立，但证据有两处错误

§14.2 的核心结论正确：CLI 拿不到 `cdpSubtype`，所以“Rust 单测 mock CDP result”
测的是不存在的接口。但支撑细节需要修正：

1. **行号指向的不是 CLI eval 的实际路径。**
   CLI `eval` 走 `POST /mcp/call-tool` → `dispatchToToolExecutor` →
   `dispatchToAgentToolExecutor`，文本渲染在 `MCPToolController.kt:1989`。
   `MCPToolController.kt:1827-1829` 是 `executeAgentToolText`，服务截图/PDF/焦点等
   内部路径，与 CLI eval 不是同一条链路；结论相同，但不能拿它证明 CLI eval。
2. **“undefined 已变为空字符串”是错的。**
   `AbstractToolExecutor.kt:118-127` 把 `JsEvaluation.cdpType == "undefined"` 映射为
   `className = "undefined"`；`ToolResultTextRenderer.kt:34-40` 对
   `value == null && className in {"null", "undefined"}` 统一渲染为字面量 `"null"`，
   只有 Kotlin `Unit`/无 className 的 null 才是 `""`。所以：
   - CLI 无法区分 JS `null` 与 `undefined`（两者都是 `"null"`）；
   - CLI 可以看到空字符串，但那不是 undefined 的路径；
   - 真正的“`{}`”来自对象返回值（本次 trace seq 243/414/483 都是对象/异步 IIFE 的
     返回值），不能据此断言“没有 return 就会得到 `{}`”。S5 的“没有 return”案例
     需要用原始表达式与 `eval --json` 结果重新取证。
3. **修复位置应更精确。** `BrowserTabToolExecutor` 的 `eval/evaluateValue` 分支确实
   能拿到 `driver.evaluateValueDetail(...)` 返回的 `JsEvaluation`；但真正消费
   `cdpType/cdpSubtype` 的最后一层是 `AbstractToolExecutor.callFunctionOn(Tc, ...)`。
   建议在这一层（或 driver 的 eval 包装层）检测 `subtype == "promise"` 并返回
   结构化错误/警告，或者在 MCP 响应中增加 `resultType` 元数据再由 CLI 消费。
   Rust 侧只应测试“收到某段文本/元数据后如何分类”，不应 mock CDP。

### 15.4 §14.3（F4 缺有效预算字段）——成立

- `AgentRunEvent.kt:30-41` 只有本次请求的 `inputTokens/outputTokens/totalTokens`。
  CLI 的 `TaskEvent`/`RunStats`（`agent_interactive.rs:358-365`）同样只能累加，
  没有 `effectiveBudget`，无法渲染 `x / y (%)`。
- `AgentTokenBudget.effectiveMaxTotalTokens`（`TokenBudget.kt:50-51`）读的是
  process-wide `RuntimeConfigRegistry`，这正是计划里“任务级预算不进入 registry”
  必须坚持的原因；否则并发任务会互相改预算。
- §14.3 第二段成立：`AgentTokenBudget.from(session.sessionConfig)` 在
  `RobustBrowserAgent.kt:990` 创建，`buildCliToolLoop` 只是接收参数
  （`1029-1035`）。F4 的“在 buildCliToolLoop 内构造”应改为“在 990 行读取
  per-run override，再传入 loop”。
- 建议 `run.usage` 事件增加 `effectiveTokenBudget`（0 表示 unlimited），
  `CommandStatus` 也带一份，CLI 的 human 行和 NDJSON 都消费同一个字段；三种来源
  （默认 / 全局 config / 任务级 `--token-budget`）各一条测试。

### 15.5 §14.4（F2 必须覆盖 b4.wait/status）——成立，且应升级为阻断

- 代码事实：`B4CliToolExecutor.kt:179-207` 的 `status`/`wait` 只拼文本，
  `ok` 仍由 `ToolOutcome.from` 的 `evaluate.success` 决定（`ToolOutcome.kt:105`）；
  `CliResult.isSuccess`（`CliModels.kt:64-65`）虽然已经存在，但没有参与 `ok`。
- 本次运行证据（`cli-tool-trace.jsonl`）：
  - seq 150 / 166 / 464：`b4.wait [ok] Job ... state=FAILED`，正文是
    `browser_evaluate` 30s 超时；
  - 也就是说，长 eval 升级为 job 后，失败仍然以 `[ok]` 进入模型上下文和 `--follow`。
- F2 的验收写着“本次 5 次 `status:error` 全部以 `[fail]` 出现”。只改 `run` 的直接
  stdout，这个验收不可能达成。**14.4 应从“重要”升级为 F2 的验收阻断。**
- 建议抽一个 `classifyCliResult(stdout, exitCode, jobState)`：`run/status/wait`
  共用；`status:error`、非零 exit code、`state=FAILED/CANCELLED` 都映射为失败，
  并保留 job id 与 “改用 crawl/分批” 的可操作提示；测试覆盖“立即失败”和
  “job 升级后失败”两条路径。

### 15.6 §14.5（F1 预算与断言矛盾）——成立

- `ToolOutcome.boundedBody`（`ToolOutcome.kt:107-108,162-171`）在 budget=8,000 时
  返回的 `body` 含截断 marker，长度不会超过 8,000；计划却要求
  `system.skillDoc` 的 `ToolOutcome.body > 8,000`（用 `limit=16_000`），必然失败。
- 按 §14.5 拆成三层测试即可：
  1. `SystemToolExecutor.skillDoc(name, offset, 16_000)` 原始结果可达 16,000；
  2. 经 `ToolOutcome` 后 `body.length <= 8_000`；
  3. 默认索引含 `bulk-collection.md`/`agent-workspace.md` 链接，`nextOffset` 能续读正文。
- 补充实现细节：`offset` 若按 Kotlin `String` 下标切页，会切开代理对（emoji）；
  应明确按 code point 切，并在 `nextOffset` 上与 `totalLength` 同单位。

### 15.7 §14.6（F4 依赖 F5）——成立，但漏掉了更关键的语义冲突

§14.6 只指出“F4 的负向测试依赖 F5 的过滤工作”，这不够。当前 HEAD 的行为是
**故意保留失败运行的产物**：

- `StatefulAgentRunner.kt:756-768` 的 catch/cancel 两处都设置
  `status.deliverables = deliverableFilesOf(status.workspace)`；
- `CommandStatus.kt:330-337` 在 `filesChanged` 为空时回退到 `deliverables`；
- commit `33be64efb5` 的说明明确写着：abort 的 20×75 CSV 曾被报告成“裸失败且无产物”，
  这个 fallback 是修复。

因此计划 F4 的“预算 abort → `filesChanged` 为空”和 F5 的“A 的失败 `filesChanged`
为空”都**与刚落地的语义相反**。如果照做，会把“保留已写产物”的修复回归掉。
正确的验收应是：

- `filesChanged ⊆ { 本次 task 的 mutations / mtime >= startedAt 的文件 }`；
- 允许包含本次已经写出的部分 CSV（这是有价值的信息）；
- 旧任务 CSV、`msedge-*.md` 等必须排除；
- `deliverables` fallback 不能删，只应加过滤；按 §14.6 把过滤逻辑移到 F4/F5
  同一 PR 或明确先后依赖，但**不要**把“为空”作为目标。

另外，`mtime >= startedAt` 受文件系统时间粒度/时钟偏移影响，优先用
`WorkspaceStore` 的 current/last task mutations；fallback 再按 mtime。

### 15.8 §14.7（数据 Gate 语义）——成立，补两点

- `category` 的“符合字段规格”目前**无法机器校验**：`asin_field_description.xlsx`
  第 2 行中文说明仍是 U+FFFD（F8 #2 才修）。Gate 要么先依赖 F8 修复并给出
  字段名→期望值/ID 的映射，要么把 `category` 校验降级为“非空 + 来自允许集合”，
  并明确这不是完整语义校验。
- DoD 已经写了 `keywords/category/price` 非空；监督报告显示上一轮 `keywords` 是
  0/20。因此“只检查非空”其实已经能抓到 `keywords`，§14.7 应把这一点写清楚，
  避免让人以为现有 Gate 连空值都不查。
- “与本次重新获取的 bestseller ASIN 集合一致”要落在**本次运行捕获的列表**上
  （从页面上抓下来的 20 个 ASIN，写入 run log/NDJSON），而不是 Gate 事后再联网
  fetch 一次——后者会受地区、登录态、榜单刷新影响，可能把正确结果判错。
- 建议再加：`overviewbullets/featurebullets` 去重；`asin` 唯一且格式校验；
  `price` 可解析为数值/货币；`task_time` 落在本次运行窗口内。

### 15.9 §14.8（NDJSON 与 human usage 冲突）——成立

- `agent_interactive.rs:1064-1074` 明确是 `if ndjson { ... } else { renderer.render }`，
  不可能同时出现 human 的 `tokens ... / ...` 行；`agent_interactive.rs:650-651`
  的 `notice_enabled` 也保证 NDJSON 不混 prose。
- 11.2 用 `--follow --ndjson | Tee-Object`，后面却检查 human 行，验收必然失败。
  按 §14.8 拆两次运行即可；如果一定要一次采集，就在 NDJSON 的 `run.usage` 中加
  `cumulativeTokens/effectiveTokenBudget`，让机器校验，不要求 prose。
- completion 在 NDJSON 下同样是 JSON（`print_completion`，1239-1249/1274-1283），
  所以“给 NDJSON 的 `run.usage` 增加累计 usage/budget 字段”是可行方案；human 行
  只在非 NDJSON 分支出现（1250-1256）。

### 15.10 §14.9（其他待补项）——部分成立，需修正/补项

1. **D7/S11 成立**：计划没有处置 40 调用上限（`D7`），也没有把
   `progress.txt` 的 resident 契约写进 F8 #3；只加了 `listDir` 汇总不够。
2. **补 D14（dev bundle 来源）**：监督报告自己写了“D14 本次未复测”，而 15.11.1
   将说明 F2 的现场证据与 HEAD 不一致；在定位 F2 前必须先记录后端运行的
   jar/bundle 来自哪个 worktree/commit，否则会把已修的代码再“修”一遍。
3. **F5 输入物化顺序：现有代码已经正确，不需要“保证”，需要测试。**
   `StatefulAgentRunner.kt:672-682` 在 `executeAgentCommand`/`agent.run`（906）
   之前设置 override + `rebindWorkspace`；`RobustBrowserAgent.kt:958` 才调用
   `materializeTaskInputs`，读取的是已 override 的 `agentWorkspaceDir`。所以
   §14.9 的“必须保证 rebind 先于输入物化”应改写为回归测试：
   - 连续两个 per-task workspace 任务各自得到 `.b4inputs`；
   - 第二个任务看不到第一个任务的 `.b4inputs`/旧 CSV；
   - 任务输入来自外部绝对路径时，复制目标是本任务 workspace。
4. **`goto` 没有 `--load` 选项。** `commands.rs:1346` 的 `goto` 只有
   `--new-tab`；`--load` 是 `wait` 的选项（`commands.rs:1875-1896`）；
   `navigate` 也只接受 `url/rawUrl/pageUrl`（`BrowserTabToolExecutor.kt:373-381`）。
   因此计划 F8 #5 的“goto 默认 `--load domcontentloaded`（或文档明确 `--load`）”
   需要改写：要么新增一个真实的 navigate 等待策略（属于全局导航语义变化，需要
   真实页面 e2e），要么只在 `bulk-collection.md`/quickstart 中推荐
   `wait --load`/`crawl`，不要写一个不存在的 flag。§14.9 的“谨慎修改”方向对，
   但没有指出这个事实错误。

### 15.11 §14 未覆盖的三个关键问题

#### 15.11.1 F2 的 `workingDir` 前提在 HEAD 上不成立，现场是“跨 workspace”而不是“默认 null”

计划 F2 的问题描述是“`b4.run` 的 `workingDir` 默认 null → 相对路径按 CLI cwd 解析，
不是 agent workspace”。当前 HEAD 不是这样：

- `AgentToolManager.buildB4CliExecutor(root)` 明确传
  `defaultWorkingDir = root`（`AgentToolManager.kt:345-351`），并在
  `rebindWorkspace` 时重建（`359-365`）；
- `root` 来自 `BasicBrowserAgent.workspaceRoot`，对 `RobustBrowserAgent` 是
  `agentWorkspaceDir`（`BasicBrowserAgent.kt:41,51,66-68`）；
- `B4CliToolExecutor` 的 `run` 使用
  `workingDir ?: defaultWorkingDir`（`B4CliToolExecutor.kt:163-167`）。

但本次运行证据显示的是另一种更糟的状态：

- `coding.workspaceRoot` 返回
  `...\agent\2026\10\28\e1676135-...\workspace`（trace seq 23 等）；
- `b4.run` 的 `eval --file .b4scratch/ev.js` 报错时，CLI 尝试的路径是
  `...\agent\2026\10\28\862d28c5-...\workspace\.b4scratch/ev.js`，以及
  `D:\workspace\Browser4\Browser4-4.15-feat\.b4scratch/ev.js`；
- `862d28c5` 是更早一个 agent 实例的 workspace（其 manifest 的 lastTask 是
  21:13 的 `6694d3c7`，workspace 里还留着 20:03 的旧 CSV）。

也就是说，`b4.run` 的 cwd 与 `coding.*` 的 workspace **不一致**；这不是“默认 null”，
而是 b4 executor 绑定到了旧的 workspace。可能的方向（需按 D14 先确认 bundle 来源）：

- `AgentToolManager._concreteExecutors` 是 `by lazy` 的映射，**首次初始化时**捕获了
  `b4CliExecutor`（`AgentToolManager.kt:139-149`）；`rebindWorkspace` 只替换了
  属性（`364`），没有刷新这个已初始化的映射，于是 `b4` 仍是旧 executor；
- 或者旧 agent/tool manager 被复用，`workspaceRoot` 与 `b4CliExecutor` 的
  `defaultWorkingDir` 生命周期不一致；
- 或者现场后端并不是 HEAD（D14），需要先确认。

对修复方案的影响：

1. 不要只加“默认 workspace”——那在 HEAD 上已经是现状，可能是 no-op；
2. 先复现并定位绑定关系；若确认 `_concreteExecutors` 缓存，修复它是 F2 的前置；
3. 增加回归测试：同一 agent 在 rebind 前后、以及连续两个任务中，
   `b4.run("workspaceRoot")`/相对 `--file` 的解析目录必须等于
   `coding.workspaceRoot`；
4. 在 `--follow`/trace 中记录 `b4.run` 的实际 cwd，避免同类问题再次“看起来是相对路径”；
5. F2 的 `timeoutSeconds`/`--timeout` 部分仍然有效（§14.4 的 wait/status 分类也要保留），
   但“workingDir 默认 null”的表述必须删除或改写。

#### 15.11.2 F10 仍用 `--fresh-workspace`，没有验证默认 workspace

11.2 的命令是 `agent run "<原任务>" --follow --fresh-workspace --ndjson`。在 F5 落地后
`--fresh-workspace` 计划成为默认行为的别名，所以它不再“绕过”隔离；但在 Gate 里
仍然需要一次**不带任何 workspace flag** 的运行，才能证明“默认 per-task workspace”
真的生效。否则 F5 的默认路径没有被验收，只是又用显式 flag 走了一遍旧路径。
建议 Gate 至少包含：

- `agent run "<原任务>" --follow`（默认预算、默认 workspace、human 输出）；
- 同一任务的 `--ndjson` 机器校验（预算/usage/事件）；
- 仅在负向测试里使用 `--token-budget 20000`/`config set`。

#### 15.11.3 F3 的“没有 return → `{}`”需要重新取证

§14.2 已指出 F3 放错层，但没有指出 S5 的现象描述本身也未被证据支持：

- `ToolResultTextRenderer.kt:40` 对 undefined 输出 `"null"`，不是 `{}`；
- 本次 trace 中三次 `"result":{}`（seq 243/414/483）都是对象/异步 IIFE 的返回值，
  其中 seq 483 的表达式里有 `const out = {}`，不能证明“没有 return”；
- 因而 F3 的“2 次 `{}` 至少一次变成可操作错误”需要改成按真实类型分类：
  Promise → 明确错误；undefined → 明确提示；对象 `{}` → 不误报。
- 这同时影响 15.3 的测试设计：先补后端/CLI 的类型元数据，再写断言，不能先写
  “undefined 显示为 `{}`”的测试。

### 15.12 修正后的实施门槛（替代 §14.10）

- [ ] **先修 Gate 措辞与验收**：14.1 改为“默认预算 + 默认 flag”运行；`<8M` 改成
      相对基线指标；峰值输入设实际目标；`config set 10000000` 只保留为缓解/负向。
- [ ] **F2 先取证再改**：按 D14 确认后端 bundle；复现 `b4.run` 与 `coding.*` 的
      workspace 不一致；若确认 executor 缓存/绑定问题，先修它，再加 cwd 回归测试。
- [ ] **F2 统一 run/status/wait 分类器**（14.4 升级为阻断）：非零 exit、
      `status:error`、`state=FAILED/CANCELLED` 全部 `ok=false`；覆盖 job 升级路径。
- [ ] **F3 改在后端做类型检测**（14.2）：Promise/undefined 在
      `JsEvaluation.cdpSubtype/cdpType` 仍可见的层处理；扩展 MCP 元数据或以结构化
      错误返回；Rust 测试只测文本/元数据分类。
- [ ] **F1 按三层拆测试**（14.5）：executor 原始 16k、`ToolOutcome` ≤8k、默认索引
      含参考链接；offset 按 code point。
- [ ] **F4 增加 `effectiveTokenBudget`**（14.3）：usage 事件 + status；三种来源测试；
      任务级 override 不进 process-wide registry；修正 `AgentTokenBudget` 的创建位置描述。
- [ ] **F4/F5 修正 `filesChanged` 断言**（14.6）：允许本次已写产物，排除旧任务文件；
      保留 `deliverables` fallback，只加过滤。
- [ ] **F10 拆 human/NDJSON 两次运行**（14.8）；补数据 Gate 的 ASIN 唯一性、
      bestseller 集合、`category` 语义（依赖 F8 的 xlsx 修复）、bullet 去重。
- [ ] **补 D7/S11/D14 的处置**；`goto --load` 改为真实选项或只写文档；
      F5 输入物化改为回归测试（顺序已正确）。
- [ ] 保留三项既定决策：默认 per-task workspace、任务级预算不进 process-wide
      registry、暂不放行 `if/for`。

### 15.13 复审后仍然成立的原审查结论

- §14 的总体判断“先修实现层阻断，再拆 P0/P1/P2”成立；F3/F4 的阻断判断正确。
- §14.4、§14.5、§14.7、§14.8 的验收/依赖矛盾均真实存在，不能按原计划直接开工。
- §14.9 的 D7/S11 遗漏、F5 输入测试、`goto` 风险提醒都应保留——只需按上面的
  事实修正措辞（F5 顺序已正确、`goto` 没有 `--load`、补 D14）。
