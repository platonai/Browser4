# `agent run -i` 输出格式优化计划

- 日期：2026-11-09
- 范围：`cli/browser4-cli/src/agent_interactive.rs`（`Renderer` / `Repl` / `TaskFollower`）、
  `cli/browser4-cli/src/main.rs`（`--wait` 收尾行、`--json` 信封）、`help.rs`、`tips.rs` 与相关文档
- 目标：让 `-i` 的每一行都**可扫读、可定位、可回看**，并让 `-i` / `--follow` / `--wait` 三种模式
  的同一事件**输出同一种行**（今天三份实现各写各的，已经漂移）
- 不改：后端事件语义、`--ndjson` / `--json` 的机器输出纯度、默认的 `agent run`（无 flag）行为

---

## 一、现状：`-i` 现在到底打印什么

| 场景 | 现在的行 | 代码锚点 |
|---|---|---|
| 横幅 | `Browser4 agent · session=… · engine=cli · type /help for commands` | `Repl::banner` 1662-1671（2026-11-14 起横幅已去掉 `engine=cli`，见文末「后续变更」） |
| 提示符 | `agent> `（固定，不区分空闲/运行中） | `Repl::prompt` 1673-1682 |
| 提交 | `▶ task <id>` | `Repl::start_task` 1895 |
| 工具调用 | `[00:02] #1 b4.run({"args":"goto …"})` | `Renderer::format` 588-603 |
| 工具结果 | `[00:04]   ↳ b4.run ok in 3.5s` / `… failed after 120ms: …`（仅慢/失败才打） | 604-636 |
| 模型文本 | `[00:05]   💭 <截断 200 字>`，不换行 | 637-647 |
| steering | 本地 `↪ steering queued — the agent sees it at its next model round` + 后端 `[00:20]   ↪ steering delivered: …` | 1869 / 648-655 |
| 用量 | `[00:09] tokens 12.4k / 5.00M (0%) · 10 requests`（节流：10 次请求或 15s） | `format_usage` 668-718 |
| 完成 | `✔ done in 18.4s · <id>` 后原样倾倒整个 result 文本 | `complete_current` 2026-2034 |
| 失败 | `✖ failed · <id> — <reason>`（无耗时、无 errorCode、无 resume 提示） | 2040-2046 |
| 取消 | `⏹ cancelled · <id>` | 2036-2039 |
| `/status` | `<id> · <status> · N step(s) <message>`（message 为空时留尾随空格） | 2296-2311 |

对照 `--follow` / `--wait` 的同场景行（`print_completion` 1355-1432、`main.rs` 16095、
`main.rs` 16483-16506）：

| 场景 | `--follow` / `--wait` | `-i` |
|---|---|---|
| 完成 | `✔ Agent completed in 18.4s:`（`--wait` 无 `✔`） | `✔ done in 18.4s · <id>` |
| 失败 | `✖ failed after 3.2s: <reason>` | `✖ failed · <id> — <reason>` |
| 取消 | `[00:12] ⏹ cancelled` | `⏹ cancelled · <id>` |
| 失败补充 | `errorCode` / `workspace` / `files` / `budget` / `resume:` 都打（`agent run --wait`） | 全无 |

**结论：同一件事有 3 种行文，`-i` 是信息最少的那一种。**

---

## 二、问题清单

### P0 —— 影响可读性 / 信息正确性，改动小

