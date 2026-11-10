# `agent run -i` 最终结果渲染设计

- 日期：2026-11-09
- 关联：`20261109-agent-run-interactive-output-format-plan.md`（P0-4 的详细方案）
- 触发：`-i` 每个任务结束后把 `command_result` 的 MCP 文本**原样**打到 stdout ——
  一个 2–5 KB 的单行 JSON，`summary`/`keyFindings`/`filesChanged` 全埋在里面

---

## 一、根因（不是"格式不好看"，是缺一层渲染）

`get_command_result`（`http.rs` 1179-1191）返回的是 MCP tool 的**文本**，即后端
`CommandToolExecutor` 里 `writeValueAsString(...)` 的结果：

```
TaskEnvelopes.of(...) + mapOf("result" to CommandResult)      // CommandToolExecutor.kt:344-354
```

实际形状（`TaskEnvelopes.kt:103-138` + `CommandResult.kt:23-36`）：

```json
{
  "taskId": "5b0c…", "status": "done", "processed": 12, "elapsedMs": 18400,
  "statusCode": 200, "message": "Agent run completed, finalizing",
  "errorCode": null, "resumable": false, "effectiveTokenBudget": 5000000,
  "statusTool": "command_status", "resultTool": "command_result", "cancelTool": "command_cancel",
  "result": {
    "summary": "…", "pageSummary": "…",
    "fields": {"title": "…"}, "links": ["…"], "xsqlResultSet": [{"plan": "Starter"}],
    "keyFindings": ["…", "…"], "filesChanged": ["pricing.csv"], "problems": ["…"],
    "workspace": "…", "errorCode": null, "resumable": false, "effectiveTokenBudget": 5000000
  }
}
```

三个调用点今天都是**直接打印这个字符串**：

| 模式 | 代码 | 现状 |
|---|---|---|
| `-i` | `complete_current` 2027-2032 | `self.out(&result)` —— 用户看到的就是这一行 JSON |
| `--follow` | `print_completion` 1390-1396 | `renderer.print(&result)`，同样原样 |
| `--wait` | `main.rs` ~16100 | 原样 |
| `agent result` | `handle_agent_result` 16649 + 16653 | 先打原始 JSON，**再**打「Key findings / Files / Problems / workspace」段落 → **同一份数据出现两次** |

外加两个隐性困扰：

- JSON 字符串里的 `\n`、`\"` 会原样出现（`summary` 多行时尤其明显）；
- 信封外壳的 `statusTool`/`resultTool`/`cancelTool`/`processed`/`statusCode` 是给机器看的，
  人读时纯噪声，且 `errorCode`/`resumable`/`effectiveTokenBudget` 在信封和 `result` 里各有一份。

**结论：需要的不是"美化 JSON"，而是按字段语义分层渲染 + 去掉重复与管道字段。**

---

## 二、目标格式

### 2.1 编码/浏览任务（有完成报告）

```
✔ Agent completed in 3m12s · 7f21ab9c · 9 rounds · 64 tools · 182.4k tokens

Summary
  Fixed the failing module build: the BOM pinned an older zip4j. `mvnw -pl
  browser4-rest -am test-compile` passes locally.

Key findings (2)
  • The failure came from the dependency BOM, not the module's own code
  • No source change was needed — the version alignment was enough

Files written (1) · workspace: ~/.browser4/agent/workspace.7f21ab9c
  browser4-dependencies/pom.xml

Problems (1)
  ⚠ The CI gate was not run (no Docker in this environment)
```

### 2.2 数据任务（`fields` / `xsqlResultSet`）

```
✔ Agent completed in 18.4s · 5b0c3f2a · 3 rounds · 39 tools · 54.4k tokens

Summary
  The pricing page lists four plans; enterprise pricing is quote-only, so the
  three published tiers were collected.

Data (3 rows × 3 columns)
  plan      price  seats
  --------- -----  -----
  Starter   19     1
  Team      49     10
  Business  89     25

  … 12 more row(s) — /result --json prints the whole envelope
```

### 2.3 失败

```
✖ failed · 7f21ab9c · 3m12s — TOKEN_BUDGET: the run aborted at the token budget

Summary
  Wrote top20.csv (18 of 20 rows) before the abort. · wrote 1 file: top20.csv ·
  resume: agent run --resume 7f21ab9c
```

（失败摘要的 `· wrote … · resume:` 尾巴是后端 `CommandStatus.kt:372-387` 拼的，渲染器只负责断行，
不重复打 `resume`。）

