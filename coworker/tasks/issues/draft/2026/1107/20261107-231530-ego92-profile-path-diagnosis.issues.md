# Issues: 诊断 — 同上一轮 5 处报错 + 4 次静默空转的根因、证据与候选修复

> **Source:** agent-run 日志（会话 `ego92` / `egolite2` / `probe42350`）+ 4.16.x 源码核对
> **Date:** 20261107-231530 · **Mode:** dev
> **配对文档:** 可观测事实（O1–O15）见配套的 **symptom** issue：https://github.com/platonai/Browser4/issues/635 （同一轮分析拆分提交）
> **GitHub:** https://github.com/platonai/Browser4/issues/636
> **状态:** **只做诊断，未修改任何代码/文档**（按要求"暂时不修复"）
> **代码基线:** 工作树 `4.16.x` @ `043aeb7115`（`4.16.4-SNAPSHOT`）。工作树含未提交改动（`main.rs` −138 行、`HTMLSnapshotToolExecutor.kt` −26 行，均为 X-SQL `:expr` 提示相关，**不涉及本文引用的任何区域**），因此下文统一列"函数名 + 行号"，行号以当前工作树为准。

---

## 0. 结论摘要

| 编号 | 对应现象 | 根因归属 | 是产品缺陷吗 | 置信度 | 建议决策 |
|---|---|---|---|---|---|
| D1 | O1 `cdp --json` exit 2 | CLI 参数校验 + Windows 引号层 + 报错未指向逃生口 | 部分是（可发现性） | 高 | ACCEPT（改文案） |
| D2 | O4–O7 四次静默成功 | CDP 空结果被当作成功；无会话名拼写问题（已修正） | 否 | 中 | WONTFIX（补文档/引导） |
| D3 | O8 `wmic` 被拒 | 命令白名单（设计闸门） | 否 | 高 | WONTFIX（可选改 hint） |
| D4 | O9 内联脚本被拒 | 内联解释器脚本守卫（设计闸门） | 否（但缺替代路径提示） | 高 | ACCEPT（改 hint）/ DEFER |
| D5 | O13 `chrome://` 被 htmlsnapshot 拒 | 页面族约束（只接受 http(s)/file） | 部分是（未提示 `eval`） | 高 | ACCEPT（改文案 + 文档） |
| D6 | O15 `tab-close 3` 越界 | 0-based 索引 + agent 忽略已打印的 index/GUID | 否（轻微可发现性） | 高 | DEFER（低优先） |
| D7 | 55 分钟 / 1.65M tokens | agent 侧试探策略（非产品缺陷） | 否 | 高 | 不进 issue 流水线 |

**一句话**：5 处报错（O1 / O8 / O9 / O13 / O15）里没有一处是渲染或传输故障 —— 其中 **3 处是设计闸门/页面族约束按预期拒绝**（D3 `wmic`、D4 内联脚本、D5 `chrome://`），1 处是**Windows 引号层**（D1，且有现成逃生口 `--file`），1 处是**agent 忽略了已打印的索引**（D6）；另有 4 次**静默空转**（D2）源于 CDP 空数组即成功。真正值得改代码的只有**三处文案/提示**（D1/D4/D5），其余是文档与 agent 行为。

### 0.1 对上一轮口头结论的修正（重要）

上一轮我说过"`-s <拼错的名字>` 会静默成功/空返回"。**核对代码后这条不成立**：

`-s <name>` 读的是 CLI 本地状态槽（`main.rs:608 require_session` → `read_state(None, session_name)`），该名字没有记录过 `session_id` 时直接返回 `no_active_session_message()` 报错 —— 那会在日志里留下 `↳ … failed` 行。而 O4–O7 **没有任何失败行**，说明 `egolite2` / `probe42350` 是**本地确实存在的会话**，问题不是拼写，而是"CDP 返回空数组 = 成功"（见 D2）。

---

## D1：`cdp --json` 的 JSON 在到达 CLI 之前就被改坏了