| # | 问题 | 证据 | 改法 |
|---|---|---|---|
| P0-1 | 终态行三模式不一致，且 `-i` 丢耗时 | 上面表格 | 抽一个 `terminal_status_line(status, elapsed, task_id, cost, extra) -> String`，`--follow` / `--wait` / `-i` 共用；`-i` 统一为 `✔ Agent completed in 18.4s · <id> · <cost>` |
| P0-2 | `tool.result` 行没有 `#n`，同名工具连续调用时分不清是哪一次失败 | `tool_index` 只在 `tool.call` 递增（589），结果行不打 | `Renderer` 增加 `pending_calls: Vec<(String, u64)>`，结果行打 `↳ #3 tool …`；快速成功的 `tool.result` 虽不渲染，也必须出队，否则序号漂移 |
| P0-3 | 工具参数是一整串 JSON（截 120 字），关键信息埋在键名里 | `format_tool_arguments` 905-911 | 解析 JSON object → 按白名单键（`selector`/`ref`/`url`/`path`/`file`/`args`/`text`/`query`/`sql`/`command`）渲染 `key="value"`，最多 3 对 + `+N`；解析失败或非 object 回落到现在的紧凑 JSON |
| P0-4 | `-i` 把 result 整段倾倒（单行 2–5 KB JSON）；`agent result` 还先打原始 JSON、再打同一份数据的人类段落 | `complete_current` 2027-2032、`print_completion` 1390-1396、`handle_agent_result` 16649+16653 | 按字段分层渲染 + 去重 + `/result --json` 机器出口，详见 [`20261109-agent-result-rendering-design.md`](20261109-agent-result-rendering-design.md) |
| P0-5 | `-i --json` 的字段互相覆盖：每个任务都写 `task_id` / `raw` / `stats`，最后只剩末个任务 | 1896、2033、2401 | 保留标量为"最后一个任务"（兼容），新增 `tasks: [{taskId,status,elapsedMs,…}]` 累积数组；文档写明语义 |

### P1 —— 体验，改动仍局限在 CLI

| # | 问题 | 证据 | 改法 |
|---|---|---|---|
| P1-1 | 模型文本 200 字硬截断、不换行、续行无缩进，和工具行糊在一起 | 637-647 | 统一"角色标记 + 续行缩进"：`[00:05]   ⋯ <text>` 换行后缩进到角色列；标记 `💭` → `⋯`（emoji 在 conhost / 无彩色字体的终端上是豆腐块，且宽度不是 1） |
| P1-2 | 步进行没有宽度概念，全靠终端硬折行 | 全文 | 宽度来源 `COLUMNS`（clamp 40–400，默认 100），不加依赖；`wrap_with_gutter(text, gutter, width)` |
| P1-3 | 长静默期没有任何输出（一次慢工具 3 分钟 ≈ 卡死） | 无实现 | `TaskFollower` 记 `last_step_at`；`Repl::run` 与 `follow_task` 的 tick 上打心跳：`[02:39]   ⋯ still running · 5 step(s) · 30s since the last step`，30s 静默起、30s 节流；TTY-only、`--quiet`/`--ndjson` 下静默 |
| P1-4 | 完成行不含消耗，用户必须再敲 `/stats`（2026-10-24 评审 §四.1 遗留） | 2027-2031 | 完成时补一次 `GET /api/commands/{id}/stats`（2s 超时、失败即整段省略），行尾追加 `· 3 rounds · 39 tools · 54.4k tokens` |
| P1-5 | 失败行没有 `errorCode` / `resume:`，而 `agent run --wait` 有 | 2040-2046 vs 16483-16506；`StatusProgress` 已有 `error_code`/`resumable`（263-266） | 补一行 `  resume: agent run --resume <id>`（仅 `resumable`）与 `— <code>: <reason>` |
| P1-6 | 任务之间没有分隔：`▶` / 步骤 / `✔` / 下一个 `▶` 连成一片 | 1895、2027 | 每个任务前一行空行 + 提交行带任务文本：`▶ task <id> — <task 前 60 字>`（保留 `▶ task <id>` 子串，既有 e2e 断言不破） |
| P1-7 | `/quit` 或 stdin EOF 时任务仍在跑，一个字都不说（2026-10-24 评审 §二.1 遗留） | `handle_slash_command` 2066 / `run` 1719-1723 | 退出前：`⚠ task <id> keeps running — agent status <id> · /cancel to stop it` |
| P1-8 | 横幅不体现本次会话的关键开关 | 1662-1671 | 只列**非默认**项：`· coding=on`、`· access=read-build`、`· budget=5.0M`、`· quiet`；第二行固定为 `  type a task · /help for commands · Ctrl+C cancels the running task` |
| P1-9 | `/status` 行格式混杂、空 message 留尾随空格 | 2305-2308 | `task <id> · <status> · N step(s)`，有 message 时另起 `— <message>` |
| P1-10 | 不支持 `NO_COLOR`（标准环境变量），只判 `is_terminal` | `color_enabled` 1505-1507 | 谓词化：`color_enabled_for(is_tty, quiet, json, no_color)`，加 `NO_COLOR` 判据，纯函数便于单测 |