### 2.4 兜底

| 情况 | 渲染 |
|---|---|
| `result` 为 `null` / 缺失 | `(no result payload)` + `agent status <id>` 提示 |
| `result` 是字符串 | 去引号、按宽度折行（`"interactive result"` → `interactive result`） |
| `result` 是数字/布尔 | 一行 |
| `result` 是未知结构的对象 | 2 空格缩进的 pretty JSON，**有界**（≤40 行且 ≤宽度），末尾 `… N more line(s) — /result --json prints the whole envelope` |
| 响应根本不是 JSON（mock/旧后端） | 文本原样打印，不加任何段落头 |

---

## 三、渲染规范

按固定顺序，**有则打、无则跳过**，每段都带计数以便判断是否被折叠：

| `result` 字段 | 段落 | 规则 |
|---|---|---|
| `summary` | 正文 | 按宽度折行（保留原有换行）；无 `summary` 时改用 `pageSummary` |
| `keyFindings` | `Key findings (N)` | `• ` 列表，`take(10)`，超出 `… and N more` |
| `filesChanged` | `Files written (N)` | 每行一个路径，`take(10)`；段头附 `workspace` |
| `workspace` | 同上段尾或独立一行 | 无 `filesChanged` 时单独 `workspace: …` |
| `problems` | `Problems (N)` | `⚠ ` 列表，`take(5)` |
| `fields` | `Data` 两列表 | key/value 对齐表，值超宽截断 |
| `xsqlResultSet` | `Data (N rows × M columns)` | 复用 `main.rs::format_table`（升 `pub(crate)`），`take(20)` 行 |
| `links` | `Links (N)` | 前 5 条，其余只报数量 |
| `errorCode` / `resumable` / `effectiveTokenBudget` | — | 折进头部/结论行，**不单独打** |
| 未知键 | `Other (N fields)` | 有界 pretty JSON |

**信封字段**：`statusTool`/`resultTool`/`cancelTool`/`processed`/`statusCode`/`taskId`/`status`
在人类模式下**一律不打印**（`taskId`/`status`/`elapsedMs` 已在头部行；`statusCode` 仅失败时
并入 `errorCode` 位）；`message`/`error` 只在失败时作为 `— <原因>`。

**去重**：`agent result` 不再先打原始 JSON；`/result` 打渲染版。

**默认上限**：总行数 40、宽 `COLUMNS`（clamp 40–400，默认 100）。

---

## 四、机器出口（不能被渲染挡住）

| 需求 | 出口 |
|---|---|
| 完整 envelope | `agent result --json`（已存在，语义不变） |
| `-i` 会话内取完整 envelope | `/result --json`（新增），渲染版仍是 `/result` |
| 逐行机器流 | `--ndjson`（完全不动） |
| `--json` 全局信封 | `raw` 字段继续放原始字符串，`json_text_or_value` 不变 |

> 渲染只发生在 `!quiet_active() && !json_active()` 时；`--pretty` 目前是空转的，
> 本次让它对"未知结构兜底"生效（打完整 pretty-JSON，不折叠）。

---

## 五、实现落点

| 层 | 文件 | 改动 |
|---|---|---|
| 新模块 | `cli/browser4-cli/src/agent_result.rs` | `pub(crate) struct RenderOptions { width, max_lines, color }`；`pub(crate) fn render_result_text(task_id, body: &str, opts) -> Vec<String>`；内部 `section_summary/lists/table/fallback` |
| 复用 | `main.rs` | `format_table` → `pub(crate)`；`maybe_pretty_print_json` → `pub(crate)`（未知结构兜底用） |
| 复用 | `agent_interactive.rs` | `truncate`、`format_thousands`、新增 `wrap_text(text, width) -> Vec<String>` |
| 调用点 | `agent_interactive.rs` | `complete_current`（`-i`）与 `print_completion`（`--follow`）改为渲染；`print_result` 加 `--json` 分支 |
| 调用点 | `main.rs` | `handle_agent_result` 去掉原始 JSON + `print_agent_deliverables`（由渲染器替代）；`--wait` 完成分支同样改用渲染器 |
| 注册 | `lib.rs` | `mod agent_result;` |
| 测试 | 单元 + e2e | 见下 |

大约 250 行实现 + 250 行测试，不动后端。

---

## 六、测试

**Rust 单测（表驱动，`agent_result.rs` 内）**

