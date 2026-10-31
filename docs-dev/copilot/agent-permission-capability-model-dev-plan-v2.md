# Agent 权限能力模型 — 开发计划 v2（2026-10-31）

> 依据：[设计稿 v0.1](agent-permission-capability-model-design.md) 与
> [执行计划 v1（A–E 阶段定义与实现记录）](agent-permission-capability-model-execution-plan.md)。
> 代码基线：`4.15.x`；`browser4-coding` 530 测试、`browser4-agentic` 1695 测试（唯一失败为既有
> `CliAgentPromptTest.hygieneFollowsMode`，与权限改动无关）。
> 本文档只覆盖**剩余工作**的重新编排；A/B/C1–C2 的事实与实现记录仍以 v1 为准。

## 1. 总体目标与完成定义

目标：把 `coding.*` / `skill.*` 的执行模型从"字符串护栏"升级为**能力声明 → 单点判定 → OS 级强制 →
可审批的升级路径**，且默认配置即安全、失败即停。

完成定义（DoD，逐条可验证）：

1. 所有 coding/skill 入口声明 `effects`，缺声明在注册期失败（已完成）。
2. 路径与命令判定只在 `Authorizer`；字符串层仅用于快速失败与提示（已完成）。
3. exec 在沙箱内运行：workspace 可写、只读根可读、其余拒绝；越界写入由 OS 拒绝。
4. 沙箱不可用 = 任务失败，不回退到无沙箱直跑；启动有写探针自检。
5. 拒绝与事实都能查询：结构化 `CODE_*`、`coding.permissions`、任务报告一致。
6. 默认开启后，在没有 JVM flag 的分支上跑完整回归；`sandbox=off` 时行为与当前完全一致。
7. 一周真实任务观测产出的 scope 清单被固化进默认 scope 与测试。

## 2. 已完成基线（不再重复实施）

| 层 | 结果 | 关键产物 |
|---|---|---|
| M0 判定收敛 | A1–A7 全绿 | `Capability.kt`、`Authorizer.kt`、`MirrorRoots.kt`、`coding.explain`、`PermissionDecisionBaselineTest`（46 条金标准） |
| M1 观测 | B1–B5 全绿 | `ToolSpec.effects`、`CodingToolEffectsCoverageTest`、`PermissionAudit.writeEvent` 与 4 个事实码、`coding.permissions` 观测区块、FS 越界写入与 shell advisory 记录 |
| C1 执行路径统一 | 全绿 | `sandbox/ExecutionSandbox.kt`（`ProcessSpec` / `ExecutionSandbox` / `ProcessLauncher`），shell 与 runCode 共用 |
| C2 环境白名单 | 全绿 | `SandboxEnvironment` 白名单 + 长期密钥剥离 + 显式透传审计（`coding.env:ENV_PASSTHROUGH`） |

已解决的历史决策：设计 §6 #1（Windows v1 = 低权用户 + ACL）、#5（镜像模块 scope 由
`MirrorRoots` 自动派生）。

### 2.1 browse / coding 权限与工具矩阵（2026-10-31 定稿）

结论先行：两模式在**判定层**完全相同（无人值守默认 `full`）；差异只在**初始工具集**。模式不是权限维度。

| 维度 | browse（`agent run` 默认） | coding（`--coding`） |
|---|---|---|
| 初始工具集 | `b4` + `system` + `memory` 全域；`coding` 15 个方法（`shell` + 常用文件工具） | 同三域全域；`coding` 24 个方法（`shell`/`shellOutput`/`shellStatus` + 全量文件工具 + `validate`/`verify`/`mvnBuild`/`diagnostics`/`devTask`） |
| `coding.shell` | 默认可见且可执行 | 默认可见且可执行 |
| `coding.runCode` | 不可见（long tail），`exposeTools` 后可见 | 同 |
| 构建类（`mvnBuild`/`verify`/`validate`/`diagnostics`） | 不可见，`exposeTools` 后可见 | 默认可见 |
| `b4.*`（浏览/爬取/查询） | 默认可见；不受 coding preset 判定 | 默认可见；同 |
| `skill.*` | 不在初始集；`install`/`uninstall`/`reload` 永久不在 CLI 引擎内 | 同 |
| workspace 读写（`FS_READ`/`FS_WRITE`） | 允许（`full`） | 允许（`full`） |
| `FS_EXEC` / `PROC_SPAWN` | 允许（`full`） | 允许（`full`） |
| `NET_CONNECT`（coding 子进程） | 允许，出站不限域名，逐条审计（`allowNetwork=true`，P4-1） | 同 |
| 浏览器自身联网 | 不受 coding 权限约束 | 同 |
| workspace 外路径 | 拒绝（`allowExternalAccess=false`） | 同 |
| toolchain 根（`~/.m2`/`~/.gradle`/tmpdir） | 当前 B4 例外放行；P4-2 改为显式根 | 同 |
| 受保护路径 / `denyTools` | 拒绝 | 同 |
| secret（`SECRET_READ`）与子进程环境 | 拒绝；环境按白名单剥离（C2） | 同 |
| 沙箱覆盖 | `shell`/`runCode` 进程树；`b4.run` 豁免（决策 #2） | 同 |
| 无人值守默认 | `full`（P4-1） | `full`（P4-1） |
| 有人值守（`-i` + TTY） | 按 `--access`，拒绝走 `/approve` | 同 |
| 审批通道 | 策略文件 / REST / CLI 斜杠命令 | 同 |

三条结论：

1. **模式只影响"默认看得见多少"，不影响"能不能"。** 判定层一致；`exposeTools` 之后 browse 同样能拿到
   `runCode` 与构建工具，因此"browse 比 coding 更受限"不成立。
2. **真正的边界是四条**：workspace 边界（`allowExternalAccess=false`）、护栏（`denyTools` / `protected*` /
   `sensitive*`）、`DEV_COMMANDS` 命令白名单、沙箱 ACL（P1/P2）。四条与模式无关。
3. **浏览模式本来就带 `shell`**：`CLI_BROWSING_CODING_METHODS` 含 `shell`（2026-10-28 真实运行验证后加回），
   所以"浏览任务需要跑脚本"在当前代码里已是默认能力，无需额外暴露；需要收窄时应从策略侧收紧，而不是删工具。

## 3. 剩余工作分解

### P0 — 观测收口（可立即开始，与 P1 并行）

| ID | 工作项 | 改动/动作 | 验收 |
|---|---|---|---|
| P0-1 | 一周真实任务观测（**未开始**，等待 ≥1 周真实任务数据） | 用现有 `coding.permissions` 每日快照；汇总 `OUT_OF_SCOPE_WRITE` / `NETWORK_TARGET` / `SCOPE_UNCERTAIN` / `ENV_PASSTHROUGH` 与 `PermissionAudit` 计数 | 计数一致、无新增高频日志；产出「实际需要的 scope 清单」 |
| P0-2 | scope 清单固化（**骨架已实现**） | 新增 `ScopeManifest.kt`（`ScopeEntry`/`ScopeKind`/`ScopeNarrowing`）；`CodingComponents.scopeManifest(root)` 把 workspace / 镜像 / toolchain 根变成具名条目，`buildBoundaryAuthorizer` 改为从清单派生两个 grant 的根；`toolchainEntries()` 成为 `toolchainRoots()` 的唯一来源 | 清单条目可被 `CodingScopeManifestTest` / 基线测试引用 |
| P0-3 | 审计一致性检查（**已实现**） | 新增 `PermissionsConsistencyTest`（`browser4-agentic/src/test/.../builtin/`）：经真实生产者各产生一条事实（FS 越界写 / shell 越界拼写 / shell 网络命令 / 显式密钥透传），再调用 `coding.permissions`，逐项比对报告数字与 `PermissionAudit.snapshot()` | 差异为 0 时通过，漂移即失败 |
| P0-4 | 决策归档（**已关闭**，含 #2/#3/#4） | 记录见 v1 §6.1–6.3；P4 按已定形态实施 | 三份记录可追溯 |

**P0-3 实现记录**（2026-10-31）：