### P2 —— 需要后端配合 / 锦上添花

| # | 项 | 说明 |
|---|---|---|
| P2-1 | `callId` 精确配对 | P0-2 的客户端 FIFO 是启发式（同名并发调用可能错配）。后端在 `tool.call`/`tool.result` 上都带 `callId` 才能精确；跨模块（Kotlin + 线格式契约测试），单独一单 |
| P2-2 | `/verbose on\|off` | 显示快速成功的 `tool.result` 与 `round.start`，排障用；默认关 |
| P2-3 | `-i --ndjson` 现在只在 stderr 说"忽略" | 保持；`/events [n]` 已是机器逃生口，不再扩展 |
| P2-4 | 会话级 `/quiet` | 复用全局 `-q`；`Repl.quiet` 已是字段，只需一个斜杠命令改它 |

### 已评估但**不做**

- **全屏 TUI / PTY / spinner**：与事件流争行，且要引入 `crossterm` 之类的依赖。
- **运行期重打提示符**（2026-10-23 评审 §P3-4 的有意取舍）：会让提示符插进步骤流。改为靠
  `↪ steering queued` 回执 + 心跳 + `/status` 回答"它在动吗"。
- **Markdown 渲染模型文本**：成本高、宽度/颜色不可控，收益不匹配。
- **多语言**：CLI 输出保持英文。

---

## 三、目标格式（Before / After）

### 3.1 一次正常任务

before

```
Browser4 agent · session=abc · engine=cli · type /help for commands
▶ task 5b0c3f2a
[00:02] #1 b4.run({"args":"goto https://example.com/pricing"})
[00:04]   ↳ b4.run ok in 2.4s
[00:05]   💭 The page has a cookie banner; I'll dismiss it first so the pricing grid is readable.
[00:06] #2 browser_click({"ref":"#accept"})
[00:07]   ↳ browser_click failed after 120ms: no element matched selector '#accept'
[00:09] tokens 12.4k / 5.00M (0%) · 10 requests
✔ done in 18.4s · 5b0c3f2a
{"summary":"…","keyFindings":[…60 行 JSON…]}
```

after

```
Browser4 agent · session=abc
  type a task · /help for commands · Ctrl+C cancels the running task

▶ task 5b0c3f2a — Summarize the pricing page
[00:02] #1 b4.run(args="goto https://example.com/pricing")
[00:04]   ↳ #1 b4.run ok in 2.4s
[00:05]   ⋯ The page has a cookie banner; I'll dismiss it first so the pricing
            grid is readable.
[00:06] #2 browser_click(selector="#accept")
[00:07]   ↳ #2 browser_click failed after 120ms: no element matched selector '#accept'
[00:09]   tokens 12.4k / 5.00M (0%) · 10 requests
[00:39]   ⋯ still running · 5 step(s) · 30s since the last step
[00:41]   ↪ steering delivered: also check the enterprise page
✔ Agent completed in 18.4s · 5b0c3f2a · 3 rounds · 39 tools · 54.4k tokens
（正文改为字段化渲染：Summary / Key findings / Files written / Problems / Data
 —— 见 [`20261109-agent-result-rendering-design.md`](20261109-agent-result-rendering-design.md) §二）

agent>
```

### 3.2 失败 / 取消

```
✖ failed · 5b0c3f2a · 3.2s — access_denied: editing /etc/hosts is refused
    resume: agent run --resume 5b0c3f2a · /status for the envelope
```

```
⏹ cancelled · 5b0c3f2a · 18.4s · 39 tool(s)
```

### 3.3 `/status`

