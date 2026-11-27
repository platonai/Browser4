# 评估：在 `coding.shell` 中执行大段脚本

- 评估日期：2026-11-22
- 版本：`4.16.10-SNAPSHOT`
- 环境：Windows 11 + GraalVM JDK 25，`powershell` 5.1 / `pwsh` 7.6.10，Python 3（`cmd.exe` 为 `coding.shell` 的平台壳）
- 方法：直接实例化 `CodingAgentShell`（`sandbox = OffSandbox`，与单测一致）做黑盒探针，测完即删；**工作区未提交任何改动**（`git status` 干净）

## 结论（TL;DR）

**能用，但只在“小”的范围内**：`coding.shell` 把整条命令作为一个 argv 交给 `cmd.exe /c`（Windows）或 `sh -c`（POSIX），
命令串在 **≈8,180 字符**处就硬失效，且**没有长度门**——校验层说 `allowed`，执行层给出本地化的
`命令行太长。`。大段脚本的正确姿势是**落盘再执行**：`coding.write` → `coding.shell("python .b4scratch/x.py")`。

四条实测得到的硬约束：

| # | 现象 | 触发条件 |
|---|---|---|
| F1 | `✗ exit 1 · stderr 命令行太长。` | Windows 命令串 ≥ 8,192 字符（`cmd.exe` 限制；与策略无关） |
| F2 | `Error: I/O failure for '<整条命令>' — CreateProcess error=206` | 命令串 > 32,767 字符（`CreateProcess` 上限） |
| F3 | **只执行第一行**，其余静默丢弃，`exit 0 · SUCCESS` | 多行脚本**不加引号**直接传给 `coding.shell` |
| F4 | 校验耗时 ≈ **95 ms / 行**（201 行 → 20.5 s） | `powershell -Command` / `bash -c` / `cmd /c` 的 inline body，且行首不是白名单命令 |

## 1. 执行路径与“为什么是命令串”

`CodingAgentShell.runCommand()`（`browser4-coding/.../CodingAgentShell.kt:2761`）只构造一个 argv：

```kotlin
val commandLine = if (isWindows) listOf("cmd.exe", "/c", command) else listOf("sh", "-c", command)
```

于是整段脚本都挤在**一个命令行**里，没有任何长度检查（`validateCommand` 只做策略判定），
脚本大小直接撞到操作系统的上限：

- Windows：`cmd.exe /c` 的整条命令行上限 **8,191** 字符（实测命令串 8,162 通过、8,192 失败，
  `cmd.exe ` + `/c ` 前缀另占 10 字符）；`CreateProcess` 总量上限 **32,767** 字符（实测 32,027 仍是
  `命令行太长`，33,012 已变成 `error=206`）。
- Linux：单参数 `MAX_ARG_STRLEN` = **128 KiB**，整体 `ARG_MAX` ≈ 2 MB（本机未实测）。
- macOS：`ARG_MAX` ≈ 1 MB 总量。

**跨平台含义**：同一个 20 KB 的 inline 脚本在 Linux 上能跑、在 Windows 上必失败——而 CI 两条腿都跑。

## 2. 实测数据（Windows / `cmd.exe`）

### 2.1 长度阈值

| 探测 | `command.length` | 结果 |
|---|---|---|
| `python -c "<2 KB 多行 body>"` | 2,033 | ✓ 145 ms（其中校验约 5 ms） |
| `python -c "<body>"` | 8,111 | ✓ 107 ms |
| `python -c "<body>"` | 8,162 | ✓ 进程确实启动（`python` 自己报 `Traceback`） |
| `python -c "<body>"` | **8,192** | ✗ `exit 1`，`stderr`：**命令行太长。** |
| `python -c "<body>"` | 33,012 | `Error: I/O failure for '…'` + `CreateProcess error=206, 文件名或扩展名太长。` → **返回 33,190 字符** |
| `python -c "<body>"` | 40,012 | 同上 → 返回 **40,190 字符** |

两个细节值得注意：

1. **8 KB 失败没有任何可诊断性**：`allowed=true`，`explain()` 说“`python` is a dev tool”，
   然后 stderr 是一句本地化的 `命令行太长。`。模型通常不会把它和“脚本太大”联系起来。
2. **>32 KB 时错误把整条命令回显**（`execute()` 的 `catch (e: IOException)` 在
   `CodingAgentShell.kt:860` 用 `$command` 插值）。40 KB 脚本 → 40 KB 工具结果，
   既灌日志也灌上下文；再被 `ToolOutcome` 截到 3,000 字符后，真正的原因
   （`CreateProcess error=206`）落在被裁掉的尾部。

### 2.2 多行脚本：静默只跑第一行（最危险）

