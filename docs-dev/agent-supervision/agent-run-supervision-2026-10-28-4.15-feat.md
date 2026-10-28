# `agent run "亚马逊微波炉 Top20"` 监督记录 — 4.15-feat（2026-10-28）

> 角色：监督方（本会话独立发起并全程盯运行）。
> 任务：按用户给出的原始命令运行
> `./b4w agent run "到亚马逊上找到今天卖得最好的20个微波炉，访问每个详情页，采集产品信息，保存为电子表格。需采集的产品字段见 browser4-tests\pulsar-tests-common\src\test\resources\asin_field_description.xlsx"。 --follow`，
> 找出 `agent run` 链路上的问题（权限、SKILL 文档、工具集、工具披露、工具选择等）。
> 环境：`D:/workspace/Browser4/Browser4-4.15-feat`，后端 dev 8282（runtime bundle），模型 `deepseek-v4-flash`。
> 上一份监督报告（4.15.x）：[`agent-run-supervision-2026-10-28.md`](agent-run-supervision-2026-10-28.md)（下称"上一份"）。
> 本文是 **4.15-feat 上的复核 + 新发现**，不重复上一份的完整叙述。

---

## 0. 结论（TL;DR）

1. **本次运行没有产出任何新交付物，却再次烧掉 5M tokens**：同名 agent 会话的 workspace 跨任务复用，
   上一轮失败留下的 `amazon_top20_microwaves_bestsellers.csv`（20 行 × 75 列，mtime **22:07**）被当成
   本次输入；模型审计它、补若干缺列、写了 **58 个 `.b4scratch` 脚本**，但在 15m25s / 5,006,041 tokens
   处再次被预算中止。最终 `agent result` 的 `filesChanged` 仍然是上一轮的 CSV + **另一个任务**
   （msedge）的 `msedge-automation-detection-report.md`，CSV 的 mtime 没有变化。
   **"每个任务独立 workspace"的文档承诺在 4.15-feat 上不成立，且失败结果会伪造"改过文件"的印象。**
2. **SKILL 文档交付链路是坏的**：`system.skillDoc("SKILL.md")` 返回 59,786 字符，但
   `ToolOutcome` 把它截成 **1,290 字符**（`…[truncated 58,125 chars]…`）。模型只看到 frontmatter 和
   开头/结尾，**参考文档索引（含 `bulk-collection.md`、`agent-workspace.md`）正好落在被截掉的中段**。
   这是"文档明明写了、模型却按旧习惯乱做"的直接原因。
3. **Token 预算已生效，但默认 5M 对"20 页 × 75 字段"结构性不够**：上一轮同任务 37m06s 后以
   `TokenBudgetExceededException`（5,005,088 / 5,000,000）失败；本次 **15m25s / 224 次 LLM 请求 /
   5,006,041 tokens** 再次中止（输入 4,931,514 + 输出 74,527，单次输入峰值 38,472）。`--follow`
   不显示累计 token，80% 告警只进后端日志，用户在看到失败前没有任何预警；`agent result` 仍以
   **exit 0** 退出并打印上面那份过期的 `filesChanged`。
4. **`b4.run` 的"成功"是假的**：CLI 返回 `{"status":"error"}`（例如 eval 文件不存在）时，工具结果仍是
   `b4.run [ok] …`；`--follow` 不打印，模型重复同一错误 4 次。相对路径 `--file .b4scratch/...` 也不
   默认解析到 agent workspace，必须传绝对路径或 `workingDir`。
5. **Windows 编码仍在丢数据**：`b4.run` 经 `pwsh -Command` 执行，子 pwsh 的 `[Console]::OutputEncoding`
   是 cp936，CLI 的 UTF-8 stdout 被当 GBK 解码后再落盘，`top20.json`/最终 CSV 里出现
   `It鈥檚`、`12.8鈥?`；`coding.shell` 的 `set`/`if`/`echo.`/`for` 又被命令白名单拒绝（5 次），
   模型没有干净的办法设置 UTF-8，`| Out-File -Encoding utf8` 也被 CLI 当成未知选项。
6. 上一份的 D1/D2/D3/D8/D10 **已修复**；D6/D7/D9/D13 **仍在**；D11/D12 **部分修复**（文档存在但读不全；
   `--resume` 存在但本次是"意外续跑"）。D4/D14 本次未复测。

---

## 1. 运行事实（终局）