- 报告侧不改造：`describePermissions` 已直接读 `PermissionAudit.snapshot()`，本条只需在测试层钉住"报告 = 单一事实源"。
- 测试用四个真实生产者：`CodingAgentFileSystem.writeFile`（`allowExternalAccess=true`，记 `coding.fs:OUT_OF_SCOPE_WRITE`）、
  `CodingAgentShell.execute` 的外部路径拼写（记 `coding.shell:SCOPE_UNCERTAIN`）、`curl --version`（记
  `coding.shell:NETWORK_TARGET`）、`SandboxEnvironment.build` 显式传 `B4_TEST_API_KEY`（记
  `coding.env:ENV_PASSTHROUGH`）。
- 两个易错事实：①FS 的 canonical scope 不含 toolchain 根，临时目录内文件即算越界；②shell 的 lexical scope
  **含** toolchain 根（`java.io.tmpdir`），所以 shell 的"工作区外"必须用文件系统根，与 A7 基线同一口径。
- 报告用正则解析四个计数器并断言标签齐全（标签消失即失败，不默认成 0），再加上"四类计数 ≥ 1"的非空转断言。
- 验证：`browser4-agentic` `PermissionsConsistencyTest` 1 用例通过，`BUILD SUCCESS`。

**P0-2 实现记录**（2026-10-31，骨架）：

- 新增 `ScopeManifest.kt`：[ScopeEntry] 是具名根（`id`/`path`/`kind`/`effects`/`grants`），`ScopeKind` 分
  `WORKSPACE`/`MIRROR`/`TOOLCHAIN`；`ScopeNarrowing.suggestions(...)` 只对**有观测数据**且连续未使用
  `>= DEFAULT_UNUSED_TASKS`（7）的非 workspace 条目给出建议——没有数据时返回空，不把"未观测"当"未使用"。
- `CodingComponents.scopeManifest(root)`：workspace 用 real root；镜像条目 id 为 `mirror:<real path>`，
  与 workspace 同持两个 grant；toolchain 条目 id 为 `toolchain:jdk|maven|gradle|cache|tmp|os-root`，
  仅持 `CAP_SHELL_BOUNDARY`。
- `buildBoundaryAuthorizer` 不再原地拼根：`rootsOf(grant)` 从清单过滤，manifest 与判定不可能漂移。
- `toolchainRoots()` 改为 `toolchainEntries().map { it.path }`，清单是工具链根的唯一来源。
- 行为等价：`PermissionDecisionBaselineTest` 三条金标准全绿；`browser4-coding` **533** 测试全绿
  （530 + 新增 3），`failures=0 / errors=0`。
- 待接：P0-1 产出的实际使用计数按 `ScopeEntry.id` 回填，`DECLARED` 显式根替换派生工具链根；
  镜像条目在测试里暂未覆盖（建 junction 需额外权限，留到 A7 基线与真实工作区数据）。

**P0 收尾小结**（2026-10-31）：

| 项 | 状态 | 关闭条件 / 阻塞 |
|---|---|---|
| P0-1 | 未开始 | 需 ≥1 周真实任务数据；产出「实际需要的 scope 清单」 |
| P0-2 | 骨架已关，固化未关 | 清单机制可被测试引用；条目内容待 P0-1 数据回填，镜像条目待 junction 环境 |
| P0-3 | **已关闭** | `PermissionsConsistencyTest` + 计划文档记录落地 |
| P0-4 | **已关闭** | 决策 #2/#3/#4 记录见 v1 §6.1–6.3，可追溯 |

现在可以关闭 P0-3、P0-4；P0-2 只关闭"机制"，不关闭"内容"——把派生工具链根换成观测得出的显式根，
仍是 P0-2 的验收项。P0 阶段的**整体关闭**条件因此是：P0-1 产出一周观测清单 **且** P0-2 把清单转成显式
条目（默认配置建议同批落地），两者缺一不可。

**观测数据缺口（P0-1 的前置，必须先定）**：`PermissionAudit` 的键是 `tool:code`
（如 `coding.shell:SCOPE_UNCERTAIN`），**不含路径或 `ScopeEntry.id`**；`coding.permissions` 也只打印这四个
聚合计数。因此"哪些 scope 条目被真实用过"无法从现有输出直接得出，只有两条路：

1. 离线聚合：`SCOPE_UNCERTAIN` / `OUT_OF_SCOPE_WRITE` 的 detail 已带解析后的路径，日快照按路径反查清单条目。
   零代码改动，但同一条目下的多路径会被压成一个计数；
2. 增加条目维度：`writeEvent` 多带一个 `ScopeEntry.id`，由 `coding.permissions` 汇总。

倾向 1 起步（P0-1 本就只要求"快照 + 汇总"）；若一周数据不足以判断收窄，再走 2。

### P1 — Windows 强制层（C3）

| ID | 工作项 | 改动点 | 验收 |
|---|---|---|---|
| P1-1 | 沙箱账户与凭据供给（**已实现**） | 新增 `sandbox/WindowsSandboxBootstrap`：低权本地用户、`icacls` 授予 workspace `rw`、其余只读；账户凭据从 Windows Credential Manager / DPAPI 读取 | 账户缺失、授权失败均抛 `SandboxUnavailableException` |
| P1-2 | `CreateProcessWithToken` 启动（**已实现**） | 通过 JNA 走 `LogonUserW` + `CreateProcessAsUserW`（或 `CreateProcessWithTokenW`）；`ProcessLauncher` 增加 token 分支，仍吃同一个 `ProcessSpec` | 子进程覆盖：`cmd /c`、`pwsh -Command`、脚本解释器 |
| P1-3 | ACL 落盘（**已实现**） | workspace `rw`；只读根 `ro`；其余 deny。toolchain 根（`~/.m2`、`~/.gradle`、tmpdir）按 P0 清单显式列根 | 沙箱外写入被 OS 拒绝（`CodingAgentShellWindowsBuiltinTest`） |
| P1-4 | Windows 专项测试（**已实现**） | 扩展 `CodingAgentShellWindowsBuiltinTest`：ACL 拒绝、`cmd /c`、`pwsh -Command`、runCode 子进程 | 全部通过；`sandbox=off` 时同套测试仍绿 |

**前置**：决策 #2 已关闭（2026-10-31，记录见 v1 §6.1）：后端驱动沙箱外 Chrome，`b4.run` 不纳入沙箱。
因此 P1-2 不增加回环通道；`b4.run` 作为唯一显式豁免需在文档与审计中标注。

**P1-1 实现记录**（2026-10-31）：

- 新增 `sandbox/WindowsSandboxBootstrap.kt`（同文件含 `SandboxCommandRunner` / `SystemSandboxCommandRunner`、
  `SandboxCredentialSource` / `WindowsCredentialStore`、`SandboxAccount`）。`provision(workspace)` 按序执行：
  平台检查 → workspace 必须是已存在目录 → `net user <account>` → 读取凭据 → `icacls <ws> /grant <account>:(OI)(CI)M`。
  任一环失败抛 `SandboxUnavailableException(sandboxId = "windows-acl")`，异常文案带可直接执行的开户/注册命令；
  不创建账户（验收要求"账户缺失即失败"），也不回退到无沙箱启动。
- 凭据读取顺序：Credential Manager 目标 `browser4:<account>`（`CredReadW` + `CredFree`，经 `powershell.exe`
  内联 `Add-Type`）→ DPAPI 文件 `%LOCALAPPDATA%\browser4\sandbox\agent.cred`（`ProtectedData.Unprotect`，
  `CurrentUser`）。密码以 UTF-16LE 的 base64 经管道返回，避免 Windows PowerShell 5 与 7 的 console 编码差异。
  两处都读不到时返回 `null`，由 `provision` 转为硬失败，绝不当作"空密码"。
- 账户名通过 `-Dbrowser4.agent.sandbox.account` 覆盖，默认 `browser4-agent`。"其余只读"由低权本地账户的默认
  NTFS 权限提供（只读 + 执行，只写自己的 profile）；显式 deny 矩阵属于 P1-3，不在本步展开。
- 外部命令可注入（`SandboxCommandRunner`），测试用假 `net`/`icacls` 模拟账户缺失与授权失败，无需真实账户、
  管理员权限或实际建户；生产 runner 复用 C2 的 `SandboxEnvironment` 白名单，子命令拿不到后端长期密钥。
- `SandboxAccount` 以 `CharArray` 持有密码，`password()` 返回副本，`toString()` 固定 `<redacted>`，避免日志与
  异常消息泄密。
