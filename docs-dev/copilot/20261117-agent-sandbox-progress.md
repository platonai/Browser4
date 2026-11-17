# agent run 权限体系重构 — 进展快照（2026-11-17 21:20）

状态：**P0 / P1 / P1.5 / P2 / P3 前六步已落地并测试通过**，browse 的容器后端可用但**默认未启用**（opt-in）。
设计正文见 [`docs/agent-permissions-redesign.md`](../../docs/agent-permissions-redesign.md)（§0–§16），
本篇是执行侧的快照：做了什么、怎么验证、还剩什么、怎么开关。

仓库：`Browser4` 分支 `4.16.x`；工作区 `D:\workspace\Browser4\Browser4-4.16`。
测试最近一次结果：`browser4-coding` 全量 **889 例全绿**（其中 `sandbox` 包 101 例，明细见 §6），`browser4-agentic` 权限/CLI 定向 **469 例全绿**，全 reactor `compile`（29 模块）通过。

---

## 1. 目标与已定决策

三条目标（用户 2026-11-17 给定）：

1. browse 模式：围绕 browser4 命令与浏览器目标，最小权限；允许写脚本，文件访问限制在工作目录；
2. coding 模式：围绕程序开发，放宽权限（可开发任意项目、支持 Browser4 自开发）；**读继承当前用户、写限工作目录**；
3. 权限限制必须在 **OS 层**生效，且对被启动的程序（子孙进程）同样有效。

已拍板的决策：

| # | 决策 |
|---|---|
| D1 | coding 的"读继承用户"**不含**凭据库（`~/.ssh`、`.aws`、`.gnupg`、`.config/gcloud`） |
| D3 | coding 网络默认 **off + 逐域申请**（a 方案）；后端能兑现就兑现，不能就如实标注 |
| D5 | Windows browse 用 **AppContainer（隔离优先）**，restricted-token 降为备选 |
| 其他 | 由实现方决定：模式矩阵、后端优先序、就绪门控、默认保留 `windows-acl` |

---

## 2. 分期与状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | 权限模型进代码：`AgentMode` / `IsolationProfile`（读/写/网/执行四轴）/ `forMode()` 矩阵 / `defaultCaches()` | ✅ 完成 |
| P1 | `windows-low-il` 后端（coding）：受限 + Low 完整性令牌，写与凭据读的内核强制，免账户免管理员 | ✅ 完成 |
| P1.5 | 模式接线：`--coding` → `AgentAccessPolicy.mode` → `resolveForMode` → 后端；`coding.permissions` 打印生效 profile | ✅ 完成 |
| P2 | `windows-appcontainer` 后端（browse）：容器 SID 授权工作区、无能力=无网络、profile 不可达 | ✅ 实现并验证；**默认 opt-in** |
| P3-1 | 生命周期：`stop/kill` 由完整性接管（Low 子进程终止不了 Medium 后端，已实测） | ✅ 完成 |
| P3-2 | Job Object：`KILL_ON_JOB_CLOSE`，backend 退出即整树终止 | ✅ 完成 |
| P3-3 | 技能脚本（`SkillScriptToolExecutor`）接入沙箱接缝 | ✅ 完成 |
| P3-4 | `b4.run`（`CliProcessManager`）接入沙箱接缝 + CLI 状态目录内迁 | ✅ 完成 |
| P3-5 | 容器读 CLI/runtime 实验 | ❌ 受阻，见 §7；browse 因此保持 opt-in |
| P3-6 | 文件写操作下沉沙箱子进程（`coding.write` 等） | ✅ 完成（含真实 Low-IL 与容器子进程验证） |

---

## 3. 代码清单

### 3.1 新增