**Severity:** Medium · **Category:** UX（可发现性）· **复现状态:** 未复现（报错串与代码路径一致）

#### 现象

O1：`-s ego92 cdp Target.getTargets --json "{\"filter\":[{\"type\":\"page\"}]}"` → 480ms、exit **2**：

```
Invalid JSON for CDP params: key must be a string at line 1 column 2
Params must be a valid JSON object, e.g. '{"format": "jpeg"}'
```

#### 根因

1. **失败点在 CLI 本地，请求没有发出。** `cli/browser4-cli/src/main.rs:2002 build_cdp_tool_params()` 对 `--json` 的值直接 `serde_json::from_str()`（2015 行），解析失败映射为 `CliError(ExitCode::Usage, …)`（2016–2023 行），`ExitCode::Usage = 2`（`main.rs:337`）。这条路径在 `call_tool()` 之前，所以 480ms 内结束，后端与会话状态不受影响。
2. **`key must be a string at line 1 column 2` 是精确的位置断言。** serde_json 在对象起始 `{` 之后期待 `"`；报 column 2 说明**第 2 个字符不是引号**。最符合的解释是到达 CLI 的值以 `{\` 开头（反斜杠还在、引号已被剥掉），与下面第 3 点一致。
3. **引号层剥坏。** 该行的 `--json "{\"filter\":…}"` 要过：工具调用 JSON → Windows shell → CLI argv。PowerShell 的转义字符是反引号 `` ` ``，**`\"` 不是转义**：`\` 原样保留，而 `"` 被当作字符串边界，于是整个值被切成多段后重新拼接，得到形如 `{\filter\:[{\type\:…` 的畸形串。这条坑在 `skills/browser4-cli/references/shell-quoting.md`（§ Agent `b4.run`: one PowerShell layer, no stdin）里有系统说明。
4. **逃生口早就存在，只是没被指出来。** `cdp` 除 `--json` 外还有 `--file`（`main.rs:2041 resolve_cdp_params_file`，直接读文件字节，shell 不参与）和 `--stdin`（`commands.rs:2309–2310`）。O2/O3 用的正是 `--file`，并且**成功了**。

#### 判定

- **命令写错**：是（agent 侧）。
- **产品缺陷**：报错只举了 `'{"format": "jpeg"}'` 这个反例，没有给出"改用 `--file`/`--stdin`"的下一步 —— 对 AI 用户这是可发现性缺陷。

#### 候选修复（未实施）

| 改动 | 位置 | 内容 |
|---|---|---|
| 报错追加逃生口 | `build_cdp_tool_params()` 的 `Err` 分支 | `Invalid JSON for CDP params: {e}` 之后加一行 `If the value was mangled by your shell, pass it as a file: cdp <method> --file params.json (or --stdin)` |
| （可选）畸形值识别 | 同上 | 当 `ps` 以 `{\` 开头或含 `\"` 时，追加一句"looks like shell-escaped JSON" |

- 风险：极低（只改错误消息）。
- 验证：`main.rs` 已有 `build_cdp_tool_params` 的单测（`Invalid JSON for CDP params` 断言在 36213 行附近），补一条断言新提示；`cargo test --bin browser4-cli`。
- **注意**：不要改 `--json` 的宽松解析（例如自动去掉反斜杠）—— 那会把"用户写错"变成"我们猜"，与 CDP 直通的调试命令定位冲突。

#### 置信度

- "值在到达 serde 之前已被改坏"：**高**（报错位置 + 文档 + `--file` 立即可用三点自洽）。
- "畸形串的确切内容"：**中**（未抓 argv，见 §证据缺口 E1）。

---

## D2：`Target.getTargets` 返回空数组 → 四次"成功"的空转

**Severity:** Low · **Category:** Reliability（对 AI 用户有误导性）· **复现状态:** 未复现

#### 现象

