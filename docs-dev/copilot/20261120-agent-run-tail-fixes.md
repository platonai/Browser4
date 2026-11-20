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

## 第四轮（真实 agent run 暴露的两个沙箱缺陷）

前一轮的 `coding.verify` 在裸 `tool call` 下拿不到 Maven reactor（没有任务工作区镜像），于是改用**真实 `agent run`**（`--coding --wait`）来跑门禁。两次 run 共 3m11s + 51s，暴露两个此前没人测过的问题。

### 4.1 F1：沙箱内**无法编译 Kotlin 模块**（已修）

第一次 run：`coding.verify(module=browser4-coding, checks=compile)` FAILED，但**没有一条编译器诊断**：

```
[ERROR] java.nio.file.AccessDeniedException:
  C:\Users\pereg\AppData\Local\kotlin\daemon\kotlin-daemon-client-tsmarker*.tmp
[ERROR] … kotlin-maven-plugin:2.3.21:kapt (default-kapt) on project browser4-coding: Compilation failure
```

低完整性（Low）子进程写不了 Medium 的 `%LOCALAPPDATA%\kotlin\daemon`，kapt 在**读第一个源文件之前**就失败——默认 `windows-low-il` 后端下，任何要求 agent 修 Kotlin 模块并 verify 的任务必然失败。

修法两层（都落地）：

- `CodingComponents.toolchainEntries()` 新增 `toolchain:kotlin-local`（`%LOCALAPPDATA%\kotlin`）与 `toolchain:kotlin-home`（`~/.kotlin`），并加入 `IsolationProfile.CACHE_ENTRY_IDS`——和 `~/.m2`、`~/.gradle` 一样被标记为可写缓存；
- 受限构建传 `-Dkotlin.compiler.execution.strategy=in-process`（`CodingAgentShell.sandboxId() != "off"` 时），不再需要共享 daemon 的客户端 marker。

**端到端验证**（重建后端 + 同一任务再跑一次）：

```
✔ Agent completed in 51.0s · 1 round · 2 tool(s)
Verification: PASSED (1/1 ran) in 43.1s — compile of module browser4-coding succeeded (exit 0)
VERDICT_JSON: {"passed":true,"module":"browser4-coding","requested":["compile"],…,"exitCode":0,…}
```

### 4.2 F2：verify 输出被截断，`VERDICT_JSON` 无法逐字引用（已修）

第一次 run 里模型明确报告：工具结果中段被 `…[truncated 3275 chars]…` 截断，只能拿到 verdict 的头和尾，**无法逐字引用**——而 finish gate 正是比对这份逐字引用。

根因：失败路径把整段 Maven tail 塞进 `checks[].detail`，单行 `VERDICT_JSON` 超出工具结果的显示预算。

修法（`VerificationReport`）：`detail` 与 `failureReason` 按**头+尾**裁剪（600 / 800 字符，保留首条错误与 `BUILD FAILURE`），`diagnostics` 最多 20 条并新增 `diagnosticsOmitted` 计数。verdict 于是稳定落在预算内；人读摘要仍保留全文。

验证：`VerificationReportTest` 新增用例（长 detail → verdict < 2000 字符、尾部保留、`passed`/`exitCode` 仍可解析、detail 带 `…(cut)…`），11 passed。**未做端到端**：要通过一次 verify 制造失败才能触发，属于刻意构造，收益不如单测钉住契约。


### 4.3 A2：实测把"注入 `[Console]::OutputEncoding`"这条路否掉了

原本的判断：沙箱里 `powershell` 报 `default=gb2312 out=gb2312 in=gb2312`，所以它把子进程的 UTF-8 输出按 GBK 解码。**这个判断被实验推翻**：

| 实验 | 结果 |
|---|---|
| `powershell -Command "& node -e '...中文...'"`（不注入） | **中文**（正确） |
| 同上，`-Command` 里先 `[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)` | 中文（无差别） |
| `powershell -File probe.ps1`，脚本**无 BOM**、含 `'中文'` 字面量 | **涓枃**（乱码） |
| 同一个脚本，**加 UTF-8 BOM** | **中文**（正确） |
| 同一个无 BOM 脚本，改用 `pwsh -File` | **中文**（正确） |

结论：`-Command` 路径下 PowerShell 本来就能正确解码子进程的 UTF-8，**注入是无用功**（还会带来改写命令行的引号风险，因此没有实现）。真正的坑是 **Windows PowerShell 5.1 把无 BOM 的 `.ps1` 按 ANSI 读**——脚本里的中文字面量在运行前就已经坏了；而 `coding.write` 写的是无 BOM UTF-8。仓库里 9393 个 `.ps1` 只有 7 个带 BOM，所以不给 `coding.write` 自动加 BOM。