```
task 5b0c3f2a · running · 12 step(s) — collecting product details
```

---

## 四、设计决议

1. **单一渲染入口。** 终态行、工具行、预览都收敛到 `agent_interactive.rs` 的自由函数
   （`terminal_status_line`、`format_tool_arguments`、`format_result_preview`、`wrap_with_gutter`），
   `--follow` / `--wait` / `-i` 只做"何时调"，不做"怎么拼"。这是本次唯一的结构性改动，也是防止再次漂移的办法。
2. **人类行只走 `Renderer::print` 与 `Repl::out`**，两者都已门控 `quiet` / `json_active()`；新行不得绕过。
3. **机器纯度不变。** `--ndjson` 一个字符都不改；`-i` 依旧不接受 `--ndjson`（stderr 明说）。
   新增的 `tasks` 数组只在 `--json` 下出现，且不改变既有 `task_id` / `raw` / `stats` 键。
4. **宽度与颜色不引依赖。** `COLUMNS`（默认 100）+ `NO_COLOR`；纯函数谓词便于单测，不做 `set_var` 环境改动型测试。
5. **预览只在 `-i` 收窄**，`--follow` 保持全文 —— 一次性模式没有 `/result` 可回看，收窄就是丢信息。
6. **兼容优先：** 既有断言子串一律保留（`▶ task <id>`、`steering queued`、`✖ failed`、`failed after 120ms`、
   `ok in 3.5s`、`agent-task-1 · done · 41.3s`），新信息只做**追加**。
7. **失败即省略，不失败即 `?`：** 统计、心跳、预览这些"增值信息"的任何错误都必须降级为"不打这一段"，
   绝不能让 `-i` 的完成行因一次 stats 失败而消失。

---

## 五、分阶段实施

### Phase 1（P0，1 PR）

| 文件 | 改动 |
|---|---|
| `agent_interactive.rs` | `Renderer`：`pending_calls`、`width`、`paint` 不变；`format()` 的 `tool.result` / `model.text` 分支改写；新增 `terminal_status_line` / `format_result_preview` / `wrap_with_gutter`；`format_tool_arguments` 重写 |
| `agent_interactive.rs` | `Repl`：`last_task_text`；`start_task` 打任务文本；`complete_current` 用共享行 + 预览 + `/result` 提示；`release_current`/完成路径累积 `tasks` JSON |
| `main.rs` | `maybe_pretty_print_json` → `pub(crate)`；`--wait` 完成行（16095）改用 `terminal_status_line` |
| 测试 | 单测：工具参数渲染表（白名单键/回落/`{}`/非 object/截断）、`#n` 配对（含快速成功不漂移）、换行缩进、共享终态行三态一致、结果预览阈值与提示；更新 `renderer_formats_tool_calls_with_index_and_arguments` 的 JSON 断言 |

验收：`cargo test --bin browser4-cli` 全绿；`-i` 在 mock 后端下完成行含 `Agent completed in`。

### Phase 2（P1，1 PR）

| 文件 | 改动 |
|---|---|
| `agent_interactive.rs` | 横幅（非默认开关 + 提示行）、任务空行分隔、`/status` 行、`/quit` 存活提示、失败补充 `resume:`/`errorCode`、完成行追加消耗（`get_command_stats` + 2s 超时）、`NO_COLOR` 谓词、心跳 |
| `main.rs` | 若 `--follow` 也加消耗摘要：复用同一函数，TTY 且非 ndjson 才请求 |
| 测试 | 单测：横幅只列非默认项、`color_enabled_for` 四真值表、`/status` 有/无 message、退出提示、完成行消耗段落的存在/缺失（stats 404/超时 → 整段省略）；e2e：`-i` 完成行 + `/result` 预览提示 |

### Phase 3（P2，可延后）

- 后端 `callId`（Kotlin：`AgentTaskEvents.kt`、`CommandController` 线格式契约测试）→ 客户端精确配对；
- `/verbose`、`/quiet` 会话命令。

---

## 六、测试与验收矩阵