| 文件 | 作用 |
|---|---|
| `browser4-coding/.../coding/IsolationProfile.kt` | `AgentMode`、`ReadScope`/`WriteScope`/`NetPolicy`/`ExecPolicy`、`IsolationProfile.forMode()`、`backendChain()`、`defaultCaches()`、`unrestricted()` |
| `browser4-coding/.../coding/SandboxStatus.kt` | "有没有 OS 边界在背后"的唯一事实源，供拒绝文案与提示词使用 |
| `browser4-coding/.../sandbox/WindowsLowIntegritySandbox.kt` | coding 后端：受限令牌 + Low 完整性；工作区/缓存/镜像打标；凭据 Medium+`NO_READ_UP`；三段探针 |
| `browser4-coding/.../sandbox/WindowsAppContainerSandbox.kt` | browse 后端：每用户容器 profile、容器 SID 授权工作区、`STARTUPINFOEX` 启动、无能力=无网络、三段探针 |
| `browser4-coding/.../sandbox/WindowsJob.kt` | 进程树生命周期（`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`） |
| `browser4-coding/.../sandbox/SandboxFileOps.kt` | 写侧接缝：`SandboxFileOps` 接口 + `SandboxedFileOps`（暂存载荷 + `powershell -File` 子进程，走 `prepare`/`ProcessLauncher`） |
| `browser4-agentic/src/test/.../cli/CliProcessManagerSandboxTest.kt` | `b4.run` 沙箱化的状态目录/工作目录断言 |
| `browser4-coding/src/test/.../sandbox/SandboxFileOpsTest.kt` | 接线、失败关闭、真实 Low-IL/生产接线端到端 |
| 四个 coding 测试类 + 一个 appcontainer 测试类 + 一个 job 测试类 | 见 §6 |

### 3.2 修改

| 文件 | 改动 |
|---|---|
| `sandbox/ExecutionSandbox.kt` | `ProcessSpec.sandboxLauncher`、`SandboxLauncher`、`isReady()`、`autoSelectable`、`enforcesNetwork`、`executesFileOps`、`resolveForMode()`、新后端注册 |
| `sandbox/WindowsNativeApi.kt` | Job Object、完整性标签（`AddMandatoryAce`/`SetNamedSecurityInfoW`）、受限令牌、AppContainer（`CreateAppContainerProfile`/`STARTUPINFOEX`/`CreateProcessW`）等绑定 |
| `sandbox/WindowsTokenLauncher.kt` | token 启动加入 Job；新增 `startInAppContainer()`（复用管道/环境/命令行机制） |
| `sandbox/SandboxDefaults.kt` | marker 升到 `v2:<id>`；老装一次性升级；升级以"后端就绪"为门；区分"操作者显式"与"发布默认" |
| `sandbox/WindowsSandboxBootstrap.kt` | `isReady()`：只查账户+凭据，不动 ACL |
| `coding/AgentAccessPolicy.kt` | `mode: AgentMode`（默认 BROWSE） |
| `coding/CodingComponents.kt` | `sandbox(policy)` 改为按模式解析；`isolationSummary(policy)` 供 `coding.permissions`；`fileOps(policy, root)` 与 `fs()` 注入写侧接缝 |
| `coding/CodingAgentShell.kt` | 拒绝文案从常量改为跟随真实执行层 |
| `coding/CodingAgentFileSystem.kt` | `PATH_DENIED` 提示同上；新增 `mutationOpsProvider`，`writeAtomic`/`delete`/`mkdir`/`copy`/`move`/`revert` 改走沙箱子进程（快照簿记仍进程内） |
| `sandbox/WindowsLowIntegritySandbox.kt` / `WindowsAppContainerSandbox.kt` | 声明 `executesFileOps = true` |
| `agentic/tools/AgentToolManager.kt` | 技能脚本与 `b4.run` 注入 sandbox/workspace 供应商 |
| `agentic/cli/CliProcessManager.kt` | 沙箱化启动 + `BROWSER4_CLI_STATE_DIR` + 无 payload 时关闭 stdin |
| `agentic/skills/tools/*` | 技能脚本走 `ProcessLauncher` + `SandboxEnvironment` |
| `agentic/tools/builtin/CodingToolExecutor.kt` | `coding.permissions` 增：生效 profile、网络是否强制、边界覆盖哪些通道 |
| `agentic/agents/CliAgentPrompt.kt` | 删掉"解释器绕不过去"的假承诺，按实际状态说明 |
| `agent-tools/.../StatefulAgentRunner.kt` | 把 `--coding` 作为权限输入写进任务策略 |
| `docs/config.md`、`docs/agent-permissions.md` | 默认值、后端清单、opt-in 开关、诚实边界 |

---

## 4. 后端能力矩阵