- 测试：`WindowsSandboxTest` 7 用例（账户缺失、icacls 失败、凭据缺失、成功路径、workspace 缺失、
  base64/UTF-16LE 解码、双源缺失返回 null）；三个真实 provisioning 用例在非 Windows 平台 `assumeTrue` 跳过。
  验证：`browser4-coding` **540** 测试全绿（533 + 7），`failures=0 / errors=0 / skipped=0`。
- 待接：P1-2 消费 `provision()` 返回的 `SandboxAccount` 走 `LogonUserW` + `CreateProcessWithTokenW`；
  P1-3 补齐只读根/deny 矩阵；P3-2 的写探针复用同一 workspace 授权结果。

**P1-2 实现记录**（2026-10-31）：

- 新增 `sandbox/WindowsNativeApi.kt`（`internal object Win32`：advapi32/kernel32/userenv 声明、
  `STARTUPINFO`/`PROCESS_INFORMATION`/`SECURITY_ATTRIBUTES`/`LUID`/`TOKEN_PRIVILEGES` 结构体），
  `browser4-coding/pom.xml` 增加唯一新依赖 `net.java.dev.jna:jna:5.17.0`；库与结构体全部懒加载，
  非 Windows 平台不加载 native 库。
- 新增 `sandbox/WindowsTokenLauncher.kt`：`LogonUserW`（`LOGON32_LOGON_INTERACTIVE`）→ 尽力启用
  `SeAssignPrimaryTokenPrivilege` / `SeIncreaseQuotaPrivilege` → `CreateProcessAsUserW`
  （`bInheritHandles=true`、`CREATE_UNICODE_ENVIRONMENT|CREATE_NO_WINDOW`）。密码 `CharArray` 只在
  `LogonUserW` 调用期间存在，`finally` 清零；logon token 在进程创建后立即关闭。
- 未采用 `CreateProcessWithLogonW` / `CreateProcessWithTokenW`：二者都不继承调用方句柄，无法用匿名管道
  重定向 `stdout`/`stderr`（`ProcessBuilder` 的 `redirectErrorStream` 语义会丢失）。因此选择
  `CreateProcessAsUserW`；权限不足（错误 **1314**）不是回退，而是 `SandboxUnavailableException`，文案指出
  需要授予的两个特权；错误 2（`ERROR_FILE_NOT_FOUND`）由下面的 PATH 解析消除。
- stdio 用 `CreatePipe` + 自定义 `java.lang.Process`（`inputStream`/`errorStream`/`outputStream`/
  `waitFor`/`exitValue`/`isAlive`/`destroyForcibly`），父端句柄 `HANDLE_FLAG_INHERIT` 清零，子端在创建后
  关闭；`CodingAgentShell` 与 `CodeRunner` 的调用点零改动，`ProcessSpec` 只多一个可空的 `sandboxAccount`。
- 环境策略：`CreateEnvironmentBlock(token)` 提供沙箱身份变量（`USERPROFILE`/`TEMP`/`APPDATA`/`SYSTEMROOT`…），
  声明的 toolchain 变量（`PATH`/`JAVA_HOME`/`GRADLE_HOME`…）覆盖其余；token 拥有变量不允许被声明覆盖，
  否则低权子进程的 `TEMP` 会指向后端用户目录而写失败。块以 UTF-16LE 显式写入（双 NUL 结尾，精确分配，
  避免读到未初始化内存），读取用 `getWideString`（按字节解码会在每个 `NAME=value` 的首个 NUL 处截断）。
- 新增 `resolveProgram`：`CreateProcessAsUserW` 不按 `PATH` 搜索，`cmd.exe` 直接抛 `ERROR_FILE_NOT_FOUND`
  （2026-10-31 实测）。解析按**沙箱环境**的 `PATH`/`PATHEXT` 展开，显式路径原样返回；shell 仍只构造
  `cmd.exe /c <text>`，调用点不变。
- 接线：`ProcessSpec.sandboxAccount`；新增 `WindowsAclSandbox`（`id="windows-acl"`，`provision` 可注入，
  按 workspace 缓存账户，避免每条命令重复 `net user`/`icacls`）；`ExecutionSandboxes.of("windows-acl")`
  返回它（未知 id 仍抛「not available in this build」）；`ProcessLauncher.start` 在有 `sandboxAccount`
  时走 token 分支，否则走原有 `ProcessBuilder`，`off` 行为完全不变。
- 测试：`WindowsTokenLaunchTest` 9 用例 —— 引号规则、环境块 UTF-16LE/双 NUL/跳过含 `=` 的名称、合并策略
  （token 身份变量不被覆盖、大小写变体不重复）、`PATH` 解析、非 Windows 硬失败、缺失账户 `LogonUserW`
  硬失败、`cmd.exe`（stdout 与 `exit 7` 退出码、`set` 中的 `USERPROFILE`/`SYSTEMROOT`）、`stderr` 合并、
  `powershell.exe -Command`。真实启动复用当前进程 token，不依赖 provisioning；后端缺少 token 特权时
  以 `assumeTrue` 跳过（不把文档化的硬失败变成红灯）。`ExecutionSandboxTest` 更新：`windows-acl` 可解析、
  每 workspace 只 provision 一次、provision 失败不回退。
- 验证：`browser4-coding` **551** 测试全绿（540 + 11），`failures=0 / errors=0 / skipped=1`（跳过项为
  非 Windows 契约用例）。
- 已知边界：`.bat`/`.cmd` 不能被 `CreateProcessAsUserW` 直接启动（当前 shell 一律经 `cmd.exe /c`，尚未触发）；
  `allowNetwork` 在 P1-2 未使用（P4-1 审计）；`PermissionAudit.CODE_SANDBOX_DENIED` 接线属 P3-3。
- 待接：P1-3（只读根/deny 矩阵）、P1-4（Windows 专项端到端测试）、P3-1（默认开启与 `current()` 解析）。

**P1-3 实现记录**（2026-10-31）：

- `WindowsSandboxBootstrap.provision` 增加三个可选入参并落盘完整 ACL 矩阵，顺序固定为
  workspace `M` → 可写根 `M` → 只读根 `RX` → deny `F`：`workspace` 恒为 `(OI)(CI)M`（既有行为不变）；
  可写根 `icacls <root> /grant <account>:(OI)(CI)M`；只读根 `icacls <root> /grant <account>:(OI)(CI)RX`；
  deny 根 `icacls <root> /deny <account>:(F)`。每条 ACL 失败都是 `SandboxUnavailableException`，没有"跳过这一条"。
- deny **刻意不加 `(OI)(CI)`**：显式 ACE 优先于继承 ACE，而继承 ACE 中 deny 先于 allow。若 deny 递归继承，
  位于被 deny 目录之下的 workspace（JUnit `@TempDir`、`%TEMP%` 都可能落在操作者 profile 下）会因继承 deny
  压过自身继承 allow 而变成只读；文件夹对象级 deny 不会流入子目录，子目录的显式授权因此仍然生效。
  子进程能穿过被 deny 的父目录，靠的是 `SeChangeNotifyPrivilege`（默认授予 `Everyone`）。
- 根来源全部复用 P0 清单，不在沙箱层硬编码：可写根 = `MirrorRoots.derive(workspace)`（junction/symlink 的
  真实目标，否则经镜像模块的构建写入会被 ACL 拦下）；只读根 = `CodingComponents.sandboxReadOnlyRoots()`
  （`toolchainEntries()` 去掉 `toolchain:os-root` 与 `toolchain:tmp`：系统目录本就可读，后端临时目录是私有数据，
  子进程的临时目录来自 logon token 环境）；deny 根 = `defaultDenyRoots()`（操作者 `user.home`）。
- 不存在的根在 `existingDirectories` 处过滤（对缺失路径执行 `icacls` 会让整个 provisioning 失败），
  并按大小写不敏感去重；与 workspace 同路径的根不会二次授权，与可写/只读根重叠的 deny 根会被跳过，
  避免自相矛盾的 ACL。
- 测试：`WindowsSandboxTest` 增至 10 用例 —— 新增「ACL 矩阵顺序」（断言精确调用序列：`net user` →
  workspace `M` → module `M` → `m2 RX` → home `deny (F)`，不存在的根被跳过，workspace 不重复授权/不被 deny）、
  「只读根授权失败是硬失败」（文案含 `read-only root` 与路径）、「deny 失败是硬失败」（文案含 `deny` 与路径）。
  `CodingAgentShellWindowsBuiltinTest` 新增验收用例 `sandboxDeniesOutsideWrites`：真实 provisioning 下
  workspace 内写入成功、`user.home` 下探针文件被 OS 拒绝（非 0 退出码 + 文件不存在）。该用例需外部开户，
  以 `-Dbrowser4.agent.sandbox.test=true` 显式开启，否则 `assumeTrue` 跳过；运行时临时把
  `browser4.agent.sandbox` 设为 `windows-acl`，并打开 `allowExternalAccess`、关闭词法边界
  （`workspaceBoundaryEnabled=false`），确保被拒绝的是 OS 而非守卫。