O4–O7：`ego92` → `egolite2` → `probe42350`，同一族命令连打 4 次，**全部没有失败行**，也没有任何一条的输出被后续步骤引用。

#### 根因（修正后）

1. `-s <name>` 只认本地状态槽；名字不存在会报 `no_active_session_message()`，而日志里**没有**这类失败 → 三个会话都是真实存在的（见 §0.1）。
2. `cdp` 是直通命令：`Target.getTargets` 返回 `[]` 时 CLI 打印 `[]`、退出码 0。**空集合与"会话不对/浏览器没连上"在当前输出里不可区分**，agent 只能靠猜。
3. agent 侧：O3（`--file` 版本）已经成功却未读取结果，转而换会话名重试 —— 这是流程问题，不是产品缺陷。

#### 判定

非产品缺陷。CDP 直通命令**应该**忠实返回 `[]`；要区分"空"与"错"，正解是让 agent 先查会话与标签（`list` / `tab-list` / `page-info`），而不是给 `cdp` 加启发式告警。

#### 候选修复（未实施）

- 文档（`skills/browser4-cli/references/agent.md` 或 `SKILL.md` 的 CDP 小节）加一句：**`cdp` 的空数组是成功返回；判断"浏览器是否连上"要看 `Browser.getVersion`/`tab-list`，不要用 `Target.getTargets` 的长度当健康检查**。
- 可选：`cdp` 在 stderr 提示 `Target.getTargets returned 0 page targets — check 'list' / 'tab-list'`。价值低、噪音高，不建议默认开启。

#### 置信度

- "三个会话真实存在"：**高**（由 `require_session` 的行为反推）。
- "四次调用具体返回了什么"：**低**（输出未保存，见 E3）。

---

## D3：`wmic` 被命令白名单拒绝 —— 设计闸门按预期工作

**Severity:** Low · **Category:** Product（策略）· **复现状态:** 高置信

#### 现象

O8：`wmic process where "name='ego.exe'" get ProcessId,CommandLine /format:list` → 88ms：

```
PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED | tool=coding.shell | detail=Command blocked — command 'wmic' is …
```

#### 根因

`coding.shell` 的拒绝由 `browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/CodingAgentShell.kt` 构造：`PermissionAudit.denied(detail = "Command blocked — $violation", hint = …)`（802–822 行），审计码 `COMMAND_NOT_ALLOWED` 由 `Authorizer.kt:203`（`Effect.FS_EXEC / PROC_SPAWN`）映射。命中理由通常是"不在当前档位的允许命令集"（`operatorScopeViolation()`，839–853 行）或"不在白名单"。

附带事实：`wmic` 在 Windows 11 24H2+ 已从系统移除，即使放行也多半是空结果。

#### 判定

**预期行为**（WONTFIX）。唯一可讨论点：hint 目前偏构建场景 ——

```
run the build through coding.mvnBuild/coding.verify, or request operator approval with
coding.requestAccess(effect="fs.exec", target="<command>")
```

对一个"查进程"的请求，"跑构建"没有帮助。若要让 agent 少绕圈，可让 hint 先给"这条命令在本策略下无替代路径"的直白结论，再给 `requestAccess`。

#### 候选修复（未实施）

- 可选：`PermissionAudit.denied` 的 hint 按 violation 分类（构建类 / 进程类 / 网络类），进程类直接说明"the shell preset cannot inspect processes"。
- 明确**不要**放行 `wmic`。

#### 置信度：高（错误码、`detail` 文案与代码结构一一对应）。

---

## D4：内联解释器脚本被拒 —— 同样是设计闸门，但缺替代路径

**Severity:** Medium · **Category:** UX · **复现状态:** 高置信

#### 现象

O9：`powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='ego.exe'\" | ForEach-Obje…"` → 88ms：

```
PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED | tool=coding.shell | detail=Command blocked — inline script via …
```

#### 根因