落地（`48e9b08d02`）：

- 编码提示改成实测结论：写 `.ps1` 要用 `pwsh`；必须用 5.1 时保持 ASCII 或加 BOM；
- `coding.validate(type=script)` 对含非 ASCII 的 `.ps1` 给 WARNING（点名原因与修法）；
- `CliAgentPromptTest` 新增断言（含 `pwsh` / `BOM-less`），`ArtifactValidatorTest` 新增正反用例。


### 4.4 一个会骗人的测试假阳性（已修，2026-11-20 晚）

在**编辑了后端源码但还没重建 bundle** 的状态下跑 Tier A，`A05/A06/A17` 报 FAIL：

```
✗ [coding_shell] ok: expected=False actual=True exit=0
```

但 `.out.txt` 里沙箱**确实拒绝了**（`code=SANDBOX_DENIED`，canary 也没生成）。原因：查看到期 bundle 时 CLI 每次调用都会往 stderr 打一行

```
Backend sources are newer than the runtime bundle; the next backend command rebuilds it automatically (a few minutes).
```

runner 把 stdout+stderr 合并后，这一行成了 payload 首行，于是 `Invoke-CliTool` 的"leading marker"判定（`Error:` / `PERMISSION_DENIED` / `✗ … FAILED`）全部落空 → `ok=True`。重建 bundle 后同一套件 **17 PASS / 0 FAIL / 4 SKIP**。

修法（`run-permission-e2e.ps1`，新增 `Remove-CliAdvisoryHead`）：判定前先剥掉**开头**的 CLI 自我告警行
（`browser4-cli: warning:` / `browser4-cli: note:` / `Backend sources are newer than the runtime bundle` / `⚠`），
只剥开头、只认 CLI 自己的前缀——报告正文里出现的 `PERMISSION_DENIED` 仍然不算"调用被拒"，而 CLI 没有
`browser4-cli: error:` 这种形态。自测新增 5 条（`[cli advisories]` 小节）。

**验证**：在**同一个仍然过期的 bundle**（`staleness: sources_newer_than_bundle`）上重跑
`-Case A05,A06,A17` → **3 PASS**；随后全量 `-Tier A` → **18 PASS / 0 FAIL / 4 SKIP**。
也就是说，判定不再被告警行带偏；但"重建后再信"这条纪律仍然保留（过期 bundle 本身是另一回事）。

## 5. 第五轮（同日，把 fallback 从"CWD 相对"改成"工作区锚定"，并补上用例）

### 5.1 缺陷：fallback 目录是 CWD 相对，一次 `attach` 之后的下一条命令读不到它

`write_state` 在 `~/.browser4` 不可写时改写到 `./.browser4-cli-state`（相对**当前目录**）。
2026-11-20 那次 18 分钟 run 里，`attach` 写进的是 `<cwd-A>/.browser4-cli-state`，下一条 `eval` 在
`<cwd-B>` 里读 `<cwd-B>/.browser4-cli-state` → `Session required`；`attach` 自己已经报告成功。
`BROWSER4_CLI_STATE_DIR` 注入能绕开它，但 CLI 自身的回退仍然会造出第二个目录。

### 5.2 修法：锚点 + 指针 + 迁移（`cli/browser4-cli/src/state.rs`）

`resolve_fallback_dir_in(cwd)` 从工作目录向上找**锚点**，不再"就地取 CWD"：

| 顺序 | 锚 | 结果 |
|---|---|---|
| 1 | 某级目录里的 `.browser4-cli-state/STATE_DIR_POINTER` | 文件里记的路径（迁移写下的稳定指针，单跳不回环） |
| 2 | 最近的 `.b4scratch`（agent 工作区标记） | `<workspace>/.b4scratch/cli-state` —— **与 Java 侧 `CliStateEnv` 注入的目录完全相同** |
| 3 | 最近的检出根（`.git`） | `<checkout>/.b4scratch/cli-state`（`.gitignore:161` 已忽略该目录） |
| 4 | 父级已有的 `.browser4-cli-state` | 复用它（用户自己的目录树也有连续性） |
| 5 | 以上都没有 | `./.browser4-cli-state`，与旧行为一致 |

配套四条：

- **读顺序**：primary → 锚定目录 → **legacy**（旧版 CLI 写过的 `./.browser4-cli-state`）。
  legacy 放在最后，只在锚定目录没有会话时生效，所以不会让旧副本盖住新会话，也不会让
  `close` 之后"复活"一个幽灵会话（既有回归用例
  `test_read_state_does_not_resurrect_fallback_when_primary_exists` 仍绿）。
- **迁移**：`write_state` 首次改道写入时把 legacy 里的文件搬进锚定目录（**目标已存在的不覆盖**、
  搬不动就留着让读路径继续兜底），并只在真的搬动了东西时在 legacy 原位写 `STATE_DIR_POINTER`。