- 验证：`browser4-coding` **555** 测试全绿（551 + 4），`failures=0 / errors=0 / skipped=2`
  （跳过项：非 Windows 的 token 契约用例、需真实账户的 opt-in 集成用例）。
- 已知边界：本机未开户（`net user browser4-agent` 不存在），opt-in 集成用例在此环境只验证到"跳过"；
  真实拒绝路径需在已 provisioning 的机器上执行一次。`ExecutionSandboxes.current()` 每次调用新建实例，
  因此账户缓存只在一次 `prepare` 内生效——真实使用中每条命令会重复 provisioning（P3-1 统一解析时可改为
  按 id 缓存实例）。
- 待接：P1-4（Windows 专项端到端测试：`cmd /c`、`pwsh -Command`、runCode 子进程 + `sandbox=off` 回归）、
  P3-1（默认开启与 `current()` 解析）。

**P1-4 实现记录**（2026-10-31）：

- `CodingAgentShellWindowsBuiltinTest` 扩为 Windows 执行端到端套件（9 用例）：
  `cmd /c echo` 经 `CodingAgentShell.executeRaw` 走完整链路（校验 → `cmd.exe /c` → `ProcessLauncher` → 沙箱 seam）；
  `powershell.exe -NoProfile -NonInteractive -Command "echo …"` 同样经 shell 执行；`runCode` 用 `CodeRunner`
  启动解释器子进程（优先 `python`，退到 `node`，两者都缺时 `assumeTrue` 跳过）。三例都断言退出码 0 与 stdout 标记。
- 套件对沙箱后端无感：默认 `off` 时全部执行且全绿；把 `browser4.agent.sandbox` 设为 `windows-acl` 且账户未
  供给时，`runInShell`/`assumeSandboxReady` 识别 `Windows sandbox unavailable` 并以 `assumeTrue` 跳过，
  不把文档化的硬失败变成红灯。P1-3 的 `sandboxDeniesOutsideWrites`（ACL 拒绝）继续以
  `-Dbrowser4.agent.sandbox.test=true` 作唯一 opt-in 用例。
- PowerShell 用例刻意用 `echo` 而非 `Write-Output`：内联 `-Command` 脚本会被递归校验，cmdlet 名不在
  允许清单内（`Get-Content`/`Write-Output` 都会被拒绝，见 2026-10-31 的 `coding.shell` 记录）。
- 验证：`browser4-coding` **558** 测试全绿（555 + 3），`failures=0 / errors=0 / skipped=2`
  （跳过项：非 Windows 的 token 契约用例、需真实账户的 opt-in ACL 用例）。**P1 阶段（P1-1..P1-4）全部完成。**
- 遗留：`sandbox=off` 下的越界写入属预期行为（无 OS 隔离）；`windows-acl` 的真实拒绝路径仍需在已 provisioning
  的机器上跑一次 opt-in 用例。`allowNetwork`、`PermissionAudit.CODE_SANDBOX_DENIED` 分别属 P4-1、P3-3。

### P1.5 — 凭据 broker（C2 遗留，可选）

| ID | 工作项 | 改动点 | 验收 |
|---|---|---|---|
| P1.5-1 | 短期 token 签发 | `coding.requestCredential(provider, purpose, ttl)`：向外部 broker 申请短期 token，只注入目标子进程的环境；审计记录用途与有效期 | 长期密钥仍不进 exec 环境；token 过期自动失效 |

**前置**：需要可用的 broker 端点；无端点时保持现状（剥离 + 显式透传），不阻塞 P1/P2。

### P2 — Linux / macOS 强制层（C4）

| ID | 工作项 | 改动点 | 验收 |
|---|---|---|---|
| P2-1 | bwrap 后端 | `BwrapSandbox.prepare`：`--ro-bind / /`、`--bind <workspace>`、`--tmpfs /tmp`、`--unshare-net`（`allowNetwork=false` 时）、`--clearenv` + 白名单 `--setenv`、`--die-with-parent` | 构建 + 单测在沙箱内跑通 |
| P2-2 | nsjail / 容器后端 | 容器方案按任务预热一个容器实例，命令在其中执行；避免每命令 50–200ms 冷启动 | 同一任务内连续命令可用；断开网络时外联被拒 |
| P2-3 | macOS | `sandbox-exec` 策略（deprecated 但可用）；生产建议容器 | 文件写入边界与 Linux 等价 |
| P2-4 | 跨平台测试 | 在 Linux CI 增加沙箱内 `CodingToolsE2ETest`；macOS 标注条件跳过 | 沙箱外写入被 OS 拒绝；`off` 行为不变 |

### P3 — 硬失败、自检与默认开启（C5 + D）

| ID | 工作项 | 改动点 | 验收 |
|---|---|---|---|
| P3-1 | 开关与校验（**已实现**） | `ExecutionSandboxes` 支持 `off` / `windows-acl` / `bwrap` / `container` / `nsjail` / `sandbox-exec`；未知值构造期抛异常 | 非法值启动即失败 |
| P3-2 | 写探针自检（**已实现**） | 沙箱启动时：workspace 内写成功、workspace 外写必须失败；任一不符即硬失败 | 探针失败 = 任务失败（专项测试） |
| P3-3 | 结构化拒绝翻译（**已实现**） | 把 OS `permission denied` / `EACCES` 翻译为 `CODE_SANDBOX_DENIED` + `needed`，不把裸文本交给模型 | 拒绝信封结构化；日志保留原始证据 |
| P3-4 | 默认开启（D1）（**已实现**） | 新安装默认 `windows-acl` / `bwrap`；已有安装保持现状直至显式切换 | 默认配置下完整回归；`off` 分支回归同样全绿 |
| P3-5 | 字符串层降级（D2）（**已实现**） | `browser4.agent.shell.pathGuard` / `allowExternalAccess` 标记 deprecated、语义改为 advisory；文案不再承诺"安全边界" | 文档与拒绝文案一致；基线测试按 advisory 语义更新 |
| P3-6 | 文档（D4）（**已实现**） | `skills/browser4-cli/references/agent.md` 权限章节、`docs/config.md` 开关表 | 开关、默认值、失败语义可查 |

**P3-1 实现记录**（2026-10-31）：

- `ExecutionSandboxes` 新增 `KNOWN_IDS`：`off`、`windows-acl`、`bwrap`、`container`、`nsjail`、`sandbox-exec`。
  `of()` 的三种结果：`off` → `OffSandbox`；`windows-acl` → `WindowsAclSandbox`；其余已知 id →
  `SandboxUnavailableException`（"known but not implemented in this build"，Linux/macOS 后端属 P2）；
  未知值 → `SandboxUnavailableException`（"Unknown sandbox … Known ids: …"）。三类都不回退到无沙箱启动。
- `current()` 统一解析：`configuredId()` 归一化后按 id 走 `ConcurrentHashMap.computeIfAbsent`，同一 id 只解析一次。
  这使 `WindowsAclSandbox` 的按 workspace 账户缓存真正跨命令生效（此前每次 `current()` 新建实例，
  P1-3 记录的"每条命令重复 provisioning"已消除）；`browser4.agent.sandbox` 变化会命中不同的 id key，
  因此运行时切换后端的测试与运维语义不变。
- `validateConfigured()` 在 `CodingComponents` 的 `init` 中调用——该对象是 coding 域的唯一构建点（A5），
  因此非法值在域初始化时即抛异常，而不是等第一条命令才失败。
- 测试：`ExecutionSandboxTest` 增至 9 用例（新增「current() 按 id 解析一次、属性变化重解析」、
  「已知未实现 id 与未知值的两种硬失败文案/`validateConfigured` 直接校验」）；原 `of("bwrap")` 断言
  由 "not available in this build" 更新为 P3-1 的新契约。

**P3-2 实现记录**（2026-10-31）：