| 层 | 用例 | 目的 |
|---|---|---|
| Rust 单测 | `format_tool_arguments` 表驱动（6–8 例） | 白名单键、回落、截断、非 object |
| Rust 单测 | `renderer_pairs_results_with_call_index` | `#n` 配对 + 快速成功出队 |
| Rust 单测 | `renderer_wraps_with_a_gutter` | 宽度 80/100 的续行缩进、`⋯` 标记、无 ANSI |
| Rust 单测 | `terminal_line_is_identical_in_every_mode` | `--follow`/`--wait`/`-i` 同输入同输出 |
| Rust 单测 | 结果渲染器表驱动测试（见[结果渲染设计](20261109-agent-result-rendering-design.md) §六） | 段落/表格/兜底/去重 |
| Rust 单测 | `banner_lists_only_non_default_knobs`、`color_enabled_for`、`/status` 两态、退出提示 | P1 行为 |
| e2e（mock） | 既有 4 个 `test_e2e_agent_run_interactive_*` + 新增 1（`/result` 预览提示）；断言现有子串不破 | 交互链路 |
| 手工（TTY） | `pwsh ./b4w.ps1 agent run -i "<task>"`：看横幅、分隔、心跳、完成消耗、`/result` | 真实观感 |
| 手工（管道） | `printf 'task\n/quit\n' \| … agent run -i` | 非 TTY 不出现横幅/提示符/心跳 |

> 不跑全量 Maven：本次不动后端（Phase 1–2）。Phase 3 若动 Kotlin，才按
> `mvn test -pl browser4-rest -am -Dtest=CommandControllerTest` + `AgentTaskEventLogTest` 定向跑。

---

## 七、文档更新清单（Phase 2 一起做）

1. `skills/browser4-cli/references/agent.md` — §`agent run -i` 的示例（357-365）换成新格式，补结果预览与 `/result` 提示；
   §`/stats`（389-412）头部行不变，补"完成行已带紧凑消耗"；§Flags 表 `--follow` 行提一句收尾摘要。
2. `skills/browser4-cli/SKILL.md`（367、374 行）— `-i` 那句补"完成行含 rounds/tools/tokens，结果超过 12 行折叠为预览"。
3. `README.md`、`README.zh.md`、`cli/browser4-cli/README.md` — `agent run` 行同步一句话。
4. `cli/browser4-cli/src/help.rs` — `agent-run` 的 Notes 增加输出词汇表（`▶ #n ↳ ⋯ ↪ ✔ ✖ ⏹` 与 `/result`）。
5. `cli/browser4-cli/src/tips.rs` — 327 行那条补 `/result`。
6. `docs-dev/copilot/agent-run-flow.md` — 若其中描述了渲染行为，同步。

---

## 八、风险与非目标

- **e2e/外部脚本 grep**：`-i` 的完成行由 `✔ done in` 变为 `✔ Agent completed in`。仓内无断言依赖旧串，
  且 `-i` 是交互模式，脚本用它不合理；在 CHANGELOG/文档里点名一次即可。
- **非 TTY 会话（管道驱动）**：横幅/提示符已关；心跳与退出提示也必须在非 TTY 下静默，否则 e2e 抖动。
- **`--quiet` / `--json`**：所有新行必须走既有门控，宁可不打。
- **`COLUMNS` 缺失或异常**：clamp 到 40–400 后回落 100，绝不让宽度把行切坏（多字节安全由 `truncate` 保证）。
- **非目标**：见 §二 末尾四条。

---

## 九、待确认（建议默认已按"建议"取值）

1. 完成行是否默认带消耗（需一次额外 stats 请求）？**建议：带**，失败静默省略；可否用 `/stats off` 关掉。
2. `💭` 是否换成 `⋯`？**建议：换**（字体/宽度兼容），文档里的示例同步。
3. 结果预览是否也用于 `--follow`？**建议：不用于**（一次性模式无可回看），只用于 `-i`。

---

## 十、实施状态（2026-11-10）

