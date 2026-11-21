# Issues: 工作区里的 junction 让沙箱把**任意**区外目录降标签并授权写入（permission-e2e A17 实测）

> **Source:** 2026-11-20 11:49 本机实测（live backend `windows-low-il`，手工复现 `permission-e2e` A17 的探针）
> **Date:** 20261120-1200 · **Mode:** dev
> **状态:** **已修复 2026-11-20 12:05**（`MirrorRoots` 不再把任意链接目标当授权根）
> **代码基线:** `4.16.x` @ `6df6fd520e`
> **报告人:** 本会话 agent（实现 `permission-e2e` P3-14 时发现）

---

## 0. 结论摘要

| 编号 | 问题 | 是产品缺陷吗 | 置信度 | 建议决策 |
|---|---|---|---|---|
| I1 | `MirrorRoots.derive` 把**任意**"解析到工作区外"的链接目标当作镜像根，而镜像根会被策略层当作**可写根**、并被 `windows-low-il` **降为 Low 完整性标签** | 是（越界写入 + 安全标签污染） | 高 | ACCEPT |
| I2 | 结果是：工作区内一个指向区外的 junction，使低完整性子进程**成功写入区外目录**（本应被内核拒） | 是（边界逃逸） | 高 | ACCEPT |
| I3 | 该沙箱还会**修改区外目录的安全标签**（`Mandatory Label\Low:(OI)(CI)(NW)`），属于对非授权目录的副作用 | 是（副作用） | 高 | ACCEPT |

**一句话**：镜像/链接的支持把"任何链接目标"都当成了"这个任务可写的目录"，于是"在工作区里造一个指向 C:\Users\x 的 junction"就等于"把 C:\Users\x 交给低完整性进程写"。

---

## 1. 复现（最小、可判）

```powershell
$cli = 'cli\browser4-cli\target\release\browser4-cli.exe'
$ws  = (& $cli tool call coding_permissions --json '{}' --fail-on-error | Select-String '^Workspace root:').Line -replace '^Workspace root:\s*',''
$target = 'C:\Users\pereg\b4-perm-e2e-canary\label-exp'
New-Item -ItemType Directory -Path $target -Force | Out-Null
"before: " + ((icacls $target) -match 'Mandatory Label' -join '')      # 空 = 没有标签（Medium 默认）
New-Item -ItemType Junction -Path "$ws\.b4-junction-label-exp" -Target $target | Out-Null
$null = & $cli tool call coding_shell --json '{"command":"cmd /c echo hi"}' --fail-on-error   # 触发 prepare
"after:  " + ((icacls $target) -match 'Mandatory Label' -join '')
# → Mandatory Label\Low Mandatory Level:(OI)(CI)(NW)      ← 区外目录被降标签
$null = & $cli tool call coding_shell --json '{"command":"cmd /c echo probe > <ws>\.b4-junction-label-exp\x.txt"}' --fail-on-error
Test-Path "$target\x.txt"      # → True（低完整性子进程写成功）
```

实测（2026-11-20 11:49）：

| 步骤 | 观察 |
|---|---|
| junction 创建后 | 目标目录**没有** Low 标签 |
| 一次 `coding_shell`（`cmd /c echo hi`）之后 | 目标目录出现 `Mandatory Label\Low Mandatory Level:(OI)(CI)(NW)` |
| 经 junction 写入 | `✓ devshell-3 · SUCCESS · exit 0`，`x.txt` 真的出现在区外目录 |

同时 `<data>/sandbox/low-il/` 里出现两条 marker：`true|C:\Users\pereg\b4-perm-e2e-canary\junction-probe` /
`true|…\label-exp` —— 直接证明 `labelWritable()` 是**以区外目录为参数**被调用的。

---

## 2. 根因

`browser4-coding/.../coding/MirrorRoots.kt::derive`：

```kotlin
val isLink = real != null && !samePath(real, absolute)
if (isLink) {
    if (real != null && !real.startsWith(realRoot)) {
        roots.add(real.normalize())   // ← 任何区外目标都成了"镜像根"
    }
    continue
}
```