| 后端 | 读 | 写 | 网络 | 凭据读 | 需要账户/管理员 | 默认 |
|---|---|---|---|---|---|---|
| `windows-low-il`（coding） | 继承用户 | 工作区 + 缓存（Low 标签） | ❌ 不强制 | ❌ 被 `NO_READ_UP` 拒 | 都不需要 | coding 链首选 |
| `windows-appcontainer`（browse） | 仅工作区（profile 不可达） | 仅工作区 | ✅ **无能力=无出网** | ❌ profile 不可达 | 都不需要 | **opt-in**（`-Dbrowser4.agent.sandbox.browse=on`） |
| `windows-acl`（账户） | 账户可读的树 | 工作区+镜像 | ❌ 不强制 | 靠 ACL | 需要管理员离线建账户 | 安装默认（历史行为） |
| `off` | 当前用户 | 当前用户 | 当前用户 | 仅策略层 | — | 老装兜底 |

模式 → 后端链（`IsolationProfile.backendChain`）：browse = `appcontainer → windows-acl`；coding = `windows-low-il → windows-acl`；
操作者显式 `-Dbrowser4.agent.sandbox=<id>` 永远优先；链上"未就绪/未实现/不可自动选择"的后端被跳过。

---

## 5. 通道覆盖（哪些动作真的在内核边界内）

| 通道 | 执行身份 | 是否在内核边界内 |
|---|---|---|
| `coding.shell` / `coding.runCode` | 沙箱子进程 | ✅ |
| `coding.mvnBuild` / `verify` | build shell（同一接缝） | ✅ |
| 技能脚本（`skill.runScript`） | 沙箱子进程 | ✅（P3-3 接入） |
| `b4.run` → `browser4-cli` | 沙箱子进程（状态目录内迁） | ✅（P3-4 接入） |
| 进程树生命周期 | Job Object | ✅（P3-2 实现） |
| `coding.write/append/replace/editLines/delete/mkdir/copy/move/revert` | 沙箱子进程（每次操作一个） | ✅（P3-6 接入；`windows-low-il` / `windows-appcontainer`；`windows-acl` 仍为策略层） |
| `coding.read/glob/grep`、`coding.*` 的判定/快照 | 后端 JVM（Medium） | ❌ 仅策略层（**按设计**：coding 读=继承用户，browse 读=工作区） |
| git / worktree / merge、LSP、ripgrep | 后端 JVM | ❌ 产品流程本身 |
| 网络（coding） | — | ❌ `net=off` 未兑现（待代理/WFP） |

`coding.permissions` 会把这张表里"在/不在"边界内的通道直接打印给模型。

---

## 6. 测试

`browser4-coding` 全量 **889 例全绿**（其中 `sandbox` 包 101 例）：

| 类 | 例数 | 关键断言 |
|---|---|---|
| `WindowsLowIntegritySandboxTest` | 6 | 真实子进程区内写成功、区外写被拒、注入的凭据目录列不出、Medium 进程可读、子进程杀不掉 Medium 进程、探针失败硬失败 |
| `WindowsAppContainerSandboxTest` | 8 | 容器内命令可跑、写仅在授权树、`%SystemRoot%\Temp` 写被拒、`.ssh` 列不出、无能力=无网络、opt-in 语义、**容器内 `powershell.exe` 能起并执行文件写（P3-6）**、**容器够不到宿主机 loopback/出网（curl exit 6/7/28），工作区暂存的 exe 能跑** |
| `ProductCommandBoundaryTest` | 4 | `executesProductCommands` 按后端声明（容器 false，其余 true）；容器不会被交给它跑不了的 `b4.run` 启动；`--unrestricted` 不包装；不可用后端仍抛异常（决策不是回退） |
| `SandboxFileOpsTest` | 7 | 7 个变更方法各命中边界一次；拒绝→工具错误且不落盘；provider 失败→报错不透支；快照簿记留进程内；能力矩阵；真实 Low-IL 区内全通/区外拒绝；生产接线（`CodingComponents.fs` + 真实后端）端到端 |
| `WindowsJobTest` | 3 | 关 job 成员死亡、close 幂等、attach 已退出 pid 返回 false |
| `ExecutionSandboxesModeTest` | 10 | coding→low-il、browse 不落到 coding 后端、跳过未实现/不可自动选择、显式覆盖优先、`isolationSummary` 文案 |
| `IsolationProfileTest` | 8 | browse 最小面、coding 读放开写收紧、凭据仍拒、caches 不含 JDK/OS、`--add-dir` 只读 |
| `SandboxDefaultsTest` | 13 | marker v2、老装升级与就绪门控、`v2:off` 为操作者决定、显式值不落盘 |
| `SandboxStatusTest` | 3 | 有无 OS 边界时文案不同 |
| `ExecutionSandboxTest` / `WindowsSandboxTest` / `SandboxProbeTest` | 14 / 14 / 6 | 既有契约无回归 |
| `CodingAgentShell*` / `ReadablePaths` / `MirrorBoundary` | 20 / 10 / 13 / 11 | 字符串层与可读根无回归 |