- **`status` 说清楚**：`Session state: <dir> (workspace fallback, <锚> — …)`，`--json` 新增
  `session_state_anchor`（`primary|cwd|ancestor|b4scratch|checkout|pointer`）与既有
  `session_state_fallback`。
- **`is_implicit_default_dir` 认开发模式命名空间**：以前只认扁平的 `~/.browser4`，于是
  `SessionRegistry` 固定的 `~/.browser4/workspaces/<checkout>-<hash>` 被判成"显式目录 → 永不改道"，
  沙箱里会直接抛 `PermissionDenied` 而不是改道。

`close` / `close-all` 同时清锚定目录与 legacy（否则旧副本会被反复计数）。

### 5.3 用例：`A20-sandbox-cli-state-anchored`（Tier A，无浏览器无 LLM）

沙箱里把 `BROWSER4_CLI_STATE_DIR` **删掉**，让 CLI 自己在 `<ws>/anchor-sub-one` 与
`<ws>/anchor-sub-two` 两个目录各跑一次 `status --json`，两次解析出的 `session_state_dir` 必须相同，
且以 `.b4scratch\cli-state` 结尾。夹具新增两个占位符：`{cliExe}`（真实的 CLI 可执行文件——
用例不能走 `b4w.ps1`，那会重建并写工作区外）与 runner 侧的 `Resolve-CliExePath`。

实测证据（`run-20261120-223936`）：

```
✓ devshell-30 · SUCCESS · exit 0 · 1382ms
ANCHOR OK C:\Users\pereg\.browser4\workspaces\Browser4-4.16-02e52167\app-data\agent\2026\11\20\21716e63-…\workspace\.b4scratch\cli-state
```

修复前同一探针会打印 `ANCHOR MISMATCH one=<ws>\anchor-sub-one\.browser4-cli-state two=<ws>\anchor-sub-two\.browser4-cli-state`。
单测新增 9 条（锚点、指针、迁移不覆盖、读顺序、状态报告），`cargo test --bin browser4-cli` **2279 passed**。

### 5.4 手工 e2e：沙箱内 `attach` 一次成功（本轮之前跑的那次）

在沙箱化的 `coding.shell` 里用**一条命令**串起三次 CLI 调用（跨进程读状态，正是 18 分钟 run 里失败的那种形状）：

```
browser4-cli -s cdp-e2e attach --cdp 9222
browser4-cli -s cdp-e2e eval --json location.href
browser4-cli -s cdp-e2e tab-list
```

```
Attached to Google Chrome 156.0.8078.96 at http://localhost:9222
Session opened: cdp-e2e (dbab9235-c8a2-4753-b09d-e225230fe2a2)
{"status":"ok","command":"eval","output":{"result":"https://x.com/home",…}}
（tab-list 列出 5 个标签页）
```

三次调用都成功、没有 `Session required`，原始缺陷（写盘成功但读不回来）在真实沙箱通道上不复现。

**但这次留下了一个真实事故**：9222 上跑的其实是用户自己的 ego lite 浏览器，我另外起的 headless Chrome 没抢到端口，
于是 attach 到了用户的真实浏览器；收尾的 `disconnect` 按语义关闭了本会话驱动的标签页，用户的 `x.com/home`
被关掉（其余标签未受影响，Ctrl+Shift+T 可恢复）。教训写进了 §5.5 第二条：任何 attach 用例必须
**先确认端口的 `/json/version` 属于自己启动的临时 profile**，并用专用端口。

### 5.5 有意不做的两件

- **deny-read 单文件不回退**（决定：保持现状）：状态文件"存在但不可读"只告警 + 当作没有会话，
  不按 mtime 去 legacy 里找更新的副本——mtime 在这类场景里是猜测，猜错就是"复活旧会话"。
- **沙箱内 `attach` 的分层 e2e 仍缺**：手工 e2e 已通过（`attach` → `eval` → `tab-list` 全通，见 §5.4），
  但落成用例需要**专用 CDP 端口**（不能占 9222）并在 attach 前断言 `/json/version` 属于自己启动的
  临时 profile；否则 `disconnect` 会关掉用户真实标签页（2026-11-20 已真实发生一次）。

## 后续（未做）

1. 沙箱内 `attach` 的分层 e2e（见 §5.5 第二条）：专用端口 + 实例断言 + 只杀自己启动的 Chrome。
2. 若将来确认还有别的 PowerShell 乱码路径（不是 BOM-less `.ps1`），再考虑注入 `[Console]::OutputEncoding`；本轮实测它并不解决该症状。
3. junction 残留：指向**另一个检出**的链接仍是授权根（issue §5），"造链接"这类操作值得单独收口。