| 项 | 值 |
|---|---|
| 任务 id | `02807b73-d104-41c9-957e-875e9b1f53ff` |
| 提交/开始 | 2026-10-28 22:43:34（`--follow`，无 `--wait-timeout`） |
| 结束 / 时长 | 22:59:02 / **925.4s（15m25s）**，`failed (417)` |
| agent 会话 / workspace | `5cb00537-…` / `…\agent\2026\10\28\e1676135-f357-4afb-8bd7-3a44a1d02f93\workspace` |
| 同一 workspace 的其他任务 | `d3440cb9-…`（21:30，37m 失败）、`8bbbfa11-…`（msedge，21:26，完成） |
| 模型 | `deepseek-v4-flash` |
| 工具调用 | **248**（`coding_shell` 93、`coding_write` 61、`b4_run` 39、`coding_read` 24、`b4_wait` 10、`coding_listDir` 5、`b4_help` 4、`coding_grep` 3、`coding_workspaceRoot` 3、`system_skillDoc` 2、`b4_version`/`coding_changeSummary`/`memory_read`/`memory_search` 各 1） |
| LLM 请求 | **224**（`run.usage` 事件） |
| Token | **5,006,041**（输入 4,931,514 + 输出 74,527；单次输入峰值 **38,472**） |
| 内层循环溢出 | 至少 **2 次**（22:45:44 首次，40 调用上限；22:47:26 轮次重置）；溢出摘要以 user 消息常驻 |
| 上下文压缩 | `cli-compactions.jsonl` 当前任务 **124+** 条 |
| 失败工具 | `coding_shell` 权限拒绝 **5** 次（`set` 1、`if` 2、`echo.` 1、`for` 1）；`b4_run` 的 `status:error` **5** 次（4 次相对路径 `--file` 找不到 + 1 次 MCP `browser_evaluate` 30s 超时），工具层仍全部 `[ok]` |
| 慢调用 | `b4_wait` **10** 次（4.5s / 5.6s / 10.5s / 13.4s / 19.2s / 19.5s / 23.9s / 54.6s / **84.2s**）；`b4.run` 10s job 升级 **10** 次 |
| 写入的文件 | `coding.changeSummary`：**60 operations on 58 files**，全部是 `.b4scratch/` 脚本；根目录交付物 **0 个新文件** |
| 交付物 | 上一轮 CSV 169,948 B / 21 行 / 75 列，mtime **22:07**（本次未改）；`msedge-automation-detection-report.md`（21:29，别的任务） |
| `system.taskComplete` | **未调用**（预算中止先于完成报告） |

**上一轮同任务（`d3440cb9`）的终局**，作为对照：

```json
{"summary":"TokenBudgetExceededException: Token budget exceeded: consumed 5,005,088 tokens, budget 5,000,000. …",
 "filesChanged":["amazon_top20_microwaves_bestsellers.csv","msedge-automation-detection-report.md"],
 "workspace":"…\\agent\\2026\\10\\28\\e1676135-…\\workspace"}
```

本次 `02807b73` 的 `agent result` **与上面逐字相同**（只差 token 数 5,006,041），CSV mtime 仍是 22:07：

```text
Files the agent reported changing:
  amazon_top20_microwaves_bestsellers.csv
  msedge-automation-detection-report.md
Task workspace: …\e1676135-…\workspace
exit=0
```

注意两点：

- `filesChanged` 里的 `msedge-automation-detection-report.md` 是**另一个任务**（21:26 的 msedge run）
  写进同一 workspace 的文件——完成报告把别人的产物算成了自己的。
- 本次运行实际写的是 58 个 `.b4scratch` 脚本，`agent result` 一个都没列；而它列出的两个文件，
  本次一个都没改。`agent result` 在 `status=failed` 时仍以 **exit 0** 退出。

---

## 2. 上一份 D1–D14 在 4.15-feat 上的复核