`browser4-agentic`：定向运行 `*Permission*`/`*Coding*`/`*Skill*`/`*Cli*`/`*Prompt*` 共 **469 例全绿**，其中
`CliProcessManagerSandboxTest`（3 例）、`PermissionsConsistencyTest`、`CodingAccessEnforcementTest`、
`CliAgentPromptTest`（15 例，含 `osSandboxNote` 改为按模式解析后端后的文案断言）覆盖了本轮改动的三处文案与注入。

---

## 7. 关键结论与踩坑记录

1. **完整性级别只收紧写，容器收紧读+写+网。** coding 要"读放开、写收紧"→ Low IL 正好；browse 要"连读都不许出工作区"→ 只能 AppContainer。
2. **`LocalFree` 在 kernel32，不在 advapi32**（放错 DLL → `UnsatisfiedLinkError`）。
3. **两种引号规则不能叠加**：launcher 用 C 运行时规则给 argv 加引号，`cmd /c` 再解析一遍，`echo x> "C:\a b\f.txt"` 会报"语法不正确"。探针因此改成**工作区内的 PowerShell 脚本 + 把路径作为普通 argv 传**（已验证）。
4. **`HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)` = `0x800700B7`**，不要手算十进制（手算成 `-2147024777` 会把"配置已存在"变成硬失败）。
5. **容器 SID 不能授予任务工作区的共享祖先**（实测）：给 `~/.browser4` 加 AppContainer SID 的 ACE 之后，其下 Low-IL 工作区的子进程立刻 `Access is denied`；移除即恢复。规则：容器授权只落在工作区自身。
6. **祖先 traverse 授权是死路**：容器要读 `%APPDATA%\browser4` 需要 `C:\Users`、`C:\Users\<u>` 也给它 traverse，而这些目录归系统所有，普通用户没有 WRITE_DAC。
7. **`icacls /setintegritylevel` 需要 WRITE_OWNER，`icacls /grant` 需要 WRITE_DAC**：`%TEMP%` 这类从共享目录继承 Modify 的位置做不了，后端现在直接报错而不是含糊失败。
8. **写操作下沉的载荷不能上命令行**（P3-6）：命令行有 ~32KB 上限、还要处理编码与引号，所以内容先由后端写成工作区内暂存文件（`.b4scratch/fs-bridge/payload-*.bin`，继承 Low 标签 → 子进程读得到），子进程只做 `ReplaceFile`/rename。命令行上只有路径。
9. **`-switch` 不是 `-Recursive true`**：PowerShell 的开关参数写成 `[switch]` 时 `-Recursive true` 会把 `true` 当成位置参数；用 `[string]` + `-eq 'true'` 才稳。
10. **一键一进程的代价是实测过的**：一次文件变更 ≈ 0.3–0.5s（`powershell -File` 启动），8 次操作 ~2.5s。常驻助手能省掉它，但要引入单飞锁/挂起/重启/孤儿清理——正是本轮要消灭的那类静默失败模式，所以先选简单形态（设计 §16.2）。
11. **`browser4-coding` 的沙箱测试要自己收拾现场**：`CodingComponents` 的生产后端会顺手把 `~/.m2`、`~/.cache` 的标签降到 Low 并写 marker。端到端测试因此在前后比对 marker，测试结束把新建的那几条**还原成 Medium 并删掉 marker**（只删 marker 不还原标签，会让下一次真实运行跳过打标、然后在写的时候失败）。