| 项 | 状态 | 落点 |
|---|---|---|
| P0-4 结果分层渲染 | ✅ | 新模块 `agent_result.rs`（四入口共用：`agent result` / `--wait` / `--follow` / `-i` / `/result`），见 [结果渲染设计](20261109-agent-result-rendering-design.md) |
| P0-1 终态行统一 | ✅ | `agent_interactive::terminal_status_line(TerminalStatus, elapsed, task_id, suffix, reason)`：完成 `✔ Agent completed in 41.3s · <id> · …`、失败 `✖ failed · <id> · 12.1s — reason`、取消 `⏹ cancelled · <id> · 41.3s · 39 tool(s)`。`--wait` 的失败路径仍是错误消息 + 非零退出（不重复打一行） |
| P1-4 完成行带消耗 | ✅ | `format_run_cost`（`3 round(s) · 39 tool(s) · 54.4k tokens`，缺项省略、provider 不报 token 时不写 0）+ `terminal_cost_chain`（2s 预算；`--json`/`-q` 不请求；任何失败整段省略） |
| P0-2 工具结果 `#n` 配对 | ✅ | `Renderer.pending_calls: Vec<(String, u64)>`：`tool.call` 入队，`tool.result` **无论是否渲染**都出队（快速成功也出队，否则序号漂移）；结果行 `↳ #2 browser_click failed after 120ms: …`；中途开始跟随（队列为空）时不编造序号。同一工具连续调用靠它区分，精确配对仍等 P2-1 的 `callId` |
| P0-3 工具参数白名单渲染 | ✅ | `format_tool_arguments`：按 `selector/ref/url/path/file/args/text/query/sql/command` 输出 `key="value"`，最多 3 对再 `+N`（`+N` 含未列出的键）；值内引号转 `'`、单值截 60 字；非 object / 无命中键回落紧凑 JSON |
| P0-5 `-i --json` 累积 `tasks` 数组 | ✅ | `Repl.tasks_json`：`release_current`（所有终态的唯一出口）追加 `{taskId,status,isDone,elapsedMs,steps}` 并 `json_field("tasks", …)`；标量 `task_id`/`raw`/`stats` 语义不变（仍是最后一个任务） |
| P1-1 `⋯` 标记 + 续行缩进 | ✅ | `model.text` 的 `💭` 改 `⋯`（无彩色 emoji 字体 / conhost 上是豆腐块且宽度非 1），steering 行同样处理；`wrap_with_gutter` 让续行缩进到标记列 |
| P1-2 宽度换行 | ✅ | `Renderer.width` = `COLUMNS`（clamp 40–400，默认 100，复用 `agent_result::width_from_columns`）；`wrap_with_gutter` 复用 `agent_result::wrap` 的断词实现；`display_width` 跳过 ANSI，彩色时间前缀不会把缩进算宽。结构化行（call/result）保持单行 —— 那是给人 grep 和复制的 |
| P1-3 静默期心跳 | ✅ | `TaskFollower.last_step_at` / `last_heartbeat_at`（每个 step 都重置窗口）；两个纯函数 `heartbeat_due(silent_for, since_last)`（30s 起、30s 节流）与 `heartbeat_line(prefix, steps, silent)`；两个轮询循环（`follow_task`、`Repl::run` 的 tick）各调一次 `emit_heartbeat()`；**TTY-only**（`Renderer::color` 已含 TTY/`--quiet`/`--json` 判定，再加 `!ndjson`），管道日志保持只有 step |
| P1-5 失败行 `errorCode`/`resume:` | ✅ | `failure_detail()` 把机器码放前面并小写（`page_blocked: the page was blocked`）；`resume_hint_line()` 仅在 `resumable=true` 时补 `    resume: agent run --resume <id> · /status for the envelope`；`-i` 与 `--follow` 都走，`--ndjson` 的 `message` 保持原句不变 |
| P1-6 任务分隔 + 提交行带文本 | ✅ | `start_task` 先打空行再打 `▶ task <id> — <task 前 60 字>`（`task_label` 折叠换行并截断；`▶ task <id>` 子串保留） |
| P1-7 退出时任务仍在跑 | ✅ | `Repl::run` 收尾：`⚠ task <id> keeps running — agent status <id> · agent cancel <id>`（提示用提示符消失后仍存在的命令） |
| P1-8 横幅只列非默认开关 | ✅ | `banner_lines()`（纯函数，便于测试）：首行 session（无 `engine` 字段）+ `coding=on` / `access=…` / `budget=5.00M` / `quiet` 中实际生效者；第二行固定 `  type a task · /help for commands · Ctrl+C cancels the running task` |
| P1-9 `/status` 行 | ✅ | `status_line()`：`task <id> · <status> · N step(s) — <message>`；无 message 不留尾随空格，空消息等同无消息 |
| P1-10 `NO_COLOR` | ✅ | `color_enabled_for(is_tty, quiet, json, no_color)` 纯谓词 + `no_color_requested()`（任何非空值生效），四真值表单测；`color_enabled()` 只做 IO 取值 |
| P2-1 `callId` 精确配对 | ⬜ | 需后端在 `tool.call`/`tool.result` 上补 `callId`（Kotlin + 线格式契约），单独一单 |
| P2-2 `/verbose [on|off]` | ✅ | `Renderer.verbose`：`round.start`（`· round N`）与快速成功的 `tool.result` 默认不打，`/verbose on` 后打；会话命令即时作用到正在跟随的 follower，新任务继承 |
| P2-3 `-i --ndjson` | ➖ | 保持现状（stderr 明说忽略；`/events` 是机器出口） |
| P2-4 `/quiet [on|off]` | ✅ | 复用全局 `-q` 语义：`Repl.quiet` + 当前 follower 的 `renderer.quiet`（心跳随之关闭），新任务继承；开关本身的确认行用 raw printer，不被自己吞掉 |