| 编号 | 状态 | 本次证据 / 说明 |
|---|---|---|
| D1 字段说明文件不存在 | **已修** | `browser4-tests/.../asin_field_description.xlsx` 存在（721,965 B）；启动时复制为 `.b4inputs/asin_field_description.xlsx`（705.0 KB），prompt 的 "Task inputs copied" 块明确给出工作区相对路径。 |
| D2 repo 相对路径不可读 | **已修** | 同上；本次运行没有出现 `PATH_DENIED`。`TaskInputs.materialize` + `RobustBrowserAgent.kt:1249-1258`。 |
| D3 权限拒绝被包成 `[ok]` | **已修** | `ToolOutcome.kt:104-105` 把 `isPermissionRefusal` 计入 `ok=false`；`--follow` 实际打印 `↳ coding_shell failed … PERMISSION_DENIED`。 |
| D4 沙箱可绕过 | **未复测** | 本次只用了 workspace 内路径与 `*>` 重定向（仍可写文件），没有尝试 `file://`/外部绝对路径。 |
| D5 工具披露/提示词不一致 | **部分修复** | browsing 初始集已含 `coding.changeSummary`/`coding.diff`（`RobustBrowserAgent.kt:123-129`）；本次初始 29 个工具，未调用 `system.exposeTools`。 |
| D6 无表格/结构化落盘 | **仍在（既定决策）** | 仍没有 xlsx 写入；本次 50+ 次 `coding_shell` + 28 次 `coding_write` 手动拼数据。`agent-workspace.md:103-115` 仍要求 CSV + `problems` 记录降级。 |
| D7 40 调用/轮 + 有损摘要 | **仍在，部分缓解** | `cliToolLoopMaxIterations` 仍 floor 40（`RobustBrowserAgent.kt:276-277`）；本次 2 次溢出。但新增了压缩（`cli-compactions` 124 条）和溢出续跑摘要，模型能接着做。 |
| D8 `b4.wait` 丢终态 | **已修** | `B4CliToolExecutor.kt:191-207` 现在返回 `state=COMPLETED`，本次实际看到。 |
| D9 `b4.run` 经 pwsh、报错乱码 | **仍在（文档已补）** | 工具描述已写明"Windows 上 args 由 pwsh 解释"（`B4CliToolExecutor.kt:85-93`）；但编码仍按 cp936 解码子进程输出，见 S6；`| Out-File -Encoding utf8` 又被 CLI 当成未知选项。 |
| D10 预算在 CLI 引擎未生效 | **已修，但反向出问题** | `TokenBudget.charge` 已接 CLI 循环（`TokenBudget.kt:79-102`），上一轮 5,005,088 被中止；问题是 5M 默认值太小、`--follow` 不可见（S2）。 |
| D11 SKILL 缺关键场景指导 | **部分修复** | `bulk-collection.md`、`agent-workspace.md` 已存在且更新到 20:37；但 `skillDoc` 截断使它们**不可达**（S1）。 |
| D12 无法续跑 | **部分修复** | `agent run --resume <id>` 已存在（`commands.rs:4042`）；但本次没有显式 resume，是 workspace 共享造成的**隐式续跑**（S3）。 |
| D13 字段规格读错 | **仍在** | 本次正确读出 75 个字段，但第 2 行中文说明是乱码（U+FFFD），`category`/`keywords` 等语义靠猜；上一轮 CSV 的 `keywords` 0/20、`category` 是品类名而非品类 ID（S7）。 |
| D14 dev bundle 跨 worktree | **未复测** | 本次后端 8282 由既有 dev bundle 提供；未检查 jar 来源。 |

---

## 3. 本次新发现（按优先级）

### S1（P0，SKILL 文档 / 工具披露）`system.skillDoc` 把 59 KB 的 SKILL.md 截成 1.2 KB

**现象**：browsing 系统提示明确要求"需要细节时 `system.skillDoc("SKILL.md")`"；模型确实调用了，
但拿到的结果是：

```text
system.skillDoc [ok] ---
  name: browser4-cli
  ...
  This is the **decision layer**: the core loop, the command map, and which reference to open. Every
  topic links to a deep dive — load only what the task needs ([§7 Reference Map](
  …[truncated 58125 chars]…
  end:
  ./b4w.sh <command> ...
```

- 文档本体 `skills/browser4-cli/SKILL.md` = **59,786 字符**；返回 **1,290 字符**。
- 截断发生在 `ToolOutcome`：`DEFAULT_BODY_LEN = 1_200`，`BODY_BUDGETS` 没有 `skillDoc` 条目
  （`ToolOutcome.kt:44-61,107-108`），`boundedBody` 保留 head 2/3 + tail 1/3（`:164-171`）。
- `ToolLoopCompressor` 里"知识文档受保护"的注释（`ToolLoopCompressor.kt:51-54`）救不了它：截断发生在
  更早的 `ToolOutcome`，进入上下文时已经只剩 1.2 KB。
- 后果：`SKILL.md:416` 的 `bulk-collection.md` 链接、`SKILL.md:439` 的 `agent-workspace.md` 链接都在中段；
  模型从未看到"边采边写 CSV、不要 xlsx、固定 locale、`b4.run` 的 PowerShell 引号"这些正是本次任务需要的指导。
  上一份报告说"SKILL 缺指导"，实际是**文档有了但交付链路截断了**。

**修复建议**：
1. `BODY_BUDGETS["skillDoc"] = 60_000`（或按文档大小），并把 `system.skillDoc` 加入 compressor 的
   `protectedToolNames`（已有机制）。
2. 更稳的做法：`skillDoc(name, offset, limit)` 分页，或首次只返回目录/参考索引（<1.5 KB），
   由模型按 section 读取；不要用"头 2/3 + 尾 1/3"这种会切掉中段的策略交付文档。
3. 加测试：`skillDoc("SKILL.md")` 的结果必须包含 `bulk-collection.md` 和 `agent-workspace.md` 两个链接。

### S2（P0，成本 / 可观测性）5M 预算对 20×75 任务结构性不足，且 `--follow` 看不到消耗

**现象**：
- 上一轮同任务：37m06s，`consumed 5,005,088 / budget 5,000,000`，任务 `failed (417)`；
  交付 CSV 已写完，但 `summary` 被替换成异常文本，用户只看到"失败"。
