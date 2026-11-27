# `agent run -i` 交互系统优化方案（仅计划，未改代码）

- 日期：2026-11-22
- 状态：**计划已确认，Phase 0–3 已实现（2026-11-22）；Phase 4 按决策 A 暂缓（只补文档）**
- 范围：`cli/browser4-cli/src/line_editor.rs`、`cli/browser4-cli/src/agent_interactive.rs`
  （`Repl` / `TaskFollower` / `Renderer`）、`cli/browser4-cli/src/main.rs`（`print_stdout_line`）、
  `cli/browser4-cli/src/state.rs`、`http.rs`、`help.rs`、`tips.rs`、`commands.rs`（`-i` 描述）、
  `cli/browser4-cli/tests/e2e/`（mock 脚本能力 + 交互场景）、相关文档
- **不改**：后端事件 / steering / 审批语义，`--ndjson` / `--json` 的机器输出纯度，
  管道（非 TTY）行为，默认 `agent run`（无 flag）行为，
  以及 2026-11-09 输出格式计划已完成的行文规范（`Renderer` 的每一行保持原样）

---

## 0. TL;DR

交互层现在**能力齐、手感糙**：会话命令（`/status` `/stats` `/result` `/resume` `/rewind`
`/add-dir` `/approve` …）已经比大多数 CLI agent 全，但「**终端这一层**」还停在
"能读到一行字"的水平，并留下 3 个真实的正确性问题：

| 级别 | 问题 | 一句话 |
|---|---|---|
| P0 | 终端状态泄漏 | Unix 上 `/quit` 后 tty 停在 `-icanon -echo`（`RawMode` 的 Drop 在已被阻塞的 stdin 线程里，进程退出时不执行） |
| P0 | 输入行与输出流互毁 | 跑任务时打字，流式输出会接在你正在输入的那一行后面；下一次按键又把上一行文字重画一遍 |
| P0 | 管道被污染 | `agent run -i \| tee log` 时 stdin 是 TTY、stdout 不是，编辑器仍接管 stdin，把提示符与 `\r\x1b[K` 写进管道 |
| P1 | 行编辑只做了半套 | Ctrl+A/E 实际未实现（`decode_key` 把 0x01/0x05 归入 `Ignored`）、Tab 插字面制表符、无补全、历史不落盘、长行换行重画错位、粘贴多行 = 一个任务 + N-1 次 steering |
| P1 | 运行期没有状态可见面 | 提示符固定 `agent> `、看不到排队几条 steering、审批请求不主动提示（待决摘要只在非 TTY 会话结束时打印，能批准的人反而没有线索） |
| P2 | 会话不连续 | 每次 `-i` 都是新的 `interactive-<hash>`，关掉终端就丢了这条会话（后端上下文有 12h TTL，但 CLI 不记 id） |

方案按「**终端所有权 → 输入能力 → 会话可见性 → 连续性 → 跨平台**」分 5 个 PR，
每个 PR 独立可回滚；P0 三项一个 PR 收掉，不引入新依赖（Windows 一项除外，见 §9）。

---

## 1. 现状盘点（避免重复造轮子）

| 能力 | 位置 | 备注 |
|---|---|---|
| 行编辑器（cbreak + 键解码 + 行缓冲 + 内存历史） | `line_editor.rs:24` `Key`、`:61` `decode_key`、`:159` `EditorState`、`:331` `Editor` | `stty` 走子进程（`:432` `RawMode`），Unix-only |
| 提示符 / 横幅 / 输出门控 | `agent_interactive.rs:2949` `prompt`、`:2907` `banner`、`:2960` `out`、`:1046` `Renderer::print` | 提示符常量 `line_editor::PROMPT = "agent> "`（`:328`） |
| 主循环（行 / tick / Ctrl+C 三路 select） | `:2972` `run` | 400ms tick（`:38`） |
| 任务提交 / 跟随 / 心跳 / 收尾 | `:3336` `start_task`、`:1980` `TaskFollower`、`:2284` `emit_heartbeat`、`:3650` `complete_current` | |
| steering / follow-up | `:3308` `steer`、`:3259` `follow_up`、`http.rs:1242` | 已实现路由判定（`:3051` `handle_line`） |
| 会话命令 21 条（含别名） | `:3731` `handle_slash_command`、`:3948` `print_help` | |
| 审批（人工） | `:3794` `print_access`、`:3921` `print_pending_summary_if_any`、`http.rs:1280` | 只能 `/access` 主动查 |
| 结果渲染 / 统计 / 预测下一步 | `Renderer`、`:4007` `print_stats`、`:3151` `offer_next_after` | 2026-11-09/14 计划已完成 |
| 会话上下文（跨任务真实对话） | 后端 `SessionConversation` + `agent-run-flow.md` §9 | 后端侧已成熟 |

> 结论：**不要重做输出格式与会话语义**。本轮只补「终端所有权 + 输入能力 + 可见性 + 连续性」。

---

## 2. 问题清单

### 2.1 A 类：终端所有权（P0）

