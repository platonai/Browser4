# Issue #620：`agent run -i` 出错后不再逐步输出 / 插话被当成新任务

- 日期：2026-10-26
- 分支：`4.15`（工作区变更，未提交）
- Issue：<https://github.com/platonai/Browser4/issues/620>
  （正文：出错后静默；评论 `#issuecomment-6420050579`：插话被排成新任务）
- 范围：
  - 后端：`browser4-agent-tools/.../AgentTaskEvents.kt`（`AgentTaskEventSanitizer`、`AgentTaskEventLog`）
  - CLI：`cli/browser4-cli/src/agent_interactive.rs`（跟随器 + 交互会话 + 插话契约）
  - 测试：`AgentTaskEventSanitizerTest`/`AgentTaskEventLogTest`（Kotlin）、`agent_interactive::tests`（Rust）
  - 文档：`skills/browser4-cli/references/agent.md`、两份 README、`cli/browser4-cli/README.md`、`help.rs`

## 一、现象（Issue 日志）

```
[05:16]   … could not parse the events response as JSON: unexpected end of hex escape at line 23 column 276 (retrying for up to 300s)
⚠ giving up on task 945cd56d-… after 300s without a usable response: could not parse the events response as JSON: …   # 无时间前缀，是 REPL 打印
agent> /status
945cd56d-… · running · 165 step(s) Agent is working            # 任务仍在跑
agent> /status
945cd56d-… · running · 240 step(s) Agent is working            # 步数在涨，但再没有任何步骤输出
```

关键词：任务**仍在运行**、步数**仍在增长**，但逐步输出**永久停止**。

## 二、根因链（三层，缺一不成）

1. **后端把 emoji 切成了半个**。`AgentTaskEventSanitizer.preview()` 用
   `collapsed.take(maxChars)` 截断 240 个 **UTF-16 code unit**，正好切在增补平面字符
   （emoji、CJK 扩展 B 等 4 字节字符）的代理对中间，字符串尾部留下一个**孤立高位代理项**。
2. **Jackson 把它写成 `\uD83D`**。`pulsarObjectMapper()` 没有开 `ESCAPE_NON_ASCII`，
   `writeValueAsString()` 走 `WriterBasedJsonGenerator` 会**原样保留**孤立代理项；
   但 Spring 响应体走字节流（`UTF8JsonGenerator`），实测会把它转义成 `\uD83D`：

   ```
   $ java -cp jackson-*.jar LoneProxyBytes.java
   bytes: 22 61 62 63 5c 75 44 38 33 44 22
   ascii: "abc\uD83D"
   ```

   CLI 的 `serde_json` 1.0.149（`read.rs:927-953`）对「高位代理项后不是 `\uDCxx`」的情况
   报 `ErrorCode::UnexpectedEndOfHexEscape` → 正是日志里的 *unexpected end of hex escape*。
   注意：整个**页面**因此不可解析，不只是那一条事件。
3. **CLI 把确定性失败当成瞬态故障**。`poll()` 解析失败即 `tolerate_transient(...)`：
   游标不动、原样重试 300 秒。毒页面是确定性的（同一游标 → 同一字节），
   于是 5 分钟后必然放弃；`Repl::run` 收到错误后直接 `current.take()` 丢掉跟随器，
   任务继续跑，实时输出就此结束。这正是 `20261024-agent-run-interactive-review-2.md`
   里 P1-1「已修复」的残留面：当时只做了「重试 + 会话不崩」，没做「毒页面必须越过」。

## 三、修复

### 后端：预览必须是严格合法的 JSON（根因）

`AgentTaskEventSanitizer`：

- `preview()` 截断落在 **code point 边界**：若第 `maxChars` 个单位是高位代理项就回退一位，
  保证代理对要么完整保留、要么整个不写；
- 新增 `replaceUnpairedSurrogates()`：源文本里本就存在的孤立代理项（provider/CDP 可能给出）
  替换为 `U+FFFD`，不留给序列化器去转义；
- `AgentTaskEventLog.append()` 对**每个字符串字段**（`type`/`tool`/`arguments`/`text`）做同一个修复——
  日志是序列化前的最后一道关，任何调用方都不该能把坏字段写进线上流；干净文本原样返回同一实例，
  常见路径零开销；
- KDoc 说明了「为什么预览必须能被严格解析器读」，避免以后有人再把 `.take(n)` 加回来。

### CLI：毒页面必须越过（不再空等 5 分钟）

`TaskFollower` 新增自适应分页 `page_limit`：