`derive()` 的返回值有两个消费者，都以"这是本任务可写的目录"为前提：

1. `CodingComponents.boundaryAuthorizer` / `scopeManifest`：**策略层**把这些路径当可写根（所以 `coding.write` 经链接写入也会被放行）；
2. `WindowsLowIntegritySandbox.prepare`：`MirrorRoots.derive(root).forEach { labelWritable(it, deep = true) }`
   —— 对区外目录执行 `icacls <dir> /setintegritylevel (OI)(CI)Low`。`icacls` 对 junction 路径的 ACL 操作落在**目标对象**上，
   于是 Low 子进程获得了写权限（Low 可以写 Low）。

链接本身可以由模型创建：`mklink` 在 shell 白名单外，但**内联脚本体不做层级检查**（`powershell -Command "New-Item -ItemType Junction …"`、
`python -c "os.system('mklink /J …')"`），因此"先造链接、再经链接写"是一条真实可走的逃逸路线
（威胁模型 T7；permission-e2e `A17-junction-traversal` 就是按这条写的）。

---

## 3. 修复

`MirrorRoots.derive` 只接受**某个检出里的模块**作为镜像根（`isCheckoutLinkedModule`）：从目标目录向上最多
`MAX_SOURCE_ROOT_HOPS` 层寻找仓库标记（`.git` 或 `pom.xml` / `Cargo.toml` / `package.json` / `pyproject.toml` /
`setup.py` / `go.mod` / `build.gradle*` / `settings.gradle*` / `Gemfile`）。找不到就**忽略**这个链接：

- 策略层：经链接的写入按**真实路径**判定，落在所有可写根之外 → `PERMISSION_DENIED | code=PATH_DENIED`；
- OS 层：区外目录**不再被降标签**，低完整性子进程写它会被内核拒绝 → `code=SANDBOX_DENIED`。

真实镜像不受影响：镜像目标就是被镜像检出的模块（有 `pom.xml` 等标记），而且**源检出本身**本来就是写根
（`sourceRoot` + `scopeManifest` 的 `SOURCE` grant），所以"通过链接写模块"仍然可用（已有测试覆盖）。

---

## 4. 测试

| 测试 | 守住什么 |
|---|---|
| `CodingAgentFileSystemMirrorBoundaryTest.arbitraryLinkTargetIsNotAGrant`（新） | 指向普通目录的链接**不是**镜像根；经它写入被拒，且目标目录保持干净 |
| 同文件既有用例（已补 `pom.xml` 标记） | 真实镜像（模块链接）仍然可读可写；`sourceRoot` 仍能走到检出版本 |
| `permission-e2e` `A17-junction-traversal` | 端到端：工作区内 junction 写区外 → `code=SANDBOX_DENIED`、canary 不存在（需要**已部署修复**的后端才转 PASS） |
| `A17b-off-twin-junction` | 反向对照：边界关闭时同一条命令必须写出 canary |

---

## 5. 残留与后续

1. **指向另一个检出的链接仍是授权根**：`isCheckoutLinkedModule` 只要求"在某个检出里"。这仍然比"任意目录"窄得多，
   但如果要彻底收口，应让 `WorkspaceMirror` 落一份**清单**（它创建的链接 + 目标），`MirrorRoots` 只信清单。
2. **内联脚本体可造链接**：`mklink` 被禁，但 `powershell`/`python` 内联体不受层级检查——要么把"创建链接"作为
   一类独立的 shell 效果来拒（保守），要么接受"OS 层是最终兜底"（本修复的方向）。
3. 本修复不改变 `permission-e2e` 的判定口径：`A17` 仍要求 **OS 层**拒绝（避免"策略层抢先"伪装成内核边界）。

---

## 6. 关联

- 设计：`docs-dev/copilot/20261120-permission-sandbox-e2e-plan.md` §12（本节记录）、待办 P3-14 / 新增 P0-5
- 同批 issue：`20261120-0145`（拒绝被报成成功）、`20261120-0146`（.NET 文案 / 引号 argv）、`20261120-0156`（`--add-dir` 与 finish gate）
- 代码：`browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/MirrorRoots.kt`
