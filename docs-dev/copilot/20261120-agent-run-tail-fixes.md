# Agent run 尾部三问题的定位与修复（2026-11-20）

一次 `agent run`（18m41s / 3 rounds / 179 tools / 4.85M tokens）在收尾报告里留下三条"环境问题"。逐条读码后判定：
三条都不是"环境所致、无需处理"，其中两条是产品缺陷，而且是 agent 最贵的那一类——**静默成功、随后失败**。

## 1. `attach` 报成功、随后 `Session required`

**不是** `~/.browser4` "读不到"，而是**能读、不能写**——这正是 `windows-low-il` 的形状（Low 令牌可读 Medium 对象，写被强制性完整性标签拒绝）。

- 写：`write_state` 在 `PermissionDenied` 时把状态改写到 `./.browser4-cli-state`（`cli/browser4-cli/src/state.rs`）。
- 读：`read_state` 只在主目录**不可枚举**时才看 fallback；"可读但不可写"不触发 → 读到空状态 → `Session required`。
- 文档（`skills/browser4-cli/SKILL.md`）与提示（`tips.rs`）都宣称对称，所以是实现漏了一半，不是取舍。
- 放大器：`b4.run` 在边界内**强制**注入 `BROWSER4_CLI_STATE_DIR=<workspace>/.b4scratch/cli-state`（`CliProcessManager`），而 `coding.shell` / 技能脚本只透传操作员已设的值（`AgentToolManager.cliSessionEnv`）——同一 run 里两条通道的 CLI 状态不一致。

**修复**

| 层 | 改动 |
|---|---|
| CLI | 读、写共用一次判定：`should_consult_fallback(implicit, usable, writable)`，可写性用一次 create+remove 探针（进程内缓存），只在主目录无状态文件时才探；`has_persisted_state` 用同一判据，避免陈旧 fallback 影响 dev 端口分配 |
| 产品 | 新增 `browser4-agentic/.../cli/CliStateEnv.kt`：`b4.run`、`coding.shell`、技能脚本统一从它取 `BROWSER4_CLI_STATE_DIR`——操作员显式值优先，否则**仅有边界时**改指 `<workspace>/.b4scratch/cli-state`（无边界保持 `~/.browser4`，与用户终端共享会话） |

权限安全：没有放宽边界。`~/.browser4/config/conf-enabled/` 里是 LLM key，仍需对被沙箱子进程不可读/不可写；状态搬进工作区是唯一不需要扩大授权的解法（`.b4scratch` 已被边界整体授予、git-ignore、且不计入 change set）。

## 2. `coding_validate(type=js)` 把注释里的 `1) 2)` 判成括号不平衡

`ArtifactValidator.checkBracketBalance` 就是 `count('(') - count(')')`，对整文件跑三种括号——注释、字符串、模板串、正则全部计入。

- 假阳性：注释里的列表标记 → `✗ Validation failed` → agent 改写自己的注释再校验（本次 run 的实际代价）。
- 假阴性（更贵）：注释里的 `)` 抵消代码里真正缺的 `)` → 坏脚本通过校验，到 `tab.eval` 才炸。同文件 `:222` 的注释已记录过一次"桩 JS 通过校验"。

**修复**：`checkJsDelimiters` —— 跳过 `//`、`/* */`、单/双引号、模板串（含 `${…}` 代码段）、正则字面量；用**栈**匹配而不是计数（`)(` 也能抓）；报 `line N, col M`。正则/字符串遇到裸换行会回滚为"非字面量"，不会吞掉脚本剩余部分。`validateScript` 的 PowerShell 大括号检查暂保持原样（后续项）。

## 3. Windows 控制台 GBK 乱码

采集层在"猜编码"，而不是"定契约"：

- `CodingAgentShell.decodeProcessOutput` 是**整段**先试严格 UTF-8，失败则整段按 `native.encoding`（GBK）解 → 子进程里**一行** GBK（`cmd.exe` 本地化报错）就能把整段 UTF-8 输出变成乱码。同仓库的 `CliProcessManager.decodeLine` 已经是**按行**回退，说明这是遗漏而非设计。
- 上游：`pwsh -Command "node x.js"` 里 PowerShell 用 GBK 解子进程的 UTF-8 字节再以 UTF-8 写出，采集层无法还原 → 只能给子进程明确的编码契约（`[Console]::OutputEncoding`、`PYTHONIOENCODING`/`PYTHONUTF8`）。
- CLI 自身：`main.rs` 只给内部 `run_shell_command` 加了 `chcp 65001`；`cli/README.md` 让用户自己 `chcp`。

**状态**：见下方"第二轮"。

## 第二轮（同日，收尾上一轮的三项遗留）

### 3.1 采集层按行解码，两个通道共用一份实现