- 本次：**15m25s / 224 请求 / 5,006,041 tokens** 再次触顶；8 分钟时约 2.10M，输入峰值从 8,325
  涨到 38,472 tokens/次。两次都是同一个任务、同一个默认预算、同一个失败。
- `--follow` 只打印工具调用/慢调用/失败，不打印 `run.usage`；80% 告警只有后端日志一行
  （`TokenBudget.kt:96-98`）。用户在失败前没有任何信号。

**证据**：`config.md:24,67` 已文档化 `config set agent.token.budget.total 10000000`；但
`agent.md` 的 Flags 表没有预算相关选项，`--follow` 也没有 usage 行。上一轮 `agent result` 的
`summary` 就是异常字符串，`keyFindings/problems` 为空；本次同样如此，而且 `agent result`
在 `status=failed` 时仍 **exit 0**，只打印异常和过期的 `filesChanged`。

**修复建议**：
1. `agent run --follow` 每 N 秒（或每轮）打印 `tokens 1.2M/5M (24%)`；80% 时打印一次黄字告警。
2. 增加 `--token-budget <n>`（任务级覆盖，落到 `command.run` 参数），或在任务超过 ~40 次工具调用时
   自动把默认预算提高/提示用户。
3. 预算中止时保留已产生的交付物信息：`agent result` 至少给出 `workspace` + 已写文件 + 明确
   "任务被预算中止，可 `--resume` / 提高预算后续跑"，而不是把异常文本当 summary。

### S3（P0，workspace 隔离 / 归因）agent workspace 跨任务复用，文档却承诺"每个任务独立"

**现象**：
- `agent result` 对三个不同任务返回**同一个** workspace：
  `d3440cb9`（21:30 微波炉）、`8bbbfa11`（21:26 msedge）、`02807b73`（本次）→
  `…\agent\2026\10\28\e1676135-f357-4afb-8bd7-3a44a1d02f93\workspace`。
- 该 workspace 的 `.b4scratch` 有 **125 个条目**（多次运行的脚本、`pages/*.json`、`extra*/*.json`），
  本次 `coding_listDir` 一次返回 125 条。
- 本次模型 `memory_search` 找到上一任务 `9c4f389f` 并 `memory_read`，随后读取上一轮 CSV，
  只补 `desc`/`delivery`/`buyboxprice` 等缺口——**任务被隐式变成"修上一轮"**。
- 文档承诺相反：`agent.md:104-108`"Each task gets its own workspace"；`agent-workspace.md:41-45`
  也按任务列表；`--fresh-workspace` 只是 opt-in（`commands.rs:4039`）。
- 后果：陈旧产物污染、完成报告把别的任务的 `msedge-automation-detection-report.md` 算进
  `filesChanged`、`--resume` 的语义与"共享 workspace"重叠。
- **终局证据**：本次 `coding.changeSummary` = "Changes tracked: **60 operations on 58 files**"，
  全是 `.b4scratch/` 脚本；根目录 CSV 的 mtime 仍是 22:07。而 `agent result` 打印的
  `filesChanged` 是 CSV + msedge 报告，**既不是本次写的，也不包含本次写的 58 个文件**。
  `agent result` 在 `status=failed` 时仍以 **exit 0** 退出，看起来像成功。

**修复建议**：
1. 默认按 **task id** 分配 workspace；需要跨任务续跑时显式 `--resume`，不要靠共享目录。
2. 若保留"每会话 workspace"（可能是有意设计），必须改文档，并在 `agent run` 启动时打印
   `workspace: … (shared with N earlier tasks; pass --fresh-workspace to isolate)`。
3. finish gate 的 `filesChanged` 只接受本次运行实际写过的路径（mutation trace），不要把 workspace 里
   已存在的文件扫进来；`d3440cb9` 的 `msedge-…md` 就是反例。

### S4（P1，工具选择 / 错误语义）`b4.run` 对 CLI 的 `status:error` 仍返回 `[ok]`

**现象**（本次 trace，5 次）：

```text
b4.run [ok] {"status":"error",…"Eval file '.b4scratch/top.js' not found…
b4.run [ok] {"status":"error",…"Eval file '.b4scratch/batch5.js' not found…
b4.run [ok] {"status":"error",…"Eval file '.b4scratch/gap_b1.js' not found…
b4.run [ok] {"status":"error",…"Eval file '.b4scratch/gapfill.js' not found…
b4.run [ok] {"status":"error",…"HTTP request timed out [tool=browser_evaluate, endpoint=…, timeout=30s…
```

- `b4.run` 只看子进程是否正常退出；CLI 用 JSON `status:error` 表达业务失败，工具层没有映射。
- `ToolOutcome` 的 `ok` 由 `evaluate.success`/异常决定（`ToolOutcome.kt:97-108`），
  `--follow` 只在 `ok=false` 时打印失败，所以监督者和模型都看不到。