`CodingAgentShell.kt:1381–1394`（`validateSegments`）：先识别内联脚本体（`internal fun inlineScript()`，1639–1651 行；`sh|bash|zsh|dash -c`、`pwsh|powershell -c|-command|/c|/command`、`cmd /c|/k`），再对**内层正文递归校验**；不通过时返回

```
"inline script via '$baseCommand' rejected — $violation"
```

设计动机写在 1381–1384 行的注释里：只校验外层会让 `sh -c "mvn …"` 越过 build-only 档位。

所以这是"**换解释器绕过白名单**"被挡住的正常结果 —— agent 在 O8 被拒后立刻做了这件事（O9），是典型的"换写法而非换策略"。

#### 判定

**预期行为**。可改进点只有一个：拒绝时给出的 hint（802–822 行）与 `wmic` 那条完全一样，没有告诉 agent "该怎么办"。文档 `skills/browser4-cli/references/shell-quoting.md:199` 已经写明"内联脚本被拒"，但运行时提示里没有。

#### 附带观察（供维护者判断，本文不做结论）

`inlineScript()` 只识别 `-c` / `-command` / `/c` / `/command`，**不识别 `-File`**，因此 `powershell -NoProfile -File probe.ps1` 不属于内联脚本，会落到 1396 行之后的通用检查。而 1386 行的注释说明"内联体必须校验"的原因正是防止绕过档位 —— 脚本文件的内容同样未被校验。这是一个**策略覆盖的对称性缺口**：要拦内联体就绕不开脚本文件，除非对 `-File` 也做同样处理（例如限定脚本必须位于工作区内 + 记录）。是否收紧由安全 owner 判断。

#### 候选修复（未实施）

| 改动 | 位置 | 内容 |
|---|---|---|
| hint 分流 | 内联脚本分支（1390–1392 行返回值处） | 追加：`inline scripts are refused by policy — put the body in a workspace script and run the file, or ask with coding.requestAccess(...)` |
| （不建议）放宽 | — | 不建议对 `powershell` 开口子 |

- 验证：`CodingAgentShellPathGuardTest` / `PermissionDecisionBaselineTest` 已有同类断言，补一条 hint 文案断言即可。
- 风险：低（只改 hint）。

#### 置信度：高（拒绝文案逐字对应 1391 行的格式串）。

---

## D5：`htmlsnapshot` 对 `chrome://` 页面永久无效 —— 正确拒绝，但没提供 `eval`

**Severity:** Medium · **Category:** Documentation / UX · **复现状态:** 高置信

#### 现象

O13：`htmlsnapshot get all text "#profile_path, #executable_path, #command_line, #product_name"` → 498ms、exit **1**：

```
[INVALID_ARGUMENT] html_snapshot_scrape_all failed: Nothing to read: the active tab shows <chrome://version/>.
Navigate to an http(s) or file:// page…
```

（O14 的 `eval --json "JSON.stringify({…getElementById('profile_path')…})"` 在同一个 `chrome://version` 页面上**没有失败行** → 同一页面 `eval` 可用。）

#### 根因

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt`：

```kotlin
private fun isArchivableAddress(url: String): Boolean =          // 431–432
    url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")

private fun requireArchivableActivePage(address: String?, action: String): String {   // 441–450
    val archivable = address?.takeIf { isArchivableAddress(it) }
    require(archivable != null) {
        "Nothing to $action: the active tab shows <${address ?: "no page"}>. Navigate to an http(s) …"
    }
    …
}
```

`chrome://version` 不匹配任何前缀 → `require` 抛 `IllegalArgumentException` → 上层包成 `[INVALID_ARGUMENT]`，CLI 退出码 1。日志文案里的 `Nothing to read` 与 `action="read"` 逐字吻合。

原因不是权限，而是这条链路（抓文档 → 存 page store → 查询）**对 WebUI 特权页没有可归档的文档**。因此 `goto` → 重试 → 再 `htmlsnapshot` 永远不会成功。

#### 判定