```
coding.shell("echo AAA>>f.txt\necho BBB>>f.txt\necho CCC>>f.txt")
→ ✓ SUCCESS · exit 0        f.txt 内容只有 "AAA"
```

`cmd.exe /c` 对命令串里的换行**不当作语句分隔符**，第一行之后的内容被丢弃，退出码仍是 0。
校验层反而很“配合”：`splitSimpleCommands()` 把三行拆成三个可独立校验的段（`CodingAgentShell.kt:2549`）、
每段都合法，于是**执行了十分之一却报告成功**——典型的 silent-wrong。

正确写法是把它们变成一条真正的命令链，或落盘：
`cmd /c "echo AAA>>f & echo BBB>>f & echo CCC>>f"`（实测三行都写入了）。

### 2.3 inline body 的校验成本：≈95 ms / 行

`powershell -Command "<body>"` 的 body 会被当作**脚本**递归校验
（`validateSimpleCommandTier` 的 `inlineScript(...)` 分支，`CodingAgentShell.kt:2207`），
再按换行拆成一段段“命令”。每段行首若不是白名单命令，就会走到

```kotlin
if (allowDevTools && !buildOnly && isCommandOnPath(baseCommand)) return null   // :2300
```

而 `isCommandOnPath()`（`:2743`）**每次都会 spawn 一个 `where <cmd>` 子进程，且没有缓存**。
真正的“inline body 一律放行”的判定在 `:2322`，**排在 PATH 探测之后**，所以探测白做了。

| inline body | 行数 / 字符 | 校验耗时 | 进程本身 |
|---|---|---|---|
| `$v0 = 0 …` × 5 + `Write-Output` | 6 行 / 90 | **666 ms** | — |
| × 20 | 21 行 / 230 | **2,074 ms** | — |
| × 60 | 61 行 / 630 | **5,928 ms** | — |
| × 120 | 121 行 / 1,270 | **11,803 ms** | — |
| 200 行脚本 inline | 201 行 / 2,235 | **20,911 ms** | 329 ms |
| 同样的 120 行，但每行都是 `echo` | 121 行 / 1,484 | **1 ms** | — |
| `python -c` 的 120 行 body | 121 行 / 1,115 | **2 ms** | — |

最后两行是关键对照：**成本来自“行首 token 不在白名单”**，与脚本长度无关；
`python -c` 不在 `inlineScript()` 的宿主列表（`sh/bash/zsh/dash`、`pwsh/powershell`、`cmd`）里，
body 只是一个带引号的参数，所以几乎零成本。

同一份 200 行脚本落盘执行：`powershell -NoProfile -File large.ps1` 命令串 37 字符，
**总耗时 495 ms**（而 inline 是 20.9 s，40 倍差距；该次执行本身因执行策略被拦，见 §2.7，
495 ms 代表的正是“校验 + 启动”成本）。

### 2.4 落盘 vs inline（同一份 26.9 KB 的 python 脚本）

| 形式 | 命令串 | 结果 |
|---|---|---|
| `python large.py`（文件 26,922 字节） | 15 | ✓ 167 ms，`total 1999000` |
| `python -c "<同样的脚本>"` | 26,934 | ✗ `命令行太长。` |

### 2.5 输出上限：三级漏斗

1. `truncateOutput()`（`:2917`）：`MAX_OUTPUT_CHARS = 200_000`。
   **只有** `mvn/mvnw/gradle/cargo/npm/pytest/go/dotnet/tsc/eslint/…` 才“保头 2,000 + 保尾”
   （`DIAGNOSTIC_COMMAND`，`:771`）；`python`/`node`/自定义脚本是**只保头**——脚本把结论打在最后时，
   结论正好被砍掉。实测 300,000 字符输出 → 结果 200,083 字符，尾部丢失。
2. `ToolOutcome`（`browser4-agentic/.../ToolOutcome.kt:83`）：模型只看得到
   `BODY_BUDGETS["shell"] = 3_000` 字符（头 2/3 + 尾 1/3，`:233`）。
3. `RoundToolDigest` / 事件日志里另有各自的折叠。

含义：**脚本的“值”要打印在最前面，或者写文件再用 `coding.read` 分段读**；
`coding.read` 单次预算只有 1,600 字符（`:85`）。

### 2.6 超时与“分离的子进程”

- `timeoutSeconds` 默认 120、上限 600（`MAX_TIMEOUT_SECONDS`，`:330`；工具层默认
  `CodingToolExecutor.kt:999`）。实测 `ping` + `timeout=1` → `✗ exit -1 · TIMED OUT`，头行有标记。
- 命令退出后若仍有子进程持有管道，`awaitCapturedOutput()` 只等 **5 s**
  （`OUTPUT_CLOSE_GRACE_SECONDS`，`:337`），然后标注
  `stdout/stderr stayed open … captured output may be incomplete`——后台化服务器脚本常见此提示。