| # | 问题 | 证据 | 用户代价 |
|---|---|---|---|
| A1 | **终端状态泄漏**：`RawMode` 在 stdin 线程里创建并被 `_cbreak` 持有（`agent_interactive.rs:4532`），`/quit` 后进程退出、线程仍阻塞在 `read()`，Drop（`line_editor.rs:459`）不执行 ⇒ tty 留在 `-icanon -echo` | 只有 `RawMode::Drop` 一条恢复路径 | 退出后 shell 不回显，必须手工 `stty sane`；Linux/macOS 必现 |
| A2 | **输入行与输出流互相踩**：所有人类输出走 `print_stdout_line`（`main.rs:262`），完全不知道编辑器当前画着一行没换行的 `agent> <半句话>`；`Editor::feed`（`line_editor.rs:372`）每按一键重画一次 | 输出路径与编辑器零交互；`prompt()`（`:2949`）只在 `prompt_pending && current.is_none()`（`:2994`）时重画 | 跑任务时打字：文字被事件行吃掉/重复；用户不知道哪一行是自己的输入 |
| A3 | **stdout 不是 TTY 时仍接管 stdin**：`owns_prompt = self.tty && human_output_enabled(...)`（`:2987`），`self.tty` 只看 stdin（`:2852`） | `Editor::new(..., owns_prompt && io::stdin().is_terminal())`（`:4524`） | `-i \| tee log` / `> log` 时提示符与 ANSI 写进管道；日志不可读、机器消费方被污染 |
| A4 | **长输入行换行后重画错位**：`render`（`line_editor.rs:309`）只做 `\r\x1b[K` + 按 `tail` 宽度左移，没有"占了几行"的概念，也拿不到终端宽度 | 无宽度来源（`COLUMNS` 只在结果渲染用了，`agent_interactive.rs:753`） | 任务/steering 超过一行时出现残字、光标跳错位置，CJK 更明显 |

> **与旧决议的关系**：2026-11-09 计划把"运行期重打提示符"列为**有意不做**（`20261109-agent-run-interactive-output-format-plan.md:82`），
> 理由是"提示符会插进步骤流"。当时 `agent>` 是 cooked-mode 读行，屏幕上是行缓冲，不会与输出争行。
> 2026-11-12 的 line editor（`f35519ae03`）上线后，**用户每按一键就会往 stdout 画一次行**，
> 原前提已不成立 ⇒ 本条不是"重开旧议题"，而是"新实现引入的新问题"。

### 2.2 B 类：输入能力（P1）

| # | 问题 | 证据 | 修法方向 |
|---|---|---|---|
| B1 | **Ctrl+A / Ctrl+E 未实现**（commit message 写了，代码没做）：`decode_key` 把 `0x01..=0x1f` 一律 `Ignored`（`line_editor.rs:87`） | `Key` 无 Home/End 的 Ctrl 绑定 | 补 `Ctrl+A/E/U/K/W`、`Alt+←/→`（按词移动） |
| B2 | **Tab 插入字面制表符**（`line_editor.rs:85`）：任务文本里混入 `\t`，且没有补全 | | 把 Tab 变成"补全请求" |
| B3 | **历史不落盘**：`EditorState.history`（`line_editor.rs:162`）随进程消失 | 无状态目录读写 | `<state dir>/agent-repl-history`，上限 200 条，0600，可关 |
| B4 | **粘贴多行 = 多个任务**：`feed()` 把每个 `\r`/`\n` 都当 Enter（`line_editor.rs:232`），一次 `read()` 里的 3 行 ⇒ 1 个任务 + 2 次 steering | `spawn_stdin_reader` 逐条 `tx.send`（`:4539`） | bracketed paste（`\x1b[?2004h`）+ 尾部 `\` 续行 |
| B5 | **Ctrl+C 在空闲态结束会话**：SIGINT → `current` 为 `None` → `break`（`:3011-3027`） | `Key::Interrupt`（`line_editor.rs:36`）实际不可达（ISIG 开着，字节进不了解码器） | 空闲态 Ctrl+C = 清行；空行再按一次或 Ctrl+D 才退出 |
| B6 | **SIGTERM / SIGHUP 未处理**：与 A1 同源，进程被杀时终端不恢复 | 无信号分支 | 在 `run()` 的 select 里加 SIGTERM/SIGHUP → 恢复 tty 后退出 |

### 2.3 C 类：会话可见性（P1）

| # | 问题 | 证据 | 用户代价 |
|---|---|---|---|
| C1 | **提示符不表达状态**：`agent> ` 恒不变，运行中打字等于盲打 | `line_editor::PROMPT`（`:328`）+ `:2949` | 不知道这一行会变成 steering |
| C2 | **steering 队列不可见**：只回一条 `↪ steering queued`（`:3313`），没有"N 条待投递"，`steer`/`undelivered` 事件也不顶计数 | | 连打三句后不知道几句会生效 |
| C3 | **审批请求沉默**：只能 `/access` 主动查，运行中不提示；退出时的待决摘要 `print_pending_summary_if_any` 反而对 TTY 早退（`:3921-3924`，注释理由"非 TTY 无法自行批准"），于是"能批准的人"唯一没有主动线索 | | coding 任务被护栏挡住时，用户以为卡了；`/quit` 时待决请求静默过期 |
| C4 | **只能看最后一个任务**：`/result`（`:4099`）固定 `last_task`，无 `/tasks` | 会话里已经跑了 5 个任务 | 想回看第 2 个任务只能退出去 `agent result <id>` |
| C5 | **`/status` 缺"现在在干什么"**：只有 `status · N step(s) — message`（`:1662`），无耗时、无最近一步 | | 长任务里反复敲 `/status` 仍答不上"它在动吗" |
| C6 | **`/help` 平铺 20 余行、无 `/help <cmd>`、未知命令无建议**（`:3948`、`:3771`） | | 新用户记不住；`/stat` 只回一句"unknown" |

### 2.4 D 类：连续性与跨平台（P2）

| # | 问题 | 证据 | 说明 |
|---|---|---|---|
| D1 | **会话不连续**：无 `--continue`，进程退出即换 `interactive-<hash>`（`:2709`） | 后端会话上下文有 TTL、可续 | 记录最后一条匿名会话 id，`-i --continue` 复用 |
| D2 | **Windows 无行编辑**：`RawMode::enable` 依赖 `stty`（`line_editor.rs:435`），Windows 上回落到 `read_until`（`:4549`），历史/补全/粘贴全部不可用 | Windows 是仓库一等开发平台 | 见 §9 决策点 4 |
| D3 | **无法排队下一个任务**：跑着时只能 steer 或 `/cancel` | | 可选：`/next <task>` 在当前任务结束后自动开始 |

### 2.5 明确**不做**

- 全屏 TUI / spinner / 底部状态栏 / 引入 `crossterm` / `reedline` / `rustyline`
  （沿用 2026-11-09 计划的决议：与事件流争行、依赖成本高）
- Markdown 渲染模型文本；多语言（CLI 输出保持英文）
- 改造输出行文规范（`Renderer` 的 15 种行已定型）
- 后端协议变更（Phase 2 只需要 mock 侧多一个"可脚本化审批视图"，见 §6）

---

## 3. 目标交互模型

### 3.1 单行所有权（核心不变量）

> **终端上"当前行"归编辑器所有；任何人类输出都必须先借走再归还。**

定义唯一出口 `console::write_line(line)`：

```
lock(state)
  if 有草稿或提示符已画出: write("\r\x1b[K")     // 借走当前行