1. 完整完成报告：段落顺序、计数、workspace 位置；
2. `fields` 表格：列宽对齐、值截断、空 map 不打印；
3. `xsqlResultSet` 表格：20 行截断 + 提示行；
4. `result` 为字符串/数字/布尔/`null`；
5. 未知结构：pretty + 折叠提示；`opts.full` 时不折叠；
6. 信封管道字段一个都不出现（`statusTool`/`resultTool`/`resultTool`/`processed`）；
7. 无 ANSI 时零转义；折行不破坏多字节（中文）；
8. 宽度边界：40 / 100 / 400 各一组，超宽单元格带 `…`。

**e2e（mock 后端）**

- 新场景：`command_result` 返回完整 envelope（`summary` + 3 findings + 2 files + workspace + 1 problem），
  断言 stdout 含 `Summary` / `Key findings (3)` / `Files written (2)`、**不含** `"statusTool"`；
- `agent result` 的 `Key findings` 只出现一次（去重回归）；
- `/result --json` 的输出可被 `serde_json` 解析且带 `result.keyFindings`；
- 既有 `test_e2e_agent_run_interactive_*` 全部通过（`▶ task agent-task-1`、`steering queued` 等子串不变）。

验收命令：

```bash
cd cli/browser4-cli
cargo test --bin browser4-cli
cargo test --test e2e -- --scenario 'test_e2e_agent_run_*'
```

---

## 七、风险与取舍

| 风险 | 处理 |
|---|---|
| 有人 grep `agent result` 的原始 JSON | `--json` 是官方机器口；release note 点名"人类输出改为字段渲染" |
| 折叠会藏数据 | 每段带 `(N)`、折叠行给 `/result --json`；`--pretty` 可关闭折叠 |
| 非 TTY 输出不确定 | 渲染是纯函数：宽度来自 `COLUMNS` 或默认 100（不探测终端），无 ANSI → e2e 稳定 |
| 后端加了新字段 | 未知键进 `Other (N fields)`，不丢、也不破坏版式 |
| 与 P0-1 的头部行冲突 | 头部行只由 `terminal_status_line` 产生；本设计只负责 `result` 正文，两者拼接顺序固定：头部 → 空行 → 正文 |
| 不做 | 分页交互、颜色高亮 JSON、Markdown 渲染、`--out <file>`（`/result --json` 足够） |

---

## 八、实施记录（2026-11-09）

已落地（本 PR）：

| 层 | 文件 | 改动 |
|---|---|---|
| 新模块 | `cli/browser4-cli/src/agent_result.rs`（+ 17 个单测） | `render_result_text(task_id, body, &RenderOptions)`：Summary / Key findings / Files written (+workspace) / Problems / Data（`fields` 与 `xsqlResultSet` 走 `format_table`）/ Links / `Other (N field(s))`；宽度取 `COLUMNS`（40–400，默认 100），40 行折叠；未知结构 pretty + 折叠；纯文本/JSON 字符串/裸对象/`null` 全部有确定行为；行尾空白一律裁掉 |
| 复用 | `main.rs` | `format_table` → `pub(crate)`；`mod agent_result;` |
| `-i` | `agent_interactive.rs::complete_current` + 新增 `print_payload` | 完成行之后打渲染段落；`raw` JSON 字段保持原样 |
| `--follow` | `agent_interactive.rs::print_completion` | 同一渲染器（颜色沿用 `Renderer.color`） |
| `/result` | `agent_interactive.rs::print_result(argument)` | 默认渲染；`--json` 打原始 envelope；非法参数给用法；`/help` 同步 |
| 行为修正 | `agent_interactive.rs::start_task` + 新 `poll_and_recover` | 提交后立刻拉一次事件（原来要等下一个 400 ms tick）：首屏更快，且"已结束的任务"在下一条输入前就渲染完 —— 管道 stdin 的会话原先会在首次轮询前退出，结果静默丢失（新增 e2e 正是靠这条修正才能确定性通过） |
| 文档 | `references/agent.md`（新增 “Result rendering” 段、`-i` 示例、`/result [--json]`）、`SKILL.md`、`help.rs` 的 `agent run` Notes | 输出格式与机器出口 |

测试证据：

- `cargo test --bin browser4-cli`：**2085 passed / 1 failed**，唯一失败是
  `daemon::tests::wmi_spawned_backend_survives_a_hostile_process_tree_kill`
  （`spawn_backend_command must create the backend through Win32_Process.Create`）——
  与本改动无关的 Windows/WMI 环境问题，单独重跑同样失败，`daemon.rs` 未被修改。