### 2.7 其它相关约束（读代码确认）

- **执行策略**：本机 `powershell -NoProfile -File x.ps1` 被拦（`is not digitally signed`），
  `powershell -ExecutionPolicy Bypass -File x.ps1` 与 `pwsh -File x.ps1` 均可。
  SKILL.md 里推荐的 `coding.shell(command="powershell -File build.ps1")` 在默认 Windows 上会因此失败。
- **环境**：`SandboxEnvironment.build()` 只放行白名单（`PATH/TEMP/SYSTEMROOT/PYTHONPATH/…`，
  `ExecutionSandbox.kt:525`）+ 显式声明的变量；脚本**不会**继承后端的 API key。
- **沙箱**：`browser4.agent.sandbox=windows-acl|windows-low-il` 时，工作区外的写入（含 `%TEMP%`）
  由 OS 拒绝，变成 `SANDBOX_DENIED`。临时文件一律放 `.b4scratch/`。
- **替代通道 `coding.runCode`**：脚本写进**私有 temp 目录**再执行（`CodeRunner.kt:110`），
  因而没有 8 KB 限制；但输出上限 20,000 字符、超时 `coerceIn(1,120)`（`CodingToolExecutor.kt:1615`），
  且 cwd 在 workspace 之外——它适合“纯计算/转换”，不适合需要工程文件的脚本。

## 3. 建议的用法（给 agent 与文档）

1. **默认落盘**：`coding.write(path=".b4scratch/job.py", content=…)` →
   `coding.shell("python .b4scratch/job.py")`。命令串 < 100 字符，无 8 KB 上限、无逐行校验、可重跑可 diff。
2. **一次性小片段**才用 inline，且优先 `python -c`（不受逐行校验影响）；`powershell -Command` / `bash -c`
   只放几行，否则 95 ms/行的校验会吃掉几秒到几十秒。
3. **PowerShell 落盘执行**用 `pwsh -File` 或 `powershell -ExecutionPolicy Bypass -File`。
4. **输出**：> 3 KB 就写文件；结论/JSON 打在**最前面**（非诊断命令只保头），或直接用
   `coding.read` 分段读。
5. **长任务**显式给 `timeoutSeconds`（≤600）；需要后台服务时预期“output may be incomplete”。
6. 不要把多行脚本裸传给 `coding.shell`（只会跑第一行且报成功）。

## 4. 建议的工程改动（按收益排序）

| 优先级 | 改动 | 收益 |
|---|---|---|
| **P0** | 给 `coding.shell` 加**命令长度门**：Windows > ~7,500 字符、POSIX > 100,000 字符时结构化拒绝（`COMMAND_TOO_LONG` + hint “write it with coding.write and run the file”）。当前是“策略放行、执行必败”，且拒绝文本本地化、无行动指引 | 把不可诊断的 OS 错误变成一句可执行的提示 |
| **P1** | `isCommandOnPath()` 结果缓存（每 shell 一个 `ConcurrentHashMap`），或对 `inlineBody` 跳过 PATH 探测（`:2322` 最终就是放行，`:2300` 的探测纯浪费） | inline body 校验 20,911 ms → < 1 s（实测 95 ms/行） |
| **P2** | I/O 失败时**截断回显命令**（如 200 字符），并让 `Error: …` 进入 `ToolOutcome` 的失败分类（`isRefusal()` 只认 `PERMISSION_DENIED`，所以 `CreateProcess error=206` 目前渲染成 `[ok]`） | 40 KB 结果 → 几百字符；失败不再伪装成成功 |
| **P3** | `truncateOutput()` 对脚本类命令也保尾；SKILL.md/`shell-quoting.md` 增加“大脚本落盘 / PowerShell 执行策略 / 8 KB 上限”三行说明 | 少一类“输出被砍”的困惑 |

**测试缺口**：现有 `CodingAgentShellTest` 没有任何命令长度用例，也没有 inline body 校验成本的用例
（校验延迟不会让断言失败，只会让 CI 变慢）。P0/P1 落地时应各配一个
（例如 `isAllowed` 对 40 KB 命令仍为 true 但 `execute` 被拒；`isAllowed` 对 121 行 PS body < 200 ms）。

## 5. 复现方式

探针即一个 `@Test`：`CodingAgentShell(baseDir = 临时目录, sandbox = OffSandbox)`，
对上面每条命令调 `isAllowed()` / `explain()` / `execute()` 并打印
`command.length / 耗时 / exit / result 头尾`。本次三组探针（长度扫描、逐行校验成本、落盘 A/B）
均在 `browser4-coding` 模块内以 `mvn -o -pl browser4-coding test -Dtest=ShellLargeScriptProbeTest`
跑完即删除，未进入版本库。
