# Issues: 现象 — ego92 会话获取 chrome://version 信息的一轮失败（16 次调用 / 55 分钟）

> **Source:** agent-run 日志摘录（b4 agent，会话 `ego92` / `egolite2` / `probe42350`）
> **Date:** 20261107-231530 · **Mode:** dev
> **配对文档:** 根因分析（D1–D7）与候选修复（F1–F7）见配套的 **diagnosis** issue：https://github.com/platonai/Browser4/issues/636
> **GitHub:** https://github.com/platonai/Browser4/issues/635
> **本文范围:** 只记录**可观测事实**（命令原文、退出码、stderr 逐字、耗时、预算）。
> **不含因果结论** —— 任何"为什么"都放在配对诊断文档里。
> **复现状态:** 未复现。以下内容来自原始日志 + 4.16.x 源码阅读，本次未执行任何命令。

---

## 1. 场景

- 会话名：`ego92`（另出现 `egolite2`、`probe42350`，日志中无创建记录）
- 时间窗：`04:20` → `05:15`（日志只带 `HH:MM`，**日期未记录**）
- 调用编号：#75 … #90（共 16 次工具调用）
- 从命令链推断的意图：拿到 ego 浏览器的 **profile 路径 / 可执行路径**（先试 `cdp Target.getTargets`，再试进程表，最后改读 `chrome://version` 的 `#profile_path` / `#executable_path` / `#command_line`）

> 意图是从命令推断的，日志本身没有写目标。

---

## 2. 可观测事实（逐条）

| ID | 调用 | 命令（日志原文） | 结果 | 耗时 | 退出码 |
|---|---|---|---|---|---|
| O1 | #76 | `-s ego92 cdp Target.getTargets --json "{\"filter\":[{\"type\":\"page\"}]}"` | 失败 | 480ms | 2 |
| O2 | #77 | `coding_write` → `.b4scratch/pages.json`，内容 `{"filter":[{"type":"page"}]}` | 无错误行 | — | — |
| O3 | #78 | `-s ego92 cdp Target.getTargets --file .b4scratch/pages.json` | 无错误行 | — | — |
| O4 | #79 | `-s ego92 cdp Browser.getVersion` | 无错误行 | — | — |
| O5 | #80 | `-s egolite2 cdp Browser.getVersion` | 无错误行 | — | — |
| O6 | #81 | `-s egolite2 cdp Target.getTargets --file .b4scratch/pages.json` | 无错误行 | — | — |
| O7 | #82 | `-s probe42350 cdp Target.getTargets --file .b4scratch/pages.json` | 无错误行 | — | — |
| O8 | #83 | `coding_shell`: `wmic process where "name='ego.exe'" get ProcessId,CommandLine /format:list` | 被拒 | 88ms | — |
| O9 | #84 | `coding_shell`: `powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='ego.exe'\" \| ForEach-Obje…"` | 被拒 | 88ms | — |
| O10 | #85 | `-s ego92 cdp Target.getBrowserContexts` | 无错误行 | — | — |
| O11 | #86 | `coding_readLines` `.b4scratch/round-0.log` 第 185–200 行 | 无错误行 | — | — |
| O12 | #87 | `-s ego92 tab-new chrome://version` | 无错误行 | — | — |
| O13 | #88 | `-s ego92 htmlsnapshot get all text "#profile_path, #executable_path, #command_line, #product_name"` | 失败 | 498ms | 1 |
| O14 | #89 | `-s ego92 eval --json "JSON.stringify({p:(document.getElementById('profile_path')\|\|{}).innerText, exe:(document…"` | 无错误行 | — | — |
| O15 | #90 | `-s ego92 tab-close 3` | 失败 | 695ms | 1 |

"无错误行"仅表示日志没有该调用的 `↳ … failed` 行；**返回值未保存**，是否为空未知。

### 失败原文（逐字）

**O1（#76）** — `b4_run failed after 480ms`：

```
b4.run [fail] failed: CLI exited with code 2
stderr: Invalid JSON for CDP params: key must be a string at line 1 column 2
Params must be a valid JSON object, e.g. '{"format": "jpeg"}'
```

**O8（#83）** — `coding_shell failed after 88ms`：

```
PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED | tool=coding.shell | detail=Command blocked — command 'wmic' is ...
```

**O9（#84）** — `coding_shell failed after 88ms`：

```
PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED | tool=coding.shell | detail=Command blocked — inline script via ...
```

**O13（#88）** — `b4_run failed after 498ms`：

```
[INVALID_ARGUMENT] html_snapshot_scrape_all failed: Nothing to read: the active tab shows <chrome://version/>.
Navigate to an http(s) or file:// page…
```

**O15（#90）** — `b4_run failed after 695ms`：

```
Error: Tab index 3 is out of range. There are 3 tabs (indices 0-2).
```

> 日志中 `…` 处为原始摘录的截断，不是省略。

---

## 3. 资源消耗观测

| 时刻 | tokens | 预算占比 | 请求数 |
|---|---|---|---|
| 04:20 | 1.33M / 5.00M | 27% | 63 |
| 04:38 | 1.45M / 5.00M | 29% | 67 |
| 04:48 | 1.55M / 5.00M | 31% | 70 |
| 05:06 | 1.65M / 5.00M | 33% | 73 |

- 时间窗 55 分钟，工具调用 16 次（#75–#90）。
- 目标信息（profile 路径）**在 O14 一次 `eval` 后即已到手**（日志截断，看不出是否读到了值）。