- 200 但解析失败 → **页长减半**立即重试（`200 → 100 → … → 1`），把坏步骤逼到页首；
- 页长已为 1 仍解析失败 → 从响应体里取出 `nextSeq`（它在 `events` 之前序列化，坏在步骤内部时仍完好），
  游标越过该步骤，打印 `… skipped 1 unreadable step (up to seq N)`，页长恢复到 200；
- 取不到可用游标（代理错误页、响应体被截断）→ 仍走原来的瞬态失败窗口（300 秒），
  这才是真正的传输故障；
- 解析成功时页长翻倍（上限 200），长任务不会因为一次隔离而长期只读 1 条/页。

### CLI：`-i` 不再丢弃仍在运行的任务（Issue 标题的直接诉求）

`Repl::recover_current()` 取代「出错即 `take()`」：

| 后端对 `command_status` 的回答 | 处理 |
|---|---|
| `running` / `queued` | **保留 follower**，`resume()` 重置故障窗，打印 `… still following it; the live step view resumes when the backend answers` |
| 不可达（探测也失败） | 同上——「无法判断」不能等于「任务不存在」 |
| 终态（`done`/`failed`/`cancelled`） | `complete_current()`：照常打印结果/失败原因 |
| 任务不存在（404 / `No agent task found`） | 释放 follower，`/status`、`/result` 仍用本地记录回答 |

一次性 `--follow` 保持「约 5 分钟后非零退出」的语义（一次性命令必须能终止），
但错误信息补上 `the task itself may still be running — 'agent status <id>' reports it`；
结束时若跳过了步骤，会补一行 `… N step(s) could not be displayed and were skipped`。

## 三之二、插话失效（Issue 评论 #issuecomment-6420050579）

评论原文：*“插话机制实现错误。帮助信息写着 Type a line while a task runs to steer it，
但是输入指令后，被当成新任务进入队列排队。”* 日志里 `agent> 你应该打开真实浏览器…` 之后是
`▶ task b3fabba3-…`（新任务），随后 `⏹ cancelled`。

**根因**：插话与否只由 `self.current`（follower）决定，而 follower 在上一个缺陷里被丢掉了，
于是 REPL 认为「空闲」——还打印了空闲提示符 `agent>` 邀请用户输入——键入的行只能变成新任务。
新任务在同一 session 的 `runMutex` 上排队（`StatefulAgentRunner.execute`），用户看到的却是
「插话没生效 + 多了一个任务」。

**修复**（`Repl::running_task()` / `attach_if_detached()`）：把契约从「我有没有 follower」
改成「后端这个任务还活着吗」：

- `current` 为空时先用 `command_status(last_task)` 探测；**只有肯定答复**（可解析的状态信封、
  非 404、非终态）才算「仍在运行」，然后插话；
- 「无法判断」（网络失败、信封不可读）一律按**提交**处理——提交是可恢复的，静默吞掉用户指令不是；
- 插话被接受时若没有 follower，则从 `last_task_seq`（释放时的游标）**重新挂上**实时视图，
  打印 `… following task <id> again — live steps resume`，不重放历史；
- `/new` 之后的上一代任务不再被插话（`last_task_generation`）——用户明确要了新上下文；
  想换任务就 `/cancel`；
- `/help` 措辞改成「任务运行时键入的行会插话（下一次模型轮次生效）；要开始另一个任务先 /cancel」。

## 四、测试

后端（`AgentTaskEventSanitizerTest`，13 passed）：

- `truncationKeepsSurrogatePairsWhole`：`"ab😀cd"` 限长 3/4/1 三种边界；
- `replacesUnpairedSurrogates`：孤立高位/低位代理项、240 字符边界的真实场景；
- `argumentsPreviewSerializesWithoutLoneSurrogateEscapes`：用
  `pulsarObjectMapper().writeValueAsBytes(event)` 断言产物中**没有** `\uD8xx` 转义。
- `AgentTaskEventLogTest.logKeepsEveryFieldStrictlySerializable`：日志收口——
  `type`/`tool`/`text` 里塞进孤立代理项也走不出去。
- **回归验证**：把 `preview()` 临时改回 `take(maxChars)`，上述 3 个测试全部失败，
  报 `unpaired high surrogate at 239`（正是 issue 里 column 276 附近的位置）；改回修复即通过。

CLI（`agent_interactive::tests`，42 passed / 全量 1955 passed）：

- `parse_next_seq_reads_the_cursor_from_a_body_json_rejects`；
- `unparseable_page_narrows_the_page_before_it_retries`（游标不动、页长减半、成功后恢复）；
- `an_unreadable_step_is_skipped_and_following_continues`（用真实 `\ud83d` 毒体模拟后端，
  断言越过、计数、继续收流并读到 `done`）；