- 同一相对路径错误在本次出现 **4 次**（22:47、22:50、22:51、22:53）；模型每次要重写绝对路径。
- 第 5 次是 **MCP `browser_evaluate` 的 30s HTTP 超时**（见 S13），`b4.run` 的
  `timeoutSeconds=300` 并没有延长它。

**修复建议**：
1. `B4CliToolExecutor` 解析 `{"status":"error"}` → 返回 `[fail]`（或 `ok=false` + 结构化错误），
   至少让 `--follow` 打印。
2. `b4.run` 的 `workingDir` 默认设为 `agentWorkspaceDir`（当前默认 null/CLI cwd，
   `B4CliToolExecutor.kt:158-166`），或在描述里写明"相对路径相对 CLI cwd，请传 workingDir"。
3. 结果里直接给出"把 `.b4scratch/x` 换成绝对路径/workingDir"的 hint。

### S5（P1，工具选择 / 反馈）async `eval` 静默返回 `{}`，20 页批量一次丢光

**现象**：模型写了一个 `(async () => { … fetch 20 pages … })` 的 `eval --file`，结果：

```text
b4.run [ok] {"status":"ok","command":"eval","output":{"result":{},"expression":"(async () => {…"}}
```

- `eval` 没有 `--await` 时不会等 Promise，返回 `{}`，且没有 `status:error`/提示。
- 本次出现 **2 次**空结果：一次是 async IIFE 没加 `--await`（`(async () => { const SRC = …` → `{}`），
  一次是 IIFE 只赋值 `window.__gap` 但没有 `return`（同样是 `{}`）。两次都被记成 `[ok]`。
- 模型随后 `b4_help("eval")` 才看到 "Use --await for async code (fetch, Promises)"；之后加 `--await`
  才拿到 20 条数组。中间浪费了 batch 构建、重试、`goto` + 84s `b4_wait` 等约 5 分钟。
- 说明：帮助文本本身也被 `ToolOutcome` 截断（`…[truncated 3490 chars]…`，S1 同源），只是关键字恰好在前 1.2 KB。

**修复建议**：`eval` 检测到 Promise 返回值时返回 `status:error`/`error: result is a Promise, re-run with --await`，
或默认 `--await`；在 `b4.run` 的 tool description 里把 `--await` 作为 eval 的第一条注意事项。

### S6（P1，编码 / 数据完整性）`b4.run` 子 pwsh 用 cp936 解码 UTF-8，CSV/JSON 落盘乱码

**现象**：
- `top20.json`：`"title_list": "GE 30\" … 12.8鈥?Removable Turntable"`；上一轮 CSV：
  `It鈥檚 easy to set up`、`d茅cor`（应为 `It's`、`décor`）。
- 来源：`b4.run("eval … > .b4scratch/top20.json")` 在 `pwsh -Command` 中执行；子 pwsh 的
  `[Console]::OutputEncoding` 是 **cp936**（本机 `chcp` = 936），把 CLI 的 UTF-8 stdout 按 GBK
  解码成字符串，再用 UTF-8 写文件 → 双重编码。
- 代码：`CliProcessManager.kt:414-420` 只给 **Windows PowerShell 5.1** 加
  `[Console]::OutputEncoding=[Text.Encoding]::UTF8`，`pwsh` 分支的 `preamble` 是空串。
- 本次模型尝试 `eval … | Out-File -Encoding utf8` 自救，但 CLI 把 `-Encoding` 当未知选项报了错
  （`unknown option -encodedCommand, -inputFormat, -outputFormat for 'eval'`），只能改回
  `eval --file --await --json` 让 Java 端读 stdout（Java 的严格 UTF-8 解码是好的）。

**修复建议**：pwsh 分支同样设置 `[Console]::OutputEncoding`/`$OutputEncoding`，或对需要落盘的命令
提供 CLI 自带的 `--out <file>`（由 Rust 直接写字节，不经 shell）；对 `coding.shell` 的 `set`/`if`
补白名单（S7）。

### S7（P1，权限 / Windows 兼容）`coding.shell` 误拒 4 个 cmd 内建命令（共 5 次，`if` 出现 2 次）

**现象**（本次 5 次拒绝，均为 `[fail]`，已可见）：

```text
PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED | tool=coding.shell |
detail=Command blocked — command 'set' is not …
detail=Command blocked — command 'if' is not …
detail=Command blocked — command 'echo.' is not …
detail=Command blocked — command 'for' is not …
```

- `CodingAgentShell.SAFE_COMMANDS` 有 `dir/type/cd/echo` 等，但没有 `set`/`if`/`for`；
  `echo.`（打印空行的常用写法）也没有被归一化成 `echo`。
- 模型用 `set PYTHONIOENCODING=utf-8`、`if exist … else …`、`for %f in (...) do …` 都是 Windows 上
  最自然的写法；每次拒绝浪费 1 轮，也让"用 cmd.exe"的提示词显得不可信。
- 最终模型放弃 shell 分支，改用 python 脚本（61 次 `coding_write`、93 次 `coding_shell`）。