---

## 4. 观测到的异常模式（只描述，不解释）

1. **同一能力被连续试探**：O4–O7 是同一族命令换会话名（`ego92` → `egolite2` → `probe42350`）连打 4 次，全部没有错误输出，也没有任何一条的返回值被引用。
2. **被拒后换执行层重试**：O8（`wmic`）被拒后，O9 改用 `powershell -Command` 内联脚本，再被拒。
3. **每次失败后平均 2–6 分钟才出现下一条调用**（例：04:39 O5 → 04:43 O6 → 04:44 O7；04:48 O9 → 04:53 O10）。
4. **把"无错误"当作进展**：O3（`--file` 版本）成功后，O6/O7 又用同一 payload 在其它会话重跑，而不是读取 O3 的结果。

---

## 5. 环境事实与不确定性登记

| 项 | 状态 |
|---|---|
| CLI / 后端版本 | **未记录**。本次核对用工作树 `4.16.x` @ `043aeb7115`（`4.16.4-SNAPSHOT`），与出问题的那次不一定同版本 |
| 操作系统 | 未记录；`wmic` / `powershell -Command` / `tab-close` 组合指向 Windows |
| `b4.run` 实际收到的 argv | **未抓取**（O1 的失败发生在哪一层、值坏成什么样，只能从 serde 报错反推） |
| `ego92` 在 O15 时的 tab 数 | 3（错误信息自述，可信） |
| 新标签的插入位置 | 未知（`tab-new` 的返回未保存） |
| O3 / O4–O7 / O10 / O12 / O14 的返回体 | **未保存** → 无法判断"成功但为空"还是"成功且有值" |

---

## 6. 期望行为（agent 视角）

- 失败信息应指向**下一个可用的替代路径**（`--json` 失败 → `--file`/`--stdin`；`chrome://` 页面 → `eval`；索引越界 → `tab-list`/`--guid`）。
- "没有数据"与"参数/会话不对"必须可区分，不能都以成功的空结果返回。
- 同一条命令在合法参数下不应因为 shell 转义层数而失败。

---

## 7. 原始日志片段

```
[04:20] tokens 1.33M / 5.00M (27%) · 63 requests
[04:20] #75 b4_run({"args":"-s ego92 cdp Target.getTargets"})
[04:27] #76 b4_run({"args":"-s ego92 cdp Target.getTargets --json \"{\\\"filter\\\":[{\\\"type\\\":\\\"page\\\"}]}\""})
[04:27]   ↳ b4_run failed after 480ms: b4.run [fail] failed: CLI exited with code 2 …
[04:28] #77 coding_write({"path":".b4scratch/pages.json","content":"{\"filter\":[{\"type\":\"page\"}]}\n"})
[04:30] #78 b4_run({"args":"-s ego92 cdp Target.getTargets --file .b4scratch/pages.json"})
[04:38] tokens 1.45M / 5.00M (29%) · 67 requests
[04:38] #79 b4_run({"args":"-s ego92 cdp Browser.getVersion"})
[04:39] #80 b4_run({"args":"-s egolite2 cdp Browser.getVersion"})
[04:43] #81 b4_run({"args":"-s egolite2 cdp Target.getTargets --file .b4scratch/pages.json"})
[04:44] #82 b4_run({"args":"-s probe42350 cdp Target.getTargets --file .b4scratch/pages.json"})
[04:47] #83 coding_shell({"command":"wmic process where \"name='ego.exe'\" get ProcessId,CommandLine /format:list"})
[04:47]   ↳ coding_shell failed after 88ms: … COMMAND_NOT_ALLOWED … command 'wmic' is …
[04:48] tokens 1.55M / 5.00M (31%) · 70 requests
[04:48] #84 coding_shell({"command":"powershell -NoProfile -Command \"Get-CimInstance Win32_Process -Filter \\\"Name='ego.exe'\\\" | ForEach-Obje…"})
[04:48]   ↳ coding_shell failed after 88ms: … COMMAND_NOT_ALLOWED … inline script via …
[04:53] #85 b4_run({"args":"-s ego92 cdp Target.getBrowserContexts"})
[05:02] #86 coding_readLines({"endLine":200,"path":".b4scratch/round-0.log","startLine":185})
[05:06] tokens 1.65M / 5.00M (33%) · 73 requests
[05:06] #87 b4_run({"args":"-s ego92 tab-new chrome://version"})
[05:07] #88 b4_run({"args":"-s ego92 htmlsnapshot get all text \"#profile_path, #executable_path, #command_line, #product_name\""})
[05:08]   ↳ b4_run failed after 498ms: … [INVALID_ARGUMENT] … <chrome://version/> …
[05:10] #89 b4_run({"args":"-s ego92 eval --json \"JSON.stringify({p:(document.getElementById('profile_path')||{}).innerText, exe:(document…"})
[05:15] #90 b4_run({"args":"-s ego92 tab-close 3"})
[05:15]   ↳ b4_run failed after 695ms: … Tab index 3 is out of range. There are 3 tabs (indices 0-2).
```

---

## 8. 状态

| 项 | 值 |
|---|---|
| 状态 | **Open — 未修复**（按要求"暂时不修复"） |
| 已改动代码 | 无 |
| 已改动文档 | 无（本文件是新增的 issue 草稿） |
| 下一步 | 评审配对诊断文档，按 D1–D6 逐条决定 ACCEPT / DEFER / WONTFIX |