- `cargo test --test e2e -- --scenario 'test_e2e_agent_run_*'`：**26/26 通过**，含新增
  `test_e2e_agent_run_interactive_renders_the_result_sections`（断言段落存在、`statusTool`/`cancelled`
  等管道字段不出现）与 `test_e2e_agent_run_interactive_result_json_prints_the_raw_envelope`
  （`/result --json` 仍是原始 envelope）。

未做（2026-11-09 记录，2026-11-10 已补两项）：

1. ~~`agent result` / `agent run --wait` 仍打印原始 JSON~~ → 已完成，见 §九。
2. 完成行统一（计划 P0-1）、token 消耗摘要（P1-4）、心跳（P1-3）等仍在计划里。
3. ~~`--pretty` 尚未接到渲染器~~ → 已完成，见 §九。

## 九、续修（2026-11-10，接会话 544bea63 的收尾）

| 项 | 改动 |
|---|---|
| `agent result` | 删掉原始 JSON 那行与 `print_agent_deliverables`（同一份 `keyFindings`/`filesChanged`/`problems` 会打两遍），改走 `render_result_text`；失败/取消仍保留 verdict 行（reason、budget、`resume:`），`--json` 语义不变 |
| `agent run --wait` | 完成行去掉尾随冒号（与 `-i`/`--follow` 对齐），正文改用同一渲染器；`json_field("raw", …)` 不变 |
| `--pretty` | `RenderOptions::with_full`：四个入口（`agent result`、`--wait`、`--follow`、`-i` / `/result`）都传 `pretty_active()`，显式关闭 40 行折叠；各段落自身的 `take(N)` 上限保留 |
| 折叠提示 | `RenderOptions::with_json_hint`：折叠行按入口给可执行命令——`-i` 给 `/result --json`，`agent result` / `--wait` / `--follow` 给 `agent result <id> --json`（原实现一律写 `/result --json`，在非交互入口是错的） |
| 复用 | `agent_interactive::color_enabled` 升为 `pub(crate)`，三处入口着色一致 |

测试：

- `cargo test --bin browser4-cli`：**2088 passed / 1 failed / 4 ignored**，新增
  `agent_result::tests::full_rendering_does_not_fold_the_payload` 与
  `the_fold_line_names_the_json_command_of_its_entry_point`。唯一失败仍是
  `daemon::tests::wmi_spawned_backend_survives_a_hostile_process_tree_kill`：已在
  **HEAD `7f212f9b68` 的干净 worktree** 中复现同一 panic（`daemon.rs:10980`，
  `spawn_backend_command` 回落到 `LaunchedBackend::Child`，即本机 `Win32_Process.Create`
  不可用），与本次改动无关，`daemon.rs` 未被修改。
- `cargo test --test e2e -- --scenario 'test_e2e_agent_run_*'`：**26/26 通过**（`--wait` 场景
  新增「出现 Summary 段落、不出现原始 `"summary"`」断言）。
- `cargo test --test e2e -- --scenario 'test_e2e_agent_result_*'`：**2/2 通过**，含新增
  `test_e2e_agent_result_renders_the_payload_sections_once`（段落齐全、`Key findings`
  恰好出现一次、`statusTool`/`"keyFindings"` 等管道字段不出现）。
- `cargo test --test e2e -- --group agent`：**36/36 通过**（42 选中，6 个需要真实服务端/浏览器的场景跳过）。
- 断言更新：`scenarios/mock_server.rs` 三处 `agent result` 断言从原始 JSON 的 `"summary"`
  改为渲染后的 `Summary`。
- 文档：`references/agent.md`（渲染覆盖四个入口、`--pretty` 关折叠、`agent result` 示例换新格式）、
  `SKILL.md`、`README.md` / `README.zh.md` / `cli/browser4-cli/README.md`（`agent result` 行）、
  `help.rs`（`agent result` Notes）、`tips.rs`（`-i` 那条补 `/result [--json]`）、
  `docs-dev/copilot/agent-run-flow.md`。

仍未做：完成行统一（P0-1）、完成行 token 消耗摘要（P1-4）、静默期心跳（P1-3）。

注：`cargo fmt --check` 在本仓本来就大面积不通过（`main.rs` 535 处、`commands.rs` 54 处），
未执行 `cargo fmt`（会产生与本次无关的全仓改写）；新增代码沿用周边风格。