- `CodingAgentShell.decodeProcessOutput` 不再"整段试 UTF-8、失败整段 GBK"，改为：整段是合法 UTF-8 → 直接采信；否则**逐行**解码（`decodeLine`），一行坏不连累其余。
- 新增 `CodingAgentShell.decodeLine`，`CliProcessManager.decodeLine` 改为委托它 —— `b4.run` 与 `coding.shell` 不会再各写一套。
- 关键的歧义处理：GBK 文本可能**本身就是合法 UTF-8**（`目录` = `C4 BF C2 BC` = `U+013F U+00BC`），"严格 UTF-8 优先"会把它读成 `Ŀ¼`。真实 UTF-8 非 ASCII 一般是 3/4 字节，因此"非 ASCII 全是 2 字节序列"的行按平台编码解；含 CJK/emoji 的行保持 UTF-8。整段合法 UTF-8 的流仍走快路径（`café` 这类只有 2 字节非 ASCII 的正常 UTF-8 不会被误判）。
- 残余限制（已写进 KDoc）：若整个 capture 恰好处处合法 UTF-8、且唯一的 GBK 行也是合法 UTF-8，则无法区分，按 UTF-8 读。

### 3.2 `attach` 类写入"回读校验"

校验放在 `state::write_state` 而不是 `handle_attach`，一次覆盖所有写入方（extension/CDP/endpoint 三条 attach 路径、`goto`/`open`/session 刷新）：

- 写成功后立刻按**同一套 fallback 判据**回读，`session_id` 不一致就返回错误，消息带真实文件路径 + `BROWSER4_CLI_STATE_DIR` 提示。
- 这样"写进去"与"下一条命令能读到"成为同一个事实，`attach` 不再可能先报成功、后报 `Session required`。

### 3.3 PowerShell 大括号检查

`validateScript` 的 ps1 分支改用 `checkPowershellBraces`：跳过 `#` 行注释、`<# #>` 块注释、单/双引号串（`''`/`""`/反引号转义）、`@'…'@`/`@"…"@` here-string；用栈匹配并报 `line:col`。旧实现 `checkBracketBalance`（纯 `count`）随之删除。

### 3.4 权限 e2e 用例

`browser4-tests/permission-e2e/cases.json` 新增 `A19-sandbox-cli-state-in-workspace`（Tier A，`needs: windows,sandbox`，`backend: windows-low-il`）：

```
coding_shell(command="cmd /c echo %BROWSER4_CLI_STATE_DIR%")
  → resultContains [".b4scratch\cli-state"]
```

一行、不需要浏览器或 LLM：环境变量没注入就当场失败，而不是在 19 分钟的 agent run 里以 `Session required` 出现。**只在 `-List` 层面验证过解析与注册（36 例），未在真实后端跑过**（需要重启到本轮构建的 backend 才能看到新行为）。

## 验证

| 范围 | 命令 | 结果 |
|---|---|---|
| CLI 全套单测 | `cargo test --bin browser4-cli` | 2265 passed / 0 failed / 4 ignored |
| 状态目录 | `cargo test --bin browser4-cli -- state::` | 71 passed（+ 回读校验的两条用例） |
| coding 模块 | `mvn -o -pl browser4-coding -Dtest=ArtifactValidatorTest,CodingAgentShellTest test` | 67 passed（ArtifactValidatorTest 47 / CodingAgentShellTest 20） |
| agentic 模块 | `mvn -o -pl browser4-agentic -Dtest=CliOutputDecodingTest,CliProcessManagerTest,CliProcessManagerSandboxTest,CliStateEnvTest test` | 24 passed |
| 权限 e2e 注册 | `pwsh browser4-tests/permission-e2e/run-permission-e2e.ps1 -List` | 36 例，A19 在列 |

## 第三轮（同日，收掉 `validate*` 与编码的剩余口子）

### 3.1 `validateKotlinToolExecutor` 改用词法扫描

`validateKotlinToolExecutor` 的 `{`/`(` 原来是裸计数。ToolExecutor 里几乎全是 prompt/JSON 字面量，一个 `"{ref}"` 或注释里的 `{` 就会被判成"文件不平衡"，而这个 ERROR 会挡住能编译的文件。

新实现 `checkKotlinDelimiters`：跳过 `//`、**可嵌套** `/* */`、raw `"""` 字符串、字符字面量、反引号标识符；用栈匹配并报 `line:col`。Kotlin 的 `${…}` 插值**是代码**且内部可以有引号（`"${map["key"]}"`），所以字符串在 `${` 处挂起、由深度回到入栈处的 `}` 恢复——不处理这一点会把 `]` 当成"无对应开符"而误报。

### 3.2 `validateJs` 的词启发式只看代码

`return ` / `eval(` / `document.write(` / `(function` 原来直接 `contains` 原文：注释里的"no return value"能满足 return 检查，注释或字符串里的 `eval(`/`document.write(` 会误报 WARNING。新增 `jsCodeOnly(content)`（注释、单双引号串、模板串、正则体置空，保留换行与位置），启发式改在投影上判断；`use strict` 检查仍读原文（它本身就是字符串指令）。