- 守卫本身：**正确**（早拒绝优于产出空结果）。
- 缺陷：拒绝文案没有指出**唯一可行路径** `eval`（`Runtime.evaluate` 在 `chrome://` 页面可用，O14 已经证明）。agent 花了 3 分钟才想到 `eval`。

#### 候选修复（未实施）

| 改动 | 位置 | 内容 |
|---|---|---|
| 拒绝文案补替代路径 | `requireArchivableActivePage()` 的 message | 加一句：`For chrome:// / devtools:// pages use \`eval\` (Runtime.evaluate) — htmlsnapshot only archives http(s) and file:// documents.` |
| 文档 | `skills/browser4-cli/SKILL.md` + `references/htmlsnapshot.md` | 加一条"WebUI 页面（chrome://、devtools://、about:）只支持 eval" |

- 验证：`HTMLSnapshotToolExecutorTest` 已有该守卫的测试（工作树里该测试文件也有未提交改动，改前先 rebase 到干净基线），补断言新文案。
- 风险：极低。

#### 置信度：高（文案与代码逐字对应；`eval` 可用由 O14 的"无失败行"佐证，但 O14 的实际输出未保存）。

---

## D6：`tab-close 3` 越界 —— 0-based 语义 + 已打印的 index 未被使用

**Severity:** Low · **Category:** UX · **复现状态:** 高置信

#### 现象

O15：`-s ego92 tab-close 3` → 695ms、exit **1**：`Error: Tab index 3 is out of range. There are 3 tabs (indices 0-2).`

#### 根因

1. **索引 0-based**：`main.rs:5301–5310`（`tab-close` 的越界分支）与 `main.rs:4999 validate_tab_select_target()`（5017–5026 行）使用同一语义，上界 `len − 1`。3 个标签 → 0..2，`3` 不存在。
2. **`tab-new` 其实已经把新标签的 index 和 GUID 打印出来了**：`main.rs:4919` 打印 `Created tab with GUID: … (url)`，随后（4951–4972 行）按 GUID 在列表里定位并打印 `Switched to tab {index} ({url})`。也就是说 O12（`tab-new chrome://version`）的输出里**应当含有正确的 index**，agent 仍然凭 1-based 计数猜了 `3`。
3. **但这条打印有前提**：定位新标签依赖紧随其后的一次 `list`（4923–4936 行）；`list` 拿不到新标签时，只打印 GUID、不打印 index（`None` ⇒ `tabs.last()` 兜底，或压根不进入 `if let Some(new_tab)`）。所以"只有 GUID、没有 index"是一种真实可能的输出形态。

#### 判定

非产品缺陷（错误信息准确、上界清楚）。可改进性很低：唯一低成本补偿是在越界文案里回指 `tab-list` / `--guid`。

#### 候选修复（未实施）

| 改动 | 位置 | 内容 |
|---|---|---|
| 越界文案补指路 | `main.rs:5304` 与 `5020` 两处同款 format | 追加 `Run \`tab-list\` for current indices, or pass --guid <guid>.` |
| （可选）`tab-new` 兜底打印 | 4904–4949 行 | 当 `list` 未命中时，用"创建前/创建后列表差集"定位新标签，保证 index 一定会打印 |

#### 置信度：高（文案与 format 串逐字一致；`tab-new` 的打印路径已读代码确认）。

---

## D7：非缺陷观察 —— 55 分钟 / 16 次调用 / 1.65M tokens

不属于 issue 流水线的条目，记录在此供流程改进：

1. **同一失败族内连续改写法**：O4–O7 换会话名（3 次）、O8→O9 换解释器（1 次）。建议在 agent 侧规则里写死"同一能力被拒 2 次即停止换写法，改为读结果 / 上报阻塞"。
2. **"成功"没有被消费**：O3 成功、O4–O7 与 O6/O7 的返回从未被后续步骤引用。
3. **信息其实早就到手**：O12 打开 `chrome://version` 后，一条 `eval`（O14）即可拿到 `#profile_path` / `#executable_path` / `#command_line`；在 O13 之前就做这一步可省掉 O1–O11 的绝大多数调用。