- 新增 `sandbox/SandboxProbe.kt`：`verify(workspace, account, outside, runner)` 在 workspace 内写一个
  `.b4-sandbox-probe-<token>.txt`（必须成功且文件存在），再向 workspace 外的目标写同名文件（必须被 OS 拒绝、
  文件不得出现）。两半任一不符即返回失败描述；探针文件在 `finally` 中清理（包括"泄漏"到 workspace 外的那个）。
  子进程输出先读到 EOF 再 `waitFor`（沿用 P1-2 的管道死锁教训），超时 30s。
- 外部目标由 `outsideTarget(workspace)` 选取：优先 `defaultDenyRoots()`（操作者 profile，与实际下发的 deny 根一致），
  否则取 workspace 父目录；连一个 workspace 外目录都找不到时返回 `null`，调用方按探针失败处理（不跳过检查）。
- `WindowsAclSandbox` 增加第二个可注入依赖 `selfCheck`，并在 `prepare` 中按 workspace 用 `verified` 缓存结果：
  首次命令前跑一次探针，失败抛 `SandboxUnavailableException`（消息前缀保持 `Windows sandbox unavailable:`
  + `write probe failed: …`，现有 opt-in 集成测试的"未供给即跳过"逻辑因此继续有效）。
- 测试：新增 `SandboxProbeTest` 5 用例（内写成功/外写被拒通过、内写被拒失败、外写成功失败、退出码 0 但文件缺失失败、
  探针文件清理、外部目标不在 workspace 内）；`ExecutionSandboxTest` 增至 11 用例（新增「探针每 workspace 只跑一次」、
  「探针失败即硬失败」；原 `WindowsAclSandbox { … }` 构造改为显式 `selfCheck`，避免单测触发真实开户）。
- 验证：`browser4-coding` **572** 测试全绿（565 + 7），`failures=0 / errors=0 / skipped=2`。
- 已知边界：探针经 `ProcessLauncher` → `WindowsTokenLauncher` 启动，因此和真实命令一样要求后端具备
  `SeAssignPrimaryToken`/`SeIncreaseQuota` 等特权；缺少特权时探针按硬失败处理（与 P1-2 的契约一致，不回退）。
  探针只验证"内写成功 + 外写被拒"两点，不覆盖只读根的可读性与网络策略（后者属 P4-1）。

**P3-3 实现记录**（2026-10-31）：

- 新增 `sandbox/SandboxDenials.kt`：`isDenial` 识别平台拒绝标记（`Access is denied`、`Permission denied`、
  `Operation not permitted`、`EACCES`、`EPERM`，大小写不敏感，刻意不含单独的 "permission" 以免把普通日志误判）；
  `envelope(tool, command, evidence)` 仅在**活跃沙箱**（`current().id != off`）且命中标记时返回结构化拒绝，
  否则返回 `null`（`off` 下的 `Access is denied` 是普通 OS 错误，不能归因于不存在的边界）。
- 结构化文本复用 `PermissionAudit`：`PERMISSION_DENIED | code=SANDBOX_DENIED | tool=… | detail=… | hint=… |
  needed=…`；`format`/`denied` 新增可选 `needed` 字段（默认 null，向后兼容）。原始证据（sandbox id、
  command、完整子进程输出）走 `logger.info("sandbox denied: …")`，只进日志不进模型上下文。
- 接线两条执行路径：`CodingAgentShell.runCommand` 在退出码非 0 时把 `stderr` 替换为结构化拒绝
  （`executeRaw` 得到同一文本）；`CodeRunner.run` 在进程正常结束且退出码非 0 时同样替换 `stderr`。
  `coding.runCode` 的渲染器无需改动，模型看到的是结构化信封而非裸 `Permission denied`。
- 测试：新增 `SandboxDenialsTest` 5 用例 —— 活跃沙箱下结构化替换（含 `needed=`、不含裸文本）、
  `off` 不翻译、普通构建失败不翻译、审计计数按 `SANDBOX_DENIED` 递增、平台标记识别。
- 验证：`browser4-coding` **565** 测试全绿（558 + 7），`failures=0 / errors=0 / skipped=2`
  （跳过项：非 Windows 的 token 契约用例、需真实账户的 opt-in ACL 用例）。
- 已知边界：检测是"退出码非 0 + 输出含标记"的启发式；被拒绝的路径本身不解析（模型已知它发出的命令）。
  P3-5（字符串层降级）会统一拒绝文案，P3-2（写探针）与 P3-4（默认开启）仍按门禁 G2 等待 P2 平台后端。

**P3-4 实现记录**（2026-10-31）：

- 新增 `sandbox/SandboxDefaults.kt`：`apply(dataDir)` 的判定顺序为
  ① 显式 `-Dbrowser4.agent.sandbox=<id>` 优先（归一化后直接返回，**不**写 marker、不建目录）；
  ② 若 `<dataDir>/sandbox-default` 标记文件已存在，回放其中记录的 id（不重新按文件系统状态推导）；
  ③ 新安装（`dataDir` 尚不存在）取平台默认；已有安装保持 `off`；④ 把决策写入 marker 并发布为系统属性。
  显式值即使为空串/纯空白也视为"未设置"（与 P3-1 的归一化口径一致）。
- 平台默认 `defaultForPlatform(os)` 只在 Windows 返回 `windows-acl`，其余（Linux/macOS）返回 `off`。
  原因：P2 后端尚未实现，而 `ExecutionSandboxes.of()` 对已知未实现 id 是硬失败（P3-1），
  "选一个必然失败的后端"不能作为默认；该映射随 P2 落地后随平台矩阵一起放开。
- **默认化放在应用入口，而非库默认**：`ApiApplication`、`Browser4StandaloneApplication`、
  `Browser4BundleApplication` 三个 main 在 `LlmConfigNormalizer.normalize()` 之后、Spring 启动之前调用
  `SandboxDefaults.apply()`。`ExecutionSandboxes` 自身默认仍为 `off`，因此单测、嵌入式使用方
  不会因缺少 `~/.browser4` 或缺少沙箱账户而被硬失败拖红。
- 数据目录口径与产品其余部分一致：`<browser4.data.dir 或 user.home>/.browser4`（`defaultDataDir()`）。
  marker 是"决策"而非"当前配置"：运维显式改 `-Dbrowser4.agent.sandbox` 时不覆盖它，
  删除 marker 即回到"按安装新旧重新判定"。
- 回退路径：显式 `-Dbrowser4.agent.sandbox=off`（或删除 marker 后对已存在安装重新判定）可完全回到历史行为。
- 测试：新增 `SandboxDefaultsTest` 5 用例 —— 新安装取平台默认并持久化（二次启动不漂移）、
  已有安装保持 `off`、显式值胜出且不持久化/不建目录、空白属性视为未设置、平台映射受限且结果落在 `KNOWN_IDS`。
- 验证：`browser4-coding` **577** 测试全绿（572 + 5），`failures=0 / errors=0 / skipped=2`
  （跳过项：非 Windows 的 token 契约用例、需真实账户的 opt-in ACL 用例）。
  三个应用入口的编译已用 `-Pall-main-modules -pl :browser4-rest,:browser4-standalone,:browser4-bundle -am compile` 验证通过
  （apps 模块只在 `all-main-modules` profile 中，默认 reactor 不包含）。
- **G2 证据受限**：P3-4 的开启条件要求"至少一个平台实测沙箱外写入被 OS 拒绝 + 写探针通过"。
  本机没有 `browser4-agent` 账户，P1-4 的 opt-in ACL 用例按设计跳过，真实"外写被拒"未实测；
  写探针（P3-2）已有 5 个单测与硬失败用例，但真实后端探针同样需要账户。因此：
  默认值实现按 D1 落地且可一键回退，但**在具备沙箱账户的 Windows CI 上复核 G2 前，不应把
  "默认开启已通过端到端验收"写进对外文档**（该表述留给 P3-6 文档项，并在 CI 证据齐备后补齐）。
- G3（`off` 分支回归）：库默认仍是 `off`，本机全量回归即 `off` 分支的完整回归，577 项全绿。
  `browser4-agentic` 同步回归 1335 项（165 套件）：仅 1 项**既有**失败 `CliAgentPromptTest.hygieneFollowsMode`
  （提示词里 `≤`/`→` 非 ASCII 字符导致的文本断言差异，与本阶段改动无关，此前 A6 阶段即有记录）；
  其余 1334 项通过。E2E 套件未在本机运行（需浏览器与真实站点，属 CI 范畴）。