**修复建议**：把 `set`（无参数/赋值）、`if`、`for` 加入 Windows 内建白名单；命令 token 比较前
去掉尾部标点（`echo.` → `echo`）；或在 `coding.shell` 描述里明确列出"仅允许这些内建命令"，
并给出 `set VAR=...` 的替代写法。

### S8（P1，输入保真）用户命令里多出的 `。` 被静默拼进任务文本

**现象**：用户原始命令的引号在 `xlsx` 后就结束了，后面的 `。` 是独立 token。CLI
`build_command_args` 把多余 positional 拼进最后一个参数（`args.rs:743-781`），于是任务文本变成：

```text
…asin_field_description.xlsx 。
```

- prompt 的 user message 682 字符，末尾确实是 `xlsx 。`（`.b4inputs` 复制块之前）。
- 没有 warning/拒绝；对模型是噪声，也说明 `agent run` 的"单 positional"契约没有真正校验。

**修复建议**：`agent-run` 声明 1 个 positional 时，对多余 positional 报
`unexpected positional arguments`（不要复用 `loop` 的拼接逻辑），或在拼接时打印 `⚠`。

### S9（P1，输入文档质量）字段规格 xlsx 的中文说明是乱码，字段语义只能猜

**现象**：
- `asin_field_description.xlsx` sheet `Sheet2`，`A1:BW300`：第 1 行 75 个英文字段名，第 2 行中文说明，
  其余 298 行是示例。
- 第 2 行实际是 `�� URL`、`���ĸ����ӵ��ȥ`、`��Ʒ���Ա�����ͼ�Աߣ�` 等 U+FFFD（`sharedStrings.xml` 里就是坏的，
  不是 openpyxl 的显示问题）。
- 模型只能按字段名猜：上一轮 CSV 的 `keywords` 全空（0/20）、`category` 填成
  "Countertop Microwave Ovens"（应是品类 ID）、`href` 被规整成 `/dp/…?language=en_US`、
  `overviewbullets` 每条重复两次。`agent-workspace.md:113-115` 的"对照表头报告缺列"也无法发现语义错位。

**修复建议**：重新生成该 fixture 的中文说明（GBK→UTF-8 修复或补一份 CSV 字段表）；加一个
fixture 校验测试（`sharedStrings` 不含 U+FFFD）；任务模板要求"字段名 + 类型/示例"而不是只有名称。

### S10（P1，性能 / 工具选择）逐页 `goto` 变成 10s job + 84s `b4_wait`

**现象**：本次 `goto https://www.amazon.com/dp/B0DY95T5HB?language=en_US`：

```text
10:02 b4_run   → [job: 430fed99…] Command still running after 10s
13:26 b4_wait  → 84,223ms, state=COMPLETED, "Navigated to …"
```

- 单页导航 84 秒；20 页就是 28 分钟。上一轮也是这个模式（37 分钟）。
- 本次最终：`b4_run` 39 次、`b4_wait` 10 次、10s job 升级 10 次；15m25s 里有相当一部分花在
  等 `goto`/`eval` job 上，而不是采集本身。
- `b4.run` 的 `jobYieldMs` 固定 10s（`B4CliToolExecutor.kt:48`），`goto` 没有 `--load`/`--timeout`
  提示；模型只能 `b4_wait`。
- 正确的批量路线（`crawl --seed-file` 或 in-page `fetch` + `--await`）在 `bulk-collection.md` 里，
  但该文档因 S1 不可达；模型靠试错才走到 `eval --await` 批量。

**修复建议**：`goto` 默认 `--load domcontentloaded`/较短超时，或在 `b4.run` 描述中给
"批量页面用 `eval --await`/`crawl`，不要逐页 goto"；把 `bulk-collection.md` 的 quick start
放进 resident quickstart（它只有 ~2 KB）。

### S11（P2，工具集）`coding_listDir` 对脏 workspace 一次返回 125 条，`progress.txt` 不存在

**现象**：`coding_listDir(".b4scratch")` → `Contents of .b4scratch (125 entries)`；模型同时
`coding_read(".b4scratch/progress.txt")` → `File not found`。`bulk-collection.md` 要求"每采一项 append
progress.txt"，但本次没有该文件（上一轮也没建立），模型无法从 workspace 判断哪些已完成。
最终 `coding.changeSummary` 显示本次又写了 **58 个 `.b4scratch` 文件**（60 operations），
workspace 的噪声只会继续增长。

**修复建议**：`coding.listDir` 默认只回 top-N + 计数/扩展名汇总，或提供 `summary=true`；把
`progress.txt` 契约写进 resident prompt（不能只放在被截断的 bulk-collection.md 里）。

### S12（P2，memory / 归因）跨任务 memory 命中没有来源/新鲜度提示