结论：这轮的成本主要花在**策略选择**上，而不是工具能力上。产品侧能做的只有 D1/D4/D5 那三处提示。

---

## 证据缺口与待确认实验

| ID | 缺口 | 实验（未执行） | 预期 |
|---|---|---|---|
| E1 | `b4.run` 到达 CLI 的 `--json` 值到底长什么样 | 分别用 (a) PowerShell 控制台 (b) `b4.run` (c) Git Bash 跑 `cdp Target.getTargets --json "{\"filter\":[]}"`；再用 `cmd /c echo %*` 等价手段 dump argv | 三者行为不同；`b4.run` 复现 column 2 |
| E1b | 反斜杠是否残留 | 同一命令改用单引号 `--json '{"filter":[]}'` | 单引号版本在 PowerShell 下成功（可作临时规避写进文档） |
| E2 | `-s <从未创建的会话名>` 的真实输出 | `b4 -s definitely-not-a-session tab-list` | 报 `no_active_session_message()`，非零退出 |
| E3 | O4–O7 各自返回了什么 | 在 `ego92`/`egolite2`/`probe42350` 上重跑 `cdp Target.getTargets` + `tab-list` | `ego92` 有 page target，另两个为空 → 证实"空 = 成功" |
| E4 | `chrome://version` 上 `eval --file` 是否稳定可读 | `tab-new chrome://version` → `eval --file probe.js`（`JSON.stringify({p:…innerText})`） | 三个字段均可读；`#profile_path` 含实际配置目录 |
| E5 | `tab-new` 的 index 打印是否稳定 | 新建 3 个标签，检查是否每次都出现 `Switched to tab N` | 稳定出现；若不稳定则 D6 的兜底修复升级为 ACCEPT |

---

## 候选修复清单（**全部未实施**）

| # | 位置 | 改动 | 优先级 | 风险 | 验证 |
|---|---|---|---|---|---|
| F1 | `main.rs: build_cdp_tool_params()` | `--json` 解析失败时提示 `--file` / `--stdin` | P1 | 极低 | `cargo test --bin browser4-cli`（补断言 36213 附近） |
| F2 | `HTMLSnapshotToolExecutor.requireArchivableActivePage()` | 拒绝文案补"用 `eval`" | P1 | 极低 | `HTMLSnapshotToolExecutorTest` |
| F3 | `CodingAgentShell`（内联脚本拒绝） | hint 补"写成脚本文件 / requestAccess" | P2 | 低 | `CodingAgentShellPathGuardTest` |
| F4 | `CodingAgentShell`（`COMMAND_NOT_ALLOWED` hint） | 按 violation 分类给出对症 hint | P2 | 低 | `PermissionDecisionBaselineTest` 快照需同步 |
| F5 | `main.rs:5304` / `5020` | 越界文案回指 `tab-list` / `--guid` | P2 | 极低 | 单测（`37403` 附近已有断言） |
| F6 | `skills/browser4-cli/SKILL.md` + `references/htmlsnapshot.md` | 加"WebUI 页只支持 `eval`" | P1 | 无 | 文档审阅 |
| F7 | `skills/browser4-cli/references/agent.md` | 加"`cdp` 空数组 = 成功" | P2 | 无 | 文档审阅 |

> 按用户要求，**本轮不实施 F1–F7**，也**不改任何现有测试**。

---

## 状态

| 项 | 值 |
|---|---|
| 状态 | **Open — 已诊断，未修复** |
| 代码/文档改动 | 无（本文件与配对的现象文件为新增） |
| 与工作树未提交改动的关系 | 无交集（那些改动是 X-SQL `:expr` 提示相关） |
| 下一步 | 人工评审：D1/D4/D5 三处文案是否 ACCEPT；D2/D3/D6 是否 WONTFIX/DEFER；F1–F7 是否进 `1ready` |