- `a_page_without_a_cursor_still_counts_as_an_outage`；
- `a_follower_that_gave_up_keeps_following_a_running_task` / `…_when_the_backend_cannot_answer` /
  `…_releases_a_task_the_backend_lost` / `…_completes_a_task_that_finished_meanwhile`；
- 插话契约：`a_line_for_a_detached_running_task_steers_instead_of_queueing_a_second_task`、
  `only_a_positive_status_answer_makes_a_task_steerable`（running/done/坏体/404 四种探测）、
  `a_task_from_a_previous_session_generation_is_not_steered`、`attaching_never_steals_an_existing_follower`。

**回归验证**：把 `running_task()` 临时改成恒返回 `None`（即「没有 follower 就没有任务」的旧语义），
`a_line_for_a_detached_running_task_steers_instead_of_queueing_a_second_task` 立即失败
（`steering re-attaches the live view of the running task`）；恢复后通过。

### 测试基础设施：Windows 上 `set_nonblocking` 会传染

期间发现全量跑时 **间歇性** 失败（单跑 `agent_interactive::tests` 却稳定）。根因在测试桩：
监听 socket 为了用 stop 标志退出而设成非阻塞，而 Windows 上 `accept()` 出来的连接**继承**
非阻塞模式；`read()` 于是立刻返回 `WouldBlock`，桩在客户端请求体到达前就应答并 close，
客户端报 `error sending request`。修复：accept 后 `set_nonblocking(false)` + 读超时，
并把 `WouldBlock` 当作「继续读」而不是「结束」。修完连跑 5 次全量（1955 tests）零失败。

## 五、复现与验证

```bash
# 后端不变量（旧代码必失败的那三个）
./mvnw.cmd -o test -pl browser4-agent-tools -Dtest=AgentTaskEventSanitizerTest

# CLI 跟随器 + 交互会话
cd cli/browser4-cli && cargo test --bin browser4-cli -- agent_interactive::tests
```

真机复现要点：让事件里的预览文本在 240 字符处正好切断一个 emoji
（例如 `coding_write` 写一段含 emoji 的长文本），旧版后端 + 旧版 CLI 即可复现
`unexpected end of hex escape` 与随后的静默。

## 六、取舍与遗留

- **丢一步而不是丢一屏**：无法解析的那 1 条事件被永久跳过（会打印一行提示）。
  事件流是 240 字符预览的展示层，这个代价远小于丢掉整段实时视图。
- **孤立的预览被 `U+FFFD` 替换**：`\uFFFD` 只在源文本本身非法时出现，正常文本无变化。
- **`-i` 在长时间断连下不会自动回到提示符**：用户仍可 `Ctrl+C`（取消）/`/cancel`/`/quit`；
  这是「宁可继续等，也不要静默」的产品取舍。
- **插话要花一次 `command_status`**：只在「没有 follower 且本会话有上一个任务」时探测，
  代价是一次本地 MCP 调用（毫秒级），换来的是「键入的指令绝不会被静默丢进队列」。
- **插话被拒绝时不自动提交**：任务刚好结束的竞态里，CLI 只提示 `⚠ not delivered`，
  不擅自启动一个昂贵的 agent 任务（与既有文档一致，用户可重发）。
- **未改后端排队语义**：同一 session 的第二个任务仍在 `runMutex` 上排队——那是脚本化
  `agent run` 需要的；CLI 的责任是不再意外造出第二个任务。
- **待跟进的观察（评论里的 `⏹ cancelled`）**：日志中新任务 `▶ task b3fabba3` 之后立刻
  `⏹ cancelled · b3fabba3`，而旧任务仍在跑。两个可疑点（**本轮未改，需单独验证**）：
  1. `UserCommandExecutor.ensureAgentRunner` 在 `!existing.session.isActive` 时会
     `remove + close()` 旧 runner，而 `StatefulAgentRunner.close()` 会 `taskScope.cancel()`
     并把在跑任务标成 `cancelled / "Runner closed"`——即「提交新任务」可能顺手取消掉还在跑的旧任务；
  2. `execute()` 的 `catch (CancellationException)` 会把排队中的任务标成 `cancelled`，
     所以会话真死掉后每次提交都立刻"取消"。
  修法方向：runner 仍有 live job 时不要关闭/替换它，让新任务排在其后（工具会以清晰错误失败），
  而不是静默取消正在跑的任务；需要浏览器会话的重型测试来验证，本轮未动。
- 未新增 e2e 场景：mock server 由 `serde_json` 生成事件（Rust 字符串无法承载孤立代理项），
  脚本化「坏页面」需要给它加裸响应体开关；当前 Rust 单测已用**真实 socket + 真实 reqwest**
  覆盖了跟随器、会话恢复与插话三条路径，收益不高。