**P3-5 实现记录**（2026-10-31）：

- **语义：advisory，不改行为**。字符串层仍按原样拒绝（快失败、给模型可执行提示、省一次沙箱启动），
  只是文档、代码注释与拒绝文案不再把它描述为安全边界；`pathGuard=off` / `allowExternalAccess=true`
  为兼容继续生效，但文案不再把它们写成"官方绕过入口"。设计与验收口径见设计稿 §4 原则 4 与 §13 M3。
- `CodingAgentShell`：
  - 两个开关的 KDoc 标注 deprecated/advisory，明确"判定不覆盖被注入脚本拼出的路径，强制在 OS 沙箱"；
    `workspaceBoundaryEnabled` 同样说明是 advisory（fast-fail），非强制层。
  - 新增 `ADVISORY_NOTE` 常量，三条拒绝文案（凭据直链、凭据变量、workspace 外路径）统一拼入
    `the path check is advisory — the OS sandbox enforces the boundary when enabled`；
    同时删除原有的 `set -Dbrowser4.agent.shell.pathGuard=off to bypass` 与
    `or set -Dbrowser4.agent.allowExternalAccess=true` 两处"绕过指引"。
  - `PATH_GUARD_PREFIX`（`path guard: `）保持不变，`commandRefusal`/基线按前缀选审计码的逻辑不受影响。
- `CodingAgentFileSystem.unresolvedPath` 的 hint 同步：`allowExternalAccess` 被描述为"已废弃、只放宽该检查，
  OS 沙箱仍强制边界；凭据位置仍然拒绝"，不再作为可选的放宽手段单独出现。
- `AgentAccessPolicy.PROP_ALLOW_EXTERNAL_ACCESS` 的 KDoc 标注 deprecated/advisory。
- `CodingComponents.init`（P3-1 已作为编码域唯一构建点）新增一次性启动告警：
  `pathGuard=off` 与 `allowExternalAccess=true` 各自 warn，说明"deprecated and advisory only，
  强制来自 `browser4.agent.sandbox`"。
- 基线（`permission-decision-baseline.json`）：新增两条按 advisory 语义的冻结项——
  `shell.boundary-survives-pathguard-off`（关掉凭据开关不会放宽 workspace 边界，仍 `COMMAND_PATH_DENIED`）、
  `shell.credential-survives-external-access`（开启外部访问不会放宽凭据拒绝，仍 `COMMAND_PATH_DENIED`）；
  并在文件头说明"基线只比较判定、不跑进程，因此不覆盖沙箱强制本身"。
- 测试：`CodingAgentShellPathGuardTest` 新增 `refusalsAreAdvisory`（拒绝文案含 `advisory` 且不再含
  `pathGuard=off`）；原"protected 子树在凭据开关关闭时仍生效"用例注释按 deprecated/advisory 更新。
- 文档同步：`docs/config.md`（`pathGuard` 标 **deprecated** + advisory；`allowExternalAccess` 补
  "只放宽字符串层、不放宽沙箱"；`workspaceBoundary` 补 advisory 段落）、
  `skills/browser4-cli/references/agent-access.md`、`skills/browser4-cli/references/agent-workspace.md`、
  `skills/browser4-coding/SKILL.md`。
- 验证：`browser4-coding` **578** 测试全绿（577 + 1），`failures=0 / errors=0 / skipped=2`。
- 已知边界：本阶段不改判定结果，因此既有 `agentic` 回归不受影响（未重跑）；设计稿 §13 M3 要求的
  "沙箱启动失败 = 任务失败"已由 P3-1/P3-2 的硬失败实现覆盖。

**P3-6 实现记录**（2026-10-31，纯文档）：

- `skills/browser4-cli/references/agent.md` 权限章节新增 "Two layers, one boundary" 段：
  预设与字符串检查（`pathGuard` / `workspaceBoundary` / `allowExternalAccess`）只决定模型能*拼出*什么，
  属于 advisory fast-fail；强制层是 `browser4.agent.sandbox`。写明新装 Windows 默认 `windows-acl`、
  已有安装与 Linux/macOS 保持 `off`、拒绝翻译为 `code=SANDBOX_DENIED`、不可用即任务失败且无无沙箱回退，
  并指向 `docs/config.md`。
- `docs/config.md` 新增两条独立条目：
  - `browser4.agent.sandbox`：值表（`off` / `windows-acl` / P2 未实现的四个 id）、默认值与判定口径
    （新装 vs 已有安装、marker `<data dir>/sandbox-default`、显式属性优先且不持久化、空白视为未设置、
    `off` 即回滚）、失败语义（账户/凭据/ACL/写探针/非法 id → `SandboxUnavailableException`，
    沙箱内拒绝 → `PERMISSION_DENIED | code=SANDBOX_DENIED | needed=…`，`off` 时裸拒绝仍是普通 OS 错误）；
  - `browser4.agent.sandbox.account`：默认 `browser4-agent`、凭据来源（Credential Manager
    `browser4:<account>` → DPAPI 文件 `%LOCALAPPDATA%\browser4\sandbox\agent.cred`）、缺失即硬失败。
- `docs/config.md` 新增 "Agent permission switches at a glance" 汇总表（8 个开关：sandbox、sandbox.account、
  access、autoApprove、workspaceBoundary、pathGuard、allowExternalAccess、allowDestructive），
  逐项给出默认值与失败语义，满足验收"开关、默认值、失败语义可查"。
- 一致性修订：`agent-access.md` 的拒绝码目录与 Errors 表补 `SANDBOX_DENIED`（含 `needed` 说明）；
  "Two limits" 中"预设不是 OS 沙箱"改为指向 `browser4.agent.sandbox` 后端，保持与 P3-5 文案一致。
- 合规约束：按 P3-4 记录的要求，文档只描述"默认值与失败语义"，未把"默认开启已通过端到端验收"写成事实
  （G2 仍待有账户的 Windows CI 复核）。
- 验证：纯文档变更，未改代码，沿用 P3-5 的 `browser4-coding` 578 全绿结果；文档内链接沿用仓库相对路径。
- **P3 阶段（P3-1…P3-6）至此全部实现**，后续进入 P4（审批取代开闸）。

### P4 — 审批取代开闸（E）

| ID | 工作项 | 改动点 | 验收 |
|---|---|---|---|
| P4-1 | `coding.requestAccess`、非阻塞 pending 与取消 autoApprove（**已实现**，记录见下） | 参数 `effect/target/reason/ttl`；立即返回 `{requestId, state:"pending", needed, alternatives}`，不阻塞 loop；同一 effect+target 去重；三条通道统一签发 `source=OPERATOR` 的 cap；惰性过期；删除 `autoApproveUpTo` 与 `--auto-approve`，无人值守默认 `full`（`allowNetwork=true`，出站不限域名，exec 仍受 `DEV_COMMANDS` 白名单） | 申请/批准/拒绝/过期进审计并带来源；护栏不可越；无人值守默认 `full` 生效且 TTY 下不套用；`access.unattended` 可覆盖 |
| P4-2 | 删除 B3/B4 例外 | 移除 `isLauncherReference` 外部路径放行与 `toolchainRoots` 整片豁免，改为 P0 清单里的显式根 | 原豁免用例改写为显式根用例 |
| P4-3 | 拒绝文案收敛（**已实现**，记录见下） | 去掉 JVM 开关提示，改为结构化 `needed` + 审批入口 | 文案只描述"需要什么" |
| P4-4 | CLI 与策略文件形态（E4）（**已实现**，记录见下） | 按决策 #3：`-i` 增加 `/access`、`/approve`、`/deny`（本地处理，不进模型上下文）；非 TTY 只打印 pending 摘要；策略文件按 effect+target+ttl 预批准 | CLI 与策略文件各一条端到端用例；非 TTY 不阻塞 |

#### P4-1 实现记录（2026-10-31，已实现）

- 新增 `AccessApprovals`（`browser4-coding`）：`request(effect, target, reason, ttlSeconds)` 立即返回
  `AccessRequest(id, state=pending, effect, target, needed, alternatives)`，不阻塞调用方；同一
  `effect|target 类型|describe()` 去重（已批准且 grant 活跃时复用，过期/拒绝后才允许重新申请）。