### 3.3 子进程 Python stdio 编码契约

`SandboxEnvironment.build` 新增默认值 `PYTHONIOENCODING=utf-8`：zh-CN Windows 上 `print('中文')` 会把 GBK 字节写进 agent 按 UTF-8 读的管道。只改 **stdio**——`PYTHONUTF8=1` 会连带改变脚本打开文件的方式，可能弄坏原本能跑的任务，因此不强制。继承环境里的同名变量不在白名单内（进不来），显式声明的值优先。

### 3.4 Windows 控制台码页

`browser4-cli` 在 `main` 里、打印第一个字节之前，若 stdout 是终端且控制台码页不是 65001，就 `SetConsoleOutputCP(65001)`（`kernel32` 直接 FFI，无新依赖）。重定向到管道/文件的行为不变——那本来就是 UTF-8 字节。`cli/README.md` 的"每条命令都加 `chcp 65001`"改成"CLI 自己会切；只有已按旧码页启动的 shell 在**管道**场景下仍需要"。

### 第三轮验证

| 范围 | 命令 | 结果 |
|---|---|---|
| CLI 全套单测 | `cargo test --bin browser4-cli` | 2268 passed / 0 failed / 4 ignored（含控制台 FFI 的链接冒烟测试） |
| coding 模块 | `mvn -o -pl browser4-coding -Dtest=ArtifactValidatorTest,CodingAgentShellTest,ExecutionSandboxTest test` | 86 passed（50 / 20 / 16） |
| agentic 模块 | `mvn -o -pl browser4-agentic -Dtest=CliOutputDecodingTest,CliProcessManagerTest,CliProcessManagerSandboxTest,CliStateEnvTest test` | 24 passed |
| 控制台实测（真实控制台） | `Start-Process cmd.exe`（新控制台）→ 跑 `browser4-cli --version` → P/Invoke 读 `GetConsoleOutputCP`/`GetConsoleCP` | `out=65001 in=936`：切换生效。**注意 `chcp` 无参打印的是输入码页**，用它验会被误判成"没变" |
| A19（真实 `windows-low-il` 后端） | `pwsh browser4-tests/permission-e2e/run-permission-e2e.ps1 -Case A19-sandbox-cli-state-in-workspace` | **PASS (9.9s)**；`coding_shell` 输出 `<task>\.b4scratch\cli-state` |
| 整个 Tier A（沙箱后端，新构建） | `run-permission-e2e.ps1 -Tier A` | **17 PASS / 0 FAIL / 4 SKIP**（A12 需 bwrap；A13/A14/A17b 需 `sandbox=off`） |
| 边界关闭的 twin | 后端以 `BROWSER4_SERVER_OPTS=-Dbrowser4.agent.sandbox=off` 重启后 `-Case 'A13-off-twin-script,A14-off-twin-env-composed,A17b-off-twin-junction'` | **3 PASS**（反空转成立） |
| A19 复跑（恢复沙箱后端后） | `-Case A19-…` | PASS (6.1s) |

> 重启后端时踩到一次：`./b4w.ps1 open` 在 `stop` 之后挂了 10 分钟，没有 JVM、没有新日志；杀掉重试（不设 `BROWSER4_CLI_DISABLE_AOT_CACHE`、不截断输出）后 13.6s 正常。原因未定位，疑似 AOT 训练锁/首启竞争；在此记录以免下次当成"我改坏了"。

### 3.5 文档

`tips.rs` / `help.rs` 的 `BROWSER4_CLI_STATE_DIR` 说明补上"读写同一目录 + agent 沙箱指到 `<workspace>/.b4scratch/cli-state`"；`docs/metadata-files.md` §8 增 `Fallback`、`In agent sandboxes`、`Write check` 三行。

## 后续（未做）

1. PowerShell 的 `[Console]::OutputEncoding`：没有对应的环境变量可设，只能给后端进程切控制台码页（`SetConsoleOutputCP`）或在命令前注入初始化语句；两者都需要在真实 GBK 控制台上实测，本轮先做能验证的部分（Python stdio + CLI 自身控制台）。
2. 状态文件"存在但不可读"（deny-read 单文件）仍不进 fallback：只在文件缺失时回退。更好的修法是"读失败就报错"，而不是静默返回默认状态。
3. fallback 目录是 CWD 相对：读写已一致，但跨不同工作目录仍不连续。
4. `A19` 已验证（见上），但只覆盖"环境变量是否注入"；"沙箱内 `attach` 一次成功"仍需一条带浏览器的 e2e。
5. `jsCodeOnly` 把模板字面量整体置空：`${…}` 里的代码关键词不再参与启发式（对词匹配是保守选择；要更准可复用扫描器状态机）。