**现象**：`memory_search("亚马逊 微波炉 best sellers 20 采集")` 返回上一任务 `9c4f389f` 的 hits，
模型 `memory_read(taskId="9c4f389f")` 后把上一轮 CSV 当自己的成果。任务要求"**今天**卖得最好的 20 个"，
但模型没有重新确认 bestseller 列表的日期；上一轮 CSV 的 `task_time` 是 22:07，而本次补采是 22:50+。

**修复建议**：memory hit 附带 `taskId`/`time`/`workspace` 并在 prompt 里明确"这是历史运行的产物，
不是本次结果"；"今天"类任务要求重新拉一次 bestseller 列表并比对 ASIN 集合。

### S13（P1，超时契约）`b4.run timeoutSeconds` 不覆盖 MCP `browser_evaluate` 的 30s 超时

**现象**：模型把 20 个 ASIN 的详情页 `fetch` 放进**一个** `eval --file … --await`，结果：

```text
{"status":"error","command":"eval","error":{"message":
 "HTTP request timed out [tool=browser_evaluate, endpoint=http://localhost:8282/mcp/call-tool,
  timeout=30s, sessionId=…]"}}
```

- 这次 `b4.run` 传了 `timeoutSeconds: 300`，但那是 **CLI 子进程**的超时；CLI 到后端 MCP 的
  `browser_evaluate` 请求仍按 **30s** 超时，先失败。
- 后果：模型只好把 20 个 ASIN 拆成 5 批 × 4 个，每批 2 次调用（`b4.run` + `b4_wait`），
  直接放大了调用次数和上下文 token；这也是本次 10 次 `b4_wait`、10 次 job 升级的主要来源。
- 文档没有区分这两个超时；`bulk-collection.md` 推荐 `crawl` 正是为了避开它，但该文档被 S1 截断。

**修复建议**：`b4.run` 把 `timeoutSeconds` 同时传给 CLI 的 `--timeout`（或增加 `mcpTimeoutSeconds`），
或后端给 `browser_evaluate` 一个可按命令配置的超时；在 tool description/SKILL 里明确
"批量 fetch 用 `crawl` 或分批 ≤N 页"。加测试：`timeoutSeconds=300` 的 20 页 eval 不应在 30s 被掐。

---

## 4. 数据/交付物快照

上一轮 `amazon_top20_microwaves_bestsellers.csv`：

- 21 行（1 表头 + 20 商品）× 75 列（与 xlsx 第 1 行一致）；169,948 B；`task_time=2026-10-28 22:07:00`。
- 质量抽查：
  - `It鈥檚` / `d茅cor`：UTF-8 被当 GBK 解码（S6）。
  - `keywords` 20 行全空；`category` 是品类名，不是品类 ID；`href` 全部规整为 `/dp/<asin>?language=en_US`。
  - `overviewbullets`/`featurebullets` 中同一 bullet 重复两次（页面 + 提取脚本各拼一次）。
  - `msedge-automation-detection-report.md` 被上一轮 `filesChanged` 收录（S3）。
- 本次运行最终**没有写任何根目录交付物**：CSV mtime 仍是 22:07；15m25s / 5M tokens 后预算中止。
  唯一写入是 **58 个 `.b4scratch/` 脚本**（`coding.changeSummary`: 60 operations on 58 files），
  其中包含 `qa13.py` 这样的自检脚本，但没有等来 `system.taskComplete`。

---

## 5. 修复清单（按优先级）

| # | 优先级 | 改动 | 位置 |
|---|---|---|---|
| 1 | P0 | `skillDoc` 结果预算 ≥ 文档大小，或改分页/目录；保留完整参考索引 | `ToolOutcome.kt:44-61,107-108`；`SystemToolExecutor.kt:65-69` |
| 2 | P0 | 默认按 task 分配 workspace；或明确共享并打印；finish gate 只认本次 mutation | `RobustBrowserAgent.kt:390-395`；`FinishReportGate.kt`；docs `agent.md`/`agent-workspace.md` |
| 3 | P0 | `--follow` 显示 token/预算；`--token-budget` 任务级覆盖；预算中止保留产物摘要 | `agent_interactive.rs`；`commands.rs` agent-run options；`TokenBudget.kt:96-102` |
| 4 | P1 | `b4.run` 把 `status:error` 映射为 `[fail]`；`workingDir` 默认 workspace；相对路径 hint | `B4CliToolExecutor.kt:147-176,85-93` |
| 5 | P1 | pwsh 分支也设 UTF-8（`[Console]::OutputEncoding`）；或 CLI `--out` 直接写文件 | `CliProcessManager.kt:414-420` |
| 6 | P1 | `eval` 对 Promise 返回 `{}` 报错/默认 `--await`；帮助里前置说明 | CLI `eval`；`b4.run` description |
| 7 | P1 | `coding.shell` 允许 `set`/`if`/`for`，`echo.` 归一化为 `echo`，补 Windows 内建说明 | `CodingAgentShell.kt:202-214` |
| 8 | P1 | 修复 `asin_field_description.xlsx` 第 2 行中文说明；加 U+FFFD 校验 | fixture |
| 9 | P2 | `agent-run` 拒绝多余 positional，不再拼进 task | `args.rs:743-781` |
| 10 | P1 | `goto` 默认简短 load/超时，或 resident quickstart 给批量采集路线 | CLI `goto`；`quickstart.md` |
| 11 | P2 | `coding.listDir` 汇总模式；`progress.txt` 契约进 resident prompt | `CodingToolExecutor.kt`；`CliAgentPrompt.kt` |
| 12 | P2 | memory hit 带 task/time/workspace，提示"历史产物" | memory 工具/提示词 |
| 13 | P1 | `b4.run timeoutSeconds` 同时约束 MCP `browser_evaluate`，或给 eval 独立超时；批量 fetch 分批上限写进 SKILL | `B4CliToolExecutor.kt`；CLI `--timeout`；`bulk-collection.md` |

