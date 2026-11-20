# Issues: `windows-low-il` 下 .NET 风格拒绝文案未被翻译；带引号的参数被 token launcher 打坏

> **Source:** 2026-11-20 10:55–11:00 本机实测（live backend `windows-low-il`，permission-e2e `A05`/`A06`）
> **Date:** 20261120-1100 · **Mode:** dev
> **状态:** **I1/I2/I3 已修复 2026-11-20 12:00**（下一节记录修法与更正）
> **代码基线:** `4.16.x` @ `6df6fd520e`
> **报告人:** 本会话 agent

---

## 0.1 修复（2026-11-20 12:00）

| 编号 | 修复 | 位置 |
|---|---|---|
| I1 .NET 拒绝未翻译 | `MARKERS` += `is denied` / `unauthorizedaccessexception` / `0x80070005`；A05/A06 断言收紧为只有 `code=SANDBOX_DENIED`，并恢复每次拒绝都会写的 `sandbox denied:` 日志断言 | `browser4-coding/.../sandbox/SandboxDenials.kt`；`SandboxDenialsTest` +2 例 |
| I2 引号被打坏 | **根因更正**：不是"没引用参数"，而是把 `cmd.exe /c <text>` 的 `<text>` 当成 argv 元素用 CRT 规则重新引用了；它是 cmd 自己解析的命令行片段，必须原样拼接 | `WindowsTokenLauncher.buildWindowsCommandLine`（`shellTailIndex`）；`WindowsTokenLaunchTest` 新增 6 条规则断言 + 2 个真子进程用例（带引号 `-File`、带空格重定向目标） |
| I3 报告措辞 | `paths outside workspace : NO` 后面补上"除声明的 toolchain/cache 根（`~/.m2`、`~/.gradle`、`%TEMP%`、JDK、SystemRoot）按设计可写" | `CodingToolExecutor.describePermissions` |

实测更正：本文件 §3 早先建议"实现 Windows argv 引用（`CommandLineToArgvW` 的逆过程）"——那条其实**已经实现且对普通 argv 是对的**；
缺陷只在 `cmd.exe /c` 的片段上。补的单测把两种形态分开钉住（普通 argv 仍走 CRT 规则，`cmd /c` 尾部原样）。

---

## 0. 更正声明（先读这一段）

本文件早先（`20261120-0146`）的结论是**错的**，必须撤回：

> ~~「字符串层不检查 `cmd /c … > <path>`，重定向目标不进路径守卫」~~

实测真相是：当时的目标路径位于 **`%TEMP%`**，而 `%TEMP%` 是本项目**声明过的 toolchain/cache 根**
（`CodingComponents.toolchainEntries()` 的 `toolchain:tmp`，且 `IsolationProfile.defaultCaches()` 把它列在可写缓存里），
所以：

- 字符串层**有意**放行它（它在 `toolchainRoots` 里）；
- `windows-low-il` **有意**给它打 Low 标签（`labelWritable(caches())`），于是一个 Low 子进程当然写得进去。

换成**不在任何授权根内**的路径（用户 profile 下），实测立即被拒：

```
# 重定向（路径在命令行里）
Error: PERMISSION_DENIED | code=COMMAND_PATH_DENIED | tool=coding.shell | ... resolves outside the workspace

# 经脚本（路径在脚本体里，字符串层看不见）
PROBE_DENIED: 调用"WriteAllText"并传入"2"个参数时发生异常：
  "Access to the path 'C:\Users\pereg\b4-perm-e2e-canary\…\a06.txt' is denied."
```

所以 **边界是好的**；permission-e2e 的 canary 已从 `%TEMP%` 迁到 `%USERPROFILE%\b4-perm-e2e-canary`（不在任何标签根内）。

本文件保留两条**真实**的残留问题。

---

## 1. 结论摘要

| 编号 | 问题 | 是产品缺陷吗 | 置信度 | 建议决策 |
|---|---|---|---|---|
| I1 | 子进程以 .NET 异常形式拒绝（`Access to the path '…' is denied.`）时，`SandboxDenials` 不翻译，模型拿不到 `code=SANDBOX_DENIED` | 是（可观测性，与 run 4125acaa 同类） | 高 | ACCEPT |
| I2 | `windows-low-il` 下**带引号的参数被 token launcher 打坏**：`pwsh -NoProfile -File "C:\…\x.ps1"` → `pwsh` 用法错误 `exit 64`；`cmd /c "…"` 里的引号变成字面 `\"` | 是（可用性，禁掉一大类命令） | 高 | ACCEPT |
| I3 | `coding.permissions` 的 `paths outside workspace : NO` 没有区分"声明过的 toolchain/cache 根按设计可写" | 是（措辞误导，低危） | 中 | 记录 |