unlock(state)
write(line + "\n")                                // 输出自己占满整行
lock(state)
  write(render(prompt, state))                    // 归还：重画提示符 + 草稿 + 光标
unlock(state)
flush
```

所有人类输出（`Repl::out`、`Renderer::print`、`--follow` 的行、横幅、`▶ task` 行）
统一走这个函数；非 TTY / `--json` / `--quiet` / `--ndjson` 下它是直通（与原行为逐字节一致）。
`crate::print_stdout_line`（`main.rs:262`）是所有人类输出的**唯一**汇合点（含 `renderer.print`、
`sanitize` 相关路径），在这一层安装"控制台"即可，不需要改几十处调用。

锁序固定为 `console.state → stdout`，且**持有 state 锁时不写整行 payload**，只写擦除序列；
这样与 `Editor::feed` 的重画路径同序，无死锁面。

### 3.2 状态机与提示符

| 状态 | 条件 | 提示符 | 裸文本语义 |
|---|---|---|---|
| `Idle` | 无运行中任务 | `agent> ` | 新任务 |
| `Running` | `current.is_some()` 或后端仍报 running | `steer> ` | steering |
| `Running(attention)` | 运行中且存在未决审批请求 | `steer!> ` | steering（提示 `/approve`） |
| `Queued(n)` | 有 N 条 steering 已接受未投递 | `steer[N]> ` | steering |
| `Detached` | 跟随器释放但任务仍 running | `steer> ` | steering（接受后自动重挂，已有 `attach_if_detached`） |
| 机器模式 | `--json` / 非 TTY stdout | 不画 | 原行为 |

提示符**不显示耗时**（耗时归心跳行与 `/status`），避免每秒重画。

### 3.3 输入路由

| 输入 | Idle | Running / Detached |
|---|---|---|
| 裸文本 | 新任务 | steering（已有） |
| `/cmd …` | 本地命令 | 本地命令（已有） |
| `1..3` | 选中预测项（已有） | steering（数字视为文本，已有） |
| `/approve`（无参） | 无待决请求则报用法 | 唯一待决请求直接批准（新增便捷路径） |
| `/next <task>` | 报"没有任务在跑，直接提交" | 任务结束后自动开始（D3，可选） |

### 3.4 审批时间线（C3）

1. **运行中轮询**：在既有 400ms tick 上加节流（2.5s 一次、1.5s 超时），
   仅当本任务可能产生审批时轮询（`coding` 或 access 预设非 `read`），失败静默；
2. **首次出现**：按 `requestId` 去重，打印一行
   `⚠ access request req-1 · fs.write src/generated — /approve req-1 · /deny req-1`
   （不写"任务正在等待"：`coding.requestAccess` 返回即继续，任务不被阻塞）；
3. **提示符**切到 `steer!>`，直到 pending 清空；
4. **任务结束**（含 `/cancel`、失败、`/quit`）：TTY 与非 TTY 都打印待决摘要
   （去掉 `:3921` 的 TTY 早退）；会话还在时给 `/approve <id>`，会话已退出（`/quit` 之后）
   则打印 `POST /api/agent/<task>/access/<req>/approve` 与 task id，让人还有补救路径。

### 3.5 不变量（每阶段都要守）

- 非 TTY（管道）输出与今天**逐字节一致**（e2e 全部走这条路径）；
- `--json` 只有一条信封、`--ndjson` 每行一个 JSON（新提示语一律走 `human_output_enabled`）；
- `--quiet` / `--json` 下不画提示符、不画心跳（`NO_COLOR` 只去颜色，不影响提示符）；
- 后端事件语义、steering/follow-up 通道、审批 REST 契约不变。

---

## 4. 分阶段实施

| Phase | 收掉的问题 | 预估体积 |
|---|---|---|
| 0 终端所有权 | A1 终端恢复 · A2 输入/输出仲裁 · A3 管道纯度 · A4 宽度换行 | ~600 行（含单测） |
| 1 输入能力 | B1 按键 · B2 补全 · B3 历史 · B4 粘贴/续行 · B5 Ctrl+C · B6 信号 | ~500 行 |
| 2 会话可见性 | C1 提示符状态 · C2 steering 计数 · C3 审批提示 · C4 `/tasks` `/result <id>` · C5 `/status` · C6 帮助 | ~450 行 |
| 3 连续性 | D1 `--continue`（D3 `/next` 可选） | ~150 行 |
| 4 跨平台 | D2 Windows（决策后） | ~200 行 |

### Phase 0 — 终端所有权（P0，1 个 PR）

改动：

| 文件 | 改动 |
|---|---|
| `line_editor.rs` | `Console`（进程级单例，持 `EditorState` + 提示符 + 输出汇合点）；`flush_line` 的纯函数 `render_output(prompt, state, line, width) -> Vec<u8>`；`RawMode` 改为**由 REPL 持有**的 guard，并加 `restore_now()` |
| `agent_interactive.rs` | `console::install(...)`（仅 TTY 且 human 模式）；`run()` 持有 guard，退出 / `Err` / SIGTERM 路径显式 restore；`owns_prompt` 加 stdout 也是 TTY 的条件 |
| `main.rs` | `print_stdout_line` 改走 `console::write_line`（无 console 时直通） |
| `line_editor.rs` | `render` 增加宽度参数（`stty size` 缓存 → `COLUMNS` → 100），按行数上移重画（A4） |
| `agent_interactive.rs` | Phase 0 就加最小 SIGTERM/SIGHUP 恢复（`main.rs:33192` 走 `std::process::exit`，正常退出靠 guard 的 Drop 即可，信号退出必须显式 restore） |

验收（可机器检查）：

- `printf 'x\n/quit\n' | browser4-cli agent run -i` 输出保持不变（e2e 全绿）；
- 单测：`render_output` 的字节序（擦除 → 行 → 重画）、3 行输入的 `\x1b[{n}A` 序列、CJK 宽度；
- 单测：`Console` 装到内存 `Vec<u8>` 上，断言"输出行之后必有提示符重画"；
- 手工（Linux TTY）：`-i` → `/quit` → `stty -a | grep -e icanon -e '-echo'` 恢复原值；
- 手工：`-i` → 跑任务 → 连续打字，屏幕始终只有一行输入、事件行完整；
- 手工：`-i | tee /tmp/log`，日志里**不含** `agent> ` 与 `\x1b[`；
- 手工：任务结束 / 取消 / 审批提示插入之后，已敲了一半的草稿仍在（不丢字、不重复）。

风险：所有人类输出都多了一次加锁/重画 ⇒ 高频事件流（每秒几十行）的重画开销。
对策：`Console` 记录"当前行是否有草稿"，**空草稿时不重画**（省掉绝大多数重画）；
重画只在 `state.chars` 非空或提示符确实被擦掉时进行。

### Phase 1 — 输入能力（P1，1 个 PR）

| 项 | 做法 |
|---|---|
| B1 按键 | `Key::Home/End/DeleteWordBack/DeleteWordForward/DeleteToEnd/DeleteToStart/ClearScreen`；`decode_key` 补 `0x01/0x05/0x0b/0x15/0x17/0x0c`，`Alt+←/→` = `ESC b`/`ESC f` |
| B2 补全 | `Key::Tab` → `Outcome::Complete { line, cursor }`；**候选由 REPL 计算**（编辑器不发回调）：`/` 命令名、命令参数（`on/off`、`clear`）、任务 id（`/result` `/resume` `/rewind` `/stats`）、目录（`/add-dir`）；连按两次列出候选；纯函数 `complete(line, cursor, ctx) -> Completion` |
| B3 历史 | `<state dir>/agent-repl-history`（每行一条，JSON 转义换行），启动载入、提交追加、上限 200、连续去重；权限 0600（Unix），`BROWSER4_CLI_NO_HISTORY=1` 关闭；`/history [n]` 与 `/history clear` |
| B4 粘贴/多行 | 启用 bracketed paste；粘贴期间 `\r`/`\n` 进缓冲区不提交；行尾 `\` 续行（续行提示符 `...> `，提交时拼接） |
| B5 Ctrl+C | SIGINT 分支改为三态：运行中→取消任务（现状）；空闲且有草稿→清行并回显 `^C`；空闲且空行→退出（或 1s 内第二次） |
| B6 信号 | 细化 Phase 0 的信号退出：恢复 tty 后再提示"任务仍在跑"（沿用 `:3038` 文案）并给出口命令 |

验收：`line_editor` 单测覆盖每个键、粘贴序列 `\x1b[200~…\x1b[201~`、历史加载/上限/去重/关闭开关、
补全的 5 类候选；手工 TTY 清单见 §6。

### Phase 2 — 会话可见性（P1，1 个 PR）

| 项 | 做法 |
|---|---|
| C1/C2 提示符 | `Console::set_prompt(&str)`；`Repl` 计算状态（`prompt_for(state)` 纯函数）+ steering 队列计数（本地 `accepted - delivered`，`steer`/`undelivered`/`follow_up` 事件归零或递减） |
| C3 审批 | §3.4；`/approve` `/deny` 无参 = 唯一待决；`/access` 保留 |
| C4 任务列表 | `/tasks [n]`：会话内任务（`tasks_json` + 运行中）→ `id · status · 时长 · 任务前 50 字`；`/result [<task-id>] …` 支持指定 id（后端 `command_result` 本来就按 id 取） |
| C5 `/status` | 追加耗时与最近一步：`task <id> · running · 12 step(s) · 1m23s — #7 b4.run(args="…"), 12s ago` |
| C6 帮助 | `/help` 分三组（任务 / 观察 / 会话设置），`/help <cmd>` 单条详细说明，未知命令给最近似建议（编辑距离 1–2） |

验收：mock e2e 新增
`..._reports_pending_access_while_running`、`..._tasks_and_result_by_id`、
`..._status_includes_elapsed_and_last_step`、`..._unknown_command_suggests`；
单测覆盖 `prompt_for` / `access_notice_dedupe` / `complete` / `format_tasks`。

> mock 现成能力：`tests/e2e/mod.rs:2269` 的 `GET /api/agent/{id}/access` **无条件返回一条 pending**。
> 新增审批提示后，所有 `-i` e2e 都会看到这一行 ⇒ 顺手把 mock 改成可脚本化
> （`set_agent_access_view(task_id, view)`，默认空 pending），`slash_access` 场景显式脚本化。

### Phase 3 — 连续性（P2，1 个 PR）

| 项 | 做法 |
|---|---|
| D1 `--continue` | 会话启动时把匿名 session id 写进 state（`state.rs` 新键 `last_interactive_session_id` + 时间戳）；`agent run -i --continue` 复用它（超过后端 TTL 时后端自然空上下文，CLI 打一行说明）；横幅第二行提示 `-s <id>` |
| `/next`（可选） | `Vec<String> pending_tasks`；任务结束后取一条 `start_task`；`/next` 无参列出队列 |

验收：`--continue` 单测（state 缺省 / 过期 / 与 `-s` 冲突时报错），mock e2e 断言
第二个进程提交到同一 `sessionId`。

### Phase 4 — 按决策 A 暂缓（2026-11-22）

Windows 上的行编辑这一轮**不做**，只把差异写进文档，并留下实现入口：

| 位置 | 内容 |
|---|---|
| `skills/browser4-cli/references/agent-progress-sessions.md` | 新增 "Windows console" 段：没有 `stty` 时沿用控制台自带的行编辑，`←/→`、`Home/End` 与控制台自身的当前行历史照常；**没有**持久 `↑/↓` 历史、`Tab` 补全、括号粘贴、`Ctrl+L`；steering / `/` 命令 / 提示符状态 / Ctrl+C 行为一致；粘贴多行在 Windows 上逐行提交 |
| `cli/browser4-cli/src/help.rs` | `agent run` 帮助补两行同样的说明 |
| `SKILL.md` | `-i` 条目补一句降级说明 |
| `cli/browser4-cli/src/line_editor.rs` | `RawMode` 的文档注明 `None` 即"控制台自带行编辑"，并写明 follow-up 的入口（`SetConsoleMode(ENABLE_VIRTUAL_TERMINAL_INPUT)` + `ReadConsoleInputW`，复用 `Console`/`EditorState`） |

理由：这一轮的目标是**修掉 Unix TTY 上的真实缺陷**（终端不恢复、输入行被输出吃掉、管道被污染）并补齐行编辑与会话可见性；
Windows 不会比改动前更差（改动前也只有控制台自带编辑），而引入 `windows-sys` 会把一个纯 CLI 侧重构变成跨平台输入层改造，
不值得和前三个 Phase 混在一个评审单元里。做的时候预计 1–1.5 天，删掉上面三处"降级"文字即可。

### 唯一剩余验收：Linux/macOS 真实 TTY

本机是 Windows，`stty` 不存在 ⇒ cbreak 分支**一次都没在真终端跑过**。上线前请在 Linux/macOS 上过一遍：

1. `agent run -i "…"` 跑任务时连续打字：只有一行输入、事件行完整、草稿不丢；
2. `/quit` 后 `stty -a` 与进会话前一致（`icanon`/`echo` 已恢复）；
3. 粘贴三行任务：只提交 **一次**；
4. `↑`/`↓` 跨进程历史（另一个终端里再进一次 `-i` 仍能用），`/history`/`/history clear` 正常；
5. `Tab`：`/rewi`→`/rewind `、`/sta`→`/stat`、`/coding on`、`/result <tab>` 任务 id、`/add-dir <tab>` 目录；
6. 空闲 Ctrl+C 清行、二次 Ctrl+C 退出；空行 Ctrl+D 退出；
7. `agent run -i | tee log`：日志里没有 `agent>` 也没有 `\x1b[`；
8. 输入超过一屏宽（含中文）时重画不错位；
9. `/quit` 后终端也不残留 `agent> `（`Console::release`）；
10. Ctrl+L 清屏后提示符与草稿还在。

---

## 5. 关键设计细节

1. **回调倒置**：编辑器不持有"补全器/历史存储"回调，而是把 `Complete` / `Interrupt`
   作为 `Outcome` 交回 REPL，REPL 拿到候选后调 `editor.set_text(line, cursor)`。
   输入通道由 `mpsc::UnboundedReceiver<String>` 升级为 `Receiver<Input>`（`Line` / `Complete` / `Interrupt`）。
   好处：`line_editor` 保持零业务知识，全部可单测。
2. **锁序**：`Console.state` → `stdout`；payload 写入时不持 `state` 锁；`feed`/`redraw`/`write_line`
   共用同一把锁，避免"输出写一半被重画截断"。
3. **宽度**：`stty size` 在 `RawMode::enable` 时读一次（失败回落 `COLUMNS` → 100），
   `SIGWINCH` 暂不处理（轮询 `stty` 的成本不值当；阶段 1 的 Ctrl+L 可强制重画）。
4. **历史格式**：一行一条、JSON 字符串（避免任务里的换行破坏文件）；读取时坏行跳过；
   不做跨机器同步；文件头注释由文件名与文档承担。
5. **审批轮询成本**：每任务最多 1 次/2.5s；只在 `coding=true` 或 `access != "read"` 时启用；
   `/quiet` 下不打印但计数（保证 `steer!>` 仍准确）。
6. **失败一律本地化**：新增的轮询（审批视图）失败即静默，绝不因为提示符/审批/统计去动任务
   （沿用 `--wait` 统计 2s 预算的既有原则）。

---

## 6. 测试与验收矩阵

| 层 | 内容 | 命令 |
|---|---|---|
| 单测（bin） | 键解码、粘贴、补全、历史、`render_output` 字节序、`prompt_for`、审批去重、`/tasks` 渲染、`--continue` 解析 | `cd cli/browser4-cli && cargo test --bin browser4-cli` |
| mock e2e | 既有 9 个 `test_e2e_agent_run_interactive_*` 不破 + Phase 2 新增 4 个 + Phase 3 新增 1 个 | `cargo test --test e2e -- --scenario 'test_e2e_agent_run_interactive*'` |
| 回归 | 非 TTY 输出与 `--json` 纯度 | 同上（全部 scenario 走管道） |
| 手工 TTY（必做，无法自动化） | ① `-i` 跑任务时连续打字；② 粘贴 3 行任务 → 只提交 1 个；③ ↑/↓ 历史跨进程；④ Tab 补全 5 类；⑤ 空闲 Ctrl+C 清行、再按退出；⑥ `/quit` 后 `stty -a` 正常；⑦ `-i \| tee` 日志干净；⑧ 长任务（>1 屏宽）输入重画 | Linux/macOS 终端，Windows conhost 与 Windows Terminal 各一遍 |
| Windows | 控制台可用、无转义噪声、提示符正常（Phase 4 未做时只保证降级可用） | 手工 |

---

## 7. 文档更新清单（按 AGENTS.md「Documentation Update Rule」）

1. `skills/browser4-cli/SKILL.md`（`agent run` 条目里的 `-i` 描述与命令表）
2. `skills/browser4-cli/references/agent-progress-sessions.md`
   （§`agent run -i`：提示符状态、`/tasks`、`/result <id>`、`/history`、粘贴、审批提示、`--continue`）
3. `skills/browser4-cli/references/agent.md`（`-i` 行、命令表）
4. `skills/browser4-cli/references/agent-access.md`（§"Interactive CLI"：审批提示与无参 `/approve`）
5. `cli/browser4-cli/src/help.rs`（`:2308` `-i` 段落、`:2410` `/stats` 段落）
6. `cli/browser4-cli/src/tips.rs`（`:327` `-i` 提示，补 `/tasks`、`--continue`）
7. `cli/browser4-cli/src/commands.rs`（`:4266` `-i` 选项描述列出新命令）
8. `README.md` / `README.zh.md`（`agent run` 行的 `-i` 子句）
9. `cli/browser4-cli/README.md`
10. `docs-dev/copilot/agent-run-flow.md` §8（输入通道从 `String` 变 `Input`、提示符状态机）

---

## 8. 风险与回滚

| 风险 | 说明 | 对策 |
|---|---|---|
| 输出路径成为热点 | 每个事件行都要过 `Console` 锁 | 空草稿直通（不加锁不重画）；TTY-only 安装 |
| 机器输出被污染 | 新提示语写进 `--json`/`--ndjson` | 全部走 `human_output_enabled`；e2e 断言保持 |
| 死锁/重画截断 | 输出与 `feed` 并发 | 固定锁序 + "payload 不持锁"；`Console` 内不做阻塞 IO |
| 历史泄漏敏感任务文本 | 任务里可能有 URL/token | 0600、上限 200、`BROWSER4_CLI_NO_HISTORY=1`、文档写明 |
| 提示符变化影响脚本 | 现有 e2e 断言 `▶ task` 等子串，不断言提示符 | 不改变 `▶ task <id>` 等既有行；帮助文档同步 |
| Windows 分支 | 若引入 `windows-sys` | 单独立项，默认不进前 3 个 PR |

回滚：每个 Phase 独立 PR；Phase 0 的 `Console` 若出问题，`print_stdout_line` 回退直通即恢复原行为
（不涉数据迁移；历史文件删掉即无副作用）。

---

## 9. 决策点（请确认；括号内为推荐值）

1. **提示符是否随状态变化**（推荐：是，`agent>` / `steer>` / `steer[N]>` / `steer!>`）。
   代价是文档里的示例需要同步，收益是"这一行会变成 steering"不再靠记。
2. **Ctrl+C 空闲态语义**（推荐：清行；空行再按一次或 Ctrl+D 才退出）。
   现状是直接退出会话，用户想清掉打了一半的任务会被踢出。
3. **历史是否落盘**（推荐：落盘，0600，200 条，`BROWSER4_CLI_NO_HISTORY=1` 可关）。
   若出于隐私考虑不落盘，Phase 1 只做按键与补全。
4. **Windows 本轮是否做行编辑**（推荐：本轮不做，Phase 4 单独立项；
   若要求"Windows 一等"，则加 `windows-sys` 依赖走 VT 输入模式）。
5. **审批轮询范围**（推荐：只在 `coding=true` 或 `access != read` 时轮询，2.5s/次；
   全域轮询会多一次常驻请求，但能覆盖 access 为 `read` 却调用 `requestAccess` 的边缘情形）。
6. **`/next` 任务队列**（推荐：本轮不做，等真实需求；`/followup` 已覆盖"再加一句"的场景）。

---

## 10. 工作量（单人，含测试与文档）

| Phase | 内容 | 估时 |
|---|---|---|
| 0 | Console 单行所有权、终端恢复、管道纯度、宽度换行 | 1.5 天 |
| 1 | 按键、补全、历史、粘贴/续行、Ctrl+C、信号 | 2 天 |
| 2 | 提示符状态、steering 计数、审批提示、`/tasks`、`/result <id>`、`/status`、帮助 | 1.5 天 |
| 3 | `--continue`、state 落盘（+ 可选 `/next`） | 0.5 天 |
| 4 | Windows（决策后） | 1–1.5 天 |
| — | 手工 TTY/Windows 验收 + 文档 | 0.5–1 天 |

前 3 个 Phase ≈ **5.5 人日**（不含 Phase 4）。

---

## 附：与既有计划的关系

| 既有文档 | 关系 |
|---|---|
| `20261109-agent-run-interactive-output-format-plan.md` | 输出行文已实现；其 §二 P2「运行期重打提示符=不做」被 line editor 推翻，见 §2.1 |
| `20261023/20261024-agent-run-interactive-review*.md` | 其 P3「steer 后不重打提示符」同上；本轮 C1/C2 是其替代方案 |
| `20261026-issue-620-live-view-resilience.md` | issue #620 的"插话被当成新任务"已修（`handle_line` 先探 `running_task`），本轮不动 |
| `agent-run-flow.md` §8/§9 | 数据链路仍准确；本轮只改终端层与 CLI 侧会话状态 |

---

## 11. 实施状态

### Phase 0 —— 已完成（2026-11-22）

| 改动 | 位置 |
|---|---|
| `Console`：提示行 + 草稿 + 人类输出的唯一出口（锁序 `line → out`） | `line_editor.rs`（`Console` / `write_human_line` / `ConsoleGuard`） |
| 输出仲裁：擦除当前提示行 → 写整行 → 重画提示符与草稿 | `Console::write_line`；`main.rs:262` 的 `print_stdout_line` 改为唯一汇合点 |
| 换行与宽度：`stty size` → `COLUMNS` → 100，按行数上移重画、CJK 双宽 | `terminal_width`、`render(prompt, state, width, from_caret_row)` |
| 终端恢复：guard 移到 REPL 栈帧；`/quit`、错误路径、SIGTERM/SIGHUP 都恢复 `stty` | `ConsoleGuard`、`TerminationSignals`、`RawMode::restore_now` |
| 管道纯度：提示行只在 stdin **与** stdout 都是 TTY 时出现 | `prompt_owner`（原实现只看 stdin） |
| 退出时不残留 `agent> ` 与 shell 提示符抢行 | `Console::release` |
| `-i` 的会话错误不再跳过收尾 | `run()` 把 `handle_line` 的错误记下来，走同一套收尾后返回 |

验证（本机 Windows，2026-11-22）：

- `cargo test --bin browser4-cli`：**2312 passed / 0 failed**（新增 8 个 `line_editor` 用例 + 1 个 `prompt_owner` 用例）；
- `cargo test --test e2e -- --scenario 'test_e2e_agent_run_interactive*'`：**9/9 通过**——全部走管道，
  输出保持逐字节兼容（非 TTY 路径没有安装 console）；
- **手工 TTY 未执行**：本机无 `stty`，编辑器不走 cbreak 分支。上线前需在 Linux/macOS 上做
  §6 的手工清单：`/quit` 后 `stty -a` 恢复、跑任务时连续打字不被吃掉、`-i | tee log` 日志干净、
  长行（>终端宽度）重画正确。

### 代码里保留给下一阶段的接口

- `Console::release` / `Console::draw` 已是状态机入口，Phase 2 的 `agent>` / `steer>` 只需一个
  `set_prompt(&str)`（本轮故意未加，避免无调用方的死代码）；
- `write_human_line` 是全局唯一人类输出汇合点，Phase 2 的审批提示不需要改输出路径。

### Phase 1 —— 已完成（2026-11-22）

| 改动 | 位置 |
|---|---|
| Readline 按键：Ctrl+A/B/E/F/P/N/U/K/W、Alt+←/→（CSI `1;3` 与 `ESC b/f` 两种编码）、Tab、Ctrl+L | `line_editor.rs` 的 `Key` / `decode_key` / `decode_escape` |
| 词级移动与删除（`word_left` / `word_right` / `DeleteWordBack` / `DeleteToStart` / `DeleteToEnd`） | `EditorState` |
| Tab 补全：命令名、`on`/`off`、任务 id、`/add-dir` 目录；多候选先扩到公共前缀、再列出 | `agent_interactive.rs` 的 `complete_line` / `apply_completion` / `directory_candidates`；编辑器只回 `Input::Complete`，不持有命令文法 |
| 历史落盘：`<state dir>/agent-repl-history`（Unix 0600、200 条、载入时压缩、`BROWSER4_CLI_NO_HISTORY=1` 关闭），`/history [n]` 与 `/history clear` | `history_file` / `load_history` / `write_history`；`Repl::print_history` |
| 括号粘贴：`ESC[200~` … `ESC[201~`，粘贴内的换行变空格 ⇒ 一次提交；行尾 `\` 续行 | `decode_escape`、`EditorState::pasting`、`continue_on_backslash`、`ConsoleGuard` 的 `?2004h` / `?2004l` |
| Ctrl+C 三态：取消任务 / 清行 / 空行两次退出（2 秒窗口）；`Input::Interrupt` 与 SIGINT 走同一逻辑 | `Repl::interrupt`、`Repl::cancel_follower` |
| 会话帮助补编辑键说明 | `print_help` |

验证（2026-11-22）：`cargo test --bin browser4-cli` **2330 passed / 0 failed**（Phase 1 新增 18 个用例）；
交互 e2e **9/9**；构建 0 告警。**手工 TTY 仍未执行**（本机 Windows 无 `stty`），上线前需在
Linux/macOS 上验证：`/quit` 后 `stty -a`、跑任务时连续打字、粘贴三行只提交一次、Tab 四类候选、
`↑` 跨进程历史、Ctrl+C 清行与二次退出、`-i | tee` 日志干净、超宽行长重画。

### Phase 2 —— 已完成（2026-11-22）

| 改动 | 位置 |
|---|---|
| 提示符状态：`agent> ` / `steer> ` / `steer[N]> ` / `steer!> `（`prompt_text` 纯函数 + `Console::set_prompt` 就地重画） | `Repl::prompt_text` / `refresh_prompt` / `queued_steers` |
| steering 队列计数：本地 `accepted_steers − follower.resolved_steers`；`steer` 与 `steering.undelivered` 事件结清 | `Repl.accepted_steers`、`TaskFollower.resolved_steers` |
| 审批主动提示：编码任务运行期每 2.5s 轮询（1.5s 预算、失败静默、按 `requestId` 去重），编码/`--access` 之外的任务不轮询 | `poll_access_requests` / `access_may_be_requested` / `ACCESS_POLL_INTERVAL` |
| 任务结束时列出未决审批（会话内给 `/approve`，退出后给 REST 调用）；去掉 `print_pending_summary_if_any` 的 TTY 早退 | `report_pending_access` / `pending_access_lines` |
| `/tasks [n]`：运行中 + 本会话任务（新到旧，含任务文本）；`tasks[]` 的每一项增加 `task` 字段 | `print_tasks` / `task_rows` / `release_current` |
| `/result [<task-id>] [--json] [--full]`：可读任一任务 | `parse_result_args` |
| `/status` 追加耗时与最近一步（最近一步优先于后端通用 message） | `status_line` 的 `elapsed` / `last_step`、`TaskEvent::status_label` |
| `/help` 分三组、`/help <command>` 单条、未知命令给最近似建议（编辑距离 ≤2）、Tab 补全与帮助共用一张表 | `SESSION_HELP` / `print_help_or_command` / `suggest_command` / `edit_distance` |
| 任务刚提交后先轮询一次审批视图（首屏就可能显示 `steer!>`；该轮询先于可能终结任务的 events 首拉） | `start_task` |

验证：`cargo test --bin browser4-cli` **2336 passed / 0 failed**（Phase 2 新增/改写 9 个用例）；
交互 e2e **12/12**（新增 3 个：审批提示、`/status` 进度、`/help`+`/tasks`+`/result <id>`）；
构建 0 告警。mock 的 `GET /api/agent/{id}/access` 改为可脚本化（默认"无待决"），
`slash_access` 场景显式脚本化视图。

### Phase 3 —— 已完成（2026-11-22）

| 改动 | 位置 |
|---|---|
| 会话记录：`<state dir>/agent-interactive-session.json`（`sessionId` + `recordedAt`），`/new` 记录新 generation | `state.rs` 的 `InteractiveSessionNote` / `remember_interactive_session` / `read_interactive_session` |
| `agent run -i --continue`：复用记录的 session id；否则保持原行为（`-s` / 已记录会话 / `interactive-<hash>`） | `commands.rs` 的 `--continue` 选项 → `continueSession`；`run_interactive` |
| 冲突与误用：`--continue` + `-s` 拒绝；`--continue` 不带 `-i` 明确报错（不静默忽略） | `resolve_continued_session`、`handle_agent_run` |
| 会话可见性：横幅加 `· resumed`；记录超过 12h 时提示"上下文可能已失效"而不是假装恢复了；退出时打印 `↻ resume this session: agent run -i --continue (or -s <id>)`（仅 TTY） | `Repl::with_resumed` / `recorded_age` / `run()` 与 `run_interactive` |
| 未记录时的友好路径：说明"还没有记录，这次会重新开始，下次 `/quit` 会记录" | `run_interactive` |

验证：`cargo test --bin browser4-cli` **2340 passed / 0 failed**（新增 4 个：commands 映射、state 记录往返、
`resolve_continued_session` 四分支 + 横幅、记录年龄）；交互 e2e **13/13**（新增
`test_e2e_agent_run_interactive_continue_reuses_the_session`：两次进程的 `command_run` 携带同一
`sessionId`，并覆盖 `--continue` 不带 `-i` 的报错）；构建 0 告警。