测试：`agent_interactive` 本轮新增 26 个单测 ——
终态行 4（三模式同形、消耗链、缺项省略、空原因）+
工具行 3（`#n` 配对含快速成功出队、中途跟随不编造序号、参数白名单）+ 
换行 5（`⋯` 与缩进、彩色前缀不撑宽缩进、多字节、结构化行单行、`display_width`）+
心跳 4（30s 起/30s 节流、行文案与时长、机器模式关闭、step 重置窗口）+
失败/状态 3（`failure_detail` 带码、`resume:` 仅 resumable、`/status` 无尾随空格）+
会话外观 4（横幅只列非默认、提交行标签、`NO_COLOR` 四真值、`tasks[]` 累积）+
P2 3（`parse_toggle` 表、`/verbose` 显示 round 与快速成功、两个开关作用到正在跟随的 follower）；
e2e `test_e2e_agent_run_follow_streams_steps` 增加 `#1 b4.run(args="goto …")` / `↳ #1 b4.run ok in 2.5s`，
`test_e2e_agent_run_follow_reports_failure` 增加「失败行带 errorCode + resume 提示」，
`test_e2e_agent_run_interactive_renders_the_result_sections` 与
`test_agent_run_wait_completed_with_predefined_summary` 增加「完成行含 id 与消耗链」，
`test_agent_run_interactive_reports_run_statistics` 的 stats 断言改为「至少两次、同一 task」。

门禁（最终）：`cargo test --bin browser4-cli` = **2114 passed / 1 failed / 4 ignored**
（唯一失败仍是既有 WMI 环境用例，已在干净 HEAD worktree 复现）；
`cargo test --test e2e -- --group agent --max-failures 0` = **36/36 通过，tolerated 0**。


---

## 十一、后续变更（2026-11-14）

1. **横幅去掉 `engine=` 字段。** CLI 工具循环已是唯一 engine，每行都写 `engine=cli` 只是噪音。
   `Repl::banner_lines()` 的首行现在是 `Browser4 agent · session=<id>`，只在生效时追加
   `· coding=on` / `· access=…` / `· add-dir=<n>` / `· budget=…` / `· quiet`；第二行不变。
   §一「现状」表里的 `engine=cli` 与 §三 3.1 的 `before` 属历史记录，`after` 示例已同步为现契约。