**一句话**：边界本身成立（换到真正的区外路径立刻被拒），但 `windows-low-il` 这条通道上，
一类拒绝**没有结构化**、一类参数**被启动器弄坏**。

---

## 2. I1：.NET 拒绝文案不翻译

`SandboxDenials.MARKERS` 只有 `access is denied` / `permission denied` / `operation not permitted` /
`eacces` / `eperm` / `拒绝访问` / `无权限访问`（`browser4-coding/.../sandbox/SandboxDenials.kt:38`）。
.NET 的 `UnauthorizedAccessException` 文案是：

```
Access to the path 'C:\…' is denied.
```

它既不含 `access is denied`（中间隔了 `the path '…' is`），也不是 `拒绝访问`——于是 `envelope()` 返回 `null`，
模型看到的是**原始英文异常**，而不是 `PERMISSION_DENIED | code=SANDBOX_DENIED | …`。

影响：

- 模型无法区分"OS 拒绝"与"脚本自己写错了路径"，会去改命令而不是改路径（正是 4125acaa 的教训）；
- `agent run --ndjson` 的 `tool.result.ok=false` 有了，但**没有 code**，统计/门禁拿不到归因；
- 任何"只看 code"的调用方（如 permission-e2e 的 `resultContainsAny`）只能退化成匹配英文句子。

**建议**：给 MARKERS 加 `"is denied"` + `"UnauthorizedAccessException"` + `"0x80070005"`；或让
`coding.shell` 在子进程非零退出时把 stderr 也送进 `SandboxDenials.envelope()`（现在是按 stderr 匹配的，
但 .NET 消息来自 PowerShell 的 *stdout*？——实测两路都要覆盖）。

**测试**：permission-e2e `A06-os-script-body` 现在同时接受 `code=SANDBOX_DENIED` 与 `is denied`；
翻译修好后应把宽匹配收紧回只有 code。

---

## 3. I2：引号被 token launcher 打坏

复现（backend `windows-low-il`）：

```powershell
# 1) 带引号的 -File：exit 64（pwsh 用法错误）
browser4-cli tool call coding_shell --json '{"command":"pwsh -NoProfile -File \"D:\\…\\a06.ps1\""}'
✗ devshell-4 · FAILED · exit 64
用法: pwsh[.exe] [-Login] [[-File] <filePath> [args]] …

# 2) 不带引号：正常执行
browser4-cli tool call coding_shell --json '{"command":"pwsh -NoProfile -File D:\\…\\a06.ps1"}'
✓ devshell-3 · SUCCESS · exit 0
PROBE_ESCAPED: …   （Medium 下写得进去；Low 下才是 Access denied）

# 3) 内联 cmd 的引号变成字面量
browser4-cli tool call coding_shell --json '{"command":"cmd /v:on /c \"echo probe > !B4_CANARY!\""}'
'\"echo probe > C:\…\"' 不是内部或外部命令，也不是可运行的程序或批处理文件。
```

同样两条命令在 `sandbox=off`（Medium，普通 `ProcessBuilder` 启动）下**都正常**，所以问题在
`WindowsTokenLauncher.startWithToken` 组装 `lpCommandLine` 的那一步（`browser4-coding/.../sandbox/WindowsTokenLauncher.kt:80` 起）：
它没有按 `CreateProcessAsUserW` 需要的规则重新引用参数（`ProcessBuilder` 会做，token 路径不会）。

**影响**：任何含空格路径、任何需要引号的参数在 `windows-low-il` 下都不可用——这几乎覆盖了真实命令的一半。
**建议**：在 launcher 里实现 Windows argv 引用（`CommandLineToArgvW` 的逆过程），或直接把 `ProcessSpec.command`
的每个元素按 CRT 规则包一层；补 `WindowsTokenLaunchTest` 用例（带空格路径、内联引号）。

---

## 4. I3：报告措辞

`coding.permissions` 输出 `paths outside workspace : NO (refused with PERMISSION_DENIED)`，
但**声明过的 toolchain/cache 根**（`%TEMP%`、`~/.m2`、`~/.gradle`、`~/.cache`、JDK、SystemRoot）按设计可写。
建议把这行改成"outside the workspace **and the declared toolchain roots**"，或在同段列出这些根（报告里已有 toolchain 段落，
只是这行读起来像绝对边界）。

---

## 5. 关联

- permission-e2e：`A04-policy-block-redirect`（改判回**策略层**）、`A05-os-env-composed-path`（`^>` 形式）、
  `A06-os-script-body`（脚本路线，实测 OS 拒绝）、`A15-cache-root-redirect-allowed`（记录 TEMP 按设计可写）
- 同批：`20261120-0145-tool-call-reports-permission-denied-as-success`、`20261120-0156-add-dir-write-fails-finish-gate`
- 设计文档 §9 的实测表格