- 审批收敛到预设门禁：`AgentAccessPolicy.operatorGrants` 是 live supplier，`allowsTool` /
  `allowsExec` / `allowsFileMutation` / `denial` 统一读取；`CodingAgentFileSystem.readOnlyProvider`
  与 `CodingAgentShell.allowExecutionProvider` 让组件层同步看到审批状态，避免"门禁放行、组件仍拒"。
- 审批为 **effect 级**：`fs.write` 解锁文件变更（与 build），`fs.exec` / `proc.spawn` 解锁 shell；
  target 只进审计与 grant scope，不宣称逐 target 强制。
- 护栏不可越：`guardrailViolation` 在批准时检查 `denyTools`、受保护/敏感路径、
  `allowExternalAccess=false` 的 workspace 边界与 `DEV_COMMANDS ∪ SAFE_COMMANDS` 命令白名单；
  越轨审批被转成 denial（REST 返回 409）。
- 三通道统一签发 `Capability(source=OPERATOR, expiresAt=…)`；本轮落地共享签发 API + REST
  （`GET/POST /api/agent/<taskId>/access[/<requestId>/approve|deny]`）+ `coding.requestAccess` 工具；
  CLI 斜杠命令与策略文件留到 P4-4。
- 惰性过期：`activeCapabilities()` / `describe()` 在调用时丢弃过期 grant 并记一次 `ACCESS_EXPIRED`，
  不引入后台定时器。
- 审计码 `ACCESS_REQUESTED / ACCESS_APPROVED / ACCESS_DENIED / ACCESS_EXPIRED`，每条带来源
  （本轮 `rest` / `default`；`file` / `cli` 预留给 P4-4）。
- 删除 `autoApproveUpTo` 全链路：`AgentAccessPolicy` 字段与 `browser4.agent.autoApprove`、
  CLI `--auto-approve`、`command.run(autoApprove=…)`、`AUTO_APPROVED` 事件及对应用例。
- 无人值守默认：`AgentAccessPolicy.unattendedDefault` 默认 `full` + `allowNetwork=true`，
  `browser4.agent.access.unattended` 可覆盖为 `deny` / `read` / `read-build` / `write` / `full`；
  `command.run(unattended=false)`（TTY 的 `agent run -i`）沿用服务端 `browser4.agent.access`，
  派生默认记 `ACCESS_APPROVED(source=default)`。Rust CLI 用 `io::stdin().is_terminal()` 传
  `Some(!tty)`，一次性 `agent run` 省略该键（后端默认 `true`）。
- `coding.permissions` 新增 "Operator approval" 块（pending、生效 grant、已安装通道），
  沙箱行改为动态读取 `ExecutionSandboxes.current().id`。
- 测试：`AgentAccessPolicyTest`（grant 只放宽其 effect、不越 `denyTools`、惰性过期、无人值守默认、
  task override）、`CodingAccessEnforcementTest`（审批穿透 FS 与 shell、越轨审批转 denial、
  permissions 报告、审计）、`CliAgentPromptTest`（prompt 中的 Operator approval）、
  `CommandToolExecutorTest`（`unattended` 透传与默认）、Rust e2e（`unattended` 省略/置位）。
- 验证：`mvnw -o -q -pl browser4-coding,browser4-agentic,browser4-agent-tools,browser4-rest -am
  "-DskipTests" compile` 通过；`cargo check --tests`（`cli/browser4-cli`）通过；
  `AgentAccessPolicyTest` / `AccessApprovalsTest` / `CliAgentPromptTest` /
  `CodingAccessEnforcementTest` / `CodingToolExecutorTest` / `CommandToolExecutorTest` 全绿；
  Rust e2e `cargo test --test e2e -- --scenario '*unattended*'` 2 passed。

#### P4-3 实现记录（2026-10-31，已实现）

- 模型可见的拒绝文本不再出现任何 JVM/CLI 开关建议：`access=full` / `access=write` /
  `-Dbrowser4.agent.*` / "re-run with …" 全部移除；文案只描述被拒绝的事实（detail）、
  需要什么（`needed=<effect.id>`）与审批入口（hint 指向 `coding.requestAccess`，
  非阻塞、立即返回 pending requestId）。
- 结构化 `needed` 接线到每条预设拒绝：`AgentAccessPolicy.denial()` 按 required preset
  映射 `fs.read`/`fs.exec`/`fs.write`，`mutationDenial()` → `fs.write`；
  `CodingAgentShell.execDisabledRefusal()` 与 `COMMAND_NOT_ALLOWED` 分支 → `fs.exec`；
  `CodingAgentFileSystem.mutationViolation()` → `fs.write`。`needed` 复用 P3-3
  `PermissionAudit.denied/format` 的可选参数，格式 `needed=<effect.id>`，
  与 `coding.requestAccess(effect=…)` 的参数值一致。
- 无法靠审批跨越的边界不提供 `needed`：`PATH_OUTSIDE_WORKSPACE` / `SENSITIVE_PATH` /
  `PROTECTED_PATH` / `denyTools` 与 `b4.run` 直通 MCP 守卫，hint 只说允许的替代路径
  （"use the gated tools instead"、"use a path inside the workspace"）。
- 其余开关提示清理：`WorkspaceEscapeException` 与 `b4.run` 外部路径拒绝去掉
  `-Dbrowser4.agent.allowExternalAccess=true` 尾巴；`OUT_OF_SCOPE_WRITE` 审计事实改为
  "allowed only because external access is enabled for this task"。
- 测试：`AgentAccessPolicyTest.testRefusalTextIsConverged`（`needed=` 在位、无
  `access=*`/`re-run`/`-Dbrowser4` 字样）、`CodingAccessEnforcementTest.testReadPresetDeniesWrite`
  （端到端同一断言）、`B4CliPolicyGuardTest` 的出路断言从 `access=full` 改为 `gated tools`、
  `B4CliExternalPathTest` 改断言 `external access is disabled` 且无 `-Dbrowser4`。
- 验证：`browser4-coding` 全模块回归 exit 0；`browser4-agentic` `tools.builtin` 包
  295 用例 exit 0（含 `AgentAccessPolicyTest` / `AccessApprovalsTest` /
  `CodingAccessEnforcementTest` / `B4CliPolicyGuardTest` / `B4CliExternalPathTest` /
  `CodingAgentShellTest` / `CodingAgentFileSystemTest` / `SandboxDenialsTest` /
  `PermissionsConsistencyTest`）。

#### P4-4 实现记录（2026-10-31，已实现）

- 策略文件通道（`browser4-coding`）：新增 `AccessPolicyFile`（`<workspace>/.browser4/access-policy.json`，
  `{"grants": [{effect, target, ttlSeconds?, reason?}]}`；缺文件=无预批准、坏 JSON/缺 `grants` 数组/
  坏条目仅告警跳过）。`AccessApprovals.loadPolicyFile()` 把每个条目走 `request()` +
  `approve(source=FILE)`——与手工审批同一队列、同一审计、同一护栏：越轨条目（workspace 外、
  `.browser4` 等受保护目录、非白名单命令）转 denial 并审计，绝不静默生效。`target` 按 effect
  解释：`fs.exec`/`proc.spawn` → 命令行，`net.connect` → host，其余按相对 workspace 的路径解析。
  `.browser4` 本身是受保护目录，任务无法改写自己的权限文件。
- `AccessApprovals.request()` 新增 `requestedBy` 参数（默认 REST），审计详情里的 `source=` 不再
  硬编码；`AccessRequest.requestedBy` 随请求落档。
- 接线：`AgentToolManager.applyAccessPolicy` 在建好审批队列与 effective policy 后、首次工具调用前
  调 `loadPolicyFile()`，无人值守任务不等操作员。
- CLI 通道（`cli/browser4-cli`）：`agent run -i` 新增 `/access [task-id]`、`/approve <request-id>
  [task-id]`、`/deny <request-id> [task-id]`，由会话本地处理、直打 REST 端点，不进模型上下文；
  任务 id 缺省取当前/上一任务。2xx 与 409 都按 decision 渲染（服务端 deny 恒回 409 的既有行为），
  404 给出友好提示。非 TTY 会话结束时打印一次 pending 摘要（有 pending 才打印，任何失败静默），
  不提示、不阻塞。`/help` 同步三行。
- 三通道至此齐了：REST / CLI / 策略文件统一签发 `Capability(source=OPERATOR)`，审计来源
  `rest` / `cli` / `file`（`default` 为无人值守派生）。