2. **`Repl::engine` 字段保留，与横幅无关。** `payload()` 仍把 `engine` 发给 `agent.run`
   （单测断言 `payload["engine"] == "cli"`），去掉的只是显示层。
3. **验收补正（本机复核）。**
   - `cargo test --bin browser4-cli banner` = **3 passed**：横幅用例断言首行不含 `engine=`，且默认会话不出现任何开关。
   - `cargo test --lib` 原先编不过：`commands.rs` 的 `#[cfg(test)]` 用到 `crate::args`，而 `lib.rs` 只声明了 bin 之外的那批模块。
     已在 `lib.rs` 用 `#[cfg(test)]` 补 `pub mod args;` / `pub mod help;`（`args → help → commands/skills` 闭环），
     现在 `cargo test --lib` = **1009 passed / 3 ignored**。
   - 全量 `cargo test --bin browser4-cli` = **2207 passed / 0 failed / 4 ignored**（共 2211 个用例）。
       前提是**清掉继承来的 `BROWSER4_CLI_SERVER`**：本机 shell 里它被设成 `http://localhost:8283`，
       此时有 20 个 `compile_batch_step*` / batch-plan 用例报 `Batch subcommands cannot override --server.`——
       那是环境注入的 server override，不是代码回归（`set BROWSER4_CLI_SERVER=` 后同一条用例由 FAILED 变 ok）。
       早前一次全量 `cargo test` 因 `target\debug\browser4-cli.exe` 被运行中的 `browser4-cli` 进程占用而无法重新链接
       （`os error 5`）；`cargo test --bin` 只链接 `deps\browser4_cli-*.exe`，不受该占用影响。

4. **CLI 测试对环境变量的敏感性已修（2026-11-14，第二轮）。** 上一条里让 20 个 batch 用例变红的
   `BROWSER4_CLI_SERVER` 不是"用例太脆"，而是真缺陷：`compile_batch_step()` 用 `parse_global_flags()`
   解析**每一步自己的 token**，而那个函数会把 `BROWSER4_CLI_SERVER` / `BROWSER4_CLI_SESSION` 折进来，
   于是环境默认值被当成"这一步显式写了 `--server`"，batch 一律拒绝并报
   `Batch subcommands cannot override --server.`。换言之，在导出了该变量的 shell 里跑 `batch` 也会全红，
   不只是测试。
   - 修法：`args::parse_global_flags()` 拆成 `parse_global_flags_from_argv()`（只看 argv）+
     `apply_env_defaults()`（私有）；只有顶层的 `parse_global_flags()` 合并 env，
     `compile_batch_step()` 改用 `parse_global_flags_from_argv()`。env 默认值因此只作用一次，
     落在 CLI 自己的命令行上，永不冒充某一步的 override。
   - 用例侧：`args.rs` 里断言 argv 语义的用例改走 `parse_global_flags_no_env()`，它用
     `EnvDefaultsGuard` 临时摘掉两个 env 键（走 `crate::test_env::lock()` 的进程级 env 锁，
     并发用例不会互相踩），析构时恢复原值。
   - 新增回归用例：`test_parse_global_flags_from_argv_ignores_environment_defaults`（env 已设置时仍返回
     `None`）与 `compile_batch_step_ignores_ambient_server_env`（`BROWSER4_CLI_SERVER=http://localhost:8283`
     下 `tab-close` 仍编译成功）；`compile_batch_step_rejects_nested_batch_and_server_override` 保留，
     证明显式 `--server` 仍被拒。
   - 复核：`BROWSER4_CLI_SERVER=http://localhost:8283`、`BROWSER4_CLI_SESSION=default` 下
     `cargo test --lib --bin browser4-cli` = **1013 + 2212 passed / 0 failed**；清空两变量后同样全绿；
     再叠加 `TZ` / `COLUMNS` / `NO_COLOR` / `CLICOLOR_FORCE` / `TERM=dumb` / `BROWSER4_CLI_PROXY` 的
     敌对环境组合仍全绿。