---

## 8. 机器状态与操作

本机（`D:\workspace\Browser4\Browser4-4.16`）当前状态：

- `<data>/.browser4/sandbox-default` 仍是**旧的裸 `off`**；后端未重启，所以跑着的还是旧构建；
- Windows 沙箱账户 `browser4-agent` **未创建**，`%LOCALAPPDATA%\browser4\sandbox\agent.cred` **不存在** → `windows-acl` 不就绪；
- `~/.browser4` 上实验时加的容器 ACE **已移除**，ACL 恢复原样；
- `~/.m2`/`~/.cache` 的完整性标签在本轮测试后**已还原为 Medium**，`~/.browser4/sandbox/low-il` 下无遗留 marker（下一次 `--coding` 任务会重新打标）。

启用 / 回滚：

```powershell
# coding 模式走 Low IL（本机自动：coding 链首选且就绪）
BROWSER4_SERVER_OPTS="-Dbrowser4.agent.sandbox=windows-low-il" browser4-cli open

# browse 允许容器（默认 opt-in，因为容器读不到 profile 里的 CLI）
BROWSER4_SERVER_OPTS="-Dbrowser4.agent.sandbox.browse=on" browser4-cli open

# 直接指定 / 回滚
BROWSER4_SERVER_OPTS="-Dbrowser4.agent.sandbox=windows-appcontainer" browser4-cli open
BROWSER4_SERVER_OPTS="-Dbrowser4.agent.sandbox=off"                browser4-cli open
```

注意：改动只在**重启后**生效；`b4.run`/技能脚本/`coding.shell` 都走同一沙箱；文件写操作在
`windows-low-il`/`windows-appcontainer` 下同样由沙箱子进程执行（每次变更一次 `powershell -File`）。

---

## 9. 未收口清单（按危害）

| # | 项 | 方案 |
|---|---|---|
| ~~1~~ | ~~`coding.write/read/glob`（后端自身）~~ | ✅ **写操作已下沉**（P3-6，见设计 §16）；`coding.read/glob/grep` 按设计留在后端 |
| 2 | 容器内跑 `browser4-cli` | **实测已排除三种"轻方案"**（见 §9.1）；剩下的是产品决定，不是实现细节 |
| 3 | coding 的网络 `net=off` | 与 #2 同一套代理，或 WFP 规则（需管理员，本机无法验证） |
| 4 | git/worktree/merge、LSP、ripgrep | 产品流程本身；linked-worktree 的 git 元数据在工作区外 |
| 5 | browse 默认启用容器 | 依赖 #2 **且**容器内要能跑脚本解释器（见 §9.1） |
| 6 | `--unrestricted` 下的护栏 | 凭据与生命周期两条仍在；其余按设计放开 |
| 7 | `windows-acl` 也支持文件下沉 | 它的账户 provisioning 还没按这条通道验证过；先保持策略层并在 `coding.permissions` 标注 |
| 8 | 每次变更一次 `powershell` 启动（0.3–0.5s） | 若实测主导任务时长，换成常驻助手/批量协议；`SandboxFileOps` 接口不变 |

### 9.1 #2/#5 的新实测（2026-11-17 21:50，`realContainerNetworkAndStagedBinary`）

探针（`WindowsAppContainerSandboxTest`，8 例全绿）测到的事实：