- 测试：`AccessPolicyFileTest`（缺文件、两条目预批准+source=file+ttl→expiry、越 workspace 边界转
  denial 并审计、`.browser4` 受保护目录不可预批准、坏 JSON/坏条目跳过）；
  `CodingAccessEnforcementTest.testStrategyFilePreApproves`（策略文件端到端：read 预拿下
  fs.write 预批准后 write 穿透、shell 仍拒——策略文件端到端用例）；Rust 单测
  `format_pending_summary` 两条；Rust e2e `test_e2e_agent_run_interactive_slash_access_commands`
  （显式任务 id 打 mock 的 GET/approve/deny，断言渲染与 409 决策——CLI 端到端用例，非 TTY
  不阻塞由摘要只在结束后打印、无任何等待保证）。
- 验证：`mvnw -o -q -pl browser4-coding,browser4-agentic -am
  "-Dtest=AccessPolicyFileTest,AccessApprovalsTest,AgentAccessPolicyTest,CodingAccessEnforcementTest"
  "-Dsurefire.failIfNoSpecifiedTests=false" test` exit 0；`cargo check --tests` 通过；
  `cargo test --bin browser4-cli` 1980 passed；e2e `test_e2e_agent_run_interactive_*` 6/6 ok。

## 4. 顺序与门禁

```
P0-1 ──► P0-2 ──► P0-3
          │
P1-1 ──► P1-2 ──► P1-3 ──► P1-4 ──► P3-1/P3-2 ──► P3-4
P2-1 ──► P2-2 ──► P2-3 ──► P2-4 ─────┘            │
P1.5（可选，不阻塞）                              ▼
                                            P3-3 ──► P3-5 ──► P3-6
                                                  P4-1 ──► P4-2 ──► P4-3 ──► P4-4
```

门禁：

- **G0**：P0-1 完成（≥ 一周观测 + scope 清单），否则不进入 P4-2（删除豁免需要清单兜底）。
  当前状态：**未满足**（P0-1 未开始）→ P4-2 阻塞；P1/P2 不受 G0 约束。
- **G1**：决策 #2 已满足（2026-10-31）：后端驱动沙箱外 Chrome，P1-2 按原表定形态，无需回环通道。
- **G2**：P1-4 或 P2-4 至少一个平台通过"沙箱外写入被 OS 拒绝"，且 P3-2 写探针通过，才允许 P3-4 默认开启。
  当前状态：**部分满足**（2026-10-31）。默认开启机制（P3-4）已实现且可回退，但本机无 `browser4-agent`
  账户，P1-4 的 opt-in ACL 用例被跳过，真实外写拒绝未实测 → 需在具备沙箱账户的 Windows CI 上复核后才算关闭。
- **G3**：P3-4 后无 JVM flag 分支完整回归通过（含 `browser4-coding`、`browser4-agentic`、E2E）。
- **G4**：P4-1 的审批通道与审计落地后，才允许 P4-2 删除 B3/B4。

## 5. 测试矩阵（剩余部分）

| 层 | 测试 | 位置 | 覆盖 |
|---|---|---|---|
| 单元 | `CodingScopeManifestTest`（新增） | `browser4-coding/src/test/.../coding/` | 清单与 grant 一致、toolchainRoots 单源、收窄建议 |
| 单元 | `SandboxEnvironmentTest`（由 `ExecutionSandboxTest` 对齐改名） | `browser4-coding/src/test/.../coding/sandbox/` | 白名单、密钥剥离、显式透传审计 |
| 单元 | `WindowsSandboxTest` | 同上 | 账户缺失/授权失败硬失败；token 启动失败结构化异常 |
| 单元 | `SandboxProbeTest` | 同上 | 写探针：内写成功、外写失败、失败即抛 |
| 集成 | `CodingToolExecutorIsolationTest`（扩展） | `browser4-agentic/src/test/.../builtin/` | 预设 × 工具的端到端拒绝 |
| E2E | `CodingToolsE2ETest`（扩展） | `browser4-coding/src/test/.../coding/` | 沙箱内构建 + 单测 + 文件工具全链路 |
| Windows 专项 | `CodingAgentShellWindowsBuiltinTest`（扩展） | 同上 | `cmd /c`、`pwsh -Command`、ACL 拒绝 |
| 审计 | `PermissionsConsistencyTest`（新增） | `browser4-agentic/src/test/.../builtin/` | `permissions` 输出与 `PermissionAudit.snapshot()` 一致 |
| 审批 | `RequestAccessTest`（新增） | `browser4-coding/src/test/.../coding/` | pending 去重、三条通道各自生效、护栏不可越、惰性过期、无人值守默认 `full`、`access.unattended` 覆盖 |

## 6. 待关闭的决策

| # | 决策 | 状态 | 阻塞 |
|---|---|---|---|
| 2 | 沙箱内 Chrome 由谁启动（后端驱动沙箱外 Chrome / 沙箱内 Chrome + CDP 通道） | **已定（2026-10-31）：后端驱动沙箱外 Chrome；`b4.run` 不纳入沙箱（唯一显式豁免）** | —（P1-2 无需回环通道；记录见 v1 §6.1） |
| 3 | `coding.requestAccess` 形态（交互式确认 / 预置策略文件） | **已定（2026-10-31）：混合三通道（策略文件 / REST / CLI 斜杠命令）+ 非阻塞 pending** | —（记录见 v1 §6.2） |
| 4 | 无人值守是否保留 `autoApproveUpTo` | **已定（2026-10-31）：取消；无人值守默认统一为 `full`（含 exec 与出站网络）** | —（记录见 v1 §6.3） |
| 1 | Windows 默认强制方式（低权用户 + ACL） | 已定 | — |
| 5 | 镜像模块写 scope（`MirrorRoots` 自动派生） | 已解决（A4） | — |

## 7. 风险与回滚

| 风险 | 触发点 | 缓解 | 回滚 |
|---|---|---|---|
| ACL 配错导致 `~/.m2` / `~/.gradle` 写失败 | P1-3 | P0 清单先行 + P3-2 写探针；toolchain 根显式列根 | `sandbox=off`（仍保持字符串层） |
| 沙箱账户令牌不可用 / 权限不足 | P1-1/P1-2 | 启动期探测并存证；硬失败不降级 | 同上 |
| `b4.run` 成为沙箱唯一旁路（不执行任意命令，但可驱动浏览器） | P1/P2 | 显式豁免 + 文档与 `coding.permissions` 标注；后续可按 `localhost:18182` 主机白名单收紧 | 恢复纳入沙箱（需网络策略） |
| 每命令容器冷启动 50–200ms | P2-2 | 每任务预热一个容器实例 | `sandbox=bwrap`（Linux 本地） |
| OS 拒绝裸文本污染模型上下文 | P3-3 | 翻译为 `CODE_SANDBOX_DENIED` + `needed` | 保留原始日志，回退文案 |
| 默认开启后旧环境构建失败 | P3-4 | 只对新安装默认开启；已有安装保持现状 | 配置回 `off` |
| 无人值守默认 `full`（命令与出站网络不设上限） | P4-1 | `DEV_COMMANDS` 白名单仍生效；P3-4 沙箱先于 P4；`NET_CONNECT(default)` 逐条审计；首行展示 `unattended default: full`；`access.unattended` 可收紧 | 设 `access.unattended=write` 或 `deny` |
| pending 无人处理导致任务反复失败 | P4-1 | 任务结束前汇总 pending 并给 workspace 内替代方案；无人值守用策略文件兜底 | 设 `access.unattended=write` 或 `deny` |
| 删除 B3/B4 后合法工作流被拒 | P4-2 | G0 清单 + 显式根；先告警一个观测周期 | 恢复豁免（保留旧代码开关） |

## 8. 明确不做

- 不引入策略 DSL；`Effect` 保持封闭枚举。
- 不为「沙箱内 Chrome」新增端口桥接或 CDP 反向通道（决策 #2，2026-10-31）。
- 不把 `b4.run` 改道进沙箱；它是唯一显式豁免。
- 不保留第二套自动放宽路径（`autoApproveUpTo` 已由决策 #4 取消）。
- 不在 P3 之前删除任何现有守卫；字符串层与 OS 层并存以便对比。
- 不把字符串守卫包装成"安全边界"写进文档。
- 不为沙箱引入包身份（AppContainer 留作后续加固，不在本计划内）。