---

## 6. 终局

- 最终状态：`failed (417)`，`TokenBudgetExceededException`，**5,006,041 / 5,000,000 tokens**，
  时长 **925.4s（15m25s）**。
- 规模：**224** 次 LLM 请求、**248** 次工具调用（93 `coding_shell`、61 `coding_write`、39 `b4_run`、
  24 `coding_read`、10 `b4_wait`、5 `coding_listDir`、4 `b4_help`、2 `system_skillDoc`…）；
  输入 4,931,514 + 输出 74,527 tokens，单次输入峰值 38,472。
- 产物：**0 个新交付物**。`amazon_top20_microwaves_bestsellers.csv` 仍是上一轮的 21 行 × 75 列
  （169,948 B，mtime 22:07）；`msedge-automation-detection-report.md` 是 21:29 另一个任务的。
  本次写入 58 个 `.b4scratch` 脚本（60 operations）。
- **死在最后一步**：15:24 最后一条 `coding_write` 写好了 `qa13.py`（`import openpyxl`，读字段表 +
  准备落盘），15:25 预算中止，脚本从未执行——最终 CSV/xlsx 没有生成。
- 完成报告：`system.taskComplete` **未调用**；`problems` 为空，因此"请求 .xlsx、按策略降级为 CSV"
  这一条**没有**被记录（上一轮的修复任务曾在 prompt 里要求记录它，本次没有）。
- `agent result`：在 `status=failed` 时仍 **exit 0**，打印异常文本 + 过期的 `filesChanged`
  （CSV + msedge 报告），对"本次运行改了什么"给出**错误答案**。

---

## 7. 证据文件

本会话原始证据（未纳入 git，路径相对于仓库根）：

- `logs/supervise-20261028/run-exact.log` — `--follow` 全量输出（原样命令）。
- `logs/supervise-20261028/events-02807b73-*.jsonl` / `.summary.log` — 每 4s 拉取的事件流与摘要。
- `logs/supervise-20261028/analysis-*.txt` — 旧任务事件聚合。
- `C:\Users\pereg\.browser4\workspaces\Browser4-4.15-feat-2ec92531\app-data\logs\agent\20261028.211312\e1676135-…\` —
  `cli-prompt/*.request.json`（389 份，完整 prompt）、`cli-tool-trace.jsonl`（443 条，完整工具结果）、
  `cli-usage.jsonl`、`cli-compactions.jsonl`、`task-*/`。
- workspace：`…\app-data\agent\2026\10\28\e1676135-…\workspace\`（CSV、`.b4inputs/`、`.b4scratch/`）。

关键代码/文档位置：

- SKILL 截断：`browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/model/ToolOutcome.kt:44-61,107-108,164-171`
- skillDoc 读取：`browser4-agentic/.../tools/builtin/SystemToolExecutor.kt:65-69,117-126`
- 系统提示要求读 SKILL.md：`browser4-agentic/.../agents/CliAgentPrompt.kt:37-50`
- 工具集/预算：`browser4-agentic/.../agents/RobustBrowserAgent.kt:77-138,276-277,390-395,1289-1359`
- 预算：`browser4-agentic/.../inference/TokenBudget.kt:79-102,112-120`
- `b4.run`：`browser4-agentic/.../tools/builtin/B4CliToolExecutor.kt:85-93,147-176,191-207`
- pwsh 编码：`browser4-agentic/.../cli/CliProcessManager.kt:313-372,414-420`
- shell 白名单：`browser4-coding/.../CodingAgentShell.kt:202-214,294-296`
- 参数拼接：`cli/browser4-cli/src/args.rs:702-793`
- 文档承诺：`skills/browser4-cli/references/agent.md:104-108`；`agent-workspace.md:41-45,103-115`
- 预算 knob：`skills/browser4-cli/references/config.md:24,63-67`
- 上一份报告：`docs-dev/agent-supervision/agent-run-supervision-2026-10-28.md`