| 探测 | 结果 | 含义 |
|---|---|---|
| JVM 在 `127.0.0.1:<port>` 起 HTTP 服务，宿主 `curl` 取到 `ok` | ✅ | 探针本身可信（否则"拒绝"可能是探针坏了） |
| 容器内 `curl.exe --version` | ✅ exit 0 | 探针工具真能跑（`C:\Windows\System32` 对容器可读） |
| 容器内 `curl` 访问宿主机 loopback | ❌ exit 7/28（连不上/超时） | **容器里的 `browser4-cli` 够不到后端**——就算把 CLI 复制进工作区也一样 |
| 容器内 `curl` 访问 `example.com` | ❌ exit 6（DNS 都不通） | "无能力=无出网"成立 |
| 工作区内暂存的 `staged-curl.exe` | ✅ exit 0 | 暂存的可执行文件**能跑**（"复制进工作区"只解决可执行性，不解决连通性） |
| 容器内 `if exist D:\` | ❌ 看不到 | **整个 D: 卷对容器不可达**（卷根没有容器/`ALL APPLICATION PACKAGES` 的 traverse） |
| 容器内 `python` / `node` / `java` | ❌ 不在 PATH 上可执行 | 本机 Python/JDK 在 `D:` 上 → 容器里等于没有解释器；`git`（`C:\Program Files`）与 System32 工具可用 |

结论：`b4.run` 的三条"轻方案"都不成立。CLI 复制进工作区只换来"能执行但连不上后端"；
loopback 代理同样要先让容器连得上 loopback（需要 `privateNetworkClientServer` 能力，而这会把
"主机本地任何服务"一起放开，等于削弱围栏，且 loopback 豁免通常还要管理员）。
browse 默认化还有第二个独立障碍：**容器里没有脚本解释器/构建工具链**（真实安装多在 profile 或非系统卷）。

**已拍板（2026-11-17 22:10）：`b4.run` 在 browse 模式下算产品通道。** 它是 `browser4-cli`——
装在 profile、走 `127.0.0.1` 访问后端，与 git/worktree/LSP 同类。实现是一个声明式能力而不是静默回退：

- `ExecutionSandbox.executesProductCommands`：能否在本边界内执行*产品子进程*。容器 `false`，其余 `true`；
- `CodingComponents.b4RunSandbox(policy)` → `productCommandSandbox(...)`：`--unrestricted` 或后端声明不能托管
  CLI → `null`（保留后端进程启动），否则照旧入界；
- `coding.permissions` 的 covered / NOT covered 由这些能力**生成**，所以容器模式下会写出
  `b4.run (this backend cannot host browser4-cli)`；
- 不给容器 `privateNetworkClientServer`、不做 loopback 豁免、不复制 CLI（前两个削弱围栏且要管理员，
  后一个只解决可执行性）。

测试：`ProductCommandBoundaryTest`（4 例：能力声明、容器不被交给它跑不了的启动、`--unrestricted` 不包装、
不可用后端仍然抛异常）+ `realContainerNetworkAndStagedBinary`（8 例里的网络/暂存二进制部分）。
`browser4-agentic` 定向 469 例仍全绿。

#5（browse 默认启用容器）**仍未解锁**，且与 `b4.run` 无关：容器里没有解释器（实测 `python`/`node`/`java`
都不可执行，本机 Python/JDK 在 `D:` 卷，容器连 `D:\` 都看不到）。那是部署层决定，不是沙箱代码问题。

所以 #2 不再是"实现一个代理"，而是一个需要拍板的产品决定：
`b4.run` 在 browse 模式下算"产品通道"（在后端执行、`coding.permissions` 明说它不在边界内，与 git/LSP 同类），
还是必须入界（那就要能力+豁免，且要管理员）。


## 10. 相关文档

- [`docs/agent-permissions-redesign.md`](../../docs/agent-permissions-redesign.md) — 设计正文（矩阵、OS 映射、分期、§14 容器实验结论、§16 文件写下沉）
- [`20261117-agent-run-permissions-report.md`](20261117-agent-run-permissions-report.md) — **as-built 总览**：模式 × 四轴 × 五层决策 × 四后端、通道覆盖矩阵、任务生命周期、已知缺口
- [`docs/agent-permissions.md`](../../docs/agent-permissions.md) — 面向使用者的权限模型说明
- [`docs/config.md`](../../docs/config.md) — `browser4.agent.sandbox*` 开关、默认值、失败语义
- [`docs-dev/copilot/20261116-agent-permission-bypass-review.md`](20261116-agent-permission-bypass-review.md) — 起点：run `966a5f2e` 的 11 次拒绝复盘
- [`docs-dev/copilot/20261117-sandbox-runtime-adoption-evaluation.md`](20261117-sandbox-runtime-adoption-evaluation.md) — 引入 Anthropic sandbox-runtime 的可行性评估（Linux/macOS 后端、`net=off`、Windows 的 env/JVM 代理/Schannel 三处硬约束、分期建议）
